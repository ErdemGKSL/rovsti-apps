//! What the chat header offers to copy: name, number or WhatsApp ID.

use crate::text_detect::same_number;

/// One entry of the copy menu.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct CopyChoice {
    /// Menu label.
    pub label: &'static str,
    /// Text for the clipboard.
    pub text: String,
    /// Feedback shown after copying.
    pub notice: &'static str,
}

/// A phone-number JID: digits-only user (device suffix allowed) on
/// `s.whatsapp.net`.
fn has_number(jid: &str) -> bool {
    let Some((local, server)) = jid.split_once('@') else {
        return false;
    };
    let user = local.split(':').next().unwrap_or(local);
    server == "s.whatsapp.net" && !user.is_empty() && user.bytes().all(|b| b.is_ascii_digit())
}

/// The copy choices for a chat header. Name is left out when blank or just
/// the number; the WhatsApp ID is offered only for JIDs without a number
/// (group, LID, channel), since a number JID is the number already.
pub fn header_choices(name: &str, number: Option<&str>, wa_id: Option<&str>) -> Vec<CopyChoice> {
    let clean = |s: &str| {
        let t = s.trim();
        (!t.is_empty()).then(|| t.to_owned())
    };
    let number = number.and_then(clean);
    let mut out = Vec::new();
    if let Some(name) = clean(name)
        && !number.as_deref().is_some_and(|n| same_number(&name, n))
    {
        out.push(CopyChoice {
            label: "Copy name",
            text: name,
            notice: "Name copied",
        });
    }
    if let Some(number) = number {
        out.push(CopyChoice {
            label: "Copy number",
            text: number,
            notice: "Number copied",
        });
    }
    if let Some(id) = wa_id.and_then(clean)
        && !has_number(&id)
    {
        out.push(CopyChoice {
            label: "Copy WhatsApp ID",
            text: id,
            notice: "ID copied",
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn labels(v: &[CopyChoice]) -> Vec<&'static str> {
        v.iter().map(|c| c.label).collect()
    }

    #[test]
    fn sms_contact_has_name_and_number() {
        let v = header_choices("Ayse", Some("+90 532 123 45 67"), None);
        assert_eq!(labels(&v), ["Copy name", "Copy number"]);
        assert_eq!(v[0].text, "Ayse");
        assert_eq!(v[0].notice, "Name copied");
        assert_eq!(v[1].text, "+90 532 123 45 67");
        assert_eq!(v[1].notice, "Number copied");
    }

    #[test]
    fn unknown_number_is_only_the_number() {
        let v = header_choices("+90 532 123 45 67", Some("05321234567"), None);
        assert_eq!(labels(&v), ["Copy number"]);
        assert_eq!(
            labels(&header_choices("  ", Some("05321234567"), None)),
            ["Copy number"]
        );
    }

    #[test]
    fn number_jid_adds_no_id() {
        let v = header_choices(
            "Ayse",
            Some("+905321234567"),
            Some("905321234567@s.whatsapp.net"),
        );
        assert_eq!(labels(&v), ["Copy name", "Copy number"]);
        let v = header_choices(
            "Ayse",
            Some("+905321234567"),
            Some("905321234567:12@s.whatsapp.net"),
        );
        assert_eq!(labels(&v), ["Copy name", "Copy number"]);
    }

    #[test]
    fn group_lid_and_channel_offer_the_id() {
        for id in ["123-456@g.us", "98765@lid", "120363@newsletter"] {
            let v = header_choices("Team", None, Some(id));
            assert_eq!(labels(&v), ["Copy name", "Copy WhatsApp ID"], "{id}");
            assert_eq!(v[1].text, id);
            assert_eq!(v[1].notice, "ID copied");
        }
    }

    #[test]
    fn nothing_to_copy() {
        assert!(header_choices("", None, None).is_empty());
        assert!(header_choices(" ", Some(" "), Some("")).is_empty());
    }
}
