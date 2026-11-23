//! The notice lines of group changes ("Ada added Ben", "You left", "Ada changed the subject to "Aile"").
//!
//! The gateway stores each group notification as a line (`SystemNote::Group {action, by, participants, value}`).
//! This picks the line's template and names its people as ids for the app to name: who did it (me, someone, or no
//! one known) and whom it was done to, me marked, at most [`MAX_NAMED`] then a count. A removal of oneself is a
//! "left", an add of oneself (an invite link, an approved request) a "joined". The app turns the template into text in
//! its language.

use serde::{Deserialize, Serialize};

use super::members::same_id;

/// The most people a line names; the rest is a count ("Ada, Ben, Cem and 4 others").
pub const MAX_NAMED: usize = 3;

/// A group change as the gateway stores it (`rosty_wa_proto::SystemNote::Group`).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct GroupNote {
    /// `add`, `remove`, `leave`, `join_link`, `promote`, `demote`, `number`, `subject`, `description`, `picture`,
    /// `announce`, `locked`, `approval`, `link_reset`, `create`, `delete`; another is one a newer gateway names.
    pub action: String,
    /// Who did it: a JID, `me`, or nobody known.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub by: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub participants: Vec<String>,
    /// The new subject or description, `on` / `off` of a setting, `removed` of a picture or description taken away.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<String>,
}

/// Which sentence a line is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Template {
    Added,
    Removed,
    Left,
    Joined,
    Promoted,
    Demoted,
    NumberChanged,
    Subject,
    Description,
    DescriptionRemoved,
    Picture,
    PictureRemoved,
    AnnounceOn,
    AnnounceOff,
    LockedOn,
    LockedOff,
    ApprovalOn,
    ApprovalOff,
    LinkReset,
    Created,
    Deleted,
    /// An action this build does not know: the app shows a neutral "The group changed".
    Other,
}

/// Who did it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Actor {
    Me,
    Someone {
        jid: String,
    },
    /// Not said (an older stanza), or WhatsApp itself.
    Nobody,
}

/// One person the line names.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Target {
    pub jid: String,
    /// The person is me ("you").
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub me: bool,
}

/// A line to show.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Notice {
    pub template: Template,
    pub actor: Actor,
    /// At most [`MAX_NAMED`] people, me first when I am one of them.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub targets: Vec<Target>,
    /// People beyond those named.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub more: usize,
    /// The new subject or description.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<String>,
    /// I am one of the people it was done to (a line that may matter for the composer: "You were removed").
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub about_me: bool,
}

#[allow(clippy::trivially_copy_pass_by_ref)] // serde passes a reference
fn is_zero(n: &usize) -> bool {
    *n == 0
}

/// The [`Notice`] of `note`; `own` are my ids (number and LID). `me` as `by` is me too.
#[must_use]
pub fn notice(note: &GroupNote, own: &[String]) -> Notice {
    let is_me = |id: &str| id == ME || own.iter().any(|o| same_id(o, id));
    let actor = match note.by.as_deref().filter(|b| !b.is_empty()) {
        Some(by) if is_me(by) => Actor::Me,
        Some(by) => Actor::Someone { jid: by.to_owned() },
        None => Actor::Nobody,
    };
    // Each person once, me first.
    let mut people: Vec<Target> = Vec::new();
    for jid in &note.participants {
        if people.iter().any(|t| same_id(&t.jid, jid)) {
            continue;
        }
        people.push(Target {
            jid: jid.clone(),
            me: is_me(jid),
        });
    }
    people.sort_by_key(|t| !t.me);
    let about_me = people.iter().any(|t| t.me);
    let only_self = match (&actor, people.as_slice()) {
        (Actor::Me, [one]) => one.me,
        (Actor::Someone { jid }, [one]) => same_id(jid, &one.jid),
        _ => false,
    };
    let on = note.value.as_deref() != Some("off");
    let removed = note.value.as_deref() == Some("removed");
    let template = match note.action.as_str() {
        "add" if only_self => Template::Joined,
        "add" => Template::Added,
        "join_link" => Template::Joined,
        "remove" if only_self => Template::Left,
        "remove" => Template::Removed,
        "leave" => Template::Left,
        "promote" => Template::Promoted,
        "demote" => Template::Demoted,
        "number" => Template::NumberChanged,
        "subject" => Template::Subject,
        "description" if removed => Template::DescriptionRemoved,
        "description" => Template::Description,
        "picture" if removed => Template::PictureRemoved,
        "picture" => Template::Picture,
        "announce" if on => Template::AnnounceOn,
        "announce" => Template::AnnounceOff,
        "locked" if on => Template::LockedOn,
        "locked" => Template::LockedOff,
        "approval" if on => Template::ApprovalOn,
        "approval" => Template::ApprovalOff,
        "link_reset" => Template::LinkReset,
        "create" => Template::Created,
        "delete" => Template::Deleted,
        _ => Template::Other,
    };
    // A join or leave names the person as the one who acted.
    let (actor, people) = match template {
        Template::Joined | Template::Left if people.len() == 1 && actor == Actor::Nobody => {
            let actor = match people.into_iter().next() {
                Some(person) if person.me => Actor::Me,
                Some(person) => Actor::Someone { jid: person.jid },
                None => Actor::Nobody,
            };
            (actor, Vec::new())
        }
        Template::Joined | Template::Left if only_self => (actor, Vec::new()),
        _ => (actor, people),
    };
    let more = people.len().saturating_sub(MAX_NAMED);
    let targets: Vec<Target> = people.into_iter().take(MAX_NAMED).collect();
    let value = match template {
        Template::Subject | Template::Description => note.value.clone(),
        _ => None,
    };
    Notice {
        template,
        actor,
        targets,
        more,
        value,
        about_me,
    }
}

/// `by` of a change I made through the gateway.
pub const ME: &str = "me";

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    const ADA: &str = "100000000001@s.whatsapp.net";
    const BEN: &str = "100000000002@s.whatsapp.net";
    const ME_PN: &str = "100000000009@s.whatsapp.net";
    const ME_LID: &str = "777000000000009@lid";

    fn own() -> Vec<String> {
        vec![ME_PN.into(), ME_LID.into()]
    }

    fn note(
        action: &str,
        by: Option<&str>,
        participants: &[&str],
        value: Option<&str>,
    ) -> GroupNote {
        GroupNote {
            action: action.into(),
            by: by.map(str::to_owned),
            participants: participants.iter().map(|p| (*p).to_owned()).collect(),
            value: value.map(str::to_owned),
        }
    }

    fn someone(jid: &str) -> Actor {
        Actor::Someone { jid: jid.into() }
    }

    #[test]
    fn adds_name_who_and_whom_with_me_first() {
        let n = notice(&note("add", Some(ADA), &[BEN, ME_LID], None), &own());
        assert_eq!(n.template, Template::Added);
        assert_eq!(n.actor, someone(ADA));
        assert_eq!(
            n.targets,
            vec![
                Target {
                    jid: ME_LID.into(),
                    me: true
                },
                Target {
                    jid: BEN.into(),
                    me: false
                }
            ]
        );
        assert!(n.about_me);
        let mine = notice(&note("add", Some("me"), &[BEN], None), &own());
        assert_eq!(mine.actor, Actor::Me);
        assert!(!mine.about_me);
    }

    #[test]
    fn many_people_are_named_up_to_three_then_counted() {
        let many: Vec<String> = (1..=7)
            .map(|n| format!("10000000001{n}@s.whatsapp.net"))
            .collect();
        let refs: Vec<&str> = many.iter().map(String::as_str).collect();
        let n = notice(&note("add", Some(ADA), &refs, None), &own());
        assert_eq!(n.targets.len(), MAX_NAMED);
        assert_eq!(n.more, 4);
        // The same person twice (device suffix) is one.
        let twice = notice(
            &note(
                "remove",
                Some(ADA),
                &[BEN, "100000000002:3@s.whatsapp.net"],
                None,
            ),
            &own(),
        );
        assert_eq!(twice.targets.len(), 1);
    }

    #[test]
    fn removing_oneself_is_leaving_and_adding_oneself_is_joining() {
        let left = notice(&note("remove", Some(ADA), &[ADA], None), &own());
        assert_eq!(
            (left.template, left.actor, left.targets.len()),
            (Template::Left, someone(ADA), 0)
        );
        let left_no_actor = notice(&note("remove", None, &[BEN], None), &own());
        assert_eq!(left_no_actor.template, Template::Removed);
        let i_left = notice(&note("leave", None, &[ME_PN], None), &own());
        assert_eq!((i_left.template, i_left.actor), (Template::Left, Actor::Me));
        let joined = notice(&note("join_link", None, &[BEN], None), &own());
        assert_eq!(
            (joined.template, joined.actor),
            (Template::Joined, someone(BEN))
        );
        let self_add = notice(&note("add", Some(BEN), &[BEN], None), &own());
        assert_eq!(self_add.template, Template::Joined);
        let removed_me = notice(&note("remove", Some(ADA), &[ME_LID], None), &own());
        assert_eq!(removed_me.template, Template::Removed);
        assert!(removed_me.about_me);
    }

    #[test]
    fn settings_and_info_lines() {
        let subject = notice(&note("subject", Some(ADA), &[], Some("Aile")), &own());
        assert_eq!(
            (subject.template, subject.value.as_deref()),
            (Template::Subject, Some("Aile"))
        );
        let gone = notice(
            &note("description", Some(ADA), &[], Some("removed")),
            &own(),
        );
        assert_eq!(
            (gone.template, gone.value),
            (Template::DescriptionRemoved, None)
        );
        assert_eq!(
            notice(&note("picture", Some(ME_PN), &[], None), &own()).actor,
            Actor::Me
        );
        assert_eq!(
            notice(&note("picture", None, &[], Some("removed")), &own()).template,
            Template::PictureRemoved
        );
        for (action, on, off) in [
            ("announce", Template::AnnounceOn, Template::AnnounceOff),
            ("locked", Template::LockedOn, Template::LockedOff),
            ("approval", Template::ApprovalOn, Template::ApprovalOff),
        ] {
            assert_eq!(
                notice(&note(action, None, &[], Some("on")), &own()).template,
                on
            );
            assert_eq!(
                notice(&note(action, None, &[], Some("off")), &own()).template,
                off
            );
        }
        assert_eq!(
            notice(&note("promote", Some(ADA), &[ME_PN], None), &own()).template,
            Template::Promoted
        );
        assert_eq!(
            notice(&note("future", None, &[], None), &own()).template,
            Template::Other
        );
    }

    #[test]
    fn a_note_round_trips_as_json() {
        let n = note("add", Some(ADA), &[BEN], None);
        let json = serde_json::to_string(&n).unwrap();
        assert_eq!(
            json,
            r#"{"action":"add","by":"100000000001@s.whatsapp.net","participants":["100000000002@s.whatsapp.net"]}"#
        );
        assert_eq!(serde_json::from_str::<GroupNote>(&json).unwrap(), n);
        let out = serde_json::to_value(notice(&n, &own())).unwrap();
        assert_eq!(out["template"], "added");
        assert_eq!(out["actor"]["type"], "someone");
    }
}
