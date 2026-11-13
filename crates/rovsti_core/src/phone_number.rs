//! Phone number normalisation for matching call log, SMS and WhatsApp
//! entries to the same contact.
//!
//! This is deliberately small: it canonicalises what the user typed into
//! digits with an optional leading `+`, and compares numbers by their
//! national significant tail. Full libphonenumber-grade parsing can replace
//! the internals later without changing the public type.

use std::fmt;

/// How many trailing digits two numbers must share to be treated as the same
/// line when one of them lacks a country code (Android's `PhoneNumberUtils`
/// uses 7; 9 avoids most false positives on mobile numbers).
const MATCH_TAIL: usize = 9;

/// The fewest national digits a number needs to have an E.164 form; shorter
/// ones are short codes and service numbers (`112`, `4440123`) that only ever
/// match exactly.
pub const MIN_E164_NSN: usize = 8;

/// Invisible direction and format marks that contact apps paste around
/// numbers (LRM, RLM, embeddings, isolates, zero-width, BOM). They carry no
/// digit and are ignored everywhere a number is read.
pub(crate) fn is_format_char(c: char) -> bool {
    matches!(
        c,
        '\u{200b}'..='\u{200f}'
            | '\u{202a}'..='\u{202e}'
            | '\u{2060}'
            | '\u{2066}'..='\u{2069}'
            | '\u{061c}'
            | '\u{feff}'
    )
}

/// `+90 (0) 555 ...`: the optional trunk zero written after a country code is
/// not part of the number. Removes the first `(0)` of an international number.
pub(crate) fn drop_optional_trunk(raw: &str) -> std::borrow::Cow<'_, str> {
    let international = raw
        .trim_start_matches(|c: char| c.is_whitespace() || is_format_char(c))
        .starts_with('+');
    match raw.find("(0)") {
        Some(at) if international => {
            let mut owned = String::with_capacity(raw.len());
            owned.push_str(&raw[..at]);
            owned.push_str(&raw[at + 3..]);
            std::borrow::Cow::Owned(owned)
        }
        _ => std::borrow::Cow::Borrowed(raw),
    }
}

/// The ASCII digits of `raw` as search sees them: format marks and a
/// `(0)` after the country code are ignored.
pub(crate) fn search_digits(raw: &str) -> String {
    drop_optional_trunk(raw)
        .chars()
        .filter(char::is_ascii_digit)
        .collect()
}

/// True for what a call log or a caller-ID writes for a caller nobody can
/// name: an empty number, Android's presentation placeholders (`-1` unknown,
/// `-2` private, `-3` payphone) and the words some phones store instead.
/// Such a number never matches a contact.
pub fn is_withheld(raw: &str) -> bool {
    let text = raw.trim_matches(|c: char| c.is_whitespace() || is_format_char(c));
    if text.is_empty() || matches!(text, "-1" | "-2" | "-3" | "-4") {
        return true;
    }
    let lower = text.to_lowercase();
    matches!(
        lower.as_str(),
        "unknown" | "private" | "anonymous" | "payphone" | "restricted" | "withheld"
    )
}

/// `raw` without an extension or a dial string after the number:
/// `+90 212 555 00 00 ext. 12`, `x12`, `;ext=12`, `,12` (pause) and `;12`
/// (wait) all give `+90 212 555 00 00`. A service code (`*`, `#`) is kept
/// whole: `*#06#` is not a number with an extension.
pub fn strip_extension(raw: &str) -> &str {
    if raw.contains(['*', '#']) && !raw.trim_start().starts_with('+') {
        return raw;
    }
    let lower = raw.to_ascii_lowercase();
    let mut cut = raw.len();
    for marker in [",", ";", "#", "ext", "x", "p", "w"] {
        if let Some(at) = lower.find(marker) {
            // Only after some digits: a leading marker is not an extension.
            if raw[..at].bytes().any(|b| b.is_ascii_digit()) {
                cut = cut.min(at);
            }
        }
    }
    raw[..cut].trim_end_matches(|c: char| c.is_whitespace() || c == '.' || c == ':')
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub struct PhoneNumber {
    /// Digits only, prefixed with `+` when the number is international.
    canonical: String,
}

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum PhoneNumberError {
    #[error("phone number is empty")]
    Empty,
    #[error("phone number contains invalid character {0:?}")]
    InvalidCharacter(char),
    #[error("phone number has {0} digits; at most 15 are allowed (E.164)")]
    TooLong(usize),
}

impl PhoneNumber {
    /// Parses user or provider input such as `+90 (532) 123-45-67`,
    /// `0532 123 45 67` or `00 49 151 2345678`.
    pub fn parse(input: &str) -> Result<Self, PhoneNumberError> {
        let cleaned = drop_optional_trunk(input);
        let trimmed = cleaned.trim_matches(|c: char| c.is_whitespace() || is_format_char(c));
        let (international, rest) = if let Some(rest) = trimmed.strip_prefix('+') {
            (true, rest)
        } else if let Some(rest) = trimmed.strip_prefix("00") {
            (true, rest)
        } else {
            (false, trimmed)
        };

        let mut digits = String::with_capacity(rest.len());
        for c in rest.chars() {
            match c {
                '0'..='9' => digits.push(c),
                ' ' | '-' | '.' | '(' | ')' | '/' | '\u{a0}' => {}
                c if is_format_char(c) => {}
                other => return Err(PhoneNumberError::InvalidCharacter(other)),
            }
        }
        if digits.is_empty() {
            return Err(PhoneNumberError::Empty);
        }
        if digits.len() > 15 {
            return Err(PhoneNumberError::TooLong(digits.len()));
        }

        let canonical = if international {
            format!("+{digits}")
        } else {
            digits
        };
        Ok(Self { canonical })
    }

    pub fn as_str(&self) -> &str {
        &self.canonical
    }

    pub fn is_international(&self) -> bool {
        self.canonical.starts_with('+')
    }

    /// The digits without the `+`.
    pub fn digits(&self) -> &str {
        self.canonical.trim_start_matches('+')
    }

    /// The number in E.164 (`+905321234567`) with `home_cc` (`"90"`) as the
    /// country of a national number: one trunk `0` is dropped, `00` was read
    /// as `+` by [`Self::parse`], and a number that already starts with the
    /// home code and is too long to be national (`905321234567`) only lacks
    /// its `+`. `None` for short codes (under [`MIN_E164_NSN`] digits), and
    /// for a national number when the home country is unknown.
    pub fn to_e164(&self, home_cc: Option<&str>) -> Option<String> {
        let digits = self.digits();
        if self.is_international() {
            return (digits.len() >= MIN_E164_NSN).then(|| self.canonical.clone());
        }
        let cc = home_cc
            .map(|c| c.trim().trim_start_matches('+'))
            .filter(|c| (1..=3).contains(&c.len()) && c.bytes().all(|b| b.is_ascii_digit()))
            .filter(|c| !c.starts_with('0'))?;
        if let Some(nsn) = digits.strip_prefix('0') {
            return (nsn.len() >= MIN_E164_NSN && !nsn.starts_with('0'))
                .then(|| format!("+{cc}{nsn}"));
        }
        if digits.len() < MIN_E164_NSN {
            return None;
        }
        if digits.starts_with(cc) && digits.len() > cc.len() + MIN_E164_NSN && digits.len() >= 11 {
            return Some(format!("+{digits}"));
        }
        Some(format!("+{cc}{digits}"))
    }

    /// True when both numbers very likely reach the same line, e.g.
    /// `+905321234567` and `05321234567`.
    pub fn matches(&self, other: &Self) -> bool {
        if self.is_international() && other.is_international() {
            return self.canonical == other.canonical;
        }
        let (a, b) = (self.digits(), other.digits());
        let tail = MATCH_TAIL.min(a.len()).min(b.len());
        // Short codes (e.g. 112, 4440000) must match exactly.
        if tail < MATCH_TAIL {
            return a == b;
        }
        a[a.len() - tail..] == b[b.len() - tail..]
    }

    /// The WhatsApp user part of a JID (`<digits>@s.whatsapp.net`), which is
    /// only defined for international numbers.
    pub fn whatsapp_user(&self) -> Option<&str> {
        self.is_international().then(|| self.digits())
    }
}

impl fmt::Display for PhoneNumber {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.canonical)
    }
}

impl std::str::FromStr for PhoneNumber {
    type Err = PhoneNumberError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Self::parse(s)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn n(s: &str) -> PhoneNumber {
        PhoneNumber::parse(s).expect("valid number")
    }

    #[test]
    fn strips_formatting() {
        assert_eq!(n("+90 (532) 123-45-67").as_str(), "+905321234567");
        assert_eq!(n("0532 123 45 67").as_str(), "05321234567");
        assert_eq!(n("00 49 151 2345678").as_str(), "+491512345678");
    }

    #[test]
    fn format_marks_and_optional_trunk_zero_are_ignored() {
        assert_eq!(
            n("\u{202a}+90 555 111 22 33\u{202c}").as_str(),
            "+905551112233"
        );
        assert_eq!(n("\u{200e}+90\u{200f} 555").as_str(), "+90555");
        assert_eq!(n("+90 (0) 555 111 22 33").as_str(), "+905551112233");
        assert_eq!(n("+44 (0)20 7946 0018").as_str(), "+442079460018");
        // Without a country code the (0) is just a digit group.
        assert_eq!(n("(0)555").as_str(), "0555");
    }

    #[test]
    fn rejects_garbage() {
        assert_eq!(PhoneNumber::parse("  "), Err(PhoneNumberError::Empty));
        assert_eq!(
            PhoneNumber::parse("+1 555 CALL"),
            Err(PhoneNumberError::InvalidCharacter('C'))
        );
        assert_eq!(
            PhoneNumber::parse("1234567890123456"),
            Err(PhoneNumberError::TooLong(16))
        );
    }

    #[test]
    fn national_and_international_forms_match() {
        assert!(n("+905321234567").matches(&n("05321234567")));
        assert!(!n("+905321234567").matches(&n("+905321234568")));
        assert!(!n("+15551234567").matches(&n("+445551234567")));
    }

    #[test]
    fn short_codes_match_exactly() {
        assert!(n("112").matches(&n("112")));
        assert!(!n("112").matches(&n("0112")));
    }

    #[test]
    fn e164_with_the_home_country() {
        let tr = Some("90");
        assert_eq!(
            n("+90 532 123 45 67").to_e164(tr).as_deref(),
            Some("+905321234567")
        );
        assert_eq!(
            n("0532 123 45 67").to_e164(tr).as_deref(),
            Some("+905321234567")
        );
        assert_eq!(
            n("(532) 123-45-67").to_e164(tr).as_deref(),
            Some("+905321234567")
        );
        assert_eq!(
            n("0090 532 123 45 67").to_e164(tr).as_deref(),
            Some("+905321234567")
        );
        // The `+` is missing, the country code is not.
        assert_eq!(
            n("905321234567").to_e164(tr).as_deref(),
            Some("+905321234567")
        );
        assert_eq!(
            n("+1 555 123 4567").to_e164(tr).as_deref(),
            Some("+15551234567")
        );
        assert_eq!(
            n("555 123 4567").to_e164(Some("+1")).as_deref(),
            Some("+15551234567")
        );
        assert_eq!(
            n("15551234567").to_e164(Some("1")).as_deref(),
            Some("+15551234567")
        );
    }

    #[test]
    fn e164_needs_a_country_and_a_full_number() {
        assert_eq!(n("0532 123 45 67").to_e164(None), None);
        assert_eq!(n("0532 123 45 67").to_e164(Some("0")), None);
        assert_eq!(n("112").to_e164(Some("90")), None);
        assert_eq!(n("444 0 123").to_e164(Some("90")), None);
        assert_eq!(n("+90 112").to_e164(Some("90")), None);
    }

    #[test]
    fn withheld_numbers() {
        for raw in [
            "",
            "  ",
            "-1",
            "-2",
            "-3",
            "Unknown",
            "PRIVATE",
            "Anonymous",
            "payphone",
        ] {
            assert!(is_withheld(raw), "{raw:?}");
        }
        for raw in ["112", "+905321234567", "0", "-12"] {
            assert!(!is_withheld(raw), "{raw:?}");
        }
    }

    #[test]
    fn extensions_are_cut() {
        assert_eq!(
            strip_extension("+90 212 555 00 00 ext. 12"),
            "+90 212 555 00 00"
        );
        assert_eq!(strip_extension("+902125550000x12"), "+902125550000");
        assert_eq!(strip_extension("02125550000;ext=12"), "02125550000");
        assert_eq!(strip_extension("02125550000,,12"), "02125550000");
        assert_eq!(strip_extension("02125550000;12"), "02125550000");
        assert_eq!(strip_extension("+902125550000#12"), "+902125550000");
        assert_eq!(strip_extension("*#06#"), "*#06#");
        assert_eq!(strip_extension("0532 123 45 67"), "0532 123 45 67");
    }

    #[test]
    fn whatsapp_user_requires_country_code() {
        assert_eq!(n("+90 532 123 45 67").whatsapp_user(), Some("905321234567"));
        assert_eq!(n("0532 123 45 67").whatsapp_user(), None);
    }
}
