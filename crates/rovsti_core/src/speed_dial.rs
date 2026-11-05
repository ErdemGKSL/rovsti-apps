//! Speed dial and voicemail resolution for a long-press on a dialpad digit.
//!
//! Pure logic: the UI layer asks what a held digit means and acts on the
//! answer. Digit `1` is voicemail, `0` is reserved for `+` (see
//! [`crate::dial::long_press_zero`]), and `2..=9` are owner-assigned.

use std::collections::BTreeMap;

use crate::settings::{MAX_FIELD_CHARS, SPEED_DIAL_DIGITS, SpeedDialEntry};

/// What holding a dialpad digit should do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HoldDigit {
    /// Call the voicemail number.
    Voicemail,
    /// Call the assigned speed-dial contact.
    Call { number_raw: String, name: String },
    /// A speed-dial digit with nothing assigned; offer to assign it.
    Unassigned(u8),
    /// Not a speed-dial gesture; the normal key handling applies.
    Ignore,
}

/// Why a speed-dial assignment was refused.
#[derive(thiserror::Error, Debug, PartialEq, Eq)]
pub enum SpeedDialError {
    #[error("digit {0} cannot hold a speed dial")]
    Digit(u8),
    #[error("speed dial number is empty")]
    EmptyNumber,
    #[error("speed dial number or name is too long")]
    TooLong,
}

/// Resolves a long-press on `digit`. Speed dial only applies while the number
/// field was empty before the press (`dialed_before`); otherwise the hold is a
/// plain key repeat and the result is [`HoldDigit::Ignore`].
pub fn on_hold(digit: char, dialed_before: &str, map: &BTreeMap<u8, SpeedDialEntry>) -> HoldDigit {
    if !dialed_before.is_empty() {
        return HoldDigit::Ignore;
    }
    let Some(d) = digit.to_digit(10).and_then(|d| u8::try_from(d).ok()) else {
        return HoldDigit::Ignore;
    };
    match d {
        1 => HoldDigit::Voicemail,
        d if SPEED_DIAL_DIGITS.contains(&d) => match map.get(&d) {
            Some(e) if !e.number_raw.is_empty() => HoldDigit::Call {
                number_raw: e.number_raw.clone(),
                name: e.name.clone(),
            },
            _ => HoldDigit::Unassigned(d),
        },
        _ => HoldDigit::Ignore,
    }
}

/// Assigns `entry` to `digit`, replacing any previous entry. The number and
/// name are trimmed before they are stored.
pub fn assign(
    map: &mut BTreeMap<u8, SpeedDialEntry>,
    digit: u8,
    mut entry: SpeedDialEntry,
) -> Result<(), SpeedDialError> {
    if !SPEED_DIAL_DIGITS.contains(&digit) {
        return Err(SpeedDialError::Digit(digit));
    }
    entry.number_raw = entry.number_raw.trim().to_owned();
    entry.name = entry.name.trim().to_owned();
    if entry.number_raw.is_empty() {
        return Err(SpeedDialError::EmptyNumber);
    }
    if [&entry.number_raw, &entry.name, &entry.lookup_key]
        .iter()
        .any(|s| s.chars().count() > MAX_FIELD_CHARS)
    {
        return Err(SpeedDialError::TooLong);
    }
    map.insert(digit, entry);
    Ok(())
}

/// Clears `digit`. Returns whether there was an entry to remove.
pub fn unassign(map: &mut BTreeMap<u8, SpeedDialEntry>, digit: u8) -> bool {
    map.remove(&digit).is_some()
}

/// Speed-dial digits that have no entry yet, ascending.
pub fn free_digits(map: &BTreeMap<u8, SpeedDialEntry>) -> impl Iterator<Item = u8> + '_ {
    SPEED_DIAL_DIGITS.filter(|d| !map.contains_key(d))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(number: &str) -> SpeedDialEntry {
        SpeedDialEntry {
            lookup_key: "k".into(),
            number_raw: number.into(),
            name: "Ana".into(),
        }
    }

    #[test]
    fn one_is_voicemail() {
        assert_eq!(on_hold('1', "", &BTreeMap::new()), HoldDigit::Voicemail);
    }

    #[test]
    fn zero_is_ignored() {
        assert_eq!(on_hold('0', "", &BTreeMap::new()), HoldDigit::Ignore);
    }

    #[test]
    fn assigned_digit_calls() {
        let mut m = BTreeMap::new();
        assign(&mut m, 5, entry("0555")).expect("assign");
        assert_eq!(
            on_hold('5', "", &m),
            HoldDigit::Call {
                number_raw: "0555".into(),
                name: "Ana".into()
            }
        );
    }

    #[test]
    fn unassigned_digit_reports_itself() {
        assert_eq!(on_hold('7', "", &BTreeMap::new()), HoldDigit::Unassigned(7));
    }

    #[test]
    fn non_empty_field_ignores_hold() {
        let mut m = BTreeMap::new();
        assign(&mut m, 5, entry("0555")).expect("assign");
        assert_eq!(on_hold('5', "1", &m), HoldDigit::Ignore);
        assert_eq!(on_hold('1', "12", &m), HoldDigit::Ignore);
    }

    #[test]
    fn non_digits_are_ignored() {
        assert_eq!(on_hold('*', "", &BTreeMap::new()), HoldDigit::Ignore);
        assert_eq!(on_hold('#', "", &BTreeMap::new()), HoldDigit::Ignore);
    }

    #[test]
    fn assign_refuses_bad_input() {
        let mut m = BTreeMap::new();
        assert_eq!(assign(&mut m, 1, entry("1")), Err(SpeedDialError::Digit(1)));
        assert_eq!(
            assign(&mut m, 10, entry("1")),
            Err(SpeedDialError::Digit(10))
        );
        assert_eq!(
            assign(&mut m, 2, entry("  ")),
            Err(SpeedDialError::EmptyNumber)
        );
        let long = "9".repeat(MAX_FIELD_CHARS + 1);
        assert_eq!(
            assign(&mut m, 2, entry(&long)),
            Err(SpeedDialError::TooLong)
        );
        assert!(m.is_empty());
    }

    #[test]
    fn assign_trims_and_replaces() {
        let mut m = BTreeMap::new();
        assign(&mut m, 2, entry(" 111 ")).expect("assign");
        assign(&mut m, 2, entry("222")).expect("assign");
        assert_eq!(m[&2].number_raw, "222");
    }

    #[test]
    fn double_unassign_returns_false() {
        let mut m = BTreeMap::new();
        assign(&mut m, 3, entry("1")).expect("assign");
        assert!(unassign(&mut m, 3));
        assert!(!unassign(&mut m, 3));
    }

    #[test]
    fn free_digits_skip_assigned() {
        let mut m = BTreeMap::new();
        assign(&mut m, 2, entry("1")).expect("assign");
        assign(&mut m, 9, entry("1")).expect("assign");
        assert_eq!(free_digits(&m).collect::<Vec<_>>(), vec![3, 4, 5, 6, 7, 8]);
    }
}
