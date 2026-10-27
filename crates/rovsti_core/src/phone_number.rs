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
        let trimmed = input.trim();
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

    fn digits(&self) -> &str {
        self.canonical.trim_start_matches('+')
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
    fn whatsapp_user_requires_country_code() {
        assert_eq!(n("+90 532 123 45 67").whatsapp_user(), Some("905321234567"));
        assert_eq!(n("0532 123 45 67").whatsapp_user(), None);
    }
}
