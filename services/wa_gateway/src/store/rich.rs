//! Message meta, chat settings and profiles (schema v3), and deleted
//! messages kept marked with their edit history (schema v6).

use std::collections::HashMap;

use rovsti_wa_proto::{
    ChatMeta, DELETED_BY_ME, DeliveryStatus, EditRecord, KeptContent, MessageBody, MessageMeta,
    Reaction, RichChat, RichMessage, WaMessage, WaProfile, is_status_chat,
};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use super::{InstanceId, Store, StoreError, last_message_row, reactions_of, upsert_chat};

/// Largest thumbnail kept per message (they are a few KiB in practice).
pub const MAX_THUMBNAIL_BYTES: usize = 64 * 1024;

/// What is stored with a message besides the [`rovsti_wa_proto::WaMessage`].
#[derive(Clone, Debug, Default, PartialEq)]
pub struct MessageExtras {
    pub meta: MessageMeta,
    /// The encoded WhatsApp message, kept for media download.
    pub raw_proto: Option<Vec<u8>>,
    /// The message secret, needed to read encrypted edits of this message.
    pub secret: Option<Vec<u8>>,
    /// A small preview image (JPEG or PNG) that came inside the message.
    pub thumbnail: Option<Vec<u8>>,
}

/// A change to a stored message.
#[derive(Clone, Debug, PartialEq)]
pub enum MessageChange {
    /// New text or caption, edited at `edited_ms`.
    Edit {
        body: MessageBody,
        edited_ms: i64,
    },
    /// Deleted for everyone at `at_ms` by `by` ([`DELETED_BY_ME`] or the
    /// JID that deleted it). The content is kept; apps see it as deleted.
    Revoke {
        by: String,
        at_ms: i64,
    },
    Star(bool),
    /// A receipt for a message I sent.
    Status(DeliveryStatus),
}

/// A change to a chat's settings (from this gateway or another device).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChatChange {
    Archived(bool),
    Pinned(bool),
    /// `None` unmutes; `Some(MUTED_FOREVER)` mutes for good.
    Muted(Option<i64>),
    MarkedUnread(bool),
}

/// The settings a history sync reports for a chat.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ChatSettings {
    pub archived: bool,
    pub pinned: bool,
    pub muted_until_ms: Option<i64>,
    pub marked_unread: bool,
}

/// What WhatsApp answered about one JID.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FetchedProfile {
    pub jid: String,
    pub about: Option<String>,
    pub picture_id: Option<String>,
    pub business: bool,
    pub verified_name: Option<String>,
}

/// A change to what the gateway knows about a person or group.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProfileChange {
    PushName(String),
    ContactName(Option<String>),
    About(Option<String>),
    /// The current picture id (`None`: no picture), known at `now`.
    Picture(Option<String>),
    /// Something changed on WhatsApp's side; ask again next time.
    Stale,
    Fetched(FetchedProfile),
}

/// The body an edit leaves: a caption edit of media keeps the media.
pub fn merge_edit(old: &MessageBody, new: MessageBody) -> MessageBody {
    match (old, new) {
        (
            MessageBody::Media {
                kind, mime, size, ..
            },
            MessageBody::Media { caption, .. },
        ) => MessageBody::Media {
            kind: *kind,
            caption,
            mime: mime.clone(),
            size: *size,
        },
        (
            MessageBody::Media {
                kind, mime, size, ..
            },
            MessageBody::Text(text),
        ) => MessageBody::Media {
            kind: *kind,
            caption: Some(text).filter(|t| !t.is_empty()),
            mime: mime.clone(),
            size: *size,
        },
        (_, new) => new,
    }
}

/// Applies `change` to a message. Returns whether the body apps see changed
/// (a revoke turns it into "deleted" without touching the stored body) and
/// whether anything changed at all.
pub fn apply_change(
    body: &mut MessageBody,
    meta: &mut MessageMeta,
    from_me: bool,
    change: MessageChange,
) -> (bool, bool) {
    match change {
        MessageChange::Edit {
            body: new,
            edited_ms,
        } => {
            if meta.revoked {
                return (false, false);
            }
            let merged = merge_edit(body, new);
            let body_changed = merged != *body;
            *body = merged;
            let newer = meta.edited_ms.is_none_or(|t| edited_ms > t);
            if newer {
                meta.edited_ms = Some(edited_ms);
            }
            (body_changed, body_changed || newer)
        }
        MessageChange::Revoke { .. } => {
            if meta.revoked {
                return (false, false);
            }
            // Nothing of the content goes: [`present`] hides it from apps.
            meta.revoked = true;
            (true, true)
        }
        MessageChange::Star(starred) => {
            let changed = meta.starred != starred;
            meta.starred = starred;
            (false, changed)
        }
        MessageChange::Status(status) => {
            if !from_me {
                return (false, false);
            }
            let merged = status.merge(meta.status);
            let changed = meta.status != Some(merged);
            meta.status = Some(merged);
            (false, changed)
        }
    }
}

/// How many other members of a group reached each state with one of my
/// messages (a member who read it also counts as delivered).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ReceiptCounts {
    pub delivered: usize,
    pub read: usize,
    pub played: usize,
}

/// The ticks a group message shows, like WhatsApp: delivered once every other
/// member has it, read once every other member read it, played once every
/// other member played it. `members` is the number of other members now;
/// `None` when the gateway could not learn it, and then one receipt is
/// enough for two grey ticks but never for blue ones. `None` result: still
/// one tick.
///
/// Approximate: the member count is today's, not the one at send time, so a
/// member who joined later (and never got the message) holds the ticks back,
/// and one who left is still waited for until the group is re-read.
pub fn group_status(members: Option<usize>, counts: ReceiptCounts) -> Option<DeliveryStatus> {
    match members.filter(|&n| n > 0) {
        Some(n) if counts.played >= n => Some(DeliveryStatus::Played),
        Some(n) if counts.read >= n => Some(DeliveryStatus::Read),
        Some(n) if counts.delivered >= n => Some(DeliveryStatus::Delivered),
        Some(_) => None,
        None => (counts.delivered > 0).then_some(DeliveryStatus::Delivered),
    }
}

/// The `receipts.status` value of a state (`Sent` is never stored).
fn receipt_rank(status: DeliveryStatus) -> i64 {
    match status {
        DeliveryStatus::Sent => 0,
        DeliveryStatus::Delivered => 1,
        DeliveryStatus::Read => 2,
        DeliveryStatus::Played => 3,
    }
}

fn meta_from(json: Option<String>) -> Result<MessageMeta, StoreError> {
    match json {
        Some(text) => Ok(serde_json::from_str(&text)?),
        None => Ok(MessageMeta::default()),
    }
}

fn meta_json(meta: &MessageMeta) -> Result<Option<String>, StoreError> {
    if meta.is_empty() {
        Ok(None)
    } else {
        Ok(Some(serde_json::to_string(meta)?))
    }
}

/// The text an edit history keeps of `body`: its text or caption.
fn text_of(body: &MessageBody) -> String {
    match body {
        MessageBody::Text(text) => text.clone(),
        MessageBody::Media { caption, .. } => caption.clone().unwrap_or_default(),
        MessageBody::Unsupported => String::new(),
    }
}

/// Whether a stored message is deleted: marked by this gateway, or revoked
/// by an older one (which kept no deletion time).
fn is_deleted(deleted_at: Option<i64>, meta: &MessageMeta) -> bool {
    deleted_at.is_some() || meta.revoked
}

/// What apps see of a stored message. A deleted one reads as older gateways
/// sent it (body `unsupported`, `revoked`, no content in the meta), so older
/// apps show "This message was deleted"; it also carries `deleted`,
/// `deleted_at`, `deleted_by` and the kept content in `original`.
pub fn present(
    body: MessageBody,
    mut meta: MessageMeta,
    deleted_at: Option<i64>,
    deleted_by: Option<String>,
    edits: Vec<EditRecord>,
) -> (MessageBody, MessageMeta) {
    meta.edit_history = edits;
    if !is_deleted(deleted_at, &meta) {
        return (body, meta);
    }
    let kept = MessageMeta {
        quoted: meta.quoted.take(),
        edited_ms: meta.edited_ms.take(),
        media: meta.media.take(),
        location: meta.location.take(),
        contact: meta.contact.take(),
        mentions: std::mem::take(&mut meta.mentions),
        edit_history: std::mem::take(&mut meta.edit_history),
        ..MessageMeta::default()
    };
    // An older gateway's revoke kept nothing.
    let original = (body != MessageBody::Unsupported || !kept.is_empty())
        .then(|| Box::new(KeptContent { body, meta: kept }));
    let meta = MessageMeta {
        revoked: true,
        deleted: true,
        deleted_at,
        deleted_by,
        original,
        ..meta
    };
    (MessageBody::Unsupported, meta)
}

/// Edit histories of `chat` by message id (only message `id` when given),
/// oldest first.
pub(super) fn edits_of(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
    id: Option<&str>,
) -> Result<HashMap<String, Vec<EditRecord>>, StoreError> {
    let mut stmt = conn.prepare(
        "SELECT id, previous_text, edited_at FROM message_edits
         WHERE instance_id = ?1 AND chat = ?2 AND (?3 IS NULL OR id = ?3)
         ORDER BY id, edited_at",
    )?;
    let rows = stmt.query_map(params![instance.as_str(), chat, id], |r| {
        Ok((
            r.get::<_, String>(0)?,
            EditRecord {
                text: r.get(1)?,
                edited_at: r.get(2)?,
            },
        ))
    })?;
    let mut map: HashMap<String, Vec<EditRecord>> = HashMap::new();
    for row in rows {
        let (id, record) = row?;
        map.entry(id).or_default().push(record);
    }
    Ok(map)
}

/// The columns [`StoredRow::read`] reads, in order.
pub(super) const ROW_COLUMNS: &str =
    "id, sender, from_me, ts_ms, body_json, meta_json, deleted_at, deleted_by";

/// A message row as stored, before [`present`].
pub(super) struct StoredRow {
    id: String,
    sender: Option<String>,
    from_me: bool,
    timestamp_ms: i64,
    body: String,
    meta: Option<String>,
    deleted_at: Option<i64>,
    deleted_by: Option<String>,
}

impl StoredRow {
    pub(super) fn read(r: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: r.get(0)?,
            sender: r.get(1)?,
            from_me: r.get(2)?,
            timestamp_ms: r.get(3)?,
            body: r.get(4)?,
            meta: r.get(5)?,
            deleted_at: r.get(6)?,
            deleted_by: r.get(7)?,
        })
    }

    /// The message of `chat` as apps see it, taking its reactions and edits
    /// out of the chat's maps. A deleted message shows no reactions.
    pub(super) fn into_rich(
        self,
        chat: &str,
        reactions: &mut HashMap<String, Vec<Reaction>>,
        edits: &mut HashMap<String, Vec<EditRecord>>,
    ) -> Result<RichMessage, StoreError> {
        let mut reactions = reactions.remove(&self.id).unwrap_or_default();
        let (body, meta) = present(
            serde_json::from_str(&self.body)?,
            meta_from(self.meta)?,
            self.deleted_at,
            self.deleted_by,
            edits.remove(&self.id).unwrap_or_default(),
        );
        if meta.deleted {
            reactions.clear();
        }
        Ok(RichMessage {
            message: WaMessage {
                id: self.id,
                chat: chat.to_owned(),
                sender: self.sender,
                from_me: self.from_me,
                timestamp_ms: self.timestamp_ms,
                body,
                reactions,
            },
            meta,
        })
    }
}

/// The meta apps see of message `id` after a change, with its edit history.
fn shown_meta(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
    id: &str,
    body: MessageBody,
    meta: MessageMeta,
    deleted: (Option<i64>, Option<String>),
) -> Result<(MessageBody, MessageMeta), StoreError> {
    let edits = edits_of(conn, instance, chat, Some(id))?
        .remove(id)
        .unwrap_or_default();
    Ok(present(body, meta, deleted.0, deleted.1, edits))
}

fn clean_name(name: Option<String>) -> Option<String> {
    name.map(|n| n.trim().to_owned()).filter(|n| !n.is_empty())
}

/// Inserts one message row with its extras. False when it was already there.
pub(super) fn insert_row(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    chat: &str,
    message: &rovsti_wa_proto::WaMessage,
    extras: &MessageExtras,
) -> Result<bool, StoreError> {
    let thumb = extras
        .thumbnail
        .as_ref()
        .filter(|t| !t.is_empty() && t.len() <= MAX_THUMBNAIL_BYTES);
    let n = tx.execute(
        "INSERT OR IGNORE INTO messages
         (instance_id, chat, id, sender, from_me, ts_ms, body_json, raw_proto, meta_json, secret, thumb)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
        params![
            instance.as_str(),
            chat,
            message.id,
            message.sender,
            message.from_me,
            message.timestamp_ms,
            serde_json::to_string(&message.body)?,
            extras.raw_proto,
            meta_json(&extras.meta)?,
            extras.secret,
            thumb
        ],
    )?;
    if n > 0 && !message.from_me {
        // Remember how the author calls themselves.
        let author = message
            .sender
            .as_deref()
            .unwrap_or(&message.chat)
            .to_owned();
        if let Some(name) = clean_name(extras.meta.sender_name.clone())
            && !author.ends_with("@g.us")
        {
            set_push_name(tx, instance, &author, &name)?;
        }
    }
    Ok(n > 0)
}

fn set_push_name(
    conn: &Connection,
    instance: &InstanceId,
    jid: &str,
    name: &str,
) -> Result<bool, StoreError> {
    let n = conn.execute(
        "INSERT INTO profiles (instance_id, jid, push_name) VALUES (?1, ?2, ?3)
         ON CONFLICT(instance_id, jid) DO UPDATE SET push_name = excluded.push_name
         WHERE push_name IS NOT excluded.push_name",
        params![instance.as_str(), jid, name],
    )?;
    Ok(n > 0)
}

pub(super) fn write_settings(
    conn: &Connection,
    instance: &InstanceId,
    jid: &str,
    s: &ChatSettings,
) -> Result<(), StoreError> {
    if *s == ChatSettings::default() {
        return Ok(());
    }
    conn.execute(
        "INSERT INTO chat_settings (instance_id, jid, archived, pinned, muted_until_ms, marked_unread)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(instance_id, jid) DO UPDATE SET
            archived = excluded.archived, pinned = excluded.pinned,
            muted_until_ms = excluded.muted_until_ms, marked_unread = excluded.marked_unread",
        params![
            instance.as_str(),
            jid,
            s.archived,
            s.pinned,
            s.muted_until_ms,
            s.marked_unread
        ],
    )?;
    Ok(())
}

fn chat_meta(conn: &Connection, instance: &InstanceId, jid: &str) -> Result<ChatMeta, StoreError> {
    let settings = conn
        .query_row(
            "SELECT archived, pinned, muted_until_ms, marked_unread FROM chat_settings
             WHERE instance_id = ?1 AND jid = ?2",
            params![instance.as_str(), jid],
            |r| {
                Ok((
                    r.get::<_, bool>(0)?,
                    r.get::<_, bool>(1)?,
                    r.get::<_, Option<i64>>(2)?,
                    r.get::<_, bool>(3)?,
                ))
            },
        )
        .optional()?;
    let profile = conn
        .query_row(
            "SELECT picture_id, push_name FROM profiles WHERE instance_id = ?1 AND jid = ?2",
            params![instance.as_str(), jid],
            |r| {
                Ok((
                    r.get::<_, Option<String>>(0)?,
                    r.get::<_, Option<String>>(1)?,
                ))
            },
        )
        .optional()?;
    let (archived, pinned, muted_until_ms, marked_unread) = settings.unwrap_or_default();
    let (picture_id, push_name) = profile.unwrap_or_default();
    Ok(ChatMeta {
        picture_id,
        push_name: push_name.filter(|_| !jid.ends_with("@g.us")),
        archived,
        pinned,
        muted_until_ms,
        marked_unread,
    })
}

const PROFILE_COLUMNS: &str =
    "jid, push_name, contact_name, about, picture_id, business, verified_name, fetched_ms";

fn profile_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<WaProfile> {
    Ok(WaProfile {
        jid: r.get(0)?,
        push_name: r.get(1)?,
        contact_name: r.get(2)?,
        about: r.get(3)?,
        picture_id: r.get(4)?,
        business: r.get(5)?,
        verified_name: r.get(6)?,
        fetched_ms: r.get(7)?,
    })
}

fn profile_of(
    conn: &Connection,
    instance: &InstanceId,
    jid: &str,
) -> Result<Option<WaProfile>, StoreError> {
    Ok(conn
        .query_row(
            &format!("SELECT {PROFILE_COLUMNS} FROM profiles WHERE instance_id = ?1 AND jid = ?2"),
            params![instance.as_str(), jid],
            profile_row,
        )
        .optional()?)
}

/// The parts of a message row a change needs: from me, body and meta JSON,
/// deletion time and author.
type ChangeRow = (bool, String, Option<String>, Option<i64>, Option<String>);

fn change_row(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
    id: &str,
) -> Result<Option<ChangeRow>, StoreError> {
    Ok(conn
        .query_row(
            "SELECT from_me, body_json, meta_json, deleted_at, deleted_by FROM messages
             WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
            params![instance.as_str(), chat, id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()?)
}

/// Message `id` of `chat` as apps see it ([`present`]).
pub(super) fn message_row(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
    id: &str,
) -> Result<Option<RichMessage>, StoreError> {
    let row = conn
        .query_row(
            &format!(
                "SELECT {ROW_COLUMNS} FROM messages
                 WHERE instance_id = ?1 AND chat = ?2 AND id = ?3"
            ),
            params![instance.as_str(), chat, id],
            StoredRow::read,
        )
        .optional()?;
    let Some(row) = row else {
        return Ok(None);
    };
    let mut reactions = reactions_of(conn, instance, chat)?;
    let mut edits = edits_of(conn, instance, chat, Some(id))?;
    row.into_rich(chat, &mut reactions, &mut edits).map(Some)
}

impl Store {
    /// Stores a message with its extras and bumps its chat, in one
    /// transaction. False when the message was already there.
    pub async fn insert_message_full(
        &self,
        instance: InstanceId,
        message: rovsti_wa_proto::WaMessage,
        extras: MessageExtras,
    ) -> Result<bool, StoreError> {
        // Status updates never become a chat or an unread count.
        if is_status_chat(&message.chat) {
            return Ok(false);
        }
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let fresh = insert_row(&tx, &instance, &message.chat, &message, &extras)?;
            if fresh {
                upsert_chat(
                    &tx,
                    &instance,
                    &message.chat,
                    None,
                    message.chat.ends_with("@g.us"),
                    message.timestamp_ms,
                    u32::from(!message.from_me),
                )?;
            }
            tx.commit()?;
            Ok(fresh)
        })
        .await
    }

    /// Messages of `chat` with their meta, newest first, older than
    /// `before_ms` when given.
    pub async fn messages_rich(
        &self,
        instance: InstanceId,
        chat: String,
        before_ms: Option<i64>,
        limit: u32,
    ) -> Result<Vec<RichMessage>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(&format!(
                "SELECT {ROW_COLUMNS} FROM messages
                 WHERE instance_id = ?1 AND chat = ?2 AND ts_ms < ?3
                 ORDER BY ts_ms DESC, id DESC LIMIT ?4"
            ))?;
            let rows = stmt
                .query_map(
                    params![
                        instance.as_str(),
                        chat,
                        before_ms.unwrap_or(i64::MAX),
                        limit
                    ],
                    StoredRow::read,
                )?
                .collect::<Result<Vec<_>, _>>()?;
            let mut reactions = reactions_of(conn, &instance, &chat)?;
            let mut edits = edits_of(conn, &instance, &chat, None)?;
            rows.into_iter()
                .map(|row| row.into_rich(&chat, &mut reactions, &mut edits))
                .collect()
        })
        .await
    }

    /// One message with its meta.
    pub async fn message_rich(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
    ) -> Result<Option<RichMessage>, StoreError> {
        self.run(move |conn| message_row(conn, &instance, &chat, &id))
            .await
    }

    /// Chats with their settings, picture id and the newest message's meta,
    /// most recently active first.
    pub async fn chats_rich(&self, instance: InstanceId) -> Result<Vec<RichChat>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT jid, name, is_group, unread FROM chats
                 WHERE instance_id = ?1 AND jid != 'status@broadcast'
                 ORDER BY last_ms DESC, jid",
            )?;
            let rows = stmt
                .query_map([instance.as_str()], |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, Option<String>>(1)?,
                        r.get::<_, bool>(2)?,
                        r.get::<_, u32>(3)?,
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            let mut out = Vec::with_capacity(rows.len());
            for (jid, name, is_group, unread) in rows {
                let (last, last_meta) = match last_message_row(conn, &instance, &jid)? {
                    Some(rich) => (Some(rich.message), Some(rich.meta)),
                    None => (None, None),
                };
                out.push(RichChat {
                    meta: chat_meta(conn, &instance, &jid)?,
                    chat: rovsti_wa_proto::ChatSummary {
                        jid,
                        name,
                        last,
                        unread,
                        is_group,
                    },
                    last_meta: last_meta.filter(|m| !m.is_empty()),
                });
            }
            Ok(out)
        })
        .await
    }

    /// Applies `change` to a stored message. `Some((new body, meta))` when it
    /// changed (the body only when it did); `None` for an unknown message or
    /// no change.
    pub async fn change_message(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
        change: MessageChange,
    ) -> Result<Option<(Option<MessageBody>, MessageMeta)>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let Some((from_me, body, meta, deleted_at, deleted_by)) =
                change_row(&tx, &instance, &chat, &id)?
            else {
                return Ok(None);
            };
            let mut body: MessageBody = serde_json::from_str(&body)?;
            let mut meta = meta_from(meta)?;
            // A deleted message keeps its content as it was deleted: it is
            // neither edited nor deleted again.
            if is_deleted(deleted_at, &meta)
                && matches!(
                    change,
                    MessageChange::Edit { .. } | MessageChange::Revoke { .. }
                )
            {
                return Ok(None);
            }
            let before = body.clone();
            let edited_at = match &change {
                MessageChange::Edit { edited_ms, .. } => Some(*edited_ms),
                _ => None,
            };
            let revoked = match &change {
                MessageChange::Revoke { by, at_ms } => Some((*at_ms, by.clone())),
                _ => None,
            };
            let (body_changed, changed) = apply_change(&mut body, &mut meta, from_me, change);
            if !changed {
                return Ok(None);
            }
            tx.execute(
                "UPDATE messages SET body_json = ?4, meta_json = ?5
                 WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                params![
                    instance.as_str(),
                    chat,
                    id,
                    serde_json::to_string(&body)?,
                    meta_json(&meta)?
                ],
            )?;
            if let Some(edited_at) = edited_at.filter(|_| body_changed) {
                tx.execute(
                    "INSERT OR IGNORE INTO message_edits
                     (instance_id, chat, id, edited_at, previous_text)
                     VALUES (?1, ?2, ?3, ?4, ?5)",
                    params![instance.as_str(), chat, id, edited_at, text_of(&before)],
                )?;
            }
            let deleted = match revoked {
                Some((at_ms, by)) => {
                    tx.execute(
                        "UPDATE messages SET deleted_at = ?4, deleted_by = ?5
                         WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                        params![instance.as_str(), chat, id, at_ms, by],
                    )?;
                    (Some(at_ms), Some(by))
                }
                None => (deleted_at, deleted_by),
            };
            let (shown, meta) = shown_meta(&tx, &instance, &chat, &id, body, meta, deleted)?;
            tx.commit()?;
            Ok(Some((body_changed.then_some(shown), meta)))
        })
        .await
    }

    /// Records a receipt for my messages `ids` in `chat`. Returns the messages
    /// whose ticks moved, with their new meta.
    pub async fn set_status(
        &self,
        instance: InstanceId,
        chat: String,
        ids: Vec<String>,
        status: DeliveryStatus,
    ) -> Result<Vec<(String, MessageMeta)>, StoreError> {
        let mut changed = Vec::new();
        for id in ids {
            if let Some((_, meta)) = self
                .change_message(
                    instance.clone(),
                    chat.clone(),
                    id.clone(),
                    MessageChange::Status(status),
                )
                .await?
            {
                changed.push((id, meta));
            }
        }
        Ok(changed)
    }

    /// Records that group member `participant` reached `status` with my
    /// messages `ids` in group `chat`, then moves each message's ticks to
    /// what the whole group reached ([`group_status`] with `members` other
    /// members). Returns the messages whose ticks moved, with their new meta.
    /// Receipts for messages that are not mine are ignored.
    pub async fn record_group_receipt(
        &self,
        instance: InstanceId,
        chat: String,
        participant: String,
        ids: Vec<String>,
        status: DeliveryStatus,
        members: Option<usize>,
    ) -> Result<Vec<(String, MessageMeta)>, StoreError> {
        let rank = receipt_rank(status);
        if rank == 0 {
            return Ok(Vec::new());
        }
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let mut changed = Vec::new();
            for id in ids {
                let Some((true, body, meta, deleted_at, deleted_by)) =
                    change_row(&tx, &instance, &chat, &id)?
                else {
                    continue;
                };
                tx.execute(
                    "INSERT INTO receipts (instance_id, chat, id, participant, status)
                     VALUES (?1, ?2, ?3, ?4, ?5)
                     ON CONFLICT (instance_id, chat, id, participant)
                     DO UPDATE SET status = MAX(status, excluded.status)",
                    params![instance.as_str(), chat, id, participant, rank],
                )?;
                let counts = tx.query_row(
                    "SELECT COUNT(*),
                            COALESCE(SUM(status >= 2), 0),
                            COALESCE(SUM(status >= 3), 0)
                     FROM receipts WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                    |r| {
                        let n = |i| r.get::<_, i64>(i).map(|v| usize::try_from(v).unwrap_or(0));
                        Ok(ReceiptCounts {
                            delivered: n(0)?,
                            read: n(1)?,
                            played: n(2)?,
                        })
                    },
                )?;
                let Some(group) = group_status(members, counts) else {
                    continue;
                };
                let mut body: MessageBody = serde_json::from_str(&body)?;
                let mut meta = meta_from(meta)?;
                let (_, moved) =
                    apply_change(&mut body, &mut meta, true, MessageChange::Status(group));
                if moved {
                    tx.execute(
                        "UPDATE messages SET meta_json = ?4
                         WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                        params![instance.as_str(), chat, id, meta_json(&meta)?],
                    )?;
                    let deleted = (deleted_at, deleted_by);
                    let (_, meta) = shown_meta(&tx, &instance, &chat, &id, body, meta, deleted)?;
                    changed.push((id, meta));
                }
            }
            tx.commit()?;
            Ok(changed)
        })
        .await
    }

    /// The secret of a stored message (for encrypted edits).
    pub async fn message_secret(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
    ) -> Result<Option<Vec<u8>>, StoreError> {
        self.run(move |conn| {
            Ok(conn
                .query_row(
                    "SELECT secret FROM messages WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                    |r| r.get::<_, Option<Vec<u8>>>(0),
                )
                .optional()?
                .flatten())
        })
        .await
    }

    /// The thumbnail of message `id` (the newest when chats reuse the id).
    pub async fn thumbnail(
        &self,
        instance: InstanceId,
        id: String,
    ) -> Result<Option<Vec<u8>>, StoreError> {
        self.run(move |conn| {
            Ok(conn
                .query_row(
                    "SELECT thumb FROM messages WHERE instance_id = ?1 AND id = ?2
                     ORDER BY ts_ms DESC LIMIT 1",
                    params![instance.as_str(), id],
                    |r| r.get::<_, Option<Vec<u8>>>(0),
                )
                .optional()?
                .flatten())
        })
        .await
    }

    /// Marks one message deleted for me at `at_ms`, keeping its content,
    /// media, reactions and receipts. False when it was not stored or was
    /// already deleted.
    pub async fn delete_message(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
        at_ms: i64,
    ) -> Result<bool, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let Some((_, _, meta, deleted_at, _)) = change_row(&tx, &instance, &chat, &id)? else {
                return Ok(false);
            };
            if is_deleted(deleted_at, &meta_from(meta)?) {
                return Ok(false);
            }
            tx.execute(
                "UPDATE messages SET deleted_at = ?4, deleted_by = ?5
                 WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                params![instance.as_str(), chat, id, at_ms, DELETED_BY_ME],
            )?;
            tx.commit()?;
            Ok(true)
        })
        .await
    }

    /// Deletes the messages of a chat, and the chat itself unless `keep_chat`.
    /// False when nothing was stored.
    pub async fn delete_chat(
        &self,
        instance: InstanceId,
        jid: String,
        keep_chat: bool,
    ) -> Result<bool, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let mut n = tx.execute(
                "DELETE FROM messages WHERE instance_id = ?1 AND chat = ?2",
                params![instance.as_str(), jid],
            )?;
            tx.execute(
                "DELETE FROM reactions WHERE instance_id = ?1 AND chat = ?2",
                params![instance.as_str(), jid],
            )?;
            tx.execute(
                "DELETE FROM receipts WHERE instance_id = ?1 AND chat = ?2",
                params![instance.as_str(), jid],
            )?;
            tx.execute(
                "DELETE FROM message_edits WHERE instance_id = ?1 AND chat = ?2",
                params![instance.as_str(), jid],
            )?;
            if keep_chat {
                tx.execute(
                    "UPDATE chats SET unread = 0 WHERE instance_id = ?1 AND jid = ?2",
                    params![instance.as_str(), jid],
                )?;
            } else {
                n += tx.execute(
                    "DELETE FROM chats WHERE instance_id = ?1 AND jid = ?2",
                    params![instance.as_str(), jid],
                )?;
                tx.execute(
                    "DELETE FROM chat_settings WHERE instance_id = ?1 AND jid = ?2",
                    params![instance.as_str(), jid],
                )?;
            }
            tx.commit()?;
            Ok(n > 0)
        })
        .await
    }

    /// Changes a chat's settings. Returns the new [`ChatMeta`] when it changed.
    pub async fn change_chat(
        &self,
        instance: InstanceId,
        jid: String,
        change: ChatChange,
    ) -> Result<Option<ChatMeta>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let before = chat_meta(&tx, &instance, &jid)?;
            let mut s = ChatSettings {
                archived: before.archived,
                pinned: before.pinned,
                muted_until_ms: before.muted_until_ms,
                marked_unread: before.marked_unread,
            };
            match change {
                ChatChange::Archived(v) => s.archived = v,
                ChatChange::Pinned(v) => s.pinned = v,
                ChatChange::Muted(v) => s.muted_until_ms = v,
                ChatChange::MarkedUnread(v) => s.marked_unread = v,
            }
            if s == ChatSettings::default() {
                tx.execute(
                    "DELETE FROM chat_settings WHERE instance_id = ?1 AND jid = ?2",
                    params![instance.as_str(), jid],
                )?;
            } else {
                write_settings(&tx, &instance, &jid, &s)?;
            }
            if matches!(change, ChatChange::MarkedUnread(false)) {
                tx.execute(
                    "UPDATE chats SET unread = 0 WHERE instance_id = ?1 AND jid = ?2",
                    params![instance.as_str(), jid],
                )?;
            }
            let after = chat_meta(&tx, &instance, &jid)?;
            tx.commit()?;
            Ok((after != before).then_some(after))
        })
        .await
    }

    /// The stored profiles of `jids` (those the gateway knows anything about).
    pub async fn profiles(
        &self,
        instance: InstanceId,
        jids: Vec<String>,
    ) -> Result<Vec<WaProfile>, StoreError> {
        self.run(move |conn| {
            let mut out = Vec::with_capacity(jids.len());
            for jid in &jids {
                out.extend(profile_of(conn, &instance, jid)?);
            }
            Ok(out)
        })
        .await
    }

    /// The picture id the gateway knows for `jid` and when it last checked
    /// it (0: never).
    pub async fn picture_state(
        &self,
        instance: InstanceId,
        jid: String,
    ) -> Result<(Option<String>, i64), StoreError> {
        self.run(move |conn| {
            Ok(conn
                .query_row(
                    "SELECT picture_id, picture_checked_ms FROM profiles
                     WHERE instance_id = ?1 AND jid = ?2",
                    params![instance.as_str(), jid],
                    |r| Ok((r.get::<_, Option<String>>(0)?, r.get::<_, i64>(1)?)),
                )
                .optional()?
                .unwrap_or((None, 0)))
        })
        .await
    }

    /// Applies `change` to the profile of `jid` at `now_ms`. Returns the new
    /// profile when something an app shows changed.
    pub async fn change_profile(
        &self,
        instance: InstanceId,
        jid: String,
        change: ProfileChange,
        now_ms: i64,
    ) -> Result<Option<WaProfile>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let before = profile_of(&tx, &instance, &jid)?;
            tx.execute(
                "INSERT OR IGNORE INTO profiles (instance_id, jid) VALUES (?1, ?2)",
                params![instance.as_str(), jid],
            )?;
            match change {
                ProfileChange::PushName(name) => {
                    if let Some(name) = clean_name(Some(name)) {
                        set_push_name(&tx, &instance, &jid, &name)?;
                    }
                }
                ProfileChange::ContactName(name) => {
                    tx.execute(
                        "UPDATE profiles SET contact_name = ?3 WHERE instance_id = ?1 AND jid = ?2",
                        params![instance.as_str(), jid, clean_name(name)],
                    )?;
                }
                ProfileChange::About(about) => {
                    tx.execute(
                        "UPDATE profiles SET about = ?3 WHERE instance_id = ?1 AND jid = ?2",
                        params![instance.as_str(), jid, about],
                    )?;
                }
                ProfileChange::Picture(id) => {
                    tx.execute(
                        "UPDATE profiles SET picture_id = ?3, picture_checked_ms = ?4
                         WHERE instance_id = ?1 AND jid = ?2",
                        params![instance.as_str(), jid, id, now_ms],
                    )?;
                }
                ProfileChange::Stale => {
                    tx.execute(
                        "UPDATE profiles SET fetched_ms = 0, picture_checked_ms = 0
                         WHERE instance_id = ?1 AND jid = ?2",
                        params![instance.as_str(), jid],
                    )?;
                }
                ProfileChange::Fetched(f) => {
                    tx.execute(
                        "UPDATE profiles SET about = ?3, picture_id = ?4, picture_checked_ms = ?7,
                            business = ?5, verified_name = ?6, fetched_ms = ?7
                         WHERE instance_id = ?1 AND jid = ?2",
                        params![
                            instance.as_str(),
                            jid,
                            f.about,
                            f.picture_id,
                            f.business,
                            f.verified_name,
                            now_ms
                        ],
                    )?;
                }
            }
            let after = profile_of(&tx, &instance, &jid)?;
            tx.commit()?;
            let visible =
                |p: &Option<WaProfile>| p.clone().map(|p| WaProfile { fetched_ms: 0, ..p });
            let changed = visible(&before).unwrap_or_else(|| WaProfile {
                jid: jid.clone(),
                ..Default::default()
            }) != visible(&after).unwrap_or_default();
            Ok(after.filter(|_| changed))
        })
        .await
    }

    /// Push names from a history sync, in one transaction.
    pub async fn set_push_names(
        &self,
        instance: InstanceId,
        names: Vec<(String, String)>,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            for (jid, name) in &names {
                if let Some(name) = clean_name(Some(name.clone())) {
                    set_push_name(&tx, &instance, jid, &name)?;
                }
            }
            tx.commit()?;
            Ok(())
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::MediaKind;

    #[test]
    fn group_ticks_wait_for_every_member() {
        use DeliveryStatus::*;
        let counts = |delivered, read, played| ReceiptCounts {
            delivered,
            read,
            played,
        };
        // Three other members: one reader is not enough for blue ticks.
        assert_eq!(group_status(Some(3), counts(1, 1, 0)), None);
        assert_eq!(group_status(Some(3), counts(3, 1, 0)), Some(Delivered));
        assert_eq!(group_status(Some(3), counts(3, 2, 0)), Some(Delivered));
        assert_eq!(group_status(Some(3), counts(3, 3, 0)), Some(Read));
        assert_eq!(group_status(Some(3), counts(3, 3, 3)), Some(Played));
        // Readers who never sent "delivered" still count as delivered.
        assert_eq!(group_status(Some(2), counts(2, 2, 1)), Some(Read));
        // Unknown member count: grey ticks at most.
        assert_eq!(group_status(None, counts(0, 0, 0)), None);
        assert_eq!(group_status(None, counts(5, 5, 5)), Some(Delivered));
        assert_eq!(group_status(Some(0), counts(1, 1, 0)), Some(Delivered));
    }

    fn photo(caption: Option<&str>) -> MessageBody {
        MessageBody::Media {
            kind: MediaKind::Image,
            caption: caption.map(str::to_owned),
            mime: "image/jpeg".into(),
            size: 9,
        }
    }

    #[test]
    fn caption_edits_keep_the_media() {
        assert_eq!(merge_edit(&photo(Some("a")), photo(None)), photo(None));
        let edited = MessageBody::Media {
            kind: MediaKind::Image,
            caption: Some("b".into()),
            mime: String::new(),
            size: 0,
        };
        assert_eq!(merge_edit(&photo(Some("a")), edited), photo(Some("b")));
        assert_eq!(
            merge_edit(&photo(None), MessageBody::Text("c".into())),
            photo(Some("c"))
        );
        assert_eq!(
            merge_edit(
                &MessageBody::Text("a".into()),
                MessageBody::Text("b".into())
            ),
            MessageBody::Text("b".into())
        );
    }

    #[test]
    fn changes() {
        let mut body = MessageBody::Text("a".into());
        let mut meta = MessageMeta::default();
        let edit = |t: &str, at| MessageChange::Edit {
            body: MessageBody::Text(t.into()),
            edited_ms: at,
        };
        assert_eq!(
            apply_change(&mut body, &mut meta, true, edit("b", 5)),
            (true, true)
        );
        assert_eq!(meta.edited_ms, Some(5));
        // A replayed edit changes nothing; an older one keeps the newer time.
        assert_eq!(
            apply_change(&mut body, &mut meta, true, edit("b", 5)),
            (false, false)
        );
        apply_change(&mut body, &mut meta, true, edit("b", 3));
        assert_eq!(meta.edited_ms, Some(5));

        let status = |s| MessageChange::Status(s);
        assert_eq!(
            apply_change(&mut body, &mut meta, true, status(DeliveryStatus::Read)),
            (false, true)
        );
        assert_eq!(
            apply_change(
                &mut body,
                &mut meta,
                true,
                status(DeliveryStatus::Delivered)
            ),
            (false, false)
        );
        assert_eq!(meta.status, Some(DeliveryStatus::Read));
        // Receipts are about my messages only.
        let mut theirs = MessageMeta::default();
        apply_change(&mut body, &mut theirs, false, status(DeliveryStatus::Read));
        assert_eq!(theirs.status, None);

        assert_eq!(
            apply_change(&mut body, &mut meta, true, MessageChange::Star(true)),
            (false, true)
        );
        let revoke = || MessageChange::Revoke {
            by: DELETED_BY_ME.into(),
            at_ms: 8,
        };
        assert_eq!(
            apply_change(&mut body, &mut meta, true, revoke()),
            (true, true)
        );
        // The content is kept, only marked.
        assert_eq!(body, MessageBody::Text("b".into()));
        assert!(meta.revoked && meta.edited_ms == Some(5));
        // A deleted message cannot be edited or deleted again.
        assert_eq!(
            apply_change(&mut body, &mut meta, true, edit("x", 9)),
            (false, false)
        );
        assert_eq!(
            apply_change(&mut body, &mut meta, true, revoke()),
            (false, false)
        );
        assert_eq!(body, MessageBody::Text("b".into()));
    }

    #[test]
    fn deleted_messages_read_as_deleted_with_their_content_kept() {
        use rovsti_wa_proto::{MediaMeta, Quoted};
        let history = vec![EditRecord {
            text: "frist".into(),
            edited_at: 4,
        }];
        let meta = MessageMeta {
            quoted: Some(Quoted {
                id: "q".into(),
                sender: None,
                preview: Some("earlier".into()),
            }),
            edited_ms: Some(4),
            media: Some(MediaMeta {
                thumbnail: true,
                ..Default::default()
            }),
            mentions: vec!["1@s.whatsapp.net".into()],
            starred: true,
            status: Some(DeliveryStatus::Read),
            ..Default::default()
        };
        // Not deleted: as stored, with the edit history.
        let (body, shown) = present(photo(Some("a")), meta.clone(), None, None, history.clone());
        assert_eq!(body, photo(Some("a")));
        assert_eq!(shown.edit_history, history);
        assert!(!shown.deleted && !shown.revoked);

        let (body, shown) = present(
            photo(Some("a")),
            meta.clone(),
            Some(9),
            Some("s@s.whatsapp.net".into()),
            history.clone(),
        );
        // What an older app reads: deleted, nothing of the content.
        assert_eq!(body, MessageBody::Unsupported);
        assert!(shown.revoked && shown.deleted);
        assert_eq!(
            (shown.deleted_at, shown.deleted_by.as_deref()),
            (Some(9), Some("s@s.whatsapp.net"))
        );
        assert!(shown.quoted.is_none() && shown.media.is_none() && shown.mentions.is_empty());
        assert!(shown.edited_ms.is_none() && shown.edit_history.is_empty());
        // Ticks and star stay on the bubble.
        assert!(shown.starred && shown.status == Some(DeliveryStatus::Read));
        // The kept content.
        let original = shown.original.unwrap();
        assert_eq!(original.body, photo(Some("a")));
        assert_eq!(original.meta.quoted, meta.quoted);
        assert_eq!(original.meta.media, meta.media);
        assert_eq!(original.meta.mentions, meta.mentions);
        assert_eq!(original.meta.edited_ms, Some(4));
        assert_eq!(original.meta.edit_history, history);
        assert!(!original.meta.starred && original.meta.status.is_none());

        // An older gateway's revoke: deleted, at an unknown time, nothing kept.
        let old = MessageMeta {
            revoked: true,
            ..Default::default()
        };
        let (body, shown) = present(MessageBody::Unsupported, old, None, None, Vec::new());
        assert_eq!(body, MessageBody::Unsupported);
        assert!(shown.deleted && shown.deleted_at.is_none() && shown.original.is_none());
    }

    #[test]
    fn edit_history_keeps_texts_and_captions() {
        assert_eq!(text_of(&MessageBody::Text("a".into())), "a");
        assert_eq!(text_of(&photo(Some("c"))), "c");
        assert_eq!(text_of(&photo(None)), "");
        assert_eq!(text_of(&MessageBody::Unsupported), "");
    }
}
