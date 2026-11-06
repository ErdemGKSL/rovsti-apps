//! Message meta, chat settings and profiles (schema v3).

use rovsti_wa_proto::{
    ChatMeta, DeliveryStatus, MessageBody, MessageMeta, RichChat, RichMessage, WaProfile,
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
    /// Deleted for everyone.
    Revoke,
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

/// Applies `change` to a message. Returns whether the body changed and
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
        MessageChange::Revoke => {
            if meta.revoked {
                return (false, false);
            }
            *body = MessageBody::Unsupported;
            meta.revoked = true;
            meta.quoted = None;
            meta.media = None;
            meta.location = None;
            meta.contact = None;
            meta.mentions.clear();
            meta.edited_ms = None;
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

/// One message row with its meta, newest first.
type RichRow = (String, Option<String>, bool, i64, String, Option<String>);

fn rich_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<RichRow> {
    Ok((
        r.get(0)?,
        r.get(1)?,
        r.get(2)?,
        r.get(3)?,
        r.get(4)?,
        r.get(5)?,
    ))
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
            let mut stmt = conn.prepare(
                "SELECT id, sender, from_me, ts_ms, body_json, meta_json FROM messages
                 WHERE instance_id = ?1 AND chat = ?2 AND ts_ms < ?3
                 ORDER BY ts_ms DESC, id DESC LIMIT ?4",
            )?;
            let rows = stmt
                .query_map(
                    params![
                        instance.as_str(),
                        chat,
                        before_ms.unwrap_or(i64::MAX),
                        limit
                    ],
                    rich_row,
                )?
                .collect::<Result<Vec<_>, _>>()?;
            let mut reactions = reactions_of(conn, &instance, &chat)?;
            rows.into_iter()
                .map(|(id, sender, from_me, timestamp_ms, body, meta)| {
                    Ok(RichMessage {
                        message: rovsti_wa_proto::WaMessage {
                            reactions: reactions.remove(&id).unwrap_or_default(),
                            id,
                            chat: chat.clone(),
                            sender,
                            from_me,
                            timestamp_ms,
                            body: serde_json::from_str(&body)?,
                        },
                        meta: meta_from(meta)?,
                    })
                })
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
        self.run(move |conn| {
            let row = conn
                .query_row(
                    "SELECT id, sender, from_me, ts_ms, body_json, meta_json FROM messages
                     WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                    rich_row,
                )
                .optional()?;
            let Some((id, sender, from_me, timestamp_ms, body, meta)) = row else {
                return Ok(None);
            };
            let mut reactions = reactions_of(conn, &instance, &chat)?;
            Ok(Some(RichMessage {
                message: rovsti_wa_proto::WaMessage {
                    reactions: reactions.remove(&id).unwrap_or_default(),
                    id,
                    chat,
                    sender,
                    from_me,
                    timestamp_ms,
                    body: serde_json::from_str(&body)?,
                },
                meta: meta_from(meta)?,
            }))
        })
        .await
    }

    /// Chats with their settings, picture id and the newest message's meta,
    /// most recently active first.
    pub async fn chats_rich(&self, instance: InstanceId) -> Result<Vec<RichChat>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT jid, name, is_group, unread FROM chats
                 WHERE instance_id = ?1 ORDER BY last_ms DESC, jid",
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
                let last = last_message_row(conn, &instance, &jid)?;
                let (last, last_meta) = match last {
                    Some((message, meta)) => (Some(message), Some(meta_from(meta)?)),
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
            let row = tx
                .query_row(
                    "SELECT from_me, body_json, meta_json FROM messages
                     WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                    |r| {
                        Ok((
                            r.get::<_, bool>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, Option<String>>(2)?,
                        ))
                    },
                )
                .optional()?;
            let Some((from_me, body, meta)) = row else {
                return Ok(None);
            };
            let mut body: MessageBody = serde_json::from_str(&body)?;
            let mut meta = meta_from(meta)?;
            let revoke = change == MessageChange::Revoke;
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
            if revoke {
                // Nothing of a deleted message is kept.
                tx.execute(
                    "UPDATE messages SET raw_proto = NULL, thumb = NULL, secret = NULL
                     WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                )?;
                tx.execute(
                    "DELETE FROM reactions WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                )?;
            }
            tx.commit()?;
            Ok(Some((body_changed.then_some(body), meta)))
        })
        .await
    }

    /// Records a receipt for my messages `ids` in `chat`.
    pub async fn set_status(
        &self,
        instance: InstanceId,
        chat: String,
        ids: Vec<String>,
        status: DeliveryStatus,
    ) -> Result<(), StoreError> {
        for id in ids {
            self.change_message(
                instance.clone(),
                chat.clone(),
                id,
                MessageChange::Status(status),
            )
            .await?;
        }
        Ok(())
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

    /// Deletes one message and its reactions. False when it was not stored.
    pub async fn delete_message(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
    ) -> Result<bool, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let n = tx.execute(
                "DELETE FROM messages WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                params![instance.as_str(), chat, id],
            )?;
            tx.execute(
                "DELETE FROM reactions WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                params![instance.as_str(), chat, id],
            )?;
            tx.commit()?;
            Ok(n > 0)
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
        assert_eq!(
            apply_change(&mut body, &mut meta, true, MessageChange::Revoke),
            (true, true)
        );
        assert_eq!(body, MessageBody::Unsupported);
        assert!(meta.revoked && meta.edited_ms.is_none());
        // A deleted message cannot be edited or deleted again.
        assert_eq!(
            apply_change(&mut body, &mut meta, true, edit("x", 9)),
            (false, false)
        );
        assert_eq!(
            apply_change(&mut body, &mut meta, true, MessageChange::Revoke),
            (false, false)
        );
    }
}
