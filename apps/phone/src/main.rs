//! Rovsti Phone: the dialer.
//!
//! Desktop `cargo run -p rovsti_phone` renders the same UI for design work;
//! calls, contacts and the call log need the Android host (see
//! docs/android.md) and the default-dialer role.

use gpui::{
    App, Bounds, Context, InteractiveElement, IntoElement, ParentElement, Render, SharedString,
    StatefulInteractiveElement, Styled, Window, WindowBounds, WindowOptions, div, prelude::*, px,
    size,
};
use rovsti_android::{roles, telecom, xiaomi};
use rovsti_core::PhoneNumber;
use rovsti_ui::{
    components::{empty_state, glass_pill, large_title, section},
    current_theme,
    glass::{GlassSpec, glass},
    tab_bar::{TabBar, content_bottom_inset},
    tokens::{Theme, typography},
};

const TABS: [&str; 4] = ["Favorites", "Recents", "Contacts", "Keypad"];
const KEYPAD_TAB: usize = 3;

/// Dial pad keys with their letter captions.
const KEYS: [(&str, &str); 12] = [
    ("1", ""),
    ("2", "ABC"),
    ("3", "DEF"),
    ("4", "GHI"),
    ("5", "JKL"),
    ("6", "MNO"),
    ("7", "PQRS"),
    ("8", "TUV"),
    ("9", "WXYZ"),
    ("*", ""),
    ("0", "+"),
    ("#", ""),
];

struct PhoneApp {
    tabs: TabBar,
    dialed: String,
    status: Option<SharedString>,
    xiaomi: Option<xiaomi::XiaomiOs>,
}

impl PhoneApp {
    fn new() -> Self {
        let mut tabs = TabBar::new(TABS);
        tabs.select(KEYPAD_TAB);
        Self {
            tabs,
            dialed: String::new(),
            status: None,
            xiaomi: xiaomi::detect(),
        }
    }

    fn press_key(&mut self, key: &str, cx: &mut Context<Self>) {
        if self.dialed.len() < 32 {
            self.dialed.push_str(key);
            self.status = None;
            cx.notify();
        }
    }

    fn backspace(&mut self, cx: &mut Context<Self>) {
        self.dialed.pop();
        cx.notify();
    }

    fn call(&mut self, cx: &mut Context<Self>) {
        let number = match PhoneNumber::parse(&self.dialed) {
            Ok(number) => number,
            Err(error) => {
                self.status = Some(error.to_string().into());
                cx.notify();
                return;
            }
        };
        self.status = Some(match telecom::place_call(number.as_str()) {
            Ok(true) => format!("Calling {number}…").into(),
            Ok(false) => "Grant the Phone permission to call directly.".into(),
            Err(rovsti_android::Error::Unsupported) => {
                format!("Would call {number} (desktop preview)").into()
            }
            Err(error) => error.to_string().into(),
        });
        cx.notify();
    }

    fn keypad(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme.colors();
        let key = |(digit, letters): (&'static str, &'static str), cx: &mut Context<Self>| {
            glass(GlassSpec::control(theme))
                .id(digit)
                .size(px(78.0))
                .rounded_full()
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .child(
                    div()
                        .text_size(px(32.0))
                        .text_color(colors.label)
                        .child(digit),
                )
                .child(
                    div()
                        .h(px(12.0))
                        .text_size(typography::CAPTION2.size)
                        .font_weight(typography::CAPTION2.weight)
                        .text_color(colors.secondary_label)
                        .child(letters),
                )
                .on_click(cx.listener(move |this, _, _, cx| this.press_key(digit, cx)))
        };

        let rows = KEYS.chunks(3).map(|row| {
            div()
                .flex()
                .gap(px(24.0))
                .children(row.iter().map(|&k| key(k, cx)))
        });
        let rows: Vec<_> = rows.collect();

        div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(16.0))
            .pt(px(24.0))
            .child(
                div()
                    .h(px(48.0))
                    .px(px(24.0))
                    .text_size(px(36.0))
                    .text_color(colors.label)
                    .child(SharedString::from(self.dialed.clone())),
            )
            .children(self.status.clone().map(|status| {
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(status)
            }))
            .children(rows)
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(24.0))
                    .child(div().size(px(78.0)))
                    .child(
                        div()
                            .id("call")
                            .size(px(78.0))
                            .rounded_full()
                            .bg(colors.green)
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(gpui::white())
                            .child("Call")
                            .on_click(cx.listener(|this, _, _, cx| this.call(cx))),
                    )
                    .child(
                        div()
                            .id("backspace")
                            .size(px(78.0))
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_size(typography::HEADLINE.size)
                            .text_color(colors.secondary_label)
                            .child(if self.dialed.is_empty() { "" } else { "Delete" })
                            .on_click(cx.listener(|this, _, _, cx| this.backspace(cx))),
                    ),
            )
    }

    /// Default-dialer role and, on Xiaomi, the background checklist.
    fn setup_card(&self, theme: Theme, cx: &mut Context<Self>) -> Option<impl IntoElement> {
        let role_held = roles::is_held(roles::Role::Dialer).unwrap_or(true);
        if role_held && self.xiaomi.is_none() {
            return None;
        }
        let mut rows = Vec::new();
        if !role_held {
            rows.push(
                glass_pill(theme, "Set Rovsti Phone as default")
                    .id("role")
                    .m(px(12.0))
                    .on_click(cx.listener(|_, _, _, _| {
                        if let Err(error) = roles::request(roles::Role::Dialer) {
                            log::warn!("dialer role request failed: {error}");
                        }
                    }))
                    .into_any_element(),
            );
        }
        if self.xiaomi.is_some() {
            for (label, setting) in [
                ("Allow Autostart", xiaomi::Setting::Autostart),
                (
                    "Battery saver: No restrictions",
                    xiaomi::Setting::BatterySaver,
                ),
                (
                    "Show on lock screen & pop-ups",
                    xiaomi::Setting::OtherPermissions,
                ),
            ] {
                rows.push(
                    glass_pill(theme, label)
                        .id(label)
                        .m(px(12.0))
                        .on_click(move |_, _, _| {
                            if let Err(error) = xiaomi::open(setting) {
                                log::warn!("opening Xiaomi setting failed: {error}");
                            }
                        })
                        .into_any_element(),
                );
            }
        }
        Some(section(
            theme,
            Some("Finish setup so calls always ring".into()),
            rows,
        ))
    }

    fn page(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let tab = self.tabs.selected();
        div()
            .id("page")
            .size_full()
            .overflow_y_scroll()
            .pb(content_bottom_inset())
            .child(large_title(theme, TABS[tab], None))
            .children(self.setup_card(theme, cx))
            .child(match tab {
                KEYPAD_TAB => self.keypad(theme, cx).into_any_element(),
                0 => empty_state(
                    theme,
                    "No Favorites",
                    "Star a contact to call them with one tap.",
                )
                .into_any_element(),
                1 => empty_state(
                    theme,
                    "No Recent Calls",
                    "Your call history appears here once Rovsti Phone is the default dialer.",
                )
                .into_any_element(),
                _ => empty_state(
                    theme,
                    "Contacts",
                    "Grant contacts access to see the people on this phone.",
                )
                .into_any_element(),
            })
    }
}

impl Render for PhoneApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        self.tabs.tick(window);
        let entity = cx.entity();
        div()
            .size_full()
            .relative()
            .bg(theme.colors().background)
            .child(self.page(theme, cx))
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
            |_, cx| cx.new(|_| PhoneApp::new()),
        )
        .expect("failed to open the Rovsti Phone window");
        cx.activate(true);
    });
}
