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
const CHIPS: [(&str, &str); 10] = [
    ("dev-ring", "Ring"),
    ("dev-ringp", "Ring Ayşe"),
    ("dev-call", "Call"),
    ("dev-hold", "Hold"),
    ("dev-wait", "Waiting"),
    ("dev-mute", "Muted"),
    ("dev-missed", "+Missed"),
    ("dev-routes", "Routes"),
    ("dev-btadd", "+BT"),
    ("dev-btrm", "-BT"),
];

/// The multi-call design scenes (story 15.3), a second row of chips under the first.
/// `(press id, label, scene name)`; see `backend/preview_multi.rs`.
const SCENE_CHIPS: [(&str, &str, &str); 8] = [
    ("dev-s-single", "1 call", "single"),
    ("dev-s-two", "2 calls", "two"),
    ("dev-s-nomerge", "No merge", "nomerge"),
    ("dev-s-conf", "Conf 4", "conf"),
    ("dev-s-conf4", "Conf 6", "conf4"),
    ("dev-s-hidden", "Conf n", "hidden"),
    ("dev-s-waiting", "Waiting+1", "waiting"),
    ("dev-s-three", "3 calls", "three"),
];

/// Fake SIM set-ups for the desktop preview (Epic 16): `(press id, label, mode)`.
const SIM_CHIPS: [(&str, &str, u8); 3] = [
    ("dev-sim-1", "1 SIM", 1),
    ("dev-sim-2", "2 SIM ask", 2),
    ("dev-sim-3", "2 SIM def", 3),
];

impl PhoneApp {
    pub(crate) fn dev_switcher(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let scene_row = div()
            .flex()
            .justify_center()
            .gap(px(4.0))
            // Not drawn in tests: the allocation budgets count the app, not its design aids.
            .children(
                (!cfg!(test))
                    .then_some(SCENE_CHIPS)
                    .into_iter()
                    .flatten()
                    .map(|(id, label, _)| {
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
                    }),
            );
        let sim_row = div().flex().justify_center().gap(px(4.0)).children(
            (!cfg!(test))
                .then_some(SIM_CHIPS)
                .into_iter()
                .flatten()
                .map(|(id, label, mode)| {
                    let on = self.backend.read(cx).preview_sims_mode() == mode;
                    div()
                        .id(id)
                        .role(Role::Button)
                        .aria_label(label)
                        .h(px(20.0))
                        .px(px(7.0))
                        .rounded_full()
                        .bg(if on {
                            colors.accent
                        } else {
                            gpui::Hsla {
                                a: 0.8,
                                ..colors.fill
                            }
                        })
                        .flex()
                        .items_center()
                        .text_size(px(10.5))
                        .text_color(if on {
                            gpui::white()
                        } else {
                            colors.secondary_label
                        })
                        .child(label)
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(move |this, _, _, cx| this.dev_action(id, cx)),
                        )
                }),
        );
        div()
            .absolute()
            .top(px(8.0))
            .left_0()
            .right_0()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(4.0))
            .child(scene_row)
            .child(sim_row)
            .child(
                div()
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
                    })),
            )
            .into_any_element()
    }

    /// Desktop preview: plays a script of presses (ROVSTI_PREVIEW_KEYS), one
    /// step every ROVSTI_PREVIEW_KEYS_MS (default 350) after
    /// ROVSTI_PREVIEW_KEYS_AT ms (default 2500). Digits, `*` and `#` press keys,
    /// `c` Call, `d` Delete, `a` Answer, `x` Decline, `e` End, `f` the first
    /// Favorites tile, `r` the first Recents row, `t<n>` selects tab n and
    /// `.` waits one step, `m` Mute, `h` Hold, `w` Waiting, `k` a call, `n` a new missed call,
    /// `s` the open Contact Sheet's star, `z` replays Setup complete (S14),
    /// `u` a call with the fake route list, `b`/`v` a Bluetooth device connecting or leaving,
    /// `o` the route popover, `y`/`g` its second/first row, `l` a tap on the Audio control. It goes through the same code as a tap.
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
            'u' => self.dev_action("dev-routes", cx),
            'b' => self.dev_action("dev-btadd", cx),
            'v' => self.dev_action("dev-btrm", cx),
            // The route popover: `o` opens it, `y` picks its second row, `g` the first,
            // `l` taps the Audio control (toggle or open), `q` closes it.
            'o' => self.route_open(window, cx),
            'y' => self.preview_route_pick(1, window, cx),
            'g' => self.preview_route_pick(0, window, cx),
            'l' => self.preview_audio_tap(window, cx),
            // Multi-call scenes: `S` one call, `T` two calls, `N` two calls without merge,
            // `C` a 4-person conference, `G` a 6-person one, `U` a conference with hidden
            // participants, `W` a call with a waiting caller, `R` three calls.
            'S' => self.dev_scene("single", cx),
            'T' => self.dev_scene("two", cx),
            'N' => self.dev_scene("nomerge", cx),
            'C' => self.dev_scene("conf", cx),
            'G' => self.dev_scene("conf4", cx),
            'U' => self.dev_scene("hidden", cx),
            'W' => self.dev_scene("waiting", cx),
            'R' => self.dev_scene("three", cx),
            // Taps: `A` Add call, `X` Hold or Swap, `M` Merge, `H` the Held card, `B`/`D`/`E`/`F`
            // the Waiting card's pills (Hold and answer, End and answer, Decline, Message),
            // `P`/`L` Split/End on the first conference row, `J` the Add call sheet's next
            // segment, `K` its first Favorites tile.
            'A' => self.incall_tap("ix-add", window, cx),
            'X' => self.incall_tap("ix-hold", window, cx),
            'M' => self.incall_tap("ix-merge", window, cx),
            'H' => self.incall_tap("ix-held", window, cx),
            'B' => self.incall_tap("iw-hold", window, cx),
            'D' => self.incall_tap("iw-end", window, cx),
            'E' => self.incall_tap("iw-decline", window, cx),
            'F' => self.incall_tap("iw-msg", window, cx),
            'P' => self.conference_act(0, 0, window, cx),
            'L' => self.conference_act(0, 1, window, cx),
            'J' => self.preview_add_segment(window, cx),
            'K' => self.preview_add_pick(window, cx),
            'q' => self.route_close(window, cx),
            // `i` opens the Diagnostics page, `j` runs its Audio probe, `p` copies the report.
            'i' => {
                self.sheet_kind = crate::SheetKind::Diagnostics;
                self.sheet.open();
            }
            'j' => self.diag_run("audio", cx),
            'p' => self.diag_copy(cx),
            's' => self.contact_sheet_act("sheet-cstar", window, cx),
            // Settings: `Q` opens the page, `Z` closes it, `I` taps the next row id of
            // ROVSTI_PREVIEW_TAPS (comma separated, e.g. `set-proximity,set-sort-1`).
            'Q' => self.settings_open(window, cx),
            'Z' => self.settings_close(window, cx),
            'I' => {
                if let Some(id) = self.settings.preview_taps.pop_front() {
                    if id.starts_with("sheet-sim") {
                        self.sim_sheet_act(&id, window, cx);
                    } else if id.starts_with("sheet-import") {
                        self.backup_sheet_act(&id, cx);
                    } else {
                        self.settings_tap(&id, window, cx);
                    }
                }
            }
            'z' => {
                let reduced = window.prefers_reduced_motion();
                self.celebrate.observe(false, reduced);
                self.celebrate.observe(true, reduced);
            }
            _ => {}
        }
        cx.notify();
    }

    fn dev_scene(&mut self, scene: &str, cx: &mut Context<Self>) {
        self.backend
            .update(cx, |backend, cx| backend.preview_scene(scene, cx));
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
            "dev-routes" => backend.preview_scenario("routes", cx),
            "dev-btadd" => backend.preview_add_bluetooth(cx),
            "dev-btrm" => backend.preview_remove_bluetooth(cx),
            sim if sim.starts_with("dev-sim-") => {
                let mode = sim["dev-sim-".len()..].parse().unwrap_or(1);
                backend.preview_set_sims(mode, cx)
            }
            scene if scene.starts_with("dev-s-") => {
                backend.preview_scene(&scene["dev-s-".len()..], cx)
            }
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
