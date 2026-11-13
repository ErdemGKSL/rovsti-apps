//! Contact search: diacritic-, case- and format-insensitive (AD-14).
//!
//! A text query matches the name or the contact's email address (the one the
//! provider reports); digit queries match numbers and names.
//!
//! [`ContactIndex`] is built once per contacts payload and then queried with
//! `&self`, so typing never rebuilds anything.

use crate::phone_number::{is_format_char, search_digits};
use crate::wire::WireContact;

/// Folds one character for comparison, appending to `out`. Turkish letters
/// fold to their ASCII base (`ı İ I -> i`, `ş -> s`, `ğ -> g`, `ö -> o`,
/// `ü -> u`, `ç -> c`), as do common Latin accents. Combining marks vanish.
pub(crate) fn fold_char(c: char, out: &mut String) {
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
    /// Letters present: folded name or email match.
    Text(String),
    /// Digits and formatting only: digit substring against numbers, plus
    /// folded name match (names can contain digits).
    Digits {
        digits: String,
        /// The same query without a trunk `0` or `00` prefix, to find
        /// `05xx` among numbers stored as `+90 5xx`.
        national: Option<String>,
        /// The query's caller-ID key when it is a whole number (not a short
        /// code): finds a number saved in another form, as caller-ID does.
        line: Option<String>,
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
            let line =
                crate::caller_id::line_key(trimmed, None, None).filter(|key| !key.starts_with('#'));
            Self::Digits {
                digits,
                national,
                line,
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

/// Folded words of a name, split on anything that is not alphanumeric.
fn name_words(name: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for w in fold(name).split(|c: char| !c.is_alphanumeric()) {
        if !w.is_empty() && !out.iter().any(|o| o == w) {
            out.push(w.to_owned());
        }
    }
    out
}

/// First letter of every word, folded and joined.
fn initials_of(name: &str) -> String {
    fold(name)
        .split(|c: char| !c.is_alphanumeric())
        .filter_map(|w| w.chars().next())
        .collect()
}

pub(crate) struct Entry {
    pub(crate) contact: WireContact,
    folded_name: String,
    /// Folded name words of the name and, when it differs, the alternative.
    words: Vec<String>,
    /// Folded first letters of the words of the name, and of the alternative.
    initials: String,
    initials_alt: Option<String>,
    /// Folded, trimmed email; `None` when the contact has none.
    folded_email: Option<String>,
    /// Digits only, per number.
    pub(crate) digits: Vec<String>,
    /// [`crate::caller_id::line_key`] of every number: a whole number typed in
    /// any form (`+90 532…` for a contact saved as `0532…`) finds its owner.
    pub(crate) lines: Vec<String>,
    /// T9 codes of the primary name, and of the alternative when it differs.
    pub(crate) t9: crate::t9::NameCodes,
    pub(crate) t9_alt: Option<crate::t9::NameCodes>,
    /// Calls in the Frequent window; breaks T9 ties.
    pub(crate) calls: u32,
    /// Position of the folded name among all contacts; breaks T9 ties.
    pub(crate) name_rank: u32,
}

/// Immutable search index over one contacts payload, in provider order.
#[derive(Default)]
pub struct ContactIndex {
    pub(crate) entries: Vec<Entry>,
    /// Who owns which number ([`crate::caller_id`]), for the home country set last.
    book: crate::caller_id::NumberBook,
    /// Unique per built index, so a `T9Scratch` never trusts another index.
    pub(crate) id: u64,
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
        let mut entries: Vec<Entry> = contacts
            .into_iter()
            .map(|contact| {
                let folded_name = fold(&contact.display_name);
                let folded_email = contact
                    .email
                    .as_deref()
                    .map(str::trim)
                    .filter(|e| !e.is_empty())
                    .map(fold);
                let t9 = crate::t9::NameCodes::build(&contact.display_name);
                let t9_alt = contact
                    .display_name_alt
                    .as_deref()
                    .map(str::trim)
                    .filter(|alt| !alt.is_empty() && *alt != contact.display_name.trim())
                    .map(crate::t9::NameCodes::build);
                let digits = contact
                    .numbers
                    .iter()
                    .map(|n| search_digits(&n.raw))
                    .collect();
                let lines = contact
                    .numbers
                    .iter()
                    .filter_map(|n| {
                        crate::caller_id::line_key(&n.raw, n.normalized.as_deref(), None)
                    })
                    .collect();
                let alt = contact
                    .display_name_alt
                    .as_deref()
                    .map(str::trim)
                    .filter(|alt| !alt.is_empty() && *alt != contact.display_name.trim());
                let mut words = name_words(&contact.display_name);
                let mut initials_alt = None;
                if let Some(alt) = alt {
                    for w in name_words(alt) {
                        if !words.contains(&w) {
                            words.push(w);
                        }
                    }
                    initials_alt = Some(initials_of(alt));
                }
                let initials = initials_of(&contact.display_name);
                Entry {
                    contact,
                    folded_name,
                    words,
                    initials,
                    initials_alt,
                    folded_email,
                    digits,
                    lines,
                    t9,
                    t9_alt,
                    calls: 0,
                    name_rank: 0,
                }
            })
            .collect();
        let mut by_name: Vec<u32> = (0..entries.len() as u32).collect();
        by_name.sort_by(|a, b| {
            entries[*a as usize]
                .folded_name
                .cmp(&entries[*b as usize].folded_name)
        });
        for (rank, i) in by_name.into_iter().enumerate() {
            entries[i as usize].name_rank = rank as u32;
        }
        let book = Self::book_of(&entries, None);
        Self {
            entries,
            book,
            id: crate::t9::next_index_id(),
        }
    }

    /// Call counts per contact id (see `call_log::call_counts`); the T9 strip
    /// ranks people you call often first. Replaces earlier counts.
    pub fn set_call_counts(&mut self, counts: &std::collections::HashMap<i64, u32>) {
        for entry in &mut self.entries {
            entry.calls = counts.get(&entry.contact.id).copied().unwrap_or(0);
        }
    }

    /// The contact at `idx` of a [`T9Hit`](crate::t9::T9Hit).
    pub fn contact(&self, idx: u32) -> Option<&WireContact> {
        self.entries.get(idx as usize).map(|e| &e.contact)
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
                .filter(|e| {
                    e.folded_name.contains(&folded)
                        || e.folded_email
                            .as_deref()
                            .is_some_and(|m| m.contains(&folded))
                })
                .map(|e| &e.contact)
                .collect(),
            Query::Digits {
                digits,
                national,
                line,
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
                    }) || line.as_ref().is_some_and(|l| e.lines.contains(l))
                        || e.folded_name.contains(&folded)
                })
                .map(|e| &e.contact)
                .collect(),
        }
    }

    /// Like [`search`](Self::search) but forgiving for names: after the
    /// substring hits come contacts whose name words start with every query
    /// word (any order), then contacts whose initials start with the query
    /// ("ay" finds Ayse Yilmaz). Each tier keeps provider order; no duplicates.
    pub fn search_loose(&self, query: &str) -> Vec<&WireContact> {
        let folded = match Query::parse(query) {
            Query::Text(f) => f,
            _ => return self.search(query),
        };
        let mut seen = vec![false; self.entries.len()];
        let mut out: Vec<&WireContact> = Vec::new();
        let mut push_tier = |pred: &dyn Fn(&Entry) -> bool| {
            for (i, e) in self.entries.iter().enumerate() {
                if !seen[i] && pred(e) {
                    seen[i] = true;
                    out.push(&e.contact);
                }
            }
        };
        push_tier(&|e| {
            e.folded_name.contains(&folded)
                || e.folded_email
                    .as_deref()
                    .is_some_and(|m| m.contains(&folded))
        });
        let q_words: Vec<&str> = folded.split_whitespace().collect();
        push_tier(&|e| {
            let mut used = vec![false; e.words.len()];
            q_words.iter().all(|q| {
                match e
                    .words
                    .iter()
                    .enumerate()
                    .find(|(i, w)| !used[*i] && w.starts_with(q))
                {
                    Some((i, _)) => {
                        used[i] = true;
                        true
                    }
                    None => false,
                }
            })
        });
        let n = folded.chars().count();
        if q_words.len() == 1 && (2..=5).contains(&n) && folded.chars().all(char::is_alphabetic) {
            push_tier(&|e| {
                e.initials.starts_with(&folded)
                    || e.initials_alt
                        .as_deref()
                        .is_some_and(|i| i.starts_with(&folded))
            });
        }
        out
    }

    fn book_of(entries: &[Entry], home_cc: Option<&str>) -> crate::caller_id::NumberBook {
        let contacts: Vec<WireContact> = entries.iter().map(|e| e.contact.clone()).collect();
        crate::caller_id::NumberBook::new(&contacts, home_cc)
    }

    /// The home calling code (`"90"`) national numbers belong to; the number
    /// book is rebuilt only when it changes.
    pub fn set_home_cc(&mut self, home_cc: Option<&str>) {
        let home_cc = home_cc.map(str::trim).filter(|c| !c.is_empty());
        if self.book.home_cc() != home_cc {
            self.book = Self::book_of(&self.entries, home_cc);
        }
    }

    /// The contact owning `number` (typed or from a call), by
    /// [`crate::caller_id::NumberBook`]. `None` for withheld or unparsable
    /// numbers and strangers.
    pub fn match_number(&self, number: &str) -> Option<&WireContact> {
        self.match_number_e164(number, None)
    }

    /// [`Self::match_number`] with the E.164 form the platform worked out
    /// for `number` (`PhoneNumberUtils.formatNumberToE164`), when it has one.
    pub fn match_number_e164(&self, number: &str, e164: Option<&str>) -> Option<&WireContact> {
        self.book
            .lookup(number, e164)
            .map(|at| &self.entries[at].contact)
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
                    normalized: None,
                })
                .collect(),
            starred: false,
            photo_uri: None,
            ..Default::default()
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
    fn a_whole_number_in_another_form_finds_a_nationally_saved_contact() {
        // F39: the owner's contact is saved as `0532 …`; searching the number as
        // a caller shows it (`+90 532 …`, `0090…`, `90532…`) finds them.
        let idx = ContactIndex::new(vec![
            contact(1, "Ayşe", &["0532 123 45 67"]),
            contact(2, "Bo", &["0532 123 45 68"]),
            contact(3, "Emergency", &["112"]),
        ]);
        for q in [
            "+90 532 123 45 67",
            "+905321234567",
            "905321234567",
            "0090 532 123 45 67",
            "05321234567",
        ] {
            assert_eq!(names(&idx.search(q)), ["Ayşe"], "query {q:?}");
        }
        // A short code is never a tail of a full number.
        assert_eq!(names(&idx.search("112")), ["Emergency"]);
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
    fn match_number_with_the_home_country_and_the_platform_e164() {
        let mut idx = ContactIndex::new(vec![contact(1, "Bora", &["0 212 555 00 00"])]);
        // No home country: a different country with the same tail is not ruled out, the tail decides.
        assert_eq!(idx.match_number("+902125550000").map(|c| c.id), Some(1));
        idx.set_home_cc(Some("90"));
        assert_eq!(idx.match_number("+902125550000").map(|c| c.id), Some(1));
        assert!(idx.match_number("+12125550000").is_none());
        assert_eq!(
            idx.match_number_e164("212 555 00 00", Some("+902125550000"))
                .map(|c| c.id),
            Some(1)
        );
        assert!(idx.match_number("-2").is_none());
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
    fn text_queries_find_a_contact_through_its_email() {
        let mut c = contact(1, "Ayşe", &[]);
        c.email = Some(" Ayse@Example.com ".to_owned());
        let idx = ContactIndex::new(vec![c, contact(2, "Bo", &[])]);
        for q in ["ayse@", "example.com", "EXAMPLE", "@example"] {
            assert_eq!(names(&idx.search(q)), ["Ayşe"], "query {q:?}");
        }
        assert!(idx.search("@").len() == 1, "only the contact with an email");
        assert!(idx.search("nobody@").is_empty());
    }

    fn people() -> ContactIndex {
        let mut c4 = contact(4, "Yılmaz Ayşe", &[]);
        c4.display_name_alt = Some("Ayşe Yılmaz".to_owned());
        ContactIndex::new(vec![
            contact(1, "Ayşe Yılmaz", &["0555 111 22 33"]),
            contact(2, "Ayla", &[]),
            contact(3, "Mehmet Ay Kaya", &[]),
            c4,
            contact(5, "Zeynep Çelik", &[]),
        ])
    }

    fn ids(hits: &[&WireContact]) -> Vec<i64> {
        hits.iter().map(|c| c.id).collect()
    }

    #[test]
    fn loose_initials_find_first_and_last_name() {
        let idx = people();
        // Substring hits first (1,2,3,4), no initials-only extras here.
        assert_eq!(ids(&idx.search_loose("ay")), [1, 2, 3, 4]);
        assert_eq!(ids(&idx.search_loose("yc")), [] as [i64; 0]);
        let idx = ContactIndex::new(vec![contact(1, "Ayşe Yılmaz", &[])]);
        assert_eq!(ids(&idx.search_loose("ay")), [1]);
        assert_eq!(ids(&idx.search_loose("AY")), [1]);
        let idx = ContactIndex::new(vec![contact(7, "Bora Yıldız", &[])]);
        assert!(idx.search_loose("ay").is_empty());
    }

    #[test]
    fn loose_turkish_folding_in_initials() {
        let idx = ContactIndex::new(vec![contact(1, "Çağla Şahin", &[])]);
        assert_eq!(ids(&idx.search_loose("cs")), [1]);
        assert_eq!(ids(&idx.search_loose("ÇŞ")), [1]);
    }

    #[test]
    fn loose_alt_name_initials() {
        let mut c = contact(1, "Ayşe Yılmaz", &[]);
        c.display_name_alt = Some("Yılmaz, Ayşe".to_owned());
        let idx = ContactIndex::new(vec![c]);
        assert_eq!(ids(&idx.search_loose("ya")), [1]);
        assert_eq!(ids(&idx.search_loose("ay")), [1]);
    }

    #[test]
    fn loose_words_in_any_order() {
        let idx = people();
        assert_eq!(ids(&idx.search_loose("yilmaz ay")), [4, 1]);
        assert_eq!(ids(&idx.search_loose("kay meh")), [3]);
        assert_eq!(ids(&idx.search_loose("ay kay")), [3]);
        assert!(
            idx.search_loose("ay ay").is_empty(),
            "words must be distinct"
        );
        assert!(idx.search_loose("celik zeynepp").is_empty());
    }

    #[test]
    fn loose_tier_order_and_dedup() {
        let idx = ContactIndex::new(vec![
            contact(1, "Ali Yay", &[]),
            contact(2, "Ayla", &[]),
            contact(3, "Banu Kaya", &[]),
            contact(4, "Ahmet Yilmaz", &[]),
            contact(5, "Yasar Ay", &[]),
        ]);
        // 1, 2, 5 contain "ay"; 4 matches only by initials.
        assert_eq!(ids(&idx.search_loose("ay")), [1, 2, 3, 5, 4]);
        // Word tier then substring: "ya" substrings 1,3,5; word-prefix adds 4.
        let hits = ids(&idx.search_loose("ya"));
        let mut dedup = hits.clone();
        dedup.dedup();
        assert_eq!(hits, dedup);
        assert_eq!(hits, [1, 3, 5]);
        // Word tier after substring tier.
        let idx = ContactIndex::new(vec![
            contact(1, "Ali Veli", &[]),
            contact(2, "Velioglu Ali", &[]),
        ]);
        assert_eq!(ids(&idx.search_loose("ali veli")), [1, 2]);
    }

    #[test]
    fn loose_single_letter_and_long_queries_skip_initials() {
        let idx = ContactIndex::new(vec![contact(1, "Ali Ben Can Dan Eda Fil", &[])]);
        assert!(idx.search_loose("abcdef").is_empty(), "six letters");
        assert_eq!(ids(&idx.search_loose("abcde")), [1]);
        assert!(idx.search_loose("a b").len() == 1, "words, not initials");
        // A single letter that is only an initial of a later word: "z" is no
        // substring of "Mary Jo", and "j" matches the name itself.
        let idx = ContactIndex::new(vec![contact(1, "Xan Yo", &[])]);
        assert!(idx.search_loose("z").is_empty());
        assert_eq!(ids(&idx.search_loose("xy")), [1]);
    }

    #[test]
    fn loose_digits_and_empty_match_search() {
        let idx = people();
        assert_eq!(ids(&idx.search_loose("")), ids(&idx.search("")));
        assert_eq!(ids(&idx.search_loose("555 111")), [1]);
        assert_eq!(ids(&idx.search_loose("0555")), ids(&idx.search("0555")));
    }

    #[test]
    fn search_stays_strict() {
        let idx = ContactIndex::new(vec![contact(1, "Ayşe Yılmaz", &[])]);
        assert!(idx.search("ya yi").is_empty());
        assert!(idx.search("yilmaz ayse").is_empty());
        assert_eq!(ids(&idx.search("ayse y")), [1]);
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
