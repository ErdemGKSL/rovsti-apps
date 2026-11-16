//! Stored rows follow a key change: everything under `from_key` moves under `into_key`.
//!
//! A step is a row of `merge_log`; `sync_state.merge_cursor` is the last one applied. A step is a function of the
//! stored rows and its two keys only (it never asks the identity map), so replaying the log after a crash gives
//! the same rows, and a step applied twice changes nothing the second time.
//!
//! What one step does:
//! * Messages of chat `from` move to chat `into`. A message stored in both is kept once: the row of `into`, with
//!   the star, the thumbnail, the client id, the higher tick, the longer edit history and the reactions of both;
//!   the sender the other row had stays as its wire sender.
//! * Media rows move with their messages. A message stored in both keeps one media row: the one that holds the
//!   cached file, else the one nearer to it ([`media::follow_merge`]).
//! * Every row of every chat that names `from` as an author names `into`: the sender (its old id stays in
//!   `wire_sender`), who deleted it, who reacted (one reaction per person, the one listed last), the author of the
//!   quoted message, the mentions.
//! * The chat row: unread summed, pinned / archived / marked unread when either was, muted until the later time,
//!   cleared up to the later time, hidden only when both were, names and picture of `into` else of `from`. The id
//!   the chat was known under stays in `wire_jid`.
//! * The cursor: newest of both, oldest of both, the start reached only when both reached it, a gap open in either
//!   stays open from the lower floor. Then the chat list must be refreshed.
//! * Outbox rows follow, and the senders their actions name.
//!
//! The store holds no per-recipient receipts (ticks are one status per message, the higher one wins above) and no
//! scheduled sends; both live on the gateway.

use rosty_wa_proto::{DeliveryStatus, MUTED_FOREVER};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;

use super::apply::{self, Stored};
use super::authors::{self, JsonRefs, Moved};
use super::outbox::Action;
use super::{Result, media};

/// Rows stored under `from_key` now live under `into_key`: move what is kept per chat outside the store (the
/// draft, the prefs, the notification tag, the open thread).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ChatRekeyed {
    /// The step's place in the log, for a consumer's own cursor.
    pub seq: u64,
    pub from_key: String,
    pub into_key: String,
}

fn read_step(row: &rusqlite::Row<'_>) -> rusqlite::Result<ChatRekeyed> {
    Ok(ChatRekeyed {
        seq: row.get(0)?,
        from_key: row.get(1)?,
        into_key: row.get(2)?,
    })
}

/// The steps logged after `seq`, oldest first.
pub(super) fn logged_since(conn: &Connection, seq: u64) -> Result<Vec<ChatRekeyed>> {
    Ok(conn
        .prepare("SELECT seq, from_key, into_key FROM merge_log WHERE seq > ?1 ORDER BY seq")?
        .query_map([seq], read_step)?
        .collect::<rusqlite::Result<_>>()?)
}

/// Applies the oldest step the stored rows have not followed and moves the cursor past it, in the caller's
/// transaction. The answer is the step and whether it moved a chat ([`apply_step`]); `None` when the rows are up
/// to date.
pub(super) fn apply_next(conn: &Connection) -> Result<Option<(ChatRekeyed, bool)>> {
    let next = conn
        .prepare_cached(
            "SELECT seq, from_key, into_key FROM merge_log
             WHERE seq > (SELECT merge_cursor FROM sync_state WHERE id = 1) ORDER BY seq LIMIT 1",
        )?
        .query_row([], read_step)
        .optional()?;
    let Some(step) = next else {
        return Ok(None);
    };
    let moved = apply_step(conn, &step.from_key, &step.into_key)?;
    conn.execute(
        "UPDATE sync_state SET merge_cursor = ?1 WHERE id = 1",
        [step.seq],
    )?;
    Ok(Some((step, moved)))
}

/// Applies every step not applied yet, in the caller's transaction.
pub(super) fn apply_pending(conn: &Connection) -> Result<Vec<ChatRekeyed>> {
    let mut rekeyed = Vec::new();
    while let Some((step, _)) = apply_next(conn)? {
        rekeyed.push(step);
    }
    Ok(rekeyed)
}

/// Moves everything stored under `from` to `into`. True when a chat, a message, a cursor or an outbox row moved
/// (the chat list then needs a refresh); author references in other chats alone do not count.
pub(super) fn apply_step(conn: &Connection, from: &str, into: &str) -> Result<bool> {
    if from == into || from.is_empty() || into.is_empty() {
        return Ok(false);
    }
    let mut moved = move_messages(conn, from, into)?;
    moved |= media::follow_merge(conn, from, into)?;
    rewrite_authors(conn, from, into)?;
    moved |= merge_chat_rows(conn, from, into)?;
    moved |= merge_cursors(conn, from, into)?;
    moved |= move_outbox(conn, from, into)?;
    if moved {
        apply::ensure_chat(conn, into)?;
        conn.execute("UPDATE sync_state SET needs_resync = 1 WHERE id = 1", [])?;
    }
    Ok(moved)
}

// ---- messages

fn later_status(a: Option<DeliveryStatus>, b: Option<DeliveryStatus>) -> Option<DeliveryStatus> {
    match (a, b) {
        (Some(a), b) => Some(a.merge(b)),
        (None, b) => b,
    }
}

/// One message stored under both keys: `keep` (the row of `into`) with what only `other` has.
fn combine(mut keep: Stored, other: Stored) -> Stored {
    keep.sender = keep.sender.or(other.sender);
    keep.sender_name = keep.sender_name.or(other.sender_name);
    if !keep.content.is_known() && other.content.is_known() {
        keep.content = other.content;
    }
    keep.quoted = keep.quoted.or(other.quoted);
    // Those of `keep` are listed last, so they win where one person reacted in both rows.
    let mut reactions = other.reactions;
    reactions.append(&mut keep.reactions);
    authors::one_per_person(&mut reactions);
    keep.reactions = reactions;
    keep.status = later_status(keep.status, other.status);
    if other.edit_history.len() > keep.edit_history.len() {
        keep.edit_history = other.edit_history;
        keep.edited_ms = other.edited_ms.or(keep.edited_ms);
    } else {
        keep.edited_ms = keep.edited_ms.or(other.edited_ms);
    }
    keep.deleted |= other.deleted;
    keep.deleted_at = keep.deleted_at.or(other.deleted_at);
    keep.deleted_by = keep.deleted_by.or(other.deleted_by);
    keep.forwarded |= other.forwarded;
    keep.starred |= other.starred;
    keep.media = keep.media.or(other.media);
    keep.extra.location = keep.extra.location.or(other.extra.location);
    keep.extra.contact = keep.extra.contact.or(other.extra.contact);
    if keep.extra.mentions.is_empty() {
        keep.extra.mentions = other.extra.mentions;
    }
    if !keep.content.is_known() {
        keep.extra.unavailable = keep.extra.unavailable.or(other.extra.unavailable);
    }
    keep
}

/// Moves the messages of `from` to `into`; a message both chats hold becomes one row.
fn move_messages(conn: &Connection, from: &str, into: &str) -> Result<bool> {
    let both: Vec<String> = conn
        .prepare(
            "SELECT f.id FROM messages AS f JOIN messages AS i ON i.chat = ?2 AND i.id = f.id WHERE f.chat = ?1",
        )?
        .query_map(params![from, into], |row| row.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    for id in &both {
        let (Some(keep), Some(other)) =
            (apply::load(conn, into, id)?, apply::load(conn, from, id)?)
        else {
            continue;
        };
        apply::write(conn, into, id, &combine(keep, other))?;
        // What only this device knows about the message: whichever row has it.
        conn.execute(
            "UPDATE messages SET
                thumb = COALESCE(messages.thumb, f.thumb),
                client_id = COALESCE(messages.client_id, f.client_id),
                viewed_ms = COALESCE(messages.viewed_ms, f.viewed_ms),
                played_ms = COALESCE(messages.played_ms, f.played_ms),
                wire_sender = COALESCE(messages.wire_sender, f.wire_sender,
                    CASE WHEN f.sender IS NOT messages.sender THEN f.sender END)
             FROM (SELECT * FROM messages WHERE chat = ?1 AND id = ?3) AS f
             WHERE messages.chat = ?2 AND messages.id = ?3",
            params![from, into, id],
        )?;
        conn.execute(
            "DELETE FROM messages WHERE chat = ?1 AND id = ?2",
            params![from, id],
        )?;
    }
    let moved = conn.execute(
        "UPDATE messages SET chat = ?2 WHERE chat = ?1",
        params![from, into],
    )?;
    Ok(moved > 0 || !both.is_empty())
}

// ---- authors, in every chat

/// `text` as it reads inside a JSON string: what a JSON column that names it contains.
fn json_needle(text: &str) -> Result<String> {
    let quoted = serde_json::to_string(text)?;
    Ok(quoted[1..quoted.len() - 1].to_owned())
}

fn rewrite_authors(conn: &Connection, from: &str, into: &str) -> Result<()> {
    conn.execute(
        "UPDATE messages SET wire_sender = COALESCE(wire_sender, sender), sender = ?2 WHERE sender = ?1",
        params![from, into],
    )?;
    conn.execute(
        "UPDATE messages SET deleted_by = ?2 WHERE deleted_by = ?1",
        params![from, into],
    )?;
    let mut to_into = |text: &str| {
        Ok((text == from).then(|| Moved {
            to: into.to_owned(),
            wire: from.to_owned(),
        }))
    };
    let named: Vec<(String, String, JsonRefs)> = conn
        .prepare(&format!(
            "SELECT chat, id, {} FROM messages
             WHERE instr(reactions, ?1) > 0 OR instr(quoted_json, ?1) > 0 OR instr(extra_json, ?1) > 0",
            JsonRefs::COLUMNS
        ))?
        .query_map([json_needle(from)?], |row| {
            Ok((row.get(0)?, row.get(1)?, JsonRefs::read(row, 2)?))
        })?
        .collect::<rusqlite::Result<_>>()?;
    for (chat, id, refs) in named {
        if let Some(rewritten) = refs.rewritten(&mut to_into)? {
            rewritten.write(conn, &chat, &id)?;
        }
    }
    Ok(())
}

// ---- the chat row

/// What a chat row holds besides its key.
#[derive(Clone, Debug, PartialEq, Eq)]
struct ChatState {
    name: Option<String>,
    push_name: Option<String>,
    unread: i64,
    pinned: bool,
    archived: bool,
    muted_until_ms: Option<i64>,
    marked_unread: bool,
    picture_id: Option<String>,
    hidden: bool,
    cleared_ts: Option<i64>,
    wire_jid: Option<String>,
}

/// The later of two mute ends; forever outlasts every time.
pub(super) fn later_mute(a: Option<i64>, b: Option<i64>) -> Option<i64> {
    match (a, b) {
        (Some(MUTED_FOREVER), _) | (_, Some(MUTED_FOREVER)) => Some(MUTED_FOREVER),
        (a, b) => a.max(b),
    }
}

impl ChatState {
    fn load(conn: &Connection, jid: &str) -> Result<Option<Self>> {
        Ok(conn
            .query_row(
                "SELECT name, push_name, unread, pinned, archived, muted_until_ms, marked_unread, picture_id,
                    hidden, cleared_ts, wire_jid
                 FROM chats WHERE jid = ?1",
                [jid],
                |row| {
                    Ok(Self {
                        name: row.get(0)?,
                        push_name: row.get(1)?,
                        unread: row.get(2)?,
                        pinned: row.get(3)?,
                        archived: row.get(4)?,
                        muted_until_ms: row.get(5)?,
                        marked_unread: row.get(6)?,
                        picture_id: row.get(7)?,
                        hidden: row.get(8)?,
                        cleared_ts: row.get(9)?,
                        wire_jid: row.get(10)?,
                    })
                },
            )
            .optional()?)
    }

    /// The row of `into` after the chat stored under `from_key` joined it.
    fn joined(self, from: Self, from_key: &str) -> Self {
        Self {
            name: self.name.or(from.name),
            push_name: self.push_name.or(from.push_name),
            unread: self.unread.saturating_add(from.unread),
            pinned: self.pinned || from.pinned,
            archived: self.archived || from.archived,
            muted_until_ms: later_mute(self.muted_until_ms, from.muted_until_ms),
            marked_unread: self.marked_unread || from.marked_unread,
            picture_id: self.picture_id.or(from.picture_id),
            hidden: self.hidden && from.hidden,
            cleared_ts: self.cleared_ts.max(from.cleared_ts),
            wire_jid: self
                .wire_jid
                .or(from.wire_jid)
                .or_else(|| Some(from_key.to_owned())),
        }
    }
}

fn merge_chat_rows(conn: &Connection, from: &str, into: &str) -> Result<bool> {
    let Some(old) = ChatState::load(conn, from)? else {
        return Ok(false);
    };
    let Some(kept) = ChatState::load(conn, into)? else {
        // Nothing under the new key: the chat is renamed.
        conn.execute(
            "UPDATE chats SET jid = ?2, wire_jid = COALESCE(wire_jid, ?1) WHERE jid = ?1",
            params![from, into],
        )?;
        return Ok(true);
    };
    let joined = kept.joined(old, from);
    conn.execute(
        "UPDATE chats SET name = ?2, push_name = ?3, unread = ?4, pinned = ?5, archived = ?6, muted_until_ms = ?7,
            marked_unread = ?8, picture_id = ?9, hidden = ?10, cleared_ts = ?11, wire_jid = ?12
         WHERE jid = ?1",
        params![
            into,
            joined.name,
            joined.push_name,
            joined.unread,
            joined.pinned,
            joined.archived,
            joined.muted_until_ms,
            joined.marked_unread,
            joined.picture_id,
            joined.hidden,
            joined.cleared_ts,
            joined.wire_jid,
        ],
    )?;
    conn.execute("DELETE FROM chats WHERE jid = ?1", [from])?;
    Ok(true)
}

// ---- the cursor

/// A row of `chat_sync`.
#[derive(Clone, Debug, PartialEq, Eq)]
struct CursorRow {
    newest: Option<(i64, Option<String>)>,
    oldest_ts: Option<i64>,
    reached_start: bool,
    /// The floor of an open gap.
    gap_floor_ts: Option<i64>,
    gap_before: Option<i64>,
}

impl CursorRow {
    fn load(conn: &Connection, jid: &str) -> Result<Option<Self>> {
        Ok(conn
            .query_row(
                "SELECT newest_ts, newest_id, oldest_ts, reached_start, gap_open, gap_floor_ts, gap_before
                 FROM chat_sync WHERE jid = ?1",
                [jid],
                |row| {
                    let newest_ts: Option<i64> = row.get(0)?;
                    let newest_id: Option<String> = row.get(1)?;
                    let open: bool = row.get(4)?;
                    let floor: Option<i64> = row.get(5)?;
                    Ok(Self {
                        newest: newest_ts.map(|ts| (ts, newest_id)),
                        oldest_ts: row.get(2)?,
                        reached_start: row.get(3)?,
                        gap_floor_ts: floor.filter(|_| open),
                        gap_before: row.get(6)?,
                    })
                },
            )
            .optional()?)
    }

    /// The cursor of the two chats as one. A gap that was open in one of them starts over from the top: its
    /// `before=` described that chat alone.
    fn joined(self, from: Self) -> Self {
        let gap_floor_ts = match (self.gap_floor_ts, from.gap_floor_ts) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        };
        Self {
            newest: self.newest.max(from.newest),
            oldest_ts: match (self.oldest_ts, from.oldest_ts) {
                (Some(a), Some(b)) => Some(a.min(b)),
                (a, b) => a.or(b),
            },
            reached_start: self.reached_start && from.reached_start,
            gap_floor_ts,
            gap_before: None,
        }
    }
}

fn merge_cursors(conn: &Connection, from: &str, into: &str) -> Result<bool> {
    let Some(old) = CursorRow::load(conn, from)? else {
        return Ok(false);
    };
    let Some(kept) = CursorRow::load(conn, into)? else {
        conn.execute(
            "UPDATE chat_sync SET jid = ?2 WHERE jid = ?1",
            params![from, into],
        )?;
        return Ok(true);
    };
    let joined = kept.joined(old);
    let (newest_ts, newest_id) = joined.newest.unzip();
    conn.execute(
        "UPDATE chat_sync SET newest_ts = ?2, newest_id = ?3, oldest_ts = ?4, reached_start = ?5, gap_open = ?6,
            gap_floor_ts = ?7, gap_before = ?8
         WHERE jid = ?1",
        params![
            into,
            newest_ts,
            newest_id.flatten(),
            joined.oldest_ts,
            joined.reached_start,
            joined.gap_floor_ts.is_some(),
            joined.gap_floor_ts,
            joined.gap_before,
        ],
    )?;
    conn.execute("DELETE FROM chat_sync WHERE jid = ?1", [from])?;
    Ok(true)
}

// ---- the outbox

/// Renames the sender an action names. True when it did.
fn rename_sender(action: &mut Action, from: &str, into: &str) -> bool {
    let sender = match action {
        Action::Revoke { sender, .. }
        | Action::React { sender, .. }
        | Action::MarkRead { sender, .. }
        | Action::MarkPlayed { sender, .. } => sender,
        Action::SendText { .. }
        | Action::SendMedia { .. }
        | Action::Edit { .. }
        | Action::Star { .. } => return false,
    };
    if sender.as_deref() != Some(from) {
        return false;
    }
    *sender = Some(into.to_owned());
    true
}

/// Outbox rows of chat `from` go to `into` and keep their place in the order written (the outbox has one sequence
/// for the whole store). The senders named by waiting actions of every chat follow.
fn move_outbox(conn: &Connection, from: &str, into: &str) -> Result<bool> {
    let moved = conn.execute(
        "UPDATE outbox SET chat = ?2 WHERE chat = ?1",
        params![from, into],
    )?;
    let naming: Vec<(String, String)> = conn
        .prepare("SELECT client_id, payload FROM outbox WHERE instr(payload, ?1) > 0")?
        .query_map([json_needle(from)?], |row| Ok((row.get(0)?, row.get(1)?)))?
        .collect::<rusqlite::Result<_>>()?;
    for (client_id, payload) in naming {
        let Ok(mut action) = serde_json::from_str::<Action>(&payload) else {
            continue;
        };
        if rename_sender(&mut action, from, into) {
            conn.execute(
                "UPDATE outbox SET payload = ?2 WHERE client_id = ?1",
                params![client_id, serde_json::to_string(&action)?],
            )?;
        }
    }
    Ok(moved > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn chat(unread: i64) -> ChatState {
        ChatState {
            name: None,
            push_name: None,
            unread,
            pinned: false,
            archived: false,
            muted_until_ms: None,
            marked_unread: false,
            picture_id: None,
            hidden: false,
            cleared_ts: None,
            wire_jid: None,
        }
    }

    #[test]
    fn mute_keeps_the_later_end_and_forever_outlasts_a_time() {
        assert_eq!(later_mute(None, None), None);
        assert_eq!(later_mute(Some(5), None), Some(5));
        assert_eq!(later_mute(Some(5), Some(9)), Some(9));
        assert_eq!(
            later_mute(Some(MUTED_FOREVER), Some(9)),
            Some(MUTED_FOREVER)
        );
        assert_eq!(later_mute(None, Some(MUTED_FOREVER)), Some(MUTED_FOREVER));
    }

    #[test]
    fn chat_rows_join_as_the_rule_says() {
        let into = ChatState {
            name: Some("Ali".into()),
            pinned: true,
            hidden: true,
            cleared_ts: Some(10),
            ..chat(2)
        };
        let from = ChatState {
            name: Some("old name".into()),
            push_name: Some("ali".into()),
            archived: true,
            muted_until_ms: Some(50),
            marked_unread: true,
            picture_id: Some("p1".into()),
            cleared_ts: Some(30),
            ..chat(3)
        };
        assert_eq!(
            into.joined(from, "777000000000001@lid"),
            ChatState {
                name: Some("Ali".into()),
                push_name: Some("ali".into()),
                unread: 5,
                pinned: true,
                archived: true,
                muted_until_ms: Some(50),
                marked_unread: true,
                picture_id: Some("p1".into()),
                hidden: false,
                cleared_ts: Some(30),
                wire_jid: Some("777000000000001@lid".into()),
            }
        );
        let both_hidden = ChatState {
            hidden: true,
            ..chat(0)
        };
        assert!(both_hidden.clone().joined(both_hidden, "x").hidden);
    }

    #[test]
    fn cursors_join_to_the_widest_span_and_keep_a_gap_open() {
        let into = CursorRow {
            newest: Some((900, Some("B".into()))),
            oldest_ts: Some(500),
            reached_start: true,
            gap_floor_ts: None,
            gap_before: None,
        };
        let from = CursorRow {
            newest: Some((900, Some("C".into()))),
            oldest_ts: Some(100),
            reached_start: false,
            gap_floor_ts: Some(700),
            gap_before: Some(800),
        };
        assert_eq!(
            into.clone().joined(from.clone()),
            CursorRow {
                newest: Some((900, Some("C".into()))),
                oldest_ts: Some(100),
                reached_start: false,
                gap_floor_ts: Some(700),
                gap_before: None,
            }
        );
        let both_open = CursorRow {
            gap_floor_ts: Some(300),
            reached_start: true,
            ..into.clone()
        };
        let joined = both_open.joined(CursorRow {
            reached_start: true,
            ..from
        });
        assert_eq!(joined.gap_floor_ts, Some(300));
        assert!(joined.reached_start);
    }
}
