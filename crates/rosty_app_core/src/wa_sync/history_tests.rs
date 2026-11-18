//! The history snapshot of round 7a: what a store in memory keeps between unlocks, its bounds, and that seeding it
//! adds no request to the lazy sync.

use rosty_wa_proto::{
    ChatMeta, ChatSummary, EventEnvelope, GatewayEvent, MessageBody, RichChat, RichMessage,
    WaMessage,
};

use super::*;

fn jid(n: usize) -> String {
    format!("1000000000{n:02}@s.whatsapp.net")
}

fn text(chat: &str, id: &str, ts: i64) -> WaMessage {
    WaMessage {
        id: id.into(),
        chat: chat.into(),
        sender: Some(chat.into()),
        from_me: false,
        timestamp_ms: ts,
        body: MessageBody::Text(format!("text {id}")),
        reactions: Vec::new(),
        chat_alt: None,
        sender_alt: None,
    }
}

fn listed(chat: &str, last: WaMessage) -> RichChat {
    RichChat {
        chat: ChatSummary {
            jid: chat.into(),
            name: Some(format!("Chat {chat}")),
            last: Some(last),
            unread: 0,
            is_group: false,
            alt: None,
        },
        meta: ChatMeta::default(),
        last_meta: None,
    }
}

/// `chats` chats with `per_chat` messages each, chat `n` newer than chat `n - 1`.
fn filled(chats: usize, per_chat: usize) -> Store {
    let mut store = Store::open_in_memory("inst1").unwrap();
    let mut seq = 0;
    for n in 0..chats {
        let chat = jid(n);
        let mut events = Vec::new();
        for k in 0..per_chat {
            seq += 1;
            events.push(EventEnvelope {
                seq,
                at_ms: 1_000 + seq as i64,
                event: GatewayEvent::Message(text(
                    &chat,
                    &format!("{n}-{k}"),
                    (n * 1_000 + k) as i64,
                )),
            });
        }
        store.apply_events(&events).unwrap();
        let last = text(
            &chat,
            &format!("{n}-{}", per_chat - 1),
            (n * 1_000 + per_chat - 1) as i64,
        );
        store
            .store_chats(&[listed(&chat, last)], 50_000, None, None)
            .unwrap();
    }
    store
}

fn seeded(snapshot: &HistorySnapshot) -> Store {
    let mut store = Store::open_in_memory("inst1").unwrap();
    store.set_lazy(true);
    assert!(store.seed_history(snapshot).unwrap().is_some());
    store
}

#[test]
fn a_seeded_store_shows_the_same_inbox_and_threads() {
    let store = filled(3, 5);
    let snapshot = store.history_snapshot().unwrap();
    let copy = seeded(&snapshot);
    assert_eq!(copy.chats(true).unwrap(), store.chats(true).unwrap());
    for n in 0..3 {
        let chat = jid(n);
        assert_eq!(
            copy.thread(&chat, 50, None).unwrap(),
            store.thread(&chat, 50, None).unwrap()
        );
        assert_eq!(copy.cursor(&chat).unwrap(), store.cursor(&chat).unwrap());
    }
}

#[test]
fn the_snapshot_keeps_the_newest_chats_and_messages_only() {
    let store = filled(HISTORY_CHATS + 5, HISTORY_MESSAGES + 4);
    // The oldest chat is pinned: it is kept first.
    store
        .conn
        .execute("UPDATE chats SET pinned = 1 WHERE jid = ?1", [jid(0)])
        .unwrap();
    let snapshot = store.history_snapshot().unwrap();
    assert_eq!(snapshot.chats.len(), HISTORY_CHATS);
    assert_eq!(snapshot.messages.len(), HISTORY_CHATS * HISTORY_MESSAGES);
    let kept: Vec<String> = snapshot
        .chats
        .iter()
        .map(|c| match c.get("jid") {
            Some(Cell::Text(t)) => t.clone(),
            _ => String::new(),
        })
        .collect();
    assert_eq!(kept[0], jid(0));
    assert_eq!(
        kept[1],
        jid(HISTORY_CHATS + 4),
        "then the newest message first"
    );
    assert!(
        !kept.contains(&jid(1)),
        "the oldest unpinned chats are left out"
    );

    let copy = seeded(&snapshot);
    let thread = copy.thread(&jid(0), 200, None).unwrap();
    assert_eq!(thread.items.len(), HISTORY_MESSAGES);
    assert_eq!(thread.items[0].id, format!("0-{}", HISTORY_MESSAGES + 3));
    // Older history than what is kept is asked for by a scroll, from the oldest kept message.
    let cursor = copy.cursor(&jid(0)).unwrap();
    assert!(!cursor.reached_start);
    assert_eq!(cursor.oldest_ts, Some(4));
}

#[test]
fn thumbnails_are_kept_up_to_their_budget() {
    let store = filled(2, 3);
    let big = HISTORY_THUMB_BYTES / 4;
    store
        .conn
        .execute("UPDATE messages SET thumb = zeroblob(?1)", [big as i64])
        .unwrap();
    let snapshot = store.history_snapshot().unwrap();
    let with_thumb = snapshot
        .messages
        .iter()
        .filter(|m| matches!(m.get("thumb"), Some(Cell::Blob { .. })))
        .count();
    // base64 makes each a third bigger: two fit, the third would not.
    assert_eq!(with_thumb, 2);
    let copy = seeded(&snapshot);
    let newest = copy.thread(&jid(1), 10, None).unwrap();
    assert_eq!(
        newest.items.len(),
        3,
        "a message over the budget stays, without its thumbnail"
    );
}

#[test]
fn hidden_chats_cleared_messages_and_the_outbox_are_not_kept() {
    let store = filled(3, 4);
    store
        .conn
        .execute("UPDATE chats SET hidden = 1 WHERE jid = ?1", [jid(0)])
        .unwrap();
    store
        .conn
        .execute(
            "UPDATE chats SET cleared_ts = 1001 WHERE jid = ?1",
            [jid(1)],
        )
        .unwrap();
    let snapshot = store.history_snapshot().unwrap();
    assert_eq!(snapshot.chats.len(), 2);
    let of = |chat: &str| {
        snapshot
            .messages
            .iter()
            .filter(|m| m.get("chat") == Some(&Cell::Text(chat.to_owned())))
            .count()
    };
    assert_eq!(of(&jid(0)), 0);
    assert_eq!(of(&jid(1)), 2, "messages up to the clear are left out");
    let json = serde_json::to_string(&snapshot).unwrap();
    assert!(!json.contains("outbox"));
}

#[test]
fn seeding_is_refused_for_another_store_instance_or_schema() {
    let store = filled(2, 2);
    let snapshot = store.history_snapshot().unwrap();
    let mut busy = filled(1, 1);
    assert_eq!(
        busy.seed_history(&snapshot).unwrap(),
        None,
        "a store with chats"
    );
    let mut other = Store::open_in_memory("inst2").unwrap();
    assert_eq!(other.seed_history(&snapshot).unwrap(), None);
    let older = HistorySnapshot {
        schema_version: SCHEMA_VERSION - 1,
        ..snapshot.clone()
    };
    let mut fresh = Store::open_in_memory("inst1").unwrap();
    assert_eq!(fresh.seed_history(&older).unwrap(), None);
    assert_eq!(
        fresh.seed_history(&HistorySnapshot::default()).unwrap(),
        None
    );
    assert_eq!(
        fresh.seed_history(&snapshot).unwrap(),
        Some(HistoryCount {
            chats: 2,
            messages: 4
        })
    );
}

#[test]
fn a_seeded_store_asks_the_gateway_for_nothing_more_than_a_fresh_one() {
    let snapshot = filled(3, 5).history_snapshot().unwrap();
    // The gateway moved on in chat 2 (three new messages) and nowhere else.
    let mut fresh_list = Vec::new();
    for n in 0..3 {
        let chat = jid(n);
        let last = if n == 2 {
            text(&chat, "2-new", 9_000)
        } else {
            text(&chat, &format!("{n}-4"), (n * 1_000 + 4) as i64)
        };
        fresh_list.push(listed(&chat, last));
    }
    let mut empty = Store::open_in_memory("inst1").unwrap();
    empty.set_lazy(true);
    let mut copy = seeded(&snapshot);
    for store in [&mut empty, &mut copy] {
        // The cold start: the plan refreshes the chat list; no chat is on screen yet.
        let plan = store.plan(None).unwrap();
        assert!(plan.refresh_chats);
        assert!(plan.fetch.is_empty());
        let stored = store.store_chats(&fresh_list, 60_000, None, None).unwrap();
        assert!(
            stored.fetch.is_empty(),
            "the lazy sync pages nothing in advance"
        );
    }
    // The chat that moved on has a gap from the kept newest message up, and opening it asks one page, as an
    // empty store does.
    assert!(copy.cursor(&jid(2)).unwrap().gap.is_some());
    for store in [&empty, &copy] {
        assert_eq!(
            store.open_page(&jid(2), None).unwrap().map(|p| p.before),
            Some(None)
        );
    }
    let page: Vec<RichMessage> = vec![RichMessage::from(text(&jid(2), "2-new", 9_000))];
    let stored = copy.store_page(&jid(2), None, 50, &page, 61_000).unwrap();
    assert_eq!(stored.next, None, "one page per open");
    assert!(
        copy.cursor(&jid(2)).unwrap().gap.is_none(),
        "the short page closed the gap"
    );
    let thread = copy.thread(&jid(2), 50, None).unwrap();
    assert_eq!(thread.items[0].id, "2-new");
    assert_eq!(
        thread.items.len(),
        6,
        "the new message on top of the kept ones"
    );
}
