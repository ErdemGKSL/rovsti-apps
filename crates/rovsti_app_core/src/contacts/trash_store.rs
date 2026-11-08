//! Reading and writing `contacts-trash.json` in the app data directory.
//!
//! The value and how it ages live in `rovsti_core::contact_trash`. A missing,
//! damaged or oversized file reads as an empty Trash; a write goes to a
//! temporary file first and is renamed over the old one, so a crash never
//! leaves half a file.

use std::{
    fs, io,
    path::{Path, PathBuf},
};

use rovsti_core::contact_trash::{MAX_FILE_BYTES, SCHEMA_VERSION, Trash};

/// File name inside the app data directory.
const FILE: &str = "contacts-trash.json";

fn path(dir: &Path) -> PathBuf {
    dir.join(FILE)
}

/// The saved Trash, or an empty one when there is none.
pub fn trash_load(dir: &Path) -> Trash {
    let Ok(bytes) = fs::read(path(dir)) else {
        return Trash::default();
    };
    if bytes.len() > MAX_FILE_BYTES {
        log::warn!("contacts trash is {} bytes; ignoring", bytes.len());
        return Trash::default();
    }
    serde_json::from_slice(&bytes).unwrap_or_else(|error| {
        log::warn!("contacts trash unreadable ({error}); starting empty");
        Trash::default()
    })
}

/// The saved Trash with the entries older than the retention period dropped
/// (and the file rewritten when any were).
pub fn trash_load_purged(dir: &Path, now_ms: i64) -> Trash {
    let mut trash = trash_load(dir);
    if trash.purge_expired(now_ms) > 0
        && let Err(error) = trash_save(dir, &trash)
    {
        log::warn!("could not save the purged Trash: {error}");
    }
    trash
}

/// Writes `trash`; the old file stays whole if this fails. A file that would
/// be too big to read back again is refused.
pub fn trash_save(dir: &Path, trash: &Trash) -> io::Result<()> {
    let mut copy = trash.clone();
    copy.schema_version = SCHEMA_VERSION;
    let json = serde_json::to_vec_pretty(&copy).map_err(io::Error::other)?;
    if json.len() > MAX_FILE_BYTES {
        return Err(io::Error::other("the Trash file would be too big"));
    }
    let target = path(dir);
    let temp = dir.join(format!("{FILE}.tmp"));
    fs::write(&temp, json)?;
    fs::rename(&temp, &target)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::contact_edit::ContactDraft;

    /// A fresh empty directory under the system temp dir.
    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rovsti-contacts-trash-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |d| d.as_nanos())
        ));
        fs::create_dir_all(&dir).expect("temp dir");
        dir
    }

    fn draft(name: &str) -> ContactDraft {
        ContactDraft {
            given: name.to_owned(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn nothing_saved_reads_as_empty() {
        let dir = scratch("none");
        assert_eq!(trash_load(&dir), Trash::default());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn what_is_saved_comes_back() {
        let dir = scratch("roundtrip");
        let mut trash = Trash::default();
        trash.push(vec![draft("Ayşe"), draft("Burak")], 1_000);
        trash_save(&dir, &trash).expect("writes");
        let back = trash_load(&dir);
        assert_eq!(back.entries, trash.entries);
        assert_eq!(back.entries.len(), 2);
        assert!(!dir.join("contacts-trash.json.tmp").exists());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn a_second_save_replaces_the_first() {
        let dir = scratch("replace");
        let mut trash = Trash::default();
        trash.push(vec![draft("A")], 1);
        trash_save(&dir, &trash).expect("first");
        trash.clear();
        trash_save(&dir, &trash).expect("second");
        assert!(trash_load(&dir).entries.is_empty());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn loading_drops_what_is_older_than_thirty_days() {
        let dir = scratch("purge");
        let day = 86_400_000;
        let mut trash = Trash::default();
        trash.push(vec![draft("Old")], 0);
        trash.push(vec![draft("New")], 40 * day);
        trash_save(&dir, &trash).expect("writes");
        let back = trash_load_purged(&dir, 40 * day + 1);
        assert_eq!(back.entries.len(), 1);
        assert_eq!(back.entries[0].draft.given, "New");
        assert_eq!(trash_load(&dir).entries.len(), 1);
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn a_damaged_file_reads_as_empty() {
        let dir = scratch("damaged");
        fs::write(path(&dir), b"{ not json").expect("writes");
        assert_eq!(trash_load(&dir), Trash::default());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn an_oversized_file_is_ignored() {
        let dir = scratch("big");
        fs::write(path(&dir), vec![b' '; MAX_FILE_BYTES + 1]).expect("writes");
        assert_eq!(trash_load(&dir), Trash::default());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn a_trash_too_big_to_read_back_is_not_written() {
        let dir = scratch("refuse");
        let mut trash = Trash::default();
        let mut big = draft("Big");
        big.note = "x".repeat(MAX_FILE_BYTES);
        trash.push(vec![big], 1);
        assert!(trash_save(&dir, &trash).is_err());
        assert!(!path(&dir).exists());
        fs::remove_dir_all(dir).ok();
    }
}
