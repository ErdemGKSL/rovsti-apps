//! The lazy sync of a store in memory (`plans/wa-vault.md` 14) and the identity snapshot of the vault cache
//! (13.5): what the store asks the gateway for compared with the eager sync of a file store.

use rosty_core::wa_identity::Source;
use rosty_wa_proto::{
    ChatMeta, ChatSummary, EventEnvelope, GatewayEvent, IdentityMapping, IdentityMappings,
    IdentitySource, MessageBody, RichChat, RichMessage, WaMessage,
};

use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const BERK: &str = "100000000002@s.whatsapp.net";
const CAN: &str = "100000000003@s.whatsapp.net";
const ALI_LID: &str = "777000000000001@lid";

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

fn env(seq: u64, event: GatewayEvent) -> EventEnvelope {
    EventEnvelope {
        seq,
        at_ms: 1_000_000 + seq as i64,
        event,
    }
}

fn listed(jid: &str, last: WaMessage) -> RichChat {
    RichChat {
        chat: ChatSummary {
            jid: jid.into(),
            name: None,
            last: Some(last),
            unread: 0,
            is_group: false,
            alt: None,
        },
        meta: ChatMeta::default(),
        last_meta: None,
    }
}

/// The gateway's newest-first page of `all` before `before`.
fn page(all: &[WaMessage], before: Option<i64>, limit: u32) -> Vec<RichMessage> {
    let mut page: Vec<_> = all
        .iter()
        .filter(|m| before.is_none_or(|before| m.timestamp_ms < before))
        .cloned()
        .collect();
    page.sort_by_key(|m| std::cmp::Reverse(m.timestamp_ms));
    page.truncate(limit as usize);
    page.into_iter().map(RichMessage::from).collect()
}

/// Three chats with two stored messages each, then a gateway restart while each missed five.
fn missed_everywhere(store: &mut Store) -> (Vec<RichChat>, Vec<Vec<WaMessage>>) {
    let chats = [ALI, BERK, CAN];
    let mut seq = 0;
    for (n, chat) in chats.iter().enumerate() {
        for k in 1..=2 {
            seq += 1;
            let ts = (n as i64 + 1) * 100 + k;
            store
                .apply_events(&[env(
                    seq,
                    GatewayEvent::Message(text(chat, &format!("{n}-{k}"), ts)),
                )])
                .unwrap();
        }
    }
    let first: Vec<RichChat> = chats
        .iter()
        .enumerate()
        .map(|(n, chat)| {
            listed(
                chat,
                text(chat, &format!("{n}-2"), (n as i64 + 1) * 100 + 2),
            )
        })
        .collect();
    store.store_chats(&first, 10_000, Some(2), None).unwrap();
    store
        .apply_events(&[env(1, GatewayEvent::Resync { epoch: None })])
        .unwrap();
    let mut history = Vec::new();
    let mut fresh = Vec::new();
    for (n, chat) in chats.iter().enumerate() {
        let mut all: Vec<WaMessage> = (1..=7)
            .map(|k| text(chat, &format!("{n}-{k}"), (n as i64 + 1) * 100 + k))
            .collect();
        all.sort_by_key(|m| m.timestamp_ms);
        fresh.push(listed(chat, all.last().cloned().unwrap()));
        history.push(all);
    }
    (fresh, history)
}

/// Pages every fetch the store asks for, following `next`, and answers how many pages it took.
fn drain(store: &mut Store, mut owed: Vec<FetchPage>, history: &[Vec<WaMessage>]) -> usize {
    let chats = [ALI, BERK, CAN];
    let mut calls = 0;
    while let Some(fetch) = owed.pop() {
        calls += 1;
        let at = chats.iter().position(|c| *c == fetch.chat).unwrap();
        let answer = page(&history[at], fetch.before, fetch.limit);
        let stored = store
            .store_page(&fetch.chat, fetch.before, fetch.limit, &answer, 20_000)
            .unwrap();
        owed.extend(stored.next);
    }
    calls
}

#[test]
fn the_eager_resync_pages_every_chat_and_the_lazy_one_only_the_chat_on_screen() {
    let mut eager = Store::open_in_memory("inst1").unwrap();
    let (fresh, history) = missed_everywhere(&mut eager);
    let owed = eager
        .store_chats(&fresh, 20_000, Some(2), None)
        .unwrap()
        .fetch;
    assert_eq!(owed.len(), 3, "the eager sync opens a gap in every chat");
    let eager_pages = drain(&mut eager, owed, &history);
    assert!(
        eager_pages >= 6,
        "and pages each down to the stored history: {eager_pages}"
    );

    let mut lazy = Store::open_in_memory("inst1").unwrap();
    lazy.set_lazy(true);
    let (fresh, history) = missed_everywhere(&mut lazy);
    lazy.set_focus(Some(BERK));
    let owed = lazy
        .store_chats(&fresh, 20_000, Some(2), None)
        .unwrap()
        .fetch;
    assert_eq!(
        owed,
        vec![FetchPage {
            chat: BERK.into(),
            before: None,
            limit: 2
        }]
    );
    assert_eq!(drain(&mut lazy, owed, &history), 1, "one page, no next");
    // The other chats keep their gap and owe nothing until they are opened; the chat on screen owes one page
    // per catch-up at most.
    let plan = lazy.plan(Some(2)).unwrap().fetch;
    assert!(plan.len() == 1 && plan[0].chat == BERK, "{plan:?}");
    lazy.set_focus(None);
    assert!(lazy.plan(Some(2)).unwrap().fetch.is_empty());
    let open = lazy.open_page(ALI, Some(2)).unwrap().expect("a gap page");
    assert_eq!((open.chat.as_str(), open.before), (ALI, None));
    assert_eq!(drain(&mut lazy, vec![open], &history), 1);
}

#[test]
fn a_chat_is_paged_once_per_session_and_older_history_one_page_per_scroll() {
    let mut store = Store::open_in_memory("inst1").unwrap();
    store.set_lazy(true);
    let all: Vec<WaMessage> = (1..=9)
        .map(|k| text(ALI, &format!("a{k}"), k * 10))
        .collect();
    // A cold start: the chat list stores only the newest message, and pages nothing.
    let stored = store
        .store_chats(&[listed(ALI, all[8].clone())], 1_000, Some(3), None)
        .unwrap();
    assert!(stored.fetch.is_empty());
    assert!(store.plan(Some(3)).unwrap().fetch.is_empty());

    let first = store
        .open_page(ALI, Some(3))
        .unwrap()
        .expect("the newest page");
    assert_eq!(first.before, None);
    let answer = page(&all, None, 3);
    let stored = store.store_page(ALI, None, 3, &answer, 1_000).unwrap();
    assert_eq!(stored.next, None);
    assert_eq!(
        store.open_page(ALI, Some(3)).unwrap(),
        None,
        "kept in memory"
    );

    let older = store.older(ALI, Some(3)).unwrap().expect("one page more");
    assert_eq!(older.before, Some(71));
    store
        .store_page(ALI, older.before, 3, &page(&all, older.before, 3), 1_000)
        .unwrap();
    assert_eq!(store.open_page(ALI, Some(3)).unwrap(), None);
}

#[test]
fn an_eager_store_owes_nothing_on_open() {
    let mut store = Store::open_in_memory("inst1").unwrap();
    store
        .store_chats(&[listed(ALI, text(ALI, "a1", 10))], 1_000, None, None)
        .unwrap();
    assert!(!store.is_lazy());
    assert_eq!(store.open_page(ALI, None).unwrap(), None);
}

#[test]
fn the_identity_snapshot_seeds_a_fresh_store_and_keeps_its_cursor() {
    let mut first = Store::open_in_memory("inst1").unwrap();
    first
        .identity_catch_up(&IdentityMappings {
            items: vec![IdentityMapping::new(
                ALI_LID,
                ALI,
                IdentitySource::PeerMessage,
                5,
            )],
            next: Some("c1".into()),
        })
        .unwrap();
    let snapshot = first.identity_snapshot().unwrap();
    assert_eq!(snapshot.since.as_deref(), Some("c1"));
    assert_eq!(snapshot.aliases.len(), 2);
    let json = serde_json::to_value(&snapshot).unwrap();
    let back: IdentitySnapshot = serde_json::from_value(json).unwrap();
    assert_eq!(back, snapshot);

    let mut next = Store::open_in_memory("inst1").unwrap();
    assert!(next.seed_identity(&back).unwrap());
    assert_eq!(next.identity_since().unwrap().as_deref(), Some("c1"));
    // A message under the LID lands in the number's chat without asking the gateway again.
    assert_eq!(next.stored_key(ALI_LID), ALI);
    assert_eq!(next.identity_snapshot().unwrap(), snapshot);
    // A store that already knows someone keeps what it learned.
    assert!(!next.seed_identity(&back).unwrap());
    let mut known = Store::open_in_memory("inst1").unwrap();
    known
        .identity_learn(
            "777000000000002@lid",
            BERK,
            Source::from_wire("peer_message"),
            1,
        )
        .unwrap();
    assert!(!known.seed_identity(&back).unwrap());
    assert!(
        !Store::open_in_memory("inst1")
            .unwrap()
            .seed_identity(&IdentitySnapshot::default())
            .unwrap()
    );
}
