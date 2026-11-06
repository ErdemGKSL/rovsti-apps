//! Speed dial (keypad long-press) and its Settings page.
//!
//! Holding a digit on an empty keypad field calls voicemail (1) or the
//! contact assigned to it (2..=9); see `rovsti_core::speed_dial` for what a
//! hold means. The Settings page lists the nine digits: 1 is voicemail and
//! fixed, a free digit starts a pick in the Contacts tab (the contact sheet
//! shows the number list, a tap on a number assigns it), an assigned digit
//! offers Change and Remove.
//!
//! The row model is a pure function of the stored map ([`rows`]); the GPUI
//! side only draws it and writes through `Backend::update_prefs`.

use std::collections::BTreeMap;

use gpui::{
    AnyElement, Context, HapticFeedback, Hsla, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::{
    dial,
    settings::SpeedDialEntry,
    speed_dial::{self, HoldDigit},
};
use rovsti_ui::{
    Icon,
    theme::{self, call_colors},
    tokens::{Theme, radius, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, block_ui::PillAction, settings_ui::Sub};

/// Ids of the digit rows, indexed by digit - 1. All start with the page prefix.
const ROW_IDS: [&str; 9] = [
    "set-sd-1", "set-sd-2", "set-sd-3", "set-sd-4", "set-sd-5", "set-sd-6", "set-sd-7", "set-sd-8",
    "set-sd-9",
];
/// The digit labels, indexed by digit - 1 (static, so a frame formats nothing).
const DIGITS: [&str; 9] = ["1", "2", "3", "4", "5", "6", "7", "8", "9"];
const CHANGE_ID: &str = "set-sd-change";
const REMOVE_ID: &str = "set-sd-remove";

/// What one digit does when held.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Slot {
    /// Digit 1: always voicemail.
    Voicemail,
    Assigned {
        name: String,
        number: String,
    },
    Free,
}

/// One line of the Speed dial page.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Row {
    pub digit: u8,
    pub slot: Slot,
}

impl Row {
    pub fn id(&self) -> &'static str {
        ROW_IDS[usize::from(self.digit - 1)]
    }

    /// The person's name, or the number when the entry has no name.
    pub fn title(&self) -> String {
        match &self.slot {
            Slot::Voicemail => "Voicemail".to_owned(),
            Slot::Assigned { name, number } if name.is_empty() => number.clone(),
            Slot::Assigned { name, .. } => name.clone(),
            Slot::Free => "Not set".to_owned(),
        }
    }

    pub fn subtitle(&self) -> Option<String> {
        match &self.slot {
            Slot::Voicemail => Some("Fixed".to_owned()),
            Slot::Assigned { name, number } if !name.is_empty() => Some(number.clone()),
            Slot::Assigned { .. } | Slot::Free => None,
        }
    }

    /// Voicemail cannot be changed.
    pub fn tappable(&self) -> bool {
        self.slot != Slot::Voicemail
    }

    pub fn aria(&self) -> String {
        let head = format!("{}, {}", self.digit, self.title());
        match (&self.slot, self.subtitle()) {
            (Slot::Voicemail, _) => format!("{head}, fixed"),
            (Slot::Assigned { .. }, Some(number)) => format!("{head}, {number}"),
            (Slot::Assigned { .. }, None) => head,
            (Slot::Free, _) => format!("{head}, choose a contact"),
        }
    }
}

/// The nine rows, digit 1 first, from the stored assignments.
pub(crate) fn rows(map: &BTreeMap<u8, SpeedDialEntry>) -> Vec<Row> {
    let mut out = vec![Row {
        digit: 1,
        slot: Slot::Voicemail,
    }];
    out.extend(rovsti_core::settings::SPEED_DIAL_DIGITS.map(|digit| Row {
        digit,
        slot: match map.get(&digit) {
            Some(e) if !e.number_raw.is_empty() => Slot::Assigned {
                name: e.name.trim().to_owned(),
                number: e.number_raw.clone(),
            },
            _ => Slot::Free,
        },
    }));
    out
}

/// The digit a row id (`set-sd-N`) stands for.
pub(crate) fn digit_of(id: &str) -> Option<u8> {
    ROW_IDS
        .iter()
        .position(|r| *r == id)
        .and_then(|i| u8::try_from(i + 1).ok())
}

/// The page's own state.
#[derive(Default)]
pub(crate) struct SpeedDialUi {
    /// The assigned digit whose Change / Remove buttons are showing.
    menu: Option<u8>,
    /// A digit waiting for a contact's number from the Contacts tab.
    pub pick: Option<u8>,
}

impl PhoneApp {
    pub(crate) fn speed_dial_closed(&mut self) {
        self.speed_dial.menu = None;
    }

    /// A digit key was held on an empty field (500 ms).
    pub(crate) fn speed_dial_hold(
        &mut self,
        digit: u8,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let key = char::from(b'0' + digit);
        // More was typed meanwhile: the digit is part of a number, not a gesture.
        if self.dialed != key.to_string() {
            return;
        }
        let map = self.backend.read(cx).prefs().speed_dial.clone();
        let hold = speed_dial::on_hold(key, "", &map);
        if hold == HoldDigit::Ignore {
            return;
        }
        // The digit went into the field on finger-down; the gesture takes it back.
        dial::backspace(&mut self.dialed);
        self.status = None;
        self.delete_shown.set_target(0.0);
        rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
        match hold {
            HoldDigit::Voicemail => self.voicemail_press(window, cx),
            HoldDigit::Call { number_raw, .. } => {
                let _ = self.place_call_from(&number_raw, window, cx);
            }
            HoldDigit::Unassigned(d) => self.show_pill_action(
                format!("No speed dial on {d}"),
                "Set",
                PillAction::SpeedDial,
                cx,
            ),
            HoldDigit::Ignore => {}
        }
    }

    /// The pill's "Set" / "View": opens Settings on this page.
    pub(crate) fn speed_dial_open_page(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.settings_open(window, cx);
        if self.settings.is_open() {
            self.settings_open_sub(Sub::SpeedDial, window, cx);
        }
    }

    /// A control of the page was tapped.
    pub(crate) fn speed_dial_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        let map = self.backend.read(cx).prefs().speed_dial.clone();
        match id {
            CHANGE_ID => {
                if let Some(digit) = self.speed_dial.menu {
                    self.speed_dial_pick(digit, window, cx);
                }
            }
            REMOVE_ID => {
                if let Some(digit) = self.speed_dial.menu.take() {
                    self.speed_dial_write(digit, None, cx);
                    rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                }
            }
            _ => {
                let Some(digit) = digit_of(id) else { return };
                match rows(&map).get(usize::from(digit - 1)).map(|r| &r.slot) {
                    Some(Slot::Free) => self.speed_dial_pick(digit, window, cx),
                    Some(Slot::Assigned { .. }) => {
                        self.speed_dial.menu =
                            (self.speed_dial.menu != Some(digit)).then_some(digit);
                        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                    }
                    _ => {}
                }
            }
        }
        cx.notify();
    }

    /// Leaves Settings for the Contacts tab, where the next number tapped in a
    /// contact's sheet is assigned to `digit`.
    fn speed_dial_pick(&mut self, digit: u8, window: &mut Window, cx: &mut Context<Self>) {
        self.speed_dial.menu = None;
        self.settings_close(window, cx);
        self.select_tab(crate::CONTACTS_TAB, cx);
        // After the tab change, which drops a pick that belongs to another tab.
        self.speed_dial.pick = Some(digit);
        self.show_pill_action(
            format!("Choose a contact for {digit}"),
            "Cancel",
            PillAction::CancelSpeedDialPick,
            cx,
        );
    }

    /// A number was chosen in the contact sheet while a pick is under way.
    pub(crate) fn speed_dial_assign(
        &mut self,
        name: &str,
        number_raw: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(digit) = self.speed_dial.pick.take() else {
            return;
        };
        let entry = SpeedDialEntry {
            lookup_key: String::new(),
            number_raw: number_raw.to_owned(),
            name: name.to_owned(),
        };
        if self.speed_dial_write(digit, Some(entry), cx) {
            rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
            self.show_pill_action(
                format!("Speed dial {digit}: {name}"),
                "View",
                PillAction::SpeedDial,
                cx,
            );
        } else {
            self.show_notice_pill("Couldn't set speed dial", None, false, cx);
        }
    }

    /// Assigns (`Some`) or clears (`None`) a digit. Whether it went through.
    fn speed_dial_write(
        &mut self,
        digit: u8,
        entry: Option<SpeedDialEntry>,
        cx: &mut Context<Self>,
    ) -> bool {
        let mut ok = true;
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |prefs| match entry {
                Some(entry) => ok = speed_dial::assign(&mut prefs.speed_dial, digit, entry).is_ok(),
                None => {
                    speed_dial::unassign(&mut prefs.speed_dial, digit);
                }
            });
        });
        self.sync_settings_rows(cx, false);
        cx.notify();
        ok
    }

    // ---- Rendering ------------------------------------------------------

    pub(crate) fn speed_dial_body(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let list = rows(&self.backend.read(cx).prefs().speed_dial);
        let mut children: Vec<AnyElement> = Vec::new();
        for (i, row) in list.iter().enumerate() {
            let open = self.speed_dial.menu == Some(row.digit);
            let last = i + 1 == list.len() && !open;
            children.push(self.speed_dial_row(theme, row, last, cx));
            if open {
                children.push(self.speed_dial_menu(theme, i + 1 == list.len(), cx));
            }
        }
        div()
            .id("sd-scroll")
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .pb(px(48.0))
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(
                div()
                    .mx(px(16.0))
                    .rounded(radius::GROUPED_LIST)
                    .overflow_hidden()
                    .bg(Hsla {
                        a: 0.72,
                        ..colors.secondary_background
                    })
                    .flex()
                    .flex_col()
                    .children(children),
            )
            .child(
                div()
                    .px(px(32.0))
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child("Hold a number on the keypad to call it. Speed dial only works on an empty number field."),
            )
            .into_any_element()
    }

    fn speed_dial_row(
        &self,
        theme: Theme,
        row: &Row,
        last: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = SharedString::new_static(row.id());
        let press = self.presses.value(row.id()).clamp(0.0, 1.0);
        let tappable = row.tappable();
        let free = row.slot == Slot::Free;
        let mut el = div()
            .id(id.clone())
            .role(if tappable { Role::Button } else { Role::Row })
            .aria_label(SharedString::from(row.aria()))
            .relative()
            .w_full()
            .min_h(px(58.0))
            .px(px(16.0))
            .flex()
            .items_center()
            .gap(px(14.0))
            .bg(Hsla {
                a: 0.07 * press,
                ..colors.label
            })
            .child(
                div()
                    .flex_none()
                    .w(px(24.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.accent)
                    .child(DIGITS[usize::from(row.digit - 1)]),
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
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(if free {
                                colors.secondary_label
                            } else {
                                colors.label
                            })
                            .child(row.title()),
                    )
                    .children(row.subtitle().map(|s| {
                        div()
                            .truncate()
                            .text_size(typography::CAPTION.size)
                            .text_color(colors.secondary_label)
                            .child(s)
                    })),
            )
            .children(tappable.then(|| {
                svg()
                    .path(Icon::LucideChevronRight.path())
                    .size(px(18.0))
                    .flex_none()
                    .text_color(colors.tertiary_label)
            }));
        if !last {
            el = el.border_b(px(0.33)).border_color(colors.separator);
        }
        if tappable {
            el = el
                .on_mouse_down(MouseButton::Left, {
                    let id = id.clone();
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id.clone());
                        this.settings_tap(&id, window, cx);
                    })
                })
                .child(touch_surface(Self::touch_listener(cx, Some(id))));
        }
        el.into_any_element()
    }

    /// Change and Remove, under the assigned row that was tapped.
    fn speed_dial_menu(&self, theme: Theme, last: bool, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let button = |id: &'static str, label: &'static str, ink: Hsla, cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .flex_1()
                .h(px(40.0))
                .rounded_full()
                .bg(Hsla {
                    a: 0.7 + 0.2 * press,
                    ..colors.fill
                })
                .flex()
                .items_center()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(ink)
                .child(label)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        this.settings_tap(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
        };
        let mut el = div()
            .w_full()
            .px(px(16.0))
            .pb(px(12.0))
            .flex()
            .gap(px(10.0))
            .child(button(CHANGE_ID, "Change", colors.label, cx))
            .child(button(REMOVE_ID, "Remove", call_colors(theme).danger, cx));
        if !last {
            el = el.border_b(px(0.33)).border_color(colors.separator);
        }
        el.into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(name: &str, number: &str) -> SpeedDialEntry {
        SpeedDialEntry {
            lookup_key: String::new(),
            number_raw: number.into(),
            name: name.into(),
        }
    }

    #[test]
    fn nine_rows_with_voicemail_first() {
        let list = rows(&BTreeMap::new());
        assert_eq!(list.len(), 9);
        assert_eq!(list[0].slot, Slot::Voicemail);
        assert_eq!(
            list.iter().map(|r| r.digit).collect::<Vec<_>>(),
            (1..=9).collect::<Vec<u8>>()
        );
        assert!(list[1..].iter().all(|r| r.slot == Slot::Free));
    }

    #[test]
    fn voicemail_is_fixed_and_not_tappable() {
        let row = &rows(&BTreeMap::new())[0];
        assert!(!row.tappable());
        assert_eq!(row.title(), "Voicemail");
        assert_eq!(row.aria(), "1, Voicemail, fixed");
    }

    #[test]
    fn assigned_digits_show_name_and_number() {
        let mut map = BTreeMap::new();
        map.insert(3, entry("Ana", "0555 111"));
        let list = rows(&map);
        let row = &list[2];
        assert_eq!(row.digit, 3);
        assert_eq!(row.title(), "Ana");
        assert_eq!(row.subtitle().as_deref(), Some("0555 111"));
        assert_eq!(row.aria(), "3, Ana, 0555 111");
        assert!(row.tappable());
        assert_eq!(list[1].slot, Slot::Free);
    }

    #[test]
    fn a_nameless_entry_shows_its_number_once() {
        let mut map = BTreeMap::new();
        map.insert(5, entry("  ", "0555"));
        let row = &rows(&map)[4];
        assert_eq!(row.title(), "0555");
        assert_eq!(row.subtitle(), None);
        assert_eq!(row.aria(), "5, 0555");
    }

    #[test]
    fn an_entry_without_a_number_counts_as_free() {
        let mut map = BTreeMap::new();
        map.insert(4, entry("Ana", ""));
        assert_eq!(rows(&map)[3].slot, Slot::Free);
        assert_eq!(rows(&map)[3].aria(), "4, Not set, choose a contact");
    }

    #[test]
    fn row_ids_carry_the_page_prefix_and_map_back_to_digits() {
        for row in rows(&BTreeMap::new()) {
            assert!(row.id().starts_with(Sub::SpeedDial.prefix()));
            assert_eq!(digit_of(row.id()), Some(row.digit));
        }
        assert!(CHANGE_ID.starts_with(Sub::SpeedDial.prefix()));
        assert!(REMOVE_ID.starts_with(Sub::SpeedDial.prefix()));
        assert_eq!(digit_of(CHANGE_ID), None);
        assert_eq!(digit_of("set-sd-0"), None);
    }
}
