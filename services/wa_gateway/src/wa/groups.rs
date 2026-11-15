//! Group metadata to gateway types. Pure.

use rosty_wa_proto::{ADDRESSING_LID, ADDRESSING_PN, GroupInfo, Participant};

use super::lid::{self, LidMap, Pair};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::stanza::groups::GroupNotificationAction;
use whatsapp_rust::wacore::types::message::AddressingMode;

/// One group member as WhatsApp lists them: the id the group addresses them
/// by, and their other id when the group gives it.
#[derive(Clone, Copy, Debug)]
pub struct Member<'a> {
    pub jid: &'a Jid,
    pub phone_number: Option<&'a Jid>,
    pub lid: Option<&'a Jid>,
    pub admin: bool,
}

impl Member<'_> {
    /// The pair the member list states for this member, if it names both
    /// their LID and their number.
    pub fn pair(&self) -> Option<Pair> {
        [self.phone_number, self.lid]
            .into_iter()
            .flatten()
            .find_map(|other| Pair::of(self.jid, other))
    }
}

/// The JID the apps should know a participant by: the phone-number JID when
/// the group addresses members by LID (given by the group, else by `map`),
/// otherwise the JID itself.
pub fn participant_jid(jid: &Jid, phone_number: Option<&Jid>, map: &LidMap) -> String {
    match phone_number {
        Some(pn) => pn.to_non_ad(),
        None => lid::resolve(jid, None, map),
    }
    .to_string()
}

/// A member as the apps see them: named by phone number when it is known,
/// with both ids next to it. A member who hides their number has a LID only.
pub fn participant(member: &Member<'_>, map: &LidMap) -> Participant {
    let jid = participant_jid(member.jid, member.phone_number, map);
    let canonical: Option<Jid> = jid.parse().ok();
    let pn = canonical.as_ref().filter(|j| j.is_pn());
    let lid = match pn {
        Some(pn) => lid::alt_of(pn, member.jid, member.lid, map),
        // Still a LID: that is all there is.
        None => canonical
            .as_ref()
            .filter(|j| j.is_lid())
            .map(ToString::to_string),
    };
    Participant {
        pn: pn.map(ToString::to_string),
        lid,
        jid,
        admin: member.admin,
        name: None,
    }
}

/// The wire name of how a group addresses its members.
pub fn addressing(mode: AddressingMode) -> &'static str {
    match mode {
        AddressingMode::Pn => ADDRESSING_PN,
        AddressingMode::Lid => ADDRESSING_LID,
    }
}

/// A group as the apps see it.
pub fn group_info(
    jid: &Jid,
    subject: &str,
    participants: impl IntoIterator<Item = Participant>,
) -> GroupInfo {
    GroupInfo {
        jid: jid.to_non_ad().to_string(),
        subject: subject.to_owned(),
        participants: participants.into_iter().collect(),
        description: None,
        announce: false,
        addressing_mode: None,
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

    fn member<'a>(jid: &'a Jid, phone_number: Option<&'a Jid>, lid: Option<&'a Jid>) -> Member<'a> {
        Member {
            jid,
            phone_number,
            lid,
            admin: false,
        }
    }

    #[test]
    fn phone_number_wins_over_lid() {
        let lid = jid("1234:5@lid");
        let pn = jid("905550000001@s.whatsapp.net");
        assert_eq!(
            participant_jid(&lid, Some(&pn), &LidMap::default()),
            "905550000001@s.whatsapp.net"
        );
        assert_eq!(participant_jid(&lid, None, &LidMap::default()), "1234@lid");
        let known = LidMap::from_entries([("1234", "905550000001")]);
        assert_eq!(
            participant_jid(&lid, None, &known),
            "905550000001@s.whatsapp.net"
        );
    }

    #[test]
    fn members_carry_both_ids_when_known() {
        let lid = jid("1234:5@lid");
        let pn = jid("905550000001@s.whatsapp.net");
        let none = LidMap::default();
        let known = LidMap::from_entries([("1234", "905550000001")]);
        let both = Participant {
            jid: "905550000001@s.whatsapp.net".into(),
            admin: false,
            name: None,
            lid: Some("1234@lid".into()),
            pn: Some("905550000001@s.whatsapp.net".into()),
        };
        // A LID group that gives the number.
        assert_eq!(participant(&member(&lid, Some(&pn), None), &none), both);
        // A LID group that does not, but the gateway knows the pair.
        assert_eq!(participant(&member(&lid, None, None), &known), both);
        // A number group that gives the LID, or whose LID the gateway knows.
        assert_eq!(participant(&member(&pn, None, Some(&lid)), &none), both);
        assert_eq!(participant(&member(&pn, None, None), &known), both);
        // A member who hides their number: a LID and nothing else.
        assert_eq!(
            participant(&member(&lid, None, None), &none),
            Participant {
                jid: "1234@lid".into(),
                admin: false,
                name: None,
                lid: Some("1234@lid".into()),
                pn: None,
            }
        );
        // A number whose LID nobody told.
        assert_eq!(
            participant(&member(&pn, None, None), &none),
            Participant {
                jid: "905550000001@s.whatsapp.net".into(),
                admin: false,
                name: None,
                lid: None,
                pn: Some("905550000001@s.whatsapp.net".into()),
            }
        );
        let admin = Member {
            admin: true,
            ..member(&pn, None, None)
        };
        assert!(participant(&admin, &none).admin);
    }

    #[test]
    fn the_member_list_states_pairs() {
        let lid = jid("1234:5@lid");
        let pn = jid("905550000001:2@s.whatsapp.net");
        let pair = Pair::of(&lid, &pn);
        assert!(pair.is_some());
        assert_eq!(member(&lid, Some(&pn), None).pair(), pair);
        assert_eq!(member(&pn, None, Some(&lid)).pair(), pair);
        assert_eq!(member(&lid, None, None).pair(), None);
        assert_eq!(member(&pn, None, None).pair(), None);
        // The same id twice is no pair.
        assert_eq!(member(&lid, None, Some(&lid)).pair(), None);
    }

    #[test]
    fn addressing_modes_have_wire_names() {
        assert_eq!(addressing(AddressingMode::Pn), "pn");
        assert_eq!(addressing(AddressingMode::Lid), "lid");
    }

    #[test]
    fn group_info_strips_device_suffixes() {
        let plain = |jid: &str, admin| Participant {
            jid: jid.into(),
            admin,
            name: None,
            lid: None,
            pn: None,
        };
        let info = group_info(
            &jid("120363-1@g.us"),
            "Aile",
            [
                plain("905@s.whatsapp.net", true),
                plain("906@s.whatsapp.net", false),
            ],
        );
        assert_eq!(info.jid, "120363-1@g.us");
        assert_eq!(info.subject, "Aile");
        assert_eq!(info.addressing_mode, None);
        assert_eq!(
            info.participants,
            vec![
                plain("905@s.whatsapp.net", true),
                plain("906@s.whatsapp.net", false),
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
