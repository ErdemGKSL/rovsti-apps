//! Rovsti Messages: SMS/MMS and WhatsApp in one inbox.
//!
//! WhatsApp runs on the Rovsti gateway (`services/wa_gateway`): the WhatsApp
//! tab hosts the shared settings screen (`rovsti_wa_ui`) that links this app to
//! an instance, and chats and live messages come from the gateway through
//! `rovsti_wa_client`. SMS stays on the phone.

mod access;
mod backend;
mod backup_ui;
mod conversation;
mod drafts;
mod files;
mod find_ui;
// Only the Android JNI entry calls `handle`; desktop builds exercise it from the tests.
mod forward_ui;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
mod headless;
mod inbox;
mod info_ui;
mod keyed_list;
mod media_viewer;
mod new_conversation;
mod notifications;
mod photos;
mod prefs;
mod schedule_ui;
mod settings_ui;
mod sms_actions;
mod sms_conversation;
mod status_ui;
mod ticks;

use std::{
    borrow::Cow,
    collections::{BTreeMap, HashMap},
    path::Path,
    sync::Arc,
    time::Duration,
};

use gpui::{
    AnyElement, App, AppContext as _, Bounds, ClipboardItem, Context, Entity, Hsla,
    InteractiveElement, IntoElement, ListAlignment, ParentElement, Render, RenderImage,
    SharedString, StatefulInteractiveElement, Styled, Subscription, TextInputAction,
    TextInputPurpose, Window, WindowBounds, WindowOptions, div, list, px, size,
};
use rovsti_core::{
    Message, MessageDirection, PhoneNumber, ThreadId, blocklist,
    inbox::InboxThread,
    notify::NotificationSpec,
    schedule::{self, ScheduleTarget, ScheduledSms},
    spam,
    wire::{EmergencyList, Handoff},
};
use rovsti_ui::{
    Icon, IconAssets,
    components::{avatar, empty_state, glass_icon_button, glass_pill, large_title, list_row},
    current_theme,
    field::{TextField, field_box},
    glass::{GlassSpec, glass},
    notice_pill::{NoticePill, PillEvent},
    storage,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    theme,
    tokens::{Theme, metrics, shadow, typography},
};

use backend::{Access, Backend, BackendEvent, now_ms};
use conversation::{Conversation, ConversationEvent, Peer};
use inbox::{InboxView, RowExtras, RowView, WaChatInfo};
use keyed_list::{KeyedList, key_of};
use new_conversation::{NewConversation, NewConversationEvent, Recipient};
use notifications::{Arrival, Digests, Reply, SmsReceived, Switches};
use photos::ContactPhotos;
use rovsti_wa_client::ClientError;
use rovsti_wa_proto::{
    ChatAction, ChatActionKind, ChatMeta, GatewayEvent, ScheduleText, ScheduledText, SendText,
    WaMessage, is_pn_jid, is_status_chat, jid_user, to_rovsti,
};
use rovsti_wa_ui::{AppId, WaAvatars, WhatsAppSettings, display_name};
use schedule_ui::{Dest, Draft, ScheduleSheet, StripKey, StripTarget};
use settings_ui::{SettingsEvent, SettingsUndo, SettingsView};
use sms_conversation::{SmsConversation, SmsConversationEvent};
use status_ui::{StatusFeed, StatusPage, StatusPageEvent};

const TABS: [&str; 3] = ["Chats", "WhatsApp", "Settings"];
const CHATS_TAB: usize = 0;
const WHATSAPP_TAB: usize = 1;
const SETTINGS_TAB: usize = 2;
const TAB_ICONS: [Icon; 3] = [
    Icon::LucideMessageSquare,
    Icon::LucideMessageCircle,
    Icon::LucideSettings,
];

/// How long a tapped OTP chip says "Copied".
const COPIED_FOR: Duration = Duration::from_secs(3);

const HOUR_MS: i64 = 3_600_000;
/// The mute lengths the chat menu offers ("Always" is the third).
const MUTE_8H_MS: i64 = 8 * HOUR_MS;
const MUTE_WEEK_MS: i64 = 7 * 24 * HOUR_MS;

/// The long-press menu of a WhatsApp row.
#[derive(Clone, Debug)]
struct RowMenu {
    /// The row's WhatsApp chat, when it has one.
    jid: Option<String>,
    title: SharedString,
    meta: ChatMeta,
    /// The chat carries the local mute tag (`wa:<jid>`).
    local_muted: bool,
    unread: u32,
    /// The mute lengths show instead of the main actions.
    mute_choices: bool,
    /// The row's SMS thread, when it has one.
    sms: Option<SmsRowMenu>,
    /// "Delete conversation" asked: the confirmation shows instead.
    delete_confirm: bool,
    /// The phone number of a 1:1 WhatsApp chat page's menu (Call, Add to contacts).
    page_number: Option<String>,
    /// Android contact id of `page_number`, when it is in the address book.
    contact_id: Option<i64>,
    /// A conversation page's menu: "Send later" comes first.
    send_later: bool,
}

/// The phone number behind a 1:1 WhatsApp chat JID. Groups, channels and hidden-number (LID)
/// chats have none.
fn wa_page_number(jid: &str) -> Option<String> {
    let user = jid.strip_suffix("@s.whatsapp.net")?;
    let user = user.split(':').next().unwrap_or(user);
    (!user.is_empty() && user.bytes().all(|b| b.is_ascii_digit())).then(|| format!("+{user}"))
}

/// The SMS part of a row menu.
#[derive(Clone, Debug)]
struct SmsRowMenu {
    thread_id: i64,
    sender: Option<String>,
    unread: bool,
    muted: bool,
    in_spam: bool,
    /// The menu is the open conversation page's, not an inbox row's.
    on_page: bool,
    /// Android contact id of the sender, when the number is in the address book.
    contact_id: Option<i64>,
    /// The sender is on the system blocklist.
    blocked: bool,
    /// The sender is a phone number (not an alphanumeric name).
    sender_is_number: bool,
    /// The row is SMS-only, so it may be moved into Spam.
    can_be_spam: bool,
}

/// Whether a sender is a blockable phone number (not missing or alphanumeric).
fn is_number_sender(sender: Option<&str>) -> bool {
    sender.is_some_and(|s| {
        PhoneNumber::parse(s.trim()).is_ok()
            && !matches!(
                blocklist::can_block(s, &EmergencyList::default()),
                Err(blocklist::BlockRefusal::NoNumber | blocklist::BlockRefusal::NotANumber)
            )
    })
}

/// The SMS actions a row menu lists.
fn row_sms_actions(sms: &SmsRowMenu) -> Vec<sms_actions::SmsAction> {
    let mut actions = sms_actions::actions(&sms_actions::SmsActionContext {
        unread: sms.unread,
        muted: sms.muted,
        in_spam: sms.in_spam,
        single_sender: sms.sender.is_some(),
        known_contact: sms.contact_id.is_some(),
        sender_is_number: sms.sender_is_number,
        can_be_spam: sms.can_be_spam,
        blocked: sms.blocked,
        on_page: sms.on_page,
    });
    // A new conversation has no provider thread to delete yet.
    if sms.thread_id < 0 {
        actions.retain(|a| *a != sms_actions::SmsAction::Delete);
    }
    actions
}

/// What the pill's trailing "Undo" does.
#[derive(Clone, Debug)]
enum PillAction {
    /// Puts a sender back in Spam.
    Unallow(String),
    /// Takes a sender back out of Spam after Move to Spam.
    Unspam {
        sender: String,
        mark: prefs::SpamMark,
    },
    /// Unblocks a number.
    Unblock(String),
    /// Blocks a number again after an Unblock.
    Reblock(String),
    /// Restores something deleted in Settings.
    Settings(SettingsUndo),
    /// Asks for the default SMS app role.
    RequestSmsRole,
    /// Reverses a WhatsApp chat action (archive, mute).
    Chat(String, ChatActionKind),
    /// Sets an SMS thread's mute back (thread id, muted).
    SmsMute(i64, bool),
    /// Reopens a failed scheduled SMS in the composer (to, body).
    ReopenScheduled { to: String, body: String },
    /// Tries loading the WhatsApp chats again.
    ReloadChats,
    /// Confirms "mark all as read" for these chats.
    MarkAll(inbox::MarkAllTargets),
    /// Marks WhatsApp chats unread again after "mark all as read".
    UnmarkAll(Vec<String>),
    /// Opens a WhatsApp chat (its JID and title).
    OpenChat { jid: String, title: String },
    /// Opens the chat a message was just forwarded to.
    OpenThread { thread: ThreadId, title: String },
}

/// The page pushed over the tabs.
enum Open {
    None,
    /// A WhatsApp chat (its JID, for the channel switch).
    WhatsApp(String, Entity<Conversation>),
    Sms(Entity<SmsConversation>),
    New(Entity<NewConversation>),
    /// WhatsApp Status updates (stories).
    Status(Entity<StatusPage>),
}

struct MessagesApp {
    tabs: TabBar,
    motion: rovsti_ui::motion::Motion,
    page: rovsti_ui::motion::Entrance,
    whatsapp: Entity<WhatsAppSettings>,
    settings: Entity<SettingsView>,
    backend: Entity<Backend>,
    /// The "Send later" sheet.
    schedule: ScheduleSheet,
    /// Confirmations and failures, over the page.
    pill: NoticePill,
    /// What the pill's trailing button does, with the pill's token.
    pill_action: Option<(u64, PillAction)>,
    /// What each notified conversation says, for the digest.
    digests: Digests,
    /// The gateway's scheduled texts (pending and recently finished).
    remote_scheduled: Vec<ScheduledText>,
    /// GPUI asked the host for the Back key (a sheet is open).
    back_enabled: bool,
    /// The full-screen picture viewer, over the open chat.
    media_viewer: Option<media_viewer::MediaViewer>,
    /// The gateway's chats as inbox threads (SMS comes from the backend).
    /// Change it through [`Self::wa_threads_mut`], which marks the inbox stale.
    drafts: drafts::Drafts,
    /// The drafts file was asked for (once the data directory is known).
    drafts_loading: bool,
    /// The drafts file has been merged in; saving is allowed.
    drafts_loaded: bool,
    /// The drafts revision last written (or being written).
    drafts_saved_rev: u64,
    /// A drafts write is running.
    drafts_saving: bool,
    wa_threads: Vec<InboxThread>,
    /// Archive, pin, mute and preview details of each chat, by JID. Change it
    /// through [`Self::wa_info_mut`].
    wa_info: HashMap<String, WaChatInfo>,
    /// WhatsApp profile pictures (shared with the conversation).
    avatars: Entity<WaAvatars>,
    /// WhatsApp Status updates (shared with the Status page).
    statuses: Entity<StatusFeed>,
    /// Address-book photos (shared with the conversation).
    photos: Entity<ContactPhotos>,
    /// The address book the photo misses were recorded against.
    contacts_seen: usize,
    /// The archived chats are showing instead of the inbox.
    show_archived: bool,
    /// The scheduled-messages overview is showing instead of the inbox.
    show_scheduled: bool,
    /// Everything waiting to be sent (both queues), as of the last list sync.
    scheduled_entries: Vec<schedule_ui::ScheduledEntry>,
    /// The long-press menu of a WhatsApp row.
    row_menu: Option<RowMenu>,
    /// New message: the recipient being asked "SMS or WhatsApp".
    channel_pick: Option<Recipient>,
    /// The "Forward to" chooser.
    forward: Option<forward_ui::ForwardSheet>,
    /// Bumped on every change of `wa_threads`.
    wa_rev: u64,
    /// Bumped on every backend notification (threads, contacts, rules, clock).
    backend_epoch: u64,
    /// What `view` was merged from; the merge runs again only when it changes.
    inbox_key: Option<InboxKey>,
    /// The merged inbox, rebuilt whenever a source changes.
    view: InboxView,
    /// Bumped on every rebuild of `view`.
    view_gen: u64,
    /// The Chats tab: a virtualised list of the title, banners and rows.
    chat_list: KeyedList,
    /// What each item of `chat_list` is, rebuilt only when `chat_layout` changes.
    chat_items: Vec<ChatItem>,
    chat_layout: Option<ChatLayout>,
    /// The Spam filter's rows are showing instead of the inbox.
    show_spam: bool,
    /// The Chats search row is showing.
    search_open: bool,
    search_field: Entity<TextField>,
    /// The OTP code that was just copied.
    copied: Option<SharedString>,
    open: Open,
    /// The chat list is being fetched, or was fetched for this link.
    chats: ChatsState,
    /// The window is in front of the user (not backgrounded, screen on, not
    /// covered). Only then does an open chat count as read.
    window_active: bool,
    /// The window has been in front at least once (the first time `refresh_all`
    /// already covers).
    seen_active: bool,
    _subscriptions: Vec<Subscription>,
}

/// The inputs of one inbox merge. Time labels only change with the day.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct InboxKey {
    backend: u64,
    wa: u64,
    /// The local day of "now", in the backend's offset.
    day: i64,
    /// The desktop preview chat stands in for an unlinked WhatsApp.
    wa_preview: bool,
    /// Unsent drafts, which show in the rows.
    drafts: u64,
}

/// What decides the Chats list's items besides the rows themselves.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct ChatLayout {
    view_gen: u64,
    show_spam: bool,
    show_archived: bool,
    show_scheduled: bool,
    /// The scheduled entries: count, failed count, soonest time.
    scheduled: (usize, usize, i64),
    /// Whether a non-blank query filters the list, and its field's edit count.
    searching: bool,
    search_edits: u64,
    role_held: bool,
    /// The WhatsApp banner's text, `None` while there is nothing to say.
    wa_banner: Option<&'static str>,
    /// Android has notifications off for the app.
    access_banner: Option<&'static str>,
    state: Access,
    /// The Status row's people and unseen count.
    statuses: (usize, usize),
}

/// One item of the Chats list.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ChatItem {
    Title,
    /// An archived chat that matches the search query.
    ArchivedMatch(usize),
    /// The search field and its Cancel pill.
    Search,
    /// Nothing matches the query.
    NoResults,
    /// "Read-only: not your default SMS app".
    RoleBanner,
    /// "WhatsApp is offline" or "logged out"; a tap opens the WhatsApp tab.
    WaBanner,
    /// Android has notifications off; a tap asks for them.
    AccessBanner,
    /// Back from the Spam filter to the inbox.
    SpamBack,
    /// The Spam filter's entry row.
    SpamFilter,
    /// Back from the archived chats to the inbox.
    ArchivedBack,
    /// The "Archived" entry row.
    ArchivedFilter,
    /// The "Scheduled" entry row.
    ScheduledFilter,
    /// Back from the scheduled overview to the inbox.
    ScheduledBack,
    /// One waiting scheduled message (index into `scheduled_entries`).
    Scheduled(usize),
    /// WhatsApp Status updates.
    Status,
    /// A row of the inbox (or of the Spam filter while it shows).
    Row(usize),
    Empty,
    /// Room for the floating tab bar under the last row.
    Tail,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ChatsState {
    /// Not connected, or the chat list is stale and a fetch is due.
    Needed,
    Loading,
    Loaded,
}

impl MessagesApp {
    fn new(backend: Entity<Backend>, cx: &mut Context<Self>) -> Self {
        let whatsapp = cx.new(|cx| WhatsAppSettings::new(AppId::Messages, "messages", cx));
        let settings = cx.new(|cx| SettingsView::new(backend.clone(), cx));
        let schedule = ScheduleSheet::new(cx);
        let avatars = cx.new(|cx| WaAvatars::new(&whatsapp, cx));
        let photos = cx.new(|_| ContactPhotos::new());
        let statuses = cx.new(|_| StatusFeed::default());
        let search_field =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Search, cx));
        // The backend owns the one event sink: it hands WhatsApp pushes over
        // here, and SMS, contact and role changes rebuild the inbox.
        let subscriptions = vec![
            cx.observe(&search_field, |_, _, cx| cx.notify()),
            cx.observe(&whatsapp, |this, whatsapp, cx| {
                let events = whatsapp.update(cx, |settings, _| settings.take_events());
                let connected = whatsapp.read(cx).is_connected();
                whatsapp.update(cx, |settings, cx| settings.register_push(cx));
                this.on_gateway_events(events, connected, cx);
                cx.notify();
            }),
            cx.observe(&backend, |this, backend, cx| {
                this.backend_epoch += 1;
                // A new address book may hold photos that were missing.
                let contacts = Arc::as_ptr(backend.read(cx).contacts()) as usize;
                if contacts != this.contacts_seen {
                    this.contacts_seen = contacts;
                    this.photos.update(cx, |photos, _| photos.forget_misses());
                }
                this.load_drafts(cx);
                this.rebuild(cx);
                cx.notify();
            }),
            cx.observe(&avatars, |_, _, cx| cx.notify()),
            cx.observe(&photos, |_, _, cx| cx.notify()),
            cx.observe(&statuses, |_, _, cx| cx.notify()),
            cx.subscribe(&backend, |this, _, event, cx| match event {
                BackendEvent::WaPush => {
                    this.whatsapp.update(cx, |settings, cx| settings.wake(cx));
                }
                BackendEvent::SmsReceived(received) => this.on_sms_received(received, cx),
                BackendEvent::Reply(reply) => this.on_reply(reply, cx),
                BackendEvent::OpenThread(tag) => this.on_open_tag(tag, None, cx),
                BackendEvent::Handoff(handoff) => this.on_handoff(handoff, cx),
                BackendEvent::Notice(text) => {
                    this.show_pill(text.clone());
                    cx.notify();
                }
                BackendEvent::ThreadDeleted(id) => this.on_thread_deleted(*id, cx),
                BackendEvent::ScheduledFailed(items) => {
                    if let Some(notice) = schedule_ui::failed_notice(items) {
                        match notice.reopen {
                            Some((to, body)) => this.show_pill_action(
                                notice.text,
                                "Open",
                                PillAction::ReopenScheduled { to, body },
                                cx,
                            ),
                            None => this.show_pill(notice.text),
                        }
                    }
                    cx.notify();
                }
                BackendEvent::SmsChanged | BackendEvent::Status(_) | BackendEvent::Document(_) => {}
            }),
            cx.subscribe(&settings, |this, _, event, cx| match event {
                SettingsEvent::OpenWhatsApp => {
                    if this.tabs.select(1) {
                        this.page.restart();
                    }
                    cx.notify();
                }
                SettingsEvent::UnmuteWhatsApp(jid) => {
                    this.chat_action(jid, ChatActionKind::Unmute, false, cx);
                }
                SettingsEvent::Notice(text) => {
                    this.show_pill(text.clone());
                    cx.notify();
                }
                SettingsEvent::Undoable { text, undo } => {
                    this.show_pill_action(
                        text.clone(),
                        "Undo",
                        PillAction::Settings(undo.clone()),
                        cx,
                    );
                }
            }),
        ];
        let mut this = Self {
            tabs: TabBar::new(
                TABS.into_iter()
                    .zip(TAB_ICONS)
                    .map(|(label, icon)| Tab::new(icon, label)),
            ),
            motion: Default::default(),
            page: Default::default(),
            whatsapp,
            settings,
            backend,
            schedule,
            pill: NoticePill::new(),
            pill_action: None,
            digests: Digests::default(),
            remote_scheduled: Vec::new(),
            back_enabled: false,
            media_viewer: None,
            drafts: drafts::Drafts::default(),
            drafts_loading: false,
            drafts_loaded: false,
            drafts_saved_rev: 0,
            drafts_saving: false,
            wa_threads: Vec::new(),
            wa_info: HashMap::new(),
            avatars,
            photos,
            statuses,
            contacts_seen: 0,
            show_archived: false,
            show_scheduled: false,
            scheduled_entries: Vec::new(),
            row_menu: None,
            channel_pick: None,
            forward: None,
            wa_rev: 0,
            backend_epoch: 0,
            inbox_key: None,
            view: InboxView::default(),
            view_gen: 0,
            chat_list: KeyedList::new(ListAlignment::Top, 0),
            chat_items: Vec::new(),
            chat_layout: None,
            show_spam: false,
            search_open: false,
            search_field,
            copied: None,
            open: Open::None,
            chats: ChatsState::Needed,
            window_active: false,
            seen_active: false,
            _subscriptions: subscriptions,
        };
        this.rebuild(cx);
        this
    }

    /// The WhatsApp threads the inbox merges. On desktop with nothing linked
    /// the preview chat stands in, so the merged person shows.
    fn wa_for_view(&self, cx: &App) -> Cow<'_, [InboxThread]> {
        if self.wa_preview(cx) {
            return Cow::Owned(backend::preview::whatsapp_threads(now_ms()));
        }
        Cow::Borrowed(&self.wa_threads)
    }

    fn wa_preview(&self, cx: &App) -> bool {
        self.wa_threads.is_empty()
            && self.backend.read(cx).is_preview()
            && self.whatsapp.read(cx).link().is_none()
    }

    /// Follows the window coming to the front and leaving it, so read
    /// receipts only go out while the user can see the chat.
    fn watch_window(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.window_active = window.is_window_active();
        let active = self.window_active;
        self.backend
            .update(cx, |backend, cx| backend.set_ui_active(active, cx));
        let subscription = cx.observe_window_activation(window, |this, window, cx| {
            this.on_window_active(window.is_window_active(), cx);
        });
        self._subscriptions.push(subscription);
    }

    fn on_window_active(&mut self, active: bool, cx: &mut Context<Self>) {
        if self.window_active == active {
            return;
        }
        self.window_active = active;
        if active {
            if self.seen_active {
                self.backend.update(cx, |backend, cx| backend.on_resume(cx));
            }
            self.seen_active = true;
        }
        if !active {
            self.stash_open_draft(cx);
            self.save_drafts(cx);
        }
        self.sms_window_active(active, cx);
        if let Open::Status(page) = &self.open {
            page.update(cx, |page, cx| page.set_active(active, cx));
        }
        if let Open::WhatsApp(jid, conversation) = &self.open {
            let (jid, conversation) = (jid.clone(), conversation.clone());
            conversation.update(cx, |c, cx| c.set_active(active, cx));
            if active {
                // Back in front of the open chat: what arrived meanwhile is
                // read now.
                let id = ThreadId::WhatsApp(jid);
                self.dismiss_notification(&id, cx);
                for thread in self.wa_threads_mut().iter_mut().filter(|t| t.id == id) {
                    thread.unread = 0;
                }
                self.rebuild(cx);
                cx.notify();
            }
        }
    }

    /// The WhatsApp threads, for a change: the inbox merges again next rebuild.
    fn wa_threads_mut(&mut self) -> &mut Vec<InboxThread> {
        self.wa_rev += 1;
        &mut self.wa_threads
    }

    /// The chats' meta, for a change: the inbox merges again next rebuild.
    fn wa_info_mut(&mut self) -> &mut HashMap<String, WaChatInfo> {
        self.wa_rev += 1;
        &mut self.wa_info
    }

    /// Merges SMS and WhatsApp into the rows again (only when a source
    /// changed) and keeps an open conversation's channel switch and clock in
    /// step.
    fn rebuild(&mut self, cx: &mut Context<Self>) {
        let now = now_ms();
        let locale = self.backend.read(cx).locale();
        let key = InboxKey {
            backend: self.backend_epoch,
            wa: self.wa_rev,
            day: (now.div_euclid(1000) + i64::from(locale.tz_offset_s)).div_euclid(86_400),
            wa_preview: self.wa_preview(cx),
            drafts: self.drafts.rev(),
        };
        if self.inbox_key != Some(key) {
            let mut view = {
                let wa = self.wa_for_view(cx);
                let backend = self.backend.read(cx);
                inbox::build(
                    backend.threads(),
                    &wa,
                    &inbox::Inputs {
                        contacts: backend.contacts(),
                        wa: &self.wa_info,
                        rules: backend.spam_rules(),
                        blocked: backend.blocked(),
                        muted: &backend.prefs().muted,
                        locale,
                        now_ms: now,
                    },
                )
            };
            inbox::apply_drafts(&mut view, |t| self.drafts.get(t));
            let titles: HashMap<String, String> = view
                .rows
                .iter()
                .chain(&view.spam)
                .chain(&view.archived)
                .filter_map(|r| Some((r.wa_chat.as_ref()?.to_string(), r.title.to_string())))
                .collect();
            let gateway_muted =
                inbox::gateway_muted_jids(&self.wa_info, &self.backend.read(cx).prefs().muted, now);
            self.settings.update(cx, |settings, cx| {
                settings.set_wa_titles(titles, cx);
                settings.set_wa_muted(gateway_muted, cx);
            });
            if view != self.view {
                self.view = view;
                self.view_gen += 1;
            }
            self.inbox_key = Some(key);
        }
        if self.view.spam.is_empty() {
            self.show_spam = false;
        }
        if self.view.archived.is_empty() {
            self.show_archived = false;
        }
        if let Open::WhatsApp(_, conversation) = &self.open {
            conversation.update(cx, |c, cx| c.set_locale(locale, cx));
        }
        self.update_switch(cx);
        self.sync_open_page(cx);
        self.save_drafts(cx);
    }

    /// Reads the drafts file once the data directory is known and merges it
    /// under this session's drafts.
    fn load_drafts(&mut self, cx: &mut Context<Self>) {
        if self.drafts_loading {
            return;
        }
        let Some(dir) = self.backend.read(cx).data_dir().map(Path::to_path_buf) else {
            return;
        };
        self.drafts_loading = true;
        let task = cx.background_executor().spawn(async move {
            storage::load_json::<BTreeMap<String, String>>(&dir, drafts::DRAFTS_FILE)
        });
        cx.spawn(async move |this, cx| {
            let map = task.await.unwrap_or_default();
            this.update(cx, |this, cx| {
                this.drafts.merge_loaded(drafts::Drafts::from_map(&map));
                this.drafts_loaded = true;
                this.rebuild(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Writes the drafts when they changed, one write at a time.
    fn save_drafts(&mut self, cx: &mut Context<Self>) {
        if !self.drafts_loaded || self.drafts_saving || self.drafts.rev() == self.drafts_saved_rev {
            return;
        }
        let Some(dir) = self.backend.read(cx).data_dir().map(Path::to_path_buf) else {
            return;
        };
        self.drafts_saving = true;
        let rev = self.drafts.rev();
        let map = self.drafts.to_map();
        let task = cx
            .background_executor()
            .spawn(async move { storage::save_json(&dir, drafts::DRAFTS_FILE, &map) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.drafts_saving = false;
                let saved = result.is_ok();
                match result {
                    Ok(()) => this.drafts_saved_rev = rev,
                    Err(error) => log::warn!("saving the drafts failed: {error}"),
                }
                // Changed meanwhile: write again right away. After a failure
                // the next rebuild retries.
                if saved {
                    this.save_drafts(cx);
                }
            })
            .ok();
        })
        .detach();
    }

    /// Puts the open page's composer text into the drafts (nothing closes).
    fn stash_open_draft(&mut self, cx: &App) {
        if let Some(thread) = self.open_thread_id(cx) {
            let text = match &self.open {
                Open::WhatsApp(_, conversation) => conversation.read(cx).draft(cx),
                Open::Sms(conversation) => conversation.read(cx).draft(cx),
                _ => String::new(),
            };
            self.drafts.put(thread, &text);
        }
    }

    /// Points the open conversation's header switch at the person's other thread.
    fn update_switch(&mut self, cx: &mut Context<Self>) {
        match &self.open {
            Open::WhatsApp(jid, conversation) => {
                let current = ThreadId::WhatsApp(jid.clone());
                let target = inbox::switch_target(&self.view, &current)
                    .map(|(thread, badge)| (thread.clone(), badge));
                conversation.update(cx, |c, cx| c.set_switch(target, cx));
            }
            Open::Sms(conversation) => {
                let current = conversation.read(cx).thread();
                let target = inbox::switch_target(&self.view, &current)
                    .map(|(thread, badge)| (thread.clone(), badge));
                conversation.update(cx, |c, cx| c.set_switch(target, cx));
            }
            Open::New(_) | Open::Status(_) | Open::None => {}
        }
    }

    fn on_gateway_events(
        &mut self,
        events: Vec<GatewayEvent>,
        connected: bool,
        cx: &mut Context<Self>,
    ) {
        for event in events {
            self.avatars
                .update(cx, |avatars, cx| avatars.on_event(&event, cx));
            if let Open::WhatsApp(_, conversation) = &self.open {
                conversation.update(cx, |conversation, cx| conversation.on_event(&event, cx));
            }
            match event {
                // An older gateway sends status updates as messages: they
                // belong on the Status page, never in the inbox.
                GatewayEvent::Message(message) if is_status_chat(&message.chat) => {}
                GatewayEvent::Message(message) => {
                    // An open chat in a window the user can't see is not read.
                    let viewing = self.window_active
                        && matches!(&self.open, Open::WhatsApp(jid, _) if *jid == message.chat);
                    inbox::fold_message(self.wa_threads_mut(), &message, viewing);
                    inbox::note_last(self.wa_info_mut(), &message);
                    self.notify_whatsapp(&message, cx);
                }
                GatewayEvent::MessageMeta {
                    chat,
                    id,
                    body,
                    meta,
                } => {
                    if inbox::note_meta(&mut self.wa_info, &chat, &id, body.as_ref(), &meta) {
                        self.wa_rev += 1;
                    }
                }
                GatewayEvent::Receipt { chat, ids, kind } => {
                    if inbox::note_receipt(&mut self.wa_info, &chat, &ids, kind) {
                        self.wa_rev += 1;
                    }
                }
                GatewayEvent::MessageDeleted { chat, id } => {
                    let was_last = self
                        .wa_info
                        .get(&chat)
                        .and_then(|info| info.last.as_ref())
                        .is_some_and(|last| last.id == id);
                    if was_last {
                        self.clear_preview(&chat);
                    }
                }
                GatewayEvent::ChatMeta { jid, meta } => {
                    let info = self.wa_info_mut().entry(jid.clone()).or_default();
                    info.meta = meta;
                    if self
                        .row_menu
                        .as_ref()
                        .is_some_and(|m| m.jid.as_deref() == Some(jid.as_str()))
                    {
                        self.row_menu = None;
                    }
                }
                GatewayEvent::ChatCleared { jid } => self.clear_preview(&jid),
                GatewayEvent::ChatDeleted { jid } => {
                    let id = ThreadId::WhatsApp(jid.clone());
                    self.wa_threads_mut().retain(|t| t.id != id);
                    self.wa_info_mut().remove(&jid);
                    if matches!(&self.open, Open::WhatsApp(open, _) if *open == jid) {
                        self.open = Open::None;
                        self.page.restart();
                    }
                    if self
                        .row_menu
                        .as_ref()
                        .is_some_and(|m| m.jid.as_deref() == Some(jid.as_str()))
                    {
                        self.row_menu = None;
                    }
                }
                GatewayEvent::ChatMerged { from, into } => {
                    self.wa_rev += 1;
                    inbox::apply_merge(&mut self.wa_threads, &mut self.wa_info, &from, &into);
                    let (from_id, into_id) = (
                        ThreadId::WhatsApp(from.clone()),
                        ThreadId::WhatsApp(into.clone()),
                    );
                    self.drafts.rekey(&from_id, &into_id);
                    let (from_tag, into_tag) = (
                        notifications::thread_tag(&from_id),
                        notifications::thread_tag(&into_id),
                    );
                    self.backend.update(cx, |backend, cx| {
                        backend.update_prefs(cx, |prefs| {
                            prefs.rekey_thread(&from_tag, &into_tag);
                        });
                    });
                    if let Open::WhatsApp(open, _) = &mut self.open
                        && *open == from
                    {
                        *open = into.clone();
                    }
                    if let Some(menu) = &mut self.row_menu
                        && menu.jid.as_deref() == Some(from.as_str())
                    {
                        menu.jid = Some(into.clone());
                    }
                    self.chats = ChatsState::Needed;
                    self.reload_statuses(cx);
                }
                GatewayEvent::Scheduled(item) => {
                    let before = self.remote_scheduled.iter().find(|t| t.id == item.id);
                    let announce = schedule_ui::newly_failed(before, &item)
                        && !matches!(&self.open, Open::WhatsApp(open, _) if *open == item.chat);
                    let chat = item.chat.clone();
                    schedule_ui::upsert_remote(&mut self.remote_scheduled, item);
                    if announce {
                        let title = self
                            .title_of(&ThreadId::WhatsApp(chat.clone()))
                            .unwrap_or_else(|| display_name(None, None, &chat));
                        self.show_pill_action(
                            schedule_ui::remote_failed_text(&title),
                            "Open",
                            PillAction::OpenChat { jid: chat, title },
                            cx,
                        );
                    }
                    self.sync_open_page(cx);
                }
                GatewayEvent::StatusChanged { .. } => self.reload_statuses(cx),
                // The stream missed events: fetch the chat list again.
                GatewayEvent::Resync => {
                    self.chats = ChatsState::Needed;
                    self.reload_statuses(cx);
                }
                _ => {}
            }
        }
        if !connected && self.whatsapp.read(cx).link().is_none() {
            // Unlinked: WhatsApp chats leave the inbox.
            self.wa_threads_mut().clear();
            self.wa_info_mut().clear();
            self.row_menu = None;
            if matches!(self.open, Open::WhatsApp(..) | Open::Status(_)) {
                self.open = Open::None;
            }
            self.chats = ChatsState::Needed;
            self.statuses.update(cx, |feed, cx| feed.clear(cx));
        } else if connected && self.chats == ChatsState::Needed {
            self.load_chats(cx);
            self.load_remote_scheduled(cx);
            self.reload_statuses(cx);
        }
        self.rebuild(cx);
    }

    /// Fetches the Status updates again (after a `status_changed` event).
    fn reload_statuses(&mut self, cx: &mut Context<Self>) {
        if let Some(gateway) = self.whatsapp.read(cx).gateway() {
            self.statuses
                .update(cx, |feed, cx| feed.reload(gateway, cx));
        }
    }

    /// Opens the Status page.
    fn open_status(&mut self, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        self.leave_page(cx);
        let (feed, backend, avatars) = (
            self.statuses.clone(),
            self.backend.clone(),
            self.avatars.clone(),
        );
        let active = self.window_active;
        let page = cx.new(|cx| StatusPage::new(feed, gateway, backend, avatars, active, cx));
        cx.subscribe(&page, |this, _, event, cx| match event {
            StatusPageEvent::Back => this.close_page(cx),
            StatusPageEvent::Message { jid, title } => {
                this.open_chat(jid.clone(), title.clone(), cx)
            }
        })
        .detach();
        self.open = Open::Status(page);
        self.page.restart();
        cx.notify();
    }

    /// A chat lost its messages (cleared, or its newest deleted for me): it
    /// stays in the list with an empty preview.
    fn clear_preview(&mut self, jid: &str) {
        let id = ThreadId::WhatsApp(jid.to_owned());
        for thread in self.wa_threads_mut().iter_mut().filter(|t| t.id == id) {
            thread.last.body.clear();
        }
        if let Some(info) = self.wa_info_mut().get_mut(jid) {
            info.last = None;
        }
    }

    /// Fills the inbox with each chat's last message and meta, off the UI
    /// thread.
    fn load_chats(&mut self, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        self.chats = ChatsState::Loading;
        let task = cx
            .background_executor()
            .spawn(async move { gateway.chats_with_meta() });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.chats = ChatsState::Loaded;
                match result {
                    Ok(chats) => {
                        let threads = inbox::chat_threads(&chats, &this.wa_threads);
                        let mut infos = inbox::chat_infos(&chats);
                        // A live message newer than the fetch keeps its preview.
                        for thread in &threads {
                            if let ThreadId::WhatsApp(jid) = &thread.id
                                && let Some(held) = this.wa_info.get(jid)
                                && let Some(last) = &held.last
                                && infos
                                    .get(jid)
                                    .and_then(|i| i.last.as_ref())
                                    .is_none_or(|fetched| fetched.timestamp_ms < last.timestamp_ms)
                            {
                                infos.entry(jid.clone()).or_default().last = Some(last.clone());
                            }
                        }
                        *this.wa_threads_mut() = threads;
                        *this.wa_info_mut() = infos;
                        this.rebuild(cx);
                    }
                    Err(ClientError::Unauthorized) => {
                        // The WhatsApp banner explains it.
                        log::warn!("loading WhatsApp chats: unauthorized");
                        this.chats = ChatsState::Needed;
                    }
                    Err(error) => {
                        log::warn!("loading WhatsApp chats failed: {error}");
                        // The next gateway event (or reconnect) tries again.
                        this.chats = ChatsState::Needed;
                        this.show_pill_action(
                            "Couldn't load WhatsApp chats",
                            "Retry",
                            PillAction::ReloadChats,
                            cx,
                        );
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---- opening and closing pages -------------------------------------------

    /// Opens a thread of the inbox, whichever channel it lives in.
    fn open_thread(&mut self, thread: ThreadId, title: String, cx: &mut Context<Self>) {
        match thread {
            ThreadId::WhatsApp(jid) => self.open_chat(jid, title, cx),
            ThreadId::Sms(thread_id) => {
                let recipients = self
                    .backend
                    .read(cx)
                    .threads()
                    .iter()
                    .find(|t| t.thread_id == thread_id)
                    .map(|t| t.addresses.clone())
                    .unwrap_or_default();
                self.open_sms(thread_id, title, recipients, cx);
            }
        }
    }

    fn open_chat(&mut self, jid: String, title: String, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        self.leave_page(cx);
        let is_group = self
            .wa_threads
            .iter()
            .find(|t| t.id == ThreadId::WhatsApp(jid.clone()))
            .map_or_else(|| jid.ends_with("@g.us"), |t| t.is_group);
        let locale = self.backend.read(cx).locale();
        let meta = self
            .wa_info
            .get(&jid)
            .map(|info| info.meta.clone())
            .unwrap_or_default();
        // Contact name, then WhatsApp's name, then the number.
        let title = display_name(Some(&title), meta.push_name.as_deref(), &jid);
        let row = self
            .view
            .all_rows()
            .find(|row| row.threads.contains(&ThreadId::WhatsApp(jid.clone())));
        let peer = Peer {
            initials: row.map_or_else(
                || inbox::initials(&title).into(),
                |row| row.initials.clone(),
            ),
            photo_uri: row.and_then(|row| row.avatar.photo_uri.clone()),
            picture_id: meta.picture_id.clone().map(SharedString::from),
        };
        let chat = jid.clone();
        // What was unread when the chat opened (it is read once the page is up).
        let unread = self
            .wa_threads
            .iter()
            .find(|t| t.id == ThreadId::WhatsApp(jid.clone()))
            .map_or(0, |t| t.unread);
        let backend = self.backend.clone();
        let avatars = self.avatars.clone();
        let photos = self.photos.clone();
        let conversation = cx.new(|cx| {
            Conversation::new(
                conversation::Setup {
                    chat,
                    title: title.into(),
                    is_group,
                    gateway,
                    locale,
                    backend,
                    avatars,
                    photos,
                    peer,
                    active: self.window_active,
                    unread,
                },
                cx,
            )
        });
        cx.subscribe(&conversation, |this, _, event, cx| match event {
            ConversationEvent::Back => this.close_page(cx),
            ConversationEvent::Switch(thread) => this.switch_to(thread.clone(), cx),
            ConversationEvent::Schedule(text) => this.ask_schedule_whatsapp(text.clone(), cx),
            ConversationEvent::CancelScheduled(key) => this.cancel_scheduled(key.clone(), cx),
            ConversationEvent::ToggleMute => this.toggle_open_mute(cx),
            ConversationEvent::Menu => this.open_wa_page_menu(cx),
            ConversationEvent::OpenSettings => this.open_settings_tab(cx),
            ConversationEvent::Copy(text) => this.copy_text(text.clone().into(), false, cx),
            ConversationEvent::Share(text) => this.share_text(text.clone(), cx),
            ConversationEvent::Call(n) => {
                this.telecom_action(sms_actions::SmsAction::Call, n.clone(), cx);
            }
            ConversationEvent::AddContact(n) => this.add_or_open_contact(n.clone(), cx),
            ConversationEvent::Email(a) => this.email_to(a.clone(), cx),
            ConversationEvent::Message { name, number } => this.open_recipient(
                &Recipient {
                    title: name.clone(),
                    number: number.clone(),
                    detail: None,
                },
                cx,
            ),
            ConversationEvent::Notice(text) => {
                this.show_pill(text.clone());
                cx.notify();
            }
            ConversationEvent::OpenMedia { items, index } => {
                this.open_media_viewer(items.clone(), *index, cx);
            }
            ConversationEvent::OpenAvatar(item) => {
                this.open_media_viewer(vec![item.clone()], 0, cx);
            }
            ConversationEvent::Forward(item) => this.open_forward(item.clone(), cx),
        })
        .detach();
        // Opening reads the chat.
        let id = ThreadId::WhatsApp(jid.clone());
        self.dismiss_notification(&id, cx);
        self.load_remote_scheduled(cx);
        for thread in self.wa_threads_mut().iter_mut().filter(|t| t.id == id) {
            thread.unread = 0;
        }
        // Opening a chat marked unread by hand takes the mark away everywhere.
        if meta.marked_unread {
            self.chat_action(&jid, ChatActionKind::MarkRead { read: true }, true, cx);
        }
        if let Some(text) = self.drafts.take(&ThreadId::WhatsApp(jid.clone())) {
            conversation.update(cx, |c, cx| c.set_draft(&text, cx));
        }
        self.open = Open::WhatsApp(jid, conversation);
        self.rebuild(cx);
        self.page.restart();
        cx.notify();
    }

    fn open_sms(
        &mut self,
        thread_id: i64,
        title: String,
        recipients: Vec<String>,
        cx: &mut Context<Self>,
    ) {
        self.leave_page(cx);
        let backend = self.backend.clone();
        let unread = self
            .backend
            .read(cx)
            .threads()
            .iter()
            .find(|t| t.thread_id == thread_id)
            .map_or(0, |t| t.unread);
        let conversation =
            cx.new(|cx| SmsConversation::new(backend, thread_id, title, recipients, unread, cx));
        let row = self
            .view
            .all_rows()
            .find(|row| row.threads.contains(&ThreadId::Sms(thread_id)));
        if let Some(row) = row
            && let Some(uri) = row.avatar.photo_uri.clone()
        {
            let (photos, initials) = (self.photos.clone(), row.initials.clone());
            conversation.update(cx, |page, cx| {
                page.set_peer_photo(photos, uri, initials, cx)
            });
        }
        cx.subscribe(&conversation, |this, _, event, cx| match event {
            SmsConversationEvent::Back => this.close_page(cx),
            SmsConversationEvent::Switch(thread) => this.switch_to(thread.clone(), cx),
            SmsConversationEvent::Schedule { to, body, sub_id } => {
                this.ask_schedule_sms(to.clone(), body.clone(), *sub_id, cx);
            }
            SmsConversationEvent::CancelScheduled(key) => this.cancel_scheduled(key.clone(), cx),
            SmsConversationEvent::ToggleMute => this.toggle_open_mute(cx),
            SmsConversationEvent::Copy { text, sensitive } => {
                this.copy_text(text.clone().into(), *sensitive, cx);
            }
            SmsConversationEvent::Share(text) => this.share_text(text.clone(), cx),
            SmsConversationEvent::Call(n) => {
                this.telecom_action(sms_actions::SmsAction::Call, n.clone(), cx);
            }
            SmsConversationEvent::Message(number) => this.open_recipient(
                &Recipient {
                    title: number.clone(),
                    number: number.clone(),
                    detail: None,
                },
                cx,
            ),
            SmsConversationEvent::Email(a) => this.email_to(a.clone(), cx),
            SmsConversationEvent::Menu => this.open_page_menu(cx),
            SmsConversationEvent::OpenSettings => this.open_settings_tab(cx),
            SmsConversationEvent::NotSpam => this.open_page_not_spam(cx),
            SmsConversationEvent::OpenMedia { items, index } => {
                this.open_media_viewer(items.clone(), *index, cx);
            }
            SmsConversationEvent::OpenAvatar(item) => {
                this.open_media_viewer(vec![item.clone()], 0, cx);
            }
            SmsConversationEvent::Forward(item) => this.open_forward(item.clone(), cx),
            SmsConversationEvent::Notice(text) => {
                this.show_pill(*text);
                cx.notify();
            }
        })
        .detach();
        if thread_id >= 0 {
            self.dismiss_notification(&ThreadId::Sms(thread_id), cx);
        }
        if thread_id >= 0
            && let Some(text) = self.drafts.take(&ThreadId::Sms(thread_id))
        {
            conversation.update(cx, |c, cx| c.set_draft(&text, cx));
        }
        self.open = Open::Sms(conversation);
        self.update_switch(cx);
        self.sync_open_page(cx);
        self.page.restart();
        cx.notify();
    }

    /// The header's channel switch: the same person's other thread.
    fn switch_to(&mut self, thread: ThreadId, cx: &mut Context<Self>) {
        let title = self.title_of(&thread).unwrap_or_default();
        self.open_thread(thread, title, cx);
    }

    /// The inbox's title for a thread, when the inbox has it.
    fn title_of(&self, thread: &ThreadId) -> Option<String> {
        self.view
            .rows
            .iter()
            .chain(&self.view.spam)
            .find(|row| row.threads.contains(thread))
            .map(|row| row.title.to_string())
    }

    fn open_new(&mut self, cx: &mut Context<Self>) {
        self.leave_page(cx);
        let backend = self.backend.clone();
        let entry = cx.new(|cx| NewConversation::new(backend, cx));
        cx.subscribe(&entry, |this, _, event, cx| match event {
            NewConversationEvent::Back => this.close_page(cx),
            NewConversationEvent::Open(recipient) => this.open_recipient(recipient, cx),
        })
        .detach();
        self.open = Open::New(entry);
        self.page.restart();
        cx.notify();
    }

    /// A recipient picked in the new-message entry: their SMS thread when there
    /// is one, else a draft that the first send turns into a thread.
    fn open_recipient(&mut self, recipient: &Recipient, cx: &mut Context<Self>) {
        if self.whatsapp.read(cx).gateway().is_some() {
            self.channel_pick = Some(recipient.clone());
            cx.notify();
            return;
        }
        self.open_recipient_sms(recipient, cx);
    }

    /// The chosen WhatsApp channel for a recipient: an existing chat, else a
    /// background lookup of the bare number.
    fn open_recipient_whatsapp(&mut self, recipient: &Recipient, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            self.open_recipient_sms(recipient, cx);
            return;
        };
        let users: Vec<String> = self
            .wa_threads
            .iter()
            .filter_map(|t| match &t.id {
                ThreadId::WhatsApp(jid) if !jid.ends_with("@g.us") => {
                    Some(jid_user(jid).to_owned())
                }
                _ => None,
            })
            .collect();
        let users: Vec<&str> = users.iter().map(String::as_str).collect();
        let title = recipient.title.clone();
        match rovsti_wa_proto::wa_target(&[recipient.number.as_str()], &users) {
            rovsti_wa_proto::WaTarget::Chat(jid) => self.open_chat(jid, title, cx),
            rovsti_wa_proto::WaTarget::Number(jid) => {
                let task = cx
                    .background_executor()
                    .spawn(async move { gateway.lookup_users(&[jid]) });
                cx.spawn(async move |this, cx| {
                    let result = task.await;
                    this.update(cx, |this, cx| match result {
                        Ok(users) => match new_conversation::registered_jid(&users) {
                            Some(jid) => this.open_chat(jid, title, cx),
                            None => this.show_pill(format!("{title} is not on WhatsApp")),
                        },
                        Err(_) => this.show_pill("Can't check WhatsApp right now."),
                    })
                    .ok();
                    this.update(cx, |_, cx| cx.notify()).ok();
                })
                .detach();
            }
            rovsti_wa_proto::WaTarget::NeedsCountryCode | rovsti_wa_proto::WaTarget::None => {
                self.show_pill("Add the country code to message on WhatsApp");
                cx.notify();
            }
        }
    }

    /// The SMS channel for a recipient.
    fn open_recipient_sms(&mut self, recipient: &Recipient, cx: &mut Context<Self>) {
        let found = inbox::thread_for_number(self.backend.read(cx).threads(), &recipient.number)
            .map(|t| (t.thread_id, t.addresses.clone()));
        match found {
            Some((thread_id, addresses)) => {
                self.open_sms(thread_id, recipient.title.clone(), addresses, cx);
            }
            None => {
                self.open_sms(
                    -1,
                    recipient.title.clone(),
                    vec![recipient.number.clone()],
                    cx,
                );
            }
        }
    }

    /// A page is about to replace the open one (a notification, the channel
    /// switch): layers that belonged to the old page go with it.
    fn leave_page(&mut self, cx: &App) {
        if let Some(thread) = self.open_thread_id(cx) {
            let text = match &self.open {
                Open::WhatsApp(_, conversation) => conversation.read(cx).draft(cx),
                Open::Sms(conversation) => conversation.read(cx).draft(cx),
                _ => String::new(),
            };
            self.drafts.put(thread, &text);
        }
        self.schedule.close();
        self.row_menu = None;
    }

    /// The "Add quick replies in Settings" chip: leave the page for Settings.
    fn open_settings_tab(&mut self, cx: &mut Context<Self>) {
        self.close_page(cx);
        if self.tabs.select(SETTINGS_TAB) {
            self.page.restart();
        }
        cx.notify();
    }

    fn close_page(&mut self, cx: &mut Context<Self>) {
        self.leave_page(cx);
        self.open = Open::None;
        self.rebuild(cx);
        self.page.restart();
        cx.notify();
    }

    // ---- WhatsApp chat actions -------------------------------------------------

    /// Long press on a WhatsApp row: its menu.
    fn open_row_menu(
        &mut self,
        jid: Option<String>,
        sms: Option<(i64, Option<String>)>,
        title: SharedString,
        cx: &mut Context<Self>,
    ) {
        let local_muted = jid.as_ref().is_some_and(|jid| {
            self.backend
                .read(cx)
                .prefs()
                .is_muted(&notifications::thread_tag(&ThreadId::WhatsApp(jid.clone())))
        });
        let (unread, meta) = jid.as_ref().map_or_else(
            || (0, ChatMeta::default()),
            |jid| {
                let id = ThreadId::WhatsApp(jid.clone());
                let unread = self
                    .wa_threads
                    .iter()
                    .find(|t| t.id == id)
                    .map_or(0, |t| t.unread);
                let meta = self
                    .wa_info
                    .get(jid)
                    .map(|info| info.meta.clone())
                    .unwrap_or_default();
                (unread, meta)
            },
        );
        let sms = sms.map(|(thread_id, sender)| {
            let can_be_spam = self.view.sms_only(&ThreadId::Sms(thread_id));
            let backend = self.backend.read(cx);
            let blocked = sender
                .as_deref()
                .is_some_and(|s| backend.blocked().is_blocked(s));
            let contact_id = sender
                .as_deref()
                .and_then(|s| backend.contacts().match_number(s).map(|c| c.id));
            let sender_is_number = is_number_sender(sender.as_deref());
            SmsRowMenu {
                thread_id,
                sender,
                unread: backend
                    .threads()
                    .iter()
                    .any(|t| t.thread_id == thread_id && t.unread > 0),
                muted: backend
                    .prefs()
                    .is_muted(&notifications::thread_tag(&ThreadId::Sms(thread_id))),
                in_spam: self.show_spam,
                on_page: false,
                contact_id,
                blocked,
                sender_is_number,
                can_be_spam,
            }
        });
        self.row_menu = Some(RowMenu {
            jid,
            title,
            meta,
            local_muted,
            unread,
            mute_choices: false,
            sms,
            delete_confirm: false,
            page_number: None,
            contact_id: None,
            send_later: false,
        });
        cx.notify();
    }

    /// Mutes or unmutes an SMS thread and offers Undo.
    fn set_sms_mute(&mut self, id: i64, mute: bool, cx: &mut Context<Self>) {
        let tag = notifications::thread_tag(&ThreadId::Sms(id));
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |p| p.set_muted(&tag, mute))
        });
        let text = if mute { "Muted" } else { "Unmuted" };
        self.show_pill_action(text, "Undo", PillAction::SmsMute(id, !mute), cx);
    }

    /// Move to Spam: a sender rule and off the allow list, with Undo.
    fn move_to_spam(&mut self, sender: String, title: SharedString, cx: &mut Context<Self>) {
        let mut result = None;
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |p| result = Some(p.mark_spam(&sender)))
        });
        match result {
            Some(Ok(mark)) => self.show_pill_action(
                sms_actions::moved_to_spam_text(&title),
                "Undo",
                PillAction::Unspam { sender, mark },
                cx,
            ),
            Some(Err(rovsti_core::spam::RuleError::TooMany)) => {
                self.show_pill(sms_actions::TOO_MANY_RULES)
            }
            _ => self.show_pill("Couldn't move that to Spam"),
        }
    }

    /// One SMS item of the row menu.
    fn sms_row_action(&mut self, action: sms_actions::SmsAction, cx: &mut Context<Self>) {
        use sms_actions::SmsAction;
        let Some(sms) = self.row_menu.as_ref().and_then(|m| m.sms.clone()) else {
            return;
        };
        let id = sms.thread_id;
        match action {
            SmsAction::Delete => {
                let backend = self.backend.read(cx);
                if !backend.sms_role_held() && !backend.is_preview() {
                    self.row_menu = None;
                    self.show_pill_action(
                        sms_actions::NEEDS_ROLE,
                        sms_actions::MAKE_DEFAULT,
                        PillAction::RequestSmsRole,
                        cx,
                    );
                } else if let Some(menu) = &mut self.row_menu {
                    menu.delete_confirm = true;
                }
            }
            SmsAction::MarkRead => {
                self.row_menu = None;
                self.backend
                    .update(cx, |backend, cx| backend.mark_read(id, cx));
            }
            SmsAction::Mute | SmsAction::Unmute => {
                self.row_menu = None;
                self.set_sms_mute(id, action == SmsAction::Mute, cx);
            }
            SmsAction::NotSpam => {
                self.row_menu = None;
                if let Some(sender) = sms.sender {
                    self.not_spam(sender, cx);
                }
            }
            SmsAction::Block => {
                let title = self.row_menu.take().map(|m| m.title).unwrap_or_default();
                if let Some(sender) = sms.sender {
                    self.block_sender(sender, title, cx);
                }
            }
            SmsAction::Unblock => {
                self.row_menu = None;
                if let Some(sender) = sms.sender {
                    self.unblock_sender(sender, cx);
                }
            }
            SmsAction::OpenContact => {
                self.row_menu = None;
                if let Some(contact_id) = sms.contact_id {
                    let task = cx
                        .background_executor()
                        .spawn(async move { rovsti_android::telecom::edit_contact(contact_id) });
                    cx.spawn(async move |this, cx| {
                        let result = task.await;
                        this.update(cx, |this, cx| {
                            match result {
                                Ok(true) => {}
                                Err(rovsti_android::Error::Unsupported) => {
                                    this.show_pill("Contacts need an Android phone.");
                                }
                                Ok(false) | Err(_) => this.show_pill("Couldn't open that contact."),
                            }
                            cx.notify();
                        })
                        .ok();
                    })
                    .detach();
                }
            }
            SmsAction::Call | SmsAction::AddContact => {
                self.row_menu = None;
                if let Some(sender) = sms.sender {
                    self.telecom_action(action, sender, cx);
                }
            }
            SmsAction::MoveToSpam => {
                let title = self.row_menu.take().map(|m| m.title).unwrap_or_default();
                if let Some(sender) = sms.sender {
                    self.move_to_spam(sender, title, cx);
                }
            }
        }
        cx.notify();
    }

    /// The open WhatsApp page's more button: the chat menu plus Call and contact for 1:1 chats.
    fn open_wa_page_menu(&mut self, cx: &mut Context<Self>) {
        let Open::WhatsApp(jid, conversation) = &self.open else {
            return;
        };
        let jid = jid.clone();
        let title = conversation.read(cx).title();
        self.open_row_menu(Some(jid.clone()), None, title, cx);
        let number = wa_page_number(&jid);
        let contact_id = number.as_deref().and_then(|n| {
            self.backend
                .read(cx)
                .contacts()
                .match_number(n)
                .map(|c| c.id)
        });
        if let Some(menu) = &mut self.row_menu {
            menu.page_number = number;
            menu.contact_id = contact_id;
            menu.send_later = true;
        }
        cx.notify();
    }

    /// "Search in chat" of a page menu: closes the menu and opens the find bar of the open page.
    fn menu_find(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.row_menu = None;
        match &self.open {
            Open::WhatsApp(_, conversation) => {
                conversation.update(cx, |c, cx| c.begin_find(window, cx));
            }
            Open::Sms(conversation) => {
                conversation.update(cx, |c, cx| c.begin_find(window, cx));
            }
            _ => {}
        }
        cx.notify();
    }

    /// "Starred messages" of a WhatsApp page menu: closes the menu and opens the sheet.
    fn menu_starred(&mut self, cx: &mut Context<Self>) {
        self.row_menu = None;
        if let Open::WhatsApp(_, c) = &self.open {
            c.update(cx, |c, cx| c.open_starred(cx));
        }
        cx.notify();
    }

    /// "Export chat" / "Share as text" of a page menu: the open page writes or shares its
    /// loaded messages.
    fn menu_export(&mut self, share: bool, cx: &mut Context<Self>) {
        self.row_menu = None;
        match &self.open {
            Open::WhatsApp(_, c) => c.update(cx, |c, cx| {
                if share {
                    c.share_chat(cx)
                } else {
                    c.export_chat(cx)
                }
            }),
            Open::Sms(c) => c.update(cx, |c, cx| {
                if share {
                    c.share_chat(cx)
                } else {
                    c.export_chat(cx)
                }
            }),
            _ => {}
        }
        cx.notify();
    }

    /// "Send later" of a page menu: closes the menu and asks the open page to schedule its
    /// composer text; a blank composer gets the "type first" pill.
    fn menu_send_later(&mut self, cx: &mut Context<Self>) {
        self.row_menu = None;
        let (scheduled, draft) = match &self.open {
            Open::WhatsApp(_, conversation) => (
                conversation.update(cx, |c, cx| c.request_schedule(cx)),
                conversation.read(cx).draft(cx),
            ),
            Open::Sms(conversation) => (
                conversation.update(cx, |c, cx| c.request_schedule(cx)),
                conversation.read(cx).draft(cx),
            ),
            _ => (true, String::new()),
        };
        if schedule_ui::explain_type_first(scheduled, &draft) {
            self.show_pill(schedule_ui::TYPE_FIRST);
        }
        cx.notify();
    }

    /// Call or Add/Open contact from a WhatsApp page menu.
    fn wa_page_contact_action(&mut self, action: sms_actions::SmsAction, cx: &mut Context<Self>) {
        let Some(menu) = self.row_menu.take() else {
            return;
        };
        let Some(number) = menu.page_number else {
            return;
        };
        match (action, menu.contact_id) {
            (sms_actions::SmsAction::AddContact, Some(contact_id)) => {
                let task = cx
                    .background_executor()
                    .spawn(async move { rovsti_android::telecom::edit_contact(contact_id) });
                cx.spawn(async move |this, cx| {
                    let result = task.await;
                    this.update(cx, |this, cx| {
                        match result {
                            Ok(true) => {}
                            Err(rovsti_android::Error::Unsupported) => {
                                this.show_pill("Contacts need an Android phone.");
                            }
                            Ok(false) | Err(_) => this.show_pill("Couldn't open that contact."),
                        }
                        cx.notify();
                    })
                    .ok();
                })
                .detach();
            }
            _ => self.telecom_action(action, number, cx),
        }
        cx.notify();
    }

    /// The Spam banner's "Not spam" on the open SMS page; the page stays open.
    fn open_page_not_spam(&mut self, cx: &mut Context<Self>) {
        let Open::Sms(conversation) = &self.open else {
            return;
        };
        let sender = sms_actions::sender_of(conversation.read(cx).recipients()).map(str::to_owned);
        if let Some(sender) = sender {
            self.not_spam(sender, cx);
            cx.notify();
        }
    }

    /// The open SMS page's more button: its menu (Call, Add to contacts, Block, Delete).
    fn open_page_menu(&mut self, cx: &mut Context<Self>) {
        let Open::Sms(conversation) = &self.open else {
            return;
        };
        let (thread_id, title, recipients) = {
            let c = conversation.read(cx);
            let ThreadId::Sms(id) = c.thread() else {
                return;
            };
            (id, c.title(), c.recipients().to_vec())
        };
        let sender = sms_actions::sender_of(&recipients).map(str::to_owned);
        let in_spam = self.view.in_spam(&ThreadId::Sms(thread_id));
        let can_be_spam = self.view.sms_only(&ThreadId::Sms(thread_id));
        let sender_is_number = is_number_sender(sender.as_deref());
        let (contact_id, blocked, muted) = {
            let backend = self.backend.read(cx);
            (
                sender
                    .as_deref()
                    .and_then(|s| backend.contacts().match_number(s).map(|c| c.id)),
                sender
                    .as_deref()
                    .is_some_and(|s| backend.blocked().is_blocked(s)),
                backend
                    .prefs()
                    .is_muted(&notifications::thread_tag(&ThreadId::Sms(thread_id))),
            )
        };
        self.row_menu = Some(RowMenu {
            jid: None,
            title,
            meta: ChatMeta::default(),
            local_muted: false,
            unread: 0,
            mute_choices: false,
            sms: Some(SmsRowMenu {
                thread_id,
                sender,
                unread: false,
                muted,
                in_spam,
                on_page: true,
                contact_id,
                blocked,
                sender_is_number,
                can_be_spam,
            }),
            delete_confirm: false,
            page_number: None,
            contact_id: None,
            send_later: true,
        });
        cx.notify();
    }

    /// Add to contacts, or open the contact when the number already matches one.
    fn add_or_open_contact(&mut self, number: String, cx: &mut Context<Self>) {
        let contact_id = self
            .backend
            .read(cx)
            .contacts()
            .match_number(&number)
            .map(|c| c.id);
        let Some(contact_id) = contact_id else {
            self.telecom_action(sms_actions::SmsAction::AddContact, number, cx);
            return;
        };
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::telecom::edit_contact(contact_id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {}
                    Err(rovsti_android::Error::Unsupported) => {
                        this.show_pill("Contacts need an Android phone.");
                    }
                    Ok(false) | Err(_) => this.show_pill("Couldn't open that contact."),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Opens the mail composer for an address found in a message.
    fn email_to(&mut self, address: String, cx: &mut Context<Self>) {
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::telecom::send_email_to(&address) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {}
                    Err(rovsti_android::Error::Unsupported) => {
                        this.show_pill("That needs an Android phone.");
                    }
                    Ok(false) | Err(_) => this.show_pill("No mail app answered"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Call or Add to contacts from the page menu, asked of Android off the UI thread.
    fn telecom_action(
        &mut self,
        action: sms_actions::SmsAction,
        number: String,
        cx: &mut Context<Self>,
    ) {
        let call = action == sms_actions::SmsAction::Call;
        let task = cx.background_executor().spawn(async move {
            if call {
                rovsti_android::telecom::place_call(&number)
            } else {
                rovsti_android::telecom::add_contact(&number)
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {}
                    Err(rovsti_android::Error::Unsupported) => {
                        this.show_pill("That needs an Android phone.");
                    }
                    Ok(false) | Err(_) => this.show_pill(if call {
                        "Couldn't place that call"
                    } else {
                        "Couldn't open Contacts"
                    }),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Not spam: the sender joins the allow list, so its row moves to the inbox.
    fn not_spam(&mut self, sender: String, cx: &mut Context<Self>) {
        let mut added = false;
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |p| added = p.allow_sender(&sender));
        });
        if added {
            self.show_pill_action(
                sms_actions::not_spam_text(&sender),
                "Undo",
                PillAction::Unallow(sender),
                cx,
            );
        } else {
            self.show_pill("Already allowed");
        }
    }

    /// Block: refuses emergency numbers, asks Android off the UI thread, then
    /// re-pulls the blocklist. The pill offers Undo.
    fn block_sender(&mut self, raw: String, title: SharedString, cx: &mut Context<Self>) {
        if let Err(refusal) = blocklist::can_block(&raw, &EmergencyList::default()) {
            self.show_pill(sms_actions::refusal_note(refusal));
            return;
        }
        let number = raw.clone();
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::blocklist::block(&number) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {
                        this.backend
                            .update(cx, |backend, cx| backend.pull_blocked(cx));
                        this.show_pill_action(
                            sms_actions::blocked_text(&title),
                            "Undo",
                            PillAction::Unblock(raw),
                            cx,
                        );
                    }
                    Err(rovsti_android::Error::Unsupported) => {
                        this.show_pill("Blocking needs an Android phone.");
                    }
                    Ok(false) | Err(_) => this.show_pill("Android refused that change."),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The Chats title's check button: asks before marking every unread chat read.
    fn ask_mark_all(&mut self, cx: &mut Context<Self>) {
        let targets = inbox::mark_all_targets(&self.view.rows);
        if targets.is_empty() {
            self.show_pill("Nothing to mark as read");
            return;
        }
        let prompt = inbox::mark_all_prompt(&targets);
        self.show_pill_action(prompt, "Mark", PillAction::MarkAll(targets), cx);
    }

    /// Marks the confirmed chats read. SMS goes through the backend, which asks Android off
    /// the UI thread; WhatsApp chats go to the gateway one by one. Only a WhatsApp-only set
    /// can be undone.
    fn mark_all_read(&mut self, targets: inbox::MarkAllTargets, cx: &mut Context<Self>) {
        let count = targets.count();
        let undo = targets.undoable().then(|| targets.wa_chats.clone());
        for id in &targets.sms_threads {
            let id = *id;
            self.backend
                .update(cx, |backend, cx| backend.mark_read(id, cx));
        }
        for jid in &targets.wa_chats {
            self.chat_action(jid, ChatActionKind::MarkRead { read: true }, true, cx);
        }
        let text = inbox::mark_all_text(count);
        match undo {
            Some(chats) => self.show_pill_action(text, "Undo", PillAction::UnmarkAll(chats), cx),
            None => self.show_pill(text),
        }
    }

    /// Shows the pill with a trailing button and remembers what it does.
    fn show_pill_action(
        &mut self,
        text: impl Into<SharedString>,
        label: &'static str,
        action: PillAction,
        cx: &mut Context<Self>,
    ) {
        let token = self.pill.show(text, Some(label.into()), true, false);
        self.pill_action = Some((token, action));
        cx.notify();
    }

    /// The trailing button was tapped. A pill replaced meanwhile owns another
    /// token, so a stale tap does nothing.
    fn pill_fired(&mut self, cx: &mut Context<Self>) {
        let Some((token, action)) = self.pill_action.take() else {
            return;
        };
        if token != self.pill.token() {
            return;
        }
        self.pill.dismiss();
        match action {
            PillAction::Unallow(sender) => {
                self.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| {
                        p.unallow_sender(&sender);
                    })
                });
                self.show_pill("Back in Spam");
            }
            PillAction::Unspam { sender, mark } => {
                self.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| p.unmark_spam(&sender, mark))
                });
                self.show_pill("Back in the inbox");
            }
            PillAction::Settings(undo) => self.undo_settings(undo, cx),
            PillAction::Chat(jid, undo) => self.chat_action(&jid, undo, true, cx),
            PillAction::SmsMute(id, mute) => self.set_sms_mute(id, mute, cx),
            PillAction::ReloadChats => self.load_chats(cx),
            PillAction::MarkAll(targets) => self.mark_all_read(targets, cx),
            PillAction::UnmarkAll(chats) => {
                for jid in chats {
                    self.chat_action(&jid, ChatActionKind::MarkRead { read: false }, true, cx);
                }
            }
            PillAction::OpenChat { jid, title } => self.open_chat(jid, title, cx),
            PillAction::OpenThread { thread, title } => self.open_thread(thread, title, cx),
            PillAction::ReopenScheduled { to, body } => {
                let title = self
                    .backend
                    .read(cx)
                    .contacts()
                    .match_number(&to)
                    .map_or_else(|| to.clone(), |c| c.display_name.clone());
                self.open_recipient_sms(
                    &Recipient {
                        title,
                        number: to,
                        detail: None,
                    },
                    cx,
                );
                if let Open::Sms(conversation) = &self.open {
                    conversation.update(cx, |c, cx| c.set_draft(&body, cx));
                }
            }
            PillAction::Reblock(raw) => self.reblock(raw, cx),
            PillAction::RequestSmsRole => self
                .backend
                .update(cx, |backend, cx| backend.request_sms_role(cx)),
            PillAction::Unblock(raw) => {
                let task = cx
                    .background_executor()
                    .spawn(async move { rovsti_android::blocklist::unblock(&raw) });
                cx.spawn(async move |this, cx| {
                    let result = task.await;
                    this.update(cx, |this, cx| {
                        match result {
                            Ok(true) => {
                                this.backend
                                    .update(cx, |backend, cx| backend.pull_blocked(cx));
                                this.show_pill("Unblocked");
                            }
                            _ => this.show_pill("Couldn't unblock that number"),
                        }
                        cx.notify();
                    })
                    .ok();
                })
                .detach();
            }
        }
        cx.notify();
    }

    /// Unblock from a menu: asks Android off the UI thread, re-pulls the
    /// blocklist; the pill offers Undo.
    fn unblock_sender(&mut self, raw: String, cx: &mut Context<Self>) {
        let number = raw.clone();
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::blocklist::unblock(&number) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {
                        this.backend
                            .update(cx, |backend, cx| backend.pull_blocked(cx));
                        this.show_pill_action("Unblocked", "Undo", PillAction::Reblock(raw), cx);
                    }
                    Err(rovsti_android::Error::Unsupported) => {
                        this.show_pill("Unblocking needs an Android phone.");
                    }
                    Ok(false) | Err(_) => this.show_pill("Couldn't unblock that number"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Blocks a number again (Undo of an unblock or of a Settings delete).
    fn reblock(&mut self, raw: String, cx: &mut Context<Self>) {
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::blocklist::block(&raw) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {
                        this.backend
                            .update(cx, |backend, cx| backend.pull_blocked(cx));
                        this.show_pill("Blocked again");
                    }
                    _ => this.show_pill("Couldn't block that number"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Puts back what a Settings delete removed.
    fn undo_settings(&mut self, undo: SettingsUndo, cx: &mut Context<Self>) {
        match undo {
            SettingsUndo::Rule(index, rule) => {
                self.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| {
                        p.spam.insert_rule(index, rule);
                    })
                });
                self.show_pill("Rule restored");
            }
            SettingsUndo::Allow(sender) => {
                self.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| {
                        p.allow_sender(&sender);
                    })
                });
                self.show_pill("Sender allowed again");
            }
            SettingsUndo::Mute(tag) => {
                self.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| p.set_muted(&tag, true))
                });
                self.show_pill("Muted again");
            }
            SettingsUndo::Prefs(old) => {
                self.backend
                    .update(cx, |backend, cx| backend.update_prefs(cx, |p| *p = *old));
                self.show_pill("Settings restored");
            }
            SettingsUndo::QuickReply(index, text) => {
                self.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| {
                        rovsti_core::quick_replies::restore(&mut p.quick_replies, index, text);
                    });
                });
                self.show_pill("Reply restored");
            }
            SettingsUndo::Block(raw) => self.reblock(raw, cx),
        }
        cx.notify();
    }

    /// The confirmed delete: the backend does it and answers with an event.
    fn confirm_delete_thread(&mut self, cx: &mut Context<Self>) {
        let Some(menu) = self.row_menu.take() else {
            return;
        };
        if let Some(sms) = menu.sms {
            self.backend
                .update(cx, |backend, cx| backend.delete_thread(sms.thread_id, cx));
        }
        cx.notify();
    }

    /// A conversation is gone: its page, notification and menu go with it.
    fn on_thread_deleted(&mut self, thread_id: i64, cx: &mut Context<Self>) {
        let thread = ThreadId::Sms(thread_id);
        if self.open_thread_id(cx).as_ref() == Some(&thread) {
            self.close_page(cx);
        }
        self.drafts.forget(&thread);
        self.dismiss_notification(&thread, cx);
        self.show_pill("Conversation deleted");
        cx.notify();
    }

    /// Archive, pin, mute or mark a chat: shown at once, then sent to the
    /// gateway (its `chat_meta` event confirms it on every device).
    fn chat_action(
        &mut self,
        jid: &str,
        action: ChatActionKind,
        quiet: bool,
        cx: &mut Context<Self>,
    ) {
        self.row_menu = None;
        if matches!(action, ChatActionKind::Unmute) {
            let tag = notifications::thread_tag(&ThreadId::WhatsApp(jid.to_owned()));
            self.backend.update(cx, |backend, cx| {
                if backend.prefs().is_muted(&tag) {
                    backend.update_prefs(cx, |prefs| prefs.set_muted(&tag, false));
                }
            });
        }
        if matches!(action, ChatActionKind::Archive { archived: true })
            && matches!(&self.open, Open::WhatsApp(open, _) if open == jid)
        {
            self.close_page(cx);
        }
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            self.show_pill("WhatsApp is not connected");
            cx.notify();
            return;
        };
        let id = ThreadId::WhatsApp(jid.to_owned());
        let mut unread = self
            .wa_threads
            .iter()
            .find(|t| t.id == id)
            .map_or(0, |t| t.unread);
        let info = self.wa_info_mut().entry(jid.to_owned()).or_default();
        let before = info.meta.clone();
        inbox::apply_action(&mut info.meta, &mut unread, &action);
        for thread in self.wa_threads_mut().iter_mut().filter(|t| t.id == id) {
            thread.unread = unread;
        }
        self.rebuild(cx);
        if let Some(text) = inbox::action_notice(&action) {
            match inbox::undo_of(&action, &before).filter(|_| !quiet) {
                Some(undo) => {
                    self.show_pill_action(text, "Undo", PillAction::Chat(jid.to_owned(), undo), cx)
                }
                None => self.show_pill(text),
            }
        }
        let request = ChatAction {
            chat: jid.to_owned(),
            action,
        };
        let task = cx
            .background_executor()
            .spawn(async move { gateway.chat_action(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("a WhatsApp chat action failed: {error}");
                this.update(cx, |this, cx| {
                    this.show_pill("Couldn't change that chat");
                    // The list shows what the gateway really has.
                    this.load_chats(cx);
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    // ---- forward -------------------------------------------------------------

    /// Opens the "Forward to" chooser for `item`, leaving out the open chat.
    pub(crate) fn open_forward(&mut self, item: forward_ui::ForwardItem, cx: &mut Context<Self>) {
        let field =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Search, cx));
        cx.observe(&field, |_, _, cx| cx.notify()).detach();
        self.forward = Some(forward_ui::ForwardSheet {
            item,
            field,
            exclude: self.open_thread_id(cx),
        });
        cx.notify();
    }

    fn close_forward(&mut self, cx: &mut Context<Self>) {
        self.forward = None;
        cx.notify();
    }

    fn forward_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let sheet = self.forward.as_ref()?;
        Some(forward_ui::forward_overlay(
            theme,
            sheet,
            &self.view.rows,
            window,
            cx,
        ))
    }

    /// A chat was tapped in the chooser: close it and send.
    fn pick_forward(&mut self, target: forward_ui::ForwardTarget, cx: &mut Context<Self>) {
        if let Some(sheet) = self.forward.take() {
            self.run_forward(sheet.item, target, cx);
        }
        cx.notify();
    }

    /// Sends `item` to `target` and says how it went.
    fn run_forward(
        &mut self,
        item: forward_ui::ForwardItem,
        target: forward_ui::ForwardTarget,
        cx: &mut Context<Self>,
    ) {
        use rovsti_core::forward::{ForwardPlan, plan};
        let title = target.title.to_string();
        let thread = target.thread.clone();
        let wa_gateway = self.whatsapp.read(cx).gateway();
        let sms_ready = self.backend.read(cx).sms_role_held();
        let dir = self.backend.read(cx).data_dir().map_or_else(
            || std::env::temp_dir().join("rovsti-forward"),
            |dir| dir.join("cache"),
        );
        let (caption, picture) = match plan(&item.body, target.to()) {
            ForwardPlan::Nothing => {
                self.show_pill("Nothing to forward");
                return;
            }
            ForwardPlan::Text(text) => {
                match &thread {
                    ThreadId::Sms(id) => {
                        if !sms_ready {
                            self.show_pill("Set Rovsti as the SMS app to forward by SMS");
                            return;
                        }
                        let id = *id;
                        let ok = self
                            .backend
                            .update(cx, |backend, cx| backend.forward_sms(id, text, None, cx));
                        self.finish_forward(&title, thread, ok);
                    }
                    ThreadId::WhatsApp(jid) => {
                        let Some(gateway) = wa_gateway else {
                            self.show_pill("WhatsApp is not connected");
                            return;
                        };
                        let request = SendText {
                            chat: jid.clone(),
                            text,
                            quoted_id: None,
                        };
                        self.show_pill("Forwarding…");
                        let task = cx
                            .background_executor()
                            .spawn(async move { gateway.send_text(&request).is_ok() });
                        self.settle_forward(title, thread, task, cx);
                    }
                }
                return;
            }
            ForwardPlan::Picture { caption } => (caption, item.picture),
        };
        let Some(picture) = picture else {
            self.show_pill("Nothing to forward");
            return;
        };
        if matches!(thread, ThreadId::Sms(_)) && !sms_ready {
            self.show_pill("Set Rovsti as the SMS app to forward by SMS");
            return;
        }
        if matches!(thread, ThreadId::WhatsApp(_)) && wa_gateway.is_none() {
            self.show_pill("WhatsApp is not connected");
            return;
        }
        self.show_pill("Forwarding…");
        cx.notify();
        let sms_thread = match &thread {
            ThreadId::Sms(id) => Some(*id),
            ThreadId::WhatsApp(_) => None,
        };
        let chat = match &thread {
            ThreadId::WhatsApp(jid) => Some(jid.clone()),
            ThreadId::Sms(_) => None,
        };
        let mime = picture.mime.clone();
        let task = cx.background_executor().spawn(async move {
            let path = forward_file(&picture, wa_gateway.as_ref(), &dir)?;
            match (chat, wa_gateway) {
                (Some(chat), Some(gateway)) => {
                    let bytes = std::fs::read(&path).ok()?;
                    gateway
                        .send_media_with(
                            &chat,
                            rovsti_wa_proto::MediaKind::Image,
                            &mime,
                            caption.as_deref(),
                            &rovsti_wa_proto::MediaOptions::default(),
                            bytes,
                        )
                        .map_err(|error| log::warn!("forwarding a picture failed: {error}"))
                        .ok()?;
                    Some((path, caption, true))
                }
                _ => Some((path, caption, false)),
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                let ok = match (result, sms_thread) {
                    (Some((_, _, sent)), None) => sent,
                    (Some((path, caption, _)), Some(id)) => {
                        let path = path.to_string_lossy().into_owned();
                        this.backend.update(cx, |backend, cx| {
                            backend.forward_sms(id, caption.unwrap_or_default(), Some(path), cx)
                        })
                    }
                    (None, _) => false,
                };
                this.finish_forward(&title, thread, ok);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Waits for a background WhatsApp send and shows its pill.
    fn settle_forward(
        &mut self,
        title: String,
        thread: ThreadId,
        task: gpui::Task<bool>,
        cx: &mut Context<Self>,
    ) {
        cx.spawn(async move |this, cx| {
            let ok = task.await;
            this.update(cx, |this, cx| {
                this.finish_forward(&title, thread, ok);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// "Forwarded to Ada" with an Open action, or the failure.
    fn finish_forward(&mut self, title: &str, thread: ThreadId, ok: bool) {
        let text = rovsti_core::forward::notice(title, ok);
        if ok {
            self.pill_action_later(text, thread, title.to_owned());
        } else {
            self.show_pill(text);
        }
    }

    fn pill_action_later(&mut self, text: String, thread: ThreadId, title: String) {
        let token = self.pill.show(text, Some("Open".into()), true, false);
        self.pill_action = Some((token, PillAction::OpenThread { thread, title }));
    }

    /// New message with WhatsApp linked: SMS or WhatsApp for the picked recipient.
    fn channel_pick_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let recipient = self.channel_pick.clone()?;
        let colors = theme::colors(theme);
        let sms = recipient.clone();
        let wa = recipient.clone();
        let items = vec![
            menu_item(
                theme,
                "channel-sms",
                Icon::LucideMessageSquare,
                "SMS".into(),
                colors.label,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.channel_pick = None;
                this.open_recipient_sms(&sms, cx);
            }))
            .into_any_element(),
            menu_item(
                theme,
                "channel-whatsapp",
                Icon::LucideMessageCircle,
                "WhatsApp".into(),
                colors.label,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.channel_pick = None;
                this.open_recipient_whatsapp(&wa, cx);
                cx.notify();
            }))
            .into_any_element(),
        ];
        Some(menu_card(
            theme,
            "channel-pick",
            recipient.title.into(),
            items,
            cx.listener(|this, _, _, cx| {
                this.channel_pick = None;
                cx.notify();
            }),
        ))
    }

    /// The row menu over the inbox: a dimmed screen and a glass card of actions.
    fn row_menu_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let menu = self.row_menu.as_ref()?;
        let colors = theme::colors(theme);
        let now = now_ms();
        let jid = menu.jid.clone().unwrap_or_default();
        let action = |id: &'static str, icon: Icon, label: String, kind: ChatActionKind| {
            let jid = jid.clone();
            menu_item(theme, id, icon, label.into(), colors.label).on_click(
                cx.listener(move |this, _, _, cx| this.chat_action(&jid, kind.clone(), false, cx)),
            )
        };
        let mut items: Vec<AnyElement> = Vec::new();
        if menu.delete_confirm {
            let prompt = sms_actions::delete_prompt(&menu.title);
            items.push(
                div()
                    .px(px(16.0))
                    .py(px(8.0))
                    .text_size(typography::BODY.size)
                    .text_color(colors.label)
                    .child(prompt)
                    .into_any_element(),
            );
            items.push(
                menu_item(
                    theme,
                    "menu-delete-confirm",
                    Icon::LucideTrash2,
                    "Delete".into(),
                    colors.red,
                )
                .on_click(cx.listener(|this, _, _, cx| this.confirm_delete_thread(cx)))
                .into_any_element(),
            );
            items.push(
                menu_item(
                    theme,
                    "menu-delete-cancel",
                    Icon::LucideX,
                    "Cancel".into(),
                    colors.label,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    if let Some(menu) = &mut this.row_menu {
                        menu.delete_confirm = false;
                    }
                    cx.notify();
                }))
                .into_any_element(),
            );
        } else if menu.mute_choices {
            items.push(
                action(
                    "menu-mute-8h",
                    Icon::LucideBellOff,
                    "8 hours".into(),
                    ChatActionKind::Mute {
                        until_ms: Some(now + MUTE_8H_MS),
                    },
                )
                .into_any_element(),
            );
            items.push(
                action(
                    "menu-mute-week",
                    Icon::LucideBellOff,
                    "1 week".into(),
                    ChatActionKind::Mute {
                        until_ms: Some(now + MUTE_WEEK_MS),
                    },
                )
                .into_any_element(),
            );
            items.push(
                action(
                    "menu-mute-always",
                    Icon::LucideBellOff,
                    "Always".into(),
                    ChatActionKind::Mute { until_ms: None },
                )
                .into_any_element(),
            );
        } else {
            if menu.send_later {
                items.push(
                    menu_item(
                        theme,
                        "menu-find",
                        Icon::LucideSearch,
                        "Search in chat".into(),
                        colors.label,
                    )
                    .on_click(cx.listener(|this, _, window, cx| this.menu_find(window, cx)))
                    .into_any_element(),
                );
                if menu.jid.is_some() {
                    items.push(
                        menu_item(
                            theme,
                            "menu-starred",
                            Icon::LucideStar,
                            "Starred messages".into(),
                            colors.label,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.menu_starred(cx)))
                        .into_any_element(),
                    );
                }
                items.push(
                    menu_item(
                        theme,
                        "menu-send-later",
                        Icon::LucideAlarmClock,
                        "Send later".into(),
                        colors.label,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.menu_send_later(cx)))
                    .into_any_element(),
                );
                items.push(
                    menu_item(
                        theme,
                        "menu-export",
                        Icon::LucideDownload,
                        "Export chat".into(),
                        colors.label,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.menu_export(false, cx)))
                    .into_any_element(),
                );
                items.push(
                    menu_item(
                        theme,
                        "menu-share-text",
                        Icon::LucideShare,
                        "Share as text".into(),
                        colors.label,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.menu_export(true, cx)))
                    .into_any_element(),
                );
            }
            if menu.jid.is_some() {
                self.wa_menu_items(menu, theme, &mut items, cx);
            }
            if let Some(sms) = &menu.sms {
                for sms_action in row_sms_actions(sms) {
                    let danger = sms_actions::is_danger(sms_action);
                    let icon = match sms_action {
                        sms_actions::SmsAction::MarkRead => Icon::LucideMailOpen,
                        sms_actions::SmsAction::Mute => Icon::LucideBellOff,
                        sms_actions::SmsAction::Unmute => Icon::LucideBell,
                        sms_actions::SmsAction::Call => Icon::LucidePhone,
                        sms_actions::SmsAction::AddContact => Icon::LucideUserPlus,
                        sms_actions::SmsAction::Block => Icon::LucideBan,
                        sms_actions::SmsAction::Unblock => Icon::LucideCircleCheck,
                        sms_actions::SmsAction::OpenContact => Icon::LucideUser,
                        sms_actions::SmsAction::MoveToSpam => Icon::LucideShieldAlert,
                        _ => Icon::LucideTrash2,
                    };
                    items.push(
                        menu_item(
                            theme,
                            format!("menu-sms-{sms_action:?}"),
                            icon,
                            sms_actions::label(sms_action).into(),
                            if danger { colors.red } else { colors.label },
                        )
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.sms_row_action(sms_action, cx)),
                        )
                        .into_any_element(),
                    );
                }
            }
        }
        Some(menu_card(
            theme,
            "row-menu",
            menu.title.clone(),
            items,
            cx.listener(|this, _, _, cx| {
                this.row_menu = None;
                cx.notify();
            }),
        ))
    }

    /// The WhatsApp items of the row menu (pin, archive, mute, read).
    fn wa_menu_items(
        &self,
        menu: &RowMenu,
        theme: Theme,
        items: &mut Vec<AnyElement>,
        cx: &mut Context<Self>,
    ) {
        let colors = theme::colors(theme);
        let now = now_ms();
        let jid = menu.jid.clone().unwrap_or_default();
        let action = |id: &'static str, icon: Icon, label: String, kind: ChatActionKind| {
            let jid = jid.clone();
            menu_item(theme, id, icon, label.into(), colors.label).on_click(
                cx.listener(move |this, _, _, cx| this.chat_action(&jid, kind.clone(), false, cx)),
            )
        };
        {
            let meta = &menu.meta;
            items.push(
                action(
                    "menu-pin",
                    Icon::LucidePin,
                    if meta.pinned { "Unpin" } else { "Pin" }.into(),
                    ChatActionKind::Pin {
                        pinned: !meta.pinned,
                    },
                )
                .into_any_element(),
            );
            items.push(
                action(
                    "menu-archive",
                    Icon::LucideArchive,
                    if meta.archived {
                        "Unarchive"
                    } else {
                        "Archive"
                    }
                    .into(),
                    ChatActionKind::Archive {
                        archived: !meta.archived,
                    },
                )
                .into_any_element(),
            );
            if inbox::wa_muted(Some(meta), menu.local_muted, now) {
                items.push(
                    action(
                        "menu-unmute",
                        Icon::LucideBell,
                        "Unmute".into(),
                        ChatActionKind::Unmute,
                    )
                    .into_any_element(),
                );
            } else {
                items.push(
                    menu_item(
                        theme,
                        "menu-mute",
                        Icon::LucideBellOff,
                        "Mute…".into(),
                        colors.label,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        if let Some(menu) = &mut this.row_menu {
                            menu.mute_choices = true;
                        }
                        cx.notify();
                    }))
                    .into_any_element(),
                );
            }
            let unread = menu.unread > 0 || meta.marked_unread;
            items.push(
                action(
                    "menu-unread",
                    Icon::LucideMailOpen,
                    if unread {
                        "Mark as read"
                    } else {
                        "Mark as unread"
                    }
                    .into(),
                    ChatActionKind::MarkRead { read: unread },
                )
                .into_any_element(),
            );
            if menu.page_number.is_some() {
                items.push(
                    menu_item(
                        theme,
                        "menu-wa-call",
                        Icon::LucidePhone,
                        "Call".into(),
                        colors.label,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.wa_page_contact_action(sms_actions::SmsAction::Call, cx);
                    }))
                    .into_any_element(),
                );
                let (icon, label) = if menu.contact_id.is_some() {
                    (Icon::LucideUser, "Open contact")
                } else {
                    (Icon::LucideUserPlus, "Add to contacts")
                };
                items.push(
                    menu_item(theme, "menu-wa-contact", icon, label.into(), colors.label)
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.wa_page_contact_action(sms_actions::SmsAction::AddContact, cx);
                        }))
                        .into_any_element(),
                );
            }
        }
    }

    /// Whether System Back has a layer to close: the "Send later" sheet, an open chat or the new
    /// message page, a step inside the WhatsApp settings, or any tab but the first (Chats). When
    /// it has not, Back leaves the app.
    fn can_go_back(&self, cx: &App) -> bool {
        self.forward.is_some()
            || self.media_viewer.is_some()
            || self.schedule.is_open()
            || self.row_menu.is_some()
            || self.channel_pick.is_some()
            || self.search_open
            || self.show_archived
            || self.show_spam
            || self.show_scheduled
            || !matches!(self.open, Open::None)
            || (self.tabs.selected() == WHATSAPP_TAB && self.whatsapp.read(cx).can_go_back())
            || self.tabs.selected() != CHATS_TAB
    }

    /// System Back: one step out of the top-most layer.
    fn go_back(&mut self, cx: &mut Context<Self>) {
        if self.forward.is_some() {
            self.close_forward(cx);
        } else if self.back_media_viewer(cx) {
            // The picture viewer closed first.
        } else if self.schedule.is_open() {
            self.close_schedule(cx);
        } else if self.channel_pick.is_some() {
            self.channel_pick = None;
            cx.notify();
        } else if let Some(menu) = &mut self.row_menu {
            // The mute lengths step back to the menu, the menu closes.
            if menu.delete_confirm {
                menu.delete_confirm = false;
            } else if menu.mute_choices {
                menu.mute_choices = false;
            } else {
                self.row_menu = None;
            }
            cx.notify();
        } else if let Open::WhatsApp(_, conversation) = &self.open
            && conversation.update(cx, |c, cx| c.go_back(cx))
        {
            // The conversation closed one of its own layers (menu, reply, edit).
        } else if let Open::Sms(conversation) = &self.open
            && conversation.update(cx, |c, cx| c.go_back(cx))
        {
            // The SMS page cleared its selected bubble.
        } else if let Open::Status(page) = &self.open
            && page.update(cx, |page, cx| page.go_back(cx))
        {
            // The Status viewer closed; the page stays.
        } else if !matches!(self.open, Open::None) {
            self.close_page(cx);
        } else if self.search_open && self.tabs.selected() == CHATS_TAB {
            self.close_search(cx);
        } else if (self.show_archived || self.show_spam || self.show_scheduled)
            && self.tabs.selected() == CHATS_TAB
        {
            // The Spam filter, the archived chats and the scheduled overview
            // step back to the inbox.
            self.show_archived = false;
            self.show_scheduled = false;
            self.show_spam = false;
            self.chat_list.scroll_to_top();
            cx.notify();
        } else if self.tabs.selected() == WHATSAPP_TAB
            && self
                .whatsapp
                .update(cx, |whatsapp, cx| whatsapp.go_back(cx))
        {
            // The WhatsApp settings stepped back inside themselves.
        } else if self.tabs.select(CHATS_TAB) {
            self.page.restart();
            cx.notify();
        }
    }

    // ---- notifications (while attached) --------------------------------------

    /// The conversation on screen, if it is one.
    fn open_thread_id(&self, cx: &App) -> Option<ThreadId> {
        match &self.open {
            Open::WhatsApp(jid, _) => Some(ThreadId::WhatsApp(jid.clone())),
            Open::Sms(conversation) => {
                Some(conversation.read(cx).thread()).filter(|t| *t != ThreadId::Sms(-1))
            }
            Open::New(_) | Open::Status(_) | Open::None => None,
        }
    }

    /// The conversation someone is looking at: the open one while the window
    /// is in front. In the background nothing is being read, so it notifies.
    fn viewed_thread_id(&self, cx: &App) -> Option<ThreadId> {
        self.window_active
            .then(|| self.open_thread_id(cx))
            .flatten()
    }

    /// The SMS side of the window coming to the front or leaving it: Kotlin is
    /// told (see [`Backend::set_ui_active`]), and an open SMS thread that got
    /// messages meanwhile is read now.
    fn sms_window_active(&mut self, active: bool, cx: &mut Context<Self>) {
        self.backend
            .update(cx, |backend, cx| backend.set_ui_active(active, cx));
        if let (true, Some(ThreadId::Sms(id))) = (active, self.open_thread_id(cx)) {
            self.dismiss_notification(&ThreadId::Sms(id), cx);
            self.backend
                .update(cx, |backend, cx| backend.mark_read(id, cx));
        }
    }

    /// Posts a digest through Kotlin, off the UI thread.
    fn post_notification(&self, spec: NotificationSpec, cx: &mut Context<Self>) {
        let Ok(json) = serde_json::to_string(&spec) else {
            return;
        };
        cx.background_executor()
            .spawn(async move {
                match rovsti_android::notify::show(&json) {
                    Ok(_) | Err(rovsti_android::Error::Unsupported) => {}
                    Err(error) => log::warn!("showing a notification failed: {error}"),
                }
            })
            .detach();
    }

    /// Takes a conversation's notification away (it was opened or answered).
    fn dismiss_notification(&mut self, thread: &ThreadId, cx: &mut Context<Self>) {
        let tag = notifications::thread_tag(thread);
        self.digests.clear(&tag);
        cx.background_executor()
            .spawn(async move {
                match rovsti_android::notify::cancel(&tag) {
                    Ok(_) | Err(rovsti_android::Error::Unsupported) => {}
                    Err(error) => log::warn!("cancelling a notification failed: {error}"),
                }
            })
            .detach();
    }

    /// Runs one arrival through the digest and posts the result.
    fn announce(&mut self, thread: &ThreadId, arrival: Arrival, cx: &mut Context<Self>) {
        let (switches, muted) = {
            let prefs = self.backend.read(cx).prefs();
            (
                Switches {
                    enabled: prefs.notifications,
                    otp_copy: prefs.otp_copy,
                },
                prefs.is_muted(&notifications::thread_tag(thread)),
            )
        };
        if let Some(spec) = notifications::plan(&mut self.digests, arrival, switches, muted) {
            self.post_notification(spec, cx);
        }
    }

    /// A live WhatsApp message: notify unless it is mine or its chat is open.
    fn notify_whatsapp(&mut self, message: &WaMessage, cx: &mut Context<Self>) {
        let incoming = to_rovsti(message);
        // The open chat stays quiet only while the user is looking at it.
        let open = self
            .window_active
            .then(|| self.open_thread_id(cx))
            .flatten();
        if !notifications::should_notify(open.as_ref(), &incoming) {
            return;
        }
        // Muted in WhatsApp (here or on another device): stay quiet.
        if self
            .wa_info
            .get(&message.chat)
            .is_some_and(|info| info.meta.is_muted(now_ms()))
        {
            return;
        }
        let thread = incoming.thread.clone();
        let known = self.wa_threads.iter().find(|t| t.id == thread);
        let is_group = known.map_or_else(|| message.chat.ends_with("@g.us"), |t| t.is_group);
        let title = known
            .and_then(|t| t.title.clone())
            .or_else(|| self.title_of(&thread))
            .unwrap_or_else(|| display_name(None, None, &message.chat));
        // The sender in a group: the contact, WhatsApp's name, the number.
        let sender_title = message.sender.as_deref().map_or_else(String::new, |jid| {
            let contact = is_pn_jid(jid)
                .then(|| {
                    self.backend
                        .read(cx)
                        .contacts()
                        .match_number(&format!("+{}", jid_user(jid)))
                        .map(|c| c.display_name.clone())
                })
                .flatten();
            let wa_name = self.avatars.read(cx).name(jid).map(str::to_owned);
            display_name(contact.as_deref(), wa_name.as_deref(), jid)
        });
        let arrival = Arrival {
            tag: notifications::thread_tag(&thread),
            title,
            is_group,
            sender_title,
            body: incoming.body,
            at_ms: incoming.timestamp_ms,
            spam: false,
        };
        self.announce(&thread, arrival, cx);
    }

    /// An SMS that arrived while the UI is attached (Kotlin posts nothing).
    fn on_sms_received(&mut self, received: &SmsReceived, cx: &mut Context<Self>) {
        let thread = ThreadId::Sms(received.thread_id);
        let message = Message {
            thread: thread.clone(),
            direction: MessageDirection::Incoming,
            sender: PhoneNumber::parse(&received.address).ok(),
            body: received.body.clone(),
            timestamp_ms: received.date_ms,
            read: false,
        };
        let open = self.viewed_thread_id(cx);
        if !notifications::should_notify(open.as_ref(), &message) {
            // Kotlin posted its notification before telling the app; the
            // conversation is on screen, so it goes again.
            if open.as_ref() == Some(&thread) {
                self.dismiss_notification(&thread, cx);
            }
            return;
        }
        let (name, is_spam, is_group) = {
            let backend = self.backend.read(cx);
            let contact = backend.contacts().match_number(&received.address);
            let verdict = spam::classify(
                backend.spam_rules(),
                &received.address,
                &received.body,
                contact.is_some(),
                backend.blocked(),
            );
            let is_group = backend
                .threads()
                .iter()
                .find(|t| t.thread_id == received.thread_id)
                .is_some_and(|t| t.addresses.len() > 1);
            (
                contact.map(|c| c.display_name.clone()),
                verdict.is_spam(),
                is_group,
            )
        };
        let sender = name
            .clone()
            .filter(|n| !n.is_empty())
            .unwrap_or_else(|| received.address.clone());
        let arrival = Arrival {
            tag: notifications::thread_tag(&thread),
            title: self.title_of(&thread).unwrap_or_else(|| sender.clone()),
            is_group,
            sender_title: sender,
            body: received.body.clone(),
            at_ms: received.date_ms,
            spam: is_spam,
        };
        self.announce(&thread, arrival, cx);
    }

    /// Text typed into a notification: it goes out through the channel it
    /// came from, in the open conversation's timeline when that is on screen.
    fn on_reply(&mut self, reply: &Reply, cx: &mut Context<Self>) {
        let Some(thread) = notifications::parse_tag(&reply.tag) else {
            return;
        };
        let text = reply.text.trim().to_owned();
        if text.is_empty() {
            return;
        }
        match (&thread, &self.open) {
            (ThreadId::Sms(_), Open::Sms(conversation))
                if conversation.read(cx).thread() == thread =>
            {
                if !conversation.update(cx, |c, cx| c.send_reply(text, cx)) {
                    self.show_pill("The reply was not sent");
                    cx.notify();
                }
            }
            (ThreadId::WhatsApp(jid), Open::WhatsApp(open, conversation)) if open == jid => {
                conversation.update(cx, |c, cx| c.send_text(text, cx));
            }
            (ThreadId::Sms(id), _) => {
                let id = *id;
                self.backend
                    .update(cx, |backend, cx| backend.reply_sms(id, text, cx));
            }
            (ThreadId::WhatsApp(jid), _) => self.reply_whatsapp(jid.clone(), text, cx),
        }
        self.dismiss_notification(&thread, cx);
    }

    fn reply_whatsapp(&mut self, chat: String, text: String, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            self.show_pill("WhatsApp is not connected, the reply was not sent");
            cx.notify();
            return;
        };
        let request = SendText {
            chat,
            text,
            quoted_id: None,
        };
        let task = cx
            .background_executor()
            .spawn(async move { gateway.send_text(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("a WhatsApp reply failed: {error}");
                this.update(cx, |this, cx| {
                    this.show_pill("The reply was not sent");
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    /// A tapped notification opens its conversation.
    fn on_open_tag(&mut self, tag: &str, name: Option<&Handoff>, cx: &mut Context<Self>) {
        let Some(thread) = notifications::parse_tag(tag) else {
            return;
        };
        let known = self.title_of(&thread);
        let title = match name {
            Some(handoff) => notifications::handoff_title(known, handoff),
            None => known.unwrap_or_default(),
        };
        self.open_thread(thread, title, cx);
    }

    /// A sibling app (Contacts) asked for a WhatsApp chat. A JID with no chat yet opens an empty
    /// conversation titled with the contact's name.
    fn on_handoff(&mut self, handoff: &Handoff, cx: &mut Context<Self>) {
        let Some(tag) = notifications::handoff_tag(handoff) else {
            return;
        };
        if self.whatsapp.read(cx).gateway().is_none() {
            self.show_pill("Connect WhatsApp in Settings first");
            cx.notify();
            return;
        }
        self.on_open_tag(&tag, Some(handoff), cx);
    }

    fn toggle_open_mute(&mut self, cx: &mut Context<Self>) {
        let Some(thread) = self.open_thread_id(cx) else {
            return;
        };
        let tag = notifications::thread_tag(&thread);
        if let ThreadId::WhatsApp(jid) = &thread {
            let local = self.backend.read(cx).prefs().is_muted(&tag);
            let meta = self.wa_info.get(jid).map(|info| info.meta.clone());
            match inbox::wa_bell(meta.as_ref(), local, now_ms()) {
                inbox::BellToggle::Mute => {
                    self.chat_action(jid, ChatActionKind::Mute { until_ms: None }, false, cx)
                }
                inbox::BellToggle::Unmute { gateway, local } => {
                    if gateway {
                        self.chat_action(jid, ChatActionKind::Unmute, false, cx);
                    } else {
                        if local {
                            self.backend.update(cx, |backend, cx| {
                                backend.update_prefs(cx, |prefs| prefs.set_muted(&tag, false));
                            });
                        }
                        self.show_pill("Unmuted");
                    }
                }
            }
            self.sync_open_page(cx);
            cx.notify();
            return;
        }
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |prefs| {
                let muted = !prefs.is_muted(&tag);
                prefs.set_muted(&tag, muted);
            });
        });
    }

    /// Mute state and the Scheduled strip of the open conversation.
    fn sync_open_page(&mut self, cx: &mut Context<Self>) {
        let (muted, in_spam, queue) = {
            let backend = self.backend.read(cx);
            let in_spam = self
                .open_thread_id(cx)
                .is_some_and(|t| self.view.in_spam(&t));
            let muted = self.open_thread_id(cx).is_some_and(|t| {
                let local = backend.prefs().is_muted(&notifications::thread_tag(&t));
                match &t {
                    ThreadId::WhatsApp(jid) => inbox::wa_muted(
                        self.wa_info.get(jid).map(|info| &info.meta),
                        local,
                        now_ms(),
                    ),
                    ThreadId::Sms(_) => local,
                }
            });
            (muted, in_spam, backend.queue().items().to_vec())
        };
        match &self.open {
            Open::WhatsApp(jid, conversation) => {
                let items = schedule_ui::strip_items(
                    &StripTarget::WhatsApp(jid.clone()),
                    &queue,
                    &self.remote_scheduled,
                );
                conversation.update(cx, |c, cx| {
                    c.set_muted(muted, cx);
                    c.set_scheduled(items, cx);
                });
            }
            Open::Sms(conversation) => {
                let recipients = conversation.read(cx).recipients().to_vec();
                let items = schedule_ui::strip_items(
                    &StripTarget::Sms(recipients),
                    &queue,
                    &self.remote_scheduled,
                );
                conversation.update(cx, |c, cx| {
                    c.set_muted(muted, cx);
                    c.set_in_spam(in_spam, cx);
                    c.set_scheduled(items, cx);
                });
            }
            Open::New(_) | Open::Status(_) | Open::None => {}
        }
    }

    // ---- scheduled send -------------------------------------------------------

    fn show_pill(&mut self, text: impl Into<gpui::SharedString>) {
        self.pill.show(text, None, false, false);
    }

    fn ask_schedule_sms(
        &mut self,
        to: Vec<String>,
        body: String,
        sub_id: Option<i32>,
        cx: &mut Context<Self>,
    ) {
        let [to] = to.as_slice() else {
            self.show_pill("Scheduling works in one-to-one conversations");
            cx.notify();
            return;
        };
        let draft = Draft {
            dest: Dest::Sms {
                to: to.clone(),
                sub_id,
            },
            body,
        };
        let exact = self.backend.read(cx).exact_alarms();
        self.schedule
            .set_late_note(schedule_ui::late_note(true, exact));
        self.schedule.open(draft, cx);
        cx.notify();
    }

    fn ask_schedule_whatsapp(&mut self, body: String, cx: &mut Context<Self>) {
        let Open::WhatsApp(chat, _) = &self.open else {
            return;
        };
        let draft = Draft {
            dest: Dest::WhatsApp { chat: chat.clone() },
            body,
        };
        self.schedule.set_late_note(None);
        self.schedule.open(draft, cx);
        cx.notify();
    }

    pub(crate) fn close_schedule(&mut self, cx: &mut Context<Self>) {
        self.schedule.close();
        cx.notify();
    }

    pub(crate) fn schedule_custom(&mut self, cx: &mut Context<Self>) {
        // The sheet stays tappable while it slides away: one tap schedules once.
        if !self.schedule.is_open() {
            return;
        }
        let typed = self.schedule.custom_text(cx);
        let offset = self.backend.read(cx).locale().tz_offset_s;
        match schedule_ui::parse_custom(&typed, now_ms(), offset) {
            Ok(at_ms) => self.schedule_at(at_ms, cx),
            Err(reason) => {
                self.schedule.set_error(reason);
                cx.notify();
            }
        }
    }

    pub(crate) fn schedule_pick(&mut self, cx: &mut Context<Self>) {
        if !self.schedule.is_open() {
            return;
        }
        let offset = self.backend.read(cx).locale().tz_offset_s;
        let now = now_ms();
        let pick = self.schedule.pick(now, offset);
        if pick.is_schedulable(now, offset) {
            self.schedule_at(pick.at_ms(now, offset), cx);
        } else {
            self.schedule
                .set_error("Pick a time at least a minute from now");
            cx.notify();
        }
    }

    /// Schedules the sheet's text for `at_ms`: SMS into the local queue and an
    /// alarm, WhatsApp to the gateway.
    pub(crate) fn schedule_at(&mut self, at_ms: i64, cx: &mut Context<Self>) {
        // The sheet stays tappable while it slides away: one tap schedules once.
        if !self.schedule.is_open() {
            return;
        }
        let Some(draft) = self.schedule.draft().cloned() else {
            return;
        };
        let now = now_ms();
        if let Err(error) = schedule::validate(at_ms, now, &draft.body) {
            self.schedule.set_error(error.to_string());
            cx.notify();
            return;
        }
        let offset = self.backend.read(cx).locale().tz_offset_s;
        match draft.dest {
            Dest::Sms { to, sub_id } => {
                let item = ScheduledSms {
                    id: schedule_ui::next_schedule_id(self.backend.read(cx).queue().items(), now),
                    at_ms,
                    target: ScheduleTarget::Sms { to, sub_id },
                    body: draft.body,
                    created_ms: now,
                };
                let added = self
                    .backend
                    .update(cx, |backend, cx| backend.schedule_sms(item, cx));
                if let Err(error) = added {
                    self.schedule.set_error(error.to_string());
                    cx.notify();
                    return;
                }
                if let Open::Sms(conversation) = &self.open {
                    conversation.update(cx, |c, cx| c.clear_draft(cx));
                }
            }
            Dest::WhatsApp { chat } => {
                let Some(gateway) = self.whatsapp.read(cx).gateway() else {
                    self.schedule.set_error("WhatsApp is not connected.");
                    cx.notify();
                    return;
                };
                let scheduled_for = chat.clone();
                let request = ScheduleText {
                    chat,
                    text: draft.body,
                    send_at_ms: at_ms,
                };
                let task = cx
                    .background_executor()
                    .spawn(async move { gateway.schedule_text(&request) });
                cx.spawn(async move |this, cx| {
                    let result = task.await;
                    this.update(cx, |this, cx| {
                        match result {
                            Ok(item) => {
                                schedule_ui::upsert_remote(&mut this.remote_scheduled, item);
                                // Only the chat it was typed in: another may be open by now.
                                if let Open::WhatsApp(open, conversation) = &this.open
                                    && *open == scheduled_for
                                {
                                    conversation.update(cx, |c, cx| c.clear_draft(cx));
                                }
                                this.sync_open_page(cx);
                            }
                            Err(error) => {
                                log::warn!("scheduling a WhatsApp text failed: {error}");
                                this.show_pill("Couldn't schedule that message");
                            }
                        }
                        cx.notify();
                    })
                    .ok();
                })
                .detach();
            }
        }
        self.schedule.close();
        self.show_pill(format!(
            "Scheduled for {}",
            schedule_ui::when_label(at_ms, now, offset)
        ));
        self.sync_open_page(cx);
        cx.notify();
    }

    /// Cancel on a Scheduled strip row.
    fn cancel_scheduled(&mut self, key: StripKey, cx: &mut Context<Self>) {
        match key {
            StripKey::Local(id) => {
                self.backend
                    .update(cx, |backend, cx| backend.cancel_scheduled(id, cx));
                self.show_pill("Scheduled message cancelled");
            }
            StripKey::Gateway(id) => {
                let Some(gateway) = self.whatsapp.read(cx).gateway() else {
                    return;
                };
                let wanted = id.clone();
                let task = cx
                    .background_executor()
                    .spawn(async move { gateway.cancel_scheduled(&wanted) });
                cx.spawn(async move |this, cx| {
                    let result = task.await;
                    this.update(cx, |this, cx| {
                        match result {
                            // Gone already (sent meanwhile): the list is stale either way.
                            Ok(()) => {
                                this.remote_scheduled.retain(|t| t.id != id);
                                this.show_pill("Scheduled message cancelled");
                            }
                            Err(ClientError::Api(api))
                                if api.code == rovsti_wa_proto::ErrorCode::NotFound =>
                            {
                                this.remote_scheduled.retain(|t| t.id != id);
                                this.show_pill("Scheduled message cancelled");
                                this.load_remote_scheduled(cx);
                            }
                            Err(error) => {
                                log::warn!("cancelling a scheduled text failed: {error}");
                                this.show_pill("Couldn't cancel that message");
                            }
                        }
                        this.sync_open_page(cx);
                        cx.notify();
                    })
                    .ok();
                })
                .detach();
            }
        }
    }

    /// Fetches the gateway's scheduled texts (pending and recent).
    fn load_remote_scheduled(&mut self, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        let task = cx
            .background_executor()
            .spawn(async move { gateway.scheduled() });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| match result {
                Ok(list) => {
                    this.remote_scheduled = list;
                    this.sync_open_page(cx);
                    cx.notify();
                }
                Err(ClientError::Unauthorized) => {}
                Err(error) => log::warn!("loading scheduled texts failed: {error}"),
            })
            .ok();
        })
        .detach();
    }

    /// The pill's distance from the window bottom.
    fn pill_bottom(&self, cx: &App) -> f32 {
        const GAP: f32 = 12.0;
        if self.open_thread_id(cx).is_some() || !matches!(self.open, Open::None) {
            return GAP + 72.0;
        }
        f32::from(metrics::TAB_BAR_MARGIN) + f32::from(metrics::TAB_BAR_HEIGHT) * 1.1 + GAP
    }

    /// The sheet and the pill, drawn last over whatever page is up.
    fn overlays(
        &mut self,
        theme: Theme,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let reduced = window.prefers_reduced_motion();
        self.schedule.sheet.set_reduced(reduced);
        self.schedule.sheet.set_viewport(window.viewport_size());
        self.pill.set_reduced(reduced);
        let want_back = self.can_go_back(cx);
        if want_back != self.back_enabled {
            self.back_enabled = want_back;
            window.set_back_enabled(want_back);
        }
        let offset = self.backend.read(cx).locale().tz_offset_s;
        let mut out = Vec::new();
        self.sync_media_viewer(window);
        // The pill is drawn after the viewer so it stays visible over it.
        out.extend(self.media_viewer_overlay(theme, window, cx));
        out.extend(self.forward_overlay(theme, window, cx));
        out.extend(self.pill.render(theme, self.pill_bottom(cx), {
            let entity = cx.entity();
            move |pointer, position, _, cx| {
                entity.update(cx, |this, cx| {
                    if this.pill.pointer(pointer, position) == PillEvent::Action {
                        this.pill_fired(cx);
                    }
                    cx.notify();
                });
            }
        }));
        out.extend(self.schedule.render(theme, window, now_ms(), offset, cx));
        if matches!(self.open, Open::None | Open::Sms(_)) {
            out.extend(self.row_menu_overlay(theme, cx));
        }
        out.extend(self.channel_pick_overlay(theme, cx));
        out
    }

    // ---- the inbox -----------------------------------------------------------

    /// Copies a one-time code (hidden from the paste preview) and says so.
    fn copy_code(&mut self, code: SharedString, cx: &mut Context<Self>) {
        self.copy_text(code, true, cx);
    }

    /// Opens the system share sheet with `text`, off the UI thread.
    fn share_text(&mut self, text: String, cx: &mut Context<Self>) {
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::telecom::share_text(&text) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {}
                    Err(rovsti_android::Error::Unsupported) => {
                        this.show_pill("Sharing isn't available here");
                    }
                    Ok(false) | Err(_) => {
                        log::warn!("sharing failed");
                        this.show_pill("Couldn't share that");
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Copies text to the clipboard (`sensitive` hides it from the paste preview)
    /// and says "Copied". A sensitive copy also marks the inbox's code chip.
    fn copy_text(&mut self, code: SharedString, sensitive: bool, cx: &mut Context<Self>) {
        let text = code.clone();
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::notify::copy_to_clipboard(&text, sensitive) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            let copied = this
                .update(cx, |this, cx| {
                    match result {
                        Ok(true) => this.copied = sensitive.then(|| code.clone()),
                        // Desktop preview: the window's own clipboard.
                        Err(rovsti_android::Error::Unsupported) => {
                            cx.write_to_clipboard(ClipboardItem::new_string(code.to_string()));
                            this.copied = sensitive.then(|| code.clone());
                        }
                        Ok(false) | Err(_) => {
                            log::warn!("copying failed");
                            this.show_pill("Couldn't copy that");
                            cx.notify();
                            return None;
                        }
                    }
                    this.show_pill("Copied");
                    cx.notify();
                    this.copied.clone()
                })
                .ok()
                .flatten();
            if copied.is_some() {
                cx.background_executor().timer(COPIED_FOR).await;
                this.update(cx, |this, cx| {
                    if this.copied == copied {
                        this.copied = None;
                        cx.notify();
                    }
                })
                .ok();
            }
        })
        .detach();
    }

    /// The picture a row's avatar shows: the contact photo, else the
    /// WhatsApp picture (both fetched only for rows on screen), with the
    /// viewer source of the picture it found.
    fn row_photo_source(
        &self,
        row: &RowView,
        cx: &mut Context<Self>,
    ) -> Option<((Arc<RenderImage>, f32), media_viewer::ViewerSource)> {
        if let Some(uri) = &row.avatar.photo_uri {
            self.photos.update(cx, |photos, cx| photos.want(uri, cx));
            if let Some(photo) = self.photos.read(cx).photo(uri) {
                let source = row.avatar.viewer_source(true, false)?;
                return Some((photo, source));
            }
        }
        let jid = row.avatar.wa_jid.as_ref()?;
        let picture_id = row.avatar.picture_id.as_deref();
        self.avatars
            .update(cx, |avatars, cx| avatars.want(jid, picture_id, cx));
        let photo = self.avatars.read(cx).picture(jid)?;
        let source = row.avatar.viewer_source(false, true)?;
        Some((photo, source))
    }

    fn inbox_row(
        &self,
        theme: Theme,
        index: usize,
        row: &RowView,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let thread = row.newest().cloned();
        let title = row.title.clone();
        let code = row.otp.clone();
        let copied = code.is_some() && self.copied == code;
        let found = self.row_photo_source(row, cx);
        let open_avatar = found.as_ref().map(|((image, _), source)| {
            let item = media_viewer::ViewerItem {
                key: "avatar".to_owned(),
                title: row.title.clone(),
                mime: if matches!(source, media_viewer::ViewerSource::ContactPhoto { .. }) {
                    "image/png"
                } else {
                    "image/jpeg"
                }
                .to_owned(),
                source: source.clone(),
                at_ms: 0,
                placeholder: None,
                avatar: Some(Arc::clone(image)),
            };
            Box::new(cx.listener(move |this, _, _, cx| {
                this.open_media_viewer(vec![item.clone()], 0, cx);
            })) as Box<dyn Fn(&gpui::ClickEvent, &mut Window, &mut App)>
        });
        let photo = found.map(|(photo, _)| photo);
        let has_menu = row.wa_chat.is_some() || row.sms_thread.is_some();
        let menu = has_menu.then(|| {
            let title = row.title.clone();
            let jid = row.wa_chat.clone();
            let sms = row
                .sms_thread
                .map(|id| (id, row.sms_sender.as_ref().map(|s| s.to_string())));
            Box::new(cx.listener(move |this, _, _, cx| {
                this.open_row_menu(
                    jid.as_ref().map(|j| j.to_string()),
                    sms.clone(),
                    title.clone(),
                    cx,
                );
            })) as Box<dyn Fn(&gpui::LongPressEvent, &mut Window, &mut App)>
        });
        let open_title = title.clone();
        inbox::row_element(
            theme,
            index,
            row,
            RowExtras {
                photo,
                copied,
                open: Box::new(cx.listener(move |this, _, _, cx| {
                    if let Some(thread) = thread.clone() {
                        this.open_thread(thread, open_title.to_string(), cx);
                    }
                })),
                copy_code: Box::new(cx.listener(move |this, _, _, cx| {
                    if let Some(code) = code.clone() {
                        this.copy_code(code, cx);
                    }
                })),
                open_avatar,
                menu,
            },
        )
    }

    /// The read-only banner shown while Rovsti Messages is not the default SMS app.
    fn role_banner(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .mx(px(16.0))
            .mb(px(8.0))
            .p(px(12.0))
            .rounded(rovsti_ui::tokens::radius::CARD)
            .bg(colors.orange.opacity(0.16))
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.label)
                    .child(
                        "Read-only: Rovsti Messages is not your default SMS app, so it can't \
                         send, receive or mark messages read yet.",
                    ),
            )
            .child(
                glass_pill(theme, "Make default")
                    .id("role-banner")
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.backend
                            .update(cx, |backend, cx| backend.request_sms_role(cx));
                    })),
            )
            .into_any_element()
    }

    /// The trimmed search query, empty when the search is closed.
    fn search_query(&self, cx: &App) -> String {
        if self.search_open {
            self.search_field.read(cx).text().trim().to_owned()
        } else {
            String::new()
        }
    }

    fn close_search(&mut self, cx: &mut Context<Self>) {
        self.search_open = false;
        self.search_field.update(cx, |field, cx| field.clear(cx));
        self.chat_list.scroll_to_top();
        cx.notify();
    }

    /// The rows the Chats list shows now: the Spam filter's, the archived
    /// chats or the inbox's.
    fn shown_rows(&self) -> &[RowView] {
        if self.show_spam {
            &self.view.spam
        } else if self.show_archived {
            &self.view.archived
        } else {
            &self.view.rows
        }
    }

    /// Brings the Chats list's items in step with the inbox. Runs every frame
    /// but only does work when the inbox, the Spam toggle, the role or the
    /// load state changed; unchanged rows keep their measured heights.
    fn sync_chat_list(&mut self, cx: &App) {
        let (role_held, state) = {
            let backend = self.backend.read(cx);
            (backend.sms_role_held(), backend.threads_state())
        };
        let statuses = {
            let list = self.statuses.read(cx).list();
            let shown = list.authors.len() + usize::from(!list.mine.is_empty());
            (shown, list.unviewed())
        };
        let query = self.search_query(cx);
        self.scheduled_entries = schedule_ui::overview(
            self.backend.read(cx).queue().items(),
            &self.remote_scheduled,
        );
        if self.scheduled_entries.is_empty() {
            self.show_scheduled = false;
        }
        let scheduled = (
            self.scheduled_entries.len(),
            self.scheduled_entries.iter().filter(|e| e.failed).count(),
            self.scheduled_entries.first().map_or(0, |e| e.at_ms),
        );
        let wa_banner = {
            let whatsapp = self.whatsapp.read(cx);
            inbox::wa_banner(whatsapp.link().is_some(), whatsapp.instance_state())
        }
        .filter(|_| query.is_empty());
        let access_banner = {
            let backend = self.backend.read(cx);
            access::notify_banner(backend.prefs().notifications, backend.notify_access())
        }
        .filter(|_| query.is_empty());
        let layout = ChatLayout {
            view_gen: self.view_gen,
            show_spam: self.show_spam,
            show_archived: self.show_archived,
            show_scheduled: self.show_scheduled,
            scheduled,
            searching: !query.is_empty(),
            search_edits: if self.search_open {
                self.search_field.read(cx).edits
            } else {
                0
            },
            role_held,
            wa_banner,
            access_banner,
            state,
            statuses,
        };
        if self.chat_layout == Some(layout) {
            return;
        }
        self.chat_layout = Some(layout);
        let mut items = vec![ChatItem::Title];
        if self.search_open {
            items.push(ChatItem::Search);
        }
        if !role_held {
            items.push(ChatItem::RoleBanner);
        }
        if wa_banner.is_some() {
            items.push(ChatItem::WaBanner);
        }
        if access_banner.is_some() {
            items.push(ChatItem::AccessBanner);
        }
        if self.show_spam {
            items.push(ChatItem::SpamBack);
        } else if self.show_archived {
            items.push(ChatItem::ArchivedBack);
        } else if self.show_scheduled {
            items.push(ChatItem::ScheduledBack);
            items.extend((0..self.scheduled_entries.len()).map(ChatItem::Scheduled));
        } else if query.is_empty() {
            if statuses.0 > 0 {
                items.push(ChatItem::Status);
            }
            if !self.view.archived.is_empty() {
                items.push(ChatItem::ArchivedFilter);
            }
            if !self.scheduled_entries.is_empty() {
                items.push(ChatItem::ScheduledFilter);
            }
            if !self.view.spam.is_empty() {
                items.push(ChatItem::SpamFilter);
            }
        }
        let rows = self.shown_rows();
        let mut no_match_empty = false;
        let mut archived_matches = Vec::new();
        if self.show_scheduled {
            // The overview lists its own entries, not chats.
        } else if !query.is_empty() {
            let matches = inbox::filter_rows(rows, &query);
            no_match_empty = matches.is_empty();
            items.extend(matches.into_iter().map(ChatItem::Row));
            if !self.show_spam && !self.show_archived {
                archived_matches = inbox::filter_rows(&self.view.archived, &query);
            }
        } else {
            items.extend((0..rows.len()).map(ChatItem::Row));
        }
        no_match_empty = no_match_empty && archived_matches.is_empty();
        items.extend(archived_matches.into_iter().map(ChatItem::ArchivedMatch));
        if no_match_empty {
            items.push(ChatItem::NoResults);
        }
        if !self.show_spam
            && !self.show_archived
            && !self.show_scheduled
            && self.view.rows.is_empty()
            && self.view.spam.is_empty()
            && self.view.archived.is_empty()
        {
            items.push(ChatItem::Empty);
        }
        items.push(ChatItem::Tail);
        let keys = items
            .iter()
            .map(|item| match *item {
                ChatItem::Row(index) => {
                    key_of((1u8, self.show_spam, self.show_archived, &rows[index]))
                }
                ChatItem::ArchivedMatch(index) => key_of((17u8, &self.view.archived[index])),
                ChatItem::SpamFilter => {
                    key_of((2u8, self.view.spam.len(), self.view.spam_unread()))
                }
                ChatItem::ArchivedFilter => {
                    key_of((7u8, self.view.archived.len(), self.view.archived_unread()))
                }
                ChatItem::ArchivedBack => key_of(8u8),
                ChatItem::ScheduledFilter => key_of((
                    14u8,
                    scheduled.0,
                    scheduled.1,
                    schedule_ui::overview_subtitle(&self.scheduled_entries),
                )),
                ChatItem::ScheduledBack => key_of(15u8),
                ChatItem::Scheduled(index) => {
                    let entry = &self.scheduled_entries[index];
                    key_of((16u8, &entry.key, entry.at_ms, entry.failed))
                }
                ChatItem::Status => key_of((9u8, statuses)),
                ChatItem::Empty => key_of((3u8, state)),
                ChatItem::Title => key_of(0u8),
                ChatItem::Search => key_of(10u8),
                ChatItem::NoResults => key_of((11u8, &query)),
                ChatItem::RoleBanner => key_of(4u8),
                ChatItem::WaBanner => key_of((12u8, wa_banner)),
                ChatItem::AccessBanner => key_of((13u8, access_banner)),
                ChatItem::SpamBack => key_of(5u8),
                ChatItem::Tail => key_of(6u8),
            })
            .collect();
        self.chat_list.sync(keys);
        self.chat_items = items;
    }

    /// The Chats tab as a virtualised list: only the items on screen are built.
    fn chats_list(&self, cx: &mut Context<Self>) -> AnyElement {
        list(
            self.chat_list.state().clone(),
            cx.processor(|this, index: usize, window, cx| {
                div()
                    .w_full()
                    .child(this.chat_item(index, window, cx))
                    .into_any_element()
            }),
        )
        .size_full()
        .into_any_element()
    }

    /// One item of the Chats list.
    fn chat_item(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let Some(&item) = self.chat_items.get(index) else {
            return div().into_any_element();
        };
        match item {
            ChatItem::Title => {
                let compose = glass_icon_button(theme, Icon::LucidePencil, 0.0, false)
                    .id("new-message")
                    .on_click(cx.listener(|this, _, _, cx| this.open_new(cx)))
                    .into_any_element();
                let search = glass_icon_button(theme, Icon::LucideSearch, 0.0, false)
                    .id("search-chats")
                    .on_click(cx.listener(|this, _, window, cx| {
                        this.search_open = true;
                        let focus = this.search_field.read(cx).focus_handle();
                        window.focus(&focus, cx);
                        cx.notify();
                    }))
                    .into_any_element();
                let mark_all = (!inbox::mark_all_targets(&self.view.rows).is_empty()).then(|| {
                    glass_icon_button(theme, Icon::LucideCheckCheck, 0.0, false)
                        .id("mark-all-read")
                        .on_click(cx.listener(|this, _, _, cx| this.ask_mark_all(cx)))
                });
                let actions = div()
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .children(mark_all)
                    .child(search)
                    .child(compose)
                    .into_any_element();
                large_title(theme, TABS[CHATS_TAB], Some(actions)).into_any_element()
            }
            ChatItem::Search => div()
                .w_full()
                .px(px(12.0))
                .pb(px(8.0))
                .flex()
                .items_center()
                .gap(px(8.0))
                .child(div().flex_1().min_w_0().child(field_box(
                    theme,
                    "chat-search",
                    &self.search_field,
                    "Search chats",
                    window,
                    cx,
                )))
                .child(
                    glass_pill(theme, "Cancel")
                        .id("search-cancel")
                        .on_click(cx.listener(|this, _, _, cx| this.close_search(cx))),
                )
                .into_any_element(),
            ChatItem::NoResults => {
                let query = self.search_query(cx);
                empty_state(
                    theme,
                    "No Results",
                    format!("No conversation matches \"{query}\"."),
                )
                .into_any_element()
            }
            ChatItem::RoleBanner => self.role_banner(theme, cx),
            ChatItem::WaBanner => {
                let text = self
                    .chat_layout
                    .and_then(|layout| layout.wa_banner)
                    .unwrap_or_default();
                let icon = div()
                    .size(px(44.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        gpui::svg()
                            .path(Icon::LucideWifiOff.path())
                            .size(px(22.0))
                            .text_color(theme::colors(theme).orange),
                    )
                    .into_any_element();
                list_row(theme, Some(icon), text, None, None)
                    .id("wa-banner")
                    .on_click(cx.listener(|this, _, _, cx| {
                        if this.tabs.select(1) {
                            this.page.restart();
                        }
                        cx.notify();
                    }))
                    .into_any_element()
            }
            ChatItem::AccessBanner => {
                let text = self
                    .chat_layout
                    .and_then(|layout| layout.access_banner)
                    .unwrap_or_default();
                let icon = div()
                    .size(px(44.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        gpui::svg()
                            .path(Icon::LucideBellOff.path())
                            .size(px(22.0))
                            .text_color(theme::colors(theme).orange),
                    )
                    .into_any_element();
                list_row(theme, Some(icon), text, None, None)
                    .id("access-banner")
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.backend.update(cx, |backend, cx| {
                            backend
                                .ask_access(rovsti_android::permissions::Group::Notifications, cx);
                        });
                    }))
                    .into_any_element()
            }
            ChatItem::SpamBack => list_row(
                theme,
                None,
                "Inbox",
                Some("Spam is never deleted or announced.".into()),
                None,
            )
            .id("spam-back")
            .on_click(cx.listener(|this, _, _, cx| {
                this.show_spam = false;
                this.chat_list.scroll_to_top();
                cx.notify();
            }))
            .into_any_element(),
            ChatItem::ArchivedBack => list_row(theme, None, "Inbox", Some("Archived".into()), None)
                .id("archived-back")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.show_archived = false;
                    this.chat_list.scroll_to_top();
                    cx.notify();
                }))
                .into_any_element(),
            ChatItem::ScheduledBack => {
                list_row(theme, None, "Inbox", Some("Scheduled".into()), None)
                    .id("scheduled-back")
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.show_scheduled = false;
                        this.chat_list.scroll_to_top();
                        cx.notify();
                    }))
                    .into_any_element()
            }
            ChatItem::ScheduledFilter => {
                let icon = div()
                    .size(px(44.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        gpui::svg()
                            .path(Icon::LucideCalendarClock.path())
                            .size(px(22.0))
                            .text_color(colors.secondary_label),
                    )
                    .into_any_element();
                list_row(
                    theme,
                    Some(icon),
                    "Scheduled",
                    Some(schedule_ui::overview_subtitle(&self.scheduled_entries).into()),
                    None,
                )
                .id("scheduled-filter")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.show_scheduled = true;
                    this.chat_list.scroll_to_top();
                    cx.notify();
                }))
                .into_any_element()
            }
            ChatItem::Scheduled(index) => {
                let Some(entry) = self.scheduled_entries.get(index).cloned() else {
                    return div().into_any_element();
                };
                let locale = self.backend.read(cx).locale();
                let (title, sms_number, chat) = match &entry.target {
                    schedule_ui::EntryTarget::Sms(to) => {
                        let title = self
                            .backend
                            .read(cx)
                            .contacts()
                            .match_number(to)
                            .map_or_else(|| to.clone(), |c| c.display_name.clone());
                        (title, Some(to.clone()), None)
                    }
                    schedule_ui::EntryTarget::WhatsApp(chat) => {
                        let title = self
                            .title_of(&ThreadId::WhatsApp(chat.clone()))
                            .unwrap_or_else(|| jid_user(chat).to_owned());
                        (title, None, Some(chat.clone()))
                    }
                };
                let preview = schedule_ui::preview(&entry.body);
                let subtitle = if entry.failed {
                    format!("Couldn't be sent · {preview}")
                } else {
                    let when = schedule_ui::when_label(entry.at_ms, now_ms(), locale.tz_offset_s);
                    format!("{when} · {preview}")
                };
                let icon = div()
                    .size(px(44.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        gpui::svg()
                            .path(Icon::LucideCalendarClock.path())
                            .size(px(22.0))
                            .text_color(if entry.failed {
                                colors.orange
                            } else {
                                colors.secondary_label
                            }),
                    )
                    .into_any_element();
                list_row(
                    theme,
                    Some(icon),
                    title.clone(),
                    Some(subtitle.into()),
                    None,
                )
                .id(("scheduled-entry", index))
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.show_scheduled = false;
                    if let Some(number) = &sms_number {
                        let recipient = Recipient {
                            title: title.clone(),
                            number: number.clone(),
                            detail: None,
                        };
                        this.open_recipient_sms(&recipient, cx);
                    } else if let Some(chat) = &chat {
                        this.open_chat(chat.clone(), title.clone(), cx);
                    }
                }))
                .into_any_element()
            }
            ChatItem::ArchivedFilter => {
                let unread = self.view.archived_unread();
                let count = self.view.archived.len();
                let icon = div()
                    .size(px(44.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        gpui::svg()
                            .path(Icon::LucideArchive.path())
                            .size(px(22.0))
                            .text_color(colors.secondary_label),
                    )
                    .into_any_element();
                list_row(
                    theme,
                    Some(icon),
                    "Archived",
                    Some(format!("{count} chat{}", if count == 1 { "" } else { "s" }).into()),
                    (unread > 0).then(|| unread.to_string().into()),
                )
                .id("archived-filter")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.show_archived = true;
                    this.chat_list.scroll_to_top();
                    cx.notify();
                }))
                .into_any_element()
            }
            ChatItem::Status => {
                let (leading, subtitle, unseen) = {
                    let feed = self.statuses.read(cx);
                    let list = feed.list().clone();
                    let leading =
                        status_ui::entry_avatars(theme, &list, &self.backend, &self.avatars, cx);
                    (leading, status_ui::entry_subtitle(&list), list.unviewed())
                };
                list_row(
                    theme,
                    Some(leading),
                    "Status",
                    Some(subtitle.into()),
                    (unseen > 0).then(|| unseen.to_string().into()),
                )
                .id("status-entry")
                .on_click(cx.listener(|this, _, _, cx| this.open_status(cx)))
                .into_any_element()
            }
            ChatItem::SpamFilter => {
                let unread = self.view.spam_unread();
                let count = self.view.spam.len();
                list_row(
                    theme,
                    Some(avatar(theme, "!", 44.0, colors.orange).into_any_element()),
                    "Spam",
                    Some(
                        format!("{count} conversation{}", if count == 1 { "" } else { "s" }).into(),
                    ),
                    (unread > 0).then(|| unread.to_string().into()),
                )
                .id("spam-filter")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.show_spam = true;
                    this.chat_list.scroll_to_top();
                    cx.notify();
                }))
                .into_any_element()
            }
            ChatItem::Row(row) => {
                let Some(view) = self.shown_rows().get(row) else {
                    return div().into_any_element();
                };
                // Spam and archived rows keep ids apart from the inbox's.
                let id = if self.show_spam {
                    1000 + row
                } else if self.show_archived {
                    2000 + row
                } else {
                    row
                };
                self.inbox_row(theme, id, view, cx)
            }
            ChatItem::ArchivedMatch(row) => {
                let Some(view) = self.view.archived.get(row) else {
                    return div().into_any_element();
                };
                self.inbox_row(theme, 2000 + row, view, cx)
            }
            ChatItem::Empty => match self.backend.read(cx).threads_state() {
                Access::Denied => {
                    // The default SMS app gets the access with the role; the
                    // default without it turns it on in the app's settings.
                    let held = self.backend.read(cx).sms_role_held();
                    div()
                        .flex()
                        .flex_col()
                        .items_center()
                        .gap(px(12.0))
                        .child(empty_state(
                            theme,
                            "SMS Access Is Off",
                            "Allow SMS access for Rovsti Messages in the system settings to see your messages.",
                        ))
                        .child(
                            glass_pill(theme, if held { "Open settings" } else { "Make default" })
                                .id("sms-access")
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.backend.update(cx, |backend, cx| {
                                        if held {
                                            backend.open_app_settings(cx);
                                        } else {
                                            backend.request_sms_role(cx);
                                        }
                                    });
                                })),
                        )
                        .into_any_element()
                }
                Access::Loading => {
                    empty_state(theme, "Loading", "Reading your messages…").into_any_element()
                }
                _ => empty_state(
                    theme,
                    "No Conversations",
                    "Start one with the pencil above. Link WhatsApp from the WhatsApp tab.",
                )
                .into_any_element(),
            },
            ChatItem::Tail => div().h(content_bottom_inset()).into_any_element(),
        }
    }
}

impl Render for MessagesApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let mut motion = std::mem::take(&mut self.motion);
        let fading = self.avatars.read(cx).fading() || self.photos.read(cx).fading();
        motion.frame_timed(window, |dt, real| {
            self.tabs.tick(dt)
                | self.page.tick(dt)
                | self.schedule.sheet.tick(dt)
                | self.schedule.sheet.tick_real(real)
                | self.pill.tick(dt)
                | self.media_viewer.as_mut().is_some_and(|v| v.tick(dt))
                | fading
        });
        self.motion = motion;
        let overlays = self.overlays(theme, window, cx);
        let (page, accent) = match &self.open {
            Open::None => (None, theme.colors().accent),
            Open::WhatsApp(_, conversation) => (
                Some(conversation.clone().into_any_element()),
                theme.colors().green,
            ),
            Open::Sms(conversation) => (
                Some(conversation.clone().into_any_element()),
                theme.colors().accent,
            ),
            Open::New(entry) => (
                Some(entry.clone().into_any_element()),
                theme.colors().accent,
            ),
            Open::Status(page) => (Some(page.clone().into_any_element()), theme.colors().green),
        };
        if let Some(page) = page {
            return div()
                .size_full()
                .relative()
                .child(rovsti_ui::components::backdrop(theme, accent))
                .child(div().size_full().opacity(self.page.progress()).child(page))
                .children(overlays)
                .into_any_element();
        }
        let tab = self.tabs.selected();
        let entity = cx.entity();
        let touch_entity = entity.clone();

        let page = if tab == CHATS_TAB {
            // The inbox grows with the user's data: a virtualised list that
            // carries the large title as its first item.
            self.sync_chat_list(cx);
            div()
                .size_full()
                .opacity(self.page.progress())
                .child(
                    div()
                        .size_full()
                        .relative()
                        .top(px(self.page.offset(24.0)))
                        .child(self.chats_list(cx)),
                )
                .into_any_element()
        } else {
            let body = if tab == WHATSAPP_TAB {
                self.whatsapp.clone().into_any_element()
            } else {
                self.settings.clone().into_any_element()
            };
            // The keyboard covers the page's bottom: room to scroll a form
            // field (passphrase, spam rule) above it.
            let insets = window.insets();
            let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
            div()
                .id("page")
                .size_full()
                .overflow_y_scroll()
                .pb(content_bottom_inset() + keyboard)
                .opacity(self.page.progress())
                .child(
                    div()
                        .relative()
                        .top(px(self.page.offset(24.0)))
                        .child(large_title(theme, TABS[tab], None))
                        .child(body),
                )
                .into_any_element()
        };

        div()
            .size_full()
            .relative()
            .on_mouse_up(
                gpui::MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.tabs.release();
                    cx.notify();
                }),
            )
            .child(rovsti_ui::components::backdrop(
                theme,
                theme.colors().accent,
            ))
            .child(page)
            .child(self.tabs.render(
                theme,
                move |index, _, cx| {
                    entity.update(cx, |this, cx| {
                        this.tabs.press();
                        if this.tabs.select(index) {
                            this.page.restart();
                        }
                        cx.notify();
                    });
                },
                move |phase, _, cx| {
                    touch_entity.update(cx, |this, cx| {
                        this.tabs.touch(phase);
                        cx.notify();
                    });
                },
            ))
            .children(overlays)
            .into_any_element()
    }
}

/// A local file holding the picture to forward: a phone file as is, a
/// WhatsApp picture from the viewer's cache or downloaded into `cache/forward`.
/// Blocking: run it on the background executor.
fn forward_file(
    picture: &media_viewer::ViewerItem,
    gateway: Option<&rovsti_wa_client::Gateway>,
    cache: &Path,
) -> Option<std::path::PathBuf> {
    match &picture.source {
        media_viewer::ViewerSource::File { path } => path.exists().then(|| path.clone()),
        media_viewer::ViewerSource::WaMedia { message_id } => {
            let name = forward_ui::cache_name(message_id, &picture.mime);
            let viewer = cache.join("viewer").join(&name);
            if viewer.exists() {
                return Some(viewer);
            }
            let path = cache.join("forward").join(&name);
            if path.exists() {
                return Some(path);
            }
            let bytes = gateway?
                .media(message_id)
                .map_err(|error| log::warn!("downloading a picture to forward failed: {error}"))
                .ok()?;
            std::fs::create_dir_all(path.parent()?).ok()?;
            std::fs::write(&path, bytes).ok()?;
            Some(path)
        }
        media_viewer::ViewerSource::WaPicture { .. }
        | media_viewer::ViewerSource::ContactPhoto { .. } => None,
    }
}

/// One action of a menu card: an icon and a label, the whole width.
pub(crate) fn menu_item(
    theme: Theme,
    id: impl Into<gpui::ElementId>,
    icon: Icon,
    label: SharedString,
    color: Hsla,
) -> gpui::Stateful<gpui::Div> {
    let colors = theme::colors(theme);
    div()
        .id(id)
        .flex()
        .items_center()
        .gap(px(12.0))
        .px(px(16.0))
        .min_h(metrics::MIN_HIT)
        .rounded(px(14.0))
        .hover(|s| s.bg(colors.label.opacity(0.06)))
        .child(
            gpui::svg()
                .flex_none()
                .path(icon.path())
                .size(px(20.0))
                .text_color(color),
        )
        .child(
            div()
                .text_size(typography::BODY.size)
                .text_color(color)
                .child(label),
        )
}

/// A dimmed screen with a glass card of actions near the bottom. Tapping
/// outside the card calls `dismiss`; the card itself swallows touches so the
/// page under it never reacts.
pub(crate) fn menu_card(
    theme: Theme,
    id: &'static str,
    title: SharedString,
    items: Vec<AnyElement>,
    dismiss: impl Fn(&gpui::ClickEvent, &mut Window, &mut App) + 'static,
) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .id(id)
        .absolute()
        .top_0()
        .left_0()
        .size_full()
        .flex()
        .flex_col()
        .justify_end()
        .px(px(12.0))
        .pb(px(28.0))
        .bg(Hsla {
            a: 0.4,
            ..gpui::black()
        })
        .occlude()
        .on_click(dismiss)
        .child(
            glass(GlassSpec::control(theme))
                .occlude()
                .w_full()
                .rounded(px(26.0))
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .flex_col()
                .gap(px(2.0))
                .p(px(8.0))
                .child(
                    div()
                        .px(px(16.0))
                        .pt(px(8.0))
                        .pb(px(4.0))
                        .truncate()
                        .text_size(typography::FOOTNOTE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.secondary_label)
                        .child(title),
                )
                .children(items),
        )
        .into_any_element()
}

// Cold receivers (SMS, inline reply, FCM digest) ask Rust for decisions here (AD-MSG4).
rovsti_android::headless_entry!(headless::handle);

#[gpui_platform::main]
fn main() {
    gpui_platform::application()
        .with_assets(IconAssets::new())
        .run(|cx: &mut App| {
            let backend = Backend::start(cx);
            cx.open_window(
                WindowOptions {
                    window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                        None,
                        size(px(402.0), px(874.0)),
                        cx,
                    ))),
                    ..Default::default()
                },
                |window, cx| {
                    let app = cx.new(|cx| MessagesApp::new(backend, cx));
                    app.update(cx, |app, cx| app.watch_window(window, cx));
                    // System Back steps out of the top-most layer, then to the first tab.
                    let weak = app.downgrade();
                    window.on_system_back(cx, move |_, cx| {
                        weak.update(cx, |this, cx| this.go_back(cx)).ok();
                    });
                    app
                },
            )
            .expect("failed to open the Rovsti Messages window");
            cx.activate(true);
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    use sms_actions::SmsAction::*;

    fn menu(unread: bool, muted: bool, in_spam: bool) -> SmsRowMenu {
        SmsRowMenu {
            thread_id: 1,
            sender: Some("+905321234567".into()),
            unread,
            muted,
            in_spam,
            on_page: false,
            contact_id: None,
            blocked: false,
            sender_is_number: true,
            can_be_spam: false,
        }
    }

    #[test]
    fn number_sender_excludes_names_and_missing() {
        assert!(is_number_sender(Some("+905321234567")));
        assert!(!is_number_sender(Some("TRENDYOL")));
        assert!(!is_number_sender(None));
    }

    #[test]
    fn alphanumeric_spam_candidate_gets_only_move_to_spam() {
        let mut m = menu(false, false, false);
        m.sender = Some("TRENDYOL".into());
        m.sender_is_number = false;
        m.can_be_spam = true;
        let a = row_sms_actions(&m);
        assert!(a.contains(&MoveToSpam));
        assert!(!a.contains(&Block) && !a.contains(&Call) && !a.contains(&AddContact));
    }

    #[test]
    fn wa_page_number_only_for_plain_one_to_one_jids() {
        assert_eq!(
            wa_page_number("905321234567@s.whatsapp.net").as_deref(),
            Some("+905321234567")
        );
        assert_eq!(
            wa_page_number("905321234567:12@s.whatsapp.net").as_deref(),
            Some("+905321234567")
        );
        assert_eq!(wa_page_number("123-456@g.us"), None);
        assert_eq!(wa_page_number("998877@lid"), None);
        assert_eq!(wa_page_number("@s.whatsapp.net"), None);
    }

    #[test]
    fn row_menu_offers_not_spam_and_block() {
        assert_eq!(
            row_sms_actions(&menu(true, false, false)),
            vec![MarkRead, Mute, Block, Delete]
        );
        assert_eq!(
            row_sms_actions(&menu(false, true, true)),
            vec![Unmute, NotSpam, Block, Delete]
        );
    }

    #[test]
    fn spam_page_menu_offers_not_spam() {
        let page = SmsRowMenu {
            on_page: true,
            ..menu(false, false, true)
        };
        assert_eq!(
            row_sms_actions(&page),
            vec![NotSpam, Call, AddContact, Block, Delete]
        );
    }

    #[test]
    fn blocked_sender_offers_unblock_on_row_and_page() {
        let blocked = |on_page| SmsRowMenu {
            on_page,
            blocked: true,
            ..menu(false, false, false)
        };
        assert_eq!(
            row_sms_actions(&blocked(false)),
            vec![Mute, Unblock, Delete]
        );
        assert_eq!(
            row_sms_actions(&blocked(true)),
            vec![Call, AddContact, Unblock, Delete]
        );
    }

    #[test]
    fn page_menu_hides_add_contact_for_a_known_number() {
        let page = |known_contact: bool| SmsRowMenu {
            on_page: true,
            contact_id: known_contact.then_some(7),
            ..menu(false, false, false)
        };
        assert_eq!(
            row_sms_actions(&page(false)),
            vec![Call, AddContact, Block, Delete]
        );
        assert_eq!(
            row_sms_actions(&page(true)),
            vec![Call, OpenContact, Block, Delete]
        );
        let draft = SmsRowMenu {
            thread_id: -1,
            ..page(false)
        };
        assert_eq!(row_sms_actions(&draft), vec![Call, AddContact, Block]);
    }
}
