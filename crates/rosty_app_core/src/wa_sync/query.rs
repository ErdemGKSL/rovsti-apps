//! What the UI reads: the chat list and pages of a thread. Nothing here touches the network.

use std::collections::HashMap;

use rusqlite::{Connection, Row, params};

use super::apply::{self, COLUMN_COUNT, COLUMNS, Stored};
use super::model::{
    ChatRow, MessageRow, PendingOp, RowState, StarredCursor, StarredPage, ThreadCursor, ThreadPage,
};
use super::store::Store;
use super::{Result, outbox};

/// The largest thread page.
pub const MAX_THREAD_PAGE: u32 = 500;

/// `id`, `chat`, the gateway columns, then whether a thumbnail is stored, the cached file, the client id and the
/// wire sender.
fn row_columns() -> String {
    format!("id, chat, {COLUMNS}, thumb IS NOT NULL, media_path, client_id, wire_sender")
}

fn read_row(row: &Row<'_>) -> rusqlite::Result<MessageRow> {
    let stored: Stored = apply::read(row, 2)?;
    let tail = 2 + COLUMN_COUNT;
    Ok(MessageRow {
        id: row.get(0)?,
        chat: row.get(1)?,
        sender: stored.sender,
        sender_name: stored.sender_name,
        from_me: stored.from_me,
        timestamp_ms: stored.ts,
        kind: stored.content.kind,
        text: stored.content.text,
        mime: stored.content.mime,
        size: stored.content.size,
        quoted: stored.quoted,
        reactions: stored.reactions,
        state: RowState::of_stored(stored.from_me, stored.status),
        edited_ms: stored.edited_ms,
        edit_history: stored.edit_history,
        deleted: stored.deleted,
        deleted_at: stored.deleted_at,
        deleted_by: stored.deleted_by,
        forwarded: stored.forwarded,
        starred: stored.starred,
        media: stored.media,
        location: stored.extra.location,
        contact: stored.extra.contact,
        mentions: stored.extra.mentions,
        has_thumbnail: row.get(tail)?,
        media_path: row.get(tail + 1)?,
        client_id: row.get(tail + 2)?,
        wire_sender: row.get(tail + 3)?,
        error: None,
        pending: Vec::new(),
    })
}

/// The newest stored message of `chat` that the thread shows.
fn newest(conn: &Connection, chat: &str, cleared_ts: Option<i64>) -> Result<Option<MessageRow>> {
    let sql = format!(
        "SELECT {} FROM messages WHERE chat = ?1 AND ts > ?2 ORDER BY ts DESC, id DESC LIMIT 1",
        row_columns()
    );
    let mut stmt = conn.prepare_cached(&sql)?;
    let mut rows = stmt.query_map(params![chat, cleared_ts.unwrap_or(i64::MIN)], read_row)?;
    Ok(rows.next().transpose()?)
}

impl Store {
    /// The chat list, most recently active first. A chat deleted on another device is left out until a message
    /// arrives; `include_archived` adds the archived ones.
    pub fn chats(&self, include_archived: bool) -> Result<Vec<ChatRow>> {
        let mut stmt = self.conn.prepare(
            "SELECT jid, name, push_name, is_group, unread, pinned, archived, muted_until_ms, marked_unread,
                picture_id, cleared_ts, wire_jid
             FROM chats WHERE hidden = 0 AND (archived = 0 OR ?1)",
        )?;
        let found: Vec<(ChatRow, Option<i64>)> = stmt
            .query_map([include_archived], |row| {
                Ok((
                    ChatRow {
                        jid: row.get(0)?,
                        wire_jid: row.get(11)?,
                        name: row.get(1)?,
                        push_name: row.get(2)?,
                        is_group: row.get(3)?,
                        unread: row.get(4)?,
                        pinned: row.get(5)?,
                        archived: row.get(6)?,
                        muted_until_ms: row.get(7)?,
                        marked_unread: row.get(8)?,
                        picture_id: row.get(9)?,
                        last_ms: 0,
                        last: None,
                        waiting: 0,
                    },
                    row.get(10)?,
                ))
            })?
            .collect::<rusqlite::Result<_>>()?;
        let mut chats = Vec::with_capacity(found.len());
        for (mut chat, cleared_ts) in found {
            let stored = newest(&self.conn, &chat.jid, cleared_ts)?;
            let (queued, waiting) = outbox::chat_tail(&self.conn, &chat.jid)?;
            chat.waiting = waiting;
            // A send in the outbox was written after everything stored: it is the newest row.
            chat.last = queued.or(stored);
            chat.last_ms = chat.last.as_ref().map_or(0, |last| last.timestamp_ms);
            chats.push(chat);
        }
        chats.sort_by(|a, b| b.last_ms.cmp(&a.last_ms).then_with(|| a.jid.cmp(&b.jid)));
        Ok(chats)
    }

    /// A page of a thread, newest first: at most `limit` stored messages older than `before` (`None`: from the
    /// newest). The first page starts with the sends still in the outbox (they are not counted in `limit`).
    /// Deleted and edited messages are rows like any other, with their state and their kept content.
    ///
    /// `chat` may be any id of the chat: one a merge has since moved (a LID whose number is known now) reads the
    /// merged thread, and the rows name the key ([`Store::stored_key`]).
    pub fn thread(
        &self,
        chat: &str,
        limit: u32,
        before: Option<&ThreadCursor>,
    ) -> Result<ThreadPage> {
        let chat = self.stored_key(chat);
        let chat = chat.as_str();
        let limit = limit.clamp(1, MAX_THREAD_PAGE);
        let cleared_ts: Option<i64> = self
            .conn
            .query_row(
                "SELECT cleared_ts FROM chats WHERE jid = ?1",
                [chat],
                |row| row.get(0),
            )
            .unwrap_or(None);
        let (before_ts, before_id) = before.map_or((i64::MAX, ""), |cursor| {
            (cursor.timestamp_ms, cursor.id.as_str())
        });
        let sql = format!(
            "SELECT {} FROM messages
             WHERE chat = ?1 AND ts > ?2 AND (?3 OR ts < ?4 OR (ts = ?4 AND id < ?5))
             ORDER BY ts DESC, id DESC LIMIT ?6",
            row_columns()
        );
        let mut stmt = self.conn.prepare_cached(&sql)?;
        // One more than asked tells whether an older stored page exists.
        let mut stored: Vec<MessageRow> = stmt
            .query_map(
                params![
                    chat,
                    cleared_ts.unwrap_or(i64::MIN),
                    before.is_none(),
                    before_ts,
                    before_id,
                    limit + 1
                ],
                read_row,
            )?
            .collect::<rusqlite::Result<_>>()?;
        let more = stored.len() > limit as usize;
        stored.truncate(limit as usize);
        let next = stored.last().filter(|_| more).map(|last| ThreadCursor {
            timestamp_ms: last.timestamp_ms,
            id: last.id.clone(),
        });

        let mut waiting: HashMap<String, Vec<PendingOp>> = HashMap::new();
        for (message_id, op) in outbox::pending_ops(&self.conn, chat)? {
            waiting.entry(message_id).or_default().push(op);
        }
        for row in &mut stored {
            if let Some(ops) = waiting.remove(&row.id) {
                row.pending = ops;
            }
        }

        let mut items = if before.is_none() {
            outbox::pending_rows(&self.conn, chat)?
        } else {
            Vec::new()
        };
        items.append(&mut stored);
        let cursor = self.cursor(chat)?;
        Ok(ThreadPage {
            items,
            next,
            reached_start: cursor.reached_start,
            syncing: cursor.gap.is_some(),
        })
    }

    /// A page of the starred messages of every chat, newest first: at most `limit` older than `before` (`None`:
    /// from the newest). A star still in the outbox counts (the flag is set when it is queued). Left out: deleted
    /// messages, and what the thread does not show either (a chat deleted on another device, messages cleared there).
    pub fn starred(&self, limit: u32, before: Option<&StarredCursor>) -> Result<StarredPage> {
        let limit = limit.clamp(1, MAX_THREAD_PAGE);
        let (before_ts, before_chat, before_id) = before.map_or((i64::MAX, "", ""), |cursor| {
            (
                cursor.timestamp_ms,
                cursor.chat.as_str(),
                cursor.id.as_str(),
            )
        });
        let sql = format!(
            "SELECT {} FROM messages LEFT JOIN chats ON jid = chat
             WHERE starred = 1 AND deleted = 0 AND COALESCE(hidden, 0) = 0 AND ts > COALESCE(cleared_ts, ?1)
                AND (?2 OR ts < ?3 OR (ts = ?3 AND (chat < ?4 OR (chat = ?4 AND id < ?5))))
             ORDER BY ts DESC, chat DESC, id DESC LIMIT ?6",
            row_columns()
        );
        let mut stmt = self.conn.prepare_cached(&sql)?;
        // One more than asked tells whether an older page exists.
        let mut items: Vec<MessageRow> = stmt
            .query_map(
                params![
                    i64::MIN,
                    before.is_none(),
                    before_ts,
                    before_chat,
                    before_id,
                    limit + 1
                ],
                read_row,
            )?
            .collect::<rusqlite::Result<_>>()?;
        let more = items.len() > limit as usize;
        items.truncate(limit as usize);
        let next = items.last().filter(|_| more).map(|last| StarredCursor {
            timestamp_ms: last.timestamp_ms,
            chat: last.chat.clone(),
            id: last.id.clone(),
        });
        Ok(StarredPage { items, next })
    }

    /// One stored message. `chat` may be any id of the chat ([`Store::stored_key`]).
    pub fn message(&self, chat: &str, id: &str) -> Result<Option<MessageRow>> {
        let chat = self.stored_key(chat);
        let sql = format!(
            "SELECT {} FROM messages WHERE chat = ?1 AND id = ?2",
            row_columns()
        );
        let mut stmt = self.conn.prepare_cached(&sql)?;
        let mut rows = stmt.query_map(params![chat, id], read_row)?;
        Ok(rows.next().transpose()?)
    }
}
