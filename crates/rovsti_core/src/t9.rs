//! T9 on the Keypad: type digits, see the people they could spell (FR-65,
//! AD-33).
//!
//! The index is built once per contacts payload ([`ContactIndex::new`]): each
//! name is folded (Turkish-aware, see [`crate::search::fold`]) and mapped to
//! digit codes with ITU E.161 (abc2 def3 ghi4 jkl5 mno6 pqrs7 tuv8 wxyz9). Any
//! other character is a separator and ends a word. A query is a run of ASCII
//! digits; [`ContactIndex::t9`] ranks, in order:
//!
//! 1. an exact number match (3 digits or more),
//! 2. the name starts with the digits,
//! 3. a later word of the name starts with them,
//! 4. a number contains them (3 digits or more),
//!
//! then people you call more often, then name order, and returns the top
//! [`MAX_HITS`]. Typing extends the previous query, so the search narrows from
//! the previous candidates and a keypress costs a few hundred comparisons; the
//! scratch holds every buffer, so nothing allocates after warm-up.

use crate::search::{ContactIndex, fold_char};
use crate::wire::NameFormat;
use std::sync::atomic::{AtomicU64, Ordering};

/// The most hits a query returns.
pub const MAX_HITS: usize = 50;
/// Numbers are matched from this many digits; shorter queries match names only.
const MIN_NUMBER_DIGITS: usize = 3;
/// A query longer than this is not a T9 query.
const MAX_QUERY: usize = 32;

pub(crate) fn next_index_id() -> u64 {
    static NEXT: AtomicU64 = AtomicU64::new(1);
    NEXT.fetch_add(1, Ordering::Relaxed)
}

/// The digit a letter sits on, or 0 for a separator.
const fn code_of(c: char) -> u8 {
    match c {
        'a'..='c' => 2,
        'd'..='f' => 3,
        'g'..='i' => 4,
        'j'..='l' => 5,
        'm'..='o' => 6,
        'p'..='s' => 7,
        't'..='v' => 8,
        'w'..='z' => 9,
        _ => 0,
    }
}

/// One name as digit codes and word starts.
#[derive(Clone, Debug, Default)]
pub(crate) struct NameCodes {
    /// One code per folded character; 0 separates words.
    codes: Box<[u8]>,
    /// Indices into `codes` where a word begins.
    starts: Box<[u16]>,
    /// Folding can change the character count (`ß` is two, a combining mark
    /// none). Only then, the character index of every code; otherwise code
    /// index and character index are the same.
    char_of: Option<Box<[u16]>>,
}

impl NameCodes {
    pub(crate) fn build(name: &str) -> Self {
        let mut codes = Vec::with_capacity(name.len());
        let mut chars = Vec::new();
        let mut aligned = true;
        let mut folded = String::new();
        for (at, c) in name.chars().enumerate() {
            folded.clear();
            fold_char(c, &mut folded);
            if folded.chars().count() != 1 {
                aligned = false;
            }
            for f in folded.chars() {
                codes.push(code_of(f));
                chars.push(u16::try_from(at).unwrap_or(u16::MAX));
            }
        }
        // Names are short; anything longer is cut so every index fits a u16.
        codes.truncate(usize::from(u16::MAX));
        chars.truncate(usize::from(u16::MAX));
        let starts: Vec<u16> = (0..codes.len())
            .filter(|&i| codes[i] != 0 && (i == 0 || codes[i - 1] == 0))
            .map(|i| i as u16)
            .collect();
        Self {
            codes: codes.into_boxed_slice(),
            starts: starts.into_boxed_slice(),
            char_of: (!aligned).then(|| chars.into_boxed_slice()),
        }
    }

    /// Leftmost word start where `query` (digit values 2..=9) matches.
    fn find(&self, query: &[u8]) -> Option<(usize, bool)> {
        self.starts.iter().enumerate().find_map(|(word, &start)| {
            let start = usize::from(start);
            let end = start + query.len();
            let window = self.codes.get(start..end)?;
            (window == query).then_some((start, word == 0))
        })
    }

    /// Character span of `len` codes starting at code `start`.
    fn span(&self, start: usize, len: usize) -> (u16, u16) {
        let char_at = |code: usize| match &self.char_of {
            Some(map) => map.get(code).copied().unwrap_or(u16::MAX),
            None => u16::try_from(code).unwrap_or(u16::MAX),
        };
        let first = char_at(start);
        // One past the character holding the last matched code.
        let last = char_at(start + len - 1).saturating_add(1);
        (first, last)
    }
}

/// One suggestion.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct T9Hit {
    /// Position in the contacts payload ([`ContactIndex::contact`]).
    pub contact_idx: u32,
    /// The number to show and call: the one that matched, else the contact's
    /// default (or first).
    pub number_idx: u16,
    /// Characters `start..end` of the name as displayed (under the format given
    /// to [`T9Scratch::set_format`]) that the digits spell; `None` when only a
    /// number matched.
    pub name_span: Option<(u16, u16)>,
    /// 0 exact number, 1 name prefix, 2 word start, 3 number contains.
    pub rank: u8,
}

/// Buffers and memory of the previous query. Create one per Keypad and keep
/// it; it is a cache, so a stale one only costs a full scan.
#[derive(Debug)]
pub struct T9Scratch {
    index_id: u64,
    format: NameFormat,
    query: Vec<u8>,
    candidates: Vec<u32>,
    next: Vec<u32>,
    ranked: Vec<(u64, T9Hit)>,
    hits: Vec<T9Hit>,
}

impl Default for T9Scratch {
    fn default() -> Self {
        Self::new()
    }
}

impl T9Scratch {
    pub fn new() -> Self {
        Self {
            index_id: 0,
            format: NameFormat::FirstLast,
            query: Vec::with_capacity(MAX_QUERY),
            candidates: Vec::new(),
            next: Vec::new(),
            ranked: Vec::new(),
            hits: Vec::with_capacity(MAX_HITS),
        }
    }

    /// The name format the spans refer to (the owner's `name_format`).
    pub fn set_format(&mut self, format: NameFormat) {
        if self.format != format {
            self.format = format;
            self.forget();
        }
    }

    /// Drops the memory of the previous query (the field was cleared).
    pub fn forget(&mut self) {
        self.query.clear();
        self.candidates.clear();
        self.hits.clear();
    }

    /// The hits of the last query.
    pub fn hits(&self) -> &[T9Hit] {
        &self.hits
    }
}

fn sort_key(rank: u8, calls: u32, name_rank: u32, idx: u32) -> u64 {
    (u64::from(rank) << 60)
        | (u64::from(0xF_FFFF - calls.min(0xF_FFFF)) << 40)
        | (u64::from(name_rank.min(0xF_FFFF)) << 20)
        | u64::from(idx.min(0xF_FFFF))
}

/// Whether `a` and `b` are the same number apart from a trunk zero.
fn same_number(a: &[u8], b: &[u8]) -> bool {
    let strip = |s: &[u8]| {
        let mut s = s;
        while let [b'0', rest @ ..] = s {
            s = rest;
        }
        s.len()
    };
    let (la, lb) = (strip(a), strip(b));
    la > 0 && la == lb && a[a.len() - la..] == b[b.len() - lb..]
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}

impl ContactIndex {
    /// Suggestions for the digits typed so far (ASCII `0`-`9`), at most
    /// [`MAX_HITS`], best first. Anything but digits (`*`, `#`, `+`, a pause)
    /// is not a T9 query and returns nothing. Contacts without a number are
    /// never suggested.
    pub fn t9<'s>(&self, digits: &[u8], scratch: &'s mut T9Scratch) -> &'s [T9Hit] {
        if digits.is_empty() || digits.len() > MAX_QUERY || !digits.iter().all(u8::is_ascii_digit) {
            scratch.forget();
            return &scratch.hits;
        }
        // The previous candidates hold every contact the previous query
        // matched, which includes every match of a longer query that extends
        // it. Numbers only count from three digits, so a query that crosses
        // that line cannot trust a shorter one's candidates.
        let narrow = scratch.index_id == self.id
            && !scratch.query.is_empty()
            && digits.starts_with(&scratch.query)
            && (scratch.query.len() >= MIN_NUMBER_DIGITS || digits.len() < MIN_NUMBER_DIGITS);

        let query: [u8; MAX_QUERY] = {
            let mut q = [0u8; MAX_QUERY];
            for (slot, d) in q.iter_mut().zip(digits) {
                *slot = d - b'0';
            }
            q
        };
        let query = &query[..digits.len()];
        let with_numbers = digits.len() >= MIN_NUMBER_DIGITS;

        scratch.next.clear();
        scratch.ranked.clear();
        let format = scratch.format;
        let consider = |i: u32, next: &mut Vec<u32>, ranked: &mut Vec<(u64, T9Hit)>| {
            let entry = &self.entries[i as usize];
            if entry.contact.numbers.is_empty() {
                return;
            }
            let codes = match (&entry.t9_alt, format) {
                (Some(alt), NameFormat::LastFirst) => alt,
                _ => &entry.t9,
            };
            let by_name = codes.find(query);
            let mut by_number: Option<(u8, u16)> = None;
            if with_numbers {
                for (k, d) in entry.digits.iter().enumerate() {
                    let d = d.as_bytes();
                    let found = if same_number(d, digits) {
                        Some(0)
                    } else if contains(d, digits) {
                        Some(3)
                    } else {
                        None
                    };
                    if let Some(rank) = found
                        && by_number.is_none_or(|(best, _)| rank < best)
                    {
                        by_number = Some((rank, k as u16));
                    }
                }
            }
            let name_rank = by_name.map(|(_, first)| if first { 1 } else { 2 });
            let rank = match (name_rank, by_number) {
                (None, None) => return,
                (Some(n), None) => n,
                (None, Some((r, _))) => r,
                (Some(n), Some((r, _))) => n.min(r),
            };
            let number_idx = match by_number {
                Some((_, k)) if rank == 0 || rank == 3 => k,
                _ => entry
                    .contact
                    .numbers
                    .iter()
                    .position(|n| n.default)
                    .unwrap_or(0) as u16,
            };
            next.push(i);
            ranked.push((
                sort_key(rank, entry.calls, entry.name_rank, i),
                T9Hit {
                    contact_idx: i,
                    number_idx,
                    name_span: by_name.map(|(start, _)| codes.span(start, query.len())),
                    rank,
                },
            ));
        };

        if narrow {
            // Take the list out so `next` can be filled while reading it.
            let previous = std::mem::take(&mut scratch.candidates);
            for &i in &previous {
                consider(i, &mut scratch.next, &mut scratch.ranked);
            }
            scratch.candidates = previous;
        } else {
            for i in 0..self.entries.len() as u32 {
                consider(i, &mut scratch.next, &mut scratch.ranked);
            }
        }
        std::mem::swap(&mut scratch.candidates, &mut scratch.next);

        if scratch.ranked.len() > MAX_HITS {
            scratch
                .ranked
                .select_nth_unstable_by_key(MAX_HITS - 1, |(key, _)| *key);
            scratch.ranked.truncate(MAX_HITS);
        }
        scratch.ranked.sort_unstable_by_key(|(key, _)| *key);
        scratch.hits.clear();
        scratch
            .hits
            .extend(scratch.ranked.iter().map(|(_, hit)| *hit));

        scratch.index_id = self.id;
        scratch.query.clear();
        scratch.query.extend_from_slice(digits);
        &scratch.hits
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::{WireContact, WireNumber};
    use std::collections::HashMap;
    use std::time::{Duration, Instant};

    fn person(id: i64, name: &str, alt: Option<&str>, numbers: &[&str]) -> WireContact {
        WireContact {
            id,
            display_name: name.into(),
            display_name_alt: alt.map(Into::into),
            numbers: numbers
                .iter()
                .map(|n| WireNumber {
                    raw: (*n).into(),
                    label: None,
                    default: false,
                    normalized: None,
                })
                .collect(),
            ..WireContact::default()
        }
    }

    fn index() -> ContactIndex {
        ContactIndex::new(vec![
            person(
                1,
                "Ayşe Yılmaz",
                Some("Yılmaz, Ayşe"),
                &["+90 555 111 22 33"],
            ),
            person(2, "Özgür Kaya", Some("Kaya, Özgür"), &["0532 123 45 67"]),
            person(3, "Özge Demir", Some("Demir, Özge"), &["0533 765 43 21"]),
            person(4, "Ozan", None, &["0212 555 00 11", "0544 000 00 00"]),
            person(5, "Işık", None, &["0505 505 05 05"]),
            person(6, "No Number", None, &[]),
        ])
    }

    fn q(index: &ContactIndex, scratch: &mut T9Scratch, digits: &str) -> Vec<(i64, u8)> {
        index
            .t9(digits.as_bytes(), scratch)
            .iter()
            .map(|h| (index.contact(h.contact_idx).expect("contact").id, h.rank))
            .collect()
    }

    // ---- E.161 and folding -----------------------------------------------

    #[test]
    fn the_keypad_letters_follow_itu_e161() {
        let expect = [
            ('a', 2),
            ('b', 2),
            ('c', 2),
            ('d', 3),
            ('f', 3),
            ('g', 4),
            ('i', 4),
            ('j', 5),
            ('l', 5),
            ('m', 6),
            ('o', 6),
            ('p', 7),
            ('s', 7),
            ('t', 8),
            ('v', 8),
            ('w', 9),
            ('z', 9),
            (' ', 0),
            ('-', 0),
            ('1', 0),
            ('é', 0),
        ];
        for (c, d) in expect {
            assert_eq!(code_of(c), d, "{c}");
        }
    }

    #[test]
    fn seven_nine_two_finds_ayse_through_diacritic_folding() {
        let ix = index();
        let mut s = T9Scratch::new();
        // a-y-s = 2 9 7 on the keys: "ayse" is 2973; "yılmaz" starts 9.
        assert_eq!(q(&ix, &mut s, "297"), [(1, 1)]);
        assert_eq!(q(&ix, &mut s, "2973"), [(1, 1)]);
        // y-i-l (the dotless ı folds to i) is 945 and starts the second word.
        assert_eq!(q(&ix, &mut s, "945"), [(1, 2)]);
    }

    #[test]
    fn six_nine_two_finds_the_ozgur_family() {
        let ix = index();
        let mut s = T9Scratch::new();
        // ö-z-g folds to o-z-g: 6 9 4. "Özgür" and "Özge" both start so.
        let hits = q(&ix, &mut s, "694");
        let ids: Vec<i64> = hits.iter().map(|(id, _)| *id).collect();
        assert_eq!(ids, [3, 2], "Özge sorts before Özgür");
        // o-z-a-n: Ozan only.
        assert_eq!(q(&ix, &mut s, "692"), [(4, 1)]);
        // Özgür has a ü: ö-z-g-u = 6 9 4 8.
        assert_eq!(q(&ix, &mut s, "6948"), [(2, 1)]);
    }

    #[test]
    fn dotted_and_dotless_i_are_the_same_key() {
        let ix = ContactIndex::new(vec![
            person(1, "Işık", None, &["0555"]),
            person(2, "İlker", None, &["0556"]),
        ]);
        let mut s = T9Scratch::new();
        // i = 4, so both start with 4.
        let ids: Vec<i64> = q(&ix, &mut s, "4").iter().map(|h| h.0).collect();
        assert_eq!(ids.len(), 2);
    }

    // ---- ranking -------------------------------------------------------

    #[test]
    fn exact_number_then_prefix_then_word_start_then_number_substring() {
        let ix = ContactIndex::new(vec![
            person(1, "Zed Mum", None, &["0000"]), // word start of "mum" = 686
            person(2, "Mumford", None, &["0000"]), // prefix of the name
            person(3, "Zed", None, &["686"]),      // exact number
            person(4, "Zed Zed", None, &["09 686 99"]), // number contains
        ]);
        let mut s = T9Scratch::new();
        let hits = q(&ix, &mut s, "686");
        assert_eq!(hits, [(3, 0), (2, 1), (1, 2), (4, 3)]);
    }

    #[test]
    fn a_trunk_zero_does_not_defeat_an_exact_match() {
        let ix = ContactIndex::new(vec![person(1, "Zed", None, &["0532 123 45 67"])]);
        let mut s = T9Scratch::new();
        assert_eq!(q(&ix, &mut s, "5321234567"), [(1, 0)]);
        assert_eq!(q(&ix, &mut s, "05321234567"), [(1, 0)]);
    }

    #[test]
    fn short_queries_match_names_only() {
        let ix = ContactIndex::new(vec![person(1, "Zed", None, &["1234"])]);
        let mut s = T9Scratch::new();
        assert!(q(&ix, &mut s, "12").is_empty());
        assert_eq!(q(&ix, &mut s, "123"), [(1, 3)]);
        assert_eq!(q(&ix, &mut s, "1234"), [(1, 0)]);
    }

    #[test]
    fn ties_are_broken_by_call_frequency_then_name() {
        let mut ix = ContactIndex::new(vec![
            person(1, "Bora", None, &["0555"]),
            person(2, "Bahar", None, &["0556"]),
            person(3, "Banu", None, &["0557"]),
        ]);
        let mut s = T9Scratch::new();
        // "ba" = 22: all three; alphabetical: Bahar(2), Banu(3), Bora(1)? No:
        // Bora is b-o (26), so only Bahar and Banu start with 22.
        assert_eq!(q(&ix, &mut s, "22"), [(2, 1), (3, 1)]);
        let counts: HashMap<i64, u32> = [(3, 5), (2, 1)].into();
        ix.set_call_counts(&counts);
        let mut s = T9Scratch::new();
        assert_eq!(q(&ix, &mut s, "22"), [(3, 1), (2, 1)]);
    }

    #[test]
    fn the_matching_number_is_the_one_returned() {
        let ix = index();
        let mut s = T9Scratch::new();
        let hits = ix.t9(b"544000", &mut s);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].number_idx, 1);
        assert_eq!(hits[0].rank, 3);
        // A name match uses the default number (the first when none is).
        let hits = ix.t9(b"692", &mut s);
        assert_eq!(hits[0].number_idx, 0);
    }

    #[test]
    fn contacts_without_a_number_are_never_suggested() {
        let ix = index();
        let mut s = T9Scratch::new();
        // "No Number" starts 66.
        assert!(q(&ix, &mut s, "66").iter().all(|(id, _)| *id != 6));
    }

    // ---- degenerate queries --------------------------------------------

    #[test]
    fn empty_and_non_digit_queries_return_nothing() {
        let ix = index();
        let mut s = T9Scratch::new();
        assert!(ix.t9(b"", &mut s).is_empty());
        for bad in [&b"2*"[..], b"+90", b"2,3", b"#", b"2 3", b"\xff"] {
            assert!(ix.t9(bad, &mut s).is_empty(), "{bad:?}");
        }
        assert!(ix.t9(&[b'2'; 40], &mut s).is_empty());
        // 0 and 1 are not letters: no name can match them.
        assert!(ix.t9(b"1", &mut s).is_empty());
        assert!(ix.t9(b"0", &mut s).is_empty());
    }

    #[test]
    fn a_stale_scratch_gives_the_same_answer_as_a_fresh_one() {
        let ix = index();
        let other = ContactIndex::new(vec![person(9, "Zed", None, &["0999"])]);
        let mut s = T9Scratch::new();
        let _ = other.t9(b"933", &mut s);
        // The scratch remembers "933" from another index; "9335" must not narrow.
        assert!(ix.t9(b"9335", &mut s).is_empty());
        // Backspace, then a different branch.
        let mut warm = T9Scratch::new();
        for step in ["6", "69", "692", "69", "694", "6", "29", "297"] {
            let a = ix.t9(step.as_bytes(), &mut warm).to_vec();
            let b = ix.t9(step.as_bytes(), &mut T9Scratch::new()).to_vec();
            assert_eq!(a, b, "after {step}");
        }
    }

    #[test]
    fn narrowing_agrees_with_a_full_scan_at_every_length() {
        let ix = index();
        // Typing "5551112" digit by digit crosses the three-digit number line.
        let typed = "5551112";
        let mut warm = T9Scratch::new();
        for n in 1..=typed.len() {
            let a = ix.t9(&typed.as_bytes()[..n], &mut warm).to_vec();
            let b = ix
                .t9(&typed.as_bytes()[..n], &mut T9Scratch::new())
                .to_vec();
            assert_eq!(a, b, "prefix {n}");
        }
        let hits = ix.t9(b"5551112", &mut warm);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].contact_idx, 0);
    }

    #[test]
    fn at_most_fifty_hits_are_returned_best_first() {
        let many: Vec<_> = (0..300)
            .map(|i| person(i, &format!("Ab{i:03}"), None, &["0555"]))
            .collect();
        let ix = ContactIndex::new(many);
        let mut s = T9Scratch::new();
        let hits = ix.t9(b"22", &mut s);
        assert_eq!(hits.len(), MAX_HITS);
        let ids: Vec<u32> = hits.iter().map(|h| h.contact_idx).collect();
        // All tie on rank and calls: name order, which is 000, 001, ...
        assert_eq!(ids, (0..50).collect::<Vec<u32>>());
    }

    // ---- spans ---------------------------------------------------------

    fn span_text(name: &str, span: (u16, u16)) -> String {
        name.chars()
            .skip(usize::from(span.0))
            .take(usize::from(span.1 - span.0))
            .collect()
    }

    #[test]
    fn the_span_marks_what_the_digits_spell_in_the_displayed_name() {
        let ix = index();
        let mut s = T9Scratch::new();
        let hit = ix.t9(b"297", &mut s)[0];
        assert_eq!(
            span_text("Ayşe Yılmaz", hit.name_span.expect("span")),
            "Ayş"
        );
        let hit = ix.t9(b"945", &mut s)[0];
        assert_eq!(
            span_text("Ayşe Yılmaz", hit.name_span.expect("span")),
            "Yıl"
        );
    }

    #[test]
    fn the_span_follows_the_name_format() {
        let ix = index();
        let mut s = T9Scratch::new();
        s.set_format(NameFormat::LastFirst);
        // Displayed as "Yılmaz, Ayşe": "yil" is now the prefix.
        let hit = ix.t9(b"945", &mut s)[0];
        assert_eq!(hit.rank, 1);
        assert_eq!(
            span_text("Yılmaz, Ayşe", hit.name_span.expect("span")),
            "Yıl"
        );
        // "ays" is a later word.
        let hit = ix.t9(b"297", &mut s)[0];
        assert_eq!(hit.rank, 2);
        assert_eq!(
            span_text("Yılmaz, Ayşe", hit.name_span.expect("span")),
            "Ayş"
        );
        // Switching format forgets the narrowing.
        s.set_format(NameFormat::FirstLast);
        assert_eq!(ix.t9(b"945", &mut s)[0].rank, 2);
    }

    #[test]
    fn a_number_only_hit_has_no_span() {
        let ix = index();
        let mut s = T9Scratch::new();
        let hit = ix.t9(b"5551112", &mut s)[0];
        assert_eq!(hit.name_span, None);
    }

    #[test]
    fn spans_stay_on_characters_when_folding_changes_the_length() {
        // ß folds to ss (2 codes, 1 char); a combining mark folds to nothing.
        let ix = ContactIndex::new(vec![
            person(1, "Straße Zed", None, &["0555"]),
            person(2, "Jose\u{301} Ana", None, &["0556"]),
        ]);
        let mut s = T9Scratch::new();
        // z-e-d: 933, the second word of "Straße Zed" after the ß.
        let hit = ix.t9(b"933", &mut s)[0];
        assert_eq!(span_text("Straße Zed", hit.name_span.expect("span")), "Zed");
        // a-n-a after the combining mark.
        let hit = ix.t9(b"262", &mut s)[0];
        assert_eq!(
            span_text("Jose\u{301} Ana", hit.name_span.expect("span")),
            "Ana"
        );
        // The ß is two codes (ss = 77) and one character in the span.
        let hit = ix.t9(b"787277", &mut s)[0];
        assert_eq!(
            span_text("Straße Zed", hit.name_span.expect("span")),
            "Straß"
        );
    }

    // ---- cost ----------------------------------------------------------

    fn big_index(n: usize) -> ContactIndex {
        const SYLLABLES: [&str; 16] = [
            "ay", "se", "oz", "gur", "ka", "ya", "de", "mir", "is", "ik", "el", "ci", "ba", "nu",
            "ta", "ri",
        ];
        let contacts: Vec<_> = (0..n)
            .map(|i| {
                let word = |seed: usize| {
                    let mut w = String::new();
                    for k in 0..3 {
                        w.push_str(SYLLABLES[(seed / (k + 1) + k * 5 + seed % 7) % 16]);
                    }
                    w
                };
                let name = format!("{} {}", word(i * 3 + 1), word(i * 7 + 2));
                let number = format!(
                    "05{:02} {:03} {:02} {:02}",
                    30 + i % 40,
                    i % 1000,
                    i % 97,
                    i % 89
                );
                person(i as i64, &name, None, &[&number, "0212 000 00 00"])
            })
            .collect();
        ContactIndex::new(contacts)
    }

    #[test]
    fn two_thousand_contacts_stay_well_inside_the_ceiling() {
        let ix = big_index(2_000);
        let mut s = T9Scratch::new();
        // Warm up so the buffers have their final capacity.
        let _ = ix.t9(b"2", &mut s);
        let sequences = [
            "2", "29", "297", "2973", "29731", "6", "69", "694", "6948", "3", "34", "345",
        ];
        let mut keypresses: Vec<Duration> = Vec::new();
        let mut worst = Duration::ZERO;
        for _round in 0..5 {
            let mut previous = "";
            for seq in sequences {
                if !seq.starts_with(previous) {
                    s.forget();
                }
                let start = Instant::now();
                let n = ix.t9(seq.as_bytes(), &mut s).len();
                let took = start.elapsed();
                assert!(n <= MAX_HITS);
                keypresses.push(took);
                worst = worst.max(took);
                previous = seq;
            }
        }
        keypresses.sort();
        let p95 = keypresses[keypresses.len() * 95 / 100];
        let typical = keypresses[keypresses.len() / 2];
        // Recorded in the completion notes (debug build; release is faster).
        eprintln!("t9 2000 contacts: median {typical:?}, p95 {p95:?}, worst {worst:?}");
        assert!(worst < Duration::from_millis(8), "worst case {worst:?}");
    }

    #[test]
    fn a_warm_scratch_does_not_grow() {
        let ix = big_index(500);
        let mut s = T9Scratch::new();
        let _ = ix.t9(b"2", &mut s);
        let caps = (
            s.candidates.capacity(),
            s.next.capacity(),
            s.ranked.capacity(),
            s.hits.capacity(),
            s.query.capacity(),
        );
        for seq in ["2", "29", "297", "2973", "29", "2", "6", "69", "694"] {
            let _ = ix.t9(seq.as_bytes(), &mut s);
            let now = (
                s.candidates.capacity(),
                s.next.capacity(),
                s.ranked.capacity(),
                s.hits.capacity(),
                s.query.capacity(),
            );
            assert!(
                now.0 >= caps.0 && now.1 >= caps.1,
                "capacities only ever grow: {caps:?} -> {now:?}"
            );
        }
        // After one pass over a broad query, the same query never reallocates.
        let _ = ix.t9(b"2", &mut s);
        let warm = (
            s.candidates.capacity(),
            s.next.capacity(),
            s.ranked.capacity(),
        );
        for _ in 0..10 {
            let _ = ix.t9(b"2", &mut s);
            s.forget();
            let _ = ix.t9(b"2", &mut s);
        }
        assert_eq!(
            warm,
            (
                s.candidates.capacity(),
                s.next.capacity(),
                s.ranked.capacity()
            )
        );
    }
}
