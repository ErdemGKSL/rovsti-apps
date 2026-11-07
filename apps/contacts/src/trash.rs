//! The Trash page, "Scan QR code" and the code that keeps deleted contacts.
//!
//! What the Trash holds and how it ages live in `rovsti_core::contact_trash`,
//! the file in `trash_store`; this module is the page (Settings, "Trash"),
//! its sheet and select mode, and the glue for restoring and deleting for
//! good. The decisions that can be tested without a window are plain
//! functions at the top.

use std::path::PathBuf;

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::{
    avatar,
    contact_edit::{ContactDraft, DraftField, FieldKind},
    contact_scan::Scanned,
    contact_trash::{self, Trash},
    multi_select::{self, Selection},
};
use rovsti_ui::{
    Icon,
    components::{flat_glass, glass_icon_button, glass_pill, large_title, list_row, section},
    sheet::Sheet,
    theme::{self, hue_pair},
    tokens::{Theme, radius, typography},
};

use crate::{
    ContactsApp, backend,
    confirm::Confirm,
    editor,
    photos::photo_avatar,
    trash_store,
    ui::{Tone, tap_row},
    whatsapp::now_ms,
};

/// An action of the Trash page on one or more entries.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TrashAction {
    Restore,
    DeleteForever,
}

/// The actions offered for `selected` ticked entries: none for none.
pub(crate) fn trash_actions(selected: usize) -> Vec<TrashAction> {
    if selected == 0 {
        Vec::new()
    } else {
        vec![TrashAction::Restore, TrashAction::DeleteForever]
    }
}

/// Where a scanned code goes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ScanRoute {
    /// The new-contact form, prefilled.
    Editor,
    /// The import card (several contacts).
    Import,
    /// A pill only.
    Notice,
}

/// The route for what a picture held.
pub(crate) fn scanned_route(scanned: &Scanned) -> ScanRoute {
    match scanned {
        Scanned::Cards(cards) => match cards.len() {
            0 => ScanRoute::Notice,
            1 => ScanRoute::Editor,
            _ => ScanRoute::Import,
        },
        Scanned::Number(_) | Scanned::Email(_) => ScanRoute::Editor,
        Scanned::Nothing => ScanRoute::Notice,
    }
}

/// The prefilled form for a scan that routes to the editor.
pub(crate) fn scanned_draft(scanned: &Scanned) -> Option<ContactDraft> {
    match scanned {
        Scanned::Cards(cards) if cards.len() == 1 => cards.first().cloned(),
        Scanned::Number(number) => Some(ContactDraft {
            numbers: vec![DraftField::new(number.clone(), FieldKind::Mobile)],
            ..ContactDraft::blank()
        }),
        Scanned::Email(email) => Some(ContactDraft {
            numbers: Vec::new(),
            emails: vec![DraftField::new(email.clone(), FieldKind::Home)],
            ..ContactDraft::blank()
        }),
        _ => None,
    }
}

/// The sentence of the card that asks before deleting without a copy.
pub(crate) fn no_copy_text(count: usize) -> (String, String) {
    (
        "Delete anyway?".to_owned(),
        if count == 1 {
            "Can't keep a copy in Trash. The contact is deleted for good.".to_owned()
        } else {
            format!("Can't keep a copy in Trash. {count} contacts are deleted for good.")
        },
    )
}

/// One row of the page: the entry key, a name and how long it stays.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct TrashRow {
    pub(crate) key: u64,
    pub(crate) name: String,
    pub(crate) left: String,
}

/// The rows for `trash`, newest deleted first.
pub(crate) fn rows(trash: &Trash, now_ms: i64) -> Vec<TrashRow> {
    trash
        .sorted()
        .into_iter()
        .map(|entry| {
            let mut name = entry.draft.display_name();
            if name.trim().is_empty() {
                name = entry
                    .draft
                    .numbers
                    .first()
                    .or(entry.draft.emails.first())
                    .map_or_else(|| "No name".to_owned(), |f| f.value.clone());
            }
            TrashRow {
                key: entry.key,
                name,
                left: contact_trash::left_text(entry.deleted_ms, now_ms),
            }
        })
        .collect()
}

fn action_label(action: TrashAction) -> &'static str {
    match action {
        TrashAction::Restore => "Restore",
        TrashAction::DeleteForever => "Delete forever",
    }
}

/// Copies saved to the Trash for a delete in flight: the file to write, the
/// Trash as it will be, and which contact got which entry.
pub(crate) struct Kept {
    pub(crate) dir: PathBuf,
    pub(crate) snapshot: Trash,
    pub(crate) entries: Vec<(i64, u64)>,
}

/// The Trash page and what it opens.
#[derive(Default)]
pub(crate) struct TrashUi {
    pub(crate) open: bool,
    pub(crate) select: Option<Selection<u64>>,
    pub(crate) sheet: Sheet,
    /// The entry the sheet acts on.
    target: Option<u64>,
    /// A restore is running.
    busy: bool,
}

impl ContactsApp {
    /// Settings, "Trash": the page opens (and old entries go).
    pub(crate) fn set_trash_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.trash_ui.open = open;
        self.trash_ui.select = None;
        self.trash_ui.sheet.close();
        self.clear_notice();
        self.page.restart();
        if open {
            self.purge_trash(cx);
        }
        cx.notify();
    }

    /// Drops the entries past their 30 days, writing the file if any went.
    pub(crate) fn purge_trash(&mut self, cx: &mut Context<Self>) {
        if self.trash.purge_expired(now_ms()) > 0 {
            self.save_trash_quietly(cx);
        }
    }

    /// Writes the Trash in the background; a failure only goes to the log.
    pub(crate) fn save_trash_quietly(&self, cx: &mut Context<Self>) {
        let Some(dir) = self.prefs_dir.clone() else {
            return;
        };
        let snapshot = self.trash.clone();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = trash_store::trash_save(&dir, &snapshot) {
                    log::warn!("could not save the Trash: {error}");
                }
            })
            .detach();
    }

    /// Writes the Trash, then shows `done` (or why it could not be saved).
    fn persist_trash(&mut self, done: String, cx: &mut Context<Self>) {
        let Some(dir) = self.prefs_dir.clone() else {
            self.notice = Some(done.into());
            cx.notify();
            return;
        };
        let snapshot = self.trash.clone();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { trash_store::trash_save(&dir, &snapshot) })
                .await;
            this.update(cx, |this, cx| {
                this.notice = Some(match result {
                    Ok(()) => done.into(),
                    Err(error) => {
                        log::warn!("could not save the Trash: {error}");
                        "Can't update Trash on this phone.".into()
                    }
                });
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Adds `drafts` (the contacts about to be deleted) to the Trash in
    /// memory. `None` when there is nowhere to keep them.
    pub(crate) fn keep_copies(&mut self, drafts: Vec<ContactDraft>) -> Option<Kept> {
        let dir = self.prefs_dir.clone()?;
        let ids: Vec<i64> = drafts.iter().filter_map(|d| d.id).collect();
        let keys = self.trash.push(drafts, now_ms());
        Some(Kept {
            dir,
            snapshot: self.trash.clone(),
            entries: ids.into_iter().zip(keys).collect(),
        })
    }

    // ---- restore and delete for good ----

    /// Puts the entries back into the address book.
    fn restore_entries(&mut self, keys: Vec<u64>, cx: &mut Context<Self>) {
        if self.trash_ui.busy {
            return;
        }
        let drafts: Vec<ContactDraft> = self
            .trash
            .entries
            .iter()
            .filter(|e| keys.contains(&e.key))
            .map(|e| e.draft.clone())
            .collect();
        if drafts.is_empty() {
            return;
        }
        let fallback = editor::default_account(&self.accounts, &self.save_to);
        let drafts = contact_trash::restore_drafts(drafts, &self.groups, &self.accounts, &fallback);
        self.trash_ui.busy = true;
        self.trash_ui.sheet.close();
        self.notice = Some("Restoring…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::add_contacts(drafts) })
                .await;
            this.update(cx, |this, cx| {
                this.trash_ui.busy = false;
                match result {
                    Ok(added) => {
                        this.trash.remove(&keys);
                        this.trash_ui.select = None;
                        this.persist_trash(contact_trash::restored_notice(added as usize), cx);
                        this.reload(cx);
                    }
                    // The entries stay in the Trash.
                    Err(words) => {
                        this.notice = Some(format!("Can't restore: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Removes the entries for good (after the owner confirmed).
    pub(crate) fn delete_forever(&mut self, keys: Vec<u64>, cx: &mut Context<Self>) {
        let gone = self.trash.remove(&keys);
        self.trash_ui.select = None;
        self.trash_ui.sheet.close();
        self.persist_trash(forever_notice(gone), cx);
        cx.notify();
    }

    /// Empties the Trash (after the owner confirmed).
    pub(crate) fn empty_trash(&mut self, cx: &mut Context<Self>) {
        let gone = self.trash.clear();
        self.trash_ui.select = None;
        self.trash_ui.sheet.close();
        self.persist_trash(forever_notice(gone), cx);
        cx.notify();
    }

    fn ask_forever(&mut self, keys: Vec<u64>, cx: &mut Context<Self>) {
        if keys.is_empty() {
            return;
        }
        self.trash_ui.sheet.close();
        self.confirm = Some(Confirm::DeleteForever { keys });
        cx.notify();
    }

    fn run_trash_action(&mut self, action: TrashAction, cx: &mut Context<Self>) {
        let keys: Vec<u64> = self
            .trash_ui
            .select
            .iter()
            .flat_map(|s| s.keys().copied())
            .collect();
        match action {
            TrashAction::Restore => self.restore_entries(keys, cx),
            TrashAction::DeleteForever => self.ask_forever(keys, cx),
        }
    }

    fn open_entry_sheet(&mut self, key: u64, cx: &mut Context<Self>) {
        self.trash_ui.target = Some(key);
        self.trash_ui.sheet.open();
        cx.notify();
    }

    fn toggle_entry(&mut self, key: u64, cx: &mut Context<Self>) {
        if let Some(selection) = self.trash_ui.select.as_mut() {
            selection.toggle(key);
        }
        cx.notify();
    }

    pub(crate) fn close_trash_sheet(&mut self, cx: &mut Context<Self>) {
        self.trash_ui.sheet.close();
        cx.notify();
    }

    pub(crate) fn leave_trash_select(&mut self, cx: &mut Context<Self>) {
        self.trash_ui.select = None;
        cx.notify();
    }

    // ---- views ----

    /// The Settings row with the count.
    pub(crate) fn trash_entry(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        list_row(
            theme,
            None,
            "Trash",
            None,
            Some(self.trash.entries.len().to_string().into()),
        )
        .id("trash-open")
        .on_click(cx.listener(|this, _, _, cx| this.set_trash_open(true, cx)))
        .into_any_element()
    }

    /// The page: deleted contacts, newest first.
    pub(crate) fn trash_page(
        &mut self,
        window: &Window,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let items = rows(&self.trash, now_ms());
        let selecting = self.trash_ui.select.as_ref().map(Selection::len);
        let top = if selecting.is_some() {
            div().px(px(16.0)).pt(px(48.0)).flex().child(
                glass_pill(theme, "Cancel")
                    .id("trash-cancel")
                    .on_click(cx.listener(|this, _, _, cx| this.leave_trash_select(cx))),
            )
        } else {
            div().px(px(8.0)).pt(px(48.0)).child(
                glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                    .id("trash-back")
                    .on_click(cx.listener(|this, _, _, cx| this.set_trash_open(false, cx))),
            )
        };
        let title = match selecting {
            Some(n) => multi_select::title(n),
            None => "Trash".to_owned(),
        };
        let trailing = (selecting.is_none() && !items.is_empty()).then(|| {
            glass_pill(theme, "Select")
                .id("trash-select")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.trash_ui.select = Some(Selection::new());
                    cx.notify();
                }))
                .into_any_element()
        });
        let body = if items.is_empty() {
            rovsti_ui::components::empty_state(
                theme,
                "Trash Is Empty",
                "Deleted contacts stay here for 30 days.",
            )
            .into_any_element()
        } else {
            let list: Vec<AnyElement> = items
                .iter()
                .enumerate()
                .map(|(n, row)| self.trash_item(theme, row, n, selecting.is_some(), cx))
                .collect();
            let note = div()
                .px(px(32.0))
                .py(px(16.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.secondary_label)
                .child("Deleted contacts stay here for 30 days, then they are gone for good.");
            let mut page = div().flex().flex_col().child(note);
            page = page.child(section(theme, None, list));
            if selecting.is_none() {
                page = page.child(
                    div().pt(px(24.0)).child(section(
                        theme,
                        None,
                        [tap_row(
                            theme,
                            "trash-empty",
                            Some(Icon::LucideTrash2),
                            "Empty Trash",
                            Tone::Danger,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.confirm = Some(Confirm::EmptyTrash {
                                count: this.trash.entries.len(),
                            });
                            cx.notify();
                        }))
                        .into_any_element()],
                    )),
                );
            }
            page.into_any_element()
        };
        div()
            .child(top)
            .child(large_title(window, theme, title, trailing))
            .child(body)
            .into_any_element()
    }

    fn trash_item(
        &self,
        theme: Theme,
        row: &TrashRow,
        n: usize,
        selecting: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let key = row.key;
        let mono = avatar::monogram(&row.name)
            .map(String::from)
            .unwrap_or_default();
        let tint = hue_pair(theme, avatar::hue_index(i64::try_from(key).ok())).0;
        let ticked = selecting.then(|| {
            self.trash_ui
                .select
                .as_ref()
                .is_some_and(|s| s.contains(&key))
        });
        list_row(
            theme,
            Some(photo_avatar(theme, mono, 40.0, tint, None).into_any_element()),
            row.name.clone(),
            Some(row.left.clone().into()),
            None,
        )
        .children(ticked.map(|on| crate::select::check_mark(theme, on)))
        .id(("trash-row", n))
        .on_click(cx.listener(move |this, _, _, cx| {
            if this.trash_ui.select.is_some() {
                this.toggle_entry(key, cx);
            } else {
                this.open_entry_sheet(key, cx);
            }
        }))
        .into_any_element()
    }

    /// Restore and Delete forever for the ticked entries, in place of the tab bar.
    pub(crate) fn trash_bar_view(
        &self,
        safe_bottom: f32,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let selection = self.trash_ui.select.as_ref()?;
        if !self.trash_ui.open {
            return None;
        }
        let colors = theme::colors(theme);
        let actions = trash_actions(selection.len());
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
                let (icon, color, id) = match action {
                    TrashAction::Restore => {
                        (Icon::LucideArchiveRestore, colors.label, "trash-restore")
                    }
                    TrashAction::DeleteForever => (Icon::LucideTrash, colors.red, "trash-forever"),
                };
                row = row.child(
                    glass_pill(theme, action_label(action))
                        .id(id)
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .text_color(color)
                        .child(svg().path(icon.path()).size(px(14.0)).text_color(color))
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.run_trash_action(action, cx)),
                        ),
                );
            }
            row.into_any_element()
        };
        Some(
            div()
                .id("trash-select-bar")
                .absolute()
                .bottom(px(rovsti_ui::layout::geom::tab_bar_bottom(safe_bottom)))
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

    /// The sheet of one entry: Restore and Delete forever.
    pub(crate) fn trash_sheet_view(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.trash_ui
            .sheet
            .set_reduced(window.prefers_reduced_motion());
        self.trash_ui.sheet.set_viewport(window.viewport_size());
        if !self.trash_ui.sheet.is_visible() {
            return None;
        }
        let key = self.trash_ui.target?;
        let row = rows(&self.trash, now_ms())
            .into_iter()
            .find(|r| r.key == key)?;
        let colors = theme::colors(theme);
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
                    .child(SharedString::from(row.name)),
            )
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(10.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(SharedString::from(row.left)),
            )
            .child(
                tap_row(
                    theme,
                    "trash-sheet-restore",
                    Some(Icon::LucideArchiveRestore),
                    "Restore",
                    Tone::Accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.restore_entries(vec![key], cx))),
            )
            .child(
                tap_row(
                    theme,
                    "trash-sheet-forever",
                    Some(Icon::LucideTrash),
                    "Delete forever",
                    Tone::Danger,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.ask_forever(vec![key], cx))),
            );
        let entity = cx.entity();
        let drag = entity.clone();
        self.trash_ui.sheet.render(
            theme,
            window,
            "Deleted contact",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_trash_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.trash_ui.sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    /// Whether the page's select bar replaces the tab bar.
    pub(crate) fn trash_selecting(&self) -> bool {
        self.trash_ui.open && self.trash_ui.select.is_some()
    }
}

/// The pill after entries were deleted for good.
fn forever_notice(count: usize) -> String {
    if count == 1 {
        "Deleted for good".to_owned()
    } else {
        format!("Deleted {count} contacts for good")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn card(name: &str) -> ContactDraft {
        ContactDraft {
            id: Some(1),
            given: name.to_owned(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn actions_need_a_tick() {
        assert!(trash_actions(0).is_empty());
        assert_eq!(
            trash_actions(1),
            [TrashAction::Restore, TrashAction::DeleteForever]
        );
        assert_eq!(trash_actions(7), trash_actions(1));
    }

    #[test]
    fn scans_are_routed_by_what_they_hold() {
        assert_eq!(
            scanned_route(&Scanned::Cards(vec![card("A")])),
            ScanRoute::Editor
        );
        assert_eq!(
            scanned_route(&Scanned::Cards(vec![card("A"), card("B")])),
            ScanRoute::Import
        );
        assert_eq!(
            scanned_route(&Scanned::Cards(Vec::new())),
            ScanRoute::Notice
        );
        assert_eq!(
            scanned_route(&Scanned::Number("+90 532 000 00 00".into())),
            ScanRoute::Editor
        );
        assert_eq!(
            scanned_route(&Scanned::Email("a@b.c".into())),
            ScanRoute::Editor
        );
        assert_eq!(scanned_route(&Scanned::Nothing), ScanRoute::Notice);
    }

    #[test]
    fn a_scanned_number_or_email_fills_one_field() {
        let number = scanned_draft(&Scanned::Number("0532".into())).expect("draft");
        assert_eq!(number.numbers.len(), 1);
        assert_eq!(number.numbers[0].value, "0532");
        assert!(number.emails.is_empty());
        let email = scanned_draft(&Scanned::Email("a@b.c".into())).expect("draft");
        assert_eq!(email.emails[0].value, "a@b.c");
        assert!(email.numbers.is_empty());
        assert_eq!(
            scanned_draft(&Scanned::Cards(vec![card("Ada")]))
                .expect("draft")
                .given,
            "Ada"
        );
        assert!(scanned_draft(&Scanned::Cards(vec![card("A"), card("B")])).is_none());
        assert!(scanned_draft(&Scanned::Nothing).is_none());
    }

    #[test]
    fn rows_are_newest_first_and_say_how_long_is_left() {
        let day = 86_400_000;
        let mut trash = Trash::default();
        trash.push(vec![card("Old")], 0);
        trash.push(vec![card("New")], 10 * day);
        let got = rows(&trash, 10 * day);
        let names: Vec<_> = got.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(names, ["New", "Old"]);
        assert_eq!(got[0].left, "Deleted today");
        assert_eq!(got[1].left, "20 days left");
    }

    #[test]
    fn a_nameless_entry_shows_its_number() {
        let mut trash = Trash::default();
        trash.push(
            vec![ContactDraft {
                numbers: vec![DraftField::new("0532", FieldKind::Mobile)],
                ..ContactDraft::default()
            }],
            0,
        );
        trash.push(vec![ContactDraft::default()], 1);
        let got = rows(&trash, 1);
        assert_eq!(got[0].name, "No name");
        assert_eq!(got[1].name, "0532");
    }

    #[test]
    fn the_no_copy_card_counts() {
        assert_eq!(no_copy_text(1).0, "Delete anyway?");
        assert!(no_copy_text(1).1.starts_with("Can't keep a copy in Trash."));
        assert!(no_copy_text(3).1.contains("3 contacts"));
    }

    #[test]
    fn forever_notices_count() {
        assert_eq!(forever_notice(1), "Deleted for good");
        assert_eq!(forever_notice(4), "Deleted 4 contacts for good");
    }
}
