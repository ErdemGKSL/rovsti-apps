//! Rovsti Messages: SMS/MMS and WhatsApp in one inbox.
//!
//! WhatsApp runs on the Rovsti gateway (`services/wa_gateway`): the WhatsApp
//! tab hosts the shared settings screen (`rovsti_wa_ui`) that links this app to
//! an instance, and chats and live messages come from the gateway through
//! `rovsti_wa_client`. SMS stays on the phone.

mod backend;
mod backup_ui;
mod conversation;
// Only the Android JNI entry calls `handle`; desktop builds exercise it from the tests.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
mod headless;
mod inbox;
mod new_conversation;
mod notifications;
mod prefs;
mod schedule_ui;
mod settings_ui;
mod sms_conversation;

use std::time::Duration;

use gpui::{
    AnyElement, App, AppContext as _, Bounds, ClipboardItem, Context, Entity, InteractiveElement,
    IntoElement, ParentElement, Render, StatefulInteractiveElement, Styled, Subscription, Window,
    WindowBounds, WindowOptions, div, px, size,
};
use rovsti_core::{
    Message, MessageDirection, PhoneNumber, ThreadId,
    inbox::InboxThread,
    notify::NotificationSpec,
    schedule::{self, ScheduleTarget, ScheduledSms},
    spam,
};
use rovsti_ui::{
    Icon, IconAssets,
    components::{avatar, empty_state, glass_icon_button, glass_pill, large_title, list_row},
    current_theme,
    notice_pill::NoticePill,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    theme,
    tokens::{Theme, metrics, typography},
};

use backend::{Access, Backend, BackendEvent, now_ms};
use conversation::{Conversation, ConversationEvent};
use inbox::{InboxView, RowView};
use new_conversation::{NewConversation, NewConversationEvent, Recipient};
use notifications::{Arrival, Digests, Reply, SmsReceived, Switches};
use rovsti_wa_client::ClientError;
use rovsti_wa_proto::{
    GatewayEvent, ScheduleText, ScheduledText, SendText, WaMessage, jid_user, to_rovsti,
};
use rovsti_wa_ui::{AppId, WhatsAppSettings};
use schedule_ui::{Dest, Draft, ScheduleSheet, StripKey, StripTarget};
use settings_ui::{SettingsEvent, SettingsView};
use sms_conversation::{SmsConversation, SmsConversationEvent};

const TABS: [&str; 3] = ["Chats", "WhatsApp", "Settings"];
const TAB_ICONS: [Icon; 3] = [
    Icon::LucideMessageSquare,
    Icon::LucideMessageCircle,
    Icon::LucideSettings,
];

/// How long a tapped OTP chip says "Copied".
const COPIED_FOR: Duration = Duration::from_secs(3);

/// The page pushed over the tabs.
enum Open {
    None,
    /// A WhatsApp chat (its JID, for the channel switch).
    WhatsApp(String, Entity<Conversation>),
    Sms(Entity<SmsConversation>),
    New(Entity<NewConversation>),
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
    /// What each notified conversation says, for the digest.
    digests: Digests,
    /// The gateway's scheduled texts (pending and recently finished).
    remote_scheduled: Vec<ScheduledText>,
    /// GPUI asked the host for the Back key (a sheet is open).
    back_enabled: bool,
    /// The gateway's chats as inbox threads (SMS comes from the backend).
    wa_threads: Vec<InboxThread>,
    /// The merged inbox, rebuilt whenever a source changes.
    view: InboxView,
    /// The Spam filter's rows are showing instead of the inbox.
    show_spam: bool,
    /// The OTP code that was just copied.
    copied: Option<String>,
    open: Open,
    /// The chat list is being fetched, or was fetched for this link.
    chats: ChatsState,
    _subscriptions: Vec<Subscription>,
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
        // The backend owns the one event sink: it hands WhatsApp pushes over
        // here, and SMS, contact and role changes rebuild the inbox.
        let subscriptions = vec![
            cx.observe(&whatsapp, |this, whatsapp, cx| {
                let events = whatsapp.update(cx, |settings, _| settings.take_events());
                let connected = whatsapp.read(cx).is_connected();
                whatsapp.update(cx, |settings, cx| settings.register_push(cx));
                this.on_gateway_events(events, connected, cx);
                cx.notify();
            }),
            cx.observe(&backend, |this, _, cx| {
                this.rebuild(cx);
                cx.notify();
            }),
            cx.subscribe(&backend, |this, _, event, cx| match event {
                BackendEvent::WaPush => {
                    this.whatsapp.update(cx, |settings, cx| settings.wake(cx));
                }
                BackendEvent::SmsReceived(received) => this.on_sms_received(received, cx),
                BackendEvent::Reply(reply) => this.on_reply(reply, cx),
                BackendEvent::OpenThread(tag) => this.on_open_tag(tag, cx),
                BackendEvent::Notice(text) => this.show_pill(text.clone()),
                BackendEvent::SmsChanged | BackendEvent::Status(_) | BackendEvent::Document(_) => {}
            }),
            cx.subscribe(&settings, |this, _, event, cx| match event {
                SettingsEvent::OpenWhatsApp => {
                    if this.tabs.select(1) {
                        this.page.restart();
                    }
                    cx.notify();
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
            digests: Digests::default(),
            remote_scheduled: Vec::new(),
            back_enabled: false,
            wa_threads: Vec::new(),
            view: InboxView::default(),
            show_spam: false,
            copied: None,
            open: Open::None,
            chats: ChatsState::Needed,
            _subscriptions: subscriptions,
        };
        this.rebuild(cx);
        this
    }

    /// The WhatsApp threads the inbox merges. On desktop with nothing linked
    /// the preview chat stands in, so the merged person shows.
    fn wa_for_view(&self, cx: &App) -> Vec<InboxThread> {
        if self.wa_threads.is_empty()
            && self.backend.read(cx).is_preview()
            && self.whatsapp.read(cx).link().is_none()
        {
            return backend::preview::whatsapp_threads(now_ms());
        }
        self.wa_threads.clone()
    }

    /// Merges SMS and WhatsApp into the rows again and keeps an open
    /// conversation's channel switch and clock in step.
    fn rebuild(&mut self, cx: &mut Context<Self>) {
        let wa = self.wa_for_view(cx);
        let locale = {
            let backend = self.backend.read(cx);
            self.view = inbox::build(
                backend.threads(),
                &wa,
                &inbox::Inputs {
                    contacts: backend.contacts(),
                    rules: backend.spam_rules(),
                    blocked: backend.blocked(),
                    locale: backend.locale(),
                    now_ms: now_ms(),
                },
            );
            backend.locale()
        };
        if self.view.spam.is_empty() {
            self.show_spam = false;
        }
        if let Open::WhatsApp(_, conversation) = &self.open {
            conversation.update(cx, |c, cx| c.set_locale(locale, cx));
        }
        self.update_switch(cx);
        self.sync_open_page(cx);
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
            Open::New(_) | Open::None => {}
        }
    }

    fn on_gateway_events(
        &mut self,
        events: Vec<GatewayEvent>,
        connected: bool,
        cx: &mut Context<Self>,
    ) {
        for event in events {
            if let Open::WhatsApp(_, conversation) = &self.open {
                conversation.update(cx, |conversation, cx| conversation.on_event(&event, cx));
            }
            match event {
                GatewayEvent::Message(message) => {
                    let viewing =
                        matches!(&self.open, Open::WhatsApp(jid, _) if *jid == message.chat);
                    inbox::fold_message(&mut self.wa_threads, &message, viewing);
                    self.notify_whatsapp(&message, cx);
                }
                GatewayEvent::Scheduled(item) => {
                    schedule_ui::upsert_remote(&mut self.remote_scheduled, item);
                    self.sync_open_page(cx);
                }
                // The stream missed events: fetch the chat list again.
                GatewayEvent::Resync => self.chats = ChatsState::Needed,
                _ => {}
            }
        }
        if !connected && self.whatsapp.read(cx).link().is_none() {
            // Unlinked: WhatsApp chats leave the inbox.
            self.wa_threads.clear();
            if matches!(self.open, Open::WhatsApp(..)) {
                self.open = Open::None;
            }
            self.chats = ChatsState::Needed;
        } else if connected && self.chats == ChatsState::Needed {
            self.load_chats(cx);
        }
        self.rebuild(cx);
    }

    /// Fills the inbox with each chat's last message, off the UI thread.
    fn load_chats(&mut self, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        self.chats = ChatsState::Loading;
        let task = cx
            .background_executor()
            .spawn(async move { gateway.chats() });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.chats = ChatsState::Loaded;
                match result {
                    Ok(chats) => {
                        this.wa_threads = inbox::chat_threads(&chats, &this.wa_threads);
                        this.rebuild(cx);
                    }
                    Err(ClientError::Unauthorized) => {}
                    Err(error) => log::warn!("loading WhatsApp chats failed: {error}"),
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
        let is_group = self
            .wa_threads
            .iter()
            .find(|t| t.id == ThreadId::WhatsApp(jid.clone()))
            .map_or_else(|| jid.ends_with("@g.us"), |t| t.is_group);
        let locale = self.backend.read(cx).locale();
        let chat = jid.clone();
        let backend = self.backend.clone();
        let conversation =
            cx.new(|cx| Conversation::new(chat, title, is_group, gateway, locale, backend, cx));
        cx.subscribe(&conversation, |this, _, event, cx| match event {
            ConversationEvent::Back => this.close_page(cx),
            ConversationEvent::Switch(thread) => this.switch_to(thread.clone(), cx),
            ConversationEvent::Schedule(text) => this.ask_schedule_whatsapp(text.clone(), cx),
            ConversationEvent::CancelScheduled(key) => this.cancel_scheduled(key.clone(), cx),
            ConversationEvent::ToggleMute => this.toggle_open_mute(cx),
        })
        .detach();
        // Opening reads the chat.
        let id = ThreadId::WhatsApp(jid.clone());
        self.dismiss_notification(&id, cx);
        self.load_remote_scheduled(cx);
        for thread in self.wa_threads.iter_mut().filter(|t| t.id == id) {
            thread.unread = 0;
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
        let backend = self.backend.clone();
        let conversation =
            cx.new(|cx| SmsConversation::new(backend, thread_id, title, recipients, cx));
        cx.subscribe(&conversation, |this, _, event, cx| match event {
            SmsConversationEvent::Back => this.close_page(cx),
            SmsConversationEvent::Switch(thread) => this.switch_to(thread.clone(), cx),
            SmsConversationEvent::Schedule { to, body, sub_id } => {
                this.ask_schedule_sms(to.clone(), body.clone(), *sub_id, cx);
            }
            SmsConversationEvent::CancelScheduled(key) => this.cancel_scheduled(key.clone(), cx),
            SmsConversationEvent::ToggleMute => this.toggle_open_mute(cx),
        })
        .detach();
        if thread_id >= 0 {
            self.dismiss_notification(&ThreadId::Sms(thread_id), cx);
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
            .map(|row| row.title.clone())
    }

    fn open_new(&mut self, cx: &mut Context<Self>) {
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

    fn close_page(&mut self, cx: &mut Context<Self>) {
        self.open = Open::None;
        self.page.restart();
        cx.notify();
    }

    // ---- notifications (while attached) --------------------------------------

    /// The conversation on screen, if it is one.
    fn open_thread_id(&self, cx: &App) -> Option<ThreadId> {
        match &self.open {
            Open::WhatsApp(jid, _) => Some(ThreadId::WhatsApp(jid.clone())),
            Open::Sms(conversation) => {
                Some(conversation.read(cx).thread()).filter(|t| *t != ThreadId::Sms(-1))
            }
            Open::New(_) | Open::None => None,
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
        let open = self.open_thread_id(cx);
        if !notifications::should_notify(open.as_ref(), &incoming) {
            return;
        }
        let thread = incoming.thread.clone();
        let known = self.wa_threads.iter().find(|t| t.id == thread);
        let is_group = known.map_or_else(|| message.chat.ends_with("@g.us"), |t| t.is_group);
        let title = known
            .and_then(|t| t.title.clone())
            .or_else(|| self.title_of(&thread))
            .unwrap_or_else(|| jid_user(&message.chat).to_owned());
        let sender_title = message
            .sender
            .as_deref()
            .map_or_else(String::new, |jid| jid_user(jid).to_owned());
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
        let open = self.open_thread_id(cx);
        if !notifications::should_notify(open.as_ref(), &message) {
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
                conversation.update(cx, |c, cx| c.send_reply(text, cx));
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
                this.update(cx, |this, _| this.show_pill("The reply was not sent"))
                    .ok();
            }
        })
        .detach();
    }

    /// A tapped notification opens its conversation.
    fn on_open_tag(&mut self, tag: &str, cx: &mut Context<Self>) {
        let Some(thread) = notifications::parse_tag(tag) else {
            return;
        };
        let title = self.title_of(&thread).unwrap_or_default();
        self.open_thread(thread, title, cx);
    }

    fn toggle_open_mute(&mut self, cx: &mut Context<Self>) {
        let Some(thread) = self.open_thread_id(cx) else {
            return;
        };
        let tag = notifications::thread_tag(&thread);
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |prefs| {
                let muted = !prefs.is_muted(&tag);
                prefs.set_muted(&tag, muted);
            });
        });
    }

    /// Mute state and the Scheduled strip of the open conversation.
    fn sync_open_page(&mut self, cx: &mut Context<Self>) {
        let (muted, queue) = {
            let backend = self.backend.read(cx);
            let muted = self
                .open_thread_id(cx)
                .is_some_and(|t| backend.prefs().is_muted(&notifications::thread_tag(&t)));
            (muted, backend.queue().items().to_vec())
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
                    c.set_scheduled(items, cx);
                });
            }
            Open::New(_) | Open::None => {}
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
        self.schedule.open(draft, cx);
        cx.notify();
    }

    pub(crate) fn close_schedule(&mut self, cx: &mut Context<Self>) {
        self.schedule.close();
        cx.notify();
    }

    pub(crate) fn schedule_custom(&mut self, cx: &mut Context<Self>) {
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

    /// Schedules the sheet's text for `at_ms`: SMS into the local queue and an
    /// alarm, WhatsApp to the gateway.
    pub(crate) fn schedule_at(&mut self, at_ms: i64, cx: &mut Context<Self>) {
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
                                if let Open::WhatsApp(_, conversation) = &this.open {
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
                            Ok(()) => this.remote_scheduled.retain(|t| t.id != id),
                            Err(ClientError::Api(api))
                                if api.code == rovsti_wa_proto::ErrorCode::NotFound =>
                            {
                                this.remote_scheduled.retain(|t| t.id != id);
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
        let want_back = self.schedule.is_open();
        if want_back != self.back_enabled {
            self.back_enabled = want_back;
            window.set_back_enabled(want_back);
        }
        let offset = self.backend.read(cx).locale().tz_offset_s;
        let mut out = Vec::new();
        out.extend(self.pill.render(theme, self.pill_bottom(cx), {
            let entity = cx.entity();
            move |pointer, position, _, cx| {
                entity.update(cx, |this, cx| {
                    this.pill.pointer(pointer, position);
                    cx.notify();
                });
            }
        }));
        out.extend(self.schedule.render(theme, window, now_ms(), offset, cx));
        out
    }

    // ---- the inbox -----------------------------------------------------------

    /// Copies a one-time code (hidden from the paste preview) and says so.
    fn copy_code(&mut self, code: String, cx: &mut Context<Self>) {
        let text = code.clone();
        let task = cx
            .background_executor()
            .spawn(async move { rovsti_android::notify::copy_to_clipboard(&text, true) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            let copied = this
                .update(cx, |this, cx| {
                    match result {
                        Ok(true) => this.copied = Some(code.clone()),
                        // Desktop preview: the window's own clipboard.
                        Err(rovsti_android::Error::Unsupported) => {
                            cx.write_to_clipboard(ClipboardItem::new_string(code.clone()));
                            this.copied = Some(code.clone());
                        }
                        Ok(false) | Err(_) => log::warn!("copying the code failed"),
                    }
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
        inbox::row_element(
            theme,
            index,
            row,
            copied,
            Box::new(cx.listener(move |this, _, _, cx| {
                if let Some(thread) = thread.clone() {
                    this.open_thread(thread, title.clone(), cx);
                }
            })),
            Box::new(cx.listener(move |this, _, _, cx| {
                if let Some(code) = code.clone() {
                    this.copy_code(code, cx);
                }
            })),
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

    fn chats(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let (role_held, state) = {
            let backend = self.backend.read(cx);
            (backend.sms_role_held(), backend.threads_state())
        };
        let mut out: Vec<AnyElement> = Vec::new();
        if !role_held {
            out.push(self.role_banner(theme, cx));
        }
        if self.show_spam {
            out.push(
                list_row(
                    theme,
                    None,
                    "Inbox",
                    Some("Spam is never deleted or announced.".into()),
                    None,
                )
                .id("spam-back")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.show_spam = false;
                    cx.notify();
                }))
                .into_any_element(),
            );
            for (index, row) in self.view.spam.iter().enumerate() {
                out.push(self.inbox_row(theme, 1000 + index, row, cx));
            }
        } else {
            if !self.view.spam.is_empty() {
                let unread = self.view.spam_unread();
                let count = self.view.spam.len();
                out.push(
                    list_row(
                        theme,
                        Some(avatar(theme, "!", 44.0, colors.orange).into_any_element()),
                        "Spam",
                        Some(
                            format!("{count} conversation{}", if count == 1 { "" } else { "s" })
                                .into(),
                        ),
                        (unread > 0).then(|| unread.to_string().into()),
                    )
                    .id("spam-filter")
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.show_spam = true;
                        cx.notify();
                    }))
                    .into_any_element(),
                );
            }
            for (index, row) in self.view.rows.iter().enumerate() {
                out.push(self.inbox_row(theme, index, row, cx));
            }
            if self.view.rows.is_empty() && self.view.spam.is_empty() {
                out.push(match state {
                    Access::Denied => empty_state(
                        theme,
                        "SMS Access Is Off",
                        "Allow SMS access for Rovsti Messages in the system settings to see your messages.",
                    )
                    .into_any_element(),
                    Access::Loading => empty_state(theme, "Loading", "Reading your messages…")
                        .into_any_element(),
                    _ => empty_state(
                        theme,
                        "No Conversations",
                        "Start one with the pencil above. Link WhatsApp from the WhatsApp tab.",
                    )
                    .into_any_element(),
                });
            }
        }
        div().children(out).into_any_element()
    }
}

impl Render for MessagesApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame_timed(window, |dt, real| {
            self.tabs.tick(dt)
                | self.page.tick(dt)
                | self.schedule.sheet.tick(dt)
                | self.schedule.sheet.tick_real(real)
                | self.pill.tick(dt)
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

        let body = match tab {
            0 => self.chats(theme, cx),
            1 => self.whatsapp.clone().into_any_element(),
            _ => self.settings.clone().into_any_element(),
        };

        let compose = (tab == 0).then(|| {
            glass_icon_button(theme, Icon::LucidePencil, 0.0, false)
                .id("new-message")
                .on_click(cx.listener(|this, _, _, cx| this.open_new(cx)))
                .into_any_element()
        });

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
            .child(
                div()
                    .id("page")
                    .size_full()
                    .overflow_y_scroll()
                    .pb(content_bottom_inset())
                    .opacity(self.page.progress())
                    .child(
                        div()
                            .relative()
                            .top(px(self.page.offset(24.0)))
                            .child(large_title(theme, TABS[tab], compose))
                            .child(body),
                    ),
            )
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
                    // System Back closes the "Send later" sheet.
                    let weak = app.downgrade();
                    window.on_system_back(cx, move |_, cx| {
                        weak.update(cx, |this, cx| this.close_schedule(cx)).ok();
                    });
                    app
                },
            )
            .expect("failed to open the Rovsti Messages window");
            cx.activate(true);
        });
}
