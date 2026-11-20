//! Group metadata, results and notifications to gateway types. Pure.

use rosty_core::wa_group::{CommunityKind, Role, community_kind};
use rosty_wa_proto::{
    ADDRESSING_LID, ADDRESSING_PN, CommunityRef, DELETED_BY_ME, GroupInfo, InviteCode,
    InvitePreview, Participant, ParticipantResult, Subgroup, SystemNote,
};

use super::lid::{self, LidMap, Pair};
use crate::driver::DriverError;
use whatsapp_rust::IqError;
use whatsapp_rust::features::{
    CommunityError, CommunitySubgroup, GroupError, GroupMetadata, MemberAddMode,
    ParticipantChangeResponse, ParticipantType,
};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::stanza::groups::{GroupNotificationAction, GroupParticipantInfo};
use whatsapp_rust::wacore::types::message::AddressingMode;

/// One group member as WhatsApp lists them: the id the group addresses them
/// by, and their other id when the group gives it.
#[derive(Clone, Copy, Debug)]
pub struct Member<'a> {
    pub jid: &'a Jid,
    pub phone_number: Option<&'a Jid>,
    pub lid: Option<&'a Jid>,
    pub role: Role,
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
        admin: member.role.is_admin(),
        name: None,
        role: Some(member.role.wire().to_owned()),
    }
}

/// The role of a library participant type.
pub fn role_of(kind: ParticipantType) -> Role {
    match kind {
        ParticipantType::SuperAdmin => Role::SuperAdmin,
        ParticipantType::Admin => Role::Admin,
        ParticipantType::Member => Role::Member,
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
        ..GroupInfo::default()
    }
}

/// A person a creator, a subject owner or a notification names: the number
/// when WhatsApp gives it, else the id resolved through `map`.
fn person(jid: &Jid, phone_number: Option<&Jid>, map: &LidMap) -> String {
    participant_jid(jid, phone_number, map)
}

/// The community wire name of a kind.
fn community_wire(kind: CommunityKind) -> &'static str {
    match kind {
        CommunityKind::Parent => "parent",
        CommunityKind::Announcement => "announcement",
        CommunityKind::General => "general",
        CommunityKind::Sub => "sub",
    }
}

/// A group's place in its community, from the metadata flags.
pub fn community_of(meta: &GroupMetadata) -> Option<CommunityRef> {
    let kind = community_kind(
        meta.is_parent_group,
        meta.parent_group_jid.is_some(),
        meta.is_default_sub_group,
        meta.is_general_chat,
    )?;
    Some(CommunityRef {
        kind: community_wire(kind).to_owned(),
        parent: meta
            .parent_group_jid
            .as_ref()
            .map(|p| p.to_non_ad().to_string()),
    })
}

/// Adds what the info page shows beyond the members (E6-WA R6): creation,
/// creator, the settings, timer, size and community.
pub fn add_details(info: &mut GroupInfo, meta: &GroupMetadata, map: &LidMap) {
    info.created_ms = meta
        .creation_time
        .and_then(|s| i64::try_from(s).ok())
        .map(|s| s.saturating_mul(1000));
    info.creator = meta
        .creator
        .as_ref()
        .map(|c| person(c, meta.creator_pn.as_ref(), map));
    info.description_id.clone_from(&meta.description_id);
    info.locked = meta.is_locked;
    info.approval = meta.membership_approval;
    info.admins_add = meta.member_add_mode == Some(MemberAddMode::AdminAdd);
    info.ephemeral_s = meta
        .ephemeral
        .as_ref()
        .and_then(|e| e.expiration)
        .filter(|s| *s > 0);
    info.size = meta
        .size
        .or_else(|| u32::try_from(meta.participants.len()).ok());
    info.community = community_of(meta);
    // A community's announcement group sends only from admins, whatever the
    // flag says.
    if info
        .community
        .as_ref()
        .is_some_and(|c| c.kind == "announcement")
    {
        info.announce = true;
    }
}

/// What an invite link leads to.
pub fn preview_of(meta: &GroupMetadata) -> InvitePreview {
    InvitePreview {
        jid: meta.id.to_non_ad().to_string(),
        subject: meta.subject.clone(),
        size: meta.size.or_else(|| {
            u32::try_from(meta.participants.len())
                .ok()
                .filter(|n| *n > 0)
        }),
        description: meta
            .description
            .as_deref()
            .map(str::trim)
            .filter(|d| !d.is_empty())
            .map(str::to_owned),
        approval: meta.membership_approval,
        community: community_of(meta),
    }
}

/// One group of a community.
pub fn subgroup_of(group: &CommunitySubgroup) -> Subgroup {
    Subgroup {
        jid: group.id.to_non_ad().to_string(),
        subject: group.subject.clone(),
        size: group.participant_count,
        announcement: group.is_default_sub_group,
        general: group.is_general_chat,
    }
}

/// One person's result of a bulk action, named by number when WhatsApp
/// gives it.
pub fn result_of(response: &ParticipantChangeResponse, map: &LidMap) -> ParticipantResult {
    ParticipantResult {
        jid: person(&response.jid, response.phone_number.as_ref(), map),
        error: response.error.clone(),
        invite: response.add_request.as_ref().map(|r| InviteCode {
            code: r.code.clone(),
            expiration: i64::try_from(r.expiration).unwrap_or(i64::MAX),
        }),
    }
}

/// WhatsApp's answer to a group call: a refusal (not an admin, not a member,
/// changed elsewhere) is [`DriverError::Refused`], an unknown or reset invite
/// [`DriverError::NotFound`], a rate limit [`DriverError::RateLimited`].
pub fn group_failed(e: GroupError) -> DriverError {
    match e {
        GroupError::Iq(iq) => iq_failed(iq),
        GroupError::DescriptionConflict => {
            DriverError::Refused("the description changed since it was read".into())
        }
        GroupError::InvalidRequest(msg) => DriverError::BadRequest(msg),
        other => DriverError::Failed(other.to_string()),
    }
}

/// A community call's failure, as [`group_failed`].
pub fn community_failed(e: CommunityError) -> DriverError {
    match e {
        CommunityError::Iq(iq) => iq_failed(iq),
        other => DriverError::Failed(other.to_string()),
    }
}

fn iq_failed(e: IqError) -> DriverError {
    match e {
        IqError::ServerError { code, backoff, .. } => match code {
            429 => DriverError::RateLimited {
                retry_after_s: backoff,
            },
            404 | 410 => DriverError::NotFound(format!("WhatsApp does not know it ({code})")),
            400 => DriverError::BadRequest(format!("WhatsApp refused the request ({code})")),
            401 | 403 | 405 | 406 | 409 | 419 => {
                DriverError::Refused(format!("WhatsApp refused the change ({code})"))
            }
            _ => DriverError::Failed(e.to_string()),
        },
        IqError::NotConnected => DriverError::NotConnected,
        other => DriverError::Failed(other.to_string()),
    }
}

/// The people of a notification, by number when known.
fn people(participants: &[GroupParticipantInfo], map: &LidMap) -> Vec<String> {
    participants
        .iter()
        .map(|p| person(&p.jid, p.phone_number.as_ref(), map))
        .collect()
}

/// The ids of the people a notification names, as library JIDs, with their
/// numbers: to tell whether it is about me.
pub fn named(action: &GroupNotificationAction) -> Vec<&Jid> {
    let list = match action {
        GroupNotificationAction::Add { participants, .. }
        | GroupNotificationAction::Remove { participants, .. }
        | GroupNotificationAction::Promote { participants }
        | GroupNotificationAction::Demote { participants } => participants.as_slice(),
        _ => &[],
    };
    list.iter()
        .flat_map(|p| [Some(&p.jid), p.phone_number.as_ref(), p.lid.as_ref()])
        .flatten()
        .collect()
}

/// The line a group notification leaves in the chat (E6-WA R6), by `by`
/// ([`DELETED_BY_ME`] for me). `None` for a change with no line (the timer
/// has its own, requests and community links have none).
pub fn note_of(
    action: &GroupNotificationAction,
    by: Option<String>,
    map: &LidMap,
) -> Option<SystemNote> {
    use GroupNotificationAction as A;
    let on = |b: bool| Some(if b { "on" } else { "off" }.to_owned());
    let (name, participants, value) = match action {
        A::Add {
            participants,
            reason,
        } => {
            let joined = reason.as_deref() == Some("invite");
            let name = if joined { "join_link" } else { "add" };
            (name, people(participants, map), None)
        }
        A::Remove { participants, .. } => ("remove", people(participants, map), None),
        A::Promote { participants } => ("promote", people(participants, map), None),
        A::Demote { participants } => ("demote", people(participants, map), None),
        A::Modify { participants } => ("number", people(participants, map), None),
        A::Subject { subject, .. } if !subject.is_empty() => {
            ("subject", Vec::new(), Some(subject.clone()))
        }
        A::Description { description, .. } => (
            "description",
            Vec::new(),
            Some(
                description
                    .as_deref()
                    .map(str::trim)
                    .filter(|d| !d.is_empty())
                    .unwrap_or("removed")
                    .to_owned(),
            ),
        ),
        A::Locked { .. } => ("locked", Vec::new(), on(true)),
        A::Unlocked => ("locked", Vec::new(), on(false)),
        A::Announce => ("announce", Vec::new(), on(true)),
        A::NotAnnounce => ("announce", Vec::new(), on(false)),
        A::MembershipApprovalMode { enabled } => ("approval", Vec::new(), on(*enabled)),
        A::RevokeInvite => ("link_reset", Vec::new(), None),
        A::Invite { .. } => ("join_link", by.iter().cloned().collect(), None),
        A::Create { .. } => ("create", Vec::new(), None),
        A::Delete { .. } => ("delete", Vec::new(), None),
        _ => return None,
    };
    Some(SystemNote::Group {
        action: name.to_owned(),
        by,
        participants,
        value,
    })
}

/// `by` of a note: me, else the person by number when known.
pub fn note_by(
    participant: Option<&Jid>,
    participant_pn: Option<&Jid>,
    own: &[&Jid],
    map: &LidMap,
) -> Option<String> {
    let actor = participant.or(participant_pn)?;
    let mine = [Some(actor), participant_pn]
        .into_iter()
        .flatten()
        .any(|j| own.iter().any(|o| o.user == j.user && o.server == j.server));
    Some(if mine {
        DELETED_BY_ME.to_owned()
    } else {
        person(actor, participant_pn, map)
    })
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
            role: Role::Member,
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
            role: Some("member".into()),
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
                role: Some("member".into()),
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
                role: Some("member".into()),
            }
        );
        let admin = Member {
            role: Role::SuperAdmin,
            ..member(&pn, None, None)
        };
        let named = participant(&admin, &none);
        assert!(named.admin);
        assert_eq!(named.role.as_deref(), Some("superadmin"));
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
            role: None,
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

    fn info(jid: &str, pn: Option<&str>) -> GroupParticipantInfo {
        GroupParticipantInfo {
            jid: self::jid(jid),
            phone_number: pn.map(self::jid),
            display_name: None,
            r#type: None,
            lid: None,
            username: None,
            join_time: None,
            group_history_sent_state: None,
        }
    }

    fn group_note(
        note: Option<SystemNote>,
    ) -> (String, Option<String>, Vec<String>, Option<String>) {
        match note {
            Some(SystemNote::Group {
                action,
                by,
                participants,
                value,
            }) => (action, by, participants, value),
            other => panic!("not a group line: {other:?}"),
        }
    }

    #[test]
    fn notifications_become_lines_with_people_by_number() {
        let map = LidMap::from_entries([("777000000000002", "100000000002")]);
        let by = Some("100000000001@s.whatsapp.net".to_owned());
        let add = GroupNotificationAction::Add {
            participants: vec![
                info("777000000000002@lid", None),
                info("777000000000003@lid", Some("100000000003@s.whatsapp.net")),
                info("777000000000004@lid", None),
            ],
            reason: None,
        };
        let (action, who, people, value) = group_note(note_of(&add, by.clone(), &map));
        assert_eq!((action.as_str(), who, value), ("add", by.clone(), None));
        assert_eq!(
            people,
            vec![
                "100000000002@s.whatsapp.net",
                "100000000003@s.whatsapp.net",
                // A member known only by LID stays a LID.
                "777000000000004@lid"
            ]
        );
        let link = GroupNotificationAction::Add {
            participants: vec![info("100000000005@s.whatsapp.net", None)],
            reason: Some("invite".into()),
        };
        assert_eq!(group_note(note_of(&link, None, &map)).0, "join_link");
        let gone = GroupNotificationAction::Description {
            id: "d1".into(),
            description: None,
        };
        assert_eq!(
            group_note(note_of(&gone, None, &map)).3.as_deref(),
            Some("removed")
        );
        let approval = GroupNotificationAction::MembershipApprovalMode { enabled: true };
        assert_eq!(
            group_note(note_of(&approval, None, &map)),
            ("approval".into(), None, vec![], Some("on".into()))
        );
        assert_eq!(
            group_note(note_of(&GroupNotificationAction::NotAnnounce, None, &map)).3,
            Some("off".into())
        );
        // The timer has its own line; requests have none.
        let timer = GroupNotificationAction::Ephemeral {
            expiration: 86_400,
            trigger: None,
        };
        assert!(note_of(&timer, None, &map).is_none());
    }

    #[test]
    fn who_acted_is_me_or_their_number() {
        let me = jid("100000000009@s.whatsapp.net");
        let my_lid = jid("777000000000009@lid");
        let own = [&me, &my_lid];
        let map = LidMap::from_entries([("777000000000001", "100000000001")]);
        assert_eq!(
            note_by(Some(&jid("777000000000009:2@lid")), None, &own, &map).as_deref(),
            Some(DELETED_BY_ME)
        );
        assert_eq!(
            note_by(Some(&jid("777000000000001@lid")), None, &own, &map).as_deref(),
            Some("100000000001@s.whatsapp.net")
        );
        assert_eq!(
            note_by(
                Some(&jid("777000000000005@lid")),
                Some(&jid("100000000005@s.whatsapp.net")),
                &own,
                &map
            )
            .as_deref(),
            Some("100000000005@s.whatsapp.net")
        );
        assert_eq!(note_by(None, None, &own, &map), None);
        let remove = GroupNotificationAction::Remove {
            participants: vec![info(
                "777000000000009@lid",
                Some("100000000009@s.whatsapp.net"),
            )],
            reason: None,
        };
        assert!(named(&remove).iter().any(|j| j.user == "100000000009"));
    }

    #[test]
    fn whatsapp_refusals_map_to_http_answers() {
        let server = |code| IqError::ServerError {
            code,
            text: String::new(),
            error_type: None,
            backoff: Some(30),
        };
        assert!(matches!(
            group_failed(GroupError::Iq(server(403))),
            DriverError::Refused(_)
        ));
        assert!(matches!(
            group_failed(GroupError::Iq(server(410))),
            DriverError::NotFound(_)
        ));
        assert!(matches!(
            group_failed(GroupError::Iq(server(404))),
            DriverError::NotFound(_)
        ));
        assert!(matches!(
            group_failed(GroupError::Iq(server(429))),
            DriverError::RateLimited {
                retry_after_s: Some(30)
            }
        ));
        assert!(matches!(
            group_failed(GroupError::DescriptionConflict),
            DriverError::Refused(_)
        ));
        assert!(matches!(
            group_failed(GroupError::InvalidRequest("x".into())),
            DriverError::BadRequest(_)
        ));
        assert!(matches!(
            group_failed(GroupError::Iq(IqError::NotConnected)),
            DriverError::NotConnected
        ));
    }
}
