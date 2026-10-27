//! Rovsti Messages: SMS/MMS and WhatsApp in one inbox.
//!
//! WhatsApp runs as a linked device inside this app (see `rovsti_whatsapp`);
//! nothing goes through a Rovsti server.

mod whatsapp;

use gpui::{
    App, Bounds, Context, Entity, IntoElement, ParentElement, Render, StatefulInteractiveElement,
    Styled, Window, WindowBounds, WindowOptions, div, prelude::*, px, size,
};
use rovsti_ui::{
    Icon, IconAssets,
    components::{avatar, empty_state, large_title, list_row},
    current_theme,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    tokens::Theme,
};

use crate::whatsapp::WhatsAppScreen;

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
    whatsapp: Entity<WhatsAppScreen>,
    threads: Vec<rovsti_core::Message>,
}

impl MessagesApp {
    fn new(cx: &mut Context<Self>) -> Self {
        let whatsapp = cx.new(WhatsAppScreen::new);
        // Fold WhatsApp messages into the unified inbox as they arrive.
        cx.observe(&whatsapp, |this, whatsapp, cx| {
            for message in whatsapp.update(cx, |screen, _| screen.take_new_messages()) {
                this.threads.retain(|m| m.thread != message.thread);
                this.threads.insert(0, message);
            }
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
        }
    }

    fn chats(&self, theme: Theme) -> impl IntoElement {
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
            .children(self.threads.iter().map(|message| {
                let title = message
                    .sender
                    .as_ref()
                    .map_or_else(|| "Me".to_owned(), ToString::to_string);
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
                list_row(
                    theme,
                    Some(avatar(theme, initials, 44.0, badge).into_any_element()),
                    title,
                    Some(message.body.clone().into()),
                    None,
                )
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
        let tab = self.tabs.selected();
        let entity = cx.entity();

        let body = match tab {
            0 => self.chats(theme).into_any_element(),
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
            .child(self.tabs.render(theme, move |index, _, cx| {
                entity.update(cx, |this, cx| {
                    this.tabs.press();
                    if this.tabs.select(index) {
                        this.page.restart();
                    }
                    cx.notify();
                });
            }))
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
