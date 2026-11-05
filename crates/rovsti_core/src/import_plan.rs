//! What a vCard import will add: the cards that are not already in the
//! address book.
//!
//! A card counts as already there when a contact with the same name (case and
//! accents ignored) shares a phone number with it. Numbers are compared by
//! their last ten digits, so `0555 111 22 33` and `+90 555 111 22 33` match.
//! Cards repeated inside the file itself are added once.

use std::collections::HashSet;

use crate::{contact_edit::ContactDraft, search::fold, wire::WireContact};

/// How many trailing digits identify a number.
const KEY_DIGITS: usize = 10;

/// The outcome of [`plan`].
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Plan {
    /// Cards to add.
    pub add: Vec<ContactDraft>,
    /// Cards left out because the contact is already there (or repeated).
    pub duplicates: usize,
}

fn number_key(raw: &str) -> Option<String> {
    let digits: Vec<char> = raw.chars().filter(char::is_ascii_digit).collect();
    if digits.is_empty() {
        return None;
    }
    let from = digits.len().saturating_sub(KEY_DIGITS);
    Some(digits[from..].iter().collect())
}

/// `(folded name, number key)` pairs of one contact.
fn keys<'a>(name: &str, numbers: impl IntoIterator<Item = &'a str>) -> Vec<(String, String)> {
    let name = fold(name.trim());
    numbers
        .into_iter()
        .filter_map(number_key)
        .map(|key| (name.clone(), key))
        .collect()
}

/// Splits `incoming` into the cards to add and the duplicates.
pub fn plan<'a>(
    existing: impl IntoIterator<Item = &'a WireContact>,
    incoming: Vec<ContactDraft>,
) -> Plan {
    let mut seen: HashSet<(String, String)> = existing
        .into_iter()
        .flat_map(|c| keys(&c.display_name, c.numbers.iter().map(|n| n.raw.as_str())))
        .collect();
    let mut plan = Plan::default();
    for card in incoming {
        let card_keys = keys(
            &card.display_name(),
            card.numbers.iter().map(|n| n.value.as_str()),
        );
        if card_keys.iter().any(|k| seen.contains(k)) {
            plan.duplicates += 1;
        } else {
            seen.extend(card_keys);
            plan.add.push(card);
        }
    }
    plan
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        contact_edit::{DraftField, FieldKind},
        wire::WireNumber,
    };

    fn existing(id: i64, name: &str, number: &str) -> WireContact {
        WireContact {
            id,
            display_name: name.to_owned(),
            numbers: vec![WireNumber {
                raw: number.to_owned(),
                label: None,
                default: false,
            }],
            ..WireContact::default()
        }
    }

    fn card(given: &str, family: &str, number: &str) -> ContactDraft {
        ContactDraft {
            given: given.to_owned(),
            family: family.to_owned(),
            numbers: vec![DraftField::new(number, FieldKind::Mobile)],
            ..ContactDraft::default()
        }
    }

    #[test]
    fn new_cards_are_added() {
        let book = [existing(1, "Ayşe Yılmaz", "0555 111 22 33")];
        let plan = plan(&book, vec![card("Burak", "Demir", "0532 123 45 67")]);
        assert_eq!(plan.add.len(), 1);
        assert_eq!(plan.duplicates, 0);
    }

    #[test]
    fn the_same_name_and_number_in_another_format_is_a_duplicate() {
        let book = [existing(1, "Ayşe Yılmaz", "0555 111 22 33")];
        let plan = plan(&book, vec![card("AYSE", "yilmaz", "+90 555 111 22 33")]);
        assert!(plan.add.is_empty());
        assert_eq!(plan.duplicates, 1);
    }

    #[test]
    fn the_same_number_under_another_name_is_kept() {
        let book = [existing(1, "Ayşe Yılmaz", "0555 111 22 33")];
        let plan = plan(&book, vec![card("Anne", "", "0555 111 22 33")]);
        assert_eq!(
            plan.add.len(),
            1,
            "a shared landline is not the same person"
        );
    }

    #[test]
    fn the_same_name_with_another_number_is_kept() {
        let book = [existing(1, "Ayşe Yılmaz", "0555 111 22 33")];
        let plan = plan(&book, vec![card("Ayşe", "Yılmaz", "0532 000 00 00")]);
        assert_eq!(plan.add.len(), 1);
    }

    #[test]
    fn a_card_repeated_in_the_file_is_added_once() {
        let two = vec![
            card("Can", "Aydın", "0506 333 44 55"),
            card("Can", "Aydın", "05063334455"),
        ];
        let plan = plan(&[], two);
        assert_eq!(plan.add.len(), 1);
        assert_eq!(plan.duplicates, 1);
    }

    #[test]
    fn a_card_with_a_second_matching_number_is_a_duplicate() {
        let book = [existing(1, "Elif Kaya", "0533 765 43 21")];
        let mut incoming = card("Elif", "Kaya", "0212 555 00 11");
        incoming
            .numbers
            .push(DraftField::new("0533 765 43 21", FieldKind::Mobile));
        assert_eq!(plan(&book, vec![incoming]).duplicates, 1);
    }

    #[test]
    fn short_numbers_compare_by_all_their_digits() {
        assert_eq!(number_key("112").as_deref(), Some("112"));
        assert_eq!(number_key("abc"), None);
        assert_eq!(
            number_key("+90 555 111 22 33").as_deref(),
            Some("5551112233")
        );
    }
}
