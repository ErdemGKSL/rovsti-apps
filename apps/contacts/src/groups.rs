//! Contact groups: the Groups tab, a group's page, and creating, renaming and
//! deleting a group.
//!
//! Groups are labels like "Family". A contact joins a group in its edit form;
//! here the owner sees who is in each group and manages the groups
//! themselves. New groups live on the phone only (no account).

use gpui::{
    AnyElement, AppContext, Context, Entity, InteractiveElement, IntoElement, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::{
    contact_edit::{Group, group_title},
    contacts_view,
};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, large_title, list_row, section},
    theme,
    tokens::{Theme, metrics, typography},
};

use crate::{
    ContactsApp, Row, backend, editor,
    field::{Config, TextField},
    ui::{Tone, field_box, form_row, pill, tap_row},
};

/// The title box shown while a group is being created or renamed.
pub(crate) struct GroupEdit {
    /// `None` while creating.
    id: Option<i64>,
    field: Entity<TextField>,
    error: Option<SharedString>,
    saving: bool,
}

fn count_words(count: usize) -> String {
    match count {
        0 => "No contacts".to_owned(),
        1 => "1 contact".to_owned(),
        n => format!("{n} contacts"),
    }
}

impl ContactsApp {
    /// The group with `id`, if it still exists.
    pub(crate) fn group(&self, id: i64) -> Option<&Group> {
        self.groups.iter().find(|g| g.id == id)
    }

    /// Members of `group` that are in the address book, as list rows.
    pub(crate) fn rows_of_group(&self, group: &Group) -> Vec<Row> {
        let Some(index) = self.index() else {
            return Vec::new();
        };
        let members = index.all().filter(|c| group.members.contains(&c.id));
        let ordered = contacts_view::ordered(members, self.style);
        contacts_view::build_rows_styled(ordered, "", self.style)
            .into_iter()
            .map(Row::from)
            .collect()
    }

    pub(crate) fn open_group(&mut self, id: i64, cx: &mut Context<Self>) {
        self.group_page = Some(id);
        self.group_rows = self
            .group(id)
            .map(|g| self.rows_of_group(g))
            .unwrap_or_default();
        self.group_edit = None;
        self.notice = None;
        self.page.restart();
        cx.notify();
    }

    pub(crate) fn close_group(&mut self, cx: &mut Context<Self>) {
        self.group_page = None;
        self.group_rows.clear();
        self.group_edit = None;
        self.page.restart();
        cx.notify();
    }

    /// Opens the title box for a new group (`id` `None`) or to rename one.
    pub(crate) fn start_group_edit(&mut self, id: Option<i64>, cx: &mut Context<Self>) {
        let title = id
            .and_then(|id| self.group(id))
            .map(|g| g.title.clone())
            .unwrap_or_default();
        let field = cx.new(|cx| TextField::configured(Config::TEXT, &title, cx));
        self.group_edit = Some(GroupEdit {
            id,
            field,
            error: None,
            saving: false,
        });
        self.notice = None;
        cx.notify();
    }

    pub(crate) fn cancel_group_edit(&mut self, cx: &mut Context<Self>) {
        self.group_edit = None;
        cx.notify();
    }

    pub(crate) fn save_group_edit(&mut self, cx: &mut Context<Self>) {
        let Some(edit) = self.group_edit.as_ref() else {
            return;
        };
        if edit.saving {
            return;
        }
        let id = edit.id;
        // A new group goes where a new contact does.
        let account = editor::default_account(&self.accounts, &self.save_to);
        let text = edit.field.read(cx).text().to_owned();
        let title = match group_title(&text, &self.groups, id) {
            Ok(title) => title,
            Err(error) => {
                if let Some(edit) = self.group_edit.as_mut() {
                    edit.error = Some(error.to_string().into());
                }
                cx.notify();
                return;
            }
        };
        if let Some(edit) = self.group_edit.as_mut() {
            edit.error = None;
            edit.saving = true;
        }
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::save_group(id.unwrap_or(0), &title, &account) })
                .await;
            this.update(cx, |this, cx| match result {
                Ok(_) => {
                    this.group_edit = None;
                    this.reload(cx);
                }
                Err(words) => {
                    if let Some(edit) = this.group_edit.as_mut() {
                        edit.saving = false;
                        edit.error = Some(format!("Can't save the group: {words}.").into());
                    }
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Deletes a group (after the owner confirmed). Its contacts stay.
    pub(crate) fn delete_group(&mut self, id: i64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::delete_group(id) })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(()) => {
                        this.notice = Some("Group deleted.".into());
                        this.close_group(cx);
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't delete the group: {words}.").into());
                    }
                }
                this.reload(cx);
            })
            .ok();
        })
        .detach();
    }

    /// The title box with Save and Cancel, while one is open.
    fn group_edit_section(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let colors = theme::colors(theme);
        let edit = self.group_edit.as_ref()?;
        let heading = if edit.id.is_some() {
            "Rename group"
        } else {
            "New group"
        };
        Some(
            div()
                .flex()
                .flex_col()
                .gap(px(10.0))
                .child(section(
                    theme,
                    Some(heading.into()),
                    [form_row(theme)
                        .child(field_box(
                            theme,
                            "group-title",
                            &edit.field,
                            "Group name",
                            false,
                            window,
                            cx,
                        ))
                        .into_any_element()],
                ))
                .children(edit.error.clone().map(|error| {
                    div()
                        .px(px(32.0))
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.red)
                        .child(error)
                }))
                .child(
                    div()
                        .flex()
                        .justify_end()
                        .gap(px(10.0))
                        .px(metrics::ROW_INSET)
                        .child(
                            pill(theme, "group-cancel", "Cancel", false)
                                .on_click(cx.listener(|this, _, _, cx| this.cancel_group_edit(cx))),
                        )
                        .child(
                            pill(
                                theme,
                                "group-save",
                                if edit.saving { "Saving…" } else { "Save" },
                                false,
                            )
                            .on_click(cx.listener(|this, _, _, cx| this.save_group_edit(cx))),
                        ),
                )
                .into_any_element(),
        )
    }

    /// The Groups tab: the title box, a New Group row and the groups.
    pub(crate) fn groups_tab(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        if let Some(state) = self.book_state(theme, cx) {
            return state;
        }
        let colors = theme::colors(theme);
        let rows: Vec<AnyElement> = self
            .groups
            .iter()
            .enumerate()
            .map(|(i, group)| {
                let id = group.id;
                let count = self.member_count(group);
                list_row(
                    theme,
                    Some(
                        svg()
                            .path(Icon::LucideUsers.path())
                            .size(px(22.0))
                            .text_color(colors.accent)
                            .into_any_element(),
                    ),
                    SharedString::from(group.title.clone()),
                    Some(count_words(count).into()),
                    None,
                )
                .id(("group", i))
                .on_click(cx.listener(move |this, _, _, cx| this.open_group(id, cx)))
                .into_any_element()
            })
            .collect();
        let list = if rows.is_empty() {
            empty_state(
                theme,
                "No Groups",
                "Make a group, then add contacts to it from their Edit screen.",
            )
            .into_any_element()
        } else {
            section(theme, None, rows).into_any_element()
        };
        div()
            .flex()
            .flex_col()
            .gap(px(16.0))
            .children(self.group_edit_section(theme, window, cx))
            .child(section(
                theme,
                None,
                [tap_row(
                    theme,
                    "new-group",
                    Some(Icon::LucidePlus),
                    "New Group",
                    Tone::Accent,
                )
                .on_click(cx.listener(|this, _, _, cx| this.start_group_edit(None, cx)))
                .into_any_element()],
            ))
            .child(list)
            .into_any_element()
    }

    /// How many of a group's members are in the address book.
    fn member_count(&self, group: &Group) -> usize {
        self.index().map_or(0, |index| {
            index
                .all()
                .filter(|c| group.members.contains(&c.id))
                .count()
        })
    }

    /// One group's page: its contacts, with Rename and Delete.
    pub(crate) fn group_page_view(
        &self,
        theme: Theme,
        id: i64,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let Some(group) = self.group(id) else {
            return div().into_any_element();
        };
        let title = SharedString::from(group.title.clone());
        let delete_title = title.clone();
        let top = div()
            .flex()
            .items_center()
            .justify_between()
            .px(px(8.0))
            .pt(px(48.0))
            .child(
                glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                    .id("group-back")
                    .on_click(cx.listener(|this, _, _, cx| this.close_group(cx))),
            )
            .child(
                div()
                    .flex()
                    .gap(px(8.0))
                    .child(
                        glass_icon_button(theme, Icon::LucidePencil, 0.0, false)
                            .id("group-rename")
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.start_group_edit(Some(id), cx);
                            })),
                    )
                    .child(
                        glass_icon_button(theme, Icon::LucideTrash2, 0.0, false)
                            .id("group-delete")
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.ask_delete_group(id, delete_title.clone(), cx);
                            })),
                    ),
            );
        let rows: Vec<AnyElement> = self
            .group_rows
            .iter()
            .map(|row| self.contact_row(theme, row, "group-contact", cx))
            .collect();
        let body = if rows.is_empty() {
            empty_state(
                theme,
                "Empty Group",
                "Add contacts to this group from their Edit screen.",
            )
            .into_any_element()
        } else {
            div().flex().flex_col().children(rows).into_any_element()
        };
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(top)
            .child(large_title(theme, title, None))
            .children(self.group_edit_section(theme, window, cx))
            .child(body)
            .into_any_element()
    }
}
