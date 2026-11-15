//! Identity on the way in: the identity events, the other form an id arrives with, the gateway's list of pairs,
//! and reads under an id a merge has since moved.

use rosty_core::wa_identity::{AliasKind, AliasRow, RejectReason, Source};
use rosty_wa_proto::{
    CallDirection, CallPhase, CallUpdate, GatewayEvent, GroupInfo, IdentityMapping,
    IdentityMappings, IdentitySource, InstanceState, InstanceStatus, Participant, Reaction,
    RichMessage, TypingState, WaMessage, WaProfile,
};

use super::identity_tests::{
    GROUP, LID, LID2, LID3, OWN_LID, OWN_PN, PN, PN2, apply, assert_tables_match_map, by, chat_row,
    count, dump, env, key_of, listed, message, old_file, reaction, store, temp_dir, text,
    thread_ids,
};
use super::*;

fn mapped(lid: &str, pn: &str, source: &str, learned_ms: i64) -> GatewayEvent {
    GatewayEvent::IdentityMapped {
        lid: lid.into(),
        pn: pn.into(),
        source: source.into(),
        learned_ms,
    }
}

fn state(own_jid: Option<&str>, own_lid: Option<&str>) -> GatewayEvent {
    GatewayEvent::State(InstanceStatus {
        instance_id: "inst1".into(),
        state: InstanceState::Connected,
        own_jid: own_jid.map(str::to_owned),
        push_name: None,
        own_lid: own_lid.map(str::to_owned),
    })
}

/// The alias row of `id` (a bare LID or number id).
fn alias(store: &Store, id: &str) -> AliasRow {
    let resolved = store.identity().lookup(id).unwrap();
    let person = resolved.person_id.expect("a person");
    store
        .identity()
        .aliases(person)
        .into_iter()
        .find(|row| row.user == resolved.wire.user())
        .expect("an alias row")
}

/// Whether the map holds the two ids as one person.
fn one_person(store: &Store, a: &str, b: &str) -> bool {
    let person = |text: &str| {
        store
            .identity()
            .lookup(text)
            .and_then(|found| found.person_id)
    };
    person(a).is_some() && person(a) == person(b)
}

/// The identity tables, row by row.
fn identity_rows(store: &Store) -> Vec<String> {
    dump(&store.conn)
        .into_iter()
        .filter(|row| {
            ["person:", "alias:", "merge_log:"]
                .iter()
                .any(|table| row.starts_with(table))
        })
        .collect()
}

/// A LID chat and the number chat of the same person, unread 1 each.
fn two_chats() -> Store {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "under the lid")),
            message(text(PN, "P1", 2_000, "under the number")),
        ],
    );
    store
}

fn page(items: Vec<IdentityMapping>, next: Option<&str>) -> IdentityMappings {
    IdentityMappings {
        items,
        next: next.map(str::to_owned),
    }
}

// ---- the identity events

#[test]
fn identity_mapped_joins_a_lid_chat_and_a_number_chat() {
    let mut store = two_chats();
    let applied = apply(&mut store, vec![mapped(LID, PN, "receipt", 7_000)]);
    assert_eq!(applied.applied, 1);
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(
        (
            applied.rekeyed[0].from_key.as_str(),
            applied.rekeyed[0].into_key.as_str()
        ),
        (LID, PN)
    );
    // The chat list reads differently: the merged chat is named and a refresh is asked for.
    assert!(applied.resync && applied.rejected.is_empty());
    assert_eq!(applied.chats, [PN]);
    assert_eq!(thread_ids(&store, PN), ["P1", "L1"]);
    assert!(chat_row(&store, LID).is_none());
    assert_eq!(chat_row(&store, PN).unwrap().unread, 2);
    let row = alias(&store, LID);
    assert_eq!((row.source, row.learned_ms), (Source::Receipt, 7_000));
    assert_tables_match_map(&store);
}

#[test]
fn a_source_this_build_does_not_know_is_learned_as_other() {
    let mut store = store();
    apply(&mut store, vec![mapped(LID, PN, "from_the_future", 5)]);
    assert!(one_person(&store, LID, PN));
    assert_eq!(alias(&store, LID).source, Source::Other);
}

#[test]
fn identity_number_changed_joins_the_old_and_the_new_number() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(PN, "P1", 1_000, "old number")),
            message(text(PN2, "P2", 2_000, "new number")),
            GatewayEvent::IdentityNumberChanged {
                old_pn: PN.into(),
                new_pn: PN2.into(),
                old_lid: None,
                new_lid: Some(LID.into()),
            },
        ],
    );
    assert_eq!(thread_ids(&store, PN2), ["P2", "P1"]);
    assert!(one_person(&store, PN, PN2) && one_person(&store, LID, PN2));
    assert_eq!(key_of(&store, PN), PN2);
    assert_eq!(alias(&store, PN2).source, Source::NumberChange);
    assert_tables_match_map(&store);
}

#[test]
fn a_state_event_names_the_accounts_own_ids() {
    let mut store = store();
    let applied = apply(&mut store, vec![state(Some(OWN_PN), Some(OWN_LID))]);
    assert_eq!((applied.applied, applied.last_seq), (1, Some(1)));
    assert!(store.identity().is_me(OWN_PN) && store.identity().is_me("777000000000009:4@lid"));
    assert!(one_person(&store, OWN_PN, OWN_LID));
    // A state without the account (logged out) keeps what is stored.
    apply(&mut store, vec![state(None, None)]);
    assert!(store.identity().is_me(OWN_LID));
    // An old gateway names the number only: the LID stays.
    apply(&mut store, vec![state(Some(OWN_PN), None)]);
    assert!(store.identity().is_me(OWN_LID));
    assert_tables_match_map(&store);
}

#[test]
fn a_state_event_already_applied_does_not_set_the_own_ids_again() {
    let mut store = store();
    apply(&mut store, vec![state(Some(OWN_PN), Some(OWN_LID))]);
    store.identity_learn(LID, PN, Source::Usync, 1).unwrap();
    // A replay of an earlier state that named another number must not reset the identity tables.
    let stale = env(1, state(Some(PN2), None));
    let applied = store.apply_events(&[stale]).unwrap();
    assert_eq!(applied.applied, 0);
    assert!(store.identity().is_me(OWN_PN) && !store.identity().is_me(PN2));
    assert!(one_person(&store, LID, PN));
}

#[test]
fn events_around_a_state_event_are_all_applied_in_order() {
    let mut store = store();
    let applied = apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "before")),
            state(Some(OWN_PN), Some(OWN_LID)),
            message(text(PN, "P1", 2_000, "after")),
            state(Some(OWN_PN), Some(OWN_LID)),
            mapped(LID, PN, "peer_message", 3_000),
        ],
    );
    assert_eq!((applied.applied, applied.last_seq), (5, Some(5)));
    assert_eq!(store.sync_state().unwrap().last_seq, Some(5));
    assert_eq!(applied.chats, [LID, PN]);
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(thread_ids(&store, PN), ["P1", "L1"]);
    assert!(store.identity().is_me(OWN_LID));
}

#[test]
fn pairs_that_change_nothing_are_reported_once() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            state(Some(OWN_PN), Some(OWN_LID)),
            mapped(LID, PN, "peer_message", 10),
        ],
    );
    let applied = apply(
        &mut store,
        vec![
            // Would join my LID to someone else.
            mapped(OWN_LID, PN, "peer_message", 20),
            // The LID is held by a stronger source.
            mapped(LID, PN2, "history", 30),
            mapped(LID, PN2, "history", 30),
            // Not a LID next to a number.
            mapped(PN, LID, "usync", 40),
            // The other form of a message's sender, weaker than what is held: once, however often it comes.
            message(WaMessage {
                sender_alt: Some(OWN_LID.into()),
                ..by(GROUP, PN2, "G1", 50, "hi")
            }),
            message(WaMessage {
                sender_alt: Some(OWN_LID.into()),
                ..by(GROUP, PN2, "G2", 50, "again")
            }),
        ],
    );
    let reasons: Vec<(&str, &str, RejectReason)> = applied
        .rejected
        .iter()
        .map(|rejected| (rejected.lid.as_str(), rejected.pn.as_str(), rejected.reason))
        .collect();
    assert_eq!(
        reasons,
        [
            (OWN_LID, PN, RejectReason::OwnId),
            (LID, PN2, RejectReason::Outranked),
            (PN, LID, RejectReason::Invalid),
            (OWN_LID, PN2, RejectReason::OwnId),
        ]
    );
    assert_eq!(applied.rejected[1].source, Source::History);
    assert!(applied.rekeyed.is_empty());
    assert!(one_person(&store, LID, PN) && !one_person(&store, LID, PN2));
    // The answer says nothing about pairs when there is nothing to say.
    let quiet = apply(&mut store, vec![mapped(LID, PN, "peer_message", 10)]);
    let json = serde_json::to_value(&quiet).unwrap();
    assert!(json.get("rejected").is_none() && json.get("rekeyed").is_none());
    assert_tables_match_map(&store);
}

// ---- the other form next to an id

#[test]
fn the_other_form_of_a_one_to_one_chat_is_learned_before_the_message_is_stored() {
    let mut store = two_chats();
    let applied = apply(
        &mut store,
        vec![message(WaMessage {
            chat_alt: Some(PN.into()),
            ..text("777000000000001:2@lid", "L2", 3_000, "names both")
        })],
    );
    assert_eq!(applied.rekeyed.len(), 1);
    assert!(applied.resync);
    assert_eq!(applied.chats, [PN]);
    assert_eq!(thread_ids(&store, PN), ["L2", "P1", "L1"]);
    let chat = chat_row(&store, PN).unwrap();
    assert_eq!((chat.unread, chat.wire_jid.as_deref()), (3, Some(LID)));
    let row = alias(&store, LID);
    assert_eq!((row.source, row.learned_ms), (Source::PeerMessage, 3_000));
    assert_eq!(
        store
            .message(PN, "L2")
            .unwrap()
            .unwrap()
            .wire_sender
            .as_deref(),
        Some(LID)
    );
}

#[test]
fn the_other_form_of_a_group_author_is_learned() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(by(GROUP, LID, "G1", 1_000, "before the pair")),
            message(WaMessage {
                sender_alt: Some(PN.into()),
                ..by(GROUP, LID, "G2", 2_000, "names both")
            }),
        ],
    );
    assert!(one_person(&store, LID, PN));
    assert_eq!(alias(&store, PN).source, Source::PeerMessage);
    for id in ["G1", "G2"] {
        let row = store.message(GROUP, id).unwrap().unwrap();
        assert_eq!(row.sender.as_deref(), Some(PN), "{id}");
        assert_eq!(row.wire_sender.as_deref(), Some(LID), "{id}");
    }
    // The group is a chat of its own, whatever its authors are.
    assert_eq!(thread_ids(&store, GROUP), ["G2", "G1"]);
    assert!(chat_row(&store, PN).is_none());
}

#[test]
fn the_other_form_of_a_reactor_is_learned_and_not_stored() {
    let mut store = store();
    let with_alt = |sender: &str, alt: &str, emoji: &str| Reaction {
        sender_alt: Some(alt.into()),
        ..reaction(sender, emoji)
    };
    apply(
        &mut store,
        vec![
            message(WaMessage {
                reactions: vec![with_alt(LID, PN, "a")],
                ..by(GROUP, PN2, "G1", 1_000, "hi")
            }),
            GatewayEvent::MessageUpdate {
                chat: GROUP.into(),
                id: "G1".into(),
                reactions: vec![with_alt(PN, LID, "a"), with_alt(PN2, LID2, "b")],
                read: false,
            },
        ],
    );
    assert!(one_person(&store, LID, PN) && one_person(&store, LID2, PN2));
    assert_eq!(alias(&store, LID2).source, Source::PeerMessage);
    let row = store.message(GROUP, "G1").unwrap().unwrap();
    assert_eq!(row.reactions, [reaction(PN, "a"), reaction(PN2, "b")]);
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM messages WHERE instr(reactions, 'sender_alt') > 0"
        ),
        0
    );
}

#[test]
fn a_listed_chat_with_its_other_form_is_one_chat() {
    let mut store = two_chats();
    let mut entry = listed(LID, Some(text(LID, "L9", 9_000, "newest")), 4);
    entry.chat.alt = Some(PN.into());
    let stored = store.store_chats(&[entry], 10_000, None, None).unwrap();
    assert_eq!(stored.rekeyed.len(), 1);
    assert_eq!(stored.rekeyed[0].into_key, PN);
    assert!(chat_row(&store, LID).is_none());
    let chat = chat_row(&store, PN).unwrap();
    assert_eq!((chat.unread, chat.wire_jid.as_deref()), (4, Some(LID)));
    assert_eq!(thread_ids(&store, PN), ["L9", "P1", "L1"]);
    // It is the gateway's own resolution: the weakest source.
    let row = alias(&store, LID);
    assert_eq!((row.source, row.learned_ms), (Source::Other, 10_000));
    // The list was just stored: nothing asks for it again.
    assert!(!store.plan(None).unwrap().refresh_chats);
    assert_tables_match_map(&store);
}

#[test]
fn two_listed_entries_of_one_person_are_joined_whichever_names_the_pair() {
    let mut store = store();
    let mut second = listed(PN, Some(text(PN, "P1", 2_000, "number")), 1);
    second.chat.alt = Some(LID.into());
    let first = listed(LID, Some(text(LID, "L1", 1_000, "lid")), 2);
    let stored = store
        .store_chats(&[first, second], 5_000, None, None)
        .unwrap();
    assert!(stored.rekeyed.is_empty() && stored.rejected.is_empty());
    let chats = store.chats(true).unwrap();
    assert_eq!(chats.len(), 1);
    assert_eq!((chats[0].jid.as_str(), chats[0].unread), (PN, 3));
    assert_eq!(thread_ids(&store, PN), ["P1"]);
}

#[test]
fn typing_presence_a_call_and_a_profile_teach_the_pair_they_name() {
    let cases: Vec<(GatewayEvent, Source)> = vec![
        (
            GatewayEvent::Typing {
                chat: GROUP.into(),
                jid: LID.into(),
                state: TypingState::Typing,
                jid_alt: Some(PN.into()),
            },
            Source::Other,
        ),
        (
            GatewayEvent::Presence {
                jid: PN.into(),
                available: true,
                last_seen_ms: None,
                jid_alt: Some(LID.into()),
            },
            Source::Other,
        ),
        (
            GatewayEvent::Call(CallUpdate {
                call_id: "k".into(),
                peer: PN.into(),
                peer_name: None,
                direction: CallDirection::In,
                phase: CallPhase::Ringing,
                is_video: false,
                peer_alt: Some(LID.into()),
            }),
            Source::Call,
        ),
        (
            GatewayEvent::Profile(WaProfile {
                jid: PN.into(),
                lid: Some(LID.into()),
                ..WaProfile::default()
            }),
            Source::Usync,
        ),
    ];
    for (event, source) in cases {
        let mut store = two_chats();
        let applied = apply(&mut store, vec![event.clone()]);
        assert_eq!(applied.rekeyed.len(), 1, "{event:?}");
        assert!(one_person(&store, LID, PN), "{event:?}");
        let row = alias(&store, LID);
        assert_eq!(row.source, source, "{event:?}");
        // The time is the event's.
        assert_eq!(row.learned_ms, 1_000_003, "{event:?}");
        assert_eq!(thread_ids(&store, PN), ["P1", "L1"], "{event:?}");
    }
}

#[test]
fn groups_profiles_and_the_instance_are_observed() {
    let mut store = two_chats();
    let member = |jid: &str, lid: Option<&str>, pn: Option<&str>| Participant {
        jid: jid.into(),
        admin: false,
        name: None,
        lid: lid.map(str::to_owned),
        pn: pn.map(str::to_owned),
    };
    let group = GroupInfo {
        jid: GROUP.into(),
        subject: "Team".into(),
        participants: vec![
            // A LID-addressed member whose number the gateway knows.
            member(LID, Some(LID), Some(PN)),
            // Hides the number.
            member(LID3, Some(LID3), None),
        ],
        description: None,
        announce: false,
        addressing_mode: Some("lid".into()),
    };
    let profile = WaProfile {
        jid: PN2.into(),
        lid: Some(LID2.into()),
        ..WaProfile::default()
    };
    let status = InstanceStatus {
        instance_id: "inst1".into(),
        state: InstanceState::Connected,
        own_jid: Some("905550000009:3@s.whatsapp.net".into()),
        push_name: None,
        own_lid: Some(OWN_LID.into()),
    };
    let applied = store
        .identity_observe(
            &Observed {
                status: Some(&status),
                groups: std::slice::from_ref(&group),
                profiles: std::slice::from_ref(&profile),
            },
            9_000,
        )
        .unwrap();
    assert_eq!(applied.rekeyed.len(), 1);
    assert!(applied.resync && !applied.account_changed && applied.rejected.is_empty());
    assert_eq!(thread_ids(&store, PN), ["P1", "L1"]);
    assert_eq!(alias(&store, LID).source, Source::GroupMetadata);
    assert_eq!(alias(&store, LID2).source, Source::Usync);
    assert!(one_person(&store, LID2, PN2));
    assert!(store.identity().is_me(OWN_LID) && store.identity().is_me(OWN_PN));
    // The member without a number taught nothing.
    assert!(store.identity().lookup(LID3).unwrap().person_id.is_none());
    // Nothing to observe changes nothing.
    let before = dump(&store.conn);
    let quiet = store.identity_observe(&Observed::default(), 9_500).unwrap();
    assert_eq!(quiet, IdentityApplied::default());
    assert_eq!(dump(&store.conn), before);
    assert_tables_match_map(&store);
}

#[test]
fn a_page_asked_under_a_lid_whose_messages_name_the_number_is_stored() {
    let mut store = two_chats();
    let rich = |message: WaMessage| RichMessage {
        message,
        meta: Default::default(),
    };
    let messages = [
        rich(WaMessage {
            chat_alt: Some(PN.into()),
            ..text(LID, "L3", 3_000, "newer")
        }),
        rich(text(LID, "L1", 1_000, "under the lid")),
    ];
    let stored = store.store_page(LID, None, 50, &messages, 9_000).unwrap();
    assert_eq!((stored.stored, stored.new), (2, 1));
    assert_eq!(stored.rekeyed.len(), 1);
    assert_eq!(thread_ids(&store, PN), ["L3", "P1", "L1"]);
    // The merge moved a chat: the list is owed again.
    assert!(store.plan(None).unwrap().refresh_chats);
}

// ---- which of the two is the LID

#[test]
fn either_order_of_the_two_forms_is_one_pair() {
    for (chat, alt) in [(LID, PN), (PN, LID)] {
        let mut store = store();
        apply(
            &mut store,
            vec![message(WaMessage {
                chat_alt: Some(alt.into()),
                ..text(chat, "M1", 1_000, "hi")
            })],
        );
        assert!(one_person(&store, LID, PN), "{chat}");
        assert_eq!(thread_ids(&store, PN), ["M1"], "{chat}");
        assert_eq!(alias(&store, LID).kind, AliasKind::Lid);
        assert_eq!(alias(&store, PN).kind, AliasKind::Pn);
    }
}

#[test]
fn two_ids_that_are_not_a_lid_and_a_number_teach_nothing() {
    let mut store = store();
    let applied = apply(
        &mut store,
        vec![
            // Two numbers.
            message(WaMessage {
                chat_alt: Some(PN2.into()),
                ..text(PN, "P1", 1_000, "hi")
            }),
            // Two LIDs.
            message(WaMessage {
                sender_alt: Some(LID2.into()),
                ..by(GROUP, LID, "G1", 2_000, "hi")
            }),
            // A group next to a number, text that is no id, the same id twice.
            message(WaMessage {
                chat_alt: Some(PN.into()),
                sender_alt: Some("not an id".into()),
                ..by(GROUP, LID3, "G2", 3_000, "hi")
            }),
            GatewayEvent::Typing {
                chat: PN.into(),
                jid: PN.into(),
                state: TypingState::Paused,
                jid_alt: Some(PN.into()),
            },
            // A LID with the digits of the number is another id.
            message(text("905550000001@lid", "X1", 4_000, "same digits")),
        ],
    );
    assert!(applied.rekeyed.is_empty() && applied.rejected.is_empty());
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM merge_log"), 0);
    for (a, b) in [(PN, PN2), (LID, LID2), (LID3, PN), (LID, PN)] {
        assert!(!one_person(&store, a, b), "{a} {b}");
    }
    assert!(!one_person(&store, "905550000001@lid", PN));
    assert_eq!(thread_ids(&store, "905550000001@lid"), ["X1"]);
}

// ---- learning twice

#[test]
fn a_pair_that_is_known_is_not_written_again() {
    let mut store = store();
    let named = |id: &str, ts: i64| {
        message(WaMessage {
            sender_alt: Some(PN.into()),
            ..by(GROUP, LID, id, ts, "hi")
        })
    };
    apply(&mut store, vec![named("G1", 1_000)]);
    let before = identity_rows(&store);
    // Later messages name the same pair: no person, alias or merge row is touched, not even its time.
    apply(&mut store, vec![named("G2", 2_000), named("G3", 3_000)]);
    apply(
        &mut store,
        vec![GatewayEvent::Typing {
            chat: GROUP.into(),
            jid: LID.into(),
            state: TypingState::Typing,
            jid_alt: Some(PN.into()),
        }],
    );
    assert_eq!(identity_rows(&store), before);
    assert_eq!(alias(&store, LID).learned_ms, 1_000);

    // The gateway's list repeats what is known: not one row of the file changes.
    let changes = store.conn.total_changes();
    let known = page(
        vec![IdentityMapping::new(
            LID,
            PN,
            IdentitySource::PeerMessage,
            9_000,
        )],
        None,
    );
    store.identity_catch_up(&known).unwrap();
    store
        .identity_observe(
            &Observed {
                profiles: &[WaProfile {
                    jid: PN.into(),
                    lid: Some(LID.into()),
                    ..WaProfile::default()
                }],
                ..Observed::default()
            },
            9_500,
        )
        .unwrap();
    assert_eq!(store.conn.total_changes(), changes);
}

#[test]
fn a_stronger_source_is_written_once() {
    let mut store = store();
    let mut entry = listed(LID, None, 0);
    entry.chat.alt = Some(PN.into());
    store
        .store_chats(std::slice::from_ref(&entry), 1_000, None, None)
        .unwrap();
    assert_eq!(alias(&store, LID).source, Source::Other);
    // A message is a stronger witness than the gateway's resolution.
    let named = |id: &str, ts: i64| {
        message(WaMessage {
            chat_alt: Some(LID.into()),
            ..text(PN, id, ts, "hi")
        })
    };
    apply(&mut store, vec![named("P1", 2_000)]);
    let row = alias(&store, LID);
    assert_eq!((row.source, row.learned_ms), (Source::PeerMessage, 2_000));
    let before = identity_rows(&store);
    apply(&mut store, vec![named("P2", 3_000)]);
    // The weaker one again changes nothing either.
    store.store_chats(&[entry], 4_000, None, None).unwrap();
    assert_eq!(identity_rows(&store), before);
    // Both ids were new when the pair came: one person from the start, no key ever changed.
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM merge_log"), 0);
}

// ---- the gateway's list of pairs

#[test]
fn the_list_is_paged_until_the_gateway_has_nothing_more() {
    let mut store = two_chats();
    // Nothing was asked yet: every pair is owed.
    assert_eq!(store.identity_since().unwrap(), None);
    assert_eq!(store.plan(None).unwrap().identity_since, None);

    let first = store
        .identity_catch_up(&page(
            vec![IdentityMapping::new(LID, PN, IdentitySource::Usync, 100)],
            Some("c1"),
        ))
        .unwrap();
    assert_eq!((first.pairs, first.since.as_deref()), (1, Some("c1")));
    assert!(first.more && first.resync);
    assert_eq!(first.rekeyed.len(), 1);
    assert_eq!(thread_ids(&store, PN), ["P1", "L1"]);

    let second = store
        .identity_catch_up(&page(
            vec![
                IdentityMapping::new(LID2, PN2, IdentitySource::GroupMetadata, 200),
                IdentityMapping {
                    lid: LID3.into(),
                    pn: "not a number".into(),
                    source: "from_the_future".into(),
                    learned_ms: 300,
                },
            ],
            Some("c2"),
        ))
        .unwrap();
    assert_eq!((second.pairs, second.since.as_deref()), (2, Some("c2")));
    assert!(second.more && !second.resync && second.rekeyed.is_empty());
    assert_eq!(second.rejected.len(), 1);
    assert_eq!(second.rejected[0].reason, RejectReason::Invalid);
    assert!(one_person(&store, LID2, PN2));
    assert_eq!(alias(&store, LID2).learned_ms, 200);

    // An empty page: the store has every pair and keeps the last cursor for the next catch-up.
    let last = store.identity_catch_up(&page(Vec::new(), None)).unwrap();
    assert_eq!((last.pairs, last.since.as_deref()), (0, Some("c2")));
    assert!(!last.more);
    assert_eq!(
        store.plan(None).unwrap().identity_since.as_deref(),
        Some("c2")
    );
    let json = serde_json::to_value(&last).unwrap();
    assert_eq!(
        json,
        serde_json::json!({"pairs": 0, "since": "c2", "more": false, "resync": false})
    );
    assert_tables_match_map(&store);
}

#[test]
fn a_catch_up_cut_short_goes_on_from_the_stored_cursor() {
    let dir = temp_dir("catch-up-resume");
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        store
            .identity_catch_up(&page(
                vec![IdentityMapping::new(LID, PN, IdentitySource::Usync, 100)],
                Some("c1"),
            ))
            .unwrap();
        // Killed before the next page.
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(store.identity_since().unwrap().as_deref(), Some("c1"));
    assert_eq!(
        store.plan(None).unwrap().identity_since.as_deref(),
        Some("c1")
    );
    assert!(one_person(&store, LID, PN));
    // A `resync` event starts the chat list over, not the list of pairs.
    let applied = apply(&mut store, vec![GatewayEvent::Resync]);
    assert!(applied.resync);
    assert_eq!(store.identity_since().unwrap().as_deref(), Some("c1"));
    let next = store
        .identity_catch_up(&page(
            vec![IdentityMapping::new(
                LID2,
                PN2,
                IdentitySource::Receipt,
                200,
            )],
            Some("c2"),
        ))
        .unwrap();
    assert_eq!(next.since.as_deref(), Some("c2"));
    drop(store);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_gateway_without_the_list_leaves_the_store_as_it_is() {
    // Nothing is handed in for a gateway that does not list pairs: the store works on events and other forms
    // alone, and still owes the whole list should the gateway learn to answer.
    let mut store = two_chats();
    let before = dump(&store.conn);
    assert_eq!(store.plan(None).unwrap().identity_since, None);
    assert_eq!(dump(&store.conn), before);
    // The old gateway's `chat_merged` still teaches the pair.
    let applied = apply(
        &mut store,
        vec![GatewayEvent::ChatMerged {
            from: LID.into(),
            into: PN.into(),
        }],
    );
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(store.identity_since().unwrap(), None);
}

#[test]
fn an_upgraded_store_catches_up_and_joins_the_chats_it_held_twice() {
    for version in 1..=3 {
        let dir = temp_dir(&format!("catch-up-v{version}"));
        old_file(&dir, version);
        let mut store = Store::open(&dir, "inst1").unwrap();
        assert_eq!(store.identity_since().unwrap(), None, "v{version}");
        let caught = store
            .identity_catch_up(&page(
                vec![IdentityMapping::new(
                    LID,
                    PN,
                    IdentitySource::MigrationSync,
                    100,
                )],
                Some("7"),
            ))
            .unwrap();
        assert_eq!(caught.rekeyed.len(), 1, "v{version}");
        assert!(caught.resync, "v{version}");
        let ids: Vec<String> = thread_ids(&store, PN)
            .into_iter()
            .filter(|id| id != "c1")
            .collect();
        assert_eq!(ids, ["P1", "L2", "L1"], "v{version}");
        assert_eq!(
            store.identity_since().unwrap().as_deref(),
            Some("7"),
            "v{version}"
        );
        drop(store);
        let _ = std::fs::remove_dir_all(dir);
    }
}

#[test]
fn another_account_starts_the_list_over() {
    let mut store = store();
    store
        .identity_set_own(Some(OWN_PN), Some(OWN_LID), 1)
        .unwrap();
    store
        .identity_catch_up(&page(
            vec![IdentityMapping::new(LID, PN, IdentitySource::Usync, 100)],
            Some("c1"),
        ))
        .unwrap();
    // The same account again keeps the cursor.
    apply(&mut store, vec![state(Some(OWN_PN), Some(OWN_LID))]);
    assert_eq!(store.identity_since().unwrap().as_deref(), Some("c1"));
    // Another account: its pairs are asked for from the start.
    apply(&mut store, vec![state(Some(PN2), None)]);
    assert_eq!(store.identity_since().unwrap(), None);
    assert!(!one_person(&store, LID, PN));
}

// ---- reads under an id of before a merge

#[test]
fn a_reader_holding_the_old_key_reads_the_merged_chat() {
    let mut store = two_chats();
    store.set_thumbnail(LID, "L1", b"thumb").unwrap();
    store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();
    for held in [LID, "777000000000001:5@lid", PN] {
        let thread = store.thread(held, 50, None).unwrap();
        let ids: Vec<&str> = thread.items.iter().map(|row| row.id.as_str()).collect();
        assert_eq!(ids, ["P1", "L1"], "{held}");
        // The rows name the key.
        assert!(thread.items.iter().all(|row| row.chat == PN), "{held}");
        let row = store.message(held, "L1").unwrap().unwrap();
        assert_eq!((row.chat.as_str(), row.has_thumbnail), (PN, true), "{held}");
        assert_eq!(
            store.cursor(held).unwrap(),
            store.cursor(PN).unwrap(),
            "{held}"
        );
        assert_eq!(store.cursor(held).unwrap().newest_ts, Some(2_000), "{held}");
        let older = store.older(held, Some(20)).unwrap().unwrap();
        assert_eq!(
            (older.chat.as_str(), older.before, older.limit),
            (PN, Some(1_001), 20),
            "{held}"
        );
        assert_eq!(
            store
                .media(held, "L1", None)
                .unwrap()
                .unwrap()
                .thumbnail
                .as_deref(),
            Some(&b"thumb"[..]),
            "{held}"
        );
    }
    // Media that was being fetched under the old key still lands on its message.
    store
        .set_media_file(LID, "L1", "/cache/L1.jpg", 10, 9_500)
        .unwrap();
    store.set_thumbnail(LID, "P1", b"other").unwrap();
    assert_eq!(
        store
            .media(PN, "L1", Some(9_600))
            .unwrap()
            .unwrap()
            .path
            .as_deref(),
        Some("/cache/L1.jpg")
    );
    assert!(store.message(PN, "P1").unwrap().unwrap().has_thumbnail);
}

#[test]
fn a_read_teaches_the_map_nothing() {
    let store = two_chats();
    let before = dump(&store.conn);
    assert!(store.thread(LID3, 50, None).unwrap().items.is_empty());
    assert!(
        store
            .thread("not an id", 50, None)
            .unwrap()
            .items
            .is_empty()
    );
    assert_eq!(store.message(PN2, "nope").unwrap(), None);
    assert_eq!(store.cursor(LID2).unwrap(), ChatCursor::default());
    assert_eq!(store.older(LID3, None).unwrap().unwrap().chat, LID3);
    assert_eq!(store.stored_key("905550000002:7@s.whatsapp.net"), PN2);
    assert_eq!(store.stored_key("not an id"), "not an id");
    assert_eq!(dump(&store.conn), before);
    assert!(store.identity().lookup(LID3).unwrap().person_id.is_none());
}
