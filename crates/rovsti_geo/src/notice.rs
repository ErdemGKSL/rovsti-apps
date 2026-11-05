//! The "arriving soon" message template sent to a chat.

use std::fmt;
use std::time::Duration;

/// Template used when the user has not written their own.
pub const DEFAULT_TEMPLATE: &str = "Arriving at {stop} in about {min} min.";

/// Rendered messages and templates are capped at this many characters.
pub const MAX_CHARS: usize = 500;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TemplateError {
    Empty,
    TooLong,
}

impl fmt::Display for TemplateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => f.write_str("the message is empty"),
            Self::TooLong => write!(f, "the message is longer than {MAX_CHARS} characters"),
        }
    }
}

impl std::error::Error for TemplateError {}

/// Checks a user-written template. Placeholders are optional.
pub fn validate(template: &str) -> Result<(), TemplateError> {
    let template = template.trim();
    if template.is_empty() {
        Err(TemplateError::Empty)
    } else if template.chars().count() > MAX_CHARS {
        Err(TemplateError::TooLong)
    } else {
        Ok(())
    }
}

/// Fills `{stop}` and `{min}`. Without an ETA `{min}` becomes "a few". The
/// result is trimmed and capped at [`MAX_CHARS`] characters.
pub fn render(template: &str, stop: &str, eta: Option<Duration>) -> String {
    let min = match eta {
        Some(eta) => (eta.as_secs_f64() / 60.0).ceil().max(1.0).to_string(),
        None => "a few".to_owned(),
    };
    let text = template.replace("{stop}", stop).replace("{min}", &min);
    let text = text.trim();
    match text.char_indices().nth(MAX_CHARS) {
        Some((end, _)) => text[..end].trim_end().to_owned(),
        None => text.to_owned(),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn fills_placeholders() {
        let text = render(DEFAULT_TEMPLATE, "Kadikoy", Some(Duration::from_secs(250)));
        assert_eq!(text, "Arriving at Kadikoy in about 5 min.");
    }

    #[test]
    fn short_eta_rounds_up_to_one_minute() {
        let text = render("{min}", "x", Some(Duration::from_secs(5)));
        assert_eq!(text, "1");
    }

    #[test]
    fn missing_eta_says_a_few() {
        let text = render(DEFAULT_TEMPLATE, "Kadikoy", None);
        assert_eq!(text, "Arriving at Kadikoy in about a few min.");
    }

    #[test]
    fn repeated_placeholders_and_trim() {
        assert_eq!(render("  {stop}, {stop}!  ", "A", None), "A, A!");
    }

    #[test]
    fn caps_at_500_chars() {
        let text = render(&"a".repeat(900), "x", None);
        assert_eq!(text.chars().count(), 500);
    }

    #[test]
    fn cap_respects_unicode_boundaries() {
        // 3-byte chars: a byte-based cut would split one.
        let text = render(&"\u{20ac}".repeat(600), "x", None);
        assert_eq!(text.chars().count(), 500);
        let text = render(
            &format!("{{stop}}{}", "\u{1f68c}".repeat(600)),
            "\u{00fc}",
            None,
        );
        assert_eq!(text.chars().count(), 500);
        assert!(text.starts_with('\u{00fc}'));
    }

    #[test]
    fn validate_rejects_empty_and_long() {
        assert_eq!(validate("  \n"), Err(TemplateError::Empty));
        assert_eq!(validate(&"a".repeat(501)), Err(TemplateError::TooLong));
        assert_eq!(validate(&"a".repeat(500)), Ok(()));
        assert_eq!(validate(DEFAULT_TEMPLATE), Ok(()));
    }
}
