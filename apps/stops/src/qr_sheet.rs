//! The QR code sheet for a map point: the code on a white card, then Save (to
//! Photos) and Share image.
//!
//! The payload is a `geo:` link (`rovsti_geo::dest::point_qr_payload`), drawn
//! by `rovsti_wa_ui::qr_png`. The card stays dark-on-white in dark mode, since
//! scanners need the contrast.

use std::sync::Arc;

use gpui::{
    AnyElement, Context, Image, ImageFormat, InteractiveElement, IntoElement, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, div, img, px,
};
use rovsti_core::number_format::file_slug_opt;
use rovsti_ui::{
    components::glass_pill,
    theme,
    tokens::{Theme, radius, typography},
};

use crate::{StopsApp, backend};

/// Side length of the PNG that is saved and shared.
const QR_PX: u32 = 720;

/// The QR code shown in the sheet.
#[derive(Clone)]
pub struct QrCard {
    png: Vec<u8>,
    image: Arc<Image>,
    name: String,
}

impl std::fmt::Debug for QrCard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("QrCard")
            .field("name", &self.name)
            .field("bytes", &self.png.len())
            .finish()
    }
}

impl PartialEq for QrCard {
    fn eq(&self, other: &Self) -> bool {
        self.name == other.name && self.png == other.png
    }
}

/// The gallery name of a point's QR image.
pub fn file_name(name: &str) -> String {
    let slug = file_slug_opt(name).unwrap_or_else(|| "point".to_owned());
    format!("rovsti-stops-{slug}-qr.png")
}

/// The caption under the code: the name, or a plain fallback.
pub fn caption(name: &str) -> String {
    match name.trim() {
        "" => "Map point".to_owned(),
        name => name.to_owned(),
    }
}

/// Draws the card for `payload`, or says why not.
pub fn make_card(payload: &str, name: &str) -> Result<QrCard, &'static str> {
    let png = rovsti_wa_ui::qr_png(payload, QR_PX).map_err(|error| {
        log::warn!("rendering the point QR failed: {error}");
        "Could not draw the QR code"
    })?;
    Ok(QrCard {
        image: Arc::new(Image::from_bytes(ImageFormat::Png, png.clone())),
        png,
        name: name.trim().to_owned(),
    })
}

impl StopsApp {
    fn qr_save(&mut self, card: &QrCard, cx: &mut Context<Self>) {
        let outcome = backend::save_png(&file_name(&card.name), &card.png);
        self.sheet.close();
        let pill = match outcome {
            Ok(()) => "Saved to Photos".to_owned(),
            Err(words) => format!("Can't save the QR code: {words}"),
        };
        self.show_pill(pill, None, cx);
        cx.notify();
    }

    fn qr_share(&mut self, card: &QrCard, cx: &mut Context<Self>) {
        let outcome = backend::share_png(&file_name(&card.name), &card.png);
        self.sheet.close();
        if let Err(words) = outcome {
            self.show_pill(format!("Could not share: {words}"), None, cx);
        }
        cx.notify();
    }

    /// The QR sheet content: the code on a white card, the name, Save and Share.
    pub(crate) fn qr_body(
        &mut self,
        theme: Theme,
        card: QrCard,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let code = div().flex().justify_center().pb(px(12.0)).child(
            div()
                .p(px(14.0))
                .rounded(radius::CARD)
                .bg(gpui::white())
                .child(img(Arc::clone(&card.image)).size(px(240.0))),
        );
        let save = card.clone();
        let share = card.clone();
        div()
            .flex()
            .flex_col()
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(4.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(caption(&card.name))),
            )
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(12.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child("Scan with a phone camera to open this point in a maps app."),
            )
            .child(code)
            .child(
                div()
                    .flex()
                    .justify_center()
                    .gap(px(12.0))
                    .px(px(16.0))
                    .child(
                        glass_pill(theme, "Save")
                            .id("qr-save")
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| this.qr_save(&save, cx))),
                    )
                    .child(
                        glass_pill(theme, "Share")
                            .id("qr-share")
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| this.qr_share(&share, cx))),
                    ),
            )
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_names_are_slugs_with_a_fallback() {
        assert_eq!(file_name("Home stop"), "rovsti-stops-Home-stop-qr.png");
        assert_eq!(file_name("😀"), "rovsti-stops-point-qr.png");
        assert_eq!(file_name(""), "rovsti-stops-point-qr.png");
    }

    #[test]
    fn the_caption_falls_back() {
        assert_eq!(caption("  "), "Map point");
        assert_eq!(caption(" Pier "), "Pier");
    }

    #[test]
    fn the_card_draws_a_png() {
        let card = make_card("geo:41.0,28.9", " Pier ").expect("qr");
        assert!(card.png.starts_with(&[0x89, b'P', b'N', b'G']));
        assert_eq!(card.name, "Pier");
        assert_eq!(card, card.clone());
    }
}
