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
