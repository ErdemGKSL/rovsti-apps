//! Caller identity: which contact owns a number from the call log, a
//! WhatsApp call or the keypad.
//!
//! [`NumberBook`] keys every contact number by its E.164 form (the
//! provider's `NORMALIZED_NUMBER` when it sent one, else
//! [`PhoneNumber::to_e164`] with the home country), so `+905321234567`,
//! `05321234567`, `0090 532 123 45 67`, `905321234567` and
//! `(532) 123-45-67` are one key. Short codes and service numbers only match
//! exactly. A number without a country (no home country known, or a contact
//! number Android could not normalize) falls back to its last digits
//! (at most [`TAIL`], at least 9), never against a number of a known other
//! country.
//!
//! Two contacts with the same number: the starred one wins, then the most
//! recently updated one, then the lowest id, so every surface names the same
//! person.

use std::collections::HashMap;

use crate::phone_number::{MIN_E164_NSN, PhoneNumber, is_withheld, strip_extension};
use crate::wire::WireContact;

/// The most trailing digits the fallback compares.
pub const TAIL: usize = 10;

/// The fewest trailing digits the fallback accepts as the same line.
const MIN_TAIL: usize = 9;

/// How a number is looked up.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Key {
    /// `+<digits>`, a full number with its country.
    E164(String),
    /// The national digits (trunk zero dropped) of a number with no known country.
    Loose(String),
    /// A short code or service number: its digits, compared exactly.
    Short(String),
}

fn valid_e164(text: &str) -> Option<String> {
    let digits = text.trim().strip_prefix('+')?;
    let ok = (MIN_E164_NSN..=15).contains(&digits.len())
        && digits.bytes().all(|b| b.is_ascii_digit())
        && !digits.starts_with('0');
    ok.then(|| format!("+{digits}"))
}

fn key_of(raw: &str, e164: Option<&str>, home_cc: Option<&str>) -> Option<Key> {
    if is_withheld(raw) {
        return None;
    }
    if let Some(key) = e164.and_then(valid_e164) {
        return Some(Key::E164(key));
    }
    let number = PhoneNumber::parse(strip_extension(raw)).ok()?;
    if let Some(key) = number.to_e164(home_cc) {
        return Some(Key::E164(key));
    }
    let digits = number.digits();
    let nsn = digits.strip_prefix('0').unwrap_or(digits);
    if number.is_international() || nsn.len() < MIN_E164_NSN {
        // A short international number (`+90 112`) is a short code too.
        return Some(Key::Short(digits.to_owned()));
    }
    Some(Key::Loose(nsn.to_owned()))
}

fn tail(digits: &str) -> &str {
    &digits[digits.len().saturating_sub(TAIL)..]
}

/// Same line by trailing digits: at most [`TAIL`], at least [`MIN_TAIL`].
fn tails_match(a: &str, b: &str) -> bool {
    let n = TAIL.min(a.len()).min(b.len());
    n >= MIN_TAIL && a[a.len() - n..] == b[b.len() - n..]
}

/// What decides between two contacts with the same number.
#[derive(Clone, Copy, Debug)]
struct Rank {
    starred: bool,
    updated_ms: i64,
    id: i64,
}

impl Rank {
    fn of(contact: &WireContact) -> Self {
        Self {
            starred: contact.starred,
            updated_ms: contact.updated_ms.unwrap_or(0),
            id: contact.id,
        }
    }

    /// True when `self` should name the number rather than `other`.
    fn beats(self, other: Self) -> bool {
        (self.starred, self.updated_ms, std::cmp::Reverse(self.id))
            > (other.starred, other.updated_ms, std::cmp::Reverse(other.id))
    }
}

/// Every contact number, keyed for [`NumberBook::lookup`]; built once per
/// contacts payload and home country.
#[derive(Debug, Default)]
pub struct NumberBook {
    home_cc: Option<String>,
    /// E.164 key to the best contact (position in the payload).
    e164: HashMap<String, usize>,
    /// Exact digits of short codes to the best contact.
    short: HashMap<String, usize>,
    /// Digits (no `+`) of every E.164 number, for a wanted number with no country.
    known: Vec<(String, usize)>,
    /// National digits of contact numbers with no country.
    loose: Vec<(String, usize)>,
    ranks: Vec<Rank>,
}

impl NumberBook {
    pub fn new(contacts: &[WireContact], home_cc: Option<&str>) -> Self {
        let mut book = Self {
            home_cc: home_cc.map(str::to_owned),
            ranks: contacts.iter().map(Rank::of).collect(),
            ..Self::default()
        };
        for (at, contact) in contacts.iter().enumerate() {
            for number in &contact.numbers {
                match key_of(&number.raw, number.normalized.as_deref(), home_cc) {
                    Some(Key::E164(key)) => {
                        book.known.push((key[1..].to_owned(), at));
                        book.keep(Bucket::E164, key, at);
                    }
                    Some(Key::Short(key)) => book.keep(Bucket::Short, key, at),
                    Some(Key::Loose(digits)) => book.loose.push((digits, at)),
                    None => {}
                }
            }
        }
        book
    }

    /// The home calling code the book was built with.
    pub fn home_cc(&self) -> Option<&str> {
        self.home_cc.as_deref()
    }

    fn keep(&mut self, bucket: Bucket, key: String, at: usize) {
        let ranks = &self.ranks;
        let map = match bucket {
            Bucket::E164 => &mut self.e164,
            Bucket::Short => &mut self.short,
        };
        map.entry(key)
            .and_modify(|best| {
                if ranks[at].beats(ranks[*best]) {
                    *best = at;
                }
            })
            .or_insert(at);
    }

    fn best(&self, hits: impl Iterator<Item = usize>) -> Option<usize> {
        hits.reduce(|a, b| {
            if self.ranks[b].beats(self.ranks[a]) {
                b
            } else {
                a
            }
        })
    }

    /// The position of the contact that owns `raw` (with its E.164 form
    /// when the platform knows it), `None` for a stranger, a withheld number
    /// or text that is no number.
    pub fn lookup(&self, raw: &str, e164: Option<&str>) -> Option<usize> {
        match key_of(raw, e164, self.home_cc.as_deref())? {
            Key::E164(key) => self.e164.get(&key).copied().or_else(|| {
                let digits = tail(&key[1..]);
                self.best(
                    self.loose
                        .iter()
                        .filter(|(d, _)| tails_match(d, digits))
                        .map(|(_, at)| *at),
                )
            }),
            Key::Loose(digits) => self.best(
                self.known
                    .iter()
                    .chain(&self.loose)
                    .filter(|(d, _)| tails_match(d, &digits))
                    .map(|(_, at)| *at),
            ),
            Key::Short(digits) => self.short.get(&digits).copied(),
        }
    }
}

#[derive(Clone, Copy)]
enum Bucket {
    E164,
    Short,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::WireNumber;

    fn number(raw: &str, normalized: Option<&str>) -> WireNumber {
        WireNumber {
            raw: raw.to_owned(),
            label: None,
            default: false,
            normalized: normalized.map(str::to_owned),
        }
    }

    fn contact(id: i64, numbers: &[&str]) -> WireContact {
        WireContact {
            id,
            display_name: format!("C{id}"),
            numbers: numbers.iter().map(|n| number(n, None)).collect(),
            ..Default::default()
        }
    }

    fn id_of(book: &NumberBook, contacts: &[WireContact], raw: &str) -> Option<i64> {
        book.lookup(raw, None).map(|at| contacts[at].id)
    }

    #[test]
    fn every_turkish_form_finds_the_same_contact() {
        let contacts = vec![
            contact(1, &["0532 123 45 67"]),
            contact(2, &["+90 555 111 22 33"]),
        ];
        let book = NumberBook::new(&contacts, Some("90"));
        for raw in [
            "+905321234567",
            "05321234567",
            "5321234567",
            "905321234567",
            "00905321234567",
            "+90 (532) 123-45-67",
            "0 (532) 123 45 67",
            "+90 (0) 532 123 45 67",
        ] {
            assert_eq!(id_of(&book, &contacts, raw), Some(1), "{raw}");
        }
        assert_eq!(id_of(&book, &contacts, "0555 111 22 33"), Some(2));
        assert_eq!(id_of(&book, &contacts, "05321234568"), None);
    }

    #[test]
    fn the_provider_normalized_number_is_the_key() {
        let contacts = vec![WireContact {
            id: 4,
            numbers: vec![number("532-123-4567", Some("+905321234567"))],
            ..Default::default()
        }];
        // No home country known: the provider's E.164 still matches exactly.
        let book = NumberBook::new(&contacts, None);
        assert_eq!(book.lookup("+905321234567", None), Some(0));
        assert_eq!(book.lookup("x", Some("+905321234567")), Some(0));
    }

    #[test]
    fn without_a_home_country_the_last_digits_decide() {
        let contacts = vec![
            contact(1, &["0532 123 45 67"]),
            contact(2, &["+33 6 12 34 56 78"]),
        ];
        let book = NumberBook::new(&contacts, None);
        assert_eq!(id_of(&book, &contacts, "+905321234567"), Some(1));
        assert_eq!(id_of(&book, &contacts, "905321234567"), Some(1));
        assert_eq!(id_of(&book, &contacts, "06 12 34 56 78"), Some(2));
    }

    #[test]
    fn a_known_other_country_never_matches_by_tail() {
        let contacts = vec![contact(1, &["+1 532 123 4567"])];
        let book = NumberBook::new(&contacts, Some("90"));
        assert_eq!(id_of(&book, &contacts, "0532 123 45 67"), None);
        assert_eq!(id_of(&book, &contacts, "+905321234567"), None);
    }

    #[test]
    fn two_contacts_with_one_number_prefer_starred_then_recent_then_lowest_id() {
        let mut a = contact(7, &["05321234567"]);
        let mut b = contact(3, &["+905321234567"]);
        let book = NumberBook::new(&[a.clone(), b.clone()], Some("90"));
        assert_eq!(
            id_of(&book, &[a.clone(), b.clone()], "05321234567"),
            Some(3)
        );
        a.updated_ms = Some(2_000);
        b.updated_ms = Some(1_000);
        let both = [a.clone(), b.clone()];
        assert_eq!(
            id_of(&NumberBook::new(&both, Some("90")), &both, "05321234567"),
            Some(7)
        );
        b.starred = true;
        let both = [a, b];
        let book = NumberBook::new(&both, Some("90"));
        assert_eq!(id_of(&book, &both, "05321234567"), Some(3));
        // The same answer whatever the payload order.
        let flipped = [both[1].clone(), both[0].clone()];
        assert_eq!(
            id_of(
                &NumberBook::new(&flipped, Some("90")),
                &flipped,
                "05321234567"
            ),
            Some(3)
        );
    }

    #[test]
    fn the_tail_fallback_picks_the_same_winner() {
        let mut a = contact(9, &["0532 123 45 67"]);
        a.starred = true;
        let both = [contact(2, &["532 123 45 67"]), a];
        let book = NumberBook::new(&both, None);
        assert_eq!(id_of(&book, &both, "+905321234567"), Some(9));
    }

    #[test]
    fn a_deleted_contact_is_gone_from_the_next_book() {
        let before = vec![contact(1, &["05321234567"])];
        assert!(
            NumberBook::new(&before, Some("90"))
                .lookup("05321234567", None)
                .is_some()
        );
        assert!(
            NumberBook::new(&[], Some("90"))
                .lookup("05321234567", None)
                .is_none()
        );
    }

    #[test]
    fn withheld_numbers_match_nobody() {
        let contacts = vec![
            contact(1, &["1"]),
            contact(2, &["-1"]),
            contact(3, &["05321234567"]),
        ];
        let book = NumberBook::new(&contacts, Some("90"));
        for raw in ["", "-1", "-2", "-3", "Unknown", "Private", "payphone"] {
            assert_eq!(id_of(&book, &contacts, raw), None, "{raw:?}");
        }
    }

    #[test]
    fn short_codes_and_service_numbers_match_exactly() {
        let contacts = vec![
            contact(1, &["112"]),
            contact(2, &["444 0 123"]),
            contact(3, &["+90 532 444 01 23"]),
            contact(4, &["*123#"]),
        ];
        let book = NumberBook::new(&contacts, Some("90"));
        assert_eq!(id_of(&book, &contacts, "112"), Some(1));
        assert_eq!(id_of(&book, &contacts, "0112"), None);
        assert_eq!(id_of(&book, &contacts, "4440123"), Some(2));
        // A short code is never a tail of a full number, nor the other way round.
        assert_eq!(id_of(&book, &contacts, "1123"), None);
        assert_eq!(id_of(&book, &contacts, "+905324440123"), Some(3));
        assert_eq!(id_of(&book, &contacts, "*123#"), None);
        assert_eq!(id_of(&book, &contacts, "*#06#"), None);
    }

    #[test]
    fn extensions_do_not_hide_the_number() {
        let contacts = vec![
            contact(1, &["0212 555 00 00"]),
            contact(2, &["+90 216 555 00 00 ext. 9"]),
        ];
        let book = NumberBook::new(&contacts, Some("90"));
        assert_eq!(id_of(&book, &contacts, "+902125550000,12"), Some(1));
        assert_eq!(id_of(&book, &contacts, "02125550000;ext=4"), Some(1));
        assert_eq!(id_of(&book, &contacts, "+902165550000"), Some(2));
    }
}
