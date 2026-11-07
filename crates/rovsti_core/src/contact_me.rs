//! My card and emergency contacts: finding the contacts by lookup key, the
//! texts the UI shows, and the rules for the emergency list.

use crate::{contacts_prefs::MAX_EMERGENCY, wire::WireContact};

/// Why a contact cannot be added to the emergency list.
#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum EmergencyError {
    #[error("Up to 5 emergency contacts")]
    Full,
    #[error("This contact has no number")]
    NoNumber,
    #[error("This contact has no key yet")]
    NoKey,
}

fn key_of(contact: &WireContact) -> Option<&str> {
    contact.lookup_key.as_deref().filter(|k| !k.is_empty())
}

/// The contact with this lookup key, if it still exists.
pub fn resolve<'a>(key: Option<&str>, all: &'a [WireContact]) -> Option<&'a WireContact> {
    let key = key.filter(|k| !k.is_empty())?;
    all.iter().find(|c| key_of(c) == Some(key))
}

/// Title and subtitle of the pinned row at the top of the list.
pub fn my_card_row(found: Option<&WireContact>) -> (String, String) {
    match found {
        Some(c) => ("My card".to_owned(), c.display_name.clone()),
        None => (
            "Set up my card".to_owned(),
            "Choose the contact that is you".to_owned(),
        ),
    }
}

/// Feedback after setting or removing the card.
pub fn my_card_notice(set: bool) -> &'static str {
    if set {
        "Set as my card"
    } else {
        "My card removed"
    }
}

/// Adds or removes `contact`; returns the new keys and whether it was added.
/// Removing always works, even when the list is full.
pub fn toggle_emergency(
    keys: &[String],
    contact: &WireContact,
) -> Result<(Vec<String>, bool), EmergencyError> {
    let key = key_of(contact).ok_or(EmergencyError::NoKey)?;
    if keys.iter().any(|k| k == key) {
        let rest = keys.iter().filter(|k| *k != key).cloned().collect();
        return Ok((rest, false));
    }
    if !contact.numbers.iter().any(|n| !n.raw.trim().is_empty()) {
        return Err(EmergencyError::NoNumber);
    }
    if keys.len() >= MAX_EMERGENCY {
        return Err(EmergencyError::Full);
    }
    let mut next = keys.to_vec();
    next.push(key.to_owned());
    Ok((next, true))
}

/// The emergency contacts in key order; deleted contacts are dropped.
pub fn emergency_list<'a>(keys: &[String], all: &'a [WireContact]) -> Vec<&'a WireContact> {
    keys.iter().filter_map(|k| resolve(Some(k), all)).collect()
}

/// The share text: a numbered list of `(name, number)`.
pub fn emergency_text(owner: Option<&str>, entries: &[(&str, &str)]) -> String {
    let mut out = match owner.map(str::trim).filter(|o| !o.is_empty()) {
        Some(o) => format!("Emergency contacts for {o}:"),
        None => "Emergency contacts:".to_owned(),
    };
    for (i, (name, number)) in entries.iter().enumerate() {
        out.push_str(&format!("\n{}. {name}: {number}", i + 1));
    }
    out
}

/// Feedback after toggling an emergency contact.
pub fn emergency_notice(added: bool) -> &'static str {
    if added {
        "Added to emergency contacts"
    } else {
        "Removed from emergency contacts"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::WireNumber;

    fn contact(name: &str, key: Option<&str>, number: Option<&str>) -> WireContact {
        WireContact {
            display_name: name.into(),
            lookup_key: key.map(Into::into),
            numbers: number
                .map(|n| WireNumber {
                    raw: n.into(),
                    label: None,
                    default: false,
                })
                .into_iter()
                .collect(),
            ..WireContact::default()
        }
    }

    fn keys(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn resolve_by_key() {
        let all = [contact("A", Some("a"), None), contact("B", Some("b"), None)];
        assert_eq!(
            resolve(Some("b"), &all).map(|c| c.display_name.as_str()),
            Some("B")
        );
        assert!(resolve(Some("zz"), &all).is_none());
        assert!(resolve(None, &all).is_none());
        assert!(resolve(Some(""), &all).is_none());
    }

    #[test]
    fn row_texts() {
        let c = contact("Ayşe Yılmaz", Some("a"), None);
        assert_eq!(
            my_card_row(Some(&c)),
            ("My card".into(), "Ayşe Yılmaz".into())
        );
        assert_eq!(
            my_card_row(None),
            (
                "Set up my card".into(),
                "Choose the contact that is you".into()
            )
        );
        assert_eq!(my_card_notice(true), "Set as my card");
        assert_eq!(my_card_notice(false), "My card removed");
        assert_eq!(emergency_notice(true), "Added to emergency contacts");
        assert_eq!(emergency_notice(false), "Removed from emergency contacts");
    }

    #[test]
    fn toggle_adds_and_removes() {
        let c = contact("A", Some("a"), Some("+905321234567"));
        let (k, added) = toggle_emergency(&keys(&["x"]), &c).expect("adds");
        assert_eq!(k, ["x", "a"]);
        assert!(added);
        let (k, added) = toggle_emergency(&k, &c).expect("removes");
        assert_eq!(k, ["x"]);
        assert!(!added);
    }

    #[test]
    fn toggle_errors() {
        let full = keys(&["1", "2", "3", "4", "5"]);
        let c = contact("A", Some("a"), Some("1"));
        assert_eq!(toggle_emergency(&full, &c), Err(EmergencyError::Full));
        let no_num = contact("A", Some("a"), None);
        assert_eq!(
            toggle_emergency(&[], &no_num),
            Err(EmergencyError::NoNumber)
        );
        let blank = contact("A", Some("a"), Some("  "));
        assert_eq!(toggle_emergency(&[], &blank), Err(EmergencyError::NoNumber));
        let no_key = contact("A", None, Some("1"));
        assert_eq!(toggle_emergency(&[], &no_key), Err(EmergencyError::NoKey));
        assert_eq!(
            EmergencyError::Full.to_string(),
            "Up to 5 emergency contacts"
        );
    }

    #[test]
    fn removing_works_when_full() {
        let full = keys(&["1", "2", "3", "4", "5"]);
        let c = contact("A", Some("3"), None);
        let (k, added) = toggle_emergency(&full, &c).expect("removes");
        assert_eq!(k.len(), 4);
        assert!(!added);
    }

    #[test]
    fn list_keeps_key_order_and_drops_deleted() {
        let all = [
            contact("A", Some("a"), None),
            contact("B", Some("b"), None),
            contact("C", Some("c"), None),
        ];
        let got = emergency_list(&keys(&["c", "gone", "a"]), &all);
        let names: Vec<_> = got.iter().map(|c| c.display_name.as_str()).collect();
        assert_eq!(names, ["C", "A"]);
    }

    #[test]
    fn share_text_with_and_without_owner() {
        let entries = [("Mehmet", "+90 532 000"), ("Zeynep", "+90 533 111")];
        assert_eq!(
            emergency_text(Some("Ayşe Yılmaz"), &entries),
            "Emergency contacts for Ayşe Yılmaz:\n1. Mehmet: +90 532 000\n2. Zeynep: +90 533 111"
        );
        assert_eq!(
            emergency_text(None, &entries[..1]),
            "Emergency contacts:\n1. Mehmet: +90 532 000"
        );
        assert_eq!(emergency_text(Some(" "), &[]), "Emergency contacts:");
    }
}
