//! Scan to add: finds QR codes in a grey-scale picture and reads what they
//! hold (a vCard, a MECARD, a phone number or an email address).
//!
//! Pure: the platform decodes the picked image and passes 8-bit luma.

use crate::contact_dates;
use crate::contact_edit::{ContactDraft, DraftEvent, DraftField, EventKind, FieldKind};
use crate::{import_plan, vcard};

/// Longest side, in pixels, the decoder should be given. Callers downscale
/// larger pictures to this first.
pub const MAX_SIDE: u32 = 1600;

/// Longest payload read, in bytes.
const MAX_PAYLOAD: usize = 8 * 1024;

/// What a scanned code holds.
#[derive(Debug, PartialEq)]
pub enum Scanned {
    /// One or more contact cards.
    Cards(Vec<ContactDraft>),
    /// A phone number as written.
    Number(String),
    /// An email address.
    Email(String),
    /// Nothing the app can add.
    Nothing,
}

/// Decodes every QR code in `luma` (row-major, `width * height` bytes).
/// Callers downscale to [`MAX_SIDE`] first. Returns the distinct texts in
/// detection order; a wrong buffer length gives an empty list.
pub fn decode_luma(width: u32, height: u32, luma: &[u8]) -> Vec<String> {
    let (w, h) = (width as usize, height as usize);
    if w == 0 || h == 0 || w.checked_mul(h) != Some(luma.len()) {
        return Vec::new();
    }
    let mut image = rqrr::PreparedImage::prepare_from_greyscale(w, h, |x, y| luma[y * w + x]);
    let mut found: Vec<String> = Vec::new();
    for grid in image.detect_grids() {
        let mut bytes = Vec::new();
        if grid.decode_to(&mut bytes).is_err() {
            continue;
        }
        if let Ok(text) = String::from_utf8(bytes)
            && !found.contains(&text)
        {
            found.push(text);
        }
    }
    found
}

/// Reads the text of a scanned code.
pub fn parse_payload(text: &str) -> Scanned {
    let text = clip(text).trim();
    let upper = text
        .get(..8)
        .map(str::to_ascii_uppercase)
        .unwrap_or_default();
    if upper.starts_with("BEGIN:VC") {
        let cards: Vec<ContactDraft> = vcard::parse(text)
            .contacts
            .into_iter()
            .filter(usable)
            .collect();
        return cards_or_nothing(cards);
    }
    if upper.starts_with("MECARD:") {
        return cards_or_nothing(
            parse_mecard(&text[7..])
                .into_iter()
                .filter(usable)
                .collect(),
        );
    }
    let lower = text.to_ascii_lowercase();
    if lower.starts_with("mailto:") {
        let address = text[7..].split('?').next().unwrap_or("").trim();
        return if is_email(address) {
            Scanned::Email(address.to_owned())
        } else {
            Scanned::Nothing
        };
    }
    let number = if lower.starts_with("tel:") {
        text[4..].trim()
    } else {
        text
    };
    if is_number(number) {
        return Scanned::Number(number.to_owned());
    }
    if is_email(text) {
        return Scanned::Email(text.to_owned());
    }
    Scanned::Nothing
}

/// The pill for a scan result.
pub fn scan_notice(s: &Scanned) -> &'static str {
    match s {
        Scanned::Cards(cards) if cards.len() == 1 => "Contact found",
        Scanned::Cards(_) => "Contacts found",
        Scanned::Number(_) => "Phone number found",
        Scanned::Email(_) => "Email address found",
        Scanned::Nothing => "No contact in this picture",
    }
}

fn cards_or_nothing(cards: Vec<ContactDraft>) -> Scanned {
    if cards.is_empty() {
        Scanned::Nothing
    } else {
        Scanned::Cards(cards)
    }
}

fn usable(card: &ContactDraft) -> bool {
    !card.given.trim().is_empty()
        || !card.family.trim().is_empty()
        || card.numbers.iter().any(|n| !n.value.trim().is_empty())
        || card.emails.iter().any(|e| !e.value.trim().is_empty())
}

fn clip(text: &str) -> &str {
    if text.len() <= MAX_PAYLOAD {
        return text;
    }
    let mut end = MAX_PAYLOAD;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

fn is_number(s: &str) -> bool {
    !s.is_empty()
        && s.chars()
            .all(|c| c.is_ascii_digit() || matches!(c, '+' | ' ' | '-' | '(' | ')' | '.' | '/'))
        && import_plan::number_key(s).is_some()
}

fn is_email(s: &str) -> bool {
    let Some((local, domain)) = s.split_once('@') else {
        return false;
    };
    !local.is_empty()
        && !domain.is_empty()
        && !domain.contains('@')
        && !s.chars().any(char::is_whitespace)
}

/// Splits on `sep` where it is not escaped with a backslash.
fn split_unescaped(s: &str, sep: char) -> Vec<&str> {
    let mut parts = Vec::new();
    let (mut start, mut escaped) = (0, false);
    for (i, c) in s.char_indices() {
        if escaped {
            escaped = false;
        } else if c == '\\' {
            escaped = true;
        } else if c == sep {
            parts.push(&s[start..i]);
            start = i + c.len_utf8();
        }
    }
    parts.push(&s[start..]);
    parts
}

fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            out.extend(chars.next());
        } else {
            out.push(c);
        }
    }
    out
}

/// Reads the part of a MECARD after `MECARD:`; one card at most.
fn parse_mecard(body: &str) -> Vec<ContactDraft> {
    let mut card = ContactDraft::default();
    for field in split_unescaped(body, ';') {
        let Some((key, value)) = field.split_once(':') else {
            continue;
        };
        let text = unescape(value).trim().to_owned();
        if text.is_empty() {
            continue;
        }
        match key.trim().to_ascii_uppercase().as_str() {
            "N" => match split_unescaped(value, ',').as_slice() {
                [family, given, ..] => {
                    card.family = unescape(family).trim().to_owned();
                    card.given = unescape(given).trim().to_owned();
                }
                _ => card.given = text,
            },
            "TEL" | "TEL-AV" => card.numbers.push(DraftField::new(text, FieldKind::Mobile)),
            "EMAIL" => card.emails.push(DraftField::new(text, FieldKind::Home)),
            "ADR" => {
                let address = text
                    .split(',')
                    .map(str::trim)
                    .filter(|p| !p.is_empty())
                    .collect::<Vec<_>>()
                    .join("\n");
                card.addresses
                    .push(DraftField::new(address, FieldKind::Home));
            }
            "NOTE" => card.note = text,
            "URL" => card.websites.push(text),
            "ORG" => card.org = text,
            "BDAY" => {
                if let Some(date) = contact_dates::parse(&text) {
                    card.events
                        .push(DraftEvent::new(date.to_android(), EventKind::Birthday));
                }
            }
            _ => {}
        }
    }
    vec![card]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::number_format;
    use qrcode::{Color, QrCode};

    /// Draws `text` as 4 px modules with a 4-module quiet zone.
    fn render(text: &str) -> (u32, u32, Vec<u8>) {
        let code = QrCode::new(text.as_bytes()).expect("fits");
        let modules = code.width();
        let colors = code.to_colors();
        let side = (modules + 8) * 4;
        let mut luma = vec![255u8; side * side];
        for y in 0..side {
            for x in 0..side {
                let (mx, my) = (x / 4, y / 4);
                if (4..modules + 4).contains(&mx)
                    && (4..modules + 4).contains(&my)
                    && colors[(my - 4) * modules + (mx - 4)] == Color::Dark
                {
                    luma[y * side + x] = 0;
                }
            }
        }
        (side as u32, side as u32, luma)
    }

    #[test]
    fn round_trips_a_contact_qr() {
        let qr = number_format::contact_qr("Ada Lovelace", &[("+905551112233", None)], None)
            .expect("fits");
        let (w, h, luma) = render(&qr.payload);
        assert_eq!(decode_luma(w, h, &luma), vec![qr.payload.clone()]);
        let Scanned::Cards(cards) = parse_payload(&qr.payload) else {
            panic!("cards");
        };
        assert_eq!(cards[0].display_name(), "Ada Lovelace");
    }

    #[test]
    fn round_trips_two_cards() {
        let text = "BEGIN:VCARD\nVERSION:3.0\nFN:Ada Lovelace\nN:Lovelace;Ada;;;\nTEL:+905551112233\nEND:VCARD\n\
BEGIN:VCARD\nVERSION:3.0\nFN:Alan Turing\nN:Turing;Alan;;;\nEMAIL:alan@example.com\nEND:VCARD\n";
        let (w, h, luma) = render(text);
        let found = decode_luma(w, h, &luma);
        assert_eq!(found, vec![text.to_owned()]);
        let Scanned::Cards(cards) = parse_payload(&found[0]) else {
            panic!("cards");
        };
        assert_eq!(cards.len(), 2);
    }

    #[test]
    fn bad_buffers_give_nothing() {
        assert!(decode_luma(10, 10, &[0; 99]).is_empty());
        assert!(decode_luma(0, 10, &[]).is_empty());
        assert!(decode_luma(100, 100, &vec![255; 10_000]).is_empty());
    }

    #[test]
    fn mecard_with_escapes_and_birthday() {
        let text = r"MECARD:N:Celik\, Jr,Omer;TEL:+90 532 111 22 33;EMAIL:o@x.com;ADR:Main St\; 5,Ankara;NOTE:a\:b \\ c;URL:https://x.com;BDAY:19900307;;";
        let Scanned::Cards(cards) = parse_payload(text) else {
            panic!("cards");
        };
        let c = &cards[0];
        assert_eq!((c.family.as_str(), c.given.as_str()), ("Celik, Jr", "Omer"));
        assert_eq!(c.numbers[0].value, "+90 532 111 22 33");
        assert_eq!(c.emails[0].value, "o@x.com");
        assert_eq!(c.addresses[0].value, "Main St; 5\nAnkara");
        assert_eq!(c.note, r"a:b \ c");
        assert_eq!(c.websites, vec!["https://x.com".to_owned()]);
        assert_eq!(c.events[0].date, "1990-03-07");
        assert_eq!(c.events[0].kind, EventKind::Birthday);
    }

    #[test]
    fn mecard_single_name() {
        let Scanned::Cards(cards) = parse_payload("MECARD:N:Ada;;") else {
            panic!("cards");
        };
        assert_eq!(cards[0].display_name(), "Ada");
    }

    #[test]
    fn numbers_and_emails() {
        assert_eq!(
            parse_payload("tel:+90 532 111"),
            Scanned::Number("+90 532 111".into())
        );
        assert_eq!(parse_payload("TEL:0532"), Scanned::Number("0532".into()));
        assert_eq!(
            parse_payload(" 0532 111 22 33 "),
            Scanned::Number("0532 111 22 33".into())
        );
        assert_eq!(
            parse_payload("mailto:a@b.c"),
            Scanned::Email("a@b.c".into())
        );
        assert_eq!(
            parse_payload("mailto:a@b.c?subject=hi"),
            Scanned::Email("a@b.c".into())
        );
        assert_eq!(parse_payload("a@b.c"), Scanned::Email("a@b.c".into()));
    }

    #[test]
    fn everything_else_is_nothing() {
        for text in [
            "",
            "https://example.com/p/12345",
            "hello world",
            "a@b@c",
            "mailto:nope",
            "BEGIN:VCARD\nVERSION:3.0\nEND:VCARD",
            "MECARD:;;",
        ] {
            assert_eq!(parse_payload(text), Scanned::Nothing, "{text}");
        }
        assert_eq!(scan_notice(&Scanned::Nothing), "No contact in this picture");
    }

    #[test]
    fn long_input_is_clipped_on_a_char_boundary() {
        let text = format!("MECARD:N:{};;", "é".repeat(10_000));
        let Scanned::Cards(cards) = parse_payload(&text) else {
            panic!("cards");
        };
        assert!(cards[0].given.len() <= MAX_PAYLOAD);
    }
}
