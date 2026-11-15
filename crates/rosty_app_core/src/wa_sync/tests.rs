use std::path::PathBuf;

use rosty_wa_proto::event::ReceiptKind;
use rosty_wa_proto::{
    ChatMeta, ChatSummary, DELETED_BY_ME, DeliveryStatus, EditRecord, EventEnvelope, GatewayEvent,
    KeptContent, MediaKind, MediaMeta, MessageBody, MessageMeta, Quoted, Reaction, RichChat,
    RichMessage, Since, WaMessage,
};
use rusqlite::Connection;

use super::plan::{self, Step};
use super::*;

const ALI: &str = "100000000001@s.whatsapp.net";
const BERK: &str = "100000000002@s.whatsapp.net";
const GROUP: &str = "120000000000000001@g.us";

fn store() -> Store {
    Store::open_in_memory("inst1").unwrap()
}

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rosty-wa-sync-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
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

fn rich(message: WaMessage) -> RichMessage {
    RichMessage::from(message)
}

fn chat(jid: &str, last: Option<WaMessage>, unread: u32) -> RichChat {
    RichChat {
        chat: ChatSummary {
            jid: jid.into(),
            name: None,
            last,
            unread,
            is_group: jid.ends_with("@g.us"),
            alt: None,
        },
        meta: ChatMeta::default(),
        last_meta: None,
    }
}

fn ids(page: &ThreadPage) -> Vec<&str> {
    page.items.iter().map(|row| row.id.as_str()).collect()
}

fn send(text: &str) -> Action {
    Action::SendText {
        text: text.into(),
        quoted_id: None,
    }
}

fn sent(id: &str, ts: i64) -> Outcome {
    Outcome::Sent {
        message_id: Some(id.into()),
        timestamp_ms: Some(ts),
    }
}

fn error(kind: ErrorKind) -> Outcome {
    Outcome::Error {
        error: kind,
        message: "no".into(),
        retry_after_ms: None,
    }
}

// ---- ordering

#[test]
fn a_thread_is_newest_first_whatever_order_the_events_came_in() {
    let mut store = store();
    let applied = store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "B", 2_000, "second"))),
            env(2, GatewayEvent::Message(text(ALI, "A", 1_000, "first"))),
            env(
                3,
                GatewayEvent::Message(text(ALI, "D", 3_000, "same ms, later id")),
            ),
            env(4, GatewayEvent::Message(text(ALI, "C", 3_000, "same ms"))),
        ])
        .unwrap();
    assert_eq!((applied.applied, applied.last_seq), (4, Some(4)));
    assert_eq!(applied.chats, [ALI]);
    let page = store.thread(ALI, 10, None).unwrap();
    assert_eq!(ids(&page), ["D", "C", "B", "A"]);
    assert_eq!(page.next, None);
    let cursor = store.cursor(ALI).unwrap();
    assert_eq!(
        (cursor.newest_ts, cursor.oldest_ts),
        (Some(3_000), Some(1_000))
    );
    assert_eq!(cursor.newest_id.as_deref(), Some("D"));
}

#[test]
fn thread_pages_follow_the_cursor_without_gaps_or_repeats() {
    let mut store = store();
    let events: Vec<_> = (1..=7u64)
        .map(|n| {
            // Two messages share every millisecond, so the cursor must break ties by id.
            let ts = 1_000 + (n as i64 / 2);
            env(
                n,
                GatewayEvent::Message(text(ALI, &format!("M{n}"), ts, "x")),
            )
        })
        .collect();
    store.apply_events(&events).unwrap();
    let mut seen = Vec::new();
    let mut before = None;
    loop {
        let page = store.thread(ALI, 3, before.as_ref()).unwrap();
        seen.extend(page.items.iter().map(|row| row.id.clone()));
        match page.next {
            Some(next) => before = Some(next),
            None => break,
        }
    }
    assert_eq!(seen, ["M7", "M6", "M5", "M4", "M3", "M2", "M1"]);
}

#[test]
fn the_chat_list_is_newest_first_and_counts_unread() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A1", 1_000, "hi"))),
            env(2, GatewayEvent::Message(text(BERK, "B1", 2_000, "yo"))),
            env(3, GatewayEvent::Message(text(ALI, "A2", 3_000, "there"))),
            env(4, GatewayEvent::Message(mine(BERK, "B2", 2_500, "reply"))),
        ])
        .unwrap();
    let chats = store.chats(false).unwrap();
    let rows: Vec<_> = chats
        .iter()
        .map(|chat| (chat.jid.as_str(), chat.unread, chat.last_ms))
        .collect();
    // My own reply means I read the chat.
    assert_eq!(rows, [(ALI, 2, 3_000), (BERK, 0, 2_500)]);
    assert_eq!(
        chats[0].last.as_ref().unwrap().text.as_deref(),
        Some("there")
    );
    assert_eq!(chats[1].last.as_ref().unwrap().state, RowState::Sent);
}

#[test]
fn stale_and_repeated_sequences_are_skipped() {
    let mut store = store();
    store
        .apply_events(&[env(5, GatewayEvent::Message(text(ALI, "A", 1_000, "one")))])
        .unwrap();
    let again = store
        .apply_events(&[
            env(4, GatewayEvent::Message(text(ALI, "OLD", 500, "stale"))),
            env(5, GatewayEvent::Message(text(ALI, "A", 1_000, "one"))),
            env(6, GatewayEvent::Message(text(ALI, "B", 2_000, "two"))),
        ])
        .unwrap();
    assert_eq!((again.applied, again.last_seq), (1, Some(6)));
    assert_eq!(ids(&store.thread(ALI, 10, None).unwrap()), ["B", "A"]);
}

// ---- idempotent upsert

#[test]
fn applying_the_same_things_twice_changes_nothing() {
    let mut store = store();
    let page = vec![
        rich(text(ALI, "B", 2_000, "two")),
        rich(text(ALI, "A", 1_000, "one")),
    ];
    let first = store.store_page(ALI, None, 50, &page, 9_000).unwrap();
    assert_eq!((first.stored, first.new), (2, 2));
    let before = store.thread(ALI, 10, None).unwrap();
    let second = store.store_page(ALI, None, 50, &page, 9_500).unwrap();
    assert_eq!((second.stored, second.new), (2, 0));
    assert_eq!(store.thread(ALI, 10, None).unwrap(), before);

    // The same message as a live event (another seq) is the same row and does not count as unread again.
    store
        .apply_events(&[env(
            1,
            GatewayEvent::Message(text(ALI, "C", 3_000, "three")),
        )])
        .unwrap();
    let repeat = store
        .apply_events(&[env(
            2,
            GatewayEvent::Message(text(ALI, "C", 3_000, "three")),
        )])
        .unwrap();
    assert!(repeat.chats.is_empty());
    assert_eq!(store.chats(false).unwrap()[0].unread, 1);
    assert_eq!(ids(&store.thread(ALI, 10, None).unwrap()), ["C", "B", "A"]);
}

#[test]
fn a_bare_message_event_keeps_the_meta_stored_before() {
    let mut store = store();
    let mut with_meta = rich(mine(ALI, "A", 1_000, "hello"));
    with_meta.meta = MessageMeta {
        quoted: Some(Quoted {
            id: "Q".into(),
            sender: None,
            preview: Some("earlier".into()),
        }),
        status: Some(DeliveryStatus::Read),
        starred: true,
        ..Default::default()
    };
    store.store_page(ALI, None, 50, &[with_meta], 0).unwrap();
    store
        .apply_events(&[env(
            1,
            GatewayEvent::Message(mine(ALI, "A", 1_000, "hello")),
        )])
        .unwrap();
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert_eq!(row.quoted.unwrap().id, "Q");
    assert_eq!(row.state, RowState::Read);
    assert!(row.starred);
}

#[test]
fn receipts_only_move_ticks_forward() {
    let mut store = store();
    let receipt = |seq, kind| {
        env(
            seq,
            GatewayEvent::Receipt {
                chat: ALI.into(),
                ids: vec!["A".into(), "THEIRS".into(), "UNKNOWN".into()],
                kind,
            },
        )
    };
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(mine(ALI, "A", 1_000, "hi"))),
            env(2, GatewayEvent::Message(text(ALI, "THEIRS", 1_500, "hey"))),
            receipt(3, ReceiptKind::Read),
            receipt(4, ReceiptKind::Delivered),
        ])
        .unwrap();
    assert_eq!(
        store.message(ALI, "A").unwrap().unwrap().state,
        RowState::Read
    );
    assert_eq!(
        store.message(ALI, "THEIRS").unwrap().unwrap().state,
        RowState::Received
    );
}

// ---- gap -> resync

fn state(last_seq: Option<u64>, needs_resync: bool) -> SyncState {
    SyncState {
        instance_id: "inst1".into(),
        last_seq,
        needs_resync,
        chats_synced_ms: None,
    }
}

#[test]
fn since_events_are_applied_in_order_and_advance_the_seq() {
    let events = Since::Events(vec![
        env(4, GatewayEvent::GroupChanged { jid: GROUP.into() }),
        env(5, GatewayEvent::GroupChanged { jid: GROUP.into() }),
        env(6, GatewayEvent::GroupChanged { jid: GROUP.into() }),
    ]);
    assert_eq!(
        plan::since(&state(Some(4), false), &events, 6),
        [Step::Apply(vec![5, 6]), Step::AdvanceSeq(6)]
    );
    assert_eq!(plan::since(&state(Some(6), false), &events, 6), []);
    assert_eq!(
        plan::since(&state(Some(6), true), &Since::Events(Vec::new()), 6),
        [Step::RefreshChats]
    );
}

#[test]
fn a_gap_plans_a_resync_from_the_gateway_head() {
    assert_eq!(
        plan::since(&state(Some(900), false), &Since::Gap, 12),
        [Step::AdvanceSeq(12), Step::RefreshChats]
    );
}

#[test]
fn chat_gap_only_pages_chats_that_missed_messages() {
    let cursor = ChatCursor {
        newest_ts: Some(5_000),
        newest_id: Some("N".into()),
        oldest_ts: Some(1_000),
        reached_start: false,
        gap: None,
    };
    let floor = Some(Gap {
        floor_ts: 5_000,
        before: None,
    });
    assert_eq!(
        plan::chat_gap(&cursor, Some(&text(ALI, "N", 5_000, "x")), false),
        None
    );
    assert_eq!(
        plan::chat_gap(&cursor, Some(&text(ALI, "NEW", 6_000, "x")), false),
        floor
    );
    assert_eq!(
        plan::chat_gap(&cursor, Some(&text(ALI, "N", 5_000, "x")), true),
        floor
    );
    assert_eq!(plan::chat_gap(&cursor, None, true), None);
    // Nothing stored: the newest message comes from the list, older history on demand.
    assert_eq!(
        plan::chat_gap(
            &ChatCursor::default(),
            Some(&text(ALI, "N", 5_000, "x")),
            true
        ),
        None
    );
    // An interrupted resync starts over from the top and keeps its floor.
    let interrupted = ChatCursor {
        newest_ts: Some(9_000),
        gap: Some(Gap {
            floor_ts: 5_000,
            before: Some(7_000),
        }),
        ..cursor
    };
    assert_eq!(
        plan::chat_gap(&interrupted, Some(&text(ALI, "Z", 9_000, "x")), false),
        floor
    );
}

#[test]
fn a_page_closes_a_gap_when_it_overlaps_or_runs_out() {
    let gap = Gap {
        floor_ts: 5_000,
        before: None,
    };
    assert_eq!(
        plan::after_page(gap, Some((8_000, 9_000)), 2, 2),
        Some(Gap {
            floor_ts: 5_000,
            before: Some(8_001)
        })
    );
    assert_eq!(plan::after_page(gap, Some((5_000, 9_000)), 2, 2), None);
    assert_eq!(plan::after_page(gap, Some((8_000, 9_000)), 1, 2), None);
    assert_eq!(plan::after_page(gap, None, 0, 2), None);
    // A page of one single millisecond still moves.
    assert_eq!(plan::next_before(8_000, 8_000), 8_000);
}

/// The gateway's `GET .../messages?before=&limit=`: newest first, strictly older than `before`.
fn gateway_page(all: &[WaMessage], before: Option<i64>, limit: u32) -> Vec<RichMessage> {
    let mut page: Vec<_> = all
        .iter()
        .filter(|message| before.is_none_or(|before| message.timestamp_ms < before))
        .cloned()
        .collect();
    page.sort_by(|a, b| (b.timestamp_ms, &b.id).cmp(&(a.timestamp_ms, &a.id)));
    page.truncate(limit as usize);
    page.into_iter().map(rich).collect()
}

#[test]
fn a_resync_pages_down_to_the_stored_history_and_drops_nothing() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A1", 1_000, "old 1"))),
            env(2, GatewayEvent::Message(text(ALI, "A2", 2_000, "old 2"))),
            env(
                3,
                GatewayEvent::Message(text(BERK, "B1", 1_500, "quiet chat")),
            ),
        ])
        .unwrap();
    assert!(store.plan(None).unwrap().refresh_chats, "first run");
    store
        .store_chats(
            &[
                chat(ALI, Some(text(ALI, "A2", 2_000, "old 2")), 0),
                chat(BERK, Some(text(BERK, "B1", 1_500, "quiet chat")), 0),
            ],
            10_000,
            Some(2),
            None,
        )
        .unwrap();
    assert_eq!(
        store.plan(Some(2)).unwrap(),
        SyncPlan {
            after_seq: Some(3),
            refresh_chats: false,
            fetch: Vec::new()
        }
    );

    // The gateway restarted: its sequence starts over and five messages were missed in one chat.
    let applied = store.apply_events(&[env(2, GatewayEvent::Resync)]).unwrap();
    assert!(applied.resync);
    assert_eq!(
        applied.last_seq,
        Some(2),
        "the seq follows the gateway's new head"
    );
    let plan = store.plan(Some(2)).unwrap();
    assert!(plan.refresh_chats && plan.fetch.is_empty());

    let mut gateway: Vec<WaMessage> = vec![
        text(ALI, "A2", 2_000, "old 2"),
        text(ALI, "A1", 1_000, "old 1"),
    ];
    gateway.extend((3..=7).map(|n| text(ALI, &format!("A{n}"), n * 1_000, "missed")));
    let stored = store
        .store_chats(
            &[
                chat(ALI, Some(text(ALI, "A7", 7_000, "missed")), 5),
                chat(BERK, Some(text(BERK, "B1", 1_500, "quiet chat")), 0),
                chat(GROUP, Some(text(GROUP, "G1", 6_500, "new group")), 1),
            ],
            20_000,
            Some(2),
            None,
        )
        .unwrap();
    // Only the chat that missed messages is paged; the new chat has its newest message from the list.
    let first = FetchPage {
        chat: ALI.into(),
        before: None,
        limit: 2,
    };
    assert_eq!(stored.fetch, std::slice::from_ref(&first));
    assert!(store.thread(ALI, 10, None).unwrap().syncing);

    let mut fetch = Some(first);
    let mut calls = Vec::new();
    while let Some(page) = fetch {
        calls.push(page.before);
        let messages = gateway_page(&gateway, page.before, page.limit);
        fetch = store
            .store_page(&page.chat, page.before, page.limit, &messages, 21_000)
            .unwrap()
            .next;
    }
    // Pages of two, newest first; the edge millisecond is asked for again; the page that reaches the old newest
    // message (2 000) closes the gap.
    assert_eq!(
        calls,
        [None, Some(6_001), Some(5_001), Some(4_001), Some(3_001)]
    );
    let thread = store.thread(ALI, 20, None).unwrap();
    assert_eq!(ids(&thread), ["A7", "A6", "A5", "A4", "A3", "A2", "A1"]);
    assert!(!thread.syncing);
    assert!(store.plan(Some(2)).unwrap().fetch.is_empty());
    assert_eq!(ids(&store.thread(GROUP, 20, None).unwrap()), ["G1"]);
    assert_eq!(store.chats(false).unwrap()[0].unread, 5);
}

#[test]
fn an_interrupted_resync_resumes_and_live_events_do_not_hide_the_hole() {
    let mut store = store();
    store
        .apply_events(&[env(1, GatewayEvent::Message(text(ALI, "A1", 1_000, "old")))])
        .unwrap();
    store.apply_events(&[env(9, GatewayEvent::Resync)]).unwrap();
    let gateway: Vec<WaMessage> = (1..=6)
        .map(|n| text(ALI, &format!("A{n}"), n * 1_000, "m"))
        .collect();
    let stored = store
        .store_chats(
            &[chat(ALI, Some(gateway[5].clone()), 5)],
            20_000,
            Some(2),
            None,
        )
        .unwrap();
    let first = &stored.fetch[0];
    let next = store
        .store_page(
            ALI,
            first.before,
            2,
            &gateway_page(&gateway, first.before, 2),
            0,
        )
        .unwrap()
        .next
        .unwrap();
    assert_eq!(next.before, Some(5_001));

    // The app dies here. A live message arrives after the restart; the hole below is still known.
    store
        .apply_events(&[env(
            10,
            GatewayEvent::Message(text(ALI, "A7", 7_000, "live")),
        )])
        .unwrap();
    assert_eq!(
        store.plan(Some(2)).unwrap().fetch,
        std::slice::from_ref(&next)
    );
    let mut fetch = Some(next);
    while let Some(page) = fetch {
        let messages = gateway_page(&gateway, page.before, page.limit);
        fetch = store
            .store_page(ALI, page.before, page.limit, &messages, 0)
            .unwrap()
            .next;
    }
    assert_eq!(
        ids(&store.thread(ALI, 20, None).unwrap()),
        ["A7", "A6", "A5", "A4", "A3", "A2", "A1"]
    );
}

#[test]
fn recent_chats_get_one_refresh_page_when_asked() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A1", 90_000, "recent"))),
            env(
                2,
                GatewayEvent::Message(text(BERK, "B1", 1_000, "long ago")),
            ),
        ])
        .unwrap();
    let list = [
        chat(ALI, Some(text(ALI, "A1", 90_000, "recent")), 0),
        chat(BERK, Some(text(BERK, "B1", 1_000, "long ago")), 0),
    ];
    let stored = store
        .store_chats(&list, 100_000, None, Some(50_000))
        .unwrap();
    assert_eq!(
        stored.fetch,
        [FetchPage {
            chat: ALI.into(),
            before: None,
            limit: 50
        }]
    );
    // That one page overlaps at once.
    let page = [rich(text(ALI, "A1", 90_000, "recent"))];
    assert_eq!(
        store.store_page(ALI, None, 50, &page, 0).unwrap().next,
        None
    );
    assert!(store.plan(None).unwrap().fetch.is_empty());
}

#[test]
fn older_history_is_fetched_before_the_oldest_stored_message_until_the_start() {
    let mut store = store();
    let gateway: Vec<WaMessage> = (1..=5)
        .map(|n| text(ALI, &format!("A{n}"), n * 1_000, "m"))
        .collect();
    store
        .store_chats(&[chat(ALI, Some(gateway[4].clone()), 0)], 9_000, None, None)
        .unwrap();
    assert_eq!(ids(&store.thread(ALI, 10, None).unwrap()), ["A5"]);

    let mut pages = 0;
    while let Some(page) = store.older(ALI, Some(3)).unwrap() {
        let messages = gateway_page(&gateway, page.before, page.limit);
        store
            .store_page(ALI, page.before, page.limit, &messages, 0)
            .unwrap();
        pages += 1;
        assert!(pages < 10, "paging must end");
    }
    assert_eq!(pages, 3);
    let thread = store.thread(ALI, 10, None).unwrap();
    assert_eq!(ids(&thread), ["A5", "A4", "A3", "A2", "A1"]);
    assert!(thread.reached_start);
    assert_eq!(store.older(ALI, None).unwrap(), None);
}

// ---- edits and deletes are kept

#[test]
fn a_message_deleted_for_everyone_keeps_what_it_said() {
    let mut store = store();
    let mut photo = rich(WaMessage {
        body: MessageBody::Media {
            kind: MediaKind::Image,
            caption: Some("look".into()),
            mime: "image/jpeg".into(),
            size: 2_048,
        },
        ..text(ALI, "P", 1_000, "")
    });
    photo.meta = MessageMeta {
        quoted: Some(Quoted {
            id: "Q".into(),
            sender: None,
            preview: Some("earlier".into()),
        }),
        media: Some(MediaMeta {
            width: Some(640),
            thumbnail: true,
            ..Default::default()
        }),
        ..Default::default()
    };
    store.store_page(ALI, None, 50, &[photo], 0).unwrap();
    store.set_thumbnail(ALI, "P", b"thumb").unwrap();
    store
        .set_media_file(ALI, "P", "/media/p.jpg", 2_048, 5)
        .unwrap();

    // The gateway's revoke: the body reads `unsupported`, nothing of the content is repeated.
    store
        .apply_events(&[env(
            1,
            GatewayEvent::MessageMeta {
                chat: ALI.into(),
                id: "P".into(),
                body: Some(MessageBody::Unsupported),
                meta: Box::new(MessageMeta {
                    revoked: true,
                    deleted: true,
                    deleted_by: Some(ALI.into()),
                    ..Default::default()
                }),
            },
        )])
        .unwrap();
    let row = store.message(ALI, "P").unwrap().unwrap();
    assert!(row.deleted);
    assert_eq!(
        row.deleted_at,
        Some(1_000_001),
        "the event's time when the gateway names none"
    );
    assert_eq!(row.deleted_by.as_deref(), Some(ALI));
    assert_eq!((row.kind, row.text.as_deref()), (Kind::Image, Some("look")));
    assert_eq!(row.quoted.unwrap().id, "Q");
    assert_eq!(row.media.unwrap().width, Some(640));
    assert!(row.has_thumbnail);
    assert_eq!(row.media_path.as_deref(), Some("/media/p.jpg"));
    assert_eq!(
        ids(&store.thread(ALI, 10, None).unwrap()),
        ["P"],
        "still in the thread"
    );

    // A later page lists it as deleted, without content: nothing is lost.
    let mut listed = rich(WaMessage {
        body: MessageBody::Unsupported,
        ..text(ALI, "P", 1_000, "")
    });
    listed.meta = MessageMeta {
        revoked: true,
        deleted: true,
        deleted_at: Some(4_242),
        ..Default::default()
    };
    store.store_page(ALI, None, 50, &[listed], 0).unwrap();
    let row = store.message(ALI, "P").unwrap().unwrap();
    assert_eq!((row.kind, row.text.as_deref()), (Kind::Image, Some("look")));
    assert_eq!(row.deleted_at, Some(4_242), "the gateway's time wins");
}

#[test]
fn a_message_deleted_for_me_is_only_marked() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A", 1_000, "secret"))),
            env(
                2,
                GatewayEvent::MessageDeleted {
                    chat: ALI.into(),
                    id: "A".into(),
                    deleted_at: Some(7_000),
                    deleted_by: None,
                },
            ),
            // A re-delivered copy never revives or empties it.
            env(3, GatewayEvent::Message(text(ALI, "A", 1_000, "secret"))),
        ])
        .unwrap();
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert!(row.deleted);
    assert_eq!(
        (row.deleted_at, row.deleted_by.as_deref()),
        (Some(7_000), Some(DELETED_BY_ME))
    );
    assert_eq!(row.text.as_deref(), Some("secret"));
}

#[test]
fn a_deleted_message_never_seen_before_takes_the_content_the_gateway_kept() {
    let mut store = store();
    let mut listed = rich(WaMessage {
        body: MessageBody::Unsupported,
        ..text(ALI, "A", 1_000, "")
    });
    listed.meta = MessageMeta {
        revoked: true,
        deleted: true,
        deleted_at: Some(2_000),
        deleted_by: Some(ALI.into()),
        original: Some(Box::new(KeptContent {
            body: MessageBody::Text("what it said".into()),
            meta: MessageMeta {
                edit_history: vec![EditRecord {
                    text: "what it sad".into(),
                    edited_at: 1_500,
                }],
                ..Default::default()
            },
        })),
        ..Default::default()
    };
    store.store_page(ALI, None, 50, &[listed], 0).unwrap();
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert!(row.deleted);
    assert_eq!(
        (row.kind, row.text.as_deref()),
        (Kind::Text, Some("what it said"))
    );
    assert_eq!(row.edit_history.len(), 1);
}

#[test]
fn an_edit_keeps_the_previous_text_as_history() {
    let mut store = store();
    let edit = |seq, new: &str, edited_ms, history: Vec<EditRecord>| {
        env(
            seq,
            GatewayEvent::MessageMeta {
                chat: ALI.into(),
                id: "A".into(),
                body: Some(MessageBody::Text(new.into())),
                meta: Box::new(MessageMeta {
                    edited_ms: Some(edited_ms),
                    edit_history: history,
                    ..Default::default()
                }),
            },
        )
    };
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A", 1_000, "helo"))),
            // An older gateway sends no history: the store collects it.
            edit(2, "hello", 2_000, Vec::new()),
            edit(3, "hello!", 3_000, Vec::new()),
        ])
        .unwrap();
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert_eq!(row.text.as_deref(), Some("hello!"));
    assert_eq!(row.edited_ms, Some(3_000));
    let history = |row: &MessageRow| -> Vec<(String, i64)> {
        row.edit_history
            .iter()
            .map(|record| (record.text.clone(), record.edited_at))
            .collect()
    };
    assert_eq!(
        history(&row),
        [("helo".to_owned(), 2_000), ("hello".to_owned(), 3_000)]
    );

    // The same edit again adds nothing; a gateway that sends the whole history replaces what was collected.
    store
        .apply_events(&[edit(4, "hello!", 3_000, Vec::new())])
        .unwrap();
    assert_eq!(
        store.message(ALI, "A").unwrap().unwrap().edit_history.len(),
        2
    );
    let full = vec![
        EditRecord {
            text: "helo".into(),
            edited_at: 1_999,
        },
        EditRecord {
            text: "hello".into(),
            edited_at: 2_999,
        },
        EditRecord {
            text: "hello!".into(),
            edited_at: 3_999,
        },
    ];
    store
        .apply_events(&[edit(5, "hello!!", 4_000, full)])
        .unwrap();
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert_eq!(row.text.as_deref(), Some("hello!!"));
    assert_eq!(row.edit_history.len(), 3);
    assert_eq!(row.edit_history[0].edited_at, 1_999);
}

#[test]
fn a_cleared_or_deleted_chat_keeps_its_messages_in_the_store() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A", 1_000, "kept"))),
            env(2, GatewayEvent::Message(text(BERK, "B", 1_000, "kept too"))),
            env(3, GatewayEvent::ChatCleared { jid: ALI.into() }),
            env(4, GatewayEvent::ChatDeleted { jid: BERK.into() }),
        ])
        .unwrap();
    assert!(store.thread(ALI, 10, None).unwrap().items.is_empty());
    assert!(store.message(ALI, "A").unwrap().is_some());
    assert!(store.message(BERK, "B").unwrap().is_some());
    let listed: Vec<_> = store
        .chats(false)
        .unwrap()
        .into_iter()
        .map(|c| c.jid)
        .collect();
    assert_eq!(listed, [ALI]);
    // A new message brings the deleted chat back.
    store
        .apply_events(&[env(
            5,
            GatewayEvent::Message(text(BERK, "B2", 2_000, "again")),
        )])
        .unwrap();
    assert_eq!(store.chats(false).unwrap().len(), 2);
}

#[test]
fn a_merged_chat_moves_its_messages_and_asks_for_a_refresh() {
    let mut store = store();
    let lid = "777000000000001@lid";
    store
        .apply_events(&[
            env(
                1,
                GatewayEvent::Message(text(lid, "L1", 1_000, "from the lid chat")),
            ),
            env(
                2,
                GatewayEvent::Message(text(lid, "SAME", 2_000, "in both")),
            ),
            env(
                3,
                GatewayEvent::Message(text(ALI, "SAME", 2_000, "in both")),
            ),
        ])
        .unwrap();
    store.store_chats(&[], 1, None, None).unwrap();
    store
        .outbox_enqueue("c1", lid, &send("queued"), 3_000)
        .unwrap();
    let applied = store
        .apply_events(&[env(
            4,
            GatewayEvent::ChatMerged {
                from: lid.into(),
                into: ALI.into(),
            },
        )])
        .unwrap();
    assert!(applied.resync);
    assert!(store.plan(None).unwrap().refresh_chats);
    assert_eq!(
        ids(&store.thread(ALI, 10, None).unwrap()),
        ["c1", "SAME", "L1"]
    );
    assert!(store.thread(lid, 10, None).unwrap().items.is_empty());
    assert_eq!(store.cursor(ALI).unwrap().oldest_ts, Some(1_000));
}

#[test]
fn chat_settings_follow_the_gateway() {
    let mut store = store();
    let mut listed = chat(ALI, Some(text(ALI, "A", 1_000, "hi")), 3);
    listed.chat.name = Some("Ali".into());
    listed.meta = ChatMeta {
        pinned: true,
        archived: true,
        muted_until_ms: Some(-1),
        push_name: Some("ali".into()),
        ..Default::default()
    };
    store.store_chats(&[listed], 5_000, None, None).unwrap();
    assert!(store.chats(false).unwrap().is_empty(), "archived");
    let row = store.chats(true).unwrap().remove(0);
    assert_eq!(
        (row.name.as_deref(), row.push_name.as_deref()),
        (Some("Ali"), Some("ali"))
    );
    assert!(row.pinned && row.archived);
    assert_eq!((row.unread, row.muted_until_ms), (3, Some(-1)));
    store
        .apply_events(&[env(
            1,
            GatewayEvent::ChatMeta {
                jid: ALI.into(),
                meta: ChatMeta::default(),
            },
        )])
        .unwrap();
    let row = store.chats(false).unwrap().remove(0);
    assert!(!row.pinned && !row.archived);
    assert_eq!(
        row.push_name.as_deref(),
        Some("ali"),
        "an event without a name keeps it"
    );
}

// ---- outbox

#[test]
fn queued_messages_show_at_once_and_go_out_in_the_order_written_per_chat() {
    let mut store = store();
    store
        .apply_events(&[env(1, GatewayEvent::Message(text(ALI, "A", 1_000, "hi")))])
        .unwrap();
    store
        .outbox_enqueue("c1", ALI, &send("one"), 2_000)
        .unwrap();
    store
        .outbox_enqueue("c2", ALI, &send("two"), 2_001)
        .unwrap();
    store
        .outbox_enqueue("c3", BERK, &send("other chat"), 2_002)
        .unwrap();
    // Queuing the same client id again (a retried call) is the same row.
    store
        .outbox_enqueue("c1", ALI, &send("one"), 2_500)
        .unwrap();
    assert_eq!(store.outbox().unwrap().len(), 3);

    let thread = store.thread(ALI, 10, None).unwrap();
    assert_eq!(ids(&thread), ["c2", "c1", "A"]);
    assert_eq!(thread.items[0].state, RowState::Waiting);
    let chats = store.chats(false).unwrap();
    assert_eq!(
        chats[0].jid, BERK,
        "a waiting send makes its chat the newest"
    );
    assert_eq!(
        (
            chats[1].waiting,
            chats[1].last.as_ref().unwrap().id.as_str()
        ),
        (2, "c2")
    );

    let due = |store: &mut Store, now| -> Vec<String> {
        store
            .outbox_due(now, false)
            .unwrap()
            .items
            .into_iter()
            .map(|item| item.client_id)
            .collect()
    };
    assert_eq!(
        due(&mut store, 3_000),
        ["c1", "c3"],
        "one per chat, the oldest"
    );
    assert_eq!(
        due(&mut store, 3_001),
        Vec::<String>::new(),
        "c2 waits for c1"
    );
    assert_eq!(
        store.thread(ALI, 10, None).unwrap().items[1].state,
        RowState::Sending
    );
    store
        .outbox_settle("c1", &sent("S1", 3_100), 3_100)
        .unwrap();
    assert_eq!(due(&mut store, 3_200), ["c2"]);
}

#[test]
fn the_answer_and_the_echo_in_either_order_leave_one_message() {
    for echo_first in [false, true] {
        let mut store = store();
        store
            .outbox_enqueue("c1", ALI, &send("hello"), 1_000)
            .unwrap();
        assert_eq!(store.outbox_due(1_000, false).unwrap().items.len(), 1);
        let echo = env(1, GatewayEvent::Message(mine(ALI, "S1", 1_200, "hello")));
        if echo_first {
            let applied = store.apply_events(std::slice::from_ref(&echo)).unwrap();
            assert_eq!(applied.reconciled, ["c1"]);
        }
        let settled = store
            .outbox_settle("c1", &sent("S1", 1_200), 1_300)
            .unwrap();
        assert_eq!(
            settled,
            Settled::Done {
                message_id: Some("S1".into())
            }
        );
        if !echo_first {
            let applied = store.apply_events(&[echo]).unwrap();
            assert!(applied.reconciled.is_empty());
        }
        let thread = store.thread(ALI, 10, None).unwrap();
        assert_eq!(ids(&thread), ["S1"], "echo first: {echo_first}");
        assert_eq!(thread.items[0].client_id.as_deref(), Some("c1"));
        assert_eq!(thread.items[0].state, RowState::Sent);
        assert!(store.outbox().unwrap().is_empty());
        assert!(
            store.outbox_due(9_999_999, true).unwrap().items.is_empty(),
            "never sent twice"
        );
    }
}

#[test]
fn a_send_whose_answer_was_lost_is_confirmed_by_its_echo_instead_of_sent_again() {
    let mut store = store();
    store
        .outbox_enqueue("c1", ALI, &send("hello"), 1_000)
        .unwrap();
    store.outbox_due(1_000, false).unwrap();
    // The request left, the connection dropped: nobody knows whether it went out.
    let settled = store
        .outbox_settle("c1", &error(ErrorKind::Network), 1_500)
        .unwrap();
    assert_eq!(
        settled,
        Settled::Waiting {
            next_attempt_ms: 1_500 + BACKOFF_BASE_MS
        }
    );
    // Back online, the missed events come first: the echo is among them.
    let applied = store
        .apply_events(&[env(
            1,
            GatewayEvent::Message(mine(ALI, "S1", 1_100, "hello")),
        )])
        .unwrap();
    assert_eq!(applied.reconciled, ["c1"]);
    assert!(store.outbox_due(99_999, true).unwrap().items.is_empty());
    assert_eq!(ids(&store.thread(ALI, 10, None).unwrap()), ["S1"]);
    // The answer of a later, pointless settle call is still "done".
    assert_eq!(
        store
            .outbox_settle("c1", &sent("S1", 1_100), 2_000)
            .unwrap(),
        Settled::Done {
            message_id: Some("S1".into())
        }
    );
}

#[test]
fn the_same_text_from_my_phone_does_not_swallow_a_message_that_never_went_out() {
    let mut store = store();
    store.outbox_enqueue("c1", ALI, &send("ok"), 1_000).unwrap();
    // Never attempted: an "ok" typed on the phone is another message.
    let applied = store
        .apply_events(&[env(
            1,
            GatewayEvent::Message(mine(ALI, "PHONE", 1_100, "ok")),
        )])
        .unwrap();
    assert!(applied.reconciled.is_empty());
    assert_eq!(ids(&store.thread(ALI, 10, None).unwrap()), ["c1", "PHONE"]);

    // Attempted and refused by the gateway (it said it did not send): still not confirmed by a look-alike.
    store.outbox_due(2_000, false).unwrap();
    store
        .outbox_settle("c1", &error(ErrorKind::Gateway), 2_100)
        .unwrap();
    let applied = store
        .apply_events(&[env(
            2,
            GatewayEvent::Message(mine(ALI, "PHONE2", 2_200, "ok")),
        )])
        .unwrap();
    assert!(applied.reconciled.is_empty());
    assert_eq!(store.outbox().unwrap().len(), 1);

    // An echo older than the attempt (history replayed) is not it either.
    store.outbox_due(99_000_000, true).unwrap();
    store
        .outbox_settle("c1", &error(ErrorKind::Network), 99_000_100)
        .unwrap();
    let applied = store
        .apply_events(&[env(3, GatewayEvent::Message(mine(ALI, "OLD", 5_000, "ok")))])
        .unwrap();
    assert!(applied.reconciled.is_empty());
}

#[test]
fn a_resync_page_confirms_a_send_whose_echo_fell_into_the_gap() {
    let mut store = store();
    store
        .outbox_enqueue("c1", ALI, &send("hello"), 1_000)
        .unwrap();
    store.outbox_due(1_000, false).unwrap();
    // The app was killed mid-send; after the restart the events gap is repaired by pages.
    let page = [rich(mine(ALI, "S1", 1_050, "hello"))];
    let stored = store.store_page(ALI, None, 50, &page, 5_000).unwrap();
    assert_eq!(stored.reconciled, ["c1"]);
    assert!(store.outbox().unwrap().is_empty());
}

#[test]
fn a_send_that_may_have_gone_out_waits_for_the_sync_before_it_is_retried() {
    let mut store = store();
    store
        .apply_events(&[env(1, GatewayEvent::Message(text(ALI, "A1", 1_000, "old")))])
        .unwrap();
    store.store_chats(&[], 1_500, None, None).unwrap();
    store
        .outbox_enqueue("c1", ALI, &send("hello"), 2_000)
        .unwrap();
    store
        .outbox_enqueue("c2", BERK, &send("other chat, never tried"), 2_001)
        .unwrap();
    store.outbox_due(2_000, false).unwrap();
    store
        .outbox_settle("c1", &error(ErrorKind::Network), 2_500)
        .unwrap();
    store
        .outbox_settle("c2", &error(ErrorKind::Gateway), 2_500)
        .unwrap();

    // Back online after a gap: the echo of "hello" may be among what was missed.
    store.apply_events(&[env(1, GatewayEvent::Resync)]).unwrap();
    let due = store.outbox_due(900_000, true).unwrap();
    let ready: Vec<_> = due
        .items
        .iter()
        .map(|item| item.client_id.as_str())
        .collect();
    assert_eq!((ready, due.held), (vec!["c2"], 1));

    // The chat list shows a newer message: still unsure until that chat's pages are in.
    let newest = text(ALI, "A2", 3_000, "something else");
    let stored = store
        .store_chats(&[chat(ALI, Some(newest.clone()), 1)], 900_100, None, None)
        .unwrap();
    assert_eq!(stored.fetch.len(), 1);
    assert_eq!(store.outbox_due(900_200, true).unwrap().held, 1);

    // The pages hold no echo: it really never went out, so now it goes.
    let page = [rich(newest), rich(text(ALI, "A1", 1_000, "old"))];
    assert_eq!(
        store
            .store_page(ALI, None, 50, &page, 900_300)
            .unwrap()
            .next,
        None
    );
    let due = store.outbox_due(900_400, true).unwrap();
    assert_eq!((due.items[0].client_id.as_str(), due.held), ("c1", 0));
}

#[test]
fn errors_back_off_and_a_returning_network_ends_the_wait() {
    assert_eq!(backoff_ms(1), 2_000);
    assert_eq!(backoff_ms(2), 4_000);
    assert_eq!(backoff_ms(5), 32_000);
    assert_eq!(backoff_ms(40), BACKOFF_MAX_MS);

    let mut store = store();
    store.outbox_enqueue("c1", ALI, &send("one"), 0).unwrap();
    store.outbox_enqueue("c2", ALI, &send("two"), 1).unwrap();
    let mut now = 10_000;
    for attempt in 1..=3u32 {
        let due = store.outbox_due(now, false).unwrap();
        assert_eq!(due.items.len(), 1);
        assert_eq!(due.items[0].attempts, attempt);
        let settled = store
            .outbox_settle("c1", &error(ErrorKind::Gateway), now)
            .unwrap();
        let next = now + backoff_ms(attempt);
        assert_eq!(
            settled,
            Settled::Waiting {
                next_attempt_ms: next
            }
        );
        // Not due yet, and the later message of the chat does not overtake.
        let waiting = store.outbox_due(next - 1, false).unwrap();
        assert!(waiting.items.is_empty());
        assert_eq!(waiting.next_wake_ms, Some(next));
        now = next;
    }
    // The gateway's own hint wins when it is longer.
    store.outbox_due(now, false).unwrap();
    let limited = Outcome::Error {
        error: ErrorKind::Gateway,
        message: "rate limited".into(),
        retry_after_ms: Some(900_000),
    };
    assert_eq!(
        store.outbox_settle("c1", &limited, now).unwrap(),
        Settled::Waiting {
            next_attempt_ms: now + 900_000
        }
    );
    // The connection is back: no reason to keep waiting.
    let back = store.outbox_due(now + 1, true).unwrap();
    assert_eq!(back.items[0].client_id, "c1");
}

#[test]
fn a_send_left_in_flight_by_a_killed_app_is_due_again_after_its_lease() {
    let mut store = store();
    store.store_chats(&[], 0, None, None).unwrap();
    store.outbox_enqueue("c1", ALI, &send("one"), 0).unwrap();
    let first = store.outbox_due(1_000, false).unwrap();
    assert_eq!(first.next_wake_ms, Some(1_000 + SENDING_LEASE_MS));
    assert!(
        store
            .outbox_due(1_000 + SENDING_LEASE_MS - 1, true)
            .unwrap()
            .items
            .is_empty()
    );
    let again = store.outbox_due(1_000 + SENDING_LEASE_MS, false).unwrap();
    assert_eq!((again.items.len(), again.items[0].attempts), (1, 2));
}

#[test]
fn a_rejected_message_fails_and_lets_the_next_one_through() {
    let mut store = store();
    store
        .outbox_enqueue("c1", ALI, &send("too long"), 0)
        .unwrap();
    store.outbox_enqueue("c2", ALI, &send("fine"), 1).unwrap();
    store.outbox_due(10, false).unwrap();
    assert_eq!(
        store
            .outbox_settle("c1", &error(ErrorKind::Rejected), 20)
            .unwrap(),
        Settled::Failed
    );
    let thread = store.thread(ALI, 10, None).unwrap();
    assert_eq!(thread.items[1].state, RowState::Failed);
    assert_eq!(thread.items[1].error.as_deref(), Some("no"));
    assert_eq!(
        store.outbox_due(30, false).unwrap().items[0].client_id,
        "c2"
    );
    store.outbox_settle("c2", &sent("S2", 40), 40).unwrap();

    // The owner edits the failed one: it waits again with the new text.
    let edited = store.outbox_edit("c1", "short").unwrap();
    assert_eq!(edited.state, OutboxState::Waiting);
    let due = store.outbox_due(50, false).unwrap();
    assert_eq!(due.items[0].action, send("short"));
}

#[test]
fn a_waiting_message_can_be_cancelled_or_edited_but_not_one_in_flight() {
    let mut store = store();
    store.outbox_enqueue("c1", ALI, &send("draft"), 0).unwrap();
    store.outbox_enqueue("c2", ALI, &send("second"), 1).unwrap();
    assert_eq!(
        store.outbox_edit("c2", "second, edited").unwrap().action,
        send("second, edited")
    );
    assert!(store.outbox_cancel("c2").unwrap());
    assert!(!store.outbox_cancel("c2").unwrap(), "already gone");

    store.outbox_due(10, false).unwrap();
    assert!(!store.outbox_cancel("c1").unwrap(), "going out right now");
    assert!(matches!(
        store.outbox_edit("c1", "x"),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        store.outbox_edit("nope", "x"),
        Err(Error::NotFound(_))
    ));
    assert_eq!(ids(&store.thread(ALI, 10, None).unwrap()), ["c1"]);
}

#[test]
fn edits_deletes_reactions_and_read_marks_queue_too() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(mine(ALI, "MINE", 1_000, "helo"))),
            env(2, GatewayEvent::Message(text(ALI, "THEIRS", 2_000, "hi"))),
        ])
        .unwrap();
    assert_eq!(store.chats(false).unwrap()[0].unread, 1);
    let edit = Action::Edit {
        message_id: "MINE".into(),
        text: "hello".into(),
    };
    let react = Action::React {
        message_id: "THEIRS".into(),
        from_me: false,
        sender: Some(ALI.into()),
        emoji: "+".into(),
    };
    let read = Action::MarkRead {
        sender: Some(ALI.into()),
        ids: vec!["THEIRS".into()],
    };
    let revoke = Action::Revoke {
        message_id: "MINE".into(),
        sender: None,
    };
    store.outbox_enqueue("e1", ALI, &edit, 3_000).unwrap();
    store.outbox_enqueue("r1", ALI, &react, 3_001).unwrap();
    store.outbox_enqueue("m1", ALI, &read, 3_002).unwrap();
    store.outbox_enqueue("d1", ALI, &revoke, 3_003).unwrap();

    // Shown at once: the reaction and the read chat; the edit and the delete as pending marks on their message.
    let thread = store.thread(ALI, 10, None).unwrap();
    assert_eq!(ids(&thread), ["THEIRS", "MINE"]);
    assert_eq!(
        thread.items[0].reactions,
        [Reaction {
            sender: None,
            from_me: true,
            emoji: "+".into(),
            sender_alt: None,
        }]
    );
    let pending: Vec<_> = thread.items[1]
        .pending
        .iter()
        .map(|op| (op.kind.as_str(), op.text.as_deref()))
        .collect();
    assert_eq!(pending, [("edit", Some("hello")), ("revoke", None)]);
    assert_eq!(store.chats(false).unwrap()[0].unread, 0);
    // A chat list fetched before the read mark went out does not bring the unread count back.
    store
        .store_chats(
            &[chat(ALI, Some(text(ALI, "THEIRS", 2_000, "hi")), 1)],
            3_500,
            None,
            None,
        )
        .unwrap();
    assert_eq!(store.chats(false).unwrap()[0].unread, 0);

    // They go out in the order written; each answer is plain "sent".
    let done = Outcome::Sent {
        message_id: None,
        timestamp_ms: None,
    };
    for (expected, now) in [("e1", 4_000), ("r1", 4_001), ("m1", 4_002), ("d1", 4_003)] {
        let due = store.outbox_due(now, false).unwrap();
        assert_eq!(due.items[0].client_id, expected);
        assert_eq!(
            store.outbox_settle(expected, &done, now).unwrap(),
            Settled::Done { message_id: None }
        );
    }
    let row = store.message(ALI, "MINE").unwrap().unwrap();
    assert_eq!(
        row.text.as_deref(),
        Some("hello"),
        "the edit shows before its event"
    );
    assert_eq!(row.edit_history[0].text, "helo");
    assert!(row.deleted && row.deleted_by.as_deref() == Some(DELETED_BY_ME));
    assert!(row.pending.is_empty());
}

fn star(id: &str, starred: bool) -> Action {
    Action::Star {
        message_id: id.into(),
        starred,
    }
}

/// A page row as the gateway has it, with its star.
fn starred_rich(message: WaMessage, starred: bool) -> RichMessage {
    let mut rich = rich(message);
    rich.meta.starred = starred;
    rich
}

fn is_starred(store: &Store, chat: &str, id: &str) -> bool {
    store.message(chat, id).unwrap().unwrap().starred
}

fn starred_ids(page: &StarredPage) -> Vec<(&str, &str)> {
    page.items
        .iter()
        .map(|row| (row.chat.as_str(), row.id.as_str()))
        .collect()
}

#[test]
fn a_star_shows_at_once_holds_against_the_gateway_and_goes_out() {
    let mut store = store();
    store
        .apply_events(&[env(1, GatewayEvent::Message(text(ALI, "A", 1_000, "hi")))])
        .unwrap();
    let queued = store
        .outbox_enqueue("s1", ALI, &star("A", true), 2_000)
        .unwrap();
    assert_eq!(queued.action.kind(), "star");
    assert_eq!(queued.state, OutboxState::Waiting);
    assert!(is_starred(&store, ALI, "A"), "starred before it went out");
    assert_eq!(
        starred_ids(&store.starred(10, None).unwrap()),
        [(ALI, "A")],
        "the Starred list has it while it waits"
    );
    // It is not a thread row and not a pending mark of its message.
    let thread = store.thread(ALI, 10, None).unwrap();
    assert_eq!(ids(&thread), ["A"]);
    assert!(thread.items[0].pending.is_empty());
    // Queuing the same client id again is the same row.
    store
        .outbox_enqueue("s1", ALI, &star("A", true), 2_001)
        .unwrap();
    assert_eq!(store.outbox().unwrap().len(), 1);

    // A page fetched before the star went out still says "not starred": the star holds.
    store
        .store_page(
            ALI,
            None,
            50,
            &[starred_rich(text(ALI, "A", 1_000, "hi"), false)],
            2_500,
        )
        .unwrap();
    assert!(is_starred(&store, ALI, "A"));

    // A gateway that did not do it is asked again; the star stays.
    assert_eq!(
        store.outbox_due(3_000, false).unwrap().items[0].client_id,
        "s1"
    );
    assert_eq!(
        store
            .outbox_settle("s1", &error(ErrorKind::Gateway), 3_000)
            .unwrap(),
        Settled::Waiting {
            next_attempt_ms: 3_000 + BACKOFF_BASE_MS
        }
    );
    assert!(is_starred(&store, ALI, "A"));
    let due = store.outbox_due(3_100, true).unwrap();
    assert_eq!(due.items[0].action, star("A", true));
    assert_eq!(
        store
            .outbox_settle(
                "s1",
                &Outcome::Sent {
                    message_id: None,
                    timestamp_ms: None
                },
                3_200
            )
            .unwrap(),
        Settled::Done { message_id: None }
    );
    assert!(store.outbox().unwrap().is_empty());
    assert!(is_starred(&store, ALI, "A"));

    // Nothing waits any more: an unstar made on another device is followed.
    store
        .store_page(
            ALI,
            None,
            50,
            &[starred_rich(text(ALI, "A", 1_000, "hi"), false)],
            4_000,
        )
        .unwrap();
    assert!(!is_starred(&store, ALI, "A"));
    assert!(store.starred(10, None).unwrap().items.is_empty());
}

#[test]
fn a_refused_star_is_undone_and_dropped() {
    let mut store = store();
    store
        .store_page(
            ALI,
            None,
            50,
            &[
                starred_rich(text(ALI, "B", 2_000, "starred"), true),
                rich(text(ALI, "A", 1_000, "plain")),
            ],
            0,
        )
        .unwrap();
    store
        .outbox_enqueue("s1", ALI, &star("A", true), 3_000)
        .unwrap();
    store
        .outbox_enqueue("c1", ALI, &send("next"), 3_001)
        .unwrap();
    assert!(is_starred(&store, ALI, "A"));
    store.outbox_due(3_100, false).unwrap();
    assert_eq!(
        store
            .outbox_settle("s1", &error(ErrorKind::Rejected), 3_200)
            .unwrap(),
        Settled::Failed
    );
    assert!(!is_starred(&store, ALI, "A"), "the star is taken back");
    // No failed row is left behind, and the chat's next row goes out.
    let left: Vec<_> = store
        .outbox()
        .unwrap()
        .into_iter()
        .map(|item| item.client_id)
        .collect();
    assert_eq!(left, ["c1"]);
    assert_eq!(
        store.outbox_due(3_300, false).unwrap().items[0].client_id,
        "c1"
    );
    store
        .outbox_settle("c1", &sent("S1", 3_400), 3_400)
        .unwrap();

    // A refused unstar brings the star back.
    store
        .outbox_enqueue("s2", ALI, &star("B", false), 4_000)
        .unwrap();
    assert!(!is_starred(&store, ALI, "B"));
    store.outbox_due(4_100, false).unwrap();
    store
        .outbox_settle("s2", &error(ErrorKind::Rejected), 4_200)
        .unwrap();
    assert!(is_starred(&store, ALI, "B"));
    assert!(store.outbox().unwrap().is_empty());
}

#[test]
fn a_star_and_its_unstar_written_offline_are_one_call() {
    let mut store = store();
    store
        .apply_events(&[
            env(1, GatewayEvent::Message(text(ALI, "A", 1_000, "one"))),
            env(2, GatewayEvent::Message(text(ALI, "B", 2_000, "two"))),
        ])
        .unwrap();
    store
        .outbox_enqueue("s1", ALI, &star("A", true), 3_000)
        .unwrap();
    store
        .outbox_enqueue("s2", ALI, &star("B", true), 3_001)
        .unwrap();
    store
        .outbox_enqueue("s3", ALI, &star("A", false), 3_002)
        .unwrap();
    // The unstar replaced the star of the same message; the other message's star is untouched.
    let queued: Vec<_> = store
        .outbox()
        .unwrap()
        .into_iter()
        .map(|item| (item.client_id, item.action))
        .collect();
    assert_eq!(
        queued,
        [
            ("s2".to_owned(), star("B", true)),
            ("s3".to_owned(), star("A", false))
        ]
    );
    assert!(!is_starred(&store, ALI, "A"));
    assert!(is_starred(&store, ALI, "B"));
    assert_eq!(starred_ids(&store.starred(10, None).unwrap()), [(ALI, "B")]);

    // One in flight cannot be taken back: the newer wish waits behind it and decides the flag whatever comes back.
    assert_eq!(
        store.outbox_due(4_000, false).unwrap().items[0].client_id,
        "s2"
    );
    store
        .outbox_enqueue("s4", ALI, &star("B", false), 4_001)
        .unwrap();
    assert_eq!(store.outbox().unwrap().len(), 3);
    assert!(!is_starred(&store, ALI, "B"));
    let done = Outcome::Sent {
        message_id: None,
        timestamp_ms: None,
    };
    store.outbox_settle("s2", &done, 4_100).unwrap();
    assert!(
        !is_starred(&store, ALI, "B"),
        "the newer unstar still holds"
    );
    // Undo: the star again replaces the unstar that has not gone out.
    store
        .outbox_enqueue("s5", ALI, &star("B", true), 4_200)
        .unwrap();
    let queued: Vec<_> = store
        .outbox()
        .unwrap()
        .into_iter()
        .map(|item| item.client_id)
        .collect();
    assert_eq!(queued, ["s3", "s5"]);
    assert!(is_starred(&store, ALI, "B"));
    // A refused star that a newer one replaced in the meantime leaves the flag to the newer one.
    assert_eq!(
        store.outbox_due(4_300, false).unwrap().items[0].client_id,
        "s3"
    );
    store
        .outbox_enqueue("s6", ALI, &star("A", true), 4_301)
        .unwrap();
    store
        .outbox_settle("s3", &error(ErrorKind::Rejected), 4_400)
        .unwrap();
    assert!(is_starred(&store, ALI, "A"));
    // A star cannot be edited like a text.
    assert!(matches!(
        store.outbox_edit("s6", "x"),
        Err(Error::Invalid(_))
    ));
}

#[test]
fn starred_messages_of_every_chat_are_listed_newest_first_in_pages() {
    let mut store = store();
    store
        .store_page(
            ALI,
            None,
            50,
            &[
                starred_rich(text(ALI, "A5", 5_000, "five"), true),
                rich(text(ALI, "A4", 4_000, "not starred")),
                starred_rich(text(ALI, "A3", 3_000, "three"), true),
                starred_rich(mine(ALI, "A1", 1_000, "one"), true),
            ],
            0,
        )
        .unwrap();
    store
        .store_page(
            BERK,
            None,
            50,
            &[
                starred_rich(text(BERK, "B4", 4_000, "four"), true),
                // Same time as A3 and the same id as a message of the other chat.
                starred_rich(text(BERK, "A3", 3_000, "three too"), true),
                starred_rich(text(BERK, "B2", 2_000, "two"), true),
            ],
            0,
        )
        .unwrap();
    store
        .store_page(
            GROUP,
            None,
            50,
            &[starred_rich(text(GROUP, "G6", 6_000, "six"), true)],
            0,
        )
        .unwrap();

    let all = store.starred(50, None).unwrap();
    assert_eq!(
        starred_ids(&all),
        [
            (GROUP, "G6"),
            (ALI, "A5"),
            (BERK, "B4"),
            (BERK, "A3"),
            (ALI, "A3"),
            (BERK, "B2"),
            (ALI, "A1"),
        ]
    );
    assert_eq!(all.next, None);
    assert!(all.items.iter().all(|row| row.starred));
    assert!(all.items[6].from_me);

    // Pages follow the cursor without gaps or repeats, also across messages of one time.
    let mut seen = Vec::new();
    let mut before: Option<StarredCursor> = None;
    let mut pages = 0;
    loop {
        let page = store.starred(3, before.as_ref()).unwrap();
        assert!(page.items.len() <= 3);
        seen.extend(
            page.items
                .iter()
                .map(|row| (row.chat.clone(), row.id.clone())),
        );
        pages += 1;
        match page.next {
            Some(next) => before = Some(next),
            None => break,
        }
    }
    assert_eq!(pages, 3);
    let expected: Vec<_> = all
        .items
        .iter()
        .map(|row| (row.chat.clone(), row.id.clone()))
        .collect();
    assert_eq!(seen, expected);
    // A full last page names no next one.
    let first = store.starred(4, None).unwrap();
    assert_eq!(
        first.next,
        Some(StarredCursor {
            timestamp_ms: 3_000,
            chat: BERK.into(),
            id: "A3".into()
        })
    );
    let rest = store.starred(3, first.next.as_ref()).unwrap();
    assert_eq!(rest.items.len(), 3);
    assert_eq!(rest.next, None);

    // Left out: a deleted message, the messages cleared on another device, a chat deleted there.
    store
        .apply_events(&[
            env(
                1,
                GatewayEvent::MessageDeleted {
                    chat: GROUP.into(),
                    id: "G6".into(),
                    deleted_at: Some(7_000),
                    deleted_by: None,
                },
            ),
            env(2, GatewayEvent::ChatCleared { jid: BERK.into() }),
        ])
        .unwrap();
    assert_eq!(
        starred_ids(&store.starred(50, None).unwrap()),
        [(ALI, "A5"), (ALI, "A3"), (ALI, "A1")]
    );
    store
        .apply_events(&[env(3, GatewayEvent::ChatDeleted { jid: ALI.into() })])
        .unwrap();
    assert!(store.starred(50, None).unwrap().items.is_empty());
}

#[test]
fn a_media_send_queues_with_its_file_and_keeps_it_as_the_cached_media() {
    let mut store = store();
    let photo = Action::SendMedia {
        file: "/files/outbox/p.jpg".into(),
        media: MediaKind::Image,
        mime: "image/jpeg".into(),
        size: 4_096,
        caption: Some("look".into()),
        quoted_id: Some("Q".into()),
        file_name: None,
        seconds: None,
        voice_note: false,
    };
    store.outbox_enqueue("c1", ALI, &photo, 1_000).unwrap();
    let row = store.thread(ALI, 10, None).unwrap().items.remove(0);
    assert_eq!((row.kind, row.text.as_deref()), (Kind::Image, Some("look")));
    assert_eq!(row.media_path.as_deref(), Some("/files/outbox/p.jpg"));
    assert_eq!(store.outbox_edit("c1", "").unwrap().action, {
        let mut no_caption = photo.clone();
        if let Action::SendMedia { caption, .. } = &mut no_caption {
            *caption = None;
        }
        no_caption
    });
    store.outbox_due(2_000, false).unwrap();
    store
        .outbox_settle("c1", &sent("S1", 2_100), 2_100)
        .unwrap();
    let row = store.message(ALI, "S1").unwrap().unwrap();
    assert_eq!(row.kind, Kind::Image);
    assert_eq!(row.quoted.unwrap().id, "Q");
    assert_eq!(row.media_path.as_deref(), Some("/files/outbox/p.jpg"));
    assert_eq!(store.media_bytes().unwrap(), 4_096);
}

#[test]
fn the_outbox_survives_closing_the_store() {
    let dir = temp_dir("outbox");
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        store
            .outbox_enqueue("c1", ALI, &send("kept across a kill"), 1_000)
            .unwrap();
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(ids(&store.thread(ALI, 10, None).unwrap()), ["c1"]);
    assert_eq!(
        store.outbox_due(2_000, true).unwrap().items[0].action,
        send("kept across a kill")
    );
    let _ = std::fs::remove_dir_all(dir);
}

// ---- schema

fn tables(conn: &Connection) -> Vec<String> {
    let mut stmt = conn
        .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .unwrap();
    stmt.query_map([], |row| row.get(0))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
}

#[test]
fn a_new_file_gets_the_whole_schema() {
    let store = store();
    assert_eq!(store.opened().unwrap().schema_version, SCHEMA_VERSION);
    assert_eq!(
        tables(&store.conn),
        ["chat_sync", "chats", "messages", "outbox", "sync_state"]
    );
    assert_eq!(
        store.opened().unwrap(),
        Opened {
            instance_id: "inst1".into(),
            schema_version: SCHEMA_VERSION,
            last_seq: None,
            needs_resync: true
        }
    );
}

#[test]
fn a_version_one_file_upgrades_and_keeps_its_data() {
    let dir = temp_dir("migrate");
    let path = dir.join(file_name("inst1"));
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate_to(&mut conn, 1).unwrap();
        assert_eq!(schema::version(&conn).unwrap(), 1);
        conn.execute_batch(
            "INSERT INTO sync_state (id, instance_id, last_seq, needs_resync) VALUES (1, 'inst1', 41, 0);
             INSERT INTO chats (jid, name, unread) VALUES ('100000000001@s.whatsapp.net', 'Ali', 2);
             INSERT INTO chat_sync (jid, newest_ts, newest_id, oldest_ts) VALUES
                ('100000000001@s.whatsapp.net', 1000, 'A', 1000);
             INSERT INTO messages (chat, id, from_me, ts, kind, text, deleted, deleted_at, deleted_by) VALUES
                ('100000000001@s.whatsapp.net', 'A', 0, 1000, 'text', 'from version one', 1, 1500, 'me');",
        )
        .unwrap();
        assert_eq!(
            tables(&conn),
            ["chat_sync", "chats", "messages", "sync_state"]
        );
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    let opened = store.opened().unwrap();
    assert_eq!(
        (opened.schema_version, opened.last_seq, opened.needs_resync),
        (SCHEMA_VERSION, Some(41), false)
    );
    let row = store.message(ALI, "A").unwrap().unwrap();
    assert_eq!(row.text.as_deref(), Some("from version one"));
    assert!(row.deleted && row.client_id.is_none());
    assert_eq!(store.chats(false).unwrap()[0].name.as_deref(), Some("Ali"));
    // What version two added works on the upgraded file.
    store
        .outbox_enqueue("c1", ALI, &send("new"), 2_000)
        .unwrap();
    store.outbox_due(2_000, false).unwrap();
    store
        .outbox_settle("c1", &sent("S1", 2_100), 2_100)
        .unwrap();
    assert_eq!(
        store
            .message(ALI, "S1")
            .unwrap()
            .unwrap()
            .client_id
            .as_deref(),
        Some("c1")
    );
    // And what version three added: the index the Starred list reads.
    let index: String = store
        .conn
        .query_row(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'messages_starred'",
            [],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(index, "messages_starred");
    store
        .outbox_enqueue("s1", ALI, &star("S1", true), 2_200)
        .unwrap();
    assert_eq!(
        starred_ids(&store.starred(10, None).unwrap()),
        [(ALI, "S1")]
    );
    drop(store);
    // Opening again migrates nothing.
    assert_eq!(
        Store::open(&dir, "inst1")
            .unwrap()
            .opened()
            .unwrap()
            .schema_version,
        SCHEMA_VERSION
    );
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_file_from_a_newer_build_is_refused_untouched() {
    let dir = temp_dir("newer");
    let path = dir.join(file_name("inst1"));
    {
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("CREATE TABLE future (x); PRAGMA user_version = 99;")
            .unwrap();
    }
    assert!(matches!(
        Store::open(&dir, "inst1"),
        Err(Error::NewerSchema {
            found: 99,
            supported: SCHEMA_VERSION
        })
    ));
    let conn = Connection::open(&path).unwrap();
    assert_eq!(tables(&conn), ["future"]);
    let _ = std::fs::remove_dir_all(dir);
}

// ---- instance separation

#[test]
fn another_instance_has_its_own_file_and_never_sees_the_first_ones_data() {
    let dir = temp_dir("instances");
    {
        let mut first = Store::open(&dir, "inst1").unwrap();
        first
            .apply_events(&[env(
                7,
                GatewayEvent::Message(text(ALI, "A", 1_000, "first account")),
            )])
            .unwrap();
        first
            .outbox_enqueue("c1", ALI, &send("queued on the first"), 2_000)
            .unwrap();
    }
    // Re-linked to another instance: an empty store with its own cursor.
    let mut second = Store::open(&dir, "inst2").unwrap();
    assert!(second.chats(true).unwrap().is_empty());
    assert!(second.outbox().unwrap().is_empty());
    assert_eq!(second.plan(None).unwrap().after_seq, None);
    second
        .apply_events(&[env(
            1,
            GatewayEvent::Message(text(BERK, "B", 5_000, "second account")),
        )])
        .unwrap();
    drop(second);

    // Linking the first one again finds its data as it was.
    let first = Store::open(&dir, "inst1").unwrap();
    let chats: Vec<_> = first
        .chats(true)
        .unwrap()
        .into_iter()
        .map(|c| c.jid)
        .collect();
    assert_eq!(chats, [ALI]);
    assert_eq!(first.plan(None).unwrap().after_seq, Some(7));
    assert_eq!(first.outbox().unwrap().len(), 1);

    // A file opened under the wrong instance is refused.
    assert!(matches!(
        Store::open_file(&dir.join(file_name("inst1")), "inst2"),
        Err(Error::InstanceMismatch { .. })
    ));
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn instance_ids_map_to_distinct_safe_file_names() {
    assert_eq!(file_name("inst_1-A"), "wa-sync-inst_1-A.db");
    assert_eq!(file_name("../x y"), "wa-sync-~2e~2e~2fx~20y.db");
    assert_ne!(file_name("a/b"), file_name("a_b"));
    assert!(matches!(Store::open_in_memory(" "), Err(Error::Invalid(_))));
}

// ---- media LRU

#[test]
fn eviction_drops_the_least_recently_used_files_and_keeps_messages_and_thumbnails() {
    let mut store = store();
    let events: Vec<_> = (1..=4u64)
        .map(|n| {
            let photo = WaMessage {
                body: MessageBody::Media {
                    kind: MediaKind::Image,
                    caption: None,
                    mime: "image/jpeg".into(),
                    size: 1_000,
                },
                ..text(ALI, &format!("P{n}"), n as i64 * 1_000, "")
            };
            env(n, GatewayEvent::Message(photo))
        })
        .collect();
    store.apply_events(&events).unwrap();
    for n in 1..=4 {
        let id = format!("P{n}");
        store.set_thumbnail(ALI, &id, id.as_bytes()).unwrap();
        store
            .set_media_file(ALI, &id, &format!("/media/{id}.jpg"), 1_000, n * 10)
            .unwrap();
    }
    assert!(matches!(
        store.set_media_file(ALI, "NOPE", "/x", 1, 1),
        Err(Error::NotFound(_))
    ));
    assert_eq!(store.media_bytes().unwrap(), 4_000);
    // Opening P1 makes it the most recently used.
    assert_eq!(
        store
            .media(ALI, "P1", Some(100))
            .unwrap()
            .unwrap()
            .path
            .as_deref(),
        Some("/media/P1.jpg")
    );
    assert_eq!(
        store.media_evict(4_000).unwrap().evicted,
        [],
        "under the cap"
    );

    let eviction = store.media_evict(2_500).unwrap();
    let gone: Vec<_> = eviction
        .evicted
        .iter()
        .map(|file| file.path.as_str())
        .collect();
    assert_eq!(gone, ["/media/P2.jpg", "/media/P3.jpg"]);
    assert_eq!(eviction.bytes_left, 2_000);
    assert_eq!(store.media_bytes().unwrap(), 2_000);

    // The evicted messages read the same and keep their thumbnails; only the file is gone.
    assert_eq!(
        ids(&store.thread(ALI, 10, None).unwrap()),
        ["P4", "P3", "P2", "P1"]
    );
    let evicted = store.message(ALI, "P2").unwrap().unwrap();
    assert!(evicted.has_thumbnail && evicted.media_path.is_none());
    assert_eq!(evicted.kind, Kind::Image);
    let media = store.media(ALI, "P2", None).unwrap().unwrap();
    assert_eq!(
        (media.thumbnail.as_deref(), media.path),
        (Some(&b"P2"[..]), None)
    );
    assert!(
        store
            .message(ALI, "P1")
            .unwrap()
            .unwrap()
            .media_path
            .is_some()
    );
    assert_eq!(store.media_evict(0).unwrap().bytes_left, 0);
    assert_eq!(store.media(ALI, "NOPE", None).unwrap(), None);
}
