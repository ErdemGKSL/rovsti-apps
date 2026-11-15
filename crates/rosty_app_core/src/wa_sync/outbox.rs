//! The outbox: what was written while offline waits here, in the same database, and goes out by itself.
//!
//! * Rows of one chat go out in the order written: only the oldest unsettled row of a chat is ever due.
//! * [`Store::outbox_due`] hands rows out and marks them `sending`; [`Store::outbox_settle`] reports the answer. A
//!   row that stays `sending` longer than [`SENDING_LEASE_MS`] (the app was killed mid-send) is due again.
//! * A send is reconciled with its echo so it never doubles: the gateway's answer names the message id, and
//!   whichever of the answer and the echo event comes second finds the message already stored. When the answer
//!   never came (the app died, the network dropped after the request left), the row is `maybe_sent`: an echo of
//!   my own message with the same content, not older than the attempt, confirms it instead of sending again.
//! * A star shows on the stored message at once and holds against what the gateway still says until it went out. A
//!   later star of the same message replaces one that has not gone out; a refused one is undone and dropped.

use rosty_wa_proto::{MediaKind, MessageBody, Reaction, WaMessage};
use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};

use super::model::{Content, Kind, MessageRow, RowState};
use super::store::Store;
use super::{Error, Result, apply};

/// The first retry waits this long; every further one twice as long.
pub const BACKOFF_BASE_MS: i64 = 2_000;
/// The longest wait between retries.
pub const BACKOFF_MAX_MS: i64 = 300_000;
/// A row handed out this long ago without an answer is due again.
pub const SENDING_LEASE_MS: i64 = 120_000;
/// How much older than the attempt an echo's timestamp may be (clocks differ, WhatsApp stamps whole seconds).
const ECHO_SKEW_MS: i64 = 120_000;

/// The wait before retry number `attempts` (1 is the first retry).
pub fn backoff_ms(attempts: u32) -> i64 {
    let doublings = attempts.saturating_sub(1).min(20);
    BACKOFF_BASE_MS
        .saturating_mul(1_i64 << doublings)
        .min(BACKOFF_MAX_MS)
}

/// What a queued row does. `chat` is on the row.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Action {
    SendText {
        text: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        quoted_id: Option<String>,
    },
    SendMedia {
        /// The file to send, already copied into app storage.
        file: String,
        media: MediaKind,
        mime: String,
        size: u64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        caption: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        quoted_id: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        file_name: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        seconds: Option<u32>,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        voice_note: bool,
    },
    Edit {
        message_id: String,
        text: String,
    },
    /// Delete for everyone.
    Revoke {
        message_id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sender: Option<String>,
    },
    React {
        message_id: String,
        from_me: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sender: Option<String>,
        /// Empty removes the reaction.
        emoji: String,
    },
    MarkRead {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sender: Option<String>,
        ids: Vec<String>,
    },
    /// Star or unstar a stored message on the account.
    Star {
        message_id: String,
        starred: bool,
    },
}

impl Action {
    pub fn kind(&self) -> &'static str {
        match self {
            Action::SendText { .. } => "send_text",
            Action::SendMedia { .. } => "send_media",
            Action::Edit { .. } => "edit",
            Action::Revoke { .. } => "revoke",
            Action::React { .. } => "react",
            Action::MarkRead { .. } => "mark_read",
            Action::Star { .. } => "star",
        }
    }

    pub fn is_send(&self) -> bool {
        matches!(self, Action::SendText { .. } | Action::SendMedia { .. })
    }

    /// The stored message this action changes.
    fn target(&self) -> Option<&str> {
        match self {
            Action::Edit { message_id, .. }
            | Action::Revoke { message_id, .. }
            | Action::React { message_id, .. }
            | Action::Star { message_id, .. } => Some(message_id),
            Action::SendText { .. } | Action::SendMedia { .. } | Action::MarkRead { .. } => None,
        }
    }

    /// What the message of a send says.
    fn content(&self) -> Option<Content> {
        match self {
            Action::SendText { text, .. } => Some(Content {
                kind: Kind::Text,
                text: Some(text.clone()),
                mime: None,
                size: None,
            }),
            Action::SendMedia {
                media,
                mime,
                size,
                caption,
                ..
            } => Some(Content {
                kind: Kind::of_media(*media),
                text: caption.clone(),
                mime: Some(mime.clone()),
                size: Some(*size),
            }),
            _ => None,
        }
    }

    fn quoted_id(&self) -> Option<&str> {
        match self {
            Action::SendText { quoted_id, .. } | Action::SendMedia { quoted_id, .. } => {
                quoted_id.as_deref()
            }
            _ => None,
        }
    }

    /// True when `body` is what this send would arrive as. Media compares kind and caption: the gateway may
    /// re-encode, so MIME type and size can differ.
    fn echoes(&self, body: &MessageBody) -> bool {
        match (self, body) {
            (Action::SendText { text, .. }, MessageBody::Text(echo)) => text == echo,
            (
                Action::SendMedia { media, caption, .. },
                MessageBody::Media {
                    kind,
                    caption: echo,
                    ..
                },
            ) => {
                let empty = |text: &Option<String>| text.as_deref().unwrap_or("").is_empty();
                media == kind && (caption == echo || (empty(caption) && empty(echo)))
            }
            _ => false,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OutboxState {
    /// Waits for the connection, or for its turn, or for its next attempt.
    Waiting,
    /// Handed out by [`Store::outbox_due`]; the answer is not in yet.
    Sending,
    /// Refused for good; goes out again only after [`Store::outbox_retry`] or [`Store::outbox_edit`].
    Failed,
}

impl OutboxState {
    fn from_name(name: &str) -> OutboxState {
        match name {
            "sending" => OutboxState::Sending,
            "failed" => OutboxState::Failed,
            _ => OutboxState::Waiting,
        }
    }
}

/// One queued row.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct OutboxItem {
    pub client_id: String,
    pub chat: String,
    pub action: Action,
    pub created_ms: i64,
    pub attempts: u32,
    pub next_attempt_ms: i64,
    pub state: OutboxState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_error: Option<String>,
}

/// What [`Store::outbox_due`] hands out.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Due {
    /// Rows to send now, at most one per chat, oldest first. They are `sending` from now on.
    pub items: Vec<OutboxItem>,
    /// Sends held back because an earlier attempt may have gone out and the store is not in sync yet (a chat list
    /// refresh or a gap page is owed, see [`Store::plan`]). Ask again once the sync is done.
    pub held: u32,
    /// When to ask again without a new trigger (a backoff ends, a lease runs out); `None`: nothing is scheduled.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_wake_ms: Option<i64>,
}

/// Why an attempt failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorKind {
    /// No answer (timeout, connection lost): the gateway may have sent it. Retried; an echo may confirm it.
    Network,
    /// The gateway answered that it did not send (not connected, rate limited, internal error). Retried.
    Gateway,
    /// The gateway refused the request itself (bad request, too large, unknown chat). Not retried.
    Rejected,
}

/// The answer to one attempt.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Outcome {
    /// It went out. A send names the gateway's message (`Sent`); other actions leave both out.
    Sent {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        message_id: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        timestamp_ms: Option<i64>,
    },
    Error {
        error: ErrorKind,
        message: String,
        /// The gateway's `retry_after_ms`, when it named one.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        retry_after_ms: Option<i64>,
    },
}

/// What became of the row.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum Settled {
    /// Out of the outbox (sent, or already confirmed by its echo, or cancelled meanwhile).
    Done {
        /// The stored message of a send.
        #[serde(skip_serializing_if = "Option::is_none")]
        message_id: Option<String>,
    },
    Waiting {
        next_attempt_ms: i64,
    },
    Failed,
}

const ITEM_COLUMNS: &str =
    "client_id, chat, payload, created_ms, attempts, next_attempt_ms, state, last_error";

/// The columns of [`ITEM_COLUMNS`] before the payload is parsed.
type RawItem = (
    String,
    String,
    String,
    i64,
    u32,
    i64,
    String,
    Option<String>,
);

fn read_item(row: &Row<'_>) -> rusqlite::Result<RawItem> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
        row.get(6)?,
        row.get(7)?,
    ))
}

fn parse(raw: RawItem) -> Result<OutboxItem> {
    let (client_id, chat, payload, created_ms, attempts, next_attempt_ms, state, last_error) = raw;
    Ok(OutboxItem {
        client_id,
        chat,
        action: serde_json::from_str(&payload)?,
        created_ms,
        attempts,
        next_attempt_ms,
        state: OutboxState::from_name(&state),
        last_error,
    })
}

fn item(conn: &Connection, client_id: &str) -> Result<Option<OutboxItem>> {
    conn.query_row(
        &format!("SELECT {ITEM_COLUMNS} FROM outbox WHERE client_id = ?1"),
        [client_id],
        read_item,
    )
    .optional()?
    .map(parse)
    .transpose()
}

fn items_where(conn: &Connection, clause: &str, chat: Option<&str>) -> Result<Vec<OutboxItem>> {
    let sql = format!("SELECT {ITEM_COLUMNS} FROM outbox WHERE {clause} ORDER BY seq");
    let mut stmt = conn.prepare(&sql)?;
    let rows = match chat {
        Some(chat) => stmt
            .query_map([chat], read_item)?
            .collect::<rusqlite::Result<Vec<_>>>(),
        None => stmt.query_map([], read_item)?.collect(),
    }?;
    rows.into_iter().map(parse).collect()
}

/// True while a read mark of `chat` waits: the gateway's unread count is stale until it went out.
pub(super) fn has_pending_read(conn: &Connection, chat: &str) -> Result<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM outbox WHERE chat = ?1 AND kind = 'mark_read')",
        [chat],
        |row| row.get(0),
    )?)
}

/// What the newest star of message `id` still in the outbox asks for: the stored flag holds it until it went out.
pub(super) fn pending_star(conn: &Connection, chat: &str, id: &str) -> Result<Option<bool>> {
    Ok(
        items_where(conn, "chat = ?1 AND kind = 'star'", Some(chat))?
            .into_iter()
            .rev()
            .find_map(|item| match item.action {
                Action::Star {
                    message_id,
                    starred,
                } if message_id == id => Some(starred),
                _ => None,
            }),
    )
}

fn set_starred(conn: &Connection, chat: &str, id: &str, starred: bool) -> Result<()> {
    conn.execute(
        "UPDATE messages SET starred = ?3 WHERE chat = ?1 AND id = ?2",
        params![chat, id, starred],
    )?;
    Ok(())
}

/// The sends of `chat` still in the outbox, newest first, as thread rows.
pub(super) fn pending_rows(conn: &Connection, chat: &str) -> Result<Vec<MessageRow>> {
    let mut rows: Vec<MessageRow> = items_where(
        conn,
        "chat = ?1 AND kind IN ('send_text', 'send_media')",
        Some(chat),
    )?
    .into_iter()
    .filter_map(pending_row)
    .collect();
    rows.reverse();
    Ok(rows)
}

fn pending_row(item: OutboxItem) -> Option<MessageRow> {
    let content = item.action.content()?;
    let quoted = item.action.quoted_id().map(|id| rosty_wa_proto::Quoted {
        id: id.to_owned(),
        sender: None,
        preview: None,
    });
    let media_path = match &item.action {
        Action::SendMedia { file, .. } => Some(file.clone()),
        _ => None,
    };
    Some(MessageRow {
        id: item.client_id.clone(),
        chat: item.chat,
        sender: None,
        sender_name: None,
        from_me: true,
        timestamp_ms: item.created_ms,
        kind: content.kind,
        text: content.text,
        mime: content.mime,
        size: content.size,
        quoted,
        reactions: Vec::new(),
        state: RowState::of_outbox(item.state),
        edited_ms: None,
        edit_history: Vec::new(),
        deleted: false,
        deleted_at: None,
        deleted_by: None,
        forwarded: false,
        starred: false,
        media: None,
        location: None,
        contact: None,
        mentions: Vec::new(),
        has_thumbnail: false,
        media_path,
        client_id: Some(item.client_id),
        error: item.last_error,
        pending: Vec::new(),
    })
}

/// Edits, deletes and reactions of stored messages of `chat` that wait, as `(message id, op)`.
pub(super) fn pending_ops(
    conn: &Connection,
    chat: &str,
) -> Result<Vec<(String, super::model::PendingOp)>> {
    Ok(items_where(
        conn,
        "chat = ?1 AND kind IN ('edit', 'revoke', 'react')",
        Some(chat),
    )?
    .into_iter()
    .filter_map(|item| {
        let target = item.action.target()?.to_owned();
        let text = match &item.action {
            Action::Edit { text, .. } => Some(text.clone()),
            Action::React { emoji, .. } => Some(emoji.clone()),
            _ => None,
        };
        Some((
            target,
            super::model::PendingOp {
                client_id: item.client_id,
                kind: item.action.kind().to_owned(),
                state: item.state,
                text,
            },
        ))
    })
    .collect())
}

/// The newest send of `chat` in the outbox and how many wait, for the chat list.
pub(super) fn chat_tail(conn: &Connection, chat: &str) -> Result<(Option<MessageRow>, u32)> {
    let mut rows = pending_rows(conn, chat)?;
    let waiting = rows.len() as u32;
    Ok((
        if rows.is_empty() {
            None
        } else {
            Some(rows.swap_remove(0))
        },
        waiting,
    ))
}

/// Confirms the outbox send that `message` (my own, new to the store) is the echo of. Only rows whose attempt
/// got no definite answer can match. Returns the client id of the confirmed row.
pub(super) fn reconcile_echo(conn: &Connection, message: &WaMessage) -> Result<Option<String>> {
    if !message.from_me {
        return Ok(None);
    }
    let candidates = items_where(
        conn,
        "chat = ?1 AND maybe_sent = 1 AND kind IN ('send_text', 'send_media')",
        Some(&message.chat),
    )?;
    for candidate in candidates {
        let attempted_ms: Option<i64> = conn.query_row(
            "SELECT attempted_ms FROM outbox WHERE client_id = ?1",
            [&candidate.client_id],
            |row| row.get(0),
        )?;
        let recent = attempted_ms.is_some_and(|at| message.timestamp_ms >= at - ECHO_SKEW_MS);
        if recent && candidate.action.echoes(&message.body) {
            conn.execute(
                "UPDATE messages SET client_id = ?3 WHERE chat = ?1 AND id = ?2",
                params![message.chat, message.id, candidate.client_id],
            )?;
            conn.execute(
                "DELETE FROM outbox WHERE client_id = ?1",
                [&candidate.client_id],
            )?;
            return Ok(Some(candidate.client_id));
        }
    }
    Ok(None)
}

/// Shows an action on the stored rows at once: a read mark clears the unread count, a reaction replaces mine, a
/// star sets the flag.
fn apply_optimistic(conn: &Connection, chat: &str, action: &Action) -> Result<()> {
    match action {
        Action::MarkRead { .. } => {
            conn.execute(
                "UPDATE chats SET unread = 0, marked_unread = 0 WHERE jid = ?1",
                [chat],
            )?;
        }
        Action::React {
            message_id, emoji, ..
        } => {
            let stored: Option<String> = conn
                .query_row(
                    "SELECT reactions FROM messages WHERE chat = ?1 AND id = ?2",
                    params![chat, message_id],
                    |row| row.get(0),
                )
                .optional()?;
            if let Some(stored) = stored {
                let mut reactions: Vec<Reaction> =
                    serde_json::from_str(&stored).unwrap_or_default();
                reactions.retain(|reaction| !reaction.from_me);
                if !emoji.is_empty() {
                    reactions.push(Reaction {
                        sender: None,
                        from_me: true,
                        emoji: emoji.clone(),
                        sender_alt: None,
                    });
                }
                conn.execute(
                    "UPDATE messages SET reactions = ?3 WHERE chat = ?1 AND id = ?2",
                    params![chat, message_id, serde_json::to_string(&reactions)?],
                )?;
            }
        }
        Action::Star {
            message_id,
            starred,
        } => set_starred(conn, chat, message_id, *starred)?,
        Action::SendText { .. }
        | Action::SendMedia { .. }
        | Action::Edit { .. }
        | Action::Revoke { .. } => {}
    }
    Ok(())
}

/// Takes the stars of message `id` that have not gone out from the outbox: a newer one replaces them.
fn supersede_stars(conn: &Connection, chat: &str, id: &str) -> Result<()> {
    let waiting = items_where(
        conn,
        "chat = ?1 AND kind = 'star' AND state != 'sending'",
        Some(chat),
    )?;
    for item in waiting {
        if item.action.target() == Some(id) {
            conn.execute("DELETE FROM outbox WHERE client_id = ?1", [&item.client_id])?;
        }
    }
    Ok(())
}

impl Store {
    /// Queues `action` for `chat` under `client_id` (the caller's random id). Queuing the same client id again
    /// changes nothing and answers the stored row, so a retried call cannot double. A star replaces the stars of the
    /// same message that have not gone out, so a star and its unstar written offline are one call.
    pub fn outbox_enqueue(
        &mut self,
        client_id: &str,
        chat: &str,
        action: &Action,
        now_ms: i64,
    ) -> Result<OutboxItem> {
        if client_id.is_empty() || chat.is_empty() {
            return Err(Error::Invalid(
                "client id and chat must not be empty".into(),
            ));
        }
        let tx = self.conn.transaction()?;
        if let Some(existing) = item(&tx, client_id)? {
            return Ok(existing);
        }
        if let Action::Star { message_id, .. } = action {
            supersede_stars(&tx, chat, message_id)?;
        }
        tx.execute(
            "INSERT INTO outbox (client_id, chat, kind, payload, created_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![client_id, chat, action.kind(), serde_json::to_string(action)?, now_ms],
        )?;
        if action.is_send() {
            apply::ensure_chat(&tx, chat)?;
            tx.execute("UPDATE chats SET hidden = 0 WHERE jid = ?1", [chat])?;
        }
        apply_optimistic(&tx, chat, action)?;
        let queued = item(&tx, client_id)?
            .ok_or_else(|| Error::NotFound(format!("outbox row {client_id}")))?;
        tx.commit()?;
        Ok(queued)
    }

    /// The rows to send now and marks them `sending`. Per chat only the oldest unsettled row is considered, so a
    /// chat's rows go out in the order written; a row that waits for its retry holds the later ones back.
    /// `network_back` (the connection just returned) ends every backoff: the waits were for a network that is back.
    /// Call it after the events missed while offline were applied, so an echo confirms a send before it is retried.
    pub fn outbox_due(&mut self, now_ms: i64, network_back: bool) -> Result<Due> {
        let tx = self.conn.transaction()?;
        if network_back {
            tx.execute(
                "UPDATE outbox SET next_attempt_ms = 0 WHERE state = 'waiting'",
                [],
            )?;
        }
        let heads = items_where(
            &tx,
            "state != 'failed' AND seq = (SELECT MIN(seq) FROM outbox AS first
                WHERE first.chat = outbox.chat AND first.state != 'failed')",
            None,
        )?;
        let mut due = Vec::new();
        let mut held = 0;
        let mut next_wake_ms: Option<i64> = None;
        let mut wake =
            |at: i64| next_wake_ms = Some(next_wake_ms.map_or(at, |soonest| soonest.min(at)));
        for mut head in heads {
            let leased_until: Option<i64> = match head.state {
                OutboxState::Sending => tx
                    .query_row(
                        "SELECT attempted_ms FROM outbox WHERE client_id = ?1",
                        [&head.client_id],
                        |row| row.get::<_, Option<i64>>(0),
                    )?
                    .map(|at| at.saturating_add(SENDING_LEASE_MS)),
                _ => None,
            };
            let ready_at = leased_until.unwrap_or(head.next_attempt_ms);
            if ready_at > now_ms {
                wake(ready_at);
                continue;
            }
            // An earlier attempt may have gone out. Until the missed events are in (the chat list refreshed, the
            // chat's gap closed) its echo may still be on the way: sending now could double it.
            let unsure: bool = tx.query_row(
                "SELECT maybe_sent = 1 AND ((SELECT needs_resync FROM sync_state WHERE id = 1) = 1
                    OR EXISTS (SELECT 1 FROM chat_sync WHERE jid = outbox.chat AND gap_open = 1))
                 FROM outbox WHERE client_id = ?1",
                [&head.client_id],
                |row| row.get(0),
            )?;
            if unsure {
                held += 1;
                continue;
            }
            head.attempts += 1;
            head.state = OutboxState::Sending;
            tx.execute(
                "UPDATE outbox SET state = 'sending', attempts = ?2, attempted_ms = ?3, maybe_sent = ?4
                 WHERE client_id = ?1",
                params![head.client_id, head.attempts, now_ms, head.action.is_send()],
            )?;
            wake(now_ms.saturating_add(SENDING_LEASE_MS));
            due.push(head);
        }
        tx.commit()?;
        Ok(Due {
            items: due,
            held,
            next_wake_ms,
        })
    }

    /// Reports the answer to an attempt. A row that is gone (its echo confirmed it, or it was cancelled) is `Done`.
    pub fn outbox_settle(
        &mut self,
        client_id: &str,
        outcome: &Outcome,
        now_ms: i64,
    ) -> Result<Settled> {
        let tx = self.conn.transaction()?;
        let Some(row) = item(&tx, client_id)? else {
            let message_id = tx
                .query_row(
                    "SELECT id FROM messages WHERE client_id = ?1",
                    [client_id],
                    |row| row.get(0),
                )
                .optional()?;
            return Ok(Settled::Done { message_id });
        };
        let settled = match outcome {
            Outcome::Sent {
                message_id,
                timestamp_ms,
            } => {
                if let (Some(content), Some(message_id)) = (row.action.content(), message_id) {
                    confirm_send(
                        &tx,
                        &row,
                        &content,
                        message_id,
                        timestamp_ms.unwrap_or(now_ms),
                    )?;
                }
                apply_sent(&tx, &row, now_ms)?;
                tx.execute("DELETE FROM outbox WHERE client_id = ?1", [client_id])?;
                Settled::Done {
                    message_id: message_id.clone().filter(|_| row.action.is_send()),
                }
            }
            // A refused star has nothing to retry or edit: it is undone and dropped, unless a newer star of the
            // message waits (that one decides the flag).
            Outcome::Error {
                error: ErrorKind::Rejected,
                ..
            } if matches!(row.action, Action::Star { .. }) => {
                tx.execute("DELETE FROM outbox WHERE client_id = ?1", [client_id])?;
                if let Action::Star {
                    message_id,
                    starred,
                } = &row.action
                    && pending_star(&tx, &row.chat, message_id)?.is_none()
                {
                    set_starred(&tx, &row.chat, message_id, !starred)?;
                }
                Settled::Failed
            }
            Outcome::Error {
                error: ErrorKind::Rejected,
                message,
                ..
            } => {
                tx.execute(
                    "UPDATE outbox SET state = 'failed', last_error = ?2, maybe_sent = 0 WHERE client_id = ?1",
                    params![client_id, message],
                )?;
                Settled::Failed
            }
            Outcome::Error {
                error,
                message,
                retry_after_ms,
            } => {
                let wait = backoff_ms(row.attempts).max(retry_after_ms.unwrap_or(0));
                let next_attempt_ms = now_ms.saturating_add(wait);
                // Without an answer the gateway may still have sent it: keep the row open to its echo.
                let maybe_sent = *error == ErrorKind::Network && row.action.is_send();
                tx.execute(
                    "UPDATE outbox SET state = 'waiting', last_error = ?2, next_attempt_ms = ?3, maybe_sent = ?4
                     WHERE client_id = ?1",
                    params![client_id, message, next_attempt_ms, maybe_sent],
                )?;
                Settled::Waiting { next_attempt_ms }
            }
        };
        tx.commit()?;
        Ok(settled)
    }

    /// Takes a row out of the outbox (the owner cancelled it). `false` when it is gone already or going out right
    /// now (a send in flight cannot be taken back; delete the message once it is sent).
    pub fn outbox_cancel(&mut self, client_id: &str) -> Result<bool> {
        let removed = self.conn.execute(
            "DELETE FROM outbox WHERE client_id = ?1 AND state != 'sending'",
            [client_id],
        )?;
        Ok(removed > 0)
    }

    /// Changes the text (or caption) of a send that has not gone out. A failed row waits again.
    pub fn outbox_edit(&mut self, client_id: &str, text: &str) -> Result<OutboxItem> {
        let tx = self.conn.transaction()?;
        let mut row = item(&tx, client_id)?.ok_or_else(|| {
            Error::NotFound(format!("outbox row {client_id} (sent or cancelled)"))
        })?;
        if row.state == OutboxState::Sending {
            return Err(Error::Invalid("the message is going out right now".into()));
        }
        match &mut row.action {
            Action::SendText { text: stored, .. } | Action::Edit { text: stored, .. } => {
                text.clone_into(stored);
            }
            Action::SendMedia { caption, .. } => {
                *caption = (!text.is_empty()).then(|| text.to_owned());
            }
            Action::Revoke { .. }
            | Action::React { .. }
            | Action::MarkRead { .. }
            | Action::Star { .. } => {
                return Err(Error::Invalid("only a message text can be edited".into()));
            }
        }
        tx.execute(
            "UPDATE outbox SET payload = ?2, state = 'waiting', next_attempt_ms = 0, last_error = NULL,
                maybe_sent = 0
             WHERE client_id = ?1",
            params![client_id, serde_json::to_string(&row.action)?],
        )?;
        let edited = item(&tx, client_id)?
            .ok_or_else(|| Error::NotFound(format!("outbox row {client_id}")))?;
        tx.commit()?;
        Ok(edited)
    }

    /// Lets a failed row (or one waiting for its retry) go out at the next [`Self::outbox_due`].
    pub fn outbox_retry(&mut self, client_id: &str) -> Result<bool> {
        let changed = self.conn.execute(
            "UPDATE outbox SET state = 'waiting', next_attempt_ms = 0 WHERE client_id = ?1 AND state != 'sending'",
            [client_id],
        )?;
        Ok(changed > 0)
    }

    /// Every row of the outbox in the order written (settings, diagnostics, tests).
    pub fn outbox(&self) -> Result<Vec<OutboxItem>> {
        items_where(&self.conn, "1", None)
    }
}

/// Shows an edit, a delete or a star that went out on the stored row at once; the gateway's event says the same
/// later.
fn apply_sent(conn: &Connection, row: &OutboxItem, now_ms: i64) -> Result<()> {
    match &row.action {
        Action::Edit { message_id, text } => {
            let is_text: bool = conn
                .query_row(
                    "SELECT kind = 'text' FROM messages WHERE chat = ?1 AND id = ?2",
                    params![row.chat, message_id],
                    |found| found.get(0),
                )
                .optional()?
                .unwrap_or(false);
            if is_text {
                let body = MessageBody::Text(text.clone());
                apply::upsert(
                    conn,
                    &row.chat,
                    message_id,
                    &apply::Incoming {
                        base: None,
                        body: Some(&body),
                        meta: None,
                        event_ms: None,
                        now_ms,
                    },
                )?;
            }
        }
        Action::Revoke { message_id, .. } => {
            apply::mark_deleted(
                conn,
                &row.chat,
                message_id,
                Some(now_ms),
                rosty_wa_proto::DELETED_BY_ME,
            )?;
        }
        // `row` is still in the outbox here: the newest star of the message decides the flag.
        Action::Star {
            message_id,
            starred,
        } => {
            let newest = pending_star(conn, &row.chat, message_id)?;
            set_starred(conn, &row.chat, message_id, newest.unwrap_or(*starred))?;
        }
        Action::SendText { .. }
        | Action::SendMedia { .. }
        | Action::React { .. }
        | Action::MarkRead { .. } => {}
    }
    Ok(())
}

/// Stores the message of a confirmed send, unless its echo already did; either way it carries the client id.
fn confirm_send(
    conn: &Connection,
    row: &OutboxItem,
    content: &Content,
    message_id: &str,
    timestamp_ms: i64,
) -> Result<()> {
    let echoed = conn.execute(
        "UPDATE messages SET client_id = ?3 WHERE chat = ?1 AND id = ?2",
        params![row.chat, message_id, row.client_id],
    )?;
    if echoed > 0 {
        return Ok(());
    }
    let body = match content.kind {
        Kind::Text => MessageBody::Text(content.text.clone().unwrap_or_default()),
        _ => match &row.action {
            Action::SendMedia {
                media,
                mime,
                size,
                caption,
                ..
            } => MessageBody::Media {
                kind: *media,
                caption: caption.clone(),
                mime: mime.clone(),
                size: *size,
            },
            _ => MessageBody::Unsupported,
        },
    };
    let message = WaMessage {
        id: message_id.to_owned(),
        chat: row.chat.clone(),
        sender: None,
        from_me: true,
        timestamp_ms,
        body,
        reactions: Vec::new(),
        chat_alt: None,
        sender_alt: None,
    };
    let meta = rosty_wa_proto::MessageMeta {
        quoted: row.action.quoted_id().map(|id| rosty_wa_proto::Quoted {
            id: id.to_owned(),
            sender: None,
            preview: None,
        }),
        status: Some(rosty_wa_proto::DeliveryStatus::Sent),
        ..Default::default()
    };
    apply::upsert(
        conn,
        &row.chat,
        message_id,
        &apply::Incoming {
            base: Some(&message),
            body: None,
            meta: Some(&meta),
            event_ms: None,
            now_ms: timestamp_ms,
        },
    )?;
    conn.execute(
        "UPDATE messages SET client_id = ?3 WHERE chat = ?1 AND id = ?2",
        params![row.chat, message_id, row.client_id],
    )?;
    // The file that was sent is the cached full media of the message.
    if let Action::SendMedia { file, size, .. } = &row.action {
        conn.execute(
            "UPDATE messages SET media_path = ?3, media_bytes = ?4, media_used_ms = ?5
             WHERE chat = ?1 AND id = ?2 AND media_path IS NULL",
            params![row.chat, message_id, file, size, timestamp_ms],
        )?;
    }
    Ok(())
}
