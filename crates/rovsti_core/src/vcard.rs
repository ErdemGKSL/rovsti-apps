//! vCard import and export for Rovsti Contacts.
//!
//! Reads vCard 2.1, 3.0 and 4.0 (what phones, Google Contacts and iCloud
//! produce) into [`ContactDraft`]s and writes vCard 3.0, which every address
//! book accepts. Only what the app edits is carried: name, organisation,
//! nickname (`NICKNAME`, the first of a comma list), job title (`TITLE`),
//! numbers, emails, websites (`URL`), the note, the birthday and anniversary (`BDAY`,
//! `ANNIVERSARY`, `X-ANNIVERSARY`) and postal addresses (`ADR`). Photos and
//! other properties are skipped on import and not written; a date that does
//! not parse is dropped without failing the card.
//!
//! Pure text in, pure values out: the file itself is read and written by the
//! platform (Storage Access Framework), and the text is already UTF-8.

use crate::contact_dates;
use crate::contact_edit::{ContactDraft, DraftEvent, DraftField, EventKind, FieldKind};

/// Longest content line before folding, in bytes (RFC 6350 says 75 octets).
const FOLD_AT: usize = 75;

/// What an import found.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Parsed {
    /// Cards that hold a usable name and a name.
    pub contacts: Vec<ContactDraft>,
    /// Cards that were read but are not usable (no name).
    pub skipped: usize,
}

/// Reads every `BEGIN:VCARD ... END:VCARD` block of `text`.
pub fn parse(text: &str) -> Parsed {
    let lines = unfold(text);
    let mut parsed = Parsed::default();
    let mut current: Option<Builder> = None;
    for line in &lines {
        let Some(prop) = Property::parse(line) else {
            continue;
        };
        match (prop.name.as_str(), current.as_mut()) {
            ("BEGIN", _) if prop.value.eq_ignore_ascii_case("VCARD") => {
                current = Some(Builder::default());
            }
            ("END", Some(_)) if prop.value.eq_ignore_ascii_case("VCARD") => {
                if let Some(builder) = current.take() {
                    match builder.finish() {
                        Ok(card) => parsed.contacts.push(card),
                        Err(_) => parsed.skipped += 1,
                    }
                }
            }
            (_, Some(builder)) => builder.apply(&prop),
            _ => {}
        }
    }
    parsed
}

/// A card being read: the draft plus the `FN`, which is used only when the
/// card has no structured `N`.
#[derive(Default)]
struct Builder {
    draft: ContactDraft,
    full_name: Option<String>,
}

impl Builder {
    fn apply(&mut self, prop: &Property) {
        let card = &mut self.draft;
        match prop.name.as_str() {
            "N" => {
                let parts = split_all_unescaped(&prop.value, ';');
                let part = |i: usize| parts.get(i).map(|p| unescape(p)).unwrap_or_default();
                card.family = part(0);
                card.given = [part(1), part(2)]
                    .into_iter()
                    .map(|s| s.trim().to_owned())
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(" ");
            }
            "FN" => self.full_name = Some(unescape(&prop.value)),
            "ORG" => {
                card.org = split_all_unescaped(&prop.value, ';')
                    .first()
                    .map(|p| unescape(p))
                    .unwrap_or_default();
            }
            "NICKNAME" => {
                card.nickname = split_all_unescaped(&prop.value, ',')
                    .iter()
                    .map(|n| unescape(n).trim().to_owned())
                    .find(|n| !n.is_empty())
                    .unwrap_or_default();
            }
            "TITLE" => card.title = unescape(&prop.value),
            "URL" => {
                let url = unescape(&prop.value);
                if !url.trim().is_empty() {
                    card.websites.push(url);
                }
            }
            "TEL" => {
                let value = prop.value.trim();
                let value = value.strip_prefix("tel:").unwrap_or(value);
                card.numbers.push(DraftField::new(value, number_kind(prop)));
            }
            "EMAIL" => card
                .emails
                .push(DraftField::new(unescape(&prop.value), email_kind(prop))),
            "NOTE" => card.note = unescape(&prop.value),
            "BDAY" => push_event(card, &prop.value, EventKind::Birthday),
            "ANNIVERSARY" | "X-ANNIVERSARY" => {
                push_event(card, &prop.value, EventKind::Anniversary);
            }
            "ADR" => {
                let text = adr_text(&prop.value);
                if !text.is_empty() {
                    card.addresses
                        .push(DraftField::new(text, address_kind(prop)));
                }
            }
            _ => {}
        }
    }

    fn finish(mut self) -> Result<ContactDraft, crate::contact_edit::DraftError> {
        let card = &mut self.draft;
        if card.given.trim().is_empty()
            && card.family.trim().is_empty()
            && let Some(full) = self.full_name.as_deref().map(str::trim)
        {
            match full.rsplit_once(char::is_whitespace) {
                Some((given, family)) => {
                    card.given = given.trim().to_owned();
                    card.family = family.trim().to_owned();
                }
                None => card.given = full.to_owned(),
            }
        }
        self.draft.finish()
    }
}

/// One content line: `[group.]NAME[;PARAM[=VALUE]]*:value`.
struct Property {
    name: String,
    params: Vec<(String, String)>,
    value: String,
}

impl Property {
    fn parse(line: &str) -> Option<Self> {
        let (head, value) = split_unquoted(line, ':')?;
        let mut parts = split_all_unquoted(head, ';').into_iter();
        let full_name = parts.next()?;
        let name = full_name
            .rsplit_once('.')
            .map_or(full_name.as_str(), |(_, n)| n)
            .trim()
            .to_ascii_uppercase();
        if name.is_empty() {
            return None;
        }
        let params = parts
            .map(|p| match p.split_once('=') {
                Some((k, v)) => (
                    k.trim().to_ascii_uppercase(),
                    v.trim().trim_matches('"').to_owned(),
                ),
                // vCard 2.1: a bare parameter is a TYPE (`TEL;CELL:`).
                None => ("TYPE".to_owned(), p.trim().to_owned()),
            })
            .collect();
        let mut prop = Self {
            name,
            params,
            value: value.to_owned(),
        };
        if prop.quoted_printable() {
            prop.value = decode_quoted_printable(&prop.value);
        }
        Some(prop)
    }

    fn quoted_printable(&self) -> bool {
        self.params.iter().any(|(k, v)| {
            (k == "ENCODING" && v.eq_ignore_ascii_case("QUOTED-PRINTABLE"))
                || (k == "TYPE" && v.eq_ignore_ascii_case("QUOTED-PRINTABLE"))
        })
    }

    /// Every TYPE token, upper case (`TYPE=work,voice` gives WORK and VOICE).
    fn types(&self) -> Vec<String> {
        self.params
            .iter()
            .filter(|(k, _)| k == "TYPE")
            .flat_map(|(_, v)| v.split(','))
            .map(|t| t.trim().to_ascii_uppercase())
            .filter(|t| !t.is_empty())
            .collect()
    }
}

/// Adds a dated event; a date that does not parse is dropped.
fn push_event(card: &mut ContactDraft, value: &str, kind: EventKind) {
    if let Some(date) = contact_dates::parse(&unescape(value)) {
        card.events.push(DraftEvent::new(date.to_android(), kind));
    }
}

/// The 7 `ADR` components (PO box, extended, street, city, region, postcode,
/// country) as "street\ncity region postcode\ncountry", empties skipped.
fn adr_text(value: &str) -> String {
    let parts: Vec<String> = split_all_unescaped(value, ';')
        .iter()
        .map(|p| unescape(p).trim().to_owned())
        .collect();
    let part = |i: usize| parts.get(i).map_or("", String::as_str);
    let street = [part(0), part(1), part(2)]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    let town = [part(3), part(4), part(5)]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    [street.as_str(), town.as_str(), part(6)]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("\n")
}

fn address_kind(prop: &Property) -> FieldKind {
    let types = prop.types();
    let has = |t: &str| types.iter().any(|x| x == t);
    if has("HOME") {
        FieldKind::Home
    } else if has("WORK") {
        FieldKind::Work
    } else {
        FieldKind::Other
    }
}

fn number_kind(prop: &Property) -> FieldKind {
    let types = prop.types();
    let has = |t: &str| types.iter().any(|x| x == t);
    if has("CELL") || has("MOBILE") || has("IPHONE") {
        FieldKind::Mobile
    } else if has("HOME") {
        FieldKind::Home
    } else if has("WORK") {
        FieldKind::Work
    } else {
        FieldKind::Other
    }
}

fn email_kind(prop: &Property) -> FieldKind {
    let types = prop.types();
    let has = |t: &str| types.iter().any(|x| x == t);
    if has("HOME") {
        FieldKind::Home
    } else if has("WORK") {
        FieldKind::Work
    } else {
        FieldKind::Other
    }
}

/// Joins folded lines (RFC 6350 section 3.2) and the soft line breaks of
/// quoted-printable values, and drops blank lines.
fn unfold(text: &str) -> Vec<String> {
    let mut lines: Vec<String> = Vec::new();
    for raw in text.lines() {
        let raw = raw.trim_end_matches('\r');
        if raw.starts_with([' ', '\t']) && !lines.is_empty() {
            if let Some(last) = lines.last_mut() {
                last.push_str(&raw[1..]);
            }
            continue;
        }
        // A quoted-printable value that ends in `=` continues on the next line.
        let continues = lines.last().is_some_and(|last| {
            last.ends_with('=') && last.to_ascii_uppercase().contains("QUOTED-PRINTABLE")
        });
        if continues {
            if let Some(last) = lines.last_mut() {
                last.pop();
                last.push_str(raw);
            }
            continue;
        }
        if !raw.trim().is_empty() {
            lines.push(raw.to_owned());
        }
    }
    lines
}

/// Splits at the first `sep` that is not inside double quotes.
fn split_unquoted(s: &str, sep: char) -> Option<(&str, &str)> {
    let mut quoted = false;
    for (i, c) in s.char_indices() {
        if c == '"' {
            quoted = !quoted;
        } else if c == sep && !quoted {
            return Some((&s[..i], &s[i + c.len_utf8()..]));
        }
    }
    None
}

fn split_all_unquoted(s: &str, sep: char) -> Vec<String> {
    let mut out = Vec::new();
    let mut quoted = false;
    let mut start = 0;
    for (i, c) in s.char_indices() {
        if c == '"' {
            quoted = !quoted;
        } else if c == sep && !quoted {
            out.push(s[start..i].to_owned());
            start = i + c.len_utf8();
        }
    }
    out.push(s[start..].to_owned());
    out
}

/// Splits at every `sep` that is not escaped with a backslash.
fn split_all_unescaped(s: &str, sep: char) -> Vec<String> {
    let mut out = vec![String::new()];
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            let escaped = chars.next();
            let target = out.last_mut().expect("never empty");
            target.push('\\');
            target.extend(escaped);
        } else if c == sep {
            out.push(String::new());
        } else {
            out.last_mut().expect("never empty").push(c);
        }
    }
    out
}

/// `\n`, `\N`, `\,`, `\;` and `\\` back to the characters they stand for.
fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n' | 'N') => out.push('\n'),
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    out
}

fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            ';' => out.push_str("\\;"),
            ',' => out.push_str("\\,"),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            other => out.push(other),
        }
    }
    out
}

/// Decodes `=C3=A7` style bytes as UTF-8; invalid sequences keep the raw text.
fn decode_quoted_printable(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'='
            && i + 2 < bytes.len()
            && let (Some(hi), Some(lo)) = (hex(bytes[i + 1]), hex(bytes[i + 2]))
        {
            out.push(hi * 16 + lo);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).unwrap_or_else(|_| s.to_owned())
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// Writes `contacts` as vCard 3.0, CRLF line ends, folded at 75 bytes.
pub fn write(contacts: &[ContactDraft]) -> String {
    let mut out = String::new();
    for contact in contacts {
        write_card(contact, &mut out);
    }
    out
}

fn write_card(contact: &ContactDraft, out: &mut String) {
    push_line(out, "BEGIN:VCARD");
    push_line(out, "VERSION:3.0");
    push_line(
        out,
        &format!(
            "N:{};{};;;",
            escape(&contact.family),
            escape(&contact.given)
        ),
    );
    push_line(out, &format!("FN:{}", escape(&contact.display_name())));
    if !contact.org.is_empty() {
        push_line(out, &format!("ORG:{}", escape(&contact.org)));
    }
    if !contact.nickname.is_empty() {
        push_line(out, &format!("NICKNAME:{}", escape(&contact.nickname)));
    }
    if !contact.title.is_empty() {
        push_line(out, &format!("TITLE:{}", escape(&contact.title)));
    }
    for site in &contact.websites {
        push_line(out, &format!("URL:{}", escape(site)));
    }
    for number in &contact.numbers {
        push_line(
            out,
            &format!("TEL;TYPE={}:{}", tel_type(number), escape(&number.value)),
        );
    }
    for email in &contact.emails {
        push_line(
            out,
            &format!(
                "EMAIL;TYPE=INTERNET,{}:{}",
                match email.kind {
                    FieldKind::Home => "HOME",
                    FieldKind::Work => "WORK",
                    _ => "OTHER",
                },
                escape(&email.value)
            ),
        );
    }
    if !contact.note.is_empty() {
        push_line(out, &format!("NOTE:{}", escape(&contact.note)));
    }
    let dated = |kind: EventKind| {
        contact
            .events
            .iter()
            .filter(|e| e.kind == kind)
            .find_map(|e| contact_dates::parse(&e.date))
    };
    if let Some(date) = dated(EventKind::Birthday) {
        push_line(out, &format!("BDAY:{}", date.to_android()));
    }
    if let Some(date) = dated(EventKind::Anniversary) {
        push_line(out, &format!("X-ANNIVERSARY:{}", date.to_android()));
    }
    for address in &contact.addresses {
        let kind = match address.kind {
            FieldKind::Home => ";TYPE=HOME",
            FieldKind::Work => ";TYPE=WORK",
            _ => "",
        };
        // The whole text sits in the street component, so it reads back as is.
        push_line(out, &format!("ADR{kind}:;;{};;;;", escape(&address.value)));
    }
    push_line(out, "END:VCARD");
}

/// File name for sharing `drafts`: the contact's own name for one,
/// `rovsti-contacts-<n>-YYYY-MM-DD.vcf` for several.
pub fn export_name(drafts: &[ContactDraft], today: (i32, u8, u8)) -> String {
    match drafts {
        [one] => crate::shared_card::vcf_file_name(&one.display_name()),
        _ => format!(
            "rovsti-contacts-{}-{:04}-{:02}-{:02}.vcf",
            drafts.len(),
            today.0,
            today.1,
            today.2
        ),
    }
}

fn tel_type(number: &DraftField) -> &'static str {
    match number.kind {
        FieldKind::Mobile => "CELL",
        FieldKind::Home => "HOME",
        FieldKind::Work => "WORK",
        FieldKind::Custom | FieldKind::Other => "VOICE",
    }
}

/// Appends `line` folded at [`FOLD_AT`] bytes without cutting a character.
fn push_line(out: &mut String, line: &str) {
    let mut rest = line;
    let mut first = true;
    while !rest.is_empty() {
        let room = if first { FOLD_AT } else { FOLD_AT - 1 };
        let mut cut = rest.len().min(room);
        while !rest.is_char_boundary(cut) {
            cut -= 1;
        }
        if !first {
            out.push(' ');
        }
        out.push_str(&rest[..cut]);
        out.push_str("\r\n");
        rest = &rest[cut..];
        first = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SIMPLE: &str = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Yılmaz;Ayşe;Nur;;\r\nFN:Ayşe Nur Yılmaz\r\n\
ORG:Rovsti;Dev\r\nTEL;TYPE=CELL,VOICE:0555 111 22 33\r\nTEL;TYPE=WORK:+90 212 000 00 00\r\n\
EMAIL;TYPE=INTERNET,HOME:ayse@example.com\r\nNOTE:Line one\\nLine\\, two\r\nEND:VCARD\r\n";

    #[test]
    fn reads_a_version_three_card() {
        let parsed = parse(SIMPLE);
        assert_eq!(parsed.skipped, 0);
        let card = &parsed.contacts[0];
        assert_eq!(card.family, "Yılmaz");
        assert_eq!(card.given, "Ayşe Nur");
        assert_eq!(card.org, "Rovsti");
        assert_eq!(card.numbers[0].kind, FieldKind::Mobile);
        assert_eq!(card.numbers[1].kind, FieldKind::Work);
        assert_eq!(card.emails[0].kind, FieldKind::Home);
        assert_eq!(card.note, "Line one\nLine, two");
    }

    #[test]
    fn nickname_title_and_urls_round_trip() {
        let mut c = ContactDraft {
            given: "Ayşe".into(),
            nickname: "Ay, o".into(),
            title: "Head; of Things".into(),
            websites: vec!["https://a.com/x,y".into(), "b.org".into()],
            ..ContactDraft::default()
        };
        c.clean();
        let text = write(std::slice::from_ref(&c));
        assert!(text.contains("NICKNAME:Ay\\, o\r\n"));
        assert!(text.contains("TITLE:Head\\; of Things\r\n"));
        assert_eq!(parse(&text).contacts, vec![c]);
    }

    #[test]
    fn nickname_reads_the_first_of_a_list() {
        let text = "BEGIN:VCARD\nFN:Anne\nNICKNAME:,Annie,Ann\nTITLE:Boss\nURL;TYPE=WORK:http://x.com\nURL:\nEND:VCARD";
        let card = &parse(text).contacts[0];
        assert_eq!(card.nickname, "Annie");
        assert_eq!(card.title, "Boss");
        assert_eq!(card.websites, vec!["http://x.com"]);
    }

    #[test]
    fn fn_alone_is_split_on_the_last_space() {
        let text = "BEGIN:VCARD\nVERSION:4.0\nFN:Ayşe Nur Yılmaz\nTEL;TYPE=\"cell\":+905551112233\nEND:VCARD\n";
        let card = &parse(text).contacts[0];
        assert_eq!(card.given, "Ayşe Nur");
        assert_eq!(card.family, "Yılmaz");
        assert_eq!(card.note, "");
        assert_eq!(card.numbers[0].kind, FieldKind::Mobile);
    }

    #[test]
    fn a_single_word_fn_is_the_given_name() {
        let text = "BEGIN:VCARD\nFN:Anne\nTEL:5551112233\nEND:VCARD";
        let card = &parse(text).contacts[0];
        assert_eq!(card.given, "Anne");
        assert_eq!(card.family, "");
    }

    #[test]
    fn a_note_does_not_leak_the_fn_marker() {
        let text = "BEGIN:VCARD\nFN:Anne Can\nNOTE:Hello\nTEL:5551112233\nEND:VCARD";
        let card = &parse(text).contacts[0];
        assert_eq!(card.note, "Hello");
    }

    #[test]
    fn cards_without_a_name_are_skipped() {
        let text = "BEGIN:VCARD\nFN:No Number\nEND:VCARD\n\
BEGIN:VCARD\nTEL:5551112233\nEND:VCARD\nBEGIN:VCARD\nFN:Ok\nTEL:5551112233\nEND:VCARD";
        let parsed = parse(text);
        assert_eq!(parsed.contacts.len(), 2);
        assert_eq!(parsed.skipped, 1);
    }

    #[test]
    fn reads_version_two_one_bare_types_and_quoted_printable() {
        let text = "BEGIN:VCARD\r\nVERSION:2.1\r\n\
N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Y=C4=B1lmaz;A=C5=9Fk=\r\n=C4=B1n;;;\r\n\
TEL;CELL;PREF:5551112233\r\nEND:VCARD\r\n";
        let card = &parse(text).contacts[0];
        assert_eq!(card.family, "Yılmaz");
        assert_eq!(card.given, "Aşkın");
        assert_eq!(card.numbers[0].kind, FieldKind::Mobile);
    }

    #[test]
    fn unfolds_continuation_lines() {
        let text =
            "BEGIN:VCARD\r\nFN:Ayşe\r\nTEL:555\r\n 1112233\r\nNOTE:one\r\n\ttwo\r\nEND:VCARD\r\n";
        let card = &parse(text).contacts[0];
        assert_eq!(card.numbers[0].value, "5551112233");
        assert_eq!(card.note, "onetwo");
    }

    #[test]
    fn groups_prefixes_and_other_properties_are_tolerated() {
        let text = "BEGIN:VCARD\nVERSION:3.0\nitem1.TEL:5551112233\nitem1.X-ABLabel:Mine\n\
PHOTO;ENCODING=b;TYPE=JPEG:AAAA\nBDAY:1990-01-01\nFN:Ayşe Can\nEND:VCARD\n";
        let parsed = parse(text);
        assert_eq!(parsed.contacts.len(), 1);
        assert_eq!(parsed.contacts[0].numbers[0].value, "5551112233");
        // The birthday is now read; the photo is still skipped.
        assert_eq!(
            parsed.contacts[0].events,
            vec![DraftEvent::new("1990-01-01", EventKind::Birthday)]
        );
    }

    #[test]
    fn reads_several_cards_and_ignores_text_outside_them() {
        let text = "junk\nBEGIN:VCARD\nFN:A B\nTEL:111\nEND:VCARD\nmore junk\n\
BEGIN:VCARD\nFN:C D\nTEL:222\nEND:VCARD\n";
        assert_eq!(parse(text).contacts.len(), 2);
    }

    #[test]
    fn writes_and_reads_back_the_same_contact() {
        let original = parse(SIMPLE).contacts;
        let text = write(&original);
        assert!(text.starts_with("BEGIN:VCARD\r\nVERSION:3.0\r\n"));
        let again = parse(&text);
        assert_eq!(again.contacts, original);
        assert_eq!(again.skipped, 0);
    }

    #[test]
    fn written_lines_are_folded_and_cut_on_character_boundaries() {
        let mut contact = ContactDraft {
            given: "Ayşe".into(),
            note: "ş".repeat(120),
            numbers: vec![DraftField::new("5551112233", FieldKind::Mobile)],
            ..ContactDraft::default()
        };
        contact.clean();
        let text = write(std::slice::from_ref(&contact));
        assert!(text.lines().all(|l| l.len() <= FOLD_AT));
        assert_eq!(parse(&text).contacts[0].note, contact.note);
    }

    #[test]
    fn special_characters_survive_a_round_trip() {
        let mut contact = ContactDraft {
            given: "A;B,C".into(),
            family: "D\\E".into(),
            note: "x\ny".into(),
            numbers: vec![DraftField::new("5551112233", FieldKind::Home)],
            ..ContactDraft::default()
        };
        contact.clean();
        let again = parse(&write(std::slice::from_ref(&contact)));
        assert_eq!(again.contacts[0].given, "A;B,C");
        assert_eq!(again.contacts[0].family, "D\\E");
        assert_eq!(again.contacts[0].note, "x\ny");
    }

    #[test]
    fn empty_and_garbage_input_gives_nothing() {
        assert_eq!(parse(""), Parsed::default());
        assert_eq!(parse("hello\nworld"), Parsed::default());
    }

    #[test]
    fn an_unfinished_card_is_dropped() {
        assert!(parse("BEGIN:VCARD\nFN:A B\nTEL:111\n").contacts.is_empty());
    }

    #[test]
    fn an_email_only_card_imports() {
        let parsed = parse("BEGIN:VCARD\nVERSION:3.0\nFN:A B\nEMAIL:a@b.com\nEND:VCARD\n");
        assert_eq!(parsed.contacts.len(), 1);
        assert!(parsed.contacts[0].numbers.is_empty());
    }

    fn round_trip(card: ContactDraft) -> ContactDraft {
        let mut card = card;
        card.clean();
        let parsed = parse(&write(std::slice::from_ref(&card)));
        assert_eq!(parsed.skipped, 0);
        assert_eq!(parsed.contacts[0], card);
        parsed.contacts.into_iter().next().expect("one card")
    }

    fn named() -> ContactDraft {
        ContactDraft {
            given: "Ayşe".into(),
            numbers: vec![DraftField::new("5551112233", FieldKind::Mobile)],
            ..ContactDraft::default()
        }
    }

    #[test]
    fn birthday_with_and_without_year_round_trips() {
        let mut a = named();
        a.events = vec![DraftEvent::new("1994-05-12", EventKind::Birthday)];
        round_trip(a);
        let mut b = named();
        b.events = vec![DraftEvent::new("--05-12", EventKind::Birthday)];
        let text = write(std::slice::from_ref(&b));
        assert!(text.contains("BDAY:--05-12\r\n"));
        round_trip(b);
    }

    #[test]
    fn anniversary_round_trips_as_x_anniversary() {
        let mut a = named();
        a.events = vec![
            DraftEvent::new("1994-05-12", EventKind::Birthday),
            DraftEvent::new("2015-06-20", EventKind::Anniversary),
        ];
        assert!(write(std::slice::from_ref(&a)).contains("X-ANNIVERSARY:2015-06-20\r\n"));
        round_trip(a);
    }

    #[test]
    fn addresses_with_escapes_round_trip() {
        let mut a = named();
        a.addresses = vec![
            DraftField::new(
                "12 Main St, Apt 4; Floor 2\nParis 75001\nFrance",
                FieldKind::Home,
            ),
            DraftField::new("1 Back\\slash Rd", FieldKind::Work),
            DraftField::new("PO Box 9", FieldKind::Other),
        ];
        let text = write(std::slice::from_ref(&a));
        assert!(text.contains("ADR;TYPE=HOME:;;12 Main St\\, Apt 4\\; Floor 2\\nParis"));
        round_trip(a);
    }

    #[test]
    fn adr_components_are_joined() {
        let text = "BEGIN:VCARD\nFN:A B\nTEL:1\n\
ADR;TYPE=WORK:;;1 Infinite Loop;Cupertino;CA;95014;USA\n\
ADR;TYPE=HOME:;;;Berlin;;;Germany\nADR:;;;;;;\nEND:VCARD";
        let card = &parse(text).contacts[0];
        assert_eq!(card.addresses.len(), 2);
        assert_eq!(
            card.addresses[0].value,
            "1 Infinite Loop\nCupertino CA 95014\nUSA"
        );
        assert_eq!(card.addresses[0].kind, FieldKind::Work);
        assert_eq!(card.addresses[1].value, "Berlin\nGermany");
        assert_eq!(card.addresses[1].kind, FieldKind::Home);
    }

    #[test]
    fn reads_an_icloud_version_three_card() {
        let text = "BEGIN:VCARD\r\nVERSION:3.0\r\nPRODID:-//Apple Inc.//iPhone OS 17.0//EN\r\n\
N:Doe;Jane;;;\r\nFN:Jane Doe\r\nitem1.ADR;type=HOME;type=pref:;;1 Apple Park Way;Cupertino;CA;95014;United States\r\n\
item1.X-ABADR:us\r\nBDAY:1994-05-12\r\nitem2.X-ABDATE;type=pref:2015-06-20\r\nitem2.X-ABLabel:_$!<Anniversary>!$_\r\n\
X-ANNIVERSARY;VALUE=date:2015-06-20\r\nTEL;type=CELL;type=VOICE;type=pref:+1 555 010 0000\r\nEND:VCARD\r\n";
        let card = &parse(text).contacts[0];
        assert_eq!(card.family, "Doe");
        assert_eq!(
            card.events,
            vec![
                DraftEvent::new("1994-05-12", EventKind::Birthday),
                DraftEvent::new("2015-06-20", EventKind::Anniversary),
            ]
        );
        assert_eq!(
            card.addresses[0].value,
            "1 Apple Park Way\nCupertino CA 95014\nUnited States"
        );
        assert_eq!(card.addresses[0].kind, FieldKind::Home);
    }

    #[test]
    fn reads_a_google_version_four_card() {
        let text = "BEGIN:VCARD\r\nVERSION:4.0\r\nN:Doe;John;;;\r\nFN:John Doe\r\n\
BDAY:19940512\r\nANNIVERSARY:20150620\r\n\
ADR;TYPE=work:;;5 Main St\\, Suite 2;Springfield;IL;62701;USA\r\n\
TEL;TYPE=cell:+15550100\r\nEND:VCARD\r\n\
BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Sam Lee\r\nBDAY;VALUE=date:--0229\r\nTEL:1\r\nEND:VCARD\r\n";
        let parsed = parse(text);
        let john = &parsed.contacts[0];
        assert_eq!(
            john.events,
            vec![
                DraftEvent::new("1994-05-12", EventKind::Birthday),
                DraftEvent::new("2015-06-20", EventKind::Anniversary),
            ]
        );
        assert_eq!(
            john.addresses[0].value,
            "5 Main St, Suite 2\nSpringfield IL 62701\nUSA"
        );
        assert_eq!(john.addresses[0].kind, FieldKind::Work);
        assert_eq!(
            parsed.contacts[1].events,
            vec![DraftEvent::new("--02-29", EventKind::Birthday)]
        );
    }

    #[test]
    fn an_unparsable_bday_is_dropped_not_fatal() {
        let text =
            "BEGIN:VCARD\nFN:A B\nTEL:1\nBDAY:someday\nBDAY:1990-02-30\nX-ANNIVERSARY:\nEND:VCARD";
        let parsed = parse(text);
        assert_eq!(parsed.skipped, 0);
        assert!(parsed.contacts[0].events.is_empty());
    }

    #[test]
    fn export_names() {
        let mut one = named();
        one.family = "Yılmaz".into();
        let day = (2026, 11, 7);
        assert_eq!(
            export_name(std::slice::from_ref(&one), day),
            crate::shared_card::vcf_file_name(&one.display_name())
        );
        assert_eq!(
            export_name(&[named(), one], (2026, 1, 9)),
            "rovsti-contacts-2-2026-01-09.vcf"
        );
        assert_eq!(export_name(&[], day), "rovsti-contacts-0-2026-11-07.vcf");
    }
}
