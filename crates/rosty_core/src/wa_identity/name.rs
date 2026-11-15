//! The name shown for a WhatsApp person.

use serde::{Deserialize, Serialize};

/// Where a shown name came from, weakest first, so a stronger tier compares
/// greater.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NameTier {
    /// Nothing is known: the text is the caller's "WhatsApp user" label.
    Unknown,
    /// The phone number, formatted by the caller.
    Number,
    /// The name stored with the chat.
    StoredChat,
    /// The name the person set on WhatsApp.
    Push,
    /// The verified business name.
    Verified,
    /// The name in the device's contacts.
    Contact,
}

/// What is known about a person, each blank or missing when unknown.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct NameInputs<'a> {
    /// The device contact matched by any number of the person.
    pub contact: Option<&'a str>,
    /// The verified business name.
    pub verified: Option<&'a str>,
    /// The push name.
    pub push: Option<&'a str>,
    /// The name stored with the chat.
    pub stored_chat: Option<&'a str>,
    /// The current number, already formatted for reading. Never a LID.
    pub number: Option<&'a str>,
}

/// A name to show and where it came from.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DisplayName {
    /// The text to show, trimmed.
    pub text: String,
    /// Where the text came from.
    pub tier: NameTier,
}

/// The name of a person: contact, verified name, push name, stored chat
/// name, number, else `unknown_label` (the localized "WhatsApp user").
///
/// Blank values are skipped. The function never sees an id, so a LID with
/// nothing known shows `unknown_label` and never its digits.
#[must_use]
pub fn display_name(inputs: &NameInputs<'_>, unknown_label: &str) -> DisplayName {
    let candidates = [
        (inputs.contact, NameTier::Contact),
        (inputs.verified, NameTier::Verified),
        (inputs.push, NameTier::Push),
        (inputs.stored_chat, NameTier::StoredChat),
        (inputs.number, NameTier::Number),
    ];
    candidates
        .into_iter()
        .find_map(|(text, tier)| {
            let text = text?.trim();
            (!text.is_empty()).then(|| DisplayName {
                text: text.to_owned(),
                tier,
            })
        })
        .unwrap_or_else(|| DisplayName {
            text: unknown_label.to_owned(),
            tier: NameTier::Unknown,
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    const LABEL: &str = "WhatsApp user";

    fn full() -> NameInputs<'static> {
        NameInputs {
            contact: Some("Ayla Demir"),
            verified: Some("Demir Ltd"),
            push: Some("ayla"),
            stored_chat: Some("Ayla (work)"),
            number: Some("+90 555 000 00 01"),
        }
    }

    fn shown(inputs: &NameInputs<'_>) -> (String, NameTier) {
        let name = display_name(inputs, LABEL);
        (name.text, name.tier)
    }

    #[test]
    fn order_is_contact_verified_push_stored_number_unknown() {
        let mut inputs = full();
        assert_eq!(shown(&inputs), ("Ayla Demir".to_owned(), NameTier::Contact));
        inputs.contact = None;
        assert_eq!(shown(&inputs), ("Demir Ltd".to_owned(), NameTier::Verified));
        inputs.verified = None;
        assert_eq!(shown(&inputs), ("ayla".to_owned(), NameTier::Push));
        inputs.push = None;
        assert_eq!(
            shown(&inputs),
            ("Ayla (work)".to_owned(), NameTier::StoredChat)
        );
        inputs.stored_chat = None;
        assert_eq!(
            shown(&inputs),
            ("+90 555 000 00 01".to_owned(), NameTier::Number)
        );
        inputs.number = None;
        assert_eq!(shown(&inputs), (LABEL.to_owned(), NameTier::Unknown));
    }

    #[test]
    fn blank_values_are_skipped() {
        let inputs = NameInputs {
            contact: Some("  "),
            verified: Some(""),
            push: Some("\t\n"),
            stored_chat: Some("  Ayla  "),
            number: Some("+90 555 000 00 01"),
        };
        assert_eq!(shown(&inputs), ("Ayla".to_owned(), NameTier::StoredChat));
    }

    #[test]
    fn lid_with_nothing_known_shows_the_label_only() {
        // A hidden member: no contact, no names, no number. The LID digits
        // are not an input, so they cannot be shown.
        let name = display_name(&NameInputs::default(), "WhatsApp kullanıcısı");
        assert_eq!(name.tier, NameTier::Unknown);
        assert_eq!(name.text, "WhatsApp kullanıcısı");
        assert!(!name.text.bytes().any(|b| b.is_ascii_digit()));
    }

    #[test]
    fn lid_with_a_push_name_shows_it() {
        let inputs = NameInputs {
            push: Some("mert"),
            ..NameInputs::default()
        };
        assert_eq!(shown(&inputs), ("mert".to_owned(), NameTier::Push));
    }

    #[test]
    fn contact_saved_later_wins() {
        let mut inputs = NameInputs {
            push: Some("ayla"),
            number: Some("+90 555 000 00 01"),
            ..NameInputs::default()
        };
        assert_eq!(shown(&inputs).1, NameTier::Push);
        inputs.contact = Some("Ayla Demir");
        assert_eq!(shown(&inputs), ("Ayla Demir".to_owned(), NameTier::Contact));
    }

    #[test]
    fn verified_name_beats_push_name() {
        let inputs = NameInputs {
            verified: Some("Demir Ltd"),
            push: Some("demir"),
            ..NameInputs::default()
        };
        assert_eq!(shown(&inputs), ("Demir Ltd".to_owned(), NameTier::Verified));
    }

    #[test]
    fn tiers_compare_by_strength() {
        let order = [
            NameTier::Unknown,
            NameTier::Number,
            NameTier::StoredChat,
            NameTier::Push,
            NameTier::Verified,
            NameTier::Contact,
        ];
        assert!(order.windows(2).all(|pair| pair[0] < pair[1]));
    }

    #[test]
    fn tier_wire_names() {
        assert_eq!(
            serde_json::to_string(&NameTier::StoredChat).expect("serializes"),
            "\"stored_chat\""
        );
    }
}
