//! Select mode in the Contacts list: ticking rows, the glass action bar and
//! the bulk actions (share, save to file, add to group, star, delete). The
//! decisions (which actions, notices, group targets) live in
//! `rovsti_core::contact_select` and `rovsti_core::multi_select`; the work
//! runs on the background executor.

use std::collections::BTreeSet;

use gpui::{
    AnyElement, Context, Div, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, prelude::*, px, svg,
};
use rovsti_core::{
    contact_dates,
    contact_edit::account_label,
    contact_select::{self, GroupTarget, SHARE_FORMATS, SelectAction, ShareFormat},
    multi_select::{self, Selection},
};
use rovsti_ui::{
    Icon,
    components::{flat_glass, glass_pill, large_title},
    sheet::Sheet,
    theme,
    tokens::{Theme, metrics, radius, typography},
};

use crate::{
    ContactsApp, backend,
    confirm::Confirm,
    ui::{Tone, tap_row},
};

/// The "Add to group" sheet and the work behind it.
#[derive(Default)]
pub(crate) struct SelectUi {
    pub(crate) sheet: Sheet,
    /// Groups the selection can join, built when the sheet opens.
    targets: Vec<GroupTarget>,
    /// The accounts of the selection are being read.
    busy: bool,
    /// What the sheet lists: groups to join, or the formats to share in.
    mode: SheetMode,
}

/// The two things the select sheet offers.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum SheetMode {
    #[default]
    Group,
    ShareFormat,
}

/// The rows of the share sheet: each format with its icon, in order.
pub(crate) fn share_sheet_rows() -> Vec<(ShareFormat, Icon)> {
    SHARE_FORMATS
        .iter()
        .map(|format| {
            let icon = match format {
                ShareFormat::Vcf => Icon::LucideContact,
                ShareFormat::Text => Icon::LucideUpload,
            };
            (*format, icon)
        })
        .collect()
}

/// The title of the sheet.
fn sheet_title(mode: SheetMode) -> &'static str {
    match mode {
        SheetMode::Group => "Add to group",
        SheetMode::ShareFormat => "Share as",
    }
}

/// The round check at the end of a row: filled accent when ticked.
pub(crate) fn check_mark(theme: Theme, on: bool) -> Div {
    let colors = theme::colors(theme);
    let ring = if on {
        colors.accent
    } else {
        colors.tertiary_label
    };
    div()
        .flex_none()
        .size(px(22.0))
        .rounded_full()
        .border_1()
        .border_color(ring)
        .flex()
        .items_center()
        .justify_center()
        .when(on, |m| {
            m.bg(colors.accent).child(
                svg()
                    .path(Icon::LucideCheck.path())
                    .size(px(14.0))
                    .text_color(gpui::white()),
            )
        })
}

fn action_icon(action: SelectAction) -> Icon {
    match action {
        SelectAction::Share => Icon::LucideShare,
        SelectAction::SaveToFile => Icon::LucideDownload,
        SelectAction::AddToGroup => Icon::LucideUsers,
        SelectAction::Star => Icon::LucideStar,
        SelectAction::Delete => Icon::LucideTrash,
    }
}

fn action_id(action: SelectAction) -> &'static str {
    match action {
        SelectAction::Share => "sel-share",
        SelectAction::SaveToFile => "sel-save",
        SelectAction::AddToGroup => "sel-group",
        SelectAction::Star => "sel-star",
        SelectAction::Delete => "sel-delete",
    }
}

fn action_label(action: SelectAction, star: bool) -> &'static str {
    match action {
        SelectAction::Share => "Share",
        SelectAction::SaveToFile => "Save",
        SelectAction::AddToGroup => "Group",
        SelectAction::Star if star => "Star",
        SelectAction::Star => "Unstar",
        SelectAction::Delete => "Delete",
    }
}

impl ContactsApp {
    /// Enters select mode, ticking `first` if given.
    pub(crate) fn enter_select(&mut self, first: Option<i64>, cx: &mut Context<Self>) {
        let mut selection = Selection::new();
        if let Some(id) = first {
            selection.toggle(id);
        }
        self.select = Some(selection);
        self.clear_notice();
        cx.notify();
    }

    /// A long press on a Contacts row starts select mode, or toggles in it.
    pub(crate) fn long_press_contact(&mut self, id: i64, cx: &mut Context<Self>) {
        if self.select.is_some() {
            self.toggle_selected(id, cx);
        } else {
            self.enter_select(Some(id), cx);
        }
    }

    pub(crate) fn toggle_selected(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(selection) = self.select.as_mut() else {
            return;
        };
        if !selection.toggle(id) {
            self.notice = Some(contact_select::cap_notice().into());
        }
        cx.notify();
    }

    pub(crate) fn leave_select(&mut self, cx: &mut Context<Self>) {
        self.select = None;
        self.select_ui.sheet.close();
        cx.notify();
    }

    /// "Select all" over the rows shown, up to the cap.
    fn select_all_shown(&mut self, cx: &mut Context<Self>) {
        let ids: Vec<i64> = self.rows.iter().map(|r| r.vm.id).collect();
        let Some(selection) = self.select.as_mut() else {
            return;
        };
        let missing = ids.iter().filter(|id| !selection.contains(id)).count();
        let added = selection.select_all(ids);
        if added < missing {
            self.notice = Some(contact_select::cap_notice().into());
        }
        cx.notify();
    }

    /// The ticked contacts, ascending, without those waiting to be deleted.
    fn selected_ids(&self) -> Vec<i64> {
        let set: BTreeSet<i64> = self.select.iter().flat_map(|s| s.keys().copied()).collect();
        let hidden = self
            .pending_delete
            .as_ref()
            .map(|p| p.ids.as_slice())
            .unwrap_or_default();
        contact_select::export_ids(&set, hidden)
    }

    /// Whether the star button stars (every ticked contact otherwise starred
    /// means it unstars).
    fn star_wanted(&self, ids: &[i64]) -> bool {
        let flags: Vec<bool> = self
            .index()
            .map(|index| {
                index
                    .all()
                    .filter(|c| ids.contains(&c.id))
                    .map(|c| c.starred)
                    .collect()
            })
            .unwrap_or_default();
        multi_select::star_all(&flags)
    }

    fn today(&self) -> (i32, u8, u8) {
        contact_dates::local_ymd(crate::whatsapp::now_ms(), self.wa.offset_s)
    }

    fn run_select_action(&mut self, action: SelectAction, cx: &mut Context<Self>) {
        match action {
            SelectAction::Share => self.open_share_format_sheet(cx),
            SelectAction::SaveToFile => self.save_selection(cx),
            SelectAction::AddToGroup => self.open_group_sheet(cx),
            SelectAction::Star => self.star_selection(cx),
            SelectAction::Delete => {
                let ids = self.selected_ids();
                if !ids.is_empty() {
                    self.confirm = Some(Confirm::DeleteMany { ids });
                    cx.notify();
                }
            }
        }
    }

    /// Select mode's Share: asks for the format first.
    fn open_share_format_sheet(&mut self, cx: &mut Context<Self>) {
        if self.selected_ids().is_empty() {
            return;
        }
        self.select_ui.mode = SheetMode::ShareFormat;
        self.select_ui.sheet.open();
        cx.notify();
    }

    /// A format was tapped in the share sheet.
    fn share_in_format(&mut self, format: ShareFormat, cx: &mut Context<Self>) {
        self.select_ui.sheet.close();
        match format {
            ShareFormat::Vcf => self.share_selection(cx),
            ShareFormat::Text => self.share_selection_text(cx),
        }
    }

    /// Share sheet with the ticked contacts as plain text.
    fn share_selection_text(&mut self, cx: &mut Context<Self>) {
        let ids = self.selected_ids();
        if ids.is_empty() {
            return;
        }
        let hidden = self.hidden_ids();
        self.notice = Some("Preparing the text…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::share_text_of(&ids, &hidden) })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(0) => this.notice = Some("Nothing to share.".into()),
                    Ok(count) => {
                        this.notice = Some(contact_select::shared_text_notice(count).into());
                        this.select = None;
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't share the contacts: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Share sheet with the ticked contacts as one `.vcf`.
    fn share_selection(&mut self, cx: &mut Context<Self>) {
        let ids = self.selected_ids();
        if ids.is_empty() {
            return;
        }
        let hidden = self.hidden_ids();
        let today = self.today();
        self.notice = Some("Preparing the file…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let (text, count) = backend::export_selection(&ids, &hidden)?;
                    if count == 0 {
                        return Ok(0);
                    }
                    let name = contact_select::selection_file_name(count, today);
                    backend::share_vcf(&name, &text).map(|()| count)
                })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(0) => this.notice = Some("Nothing to share.".into()),
                    Ok(_) => {
                        this.notice = None;
                        this.select = None;
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't share the contacts: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// "Save as" for the ticked contacts; the answer is a `document_result`.
    fn save_selection(&mut self, cx: &mut Context<Self>) {
        if self.transfer_busy {
            self.notice = Some("Wait for the file being saved or read to finish.".into());
            cx.notify();
            return;
        }
        let ids = self.selected_ids();
        if ids.is_empty() {
            return;
        }
        let hidden = self.hidden_ids();
        let today = self.today();
        self.transfer_busy = true;
        self.notice = Some("Preparing the file…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let (text, count) = backend::export_selection(&ids, &hidden)?;
                    if count == 0 {
                        return Ok(0);
                    }
                    let name = contact_select::selection_file_name(count, today);
                    backend::start_export_named(&name, &text).map(|()| count)
                })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(0) => {
                        this.transfer_busy = false;
                        this.notice = Some("Nothing to save.".into());
                    }
                    Ok(count) => {
                        this.exported = Some(count);
                        this.select = None;
                    }
                    Err(words) => {
                        this.transfer_busy = false;
                        this.notice = Some(format!("Can't save the file: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn hidden_ids(&self) -> Vec<i64> {
        self.pending_delete
            .as_ref()
            .map(|p| p.ids.clone())
            .unwrap_or_default()
    }

    /// Stars or unstars every ticked contact, one save each.
    fn star_selection(&mut self, cx: &mut Context<Self>) {
        let ids = self.selected_ids();
        if ids.is_empty() {
            return;
        }
        let star = self.star_wanted(&ids);
        self.notice = Some(if star { "Starring…" } else { "Unstarring…" }.into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let (done, failed) = cx
                .background_executor()
                .spawn(async move {
                    let mut done = 0usize;
                    let mut failed: Option<String> = None;
                    for id in ids {
                        let saved = backend::pull_detail(id).and_then(|mut draft| {
                            draft.starred = star;
                            backend::save(&draft).map(|_| ())
                        });
                        match saved {
                            Ok(()) => done += 1,
                            Err(words) => failed = Some(words),
                        }
                    }
                    (done, failed)
                })
                .await;
            this.update(cx, |this, cx| {
                this.notice = Some(match (done, failed) {
                    (0, Some(words)) => format!("Can't change the star: {words}.").into(),
                    (n, Some(words)) => format!(
                        "{}. Some failed: {words}.",
                        contact_select::starred_notice(n, star)
                    )
                    .into(),
                    (n, None) => contact_select::starred_notice(n, star).into(),
                });
                if done > 0 {
                    this.select = None;
                }
                this.reload(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Reads the accounts of the ticked contacts, then opens the group sheet.
    fn open_group_sheet(&mut self, cx: &mut Context<Self>) {
        let ids = self.selected_ids();
        if ids.is_empty() || self.select_ui.busy {
            return;
        }
        self.select_ui.busy = true;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    backend::pull_full().map(|all| {
                        contact_select::pick_drafts(&all, &ids)
                            .into_iter()
                            .filter_map(|d| d.id.map(|id| (id, d.account)))
                            .collect::<Vec<_>>()
                    })
                })
                .await;
            this.update(cx, |this, cx| {
                this.select_ui.busy = false;
                match result {
                    Ok(selected) => {
                        this.select_ui.targets =
                            contact_select::group_targets(&this.groups, &selected);
                        this.select_ui.mode = SheetMode::Group;
                        this.select_ui.sheet.open();
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't read the contacts: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// A group was tapped in the sheet: adds the contacts of its account.
    fn add_selection_to_group(&mut self, ix: usize, cx: &mut Context<Self>) {
        let Some(target) = self.select_ui.targets.get(ix).cloned() else {
            return;
        };
        self.select_ui.sheet.close();
        let members = self
            .groups
            .iter()
            .find(|g| g.id == target.group_id)
            .map(|g| g.members.clone())
            .unwrap_or_default();
        let change = contact_select::add_to_group_change(&members, &target.eligible);
        if change.add.is_empty() {
            self.notice =
                Some(contact_select::added_with_skipped(0, &target.title, target.skipped).into());
            cx.notify();
            return;
        }
        self.notice = Some("Adding to the group…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let group_id = target.group_id;
            let result = cx
                .background_executor()
                .spawn(async move { backend::set_group_members(group_id, &change) })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(payload) => {
                        this.notice = Some(
                            contact_select::added_with_skipped(
                                payload.added as usize,
                                &target.title,
                                target.skipped,
                            )
                            .into(),
                        );
                        this.select = None;
                        this.reload(cx);
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't add to the group: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Item 0 of the Contacts list in select mode: Cancel, the count, Select
    /// all, and the search field.
    pub(crate) fn select_header(
        &mut self,
        theme: Theme,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let count = self.select.as_ref().map_or(0, Selection::len);
        let all = glass_pill(theme, "Select all")
            .id("select-all")
            .on_click(cx.listener(|this, _, _, cx| this.select_all_shown(cx)))
            .into_any_element();
        let cancel = glass_pill(theme, "Cancel")
            .id("select-cancel")
            .on_click(cx.listener(|this, _, _, cx| this.leave_select(cx)));
        div()
            .flex()
            .flex_col()
            .child(div().px(px(16.0)).pt(px(48.0)).flex().child(cancel))
            .child(large_title(theme, contact_select::title(count), Some(all)))
            .child(self.search_field(theme, window, cx))
            .into_any_element()
    }

    /// The glass action bar, in place of the tab bar.
    pub(crate) fn select_bar_view(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let selection = self.select.as_ref()?;
        let colors = theme::colors(theme);
        let ids = self.selected_ids();
        let actions = contact_select::select_bar_actions(selection.len(), !self.groups.is_empty());
        let star = self.star_wanted(&ids);
        let body = if actions.is_empty() {
            div()
                .py(px(10.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("Tick contacts to act on them")
                .into_any_element()
        } else {
            let mut row = div()
                .flex()
                .flex_wrap()
                .justify_center()
                .gap(px(6.0))
                .px(px(8.0))
                .py(px(8.0));
            for action in actions {
                let color = if action == SelectAction::Delete {
                    colors.red
                } else {
                    colors.label
                };
                row = row.child(
                    glass_pill(theme, action_label(action, star))
                        .id(action_id(action))
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .text_color(color)
                        .child(
                            svg()
                                .path(action_icon(action).path())
                                .size(px(14.0))
                                .text_color(color),
                        )
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.run_select_action(action, cx)),
                        ),
                );
            }
            row.into_any_element()
        };
        Some(
            div()
                .id("contacts-select-bar")
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

    /// The "Add to group" sheet.
    pub(crate) fn select_sheet_view(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.select_ui
            .sheet
            .set_reduced(window.prefers_reduced_motion());
        self.select_ui.sheet.set_viewport(window.viewport_size());
        if !self.select_ui.sheet.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
        let mode = self.select_ui.mode;
        let format_rows: Vec<AnyElement> = if mode == SheetMode::ShareFormat {
            share_sheet_rows()
                .into_iter()
                .enumerate()
                .map(|(ix, (format, icon))| {
                    tap_row(
                        theme,
                        ("sel-share-row", ix),
                        Some(icon),
                        format.label(),
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| this.share_in_format(format, cx)))
                    .into_any_element()
                })
                .collect()
        } else {
            Vec::new()
        };
        let group_targets: &[GroupTarget] = if mode == SheetMode::Group {
            &self.select_ui.targets
        } else {
            &[]
        };
        let rows: Vec<AnyElement> = group_targets
            .iter()
            .enumerate()
            .map(|(ix, target)| {
                let label: SharedString =
                    format!("{} · {}", target.title, account_label(&target.account)).into();
                tap_row(
                    theme,
                    ("sel-group-row", ix),
                    Some(Icon::LucideUsers),
                    label,
                    Tone::Plain,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.add_selection_to_group(ix, cx)))
                .into_any_element()
            })
            .collect();
        let empty = (mode == SheetMode::Group && rows.is_empty()).then(|| {
            div()
                .px(px(20.0))
                .py(px(8.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("No group fits these contacts. Groups hold contacts of one account.")
        });
        let body = div()
            .flex()
            .flex_col()
            .pb(px(12.0))
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(8.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(sheet_title(mode)),
            )
            .children(format_rows)
            .children(rows)
            .children(empty);
        let entity = cx.entity();
        let drag = entity.clone();
        self.select_ui.sheet.render(
            theme,
            window,
            sheet_title(mode),
            body,
            move |_, cx| {
                entity.update(cx, |this, cx| {
                    this.select_ui.sheet.close();
                    cx.notify();
                })
            },
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.select_ui.sheet.drag_touch(phase, y);
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
    fn share_sheet_lists_both_formats_in_order() {
        let rows = share_sheet_rows();
        let labels: Vec<_> = rows.iter().map(|(f, _)| f.label()).collect();
        assert_eq!(labels, [".vcf file", "Text"]);
    }

    #[test]
    fn sheet_titles_follow_the_mode() {
        assert_eq!(sheet_title(SheetMode::Group), "Add to group");
        assert_eq!(sheet_title(SheetMode::ShareFormat), "Share as");
        assert_eq!(SheetMode::default(), SheetMode::Group);
    }
}
