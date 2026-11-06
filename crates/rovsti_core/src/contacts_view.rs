//! Contacts and Favorites view-models (stories 8.1 to 8.7).
//!
//! Everything a row or a sheet says is prepared here, once per contacts
//! snapshot or per query, so rendering only clones string handles. The module
//! is platform-free: the Phone app wraps these plain values in `SharedString`s.

use crate::{
    PhoneNumber, avatar,
    call::format_number,
    search::fold,
    wire::{WireContact, WireNumber},
};

/// Longest query echoed in "No contact matches ...".
pub const QUERY_ECHO_MAX: usize = 24;

/// One number of a contact, ready to draw and to dial.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NumberVm {
    /// As stored; this is what gets dialed.
    pub raw: String,
    /// Grouped for reading (`+90 555 111 22 33`).
    pub display: String,
    /// `Mobile`, `Home`, `Work` or the custom label; `Other` when unlabelled.
    pub label: String,
    /// The contact's default (super-primary) number.
    pub default: bool,
}

impl NumberVm {
    pub(crate) fn from_wire(n: &WireNumber) -> Self {
        let display = PhoneNumber::parse(&n.raw)
            .map(|p| format_number(&p))
            .unwrap_or_else(|_| n.raw.clone());
        Self {
            raw: n.raw.clone(),
            display,
            label: n
                .label
                .as_deref()
                .map(str::trim)
                .filter(|l| !l.is_empty())
                .map_or_else(|| "Other".to_owned(), capitalised),
            default: n.default,
        }
    }
}

fn capitalised(label: &str) -> String {
    let mut chars = label.chars();
    chars.next().map_or_else(String::new, |first| {
        first.to_uppercase().chain(chars).collect()
    })
}

/// One contact, ready to draw.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContactVm {
    pub id: i64,
    pub name: String,
    /// "Mobile and 2 more" for several numbers, the matched number while a
    /// number is searched, nothing otherwise.
    pub subtitle: Option<String>,
    /// Section letter (`#` for names that do not start with a letter).
    pub letter: char,
    /// First row of its section; always false while searching (sections flatten).
    pub header: bool,
    pub monogram: Option<char>,
    pub hue: Option<usize>,
    pub numbers: Vec<NumberVm>,
    pub starred: bool,
    pub photo_uri: Option<String>,
}

/// The section a name sorts under: its first letter folded to A-Z, else `#`.
pub fn section_letter(name: &str) -> char {
    name.chars()
        .find(|c| c.is_alphanumeric())
        .and_then(|c| {
            let folded = fold(&c.to_string());
            folded.chars().next()
        })
        .filter(char::is_ascii_alphabetic)
        .map_or('#', |c| c.to_ascii_uppercase())
}

fn digits_of(raw: &str) -> String {
    raw.chars().filter(char::is_ascii_digit).collect()
}

/// How names are shown and sectioned (story 16.9). The default is the v0.1
/// look: the primary name, sections by its first letter.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct NameStyle {
    pub format: crate::wire::NameFormat,
    pub sort: crate::settings::SortBy,
}

/// The contacts in the order the list shows them: the provider's order for
/// First name, re-sorted by the alternative key for Last name.
pub fn ordered<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    style: NameStyle,
) -> Vec<&'a WireContact> {
    let mut all: Vec<&WireContact> = contacts.into_iter().collect();
    if style.sort == crate::settings::SortBy::LastName {
        crate::names::sort_refs(&mut all, style.sort);
    }
    all
}

/// Builds rows for `contacts` (already in provider order). With an empty
/// `query` the rows carry section headers; with one they are a flat list and
/// a number match shows the matched number as the subtitle.
pub fn build_rows<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    query: &str,
) -> Vec<ContactVm> {
    build_rows_styled(contacts, query, NameStyle::default())
}

/// [`build_rows`] with the Name format and Sort by settings: the shown name
/// follows the format, the section letter follows the sort key.
pub fn build_rows_styled<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    query: &str,
    style: NameStyle,
) -> Vec<ContactVm> {
    let query = query.trim();
    let sections = query.is_empty();
    let wanted = digits_of(query);
    let digit_query = !wanted.is_empty()
        && query
            .chars()
            .all(|c| c.is_ascii_digit() || " -.()/+\u{a0}".contains(c));
    let mut out: Vec<ContactVm> = Vec::new();
    for c in contacts {
        let numbers: Vec<NumberVm> = c.numbers.iter().map(NumberVm::from_wire).collect();
        let letter = section_letter(crate::names::section_name(c, style.sort));
        let subtitle = if digit_query {
            c.numbers
                .iter()
                .position(|n| digits_of(&n.raw).contains(&wanted))
                .map(|i| numbers[i].display.clone())
        } else {
            None
        }
        .or_else(|| {
            if numbers.is_empty() {
                return c.email.clone().filter(|e| !e.trim().is_empty());
            }
            (numbers.len() > 1).then(|| {
                let lead = numbers.iter().find(|n| n.default).unwrap_or(&numbers[0]);
                format!("{} and {} more", lead.label, numbers.len() - 1)
            })
        });
        let header = sections && out.last().is_none_or(|prev| prev.letter != letter);
        out.push(ContactVm {
            id: c.id,
            name: crate::names::display(c, style.format).into_owned(),
            subtitle,
            letter,
            header,
            monogram: avatar::monogram(&c.display_name),
            hue: avatar::hue_index(Some(c.id)),
            numbers,
            starred: c.starred,
            photo_uri: c.photo_uri.clone(),
        });
    }
    out
}

/// What tapping a contact (a Favorites tile) does (story 8.7).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CallTarget {
    /// Call `numbers[index]` now.
    Direct(usize),
    /// Several numbers and no default: ask which.
    Choose,
    /// Nothing to call.
    Nothing,
}

pub fn call_target(numbers: &[NumberVm]) -> CallTarget {
    if let Some(i) = numbers.iter().position(|n| n.default) {
        return CallTarget::Direct(i);
    }
    match numbers.len() {
        0 => CallTarget::Nothing,
        1 => CallTarget::Direct(0),
        _ => CallTarget::Choose,
    }
}

/// The query as echoed in the empty state: trimmed, at most 24 characters.
pub fn clip_query(query: &str) -> String {
    let query = query.trim();
    if query.chars().count() <= QUERY_ECHO_MAX {
        return query.to_owned();
    }
    let head: String = query.chars().take(QUERY_ECHO_MAX - 1).collect();
    format!("{head}…")
}

/// The number a search query stands for, when the query is a number: `None`
/// if it has a letter or is not dialable, else the pasted form normalised.
pub fn query_number(query: &str) -> Option<String> {
    if query.chars().any(char::is_alphabetic) {
        return None;
    }
    let number = crate::dial::normalise_paste(query);
    if number.is_empty() {
        return None;
    }
    crate::dial::validate(&number).ok()?;
    Some(number)
}

/// Tile columns for a window `width_dp` wide: 2, or 3 from 440 dp.
pub fn favorite_columns(width_dp: f32) -> usize {
    if width_dp >= 440.0 { 3 } else { 2 }
}

/// Favorites in the user's saved order. Contacts whose `lookup_key` is in
/// `order` come first, by index there; the rest follow by name, then id. Keys
/// of vanished contacts are skipped.
pub fn order_favorites<'a>(favorites: &'a [WireContact], order: &[String]) -> Vec<&'a WireContact> {
    let rank = |c: &WireContact| {
        c.lookup_key
            .as_deref()
            .and_then(|key| order.iter().position(|k| k == key))
    };
    let mut shown: Vec<&WireContact> = favorites.iter().collect();
    shown.sort_by_cached_key(|c| (rank(c).unwrap_or(usize::MAX), fold(&c.display_name), c.id));
    shown
}

/// The key list after moving the favorite at `from` to `to`. Contacts without
/// a key are left out; an out-of-range index returns the list unchanged.
pub fn reordered_keys(shown: &[&WireContact], from: usize, to: usize) -> Vec<String> {
    let mut keys: Vec<String> = shown.iter().filter_map(|c| c.lookup_key.clone()).collect();
    if from < keys.len() && to < keys.len() {
        crate::settings::move_reply(&mut keys, from, to);
    }
    keys
}

/// The contacts whose id is not in `hidden`, in the order given.
pub fn without<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    hidden: &[i64],
) -> Vec<WireContact> {
    contacts
        .into_iter()
        .filter(|c| !hidden.contains(&c.id))
        .cloned()
        .collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn query_number_accepts_numbers_only() {
        use super::query_number;
        assert_eq!(
            query_number("0555 111 22 33").as_deref(),
            Some("05551112233")
        );
        assert_eq!(
            query_number(" +90 (555) 111-22-33 ").as_deref(),
            Some("+905551112233")
        );
        assert_eq!(query_number("Ayse"), None);
        assert_eq!(query_number("555 abc"), None);
        assert_eq!(query_number(""), None);
        assert_eq!(query_number("  "), None);
        assert_eq!(query_number("-- ()"), None);
    }

    use super::*;

    fn num(raw: &str, label: Option<&str>, default: bool) -> WireNumber {
        WireNumber {
            raw: raw.to_owned(),
            label: label.map(str::to_owned),
            default,
        }
    }

    fn person(id: i64, name: &str, numbers: Vec<WireNumber>) -> WireContact {
        WireContact {
            id,
            display_name: name.to_owned(),
            numbers,
            starred: false,
            photo_uri: None,
            ..Default::default()
        }
    }

    fn fav(id: i64, name: &str, key: Option<&str>) -> WireContact {
        WireContact {
            lookup_key: key.map(str::to_owned),
            ..person(id, name, Vec::new())
        }
    }

    fn keys(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| (*s).to_owned()).collect()
    }

    fn ids(v: &[&WireContact]) -> Vec<i64> {
        v.iter().map(|c| c.id).collect()
    }

    #[test]
    fn favorites_follow_saved_order_then_name() {
        let all = [
            fav(1, "Zeynep", Some("z")),
            fav(2, "ali", Some("a")),
            fav(3, "Burak", Some("b")),
            fav(4, "Cem", None),
            fav(5, "Ece", Some("e")),
        ];
        // "gone" vanished; "e" is saved first, "z" second.
        let shown = order_favorites(&all, &keys(&["e", "gone", "z"]));
        assert_eq!(ids(&shown), [5, 1, 2, 3, 4]);
    }

    #[test]
    fn favorites_duplicate_key_and_tie_break_by_id() {
        let all = [fav(9, "Same", Some("x")), fav(3, "same", Some("x"))];
        let shown = order_favorites(&all, &keys(&["x"]));
        assert_eq!(ids(&shown), [3, 9]);
        let shown = order_favorites(&all, &[]);
        assert_eq!(ids(&shown), [3, 9]);
    }

    #[test]
    fn reordered_keys_moves_and_guards() {
        let all = [
            fav(1, "A", Some("a")),
            fav(2, "B", None),
            fav(3, "C", Some("c")),
            fav(4, "D", Some("d")),
        ];
        let shown: Vec<&WireContact> = all.iter().collect();
        assert_eq!(reordered_keys(&shown, 0, 2), keys(&["c", "d", "a"]));
        assert_eq!(reordered_keys(&shown, 2, 0), keys(&["d", "a", "c"]));
        assert_eq!(reordered_keys(&shown, 0, 0), keys(&["a", "c", "d"]));
        assert_eq!(reordered_keys(&shown, 0, 9), keys(&["a", "c", "d"]));
        assert_eq!(reordered_keys(&shown, 9, 0), keys(&["a", "c", "d"]));
    }

    #[test]
    fn sections_use_the_folded_first_letter() {
        assert_eq!(section_letter("Ayşe"), 'A');
        assert_eq!(section_letter("Özgür"), 'O');
        assert_eq!(section_letter("İpek"), 'I');
        assert_eq!(section_letter("  zeynep"), 'Z');
        assert_eq!(section_letter("+90 555"), '#');
        assert_eq!(section_letter("5 Alice"), '#');
        assert_eq!(section_letter(""), '#');
    }

    #[test]
    fn headers_mark_the_first_row_of_each_section() {
        let people = vec![
            person(1, "Ada", vec![num("0532 123 45 67", None, false)]),
            person(2, "Adem", vec![num("0533 123 45 67", None, false)]),
            person(3, "Burak", vec![num("0534 123 45 67", None, false)]),
        ];
        let rows = build_rows(&people, "");
        assert_eq!(
            rows.iter().map(|r| r.header).collect::<Vec<_>>(),
            [true, false, true]
        );
        // Searching flattens the sections.
        assert!(build_rows(&people, "a").iter().all(|r| !r.header));
    }

    #[test]
    fn a_number_less_row_shows_the_email() {
        let mut p = person(1, "Ghost", vec![]);
        assert_eq!(build_rows(&[p.clone()], "")[0].subtitle, None);
        p.email = Some("g@x.com".into());
        let rows = build_rows(&[p], "");
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].subtitle.as_deref(), Some("g@x.com"));
        assert!(rows[0].numbers.is_empty());
    }

    #[test]
    fn several_numbers_say_how_many_more() {
        let people = vec![person(
            1,
            "Elif",
            vec![
                num("+90 212 555 00 11", Some("work"), false),
                num("0533 765 43 21", None, true),
                num("0533 765 43 22", Some("Home"), false),
            ],
        )];
        let rows = build_rows(&people, "");
        // The default number leads the subtitle.
        assert_eq!(rows[0].subtitle.as_deref(), Some("Other and 2 more"));
        let one = vec![person(2, "Can", vec![num("0533 765 43 21", None, false)])];
        assert_eq!(build_rows(&one, "")[0].subtitle, None);
    }

    #[test]
    fn a_number_query_shows_the_matched_number() {
        let people = vec![person(
            1,
            "Elif",
            vec![
                num("+90 212 555 00 11", Some("Work"), false),
                num("0533 765 43 21", Some("Mobile"), false),
            ],
        )];
        let rows = build_rows(&people, "765 43");
        assert_eq!(rows[0].subtitle.as_deref(), Some("0533 765 43 21"));
    }

    #[test]
    fn numbers_are_grouped_for_reading_and_kept_for_dialing() {
        let people = vec![person(
            1,
            "Ayşe",
            vec![num("+905551112233", Some("mobile"), false)],
        )];
        let n = &build_rows(&people, "")[0].numbers[0];
        assert_eq!(n.raw, "+905551112233");
        assert_eq!(n.display, "+90 555 111 22 33");
        assert_eq!(n.label, "Mobile");
    }

    #[test]
    fn call_target_prefers_the_default_number() {
        let mk = |default: bool| NumberVm {
            raw: "1".into(),
            display: "1".into(),
            label: "Mobile".into(),
            default,
        };
        assert_eq!(call_target(&[]), CallTarget::Nothing);
        assert_eq!(call_target(&[mk(false)]), CallTarget::Direct(0));
        assert_eq!(call_target(&[mk(false), mk(false)]), CallTarget::Choose);
        assert_eq!(call_target(&[mk(false), mk(true)]), CallTarget::Direct(1));
    }

    #[test]
    fn the_echoed_query_is_clipped() {
        assert_eq!(clip_query("  ayse "), "ayse");
        let long = "x".repeat(40);
        let clipped = clip_query(&long);
        assert_eq!(clipped.chars().count(), QUERY_ECHO_MAX);
        assert!(clipped.ends_with('…'));
    }

    #[test]
    fn wide_windows_get_three_columns() {
        assert_eq!(favorite_columns(402.0), 2);
        assert_eq!(favorite_columns(440.0), 3);
    }

    fn named(id: i64, name: &str, alt: &str) -> WireContact {
        WireContact {
            display_name_alt: Some(alt.to_owned()),
            sort_key: Some(name.to_lowercase()),
            sort_key_alt: Some(alt.to_lowercase()),
            ..person(id, name, vec![num("0555 000 00 01", None, false)])
        }
    }

    #[test]
    fn last_first_shows_the_alternative_and_sections_follow_the_sort_key() {
        let people = vec![
            named(1, "Ayse Yilmaz", "Yilmaz, Ayse"),
            named(2, "Can Aksoy", "Aksoy, Can"),
        ];
        let style = NameStyle {
            format: crate::wire::NameFormat::LastFirst,
            sort: crate::settings::SortBy::LastName,
        };
        let rows = build_rows_styled(ordered(&people, style), "", style);
        assert_eq!(rows[0].name, "Aksoy, Can");
        assert_eq!(rows[0].letter, 'A');
        assert_eq!(rows[1].name, "Yilmaz, Ayse");
        assert_eq!(rows[1].letter, 'Y');
        // The default style is the v0.1 look.
        let rows = build_rows(&people, "");
        assert_eq!(rows[0].name, "Ayse Yilmaz");
        assert_eq!(rows[0].letter, 'A');
    }

    #[test]
    fn first_name_sort_keeps_the_provider_order() {
        let people = vec![
            named(2, "Can Aksoy", "Aksoy, Can"),
            named(1, "Ayse Yilmaz", "Yilmaz, Ayse"),
        ];
        let out = ordered(&people, NameStyle::default());
        assert_eq!(out[0].id, 2);
    }

    #[test]
    fn without_hides_the_listed_ids_and_keeps_the_order() {
        let all = [
            person(3, "C", Vec::new()),
            person(1, "A", Vec::new()),
            person(2, "B", Vec::new()),
        ];
        let kept: Vec<i64> = without(&all, &[1]).iter().map(|c| c.id).collect();
        assert_eq!(kept, [3, 2]);
        let kept: Vec<i64> = without(&all, &[]).iter().map(|c| c.id).collect();
        assert_eq!(kept, [3, 1, 2]);
        assert!(without(&all, &[1, 2, 3]).is_empty());
    }
}
