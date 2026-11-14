//! A contact card received in a message (vCard): what the "View contact" sheet
//! shows, and the exports (save .vcf, share text, insert into the system book).

use serde_json::json;

use crate::contact_edit::{ContactDraft, DraftField, FieldKind};
use crate::number_format::file_slug_opt;
use crate::vcard;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardField {
    pub value: String,
    pub label: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct CardView {
    pub name: String,
    pub org: String,
    pub numbers: Vec<CardField>,
    pub emails: Vec<CardField>,
    pub note: String,
}

fn fields(src: &[DraftField]) -> Vec<CardField> {
    src.iter()
        .filter(|f| !f.value.trim().is_empty())
        .map(|f| CardField {
            value: f.value.trim().to_owned(),
            label: f.title(),
        })
        .collect()
}

/// Reads the first card of `vcard`. The name falls back to `display_name`,
/// then the organisation, then the first number. An unreadable vCard gives a
/// card with only the display name.
pub fn from_vcard(display_name: &str, vcard: &str) -> CardView {
    let parsed = vcard::parse(vcard);
    let mut view = parsed
        .contacts
        .first()
        .map(|c| CardView {
            name: [c.given.trim(), c.family.trim()]
                .iter()
                .filter(|p| !p.is_empty())
                .copied()
                .collect::<Vec<_>>()
                .join(" "),
            org: c.org.trim().to_owned(),
            numbers: fields(&c.numbers),
            emails: fields(&c.emails),
            note: c.note.trim().to_owned(),
        })
        .unwrap_or_default();
    if view.name.is_empty() {
        let fallback = display_name.trim();
        view.name = if !fallback.is_empty() {
            fallback.to_owned()
        } else if !view.org.is_empty() {
            view.org.clone()
        } else {
            view.numbers
                .first()
                .map(|n| n.value.clone())
                .unwrap_or_default()
        };
    }
    view
}

fn kind_of(label: &str) -> (FieldKind, Option<String>) {
    match label {
        "Mobile" => (FieldKind::Mobile, None),
        "Home" => (FieldKind::Home, None),
        "Work" => (FieldKind::Work, None),
        "Other" | "" => (FieldKind::Other, None),
        custom => (FieldKind::Custom, Some(custom.to_owned())),
    }
}

fn draft_fields(src: &[CardField]) -> Vec<DraftField> {
    src.iter()
        .map(|f| {
            let (kind, label) = kind_of(&f.label);
            DraftField {
                value: f.value.clone(),
                kind,
                label,
                primary: false,
            }
        })
        .collect()
}

/// The card as vCard 3.0 text.
pub fn to_vcard(card: &CardView) -> String {
    vcard::write(&[ContactDraft {
        given: card.name.clone(),
        org: card.org.clone(),
        note: card.note.clone(),
        numbers: draft_fields(&card.numbers),
        emails: draft_fields(&card.emails),
        ..ContactDraft::default()
    }])
}

/// "ada-lovelace.vcf", or "contact.vcf" when the name has nothing usable.
pub fn vcf_file_name(name: &str) -> String {
    format!(
        "{}.vcf",
        file_slug_opt(name).unwrap_or_else(|| "contact".to_owned())
    )
}

/// Plain text for the share sheet.
pub fn share_text(card: &CardView) -> String {
    let mut lines = vec![card.name.clone()];
    if !card.org.is_empty() {
        lines.push(card.org.clone());
    }
    lines.extend(
        card.numbers
            .iter()
            .map(|n| format!("{}: {}", n.label, n.value)),
    );
    lines.extend(card.emails.iter().map(|e| e.value.clone()));
    lines.retain(|l| !l.is_empty());
    lines.join("\n")
}

/// JSON for the Kotlin "insert contact" intent.
pub fn insert_json(card: &CardView) -> String {
    let list = |src: &[CardField]| {
        src.iter()
            .map(|f| json!({"value": f.value, "label": f.label}))
            .collect::<Vec<_>>()
    };
    json!({
        "name": card.name,
        "org": card.org,
        "note": card.note,
        "phones": list(&card.numbers),
        "emails": list(&card.emails),
    })
    .to_string()
}

/// The number to call or message first.
pub fn primary_number(card: &CardView) -> Option<&str> {
    card.numbers.first().map(|n| n.value.as_str())
}

/// The largest vCard file that is read for a card (bytes).
pub const MAX_VCARD_BYTES: u64 = 64 * 1024;

/// Whether an MMS part type is a vCard.
pub fn is_vcard_mime(mime: &str) -> bool {
    let t = mime.trim().to_ascii_lowercase();
    let t = t.split(';').next().unwrap_or("").trim();
    matches!(t, "text/x-vcard" | "text/vcard" | "text/directory")
}

/// The numbers a card bubble lists: at most `max`, then "+N more".
pub fn number_lines(card: &CardView, max: usize) -> Vec<String> {
    let mut lines: Vec<String> = card
        .numbers
        .iter()
        .take(max)
        .map(|n| n.value.clone())
        .collect();
    if card.numbers.len() > max {
        lines.push(format!("+{} more", card.numbers.len() - max));
    }
    lines
}

/// "card-Ada-Lovelace.vcf": the cache file a Save .vcf writes.
pub fn cache_file_name(name: &str) -> String {
    format!(
        "card-{}.vcf",
        file_slug_opt(name).unwrap_or_else(|| "contact".to_owned())
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const FULL: &str = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Lovelace;Ada;;;\r\nFN:Ada Lovelace\r\n\
ORG:Analytical Engines\r\nTEL;TYPE=CELL:+905551112233\r\nTEL;TYPE=WORK:+902125550000\r\n\
EMAIL;TYPE=INTERNET,HOME:ada@x.org\r\nEMAIL;TYPE=INTERNET,WORK:ada@work.org\r\nNOTE:Hi\r\n\
END:VCARD\r\n";

    #[test]
    fn parses_every_field() {
        let c = from_vcard("ignored", FULL);
        assert_eq!(c.name, "Ada Lovelace");
        assert_eq!(c.org, "Analytical Engines");
        assert_eq!(c.numbers.len(), 2);
        assert_eq!(
            c.numbers[0],
            CardField {
                value: "+905551112233".into(),
                label: "Mobile".into()
            }
        );
        assert_eq!(c.numbers[1].label, "Work");
        assert_eq!(c.emails[0].label, "Home");
        assert_eq!(c.emails[1].value, "ada@work.org");
        assert_eq!(c.note, "Hi");
        assert_eq!(primary_number(&c), Some("+905551112233"));
    }

    #[test]
    fn whatsapp_style_item_lines() {
        let v = "BEGIN:VCARD\nVERSION:3.0\nFN:Bob\nitem1.TEL;waid=905551112233:+90 555 111 22 33\nitem1.X-ABLabel:Mobile\nEND:VCARD\n";
        let c = from_vcard("", v);
        assert_eq!(c.name, "Bob");
        assert_eq!(c.numbers.len(), 1);
        assert_eq!(c.numbers[0].value, "+90 555 111 22 33");
    }

    #[test]
    fn name_fallbacks() {
        let no_name = "BEGIN:VCARD\nVERSION:3.0\nTEL:5551112233\nEND:VCARD\n";
        // A nameless card is skipped by the parser: display name wins.
        assert_eq!(from_vcard("  Zed ", no_name).name, "Zed");
        let org_only = "BEGIN:VCARD\nVERSION:3.0\nORG:Acme\nTEL:5551112233\nEND:VCARD\n";
        let c = from_vcard("", org_only);
        assert_eq!(c.name, "Acme");
        assert_eq!(c.numbers.len(), 1);
    }

    #[test]
    fn empty_vcard_keeps_the_display_name() {
        let c = from_vcard("Ada", "");
        assert_eq!(
            c,
            CardView {
                name: "Ada".into(),
                ..CardView::default()
            }
        );
        assert_eq!(primary_number(&c), None);
        assert_eq!(from_vcard("", "garbage").name, "");
    }

    #[test]
    fn round_trip() {
        let c = from_vcard("", FULL);
        let again = from_vcard("", &to_vcard(&c));
        assert_eq!(again, c);
    }

    #[test]
    fn custom_labels_survive_the_draft() {
        let c = CardView {
            name: "Z".into(),
            numbers: vec![CardField {
                value: "1".into(),
                label: "Boss".into(),
            }],
            ..CardView::default()
        };
        let drafts = draft_fields(&c.numbers);
        assert_eq!(drafts[0].kind, FieldKind::Custom);
        assert_eq!(drafts[0].title(), "Boss");
    }

    #[test]
    fn file_names() {
        assert_eq!(vcf_file_name("Ada Lovelace"), "Ada-Lovelace.vcf");
        assert_eq!(vcf_file_name("Şebnem Ünal"), "Şebnem-Ünal.vcf");
        assert_eq!(vcf_file_name("   "), "contact.vcf");
        assert_eq!(vcf_file_name("***"), "contact.vcf");
    }

    #[test]
    fn share_text_lines() {
        let c = from_vcard("", FULL);
        assert_eq!(
            share_text(&c),
            "Ada Lovelace\nAnalytical Engines\nMobile: +905551112233\nWork: +902125550000\nada@x.org\nada@work.org"
        );
        let bare = CardView {
            name: "Q".into(),
            ..CardView::default()
        };
        assert_eq!(share_text(&bare), "Q");
    }

    #[test]
    fn insert_json_shape() {
        let c = from_vcard("", FULL);
        let v: serde_json::Value = serde_json::from_str(&insert_json(&c)).expect("json");
        assert_eq!(v["name"], "Ada Lovelace");
        assert_eq!(v["org"], "Analytical Engines");
        assert_eq!(v["note"], "Hi");
        assert_eq!(
            v["phones"][0],
            serde_json::json!({"value": "+905551112233", "label": "Mobile"})
        );
        assert_eq!(v["emails"][1]["label"], "Work");
        assert_eq!(v["phones"].as_array().expect("array").len(), 2);
    }

    #[test]
    fn vcard_mimes() {
        assert!(is_vcard_mime("text/x-vcard"));
        assert!(is_vcard_mime(" Text/VCard; charset=utf-8"));
        assert!(!is_vcard_mime("text/plain"));
    }

    #[test]
    fn number_lines_cap_with_more() {
        let f = |v: &str| CardField {
            value: v.into(),
            label: "Mobile".into(),
        };
        let card = CardView {
            numbers: vec![f("1"), f("2"), f("3"), f("4"), f("5")],
            ..CardView::default()
        };
        assert_eq!(number_lines(&card, 3), vec!["1", "2", "3", "+2 more"]);
        assert_eq!(number_lines(&card, 5).len(), 5);
        assert!(number_lines(&CardView::default(), 3).is_empty());
    }

    #[test]
    fn cache_names() {
        assert_eq!(cache_file_name("Ada Lovelace"), "card-Ada-Lovelace.vcf");
        assert_eq!(cache_file_name("  "), "card-contact.vcf");
    }
}
