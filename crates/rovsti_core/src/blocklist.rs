//! The blocklist as the views need it: is this number blocked, and may it be
//! blocked at all (AD-27).
//!
//! The system `BlockedNumberContract` is the truth; Kotlin pulls its rows and
//! this set only answers questions about them. Hiding blocked calls in Recents
//! is by `kind == blocked` on the call-log row, never by this set.

use crate::dial::is_emergency;
use crate::phone_number::PhoneNumber;
use crate::wire::{BlockReason, BlockedNumber, CallLogEntry, EmergencyList};

/// A blocked entry: a number we can match tolerantly, or a sender id that
/// only matches itself.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Entry {
    Number(PhoneNumber),
    Text(String),
}

/// Normalised blocklist rows.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BlockSet {
    entries: Vec<Entry>,
}

fn entry_of(raw: &str) -> Option<Entry> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(
        PhoneNumber::parse(trimmed)
            .map(Entry::Number)
            .unwrap_or_else(|_| Entry::Text(trimmed.to_lowercase())),
    )
}

impl BlockSet {
    /// Builds the set from the pulled rows. The provider's `normalized` form
    /// wins over `number_raw` when it parses.
    pub fn from_rows(rows: &[BlockedNumber]) -> Self {
        let entries = rows
            .iter()
            .filter_map(|row| {
                entry_of(&row.normalized)
                    .filter(|e| matches!(e, Entry::Number(_)))
                    .or_else(|| entry_of(&row.number_raw))
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

    /// Whether `raw` (as shown in Recents or a call) is on the list. Format
    /// insensitive, and tolerant of a missing country code the way the v0.1
    /// matcher is (`PhoneNumber::matches`). Drives the Block/Unblock label and
    /// the blocked chip.
    pub fn is_blocked(&self, raw: &str) -> bool {
        let Some(probe) = entry_of(raw) else {
            return false;
        };
        self.entries.iter().any(|entry| match (entry, &probe) {
            (Entry::Number(a), Entry::Number(b)) => a.matches(b),
            (Entry::Text(a), Entry::Text(b)) => a == b,
            _ => false,
        })
    }
}

/// Why a number cannot be added to the blocklist.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum BlockRefusal {
    /// Emergency numbers can never be blocked.
    #[error("emergency numbers can't be blocked")]
    Emergency,
    /// A hidden or empty number has nothing to block.
    #[error("no number to block")]
    NoNumber,
    /// A dial code (`*#06#`, USSD) is not a caller.
    #[error("not a phone number")]
    NotANumber,
}

/// The guard every Block action goes through, before Kotlin is asked to write.
pub fn can_block(raw: &str, emergency: &EmergencyList) -> Result<(), BlockRefusal> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(BlockRefusal::NoNumber);
    }
    if is_emergency(trimmed, emergency) {
        return Err(BlockRefusal::Emergency);
    }
    if trimmed.contains(['*', '#']) {
        return Err(BlockRefusal::NotANumber);
    }
    Ok(())
}

/// Why typed text cannot be added to the blocklist (story 17.5).
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum BlockInputError {
    /// Letters, dial codes, nothing: "Not a valid number".
    #[error("Not a valid number")]
    Invalid,
    /// Emergency numbers can never be blocked.
    #[error("Emergency numbers can't be blocked")]
    Emergency,
}

/// Turns what the owner typed into the string that is blocked: the canonical
/// digits (with a leading `+` for an international number), after the FR-9
/// dial-string rules. Separators are accepted, letters are not.
pub fn parse_input(typed: &str, emergency: &EmergencyList) -> Result<String, BlockInputError> {
    let number = PhoneNumber::parse(typed.trim()).map_err(|_| BlockInputError::Invalid)?;
    match can_block(number.as_str(), emergency) {
        Ok(()) => Ok(number.as_str().to_owned()),
        Err(BlockRefusal::Emergency) => Err(BlockInputError::Emergency),
        Err(_) => Err(BlockInputError::Invalid),
    }
}

/// What one row of the Blocked numbers page says (stories 17.5, 17.6).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BlockedLine {
    pub title: String,
    pub subtitle: String,
    /// What TalkBack reads.
    pub aria: String,
}

/// The number as a person reads it: grouped when it parses, as stored when
/// it does not (a sender id).
fn shown_number(raw: &str) -> String {
    match PhoneNumber::parse(raw) {
        Ok(n) => crate::call::format_number(&n),
        Err(_) => raw.trim().to_owned(),
    }
}

/// "Blocked by you" / "Blocked by the system"; nothing when the reason is
/// not known.
pub fn reason_text(reason: Option<BlockReason>) -> Option<&'static str> {
    match reason? {
        BlockReason::User => Some("Blocked by you"),
        BlockReason::System => Some("Blocked by the system"),
        BlockReason::Unknown => None,
    }
}

/// A Numbers row: the contact's name if the number matches, else the number;
/// the subtitle is the number, or "Blocked number" when the title already is.
pub fn numbers_line(row: &BlockedNumber, contact_name: Option<&str>) -> BlockedLine {
    let raw = if row.number_raw.trim().is_empty() {
        row.normalized.as_str()
    } else {
        row.number_raw.as_str()
    };
    let number = shown_number(raw);
    let (title, subtitle) = match contact_name.filter(|n| !n.trim().is_empty()) {
        Some(name) => (name.to_owned(), number),
        None => (number, "Blocked number".to_owned()),
    };
    let aria = format!("{title}, {subtitle}. Unblock");
    BlockedLine {
        title,
        subtitle,
        aria,
    }
}

/// A Blocked calls row. `time` is the Recents time label of the call.
pub fn call_line(entry: &CallLogEntry, contact_name: Option<&str>, time: &str) -> BlockedLine {
    let number = if entry.number_raw.trim().is_empty() {
        "Unknown number".to_owned()
    } else {
        shown_number(&entry.number_raw)
    };
    let title = contact_name
        .filter(|n| !n.trim().is_empty())
        .map_or(number, str::to_owned);
    let subtitle = reason_text(entry.block_reason)
        .unwrap_or_default()
        .to_owned();
    let aria = if subtitle.is_empty() {
        format!("{title}, blocked call, {time}")
    } else {
        format!("{title}, {subtitle}, {time}")
    };
    BlockedLine {
        title,
        subtitle,
        aria,
    }
}

/// The whole list as shareable text: a header, then one line per number
/// (`Name  number` when `name_of` knows the number). Empty for no rows.
pub fn list_text(rows: &[BlockedNumber], name_of: impl Fn(&str) -> Option<String>) -> String {
    if rows.is_empty() {
        return String::new();
    }
    let mut out = String::from("Blocked numbers");
    for row in rows {
        let raw = if row.number_raw.trim().is_empty() {
            row.normalized.as_str()
        } else {
            row.number_raw.as_str()
        };
        let number = shown_number(raw);
        out.push('\n');
        match name_of(raw).filter(|n| !n.trim().is_empty()) {
            Some(name) => out.push_str(&format!("{}  {number}", name.trim())),
            None => out.push_str(&number),
        }
    }
    out
}

/// What pasting several numbers into "Block a number" would do.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BulkParse {
    /// Canonical numbers to block, without repeats.
    pub to_block: Vec<String>,
    pub skipped_emergency: usize,
    pub already_blocked: usize,
}

/// Finds every number in `text` ([`crate::dial::extract_numbers`]) and sorts
/// them: to block, emergency (never blocked) or already on the list.
/// Entries that are not blockable numbers are ignored.
pub fn parse_many(text: &str, emergency: &EmergencyList, already: &BlockSet) -> BulkParse {
    let mut out = BulkParse::default();
    for found in crate::dial::extract_numbers(text) {
        match parse_input(&found, emergency) {
            Ok(number) => {
                if already.is_blocked(&number) {
                    out.already_blocked += 1;
                } else if !out.to_block.iter().any(|n| {
                    matches!((PhoneNumber::parse(n), PhoneNumber::parse(&number)),
                        (Ok(a), Ok(b)) if a.matches(&b))
                }) {
                    out.to_block.push(number);
                }
            }
            Err(BlockInputError::Emergency) => out.skipped_emergency += 1,
            Err(BlockInputError::Invalid) => {}
        }
    }
    out
}

/// The pill after a bulk block: "Blocked 3 numbers", "Blocked 2 numbers, 1
/// already blocked".
pub fn bulk_text(b: &BulkParse) -> String {
    let n = b.to_block.len();
    let noun = if n == 1 { "number" } else { "numbers" };
    let mut out = format!("Blocked {n} {noun}");
    if b.already_blocked > 0 {
        out.push_str(&format!(", {} already blocked", b.already_blocked));
    }
    out
}

/// First line of the blocked-numbers file `to_csv` writes.
pub const CSV_HEADER: &str = "number,name";

/// Most numbers one file import will block.
pub const IMPORT_MAX: usize = 2000;

/// The list as a CSV file: header, then one CRLF row per number in list
/// order (the raw number and the contact name, both guarded and quoted).
pub fn to_csv(rows: &[BlockedNumber], name_of: impl Fn(&str) -> Option<String>) -> String {
    use crate::call_log_query::cell;
    let mut out = String::from(CSV_HEADER);
    out.push_str("\r\n");
    for row in rows {
        let raw = if row.number_raw.trim().is_empty() {
            row.normalized.as_str()
        } else {
            row.number_raw.as_str()
        };
        let raw = raw.trim();
        let name = name_of(raw).unwrap_or_default();
        out.push_str(&cell(raw));
        out.push(',');
        out.push_str(&cell(name.trim()));
        out.push_str("\r\n");
    }
    out
}

/// `rovsti-blocked-numbers-YYYY-MM-DD.csv` for the local date of `now_ms`.
pub fn csv_file_name(now_ms: i64, tz_offset_s: i32) -> String {
    format!(
        "rovsti-blocked-numbers-{}.csv",
        crate::call_log_query::local_date(now_ms, tz_offset_s).0
    )
}

/// What reading a blocked-numbers file would do.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FileImport {
    pub bulk: BulkParse,
    /// Lines that held no usable number.
    pub skipped_lines: usize,
    /// More than [`IMPORT_MAX`] new numbers: the rest was ignored.
    pub truncated: bool,
}

/// The first CSV field of `line`, unquoted, without the injection guard `'`.
fn first_field(line: &str) -> String {
    let line = line.trim_start();
    let field = if let Some(rest) = line.strip_prefix('"') {
        let mut out = String::new();
        let mut chars = rest.chars().peekable();
        while let Some(c) = chars.next() {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    chars.next();
                    out.push('"');
                } else {
                    break;
                }
            } else {
                out.push(c);
            }
        }
        out
    } else {
        line.split(',').next().unwrap_or("").to_owned()
    };
    let field = field.trim();
    field.strip_prefix('\'').unwrap_or(field).trim().to_owned()
}

/// Reads a blocked-numbers file (what [`to_csv`] writes, or any list with one
/// number per line). Blank lines, `#` comments and a `number` header are
/// skipped; the first CSV field of each other line is the number.
pub fn parse_file(text: &str, emergency: &EmergencyList, already: &BlockSet) -> FileImport {
    let mut out = FileImport::default();
    let mut parsed: Vec<PhoneNumber> = Vec::new();
    let mut first = true;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let is_first = std::mem::replace(&mut first, false);
        if is_first && line.to_lowercase().starts_with("number") {
            continue;
        }
        let field = first_field(line);
        match parse_input(&field, emergency) {
            Ok(number) => {
                let Ok(p) = PhoneNumber::parse(&number) else {
                    out.skipped_lines += 1;
                    continue;
                };
                if already.is_blocked(&number) || parsed.iter().any(|q| q.matches(&p)) {
                    out.bulk.already_blocked += 1;
                } else if out.bulk.to_block.len() >= IMPORT_MAX {
                    out.truncated = true;
                    break;
                } else {
                    parsed.push(p);
                    out.bulk.to_block.push(number);
                }
            }
            Err(BlockInputError::Emergency) => out.bulk.skipped_emergency += 1,
            Err(BlockInputError::Invalid) => out.skipped_lines += 1,
        }
    }
    out
}

/// The pill after a file import: "Blocked 12 numbers, 3 already blocked, 1
/// line skipped", or "No new numbers to block".
pub fn import_text(f: &FileImport) -> String {
    if f.bulk.to_block.is_empty() {
        return "No new numbers to block".to_owned();
    }
    let mut out = bulk_text(&f.bulk);
    if f.skipped_lines > 0 {
        let noun = if f.skipped_lines == 1 {
            "line"
        } else {
            "lines"
        };
        out.push_str(&format!(", {} {noun} skipped", f.skipped_lines));
    }
    out
}

/// Title and message of the "Unblock all" confirmation.
pub fn unblock_all_text(n: usize) -> (String, String) {
    let message = if n == 1 {
        "1 number can call and text you again.".to_owned()
    } else {
        format!("{n} numbers can call and text you again.")
    };
    ("Unblock all numbers?".to_owned(), message)
}

fn count_numbers(n: usize) -> String {
    if n == 1 {
        "1 number".to_owned()
    } else {
        format!("{n} numbers")
    }
}

/// The pill after "Unblock all" (`n` numbers were unblocked).
pub fn unblocked_all_text(n: usize) -> String {
    format!("Unblocked {}", count_numbers(n))
}

/// The pill after Undo of "Unblock all" (`n` numbers were blocked again).
pub fn blocked_again_text(n: usize) -> String {
    format!("Blocked {}", count_numbers(n))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: i64, raw: &str, normalized: &str) -> BlockedNumber {
        BlockedNumber {
            id,
            number_raw: raw.into(),
            normalized: normalized.into(),
        }
    }

    fn set() -> BlockSet {
        BlockSet::from_rows(&[
            row(1, "+90 555 111 22 33", "+905551112233"),
            row(2, "0850 000 00 00", ""),
            row(3, "ACME-BANK", ""),
            row(4, "  ", ""),
        ])
    }

    #[test]
    fn rows_become_entries_and_blank_rows_are_dropped() {
        assert_eq!(set().len(), 3);
        assert!(BlockSet::default().is_empty());
        assert!(!BlockSet::default().is_blocked("0555 111 22 33"));
    }

    #[test]
    fn matching_ignores_formatting() {
        let s = set();
        for typed in [
            "+905551112233",
            "+90 555 111 22 33",
            "+90 (555) 111-22-33",
            "+90 (0) 555 111 22 33",
            "00 90 555 111 22 33",
        ] {
            assert!(s.is_blocked(typed), "{typed}");
        }
        assert!(s.is_blocked("0850-000-00-00"));
    }

    #[test]
    fn matching_tolerates_a_missing_country_code() {
        let s = set();
        assert!(s.is_blocked("05551112233"));
        assert!(s.is_blocked("5551112233"));
        assert!(!s.is_blocked("05551112234"));
        assert!(!s.is_blocked("+905551112234"));
        // Two different countries with the same tail are different lines.
        assert!(!s.is_blocked("+495551112233"));
    }

    #[test]
    fn short_codes_match_exactly() {
        let s = BlockSet::from_rows(&[row(1, "4440000", "")]);
        assert!(s.is_blocked("444 0000"));
        assert!(!s.is_blocked("04440000"));
    }

    #[test]
    fn sender_ids_match_themselves_only() {
        let s = set();
        assert!(s.is_blocked("acme-bank"));
        assert!(s.is_blocked(" ACME-BANK "));
        assert!(!s.is_blocked("ACME"));
        assert!(!s.is_blocked(""));
        assert!(!s.is_blocked("   "));
    }

    #[test]
    fn the_normalised_form_wins_when_it_parses() {
        let s = BlockSet::from_rows(&[row(1, "garbled", "05551112233")]);
        assert!(s.is_blocked("+90 555 111 22 33"));
        assert!(!s.is_blocked("garbled"));
    }

    // ---- guard ---------------------------------------------------------

    #[test]
    fn emergency_numbers_can_never_be_blocked() {
        let tr = EmergencyList {
            numbers: vec!["155".into()],
        };
        for raw in ["112", "911", "1 1 2", "155", " 155 "] {
            assert_eq!(can_block(raw, &tr), Err(BlockRefusal::Emergency), "{raw}");
        }
        // Even with no list pulled.
        assert_eq!(
            can_block("112", &EmergencyList::default()),
            Err(BlockRefusal::Emergency)
        );
    }

    #[test]
    fn ordinary_numbers_and_codes() {
        let tr = EmergencyList::default();
        assert_eq!(can_block("+90 555 111 22 33", &tr), Ok(()));
        assert_eq!(can_block("ACME-BANK", &tr), Ok(()));
        assert_eq!(can_block("", &tr), Err(BlockRefusal::NoNumber));
        assert_eq!(can_block("  ", &tr), Err(BlockRefusal::NoNumber));
        assert_eq!(can_block("*#06#", &tr), Err(BlockRefusal::NotANumber));
    }

    #[test]
    fn typed_numbers_are_cleaned_and_checked() {
        let list = EmergencyList::default();
        assert_eq!(
            parse_input(" 0532 123-45-67 ", &list).as_deref(),
            Ok("05321234567")
        );
        assert_eq!(
            parse_input("+90 (532) 123 45 67", &list).as_deref(),
            Ok("+905321234567")
        );
        assert_eq!(
            parse_input("00 49 151 2345678", &list).as_deref(),
            Ok("+491512345678")
        );
    }

    #[test]
    fn letters_codes_and_nothing_are_not_a_number() {
        let list = EmergencyList::default();
        for bad in ["", "   ", "abc", "05a5", "*#06#", "+", "1234567890123456"] {
            assert_eq!(
                parse_input(bad, &list),
                Err(BlockInputError::Invalid),
                "{bad:?}"
            );
        }
    }

    #[test]
    fn an_emergency_number_is_refused_by_name() {
        let list = EmergencyList::default();
        assert_eq!(parse_input("112", &list), Err(BlockInputError::Emergency));
        assert_eq!(parse_input("911", &list), Err(BlockInputError::Emergency));
    }

    #[test]
    fn a_numbers_row_prefers_the_contact_name() {
        let r = BlockedNumber {
            id: 1,
            number_raw: "0532 123 45 67".into(),
            normalized: "05321234567".into(),
        };
        let named = numbers_line(&r, Some("Ayse Yilmaz"));
        assert_eq!(named.title, "Ayse Yilmaz");
        assert!(named.subtitle.contains("532"));
        let plain = numbers_line(&r, None);
        assert_eq!(plain.subtitle, "Blocked number");
        assert!(plain.title.contains("532"));
        assert!(plain.aria.ends_with("Unblock"));
    }

    #[test]
    fn a_sender_id_is_shown_as_stored() {
        let r = BlockedNumber {
            id: 2,
            number_raw: "  Bank  ".into(),
            normalized: String::new(),
        };
        assert_eq!(numbers_line(&r, None).title, "Bank");
    }

    #[test]
    fn blocked_call_reasons_read_plainly() {
        assert_eq!(reason_text(Some(BlockReason::User)), Some("Blocked by you"));
        assert_eq!(
            reason_text(Some(BlockReason::System)),
            Some("Blocked by the system")
        );
        assert_eq!(reason_text(Some(BlockReason::Unknown)), None);
        assert_eq!(reason_text(None), None);
        let entry = CallLogEntry {
            number_raw: String::new(),
            block_reason: Some(BlockReason::System),
            ..CallLogEntry::default()
        };
        let line = call_line(&entry, None, "Monday");
        assert_eq!(line.title, "Unknown number");
        assert_eq!(line.aria, "Unknown number, Blocked by the system, Monday");
        let unknown = call_line(&CallLogEntry::default(), None, "9:41");
        assert_eq!(unknown.subtitle, "");
    }

    #[test]
    fn unblock_all_pills_count_numbers() {
        assert_eq!(unblocked_all_text(1), "Unblocked 1 number");
        assert_eq!(unblocked_all_text(3), "Unblocked 3 numbers");
        assert_eq!(blocked_again_text(1), "Blocked 1 number");
        assert_eq!(blocked_again_text(12), "Blocked 12 numbers");
    }

    #[test]
    fn list_text_has_header_names_and_order() {
        let rows = [
            row(1, "+442079460958", ""),
            row(2, "+90 532 111 22 33", ""),
            row(3, "", "ACME"),
        ];
        let text = list_text(&rows, |raw| {
            raw.contains("532").then(|| "Anna Smith".to_owned())
        });
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines[0], "Blocked numbers");
        assert_eq!(lines.len(), 4);
        assert_eq!(lines[1], shown_number("+442079460958"));
        assert_eq!(
            lines[2],
            format!("Anna Smith  {}", shown_number("+90 532 111 22 33"))
        );
        assert_eq!(lines[3], "ACME");
    }

    #[test]
    fn list_text_empty_and_blank_names() {
        assert_eq!(list_text(&[], |_| Some("x".into())), "");
        let text = list_text(&[row(1, "ACME", "")], |_| Some("  ".into()));
        assert_eq!(text, "Blocked numbers\nACME");
    }

    #[test]
    fn unblock_all_singular_and_plural() {
        let (t, m) = unblock_all_text(1);
        assert_eq!(t, "Unblock all numbers?");
        assert_eq!(m, "1 number can call and text you again.");
        assert_eq!(
            unblock_all_text(3).1,
            "3 numbers can call and text you again."
        );
    }

    #[test]
    fn parse_many_sorts_numbers() {
        let emergency = EmergencyList::default();
        let already = set();
        let text =
            "0532 444 55 66, +90 555 111 22 33 / 0532 444 55 66 or 112 and 0850 000 00 00 hi";
        let p = parse_many(text, &emergency, &already);
        assert_eq!(p.to_block, ["05324445566"]);
        assert_eq!(p.already_blocked, 2);
        assert_eq!(p.skipped_emergency, 1);
        assert_eq!(bulk_text(&p), "Blocked 1 number, 2 already blocked");
        let none = parse_many("nothing here", &emergency, &already);
        assert_eq!(none, BulkParse::default());
        assert_eq!(bulk_text(&none), "Blocked 0 numbers");
    }

    // ---- file export / import -------------------------------------------

    #[test]
    fn csv_header_only_for_no_rows() {
        assert_eq!(to_csv(&[], |_| None), "number,name\r\n");
    }

    #[test]
    fn csv_rows_quote_and_guard() {
        let rows = [
            row(1, "+90 555 111 22 33", ""),
            row(2, "", "0850 000 00 00"),
            row(3, "=1+1", ""),
        ];
        let csv = to_csv(&rows, |n| {
            n.starts_with("+90").then(|| "Smith, Anna".to_owned())
        });
        assert_eq!(
            csv,
            "number,name\r\n+90 555 111 22 33,\"Smith, Anna\"\r\n0850 000 00 00,\r\n'=1+1,\r\n"
        );
    }

    #[test]
    fn csv_file_name_uses_local_date() {
        // 2026-11-06 23:30 UTC, +3h -> 2026-11-07.
        let ms = 1_794_007_800_000;
        assert_eq!(
            csv_file_name(ms, 0),
            "rovsti-blocked-numbers-2026-11-06.csv"
        );
        assert_eq!(
            csv_file_name(ms, 3 * 3600),
            "rovsti-blocked-numbers-2026-11-07.csv"
        );
    }

    #[test]
    fn parse_file_skips_header_comments_and_blanks() {
        let text = "number,name\r\n# my list\r\n\r\n0532 444 55 66,Spam\n   \n+49 30 1234567\n";
        let f = parse_file(text, &EmergencyList::default(), &BlockSet::default());
        assert_eq!(f.bulk.to_block, ["05324445566", "+49301234567"]);
        assert_eq!(f.skipped_lines, 0);
        assert!(!f.truncated);
    }

    #[test]
    fn parse_file_reads_quotes_and_guard() {
        let text = "\"0532 444 55 66\",\"Smith, Anna\"\n'+90 555 777 88 99,x\n\"\"\"0\"\"\"\n";
        let f = parse_file(text, &EmergencyList::default(), &BlockSet::default());
        assert_eq!(f.bulk.to_block, ["05324445566", "+905557778899"]);
        assert_eq!(f.skipped_lines, 1);
    }

    #[test]
    fn parse_file_sorts_emergency_repeats_and_junk() {
        let text = "112\n0532 444 55 66\n0532-444-55-66\n+90 555 111 22 33\nACME-BANK\nhello\n";
        let f = parse_file(text, &EmergencyList::default(), &set());
        assert_eq!(f.bulk.to_block, ["05324445566"]);
        assert_eq!(f.bulk.skipped_emergency, 1);
        assert_eq!(f.bulk.already_blocked, 2);
        assert_eq!(f.skipped_lines, 2);
        assert_eq!(
            import_text(&f),
            "Blocked 1 number, 2 already blocked, 2 lines skipped"
        );
    }

    #[test]
    fn parse_file_caps_at_import_max() {
        let text: String = (0..=IMPORT_MAX).map(|i| format!("+4930{i:07}\n")).collect();
        let f = parse_file(&text, &EmergencyList::default(), &BlockSet::default());
        assert_eq!(f.bulk.to_block.len(), IMPORT_MAX);
        assert!(f.truncated);
        let exact: String = (0..IMPORT_MAX).map(|i| format!("+4930{i:07}\n")).collect();
        let f = parse_file(&exact, &EmergencyList::default(), &BlockSet::default());
        assert_eq!(f.bulk.to_block.len(), IMPORT_MAX);
        assert!(!f.truncated);
    }

    #[test]
    fn csv_round_trip() {
        let rows = [
            row(1, "+90 555 111 22 33", ""),
            row(2, "0850 000 00 00", ""),
            row(3, "+49 30 1234567", ""),
        ];
        let csv = to_csv(&rows, |n| Some(format!("Name, {n}")));
        let f = parse_file(&csv, &EmergencyList::default(), &BlockSet::default());
        assert_eq!(
            f.bulk.to_block,
            ["+905551112233", "08500000000", "+49301234567"]
        );
        assert_eq!(f.skipped_lines, 0);
    }

    #[test]
    fn import_text_variants() {
        let mut f = FileImport::default();
        assert_eq!(import_text(&f), "No new numbers to block");
        f.skipped_lines = 3;
        assert_eq!(import_text(&f), "No new numbers to block");
        f.bulk.to_block = vec!["1".into(); 12];
        f.bulk.already_blocked = 3;
        f.skipped_lines = 1;
        assert_eq!(
            import_text(&f),
            "Blocked 12 numbers, 3 already blocked, 1 line skipped"
        );
        f.bulk.already_blocked = 0;
        f.skipped_lines = 0;
        assert_eq!(import_text(&f), "Blocked 12 numbers");
    }
}
