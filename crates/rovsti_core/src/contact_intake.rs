//! Contacts shared to the app or opened with it: a `.vcf` file, a contacts
//! `.csv` (Google or Outlook) or plain text.

use crate::contact_csv;
use crate::contact_edit::ContactDraft;
use crate::contact_scan::{self, Scanned};
use crate::vcard;

/// Largest shared file read, in bytes.
pub const MAX_SHARED_BYTES: usize = 4 * 1024 * 1024;

/// What a shared file or text holds.
#[derive(Debug, PartialEq)]
pub enum Intake {
    Cards(Vec<ContactDraft>),
    Single(Scanned),
    NotContacts,
    TooBig,
    Unreadable,
}

const UTF8_BOM: &[u8] = b"\xEF\xBB\xBF";

/// Whether a file looks like a vCard by type, name or first bytes.
pub fn is_vcard(mime: Option<&str>, name: &str, head: &[u8]) -> bool {
    if let Some(mime) = mime {
        let mime = mime
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        if matches!(
            mime.as_str(),
            "text/vcard" | "text/x-vcard" | "text/directory"
        ) {
            return true;
        }
    }
    let name = name.to_ascii_lowercase();
    if name.ends_with(".vcf") || name.ends_with(".vcard") {
        return true;
    }
    let head = head.strip_prefix(UTF8_BOM).unwrap_or(head);
    let head = head.trim_ascii_start();
    head.len() >= 11 && head[..11].eq_ignore_ascii_case(b"BEGIN:VCARD")
}

/// The text of `bytes`: UTF-8 only, a byte order mark removed.
pub fn decode(bytes: &[u8]) -> Option<String> {
    let bytes = bytes.strip_prefix(UTF8_BOM).unwrap_or(bytes);
    std::str::from_utf8(bytes).ok().map(str::to_owned)
}

/// Reads a shared or opened file (vCard, contacts CSV or text).
pub fn from_file(mime: Option<&str>, name: &str, bytes: &[u8]) -> Intake {
    if bytes.len() > MAX_SHARED_BYTES {
        return Intake::TooBig;
    }
    let head = &bytes[..bytes.len().min(64)];
    let looks_vcard = is_vcard(mime, name, head);
    let Some(text) = decode(bytes) else {
        return Intake::Unreadable;
    };
    if !looks_vcard && contact_csv::looks_like_csv(mime, name, head) {
        let cards = contact_csv::parse(&text).contacts;
        return if cards.is_empty() {
            Intake::NotContacts
        } else {
            Intake::Cards(cards)
        };
    }
    if !looks_vcard {
        return match from_text(&text) {
            Intake::Single(Scanned::Nothing) => Intake::NotContacts,
            other => other,
        };
    }
    let cards: Vec<ContactDraft> = vcard::parse(&text)
        .contacts
        .into_iter()
        .filter(|c| {
            !c.given.trim().is_empty()
                || !c.family.trim().is_empty()
                || c.numbers.iter().any(|n| !n.value.trim().is_empty())
                || c.emails.iter().any(|e| !e.value.trim().is_empty())
        })
        .collect();
    if cards.is_empty() {
        Intake::NotContacts
    } else {
        Intake::Cards(cards)
    }
}

/// Reads shared text.
pub fn from_text(text: &str) -> Intake {
    match contact_scan::parse_pasted(text) {
        Scanned::Cards(cards) => Intake::Cards(cards),
        Scanned::Nothing => Intake::NotContacts,
        single => Intake::Single(single),
    }
}

/// The pill for an intake that cannot be used.
pub fn notice(i: &Intake) -> Option<&'static str> {
    match i {
        Intake::NotContacts => Some("This file has no contacts"),
        Intake::TooBig => Some("This file is too big to open"),
        Intake::Unreadable => Some("Can't read this file"),
        Intake::Cards(_) | Intake::Single(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CARD: &str = "BEGIN:VCARD\nVERSION:3.0\nFN:Ada Lovelace\nN:Lovelace;Ada;;;\nTEL:+905551112233\nEND:VCARD\n";

    #[test]
    fn vcard_by_mime_name_or_head() {
        assert!(is_vcard(Some("text/x-vcard"), "a", b""));
        assert!(is_vcard(Some("Text/VCard; charset=utf-8"), "a", b""));
        assert!(is_vcard(Some("text/directory"), "a", b""));
        assert!(is_vcard(None, "Ada.VCF", b""));
        assert!(is_vcard(None, "ada.vcard", b""));
        assert!(is_vcard(None, "x", b"\xEF\xBB\xBF \r\nbegin:vcard\n"));
        assert!(!is_vcard(Some("text/plain"), "a.txt", b"hello"));
        assert!(!is_vcard(None, "", b"BEGIN:VCAL"));
    }

    #[test]
    fn decodes_utf8_only() {
        assert_eq!(decode(b"\xEF\xBB\xBFhi").as_deref(), Some("hi"));
        assert_eq!(decode(&[0xff, 0xfe, 0x41]), None);
    }

    #[test]
    fn file_with_cards() {
        let Intake::Cards(cards) = from_file(None, "a.vcf", CARD.as_bytes()) else {
            panic!("cards");
        };
        assert_eq!(cards[0].display_name(), "Ada Lovelace");
        assert_eq!(notice(&Intake::Cards(cards)), None);
    }

    #[test]
    fn bad_files() {
        assert_eq!(
            from_file(None, "a.vcf", &[0xff, 0xfe, 0x00]),
            Intake::Unreadable
        );
        let big = vec![b'a'; MAX_SHARED_BYTES + 1];
        assert_eq!(from_file(None, "a.vcf", &big), Intake::TooBig);
        assert_eq!(
            from_file(None, "a.vcf", b"BEGIN:VCARD\nVERSION:3.0\nEND:VCARD"),
            Intake::NotContacts
        );
        assert_eq!(
            from_file(None, "a.txt", b"hello there"),
            Intake::NotContacts
        );
        assert_eq!(
            notice(&Intake::TooBig),
            Some("This file is too big to open")
        );
        assert_eq!(notice(&Intake::Unreadable), Some("Can't read this file"));
        assert_eq!(
            notice(&Intake::NotContacts),
            Some("This file has no contacts")
        );
    }

    #[test]
    fn csv_file_routes_to_cards() {
        let csv = "First Name,Last Name,Phone 1 - Label,Phone 1 - Value\r\nAda,Lovelace,Mobile,+905551112233\r\n";
        let Intake::Cards(cards) = from_file(Some("text/csv"), "c.csv", csv.as_bytes()) else {
            panic!("cards");
        };
        assert_eq!(cards[0].display_name(), "Ada Lovelace");
        assert_eq!(cards[0].numbers[0].value, "+905551112233");
        // By header alone, with a BOM and an unhelpful name.
        let bom = format!("\u{FEFF}{csv}");
        assert!(matches!(
            from_file(None, "export", bom.as_bytes()),
            Intake::Cards(_)
        ));
        assert_eq!(
            from_file(None, "a.csv", b"First Name,Last Name\r\n"),
            Intake::NotContacts
        );
    }

    #[test]
    fn text_intake() {
        assert!(matches!(from_text(CARD), Intake::Cards(_)));
        assert!(matches!(
            from_text("Ayse Yilmaz +90 532 111 22 33"),
            Intake::Cards(_)
        ));
        assert_eq!(
            from_text("0532 111 22 33"),
            Intake::Single(Scanned::Number("0532 111 22 33".into()))
        );
        assert_eq!(from_text("hello"), Intake::NotContacts);
    }
}
