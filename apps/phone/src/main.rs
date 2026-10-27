//! Rovsti Phone: the dialer.
//!
//! Desktop `cargo run -p rovsti_phone` renders the same UI for design work;
//! calls, contacts and the call log need the Android host (see
//! docs/android.md) and the default-dialer role.

use gpui::{
    App, Bounds, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Render,
    SharedString, StatefulInteractiveElement, Styled, Window, WindowBounds, WindowOptions, div,
    prelude::*, px, size,
};
use rovsti_android::{roles, telecom, xiaomi};
use rovsti_core::PhoneNumber;
use rovsti_ui::{
    Icon, IconAssets,
    components::{backdrop, empty_state, glass_pill, large_title, section},
    current_theme,
    glass::{GlassInteraction, GlassSpec, glass_interactive},
    motion::{Entrance, Motion, Presses, press_scale},
    motion_tokens::{self, scale},
    spring::Spring,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    tokens::{Theme, mix, typography},
};

const TABS: [&str; 4] = ["Favorites", "Recents", "Contacts", "Keypad"];
const TAB_ICONS: [Icon; 4] = [
    Icon::LucideStar,
    Icon::LucideClock,
    Icon::LucideUsers,
    Icon::LucideGrid3x3,
];
const KEYPAD_TAB: usize = 3;
const KEY_SIZE: f32 = 78.0;

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

    motion: Motion,
    presses: Presses,
    /// Page fade-and-lift on tab switches.
    page: Entrance,
    /// Status line fade-in.
    status_in: Entrance,
    /// Scale of the newest digit: kicked above 1, springs back.
    digit_pop: Spring,
    /// Horizontal offset of the number; kicked sideways on invalid input.
    shake: Spring,
    /// 0 = Delete hidden, 1 = shown.
    delete_shown: Spring,
}

impl PhoneApp {
    fn new() -> Self {
        let mut tabs = TabBar::new(
            TABS.into_iter()
                .zip(TAB_ICONS)
                .map(|(label, icon)| Tab::new(icon, label)),
        );
        tabs.jump_to(KEYPAD_TAB);
        Self {
            tabs,
            dialed: String::new(),
            status: None,
            xiaomi: xiaomi::detect(),
            motion: Motion::default(),
            presses: Presses::default(),
            page: Entrance::default(),
            status_in: Entrance::default(),
            digit_pop: Spring::new(1.0, motion_tokens::release()).with_epsilon(0.002),
            shake: Spring::new(0.0, motion_tokens::glow()).with_epsilon(0.1),
            delete_shown: Spring::new(0.0, motion_tokens::appear()).with_epsilon(0.002),
        }
    }

    fn set_status(&mut self, status: impl Into<SharedString>) {
        self.status = Some(status.into());
        self.status_in.restart();
    }

    fn select_tab(&mut self, index: usize, cx: &mut Context<Self>) {
        self.tabs.press();
        if self.tabs.select(index) {
            self.page.restart();
        }
        cx.notify();
    }

    fn key_down(&mut self, digit: &'static str, cx: &mut Context<Self>) {
        self.presses.down(digit);
        if self.dialed.len() < 32 {
            self.dialed.push_str(digit);
            self.status = None;
            self.digit_pop.snap(1.18);
            self.digit_pop.set_target(1.0);
            self.delete_shown.set_target(1.0);
        }
        cx.notify();
    }

    fn backspace(&mut self, cx: &mut Context<Self>) {
        self.presses.down("delete");
        self.dialed.pop();
        if self.dialed.is_empty() {
            self.delete_shown.set_target(0.0);
        }
        cx.notify();
    }

    fn call(&mut self, cx: &mut Context<Self>) {
        self.presses.down("call");
        match PhoneNumber::parse(&self.dialed) {
            Ok(number) => {
                let status = match telecom::place_call(number.as_str()) {
                    Ok(true) => format!("Calling {number}…"),
                    Ok(false) => "Grant the Phone permission to call directly.".to_owned(),
                    Err(rovsti_android::Error::Unsupported) => {
                        format!("Would call {number} (desktop preview)")
                    }
                    Err(error) => error.to_string(),
                };
                self.set_status(status);
            }
            Err(error) => {
                self.set_status(error.to_string());
                // A sideways kick on a centred spring reads as "no".
                self.shake.kick(-1400.0);
            }
        }
        cx.notify();
    }

    fn tick(&mut self, dt: std::time::Duration) -> bool {
        let mut moving = self.tabs.tick(dt);
        moving |= self.presses.tick(dt);
        moving |= self.page.tick(dt);
        moving |= self.status_in.tick(dt);
        for spring in [&mut self.digit_pop, &mut self.shake, &mut self.delete_shown] {
            moving |= spring.tick(dt);
        }
        moving
    }

    fn number_display(&self, theme: Theme) -> impl IntoElement {
        let colors = theme.colors();
        let (head, last) = match self.dialed.char_indices().last() {
            Some((i, _)) => (&self.dialed[..i], &self.dialed[i..]),
            None => ("", ""),
        };
        div()
            .relative()
            .left(px(self.shake.value * 0.02))
            .h(px(52.0))
            .px(px(24.0))
            .flex()
            .items_center()
            .text_color(colors.label)
            .child(
                div()
                    .text_size(px(36.0))
                    .child(SharedString::from(head.to_owned())),
            )
            .child(
                div()
                    .text_size(px(36.0 * self.digit_pop.value))
                    .child(SharedString::from(last.to_owned())),
            )
    }

    fn key(
        &self,
        theme: Theme,
        (digit, letters): (&'static str, &'static str),
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme.colors();
        let press = self.presses.value(digit);
        let side = KEY_SIZE * press_scale(press, scale::PRESS_CARD);
        let spec =
            GlassSpec::control(theme).lerp(GlassSpec::lens(theme), press.clamp(0.0, 1.0) * 0.6);
        // Fixed slot so neighbours never move while a key shrinks.
        div()
            .id(digit)
            .size(px(KEY_SIZE))
            .flex()
            .items_center()
            .justify_center()
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, _, cx| this.key_down(digit, cx)),
            )
            .child(
                glass_interactive(spec, move || GlassInteraction { press, focus: None })
                    .size(px(side))
                    .rounded_full()
                    .flex()
                    .flex_col()
                    .items_center()
                    .justify_center()
                    .child(
                        div()
                            .text_size(px(32.0 * side / KEY_SIZE))
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
                    ),
            )
    }

    fn keypad(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme.colors();
        let mut rows = Vec::new();
        for row in KEYS.chunks(3) {
            let keys: Vec<_> = row
                .iter()
                .map(|&k| self.key(theme, k, cx).into_any_element())
                .collect();
            rows.push(div().flex().gap(px(24.0)).children(keys));
        }

        let call_press = self.presses.value("call");
        let call_side = KEY_SIZE * press_scale(call_press, scale::PRESS_CARD);
        let call_bg = mix(
            colors.green,
            gpui::white(),
            0.18 * call_press.clamp(0.0, 1.0),
        );
        let delete_press = self.presses.value("delete");
        let shown = self.delete_shown.value.clamp(0.0, 1.0);

        div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(16.0))
            .pt(px(24.0))
            .child(self.number_display(theme))
            .child(
                div()
                    .h(px(18.0))
                    .opacity(self.status_in.progress())
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .children(self.status.clone()),
            )
            .children(rows)
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(24.0))
                    .child(div().size(px(KEY_SIZE)))
                    .child(
                        div()
                            .id("call")
                            .size(px(KEY_SIZE))
                            .flex()
                            .items_center()
                            .justify_center()
                            .on_mouse_down(
                                MouseButton::Left,
                                cx.listener(|this, _, _, cx| this.call(cx)),
                            )
                            .child(
                                div()
                                    .size(px(call_side))
                                    .rounded_full()
                                    .bg(call_bg)
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .text_size(typography::HEADLINE.size)
                                    .font_weight(typography::HEADLINE.weight)
                                    .text_color(gpui::white())
                                    .child("Call"),
                            ),
                    )
                    .child(
                        div()
                            .id("backspace")
                            .size(px(KEY_SIZE))
                            .flex()
                            .items_center()
                            .justify_center()
                            .opacity(shown * (1.0 - 0.5 * delete_press.clamp(0.0, 1.0)))
                            .left(px((1.0 - shown) * 12.0))
                            .text_size(typography::HEADLINE.size)
                            .text_color(colors.secondary_label)
                            .child("Delete")
                            .when(!self.dialed.is_empty(), |el| {
                                el.on_mouse_down(
                                    MouseButton::Left,
                                    cx.listener(|this, _, _, cx| this.backspace(cx)),
                                )
                            }),
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
            .opacity(self.page.progress())
            .child(
                div()
                    .relative()
                    .top(px(self.page.offset(24.0)))
                    .child(large_title(theme, TABS[tab], None))
                    .children(self.setup_card(theme, cx))
                    .child(match tab {
                        KEYPAD_TAB => self.keypad(theme, cx).into_any_element(),
                        0 => empty_state(theme, "No Favorites", "Star a contact to call them with one tap.")
                            .into_any_element(),
                        1 => empty_state(
                            theme,
                            "No Recent Calls",
                            "Your call history appears here once Rovsti Phone is the default dialer.",
                        )
                        .into_any_element(),
                        _ => empty_state(theme, "Contacts", "Grant contacts access to see the people on this phone.")
                            .into_any_element(),
                    }),
            )
    }
}

impl Render for PhoneApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| self.tick(dt));
        self.motion = motion;

        let entity = cx.entity();
        div()
            .size_full()
            .relative()
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.presses.release_all();
                    this.tabs.release();
                    cx.notify();
                }),
            )
            .child(backdrop(theme, theme.colors().green))
            .child(self.page(theme, cx))
            .child(self.tabs.render(theme, move |index, _, cx| {
                entity.update(cx, |this, cx| this.select_tab(index, cx));
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
                |_, cx| cx.new(|_| PhoneApp::new()),
            )
            .expect("failed to open the Rovsti Phone window");
            cx.activate(true);
        });
}
