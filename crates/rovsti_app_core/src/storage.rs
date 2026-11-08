//! Local JSON storage shared by the apps: atomic saves, tolerant loads, the
//! preferences file and the backup file. The data directory is always a
//! `&Path` argument.

use std::{fs, io::Write, path::Path};

use rovsti_core::settings::{
    BackupFile, ImportError, ImportPreview, PrefsV1, PrefsV2, SCHEMA_VERSION, plausible_backup_size,
};
use serde::{Deserialize, Serialize, de::DeserializeOwned};

/// The app's preferences (FR-8, FR-27, FR-36, AD-30): the v2 schema from
/// `rovsti_core::settings`. Written by Rust only, as `prefs.json` in the app
/// data dir.
pub type Prefs = PrefsV2;

/// File name of the preferences.
pub const PREFS_FILE: &str = "prefs.json";

// ---- backup file (story 16.6) ---------------------------------------------------

/// Why a picked file is not a usable backup, in the owner's words.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BackupRefusal {
    /// Over 256 KB, not JSON, or another kind of file.
    NotABackup,
    /// Written by a newer build of the app.
    Newer,
}

impl BackupRefusal {
    /// The inline red row's text.
    pub fn message(self) -> &'static str {
        match self {
            Self::NotABackup => "This isn't a Rovsti Phone backup.",
            Self::Newer => "This backup is from a newer version of Rovsti Phone.",
        }
    }
}

/// The text of a backup file for the current preferences.
pub fn backup_json(prefs: &Prefs, created_ms: i64, app_version: &str) -> String {
    let file = BackupFile::from_prefs(prefs, created_ms, app_version);
    serde_json::to_string_pretty(&file).unwrap_or_default()
}

/// Parses picked text outside core: the size is checked first, then the JSON,
/// then the kind and version, and the result is what an import would change.
pub fn parse_backup(text: &str, current: &Prefs) -> Result<ImportPreview, BackupRefusal> {
    if !plausible_backup_size(text.len()) {
        return Err(BackupRefusal::NotABackup);
    }
    let file: BackupFile = serde_json::from_str(text).map_err(|_| BackupRefusal::NotABackup)?;
    ImportPreview::from(&file, current).map_err(|error| match error {
        ImportError::Newer => BackupRefusal::Newer,
        ImportError::NotABackup => BackupRefusal::NotABackup,
    })
}

/// Reads only the version of a prefs file; a missing one counts as 1.
#[derive(Deserialize)]
struct Probe {
    #[serde(default)]
    schema_version: Option<u32>,
}

/// What `load` found on disk.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Origin {
    /// No file (first launch) or it could not be read: defaults.
    Missing,
    /// A v2 file.
    Current,
    /// A v0.1 file converted by `From<PrefsV1>`; the first save keeps a copy.
    UpgradedFromV1,
    /// A file from a newer build, read best effort and copied to
    /// `prefs.json.bak-v{n}`.
    Newer(u32),
    /// Not valid JSON for any version: defaults, the file copied to
    /// `prefs.json.corrupt`.
    Corrupt,
}

/// The preferences and how they were found.
#[derive(Clone, Debug)]
pub struct Loaded {
    pub prefs: Prefs,
    pub origin: Origin,
}

pub fn copy_aside(dir: &Path, name: &str, overwrite: bool) {
    let target = dir.join(name);
    if !overwrite && target.exists() {
        return;
    }
    if let Err(error) = fs::copy(dir.join(PREFS_FILE), &target) {
        log::warn!("copying {PREFS_FILE} to {name} failed: {:?}", error.kind());
    }
}

/// Reads `dir/prefs.json`, converting older versions. Never fails: a missing,
/// unreadable or corrupt file yields defaults (a corrupt one is first copied
/// to `prefs.json.corrupt`), and a half-written file cannot exist because
/// saves rename.
pub fn load_prefs(dir: &Path) -> Loaded {
    let Ok(bytes) = fs::read(dir.join(PREFS_FILE)) else {
        return Loaded {
            prefs: Prefs::default(),
            origin: Origin::Missing,
        };
    };
    let corrupt = || {
        copy_aside(dir, "prefs.json.corrupt", true);
        Loaded {
            prefs: Prefs::default(),
            origin: Origin::Corrupt,
        }
    };
    let Ok(probe) = serde_json::from_slice::<Probe>(&bytes) else {
        return corrupt();
    };
    let version = probe.schema_version.unwrap_or(1).max(1);
    let (parsed, origin) = match version {
        1 => (
            serde_json::from_slice::<PrefsV1>(&bytes).map(Prefs::from),
            Origin::UpgradedFromV1,
        ),
        SCHEMA_VERSION => (serde_json::from_slice::<Prefs>(&bytes), Origin::Current),
        newer => {
            copy_aside(dir, &format!("prefs.json.bak-v{newer}"), false);
            (
                serde_json::from_slice::<Prefs>(&bytes),
                Origin::Newer(newer),
            )
        }
    };
    match parsed {
        Ok(mut prefs) => {
            prefs.validate();
            Loaded { prefs, origin }
        }
        Err(_) => corrupt(),
    }
}

/// Atomic save: write a temp file in the same directory, sync it, then
/// rename over the old file.
pub fn save_prefs(prefs: &Prefs, dir: &Path) -> std::io::Result<()> {
    save_json(dir, PREFS_FILE, prefs)
}

/// Atomically writes `value` as JSON to `dir/name` (temp file, `sync_all`,
/// rename), creating `dir` if needed. A crash leaves the old file or the new
/// one, never half of each.
pub fn save_json<T: Serialize>(dir: &Path, name: &str, value: &T) -> std::io::Result<()> {
    fs::create_dir_all(dir)?;
    let tmp = dir.join(format!("{name}.tmp"));
    let bytes = serde_json::to_vec(value).map_err(std::io::Error::other)?;
    let _ = fs::remove_file(&tmp); // a stale temp file would keep its old mode
    let mut file = private_create(&tmp)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&tmp, dir.join(name))
}

/// Creates `path` readable by the owner only: saved JSON can hold a connect
/// key, which is a bearer secret.
fn private_create(path: &Path) -> std::io::Result<fs::File> {
    let mut options = fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt as _;
        options.mode(0o600);
    }
    options.open(path)
}

/// Reads `dir/name` as JSON. A missing, unreadable or corrupt file is `None`:
/// callers start from their defaults.
pub fn load_json<T: DeserializeOwned>(dir: &Path, name: &str) -> Option<T> {
    let bytes = fs::read(dir.join(name)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::settings::DEFAULT_REPLIES;
    use rovsti_core::wire::HapticsLevel;
    use std::path::PathBuf;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rovsti-prefs-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn write(dir: &Path, text: &str) {
        fs::create_dir_all(dir).expect("dir");
        fs::write(dir.join(PREFS_FILE), text).expect("write");
    }

    #[test]
    fn json_round_trips_and_a_corrupt_file_is_none() {
        let dir = temp_dir("json");
        let value = vec![("a".to_owned(), 1_u32), ("b".to_owned(), 2)];
        save_json(&dir, "x.json", &value).expect("save");
        assert_eq!(load_json::<Vec<(String, u32)>>(&dir, "x.json"), Some(value));
        assert!(!dir.join("x.json.tmp").exists(), "the temp file is renamed");
        assert_eq!(load_json::<Vec<u32>>(&dir, "missing.json"), None);
        fs::write(dir.join("bad.json"), "{not json").expect("write");
        assert_eq!(load_json::<Vec<u32>>(&dir, "bad.json"), None);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn round_trips() {
        let dir = temp_dir("round");
        let mut prefs = Prefs::default();
        prefs.ui.last_tab = Some(2);
        prefs.ui.missed_seen_ms = 1_790_000_000_000;
        prefs.ui.app_version = "0.1.0".into();
        prefs
            .ui
            .xiaomi_confirmed
            .insert("autostart".into(), "0.1.0".into());
        prefs.settings.haptics = HapticsLevel::Light;
        save_prefs(&prefs, &dir).expect("save");
        let loaded = load_prefs(&dir);
        assert_eq!(loaded.prefs, prefs);
        assert_eq!(loaded.origin, Origin::Current);
        assert!(
            !dir.join("prefs.json.tmp").exists(),
            "temp file is renamed away"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_file_is_default() {
        let loaded = load_prefs(&temp_dir("missing"));
        assert_eq!(loaded.prefs, Prefs::default());
        assert_eq!(loaded.origin, Origin::Missing);
    }

    #[test]
    fn a_v1_file_without_schema_version_is_converted() {
        let dir = temp_dir("v1");
        write(
            &dir,
            r#"{"last_tab":2,"missed_seen_ms":5,"xiaomi_confirmed":{"autostart":"0.1.0"},"app_version":"0.1.0"}"#,
        );
        let loaded = load_prefs(&dir);
        assert_eq!(loaded.origin, Origin::UpgradedFromV1);
        assert_eq!(loaded.prefs.ui.last_tab, Some(2));
        assert_eq!(loaded.prefs.ui.missed_seen_ms, 5);
        assert_eq!(loaded.prefs.ui.xiaomi_confirmed["autostart"], "0.1.0");
        assert_eq!(loaded.prefs.quick_replies, DEFAULT_REPLIES);
        // Loading alone leaves the file as v0.1 wrote it.
        assert!(!dir.join("prefs.json.bak-v1").exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_explicit_version_one_counts_as_v1() {
        let dir = temp_dir("v1explicit");
        write(&dir, r#"{"schema_version":1,"last_tab":1}"#);
        let loaded = load_prefs(&dir);
        assert_eq!(loaded.origin, Origin::UpgradedFromV1);
        assert_eq!(loaded.prefs.ui.last_tab, Some(1));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_file_loads_defaults_after_a_copy() {
        let dir = temp_dir("corrupt");
        write(&dir, "{ not json");
        let loaded = load_prefs(&dir);
        assert_eq!(loaded.prefs, Prefs::default());
        assert_eq!(loaded.origin, Origin::Corrupt);
        assert_eq!(
            fs::read_to_string(dir.join("prefs.json.corrupt")).expect("copy"),
            "{ not json"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_wrongly_typed_field_is_corrupt_not_a_panic() {
        let dir = temp_dir("badtab");
        write(&dir, r#"{"last_tab":"banana"}"#);
        let loaded = load_prefs(&dir);
        assert_eq!(loaded.origin, Origin::Corrupt);
        assert_eq!(loaded.prefs.last_tab_or(4, 3), 3);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_newer_file_loads_best_effort_after_a_copy() {
        let dir = temp_dir("newer");
        let text =
            r#"{"schema_version":3,"ui":{"last_tab":1},"settings":{"proximity":false},"x":1}"#;
        write(&dir, text);
        let loaded = load_prefs(&dir);
        assert_eq!(loaded.origin, Origin::Newer(3));
        assert_eq!(loaded.prefs.ui.last_tab, Some(1));
        assert!(!loaded.prefs.settings.proximity);
        assert_eq!(loaded.prefs.schema_version, 2);
        assert_eq!(
            fs::read_to_string(dir.join("prefs.json.bak-v3")).expect("copy"),
            text
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn unknown_fields_are_tolerated() {
        let dir = temp_dir("fields");
        write(
            &dir,
            r#"{"schema_version":2,"ui":{"last_tab":3},"future":true}"#,
        );
        let prefs = load_prefs(&dir).prefs;
        assert_eq!(prefs.ui.last_tab, Some(3));
        assert_eq!(prefs.ui.missed_seen_ms, 0);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn load_clamps_invalid_content() {
        let dir = temp_dir("clamp");
        write(
            &dir,
            r#"{"schema_version":2,"quick_replies":["a","b","c","d","e","f","g","h"],"favorites_order":["x","x"]}"#,
        );
        let prefs = load_prefs(&dir).prefs;
        assert_eq!(prefs.quick_replies.len(), 6);
        assert_eq!(prefs.favorites_order, ["x"]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn save_replaces_the_previous_file() {
        let dir = temp_dir("replace");
        let mut prefs = Prefs::default();
        save_prefs(&prefs, &dir).expect("first");
        prefs.ui.last_tab = Some(1);
        save_prefs(&prefs, &dir).expect("second");
        assert_eq!(load_prefs(&dir).prefs.ui.last_tab, Some(1));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn last_tab_falls_back_to_the_default() {
        let mut prefs = Prefs::default();
        assert_eq!(prefs.last_tab_or(4, 3), 3, "first launch");
        prefs.ui.last_tab = Some(0);
        assert_eq!(prefs.last_tab_or(4, 3), 0, "Favorites is a real choice");
        prefs.ui.last_tab = Some(2);
        assert_eq!(prefs.last_tab_or(4, 3), 2);
        prefs.ui.last_tab = Some(9);
        assert_eq!(prefs.last_tab_or(4, 3), 3, "out of range");
    }

    #[test]
    fn a_backup_round_trips_through_export_wipe_import() {
        use rovsti_core::settings::SpeedDialEntry;
        let mut model = Prefs::default();
        model.settings.haptics = rovsti_core::wire::HapticsLevel::Light;
        model.settings.flip_to_silence = true;
        model.settings.keypad_tones = Some(false);
        model.quick_replies = vec!["Call you back".into(), "In a meeting".into()];
        model.favorites_order = vec!["k1".into(), "k2".into()];
        model.speed_dial.insert(
            2,
            SpeedDialEntry {
                lookup_key: "k1".into(),
                number_raw: "+905551112233".into(),
                name: "Ayse".into(),
            },
        );
        let text = backup_json(&model, 1_700_000_000_000, "0.2.0");
        // Wipe: a fresh install, with its own ui state kept.
        let mut wiped = Prefs::default();
        wiped.ui.last_tab = Some(2);
        let preview = parse_backup(&text, &wiped).expect("preview");
        assert_eq!(preview.speed_dial, 1);
        assert_eq!(preview.favorites, 2);
        assert_eq!(preview.quick_replies, 2);
        assert_eq!(preview.settings_changed, 3);
        preview.apply_to(&mut wiped);
        assert_eq!(wiped.settings, model.settings);
        assert_eq!(wiped.quick_replies, model.quick_replies);
        assert_eq!(wiped.speed_dial, model.speed_dial);
        assert_eq!(wiped.favorites_order, model.favorites_order);
        assert_eq!(wiped.ui.last_tab, Some(2));
    }

    #[test]
    fn foreign_oversized_and_newer_files_are_refused() {
        let p = Prefs::default();
        assert_eq!(
            parse_backup("", &p).expect_err("refused"),
            BackupRefusal::NotABackup
        );
        assert_eq!(
            parse_backup("[1,2]", &p).expect_err("refused"),
            BackupRefusal::NotABackup
        );
        assert_eq!(
            parse_backup(r#"{"v":1,"kind":"other"}"#, &p).expect_err("refused"),
            BackupRefusal::NotABackup
        );
        assert_eq!(
            parse_backup(r#"{"v":9,"kind":"rovsti-phone-backup"}"#, &p).expect_err("refused"),
            BackupRefusal::Newer
        );
        let big = format!(
            r#"{{"v":1,"kind":"rovsti-phone-backup","pad":"{}"}}"#,
            "x".repeat(300_000)
        );
        assert_eq!(
            parse_backup(&big, &p).expect_err("refused"),
            BackupRefusal::NotABackup
        );
    }
}
