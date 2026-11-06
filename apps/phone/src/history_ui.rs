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
use rovsti_core::{
    call_log::{EntryRef, blocked_ids_for_numbers, ids_for_numbers},
    call_log_query::{PruneAge, ids_older_than, prune_text},
    recents::extra_ids_for_numbers,
    wire::CallLogEntry,
};
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
        /// The Number sheet came from Blocked calls: only blocked calls go.
        blocked: bool,
    },
    /// The whole call log (opened from Settings > Data).
    All,
    /// The rows chosen in Recents select mode: their entries, row and call counts.
    Selected {
        refs: Arc<[EntryRef]>,
        rows: usize,
        calls: u32,
    },
    /// Calls that started before `cutoff_ms` (Settings > Data > Delete older calls).
    Older { age: PruneAge, cutoff_ms: i64 },
}

pub(crate) const NUMBER_SHEET_DELETE: &str = "sheet-nhist-del";
pub(crate) const CONTACT_SHEET_DELETE: &str = "sheet-chist-del";
const YES: &str = "sheet-del-yes";
const NO: &str = "sheet-del-no";

/// Above this many entries Delete all keeps no snapshot, so it has no Undo.
pub(crate) const UNDO_CAP: usize = 5000;

/// Whether `count` entries are few enough to keep an Undo snapshot.
pub(crate) fn within_undo_cap(count: usize) -> bool {
    count <= UNDO_CAP
}

/// The warning appended to a confirm body when there will be no Undo.
const NO_UNDO: &str = " This can't be undone.";

/// Title and body of the confirm sheet; `count` is how many calls go.
pub(crate) fn confirm_text(scope: &DeleteScope, count: usize) -> (String, String) {
    match scope {
        DeleteScope::Numbers {
            who, blocked: true, ..
        } => (
            format!("Delete blocked calls from {who}?"),
            format!("{count} blocked calls will be removed from this phone's call log."),
        ),
        DeleteScope::Numbers { who, .. } => (
            format!("Delete calls with {who}?"),
            format!("{count} calls will be removed from this phone's call log."),
        ),
        DeleteScope::All => {
            let mut body =
                "This removes every entry from this phone's call log. Missed-call count goes to 0."
                    .to_owned();
            if !within_undo_cap(count) {
                body.push_str(NO_UNDO);
            }
            ("Delete all call history?".to_owned(), body)
        }
        DeleteScope::Selected { rows, calls, .. } => {
            rovsti_core::call_log_query::delete_selected_text(*rows, *calls)
        }
        DeleteScope::Older { age, .. } => {
            let (title, mut body) = prune_text(*age, count);
            if !within_undo_cap(count) {
                body.push_str(NO_UNDO);
            }
            (title, body)
        }
    }
}

impl PhoneApp {
    /// Call-log ids and WhatsApp call ids `scope` covers right now.
    fn scope_ids(&self, scope: &DeleteScope, cx: &gpui::App) -> (Vec<i64>, Vec<String>) {
        if let DeleteScope::Older { cutoff_ms, .. } = scope {
            let log = match self.backend.read(cx).call_log() {
                backend::Load::Ready(log) => Some(log),
                _ => None,
            };
            let source: &[CallLogEntry] = log.as_ref().map_or(&[], |l| &l.source);
            return ids_older_than(source, &self.wa_call.read(cx).recents_extras(), *cutoff_ms);
        }
        let DeleteScope::Numbers {
            numbers, blocked, ..
        } = scope
        else {
            return (Vec::new(), Vec::new());
        };
        let numbers: Vec<&str> = numbers.iter().map(|n| n.as_ref()).collect();
        if *blocked {
            let ids = match self.backend.read(cx).blocked_calls() {
                backend::Load::Ready(log) => blocked_ids_for_numbers(&log, &numbers),
                _ => Vec::new(),
            };
            return (ids, Vec::new());
        }
        let ids = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => ids_for_numbers(&log.source, &numbers),
            _ => Vec::new(),
        };
        let wa = extra_ids_for_numbers(&self.wa_call.read(cx).recents_extras(), &numbers);
        (ids, wa)
    }

    /// Entries a delete-all would remove: the call log plus WhatsApp records.
    fn all_calls_count(&self, cx: &gpui::App) -> usize {
        let log = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => log.source.len(),
            _ => 0,
        };
        log + self.wa_call.read(cx).all_records().len()
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
        match scope {
            Some(scope @ DeleteScope::Numbers { .. }) => {
                let (ids, wa_ids) = self.scope_ids(&scope, cx);
                if matches!(scope, DeleteScope::Numbers { blocked: true, .. }) {
                    let refs: Vec<EntryRef> = ids.into_iter().map(EntryRef::Log).collect();
                    self.delete_blocked_refs(&refs, window, cx);
                } else {
                    self.delete_call_ids(ids, wa_ids, window, cx);
                }
            }
            Some(scope @ DeleteScope::Older { .. }) => {
                let (ids, wa_ids) = self.scope_ids(&scope, cx);
                self.delete_call_ids(ids, wa_ids, window, cx);
            }
            Some(DeleteScope::All) => self.delete_all_history(window, cx),
            Some(DeleteScope::Selected { refs, .. }) => {
                self.recents_leave_select(cx);
                self.delete_row_calls(refs, window, cx);
            }
            None => {}
        }
    }

    /// Settings > Data > Delete all call history, after the confirm sheet.
    fn delete_all_history(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        let entries: Vec<CallLogEntry> = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => log.source.to_vec(),
            _ => Vec::new(),
        };
        let records = self.wa_call.read(cx).all_records();
        let undoable = within_undo_cap(entries.len() + records.len());
        let task = self.backend.update(cx, |b, cx| b.delete_all_calls(cx));
        cx.spawn(async move |this, cx| {
            let outcome = task.await;
            this.update(cx, |this, cx| {
                match outcome {
                    backend::WriteOutcome::Done(_) => {
                        this.wa_call.update(cx, |ui, cx| {
                            ui.forget_all_calls(cx);
                        });
                        if undoable {
                            this.show_pill_action(
                                "Call history deleted",
                                "Undo",
                                crate::block_ui::PillAction::RestoreCalls(
                                    entries.into(),
                                    records.into(),
                                ),
                                cx,
                            );
                        } else {
                            this.show_notice_pill("Call history deleted", None, false, cx);
                        }
                    }
                    backend::WriteOutcome::Permission => this.show_pill_action(
                        "Allow call log access",
                        "Allow",
                        crate::block_ui::PillAction::GrantCallLog,
                        cx,
                    ),
                    _ => this.show_notice_pill("Not allowed on this phone", None, false, cx),
                }
                this.sync_recents(cx);
            })
            .ok();
        })
        .detach();
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
        let mut count = ids.len() + wa.len();
        match scope {
            DeleteScope::All => count = self.all_calls_count(cx),
            DeleteScope::Selected { calls, .. } => count = *calls as usize,
            DeleteScope::Numbers { .. } | DeleteScope::Older { .. } => {}
        }
        let (title, text) = confirm_text(scope, count);
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
                    blocked: self.recents.number_blocked,
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
                    blocked: false,
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
            blocked: false,
        }
    }

    #[test]
    fn confirm_text_names_the_person_and_count() {
        let (t, b) = confirm_text(&numbers("Ayşe"), 3);
        assert_eq!(t, "Delete calls with Ayşe?");
        assert_eq!(b, "3 calls will be removed from this phone's call log.");
    }

    #[test]
    fn confirm_text_blocked() {
        let scope = DeleteScope::Numbers {
            numbers: Arc::from([SharedString::from("0555")]),
            who: "Ayşe".into(),
            blocked: true,
        };
        let (t, b) = confirm_text(&scope, 2);
        assert_eq!(t, "Delete blocked calls from Ayşe?");
        assert_eq!(
            b,
            "2 blocked calls will be removed from this phone's call log."
        );
    }

    #[test]
    fn confirm_text_all() {
        let (t, b) = confirm_text(&DeleteScope::All, 99);
        assert!(!b.contains("can't be undone"));
        assert_eq!(t, "Delete all call history?");
        assert!(b.starts_with("This removes every entry"));
        assert!(b.ends_with("Missed-call count goes to 0."));
    }

    #[test]
    fn confirm_text_selected() {
        let scope = |rows, calls| DeleteScope::Selected {
            refs: Arc::from(Vec::new()),
            rows,
            calls,
        };
        assert_eq!(
            confirm_text(&scope(3, 7), 7),
            (
                "Delete 3 rows?".to_owned(),
                "7 calls will be removed from this phone's call log.".to_owned()
            )
        );
        assert_eq!(confirm_text(&scope(1, 1), 1).0, "Delete this row?");
    }

    #[test]
    fn confirm_text_older() {
        let scope = DeleteScope::Older {
            age: PruneAge::Days90,
            cutoff_ms: 0,
        };
        assert_eq!(
            confirm_text(&scope, 12),
            (
                "Delete calls older than 90 days?".to_owned(),
                "12 calls will be removed from this phone's call log.".to_owned()
            )
        );
        let (_, at_cap) = confirm_text(&scope, UNDO_CAP);
        assert!(!at_cap.contains("can't be undone"));
        let (_, over) = confirm_text(&scope, UNDO_CAP + 1);
        assert!(over.ends_with("This can't be undone."));
        assert!(within_undo_cap(UNDO_CAP) && !within_undo_cap(UNDO_CAP + 1));
    }

    #[test]
    fn confirm_text_all_cap() {
        let (_, at_cap) = confirm_text(&DeleteScope::All, UNDO_CAP);
        assert!(!at_cap.contains("can't be undone"));
        let (_, over) = confirm_text(&DeleteScope::All, 6000);
        assert!(over.ends_with("This can't be undone."));
        let (_, few) = confirm_text(&DeleteScope::All, 10);
        assert!(!few.contains("can't be undone"));
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
