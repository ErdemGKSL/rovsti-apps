//! Media rows: the v5 migration, the download queue, the cap and its exemptions, the reconcile at open, the
//! numbers for settings, and rows that follow a merge.

use rosty_core::wa_identity::Source;
use rosty_wa_proto::{GatewayEvent, MediaKind, MediaMeta, MessageBody, MessageMeta, WaMessage};
use rusqlite::Connection;

use super::identity_tests::{apply, columns, count, message, meta_event, store, temp_dir, text};
use super::media::{DEFAULT_CAP_BYTES, DOWNLOAD_LEASE_MS, MAX_ATTEMPTS, RECENT_USE_MS};
use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const ALI_LID: &str = "777000000000001@lid";
const DAY: i64 = 24 * 60 * 60 * 1000;
/// "Now" of these tests; files used at or before `NOW - DAY` are old.
const NOW: i64 = 10 * DAY;
const WIFI: Conditions = Conditions {
    network: Network::Wifi,
    free_bytes: None,
};
const MOBILE: Conditions = Conditions {
    network: Network::Mobile,
    free_bytes: None,
};

fn media_msg(chat: &str, id: &str, ts: i64, kind: MediaKind, size: u64) -> GatewayEvent {
    message(WaMessage {
        body: MessageBody::Media {
            kind,
            caption: None,
            mime: "application/octet-stream".into(),
            size,
        },
        ..text(chat, id, ts, "")
    })
}

fn flags(chat: &str, id: &str, voice_note: bool, view_once: bool) -> GatewayEvent {
    meta_event(
        chat,
        id,
        MessageMeta {
            media: Some(MediaMeta {
                voice_note,
                view_once,
                ..MediaMeta::default()
            }),
            ..MessageMeta::default()
        },
    )
}

/// A photo, a video, a voice note, a sticker, a document, a view-once photo and a text, all from Ali.
fn mixed() -> Store {
    let mut store = store();
    apply(
        &mut store,
        vec![
            media_msg(ALI, "photo", 1_000, MediaKind::Image, 1_000),
            media_msg(ALI, "video", 2_000, MediaKind::Video, 9_000),
            media_msg(ALI, "voice", 3_000, MediaKind::Audio, 500),
            flags(ALI, "voice", true, false),
            media_msg(ALI, "sticker", 4_000, MediaKind::Sticker, 100),
            media_msg(ALI, "doc", 5_000, MediaKind::Document, 2_000),
            media_msg(ALI, "once", 6_000, MediaKind::Image, 800),
            flags(ALI, "once", false, true),
            message(text(ALI, "words", 7_000, "hello")),
        ],
    );
    store
}

/// `n` photos of 1 000 bytes each, `P1` to `Pn`, each cached and last used at `NOW - 2 * DAY + n`.
fn photos(n: u64) -> Store {
    let mut store = store();
    let events = (1..=n)
        .map(|i| {
            media_msg(
                ALI,
                &format!("P{i}"),
                i as i64 * 1_000,
                MediaKind::Image,
                1_000,
            )
        })
        .collect();
    apply(&mut store, events);
    for i in 1..=n {
        let id = format!("P{i}");
        store
            .set_media_file(
                ALI,
                &id,
                &format!("/m/{id}.jpg"),
                1_000,
                NOW - 2 * DAY + i as i64,
            )
            .unwrap();
    }
    store
}

fn state(store: &mut Store, id: &str) -> MediaState {
    store.media(ALI, id, None).unwrap().unwrap().media.state
}

fn paths(eviction: &Eviction) -> Vec<&str> {
    eviction
        .evicted
        .iter()
        .map(|file| file.path.as_str())
        .collect()
}

fn ready(path: &str, bytes: u64) -> Fetched {
    Fetched::Ready {
        path: path.into(),
        bytes,
        sha256: None,
    }
}

fn send_file(file: &str, size: u64) -> Action {
    Action::SendMedia {
        file: file.into(),
        media: MediaKind::Image,
        mime: "image/jpeg".into(),
        size,
        caption: None,
        quoted_id: None,
        file_name: None,
        seconds: None,
        voice_note: false,
        extras: MediaExtras::default(),
    }
}

fn failed() -> Fetched {
    Fetched::Failed {
        error: Some("timeout".into()),
    }
}

// ---- migration

#[test]
fn a_version_four_file_moves_its_cached_media_into_media_rows() {
    let dir = temp_dir("media-v5");
    let path = dir.join(file_name("inst1"));
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate_to(&mut conn, 4).unwrap();
        conn.execute_batch(&format!(
            "INSERT INTO sync_state (id, instance_id, last_seq, needs_resync, identity_backfill)
                VALUES (1, 'inst1', 7, 0, 'done');
             INSERT INTO chats (jid, name) VALUES ('{ALI}', 'Ali');
             INSERT INTO messages (chat, id, from_me, ts, kind, mime, size, thumb, media_path, media_bytes,
                    media_used_ms, starred) VALUES
                ('{ALI}', 'A', 0, 1000, 'image', 'image/jpeg', 2048, x'aabb', '/m/A.jpg', 2048, 500, 1),
                ('{ALI}', 'B', 0, 2000, 'video', 'video/mp4', 9000, NULL, '/m/B.mp4', NULL, NULL, 0),
                ('{ALI}', 'C', 0, 3000, 'image', 'image/jpeg', 100, x'cc', NULL, NULL, NULL, 0),
                ('{ALI}', 'D', 0, 4000, 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0);"
        ))
        .unwrap();
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    let opened = store.opened().unwrap();
    assert_eq!(
        (opened.schema_version, opened.last_seq, opened.needs_resync),
        (SCHEMA_VERSION, Some(7), false)
    );
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM media"), 2);
    let a = store.media(ALI, "A", None).unwrap().unwrap();
    assert_eq!(a.thumbnail.as_deref(), Some(&[0xaa, 0xbb][..]));
    assert_eq!(
        (
            a.media.state,
            a.media.path.as_deref(),
            a.media.bytes,
            a.media.used_ms
        ),
        (MediaState::Ready, Some("/m/A.jpg"), Some(2048), Some(500))
    );
    assert_eq!(
        (a.media.kind, a.media.size_expected, a.media.mime.as_deref()),
        (Kind::Image, Some(2048), Some("image/jpeg"))
    );
    // A size the old row never recorded counts as nothing, not as unknown.
    let b = store.media(ALI, "B", None).unwrap().unwrap().media;
    assert_eq!((b.state, b.bytes), (MediaState::Ready, Some(0)));
    assert_eq!(state(&mut store, "C"), MediaState::None);
    assert_eq!(store.media_bytes().unwrap(), 2048);
    // The thread reads the file from the media row; the old columns are empty and their index is gone.
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert_eq!(row.media_path.as_deref(), Some("/m/A.jpg"));
    assert_eq!(row.media_state, Some(MediaState::Ready));
    assert!(row.has_thumbnail && row.starred);
    assert_eq!(store.message(ALI, "C").unwrap().unwrap().media_state, None);
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM messages WHERE media_path IS NOT NULL OR media_bytes IS NOT NULL
                OR media_used_ms IS NOT NULL"
        ),
        0
    );
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM sqlite_master WHERE name = 'messages_cached_media'"
        ),
        0
    );
    for column in ["epoch", "account_epoch", "history_json", "media_cap_bytes"] {
        assert!(
            columns(&store.conn, "sync_state").contains(&column.to_owned()),
            "{column}"
        );
    }
    assert_eq!(store.media_cap().unwrap(), Some(DEFAULT_CAP_BYTES));
    drop(store);
    // Opening again migrates nothing.
    let store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM media"), 2);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_version_three_file_upgrades_through_four_to_five() {
    let dir = temp_dir("media-v3");
    let path = dir.join(file_name("inst1"));
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate_to(&mut conn, 3).unwrap();
        conn.execute_batch(&format!(
            "INSERT INTO sync_state (id, instance_id, last_seq, needs_resync) VALUES (1, 'inst1', 3, 0);
             INSERT INTO chats (jid) VALUES ('{ALI_LID}');
             INSERT INTO messages (chat, id, sender, from_me, ts, kind, size, media_path, media_bytes,
                    media_used_ms) VALUES
                ('{ALI_LID}', 'A', '{ALI_LID}', 0, 1000, 'image', 10, '/m/A.jpg', 10, 5);
             INSERT INTO outbox (client_id, chat, kind, payload, created_ms) VALUES
                ('c1', '{ALI_LID}', 'send_text', '{{\"kind\":\"send_text\",\"text\":\"hi\"}}', 900);"
        ))
        .unwrap();
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(store.opened().unwrap().schema_version, SCHEMA_VERSION);
    assert_eq!(
        store
            .message(ALI_LID, "A")
            .unwrap()
            .unwrap()
            .media_path
            .as_deref(),
        Some("/m/A.jpg")
    );
    assert_eq!(store.outbox().unwrap().len(), 1);
    // What version four added works on the row version five moved: the media follows the merge.
    store
        .identity_learn(ALI_LID, ALI, Source::Usync, 2_000)
        .unwrap();
    let moved = store.media(ALI, "A", None).unwrap().unwrap().media;
    assert_eq!((moved.chat.as_str(), moved.state), (ALI, MediaState::Ready));
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_build_of_schema_four_would_refuse_this_file() {
    // The rule every build applies: a file with a higher version than it knows is left alone. A build that
    // knows up to four sees five the way this one sees the next.
    let dir = temp_dir("media-newer");
    drop(Store::open(&dir, "inst1").unwrap());
    let path = dir.join(file_name("inst1"));
    let conn = Connection::open(&path).unwrap();
    assert_eq!(schema::version(&conn).unwrap(), SCHEMA_VERSION);
    conn.pragma_update(None, "user_version", SCHEMA_VERSION + 1)
        .unwrap();
    drop(conn);
    assert!(matches!(
        Store::open(&dir, "inst1"),
        Err(Error::NewerSchema { found, supported })
            if found == SCHEMA_VERSION + 1 && supported == SCHEMA_VERSION
    ));
    let conn = Connection::open(&path).unwrap();
    assert_eq!(schema::version(&conn).unwrap(), SCHEMA_VERSION + 1);
    assert_eq!(count(&conn, "SELECT COUNT(*) FROM media"), 0);
    let _ = std::fs::remove_dir_all(dir);
}

// ---- the queue

#[test]
fn the_rules_decide_what_an_automatic_request_queues() {
    use Decision::{Auto, Never, OnTap};
    let cases = [
        // (id, on Wi-Fi, on mobile data)
        ("photo", Auto, Auto),
        ("voice", Auto, Auto),
        ("sticker", Auto, OnTap),
        ("video", OnTap, OnTap),
        ("doc", OnTap, OnTap),
        ("once", Never, Never),
        ("words", Never, Never),
    ];
    for (conditions, column) in [(WIFI, 0), (MOBILE, 1)] {
        let mut store = mixed();
        for (id, wifi, mobile) in cases {
            let wanted = [wifi, mobile][column];
            assert_eq!(
                store.media_policy(ALI, id, &conditions).unwrap(),
                wanted,
                "{id}"
            );
            let queued = store
                .media_enqueue(ALI, id, Fetch::Auto(conditions), NOW)
                .unwrap();
            assert_eq!(
                (queued.decision, queued.queued),
                (wanted, wanted == Auto),
                "{id}"
            );
            let wanted_state = if wanted == Auto {
                MediaState::Queued
            } else {
                MediaState::None
            };
            assert_eq!(queued.media.state, wanted_state, "{id}");
        }
    }
    // Roaming, Data Saver, no network and low space queue nothing by themselves.
    let low = Conditions {
        network: Network::Wifi,
        free_bytes: Some(FREE_FLOOR - 1),
    };
    for conditions in [
        Conditions::on(Network::Roaming),
        Conditions::on(Network::DataSaver),
        Conditions::on(Network::None),
        low,
    ] {
        let mut store = mixed();
        for (id, ..) in cases {
            let queued = store
                .media_enqueue(ALI, id, Fetch::Auto(conditions), NOW)
                .unwrap();
            assert!(!queued.queued, "{id} under {conditions:?}");
        }
        assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM media"), 0);
    }
    assert!(matches!(
        mixed().media_enqueue(ALI, "nope", Fetch::Tap, NOW),
        Err(Error::NotFound(_))
    ));
}

const FREE_FLOOR: u64 = media::FREE_SPACE_FLOOR;

#[test]
fn a_tap_queues_what_the_rules_hold_back_also_view_once_but_never_a_text() {
    let mut store = mixed();
    // View once is never fetched by itself; the tap that opens it fetches it (`media_round_tests`).
    for id in ["video", "doc", "photo", "once"] {
        let queued = store.media_enqueue(ALI, id, Fetch::Tap, NOW).unwrap();
        assert!(queued.queued && queued.media.by_tap, "{id}");
        assert_eq!(queued.decision, Decision::OnTap);
    }
    let queued = store.media_enqueue(ALI, "words", Fetch::Tap, NOW).unwrap();
    assert_eq!((queued.queued, queued.decision), (false, Decision::Never));
    // A deleted message is not fetched by itself; a tap still may.
    apply(
        &mut store,
        vec![meta_event(
            ALI,
            "voice",
            MessageMeta {
                deleted: true,
                media: Some(MediaMeta {
                    voice_note: true,
                    ..MediaMeta::default()
                }),
                ..MessageMeta::default()
            },
        )],
    );
    assert!(
        !store
            .media_enqueue(ALI, "voice", Fetch::Auto(WIFI), NOW)
            .unwrap()
            .queued
    );
    assert!(
        store
            .media_enqueue(ALI, "voice", Fetch::Tap, NOW)
            .unwrap()
            .queued
    );
}

#[test]
fn due_hands_out_taps_first_then_the_order_queued_and_settle_records_the_file() {
    let mut store = mixed();
    store
        .media_enqueue(ALI, "photo", Fetch::Auto(WIFI), NOW)
        .unwrap();
    store
        .media_enqueue(ALI, "sticker", Fetch::Auto(WIFI), NOW + 1)
        .unwrap();
    store
        .media_enqueue(ALI, "video", Fetch::Tap, NOW + 2)
        .unwrap();
    let due = store.media_due(NOW + 3, Some(2), Some(&WIFI)).unwrap();
    let ids: Vec<&str> = due.items.iter().map(|item| item.id.as_str()).collect();
    assert_eq!(ids, ["video", "photo"]);
    assert!(
        due.items
            .iter()
            .all(|item| item.state == MediaState::Downloading)
    );
    // What is out is not handed out twice; the rest follows.
    let next = store.media_due(NOW + 4, None, Some(&WIFI)).unwrap();
    assert_eq!(next.items.len(), 1);
    assert_eq!(next.items[0].id, "sticker");
    assert_eq!(next.next_wake_ms, Some(NOW + 3 + DOWNLOAD_LEASE_MS));
    assert!(
        store
            .media_due(NOW + 5, None, None)
            .unwrap()
            .items
            .is_empty()
    );
    assert_eq!(
        store.message(ALI, "video").unwrap().unwrap().media_state,
        Some(MediaState::Downloading)
    );

    let settled = store
        .media_settle(
            ALI,
            "video",
            &Fetched::Ready {
                path: "/m/video.mp4".into(),
                bytes: 9_100,
                sha256: Some("ab12".into()),
            },
            NOW + 10,
        )
        .unwrap();
    assert_eq!(settled.replaced, None);
    let media = settled.media;
    assert_eq!(
        (
            media.state,
            media.path.as_deref(),
            media.bytes,
            media.sha256.as_deref(),
            media.used_ms,
            media.size_expected
        ),
        (
            MediaState::Ready,
            Some("/m/video.mp4"),
            Some(9_100),
            Some("ab12"),
            Some(NOW + 10),
            Some(9_000)
        )
    );
    assert_eq!(
        store
            .message(ALI, "video")
            .unwrap()
            .unwrap()
            .media_path
            .as_deref(),
        Some("/m/video.mp4")
    );
    // A cached file is not queued again, and another file for the row names the old one for deletion.
    let again = store
        .media_enqueue(ALI, "video", Fetch::Tap, NOW + 11)
        .unwrap();
    assert_eq!(
        (again.queued, again.media.state),
        (false, MediaState::Ready)
    );
    let replaced = store
        .media_settle(ALI, "video", &ready("/m/video-2.mp4", 9_100), NOW + 12)
        .unwrap();
    assert_eq!(replaced.replaced.as_deref(), Some("/m/video.mp4"));
    assert!(matches!(
        store.media_settle(ALI, "nope", &ready("/x", 1), NOW),
        Err(Error::NotFound(_))
    ));
}

#[test]
fn a_failed_download_backs_off_then_fails_for_good_until_a_tap() {
    let mut store = mixed();
    store
        .media_enqueue(ALI, "photo", Fetch::Auto(WIFI), NOW)
        .unwrap();
    let mut now = NOW;
    for attempt in 1..MAX_ATTEMPTS {
        let due = store.media_due(now, None, Some(&WIFI)).unwrap();
        assert_eq!(due.items.len(), 1, "attempt {attempt}");
        let settled = store.media_settle(ALI, "photo", &failed(), now).unwrap();
        let wait = backoff_ms(attempt);
        assert_eq!(
            (
                settled.media.state,
                settled.media.attempts,
                settled.media.next_attempt_ms
            ),
            (MediaState::Queued, attempt, Some(now + wait))
        );
        assert_eq!(settled.media.last_error.as_deref(), Some("timeout"));
        // Not before its time.
        let early = store.media_due(now + wait - 1, None, Some(&WIFI)).unwrap();
        assert!(early.items.is_empty());
        assert_eq!(early.next_wake_ms, Some(now + wait));
        now += wait;
    }
    store.media_due(now, None, Some(&WIFI)).unwrap();
    let last = store.media_settle(ALI, "photo", &failed(), now).unwrap();
    assert_eq!(
        (last.media.state, last.media.attempts),
        (MediaState::Failed, MAX_ATTEMPTS)
    );
    assert!(
        store
            .media_due(now + DAY, None, None)
            .unwrap()
            .items
            .is_empty()
    );
    // The rules do not try again by themselves; a tap starts over.
    let auto = store
        .media_enqueue(ALI, "photo", Fetch::Auto(WIFI), now)
        .unwrap();
    assert_eq!((auto.queued, auto.media.state), (false, MediaState::Failed));
    let tapped = store.media_enqueue(ALI, "photo", Fetch::Tap, now).unwrap();
    assert_eq!(
        (
            tapped.media.state,
            tapped.media.attempts,
            tapped.media.last_error
        ),
        (MediaState::Queued, 0, None)
    );
    // A tap on a file that waits for its next attempt sends it now.
    store.media_due(now, None, None).unwrap();
    store.media_settle(ALI, "photo", &failed(), now).unwrap();
    assert!(
        store
            .media_due(now + 1, None, None)
            .unwrap()
            .items
            .is_empty()
    );
    store
        .media_enqueue(ALI, "photo", Fetch::Tap, now + 1)
        .unwrap();
    assert_eq!(store.media_due(now + 1, None, None).unwrap().items.len(), 1);
}

#[test]
fn gone_and_cancelled_end_a_download() {
    let mut store = mixed();
    store.media_enqueue(ALI, "doc", Fetch::Tap, NOW).unwrap();
    store.media_due(NOW, None, None).unwrap();
    let gone = store
        .media_settle(
            ALI,
            "doc",
            &Fetched::Gone {
                error: Some("not kept".into()),
            },
            NOW + 1,
        )
        .unwrap();
    assert_eq!(gone.media.state, MediaState::Gone);
    assert_eq!(gone.media.last_error.as_deref(), Some("not kept"));
    assert!(
        !store
            .media_enqueue(ALI, "doc", Fetch::Auto(WIFI), NOW + 2)
            .unwrap()
            .queued
    );
    // A tap asks the gateway once more.
    assert!(
        store
            .media_enqueue(ALI, "doc", Fetch::Tap, NOW + 2)
            .unwrap()
            .queued
    );

    store.media_enqueue(ALI, "video", Fetch::Tap, NOW).unwrap();
    let cancelled = store
        .media_settle(ALI, "video", &Fetched::Cancelled, NOW + 1)
        .unwrap();
    assert_eq!(
        (cancelled.media.state, cancelled.media.by_tap),
        (MediaState::None, false)
    );
    // Neither outcome takes a cached file away.
    store
        .set_media_file(ALI, "photo", "/m/p.jpg", 1_000, NOW)
        .unwrap();
    for outcome in [failed(), Fetched::Gone { error: None }, Fetched::Cancelled] {
        let kept = store.media_settle(ALI, "photo", &outcome, NOW + 5).unwrap();
        assert_eq!(kept.media.state, MediaState::Ready, "{outcome:?}");
    }
}

#[test]
fn automatic_downloads_wait_when_the_network_or_the_space_no_longer_allows_them() {
    let mut store = mixed();
    store
        .media_enqueue(ALI, "sticker", Fetch::Auto(WIFI), NOW)
        .unwrap();
    store
        .media_enqueue(ALI, "photo", Fetch::Auto(WIFI), NOW + 1)
        .unwrap();
    store
        .media_enqueue(ALI, "doc", Fetch::Tap, NOW + 2)
        .unwrap();
    // On mobile data the sticker waits; the photo and the tapped document go.
    let mobile = store.media_due(NOW + 3, None, Some(&MOBILE)).unwrap();
    let ids: Vec<&str> = mobile.items.iter().map(|item| item.id.as_str()).collect();
    assert_eq!((ids, mobile.held), (vec!["doc", "photo"], 1));
    assert_eq!(state(&mut store, "sticker"), MediaState::Queued);
    // Roaming holds it too; back on Wi-Fi with room it goes.
    let roaming = Conditions::on(Network::Roaming);
    assert_eq!(
        store.media_due(NOW + 4, None, Some(&roaming)).unwrap().held,
        1
    );
    let low = Conditions {
        network: Network::Wifi,
        free_bytes: Some(FREE_FLOOR + 99),
    };
    assert_eq!(store.media_due(NOW + 5, None, Some(&low)).unwrap().held, 1);
    let back = store.media_due(NOW + 6, None, Some(&WIFI)).unwrap();
    assert_eq!((back.items.len(), back.held), (1, 0));
    assert_eq!(back.items[0].id, "sticker");
}

#[test]
fn a_download_nobody_settled_is_handed_out_again_after_its_lease() {
    let mut store = mixed();
    store.media_enqueue(ALI, "photo", Fetch::Tap, NOW).unwrap();
    assert_eq!(store.media_due(NOW, None, None).unwrap().items.len(), 1);
    let before = store
        .media_due(NOW + DOWNLOAD_LEASE_MS - 1, None, None)
        .unwrap();
    assert!(before.items.is_empty());
    assert_eq!(before.next_wake_ms, Some(NOW + DOWNLOAD_LEASE_MS));
    let after = store
        .media_due(NOW + DOWNLOAD_LEASE_MS, None, None)
        .unwrap();
    assert_eq!(after.items.len(), 1);
}

// ---- the cap

#[test]
fn eviction_is_least_recently_used_and_an_evicted_file_can_be_queued_again() {
    let mut store = photos(4);
    store.set_thumbnail(ALI, "P1", b"thumb").unwrap();
    assert_eq!(store.media_bytes().unwrap(), 4_000);
    // Opening P2 long ago still makes it fresher than P3 and P4.
    store.media(ALI, "P2", Some(NOW - 2 * DAY + 100)).unwrap();
    let under = store.media_evict(Some(4_000), Some(NOW)).unwrap();
    assert_eq!((paths(&under), under.bytes_left), (vec![], 4_000));
    let eviction = store.media_evict(Some(2_500), Some(NOW)).unwrap();
    assert_eq!(paths(&eviction), ["/m/P1.jpg", "/m/P3.jpg"]);
    assert_eq!((eviction.bytes_left, eviction.exempt_bytes), (2_000, 0));
    assert_eq!(eviction.evicted[0].bytes, 1_000);

    let evicted = store.media(ALI, "P1", None).unwrap().unwrap();
    assert_eq!(evicted.thumbnail.as_deref(), Some(&b"thumb"[..]));
    assert_eq!(
        (evicted.media.state, evicted.media.path, evicted.media.bytes),
        (MediaState::Evicted, None, None)
    );
    let row = store.message(ALI, "P1").unwrap().unwrap();
    assert!(row.has_thumbnail && row.media_path.is_none());
    assert_eq!(row.media_state, Some(MediaState::Evicted));
    // The rules fetch it again like a file never seen, and so does a tap.
    assert!(
        store
            .media_enqueue(ALI, "P1", Fetch::Auto(WIFI), NOW)
            .unwrap()
            .queued
    );
    assert!(
        store
            .media_enqueue(ALI, "P3", Fetch::Tap, NOW)
            .unwrap()
            .queued
    );
    store
        .media_settle(ALI, "P1", &ready("/m/P1-again.jpg", 1_000), NOW)
        .unwrap();
    assert_eq!(store.media_bytes().unwrap(), 3_000);
}

#[test]
fn starred_saved_pinned_unsent_and_recently_used_files_are_never_evicted() {
    let mut store = photos(7);
    // P1 starred, P2 saved to the gallery, P3 pinned, P4 being sent again, P5 opened an hour ago.
    store
        .outbox_enqueue(
            "s1",
            ALI,
            &Action::Star {
                message_id: "P1".into(),
                starred: true,
            },
            NOW,
        )
        .unwrap();
    store
        .media_keep(ALI, "P2", None, Some("content://media/42"))
        .unwrap();
    store.media_keep(ALI, "P3", Some(true), None).unwrap();
    store
        .outbox_enqueue("c1", ALI, &send_file("/m/P4.jpg", 1_000), NOW)
        .unwrap();
    store.media(ALI, "P5", Some(NOW - 60 * 60 * 1000)).unwrap();

    let eviction = store.media_evict(Some(0), Some(NOW)).unwrap();
    assert_eq!(paths(&eviction), ["/m/P6.jpg", "/m/P7.jpg"]);
    // The exempt files alone are over the cap: nothing more goes.
    assert_eq!((eviction.bytes_left, eviction.exempt_bytes), (5_000, 5_000));
    let again = store.media_evict(Some(0), Some(NOW)).unwrap();
    assert_eq!((paths(&again), again.bytes_left), (vec![], 5_000));
    let stats = store.media_stats(Some(NOW)).unwrap();
    assert_eq!(
        (stats.total.bytes, stats.exempt.bytes, stats.exempt.files),
        (5_000, 5_000, 5)
    );

    // Each exemption ends with its reason, and exactly at the 24 hour mark for the recently used file.
    store
        .outbox_enqueue(
            "s2",
            ALI,
            &Action::Star {
                message_id: "P1".into(),
                starred: false,
            },
            NOW,
        )
        .unwrap();
    store.media_keep(ALI, "P2", None, Some("")).unwrap();
    store.media_keep(ALI, "P3", Some(false), None).unwrap();
    assert!(store.outbox_cancel("c1").unwrap());
    let later = NOW - 60 * 60 * 1000 + RECENT_USE_MS;
    let kept = store.media_evict(Some(0), Some(later - 1)).unwrap();
    assert_eq!(
        paths(&kept),
        ["/m/P1.jpg", "/m/P2.jpg", "/m/P3.jpg", "/m/P4.jpg"]
    );
    assert_eq!((kept.bytes_left, kept.exempt_bytes), (1_000, 1_000));
    let last = store.media_evict(Some(0), Some(later)).unwrap();
    assert_eq!((paths(&last), last.bytes_left), (vec!["/m/P5.jpg"], 0));
}

#[test]
fn without_a_time_no_file_counts_as_recently_used() {
    let mut store = photos(2);
    store.media(ALI, "P1", Some(NOW)).unwrap();
    assert_eq!(
        store.media_evict(Some(0), Some(NOW)).unwrap().bytes_left,
        1_000
    );
    assert_eq!(store.media_evict(Some(0), None).unwrap().bytes_left, 0);
}

#[test]
fn the_stored_cap_is_the_default_can_be_set_and_can_be_no_limit() {
    let mut store = photos(3);
    assert_eq!(store.media_cap().unwrap(), Some(512 * 1024 * 1024));
    assert!(
        store
            .media_evict(None, Some(NOW))
            .unwrap()
            .evicted
            .is_empty()
    );
    store.set_media_cap(Some(1_500)).unwrap();
    assert_eq!(store.media_cap().unwrap(), Some(1_500));
    let eviction = store.media_evict(None, Some(NOW)).unwrap();
    assert_eq!(paths(&eviction), ["/m/P1.jpg", "/m/P2.jpg"]);
    // No limit: nothing is evicted whatever the size, and an explicit limit still works.
    store.set_media_cap(None).unwrap();
    assert_eq!(store.media_cap().unwrap(), None);
    assert!(
        store
            .media_evict(None, Some(NOW))
            .unwrap()
            .evicted
            .is_empty()
    );
    assert_eq!(store.media_stats(Some(NOW)).unwrap().cap_bytes, None);
    assert_eq!(
        store.media_evict(Some(0), Some(NOW)).unwrap().evicted.len(),
        1
    );
    assert!(matches!(
        store.set_media_cap(Some(u64::MAX)),
        Err(Error::Invalid(_))
    ));
}

// ---- reconcile at open

#[test]
fn reconcile_requeues_cut_downloads_evicts_rows_without_a_file_and_names_orphans() {
    let mut store = photos(3);
    apply(
        &mut store,
        vec![media_msg(ALI, "V1", 9_000, MediaKind::Video, 5_000)],
    );
    store.media_enqueue(ALI, "V1", Fetch::Tap, NOW).unwrap();
    store.media_due(NOW, None, None).unwrap();
    assert_eq!(state(&mut store, "V1"), MediaState::Downloading);
    store
        .outbox_enqueue("c1", ALI, &send_file("/m/out/u1/send.jpg", 700), NOW)
        .unwrap();

    // Without a listing only the cut download is put back.
    let bare = store.media_reconcile(None).unwrap();
    assert_eq!(
        (bare.requeued, bare.missing.len(), bare.delete.len()),
        (1, 0, 0)
    );
    assert_eq!(state(&mut store, "V1"), MediaState::Queued);
    assert_eq!(store.media_bytes().unwrap(), 3_000);

    let listing: Vec<String> = [
        "/m/P1.jpg",
        "/m/P3.jpg",
        "/m/V1.mp4.part",
        "/m/stray.bin",
        "/m/out/u1/send.jpg",
    ]
    .map(String::from)
    .into();
    let found = store.media_reconcile(Some(&listing)).unwrap();
    assert_eq!(found.requeued, 0);
    assert_eq!(
        found.missing,
        [Missing {
            chat: ALI.into(),
            id: "P2".into(),
            path: "/m/P2.jpg".into()
        }]
    );
    assert_eq!(found.delete, ["/m/V1.mp4.part", "/m/stray.bin"]);
    assert_eq!(state(&mut store, "P2"), MediaState::Evicted);
    assert_eq!(state(&mut store, "P1"), MediaState::Ready);
    assert_eq!(store.media_bytes().unwrap(), 2_000);
    // Running it again finds nothing new.
    let again = store.media_reconcile(Some(&listing)).unwrap();
    assert!(again.missing.is_empty());
    assert_eq!(again.delete, found.delete);
}

// ---- numbers for settings

#[test]
fn stats_count_cached_files_by_kind_and_what_is_exempt() {
    let mut store = mixed();
    store
        .set_media_file(ALI, "photo", "/m/p.jpg", 1_000, NOW - 2 * DAY)
        .unwrap();
    store
        .set_media_file(ALI, "video", "/m/v.mp4", 9_000, NOW - 2 * DAY)
        .unwrap();
    store
        .set_media_file(ALI, "voice", "/m/a.ogg", 500, NOW - 1_000)
        .unwrap();
    store
        .set_media_file(ALI, "doc", "/m/d.pdf", 2_000, NOW - 3 * DAY)
        .unwrap();
    store.media_keep(ALI, "doc", Some(true), None).unwrap();
    store
        .media_enqueue(ALI, "sticker", Fetch::Tap, NOW)
        .unwrap();
    store
        .outbox_enqueue("c1", ALI, &send_file("/m/out/new.jpg", 300), NOW)
        .unwrap();
    store.set_media_cap(Some(2_000)).unwrap();
    let stats = store.media_stats(Some(NOW)).unwrap();
    assert_eq!(
        stats.total,
        Usage {
            files: 4,
            bytes: 12_500
        }
    );
    // The pinned document and the voice note heard a second ago.
    assert_eq!(
        stats.exempt,
        Usage {
            files: 2,
            bytes: 2_500
        }
    );
    let by_kind: Vec<(Kind, u32, u64)> = stats
        .by_kind
        .iter()
        .map(|kind| (kind.kind, kind.usage.files, kind.usage.bytes))
        .collect();
    assert_eq!(
        by_kind,
        [
            (Kind::Video, 1, 9_000),
            (Kind::Document, 1, 2_000),
            (Kind::Image, 1, 1_000),
            (Kind::Audio, 1, 500)
        ]
    );
    assert_eq!(
        stats.outbox,
        Usage {
            files: 1,
            bytes: 300
        }
    );
    assert_eq!((stats.queued, stats.downloading, stats.failed), (1, 0, 0));
    assert_eq!(
        (stats.cap_bytes, stats.exempt_over_cap),
        (Some(2_000), true)
    );
    // As JSON: flat usage per kind.
    let json = serde_json::to_value(&stats).unwrap();
    assert_eq!(
        json["by_kind"][0],
        serde_json::json!({"kind": "video", "files": 1, "bytes": 9_000})
    );
}

// ---- merge

#[test]
fn media_rows_follow_a_merge_and_a_collision_keeps_the_cached_file() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            // Only in the LID chat, cached.
            media_msg(ALI_LID, "ONLY", 1_000, MediaKind::Image, 100),
            // In both; the LID row holds the file, the number row only waits.
            media_msg(ALI_LID, "LID-HAS", 2_000, MediaKind::Image, 200),
            media_msg(ALI, "LID-HAS", 2_000, MediaKind::Image, 200),
            // In both; the number row holds the file, the LID row failed.
            media_msg(ALI_LID, "PN-HAS", 3_000, MediaKind::Image, 300),
            media_msg(ALI, "PN-HAS", 3_000, MediaKind::Image, 300),
            // In both, both cached: the number row stays.
            media_msg(ALI_LID, "BOTH", 4_000, MediaKind::Image, 400),
            media_msg(ALI, "BOTH", 4_000, MediaKind::Image, 400),
            // In both; only the LID row was ever asked for.
            media_msg(ALI_LID, "QUEUED", 5_000, MediaKind::Video, 500),
            media_msg(ALI, "QUEUED", 5_000, MediaKind::Video, 500),
        ],
    );
    let lid_key = ALI_LID;
    store
        .set_media_file(lid_key, "ONLY", "/m/only.jpg", 100, 10)
        .unwrap();
    store
        .set_media_file(lid_key, "LID-HAS", "/m/lid-has.jpg", 200, 20)
        .unwrap();
    store.media_enqueue(ALI, "LID-HAS", Fetch::Tap, 21).unwrap();
    store
        .media_keep(ALI, "LID-HAS", None, Some("content://saved/1"))
        .unwrap();
    store
        .set_media_file(ALI, "PN-HAS", "/m/pn-has.jpg", 300, 30)
        .unwrap();
    store
        .media_enqueue(lid_key, "PN-HAS", Fetch::Tap, 31)
        .unwrap();
    store.media_due(32, None, None).unwrap();
    store
        .media_keep(lid_key, "PN-HAS", Some(true), None)
        .unwrap();
    store
        .set_media_file(lid_key, "BOTH", "/m/both-lid.jpg", 400, 40)
        .unwrap();
    store
        .set_media_file(ALI, "BOTH", "/m/both-pn.jpg", 400, 41)
        .unwrap();
    store
        .media_enqueue(lid_key, "QUEUED", Fetch::Tap, 50)
        .unwrap();
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM media"), 8);

    let applied = store
        .identity_learn(ALI_LID, ALI, Source::Usync, 9_000)
        .unwrap();
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(
        count(
            &store.conn,
            &format!("SELECT COUNT(*) FROM media WHERE chat = '{ALI_LID}'")
        ),
        0
    );
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM media"), 5);
    let media = |store: &mut Store, id: &str| store.media(ALI, id, None).unwrap().unwrap().media;
    assert_eq!(
        media(&mut store, "ONLY").path.as_deref(),
        Some("/m/only.jpg")
    );
    let lid_has = media(&mut store, "LID-HAS");
    assert_eq!(
        (
            lid_has.state,
            lid_has.path.as_deref(),
            lid_has.saved_uri.as_deref()
        ),
        (
            MediaState::Ready,
            Some("/m/lid-has.jpg"),
            Some("content://saved/1")
        )
    );
    let pn_has = media(&mut store, "PN-HAS");
    assert_eq!(
        (pn_has.state, pn_has.path.as_deref(), pn_has.pinned),
        (MediaState::Ready, Some("/m/pn-has.jpg"), true)
    );
    assert_eq!(
        media(&mut store, "BOTH").path.as_deref(),
        Some("/m/both-pn.jpg")
    );
    assert_eq!(media(&mut store, "QUEUED").state, MediaState::Queued);
    assert_eq!(store.media_bytes().unwrap(), 100 + 200 + 300 + 400);
    // The thread reads the files under the one key, and a reader that still holds the LID finds them.
    assert_eq!(
        store
            .message(ALI_LID, "LID-HAS")
            .unwrap()
            .unwrap()
            .media_path
            .as_deref(),
        Some("/m/lid-has.jpg")
    );
    // The file of the row that lost is one no row names: the reconcile lists it.
    let listing: Vec<String> = ["/m/both-lid.jpg", "/m/both-pn.jpg", "/m/only.jpg"]
        .map(String::from)
        .into();
    let found = store.media_reconcile(Some(&listing)).unwrap();
    assert_eq!(found.delete, ["/m/both-lid.jpg"]);
    // Applying the step again changes nothing.
    assert!(!media::follow_merge(&store.conn, ALI_LID, ALI).unwrap());
}

#[test]
fn a_file_sent_from_the_outbox_is_the_cached_file_of_its_message() {
    let mut store = store();
    apply(&mut store, vec![message(text(ALI, "A", 1_000, "hi"))]);
    store
        .outbox_enqueue("c1", ALI, &send_file("/m/out/p.jpg", 4_096), 2_000)
        .unwrap();
    // While it waits it is not a cached file, but the reconcile leaves it alone.
    assert_eq!(store.media_bytes().unwrap(), 0);
    let listing = vec!["/m/out/p.jpg".to_owned()];
    assert!(
        store
            .media_reconcile(Some(&listing))
            .unwrap()
            .delete
            .is_empty()
    );
    store.outbox_due(2_000, false).unwrap();
    store
        .outbox_settle(
            "c1",
            &Outcome::Sent {
                message_id: Some("S1".into()),
                timestamp_ms: Some(2_100),
            },
            2_100,
        )
        .unwrap();
    let sent = store.media(ALI, "S1", None).unwrap().unwrap().media;
    assert_eq!(
        (sent.state, sent.path.as_deref(), sent.bytes, sent.kind),
        (
            MediaState::Ready,
            Some("/m/out/p.jpg"),
            Some(4_096),
            Kind::Image
        )
    );
    assert!(
        store
            .media_reconcile(Some(&listing))
            .unwrap()
            .delete
            .is_empty()
    );
}
