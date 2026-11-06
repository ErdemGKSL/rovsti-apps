//! What to call a WhatsApp person or group when the address book may not
//! know them: the contact name, then WhatsApp's own name for them, then the
//! number.

use rovsti_wa_proto::{WaProfile, jid_user};

fn non_empty(name: Option<&str>) -> Option<&str> {
    name.map(str::trim).filter(|n| !n.is_empty())
}

/// `905321234567@s.whatsapp.net` → `+905321234567`. Groups and other JIDs
/// without a phone number give their user part as is; an empty JID gives "".
pub fn number_label(jid: &str) -> String {
    let user = jid_user(jid);
    if user.is_empty() {
        String::new()
    } else if jid.ends_with("@s.whatsapp.net") && user.bytes().all(|b| b.is_ascii_digit()) {
        format!("+{user}")
    } else {
        user.to_owned()
    }
}

/// The name to show for `jid`: `contact` (the address book's name), else
/// `wa_name` (a chat name, a push name or [`WaProfile::display_name`]),
/// else the number.
pub fn display_name(contact: Option<&str>, wa_name: Option<&str>, jid: &str) -> String {
    non_empty(contact)
        .or_else(|| non_empty(wa_name))
        .map_or_else(|| number_label(jid), str::to_owned)
}

/// [`display_name`] with WhatsApp's name taken from a profile.
pub fn display_name_with_profile(
    contact: Option<&str>,
    profile: Option<&WaProfile>,
    jid: &str,
) -> String {
    display_name(contact, profile.and_then(WaProfile::display_name), jid)
}

#[cfg(test)]
mod tests {
    use super::*;

    const J: &str = "905321234567@s.whatsapp.net";

    #[test]
    fn contact_then_whatsapp_then_number() {
        assert_eq!(display_name(Some("Ayşe"), Some("ayse"), J), "Ayşe");
        assert_eq!(display_name(Some("  "), Some("ayse"), J), "ayse");
        assert_eq!(display_name(None, None, J), "+905321234567");
        assert_eq!(
            display_name(None, Some(""), "9053:4@s.whatsapp.net"),
            "+9053"
        );
    }

    #[test]
    fn profiles_pick_their_best_name() {
        let profile = WaProfile {
            jid: J.into(),
            push_name: Some("ayse".into()),
            contact_name: Some("Ayşe Y".into()),
            ..WaProfile::default()
        };
        assert_eq!(display_name_with_profile(None, Some(&profile), J), "Ayşe Y");
        assert_eq!(
            display_name_with_profile(Some("Ayşe Yılmaz"), Some(&profile), J),
            "Ayşe Yılmaz"
        );
        assert_eq!(display_name_with_profile(None, None, J), "+905321234567");
    }

    #[test]
    fn numbers_and_other_jids() {
        assert_eq!(number_label("120363@g.us"), "120363");
        assert_eq!(number_label("abc@lid"), "abc");
        assert_eq!(number_label(""), "");
    }
}
