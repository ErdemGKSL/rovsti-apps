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
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use futures::StreamExt;
use gpui::{App, AppContext as _, Context, Entity, Task};
use rovsti_android::{
    Error, calls,
    events::{self, RawEvent, kind},
    permissions, providers, roles, telecom, xiaomi,
};
use rovsti_core::{
    call::CallModel,
    call_log::{self, RecentsRow},
    recents::LocaleTime,
    search::ContactIndex,
    setup::{self, Reading, SetupChecklist, SetupFacts, SetupItem, XiaomiConfirmations},
    wire::{
        AudioRoute, CallLogEntry, CallLogPayload, CallSnapshot, ContactsPayload, Envelope,
        FavoritesPayload, WireContact, WireError,
    },
};
use rovsti_ui::storage::{self, Prefs};
use serde::de::DeserializeOwned;

use crate::sample;

#[cfg(any(not(target_os = "android"), feature = "perf", test))]
mod preview;

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
    pub entries: Vec<CallLogEntry>,
    pub rows: Vec<RecentsRow>,
    /// Clock style of the phone, for the row time labels.
    pub locale: LocaleTime,
}

/// What Setup needs to know about the phone (FR-5, FR-6).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PermissionFacts {
    pub phone: Access,
    pub contacts: Access,
    pub notifications: Access,
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
}

/// A second Call press inside this window is ignored.
const PLACE_DEBOUNCE: Duration = Duration::from_millis(800);

pub struct Backend {
    calls: CallModel,
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
    /// The preferences were read (or could not be): the app may now pick its
    /// first tab.
    prefs_ready: bool,
    data_dir: Option<PathBuf>,
    /// Newest pull wins: a slow older pull never overwrites a newer one.
    log_pull: u64,
    contacts_pull: u64,
    permissions_pull: u64,
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
            let mut events_rx = events::install_sink();
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
                last_place: None,
                pending_call: None,
                call_blocked: false,
                call_log: Load::Loading,
                contacts: Load::Loading,
                favorites: Load::Loading,
                permissions: PermissionFacts::default(),
                permissions_known: false,
                prefs: Prefs::default(),
                prefs_ready: false,
                data_dir: None,
                log_pull: 0,
                contacts_pull: 0,
                permissions_pull: 0,
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
                        cx.notify();
                    })
                    .ok();
                    return;
                };
                let load_dir = dir.clone();
                let mut prefs = cx
                    .background_executor()
                    .spawn(async move { Prefs::load(&load_dir) })
                    .await;
                let changed = prefs.app_version != APP_VERSION;
                if changed {
                    // Xiaomi confirmations are valid for one app version only.
                    prefs.xiaomi_confirmed.clear();
                    prefs.app_version = APP_VERSION.to_owned();
                }
                this.update(cx, |this: &mut Backend, cx| {
                    this.prefs = prefs;
                    this.prefs_ready = true;
                    this.data_dir = Some(dir);
                    if changed {
                        this.save_prefs(cx);
                    }
                    cx.notify();
                })
                .ok();
            });
            backend._tasks.push(drain);
            backend._tasks.push(prefs);
            // The sink belongs to this session; clear it when the session ends.
            cx.on_release(|_, _| events::clear_sink()).detach();
            // (3) permissions and providers.
            backend.refresh_permissions(cx);
            backend.refresh_call_log(cx);
            backend.refresh_contacts(cx);
            backend
        })
    }

    // ---- Reads: views take Arc snapshots -------------------------------

    pub fn calls(&self) -> &CallModel {
        &self.calls
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
            prefs.missed_seen_ms = prefs.missed_seen_ms.max(seen)
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
        for (key, version) in &self.prefs.xiaomi_confirmed {
            if let Some(item) = SetupItem::from_key(key) {
                confirmations.confirm(item, version);
            }
        }
        setup::derive(facts, xiaomi, &confirmations, APP_VERSION)
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
                    .xiaomi_confirmed
                    .insert(item.key().to_owned(), APP_VERSION.to_owned());
            } else {
                prefs.xiaomi_confirmed.remove(item.key());
            }
        });
        cx.notify();
    }

    // ---- Preferences (written by Rust only, off the main thread) --------

    pub fn update_prefs(&mut self, cx: &mut Context<Self>, f: impl FnOnce(&mut Prefs)) {
        let before = self.prefs.clone();
        f(&mut self.prefs);
        if self.prefs != before {
            self.save_prefs(cx);
        }
    }

    fn save_prefs(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.data_dir.clone() else {
            return;
        };
        let prefs = self.prefs.clone();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = prefs.save(&dir) {
                    log::warn!("saving preferences failed: {error}");
                }
            })
            .detach();
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
            CallCommand::Dtmf(digit) => calls::dtmf(digit),
            CallCommand::DtmfStop => calls::dtmf_stop(),
            CallCommand::AnswerReplacing(id) => calls::answer_replacing(id),
        };
        let outcome = Outcome::from(result, "call command");
        #[cfg(any(not(target_os = "android"), feature = "perf", test))]
        if outcome == Outcome::NotAvailable {
            self.preview_command(command, cx);
        }
        let _ = cx;
        outcome
    }

    /// Perf runs (story 12.2): a 500-entry call log, so the scroll scenario
    /// has the same rows on every phone.
    #[cfg(any(feature = "perf", test))]
    pub fn perf_big_log(&mut self, cx: &mut Context<Self>) {
        let log = build_call_log(sample::long_call_log(now_ms(), 500), local_time());
        self.call_log = Load::Ready(Arc::new(log));
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

    fn place_now(&mut self, raw: &str, cx: &mut Context<Self>) -> Placement {
        match telecom::place_call(raw) {
            Ok(true) => Placement::Placed,
            Ok(false) => Placement::Failed,
            Err(Error::Unsupported) => {
                #[cfg(not(target_os = "android"))]
                self.preview_start(raw, cx);
                let _ = cx;
                Placement::Placed
            }
            Err(error) => {
                log::warn!("placing a call failed: {error}");
                Placement::Failed
            }
        }
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
                Ok(snapshot) => {
                    if self.calls.apply(snapshot) {
                        cx.notify();
                    }
                }
                Err(error) => log::warn!("bad calls event: {error}"),
            },
            kind::PERMISSIONS_CHANGED => {
                self.refresh_permissions(cx);
                self.refresh_call_log(cx);
                self.refresh_contacts(cx);
            }
            kind::CALL_LOG_CHANGED => self.refresh_call_log(cx),
            kind::CONTACTS_CHANGED => self.refresh_contacts(cx),
            other => log::debug!("ignoring event {other}"),
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
        let task = cx.spawn(async move |this, cx| {
            let load = cx
                .background_executor()
                .spawn(async { load_call_log() })
                .await;
            this.update(cx, |this, cx| {
                if this.log_pull == pull && !same_call_log(&this.call_log, &load) {
                    this.call_log = load;
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
            Arc::ptr_eq(old, new) || (old.entries == new.entries && old.locale == new.locale)
        }
        _ => false,
    }
}

fn build_call_log(entries: Vec<CallLogEntry>, locale: LocaleTime) -> CallLog {
    let rows = call_log::group(&entries);
    CallLog {
        entries,
        rows,
        locale,
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

fn load_call_log() -> Load<CallLog> {
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
        |json| decode(json, |p: CallLogPayload| build_call_log(p.entries, locale)),
        || build_call_log(sample::preview_call_log(now_ms()), locale),
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
        assert!(matches!(load_call_log(), Load::Ready(_)));
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
