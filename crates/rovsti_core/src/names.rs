//! Contact names: which form to show and how to order a list (AD-32).
//!
//! The AOSP provider makes the primary name given-name-first
//! (`DISPLAY_NAME_PRIMARY`, "Ayse Yilmaz") and the alternative
//! family-name-first ("Yilmaz, Ayse"). Views call [`display`]; they never
//! decide which one to show. [`NameOrder`] sorts once per setting change, not
//! per render.

use crate::search::fold;
use crate::settings::SortBy;
use crate::wire::{NameFormat, WireContact};
use std::borrow::Cow;

fn non_empty(s: &Option<String>) -> Option<&str> {
    s.as_deref().map(str::trim).filter(|s| !s.is_empty())
}

/// The name to show. Falls back to the primary name when the alternative is
/// missing (and the other way round), then to the contact's first number for a
/// contact with no name at all; empty when there is nothing to show.
pub fn display(contact: &WireContact, format: NameFormat) -> Cow<'_, str> {
    let primary = Some(contact.display_name.trim()).filter(|s| !s.is_empty());
    let alt = non_empty(&contact.display_name_alt);
    let chosen = match format {
        NameFormat::FirstLast => primary.or(alt),
        NameFormat::LastFirst => alt.or(primary),
    };
    match chosen {
        Some(name) => Cow::Borrowed(name),
        None => contact
            .numbers
            .first()
            .map_or(Cow::Borrowed(""), |n| Cow::Borrowed(n.raw.trim())),
    }
}

/// The folded key a list is ordered by: the provider's sort key for `by`
/// (`SORT_KEY_PRIMARY` or `_ALTERNATIVE`), else the matching display name,
/// folded Turkish-aware so "Özgür" sorts with "O" and "Işık" with "I".
pub fn sort_key(contact: &WireContact, by: SortBy) -> String {
    let (key, name) = match by {
        SortBy::FirstName => (&contact.sort_key, Some(contact.display_name.as_str())),
        SortBy::LastName => (&contact.sort_key_alt, contact.display_name_alt.as_deref()),
    };
    let chosen = non_empty(key)
        .or(name.map(str::trim).filter(|s| !s.is_empty()))
        .or(Some(contact.display_name.trim()).filter(|s| !s.is_empty()))
        .or_else(|| contact.numbers.first().map(|n| n.raw.trim()))
        .unwrap_or("");
    fold(chosen)
}

/// Orders `contacts` (references) by the sort key of `by`, with letters before
/// digits and symbols; equal keys keep their given order. The Contacts tab
/// calls this once per setting change or payload swap, never per frame.
pub fn sort_refs(contacts: &mut Vec<&WireContact>, by: SortBy) {
    let mut keyed: Vec<(u8, String, usize)> = contacts
        .iter()
        .enumerate()
        .map(|(i, c)| {
            // Leading punctuation does not decide the place: "(Bank)" sorts as "Bank",
            // the same way the section letter reads it.
            let key = sort_key(c, by);
            let key = key
                .trim_start_matches(|ch: char| !ch.is_alphanumeric())
                .to_owned();
            (group(&key), key, i)
        })
        .collect();
    keyed.sort();
    let sorted: Vec<&WireContact> = keyed.into_iter().map(|(_, _, i)| contacts[i]).collect();
    *contacts = sorted;
}

/// The name the section letter is taken from: the alternative (family-first)
/// name when sorting by last name, else the primary one.
pub fn section_name(contact: &WireContact, by: SortBy) -> &str {
    match by {
        SortBy::LastName => non_empty(&contact.display_name_alt).unwrap_or(&contact.display_name),
        SortBy::FirstName => &contact.display_name,
    }
}

/// Contacts that start with a letter come first, then digits and symbols
/// (the "#" section), as in the system Contacts app.
fn group(key: &str) -> u8 {
    u8::from(!key.chars().next().is_some_and(char::is_alphabetic))
}

/// The display order of a contacts list: indices into the payload, sorted by
/// [`sort_key`]. Re-sorts only when the sort setting or the payload changed.
#[derive(Clone, Debug, Default)]
pub struct NameOrder {
    by: Option<SortBy>,
    /// Payload generation the order was built for.
    generation: u64,
    order: Vec<u32>,
    sorts: u32,
}

impl NameOrder {
    /// Brings the order up to date; returns whether it was re-sorted.
    /// `generation` changes whenever the contacts payload is replaced.
    pub fn ensure(&mut self, contacts: &[WireContact], by: SortBy, generation: u64) -> bool {
        if self.by == Some(by)
            && self.generation == generation
            && self.order.len() == contacts.len()
        {
            return false;
        }
        let mut keyed: Vec<(u8, String, u32)> = contacts
            .iter()
            .enumerate()
            .map(|(i, c)| {
                let key = sort_key(c, by);
                (group(&key), key, i as u32)
            })
            .collect();
        // Index last: equal keys keep the provider's order.
        keyed.sort();
        self.order.clear();
        self.order.extend(keyed.into_iter().map(|(_, _, i)| i));
        self.by = Some(by);
        self.generation = generation;
        self.sorts += 1;
        true
    }

    /// Indices into the contacts payload, in display order.
    pub fn indices(&self) -> &[u32] {
        &self.order
    }

    /// How many times the list was actually sorted.
    pub fn sorts(&self) -> u32 {
        self.sorts
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::WireNumber;

    fn person(
        name: &str,
        alt: Option<&str>,
        key: Option<&str>,
        key_alt: Option<&str>,
    ) -> WireContact {
        WireContact {
            id: 1,
            display_name: name.into(),
            display_name_alt: alt.map(Into::into),
            sort_key: key.map(Into::into),
            sort_key_alt: key_alt.map(Into::into),
            ..WireContact::default()
        }
    }

    fn ayse() -> WireContact {
        person(
            "Ayse Yilmaz",
            Some("Yilmaz, Ayse"),
            Some("Ayse Yilmaz"),
            Some("Yilmaz, Ayse"),
        )
    }

    fn number_only(raw: &str) -> WireContact {
        WireContact {
            numbers: vec![WireNumber {
                raw: raw.into(),
                label: None,
                default: false,
            }],
            ..WireContact::default()
        }
    }

    // ---- display -------------------------------------------------------

    #[test]
    fn sort_refs_orders_by_the_chosen_key_and_keeps_ties() {
        let mk = |name: &str, alt: &str, key: &str, key_alt: &str| WireContact {
            display_name: name.into(),
            display_name_alt: Some(alt.into()),
            sort_key: Some(key.into()),
            sort_key_alt: Some(key_alt.into()),
            ..WireContact::default()
        };
        let ayse = mk("Ayse Zeybek", "Zeybek, Ayse", "ayse zeybek", "zeybek ayse");
        let can = mk("Can Aksoy", "Aksoy, Can", "can aksoy", "aksoy can");
        let num = mk("5551", "5551", "5551", "5551");
        let mut v = vec![&num, &can, &ayse];
        sort_refs(&mut v, SortBy::FirstName);
        assert_eq!(v[0].display_name, "Ayse Zeybek");
        assert_eq!(v[2].display_name, "5551");
        sort_refs(&mut v, SortBy::LastName);
        assert_eq!(v[0].display_name, "Can Aksoy");
        assert_eq!(section_name(&can, SortBy::LastName), "Aksoy, Can");
        assert_eq!(section_name(&can, SortBy::FirstName), "Can Aksoy");
    }

    #[test]
    fn display_picks_primary_or_alternate() {
        let c = ayse();
        assert_eq!(display(&c, NameFormat::FirstLast), "Ayse Yilmaz");
        assert_eq!(display(&c, NameFormat::LastFirst), "Yilmaz, Ayse");
    }

    #[test]
    fn a_single_name_is_the_same_either_way() {
        let c = person("Özgür", Some("Özgür"), Some("Özgür"), Some("Özgür"));
        assert_eq!(display(&c, NameFormat::FirstLast), "Özgür");
        assert_eq!(display(&c, NameFormat::LastFirst), "Özgür");
        assert_eq!(
            sort_key(&c, SortBy::FirstName),
            sort_key(&c, SortBy::LastName)
        );
        assert_eq!(sort_key(&c, SortBy::FirstName), "ozgur");
    }

    #[test]
    fn a_missing_alternative_falls_back_to_the_primary() {
        let c = person("Ayse Yilmaz", None, None, None);
        assert_eq!(display(&c, NameFormat::LastFirst), "Ayse Yilmaz");
        let c = person("Ayse Yilmaz", Some("  "), None, None);
        assert_eq!(display(&c, NameFormat::LastFirst), "Ayse Yilmaz");
        let only_alt = person("", Some("Yilmaz, Ayse"), None, None);
        assert_eq!(display(&only_alt, NameFormat::FirstLast), "Yilmaz, Ayse");
    }

    #[test]
    fn a_number_only_contact_shows_its_number() {
        let c = number_only(" 0532 123 45 67 ");
        assert_eq!(display(&c, NameFormat::FirstLast), "0532 123 45 67");
        assert_eq!(display(&c, NameFormat::LastFirst), "0532 123 45 67");
        assert_eq!(display(&WireContact::default(), NameFormat::FirstLast), "");
    }

    // ---- sort keys -----------------------------------------------------

    #[test]
    fn the_sort_key_follows_the_setting_and_is_folded() {
        let c = person(
            "Özgür Kaya",
            Some("Kaya, Özgür"),
            Some("Özgür Kaya"),
            Some("Kaya, Özgür"),
        );
        assert_eq!(sort_key(&c, SortBy::FirstName), "ozgur kaya");
        assert_eq!(sort_key(&c, SortBy::LastName), "kaya, ozgur");
    }

    #[test]
    fn without_a_provider_key_the_display_name_is_used() {
        let c = person("Ayşe Yılmaz", Some("Yılmaz, Ayşe"), None, None);
        assert_eq!(sort_key(&c, SortBy::FirstName), "ayse yilmaz");
        assert_eq!(sort_key(&c, SortBy::LastName), "yilmaz, ayse");
        assert_eq!(
            sort_key(&number_only("0532 1"), SortBy::FirstName),
            "0532 1"
        );
        assert_eq!(sort_key(&WireContact::default(), SortBy::LastName), "");
    }

    #[test]
    fn turkish_letters_sort_with_their_base_letter() {
        let people = vec![
            person("Zeynep", None, None, None),
            person("Işık", None, None, None),
            person("Ece", None, None, None),
            person("İpek", None, None, None),
            person("Özgür", None, None, None),
            person("Oya", None, None, None),
        ];
        let mut order = NameOrder::default();
        order.ensure(&people, SortBy::FirstName, 1);
        let names: Vec<&str> = order
            .indices()
            .iter()
            .map(|i| people[*i as usize].display_name.as_str())
            .collect();
        // ı and i fold to i, so they sit together between E and O.
        assert_eq!(names, ["Ece", "İpek", "Işık", "Oya", "Özgür", "Zeynep"]);
    }

    #[test]
    fn numbers_and_symbols_sort_after_letters() {
        let people = vec![
            number_only("0532 123"),
            person("Zed", None, None, None),
            person("1st Choice", None, None, None),
            person("Ada", None, None, None),
        ];
        let mut order = NameOrder::default();
        order.ensure(&people, SortBy::FirstName, 1);
        assert_eq!(order.indices(), [3, 1, 0, 2]);
    }

    // ---- re-sorting ----------------------------------------------------

    #[test]
    fn the_list_is_re_sorted_once_per_setting_change() {
        let people = vec![
            person(
                "Ayse Yilmaz",
                Some("Yilmaz, Ayse"),
                Some("Ayse Yilmaz"),
                Some("Yilmaz, Ayse"),
            ),
            person(
                "Bora Arslan",
                Some("Arslan, Bora"),
                Some("Bora Arslan"),
                Some("Arslan, Bora"),
            ),
        ];
        let mut order = NameOrder::default();
        assert!(order.ensure(&people, SortBy::FirstName, 1));
        assert_eq!(order.indices(), [0, 1]);
        // Renders ask again and again: nothing is sorted.
        for _ in 0..100 {
            assert!(!order.ensure(&people, SortBy::FirstName, 1));
        }
        assert_eq!(order.sorts(), 1);
        // The owner switches to family name first: one sort.
        assert!(order.ensure(&people, SortBy::LastName, 1));
        assert_eq!(order.indices(), [1, 0]);
        assert!(!order.ensure(&people, SortBy::LastName, 1));
        assert_eq!(order.sorts(), 2);
        // A new contacts payload sorts again.
        assert!(order.ensure(&people, SortBy::LastName, 2));
        assert_eq!(order.sorts(), 3);
    }

    #[test]
    fn equal_keys_keep_the_provider_order() {
        let people = vec![
            person("Ada", None, None, None),
            person("ada", None, None, None),
            person("ADA", None, None, None),
        ];
        let mut order = NameOrder::default();
        order.ensure(&people, SortBy::FirstName, 1);
        assert_eq!(order.indices(), [0, 1, 2]);
    }
}
