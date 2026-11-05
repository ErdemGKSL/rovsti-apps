//! Rovsti Messages: SMS/MMS and WhatsApp in one inbox.
//!
//! WhatsApp runs on the Rovsti gateway (`services/wa_gateway`): the WhatsApp
//! tab hosts the shared settings screen (`rovsti_wa_ui`) that links this app to
//! an instance, and chats and live messages come from the gateway through
//! `rovsti_wa_client`. SMS stays on the phone.

mod backend;
mod conversation;
// Only the Android JNI entry calls `handle`; desktop builds exercise it from the tests.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
mod headless;
mod inbox;
mod new_conversation;
mod sms_conversation;

use std::time::Duration;

use gpui::{
    AnyElement, App, AppContext as _, Bounds, ClipboardItem, Context, Entity, InteractiveElement,
    IntoElement, ParentElement, Render, StatefulInteractiveElement, Styled, Subscription, Window,
    WindowBounds, WindowOptions, div, px, size,
};
use rovsti_core::{ThreadId, inbox::InboxThread};
use rovsti_ui::{
    Icon, IconAssets,
    components::{avatar, empty_state, glass_icon_button, glass_pill, large_title, list_row},
    current_theme,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    theme,
    tokens::{Theme, typography},
};

use backend::{Access, Backend, BackendEvent, now_ms};
use conversation::{Conversation, ConversationEvent};
use inbox::{InboxView, RowView};
use new_conversation::{NewConversation, NewConversationEvent, Recipient};
use rovsti_wa_client::ClientError;
use rovsti_wa_proto::GatewayEvent;
use rovsti_wa_ui::{AppId, WhatsAppSettings};
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
    backend: Entity<Backend>,
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
            cx.subscribe(&backend, |this, _, event, cx| {
                if matches!(event, BackendEvent::WaPush) {
                    this.whatsapp.update(cx, |settings, cx| settings.wake(cx));
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
            backend,
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
        let conversation =
            cx.new(|cx| Conversation::new(chat, title, is_group, gateway, locale, cx));
        cx.subscribe(&conversation, |this, _, event, cx| match event {
            ConversationEvent::Back => this.close_page(cx),
            ConversationEvent::Switch(thread) => this.switch_to(thread.clone(), cx),
        })
        .detach();
        // Opening reads the chat.
        let id = ThreadId::WhatsApp(jid.clone());
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
        })
        .detach();
        self.open = Open::Sms(conversation);
        self.update_switch(cx);
        self.page.restart();
        cx.notify();
    }

    /// The header's channel switch: the same person's other thread.
    fn switch_to(&mut self, thread: ThreadId, cx: &mut Context<Self>) {
        let title = self
            .view
            .rows
            .iter()
            .chain(&self.view.spam)
            .find(|row| row.threads.contains(&thread))
            .map(|row| row.title.clone())
            .unwrap_or_default();
        self.open_thread(thread, title, cx);
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
        motion.frame(window, |dt| self.tabs.tick(dt) | self.page.tick(dt));
        self.motion = motion;
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
                .into_any_element();
        }
        let tab = self.tabs.selected();
        let entity = cx.entity();
        let touch_entity = entity.clone();

        let body = match tab {
            0 => self.chats(theme, cx),
            1 => self.whatsapp.clone().into_any_element(),
            _ => empty_state(
                theme,
                "Settings",
                "Notifications, blocked numbers and backups live here.",
            )
            .into_any_element(),
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
                |_, cx| cx.new(|cx| MessagesApp::new(backend, cx)),
            )
            .expect("failed to open the Rovsti Messages window");
            cx.activate(true);
        });
}
