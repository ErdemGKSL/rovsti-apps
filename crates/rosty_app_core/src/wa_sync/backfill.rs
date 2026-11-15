//! The identity backfill: rows stored before the identity tables existed become known to the map.
//!
//! The map answers a learned pair with a merge step only for keys it already knew, so before the first pair it
//! must have seen every id the store holds: each chat, sender, reaction sender, quoted author, mention, who
//! deleted a message, and each outbox chat. Runs when the store opens until it is done; `sync_state.identity_backfill`
//! holds how far it got (NULL: not begun, a `[chat, id]` cursor into the messages, then `done`), each batch is one
//! transaction, and resolving an id twice changes nothing, so a kill at any point resumes cleanly.
//!
//! An id that is not stored as its key (a device suffix, or an id the map already joins to another key) is
//! rewritten: authors in place, a chat by a logged merge step like any other key change.

use rosty_core::wa_identity::IdentityMap;
use rusqlite::{Connection, params};

use super::Result;
use super::authors::{self, JsonRefs};
use super::identity::{self, Ids};

/// `sync_state.identity_backfill` once every stored id is known.
const DONE: &str = "done";
/// Messages resolved per transaction.
const BATCH: usize = 500;

/// Runs the backfill to its end. Nothing to do once it is done.
pub(super) fn run(conn: &mut Connection, map: &mut IdentityMap) -> Result<()> {
    while !step(conn, map, BATCH)? {}
    Ok(())
}

/// One transaction of the backfill: the chats first, then `batch` messages at a time. True when it is done.
pub(super) fn step(conn: &mut Connection, map: &mut IdentityMap, batch: usize) -> Result<bool> {
    let tx = conn.transaction()?;
    let state: Option<String> = tx.query_row(
        "SELECT identity_backfill FROM sync_state WHERE id = 1",
        [],
        |row| row.get(0),
    )?;
    let mut ids = Ids::new(&tx, map);
    let next = match state.as_deref() {
        Some(DONE) => return Ok(true),
        None => {
            chats(&mut ids)?;
            Some((String::new(), String::new()))
        }
        // A cursor that does not read starts the messages over: resolving again changes nothing.
        Some(cursor) => {
            let (chat, id): (String, String) = serde_json::from_str(cursor).unwrap_or_default();
            messages(&mut ids, &chat, &id, batch)?
        }
    };
    let state = match &next {
        Some(cursor) => serde_json::to_string(cursor)?,
        None => DONE.to_owned(),
    };
    tx.execute(
        "UPDATE sync_state SET identity_backfill = ?1 WHERE id = 1",
        [state],
    )?;
    tx.commit()?;
    Ok(next.is_none())
}

/// Resolves every id a chat, a cursor, a message or an outbox row is stored under.
fn chats(ids: &mut Ids<'_>) -> Result<()> {
    let stored: Vec<String> = ids
        .conn
        .prepare(
            "SELECT jid FROM chats UNION SELECT jid FROM chat_sync UNION SELECT chat FROM outbox
             UNION SELECT chat FROM messages",
        )?
        .query_map([], |row| row.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    for jid in stored {
        let key = ids.canon(&jid)?.key;
        if key != jid {
            identity::log_merge(ids.conn, 0, &jid, &key, identity::REASON_BACKFILL)?;
        }
    }
    Ok(())
}

/// Resolves the authors of the next `batch` messages after `(chat, id)`. The answer is the last message read, or
/// `None` when there is none left.
fn messages(
    ids: &mut Ids<'_>,
    chat: &str,
    id: &str,
    batch: usize,
) -> Result<Option<(String, String)>> {
    type Row = (String, String, Option<String>, Option<String>, JsonRefs);
    let conn = ids.conn;
    let rows: Vec<Row> = conn
        .prepare_cached(&format!(
            "SELECT chat, id, sender, deleted_by, {} FROM messages
             WHERE (chat, id) > (?1, ?2) ORDER BY chat, id LIMIT ?3",
            JsonRefs::COLUMNS
        ))?
        .query_map(params![chat, id, batch as i64], |row| {
            Ok((
                row.get(0)?,
                row.get(1)?,
                row.get(2)?,
                row.get(3)?,
                JsonRefs::read(row, 4)?,
            ))
        })?
        .collect::<rusqlite::Result<_>>()?;
    let full = rows.len() >= batch.max(1);
    let last = rows.last().map(|row| (row.0.clone(), row.1.clone()));
    let mut resolve = |text: &str| ids.moved(text);
    for (chat, id, mut sender, mut deleted_by, refs) in rows {
        let moved = authors::id(&mut sender, &mut resolve)?;
        let deleted_moved = authors::id(&mut deleted_by, &mut resolve)?.is_some();
        if moved.is_some() || deleted_moved {
            let wire = moved
                .filter(|moved| moved.wire != moved.to)
                .map(|moved| moved.wire);
            conn.execute(
                "UPDATE messages SET sender = ?3, deleted_by = ?4, wire_sender = COALESCE(wire_sender, ?5)
                 WHERE chat = ?1 AND id = ?2",
                params![chat, id, sender, deleted_by, wire],
            )?;
        }
        if let Some(rewritten) = refs.rewritten(&mut resolve)? {
            rewritten.write(conn, &chat, &id)?;
        }
    }
    Ok(last.filter(|_| full))
}
