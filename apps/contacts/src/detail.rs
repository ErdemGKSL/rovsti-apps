//! The contact card: the page one contact opens to.

use std::{iter, sync::Arc};

use gpui::{
    AnyElement, ClipboardItem, Context, InteractiveElement, IntoElement, ParentElement,
    RenderImage, Role, SharedString, StatefulInteractiveElement, Styled, div, px, svg,
};
use rovsti_core::{
    contact_card::ContactCard,
    contact_dates,
    contact_edit::{ContactDraft, DraftEvent, DraftField, EventKind, default_action, web_url},
    contact_select, number_format, vcard,
};
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, list_row, section},
    glass::{GlassSpec, glass},
    theme::{self, hue_pair},
    tokens::{Theme, metrics, shadow, typography},
};

use crate::{
    ContactsApp, backend,
    photos::photo_avatar,
    ui::{Tone, tap_row},
};

/// Where a number or email sits among its siblings in the full contact.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Slot {
    pub index: usize,
    pub count: usize,
    pub primary: bool,
}

/// The same entry in two spellings: numbers by their digits (and `+`), other
/// text ignoring case and outer spaces.
fn same_entry(a: &str, b: &str) -> bool {
    fn key(s: &str) -> String {
        let s = s.trim();
        if s.chars().any(|c| c.is_ascii_digit()) {
            s.chars()
                .filter(|c| c.is_ascii_digit() || *c == '+')
                .collect()
        } else {
            s.to_lowercase()
        }
    }
    key(a) == key(b)
}

/// Where `value` sits in `fields`, or `None` when it is not there.
pub(crate) fn slot_of(fields: &[DraftField], value: &str) -> Option<Slot> {
    let index = fields.iter().position(|f| same_entry(&f.value, value))?;
    Some(Slot {
        index,
        count: fields.len(),
        primary: fields[index].primary,
    })
}

/// What the field sheet acts on.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FieldTarget {
    Number {
        raw: String,
        display: String,
        /// `None` while the full contact is still loading.
        slot: Option<Slot>,
    },
    Email {
        address: String,
        slot: Option<Slot>,
    },
    Note(String),
    /// A postal address (may span lines).
    Address(String),
    /// A dated event as copyable text ("Birthday · 7 Mar 1990").
    Date(String),
    /// A website, as typed.
    Website(String),
}

/// One row of the field sheet.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FieldAction {
    Call,
    Message,
    SendEmail,
    Copy(&'static str),
    Share(&'static str),
    OpenMaps,
    OpenLink,
    /// Make the entry the default (`false`) or clear the default (`true`).
    SetDefault(bool),
    /// Open the list of number forms to copy.
    CopyAs,
}

impl FieldAction {
    fn label(self) -> &'static str {
        match self {
            Self::Call => "Call",
            Self::Message => "Message",
            Self::SendEmail => "Send Email",
            Self::Copy(label) | Self::Share(label) => label,
            Self::OpenMaps => "Open in Maps",
            Self::OpenLink => "Open Link",
            Self::SetDefault(true) => "Clear default",
            Self::SetDefault(false) => "Set as default",
            Self::CopyAs => "Copy as…",
        }
    }

    fn icon(self) -> Icon {
        match self {
            Self::Call => Icon::LucidePhone,
            Self::Message => Icon::LucideMessageCircle,
            Self::SendEmail => Icon::LucideMail,
            Self::Copy(_) => Icon::LucideCopy,
            Self::Share(_) => Icon::LucideUpload,
            Self::OpenMaps => Icon::LucideMapPin,
            Self::OpenLink => Icon::LucideExternalLink,
            Self::SetDefault(_) => Icon::LucideStar,
            Self::CopyAs => Icon::LucideClipboardPaste,
        }
    }

    fn key(self) -> &'static str {
        match self {
            Self::Call => "field-call",
            Self::Message => "field-sms",
            Self::SendEmail => "field-email",
            Self::Copy(_) => "field-copy",
            Self::Share(_) => "field-share",
            Self::OpenMaps => "field-maps",
            Self::OpenLink => "field-link",
            Self::SetDefault(_) => "field-default",
            Self::CopyAs => "field-copy-as",
        }
    }
}

/// The Set / Clear default row: only for an entry that has a sibling.
fn default_row(slot: Option<Slot>) -> Option<FieldAction> {
    let slot = slot?;
    let (_, clear) = default_action(slot.primary, slot.count)?;
    Some(FieldAction::SetDefault(clear))
}

impl FieldTarget {
    /// The rows the field sheet offers, top to bottom.
    pub(crate) fn actions(&self, home_cc: Option<&str>) -> Vec<FieldAction> {
        match self {
            Self::Number { slot, raw, .. } => [
                FieldAction::Call,
                FieldAction::Message,
                FieldAction::Copy("Copy Number"),
            ]
            .into_iter()
            .chain(
                (number_format::copy_forms(raw, home_cc).len() >= 2).then_some(FieldAction::CopyAs),
            )
            .chain([FieldAction::Share("Share Number")])
            .chain(default_row(*slot))
            .collect(),
            Self::Email { slot, .. } => [FieldAction::SendEmail, FieldAction::Copy("Copy Email")]
                .into_iter()
                .chain(default_row(*slot))
                .collect(),
            Self::Note(_) => vec![FieldAction::Copy("Copy Note")],
            Self::Address(_) => vec![
                FieldAction::Copy("Copy Address"),
                FieldAction::Share("Share Address"),
                FieldAction::OpenMaps,
            ],
            Self::Date(_) => vec![FieldAction::Copy("Copy Date")],
            Self::Website(_) => vec![
                FieldAction::OpenLink,
                FieldAction::Copy("Copy Link"),
                FieldAction::Share("Share Link"),
            ],
        }
    }

    /// What Copy and Share send.
    fn text(&self) -> &str {
        match self {
            Self::Number { display, .. } => display,
            Self::Email { address: text, .. }
            | Self::Note(text)
            | Self::Address(text)
            | Self::Date(text)
            | Self::Website(text) => text,
        }
    }

    /// What Call and Message dial.
    fn dial(&self) -> &str {
        match self {
            Self::Number { raw, .. } => raw,
            other => other.text(),
        }
    }

    /// The sheet's heading.
    fn title(&self) -> SharedString {
        match self {
            Self::Note(_) => "Note".into(),
            Self::Address(a) => a
                .lines()
                .map(str::trim)
                .find(|l| !l.is_empty())
                .unwrap_or("Address")
                .to_owned()
                .into(),
            Self::Date(_) => "Date".into(),
            other => other.text().to_owned().into(),
        }
    }
}

/// An entry of the card's Share menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ShareChoice {
    Text,
    Vcf,
    SaveFile,
    CopyText,
    CopyVcard,
    Qr,
    AddToHome,
}

impl ShareChoice {
    fn label(self) -> &'static str {
        match self {
            Self::Text => "Share as text",
            Self::Vcf => "Share contact file (.vcf)",
            Self::SaveFile => "Save to file (.vcf)",
            Self::CopyText => "Copy contact",
            Self::CopyVcard => "Copy as vCard",
            Self::Qr => "Show QR code",
            Self::AddToHome => "Add to Home screen",
        }
    }

    fn icon(self) -> Icon {
        match self {
            Self::Text => Icon::LucideUpload,
            Self::Vcf => Icon::LucideContact,
            Self::SaveFile => Icon::LucideDownload,
            Self::CopyText | Self::CopyVcard => Icon::LucideCopy,
            Self::Qr => Icon::LucideQrCode,
            Self::AddToHome => Icon::LucideSmartphone,
        }
    }
}

/// The Share menu entries: the QR code needs a number or an email to carry.
pub(crate) fn share_choices(
    has_numbers: bool,
    has_emails: bool,
    can_pin: bool,
) -> Vec<ShareChoice> {
    let mut choices = vec![
        ShareChoice::Text,
        ShareChoice::Vcf,
        ShareChoice::SaveFile,
        ShareChoice::CopyText,
        ShareChoice::CopyVcard,
    ];
    if has_numbers || has_emails {
        choices.push(ShareChoice::Qr);
    }
    if can_pin {
        choices.push(ShareChoice::AddToHome);
    }
    choices
}

/// The pill after Set / Clear default.
pub(crate) fn default_notice(emails: bool, clear: bool) -> &'static str {
    match (emails, clear) {
        (false, false) => "Default number set",
        (true, false) => "Default email set",
        (_, true) => "Default cleared",
    }
}

/// The copyable text of a dated event ("Birthday · 7 Mar 1990"), or `None`
/// when the date is unreadable.
pub(crate) fn date_copy_text(event: &DraftEvent) -> Option<String> {
    let date = contact_dates::parse(&event.date)?;
    Some(format!("{} · {}", event.title(), date.text()))
}

/// The org line under the name: "Engineer · Acme", or whichever part exists.
pub(crate) fn org_line(title: &str, org: &str) -> Option<String> {
    let parts: Vec<&str> = [title.trim(), org.trim()]
        .into_iter()
        .filter(|p| !p.is_empty())
        .collect();
    (!parts.is_empty()).then(|| parts.join(" · "))
}

/// The first email address of the full contact that is not blank.
pub(crate) fn first_email(draft: Option<&ContactDraft>) -> Option<String> {
    draft?
        .emails
        .iter()
        .map(|e| e.value.trim())
        .find(|v| !v.is_empty())
        .map(str::to_owned)
}

/// The vCard of one contact, with its photo when it has one. Blocks.
fn card_vcard(draft: &ContactDraft, photo_uri: Option<&str>) -> String {
    let photo = photo_uri.and_then(backend::card_photo);
    vcard::write_one(draft, photo.as_ref())
}

/// The pill after "Copy as vCard".
pub(crate) fn copied_vcard_words(with_photo: bool) -> &'static str {
    if with_photo {
        "vCard copied (with photo)"
    } else {
        contact_select::copied_vcard_notice()
    }
}

impl ContactsApp {
    /// Closes the field sheet without animation (the card is going away).
    pub(crate) fn close_field_sheet_now(&mut self) {
        self.field_sheet.close();
        self.field_target = None;
        self.field_copy_as = false;
        self.share_sheet.close();
        self.qr_sheet.close();
        self.qr_card = None;
    }

    pub(crate) fn open_field_sheet(&mut self, target: FieldTarget, cx: &mut Context<Self>) {
        self.field_target = Some(target);
        self.field_copy_as = false;
        self.field_sheet.open();
        cx.notify();
    }

    pub(crate) fn close_field_sheet(&mut self, cx: &mut Context<Self>) {
        self.field_sheet.close();
        cx.notify();
    }

    fn copy_field(&mut self, text: String, cx: &mut Context<Self>) {
        cx.write_to_clipboard(ClipboardItem::new_string(text));
        self.field_sheet.close();
        self.notice = Some("Copied.".into());
        cx.notify();
    }

    /// Runs an Android action from the field sheet and closes the sheet.
    fn field_act(
        &mut self,
        what: &'static str,
        action: impl FnOnce() -> rovsti_android::Result<bool> + Send + 'static,
        cx: &mut Context<Self>,
    ) {
        self.field_sheet.close();
        self.act(what, action, cx);
        cx.notify();
    }

    /// The card's Share button: the choice sheet.
    fn open_share_sheet(&mut self, cx: &mut Context<Self>) {
        self.share_sheet.open();
        cx.notify();
    }

    pub(crate) fn close_share_sheet(&mut self, cx: &mut Context<Self>) {
        self.share_sheet.close();
        cx.notify();
    }

    /// Runs one entry of the Share menu.
    fn run_share_choice(&mut self, choice: ShareChoice, text: String, cx: &mut Context<Self>) {
        match choice {
            ShareChoice::Text => {
                self.share_sheet.close();
                self.act(
                    "share",
                    move || rovsti_android::telecom::share_text(&text),
                    cx,
                );
                cx.notify();
            }
            ShareChoice::Vcf => {
                self.share_sheet.close();
                let Some(draft) = self.open_draft.clone() else {
                    self.notice = Some("The contact is still loading.".into());
                    cx.notify();
                    return;
                };
                let today = contact_dates::local_ymd(crate::whatsapp::now_ms(), self.wa.offset_s);
                let name = vcard::export_name(std::slice::from_ref(&draft), today);
                let photo_uri = self.open_photo_uri();
                self.notice = Some("Preparing the contact file…".into());
                cx.spawn(async move |this, cx| {
                    let outcome = cx
                        .background_executor()
                        .spawn(async move {
                            let text = card_vcard(&draft, photo_uri.as_deref());
                            backend::share_vcf(&name, &text)
                        })
                        .await;
                    this.update(cx, |this, cx| {
                        this.notice = outcome
                            .err()
                            .map(|words| format!("Can't share the contact file: {words}.").into());
                        cx.notify();
                    })
                    .ok();
                })
                .detach();
                cx.notify();
            }
            ShareChoice::SaveFile => self.save_card_file(cx),
            ShareChoice::CopyText | ShareChoice::CopyVcard => {
                self.share_sheet.close();
                let Some(draft) = self.open_draft.clone() else {
                    self.notice = Some("The contact is still loading.".into());
                    cx.notify();
                    return;
                };
                if choice == ShareChoice::CopyVcard {
                    let photo_uri = self.open_photo_uri();
                    cx.spawn(async move |this, cx| {
                        let (payload, with_photo) = cx
                            .background_executor()
                            .spawn(async move {
                                let photo = photo_uri.as_deref().and_then(backend::card_photo);
                                (vcard::write_one(&draft, photo.as_ref()), photo.is_some())
                            })
                            .await;
                        this.update(cx, |this, cx| {
                            this.act(
                                "copy the contact",
                                move || rovsti_android::telecom::copy_text(&payload),
                                cx,
                            );
                            this.notice = Some(copied_vcard_words(with_photo).into());
                            cx.notify();
                        })
                        .ok();
                    })
                    .detach();
                    return;
                }
                let payload = contact_select::full_text(&draft);
                self.act(
                    "copy the contact",
                    move || rovsti_android::telecom::copy_text(&payload),
                    cx,
                );
                self.notice = Some(contact_select::copied_contact_notice().into());
                cx.notify();
            }
            ShareChoice::Qr => self.open_qr(cx),
            ShareChoice::AddToHome => {
                self.share_sheet.close();
                if let Some(id) = self.open.as_ref().map(|card| card.id) {
                    self.pin_home(id, cx);
                }
                cx.notify();
            }
        }
    }

    /// The photo link of the open contact, if it has a photo.
    fn open_photo_uri(&self) -> Option<String> {
        let id = self.open.as_ref()?.id;
        self.photo_uri_of(id).map(str::to_owned)
    }

    /// "Save to file": the system "save as" for this one contact. The answer
    /// is a `document_result` handled by the export path in `transfer.rs`.
    fn save_card_file(&mut self, cx: &mut Context<Self>) {
        self.share_sheet.close();
        let Some(draft) = self.open_draft.clone() else {
            self.notice = Some("The contact is still loading.".into());
            cx.notify();
            return;
        };
        if self.transfer_busy {
            self.notice = Some("Wait for the file being saved or read to finish.".into());
            cx.notify();
            return;
        }
        let today = contact_dates::local_ymd(crate::whatsapp::now_ms(), self.wa.offset_s);
        let name = vcard::export_name(std::slice::from_ref(&draft), today);
        let photo_uri = self.open_photo_uri();
        self.transfer_busy = true;
        self.notice = Some("Preparing the file…".into());
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let text = card_vcard(&draft, photo_uri.as_deref());
                    backend::start_export_named(&name, &text)
                })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(()) => this.exported = Some(1),
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
        cx.notify();
    }

    /// The Share menu over the card, once it is visible.
    pub(crate) fn share_sheet_view(
        &mut self,
        theme: Theme,
        window: &gpui::Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.share_sheet
            .set_reduced(window.prefers_reduced_motion());
        self.share_sheet.set_viewport(window.viewport_size());
        let card = self.open.as_ref()?;
        let colors = theme::colors(theme);
        let choices = share_choices(
            !card.numbers.is_empty(),
            first_email(self.open_draft.as_ref()).is_some(),
            self.can_pin,
        );
        let text = card.share_text();
        let rows: Vec<AnyElement> = choices
            .into_iter()
            .map(|choice| {
                let text = text.clone();
                tap_row(
                    theme,
                    match choice {
                        ShareChoice::Text => "share-text",
                        ShareChoice::Vcf => "share-vcf",
                        ShareChoice::SaveFile => "share-save",
                        ShareChoice::CopyText => "share-copy-text",
                        ShareChoice::CopyVcard => "share-copy-vcard",
                        ShareChoice::Qr => "share-qr",
                        ShareChoice::AddToHome => "share-home",
                    },
                    Some(choice.icon()),
                    choice.label(),
                    Tone::Plain,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.run_share_choice(choice, text.clone(), cx);
                }))
                .into_any_element()
            })
            .collect();
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
                    .child("Share contact"),
            )
            .children(rows);
        let entity = cx.entity();
        let drag = entity.clone();
        self.share_sheet.render(
            theme,
            window,
            "Share contact",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_share_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.share_sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    /// Opens a website in the browser, or says it can't be opened.
    fn open_web(&mut self, text: &str, cx: &mut Context<Self>) {
        match web_url(text) {
            Some(url) => cx.open_url(&url),
            None => self.notice = Some("Can't open that link".into()),
        }
    }

    /// Runs one row of the field sheet.
    fn run_field_action(
        &mut self,
        target: &FieldTarget,
        action: FieldAction,
        cx: &mut Context<Self>,
    ) {
        let text = target.text().to_owned();
        match action {
            FieldAction::Call => {
                let number = target.dial().to_owned();
                self.field_act(
                    "call",
                    move || rovsti_android::telecom::place_call(&number),
                    cx,
                );
            }
            FieldAction::Message => {
                let number = target.dial().to_owned();
                self.field_act(
                    "send a message",
                    move || rovsti_android::telecom::send_sms_to(&number),
                    cx,
                );
            }
            FieldAction::SendEmail => self.field_act(
                "send an email",
                move || rovsti_android::telecom::send_email_to(&text),
                cx,
            ),
            FieldAction::Copy(_) => self.copy_field(text, cx),
            FieldAction::OpenLink => {
                self.field_sheet.close();
                self.open_web(&text, cx);
                cx.notify();
            }
            FieldAction::Share(_) => self.field_act(
                "share",
                move || rovsti_android::telecom::share_text(&text),
                cx,
            ),
            FieldAction::SetDefault(clear) => self.set_default_entry(target, clear, cx),
            FieldAction::CopyAs => {
                self.field_copy_as = true;
                cx.notify();
            }
            FieldAction::OpenMaps => {
                self.field_sheet.close();
                match contact_dates::maps_url(&text) {
                    Some(url) => cx.open_url(&url),
                    None => self.notice = Some("There is no address to open.".into()),
                }
                cx.notify();
            }
        }
    }

    /// The field target of number `raw` of the open card (with its place in
    /// the full contact, once that is loaded).
    fn number_target(&self, raw: &str, display: &str) -> FieldTarget {
        FieldTarget::Number {
            raw: raw.to_owned(),
            display: display.to_owned(),
            slot: self
                .open_draft
                .as_ref()
                .and_then(|d| slot_of(&d.numbers, raw)),
        }
    }

    /// Set or clear the default number or email: the contact is saved on the
    /// background executor, then the card and the book follow.
    fn set_default_entry(&mut self, target: &FieldTarget, clear: bool, cx: &mut Context<Self>) {
        self.field_sheet.close();
        let Some(draft) = self.open_draft.clone() else {
            self.notice = Some("The contact is still loading.".into());
            cx.notify();
            return;
        };
        let (emails, value) = match target {
            FieldTarget::Number { raw, .. } => (false, raw.as_str()),
            FieldTarget::Email { address, .. } => (true, address.as_str()),
            _ => return,
        };
        let list = if emails {
            &draft.emails
        } else {
            &draft.numbers
        };
        let Some(slot) = slot_of(list, value) else {
            self.notice = Some("That entry changed. Open the card again.".into());
            cx.notify();
            return;
        };
        let index = (!clear).then_some(slot.index);
        let done = default_notice(emails, clear);
        self.clear_notice();
        cx.spawn(async move |this, cx| {
            let saved = cx
                .background_executor()
                .spawn({
                    let draft = draft.clone();
                    async move { backend::set_default(&draft, emails, index) }
                })
                .await;
            this.update(cx, |this, cx| {
                match saved {
                    Ok(_) => {
                        if let Some(open) =
                            this.open_draft.as_mut().filter(|open| open.id == draft.id)
                        {
                            let list = if emails {
                                &mut open.emails
                            } else {
                                &mut open.numbers
                            };
                            rovsti_core::contact_edit::set_primary(list, index);
                        }
                        this.notice = Some(done.into());
                        this.reload(cx);
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't change the default: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The field sheet over the card, once it is visible.
    pub(crate) fn field_sheet_view(
        &mut self,
        theme: Theme,
        window: &gpui::Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.field_sheet
            .set_reduced(window.prefers_reduced_motion());
        self.field_sheet.set_viewport(window.viewport_size());
        let target = self.field_target.clone()?;
        let colors = theme::colors(theme);
        let title = target.title();
        let mut body = div().flex().flex_col().pb(px(12.0)).child(
            div()
                .px(px(20.0))
                .pb(px(8.0))
                .text_size(typography::TITLE3.size)
                .font_weight(typography::TITLE3.weight)
                .text_color(colors.label)
                .child(title.clone()),
        );
        let copy_forms = match (&target, self.field_copy_as) {
            (FieldTarget::Number { raw, .. }, true) => {
                number_format::copy_forms(raw, self.home_cc.as_deref())
            }
            _ => Vec::new(),
        };
        let actions = if copy_forms.is_empty() {
            target.actions(self.home_cc.as_deref())
        } else {
            Vec::new()
        };
        let form_rows: Vec<AnyElement> = copy_forms
            .into_iter()
            .enumerate()
            .map(|(i, (form, text))| {
                let copy = text.clone();
                list_row(theme, None, text, Some(form.label().into()), None)
                    .id(("copy-as", i))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()));
                        this.field_sheet.close();
                        this.notice = Some(number_format::copied_notice(form).into());
                        cx.notify();
                    }))
                    .into_any_element()
            })
            .collect();
        let rows: Vec<AnyElement> = actions
            .into_iter()
            .map(|action| {
                let target = target.clone();
                tap_row(
                    theme,
                    action.key(),
                    Some(action.icon()),
                    action.label(),
                    Tone::Plain,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.run_field_action(&target, action, cx);
                }))
                .into_any_element()
            })
            .collect();
        body = body.children(rows).children(form_rows);
        let entity = cx.entity();
        let drag = entity.clone();
        self.field_sheet.render(
            theme,
            window,
            "Contact actions",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_field_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.field_sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    /// The text column of a field row (label over value); callers add the
    /// tap and long-press listeners.
    fn field_main(
        theme: Theme,
        id: (&'static str, usize),
        label: SharedString,
        value: SharedString,
        value_color: gpui::Hsla,
    ) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(id)
            .flex_1()
            .min_w_0()
            .flex()
            .flex_col()
            .justify_center()
            .min_h(metrics::ROW_HEIGHT_SUBTITLE)
            .pl(metrics::ROW_INSET)
            .py(px(6.0))
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(label),
            )
            .child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(value_color)
                    .child(value),
            )
    }

    /// The trailing hit area of a field row (ellipsis or copy).
    fn field_more(
        theme: Theme,
        id: (&'static str, usize),
        icon: Icon,
    ) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(id)
            .flex()
            .items_center()
            .justify_center()
            .size(metrics::MIN_HIT)
            .flex_none()
            .child(
                svg()
                    .path(icon.path())
                    .size(px(20.0))
                    .text_color(colors.secondary_label),
            )
    }

    /// A field row: the main column and the trailing hit area, side by side.
    fn field_line(
        theme: Theme,
        main: gpui::Stateful<gpui::Div>,
        more: gpui::Stateful<gpui::Div>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .flex()
            .items_center()
            .pr(px(4.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(main)
            .child(more)
            .into_any_element()
    }

    /// Like [`Self::field_line`] with several trailing items.
    fn field_line_with(
        theme: Theme,
        main: gpui::Stateful<gpui::Div>,
        trailing: impl IntoIterator<Item = gpui::Stateful<gpui::Div>>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .flex()
            .items_center()
            .pr(px(4.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(main)
            .children(trailing)
            .into_any_element()
    }

    /// The small "Default" tag of a number or email row.
    fn default_tag(theme: Theme, id: (&'static str, usize)) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(id)
            .flex_none()
            .h(px(24.0))
            .px(px(10.0))
            .rounded_full()
            .flex()
            .items_center()
            .bg(colors.secondary_label.opacity(0.14))
            .text_size(typography::CAPTION.size)
            .text_color(colors.secondary_label)
            .child("Default")
    }

    /// The "WhatsApp" chip of a number row.
    fn wa_chip(theme: Theme, index: usize) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(("wa-chip", index))
            .flex_none()
            .h(px(32.0))
            .px(px(14.0))
            .rounded_full()
            .flex()
            .items_center()
            .bg(colors.accent.opacity(0.14))
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.accent)
            .child("WhatsApp")
    }

    /// The "Wish" chip of a birthday row.
    pub(crate) fn wish_chip(theme: Theme, index: usize) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(("wish", index))
            .flex_none()
            .h(px(32.0))
            .px(px(14.0))
            .rounded_full()
            .flex()
            .items_center()
            .bg(colors.accent.opacity(0.14))
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.accent)
            .child("Wish")
    }

    /// A round glass action with a caption (Call, Message, Share).
    pub(crate) fn action_button(
        theme: Theme,
        key: &'static str,
        icon: Icon,
        label: &'static str,
        enabled: bool,
    ) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(key)
            .flex()
            .flex_col()
            .items_center()
            .gap(px(6.0))
            .opacity(if enabled { 1.0 } else { 0.4 })
            .child(
                glass(GlassSpec::control(theme))
                    .size(px(56.0))
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(icon.path())
                            .size(px(22.0))
                            .text_color(colors.accent),
                    ),
            )
            .child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.accent)
                    .child(label),
            )
    }

    /// A labelled value row of the card (Email, Note).
    fn info_row(
        theme: Theme,
        id: (&'static str, usize),
        label: SharedString,
        value: SharedString,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .id(id)
            .flex()
            .flex_col()
            .justify_center()
            .min_h(metrics::ROW_HEIGHT_SUBTITLE)
            .px(metrics::ROW_INSET)
            .py(px(6.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(label),
            )
            .child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(colors.label)
                    .child(value),
            )
            .into_any_element()
    }

    /// The WhatsApp section: status line and Chat and Call while the instance
    /// is connected, else a hint that opens the WhatsApp settings.
    fn whatsapp_section(
        &self,
        card: &ContactCard,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let numbers: Vec<String> = card.numbers.iter().map(|n| n.raw.clone()).collect();
        if numbers.is_empty() {
            return None;
        }
        if !self.whatsapp.read(cx).is_connected() {
            return Some(
                section(
                    theme,
                    Some("WhatsApp".into()),
                    [tap_row(
                        theme,
                        "wa-hint",
                        Some(Icon::LucideMessageCircle),
                        "Connect WhatsApp to chat and call from here",
                        Tone::Accent,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.tabs.jump_to(crate::SETTINGS_TAB);
                        this.open = None;
                        this.open_draft = None;
                        this.set_whatsapp_open(true, cx);
                    }))
                    .into_any_element()],
                )
                .into_any_element(),
            );
        }
        let status = self
            .wa
            .status(card.id, &numbers, crate::whatsapp::now_ms())?;
        let jid = self.wa.jid(card.id, &numbers);
        let enabled = jid.is_some();
        let profile_rows: Vec<AnyElement> = jid
            .as_deref()
            .and_then(|jid| self.avatars.read(cx).profile(jid))
            .map(|profile| crate::whatsapp::profile_lines(profile, &card.name))
            .unwrap_or_default()
            .into_iter()
            .enumerate()
            .map(|(i, (label, value))| {
                Self::info_row(theme, ("wa-profile", i), label.into(), value.into())
            })
            .collect();
        let photo_row = jid
            .as_deref()
            .and_then(|jid| Some((jid.to_owned(), self.avatars.read(cx).picture(jid)?)))
            .map(|(jid, picture)| self.wa_photo_row(card, jid, picture, theme, cx));
        let (chat_jid, call_jid) = (jid.clone(), jid);
        let (chat_name, call_name) = (card.name.clone(), card.name.clone());
        let buttons = div()
            .flex()
            .justify_center()
            .gap(px(28.0))
            .py(px(12.0))
            .child(
                Self::action_button(theme, "wa-chat", Icon::LucideMessageCircle, "Chat", enabled)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if let Some(jid) = chat_jid.clone() {
                            this.wa_handoff(false, jid, chat_name.clone(), cx);
                        }
                    })),
            )
            .child(
                Self::action_button(theme, "wa-call", Icon::LucidePhone, "Call", enabled).on_click(
                    cx.listener(move |this, _, _, cx| {
                        if let Some(jid) = call_jid.clone() {
                            this.wa_handoff(true, jid, call_name.clone(), cx);
                        }
                    }),
                ),
            )
            .into_any_element();
        Some(
            section(
                theme,
                Some("WhatsApp".into()),
                iter::once(Self::info_row(
                    theme,
                    ("wa-status", 0),
                    "Status".into(),
                    status.into(),
                ))
                .chain(profile_rows)
                .chain(photo_row)
                .chain(iter::once(buttons)),
            )
            .into_any_element(),
        )
    }

    /// The WhatsApp picture with "Use as Contact Photo": saved at once when
    /// the contact has no photo, after a question when it would replace one.
    fn wa_photo_row(
        &self,
        card: &ContactCard,
        jid: String,
        picture: (Arc<RenderImage>, f32),
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = card.id;
        let tint = hue_pair(theme, card.hue).0;
        let monogram: SharedString = card.monogram.map(String::from).unwrap_or_default().into();
        let replaces = self.photo_uri_of(id).is_some();
        let name = SharedString::from(card.name.clone());
        div()
            .id("wa-photo")
            .flex()
            .items_center()
            .gap(px(12.0))
            .min_h(metrics::ROW_HEIGHT_SUBTITLE)
            .px(metrics::ROW_INSET)
            .py(px(6.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(photo_avatar(
                theme,
                monogram,
                40.0,
                tint,
                Some((&picture.0, picture.1)),
            ))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .text_size(typography::BODY.size)
                    .text_color(colors.accent)
                    .child("Use as Contact Photo"),
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                if replaces {
                    this.ask_use_wa_photo(id, jid.clone(), name.clone(), cx);
                } else {
                    this.use_wa_photo(id, jid.clone(), cx);
                }
            }))
            .into_any_element()
    }

    pub(crate) fn detail(
        &self,
        card: &ContactCard,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = card.id;
        let primary = card.primary().map(|n| n.raw.clone());
        let tint = hue_pair(theme, card.hue).0;
        let monogram: SharedString = card.monogram.map(String::from).unwrap_or_default().into();
        let wa_jid = self.card_jid();
        let picture =
            self.avatar_picture(id, self.photo_uri_of(id).is_some(), wa_jid.as_deref(), cx);
        let photo = picture.as_ref().map(|(image, opacity)| (image, *opacity));
        // The viewer opens on what the avatar shows: the full photo is read
        // only for the contact's own photo, not a WhatsApp stand-in.
        let own_uri = self
            .photo_uri_of(id)
            .filter(|_| self.photos.get(id).is_some())
            .map(str::to_owned);
        let viewable = picture.as_ref().map(|(image, _)| Arc::clone(image));
        let view_name: SharedString = card.name.clone().into();
        // The card's extra facts, once the full contact has been read.
        let full = self.open_draft.as_ref().filter(|d| d.id == Some(id));

        let top = div()
            .flex()
            .items_center()
            .justify_between()
            .px(px(8.0))
            .pt(px(48.0))
            .child(
                glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                    .id("back")
                    .on_click(cx.listener(|this, _, _, cx| this.close_contact(cx))),
            )
            .child(
                // The accent dot marks a favorite.
                glass_icon_button(theme, Icon::LucideStar, 0.0, card.starred)
                    .id("star")
                    .on_click(cx.listener(move |this, _, _, cx| this.toggle_star(id, cx))),
            );

        let org: Option<SharedString> = full
            .and_then(|d| org_line(&d.title, &d.org))
            .map(SharedString::from);
        let nickname: Option<SharedString> = full
            .map(|d| d.nickname.trim())
            .filter(|n| !n.is_empty())
            .map(|n| SharedString::from(n.to_owned()));
        let head = div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(12.0))
            .pt(px(8.0))
            .pb(px(20.0))
            .child(
                div()
                    .id("card-avatar")
                    .role(Role::Button)
                    .aria_label("View photo")
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.open_photo_viewer(
                            id,
                            view_name.clone(),
                            viewable.clone(),
                            own_uri.clone(),
                            cx,
                        );
                    }))
                    .child(photo_avatar(theme, monogram, 96.0, tint, photo)),
            )
            .child(
                div()
                    .px(px(24.0))
                    .text_center()
                    .text_size(typography::TITLE.size)
                    .line_height(typography::TITLE.line)
                    .font_weight(typography::TITLE.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(card.name.clone())),
            )
            .children(nickname.map(|nick| {
                div()
                    .px(px(24.0))
                    .text_center()
                    .text_size(typography::BODY.size)
                    .text_color(colors.secondary_label)
                    .child(nick)
            }))
            .children(org.map(|org| {
                div()
                    .px(px(24.0))
                    .text_center()
                    .text_size(typography::BODY.size)
                    .text_color(colors.secondary_label)
                    .child(org)
            }));

        let has_number = primary.is_some();
        let email_only = if has_number { None } else { first_email(full) };
        let call_number = primary.clone();
        let sms_number = primary;
        let actions = div()
            .flex()
            .justify_center()
            .gap(px(28.0))
            .pb(px(24.0))
            .child(
                Self::action_button(theme, "call", Icon::LucidePhone, "Call", has_number).on_click(
                    cx.listener(move |this, _, _, cx| {
                        if let Some(number) = call_number.clone() {
                            this.act(
                                "call",
                                move || rovsti_android::telecom::place_call(&number),
                                cx,
                            );
                        }
                    }),
                ),
            )
            .child(
                Self::action_button(
                    theme,
                    "message",
                    Icon::LucideMessageCircle,
                    "Message",
                    has_number,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    if let Some(number) = sms_number.clone() {
                        this.act(
                            "send a message",
                            move || rovsti_android::telecom::send_sms_to(&number),
                            cx,
                        );
                    }
                })),
            )
            .child(
                Self::action_button(theme, "share", Icon::LucideUpload, "Share", true)
                    .on_click(cx.listener(|this, _, _, cx| this.open_share_sheet(cx))),
            )
            .children(email_only.map(|address| {
                Self::action_button(theme, "email", Icon::LucideMail, "Email", true).on_click(
                    cx.listener(move |this, _, _, cx| {
                        let address = address.clone();
                        this.act(
                            "send an email",
                            move || rovsti_android::telecom::send_email_to(&address),
                            cx,
                        );
                    }),
                )
            }));

        let numbers: Vec<AnyElement> = card
            .numbers
            .iter()
            .enumerate()
            .map(|(i, number)| {
                let raw = number.raw.clone();
                let target = self.number_target(&number.raw, &number.display);
                let (more_target, press_target) = (target.clone(), target);
                let tag = (number.default && card.numbers.len() > 1)
                    .then(|| Self::default_tag(theme, ("number-default", i)));
                let main = Self::field_main(
                    theme,
                    ("number", i),
                    number.label.clone().into(),
                    number.display.clone().into(),
                    colors.accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    let raw = raw.clone();
                    this.act(
                        "call",
                        move || rovsti_android::telecom::place_call(&raw),
                        cx,
                    );
                }))
                .on_long_press(cx.listener(move |this, _, _, cx| {
                    this.open_field_sheet(press_target.clone(), cx);
                }));
                let more = Self::field_more(theme, ("number-more", i), Icon::LucideEllipsis)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.open_field_sheet(more_target.clone(), cx);
                    }));
                let chip = self.card_wa_numbers.get(i).cloned().flatten().map(|jid| {
                    let name = card.name.clone();
                    Self::wa_chip(theme, i).on_click(cx.listener(move |this, _, _, cx| {
                        this.wa_handoff(false, jid.clone(), name.clone(), cx);
                    }))
                });
                let trailing: Vec<_> = tag.into_iter().chain(chip).chain([more]).collect();
                Self::field_line_with(theme, main, trailing)
            })
            .collect();

        let emails: Vec<AnyElement> = full
            .map(|d| {
                d.emails
                    .iter()
                    .enumerate()
                    .map(|(i, email)| {
                        let address = email.value.clone();
                        let target = FieldTarget::Email {
                            address: address.clone(),
                            slot: slot_of(&d.emails, &address),
                        };
                        let (more_target, press_target) = (target.clone(), target);
                        let tag = (email.primary && d.emails.len() > 1)
                            .then(|| Self::default_tag(theme, ("email-default", i)));
                        let main = Self::field_main(
                            theme,
                            ("email", i),
                            email.title().into(),
                            email.value.clone().into(),
                            colors.accent,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            let address = address.clone();
                            this.act(
                                "send an email",
                                move || rovsti_android::telecom::send_email_to(&address),
                                cx,
                            );
                        }))
                        .on_long_press(cx.listener(
                            move |this, _, _, cx| {
                                this.open_field_sheet(press_target.clone(), cx);
                            },
                        ));
                        let more = Self::field_more(theme, ("email-more", i), Icon::LucideEllipsis)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_field_sheet(more_target.clone(), cx);
                            }));
                        Self::field_line_with(theme, main, tag.into_iter().chain([more]))
                    })
                    .collect()
            })
            .unwrap_or_default();

        let now = crate::whatsapp::now_ms();
        let today = contact_dates::local_day(now, self.wa.offset_s);
        let wish_number = card.primary().map(|n| n.raw.clone());
        let date_rows: Vec<AnyElement> = full
            .map(|d| {
                d.events
                    .iter()
                    .enumerate()
                    .filter_map(|(i, event)| {
                        let line = contact_dates::card_line(event, now, self.wa.offset_s)?;
                        let copy = date_copy_text(event)?;
                        let target = FieldTarget::Date(copy);
                        let (more_target, press_target) = (target.clone(), target);
                        let (label, value) = line.split_once(" · ").unwrap_or(("", &line));
                        let main = Self::field_main(
                            theme,
                            ("date", i),
                            label.to_owned().into(),
                            value.to_owned().into(),
                            colors.label,
                        )
                        .on_long_press(cx.listener(
                            move |this, _, _, cx| {
                                this.open_field_sheet(press_target.clone(), cx);
                            },
                        ));
                        let wish = (event.kind == EventKind::Birthday)
                            .then(|| wish_number.clone())
                            .flatten()
                            .map(|number| {
                                let text = contact_dates::wish_text(
                                    &card.name,
                                    event.kind,
                                    contact_dates::turning(event, today),
                                );
                                Self::wish_chip(theme, i).on_click(cx.listener(
                                    move |this, _, _, cx| {
                                        let (number, text) = (number.clone(), text.clone());
                                        this.act(
                                            "send a message",
                                            move || {
                                                rovsti_android::telecom::compose_sms(&number, &text)
                                            },
                                            cx,
                                        );
                                    },
                                ))
                            });
                        let more = Self::field_more(theme, ("date-more", i), Icon::LucideCopy)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_field_sheet(more_target.clone(), cx);
                            }));
                        Some(match wish {
                            Some(chip) => Self::field_line_with(theme, main, [chip, more]),
                            None => Self::field_line(theme, main, more),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();

        let address_rows: Vec<AnyElement> = full
            .map(|d| {
                d.addresses
                    .iter()
                    .filter(|a| !a.value.trim().is_empty())
                    .enumerate()
                    .map(|(i, addr)| {
                        let text = addr.value.trim().to_owned();
                        let target = FieldTarget::Address(text.clone());
                        let (more_target, press_target) = (target.clone(), target);
                        let main = Self::field_main(
                            theme,
                            ("address", i),
                            addr.title().into(),
                            text.clone().into(),
                            colors.accent,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            match contact_dates::maps_url(&text) {
                                Some(url) => cx.open_url(&url),
                                None => this.notice = Some("There is no address to open.".into()),
                            }
                            cx.notify();
                        }))
                        .on_long_press(cx.listener(
                            move |this, _, _, cx| {
                                this.open_field_sheet(press_target.clone(), cx);
                            },
                        ));
                        let more =
                            Self::field_more(theme, ("address-more", i), Icon::LucideEllipsis)
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.open_field_sheet(more_target.clone(), cx);
                                }));
                        Self::field_line(theme, main, more)
                    })
                    .collect()
            })
            .unwrap_or_default();

        let website_rows: Vec<AnyElement> = full
            .map(|d| {
                d.websites
                    .iter()
                    .filter(|w| !w.trim().is_empty())
                    .enumerate()
                    .map(|(i, site)| {
                        let text = site.trim().to_owned();
                        let target = FieldTarget::Website(text.clone());
                        let (more_target, press_target) = (target.clone(), target);
                        let main = Self::field_main(
                            theme,
                            ("website", i),
                            "Website".into(),
                            text.clone().into(),
                            colors.accent,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.open_web(&text, cx);
                            cx.notify();
                        }))
                        .on_long_press(cx.listener(
                            move |this, _, _, cx| {
                                this.open_field_sheet(press_target.clone(), cx);
                            },
                        ));
                        let more =
                            Self::field_more(theme, ("website-more", i), Icon::LucideEllipsis)
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.open_field_sheet(more_target.clone(), cx);
                                }));
                        Self::field_line(theme, main, more)
                    })
                    .collect()
            })
            .unwrap_or_default();

        let group_rows: Vec<AnyElement> = full
            .map(|d| {
                self.groups
                    .iter()
                    .filter(|g| d.groups.contains(&g.id))
                    .enumerate()
                    .map(|(i, g)| {
                        let group_id = g.id;
                        tap_row(
                            theme,
                            ("card-group", i),
                            Some(Icon::LucideUsers),
                            g.title.clone(),
                            Tone::Plain,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.close_contact(cx);
                            this.tabs.jump_to(crate::GROUPS_TAB);
                            this.open_group(group_id, cx);
                        }))
                        .into_any_element()
                    })
                    .collect()
            })
            .unwrap_or_default();

        let note: Option<AnyElement> =
            full.map(|d| d.note.trim())
                .filter(|n| !n.is_empty())
                .map(|n| {
                    let text = n.to_owned();
                    let (more_target, press_target) = (
                        FieldTarget::Note(text.clone()),
                        FieldTarget::Note(text.clone()),
                    );
                    let main = Self::field_main(
                        theme,
                        ("note", 0),
                        "Note".into(),
                        text.into(),
                        colors.label,
                    )
                    .on_long_press(cx.listener(move |this, _, _, cx| {
                        this.open_field_sheet(press_target.clone(), cx);
                    }));
                    let more = Self::field_more(theme, ("note-more", 0), Icon::LucideCopy)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.open_field_sheet(more_target.clone(), cx);
                        }));
                    Self::field_line(theme, main, more)
                });

        let whatsapp = self.whatsapp_section(card, theme, cx);
        let calls = self.calls_section(card, theme, cx);
        let call_options =
            self.call_options_section(id, &card.name, !card.numbers.is_empty(), theme, cx);

        let actions_list = section(
            theme,
            None,
            [
                tap_row(
                    theme,
                    "edit",
                    Some(Icon::LucidePencil),
                    "Edit Contact",
                    Tone::Accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.open_editor_for(id, cx)))
                .into_any_element(),
                tap_row(
                    theme,
                    "edit-system",
                    Some(Icon::LucideExternalLink),
                    "Open in Android Contacts",
                    Tone::Accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.act(
                        "edit this contact",
                        move || rovsti_android::telecom::edit_contact(id),
                        cx,
                    );
                }))
                .into_any_element(),
            ],
        );

        let name = SharedString::from(card.name.clone());
        let delete = section(
            theme,
            None,
            [tap_row(
                theme,
                "delete",
                Some(Icon::LucideTrash2),
                "Delete Contact",
                Tone::Danger,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.ask_delete_contact(id, name.clone(), cx);
            }))
            .into_any_element()],
        );

        div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(top)
            .child(head)
            .child(actions)
            .children((!numbers.is_empty()).then(|| section(theme, Some("Phone".into()), numbers)))
            .children(call_options)
            .children(calls)
            .children(whatsapp)
            .children((!emails.is_empty()).then(|| section(theme, Some("Email".into()), emails)))
            .children(
                (!date_rows.is_empty()).then(|| section(theme, Some("Dates".into()), date_rows)),
            )
            .children(
                (!address_rows.is_empty())
                    .then(|| section(theme, Some("Address".into()), address_rows)),
            )
            .children(
                (!website_rows.is_empty())
                    .then(|| section(theme, Some("Websites".into()), website_rows)),
            )
            .children(
                (!group_rows.is_empty()).then(|| section(theme, Some("Groups".into()), group_rows)),
            )
            .children(note.map(|note| section(theme, None, [note])))
            .child(actions_list)
            .child(delete)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::contact_edit::{DraftField, FieldKind};

    use super::*;

    fn draft(emails: &[&str]) -> ContactDraft {
        ContactDraft {
            emails: emails
                .iter()
                .map(|e| DraftField::new(*e, FieldKind::Home))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn copied_vcard_pill_mentions_the_photo() {
        assert_eq!(copied_vcard_words(true), "vCard copied (with photo)");
        assert_eq!(copied_vcard_words(false), "vCard copied");
    }

    #[test]
    fn first_email_skips_blank_values() {
        let d = draft(&["", "  ", " a@b.c "]);
        assert_eq!(first_email(Some(&d)).as_deref(), Some("a@b.c"));
    }

    #[test]
    fn first_email_is_none_without_addresses() {
        assert_eq!(first_email(None), None);
        assert_eq!(first_email(Some(&draft(&[]))), None);
        assert_eq!(first_email(Some(&draft(&[" "]))), None);
    }

    #[test]
    fn share_menu_offers_qr_only_with_something_to_encode() {
        use ShareChoice::*;
        let base = [Text, Vcf, SaveFile, CopyText, CopyVcard];
        assert_eq!(share_choices(false, false, false), base);
        let mut with_qr = base.to_vec();
        with_qr.push(Qr);
        assert_eq!(share_choices(true, false, false), with_qr);
        assert_eq!(share_choices(false, true, false), with_qr);
        with_qr.push(AddToHome);
        assert_eq!(share_choices(true, true, true), with_qr);
        assert_eq!(share_choices(false, false, true).last(), Some(&AddToHome));
    }

    #[test]
    fn copy_as_needs_two_forms() {
        let number = |raw: &str| FieldTarget::Number {
            raw: raw.into(),
            display: raw.into(),
            slot: None,
        };
        // National and international differ for a local number.
        let local = number("0532 111 22 33");
        assert!(local.actions(Some("90")).contains(&FieldAction::CopyAs));
        // Nothing to choose from: one form only.
        let junk = number("123");
        assert!(!junk.actions(None).contains(&FieldAction::CopyAs));
        // Other targets never offer it.
        assert!(
            !FieldTarget::Note("n".into())
                .actions(Some("90"))
                .contains(&FieldAction::CopyAs)
        );
    }

    #[test]
    fn field_sheet_actions_per_target() {
        let number = FieldTarget::Number {
            raw: "+90".into(),
            display: "+90 1".into(),
            slot: None,
        };
        assert_eq!(
            number.actions(None),
            vec![
                FieldAction::Call,
                FieldAction::Message,
                FieldAction::Copy("Copy Number"),
                FieldAction::CopyAs,
                FieldAction::Share("Share Number")
            ]
        );
        assert_eq!(number.dial(), "+90");
        assert_eq!(number.text(), "+90 1");
        assert_eq!(
            FieldTarget::Email {
                address: "a@b.c".into(),
                slot: None
            }
            .actions(None)[0],
            FieldAction::SendEmail
        );
        assert_eq!(FieldTarget::Note("n".into()).actions(None).len(), 1);
        let site = FieldTarget::Website("example.com".into());
        assert_eq!(
            site.actions(None),
            vec![
                FieldAction::OpenLink,
                FieldAction::Copy("Copy Link"),
                FieldAction::Share("Share Link")
            ]
        );
        assert_eq!(site.title(), "example.com");
        assert_eq!(
            org_line("Engineer", "Acme").as_deref(),
            Some("Engineer · Acme")
        );
        assert_eq!(org_line(" ", "Acme").as_deref(), Some("Acme"));
        assert_eq!(org_line("CTO", "").as_deref(), Some("CTO"));
        assert_eq!(org_line("", " "), None);
        let address = FieldTarget::Address("\n 1 Main St\nIzmir".into());
        assert_eq!(
            address.actions(None),
            vec![
                FieldAction::Copy("Copy Address"),
                FieldAction::Share("Share Address"),
                FieldAction::OpenMaps
            ]
        );
        assert_eq!(address.title(), "1 Main St");
        let date = FieldTarget::Date("Birthday · 7 Mar".into());
        assert_eq!(date.actions(None), vec![FieldAction::Copy("Copy Date")]);
        assert_eq!(date.title(), "Date");
    }

    fn number_with(slot: Option<Slot>) -> FieldTarget {
        FieldTarget::Number {
            raw: "+90".into(),
            display: "+90 1".into(),
            slot,
        }
    }

    #[test]
    fn default_rows_need_two_or_more_entries() {
        let slot = |count, primary| {
            Some(Slot {
                index: 0,
                count,
                primary,
            })
        };
        for target in [
            number_with(None),
            number_with(slot(1, false)),
            number_with(slot(1, true)),
        ] {
            assert!(
                !target
                    .actions(None)
                    .iter()
                    .any(|a| matches!(a, FieldAction::SetDefault(_))),
                "{target:?}"
            );
        }
        assert_eq!(
            number_with(slot(2, false)).actions(None).last(),
            Some(&FieldAction::SetDefault(false))
        );
        assert_eq!(
            number_with(slot(3, true)).actions(None).last(),
            Some(&FieldAction::SetDefault(true))
        );
        let email = FieldTarget::Email {
            address: "a@b.c".into(),
            slot: slot(2, true),
        };
        assert_eq!(
            email.actions(None),
            vec![
                FieldAction::SendEmail,
                FieldAction::Copy("Copy Email"),
                FieldAction::SetDefault(true)
            ]
        );
        assert_eq!(FieldAction::SetDefault(false).label(), "Set as default");
        assert_eq!(FieldAction::SetDefault(true).label(), "Clear default");
        assert_eq!(default_notice(false, false), "Default number set");
        assert_eq!(default_notice(true, false), "Default email set");
        assert_eq!(default_notice(true, true), "Default cleared");
    }

    #[test]
    fn slot_of_matches_numbers_by_digits_and_emails_by_case() {
        let mut numbers = vec![
            DraftField::new("0555 111 22 33", FieldKind::Mobile),
            DraftField::new("+90 555 999 88 77", FieldKind::Work),
        ];
        numbers[1].primary = true;
        assert_eq!(
            slot_of(&numbers, "05551112233"),
            Some(Slot {
                index: 0,
                count: 2,
                primary: false
            })
        );
        assert_eq!(
            slot_of(&numbers, "+905559998877"),
            Some(Slot {
                index: 1,
                count: 2,
                primary: true
            })
        );
        assert_eq!(slot_of(&numbers, "123"), None);
        let emails = [DraftField::new("Ada@Example.com", FieldKind::Home)];
        assert_eq!(
            slot_of(&emails, " ada@example.COM ").map(|s| s.index),
            Some(0)
        );
    }

    #[test]
    fn date_copy_text_names_the_event() {
        let e = DraftEvent::new("1990-03-07", EventKind::Birthday);
        assert_eq!(date_copy_text(&e).as_deref(), Some("Birthday · 7 Mar 1990"));
        assert_eq!(
            date_copy_text(&DraftEvent::new("junk", EventKind::Birthday)),
            None
        );
    }
}
