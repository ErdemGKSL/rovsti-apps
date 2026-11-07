//! Contacts as CSV: the Google Contacts column set for export, and Google or
//! Outlook files for import.
//!
//! Export writes a UTF-8 byte order mark, CRLF line ends and RFC 4180
//! quoting; phones, emails, addresses and websites get numbered columns (at
//! most [`MAX_COLUMNS`] each) and a `* ` before the label marks the default,
//! as Google does. Import reads the header row case-insensitively, splits
//! multi value cells at ` ::: `, and counts rows that are not usable cards
//! in [`vcard::Parsed::skipped`]. Pure text in, pure values out.

use std::collections::BTreeMap;

use crate::contact_dates;
use crate::contact_edit::{
    ContactDraft, DraftEvent, DraftField, DraftRelation, EventKind, FieldKind, RelationKind,
};
use crate::vcard::Parsed;

/// Most numbered columns of one kind (phones, emails, addresses, websites).
pub const MAX_COLUMNS: usize = 10;
/// Most data rows an import reads.
pub const MAX_ROWS: usize = 5000;

const BOM: &str = "\u{FEFF}";
const MULTI: &str = " ::: ";

/// Whether a file looks like a contacts CSV by type, name or header row.
pub fn looks_like_csv(mime: Option<&str>, name: &str, head: &[u8]) -> bool {
    if let Some(mime) = mime {
        let mime = mime
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        if matches!(
            mime.as_str(),
            "text/csv" | "text/comma-separated-values" | "application/csv"
        ) {
            return true;
        }
    }
    if name.to_ascii_lowercase().ends_with(".csv") {
        return true;
    }
    let head = head.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(head);
    let line = head.split(|b| *b == b'\n').next().unwrap_or(head);
    let line = String::from_utf8_lossy(line).to_lowercase();
    line.contains(',') && (line.contains("first name") || line.contains("given name"))
}

/// File name for an export: `rovsti-contacts-<n>-YYYY-MM-DD.csv`.
pub fn file_name(count: usize, today: (i32, u8, u8)) -> String {
    format!(
        "rovsti-contacts-{count}-{:04}-{:02}-{:02}.csv",
        today.0, today.1, today.2
    )
}

// ---------------------------------------------------------------- writing

/// Writes `drafts` as a Google style CSV.
pub fn write(drafts: &[ContactDraft]) -> String {
    let used =
        |f: fn(&ContactDraft) -> usize| drafts.iter().map(f).max().unwrap_or(0).min(MAX_COLUMNS);
    let phones = used(|d| d.numbers.len());
    let emails = used(|d| d.emails.len());
    let addresses = used(|d| d.addresses.len());
    let sites = used(|d| d.websites.len());
    let relations = used(|d| d.relations.len());

    let mut header: Vec<String> = [
        "First Name",
        "Middle Name",
        "Last Name",
        "Phonetic First Name",
        "Phonetic Last Name",
        "Nickname",
        "Organization Name",
        "Organization Title",
        "Birthday",
        "Notes",
    ]
    .map(str::to_owned)
    .into();
    for n in 1..=phones {
        header.push(format!("Phone {n} - Label"));
        header.push(format!("Phone {n} - Value"));
    }
    for n in 1..=emails {
        header.push(format!("E-mail {n} - Label"));
        header.push(format!("E-mail {n} - Value"));
    }
    for n in 1..=addresses {
        header.push(format!("Address {n} - Label"));
        header.push(format!("Address {n} - Formatted"));
    }
    for n in 1..=sites {
        header.push(format!("Website {n} - Value"));
    }
    for n in 1..=relations {
        header.push(format!("Relation {n} - Label"));
        header.push(format!("Relation {n} - Value"));
    }

    let mut out = String::from(BOM);
    push_row(&mut out, &header);
    for d in drafts {
        let birthday = d
            .events
            .iter()
            .filter(|e| e.kind == EventKind::Birthday)
            .find_map(|e| contact_dates::parse(&e.date))
            .map(|date| date.to_android())
            .unwrap_or_default();
        let mut row = vec![
            d.given.clone(),
            String::new(),
            d.family.clone(),
            d.phonetic_given.clone(),
            d.phonetic_family.clone(),
            d.nickname.clone(),
            d.org.clone(),
            d.title.clone(),
            birthday,
            d.note.clone(),
        ];
        for (list, count) in [(&d.numbers, phones), (&d.emails, emails)] {
            push_fields(&mut row, list, count);
        }
        push_fields(&mut row, &d.addresses, addresses);
        for i in 0..sites {
            row.push(d.websites.get(i).cloned().unwrap_or_default());
        }
        for i in 0..relations {
            match d.relations.get(i) {
                Some(r) => {
                    row.push(r.title());
                    row.push(r.name.clone());
                }
                None => row.extend([String::new(), String::new()]),
            }
        }
        push_row(&mut out, &row);
    }
    out
}

fn push_fields(row: &mut Vec<String>, list: &[DraftField], count: usize) {
    for i in 0..count {
        match list.get(i) {
            Some(f) => {
                let star = if f.primary { "* " } else { "" };
                row.push(format!("{star}{}", f.title()));
                row.push(f.value.clone());
            }
            None => {
                row.push(String::new());
                row.push(String::new());
            }
        }
    }
}

fn push_row(out: &mut String, cells: &[String]) {
    for (i, cell) in cells.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        if cell.contains([',', '"', '\n', '\r']) {
            out.push('"');
            out.push_str(&cell.replace('"', "\"\""));
            out.push('"');
        } else {
            out.push_str(cell);
        }
    }
    out.push_str("\r\n");
}

// ---------------------------------------------------------------- reading

/// Splits CSV text into rows of cells (RFC 4180: quoted commas, doubled
/// quotes and line breaks inside quotes). Rows with only empty cells are
/// dropped. Stops after the header and [`MAX_ROWS`] data rows.
fn read_rows(text: &str) -> Vec<Vec<String>> {
    let text = text.strip_prefix(BOM).unwrap_or(text);
    let mut rows: Vec<Vec<String>> = Vec::new();
    let mut row: Vec<String> = Vec::new();
    let mut cell = String::new();
    let mut quoted = false;
    let mut chars = text.chars().peekable();

    fn end_row(rows: &mut Vec<Vec<String>>, row: &mut Vec<String>, cell: &mut String) {
        row.push(std::mem::take(cell));
        let done = std::mem::take(row);
        if done.iter().any(|c| !c.trim().is_empty()) {
            rows.push(done);
        }
    }

    while let Some(c) = chars.next() {
        if quoted {
            match c {
                '"' if chars.peek() == Some(&'"') => {
                    chars.next();
                    cell.push('"');
                }
                '"' => quoted = false,
                other => cell.push(other),
            }
            continue;
        }
        match c {
            '"' if cell.is_empty() => quoted = true,
            ',' => row.push(std::mem::take(&mut cell)),
            '\r' => {
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                end_row(&mut rows, &mut row, &mut cell);
            }
            '\n' => end_row(&mut rows, &mut row, &mut cell),
            other => cell.push(other),
        }
        if rows.len() > MAX_ROWS {
            return rows;
        }
    }
    if !cell.is_empty() || !row.is_empty() {
        end_row(&mut rows, &mut row, &mut cell);
    }
    rows.truncate(MAX_ROWS + 1);
    rows
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Part {
    Label,
    Value,
}

/// What a header column holds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Col {
    Ignore,
    Given,
    Middle,
    Family,
    FullName,
    Nickname,
    PhoneticGiven,
    PhoneticFamily,
    Relation(usize, Part),
    Org,
    Title,
    Birthday,
    Notes,
    Phone(usize, Part),
    /// Outlook: a column per kind of number.
    PhoneOf(FieldKind),
    Email(usize, Part),
    /// Outlook: `E-mail Address`, `E-mail 2 Address`.
    EmailPlain,
    Address(usize, Part),
    Website,
}

fn classify(header: &str) -> Col {
    let h = header.trim().trim_start_matches('\u{FEFF}').to_lowercase();
    match h.as_str() {
        "first name" | "given name" => return Col::Given,
        "middle name" | "additional name" => return Col::Middle,
        "last name" | "family name" => return Col::Family,
        "name" | "display name" | "full name" => return Col::FullName,
        "nickname" | "nick name" => return Col::Nickname,
        "phonetic first name" | "phonetic given name" => return Col::PhoneticGiven,
        "phonetic last name" | "phonetic family name" => return Col::PhoneticFamily,
        "organization name" | "organization 1 - name" | "company" | "organization" => {
            return Col::Org;
        }
        "organization title" | "organization 1 - title" | "job title" | "title" => {
            return Col::Title;
        }
        "birthday" => return Col::Birthday,
        "notes" | "note" => return Col::Notes,
        "mobile phone" | "primary phone" | "mobile" => return Col::PhoneOf(FieldKind::Mobile),
        "home phone" | "home phone 2" => return Col::PhoneOf(FieldKind::Home),
        "business phone" | "business phone 2" | "company main phone" => {
            return Col::PhoneOf(FieldKind::Work);
        }
        "other phone" => return Col::PhoneOf(FieldKind::Other),
        "web page" | "website" => return Col::Website,
        _ => {}
    }
    if let Some((n, part)) = numbered(&h, "phone") {
        return part.map_or(Col::Ignore, |p| Col::Phone(n, p));
    }
    if let Some((n, part)) = numbered(&h, "e-mail").or_else(|| numbered(&h, "email")) {
        return part.map_or(Col::Ignore, |p| Col::Email(n, p));
    }
    if h.starts_with("e-mail") && h.ends_with(" address") {
        return Col::EmailPlain;
    }
    if let Some((n, part)) = numbered(&h, "relation") {
        return part.map_or(Col::Ignore, |p| Col::Relation(n, p));
    }
    if let Some((n, part)) = numbered(&h, "address") {
        return part.map_or(Col::Ignore, |p| Col::Address(n, p));
    }
    if let Some(rest) = h.strip_prefix("website ")
        && rest.ends_with(" - value")
    {
        return Col::Website;
    }
    Col::Ignore
}

/// `<prefix> N - Label|Value` as a number and the part (`None` for other parts).
fn numbered(h: &str, prefix: &str) -> Option<(usize, Option<Part>)> {
    let rest = h.strip_prefix(prefix)?.strip_prefix(' ')?;
    let (n, part) = rest.split_once(" - ")?;
    let n = n.trim().parse::<usize>().ok()?;
    Some((
        n,
        match part.trim() {
            "label" | "type" => Some(Part::Label),
            "value" | "formatted" => Some(Part::Value),
            _ => None,
        },
    ))
}

fn kind_of(label: &str, allowed: &[FieldKind]) -> (FieldKind, Option<String>) {
    let lower = label.trim().to_lowercase();
    let kind = match lower.as_str() {
        "" | "other" => FieldKind::Other,
        "mobile" | "cell" | "iphone" | "main" => FieldKind::Mobile,
        "home" => FieldKind::Home,
        "work" | "business" => FieldKind::Work,
        _ => return (FieldKind::Custom, Some(label.trim().to_owned())),
    };
    if kind == FieldKind::Mobile && !allowed.contains(&FieldKind::Mobile) {
        return (FieldKind::Other, None);
    }
    (kind, None)
}

fn field(value: &str, label: &str, allowed: &[FieldKind]) -> DraftField {
    let (label, primary) = match label.trim().strip_prefix('*') {
        Some(rest) => (rest.trim(), true),
        None => (label.trim(), false),
    };
    let (kind, custom) = kind_of(label, allowed);
    let mut f = DraftField::new(value.trim(), kind);
    f.label = custom;
    f.primary = primary;
    f
}

fn split_multi(cell: &str) -> Vec<&str> {
    cell.split(MULTI.trim()).map(str::trim).collect()
}

/// Reads a Google or Outlook contacts CSV.
pub fn parse(text: &str) -> Parsed {
    let rows = read_rows(text);
    let mut parsed = Parsed::default();
    let Some((header, data)) = rows.split_first() else {
        return parsed;
    };
    let cols: Vec<Col> = header.iter().map(|h| classify(h)).collect();
    for row in data.iter().take(MAX_ROWS) {
        match read_row(&cols, row).finish() {
            Ok(card) => parsed.contacts.push(card),
            Err(_) => parsed.skipped += 1,
        }
    }
    parsed
}

fn read_row(cols: &[Col], row: &[String]) -> ContactDraft {
    let mut d = ContactDraft::default();
    let mut middle = String::new();
    let mut full = String::new();
    // Numbered columns of one row: index -> (labels, values).
    let mut phones: BTreeMap<usize, (String, String)> = BTreeMap::new();
    let mut emails: BTreeMap<usize, (String, String)> = BTreeMap::new();
    let mut addresses: BTreeMap<usize, (String, String)> = BTreeMap::new();
    let mut relations: BTreeMap<usize, (String, String)> = BTreeMap::new();
    let mut plain_phones: Vec<DraftField> = Vec::new();
    let mut plain_emails: Vec<DraftField> = Vec::new();

    for (col, cell) in cols.iter().zip(row) {
        let cell = cell.trim();
        if cell.is_empty() {
            continue;
        }
        match *col {
            Col::Ignore => {}
            Col::Given => d.given = cell.to_owned(),
            Col::Middle => middle = cell.to_owned(),
            Col::Family => d.family = cell.to_owned(),
            Col::FullName => full = cell.to_owned(),
            Col::Nickname => d.nickname = cell.to_owned(),
            Col::PhoneticGiven => d.phonetic_given = cell.to_owned(),
            Col::PhoneticFamily => d.phonetic_family = cell.to_owned(),
            Col::Relation(n, part) => put(&mut relations, n, part, cell),
            Col::Org => d.org = cell.to_owned(),
            Col::Title => d.title = cell.to_owned(),
            Col::Birthday => {
                if let Some(date) = contact_dates::parse(cell) {
                    d.events
                        .push(DraftEvent::new(date.to_android(), EventKind::Birthday));
                }
            }
            Col::Notes => d.note = cell.to_owned(),
            Col::Phone(n, part) => put(&mut phones, n, part, cell),
            Col::Email(n, part) => put(&mut emails, n, part, cell),
            Col::PhoneOf(kind) => {
                for v in split_multi(cell).into_iter().filter(|v| !v.is_empty()) {
                    plain_phones.push(DraftField::new(v, kind));
                }
            }
            Col::EmailPlain => {
                for v in split_multi(cell).into_iter().filter(|v| !v.is_empty()) {
                    plain_emails.push(DraftField::new(v, FieldKind::Other));
                }
            }
            Col::Address(n, part) => put(&mut addresses, n, part, cell),
            Col::Website => {
                d.websites.extend(
                    split_multi(cell)
                        .into_iter()
                        .filter(|v| !v.is_empty())
                        .map(str::to_owned),
                );
            }
        }
    }

    if !middle.is_empty() {
        d.given = [d.given.as_str(), middle.as_str()]
            .iter()
            .filter(|s| !s.is_empty())
            .copied()
            .collect::<Vec<_>>()
            .join(" ");
    }
    if d.given.is_empty() && d.family.is_empty() && !full.is_empty() {
        match full.rsplit_once(char::is_whitespace) {
            Some((given, family)) => {
                d.given = given.trim().to_owned();
                d.family = family.trim().to_owned();
            }
            None => d.given = full,
        }
    }
    let kinds = &[FieldKind::Mobile, FieldKind::Home, FieldKind::Work];
    for (labels, values) in phones.into_values() {
        expand(&mut d.numbers, &labels, &values, kinds);
    }
    for (label, name) in relations.into_values() {
        if name.trim().is_empty() {
            continue;
        }
        let (kind, label) = match crate::vcard::relation_kind_of(&label) {
            Some(kind) => (kind, String::new()),
            None if label.trim().eq_ignore_ascii_case("other") => {
                (RelationKind::Custom, String::new())
            }
            None => (RelationKind::Custom, label.trim().to_owned()),
        };
        d.relations.push(DraftRelation {
            name: name.trim().to_owned(),
            kind,
            label,
        });
    }
    d.numbers.extend(plain_phones);
    for (labels, values) in emails.into_values() {
        expand(&mut d.emails, &labels, &values, &[]);
    }
    d.emails.extend(plain_emails);
    for (labels, values) in addresses.into_values() {
        expand(&mut d.addresses, &labels, &values, &[]);
    }
    d
}

/// Remembers a label or value cell of numbered column `n`.
fn put(map: &mut BTreeMap<usize, (String, String)>, n: usize, part: Part, cell: &str) {
    let entry = map.entry(n).or_default();
    match part {
        Part::Label => entry.0 = cell.to_owned(),
        Part::Value => entry.1 = cell.to_owned(),
    }
}

/// Pairs the ` ::: ` separated labels with the values of one column pair.
fn expand(out: &mut Vec<DraftField>, labels: &str, values: &str, allowed: &[FieldKind]) {
    let labels = split_multi(labels);
    for (i, value) in split_multi(values).into_iter().enumerate() {
        if value.is_empty() {
            continue;
        }
        let label = labels.get(i).or(labels.first()).copied().unwrap_or("");
        out.push(field(value, label, allowed));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn full() -> ContactDraft {
        let mut work = DraftField::new("+90 212 000 00 00", FieldKind::Work);
        work.primary = true;
        let mut custom = DraftField::new("ayse@example.com", FieldKind::Custom);
        custom.label = Some("Okul, \"eski\"".into());
        let mut d = ContactDraft {
            given: "Ayşe Nur".into(),
            family: "Yılmaz".into(),
            org: "Çiğdem & Oğul, Ltd.".into(),
            nickname: "Şişko".into(),
            phonetic_given: "Ay-she".into(),
            phonetic_family: "Yil-maz".into(),
            relations: vec![
                DraftRelation::new("Mehmet Yılmaz", RelationKind::Spouse),
                DraftRelation {
                    name: "Zeynep".into(),
                    kind: RelationKind::Custom,
                    label: "Godmother".into(),
                },
            ],
            title: "Müdür".into(),
            note: "Dedi ki: \"merhaba\"\nİkinci satır, virgüllü".into(),
            numbers: vec![DraftField::new("0555 111 22 33", FieldKind::Mobile), work],
            emails: vec![custom],
            addresses: vec![DraftField::new("Atatürk Cad. 5\nİstanbul", FieldKind::Home)],
            websites: vec!["https://a.com/x,y".into(), "b.org".into()],
            events: vec![DraftEvent::new("1990-03-07", EventKind::Birthday)],
            ..ContactDraft::default()
        };
        d.clean();
        d
    }

    #[test]
    fn round_trips_a_full_draft() {
        let d = full();
        let text = write(std::slice::from_ref(&d));
        assert!(text.starts_with(BOM));
        assert!(text.contains("\r\n"));
        assert!(text.contains("* Work"));
        let parsed = parse(&text);
        assert_eq!(parsed.skipped, 0);
        assert_eq!(parsed.contacts, vec![d]);
    }

    #[test]
    fn reads_relation_columns() {
        let text = "First Name,Relation 1 - Label,Relation 1 - Value,Relation 2 - Label,\
Relation 2 - Value\nAli,Spouse,Ayşe,Neighbour,Kemal\n";
        let d = &parse(text).contacts[0];
        assert_eq!(
            d.relations[0],
            DraftRelation::new("Ayşe", RelationKind::Spouse)
        );
        assert_eq!(d.relations[1].label, "Neighbour");
        assert_eq!(d.relations[1].kind, RelationKind::Custom);
    }

    #[test]
    fn exports_the_google_columns() {
        let text = write(&[full()]);
        let header = text.trim_start_matches(BOM).lines().next().expect("header");
        assert_eq!(
            header,
            "First Name,Middle Name,Last Name,Phonetic First Name,Phonetic Last Name,Nickname,Organization Name,Organization Title,\
Birthday,Notes,Phone 1 - Label,Phone 1 - Value,Phone 2 - Label,Phone 2 - Value,\
E-mail 1 - Label,E-mail 1 - Value,Address 1 - Label,Address 1 - Formatted,Website 1 - Value,\
Website 2 - Value,Relation 1 - Label,Relation 1 - Value,Relation 2 - Label,Relation 2 - Value"
        );
        assert!(text.contains("\"Dedi ki: \"\"merhaba\"\"\nİkinci satır, virgüllü\""));
        assert!(text.contains("1990-03-07"));
    }

    #[test]
    fn export_caps_numbered_columns_and_handles_nothing() {
        let d = ContactDraft {
            given: "A".into(),
            websites: (0..15).map(|i| format!("s{i}.com")).collect(),
            ..ContactDraft::default()
        };
        let text = write(&[d]);
        assert!(text.contains("Website 10 - Value"));
        assert!(!text.contains("Website 11"));
        let empty = write(&[]);
        assert!(empty.starts_with(BOM));
        assert!(!empty.contains("Phone 1"));
    }

    #[test]
    fn reads_a_google_file() {
        let text = "\u{FEFF}Name,Given Name,Family Name,Nickname,Organization 1 - Name,Birthday,\
Phone 1 - Label,Phone 1 - Value,E-mail 1 - Value,Website 1 - Value\r\n\
Ada Lovelace,Ada,Lovelace,Countess,Analytical,1815-12-10,* Mobile ::: Home,\
+905551112233 ::: 02120000000,ada@example.com,https://ada.dev\r\n";
        let parsed = parse(text);
        let c = &parsed.contacts[0];
        assert_eq!(c.display_name(), "Ada Lovelace");
        assert_eq!(c.nickname, "Countess");
        assert_eq!(c.org, "Analytical");
        assert_eq!(
            c.events,
            vec![DraftEvent::new("1815-12-10", EventKind::Birthday)]
        );
        assert_eq!(c.numbers.len(), 2);
        assert_eq!(c.numbers[0].kind, FieldKind::Mobile);
        assert!(c.numbers[0].primary);
        assert_eq!(c.numbers[1].kind, FieldKind::Home);
        assert!(!c.numbers[1].primary);
        assert_eq!(c.emails[0].value, "ada@example.com");
        assert_eq!(c.websites, vec!["https://ada.dev"]);
    }

    #[test]
    fn full_name_column_is_a_fallback() {
        let parsed = parse("Name,Phone 1 - Value\nAda Byron Lovelace,555\nCher,556\n");
        assert_eq!(parsed.contacts[0].given, "Ada Byron");
        assert_eq!(parsed.contacts[0].family, "Lovelace");
        assert_eq!(parsed.contacts[1].given, "Cher");
    }

    #[test]
    fn reads_an_outlook_file() {
        let text = "First Name,Middle Name,Last Name,Company,Job Title,E-mail Address,\
E-mail 2 Address,Mobile Phone,Home Phone,Business Phone,Notes,Birthday\r\n\
Grace,Brewster,Hopper,Navy,Admiral,grace@navy.mil,gh@home.org,555 100,555 200,555 300,\"a,b\",12/9/1906\r\n\
Alan,,Turing,,,,,,,,,1912-06-23\r\n";
        let parsed = parse(text);
        // Alan has no number or email but a name: kept.
        assert_eq!(parsed.contacts.len(), 2);
        let g = &parsed.contacts[0];
        assert_eq!(g.given, "Grace Brewster");
        assert_eq!(g.org, "Navy");
        assert_eq!(g.title, "Admiral");
        assert_eq!(g.emails.len(), 2);
        let kinds: Vec<_> = g.numbers.iter().map(|n| n.kind).collect();
        assert_eq!(kinds, [FieldKind::Mobile, FieldKind::Home, FieldKind::Work]);
        assert_eq!(g.note, "a,b");
        // A US style date does not parse and is dropped; the card stays.
        assert!(g.events.is_empty());
        assert_eq!(parsed.contacts[1].events.len(), 1);
    }

    #[test]
    fn headers_are_case_insensitive() {
        let parsed = parse("FIRST NAME,last name,PHONE 1 - VALUE\nAda,Lovelace,555\n");
        assert_eq!(parsed.contacts[0].family, "Lovelace");
        assert_eq!(parsed.contacts[0].numbers[0].value, "555");
    }

    #[test]
    fn quotes_commas_and_newlines_in_cells() {
        let parsed = parse("First Name,Notes\r\n\"A, B\",\"say \"\"hi\"\"\r\nbye\"\r\n");
        assert_eq!(parsed.contacts[0].given, "A, B");
        assert_eq!(parsed.contacts[0].note, "say \"hi\"\r\nbye");
    }

    #[test]
    fn counts_skipped_rows_and_ignores_blank_ones() {
        let text = "First Name,Last Name,Phone 1 - Value\n\
Ada,Lovelace,555\n\
,,\n\
,,999\n\
Bob,Bad,no digits\n\
\n\
Eve,Ok,\n";
        let parsed = parse(text);
        assert_eq!(parsed.contacts.len(), 2);
        assert_eq!(parsed.skipped, 2);
    }

    #[test]
    fn row_limit_and_empty_input() {
        let mut text = String::from("First Name\n");
        for i in 0..MAX_ROWS + 20 {
            text.push_str(&format!("N{i}\n"));
        }
        assert_eq!(parse(&text).contacts.len(), MAX_ROWS);
        assert_eq!(parse(""), Parsed::default());
        assert_eq!(parse("First Name\n"), Parsed::default());
        assert_eq!(parse("Foo,Bar\n1,2\n").contacts.len(), 0);
    }

    #[test]
    fn detects_csv_files() {
        assert!(looks_like_csv(Some("text/csv"), "x", b""));
        assert!(looks_like_csv(
            Some("Text/Comma-Separated-Values; charset=utf-8"),
            "x",
            b""
        ));
        assert!(looks_like_csv(None, "Contacts.CSV", b""));
        assert!(looks_like_csv(
            None,
            "x",
            b"\xEF\xBB\xBFFirst Name,Last Name\r\n"
        ));
        assert!(looks_like_csv(None, "x", b"Given Name,Family Name\n"));
        assert!(!looks_like_csv(None, "x", b"First Name only\n"));
        assert!(!looks_like_csv(
            Some("text/plain"),
            "a.txt",
            b"hello, world"
        ));
        assert!(!looks_like_csv(None, "a.vcf", b"BEGIN:VCARD"));
    }

    #[test]
    fn names_the_export() {
        assert_eq!(
            file_name(12, (2026, 3, 7)),
            "rovsti-contacts-12-2026-03-07.csv"
        );
    }
}
