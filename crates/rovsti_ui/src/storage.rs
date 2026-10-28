//! Where each app keeps its local databases. Everything is on-device; there
//! is no Rovsti server.

use std::{
    collections::BTreeMap,
    fs,
    io::Write,
    path::{Path, PathBuf},
};

use gpui::{App, Task};
use serde::{Deserialize, Serialize};

/// The app's private data directory, created if missing.
///
/// Android: the session's no-backup directory (excluded from cloud backup, so
/// WhatsApp keys never leave the phone). Must be called while the GPUI
/// `main` is running, i.e. from the `Application::run` callback.
/// Desktop: `$XDG_DATA_HOME/rovsti/<app>` (or `~/.local/share/rovsti/<app>`).
pub fn data_dir(app: &'static str, cx: &mut App) -> Task<anyhow_like::Result<PathBuf>> {
    #[cfg(target_os = "android")]
    {
        let _ = app;
        let request = gpui_android::current_platform().no_backup_directory();
        cx.foreground_executor().spawn(async move {
            let location = request.await.map_err(|e| e.to_string())?;
            location
                .path()
                .map(PathBuf::from)
                .ok_or_else(|| "no-backup directory has no filesystem path".to_owned())
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        let base = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .or_else(|| {
                std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share"))
            })
            .unwrap_or_else(std::env::temp_dir);
        let dir = base.join("rovsti").join(app);
        cx.background_executor().spawn(async move {
            std::fs::create_dir_all(&dir)
                .map_err(|e| format!("creating {}: {e}", dir.display()))?;
            Ok(dir)
        })
    }
}

/// Errors are surfaced to the UI as text; keep the dependency surface small.
pub mod anyhow_like {
    pub type Result<T> = std::result::Result<T, String>;
}

/// Small local preferences (FR-8, FR-27, FR-36): last tab, the time Recents
/// was last seen, and which Xiaomi settings the owner confirmed for which
/// app version. Written by Rust only, as `prefs.json` in the app data dir.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Prefs {
    /// The tab the owner left the app on; `None` before the first choice.
    pub last_tab: Option<u8>,
    pub missed_seen_ms: i64,
    /// Setup item key to the app version it was confirmed for.
    pub xiaomi_confirmed: BTreeMap<String, String>,
    pub app_version: String,
}

impl Prefs {
    /// The tab to reopen on: the stored one if it exists among `count` tabs,
    /// else `default` (first launch, or a corrupt value).
    pub fn last_tab_or(&self, count: usize, default: usize) -> usize {
        self.last_tab
            .map(usize::from)
            .filter(|tab| *tab < count)
            .unwrap_or(default)
    }

    pub const FILE: &'static str = "prefs.json";

    /// Reads `dir/prefs.json`. A missing, unreadable or corrupt file yields
    /// the defaults; a half-written file cannot exist because saves rename.
    pub fn load(dir: &Path) -> Self {
        fs::read(dir.join(Self::FILE))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    /// Atomic save: write a temp file in the same directory, sync it, then
    /// rename over the old file.
    pub fn save(&self, dir: &Path) -> std::io::Result<()> {
        fs::create_dir_all(dir)?;
        let tmp = dir.join(format!("{}.tmp", Self::FILE));
        let bytes = serde_json::to_vec(self).map_err(std::io::Error::other)?;
        let mut file = fs::File::create(&tmp)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&tmp, dir.join(Self::FILE))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rovsti-prefs-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn round_trips() {
        let dir = temp_dir("round");
        let mut prefs = Prefs {
            last_tab: Some(2),
            missed_seen_ms: 1_790_000_000_000,
            app_version: "0.1.0".into(),
            ..Prefs::default()
        };
        prefs
            .xiaomi_confirmed
            .insert("autostart".into(), "0.1.0".into());
        prefs.save(&dir).expect("save");
        assert_eq!(Prefs::load(&dir), prefs);
        assert!(
            !dir.join("prefs.json.tmp").exists(),
            "temp file is renamed away"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_file_is_default() {
        assert_eq!(Prefs::load(&temp_dir("missing")), Prefs::default());
    }

    #[test]
    fn corrupt_file_is_default() {
        let dir = temp_dir("corrupt");
        fs::create_dir_all(&dir).expect("dir");
        fs::write(dir.join(Prefs::FILE), b"{ not json").expect("write");
        assert_eq!(Prefs::load(&dir), Prefs::default());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn unknown_and_missing_fields_are_tolerated() {
        let dir = temp_dir("fields");
        fs::create_dir_all(&dir).expect("dir");
        fs::write(dir.join(Prefs::FILE), br#"{"last_tab":3,"future":true}"#).expect("write");
        let prefs = Prefs::load(&dir);
        assert_eq!(prefs.last_tab, Some(3));
        assert_eq!(prefs.missed_seen_ms, 0);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn save_replaces_the_previous_file() {
        let dir = temp_dir("replace");
        let mut prefs = Prefs::default();
        prefs.save(&dir).expect("first");
        prefs.last_tab = Some(1);
        prefs.save(&dir).expect("second");
        assert_eq!(Prefs::load(&dir).last_tab, Some(1));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn last_tab_falls_back_to_the_default() {
        let mut prefs = Prefs::default();
        assert_eq!(prefs.last_tab_or(4, 3), 3, "first launch");
        prefs.last_tab = Some(0);
        assert_eq!(prefs.last_tab_or(4, 3), 0, "Favorites is a real choice");
        prefs.last_tab = Some(2);
        assert_eq!(prefs.last_tab_or(4, 3), 2);
        prefs.last_tab = Some(9);
        assert_eq!(prefs.last_tab_or(4, 3), 3, "out of range");
    }

    #[test]
    fn a_corrupt_tab_value_loads_as_the_default() {
        let dir = temp_dir("badtab");
        fs::create_dir_all(&dir).expect("dir");
        fs::write(dir.join(Prefs::FILE), br#"{"last_tab":"banana"}"#).expect("write");
        assert_eq!(Prefs::load(&dir).last_tab_or(4, 3), 3);
        let _ = fs::remove_dir_all(&dir);
    }
}
