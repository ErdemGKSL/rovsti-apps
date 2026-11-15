//! The gateway epoch, the account wipe, the history progress and the unlink wipe.

use rosty_core::wa_identity::Source;
use rosty_wa_proto::{GatewayEvent, MediaKind, MessageBody, WaMessage};

use super::identity_tests::{apply, count, env, message, mine, store, temp_dir, text, thread_ids};
use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const ALI_LID: &str = "777000000000001@lid";
const OWN: &str = "100000000009@s.whatsapp.net";
const OWN_LID: &str = "777000000000009@lid";
const OTHER: &str = "100000000008@s.whatsapp.net";
const GROUP: &str = "120000000000000001@g.us";

/// Every table the account's data lives in.
const ACCOUNT_TABLES: [&str; 8] = [
    "chats",
    "chat_sync",
    "messages",
    "media",
    "outbox",
    "person",
    "alias",
    "merge_log",
];

fn status(
    epoch: Option<&'static str>,
    account_epoch: Option<u32>,
    own: Option<&'static str>,
) -> StatusNote<'static> {
    StatusNote {
        epoch,
        account_epoch,
        own_pn: own,
        ..StatusNote::default()
    }
}

/// An event as the gateway writes it: read from JSON, so a field the wire gains later does not break these
/// tests.
fn wire_event(event: serde_json::Value) -> GatewayEvent {
    serde_json::from_value(event).unwrap()
}

fn state_event(own: &str) -> GatewayEvent {
    wire_event(
        serde_json::json!({"type": "state", "instance_id": "inst1", "state": "connected",
        "own_jid": own, "push_name": null}),
    )
}

fn photo(chat: &str, id: &str, ts: i64) -> GatewayEvent {
    message(WaMessage {
        body: MessageBody::Media {
            kind: MediaKind::Image,
            caption: None,
            mime: "image/jpeg".into(),
            size: 1_000,
        },
        ..text(chat, id, ts, "")
    })
}

fn send_file(file: &str) -> Action {
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
    }
}

/// A store of account [`OWN`] at gateway epoch `e1`, account epoch 1, with something in every table: two chats
/// that merged, a group, a starred photo with its cached file and thumbnail, a queued download, a text and a
/// file in the outbox, a chat list cursor, a pairs cursor and a history report.
fn full(store: &mut Store) {
    store
        .note_status(
            &StatusNote {
                own_lid: Some(OWN_LID),
                ..status(Some("e1"), Some(1), Some(OWN))
            },
            100,
        )
        .unwrap();
    apply(
        store,
        vec![
            message(text(ALI_LID, "L1", 1_000, "from the lid")),
            message(text(ALI, "A1", 2_000, "from the number")),
            photo(ALI, "P1", 3_000),
            photo(GROUP, "G1", 4_000),
            message(mine(ALI, "A2", 5_000, "mine")),
        ],
    );
    store
        .identity_learn(ALI_LID, ALI, Source::Usync, 6_000)
        .unwrap();
    store.store_chats(&[], 6_500, None, None).unwrap();
    store.set_thumbnail(ALI, "P1", b"thumb").unwrap();
    store
        .set_media_file(ALI, "P1", "/m/P1.jpg", 1_000, 7_000)
        .unwrap();
    store.media_enqueue(GROUP, "G1", Fetch::Tap, 7_100).unwrap();
    store
        .outbox_enqueue(
            "s1",
            ALI,
            &Action::Star {
                message_id: "P1".into(),
                starred: true,
            },
            7_200,
        )
        .unwrap();
    store
        .outbox_enqueue(
            "c1",
            ALI,
            &Action::SendText {
                text: "unsent".into(),
                quoted_id: None,
            },
            7_300,
        )
        .unwrap();
    store
        .outbox_enqueue("c2", ALI, &send_file("/m/out/send.jpg"), 7_400)
        .unwrap();
    store
        .identity_catch_up(&rosty_wa_proto::IdentityMappings {
            items: Vec::new(),
            next: Some("41".into()),
        })
        .unwrap();
    store
        .note_status(
            &StatusNote {
                history: Some(&HistoryReport {
                    state: HistoryState::Syncing,
                    progress: Some(40),
                    chats: 3,
                    messages: 5,
                    oldest_ms: Some(1_000),
                }),
                ..StatusNote::default()
            },
            7_500,
        )
        .unwrap();
    for table in ACCOUNT_TABLES {
        assert!(
            count(&store.conn, &format!("SELECT COUNT(*) FROM {table}")) > 0,
            "{table} is empty before the test"
        );
    }
}

fn rows(store: &Store) -> Vec<i64> {
    ACCOUNT_TABLES
        .iter()
        .map(|table| count(&store.conn, &format!("SELECT COUNT(*) FROM {table}")))
        .collect()
}

/// Nothing of the old account is left; `own` is how many own ids the new account has named so far (each is a
/// person with one alias).
fn assert_wiped(store: &Store, own: i64) {
    assert_eq!(rows(store), [0, 0, 0, 0, 0, own, own, 0]);
    let plan = store.plan(None).unwrap();
    assert!(plan.refresh_chats && plan.fetch.is_empty());
    assert_eq!(plan.identity_since, None);
    assert_eq!(store.history().unwrap(), None);
    assert!(store.chats(true).unwrap().is_empty());
    assert!(store.starred(10, None).unwrap().items.is_empty());
    assert!(store.outbox().unwrap().is_empty());
    assert_eq!(store.media_bytes().unwrap(), 0);
    assert!(store.rekeyed_since(0).unwrap().is_empty());
    assert!(!store.identity().is_me(OWN));
}

// ---- epoch

#[test]
fn another_gateway_epoch_is_a_gap_and_the_new_one_is_stored() {
    let mut store = store();
    // An old gateway names no epoch: nothing changes, whatever is stored.
    assert!(!store.note_status(&StatusNote::default(), 1).unwrap().gap);
    assert_eq!(store.epoch().unwrap(), None);
    // The first epoch of a store that never applied an event is no gap.
    let first = store
        .note_status(&status(Some("e1"), None, None), 2)
        .unwrap();
    assert!(!first.gap && !first.account_changed);
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e1"));

    apply(
        &mut store,
        vec![
            message(text(ALI, "A1", 1_000, "one")),
            message(text(ALI, "A2", 2_000, "two")),
        ],
    );
    store.store_chats(&[], 3_000, None, None).unwrap();
    let plan = store.plan(None).unwrap();
    assert_eq!((plan.after_seq, plan.refresh_chats), (Some(2), false));
    // The same epoch, and no epoch at all: still no gap.
    for epoch in [Some("e1"), None, Some("")] {
        assert!(
            !store
                .note_status(&status(epoch, None, None), 4)
                .unwrap()
                .gap
        );
    }
    assert_eq!(store.plan(None).unwrap().after_seq, Some(2));

    // The gateway restarted: its sequence starts over, so the stored `seq` is forgotten and the chat list is
    // refreshed, as after a `resync`.
    let noted = store
        .note_status(&status(Some("e2"), None, None), 5)
        .unwrap();
    assert!(noted.gap && noted.resync && !noted.account_changed);
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e2"));
    let plan = store.plan(None).unwrap();
    assert_eq!((plan.after_seq, plan.refresh_chats), (None, true));
    // Nothing stored is dropped, and the new process's low sequence numbers are applied, not skipped.
    assert_eq!(thread_ids(&store, ALI), ["A2", "A1"]);
    let applied = store
        .apply_events(&[env(1, message(text(ALI, "A3", 3_000, "after the restart")))])
        .unwrap();
    assert_eq!((applied.applied, applied.last_seq), (1, Some(1)));
    assert_eq!(thread_ids(&store, ALI), ["A3", "A2", "A1"]);
    assert!(
        !store
            .note_status(&status(Some("e2"), None, None), 6)
            .unwrap()
            .gap
    );
}

#[test]
fn the_first_epoch_a_store_with_a_seq_sees_is_a_gap() {
    // A store from before epochs: nothing says its `seq` came from the process that now names itself.
    let mut store = store();
    apply(&mut store, vec![message(text(ALI, "A1", 1_000, "one"))]);
    store.store_chats(&[], 2_000, None, None).unwrap();
    let noted = store
        .note_status(&status(Some("e1"), None, None), 3)
        .unwrap();
    assert!(noted.gap);
    assert_eq!(store.plan(None).unwrap().after_seq, None);
    assert!(store.plan(None).unwrap().refresh_chats);
}

#[test]
fn the_epoch_survives_a_reopen() {
    let dir = temp_dir("epoch");
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        store
            .note_status(&status(Some("e1"), Some(3), None), 1)
            .unwrap();
        apply(&mut store, vec![message(text(ALI, "A1", 1_000, "one"))]);
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e1"));
    assert!(
        !store
            .note_status(&status(Some("e1"), Some(3), None), 2)
            .unwrap()
            .gap
    );
    assert!(
        store
            .note_status(&status(Some("e9"), Some(3), None), 3)
            .unwrap()
            .gap
    );
    let _ = std::fs::remove_dir_all(dir);
}

// ---- account

#[test]
fn the_same_account_linked_again_keeps_everything() {
    let mut store = store();
    full(&mut store);
    let before = rows(&store);
    let history = store.history().unwrap();
    // The same number with a device, the same account epoch, and the same again through the event stream.
    let relinked = store
        .note_status(
            &StatusNote {
                epoch: Some("e1"),
                account_epoch: Some(1),
                own_pn: Some("100000000009:7@s.whatsapp.net"),
                own_lid: Some(OWN_LID),
                history: None,
            },
            8_000,
        )
        .unwrap();
    assert!(!relinked.account_changed && !relinked.gap);
    assert!(relinked.delete_files.is_empty());
    let applied = apply(&mut store, vec![state_event(OWN)]);
    assert!(!applied.account_changed && applied.delete_files.is_empty());
    let event = store.note_account_event(Some(OWN), OWN, 8_100).unwrap();
    assert!(!event.account_changed);
    assert_eq!(rows(&store), before);
    assert_eq!(store.history().unwrap(), history);
    assert_eq!(
        thread_ids(&store, ALI),
        ["c2", "c1", "A2", "P1", "A1", "L1"]
    );
    assert!(store.identity().is_me(OWN) && store.identity().is_me(OWN_LID));
    assert_eq!(
        store.plan(None).unwrap().identity_since.as_deref(),
        Some("41")
    );
}

#[test]
fn an_unknown_own_number_or_account_epoch_keeps_everything() {
    // The store knows its account, the gateway does not say: no number, no epoch, a LID alone, text that is not
    // a number.
    let mut store = store();
    full(&mut store);
    let before = rows(&store);
    let silent = [
        status(Some("e1"), None, None),
        status(None, None, None),
        StatusNote {
            own_lid: Some(OWN_LID),
            ..StatusNote::default()
        },
        status(Some("e1"), None, Some("not a number")),
        status(Some("e1"), None, Some(GROUP)),
    ];
    for note in silent {
        let noted = store.note_status(&note, 8_000).unwrap();
        assert!(!noted.account_changed, "{note:?}");
        assert_eq!(rows(&store), before, "{note:?}");
    }
    let event = store
        .note_account_event(None, "not a number", 8_100)
        .unwrap();
    assert!(!event.account_changed);
    assert_eq!(rows(&store), before);

    // The store does not know its account, the gateway says: the number and the epoch are learned, nothing goes.
    let mut fresh = super::identity_tests::store();
    apply(
        &mut fresh,
        vec![
            message(text(ALI, "A1", 1_000, "kept")),
            photo(ALI, "P1", 2_000),
        ],
    );
    fresh
        .set_media_file(ALI, "P1", "/m/P1.jpg", 1_000, 3_000)
        .unwrap();
    let learned = fresh
        .note_status(&status(Some("e1"), Some(4), Some(OTHER)), 4_000)
        .unwrap();
    assert!(!learned.account_changed && learned.delete_files.is_empty());
    assert!(fresh.identity().is_me(OTHER));
    assert_eq!(thread_ids(&fresh, ALI), ["P1", "A1"]);
    // Nor does an event that names the new number only.
    let mut unnamed = super::identity_tests::store();
    apply(&mut unnamed, vec![message(text(ALI, "A1", 1_000, "kept"))]);
    let event = unnamed.note_account_event(None, OTHER, 2_000).unwrap();
    assert!(!event.account_changed);
    assert_eq!(thread_ids(&unnamed, ALI), ["A1"]);
    assert!(unnamed.identity().is_me(OTHER));
}

#[test]
fn a_lower_account_epoch_is_stored_and_wipes_nothing() {
    let mut store = store();
    full(&mut store);
    let before = rows(&store);
    let noted = store
        .note_status(&status(Some("e1"), Some(0), Some(OWN)), 8_000)
        .unwrap();
    assert!(!noted.account_changed);
    assert_eq!(rows(&store), before);
    // The next raise is measured from what the gateway said last.
    let raised = store
        .note_status(&status(Some("e1"), Some(1), Some(OWN)), 8_100)
        .unwrap();
    assert!(raised.account_changed);
}

#[test]
fn another_own_number_wipes_the_account_and_the_next_sync_starts_clean() {
    let mut store = store();
    full(&mut store);
    let seq_before = store.plan(None).unwrap().after_seq;
    let merge_seq = store.rekeyed_since(0).unwrap().last().unwrap().seq;
    store.set_media_cap(Some(123_456)).unwrap();

    let noted = store
        .note_status(&status(Some("e1"), Some(1), Some(OTHER)), 9_000)
        .unwrap();
    assert!(noted.account_changed && noted.resync && !noted.gap);
    // The cached file and the file the outbox was about to send.
    assert_eq!(noted.delete_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
    assert_eq!(noted.history, None);
    assert_wiped(&store, 1);
    assert!(store.identity().is_me(OTHER));
    // The gateway's stream and the settings are not the account's: they stay.
    assert_eq!(store.plan(None).unwrap().after_seq, seq_before);
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e1"));
    assert_eq!(store.media_cap().unwrap(), Some(123_456));
    // Saying it again wipes nothing more.
    let again = store
        .note_status(&status(Some("e1"), Some(1), Some(OTHER)), 9_100)
        .unwrap();
    assert!(!again.account_changed && again.delete_files.is_empty());

    // The new account syncs into an empty store: its chat list, a page, a pair and its merge.
    let last = text(ALI, "N2", 20_000, "new account");
    store
        .store_chats(
            &[super::identity_tests::listed(ALI, Some(last.clone()), 1)],
            21_000,
            None,
            None,
        )
        .unwrap();
    assert_eq!(store.chats(true).unwrap().len(), 1);
    store
        .store_page(
            ALI,
            None,
            50,
            &[
                rosty_wa_proto::RichMessage::from(last),
                rosty_wa_proto::RichMessage::from(text(ALI, "N1", 19_000, "older")),
            ],
            21_100,
        )
        .unwrap();
    assert_eq!(thread_ids(&store, ALI), ["N2", "N1"]);
    assert!(!store.plan(None).unwrap().refresh_chats);
    apply(
        &mut store,
        vec![message(text(ALI_LID, "N3", 22_000, "lid"))],
    );
    let merged = store
        .identity_learn(ALI_LID, ALI, Source::Usync, 23_000)
        .unwrap();
    // A consumer's cursor never sees a step number twice.
    assert_eq!(merged.rekeyed.len(), 1);
    assert!(merged.rekeyed[0].seq > merge_seq);
    assert_eq!(thread_ids(&store, ALI), ["N3", "N2", "N1"]);
    super::identity_tests::assert_tables_match_map(&store);
}

#[test]
fn the_wipe_is_kept_across_a_reopen() {
    let dir = temp_dir("account-wipe");
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        full(&mut store);
        let noted = store
            .note_status(&status(Some("e1"), Some(2), None), 9_000)
            .unwrap();
        assert!(noted.account_changed);
    }
    let store = Store::open(&dir, "inst1").unwrap();
    assert_wiped(&store, 0);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_raised_account_epoch_wipes_once_whichever_way_the_news_comes() {
    // The status names the raise before the number is known, then the number follows.
    let mut store = store();
    full(&mut store);
    let noted = store
        .note_status(&status(Some("e1"), Some(2), None), 9_000)
        .unwrap();
    assert!(noted.account_changed);
    assert_eq!(noted.delete_files.len(), 2);
    assert_wiped(&store, 0);
    apply(&mut store, vec![message(text(ALI, "N1", 10_000, "new"))]);
    let named = store
        .note_status(&status(Some("e1"), Some(2), Some(OTHER)), 10_100)
        .unwrap();
    assert!(!named.account_changed);
    assert_eq!(thread_ids(&store, ALI), ["N1"]);

    // The event stream names the new number first, then the status names the raise.
    let mut store = super::identity_tests::store();
    full(&mut store);
    let applied = apply(
        &mut store,
        vec![
            message(text(ALI, "OLD", 8_000, "last of the old account")),
            state_event(OTHER),
            message(text(ALI, "N1", 10_000, "first of the new account")),
        ],
    );
    assert!(applied.account_changed && applied.resync);
    assert_eq!(applied.delete_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
    assert_eq!(applied.applied, 3);
    // What came after the change in the stream is the new account's and stays.
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
    assert!(store.identity().is_me(OTHER));
    let noted = store
        .note_status(&status(Some("e1"), Some(2), Some(OTHER)), 10_100)
        .unwrap();
    assert!(!noted.account_changed);
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
    // And a later raise is seen.
    assert!(
        store
            .note_status(&status(Some("e1"), Some(3), Some(OWN)), 10_200)
            .unwrap()
            .account_changed
    );
}

#[test]
fn events_of_the_old_account_that_arrive_after_a_status_wipe_are_swept_at_the_announcement() {
    let mut store = store();
    full(&mut store);
    // The app was away while the account changed: the status says so first.
    assert!(
        store
            .note_status(&status(Some("e1"), Some(2), Some(OTHER)), 9_000)
            .unwrap()
            .account_changed
    );
    // The socket then replays what the gateway's ring still holds from before the change. A `state` event of
    // the old account among them is not believed.
    let replayed = apply(
        &mut store,
        vec![
            message(text(ALI, "STALE", 8_500, "of the old account")),
            state_event(OWN),
        ],
    );
    assert!(!replayed.account_changed);
    assert!(store.identity().is_me(OTHER) && !store.identity().is_me(OWN));
    // Meanwhile the user wrote to the new account.
    store
        .outbox_enqueue(
            "n1",
            ALI,
            &Action::SendText {
                text: "to the new account".into(),
                quoted_id: None,
            },
            9_100,
        )
        .unwrap();
    // The announcement: everything before it in the stream was the old account's.
    let swept = store.note_account_event(Some(OWN), OTHER, 9_200).unwrap();
    assert!(swept.account_changed && swept.resync);
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 0);
    assert_eq!(store.outbox().unwrap().len(), 1);
    assert!(store.identity().is_me(OTHER));
    // Once. What follows is the new account's.
    apply(&mut store, vec![message(text(ALI, "N1", 10_000, "new"))]);
    let again = store.note_account_event(Some(OWN), OTHER, 10_100).unwrap();
    assert!(!again.account_changed);
    assert_eq!(thread_ids(&store, ALI), ["n1", "N1"]);
}

#[test]
fn a_gateway_that_names_no_account_epoch_announces_nothing_so_nothing_is_waited_for() {
    let mut store = store();
    full(&mut store);
    // An older gateway: the status names the new number only.
    assert!(
        store
            .note_status(&status(Some("e1"), None, Some(OTHER)), 9_000)
            .unwrap()
            .account_changed
    );
    apply(&mut store, vec![message(text(ALI, "N1", 10_000, "new"))]);
    // No announcement will come, so there is no sweep, and a `state` event is believed as it always was.
    let event = store.note_account_event(Some(OWN), OTHER, 10_100).unwrap();
    assert!(!event.account_changed);
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
    let back = apply(&mut store, vec![state_event(OWN)]);
    assert!(back.account_changed);
    assert!(store.identity().is_me(OWN));
}

#[test]
fn a_resync_or_a_gateway_restart_ends_the_wait_for_the_announcement() {
    for restart in [false, true] {
        let mut store = store();
        full(&mut store);
        store
            .note_status(&status(Some("e1"), Some(2), Some(OTHER)), 9_000)
            .unwrap();
        if restart {
            assert!(
                store
                    .note_status(&status(Some("e2"), Some(2), Some(OTHER)), 9_100)
                    .unwrap()
                    .gap
            );
        } else {
            apply(
                &mut store,
                vec![wire_event(serde_json::json!({"type": "resync"}))],
            );
        }
        apply(&mut store, vec![message(text(ALI, "N1", 10_000, "new"))]);
        // The ring that could have held old events is gone: nothing is swept.
        let event = store.note_account_event(Some(OWN), OTHER, 10_100).unwrap();
        assert!(!event.account_changed, "restart: {restart}");
        assert_eq!(thread_ids(&store, ALI), ["N1"]);
    }
}

#[test]
fn an_announced_account_change_wipes_a_store_of_the_old_account() {
    let mut store = store();
    full(&mut store);
    let event = store.note_account_event(Some(OWN), OTHER, 9_000).unwrap();
    assert!(event.account_changed);
    assert_eq!(event.delete_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
    assert_wiped(&store, 1);
    assert!(store.identity().is_me(OTHER));
    // A store that never learned its own number believes an event that names two different ones.
    let mut unnamed = super::identity_tests::store();
    apply(&mut unnamed, vec![message(text(ALI, "A1", 1_000, "old"))]);
    assert!(
        unnamed
            .note_account_event(Some(OWN), OTHER, 2_000)
            .unwrap()
            .account_changed
    );
    assert_eq!(count(&unnamed.conn, "SELECT COUNT(*) FROM messages"), 0);
}

// ---- history

fn batch(sync_type: Option<&'static str>, oldest_ms: Option<i64>) -> HistoryBatch<'static> {
    HistoryBatch {
        sync_type,
        oldest_ms,
        progress: None,
    }
}

#[test]
fn history_progress_is_stored_as_reported_and_read_after_a_reopen() {
    let dir = temp_dir("history");
    let report = |state, progress, messages| HistoryReport {
        state,
        progress,
        chats: 12,
        messages,
        oldest_ms: Some(5_000),
    };
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        assert_eq!(store.history().unwrap(), None);
        let noted = store
            .note_status(
                &StatusNote {
                    history: Some(&report(HistoryState::Syncing, Some(250), 340)),
                    ..StatusNote::default()
                },
                1_000,
            )
            .unwrap();
        // A percentage is at most 100.
        assert_eq!(noted.history.unwrap().progress, Some(100));
        store
            .note_history_synced(&batch(Some("full"), Some(4_000)), 1_500)
            .unwrap();
        // A status without a history report leaves it as it is.
        let silent = store.note_status(&StatusNote::default(), 1_600).unwrap();
        assert_eq!(silent.history.unwrap().sync_type.as_deref(), Some("full"));
        store
            .note_status(
                &StatusNote {
                    history: Some(&report(HistoryState::Syncing, Some(60), 900)),
                    ..StatusNote::default()
                },
                2_000,
            )
            .unwrap();
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(
        store.history().unwrap(),
        Some(HistoryProgress {
            state: HistoryState::Syncing,
            progress: Some(60),
            chats: 12,
            messages: 900,
            oldest_ms: Some(5_000),
            sync_type: Some("full".into()),
            updated_ms: 2_000,
        })
    );
    // The event keeps the oldest message of both and still asks for the chat list, as before.
    let applied = apply(
        &mut store,
        vec![wire_event(
            serde_json::json!({"type": "history_synced", "chats": 3, "progress": 75}),
        )],
    );
    assert!(applied.resync);
    store
        .note_history_synced(&batch(None, Some(9_000)), 3_000)
        .unwrap();
    let history = store.history().unwrap().unwrap();
    assert_eq!(
        (
            history.oldest_ms,
            history.sync_type.as_deref(),
            history.progress,
            history.updated_ms
        ),
        (Some(5_000), Some("full"), Some(75), 3_000)
    );
    store
        .note_status(
            &StatusNote {
                history: Some(&HistoryReport {
                    state: HistoryState::Done,
                    progress: None,
                    chats: 12,
                    messages: 1_000,
                    oldest_ms: None,
                }),
                ..StatusNote::default()
            },
            4_000,
        )
        .unwrap();
    let done = store.history().unwrap().unwrap();
    assert_eq!((done.state, done.progress), (HistoryState::Done, None));
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_history_event_before_any_report_reads_as_syncing() {
    let mut store = store();
    store
        .note_history_synced(&batch(Some("initial"), None), 500)
        .unwrap();
    assert_eq!(
        store.history().unwrap(),
        Some(HistoryProgress {
            state: HistoryState::Syncing,
            sync_type: Some("initial".into()),
            updated_ms: 500,
            ..HistoryProgress::default()
        })
    );
    // The wire shape of a report: only `state` is required.
    let report: HistoryReport =
        serde_json::from_str(r#"{"state":"done","progress":null}"#).unwrap();
    assert_eq!(
        (report.state, report.progress, report.messages),
        (HistoryState::Done, None, 0)
    );
    assert_eq!(
        serde_json::to_value(store.history().unwrap().unwrap()).unwrap(),
        serde_json::json!({"state": "syncing", "chats": 0, "messages": 0, "sync_type": "initial",
            "updated_ms": 500})
    );
}

// ---- unlink

#[test]
fn wipe_closes_the_store_deletes_its_files_and_names_the_media_files() {
    let dir = temp_dir("unlink");
    let mut store = Store::open(&dir, "inst1").unwrap();
    full(&mut store);
    let db = dir.join(file_name("inst1"));
    assert!(db.exists());
    // Another instance in the same directory is not touched.
    drop(Store::open(&dir, "inst2").unwrap());

    let wiped = store.wipe().unwrap();
    assert_eq!(wiped.media_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
    assert!(wiped.deleted.contains(&db.to_string_lossy().into_owned()));
    let left: Vec<String> = std::fs::read_dir(&dir)
        .unwrap()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    assert_eq!(left, [file_name("inst2")]);
    // Linking again starts from nothing.
    let again = Store::open(&dir, "inst1").unwrap();
    assert_eq!(rows(&again), [0; 8]);
    assert_eq!(again.opened().unwrap().last_seq, None);
    drop(again);
    // A store that is not open is deleted by name; deleting what is not there is not an error.
    assert_eq!(delete_files(&dir, "inst1").unwrap().len(), 1);
    assert!(delete_files(&dir, "inst1").unwrap().is_empty());
    assert!(delete_files(&dir, "never-linked").unwrap().is_empty());
    assert!(dir.join(file_name("inst2")).exists());
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn wiping_a_store_in_memory_names_its_media_files() {
    let mut store = store();
    full(&mut store);
    let wiped = store.wipe().unwrap();
    assert!(wiped.deleted.is_empty());
    assert_eq!(wiped.media_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
}
