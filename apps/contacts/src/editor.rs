//! Creating and editing a contact inside the app.
//!
//! [`Editor`] holds the form: one text field per input, the kind of each
//! number and email, and the groups ticked. `ContactsApp::editor_view` draws
//! it; `save_editor` cleans and checks the draft with `rovsti_core::contact_edit`
//! and hands it to Android on the background executor.

use std::collections::BTreeSet;

use gpui::{
    AnyElement, AppContext, Context, Entity, InteractiveElement, IntoElement, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::contact_edit::{
    Account, ContactDraft, DraftField, FieldKind, MAX_FIELDS, account_label, retain_account_groups,
};
use rovsti_ui::{
    Icon,
    components::{large_title, section},
    theme,
    tokens::{Theme, metrics, typography},
};

use crate::{
    ContactsApp, backend,
    field::{Config, TextField},
    photos::photo_avatar,
    ui::{Tone, field_box, form_row, pill, tap_row},
};

/// One number or email being edited.
pub(crate) struct FieldRow {
    text: Entity<TextField>,
    kind: FieldKind,
    label: Option<String>,
}

impl FieldRow {
    fn title(&self, cx: &gpui::App) -> SharedString {
        DraftField {
            value: self.text.read(cx).text().to_owned(),
            kind: self.kind,
            label: self.label.clone(),
        }
        .title()
        .into()
    }
}

/// The form for one contact.
pub(crate) struct Editor {
    /// `None` while creating.
    pub(crate) id: Option<i64>,
    account: String,
    starred: bool,
    given: Entity<TextField>,
    family: Entity<TextField>,
    org: Entity<TextField>,
    note: Entity<TextField>,
    numbers: Vec<FieldRow>,
    emails: Vec<FieldRow>,
    groups: BTreeSet<i64>,
    error: Option<SharedString>,
    saving: bool,
    /// A picked image for a contact that is not saved yet; it becomes the
    /// photo once the first save returns the id.
    pending_photo: Option<String>,
}

/// The account a new contact starts in: the remembered one while it is still
/// listed, else the phone (the empty key).
pub(crate) fn default_account(accounts: &[Account], preferred: &str) -> String {
    accounts
        .iter()
        .find(|a| a.key == preferred)
        .map(|a| a.key.clone())
        .unwrap_or_default()
}

/// The account after `current` in `accounts`, wrapping round. An account that
/// is not listed moves to the first one.
pub(crate) fn next_account(accounts: &[Account], current: &str) -> String {
    let next = accounts
        .iter()
        .position(|a| a.key == current)
        .map_or(0, |i| (i + 1) % accounts.len());
    accounts
        .get(next)
        .map(|a| a.key.clone())
        .unwrap_or_default()
}

fn make_field(config: Config, text: &str, cx: &mut Context<ContactsApp>) -> Entity<TextField> {
    cx.new(|cx| TextField::configured(config, text, cx))
}

fn make_rows(
    fields: &[DraftField],
    config: Config,
    cx: &mut Context<ContactsApp>,
) -> Vec<FieldRow> {
    fields
        .iter()
        .map(|f| FieldRow {
            text: make_field(config, &f.value, cx),
            kind: f.kind,
            label: f.label.clone(),
        })
        .collect()
}

impl Editor {
    pub(crate) fn new(draft: &ContactDraft, cx: &mut Context<ContactsApp>) -> Self {
        let mut numbers = make_rows(&draft.numbers, Config::PHONE, cx);
        if numbers.is_empty() {
            numbers = make_rows(&[DraftField::new("", FieldKind::Mobile)], Config::PHONE, cx);
        }
        Self {
            id: draft.id,
            account: draft.account.clone(),
            starred: draft.starred,
            given: make_field(Config::TEXT, &draft.given, cx),
            family: make_field(Config::TEXT, &draft.family, cx),
            org: make_field(Config::TEXT, &draft.org, cx),
            note: make_field(Config::NOTE, &draft.note, cx),
            numbers,
            emails: make_rows(&draft.emails, Config::EMAIL, cx),
            groups: draft.groups.iter().copied().collect(),
            error: None,
            saving: false,
            pending_photo: None,
        }
    }

    /// The form as a draft; not yet cleaned or checked.
    fn draft(&self, cx: &gpui::App) -> ContactDraft {
        let rows = |rows: &[FieldRow]| -> Vec<DraftField> {
            rows.iter()
                .map(|r| DraftField {
                    value: r.text.read(cx).text().to_owned(),
                    kind: r.kind,
                    label: r.label.clone(),
                })
                .collect()
        };
        ContactDraft {
            id: self.id,
            given: self.given.read(cx).text().to_owned(),
            family: self.family.read(cx).text().to_owned(),
            org: self.org.read(cx).text().to_owned(),
            note: self.note.read(cx).text().to_owned(),
            numbers: rows(&self.numbers),
            emails: rows(&self.emails),
            groups: self.groups.iter().copied().collect(),
            starred: self.starred,
            account: self.account.clone(),
        }
    }

    fn rows_mut(&mut self, numbers: bool) -> &mut Vec<FieldRow> {
        if numbers {
            &mut self.numbers
        } else {
            &mut self.emails
        }
    }
}

impl ContactsApp {
    pub(crate) fn open_new_editor(&mut self, cx: &mut Context<Self>) {
        let draft = ContactDraft {
            account: default_account(&self.accounts, &self.save_to),
            ..ContactDraft::blank()
        };
        self.start_editor(&draft, cx);
    }

    /// "Save to" on a new contact: the next account. Group ticks of the old
    /// account go, since a contact only joins the groups of its own account.
    fn cycle_account(&mut self, cx: &mut Context<Self>) {
        let Some(editor) = self.editor.as_mut() else {
            return;
        };
        if editor.id.is_some() {
            return;
        }
        let mut draft = ContactDraft {
            account: next_account(&self.accounts, &editor.account),
            groups: editor.groups.iter().copied().collect(),
            ..ContactDraft::default()
        };
        retain_account_groups(&mut draft, &self.groups);
        editor.account = draft.account;
        editor.groups = draft.groups.into_iter().collect();
        cx.notify();
    }

    /// Opens the form on contact `id`, read fresh so a change made since the
    /// card opened (a star, an edit in another app) is not written back over.
    pub(crate) fn open_editor_for(&mut self, id: i64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::pull_detail(id) })
                .await;
            this.update(cx, |this, cx| match result {
                Ok(draft) => this.start_editor(&draft, cx),
                Err(words) => {
                    this.notice = Some(format!("Can't edit this contact: {words}.").into());
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    fn start_editor(&mut self, draft: &ContactDraft, cx: &mut Context<Self>) {
        self.editor = Some(Editor::new(draft, cx));
        self.notice = None;
        self.page.restart();
        cx.notify();
    }

    pub(crate) fn close_editor(&mut self, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        self.editor = None;
        self.page.restart();
        cx.notify();
    }

    fn cycle_kind(&mut self, numbers: bool, index: usize, cx: &mut Context<Self>) {
        if let Some(row) = self
            .editor
            .as_mut()
            .and_then(|e| e.rows_mut(numbers).get_mut(index))
        {
            let kinds = if numbers {
                &FieldKind::NUMBER_KINDS
            } else {
                &FieldKind::EMAIL_KINDS
            };
            row.kind = row.kind.next_in(kinds);
            row.label = None;
            cx.notify();
        }
    }

    fn add_row(&mut self, numbers: bool, cx: &mut Context<Self>) {
        let config = if numbers {
            Config::PHONE
        } else {
            Config::EMAIL
        };
        let kind = if numbers {
            FieldKind::Mobile
        } else {
            FieldKind::Home
        };
        let text = make_field(config, "", cx);
        if let Some(editor) = self.editor.as_mut() {
            let rows = editor.rows_mut(numbers);
            if rows.len() < MAX_FIELDS {
                rows.push(FieldRow {
                    text,
                    kind,
                    label: None,
                });
            }
        }
        cx.notify();
    }

    fn remove_row(&mut self, numbers: bool, index: usize, cx: &mut Context<Self>) {
        if let Some(editor) = self.editor.as_mut() {
            let rows = editor.rows_mut(numbers);
            if index < rows.len() {
                rows.remove(index);
            }
        }
        cx.notify();
    }

    fn toggle_group(&mut self, id: i64, cx: &mut Context<Self>) {
        if let Some(editor) = self.editor.as_mut()
            && !editor.groups.remove(&id)
        {
            editor.groups.insert(id);
        }
        cx.notify();
    }

    /// Cleans and checks the form, then saves it off the UI thread.
    pub(crate) fn save_editor(&mut self, cx: &mut Context<Self>) {
        let Some(editor) = self.editor.as_mut() else {
            return;
        };
        if editor.saving {
            return;
        }
        let draft = match editor.draft(cx).finish() {
            Ok(draft) => draft,
            Err(error) => {
                editor.error = Some(error.to_string().into());
                cx.notify();
                return;
            }
        };
        editor.error = None;
        editor.saving = true;
        // A new contact's account becomes the default for the next one.
        let remember = editor.id.is_none().then(|| draft.account.clone());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::save(&draft) })
                .await;
            this.update(cx, |this, cx| match result {
                Ok(id) => {
                    if let Some(account) = remember
                        && this.save_to != account
                    {
                        this.save_to = account;
                        this.save_prefs(cx);
                    }
                    this.photo_sheet.close();
                    let photo = this.editor.as_mut().and_then(|e| e.pending_photo.take());
                    this.editor = None;
                    this.pending_open = Some(id);
                    this.notice = Some("Saved.".into());
                    this.page.restart();
                    this.reload(cx);
                    if let Some(path) = photo {
                        this.apply_photo(id, path, cx);
                    }
                }
                Err(words) => {
                    if let Some(editor) = this.editor.as_mut() {
                        editor.saving = false;
                        editor.error = Some(format!("Can't save: {words}.").into());
                    }
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Whether the contact being edited has a photo, or will get one on save.
    fn editor_has_photo(&self) -> bool {
        self.editor.as_ref().is_some_and(|editor| {
            editor.pending_photo.is_some()
                || editor.id.is_some_and(|id| self.photo_uri_of(id).is_some())
        })
    }

    /// The avatar under the title; a tap opens the photo sheet.
    fn photo_head(&self, theme: Theme, editor: &Editor, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let monogram: SharedString = editor
            .draft(cx)
            .display_name()
            .chars()
            .find(|c| c.is_alphanumeric())
            .map(|c| c.to_uppercase().collect::<String>())
            .unwrap_or_default()
            .into();
        let photo = editor
            .id
            .filter(|id| self.photo_uri_of(*id).is_some())
            .and_then(|id| self.photos.get(id));
        let caption = if self.editor_has_photo() {
            "Change photo"
        } else {
            "Add photo"
        };
        div()
            .id("editor-photo")
            .flex()
            .flex_col()
            .items_center()
            .gap(px(8.0))
            .on_click(cx.listener(|this, _, _, cx| this.open_photo_sheet(cx)))
            .child(photo_avatar(theme, monogram, 88.0, colors.accent, photo))
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.accent)
                    .child(caption),
            )
            .into_any_element()
    }

    fn open_photo_sheet(&mut self, cx: &mut Context<Self>) {
        if self.editor.as_ref().is_none_or(|e| e.saving) {
            return;
        }
        self.photo_sheet.open();
        cx.notify();
    }

    pub(crate) fn close_photo_sheet(&mut self, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        cx.notify();
    }

    /// "Choose photo": opens the system picker; the answer is a `document_result`.
    fn choose_photo(&mut self, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        if self.transfer_busy {
            cx.notify();
            return;
        }
        self.transfer_busy = true;
        self.notice = None;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async { backend::start_pick_photo() })
                .await;
            this.update(cx, |this, cx| {
                if let Err(words) = result {
                    this.transfer_busy = false;
                    this.notice = Some(format!("Can't change the photo: {words}.").into());
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// "Remove photo": a saved contact loses it at once, a new one drops the
    /// image it was holding.
    fn remove_photo(&mut self, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        let Some(editor) = self.editor.as_mut() else {
            return;
        };
        match editor.id {
            Some(id) => self.clear_photo(id, cx),
            None => editor.pending_photo = None,
        }
        cx.notify();
    }

    /// An image was picked and copied to `path`: a saved contact gets it now,
    /// a new one holds it until the first save returns the id.
    pub(crate) fn photo_picked(&mut self, path: String, cx: &mut Context<Self>) {
        let Some(editor) = self.editor.as_mut() else {
            return;
        };
        match editor.id {
            Some(id) => self.apply_photo(id, path, cx),
            None => {
                editor.pending_photo = Some(path);
                self.notice = Some("The photo is saved with the contact.".into());
                cx.notify();
            }
        }
    }

    /// The photo sheet over the page, once it is visible.
    pub(crate) fn photo_sheet_view(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.photo_sheet
            .set_reduced(window.prefers_reduced_motion());
        self.photo_sheet.set_viewport(window.viewport_size());
        let want_back = self.photo_sheet.is_open();
        if want_back != self.back_enabled {
            self.back_enabled = want_back;
            window.set_back_enabled(want_back);
        }
        let colors = theme::colors(theme);
        let mut body = div().flex().flex_col().pb(px(12.0)).child(
            div()
                .px(px(20.0))
                .pb(px(8.0))
                .text_size(typography::TITLE3.size)
                .font_weight(typography::TITLE3.weight)
                .text_color(colors.label)
                .child("Contact photo"),
        );
        body = body.child(
            tap_row(
                theme,
                "photo-choose",
                Some(Icon::LucideImagePlus),
                "Choose photo",
                Tone::Plain,
            )
            .on_click(cx.listener(|this, _, _, cx| this.choose_photo(cx))),
        );
        if self.editor_has_photo() {
            body = body.child(
                tap_row(
                    theme,
                    "photo-remove",
                    Some(Icon::LucideTrash2),
                    "Remove photo",
                    Tone::Danger,
                )
                .on_click(cx.listener(|this, _, _, cx| this.remove_photo(cx))),
            );
        }
        let entity = cx.entity();
        let drag = entity.clone();
        self.photo_sheet.render(
            theme,
            window,
            "Contact photo",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_photo_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.photo_sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    /// The form, as the page.
    pub(crate) fn editor_view(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let Some(editor) = &self.editor else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let title = if editor.id.is_some() {
            "Edit Contact"
        } else {
            "New Contact"
        };

        let top = div()
            .flex()
            .items_center()
            .justify_between()
            .px(px(16.0))
            .pt(px(48.0))
            .child(
                pill(theme, "editor-cancel", "Cancel", false)
                    .on_click(cx.listener(|this, _, _, cx| this.close_editor(cx))),
            )
            .child(
                pill(
                    theme,
                    "editor-save",
                    if editor.saving { "Saving…" } else { "Save" },
                    false,
                )
                .on_click(cx.listener(|this, _, _, cx| this.save_editor(cx))),
            );

        let name = section(
            theme,
            Some("Name".into()),
            [
                form_row(theme)
                    .child(field_box(
                        theme,
                        "f-given",
                        &editor.given,
                        "First name",
                        false,
                        window,
                        cx,
                    ))
                    .into_any_element(),
                form_row(theme)
                    .child(field_box(
                        theme,
                        "f-family",
                        &editor.family,
                        "Last name",
                        false,
                        window,
                        cx,
                    ))
                    .into_any_element(),
                form_row(theme)
                    .child(field_box(
                        theme,
                        "f-org",
                        &editor.org,
                        "Organisation",
                        false,
                        window,
                        cx,
                    ))
                    .into_any_element(),
            ],
        );

        let save_to = {
            let label = SharedString::from(account_label(&editor.account));
            let row = if editor.id.is_none() && self.accounts.len() > 1 {
                tap_row(theme, "editor-save-to", None, "Save to", Tone::Plain)
                    .justify_between()
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.accent)
                            .child(label),
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.cycle_account(cx)))
                    .into_any_element()
            } else {
                // An existing contact stays in its account.
                form_row(theme)
                    .justify_between()
                    .child(
                        div()
                            .text_size(typography::BODY.size)
                            .text_color(colors.label)
                            .child("Account"),
                    )
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(label),
                    )
                    .into_any_element()
            };
            section(theme, Some("Save to".into()), [row])
        };

        let photo_head = self.photo_head(theme, editor, cx);
        let numbers = self.field_section(theme, true, window, cx);
        let emails = self.field_section(theme, false, window, cx);

        let groups: Vec<AnyElement> = self
            .groups
            .iter()
            .filter(|g| g.account == editor.account)
            .enumerate()
            .map(|(i, group)| {
                let id = group.id;
                let selected = editor.groups.contains(&id);
                tap_row(
                    theme,
                    ("editor-group", i),
                    None,
                    SharedString::from(group.title.clone()),
                    Tone::Plain,
                )
                .justify_between()
                .children(selected.then(|| {
                    svg()
                        .path(Icon::LucideCheck.path())
                        .size(px(18.0))
                        .text_color(colors.accent)
                }))
                .on_click(cx.listener(move |this, _, _, cx| this.toggle_group(id, cx)))
                .into_any_element()
            })
            .collect();

        let note = section(
            theme,
            Some("Note".into()),
            [form_row(theme)
                .child(field_box(
                    theme,
                    "f-note",
                    &editor.note,
                    "Note",
                    true,
                    window,
                    cx,
                ))
                .into_any_element()],
        );

        let delete = editor.id.map(|id| {
            let name = SharedString::from(editor.draft(cx).display_name());
            section(
                theme,
                None,
                [tap_row(
                    theme,
                    "editor-delete",
                    Some(Icon::LucideTrash2),
                    "Delete Contact",
                    Tone::Danger,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.ask_delete_contact(id, name.clone(), cx);
                }))
                .into_any_element()],
            )
        });

        div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .pb(px(24.0))
            .child(top)
            .child(large_title(theme, title, None))
            .child(photo_head)
            .children(editor.error.clone().map(|error| {
                div()
                    .px(px(32.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.red)
                    .child(error)
            }))
            .child(name)
            .child(save_to)
            .child(numbers)
            .child(emails)
            .children((!groups.is_empty()).then(|| section(theme, Some("Groups".into()), groups)))
            .child(note)
            .children(delete)
            .into_any_element()
    }

    /// The Phone or Email section: kind button, box and remove button per row.
    fn field_section(
        &self,
        theme: Theme,
        numbers: bool,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let Some(editor) = &self.editor else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let (rows, header, placeholder, kind_key, field_key, remove_key, add_key, add_label) =
            if numbers {
                (
                    &editor.numbers,
                    "Phone",
                    "Phone number",
                    "num-kind",
                    "num-field",
                    "num-remove",
                    "num-add",
                    "Add phone",
                )
            } else {
                (
                    &editor.emails,
                    "Email",
                    "Email address",
                    "mail-kind",
                    "mail-field",
                    "mail-remove",
                    "mail-add",
                    "Add email",
                )
            };
        let mut children: Vec<AnyElement> = rows
            .iter()
            .enumerate()
            .map(|(i, row)| {
                let can_remove = numbers && rows.len() > 1 || !numbers;
                form_row(theme)
                    .child(
                        div()
                            .id((kind_key, i))
                            .flex_none()
                            .w(px(64.0))
                            .h(metrics::MIN_HIT)
                            .flex()
                            .items_center()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.accent)
                            .child(row.title(cx))
                            .on_click(
                                cx.listener(move |this, _, _, cx| this.cycle_kind(numbers, i, cx)),
                            ),
                    )
                    .child(field_box(
                        theme,
                        (field_key, i),
                        &row.text,
                        placeholder,
                        false,
                        window,
                        cx,
                    ))
                    .children(can_remove.then(|| {
                        div()
                            .id((remove_key, i))
                            .flex_none()
                            .size(metrics::MIN_HIT)
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(
                                svg()
                                    .path(Icon::LucideX.path())
                                    .size(px(16.0))
                                    .text_color(colors.secondary_label),
                            )
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.remove_row(numbers, i, cx);
                            }))
                    }))
                    .into_any_element()
            })
            .collect();
        if rows.len() < MAX_FIELDS {
            children.push(
                tap_row(
                    theme,
                    add_key,
                    Some(Icon::LucidePlus),
                    add_label,
                    Tone::Accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.add_row(numbers, cx)))
                .into_any_element(),
            );
        }
        section(theme, Some(header.into()), children).into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account(key: &str) -> Account {
        Account {
            key: key.to_owned(),
            ..Account::default()
        }
    }

    fn listed() -> Vec<Account> {
        vec![account(""), account("com.google|a@b.com")]
    }

    #[test]
    fn the_remembered_account_is_the_default_while_listed() {
        assert_eq!(
            default_account(&listed(), "com.google|a@b.com"),
            "com.google|a@b.com"
        );
        assert_eq!(default_account(&listed(), "com.google|gone@b.com"), "");
        assert_eq!(default_account(&[], "com.google|a@b.com"), "");
    }

    #[test]
    fn save_to_cycles_and_wraps() {
        let accounts = listed();
        assert_eq!(next_account(&accounts, ""), "com.google|a@b.com");
        assert_eq!(next_account(&accounts, "com.google|a@b.com"), "");
        assert_eq!(next_account(&accounts, "org.gone|x"), "");
        assert_eq!(next_account(&[], "x"), "");
    }
}
