//! Speed dial and voicemail resolution for a long-press on a dialpad digit.
//!
//! Pure logic: the UI layer asks what a held digit means and acts on the
//! answer. Digit `1` is voicemail, `0` is reserved for `+` (see
//! [`crate::dial::long_press_zero`]), and `2..=9` are owner-assigned.

use std::collections::BTreeMap;

use crate::avatar;
use crate::call::format_number;
use crate::phone_number::PhoneNumber;
use crate::settings::{MAX_FIELD_CHARS, SPEED_DIAL_DIGITS, SpeedDialEntry};

/// What holding a dialpad digit should do.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
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

/// The lowest speed-dial digit whose entry is one of `numbers` (parsed match
/// or equal trimmed text).
pub fn digit_for(map: &BTreeMap<u8, SpeedDialEntry>, numbers: &[&str]) -> Option<u8> {
    let wanted: Vec<(&str, Option<PhoneNumber>)> = numbers
        .iter()
        .map(|n| n.trim())
        .filter(|n| !n.is_empty())
        .map(|n| (n, PhoneNumber::parse(n).ok()))
        .collect();
    map.iter().find_map(|(digit, entry)| {
        let raw = entry.number_raw.trim();
        if raw.is_empty() {
            return None;
        }
        let parsed = PhoneNumber::parse(raw).ok();
        wanted
            .iter()
            .any(|(text, number)| match (&parsed, number) {
                (Some(a), Some(b)) if a.matches(b) => true,
                _ => *text == raw,
            })
            .then_some(*digit)
    })
}

/// What a dialpad key shows for its speed dial: the shown name and avatar hue.
/// Index `d - 1` is digit `d`; digit 1 (voicemail) and free digits are `None`.
pub fn badges(map: &BTreeMap<u8, SpeedDialEntry>) -> [Option<(String, Option<usize>)>; 9] {
    let mut out: [Option<(String, Option<usize>)>; 9] = Default::default();
    for digit in SPEED_DIAL_DIGITS {
        let Some(entry) = map.get(&digit) else {
            continue;
        };
        let number = entry.number_raw.trim();
        if number.is_empty() {
            continue;
        }
        let name = entry.name.trim();
        let shown = if name.is_empty() { number } else { name };
        let digits: String = number.chars().filter(char::is_ascii_digit).collect();
        let hue = avatar::avatar_hue(avatar::AvatarKey::Number(&digits));
        out[usize::from(digit) - 1] = Some((shown.to_owned(), hue));
    }
    out
}

/// Pill text after assigning `digit`.
pub fn assigned_text(digit: u8, name: &str, previous: Option<&SpeedDialEntry>) -> String {
    match previous {
        Some(prev) => format!("Speed dial {digit}: {name} (was {})", prev.name),
        None => format!("Speed dial {digit}: {name}"),
    }
}

/// The list as shareable text, one line per assigned digit in order. Empty
/// when nothing is assigned.
pub fn list_text(map: &BTreeMap<u8, SpeedDialEntry>) -> String {
    let lines: Vec<String> = map
        .iter()
        .filter(|(_, e)| !e.number_raw.trim().is_empty())
        .map(|(digit, e)| {
            let raw = e.number_raw.trim();
            let number =
                PhoneNumber::parse(raw).map_or_else(|_| raw.to_owned(), |n| format_number(&n));
            match e.name.trim() {
                "" => format!("{digit}  {number}"),
                name => format!("{digit}  {name}  {number}"),
            }
        })
        .collect();
    if lines.is_empty() {
        return String::new();
    }
    format!("Speed dial\n{}", lines.join("\n"))
}

/// Removes every assignment (digit 1 is voicemail and never stored) and
/// returns the old map for Undo.
pub fn clear_all(map: &mut BTreeMap<u8, SpeedDialEntry>) -> BTreeMap<u8, SpeedDialEntry> {
    std::mem::take(map)
}

/// The confirm sheet of Clear all: title and body for `count` assigned keys.
pub fn clear_all_confirm(count: usize) -> (&'static str, String) {
    let body = match count {
        1 => "1 number will be removed from keys 2-9.".to_owned(),
        n => format!("{n} numbers will be removed from keys 2-9."),
    };
    ("Clear speed dial?", body)
}

/// The pill after Clear all.
pub const CLEARED_TEXT: &str = "Speed dial cleared";

#[cfg(test)]
mod tests {
    fn dial_entry(name: &str, number: &str) -> SpeedDialEntry {
        SpeedDialEntry {
            lookup_key: String::new(),
            number_raw: number.into(),
            name: name.into(),
        }
    }

    #[test]
    fn list_text_orders_and_groups() {
        let mut map = BTreeMap::new();
        map.insert(5, dial_entry("", "weird"));
        map.insert(2, dial_entry("Anna Smith", "+905321234567"));
        map.insert(3, dial_entry("Gone", "  "));
        assert_eq!(
            list_text(&map),
            "Speed dial\n2  Anna Smith  +90 532 123 45 67\n5  weird"
        );
    }

    #[test]
    fn list_text_empty() {
        assert_eq!(list_text(&BTreeMap::new()), "");
    }

    #[test]
    fn clear_all_confirm_copy() {
        assert_eq!(
            clear_all_confirm(1),
            (
                "Clear speed dial?",
                "1 number will be removed from keys 2-9.".to_owned()
            )
        );
        assert_eq!(
            clear_all_confirm(3).1,
            "3 numbers will be removed from keys 2-9."
        );
        assert_eq!(CLEARED_TEXT, "Speed dial cleared");
    }

    #[test]
    fn clear_all_returns_old_map() {
        let mut map = BTreeMap::new();
        map.insert(2, dial_entry("A", "123456"));
        let old = clear_all(&mut map);
        assert!(map.is_empty());
        assert_eq!(old.len(), 1);
        assert_eq!(old[&2].name, "A");
        assert!(clear_all(&mut map).is_empty());
    }

    use super::*;

    fn entry(number: &str) -> SpeedDialEntry {
        SpeedDialEntry {
            lookup_key: "k".into(),
            number_raw: number.into(),
            name: "Ana".into(),
        }
    }

    #[test]
    fn badges_empty_map_has_none() {
        assert!(badges(&BTreeMap::new()).iter().all(Option::is_none));
    }

    #[test]
    fn badges_show_name_and_hue_on_assigned_digit() {
        let mut map = BTreeMap::new();
        let mut e = entry("+90 532 111 22 33");
        e.name = " Ayşe ".into();
        map.insert(5, e);
        let b = badges(&map);
        let expected = avatar::avatar_hue(avatar::AvatarKey::Number("905321112233"));
        assert_eq!(b[4], Some(("Ayşe".to_owned(), expected)));
        assert_eq!(b.iter().filter(|x| x.is_some()).count(), 1);
    }

    #[test]
    fn badges_ignore_digit_one_and_blank_numbers() {
        let mut map = BTreeMap::new();
        map.insert(1, entry("111"));
        map.insert(3, entry("   "));
        assert!(badges(&map).iter().all(Option::is_none));
    }

    #[test]
    fn badges_nameless_shows_number() {
        let mut map = BTreeMap::new();
        let mut e = entry(" 0532 ");
        e.name = " ".into();
        map.insert(2, e);
        assert_eq!(badges(&map)[1].as_ref().map(|b| b.0.as_str()), Some("0532"));
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

    #[test]
    fn digit_for_finds_the_lowest_match() {
        let mut m = BTreeMap::new();
        assign(&mut m, 7, entry("+905551112233")).expect("assign");
        assign(&mut m, 4, entry("0555 111 22 33")).expect("assign");
        assign(&mut m, 2, entry("0555 999 88 77")).expect("assign");
        assert_eq!(digit_for(&m, &["05551112233"]), Some(4));
        assert_eq!(digit_for(&m, &["", "0555 999 88 77"]), Some(2));
        assert_eq!(digit_for(&m, &["0500 000 00 00"]), None);
        assert_eq!(digit_for(&m, &[" "]), None);
    }

    #[test]
    fn assigned_text_mentions_the_previous_entry() {
        assert_eq!(assigned_text(5, "Ana", None), "Speed dial 5: Ana");
        let prev = entry("1");
        let prev = SpeedDialEntry {
            name: "Bob".into(),
            ..prev
        };
        assert_eq!(
            assigned_text(5, "Ana", Some(&prev)),
            "Speed dial 5: Ana (was Bob)"
        );
    }
}
