//! Deleting call history from the Number and Contact sheets, behind a confirm sheet.
//!
//! The person sheet's "Delete call history" closes it and opens this sheet with
//! the numbers it showed. Delete removes every call with them (cellular and
//! WhatsApp) through the same path as the Recents row menu, so the pill offers Undo.

use std::sync::Arc;

use gpui::{
    AnyElement, Context, HapticFeedback, Hsla, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_core::{call_log::ids_for_numbers, recents::extra_ids_for_numbers};
use rovsti_ui::{
    motion::press_scale,
    motion_tokens::scale,
    theme::{self, call_colors},
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, SheetKind, backend};

/// What the confirm sheet will delete.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum DeleteScope {
    /// Every call with these numbers; `who` names the person or number.
    Numbers {
        numbers: Arc<[SharedString]>,
        who: SharedString,
    },
    /// The whole call log (opened from Settings > Data).
    #[allow(dead_code, reason = "Settings > Data opens it (S6)")]
    All,
}

pub(crate) const NUMBER_SHEET_DELETE: &str = "sheet-nhist-del";
pub(crate) const CONTACT_SHEET_DELETE: &str = "sheet-chist-del";
const YES: &str = "sheet-del-yes";
const NO: &str = "sheet-del-no";

/// Title and body of the confirm sheet; `count` is how many calls go.
pub(crate) fn confirm_text(scope: &DeleteScope, count: usize) -> (String, String) {
    match scope {
        DeleteScope::Numbers { who, .. } => (
            format!("Delete calls with {who}?"),
            format!("{count} calls will be removed from this phone's call log."),
        ),
        DeleteScope::All => (
            "Delete all call history?".to_owned(),
            "This removes every entry from this phone's call log. Missed-call count goes to 0."
                .to_owned(),
        ),
    }
}

impl PhoneApp {
    /// Call-log ids and WhatsApp call ids `scope` covers right now.
    fn scope_ids(&self, scope: &DeleteScope, cx: &gpui::App) -> (Vec<i64>, Vec<String>) {
        let DeleteScope::Numbers { numbers, .. } = scope else {
            return (Vec::new(), Vec::new());
        };
        let numbers: Vec<&str> = numbers.iter().map(|n| n.as_ref()).collect();
        let ids = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => ids_for_numbers(&log.source, &numbers),
            _ => Vec::new(),
        };
        let wa = extra_ids_for_numbers(&self.wa_call.read(cx).recents_extras(), &numbers);
        (ids, wa)
    }

    /// The person sheet's "Delete call history": swaps it for the confirm sheet.
    pub(crate) fn open_confirm_delete(&mut self, scope: DeleteScope, cx: &mut Context<Self>) {
        self.confirm_delete = Some(scope);
        self.sheet_kind = SheetKind::ConfirmDelete;
        self.sheet.open();
        cx.notify();
    }

    /// Cancel or Delete on the confirm sheet.
    pub(crate) fn history_sheet_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let scope = self.confirm_delete.take();
        self.close_sheet(cx);
        if id != YES {
            return;
        }
        if let Some(scope @ DeleteScope::Numbers { .. }) = scope {
            let (ids, wa_ids) = self.scope_ids(&scope, cx);
            self.delete_call_ids(ids, wa_ids, window, cx);
        }
    }

    /// The confirm sheet.
    pub(crate) fn confirm_delete_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let scope = self.confirm_delete.as_ref()?;
        let (ids, wa) = self.scope_ids(scope, cx);
        let (title, text) = confirm_text(scope, ids.len() + wa.len());
        let colors = theme::colors(theme);
        let button = |id: &'static str,
                      label: &'static str,
                      fill: Hsla,
                      ink: Hsla,
                      cx: &mut Context<Self>| {
            let press = self.presses.value(id).clamp(0.0, 1.0);
            div()
                .id(id)
                .role(Role::Button)
                .aria_label(label)
                .relative()
                .h(px(52.0 * press_scale(press, scale::PRESS)))
                .rounded_full()
                .bg(rovsti_ui::tokens::mix(fill, gpui::white(), 0.16 * press))
                .flex()
                .items_center()
                .justify_center()
                .text_size(typography::HEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(ink)
                .child(label)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id);
                        if id == YES {
                            rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
                        }
                        this.history_sheet_act(id, window, cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
        };
        let body = div()
            .flex()
            .flex_col()
            .gap(px(10.0))
            .px(px(18.0))
            .pb(px(18.0))
            .child(
                div()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(title),
            )
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .child(text),
            )
            .child(button(
                YES,
                "Delete",
                call_colors(theme).danger,
                gpui::white(),
                cx,
            ))
            .child(button(NO, "Cancel", colors.fill, colors.label, cx))
            .into_any_element();
        self.modal_sheet(theme, window, "Delete call history", body, cx)
    }

    /// Danger text button under a sheet's "Recent calls" list.
    pub(crate) fn history_delete_button(
        &self,
        theme: Theme,
        id: &'static str,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let press = self.presses.value(id).clamp(0.0, 1.0);
        let danger = call_colors(theme).danger;
        div()
            .id(id)
            .role(Role::Button)
            .aria_label("Delete call history")
            .relative()
            .h(px(44.0 * press_scale(press, scale::PRESS)))
            .flex()
            .items_center()
            .justify_center()
            .text_size(typography::BODY.size)
            .text_color(danger)
            .child("Delete call history")
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(id);
                    this.history_delete_tap(id, window, cx);
                }),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
            .into_any_element()
    }

    /// Opens the confirm sheet for the person sheet that showed the button.
    fn history_delete_tap(&mut self, id: &str, _window: &mut Window, cx: &mut Context<Self>) {
        let scope = if id == NUMBER_SHEET_DELETE {
            self.recents
                .number
                .as_ref()
                .map(|row| DeleteScope::Numbers {
                    numbers: Arc::from([row.number.clone()]),
                    who: row.title.clone(),
                })
        } else {
            self.contact_sheet
                .as_ref()
                .map(|sheet| DeleteScope::Numbers {
                    numbers: sheet
                        .contact
                        .numbers
                        .iter()
                        .map(|n| SharedString::from(n.raw.clone()))
                        .collect(),
                    who: sheet.contact.name.clone(),
                })
        };
        if let Some(scope) = scope {
            self.open_confirm_delete(scope, cx);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn numbers(who: &str) -> DeleteScope {
        DeleteScope::Numbers {
            numbers: Arc::from([SharedString::from("0555")]),
            who: who.to_owned().into(),
        }
    }

    #[test]
    fn confirm_text_names_the_person_and_count() {
        let (t, b) = confirm_text(&numbers("Ayşe"), 3);
        assert_eq!(t, "Delete calls with Ayşe?");
        assert_eq!(b, "3 calls will be removed from this phone's call log.");
    }

    #[test]
    fn confirm_text_all() {
        let (t, b) = confirm_text(&DeleteScope::All, 99);
        assert_eq!(t, "Delete all call history?");
        assert!(b.starts_with("This removes every entry"));
        assert!(b.ends_with("Missed-call count goes to 0."));
    }

    #[test]
    fn ids_route_to_their_own_handlers() {
        // The person sheets' prefix routers must not swallow the confirm buttons.
        for id in [YES, NO] {
            assert!(id.starts_with("sheet-del-"));
            assert!(!id.starts_with("sheet-c") && !id.starts_with("sheet-n"));
        }
        assert!(
            NUMBER_SHEET_DELETE.starts_with("sheet-n")
                && !NUMBER_SHEET_DELETE.starts_with("sheet-del-")
        );
        assert!(
            CONTACT_SHEET_DELETE.starts_with("sheet-c")
                && !CONTACT_SHEET_DELETE.starts_with("sheet-del-")
        );
    }
}
