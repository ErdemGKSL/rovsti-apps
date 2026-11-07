//! The contact QR code: a glass sheet with the code on a white card, and
//! Save (to Photos) and Share image.
//!
//! The payload is a vCard (see `rovsti_core::number_format::contact_qr`), drawn
//! by `rovsti_wa_ui::qr_png`. The card stays dark-on-white in dark mode, since
//! scanners need the contrast.

use std::sync::Arc;

use gpui::{
    AnyElement, Context, Image, ImageFormat, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, img, px,
};
use rovsti_core::{
    contact_edit::ContactDraft,
    number_format::{self, QR_MAX_BYTES, QrTooBig},
    vcard,
};
use rovsti_ui::{
    Icon, theme,
    tokens::{Theme, radius, typography},
};

use crate::{
    ContactsApp, backend,
    ui::{Tone, tap_row},
};

/// Side length of the PNG that is saved and shared.
const QR_PX: u32 = 720;

/// The QR code shown in the sheet.
pub(crate) struct QrCard {
    png: Vec<u8>,
    image: Arc<Image>,
    name: String,
}

/// Why a contact has no QR code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum QrFailure {
    TooBig,
    Draw,
}

impl QrFailure {
    pub(crate) fn words(self) -> &'static str {
        match self {
            Self::TooBig => "Too many details for one QR code",
            Self::Draw => "Could not draw the QR code",
        }
    }
}

/// The vCard text a contact's QR code carries: name and numbers (the numbers
/// that fit), or name and emails for a contact without numbers.
pub(crate) fn qr_payload(draft: &ContactDraft) -> Result<String, QrTooBig> {
    let name = draft.display_name();
    let numbers: Vec<(&str, Option<&str>)> = draft
        .numbers
        .iter()
        .map(|n| (n.value.as_str(), Some(n.kind.title())))
        .collect();
    if numbers.iter().any(|(raw, _)| !raw.trim().is_empty()) {
        return number_format::contact_qr(&name, &numbers, None).map(|q| q.payload);
    }
    let emails: Vec<_> = draft
        .emails
        .iter()
        .filter(|e| !e.value.trim().is_empty())
        .cloned()
        .collect();
    if emails.is_empty() {
        return Err(QrTooBig);
    }
    let payload = vcard::write(&[ContactDraft {
        given: name,
        emails,
        ..ContactDraft::default()
    }]);
    if payload.len() <= QR_MAX_BYTES {
        Ok(payload)
    } else {
        Err(QrTooBig)
    }
}

/// Builds the sheet content for `draft`.
pub(crate) fn make_card(draft: &ContactDraft) -> Result<QrCard, QrFailure> {
    let payload = qr_payload(draft).map_err(|_| QrFailure::TooBig)?;
    let png = rovsti_wa_ui::qr_png(&payload, QR_PX).map_err(|error| {
        log::warn!("rendering the contact QR failed: {error}");
        QrFailure::Draw
    })?;
    Ok(QrCard {
        image: Arc::new(Image::from_bytes(ImageFormat::Png, png.clone())),
        png,
        name: draft.display_name(),
    })
}

impl ContactsApp {
    /// Show QR code on the card's Share menu.
    pub(crate) fn open_qr(&mut self, cx: &mut Context<Self>) {
        self.share_sheet.close();
        let Some(draft) = self.open_draft.as_ref() else {
            self.notice = Some("The contact is still loading.".into());
            cx.notify();
            return;
        };
        match make_card(draft) {
            Ok(card) => {
                self.qr_card = Some(card);
                self.qr_sheet.open();
            }
            Err(failure) => self.notice = Some(failure.words().into()),
        }
        cx.notify();
    }

    pub(crate) fn close_qr_sheet(&mut self, cx: &mut Context<Self>) {
        self.qr_sheet.close();
        cx.notify();
    }

    fn qr_save(&mut self, cx: &mut Context<Self>) {
        let Some(card) = &self.qr_card else {
            return;
        };
        let outcome = backend::save_png(&number_format::qr_file_name(&card.name), &card.png);
        self.qr_sheet.close();
        self.notice = Some(match outcome {
            Ok(()) => "Saved to Photos".into(),
            Err(words) => format!("Can't save the QR code: {words}.").into(),
        });
        cx.notify();
    }

    fn qr_share(&mut self, cx: &mut Context<Self>) {
        let Some(card) = &self.qr_card else {
            return;
        };
        let outcome = backend::share_png(&number_format::qr_file_name(&card.name), &card.png);
        self.qr_sheet.close();
        if let Err(words) = outcome {
            self.notice = Some(format!("Can't share the QR code: {words}.").into());
        }
        cx.notify();
    }

    /// The QR sheet over the card, once it is visible.
    pub(crate) fn qr_sheet_view(
        &mut self,
        theme: Theme,
        window: &gpui::Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.qr_sheet.set_reduced(window.prefers_reduced_motion());
        self.qr_sheet.set_viewport(window.viewport_size());
        let (image, name) = {
            let card = self.qr_card.as_ref()?;
            (Arc::clone(&card.image), card.name.clone())
        };
        let colors = theme::colors(theme);
        let code = div()
            .flex()
            .justify_center()
            .pb(px(12.0))
            .child(
                div()
                    .p(px(14.0))
                    .rounded(radius::CARD)
                    .bg(gpui::white())
                    .child(img(image).size(px(240.0))),
            )
            .into_any_element();
        let body = div()
            .flex()
            .flex_col()
            .pb(px(12.0))
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(4.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(name)),
            )
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(12.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child("Scan with a phone camera to add this contact."),
            )
            .child(code)
            .child(
                tap_row(
                    theme,
                    "qr-save",
                    Some(Icon::LucideDownload),
                    "Save to Photos",
                    Tone::Plain,
                )
                .on_click(cx.listener(|this, _, _, cx| this.qr_save(cx))),
            )
            .child(
                tap_row(
                    theme,
                    "qr-share",
                    Some(Icon::LucideUpload),
                    "Share image",
                    Tone::Plain,
                )
                .on_click(cx.listener(|this, _, _, cx| this.qr_share(cx))),
            );
        let entity = cx.entity();
        let drag = entity.clone();
        self.qr_sheet.render(
            theme,
            window,
            "Contact QR code",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_qr_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.qr_sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::contact_edit::{DraftField, FieldKind};

    use super::*;

    fn draft(numbers: &[&str], emails: &[&str]) -> ContactDraft {
        ContactDraft {
            given: "Ayse".into(),
            family: "Yilmaz".into(),
            numbers: numbers
                .iter()
                .map(|n| DraftField::new(*n, FieldKind::Mobile))
                .collect(),
            emails: emails
                .iter()
                .map(|e| DraftField::new(*e, FieldKind::Home))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn payload_carries_the_numbers() {
        let p = qr_payload(&draft(&["+905321234567"], &["a@b.co"])).expect("qr");
        assert!(p.contains("+905321234567"));
        assert!(p.contains("Ayse"));
    }

    #[test]
    fn payload_falls_back_to_emails() {
        let p = qr_payload(&draft(&[" "], &["a@b.co"])).expect("qr");
        assert!(p.contains("a@b.co"));
    }

    #[test]
    fn nothing_to_encode_is_refused() {
        assert_eq!(qr_payload(&draft(&[], &[])), Err(QrTooBig));
        let long = "x".repeat(QR_MAX_BYTES);
        assert_eq!(
            qr_payload(&draft(&[], &[&format!("{long}@b.co")])),
            Err(QrTooBig)
        );
    }

    #[test]
    fn the_card_draws_a_png() {
        let card = make_card(&draft(&["+905321234567"], &[])).expect("qr");
        assert!(card.png.starts_with(&[0x89, b'P', b'N', b'G']));
        assert_eq!(
            QrFailure::TooBig.words(),
            "Too many details for one QR code"
        );
    }
}
