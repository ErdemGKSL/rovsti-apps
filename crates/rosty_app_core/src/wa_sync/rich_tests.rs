//! Rich content (E6-WA R5): polls, their votes and my choice, contact cards, places sent from the outbox, and messages
//! kept in a disappearing chat.

use rosty_wa_proto::{
    EventEnvelope, GatewayEvent, Location, MessageBody, MessageMeta, Poll, PollOption, RichContent,
    RichMessage, SharedContact, WaMessage,
};

use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const GROUP: &str = "120000000000000001@g.us";

fn store() -> Store {
    Store::open_in_memory("inst1").unwrap()
}

fn env(seq: u64, event: GatewayEvent) -> EventEnvelope {
    EventEnvelope {
        seq,
        at_ms: 1_000_000 + seq as i64,
        event,
    }
}

fn unsupported(chat: &str, id: &str, ts: i64, from_me: bool) -> WaMessage {
    WaMessage {
        id: id.into(),
        chat: chat.into(),
        sender: (!from_me).then(|| ALI.to_owned()),
        from_me,
        timestamp_ms: ts,
        body: MessageBody::Unsupported,
        reactions: Vec::new(),
        chat_alt: None,
        sender_alt: None,
    }
}

fn poll(voters_a: &[&str], voters_b: &[&str], mine: &[&str]) -> Poll {
    let option = |name: &str, voters: &[&str]| PollOption {
        name: name.into(),
        voters: voters.iter().map(|v| (*v).to_owned()).collect(),
    };
    Poll {
        question: "Lunch?".into(),
        options: vec![option("A", voters_a), option("B", voters_b)],
        selectable: 1,
        my_votes: mine.iter().map(|v| (*v).to_owned()).collect(),
    }
}

fn meta_event(chat: &str, id: &str, meta: MessageMeta) -> GatewayEvent {
    GatewayEvent::MessageMeta {
        chat: chat.into(),
        id: id.into(),
        body: None,
        meta: Box::new(meta),
    }
}

fn poll_meta(poll: Poll) -> MessageMeta {
    MessageMeta {
        poll: Some(poll),
        ..Default::default()
    }
}

#[test]
fn a_received_poll_is_a_poll_row_and_its_count_follows_the_gateway() {
    let mut store = store();
    store
        .apply_events(&[
            env(
                1,
                GatewayEvent::Message(unsupported(GROUP, "P", 1_000, false)),
            ),
            env(2, meta_event(GROUP, "P", poll_meta(poll(&[], &[], &[])))),
        ])
        .unwrap();
    let row = store.message(GROUP, "P").unwrap().unwrap();
    assert_eq!(row.kind, Kind::Poll);
    assert_eq!(row.poll.as_ref().unwrap().question, "Lunch?");

    store
        .apply_events(&[env(
            3,
            meta_event(GROUP, "P", poll_meta(poll(&[ALI], &["me"], &["B"]))),
        )])
        .unwrap();
    let counted = store.message(GROUP, "P").unwrap().unwrap().poll.unwrap();
    assert_eq!(counted.options[0].voters, vec![ALI.to_owned()]);
    assert_eq!(counted.my_votes, vec!["B".to_owned()]);
}

#[test]
fn my_vote_shows_at_once_and_a_refused_one_is_undone() {
    let mut store = store();
    store
        .store_page(
            GROUP,
            None,
            50,
            &[RichMessage {
                message: unsupported(GROUP, "P", 1_000, false),
                meta: poll_meta(poll(&[ALI], &[], &[])),
            }],
            2_000,
        )
        .unwrap();
    let vote = |options: &[&str]| Action::PollVote {
        message_id: "P".into(),
        options: options.iter().map(|o| (*o).to_owned()).collect(),
        previous: None,
    };
    let queued = store
        .outbox_enqueue("v1", GROUP, &vote(&["A"]), 3_000)
        .unwrap();
    assert!(matches!(&queued.action, Action::PollVote { previous: Some(p), .. } if p.is_empty()));
    let shown = store.message(GROUP, "P").unwrap().unwrap().poll.unwrap();
    assert_eq!(shown.my_votes, vec!["A".to_owned()]);
    assert_eq!(
        shown.options[0].voters,
        vec![ALI.to_owned(), "me".to_owned()]
    );

    store.outbox_due(3_000, false).unwrap();
    store
        .outbox_settle(
            "v1",
            &Outcome::Error {
                error: ErrorKind::Rejected,
                message: "no".into(),
                retry_after_ms: None,
            },
            3_100,
        )
        .unwrap();
    let back = store.message(GROUP, "P").unwrap().unwrap().poll.unwrap();
    assert!(back.my_votes.is_empty());
    assert_eq!(back.options[0].voters, vec![ALI.to_owned()]);

    // Two answers in a single-choice poll, and a message that is no poll, are refused at once.
    assert!(
        store
            .outbox_enqueue("v2", GROUP, &vote(&["A", "B"]), 4_000)
            .is_err()
    );
    assert!(
        store
            .outbox_enqueue("v3", GROUP, &vote(&["A"]), 4_000)
            .is_ok()
    );
    store
        .apply_events(&[env(
            1,
            GatewayEvent::Message(unsupported(ALI, "X", 1_000, false)),
        )])
        .unwrap();
    let not_a_poll = Action::PollVote {
        message_id: "X".into(),
        options: vec![],
        previous: None,
    };
    assert!(store.outbox_enqueue("v4", ALI, &not_a_poll, 4_000).is_err());
}

#[test]
fn a_rich_send_shows_at_once_and_is_stored_as_sent() {
    let mut store = store();
    let place = Action::SendRich {
        content: RichContent::Location {
            latitude: 41.0,
            longitude: 29.0,
            name: None,
            address: None,
        },
        quoted_id: None,
    };
    store.outbox_enqueue("r1", ALI, &place, 2_000).unwrap();
    let page = store.thread(ALI, 50, None).unwrap();
    assert_eq!(page.items[0].kind, Kind::Location);
    assert_eq!(page.items[0].location.as_ref().unwrap().latitude, 41.0);

    let cards = Action::SendRich {
        content: RichContent::Contacts {
            contacts: vec![
                SharedContact {
                    display_name: "Ada".into(),
                    vcard: "BEGIN:VCARD\nFN:Ada\nEND:VCARD".into(),
                },
                SharedContact {
                    display_name: "Ben".into(),
                    vcard: "BEGIN:VCARD\nFN:Ben\nEND:VCARD".into(),
                },
            ],
        },
        quoted_id: Some("Q".into()),
    };
    store.outbox_enqueue("r2", ALI, &cards, 2_001).unwrap();
    let poll_send = Action::SendRich {
        content: RichContent::Poll {
            question: " Lunch? ".into(),
            options: vec!["A".into(), "B".into()],
            multiple: false,
        },
        quoted_id: None,
    };
    store.outbox_enqueue("r3", ALI, &poll_send, 2_002).unwrap();
    // An invalid poll never queues.
    let bad = Action::SendRich {
        content: RichContent::Poll {
            question: "Q".into(),
            options: vec!["A".into()],
            multiple: false,
        },
        quoted_id: None,
    };
    assert!(store.outbox_enqueue("r4", ALI, &bad, 2_003).is_err());

    for (client, id) in [("r1", "M1"), ("r2", "M2"), ("r3", "M3")] {
        store.outbox_due(3_000, false).unwrap();
        store
            .outbox_settle(
                client,
                &Outcome::Sent {
                    message_id: Some(id.into()),
                    timestamp_ms: Some(3_000),
                },
                3_000,
            )
            .unwrap();
    }
    let sent = store.message(ALI, "M1").unwrap().unwrap();
    assert_eq!(sent.kind, Kind::Location);
    assert!(sent.from_me);
    let contacts = store.message(ALI, "M2").unwrap().unwrap();
    assert_eq!(contacts.kind, Kind::Contact);
    assert_eq!(contacts.contacts.len(), 2);
    assert_eq!(contacts.quoted.as_ref().map(|q| q.id.as_str()), Some("Q"));
    let sent_poll = store.message(ALI, "M3").unwrap().unwrap();
    assert_eq!(sent_poll.kind, Kind::Poll);
    assert_eq!(
        sent_poll.poll.unwrap().question,
        "Lunch?",
        "trimmed when queued"
    );
}

#[test]
fn a_kept_message_does_not_expire_until_the_keep_is_undone() {
    let mut store = store();
    let timed = |kept| MessageMeta {
        expires_s: Some(60),
        kept,
        location: Some(Location {
            latitude: 1.0,
            longitude: 2.0,
            ..Default::default()
        }),
        ..Default::default()
    };
    store
        .apply_events(&[
            env(
                1,
                GatewayEvent::Message(unsupported(ALI, "K", 1_000, false)),
            ),
            env(2, meta_event(ALI, "K", timed(true))),
        ])
        .unwrap();
    let row = store.message(ALI, "K").unwrap().unwrap();
    assert!(row.kept);
    assert_eq!(row.expires_at, None);
    assert_eq!(store.expire(1_000_000).unwrap().removed, 0);

    store
        .apply_events(&[env(3, meta_event(ALI, "K", timed(false)))])
        .unwrap();
    assert_eq!(
        store.message(ALI, "K").unwrap().unwrap().expires_at,
        Some(61_000)
    );
    assert_eq!(store.expire(1_000_000).unwrap().removed, 1);
}
