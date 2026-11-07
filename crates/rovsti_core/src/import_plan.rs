//! What a vCard import will add: the cards that are not already in the
//! address book.
//!
//! A card counts as already there when a contact with the same name (case and
//! accents ignored) shares a phone number with it. Numbers are compared by
//! their last ten digits, so `0555 111 22 33` and `+90 555 111 22 33` match.
//! A card without any number is compared by name and email instead (email
//! case ignored); with no email either, by name alone. A card that has a
//! number never matches on its name alone, and neither does a contact that
//! has one. Cards repeated inside the file itself are added once.

use std::collections::HashSet;

use crate::{contact_edit::ContactDraft, search::fold, vcard::CardPhoto, wire::WireContact};

/// How many trailing digits identify a number.
const KEY_DIGITS: usize = 10;
/// Most photos one import writes; later cards are added without one.
pub const MAX_IMPORT_PHOTOS: usize = 200;

/// The outcome of [`plan`].
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Plan {
    /// Cards to add.
    pub add: Vec<ContactDraft>,
    /// Cards left out because the contact is already there (or repeated).
    pub duplicates: usize,
}

pub fn number_key(raw: &str) -> Option<String> {
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

/// Keys for a contact without a usable number: one `(name, "@email")` per
/// email, or a single `(name, "")` when it has none.
fn fallback_keys<'a>(
    name: &str,
    emails: impl IntoIterator<Item = &'a str>,
) -> Vec<(String, String)> {
    let name = fold(name.trim());
    let mut keys: Vec<(String, String)> = emails
        .into_iter()
        .map(str::trim)
        .filter(|e| !e.is_empty())
        .map(|e| (name.clone(), format!("@{}", e.to_lowercase())))
        .collect();
    if keys.is_empty() {
        keys.push((name, String::new()));
    }
    keys
}

/// Splits `incoming` into the cards to add and the duplicates.
pub fn plan<'a>(
    existing: impl IntoIterator<Item = &'a WireContact>,
    incoming: Vec<ContactDraft>,
) -> Plan {
    plan_with_photos(existing, incoming, Vec::new()).0
}

/// Like [`plan`], and the photos of the kept cards in the same order (a
/// shorter `photos` list is padded with `None`).
pub fn plan_with_photos<'a>(
    existing: impl IntoIterator<Item = &'a WireContact>,
    cards: Vec<ContactDraft>,
    photos: Vec<Option<CardPhoto>>,
) -> (Plan, Vec<Option<CardPhoto>>) {
    let mut photos = photos.into_iter();
    let mut kept = Vec::new();
    let mut seen: HashSet<(String, String)> = existing
        .into_iter()
        .flat_map(|c| {
            let k = keys(&c.display_name, c.numbers.iter().map(|n| n.raw.as_str()));
            if k.is_empty() {
                fallback_keys(&c.display_name, c.email.as_deref())
            } else {
                k
            }
        })
        .collect();
    let mut plan = Plan::default();
    for card in cards {
        let photo = photos.next().flatten();
        let name = card.display_name();
        let mut card_keys = keys(&name, card.numbers.iter().map(|n| n.value.as_str()));
        if card_keys.is_empty() {
            card_keys = fallback_keys(&name, card.emails.iter().map(|e| e.value.as_str()));
        }
        if card_keys.iter().any(|k| seen.contains(k)) {
            plan.duplicates += 1;
        } else {
            seen.extend(card_keys);
            plan.add.push(card);
            kept.push(photo);
        }
    }
    (plan, kept)
}

/// `(contact id, photo)` pairs to write after an import: ids and photos are
/// aligned with the added cards; id 0 (a failed card) and cards without a
/// photo are skipped, and at most [`MAX_IMPORT_PHOTOS`] are returned.
pub fn photo_targets<'a>(
    ids: &[i64],
    photos: &'a [Option<CardPhoto>],
) -> Vec<(i64, &'a CardPhoto)> {
    ids.iter()
        .zip(photos)
        .filter(|(id, _)| **id > 0)
        .filter_map(|(id, photo)| photo.as_ref().map(|p| (*id, p)))
        .take(MAX_IMPORT_PHOTOS)
        .collect()
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

    fn bare(id: i64, name: &str, email: Option<&str>) -> WireContact {
        WireContact {
            id,
            display_name: name.to_owned(),
            email: email.map(str::to_owned),
            ..WireContact::default()
        }
    }

    fn photo(n: u8) -> Option<CardPhoto> {
        Some(CardPhoto {
            format: crate::vcard::PhotoFormat::Jpeg,
            bytes: vec![n],
        })
    }

    #[test]
    fn photos_follow_the_kept_cards() {
        let cards = vec![
            card("Ali", "Veli", "0555 111 22 33"),
            card("Ali", "Veli", "0555 111 22 33"),
            card("Can", "Er", "0555 222 33 44"),
            card("Zed", "Yo", "0555 333 44 55"),
        ];
        let (plan, kept) = plan_with_photos(&[], cards, vec![photo(1), photo(2), None]);
        assert_eq!(plan.add.len(), 3);
        assert_eq!(plan.duplicates, 1);
        // The duplicate's photo is dropped; Zed's photo was padded with None.
        assert_eq!(kept, vec![photo(1), None, None]);
    }

    #[test]
    fn photo_targets_skip_failures_and_cap() {
        let photos = vec![photo(1), None, photo(3), photo(4)];
        let targets = photo_targets(&[10, 11, 0, 13], &photos);
        assert_eq!(
            targets.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
            vec![10, 13]
        );
        let many: Vec<Option<CardPhoto>> = (0..300).map(|_| photo(1)).collect();
        let ids: Vec<i64> = (1..=300).collect();
        assert_eq!(photo_targets(&ids, &many).len(), MAX_IMPORT_PHOTOS);
    }

    fn email_card(given: &str, email: &str) -> ContactDraft {
        ContactDraft {
            given: given.to_owned(),
            emails: if email.is_empty() {
                Vec::new()
            } else {
                vec![DraftField::new(email, FieldKind::Home)]
            },
            ..ContactDraft::default()
        }
    }

    #[test]
    fn an_email_only_card_matches_the_same_contact_in_any_case() {
        let book = [bare(1, "Ayşe", Some("Ayse@Example.com"))];
        let p = plan(&book, vec![email_card("AYSE", "ayse@example.COM")]);
        assert!(p.add.is_empty());
        assert_eq!(p.duplicates, 1);
    }

    #[test]
    fn the_same_name_with_another_email_is_added() {
        let book = [bare(1, "Ayşe", Some("ayse@example.com"))];
        let p = plan(&book, vec![email_card("Ayşe", "other@example.com")]);
        assert_eq!(p.add.len(), 1);
    }

    #[test]
    fn a_name_only_card_matches_a_name_only_contact() {
        let book = [bare(1, "Ayşe", None)];
        let p = plan(&book, vec![email_card("ayse", "")]);
        assert_eq!(p.duplicates, 1);
    }

    #[test]
    fn a_name_only_card_does_not_match_a_contact_with_a_number() {
        let book = [existing(1, "Ayşe", "0555 111 22 33")];
        let p = plan(&book, vec![email_card("Ayşe", "")]);
        assert_eq!(p.add.len(), 1);
    }

    #[test]
    fn a_card_with_a_number_does_not_match_a_name_only_contact() {
        let book = [bare(1, "Ayşe", None)];
        let p = plan(&book, vec![card("Ayşe", "", "0555 111 22 33")]);
        assert_eq!(p.add.len(), 1);
    }

    #[test]
    fn identical_email_only_cards_are_added_once() {
        let two = vec![
            email_card("Can", "can@example.com"),
            email_card("Can", "CAN@example.com"),
        ];
        let p = plan(&[], two);
        assert_eq!(p.add.len(), 1);
        assert_eq!(p.duplicates, 1);
    }

    #[test]
    fn number_less_contacts_survive_a_vcard_round_trip_without_duplicates() {
        let mut cards = vec![
            email_card("Ayşe", "ayse@example.com"),
            email_card("Can", ""),
        ];
        cards.iter_mut().for_each(ContactDraft::clean);
        let book = [
            bare(1, "Ayşe", Some("ayse@example.com")),
            bare(2, "Can", None),
        ];
        let parsed = crate::vcard::parse(&crate::vcard::write(&cards));
        assert_eq!(parsed.contacts.len(), 2);
        let p = plan(&book, parsed.contacts);
        assert!(p.add.is_empty());
        assert_eq!(p.duplicates, 2);
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
