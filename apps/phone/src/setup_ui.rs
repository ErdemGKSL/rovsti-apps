//! The Setup button in the page header and the glass sheet it opens.
//!
//! The checklist comes from `rovsti_core::setup` through
//! `Backend::setup_checklist`; this file only lays it out and maps each row's
//! buttons to Android actions. The sheet is an overlay, so opening it never
//! moves the page underneath.

use std::{cell::RefCell, collections::HashMap};

use gpui::{
    AnyElement, App, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, Window, div, prelude::*, px, relative, svg,
};
use rovsti_core::setup::{ItemState, ItemStatus, SetupChecklist, SetupItem, can_confirm};
use rovsti_ui::{
    Icon,
    components::glass_icon_button_scaled,
    motion::press_scale,
    motion_tokens::scale,
    tokens::{Theme, mix, radius, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp,
    backend::{self, Access, Group, Outcome, XiaomiSetting},
};

/// What a row button does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Act {
    /// Opens this app's App info page (restricted settings, runtime permissions).
    AppInfo,
    RequestRole,
    DefaultApps,
    Permission(Group),
    FullScreen,
    Xiaomi(XiaomiSetting),
    BatteryDialog,
    Confirm,
    /// Takes back a hand confirmation of a Xiaomi row.
    Unconfirm,
}

struct Button {
    label: &'static str,
    act: Act,
    primary: bool,
}

const fn primary(label: &'static str, act: Act) -> Button {
    Button {
        label,
        act,
        primary: true,
    }
}

const fn secondary(label: &'static str, act: Act) -> Button {
    Button {
        label,
        act,
        primary: false,
    }
}

/// Buttons on a done row: only a Xiaomi row the owner confirmed by hand can be
/// taken back (Autostart is read from the system, so there is nothing to undo).
fn done_buttons(item: SetupItem, confirmed_by_hand: bool) -> Vec<Button> {
    if item.is_xiaomi() && item != SetupItem::Autostart && confirmed_by_hand {
        vec![secondary("Mark as not done", Act::Unconfirm)]
    } else {
        Vec::new()
    }
}

const RESTRICTED_STEPS: &str = "Android 15 and later blocks apps installed from a file from becoming the default phone app. \
Open App info, tap the three-dot menu, then Allow restricted settings (Kısıtlı ayarlara izin ver), and come back. \
The menu item shows up only after one blocked attempt, so tap Set as default once first if you do not see it.";

/// The line under the checklist after a button: why nothing opened. A hidden
/// role dialog offers its own "Open default apps" button, and a Xiaomi page that
/// fell back to App info says so in its steps, so those two need no line.
fn setup_note(act: Act, outcome: Outcome) -> Option<&'static str> {
    match outcome {
        Outcome::Done => None,
        Outcome::NotAvailable => Some("Not available on this platform"),
        Outcome::Declined => match act {
            Act::RequestRole | Act::Xiaomi(_) | Act::Confirm | Act::Unconfirm => None,
            _ => Some("Couldn't open settings"),
        },
    }
}

/// Which string a [`interned`] entry is.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Label {
    Button,
    RowId,
    RowAria,
}

thread_local! {
    static LABELS: RefCell<HashMap<(Label, u8, u8), SharedString>> = RefCell::new(HashMap::new());
}

/// A label that depends only on the item (and a small `extra`), made once and
/// shared afterwards: the Setup sheet is rebuilt every frame while it moves,
/// and formatting these per frame was the sheet's only steady allocation.
fn interned(
    kind: Label,
    item: SetupItem,
    extra: usize,
    make: impl FnOnce() -> String,
) -> SharedString {
    let index = SetupItem::ALL.iter().position(|i| *i == item).unwrap_or(0);
    let key = (kind, index as u8, extra as u8);
    LABELS.with(|labels| {
        labels
            .borrow_mut()
            .entry(key)
            .or_insert_with(|| make().into())
            .clone()
    })
}

impl PhoneApp {
    /// The header button: a glass shield with a dot while anything is open.
    pub(crate) fn setup_button(
        &self,
        theme: Theme,
        complete: bool,
        cx: &mut gpui::Context<Self>,
    ) -> impl IntoElement {
        let icon = if complete {
            Icon::LucideShieldCheck
        } else {
            Icon::LucideShield
        };
        div()
            .id("setup-open")
            .role(Role::Button)
            .aria_label("Setup")
            .absolute()
            .top(px(54.0))
            .right(px(12.0))
            .size(px(44.0))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.presses.down("setup-open");
                    if !this.can_open_sheet() {
                        return;
                    }
                    this.setup_note = None;
                    this.sheet_kind = crate::SheetKind::Setup;
                    this.sheet.open();
                    cx.notify();
                }),
            )
            .child(glass_icon_button_scaled(
                theme,
                icon,
                self.presses.value("setup-open"),
                !complete,
                // S14: the check pops from 0.6 when the last item turned done.
                if complete {
                    self.celebrate.check_scale()
                } else {
                    1.0
                },
            ))
            .child(touch_surface(Self::touch_listener(
                cx,
                Some("setup-open".into()),
            )))
    }

    /// Buttons for one row, derived from its state and what was opened.
    fn buttons(&self, status: ItemStatus, cx: &App) -> Vec<Button> {
        if status.state.is_done() {
            let by_hand = self.backend.read(cx).xiaomi_confirmed(status.item);
            return done_buttons(status.item, by_hand);
        }
        let permissions = self.backend.read(cx).permissions();
        let access_button = |access: Access, group| {
            let label = if access == Access::PermanentlyDenied {
                "Open settings"
            } else {
                "Allow access"
            };
            vec![primary(label, Act::Permission(group))]
        };
        let confirm_ready = can_confirm(status.item, self.opened);
        let mut buttons = match status.item {
            SetupItem::DefaultPhoneApp => {
                let mut b = vec![
                    secondary("Open App info", Act::AppInfo),
                    primary("Set as default", Act::RequestRole),
                ];
                if self.role_declined {
                    b.push(secondary("Open default apps", Act::DefaultApps));
                }
                b
            }
            SetupItem::PhoneAndCallLog => access_button(permissions.phone, Group::Phone),
            SetupItem::Contacts => access_button(permissions.contacts, Group::Contacts),
            SetupItem::Notifications => {
                access_button(permissions.notifications, Group::Notifications)
            }
            SetupItem::FullScreenNotifications => {
                vec![primary("Open settings", Act::FullScreen)]
            }
            SetupItem::Autostart => vec![primary(
                "Open settings",
                Act::Xiaomi(XiaomiSetting::Autostart),
            )],
            SetupItem::BatterySaver => vec![
                primary("Open settings", Act::Xiaomi(XiaomiSetting::BatterySaver)),
                secondary("Quick allow", Act::BatteryDialog),
            ],
            SetupItem::ShowOnLockScreen | SetupItem::BackgroundPopups => {
                vec![primary(
                    "Open settings",
                    Act::Xiaomi(XiaomiSetting::OtherPermissions),
                )]
            }
        };
        // Autostart is readable, so "denied" is final; every other Xiaomi row
        // can be confirmed by hand once its page was opened.
        let confirmable = status.item.is_xiaomi()
            && (status.state == ItemState::Unverifiable
                || (status.state == ItemState::NotDone && status.item != SetupItem::Autostart));
        if confirmable && confirm_ready {
            buttons.push(secondary("I did it", Act::Confirm));
        }
        buttons
    }

    /// Plain-language help under a row's reason.
    fn steps(&self, status: ItemStatus) -> Option<SharedString> {
        let item = status.item;
        let base = match item {
            SetupItem::DefaultPhoneApp => RESTRICTED_STEPS,
            SetupItem::ShowOnLockScreen => "In Other permissions, turn on Show on lock screen.",
            SetupItem::BackgroundPopups => {
                "In Other permissions, turn on Display pop-up windows while running in the background."
            }
            SetupItem::BatterySaver => "Choose No restrictions.",
            SetupItem::Autostart => "Switch Rovsti Phone on.",
            _ => return None,
        };
        let tail = if self.fell_back.contains(item) {
            " This phone opened App info instead; look for Other permissions, Autostart or Battery saver there."
        } else if item.is_xiaomi()
            && status.state != ItemState::NotDone
            && !can_confirm(item, self.opened)
        {
            " Open settings first, then confirm here."
        } else {
            ""
        };
        // Most rows have no tail: share the static text instead of building
        // a string every frame the sheet is open.
        Some(if tail.is_empty() {
            SharedString::new_static(base)
        } else {
            format!("{base}{tail}").into()
        })
    }

    fn run_act(&mut self, act: Act, item: SetupItem, cx: &mut gpui::Context<Self>) {
        let outcome = match act {
            Act::AppInfo => backend::open_app_details(),
            Act::RequestRole => {
                let outcome = backend::request_dialer_role();
                // Hidden or refused role dialog: offer the Settings route too.
                self.role_declined |= outcome == Outcome::Declined;
                outcome
            }
            Act::DefaultApps => backend::open_default_apps(),
            Act::Permission(group) => {
                let access = {
                    let p = self.backend.read(cx).permissions();
                    match group {
                        Group::Phone => p.phone,
                        Group::Contacts => p.contacts,
                        Group::Notifications => p.notifications,
                        // Never a Setup row: asked on demand (route popover, Stops).
                        Group::Bluetooth | Group::Location | Group::BackgroundLocation => {
                            Access::Denied
                        }
                    }
                };
                let requested = if access == Access::PermanentlyDenied {
                    Outcome::Declined
                } else {
                    backend::request_permission(group)
                };
                // No dialog possible: send the owner to the App info page.
                if requested == Outcome::Declined {
                    backend::open_app_details()
                } else {
                    requested
                }
            }
            Act::FullScreen => backend::open_full_screen_intent_settings(),
            Act::Xiaomi(setting) => {
                let outcome = backend::open_xiaomi(setting);
                self.opened.mark(item);
                if outcome == Outcome::Declined {
                    self.fell_back.mark(item);
                }
                outcome
            }
            Act::BatteryDialog => backend::request_battery_exemption(),
            Act::Confirm | Act::Unconfirm => {
                let confirmed = act == Act::Confirm;
                self.backend.update(cx, |backend, cx| {
                    backend.set_xiaomi_confirmed(item, confirmed, cx)
                });
                Outcome::Done
            }
        };
        self.setup_note = setup_note(act, outcome).map(SharedString::new_static);
        cx.notify();
    }

    fn sheet_button(
        &self,
        theme: Theme,
        item: SetupItem,
        index: usize,
        button: &Button,
        cx: &mut gpui::Context<Self>,
    ) -> impl IntoElement {
        let colors = rovsti_ui::theme::colors(theme);
        let id = interned(Label::Button, item, index, || {
            format!("sheet-{}-{index}", item.key())
        });
        let press = self.presses.value(&id);
        let k = press_scale(press, scale::PRESS);
        let (act, label) = (button.act, button.label);
        let background = if button.primary {
            mix(colors.accent, gpui::white(), 0.2 * press.clamp(0.0, 1.0))
        } else {
            mix(colors.fill, colors.label, 0.12 * press.clamp(0.0, 1.0))
        };
        // Fixed-height slot: the button shrinks inside it, neighbours stay put.
        div().h(px(40.0)).flex().items_center().child(
            div()
                .id(id.clone())
                .role(gpui::Role::Button)
                .aria_label(label)
                .relative()
                .h(px(36.0 * k))
                .px(px(16.0))
                .rounded_full()
                .bg(background)
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(15.0 * k))
                .font_weight(typography::HEADLINE.weight)
                .text_color(if button.primary {
                    gpui::white()
                } else {
                    colors.label
                })
                .child(label)
                .on_mouse_down(MouseButton::Left, {
                    let id = id.clone();
                    cx.listener(move |this, _, _, cx| {
                        this.presses.down(id.clone());
                        this.run_act(act, item, cx);
                    })
                })
                .child(touch_surface(Self::touch_listener(cx, Some(id)))),
        )
    }

    fn setup_row(
        &self,
        theme: Theme,
        status: ItemStatus,
        cx: &mut gpui::Context<Self>,
    ) -> impl IntoElement {
        let colors = rovsti_ui::theme::colors(theme);
        let done = status.state.is_done();
        let (icon, tint) = match status.state {
            ItemState::Done => (Icon::LucideCircleCheck, colors.green),
            ItemState::NotDone => (Icon::LucideCircle, colors.tertiary_label),
            ItemState::Unverifiable => (Icon::LucideCircle, colors.orange),
        };
        let steps = (!done).then(|| self.steps(status)).flatten();
        let buttons: Vec<AnyElement> = self
            .buttons(status, cx)
            .iter()
            .enumerate()
            .map(|(i, b)| {
                self.sheet_button(theme, status.item, i, b, cx)
                    .into_any_element()
            })
            .collect();
        let state_word = match status.state {
            ItemState::Done => "done",
            ItemState::NotDone => "not done",
            ItemState::Unverifiable => "needs confirmation",
        };
        div()
            .id(interned(Label::RowId, status.item, 0, || {
                format!("setup-row-{}", status.item.key())
            }))
            .flex()
            .flex_col()
            .gap(px(8.0))
            .p(px(14.0))
            .rounded(radius::CARD)
            .bg(gpui::Hsla {
                a: if done { 0.35 } else { 0.6 },
                ..colors.fill
            })
            .aria_label(interned(
                Label::RowAria,
                status.item,
                status.state as usize,
                || format!("{}, {state_word}", status.item.title()),
            ))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(12.0))
                    .child(
                        svg()
                            .path(icon.path())
                            .size(px(24.0))
                            .flex_none()
                            .text_color(tint),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .child(
                                div()
                                    .text_size(typography::HEADLINE.size)
                                    .font_weight(typography::HEADLINE.weight)
                                    .text_color(colors.label)
                                    .child(status.item.title()),
                            )
                            .child(
                                div()
                                    .text_size(typography::FOOTNOTE.size)
                                    .text_color(colors.secondary_label)
                                    .child(status.item.reason()),
                            ),
                    ),
            )
            .children(steps.map(|text| {
                div()
                    .text_size(typography::CAPTION.size)
                    .line_height(typography::CAPTION.line)
                    .text_color(colors.secondary_label)
                    .child(text)
            }))
            .when(!buttons.is_empty(), |el| {
                el.child(div().flex().flex_wrap().gap(px(8.0)).children(buttons))
            })
    }

    fn sheet_header(
        &self,
        theme: Theme,
        list: &SetupChecklist,
        cx: &mut gpui::Context<Self>,
    ) -> impl IntoElement {
        let colors = rovsti_ui::theme::colors(theme);
        let progress = list.progress();
        let fraction = if progress.total == 0 {
            1.0
        } else {
            progress.done as f32 / progress.total as f32
        };
        let (title, tint) = if progress.complete {
            (SharedString::new_static("You're all set"), colors.green)
        } else {
            let title = self
                .setup_changed_title
                .clone()
                .unwrap_or_else(|| SharedString::new_static("Finish setup"));
            (title, colors.accent)
        };
        div()
            .flex()
            .flex_col()
            .gap(px(10.0))
            .px(px(4.0))
            .pb(px(4.0))
            .child(
                div()
                    .flex()
                    .items_end()
                    .justify_between()
                    .child(
                        div()
                            .id("sheet-title")
                            .relative()
                            .flex()
                            .flex_col()
                            // Holding the title for 800 ms opens Diagnostics (story 14.0f).
                            .on_mouse_down(
                                MouseButton::Left,
                                cx.listener(|this, _, window, cx| {
                                    if !this.presses.touch_seen() {
                                        this.arm_hold(
                                            crate::diagnostics::OPEN_AFTER_MS,
                                            crate::keypad::HoldAction::Diagnostics,
                                            window,
                                            cx,
                                        );
                                    }
                                }),
                            )
                            .child(touch_surface(Self::touch_listener(
                                cx,
                                Some("sheet-title".into()),
                            )))
                            .child(
                                div()
                                    .text_size(typography::TITLE2.size)
                                    .line_height(typography::TITLE2.line)
                                    .font_weight(typography::TITLE2.weight)
                                    .text_color(colors.label)
                                    .child(title),
                            )
                            .child(
                                div()
                                    .text_size(typography::FOOTNOTE.size)
                                    .text_color(colors.secondary_label)
                                    .child("So every call rings, even when the phone is locked."),
                            ),
                    )
                    .child(
                        div()
                            .text_size(typography::SUBHEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(tint)
                            .child(SharedString::from(progress.label().as_str().to_owned())),
                    ),
            )
            .child(
                div()
                    .h(px(5.0))
                    .w_full()
                    .rounded_full()
                    .bg(colors.fill)
                    .child(div().h_full().w(relative(fraction)).rounded_full().bg(tint)),
            )
    }

    /// The Setup sheet overlay, or `None` while it is closed.
    pub(crate) fn setup_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut gpui::Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let colors = rovsti_ui::theme::colors(theme);
        let list = self.checklist(cx);
        let reveal = |index: usize, el: AnyElement| {
            let (alpha, lift) = self.sheet.rise(index);
            div()
                .relative()
                .top(px(lift))
                .when(alpha < 1.0, |d| d.opacity(alpha))
                .child(el)
        };

        let mut children = vec![reveal(
            0,
            self.sheet_header(theme, &list, cx).into_any_element(),
        )];
        for (i, status) in list.items().iter().enumerate() {
            children.push(reveal(
                i + 1,
                self.setup_row(theme, *status, cx).into_any_element(),
            ));
        }
        if let Some(note) = &self.setup_note {
            children.push(
                div()
                    .px(px(4.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(note.clone()),
            );
        }
        let body = div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .px(px(14.0))
            .pb(px(16.0))
            .children(children);

        let entity = cx.entity();
        let drag_entity = entity.clone();
        self.sheet.render(
            theme,
            window,
            "Setup",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_sheet(cx)),
            move |phase, y, _, cx| {
                drag_entity.update(cx, |this, cx| {
                    this.sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_page_that_did_not_open_says_so() {
        assert_eq!(setup_note(Act::AppInfo, Outcome::Done), None);
        assert_eq!(
            setup_note(Act::FullScreen, Outcome::Declined),
            Some("Couldn't open settings")
        );
        assert_eq!(
            setup_note(Act::BatteryDialog, Outcome::NotAvailable),
            Some("Not available on this platform")
        );
        // These two explain themselves on the sheet.
        assert_eq!(setup_note(Act::RequestRole, Outcome::Declined), None);
        assert_eq!(
            setup_note(Act::Xiaomi(XiaomiSetting::Autostart), Outcome::Declined),
            None
        );
    }

    #[test]
    fn done_rows_offer_undo_only_for_hand_confirmed_xiaomi() {
        let b = done_buttons(SetupItem::BatterySaver, true);
        assert_eq!(b.len(), 1);
        assert_eq!(b[0].label, "Mark as not done");
        assert_eq!(b[0].act, Act::Unconfirm);
        assert!(!b[0].primary);
        assert!(done_buttons(SetupItem::BatterySaver, false).is_empty());
        assert!(done_buttons(SetupItem::Contacts, false).is_empty());
        assert!(done_buttons(SetupItem::Contacts, true).is_empty());
    }
}
