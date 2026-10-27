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
    components::{avatar, empty_state, large_title, list_row},
    current_theme,
    tab_bar::{TabBar, content_bottom_inset},
    tokens::Theme,
};

use crate::whatsapp::WhatsAppScreen;

const TABS: [&str; 3] = ["Chats", "WhatsApp", "Settings"];

struct MessagesApp {
    tabs: TabBar,
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
            tabs: TabBar::new(TABS),
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
        self.tabs.tick(window);
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
            .bg(theme.colors().background)
            .child(
                div()
                    .id("page")
                    .size_full()
                    .overflow_y_scroll()
                    .pb(content_bottom_inset())
                    .child(large_title(theme, TABS[tab], None))
                    .child(body),
            )
            .child(self.tabs.render(theme, move |index, _, cx| {
                entity.update(cx, |this, cx| {
                    this.tabs.select(index);
                    cx.notify();
                });
            }))
    }
}

#[gpui_platform::main]
fn main() {
    gpui_platform::application().run(|cx: &mut App| {
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
