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
use rovsti_core::{
    contact_dates, contact_edit,
    contact_edit::{
        Account, ContactDraft, DraftEvent, DraftField, DraftRelation, EventKind, FieldKind,
        MAX_FIELDS, RelationKind, account_label, retain_account_groups,
    },
};
use rovsti_ui::{
    Icon,
    components::{large_title, section},
    field::TextField,
    theme,
    tokens::{Theme, metrics, typography},
};

use crate::{
    ContactsApp, EditUndo, NoticeAction, backend,
    photo_crop::PhotoSource,
    photos::photo_avatar,
    ui::{Config, Tone, field_box, form_row, pill, tap_row},
};

/// One number or email being edited.
pub(crate) struct FieldRow {
    text: Entity<TextField>,
    kind: FieldKind,
    label: Option<String>,
    /// The entry's default flag, kept as it was read and written back unchanged.
    primary: bool,
}

impl FieldRow {
    fn title(&self, cx: &gpui::App) -> SharedString {
        DraftField {
            value: self.text.read(cx).text().to_owned(),
            kind: self.kind,
            label: self.label.clone(),
            primary: false,
        }
        .title()
        .into()
    }
}

/// Which list of rows a button works on.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Set {
    Numbers,
    Emails,
    Addresses,
    Websites,
}

impl Set {
    fn config(self) -> Config {
        match self {
            Self::Numbers => Config::PHONE,
            Self::Emails => Config::EMAIL,
            Self::Addresses => Config::NOTE,
            Self::Websites => Config::TEXT,
        }
    }

    fn kinds(self) -> &'static [FieldKind; 3] {
        match self {
            Self::Numbers => &FieldKind::NUMBER_KINDS,
            Self::Emails => &FieldKind::EMAIL_KINDS,
            // A website has no kind; the row shows a fixed "Link" tag.
            Self::Addresses | Self::Websites => &FieldKind::ADDRESS_KINDS,
        }
    }

    fn first_kind(self) -> FieldKind {
        self.kinds()[0]
    }
}

/// One dated event being edited: the box holds `YYYY-MM-DD` or `MM-DD`.
pub(crate) struct EventRow {
    text: Entity<TextField>,
    kind: EventKind,
    label: Option<String>,
}

impl EventRow {
    fn title(&self) -> SharedString {
        DraftEvent {
            date: String::new(),
            kind: self.kind,
            label: self.label.clone(),
        }
        .title()
        .into()
    }
}

/// One related person being edited: name box, kind, and a label box that
/// shows for the custom kind.
pub(crate) struct RelationRow {
    name: Entity<TextField>,
    kind: RelationKind,
    label: Entity<TextField>,
}

/// Whether the Phonetic name fields show: when either part is set, or after
/// the owner asked for them.
pub(crate) fn phonetic_visible(given: &str, family: &str, asked: bool) -> bool {
    asked || !given.trim().is_empty() || !family.trim().is_empty()
}

/// Whether a relation row shows its label box: only for the custom kind.
pub(crate) fn relation_label_visible(kind: RelationKind) -> bool {
    kind == RelationKind::Custom
}

/// The form for one contact.
pub(crate) struct Editor {
    /// `None` while creating.
    pub(crate) id: Option<i64>,
    account: String,
    /// Kept from the loaded contact until the form edits them, so saving
    /// does not drop them.
    phonetic_given: Entity<TextField>,
    phonetic_family: Entity<TextField>,
    /// The owner asked for the Phonetic name fields with "Add phonetic name".
    phonetic_asked: bool,
    relations: Vec<RelationRow>,
    starred: bool,
    given: Entity<TextField>,
    family: Entity<TextField>,
    org: Entity<TextField>,
    nickname: Entity<TextField>,
    title: Entity<TextField>,
    note: Entity<TextField>,
    numbers: Vec<FieldRow>,
    emails: Vec<FieldRow>,
    groups: BTreeSet<i64>,
    error: Option<SharedString>,
    saving: bool,
    /// A picked image for a contact that is not saved yet; it becomes the
    /// photo once the first save returns the id.
    pub(crate) pending_photo: Option<(String, rovsti_core::contact_photo::CropView)>,
    /// The form as it opened: leaving with anything else asks first.
    initial: ContactDraft,
    events: Vec<EventRow>,
    addresses: Vec<FieldRow>,
    websites: Vec<FieldRow>,
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
    cx.new(|cx| config.build(text, cx))
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
            primary: f.primary,
        })
        .collect()
}

impl Editor {
    pub(crate) fn new(draft: &ContactDraft, cx: &mut Context<ContactsApp>) -> Self {
        let mut numbers = make_rows(&draft.numbers, Config::PHONE, cx);
        if numbers.is_empty() {
            numbers = make_rows(&[DraftField::new("", FieldKind::Mobile)], Config::PHONE, cx);
        }
        let mut editor = Self {
            id: draft.id,
            account: draft.account.clone(),
            phonetic_given: make_field(Config::TEXT, &draft.phonetic_given, cx),
            phonetic_family: make_field(Config::TEXT, &draft.phonetic_family, cx),
            phonetic_asked: false,
            relations: draft
                .relations
                .iter()
                .map(|r| RelationRow {
                    name: make_field(Config::TEXT, &r.name, cx),
                    kind: r.kind,
                    label: make_field(Config::TEXT, &r.label, cx),
                })
                .collect(),
            starred: draft.starred,
            given: make_field(Config::TEXT, &draft.given, cx),
            family: make_field(Config::TEXT, &draft.family, cx),
            org: make_field(Config::TEXT, &draft.org, cx),
            nickname: make_field(Config::TEXT, &draft.nickname, cx),
            title: make_field(Config::TEXT, &draft.title, cx),
            note: make_field(Config::NOTE, &draft.note, cx),
            numbers,
            emails: make_rows(&draft.emails, Config::EMAIL, cx),
            groups: draft.groups.iter().copied().collect(),
            error: None,
            saving: false,
            pending_photo: None,
            initial: ContactDraft::default(),
            events: draft
                .events
                .iter()
                .map(|e| EventRow {
                    text: make_field(Config::TEXT, &contact_dates::input_text(&e.date), cx),
                    kind: e.kind,
                    label: e.label.clone(),
                })
                .collect(),
            addresses: make_rows(&draft.addresses, Config::NOTE, cx),
            websites: make_rows(
                &draft
                    .websites
                    .iter()
                    .map(|w| DraftField::new(w, FieldKind::Home))
                    .collect::<Vec<_>>(),
                Config::TEXT,
                cx,
            ),
        };
        // Read back from the form (with its blank first number), so an
        // untouched form compares equal.
        editor.initial = editor.draft(cx);
        editor
    }

    /// The first-name box, for the screenshot rig to focus.
    #[cfg(all(test, feature = "shots"))]
    pub(crate) fn given_focus(&self, cx: &gpui::App) -> gpui::FocusHandle {
        self.given.read(cx).focus_handle()
    }

    /// Whether the owner changed anything since the form opened.
    fn is_dirty(&self, cx: &gpui::App) -> bool {
        self.pending_photo.is_some() || self.draft(cx) != self.initial
    }

    /// The form as a draft; not yet cleaned or checked.
    fn draft(&self, cx: &gpui::App) -> ContactDraft {
        let rows = |rows: &[FieldRow]| -> Vec<DraftField> {
            rows.iter()
                .map(|r| DraftField {
                    value: r.text.read(cx).text().to_owned(),
                    kind: r.kind,
                    label: r.label.clone(),
                    primary: r.primary,
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
            addresses: rows(&self.addresses),
            events: self
                .events
                .iter()
                .map(|e| DraftEvent {
                    date: contact_dates::normalize_input(e.text.read(cx).text()),
                    kind: e.kind,
                    label: e.label.clone(),
                })
                .collect(),
            groups: self.groups.iter().copied().collect(),
            starred: self.starred,
            account: self.account.clone(),
            phonetic_given: self.phonetic_given.read(cx).text().to_owned(),
            phonetic_family: self.phonetic_family.read(cx).text().to_owned(),
            relations: self
                .relations
                .iter()
                .map(|r| DraftRelation {
                    name: r.name.read(cx).text().to_owned(),
                    kind: r.kind,
                    label: r.label.read(cx).text().to_owned(),
                })
                .collect(),
            nickname: self.nickname.read(cx).text().to_owned(),
            title: self.title.read(cx).text().to_owned(),
            websites: self
                .websites
                .iter()
                .map(|r| r.text.read(cx).text().to_owned())
                .collect(),
        }
    }

    fn rows_mut(&mut self, set: Set) -> &mut Vec<FieldRow> {
        match set {
            Set::Numbers => &mut self.numbers,
            Set::Emails => &mut self.emails,
            Set::Addresses => &mut self.addresses,
            Set::Websites => &mut self.websites,
        }
    }
}

impl ContactsApp {
    pub(crate) fn open_new_editor(&mut self, cx: &mut Context<Self>) {
        self.open_new_editor_with(ContactDraft::blank(), cx);
    }

    /// New Contact prefilled with `draft`, saved to the default account.
    pub(crate) fn open_new_editor_with(&mut self, mut draft: ContactDraft, cx: &mut Context<Self>) {
        draft.account = default_account(&self.accounts, &self.save_to);
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
        self.clear_notice();
        self.page.restart();
        cx.notify();
    }

    /// Cancel or Back on the form: closes it, after asking when there are
    /// unsaved changes.
    pub(crate) fn leave_editor(&mut self, cx: &mut Context<Self>) {
        let dirty = self
            .editor
            .as_ref()
            .is_some_and(|editor| !editor.saving && editor.is_dirty(cx));
        if dirty {
            self.confirm = Some(crate::confirm::Confirm::DiscardEdits);
            cx.notify();
        } else {
            self.close_editor(cx);
        }
    }

    pub(crate) fn close_editor(&mut self, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        self.editor = None;
        self.page.restart();
        cx.notify();
    }

    fn cycle_kind(&mut self, set: Set, index: usize, cx: &mut Context<Self>) {
        if let Some(row) = self
            .editor
            .as_mut()
            .and_then(|e| e.rows_mut(set).get_mut(index))
        {
            row.kind = row.kind.next_in(set.kinds());
            row.label = None;
            cx.notify();
        }
    }

    fn add_row(&mut self, set: Set, cx: &mut Context<Self>) {
        let text = make_field(set.config(), "", cx);
        if let Some(editor) = self.editor.as_mut() {
            let rows = editor.rows_mut(set);
            if rows.len() < MAX_FIELDS {
                rows.push(FieldRow {
                    text,
                    kind: set.first_kind(),
                    label: None,
                    primary: false,
                });
            }
        }
        cx.notify();
    }

    fn remove_row(&mut self, set: Set, index: usize, cx: &mut Context<Self>) {
        if let Some(editor) = self.editor.as_mut() {
            let rows = editor.rows_mut(set);
            if index < rows.len() {
                rows.remove(index);
            }
        }
        cx.notify();
    }

    fn cycle_event_kind(&mut self, index: usize, cx: &mut Context<Self>) {
        if let Some(row) = self.editor.as_mut().and_then(|e| e.events.get_mut(index)) {
            row.kind = contact_dates::next_event_kind(row.kind);
            row.label = None;
            cx.notify();
        }
    }

    fn cycle_relation_kind(&mut self, index: usize, cx: &mut Context<Self>) {
        if let Some(row) = self
            .editor
            .as_mut()
            .and_then(|e| e.relations.get_mut(index))
        {
            row.kind = contact_edit::next_relation_kind(row.kind);
            cx.notify();
        }
    }

    fn add_relation(&mut self, cx: &mut Context<Self>) {
        let (name, label) = (
            make_field(Config::TEXT, "", cx),
            make_field(Config::TEXT, "", cx),
        );
        if let Some(editor) = self.editor.as_mut()
            && editor.relations.len() < MAX_FIELDS
        {
            editor.relations.push(RelationRow {
                name,
                kind: RelationKind::Spouse,
                label,
            });
        }
        cx.notify();
    }

    fn remove_relation(&mut self, index: usize, cx: &mut Context<Self>) {
        if let Some(editor) = self.editor.as_mut()
            && index < editor.relations.len()
        {
            editor.relations.remove(index);
        }
        cx.notify();
    }

    fn ask_phonetic(&mut self, cx: &mut Context<Self>) {
        if let Some(editor) = self.editor.as_mut() {
            editor.phonetic_asked = true;
        }
        cx.notify();
    }

    fn add_event(&mut self, cx: &mut Context<Self>) {
        let text = make_field(Config::TEXT, "", cx);
        if let Some(editor) = self.editor.as_mut()
            && editor.events.len() < MAX_FIELDS
        {
            // The first event is a birthday; the next ones cycle on from there.
            let kind = if editor.events.is_empty() {
                EventKind::Birthday
            } else {
                EventKind::Anniversary
            };
            editor.events.push(EventRow {
                text,
                kind,
                label: None,
            });
        }
        cx.notify();
    }

    fn remove_event(&mut self, index: usize, cx: &mut Context<Self>) {
        if let Some(editor) = self.editor.as_mut()
            && index < editor.events.len()
        {
            editor.events.remove(index);
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
        // What the Undo on the pill goes back to, and the pill's words.
        let undo = contact_edit::undo_target(&editor.initial, &draft);
        let summary = contact_edit::change_summary(&editor.initial, &draft);
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
                    if let Some(account) = remember {
                        this.note_created(id);
                        if this.save_to != account {
                            this.save_to = account;
                        }
                        this.save_prefs(cx);
                    }
                    this.photo_sheet.close();
                    let photo = this.editor.as_mut().and_then(|e| e.pending_photo.take());
                    this.editor = None;
                    // Its numbers may have changed: look WhatsApp up again.
                    this.wa.lookups.remove(&id);
                    this.pending_open = Some(id);
                    let text: SharedString = contact_edit::saved_notice(summary).into();
                    this.edit_undo = undo.map(|before| EditUndo {
                        before,
                        notice: text.clone(),
                    });
                    this.notice_action = this.edit_undo.as_ref().map(|_| NoticeAction::UndoEdit);
                    this.notice = Some(text);
                    this.page.restart();
                    this.reload(cx);
                    if let Some((path, view)) = photo {
                        this.apply_photo_with(id, path, view, cx);
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

    /// Undo on the pill after a save: the version from before the edit is
    /// saved again. The photo is not part of it (a changed photo stays).
    pub(crate) fn undo_edit(&mut self, cx: &mut Context<Self>) {
        let Some(undo) = self.edit_undo.take() else {
            self.notice = None;
            self.notice_action = None;
            cx.notify();
            return;
        };
        self.notice = Some("Undoing…".into());
        self.notice_action = None;
        cx.notify();
        let before = undo.before;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let id = backend::save(&before)?;
                    Ok::<_, String>((id, backend::pull_detail(id).ok()))
                })
                .await;
            this.update(cx, |this, cx| {
                this.notice = Some(match result {
                    Ok((id, detail)) => {
                        // Its numbers may have changed: look WhatsApp up again.
                        this.wa.lookups.remove(&id);
                        if this.open.as_ref().is_some_and(|card| card.id == id) {
                            this.open_draft = detail;
                        }
                        "Edit undone".into()
                    }
                    Err(words) => format!("Can't undo: {words}.").into(),
                });
                this.reload(cx);
                cx.notify();
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

    /// "Choose from gallery" or "Take photo": opens the system picker or the
    /// camera; the answer is a `document_result` that opens the crop page.
    pub(crate) fn pick_photo(&mut self, camera: bool, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        if self.transfer_busy {
            cx.notify();
            return;
        }
        self.transfer_busy = true;
        self.clear_notice();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    if camera {
                        backend::start_capture_photo()
                    } else {
                        backend::start_pick_photo()
                    }
                })
                .await;
            this.update(cx, |this, cx| {
                if let Err(words) = result {
                    this.transfer_busy = false;
                    this.notice = Some(if camera {
                        crate::photo_crop::capture_notice(&words).into()
                    } else {
                        format!("Can't change the photo: {words}.").into()
                    });
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// A row of the photo sheet was tapped.
    fn photo_source(&mut self, choice: PhotoSource, cx: &mut Context<Self>) {
        match choice {
            PhotoSource::TakePhoto => self.pick_photo(true, cx),
            PhotoSource::ChooseFromGallery => self.pick_photo(false, cx),
            PhotoSource::CropCurrent => self.crop_current_photo(cx),
            PhotoSource::Remove => self.remove_photo(cx),
        }
    }

    /// "Remove photo": a saved contact asks first, a new one drops the
    /// image it was holding.
    fn remove_photo(&mut self, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        let Some(editor) = self.editor.as_mut() else {
            // From the viewer: ask first, the card closes it on Remove.
            if let Some(viewer) = self.viewer.as_ref().filter(|v| v.can_edit()) {
                self.confirm = Some(crate::confirm::Confirm::RemovePhoto {
                    id: viewer.contact_id,
                    name: viewer.title.clone(),
                });
            }
            cx.notify();
            return;
        };
        match editor.id {
            Some(id) => {
                // A saved contact's photo is gone for good: ask first.
                let name = editor.initial.display_name();
                let name = if name.is_empty() {
                    "This contact".to_owned()
                } else {
                    name
                };
                self.confirm = Some(crate::confirm::Confirm::RemovePhoto {
                    id,
                    name: name.into(),
                });
            }
            None => editor.pending_photo = None,
        }
        cx.notify();
    }

    /// An image was picked or captured and copied to `path`: the crop page
    /// opens for it. A saved contact gets the result at once, a new one holds
    /// it until the first save returns the id.
    pub(crate) fn photo_picked(&mut self, path: String, cx: &mut Context<Self>) {
        use crate::photo_crop::CropTarget;
        let target = match self.editor.as_ref() {
            Some(editor) => Some(
                editor
                    .id
                    .map_or(CropTarget::NewContact, CropTarget::Contact),
            ),
            // Chosen from the viewer (or the card): the open contact gets it.
            None => self
                .viewer
                .as_ref()
                .map(|v| v.contact_id)
                .or_else(|| self.open.as_ref().map(|c| c.id))
                .map(CropTarget::Contact),
        };
        if let Some(target) = target {
            self.open_crop(target, path, cx);
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
        let colors = theme::colors(theme);
        let from_viewer = self.editor.is_none() && self.viewer.is_some();
        let mut body = div().flex().flex_col().pb(px(12.0)).child(
            div()
                .px(px(20.0))
                .pb(px(8.0))
                .text_size(typography::TITLE3.size)
                .font_weight(typography::TITLE3.weight)
                .text_color(colors.label)
                .child("Contact photo"),
        );
        let has_photo = self.editor_has_photo() || from_viewer;
        let can_crop = from_viewer && self.viewer.as_ref().is_some_and(|v| v.file.is_some());
        for choice in crate::photo_crop::photo_source_choices(has_photo) {
            if choice == PhotoSource::CropCurrent && !can_crop {
                continue;
            }
            let row = tap_row(
                theme,
                choice.id(),
                Some(choice.icon()),
                choice.label(),
                if choice == PhotoSource::Remove {
                    Tone::Danger
                } else {
                    Tone::Plain
                },
            )
            .on_click(cx.listener(move |this, _, _, cx| this.photo_source(choice, cx)));
            body = body.child(row);
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
                    .on_click(cx.listener(|this, _, _, cx| this.leave_editor(cx))),
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

        let mut name_rows: Vec<AnyElement> = vec![
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
                    "f-nickname",
                    &editor.nickname,
                    "Nickname",
                    false,
                    window,
                    cx,
                ))
                .into_any_element(),
            form_row(theme)
                .child(field_box(
                    theme,
                    "f-title",
                    &editor.title,
                    "Job title",
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
        ];
        if phonetic_visible(
            editor.phonetic_given.read(cx).text(),
            editor.phonetic_family.read(cx).text(),
            editor.phonetic_asked,
        ) {
            for (key, field, placeholder) in [
                (
                    "f-phonetic-given",
                    &editor.phonetic_given,
                    "Phonetic first name",
                ),
                (
                    "f-phonetic-family",
                    &editor.phonetic_family,
                    "Phonetic last name",
                ),
            ] {
                name_rows.push(
                    form_row(theme)
                        .child(field_box(theme, key, field, placeholder, false, window, cx))
                        .into_any_element(),
                );
            }
        } else {
            name_rows.push(
                tap_row(
                    theme,
                    "phonetic-add",
                    Some(Icon::LucidePlus),
                    "Add phonetic name",
                    Tone::Accent,
                )
                .on_click(cx.listener(|this, _, _, cx| this.ask_phonetic(cx)))
                .into_any_element(),
            );
        }
        let name = section(theme, Some("Name".into()), name_rows);

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
        let numbers = self.field_section(theme, Set::Numbers, window, cx);
        let emails = self.field_section(theme, Set::Emails, window, cx);
        let dates = self.event_section(theme, window, cx);
        let relations = self.relation_section(theme, window, cx);
        let addresses = self.field_section(theme, Set::Addresses, window, cx);
        let websites = self.field_section(theme, Set::Websites, window, cx);

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
            .child(dates)
            .child(relations)
            .child(addresses)
            .child(websites)
            .child(section(
                theme,
                Some("Groups".into()),
                if groups.is_empty() {
                    vec![
                        div()
                            .min_h(metrics::MIN_HIT)
                            .px(metrics::ROW_INSET)
                            .py(px(10.0))
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child("No groups in this account yet. Make one in the Groups tab.")
                            .into_any_element(),
                    ]
                } else {
                    groups
                },
            ))
            .child(note)
            .children(delete)
            .into_any_element()
    }

    /// The Phone, Email or Addresses section: kind button, box and remove
    /// button per row.
    fn field_section(
        &self,
        theme: Theme,
        set: Set,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let Some(editor) = &self.editor else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let (rows, header, placeholder, kind_key, field_key, remove_key, add_key, add_label) =
            match set {
                Set::Numbers => (
                    &editor.numbers,
                    "Phone",
                    "Phone number",
                    "num-kind",
                    "num-field",
                    "num-remove",
                    "num-add",
                    "Add phone",
                ),
                Set::Emails => (
                    &editor.emails,
                    "Email",
                    "Email address",
                    "mail-kind",
                    "mail-field",
                    "mail-remove",
                    "mail-add",
                    "Add email",
                ),
                Set::Addresses => (
                    &editor.addresses,
                    "Addresses",
                    "Street, city, postcode",
                    "addr-kind",
                    "addr-field",
                    "addr-remove",
                    "addr-add",
                    "Add address",
                ),
                Set::Websites => (
                    &editor.websites,
                    "Websites",
                    "example.com",
                    "web-kind",
                    "web-field",
                    "web-remove",
                    "web-add",
                    "Add website",
                ),
            };
        let multiline = set == Set::Addresses;
        let mut children: Vec<AnyElement> = rows
            .iter()
            .enumerate()
            .map(|(i, row)| {
                let can_remove = set != Set::Numbers || rows.len() > 1;
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
                            .child(if set == Set::Websites {
                                SharedString::from("Link")
                            } else {
                                row.title(cx)
                            })
                            .on_click(
                                cx.listener(move |this, _, _, cx| this.cycle_kind(set, i, cx)),
                            ),
                    )
                    .child(field_box(
                        theme,
                        (field_key, i),
                        &row.text,
                        placeholder,
                        multiline,
                        window,
                        cx,
                    ))
                    .children(can_remove.then(|| remove_button(theme, (remove_key, i), cx, set, i)))
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
                .on_click(cx.listener(move |this, _, _, cx| this.add_row(set, cx)))
                .into_any_element(),
            );
        }
        section(theme, Some(header.into()), children).into_any_element()
    }

    /// The Relationship section: kind button, name box, remove button and,
    /// for the custom kind, a label box per row.
    fn relation_section(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let Some(editor) = &self.editor else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let mut children: Vec<AnyElement> = Vec::new();
        for (i, row) in editor.relations.iter().enumerate() {
            let title = if relation_label_visible(row.kind) {
                "Custom"
            } else {
                row.kind.title()
            };
            children.push(
                form_row(theme)
                    .child(
                        div()
                            .id(("rel-kind", i))
                            .flex_none()
                            .w(px(88.0))
                            .h(metrics::MIN_HIT)
                            .flex()
                            .items_center()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.accent)
                            .child(title)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.cycle_relation_kind(i, cx);
                            })),
                    )
                    .child(field_box(
                        theme,
                        ("rel-name", i),
                        &row.name,
                        "Name",
                        false,
                        window,
                        cx,
                    ))
                    .child(
                        div()
                            .id(("rel-remove", i))
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
                                this.remove_relation(i, cx);
                            })),
                    )
                    .into_any_element(),
            );
            if relation_label_visible(row.kind) {
                children.push(
                    form_row(theme)
                        .child(field_box(
                            theme,
                            ("rel-label", i),
                            &row.label,
                            "Label, e.g. Neighbour",
                            false,
                            window,
                            cx,
                        ))
                        .into_any_element(),
                );
            }
        }
        if editor.relations.len() < MAX_FIELDS {
            children.push(
                tap_row(
                    theme,
                    "rel-add",
                    Some(Icon::LucidePlus),
                    "Add relationship",
                    Tone::Accent,
                )
                .on_click(cx.listener(|this, _, _, cx| this.add_relation(cx)))
                .into_any_element(),
            );
        }
        section(theme, Some("Relationship".into()), children).into_any_element()
    }

    /// The Birthday & dates section: kind button, date box and remove button
    /// per row.
    fn event_section(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let Some(editor) = &self.editor else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let mut children: Vec<AnyElement> = editor
            .events
            .iter()
            .enumerate()
            .map(|(i, row)| {
                form_row(theme)
                    .child(
                        div()
                            .id(("date-kind", i))
                            .flex_none()
                            .w(px(88.0))
                            .h(metrics::MIN_HIT)
                            .flex()
                            .items_center()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.accent)
                            .child(row.title())
                            .on_click(
                                cx.listener(move |this, _, _, cx| this.cycle_event_kind(i, cx)),
                            ),
                    )
                    .child(field_box(
                        theme,
                        ("date-field", i),
                        &row.text,
                        "e.g. 1994-05-12 or 05-12",
                        false,
                        window,
                        cx,
                    ))
                    .child(
                        div()
                            .id(("date-remove", i))
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
                                this.remove_event(i, cx);
                            })),
                    )
                    .into_any_element()
            })
            .collect();
        if editor.events.len() < MAX_FIELDS {
            children.push(
                tap_row(
                    theme,
                    "date-add",
                    Some(Icon::LucidePlus),
                    "Add birthday or date",
                    Tone::Accent,
                )
                .on_click(cx.listener(|this, _, _, cx| this.add_event(cx)))
                .into_any_element(),
            );
        }
        section(theme, Some("Birthday & dates".into()), children).into_any_element()
    }
}

/// The round remove button at the end of a field row.
fn remove_button(
    theme: Theme,
    id: (&'static str, usize),
    cx: &mut Context<ContactsApp>,
    set: Set,
    index: usize,
) -> gpui::Stateful<gpui::Div> {
    let colors = theme::colors(theme);
    div()
        .id(id)
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
        .on_click(cx.listener(move |this, _, _, cx| this.remove_row(set, index, cx)))
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
    fn phonetic_fields_show_when_set_or_asked() {
        assert!(!phonetic_visible("", "  ", false));
        assert!(phonetic_visible("Aa-li", "", false));
        assert!(phonetic_visible("", "Ve-li", false));
        assert!(phonetic_visible("", "", true));
    }

    #[test]
    fn only_the_custom_relation_has_a_label_box() {
        assert!(relation_label_visible(RelationKind::Custom));
        assert!(!relation_label_visible(RelationKind::Spouse));
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
