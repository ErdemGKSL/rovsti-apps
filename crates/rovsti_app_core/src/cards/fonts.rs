//! Bundled fonts for share cards. Takumi loads no system fonts, so every glyph a card draws comes from here and the
//! output is the same on every device.
//!
//! Inter 4.1 (SIL OFL 1.1, see `assets/fonts/OFL.txt`) in static Regular, Semibold and Bold. It covers Latin
//! Extended-A, so the Turkish letters (ğ ş ı İ ç ö ü) are real glyphs. There is no emoji font: an emoji in a card
//! text is skipped, not drawn as a box.

use std::sync::OnceLock;

use takumi::prelude::{FontResource, FontSource, Fonts};

use super::CardError;

/// The one family name the card CSS refers to.
pub const FAMILY: &str = "Inter";

static REGULAR: &[u8] = include_bytes!("../../assets/fonts/Inter-Regular.ttf");
static SEMIBOLD: &[u8] = include_bytes!("../../assets/fonts/Inter-SemiBold.ttf");
static BOLD: &[u8] = include_bytes!("../../assets/fonts/Inter-Bold.ttf");

static BUNDLED: OnceLock<Fonts> = OnceLock::new();

/// The bundled font set, registered once per process and shared by every render.
pub fn bundled() -> Result<&'static Fonts, CardError> {
    if let Some(fonts) = BUNDLED.get() {
        return Ok(fonts);
    }
    let mut fonts = Fonts::default();
    for bytes in [REGULAR, SEMIBOLD, BOLD] {
        // `Fonts::register` reads the family and weight from the font file itself.
        fonts
            .register(FontResource::new(FontSource::from_static(bytes)))
            .map_err(|e| CardError::Font(e.to_string()))?;
    }
    Ok(BUNDLED.get_or_init(|| fonts))
}
