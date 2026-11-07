//! Everything the Stops UI does that touches the disk or the Android seam:
//! loading and saving the app's files, the checks before a trip, starting and
//! stopping the trip, the alarm buttons, and the desktop simulation.
//!
//! All functions here block, so the UI calls them on the background executor.
//! The decisions inside (what is missing before a trip, how a trip is built,
//! how a reply is read) are plain functions with unit tests.

use std::{path::Path, sync::Arc};

use rovsti_android::{
    Error as AndroidError, permissions,
    permissions::{Access, Group},
    trip::{self, AlarmAction},
    xiaomi,
};
use rovsti_geo::{
    ActiveTrip, AlarmPlan, Diameter, DiameterError, Endpoint, Feed, FixIn, History, NoticePrefs,
    Outcome, SavedStops, StopsPrefs, saved_stops::load_saved_stops,
};
use rovsti_ui::storage;
use serde_json::json;

use crate::{
    picker::Ends,
    store::{self, now_ms},
};

/// The speed of the simulated vehicle, metres per second.
const SIM_SPEED_MPS: f64 = 50.0;
/// How far left of the line a simulated trip begins, metres.
const SIM_START_M: f64 = 2_000.0;
/// How far right of the line it parks, metres.
const SIM_END_M: f64 = 200.0;

/// Choices for the next trip besides A and B, kept so a restart does not undo
/// them (a switched-off arrival text must stay off).
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct NextTrip {
    /// Text the chat when arriving.
    #[serde(default = "notice_on_default")]
    pub notice_on: bool,
}

fn notice_on_default() -> bool {
    true
}

impl Default for NextTrip {
    fn default() -> Self {
        Self { notice_on: true }
    }
}

/// What the app keeps on disk, read once at launch.
#[derive(Default)]
pub struct Data {
    pub prefs: StopsPrefs,
    pub saved: SavedStops,
    pub history: History,
    pub trip: Option<ActiveTrip>,
    pub draft: Ends,
    pub next: NextTrip,
    pub gtfs: Option<Arc<Feed>>,
}

/// Reads every file. A missing or corrupt file gives its default.
pub fn load(dir: &Path) -> Data {
    Data {
        prefs: storage::load_json(dir, store::PREFS_FILE).unwrap_or_default(),
        saved: load_saved(dir),
        history: storage::load_json(dir, store::HISTORY_FILE).unwrap_or_default(),
        trip: store::load_trip(dir),
        draft: storage::load_json(dir, store::DRAFT_FILE).unwrap_or_default(),
        next: storage::load_json(dir, store::NEXT_TRIP_FILE).unwrap_or_default(),
        gtfs: storage::load_json::<Feed>(dir, store::GTFS_FILE).map(|feed| {
            // Build the lookup index here, off the UI thread, not on the first search.
            let _ = feed.patterns_through(0);
            Arc::new(feed)
        }),
    }
}

/// Reads the saved stops. Old single-point data (or anything unreadable) is
/// discarded, and the file is overwritten with an empty v2 list right away.
fn load_saved(dir: &Path) -> SavedStops {
    let text = std::fs::read_to_string(dir.join(store::SAVED_FILE)).ok();
    let loaded = load_saved_stops(text.as_deref());
    if loaded.discarded {
        log::warn!("discarding old saved data; starting with no saved stops");
        log_save(storage::save_json(
            dir,
            store::SAVED_FILE,
            &SavedStops::default(),
        ));
    }
    loaded.stops
}

/// The trip file and the history, which the headless handler changes behind
/// the UI's back.
pub fn reload_trip(dir: &Path) -> (Option<ActiveTrip>, History) {
    let _guard = store::lock();
    (
        store::load_trip(dir),
        storage::load_json(dir, store::HISTORY_FILE).unwrap_or_default(),
    )
}

pub fn save_prefs(dir: &Path, prefs: &StopsPrefs) {
    log_save(storage::save_json(dir, store::PREFS_FILE, prefs));
}

pub fn save_stops(dir: &Path, saved: &SavedStops) {
    log_save(storage::save_json(dir, store::SAVED_FILE, saved));
}

/// Writes the A and B being picked.
pub fn save_draft(dir: &Path, ends: &Ends) {
    log_save(storage::save_json(dir, store::DRAFT_FILE, ends));
}

/// Writes the next trip's choices.
pub fn save_next_trip(dir: &Path, next: &NextTrip) {
    log_save(storage::save_json(dir, store::NEXT_TRIP_FILE, next));
}

/// Empties the history. Holds the trip lock like `store::end_trip`, which
/// appends to the same file from the service's thread.
pub fn clear_history(dir: &Path) {
    let _guard = store::lock();
    log_save(storage::save_json(
        dir,
        store::HISTORY_FILE,
        &History::default(),
    ));
}

/// Writes `history` back, for Undo after Clear. Holds the trip lock like
/// `clear_history`.
pub fn save_history(dir: &Path, history: &History) {
    let _guard = store::lock();
    log_save(storage::save_json(dir, store::HISTORY_FILE, history));
}

fn log_save(result: std::io::Result<()>) {
    if let Err(error) = result {
        log::warn!("could not save a Stops file: {error}");
    }
}

// ---- before a trip -----------------------------------------------------------

/// What Android says about the three things a trip needs. `None` means the
/// question has no answer here (desktop), which never blocks.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Preflight {
    pub location: Option<Access>,
    pub notifications: Option<Access>,
    pub full_screen: Option<bool>,
    /// "Allow all the time" location: lets a restarted service keep tracking.
    /// Recommended, never a blocker.
    pub background_location: Option<Access>,
    /// The app may run in the background without battery limits.
    pub unrestricted_battery: Option<bool>,
    /// The system lets the app set exact alarms (snooze, watchdog).
    pub exact_alarms: Option<bool>,
    /// MIUI or HyperOS: the autostart page matters.
    pub xiaomi_os: bool,
}

/// The one thing to do next before a trip can start.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    RequestLocation,
    LocationInSettings,
    RequestNotifications,
    NotificationsInSettings,
}

impl Step {
    /// What to tell the user while the system dialog or page opens.
    pub fn message(self) -> &'static str {
        match self {
            Self::RequestLocation => "Allow location so Stops can see where you are.",
            Self::LocationInSettings => "Location is off for Stops. Turn it on in Settings.",
            Self::RequestNotifications => "Allow notifications so the alarm can wake you.",
            Self::NotificationsInSettings => {
                "Notifications are off for Stops. Turn them on in Settings."
            }
        }
    }

    /// Opens the dialog or the settings page. Blocking (JNI).
    pub fn perform(self) {
        let result = match self {
            Self::RequestLocation => permissions::request(Group::Location),
            Self::RequestNotifications => permissions::request(Group::Notifications),
            Self::LocationInSettings | Self::NotificationsInSettings => {
                permissions::open_app_details()
            }
        };
        if let Err(error) = result {
            log::warn!("could not open the permission prompt: {error}");
        }
    }
}

fn answer<T>(result: Result<T, AndroidError>) -> Option<T> {
    match result {
        Ok(value) => Some(value),
        Err(AndroidError::Unsupported) => None,
        Err(error) => {
            log::warn!("permission check failed: {error}");
            None
        }
    }
}

impl Preflight {
    /// Asks Android. Blocking (JNI).
    pub fn gather() -> Self {
        Self {
            location: answer(permissions::state(Group::Location)),
            notifications: answer(permissions::state(Group::Notifications)),
            full_screen: answer(permissions::can_use_full_screen_intent()),
            background_location: answer(permissions::state(Group::BackgroundLocation)),
            unrestricted_battery: answer(xiaomi::is_ignoring_battery_optimizations()),
            exact_alarms: answer(trip::can_schedule_exact_alarms()),
            xiaomi_os: xiaomi::detect().is_some(),
        }
    }

    /// The first blocker, location before notifications.
    pub fn next_step(&self) -> Option<Step> {
        match self.location {
            Some(Access::Denied) => return Some(Step::RequestLocation),
            Some(Access::PermanentlyDenied) => return Some(Step::LocationInSettings),
            _ => {}
        }
        match self.notifications {
            Some(Access::Denied) => Some(Step::RequestNotifications),
            Some(Access::PermanentlyDenied) => Some(Step::NotificationsInSettings),
            _ => None,
        }
    }

    /// The alarm cannot take over a locked screen: the row that links to the
    /// system page. Not a blocker, the alarm still sounds.
    pub fn full_screen_missing(&self) -> bool {
        self.full_screen == Some(false)
    }
}

/// The hint under the full-screen row: only when the grant is known to be
/// missing. Unknown (`None`) and granted show nothing.
pub fn full_screen_notice(pre: &Preflight) -> Option<&'static str> {
    pre.full_screen_missing()
        .then_some("Allow full-screen alerts so it shows over the lock screen.")
}

/// The hint under the background-location row: only once foreground location
/// is granted and "all the time" is known to be missing.
pub fn background_location_notice(pre: &Preflight) -> Option<&'static str> {
    let missing = matches!(
        pre.background_location,
        Some(Access::Denied | Access::PermanentlyDenied)
    );
    (pre.location == Some(Access::Granted) && missing)
        .then_some("Allow location all the time so tracking survives Android closing Stops.")
}

/// Asks for "Allow all the time", or opens the app's page when Android will no
/// longer show the dialog. Blocking (JNI).
pub fn request_background_location(pre: &Preflight) {
    let result = match pre.background_location {
        Some(Access::Denied) => permissions::request(Group::BackgroundLocation),
        Some(Access::PermanentlyDenied) => permissions::open_app_details(),
        _ => return,
    };
    if let Err(error) = result {
        log::warn!("could not open the background location prompt: {error}");
    }
}

/// Opens the system page where full-screen alarms are allowed.
pub fn open_full_screen_settings() {
    if let Err(error) = permissions::open_full_screen_intent_settings() {
        log::warn!("could not open the full-screen settings: {error}");
    }
}

// ---- start and stop ------------------------------------------------------------

/// A trip's session as it is written to `trip.json`. `notice` is the
/// arriving-soon message; it only applies when it names a chat. Fails when A
/// and B cannot form a diameter.
pub fn build_trip(
    id: u64,
    a: Endpoint,
    b: Endpoint,
    started_ms: i64,
    notice: Option<&NoticePrefs>,
) -> Result<ActiveTrip, DiameterError> {
    let mut trip = ActiveTrip::new(id, a, b, started_ms)?;
    trip.notice = notice
        .filter(|n| !n.chat.is_empty())
        .map(NoticePrefs::to_notice_state);
    Ok(trip)
}

/// What the foreground service is told at start: the data directory, the
/// first notification, and a short first interval so the card fills quickly.
pub fn start_spec(dir: &Path, label: &str) -> String {
    json!({
        "dir": dir.to_string_lossy(),
        "title": label,
        "text": "Waiting for a location fix",
        "next_fix_ms": 5_000,
        "high_accuracy": true,
        "alarm": AlarmPlan::default(),
    })
    .to_string()
}

/// Writes `trip.json` and starts the service. A trip already running is
/// ended first (recorded as cancelled). On failure the new trip file is
/// removed again, so a refused start leaves nothing behind.
pub fn start_trip(dir: &Path, trip: &ActiveTrip) -> Result<(), String> {
    {
        let _guard = store::lock();
        // The pill gets a sentence; the detail goes to the log.
        let storage_failed = |error: std::io::Error| {
            log::warn!("could not write the trip files: {error}");
            "Could not save the trip on the phone. Free some space and try again.".to_owned()
        };
        if store::load_trip(dir).is_some() {
            let _ = trip::stop();
            store::end_trip(dir, Outcome::Cancelled, now_ms()).map_err(storage_failed)?;
        }
        store::save_trip(dir, trip).map_err(storage_failed)?;
    }
    let started = match trip::start(&start_spec(dir, &trip.label())) {
        Ok(true) | Err(AndroidError::Unsupported) => Ok(()),
        Ok(false) => Err("Android did not start the tracking service.".to_owned()),
        Err(error) => {
            log::warn!("could not start the trip service: {error}");
            Err("Could not start tracking. Try again.".to_owned())
        }
    };
    if started.is_err() {
        let _guard = store::lock();
        let _ = std::fs::remove_file(dir.join(store::TRIP_FILE));
    }
    started
}

/// Starts the service again for a trip left in `trip.json` (the app was
/// killed and the service with it). `true` when tracking runs afterwards.
pub fn resume_trip(dir: &Path) -> bool {
    match trip::is_running() {
        Ok(true) | Err(AndroidError::Unsupported) => return true,
        Ok(false) => {}
        Err(error) => {
            log::warn!("could not ask whether the trip runs: {error}");
            return true;
        }
    }
    let _guard = store::lock();
    let Some(trip) = store::load_trip(dir) else {
        return false;
    };
    matches!(trip::start(&start_spec(dir, &trip.label())), Ok(true))
}

/// Stops the service and records the trip as cancelled.
pub fn stop_trip(dir: &Path) {
    match trip::stop() {
        Ok(_) | Err(AndroidError::Unsupported) => {}
        Err(error) => log::warn!("could not stop the tracking service: {error}"),
    }
    let _guard = store::lock();
    if let Err(error) = store::end_trip(dir, Outcome::Cancelled, now_ms()) {
        log::warn!("could not end the trip: {error}");
    }
}

// ---- the alarm buttons ---------------------------------------------------------

/// What pressing Dismiss or Snooze led to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AlarmReply {
    /// Android handles it and reports with a `trip_alarm` event.
    Sent,
    Snoozed {
        at_ms: i64,
    },
    Dismissed,
    Ended,
    Failed,
}

/// Reads the headless `trip_alarm` answer.
pub fn parse_alarm_reply(json: &str) -> AlarmReply {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(json) else {
        return AlarmReply::Failed;
    };
    if value.get("dismissed").and_then(|v| v.as_bool()) == Some(true) {
        return AlarmReply::Dismissed;
    }
    if let Some(at_ms) = value.get("ring_at_ms").and_then(|v| v.as_i64()) {
        return AlarmReply::Snoozed { at_ms };
    }
    if value.get("state").and_then(|v| v.as_str()) == Some("ended") {
        return AlarmReply::Ended;
    }
    AlarmReply::Failed
}

/// Presses a button of the alarm. On a phone, Kotlin silences the sound and
/// answers with an event. Elsewhere the same decision runs here, in process.
pub fn alarm(dir: &Path, action: AlarmAction) -> AlarmReply {
    match trip::alarm(action) {
        Ok(true) => AlarmReply::Sent,
        Ok(false) => AlarmReply::Failed,
        Err(AndroidError::Unsupported) => {
            let request = json!({ "dir": dir.to_string_lossy(), "action": action.key() });
            parse_alarm_reply(&crate::headless::handle("trip_alarm", &request.to_string()))
        }
        Err(error) => {
            log::warn!("alarm action failed: {error}");
            AlarmReply::Failed
        }
    }
}

// ---- desktop simulation ----------------------------------------------------------

/// Whether `ROVSTI_STOPS_SIMULATE=1` asks for synthetic fixes. Never on a phone.
pub fn simulate_requested() -> bool {
    !cfg!(target_os = "android") && std::env::var("ROVSTI_STOPS_SIMULATE").as_deref() == Ok("1")
}

/// A fix `elapsed_s` seconds after the start: from [`SIM_START_M`] left of the
/// midpoint, straight across the diameter at [`SIM_SPEED_MPS`], parked
/// [`SIM_END_M`] on the right side.
pub fn sim_fix(d: &Diameter, elapsed_s: f64, at_ms: i64) -> FixIn {
    let offset = (SIM_START_M - SIM_SPEED_MPS * elapsed_s.max(0.0)).max(-SIM_END_M);
    let moving = offset > -SIM_END_M;
    // Unit vector A -> B in local metres; "left" is that turned 90 degrees
    // counter-clockwise.
    let (ax, ay) = d.local(d.a());
    let (bx, by) = d.local(d.b());
    let (dx, dy) = (bx - ax, by - ay);
    let len = dx.hypot(dy);
    let (ux, uy) = (dx / len, dy / len);
    let at = d.from_local((-uy * offset, ux * offset));
    FixIn {
        lat: at.lat,
        lon: at.lon,
        accuracy_m: 8.0,
        speed_mps: moving.then_some(SIM_SPEED_MPS),
        at_ms,
    }
}

/// Feeds one synthetic fix to the headless handler, exactly as the Android
/// service does. `None` when no trip is running; otherwise the reply JSON.
pub fn simulate_tick(dir: &Path) -> Option<String> {
    let trip = {
        let _guard = store::lock();
        store::load_trip(dir)?
    };
    let at_ms = now_ms();
    let elapsed_s = (at_ms - trip.started_ms) as f64 / 1_000.0;
    let fix = sim_fix(&trip.trip.diameter, elapsed_s, at_ms);
    let mut request = serde_json::to_value(fix).ok()?;
    request["dir"] = json!(dir.to_string_lossy());
    Some(crate::headless::handle("trip_fix", &request.to_string()))
}

// ---- files and the share sheet ---------------------------------------------------------

/// Biggest places file the importer accepts, bytes.
#[allow(dead_code)] // used by the places import UI in a later step
pub const IMPORT_MAX_BYTES: u64 = 2 * 1024 * 1024;

/// What the notice line says after "Can't ...: " when an Android call fails.
fn android_words(error: &AndroidError) -> String {
    match error {
        AndroidError::Unsupported => "this needs an Android phone".to_owned(),
        other => {
            log::warn!("android call failed: {other}");
            "something went wrong".to_owned()
        }
    }
}

fn done(result: rovsti_android::Result<bool>) -> Result<(), String> {
    match result {
        Ok(true) => Ok(()),
        Ok(false) => Err("Android refused".to_owned()),
        Err(error) => Err(android_words(&error)),
    }
}

/// A file name that is safe on every file system.
fn safe_name(name: &str) -> String {
    let clean: String = name
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, '-' | '_' | '.') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let clean = clean.trim_matches(['_', '.']);
    if clean.is_empty() {
        "stops".to_owned()
    } else {
        clean.chars().take(60).collect()
    }
}

fn write_temp(name: &str, bytes: &[u8]) -> Result<std::path::PathBuf, String> {
    let words = || "the temporary file can't be written".to_owned();
    let dir = std::env::temp_dir().join("rovsti-stops");
    std::fs::create_dir_all(&dir).map_err(|_| words())?;
    let path = dir.join(safe_name(name));
    std::fs::write(&path, bytes).map_err(|_| words())?;
    Ok(path)
}

/// Saves `bytes` (a PNG) to the gallery as `name`.
#[allow(dead_code)] // used by the share-as-QR UI in a later step
pub fn save_png(name: &str, bytes: &[u8]) -> Result<(), String> {
    let path = write_temp(name, bytes)?;
    done(rovsti_android::media::save_image_to_gallery(
        &path.to_string_lossy(),
        name,
        "image/png",
    ))
}

/// Opens the share sheet with `bytes` as a PNG file called `name`.
#[allow(dead_code)] // used by the share-as-QR UI in a later step
pub fn share_png(name: &str, bytes: &[u8]) -> Result<(), String> {
    let stem = name.strip_suffix(".png").unwrap_or(name);
    let file = format!("{}.png", safe_name(stem));
    let path = write_temp(&file, bytes)?;
    done(rovsti_android::media::share_image(
        &path.to_string_lossy(),
        "image/png",
        &file,
    ))
}

/// Hands `text` to the system save dialog as the file `name` of type `mime`.
/// The answer is a `document_result` with op `ExportMessages`.
#[allow(dead_code)] // used by the places export UI in a later step
pub fn export_text(name: &str, mime: &str, text: &str) -> Result<(), String> {
    let file = safe_name(name);
    let path = write_temp(&file, text.as_bytes())?;
    done(rovsti_android::documents::export_file(
        &file,
        mime,
        &path.to_string_lossy(),
    ))
}

/// Opens the system file picker for a places file (up to 2 MB). The answer is
/// a `document_result` with op `ImportMessages` and the cache copy in `path`.
#[allow(dead_code)] // used by the places import UI in a later step
pub fn pick_import() -> Result<(), String> {
    done(rovsti_android::documents::import_file(IMPORT_MAX_BYTES))
}

/// Reads the picked file at `path`, then deletes the cache copy.
#[allow(dead_code)] // used by the places import UI in a later step
pub fn read_import(path: &str) -> Result<String, String> {
    let text = std::fs::read_to_string(path).map_err(|_| "that file can't be read".to_owned());
    if let Err(error) = std::fs::remove_file(path) {
        log::debug!("could not remove the import copy: {error}");
    }
    text
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::store::tests::tmp;
    use rovsti_geo::{Coord, NoticeWhen, Side, TripState};

    fn end(label: &str, lon: f64) -> Endpoint {
        Endpoint {
            label: label.into(),
            coord: Coord::new(41.0, lon),
        }
    }

    /// About 1 km apart, west to east.
    fn trip_of(id: u64, started_ms: i64, notice: Option<&NoticePrefs>) -> ActiveTrip {
        build_trip(id, end("A", 28.994), end("B", 29.006), started_ms, notice).unwrap()
    }

    fn pre(l: Option<Access>, n: Option<Access>) -> Preflight {
        Preflight {
            location: l,
            notifications: n,
            ..Preflight::default()
        }
    }

    #[test]
    fn the_draft_round_trips() {
        let dir = tmp("draft-rt");
        let ends = Ends {
            a: Some(crate::picker::Destination {
                gtfs_stop: Some("S1".into()),
                pattern: Some(7),
                ..crate::picker::Destination::new("Home", Coord::new(41.0, 29.0))
            }),
            b: None,
        };
        save_draft(&dir, &ends);
        assert_eq!(load(&dir).draft, ends);
    }

    #[test]
    fn the_next_trip_choices_round_trip_and_default_to_on() {
        let dir = tmp("next-trip-rt");
        assert!(load(&dir).next.notice_on);
        save_next_trip(&dir, &NextTrip { notice_on: false });
        assert!(!load(&dir).next.notice_on);
        std::fs::write(dir.join(store::NEXT_TRIP_FILE), "{}").unwrap();
        assert!(load(&dir).next.notice_on);
    }

    #[test]
    fn a_corrupt_draft_reads_as_empty() {
        let dir = tmp("draft-bad");
        std::fs::write(dir.join(store::DRAFT_FILE), "{nope").unwrap();
        assert_eq!(load(&dir).draft, Ends::default());
    }

    #[test]
    fn clearing_the_history_empties_the_file_and_keeps_the_trip() {
        let dir = tmp("backend-clear");
        let trip = trip_of(4, 1, None);
        store::save_trip(&dir, &trip).unwrap();
        store::end_trip(&dir, Outcome::Arrived, 9).unwrap();
        store::save_trip(&dir, &trip).unwrap();
        assert_eq!(reload_trip(&dir).1.records.len(), 1);

        clear_history(&dir);
        let (running, history) = reload_trip(&dir);
        assert!(history.records.is_empty());
        assert_eq!(running.unwrap().id, 4);
    }

    #[test]
    fn saved_history_round_trips() {
        let dir = tmp("backend-save-history");
        let trip = trip_of(5, 1, None);
        store::save_trip(&dir, &trip).unwrap();
        store::end_trip(&dir, Outcome::Arrived, 9).unwrap();
        let before = reload_trip(&dir).1;
        clear_history(&dir);
        assert!(reload_trip(&dir).1.records.is_empty());
        save_history(&dir, &before);
        assert_eq!(reload_trip(&dir).1, before);
    }

    #[test]
    fn desktop_has_nothing_to_ask() {
        assert_eq!(Preflight::default().next_step(), None);
        assert!(!Preflight::default().full_screen_missing());
    }

    #[test]
    fn location_comes_before_notifications() {
        let g = Some(Access::Granted);
        let d = Some(Access::Denied);
        let p = Some(Access::PermanentlyDenied);
        assert_eq!(pre(g, g).next_step(), None);
        assert_eq!(pre(d, d).next_step(), Some(Step::RequestLocation));
        assert_eq!(pre(p, d).next_step(), Some(Step::LocationInSettings));
        assert_eq!(pre(g, d).next_step(), Some(Step::RequestNotifications));
        assert_eq!(pre(g, p).next_step(), Some(Step::NotificationsInSettings));
    }

    #[test]
    fn full_screen_notice_only_when_known_missing() {
        let with = |full_screen| Preflight {
            full_screen,
            ..Preflight::default()
        };
        assert!(full_screen_notice(&with(Some(false))).is_some());
        assert_eq!(full_screen_notice(&with(Some(true))), None);
        assert_eq!(full_screen_notice(&with(None)), None);
    }

    #[test]
    fn background_notice_needs_location_and_a_known_missing_grant() {
        let with = |location, background_location| Preflight {
            location,
            background_location,
            ..Preflight::default()
        };
        let g = Some(Access::Granted);
        let d = Some(Access::Denied);
        let p = Some(Access::PermanentlyDenied);
        assert!(background_location_notice(&with(g, d)).is_some());
        assert!(background_location_notice(&with(g, p)).is_some());
        assert_eq!(background_location_notice(&with(g, g)), None);
        assert_eq!(background_location_notice(&with(g, None)), None);
        assert_eq!(background_location_notice(&with(d, d)), None);
        assert_eq!(background_location_notice(&with(p, p)), None);
        assert_eq!(background_location_notice(&with(None, d)), None);
    }

    #[test]
    fn next_step_ignores_background_location() {
        let p = Preflight {
            location: Some(Access::Granted),
            notifications: Some(Access::Granted),
            background_location: Some(Access::PermanentlyDenied),
            ..Preflight::default()
        };
        assert_eq!(p.next_step(), None);
    }

    #[test]
    fn a_missing_full_screen_grant_is_not_a_blocker() {
        let p = Preflight {
            location: Some(Access::Granted),
            notifications: Some(Access::Granted),
            full_screen: Some(false),
            ..Preflight::default()
        };
        assert_eq!(p.next_step(), None);
        assert!(p.full_screen_missing());
    }

    #[test]
    fn the_notice_needs_a_chat() {
        let mut prefs = NoticePrefs::default();
        assert!(trip_of(1, 0, Some(&prefs)).notice.is_none());
        prefs.chat = "chat-1".into();
        prefs.when = NoticeWhen::WithAlarm;
        assert_eq!(trip_of(1, 0, Some(&prefs)).notice.unwrap().chat, "chat-1");
        assert!(trip_of(1, 0, None).notice.is_none());
    }

    #[test]
    fn a_degenerate_pair_builds_no_trip() {
        let same = build_trip(1, end("A", 29.0), end("B", 29.0), 0, None);
        assert_eq!(same.unwrap_err(), DiameterError::SamePoint);
    }

    #[test]
    fn the_start_spec_names_the_directory_and_label() {
        let spec: serde_json::Value =
            serde_json::from_str(&start_spec(Path::new("/data/x"), "Kadikoy")).unwrap();
        assert_eq!(spec["dir"], "/data/x");
        assert_eq!(spec["title"], "Kadikoy");
        assert_eq!(spec["high_accuracy"], true);
        assert!(spec["next_fix_ms"].as_u64().unwrap() > 0);
        assert!(spec["alarm"]["volume_pct"].as_u64().unwrap() >= 80);
    }

    #[test]
    fn alarm_replies_are_read() {
        assert_eq!(
            parse_alarm_reply(r#"{"dismissed":true}"#),
            AlarmReply::Dismissed
        );
        assert_eq!(
            parse_alarm_reply(r#"{"ring_at_ms":1700000000000}"#),
            AlarmReply::Snoozed {
                at_ms: 1_700_000_000_000
            }
        );
        assert_eq!(parse_alarm_reply(r#"{"state":"ended"}"#), AlarmReply::Ended);
        assert_eq!(parse_alarm_reply(""), AlarmReply::Failed);
        assert_eq!(
            parse_alarm_reply(r#"{"dismissed":false}"#),
            AlarmReply::Failed
        );
    }

    #[test]
    fn the_simulation_crosses_the_diameter_and_parks() {
        let d = Diameter::new(Coord::new(41.0, 28.994), Coord::new(41.0, 29.006)).unwrap();
        let at = |f: &FixIn| Coord::new(f.lat, f.lon);
        let start = sim_fix(&d, 0.0, 0);
        assert!((d.offset_m(at(&start)) - SIM_START_M).abs() < 1.0);
        assert!(d.center().distance_m(at(&start)) > 1_900.0);
        let mid = sim_fix(&d, 10.0, 0);
        assert!((d.offset_m(at(&mid)) - (SIM_START_M - 500.0)).abs() < 1.0);
        assert!(mid.speed_mps.is_some());
        let end = sim_fix(&d, 1_000.0, 0);
        assert!((d.offset_m(at(&end)) + SIM_END_M).abs() < 1.0);
        assert!(d.contains(at(&end)));
        assert_eq!(end.speed_mps, None);
        // A start time in the future never puts the vehicle before the start.
        let early = sim_fix(&d, -5.0, 0);
        assert!((d.offset_m(at(&early)) - SIM_START_M).abs() < 1.0);
    }

    #[test]
    fn a_started_trip_is_on_disk_and_a_second_start_replaces_it() {
        let dir = tmp("backend-start");
        start_trip(&dir, &trip_of(1, 10, None)).unwrap();
        assert_eq!(store::load_trip(&dir).unwrap().id, 1);
        start_trip(&dir, &trip_of(2, 20, None)).unwrap();
        assert_eq!(store::load_trip(&dir).unwrap().id, 2);
        let history = load(&dir).history;
        assert_eq!(history.records.len(), 1);
        assert_eq!(history.records[0].outcome, Outcome::Cancelled);
    }

    #[test]
    fn stopping_records_a_cancelled_trip() {
        let dir = tmp("backend-stop");
        start_trip(&dir, &trip_of(5, 10, None)).unwrap();
        stop_trip(&dir);
        let data = load(&dir);
        assert!(data.trip.is_none());
        assert_eq!(data.history.records.len(), 1);
        // Stopping with nothing running changes nothing.
        stop_trip(&dir);
        assert_eq!(load(&dir).history.records.len(), 1);
    }

    #[test]
    fn a_simulated_trip_reaches_the_ring() {
        let dir = tmp("backend-sim");
        assert!(simulate_tick(&dir).is_none());
        // 60 s at 50 m/s: already parked 200 m past the line.
        let started = now_ms() - 60_000;
        start_trip(&dir, &trip_of(9, started, None)).unwrap();
        // The first fix of a real service is on the left; seed it.
        let seed = sim_fix(&store::load_trip(&dir).unwrap().trip.diameter, 0.0, 0);
        let mut request = serde_json::to_value(seed).unwrap();
        request["dir"] = json!(dir.to_string_lossy());
        let first: rovsti_geo::FixOut =
            serde_json::from_str(&crate::headless::handle("trip_fix", &request.to_string()))
                .unwrap();
        assert!(!first.ring);
        assert_eq!(first.side, Some(Side::Left));
        let reply = simulate_tick(&dir).unwrap();
        let out: rovsti_geo::FixOut = serde_json::from_str(&reply).unwrap();
        assert!(out.ring);
        assert_eq!(out.state, TripState::Arrived);
        assert_eq!(out.side, Some(Side::Right));
    }

    #[test]
    fn android_wrappers_are_unsupported_on_desktop() {
        assert!(save_png("qr.png", &[1]).is_err());
        assert!(share_png("qr", &[1]).is_err());
        assert!(export_text("places.gpx", "application/gpx+xml", "<gpx/>").is_err());
        assert_eq!(pick_import(), Err("this needs an Android phone".to_owned()));
    }

    #[test]
    fn read_import_reads_and_removes_the_copy() {
        let dir = tmp("backend-import");
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("places.json");
        std::fs::write(&file, "{\"a\":1}").unwrap();
        let path = file.to_string_lossy().into_owned();
        assert_eq!(read_import(&path).unwrap(), "{\"a\":1}");
        assert!(!file.exists());
        assert!(read_import(&path).is_err());
    }

    #[test]
    fn safe_names_drop_separators() {
        assert_eq!(safe_name("a/b c.gpx"), "a_b_c.gpx");
        assert_eq!(safe_name("///"), "stops");
    }

    #[test]
    fn a_missing_file_loads_the_defaults() {
        let data = load(&tmp("backend-empty"));
        assert!(data.trip.is_none() && data.gtfs.is_none());
        assert!(data.saved.stops.is_empty());
        assert_eq!(data.prefs, StopsPrefs::default());
    }

    #[test]
    fn an_old_single_point_file_loads_empty_and_is_rewritten_as_v2() {
        let dir = tmp("backend-old-saved");
        std::fs::create_dir_all(&dir).unwrap();
        let old =
            r#"{"next_id":2,"places":[{"id":1,"name":"Home","coord":{"lat":41.0,"lon":29.0}}]}"#;
        std::fs::write(dir.join(store::SAVED_FILE), old).unwrap();
        let data = load(&dir);
        assert!(data.saved.stops.is_empty());
        let text = std::fs::read_to_string(dir.join(store::SAVED_FILE)).unwrap();
        let again = load_saved_stops(Some(&text));
        assert!(!again.discarded, "the file on disk is v2 now: {text}");
        assert_eq!(again.stops.version, rovsti_geo::saved_stops::FORMAT_VERSION);
        assert!(!text.contains("places"));
    }

    #[test]
    fn saved_stops_survive_a_save_and_load() {
        use rovsti_geo::{Coord, Endpoint};
        let dir = tmp("backend-saved-round");
        let mut saved = SavedStops::default();
        let ep = |lat| Endpoint {
            coord: Coord::new(lat, 29.0),
            label: String::new(),
        };
        saved.add("Gate", ep(41.0), ep(41.002), 5).unwrap();
        save_stops(&dir, &saved);
        assert_eq!(load(&dir).saved, saved);
    }
}
