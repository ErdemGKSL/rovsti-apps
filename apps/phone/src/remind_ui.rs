//! "Call back later" reminders on the Recents row menu and the Number sheet:
//! pick a time, arm the alarm through `backend`, and undo or cancel with a pill.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::reminder::{self, Reminder, Slot};
use rovsti_ui::{
    Icon,
    motion::press_scale,
    motion_tokens::scale,
    theme,
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, SheetKind, backend, block_ui::PillAction};

/// Row menu: set a reminder.
pub(crate) const RM_REMIND: &str = "rm-remind";
/// Row menu: cancel the number's reminder.
pub(crate) const RM_UNREMIND: &str = "rm-unremind";
/// Number sheet: "Remind me".
pub(crate) const NUMBER_REMIND: &str = "sheet-n-remind";

/// The open time chooser.
pub(crate) struct RemindPick {
    pub number: SharedString,
    pub name: SharedString,
    pub missed: bool,
}

/// Row id of the chooser's `index`-th slot.
pub(crate) fn slot_id(index: usize) -> String {
    format!("sheet-rp-{index}")
}

/// The slot a chooser row id stands for.
pub(crate) fn slot_index(id: &str) -> Option<Slot> {
    let i: usize = id.strip_prefix("sheet-rp-")?.parse().ok()?;
    Slot::ALL.get(i).copied()
}

/// The row-menu item for `number`: its id and title. A number with a reminder
/// offers to cancel it ("Cancel reminder (20:00)"), any other to set one.
pub(crate) fn remind_item(
    list: &[Reminder],
    number: &str,
    tz_offset_s: i32,
) -> (&'static str, String) {
    match reminder::for_number(list, number) {
        Some(r) => (
            RM_UNREMIND,
            format!(
                "Cancel reminder ({})",
                reminder::clock(r.at_ms, tz_offset_s)
            ),
        ),
        None => (RM_REMIND, "Remind me".to_owned()),
    }
}

/// The caption under the Number sheet's "Remind me", when one is set.
pub(crate) fn remind_caption(list: &[Reminder], number: &str, tz_offset_s: i32) -> Option<String> {
    reminder::for_number(list, number)
        .map(|r| format!("Reminder at {}", reminder::clock(r.at_ms, tz_offset_s)))
}

/// Whether a reminder can be set for `number` (not private or empty).
pub(crate) fn can_remind(number: &str) -> bool {
    rovsti_core::PhoneNumber::parse(number.trim()).is_ok()
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

impl PhoneApp {
    /// The phone's offset from UTC, from the loaded call log (0 until then).
    pub(crate) fn tz_offset_s(&self, cx: &Context<Self>) -> i32 {
        match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => log.locale.tz_offset_s,
            _ => 0,
        }
    }

    /// Opens the time chooser for `number`.
    pub(crate) fn open_remind_pick(
        &mut self,
        number: SharedString,
        name: SharedString,
        missed: bool,
        cx: &mut Context<Self>,
    ) {
        self.remind_pick = Some(RemindPick {
            number,
            name,
            missed,
        });
        self.sheet_kind = SheetKind::RemindPick;
        self.sheet.open();
        self.presses.release_all();
        cx.notify();
    }

    /// A tap on a chooser row: arm the reminder.
    pub(crate) fn remind_pick_act(&mut self, id: &str, cx: &mut Context<Self>) {
        let Some(slot) = slot_index(id) else {
            return;
        };
        let Some(pick) = self.remind_pick.take() else {
            return;
        };
        let now = now_ms();
        let tz = self.tz_offset_s(cx);
        let name = Some(pick.name.as_ref()).filter(|n| *n != pick.number.as_ref());
        let Some(mut r) = reminder::new_reminder(&pick.number, name, slot, now, tz) else {
            self.close_sheet(cx);
            self.show_notice_pill("Can't remind for this number", None, false, cx);
            return;
        };
        r.missed = pick.missed;
        self.close_sheet(cx);
        // A second reminder for the same number replaces the first.
        let old = reminder::for_number(self.backend.read(cx).reminders(), &pick.number).cloned();
        let outcome = backend::arm_reminder(&r);
        if outcome != backend::Outcome::Done {
            self.show_notice_pill(
                outcome.why_not("Couldn't set the reminder"),
                None,
                false,
                cx,
            );
            return;
        }
        if let Some(old) = old {
            backend::disarm_reminder(old.id);
        }
        let text = reminder::set_text(&r, now, tz);
        let id = r.id;
        self.backend.update(cx, |b, cx| {
            let mut list: Vec<Reminder> = b
                .reminders()
                .iter()
                .filter(|x| x.number != pick.number)
                .cloned()
                .collect();
            list.push(r);
            b.set_reminders(list, cx);
        });
        self.show_pill_action(text, "Undo", PillAction::CancelReminder(id), cx);
    }

    /// Pill Undo of a set: disarm it again.
    pub(crate) fn remind_undo(&mut self, id: i64, cx: &mut Context<Self>) {
        self.drop_reminder(id, cx);
        self.show_notice_pill("Reminder cancelled", None, false, cx);
    }

    /// Row menu "Cancel reminder": disarm with an Undo.
    pub(crate) fn remind_cancel(&mut self, number: &str, cx: &mut Context<Self>) {
        let Some(r) = reminder::for_number(self.backend.read(cx).reminders(), number).cloned()
        else {
            return;
        };
        if backend::disarm_reminder(r.id).failed() {
            self.show_notice_pill("Couldn't cancel the reminder", None, false, cx);
            return;
        }
        self.drop_reminder(r.id, cx);
        self.show_pill_action(
            "Reminder cancelled",
            "Undo",
            PillAction::RestoreReminder(Box::new(r)),
            cx,
        );
    }

    /// Pill Undo of a cancel: arm the reminder again.
    pub(crate) fn remind_restore(&mut self, r: Reminder, cx: &mut Context<Self>) {
        let outcome = backend::arm_reminder(&r);
        if outcome != backend::Outcome::Done {
            self.show_notice_pill(
                outcome.why_not("Couldn't restore the reminder"),
                None,
                false,
                cx,
            );
            return;
        }
        self.backend.update(cx, |b, cx| {
            let mut list = b.reminders().to_vec();
            list.retain(|x| x.id != r.id);
            list.push(r);
            b.set_reminders(list, cx);
        });
        self.show_notice_pill("Reminder restored", None, false, cx);
    }

    /// Disarms reminder `id` and forgets it.
    fn drop_reminder(&mut self, id: i64, cx: &mut Context<Self>) {
        backend::disarm_reminder(id);
        self.backend.update(cx, |b, cx| {
            let list: Vec<Reminder> = b
                .reminders()
                .iter()
                .filter(|x| x.id != id)
                .cloned()
                .collect();
            b.set_reminders(list, cx);
        });
    }

    /// The chooser sheet.
    pub(crate) fn remind_pick_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        self.remind_pick.as_ref()?;
        let colors = theme::colors(theme);
        let now = now_ms();
        let tz = self.tz_offset_s(cx);
        let rows: Vec<AnyElement> = Slot::ALL
            .iter()
            .enumerate()
            .map(|(i, slot)| {
                let id: SharedString = slot_id(i).into();
                let text: SharedString = slot.label(now, tz).into();
                let press = self.presses.value(&id).clamp(0.0, 1.0);
                let act = id.clone();
                div()
                    .id(id.clone())
                    .role(Role::Button)
                    .aria_label(text.clone())
                    .relative()
                    .w_full()
                    .min_h(px(56.0))
                    .px(px(20.0))
                    .py(px(8.0))
                    .flex()
                    .items_center()
                    .gap(px(14.0))
                    .bg(gpui::Hsla {
                        a: 0.9 * press,
                        ..colors.fill
                    })
                    .child(
                        svg()
                            .path(Icon::LucideBell.path())
                            .size(px(22.0 * press_scale(press, scale::PRESS_CARD)))
                            .flex_none()
                            .text_color(colors.label),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_size(typography::SUBHEADLINE.size)
                            .text_color(colors.label)
                            .child(text),
                    )
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, _, cx| {
                            this.presses.down(act.clone());
                            this.remind_pick_act(&act, cx);
                        }),
                    )
                    .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
                    .into_any_element()
            })
            .collect();
        let body = div()
            .flex()
            .flex_col()
            .pb(px(16.0))
            .children(rows)
            .into_any_element();
        self.modal_sheet(theme, window, "Remind me", body, cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TR: i32 = 3 * 3600;

    fn reminder_at(number: &str, at_ms: i64) -> Reminder {
        Reminder {
            id: 1,
            number: number.to_owned(),
            name: None,
            at_ms,
            created_ms: 0,
            missed: false,
        }
    }

    #[test]
    fn the_menu_offers_remind_or_cancel() {
        // 2024-03-05 17:00 UTC is 20:00 at +03:00.
        let list = [reminder_at("0532 123 45 67", 1_709_658_000_000)];
        assert_eq!(
            remind_item(&list, "05321234567", TR),
            (RM_UNREMIND, "Cancel reminder (20:00)".to_owned())
        );
        assert_eq!(
            remind_item(&list, "0532 999 99 99", TR),
            (RM_REMIND, "Remind me".to_owned())
        );
        assert_eq!(remind_item(&[], "05321234567", TR).0, RM_REMIND);
    }

    #[test]
    fn the_number_sheet_captions_a_set_reminder() {
        let list = [reminder_at("05321234567", 1_709_658_000_000)];
        assert_eq!(
            remind_caption(&list, "05321234567", TR).as_deref(),
            Some("Reminder at 20:00")
        );
        assert_eq!(remind_caption(&list, "02079460958", TR), None);
    }

    #[test]
    fn slot_ids_round_trip() {
        for (i, slot) in Slot::ALL.iter().enumerate() {
            assert_eq!(slot_index(&slot_id(i)), Some(*slot));
        }
        assert_eq!(slot_index("sheet-rp-4"), None);
        assert_eq!(slot_index("sheet-qr-0"), None);
        assert!(can_remind("05321234567"));
        assert!(!can_remind(""));
    }

    #[test]
    fn ids_keep_the_router_prefixes() {
        assert!(RM_REMIND.starts_with("rm-") && RM_UNREMIND.starts_with("rm-"));
        assert!(NUMBER_REMIND.starts_with("sheet-n"));
        assert!(slot_id(0).starts_with("sheet-") && !slot_id(0).starts_with("sheet-n"));
    }

    #[test]
    fn pill_actions_round_trip() {
        let r = reminder_at("0555", 5);
        let a = PillAction::RestoreReminder(Box::new(r.clone()));
        assert_eq!(a.clone(), PillAction::RestoreReminder(Box::new(r)));
        assert_eq!(PillAction::CancelReminder(7), PillAction::CancelReminder(7));
        assert_ne!(PillAction::CancelReminder(7), PillAction::CancelReminder(8));
    }
}
