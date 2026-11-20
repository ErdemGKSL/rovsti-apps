//! What the store knows about the gateway process and the account linked to the instance, and what follows when
//! either changes.
//!
//! * **Epoch.** The gateway names one random id per process. A stored epoch other than the gateway's means the
//!   gateway restarted: its event sequence started over, so the stored `last_seq` says nothing. That is a gap
//!   whatever the numbers say: the `seq` is forgotten and the chat list must be refreshed, the path a `resync`
//!   event takes. A gateway that names no epoch changes nothing.
//! * **Account.** One instance can be linked to another WhatsApp account. The store then holds the chats of a
//!   stranger, so everything that belongs to the account goes, in one transaction ([`wipe_account`]). It runs
//!   only on proof: a stored own number next to a different one, or an `account_epoch` above the stored one. An
//!   unknown value on either side proves nothing and keeps everything.
//! * **History.** The gateway reports how far the phone's history has arrived; the last report is kept so the UI
//!   shows it after a restart.
//! * **Unlink.** [`Store::wipe`] closes the store and deletes its files.

use std::path::{Path, PathBuf};

use rosty_core::wa_identity::{IdentityMap, Rejected, WaIdKind};
use rosty_wa_proto::{HistoryStatus, InstanceStatus};
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};

use super::identity::{self, IdentityApplied};
use super::merge::ChatRekeyed;
use super::store::{Store, file_name};
use super::{Result, backfill, media};

/// Who said that the account is another one.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Told {
    /// An event, at its place in the stream: nothing of the old account can follow it.
    Event,
    /// An answer outside the stream (the instance status): events of the old account may still be on their way,
    /// up to the event that announces the change.
    Answer,
}

/// How the store is emptied for another account.
#[derive(Clone, Copy, Debug)]
pub(super) struct Wipe {
    /// Leave the outbox: its rows were written after the store was emptied, for the new account.
    pub keep_outbox: bool,
    /// The gateway's account epoch after the wipe; `None` when the caller does not know it.
    pub account_epoch: Option<u32>,
    /// Events of the old account may still arrive before the change is announced.
    pub sweep: bool,
}

/// How far the history of the phone has arrived at the gateway.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HistoryState {
    #[default]
    Idle,
    Syncing,
    Done,
}

/// `InstanceStatus.history` as the gateway reports it.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct HistoryReport {
    pub state: HistoryState,
    /// 0 to 100; `None` while the phone has not said.
    #[serde(default)]
    pub progress: Option<u32>,
    #[serde(default)]
    pub chats: u32,
    #[serde(default)]
    pub messages: u64,
    #[serde(default)]
    pub oldest_ms: Option<i64>,
}

/// The history progress the store keeps.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryProgress {
    pub state: HistoryState,
    /// 0 to 100.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress: Option<u8>,
    /// Chats and messages the gateway holds so far.
    pub chats: u32,
    pub messages: u64,
    /// The oldest message that arrived.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub oldest_ms: Option<i64>,
    /// The kind of the last batch (`initial`, `recent`, `full`, `on_demand`, `other`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sync_type: Option<String>,
    /// When this was last reported.
    pub updated_ms: i64,
}

/// What the gateway said about itself and the account, each part optional: an old gateway names none of it.
#[derive(Clone, Copy, Debug, Default)]
pub struct StatusNote<'a> {
    /// `InstanceStatus.epoch`, or the `epoch` of the socket's first frame or of a `resync`.
    pub epoch: Option<&'a str>,
    /// `InstanceStatus.account_epoch`.
    pub account_epoch: Option<u32>,
    /// `InstanceStatus.own_jid`.
    pub own_pn: Option<&'a str>,
    /// `InstanceStatus.own_lid`.
    pub own_lid: Option<&'a str>,
    /// `InstanceStatus.history`.
    pub history: Option<&'a HistoryReport>,
}

/// What [`Store::note_status`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct StatusNoted {
    /// The gateway is another process than the one the stored `seq` came from: subscribe from the start
    /// (`plan().after_seq` is `None` now) and refresh the chat list.
    pub gap: bool,
    /// Another account is linked: the store was emptied. Drop what is kept per chat outside the store (drafts,
    /// mutes, the rekey cursor, names) and delete `delete_files`.
    pub account_changed: bool,
    /// Media files of the old account to delete (cached files and files unsent outbox rows named).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub delete_files: Vec<String>,
    /// Key changes the stored rows followed (the own LID next to the own number).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rekeyed: Vec<ChatRekeyed>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rejected: Vec<Rejected>,
    /// The chat list must be refreshed ([`Store::store_chats`]).
    pub resync: bool,
    /// The history progress as stored now.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub history: Option<HistoryProgress>,
}

/// What [`Store::wipe`] removed.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Wiped {
    /// The store files that were deleted (the database, its `-wal` and `-shm`).
    pub deleted: Vec<String>,
    /// Media files the store named: delete them, and the rest of the instance's media directory.
    pub media_files: Vec<String>,
}

/// Takes in the gateway's epoch, in the caller's transaction. True when it is a gap: the stored `seq` belongs to
/// another gateway process and was forgotten, and the chat list must be refreshed. The first epoch a store with
/// a `seq` sees is a gap too: nothing says the `seq` came from this process.
pub(super) fn note_epoch(conn: &Connection, epoch: Option<&str>) -> Result<bool> {
    let Some(epoch) = epoch.filter(|epoch| !epoch.is_empty()) else {
        return Ok(false);
    };
    let (stored, last_seq): (Option<String>, Option<u64>) = conn.query_row(
        "SELECT epoch, last_seq FROM sync_state WHERE id = 1",
        [],
        |row| Ok((row.get(0)?, row.get(1)?)),
    )?;
    if stored.as_deref() == Some(epoch) {
        return Ok(false);
    }
    let gap = stored.is_some() || last_seq.is_some();
    if gap {
        // The ring of the old process is gone with it: no event of an old account can arrive any more.
        conn.execute(
            "UPDATE sync_state SET epoch = ?1, last_seq = NULL, needs_resync = 1, account_sweep = 0 WHERE id = 1",
            [epoch],
        )?;
    } else {
        conn.execute("UPDATE sync_state SET epoch = ?1 WHERE id = 1", [epoch])?;
    }
    Ok(gap)
}

/// A `resync` came: the gateway's ring no longer holds what the store missed, so no event of an old account
/// can arrive before the announcement the store waits for.
pub(super) fn resynced(conn: &Connection) -> Result<()> {
    conn.execute(
        "UPDATE sync_state SET account_sweep = 0 WHERE id = 1 AND account_sweep != 0",
        [],
    )?;
    Ok(())
}

/// Empties the store of everything that belongs to the linked account, in the caller's transaction: chats,
/// their cursors, messages (with their reactions, ticks, stars and thumbnails), media rows, the outbox, every
/// person, alias and key change, the own ids, the cursors of the chat list and of the gateway's pairs, and the
/// history progress. The answer lists the media files to delete. (Per-recipient receipts and scheduled sends
/// are not stored here; they live on the gateway.)
///
/// What stays: the instance, the gateway epoch and the event `seq` (they describe the gateway's stream, not the
/// account), the media cap (a setting), and the numbering of `merge_log`: the merge cursor moves past the
/// dropped log, so a step number never repeats and nothing of the old account replays.
pub(super) fn wipe_account(conn: &Connection, wipe: &Wipe) -> Result<Vec<String>> {
    let files = media::known_files(conn, !wipe.keep_outbox)?;
    conn.execute_batch(
        "DELETE FROM chats;
         DELETE FROM chat_sync;
         DELETE FROM messages;
         DELETE FROM pending_meta;
         DELETE FROM media;
         DELETE FROM person;
         DELETE FROM alias;
         DELETE FROM merge_log;
         DELETE FROM groups;
         DELETE FROM group_members;",
    )?;
    if !wipe.keep_outbox {
        conn.execute("DELETE FROM outbox", [])?;
    }
    conn.execute(
        "UPDATE sync_state SET needs_resync = 1, chats_synced_ms = NULL, identity_backfill = NULL,
            identity_cursor = NULL, own_pn = NULL, own_lid = NULL, history_json = NULL, account_epoch = ?1,
            account_sweep = ?2,
            merge_cursor = COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'merge_log'), 0)
         WHERE id = 1",
        params![wipe.account_epoch, wipe.sweep],
    )?;
    Ok(files)
}

fn stored_history(conn: &Connection) -> Result<Option<HistoryProgress>> {
    let text: Option<String> = conn.query_row(
        "SELECT history_json FROM sync_state WHERE id = 1",
        [],
        |row| row.get(0),
    )?;
    // A report this build cannot read (a newer one wrote it) reads as none.
    Ok(text.and_then(|text| serde_json::from_str(&text).ok()))
}

fn store_history(conn: &Connection, history: &HistoryProgress) -> Result<()> {
    conn.execute(
        "UPDATE sync_state SET history_json = ?1 WHERE id = 1",
        [serde_json::to_string(history)?],
    )?;
    Ok(())
}

/// `InstanceStatus.history` as the wire type has it. A state this build does not know (a newer gateway) keeps
/// the stored one.
fn report_of(conn: &Connection, history: &HistoryStatus) -> Result<HistoryReport> {
    use rosty_wa_proto::HistoryState as Wire;
    let state = match history.state {
        Wire::Idle => HistoryState::Idle,
        Wire::Syncing => HistoryState::Syncing,
        Wire::Done => HistoryState::Done,
        Wire::Unknown => stored_history(conn)?.map_or(HistoryState::Idle, |stored| stored.state),
    };
    Ok(HistoryReport {
        state,
        progress: history.progress,
        chats: history.chats,
        messages: history.messages,
        oldest_ms: history.oldest_ms,
    })
}

/// Keeps what the gateway's status says about the history. The kind of the last batch is the event's to tell
/// ([`history_synced`]) and stays.
fn note_history(conn: &Connection, report: &HistoryReport, now_ms: i64) -> Result<HistoryProgress> {
    let before = stored_history(conn)?;
    let history = HistoryProgress {
        state: report.state,
        progress: report.progress.map(|percent| percent.min(100) as u8),
        chats: report.chats,
        messages: report.messages,
        oldest_ms: report.oldest_ms,
        sync_type: before.and_then(|before| before.sync_type),
        updated_ms: now_ms,
    };
    store_history(conn, &history)?;
    Ok(history)
}

/// What a `history_synced` event says about the batch of history that arrived.
#[derive(Clone, Copy, Debug, Default)]
pub struct HistoryBatch<'a> {
    /// `initial`, `recent`, `full`, `on_demand` or `other`.
    pub sync_type: Option<&'a str>,
    /// The oldest message of the batch.
    pub oldest_ms: Option<i64>,
    /// 0 to 100, when the phone said.
    pub progress: Option<u32>,
    /// Messages of the batch the gateway did not hold before.
    pub messages: u32,
}

/// A `history_synced` event: keeps the kind of the batch, the oldest message seen so far and the progress, in
/// the caller's transaction. The messages the batch added are counted on top of what the status last reported,
/// so the count moves between two status answers; the next status answer sets it. The count of chats is the
/// status's to tell (a batch names the chats it wrote, not the new ones).
pub(super) fn history_synced(
    conn: &Connection,
    batch: &HistoryBatch<'_>,
    at_ms: i64,
) -> Result<()> {
    let mut history = stored_history(conn)?.unwrap_or(HistoryProgress {
        state: HistoryState::Syncing,
        ..HistoryProgress::default()
    });
    if let Some(sync_type) = batch.sync_type {
        history.sync_type = Some(sync_type.to_owned());
    }
    if let Some(percent) = batch.progress {
        history.progress = Some(percent.min(100) as u8);
    }
    history.oldest_ms = match (history.oldest_ms, batch.oldest_ms) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (a, b) => a.or(b),
    };
    history.messages = history.messages.saturating_add(u64::from(batch.messages));
    history.updated_ms = at_ms;
    store_history(conn, &history)
}

/// Deletes the store files of `instance_id` in `dir`: the database and its `-wal` and `-shm`. The answer names
/// the files that were there. The store must not be open.
pub fn delete_files(dir: &Path, instance_id: &str) -> Result<Vec<String>> {
    delete_store_files(&dir.join(file_name(instance_id)))
}

fn delete_store_files(path: &Path) -> Result<Vec<String>> {
    let mut deleted = Vec::new();
    for suffix in ["", "-wal", "-shm"] {
        let mut name = path.as_os_str().to_owned();
        name.push(suffix);
        let file = PathBuf::from(name);
        match std::fs::remove_file(&file) {
            Ok(()) => deleted.push(file.to_string_lossy().into_owned()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(deleted)
}

impl Store {
    /// Empties the store for another account and starts the identity map over.
    pub(super) fn account_wipe(&mut self, wipe: Wipe) -> Result<Vec<String>> {
        let wiped = in_tx(&mut self.conn, |tx| wipe_account(tx, &wipe));
        let files = self.healed(wiped)?;
        self.identity = IdentityMap::new();
        let filled = backfill::run(&mut self.conn, &mut self.identity);
        self.healed(filled)?;
        Ok(files)
    }

    /// Takes in what the gateway says about itself and the linked account: call it with every status answer
    /// (`wa.status`), and with the `epoch` alone when the event socket opens or a `resync` names one.
    ///
    /// * Another `epoch` than the stored one is a gap (`gap`): the `seq` is forgotten and the chat list must be
    ///   refreshed. No epoch changes nothing.
    /// * An `account_epoch` above the stored one, or an own number other than the stored one, is another
    ///   account: the store is emptied in one transaction (`account_changed`, `delete_files`). A value that is
    ///   unknown on either side keeps everything; so does the same account linked again.
    /// * The history progress is stored as reported.
    pub fn note_status(&mut self, note: &StatusNote<'_>, now_ms: i64) -> Result<StatusNoted> {
        self.note_gateway(note, None, now_ms, Told::Answer)
    }

    /// [`Self::note_status`] with a whole status answer (`wa.status`): its epoch, account epoch, own ids and
    /// history report.
    pub fn note_instance_status(
        &mut self,
        status: &InstanceStatus,
        now_ms: i64,
    ) -> Result<StatusNoted> {
        self.note_status_told(status, now_ms, Told::Answer)
    }

    /// A whole status, as an answer or as a `state` event of the stream.
    pub(super) fn note_status_told(
        &mut self,
        status: &InstanceStatus,
        now_ms: i64,
        told: Told,
    ) -> Result<StatusNoted> {
        let note = StatusNote {
            epoch: status.epoch.as_deref(),
            account_epoch: status.account_epoch,
            own_pn: status.own_jid.as_deref(),
            own_lid: status.own_lid.as_deref(),
            history: None,
        };
        self.note_gateway(&note, status.history.as_ref(), now_ms, told)
    }

    /// [`Self::note_status`], with who said so. `wire_history` is the history report as the wire type has it,
    /// for a note without `history`.
    ///
    /// A `state` event ([`Told::Event`]) stands at its place in the stream, so nothing of an account before it
    /// can follow: its wipe waits for no announcement. A `state` event of an account before the stored one (a
    /// lower `account_epoch`: the event was still in the gateway's ring) only names the gateway process.
    fn note_gateway(
        &mut self,
        note: &StatusNote<'_>,
        wire_history: Option<&HistoryStatus>,
        now_ms: i64,
        told: Told,
    ) -> Result<StatusNoted> {
        let mut noted = StatusNoted {
            gap: note_epoch(&self.conn, note.epoch)?,
            ..StatusNoted::default()
        };
        let stored_epoch: Option<u32> = self.conn.query_row(
            "SELECT account_epoch FROM sync_state WHERE id = 1",
            [],
            |row| row.get(0),
        )?;
        let of_an_older_account = told == Told::Event
            && matches!((stored_epoch, note.account_epoch), (Some(stored), Some(new)) if new < stored);
        if of_an_older_account {
            noted.resync = noted.gap;
            noted.history = stored_history(&self.conn)?;
            return Ok(noted);
        }
        let raised =
            matches!((stored_epoch, note.account_epoch), (Some(stored), Some(new)) if new > stored);
        if raised {
            noted.delete_files = self.account_wipe(Wipe {
                keep_outbox: false,
                account_epoch: note.account_epoch,
                sweep: told == Told::Answer && !noted.gap,
            })?;
            noted.account_changed = true;
        }
        if note.own_pn.is_some() || note.own_lid.is_some() {
            let own = self.set_own(note.own_pn, note.own_lid, now_ms, told)?;
            noted.account_changed |= own.account_changed;
            noted.delete_files.extend(own.delete_files);
            noted.rekeyed = own.rekeyed;
            noted.rejected = own.rejected;
            noted.resync = own.resync;
        }
        // A wipe by the own number alone left the epoch unknown; the answer that proved it may name it. The
        // store waits for the announcement only when one can come: the same gateway process (its ring may still
        // hold events of the old account), and a gateway that names an account epoch (an older one announces
        // nothing).
        let no_announcement = noted.gap || (noted.account_changed && note.account_epoch.is_none());
        self.conn.execute(
            "UPDATE sync_state SET account_epoch = COALESCE(?1, account_epoch),
                account_sweep = CASE WHEN ?2 THEN 0 ELSE account_sweep END
             WHERE id = 1",
            params![note.account_epoch, no_announcement],
        )?;
        let wire_report = match (note.history, wire_history) {
            (None, Some(history)) => Some(report_of(&self.conn, history)?),
            _ => None,
        };
        if let Some(report) = note.history.or(wire_report.as_ref()) {
            note_history(&self.conn, report, now_ms)?;
        }
        noted.resync |= noted.gap || noted.account_changed;
        noted.history = stored_history(&self.conn)?;
        Ok(noted)
    }

    /// The gateway announced, at this place in its event stream, that the linked account changed from `old_pn`
    /// to `new_pn` (the `account_changed` event). Everything stored before belongs to the old account:
    ///
    /// * the stored own number is another one than `new_pn`: the store is emptied;
    /// * the store was already emptied for `new_pn` on the word of a status answer: events of the old account
    ///   may have arrived since, so the rows go once more (the outbox, written for the new account, stays);
    /// * the store does not know its own number: it is emptied only when the event itself names two different
    ///   numbers.
    ///
    /// Apply it once, in stream order (an event at or before the stored `seq` was applied already).
    pub fn note_account_event(
        &mut self,
        old_pn: Option<&str>,
        new_pn: &str,
        at_ms: i64,
    ) -> Result<IdentityApplied> {
        let Some(new) = identity::bare_of(Some(new_pn), WaIdKind::Pn) else {
            return Ok(IdentityApplied::default());
        };
        let old = identity::bare_of(old_pn, WaIdKind::Pn);
        let (stored, _) = identity::own(&self.conn)?;
        let (sweep, account_epoch): (bool, Option<u32>) = self.conn.query_row(
            "SELECT account_sweep, account_epoch FROM sync_state WHERE id = 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let wipe = match &stored {
            Some(stored) if *stored != new => Some(Wipe {
                keep_outbox: false,
                account_epoch: None,
                sweep: false,
            }),
            Some(_) if sweep => Some(Wipe {
                keep_outbox: true,
                account_epoch,
                sweep: false,
            }),
            None if old.is_some_and(|old| old != new) => Some(Wipe {
                keep_outbox: false,
                account_epoch: None,
                sweep: false,
            }),
            _ => None,
        };
        let delete_files = match wipe {
            Some(wipe) => self.account_wipe(wipe)?,
            None => Vec::new(),
        };
        let mut applied = self.set_own(Some(&new), None, at_ms, Told::Event)?;
        applied.account_changed = wipe.is_some();
        applied.resync |= wipe.is_some();
        applied.delete_files = delete_files;
        Ok(applied)
    }

    /// A `history_synced` event arrived outside [`Self::apply_events`]: its kind, oldest message and progress
    /// are kept next to what the status reported.
    pub fn note_history_synced(&mut self, batch: &HistoryBatch<'_>, at_ms: i64) -> Result<()> {
        history_synced(&self.conn, batch, at_ms)
    }

    /// The history progress the gateway last reported; `None` before the first report.
    pub fn history(&self) -> Result<Option<HistoryProgress>> {
        stored_history(&self.conn)
    }

    /// The gateway epoch the stored `seq` belongs to; `None` before a gateway named one.
    pub fn epoch(&self) -> Result<Option<String>> {
        Ok(self
            .conn
            .query_row("SELECT epoch FROM sync_state WHERE id = 1", [], |row| {
                row.get(0)
            })?)
    }

    /// Unlink: closes the store and deletes its files. The answer names the media files the store knew (cached
    /// files and files of unsent outbox rows); the caller deletes them and the instance's media directory. A
    /// store in memory has no files of its own.
    pub fn wipe(self) -> Result<Wiped> {
        let media_files = media::known_files(&self.conn, true)?;
        let Store { conn, path, .. } = self;
        // Closing first: the `-wal` and `-shm` files belong to the open connection.
        drop(conn);
        let deleted = match path {
            Some(path) => delete_store_files(&path)?,
            None => Vec::new(),
        };
        Ok(Wiped {
            deleted,
            media_files,
        })
    }
}

fn in_tx<T>(conn: &mut Connection, f: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
    let tx = conn.transaction()?;
    let out = f(&tx)?;
    tx.commit()?;
    Ok(out)
}
