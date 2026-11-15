//! The schema and its migrations. `PRAGMA user_version` is the number of migrations applied.

use rusqlite::Connection;

use super::{Error, Result};

/// The schema version this build writes.
pub const SCHEMA_VERSION: u32 = 2;

/// v1: the instance row, chats, the per-chat cursor and messages.
const V1: &str = "
CREATE TABLE sync_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    instance_id TEXT NOT NULL,
    -- The last gateway event applied; NULL before the first one.
    last_seq INTEGER,
    -- The chat list must be refreshed (first run, or a gap).
    needs_resync INTEGER NOT NULL DEFAULT 1,
    chats_synced_ms INTEGER
);
CREATE TABLE chats (
    jid TEXT PRIMARY KEY,
    name TEXT,
    push_name TEXT,
    is_group INTEGER NOT NULL DEFAULT 0,
    unread INTEGER NOT NULL DEFAULT 0,
    pinned INTEGER NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0,
    muted_until_ms INTEGER,
    marked_unread INTEGER NOT NULL DEFAULT 0,
    picture_id TEXT,
    -- Deleted on another device: left out of the list until a message arrives. Its messages stay.
    hidden INTEGER NOT NULL DEFAULT 0,
    -- Cleared on another device: messages up to here are left out of the thread. They stay stored.
    cleared_ts INTEGER
) WITHOUT ROWID;
CREATE TABLE chat_sync (
    jid TEXT PRIMARY KEY,
    newest_ts INTEGER,
    newest_id TEXT,
    oldest_ts INTEGER,
    -- A page older than the oldest stored message came back short: there is no older history.
    reached_start INTEGER NOT NULL DEFAULT 0,
    -- A resync is filling the hole between `gap_floor_ts` (the newest message stored when it began) and the
    -- gateway's newest; `gap_before` is the `before=` of the next page (NULL: the first page).
    gap_open INTEGER NOT NULL DEFAULT 0,
    gap_floor_ts INTEGER,
    gap_before INTEGER
) WITHOUT ROWID;
CREATE TABLE messages (
    chat TEXT NOT NULL,
    id TEXT NOT NULL,
    sender TEXT,
    sender_name TEXT,
    from_me INTEGER NOT NULL,
    ts INTEGER NOT NULL,
    kind TEXT NOT NULL,
    -- The text, or the caption of media.
    text TEXT,
    mime TEXT,
    size INTEGER,
    quoted_id TEXT,
    quoted_json TEXT,
    reactions TEXT NOT NULL DEFAULT '[]',
    status TEXT,
    edited_ms INTEGER,
    edit_history TEXT NOT NULL DEFAULT '[]',
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER,
    deleted_by TEXT,
    forwarded INTEGER NOT NULL DEFAULT 0,
    starred INTEGER NOT NULL DEFAULT 0,
    media_json TEXT,
    -- Location, contact card, mentions.
    extra_json TEXT,
    thumb BLOB,
    media_path TEXT,
    media_bytes INTEGER,
    media_used_ms INTEGER,
    PRIMARY KEY (chat, id)
) WITHOUT ROWID;
CREATE INDEX messages_by_time ON messages (chat, ts DESC, id DESC);
CREATE INDEX messages_cached_media ON messages (media_used_ms) WHERE media_path IS NOT NULL;
";

/// v2: the outbox, and the client id that ties a sent message to the outbox row it came from.
const V2: &str = "
ALTER TABLE messages ADD COLUMN client_id TEXT;
CREATE INDEX messages_by_client ON messages (client_id) WHERE client_id IS NOT NULL;
CREATE TABLE outbox (
    -- The order written.
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id TEXT NOT NULL UNIQUE,
    chat TEXT NOT NULL,
    kind TEXT NOT NULL,
    -- The action as JSON.
    payload TEXT NOT NULL,
    created_ms INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_ms INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL DEFAULT 'waiting',
    last_error TEXT,
    -- When the last attempt was handed out.
    attempted_ms INTEGER,
    -- An attempt went out and no definite answer came back: an echo may confirm it.
    maybe_sent INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX outbox_by_chat ON outbox (chat, seq);
";

const MIGRATIONS: [&str; SCHEMA_VERSION as usize] = [V1, V2];

/// The version of the file.
pub(super) fn version(conn: &Connection) -> Result<u32> {
    Ok(conn.query_row("PRAGMA user_version", [], |row| row.get(0))?)
}

/// Brings the file to [`SCHEMA_VERSION`], one migration per transaction. A file from a newer build is refused
/// untouched.
pub(super) fn migrate(conn: &mut Connection) -> Result<()> {
    let found = version(conn)?;
    if found > SCHEMA_VERSION {
        return Err(Error::NewerSchema {
            found,
            supported: SCHEMA_VERSION,
        });
    }
    for (index, sql) in MIGRATIONS.iter().enumerate().skip(found as usize) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        tx.pragma_update(None, "user_version", index as u32 + 1)?;
        tx.commit()?;
    }
    Ok(())
}

/// The first `upto` migrations only, for the migration tests.
#[cfg(test)]
pub(super) fn migrate_to(conn: &mut Connection, upto: u32) -> Result<()> {
    for (index, sql) in MIGRATIONS.iter().enumerate().take(upto as usize) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        tx.pragma_update(None, "user_version", index as u32 + 1)?;
        tx.commit()?;
    }
    Ok(())
}
