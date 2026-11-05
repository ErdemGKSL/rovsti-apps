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
    collections::HashMap,
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use futures::StreamExt as _;
use gpui::{App, AppContext as _, Context, Entity, EventEmitter, Task};
use rovsti_android::{
    Error,
    events::{self, RawEvent, kind},
    permissions, providers,
    roles::{self, Role},
    sms,
};
use rovsti_core::{
    blocklist::BlockSet,
    recents::LocaleTime,
    search::ContactIndex,
    sms::{
        DeliveryState, SmsMessagesPayload, SmsSend, SmsSimsPayload, SmsStatus, SmsThreadRow,
        SmsThreadsPayload,
    },
    spam::{SpamRule, SpamRules},
    wire::{BlocklistPayload, ContactsPayload, DocumentOp, DocumentResult, Envelope, WireError},
};
use rovsti_ui::storage;
use serde::de::DeserializeOwned;

use preview::Preview;

/// How many threads the inbox pulls.
const THREAD_LIMIT: usize = 500;

/// The largest picture the attach button copies into the cache.
const ATTACH_MAX_BYTES: u64 = 8 * 1024 * 1024;

/// Per-thread SIM choices, next to the WhatsApp link in the app's data dir.
const SIM_FILE: &str = "sim-choices.json";

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
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
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

/// The rules the app starts with: only unknown senders' links go to Spam.
pub fn default_spam_rules() -> SpamRules {
    SpamRules {
        rules: vec![SpamRule::UnknownWithLink],
        ..SpamRules::default()
    }
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
    locale: LocaleTime,
    /// `None` until the first answer (the banner stays hidden meanwhile).
    sms_role: Option<bool>,
    spam: SpamRules,
    blocked: Arc<BlockSet>,
    /// Sample data, on desktop only.
    preview: Option<Preview>,
    preview_sims: u32,
    preview_role: bool,
    sim_choices: HashMap<i64, i32>,
    data_dir: Option<PathBuf>,
    next_local: u64,
    pulls: Pulls,
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
                locale: LocaleTime::default(),
                sms_role: None,
                spam: default_spam_rules(),
                blocked: Arc::new(BlockSet::default()),
                preview: desktop.then(|| Preview::new(now_ms())),
                preview_sims: std::env::var("ROVSTI_PREVIEW_SIMS")
                    .ok()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(2),
                preview_role: std::env::var_os("ROVSTI_PREVIEW_NO_ROLE").is_none(),
                sim_choices: HashMap::new(),
                data_dir: None,
                next_local: 1,
                pulls: Pulls::default(),
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
                let saved =
                    cx.background_executor()
                        .spawn(async move {
                            storage::load_json::<HashMap<i64, i32>>(&read_from, SIM_FILE)
                        })
                        .await;
                this.update(cx, |this: &mut Backend, cx| {
                    this.data_dir = Some(dir);
                    // A choice made before the file was read is newer.
                    for (thread, sub) in saved.unwrap_or_default() {
                        this.sim_choices.entry(thread).or_insert(sub);
                    }
                    cx.notify();
                })
                .ok();
            });
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
        &self.spam
    }

    pub fn blocked(&self) -> &Arc<BlockSet> {
        &self.blocked
    }

    pub fn is_preview(&self) -> bool {
        self.preview.is_some()
    }

    pub fn sim_choice(&self, thread_id: i64) -> Option<i32> {
        self.sim_choices.get(&thread_id).copied()
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
                Pull::Ready(p) => Some(ContactIndex::new(p.contacts)),
                Pull::Unsupported => Some(ContactIndex::new(Preview::contacts())),
                Pull::Denied => Some(ContactIndex::default()),
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
                if let Some(index) = index {
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
                    Pull::Ready(p) => this.blocked = Arc::new(BlockSet::from_rows(&p.numbers)),
                    Pull::Denied | Pull::Unsupported => this.blocked = Arc::default(),
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

    pub fn remember_sim(&mut self, thread_id: i64, sub_id: i32, cx: &mut Context<Self>) {
        if self.sim_choices.get(&thread_id) == Some(&sub_id) {
            return;
        }
        self.sim_choices.insert(thread_id, sub_id);
        let Some(dir) = self.data_dir.clone() else {
            return;
        };
        let choices = self.sim_choices.clone();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = storage::save_json(&dir, SIM_FILE, &choices) {
                    log::warn!("saving the SIM choices failed: {error}");
                }
            })
            .detach();
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

    /// Opens the system picker for a picture. The answer is a
    /// [`BackendEvent::Document`]; where there is no picker it is a refusal.
    pub fn pick_attachment(&mut self, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async { documents_import() });
        cx.spawn(async move |this, cx| {
            let started = task.await;
            this.update(cx, |_, cx| {
                if !started {
                    cx.emit(BackendEvent::Document(DocumentResult {
                        op: DocumentOp::ImportMessages,
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

fn documents_import() -> bool {
    matches!(
        rovsti_android::documents::import_file(ATTACH_MAX_BYTES),
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

    #[test]
    fn the_default_rules_only_catch_unknown_links() {
        let rules = default_spam_rules();
        assert!(rules.enabled);
        assert_eq!(rules.rules, vec![SpamRule::UnknownWithLink]);
    }

    #[test]
    fn statuses_carry_the_row_address() {
        let s = status(4, Some("content://sms/9".into()), DeliveryState::Sent);
        assert_eq!((s.local_id, s.state), (4, DeliveryState::Sent));
        assert_eq!(s.uri.as_deref(), Some("content://sms/9"));
    }
}
