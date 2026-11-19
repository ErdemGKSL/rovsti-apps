//! Message actions (E6-WA R4): early edits and deletes, edits after a merge, expiry, delete for me, forwards,
//! mentions, link previews, the edit window and the chat timer.

use rosty_core::wa_identity::Source;
use rosty_wa_proto::{
    ChatMeta, ChatSummary, DELETED_BY_ME, EventEnvelope, GatewayEvent, LinkPreview, LinkPreviewOut,
    MessageBody, MessageMeta, RichChat, RichMessage, SystemNote, WaMessage,
};

use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const BERK: &str = "100000000002@s.whatsapp.net";
const ALI_LID: &str = "777000111@lid";
const GROUP: &str = "120000000000000001@g.us";

fn store() -> Store {
    Store::open_in_memory("inst1").unwrap()
}

fn text(chat: &str, id: &str, ts: i64, body: &str) -> WaMessage {
    WaMessage {
        id: id.into(),
        chat: chat.into(),
        sender: Some(chat.into()),
        from_me: false,
        timestamp_ms: ts,
        body: MessageBody::Text(body.into()),
        reactions: Vec::new(),
        chat_alt: None,
        sender_alt: None,
    }
}

fn mine(chat: &str, id: &str, ts: i64, body: &str) -> WaMessage {
    WaMessage {
        sender: None,
        from_me: true,
        ..text(chat, id, ts, body)
    }
}

fn env(seq: u64, event: GatewayEvent) -> EventEnvelope {
    EventEnvelope {
        seq,
        at_ms: 1_000_000 + seq as i64,
        event,
    }
}

fn edit(chat: &str, id: &str, new: &str, at: i64) -> GatewayEvent {
    GatewayEvent::MessageMeta {
        chat: chat.into(),
        id: id.into(),
        body: Some(MessageBody::Text(new.into())),
        meta: Box::new(MessageMeta {
            edited_ms: Some(at),
            ..Default::default()
        }),
    }
}

fn with_meta(message: WaMessage, meta: MessageMeta) -> RichMessage {
    RichMessage { message, meta }
}

#[test]
fn an_edit_that_comes_before_its_message_is_applied_when_it_arrives() {
    let mut store = store();
    store
        .apply_events(&[env(1, edit(ALI, "A", "edited", 5_000))])
        .unwrap();
    assert!(store.message(ALI, "A").unwrap().is_none());
    store
        .apply_events(&[env(
            2,
            GatewayEvent::Message(text(ALI, "A", 1_000, "first")),
        )])
        .unwrap();
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert_eq!(row.text.as_deref(), Some("edited"));
    assert_eq!(row.edited_ms, Some(5_000));
    assert_eq!(row.edit_history.len(), 1);
}

#[test]
fn a_delete_for_everyone_that_comes_first_waits_too_but_a_tick_does_not() {
    let mut store = store();
    store
        .apply_events(&[
            env(
                1,
                GatewayEvent::MessageMeta {
                    chat: ALI.into(),
                    id: "A".into(),
                    body: None,
                    meta: Box::new(MessageMeta {
                        revoked: true,
                        deleted: true,
                        deleted_by: Some(ALI.into()),
                        ..Default::default()
                    }),
                },
            ),
            env(
                2,
                GatewayEvent::MessageMeta {
                    chat: ALI.into(),
                    id: "B".into(),
                    body: None,
                    meta: Box::new(MessageMeta {
                        starred: true,
                        ..Default::default()
                    }),
                },
            ),
        ])
        .unwrap();
    store
        .apply_events(&[
            env(3, GatewayEvent::Message(text(ALI, "A", 1_000, "oops"))),
            env(4, GatewayEvent::Message(text(ALI, "B", 2_000, "kept"))),
        ])
        .unwrap();
    assert!(store.message(ALI, "A").unwrap().unwrap().deleted);
    assert!(!store.message(ALI, "B").unwrap().unwrap().starred);
}

#[test]
fn an_edit_under_the_lid_lands_on_the_merged_chat() {
    let mut store = store();
    store
        .apply_events(&[env(
            1,
            GatewayEvent::Message(text(ALI_LID, "L1", 1_000, "written under the lid")),
        )])
        .unwrap();
    store
        .identity_learn(ALI_LID, ALI, Source::GroupMetadata, 2_000)
        .unwrap();
    // An older gateway still names the chat by its LID.
    store
        .apply_events(&[env(2, edit(ALI_LID, "L1", "fixed", 3_000))])
        .unwrap();
    let row = store.message(ALI, "L1").unwrap().unwrap();
    assert_eq!(row.chat, ALI);
    assert_eq!(row.text.as_deref(), Some("fixed"));
}

#[test]
fn disappearing_messages_expire_with_their_media_and_never_come_back() {
    let mut store = store();
    let timed = MessageMeta {
        expires_s: Some(60),
        ..Default::default()
    };
    store
        .store_page(
            ALI,
            None,
            50,
            &[
                with_meta(text(ALI, "T", 1_000, "gone soon"), timed.clone()),
                with_meta(text(ALI, "K", 2_000, "kept"), MessageMeta::default()),
            ],
            0,
        )
        .unwrap();
    store
        .conn
        .execute(
            "INSERT INTO media (chat, id, kind, state, path, bytes, used_ms)
             VALUES (?1, 'T', 'image', 'ready', '/m/T.jpg', 10, 1)",
            [ALI],
        )
        .unwrap();
    assert_eq!(
        store.message(ALI, "T").unwrap().unwrap().expires_at,
        Some(61_000)
    );
    let early = store.expire(30_000).unwrap();
    assert_eq!(early.removed, 0);
    assert_eq!(early.next_ms, Some(61_000));
    // The app was closed past the expiry: the next run at open removes it.
    let due = store.expire(90_000).unwrap();
    assert_eq!(due.removed, 1);
    assert_eq!(due.files, vec!["/m/T.jpg".to_owned()]);
    assert_eq!(due.chats, vec![ALI.to_owned()]);
    assert_eq!(due.next_ms, None);
    assert!(store.message(ALI, "T").unwrap().is_none());
    assert!(store.message(ALI, "K").unwrap().is_some());
    // A gateway page that still has it does not bring it back.
    store
        .store_page(
            ALI,
            None,
            50,
            &[with_meta(text(ALI, "T", 1_000, "gone soon"), timed)],
            90_000,
        )
        .unwrap();
    assert!(store.message(ALI, "T").unwrap().is_none());
}

#[test]
fn delete_for_me_hides_at_once_and_a_refusal_shows_it_again() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A", 1_000, "a"))),
            env(2, GatewayEvent::Message(text(ALI, "B", 2_000, "b"))),
        ])
        .unwrap();
    let thread = |store: &Store| -> Vec<String> {
        store
            .thread(ALI, 50, None)
            .unwrap()
            .items
            .into_iter()
            .map(|row| row.id)
            .collect()
    };
    store
        .outbox_enqueue(
            "d1",
            ALI,
            &Action::DeleteForMe {
                message_id: "B".into(),
            },
            3_000,
        )
        .unwrap();
    assert_eq!(thread(&store), ["A"]);
    let due = store.outbox_due(3_000, false).unwrap();
    assert_eq!(due.items.len(), 1);
    store
        .outbox_settle(
            "d1",
            &Outcome::Error {
                error: ErrorKind::Rejected,
                message: "no".into(),
                retry_after_ms: None,
            },
            3_100,
        )
        .unwrap();
    assert_eq!(thread(&store), ["B", "A"]);
    assert!(store.outbox().unwrap().is_empty());
    // Deleted for me on another device: hidden too.
    store
        .apply_events(&[env(
            3,
            GatewayEvent::MessageDeleted {
                chat: ALI.into(),
                id: "A".into(),
                deleted_at: Some(4_000),
                deleted_by: Some(DELETED_BY_ME.into()),
            },
        )])
        .unwrap();
    assert_eq!(thread(&store), ["B"]);
}

#[test]
fn a_forward_shows_its_copy_at_once_and_is_stored_forwarded() {
    let mut store = store();
    store
        .apply_events(&[env(
            1,
            GatewayEvent::Message(text(ALI, "A", 1_000, "pass it on")),
        )])
        .unwrap();
    let forward = Action::Forward {
        source_chat: ALI.into(),
        message_id: "A".into(),
        snapshot: None,
    };
    let queued = store.outbox_enqueue("f1", BERK, &forward, 2_000).unwrap();
    let Action::Forward {
        snapshot: Some(snapshot),
        ..
    } = &queued.action
    else {
        panic!("the snapshot is filled: {:?}", queued.action);
    };
    assert_eq!(snapshot.text.as_deref(), Some("pass it on"));
    let page = store.thread(BERK, 50, None).unwrap();
    assert_eq!(page.items.len(), 1);
    assert!(page.items[0].forwarded);
    assert_eq!(page.items[0].text.as_deref(), Some("pass it on"));
    store.outbox_due(2_000, false).unwrap();
    store
        .outbox_settle(
            "f1",
            &Outcome::Sent {
                message_id: Some("F1".into()),
                timestamp_ms: Some(2_100),
            },
            2_100,
        )
        .unwrap();
    let row = store.message(BERK, "F1").unwrap().unwrap();
    assert!(row.forwarded && row.from_me);
    assert_eq!(row.text.as_deref(), Some("pass it on"));
    // A deleted message is refused.
    store
        .apply_events(&[env(
            2,
            GatewayEvent::MessageMeta {
                chat: ALI.into(),
                id: "A".into(),
                body: None,
                meta: Box::new(MessageMeta {
                    revoked: true,
                    deleted: true,
                    deleted_by: Some(ALI.into()),
                    ..Default::default()
                }),
            },
        )])
        .unwrap();
    assert!(store.outbox_enqueue("f2", BERK, &forward, 3_000).is_err());
}

#[test]
fn many_forwards_and_received_link_cards_reach_the_row() {
    let mut store = store();
    let meta = MessageMeta {
        forwarded: true,
        forwarding_score: Some(127),
        link_preview: Some(LinkPreview {
            url: "https://example.com".into(),
            title: Some("Example".into()),
            description: None,
            thumbnail: true,
        }),
        ..Default::default()
    };
    store
        .store_page(
            ALI,
            None,
            50,
            &[with_meta(
                text(ALI, "A", 1_000, "https://example.com"),
                meta,
            )],
            0,
        )
        .unwrap();
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert!(row.forwarded && row.forwarded_many);
    assert_eq!(row.link_preview.unwrap().title.as_deref(), Some("Example"));
}

#[test]
fn mention_spans_follow_every_id_of_the_person() {
    let mut store = store();
    // The text names the LID; the gateway resolved the mention to the number.
    let meta = MessageMeta {
        mentions: vec![ALI.into()],
        ..Default::default()
    };
    store
        .identity_learn(ALI_LID, ALI, Source::GroupMetadata, 1)
        .unwrap();
    store
        .store_page(
            GROUP,
            None,
            50,
            &[with_meta(
                text(GROUP, "G", 1_000, "hey @777000111 and @100000000001"),
                meta,
            )],
            0,
        )
        .unwrap();
    let row = store.message(GROUP, "G").unwrap().unwrap();
    let spans: Vec<(usize, usize, &str)> = row
        .mention_spans
        .iter()
        .map(|s| (s.start, s.end, s.jid.as_str()))
        .collect();
    assert_eq!(spans, vec![(4, 14, ALI), (19, 32, ALI)]);
    let page = store.thread(GROUP, 50, None).unwrap();
    assert_eq!(page.items[0].mention_spans.len(), 2);
}

#[test]
fn my_sent_texts_can_be_edited_for_fifteen_minutes() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(mine(ALI, "M", 1_000, "mine"))),
            env(2, GatewayEvent::Message(text(ALI, "T", 2_000, "theirs"))),
        ])
        .unwrap();
    assert_eq!(
        store.message(ALI, "M").unwrap().unwrap().editable_until_ms,
        Some(1_000 + rosty_core::wa_actions::EDIT_WINDOW_MS)
    );
    assert_eq!(
        store.message(ALI, "T").unwrap().unwrap().editable_until_ms,
        None
    );
}

#[test]
fn a_text_with_mentions_and_a_link_card_is_stored_as_sent() {
    let mut store = store();
    let thumb = vec![0xFF, 0xD8, 0xFF, 0xE0, 9];
    let action = Action::SendText {
        text: "@100000000002 https://example.com".into(),
        quoted_id: None,
        mentions: vec![BERK.into()],
        link_preview: Some(LinkPreviewOut {
            url: "https://example.com".into(),
            title: Some("Example".into()),
            description: Some("A page".into()),
            thumbnail_b64: Some(rosty_wa_proto::encode_b64(&thumb)),
        }),
    };
    store.outbox_enqueue("s1", GROUP, &action, 1_000).unwrap();
    let pending = store.thread(GROUP, 50, None).unwrap();
    assert_eq!(pending.items[0].mentions, vec![BERK.to_owned()]);
    assert!(pending.items[0].link_preview.is_some());
    store.outbox_due(1_000, false).unwrap();
    store
        .outbox_settle(
            "s1",
            &Outcome::Sent {
                message_id: Some("S1".into()),
                timestamp_ms: Some(1_100),
            },
            1_100,
        )
        .unwrap();
    let row = store.message(GROUP, "S1").unwrap().unwrap();
    assert_eq!(row.mentions, vec![BERK.to_owned()]);
    assert_eq!(row.mention_spans.len(), 1);
    assert_eq!(
        row.link_preview.unwrap().description.as_deref(),
        Some("A page")
    );
    assert!(row.has_thumbnail);
}

#[test]
fn the_chat_timer_and_its_line_reach_the_rows() {
    let mut store = store();
    let listed = RichChat {
        chat: ChatSummary {
            jid: ALI.into(),
            name: None,
            last: None,
            unread: 0,
            is_group: false,
            alt: None,
        },
        meta: ChatMeta {
            ephemeral_s: Some(604_800),
            ..Default::default()
        },
        last_meta: None,
    };
    store.store_chats(&[listed], 0, None, None).unwrap();
    let chats = store.chats(true).unwrap();
    assert_eq!(chats[0].ephemeral_s, Some(604_800));
    let line = MessageMeta {
        system: Some(SystemNote::Disappearing {
            seconds: 604_800,
            by: Some(DELETED_BY_ME.into()),
        }),
        ..Default::default()
    };
    store
        .store_page(
            ALI,
            None,
            50,
            &[with_meta(
                WaMessage {
                    body: MessageBody::Unsupported,
                    ..mine(ALI, "LINE", 1_000, "")
                },
                line,
            )],
            0,
        )
        .unwrap();
    let row = store.message(ALI, "LINE").unwrap().unwrap();
    assert!(matches!(
        row.system,
        Some(SystemNote::Disappearing {
            seconds: 604_800,
            ..
        })
    ));
    assert_eq!(row.editable_until_ms, None);
}
