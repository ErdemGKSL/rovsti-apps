//! What a card is asked to show. The app resolves every word from its own string resources (English and Turkish)
//! and sends them in [`CardSpec::texts`]; this crate only lays them out.

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

/// The designs a card can have.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Template {
    /// "Happy birthday, Ayşe!", the age and the date, a photo or initials.
    Birthday,
    /// The name, up to three numbers and a QR code of the vCard.
    Contact,
    /// "My new number" and the number in a large size. A still card only.
    NewNumber,
}

impl Template {
    /// Every template, in the order the app's picker shows them.
    pub const ALL: [Template; 3] = [Template::Birthday, Template::Contact, Template::NewNumber];

    /// Whether the template has a one-shot animated version.
    pub fn animatable(self) -> bool {
        !matches!(self, Template::NewNumber)
    }

    /// The text keys the template prints; [`Self::required_texts`] must be present and not blank.
    pub fn text_keys(self) -> &'static [&'static str] {
        match self {
            Template::Birthday => &["title", "age_line", "date_line"],
            Template::Contact => &["title", "numbers"],
            Template::NewNumber => &["title", "subtitle", "numbers"],
        }
    }

    /// The keys a render refuses to go without (`text_missing`).
    pub fn required_texts(self) -> &'static [&'static str] {
        match self {
            Template::Birthday | Template::Contact => &["title"],
            Template::NewNumber => &["subtitle", "numbers"],
        }
    }
}

/// The colour families of a card. Light palettes use white glass; Ink uses dark glass.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Palette {
    Dawn,
    Lagoon,
    Meadow,
    Dusk,
    Ink,
}

impl Palette {
    /// Every palette, in swatch order.
    pub const ALL: [Palette; 5] = [
        Palette::Dawn,
        Palette::Lagoon,
        Palette::Meadow,
        Palette::Dusk,
        Palette::Ink,
    ];

    /// Whether the card uses dark glass and light ink.
    pub fn dark(self) -> bool {
        matches!(self, Palette::Ink)
    }

    /// The background gradient stops (top-left to bottom-right) and the two soft blobs behind the panel. The first
    /// two stops match the app's swatches (`CardPalette` in `:core-bridge`).
    pub(crate) fn colors(self) -> PaletteColors {
        match self {
            Palette::Dawn => PaletteColors {
                stops: ["#ffc6a8", "#ff9fb0", "#ff8fa3"],
                blobs: ["#fff1b8", "#c98bff"],
                accent: "#ff6f8e",
            },
            Palette::Lagoon => PaletteColors {
                stops: ["#8fe3e8", "#6cc0ec", "#4c9be8"],
                blobs: ["#d8fff6", "#5b6cff"],
                accent: "#2f86de",
            },
            Palette::Meadow => PaletteColors {
                stops: ["#cdeb8b", "#93db8a", "#58c98a"],
                blobs: ["#fffbc2", "#2fb39a"],
                accent: "#2e9e63",
            },
            Palette::Dusk => PaletteColors {
                stops: ["#b9a4f4", "#9286e8", "#6c6bdd"],
                blobs: ["#ffd0ef", "#3d4fd6"],
                accent: "#5a52d6",
            },
            Palette::Ink => PaletteColors {
                stops: ["#3a3f4b", "#252932", "#14161c"],
                blobs: ["#5a4ad1", "#1f7a8c"],
                accent: "#8a7dff",
            },
        }
    }
}

/// The secondary-line ink alpha over the glass panel (`card.rs` paints `.line` and `.number` with it).
pub(crate) const SOFT_INK_ALPHA_LIGHT: f32 = 0.86;
pub(crate) const SOFT_INK_ALPHA_DARK: f32 = 0.78;

fn hex(c: &str) -> [f32; 3] {
    let v = |i: usize| f32::from(u8::from_str_radix(&c[i..i + 2], 16).unwrap_or(0));
    [v(1), v(3), v(5)]
}

fn mix(a: [f32; 3], b: [f32; 3], t: f32) -> [f32; 3] {
    [0, 1, 2].map(|i| a[i] * (1.0 - t) + b[i] * t)
}

fn luminance(c: [f32; 3]) -> f32 {
    let f = |v: f32| {
        let v = v / 255.0;
        if v <= 0.03928 {
            v / 12.92
        } else {
            ((v + 0.055) / 1.055).powf(2.4)
        }
    };
    0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2])
}

impl Palette {
    /// The lowest WCAG contrast of the secondary ink over the glass panel, over every background stop and blob the
    /// panel can sit on (the panel tint and ink values mirror `card.rs`).
    pub(crate) fn worst_secondary_contrast(self) -> f32 {
        let colors = self.colors();
        let (tint, tint_alpha, ink, ink_alpha) = if self.dark() {
            (
                [24.0, 26.0, 34.0],
                0.46,
                [245.0, 246.0, 250.0],
                SOFT_INK_ALPHA_DARK,
            )
        } else {
            ([255.0; 3], 0.34, [20.0, 22.0, 28.0], SOFT_INK_ALPHA_LIGHT)
        };
        colors
            .stops
            .iter()
            .chain(colors.blobs.iter())
            .map(|c| {
                let panel = mix(hex(c), tint, tint_alpha);
                let text = mix(panel, ink, ink_alpha);
                let (a, b) = (luminance(text), luminance(panel));
                (a.max(b) + 0.05) / (a.min(b) + 0.05)
            })
            .fold(f32::MAX, f32::min)
    }
}

/// The CSS colours one palette paints with.
#[derive(Clone, Copy, Debug)]
pub(crate) struct PaletteColors {
    pub stops: [&'static str; 3],
    pub blobs: [&'static str; 2],
    pub accent: &'static str,
}

/// Everything a render needs.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct CardSpec {
    pub template: Template,
    pub palette: Palette,
    /// A one-shot GIF instead of a PNG (ignored for templates that do not move).
    #[serde(default)]
    pub animated: bool,
    /// The words on the card by key (`title`, `age_line`, `date_line`, `subtitle`, `numbers`; numbers are
    /// newline-separated). Unknown keys are ignored.
    #[serde(default)]
    pub texts: BTreeMap<String, String>,
    /// A readable picture file of the contact; `None`, a missing or an unreadable file draws [`Self::initials`].
    #[serde(default)]
    pub photo: Option<PathBuf>,
    #[serde(default)]
    pub initials: String,
    /// The text a QR code on the Contact card encodes; `None` draws no code.
    #[serde(default)]
    pub qr: Option<String>,
}

impl CardSpec {
    /// The text under `key`, trimmed; `None` when absent or blank.
    pub fn text(&self, key: &str) -> Option<&str> {
        self.texts
            .get(key)
            .map(|t| t.trim())
            .filter(|t| !t.is_empty())
    }

    /// Whether this spec renders as an animation.
    pub fn moves(&self) -> bool {
        self.animated && self.template.animatable()
    }
}
