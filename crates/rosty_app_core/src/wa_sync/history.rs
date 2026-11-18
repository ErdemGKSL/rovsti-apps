//! The history snapshot (`plans/wa-vault.md` 15, round 7a): the recent chats and messages of a store in memory,
//! kept between unlocks in the vault's history file (sealed under DK, `wa_vault::HISTORY_FILE`), so the inbox and
//! the threads show at once after an unlock instead of an empty screen and a refetch.
//!
//! What is kept, at most: the [`HISTORY_CHATS`] chats listed first (pinned, then the newest message), each with its
//! [`HISTORY_MESSAGES`] newest messages, thumbnails up to [`HISTORY_THUMB_BYTES`] in all (newest first; a message
//! over the budget keeps no thumbnail) and the rest of the rows up to [`HISTORY_ROW_BYTES`]. Never the outbox,
//! never a media file (full media stays fetch-on-tap), never a hidden chat or a message a clear left out.
//!
//! The snapshot is rows of the store's own tables, so seeding is an insert of what was there: chats, their
//! `chat_sync` cursor (newest and oldest message of what is kept; an open gap stays open and starts over from the
//! top, as after a refreshed chat list) and messages. Seeding changes nothing the sync owes: a store in memory still
//! refreshes its chat list at the cold start, the chat on screen still gets its one page, and a chat whose newest
//! message moved on gets a gap from the kept newest message up. So no request is added; the rows only show early.

use std::collections::BTreeMap;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use rusqlite::Connection;
use rusqlite::types::Value as SqlValue;
use serde::{Deserialize, Serialize};

use super::schema::SCHEMA_VERSION;
use super::{Result, Store, backfill};

/// Chats kept.
pub const HISTORY_CHATS: usize = 40;
/// Newest messages kept per chat.
pub const HISTORY_MESSAGES: usize = 30;
/// Thumbnails kept, in bytes, over all chats.
pub const HISTORY_THUMB_BYTES: usize = 2 * 1024 * 1024;
/// Every other column, in bytes (text, quotes, reactions, edit history, JSON), over all chats.
pub const HISTORY_ROW_BYTES: usize = 4 * 1024 * 1024;

/// One stored value.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Cell {
    Null,
    Int(i64),
    Real(f64),
    Text(String),
    /// A BLOB (the thumbnail), base64.
    Blob {
        blob: String,
    },
}

impl Cell {
    fn from_sql(value: SqlValue) -> Self {
        match value {
            SqlValue::Null => Cell::Null,
            SqlValue::Integer(i) => Cell::Int(i),
            SqlValue::Real(r) => Cell::Real(r),
            SqlValue::Text(t) => Cell::Text(t),
            SqlValue::Blob(b) => Cell::Blob {
                blob: STANDARD.encode(b),
            },
        }
    }

    fn to_sql(&self) -> SqlValue {
        match self {
            Cell::Null => SqlValue::Null,
            Cell::Int(i) => SqlValue::Integer(*i),
            Cell::Real(r) => SqlValue::Real(*r),
            Cell::Text(t) => SqlValue::Text(t.clone()),
            Cell::Blob { blob } => STANDARD
                .decode(blob)
                .map(SqlValue::Blob)
                .unwrap_or(SqlValue::Null),
        }
    }

    fn bytes(&self) -> usize {
        match self {
            Cell::Null | Cell::Int(_) | Cell::Real(_) => 8,
            Cell::Text(t) => t.len(),
            Cell::Blob { blob } => blob.len(),
        }
    }
}

/// A row by column name.
pub type Row = BTreeMap<String, Cell>;

/// What [`Store::history_snapshot`] keeps and [`Store::seed_history`] puts back.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct HistorySnapshot {
    /// The schema the rows were read from; another one is not seeded.
    pub schema_version: u32,
    pub instance_id: String,
    pub chats: Vec<Row>,
    pub chat_sync: Vec<Row>,
    pub messages: Vec<Row>,
}

impl HistorySnapshot {
    pub fn is_empty(&self) -> bool {
        self.chats.is_empty()
    }
}

/// How much a snapshot or a seed held.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub struct HistoryCount {
    pub chats: u32,
    pub messages: u32,
}

fn rows(conn: &Connection, sql: &str, params: impl rusqlite::Params) -> Result<Vec<Row>> {
    let mut stmt = conn.prepare(sql)?;
    let names: Vec<String> = stmt
        .column_names()
        .iter()
        .map(|n| (*n).to_owned())
        .collect();
    let found = stmt
        .query_map(params, |row| {
            let mut out = Row::new();
            for (index, name) in names.iter().enumerate() {
                out.insert(name.clone(), Cell::from_sql(row.get::<_, SqlValue>(index)?));
            }
            Ok(out)
        })?
        .collect::<rusqlite::Result<Vec<Row>>>()?;
    Ok(found)
}

fn int(row: &Row, column: &str) -> Option<i64> {
    match row.get(column) {
        Some(Cell::Int(i)) => Some(*i),
        _ => None,
    }
}

fn text<'a>(row: &'a Row, column: &str) -> Option<&'a str> {
    match row.get(column) {
        Some(Cell::Text(t)) => Some(t),
        _ => None,
    }
}

/// The columns of `table` (the snapshot names only these back).
fn columns(conn: &Connection, table: &str) -> Result<Vec<String>> {
    let mut stmt = conn.prepare(&format!("PRAGMA table_info({table})"))?;
    let names = stmt
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<String>>>()?;
    Ok(names)
}

fn insert(conn: &Connection, table: &str, known: &[String], row: &Row) -> Result<bool> {
    let named: Vec<(&String, &Cell)> = row
        .iter()
        .filter(|(name, _)| known.contains(name))
        .collect();
    if named.is_empty() {
        return Ok(false);
    }
    let list = named
        .iter()
        .map(|(name, _)| format!("\"{name}\""))
        .collect::<Vec<_>>()
        .join(", ");
    let marks = (1..=named.len())
        .map(|i| format!("?{i}"))
        .collect::<Vec<_>>()
        .join(", ");
    let values: Vec<SqlValue> = named.iter().map(|(_, cell)| cell.to_sql()).collect();
    let sql = format!("INSERT OR IGNORE INTO {table} ({list}) VALUES ({marks})");
    Ok(conn.execute(&sql, rusqlite::params_from_iter(values))? > 0)
}

impl Store {
    /// The recent chats and messages to keep between unlocks (see the module).
    pub fn history_snapshot(&self) -> Result<HistorySnapshot> {
        let conn = &self.conn;
        let chats = rows(
            conn,
            "SELECT c.* FROM chats c WHERE c.hidden = 0
             ORDER BY c.pinned DESC,
                 COALESCE((SELECT MAX(m.ts) FROM messages m WHERE m.chat = c.jid), -1) DESC, c.jid
             LIMIT ?1",
            [HISTORY_CHATS as i64],
        )?;
        let mut snapshot = HistorySnapshot {
            schema_version: SCHEMA_VERSION,
            instance_id: self.instance_id().to_owned(),
            ..HistorySnapshot::default()
        };
        let mut thumbs = 0usize;
        let mut other = 0usize;
        for chat in chats {
            let Some(jid) = text(&chat, "jid").map(str::to_owned) else {
                continue;
            };
            let cleared = int(&chat, "cleared_ts").unwrap_or(i64::MIN);
            let mut kept = rows(
                conn,
                "SELECT * FROM messages WHERE chat = ?1 AND ts > ?2 ORDER BY ts DESC, id DESC LIMIT ?3",
                rusqlite::params![jid, cleared, HISTORY_MESSAGES as i64 + 1],
            )?;
            let more = kept.len() > HISTORY_MESSAGES;
            kept.truncate(HISTORY_MESSAGES);
            let found = kept.len();
            let mut taken = Vec::with_capacity(kept.len());
            for mut message in kept {
                let thumb = message.remove("thumb").unwrap_or(Cell::Null);
                let size: usize = message.values().map(Cell::bytes).sum();
                if other + size > HISTORY_ROW_BYTES {
                    break;
                }
                other += size;
                let thumb_size = thumb.bytes();
                if !matches!(thumb, Cell::Null) && thumbs + thumb_size <= HISTORY_THUMB_BYTES {
                    thumbs += thumb_size;
                    message.insert("thumb".to_owned(), thumb);
                }
                taken.push(message);
            }
            // Every message of the chat is kept: the start of its history stays reached.
            let all = !more && taken.len() == found;
            let sync = rows(conn, "SELECT * FROM chat_sync WHERE jid = ?1", [&jid])?;
            if let Some(mut sync) = sync.into_iter().next() {
                let oldest = taken.iter().filter_map(|m| int(m, "ts")).min();
                let reached = int(&sync, "reached_start") == Some(1) && all;
                sync.insert("oldest_ts".to_owned(), oldest.map_or(Cell::Null, Cell::Int));
                sync.insert("reached_start".to_owned(), Cell::Int(i64::from(reached)));
                // An open gap starts over from the top, keeping its floor (as after a refreshed list).
                sync.insert("gap_before".to_owned(), Cell::Null);
                snapshot.chat_sync.push(sync);
            }
            snapshot.messages.extend(taken);
            snapshot.chats.push(chat);
        }
        Ok(snapshot)
    }

    /// Puts `snapshot` back into a store that holds no chat yet (a store in memory just opened), then lets the
    /// identity backfill learn the ids of the seeded rows. `None` when nothing was seeded: the store already had
    /// chats, or the snapshot is of another instance or schema.
    pub fn seed_history(&mut self, snapshot: &HistorySnapshot) -> Result<Option<HistoryCount>> {
        if snapshot.is_empty()
            || snapshot.schema_version != SCHEMA_VERSION
            || snapshot.instance_id != self.instance_id()
        {
            return Ok(None);
        }
        let has_chats: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM chats", [], |row| row.get(0))?;
        if has_chats > 0 {
            return Ok(None);
        }
        let mut count = HistoryCount::default();
        {
            let tx = self.conn.transaction()?;
            let chat_columns = columns(&tx, "chats")?;
            let sync_columns = columns(&tx, "chat_sync")?;
            let message_columns = columns(&tx, "messages")?;
            for chat in &snapshot.chats {
                count.chats += u32::from(insert(&tx, "chats", &chat_columns, chat)?);
            }
            for sync in &snapshot.chat_sync {
                insert(&tx, "chat_sync", &sync_columns, sync)?;
            }
            for message in &snapshot.messages {
                count.messages += u32::from(insert(&tx, "messages", &message_columns, message)?);
            }
            // The backfill runs again over the seeded rows: the map must know every stored id.
            tx.execute(
                "UPDATE sync_state SET identity_backfill = NULL WHERE id = 1",
                [],
            )?;
            tx.commit()?;
        }
        backfill::run(&mut self.conn, &mut self.identity)?;
        self.apply_pending_merges()?;
        Ok(Some(count))
    }
}
