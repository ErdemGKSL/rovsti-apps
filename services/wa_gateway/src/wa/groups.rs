//! Group metadata to gateway types. Pure.

use rovsti_wa_proto::{GroupInfo, Participant};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::stanza::groups::GroupNotificationAction;

/// The JID the apps should know a participant by: the phone-number JID when
/// the group addresses members by LID, otherwise the JID itself.
pub fn participant_jid(jid: &Jid, phone_number: Option<&Jid>) -> String {
    phone_number.unwrap_or(jid).to_non_ad().to_string()
}

/// A group as the apps see it.
pub fn group_info(
    jid: &Jid,
    subject: &str,
    participants: impl IntoIterator<Item = (String, bool)>,
) -> GroupInfo {
    GroupInfo {
        jid: jid.to_non_ad().to_string(),
        subject: subject.to_owned(),
        participants: participants
            .into_iter()
            .map(|(jid, admin)| Participant { jid, admin })
            .collect(),
    }
}

/// The new subject when `action` renames the group.
pub fn new_subject(action: &GroupNotificationAction) -> Option<&str> {
    match action {
        GroupNotificationAction::Subject { subject, .. } if !subject.is_empty() => {
            Some(subject.as_str())
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jid(s: &str) -> Jid {
        s.parse().unwrap()
    }

    #[test]
    fn phone_number_wins_over_lid() {
        let lid = jid("1234:5@lid");
        let pn = jid("905321234567@s.whatsapp.net");
        assert_eq!(
            participant_jid(&lid, Some(&pn)),
            "905321234567@s.whatsapp.net"
        );
        assert_eq!(participant_jid(&lid, None), "1234@lid");
    }

    #[test]
    fn group_info_strips_device_suffixes() {
        let info = group_info(
            &jid("120363-1@g.us"),
            "Aile",
            [
                ("905@s.whatsapp.net".to_owned(), true),
                ("906@s.whatsapp.net".to_owned(), false),
            ],
        );
        assert_eq!(info.jid, "120363-1@g.us");
        assert_eq!(info.subject, "Aile");
        assert_eq!(
            info.participants,
            vec![
                Participant {
                    jid: "905@s.whatsapp.net".into(),
                    admin: true
                },
                Participant {
                    jid: "906@s.whatsapp.net".into(),
                    admin: false
                },
            ]
        );
    }

    #[test]
    fn only_subject_changes_rename() {
        let rename = GroupNotificationAction::Subject {
            subject: "Yeni".into(),
            subject_owner: None,
            subject_time: None,
        };
        assert_eq!(new_subject(&rename), Some("Yeni"));
        let empty = GroupNotificationAction::Subject {
            subject: String::new(),
            subject_owner: None,
            subject_time: None,
        };
        assert_eq!(new_subject(&empty), None);
    }
}
