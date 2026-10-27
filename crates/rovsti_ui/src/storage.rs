//! Where each app keeps its local databases. Everything is on-device; there
//! is no Rovsti server.

use std::path::PathBuf;

use gpui::{App, Task};

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
