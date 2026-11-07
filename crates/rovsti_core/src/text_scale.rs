//! Chat text size setting.

use serde::{Deserialize, Serialize};

/// How large message text is drawn.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChatTextSize {
    Small,
    #[default]
    Default,
    Large,
    Larger,
}

impl ChatTextSize {
    pub const ALL: [Self; 4] = [Self::Small, Self::Default, Self::Large, Self::Larger];

    pub fn label(self) -> &'static str {
        match self {
            Self::Small => "Small",
            Self::Default => "Default",
            Self::Large => "Large",
            Self::Larger => "Larger",
        }
    }

    pub fn factor(self) -> f32 {
        match self {
            Self::Small => 0.875,
            Self::Default => 1.0,
            Self::Large => 1.15,
            Self::Larger => 1.3,
        }
    }

    /// `base` scaled and rounded to 0.5 px.
    pub fn scaled(self, base: f32) -> f32 {
        (base * self.factor() * 2.0).round() / 2.0
    }

    /// Sample line for the settings preview.
    pub fn preview_text() -> &'static str {
        "The quick brown fox…"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_and_factors() {
        let labels: Vec<_> = ChatTextSize::ALL.iter().map(|s| s.label()).collect();
        assert_eq!(labels, ["Small", "Default", "Large", "Larger"]);
        let f: Vec<_> = ChatTextSize::ALL.iter().map(|s| s.factor()).collect();
        assert_eq!(f, [0.875, 1.0, 1.15, 1.3]);
        assert_eq!(ChatTextSize::default(), ChatTextSize::Default);
    }

    #[test]
    fn scaled_rounds_to_half_px() {
        assert_eq!(ChatTextSize::Default.scaled(16.0), 16.0);
        assert_eq!(ChatTextSize::Small.scaled(16.0), 14.0);
        assert_eq!(ChatTextSize::Large.scaled(16.0), 18.5);
        assert_eq!(ChatTextSize::Larger.scaled(15.0), 19.5);
        assert_eq!(ChatTextSize::Small.scaled(15.0), 13.0);
    }

    #[test]
    fn serde_snake_case() {
        assert_eq!(
            serde_json::to_string(&ChatTextSize::Large).expect("ok"),
            "\"large\""
        );
        let s: ChatTextSize = serde_json::from_str("\"larger\"").expect("ok");
        assert_eq!(s, ChatTextSize::Larger);
        assert!(serde_json::from_str::<ChatTextSize>("\"huge\"").is_err());
    }

    #[test]
    fn preview_not_empty() {
        assert!(!ChatTextSize::preview_text().is_empty());
    }
}
