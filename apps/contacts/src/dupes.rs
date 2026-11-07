//! Find duplicates: a Settings page that lists the contacts that look like
//! the same person, a review sheet with the merged preview, and the merge
//! itself (confirmed, with Undo).
//!
//! The matching and merging rules live in `rovsti_core::contact_dupes`; this
//! file holds the small pure helpers (texts, the plan, the undo cards) and
//! the thin screen code.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, prelude::FluentBuilder, px,
};
use rovsti_core::{
    avatar,
    contact_dupes::{self, DupGroup},
    contact_edit::ContactDraft,
    import_plan::number_key,
};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, large_title, list_row, section},
    sheet::Sheet,
    theme::{self, hue_pair},
    tokens::{Theme, typography},
};

use crate::{
    ContactsApp, NoticeAction, backend,
    confirm::Confirm,
    photos::photo_avatar,
    ui::{Tone, tap_row},
};

/// The line while the contacts are being read.
pub(crate) const LOOKING: &str = "Looking for duplicates…";
/// The pill after an undone merge.
pub(crate) const UNDONE: &str = "Merge undone";

/// What the page shows.
pub(crate) enum DupState {
    /// Not asked for yet, or being read for the first time.
    Loading,
    Ready {
        drafts: Vec<ContactDraft>,
        groups: Vec<DupGroup>,
    },
    Failed(String),
}

/// The page and the sheet over it.
pub(crate) struct Dupes {
    pub(crate) open: bool,
    pub(crate) state: DupState,
    /// The group (index into `Ready::groups`) the review sheet shows.
    pub(crate) review: Option<usize>,
    pub(crate) sheet: Sheet,
    /// A merge or an undo is running.
    pub(crate) busy: bool,
    /// The newest load; older answers are dropped.
    pub(crate) loads: u64,
}

impl Dupes {
    pub(crate) fn new() -> Self {
        Self {
            open: false,
            state: DupState::Loading,
            review: None,
            sheet: Sheet::new(),
            busy: false,
            loads: 0,
        }
    }
}

/// What Undo needs: the keeper as it was, and the contacts that were deleted.
pub(crate) struct MergeUndo {
    pub(crate) notice: SharedString,
    pub(crate) keeper: ContactDraft,
    pub(crate) others: Vec<ContactDraft>,
}

/// A merge before it runs.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct MergePlan {
    pub(crate) keeper: ContactDraft,
    pub(crate) others: Vec<ContactDraft>,
    pub(crate) merged: ContactDraft,
}

/// The drafts of `ids`, in that order (ids with no draft are skipped).
fn drafts_of<'a>(drafts: &'a [ContactDraft], ids: &[i64]) -> Vec<&'a ContactDraft> {
    ids.iter()
        .filter_map(|id| drafts.iter().find(|d| d.id == Some(*id)))
        .collect()
}

/// The merge of the contacts `keeper` and `others` (ids), or `None` when the
/// keeper or every other contact is gone from `drafts`.
pub(crate) fn plan_merge(
    drafts: &[ContactDraft],
    keeper: i64,
    others: &[i64],
) -> Option<MergePlan> {
    let keeper = drafts.iter().find(|d| d.id == Some(keeper))?.clone();
    let others: Vec<ContactDraft> = drafts_of(drafts, others)
        .into_iter()
        .filter(|d| d.id != keeper.id)
        .cloned()
        .collect();
    if others.is_empty() {
        return None;
    }
    let merged = contact_dupes::merge(&keeper, &others);
    Some(MergePlan {
        keeper,
        others,
        merged,
    })
}

/// The deleted contacts as new cards: ids cleared, everything else kept.
pub(crate) fn undo_cards(others: &[ContactDraft]) -> Vec<ContactDraft> {
    others
        .iter()
        .map(|d| ContactDraft {
            id: None,
            ..d.clone()
        })
        .collect()
}

/// "Merge 3 contacts into Anna?"
pub(crate) fn confirm_title(total: usize, name: &str) -> String {
    format!("Merge {total} contacts into {name}?")
}

/// "The other 2 are deleted from the phone."
pub(crate) fn confirm_body(others: usize) -> String {
    if others == 1 {
        "The other one is deleted from the phone.".to_owned()
    } else {
        format!("The other {others} are deleted from the phone.")
    }
}

/// The names of a group, once each (ignoring case and accents), joined.
pub(crate) fn names_line(drafts: &[ContactDraft], ids: &[i64]) -> String {
    let mut seen: Vec<String> = Vec::new();
    let mut names: Vec<String> = Vec::new();
    for d in drafts_of(drafts, ids) {
        let name = d.display_name();
        let key = rovsti_core::search::fold(name.trim());
        if !seen.contains(&key) {
            seen.push(key);
            names.push(name);
        }
    }
    names.join(", ")
}

/// The numbers of a group, each once (same last ten digits), at most three,
/// then "+N more".
pub(crate) fn numbers_line(drafts: &[ContactDraft], ids: &[i64]) -> String {
    let mut keys = Vec::new();
    let mut shown: Vec<&str> = Vec::new();
    for d in drafts_of(drafts, ids) {
        for n in &d.numbers {
            if let Some(key) = number_key(&n.value)
                && !keys.contains(&key)
            {
                keys.push(key);
                shown.push(n.value.trim());
            }
        }
    }
    let extra = shown.len().saturating_sub(3);
    shown.truncate(3);
    let mut line = shown.join(", ");
    if extra > 0 {
        line.push_str(&format!(" +{extra} more"));
    }
    line
}

/// The second line of a group row: the reason and the numbers.
pub(crate) fn group_subtitle(drafts: &[ContactDraft], group: &DupGroup) -> String {
    let numbers = numbers_line(drafts, &group.ids);
    if numbers.is_empty() {
        group.reason.label().to_owned()
    } else {
        format!("{} · {numbers}", group.reason.label())
    }
}

/// The lines of the merged preview below the name.
pub(crate) fn preview_lines(draft: &ContactDraft) -> Vec<String> {
    let mut lines = Vec::new();
    if !draft.org.trim().is_empty() {
        lines.push(draft.org.trim().to_owned());
    }
    lines.extend(draft.numbers.iter().map(|n| n.value.clone()));
    lines.extend(draft.emails.iter().map(|e| e.value.clone()));
    lines.extend(draft.addresses.iter().map(|a| a.value.replace('\n', ", ")));
    lines.extend(
        draft
            .events
            .iter()
            .map(|e| format!("{}: {}", e.kind.title(), e.date)),
    );
    if !draft.note.trim().is_empty() {
        lines.push(draft.note.trim().replace('\n', " "));
    }
    lines
}

impl ContactsApp {
    /// Opens or closes the page; opening reads the contacts.
    pub(crate) fn set_dupes_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.dupes.open = open;
        self.dupes.sheet.close();
        self.clear_notice();
        self.page.restart();
        if open {
            self.dupes.state = DupState::Loading;
            self.load_dupes(cx);
        }
        cx.notify();
    }

    /// Reads every contact on the background executor and runs `find`. The
    /// page keeps what it shows until the answer lands.
    pub(crate) fn load_dupes(&mut self, cx: &mut Context<Self>) {
        self.dupes.loads += 1;
        let load = self.dupes.loads;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async {
                    backend::pull_full().map(|drafts| {
                        let groups = contact_dupes::find(&drafts);
                        (drafts, groups)
                    })
                })
                .await;
            this.update(cx, |this, cx| {
                if this.dupes.loads != load {
                    return;
                }
                this.dupes.state = match result {
                    Ok((drafts, groups)) => DupState::Ready { drafts, groups },
                    Err(words) => DupState::Failed(words),
                };
                // The group the sheet showed may be gone or have moved.
                this.dupes.review = None;
                this.dupes.sheet.close();
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn close_dupes_sheet(&mut self, cx: &mut Context<Self>) {
        self.dupes.sheet.close();
        cx.notify();
    }

    fn review_group(&mut self, ix: usize, cx: &mut Context<Self>) {
        self.dupes.review = Some(ix);
        self.dupes.sheet.open();
        cx.notify();
    }

    /// "Merge" in the review sheet: asks first.
    fn ask_merge(&mut self, cx: &mut Context<Self>) {
        let DupState::Ready { drafts, groups } = &self.dupes.state else {
            return;
        };
        let Some(group) = self.dupes.review.and_then(|ix| groups.get(ix)) else {
            return;
        };
        let Some((&keeper, others)) = group.ids.split_first() else {
            return;
        };
        let Some(plan) = plan_merge(drafts, keeper, others) else {
            self.dupes.sheet.close();
            self.notice = Some("Those contacts changed. Looking again…".into());
            self.load_dupes(cx);
            cx.notify();
            return;
        };
        self.dupes.sheet.close();
        self.confirm = Some(Confirm::MergeContacts {
            keeper,
            others: plan.others.iter().filter_map(|d| d.id).collect(),
            name: plan.merged.display_name().into(),
        });
        cx.notify();
    }

    /// The owner confirmed: saves the merged card, deletes the others.
    pub(crate) fn run_merge(&mut self, keeper: i64, others: Vec<i64>, cx: &mut Context<Self>) {
        let DupState::Ready { drafts, .. } = &self.dupes.state else {
            return;
        };
        let Some(plan) = plan_merge(drafts, keeper, &others) else {
            return;
        };
        if self.dupes.busy {
            return;
        }
        self.dupes.busy = true;
        self.notice = Some("Merging…".into());
        cx.notify();
        let total = plan.others.len() + 1;
        cx.spawn(async move |this, cx| {
            let MergePlan {
                keeper,
                others,
                merged,
            } = plan;
            let (originals, ids) = (
                (keeper, others.clone()),
                others.iter().filter_map(|d| d.id).collect::<Vec<_>>(),
            );
            let result = cx
                .background_executor()
                .spawn(async move {
                    backend::save(&merged)?;
                    for id in ids {
                        backend::delete(id)?;
                    }
                    Ok::<(), String>(())
                })
                .await;
            this.update(cx, |this, cx| {
                this.dupes.busy = false;
                match result {
                    Ok(()) => {
                        let text: SharedString = contact_dupes::merged_notice(total).into();
                        this.merge_undo = Some(MergeUndo {
                            notice: text.clone(),
                            keeper: originals.0,
                            others: originals.1,
                        });
                        this.notice = Some(text);
                        this.notice_action = Some(NoticeAction::UndoMerge);
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't merge the contacts: {words}.").into());
                    }
                }
                this.reload(cx);
                this.load_dupes(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Undo on the pill: the keeper is restored and the others come back as
    /// new contacts.
    pub(crate) fn undo_merge(&mut self, cx: &mut Context<Self>) {
        let Some(undo) = self.merge_undo.take() else {
            self.notice = None;
            self.notice_action = None;
            cx.notify();
            return;
        };
        self.notice = Some("Undoing…".into());
        self.notice_action = None;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    backend::save(&undo.keeper)?;
                    backend::add_contacts(undo_cards(&undo.others))?;
                    Ok::<(), String>(())
                })
                .await;
            this.update(cx, |this, cx| {
                this.notice = Some(match result {
                    Ok(()) => UNDONE.into(),
                    Err(words) => format!("Can't undo the merge: {words}.").into(),
                });
                this.reload(cx);
                this.load_dupes(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The duplicates page, pushed over Settings.
    pub(crate) fn dupes_page(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let top = div().px(px(8.0)).pt(px(48.0)).child(
            glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                .id("dupes-back")
                .on_click(cx.listener(|this, _, _, cx| this.set_dupes_open(false, cx))),
        );
        let note = |text: SharedString| {
            div()
                .px(px(32.0))
                .py(px(16.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child(text)
        };
        let body = match &self.dupes.state {
            DupState::Loading => note(LOOKING.into()).into_any_element(),
            DupState::Failed(words) => {
                note(format!("Can't read the contacts: {words}.").into()).into_any_element()
            }
            DupState::Ready { groups, .. } if groups.is_empty() => empty_state(
                theme,
                contact_dupes::summary(groups),
                "No two contacts share a number, an email or a name.",
            )
            .into_any_element(),
            DupState::Ready { drafts, groups } => {
                let rows = groups.iter().enumerate().map(|(ix, group)| {
                    let avatars = div().flex().flex_none().children(
                        drafts_of(drafts, &group.ids)
                            .into_iter()
                            .take(3)
                            .enumerate()
                            .map(|(n, d)| {
                                let mono = avatar::monogram(&d.display_name())
                                    .map(String::from)
                                    .unwrap_or_default();
                                let tint = hue_pair(theme, avatar::hue_index(d.id)).0;
                                div()
                                    .when(n > 0, |a| a.ml(px(-12.0)))
                                    .child(photo_avatar(theme, mono, 36.0, tint, None))
                            }),
                    );
                    list_row(
                        theme,
                        Some(avatars.into_any_element()),
                        names_line(drafts, &group.ids),
                        Some(group_subtitle(drafts, group).into()),
                        Some(group.ids.len().to_string().into()),
                    )
                    .id(("dupe-group", ix))
                    .on_click(cx.listener(move |this, _, _, cx| this.review_group(ix, cx)))
                    .into_any_element()
                });
                div()
                    .child(note(contact_dupes::summary(groups).into()))
                    .child(section(theme, None, rows))
                    .into_any_element()
            }
        };
        div()
            .child(top)
            .child(large_title(theme, "Duplicates", None))
            .child(body)
            .into_any_element()
    }

    /// The review sheet: the merged preview, Merge and Not now.
    pub(crate) fn dupes_sheet_view(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.dupes
            .sheet
            .set_reduced(window.prefers_reduced_motion());
        self.dupes.sheet.set_viewport(window.viewport_size());
        let DupState::Ready { drafts, groups } = &self.dupes.state else {
            return None;
        };
        let group = groups.get(self.dupes.review?)?;
        let (keeper, others) = group.ids.split_first()?;
        let plan = plan_merge(drafts, *keeper, others)?;
        let colors = theme::colors(theme);
        let lines = preview_lines(&plan.merged);
        let line = |text: String| {
            div()
                .px(px(20.0))
                .py(px(2.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.label)
                .child(SharedString::from(text))
        };
        let body = div()
            .flex()
            .flex_col()
            .pb(px(12.0))
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(2.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(plan.merged.display_name())),
            )
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(10.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(SharedString::from(format!(
                        "{} contacts become one. {}",
                        plan.others.len() + 1,
                        group.reason.label()
                    ))),
            )
            .children(lines.into_iter().map(line))
            .child(div().h(px(10.0)))
            .child(
                tap_row(
                    theme,
                    "dupes-merge",
                    Some(Icon::LucideUsers),
                    "Merge",
                    Tone::Accent,
                )
                .on_click(cx.listener(|this, _, _, cx| this.ask_merge(cx))),
            )
            .child(
                tap_row(theme, "dupes-later", None, "Not now", Tone::Plain)
                    .on_click(cx.listener(|this, _, _, cx| this.close_dupes_sheet(cx))),
            );
        let entity = cx.entity();
        let drag = entity.clone();
        self.dupes.sheet.render(
            theme,
            window,
            "Merge duplicates",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_dupes_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.dupes.sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::contact_edit::{DraftField, FieldKind};

    use super::*;
    use crate::sample;

    fn card(id: i64, given: &str, numbers: &[&str]) -> ContactDraft {
        ContactDraft {
            id: Some(id),
            given: given.into(),
            numbers: numbers
                .iter()
                .map(|n| DraftField::new(*n, FieldKind::Mobile))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn undo_cards_clear_ids_and_keep_the_rest() {
        let mut a = card(7, "Anna", &["+905321234567"]);
        a.starred = true;
        a.note = "hi".into();
        let cards = undo_cards(&[a.clone(), card(8, "Ann", &[])]);
        assert_eq!(cards.len(), 2);
        assert_eq!(cards[0].id, None);
        assert_eq!(cards[1].id, None);
        assert_eq!(cards[0], ContactDraft { id: None, ..a });
    }

    #[test]
    fn the_confirm_texts_count_the_contacts() {
        assert_eq!(confirm_title(3, "Anna"), "Merge 3 contacts into Anna?");
        assert_eq!(confirm_body(2), "The other 2 are deleted from the phone.");
        assert_eq!(confirm_body(1), "The other one is deleted from the phone.");
    }

    #[test]
    fn a_plan_keeps_the_keeper_and_lists_the_others() {
        let drafts = vec![
            card(1, "Anna", &["+905321234567"]),
            card(2, "Ann", &["0532 123 45 67", "+441234567890"]),
            card(3, "Bob", &[]),
        ];
        let plan = plan_merge(&drafts, 1, &[2]).expect("plan");
        assert_eq!(plan.keeper.id, Some(1));
        assert_eq!(plan.others.len(), 1);
        assert_eq!(plan.merged.id, Some(1));
        assert_eq!(plan.merged.numbers.len(), 2);
        // A vanished keeper, or nobody left to merge, is no plan.
        assert!(plan_merge(&drafts, 9, &[2]).is_none());
        assert!(plan_merge(&drafts, 1, &[9]).is_none());
        assert!(plan_merge(&drafts, 1, &[1]).is_none());
    }

    #[test]
    fn group_lines_say_each_name_and_number_once() {
        let drafts = vec![
            card(1, "Anna", &["+905321234567"]),
            card(2, "anna", &["0532 123 45 67", "+441234567890"]),
        ];
        assert_eq!(names_line(&drafts, &[1, 2]), "Anna");
        assert_eq!(
            numbers_line(&drafts, &[1, 2]),
            "+905321234567, +441234567890"
        );
        let group = DupGroup {
            ids: vec![1, 2],
            reason: contact_dupes::DupReason::SameNumber,
        };
        assert_eq!(
            group_subtitle(&drafts, &group),
            "Same number · +905321234567, +441234567890"
        );
    }

    #[test]
    fn many_numbers_are_cut_with_a_count() {
        let drafts = vec![card(
            1,
            "A",
            &[
                "+441111111111",
                "+442222222222",
                "+443333333333",
                "+444444444444",
            ],
        )];
        assert_eq!(
            numbers_line(&drafts, &[1]),
            "+441111111111, +442222222222, +443333333333 +1 more"
        );
        let group = DupGroup {
            ids: vec![1],
            reason: contact_dupes::DupReason::SameName,
        };
        assert_eq!(group_subtitle(&[card(1, "A", &[])], &group), "Same name");
    }

    #[test]
    fn the_preview_lists_the_merged_details() {
        let mut d = card(1, "Anna", &["+905321234567"]);
        d.org = "Acme".into();
        d.note = "a\nb".into();
        assert_eq!(preview_lines(&d), vec!["Acme", "+905321234567", "a b"]);
    }

    #[test]
    fn the_sample_group_makes_a_plan() {
        let drafts: Vec<_> = sample::contacts()
            .iter()
            .filter_map(|c| sample::draft(c.id))
            .collect();
        let groups = contact_dupes::find(&drafts);
        assert_eq!(groups.len(), 1);
        let (keeper, others) = groups[0].ids.split_first().expect("ids");
        assert!(plan_merge(&drafts, *keeper, others).is_some());
    }
}
