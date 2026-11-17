//! The upgrade migration's wipe (plan 8): every plaintext WhatsApp file an app may hold, deleted once the vault is
//! written. Databases are erased in place first (`secure_delete`, every row deleted, `VACUUM`), which is the best
//! an app can do on flash; only a factory reset is a guarantee, and the UI says so.
//!
//! The inventory, by directory (Android names; [`AppDirs`]):
//!
//! | dir                          | what                                                                     |
//! |------------------------------|--------------------------------------------------------------------------|
//! | data (`noBackupFilesDir`)    | `wa-sync-*.db` with `-wal`, `-shm`, `-journal` (the offline store)       |
//! | data                         | `whatsapp-link.json` (connect key), `wa-live-names.json`, `wa-rekeys.json`, `wa-identity.json` |
//! | data                         | the `wa:` entries of `drafts.json` (SMS drafts stay)                     |
//! | files (`filesDir`)           | `wa-media/` (downloads, thumbnails, staged outgoing files), `wa-stars.json` (legacy) |
//! | cache (`cacheDir`)           | `wa-status/`, `wa-avatars/`, `wa-media/` (`wa.open`'s default temp dir)  |
//!
//! Every JSON file goes with its `.tmp` sibling. Kept, because they are not WhatsApp data or are not files Rust can
//! edit: `messages-prefs.json` (mutes, keyed by chat), the `rosty_message_reminders` and `wa-link-share`
//! SharedPreferences, `people.json` and `people-sync.json` (the Contacts people cache), `recent-reactions.json`.

use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};
use std::time::Duration;

use rusqlite::{Connection, OpenFlags};
use serde::Serialize;

use crate::messages::drafts::DRAFTS_FILE;
use crate::storage;
use crate::wa_link::link::{LINK_FILE, StoredLink};

/// Plaintext JSON files in the data directory.
pub const DATA_FILES: [&str; 4] = [
    LINK_FILE,
    "wa-live-names.json",
    "wa-rekeys.json",
    crate::phone::wa_idbook::FILE,
];
/// Plaintext files in the files directory.
pub const FILES_FILES: [&str; 1] = ["wa-stars.json"];
/// Directories in the files directory.
pub const FILES_DIRS: [&str; 1] = ["wa-media"];
/// Directories in the cache directory.
pub const CACHE_DIRS: [&str; 3] = ["wa-status", "wa-avatars", "wa-media"];
/// Prefix and suffixes of the offline store files.
const STORE_PREFIX: &str = "wa-sync-";
const STORE_SUFFIXES: [&str; 4] = [".db", ".db-wal", ".db-shm", ".db-journal"];

/// The three directories of an Android app.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppDirs {
    /// `noBackupFilesDir`, the Rust core's data directory.
    pub data: PathBuf,
    /// `filesDir`.
    pub files: PathBuf,
    /// `cacheDir`.
    pub cache: PathBuf,
}

/// What [`wipe_plaintext`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct WipeReport {
    /// Files and directories deleted, sorted.
    pub deleted: Vec<String>,
    /// Databases erased in place before they were deleted.
    pub scrubbed: Vec<String>,
    /// WhatsApp drafts removed from `drafts.json`.
    pub drafts_removed: usize,
    /// What could not be done; the rest went ahead.
    pub failed: Vec<WipeFailure>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct WipeFailure {
    pub path: String,
    pub error: String,
}

impl WipeReport {
    fn fail(&mut self, path: &Path, error: impl ToString) {
        self.failed.push(WipeFailure {
            path: path.to_string_lossy().into_owned(),
            error: error.to_string(),
        });
    }

    fn delete_file(&mut self, path: &Path) {
        match std::fs::remove_file(path) {
            Ok(()) => self.deleted.push(path.to_string_lossy().into_owned()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => self.fail(path, error),
        }
    }

    fn delete_dir(&mut self, path: &Path) {
        match std::fs::remove_dir_all(path) {
            Ok(()) => self.deleted.push(path.to_string_lossy().into_owned()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => self.fail(path, error),
        }
    }
}

/// The plaintext link of an app that predates the vault: the source of the migration.
pub fn legacy_link(data: &Path) -> Option<StoredLink> {
    StoredLink::load(data)
}

/// Deletes every plaintext WhatsApp file of the inventory. Never stops at the first failure.
pub fn wipe_plaintext(dirs: &AppDirs) -> WipeReport {
    let mut report = WipeReport::default();
    wipe_stores(&dirs.data, &mut report);
    for name in DATA_FILES {
        delete_json(&dirs.data, name, &mut report);
    }
    wipe_drafts(&dirs.data, &mut report);
    for name in FILES_FILES {
        delete_json(&dirs.files, name, &mut report);
    }
    for name in FILES_DIRS {
        report.delete_dir(&dirs.files.join(name));
    }
    for name in CACHE_DIRS {
        report.delete_dir(&dirs.cache.join(name));
    }
    report.deleted.sort();
    report.scrubbed.sort();
    report
}

fn delete_json(dir: &Path, name: &str, report: &mut WipeReport) {
    report.delete_file(&dir.join(name));
    report.delete_file(&dir.join(format!("{name}.tmp")));
}

/// The offline store files: each database is erased in place, then it and its siblings are deleted. A sibling
/// whose database is gone is deleted too.
fn wipe_stores(data: &Path, report: &mut WipeReport) {
    let entries = match std::fs::read_dir(data) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return,
        Err(error) => return report.fail(data, error),
    };
    let mut files: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| {
                    name.starts_with(STORE_PREFIX)
                        && STORE_SUFFIXES.iter().any(|suffix| name.ends_with(suffix))
                })
        })
        .collect();
    files.sort();
    for db in files
        .iter()
        .filter(|path| path.extension().is_some_and(|e| e == "db"))
    {
        match scrub(db) {
            Ok(()) => report.scrubbed.push(db.to_string_lossy().into_owned()),
            Err(error) => report.fail(db, format!("not erased in place: {error}")),
        }
    }
    // Erasing may have folded or removed the WAL; delete what is left.
    for path in &files {
        report.delete_file(path);
    }
}

/// Overwrites every row with zeros (`secure_delete`), folds the WAL back, and shrinks the file. Never creates a
/// database that is not there.
fn scrub(path: &Path) -> rusqlite::Result<()> {
    let conn = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    conn.busy_timeout(Duration::from_secs(2))?;
    conn.execute_batch("PRAGMA secure_delete = ON;")?;
    let tables: Vec<String> = conn
        .prepare(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        )?
        .query_map([], |row| row.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    for table in tables {
        conn.execute(
            &format!("DELETE FROM \"{}\"", table.replace('"', "\"\"")),
            [],
        )?;
    }
    // The rows are zeros now. Folding the WAL back and shrinking the file are best effort: another connection of
    // this process may still hold the store open.
    let _ = conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
    let _ = conn.execute_batch("VACUUM;");
    let _ = conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
    conn.close().map_err(|(_, error)| error)
}

#[cfg(test)]
pub(super) fn scrub_for_test(path: &Path) -> rusqlite::Result<()> {
    scrub(path)
}

/// Removes the `wa:` drafts; SMS drafts stay.
fn wipe_drafts(data: &Path, report: &mut WipeReport) {
    let Some(mut drafts) = storage::load_json::<BTreeMap<String, String>>(data, DRAFTS_FILE) else {
        return;
    };
    let before = drafts.len();
    drafts.retain(|tag, _| !tag.starts_with("wa:"));
    let removed = before - drafts.len();
    if removed == 0 {
        return;
    }
    match storage::save_json(data, DRAFTS_FILE, &drafts) {
        Ok(()) => report.drafts_removed = removed,
        Err(error) => report.fail(&data.join(DRAFTS_FILE), error),
    }
}
