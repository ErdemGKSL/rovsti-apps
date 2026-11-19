//! What the UI reads: the chat list and pages of a thread. Nothing here touches the network.

use std::collections::HashMap;

use rusqlite::{Connection, Row, params};

use super::apply::{self, COLUMN_COUNT, COLUMNS, Stored};
use super::media::MediaState;
use super::model::{
    ChatRow, GalleryPage, Kind, MessageRow, PendingOp, RowState, StarredCursor, StarredPage,
    ThreadCursor, ThreadPage,
};
use super::store::Store;
use super::{Error, Result, outbox};

/// The largest thread page.
pub const MAX_THREAD_PAGE: u32 = 500;

/// The largest gallery page.
pub const MAX_GALLERY_PAGE: u32 = 200;
/// What a gallery shows when the caller names no kinds.
pub const GALLERY_KINDS: [Kind; 2] = [Kind::Image, Kind::Video];
/// The condition of the `messages_gallery` index, word for word (SQLite uses a partial index only when the
/// query repeats its condition), then what a gallery never shows: view-once media.
const GALLERY_ROWS: &str = "kind IN ('image', 'video', 'audio', 'document') AND deleted = 0
    AND COALESCE(json_extract(media_json, '$.view_once'), 0) = 0";

/// `id`, `chat`, the gateway columns, then whether a thumbnail is stored, the cached file, the client id, the
/// wire sender, the state of the full file and the two local marks (viewed, played). Read from `messages` under
/// its own name.
fn row_columns() -> String {
    format!(
        "id, chat, {COLUMNS}, thumb IS NOT NULL,
         (SELECT path FROM media WHERE media.chat = messages.chat AND media.id = messages.id
            AND media.state = 'ready'),
         client_id, wire_sender,
         (SELECT state FROM media WHERE media.chat = messages.chat AND media.id = messages.id),
         viewed_ms IS NOT NULL, played_ms IS NOT NULL, messages.expires_at"
    )
}

/// What the edit rule needs of a stored row.
fn edit_target(row: &MessageRow) -> rosty_core::wa_actions::EditTarget {
    rosty_core::wa_actions::EditTarget {
        from_me: row.from_me,
        sent: row.client_id.is_some() || row.state != RowState::Received,
        has_text_slot: matches!(
            row.kind,
            Kind::Text | Kind::Image | Kind::Video | Kind::Document
        ),
        deleted: row.deleted,
        special: row.system.is_some()
            || row.unavailable.is_some()
            || row.media.as_ref().is_some_and(|m| m.view_once),
        sent_ms: row.timestamp_ms,
    }
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
        unavailable: stored.extra.unavailable,
        has_thumbnail: row.get(tail)?,
        media_path: row.get(tail + 1)?,
        media_state: row
            .get::<_, Option<String>>(tail + 4)?
            .map(|name| MediaState::from_name(&name))
            .filter(|state| *state != MediaState::None),
        client_id: row.get(tail + 2)?,
        wire_sender: row.get(tail + 3)?,
        error: None,
        pending: Vec::new(),
        viewed: row.get(tail + 5)?,
        played: row.get(tail + 6)?,
        editable_until_ms: None,
        forwarding_score: stored.extra.forwarding_score,
        forwarded_many: rosty_core::wa_actions::forwarded_many(stored.extra.forwarding_score),
        mention_spans: Vec::new(),
        link_preview: stored.extra.link_preview,
        system: stored.extra.system,
        expires_at: row.get(tail + 7)?,
    })
    .map(|mut found| {
        found.editable_until_ms = rosty_core::wa_actions::editable_until(&edit_target(&found));
        found
    })
}

/// The newest stored message of `chat` that the thread shows.
fn newest(conn: &Connection, chat: &str, cleared_ts: Option<i64>) -> Result<Option<MessageRow>> {
    let sql = format!(
        "SELECT {} FROM messages WHERE chat = ?1 AND ts > ?2 AND hidden_ms IS NULL ORDER BY ts DESC, id DESC LIMIT 1",
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
                picture_id, cleared_ts, wire_jid, ephemeral_s
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
                        ephemeral_s: row.get(12)?,
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
             WHERE chat = ?1 AND ts > ?2 AND hidden_ms IS NULL AND (?3 OR ts < ?4 OR (ts = ?4 AND id < ?5))
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
        self.fill_mentions(&mut stored);

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
             WHERE starred = 1 AND deleted = 0 AND COALESCE(chats.hidden, 0) = 0 AND messages.hidden_ms IS NULL
                AND ts > COALESCE(cleared_ts, ?1)
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

    /// A page of the media of a chat, newest first: at most `limit` stored messages of `kinds`
    /// ([`GALLERY_KINDS`] when empty; photos, videos, audio and documents can be asked for) that are older than
    /// `before` and newer than `after`. With `after` alone the page is the one right above the cursor, so a
    /// viewer pages both ways from the message it opened on: a cursor is the `timestamp_ms` and `id` of any
    /// row. Left out: deleted messages, view-once media, sends still in the outbox and what the thread does
    /// not show (messages cleared on another device).
    ///
    /// `chat` may be any id of the chat ([`Store::stored_key`]).
    pub fn chat_media(
        &self,
        chat: &str,
        limit: u32,
        before: Option<&ThreadCursor>,
        after: Option<&ThreadCursor>,
        kinds: &[Kind],
    ) -> Result<GalleryPage> {
        let kinds = if kinds.is_empty() {
            &GALLERY_KINDS[..]
        } else {
            kinds
        };
        let in_gallery = |kind: &Kind| {
            matches!(
                kind,
                Kind::Image | Kind::Video | Kind::Audio | Kind::Document
            )
        };
        if let Some(other) = kinds.iter().find(|kind| !in_gallery(kind)) {
            return Err(Error::Invalid(format!(
                "a gallery has images, videos, audio and documents, not {}",
                other.as_str()
            )));
        }
        // `,image,video,`: matched with `instr`, so the statement is the same for every choice of kinds.
        let wanted: String = kinds.iter().fold(String::from(","), |mut list, kind| {
            list.push_str(kind.as_str());
            list.push(',');
            list
        });
        let chat = self.stored_key(chat);
        let chat = chat.as_str();
        let limit = limit.clamp(1, MAX_GALLERY_PAGE);
        let cleared_ts: i64 = self
            .conn
            .query_row(
                "SELECT cleared_ts FROM chats WHERE jid = ?1",
                [chat],
                |row| row.get::<_, Option<i64>>(0),
            )
            .unwrap_or(None)
            .unwrap_or(i64::MIN);
        let (before_ts, before_id) = before.map_or((i64::MAX, ""), |cursor| {
            (cursor.timestamp_ms, cursor.id.as_str())
        });
        let (after_ts, after_id) = after.map_or((i64::MIN, ""), |cursor| {
            (cursor.timestamp_ms, cursor.id.as_str())
        });
        // With `after` alone the rows nearest above the cursor are wanted: read upwards, then turn the page.
        let upwards = after.is_some() && before.is_none();
        let order = if upwards { "ASC" } else { "DESC" };
        let sql = format!(
            "SELECT {} FROM messages
             WHERE chat = ?1 AND {GALLERY_ROWS} AND ts > ?2 AND hidden_ms IS NULL
                AND instr(?3, ',' || kind || ',') > 0
                AND (?4 OR ts < ?5 OR (ts = ?5 AND id < ?6))
                AND (?7 OR ts > ?8 OR (ts = ?8 AND id > ?9))
             ORDER BY ts {order}, id {order} LIMIT ?10",
            row_columns()
        );
        let mut stmt = self.conn.prepare_cached(&sql)?;
        let mut items: Vec<MessageRow> = stmt
            .query_map(
                params![
                    chat,
                    cleared_ts,
                    wanted,
                    before.is_none(),
                    before_ts,
                    before_id,
                    after.is_none(),
                    after_ts,
                    after_id,
                    limit
                ],
                read_row,
            )?
            .collect::<rusqlite::Result<_>>()?;
        if upwards {
            items.reverse();
        }
        // Whether media lies beyond either end of the page, whatever window was asked for.
        let beyond = |older: bool, edge: &MessageRow| -> Result<Option<ThreadCursor>> {
            let side = if older {
                "(ts < ?4 OR (ts = ?4 AND id < ?5))"
            } else {
                "(ts > ?4 OR (ts = ?4 AND id > ?5))"
            };
            let sql = format!(
                "SELECT EXISTS (SELECT 1 FROM messages
                 WHERE chat = ?1 AND {GALLERY_ROWS} AND ts > ?2 AND hidden_ms IS NULL
                AND instr(?3, ',' || kind || ',') > 0
                    AND {side})"
            );
            let found: bool = self.conn.prepare_cached(&sql)?.query_row(
                params![chat, cleared_ts, wanted, edge.timestamp_ms, edge.id],
                |row| row.get(0),
            )?;
            Ok(found.then(|| ThreadCursor {
                timestamp_ms: edge.timestamp_ms,
                id: edge.id.clone(),
            }))
        };
        let older = match items.last() {
            Some(last) => beyond(true, last)?,
            None => None,
        };
        let newer = match items.first() {
            Some(first) => beyond(false, first)?,
            None => None,
        };
        Ok(GalleryPage {
            items,
            older,
            newer,
        })
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
        let mut found: Vec<MessageRow> = rows.next().transpose()?.into_iter().collect();
        drop(rows);
        self.fill_mentions(&mut found);
        Ok(found.pop())
    }

    /// The mention spans of each row that mentions someone: every token the identity layer knows for each person
    /// (a LID mention of someone whose number is known since reads as that person).
    pub(super) fn fill_mentions(&self, rows: &mut [MessageRow]) {
        for row in rows.iter_mut().filter(|row| !row.mentions.is_empty()) {
            let Some(text) = row.text.as_deref() else {
                continue;
            };
            let targets: Vec<rosty_core::wa_actions::MentionTarget> = row
                .mentions
                .iter()
                .map(|jid| rosty_core::wa_actions::MentionTarget {
                    jid: jid.clone(),
                    tokens: self.mention_tokens(jid),
                })
                .collect();
            row.mention_spans = rosty_core::wa_actions::spans(text, &targets);
        }
    }

    /// The users of every id of the person behind `jid` (numbers and LIDs), and `jid`'s own.
    fn mention_tokens(&self, jid: &str) -> Vec<String> {
        let mut tokens: Vec<String> = rosty_core::wa_actions::mention_token(jid)
            .into_iter()
            .collect();
        if let Some(person) = self.identity.lookup(jid).and_then(|r| r.person_id) {
            for alias in self.identity.aliases(person) {
                if !tokens.contains(&alias.user) {
                    tokens.push(alias.user);
                }
            }
        }
        tokens
    }

    /// Deletes the disappearing messages that are due at `now_ms`, with their media rows. Answers the files to
    /// delete, the chats that changed and when the next one is due. Run it when the store opens (expiry while the
    /// app was closed) and again at `next_ms`.
    pub fn expire(&mut self, now_ms: i64) -> Result<super::model::Expired> {
        let tx = self.conn.transaction()?;
        let due: Vec<(String, String)> = {
            let mut stmt = tx.prepare(
                "SELECT chat, id FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?1",
            )?;
            stmt.query_map([now_ms], |row| Ok((row.get(0)?, row.get(1)?)))?
                .collect::<rusqlite::Result<_>>()?
        };
        let mut files = Vec::new();
        let mut chats: Vec<String> = Vec::new();
        for (chat, id) in &due {
            let paths: Vec<String> = {
                let mut stmt = tx.prepare(
                    "SELECT path FROM media WHERE chat = ?1 AND id = ?2 AND path IS NOT NULL",
                )?;
                stmt.query_map(params![chat, id], |row| row.get(0))?
                    .collect::<rusqlite::Result<_>>()?
            };
            files.extend(paths);
            tx.execute(
                "DELETE FROM media WHERE chat = ?1 AND id = ?2",
                params![chat, id],
            )?;
            tx.execute(
                "DELETE FROM messages WHERE chat = ?1 AND id = ?2",
                params![chat, id],
            )?;
            if !chats.contains(chat) {
                chats.push(chat.clone());
            }
        }
        let next_ms: Option<i64> = tx.query_row(
            "SELECT MIN(expires_at) FROM messages WHERE expires_at IS NOT NULL",
            [],
            |row| row.get(0),
        )?;
        tx.commit()?;
        Ok(super::model::Expired {
            removed: due.len() as u32,
            files,
            chats,
            next_ms,
        })
    }
}
