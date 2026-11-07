//! The Clean up page (Settings, "Clean up"): contacts without a number, a
//! name, a photo or a group, with select and delete.
//!
//! The filters live in `rovsti_core::contact_cleanup`. The lists are built
//! once per address-book pull (and when the page opens), never per frame.
//! Delete goes through the same path as select mode, so it ends in the Trash.

use std::collections::HashSet;

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px, svg,
};
use rovsti_core::{
    avatar,
    contact_cleanup::{self, Cleanup},
    contact_edit::ContactDraft,
    multi_select::{self, Selection},
};
use rovsti_ui::{
    Icon,
    components::{
        empty_state, flat_glass, glass_icon_button, glass_pill, large_title, list_row, section,
    },
    theme::{self, hue_pair},
    tokens::{Theme, metrics, radius, typography},
};

use crate::{ContactsApp, backend, confirm::Confirm, photos::photo_avatar};

/// At most this many rows are drawn per chip (the select cap), so a book
/// where most contacts have no photo stays light.
pub(crate) const MAX_SHOWN: usize = multi_select::MAX_SELECTED;

/// Text of a chip: the filter and how many contacts match.
pub(crate) fn cleanup_chip_text(label: &str, count: usize) -> String {
    format!("{label} \u{b7} {count}")
}

/// The footnote when a chip has more matches than rows drawn.
pub(crate) fn shown_note(total: usize) -> Option<String> {
    (total > MAX_SHOWN).then(|| format!("Showing the first {MAX_SHOWN} of {total}."))
}

/// The name a row shows: the list name, or "No name" for an empty card.
pub(crate) fn item_name(draft: &ContactDraft) -> String {
    let name = draft.display_name();
    if name.trim().is_empty() {
        "No name".to_owned()
    } else {
        name
    }
}

/// One row: the contact id and its name.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CleanItem {
    pub(crate) id: i64,
    pub(crate) name: String,
}

/// The matches of every filter, in [`Cleanup::ALL`] order.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Lists {
    pub(crate) items: [Vec<CleanItem>; 4],
}

impl Lists {
    /// Builds the lists. `hidden` contacts (waiting for their Undo window)
    /// are left out.
    pub(crate) fn build(drafts: &[ContactDraft], photo_ids: &HashSet<i64>, hidden: &[i64]) -> Self {
        let shown: Vec<ContactDraft> = drafts
            .iter()
            .filter(|d| d.id.is_none_or(|id| !hidden.contains(&id)))
            .cloned()
            .collect();
        let items = Cleanup::ALL.map(|c| {
            let ids = contact_cleanup::ids(&shown, photo_ids, c);
            ids.into_iter()
                .filter_map(|id| {
                    let draft = shown.iter().find(|d| d.id == Some(id))?;
                    Some(CleanItem {
                        id,
                        name: item_name(draft),
                    })
                })
                .collect()
        });
        Self { items }
    }

    /// Matches per filter.
    pub(crate) fn counts(&self) -> [usize; 4] {
        [0, 1, 2, 3].map(|i| self.items[i].len())
    }

    /// Drops `ids` from every list (they were just deleted).
    pub(crate) fn without(&mut self, ids: &[i64]) {
        for list in &mut self.items {
            list.retain(|item| !ids.contains(&item.id));
        }
    }
}

/// The Clean up page and its state.
#[derive(Default)]
pub(crate) struct CleanupUi {
    pub(crate) open: bool,
    /// Index into [`Cleanup::ALL`].
    chip: usize,
    lists: Lists,
    loading: bool,
    failed: Option<String>,
    loads: u64,
    pub(crate) select: Option<Selection<i64>>,
}

impl ContactsApp {
    pub(crate) fn set_cleanup_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.cleanup_ui.open = open;
        self.cleanup_ui.select = None;
        self.clear_notice();
        self.page.restart();
        if open {
            self.cleanup_ui.loading = true;
            self.load_cleanup(cx);
        }
        cx.notify();
    }

    /// Reads every contact on the background executor and builds the lists.
    /// Called when the page opens and after each pull while it is open.
    pub(crate) fn load_cleanup(&mut self, cx: &mut Context<Self>) {
        if !self.cleanup_ui.open {
            return;
        }
        self.cleanup_ui.loads += 1;
        let load = self.cleanup_ui.loads;
        let photo_ids: HashSet<i64> = self
            .index()
            .map(|index| {
                index
                    .all()
                    .filter(|c| c.photo_uri.is_some())
                    .map(|c| c.id)
                    .collect()
            })
            .unwrap_or_default();
        let hidden: Vec<i64> = self
            .pending_delete
            .as_ref()
            .map(|p| p.ids.clone())
            .unwrap_or_default();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    backend::pull_full().map(|drafts| Lists::build(&drafts, &photo_ids, &hidden))
                })
                .await;
            this.update(cx, |this, cx| {
                if this.cleanup_ui.loads != load {
                    return;
                }
                this.cleanup_ui.loading = false;
                match result {
                    Ok(lists) => {
                        this.cleanup_ui.failed = None;
                        this.cleanup_ui.lists = lists;
                        let alive: HashSet<i64> = this
                            .cleanup_ui
                            .lists
                            .items
                            .iter()
                            .flatten()
                            .map(|item| item.id)
                            .collect();
                        if let Some(select) = this.cleanup_ui.select.as_mut() {
                            select.retain(|id| alive.contains(id));
                        }
                    }
                    Err(words) => this.cleanup_ui.failed = Some(words),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Contacts were deleted: they leave the lists at once.
    pub(crate) fn cleanup_forget(&mut self, ids: &[i64]) {
        self.cleanup_ui.lists.without(ids);
        self.cleanup_ui.select = None;
    }

    pub(crate) fn cleanup_selecting(&self) -> bool {
        self.cleanup_ui.open && self.cleanup_ui.select.is_some()
    }

    pub(crate) fn leave_cleanup_select(&mut self, cx: &mut Context<Self>) {
        self.cleanup_ui.select = None;
        cx.notify();
    }

    fn pick_chip(&mut self, chip: usize, cx: &mut Context<Self>) {
        self.cleanup_ui.chip = chip;
        self.cleanup_ui.select = self.cleanup_ui.select.take().map(|_| Selection::new());
        self.page.restart();
        cx.notify();
    }

    fn shown_items(&self) -> &[CleanItem] {
        let list = &self.cleanup_ui.lists.items[self.cleanup_ui.chip.min(3)];
        &list[..list.len().min(MAX_SHOWN)]
    }

    fn select_all_cleanup(&mut self, cx: &mut Context<Self>) {
        let ids: Vec<i64> = self.shown_items().iter().map(|item| item.id).collect();
        if let Some(select) = self.cleanup_ui.select.as_mut() {
            select.select_all(ids);
        }
        cx.notify();
    }

    /// The Delete button of the action bar: asks first.
    fn ask_delete_cleanup(&mut self, cx: &mut Context<Self>) {
        let ids: Vec<i64> = self
            .cleanup_ui
            .select
            .iter()
            .flat_map(|s| s.keys().copied())
            .collect();
        if !ids.is_empty() {
            self.confirm = Some(Confirm::DeleteMany { ids });
            cx.notify();
        }
    }

    /// The page: chips with counts, then the matching contacts.
    pub(crate) fn cleanup_page(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let chip = self.cleanup_ui.chip.min(3);
        let selecting = self.cleanup_ui.select.as_ref().map(Selection::len);
        let top = if selecting.is_some() {
            div().px(px(16.0)).pt(px(48.0)).flex().child(
                glass_pill(theme, "Cancel")
                    .id("cleanup-cancel")
                    .on_click(cx.listener(|this, _, _, cx| this.leave_cleanup_select(cx))),
            )
        } else {
            div().px(px(8.0)).pt(px(48.0)).child(
                glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                    .id("cleanup-back")
                    .on_click(cx.listener(|this, _, _, cx| this.set_cleanup_open(false, cx))),
            )
        };
        let title = match selecting {
            Some(n) => multi_select::title(n),
            None => "Clean up".to_owned(),
        };
        let has_rows = !self.shown_items().is_empty();
        let trailing = has_rows.then(|| {
            if selecting.is_some() {
                glass_pill(theme, "Select all")
                    .id("cleanup-select-all")
                    .on_click(cx.listener(|this, _, _, cx| this.select_all_cleanup(cx)))
                    .into_any_element()
            } else {
                glass_pill(theme, "Select")
                    .id("cleanup-select")
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.cleanup_ui.select = Some(Selection::new());
                        cx.notify();
                    }))
                    .into_any_element()
            }
        });
        let counts = self.cleanup_ui.lists.counts();
        let mut chips = div()
            .flex()
            .flex_wrap()
            .gap(px(8.0))
            .px(px(16.0))
            .pb(px(12.0));
        for (i, c) in Cleanup::ALL.iter().enumerate() {
            let on = i == chip;
            chips = chips.child(
                glass_pill(theme, cleanup_chip_text(c.label(), counts[i]))
                    .id(("cleanup-chip", i))
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(if on { colors.accent } else { colors.label })
                    .on_click(cx.listener(move |this, _, _, cx| this.pick_chip(i, cx))),
            );
        }
        let note = |text: SharedString| {
            div()
                .px(px(32.0))
                .py(px(16.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child(text)
        };
        let total = self.cleanup_ui.lists.items[chip].len();
        let body = if self.cleanup_ui.loading {
            note("Looking through your contacts\u{2026}".into()).into_any_element()
        } else if let Some(words) = &self.cleanup_ui.failed {
            note(format!("Can't read your contacts: {words}.").into()).into_any_element()
        } else if !has_rows {
            empty_state(
                theme,
                "All Clean",
                contact_cleanup::empty_text(Cleanup::ALL[chip]),
            )
            .into_any_element()
        } else {
            let items: Vec<CleanItem> = self.shown_items().to_vec();
            let rows: Vec<AnyElement> = items
                .iter()
                .enumerate()
                .map(|(n, item)| self.cleanup_item(theme, item, n, selecting.is_some(), cx))
                .collect();
            div()
                .flex()
                .flex_col()
                .child(section(theme, None, rows))
                .children(shown_note(total).map(|text| note(text.into())))
                .into_any_element()
        };
        div()
            .child(top)
            .child(large_title(theme, title, trailing))
            .child(chips)
            .child(body)
            .into_any_element()
    }

    fn cleanup_item(
        &self,
        theme: Theme,
        item: &CleanItem,
        n: usize,
        selecting: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let id = item.id;
        let mono = avatar::monogram(&item.name)
            .map(String::from)
            .unwrap_or_default();
        let tint = hue_pair(theme, avatar::hue_index(Some(id))).0;
        let ticked = selecting.then(|| {
            self.cleanup_ui
                .select
                .as_ref()
                .is_some_and(|s| s.contains(&id))
        });
        list_row(
            theme,
            Some(photo_avatar(theme, mono, 40.0, tint, None).into_any_element()),
            item.name.clone(),
            None,
            None,
        )
        .children(ticked.map(|on| crate::select::check_mark(theme, on)))
        .id(("cleanup-row", n))
        .on_click(cx.listener(move |this, _, _, cx| {
            if let Some(select) = this.cleanup_ui.select.as_mut() {
                if !select.toggle(id) {
                    this.notice = Some(rovsti_core::contact_select::cap_notice().into());
                }
                cx.notify();
            } else {
                this.open_contact(id, cx);
            }
        }))
        .into_any_element()
    }

    /// Delete for the ticked contacts, in place of the tab bar.
    pub(crate) fn cleanup_bar_view(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let selection = self.cleanup_ui.select.as_ref()?;
        if !self.cleanup_ui.open {
            return None;
        }
        let colors = theme::colors(theme);
        let body = if selection.is_empty() {
            div()
                .py(px(10.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("Tick contacts to act on them")
                .into_any_element()
        } else {
            div()
                .flex()
                .justify_center()
                .px(px(8.0))
                .py(px(8.0))
                .child(
                    glass_pill(theme, "Delete")
                        .id("cleanup-delete")
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .text_color(colors.red)
                        .child(
                            svg()
                                .path(Icon::LucideTrash.path())
                                .size(px(14.0))
                                .text_color(colors.red),
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.ask_delete_cleanup(cx))),
                )
                .into_any_element()
        };
        Some(
            div()
                .id("cleanup-select-bar")
                .absolute()
                .bottom(metrics::TAB_BAR_MARGIN)
                .left(px(8.0))
                .right(px(8.0))
                .flex()
                .justify_center()
                .rounded(radius::CARD)
                .occlude()
                .child(
                    flat_glass(theme, None)
                        .rounded(radius::CARD)
                        .flex()
                        .justify_center()
                        .child(body),
                )
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::contact_edit::{DraftField, FieldKind};

    fn draft(id: i64, given: &str, number: bool, group: bool) -> ContactDraft {
        ContactDraft {
            id: Some(id),
            given: given.to_owned(),
            numbers: if number {
                vec![DraftField::new("555", FieldKind::Mobile)]
            } else {
                Vec::new()
            },
            groups: if group { vec![1] } else { Vec::new() },
            ..ContactDraft::default()
        }
    }

    #[test]
    fn chip_text_joins_label_and_count() {
        assert_eq!(cleanup_chip_text("No photo", 12), "No photo \u{b7} 12");
        assert_eq!(cleanup_chip_text("No number", 0), "No number \u{b7} 0");
    }

    #[test]
    fn shown_note_only_past_the_cap() {
        assert_eq!(shown_note(MAX_SHOWN), None);
        assert_eq!(
            shown_note(MAX_SHOWN + 1).as_deref(),
            Some("Showing the first 200 of 201.")
        );
    }

    #[test]
    fn nameless_cards_read_no_name() {
        assert_eq!(item_name(&draft(1, "", false, false)), "No name");
        assert_eq!(item_name(&draft(1, "", true, false)), "555");
        assert_eq!(item_name(&draft(1, "Ayse", true, false)), "Ayse");
    }

    #[test]
    fn lists_follow_the_filters_and_skip_hidden() {
        let drafts = [
            draft(1, "A", true, true),
            draft(2, "B", false, true),
            draft(3, "", false, false),
        ];
        let photos: HashSet<i64> = [1].into();
        let lists = Lists::build(&drafts, &photos, &[]);
        assert_eq!(lists.counts(), [2, 1, 2, 1]);
        assert_eq!(lists.items[1][0].name, "No name");
        let hidden = Lists::build(&drafts, &photos, &[3]);
        assert_eq!(hidden.counts(), [1, 0, 1, 0]);
    }

    #[test]
    fn deleted_contacts_leave_every_list() {
        let drafts = [draft(1, "A", false, false), draft(2, "B", false, false)];
        let mut lists = Lists::build(&drafts, &HashSet::new(), &[]);
        assert_eq!(lists.counts(), [2, 0, 2, 2]);
        lists.without(&[1]);
        assert_eq!(lists.counts(), [1, 0, 1, 1]);
    }
}
