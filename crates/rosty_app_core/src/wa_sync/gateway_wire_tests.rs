//! The gateway's own words on the wire reach the store: the epoch of `hello`, `resync` and `state`, the
//! `account_changed` event, the fields of `history_synced`, and a whole status answer.

use rosty_wa_proto::{
    EventEnvelope, GatewayEvent, HistoryStatus, HistorySyncType, InstanceState, InstanceStatus,
};

use super::identity_tests::{apply, count, env, message, store, text, thread_ids};
use super::link_state_tests::{assert_wiped, full, status};
use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const OWN: &str = "100000000009@s.whatsapp.net";
const OTHER: &str = "100000000008@s.whatsapp.net";

fn hello(epoch: &str) -> GatewayEvent {
    GatewayEvent::Hello {
        epoch: epoch.into(),
    }
}

fn resync(epoch: Option<&str>) -> GatewayEvent {
    GatewayEvent::Resync {
        epoch: epoch.map(str::to_owned),
    }
}

fn account_changed(old_pn: Option<&str>, new_pn: &str) -> GatewayEvent {
    GatewayEvent::AccountChanged {
        old_pn: old_pn.map(str::to_owned),
        new_pn: new_pn.into(),
    }
}

fn instance(own: Option<&str>) -> InstanceStatus {
    InstanceStatus {
        instance_id: "inst1".into(),
        state: InstanceState::Connected,
        own_jid: own.map(str::to_owned),
        push_name: None,
        own_lid: None,
        epoch: None,
        account_epoch: None,
        history: None,
    }
}

fn wire_history(
    state: rosty_wa_proto::HistoryState,
    progress: Option<u32>,
    messages: u64,
) -> HistoryStatus {
    HistoryStatus {
        state,
        progress,
        chats: 7,
        messages,
        oldest_ms: Some(4_000),
    }
}

fn seq_and_refresh(store: &Store) -> (Option<u64>, bool) {
    let plan = store.plan(None).unwrap();
    (plan.after_seq, plan.refresh_chats)
}

/// A store at epoch `e1` that applied two events and refreshed its chat list.
fn settled() -> Store {
    let mut store = store();
    store
        .note_status(&status(Some("e1"), None, None), 1)
        .unwrap();
    apply(
        &mut store,
        vec![
            message(text(ALI, "A1", 1_000, "one")),
            message(text(ALI, "A2", 2_000, "two")),
        ],
    );
    store.store_chats(&[], 3_000, None, None).unwrap();
    assert_eq!(seq_and_refresh(&store), (Some(2), false));
    store
}

// ---- hello

#[test]
fn a_hello_of_the_stored_epoch_changes_nothing() {
    let mut store = settled();
    // The socket resumed at the stored `seq`, and one that started at the gateway's newest event: neither
    // moves the `seq`, so nothing between is passed over.
    for seq in [2, 40] {
        let applied = store.apply_events(&[env(seq, hello("e1"))]).unwrap();
        assert_eq!(
            (applied.applied, applied.resync, applied.last_seq),
            (0, false, Some(2))
        );
    }
    assert_eq!(seq_and_refresh(&store), (Some(2), false));
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e1"));
    // The first epoch a store without a `seq` hears is stored and is no gap.
    let mut fresh = super::identity_tests::store();
    fresh.store_chats(&[], 1_000, None, None).unwrap();
    let applied = fresh.apply_events(&[env(12, hello("e1"))]).unwrap();
    assert_eq!(
        (applied.applied, applied.resync, applied.last_seq),
        (0, false, None)
    );
    assert_eq!(fresh.epoch().unwrap().as_deref(), Some("e1"));
    assert_eq!(seq_and_refresh(&fresh), (None, false));
}

#[test]
fn a_hello_of_another_epoch_is_a_gap_as_a_status_answer_is() {
    let mut store = settled();
    // The gateway restarted; the socket was opened with `after=2`, which the new process happens to know.
    let applied = store
        .apply_events(&[
            env(2, hello("e2")),
            env(3, message(text(ALI, "A3", 3_000, "after the restart"))),
        ])
        .unwrap();
    assert!(applied.resync);
    assert_eq!((applied.applied, applied.last_seq), (2, Some(3)));
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e2"));
    assert_eq!(seq_and_refresh(&store), (Some(3), true));
    assert_eq!(thread_ids(&store, ALI), ["A3", "A2", "A1"]);
    // The status answer of the same process then finds no gap.
    assert!(
        !store
            .note_status(&status(Some("e2"), None, None), 5)
            .unwrap()
            .gap
    );

    // A hello alone: the stored `seq` is forgotten, and the low numbers of the new process are applied.
    let mut store = settled();
    let applied = store.apply_events(&[env(1, hello("e2"))]).unwrap();
    assert_eq!(
        (applied.applied, applied.resync, applied.last_seq),
        (1, true, None)
    );
    assert_eq!(seq_and_refresh(&store), (None, true));
    let applied = store
        .apply_events(&[env(1, message(text(ALI, "A3", 3_000, "low seq")))])
        .unwrap();
    assert_eq!((applied.applied, applied.last_seq), (1, Some(1)));
    // The same batch handed in again finds the epoch it stored.
    let again = store.apply_events(&[env(1, hello("e2"))]).unwrap();
    assert_eq!((again.applied, again.resync), (0, false));

    // A store from before epochs that has a `seq`: nothing says the `seq` came from this process.
    let mut old = super::identity_tests::store();
    apply(&mut old, vec![message(text(ALI, "A1", 1_000, "one"))]);
    old.store_chats(&[], 2_000, None, None).unwrap();
    assert!(old.apply_events(&[env(1, hello("e1"))]).unwrap().resync);
    assert_eq!(seq_and_refresh(&old), (None, true));
}

// ---- resync

#[test]
fn a_resync_names_the_epoch_and_one_without_an_epoch_leaves_it() {
    let mut store = settled();
    // An old gateway: the `seq` is reset as before and the stored epoch stays.
    let applied = store.apply_events(&[env(9, resync(None))]).unwrap();
    assert_eq!((applied.resync, applied.last_seq), (true, Some(9)));
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e1"));
    store.store_chats(&[], 4_000, None, None).unwrap();

    // The same process: still only a resync.
    let applied = store.apply_events(&[env(12, resync(Some("e1")))]).unwrap();
    assert_eq!((applied.resync, applied.last_seq), (true, Some(12)));
    store.store_chats(&[], 5_000, None, None).unwrap();

    // Another process, with a head below the stored `seq`: the epoch is stored, the `seq` is the new head,
    // and the events after it are applied.
    let applied = store
        .apply_events(&[
            env(4, resync(Some("e2"))),
            env(5, message(text(ALI, "A3", 3_000, "after the restart"))),
        ])
        .unwrap();
    assert_eq!(
        (applied.applied, applied.resync, applied.last_seq),
        (2, true, Some(5))
    );
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e2"));
    assert_eq!(seq_and_refresh(&store), (Some(5), true));
    assert_eq!(thread_ids(&store, ALI), ["A3", "A2", "A1"]);
    assert!(
        !store
            .note_status(&status(Some("e2"), None, None), 6)
            .unwrap()
            .gap
    );
}

// ---- account_changed

#[test]
fn an_account_changed_event_in_a_batch_empties_the_store_at_its_place() {
    let mut store = store();
    full(&mut store);
    let applied = apply(
        &mut store,
        vec![
            message(text(ALI, "OLD", 8_000, "last of the old account")),
            account_changed(Some(OWN), OTHER),
            message(text(ALI, "N1", 10_000, "first of the new account")),
        ],
    );
    assert!(applied.account_changed && applied.resync);
    assert_eq!(applied.delete_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
    assert_eq!(applied.applied, 3);
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
    assert!(store.identity().is_me(OTHER) && !store.identity().is_me(OWN));
    assert!(store.plan(None).unwrap().refresh_chats);

    // The same event handed in again (its `seq` is not news) does nothing.
    let seq = store.sync_state().unwrap().last_seq.unwrap();
    let again = store
        .apply_events(&[env(seq - 1, account_changed(Some(OWN), OTHER))])
        .unwrap();
    assert!(!again.account_changed && again.delete_files.is_empty());
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
}

#[test]
fn an_account_changed_event_sweeps_what_arrived_after_a_status_wipe() {
    let mut store = store();
    full(&mut store);
    // The status answer names the new account first: the store is emptied and waits for the announcement.
    assert!(
        store
            .note_status(&status(Some("e1"), Some(2), Some(OTHER)), 9_000)
            .unwrap()
            .account_changed
    );
    // The socket replays the ring: old messages, a `state` event of the old account (not believed, and its
    // lower account epoch is not stored), the announcement, then the new account.
    let mut old_state = instance(Some(OWN));
    old_state.epoch = Some("e1".into());
    old_state.account_epoch = Some(1);
    old_state.history = Some(wire_history(
        rosty_wa_proto::HistoryState::Done,
        Some(100),
        9,
    ));
    let applied = apply(
        &mut store,
        vec![
            message(text(ALI, "STALE", 8_500, "of the old account")),
            GatewayEvent::State(old_state),
            account_changed(Some(OWN), OTHER),
            message(text(ALI, "N1", 10_000, "new")),
        ],
    );
    assert!(applied.account_changed && applied.resync);
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
    assert!(store.identity().is_me(OTHER) && !store.identity().is_me(OWN));
    // The history report of the old account was not taken, and the account epoch did not go back: the next
    // status answer of the new account wipes nothing.
    assert_eq!(store.history().unwrap(), None);
    let noted = store
        .note_status(&status(Some("e1"), Some(2), Some(OTHER)), 11_000)
        .unwrap();
    assert!(!noted.account_changed);
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
}

// ---- history_synced

#[test]
fn a_history_batch_keeps_its_kind_its_oldest_message_and_counts_its_messages() {
    let mut store = store();
    let report = HistoryReport {
        state: HistoryState::Syncing,
        progress: Some(10),
        chats: 3,
        messages: 100,
        oldest_ms: Some(5_000),
    };
    store
        .note_status(
            &StatusNote {
                history: Some(&report),
                ..StatusNote::default()
            },
            1_000,
        )
        .unwrap();
    let applied = apply(
        &mut store,
        vec![
            GatewayEvent::HistorySynced {
                chats: 2,
                progress: Some(40),
                sync_type: Some(HistorySyncType::Full),
                messages: 3_500,
                oldest_ms: Some(4_000),
            },
            // A later batch that is not older, and a batch of an old gateway: nothing is lost.
            GatewayEvent::HistorySynced {
                chats: 1,
                progress: None,
                sync_type: Some(HistorySyncType::OnDemand),
                messages: 20,
                oldest_ms: Some(6_000),
            },
            GatewayEvent::HistorySynced {
                chats: 1,
                progress: None,
                sync_type: None,
                messages: 0,
                oldest_ms: None,
            },
        ],
    );
    assert!(applied.resync);
    let history = store.history().unwrap().unwrap();
    assert_eq!(
        (
            history.state,
            history.progress,
            history.chats,
            history.messages,
            history.oldest_ms,
            history.sync_type.as_deref(),
        ),
        (
            HistoryState::Syncing,
            Some(40),
            3,
            3_620,
            Some(4_000),
            Some("on_demand")
        )
    );
    // The next status answer says how many messages the gateway holds.
    store
        .note_status(
            &StatusNote {
                history: Some(&HistoryReport {
                    messages: 3_600,
                    ..report
                }),
                ..StatusNote::default()
            },
            2_000,
        )
        .unwrap();
    assert_eq!(store.history().unwrap().unwrap().messages, 3_600);
    for (kind, name) in [
        (HistorySyncType::Initial, "initial"),
        (HistorySyncType::Recent, "recent"),
        (HistorySyncType::Other, "other"),
    ] {
        apply(
            &mut store,
            vec![GatewayEvent::HistorySynced {
                chats: 0,
                progress: None,
                sync_type: Some(kind),
                messages: 0,
                oldest_ms: None,
            }],
        );
        assert_eq!(
            store.history().unwrap().unwrap().sync_type.as_deref(),
            Some(name)
        );
    }
}

// ---- state

#[test]
fn a_state_event_is_taken_in_as_a_status_answer_is() {
    let mut store = settled();
    // The same epoch, with the account epoch and a history report: both are stored, nothing is a gap.
    let mut first = instance(Some(OWN));
    first.epoch = Some("e1".into());
    first.account_epoch = Some(1);
    first.history = Some(wire_history(
        rosty_wa_proto::HistoryState::Syncing,
        Some(250),
        48_000,
    ));
    let applied = apply(&mut store, vec![GatewayEvent::State(first.clone())]);
    assert!(!applied.resync && !applied.account_changed);
    assert!(store.identity().is_me(OWN));
    let at_ms = 1_000_003;
    assert_eq!(
        store.history().unwrap(),
        Some(HistoryProgress {
            state: HistoryState::Syncing,
            progress: Some(100),
            chats: 7,
            messages: 48_000,
            oldest_ms: Some(4_000),
            sync_type: None,
            updated_ms: at_ms,
        })
    );
    // A state without own ids still tells the history, and a state from a newer gateway keeps the stored
    // state.
    let mut later = instance(None);
    later.history = Some(wire_history(
        rosty_wa_proto::HistoryState::Unknown,
        Some(70),
        50_000,
    ));
    apply(&mut store, vec![GatewayEvent::State(later)]);
    let history = store.history().unwrap().unwrap();
    assert_eq!(
        (history.state, history.progress, history.messages),
        (HistoryState::Syncing, Some(70), 50_000)
    );

    // A raised account epoch in the stream is another account: the store is emptied at the event, and the
    // announcement that follows finds nothing to sweep.
    let mut raised = instance(Some(OTHER));
    raised.epoch = Some("e1".into());
    raised.account_epoch = Some(2);
    let applied = apply(
        &mut store,
        vec![
            GatewayEvent::State(raised),
            message(text(ALI, "N1", 10_000, "new")),
            account_changed(Some(OWN), OTHER),
        ],
    );
    assert!(applied.account_changed && applied.resync);
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
    assert_eq!(store.history().unwrap(), None);
    assert!(
        !store
            .note_status(&status(Some("e1"), Some(2), Some(OTHER)), 11_000)
            .unwrap()
            .account_changed
    );

    // Another epoch in a `state` event is a gap: its low `seq` is applied and becomes the stored one.
    let mut restarted = instance(Some(OTHER));
    restarted.epoch = Some("e2".into());
    restarted.account_epoch = Some(2);
    store.store_chats(&[], 12_000, None, None).unwrap();
    let last = store.sync_state().unwrap().last_seq.unwrap();
    assert!(last > 1);
    let applied = store
        .apply_events(&[env(1, GatewayEvent::State(restarted))])
        .unwrap();
    assert_eq!(
        (applied.applied, applied.resync, applied.last_seq),
        (1, true, Some(1))
    );
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e2"));
    assert_eq!(seq_and_refresh(&store), (Some(1), true));
    assert_eq!(thread_ids(&store, ALI), ["N1"]);
}

// ---- a status answer handed in whole

#[test]
fn a_whole_status_answer_is_noted_field_by_field() {
    let mut store = store();
    full(&mut store);
    // The same gateway and account: only the history report changes.
    let mut answer = instance(Some(OWN));
    answer.epoch = Some("e1".into());
    answer.account_epoch = Some(1);
    answer.history = Some(wire_history(
        rosty_wa_proto::HistoryState::Done,
        Some(100),
        900,
    ));
    let noted = store.note_instance_status(&answer, 9_000).unwrap();
    assert!(!noted.gap && !noted.account_changed && !noted.resync);
    let history = noted.history.unwrap();
    assert_eq!(
        (history.state, history.messages, history.updated_ms),
        (HistoryState::Done, 900, 9_000)
    );

    // `identity_observe` takes the status the same way: another epoch is a gap (`resync`).
    answer.epoch = Some("e2".into());
    let seen = Observed {
        status: Some(&answer),
        ..Observed::default()
    };
    let observed = store.identity_observe(&seen, 9_100).unwrap();
    assert!(observed.resync && !observed.account_changed);
    assert_eq!(store.epoch().unwrap().as_deref(), Some("e2"));
    assert_eq!(seq_and_refresh(&store), (None, true));
    assert!(count(&store.conn, "SELECT COUNT(*) FROM messages") > 0);

    // A raised account epoch without an own number, as a logged out instance answers: the store is emptied.
    let mut other = instance(None);
    other.epoch = Some("e2".into());
    other.account_epoch = Some(2);
    let seen = Observed {
        status: Some(&other),
        ..Observed::default()
    };
    let observed = store.identity_observe(&seen, 9_200).unwrap();
    assert!(observed.account_changed && observed.resync);
    assert_eq!(observed.delete_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
    assert_wiped(&store, 0);

    // A status of an old gateway names none of it and changes nothing.
    let mut store = settled();
    let seen = Observed {
        status: Some(&instance(None)),
        ..Observed::default()
    };
    assert_eq!(
        store.identity_observe(&seen, 9_300).unwrap(),
        IdentityApplied::default()
    );
    assert_eq!(seq_and_refresh(&store), (Some(2), false));
    assert_eq!(store.history().unwrap(), None);
}

// ---- the frames as the gateway writes them

/// The JSON pinned in `rosty_wa_proto` (`epoch_account_and_history_events_are_pinned`), in the order a socket
/// sends it after a gateway restart during which the account changed.
#[test]
fn a_batch_decoded_from_the_gateway_s_frames_is_applied() {
    let mut store = store();
    full(&mut store);
    let last = store.sync_state().unwrap().last_seq.unwrap();
    let frames = [
        r#"{"type":"hello","epoch":"9f2c41d07ab35e68"}"#,
        r#"{"type":"resync","epoch":"9f2c41d07ab35e68"}"#,
        r#"{"type":"history_synced","chats":12,"progress":40,"sync_type":"full","messages":3500,"oldest_ms":1500000000000}"#,
        r#"{"type":"state","instance_id":"i","state":"connected","own_jid":"100000000001@s.whatsapp.net","push_name":null,"epoch":"9f2c41d07ab35e68","account_epoch":2,"history":{"state":"syncing","progress":55,"chats":120,"messages":48000,"oldest_ms":1500000000000}}"#,
        r#"{"type":"account_changed","old_pn":"100000000009@s.whatsapp.net","new_pn":"100000000001@s.whatsapp.net"}"#,
        r#"{"type":"history_synced","chats":0,"progress":null,"sync_type":"on_demand","messages":0}"#,
    ];
    let envelopes: Vec<EventEnvelope> = frames
        .iter()
        .enumerate()
        .map(|(index, frame)| {
            let event: serde_json::Value = serde_json::from_str(frame).unwrap();
            serde_json::from_value(serde_json::json!({
                "seq": index as u64,
                "at_ms": 2_000_000 + index as i64,
                "event": event,
            }))
            .unwrap()
        })
        .collect();
    assert_eq!(envelopes[0].event, hello("9f2c41d07ab35e68"));
    assert!(envelopes.iter().all(|envelope| envelope.seq <= last));

    let applied = store.apply_events(&envelopes).unwrap();
    // The hello is the gap; the numbers of the new process, none above the stored `seq`, are applied.
    assert_eq!((applied.applied, applied.last_seq), (6, Some(5)));
    assert!(applied.resync && applied.account_changed);
    assert_eq!(applied.delete_files, ["/m/P1.jpg", "/m/out/send.jpg"]);
    assert_eq!(store.epoch().unwrap().as_deref(), Some("9f2c41d07ab35e68"));
    assert_eq!(seq_and_refresh(&store), (Some(5), true));
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 0);
    assert!(store.identity().is_me("100000000001@s.whatsapp.net") && !store.identity().is_me(OWN));
    // The state event's report, then the batch after it.
    assert_eq!(
        store.history().unwrap(),
        Some(HistoryProgress {
            state: HistoryState::Syncing,
            progress: Some(55),
            chats: 120,
            messages: 48_000,
            oldest_ms: Some(1_500_000_000_000),
            sync_type: Some("on_demand".into()),
            updated_ms: 2_000_005,
        })
    );
    // The status answer of that gateway agrees with all of it.
    let answer: InstanceStatus = serde_json::from_str(
        r#"{"instance_id":"i","state":"connected","own_jid":"100000000001@s.whatsapp.net","push_name":"Ada","own_lid":"9@lid","epoch":"9f2c41d07ab35e68","account_epoch":2,"history":{"state":"idle","progress":null,"chats":0,"messages":0,"oldest_ms":null}}"#,
    )
    .unwrap();
    let noted = store.note_instance_status(&answer, 3_000_000).unwrap();
    assert!(!noted.gap && !noted.account_changed);
    assert_eq!(noted.history.unwrap().state, HistoryState::Idle);
}
