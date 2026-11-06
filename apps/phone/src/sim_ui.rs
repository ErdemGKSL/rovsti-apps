//! Dual-SIM UI (stories 16.4 and 16.5): the SIM chooser sheet, the SIM chip
//! on the In-Call screen and in Recents, and S26 (the chip flight).
//!
//! Everything here is absent on a single-SIM phone: no chooser, no chip, no
//! reserved space that is drawn. The chooser opens (a) before placing when the
//! system is set to "ask every time", (b) on a 450 ms hold of a Call button
//! when a default SIM exists, and (c) when another app's account picker waits
//! for an answer (`selecting_account`). The chosen row places the call on that
//! account on finger-up.

use std::time::{Duration, Instant};

use gpui::{
    AnyElement, Bounds, Context, HapticFeedback, Hsla, InteractiveElement, IntoElement,
    MouseButton, ParentElement, Pixels, Role, SharedString, StatefulInteractiveElement, Styled,
    Window, div, px,
};
use rovsti_core::{
    dial,
    sim::{DOT_MIN_CONTRAST, SimAccount, SimInfo, argb_rgb, clamp_dot},
    wire::EmergencyList,
};
use rovsti_ui::{
    moments::Glide,
    motion::press_scale,
    motion_tokens::scale,
    theme,
    tokens::{Theme, mix, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp, SheetKind,
    fx::{A_CHIP_SLOT, A_SIM_ROW},
};

/// Hold on a Call button before the chooser opens.
pub(crate) const HOLD_MS: u64 = 450;
/// A flight that finds no chip slot within this long is dropped.
const GIVE_UP: Duration = Duration::from_millis(2500);
const ROW_H: f32 = 64.0;
const CHIP_H: f32 = 20.0;
/// Stable ids for the chooser rows (the sheet never lists more than four).
const ROW_IDS: [&str; 4] = ["sheet-sim-0", "sheet-sim-1", "sheet-sim-2", "sheet-sim-3"];
const CHANGE_ID: &str = "sheet-sim-change";

/// What a chosen SIM is for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum ChooserFor {
    /// The number typed on the Keypad (it stays in the field if cancelled).
    Dial,
    /// A number from Recents, Contacts or Favorites.
    List,
    /// Another app's account picker for this call.
    System(i32),
}

/// One row of the sheet, built once when it opens.
#[derive(Clone, Debug)]
pub(crate) struct ChooserRow {
    key: SharedString,
    title: SharedString,
    carrier: SharedString,
    color_argb: i32,
    is_default: bool,
    enabled: bool,
    /// Where the row's dot and label sit, for the chip flight.
    chip: SharedString,
}

#[derive(Clone, Debug)]
pub(crate) struct Chooser {
    pub kind: ChooserFor,
    pub number: SharedString,
    pub rows: Vec<ChooserRow>,
}

struct ChipFlight {
    start: Bounds<Pixels>,
    glide: Glide,
    waiting: bool,
    since: Instant,
    row: ChooserRow,
}

#[derive(Default)]
pub(crate) struct SimUi {
    pub chooser: Option<Chooser>,
    flight: Option<ChipFlight>,
    /// The call whose system chooser was dismissed (it is being cancelled):
    /// the chooser does not reopen for it while the snapshot catches up.
    dismissed: Option<i32>,
}

impl SimUi {
    pub fn tick(&mut self, dt: Duration) -> bool {
        let Some(f) = &mut self.flight else {
            return false;
        };
        if !f.waiting && !f.glide.tick(dt) {
            self.flight = None;
        }
        true
    }

    /// The real In-Call chip stays out of sight while the copy flies.
    pub fn hides_chip(&self) -> bool {
        self.flight.is_some()
    }
}

/// Dot colour at the chip's fill, clamped to contrast (UX: at least 3:1).
pub(crate) fn chip_dot(theme: Theme, argb: i32) -> Hsla {
    let colors = theme::colors(theme);
    let fill = mix(
        colors.background,
        Hsla {
            a: 1.0,
            ..colors.fill
        },
        colors.fill.a,
    );
    let fill = gpui::Rgba::from(fill);
    let rgb = clamp_dot(argb_rgb(argb), [fill.r, fill.g, fill.b], DOT_MIN_CONTRAST);
    gpui::Rgba {
        r: rgb[0],
        g: rgb[1],
        b: rgb[2],
        a: 1.0,
    }
    .into()
}

/// The SIM chip: a 6 px dot and the label, 20 px high.
pub(crate) fn sim_chip(theme: Theme, account: &SimAccount) -> gpui::Div {
    let colors = theme::colors(theme);
    div()
        .relative()
        .flex_none()
        .h(px(CHIP_H))
        .px(px(8.0))
        .rounded_full()
        .bg(colors.fill)
        .flex()
        .items_center()
        .gap(px(5.0))
        .child(
            div()
                .size(px(6.0))
                .rounded_full()
                .bg(chip_dot(theme, account.color_argb)),
        )
        .child(
            div()
                .text_size(typography::CAPTION2.size)
                .font_weight(typography::CAPTION2.weight)
                .text_color(colors.label)
                .child(SharedString::from(account.chip.to_string())),
        )
}

/// "Call on SIM 1, Turkcell".
pub(crate) fn chip_aria(account: &SimAccount) -> SharedString {
    if account.label.is_empty() || account.label == account.title {
        format!("Call on {}", account.title).into()
    } else {
        format!("Call on {}, {}", account.title, account.label).into()
    }
}

fn rows_for(sims: &SimInfo, only: Option<&[SharedString]>) -> Vec<ChooserRow> {
    sims.accounts()
        .iter()
        .filter(|a| only.is_none_or(|keys| keys.iter().any(|k| k.as_ref() == &*a.key)))
        .take(ROW_IDS.len())
        .map(|a| ChooserRow {
            key: a.key.to_string().into(),
            title: a.title.to_string().into(),
            carrier: a.label.to_string().into(),
            color_argb: a.color_argb,
            is_default: sims.default_key() == Some(&*a.key),
            enabled: a.enabled,
            chip: a.chip.to_string().into(),
        })
        .collect()
}

impl PhoneApp {
    /// Whether Call must open the chooser first: two SIMs, "ask every time",
    /// and not an emergency number (they never show it).
    pub(crate) fn sim_needs_choice(&self, number: &str, cx: &gpui::App) -> bool {
        self.backend.read(cx).sims().ask() && !dial::is_emergency(number, &EmergencyList::default())
    }

    /// The chooser's own buttons work over the In-Call screen (system pickers).
    pub(crate) fn sim_owns(&self, id: &str) -> bool {
        id.starts_with("sheet-sim") && self.sim.chooser.is_some()
    }

    /// Two SIMs and a default one: a hold on a call circle opens the chooser.
    pub(crate) fn sim_has_default(&self, cx: &gpui::App) -> bool {
        let sims = self.backend.read(cx).sims();
        sims.visible() && !sims.ask()
    }

    /// A 450 ms hold only does something with a default SIM and a dialable number.
    pub(crate) fn sim_long_press_ready(&self, cx: &gpui::App) -> bool {
        let sims = self.backend.read(cx).sims();
        sims.visible()
            && !sims.ask()
            && dial::validate(&self.dialed).is_ok()
            && !dial::is_emergency(&self.dialed, &EmergencyList::default())
    }

    /// A hold on a number's call circle in the Contact sheet (default SIM exists).
    pub(crate) fn sim_long_press_number(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let sims = self.backend.read(cx).sims();
        if !sims.visible() || sims.ask() {
            return;
        }
        let Some(number) = self
            .contact_sheet
            .as_ref()
            .and_then(|s| s.contact.numbers.get(index))
            .map(|n| n.raw.clone())
        else {
            return;
        };
        if dial::is_emergency(&number, &EmergencyList::default()) {
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
        self.sheet.close();
        self.open_sim_chooser(ChooserFor::List, number.into(), None, cx);
    }

    pub(crate) fn sim_long_press(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.sim_long_press_ready(cx) {
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
        let number: SharedString = self.dialed.clone().into();
        self.open_sim_chooser(ChooserFor::Dial, number, None, cx);
    }

    pub(crate) fn open_sim_chooser(
        &mut self,
        kind: ChooserFor,
        number: SharedString,
        only: Option<&[SharedString]>,
        cx: &mut Context<Self>,
    ) {
        // A sheet that is closing (the Contact sheet after a tap) gives way to the chooser.
        if self.sheet.is_open() {
            return;
        }
        let sims = self.backend.read(cx).sims();
        let rows = rows_for(&sims, only);
        if rows.len() < 2 {
            return;
        }
        self.sim.chooser = Some(Chooser { kind, number, rows });
        self.sheet_kind = SheetKind::SimChooser;
        self.sheet.open();
        cx.notify();
    }

    /// A row was tapped (finger-up): place the call on that account.
    pub(crate) fn sim_choose(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(chooser) = self.sim.chooser.clone() else {
            return;
        };
        let Some(row) = chooser.rows.get(index).cloned().filter(|r| r.enabled) else {
            return;
        };
        let key = row.key.to_string();
        // The chip starts where the row's dot and label are now.
        let start = self.marks.get(A_SIM_ROW + index.min(1));
        match chooser.kind {
            ChooserFor::Dial => self.call_press_on(Some(&key), window, cx),
            ChooserFor::List => {
                self.place_call_from_on(&chooser.number, Some(&key), window, cx);
            }
            ChooserFor::System(call_id) => {
                let outcome = self
                    .backend
                    .update(cx, |backend, _| backend.select_account(call_id, &key));
                if outcome.failed() {
                    self.show_notice_pill("Couldn't choose the SIM", None, false, cx);
                }
            }
        }
        if let Some(start) = start
            && !window.prefers_reduced_motion()
        {
            self.sim.flight = Some(ChipFlight {
                start,
                glide: Glide::default(),
                waiting: true,
                since: Instant::now(),
                row,
            });
        }
        self.sim.chooser = None;
        self.close_sheet(cx);
    }

    fn sim_change_default(&mut self, cx: &mut Context<Self>) {
        // Another app's call still waits for a SIM: its chooser stays up for the
        // return from Settings (closing it would cancel the call).
        let waiting = self
            .sim
            .chooser
            .as_ref()
            .is_some_and(|c| matches!(c.kind, ChooserFor::System(_)));
        if !waiting {
            self.sim.chooser = None;
            self.close_sheet(cx);
        }
        if let Some(text) =
            crate::backend::open_system(rovsti_android::telecom::SystemPage::ChangePhoneAccounts)
                .notice()
        {
            self.show_notice_pill(text, None, false, cx);
        }
    }

    pub(crate) fn sim_sheet_act(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        if id == CHANGE_ID {
            self.sim_change_default(cx);
        } else if let Some(i) = ROW_IDS.iter().position(|r| *r == id) {
            self.sim_choose(i, window, cx);
        }
    }

    /// Another app's account picker is waiting: open the chooser over the
    /// In-Call screen, and take it down when the call moves on.
    pub(crate) fn sync_sim_selection(&mut self, cx: &mut Context<Self>) {
        let (selecting, call_id, keys) = {
            let model = self.backend.read(cx).calls();
            let snap = model.snapshot();
            (
                snap.selecting_account,
                snap.foreground_call_id,
                snap.accounts
                    .iter()
                    .map(|a| SharedString::from(a.key.clone()))
                    .collect::<Vec<_>>(),
            )
        };
        let system_open = self
            .sim
            .chooser
            .as_ref()
            .is_some_and(|c| matches!(c.kind, ChooserFor::System(_)));
        if !selecting {
            self.sim.dismissed = None;
        }
        if selecting && !system_open {
            if let Some(id) = call_id.filter(|id| self.sim.dismissed != Some(*id)) {
                self.open_sim_chooser(
                    ChooserFor::System(id),
                    SharedString::default(),
                    Some(&keys),
                    cx,
                );
            }
        } else if !selecting && system_open {
            self.sim.chooser = None;
            self.sheet.close();
            cx.notify();
        }
    }

    /// Once per frame: the chooser was put away without a choice (scrim, drag,
    /// Back). For another app's picker that cancels the call, as Android's own
    /// picker does; a call left waiting for a SIM would have no way forward.
    /// A chooser replaced by another sheet is only forgotten, so it can reopen.
    pub(crate) fn sync_sim_dismissed(&mut self, cx: &mut Context<Self>) {
        let Some(chooser) = &self.sim.chooser else {
            return;
        };
        let replaced = self.sheet_kind != SheetKind::SimChooser;
        if !replaced && self.sheet.is_open() {
            return;
        }
        let kind = chooser.kind.clone();
        self.sim.chooser = None;
        if let (ChooserFor::System(call_id), false) = (kind, replaced) {
            self.sim.dismissed = Some(call_id);
            let outcome = self
                .backend
                .update(cx, |backend, cx| backend.disconnect(call_id, cx));
            if outcome.failed() {
                self.show_notice_pill("Couldn't cancel the call", None, false, cx);
            }
        }
        cx.notify();
    }

    // ---- S26: the chip flight ----------------------------------------------------

    /// Starts the flight once the In-Call screen has painted the chip slot.
    pub(crate) fn sim_flight_ready(&mut self) {
        let call_ready = self.incall.shown && self.incall.has_call();
        let slot_ready = self.marks.get(A_CHIP_SLOT).is_some();
        let Some(f) = &mut self.sim.flight else {
            return;
        };
        if !f.waiting {
            return;
        }
        if call_ready && slot_ready {
            f.waiting = false;
            f.glide.start(f.start);
        } else if f.since.elapsed() > GIVE_UP {
            self.sim.flight = None;
        }
    }

    /// The flying copy of the chosen row's chip, drawn over everything.
    pub(crate) fn sim_flight_layer(&self, theme: Theme) -> Option<AnyElement> {
        let f = self.sim.flight.as_ref().filter(|f| !f.waiting)?;
        let to = self.marks.get(A_CHIP_SLOT)?;
        let colors = theme::colors(theme);
        let p = f.glide.progress();
        let rect = f.glide.rect(to);
        Some(
            div()
                .absolute()
                .left(rect.origin.x)
                .top(rect.origin.y)
                .w(rect.size.width)
                .h(rect.size.height)
                .rounded_full()
                .bg(Hsla {
                    a: colors.fill.a * p,
                    ..colors.fill
                })
                .flex()
                .items_center()
                .justify_center()
                .gap(px(5.0))
                .child(
                    div()
                        .size(px(6.0 + 6.0 * (1.0 - p)))
                        .rounded_full()
                        .bg(chip_dot(theme, f.row.color_argb)),
                )
                .child(
                    div()
                        .text_size(px(17.0 + (11.0 - 17.0) * p))
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(f.row.chip.clone()),
                )
                .into_any_element(),
        )
    }

    // ---- the sheet ----------------------------------------------------------------

    fn sim_row(
        &self,
        theme: Theme,
        row: &ChooserRow,
        i: usize,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = ROW_IDS[i];
        let press = self.presses.value(id);
        let aria: SharedString = if row.enabled {
            format!(
                "{}, {}{}",
                row.title,
                row.carrier,
                if row.is_default { ", default" } else { "" }
            )
            .into()
        } else {
            format!("{}, unavailable", row.title).into()
        };
        let mut el = div()
            .id(id)
            .role(Role::Button)
            .aria_label(aria)
            .aria_disabled(!row.enabled)
            .relative()
            .h(px(ROW_H))
            .w_full()
            .px(px(16.0))
            .rounded(rovsti_ui::tokens::radius::CARD)
            .bg(mix(
                Hsla {
                    a: 0.6,
                    ..colors.fill
                },
                colors.label,
                0.10 * press.clamp(0.0, 1.0),
            ))
            .opacity(if row.enabled { 1.0 } else { 0.45 })
            .flex()
            .items_center()
            .justify_between()
            .child(
                div().relative().flex().items_center().gap(px(12.0)).child(
                    // The dot and the labels fly (S26): this cluster is the start rect.
                    div()
                        .relative()
                        .flex()
                        .items_center()
                        .gap(px(12.0))
                        .child(
                            div()
                                .size(px(12.0 * press_scale(press, scale::PRESS_CARD)))
                                .rounded_full()
                                .bg(chip_dot(theme, row.color_argb)),
                        )
                        .child(
                            div()
                                .flex()
                                .flex_col()
                                .child(
                                    div()
                                        .text_size(typography::HEADLINE.size)
                                        .font_weight(typography::HEADLINE.weight)
                                        .text_color(colors.label)
                                        .child(row.title.clone()),
                                )
                                .child(
                                    div()
                                        .text_size(typography::FOOTNOTE.size)
                                        .text_color(colors.secondary_label)
                                        .child(if row.enabled {
                                            row.carrier.clone()
                                        } else {
                                            "Unavailable".into()
                                        }),
                                ),
                        )
                        .children((i < 2).then(|| self.marks.probe(A_SIM_ROW + i))),
                ),
            )
            .children(row.is_default.then(|| {
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child("Default")
            }));
        if row.enabled {
            el = el
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.sim_sheet_act(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))));
        }
        el.into_any_element()
    }

    /// The "Call with" sheet body.
    pub(crate) fn sim_sheet_body(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let chooser = self.sim.chooser.as_ref()?;
        let colors = theme::colors(theme);
        let press = self.presses.value(CHANGE_ID);
        Some(
            div()
                .flex()
                .flex_col()
                .gap(px(8.0))
                .px(px(14.0))
                .pb(px(16.0))
                .child(
                    div()
                        .px(px(4.0))
                        .pb(px(4.0))
                        .text_size(typography::TITLE2.size)
                        .line_height(typography::TITLE2.line)
                        .font_weight(typography::TITLE2.weight)
                        .text_color(colors.label)
                        .child("Call with"),
                )
                .children(
                    chooser
                        .rows
                        .iter()
                        .enumerate()
                        .map(|(i, row)| self.sim_row(theme, row, i, cx)),
                )
                .child(
                    div()
                        .id(CHANGE_ID)
                        .role(Role::Button)
                        .aria_label("Change default SIM")
                        .relative()
                        .h(px(44.0))
                        .w_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .text_size(px(17.0 * press_scale(press, scale::PRESS)))
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.accent)
                        .child("Change default SIM")
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, _, window, cx| {
                                this.presses.down(CHANGE_ID);
                                this.sim_sheet_act(CHANGE_ID, window, cx);
                            }),
                        )
                        .child(touch_surface(Self::touch_listener(
                            cx,
                            Some(CHANGE_ID.into()),
                        ))),
                )
                .into_any_element(),
        )
    }

    /// The chooser as a sheet over whatever is on screen (tabs or the In-Call screen).
    pub(crate) fn sim_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if self.sheet_kind != SheetKind::SimChooser || !self.sheet.is_visible() {
            return None;
        }
        let body = self.sim_sheet_body(theme, cx)?;
        self.modal_sheet(theme, window, "Call with", body, cx)
    }

    /// The SIM chip of the foreground call for the In-Call chip row; invisible
    /// (but present, so the slot is measurable) while the copy flies.
    pub(crate) fn incall_sim_chip(&self, theme: Theme, cx: &gpui::App) -> Option<AnyElement> {
        let backend = self.backend.read(cx);
        let sims = backend.sims();
        let key = backend.calls().foreground()?.account_key.clone()?;
        let account = sims.find(&key)?;
        let chip = sim_chip(theme, account)
            .id("ic-sim-chip")
            .role(Role::Group)
            .aria_label(chip_aria(account))
            .opacity(if self.sim.hides_chip() { 0.0 } else { 1.0 })
            .child(self.marks.probe(A_CHIP_SLOT));
        Some(chip.into_any_element())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::wire::{PhoneAccountInfo, PhoneAccounts};

    fn two() -> SimInfo {
        let account = |k: &str, label: &str, slot: u8, enabled: bool| PhoneAccountInfo {
            key: k.into(),
            label: label.into(),
            slot_index: Some(slot),
            color_argb: 0xFF1E_88E5_u32 as i32,
            sim: true,
            enabled,
        };
        SimInfo::from_payload(&PhoneAccounts {
            accounts: vec![
                account("a#1", "Turkcell", 0, true),
                account("a#2", "Vodafone", 1, false),
            ],
            default_key: Some("a#1".into()),
            ask: false,
        })
    }

    #[test]
    fn rows_carry_default_and_availability() {
        let rows = rows_for(&two(), None);
        assert_eq!(rows.len(), 2);
        assert!(rows[0].is_default && rows[0].enabled);
        assert!(!rows[1].is_default && !rows[1].enabled);
        assert_eq!(&*rows[0].title, "SIM 1");
    }

    #[test]
    fn a_system_picker_lists_only_its_accounts() {
        let only = [SharedString::from("a#2")];
        let rows = rows_for(&two(), Some(&only));
        assert_eq!(rows.len(), 1);
        assert_eq!(&*rows[0].key, "a#2");
    }

    #[test]
    fn the_aria_text_names_the_sim_and_the_carrier() {
        let sims = two();
        let a = sims.find("a#1").expect("account");
        assert_eq!(&*chip_aria(a), "Call on SIM 1, Turkcell");
    }

    #[test]
    fn every_row_has_a_distinct_sheet_id() {
        for (i, id) in ROW_IDS.iter().enumerate() {
            assert!(ROW_IDS.iter().skip(i + 1).all(|o| o != id));
            assert!(id.starts_with("sheet-"));
        }
        assert!(CHANGE_ID.starts_with("sheet-"));
    }
}
