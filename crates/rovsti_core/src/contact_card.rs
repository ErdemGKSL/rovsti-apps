//! The Rovsti Contacts detail card: everything one contact's page shows,
//! prepared once when the page opens so rendering only clones strings.

use crate::{
    avatar,
    contacts_view::{NumberVm, contact_text},
    names,
    wire::{NameFormat, WireContact},
};

/// One contact's page.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContactCard {
    pub id: i64,
    pub name: String,
    pub monogram: Option<char>,
    pub hue: Option<usize>,
    /// Default number first, then provider order.
    pub numbers: Vec<NumberVm>,
    pub starred: bool,
}

impl ContactCard {
    pub fn new(contact: &WireContact, format: NameFormat) -> Self {
        let mut numbers: Vec<NumberVm> = contact.numbers.iter().map(NumberVm::from_wire).collect();
        // Stable: numbers that are not the default keep their order.
        numbers.sort_by_key(|n| !n.default);
        Self {
            id: contact.id,
            name: names::display(contact, format).into_owned(),
            monogram: avatar::monogram(&contact.display_name),
            hue: avatar::hue_index(Some(contact.id)),
            numbers,
            starred: contact.starred,
        }
    }

    /// The number the Call and Message buttons use: the default, else the
    /// first. `None` when the contact has no number.
    pub fn primary(&self) -> Option<&NumberVm> {
        self.numbers.first()
    }

    /// Plain text for the share sheet: the name, then one `Label: number`
    /// line per number.
    pub fn share_text(&self) -> String {
        contact_text(&self.name, &self.numbers)
    }
}

/// Starred contacts, in the order given.
pub fn favorites<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
) -> impl Iterator<Item = &'a WireContact> {
    contacts.into_iter().filter(|c| c.starred)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::WireNumber;

    fn num(raw: &str, label: Option<&str>, default: bool) -> WireNumber {
        WireNumber {
            raw: raw.to_owned(),
            label: label.map(str::to_owned),
            default,
            normalized: None,
        }
    }

    fn person(id: i64, name: &str, numbers: Vec<WireNumber>, starred: bool) -> WireContact {
        WireContact {
            id,
            display_name: name.to_owned(),
            numbers,
            starred,
            ..Default::default()
        }
    }

    #[test]
    fn default_number_comes_first_and_others_keep_order() {
        let card = ContactCard::new(
            &person(
                4,
                "Mehmet Öz",
                vec![
                    num("0532 000 00 01", Some("mobile"), false),
                    num("0532 000 00 02", None, false),
                    num("0216 555 01 02", Some("Home"), true),
                ],
                false,
            ),
            NameFormat::default(),
        );
        let raws: Vec<&str> = card.numbers.iter().map(|n| n.raw.as_str()).collect();
        assert_eq!(raws, ["0216 555 01 02", "0532 000 00 01", "0532 000 00 02"]);
        assert_eq!(
            card.primary().map(|n| n.raw.as_str()),
            Some("0216 555 01 02")
        );
    }

    #[test]
    fn no_numbers_means_no_primary() {
        let card = ContactCard::new(&person(1, "Ada", vec![], false), NameFormat::default());
        assert!(card.primary().is_none());
        assert_eq!(card.share_text(), "Ada");
    }

    #[test]
    fn share_text_lists_labels() {
        let card = ContactCard::new(
            &person(
                2,
                "Burak Demir",
                vec![num("0532", Some("work"), false), num("0533", None, false)],
                false,
            ),
            NameFormat::default(),
        );
        let text = card.share_text();
        assert!(text.starts_with("Burak Demir\nWork: "), "{text}");
        assert!(text.contains("\nOther: "), "{text}");
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[1], format!("Work: {}", card.numbers[0].display));
    }

    #[test]
    fn favorites_keep_numberless_skip_unstarred() {
        let all = [
            person(1, "A", vec![num("1", None, false)], true),
            person(2, "B", vec![num("2", None, false)], false),
            person(3, "C", vec![], true),
        ];
        let ids: Vec<i64> = favorites(&all).map(|c| c.id).collect();
        assert_eq!(ids, [1, 3]);
    }
}
