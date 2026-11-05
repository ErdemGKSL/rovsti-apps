//! Epic 17 on screen: the Recents row menu (17.3), the Block flow with its
//! signature moment S27, and the Notice pill's Undo and post-call "Block"
//! actions (17.3, 17.4).
//!
//! Everything acts on finger-up. The list under the menu is frozen while S27
//! plays so the row can be seen going; the list re-lays instantly afterwards.

use std::time::Duration;

use gpui::{
    AnyElement, ClipboardItem, Context, HapticFeedback, Hsla, InteractiveElement, IntoElement,
    MouseButton, ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window,
    div, px, svg,
};
use rovsti_core::blocklist::BlockRefusal;
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    motion::press_scale,
    motion_tokens::{self, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, shadow, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, backend, recents::ROW_H};

pub(crate) const MENU_W: f32 = 220.0;
pub(crate) const MENU_ROW: f32 = 48.0;
const MENU_PAD: f32 = 4.0;
const MENU_RADIUS: f32 = 14.0;
const MENU_GAP: f32 = 6.0;
/// S27: the ring-and-slash draws in this long, then the row fades.
const RING_SECS: f32 = 0.26;
const FADE_SECS: f32 = 0.12;

/// What the Notice pill's trailing button does.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum PillAction {
    /// Undo of a Block: unblock the same raw string.
    Unblock(SharedString, SharedString),
    /// Undo of an Unblock, or the post-call "Block".
    Block(SharedString, SharedString),
}

/// The number as a person reads it (grouped when it parses).
pub(crate) fn shown_number(raw: &str) -> String {
    match rovsti_core::PhoneNumber::parse(raw) {
        Ok(n) => rovsti_core::call::format_number(&n),
        Err(_) => raw.trim().to_owned(),
    }
}

/// Why Block is disabled in the menu, in the words of the spec.
pub(crate) fn refusal_note(refusal: BlockRefusal) -> &'static str {
    match refusal {
        BlockRefusal::Emergency => "Emergency numbers can't be blocked",
        BlockRefusal::NoNumber | BlockRefusal::NotANumber => "Unknown number, cannot block",
    }
}

/// One row of the menu.
struct Item {
    id: &'static str,
    icon: Icon,
    title: &'static str,
    note: Option<&'static str>,
    enabled: bool,
}

/// The row the menu was opened on, as it looked then.
#[derive(Clone, Debug)]
pub(crate) struct MenuTarget {
    pub index: usize,
    pub number: SharedString,
    pub who: SharedString,
    pub blocked: bool,
    /// Why Block is disabled (shown as the row's subtitle).
    pub block_note: Option<&'static str>,
    /// The row's edges in window coordinates when the menu opened.
    pub top: f32,
    pub bottom: f32,
}

/// What S27 asks one row to draw.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct RowFx {
    /// 0 to 1: the ring-and-slash and the avatar desaturating.
    pub ring: f32,
    /// 1 to 0: the whole row fading.
    pub alpha: f32,
}

/// Signature moment S27: block sweep over a Recents row.
#[derive(Clone, Copy, Debug)]
pub(crate) struct S27 {
    pub index: usize,
    t: f32,
    reduced: bool,
}

fn ease_out(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    1.0 - (1.0 - x).powi(3)
}

impl S27 {
    pub fn new(index: usize, reduced: bool) -> Self {
        Self {
            index,
            t: 0.0,
            reduced,
        }
    }

    pub fn fx(&self) -> RowFx {
        if self.reduced {
            // Reduced motion removes the sweep: a 120 ms row fade only.
            return RowFx {
                ring: 0.0,
                alpha: 1.0 - (self.t / FADE_SECS).clamp(0.0, 1.0),
            };
        }
        RowFx {
            ring: ease_out(self.t / RING_SECS),
            alpha: 1.0 - ((self.t - RING_SECS) / FADE_SECS).clamp(0.0, 1.0),
        }
    }

    pub fn done(&self) -> bool {
        self.t >= self.total()
    }

    fn total(&self) -> f32 {
        if self.reduced {
            FADE_SECS
        } else {
            RING_SECS + FADE_SECS
        }
    }
}

/// The menu and S27.
pub(crate) struct RowMenu {
    pub target: Option<MenuTarget>,
    closing: bool,
    appear: Spring,
    pub s27: Option<S27>,
}

impl RowMenu {
    pub fn new() -> Self {
        Self {
            target: None,
            closing: false,
            appear: Spring::new(0.0, motion_tokens::appear()).with_epsilon(0.002),
            s27: None,
        }
    }

    /// Open and not on its way out.
    pub fn is_open(&self) -> bool {
        self.target.is_some() && !self.closing
    }

    /// Anything of the menu is on screen.
    pub fn is_visible(&self) -> bool {
        self.target.is_some()
    }

    pub fn open(&mut self, target: MenuTarget) {
        self.target = Some(target);
        self.closing = false;
        self.appear.snap(0.0);
        self.appear.set_target(1.0);
    }

    pub fn close(&mut self) {
        if self.target.is_some() && !self.closing {
            self.closing = true;
            self.appear.set_target(0.0);
        }
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.appear.tick(dt);
        if self.closing && self.appear.value < 0.01 && !moving {
            self.target = None;
            self.closing = false;
        }
        if let Some(s) = &mut self.s27 {
            s.t += dt.as_secs_f32();
            moving = true;
        }
        moving || self.target.is_some()
    }

    fn progress(&self) -> f32 {
        self.appear.value.clamp(0.0, 1.0)
    }
}

impl PhoneApp {
    // ---- the Notice pill's trailing action ------------------------------------

    /// Shows the pill with a trailing button and remembers what it does.
    pub(crate) fn show_pill_action(
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

    /// The trailing button was tapped (finger-up). A pill that was replaced
    /// meanwhile owns a different token, so a stale tap does nothing.
    pub(crate) fn pill_fired(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some((token, action)) = self.pill_action.take() else {
            return;
        };
        if token != self.pill.token() {
            return;
        }
        self.pill.dismiss();
        match action {
            PillAction::Unblock(raw, who) => self.run_unblock(raw, who, false, window, cx),
            PillAction::Block(raw, who) => self.start_block(raw, who, None, window, cx),
        }
    }

    // ---- Block and Unblock ----------------------------------------------------

    /// Blocks `raw` off the UI thread, with S27 over Recents row `index` when
    /// it came from there. The pill reads "Blocked {who}" with Undo.
    pub(crate) fn start_block(
        &mut self,
        raw: SharedString,
        who: SharedString,
        index: Option<usize>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if let Some(refusal) = self.backend.read(cx).block_refusal(&raw) {
            self.show_notice_pill(refusal_note(refusal), None, false, cx);
            return;
        }
        if !self.backend.read(cx).can_block_here() {
            self.show_notice_pill("Blocking isn't available for this user", None, false, cx);
            return;
        }
        if let Some(index) = index {
            self.row_menu.s27 = Some(S27::new(index, window.prefers_reduced_motion()));
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        let task = self.backend.update(cx, |b, cx| b.block(&raw, cx));
        cx.spawn(async move |this, cx| {
            let done = task.await;
            this.update(cx, |this, cx| {
                if done {
                    this.show_pill_action(
                        format!("Blocked {who}"),
                        "Undo",
                        PillAction::Unblock(raw, who),
                        cx,
                    );
                } else {
                    this.row_menu.s27 = None;
                    this.show_notice_pill(format!("Couldn't block {who}"), None, false, cx);
                    this.sync_recents(cx);
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Unblocks `raw`. `undoable` gives the pill its own Undo (a plain
    /// Unblock); the Undo of a Block is silent.
    pub(crate) fn run_unblock(
        &mut self,
        raw: SharedString,
        who: SharedString,
        undoable: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        let task = self.backend.update(cx, |b, cx| b.unblock(&raw, cx));
        cx.spawn(async move |this, cx| {
            let done = task.await;
            this.update(cx, |this, cx| {
                if done && undoable {
                    this.show_pill_action(
                        format!("Unblocked {who}"),
                        "Undo",
                        PillAction::Block(raw, who),
                        cx,
                    );
                } else if !done {
                    this.show_notice_pill(format!("Couldn't unblock {who}"), None, false, cx);
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Story 17.4: after a call that rang and ended (missed, declined, short)
    /// the pill offers "Block". Never for an emergency or empty number, never
    /// when blocking is unavailable, already done, or the phone is locked.
    pub(crate) fn offer_block_after_call(
        &mut self,
        number: &str,
        who: &str,
        locked: bool,
        cx: &mut Context<Self>,
    ) {
        let backend = self.backend.read(cx);
        if locked
            || number.trim().is_empty()
            || !backend.can_block_here()
            || backend.block_refusal(number).is_some()
            || backend.is_blocked(number)
        {
            return;
        }
        let who: SharedString = who.to_owned().into();
        self.show_pill_action(
            format!("Call from {who}"),
            "Block",
            PillAction::Block(number.to_owned().into(), who),
            cx,
        );
    }

    // ---- the row menu ---------------------------------------------------------------

    /// Long-press on Recents row `index` (450 ms, light haptic).
    pub(crate) fn recents_menu_open(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some((number, who, top)) = self.recents_row_facts(index) else {
            return;
        };
        let backend = self.backend.read(cx);
        let blocked = backend.is_blocked(&number);
        let block_note = if blocked {
            None
        } else if let Some(refusal) = backend.block_refusal(&number) {
            Some(refusal_note(refusal))
        } else if !backend.can_block_here() {
            Some("Blocking isn't available for this user")
        } else {
            None
        };
        rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
        self.row_menu.open(MenuTarget {
            index,
            number,
            who,
            blocked,
            block_note,
            top,
            bottom: top + ROW_H,
        });
        self.presses.release_all();
        self.update_back(window);
        cx.notify();
    }

    pub(crate) fn row_menu_close(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.row_menu.close();
        self.presses.release_all();
        self.update_back(window);
        cx.notify();
    }

    /// A menu row was tapped (finger-up).
    pub(crate) fn row_menu_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        let Some(target) = self.row_menu.target.clone() else {
            return;
        };
        if !self.row_menu.is_open() {
            return;
        }
        let has_number = !target.number.trim().is_empty();
        match id {
            "rm-copy" if has_number => {
                cx.write_to_clipboard(ClipboardItem::new_string(target.number.to_string()));
                self.row_menu_close(window, cx);
                self.show_notice_pill("Number copied", None, false, cx);
            }
            "rm-msg" if has_number => {
                self.row_menu_close(window, cx);
                let outcome = backend::send_sms_to(&target.number);
                if let Some(text) = match outcome {
                    backend::Outcome::Done => None,
                    other => Some(other.why_not("Couldn't open Messages")),
                } {
                    self.show_notice_pill(text, None, false, cx);
                }
            }
            "rm-wa" if has_number && self.whatsapp.read(cx).is_connected() => {
                self.row_menu_close(window, cx);
                let number = target.number.to_string();
                self.wa_call.update(cx, |ui, cx| ui.place(&number, cx));
            }
            "rm-block" if target.block_note.is_none() => {
                self.row_menu_close(window, cx);
                if target.blocked {
                    self.run_unblock(target.number, target.who, true, window, cx);
                } else {
                    self.start_block(target.number, target.who, Some(target.index), window, cx);
                }
            }
            _ => {}
        }
        cx.notify();
    }

    /// Once per frame: when S27 is over the list is allowed to re-lay.
    pub(crate) fn sync_row_menu(&mut self, cx: &mut Context<Self>) {
        if self.row_menu.s27.is_some_and(|s| s.done()) {
            self.row_menu.s27 = None;
            self.sync_recents(cx);
        }
    }

    fn menu_item(&self, theme: Theme, item: Item, cx: &mut Context<Self>) -> AnyElement {
        let Item {
            id,
            icon,
            title,
            note,
            enabled,
        } = item;
        let colors = theme::colors(theme);
        let press = self.presses.value(id).clamp(0.0, 1.0);
        let ink = if enabled {
            colors.label
        } else {
            colors.tertiary_label
        };
        let mut row = div()
            .id(id)
            .role(Role::Button)
            .aria_label(match note {
                Some(note) => format!("{title}. {note}"),
                None => title.to_owned(),
            })
            .aria_disabled(!enabled)
            .relative()
            .h(px(MENU_ROW))
            .w_full()
            .px(px(14.0))
            .rounded(px(MENU_RADIUS - MENU_PAD))
            .bg(Hsla {
                a: 0.10 * press,
                ..colors.label
            })
            .flex()
            .items_center()
            .gap(px(12.0))
            .child(
                svg()
                    .path(icon.path())
                    .size(px(20.0 * press_scale(press, scale::PRESS_CARD)))
                    .flex_none()
                    .text_color(ink),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::BODY.size)
                            .text_color(ink)
                            .child(title),
                    )
                    .children(note.map(|note| {
                        div()
                            .truncate()
                            .text_size(typography::CAPTION2.size)
                            .text_color(colors.secondary_label)
                            .child(note)
                    })),
            );
        if enabled {
            row = row
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.row_menu_tap(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))));
        }
        row.into_any_element()
    }

    /// The menu over the dimmed page, or nothing.
    pub(crate) fn row_menu_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let target = self.row_menu.target.as_ref()?;
        let p = self.row_menu.progress();
        let colors = theme::colors(theme);
        let has_number = !target.number.trim().is_empty();
        let item = |id, icon, title, note, enabled| Item {
            id,
            icon,
            title,
            note,
            enabled,
        };
        let rows = [
            self.menu_item(
                theme,
                item("rm-copy", Icon::LucideCopy, "Copy number", None, has_number),
                cx,
            ),
            self.menu_item(
                theme,
                item(
                    "rm-msg",
                    Icon::LucideMessageCircle,
                    "Message",
                    None,
                    has_number,
                ),
                cx,
            ),
            self.menu_item(
                theme,
                item(
                    "rm-wa",
                    Icon::LucidePhone,
                    "WhatsApp call",
                    None,
                    has_number && self.whatsapp.read(cx).is_connected(),
                ),
                cx,
            ),
            self.menu_item(
                theme,
                item(
                    "rm-block",
                    Icon::LucideBan,
                    if target.blocked {
                        "Unblock"
                    } else {
                        "Block number"
                    },
                    target.block_note,
                    target.block_note.is_none(),
                ),
                cx,
            ),
        ];
        let height = rows.len() as f32 * MENU_ROW + 2.0 * MENU_PAD;
        let viewport = window.viewport_size();
        let (vw, vh) = (f32::from(viewport.width), f32::from(viewport.height));
        // Beside the row, never over it: below when there is room, else above.
        let below = target.bottom + MENU_GAP;
        let y = if below + height + 24.0 <= vh {
            below
        } else {
            (target.top - MENU_GAP - height).max(24.0)
        };
        let x = (vw - MENU_W - 16.0).max(16.0);
        let rise = (1.0 - p) * 8.0;
        let dim = Hsla {
            a: 0.15 * p * if theme.is_dark() { 1.6 } else { 1.0 },
            ..gpui::black()
        };
        Some(
            div()
                .id("row-menu")
                .absolute()
                .inset_0()
                .occlude()
                .child(
                    div()
                        .id("row-menu-scrim")
                        .absolute()
                        .inset_0()
                        .bg(dim)
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, _, window, cx| this.row_menu_close(window, cx)),
                        ),
                )
                .child(
                    glass(GlassSpec::sheet(theme))
                        .absolute()
                        .left(px(x))
                        .top(px(y + rise))
                        .w(px(MENU_W))
                        .h(px(height))
                        .p(px(MENU_PAD))
                        .rounded(px(MENU_RADIUS))
                        .shadow(shadow::glass(theme.is_dark()))
                        .opacity(p)
                        .flex()
                        .flex_col()
                        .children(rows),
                )
                .child(
                    // The pressed row keeps a hairline so the eye finds it again.
                    div()
                        .absolute()
                        .left(px(16.0))
                        .right(px(16.0))
                        .top(px(target.top))
                        .h(px(ROW_H))
                        .rounded(px(16.0))
                        .border(px(1.0))
                        .border_color(Hsla {
                            a: 0.5 * p,
                            ..colors.accent
                        }),
                )
                .into_any_element(),
        )
    }

    /// The S27 look for one row, when it is the swept one.
    pub(crate) fn row_fx(&self, index: usize) -> Option<RowFx> {
        self.row_menu
            .s27
            .filter(|s| s.index == index)
            .map(|s| s.fx())
    }

    /// The colour of the S27 ring.
    pub(crate) fn s27_ink(theme: Theme) -> Hsla {
        call_colors(theme).danger
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn s27_draws_the_ring_then_fades_the_row() {
        let mut s = S27::new(3, false);
        assert_eq!(
            s.fx(),
            RowFx {
                ring: 0.0,
                alpha: 1.0
            }
        );
        s.t = 0.13;
        let mid = s.fx();
        assert!(mid.ring > 0.5 && mid.ring < 1.0);
        assert_eq!(mid.alpha, 1.0, "the row holds while the ring draws");
        s.t = RING_SECS;
        assert!((s.fx().ring - 1.0).abs() < 1e-5);
        assert!(!s.done());
        s.t = RING_SECS + FADE_SECS * 0.5;
        assert!((s.fx().alpha - 0.5).abs() < 1e-4);
        s.t = RING_SECS + FADE_SECS;
        assert!(s.done());
    }

    #[test]
    fn reduced_motion_is_a_120_ms_fade_with_no_ring() {
        let mut s = S27::new(0, true);
        s.t = 0.06;
        let fx = s.fx();
        assert_eq!(fx.ring, 0.0);
        assert!((fx.alpha - 0.5).abs() < 1e-4);
        s.t = FADE_SECS;
        assert!(s.done());
    }

    #[test]
    fn refusals_read_as_the_spec_says() {
        assert_eq!(
            refusal_note(BlockRefusal::NoNumber),
            "Unknown number, cannot block"
        );
        assert_eq!(
            refusal_note(BlockRefusal::Emergency),
            "Emergency numbers can't be blocked"
        );
    }

    #[test]
    fn the_menu_closes_after_its_fade() {
        let mut menu = RowMenu::new();
        menu.open(MenuTarget {
            index: 1,
            number: "0555".into(),
            who: "Ayse".into(),
            blocked: false,
            block_note: None,
            top: 100.0,
            bottom: 158.0,
        });
        assert!(menu.is_open());
        for _ in 0..60 {
            menu.tick(Duration::from_millis(16));
        }
        menu.close();
        assert!(!menu.is_open() && menu.is_visible());
        for _ in 0..200 {
            menu.tick(Duration::from_millis(16));
        }
        assert!(!menu.is_visible());
    }
}
