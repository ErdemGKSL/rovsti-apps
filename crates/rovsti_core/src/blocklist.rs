//! The blocklist as the views need it: is this number blocked, and may it be
//! blocked at all (AD-27).
//!
//! The system `BlockedNumberContract` is the truth; Kotlin pulls its rows and
//! this set only answers questions about them. Hiding blocked calls in Recents
//! is by `kind == blocked` on the call-log row, never by this set.

use crate::dial::is_emergency;
use crate::phone_number::PhoneNumber;
use crate::wire::{BlockedNumber, EmergencyList};

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
}
