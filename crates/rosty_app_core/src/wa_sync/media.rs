//! Media of a message. The thumbnail is stored inline and stays with the message. The full file is a row of
//! `media`: where it is in its download, the cached path and the bookkeeping of the cap.
//!
//! The store decides, the caller does the I/O: it downloads what [`Store::media_due`] hands out and reports with
//! [`Store::media_settle`], deletes the files [`Store::media_evict`] and [`Store::media_reconcile`] name, and
//! passes the network it is on so the auto-download rules ([`policy`]) are applied here.
//!
//! * States: `none` (nothing asked), `queued`, `downloading`, `ready` (the file is cached), `evicted` (the cap
//!   took the file; the thumbnail stays and the file can be queued again), `failed` (attempts ran out; a tap
//!   starts over) and `gone` (the gateway no longer has it).
//! * The cap ([`Store::media_cap`], 512 MiB unless set, or no limit) counts `ready` files. Eviction is least
//!   recently used first and never takes an exempt file: a starred message, a pinned or saved file, a file an
//!   unsent outbox row sends, a file used in the last 24 hours. When the exempt files alone are over the cap the
//!   others go and the exempt ones stay.
//! * The chat of a message may be named by any of its ids ([`Store::stored_key`]): media that was being fetched
//!   while a merge moved the chat still lands on its message. Rows follow a merge ([`follow_merge`]).

use std::collections::{HashMap, HashSet};

use rosty_wa_proto::MediaMeta;
use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};

pub use super::media_policy::{
    Conditions, Decision, FREE_SPACE_FLOOR, MediaClass, Network, policy,
};
use super::model::Kind;
use super::store::Store;
use super::{Error, Result, outbox};

/// The cap of a store that was never told one.
pub const DEFAULT_CAP_BYTES: u64 = 512 * 1024 * 1024;
/// A file used within this long is not evicted.
pub const RECENT_USE_MS: i64 = 24 * 60 * 60 * 1000;
/// Failed downloads of one file before it is `failed` and waits for a tap.
pub const MAX_ATTEMPTS: u32 = 5;
/// A download handed out this long ago without an answer is handed out again.
pub const DOWNLOAD_LEASE_MS: i64 = 10 * 60 * 1000;
/// Downloads handed out at once when the caller names no limit.
pub const DEFAULT_DUE: u32 = 4;
/// The most downloads handed out at once.
pub const MAX_DUE: u32 = 32;
/// `sync_state.media_cap_bytes` of a store without a cap.
const NO_LIMIT: i64 = -1;
/// What a download that never finished is written to.
const PART_SUFFIX: &str = ".part";

/// Where the full file of a message is.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaState {
    /// Nothing was asked for.
    #[default]
    None,
    Queued,
    Downloading,
    /// The file is cached.
    Ready,
    /// The cap took the file; it can be queued again.
    Evicted,
    /// Attempts ran out; a tap starts over.
    Failed,
    /// The gateway no longer has the file.
    Gone,
}

impl MediaState {
    const ALL: [MediaState; 7] = [
        MediaState::None,
        MediaState::Queued,
        MediaState::Downloading,
        MediaState::Ready,
        MediaState::Evicted,
        MediaState::Failed,
        MediaState::Gone,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            MediaState::None => "none",
            MediaState::Queued => "queued",
            MediaState::Downloading => "downloading",
            MediaState::Ready => "ready",
            MediaState::Evicted => "evicted",
            MediaState::Failed => "failed",
            MediaState::Gone => "gone",
        }
    }

    /// An unknown name (a row of a newer build) reads as [`MediaState::None`].
    pub fn from_name(name: &str) -> MediaState {
        Self::ALL
            .into_iter()
            .find(|state| state.as_str() == name)
            .unwrap_or_default()
    }

    /// Which of two rows of one message a merge keeps: the one that holds, or is nearer to, the file.
    fn weight(self) -> u8 {
        match self {
            MediaState::Ready => 6,
            MediaState::Downloading => 5,
            MediaState::Queued => 4,
            MediaState::Failed => 3,
            MediaState::Gone => 2,
            MediaState::Evicted => 1,
            MediaState::None => 0,
        }
    }
}

/// The media row of one message.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct MediaEntry {
    pub chat: String,
    pub id: String,
    pub kind: Kind,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mime: Option<String>,
    /// The size the gateway named.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size_expected: Option<u64>,
    pub state: MediaState,
    /// The cached file; only while `ready`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bytes: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub used_ms: Option<i64>,
    #[serde(skip_serializing_if = "is_false")]
    pub pinned: bool,
    /// Where the user saved a copy.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub saved_uri: Option<String>,
    /// Asked for by a tap, not by the auto-download rules.
    #[serde(skip_serializing_if = "is_false")]
    pub by_tap: bool,
    /// Downloads that failed since the file was last asked for.
    #[serde(skip_serializing_if = "is_zero")]
    pub attempts: u32,
    /// When a queued download may be tried again; left out when it may go now.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_attempt_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_error: Option<String>,
}

fn is_false(value: &bool) -> bool {
    !*value
}

fn is_zero(value: &u32) -> bool {
    *value == 0
}

/// The columns of [`MediaEntry`], of `media AS m`.
const ENTRY_COLUMNS: &str = "m.chat, m.id, m.kind, m.mime, m.size_expected, m.state, m.path, m.bytes, m.sha256, \
     m.used_ms, m.pinned, m.saved_uri, m.by_tap, m.attempts, m.next_attempt_ms, m.last_error";
const ENTRY_COLUMN_COUNT: usize = 16;

fn read_entry(row: &Row<'_>) -> rusqlite::Result<MediaEntry> {
    let kind: String = row.get(2)?;
    let state: String = row.get(5)?;
    let next_attempt_ms: i64 = row.get(14)?;
    Ok(MediaEntry {
        chat: row.get(0)?,
        id: row.get(1)?,
        kind: Kind::from_name(&kind),
        mime: row.get(3)?,
        size_expected: row.get(4)?,
        state: MediaState::from_name(&state),
        path: row.get(6)?,
        bytes: row.get(7)?,
        sha256: row.get(8)?,
        used_ms: row.get(9)?,
        pinned: row.get(10)?,
        saved_uri: row.get(11)?,
        by_tap: row.get(12)?,
        attempts: row.get(13)?,
        next_attempt_ms: (next_attempt_ms > 0).then_some(next_attempt_ms),
        last_error: row.get(15)?,
    })
}

/// What the store holds for the media of one message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MediaInfo {
    pub thumbnail: Option<Vec<u8>>,
    /// The full file: its state and, while `ready`, its path.
    pub media: MediaEntry,
}

/// A file the cache no longer counts: delete it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Evicted {
    pub chat: String,
    pub id: String,
    pub path: String,
    pub bytes: u64,
}

/// What [`Store::media_evict`] did.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Eviction {
    /// Least recently used first.
    pub evicted: Vec<Evicted>,
    /// What the cache still counts.
    pub bytes_left: u64,
    /// The part of `bytes_left` that is never evicted; more than the cap when the exempt files alone are over it.
    pub exempt_bytes: u64,
}

/// How a download was asked for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Fetch {
    /// The user tapped the media: the rules do not hold it back.
    Tap,
    /// The message arrived or came into view: queue it only when the rules say so under these conditions.
    Auto(Conditions),
}

/// What [`Store::media_enqueue`] did.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct MediaQueued {
    /// The file waits in the queue or is being downloaded.
    pub queued: bool,
    /// What the rules say about this file ([`Decision::OnTap`] for a tap the caller gave no conditions for).
    pub decision: Decision,
    pub media: MediaEntry,
}

/// What [`Store::media_due`] hands out.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct MediaDue {
    /// Download these now (taps first, then in the order queued); each is `downloading` until settled.
    pub items: Vec<MediaEntry>,
    /// Auto downloads that wait for a better network or more free space.
    pub held: u32,
    /// When a download that waits for its next attempt comes due.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_wake_ms: Option<i64>,
}

/// How a download ended.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Fetched {
    /// The file is in place (written to a `.part` file, synced and renamed by the caller).
    Ready {
        path: String,
        bytes: u64,
        /// Hex, when the caller hashed the file.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sha256: Option<String>,
    },
    /// The attempt failed and may work later (no network, a timeout, a gateway error).
    Failed {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    /// The gateway no longer has the file.
    Gone {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    /// The user stopped the download: nothing is asked for any more.
    Cancelled,
}

/// What [`Store::media_settle`] did.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct MediaSettled {
    pub media: MediaEntry,
    /// The file the row named before, when it is another one than the new: delete it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub replaced: Option<String>,
}

/// A `ready` row whose file was not there.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Missing {
    pub chat: String,
    pub id: String,
    pub path: String,
}

/// What [`Store::media_reconcile`] found.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Reconciled {
    /// Downloads that were cut off and are queued again.
    pub requeued: u32,
    /// Rows that were `ready` without their file: now `evicted`.
    pub missing: Vec<Missing>,
    /// Files to delete: unfinished downloads and files no row names.
    pub delete: Vec<String>,
}

/// Files and bytes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Usage {
    pub files: u32,
    pub bytes: u64,
}

impl Usage {
    fn add(&mut self, bytes: u64) {
        self.files += 1;
        self.bytes = self.bytes.saturating_add(bytes);
    }
}

/// The cached files of one kind.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct KindUsage {
    pub kind: Kind,
    #[serde(flatten)]
    pub usage: Usage,
}

/// The media cache in numbers, for a settings screen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct MediaStats {
    /// Every cached file.
    pub total: Usage,
    /// The cached files the cap never takes.
    pub exempt: Usage,
    /// Largest first; kinds without a cached file are left out.
    pub by_kind: Vec<KindUsage>,
    /// Files unsent outbox rows send that are not counted above.
    pub outbox: Usage,
    pub queued: u32,
    pub downloading: u32,
    pub failed: u32,
    /// The cap; left out when there is no limit.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cap_bytes: Option<u64>,
    /// The exempt files alone are over the cap: eviction cannot bring the cache under it.
    pub exempt_over_cap: bool,
}

/// What a media row needs to know about its message.
struct Facts {
    kind: Kind,
    mime: Option<String>,
    size: Option<u64>,
    meta: Option<MediaMeta>,
    deleted: bool,
}

impl Facts {
    fn class(&self) -> MediaClass {
        MediaClass::of(self.kind, self.meta.as_ref())
    }
}

fn not_found(chat: &str, id: &str) -> Error {
    Error::NotFound(format!("message {id} of {chat}"))
}

fn facts(conn: &Connection, chat: &str, id: &str) -> Result<Facts> {
    conn.query_row(
        "SELECT kind, mime, size, media_json, deleted FROM messages WHERE chat = ?1 AND id = ?2",
        params![chat, id],
        |row| {
            let kind: String = row.get(0)?;
            let meta: Option<String> = row.get(3)?;
            Ok(Facts {
                kind: Kind::from_name(&kind),
                mime: row.get(1)?,
                size: row.get(2)?,
                meta: meta.and_then(|text| serde_json::from_str(&text).ok()),
                deleted: row.get(4)?,
            })
        },
    )
    .optional()?
    .ok_or_else(|| not_found(chat, id))
}

/// Makes sure the message has a media row.
fn ensure_row(conn: &Connection, chat: &str, id: &str, facts: &Facts) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO media (chat, id, kind, mime, size_expected) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![chat, id, facts.kind.as_str(), facts.mime, facts.size],
    )?;
    Ok(())
}

fn stored_entry(conn: &Connection, chat: &str, id: &str) -> Result<Option<MediaEntry>> {
    Ok(conn
        .query_row(
            &format!("SELECT {ENTRY_COLUMNS} FROM media AS m WHERE m.chat = ?1 AND m.id = ?2"),
            params![chat, id],
            read_entry,
        )
        .optional()?)
}

/// The media row of a message; one that was never asked for reads as `none`.
fn entry(conn: &Connection, chat: &str, id: &str) -> Result<MediaEntry> {
    if let Some(entry) = stored_entry(conn, chat, id)? {
        return Ok(entry);
    }
    let facts = facts(conn, chat, id)?;
    Ok(MediaEntry {
        chat: chat.to_owned(),
        id: id.to_owned(),
        kind: facts.kind,
        mime: facts.mime,
        size_expected: facts.size,
        state: MediaState::None,
        path: None,
        bytes: None,
        sha256: None,
        used_ms: None,
        pinned: false,
        saved_uri: None,
        by_tap: false,
        attempts: 0,
        next_attempt_ms: None,
        last_error: None,
    })
}

/// Records the cached file of a message. The answer is the file the row named before, when it is another one.
fn set_ready(
    conn: &Connection,
    chat: &str,
    id: &str,
    file: (&str, u64, Option<&str>),
    now_ms: i64,
) -> Result<Option<String>> {
    let (path, bytes, sha256) = file;
    ensure_row(conn, chat, id, &facts(conn, chat, id)?)?;
    let before: Option<String> = conn.query_row(
        "SELECT path FROM media WHERE chat = ?1 AND id = ?2",
        params![chat, id],
        |row| row.get(0),
    )?;
    conn.execute(
        "UPDATE media SET state = 'ready', path = ?3, bytes = ?4, sha256 = ?5, used_ms = ?6, attempts = 0,
            next_attempt_ms = 0, attempted_ms = NULL, last_error = NULL
         WHERE chat = ?1 AND id = ?2",
        params![chat, id, path, bytes, sha256, now_ms],
    )?;
    Ok(before.filter(|old| old != path))
}

/// The file an outbox row sent is the cached file of the message it became, unless one is cached already.
pub(super) fn adopt_sent_file(
    conn: &Connection,
    chat: &str,
    id: &str,
    file: &str,
    bytes: u64,
    now_ms: i64,
) -> Result<()> {
    conn.execute(
        "INSERT INTO media (chat, id, kind, mime, size_expected, state, path, bytes, used_ms)
         SELECT chat, id, kind, mime, size, 'ready', ?3, ?4, ?5 FROM messages WHERE chat = ?1 AND id = ?2
         ON CONFLICT (chat, id) DO UPDATE SET state = 'ready', path = excluded.path, bytes = excluded.bytes,
            used_ms = excluded.used_ms, attempts = 0, next_attempt_ms = 0, attempted_ms = NULL, last_error = NULL
         WHERE media.state != 'ready'",
        params![chat, id, file, bytes, now_ms],
    )?;
    Ok(())
}

/// The cap as stored: `None` is no limit.
fn cap(conn: &Connection) -> Result<Option<u64>> {
    let stored: i64 = conn.query_row(
        "SELECT media_cap_bytes FROM sync_state WHERE id = 1",
        [],
        |row| row.get(0),
    )?;
    Ok(u64::try_from(stored).ok())
}

/// One cached file, with whether the cap may take it.
struct Cached {
    chat: String,
    id: String,
    kind: Kind,
    path: String,
    bytes: u64,
    exempt: bool,
}

/// Every cached file, least recently used first. `now_ms` is needed to tell a file used in the last 24 hours;
/// without it no file is exempt for that reason.
fn cached(conn: &Connection, now_ms: Option<i64>) -> Result<Vec<Cached>> {
    let sending: HashSet<String> = outbox::media_files(conn)?
        .into_iter()
        .map(|(file, _)| file)
        .collect();
    let recent_from = now_ms.map(|now| now.saturating_sub(RECENT_USE_MS));
    let mut stmt = conn.prepare(
        "SELECT m.chat, m.id, m.kind, m.path, COALESCE(m.bytes, 0), COALESCE(m.used_ms, 0),
            m.pinned != 0 OR m.saved_uri IS NOT NULL OR EXISTS (
                SELECT 1 FROM messages AS s WHERE s.chat = m.chat AND s.id = m.id AND s.starred = 1)
         FROM media AS m WHERE m.state = 'ready' AND m.path IS NOT NULL
         ORDER BY COALESCE(m.used_ms, 0), m.chat, m.id",
    )?;
    let rows = stmt.query_map([], |row| {
        let kind: String = row.get(2)?;
        let path: String = row.get(3)?;
        let used_ms: i64 = row.get(5)?;
        let kept: bool = row.get(6)?;
        let exempt =
            kept || sending.contains(&path) || recent_from.is_some_and(|from| used_ms > from);
        Ok(Cached {
            chat: row.get(0)?,
            id: row.get(1)?,
            kind: Kind::from_name(&kind),
            path,
            bytes: row.get(4)?,
            exempt,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<_>>()?)
}

/// The files the store names: every cached file and, with `outbox`, every file an unsent outbox row sends.
pub(super) fn known_files(conn: &Connection, outbox: bool) -> Result<Vec<String>> {
    let mut files: Vec<String> = conn
        .prepare("SELECT path FROM media WHERE path IS NOT NULL ORDER BY chat, id")?
        .query_map([], |row| row.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    if outbox {
        for (file, _) in outbox::media_files(conn)? {
            if !files.contains(&file) {
                files.push(file);
            }
        }
    }
    Ok(files)
}

/// Media rows follow a key change: those of chat `from` move to chat `into`. A message both chats hold keeps one
/// row, the one that holds the file (`ready`), else the one nearer to it; a pin and a saved copy of either stay.
/// When both rows hold a file the one of `from` is forgotten and [`Store::media_reconcile`] lists it as a file
/// no row names.
pub(super) fn follow_merge(conn: &Connection, from: &str, into: &str) -> Result<bool> {
    let both: Vec<String> = conn
        .prepare(
            "SELECT f.id FROM media AS f JOIN media AS i ON i.chat = ?2 AND i.id = f.id WHERE f.chat = ?1",
        )?
        .query_map(params![from, into], |row| row.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    for id in &both {
        let (Some(old), Some(kept)) =
            (stored_entry(conn, from, id)?, stored_entry(conn, into, id)?)
        else {
            continue;
        };
        let pinned = old.pinned || kept.pinned;
        let saved_uri = kept.saved_uri.clone().or(old.saved_uri.clone());
        if old.state.weight() > kept.state.weight() {
            conn.execute(
                "DELETE FROM media WHERE chat = ?1 AND id = ?2",
                params![into, id],
            )?;
            conn.execute(
                "UPDATE media SET chat = ?2 WHERE chat = ?1 AND id = ?3",
                params![from, into, id],
            )?;
        } else {
            conn.execute(
                "DELETE FROM media WHERE chat = ?1 AND id = ?2",
                params![from, id],
            )?;
        }
        conn.execute(
            "UPDATE media SET pinned = ?3, saved_uri = ?4 WHERE chat = ?1 AND id = ?2",
            params![into, id, pinned, saved_uri],
        )?;
    }
    let moved = conn.execute(
        "UPDATE media SET chat = ?2 WHERE chat = ?1",
        params![from, into],
    )?;
    Ok(moved > 0 || !both.is_empty())
}

impl Store {
    /// Stores the thumbnail of a message (kept for good, also after the full file is evicted).
    pub fn set_thumbnail(&mut self, chat: &str, id: &str, thumbnail: &[u8]) -> Result<()> {
        let chat = &self.stored_key(chat);
        let changed = self.conn.execute(
            "UPDATE messages SET thumb = ?3 WHERE chat = ?1 AND id = ?2",
            params![chat, id, thumbnail],
        )?;
        if changed == 0 {
            return Err(not_found(chat, id));
        }
        Ok(())
    }

    /// Records the downloaded full file of a message and counts it as just used: [`Self::media_settle`] with
    /// [`Fetched::Ready`], for a caller that downloaded without the queue.
    pub fn set_media_file(
        &mut self,
        chat: &str,
        id: &str,
        path: &str,
        bytes: u64,
        now_ms: i64,
    ) -> Result<()> {
        let outcome = Fetched::Ready {
            path: path.to_owned(),
            bytes,
            sha256: None,
        };
        self.media_settle(chat, id, &outcome, now_ms).map(|_| ())
    }

    /// The thumbnail and the media row of a message; `None` when the store has no such message. `used_ms` (the
    /// media was opened) moves a cached file to the fresh end of the LRU order.
    pub fn media(
        &mut self,
        chat: &str,
        id: &str,
        used_ms: Option<i64>,
    ) -> Result<Option<MediaInfo>> {
        let chat = &self.stored_key(chat);
        if let Some(now_ms) = used_ms {
            self.conn.execute(
                "UPDATE media SET used_ms = ?3 WHERE chat = ?1 AND id = ?2 AND state = 'ready'",
                params![chat, id, now_ms],
            )?;
        }
        let thumbnail: Option<Option<Vec<u8>>> = self
            .conn
            .query_row(
                "SELECT thumb FROM messages WHERE chat = ?1 AND id = ?2",
                params![chat, id],
                |row| row.get(0),
            )
            .optional()?;
        let Some(thumbnail) = thumbnail else {
            return Ok(None);
        };
        Ok(Some(MediaInfo {
            thumbnail,
            media: entry(&self.conn, chat, id)?,
        }))
    }

    /// Bytes of full media the cache counts.
    pub fn media_bytes(&self) -> Result<u64> {
        Ok(self.conn.query_row(
            "SELECT COALESCE(SUM(bytes), 0) FROM media WHERE state = 'ready'",
            [],
            |row| row.get(0),
        )?)
    }

    /// The cap of the media cache in bytes; `None` is no limit.
    pub fn media_cap(&self) -> Result<Option<u64>> {
        cap(&self.conn)
    }

    /// Sets the cap (`None`: no limit). Nothing is evicted here: call [`Self::media_evict`].
    pub fn set_media_cap(&mut self, cap_bytes: Option<u64>) -> Result<()> {
        let stored = match cap_bytes {
            Some(bytes) => i64::try_from(bytes)
                .map_err(|_| Error::Invalid(format!("a cap of {bytes} bytes is too large")))?,
            None => NO_LIMIT,
        };
        self.conn.execute(
            "UPDATE sync_state SET media_cap_bytes = ?1 WHERE id = 1",
            [stored],
        )?;
        Ok(())
    }

    /// What the rules say about the file of a message under `conditions`.
    pub fn media_policy(&self, chat: &str, id: &str, conditions: &Conditions) -> Result<Decision> {
        let facts = facts(&self.conn, &self.stored_key(chat), id)?;
        Ok(policy(facts.class(), facts.size, conditions))
    }

    /// Asks for the full file of a message. A tap queues it whatever the network (unless the rules say never);
    /// an automatic request queues it only when the rules say [`Decision::Auto`], and never for a deleted
    /// message or a file that already failed for good. A cached file stays as it is.
    pub fn media_enqueue(
        &mut self,
        chat: &str,
        id: &str,
        fetch: Fetch,
        now_ms: i64,
    ) -> Result<MediaQueued> {
        let chat = &self.stored_key(chat);
        let tx = self.conn.transaction()?;
        let facts = facts(&tx, chat, id)?;
        let (tap, decision) = match fetch {
            Fetch::Tap => (
                true,
                match facts.class() {
                    MediaClass::ViewOnce | MediaClass::NotMedia => Decision::Never,
                    _ => Decision::OnTap,
                },
            ),
            Fetch::Auto(conditions) => (false, policy(facts.class(), facts.size, &conditions)),
        };
        let state = stored_entry(&tx, chat, id)?.map_or(MediaState::None, |entry| entry.state);
        let wanted = match decision {
            Decision::Never => false,
            Decision::OnTap => tap,
            Decision::Auto => !facts.deleted,
        };
        let fresh = match state {
            MediaState::None | MediaState::Evicted => true,
            // Only a tap starts a file over that failed for good.
            MediaState::Failed | MediaState::Gone => tap,
            MediaState::Queued | MediaState::Downloading | MediaState::Ready => false,
        };
        if wanted && fresh {
            ensure_row(&tx, chat, id, &facts)?;
            tx.execute(
                "UPDATE media SET state = 'queued', by_tap = ?3, queued_ms = ?4, attempts = 0, next_attempt_ms = 0,
                    attempted_ms = NULL, last_error = NULL, path = NULL, bytes = NULL, sha256 = NULL
                 WHERE chat = ?1 AND id = ?2",
                params![chat, id, tap, now_ms],
            )?;
        } else if wanted && tap && state == MediaState::Queued {
            // A tap on a file that waits for its turn or its next attempt: it goes first, now.
            tx.execute(
                "UPDATE media SET by_tap = 1, next_attempt_ms = 0 WHERE chat = ?1 AND id = ?2",
                params![chat, id],
            )?;
        }
        let media = entry(&tx, chat, id)?;
        tx.commit()?;
        Ok(MediaQueued {
            queued: matches!(media.state, MediaState::Queued | MediaState::Downloading),
            decision,
            media,
        })
    }

    /// The downloads to make now, at most `limit` ([`DEFAULT_DUE`] when left out): taps first, then in the order
    /// queued. Each is `downloading` until [`Self::media_settle`] reports it; one handed out
    /// [`DOWNLOAD_LEASE_MS`] ago without an answer is handed out again. With `conditions`, an automatic download
    /// the rules no longer allow (the device left Wi-Fi, the space ran low) stays queued and is counted in
    /// `held`.
    pub fn media_due(
        &mut self,
        now_ms: i64,
        limit: Option<u32>,
        conditions: Option<&Conditions>,
    ) -> Result<MediaDue> {
        let limit = limit.unwrap_or(DEFAULT_DUE).clamp(1, MAX_DUE) as usize;
        let tx = self.conn.transaction()?;
        let lease_from = now_ms.saturating_sub(DOWNLOAD_LEASE_MS);
        let waiting: Vec<(MediaEntry, Option<String>)> = tx
            .prepare(&format!(
                "SELECT {ENTRY_COLUMNS}, s.media_json FROM media AS m
                 LEFT JOIN messages AS s ON s.chat = m.chat AND s.id = m.id
                 WHERE (m.state = 'queued' AND m.next_attempt_ms <= ?1)
                    OR (m.state = 'downloading' AND COALESCE(m.attempted_ms, 0) <= ?2)
                 ORDER BY m.by_tap DESC, COALESCE(m.queued_ms, 0), m.chat, m.id"
            ))?
            .query_map(params![now_ms, lease_from], |row| {
                Ok((read_entry(row)?, row.get(ENTRY_COLUMN_COUNT)?))
            })?
            .collect::<rusqlite::Result<_>>()?;
        let mut items = Vec::new();
        let mut held = 0;
        for (mut entry, meta) in waiting {
            let allowed = entry.by_tap
                || conditions.is_none_or(|conditions| {
                    let meta: Option<MediaMeta> =
                        meta.and_then(|text| serde_json::from_str(&text).ok());
                    let class = MediaClass::of(entry.kind, meta.as_ref());
                    policy(class, entry.size_expected, conditions) == Decision::Auto
                });
            if !allowed {
                held += 1;
                continue;
            }
            if items.len() == limit {
                continue;
            }
            tx.execute(
                "UPDATE media SET state = 'downloading', attempted_ms = ?3 WHERE chat = ?1 AND id = ?2",
                params![entry.chat, entry.id, now_ms],
            )?;
            entry.state = MediaState::Downloading;
            items.push(entry);
        }
        let next_wake_ms: Option<i64> = tx.query_row(
            "SELECT MIN(wake) FROM (
                SELECT next_attempt_ms AS wake FROM media WHERE state = 'queued' AND next_attempt_ms > ?1
                UNION ALL
                SELECT COALESCE(attempted_ms, 0) + ?2 FROM media WHERE state = 'downloading')",
            params![now_ms, DOWNLOAD_LEASE_MS],
            |row| row.get(0),
        )?;
        tx.commit()?;
        Ok(MediaDue {
            items,
            held,
            next_wake_ms,
        })
    }

    /// Reports how a download ended. A failure queues the file again after a back-off
    /// ([`outbox::backoff_ms`]) until [`MAX_ATTEMPTS`], then it is `failed` and waits for a tap. A file that is
    /// in place is counted as just used; bring the cache under the cap afterwards ([`Self::media_evict`]).
    pub fn media_settle(
        &mut self,
        chat: &str,
        id: &str,
        outcome: &Fetched,
        now_ms: i64,
    ) -> Result<MediaSettled> {
        let chat = &self.stored_key(chat);
        let tx = self.conn.transaction()?;
        let mut replaced = None;
        match outcome {
            Fetched::Ready {
                path,
                bytes,
                sha256,
            } => {
                replaced = set_ready(&tx, chat, id, (path, *bytes, sha256.as_deref()), now_ms)?;
            }
            Fetched::Failed { error } => {
                ensure_row(&tx, chat, id, &facts(&tx, chat, id)?)?;
                let attempts: u32 = tx.query_row(
                    "SELECT attempts FROM media WHERE chat = ?1 AND id = ?2",
                    params![chat, id],
                    |row| row.get(0),
                )?;
                let attempts = attempts.saturating_add(1);
                let (state, next) = if attempts >= MAX_ATTEMPTS {
                    (MediaState::Failed, 0)
                } else {
                    (
                        MediaState::Queued,
                        now_ms.saturating_add(outbox::backoff_ms(attempts)),
                    )
                };
                tx.execute(
                    "UPDATE media SET state = ?3, attempts = ?4, next_attempt_ms = ?5, attempted_ms = NULL,
                        last_error = ?6
                     WHERE chat = ?1 AND id = ?2 AND state != 'ready'",
                    params![chat, id, state.as_str(), attempts, next, error],
                )?;
            }
            Fetched::Gone { error } => {
                ensure_row(&tx, chat, id, &facts(&tx, chat, id)?)?;
                tx.execute(
                    "UPDATE media SET state = 'gone', next_attempt_ms = 0, attempted_ms = NULL, last_error = ?3
                     WHERE chat = ?1 AND id = ?2 AND state != 'ready'",
                    params![chat, id, error],
                )?;
            }
            Fetched::Cancelled => {
                facts(&tx, chat, id)?;
                tx.execute(
                    "UPDATE media SET state = 'none', by_tap = 0, attempts = 0, next_attempt_ms = 0,
                        attempted_ms = NULL, last_error = NULL
                     WHERE chat = ?1 AND id = ?2 AND state IN ('queued', 'downloading', 'failed')",
                    params![chat, id],
                )?;
            }
        }
        let media = entry(&tx, chat, id)?;
        tx.commit()?;
        Ok(MediaSettled { media, replaced })
    }

    /// Keeps the file of a message out of eviction: `pinned`, and `saved_uri` when the user saved a copy
    /// (`Some("")` forgets the copy). `None` leaves a value as it is.
    pub fn media_keep(
        &mut self,
        chat: &str,
        id: &str,
        pinned: Option<bool>,
        saved_uri: Option<&str>,
    ) -> Result<MediaEntry> {
        let chat = &self.stored_key(chat);
        let tx = self.conn.transaction()?;
        ensure_row(&tx, chat, id, &facts(&tx, chat, id)?)?;
        if let Some(pinned) = pinned {
            tx.execute(
                "UPDATE media SET pinned = ?3 WHERE chat = ?1 AND id = ?2",
                params![chat, id, pinned],
            )?;
        }
        if let Some(uri) = saved_uri {
            tx.execute(
                "UPDATE media SET saved_uri = ?3 WHERE chat = ?1 AND id = ?2",
                params![chat, id, (!uri.is_empty()).then_some(uri)],
            )?;
        }
        let media = entry(&tx, chat, id)?;
        tx.commit()?;
        Ok(media)
    }

    /// Brings the cache under `max_bytes`, or under the stored cap ([`Self::media_cap`]) when left out: the
    /// least recently used files that are not exempt become `evicted` and the caller deletes them. The messages
    /// and their thumbnails stay, so they read the same and the media can be queued again. Exempt files are
    /// never taken, also when they alone are over the cap. `now_ms` tells which files were used in the last 24
    /// hours; without it none is exempt for that reason.
    pub fn media_evict(&mut self, max_bytes: Option<u64>, now_ms: Option<i64>) -> Result<Eviction> {
        let tx = self.conn.transaction()?;
        let limit = match max_bytes {
            Some(bytes) => Some(bytes),
            None => cap(&tx)?,
        };
        let files = cached(&tx, now_ms)?;
        let mut left = files
            .iter()
            .fold(0_u64, |sum, file| sum.saturating_add(file.bytes));
        let exempt_bytes = files
            .iter()
            .filter(|file| file.exempt)
            .fold(0_u64, |sum, file| sum.saturating_add(file.bytes));
        let mut evicted = Vec::new();
        for file in files.into_iter().filter(|file| !file.exempt) {
            if limit.is_none_or(|limit| left <= limit) {
                break;
            }
            tx.execute(
                "UPDATE media SET state = 'evicted', path = NULL, bytes = NULL, sha256 = NULL, used_ms = NULL
                 WHERE chat = ?1 AND id = ?2",
                params![file.chat, file.id],
            )?;
            left = left.saturating_sub(file.bytes);
            evicted.push(Evicted {
                chat: file.chat,
                id: file.id,
                path: file.path,
                bytes: file.bytes,
            });
        }
        tx.commit()?;
        Ok(Eviction {
            evicted,
            bytes_left: left,
            exempt_bytes,
        })
    }

    /// Puts rows and files back in step when the store opens. Downloads that were cut off are queued again.
    /// With `files` (every file under the directories that hold full media and outbox files, as the caller
    /// listed them; not the thumbnails), a `ready` row whose file is not listed becomes `evicted`, and the
    /// answer names the files to delete: unfinished downloads (`*.part`) and files neither a row nor an unsent
    /// outbox row names. Pass `None` when the listing failed: an empty list would evict every row.
    pub fn media_reconcile(&mut self, files: Option<&[String]>) -> Result<Reconciled> {
        let tx = self.conn.transaction()?;
        let requeued = tx.execute(
            "UPDATE media SET state = 'queued', attempted_ms = NULL WHERE state = 'downloading'",
            [],
        )? as u32;
        let mut missing = Vec::new();
        let mut delete = Vec::new();
        if let Some(files) = files {
            let listed: HashSet<&str> = files.iter().map(String::as_str).collect();
            let ready: Vec<Missing> = tx
                .prepare(
                    "SELECT chat, id, path FROM media WHERE state = 'ready' AND path IS NOT NULL ORDER BY chat, id",
                )?
                .query_map([], |row| {
                    Ok(Missing {
                        chat: row.get(0)?,
                        id: row.get(1)?,
                        path: row.get(2)?,
                    })
                })?
                .collect::<rusqlite::Result<_>>()?;
            let mut named: HashSet<String> = outbox::media_files(&tx)?
                .into_iter()
                .map(|(file, _)| file)
                .collect();
            for row in ready {
                if listed.contains(row.path.as_str()) {
                    named.insert(row.path);
                    continue;
                }
                tx.execute(
                    "UPDATE media SET state = 'evicted', path = NULL, bytes = NULL, sha256 = NULL, used_ms = NULL
                     WHERE chat = ?1 AND id = ?2",
                    params![row.chat, row.id],
                )?;
                missing.push(row);
            }
            delete = files
                .iter()
                .filter(|file| file.ends_with(PART_SUFFIX) || !named.contains(file.as_str()))
                .cloned()
                .collect();
        }
        tx.commit()?;
        Ok(Reconciled {
            requeued,
            missing,
            delete,
        })
    }

    /// The cache in numbers. `now_ms` tells which files were used in the last 24 hours (they are exempt).
    pub fn media_stats(&self, now_ms: Option<i64>) -> Result<MediaStats> {
        let files = cached(&self.conn, now_ms)?;
        let mut total = Usage::default();
        let mut exempt = Usage::default();
        let mut kinds: HashMap<Kind, Usage> = HashMap::new();
        let mut paths = HashSet::new();
        for file in &files {
            total.add(file.bytes);
            if file.exempt {
                exempt.add(file.bytes);
            }
            kinds.entry(file.kind).or_default().add(file.bytes);
            paths.insert(file.path.as_str());
        }
        let mut by_kind: Vec<KindUsage> = kinds
            .into_iter()
            .map(|(kind, usage)| KindUsage { kind, usage })
            .collect();
        by_kind.sort_by(|a, b| {
            b.usage
                .bytes
                .cmp(&a.usage.bytes)
                .then_with(|| a.kind.as_str().cmp(b.kind.as_str()))
        });
        let mut outbox = Usage::default();
        for (file, size) in outbox::media_files(&self.conn)? {
            if !paths.contains(file.as_str()) {
                outbox.add(size);
            }
        }
        let count = |state: MediaState| -> Result<u32> {
            Ok(self.conn.query_row(
                "SELECT COUNT(*) FROM media WHERE state = ?1",
                [state.as_str()],
                |row| row.get(0),
            )?)
        };
        let cap_bytes = cap(&self.conn)?;
        Ok(MediaStats {
            total,
            exempt,
            by_kind,
            outbox,
            queued: count(MediaState::Queued)?,
            downloading: count(MediaState::Downloading)?,
            failed: count(MediaState::Failed)?,
            cap_bytes,
            exempt_over_cap: cap_bytes.is_some_and(|cap| exempt.bytes > cap),
        })
    }
}
