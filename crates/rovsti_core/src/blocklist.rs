//! The blocklist as the views need it: is this number blocked, and may it be
//! blocked at all (AD-27).
//!
//! The system `BlockedNumberContract` is the truth; Kotlin pulls its rows and
//! this set only answers questions about them. Hiding blocked calls in Recents
//! is by `kind == blocked` on the call-log row, never by this set.

use crate::dial::is_emergency;
use crate::phone_number::PhoneNumber;
use crate::wire::{BlockReason, BlockedNumber, CallLogEntry, EmergencyList};

/// A blocked entry: a number we can match tolerantly, or a sender id that
/// only matches itself.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Entry {
    Number(PhoneNumber),
    Text(String),
}

/// Normalised blocklist rows.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BlockSet {
    entries: Vec<Entry>,
}

fn entry_of(raw: &str) -> Option<Entry> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(
        PhoneNumber::parse(trimmed)
            .map(Entry::Number)
            .unwrap_or_else(|_| Entry::Text(trimmed.to_lowercase())),
    )
}

impl BlockSet {
    /// Builds the set from the pulled rows. The provider's `normalized` form
    /// wins over `number_raw` when it parses.
    pub fn from_rows(rows: &[BlockedNumber]) -> Self {
        let entries = rows
            .iter()
            .filter_map(|row| {
                entry_of(&row.normalized)
                    .filter(|e| matches!(e, Entry::Number(_)))
                    .or_else(|| entry_of(&row.number_raw))
            })
            .collect();
        Self { entries }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Whether `raw` (as shown in Recents or a call) is on the list. Format
    /// insensitive, and tolerant of a missing country code the way the v0.1
    /// matcher is (`PhoneNumber::matches`). Drives the Block/Unblock label and
    /// the blocked chip.
    pub fn is_blocked(&self, raw: &str) -> bool {
        let Some(probe) = entry_of(raw) else {
            return false;
        };
        self.entries.iter().any(|entry| match (entry, &probe) {
            (Entry::Number(a), Entry::Number(b)) => a.matches(b),
            (Entry::Text(a), Entry::Text(b)) => a == b,
            _ => false,
        })
    }
}

/// Why a number cannot be added to the blocklist.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum BlockRefusal {
    /// Emergency numbers can never be blocked.
    #[error("emergency numbers can't be blocked")]
    Emergency,
    /// A hidden or empty number has nothing to block.
    #[error("no number to block")]
    NoNumber,
    /// A dial code (`*#06#`, USSD) is not a caller.
    #[error("not a phone number")]
    NotANumber,
}

/// The guard every Block action goes through, before Kotlin is asked to write.
pub fn can_block(raw: &str, emergency: &EmergencyList) -> Result<(), BlockRefusal> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(BlockRefusal::NoNumber);
    }
    if is_emergency(trimmed, emergency) {
        return Err(BlockRefusal::Emergency);
    }
    if trimmed.contains(['*', '#']) {
        return Err(BlockRefusal::NotANumber);
    }
    Ok(())
}

/// Why typed text cannot be added to the blocklist (story 17.5).
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum BlockInputError {
    /// Letters, dial codes, nothing: "Not a valid number".
    #[error("Not a valid number")]
    Invalid,
    /// Emergency numbers can never be blocked.
    #[error("Emergency numbers can't be blocked")]
    Emergency,
}

/// Turns what the owner typed into the string that is blocked: the canonical
/// digits (with a leading `+` for an international number), after the FR-9
/// dial-string rules. Separators are accepted, letters are not.
pub fn parse_input(typed: &str, emergency: &EmergencyList) -> Result<String, BlockInputError> {
    let number = PhoneNumber::parse(typed.trim()).map_err(|_| BlockInputError::Invalid)?;
    match can_block(number.as_str(), emergency) {
        Ok(()) => Ok(number.as_str().to_owned()),
        Err(BlockRefusal::Emergency) => Err(BlockInputError::Emergency),
        Err(_) => Err(BlockInputError::Invalid),
    }
}

/// What one row of the Blocked numbers page says (stories 17.5, 17.6).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BlockedLine {
    pub title: String,
    pub subtitle: String,
    /// What TalkBack reads.
    pub aria: String,
}

/// The number as a person reads it: grouped when it parses, as stored when
/// it does not (a sender id).
fn shown_number(raw: &str) -> String {
    match PhoneNumber::parse(raw) {
        Ok(n) => crate::call::format_number(&n),
        Err(_) => raw.trim().to_owned(),
    }
}

/// "Blocked by you" / "Blocked by the system"; nothing when the reason is
/// not known.
pub fn reason_text(reason: Option<BlockReason>) -> Option<&'static str> {
    match reason? {
        BlockReason::User => Some("Blocked by you"),
        BlockReason::System => Some("Blocked by the system"),
        BlockReason::Unknown => None,
    }
}

/// A Numbers row: the contact's name if the number matches, else the number;
/// the subtitle is the number, or "Blocked number" when the title already is.
pub fn numbers_line(row: &BlockedNumber, contact_name: Option<&str>) -> BlockedLine {
    let raw = if row.number_raw.trim().is_empty() {
        row.normalized.as_str()
    } else {
        row.number_raw.as_str()
    };
    let number = shown_number(raw);
    let (title, subtitle) = match contact_name.filter(|n| !n.trim().is_empty()) {
        Some(name) => (name.to_owned(), number),
        None => (number, "Blocked number".to_owned()),
    };
    let aria = format!("{title}, {subtitle}. Unblock");
    BlockedLine {
        title,
        subtitle,
        aria,
    }
}

/// A Blocked calls row. `time` is the Recents time label of the call.
pub fn call_line(entry: &CallLogEntry, contact_name: Option<&str>, time: &str) -> BlockedLine {
    let number = if entry.number_raw.trim().is_empty() {
        "Unknown number".to_owned()
    } else {
        shown_number(&entry.number_raw)
    };
    let title = contact_name
        .filter(|n| !n.trim().is_empty())
        .map_or(number, str::to_owned);
    let subtitle = reason_text(entry.block_reason)
        .unwrap_or_default()
        .to_owned();
    let aria = if subtitle.is_empty() {
        format!("{title}, blocked call, {time}")
    } else {
        format!("{title}, {subtitle}, {time}")
    };
    BlockedLine {
        title,
        subtitle,
        aria,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: i64, raw: &str, normalized: &str) -> BlockedNumber {
        BlockedNumber {
            id,
            number_raw: raw.into(),
            normalized: normalized.into(),
        }
    }

    fn set() -> BlockSet {
        BlockSet::from_rows(&[
            row(1, "+90 555 111 22 33", "+905551112233"),
            row(2, "0850 000 00 00", ""),
            row(3, "ACME-BANK", ""),
            row(4, "  ", ""),
        ])
    }

    #[test]
    fn rows_become_entries_and_blank_rows_are_dropped() {
        assert_eq!(set().len(), 3);
        assert!(BlockSet::default().is_empty());
        assert!(!BlockSet::default().is_blocked("0555 111 22 33"));
    }

    #[test]
    fn matching_ignores_formatting() {
        let s = set();
        for typed in [
            "+905551112233",
            "+90 555 111 22 33",
            "+90 (555) 111-22-33",
            "+90 (0) 555 111 22 33",
            "00 90 555 111 22 33",
        ] {
            assert!(s.is_blocked(typed), "{typed}");
        }
        assert!(s.is_blocked("0850-000-00-00"));
    }

    #[test]
    fn matching_tolerates_a_missing_country_code() {
        let s = set();
        assert!(s.is_blocked("05551112233"));
        assert!(s.is_blocked("5551112233"));
        assert!(!s.is_blocked("05551112234"));
        assert!(!s.is_blocked("+905551112234"));
        // Two different countries with the same tail are different lines.
        assert!(!s.is_blocked("+495551112233"));
    }

    #[test]
    fn short_codes_match_exactly() {
        let s = BlockSet::from_rows(&[row(1, "4440000", "")]);
        assert!(s.is_blocked("444 0000"));
        assert!(!s.is_blocked("04440000"));
    }

    #[test]
    fn sender_ids_match_themselves_only() {
        let s = set();
        assert!(s.is_blocked("acme-bank"));
        assert!(s.is_blocked(" ACME-BANK "));
        assert!(!s.is_blocked("ACME"));
        assert!(!s.is_blocked(""));
        assert!(!s.is_blocked("   "));
    }

    #[test]
    fn the_normalised_form_wins_when_it_parses() {
        let s = BlockSet::from_rows(&[row(1, "garbled", "05551112233")]);
        assert!(s.is_blocked("+90 555 111 22 33"));
        assert!(!s.is_blocked("garbled"));
    }

    // ---- guard ---------------------------------------------------------

    #[test]
    fn emergency_numbers_can_never_be_blocked() {
        let tr = EmergencyList {
            numbers: vec!["155".into()],
        };
        for raw in ["112", "911", "1 1 2", "155", " 155 "] {
            assert_eq!(can_block(raw, &tr), Err(BlockRefusal::Emergency), "{raw}");
        }
        // Even with no list pulled.
        assert_eq!(
            can_block("112", &EmergencyList::default()),
            Err(BlockRefusal::Emergency)
        );
    }

    #[test]
    fn ordinary_numbers_and_codes() {
        let tr = EmergencyList::default();
        assert_eq!(can_block("+90 555 111 22 33", &tr), Ok(()));
        assert_eq!(can_block("ACME-BANK", &tr), Ok(()));
        assert_eq!(can_block("", &tr), Err(BlockRefusal::NoNumber));
        assert_eq!(can_block("  ", &tr), Err(BlockRefusal::NoNumber));
        assert_eq!(can_block("*#06#", &tr), Err(BlockRefusal::NotANumber));
    }

    #[test]
    fn typed_numbers_are_cleaned_and_checked() {
        let list = EmergencyList::default();
        assert_eq!(
            parse_input(" 0532 123-45-67 ", &list).as_deref(),
            Ok("05321234567")
        );
        assert_eq!(
            parse_input("+90 (532) 123 45 67", &list).as_deref(),
            Ok("+905321234567")
        );
        assert_eq!(
            parse_input("00 49 151 2345678", &list).as_deref(),
            Ok("+491512345678")
        );
    }

    #[test]
    fn letters_codes_and_nothing_are_not_a_number() {
        let list = EmergencyList::default();
        for bad in ["", "   ", "abc", "05a5", "*#06#", "+", "1234567890123456"] {
            assert_eq!(
                parse_input(bad, &list),
                Err(BlockInputError::Invalid),
                "{bad:?}"
            );
        }
    }

    #[test]
    fn an_emergency_number_is_refused_by_name() {
        let list = EmergencyList::default();
        assert_eq!(parse_input("112", &list), Err(BlockInputError::Emergency));
        assert_eq!(parse_input("911", &list), Err(BlockInputError::Emergency));
    }

    #[test]
    fn a_numbers_row_prefers_the_contact_name() {
        let r = BlockedNumber {
            id: 1,
            number_raw: "0532 123 45 67".into(),
            normalized: "05321234567".into(),
        };
        let named = numbers_line(&r, Some("Ayse Yilmaz"));
        assert_eq!(named.title, "Ayse Yilmaz");
        assert!(named.subtitle.contains("532"));
        let plain = numbers_line(&r, None);
        assert_eq!(plain.subtitle, "Blocked number");
        assert!(plain.title.contains("532"));
        assert!(plain.aria.ends_with("Unblock"));
    }

    #[test]
    fn a_sender_id_is_shown_as_stored() {
        let r = BlockedNumber {
            id: 2,
            number_raw: "  Bank  ".into(),
            normalized: String::new(),
        };
        assert_eq!(numbers_line(&r, None).title, "Bank");
    }

    #[test]
    fn blocked_call_reasons_read_plainly() {
        assert_eq!(reason_text(Some(BlockReason::User)), Some("Blocked by you"));
        assert_eq!(
            reason_text(Some(BlockReason::System)),
            Some("Blocked by the system")
        );
        assert_eq!(reason_text(Some(BlockReason::Unknown)), None);
        assert_eq!(reason_text(None), None);
        let entry = CallLogEntry {
            number_raw: String::new(),
            block_reason: Some(BlockReason::System),
            ..CallLogEntry::default()
        };
        let line = call_line(&entry, None, "Monday");
        assert_eq!(line.title, "Unknown number");
        assert_eq!(line.aria, "Unknown number, Blocked by the system, Monday");
        let unknown = call_line(&CallLogEntry::default(), None, "9:41");
        assert_eq!(unknown.subtitle, "");
    }
}
