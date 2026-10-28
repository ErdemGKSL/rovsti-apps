//! Contact search: diacritic-, case- and format-insensitive (AD-14).
//!
//! [`ContactIndex`] is built once per contacts payload and then queried with
//! `&self`, so typing never rebuilds anything.

use crate::phone_number::{PhoneNumber, is_format_char, search_digits};
use crate::wire::WireContact;

/// Folds one character for comparison, appending to `out`. Turkish letters
/// fold to their ASCII base (`ı İ I -> i`, `ş -> s`, `ğ -> g`, `ö -> o`,
/// `ü -> u`, `ç -> c`), as do common Latin accents. Combining marks vanish.
fn fold_char(c: char, out: &mut String) {
    match c {
        'ı' | 'İ' | 'I' | 'i' => out.push('i'),
        'ş' | 'Ş' => out.push('s'),
        'ğ' | 'Ğ' => out.push('g'),
        'ö' | 'Ö' | 'ó' | 'ò' | 'ô' | 'õ' | 'Ó' | 'Ò' | 'Ô' | 'Õ' | 'ø' | 'Ø' => {
            out.push('o')
        }
        'ü' | 'Ü' | 'ú' | 'ù' | 'û' | 'Ú' | 'Ù' | 'Û' => out.push('u'),
        'ç' | 'Ç' | 'ć' | 'č' | 'Ć' | 'Č' => out.push('c'),
        'á' | 'à' | 'â' | 'ä' | 'ã' | 'å' | 'Á' | 'À' | 'Â' | 'Ä' | 'Ã' | 'Å' => {
            out.push('a')
        }
        'é' | 'è' | 'ê' | 'ë' | 'É' | 'È' | 'Ê' | 'Ë' => out.push('e'),
        'í' | 'ì' | 'î' | 'ï' | 'Í' | 'Ì' | 'Î' | 'Ï' => out.push('i'),
        'ñ' | 'Ñ' => out.push('n'),
        'ý' | 'ÿ' | 'Ý' => out.push('y'),
        'ß' => out.push_str("ss"),
        '\u{0300}'..='\u{036f}' => {}
        other => out.extend(other.to_lowercase()),
    }
}

/// Case- and diacritic-folded copy of `s`.
pub fn fold(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        fold_char(c, &mut out);
    }
    out
}

/// A query reduced to what matching needs.
enum Query {
    /// No text: matches everything.
    Empty,
    /// Letters present: folded name match only.
    Text(String),
    /// Digits and formatting only: digit substring against numbers, plus
    /// folded name match (names can contain digits).
    Digits {
        digits: String,
        /// The same query without a trunk `0` or `00` prefix, to find
        /// `05xx` among numbers stored as `+90 5xx`.
        national: Option<String>,
        folded: String,
    },
}

fn is_number_punctuation(c: char) -> bool {
    matches!(c, ' ' | '-' | '.' | '(' | ')' | '/' | '+' | '\u{a0}') || is_format_char(c)
}

impl Query {
    fn parse(raw: &str) -> Self {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Self::Empty;
        }
        let digit_like = trimmed
            .chars()
            .all(|c| c.is_ascii_digit() || is_number_punctuation(c));
        let digits = search_digits(trimmed);
        if digit_like && !digits.is_empty() {
            // `05..` (trunk 0) and `0090..` (international prefix) also find
            // numbers stored with a country code. Too short a rest would
            // match everything.
            let national = digits
                .strip_prefix("00")
                .or_else(|| digits.strip_prefix('0'))
                .filter(|rest| rest.len() >= 3)
                .map(str::to_owned);
            Self::Digits {
                digits,
                national,
                folded: fold(trimmed),
            }
        } else {
            Self::Text(fold(trimmed))
        }
    }
}

/// `rest` follows a country code (1 to 3 digits, or none) at the start of `digits`.
fn after_country_code(digits: &str, rest: &str) -> bool {
    (0..=3).any(|k| digits.get(k..).is_some_and(|d| d.starts_with(rest)))
}

struct Entry {
    contact: WireContact,
    folded_name: String,
    /// Digits only, per number.
    digits: Vec<String>,
    parsed: Vec<Option<PhoneNumber>>,
}

/// Immutable search index over one contacts payload, in provider order.
#[derive(Default)]
pub struct ContactIndex {
    entries: Vec<Entry>,
}

impl std::fmt::Debug for ContactIndex {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ContactIndex")
            .field("len", &self.entries.len())
            .finish()
    }
}

impl ContactIndex {
    pub fn new(contacts: Vec<WireContact>) -> Self {
        let entries = contacts
            .into_iter()
            .map(|contact| {
                let folded_name = fold(&contact.display_name);
                let digits = contact
                    .numbers
                    .iter()
                    .map(|n| search_digits(&n.raw))
                    .collect();
                let parsed = contact
                    .numbers
                    .iter()
                    .map(|n| PhoneNumber::parse(&n.raw).ok())
                    .collect();
                Entry {
                    contact,
                    folded_name,
                    digits,
                    parsed,
                }
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

    /// All contacts, in provider order.
    pub fn all(&self) -> impl ExactSizeIterator<Item = &WireContact> {
        self.entries.iter().map(|e| &e.contact)
    }

    /// Contacts matching `query`, in provider order. An empty query returns
    /// the full list.
    pub fn search(&self, query: &str) -> Vec<&WireContact> {
        match Query::parse(query) {
            Query::Empty => self.all().collect(),
            Query::Text(folded) => self
                .entries
                .iter()
                .filter(|e| e.folded_name.contains(&folded))
                .map(|e| &e.contact)
                .collect(),
            Query::Digits {
                digits,
                national,
                folded,
            } => self
                .entries
                .iter()
                .filter(|e| {
                    e.digits.iter().any(|d| {
                        d.contains(&digits)
                            || national
                                .as_deref()
                                .is_some_and(|n| after_country_code(d, n))
                    }) || e.folded_name.contains(&folded)
                })
                .map(|e| &e.contact)
                .collect(),
        }
    }

    /// The contact owning `number` (typed or from a call), by
    /// [`PhoneNumber::matches`]. `None` for unparsable numbers.
    pub fn match_number(&self, number: &str) -> Option<&WireContact> {
        let wanted = PhoneNumber::parse(number).ok()?;
        self.entries
            .iter()
            .find(|e| {
                e.parsed
                    .iter()
                    .flatten()
                    .any(|candidate| candidate.matches(&wanted))
            })
            .map(|e| &e.contact)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::WireNumber;

    fn contact(id: i64, name: &str, numbers: &[&str]) -> WireContact {
        WireContact {
            id,
            display_name: name.to_owned(),
            numbers: numbers
                .iter()
                .map(|n| WireNumber {
                    raw: (*n).to_owned(),
                    label: None,
                    default: false,
                })
                .collect(),
            starred: false,
            photo_uri: None,
        }
    }

    fn turkish() -> ContactIndex {
        ContactIndex::new(vec![
            contact(1, "Ayşe", &["+90 555 111 22 33"]),
            contact(2, "Özgür", &["0532 123 45 67"]),
            contact(3, "Işık", &[]),
            contact(4, "İlker", &[]),
            contact(5, "Çağla", &[]),
            contact(6, "Ünal", &[]),
        ])
    }

    fn names(hits: &[&WireContact]) -> Vec<String> {
        hits.iter().map(|c| c.display_name.clone()).collect()
    }

    #[test]
    fn turkish_folding_table() {
        let idx = turkish();
        let cases: &[(&str, &[&str])] = &[
            ("ays", &["Ayşe"]),
            ("AYS", &["Ayşe"]),
            ("ayşe", &["Ayşe"]),
            ("AYŞE", &["Ayşe"]),
            ("ozgur", &["Özgür"]),
            ("ÖZGÜR", &["Özgür"]),
            ("ozg", &["Özgür"]),
            ("isik", &["Işık"]),
            ("ışık", &["Işık"]),
            ("IŞIK", &["Işık"]),
            ("ilker", &["İlker"]),
            ("İLKER", &["İlker"]),
            ("ILKER", &["İlker"]),
            ("cag", &["Çağla"]),
            ("çağ", &["Çağla"]),
            ("unal", &["Ünal"]),
            ("ÜNAL", &["Ünal"]),
            // ı/ğ fold to i/g, so "ığ" is "ig": no listed name has it.
            ("ığ", &[]),
            ("zzz", &[]),
        ];
        for (q, want) in cases {
            assert_eq!(names(&idx.search(q)), *want, "query {q:?}");
        }
    }

    #[test]
    fn dotted_capital_i_does_not_leave_combining_marks() {
        assert_eq!(fold("İ"), "i");
        assert_eq!(fold("i\u{307}"), "i");
        assert_eq!(fold("Straße"), "strasse");
        assert_eq!(fold("José"), "jose");
    }

    #[test]
    fn substring_in_the_middle_matches() {
        assert_eq!(names(&turkish().search("lker")), ["İlker"]);
    }

    #[test]
    fn digits_match_by_digit_substring() {
        let idx = turkish();
        for q in [
            "5551112",
            "555 111",
            "555-111",
            "(555) 111",
            "+90 555",
            "905551112233",
        ] {
            assert_eq!(names(&idx.search(q)), ["Ayşe"], "query {q:?}");
        }
        assert_eq!(names(&idx.search("0532")), ["Özgür"]);
        assert!(idx.search("9999").is_empty());
    }

    #[test]
    fn trunk_zero_and_international_prefix_find_country_code_numbers() {
        let idx = turkish();
        for q in [
            "0555 111",
            "05551112233",
            "0090 555",
            "00905551112233",
            "0 555 111",
        ] {
            assert_eq!(names(&idx.search(q)), ["Ayşe"], "query {q:?}");
        }
        // Anchored after the country code: a 555 in the middle of a national
        // number is not a trunk-0 hit.
        let idx = ContactIndex::new(vec![contact(1, "A", &["0532 555 11 22"])]);
        assert!(idx.search("0555 11").is_empty());
        assert_eq!(idx.search("0532 555").len(), 1);
    }

    #[test]
    fn format_marks_and_optional_trunk_zero_do_not_break_search() {
        let idx = ContactIndex::new(vec![
            contact(1, "Ayşe", &["+90 (0) 555 111 22 33"]),
            contact(2, "Bo", &["\u{202a}+44 20 7946 0018\u{202c}"]),
        ]);
        assert_eq!(names(&idx.search("905551112233")), ["Ayşe"]);
        assert_eq!(names(&idx.search("\u{200e}+90 555")), ["Ayşe"]);
        assert_eq!(names(&idx.search("442079")), ["Bo"]);
        assert_eq!(names(&idx.search("\u{202a}2079\u{202c}")), ["Bo"]);
    }

    #[test]
    fn query_with_letters_never_matches_digits() {
        let idx = turkish();
        assert!(idx.search("555a").is_empty());
        assert!(idx.search("a555").is_empty());
    }

    #[test]
    fn punctuation_only_query_matches_nothing() {
        assert!(turkish().search("-").is_empty());
        assert!(turkish().search("+").is_empty());
    }

    #[test]
    fn digits_also_match_names_that_contain_them() {
        let idx = ContactIndex::new(vec![contact(1, "Room 101", &[])]);
        assert_eq!(idx.search("101").len(), 1);
    }

    #[test]
    fn empty_and_blank_queries_return_everything_in_order() {
        let idx = turkish();
        for q in ["", "   "] {
            let all = idx.search(q);
            assert_eq!(all.len(), 6);
            assert_eq!(
                all.iter().map(|c| c.id).collect::<Vec<_>>(),
                [1, 2, 3, 4, 5, 6]
            );
        }
    }

    #[test]
    fn results_keep_provider_order() {
        let idx = ContactIndex::new(vec![
            contact(9, "Zeynep Ay", &[]),
            contact(3, "Ayşe", &[]),
            contact(5, "Mayıs", &[]),
        ]);
        assert_eq!(
            idx.search("ay").iter().map(|c| c.id).collect::<Vec<_>>(),
            [9, 3, 5]
        );
    }

    #[test]
    fn match_number_uses_phone_number_matching() {
        let idx = turkish();
        let hit = idx.match_number("05551112233").expect("match");
        assert_eq!(hit.display_name, "Ayşe");
        assert_eq!(
            idx.match_number("+90 (555) 111-22-33").map(|c| c.id),
            Some(1)
        );
        assert_eq!(idx.match_number("905321234567").map(|c| c.id), Some(2));
        assert!(idx.match_number("05551112234").is_none());
    }

    #[test]
    fn match_number_rejects_unparsable_and_empty() {
        let idx = turkish();
        assert!(idx.match_number("").is_none());
        assert!(idx.match_number("*#06#").is_none());
        assert!(idx.match_number("abc").is_none());
    }

    #[test]
    fn match_number_short_codes_are_exact() {
        let idx = ContactIndex::new(vec![contact(1, "Emergency", &["112"])]);
        assert!(idx.match_number("112").is_some());
        assert!(idx.match_number("0112").is_none());
    }

    #[test]
    fn unparsable_contact_numbers_do_not_break_the_index() {
        let idx = ContactIndex::new(vec![contact(1, "Bank", &["*123#", "444 0 123"])]);
        assert_eq!(idx.search("4440").len(), 1);
        assert!(idx.match_number("4440123").is_some());
    }

    #[test]
    fn five_hundred_contacts_search_in_one_call_without_rebuilding() {
        let contacts: Vec<_> = (0..500)
            .map(|i| {
                let name = match i % 5 {
                    0 => format!("Ayşe {i}"),
                    1 => format!("Özgür {i}"),
                    2 => format!("Işık {i}"),
                    3 => format!("Çağla {i}"),
                    _ => format!("Ünal {i}"),
                };
                contact(
                    i,
                    &name,
                    &[&format!("0555 {:03} {:02} {:02}", i, i % 100, i % 90)],
                )
            })
            .collect();
        let idx = ContactIndex::new(contacts);
        assert_eq!(idx.len(), 500);

        let start = std::time::Instant::now();
        // Simulate typing "ozgur 4": each keystroke queries the same index.
        for q in ["o", "oz", "ozg", "ozgu", "ozgur", "ozgur "] {
            let hits = idx.search(q);
            assert!(!hits.is_empty());
        }
        let hits = idx.search("ayse 10");
        assert_eq!(hits.len(), 3, "Ayşe 10, 100 and 105");
        assert_eq!(idx.search("ozgur").len(), 100);
        assert!(!idx.search("5550").is_empty());
        assert!(start.elapsed() < std::time::Duration::from_millis(250));
        assert_eq!(idx.len(), 500, "index is untouched by queries");
    }

    #[test]
    fn empty_index() {
        let idx = ContactIndex::default();
        assert!(idx.is_empty());
        assert!(idx.search("a").is_empty());
        assert!(idx.search("").is_empty());
        assert!(idx.match_number("112").is_none());
    }
}
