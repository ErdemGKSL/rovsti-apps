//! Writing what the gateway says: idempotent upserts by message id, soft deletes, edit history.

use rosty_wa_proto::event::ReceiptKind;
use rosty_wa_proto::{
    ChatMeta, DELETED_BY_ME, DeliveryStatus, EditRecord, EventEnvelope, GatewayEvent,
    HistorySyncType, Location, MediaMeta, MessageBody, MessageMeta, Quoted, Reaction, RichChat,
    SharedContact, Unavailable, WaMessage, is_status_chat,
};
use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize, de::DeserializeOwned};

use rosty_core::wa_identity::{Source, WaIdKind, parse};

use super::identity::{self, Canon, Ids};
use super::model::{AppliedEvents, Content, Kind};
use super::{Result, authors, link_state, outbox};

/// Location, contact card, mentions and the placeholder reason of a message, one JSON column.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub(super) struct Extra {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub location: Option<Location>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact: Option<SharedContact>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub mentions: Vec<String>,
    /// The message reached the gateway as a placeholder only (view once): nothing to show or fetch here.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unavailable: Option<Unavailable>,
}

/// The gateway-owned columns of a message row.
#[derive(Clone, Debug, Default, PartialEq)]
pub(super) struct Stored {
    pub sender: Option<String>,
    pub sender_name: Option<String>,
    pub from_me: bool,
    pub ts: i64,
    pub content: Content,
    pub quoted: Option<Quoted>,
    pub reactions: Vec<Reaction>,
    pub status: Option<DeliveryStatus>,
    pub edited_ms: Option<i64>,
    pub edit_history: Vec<EditRecord>,
    pub deleted: bool,
    pub deleted_at: Option<i64>,
    pub deleted_by: Option<String>,
    pub forwarded: bool,
    pub starred: bool,
    pub media: Option<MediaMeta>,
    pub extra: Extra,
}

/// The columns [`read`] expects, in order.
pub(super) const COLUMNS: &str = "sender, sender_name, from_me, ts, kind, text, mime, size, quoted_json, \
     reactions, status, edited_ms, edit_history, deleted, deleted_at, deleted_by, forwarded, starred, media_json, \
     extra_json";
/// How many columns [`COLUMNS`] names.
pub(super) const COLUMN_COUNT: usize = 20;

/// A JSON column; a missing or damaged value reads as the default instead of failing the whole query.
fn from_json<T: DeserializeOwned + Default>(text: Option<String>) -> T {
    text.and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn to_json<T: Serialize>(value: &T) -> Result<String> {
    Ok(serde_json::to_string(value)?)
}

fn opt_json<T: Serialize>(value: &Option<T>) -> Result<Option<String>> {
    value.as_ref().map(to_json).transpose()
}

pub(super) fn status_name(status: DeliveryStatus) -> &'static str {
    match status {
        DeliveryStatus::Sent => "sent",
        DeliveryStatus::Delivered => "delivered",
        DeliveryStatus::Read => "read",
        DeliveryStatus::Played => "played",
    }
}

fn status_from(name: Option<String>) -> Option<DeliveryStatus> {
    match name.as_deref()? {
        "sent" => Some(DeliveryStatus::Sent),
        "delivered" => Some(DeliveryStatus::Delivered),
        "read" => Some(DeliveryStatus::Read),
        "played" => Some(DeliveryStatus::Played),
        _ => None,
    }
}

/// Reads [`COLUMNS`] starting at column `at`.
pub(super) fn read(row: &Row<'_>, at: usize) -> rusqlite::Result<Stored> {
    let kind: String = row.get(at + 4)?;
    Ok(Stored {
        sender: row.get(at)?,
        sender_name: row.get(at + 1)?,
        from_me: row.get(at + 2)?,
        ts: row.get(at + 3)?,
        content: Content {
            kind: Kind::from_name(&kind),
            text: row.get(at + 5)?,
            mime: row.get(at + 6)?,
            size: row.get(at + 7)?,
        },
        quoted: from_json(row.get(at + 8)?),
        reactions: from_json(row.get(at + 9)?),
        status: status_from(row.get(at + 10)?),
        edited_ms: row.get(at + 11)?,
        edit_history: from_json(row.get(at + 12)?),
        deleted: row.get(at + 13)?,
        deleted_at: row.get(at + 14)?,
        deleted_by: row.get(at + 15)?,
        forwarded: row.get(at + 16)?,
        starred: row.get(at + 17)?,
        media: from_json(row.get(at + 18)?),
        extra: from_json(row.get(at + 19)?),
    })
}

pub(super) fn load(conn: &Connection, chat: &str, id: &str) -> Result<Option<Stored>> {
    Ok(conn
        .query_row(
            &format!("SELECT {COLUMNS} FROM messages WHERE chat = ?1 AND id = ?2"),
            params![chat, id],
            |row| read(row, 0),
        )
        .optional()?)
}

pub(super) fn write(conn: &Connection, chat: &str, id: &str, row: &Stored) -> Result<()> {
    conn.execute(
        "INSERT INTO messages (chat, id, sender, sender_name, from_me, ts, kind, text, mime, size, quoted_id,
            quoted_json, reactions, status, edited_ms, edit_history, deleted, deleted_at, deleted_by, forwarded,
            starred, media_json, extra_json)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21,
            ?22, ?23)
         ON CONFLICT (chat, id) DO UPDATE SET
            sender = excluded.sender, sender_name = excluded.sender_name, from_me = excluded.from_me,
            ts = excluded.ts, kind = excluded.kind, text = excluded.text, mime = excluded.mime,
            size = excluded.size, quoted_id = excluded.quoted_id, quoted_json = excluded.quoted_json,
            reactions = excluded.reactions, status = excluded.status, edited_ms = excluded.edited_ms,
            edit_history = excluded.edit_history, deleted = excluded.deleted, deleted_at = excluded.deleted_at,
            deleted_by = excluded.deleted_by, forwarded = excluded.forwarded, starred = excluded.starred,
            media_json = excluded.media_json, extra_json = excluded.extra_json",
        params![
            chat,
            id,
            row.sender,
            row.sender_name,
            row.from_me,
            row.ts,
            row.content.kind.as_str(),
            row.content.text,
            row.content.mime,
            row.content.size,
            row.quoted.as_ref().map(|quoted| quoted.id.as_str()),
            opt_json(&row.quoted)?,
            to_json(&row.reactions)?,
            row.status.map(status_name),
            row.edited_ms,
            to_json(&row.edit_history)?,
            row.deleted,
            row.deleted_at,
            row.deleted_by,
            row.forwarded,
            row.starred,
            opt_json(&row.media)?,
            (row.extra != Extra::default())
                .then(|| to_json(&row.extra))
                .transpose()?,
        ],
    )?;
    Ok(())
}

/// One thing the gateway said about a message.
pub(super) struct Incoming<'a> {
    /// The message itself (an event, a page row, the newest message of a chat); `None` for a meta-only update.
    pub base: Option<&'a WaMessage>,
    /// The new body of a meta-only update (an edit).
    pub body: Option<&'a MessageBody>,
    /// The meta, when this update carries it. A bare `message` event does not: its meta follows in its own event,
    /// so what is stored stays.
    pub meta: Option<&'a MessageMeta>,
    /// When the gateway said it (an event's `at_ms`): the delete time when the gateway names none. `None` for
    /// pages, where an unknown delete time stays unknown.
    pub event_ms: Option<i64>,
    /// The time of an edit when the gateway names none.
    pub now_ms: i64,
}

/// Merges `incoming` into what is stored. `None`: a meta-only update of a message the store does not have.
///
/// Content is never taken away: a deleted message arrives with an `unsupported` body, and what is stored (or what
/// the gateway kept in `meta.original`) stays. A text that changes moves the previous text into the edit history.
pub(super) fn merge(existing: Option<Stored>, incoming: &Incoming<'_>) -> Option<Stored> {
    let meta = incoming.meta;
    let deleted_now = meta.is_some_and(|meta| meta.deleted || meta.revoked);
    let body = incoming.base.map(|base| &base.body).or(incoming.body);
    let (content, content_meta) = if deleted_now {
        match meta.and_then(|meta| meta.original.as_deref()) {
            Some(kept) => (Some(Content::of_body(&kept.body)), Some(&kept.meta)),
            None => (None, None),
        }
    } else {
        (body.map(Content::of_body), meta)
    };

    let mut row = match (existing, incoming.base) {
        (Some(row), _) => row,
        (None, Some(_)) => Stored::default(),
        (None, None) => return None,
    };
    if let Some(base) = incoming.base {
        row.sender = base.sender.clone().or(row.sender);
        row.from_me = base.from_me;
        row.ts = base.timestamp_ms;
        row.reactions.clone_from(&base.reactions);
    }

    if let Some(new) = content.filter(Content::is_known) {
        let edited = row.content.kind == new.kind && row.content.text != new.text;
        if edited
            && !deleted_now
            && let Some(old) = row.content.text.take().filter(|old| !old.is_empty())
        {
            let edited_at = content_meta
                .and_then(|meta| meta.edited_ms)
                .unwrap_or(incoming.now_ms);
            row.edit_history.push(EditRecord {
                text: old,
                edited_at,
            });
            row.edited_ms = Some(edited_at);
        }
        row.content = new;
        // The content came after all: no longer a placeholder.
        row.extra.unavailable = None;
    }

    if let Some(meta) = content_meta {
        row.quoted = meta.quoted.clone().or(row.quoted);
        row.forwarded |= meta.forwarded;
        row.edited_ms = meta.edited_ms.or(row.edited_ms);
        // The gateway's history wins when it has at least as much as was collected here.
        if !meta.edit_history.is_empty() && meta.edit_history.len() >= row.edit_history.len() {
            row.edit_history.clone_from(&meta.edit_history);
        }
        row.media = meta.media.clone().or(row.media);
        row.extra.location = meta.location.clone().or(row.extra.location);
        row.extra.contact = meta.contact.clone().or(row.extra.contact);
        if !meta.mentions.is_empty() {
            row.extra.mentions.clone_from(&meta.mentions);
        }
        if !row.content.is_known() {
            row.extra.unavailable = meta.unavailable.or(row.extra.unavailable);
        }
    }
    if let Some(meta) = meta {
        row.sender_name = meta.sender_name.clone().or(row.sender_name);
        row.forwarded |= meta.forwarded;
        if row.from_me {
            row.status = match (row.status, meta.status) {
                (Some(stored), new) => Some(stored.merge(new)),
                (None, new) => new,
            };
        }
        if deleted_now {
            row.deleted = true;
            row.deleted_at = meta.deleted_at.or(row.deleted_at).or(incoming.event_ms);
            row.deleted_by = meta.deleted_by.clone().or(row.deleted_by);
        } else {
            row.starred = meta.starred;
        }
    }
    if !row.content.is_known() {
        if row.extra.location.is_some() {
            row.content.kind = Kind::Location;
        } else if row.extra.contact.is_some() {
            row.content.kind = Kind::Contact;
        }
    }
    Some(row)
}

/// What an upsert did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Upsert {
    New,
    Changed,
    Same,
    /// A meta-only update of a message the store does not have.
    Unknown,
}

/// Upserts one message and keeps the chat row and its cursor in step.
pub(super) fn upsert(
    conn: &Connection,
    chat: &str,
    id: &str,
    incoming: &Incoming<'_>,
) -> Result<Upsert> {
    let existing = load(conn, chat, id)?;
    let was_new = existing.is_none();
    let Some(mut merged) = merge(existing.clone(), incoming) else {
        return Ok(Upsert::Unknown);
    };
    // A star still in the outbox holds against what the gateway says until it went out.
    if let Some(was) = &existing
        && merged.starred != was.starred
        && let Some(wish) = outbox::pending_star(conn, chat, id)?
    {
        merged.starred = wish;
    }
    if existing.as_ref() == Some(&merged) {
        return Ok(Upsert::Same);
    }
    write(conn, chat, id, &merged)?;
    if was_new {
        ensure_chat(conn, chat)?;
        conn.execute(
            "UPDATE chats SET hidden = 0 WHERE jid = ?1 AND hidden = 1",
            [chat],
        )?;
    }
    bump_cursor(conn, chat, merged.ts, id)?;
    Ok(if was_new {
        Upsert::New
    } else {
        Upsert::Changed
    })
}

/// A message as the store keeps it: its chat and every author it names under their canonical keys, and the ids
/// they arrived under where those differ.
pub(super) struct Arrived {
    pub message: WaMessage,
    pub meta: Option<MessageMeta>,
    wire_chat: Option<String>,
    wire_sender: Option<String>,
}

/// Learns the pairs a list of reactions names: a reactor next to its other form.
fn learn_reactions(ids: &mut Ids<'_>, reactions: &[Reaction], at_ms: i64) -> Result<()> {
    for reaction in reactions {
        ids.learn_alt(
            reaction.sender.as_deref(),
            reaction.sender_alt.as_deref(),
            Source::PeerMessage,
            at_ms,
        )?;
    }
    Ok(())
}

/// The other form of a reactor is learned, not stored: a stored list names each person by their key only.
fn drop_alts(reactions: &mut [Reaction]) {
    for reaction in reactions {
        reaction.sender_alt = None;
    }
}

/// Learns the pairs a message names: the other form of a one-to-one chat, of its sender and of who reacted. A
/// group next to an id, or two ids of one kind, teach nothing.
pub(super) fn learn_message(ids: &mut Ids<'_>, message: &WaMessage) -> Result<()> {
    let at_ms = message.timestamp_ms;
    ids.learn_alt(
        Some(&message.chat),
        message.chat_alt.as_deref(),
        Source::PeerMessage,
        at_ms,
    )?;
    ids.learn_alt(
        message.sender.as_deref(),
        message.sender_alt.as_deref(),
        Source::PeerMessage,
        at_ms,
    )?;
    learn_reactions(ids, &message.reactions, at_ms)
}

/// Resolves what a message names before anything is matched against the store. The pairs it carries are learned
/// first, so its ids resolve to the merged person. An id seen for the first time becomes a person of its own, so
/// a pair learned later finds its rows.
pub(super) fn arrive(
    ids: &mut Ids<'_>,
    message: &WaMessage,
    meta: Option<&MessageMeta>,
) -> Result<Arrived> {
    learn_message(ids, message)?;
    let chat = ids.canon(&message.chat)?;
    let mut message = message.clone();
    message.chat = chat.key;
    (message.chat_alt, message.sender_alt) = (None, None);
    drop_alts(&mut message.reactions);
    let mut meta = meta.cloned();
    let mut resolve = |text: &str| ids.moved(text);
    let wire_sender = authors::id(&mut message.sender, &mut resolve)?
        .filter(|moved| moved.wire != moved.to)
        .map(|moved| moved.wire);
    authors::reactions(&mut message.reactions, &mut resolve)?;
    if let Some(meta) = &mut meta {
        authors::meta(meta, &mut resolve)?;
    }
    Ok(Arrived {
        message,
        meta,
        wire_chat: chat.wire,
        wire_sender,
    })
}

/// Remembers the id the gateway last used for a chat, when it is not the key the chat is stored under.
fn note_wire_jid(conn: &Connection, key: &str, wire: Option<&str>) -> Result<()> {
    if let Some(wire) = wire {
        conn.execute(
            "UPDATE chats SET wire_jid = ?2 WHERE jid = ?1 AND wire_jid IS NOT ?2",
            params![key, wire],
        )?;
    }
    Ok(())
}

/// Upserts a resolved message and keeps the ids it arrived under. The first wire sender of a message stays.
pub(super) fn upsert_arrived(
    conn: &Connection,
    arrived: &Arrived,
    event_ms: Option<i64>,
    now_ms: i64,
) -> Result<Upsert> {
    let message = &arrived.message;
    let done = upsert(
        conn,
        &message.chat,
        &message.id,
        &Incoming {
            base: Some(message),
            body: None,
            meta: arrived.meta.as_ref(),
            event_ms,
            now_ms,
        },
    )?;
    note_wire_jid(conn, &message.chat, arrived.wire_chat.as_deref())?;
    if let Some(wire) = &arrived.wire_sender {
        conn.execute(
            "UPDATE messages SET wire_sender = ?3 WHERE chat = ?1 AND id = ?2 AND wire_sender IS NULL",
            params![message.chat, message.id, wire],
        )?;
    }
    Ok(done)
}

pub(super) fn ensure_chat(conn: &Connection, jid: &str) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO chats (jid, is_group) VALUES (?1, ?2)",
        params![jid, jid.ends_with("@g.us")],
    )?;
    Ok(())
}

/// Widens the chat's newest/oldest stored message to include `(ts, id)`.
fn bump_cursor(conn: &Connection, chat: &str, ts: i64, id: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO chat_sync (jid, newest_ts, newest_id, oldest_ts) VALUES (?1, ?2, ?3, ?2)
         ON CONFLICT (jid) DO UPDATE SET
            newest_id = CASE
                WHEN newest_ts IS NULL OR excluded.newest_ts > newest_ts
                    OR (excluded.newest_ts = newest_ts AND excluded.newest_id > newest_id)
                THEN excluded.newest_id ELSE newest_id END,
            newest_ts = CASE
                WHEN newest_ts IS NULL OR excluded.newest_ts > newest_ts
                THEN excluded.newest_ts ELSE newest_ts END,
            oldest_ts = CASE
                WHEN oldest_ts IS NULL OR excluded.oldest_ts < oldest_ts
                THEN excluded.oldest_ts ELSE oldest_ts END",
        params![chat, ts, id],
    )?;
    Ok(())
}

/// Marks a message deleted. Its text, quote and media stay.
pub(super) fn mark_deleted(
    conn: &Connection,
    chat: &str,
    id: &str,
    deleted_at: Option<i64>,
    deleted_by: &str,
) -> Result<bool> {
    let changed = conn.execute(
        "UPDATE messages SET deleted = 1, deleted_at = COALESCE(deleted_at, ?3),
            deleted_by = COALESCE(deleted_by, ?4)
         WHERE chat = ?1 AND id = ?2 AND (deleted = 0 OR deleted_at IS NULL OR deleted_by IS NULL)",
        params![chat, id, deleted_at, deleted_by],
    )?;
    Ok(changed > 0)
}

fn set_chat_meta(conn: &Connection, jid: &str, meta: &ChatMeta) -> Result<()> {
    ensure_chat(conn, jid)?;
    conn.execute(
        "UPDATE chats SET archived = ?2, pinned = ?3, muted_until_ms = ?4, marked_unread = ?5,
            picture_id = ?6, push_name = COALESCE(?7, push_name)
         WHERE jid = ?1",
        params![
            jid,
            meta.archived,
            meta.pinned,
            meta.muted_until_ms,
            meta.marked_unread,
            meta.picture_id,
            meta.push_name,
        ],
    )?;
    Ok(())
}

/// A chat of the refreshed list: its row and its newest message, under `key`. The unread count is the gateway's,
/// except while a read mark of this chat still waits in the outbox.
pub(super) fn upsert_chat(
    ids: &mut Ids<'_>,
    key: &Canon,
    chat: &RichChat,
    now_ms: i64,
) -> Result<()> {
    let conn = ids.conn;
    let summary = &chat.chat;
    let jid = key.key.as_str();
    ensure_chat(conn, jid)?;
    let read_pending = outbox::has_pending_read(conn, jid)?;
    conn.execute(
        "UPDATE chats SET name = ?2, is_group = ?3, unread = ?4, hidden = 0 WHERE jid = ?1",
        params![
            jid,
            summary.name,
            summary.is_group,
            if read_pending { 0 } else { summary.unread },
        ],
    )?;
    set_chat_meta(conn, jid, &chat.meta)?;
    note_wire_jid(conn, jid, key.wire.as_deref())?;
    if let Some(last) = &summary.last {
        let mut arrived = arrive(ids, last, chat.last_meta.as_ref())?;
        arrived.message.chat = jid.to_owned();
        upsert_arrived(conn, &arrived, None, now_ms)?;
    }
    Ok(())
}

fn touched(out: &mut AppliedEvents, chat: &str) {
    if !out.chats.iter().any(|seen| seen == chat) {
        out.chats.push(chat.to_owned());
    }
}

fn upgrade_status(conn: &Connection, chat: &str, id: &str, to: DeliveryStatus) -> Result<bool> {
    let stored: Option<Option<String>> = conn
        .query_row(
            "SELECT status FROM messages WHERE chat = ?1 AND id = ?2 AND from_me = 1",
            params![chat, id],
            |row| row.get(0),
        )
        .optional()?;
    let Some(stored) = stored else {
        return Ok(false);
    };
    let stored = status_from(stored);
    let merged = to.merge(stored);
    if stored == Some(merged) {
        return Ok(false);
    }
    conn.execute(
        "UPDATE messages SET status = ?3 WHERE chat = ?1 AND id = ?2",
        params![chat, id, status_name(merged)],
    )?;
    Ok(true)
}

/// The key of a chat an event names, with the id it arrived under remembered on the chat row.
fn chat_key(ids: &mut Ids<'_>, jid: &str) -> Result<String> {
    let chat = ids.canon(jid)?;
    note_wire_jid(ids.conn, &chat.key, chat.wire.as_deref())?;
    Ok(chat.key)
}

/// A gateway that does not send identity pairs announces a merge it did: a LID chat joined its number's chat.
/// That is a pair to learn, and the stored rows follow the map. Any other pair of chats is moved as it is.
fn chat_merged(
    ids: &mut Ids<'_>,
    from: &str,
    into: &str,
    at_ms: i64,
    out: &mut AppliedEvents,
) -> Result<()> {
    let kind_of = |text: &str| parse(text).map(|parsed| parsed.id.kind());
    if kind_of(from) == Some(WaIdKind::Lid) && kind_of(into) == Some(WaIdKind::Pn) {
        ids.learn(from, into, Source::Other, at_ms)?;
    } else {
        let from_key = ids.canon(from)?.key;
        let into_key = ids.canon(into)?.key;
        if from_key != into_key {
            identity::log_merge(
                ids.conn,
                at_ms,
                &from_key,
                &into_key,
                identity::REASON_GATEWAY,
            )?;
        }
    }
    ids.follow()?;
    ids.conn
        .execute("UPDATE sync_state SET needs_resync = 1", [])?;
    out.resync = true;
    touched(out, from);
    let into_key = ids.canon(into)?.key;
    touched(out, &into_key);
    Ok(())
}

/// Applies one event. The caller checked that it is news and advances the sequence. Every chat and author the
/// event names is resolved through the identity map before it is matched against stored rows.
pub(super) fn apply_event(
    ids: &mut Ids<'_>,
    envelope: &EventEnvelope,
    out: &mut AppliedEvents,
) -> Result<()> {
    let conn = ids.conn;
    let at_ms = envelope.at_ms;
    match &envelope.event {
        GatewayEvent::Message(message) => {
            if is_status_chat(&message.chat) {
                return Ok(());
            }
            let arrived = arrive(ids, message, None)?;
            let message = &arrived.message;
            match upsert_arrived(conn, &arrived, Some(at_ms), at_ms)? {
                Upsert::New if message.from_me => {
                    conn.execute(
                        "UPDATE chats SET unread = 0 WHERE jid = ?1",
                        [&message.chat],
                    )?;
                    if let Some(client_id) = outbox::reconcile_echo(conn, message)? {
                        out.reconciled.push(client_id);
                    }
                    touched(out, &message.chat);
                }
                Upsert::New => {
                    conn.execute(
                        "UPDATE chats SET unread = unread + 1 WHERE jid = ?1",
                        [&message.chat],
                    )?;
                    touched(out, &message.chat);
                }
                Upsert::Changed => touched(out, &message.chat),
                Upsert::Same | Upsert::Unknown => {}
            }
        }
        GatewayEvent::MessageUpdate {
            chat,
            id,
            reactions,
            ..
        } => {
            learn_reactions(ids, reactions, at_ms)?;
            let chat = chat_key(ids, chat)?;
            let mut reactions = reactions.clone();
            drop_alts(&mut reactions);
            authors::reactions(&mut reactions, &mut |text| ids.moved(text))?;
            let changed = conn.execute(
                "UPDATE messages SET reactions = ?3 WHERE chat = ?1 AND id = ?2 AND reactions != ?3",
                params![chat, id, to_json(&reactions)?],
            )?;
            if changed > 0 {
                touched(out, &chat);
            }
        }
        GatewayEvent::Receipt {
            chat,
            ids: acked,
            kind,
        } => {
            let chat = chat_key(ids, chat)?;
            let to = match kind {
                ReceiptKind::Delivered => DeliveryStatus::Delivered,
                ReceiptKind::Read => DeliveryStatus::Read,
            };
            for id in acked {
                if upgrade_status(conn, &chat, id, to)? {
                    touched(out, &chat);
                }
            }
        }
        GatewayEvent::MessageMeta {
            chat,
            id,
            body,
            meta,
        } => {
            let chat = chat_key(ids, chat)?;
            let mut meta = (**meta).clone();
            authors::meta(&mut meta, &mut |text| ids.moved(text))?;
            let incoming = Incoming {
                base: None,
                body: body.as_ref(),
                meta: Some(&meta),
                event_ms: Some(at_ms),
                now_ms: at_ms,
            };
            if matches!(
                upsert(conn, &chat, id, &incoming)?,
                Upsert::New | Upsert::Changed
            ) {
                touched(out, &chat);
            }
        }
        GatewayEvent::MessageDeleted {
            chat,
            id,
            deleted_at,
            deleted_by,
        } => {
            let chat = chat_key(ids, chat)?;
            let by = ids
                .canon(deleted_by.as_deref().unwrap_or(DELETED_BY_ME))?
                .key;
            if mark_deleted(conn, &chat, id, deleted_at.or(Some(at_ms)), &by)? {
                touched(out, &chat);
            }
        }
        GatewayEvent::ChatMeta { jid, meta } => {
            let chat = ids.canon(jid)?;
            set_chat_meta(conn, &chat.key, meta)?;
            note_wire_jid(conn, &chat.key, chat.wire.as_deref())?;
            touched(out, &chat.key);
        }
        GatewayEvent::ChatDeleted { jid } => {
            let jid = chat_key(ids, jid)?;
            // Never erased: the chat leaves the list, its messages stay.
            conn.execute(
                "UPDATE chats SET hidden = 1, unread = 0 WHERE jid = ?1",
                [&jid],
            )?;
            touched(out, &jid);
        }
        GatewayEvent::ChatCleared { jid } => {
            let jid = chat_key(ids, jid)?;
            conn.execute(
                "UPDATE chats SET unread = 0,
                    cleared_ts = (SELECT newest_ts FROM chat_sync WHERE chat_sync.jid = chats.jid)
                 WHERE jid = ?1",
                [&jid],
            )?;
            touched(out, &jid);
        }
        GatewayEvent::ChatMerged { from, into } => chat_merged(ids, from, into, at_ms, out)?,
        GatewayEvent::Profile(profile) => {
            ids.learn_alt(
                Some(&profile.jid),
                profile.lid.as_deref(),
                Source::Usync,
                at_ms,
            )?;
            let jid = chat_key(ids, &profile.jid)?;
            let changed = conn.execute(
                "UPDATE chats SET push_name = COALESCE(?2, push_name), picture_id = COALESCE(?3, picture_id)
                 WHERE jid = ?1 AND (push_name IS NOT COALESCE(?2, push_name)
                    OR picture_id IS NOT COALESCE(?3, picture_id))",
                params![jid, profile.push_name, profile.picture_id],
            )?;
            if changed > 0 {
                touched(out, &jid);
            }
        }
        GatewayEvent::HistorySynced {
            chats: _,
            progress,
            sync_type,
            messages,
            oldest_ms,
        } => {
            // The phone delivered history without one event per message: chats may have older and newer
            // messages than the cursors say.
            conn.execute("UPDATE chat_sync SET reached_start = 0", [])?;
            conn.execute("UPDATE sync_state SET needs_resync = 1", [])?;
            let batch = link_state::HistoryBatch {
                sync_type: sync_type.map(sync_type_name),
                oldest_ms: *oldest_ms,
                progress: *progress,
                messages: *messages,
            };
            link_state::history_synced(conn, &batch, at_ms)?;
            out.resync = true;
        }
        // The epoch a `resync` names is taken in by `Store::apply_events`, which holds the `seq`.
        GatewayEvent::Resync { .. } => {
            conn.execute("UPDATE sync_state SET needs_resync = 1", [])?;
            link_state::resynced(conn)?;
            out.resync = true;
        }
        GatewayEvent::IdentityMapped {
            lid,
            pn,
            source,
            learned_ms,
        } => ids.learn(lid, pn, Source::from_wire(source), *learned_ms)?,
        GatewayEvent::IdentityNumberChanged {
            old_pn,
            new_pn,
            old_lid,
            new_lid,
        } => ids.number_changed(
            old_pn,
            new_pn,
            old_lid.as_deref(),
            new_lid.as_deref(),
            at_ms,
        )?,
        // Typing and presence are not stored, only the pair they name is learned; whoever shows them resolves
        // their ids (`Store::identity_resolve`) so they land on the chat as it is keyed now.
        GatewayEvent::Presence { jid, jid_alt, .. } | GatewayEvent::Typing { jid, jid_alt, .. } => {
            ids.learn_alt(Some(jid), jid_alt.as_deref(), Source::Other, at_ms)?;
        }
        GatewayEvent::Call(call) => {
            ids.learn_alt(
                Some(&call.peer),
                call.peer_alt.as_deref(),
                Source::Call,
                at_ms,
            )?;
        }
        // `State` names the gateway process, the account and its history, `AccountChanged` another account and
        // `Hello` the gateway process: `Store::apply_events` takes them in before this event is applied.
        GatewayEvent::Qr { .. }
        | GatewayEvent::PairCode { .. }
        | GatewayEvent::State(_)
        | GatewayEvent::AccountChanged { .. }
        | GatewayEvent::Hello { .. }
        | GatewayEvent::GroupChanged { .. }
        | GatewayEvent::Scheduled(_)
        | GatewayEvent::StatusChanged { .. }
        | GatewayEvent::KeyRevoked => {}
    }
    Ok(())
}

/// The wire name of a history batch's kind, as [`link_state::HistoryProgress`] keeps it.
fn sync_type_name(sync_type: HistorySyncType) -> &'static str {
    match sync_type {
        HistorySyncType::Initial => "initial",
        HistorySyncType::Recent => "recent",
        HistorySyncType::Full => "full",
        HistorySyncType::OnDemand => "on_demand",
        HistorySyncType::Other => "other",
    }
}
