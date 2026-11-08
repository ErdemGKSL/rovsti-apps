//! Where each app keeps its local databases. Everything is on-device; there
//! is no Rovsti server.

use std::{
    path::{Path, PathBuf},
    sync::{
        Arc, Condvar, Mutex, PoisonError,
        atomic::{AtomicBool, Ordering},
    },
    thread::JoinHandle,
    time::{Duration, Instant},
};

use gpui::{App, Task};

/// The app's private data directory, created if missing.
///
/// Android: the session's no-backup directory (excluded from cloud backup, so
/// WhatsApp keys never leave the phone). Must be called while the GPUI
/// `main` is running, i.e. from the `Application::run` callback or a window's
/// build closure; to ask again later, keep a [`DataDirs`] made there.
/// Desktop: `$XDG_DATA_HOME/rovsti/<app>` (or `~/.local/share/rovsti/<app>`).
pub fn data_dir(app: &'static str, cx: &mut App) -> Task<anyhow_like::Result<PathBuf>> {
    DataDirs::new(app).request(cx)
}

/// A way to ask for the app's data directory again after the app has started.
///
/// On Android [`data_dir`] reads `gpui_android::current_platform()`, which panics anywhere
/// but inside the application's main function, so a request the platform refused ("Android
/// system service rejected the request") cannot simply be repeated from a later task. Make a
/// `DataDirs` where [`data_dir`] may be called (it keeps the platform handle) and call
/// [`DataDirs::request`] as often as needed from anywhere.
#[derive(Clone)]
pub struct DataDirs {
    #[cfg(target_os = "android")]
    ask: std::rc::Rc<
        dyn Fn() -> std::pin::Pin<
            Box<dyn std::future::Future<Output = anyhow_like::Result<PathBuf>>>,
        >,
    >,
    #[cfg(not(target_os = "android"))]
    app: &'static str,
}

impl DataDirs {
    /// Call it where [`data_dir`] may be called.
    pub fn new(app: &'static str) -> Self {
        #[cfg(target_os = "android")]
        {
            let _ = app;
            let platform = gpui_android::current_platform();
            Self {
                ask: std::rc::Rc::new(move || {
                    let request = platform.no_backup_directory();
                    Box::pin(async move {
                        let location = request.await.map_err(|e| e.to_string())?;
                        location
                            .path()
                            .map(PathBuf::from)
                            .ok_or_else(|| "no-backup directory has no filesystem path".to_owned())
                    })
                }),
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            Self { app }
        }
    }

    /// Asks for the directory (again).
    pub fn request(&self, cx: &mut App) -> Task<anyhow_like::Result<PathBuf>> {
        #[cfg(target_os = "android")]
        {
            cx.foreground_executor().spawn((self.ask)())
        }
        #[cfg(not(target_os = "android"))]
        {
            let base = std::env::var_os("XDG_DATA_HOME")
                .map(PathBuf::from)
                .or_else(|| {
                    std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share"))
                })
                .unwrap_or_else(std::env::temp_dir);
            let dir = base.join("rovsti").join(self.app);
            cx.background_executor().spawn(async move {
                std::fs::create_dir_all(&dir)
                    .map_err(|e| format!("creating {}: {e}", dir.display()))?;
                Ok(dir)
            })
        }
    }
}

/// Errors are surfaced to the UI as text; keep the dependency surface small.
pub mod anyhow_like {
    pub type Result<T> = std::result::Result<T, String>;
}

pub use rovsti_app_core::storage::{
    BackupRefusal, Loaded, Origin, PREFS_FILE, Prefs, backup_json, copy_aside, load_json,
    load_prefs, parse_backup, save_json, save_prefs,
};

/// Saves closer together than this are coalesced into one.
pub const SAVE_WINDOW: Duration = Duration::from_millis(250);

struct Pending {
    latest: Option<Prefs>,
    /// When the oldest unsaved change arrived.
    since: Option<Instant>,
    flush: bool,
    stop: bool,
    submitted: u64,
    written: u64,
    /// Copy a v1 file to `prefs.json.bak-v1` before the first write.
    backup_v1: bool,
}

struct Shared {
    state: Mutex<Pending>,
    wake: Condvar,
    failed: AtomicBool,
}

/// The single writer of `prefs.json` (AD-30). It keeps the newest snapshot,
/// writes at most once per [`SAVE_WINDOW`], writes on [`flush`](Self::flush)
/// (the app pausing) immediately, and is the only user of the `.tmp` file. A
/// failed save logs the error kind only and sets [`failed`](Self::failed)
/// until a later save succeeds; the owner's value stays in memory in `Backend`
/// and the next change saves the whole snapshot again (no retry loop).
pub struct PrefsWriter {
    shared: Arc<Shared>,
    worker: Option<JoinHandle<()>>,
}

impl PrefsWriter {
    pub fn new(dir: PathBuf, origin: Origin) -> Self {
        Self::with_window(dir, origin, SAVE_WINDOW)
    }

    fn with_window(dir: PathBuf, origin: Origin, window: Duration) -> Self {
        let shared = Arc::new(Shared {
            state: Mutex::new(Pending {
                latest: None,
                since: None,
                flush: false,
                stop: false,
                submitted: 0,
                written: 0,
                backup_v1: origin == Origin::UpgradedFromV1,
            }),
            wake: Condvar::new(),
            failed: AtomicBool::new(false),
        });
        let worker = {
            let shared = Arc::clone(&shared);
            std::thread::Builder::new()
                .name("prefs-writer".into())
                .spawn(move || worker_loop(&shared, &dir, window))
                .map_err(|error| log::warn!("prefs writer did not start: {error}"))
                .ok()
        };
        Self { shared, worker }
    }

    /// Hands over the newest snapshot; older unsaved ones are replaced.
    pub fn submit(&self, prefs: Prefs) {
        let mut state = self
            .shared
            .state
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        state.latest = Some(prefs);
        state.since.get_or_insert_with(Instant::now);
        state.submitted += 1;
        self.shared.wake.notify_all();
    }

    /// Writes any unsaved snapshot now and waits (briefly) for it.
    pub fn flush(&self) {
        let mut state = self
            .shared
            .state
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        if state.latest.is_none() {
            return;
        }
        state.flush = true;
        self.shared.wake.notify_all();
        let deadline = Instant::now() + Duration::from_millis(800);
        while state.written < state.submitted {
            let Some(left) = deadline.checked_duration_since(Instant::now()) else {
                break;
            };
            let (next, _) = self
                .shared
                .wake
                .wait_timeout(state, left)
                .unwrap_or_else(PoisonError::into_inner);
            state = next;
        }
    }

    /// Whether the last save failed ("Couldn't save setting").
    pub fn failed(&self) -> bool {
        self.shared.failed.load(Ordering::Relaxed)
    }
}

impl Drop for PrefsWriter {
    fn drop(&mut self) {
        {
            let mut state = self
                .shared
                .state
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            state.stop = true;
            state.flush = true;
            self.shared.wake.notify_all();
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

fn worker_loop(shared: &Shared, dir: &Path, window: Duration) {
    let mut state = shared.state.lock().unwrap_or_else(PoisonError::into_inner);
    loop {
        if state.latest.is_none() {
            if state.stop {
                return;
            }
            state = shared
                .wake
                .wait(state)
                .unwrap_or_else(PoisonError::into_inner);
            continue;
        }
        // A snapshot waits for the end of its window unless a flush or stop
        // asks for it now; later submissions during the wait replace it.
        if !(state.flush || state.stop) {
            let due = state
                .since
                .map_or(window, |at| window.saturating_sub(at.elapsed()));
            if !due.is_zero() {
                state = shared
                    .wake
                    .wait_timeout(state, due)
                    .unwrap_or_else(PoisonError::into_inner)
                    .0;
                continue;
            }
        }
        let Some(prefs) = state.latest.take() else {
            continue;
        };
        let target = state.submitted;
        let backup = std::mem::take(&mut state.backup_v1);
        state.since = None;
        state.flush = false;
        drop(state);

        if backup {
            copy_aside(dir, "prefs.json.bak-v1", false);
        }
        let result = save_prefs(&prefs, dir);
        match &result {
            Ok(()) => shared.failed.store(false, Ordering::Relaxed),
            Err(error) => {
                // The kind only: the message may name a path, never content.
                log::warn!("saving preferences failed: {:?}", error.kind());
                shared.failed.store(true, Ordering::Relaxed);
            }
        }
        state = shared.state.lock().unwrap_or_else(PoisonError::into_inner);
        state.written = state.written.max(target);
        shared.wake.notify_all();
        if state.stop && state.latest.is_none() {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, path::PathBuf};

    fn write(dir: &Path, text: &str) {
        fs::create_dir_all(dir).expect("dir");
        fs::write(dir.join(PREFS_FILE), text).expect("write");
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rovsti-prefs-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    // ---- PrefsWriter ---------------------------------------------------

    fn with_tab(tab: u8) -> Prefs {
        let mut p = Prefs::default();
        p.ui.last_tab = Some(tab);
        p
    }

    #[test]
    fn the_writer_coalesces_a_burst_into_one_save_of_the_latest() {
        let dir = temp_dir("burst");
        let writer =
            PrefsWriter::with_window(dir.clone(), Origin::Missing, Duration::from_millis(60));
        for tab in 0..20 {
            writer.submit(with_tab(tab));
        }
        std::thread::sleep(Duration::from_millis(10));
        assert!(
            !dir.join(PREFS_FILE).exists(),
            "nothing is written inside the window"
        );
        writer.flush();
        assert_eq!(load_prefs(&dir).prefs.ui.last_tab, Some(19));
        assert!(!writer.failed());
        drop(writer);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_writer_saves_after_the_window_without_a_flush() {
        let dir = temp_dir("window");
        let writer =
            PrefsWriter::with_window(dir.clone(), Origin::Missing, Duration::from_millis(30));
        writer.submit(with_tab(1));
        let start = Instant::now();
        while !dir.join(PREFS_FILE).exists() && start.elapsed() < Duration::from_secs(3) {
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(load_prefs(&dir).prefs.ui.last_tab, Some(1));
        drop(writer);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn dropping_the_writer_flushes_what_is_pending() {
        let dir = temp_dir("drop");
        let writer =
            PrefsWriter::with_window(dir.clone(), Origin::Missing, Duration::from_secs(30));
        writer.submit(with_tab(3));
        drop(writer);
        assert_eq!(load_prefs(&dir).prefs.ui.last_tab, Some(3));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_first_v2_save_keeps_the_v1_file() {
        let dir = temp_dir("bak");
        let v1 = r#"{"last_tab":2,"app_version":"0.1.0"}"#;
        write(&dir, v1);
        let loaded = load_prefs(&dir);
        let writer =
            PrefsWriter::with_window(dir.clone(), loaded.origin, Duration::from_millis(10));
        let mut next = loaded.prefs;
        next.settings.show_frequent = false;
        writer.submit(next);
        writer.flush();
        assert_eq!(
            fs::read_to_string(dir.join("prefs.json.bak-v1")).expect("bak"),
            v1
        );
        let now = load_prefs(&dir);
        assert_eq!(now.origin, Origin::Current);
        assert!(!now.prefs.settings.show_frequent);
        // A second save does not overwrite the backup.
        let mut again = now.prefs;
        again.ui.last_tab = Some(0);
        writer.submit(again);
        writer.flush();
        assert_eq!(
            fs::read_to_string(dir.join("prefs.json.bak-v1")).expect("bak"),
            v1
        );
        drop(writer);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_failed_save_sets_the_flag_and_a_later_one_clears_it() {
        let dir = temp_dir("fail");
        // A file where the directory should be makes every save fail.
        fs::create_dir_all(dir.parent().expect("parent")).expect("tmp");
        fs::write(&dir, b"not a dir").expect("blocker");
        let writer =
            PrefsWriter::with_window(dir.clone(), Origin::Missing, Duration::from_millis(5));
        writer.submit(with_tab(1));
        writer.flush();
        assert!(writer.failed());
        // Fix the path; the kept value is retried with the next submit.
        fs::remove_file(&dir).expect("unblock");
        writer.submit(with_tab(2));
        writer.flush();
        assert!(!writer.failed());
        assert_eq!(load_prefs(&dir).prefs.ui.last_tab, Some(2));
        drop(writer);
        let _ = fs::remove_dir_all(&dir);
    }
}
