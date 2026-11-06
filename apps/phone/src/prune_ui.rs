//! Settings > Data > "Delete older calls": pick an age, then the confirm sheet
//! (`history_ui`) and the Undo pill take over.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::call_log_query::{PruneAge, ids_older_than, prune_none_text};
use rovsti_ui::{
    Icon,
    motion::press_scale,
    motion_tokens::scale,
    theme,
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, SheetKind, backend, history_ui::DeleteScope, remind_ui::now_ms};

/// The chooser's Cancel row.
pub(crate) const PRUNE_CANCEL: &str = "sheet-pa-cancel";

/// Row id of the chooser's `index`-th age.
pub(crate) fn age_id(index: usize) -> String {
    format!("sheet-pa-{index}")
}

/// The age a chooser row id stands for.
pub(crate) fn age_index(id: &str) -> Option<PruneAge> {
    let i: usize = id.strip_prefix("sheet-pa-")?.parse().ok()?;
    PruneAge::ALL.get(i).copied()
}

/// The subtitle of an age row: what it would remove, or why it is dimmed.
pub(crate) fn age_subtitle(age: PruneAge, count: usize) -> String {
    match count {
        0 => prune_none_text(age),
        1 => "1 call".to_owned(),
        n => format!("{n} calls"),
    }
}

impl PhoneApp {
    /// Calls older than each age, in `PruneAge::ALL` order.
    fn prune_counts(&self, now: i64, cx: &Context<Self>) -> Vec<usize> {
        let log = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => Some(log),
            _ => None,
        };
        let source: &[rovsti_core::wire::CallLogEntry] = log.as_ref().map_or(&[], |l| &l.source);
        let extras = self.wa_call.read(cx).recents_extras();
        PruneAge::ALL
            .iter()
            .map(|age| {
                let (ids, wa) = ids_older_than(source, &extras, age.cutoff_ms(now));
                ids.len() + wa.len()
            })
            .collect()
    }

    /// Settings > Data > Delete older calls.
    pub(crate) fn open_prune_pick(&mut self, cx: &mut Context<Self>) {
        self.sheet_kind = SheetKind::PruneAge;
        self.sheet.open();
        self.presses.release_all();
        cx.notify();
    }

    /// A tap on a chooser row.
    pub(crate) fn prune_pick_act(&mut self, id: &str, cx: &mut Context<Self>) {
        if id == PRUNE_CANCEL {
            self.close_sheet(cx);
            return;
        }
        let Some(age) = age_index(id) else {
            return;
        };
        let now = now_ms();
        let i = PruneAge::ALL.iter().position(|a| *a == age).unwrap_or(0);
        if self.prune_counts(now, cx).get(i).copied().unwrap_or(0) == 0 {
            self.show_notice_pill(prune_none_text(age), None, false, cx);
            return;
        }
        self.open_confirm_delete(
            DeleteScope::Older {
                age,
                cutoff_ms: age.cutoff_ms(now),
            },
            cx,
        );
    }

    /// The chooser sheet.
    pub(crate) fn prune_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
        let counts = self.prune_counts(now_ms(), cx);
        let mut rows: Vec<AnyElement> = Vec::new();
        let entries = PruneAge::ALL
            .iter()
            .enumerate()
            .map(|(i, age)| {
                let count = counts.get(i).copied().unwrap_or(0);
                (
                    age_id(i),
                    age.label(),
                    Some(age_subtitle(*age, count)),
                    count,
                )
            })
            .chain([(PRUNE_CANCEL.to_owned(), "Cancel", None, 1)]);
        for (id, label, sub, count) in entries {
            let id: SharedString = id.into();
            let press = self.presses.value(&id).clamp(0.0, 1.0);
            let act = id.clone();
            let cancel = sub.is_none();
            let alpha = if count == 0 { 0.45 } else { 1.0 };
            let mut text = div().flex_1().min_w_0().flex().flex_col().child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.label)
                    .child(label),
            );
            if let Some(sub) = sub {
                text = text.child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(sub),
                );
            }
            rows.push(
                div()
                    .id(id.clone())
                    .role(Role::Button)
                    .aria_label(label)
                    .relative()
                    .w_full()
                    .min_h(px(56.0))
                    .px(px(20.0))
                    .py(px(8.0))
                    .flex()
                    .items_center()
                    .gap(px(14.0))
                    .opacity(alpha)
                    .bg(gpui::Hsla {
                        a: 0.9 * press,
                        ..colors.fill
                    })
                    .child(
                        svg()
                            .path(if cancel {
                                Icon::LucideClock.path()
                            } else {
                                Icon::LucideTrash2.path()
                            })
                            .size(px(22.0 * press_scale(press, scale::PRESS_CARD)))
                            .flex_none()
                            .text_color(colors.label),
                    )
                    .child(text)
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, _, cx| {
                            this.presses.down(act.clone());
                            this.prune_pick_act(&act, cx);
                        }),
                    )
                    .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
                    .into_any_element(),
            );
        }
        let body = div()
            .flex()
            .flex_col()
            .pb(px(16.0))
            .children(rows)
            .into_any_element();
        self.modal_sheet(theme, window, "Delete older calls", body, cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_follow_the_ages_and_stay_out_of_other_routers() {
        for (i, age) in PruneAge::ALL.iter().enumerate() {
            assert_eq!(age_id(i), format!("sheet-pa-{i}"));
            assert_eq!(age_index(&age_id(i)), Some(*age));
        }
        assert_eq!(age_index("sheet-pa-3"), None);
        assert_eq!(age_index(PRUNE_CANCEL), None);
        assert_eq!(age_index("sheet-rp-0"), None);
        for id in [age_id(0), PRUNE_CANCEL.to_owned()] {
            assert!(id.starts_with("sheet-") && !id.starts_with("sheet-c"));
            assert!(!id.starts_with("sheet-n") && !id.starts_with("sheet-del-"));
        }
    }

    #[test]
    fn subtitles_show_the_count_or_why_it_is_dimmed() {
        assert_eq!(
            age_subtitle(PruneAge::Days30, 0),
            "No calls older than 30 days"
        );
        assert_eq!(age_subtitle(PruneAge::Days30, 1), "1 call");
        assert_eq!(age_subtitle(PruneAge::Year, 12), "12 calls");
    }
}
