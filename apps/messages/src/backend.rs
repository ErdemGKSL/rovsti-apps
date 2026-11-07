//! Everything Rovsti Messages asks Android, and how the answers land.
//!
//! The [`Backend`] entity owns the process's one event sink (so it also hands
//! WhatsApp pushes to the app), pulls the SMS threads, SIMs, contacts, locale
//! and role on the background executor, re-pulls when Android says something
//! changed, and routes `sms_status` events to whoever is listening (the open
//! conversation). Every JNI call blocks, so none of them runs on the UI thread.
//!
//! On desktop the provider answers `Unsupported`; the backend then serves
//! [`preview`] sample data, so the app never shows an "Android only" wall.

pub mod preview;

use std::{
    collections::HashSet,
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use futures::StreamExt as _;
use gpui::{App, AppContext as _, Context, Entity, EventEmitter, Task};
use rovsti_android::{
    Error,
    events::{self, RawEvent, kind},
    permissions, providers, reminders,
    roles::{self, Role},
    sms,
};
use rovsti_core::{
    blocklist::BlockSet,
    recents::LocaleTime,
    schedule::{ScheduleError, ScheduleQueue, ScheduledSms},
    search::ContactIndex,
    sms::{
        DeliveryState, MmsRow, SmsMessagesPayload, SmsRow, SmsSend, SmsSimsPayload, SmsStatus,
        SmsThreadRow, SmsThreadsPayload,
    },
    sms_timeline::SmsKey,
    spam::SpamRules,
    wire::{
        BlockedNumber, BlocklistPayload, ContactsPayload, DocumentOp, DocumentResult, Envelope,
        Handoff, WireError, parse_handoff,
    },
};
use rovsti_ui::storage;
use serde::de::DeserializeOwned;

use crate::{
    notifications::{OpenTag, Reply, SmsReceived},
    prefs::MessagesPrefs,
    schedule_ui::{arm_json, parse_fired},
};
use preview::Preview;

/// How many threads the inbox pulls.
const THREAD_LIMIT: usize = 500;

/// The largest picture the attach button copies into the cache: no more than
/// the MMS send (`headless::mms_send_req`) will read.
const ATTACH_MAX_BYTES: u64 = crate::headless::MAX_ATTACHMENT_BYTES;

/// The local scheduled-SMS queue, next to the preferences.
const QUEUE_FILE: &str = "scheduled.json";

/// How many threads a backup (and a restore comparison) reads: every one.
const BACKUP_THREAD_LIMIT: usize = 1_000_000;

/// Rows per provider page while a backup reads whole threads.
const SNAPSHOT_PAGE: usize = 500;

/// What the backend tells its listeners. State changes arrive by `notify`.
#[derive(Clone, Debug)]
pub enum BackendEvent {
    /// The thread list was pulled again: an open conversation re-reads its newest page.
    SmsChanged,
    /// A send moved along (`sms_status`).
    Status(SmsStatus),
    /// A file picker answered (the attach button).
    Document(DocumentResult),
    /// An FCM wake-up for WhatsApp: the app reconnects its event stream.
    WaPush,
    /// An SMS arrived while the UI is attached: the app decides on a notification.
    SmsReceived(SmsReceived),
    /// Text typed into a notification's reply box.
    Reply(Reply),
    /// A notification was tapped (its tag).
    OpenThread(OpenTag),
    /// A sibling app asked to open a WhatsApp chat (`handoff`).
    Handoff(Handoff),
    /// Something the user should be told in a pill.
    Notice(String),
    /// A conversation was deleted (the thread id): pages and notifications of it go.
    ThreadDeleted(i64),
    /// The delete of one SMS/MMS ended: `ok` when the message is gone from
    /// the provider (the page drops it), otherwise it comes back.
    SmsMessageDeleted {
        thread_id: i64,
        key: SmsKey,
        ok: bool,
    },
    /// Scheduled sends that fired while no UI looked and failed.
    ScheduledFailed(Vec<ScheduledSms>),
    /// The armed message reminders changed (set, cancelled or fired).
    RemindersChanged,
}

impl EventEmitter<BackendEvent> for Backend {}

/// How a provider pull went.
#[derive(Debug)]
pub enum Pull<T> {
    Ready(T),
    /// The permission is missing.
    Denied,
    /// Desktop: there is no Android to ask.
    Unsupported,
    Failed(String),
}

/// Decodes one `Envelope<T>` answer of a provider pull.
pub fn decode<T: DeserializeOwned + Default>(raw: rovsti_android::Result<String>) -> Pull<T> {
    let json = match raw {
        Ok(json) => json,
        Err(Error::Unsupported) => return Pull::Unsupported,
        Err(error) => return Pull::Failed(error.to_string()),
    };
    match serde_json::from_str::<Envelope<T>>(&json) {
        Ok(envelope) => match envelope.into_result() {
            Ok(data) => Pull::Ready(data),
            Err(WireError::Permission) => Pull::Denied,
            Err(error) => Pull::Failed(format!("{error:?}")),
        },
        Err(error) => Pull::Failed(error.to_string()),
    }
}

/// The state of the thread list.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Access {
    Loading,
    Ready,
    /// `READ_SMS` is not granted.
    Denied,
    Unavailable,
}

/// The SIM a new message should use: the thread's remembered one while it is
/// still active, else the system default when active, else none (Android picks).
pub fn sim_for(sims: &SmsSimsPayload, remembered: Option<i32>) -> Option<i32> {
    let active = |id: &i32| sims.sims.iter().any(|s| s.sub_id == *id);
    remembered
        .filter(active)
        .or(sims.default_sub_id.filter(active))
        .or_else(|| (sims.sims.len() == 1).then(|| sims.sims[0].sub_id))
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// Pull generations: only the newest pull of each kind lands.
#[derive(Default)]
struct Pulls {
    threads: u64,
    sims: u64,
    contacts: u64,
    locale: u64,
    role: u64,
    blocked: u64,
}

pub struct Backend {
    threads: Vec<SmsThreadRow>,
    threads_state: Access,
    sims: SmsSimsPayload,
    contacts: Arc<ContactIndex>,
    /// Android's notification permission (`None` = unknown, or desktop).
    notify_access: Option<permissions::Access>,
    /// The last contacts pull was refused by Android.
    contacts_denied: bool,
    locale: LocaleTime,
    /// `None` until the first answer (the banner stays hidden meanwhile).
    sms_role: Option<bool>,
    /// Whether Android lets the app set exact alarms (`None` = unknown, or desktop).
    exact_alarms: Option<bool>,
    prefs: MessagesPrefs,
    /// Scheduled SMS waiting for their alarm (`scheduled.json`).
    queue: ScheduleQueue,
    blocked: Arc<BlockSet>,
    blocked_rows: Vec<BlockedNumber>,
    /// A write of the prefs and the queue is running; another is due after it.
    saving: bool,
    save_due: bool,
    /// Armed "Remind me" reminders on single messages.
    message_reminders: Vec<rovsti_core::message_reminder::MessageReminder>,
    /// Sample data, on desktop only.
    preview: Option<Preview>,
    preview_sims: u32,
    preview_role: bool,
    data_dir: Option<PathBuf>,
    next_local: u64,
    pulls: Pulls,
    /// The window is in front: Kotlin leaves notifications to the app, and
    /// what the open conversation shows counts as read.
    ui_active: bool,
    _tasks: Vec<Task<()>>,
}

impl Backend {
    /// Starts a session: installs the sink, pulls everything, drains events.
    pub fn start(cx: &mut App) -> Entity<Self> {
        rovsti_android::bridge::keep_symbols();
        let data_dir = storage::data_dir("messages", cx);
        cx.new(|cx| {
            let (mut events_rx, sink) = events::install_sink_owned();
            let desktop = !cfg!(target_os = "android");
            let mut backend = Self {
                threads: Vec::new(),
                threads_state: Access::Loading,
                sims: SmsSimsPayload::default(),
                contacts: Arc::new(ContactIndex::default()),
                notify_access: None,
                contacts_denied: false,
                locale: LocaleTime::default(),
                sms_role: None,
                exact_alarms: None,
                prefs: MessagesPrefs::default(),
                queue: ScheduleQueue::default(),
                blocked: Arc::new(BlockSet::default()),
                blocked_rows: Vec::new(),
                saving: false,
                save_due: false,
                message_reminders: Vec::new(),
                preview: desktop.then(|| Preview::new(now_ms())),
                preview_sims: std::env::var("ROVSTI_PREVIEW_SIMS")
                    .ok()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(2),
                preview_role: std::env::var_os("ROVSTI_PREVIEW_NO_ROLE").is_none(),
                data_dir: None,
                next_local: 1,
                pulls: Pulls::default(),
                ui_active: true,
                _tasks: Vec::new(),
            };
            let drain = cx.spawn(async move |this, cx| {
                while let Some(event) = events_rx.next().await {
                    if this
                        .update(cx, |this: &mut Backend, cx| this.on_event(event, cx))
                        .is_err()
                    {
                        break;
                    }
                }
                events::clear_sink_if(sink);
            });
            let dir = cx.spawn(async move |this, cx| {
                let dir = match data_dir.await {
                    Ok(dir) => dir,
                    Err(error) => {
                        log::warn!("no data directory for the SIM choices: {error}");
                        return;
                    }
                };
                let read_from = dir.clone();
                let saved = cx
                    .background_executor()
                    .spawn(async move {
                        let prefs = MessagesPrefs::load(&read_from);
                        let queue = storage::load_json::<ScheduleQueue>(&read_from, QUEUE_FILE)
                            .map(|q| ScheduleQueue::from_json(&q.to_json()))
                            .unwrap_or_default();
                        (prefs, queue)
                    })
                    .await;
                this.update(cx, |this: &mut Backend, cx| {
                    this.data_dir = Some(dir);
                    // A change made before the files were read is newer.
                    if !this.save_due {
                        (this.prefs, this.queue) = saved;
                    }
                    if this.save_due {
                        this.save_due = false;
                        this.persist(cx);
                    }
                    cx.notify();
                    this.reconcile_fired(cx);
                })
                .ok();
            });
            // Tell Kotlin the session is live: it stops posting its own
            // digests and hands over what queued while no UI was attached.
            let attach = cx.background_executor().spawn(async {
                if let Err(error) = rovsti_android::window::ui_attached(true)
                    && !matches!(error, Error::Unsupported)
                {
                    log::debug!("uiAttached failed: {error}");
                }
            });
            attach.detach();
            backend.load_message_reminders(cx);
            cx.on_release(move |_, _| {
                let _ = rovsti_android::window::ui_attached(false);
            })
            .detach();
            backend._tasks = vec![drain, dir];
            backend.refresh_all(cx);
            backend
        })
    }

    // ---- reads ------------------------------------------------------------

    pub fn threads(&self) -> &[SmsThreadRow] {
        &self.threads
    }

    pub fn threads_state(&self) -> Access {
        self.threads_state
    }

    pub fn sims(&self) -> &SmsSimsPayload {
        &self.sims
    }

    pub fn notify_access(&self) -> Option<permissions::Access> {
        self.notify_access
    }

    pub fn contacts_denied(&self) -> bool {
        self.contacts_denied
    }

    pub fn contacts(&self) -> &Arc<ContactIndex> {
        &self.contacts
    }

    pub fn locale(&self) -> LocaleTime {
        self.locale
    }

    /// Whether Rovsti Messages is the default SMS app. `true` while unknown, so
    /// the read-only banner never flashes at start.
    pub fn sms_role_held(&self) -> bool {
        self.sms_role.unwrap_or(true)
    }

    pub fn spam_rules(&self) -> &SpamRules {
        &self.prefs.spam
    }

    pub fn prefs(&self) -> &MessagesPrefs {
        &self.prefs
    }

    pub fn queue(&self) -> &ScheduleQueue {
        &self.queue
    }

    /// The app's private data directory, once it is known.
    pub fn data_dir(&self) -> Option<&std::path::Path> {
        self.data_dir.as_deref()
    }

    pub fn blocked_rows(&self) -> &[BlockedNumber] {
        &self.blocked_rows
    }

    pub fn blocked(&self) -> &Arc<BlockSet> {
        &self.blocked
    }

    pub fn is_preview(&self) -> bool {
        self.preview.is_some()
    }

    /// Whether the window is in front (see [`Self::set_ui_active`]).
    pub fn ui_active(&self) -> bool {
        self.ui_active
    }

    /// The window came to the front or went to the background. Kotlin is told
    /// (`uiAttached`), so in the background it posts its own WhatsApp digests
    /// and answers notification replies itself.
    pub fn set_ui_active(&mut self, active: bool, cx: &mut Context<Self>) {
        if self.ui_active == active {
            return;
        }
        self.ui_active = active;
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = rovsti_android::window::ui_attached(active)
                    && !matches!(error, Error::Unsupported)
                {
                    log::debug!("uiAttached failed: {error}");
                }
            })
            .detach();
        cx.notify();
    }

    pub fn sim_choice(&self, thread_id: i64) -> Option<i32> {
        self.prefs.sim_choices.get(&thread_id).copied()
    }

    pub fn next_local_id(&mut self) -> u64 {
        let id = self.next_local;
        self.next_local += 1;
        id
    }

    // ---- pulls ------------------------------------------------------------

    /// Everything, in parallel on the background executor.
    pub fn refresh_all(&mut self, cx: &mut Context<Self>) {
        self.pull_threads(cx);
        self.pull_sims(cx);
        self.pull_contacts(cx);
        self.pull_locale(cx);
        self.pull_role(cx);
        self.pull_blocked(cx);
        self.pull_access(cx);
        self.pull_exact_alarms(cx);
    }

    /// The window came back to the front: the user may have changed things in
    /// the system settings.
    pub fn on_resume(&mut self, cx: &mut Context<Self>) {
        let pulls = crate::access::resume_pulls(self.threads_state, self.contacts_denied);
        if pulls.role {
            self.pull_role(cx);
        }
        if pulls.access {
            self.pull_access(cx);
        }
        self.pull_exact_alarms(cx);
        if pulls.threads {
            self.pull_threads(cx);
        }
        if pulls.contacts {
            self.pull_contacts(cx);
        }
    }

    /// Reads Android's notification permission.
    pub fn pull_access(&mut self, cx: &mut Context<Self>) {
        let task = cx
            .background_executor()
            .spawn(async { permissions::state(permissions::Group::Notifications) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                let next = match result {
                    Ok(access) => Some(access),
                    Err(Error::Unsupported) => None,
                    Err(error) => {
                        log::warn!("reading the notification permission failed: {error}");
                        this.notify_access
                    }
                };
                if next != this.notify_access {
                    this.notify_access = next;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Asks for a permission: the system dialog, or App info when Android
    /// will not show it any more.
    pub fn ask_access(&mut self, group: permissions::Group, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async move {
            let open = || permissions::open_app_details().map(|_| ());
            if matches!(group, permissions::Group::Notifications) {
                match crate::access::ask_for(permissions::state(group)?) {
                    None => Ok(()),
                    Some(crate::access::Ask::Request) => {
                        if permissions::request(group)? {
                            Ok(())
                        } else {
                            open()
                        }
                    }
                    Some(crate::access::Ask::OpenAppInfo) => open(),
                }
            } else {
                // Messages declares only READ_CONTACTS, but the group also
                // checks WRITE_CONTACTS, so its state cannot be trusted.
                if permissions::request(group)? {
                    Ok(())
                } else {
                    open()
                }
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |_, cx| match result {
                Ok(()) => {}
                Err(Error::Unsupported) => {
                    cx.emit(BackendEvent::Notice("That needs an Android phone.".into()));
                }
                Err(error) => {
                    log::warn!("asking for a permission failed: {error}");
                    cx.emit(BackendEvent::Notice(
                        "Couldn't open the permission settings".into(),
                    ));
                }
            })
            .ok();
        })
        .detach();
    }

    pub fn pull_threads(&mut self, cx: &mut Context<Self>) {
        self.pulls.threads += 1;
        let generation = self.pulls.threads;
        let task = cx
            .background_executor()
            .spawn(async { decode::<SmsThreadsPayload>(sms::threads(THREAD_LIMIT)) });
        cx.spawn(async move |this, cx| {
            let pulled = task.await;
            this.update(cx, |this, cx| {
                if this.pulls.threads != generation {
                    return;
                }
                this.apply_threads(pulled);
                cx.emit(BackendEvent::SmsChanged);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn apply_threads(&mut self, pulled: Pull<SmsThreadsPayload>) {
        match pulled {
            Pull::Ready(payload) => {
                self.threads = payload.threads;
                self.threads_state = Access::Ready;
            }
            Pull::Denied => {
                self.threads.clear();
                self.threads_state = Access::Denied;
            }
            Pull::Unsupported => {
                if let Some(preview) = &self.preview {
                    self.threads = preview.threads().threads;
                    self.threads_state = Access::Ready;
                } else {
                    self.threads_state = Access::Unavailable;
                }
            }
            Pull::Failed(error) => {
                log::warn!("pulling the SMS threads failed: {error}");
                if self.threads.is_empty() {
                    self.threads_state = Access::Unavailable;
                }
            }
        }
    }

    pub fn pull_sims(&mut self, cx: &mut Context<Self>) {
        self.pulls.sims += 1;
        let generation = self.pulls.sims;
        let task = cx
            .background_executor()
            .spawn(async { decode::<SmsSimsPayload>(sms::sims()) });
        cx.spawn(async move |this, cx| {
            let pulled = task.await;
            this.update(cx, |this, cx| {
                if this.pulls.sims != generation {
                    return;
                }
                match pulled {
                    Pull::Ready(sims) => this.sims = sims,
                    Pull::Unsupported => this.sims = Preview::sims(this.preview_sims),
                    Pull::Denied => this.sims = SmsSimsPayload::default(),
                    Pull::Failed(error) => log::warn!("pulling the SIMs failed: {error}"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub fn pull_contacts(&mut self, cx: &mut Context<Self>) {
        self.pulls.contacts += 1;
        let generation = self.pulls.contacts;
        let task = cx.background_executor().spawn(async {
            // Indexing is part of the background work.
            match decode::<ContactsPayload>(providers::contacts()) {
                Pull::Ready(p) => Some((ContactIndex::new(p.contacts), false)),
                Pull::Unsupported => Some((ContactIndex::new(Preview::contacts()), false)),
                Pull::Denied => Some((ContactIndex::default(), true)),
                Pull::Failed(error) => {
                    log::warn!("pulling the contacts failed: {error}");
                    None
                }
            }
        });
        cx.spawn(async move |this, cx| {
            let index = task.await;
            this.update(cx, |this, cx| {
                if this.pulls.contacts != generation {
                    return;
                }
                if let Some((index, denied)) = index {
                    this.contacts_denied = denied;
                    this.contacts = Arc::new(index);
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    pub fn pull_locale(&mut self, cx: &mut Context<Self>) {
        self.pulls.locale += 1;
        let generation = self.pulls.locale;
        let task = cx
            .background_executor()
            .spawn(async { providers::local_time_info() });
        cx.spawn(async move |this, cx| {
            let raw = task.await;
            this.update(cx, |this, cx| {
                if this.pulls.locale != generation {
                    return;
                }
                match raw {
                    Ok(text) => match LocaleTime::parse(&text) {
                        Some(locale) => this.locale = locale,
                        None => log::warn!("unreadable local time info: {text:?}"),
                    },
                    Err(Error::Unsupported) => {}
                    Err(error) => log::warn!("pulling the local time failed: {error}"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Reads whether exact alarms are allowed (the Send later sheet's late note).
    pub fn pull_exact_alarms(&mut self, cx: &mut Context<Self>) {
        let task = cx
            .background_executor()
            .spawn(async { rovsti_android::trip::can_schedule_exact_alarms() });
        cx.spawn(async move |this, cx| {
            let allowed = task.await;
            this.update(cx, |this, cx| {
                this.exact_alarms = match allowed {
                    Ok(allowed) => Some(allowed),
                    Err(Error::Unsupported) => None,
                    Err(error) => {
                        log::warn!("reading the exact alarm permission failed: {error}");
                        None
                    }
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Whether exact alarms are allowed, when known.
    pub fn exact_alarms(&self) -> Option<bool> {
        self.exact_alarms
    }

    pub fn pull_role(&mut self, cx: &mut Context<Self>) {
        self.pulls.role += 1;
        let generation = self.pulls.role;
        let task = cx
            .background_executor()
            .spawn(async { roles::is_held(Role::Sms) });
        cx.spawn(async move |this, cx| {
            let held = task.await;
            this.update(cx, |this, cx| {
                if this.pulls.role != generation {
                    return;
                }
                this.sms_role = match held {
                    Ok(held) => Some(held),
                    Err(Error::Unsupported) => Some(this.preview_role),
                    Err(error) => {
                        log::warn!("reading the SMS role failed: {error}");
                        None
                    }
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub fn pull_blocked(&mut self, cx: &mut Context<Self>) {
        self.pulls.blocked += 1;
        let generation = self.pulls.blocked;
        let task = cx
            .background_executor()
            .spawn(async { decode::<BlocklistPayload>(rovsti_android::blocklist::pull()) });
        cx.spawn(async move |this, cx| {
            let pulled = task.await;
            this.update(cx, |this, cx| {
                if this.pulls.blocked != generation {
                    return;
                }
                match pulled {
                    Pull::Ready(p) => {
                        this.blocked = Arc::new(BlockSet::from_rows(&p.numbers));
                        this.blocked_rows = p.numbers;
                    }
                    Pull::Denied | Pull::Unsupported => {
                        this.blocked = Arc::default();
                        this.blocked_rows.clear();
                    }
                    Pull::Failed(error) => log::warn!("pulling the blocklist failed: {error}"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// A page of one thread, newest first, off the UI thread.
    pub fn messages(
        &self,
        cx: &App,
        thread_id: i64,
        before_ms: Option<i64>,
        limit: usize,
    ) -> Task<Result<SmsMessagesPayload, String>> {
        if let Some(preview) = &self.preview {
            let page = preview.messages(thread_id, before_ms, limit);
            return Task::ready(Ok(page));
        }
        cx.background_executor().spawn(async move {
            match decode::<SmsMessagesPayload>(sms::messages(thread_id, before_ms, limit)) {
                Pull::Ready(page) => Ok(page),
                Pull::Denied => Err("SMS access is off".to_owned()),
                Pull::Unsupported => Err("this needs an Android phone".to_owned()),
                Pull::Failed(error) => Err(error),
            }
        })
    }

    // ---- events -----------------------------------------------------------

    fn on_event(&mut self, event: RawEvent, cx: &mut Context<Self>) {
        match event.kind.as_str() {
            kind::SMS_CHANGED => self.pull_threads(cx),
            kind::CONTACTS_CHANGED => self.pull_contacts(cx),
            kind::PERMISSIONS_CHANGED => self.refresh_all(cx),
            kind::SIMS_CHANGED => self.pull_sims(cx),
            kind::BLOCKED_CHANGED => self.pull_blocked(cx),
            kind::SMS_STATUS => match serde_json::from_str::<SmsStatus>(&event.json) {
                Ok(status) => cx.emit(BackendEvent::Status(status)),
                Err(error) => log::warn!("unreadable sms_status: {error}"),
            },
            kind::DOCUMENT_RESULT => match serde_json::from_str::<DocumentResult>(&event.json) {
                Ok(result) => cx.emit(BackendEvent::Document(result)),
                Err(error) => log::warn!("unreadable document_result: {error}"),
            },
            kind::WA_PUSH => cx.emit(BackendEvent::WaPush),
            kind::SMS_RECEIVED => match serde_json::from_str::<SmsReceived>(&event.json) {
                Ok(received) => cx.emit(BackendEvent::SmsReceived(received)),
                Err(error) => log::warn!("unreadable sms_received: {error}"),
            },
            kind::MSG_REPLY => match serde_json::from_str::<Reply>(&event.json) {
                Ok(reply) => cx.emit(BackendEvent::Reply(reply)),
                Err(error) => log::warn!("unreadable msg_reply: {error}"),
            },
            kind::OPEN_THREAD => match serde_json::from_str::<OpenTag>(&event.json) {
                Ok(open) => cx.emit(BackendEvent::OpenThread(open)),
                Err(error) => log::warn!("unreadable open_thread: {error}"),
            },
            kind::HANDOFF => match parse_handoff(&event.json) {
                Some(handoff) => cx.emit(BackendEvent::Handoff(handoff)),
                None => log::warn!("ignoring a bad handoff"),
            },
            kind::SCHEDULED_FIRED => self.reconcile_fired(cx),
            kind::MESSAGE_REMINDER_FIRED => self.on_message_reminder_fired(&event.json, cx),
            _ => {}
        }
    }

    // ---- actions ----------------------------------------------------------

    /// Asks for the SMS role; when the system dialog is hidden (some Xiaomi
    /// builds), opens Settings, Apps, Default apps instead.
    pub fn request_sms_role(&mut self, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async {
            match roles::request(Role::Sms) {
                Ok(true) => Ok(()),
                Ok(false) | Err(_) => permissions::open_default_apps().map(|_| ()),
            }
        });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await
                && !matches!(error, Error::Unsupported)
            {
                log::warn!("asking for the SMS role failed: {error}");
            }
            this.update(cx, |this, cx| this.pull_role(cx)).ok();
        })
        .detach();
        // Desktop preview: the role "arrives" at once.
        if self.preview.is_some() {
            self.preview_role = true;
            self.sms_role = Some(true);
            cx.notify();
        }
    }

    /// Opens this app's page in the system settings (to turn a permission on).
    pub fn open_app_settings(&mut self, cx: &mut Context<Self>) {
        cx.background_executor()
            .spawn(async {
                if let Err(error) = permissions::open_app_details()
                    && !matches!(error, Error::Unsupported)
                {
                    log::warn!("opening the app settings failed: {error}");
                }
            })
            .detach();
    }

    /// Marks a thread read, now in the list and then in the provider.
    pub fn mark_read(&mut self, thread_id: i64, cx: &mut Context<Self>) {
        if let Some(thread) = self.threads.iter_mut().find(|t| t.thread_id == thread_id) {
            if thread.unread == 0 {
                return;
            }
            thread.unread = 0;
            cx.notify();
        }
        if let Some(preview) = &mut self.preview {
            preview.mark_read(thread_id);
            return;
        }
        let task = cx
            .background_executor()
            .spawn(async move { sms::mark_read(thread_id) });
        cx.spawn(async move |_, _| {
            if let Ok(false) | Err(_) = task.await {
                log::debug!("marking thread {thread_id} read was refused");
            }
        })
        .detach();
    }

    /// Deletes an SMS conversation. Refuses without the SMS role (the preview
    /// has none to need). On success the list is pulled again, the thread's
    /// mute tag and SIM choice are dropped and `ThreadDeleted` is emitted;
    /// otherwise a `Notice` says why.
    pub fn delete_thread(&mut self, thread_id: i64, cx: &mut Context<Self>) {
        if let Some(preview) = &mut self.preview {
            preview.delete_thread(thread_id);
            self.threads.retain(|t| t.thread_id != thread_id);
            self.forget_thread_prefs(thread_id, cx);
            cx.emit(BackendEvent::ThreadDeleted(thread_id));
            cx.notify();
            return;
        }
        if !self.sms_role_held() {
            cx.emit(BackendEvent::Notice(crate::sms_actions::NEEDS_ROLE.into()));
            return;
        }
        let task = cx
            .background_executor()
            .spawn(async move { sms::delete_thread(thread_id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| match result {
                Ok(true) => {
                    this.threads.retain(|t| t.thread_id != thread_id);
                    this.forget_thread_prefs(thread_id, cx);
                    this.pull_threads(cx);
                    cx.emit(BackendEvent::ThreadDeleted(thread_id));
                    cx.notify();
                }
                Ok(false) | Err(_) => {
                    if let Err(error) = result {
                        log::warn!("deleting thread {thread_id} failed: {error}");
                    }
                    cx.emit(BackendEvent::Notice(
                        "Couldn't delete that conversation".into(),
                    ));
                }
            })
            .ok();
        })
        .detach();
    }

    /// Deletes one SMS/MMS. Refuses without the SMS role. A `Local` key has no
    /// provider row and is gone at once. The delete runs in the background;
    /// on success the thread list is pulled again, otherwise a `Notice` says
    /// why. `SmsMessageDeleted` tells the page either way.
    pub fn delete_sms_message(&mut self, thread_id: i64, key: SmsKey, cx: &mut Context<Self>) {
        let done = move |ok: bool| BackendEvent::SmsMessageDeleted { thread_id, key, ok };
        let Some(provider_id) = key.provider_id() else {
            cx.emit(done(true));
            return;
        };
        if let Some(preview) = &mut self.preview {
            let gone = match key {
                SmsKey::Sms(id) => preview.delete_message(id),
                _ => false,
            };
            if gone {
                self.threads = preview.threads().threads;
                self.forget_star(&provider_id, cx);
                cx.emit(BackendEvent::SmsChanged);
            }
            cx.emit(done(gone));
            cx.notify();
            return;
        }
        if !self.sms_role_held() {
            cx.emit(BackendEvent::Notice(crate::sms_actions::NEEDS_ROLE.into()));
            cx.emit(done(false));
            return;
        }
        let provider_id_for_star = provider_id.clone();
        let task = cx
            .background_executor()
            .spawn(async move { sms::delete_message(&provider_id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                let ok = matches!(result, Ok(true));
                if ok {
                    this.forget_star(&provider_id_for_star, cx);
                    this.pull_threads(cx);
                } else {
                    if let Err(error) = &result {
                        log::warn!("deleting message {key:?} failed: {error}");
                    }
                    cx.emit(BackendEvent::Notice("Couldn't delete that message".into()));
                }
                cx.emit(done(ok));
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Drops the local star of a deleted message.
    fn forget_star(&mut self, provider_id: &str, cx: &mut Context<Self>) {
        if self.prefs.sms_stars.is_starred(provider_id) {
            self.update_prefs(cx, |prefs| {
                prefs.sms_stars.forget(provider_id);
            });
        }
    }

    /// Drops what the preferences kept for a thread that is gone.
    fn forget_thread_prefs(&mut self, thread_id: i64, cx: &mut Context<Self>) {
        let tag = crate::notifications::thread_tag(&rovsti_core::ThreadId::Sms(thread_id));
        self.update_prefs(cx, |prefs| {
            prefs.set_muted(&tag, false);
            prefs.sim_choices.remove(&thread_id);
            prefs.sms_stars.forget_thread(&thread_id.to_string());
        });
    }

    pub fn remember_sim(&mut self, thread_id: i64, sub_id: i32, cx: &mut Context<Self>) {
        if self.prefs.sim_choices.get(&thread_id) == Some(&sub_id) {
            return;
        }
        self.update_prefs(cx, |prefs| {
            prefs.sim_choices.insert(thread_id, sub_id);
        });
    }

    // ---- preferences and the schedule queue ---------------------------------

    /// Applies `edit` to the preferences, tells the views and saves the file.
    pub fn update_prefs(&mut self, cx: &mut Context<Self>, edit: impl FnOnce(&mut MessagesPrefs)) {
        edit(&mut self.prefs);
        cx.notify();
        self.persist(cx);
    }

    /// Writes the preferences and the queue off the UI thread. Writes never
    /// overlap: one that is asked for while another runs follows it.
    fn persist(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.data_dir.clone() else {
            // The directory is not known yet: the load applies this change.
            self.save_due = true;
            return;
        };
        if self.saving {
            self.save_due = true;
            return;
        }
        self.saving = true;
        self.save_due = false;
        let (prefs, queue) = (self.prefs.clone(), self.queue.clone());
        let task = cx.background_executor().spawn(async move {
            prefs
                .save(&dir)
                .and_then(|()| storage::save_json(&dir, QUEUE_FILE, &queue))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.saving = false;
                if let Err(error) = result {
                    log::warn!("saving the Messages preferences failed: {error}");
                }
                if this.save_due {
                    this.persist(cx);
                }
            })
            .ok();
        })
        .detach();
    }

    /// Queues a scheduled SMS and arms its alarm. When Android refuses the
    /// alarm the item leaves the queue again and the user is told.
    pub fn schedule_sms(
        &mut self,
        item: ScheduledSms,
        cx: &mut Context<Self>,
    ) -> Result<(), ScheduleError> {
        self.queue.add(item.clone())?;
        self.persist(cx);
        cx.notify();
        let Some(json) = arm_json(&item) else {
            return Ok(());
        };
        let task = cx
            .background_executor()
            .spawn(async move { sms::arm_scheduled(&json) });
        let id = item.id;
        cx.spawn(async move |this, cx| {
            let armed = task.await;
            this.update(cx, |this, cx| match armed {
                // Desktop has no alarm service: the preview keeps the item.
                Ok(true) | Err(Error::Unsupported) => {}
                Ok(false) | Err(_) => {
                    this.queue.cancel(id);
                    this.persist(cx);
                    cx.emit(BackendEvent::Notice(
                        "Couldn't schedule that message".into(),
                    ));
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        Ok(())
    }

    /// Cancels a scheduled SMS: out of the queue now, the alarm after.
    pub fn cancel_scheduled(&mut self, id: u64, cx: &mut Context<Self>) {
        if !self.queue.cancel(id) {
            return;
        }
        self.persist(cx);
        cx.notify();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = sms::disarm_scheduled(id)
                    && !matches!(error, Error::Unsupported)
                {
                    log::warn!("disarming scheduled message {id} failed: {error}");
                }
            })
            .detach();
    }

    /// Settles the sends that fired while no UI looked: they leave the queue
    /// and failures are announced.
    pub fn reconcile_fired(&mut self, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async { sms::pull_fired() });
        cx.spawn(async move |this, cx| {
            let raw = task.await;
            this.update(cx, |this, cx| {
                let fired = match raw {
                    Ok(json) => parse_fired(&json),
                    Err(Error::Unsupported) => return,
                    Err(error) => {
                        log::warn!("pulling fired scheduled sends failed: {error}");
                        return;
                    }
                };
                if fired.is_empty() {
                    return;
                }
                let failed = this.queue.reconcile(&fired);
                this.persist(cx);
                if !failed.is_empty() {
                    cx.emit(BackendEvent::ScheduledFailed(failed));
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---- message reminders ------------------------------------------------

    /// The armed "Remind me" reminders.
    pub fn message_reminders(&self) -> &[rovsti_core::message_reminder::MessageReminder] {
        &self.message_reminders
    }

    /// Sets the stored alarms again (there is no boot receiver) and reads the armed list.
    /// Desktop answers Unsupported, which leaves the list empty.
    fn load_message_reminders(&mut self, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async {
            match reminders::rearm_messages() {
                Ok(_) | Err(Error::Unsupported) => {}
                Err(error) => log::debug!("re-arming message reminders: {error}"),
            }
            reminders::armed_messages()
        });
        cx.spawn(async move |this, cx| {
            let list = match task.await {
                Ok(json) => rovsti_core::message_reminder::parse_list(&json),
                Err(Error::Unsupported) => Vec::new(),
                Err(error) => {
                    log::warn!("reading message reminders failed: {error}");
                    Vec::new()
                }
            };
            this.update(cx, |this, cx| {
                this.message_reminders = list;
                cx.emit(BackendEvent::RemindersChanged);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Arms the alarm for `reminder` off the UI thread. `Ok(false)` means Android refused it.
    pub fn arm_message_reminder(
        &self,
        reminder: &rovsti_core::message_reminder::MessageReminder,
        cx: &mut Context<Self>,
    ) -> Task<Result<bool, Error>> {
        let json = rovsti_core::message_reminder::arm_json(reminder);
        cx.background_executor()
            .spawn(async move { reminders::arm_message(&json) })
    }

    /// Cancels the alarm of reminder `id`.
    pub fn disarm_message_reminder(&self, id: i64, cx: &mut Context<Self>) {
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = reminders::disarm_message(id)
                    && !matches!(error, Error::Unsupported)
                {
                    log::warn!("disarming a message reminder failed: {error}");
                }
            })
            .detach();
    }

    /// Remembers an armed reminder; a second one for the same message replaces the first.
    pub fn track_message_reminder(
        &mut self,
        reminder: rovsti_core::message_reminder::MessageReminder,
        cx: &mut Context<Self>,
    ) {
        // Chat reminders (empty message_id) never replace each other.
        if !reminder.message_id.is_empty() {
            self.message_reminders
                .retain(|r| r.message_id != reminder.message_id);
        }
        self.message_reminders.push(reminder);
        cx.emit(BackendEvent::RemindersChanged);
        cx.notify();
    }

    /// Forgets reminder `id` (cancelled or fired).
    pub fn forget_message_reminder(&mut self, id: i64, cx: &mut Context<Self>) {
        let before = self.message_reminders.len();
        self.message_reminders.retain(|r| r.id != id);
        if self.message_reminders.len() != before {
            cx.emit(BackendEvent::RemindersChanged);
            cx.notify();
        }
    }

    fn on_message_reminder_fired(&mut self, json: &str, cx: &mut Context<Self>) {
        match rovsti_core::message_reminder::fired_id(json) {
            Some(id) => self.forget_message_reminder(id, cx),
            None => self.load_message_reminders(cx),
        }
    }

    // ---- reading everything (backup) ----------------------------------------

    /// Every SMS and MMS row of every thread, off the UI thread.
    pub fn snapshot(&self, cx: &App) -> Task<Result<Rows, String>> {
        let ids: Vec<i64> = self.threads.iter().map(|t| t.thread_id).collect();
        if let Some(preview) = &self.preview {
            let result = collect_all(&ids, |thread, before| {
                Ok(preview.messages(thread, before, SNAPSHOT_PAGE))
            });
            return Task::ready(result);
        }
        cx.background_executor().spawn(async move {
            // The inbox holds only the newest THREAD_LIMIT threads: a backup reads them all.
            let ids = match decode::<SmsThreadsPayload>(sms::threads(BACKUP_THREAD_LIMIT)) {
                Pull::Ready(payload) => payload.threads.iter().map(|t| t.thread_id).collect(),
                Pull::Denied => return Err("SMS access is off".to_owned()),
                Pull::Unsupported | Pull::Failed(_) => ids,
            };
            collect_all(&ids, |thread, before| {
                match decode::<SmsMessagesPayload>(sms::messages(thread, before, SNAPSHOT_PAGE)) {
                    Pull::Ready(page) => Ok(page),
                    Pull::Denied => Err("SMS access is off".to_owned()),
                    Pull::Unsupported => Err("this needs an Android phone".to_owned()),
                    Pull::Failed(error) => Err(error),
                }
            })
        })
    }

    /// Sends a text. Progress arrives as [`BackendEvent::Status`].
    pub fn send_sms(&mut self, req: SmsSend, cx: &mut Context<Self>) {
        let (local_id, to, body, sub_id) =
            (req.local_id, req.to.clone(), req.body.clone(), req.sub_id);
        let task = cx
            .background_executor()
            .spawn(async move { sms::send(&req) });
        self.settle_send(local_id, to, body, sub_id, task, cx);
    }

    /// Sends text typed into a notification to a thread nobody has open: the
    /// provider's own row appears on the next `sms_changed`.
    pub fn reply_sms(&mut self, thread_id: i64, text: String, cx: &mut Context<Self>) {
        if !self.forward_sms(thread_id, text, None, cx) {
            cx.emit(BackendEvent::Notice("The reply was not sent".into()));
        }
    }

    /// Sends text, or a picture (a path the app can read) with its text as an
    /// MMS, to an SMS thread. `false` when the thread has no address or the
    /// app is not the SMS app.
    pub fn forward_sms(
        &mut self,
        thread_id: i64,
        text: String,
        image_path: Option<String>,
        cx: &mut Context<Self>,
    ) -> bool {
        let to = self
            .threads
            .iter()
            .find(|t| t.thread_id == thread_id)
            .map(|t| t.addresses.clone())
            .unwrap_or_default();
        if to.is_empty() || !self.sms_role_held() {
            return false;
        }
        let sub_id = sim_for(&self.sims, self.sim_choice(thread_id));
        let local_id = self.next_local_id();
        match image_path {
            Some(path) => self.send_mms(local_id, to, text, Some(path), sub_id, cx),
            None => self.send_sms(
                SmsSend {
                    local_id,
                    to,
                    body: text,
                    sub_id,
                },
                cx,
            ),
        }
        true
    }

    /// Sends an MMS with an optional picture (a path the app can read).
    pub fn send_mms(
        &mut self,
        local_id: u64,
        to: Vec<String>,
        text: String,
        image_path: Option<String>,
        sub_id: Option<i32>,
        cx: &mut Context<Self>,
    ) {
        let (task_to, task_text) = (to.clone(), text.clone());
        let task = cx.background_executor().spawn(async move {
            sms::send_mms(
                local_id,
                &task_to,
                &task_text,
                image_path.as_deref(),
                sub_id,
            )
        });
        self.settle_send(local_id, to, text, sub_id, task, cx);
    }

    /// Waits for the hand-off to Kotlin. A refusal fails the bubble; on
    /// desktop the preview plays the whole send instead.
    fn settle_send(
        &mut self,
        local_id: u64,
        to: Vec<String>,
        body: String,
        sub_id: Option<i32>,
        task: Task<rovsti_android::Result<bool>>,
        cx: &mut Context<Self>,
    ) {
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| match result {
                Ok(true) => {}
                Err(Error::Unsupported) if this.preview.is_some() => {
                    this.play_preview_send(local_id, &to, &body, sub_id, cx);
                }
                Ok(false) | Err(_) => {
                    log::warn!("starting the send of message {local_id} failed");
                    cx.emit(BackendEvent::Status(status(
                        local_id,
                        None,
                        DeliveryState::Failed,
                    )));
                }
            })
            .ok();
        })
        .detach();
    }

    /// Records the send in the sample rows and answers like Android would:
    /// Sent (with the row's address), then Delivered.
    fn play_preview_send(
        &mut self,
        local_id: u64,
        to: &[String],
        body: &str,
        sub_id: Option<i32>,
        cx: &mut Context<Self>,
    ) {
        let Some(preview) = &mut self.preview else {
            return;
        };
        let (_, row) = preview.record_send(to, body, sub_id, now_ms());
        let uri = format!("content://sms/{row}");
        cx.spawn(async move |this, cx| {
            cx.background_executor()
                .timer(Duration::from_millis(350))
                .await;
            this.update(cx, |this, cx| {
                cx.emit(BackendEvent::Status(status(
                    local_id,
                    Some(uri.clone()),
                    DeliveryState::Sent,
                )));
                // The thread list follows the status so the new row links to the bubble.
                this.pull_threads(cx);
            })
            .ok();
            cx.background_executor()
                .timer(Duration::from_millis(600))
                .await;
            this.update(cx, |_, cx| {
                cx.emit(BackendEvent::Status(status(
                    local_id,
                    Some(uri),
                    DeliveryState::Delivered,
                )));
            })
            .ok();
        })
        .detach();
    }

    /// Opens the system picker for a file to attach. The answer is a
    /// [`BackendEvent::Document`]; where there is no picker it is a refusal.
    pub fn pick_attachment(&mut self, cx: &mut Context<Self>) {
        let task = cx
            .background_executor()
            .spawn(async { documents_pick_attachment() });
        cx.spawn(async move |this, cx| {
            let started = task.await;
            this.update(cx, |_, cx| {
                if !started {
                    cx.emit(BackendEvent::Document(DocumentResult {
                        op: DocumentOp::Attachment,
                        ok: false,
                        ..DocumentResult::default()
                    }));
                }
            })
            .ok();
        })
        .detach();
    }
}

/// Every SMS and MMS row of a backup or a restore comparison.
pub type Rows = (Vec<SmsRow>, Vec<MmsRow>);

/// Pages through every thread with `fetch(thread, before_ms)` and gathers the
/// rows once each. A page is asked again from just after its oldest stamp so
/// rows that share it are not skipped; a page with nothing new ends the thread.
pub fn collect_all(
    threads: &[i64],
    mut fetch: impl FnMut(i64, Option<i64>) -> Result<SmsMessagesPayload, String>,
) -> Result<Rows, String> {
    let mut sms_rows: Vec<SmsRow> = Vec::new();
    let mut mms_rows: Vec<MmsRow> = Vec::new();
    for &thread in threads {
        let mut seen_sms: HashSet<i64> = HashSet::new();
        let mut seen_mms: HashSet<i64> = HashSet::new();
        let mut before = None;
        loop {
            let page = fetch(thread, before)?;
            let mut fresh = 0usize;
            let mut oldest: Option<i64> = None;
            for row in page.sms {
                oldest = Some(oldest.map_or(row.date_ms, |o| o.min(row.date_ms)));
                if seen_sms.insert(row.id) {
                    fresh += 1;
                    sms_rows.push(row);
                }
            }
            for row in page.mms {
                oldest = Some(oldest.map_or(row.date_ms, |o| o.min(row.date_ms)));
                if seen_mms.insert(row.id) {
                    fresh += 1;
                    mms_rows.push(row);
                }
            }
            match oldest {
                Some(oldest) if page.has_older && fresh > 0 => {
                    before = Some(oldest.saturating_add(1));
                }
                _ => break,
            }
        }
    }
    Ok((sms_rows, mms_rows))
}

fn documents_pick_attachment() -> bool {
    matches!(
        rovsti_android::documents::pick_attachment(ATTACH_MAX_BYTES),
        Ok(true)
    )
}

fn status(local_id: u64, uri: Option<String>, state: DeliveryState) -> SmsStatus {
    SmsStatus {
        local_id,
        uri,
        state,
        error_code: None,
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::sms::SmsSim;

    use super::*;

    fn sim(id: i32) -> SmsSim {
        SmsSim {
            sub_id: id,
            slot_index: None,
            label: format!("SIM {id}"),
            color_argb: 0,
        }
    }

    fn sims(ids: &[i32], default: Option<i32>) -> SmsSimsPayload {
        SmsSimsPayload {
            sims: ids.iter().copied().map(sim).collect(),
            default_sub_id: default,
        }
    }

    #[test]
    fn sim_choice_prefers_the_remembered_active_sim() {
        let two = sims(&[1, 2], Some(1));
        assert_eq!(sim_for(&two, Some(2)), Some(2));
        // A remembered SIM that was removed falls back to the default.
        assert_eq!(sim_for(&two, Some(9)), Some(1));
        assert_eq!(sim_for(&two, None), Some(1));
        // No default and two SIMs: let Android pick.
        assert_eq!(sim_for(&sims(&[1, 2], None), None), None);
        // A stale default is ignored too.
        assert_eq!(sim_for(&sims(&[1, 2], Some(7)), None), None);
        assert_eq!(sim_for(&sims(&[4], None), None), Some(4));
        assert_eq!(sim_for(&sims(&[], None), Some(1)), None);
    }

    #[test]
    fn decode_maps_the_envelope() {
        let ok = r#"{"v":1,"ok":true,"data":{"threads":[{"thread_id":3,"addresses":["+1"],"snippet":"hi","date_ms":5,"unread":1}]}}"#;
        match decode::<SmsThreadsPayload>(Ok(ok.to_owned())) {
            Pull::Ready(p) => assert_eq!(p.threads[0].thread_id, 3),
            other => panic!("{other:?}"),
        }
        let denied = r#"{"v":1,"ok":false,"error":"permission"}"#;
        assert!(matches!(
            decode::<SmsThreadsPayload>(Ok(denied.to_owned())),
            Pull::Denied
        ));
        assert!(matches!(
            decode::<SmsThreadsPayload>(Err(Error::Unsupported)),
            Pull::Unsupported
        ));
        assert!(matches!(
            decode::<SmsThreadsPayload>(Ok("nope".to_owned())),
            Pull::Failed(_)
        ));
    }

    fn page(ids: &[i64], has_older: bool) -> SmsMessagesPayload {
        SmsMessagesPayload {
            sms: ids
                .iter()
                .map(|&id| SmsRow {
                    id,
                    date_ms: id * 10,
                    ..SmsRow::default()
                })
                .collect(),
            mms: Vec::new(),
            has_older,
        }
    }

    #[test]
    fn collect_all_pages_until_the_provider_runs_out() {
        let mut asked = Vec::new();
        let (sms, mms) = collect_all(&[1, 2], |thread, before| {
            asked.push((thread, before));
            Ok(match (thread, before) {
                // Thread 1: newest page, then the older one (the +1 re-reads row 5).
                (1, None) => page(&[9, 5], true),
                (1, Some(51)) => page(&[5, 2], false),
                (2, None) => page(&[7], false),
                other => panic!("unexpected {other:?}"),
            })
        })
        .expect("ok");
        let mut ids: Vec<i64> = sms.iter().map(|r| r.id).collect();
        ids.sort_unstable();
        assert_eq!(ids, vec![2, 5, 7, 9], "row 5 is read once");
        assert!(mms.is_empty());
        assert_eq!(asked, vec![(1, None), (1, Some(51)), (2, None)]);
    }

    #[test]
    fn collect_all_stops_on_a_page_with_nothing_new_and_passes_errors_on() {
        let pages = collect_all(&[1], |_, _| Ok(page(&[4], true))).expect("ok");
        assert_eq!(pages.0.len(), 1, "no endless loop on a stuck provider");
        assert_eq!(
            collect_all(&[1], |_, _| Err("boom".to_owned())).expect_err("err"),
            "boom"
        );
        assert!(
            collect_all(&[], |_, _| unreachable!())
                .expect("ok")
                .0
                .is_empty()
        );
    }

    #[test]
    fn statuses_carry_the_row_address() {
        let s = status(4, Some("content://sms/9".into()), DeliveryState::Sent);
        assert_eq!((s.local_id, s.state), (4, DeliveryState::Sent));
        assert_eq!(s.uri.as_deref(), Some("content://sms/9"));
    }
}
