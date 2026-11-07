//! Contact groups: the Groups tab, a group's page, and creating, renaming and
//! deleting a group.
//!
//! Groups are labels like "Family". A contact joins a group in its edit form or from the
//! group's page; here the owner sees who is in each group and manages the
//! groups themselves. New groups live on the phone only (no account).

use std::collections::HashSet;

use gpui::{
    AnyElement, App, AppContext, Context, Entity, InteractiveElement, IntoElement, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, Window, div, prelude::FluentBuilder, px, svg,
};
use rovsti_core::{
    contact_dates,
    contact_edit::{
        ContactDraft, Group, account_label, group_join_hint, group_subtitle, group_title,
    },
    contacts_view, group_tools,
    group_tools::Kind,
    vcard,
};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, large_title, list_row, section},
    current_theme,
    field::TextField,
    theme,
    tokens::{Theme, metrics, radius, typography},
};

use crate::{
    ContactsApp, GROUPS_TAB, Row, RowSet, TABS, backend, editor, refill,
    ui::{Config, Tone, field_box, form_row, pill, tap_row},
};

/// An action of the group page's menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum GroupAction {
    Message,
    Email,
    Share,
    SaveToFile,
}

/// The menu rows for a group with `n_members` people in the address book.
/// Message needs a member with a number. `has_emails` is `true` when it is
/// not known (e-mails are read only when the action runs, and the pill then
/// says if nobody has one). An empty group has no menu.
pub(crate) fn group_menu(
    n_members: usize,
    has_numbers: bool,
    has_emails: bool,
) -> Vec<GroupAction> {
    if n_members == 0 {
        return Vec::new();
    }
    let mut actions = Vec::new();
    if has_numbers {
        actions.push(GroupAction::Message);
    }
    if has_emails {
        actions.push(GroupAction::Email);
    }
    actions.push(GroupAction::Share);
    actions.push(GroupAction::SaveToFile);
    actions
}

fn action_row(action: GroupAction) -> (&'static str, Icon, &'static str) {
    match action {
        GroupAction::Message => ("group-message", Icon::LucideMessageSquare, "Message group"),
        GroupAction::Email => ("group-email", Icon::LucideMail, "Email group"),
        GroupAction::Share => ("group-share", Icon::LucideShare, "Share as .vcf"),
        GroupAction::SaveToFile => ("group-save-file", Icon::LucideDownload, "Save to file"),
    }
}

/// Whether more people had an address than the cap let through.
fn was_capped(members: usize, missing: usize, used: usize, cap: usize) -> bool {
    used >= cap && members.saturating_sub(missing) > used
}

/// The pill after an action ran: what was left out, or why nothing opened.
fn recipients_pill(kind: Kind, members: usize, used: usize, missing: usize) -> Option<String> {
    let cap = match kind {
        Kind::Sms => group_tools::MAX_SMS,
        Kind::Email => group_tools::MAX_EMAIL,
    };
    group_tools::recipients_notice(kind, used, missing, was_capped(members, missing, used, cap))
}

fn nobody_text(kind: Kind) -> &'static str {
    match kind {
        Kind::Sms => "No one in this group has a number.",
        Kind::Email => "No one in this group has an email.",
    }
}

/// One row of the Groups tab, with its texts built once per change.
pub(crate) struct GroupItem {
    id: i64,
    title: SharedString,
    /// "3 contacts": members that are in the address book.
    count: SharedString,
}

/// The title box shown while a group is being created or renamed.
pub(crate) struct GroupEdit {
    /// `None` while creating.
    id: Option<i64>,
    field: Entity<TextField>,
    error: Option<SharedString>,
    saving: bool,
    /// The account key a new group is made in ("" is the phone).
    account: String,
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
            .map(|vm| {
                let mut row = Row::from(vm);
                row.set_whatsapp(self.wa.marked.get(&row.vm.id).map(String::as_str));
                row
            })
            .collect()
    }

    /// The cards of the people in group `id`, read off the UI thread.
    fn group_member_ids(&self) -> Vec<i64> {
        self.group_rows.iter().map(|r| r.vm.id).collect()
    }

    /// Message group / Email group: one composer for every member with a
    /// number or address.
    fn group_compose(&mut self, kind: Kind, cx: &mut Context<Self>) {
        let ids = self.group_member_ids();
        self.notice = Some("Opening\u{2026}".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let members: Vec<ContactDraft> = backend::drafts_of(&ids)?;
                    let (list, missing) = match kind {
                        Kind::Sms => group_tools::sms_recipients(&members),
                        Kind::Email => group_tools::email_recipients(&members),
                    };
                    if list.is_empty() {
                        return Ok::<_, String>(Err(nobody_text(kind).to_owned()));
                    }
                    match kind {
                        Kind::Sms => backend::group_sms(&list, ""),
                        Kind::Email => backend::group_email(&list),
                    }?;
                    Ok(Ok(recipients_pill(
                        kind,
                        members.len(),
                        list.len(),
                        missing,
                    )))
                })
                .await;
            this.update(cx, |this, cx| {
                this.notice = match result {
                    Ok(Ok(note)) => note.map(Into::into),
                    Ok(Err(words)) => Some(words.into()),
                    Err(words) => Some(format!("Can't open the composer: {words}.").into()),
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Share as .vcf (the share sheet) or Save to file (the picker).
    fn group_file(&mut self, id: i64, save: bool, cx: &mut Context<Self>) {
        if save && self.transfer_busy {
            self.notice = Some("Wait for the file being saved or read to finish.".into());
            cx.notify();
            return;
        }
        let Some(title) = self.group(id).map(|g| g.title.clone()) else {
            return;
        };
        let ids = self.group_member_ids();
        let today = contact_dates::local_ymd(crate::whatsapp::now_ms(), self.wa.offset_s);
        if save {
            self.transfer_busy = true;
        }
        self.notice = Some("Preparing the file\u{2026}".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let members = backend::drafts_of(&ids)?;
                    if members.is_empty() {
                        return Ok(0);
                    }
                    let name = group_tools::group_file_name(&title, today);
                    let text = vcard::write(&members);
                    if save {
                        backend::start_export_named(&name, &text)?;
                    } else {
                        backend::share_vcf(&name, &text)?;
                    }
                    Ok::<_, String>(members.len())
                })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(0) => {
                        this.transfer_busy = false;
                        this.notice = Some("Nothing to share.".into());
                    }
                    // The picker answers with a document_result.
                    Ok(count) if save => {
                        this.exported = Some(count);
                        this.notice = None;
                    }
                    Ok(_) => this.notice = None,
                    Err(words) => {
                        this.transfer_busy = false;
                        this.notice = Some(format!("Can't share the group: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn run_group_action(&mut self, id: i64, action: GroupAction, cx: &mut Context<Self>) {
        match action {
            GroupAction::Message => self.group_compose(Kind::Sms, cx),
            GroupAction::Email => self.group_compose(Kind::Email, cx),
            GroupAction::Share => self.group_file(id, false, cx),
            GroupAction::SaveToFile => self.group_file(id, true, cx),
        }
    }

    /// The Groups tab's rows: each group's title and how many of its members
    /// are in the address book. Built per pull, never per frame.
    pub(crate) fn build_group_items(&self) -> Vec<GroupItem> {
        let Some(index) = self.index() else {
            return Vec::new();
        };
        let known: HashSet<i64> = index.all().map(|c| c.id).collect();
        self.groups
            .iter()
            .map(|group| {
                let members: HashSet<i64> = group.members.iter().copied().collect();
                let count = members.iter().filter(|id| known.contains(id)).count();
                GroupItem {
                    id: group.id,
                    title: group.title.clone().into(),
                    count: group_subtitle(
                        count,
                        (self.accounts.len() > 1).then_some(group.account.as_str()),
                    )
                    .into(),
                }
            })
            .collect()
    }

    /// Re-points the Groups tab and the group page lists at their rows. The
    /// open title box is the header's field. The group page goes back to the
    /// top when `page_to_top`.
    pub(crate) fn refill_groups(&self, page_to_top: bool, cx: &App) {
        let focus = self
            .group_edit
            .as_ref()
            .map(|edit| edit.field.read(cx).focus_handle());
        refill(
            &self.groups_list,
            self.group_items.len(),
            focus.clone(),
            false,
        );
        refill(&self.group_list, self.group_rows.len(), focus, page_to_top);
    }

    pub(crate) fn open_group(&mut self, id: i64, cx: &mut Context<Self>) {
        self.group_page = Some(id);
        self.group_rows = self
            .group(id)
            .map(|g| self.rows_of_group(g))
            .unwrap_or_default();
        self.group_edit = None;
        self.clear_notice();
        self.refill_groups(true, cx);
        self.page.restart();
        cx.notify();
    }

    pub(crate) fn close_group(&mut self, cx: &mut Context<Self>) {
        self.group_page = None;
        self.group_rows.clear();
        self.group_edit = None;
        self.refill_groups(true, cx);
        self.page.restart();
        cx.notify();
    }

    /// Opens the title box for a new group (`id` `None`) or to rename one.
    pub(crate) fn start_group_edit(&mut self, id: Option<i64>, cx: &mut Context<Self>) {
        let title = id
            .and_then(|id| self.group(id))
            .map(|g| g.title.clone())
            .unwrap_or_default();
        let field = cx.new(|cx| Config::TEXT.build(&title, cx));
        let account = match id.and_then(|id| self.group(id)) {
            Some(group) => group.account.clone(),
            None => editor::default_account(&self.accounts, &self.save_to),
        };
        self.group_edit = Some(GroupEdit {
            id,
            field,
            error: None,
            saving: false,
            account,
        });
        self.clear_notice();
        // The title box sits in the header: keep it drawn while it has focus.
        self.refill_groups(false, cx);
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
        let account = edit.account.clone();
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
                Ok(saved) => {
                    this.group_edit = None;
                    if id.is_some() {
                        this.notice = Some("Group renamed.".into());
                    } else {
                        this.pending_group = Some(saved);
                        this.notice =
                            Some("Group created. Add people with Add or Remove Contacts.".into());
                    }
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
        let save_to = (edit.id.is_none() && self.accounts.len() > 1).then(|| {
            section(
                theme,
                None,
                [
                    tap_row(theme, "group-save-to", None, "Save to", Tone::Plain)
                        .justify_between()
                        .child(
                            div()
                                .text_size(typography::FOOTNOTE.size)
                                .text_color(colors.accent)
                                .child(SharedString::from(account_label(&edit.account))),
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            if let Some(edit) = this.group_edit.as_mut() {
                                edit.account = editor::next_account(&this.accounts, &edit.account);
                            }
                            cx.notify();
                        }))
                        .into_any_element(),
                ],
            )
        });
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
                .children(save_to)
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

    /// Item `ix` of the virtualised Groups tab: 0 is the header (title, the
    /// title box, a New Group row), then one group per item, drawn as the
    /// rows of one rounded card.
    pub(crate) fn groups_item(
        &mut self,
        ix: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        if let Some(i) = ix.checked_sub(1) {
            let Some(group) = self.group_items.get(i) else {
                return div().into_any_element();
            };
            let id = group.id;
            let last = i + 1 == self.group_items.len();
            return div()
                .px(metrics::ROW_INSET)
                .child(
                    div()
                        .bg(colors.secondary_background)
                        .overflow_hidden()
                        .when(i == 0, |card| card.rounded_t(radius::GROUPED_LIST))
                        .when(last, |card| card.rounded_b(radius::GROUPED_LIST))
                        .child(
                            list_row(
                                theme,
                                Some(
                                    svg()
                                        .path(Icon::LucideUsers.path())
                                        .size(px(22.0))
                                        .text_color(colors.accent)
                                        .into_any_element(),
                                ),
                                group.title.clone(),
                                Some(group.count.clone()),
                                None,
                            )
                            .id(("group", i))
                            .on_click(cx.listener(move |this, _, _, cx| this.open_group(id, cx))),
                        ),
                )
                .into_any_element();
        }
        let title = large_title(window, theme, TABS[GROUPS_TAB], None);
        if let Some(state) = self.book_state(theme, cx) {
            return div().child(title).child(state).into_any_element();
        }
        let empty = self.group_items.is_empty().then(|| {
            empty_state(
                theme,
                "No Groups",
                "Make a group, then add people to it from the group's page.",
            )
        });
        div()
            .flex()
            .flex_col()
            .child(title)
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(16.0))
                    // The gap between the New Group row and the groups' card.
                    .when(empty.is_none(), |body| body.pb(px(16.0)))
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
                    .children(empty),
            )
            .into_any_element()
    }

    /// Item `ix` of the virtualised page of group `id`: 0 is the header (Back,
    /// Rename, Delete, the title and the title box), then its contacts.
    pub(crate) fn group_page_item(
        &mut self,
        id: i64,
        ix: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        if let Some(i) = ix.checked_sub(1) {
            return self.contact_item(theme, RowSet::Group, i, cx);
        }
        let Some(group) = self.group(id) else {
            return div().into_any_element();
        };
        let title = SharedString::from(group.title.clone());
        let join_hint =
            (self.accounts.len() > 1).then(|| SharedString::from(group_join_hint(&group.account)));
        let colors = theme::colors(theme);
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
                        glass_icon_button(theme, Icon::LucideUserPlus, 0.0, false)
                            .id("group-members")
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_group_picker(id, cx);
                            })),
                    )
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
        let has_numbers = self.group_rows.iter().any(|r| {
            self.index()
                .and_then(|index| index.all().find(|c| c.id == r.vm.id))
                .is_some_and(|c| !c.numbers.is_empty())
        });
        let menu = group_menu(self.group_rows.len(), has_numbers, true);
        let menu_section = (!menu.is_empty()).then(|| {
            section(
                theme,
                None,
                menu.into_iter()
                    .map(|action| {
                        let (key, icon, label) = action_row(action);
                        tap_row(theme, key, Some(icon), label, Tone::Accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.run_group_action(id, action, cx);
                            }))
                            .into_any_element()
                    })
                    .collect::<Vec<_>>(),
            )
        });
        let empty = self.group_rows.is_empty().then(|| {
            empty_state(
                theme,
                "Empty Group",
                "Add people with Add or Remove Contacts.",
            )
        });
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            // The gap above the first contact.
            .when(empty.is_none(), |header| header.pb(px(8.0)))
            .child(top)
            .child(large_title(window, theme, title, None))
            .child(section(
                theme,
                None,
                [tap_row(
                    theme,
                    "group-members-row",
                    Some(Icon::LucideUserPlus),
                    "Add or Remove Contacts",
                    Tone::Accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.open_group_picker(id, cx)))
                .into_any_element()],
            ))
            .children(menu_section)
            .children(join_hint.map(|hint| {
                div()
                    .px(px(32.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(hint)
            }))
            .children(self.group_edit_section(theme, window, cx))
            .children(empty)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_group_has_no_menu() {
        assert!(group_menu(0, true, true).is_empty());
    }

    #[test]
    fn menu_follows_what_members_have() {
        assert_eq!(
            group_menu(3, true, true),
            [
                GroupAction::Message,
                GroupAction::Email,
                GroupAction::Share,
                GroupAction::SaveToFile
            ]
        );
        assert_eq!(
            group_menu(2, false, true),
            [
                GroupAction::Email,
                GroupAction::Share,
                GroupAction::SaveToFile
            ]
        );
        assert_eq!(
            group_menu(2, false, false),
            [GroupAction::Share, GroupAction::SaveToFile]
        );
    }

    #[test]
    fn cap_is_reported_only_when_people_were_left_out() {
        assert!(!was_capped(30, 0, 30, 30));
        assert!(was_capped(40, 5, 30, 30));
        assert!(!was_capped(35, 5, 30, 30));
        assert!(!was_capped(10, 0, 10, 30));
    }

    #[test]
    fn pill_names_the_people_left_out() {
        assert_eq!(recipients_pill(Kind::Sms, 3, 3, 0), None);
        assert_eq!(
            recipients_pill(Kind::Sms, 4, 2, 2).as_deref(),
            Some("2 people have no number")
        );
    }
}
