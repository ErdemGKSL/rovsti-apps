//! The media round: schema v6, the options a media send carries, the played receipt, view once and the gallery
//! of a chat.

use rosty_core::wa_identity::Source;
use rosty_wa_proto::{
    GatewayEvent, MediaKind, MediaMeta, MediaOptions, MessageBody, MessageMeta, RichMessage,
    Unavailable, WAVEFORM_LEN, WaMessage, encode_b64,
};
use rusqlite::Connection;

use super::identity_tests::{
    apply, by, columns, count, message, meta_event, store, temp_dir, text,
};
use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const ALI_LID: &str = "777000000000001@lid";
const GROUP: &str = "120000000000000001@g.us";
const WIFI: Conditions = Conditions {
    network: Network::Wifi,
    free_bytes: None,
};
const NOW: i64 = 1_000_000;
const JPEG: [u8; 4] = [0xFF, 0xD8, 0xFF, 0xE0];

fn media_msg(chat: &str, id: &str, ts: i64, kind: MediaKind) -> WaMessage {
    WaMessage {
        body: MessageBody::Media {
            kind,
            caption: None,
            mime: "application/octet-stream".into(),
            size: 1_000,
        },
        ..text(chat, id, ts, "")
    }
}

fn media_meta(chat: &str, id: &str, media: MediaMeta) -> GatewayEvent {
    meta_event(
        chat,
        id,
        MessageMeta {
            media: Some(media),
            ..MessageMeta::default()
        },
    )
}

fn voice_note() -> MediaMeta {
    MediaMeta {
        seconds: Some(4),
        voice_note: true,
        waveform_b64: Some(encode_b64(&[9; WAVEFORM_LEN])),
        ..MediaMeta::default()
    }
}

fn view_once() -> MediaMeta {
    MediaMeta {
        view_once: true,
        ..MediaMeta::default()
    }
}

fn photo(file: &str, extras: MediaExtras) -> Action {
    Action::SendMedia {
        file: file.into(),
        media: MediaKind::Image,
        mime: "image/jpeg".into(),
        size: 500,
        caption: None,
        quoted_id: None,
        file_name: None,
        seconds: None,
        voice_note: false,
        extras,
    }
}

fn sent(id: &str, ts: i64) -> Outcome {
    Outcome::Sent {
        message_id: Some(id.into()),
        timestamp_ms: Some(ts),
    }
}

fn rejected() -> Outcome {
    Outcome::Error {
        error: ErrorKind::Rejected,
        message: "no".into(),
        retry_after_ms: None,
    }
}

fn ready(path: &str) -> Fetched {
    Fetched::Ready {
        path: path.into(),
        bytes: 1_000,
        sha256: None,
    }
}

fn row(store: &Store, chat: &str, id: &str) -> MessageRow {
    store.message(chat, id).unwrap().unwrap()
}

// ---- schema

#[test]
fn a_version_five_file_gains_the_marks_and_the_gallery_index_and_keeps_its_rows() {
    let dir = temp_dir("round-v5");
    let path = dir.join(file_name("inst1"));
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate_to(&mut conn, 5).unwrap();
        assert!(!columns(&conn, "messages").contains(&"viewed_ms".to_owned()));
        // An outbox row as a build before this round wrote it: no preview, size, waveform or flags.
        conn.execute_batch(&format!(
            "INSERT INTO sync_state (id, instance_id, last_seq, needs_resync, identity_backfill)
                VALUES (1, 'inst1', 7, 0, 'done');
             INSERT INTO chats (jid, name) VALUES ('{ALI}', 'Ali');
             INSERT INTO messages (chat, id, from_me, ts, kind, mime, size, media_json) VALUES
                ('{ALI}', 'A', 0, 1000, 'image', 'image/jpeg', 2048, NULL),
                ('{ALI}', 'V', 0, 2000, 'audio', 'audio/ogg', 300, '{{\"seconds\":3,\"voice_note\":true}}');
             INSERT INTO media (chat, id, kind, state, path, bytes, used_ms) VALUES
                ('{ALI}', 'A', 'image', 'ready', '/m/A.jpg', 2048, 5);
             INSERT INTO outbox (client_id, chat, kind, payload, created_ms) VALUES
                ('c1', '{ALI}', 'send_media', '{{\"kind\":\"send_media\",\"file\":\"/o/v.ogg\",\"media\":\"audio\",\"mime\":\"audio/ogg\",\"size\":9,\"seconds\":2,\"voice_note\":true}}', 900);"
        ))
        .unwrap();
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(store.opened().unwrap().schema_version, 6);
    for column in ["viewed_ms", "played_ms"] {
        assert!(columns(&store.conn, "messages").contains(&column.to_owned()));
    }
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'messages_gallery'"
        ),
        1
    );
    let photo = row(&store, ALI, "A");
    assert_eq!(photo.media_path.as_deref(), Some("/m/A.jpg"));
    assert!(!photo.viewed && !photo.played);
    // The old outbox row loads, sends as it would have, and shows what it knows.
    let queued = store.outbox().unwrap().remove(0);
    assert_eq!(
        queued.action.media_options(),
        Some(MediaOptions {
            seconds: Some(2),
            voice_note: true,
            ..MediaOptions::default()
        })
    );
    // What the round added works on the old rows.
    assert!(store.mark_played("p1", ALI, "V", NOW).unwrap().queued);
    assert_eq!(
        store
            .chat_media(ALI, 10, None, None, &[])
            .unwrap()
            .items
            .len(),
        1
    );
    drop(store);
    // Opening again migrates nothing.
    let store = Store::open(&dir, "inst1").unwrap();
    assert!(row(&store, ALI, "V").played);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_version_three_file_upgrades_through_every_step_to_six() {
    let dir = temp_dir("round-v3");
    let path = dir.join(file_name("inst1"));
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate_to(&mut conn, 3).unwrap();
        conn.execute_batch(&format!(
            "INSERT INTO sync_state (id, instance_id, last_seq, needs_resync) VALUES (1, 'inst1', 3, 0);
             INSERT INTO chats (jid) VALUES ('{ALI}');
             INSERT INTO messages (chat, id, sender, from_me, ts, kind, size, media_path, media_bytes,
                    media_used_ms) VALUES
                ('{ALI}', 'A', '{ALI}', 0, 1000, 'image', 10, '/m/A.jpg', 10, 5);"
        ))
        .unwrap();
    }
    let store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(store.opened().unwrap().schema_version, SCHEMA_VERSION);
    let page = store.chat_media(ALI, 10, None, None, &[]).unwrap();
    assert_eq!(page.items[0].media_path.as_deref(), Some("/m/A.jpg"));
    let _ = std::fs::remove_dir_all(dir);
}

// ---- the options of a media send

#[test]
fn a_media_action_is_pinned_with_and_without_the_new_options() {
    let old =
        r#"{"kind":"send_media","file":"/o/a.jpg","media":"image","mime":"image/jpeg","size":500}"#;
    let plain = photo("/o/a.jpg", MediaExtras::default());
    assert_eq!(serde_json::from_str::<Action>(old).unwrap(), plain);
    // Unset options are not written: the row is what an older build wrote.
    assert_eq!(serde_json::to_string(&plain).unwrap(), old);

    let rich = photo(
        "/o/a.jpg",
        MediaExtras {
            thumbnail_b64: Some(encode_b64(&JPEG)),
            width: Some(640),
            height: Some(480),
            waveform_b64: None,
            animated: false,
            view_once: true,
        },
    );
    let json = serde_json::to_string(&rich).unwrap();
    assert_eq!(
        json,
        concat!(
            r#"{"kind":"send_media","file":"/o/a.jpg","media":"image","mime":"image/jpeg","size":500,"#,
            r#""thumbnail_b64":"/9j/4A==","width":640,"height":480,"view_once":true}"#
        )
    );
    assert_eq!(serde_json::from_str::<Action>(&json).unwrap(), rich);
    assert_eq!(
        rich.media_options(),
        Some(MediaOptions {
            thumbnail_b64: Some("/9j/4A==".into()),
            width: Some(640),
            height: Some(480),
            view_once: true,
            ..MediaOptions::default()
        })
    );
    let played: Action =
        serde_json::from_str(r#"{"kind":"mark_played","message_id":"V","sender":"s"}"#).unwrap();
    assert_eq!(
        played,
        Action::MarkPlayed {
            message_id: "V".into(),
            sender: Some("s".into())
        }
    );
    assert_eq!(played.kind(), "mark_played");
    assert_eq!(played.media_options(), None);
}

#[test]
fn the_options_show_on_the_waiting_row_reach_the_due_row_and_stay_on_the_sent_message() {
    let mut store = store();
    let waveform = encode_b64(&[40; WAVEFORM_LEN]);
    let voice = Action::SendMedia {
        file: "/o/v.ogg".into(),
        media: MediaKind::Audio,
        mime: "audio/ogg".into(),
        size: 900,
        caption: None,
        quoted_id: None,
        file_name: None,
        seconds: Some(6),
        voice_note: true,
        extras: MediaExtras {
            waveform_b64: Some(waveform.clone()),
            // A photo's options on audio: carried to the gateway call, not shown as if they applied.
            width: Some(10),
            ..MediaExtras::default()
        },
    };
    store.outbox_enqueue("c1", ALI, &voice, 1_000).unwrap();
    let expected = MediaMeta {
        seconds: Some(6),
        voice_note: true,
        waveform_b64: Some(waveform.clone()),
        ..MediaMeta::default()
    };
    let waiting = store.thread(ALI, 10, None).unwrap().items.remove(0);
    assert_eq!(waiting.media, Some(expected.clone()));
    assert_eq!(waiting.state, RowState::Waiting);

    let due = store.outbox_due(2_000, false).unwrap().items.remove(0);
    let options = due.action.media_options().unwrap();
    assert_eq!(options.waveform_b64.as_deref(), Some(waveform.as_str()));
    assert_eq!((options.seconds, options.voice_note), (Some(6), true));
    assert_eq!(options.width, Some(10));

    store
        .outbox_settle("c1", &sent("S1", 2_100), 2_100)
        .unwrap();
    let stored = row(&store, ALI, "S1");
    assert_eq!(stored.media, Some(expected));
    assert_eq!(stored.media_path.as_deref(), Some("/o/v.ogg"));

    // A photo's preview becomes the thumbnail of the sent message.
    let picture = photo(
        "/o/p.jpg",
        MediaExtras {
            thumbnail_b64: Some(encode_b64(&JPEG)),
            width: Some(640),
            height: Some(480),
            ..MediaExtras::default()
        },
    );
    store.outbox_enqueue("c2", ALI, &picture, 3_000).unwrap();
    assert!(!store.thread(ALI, 10, None).unwrap().items[0].has_thumbnail);
    store.outbox_due(3_100, false).unwrap();
    store
        .outbox_settle("c2", &sent("S2", 3_200), 3_200)
        .unwrap();
    let stored = row(&store, ALI, "S2");
    assert!(stored.has_thumbnail);
    let media = stored.media.unwrap();
    assert_eq!((media.width, media.height), (Some(640), Some(480)));
    assert_eq!(
        store.media(ALI, "S2", None).unwrap().unwrap().thumbnail,
        Some(JPEG.to_vec())
    );
}

#[test]
fn options_the_gateway_would_refuse_are_refused_when_queued() {
    let mut store = store();
    let refuse = |store: &mut Store, action: &Action| {
        let refused = store.outbox_enqueue("c1", ALI, action, 1_000);
        assert!(matches!(refused, Err(Error::Invalid(_))), "{refused:?}");
    };
    refuse(
        &mut store,
        &photo(
            "/o/a.jpg",
            MediaExtras {
                thumbnail_b64: Some(encode_b64(b"\x89PNG")),
                ..MediaExtras::default()
            },
        ),
    );
    refuse(
        &mut store,
        &photo(
            "/o/a.jpg",
            MediaExtras {
                waveform_b64: Some(encode_b64(&[1; 63])),
                ..MediaExtras::default()
            },
        ),
    );
    let document = Action::SendMedia {
        file: "/o/a.pdf".into(),
        media: MediaKind::Document,
        mime: "application/pdf".into(),
        size: 9,
        caption: None,
        quoted_id: None,
        file_name: Some("a.pdf".into()),
        seconds: None,
        voice_note: false,
        extras: MediaExtras {
            view_once: true,
            ..MediaExtras::default()
        },
    };
    refuse(&mut store, &document);
    assert!(store.outbox().unwrap().is_empty());
}

#[test]
fn a_view_once_send_does_not_keep_its_file_and_cannot_be_opened_by_its_sender() {
    let mut store = store();
    let once = photo(
        "/o/once.jpg",
        MediaExtras {
            view_once: true,
            ..MediaExtras::default()
        },
    );
    store.outbox_enqueue("c1", ALI, &once, 1_000).unwrap();
    store.outbox_due(1_100, false).unwrap();
    store
        .outbox_settle("c1", &sent("S1", 1_200), 1_200)
        .unwrap();
    let stored = row(&store, ALI, "S1");
    assert!(stored.media.unwrap().view_once);
    assert_eq!(stored.media_path, None);
    assert_eq!(store.media_bytes().unwrap(), 0);
    // No row names the file any more: the reconcile lists it for deletion.
    let reconciled = store
        .media_reconcile(Some(&["/o/once.jpg".to_owned()]))
        .unwrap();
    assert_eq!(reconciled.delete, ["/o/once.jpg"]);
    let tapped = store.media_enqueue(ALI, "S1", Fetch::Tap, NOW).unwrap();
    assert_eq!((tapped.queued, tapped.decision), (false, Decision::Never));
}

// ---- played

fn with_voice_notes() -> Store {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(media_msg(ALI, "V", 1_000, MediaKind::Audio)),
            media_meta(ALI, "V", voice_note()),
            message(media_msg(ALI, "SONG", 2_000, MediaKind::Audio)),
            message(WaMessage {
                sender: None,
                from_me: true,
                ..media_msg(ALI, "MINE", 3_000, MediaKind::Audio)
            }),
            media_meta(ALI, "MINE", voice_note()),
            message(text(ALI, "T", 4_000, "hi")),
            message(WaMessage {
                sender: Some(ALI.into()),
                ..media_msg(GROUP, "G", 5_000, MediaKind::Audio)
            }),
            media_meta(GROUP, "G", voice_note()),
        ],
    );
    store
}

#[test]
fn a_played_receipt_is_queued_once_per_voice_note_and_goes_out_like_the_others() {
    let mut store = with_voice_notes();
    assert_eq!(
        row(&store, ALI, "V").media.unwrap().waveform(),
        Some(vec![9; WAVEFORM_LEN])
    );
    assert!(!row(&store, ALI, "V").played);

    let first = store.mark_played("p1", ALI, "V", NOW).unwrap();
    assert!(first.queued);
    let item = first.item.unwrap();
    assert_eq!(
        item.action,
        Action::MarkPlayed {
            message_id: "V".into(),
            sender: None
        }
    );
    assert!(row(&store, ALI, "V").played);
    // The same call again answers the same row; another call for the message queues nothing.
    let again = store.mark_played("p1", ALI, "V", NOW + 1).unwrap();
    assert_eq!(again.item, Some(item));
    let other = store.mark_played("p2", ALI, "V", NOW + 2).unwrap();
    assert_eq!((other.queued, other.item), (false, None));
    assert_eq!(store.outbox().unwrap().len(), 1);

    let due = store.outbox_due(NOW + 3, false).unwrap().items;
    assert_eq!(due.len(), 1);
    assert_eq!(due[0].action.kind(), "mark_played");
    assert_eq!(
        store
            .outbox_settle("p1", &sent("", NOW + 4), NOW + 4)
            .unwrap(),
        Settled::Done { message_id: None }
    );
    assert!(store.outbox().unwrap().is_empty());
    // Sent: it stays played, and is not sent a second time.
    assert!(row(&store, ALI, "V").played);
    assert!(!store.mark_played("p3", ALI, "V", NOW + 5).unwrap().queued);
    // The thread shows no row for a receipt.
    assert!(
        store
            .thread(ALI, 10, None)
            .unwrap()
            .items
            .iter()
            .all(|row| row.id != "p1")
    );
}

#[test]
fn only_a_received_voice_note_can_be_marked_played() {
    let mut store = with_voice_notes();
    for id in ["SONG", "MINE", "T"] {
        let refused = store.mark_played("p1", ALI, id, NOW);
        assert!(
            matches!(refused, Err(Error::Invalid(_))),
            "{id}: {refused:?}"
        );
        assert!(!row(&store, ALI, id).played);
    }
    assert!(matches!(
        store.mark_played("p1", ALI, "NOPE", NOW),
        Err(Error::NotFound(_))
    ));
    // The generic door is closed: it would skip the once-per-message rule.
    let direct = Action::MarkPlayed {
        message_id: "V".into(),
        sender: None,
    };
    assert!(matches!(
        store.outbox_enqueue("p1", ALI, &direct, NOW),
        Err(Error::Invalid(_))
    ));
    assert!(store.outbox().unwrap().is_empty());
}

#[test]
fn a_played_receipt_names_the_author_in_a_group_and_a_refused_one_is_dropped() {
    let mut store = with_voice_notes();
    let queued = store.mark_played("p1", GROUP, "G", NOW).unwrap();
    assert_eq!(
        queued.item.unwrap().action,
        Action::MarkPlayed {
            message_id: "G".into(),
            sender: Some(ALI.into())
        }
    );
    store.outbox_due(NOW + 1, false).unwrap();
    assert_eq!(
        store.outbox_settle("p1", &rejected(), NOW + 2).unwrap(),
        Settled::Failed
    );
    // Nothing lingers, and it is not tried again.
    assert!(store.outbox().unwrap().is_empty());
    assert!(row(&store, GROUP, "G").played);
    assert!(!store.mark_played("p2", GROUP, "G", NOW + 3).unwrap().queued);
}

#[test]
fn a_played_receipt_follows_a_merge() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(media_msg(ALI_LID, "V", 1_000, MediaKind::Audio)),
            media_meta(ALI_LID, "V", voice_note()),
            message(by(GROUP, ALI_LID, "G", 2_000, "")),
        ],
    );
    apply(
        &mut store,
        vec![
            message(WaMessage {
                sender: Some(ALI_LID.into()),
                ..media_msg(GROUP, "GV", 3_000, MediaKind::Audio)
            }),
            media_meta(GROUP, "GV", voice_note()),
        ],
    );
    store.mark_played("p1", ALI_LID, "V", NOW).unwrap();
    store.mark_played("p2", GROUP, "GV", NOW).unwrap();
    store
        .identity_learn(ALI_LID, ALI, Source::Usync, NOW + 1)
        .unwrap();
    // The mark and the queued receipts are under the number now, whichever id the caller still holds.
    assert!(row(&store, ALI, "V").played);
    assert!(row(&store, ALI_LID, "V").played);
    let outbox = store.outbox().unwrap();
    assert_eq!(outbox[0].chat, ALI);
    assert_eq!(
        outbox[1].action,
        Action::MarkPlayed {
            message_id: "GV".into(),
            sender: Some(ALI.into())
        }
    );
    assert!(
        !store
            .mark_played("p3", ALI_LID, "V", NOW + 2)
            .unwrap()
            .queued
    );
}

// ---- view once

fn with_view_once() -> Store {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(media_msg(ALI, "ONCE", 1_000, MediaKind::Image)),
            media_meta(ALI, "ONCE", view_once()),
            message(media_msg(ALI, "PHOTO", 2_000, MediaKind::Image)),
        ],
    );
    store.set_thumbnail(ALI, "ONCE", &JPEG).unwrap();
    store
}

#[test]
fn view_once_is_fetched_by_a_tap_only_and_opens_once() {
    let mut store = with_view_once();
    // Never by itself, whatever the network.
    let auto = store
        .media_enqueue(ALI, "ONCE", Fetch::Auto(WIFI), NOW)
        .unwrap();
    assert_eq!((auto.queued, auto.decision), (false, Decision::Never));
    assert_eq!(
        store.media_policy(ALI, "ONCE", &WIFI).unwrap(),
        Decision::Never
    );
    // The tap that opens it fetches it.
    let tapped = store.media_enqueue(ALI, "ONCE", Fetch::Tap, NOW).unwrap();
    assert_eq!((tapped.queued, tapped.decision), (true, Decision::OnTap));
    let due = store.media_due(NOW + 1, None, Some(&WIFI)).unwrap();
    assert_eq!(due.items.len(), 1);
    store
        .media_settle(ALI, "ONCE", &ready("/m/once.jpg"), NOW + 2)
        .unwrap();
    let before = row(&store, ALI, "ONCE");
    assert_eq!(before.media_path.as_deref(), Some("/m/once.jpg"));
    assert!(before.has_thumbnail && !before.viewed);

    let viewed = store.media_viewed(ALI, "ONCE", NOW + 3).unwrap();
    assert!(viewed.first);
    assert_eq!(viewed.delete.as_deref(), Some("/m/once.jpg"));
    assert_eq!(viewed.media.state, MediaState::Gone);
    assert_eq!(viewed.media.path, None);
    let after = row(&store, ALI, "ONCE");
    assert!(after.viewed);
    assert_eq!(after.media_path, None);
    assert_eq!(after.media_state, Some(MediaState::Gone));
    assert!(!after.has_thumbnail);
    assert_eq!(store.media_bytes().unwrap(), 0);

    // Never again: not by a tap, not by itself, and a second mark hands out nothing.
    for fetch in [Fetch::Tap, Fetch::Auto(WIFI)] {
        let asked = store.media_enqueue(ALI, "ONCE", fetch, NOW + 4).unwrap();
        assert_eq!((asked.queued, asked.decision), (false, Decision::Never));
        assert_eq!(asked.media.state, MediaState::Gone);
    }
    assert!(
        store
            .media_due(NOW + 5, None, None)
            .unwrap()
            .items
            .is_empty()
    );
    let again = store.media_viewed(ALI, "ONCE", NOW + 6).unwrap();
    assert_eq!((again.first, again.delete), (false, None));
    // What the gateway says about the message later does not bring it back.
    apply(&mut store, vec![media_meta(ALI, "ONCE", view_once())]);
    assert!(row(&store, ALI, "ONCE").viewed);
}

#[test]
fn a_download_that_lands_after_the_message_was_opened_is_handed_back() {
    let mut store = with_view_once();
    store.media_enqueue(ALI, "ONCE", Fetch::Tap, NOW).unwrap();
    store.media_due(NOW + 1, None, None).unwrap();
    // Opened (and closed) from another surface while the file was on its way.
    let viewed = store.media_viewed(ALI, "ONCE", NOW + 2).unwrap();
    assert_eq!(viewed.delete, None);
    let settled = store
        .media_settle(ALI, "ONCE", &ready("/m/late.jpg"), NOW + 3)
        .unwrap();
    assert_eq!(settled.replaced.as_deref(), Some("/m/late.jpg"));
    assert_eq!(settled.media.state, MediaState::Gone);
    assert_eq!(row(&store, ALI, "ONCE").media_path, None);
}

#[test]
fn only_view_once_media_can_be_marked_viewed() {
    let mut store = with_view_once();
    assert!(matches!(
        store.media_viewed(ALI, "PHOTO", NOW),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        store.media_viewed(ALI, "NOPE", NOW),
        Err(Error::NotFound(_))
    ));
    assert!(!row(&store, ALI, "PHOTO").viewed);
}

// ---- the gallery

/// Ali's chat: photos `P1`..`P6` at 1 000..6 000, a video, a document, a voice note, a text, a deleted photo
/// and a view-once photo in between.
fn gallery() -> Store {
    let mut store = store();
    let mut events: Vec<GatewayEvent> = (1..=6)
        .map(|i| {
            message(media_msg(
                ALI,
                &format!("P{i}"),
                i * 1_000,
                MediaKind::Image,
            ))
        })
        .collect();
    events.extend([
        message(media_msg(ALI, "VID", 3_500, MediaKind::Video)),
        message(media_msg(ALI, "DOC", 4_500, MediaKind::Document)),
        message(media_msg(ALI, "VOICE", 5_500, MediaKind::Audio)),
        message(media_msg(ALI, "STICKER", 5_600, MediaKind::Sticker)),
        message(text(ALI, "TEXT", 5_700, "hi")),
        message(media_msg(ALI, "DEL", 5_800, MediaKind::Image)),
        meta_event(
            ALI,
            "DEL",
            MessageMeta {
                deleted: true,
                ..MessageMeta::default()
            },
        ),
        message(media_msg(ALI, "ONCE", 5_900, MediaKind::Image)),
        media_meta(ALI, "ONCE", view_once()),
    ]);
    apply(&mut store, events);
    store
}

fn ids(page: &GalleryPage) -> Vec<&str> {
    page.items.iter().map(|row| row.id.as_str()).collect()
}

fn cursor(ts: i64, id: &str) -> ThreadCursor {
    ThreadCursor {
        timestamp_ms: ts,
        id: id.into(),
    }
}

#[test]
fn the_gallery_is_photos_and_videos_newest_first_without_deleted_or_view_once() {
    let store = gallery();
    let page = store.chat_media(ALI, 50, None, None, &[]).unwrap();
    assert_eq!(ids(&page), ["P6", "P5", "P4", "VID", "P3", "P2", "P1"]);
    assert_eq!((page.older.clone(), page.newer.clone()), (None, None));
    let all = [Kind::Image, Kind::Video, Kind::Document, Kind::Audio];
    let page = store.chat_media(ALI, 50, None, None, &all).unwrap();
    assert_eq!(
        ids(&page),
        ["P6", "VOICE", "P5", "DOC", "P4", "VID", "P3", "P2", "P1"]
    );
    let docs = store
        .chat_media(ALI, 50, None, None, &[Kind::Document])
        .unwrap();
    assert_eq!(ids(&docs), ["DOC"]);
    for kind in [Kind::Sticker, Kind::Text] {
        assert!(matches!(
            store.chat_media(ALI, 50, None, None, &[kind]),
            Err(Error::Invalid(_))
        ));
    }
    assert!(
        store
            .chat_media("nobody@s.whatsapp.net", 50, None, None, &[])
            .unwrap()
            .items
            .is_empty()
    );
}

#[test]
fn the_gallery_pages_both_ways_from_any_row_without_gaps_or_repeats() {
    let store = gallery();
    // Down from the newest, three at a time.
    let first = store.chat_media(ALI, 3, None, None, &[]).unwrap();
    assert_eq!(ids(&first), ["P6", "P5", "P4"]);
    assert_eq!(first.newer, None);
    assert_eq!(first.older, Some(cursor(4_000, "P4")));
    let second = store
        .chat_media(ALI, 3, first.older.as_ref(), None, &[])
        .unwrap();
    assert_eq!(ids(&second), ["VID", "P3", "P2"]);
    assert_eq!(second.newer, Some(cursor(3_500, "VID")));
    let third = store
        .chat_media(ALI, 3, second.older.as_ref(), None, &[])
        .unwrap();
    assert_eq!(ids(&third), ["P1"]);
    assert_eq!(third.older, None);

    // Back up: the page right above a cursor, still newest first.
    let up = store
        .chat_media(ALI, 3, None, third.newer.as_ref(), &[])
        .unwrap();
    assert_eq!(ids(&up), ["VID", "P3", "P2"]);
    assert_eq!(up.older, Some(cursor(2_000, "P2")));
    let top = store
        .chat_media(ALI, 3, None, up.newer.as_ref(), &[])
        .unwrap();
    assert_eq!(ids(&top), ["P6", "P5", "P4"]);
    assert_eq!(top.newer, None);

    // A viewer opened on the video: one row is not in the gallery page of either side.
    let opened = cursor(3_500, "VID");
    let below = store.chat_media(ALI, 2, Some(&opened), None, &[]).unwrap();
    let above = store.chat_media(ALI, 2, None, Some(&opened), &[]).unwrap();
    assert_eq!(ids(&below), ["P3", "P2"]);
    assert_eq!(ids(&above), ["P5", "P4"]);
    // A window between two cursors.
    let between = store
        .chat_media(
            ALI,
            50,
            Some(&cursor(5_000, "P5")),
            Some(&cursor(2_000, "P2")),
            &[],
        )
        .unwrap();
    assert_eq!(ids(&between), ["P4", "VID", "P3"]);
    assert_eq!(between.older, Some(cursor(3_000, "P3")));
    assert_eq!(between.newer, Some(cursor(4_000, "P4")));
    // Rows of one millisecond are told apart by their id.
    let mut store = store;
    apply(
        &mut store,
        vec![
            message(media_msg(ALI, "Pa", 6_000, MediaKind::Image)),
            message(media_msg(ALI, "Pz", 6_000, MediaKind::Image)),
        ],
    );
    let page = store.chat_media(ALI, 2, None, None, &[]).unwrap();
    assert_eq!(ids(&page), ["Pz", "Pa"]);
    let next = store
        .chat_media(ALI, 2, page.older.as_ref(), None, &[])
        .unwrap();
    assert_eq!(ids(&next), ["P6", "P5"]);
}

#[test]
fn the_gallery_follows_a_merge_a_clear_and_a_delete() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(media_msg(ALI_LID, "L1", 1_000, MediaKind::Image)),
            message(media_msg(ALI, "N1", 2_000, MediaKind::Image)),
        ],
    );
    store
        .identity_learn(ALI_LID, ALI, Source::Usync, 3_000)
        .unwrap();
    // The old key reads the merged chat.
    for chat in [ALI, ALI_LID] {
        let page = store.chat_media(chat, 10, None, None, &[]).unwrap();
        assert_eq!(ids(&page), ["N1", "L1"]);
        assert!(page.items.iter().all(|row| row.chat == ALI));
    }
    // A message deleted later leaves the gallery; its row stays in the thread.
    apply(
        &mut store,
        vec![meta_event(
            ALI,
            "L1",
            MessageMeta {
                revoked: true,
                ..MessageMeta::default()
            },
        )],
    );
    let page = store.chat_media(ALI, 10, None, None, &[]).unwrap();
    assert_eq!(ids(&page), ["N1"]);
    assert_eq!(store.thread(ALI, 10, None).unwrap().items.len(), 2);
    // Cleared on another device: the gallery is as empty as the thread.
    apply(
        &mut store,
        vec![GatewayEvent::ChatCleared { jid: ALI.into() }],
    );
    assert!(store.thread(ALI, 10, None).unwrap().items.is_empty());
    assert!(
        store
            .chat_media(ALI, 10, None, None, &[])
            .unwrap()
            .items
            .is_empty()
    );
}

#[test]
fn the_gallery_reads_its_index_not_the_whole_thread() {
    let store = gallery();
    let plan: Vec<String> = store
        .conn
        .prepare(
            "EXPLAIN QUERY PLAN SELECT id FROM messages
             WHERE chat = ?1 AND kind IN ('image', 'video', 'audio', 'document') AND deleted = 0
                AND COALESCE(json_extract(media_json, '$.view_once'), 0) = 0
                AND ts > ?2 AND instr(?3, ',' || kind || ',') > 0
             ORDER BY ts DESC, id DESC LIMIT 10",
        )
        .unwrap()
        .query_map(rusqlite::params![ALI, 0, ",image,"], |row| {
            row.get::<_, String>(3)
        })
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap();
    assert!(
        plan.iter().any(|step| step.contains("messages_gallery")),
        "{plan:?}"
    );
    assert!(
        !plan.iter().any(|step| step.contains("TEMP B-TREE")),
        "{plan:?}"
    );
}

// ---- placeholders (a view-once message someone sent, which the phone keeps)

fn placeholder(chat: &str, id: &str, ts: i64) -> WaMessage {
    WaMessage {
        body: MessageBody::Unsupported,
        ..text(chat, id, ts, "")
    }
}

fn unavailable(reason: Unavailable) -> MessageMeta {
    MessageMeta {
        unavailable: Some(reason),
        ..MessageMeta::default()
    }
}

#[test]
fn a_view_once_placeholder_is_a_row_the_chat_list_shows_and_nothing_is_fetched() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(placeholder(ALI, "VO", 1_000)),
            meta_event(ALI, "VO", unavailable(Unavailable::ViewOnce)),
        ],
    );
    let stored = row(&store, ALI, "VO");
    assert_eq!(stored.kind, Kind::Unsupported);
    assert_eq!(stored.unavailable, Some(Unavailable::ViewOnce));
    assert!(stored.media.is_none() && !stored.has_thumbnail);
    let json = serde_json::to_value(&stored).unwrap();
    assert_eq!(json["kind"], "unsupported");
    assert_eq!(json["unavailable"], "view_once");

    let chats = store.chats(false).unwrap();
    let last = chats[0].last.as_ref().unwrap();
    assert_eq!(
        (last.id.as_str(), last.unavailable),
        ("VO", Some(Unavailable::ViewOnce))
    );
    assert_eq!(chats[0].unread, 1);

    for fetch in [Fetch::Tap, Fetch::Auto(WIFI)] {
        let asked = store.media_enqueue(ALI, "VO", fetch, NOW).unwrap();
        assert_eq!((asked.queued, asked.decision), (false, Decision::Never));
    }
    // A text row says nothing about a placeholder.
    apply(&mut store, vec![message(text(ALI, "T", 2_000, "hi"))]);
    let json = serde_json::to_value(row(&store, ALI, "T")).unwrap();
    assert!(json.get("unavailable").is_none(), "{json}");
}

#[test]
fn a_page_names_the_placeholder_and_the_content_that_comes_later_replaces_it() {
    let mut store = store();
    let page = [
        RichMessage {
            message: placeholder(ALI, "BOT", 2_000),
            meta: unavailable(Unavailable::Bot),
        },
        RichMessage {
            message: placeholder(ALI, "LATER", 1_000),
            meta: unavailable(Unavailable::Other),
        },
    ];
    store.store_page(ALI, None, 50, &page, NOW).unwrap();
    assert_eq!(row(&store, ALI, "BOT").unavailable, Some(Unavailable::Bot));
    assert_eq!(
        row(&store, ALI, "LATER").unavailable,
        Some(Unavailable::Other)
    );
    // A bare repeat of the message keeps what is stored.
    apply(&mut store, vec![message(placeholder(ALI, "BOT", 2_000))]);
    assert_eq!(row(&store, ALI, "BOT").unavailable, Some(Unavailable::Bot));
    // The real content after all: a text, no placeholder any more.
    apply(&mut store, vec![message(text(ALI, "BOT", 2_000, "hello"))]);
    let now = row(&store, ALI, "BOT");
    assert_eq!(
        (now.kind, now.text.as_deref(), now.unavailable),
        (Kind::Text, Some("hello"), None)
    );
}

#[test]
fn a_placeholder_survives_a_merge_of_the_two_forms_of_a_chat() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(placeholder(ALI_LID, "VO", 1_000)),
            meta_event(ALI_LID, "VO", unavailable(Unavailable::ViewOnce)),
            GatewayEvent::IdentityMapped {
                lid: ALI_LID.into(),
                pn: ALI.into(),
                source: "receipt".into(),
                learned_ms: 5,
            },
        ],
    );
    assert_eq!(
        row(&store, ALI, "VO").unavailable,
        Some(Unavailable::ViewOnce)
    );
}
