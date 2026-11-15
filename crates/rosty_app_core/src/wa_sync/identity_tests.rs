//! Identity in the store: schema v4, the persisted map, the backfill, the merge and resolve-before-match.

use std::path::PathBuf;

use rosty_core::wa_identity::{AliasKind, RejectReason, Source, WaIdKind};
use rosty_wa_proto::event::ReceiptKind;
use rosty_wa_proto::{
    ChatMeta, ChatSummary, DeliveryStatus, EditRecord, EventEnvelope, GatewayEvent, MUTED_FOREVER,
    MessageBody, MessageMeta, Quoted, Reaction, RichChat, RichMessage, WaMessage,
};
use rusqlite::Connection;

use super::*;
use super::{backfill, identity, merge, schema};

/// One person: a LID and the number behind it.
pub(super) const LID: &str = "777000000000001@lid";
pub(super) const PN: &str = "905550000001@s.whatsapp.net";
/// Another person.
pub(super) const LID2: &str = "777000000000002@lid";
pub(super) const PN2: &str = "905550000002@s.whatsapp.net";
/// A third, who never shows a number.
pub(super) const LID3: &str = "777000000000003@lid";
pub(super) const OWN_PN: &str = "905550000009@s.whatsapp.net";
pub(super) const OWN_LID: &str = "777000000000009@lid";
pub(super) const GROUP: &str = "120000000000000001@g.us";

pub(super) fn store() -> Store {
    Store::open_in_memory("inst1").unwrap()
}

pub(super) fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rosty-wa-identity-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

pub(super) fn text(chat: &str, id: &str, ts: i64, body: &str) -> WaMessage {
    by(chat, chat, id, ts, body)
}

/// A message of `sender` in `chat` (a group author).
pub(super) fn by(chat: &str, sender: &str, id: &str, ts: i64, body: &str) -> WaMessage {
    WaMessage {
        id: id.into(),
        chat: chat.into(),
        sender: Some(sender.into()),
        from_me: false,
        timestamp_ms: ts,
        body: MessageBody::Text(body.into()),
        reactions: Vec::new(),
        chat_alt: None,
        sender_alt: None,
    }
}

pub(super) fn mine(chat: &str, id: &str, ts: i64, body: &str) -> WaMessage {
    WaMessage {
        sender: None,
        from_me: true,
        ..text(chat, id, ts, body)
    }
}

pub(super) fn reaction(sender: &str, emoji: &str) -> Reaction {
    Reaction {
        sender: Some(sender.into()),
        from_me: false,
        emoji: emoji.into(),
        sender_alt: None,
    }
}

pub(super) fn env(seq: u64, event: GatewayEvent) -> EventEnvelope {
    EventEnvelope {
        seq,
        at_ms: 1_000_000 + seq as i64,
        event,
    }
}

/// Applies `events` with sequence numbers after everything applied so far.
pub(super) fn apply(store: &mut Store, events: Vec<GatewayEvent>) -> AppliedEvents {
    let base = store.sync_state().unwrap().last_seq.unwrap_or(0);
    let envelopes: Vec<_> = events
        .into_iter()
        .enumerate()
        .map(|(index, event)| env(base + 1 + index as u64, event))
        .collect();
    store.apply_events(&envelopes).unwrap()
}

pub(super) fn message(event: WaMessage) -> GatewayEvent {
    GatewayEvent::Message(event)
}

pub(super) fn meta_event(chat: &str, id: &str, meta: MessageMeta) -> GatewayEvent {
    GatewayEvent::MessageMeta {
        chat: chat.into(),
        id: id.into(),
        body: None,
        meta: Box::new(meta),
    }
}

pub(super) fn listed(jid: &str, last: Option<WaMessage>, unread: u32) -> RichChat {
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

pub(super) fn thread_ids(store: &Store, chat: &str) -> Vec<String> {
    store
        .thread(chat, 100, None)
        .unwrap()
        .items
        .into_iter()
        .map(|row| row.id)
        .collect()
}

pub(super) fn chat_row(store: &Store, jid: &str) -> Option<ChatRow> {
    store
        .chats(true)
        .unwrap()
        .into_iter()
        .find(|chat| chat.jid == jid)
}

pub(super) fn count(conn: &Connection, sql: &str) -> i64 {
    conn.query_row(sql, [], |row| row.get(0)).unwrap()
}

/// How many messages are stored under `key` itself. A read under an id of the chat follows the map, so it does
/// not tell where the rows are.
pub(super) fn stored_under(conn: &Connection, key: &str) -> i64 {
    conn.query_row(
        "SELECT COUNT(*) FROM messages WHERE chat = ?1",
        [key],
        |row| row.get(0),
    )
    .unwrap()
}

/// How many stored values still name `key`, the kept wire ids aside.
pub(super) fn references(conn: &Connection, key: &str) -> i64 {
    conn.query_row(
        "SELECT (SELECT COUNT(*) FROM chats WHERE jid = ?1)
            + (SELECT COUNT(*) FROM chat_sync WHERE jid = ?1)
            + (SELECT COUNT(*) FROM outbox WHERE chat = ?1 OR instr(payload, ?1) > 0)
            + (SELECT COUNT(*) FROM messages WHERE chat = ?1 OR sender = ?1 OR deleted_by = ?1
                OR instr(reactions, ?1) > 0 OR instr(COALESCE(quoted_json, ''), ?1) > 0
                OR instr(COALESCE(extra_json, ''), ?1) > 0)",
        [key],
        |row| row.get(0),
    )
    .unwrap()
}

/// Every row of every table, for "nothing changed" checks.
pub(super) fn dump(conn: &Connection) -> Vec<String> {
    let mut out = Vec::new();
    for table in [
        "sync_state",
        "chats",
        "chat_sync",
        "messages",
        "outbox",
        "person",
        "alias",
        "merge_log",
    ] {
        let mut stmt = conn
            .prepare(&format!("SELECT * FROM {table} ORDER BY 1, 2"))
            .unwrap();
        let columns = stmt.column_count();
        let mut rows = stmt.query([]).unwrap();
        while let Some(row) = rows.next().unwrap() {
            let cells: Vec<String> = (0..columns)
                .map(|at| format!("{:?}", row.get_ref(at).unwrap()))
                .collect();
            out.push(format!("{table}: {}", cells.join(" | ")));
        }
    }
    out
}

/// The tables hold exactly what the map in memory holds.
pub(super) fn assert_tables_match_map(store: &Store) {
    let loaded = identity::load(&store.conn).unwrap();
    assert_eq!(loaded.rows(), store.identity().rows());
}

pub(super) fn key_of(store: &Store, text: &str) -> String {
    store
        .identity()
        .lookup(text)
        .unwrap()
        .key
        .as_str()
        .to_owned()
}

pub(super) fn tables(conn: &Connection) -> Vec<String> {
    conn.prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
    )
    .unwrap()
    .query_map([], |row| row.get(0))
    .unwrap()
    .collect::<rusqlite::Result<_>>()
    .unwrap()
}

pub(super) fn columns(conn: &Connection, table: &str) -> Vec<String> {
    conn.prepare(&format!("SELECT name FROM pragma_table_info('{table}')"))
        .unwrap()
        .query_map([], |row| row.get(0))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
}

/// A file at schema `version` (1 to 3) holding a LID chat and the number chat of the same person, a group with
/// LID authors, and from version 2 on an outbox row.
pub(super) fn old_file(dir: &std::path::Path, version: u32) -> PathBuf {
    let path = dir.join(file_name("inst1"));
    let mut conn = Connection::open(&path).unwrap();
    schema::migrate_to(&mut conn, version).unwrap();
    assert_eq!(schema::version(&conn).unwrap(), version);
    conn.execute_batch(&format!(
        "INSERT INTO sync_state (id, instance_id, last_seq, needs_resync) VALUES (1, 'inst1', 41, 0);
         INSERT INTO chats (jid, name, unread, pinned) VALUES ('{LID}', NULL, 2, 1);
         INSERT INTO chats (jid, name, unread) VALUES ('{PN}', 'Ali', 1);
         INSERT INTO chats (jid, name, is_group) VALUES ('{GROUP}', 'Team', 1);
         INSERT INTO chat_sync (jid, newest_ts, newest_id, oldest_ts) VALUES ('{LID}', 2000, 'L2', 1000);
         INSERT INTO chat_sync (jid, newest_ts, newest_id, oldest_ts, reached_start) VALUES
            ('{PN}', 3000, 'P1', 3000, 1);
         INSERT INTO messages (chat, id, sender, from_me, ts, kind, text, starred) VALUES
            ('{LID}', 'L1', '{LID}', 0, 1000, 'text', 'from the lid chat', 1),
            ('{LID}', 'L2', '{LID}', 0, 2000, 'text', 'again', 0),
            ('{PN}', 'P1', '{PN}', 0, 3000, 'text', 'from the number chat', 0);
         INSERT INTO messages (chat, id, sender, from_me, ts, kind, text, reactions, quoted_json, extra_json,
                deleted, deleted_at, deleted_by) VALUES
            ('{GROUP}', 'G1', '{LID}', 0, 4000, 'text', 'in the group',
             '[{{\"sender\":\"{LID2}\",\"from_me\":false,\"emoji\":\"a\"}}]',
             '{{\"id\":\"G0\",\"sender\":\"{LID3}\",\"preview\":\"hi\"}}',
             '{{\"mentions\":[\"{PN2}\",\"777000000000004:7@lid\"]}}', 1, 4500, '777000000000005@lid');"
    ))
    .unwrap();
    if version >= 2 {
        conn.execute_batch(&format!(
            "INSERT INTO outbox (client_id, chat, kind, payload, created_ms) VALUES
                ('c1', '{LID}', 'send_text', '{{\"kind\":\"send_text\",\"text\":\"queued\"}}', 5000);"
        ))
        .unwrap();
    }
    path
}

// ---- schema v4

#[test]
fn a_new_file_has_the_identity_tables_and_columns() {
    let store = store();
    assert_eq!(store.opened().unwrap().schema_version, SCHEMA_VERSION);
    assert_eq!(
        tables(&store.conn),
        [
            "alias",
            "chat_sync",
            "chats",
            "media",
            "merge_log",
            "messages",
            "outbox",
            "person",
            "sync_state"
        ]
    );
    assert_eq!(columns(&store.conn, "person"), ["person_id", "key"]);
    assert_eq!(
        columns(&store.conn, "alias"),
        ["kind", "user", "person_id", "state", "source", "learned_ms"]
    );
    assert_eq!(
        columns(&store.conn, "merge_log"),
        ["seq", "at_ms", "from_key", "into_key", "reason"]
    );
    for (table, added) in [
        (
            "sync_state",
            &[
                "own_pn",
                "own_lid",
                "merge_cursor",
                "identity_backfill",
                "identity_cursor",
            ][..],
        ),
        ("chats", &["wire_jid"][..]),
        ("messages", &["wire_sender"][..]),
    ] {
        let found = columns(&store.conn, table);
        for column in added {
            assert!(found.iter().any(|name| name == column), "{table}.{column}");
        }
    }
    // An empty store has nothing to backfill and nothing to merge.
    assert_eq!(
        count(
            &store.conn,
            "SELECT merge_cursor FROM sync_state WHERE identity_backfill = 'done'"
        ),
        0
    );
    assert!(store.identity().is_empty());
}

#[test]
fn files_of_every_older_version_upgrade_and_keep_their_data() {
    for version in 1..=3 {
        let dir = temp_dir(&format!("upgrade-v{version}"));
        old_file(&dir, version);
        let store = Store::open(&dir, "inst1").unwrap();
        let opened = store.opened().unwrap();
        assert_eq!(
            (opened.schema_version, opened.last_seq, opened.needs_resync),
            (SCHEMA_VERSION, Some(41), false),
            "v{version}"
        );
        // The migration moved nothing: both chats of the person are still there, as they were.
        let stored: Vec<String> = thread_ids(&store, LID)
            .into_iter()
            .filter(|id| id != "c1")
            .collect();
        assert_eq!(stored, ["L2", "L1"]);
        assert_eq!(thread_ids(&store, PN), ["P1"]);
        let lid_chat = chat_row(&store, LID).unwrap();
        assert_eq!((lid_chat.unread, lid_chat.pinned), (2, true));
        assert_eq!(chat_row(&store, PN).unwrap().name.as_deref(), Some("Ali"));
        assert!(store.message(LID, "L1").unwrap().unwrap().starred);
        assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM merge_log"), 0);
        drop(store);
        // Opening again migrates and backfills nothing.
        let before = dump(&Connection::open(dir.join(file_name("inst1"))).unwrap());
        let again = Store::open(&dir, "inst1").unwrap();
        assert_eq!(again.opened().unwrap().schema_version, SCHEMA_VERSION);
        assert_eq!(dump(&again.conn), before, "v{version}");
        let _ = std::fs::remove_dir_all(dir);
    }
}

#[test]
fn an_upgraded_store_with_a_lid_chat_and_a_number_chat_becomes_one_chat_once_the_pair_is_learned() {
    let dir = temp_dir("upgrade-merge");
    old_file(&dir, 3);
    let mut store = Store::open(&dir, "inst1").unwrap();
    let applied = store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();
    assert_eq!(
        applied.rekeyed,
        [ChatRekeyed {
            seq: 1,
            from_key: LID.into(),
            into_key: PN.into()
        }]
    );
    assert!(applied.resync && applied.rejected.is_empty());
    // One chat with every message, the queued send first.
    assert_eq!(thread_ids(&store, PN), ["c1", "P1", "L2", "L1"]);
    assert_eq!(stored_under(&store.conn, LID), 0);
    // A reader that still holds the LID reads the merged chat.
    assert_eq!(thread_ids(&store, LID), thread_ids(&store, PN));
    assert!(chat_row(&store, LID).is_none());
    let chat = chat_row(&store, PN).unwrap();
    assert_eq!((chat.unread, chat.pinned), (3, true));
    assert_eq!(chat.name.as_deref(), Some("Ali"));
    assert_eq!(chat.wire_jid.as_deref(), Some(LID));
    assert!(store.message(PN, "L1").unwrap().unwrap().starred);
    // The group author too, with the id it arrived under.
    let authored = store.message(GROUP, "G1").unwrap().unwrap();
    assert_eq!(authored.sender.as_deref(), Some(PN));
    assert_eq!(authored.wire_sender.as_deref(), Some(LID));
    assert_eq!(references(&store.conn, LID), 0);
    assert_tables_match_map(&store);
    let _ = std::fs::remove_dir_all(dir);
}

// ---- the map on disk

#[test]
fn the_map_is_read_back_when_the_store_opens() {
    let dir = temp_dir("persist");
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        store
            .identity_set_own(Some(OWN_PN), Some(OWN_LID), 1)
            .unwrap();
        store.identity_learn(LID, PN, Source::Usync, 2_000).unwrap();
        store.identity_resolve(LID3).unwrap();
        assert_tables_match_map(&store);
    }
    let store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(key_of(&store, LID), PN);
    assert_eq!(key_of(&store, "777000000000001:4@lid"), PN);
    assert_eq!(key_of(&store, LID3), LID3);
    assert!(store.identity().is_me(OWN_LID) && store.identity().is_me(OWN_PN));
    let person = store.identity().lookup(PN).unwrap().person_id.unwrap();
    let aliases = store.identity().aliases(person);
    assert_eq!(
        aliases
            .iter()
            .map(|row| (row.kind, row.source, row.learned_ms))
            .collect::<Vec<_>>(),
        [
            (AliasKind::Pn, Source::Usync, 2_000),
            (AliasKind::Lid, Source::Usync, 2_000)
        ]
    );
    assert_tables_match_map(&store);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_merged_person_leaves_one_person_row() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "a")),
            message(text(PN, "P1", 2_000, "b")),
        ],
    );
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM person"), 2);
    store
        .identity_learn(LID, PN, Source::PeerMessage, 3_000)
        .unwrap();
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM person"), 1);
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM alias"), 2);
    assert_eq!(
        store
            .conn
            .query_row("SELECT key FROM person", [], |row| row.get::<_, String>(0))
            .unwrap(),
        PN
    );
    assert_eq!(
        store
            .conn
            .query_row(
                "SELECT from_key || '>' || into_key || ':' || reason || '@' || at_ms FROM merge_log",
                [],
                |row| row.get::<_, String>(0)
            )
            .unwrap(),
        format!("{LID}>{PN}:mapped@3000")
    );
    assert_tables_match_map(&store);
}

#[test]
fn a_failed_write_puts_the_map_back_in_step_with_the_tables() {
    let mut store = store();
    store.identity_resolve(PN).unwrap();
    // The map moves ahead of the tables, as it does inside a transaction that then fails.
    let _unsaved = store.identity.learn(LID, PN, Source::Usync, 1);
    assert_eq!(key_of(&store, LID), PN);
    let failed: Result<()> = store.healed(Err(Error::Invalid("the write failed".into())));
    assert!(failed.is_err());
    assert_eq!(key_of(&store, LID), LID);
    assert_tables_match_map(&store);
}

#[test]
fn resolve_answers_the_key_the_kind_and_the_wire_id() {
    let mut store = store();
    store.identity_learn(LID, PN, Source::Usync, 1).unwrap();
    let lid = store
        .identity_resolve("777000000000001:7@lid")
        .unwrap()
        .unwrap();
    assert_eq!(
        (lid.key.as_str(), lid.kind, lid.wire.as_str()),
        (PN, WaIdKind::Lid, LID)
    );
    assert!(lid.person_id.is_some());
    let group = store.identity_resolve(GROUP).unwrap().unwrap();
    assert_eq!(
        (
            group.key.as_str(),
            group.kind,
            group.wire.as_str(),
            group.person_id
        ),
        (GROUP, WaIdKind::Group, GROUP, None)
    );
    // An id never seen becomes a person of its own, in the tables too.
    let unseen = store.identity_resolve(LID3).unwrap().unwrap();
    assert_eq!((unseen.key.as_str(), unseen.kind), (LID3, WaIdKind::Lid));
    assert_tables_match_map(&store);
    assert_eq!(store.identity_resolve("not an id").unwrap(), None);
    // The same digits under the other server are another person.
    let digits = store
        .identity_resolve("777000000000001@s.whatsapp.net")
        .unwrap()
        .unwrap();
    assert_eq!(digits.key, "777000000000001@s.whatsapp.net");
    assert_ne!(digits.person_id, lid.person_id);
}

#[test]
fn a_pair_that_changes_nothing_is_reported() {
    let mut store = store();
    store
        .identity_set_own(Some(OWN_PN), Some(OWN_LID), 1)
        .unwrap();
    let applied = store
        .identity_learn(OWN_LID, PN, Source::PeerMessage, 2)
        .unwrap();
    assert!(applied.rekeyed.is_empty());
    assert_eq!(applied.rejected.len(), 1);
    assert_eq!(applied.rejected[0].reason, RejectReason::OwnId);
    let invalid = store.identity_learn(PN, LID, Source::Usync, 3).unwrap();
    assert_eq!(invalid.rejected[0].reason, RejectReason::Invalid);
    // A weaker source does not move a LID to another number.
    store
        .identity_learn(LID, PN, Source::PeerMessage, 10)
        .unwrap();
    let weaker = store.identity_learn(LID, PN2, Source::History, 20).unwrap();
    assert_eq!(weaker.rejected[0].reason, RejectReason::Outranked);
    assert_eq!(key_of(&store, LID), PN);
    assert_tables_match_map(&store);
}

// ---- backfill

#[test]
fn the_backfill_makes_every_stored_id_known_to_the_map() {
    let dir = temp_dir("backfill");
    old_file(&dir, 3);
    let store = Store::open(&dir, "inst1").unwrap();
    for id in [
        LID,
        PN,
        LID2,
        LID3,
        PN2,
        "777000000000004@lid",
        "777000000000005@lid",
    ] {
        assert!(
            store.identity().lookup(id).unwrap().person_id.is_some(),
            "{id}"
        );
    }
    // A group is never a person.
    assert_eq!(store.identity().lookup(GROUP).unwrap().person_id, None);
    assert_eq!(store.identity().len(), 7);
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM sync_state WHERE identity_backfill = 'done'"
        ),
        1
    );
    // The mention stored with a device reads without it; nothing else changed.
    let authored = store.message(GROUP, "G1").unwrap().unwrap();
    assert_eq!(authored.mentions, [PN2, "777000000000004@lid"]);
    assert_eq!(authored.sender.as_deref(), Some(LID));
    assert_eq!(authored.wire_sender, None);
    assert_tables_match_map(&store);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_killed_backfill_resumes_and_running_it_again_changes_nothing() {
    let dir = temp_dir("backfill-resume");
    let path = old_file(&dir, 3);
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate(&mut conn).unwrap();
        let mut map = identity::load(&conn).unwrap();
        // The chats, then two messages of four, then the process dies.
        assert!(!backfill::step(&mut conn, &mut map, 2).unwrap());
        assert!(!backfill::step(&mut conn, &mut map, 2).unwrap());
        let state: String = conn
            .query_row("SELECT identity_backfill FROM sync_state", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(state, format!(r#"["{LID}","L1"]"#));
        assert!(map.lookup(LID3).unwrap().person_id.is_some());
        assert!(map.lookup(PN).unwrap().person_id.is_some());
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(store.identity().len(), 7);
    assert_tables_match_map(&store);
    // Again from the start: every id is known, so nothing is written but the marker.
    let before = dump(&store.conn);
    store
        .conn
        .execute("UPDATE sync_state SET identity_backfill = NULL", [])
        .unwrap();
    backfill::run(&mut store.conn, &mut store.identity).unwrap();
    assert_eq!(dump(&store.conn), before);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_chat_stored_under_an_id_that_is_not_its_key_is_moved_by_the_backfill() {
    let dir = temp_dir("backfill-rekey");
    let path = dir.join(file_name("inst1"));
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate_to(&mut conn, 3).unwrap();
        conn.execute_batch(
            "INSERT INTO sync_state (id, instance_id) VALUES (1, 'inst1');
             INSERT INTO chats (jid, unread) VALUES ('905550000001:3@s.whatsapp.net', 4);
             INSERT INTO messages (chat, id, sender, from_me, ts, kind, text) VALUES
                ('905550000001:3@s.whatsapp.net', 'D1', '905550000001:3@s.whatsapp.net', 0, 1000, 'text', 'x');",
        )
        .unwrap();
    }
    let store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(thread_ids(&store, PN), ["D1"]);
    assert_eq!(chat_row(&store, PN).unwrap().unread, 4);
    assert_eq!(
        store.message(PN, "D1").unwrap().unwrap().sender.as_deref(),
        Some(PN)
    );
    assert_eq!(
        store.rekeyed_since(0).unwrap(),
        [ChatRekeyed {
            seq: 1,
            from_key: "905550000001:3@s.whatsapp.net".into(),
            into_key: PN.into()
        }]
    );
    let _ = std::fs::remove_dir_all(dir);
}

// ---- merge: messages

/// A LID chat and the number chat of one person, with nothing learned yet.
fn two_chats() -> Store {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "lid one")),
            message(text(LID, "L2", 2_000, "lid two")),
            message(text(PN, "P1", 1_500, "number one")),
            message(mine(PN, "P2", 3_000, "my answer")),
            message(text(PN, "P3", 4_000, "number three")),
        ],
    );
    store
}

#[test]
fn disjoint_messages_all_move_and_the_unread_counts_add_up() {
    let mut store = two_chats();
    store.store_chats(&[], 1, None, None).unwrap();
    let unread_before = count(&store.conn, "SELECT SUM(unread) FROM chats");
    assert_eq!(unread_before, 3);
    let applied = store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(
        (
            applied.rekeyed[0].from_key.as_str(),
            applied.rekeyed[0].into_key.as_str()
        ),
        (LID, PN)
    );
    assert!(applied.resync);
    assert!(store.plan(None).unwrap().refresh_chats);
    assert_eq!(thread_ids(&store, PN), ["P3", "P2", "L2", "P1", "L1"]);
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 5);
    assert_eq!(
        count(&store.conn, "SELECT SUM(unread) FROM chats"),
        unread_before
    );
    assert_eq!(store.chats(true).unwrap().len(), 1);
    assert_eq!(references(&store.conn, LID), 0);
    // The sender of the moved messages is the person's key, and the id they arrived under is kept.
    let moved = store.message(PN, "L1").unwrap().unwrap();
    assert_eq!(moved.sender.as_deref(), Some(PN));
    assert_eq!(moved.wire_sender.as_deref(), Some(LID));
    assert_eq!(store.message(PN, "P1").unwrap().unwrap().wire_sender, None);
}

#[test]
fn a_message_stored_under_both_keys_is_kept_once_with_what_either_row_knew() {
    let mut store = store();
    let history = vec![EditRecord {
        text: "first".into(),
        edited_at: 1_500,
    }];
    apply(
        &mut store,
        vec![
            // Under the LID: starred, edited, a reaction of the group friend, read.
            message(mine(LID, "SAME", 1_000, "second")),
            meta_event(
                LID,
                "SAME",
                MessageMeta {
                    starred: true,
                    edited_ms: Some(1_500),
                    edit_history: history.clone(),
                    status: Some(DeliveryStatus::Read),
                    ..MessageMeta::default()
                },
            ),
            GatewayEvent::MessageUpdate {
                chat: LID.into(),
                id: "SAME".into(),
                reactions: vec![reaction(LID, "old"), reaction(LID2, "kept")],
                read: false,
            },
            // Under the number: delivered only, the person's newer reaction.
            message(mine(PN, "SAME", 1_000, "second")),
            meta_event(
                PN,
                "SAME",
                MessageMeta {
                    status: Some(DeliveryStatus::Delivered),
                    ..MessageMeta::default()
                },
            ),
            GatewayEvent::MessageUpdate {
                chat: PN.into(),
                id: "SAME".into(),
                reactions: vec![reaction(PN, "new")],
                read: false,
            },
            message(text(LID, "ONLY", 2_000, "only under the lid")),
        ],
    );
    // What only the device knows, split over the two rows.
    store.set_thumbnail(LID, "SAME", b"thumb").unwrap();
    store
        .set_media_file(LID, "SAME", "/cache/same.jpg", 512, 7_000)
        .unwrap();
    store
        .conn
        .execute(
            "UPDATE messages SET client_id = 'c9' WHERE chat = ?1 AND id = 'SAME'",
            [PN],
        )
        .unwrap();

    store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();

    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM messages WHERE id = 'SAME'"
        ),
        1
    );
    assert_eq!(thread_ids(&store, PN), ["ONLY", "SAME"]);
    let row = store.message(PN, "SAME").unwrap().unwrap();
    assert!(row.starred);
    assert_eq!(row.state, RowState::Read);
    assert_eq!(row.edit_history, history);
    assert_eq!(row.edited_ms, Some(1_500));
    assert_eq!(row.client_id.as_deref(), Some("c9"));
    assert_eq!(row.media_path.as_deref(), Some("/cache/same.jpg"));
    assert!(row.has_thumbnail);
    // One reaction per person: the number row's for the merged person, the friend's from the LID row.
    assert_eq!(row.reactions, [reaction(LID2, "kept"), reaction(PN, "new")]);
    let media = store.media(PN, "SAME", None).unwrap().unwrap();
    assert_eq!(
        (media.thumbnail.as_deref(), media.media.bytes),
        (Some(&b"thumb"[..]), Some(512))
    );
    assert_eq!(store.media_bytes().unwrap(), 512);
    assert_eq!(references(&store.conn, LID), 0);
}

#[test]
fn a_lid_chat_without_a_number_chat_is_renamed() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "hello")),
            GatewayEvent::ChatMeta {
                jid: LID.into(),
                meta: ChatMeta {
                    pinned: true,
                    push_name: Some("ali".into()),
                    ..ChatMeta::default()
                },
            },
        ],
    );
    store
        .outbox_enqueue("c1", LID, &send("queued"), 2_000)
        .unwrap();
    let applied = store.identity_learn(LID, PN, Source::Usync, 3_000).unwrap();
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(thread_ids(&store, PN), ["c1", "L1"]);
    let chat = chat_row(&store, PN).unwrap();
    assert!(chat.pinned);
    assert_eq!(chat.unread, 1);
    assert_eq!(chat.push_name.as_deref(), Some("ali"));
    assert_eq!(chat.wire_jid.as_deref(), Some(LID));
    assert_eq!(store.cursor(PN).unwrap().newest_id.as_deref(), Some("L1"));
    assert_eq!(store.chats(true).unwrap().len(), 1);
    assert_eq!(references(&store.conn, LID), 0);
}

fn send(text: &str) -> Action {
    Action::SendText {
        text: text.into(),
        quoted_id: None,
    }
}

#[test]
fn a_step_applied_twice_changes_nothing_the_second_time() {
    let mut store = two_chats();
    store
        .outbox_enqueue("c1", LID, &send("queued"), 5_000)
        .unwrap();
    store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();
    // The refresh the merge asked for is done; a replay of the step must not ask again.
    store.store_chats(&[], 10_000, None, None).unwrap();
    let before = dump(&store.conn);
    assert!(!merge::apply_step(&store.conn, LID, PN).unwrap());
    assert_eq!(dump(&store.conn), before);
    // Nor does learning the pair again.
    let again = store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();
    assert_eq!(again, IdentityApplied::default());
    assert_eq!(dump(&store.conn), before);
    assert!(store.apply_pending_merges().unwrap().is_empty());
}

#[test]
fn steps_logged_before_a_kill_are_replayed_from_the_cursor_when_the_store_opens() {
    let dir = temp_dir("replay");
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        apply(
            &mut store,
            vec![
                message(text(LID, "L1", 1_000, "one")),
                message(text(PN, "P1", 2_000, "two")),
                message(text(LID2, "M1", 3_000, "three")),
                message(text(PN2, "N1", 4_000, "four")),
            ],
        );
        // The first pair is learned and applied.
        store.identity_learn(LID, PN, Source::Usync, 5_000).unwrap();
        // The second is written with its step, and the process dies before the rows follow.
        let changes = store.identity.learn(LID2, PN2, Source::Usync, 6_000);
        identity::persist(&store.conn, &changes, 6_000).unwrap();
        assert_eq!(count(&store.conn, "SELECT merge_cursor FROM sync_state"), 1);
        assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM merge_log"), 2);
        assert_eq!(stored_under(&store.conn, LID2), 1);
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(count(&store.conn, "SELECT merge_cursor FROM sync_state"), 2);
    assert_eq!(thread_ids(&store, PN2), ["N1", "M1"]);
    assert_eq!(thread_ids(&store, PN), ["P1", "L1"]);
    assert_eq!(stored_under(&store.conn, LID2), 0);
    assert_eq!(references(&store.conn, LID2), 0);
    // Whoever keeps drafts and prefs per chat replays the log from its own cursor.
    let since: Vec<_> = store
        .rekeyed_since(1)
        .unwrap()
        .into_iter()
        .map(|step| (step.seq, step.from_key, step.into_key))
        .collect();
    assert_eq!(since, [(2, LID2.to_owned(), PN2.to_owned())]);
    assert_eq!(store.rekeyed_since(0).unwrap().len(), 2);
    assert!(store.apply_pending_merges().unwrap().is_empty());
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn a_step_left_over_is_applied_before_the_next_events_are_matched() {
    let mut store = two_chats();
    let changes = store.identity.learn(LID, PN, Source::Usync, 6_000);
    identity::persist(&store.conn, &changes, 6_000).unwrap();
    let applied = apply(&mut store, vec![message(text(LID, "L3", 5_000, "late"))]);
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(applied.chats, [PN]);
    assert_eq!(thread_ids(&store, PN), ["L3", "P3", "P2", "L2", "P1", "L1"]);
}

// ---- merge: reactions and ticks

#[test]
fn one_person_who_reacted_under_both_forms_has_one_reaction_and_a_removal_under_either_form_removes_it()
 {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(by(GROUP, PN2, "G1", 1_000, "a group message")),
            GatewayEvent::MessageUpdate {
                chat: GROUP.into(),
                id: "G1".into(),
                reactions: vec![
                    reaction(PN, "first"),
                    reaction(LID3, "other"),
                    reaction(LID, "newest"),
                ],
                read: false,
            },
        ],
    );
    assert_eq!(
        store.message(GROUP, "G1").unwrap().unwrap().reactions.len(),
        3
    );
    store
        .identity_learn(LID, PN, Source::GroupMetadata, 2_000)
        .unwrap();
    assert_eq!(
        store.message(GROUP, "G1").unwrap().unwrap().reactions,
        [reaction(LID3, "other"), reaction(PN, "newest")]
    );
    // The gateway still names the person by the LID: the reaction replaces, it does not add.
    apply(
        &mut store,
        vec![GatewayEvent::MessageUpdate {
            chat: GROUP.into(),
            id: "G1".into(),
            reactions: vec![reaction(LID3, "other"), reaction(LID, "changed")],
            read: false,
        }],
    );
    assert_eq!(
        store.message(GROUP, "G1").unwrap().unwrap().reactions,
        [reaction(LID3, "other"), reaction(PN, "changed")]
    );
    // Removed under the other form: gone.
    apply(
        &mut store,
        vec![GatewayEvent::MessageUpdate {
            chat: GROUP.into(),
            id: "G1".into(),
            reactions: vec![reaction(LID3, "other")],
            read: false,
        }],
    );
    assert_eq!(
        store.message(GROUP, "G1").unwrap().unwrap().reactions,
        [reaction(LID3, "other")]
    );
    assert_eq!(references(&store.conn, LID), 0);
}

#[test]
fn delivered_under_the_lid_and_read_under_the_number_is_one_message_read() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(mine(LID, "S1", 1_000, "sent to the lid chat")),
            GatewayEvent::Receipt {
                chat: LID.into(),
                ids: vec!["S1".into()],
                kind: ReceiptKind::Delivered,
            },
        ],
    );
    assert_eq!(
        store.message(LID, "S1").unwrap().unwrap().state,
        RowState::Delivered
    );
    store
        .identity_learn(LID, PN, Source::Receipt, 2_000)
        .unwrap();
    let applied = apply(
        &mut store,
        vec![GatewayEvent::Receipt {
            chat: "905550000001:2@s.whatsapp.net".into(),
            ids: vec!["S1".into()],
            kind: ReceiptKind::Read,
        }],
    );
    assert_eq!(applied.chats, [PN]);
    assert_eq!(
        store.message(PN, "S1").unwrap().unwrap().state,
        RowState::Read
    );
    // A late delivery receipt under the old form never takes the tick back.
    apply(
        &mut store,
        vec![GatewayEvent::Receipt {
            chat: LID.into(),
            ids: vec!["S1".into()],
            kind: ReceiptKind::Delivered,
        }],
    );
    assert_eq!(
        store.message(PN, "S1").unwrap().unwrap().state,
        RowState::Read
    );
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 1);
}

// ---- merge: authors in other chats

#[test]
fn authors_quotes_mentions_and_deleters_are_renamed_in_every_chat() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 500, "the lid chat")),
            message(text(PN, "P1", 600, "the number chat")),
            message(by(GROUP, LID, "G1", 1_000, "written under the lid")),
            message(by(GROUP, PN2, "G2", 2_000, "a reply that mentions")),
            meta_event(
                GROUP,
                "G2",
                MessageMeta {
                    quoted: Some(Quoted {
                        id: "G1".into(),
                        sender: Some(LID.into()),
                        preview: Some("written under the lid".into()),
                    }),
                    mentions: vec![LID.into(), PN.into(), LID3.into()],
                    ..MessageMeta::default()
                },
            ),
            message(by(GROUP, PN2, "G3", 3_000, "deleted by the admin")),
            GatewayEvent::MessageDeleted {
                chat: GROUP.into(),
                id: "G3".into(),
                deleted_at: Some(3_500),
                deleted_by: Some(LID.into()),
            },
        ],
    );
    assert!(references(&store.conn, LID) >= 6);
    store
        .identity_learn(LID, PN, Source::GroupMetadata, 5_000)
        .unwrap();

    let g1 = store.message(GROUP, "G1").unwrap().unwrap();
    assert_eq!(g1.sender.as_deref(), Some(PN));
    assert_eq!(g1.wire_sender.as_deref(), Some(LID));
    let g2 = store.message(GROUP, "G2").unwrap().unwrap();
    assert_eq!(g2.sender.as_deref(), Some(PN2));
    assert_eq!(g2.quoted.unwrap().sender.as_deref(), Some(PN));
    assert_eq!(g2.mentions, [PN, LID3]);
    let g3 = store.message(GROUP, "G3").unwrap().unwrap();
    assert!(g3.deleted);
    assert_eq!(g3.deleted_by.as_deref(), Some(PN));
    assert_eq!(references(&store.conn, LID), 0);
    // The group itself never moves.
    assert_eq!(thread_ids(&store, GROUP), ["G3", "G2", "G1"]);

    // A message that arrives later under the LID is stored under the key, with the LID as its wire id.
    apply(
        &mut store,
        vec![
            message(by(
                GROUP,
                "777000000000001:5@lid",
                "G4",
                6_000,
                "after the merge",
            )),
            meta_event(
                GROUP,
                "G4",
                MessageMeta {
                    quoted: Some(Quoted {
                        id: "G1".into(),
                        sender: Some(LID.into()),
                        preview: None,
                    }),
                    mentions: vec![LID.into()],
                    ..MessageMeta::default()
                },
            ),
        ],
    );
    let g4 = store.message(GROUP, "G4").unwrap().unwrap();
    assert_eq!(g4.sender.as_deref(), Some(PN));
    assert_eq!(g4.wire_sender.as_deref(), Some(LID));
    assert_eq!(g4.quoted.unwrap().sender.as_deref(), Some(PN));
    assert_eq!(g4.mentions, [PN]);
    assert_eq!(references(&store.conn, LID), 0);
}

// ---- merge: the chat row, the cursor, the outbox

#[test]
fn chat_settings_are_combined_as_the_rule_says() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "a")),
            message(text(LID, "L2", 2_000, "b")),
            message(text(PN, "P1", 3_000, "c")),
            GatewayEvent::ChatMeta {
                jid: LID.into(),
                meta: ChatMeta {
                    picture_id: Some("lid-picture".into()),
                    push_name: Some("lid name".into()),
                    archived: true,
                    pinned: false,
                    muted_until_ms: Some(50_000),
                    marked_unread: true,
                },
            },
            GatewayEvent::ChatMeta {
                jid: PN.into(),
                meta: ChatMeta {
                    picture_id: None,
                    push_name: Some("number name".into()),
                    archived: false,
                    pinned: true,
                    muted_until_ms: Some(MUTED_FOREVER),
                    marked_unread: false,
                },
            },
            // Cleared on another device while it was still the LID chat.
            GatewayEvent::ChatCleared { jid: LID.into() },
            message(text(LID, "L3", 4_000, "after the clear")),
            GatewayEvent::ChatDeleted { jid: PN.into() },
        ],
    );
    let unread = count(&store.conn, "SELECT SUM(unread) FROM chats");
    assert_eq!(unread, 1);
    store.identity_learn(LID, PN, Source::Usync, 9_000).unwrap();
    let chat = chat_row(&store, PN).expect("hidden only when both were");
    assert_eq!(chat.unread as i64, unread);
    assert!(chat.pinned && chat.archived && chat.marked_unread);
    assert_eq!(chat.muted_until_ms, Some(MUTED_FOREVER));
    assert_eq!(chat.push_name.as_deref(), Some("number name"));
    assert_eq!(chat.picture_id.as_deref(), Some("lid-picture"));
    // Cleared up to the later time: what was cleared stays out of the thread, and stays stored.
    assert_eq!(thread_ids(&store, PN), ["L3", "P1"]);
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 4);
    assert_eq!(count(&store.conn, "SELECT cleared_ts FROM chats"), 2_000);
}

#[test]
fn both_chats_hidden_stay_hidden() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "a")),
            message(text(PN, "P1", 2_000, "b")),
            GatewayEvent::ChatDeleted { jid: LID.into() },
            GatewayEvent::ChatDeleted { jid: PN.into() },
        ],
    );
    store.identity_learn(LID, PN, Source::Usync, 9_000).unwrap();
    assert!(store.chats(true).unwrap().is_empty());
    assert_eq!(count(&store.conn, "SELECT hidden FROM chats"), 1);
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 2);
}

#[test]
fn cursors_widen_a_gap_stays_open_and_the_chat_list_is_refreshed() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "a")),
            message(text(LID, "L2", 5_000, "b")),
            message(text(PN, "P1", 2_000, "c")),
            message(text(PN, "P2", 3_000, "d")),
        ],
    );
    // The number chat reached its start; the LID chat missed messages and is being paged.
    store.store_page(PN, Some(2_000), 50, &[], 6_000).unwrap();
    let fetch = store
        .store_chats(
            &[listed(LID, Some(text(LID, "L9", 9_000, "missed")), 0)],
            6_500,
            None,
            None,
        )
        .unwrap()
        .fetch;
    assert_eq!(fetch.len(), 1);
    assert!(store.cursor(PN).unwrap().reached_start);
    assert!(store.cursor(LID).unwrap().gap.is_some());
    assert!(!store.plan(None).unwrap().refresh_chats);

    store.identity_learn(LID, PN, Source::Usync, 7_000).unwrap();
    let cursor = store.cursor(PN).unwrap();
    assert_eq!(
        (
            cursor.newest_ts,
            cursor.newest_id.as_deref(),
            cursor.oldest_ts
        ),
        (Some(9_000), Some("L9"), Some(1_000))
    );
    // The start was reached in one of them only.
    assert!(!cursor.reached_start);
    let gap = cursor.gap.expect("the open gap stays open");
    assert_eq!((gap.floor_ts, gap.before), (5_000, None));
    assert_eq!(
        count(
            &store.conn,
            &format!("SELECT COUNT(*) FROM chat_sync WHERE jid = '{LID}'")
        ),
        0
    );
    // Asked under the LID, the cursor is the merged chat's.
    assert_eq!(store.cursor(LID).unwrap(), store.cursor(PN).unwrap());
    let plan = store.plan(None).unwrap();
    assert!(plan.refresh_chats);
    assert_eq!(
        plan.fetch,
        [FetchPage {
            chat: PN.into(),
            before: None,
            limit: 50
        }]
    );
    assert!(store.thread(PN, 10, None).unwrap().syncing);
}

#[test]
fn outbox_rows_follow_in_the_order_written() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(LID, "L1", 1_000, "a")),
            message(text(PN, "P1", 2_000, "b")),
            message(by(GROUP, LID, "G1", 3_000, "c")),
        ],
    );
    store
        .outbox_enqueue("c1", LID, &send("first"), 4_000)
        .unwrap();
    store
        .outbox_enqueue("c2", PN, &send("second"), 4_100)
        .unwrap();
    store
        .outbox_enqueue("c3", LID, &send("third"), 4_200)
        .unwrap();
    store
        .outbox_enqueue(
            "r1",
            GROUP,
            &Action::React {
                message_id: "G1".into(),
                from_me: false,
                sender: Some(LID.into()),
                emoji: "x".into(),
            },
            4_300,
        )
        .unwrap();
    store.identity_learn(LID, PN, Source::Usync, 5_000).unwrap();
    let rows: Vec<_> = store
        .outbox()
        .unwrap()
        .into_iter()
        .map(|item| (item.client_id, item.chat))
        .collect();
    assert_eq!(
        rows,
        [
            ("c1".to_owned(), PN.to_owned()),
            ("c2".to_owned(), PN.to_owned()),
            ("c3".to_owned(), PN.to_owned()),
            ("r1".to_owned(), GROUP.to_owned())
        ]
    );
    assert_eq!(thread_ids(&store, PN), ["c3", "c2", "c1", "P1", "L1"]);
    assert_eq!(chat_row(&store, PN).unwrap().waiting, 3);
    // Only the head of the one chat is due, as for any chat.
    let due: Vec<_> = store
        .outbox_due(6_000, false)
        .unwrap()
        .items
        .into_iter()
        .map(|item| item.client_id)
        .collect();
    assert_eq!(due, ["c1", "r1"]);
    // The author a waiting reaction names follows too.
    let react = store
        .outbox()
        .unwrap()
        .into_iter()
        .find(|item| item.client_id == "r1")
        .unwrap();
    assert!(matches!(react.action, Action::React { sender: Some(sender), .. } if sender == PN));
    assert_eq!(references(&store.conn, LID), 0);
}

// ---- late events

#[test]
fn events_addressed_to_the_old_key_after_the_merge_land_on_the_merged_chat() {
    let mut store = two_chats();
    apply(
        &mut store,
        vec![message(mine(LID, "S1", 4_500, "sent under the lid"))],
    );
    store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();
    store.store_chats(&[], 9_500, None, None).unwrap();

    let applied = apply(
        &mut store,
        vec![
            // A reaction.
            GatewayEvent::MessageUpdate {
                chat: LID.into(),
                id: "P1".into(),
                reactions: vec![reaction(LID, "late")],
                read: false,
            },
            // A receipt.
            GatewayEvent::Receipt {
                chat: LID.into(),
                ids: vec!["S1".into()],
                kind: ReceiptKind::Read,
            },
            // An edit.
            GatewayEvent::MessageMeta {
                chat: LID.into(),
                id: "L1".into(),
                body: Some(MessageBody::Text("lid one, edited".into())),
                meta: Box::new(MessageMeta {
                    edited_ms: Some(9_600),
                    ..MessageMeta::default()
                }),
            },
            // A delete for everyone.
            meta_event(
                LID,
                "L2",
                MessageMeta {
                    revoked: true,
                    ..MessageMeta::default()
                },
            ),
            // A delete on my devices, a new message, the settings and the name.
            GatewayEvent::MessageDeleted {
                chat: LID.into(),
                id: "P3".into(),
                deleted_at: None,
                deleted_by: None,
            },
            message(text("777000000000001:9@lid", "L9", 9_700, "a new one")),
            GatewayEvent::ChatMeta {
                jid: LID.into(),
                meta: ChatMeta {
                    pinned: true,
                    ..ChatMeta::default()
                },
            },
            GatewayEvent::Profile(rosty_wa_proto::WaProfile {
                jid: LID.into(),
                push_name: Some("Ali".into()),
                ..Default::default()
            }),
        ],
    );
    assert_eq!(applied.chats, [PN]);
    assert_eq!(
        store.message(PN, "P1").unwrap().unwrap().reactions,
        [reaction(PN, "late")]
    );
    assert_eq!(
        store.message(PN, "S1").unwrap().unwrap().state,
        RowState::Read
    );
    let edited = store.message(PN, "L1").unwrap().unwrap();
    assert_eq!(edited.text.as_deref(), Some("lid one, edited"));
    assert_eq!(edited.edit_history[0].text, "lid one");
    let revoked = store.message(PN, "L2").unwrap().unwrap();
    assert!(revoked.deleted);
    assert_eq!(revoked.text.as_deref(), Some("lid two"));
    assert!(store.message(PN, "P3").unwrap().unwrap().deleted);
    let chat = chat_row(&store, PN).unwrap();
    assert!(chat.pinned);
    assert_eq!(chat.push_name.as_deref(), Some("Ali"));
    assert_eq!(chat.wire_jid.as_deref(), Some(LID));
    assert_eq!(chat.last.unwrap().id, "L9");
    assert_eq!(store.chats(true).unwrap().len(), 1);
    assert_eq!(references(&store.conn, LID), 0);

    // Typing and presence are not stored: whoever shows them asks where the id lives now.
    let typing = store
        .identity_resolve("777000000000001:3@lid")
        .unwrap()
        .unwrap();
    assert_eq!((typing.key.as_str(), typing.wire.as_str()), (PN, LID));
    // A send written on a thread screen that still holds the old key queues on the merged chat.
    let queued = store
        .outbox_enqueue("c7", LID, &send("typed late"), 9_800)
        .unwrap();
    assert_eq!(queued.chat, PN);
    // A page of the chat asked under the old key is stored under the new one.
    let page = store
        .store_page(
            LID,
            None,
            50,
            &[RichMessage::from(text(LID, "L0", 100, "older history"))],
            9_900,
        )
        .unwrap();
    assert_eq!((page.stored, page.new), (1, 1));
    assert_eq!(
        thread_ids(&store, PN).last().map(String::as_str),
        Some("L0")
    );
    assert_eq!(references(&store.conn, LID), 0);
}

// ---- resolve before match

#[test]
fn an_id_never_seen_becomes_a_person_so_a_later_pair_finds_its_rows() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(by(GROUP, LID, "G1", 1_000, "a")),
            GatewayEvent::MessageUpdate {
                chat: GROUP.into(),
                id: "G1".into(),
                reactions: vec![reaction(LID2, "x")],
                read: false,
            },
        ],
    );
    for id in [LID, LID2] {
        assert!(store.identity().lookup(id).unwrap().person_id.is_some());
    }
    assert_eq!(store.identity().len(), 2);
    assert_tables_match_map(&store);
    // Neither has a chat of their own, yet the pair renames what they wrote.
    let applied = store
        .identity_learn(LID2, PN2, Source::GroupMetadata, 2_000)
        .unwrap();
    assert_eq!(applied.rekeyed.len(), 1);
    assert!(!applied.resync);
    assert_eq!(
        store.message(GROUP, "G1").unwrap().unwrap().reactions,
        [reaction(PN2, "x")]
    );
    assert!(
        store
            .chats(true)
            .unwrap()
            .iter()
            .all(|chat| chat.jid == GROUP)
    );
}

#[test]
fn a_known_lid_is_stored_under_the_number_with_the_id_it_arrived_under() {
    let mut store = store();
    store.identity_learn(LID, PN, Source::Usync, 1).unwrap();
    let applied = apply(
        &mut store,
        vec![
            message(WaMessage {
                reactions: vec![reaction(LID, "own")],
                ..text(
                    "777000000000001:2@lid",
                    "L1",
                    1_000,
                    "arrives under the lid",
                )
            }),
            message(text(PN, "P1", 2_000, "arrives under the number")),
        ],
    );
    assert_eq!(applied.chats, [PN]);
    assert_eq!(thread_ids(&store, PN), ["P1", "L1"]);
    assert!(chat_row(&store, LID).is_none());
    let chat = chat_row(&store, PN).unwrap();
    assert_eq!((chat.unread, chat.wire_jid.as_deref()), (2, Some(LID)));
    let row = store.message(PN, "L1").unwrap().unwrap();
    assert_eq!(row.sender.as_deref(), Some(PN));
    assert_eq!(row.wire_sender.as_deref(), Some(LID));
    assert_eq!(row.reactions, [reaction(PN, "own")]);
    assert_eq!(store.message(PN, "P1").unwrap().unwrap().wire_sender, None);
    // The same message again under the number keeps the id it first arrived under.
    apply(
        &mut store,
        vec![message(text(PN, "L1", 1_000, "arrives under the lid"))],
    );
    assert_eq!(
        store
            .message(PN, "L1")
            .unwrap()
            .unwrap()
            .wire_sender
            .as_deref(),
        Some(LID)
    );
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 2);
    assert_eq!(references(&store.conn, LID), 0);
}

#[test]
fn a_chat_list_that_names_one_person_twice_is_one_chat() {
    let mut store = store();
    store.identity_learn(LID, PN, Source::Usync, 1).unwrap();
    let mut by_lid = listed(LID, Some(text(LID, "L1", 2_000, "newest")), 2);
    by_lid.meta.pinned = true;
    by_lid.meta.muted_until_ms = Some(70_000);
    let mut by_number = listed(PN, Some(text(PN, "P1", 1_000, "older")), 1);
    by_number.chat.name = Some("Ali".into());
    by_number.meta.archived = true;
    let stored = store
        .store_chats(
            &[by_lid, by_number, listed(GROUP, None, 0)],
            5_000,
            None,
            None,
        )
        .unwrap();
    assert_eq!(stored.chats, 3);
    let chats = store.chats(true).unwrap();
    assert_eq!(chats.len(), 2);
    let chat = chat_row(&store, PN).unwrap();
    assert_eq!(chat.unread, 3);
    assert!(chat.pinned && chat.archived);
    assert_eq!(chat.muted_until_ms, Some(70_000));
    assert_eq!(chat.name.as_deref(), Some("Ali"));
    assert_eq!(chat.wire_jid.as_deref(), Some(LID));
    assert_eq!(chat.last.unwrap().id, "L1");
    assert_eq!(thread_ids(&store, PN), ["L1"]);
    assert_eq!(references(&store.conn, LID), 0);
}

// ---- the merge an older gateway announces

#[test]
fn a_merge_announced_by_the_gateway_teaches_the_pair() {
    let mut store = two_chats();
    store.store_chats(&[], 1, None, None).unwrap();
    let applied = apply(
        &mut store,
        vec![
            GatewayEvent::ChatMerged {
                from: LID.into(),
                into: PN.into(),
            },
            // Later in the same batch, still under the LID.
            message(text(LID, "L3", 5_000, "after the merge")),
        ],
    );
    assert!(applied.resync);
    assert_eq!(applied.chats, [LID, PN]);
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(
        (
            applied.rekeyed[0].from_key.as_str(),
            applied.rekeyed[0].into_key.as_str()
        ),
        (LID, PN)
    );
    assert_eq!(key_of(&store, LID), PN);
    assert_eq!(thread_ids(&store, PN), ["L3", "P3", "P2", "L2", "P1", "L1"]);
    assert_eq!(chat_row(&store, PN).unwrap().unread, 4);
    assert_tables_match_map(&store);
    // The same announcement again is nothing new.
    let again = apply(
        &mut store,
        vec![GatewayEvent::ChatMerged {
            from: LID.into(),
            into: PN.into(),
        }],
    );
    assert!(again.rekeyed.is_empty());
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM merge_log"), 1);
}

#[test]
fn a_merge_announced_for_any_other_pair_moves_the_rows_and_teaches_nothing() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(PN, "A1", 1_000, "the old number")),
            message(text(PN2, "B1", 2_000, "the new number")),
        ],
    );
    let applied = apply(
        &mut store,
        vec![GatewayEvent::ChatMerged {
            from: PN.into(),
            into: PN2.into(),
        }],
    );
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(thread_ids(&store, PN2), ["B1", "A1"]);
    assert_eq!(chat_row(&store, PN2).unwrap().unread, 2);
    assert!(chat_row(&store, PN).is_none());
    // Two numbers are two persons until a number change says otherwise.
    assert_eq!(key_of(&store, PN), PN);
    assert_eq!(
        store
            .conn
            .query_row("SELECT reason FROM merge_log", [], |row| row
                .get::<_, String>(0))
            .unwrap(),
        "chat_merged"
    );
}

// ---- number change

#[test]
fn a_number_change_joins_the_chats_of_the_old_and_the_new_number() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(text(PN, "A1", 1_000, "from the old number")),
            message(text(PN2, "B1", 2_000, "from the new number")),
            message(by(GROUP, PN, "G1", 3_000, "in a group, old number")),
        ],
    );
    let applied = store
        .identity_number_changed(PN, PN2, Some(LID), None, 5_000)
        .unwrap();
    assert!(!applied.rekeyed.is_empty());
    assert_eq!(thread_ids(&store, PN2), ["B1", "A1"]);
    assert_eq!(chat_row(&store, PN2).unwrap().unread, 2);
    assert_eq!(
        store
            .message(GROUP, "G1")
            .unwrap()
            .unwrap()
            .sender
            .as_deref(),
        Some(PN2)
    );
    assert_eq!(references(&store.conn, PN), 0);
    // The old number and the LID still resolve to the person; a message under either lands in the one chat.
    assert_eq!(key_of(&store, PN), PN2);
    assert_eq!(key_of(&store, LID), PN2);
    apply(
        &mut store,
        vec![message(text(PN, "A2", 6_000, "a late one"))],
    );
    assert_eq!(thread_ids(&store, PN2), ["A2", "B1", "A1"]);
    let person = store.identity().lookup(PN2).unwrap().person_id.unwrap();
    assert_eq!(
        store.identity().numbers(person),
        ["+905550000002", "+905550000001"]
    );
    assert_eq!(
        store
            .conn
            .query_row("SELECT DISTINCT reason FROM merge_log", [], |row| row
                .get::<_, String>(0))
            .unwrap(),
        "number_change"
    );
    assert_tables_match_map(&store);
}

// ---- the account's own ids and the account scope

#[test]
fn my_number_and_my_lid_are_one_person_and_one_self_chat() {
    let mut store = store();
    apply(
        &mut store,
        vec![
            message(mine(OWN_LID, "N1", 1_000, "a note to myself")),
            message(mine(OWN_PN, "N2", 2_000, "another")),
            message(by(GROUP, OWN_LID, "G1", 3_000, "me, from my other phone")),
        ],
    );
    let applied = store
        .identity_set_own(Some("905550000009:4@s.whatsapp.net"), Some(OWN_LID), 5_000)
        .unwrap();
    assert!(!applied.account_changed);
    assert_eq!(applied.rekeyed.len(), 1);
    assert_eq!(thread_ids(&store, OWN_PN), ["N2", "N1"]);
    assert_eq!(
        store
            .message(GROUP, "G1")
            .unwrap()
            .unwrap()
            .sender
            .as_deref(),
        Some(OWN_PN)
    );
    assert!(store.identity().is_me("777000000000009:2@lid"));
    assert_eq!(
        store
            .conn
            .query_row(
                "SELECT own_pn || ' ' || own_lid FROM sync_state",
                [],
                |row| row.get::<_, String>(0)
            )
            .unwrap(),
        format!("{OWN_PN} {OWN_LID}")
    );
    // Told again, or told less (a status without the LID): nothing changes.
    let before = dump(&store.conn);
    assert_eq!(
        store.identity_set_own(Some(OWN_PN), None, 6_000).unwrap(),
        IdentityApplied::default()
    );
    assert_eq!(
        store.identity_set_own(None, None, 7_000).unwrap(),
        IdentityApplied::default()
    );
    assert_eq!(dump(&store.conn), before);
    assert_tables_match_map(&store);
}

#[test]
fn the_same_account_linked_again_keeps_the_identity_tables() {
    let dir = temp_dir("same-account");
    {
        let mut store = Store::open(&dir, "inst1").unwrap();
        store
            .identity_set_own(Some(OWN_PN), Some(OWN_LID), 1)
            .unwrap();
        store.identity_learn(LID, PN, Source::Usync, 2).unwrap();
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    let applied = store
        .identity_set_own(Some(OWN_PN), Some(OWN_LID), 3)
        .unwrap();
    assert!(!applied.account_changed);
    assert_eq!(key_of(&store, LID), PN);
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM alias"), 4);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn another_account_on_the_same_instance_starts_the_identity_tables_over() {
    let mut store = two_chats();
    store
        .identity_set_own(Some(OWN_PN), Some(OWN_LID), 1)
        .unwrap();
    store
        .identity_learn(LID, PN, Source::PeerMessage, 2)
        .unwrap();
    store.identity_learn(LID2, PN2, Source::Usync, 3).unwrap();
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM merge_log"), 1);
    assert_eq!(count(&store.conn, "SELECT merge_cursor FROM sync_state"), 1);

    let other = "905550000008@s.whatsapp.net";
    let applied = store.identity_set_own(Some(other), None, 10).unwrap();
    assert!(applied.account_changed);
    // What the old account's map knew is gone: its own ids, its pairs, its log.
    assert!(!store.identity().is_me(OWN_PN) && !store.identity().is_me(OWN_LID));
    assert!(store.identity().is_me(other));
    assert_eq!(key_of(&store, LID), LID);
    assert_eq!(key_of(&store, LID2), LID2);
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM merge_log"), 0);
    assert_eq!(
        store
            .conn
            .query_row(
                "SELECT own_pn || ' ' || COALESCE(own_lid, '-') FROM sync_state",
                [],
                |row| row.get::<_, String>(0)
            )
            .unwrap(),
        format!("{other} -")
    );
    // The rows of the old account are gone with its map (`link_state_tests` covers the whole wipe).
    assert_eq!(count(&store.conn, "SELECT COUNT(*) FROM messages"), 0);
    assert!(store.identity().lookup(PN).unwrap().person_id.is_none());
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM sync_state WHERE identity_backfill = 'done'"
        ),
        1
    );
    assert_tables_match_map(&store);
    // Sequence numbers go on: a consumer's cursor never sees one twice.
    apply(
        &mut store,
        vec![message(text(LID, "X1", 9_000, "new account"))],
    );
    let step = store
        .identity_learn(LID, PN, Source::Usync, 11)
        .unwrap()
        .rekeyed;
    assert_eq!(step.len(), 1);
    assert_eq!(step[0].seq, 2);
    assert_eq!(count(&store.conn, "SELECT merge_cursor FROM sync_state"), 2);
}

// ---- the invariants, on one larger store

#[test]
fn a_merge_loses_no_message_star_or_reaction_and_leaves_no_trace_of_the_old_key() {
    let mut store = store();
    let mut events = Vec::new();
    for n in 0..30_i64 {
        let chat = if n % 2 == 0 { LID } else { PN };
        // Every fifth message is stored in both chats.
        let id = format!("M{n}");
        events.push(message(WaMessage {
            reactions: vec![reaction(if n % 3 == 0 { LID } else { PN2 }, "r")],
            ..text(chat, &id, 1_000 + n, "x")
        }));
        if n % 5 == 0 {
            events.push(message(text(
                if chat == LID { PN } else { LID },
                &id,
                1_000 + n,
                "x",
            )));
        }
        if n % 4 == 0 {
            events.push(meta_event(
                chat,
                &id,
                MessageMeta {
                    starred: true,
                    ..MessageMeta::default()
                },
            ));
        }
        events.push(message(by(GROUP, LID, &format!("G{n}"), 5_000 + n, "g")));
    }
    apply(&mut store, events);
    let distinct = count(&store.conn, "SELECT COUNT(DISTINCT id) FROM messages");
    let starred = count(
        &store.conn,
        "SELECT COUNT(DISTINCT id) FROM messages WHERE starred = 1",
    );
    let reacted = count(
        &store.conn,
        "SELECT COUNT(DISTINCT id) FROM messages WHERE reactions != '[]'",
    );
    let unread = count(&store.conn, "SELECT SUM(unread) FROM chats");
    assert_eq!((distinct, starred, reacted), (60, 8, 30));

    store
        .identity_learn(LID, PN, Source::PeerMessage, 9_000)
        .unwrap();

    assert_eq!(
        count(&store.conn, "SELECT COUNT(*) FROM messages"),
        distinct
    );
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM messages WHERE starred = 1"
        ),
        starred
    );
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM messages WHERE reactions != '[]'"
        ),
        reacted
    );
    assert_eq!(count(&store.conn, "SELECT SUM(unread) FROM chats"), unread);
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM (SELECT chat, id FROM messages GROUP BY chat, id HAVING COUNT(*) > 1)"
        ),
        0
    );
    assert_eq!(references(&store.conn, LID), 0);
    assert_eq!(
        count(
            &store.conn,
            "SELECT COUNT(*) FROM messages WHERE wire_sender = '777000000000001@lid'"
        ),
        48
    );
    assert_tables_match_map(&store);
}
