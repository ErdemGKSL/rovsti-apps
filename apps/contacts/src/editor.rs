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
use rovsti_core::contact_edit::{ContactDraft, DraftField, FieldKind, MAX_FIELDS};
use rovsti_ui::{
    Icon,
    components::{large_title, section},
    theme,
    tokens::{Theme, metrics, typography},
};

use crate::{
    ContactsApp, backend,
    field::{Config, TextField},
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
        self.start_editor(&ContactDraft::blank(), cx);
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
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::save(&draft) })
                .await;
            this.update(cx, |this, cx| match result {
                Ok(id) => {
                    this.editor = None;
                    this.pending_open = Some(id);
                    this.notice = Some("Saved.".into());
                    this.page.restart();
                    this.reload(cx);
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
            .children(editor.error.clone().map(|error| {
                div()
                    .px(px(32.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.red)
                    .child(error)
            }))
            .child(name)
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
