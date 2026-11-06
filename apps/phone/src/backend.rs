//! The one facade between Android and the views (AD-15, AD-16).
//!
//! This is the only module that names `rovsti_android`. Views read `Arc`
//! snapshots and domain types from [`Backend`] and never see a `Result`:
//! on desktop (`Error::Unsupported`) the backend serves sample data, and
//! Android actions answer [`Outcome::NotAvailable`] so the UI can say
//! "Not available on this platform" instead of panicking.
//!
//! Attach order for a GPUI session (AD-5): (1) install the event sink,
//! (2) push `calls::snapshot()` through that same channel, (3) pull
//! permissions and providers. Events drain in a foreground task, so GPUI state
//! only changes on the main thread. Provider pulls and JSON parsing run on the
//! background executor.

// The views for calls, Recents, Contacts and Favorites land in Epics 4-8 and
// consume the rest of this API; until then only part of it is called.
#![allow(dead_code)]

use std::{
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use futures::StreamExt;
use gpui::{App, AppContext as _, Context, Entity, Task};
use rovsti_android::{
    Error, calls, documents,
    events::{self, RawEvent, kind},
    media, permissions, providers, roles, telecom, xiaomi,
};
use rovsti_core::{
    blocklist::BlockSet,
    call::CallModel,
    call_log::{self, RecentsRow},
    recents::LocaleTime,
    scene::SceneDiff,
    search::ContactIndex,
    setup::{self, Reading, SetupChecklist, SetupFacts, SetupItem, XiaomiConfirmations},
    wire::{
        self, AudioRoute, CallLogEntry, CallLogPayload, CallSnapshot, ContactsPayload, Envelope,
        FavoritesPayload, WireContact, WireError, WriteError,
    },
};
use rovsti_core::{settings::Settings, sim::SimInfo};
use rovsti_ui::storage::{self, Prefs, PrefsWriter};
use serde::de::DeserializeOwned;

use crate::sample;

mod blocking;
mod local;
#[cfg(any(not(target_os = "android"), feature = "perf", test))]
mod preview;
#[cfg(any(not(target_os = "android"), feature = "perf", test))]
mod preview_multi;
#[cfg(test)]
pub use preview_multi::SCENES;

pub use blocking::{BlockState, ReplyOutcome};
pub use local::SystemFacts;
pub use rovsti_android::{
    permissions::{Access, Group},
    xiaomi::{Setting as XiaomiSetting, XiaomiOs},
};

/// What the app asked Android to do.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Done,
    /// Android refused or had nothing to do (missing permission, unknown id).
    Declined,
    /// Not running on Android.
    NotAvailable,
}

impl Outcome {
    /// Android did not take the command (a desktop preview "takes" it itself).
    pub fn failed(self) -> bool {
        self == Self::Declined
    }

    /// What to tell the owner when an action did not happen: the platform
    /// note on desktop, otherwise `fallback`.
    pub fn why_not(self, fallback: &'static str) -> &'static str {
        if self == Self::NotAvailable {
            "Not available on this platform"
        } else {
            fallback
        }
    }

    fn from(result: rovsti_android::Result<bool>, what: &str) -> Self {
        match result {
            Ok(true) => Self::Done,
            Ok(false) => Self::Declined,
            Err(Error::Unsupported) => Self::NotAvailable,
            Err(error) => {
                log::warn!("{what} failed: {error}");
                Self::Declined
            }
        }
    }
}

/// How opening a system settings page went (story 14.4).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SystemOutcome {
    Opened,
    /// The page has no activity here; App info opened instead.
    Fallback,
    /// Nothing opened at all.
    Missing,
    NotAvailable,
}

impl SystemOutcome {
    /// The Notice pill text for an outcome that needs one.
    pub fn notice(self) -> Option<&'static str> {
        match self {
            Self::Opened => None,
            Self::Fallback => Some("Opened app settings instead"),
            Self::Missing => Some("Couldn't open settings"),
            Self::NotAvailable => Some("Not available on this platform"),
        }
    }

    /// Reads Kotlin's `{v, ok, error?, data?:{fallback}}` envelope.
    pub fn from_envelope(json: &str) -> Self {
        match serde_json::from_str::<rovsti_core::wire::OpenSystemResponse>(json)
            .map(Envelope::into_result)
        {
            Ok(Ok(data)) if data.fallback => Self::Fallback,
            Ok(Ok(_)) => Self::Opened,
            _ => Self::Missing,
        }
    }
}

/// Opens a system settings page; never throws, never touches a call.
pub fn open_system(page: rovsti_android::telecom::SystemPage) -> SystemOutcome {
    match telecom::open_system(page) {
        Ok(json) => SystemOutcome::from_envelope(&json),
        Err(Error::Unsupported) => SystemOutcome::NotAvailable,
        Err(error) => {
            log::warn!("open_system failed: {error}");
            SystemOutcome::Missing
        }
    }
}

/// State of a list that comes from a provider.
#[derive(Debug)]
pub enum Load<T> {
    Loading,
    Ready(Arc<T>),
    PermissionDenied,
    Unavailable,
}

impl<T> Clone for Load<T> {
    fn clone(&self) -> Self {
        match self {
            Self::Loading => Self::Loading,
            Self::Ready(data) => Self::Ready(Arc::clone(data)),
            Self::PermissionDenied => Self::PermissionDenied,
            Self::Unavailable => Self::Unavailable,
        }
    }
}

/// The call log as pulled, plus its grouped Recents rows.
#[derive(Debug)]
pub struct CallLog {
    /// What the phone gave us, newest first. Rows of a blocked number are
    /// left out of [`Self::entries`] and [`Self::rows`] but stay here, so
    /// Unblock (and Undo) brings them straight back.
    pub source: Arc<Vec<CallLogEntry>>,
    pub entries: Vec<CallLogEntry>,
    pub rows: Vec<RecentsRow>,
    /// Clock style of the phone, for the row time labels.
    pub locale: LocaleTime,
    /// Country code the numbers in `source` mostly share, for "Copy as" and
    /// "Share as". Computed once per snapshot.
    pub home_cc: Option<String>,
}

/// What Setup needs to know about the phone (FR-5, FR-6).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PermissionFacts {
    pub phone: Access,
    pub contacts: Access,
    pub notifications: Access,
    /// Needed for WhatsApp call audio.
    pub microphone: Access,
    pub full_screen_intent: bool,
    pub dialer_role: bool,
    /// `PowerManager.isIgnoringBatteryOptimizations`.
    pub battery_exempt: bool,
    /// Xiaomi AppOps readings; `None` when unreadable or not Xiaomi.
    pub autostart: Option<bool>,
    pub lock_screen: Option<bool>,
    pub background_popups: Option<bool>,
}

impl Default for PermissionFacts {
    /// Desktop: nothing to ask for, so nothing nags.
    fn default() -> Self {
        Self {
            phone: Access::Granted,
            contacts: Access::Granted,
            notifications: Access::Granted,
            microphone: Access::Granted,
            full_screen_intent: true,
            dialer_role: true,
            battery_exempt: true,
            autostart: None,
            lock_screen: None,
            background_popups: None,
        }
    }
}

/// A command for the current call (AD-6).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CallCommand {
    Answer(i32),
    Reject(i32),
    Disconnect(i32),
    Hold(i32, bool),
    Mute(bool),
    Route(AudioRoute),
    Dtmf(char),
    DtmfStop,
    AnswerReplacing(i32),
    /// Swap the active and the held call (FR-45).
    Swap,
    /// Merge the two calls into a conference (FR-46).
    Merge,
    /// Take a participant out of the conference into a private call (FR-47).
    Split(i32),
    /// Answer a waiting call and let the network hold the current one (FR-48).
    AnswerHolding(i32),
    /// The relabelled "End held call and answer" (FR-48).
    AnswerEndingHeld(i32),
    /// End every call (the notification's End).
    EndAll,
}

/// What `place_call` did (AD-11, story 4.3).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Placement {
    /// Handed to `TelecomManager.placeCall`, or started in the desktop preview.
    Placed,
    /// A second press inside the debounce window; nothing happened.
    Debounced,
    /// `CALL_PHONE` is missing: the request is showing and the call is placed
    /// once it is granted.
    Requesting,
    /// Android refused or the permission cannot be asked again.
    Blocked,
    /// Android did not accept the call.
    Failed,
    /// The SIM has no voicemail number; nothing was placed.
    NoVoicemail,
}

/// Desktop preview switch: pretend the SIM has no voicemail number.
fn preview_no_voicemail() -> bool {
    std::env::var("ROVSTI_PREVIEW_NO_VOICEMAIL").is_ok_and(|v| v == "1")
}

/// A second Call press inside this window is ignored.
const PLACE_DEBOUNCE: Duration = Duration::from_millis(800);

pub struct Backend {
    calls: CallModel,
    /// Parts of the call scene that changed and no view has consumed yet.
    scene_diff: SceneDiff,
    /// Bumped when the system confirms the keyguard was dismissed (Add call, 15.5).
    keyguard_gen: u64,
    /// Counts WhatsApp FCM wake-ups; the app root forwards new ones to the
    /// WhatsApp settings entity, which owns the gateway stream.
    wa_pushes: u64,
    /// Answer / Decline taps on a WhatsApp call notification, not yet applied.
    wa_call_actions: Vec<(String, rovsti_android::events::WaCallAction)>,
    /// A WhatsApp handoff from a sibling app, not yet taken by the app root.
    handoff: Option<rovsti_core::wire::Handoff>,
    last_place: Option<Instant>,
    /// A number waiting for the Phone permission, and the permission pull
    /// number it was requested at (only newer pulls may judge the answer).
    pending_call: Option<(String, u64)>,
    /// The Phone permission was refused for a requested call.
    call_blocked: bool,
    call_log: Load<CallLog>,
    contacts: Load<ContactIndex>,
    favorites: Load<Vec<WireContact>>,
    permissions: PermissionFacts,
    /// The first permission pull has answered (until then the role reading is
    /// a guess, so the Preview banner stays away).
    permissions_known: bool,
    prefs: Prefs,
    /// The owner's settings as an immutable snapshot; views clone the `Arc`
    /// and never read a file (AD-30). Replaced only when a setting changes.
    settings: Arc<Settings>,
    /// The one writer of `prefs.json`; present once the data dir is known.
    writer: Option<PrefsWriter>,
    /// The preferences were read (or could not be): the app may now pick its
    /// first tab.
    prefs_ready: bool,
    /// Newest pull wins: a slow older pull never overwrites a newer one.
    log_pull: u64,
    contacts_pull: u64,
    permissions_pull: u64,
    /// The phone's SIMs (empty until the first pull and on desktop).
    sims: SimInfo,
    sims_pull: u64,
    /// A SIM chosen for a call that waits for the Phone permission.
    pending_account: Option<String>,
    /// Desktop preview: the SIM chosen for the next fake call.
    preview_account: Option<String>,
    /// Desktop preview: 1 single SIM, 2 dual (ask), 3 dual (default SIM 2).
    preview_sims: u8,
    facts: SystemFacts,
    ui_ready: bool,
    last_sync: String,
    /// Bumped by every setting change; a late save check ignores older ones.
    change_gen: u64,
    setting_failed_gen: u64,
    /// No data directory: preferences live for this session only.
    prefs_unsaved: bool,
    document: Option<rovsti_core::wire::DocumentResult>,
    document_gen: u64,
    /// The system blocklist (AD-27) and whether this user may use it.
    blocklist: Load<BlockState>,
    can_block: bool,
    block_pull: u64,
    /// The type-6 rows, pulled only while the Blocked calls view is wanted.
    blocked_calls: Load<Vec<CallLogEntry>>,
    blocked_calls_wanted: bool,
    blocked_pull: u64,
    /// Android refused `silenceRinger()`: the control stays a blank slot (AD-29).
    silence_refused: bool,
    /// A system-driven request for the UI that nobody has taken yet (`ui_request`).
    ui_request: Option<rovsti_core::wire::UiRequest>,
    /// Desktop preview only: the fake call reports a locked keyguard.
    #[cfg(any(not(target_os = "android"), feature = "perf", test))]
    preview_locked: bool,
    /// Desktop preview only: how the next fake call should look.
    #[cfg(any(not(target_os = "android"), feature = "perf", test))]
    preview_variant: Option<&'static str>,
    _tasks: Vec<Task<()>>,
}

const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

pub(crate) fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

impl Backend {
    /// Starts a session: installs the sink, pushes the call snapshot, pulls
    /// everything, and starts draining events.
    pub fn start(cx: &mut App) -> Entity<Self> {
        rovsti_android::bridge::keep_symbols();
        let data_dir = storage::data_dir("phone", cx);
        cx.new(|cx| {
            // (1) the new sink replaces any previous session's.
            let (mut events_rx, sink_token) = events::install_sink_owned();
            // (2) attach by snapshot, through the same channel.
            let mut first_snapshot = None;
            match calls::snapshot() {
                Ok(json) => {
                    // Also applied directly below, so the very first frame already knows
                    // about a ringing call (the Incoming Call screen is never preceded by tabs).
                    first_snapshot = serde_json::from_str::<CallSnapshot>(&json).ok();
                    events::dispatch(kind::CALLS, json);
                }
                Err(Error::Unsupported) => {}
                Err(error) => log::debug!("no call snapshot yet: {error}"),
            }
            let mut backend = Self {
                calls: CallModel::new(),
                scene_diff: SceneDiff::default(),
                keyguard_gen: 0,
                last_place: None,
                pending_call: None,
                wa_pushes: 0,
                wa_call_actions: Vec::new(),
                call_blocked: false,
                call_log: Load::Loading,
                contacts: Load::Loading,
                favorites: Load::Loading,
                permissions: PermissionFacts::default(),
                permissions_known: false,
                prefs: Prefs::default(),
                settings: Arc::new(Settings::default()),
                writer: None,
                prefs_ready: false,
                log_pull: 0,
                contacts_pull: 0,
                permissions_pull: 0,
                sims: SimInfo::default(),
                sims_pull: 0,
                pending_account: None,
                preview_account: None,
                preview_sims: std::env::var("ROVSTI_PREVIEW_SIMS")
                    .ok()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(1),
                facts: SystemFacts::default(),
                ui_ready: false,
                last_sync: String::new(),
                change_gen: 0,
                setting_failed_gen: 0,
                prefs_unsaved: false,
                document: None,
                document_gen: 0,
                blocklist: Load::Loading,
                can_block: true,
                block_pull: 0,
                blocked_calls: Load::Loading,
                blocked_calls_wanted: false,
                blocked_pull: 0,
                silence_refused: false,
                ui_request: None,
                handoff: None,
                #[cfg(any(not(target_os = "android"), feature = "perf", test))]
                preview_locked: false,
                #[cfg(any(not(target_os = "android"), feature = "perf", test))]
                preview_variant: None,
                _tasks: Vec::new(),
            };
            if let Some(snapshot) = first_snapshot {
                backend.calls.apply(snapshot);
            }
            let drain = cx.spawn(async move |this, cx| {
                while let Some(event) = events_rx.next().await {
                    if this
                        .update(cx, |this: &mut Backend, cx| this.handle(event, cx))
                        .is_err()
                    {
                        break;
                    }
                }
            });
            let prefs = cx.spawn(async move |this, cx| {
                let Ok(dir) = data_dir.await else {
                    log::warn!("no data directory; preferences will not persist");
                    this.update(cx, |this: &mut Backend, cx| {
                        this.prefs_ready = true;
                        this.prefs_unsaved = true;
                        cx.notify();
                    })
                    .ok();
                    return;
                };
                let load_dir = dir.clone();
                let loaded = cx
                    .background_executor()
                    .spawn(async move { storage::load_prefs(&load_dir) })
                    .await;
                let mut prefs = loaded.prefs;
                let changed = prefs.ui.app_version != APP_VERSION;
                if changed {
                    // Xiaomi confirmations are valid for one app version only.
                    prefs.ui.xiaomi_confirmed.clear();
                    prefs.ui.app_version = APP_VERSION.to_owned();
                }
                // An upgraded file is saved once so it becomes v2 on disk.
                let upgraded = loaded.origin != storage::Origin::Current;
                this.update(cx, |this: &mut Backend, cx| {
                    this.settings = Arc::new(prefs.settings.clone());
                    this.prefs = prefs;
                    this.prefs_ready = true;
                    this.settings_published(cx);
                    this.writer = Some(PrefsWriter::new(dir, loaded.origin));
                    if changed || upgraded {
                        this.save_prefs();
                    }
                    cx.notify();
                })
                .ok();
            });
            backend._tasks.push(drain);
            backend._tasks.push(prefs);
            // The sink belongs to this session; clear it when the session ends.
            cx.on_release(move |_, _| {
                let _ = rovsti_android::window::ui_attached(false);
                events::clear_sink_if(sink_token);
            })
            .detach();
            // (3) permissions and providers.
            backend.refresh_permissions(cx);
            backend.refresh_call_log(cx);
            backend.refresh_contacts(cx);
            backend.refresh_sims(cx);
            backend.refresh_facts(cx);
            backend.refresh_blocklist(cx);
            // Rust's session is live: Kotlin may now hand over queued one-shot events.
            backend.set_ui_ready(true);
            backend
        })
    }

    // ---- Reads: views take Arc snapshots -------------------------------

    /// The audio route list (story 14.2); rebuilt only when it changed.
    pub fn endpoints(&self) -> &rovsti_core::audio::EndpointList {
        self.calls.endpoints()
    }

    pub fn endpoint_rev(&self) -> u64 {
        self.calls.endpoint_rev()
    }

    /// `(rev, arrivals and losses since the last call)`; consumed by the views.
    pub fn take_route_changes(&mut self) -> (u64, rovsti_core::audio::Diff) {
        (self.calls.endpoint_rev(), self.calls.take_endpoint_diff())
    }

    /// The route popover opens: vanished devices leave.
    pub fn open_route_list(&mut self, cx: &mut Context<Self>) {
        self.calls.open_route_list();
        cx.notify();
    }

    /// Routes call audio to the endpoint with this identifier (AD-23). Views
    /// never see a `Result`: an unknown id is `Declined`.
    pub fn route_endpoint(&mut self, id: &str, cx: &mut Context<Self>) -> Outcome {
        let outcome = Outcome::from(calls::route_endpoint(id), "route endpoint");
        #[cfg(any(not(target_os = "android"), feature = "perf", test))]
        if outcome == Outcome::NotAvailable {
            self.preview_route_endpoint(id, cx);
            return Outcome::Done;
        }
        let _ = cx;
        outcome
    }

    pub fn calls(&self) -> &CallModel {
        &self.calls
    }

    /// The pending `ui_request`, if any; the app takes it once.
    pub fn take_ui_request(&mut self) -> Option<rovsti_core::wire::UiRequest> {
        self.ui_request.take()
    }

    /// A request that is waiting, without taking it.
    pub fn peek_ui_request(&self) -> Option<&rovsti_core::wire::UiRequest> {
        self.ui_request.as_ref()
    }

    pub fn call_log(&self) -> Load<CallLog> {
        self.call_log.clone()
    }

    pub fn contacts(&self) -> Load<ContactIndex> {
        self.contacts.clone()
    }

    pub fn favorites(&self) -> Load<Vec<WireContact>> {
        self.favorites.clone()
    }

    pub fn permissions(&self) -> PermissionFacts {
        self.permissions
    }

    /// Whether the permission readings come from the phone yet.
    pub fn permissions_known(&self) -> bool {
        self.permissions_known
    }

    /// Whether Rovsti Phone is the default phone app. Until the first pull
    /// answers this reads `true`, so Preview mode never flashes.
    pub fn is_default_dialer(&self) -> bool {
        !self.permissions_known || self.permissions.dialer_role
    }

    /// Story 6.4: Recents was viewed. Remembers the time (the badge counts
    /// newer misses only) and, off the UI thread, clears Android's `NEW` flag
    /// and the system missed-call notification. Does nothing, quietly, without
    /// the call log.
    pub fn mark_missed_seen(&mut self, cx: &mut Context<Self>) {
        if !matches!(self.call_log, Load::Ready(_)) {
            return;
        }
        let newest = match &self.call_log {
            Load::Ready(log) => log.entries.iter().map(|e| e.date_ms).max().unwrap_or(0),
            _ => 0,
        };
        let seen = now_ms().max(newest);
        self.update_prefs(cx, |prefs| {
            prefs.ui.missed_seen_ms = prefs.ui.missed_seen_ms.max(seen)
        });
        cx.background_executor()
            .spawn(async {
                if let Err(error) = providers::mark_missed_calls_read()
                    && !matches!(error, Error::Unsupported)
                {
                    log::debug!("marking missed calls read: {error}");
                }
            })
            .detach();
        cx.notify();
    }

    pub fn prefs(&self) -> &Prefs {
        &self.prefs
    }

    /// Whether the stored preferences have been read yet.
    pub fn prefs_ready(&self) -> bool {
        self.prefs_ready
    }

    /// "Try again" on an Unavailable Recents.
    pub fn retry_call_log(&mut self, cx: &mut Context<Self>) {
        // Back to Loading, so the screen answers the tap at once.
        self.call_log = Load::Loading;
        cx.notify();
        self.refresh_call_log(cx);
    }

    /// "Try again" on an Unavailable Contacts or Favorites.
    pub fn retry_contacts(&mut self, cx: &mut Context<Self>) {
        self.contacts = Load::Loading;
        self.favorites = Load::Loading;
        cx.notify();
        self.refresh_contacts(cx);
    }

    /// The Setup checklist for the current facts and stored confirmations.
    pub fn setup_checklist(&self, xiaomi: bool) -> SetupChecklist {
        let p = self.permissions;
        let facts = SetupFacts {
            role_held: p.dialer_role,
            phone_and_call_log_granted: p.phone.is_granted(),
            contacts_granted: p.contacts.is_granted(),
            notifications_granted: p.notifications.is_granted(),
            full_screen_allowed: p.full_screen_intent,
            battery_exempt: p.battery_exempt,
            autostart: Reading::from_option(p.autostart),
            lock_screen: Reading::from_option(p.lock_screen),
            background_popups: Reading::from_option(p.background_popups),
        };
        let mut confirmations = XiaomiConfirmations::default();
        for (key, version) in &self.prefs.ui.xiaomi_confirmed {
            if let Some(item) = SetupItem::from_key(key) {
                confirmations.confirm(item, version);
            }
        }
        setup::derive(facts, xiaomi, &confirmations, APP_VERSION)
    }

    /// Whether the owner confirmed this Xiaomi item by hand for this version.
    pub fn xiaomi_confirmed(&self, item: SetupItem) -> bool {
        self.prefs
            .ui
            .xiaomi_confirmed
            .get(item.key())
            .is_some_and(|v| v == APP_VERSION)
    }

    /// Stores (or clears) the owner's manual confirmation of a Xiaomi item
    /// for this app version.
    pub fn set_xiaomi_confirmed(
        &mut self,
        item: SetupItem,
        confirmed: bool,
        cx: &mut Context<Self>,
    ) {
        if !item.is_xiaomi() {
            return;
        }
        self.update_prefs(cx, |prefs| {
            if confirmed {
                prefs
                    .ui
                    .xiaomi_confirmed
                    .insert(item.key().to_owned(), APP_VERSION.to_owned());
            } else {
                prefs.ui.xiaomi_confirmed.remove(item.key());
            }
        });
        cx.notify();
    }

    // ---- Preferences (written by Rust only, off the main thread) --------

    /// Changes the preferences. Validated, saved through the single writer
    /// (coalesced to one write per 250 ms) and, when a setting changed, a new
    /// `Arc<Settings>` is published to the views.
    pub fn update_prefs(&mut self, cx: &mut Context<Self>, f: impl FnOnce(&mut Prefs)) {
        let before = self.prefs.clone();
        f(&mut self.prefs);
        self.prefs.validate();
        if self.prefs == before {
            return;
        }
        if self.prefs.settings != *self.settings {
            self.settings = Arc::new(self.prefs.settings.clone());
            self.settings_published(cx);
            cx.notify();
        } else if before.quick_replies.is_empty() != self.prefs.quick_replies.is_empty() {
            // Kotlin shows the notification's Message action only while replies exist.
            self.push_settings(cx);
        }
        self.save_prefs();
    }

    /// Convenience for a settings-only change.
    pub fn update_settings(&mut self, cx: &mut Context<Self>, f: impl FnOnce(&mut Settings)) {
        self.update_prefs(cx, |prefs| f(&mut prefs.settings));
    }

    /// The current settings; cheap to clone and hold across a render.
    pub fn settings(&self) -> Arc<Settings> {
        Arc::clone(&self.settings)
    }

    /// The last save failed: the UI says "Couldn't save setting". The value
    /// stays in effect for this session.
    pub fn prefs_save_failed(&self) -> bool {
        self.writer.as_ref().is_some_and(PrefsWriter::failed)
    }

    /// There is no data directory: changes last until the app closes.
    pub fn prefs_unsaved(&self) -> bool {
        self.prefs_unsaved
    }

    /// Writes any unsaved preference now. Called when the app goes to the
    /// background, where the process may be killed without notice.
    pub fn flush_prefs(&self) {
        if let Some(writer) = &self.writer {
            writer.flush();
        }
    }

    fn save_prefs(&self) {
        if let Some(writer) = &self.writer {
            writer.submit(self.prefs.clone());
        }
    }

    // ---- Commands -------------------------------------------------------

    /// Sends a call command. Non-blocking; the result only says whether
    /// Android took it. Displayed state changes when the next snapshot says so.
    pub fn send(&mut self, command: CallCommand, cx: &mut Context<Self>) -> Outcome {
        let result = match command {
            CallCommand::Answer(id) => calls::answer(id),
            CallCommand::Reject(id) => calls::reject(id),
            CallCommand::Disconnect(id) => calls::disconnect(id),
            CallCommand::Hold(id, on) => calls::hold(id, on),
            CallCommand::Mute(on) => calls::mute(on),
            CallCommand::Route(route) => match route_name(route) {
                Some(name) => calls::route(name),
                None => return Outcome::Declined,
            },
            CallCommand::Dtmf(digit) => {
                self.key_tone(digit, cx);
                calls::dtmf(digit)
            }
            CallCommand::DtmfStop => calls::dtmf_stop(),
            CallCommand::AnswerReplacing(id) => calls::answer_replacing(id),
            CallCommand::Swap => calls::swap(),
            CallCommand::Merge => calls::merge(),
            CallCommand::Split(id) => calls::split(id),
            CallCommand::AnswerHolding(id) => calls::answer_holding(id),
            CallCommand::AnswerEndingHeld(id) => calls::answer_ending_held(id),
            CallCommand::EndAll => calls::end_all(),
        };
        let outcome = Outcome::from(result, "call command");
        #[cfg(any(not(target_os = "android"), feature = "perf", test))]
        if outcome == Outcome::NotAvailable {
            self.preview_command(command, cx);
        }
        let _ = cx;
        outcome
    }

    // ---- Multi-call commands (AD-24, AD-25) -----------------------------
    //
    // Each returns whether Android took the command; the displayed state only
    // ever changes with the next snapshot.

    pub fn swap(&mut self, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::Swap, cx)
    }

    pub fn merge(&mut self, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::Merge, cx)
    }

    pub fn split(&mut self, id: i32, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::Split(id), cx)
    }

    /// Ends one call or conference participant.
    pub fn disconnect(&mut self, id: i32, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::Disconnect(id), cx)
    }

    pub fn answer_holding(&mut self, id: i32, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::AnswerHolding(id), cx)
    }

    /// Ends the current call, then answers `id`.
    pub fn answer_replacing(&mut self, id: i32, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::AnswerReplacing(id), cx)
    }

    /// Ends the held call, then answers `id` (the relabelled primary choice).
    pub fn answer_ending_held(&mut self, id: i32, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::AnswerEndingHeld(id), cx)
    }

    pub fn reject(&mut self, id: i32, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::Reject(id), cx)
    }

    pub fn end_all(&mut self, cx: &mut Context<Self>) -> Outcome {
        self.send(CallCommand::EndAll, cx)
    }

    /// How many times the system confirmed a keyguard dismissal.
    pub fn keyguard_gen(&self) -> u64 {
        self.keyguard_gen
    }

    /// What changed in the call scene since a view last asked (ORed across
    /// snapshots, so a view that renders less often never misses a part).
    pub fn take_scene_diff(&mut self) -> SceneDiff {
        std::mem::take(&mut self.scene_diff)
    }

    /// Perf runs (story 12.2): a 500-entry call log, so the scroll scenario
    /// has the same rows on every phone.
    #[cfg(any(feature = "perf", test))]
    pub fn perf_big_log(&mut self, cx: &mut Context<Self>) {
        let log = build_call_log(sample::long_call_log(now_ms(), 500), local_time());
        self.call_log = Load::Ready(Arc::new(log));
        cx.notify();
    }

    /// Tests: a 600-contact book, for the list virtualisation checks.
    #[cfg(test)]
    pub fn perf_big_book(&mut self, cx: &mut Context<Self>) {
        self.contacts = Load::Ready(Arc::new(ContactIndex::new(sample::long_contacts(600))));
        cx.notify();
    }

    /// Perf runs: a fake outgoing call that connects after 1.6 s. Never
    /// reaches Telecom.
    #[cfg(any(feature = "perf", test))]
    pub fn perf_call(&mut self, cx: &mut Context<Self>) {
        self.preview_start("+905551112233", cx);
    }

    /// Desktop preview: a fake incoming call (see `backend/preview.rs`).
    #[cfg(any(not(target_os = "android"), feature = "perf", test))]
    pub fn preview_ring(
        &mut self,
        name: Option<&str>,
        locked: bool,
        give_up: bool,
        cx: &mut Context<Self>,
    ) {
        self.preview_incoming(name, locked, give_up, cx);
    }

    /// Desktop preview: a call just ended, as the call log would tell us.
    #[cfg(not(target_os = "android"))]
    pub fn preview_new_call(
        &mut self,
        kind: rovsti_core::wire::CallLogKind,
        cx: &mut Context<Self>,
    ) {
        sample::add_call(kind, now_ms());
        self.refresh_call_log(cx);
    }

    /// The one call path (AD-11): every Call button ends here with the raw
    /// string. No Setup Item gates it; only `CALL_PHONE` does.
    pub fn place_call(&mut self, raw: &str, cx: &mut Context<Self>) -> Placement {
        let now = Instant::now();
        if self
            .last_place
            .is_some_and(|t| now.duration_since(t) < PLACE_DEBOUNCE)
        {
            return Placement::Debounced;
        }
        self.last_place = Some(now);
        self.call_blocked = false;
        if !self.permissions.phone.is_granted() {
            return self.request_phone_for(raw, cx);
        }
        self.place_now(raw, cx)
    }

    /// Whether a voicemail number is set. `true` when unknown; on desktop only
    /// `ROVSTI_PREVIEW_NO_VOICEMAIL=1` makes it `false`.
    pub fn voicemail_configured(&self) -> bool {
        match telecom::voicemail_configured() {
            Ok(set) => set,
            Err(Error::Unsupported) => !preview_no_voicemail(),
            Err(error) => {
                log::warn!("voicemail_configured failed: {error}");
                true
            }
        }
    }

    /// Calls the voicemail box (long-press on 1). Shares the debounce and the
    /// Phone permission rules of [`Self::place_call`]; on desktop the preview
    /// starts a call so the flow can be seen.
    pub fn call_voicemail(&mut self, cx: &mut Context<Self>) -> Placement {
        let now = Instant::now();
        if self
            .last_place
            .is_some_and(|t| now.duration_since(t) < PLACE_DEBOUNCE)
        {
            return Placement::Debounced;
        }
        if !self.voicemail_configured() {
            return Placement::NoVoicemail;
        }
        self.last_place = Some(now);
        self.call_blocked = false;
        if !self.permissions.phone.is_granted() {
            match permissions::request(Group::Phone) {
                // The owner holds the digit again once it is granted.
                Ok(true) => return Placement::Requesting,
                // Nothing to ask on desktop: the preview just places the call.
                Err(Error::Unsupported) => {}
                _ => {
                    self.call_blocked = true;
                    cx.notify();
                    return Placement::Blocked;
                }
            }
        }
        match telecom::call_voicemail() {
            Ok(true) => Placement::Placed,
            Ok(false) => Placement::Failed,
            Err(Error::Unsupported) => {
                #[cfg(not(target_os = "android"))]
                self.preview_start("voicemail", cx);
                let _ = cx;
                Placement::Placed
            }
            Err(error) => {
                log::warn!("calling voicemail failed: {error}");
                Placement::Failed
            }
        }
    }

    fn place_now(&mut self, raw: &str, cx: &mut Context<Self>) -> Placement {
        self.place_with_account(raw, cx)
    }

    fn request_phone_for(&mut self, raw: &str, cx: &mut Context<Self>) -> Placement {
        match permissions::request(Group::Phone) {
            Ok(true) => {
                self.pending_call = Some((raw.to_owned(), self.permissions_pull));
                Placement::Requesting
            }
            // Nothing to ask on desktop: the preview just places the call.
            Err(Error::Unsupported) => self.place_now(raw, cx),
            _ => {
                self.call_blocked = true;
                cx.notify();
                Placement::Blocked
            }
        }
    }

    /// How many WhatsApp wake-ups have arrived so far.
    pub fn wa_pushes(&self) -> u64 {
        self.wa_pushes
    }

    /// Notification actions on WhatsApp calls that arrived since the last call;
    /// the app root hands them to the WhatsApp call entity.
    pub fn take_wa_call_actions(&mut self) -> Vec<(String, rovsti_android::events::WaCallAction)> {
        std::mem::take(&mut self.wa_call_actions)
    }

    /// The pending WhatsApp handoff, if any; the app root takes it once.
    pub fn take_handoff(&mut self) -> Option<rovsti_core::wire::Handoff> {
        self.handoff.take()
    }

    /// The Phone permission was refused (or cannot be asked) for a call.
    pub fn call_blocked(&self) -> bool {
        self.call_blocked
    }

    /// "Grant access": asks again, or opens App info when Android won't.
    pub fn grant_phone_access(&mut self, raw: &str, cx: &mut Context<Self>) {
        self.call_blocked = false;
        if self.request_phone_for(raw, cx) == Placement::Blocked {
            open_app_details();
        }
        cx.notify();
    }

    // ---- Event handling -------------------------------------------------

    fn handle(&mut self, event: RawEvent, cx: &mut Context<Self>) {
        match event.kind.as_str() {
            kind::CALLS => match serde_json::from_str::<CallSnapshot>(&event.json) {
                Ok(snapshot) => self.apply_snapshot(snapshot, cx),
                Err(error) => log::warn!("bad calls event: {error}"),
            },
            kind::PERMISSIONS_CHANGED => {
                self.refresh_permissions(cx);
                self.refresh_sims(cx);
                self.refresh_facts(cx);
                self.refresh_call_log(cx);
                self.refresh_contacts(cx);
                self.refresh_blocklist(cx);
            }
            kind::BLOCKED_CHANGED => {
                self.refresh_blocklist(cx);
                if self.blocked_calls_wanted {
                    self.refresh_blocked_calls(cx);
                }
            }
            kind::KEYGUARD_DISMISSED => {
                self.keyguard_gen += 1;
                cx.notify();
            }
            kind::WA_PUSH => {
                self.wa_pushes += 1;
                cx.notify();
            }
            kind::WA_CALL_ACTION => {
                match rovsti_android::events::parse_wa_call_action(&event.json) {
                    Some(action) => {
                        self.wa_call_actions.push(action);
                        cx.notify();
                    }
                    None => log::warn!("bad wa_call_action event"),
                }
            }
            kind::HANDOFF => match rovsti_core::wire::parse_handoff(&event.json) {
                Some(handoff) => {
                    self.handoff = Some(handoff);
                    cx.notify();
                }
                None => log::warn!("ignoring a bad handoff"),
            },
            kind::SIMS_CHANGED => self.refresh_sims(cx),
            kind::DOCUMENT_RESULT => self.on_document_result(&event.json, cx),
            kind::UI_REQUEST => {
                match serde_json::from_str::<rovsti_core::wire::UiRequest>(&event.json) {
                    Ok(request) => {
                        self.ui_request = Some(request);
                        cx.notify();
                    }
                    Err(error) => log::warn!("bad ui_request: {error}"),
                }
            }
            kind::CALL_LOG_CHANGED => {
                self.refresh_call_log(cx);
                if self.blocked_calls_wanted {
                    self.refresh_blocked_calls(cx);
                }
            }
            kind::CONTACTS_CHANGED => self.refresh_contacts(cx),
            other => log::debug!("ignoring event {other}"),
        }
    }

    /// Applies a snapshot and wakes the views only when the scene they draw
    /// changed (AD-35): a snapshot that only moved `seq` or `now_ms` costs nothing.
    fn apply_snapshot(&mut self, snapshot: CallSnapshot, cx: &mut Context<Self>) {
        let diff = self.calls.apply(snapshot);
        if diff.changed() {
            self.scene_diff = self.scene_diff.merge(diff);
            cx.notify();
        }
    }

    fn refresh_permissions(&mut self, cx: &mut Context<Self>) {
        self.permissions_pull += 1;
        let pull = self.permissions_pull;
        let task = cx.spawn(async move |this, cx| {
            let facts = cx
                .background_executor()
                .spawn(async { load_permissions() })
                .await;
            this.update(cx, |this, cx| {
                if this.permissions_pull == pull
                    && (this.permissions != facts || !this.permissions_known)
                {
                    this.permissions = facts;
                    this.permissions_known = true;
                    cx.notify();
                }
                this.resolve_pending_call(pull, cx);
            })
            .ok();
        });
        task.detach();
    }

    /// A requested Phone permission was answered: place the waiting call, or
    /// mark it blocked. Only pulls started after the request may judge it.
    fn resolve_pending_call(&mut self, pull: u64, cx: &mut Context<Self>) {
        let Some((_, requested_at)) = &self.pending_call else {
            return;
        };
        if pull <= *requested_at || self.permissions_pull != pull {
            return;
        }
        let Some((raw, _)) = self.pending_call.take() else {
            return;
        };
        if self.permissions.phone.is_granted() {
            self.last_place = None;
            self.place_now(&raw, cx);
        } else {
            self.call_blocked = true;
        }
        cx.notify();
    }

    fn refresh_call_log(&mut self, cx: &mut Context<Self>) {
        self.log_pull += 1;
        let pull = self.log_pull;
        let blocked = self.block_set();
        let task = cx.spawn(async move |this, cx| {
            let load = cx
                .background_executor()
                .spawn(async move { load_call_log(&blocked) })
                .await;
            this.update(cx, |this, cx| {
                if this.log_pull == pull && !same_call_log(&this.call_log, &load) {
                    this.call_log = load;
                    // The list may have changed while this pull ran.
                    this.refilter_call_log();
                    cx.notify();
                }
            })
            .ok();
        });
        task.detach();
    }

    /// Story 8.5: writes `STARRED` off the UI thread, then re-pulls Contacts
    /// and Favorites. Resolves to whether Android accepted the write.
    pub fn set_starred(
        &mut self,
        contact_id: i64,
        starred: bool,
        cx: &mut Context<Self>,
    ) -> Task<bool> {
        cx.spawn(async move |this, cx| {
            let accepted = cx
                .background_executor()
                .spawn(async move { write_starred(contact_id, starred) })
                .await;
            if accepted {
                this.update(cx, |this, cx| this.refresh_contacts(cx)).ok();
            }
            accepted
        })
    }

    /// Deletes call-log rows by id off the UI thread, then re-pulls Recents.
    pub fn delete_calls(&mut self, ids: Vec<i64>, cx: &mut Context<Self>) -> Task<WriteOutcome> {
        self.call_log_write(move || write_delete_calls(&ids), cx)
    }

    /// Undo of a delete: re-inserts the rows (the provider assigns new ids).
    pub fn restore_calls(
        &mut self,
        entries: Vec<CallLogEntry>,
        cx: &mut Context<Self>,
    ) -> Task<WriteOutcome> {
        self.call_log_write(move || write_restore_calls(&entries), cx)
    }

    /// Deletes the whole call log.
    pub fn delete_all_calls(&mut self, cx: &mut Context<Self>) -> Task<WriteOutcome> {
        self.call_log_write(write_delete_all_calls, cx)
    }

    fn call_log_write(
        &mut self,
        write: impl FnOnce() -> WriteOutcome + Send + 'static,
        cx: &mut Context<Self>,
    ) -> Task<WriteOutcome> {
        cx.spawn(async move |this, cx| {
            let outcome = cx.background_executor().spawn(async move { write() }).await;
            if matches!(outcome, WriteOutcome::Done(_)) {
                this.update(cx, |this, cx| {
                    this.refresh_call_log(cx);
                    if this.blocked_calls_wanted {
                        this.refresh_blocked_calls(cx);
                    }
                })
                .ok();
            }
            outcome
        })
    }

    fn refresh_contacts(&mut self, cx: &mut Context<Self>) {
        self.contacts_pull += 1;
        let pull = self.contacts_pull;
        let task = cx.spawn(async move |this, cx| {
            let (contacts, favorites) = cx
                .background_executor()
                .spawn(async { (load_contacts(), load_favorites()) })
                .await;
            this.update(cx, |this, cx| {
                if this.contacts_pull == pull {
                    this.contacts = contacts;
                    this.favorites = favorites;
                    cx.notify();
                }
            })
            .ok();
        });
        task.detach();
    }
}

// ---- Stateless actions (no Backend needed, callable from event handlers) --

pub fn xiaomi() -> Option<XiaomiOs> {
    // Desktop design preview of the Xiaomi rows: ROVSTI_PREVIEW_XIAOMI=1.
    #[cfg(not(target_os = "android"))]
    if std::env::var_os("ROVSTI_PREVIEW_XIAOMI").is_some() {
        return Some(XiaomiOs::Miui {
            version: "preview".to_owned(),
        });
    }
    xiaomi::detect()
}

pub fn open_xiaomi(setting: XiaomiSetting) -> Outcome {
    Outcome::from(xiaomi::open(setting), "opening a Xiaomi setting")
}

/// Tells Kotlin the Incoming Call screen drew its first frame (story 5.6):
/// it logs the ring latency under `RovstiPerf`. Fire and forget.
pub fn incoming_frame(call_id: i32) {
    if let Err(error) = calls::incoming_frame(call_id)
        && !matches!(error, Error::Unsupported)
    {
        log::debug!("incoming_frame: {error}");
    }
}

/// A slow-frame report (story 11.10): logcat tag `RovstiPerf` through Kotlin on
/// the phone, the `log` facade on desktop. Rare (once per streak).
pub fn perf_line(line: &str) {
    match calls::perf_log(line) {
        Ok(_) => {}
        Err(_) => {
            log::warn!(target: "RovstiPerf", "{line}");
            // No logcat here: the desktop shows the report on stderr.
            #[cfg(not(target_os = "android"))]
            eprintln!("RovstiPerf {line}");
        }
    }
}

/// Sends the app's task to the background without finishing it (AD-3a).
pub fn move_task_to_back() -> Outcome {
    Outcome::from(
        rovsti_android::window::move_task_to_back(),
        "moving to back",
    )
}

pub fn request_dialer_role() -> Outcome {
    Outcome::from(
        roles::request(roles::Role::Dialer),
        "requesting the dialer role",
    )
}

pub fn request_battery_exemption() -> Outcome {
    Outcome::from(
        xiaomi::request_ignore_battery_optimizations(),
        "requesting the battery exemption",
    )
}

pub fn request_permission(group: Group) -> Outcome {
    Outcome::from(permissions::request(group), "requesting a permission")
}

/// Opens the system "add contact" screen with `number` filled in.
pub fn add_contact(number: &str) -> Outcome {
    Outcome::from(telecom::add_contact(number), "adding a contact")
}

/// Opens the system SMS app on `number` (an empty body).
pub fn send_sms_to(number: &str) -> Outcome {
    Outcome::from(telecom::send_sms_to(number), "message")
}

/// Puts `text` on the system clipboard.
pub fn copy_text(text: &str) -> Outcome {
    Outcome::from(telecom::copy_text(text), "copying")
}

/// Opens the system share sheet with `text`.
pub fn share_text(text: &str) -> Outcome {
    Outcome::from(telecom::share_text(text), "sharing")
}

/// Side length of the photo shown in the full-screen viewer.
pub const FULL_PHOTO_SIZE: u32 = 1080;

/// Saves the image file at `path` to the gallery (Pictures/Rovsti) as `name`.
pub fn save_to_gallery(path: &std::path::Path, name: &str) -> Outcome {
    Outcome::from(
        media::save_image_to_gallery(&path.to_string_lossy(), name, "image/png"),
        "saving the photo",
    )
}

/// Opens the share sheet with the image file at `path`.
pub fn share_image(path: &std::path::Path) -> Outcome {
    Outcome::from(
        media::share_image(&path.to_string_lossy(), "image/png", ""),
        "sharing the photo",
    )
}

/// Opens "save as" for the call log CSV; the answer is a `document_result`.
pub fn export_calls(name: &str, csv: &str) -> Outcome {
    Outcome::from(documents::export_calls(name, csv), "exporting calls")
}

/// A contact's photo at up to [`FULL_PHOTO_SIZE`] px with the PNG file it was
/// read from, so save and share have a path. Blocks: only call it on the
/// background executor. Desktop paints the sample portrait into a temp PNG
/// (save and share then answer not available).
pub fn full_photo(uri: &str) -> Option<(image::RgbaImage, std::path::PathBuf)> {
    match providers::contact_photo(uri, FULL_PHOTO_SIZE) {
        Ok(path) if path.is_empty() => None,
        Ok(path) => {
            let bytes = std::fs::read(&path).ok()?;
            let image = image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
                .map_err(|error| log::debug!("photo {path}: {error}"))
                .ok()?
                .into_rgba8();
            Some((image, path.into()))
        }
        #[cfg(not(target_os = "android"))]
        Err(Error::Unsupported) => {
            let image = sample::photo(uri, FULL_PHOTO_SIZE)?;
            let name: String = uri.chars().filter(char::is_ascii_alphanumeric).collect();
            let path = std::env::temp_dir().join(format!("rovsti-phone-photo-{name}.png"));
            image
                .save_with_format(&path, image::ImageFormat::Png)
                .ok()?;
            Some((image, path))
        }
        Err(error) => {
            log::debug!("full photo failed: {error}");
            None
        }
    }
}

/// Opens the system Contacts app.
pub fn open_contacts_app() -> Outcome {
    Outcome::from(telecom::open_contacts(), "open contacts")
}

/// Opens the system Contacts app on a contact (story 8.4).
pub fn edit_contact(contact_id: i64) -> Outcome {
    Outcome::from(telecom::edit_contact(contact_id), "edit contact")
}

/// Story 10.5: a contact's photo as RGBA pixels of at most 128 px, or `None`
/// (no photo, unreadable). Blocks on provider reads and image decoding: only
/// call it on the background executor. Kotlin writes a small PNG into the
/// app's cache directory and the seam moves its path as a string; the desktop
/// preview paints a generated portrait for `sample:` URIs.
pub fn contact_photo(uri: &str) -> Option<image::RgbaImage> {
    match providers::contact_photo(uri, crate::photos::SIZE) {
        Ok(path) if path.is_empty() => None,
        Ok(path) => {
            let bytes = std::fs::read(&path).ok()?;
            image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
                .map_err(|error| log::debug!("photo {path}: {error}"))
                .ok()
                .map(image::DynamicImage::into_rgba8)
        }
        #[cfg(not(target_os = "android"))]
        Err(Error::Unsupported) => sample::photo(uri, crate::photos::SIZE),
        Err(error) => {
            log::debug!("contact photo failed: {error}");
            None
        }
    }
}

pub fn open_app_details() -> Outcome {
    Outcome::from(permissions::open_app_details(), "opening app details")
}

pub fn open_default_apps() -> Outcome {
    Outcome::from(permissions::open_default_apps(), "opening default apps")
}

pub fn open_full_screen_intent_settings() -> Outcome {
    Outcome::from(
        permissions::open_full_screen_intent_settings(),
        "opening full-screen intent settings",
    )
}

fn route_name(route: AudioRoute) -> Option<&'static str> {
    match route {
        AudioRoute::Earpiece => Some("earpiece"),
        AudioRoute::Speaker => Some("speaker"),
        AudioRoute::Bluetooth => Some("bluetooth"),
        AudioRoute::WiredHeadset => Some("wired_headset"),
        AudioRoute::Unknown => None,
    }
}

// ---- Pulls (run on the background executor) -------------------------------

fn load_permissions() -> PermissionFacts {
    let access = |group| match permissions::state(group) {
        Ok(access) => access,
        Err(Error::Unsupported) => Access::Granted,
        Err(_) => Access::Denied,
    };
    // Desktop and not-yet-attached both fall back to the defaults.
    #[cfg_attr(target_os = "android", allow(unused_mut))]
    let mut desktop = PermissionFacts::default();
    // Desktop design preview of Preview mode: ROVSTI_PREVIEW_ROLE=0.
    #[cfg(not(target_os = "android"))]
    if std::env::var_os("ROVSTI_PREVIEW_ROLE").is_some_and(|v| v == "0") {
        desktop.dialer_role = false;
    }
    PermissionFacts {
        phone: access(Group::Phone),
        contacts: access(Group::Contacts),
        notifications: access(Group::Notifications),
        microphone: access(Group::Microphone),
        full_screen_intent: permissions::can_use_full_screen_intent()
            .unwrap_or(desktop.full_screen_intent),
        dialer_role: roles::is_held(roles::Role::Dialer).unwrap_or(desktop.dialer_role),
        battery_exempt: xiaomi::is_ignoring_battery_optimizations()
            .unwrap_or(desktop.battery_exempt),
        autostart: op(xiaomi::Op::Autostart),
        lock_screen: op(xiaomi::Op::ShowOnLockScreen),
        background_popups: op(xiaomi::Op::BackgroundPopups),
    }
}

/// A Xiaomi permission reading; anything unreadable is `None`.
fn op(op: xiaomi::Op) -> Option<bool> {
    xiaomi::op_allowed(op).ok().flatten()
}

/// Parses an envelope and maps failures to a [`Load`] state.
fn decode<T: DeserializeOwned + Default, U>(json: &str, build: impl FnOnce(T) -> U) -> Load<U> {
    match serde_json::from_str::<Envelope<T>>(json).map(Envelope::into_result) {
        Ok(Ok(data)) => Load::Ready(Arc::new(build(data))),
        Ok(Err(WireError::Permission)) => Load::PermissionDenied,
        Ok(Err(_)) => Load::Unavailable,
        Err(error) => {
            log::warn!("bad provider payload: {error}");
            Load::Unavailable
        }
    }
}

/// Maps a pull result: Android data, sample data on desktop, or a failure state.
fn load<U>(
    pulled: rovsti_android::Result<String>,
    parse: impl FnOnce(&str) -> Load<U>,
    sample: impl FnOnce() -> U,
) -> Load<U> {
    match pulled {
        Ok(json) => parse(&json),
        Err(Error::Unsupported) => Load::Ready(Arc::new(sample())),
        // The Kotlin side is not attached yet; permissions_changed retries.
        Err(Error::NotInitialized) => Load::Loading,
        // Only Android has more error kinds (JNI failures).
        #[allow(unreachable_patterns)]
        Err(error) => {
            log::warn!("provider pull failed: {error}");
            Load::Unavailable
        }
    }
}

/// The pull brought nothing new: the same entries and clock style. The app
/// keeps its snapshot (and its row view-models) and draws no frame for it.
fn same_call_log(current: &Load<CallLog>, pulled: &Load<CallLog>) -> bool {
    match (current, pulled) {
        (Load::Ready(old), Load::Ready(new)) => {
            Arc::ptr_eq(old, new)
                || (old.source == new.source
                    && old.entries == new.entries
                    && old.locale == new.locale)
        }
        _ => false,
    }
}

fn build_call_log(entries: Vec<CallLogEntry>, locale: LocaleTime) -> CallLog {
    build_call_log_with(Arc::new(entries), locale, &BlockSet::default())
}

/// Builds the visible entries and rows: a number on the blocklist leaves
/// Recents, the badge and the Frequent row at once (FR-53).
fn build_call_log_with(
    source: Arc<Vec<CallLogEntry>>,
    locale: LocaleTime,
    blocked: &BlockSet,
) -> CallLog {
    let entries: Vec<CallLogEntry> = if blocked.is_empty() {
        source.as_ref().clone()
    } else {
        // One check per distinct number: a log repeats the same few callers.
        let mut known: std::collections::HashMap<&str, bool> = std::collections::HashMap::new();
        source
            .iter()
            .filter(|e| {
                !*known
                    .entry(e.number_raw.as_str())
                    .or_insert_with(|| blocked.is_blocked(&e.number_raw))
            })
            .cloned()
            .collect()
    };
    let rows = call_log::group(&entries);
    let home_cc =
        rovsti_core::number_format::infer_home_cc(source.iter().map(|e| e.number_raw.as_str()));
    CallLog {
        source,
        entries,
        rows,
        locale,
        home_cc,
    }
}

/// The phone's clock style; the default (24 h, UTC) when it cannot be read.
fn local_time() -> LocaleTime {
    match providers::local_time_info() {
        Ok(text) => LocaleTime::parse(&text).unwrap_or_default(),
        #[cfg(not(target_os = "android"))]
        Err(Error::Unsupported) => sample::desktop_locale(),
        Err(_) => LocaleTime::default(),
    }
}

fn load_call_log(blocked: &BlockSet) -> Load<CallLog> {
    // Desktop design preview of the Recents states.
    #[cfg(not(target_os = "android"))]
    match std::env::var("ROVSTI_PREVIEW_RECENTS").as_deref() {
        Ok("loading") => return Load::Loading,
        Ok("denied") => return Load::PermissionDenied,
        Ok("unavailable") => return Load::Unavailable,
        Ok("empty") => return Load::Ready(Arc::new(build_call_log(Vec::new(), local_time()))),
        _ => {}
    }
    let locale = local_time();
    let result = load(
        providers::call_log(call_log::MAX_ENTRIES),
        |json| {
            decode(json, |p: CallLogPayload| {
                build_call_log_with(Arc::new(p.entries), locale, blocked)
            })
        },
        || {
            build_call_log_with(
                Arc::new(sample::preview_call_log(now_ms())),
                locale,
                blocked,
            )
        },
    );
    if let Load::Ready(log) = &result {
        log::info!(
            "call log: {} entries, {} rows",
            log.entries.len(),
            log.rows.len()
        );
    }
    result
}

fn load_contacts() -> Load<ContactIndex> {
    let result = load(
        providers::contacts(),
        |json| decode(json, |p: ContactsPayload| ContactIndex::new(p.contacts)),
        || ContactIndex::new(sample::contacts()),
    );
    if let Load::Ready(index) = &result {
        log::info!("contacts: {}", index.len());
    }
    result
}

/// The provider write; the desktop preview flips the sample data instead
/// (`ROVSTI_PREVIEW_STAR_FAIL=1` rehearses a refused write).
fn write_starred(contact_id: i64, starred: bool) -> bool {
    match providers::set_starred(contact_id, starred) {
        Ok(accepted) => accepted,
        #[cfg(not(target_os = "android"))]
        Err(Error::Unsupported) => {
            if std::env::var_os("ROVSTI_PREVIEW_STAR_FAIL").is_some() {
                return false;
            }
            std::thread::sleep(Duration::from_millis(120));
            sample::set_starred(contact_id, starred);
            true
        }
        Err(error) => {
            log::warn!("set starred failed: {error}");
            false
        }
    }
}

/// What a call-log write came to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WriteOutcome {
    /// The write went through; how many rows changed.
    Done(u32),
    /// The call-log write permission is missing.
    Permission,
    /// The system refused, or the write failed.
    Refused,
}

fn outcome_of(result: Result<String, Error>) -> WriteOutcome {
    match result.map(|json| wire::parse_write_count(&json)) {
        Ok(Ok(count)) => WriteOutcome::Done(count),
        Ok(Err(WriteError::Permission)) => WriteOutcome::Permission,
        Ok(Err(error)) => {
            log::warn!("call-log write failed: {error:?}");
            WriteOutcome::Refused
        }
        Err(error) => {
            log::warn!("call-log write failed: {error}");
            WriteOutcome::Refused
        }
    }
}

/// `ROVSTI_PREVIEW_CALLLOG_FAIL=1` rehearses a missing permission on desktop.
#[cfg(not(target_os = "android"))]
fn preview_call_write(apply: impl FnOnce() -> u32) -> WriteOutcome {
    if std::env::var_os("ROVSTI_PREVIEW_CALLLOG_FAIL").is_some() {
        return WriteOutcome::Permission;
    }
    std::thread::sleep(Duration::from_millis(120));
    WriteOutcome::Done(apply())
}

fn write_delete_calls(ids: &[i64]) -> WriteOutcome {
    match providers::delete_call_log(ids) {
        #[cfg(not(target_os = "android"))]
        Err(Error::Unsupported) => preview_call_write(|| sample::delete_calls(ids, now_ms())),
        result => outcome_of(result),
    }
}

fn write_restore_calls(entries: &[CallLogEntry]) -> WriteOutcome {
    match providers::restore_call_log(&wire::call_log_restore_json(entries)) {
        #[cfg(not(target_os = "android"))]
        Err(Error::Unsupported) => preview_call_write(|| sample::restore_calls(entries.to_vec())),
        result => outcome_of(result),
    }
}

fn write_delete_all_calls() -> WriteOutcome {
    match providers::delete_all_call_log() {
        #[cfg(not(target_os = "android"))]
        Err(Error::Unsupported) => preview_call_write(|| sample::clear_calls(now_ms())),
        result => outcome_of(result),
    }
}

fn load_favorites() -> Load<Vec<WireContact>> {
    load(
        providers::favorites(),
        |json| decode(json, |p: FavoritesPayload| p.contacts),
        sample::favorites,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn desktop_permission_facts_have_a_microphone() {
        assert!(PermissionFacts::default().microphone.is_granted());
    }

    const LOG_OK: &str =
        include_str!("../../../crates/rovsti_core/tests/fixtures/call_log_ok.json");
    const LOG_DENIED: &str =
        include_str!("../../../crates/rovsti_core/tests/fixtures/call_log_permission.json");
    const CONTACTS_OK: &str =
        include_str!("../../../crates/rovsti_core/tests/fixtures/contacts_ok.json");

    #[test]
    fn call_log_envelope_becomes_rows() {
        let loaded = decode(LOG_OK, |p: CallLogPayload| {
            build_call_log(p.entries, LocaleTime::default())
        });
        let Load::Ready(log) = loaded else {
            panic!("expected data");
        };
        assert_eq!(log.entries.len(), 4);
        assert!(!log.rows.is_empty());
    }

    #[test]
    fn permission_error_maps_to_permission_denied() {
        let loaded = decode(LOG_DENIED, |p: CallLogPayload| {
            build_call_log(p.entries, LocaleTime::default())
        });
        assert!(matches!(loaded, Load::PermissionDenied));
    }

    #[test]
    fn garbage_maps_to_unavailable() {
        let loaded = decode("not json", |p: CallLogPayload| p.entries);
        assert!(matches!(loaded, Load::Unavailable));
        let loaded = decode(
            r#"{"v":1,"ok":false,"error":"boom"}"#,
            |p: CallLogPayload| p.entries,
        );
        assert!(matches!(loaded, Load::Unavailable));
    }

    #[test]
    fn contacts_envelope_builds_an_index() {
        let Load::Ready(index) = decode(CONTACTS_OK, |p: ContactsPayload| {
            ContactIndex::new(p.contacts)
        }) else {
            panic!("expected data");
        };
        assert_eq!(index.len(), 2);
    }

    #[test]
    fn an_unchanged_pull_keeps_the_snapshot() {
        let locale = LocaleTime::default();
        let entries = sample::long_call_log(1_700_000_000_000, 40);
        let current = Load::Ready(Arc::new(build_call_log(entries.clone(), locale)));
        let same = Load::Ready(Arc::new(build_call_log(entries.clone(), locale)));
        assert!(
            same_call_log(&current, &same),
            "same entries: no new snapshot"
        );
        let mut newer = entries;
        newer.pop();
        let changed = Load::Ready(Arc::new(build_call_log(newer, locale)));
        assert!(!same_call_log(&current, &changed));
        assert!(
            !same_call_log(&Load::Loading, &same),
            "a first load always lands"
        );
        assert!(!same_call_log(&current, &Load::Unavailable));
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_serves_sample_data() {
        assert!(matches!(
            load_call_log(&BlockSet::default()),
            Load::Ready(_)
        ));
        assert!(matches!(load_contacts(), Load::Ready(_)));
        let Load::Ready(favorites) = load_favorites() else {
            panic!("expected sample favorites");
        };
        assert!(favorites.iter().all(|c| c.starred));
        assert_eq!(load_permissions(), PermissionFacts::default());
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_actions_are_not_available() {
        assert_eq!(request_dialer_role(), Outcome::NotAvailable);
        assert_eq!(open_xiaomi(XiaomiSetting::Autostart), Outcome::NotAvailable);
        assert_eq!(open_app_details(), Outcome::NotAvailable);
        assert!(xiaomi().is_none());
    }

    #[test]
    fn route_names_cover_known_routes() {
        assert_eq!(route_name(AudioRoute::Speaker), Some("speaker"));
        assert_eq!(route_name(AudioRoute::Unknown), None);
    }
}
