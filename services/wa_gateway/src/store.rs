//! SQLite persistence: instances, hashed connect keys, push tokens and chat
//! history. One connection behind a mutex; every call runs on the blocking
//! pool so the async runtime never waits on disk.

use std::collections::HashMap;
use std::fmt;
use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use rovsti_wa_proto::{
    AppId, ChatSummary, InstanceState, InstanceStatus, KeyHash, KeyId, KeyInfo, MessageBody,
    Reaction, ScheduledState, ScheduledText, WaMessage, is_status_chat,
};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

mod merge;
mod rich;
mod status;
pub use merge::MergeReport;
pub use rich::{
    ChatChange, ChatSettings, FetchedProfile, MAX_THUMBNAIL_BYTES, MessageChange, MessageExtras,
    ProfileChange, ReceiptCounts, apply_change, group_status, merge_edit,
};
pub use status::{StatusTarget, StoredStatus};

/// Active (not revoked) keys one instance may hold.
pub const MAX_KEYS_PER_INSTANCE: u32 = 32;

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("sqlite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("stored json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("limit reached")]
    LimitReached,
    #[error("not found")]
    NotFound,
    #[error("database task failed: {0}")]
    Task(String),
    #[error("{0}")]
    Corrupt(String),
}

/// Opaque id of a linked WhatsApp account on this gateway.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct InstanceId(String);

impl InstanceId {
    pub fn from_bytes(bytes: &[u8; 12]) -> Self {
        let mut s = String::with_capacity(24);
        for b in bytes {
            s.push_str(&format!("{b:02x}"));
        }
        Self(s)
    }

    pub fn new(s: impl Into<String>) -> Self {
        Self(s.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for InstanceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// A key to store: only its hash, never the key itself.
#[derive(Clone, Debug)]
pub struct NewKey {
    pub key_id: KeyId,
    pub hash: KeyHash,
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredInstance {
    pub id: InstanceId,
    pub state: InstanceState,
}

/// A key id read back from the database or the wire.
pub fn parse_key_id(s: &str) -> Option<KeyId> {
    serde_json::from_value(serde_json::Value::String(s.to_owned())).ok()
}

/// A chat as a history sync describes it.
#[derive(Clone, Debug, PartialEq)]
pub struct HistoryChat {
    pub jid: String,
    pub name: Option<String>,
    pub is_group: bool,
    pub unread: u32,
    pub last_ms: i64,
    pub messages: Vec<HistoryMessage>,
    /// Archive, pin, mute and unread mark at sync time.
    pub settings: ChatSettings,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HistoryMessage {
    pub message: WaMessage,
    pub extras: MessageExtras,
    /// Reactions the sync carries with the message.
    pub reactions: Vec<Reaction>,
}

/// The 32 raw bytes of a hash (stored as a BLOB).
fn hash_bytes(hash: &KeyHash) -> Vec<u8> {
    let hex = hash.to_hex();
    hex.as_bytes()
        .chunks(2)
        .filter_map(|p| u8::from_str_radix(std::str::from_utf8(p).ok()?, 16).ok())
        .collect()
}

fn hash_from_bytes(bytes: &[u8]) -> Option<KeyHash> {
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    KeyHash::from_hex(&hex).ok()
}

fn to_tag<T: serde::Serialize>(value: &T) -> String {
    match serde_json::to_value(value) {
        Ok(serde_json::Value::String(s)) => s,
        _ => String::new(),
    }
}

fn from_tag<T: serde::de::DeserializeOwned>(tag: &str) -> Result<T, StoreError> {
    Ok(serde_json::from_value(serde_json::Value::String(
        tag.to_owned(),
    ))?)
}

const MIGRATIONS: &[&str] = &[
    // v1
    "
    CREATE TABLE instances (
        id TEXT PRIMARY KEY,
        created_ms INTEGER NOT NULL,
        state TEXT NOT NULL,
        own_jid TEXT,
        push_name TEXT
    );
    CREATE TABLE keys (
        key_id TEXT PRIMARY KEY,
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        hash BLOB NOT NULL UNIQUE,
        label TEXT NOT NULL,
        created_ms INTEGER NOT NULL,
        last_used_ms INTEGER,
        revoked INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX keys_by_instance ON keys(instance_id);
    CREATE TABLE push_tokens (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        app TEXT NOT NULL,
        token TEXT NOT NULL,
        PRIMARY KEY (instance_id, app)
    );
    CREATE TABLE chats (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        jid TEXT NOT NULL,
        name TEXT,
        is_group INTEGER NOT NULL DEFAULT 0,
        unread INTEGER NOT NULL DEFAULT 0,
        last_ms INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (instance_id, jid)
    );
    CREATE TABLE messages (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        chat TEXT NOT NULL,
        id TEXT NOT NULL,
        sender TEXT,
        from_me INTEGER NOT NULL,
        ts_ms INTEGER NOT NULL,
        body_json TEXT NOT NULL,
        raw_proto BLOB,
        PRIMARY KEY (instance_id, chat, id)
    );
    CREATE INDEX messages_by_time ON messages(instance_id, chat, ts_ms);
    CREATE TABLE reactions (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        chat TEXT NOT NULL,
        id TEXT NOT NULL,
        sender TEXT NOT NULL,
        from_me INTEGER NOT NULL DEFAULT 0,
        emoji TEXT NOT NULL,
        PRIMARY KEY (instance_id, chat, id, sender)
    );
    ",
    // v2
    "
    CREATE TABLE scheduled (
        id TEXT PRIMARY KEY,
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        chat TEXT NOT NULL,
        text TEXT NOT NULL,
        send_at_ms INTEGER NOT NULL,
        created_ms INTEGER NOT NULL,
        state_json TEXT NOT NULL
    );
    CREATE INDEX scheduled_due ON scheduled(state_json, send_at_ms);
    CREATE INDEX scheduled_by_instance ON scheduled(instance_id, send_at_ms);
    ",
    // v3
    "
    ALTER TABLE messages ADD COLUMN meta_json TEXT;
    ALTER TABLE messages ADD COLUMN secret BLOB;
    ALTER TABLE messages ADD COLUMN thumb BLOB;
    CREATE INDEX messages_by_id ON messages(instance_id, id);
    CREATE TABLE chat_settings (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        jid TEXT NOT NULL,
        archived INTEGER NOT NULL DEFAULT 0,
        pinned INTEGER NOT NULL DEFAULT 0,
        muted_until_ms INTEGER,
        marked_unread INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (instance_id, jid)
    );
    CREATE TABLE profiles (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        jid TEXT NOT NULL,
        push_name TEXT,
        contact_name TEXT,
        about TEXT,
        picture_id TEXT,
        picture_checked_ms INTEGER NOT NULL DEFAULT 0,
        business INTEGER NOT NULL DEFAULT 0,
        verified_name TEXT,
        fetched_ms INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (instance_id, jid)
    );
    ",
    // v4: how far each group member got with my group messages
    // (1 delivered, 2 read, 3 played), for group-wide ticks.
    "
    CREATE TABLE receipts (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        chat TEXT NOT NULL,
        id TEXT NOT NULL,
        participant TEXT NOT NULL,
        status INTEGER NOT NULL,
        PRIMARY KEY (instance_id, chat, id, participant)
    );
    ",
    // v5: status updates (stories) live apart from the chats. Older
    // gateways stored them as a `status@broadcast` chat: drop that.
    "
    CREATE TABLE statuses (
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        author TEXT NOT NULL,
        participant TEXT NOT NULL,
        from_me INTEGER NOT NULL DEFAULT 0,
        ts_ms INTEGER NOT NULL,
        expires_ms INTEGER NOT NULL,
        item_json TEXT NOT NULL,
        raw_proto BLOB,
        thumb BLOB,
        viewed INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (instance_id, id)
    );
    CREATE INDEX statuses_by_expiry ON statuses(expires_ms);
    DELETE FROM reactions WHERE chat = 'status@broadcast';
    DELETE FROM receipts WHERE chat = 'status@broadcast';
    DELETE FROM messages WHERE chat = 'status@broadcast';
    DELETE FROM chat_settings WHERE jid = 'status@broadcast';
    DELETE FROM chats WHERE jid = 'status@broadcast';
    ",
];

/// Brings `conn` to the latest schema using `PRAGMA user_version`.
pub fn migrate(conn: &mut Connection) -> Result<(), StoreError> {
    let current: u32 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    let latest = MIGRATIONS.len() as u32;
    if current > latest {
        return Err(StoreError::Corrupt(format!(
            "database schema v{current} is newer than this gateway (v{latest})"
        )));
    }
    for (index, sql) in MIGRATIONS.iter().enumerate().skip(current as usize) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        tx.execute_batch(&format!("PRAGMA user_version = {}", index + 1))?;
        tx.commit()?;
    }
    Ok(())
}

#[derive(Clone)]
pub struct Store {
    conn: Arc<Mutex<Connection>>,
}

impl fmt::Debug for Store {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Store")
    }
}

fn lock(conn: &Mutex<Connection>) -> MutexGuard<'_, Connection> {
    // A panic mid-query leaves the connection usable; keep serving.
    conn.lock().unwrap_or_else(|e| e.into_inner())
}

impl Store {
    pub fn open(path: &Path) -> Result<Self, StoreError> {
        Self::from_connection(Connection::open(path)?)
    }

    pub fn open_in_memory() -> Result<Self, StoreError> {
        Self::from_connection(Connection::open_in_memory()?)
    }

    fn from_connection(mut conn: Connection) -> Result<Self, StoreError> {
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.execute_batch("PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;")?;
        migrate(&mut conn)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    async fn run<T, F>(&self, f: F) -> Result<T, StoreError>
    where
        T: Send + 'static,
        F: FnOnce(&mut Connection) -> Result<T, StoreError> + Send + 'static,
    {
        let conn = Arc::clone(&self.conn);
        tokio::task::spawn_blocking(move || f(&mut lock(&conn)))
            .await
            .map_err(|e| StoreError::Task(e.to_string()))?
    }

    /// Creates an instance with its first key. Fails with `LimitReached` when
    /// `max_instances` exist already; the check and the insert share one
    /// transaction so concurrent creates cannot overshoot.
    pub async fn create_instance(
        &self,
        id: InstanceId,
        first_key: NewKey,
        now_ms: i64,
        max_instances: u32,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let count: u32 = tx.query_row("SELECT COUNT(*) FROM instances", [], |r| r.get(0))?;
            if count >= max_instances {
                return Err(StoreError::LimitReached);
            }
            tx.execute(
                "INSERT INTO instances (id, created_ms, state) VALUES (?1, ?2, ?3)",
                params![id.as_str(), now_ms, to_tag(&InstanceState::Linking)],
            )?;
            insert_key(&tx, &id, &first_key, now_ms)?;
            tx.commit()?;
            Ok(())
        })
        .await
    }

    /// Stores a pending scheduled text. Fails with `LimitReached` when the
    /// instance already holds `max_pending` pending ones.
    pub async fn add_scheduled(
        &self,
        instance: InstanceId,
        item: ScheduledText,
        max_pending: u32,
    ) -> Result<(), StoreError> {
        let state = pending_json()?;
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let count: u32 = tx.query_row(
                "SELECT COUNT(*) FROM scheduled WHERE instance_id = ?1 AND state_json = ?2",
                params![instance.as_str(), state],
                |r| r.get(0),
            )?;
            if count >= max_pending {
                return Err(StoreError::LimitReached);
            }
            tx.execute(
                "INSERT INTO scheduled
                 (id, instance_id, chat, text, send_at_ms, created_ms, state_json)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    item.id,
                    instance.as_str(),
                    item.chat,
                    item.text,
                    item.send_at_ms,
                    item.created_ms,
                    serde_json::to_string(&item.state)?
                ],
            )?;
            tx.commit()?;
            Ok(())
        })
        .await
    }

    /// Every pending item, plus finished ones that were due at or after
    /// `finished_since_ms`, soonest first.
    pub async fn list_scheduled(
        &self,
        instance: InstanceId,
        finished_since_ms: i64,
    ) -> Result<Vec<ScheduledText>, StoreError> {
        let pending = pending_json()?;
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT id, chat, text, send_at_ms, created_ms, state_json FROM scheduled
                 WHERE instance_id = ?1 AND (state_json = ?2 OR send_at_ms >= ?3)
                 ORDER BY send_at_ms, id",
            )?;
            let rows = stmt.query_map(
                params![instance.as_str(), pending, finished_since_ms],
                scheduled_row,
            )?;
            rows.map(|row| row?.into_item()).collect()
        })
        .await
    }

    /// Removes a pending item. False when there is none (unknown id, or it
    /// was sent or failed already).
    pub async fn cancel_scheduled(
        &self,
        instance: InstanceId,
        id: String,
    ) -> Result<bool, StoreError> {
        let pending = pending_json()?;
        self.run(move |conn| {
            let n = conn.execute(
                "DELETE FROM scheduled WHERE id = ?1 AND instance_id = ?2 AND state_json = ?3",
                params![id, instance.as_str(), pending],
            )?;
            Ok(n > 0)
        })
        .await
    }

    /// Pending items of every instance whose time has come, oldest first.
    pub async fn due_scheduled(
        &self,
        now_ms: i64,
        limit: u32,
    ) -> Result<Vec<(InstanceId, ScheduledText)>, StoreError> {
        let pending = pending_json()?;
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT id, chat, text, send_at_ms, created_ms, state_json, instance_id
                 FROM scheduled WHERE state_json = ?1 AND send_at_ms <= ?2
                 ORDER BY send_at_ms, id LIMIT ?3",
            )?;
            let rows = stmt.query_map(params![pending, now_ms, limit], |r| {
                Ok((scheduled_row(r)?, r.get::<_, String>(6)?))
            })?;
            let mut out = Vec::new();
            for row in rows {
                let (row, instance) = row?;
                out.push((InstanceId::new(instance), row.into_item()?));
            }
            Ok(out)
        })
        .await
    }

    /// Records the outcome of a pending item. False when it is no longer
    /// pending (cancelled meanwhile).
    pub async fn finish_scheduled(
        &self,
        id: String,
        state: ScheduledState,
    ) -> Result<bool, StoreError> {
        let pending = pending_json()?;
        let state = serde_json::to_string(&state)?;
        self.run(move |conn| {
            let n = conn.execute(
                "UPDATE scheduled SET state_json = ?1 WHERE id = ?2 AND state_json = ?3",
                params![state, id, pending],
            )?;
            Ok(n > 0)
        })
        .await
    }

    /// Deletes finished items that were due before `before_ms`.
    pub async fn prune_scheduled(&self, before_ms: i64) -> Result<usize, StoreError> {
        let pending = pending_json()?;
        self.run(move |conn| {
            Ok(conn.execute(
                "DELETE FROM scheduled WHERE state_json != ?1 AND send_at_ms < ?2",
                params![pending, before_ms],
            )?)
        })
        .await
    }

    pub async fn count_instances(&self) -> Result<u32, StoreError> {
        self.run(|conn| Ok(conn.query_row("SELECT COUNT(*) FROM instances", [], |r| r.get(0))?))
            .await
    }

    pub async fn list_instances(&self) -> Result<Vec<StoredInstance>, StoreError> {
        self.run(|conn| {
            let mut stmt =
                conn.prepare("SELECT id, state FROM instances ORDER BY created_ms, id")?;
            let rows =
                stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
            let mut out = Vec::new();
            for row in rows {
                let (id, state) = row?;
                out.push(StoredInstance {
                    id: InstanceId::new(id),
                    state: from_tag(&state)?,
                });
            }
            Ok(out)
        })
        .await
    }

    pub async fn delete_instance(&self, id: InstanceId) -> Result<bool, StoreError> {
        self.run(move |conn| {
            let n = conn.execute("DELETE FROM instances WHERE id = ?1", [id.as_str()])?;
            Ok(n > 0)
        })
        .await
    }

    pub async fn instance_status(
        &self,
        id: InstanceId,
    ) -> Result<Option<InstanceStatus>, StoreError> {
        self.run(move |conn| {
            let row = conn
                .query_row(
                    "SELECT state, own_jid, push_name FROM instances WHERE id = ?1",
                    [id.as_str()],
                    |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, Option<String>>(1)?,
                            r.get::<_, Option<String>>(2)?,
                        ))
                    },
                )
                .optional()?;
            row.map(|(state, own_jid, push_name)| {
                Ok(InstanceStatus {
                    instance_id: id.to_string(),
                    state: from_tag(&state)?,
                    own_jid,
                    push_name,
                })
            })
            .transpose()
        })
        .await
    }

    pub async fn set_state(
        &self,
        id: InstanceId,
        state: InstanceState,
        own_jid: Option<String>,
        push_name: Option<String>,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            // Keep what we know when a later event does not repeat it.
            conn.execute(
                "UPDATE instances SET state = ?2, own_jid = COALESCE(?3, own_jid),
                 push_name = COALESCE(?4, push_name) WHERE id = ?1",
                params![id.as_str(), to_tag(&state), own_jid, push_name],
            )?;
            Ok(())
        })
        .await
    }

    /// Looks a key up by its hash. A revoked key finds nothing. Bumps
    /// `last_used_ms` on a hit.
    pub async fn instance_for_key(
        &self,
        hash: KeyHash,
        now_ms: i64,
    ) -> Result<Option<(InstanceId, KeyId)>, StoreError> {
        self.run(move |conn| {
            let row = conn
                .query_row(
                    "SELECT instance_id, key_id, hash FROM keys WHERE hash = ?1 AND revoked = 0",
                    [hash_bytes(&hash)],
                    |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, Vec<u8>>(2)?,
                        ))
                    },
                )
                .optional()?;
            let Some((instance, key_id, stored)) = row else {
                return Ok(None);
            };
            let stored = hash_from_bytes(&stored);
            if !stored.is_some_and(|s| s.ct_eq(&hash)) {
                return Ok(None);
            }
            let key_id = parse_key_id(&key_id)
                .ok_or_else(|| StoreError::Corrupt("bad key id in database".into()))?;
            conn.execute(
                "UPDATE keys SET last_used_ms = ?2 WHERE key_id = ?1",
                params![key_id.as_str(), now_ms],
            )?;
            Ok(Some((InstanceId::new(instance), key_id)))
        })
        .await
    }

    /// True while the key exists and is not revoked.
    pub async fn key_active(&self, key_id: KeyId) -> Result<bool, StoreError> {
        self.run(move |conn| {
            let n: u32 = conn.query_row(
                "SELECT COUNT(*) FROM keys WHERE key_id = ?1 AND revoked = 0",
                [key_id.as_str()],
                |r| r.get(0),
            )?;
            Ok(n > 0)
        })
        .await
    }

    pub async fn mint_key(
        &self,
        instance: InstanceId,
        key: NewKey,
        now_ms: i64,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let exists: u32 = tx.query_row(
                "SELECT COUNT(*) FROM instances WHERE id = ?1",
                [instance.as_str()],
                |r| r.get(0),
            )?;
            if exists == 0 {
                return Err(StoreError::NotFound);
            }
            let active: u32 = tx.query_row(
                "SELECT COUNT(*) FROM keys WHERE instance_id = ?1 AND revoked = 0",
                [instance.as_str()],
                |r| r.get(0),
            )?;
            if active >= MAX_KEYS_PER_INSTANCE {
                return Err(StoreError::LimitReached);
            }
            insert_key(&tx, &instance, &key, now_ms)?;
            tx.commit()?;
            Ok(())
        })
        .await
    }

    pub async fn list_keys(&self, instance: InstanceId) -> Result<Vec<KeyInfo>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT key_id, label, created_ms, last_used_ms FROM keys
                 WHERE instance_id = ?1 AND revoked = 0 ORDER BY created_ms, key_id",
            )?;
            let rows = stmt.query_map([instance.as_str()], |r| {
                Ok(KeyInfo {
                    key_id: r.get(0)?,
                    label: r.get(1)?,
                    created_ms: r.get(2)?,
                    last_used_ms: r.get(3)?,
                    current: false,
                })
            })?;
            Ok(rows.collect::<Result<Vec<_>, _>>()?)
        })
        .await
    }

    /// Revokes a key of `instance`. False when there is no such live key.
    pub async fn revoke_key(
        &self,
        instance: InstanceId,
        key_id: String,
    ) -> Result<bool, StoreError> {
        self.run(move |conn| {
            let n = conn.execute(
                "UPDATE keys SET revoked = 1 WHERE instance_id = ?1 AND key_id = ?2 AND revoked = 0",
                params![instance.as_str(), key_id],
            )?;
            Ok(n > 0)
        })
        .await
    }

    pub async fn set_push_token(
        &self,
        instance: InstanceId,
        app: AppId,
        token: String,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            conn.execute(
                "INSERT INTO push_tokens (instance_id, app, token) VALUES (?1, ?2, ?3)
                 ON CONFLICT(instance_id, app) DO UPDATE SET token = excluded.token",
                params![instance.as_str(), to_tag(&app), token],
            )?;
            Ok(())
        })
        .await
    }

    /// Forgets a device token (FCM said it is dead).
    pub async fn delete_push_token(
        &self,
        instance: InstanceId,
        token: String,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            conn.execute(
                "DELETE FROM push_tokens WHERE instance_id = ?1 AND token = ?2",
                params![instance.as_str(), token],
            )?;
            Ok(())
        })
        .await
    }

    pub async fn push_tokens(
        &self,
        instance: InstanceId,
    ) -> Result<Vec<(AppId, String)>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT app, token FROM push_tokens WHERE instance_id = ?1 ORDER BY app",
            )?;
            let rows = stmt.query_map([instance.as_str()], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
            })?;
            let mut out = Vec::new();
            for row in rows {
                let (app, token) = row?;
                out.push((from_tag(&app)?, token));
            }
            Ok(out)
        })
        .await
    }

    /// Creates the chat or refreshes it. A `None` name keeps the stored one;
    /// `last_ms` only moves forward; `unread_delta` is added to the counter.
    pub async fn upsert_chat(
        &self,
        instance: InstanceId,
        jid: String,
        name: Option<String>,
        is_group: bool,
        last_ms: i64,
        unread_delta: u32,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            upsert_chat(
                conn,
                &instance,
                &jid,
                name.as_deref(),
                is_group,
                last_ms,
                unread_delta,
            )
        })
        .await
    }

    /// Stores a message and bumps its chat in one transaction. False when the
    /// message was already there (history sync and live delivery can overlap).
    pub async fn insert_message(
        &self,
        instance: InstanceId,
        message: WaMessage,
        raw_proto: Option<Vec<u8>>,
    ) -> Result<bool, StoreError> {
        let extras = MessageExtras {
            raw_proto,
            ..Default::default()
        };
        self.insert_message_full(instance, message, extras).await
    }

    pub async fn set_reaction(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
        reaction: Reaction,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            let sender = reaction_key(reaction.sender, reaction.from_me);
            if reaction.emoji.is_empty() {
                conn.execute(
                    "DELETE FROM reactions WHERE instance_id = ?1 AND chat = ?2 AND id = ?3 AND sender = ?4",
                    params![instance.as_str(), chat, id, sender],
                )?;
            } else {
                conn.execute(
                    "INSERT INTO reactions (instance_id, chat, id, sender, from_me, emoji)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                     ON CONFLICT(instance_id, chat, id, sender)
                     DO UPDATE SET emoji = excluded.emoji, from_me = excluded.from_me",
                    params![instance.as_str(), chat, id, sender, reaction.from_me, reaction.emoji],
                )?;
            }
            Ok(())
        })
        .await
    }

    /// Sets the display name of a chat that already exists (a group whose
    /// subject is known). Does nothing for an unknown chat, so listing groups
    /// does not fill the inbox with chats nobody wrote in.
    pub async fn rename_chat(
        &self,
        instance: InstanceId,
        jid: String,
        name: String,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            conn.execute(
                "UPDATE chats SET name = ?3 WHERE instance_id = ?1 AND jid = ?2",
                params![instance.as_str(), jid, name],
            )?;
            Ok(())
        })
        .await
    }

    /// The media message `id` and its encoded WhatsApp message, if stored
    /// (the newest one when several chats reuse the id).
    pub async fn media_source(
        &self,
        instance: InstanceId,
        id: String,
    ) -> Result<Option<(WaMessage, Option<Vec<u8>>)>, StoreError> {
        self.run(move |conn| {
            let row = conn
                .query_row(
                    "SELECT chat, sender, from_me, ts_ms, body_json, raw_proto FROM messages
                     WHERE instance_id = ?1 AND id = ?2 ORDER BY ts_ms DESC LIMIT 1",
                    params![instance.as_str(), id],
                    |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, Option<String>>(1)?,
                            r.get::<_, bool>(2)?,
                            r.get::<_, i64>(3)?,
                            r.get::<_, String>(4)?,
                            r.get::<_, Option<Vec<u8>>>(5)?,
                        ))
                    },
                )
                .optional()?;
            let Some((chat, sender, from_me, timestamp_ms, body, raw)) = row else {
                return Ok(None);
            };
            Ok(Some((
                WaMessage {
                    id,
                    chat,
                    sender,
                    from_me,
                    timestamp_ms,
                    body: serde_json::from_str(&body)?,
                    reactions: Vec::new(),
                },
                raw,
            )))
        })
        .await
    }

    pub async fn clear_unread(&self, instance: InstanceId, chat: String) -> Result<(), StoreError> {
        self.run(move |conn| {
            conn.execute(
                "UPDATE chats SET unread = 0 WHERE instance_id = ?1 AND jid = ?2",
                params![instance.as_str(), chat],
            )?;
            Ok(())
        })
        .await
    }

    /// One message of a chat, with its reactions.
    pub async fn message(
        &self,
        instance: InstanceId,
        chat: String,
        id: String,
    ) -> Result<Option<WaMessage>, StoreError> {
        self.run(move |conn| {
            let row = conn
                .query_row(
                    "SELECT sender, from_me, ts_ms, body_json FROM messages
                     WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    params![instance.as_str(), chat, id],
                    |r| {
                        Ok((
                            r.get::<_, Option<String>>(0)?,
                            r.get::<_, bool>(1)?,
                            r.get::<_, i64>(2)?,
                            r.get::<_, String>(3)?,
                        ))
                    },
                )
                .optional()?;
            let Some((sender, from_me, timestamp_ms, body)) = row else {
                return Ok(None);
            };
            let mut reactions = reactions_of(conn, &instance, &chat)?;
            Ok(Some(WaMessage {
                reactions: reactions.remove(&id).unwrap_or_default(),
                id,
                chat,
                sender,
                from_me,
                timestamp_ms,
                body: serde_json::from_str(&body)?,
            }))
        })
        .await
    }

    /// Chats and messages from a history sync, written in one transaction.
    /// Messages already stored are kept as they are. Returns how many chats
    /// were written.
    pub async fn import_history(
        &self,
        instance: InstanceId,
        chats: Vec<HistoryChat>,
    ) -> Result<u32, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let mut written = 0u32;
            // Status updates are stored apart, never as a chat.
            for chat in chats.iter().filter(|c| !is_status_chat(&c.jid)) {
                tx.execute(
                    "INSERT INTO chats (instance_id, jid, name, is_group, unread, last_ms)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                     ON CONFLICT(instance_id, jid) DO UPDATE SET
                        name = COALESCE(excluded.name, name),
                        is_group = excluded.is_group,
                        unread = MAX(unread, excluded.unread),
                        last_ms = MAX(last_ms, excluded.last_ms)",
                    params![
                        instance.as_str(),
                        chat.jid,
                        chat.name,
                        chat.is_group,
                        chat.unread,
                        chat.last_ms
                    ],
                )?;
                rich::write_settings(&tx, &instance, &chat.jid, &chat.settings)?;
                for row in &chat.messages {
                    let fresh = rich::insert_row(&tx, &instance, &chat.jid, &row.message, &row.extras)?;
                    if !fresh {
                        continue;
                    }
                    for reaction in row.reactions.iter().filter(|r| !r.emoji.is_empty()) {
                        tx.execute(
                            "INSERT OR IGNORE INTO reactions (instance_id, chat, id, sender, from_me, emoji)
                             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                            params![
                                instance.as_str(),
                                chat.jid,
                                row.message.id,
                                reaction_key(reaction.sender.clone(), reaction.from_me),
                                reaction.from_me,
                                reaction.emoji
                            ],
                        )?;
                    }
                }
                written += 1;
            }
            tx.commit()?;
            Ok(written)
        })
        .await
    }

    /// Messages of `chat`, newest first, older than `before_ms` when given.
    pub async fn messages(
        &self,
        instance: InstanceId,
        chat: String,
        before_ms: Option<i64>,
        limit: u32,
    ) -> Result<Vec<WaMessage>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT id, sender, from_me, ts_ms, body_json FROM messages
                 WHERE instance_id = ?1 AND chat = ?2 AND ts_ms < ?3
                 ORDER BY ts_ms DESC, id DESC LIMIT ?4",
            )?;
            let rows = stmt.query_map(
                params![
                    instance.as_str(),
                    chat,
                    before_ms.unwrap_or(i64::MAX),
                    limit
                ],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, Option<String>>(1)?,
                        r.get::<_, bool>(2)?,
                        r.get::<_, i64>(3)?,
                        r.get::<_, String>(4)?,
                    ))
                },
            )?;
            let mut reactions = reactions_of(conn, &instance, &chat)?;
            let mut out = Vec::new();
            for row in rows {
                let (id, sender, from_me, timestamp_ms, body) = row?;
                out.push(WaMessage {
                    reactions: reactions.remove(&id).unwrap_or_default(),
                    id,
                    chat: chat.clone(),
                    sender,
                    from_me,
                    timestamp_ms,
                    body: serde_json::from_str::<MessageBody>(&body)?,
                });
            }
            Ok(out)
        })
        .await
    }

    /// Chats, most recently active first, each with its newest message.
    pub async fn chats(&self, instance: InstanceId) -> Result<Vec<ChatSummary>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT jid, name, is_group, unread FROM chats
                 WHERE instance_id = ?1 AND jid != 'status@broadcast'
                 ORDER BY last_ms DESC, jid",
            )?;
            let rows = stmt.query_map([instance.as_str()], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, Option<String>>(1)?,
                    r.get::<_, bool>(2)?,
                    r.get::<_, u32>(3)?,
                ))
            })?;
            let mut out = Vec::new();
            for row in rows {
                let (jid, name, is_group, unread) = row?;
                let last = last_message(conn, &instance, &jid)?;
                out.push(ChatSummary {
                    jid,
                    name,
                    last,
                    unread,
                    is_group,
                });
            }
            Ok(out)
        })
        .await
    }
}

fn pending_json() -> Result<String, StoreError> {
    Ok(serde_json::to_string(&ScheduledState::Pending)?)
}

struct ScheduledRow {
    id: String,
    chat: String,
    text: String,
    send_at_ms: i64,
    created_ms: i64,
    state_json: String,
}

fn scheduled_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<ScheduledRow> {
    Ok(ScheduledRow {
        id: r.get(0)?,
        chat: r.get(1)?,
        text: r.get(2)?,
        send_at_ms: r.get(3)?,
        created_ms: r.get(4)?,
        state_json: r.get(5)?,
    })
}

impl ScheduledRow {
    fn into_item(self) -> Result<ScheduledText, StoreError> {
        Ok(ScheduledText {
            state: serde_json::from_str(&self.state_json)?,
            id: self.id,
            chat: self.chat,
            text: self.text,
            send_at_ms: self.send_at_ms,
            created_ms: self.created_ms,
        })
    }
}

fn insert_key(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    key: &NewKey,
    now_ms: i64,
) -> Result<(), StoreError> {
    tx.execute(
        "INSERT INTO keys (key_id, instance_id, hash, label, created_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            key.key_id.as_str(),
            instance.as_str(),
            hash_bytes(&key.hash),
            key.label,
            now_ms
        ],
    )?;
    Ok(())
}

fn upsert_chat(
    conn: &Connection,
    instance: &InstanceId,
    jid: &str,
    name: Option<&str>,
    is_group: bool,
    last_ms: i64,
    unread_delta: u32,
) -> Result<(), StoreError> {
    conn.execute(
        "INSERT INTO chats (instance_id, jid, name, is_group, unread, last_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(instance_id, jid) DO UPDATE SET
            name = COALESCE(excluded.name, name),
            is_group = excluded.is_group,
            unread = unread + excluded.unread,
            last_ms = MAX(last_ms, excluded.last_ms)",
        params![
            instance.as_str(),
            jid,
            name,
            is_group,
            unread_delta,
            last_ms
        ],
    )?;
    Ok(())
}

/// Stands in for the reactor's JID in the `reactions` key when the message
/// does not name one (one-to-one chats), so that my reaction and theirs to the
/// same message do not overwrite each other. Not a valid JID.
const OWN_REACTION_KEY: &str = "@me";

fn reaction_key(sender: Option<String>, from_me: bool) -> String {
    sender.unwrap_or_else(|| if from_me { OWN_REACTION_KEY } else { "" }.to_owned())
}

fn reactions_of(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
) -> Result<HashMap<String, Vec<Reaction>>, StoreError> {
    let mut stmt = conn.prepare(
        "SELECT id, sender, from_me, emoji FROM reactions
         WHERE instance_id = ?1 AND chat = ?2 ORDER BY id, sender",
    )?;
    let rows = stmt.query_map(params![instance.as_str(), chat], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, bool>(2)?,
            r.get::<_, String>(3)?,
        ))
    })?;
    let mut map: HashMap<String, Vec<Reaction>> = HashMap::new();
    for row in rows {
        let (id, sender, from_me, emoji) = row?;
        map.entry(id).or_default().push(Reaction {
            sender: (!sender.is_empty() && sender != OWN_REACTION_KEY).then_some(sender),
            from_me,
            emoji,
        });
    }
    Ok(map)
}

fn last_message(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
) -> Result<Option<WaMessage>, StoreError> {
    Ok(last_message_row(conn, instance, chat)?.map(|(message, _)| message))
}

/// The newest message of `chat` and its raw `meta_json`.
fn last_message_row(
    conn: &Connection,
    instance: &InstanceId,
    chat: &str,
) -> Result<Option<(WaMessage, Option<String>)>, StoreError> {
    let row = conn
        .query_row(
            "SELECT id, sender, from_me, ts_ms, body_json, meta_json FROM messages
             WHERE instance_id = ?1 AND chat = ?2 ORDER BY ts_ms DESC, id DESC LIMIT 1",
            params![instance.as_str(), chat],
            |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, Option<String>>(1)?,
                    r.get::<_, bool>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, String>(4)?,
                    r.get::<_, Option<String>>(5)?,
                ))
            },
        )
        .optional()?;
    let Some((id, sender, from_me, timestamp_ms, body, meta)) = row else {
        return Ok(None);
    };
    let mut reactions = reactions_of(conn, instance, chat)?;
    Ok(Some((
        WaMessage {
            reactions: reactions.remove(&id).unwrap_or_default(),
            id,
            chat: chat.to_owned(),
            sender,
            from_me,
            timestamp_ms,
            body: serde_json::from_str(&body)?,
        },
        meta,
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::ConnectKey;

    fn new_key(seed: u8, label: &str) -> (ConnectKey, NewKey) {
        let key = ConnectKey::from_bytes(&[seed; 32]);
        let new = NewKey {
            key_id: KeyId::from_bytes(&[seed; 8]),
            hash: key.hash(),
            label: label.to_owned(),
        };
        (key, new)
    }

    fn id(n: u8) -> InstanceId {
        InstanceId::from_bytes(&[n; 12])
    }

    fn text(chat: &str, id: &str, ts: i64, from_me: bool) -> WaMessage {
        WaMessage {
            id: id.into(),
            chat: chat.into(),
            sender: None,
            from_me,
            timestamp_ms: ts,
            body: MessageBody::Text(format!("hello {id}")),
            reactions: vec![],
        }
    }

    #[tokio::test]
    async fn migration_sets_user_version_and_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("gateway.db");
        drop(Store::open(&path).unwrap());
        // Re-opening runs no migration again and must keep the data.
        let store = Store::open(&path).unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 10, 4)
            .await
            .unwrap();
        drop(store);
        let store = Store::open(&path).unwrap();
        assert_eq!(store.count_instances().await.unwrap(), 1);

        let conn = Connection::open(&path).unwrap();
        let version: u32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, MIGRATIONS.len() as u32);
        let tables: Vec<String> = conn
            .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        for t in [
            "chats",
            "instances",
            "keys",
            "messages",
            "push_tokens",
            "reactions",
            "scheduled",
            "chat_settings",
            "profiles",
            "receipts",
            "statuses",
        ] {
            assert!(tables.iter().any(|n| n == t), "missing {t}: {tables:?}");
        }
    }

    #[tokio::test]
    async fn v1_database_upgrades_to_the_latest_and_keeps_its_data() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("gateway.db");
        {
            let mut conn = Connection::open(&path).unwrap();
            conn.execute_batch("PRAGMA foreign_keys = ON").unwrap();
            let tx = conn.transaction().unwrap();
            tx.execute_batch(MIGRATIONS[0]).unwrap();
            tx.execute_batch("PRAGMA user_version = 1").unwrap();
            tx.execute(
                "INSERT INTO instances (id, created_ms, state) VALUES ('old', 1, 'connected')",
                [],
            )
            .unwrap();
            tx.commit().unwrap();
        }
        let store = Store::open(&path).unwrap();
        assert_eq!(store.count_instances().await.unwrap(), 1);
        let item = sched("s1", 100);
        store
            .add_scheduled(InstanceId::new("old"), item.clone(), 5)
            .await
            .unwrap();
        drop(store);
        // A second open must not migrate again or lose anything.
        let store = Store::open(&path).unwrap();
        assert_eq!(store.count_instances().await.unwrap(), 1);
        assert_eq!(
            store
                .list_scheduled(InstanceId::new("old"), 0)
                .await
                .unwrap(),
            vec![item]
        );
        let conn = Connection::open(&path).unwrap();
        let version: u32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, MIGRATIONS.len() as u32);
    }

    #[tokio::test]
    async fn v5_drops_the_status_chat_an_older_gateway_stored() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("gateway.db");
        {
            let mut conn = Connection::open(&path).unwrap();
            let tx = conn.transaction().unwrap();
            for sql in &MIGRATIONS[..4] {
                tx.execute_batch(sql).unwrap();
            }
            tx.execute_batch("PRAGMA user_version = 4").unwrap();
            tx.execute_batch(
                "INSERT INTO instances (id, created_ms, state) VALUES ('old', 1, 'connected');
                 INSERT INTO chats (instance_id, jid, unread, last_ms)
                     VALUES ('old', 'status@broadcast', 3, 9),
                            ('old', '1@s.whatsapp.net', 1, 5);
                 INSERT INTO messages (instance_id, chat, id, from_me, ts_ms, body_json)
                     VALUES ('old', 'status@broadcast', 'S1', 0, 9, '{\"text\":\"story\"}'),
                            ('old', '1@s.whatsapp.net', 'M1', 0, 5, '{\"text\":\"hi\"}');
                 INSERT INTO reactions (instance_id, chat, id, sender, emoji)
                     VALUES ('old', 'status@broadcast', 'S1', 'x', 'y');",
            )
            .unwrap();
            tx.commit().unwrap();
        }
        let store = Store::open(&path).unwrap();
        let old = InstanceId::new("old");
        let chats = store.chats(old.clone()).await.unwrap();
        assert_eq!(chats.len(), 1);
        assert_eq!(chats[0].jid, "1@s.whatsapp.net");
        assert!(
            store
                .messages(old.clone(), "status@broadcast".into(), None, 10)
                .await
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            store
                .messages(old, "1@s.whatsapp.net".into(), None, 10)
                .await
                .unwrap()
                .len(),
            1
        );
    }

    #[tokio::test]
    async fn status_messages_never_become_chats() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 10, 4)
            .await
            .unwrap();
        let fresh = store
            .insert_message(id(1), text("status@broadcast", "S1", 5, false), None)
            .await
            .unwrap();
        assert!(!fresh);
        let written = store
            .import_history(
                id(1),
                vec![HistoryChat {
                    jid: "status@broadcast".into(),
                    name: None,
                    is_group: false,
                    unread: 4,
                    last_ms: 5,
                    messages: vec![],
                    settings: ChatSettings::default(),
                }],
            )
            .await
            .unwrap();
        assert_eq!(written, 0);
        assert!(store.chats(id(1)).await.unwrap().is_empty());
        assert!(store.chats_rich(id(1)).await.unwrap().is_empty());
    }

    fn sched(id: &str, at: i64) -> ScheduledText {
        ScheduledText {
            id: id.into(),
            chat: "c@s.whatsapp.net".into(),
            text: format!("t-{id}"),
            send_at_ms: at,
            created_ms: 1,
            state: ScheduledState::Pending,
        }
    }

    #[tokio::test]
    async fn scheduled_lifecycle() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 1, 4)
            .await
            .unwrap();
        store
            .create_instance(id(2), new_key(2, "b").1, 1, 4)
            .await
            .unwrap();
        store
            .add_scheduled(id(1), sched("a", 300), 2)
            .await
            .unwrap();
        store
            .add_scheduled(id(1), sched("b", 100), 2)
            .await
            .unwrap();
        // The cap counts pending items of one instance only.
        assert!(matches!(
            store.add_scheduled(id(1), sched("c", 1), 2).await,
            Err(StoreError::LimitReached)
        ));
        store.add_scheduled(id(2), sched("d", 50), 2).await.unwrap();

        let ids = |v: Vec<ScheduledText>| v.into_iter().map(|s| s.id).collect::<Vec<_>>();
        assert_eq!(
            ids(store.list_scheduled(id(1), 0).await.unwrap()),
            ["b", "a"]
        );

        let due = store.due_scheduled(200, 10).await.unwrap();
        let due_ids: Vec<_> = due.iter().map(|(i, s)| (i.clone(), s.id.clone())).collect();
        assert_eq!(due_ids, [(id(2), "d".to_owned()), (id(1), "b".to_owned())]);
        assert_eq!(store.due_scheduled(200, 1).await.unwrap().len(), 1);

        let sent = ScheduledState::Sent {
            message_id: "M".into(),
        };
        assert!(
            store
                .finish_scheduled("b".into(), sent.clone())
                .await
                .unwrap()
        );
        // Only a pending item can finish or be cancelled.
        assert!(
            !store
                .finish_scheduled("b".into(), sent.clone())
                .await
                .unwrap()
        );
        assert!(!store.cancel_scheduled(id(1), "b".into()).await.unwrap());
        assert!(!store.cancel_scheduled(id(2), "a".into()).await.unwrap());
        assert!(store.cancel_scheduled(id(1), "a".into()).await.unwrap());
        assert!(!store.cancel_scheduled(id(1), "a".into()).await.unwrap());

        let left = store.list_scheduled(id(1), 0).await.unwrap();
        assert_eq!(left.len(), 1);
        assert_eq!(left[0].state, sent);
        // Finished items older than the window drop out of the list and are pruned.
        assert!(store.list_scheduled(id(1), 101).await.unwrap().is_empty());
        assert_eq!(store.prune_scheduled(101).await.unwrap(), 1);
        assert!(store.list_scheduled(id(1), 0).await.unwrap().is_empty());
        // Room again after the pending ones are gone.
        store.add_scheduled(id(1), sched("e", 1), 2).await.unwrap();
    }

    #[tokio::test]
    async fn deleting_an_instance_removes_its_scheduled_texts() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 1, 4)
            .await
            .unwrap();
        store.add_scheduled(id(1), sched("a", 1), 5).await.unwrap();
        assert!(store.delete_instance(id(1)).await.unwrap());
        assert!(store.due_scheduled(i64::MAX, 10).await.unwrap().is_empty());
    }

    #[test]
    fn newer_schema_is_refused() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.execute_batch("PRAGMA user_version = 99").unwrap();
        assert!(matches!(migrate(&mut conn), Err(StoreError::Corrupt(_))));
    }

    #[tokio::test]
    async fn database_holds_no_plaintext_key() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("gateway.db");
        let store = Store::open(&path).unwrap();
        let (key, new) = new_key(9, "phone");
        store.create_instance(id(1), new, 1, 4).await.unwrap();
        let (key2, new2) = new_key(8, "second");
        store.mint_key(id(1), new2, 2).await.unwrap();
        // Force everything (WAL included) into the main file.
        store
            .run(|c| Ok(c.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")?))
            .await
            .unwrap();
        drop(store);

        for file in std::fs::read_dir(dir.path()).unwrap() {
            let bytes = std::fs::read(file.unwrap().path()).unwrap();
            for secret in [&key, &key2] {
                let needle = secret.as_str().as_bytes();
                assert!(
                    !bytes.windows(needle.len()).any(|w| w == needle),
                    "plaintext key found on disk"
                );
                // Not even the base64 body without the prefix.
                let body = &needle[4..];
                assert!(!bytes.windows(body.len()).any(|w| w == body));
            }
        }
    }

    #[tokio::test]
    async fn lookup_by_hash_and_last_used() {
        let store = Store::open_in_memory().unwrap();
        let (key, new) = new_key(1, "phone");
        store.create_instance(id(1), new, 100, 4).await.unwrap();

        let hit = store.instance_for_key(key.hash(), 555).await.unwrap();
        assert_eq!(hit, Some((id(1), KeyId::from_bytes(&[1; 8]))));
        let keys = store.list_keys(id(1)).await.unwrap();
        assert_eq!(keys.len(), 1);
        assert_eq!(keys[0].label, "phone");
        assert_eq!(keys[0].created_ms, 100);
        assert_eq!(keys[0].last_used_ms, Some(555));

        let other = ConnectKey::from_bytes(&[2; 32]);
        assert_eq!(store.instance_for_key(other.hash(), 1).await.unwrap(), None);
    }

    #[tokio::test]
    async fn revoke_blocks_lookup_and_listing() {
        let store = Store::open_in_memory().unwrap();
        let (key, new) = new_key(1, "a");
        let (key2, new2) = new_key(2, "b");
        store.create_instance(id(1), new, 1, 4).await.unwrap();
        store.mint_key(id(1), new2, 2).await.unwrap();
        assert_eq!(store.list_keys(id(1)).await.unwrap().len(), 2);

        let kid = KeyId::from_bytes(&[1; 8]);
        assert!(store.key_active(kid.clone()).await.unwrap());
        assert!(store.revoke_key(id(1), kid.to_string()).await.unwrap());
        assert!(!store.key_active(kid.clone()).await.unwrap());
        // Revoking twice, or through another instance, changes nothing.
        assert!(!store.revoke_key(id(1), kid.to_string()).await.unwrap());
        assert!(
            !store
                .revoke_key(id(2), "0202020202020202".into())
                .await
                .unwrap()
        );

        assert_eq!(store.instance_for_key(key.hash(), 3).await.unwrap(), None);
        assert!(
            store
                .instance_for_key(key2.hash(), 3)
                .await
                .unwrap()
                .is_some()
        );
        let left = store.list_keys(id(1)).await.unwrap();
        assert_eq!(left.len(), 1);
        assert_eq!(left[0].label, "b");
    }

    #[tokio::test]
    async fn instance_cap_is_enforced() {
        let store = Store::open_in_memory().unwrap();
        for n in 1..=2u8 {
            store
                .create_instance(id(n), new_key(n, "k").1, 1, 2)
                .await
                .unwrap();
        }
        let err = store
            .create_instance(id(3), new_key(3, "k").1, 1, 2)
            .await
            .unwrap_err();
        assert!(matches!(err, StoreError::LimitReached));
        assert_eq!(store.count_instances().await.unwrap(), 2);
        // A refused create leaves no stray key behind.
        let key3 = ConnectKey::from_bytes(&[3; 32]);
        assert_eq!(store.instance_for_key(key3.hash(), 1).await.unwrap(), None);

        // Deleting frees a slot.
        assert!(store.delete_instance(id(1)).await.unwrap());
        store
            .create_instance(id(3), new_key(3, "k").1, 1, 2)
            .await
            .unwrap();
        assert!(!store.delete_instance(id(9)).await.unwrap());
    }

    #[tokio::test]
    async fn mint_key_needs_an_instance_and_has_a_cap() {
        let store = Store::open_in_memory().unwrap();
        let err = store
            .mint_key(id(1), new_key(1, "x").1, 1)
            .await
            .unwrap_err();
        assert!(matches!(err, StoreError::NotFound));

        store
            .create_instance(id(1), new_key(100, "first").1, 1, 4)
            .await
            .unwrap();
        for n in 1..MAX_KEYS_PER_INSTANCE as u8 {
            store.mint_key(id(1), new_key(n, "k").1, 1).await.unwrap();
        }
        let err = store
            .mint_key(id(1), new_key(99, "k").1, 1)
            .await
            .unwrap_err();
        assert!(matches!(err, StoreError::LimitReached));
    }

    #[tokio::test]
    async fn state_is_kept_between_events() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 1, 4)
            .await
            .unwrap();
        let s = store.instance_status(id(1)).await.unwrap().unwrap();
        assert_eq!(s.state, InstanceState::Linking);
        assert_eq!(s.own_jid, None);

        store
            .set_state(
                id(1),
                InstanceState::Connected,
                Some("1@s.whatsapp.net".into()),
                Some("Ada".into()),
            )
            .await
            .unwrap();
        store
            .set_state(id(1), InstanceState::Disconnected, None, None)
            .await
            .unwrap();
        let s = store.instance_status(id(1)).await.unwrap().unwrap();
        assert_eq!(s.state, InstanceState::Disconnected);
        assert_eq!(s.own_jid.as_deref(), Some("1@s.whatsapp.net"));
        assert_eq!(s.push_name.as_deref(), Some("Ada"));
        assert_eq!(store.instance_status(id(2)).await.unwrap(), None);

        let all = store.list_instances().await.unwrap();
        assert_eq!(
            all,
            vec![StoredInstance {
                id: id(1),
                state: InstanceState::Disconnected
            }]
        );
    }

    #[tokio::test]
    async fn push_tokens_upsert_per_app() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 1, 4)
            .await
            .unwrap();
        store
            .set_push_token(id(1), AppId::Phone, "t1".into())
            .await
            .unwrap();
        store
            .set_push_token(id(1), AppId::Messages, "t2".into())
            .await
            .unwrap();
        store
            .set_push_token(id(1), AppId::Phone, "t3".into())
            .await
            .unwrap();
        let tokens = store.push_tokens(id(1)).await.unwrap();
        assert_eq!(
            tokens,
            vec![
                (AppId::Messages, "t2".to_owned()),
                (AppId::Phone, "t3".to_owned())
            ]
        );
    }

    #[tokio::test]
    async fn messages_chats_and_reactions() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 1, 4)
            .await
            .unwrap();
        let chat = "905@s.whatsapp.net";
        assert!(
            store
                .insert_message(id(1), text(chat, "a", 10, false), Some(vec![1, 2]))
                .await
                .unwrap()
        );
        assert!(
            store
                .insert_message(id(1), text(chat, "b", 20, false), None)
                .await
                .unwrap()
        );
        assert!(
            store
                .insert_message(id(1), text(chat, "c", 30, true), None)
                .await
                .unwrap()
        );
        // A duplicate neither inserts nor counts as unread again.
        assert!(
            !store
                .insert_message(id(1), text(chat, "b", 20, false), None)
                .await
                .unwrap()
        );
        assert!(
            store
                .insert_message(id(1), text("g@g.us", "g1", 5, false), None)
                .await
                .unwrap()
        );

        let page = store.messages(id(1), chat.into(), None, 2).await.unwrap();
        assert_eq!(
            page.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            ["c", "b"]
        );
        let older = store
            .messages(id(1), chat.into(), Some(20), 10)
            .await
            .unwrap();
        assert_eq!(
            older.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            ["a"]
        );
        // Other instances see nothing.
        assert!(
            store
                .messages(id(2), chat.into(), None, 10)
                .await
                .unwrap()
                .is_empty()
        );

        store
            .set_reaction(
                id(1),
                chat.into(),
                "b".into(),
                Reaction {
                    sender: Some("x@s.whatsapp.net".into()),
                    from_me: false,
                    emoji: "👍".into(),
                },
            )
            .await
            .unwrap();
        store
            .set_reaction(
                id(1),
                chat.into(),
                "b".into(),
                Reaction {
                    sender: None,
                    from_me: true,
                    emoji: "❤".into(),
                },
            )
            .await
            .unwrap();
        let page = store.messages(id(1), chat.into(), None, 10).await.unwrap();
        let b = page.iter().find(|m| m.id == "b").unwrap();
        assert_eq!(b.reactions.len(), 2);
        assert!(
            b.reactions
                .iter()
                .any(|r| r.from_me && r.emoji == "❤" && r.sender.is_none())
        );
        // An empty emoji removes the reaction.
        store
            .set_reaction(
                id(1),
                chat.into(),
                "b".into(),
                Reaction {
                    sender: None,
                    from_me: true,
                    emoji: String::new(),
                },
            )
            .await
            .unwrap();
        let page = store.messages(id(1), chat.into(), None, 10).await.unwrap();
        assert_eq!(
            page.iter().find(|m| m.id == "b").unwrap().reactions.len(),
            1
        );

        let chats = store.chats(id(1)).await.unwrap();
        assert_eq!(chats.len(), 2);
        assert_eq!(chats[0].jid, chat);
        assert_eq!(chats[0].unread, 2);
        assert!(!chats[0].is_group);
        assert_eq!(chats[0].last.as_ref().unwrap().id, "c");
        assert!(chats[1].is_group);

        store
            .upsert_chat(id(1), chat.into(), Some("Ada".into()), false, 1, 0)
            .await
            .unwrap();
        store
            .upsert_chat(id(1), chat.into(), None, false, 1, 0)
            .await
            .unwrap();
        let chats = store.chats(id(1)).await.unwrap();
        assert_eq!(chats[0].name.as_deref(), Some("Ada"));
        assert_eq!(chats[0].unread, 2);
    }

    #[tokio::test]
    async fn my_reaction_and_theirs_in_a_dm_do_not_collide() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 10, 4)
            .await
            .unwrap();
        let chat = "905@s.whatsapp.net";
        store
            .insert_message(id(1), text(chat, "m", 10, false), None)
            .await
            .unwrap();
        for (from_me, emoji) in [(false, "👍"), (true, "❤")] {
            store
                .set_reaction(
                    id(1),
                    chat.into(),
                    "m".into(),
                    Reaction {
                        sender: None,
                        from_me,
                        emoji: emoji.into(),
                    },
                )
                .await
                .unwrap();
        }
        let m = store
            .message(id(1), chat.into(), "m".into())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(m.reactions.len(), 2);
        assert!(m.reactions.iter().all(|r| r.sender.is_none()));
    }

    #[tokio::test]
    async fn media_source_rename_chat_and_push_token_delete() {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 10, 4)
            .await
            .unwrap();
        let chat = "123-1@g.us";
        let mut m = text(chat, "img", 10, false);
        m.body = MessageBody::Media {
            kind: rovsti_wa_proto::MediaKind::Image,
            caption: None,
            mime: "image/png".into(),
            size: 3,
        };
        store
            .insert_message(id(1), m.clone(), Some(vec![1, 2, 3]))
            .await
            .unwrap();
        let (found, raw) = store
            .media_source(id(1), "img".into())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(found.chat, chat);
        assert_eq!(found.body, m.body);
        assert_eq!(raw, Some(vec![1, 2, 3]));
        assert!(
            store
                .media_source(id(2), "img".into())
                .await
                .unwrap()
                .is_none()
        );

        store
            .rename_chat(id(1), chat.into(), "Family".into())
            .await
            .unwrap();
        store
            .rename_chat(id(1), "other@g.us".into(), "Nope".into())
            .await
            .unwrap();
        let chats = store.chats(id(1)).await.unwrap();
        assert_eq!(chats.len(), 1);
        assert_eq!(chats[0].name.as_deref(), Some("Family"));

        store
            .set_push_token(id(1), AppId::Phone, "t1".into())
            .await
            .unwrap();
        store
            .set_push_token(id(1), AppId::Stops, "t2".into())
            .await
            .unwrap();
        store.delete_push_token(id(1), "t1".into()).await.unwrap();
        assert_eq!(
            store.push_tokens(id(1)).await.unwrap(),
            vec![(AppId::Stops, "t2".to_owned())]
        );
    }

    #[tokio::test]
    async fn deleting_an_instance_removes_its_data() {
        let store = Store::open_in_memory().unwrap();
        let (key, new) = new_key(1, "a");
        store.create_instance(id(1), new, 1, 4).await.unwrap();
        store
            .insert_message(id(1), text("c@s.whatsapp.net", "m", 1, false), None)
            .await
            .unwrap();
        store
            .set_push_token(id(1), AppId::Stops, "t".into())
            .await
            .unwrap();
        assert!(store.delete_instance(id(1)).await.unwrap());
        assert!(store.chats(id(1)).await.unwrap().is_empty());
        assert!(store.push_tokens(id(1)).await.unwrap().is_empty());
        assert_eq!(store.instance_for_key(key.hash(), 1).await.unwrap(), None);
    }

    async fn store_with_instance() -> Store {
        let store = Store::open_in_memory().unwrap();
        store
            .create_instance(id(1), new_key(1, "a").1, 1, 4)
            .await
            .unwrap();
        store
    }

    #[tokio::test]
    async fn meta_thumbnails_and_push_names_are_stored() {
        use rovsti_wa_proto::{DeliveryStatus, MessageMeta, Quoted};
        let store = store_with_instance().await;
        let chat = "g@g.us";
        let mut m = text(chat, "m1", 10, false);
        m.sender = Some("4915@s.whatsapp.net".into());
        let meta = MessageMeta {
            quoted: Some(Quoted {
                id: "q".into(),
                sender: None,
                preview: Some("hi".into()),
            }),
            sender_name: Some(" Ada ".into()),
            ..Default::default()
        };
        let extras = MessageExtras {
            meta: meta.clone(),
            raw_proto: None,
            secret: Some(vec![7; 32]),
            thumbnail: Some(vec![0xFF, 0xD8, 1]),
        };
        assert!(
            store
                .insert_message_full(id(1), m.clone(), extras.clone())
                .await
                .unwrap()
        );
        assert!(
            !store
                .insert_message_full(id(1), m.clone(), extras)
                .await
                .unwrap()
        );

        let page = store
            .messages_rich(id(1), chat.into(), None, 10)
            .await
            .unwrap();
        assert_eq!(page.len(), 1);
        assert_eq!(page[0].message, m);
        assert_eq!(page[0].meta, meta);
        // The plain list is the same messages.
        assert_eq!(
            store.messages(id(1), chat.into(), None, 10).await.unwrap(),
            vec![m]
        );
        assert_eq!(
            store.thumbnail(id(1), "m1".into()).await.unwrap(),
            Some(vec![0xFF, 0xD8, 1])
        );
        assert_eq!(
            store
                .message_secret(id(1), chat.into(), "m1".into())
                .await
                .unwrap(),
            Some(vec![7; 32])
        );
        // The author's push name was remembered, trimmed.
        let profiles = store
            .profiles(
                id(1),
                vec!["4915@s.whatsapp.net".into(), "x@s.whatsapp.net".into()],
            )
            .await
            .unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].push_name.as_deref(), Some("Ada"));

        let chats = store.chats_rich(id(1)).await.unwrap();
        assert_eq!(chats.len(), 1);
        assert_eq!(
            chats[0].last_meta.as_ref().unwrap().sender_name.as_deref(),
            Some(" Ada ")
        );
        // Push names are not a group's name.
        assert_eq!(chats[0].meta.push_name, None);

        // Receipts move my messages only.
        let mine = text(chat, "m2", 20, true);
        store.insert_message(id(1), mine, None).await.unwrap();
        store
            .set_status(
                id(1),
                chat.into(),
                vec!["m2".into(), "m1".into()],
                DeliveryStatus::Delivered,
            )
            .await
            .unwrap();
        let page = store
            .messages_rich(id(1), chat.into(), None, 10)
            .await
            .unwrap();
        assert_eq!(page[0].meta.status, Some(DeliveryStatus::Delivered));
        assert_eq!(page[1].meta.status, None);
    }

    #[tokio::test]
    async fn group_receipts_need_every_member() {
        use rovsti_wa_proto::DeliveryStatus;
        let store = store_with_instance().await;
        let group = "g@g.us";
        let mine = text(group, "m", 1, true);
        store.insert_message(id(1), mine, None).await.unwrap();
        let theirs = text(group, "t", 2, false);
        store.insert_message(id(1), theirs, None).await.unwrap();
        let receipt = |who: &str, status| {
            store.record_group_receipt(
                id(1),
                group.into(),
                who.into(),
                vec!["m".into(), "t".into(), "unknown".into()],
                status,
                Some(2),
            )
        };
        let status_of = |page: &[rovsti_wa_proto::RichMessage], msg: &str| {
            page.iter()
                .find(|r| r.message.id == msg)
                .and_then(|r| r.meta.status)
        };

        // One of two members read it: not even delivered to all yet.
        assert!(receipt("a", DeliveryStatus::Read).await.unwrap().is_empty());
        // A repeated, older receipt does not count twice or go back.
        assert!(
            receipt("a", DeliveryStatus::Delivered)
                .await
                .unwrap()
                .is_empty()
        );
        let moved = receipt("b", DeliveryStatus::Delivered).await.unwrap();
        assert_eq!(moved.len(), 1);
        assert_eq!(moved[0].0, "m");
        assert_eq!(moved[0].1.status, Some(DeliveryStatus::Delivered));
        let moved = receipt("b", DeliveryStatus::Read).await.unwrap();
        assert_eq!(moved[0].1.status, Some(DeliveryStatus::Read));

        let page = store
            .messages_rich(id(1), group.into(), None, 10)
            .await
            .unwrap();
        // The ticks survive a reload; their message never gets any.
        assert_eq!(status_of(&page, "m"), Some(DeliveryStatus::Read));
        assert_eq!(status_of(&page, "t"), None);

        // Clearing the chat forgets the receipts with the messages.
        store.delete_chat(id(1), group.into(), true).await.unwrap();
        let again = text(group, "m", 1, true);
        store.insert_message(id(1), again, None).await.unwrap();
        assert!(receipt("b", DeliveryStatus::Read).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn edits_revokes_and_deletes() {
        let store = store_with_instance().await;
        let chat = "c@s.whatsapp.net";
        store
            .insert_message(id(1), text(chat, "m", 1, true), Some(vec![1, 2]))
            .await
            .unwrap();
        store
            .set_reaction(
                id(1),
                chat.into(),
                "m".into(),
                Reaction {
                    sender: None,
                    from_me: false,
                    emoji: "x".into(),
                },
            )
            .await
            .unwrap();
        let edit = MessageChange::Edit {
            body: MessageBody::Text("new".into()),
            edited_ms: 5,
        };
        let (body, meta) = store
            .change_message(id(1), chat.into(), "m".into(), edit.clone())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(body, Some(MessageBody::Text("new".into())));
        assert_eq!(meta.edited_ms, Some(5));
        assert_eq!(
            store
                .change_message(id(1), chat.into(), "m".into(), edit)
                .await
                .unwrap(),
            None
        );
        assert_eq!(
            store
                .change_message(id(1), chat.into(), "nope".into(), MessageChange::Revoke)
                .await
                .unwrap(),
            None
        );
        let (body, meta) = store
            .change_message(id(1), chat.into(), "m".into(), MessageChange::Revoke)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(body, Some(MessageBody::Unsupported));
        assert!(meta.revoked);
        let stored = store
            .message(id(1), chat.into(), "m".into())
            .await
            .unwrap()
            .unwrap();
        assert!(stored.reactions.is_empty(), "reactions go with the message");
        let (_, raw) = store
            .media_source(id(1), "m".into())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(raw, None, "the media of a deleted message is not kept");

        assert!(
            store
                .delete_message(id(1), chat.into(), "m".into())
                .await
                .unwrap()
        );
        assert!(
            !store
                .delete_message(id(1), chat.into(), "m".into())
                .await
                .unwrap()
        );

        store
            .insert_message(id(1), text(chat, "n", 2, false), None)
            .await
            .unwrap();
        assert!(store.delete_chat(id(1), chat.into(), true).await.unwrap());
        let chats = store.chats(id(1)).await.unwrap();
        assert_eq!(chats.len(), 1, "a cleared chat stays");
        assert_eq!((chats[0].unread, chats[0].last.clone()), (0, None));
        assert!(store.delete_chat(id(1), chat.into(), false).await.unwrap());
        assert!(store.chats(id(1)).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn chat_settings_and_profiles() {
        use rovsti_wa_proto::MUTED_FOREVER;
        let store = store_with_instance().await;
        let chat = "c@s.whatsapp.net";
        store
            .insert_message(id(1), text(chat, "m", 1, false), None)
            .await
            .unwrap();
        let meta = store
            .change_chat(id(1), chat.into(), ChatChange::Pinned(true))
            .await
            .unwrap()
            .unwrap();
        assert!(meta.pinned);
        assert_eq!(
            store
                .change_chat(id(1), chat.into(), ChatChange::Pinned(true))
                .await
                .unwrap(),
            None
        );
        store
            .change_chat(id(1), chat.into(), ChatChange::Muted(Some(MUTED_FOREVER)))
            .await
            .unwrap();
        store
            .change_chat(id(1), chat.into(), ChatChange::MarkedUnread(false))
            .await
            .unwrap();
        let rich = store.chats_rich(id(1)).await.unwrap();
        assert_eq!(rich[0].chat.unread, 0, "marking read clears the counter");
        assert!(rich[0].meta.pinned && rich[0].meta.is_muted(0));

        // Profiles: a picture change is visible, a refetch of the same is not.
        let changed = store
            .change_profile(
                id(1),
                chat.into(),
                ProfileChange::Picture(Some("p1".into())),
                5,
            )
            .await
            .unwrap()
            .unwrap();
        assert_eq!(changed.picture_id.as_deref(), Some("p1"));
        assert_eq!(
            store.picture_state(id(1), chat.into()).await.unwrap(),
            (Some("p1".into()), 5)
        );
        let fetched = FetchedProfile {
            jid: chat.into(),
            about: Some("hey".into()),
            picture_id: Some("p1".into()),
            business: false,
            verified_name: None,
        };
        let after = store
            .change_profile(
                id(1),
                chat.into(),
                ProfileChange::Fetched(fetched.clone()),
                9,
            )
            .await
            .unwrap()
            .unwrap();
        assert_eq!((after.about.as_deref(), after.fetched_ms), (Some("hey"), 9));
        assert_eq!(
            store
                .change_profile(id(1), chat.into(), ProfileChange::Fetched(fetched), 10)
                .await
                .unwrap(),
            None
        );
        store
            .change_profile(id(1), chat.into(), ProfileChange::Stale, 11)
            .await
            .unwrap();
        assert_eq!(store.picture_state(id(1), chat.into()).await.unwrap().1, 0);
        store
            .set_push_names(
                id(1),
                vec![(chat.into(), "Bob".into()), (chat.into(), " ".into())],
            )
            .await
            .unwrap();
        let rich = store.chats_rich(id(1)).await.unwrap();
        assert_eq!(rich[0].meta.picture_id.as_deref(), Some("p1"));
        assert_eq!(rich[0].meta.push_name.as_deref(), Some("Bob"));
        // The plain chat list did not change shape.
        assert_eq!(store.chats(id(1)).await.unwrap()[0], rich[0].chat);
    }

    #[test]
    fn hash_bytes_round_trip() {
        let hash = ConnectKey::from_bytes(&[5; 32]).hash();
        let raw = hash_bytes(&hash);
        assert_eq!(raw.len(), 32);
        assert_eq!(hash_from_bytes(&raw), Some(hash));
        assert_eq!(hash_from_bytes(&raw[..31]), None);
    }

    #[test]
    fn instance_id_is_hex() {
        assert_eq!(id(0xab).as_str(), "abababababababababababab");
        assert!(parse_key_id("0102030405060708").is_some());
    }
}
