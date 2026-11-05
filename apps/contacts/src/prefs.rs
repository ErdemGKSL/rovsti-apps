//! Reading and writing `contacts-prefs.json` in the app data directory.
//!
//! The value and how it reads (defaults, unknown fields) live in
//! `rovsti_core::contacts_prefs`. A missing, damaged or oversized file reads
//! as the defaults; a write goes to a temporary file first and is renamed over
//! the old one, so a crash never leaves half a file.

use std::{
    fs, io,
    path::{Path, PathBuf},
};

use rovsti_core::contacts_prefs::{ContactsPrefs, MAX_FILE_BYTES};

/// File name inside the app data directory.
const FILE: &str = "contacts-prefs.json";

fn path(dir: &Path) -> PathBuf {
    dir.join(FILE)
}

/// The saved preferences, or the defaults when there are none.
pub(crate) fn load(dir: &Path) -> ContactsPrefs {
    let Ok(bytes) = fs::read(path(dir)) else {
        return ContactsPrefs::default();
    };
    if bytes.len() > MAX_FILE_BYTES {
        log::warn!("contacts prefs are {} bytes; ignoring", bytes.len());
        return ContactsPrefs::default();
    }
    serde_json::from_slice(&bytes).unwrap_or_else(|error| {
        log::warn!("contacts prefs unreadable ({error}); using defaults");
        ContactsPrefs::default()
    })
}

/// Writes `prefs`; the old file stays whole if this fails.
pub(crate) fn save(dir: &Path, prefs: ContactsPrefs) -> io::Result<()> {
    let json = serde_json::to_vec_pretty(&prefs).map_err(io::Error::other)?;
    let target = path(dir);
    let temp = dir.join(format!("{FILE}.tmp"));
    fs::write(&temp, json)?;
    fs::rename(&temp, &target)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::{settings::SortBy, wire::NameFormat};

    /// A fresh empty directory under the system temp dir.
    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rovsti-contacts-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |d| d.as_nanos())
        ));
        fs::create_dir_all(&dir).expect("temp dir");
        dir
    }

    #[test]
    fn nothing_saved_reads_as_defaults() {
        let dir = scratch("none");
        assert_eq!(load(&dir), ContactsPrefs::default());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn what_is_saved_comes_back() {
        let dir = scratch("roundtrip");
        let prefs = ContactsPrefs {
            name_format: NameFormat::LastFirst,
            sort_by: SortBy::LastName,
            ..ContactsPrefs::default()
        };
        save(&dir, prefs.clone()).expect("writes");
        assert_eq!(load(&dir), prefs);
        assert!(!dir.join("contacts-prefs.json.tmp").exists());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn save_to_survives_a_round_trip() {
        let dir = scratch("save-to");
        let prefs = ContactsPrefs {
            save_to: "com.google|a@b.com".to_owned(),
            ..ContactsPrefs::default()
        };
        save(&dir, prefs.clone()).expect("writes");
        assert_eq!(load(&dir).save_to, "com.google|a@b.com");
        assert_eq!(load(&dir), prefs);
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn a_second_save_replaces_the_first() {
        let dir = scratch("replace");
        save(&dir, ContactsPrefs::default()).expect("first");
        let second = ContactsPrefs {
            sort_by: SortBy::LastName,
            ..ContactsPrefs::default()
        };
        save(&dir, second.clone()).expect("second");
        assert_eq!(load(&dir), second);
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn a_damaged_file_reads_as_defaults() {
        let dir = scratch("damaged");
        fs::write(path(&dir), b"{ not json").expect("writes");
        assert_eq!(load(&dir), ContactsPrefs::default());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn an_oversized_file_is_ignored() {
        let dir = scratch("big");
        fs::write(path(&dir), vec![b' '; MAX_FILE_BYTES + 1]).expect("writes");
        assert_eq!(load(&dir), ContactsPrefs::default());
        fs::remove_dir_all(dir).ok();
    }
}
