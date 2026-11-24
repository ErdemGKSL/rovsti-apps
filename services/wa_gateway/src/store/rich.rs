//! Message meta, chat settings and profiles (schema v3), and deleted
//! messages kept marked with their edit history (schema v6).

use std::collections::HashMap;

use rosty_wa_proto::{
    ChatMeta, DELETED_BY_ME, DeliveryStatus, EditRecord, KeptContent, MessageBody, MessageMeta,
    MessageReceipts, Reaction, ReceiptMember, RichChat, RichMessage, WaMessage, WaProfile,
    is_status_chat,
};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use super::identity::Alts;
use super::{InstanceId, Store, StoreError, last_message_row, reactions_of, upsert_chat};

/// Largest thumbnail kept per message (they are a few KiB in practice).
pub const MAX_THUMBNAIL_BYTES: usize = 64 * 1024;

/// What is stored with a message besides the [`rosty_wa_proto::WaMessage`].
#[derive(Clone, Debug, Default, PartialEq)]
pub struct MessageExtras {
    pub meta: MessageMeta,
    /// The encoded WhatsApp message, kept for media download.
    pub raw_proto: Option<Vec<u8>>,
    /// The message secret, needed to read encrypted edits of this message.
    pub secret: Option<Vec<u8>>,
    /// A small preview image (JPEG or PNG) that came inside the message.
    pub thumbnail: Option<Vec<u8>>,
    /// The chat id the message arrived under, bare (a LID while the row is
    /// stored under the phone number). `None` when it was not recorded.
    pub wire_chat: Option<String>,
    /// The author id the message arrived under, bare. `None` for one-to-one
    /// chats and when it was not recorded.
    pub wire_sender: Option<String>,
    /// Votes of a poll that came with it, opened (history sync, E6-WA R5).
    pub votes: Vec<super::polls::NewVote>,
}

/// The ids a stored message arrived under: what WhatsApp expects back in a
/// message key, a quote and a read receipt.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct WireIds {
    pub chat: Option<String>,
    pub sender: Option<String>,
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
    /// Kept in a disappearing chat, or no longer ("keep in chat", E6-WA R5): a kept message does not expire, an
    /// undone keep expires again from its send time.
    Keep {
        kept: bool,
        at_ms: i64,
    },
    /// The votes of a poll were counted again (E6-WA R5).
    Poll(rosty_wa_proto::Poll),
}

/// An edit or delete for everyone that arrived before its message
/// (`pending_changes`, schema v11).
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum PendingChange {
    Edit { body: MessageBody, edited_ms: i64 },
    Revoke { by: String, at_ms: i64 },
    Keep { kept: bool, at_ms: i64 },
}

impl PendingChange {
    fn of(change: &MessageChange) -> Option<Self> {
        match change {
            MessageChange::Edit { body, edited_ms } => Some(Self::Edit {
                body: body.clone(),
                edited_ms: *edited_ms,
            }),
            MessageChange::Revoke { by, at_ms } => Some(Self::Revoke {
                by: by.clone(),
                at_ms: *at_ms,
            }),
            MessageChange::Keep { kept, at_ms } => Some(Self::Keep {
                kept: *kept,
                at_ms: *at_ms,
            }),
            MessageChange::Star(_) | MessageChange::Status(_) | MessageChange::Poll(_) => None,
        }
    }

    fn kind(&self) -> &'static str {
        match self {
            Self::Edit { .. } => "edit",
            Self::Revoke { .. } => "revoke",
            Self::Keep { .. } => "keep",
        }
    }

    fn at_ms(&self) -> i64 {
        match self {
            Self::Edit { edited_ms, .. } => *edited_ms,
            Self::Revoke { at_ms, .. } | Self::Keep { at_ms, .. } => *at_ms,
        }
    }

    fn into_change(self) -> MessageChange {
        match self {
            Self::Edit { body, edited_ms } => MessageChange::Edit { body, edited_ms },
            Self::Revoke { by, at_ms } => MessageChange::Revoke { by, at_ms },
            Self::Keep { kept, at_ms } => MessageChange::Keep { kept, at_ms },
        }
    }
}

/// How long an early edit or delete waits for its message.
pub const PENDING_CHANGE_TTL_MS: i64 = 14 * 24 * 60 * 60 * 1000;

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
    /// The disappearing timer in seconds (`Some(0)`: off) and when it was set
    /// (unix ms). Written only when `ephemeral_ms` is set and not older than
    /// what is stored.
    pub ephemeral_s: Option<u32>,
    pub ephemeral_ms: Option<i64>,
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
    /// The business name a message of theirs carried.
    VerifiedName(String),
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
        MessageChange::Keep { kept, .. } => {
            let changed = meta.kept != kept;
            meta.kept = kept;
            (false, changed)
        }
        MessageChange::Poll(poll) => {
            // Only a poll is counted; the question and options stay as they came.
            let Some(stored) = meta.poll.as_mut() else {
                return (false, false);
            };
            let changed = stored.options != poll.options || stored.my_votes != poll.my_votes;
            stored.options = poll.options;
            stored.my_votes = poll.my_votes;
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

/// One group member's receipt for messages of mine.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupReceipt {
    /// Who acked: their phone-number JID when known, else their LID. Bare.
    pub participant: String,
    /// The messages the receipt covers.
    pub ids: Vec<String>,
    /// How far they got.
    pub status: DeliveryStatus,
    /// How many other members the group has now; `None` when unknown.
    pub members: Option<usize>,
    /// This account's own ids, bare: its devices are not members.
    pub own: Vec<String>,
}

/// Counts receipt rows by person. `rows` are `(person, furthest state)`
/// with a LID already replaced by its number where the gateway knows it;
/// `own` are this account's ids, whose devices never count.
fn count_receipts(rows: impl IntoIterator<Item = (String, i64)>, own: &[String]) -> ReceiptCounts {
    let mut counts = ReceiptCounts::default();
    for (person, status) in rows {
        if own.contains(&person) {
            continue;
        }
        counts.delivered += 1;
        counts.read += usize::from(status >= receipt_rank(DeliveryStatus::Read));
        counts.played += usize::from(status >= receipt_rank(DeliveryStatus::Played));
    }
    counts
}

/// How far each person got with message `id`: one row per person, whether
/// the receipts named them by LID, by number or both.
fn receipts_by_person(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
    id: &str,
) -> Result<Vec<(String, i64)>, StoreError> {
    let mut stmt = conn.prepare_cached(
        "SELECT COALESCE(p.pn, r.participant) AS person, MAX(r.status)
         FROM receipts r
         LEFT JOIN identity_pairs p
           ON p.instance_id = r.instance_id AND p.lid = r.participant
         WHERE r.instance_id = ?1 AND r.chat = ?2 AND r.id = ?3
         GROUP BY person",
    )?;
    let rows = stmt.query_map(params![instance.as_str(), chat, id], |r| {
        Ok((r.get(0)?, r.get(1)?))
    })?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
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

pub(super) fn meta_from(json: Option<String>) -> Result<MessageMeta, StoreError> {
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
        link_preview: meta.link_preview.take(),
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
pub(super) const ROW_COLUMNS: &str = "id, sender, from_me, ts_ms, body_json, meta_json, deleted_at, deleted_by, wire_chat, wire_sender";

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
    wire_chat: Option<String>,
    wire_sender: Option<String>,
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
            wire_chat: r.get(8)?,
            wire_sender: r.get(9)?,
        })
    }

    /// The message of `chat` as apps see it, taking its reactions and edits
    /// out of the chat's maps. A deleted message shows no reactions. The
    /// other form of its chat and author comes from `alts`.
    pub(super) fn into_rich(
        self,
        chat: &str,
        reactions: &mut HashMap<String, Vec<Reaction>>,
        edits: &mut HashMap<String, Vec<EditRecord>>,
        alts: &mut Alts<'_>,
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
        let sender_alt = match self.sender.as_deref() {
            Some(sender) => alts.of(sender, self.wire_sender.as_deref())?,
            None => None,
        };
        Ok(RichMessage {
            message: WaMessage {
                id: self.id,
                chat: chat.to_owned(),
                sender: self.sender,
                from_me: self.from_me,
                timestamp_ms: self.timestamp_ms,
                body,
                reactions,
                chat_alt: alts.of(chat, self.wire_chat.as_deref())?,
                sender_alt,
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
    message: &rosty_wa_proto::WaMessage,
    extras: &MessageExtras,
) -> Result<bool, StoreError> {
    let thumb = extras
        .thumbnail
        .as_ref()
        .filter(|t| !t.is_empty() && t.len() <= MAX_THUMBNAIL_BYTES);
    // A message kept in the chat never disappears (E6-WA R5).
    let expires_ms = if extras.meta.kept {
        None
    } else {
        rosty_core::wa_actions::expires_at(message.timestamp_ms, extras.meta.expires_s)
    };
    let n = tx.execute(
        "INSERT OR IGNORE INTO messages
         (instance_id, chat, id, sender, from_me, ts_ms, body_json, raw_proto, meta_json, secret,
          thumb, wire_chat, wire_sender, expires_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)",
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
            thumb,
            extras.wire_chat,
            extras.wire_sender,
            expires_ms
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
    if n > 0 {
        // Votes that came with the poll (history sync opens them already).
        for vote in &extras.votes {
            super::polls::upsert_vote(tx, instance, chat, &message.id, vote)?;
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
    let timer_ms = s.ephemeral_ms;
    let timer = s.ephemeral_s.filter(|t| *t > 0);
    conn.execute(
        "INSERT INTO chat_settings
            (instance_id, jid, archived, pinned, muted_until_ms, marked_unread, ephemeral_s, ephemeral_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
         ON CONFLICT(instance_id, jid) DO UPDATE SET
            archived = excluded.archived, pinned = excluded.pinned,
            muted_until_ms = excluded.muted_until_ms, marked_unread = excluded.marked_unread,
            ephemeral_s = CASE WHEN excluded.ephemeral_ms >= COALESCE(ephemeral_ms, 0)
                THEN excluded.ephemeral_s ELSE ephemeral_s END,
            ephemeral_ms = CASE WHEN excluded.ephemeral_ms >= COALESCE(ephemeral_ms, 0)
                THEN excluded.ephemeral_ms ELSE ephemeral_ms END",
        params![
            instance.as_str(),
            jid,
            s.archived,
            s.pinned,
            s.muted_until_ms,
            s.marked_unread,
            timer.filter(|_| timer_ms.is_some()),
            timer_ms
        ],
    )?;
    Ok(())
}

fn chat_meta(conn: &Connection, instance: &InstanceId, jid: &str) -> Result<ChatMeta, StoreError> {
    let settings = conn
        .query_row(
            "SELECT archived, pinned, muted_until_ms, marked_unread, ephemeral_s, left_ms
             FROM chat_settings WHERE instance_id = ?1 AND jid = ?2",
            params![instance.as_str(), jid],
            |r| {
                Ok((
                    r.get::<_, bool>(0)?,
                    r.get::<_, bool>(1)?,
                    r.get::<_, Option<i64>>(2)?,
                    r.get::<_, bool>(3)?,
                    r.get::<_, Option<u32>>(4)?,
                    r.get::<_, Option<i64>>(5)?,
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
    let (archived, pinned, muted_until_ms, marked_unread, ephemeral_s, left_ms) =
        settings.unwrap_or_default();
    let (picture_id, push_name) = profile.unwrap_or_default();
    Ok(ChatMeta {
        picture_id,
        push_name: push_name.filter(|_| !jid.ends_with("@g.us")),
        archived,
        pinned,
        muted_until_ms,
        marked_unread,
        ephemeral_s: ephemeral_s.filter(|t| *t > 0),
        left: left_ms.is_some(),
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
        lid: None,
    })
}

fn profile_of(
    conn: &Connection,
    instance: &InstanceId,
    jid: &str,
) -> Result<Option<WaProfile>, StoreError> {
    let profile = conn
        .query_row(
            &format!("SELECT {PROFILE_COLUMNS} FROM profiles WHERE instance_id = ?1 AND jid = ?2"),
            params![instance.as_str(), jid],
            profile_row,
        )
        .optional()?;
    let Some(mut profile) = profile else {
        return Ok(None);
    };
    // Only a phone-number profile names its LID; a LID profile has no number.
    if rosty_wa_proto::is_pn_jid(jid) {
        profile.lid = Alts::new(conn, instance).of(jid, None)?;
    }
    Ok(Some(profile))
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
    let mut alts = Alts::new(conn, instance);
    let mut reactions = reactions_of(conn, instance, chat, &mut alts)?;
    let mut edits = edits_of(conn, instance, chat, Some(id))?;
    row.into_rich(chat, &mut reactions, &mut edits, &mut alts)
        .map(Some)
}

impl Store {
    /// Stores a message with its extras and bumps its chat, in one
    /// transaction. False when the message was already there.
    pub async fn insert_message_full(
        &self,
        instance: InstanceId,
        message: rosty_wa_proto::WaMessage,
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
                    u32::from(!message.from_me && extras.meta.system.is_none()),
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
            let mut alts = Alts::new(conn, &instance);
            let mut reactions = reactions_of(conn, &instance, &chat, &mut alts)?;
            let mut edits = edits_of(conn, &instance, &chat, None)?;
            rows.into_iter()
                .map(|row| row.into_rich(&chat, &mut reactions, &mut edits, &mut alts))
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
            let mut alts = Alts::new(conn, &instance);
            let mut out = Vec::with_capacity(rows.len());
            for (jid, name, is_group, unread) in rows {
                let (last, last_meta) = match last_message_row(conn, &instance, &jid)? {
                    Some(rich) => (Some(rich.message), Some(rich.meta)),
                    None => (None, None),
                };
                out.push(RichChat {
                    meta: chat_meta(conn, &instance, &jid)?,
                    chat: rosty_wa_proto::ChatSummary {
                        alt: alts.of(&jid, None)?,
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
                // An edit or delete that came before its message waits for it.
                if let Some(pending) = PendingChange::of(&change) {
                    keep_pending(&tx, &instance, &chat, &id, &pending)?;
                    tx.commit()?;
                }
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
            let keep = match &change {
                MessageChange::Keep { kept, .. } => Some(*kept),
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
            if let Some(kept) = keep {
                // A kept message never disappears; an undone keep disappears its timer after it was sent
                // (`rosty_core::wa_actions::expires_at`).
                let expires = (!kept)
                    .then_some(meta.expires_s)
                    .flatten()
                    .filter(|s| *s > 0)
                    .map(i64::from);
                tx.execute(
                    "UPDATE messages SET expires_ms = CASE WHEN ?4 IS NULL THEN NULL ELSE ts_ms + ?4 * 1000 END
                     WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id, expires],
                )?;
            }
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

    /// Records that a group member reached a state with my messages in
    /// group `chat` ([`GroupReceipt`]), then moves each message's ticks to
    /// what the whole group reached ([`group_status`]). Returns the messages
    /// whose ticks moved, with their new meta. Receipts for messages that are
    /// not mine are ignored.
    ///
    /// Members are counted by person: a member who acked under a LID and
    /// under the number the gateway knows for it counts once, and a receipt
    /// from this account's own ids never counts.
    pub async fn record_group_receipt(
        &self,
        instance: InstanceId,
        chat: String,
        receipt: GroupReceipt,
    ) -> Result<Vec<(String, MessageMeta)>, StoreError> {
        self.record_group_receipt_at(instance, chat, receipt, crate::now_ms())
            .await
    }

    /// [`Self::record_group_receipt`] for a receipt that came at `at_ms`: the
    /// first time a member reached each state is kept for message info.
    pub async fn record_group_receipt_at(
        &self,
        instance: InstanceId,
        chat: String,
        receipt: GroupReceipt,
        at_ms: i64,
    ) -> Result<Vec<(String, MessageMeta)>, StoreError> {
        let GroupReceipt {
            participant,
            ids,
            status,
            members,
            own,
        } = receipt;
        let rank = receipt_rank(status);
        if rank == 0 || own.contains(&participant) {
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
                record_receipt(&tx, &instance, (&chat, &id), &participant, rank, at_ms)?;
                let counts = count_receipts(receipts_by_person(&tx, &instance, &chat, &id)?, &own);
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

    /// The ids message `id` of `chat` arrived under. Empty for an unknown
    /// message and for one stored before the gateway kept them.
    pub async fn wire_ids(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
    ) -> Result<WireIds, StoreError> {
        self.run(move |conn| {
            Ok(conn
                .query_row(
                    "SELECT wire_chat, wire_sender FROM messages
                     WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                    |r| {
                        Ok(WireIds {
                            chat: r.get(0)?,
                            sender: r.get(1)?,
                        })
                    },
                )
                .optional()?
                .unwrap_or_default())
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
                ..ChatSettings::default()
            };
            match change {
                ChatChange::Archived(v) => s.archived = v,
                ChatChange::Pinned(v) => s.pinned = v,
                ChatChange::Muted(v) => s.muted_until_ms = v,
                ChatChange::MarkedUnread(v) => s.marked_unread = v,
            }
            if s == ChatSettings::default() {
                // The disappearing timer stays when everything else is cleared.
                tx.execute(
                    "UPDATE chat_settings SET archived = 0, pinned = 0, muted_until_ms = NULL,
                        marked_unread = 0
                     WHERE instance_id = ?1 AND jid = ?2",
                    params![instance.as_str(), jid],
                )?;
                tx.execute(
                    "DELETE FROM chat_settings WHERE instance_id = ?1 AND jid = ?2
                        AND ephemeral_s IS NULL AND ephemeral_ms IS NULL AND left_ms IS NULL",
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
                ProfileChange::VerifiedName(name) => {
                    if let Some(name) = clean_name(Some(name)) {
                        tx.execute(
                            "UPDATE profiles SET business = 1, verified_name = ?3
                             WHERE instance_id = ?1 AND jid = ?2",
                            params![instance.as_str(), jid, name],
                        )?;
                    }
                }
            }
            let after = profile_of(&tx, &instance, &jid)?;
            tx.commit()?;
            // When it was fetched and which LID the number has are not
            // changes of the profile itself.
            let visible = |p: &Option<WaProfile>| {
                p.clone().map(|p| WaProfile {
                    fetched_ms: 0,
                    lid: None,
                    ..p
                })
            };
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

/// Keeps an edit or delete for everyone of a message that is not stored yet.
/// A later edit replaces an earlier one; what waited too long is dropped.
fn keep_pending(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    chat: &str,
    id: &str,
    pending: &PendingChange,
) -> Result<(), StoreError> {
    tx.execute(
        "DELETE FROM pending_changes WHERE instance_id = ?1 AND at_ms < ?2",
        params![
            instance.as_str(),
            crate::now_ms().saturating_sub(PENDING_CHANGE_TTL_MS)
        ],
    )?;
    tx.execute(
        "INSERT INTO pending_changes (instance_id, chat, id, kind, change_json, at_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT (instance_id, chat, id, kind) DO UPDATE SET
            change_json = excluded.change_json, at_ms = excluded.at_ms
         WHERE excluded.at_ms >= at_ms",
        params![
            instance.as_str(),
            chat,
            id,
            pending.kind(),
            serde_json::to_string(pending)?,
            pending.at_ms()
        ],
    )?;
    Ok(())
}

/// The receipt columns a state at `at_ms` fills: delivered, read, played
/// (a read implies delivered, a play implies read).
fn receipt_times(rank: i64, at_ms: i64) -> (Option<i64>, Option<i64>, Option<i64>) {
    (
        (rank >= 1).then_some(at_ms),
        (rank >= 2).then_some(at_ms),
        (rank >= 3).then_some(at_ms),
    )
}

/// Records how far `participant` got with my message `id`, keeping the
/// first time each state was reached.
fn record_receipt(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    target: (&str, &str),
    participant: &str,
    rank: i64,
    at_ms: i64,
) -> Result<(), StoreError> {
    let (chat, id) = target;
    let (delivered, read, played) = receipt_times(rank, at_ms);
    tx.execute(
        "INSERT INTO receipts
            (instance_id, chat, id, participant, status, delivered_ms, read_ms, played_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
         ON CONFLICT (instance_id, chat, id, participant) DO UPDATE SET
            status = MAX(status, excluded.status),
            delivered_ms = COALESCE(delivered_ms, excluded.delivered_ms),
            read_ms = COALESCE(read_ms, excluded.read_ms),
            played_ms = COALESCE(played_ms, excluded.played_ms)",
        params![
            instance.as_str(),
            chat,
            id,
            participant,
            rank,
            delivered,
            read,
            played
        ],
    )?;
    Ok(())
}

/// One person's receipt times of a message: `(person, delivered, read,
/// played)`, with a LID already replaced by its number where known. A state
/// reached by a receipt stored before its time was kept (schema v11) reads
/// as time 0.
type PersonTimes = (String, Option<i64>, Option<i64>, Option<i64>);

fn receipt_times_by_person(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
    id: &str,
) -> Result<Vec<PersonTimes>, StoreError> {
    let mut stmt = conn.prepare_cached(
        "SELECT COALESCE(p.pn, r.participant) AS person, MAX(r.status),
                MIN(r.delivered_ms), MIN(r.read_ms), MIN(r.played_ms)
         FROM receipts r
         LEFT JOIN identity_pairs p
           ON p.instance_id = r.instance_id AND p.lid = r.participant
         WHERE r.instance_id = ?1 AND r.chat = ?2 AND r.id = ?3
         GROUP BY person ORDER BY person",
    )?;
    let rows = stmt.query_map(params![instance.as_str(), chat, id], |r| {
        let status: i64 = r.get(1)?;
        let reached = |rank: i64, time: Option<i64>| time.or((status >= rank).then_some(0));
        Ok((
            r.get(0)?,
            reached(1, r.get(2)?),
            reached(2, r.get(3)?),
            reached(3, r.get(4)?),
        ))
    })?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

impl Store {
    /// The early edits and deletes of message `id`, oldest first, removed
    /// from the waiting list. The caller applies them now that the message
    /// is stored.
    pub async fn take_pending(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
    ) -> Result<Vec<MessageChange>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let rows: Vec<String> = {
                let mut stmt = tx.prepare(
                    "SELECT change_json FROM pending_changes
                     WHERE instance_id = ?1 AND chat = ?2 AND id = ?3 ORDER BY at_ms",
                )?;
                stmt.query_map(params![instance.as_str(), chat, id], |r| r.get(0))?
                    .collect::<Result<_, _>>()?
            };
            if rows.is_empty() {
                return Ok(Vec::new());
            }
            tx.execute(
                "DELETE FROM pending_changes WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                params![instance.as_str(), chat, id],
            )?;
            tx.commit()?;
            Ok(rows
                .iter()
                .filter_map(|json| serde_json::from_str::<PendingChange>(json).ok())
                .map(PendingChange::into_change)
                .collect())
        })
        .await
    }

    /// Like [`Self::set_status`], recording when the receipt came: one row
    /// per one-to-one message of mine, with the chat as participant, for
    /// message info.
    pub async fn set_status_at(
        &self,
        instance: InstanceId,
        chat: String,
        ids: Vec<String>,
        status: DeliveryStatus,
        at_ms: i64,
    ) -> Result<Vec<(String, MessageMeta)>, StoreError> {
        let rank = receipt_rank(status);
        if rank > 0 {
            let (inst, chat, ids) = (instance.clone(), chat.clone(), ids.clone());
            self.run(move |conn| {
                let tx = conn.transaction()?;
                for id in &ids {
                    if matches!(change_row(&tx, &inst, &chat, id)?, Some((true, ..))) {
                        record_receipt(&tx, &inst, (&chat, id), &chat, rank, at_ms)?;
                    }
                }
                tx.commit()?;
                Ok(())
            })
            .await?;
        }
        self.set_status(instance, chat, ids, status).await
    }

    /// The chat's disappearing timer in seconds; `None` when off or unknown.
    pub async fn chat_timer(
        &self,
        instance: InstanceId,
        jid: String,
    ) -> Result<Option<u32>, StoreError> {
        self.run(move |conn| Ok(chat_meta(conn, &instance, &jid)?.ephemeral_s))
            .await
    }

    /// Sets the chat's disappearing timer (`0`: off) as of `at_ms`. A setting
    /// older than the stored one is ignored. Returns the new [`ChatMeta`]
    /// when the timer changed.
    pub async fn set_chat_timer(
        &self,
        instance: InstanceId,
        jid: String,
        seconds: u32,
        at_ms: i64,
    ) -> Result<Option<ChatMeta>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let before = chat_meta(&tx, &instance, &jid)?;
            tx.execute(
                "INSERT INTO chat_settings (instance_id, jid, ephemeral_s, ephemeral_ms)
                 VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(instance_id, jid) DO UPDATE SET
                    ephemeral_s = excluded.ephemeral_s, ephemeral_ms = excluded.ephemeral_ms
                 WHERE excluded.ephemeral_ms >= COALESCE(ephemeral_ms, 0)",
                params![
                    instance.as_str(),
                    jid,
                    (seconds > 0).then_some(seconds),
                    at_ms
                ],
            )?;
            let after = chat_meta(&tx, &instance, &jid)?;
            tx.commit()?;
            Ok((after.ephemeral_s != before.ephemeral_s).then_some(after))
        })
        .await
    }

    /// Whether I left the group `jid` or was removed from it (E6-WA R6).
    pub async fn group_left(&self, instance: InstanceId, jid: String) -> Result<bool, StoreError> {
        self.run(move |conn| Ok(chat_meta(conn, &instance, &jid)?.left))
            .await
    }

    /// Records that I left the group `jid` (or was removed) as of `at_ms`,
    /// or that I am a member again. A change older than the stored leave
    /// does not bring me back. Returns the new [`ChatMeta`] when it changed.
    pub async fn set_group_left(
        &self,
        instance: InstanceId,
        jid: String,
        left: bool,
        at_ms: i64,
    ) -> Result<Option<ChatMeta>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let before = chat_meta(&tx, &instance, &jid)?;
            if left {
                tx.execute(
                    "INSERT INTO chat_settings (instance_id, jid, left_ms) VALUES (?1, ?2, ?3)
                     ON CONFLICT(instance_id, jid) DO UPDATE SET left_ms = excluded.left_ms
                     WHERE left_ms IS NULL",
                    params![instance.as_str(), jid, at_ms],
                )?;
            } else {
                tx.execute(
                    "UPDATE chat_settings SET left_ms = NULL
                     WHERE instance_id = ?1 AND jid = ?2 AND left_ms <= ?3",
                    params![instance.as_str(), jid, at_ms],
                )?;
            }
            let after = chat_meta(&tx, &instance, &jid)?;
            tx.commit()?;
            Ok((after.left != before.left).then_some(after))
        })
        .await
    }

    /// Who got, read or played my message `id` of `chat`, and when. `members`
    /// are the group's members today (bare, by number where known; `None`
    /// when unknown or for a one-to-one chat): a member with no receipt yet
    /// is listed with no times, and a receipt of someone not among them is
    /// marked `left`. `own` are this account's ids, never listed. A time of
    /// 0 is a state reached before the gateway kept times. `None` for an
    /// unknown message or one that is not mine.
    pub async fn message_receipts(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
        members: Option<Vec<String>>,
        own: Vec<String>,
    ) -> Result<Option<MessageReceipts>, StoreError> {
        self.run(move |conn| {
            let Some((true, ..)) = change_row(conn, &instance, &chat, &id)? else {
                return Ok(None);
            };
            let rows = receipt_times_by_person(conn, &instance, &chat, &id)?;
            let group = chat.ends_with("@g.us");
            let mut out: Vec<ReceiptMember> = Vec::new();
            for (person, delivered_ms, read_ms, played_ms) in rows {
                if own.contains(&person) {
                    continue;
                }
                let left = group
                    && members
                        .as_ref()
                        .is_some_and(|members| !members.contains(&person));
                out.push(ReceiptMember {
                    jid: person,
                    delivered_ms,
                    read_ms,
                    played_ms,
                    left,
                    ..ReceiptMember::default()
                });
            }
            if group {
                for member in members.iter().flatten() {
                    if !own.contains(member) && !out.iter().any(|m| &m.jid == member) {
                        out.push(ReceiptMember {
                            jid: member.clone(),
                            ..ReceiptMember::default()
                        });
                    }
                }
            } else if out.is_empty() {
                out.push(ReceiptMember {
                    jid: chat.clone(),
                    ..ReceiptMember::default()
                });
            }
            let mut alts = Alts::new(conn, &instance);
            for member in &mut out {
                member.jid_alt = alts.of(&member.jid, None)?;
                member.name = conn
                    .query_row(
                        "SELECT push_name FROM profiles WHERE instance_id = ?1 AND jid = ?2",
                        params![instance.as_str(), member.jid],
                        |r| r.get::<_, Option<String>>(0),
                    )
                    .optional()?
                    .flatten();
            }
            Ok(Some(MessageReceipts {
                chat: chat.clone(),
                id: id.clone(),
                members: out,
            }))
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rosty_wa_proto::MediaKind;

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
        use rosty_wa_proto::{MediaMeta, Quoted};
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

    #[test]
    fn receipts_are_counted_by_person_without_my_own_devices() {
        let rows = |rows: &[(&str, i64)]| -> Vec<(String, i64)> {
            rows.iter()
                .map(|(who, s)| ((*who).to_owned(), *s))
                .collect()
        };
        let own = ["me@s.whatsapp.net".to_owned(), "777@lid".to_owned()];
        assert_eq!(count_receipts(Vec::new(), &own), ReceiptCounts::default());
        assert_eq!(
            count_receipts(rows(&[("a", 1), ("b", 2), ("c", 3)]), &own),
            ReceiptCounts {
                delivered: 3,
                read: 2,
                played: 1,
            }
        );
        // My devices ack what I send; that says nothing about the members.
        assert_eq!(
            count_receipts(
                rows(&[("me@s.whatsapp.net", 3), ("777@lid", 2), ("a", 1)]),
                &own
            ),
            ReceiptCounts {
                delivered: 1,
                read: 0,
                played: 0,
            }
        );
        assert_eq!(
            count_receipts(rows(&[("me@s.whatsapp.net", 3)]), &[]).delivered,
            1
        );
    }
}
