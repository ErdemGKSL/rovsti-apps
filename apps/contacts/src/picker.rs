//! The contact picker: a full page of contacts to tick and untick, used to
//! add people to a group (and, later, to the Favorites).
//!
//! Nothing is written until Done, so Back and Cancel simply close the page.

use std::collections::{BTreeSet, HashSet};

use gpui::{
    AnyElement, AppContext, Context, Entity, InteractiveElement, IntoElement, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::{contact_edit, contacts_view};
use rovsti_ui::{
    Icon,
    components::{empty_state, large_title},
    current_theme, theme,
    tokens::{metrics, typography},
};

use crate::{ContactsApp, Row, backend, field::TextField, photos::photo_avatar, refill, ui::pill};

/// What the picker edits.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PickFor {
    Group(i64),
    #[expect(dead_code, reason = "opened by the Favorites + button")]
    Favorites,
}

/// The open picker.
pub(crate) struct Picker {
    pub(crate) for_: PickFor,
    /// Who was in the set when the page opened.
    pub(crate) initial: Vec<i64>,
    pub(crate) chosen: BTreeSet<i64>,
    pub(crate) field: Entity<TextField>,
    pub(crate) saving: bool,
    /// The query the rows were built for.
    built_query: String,
}

/// "None selected", "1 selected", "3 selected".
pub(crate) fn selected_words(n: usize) -> String {
    match n {
        0 => "None selected".to_owned(),
        n => format!("{n} selected"),
    }
}

/// `members` that exist in the address book, in their order.
pub(crate) fn known_members(members: &[i64], known: &HashSet<i64>) -> Vec<i64> {
    members
        .iter()
        .copied()
        .filter(|id| known.contains(id))
        .collect()
}

impl ContactsApp {
    /// Opens the picker with `initial` ticked.
    pub(crate) fn open_picker(&mut self, for_: PickFor, initial: Vec<i64>, cx: &mut Context<Self>) {
        let field = cx.new(TextField::new);
        let watched = field.clone();
        cx.observe(&field, move |this, field, cx| {
            let same = this.picker.as_ref().is_some_and(|p| p.field == watched);
            if same {
                let query = field.read(cx).text().to_owned();
                if this.picker.as_ref().is_some_and(|p| p.built_query != query) {
                    this.rebuild_picker_rows(cx);
                }
            }
        })
        .detach();
        self.notice = None;
        self.picker = Some(Picker {
            for_,
            chosen: initial.iter().copied().collect(),
            initial,
            field,
            saving: false,
            built_query: String::new(),
        });
        self.rebuild_picker_rows(cx);
        self.page.restart();
        cx.notify();
    }

    /// Opens the picker for group `id` with its members in the book ticked.
    pub(crate) fn open_group_picker(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(index) = self.index() else {
            return;
        };
        let known: HashSet<i64> = index.all().map(|c| c.id).collect();
        let initial = self
            .group(id)
            .map(|g| known_members(&g.members, &known))
            .unwrap_or_default();
        self.open_picker(PickFor::Group(id), initial, cx);
    }

    pub(crate) fn close_picker(&mut self, cx: &mut Context<Self>) {
        self.picker = None;
        self.picker_rows.clear();
        self.page.restart();
        cx.notify();
    }

    /// Rebuilds the picker's rows for its query.
    pub(crate) fn rebuild_picker_rows(&mut self, cx: &mut Context<Self>) {
        let Some(picker) = self.picker.as_ref() else {
            return;
        };
        let query = picker.field.read(cx).text().to_owned();
        let focus = picker.field.read(cx).focus_handle();
        let new_query = query != picker.built_query;
        self.picker_rows = match self.index() {
            Some(index) => {
                let shown = if query.trim().is_empty() {
                    contacts_view::ordered(index.all(), self.style)
                } else {
                    index.search(&query)
                };
                contacts_view::build_rows_styled(shown, &query, self.style)
                    .into_iter()
                    .map(Row::from)
                    .collect()
            }
            None => Vec::new(),
        };
        refill(
            &self.picker_list,
            self.picker_rows.len(),
            Some(focus),
            new_query,
        );
        if let Some(picker) = self.picker.as_mut() {
            picker.built_query = query;
        }
        cx.notify();
    }

    fn toggle_picked(&mut self, id: i64, cx: &mut Context<Self>) {
        if let Some(picker) = self.picker.as_mut()
            && !picker.saving
            && !picker.chosen.remove(&id)
        {
            picker.chosen.insert(id);
        }
        cx.notify();
    }

    /// Done: writes the change, if there is one.
    pub(crate) fn finish_picker(&mut self, cx: &mut Context<Self>) {
        let Some(picker) = self.picker.as_mut() else {
            return;
        };
        if picker.saving {
            return;
        }
        let change = contact_edit::selection_change(&picker.initial, &picker.chosen);
        if change.is_empty() {
            self.close_picker(cx);
            return;
        }
        let PickFor::Group(group_id) = picker.for_ else {
            self.close_picker(cx);
            return;
        };
        picker.saving = true;
        let title = self
            .group(group_id)
            .map(|g| g.title.clone())
            .unwrap_or_default();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::set_group_members(group_id, &change) })
                .await;
            this.update(cx, |this, cx| match result {
                Ok(payload) => {
                    this.notice = Some(contact_edit::members_summary(&title, &payload).into());
                    this.close_picker(cx);
                    this.reload(cx);
                }
                Err(words) => {
                    if let Some(picker) = this.picker.as_mut() {
                        picker.saving = false;
                    }
                    this.notice = Some(format!("Can't change the group: {words}.").into());
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    fn picker_title(&self, for_: PickFor) -> SharedString {
        match for_ {
            PickFor::Group(id) => {
                let name = self.group(id).map(|g| g.title.as_str()).unwrap_or_default();
                format!("Add to {name}").into()
            }
            PickFor::Favorites => "Favorites".into(),
        }
    }

    /// Item `ix` of the picker page: 0 is the header, then one contact each.
    pub(crate) fn picker_item(
        &mut self,
        ix: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let Some(picker) = self.picker.as_ref() else {
            return div().into_any_element();
        };
        if let Some(i) = ix.checked_sub(1) {
            let Some(row) = self.picker_rows.get(i) else {
                return div().into_any_element();
            };
            let id = row.vm.id;
            let chosen = picker.chosen.contains(&id);
            let header = row
                .vm
                .header
                .then(|| Self::letter_header(theme, row.letter.clone()));
            let tint = theme::hue_pair(theme, row.vm.hue).0;
            let picture =
                self.avatar_picture(id, row.vm.photo_uri.is_some(), row.wa_jid.as_deref(), cx);
            let photo = picture.as_ref().map(|(image, opacity)| (image, *opacity));
            let item = rovsti_ui::components::list_row(
                theme,
                Some(
                    photo_avatar(theme, row.monogram.clone(), 40.0, tint, photo).into_any_element(),
                ),
                row.name.clone(),
                row.subtitle.clone(),
                None,
            )
            .pr(px(48.0));
            let check = chosen.then(|| {
                div()
                    .absolute()
                    .top_0()
                    .bottom_0()
                    .right(metrics::ROW_INSET)
                    .flex()
                    .items_center()
                    .child(
                        svg()
                            .path(Icon::LucideCheck.path())
                            .size(px(22.0))
                            .text_color(colors.accent),
                    )
            });
            return div()
                .flex()
                .flex_col()
                .children(header)
                .child(
                    div()
                        .id(("pick", usize::try_from(id).unwrap_or_default()))
                        .relative()
                        .child(item)
                        .children(check)
                        .on_click(cx.listener(move |this, _, _, cx| this.toggle_picked(id, cx))),
                )
                .into_any_element();
        }
        let saving = picker.saving;
        let title = self.picker_title(picker.for_);
        let selected = selected_words(picker.chosen.len());
        let field = picker.field.clone();
        let empty = self.picker_rows.is_empty().then(|| {
            let query = field.read(cx).text();
            if query.trim().is_empty() {
                empty_state(
                    theme,
                    "No Contacts",
                    "Contacts with a phone number appear here.",
                )
                .into_any_element()
            } else {
                empty_state(
                    theme,
                    "No Results",
                    format!("No contact matches “{}”.", contacts_view::clip_query(query)),
                )
                .into_any_element()
            }
        });
        div()
            .flex()
            .flex_col()
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .px(px(16.0))
                    .pt(px(48.0))
                    .child(
                        pill(theme, "picker-cancel", "Cancel", false)
                            .on_click(cx.listener(|this, _, _, cx| this.close_picker(cx))),
                    )
                    .child(
                        pill(
                            theme,
                            "picker-done",
                            if saving { "Saving…" } else { "Done" },
                            false,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.finish_picker(cx))),
                    ),
            )
            .child(large_title(theme, title, None).pt(px(8.0)))
            .child(self.search_box(&field, theme, window, cx))
            .child(
                div()
                    .px(metrics::ROW_INSET)
                    .pb(px(4.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(selected),
            )
            .children(empty)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selected_words_read_naturally() {
        assert_eq!(selected_words(0), "None selected");
        assert_eq!(selected_words(1), "1 selected");
        assert_eq!(selected_words(3), "3 selected");
    }

    #[test]
    fn initial_members_keep_only_known_ids() {
        let known: HashSet<i64> = [1, 2, 3].into_iter().collect();
        assert_eq!(known_members(&[3, 9, 1, 7], &known), vec![3, 1]);
        assert!(known_members(&[], &known).is_empty());
    }
}
