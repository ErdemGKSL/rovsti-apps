//! The schema and its migrations. `PRAGMA user_version` is the number of migrations applied.

use rusqlite::Connection;

use super::{Error, Result};

/// The schema version this build writes.
pub const SCHEMA_VERSION: u32 = 8;

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

/// v3: the starred messages of every chat, newest first, without reading the whole table.
const V3: &str = "
CREATE INDEX messages_starred ON messages (ts DESC, chat DESC, id DESC) WHERE starred = 1;
";

/// v4: identity. One person behind a phone-number id and a LID (`person`, `alias`), the key changes stored rows
/// must follow (`merge_log`, applied up to `sync_state.merge_cursor`), the account's own ids, and the id a chat or
/// a message arrived under when it is not the key it is stored under, and how far the gateway's list of pairs was
/// read (`identity_cursor`). Additive: no stored row changes here; the
/// backfill (`identity_backfill`: NULL before it began, a cursor while it runs, `done`) and the merges run as
/// normal steps after the migration.
const V4: &str = "
CREATE TABLE person (
    person_id INTEGER PRIMARY KEY,
    -- The canonical key: `<digits>@s.whatsapp.net`, or `<user>@lid` while no number is known.
    key TEXT NOT NULL UNIQUE
);
CREATE TABLE alias (
    -- `pn` or `lid`.
    kind TEXT NOT NULL,
    -- The digits of the number, or the LID user.
    user TEXT NOT NULL,
    person_id INTEGER NOT NULL,
    -- `current` or `former`.
    state TEXT NOT NULL,
    source TEXT NOT NULL,
    learned_ms INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, user)
) WITHOUT ROWID;
CREATE INDEX alias_by_person ON alias (person_id);
CREATE TABLE merge_log (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    at_ms INTEGER NOT NULL,
    from_key TEXT NOT NULL,
    into_key TEXT NOT NULL,
    reason TEXT NOT NULL
);
ALTER TABLE sync_state ADD COLUMN own_pn TEXT;
ALTER TABLE sync_state ADD COLUMN own_lid TEXT;
-- The last `merge_log.seq` applied to the stored rows.
ALTER TABLE sync_state ADD COLUMN merge_cursor INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_state ADD COLUMN identity_backfill TEXT;
-- The cursor of the last page of the gateway's pairs taken in (`GET /v1/identity/mappings?since=`); NULL before
-- the first one.
ALTER TABLE sync_state ADD COLUMN identity_cursor TEXT;
-- The last id the gateway used for the chat, when it is not `jid`.
ALTER TABLE chats ADD COLUMN wire_jid TEXT;
-- The id the author arrived under, when it is not `sender`.
ALTER TABLE messages ADD COLUMN wire_sender TEXT;
";

/// v5: media rows and what the store knows about the gateway process and the linked account.
///
/// `media` holds the full file of a message: where it is in its download (`state`), the cached file and the
/// bookkeeping of the cap. It is filled from the three `media_*` columns of `messages`, which are cleared and no
/// longer written or read: `media` is the only place a cached file is recorded. The thumbnail stays in
/// `messages.thumb`.
///
/// `sync_state` gains the gateway's `epoch` (one id per gateway process: another one is a gap), the
/// `account_epoch` the gateway raises when another account is linked, `account_sweep` (the store was emptied for
/// another account on the word of a status answer and the event that announces the change has not been seen:
/// events of the old account may still arrive before it), the history progress the gateway last reported
/// (`history_json`) and the media cap in bytes (`-1`: no limit).
const V5: &str = "
CREATE TABLE media (
    chat TEXT NOT NULL,
    id TEXT NOT NULL,
    -- The kind of the message, for the sizes by kind.
    kind TEXT NOT NULL DEFAULT 'unsupported',
    mime TEXT,
    size_expected INTEGER,
    -- none | queued | downloading | ready | evicted | failed | gone
    state TEXT NOT NULL DEFAULT 'none',
    -- The cached file and its size; set while `ready` only.
    path TEXT,
    bytes INTEGER,
    -- Hex, when the caller checked the file.
    sha256 TEXT,
    used_ms INTEGER,
    -- Never evicted.
    pinned INTEGER NOT NULL DEFAULT 0,
    -- Where the user saved a copy (the gallery); a saved file is never evicted.
    saved_uri TEXT,
    -- Asked for by a tap, not by the auto-download rules.
    by_tap INTEGER NOT NULL DEFAULT 0,
    queued_ms INTEGER,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_ms INTEGER NOT NULL DEFAULT 0,
    -- When the download was handed out.
    attempted_ms INTEGER,
    last_error TEXT,
    PRIMARY KEY (chat, id)
) WITHOUT ROWID;
CREATE INDEX media_lru ON media (used_ms) WHERE state = 'ready';
CREATE INDEX media_queue ON media (state, next_attempt_ms) WHERE state IN ('queued', 'downloading');
INSERT INTO media (chat, id, kind, mime, size_expected, state, path, bytes, used_ms)
    SELECT chat, id, kind, mime, size, 'ready', media_path, COALESCE(media_bytes, 0), media_used_ms
    FROM messages WHERE media_path IS NOT NULL;
DROP INDEX messages_cached_media;
UPDATE messages SET media_path = NULL, media_bytes = NULL, media_used_ms = NULL WHERE media_path IS NOT NULL;
ALTER TABLE sync_state ADD COLUMN epoch TEXT;
ALTER TABLE sync_state ADD COLUMN account_epoch INTEGER;
ALTER TABLE sync_state ADD COLUMN account_sweep INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_state ADD COLUMN history_json TEXT;
ALTER TABLE sync_state ADD COLUMN media_cap_bytes INTEGER NOT NULL DEFAULT 536870912;
";

/// v6: what the owner did with a message on this device, and the gallery of a chat.
///
/// `viewed_ms`: a view-once message was opened here ([`super::Store::media_viewed`]); its file is gone for good.
/// `played_ms`: the played receipt of a received voice note was queued ([`super::Store::mark_played`]); it is
/// sent once. `messages_gallery` serves [`super::Store::chat_media`]: the photos, videos, audio and documents
/// of a chat, newest first, without walking its texts. Additive: no stored row changes.
const V6: &str = "
ALTER TABLE messages ADD COLUMN viewed_ms INTEGER;
ALTER TABLE messages ADD COLUMN played_ms INTEGER;
CREATE INDEX messages_gallery ON messages (chat, ts DESC, id DESC)
    WHERE kind IN ('image', 'video', 'audio', 'document') AND deleted = 0;
";

/// v7: message actions (E6-WA R4).
///
/// `messages.expires_at`: when a disappearing message goes (its send time plus its timer; the timer, the link
/// preview, the forwarding score and a conversation line live in `extra_json`); [`super::Store::expire`] deletes
/// the rows that are due, and an expired message is never stored again. `messages.hidden_ms`: deleted for me (here
/// or on another device of the account): the thread no longer shows it. `chats.ephemeral_s`: the chat's
/// disappearing timer. `pending_meta`: what the gateway said about a message the store does not have yet (an edit or
/// delete for everyone that came first), applied when the message arrives. Additive: no stored row changes.
const V7: &str = "
ALTER TABLE messages ADD COLUMN expires_at INTEGER;
ALTER TABLE messages ADD COLUMN hidden_ms INTEGER;
ALTER TABLE chats ADD COLUMN ephemeral_s INTEGER;
CREATE INDEX messages_expiry ON messages (expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE pending_meta (
    chat TEXT NOT NULL,
    id TEXT NOT NULL,
    body_json TEXT,
    meta_json TEXT NOT NULL,
    event_ms INTEGER,
    at_ms INTEGER NOT NULL,
    PRIMARY KEY (chat, id)
) WITHOUT ROWID;
";

/// v8: groups (E6-WA R6).
///
/// `groups`: what the gateway answered for a group (subject, description, creation, settings, timer, size, its
/// place in a community) and `gone` (I left it or was removed); `fetched_ms` 0 for a group known only from a
/// notice line or the left flag. `group_members`: its members by the id the gateway lists them under, their other
/// id, role and WhatsApp name. A notice line newer than the fetch merges into the members. Only new tables.
const V8: &str = "
CREATE TABLE groups (
    jid TEXT PRIMARY KEY,
    subject TEXT,
    description TEXT,
    description_id TEXT,
    created_ms INTEGER,
    creator TEXT,
    announce INTEGER NOT NULL DEFAULT 0,
    locked INTEGER NOT NULL DEFAULT 0,
    approval INTEGER NOT NULL DEFAULT 0,
    admins_add INTEGER NOT NULL DEFAULT 0,
    gone INTEGER NOT NULL DEFAULT 0,
    ephemeral_s INTEGER,
    size INTEGER,
    community_kind TEXT,
    community_parent TEXT,
    fetched_ms INTEGER NOT NULL DEFAULT 0
) WITHOUT ROWID;
CREATE TABLE group_members (
    group_jid TEXT NOT NULL,
    jid TEXT NOT NULL,
    alt TEXT,
    role TEXT NOT NULL DEFAULT 'member',
    name TEXT,
    PRIMARY KEY (group_jid, jid)
) WITHOUT ROWID;
";

const MIGRATIONS: [&str; SCHEMA_VERSION as usize] = [V1, V2, V3, V4, V5, V6, V7, V8];

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
