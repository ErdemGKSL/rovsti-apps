//! The contact card: the page one contact opens to.

use std::{iter, sync::Arc};

use gpui::{
    AnyElement, ClipboardItem, Context, InteractiveElement, IntoElement, ParentElement,
    RenderImage, Role, SharedString, StatefulInteractiveElement, Styled, div, px, svg,
};
use rovsti_core::{
    contact_card::ContactCard,
    contact_dates,
    contact_edit::{ContactDraft, DraftEvent, EventKind},
    vcard,
};
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, section},
    glass::{GlassSpec, glass},
    theme::{self, hue_pair},
    tokens::{Theme, metrics, shadow, typography},
};

use crate::{
    ContactsApp, backend,
    photos::photo_avatar,
    ui::{Tone, tap_row},
};

/// What the field sheet acts on.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FieldTarget {
    Number {
        raw: String,
        display: String,
    },
    Email(String),
    Note(String),
    /// A postal address (may span lines).
    Address(String),
    /// A dated event as copyable text ("Birthday · 7 Mar 1990").
    Date(String),
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
}

impl FieldAction {
    fn label(self) -> &'static str {
        match self {
            Self::Call => "Call",
            Self::Message => "Message",
            Self::SendEmail => "Send Email",
            Self::Copy(label) | Self::Share(label) => label,
            Self::OpenMaps => "Open in Maps",
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
        }
    }
}

impl FieldTarget {
    /// The rows the field sheet offers, top to bottom.
    pub(crate) fn actions(&self) -> Vec<FieldAction> {
        match self {
            Self::Number { .. } => vec![
                FieldAction::Call,
                FieldAction::Message,
                FieldAction::Copy("Copy Number"),
                FieldAction::Share("Share Number"),
            ],
            Self::Email(_) => vec![FieldAction::SendEmail, FieldAction::Copy("Copy Email")],
            Self::Note(_) => vec![FieldAction::Copy("Copy Note")],
            Self::Address(_) => vec![
                FieldAction::Copy("Copy Address"),
                FieldAction::Share("Share Address"),
                FieldAction::OpenMaps,
            ],
            Self::Date(_) => vec![FieldAction::Copy("Copy Date")],
        }
    }

    /// What Copy and Share send.
    fn text(&self) -> &str {
        match self {
            Self::Number { display, .. } => display,
            Self::Email(text) | Self::Note(text) | Self::Address(text) | Self::Date(text) => text,
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
    Qr,
}

impl ShareChoice {
    fn label(self) -> &'static str {
        match self {
            Self::Text => "Share as text",
            Self::Vcf => "Share contact file (.vcf)",
            Self::Qr => "Show QR code",
        }
    }

    fn icon(self) -> Icon {
        match self {
            Self::Text => Icon::LucideUpload,
            Self::Vcf => Icon::LucideContact,
            Self::Qr => Icon::LucideQrCode,
        }
    }
}

/// The Share menu entries: the QR code needs a number or an email to carry.
pub(crate) fn share_choices(has_numbers: bool, has_emails: bool) -> Vec<ShareChoice> {
    let mut choices = vec![ShareChoice::Text, ShareChoice::Vcf];
    if has_numbers || has_emails {
        choices.push(ShareChoice::Qr);
    }
    choices
}

/// The copyable text of a dated event ("Birthday · 7 Mar 1990"), or `None`
/// when the date is unreadable.
pub(crate) fn date_copy_text(event: &DraftEvent) -> Option<String> {
    let date = contact_dates::parse(&event.date)?;
    Some(format!("{} · {}", event.title(), date.text()))
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

impl ContactsApp {
    /// Closes the field sheet without animation (the card is going away).
    pub(crate) fn close_field_sheet_now(&mut self) {
        self.field_sheet.close();
        self.field_target = None;
        self.share_sheet.close();
        self.qr_sheet.close();
        self.qr_card = None;
    }

    pub(crate) fn open_field_sheet(&mut self, target: FieldTarget, cx: &mut Context<Self>) {
        self.field_target = Some(target);
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
                let outcome = backend::share_vcf(&name, &vcard::write(&[draft]));
                if let Err(words) = outcome {
                    self.notice = Some(format!("Can't share the contact file: {words}.").into());
                }
                cx.notify();
            }
            ShareChoice::Qr => self.open_qr(cx),
        }
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
                        ShareChoice::Qr => "share-qr",
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
            FieldAction::Share(_) => self.field_act(
                "share",
                move || rovsti_android::telecom::share_text(&text),
                cx,
            ),
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
        let rows: Vec<AnyElement> = target
            .actions()
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
        body = body.children(rows);
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
            .map(|d| d.org.trim())
            .filter(|o| !o.is_empty())
            .map(|o| SharedString::from(o.to_owned()));
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
                let target = FieldTarget::Number {
                    raw: number.raw.clone(),
                    display: number.display.clone(),
                };
                let (more_target, press_target) = (target.clone(), target);
                let label: SharedString = if number.default {
                    format!("{} · default", number.label).into()
                } else {
                    number.label.clone().into()
                };
                let main = Self::field_main(
                    theme,
                    ("number", i),
                    label,
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
                Self::field_line(theme, main, more)
            })
            .collect();

        let emails: Vec<AnyElement> = full
            .map(|d| {
                d.emails
                    .iter()
                    .enumerate()
                    .map(|(i, email)| {
                        let address = email.value.clone();
                        let (more_target, press_target) = (
                            FieldTarget::Email(address.clone()),
                            FieldTarget::Email(address.clone()),
                        );
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
                        Self::field_line(theme, main, more)
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
        assert_eq!(share_choices(false, false), vec![Text, Vcf]);
        assert_eq!(share_choices(true, false), vec![Text, Vcf, Qr]);
        assert_eq!(share_choices(false, true), vec![Text, Vcf, Qr]);
    }

    #[test]
    fn field_sheet_actions_per_target() {
        let number = FieldTarget::Number {
            raw: "+90".into(),
            display: "+90 1".into(),
        };
        assert_eq!(
            number.actions(),
            vec![
                FieldAction::Call,
                FieldAction::Message,
                FieldAction::Copy("Copy Number"),
                FieldAction::Share("Share Number")
            ]
        );
        assert_eq!(number.dial(), "+90");
        assert_eq!(number.text(), "+90 1");
        assert_eq!(
            FieldTarget::Email("a@b.c".into()).actions()[0],
            FieldAction::SendEmail
        );
        assert_eq!(FieldTarget::Note("n".into()).actions().len(), 1);
        let address = FieldTarget::Address("\n 1 Main St\nIzmir".into());
        assert_eq!(
            address.actions(),
            vec![
                FieldAction::Copy("Copy Address"),
                FieldAction::Share("Share Address"),
                FieldAction::OpenMaps
            ]
        );
        assert_eq!(address.title(), "1 Main St");
        let date = FieldTarget::Date("Birthday · 7 Mar".into());
        assert_eq!(date.actions(), vec![FieldAction::Copy("Copy Date")]);
        assert_eq!(date.title(), "Date");
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
