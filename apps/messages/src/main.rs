//! Rovsti Messages: SMS/MMS and WhatsApp in one inbox.
//!
//! WhatsApp runs on the Rovsti gateway (`services/wa_gateway`): the WhatsApp
//! tab hosts the shared settings screen (`rovsti_wa_ui`) that links this app to
//! an instance, and chats and live messages come from the gateway through
//! `rovsti_wa_client`. SMS stays on the phone.

mod conversation;

use gpui::{
    App, AppContext as _, Bounds, Context, Entity, IntoElement, ParentElement, Render,
    StatefulInteractiveElement, Styled, Window, WindowBounds, WindowOptions, div, prelude::*, px,
    size,
};
use rovsti_ui::{
    Icon, IconAssets,
    components::{avatar, empty_state, large_title, list_row},
    current_theme,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    tokens::Theme,
};

use conversation::{Conversation, ConversationEvent};
use rovsti_wa_client::ClientError;
use rovsti_wa_proto::{ChatSummary, GatewayEvent, to_rovsti};
use rovsti_wa_ui::{AppId, WhatsAppSettings};

const TABS: [&str; 3] = ["Chats", "WhatsApp", "Settings"];
const TAB_ICONS: [Icon; 3] = [
    Icon::LucideMessageSquare,
    Icon::LucideMessageCircle,
    Icon::LucideSettings,
];

struct MessagesApp {
    tabs: TabBar,
    motion: rovsti_ui::motion::Motion,
    page: rovsti_ui::motion::Entrance,
    whatsapp: Entity<WhatsAppSettings>,
    threads: Vec<rovsti_core::Message>,
    /// The gateway's chat list, kept for titles and the group flag.
    chat_list: Vec<ChatSummary>,
    /// The WhatsApp conversation pushed over the tabs.
    open: Option<Entity<Conversation>>,
    /// The chat list is being fetched, or was fetched for this link.
    chats: ChatsState,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ChatsState {
    /// Not connected, or the chat list is stale and a fetch is due.
    Needed,
    Loading,
    Loaded,
}

impl MessagesApp {
    fn new(cx: &mut Context<Self>) -> Self {
        let whatsapp = cx.new(|cx| WhatsAppSettings::new(AppId::Messages, "messages", cx));
        // Fold WhatsApp messages into the unified inbox as they arrive, and
        // load the chat list once the instance is connected.
        cx.observe(&whatsapp, |this, whatsapp, cx| {
            let events = whatsapp.update(cx, |settings, _| settings.take_events());
            let connected = whatsapp.read(cx).is_connected();
            whatsapp.update(cx, |settings, cx| settings.register_push(cx));
            this.on_gateway_events(events, connected, cx);
            cx.notify();
        })
        .detach();
        Self {
            tabs: TabBar::new(
                TABS.into_iter()
                    .zip(TAB_ICONS)
                    .map(|(label, icon)| Tab::new(icon, label)),
            ),
            motion: Default::default(),
            page: Default::default(),
            whatsapp,
            threads: Vec::new(),
            chat_list: Vec::new(),
            open: None,
            chats: ChatsState::Needed,
        }
    }

    fn fold(&mut self, message: rovsti_core::Message) {
        self.threads.retain(|m| m.thread != message.thread);
        self.threads.insert(0, message);
    }

    fn on_gateway_events(
        &mut self,
        events: Vec<GatewayEvent>,
        connected: bool,
        cx: &mut Context<Self>,
    ) {
        for event in events {
            if let Some(open) = &self.open {
                open.update(cx, |conversation, cx| conversation.on_event(&event, cx));
            }
            match event {
                GatewayEvent::Message(message) => self.fold(to_rovsti(&message)),
                // The stream missed events: fetch the chat list again.
                GatewayEvent::Resync => self.chats = ChatsState::Needed,
                _ => {}
            }
        }
        if !connected && self.whatsapp.read(cx).link().is_none() {
            // Unlinked: WhatsApp chats leave the inbox.
            self.threads
                .retain(|m| m.thread.channel() != rovsti_core::Channel::WhatsApp);
            self.chat_list.clear();
            self.open = None;
            self.chats = ChatsState::Needed;
        } else if connected && self.chats == ChatsState::Needed {
            self.load_chats(cx);
        }
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
                match result {
                    Ok(chats) => {
                        this.chats = ChatsState::Loaded;
                        this.merge_chats(&chats);
                    }
                    Err(ClientError::Unauthorized) => this.chats = ChatsState::Loaded,
                    Err(error) => {
                        log::warn!("loading WhatsApp chats failed: {error}");
                        this.chats = ChatsState::Loaded;
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Chats that are already in the inbox (a live message arrived first) keep
    /// whichever message is newer.
    fn merge_chats(&mut self, chats: &[ChatSummary]) {
        self.chat_list = chats.to_vec();
        for message in chats.iter().filter_map(|c| c.last.as_ref()).map(to_rovsti) {
            let newer = self
                .threads
                .iter()
                .find(|m| m.thread == message.thread)
                .is_none_or(|have| have.timestamp_ms < message.timestamp_ms);
            if newer {
                self.fold(message);
            }
        }
        self.threads
            .sort_by_key(|m| std::cmp::Reverse(m.timestamp_ms));
    }

    /// Title and group flag of a chat: the chat's name, else `+user`.
    fn chat_title(&self, jid: &str) -> (String, bool) {
        let summary = self.chat_list.iter().find(|c| c.jid == jid);
        let title = summary
            .and_then(|c| c.name.clone().filter(|n| !n.is_empty()))
            .unwrap_or_else(|| format!("+{}", jid.split(['@', ':']).next().unwrap_or(jid)));
        let is_group = summary.map_or_else(|| jid.ends_with("@g.us"), |c| c.is_group);
        (title, is_group)
    }

    fn open_chat(&mut self, jid: String, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        let (title, is_group) = self.chat_title(&jid);
        let conversation = cx.new(|cx| Conversation::new(jid, title, is_group, gateway, cx));
        cx.subscribe(&conversation, |this, _, event, cx| match event {
            ConversationEvent::Back => this.close_chat(cx),
        })
        .detach();
        self.open = Some(conversation);
        self.page.restart();
        cx.notify();
    }

    fn close_chat(&mut self, cx: &mut Context<Self>) {
        self.open = None;
        self.page.restart();
        cx.notify();
    }

    fn chats(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme.colors();
        if self.threads.is_empty() {
            return empty_state(
                theme,
                "No Conversations",
                "SMS appear here once Rovsti Messages is the default SMS app. Link WhatsApp from the WhatsApp tab.",
            )
            .into_any_element();
        }
        div()
            .children(self.threads.iter().enumerate().map(|(index, message)| {
                let jid = match &message.thread {
                    rovsti_core::ThreadId::WhatsApp(jid) => Some(jid.clone()),
                    rovsti_core::ThreadId::Sms(_) => None,
                };
                let title = match &jid {
                    Some(jid) => self.chat_title(jid).0,
                    None => message
                        .sender
                        .as_ref()
                        .map_or_else(|| "Me".to_owned(), ToString::to_string),
                };
                let badge = match message.thread.channel() {
                    rovsti_core::Channel::Sms => colors.accent,
                    rovsti_core::Channel::WhatsApp => colors.green,
                };
                let initials: String = title
                    .chars()
                    .filter(char::is_ascii_digit)
                    .rev()
                    .take(2)
                    .collect();
                let row = list_row(
                    theme,
                    Some(avatar(theme, initials, 44.0, badge).into_any_element()),
                    title,
                    Some(message.body.clone().into()),
                    None,
                );
                // WhatsApp rows open the conversation; SMS rows stay inert.
                match jid {
                    Some(jid) => row
                        .id(("thread", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.open_chat(jid.clone(), cx);
                        }))
                        .into_any_element(),
                    None => row.into_any_element(),
                }
            }))
            .into_any_element()
    }
}

impl Render for MessagesApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| self.tabs.tick(dt) | self.page.tick(dt));
        self.motion = motion;
        if let Some(conversation) = self.open.clone() {
            return div()
                .size_full()
                .relative()
                .child(rovsti_ui::components::backdrop(theme, theme.colors().green))
                .child(
                    div()
                        .size_full()
                        .opacity(self.page.progress())
                        .child(conversation),
                )
                .into_any_element();
        }
        let tab = self.tabs.selected();
        let entity = cx.entity();
        let touch_entity = entity.clone();

        let body = match tab {
            0 => self.chats(theme, cx).into_any_element(),
            1 => self.whatsapp.clone().into_any_element(),
            _ => empty_state(
                theme,
                "Settings",
                "Notifications, blocked numbers and backups live here.",
            )
            .into_any_element(),
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
                            .child(large_title(theme, TABS[tab], None))
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

#[gpui_platform::main]
fn main() {
    gpui_platform::application()
        .with_assets(IconAssets::new())
        .run(|cx: &mut App| {
            cx.open_window(
                WindowOptions {
                    window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                        None,
                        size(px(402.0), px(874.0)),
                        cx,
                    ))),
                    ..Default::default()
                },
                |_, cx| cx.new(MessagesApp::new),
            )
            .expect("failed to open the Rovsti Messages window");
            cx.activate(true);
        });
}
