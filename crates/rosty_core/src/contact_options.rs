//! Per-contact ringtone and "send to voicemail", and the spec of a pinned
//! Home screen shortcut. Pure: the Kotlin side reads and writes the provider.

use serde::{Deserialize, Serialize};

/// What the contacts provider holds for one contact (the Kotlin reply).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ContactOptions {
    /// `None`: default ringtone; `Some("")`: silent.
    pub ringtone_uri: Option<String>,
    pub ringtone_title: Option<String>,
    pub to_voicemail: bool,
}

/// Reads the Kotlin reply; missing fields take their default.
pub fn parse(json: &str) -> Result<ContactOptions, serde_json::Error> {
    serde_json::from_str(json)
}

/// The ringtone as the card shows it.
pub fn ringtone_text(o: &ContactOptions) -> String {
    match o.ringtone_uri.as_deref() {
        None => "Default".to_owned(),
        Some("") => "Silent".to_owned(),
        Some(_) => match o.ringtone_title.as_deref().map(str::trim) {
            Some(t) if !t.is_empty() => t.to_owned(),
            _ => "Custom".to_owned(),
        },
    }
}

/// Title and body of the confirmation before sending calls to voicemail.
pub fn voicemail_confirm(name: &str) -> (String, String) {
    let name = name.trim();
    let who = if name.is_empty() {
        "this contact"
    } else {
        name
    };
    (
        format!("Send {}'s calls to voicemail?", first_word(who)),
        "Their calls won't ring this phone.".to_owned(),
    )
}

/// Feedback after a ringtone change.
pub fn ringtone_notice(o: &ContactOptions) -> String {
    match ringtone_text(o).as_str() {
        "Default" => "Ringtone reset to default".to_owned(),
        "Silent" => "Ringtone set to silent".to_owned(),
        t => format!("Ringtone set to {t}"),
    }
}

/// Feedback after the voicemail switch.
pub fn voicemail_notice(on: bool) -> &'static str {
    if on {
        "Calls go to voicemail"
    } else {
        "Calls ring again"
    }
}

/// A launcher shortcut that opens a contact card.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct PinShortcut {
    pub id: String,
    pub short_label: String,
    pub long_label: String,
    pub contact_id: i64,
    pub icon_path: Option<String>,
}

const SHORT_MAX: usize = 10;
const LONG_MAX: usize = 25;

fn first_word(s: &str) -> &str {
    s.split_whitespace().next().unwrap_or("")
}

/// `s` cut to at most `max` characters, on a char boundary.
fn clip(s: &str, max: usize) -> &str {
    match s.char_indices().nth(max) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}

/// The shortcut for a contact. The id is stable per contact, so pinning twice
/// updates the same shortcut.
pub fn shortcut(
    contact_id: i64,
    lookup_key: Option<&str>,
    name: &str,
    icon_path: Option<&str>,
) -> PinShortcut {
    let name = name.trim();
    let key = lookup_key
        .map(str::trim)
        .filter(|k| !k.is_empty())
        .map_or_else(|| contact_id.to_string(), str::to_owned);
    let (short, long) = if name.is_empty() {
        ("Contact", "Contact")
    } else {
        (clip(first_word(name), SHORT_MAX), clip(name, LONG_MAX))
    };
    PinShortcut {
        id: format!("contact-{key}"),
        short_label: short.trim_end().to_owned(),
        long_label: long.trim_end().to_owned(),
        contact_id,
        icon_path: icon_path.map(str::to_owned),
    }
}

/// Feedback after asking the launcher to pin.
pub fn pin_notice(ok: bool) -> &'static str {
    if ok {
        "Added to Home screen"
    } else {
        "Your launcher can't add shortcuts"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_fills_missing_fields() {
        assert_eq!(parse("{}").expect("ok"), ContactOptions::default());
        let o =
            parse(r#"{"ringtone_uri":"content://x","to_voicemail":true,"extra":1}"#).expect("ok");
        assert_eq!(o.ringtone_uri.as_deref(), Some("content://x"));
        assert_eq!(o.ringtone_title, None);
        assert!(o.to_voicemail);
        assert!(parse("nope").is_err());
    }

    #[test]
    fn ringtone_texts() {
        let mut o = ContactOptions::default();
        assert_eq!(ringtone_text(&o), "Default");
        o.ringtone_uri = Some(String::new());
        assert_eq!(ringtone_text(&o), "Silent");
        o.ringtone_uri = Some("content://r/1".into());
        assert_eq!(ringtone_text(&o), "Custom");
        o.ringtone_title = Some("  ".into());
        assert_eq!(ringtone_text(&o), "Custom");
        o.ringtone_title = Some("Bells".into());
        assert_eq!(ringtone_text(&o), "Bells");
        assert_eq!(ringtone_notice(&o), "Ringtone set to Bells");
        assert_eq!(
            ringtone_notice(&ContactOptions::default()),
            "Ringtone reset to default"
        );
    }

    #[test]
    fn voicemail_texts() {
        let (t, b) = voicemail_confirm("Ayse Yilmaz");
        assert_eq!(t, "Send Ayse's calls to voicemail?");
        assert_eq!(b, "Their calls won't ring this phone.");
        assert_eq!(voicemail_confirm(" ").0, "Send this's calls to voicemail?");
        assert_ne!(voicemail_notice(true), voicemail_notice(false));
    }

    #[test]
    fn shortcut_ids_and_labels() {
        let s = shortcut(7, Some("0r7-abc"), "Ayşe Yılmaz", Some("/p.png"));
        assert_eq!(s.id, "contact-0r7-abc");
        assert_eq!(s.short_label, "Ayşe");
        assert_eq!(s.long_label, "Ayşe Yılmaz");
        assert_eq!(s.icon_path.as_deref(), Some("/p.png"));
        assert_eq!(shortcut(7, None, "A", None).id, "contact-7");
        assert_eq!(shortcut(7, Some(" "), "A", None).id, "contact-7");
    }

    #[test]
    fn shortcut_labels_clip_on_char_boundaries() {
        let s = shortcut(
            1,
            None,
            "Şebnem-Gülçinİstanbullu Ömer Çağlayan Özdemir",
            None,
        );
        assert_eq!(s.short_label.chars().count(), 10);
        assert_eq!(s.long_label.chars().count(), 25);
        let e = shortcut(1, None, "👩‍👩‍👧‍👦👩‍👩‍👧‍👦👩‍👩‍👧‍👦 party", None);
        assert!(e.short_label.chars().count() <= 10);
        assert!(e.long_label.chars().count() <= 25);
        let blank = shortcut(1, None, "   ", None);
        assert_eq!(
            (blank.short_label.as_str(), blank.long_label.as_str()),
            ("Contact", "Contact")
        );
    }

    #[test]
    fn pin_notices() {
        assert_eq!(pin_notice(true), "Added to Home screen");
        assert_eq!(pin_notice(false), "Your launcher can't add shortcuts");
    }
}
