//! Groups on the wire (E6-WA R6): the action route, create, invite preview and join, join requests and a community's
//! groups. All additive: an older gateway answers these routes with the router's 404, and an older app ignores the
//! new fields of [`crate::GroupInfo`].

use rosty_core::wa_group::{self, BulkKind, BulkResult};

use crate::media::decode_b64;

/// The biggest group picture the gateway takes (a 640 px JPEG is far below it).
pub const MAX_GROUP_PICTURE_BYTES: usize = 1024 * 1024;

/// Most people one bulk action names.
pub const MAX_GROUP_PARTICIPANTS: usize = 256;

/// `community.kind` of a [`crate::GroupInfo`] (the `rosty_core::wa_group::CommunityKind` names).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct CommunityRef {
    /// `parent`, `announcement`, `general` or `sub`.
    pub kind: String,
    /// The community (its parent group) of a group in it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent: Option<String>,
}

/// One change of a group (`POST /v1/groups/action`).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum GroupActionKind {
    Add {
        participants: Vec<String>,
    },
    Remove {
        participants: Vec<String>,
    },
    Promote {
        participants: Vec<String>,
    },
    Demote {
        participants: Vec<String>,
    },
    Subject {
        subject: String,
    },
    /// An empty description deletes it.
    Description {
        description: String,
    },
    /// A JPEG (base64); none removes the picture.
    Picture {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        jpeg_b64: Option<String>,
    },
    /// Only admins send.
    Announce {
        on: bool,
    },
    /// Only admins edit the group's info.
    Locked {
        on: bool,
    },
    /// New members need an admin's approval.
    Approval {
        on: bool,
    },
    /// The invite link; `reset` revokes the old one first.
    InviteLink {
        #[serde(default)]
        reset: bool,
    },
    Approve {
        participants: Vec<String>,
    },
    Reject {
        participants: Vec<String>,
    },
    /// Sends WhatsApp's group invite message to someone whose privacy refused an add, with the code the add answered.
    SendInvite {
        to: String,
        code: String,
        expiration: i64,
    },
    Leave,
}

/// Why a group action cannot be sent.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum GroupActionError {
    #[error("no participants")]
    NoParticipants,
    #[error("at most {MAX_GROUP_PARTICIPANTS} participants")]
    TooManyParticipants,
    #[error(
        "the subject is empty or longer than {} characters",
        wa_group::MAX_SUBJECT_CHARS
    )]
    Subject,
    #[error(
        "the description is longer than {} characters",
        wa_group::MAX_DESCRIPTION_CHARS
    )]
    Description,
    #[error("the picture is not base64 or larger than {MAX_GROUP_PICTURE_BYTES} bytes")]
    Picture,
    #[error("the invite code is not valid")]
    Code,
}

fn people(participants: &[String]) -> Result<(), GroupActionError> {
    match participants.len() {
        0 => Err(GroupActionError::NoParticipants),
        n if n > MAX_GROUP_PARTICIPANTS => Err(GroupActionError::TooManyParticipants),
        _ => Ok(()),
    }
}

impl GroupActionKind {
    /// The action as it is sent: a trimmed subject and description; an error for what WhatsApp would refuse.
    pub fn checked(&self) -> Result<Self, GroupActionError> {
        Ok(match self {
            Self::Add { participants }
            | Self::Remove { participants }
            | Self::Promote { participants }
            | Self::Demote { participants }
            | Self::Approve { participants }
            | Self::Reject { participants } => {
                people(participants)?;
                self.clone()
            }
            Self::Subject { subject } => Self::Subject {
                subject: wa_group::check_subject(subject).ok_or(GroupActionError::Subject)?,
            },
            Self::Description { description } => Self::Description {
                description: wa_group::check_description(description)
                    .ok_or(GroupActionError::Description)?,
            },
            Self::Picture {
                jpeg_b64: Some(b64),
            } => {
                let bytes = decode_b64(b64).ok_or(GroupActionError::Picture)?;
                if bytes.is_empty() || bytes.len() > MAX_GROUP_PICTURE_BYTES {
                    return Err(GroupActionError::Picture);
                }
                self.clone()
            }
            Self::SendInvite { code, .. } => {
                if wa_group::invite_code(code).as_deref() != Some(code.as_str()) {
                    return Err(GroupActionError::Code);
                }
                self.clone()
            }
            _ => self.clone(),
        })
    }

    /// The bulk kind of an action that answers per-person results.
    pub fn bulk(&self) -> Option<BulkKind> {
        Some(match self {
            Self::Add { .. } => BulkKind::Add,
            Self::Remove { .. } => BulkKind::Remove,
            Self::Promote { .. } => BulkKind::Promote,
            Self::Demote { .. } => BulkKind::Demote,
            Self::Approve { .. } => BulkKind::Approve,
            Self::Reject { .. } => BulkKind::Reject,
            _ => return None,
        })
    }
}

/// `POST /v1/groups/action`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct GroupAction {
    pub group: String,
    pub action: GroupActionKind,
}

/// A code an add answered for someone whose privacy refuses adds.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct InviteCode {
    pub code: String,
    /// Unix seconds.
    pub expiration: i64,
}

/// One person's result of a bulk action: no error is done.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ParticipantResult {
    pub jid: String,
    /// WhatsApp's code (`403`, `409`, ...).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// Their privacy refuses adds and this invite may be sent to them instead.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub invite: Option<InviteCode>,
}

impl ParticipantResult {
    /// The result as `rosty_core::wa_group::summarize` reads it.
    pub fn bulk(&self) -> BulkResult {
        BulkResult {
            jid: self.jid.clone(),
            error: self.error.clone(),
            invite: self.invite.is_some(),
        }
    }
}

/// What `POST /v1/groups/action` answers.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct GroupActionDone {
    /// One per person of a bulk action.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub results: Vec<ParticipantResult>,
    /// The invite link of an `invite_link` action.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub link: Option<String>,
}

/// `POST /v1/groups`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct CreateGroup {
    pub subject: String,
    #[serde(default)]
    pub participants: Vec<String>,
}

impl CreateGroup {
    /// The request as it is sent (a trimmed subject).
    pub fn checked(&self) -> Result<Self, GroupActionError> {
        if self.participants.len() > MAX_GROUP_PARTICIPANTS {
            return Err(GroupActionError::TooManyParticipants);
        }
        Ok(Self {
            subject: wa_group::check_subject(&self.subject).ok_or(GroupActionError::Subject)?,
            participants: self.participants.clone(),
        })
    }
}

/// What `POST /v1/groups` answers.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct GroupCreated {
    pub jid: String,
    /// One per person asked for that did not get in (WhatsApp lists the rest as members).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub results: Vec<ParticipantResult>,
}

/// What `GET /v1/groups/invite?code=` answers: the group a link leads to, before joining.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct InvitePreview {
    pub jid: String,
    pub subject: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// Joining sends a request an admin approves.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub approval: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub community: Option<CommunityRef>,
}

/// `POST /v1/groups/join`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct JoinGroup {
    pub code: String,
}

/// What `POST /v1/groups/join` answers.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct GroupJoined {
    pub jid: String,
    /// The group needs an admin's approval: a request was sent.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub pending: bool,
}

/// One pending join request (`GET /v1/groups/{jid}/requests`).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct JoinRequest {
    pub jid: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub jid_alt: Option<String>,
    /// When they asked (unix ms).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub requested_ms: Option<i64>,
}

/// One group of a community (`GET /v1/groups/{jid}/subgroups`).
pub use rosty_core::wa_group::Subgroup;

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    #[test]
    fn actions_are_tagged_and_checked() {
        let add: GroupAction = serde_json::from_str(
            r#"{"group":"1@g.us","action":{"type":"add","participants":["100000000001@s.whatsapp.net"]}}"#,
        )
        .unwrap();
        assert_eq!(add.action.bulk(), Some(BulkKind::Add));
        assert!(add.action.checked().is_ok());
        assert_eq!(
            serde_json::to_string(&GroupActionKind::Leave).unwrap(),
            r#"{"type":"leave"}"#
        );
        assert_eq!(
            serde_json::to_string(&GroupActionKind::InviteLink { reset: true }).unwrap(),
            r#"{"type":"invite_link","reset":true}"#
        );
        assert_eq!(
            GroupActionKind::Remove {
                participants: vec![]
            }
            .checked(),
            Err(GroupActionError::NoParticipants)
        );
        assert_eq!(
            GroupActionKind::Subject {
                subject: "  Aile ".into()
            }
            .checked(),
            Ok(GroupActionKind::Subject {
                subject: "Aile".into()
            })
        );
        assert_eq!(
            GroupActionKind::Subject {
                subject: "x".repeat(101)
            }
            .checked(),
            Err(GroupActionError::Subject)
        );
        assert_eq!(
            GroupActionKind::Picture {
                jpeg_b64: Some("not base64!".into())
            }
            .checked(),
            Err(GroupActionError::Picture)
        );
        assert!(
            GroupActionKind::Picture { jpeg_b64: None }
                .checked()
                .is_ok()
        );
        assert_eq!(
            GroupActionKind::SendInvite {
                to: "100000000001@s.whatsapp.net".into(),
                code: "x".into(),
                expiration: 0
            }
            .checked(),
            Err(GroupActionError::Code)
        );
    }

    #[test]
    fn answers_leave_out_what_is_empty() {
        assert_eq!(
            serde_json::to_string(&GroupActionDone::default()).unwrap(),
            "{}"
        );
        let done = GroupActionDone {
            results: vec![ParticipantResult {
                jid: "100000000003@s.whatsapp.net".into(),
                error: Some("403".into()),
                invite: Some(InviteCode {
                    code: "AbCdEfGhIjKl".into(),
                    expiration: 1_790_000_000,
                }),
            }],
            link: None,
        };
        let json = serde_json::to_string(&done).unwrap();
        assert_eq!(
            serde_json::from_str::<GroupActionDone>(&json).unwrap(),
            done
        );
        assert!(done.results[0].bulk().invite);
        let joined: GroupJoined = serde_json::from_str(r#"{"jid":"1@g.us"}"#).unwrap();
        assert!(!joined.pending);
        assert_eq!(
            CreateGroup {
                subject: " ".into(),
                participants: vec![]
            }
            .checked(),
            Err(GroupActionError::Subject)
        );
    }

    #[test]
    fn group_lines_and_the_left_flag_are_additive() {
        let note = crate::SystemNote::Group {
            action: "add".into(),
            by: Some("100000000001@s.whatsapp.net".into()),
            participants: vec!["100000000002@s.whatsapp.net".into()],
            value: None,
        };
        let json = serde_json::to_string(&note).unwrap();
        assert_eq!(
            json,
            r#"{"type":"group","action":"add","by":"100000000001@s.whatsapp.net","participants":["100000000002@s.whatsapp.net"]}"#
        );
        assert_eq!(
            serde_json::from_str::<crate::SystemNote>(&json).unwrap(),
            note
        );
        let meta: crate::ChatMeta = serde_json::from_str(r#"{"left":true}"#).unwrap();
        assert!(meta.left);
        assert_eq!(
            serde_json::to_string(&crate::ChatMeta::default()).unwrap(),
            "{}"
        );
        // An older gateway's group answer reads with every new field empty.
        let old: crate::GroupInfo = serde_json::from_str(
            r#"{"jid":"1@g.us","subject":"Aile","participants":[{"jid":"1@s.whatsapp.net","admin":true}]}"#,
        )
        .unwrap();
        assert!(!old.left && old.community.is_none() && old.participants[0].role.is_none());
    }
}
