//! Contact cards: reading the vCards WhatsApp carries and writing the one Rosty sends.
//!
//! WhatsApp's own cards are vCard 3.0 with `item1.TEL;waid=<digits>:<number>` lines; other apps send plain `TEL`
//! lines, 2.1 or 4.0. Lines may be folded (a line that starts with a space or a tab continues the one before) and
//! values escape `\,`, `\;`, `\n` and `\\`.

use serde::{Deserialize, Serialize};

/// Largest vCard text read or sent (the gateway's limit per card).
pub const MAX_VCARD_BYTES: usize = 64 * 1024;

/// One phone number of a card.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CardNumber {
    /// As written on the card.
    pub number: String,
    /// `CELL`, `HOME`, a custom label; `None` when the card names none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    /// The WhatsApp JID of the number, when the card says it is on WhatsApp (`waid`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub wa_jid: Option<String>,
}

/// What a contact card says.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Card {
    /// The formatted name; `None` when the card has neither `FN` nor `N`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub numbers: Vec<CardNumber>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub emails: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub org: Option<String>,
}

fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n' | 'N') => out.push('\n'),
            Some(other) => out.push(other),
            None => {}
        }
    }
    out
}

fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            ',' => out.push_str("\\,"),
            ';' => out.push_str("\\;"),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            c => out.push(c),
        }
    }
    out
}

/// The logical lines of `text`: folded lines joined, empty ones dropped.
fn unfold(text: &str) -> Vec<String> {
    let mut lines: Vec<String> = Vec::new();
    for raw in text.split('\n') {
        let raw = raw.strip_suffix('\r').unwrap_or(raw);
        match raw.strip_prefix([' ', '\t']) {
            Some(rest) if !lines.is_empty() => {
                if let Some(last) = lines.last_mut() {
                    last.push_str(rest);
                }
            }
            _ => lines.push(raw.to_owned()),
        }
    }
    lines.retain(|l| !l.trim().is_empty());
    lines
}

/// A content line split into its property name (group dropped, upper case), its parameters and its value.
fn split_line(line: &str) -> Option<(String, Vec<(String, String)>, String)> {
    let (head, value) = line.split_once(':')?;
    let mut parts = head.split(';');
    let name = parts.next()?.trim();
    let name = name
        .rsplit_once('.')
        .map_or(name, |(_, n)| n)
        .to_ascii_uppercase();
    let params = parts
        .map(|p| match p.split_once('=') {
            Some((k, v)) => (k.trim().to_ascii_uppercase(), v.trim().to_owned()),
            // vCard 2.1 `TEL;CELL:` names the type bare.
            None => ("TYPE".to_owned(), p.trim().to_owned()),
        })
        .collect();
    Some((name, params, value.to_owned()))
}

/// The digits of a WhatsApp number written on a card, or of a `waid`.
pub fn wa_number(text: &str) -> Option<String> {
    let digits: String = text.chars().filter(char::is_ascii_digit).collect();
    (6..=15).contains(&digits.len()).then_some(digits)
}

fn label_of(
    params: &[(String, String)],
    group_labels: &[(String, String)],
    group: &str,
) -> Option<String> {
    if !group.is_empty()
        && let Some((_, label)) = group_labels.iter().find(|(g, _)| g == group)
    {
        return Some(unescape(label));
    }
    params
        .iter()
        .filter(|(k, _)| k == "TYPE")
        .flat_map(|(_, v)| v.split(','))
        .map(|t| t.trim().trim_matches('"').to_ascii_uppercase())
        .find(|t| !t.is_empty() && t != "VOICE" && t != "PREF" && t != "INTERNET")
}

/// Every card in `text` (a text may hold several `BEGIN:VCARD` blocks; a text without one is read as one card).
/// Over [`MAX_VCARD_BYTES`] nothing is read.
pub fn parse_vcards(text: &str) -> Vec<Card> {
    if text.len() > MAX_VCARD_BYTES {
        return Vec::new();
    }
    let lines = unfold(text);
    let mut blocks: Vec<Vec<&String>> = Vec::new();
    let mut current: Option<Vec<&String>> = None;
    for line in &lines {
        let upper = line.trim().to_ascii_uppercase();
        if upper == "BEGIN:VCARD" {
            current = Some(Vec::new());
        } else if upper == "END:VCARD" {
            if let Some(block) = current.take() {
                blocks.push(block);
            }
        } else if let Some(block) = current.as_mut() {
            block.push(line);
        }
    }
    if let Some(block) = current {
        blocks.push(block);
    }
    if blocks.is_empty() && !lines.is_empty() {
        blocks.push(lines.iter().collect());
    }
    blocks.into_iter().map(|block| card_of(&block)).collect()
}

fn card_of(lines: &[&String]) -> Card {
    // `item1.X-ABLabel:Work` names the label of every `item1.` line.
    let group_labels: Vec<(String, String)> = lines
        .iter()
        .filter_map(|line| {
            let (head, value) = line.split_once(':')?;
            let (group, name) = head.split(';').next()?.split_once('.')?;
            name.eq_ignore_ascii_case("X-ABLABEL")
                .then(|| (group.to_ascii_lowercase(), value.trim().to_owned()))
        })
        .collect();
    let mut card = Card::default();
    let mut structured: Option<String> = None;
    for line in lines {
        let Some((name, params, value)) = split_line(line) else {
            continue;
        };
        let group = line
            .split([';', ':'])
            .next()
            .and_then(|h| h.split_once('.'))
            .map(|(g, _)| g.to_ascii_lowercase())
            .unwrap_or_default();
        let value = value.trim();
        match name.as_str() {
            "FN" if !value.is_empty() => card.name = Some(unescape(value)),
            "N" if !value.is_empty() => {
                // family;given;additional;prefix;suffix
                let parts: Vec<String> = split_unescaped(value, ';');
                let order = [3, 1, 2, 0, 4];
                let name = order
                    .iter()
                    .filter_map(|i| parts.get(*i))
                    .map(|p| p.trim())
                    .filter(|p| !p.is_empty())
                    .collect::<Vec<_>>()
                    .join(" ");
                if !name.is_empty() {
                    structured = Some(name);
                }
            }
            "TEL" => {
                let number = unescape(value).trim().to_owned();
                if number.is_empty() {
                    continue;
                }
                let waid = params
                    .iter()
                    .find(|(k, _)| k == "WAID")
                    .and_then(|(_, v)| wa_number(v));
                card.numbers.push(CardNumber {
                    label: label_of(&params, &group_labels, &group),
                    wa_jid: waid.map(|d| format!("{d}@s.whatsapp.net")),
                    number,
                });
            }
            "EMAIL" if !value.is_empty() => card.emails.push(unescape(value)),
            "ORG" if !value.is_empty() => {
                let org = split_unescaped(value, ';')
                    .into_iter()
                    .map(|p| p.trim().to_owned())
                    .filter(|p| !p.is_empty())
                    .collect::<Vec<_>>()
                    .join(", ");
                if !org.is_empty() {
                    card.org = Some(org);
                }
            }
            _ => {}
        }
    }
    if card.name.as_deref().is_none_or(|n| n.trim().is_empty()) {
        card.name = structured.or(card.org.clone());
    }
    card
}

/// `value` split at unescaped `sep`, each part unescaped.
fn split_unescaped(value: &str, sep: char) -> Vec<String> {
    let mut parts = Vec::new();
    let mut current = String::new();
    let mut escaped = false;
    for c in value.chars() {
        if escaped {
            current.push('\\');
            current.push(c);
            escaped = false;
        } else if c == '\\' {
            escaped = true;
        } else if c == sep {
            parts.push(unescape(&current));
            current.clear();
        } else {
            current.push(c);
        }
    }
    parts.push(unescape(&current));
    parts
}

/// The vCard 3.0 Rosty sends for one contact: its name and numbers, WhatsApp style (`item<n>.TEL;waid=`, with the
/// label as `X-ABLabel`). A number in international form (`+` and digits) gets its `waid`, so WhatsApp offers
/// "Message"; a number without a country code does not.
pub fn build_vcard(name: &str, numbers: &[CardNumber]) -> String {
    let name = name.trim();
    let mut out = String::from("BEGIN:VCARD\nVERSION:3.0\n");
    out.push_str(&format!("N:;{};;;\n", escape(name)));
    out.push_str(&format!("FN:{}\n", escape(name)));
    for (i, n) in numbers.iter().enumerate() {
        let number = n.number.trim();
        if number.is_empty() {
            continue;
        }
        let item = i + 1;
        let international = number.starts_with('+');
        let waid = if international {
            wa_number(number)
                .map(|d| format!(";waid={d}"))
                .unwrap_or_default()
        } else {
            String::new()
        };
        out.push_str(&format!("item{item}.TEL{waid}:{}\n", escape(number)));
        let label = n
            .label
            .as_deref()
            .map(str::trim)
            .filter(|l| !l.is_empty())
            .unwrap_or("Mobile");
        out.push_str(&format!("item{item}.X-ABLabel:{}\n", escape(label)));
    }
    out.push_str("END:VCARD");
    out
}

/// The title of a message with `cards`: the one name, or the first name and how many more. `None` when the first
/// card has no name.
pub fn cards_title(cards: &[Card]) -> Option<(String, usize)> {
    let first = cards.first()?.name.clone()?;
    Some((first, cards.len().saturating_sub(1)))
}

#[cfg(test)]
mod tests {
    use super::*;

    const WHATSAPP: &str = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Yılmaz;Ayşe;;;\r\nFN:Ayşe Yılmaz\r\n\
        item1.TEL;waid=100000000031:+1 000 000 0031\r\nitem1.X-ABLabel:Mobile\r\n\
        item2.TEL:+1 000 000 0032\r\nitem2.X-ABLabel:Work\r\nEND:VCARD";

    #[test]
    fn a_whatsapp_card_reads_its_name_numbers_labels_and_waid() {
        let cards = parse_vcards(WHATSAPP);
        assert_eq!(cards.len(), 1);
        let card = &cards[0];
        assert_eq!(card.name.as_deref(), Some("Ayşe Yılmaz"));
        assert_eq!(
            card.numbers,
            vec![
                CardNumber {
                    number: "+1 000 000 0031".into(),
                    label: Some("Mobile".into()),
                    wa_jid: Some("100000000031@s.whatsapp.net".into()),
                },
                CardNumber {
                    number: "+1 000 000 0032".into(),
                    label: Some("Work".into()),
                    wa_jid: None,
                },
            ]
        );
    }

    #[test]
    fn a_card_with_no_number_keeps_its_name_and_falls_back_to_n_or_org() {
        let cards = parse_vcards(
            "BEGIN:VCARD\nVERSION:3.0\nN:Doe;John;;Dr.;\nEMAIL:j@example.com\nEND:VCARD",
        );
        assert_eq!(cards[0].name.as_deref(), Some("Dr. John Doe"));
        assert!(cards[0].numbers.is_empty());
        assert_eq!(cards[0].emails, vec!["j@example.com".to_owned()]);
        let cards = parse_vcards("BEGIN:VCARD\nORG:Acme\\, Inc.;Sales\nEND:VCARD");
        assert_eq!(cards[0].name.as_deref(), Some("Acme, Inc., Sales"));
    }

    #[test]
    fn folded_lines_escapes_and_old_types_are_read() {
        let text = "BEGIN:VCARD\nVERSION:2.1\nFN:Ali\\, the\n  builder\nTEL;CELL:100000000041\nTEL;TYPE=home,voice:100000000042\nEND:VCARD";
        let card = &parse_vcards(text)[0];
        assert_eq!(card.name.as_deref(), Some("Ali, the builder"));
        assert_eq!(card.numbers[0].label.as_deref(), Some("CELL"));
        assert_eq!(card.numbers[1].label.as_deref(), Some("HOME"));
    }

    #[test]
    fn several_cards_in_one_text() {
        let text = format!("{WHATSAPP}\n{}", "BEGIN:VCARD\nFN:Ben\nEND:VCARD");
        let cards = parse_vcards(&text);
        assert_eq!(cards.len(), 2);
        assert_eq!(cards_title(&cards), Some(("Ayşe Yılmaz".to_owned(), 1)));
        assert_eq!(cards_title(&[]), None);
    }

    #[test]
    fn nothing_is_read_from_an_oversized_text() {
        assert!(parse_vcards(&"x".repeat(MAX_VCARD_BYTES + 1)).is_empty());
        assert!(parse_vcards("").is_empty());
    }

    #[test]
    fn a_built_card_reads_back_with_its_waid() {
        let text = build_vcard(
            "Ayşe; Yılmaz",
            &[
                CardNumber {
                    number: "+1 000 000 0031".into(),
                    label: None,
                    wa_jid: None,
                },
                CardNumber {
                    number: "0555 000 0032".into(),
                    label: Some("Home".into()),
                    wa_jid: None,
                },
            ],
        );
        assert!(text.contains("item1.TEL;waid=10000000031:+1 000 000 0031"));
        assert!(text.contains("item2.TEL:0555 000 0032"));
        let card = &parse_vcards(&text)[0];
        assert_eq!(card.name.as_deref(), Some("Ayşe; Yılmaz"));
        assert_eq!(
            card.numbers[0].wa_jid.as_deref(),
            Some("10000000031@s.whatsapp.net")
        );
        assert_eq!(card.numbers[0].label.as_deref(), Some("Mobile"));
        assert_eq!(card.numbers[1].label.as_deref(), Some("Home"));
        // No numbers at all is still a card.
        let bare = build_vcard("Ben", &[]);
        assert_eq!(parse_vcards(&bare)[0].name.as_deref(), Some("Ben"));
    }
}
