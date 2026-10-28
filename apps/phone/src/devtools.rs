//! Desktop-only design aids (story 10.4). This module is not compiled for
//! Android. It adds a thin row of chips at the very top of the window that
//! starts the sample scenarios (ringing, calling, hold, waiting, mute, a
//! missed call arriving), and the "Preview data" caption under list titles.
//! Both are absolutely positioned overlays: the real layout never moves.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, div, px,
};
use rovsti_core::wire::CallLogKind;
use rovsti_ui::{
    theme,
    tokens::{Theme, metrics},
};

use crate::PhoneApp;

/// `(press id, label)` of each chip.
const CHIPS: [(&str, &str); 7] = [
    ("dev-ring", "Ring"),
    ("dev-ringp", "Ring Ayşe"),
    ("dev-call", "Call"),
    ("dev-hold", "Hold"),
    ("dev-wait", "Waiting"),
    ("dev-mute", "Muted"),
    ("dev-missed", "+Missed"),
];

impl PhoneApp {
    pub(crate) fn dev_switcher(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .absolute()
            .top(px(8.0))
            .left_0()
            .right_0()
            .flex()
            .justify_center()
            .gap(px(4.0))
            .children(CHIPS.map(|(id, label)| {
                div()
                    .id(id)
                    .role(Role::Button)
                    .aria_label(label)
                    .h(px(20.0))
                    .px(px(7.0))
                    .rounded_full()
                    .bg(gpui::Hsla {
                        a: 0.8,
                        ..colors.fill
                    })
                    .flex()
                    .items_center()
                    .text_size(px(10.5))
                    .text_color(colors.secondary_label)
                    .child(label)
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, _, cx| this.dev_action(id, cx)),
                    )
            }))
            .into_any_element()
    }

    /// Desktop preview: plays a script of presses (ROVSTI_PREVIEW_KEYS), one
    /// step every ROVSTI_PREVIEW_KEYS_MS (default 350) after
    /// ROVSTI_PREVIEW_KEYS_AT ms (default 2500). Digits, `*` and `#` press keys,
    /// `c` Call, `d` Delete, `a` Answer, `x` Decline, `e` End, `f` the first
    /// Favorites tile, `r` the first Recents row, `t<n>` selects tab n and
    /// `.` waits one step, `m` Mute, `h` Hold, `w` Waiting, `k` a call, `n` a new missed call,
    /// `s` the open Contact Sheet's star, `z` replays Setup complete (S14). It goes through the same code as a tap.
    pub(crate) fn start_preview_keys(
        &mut self,
        script: String,
        window: &mut gpui::Window,
        cx: &mut Context<Self>,
    ) {
        let env_ms = |name: &str, default: u64| {
            std::env::var(name)
                .ok()
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(default)
        };
        let step = std::time::Duration::from_millis(env_ms("ROVSTI_PREVIEW_KEYS_MS", 350));
        let start = std::time::Duration::from_millis(env_ms("ROVSTI_PREVIEW_KEYS_AT", 2500));
        cx.spawn_in(window, async move |this, cx| {
            cx.background_executor().timer(start).await;
            let mut chars = script.chars().peekable();
            while let Some(c) = chars.next() {
                let tab = (c == 't')
                    .then(|| chars.next().and_then(|d| d.to_digit(10)))
                    .flatten();
                let alive = this
                    .update_in(cx, |this, window, cx| {
                        this.preview_press(c, tab.map(|t| t as usize), window, cx);
                    })
                    .is_ok();
                if !alive {
                    break;
                }
                cx.background_executor().timer(step / 3).await;
                this.update(cx, |this, cx| {
                    this.released(cx);
                    cx.notify();
                })
                .ok();
                cx.background_executor().timer(step * 2 / 3).await;
            }
        })
        .detach();
    }

    fn preview_press(
        &mut self,
        c: char,
        tab: Option<usize>,
        window: &mut gpui::Window,
        cx: &mut Context<Self>,
    ) {
        let key_id;
        let id: Option<&str> = match c {
            '0'..='9' | '*' | '#' => {
                key_id = format!("key-{c}");
                Some(key_id.as_str())
            }
            'c' => Some("call"),
            'd' => Some("delete"),
            'e' => Some("ic-end"),
            'a' => Some("in-answer"),
            'x' => Some("in-decline"),
            _ => None,
        };
        if let Some(id) = id {
            self.presses.down(id.to_owned());
            if matches!(id, "in-answer" | "in-decline") {
                let id: &'static str = if id == "in-answer" {
                    "in-answer"
                } else {
                    "in-decline"
                };
                self.incoming_mouse_down(id, window, cx);
            } else {
                self.activate(id, window, cx);
            }
        }
        match c {
            't' => {
                if let Some(index) = tab {
                    self.select_tab(index, cx);
                }
            }
            'f' => self.preview_tap_favorite(window, cx),
            'r' => self.preview_tap_recent(window, cx),
            'm' => self.dev_action("dev-mute", cx),
            'h' => self.dev_action("dev-hold", cx),
            'w' => self.dev_action("dev-wait", cx),
            'k' => self.dev_action("dev-call", cx),
            'n' => self.dev_action("dev-missed", cx),
            's' => self.contact_sheet_act("sheet-cstar", window, cx),
            'z' => {
                let reduced = window.prefers_reduced_motion();
                self.celebrate.observe(false, reduced);
                self.celebrate.observe(true, reduced);
            }
            _ => {}
        }
        cx.notify();
    }

    fn dev_action(&mut self, id: &str, cx: &mut Context<Self>) {
        self.backend.update(cx, |backend, cx| match id {
            "dev-ring" => backend.preview_ring(None, false, false, cx),
            "dev-ringp" => backend.preview_ring(Some("Ayşe Yılmaz"), false, false, cx),
            "dev-call" => {
                backend.place_call("+905551112233", cx);
            }
            "dev-hold" => backend.preview_scenario("hold", cx),
            "dev-wait" => backend.preview_scenario("waiting", cx),
            "dev-mute" => backend.preview_scenario("mute", cx),
            "dev-missed" => backend.preview_new_call(CallLogKind::Missed, cx),
            _ => {}
        });
    }

    /// "Preview data" in tertiary label under a list title, fading out with it.
    pub(crate) fn preview_caption(&self, theme: Theme, opacity: f32) -> AnyElement {
        div()
            .absolute()
            .top(px(56.0 + 41.0 - 5.0))
            .left(metrics::ROW_INSET)
            .opacity(opacity)
            .text_size(px(11.0))
            .text_color(theme::colors(theme).tertiary_label)
            .child(SharedString::new_static("Preview data"))
            .into_any_element()
    }
}
