//! The member list and how group changes merge into it.
//!
//! A member is known by the id the gateway names them under (the number first) and, when known, their other id (the
//! LID of a number, the number of a LID). A change (from a notice line or a bulk action) names people by either id,
//! so every match goes through both ([`same_id`]). Order on the info page: me first, then admins, then the rest by
//! name; people without a name after the named ones.

use serde::{Deserialize, Serialize};

use super::role::Role;
use crate::wa_identity;

/// One member as the device keeps them.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Member {
    pub jid: String,
    /// Their other id (a LID next to a number, a number next to a LID).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt: Option<String>,
    #[serde(default)]
    pub role: Role,
    /// Their WhatsApp name, when the gateway has one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl Member {
    /// A member named only by `jid`.
    #[must_use]
    pub fn of(jid: &str, role: Role) -> Self {
        Self {
            jid: jid.to_owned(),
            alt: None,
            role,
            name: None,
        }
    }

    fn ids(&self) -> impl Iterator<Item = &str> {
        std::iter::once(self.jid.as_str()).chain(self.alt.as_deref())
    }

    fn is(&self, id: &str) -> bool {
        self.ids().any(|mine| same_id(mine, id))
    }
}

/// Whether two JIDs name the same id (device suffixes and spellings of the server aside). A number and a LID are
/// never the same id; a member's other id makes the link.
#[must_use]
pub fn same_id(a: &str, b: &str) -> bool {
    match (wa_identity::parse(a), wa_identity::parse(b)) {
        (Some(a), Some(b)) => a.id == b.id,
        _ => a == b,
    }
}

/// The index of the member `id` names, by either of their ids.
#[must_use]
pub fn find_member(members: &[Member], id: &str) -> Option<usize> {
    members.iter().position(|m| m.is(id))
}

/// A change to the member list.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Change {
    /// People joined (added, by link, or approved). A person already listed keeps their role.
    Add {
        members: Vec<Member>,
    },
    /// People left or were removed.
    Remove {
        jids: Vec<String>,
    },
    Promote {
        jids: Vec<String>,
    },
    Demote {
        jids: Vec<String>,
    },
    /// A member changed number: `old` is listed under `new` from now on.
    Number {
        old: String,
        new: String,
    },
}

/// Applies `change` to `members`; answers how many members changed. Idempotent: a change seen twice changes nothing
/// the second time.
pub fn merge(members: &mut Vec<Member>, change: &Change) -> usize {
    match change {
        Change::Add { members: added } => {
            let mut changed = 0;
            for member in added {
                match find_member(members, &member.jid)
                    .or_else(|| member.alt.as_deref().and_then(|a| find_member(members, a)))
                {
                    Some(at) => {
                        let kept = &mut members[at];
                        if kept.alt.is_none() && member.alt.is_some() {
                            kept.alt.clone_from(&member.alt);
                            changed += 1;
                        }
                    }
                    None => {
                        members.push(member.clone());
                        changed += 1;
                    }
                }
            }
            changed
        }
        Change::Remove { jids } => {
            let before = members.len();
            members.retain(|m| !jids.iter().any(|id| m.is(id)));
            before - members.len()
        }
        Change::Promote { jids } => set_roles(members, jids, |r| match r {
            Role::Member => Role::Admin,
            other => other,
        }),
        Change::Demote { jids } => set_roles(members, jids, |r| match r {
            Role::Admin => Role::Member,
            other => other,
        }),
        Change::Number { old, new } => match find_member(members, old) {
            Some(at) if !same_id(&members[at].jid, new) => {
                let member = &mut members[at];
                member.jid.clone_from(new);
                1
            }
            _ => 0,
        },
    }
}

fn set_roles(members: &mut [Member], jids: &[String], to: impl Fn(Role) -> Role) -> usize {
    let mut changed = 0;
    for member in members.iter_mut() {
        if jids.iter().any(|id| member.is(id)) {
            let role = to(member.role);
            if role != member.role {
                member.role = role;
                changed += 1;
            }
        }
    }
    changed
}

/// My role in the group: the member any of `own` (my number and my LID) names; `None` when I am not listed.
#[must_use]
pub fn my_role(members: &[Member], own: &[String]) -> Option<Role> {
    members
        .iter()
        .find(|m| own.iter().any(|id| m.is(id)))
        .map(|m| m.role)
}

/// Sorts `members` for the info page: me first, admins (the super admin first), then by name (case-insensitive),
/// people with no name last by id. `name_of` gives the name the page shows (the address-book name first); `None` is
/// no name.
pub fn order_members(
    members: &mut [Member],
    own: &[String],
    name_of: impl Fn(&Member) -> Option<String>,
) {
    members.sort_by_cached_key(|m| {
        let me = own.iter().any(|id| m.is(id));
        let name = name_of(m).map(|n| n.to_lowercase());
        (
            !me,
            std::cmp::Reverse(m.role),
            name.is_none(),
            name.unwrap_or_default(),
            m.jid.clone(),
        )
    });
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    const ADA: &str = "100000000001@s.whatsapp.net";
    const ADA_LID: &str = "777000000000001@lid";
    const BEN: &str = "100000000002@s.whatsapp.net";
    const CEM_LID: &str = "777000000000003@lid";
    const ME: &str = "100000000009@s.whatsapp.net";
    const ME_LID: &str = "777000000000009@lid";

    fn with_alt(jid: &str, alt: &str, role: Role) -> Member {
        Member {
            alt: Some(alt.to_owned()),
            ..Member::of(jid, role)
        }
    }

    fn list() -> Vec<Member> {
        vec![
            with_alt(ADA, ADA_LID, Role::SuperAdmin),
            Member::of(BEN, Role::Member),
            Member::of(CEM_LID, Role::Member),
            with_alt(ME, ME_LID, Role::Admin),
        ]
    }

    #[test]
    fn ids_match_across_devices_and_aliases() {
        assert!(same_id(ADA, "100000000001:12@s.whatsapp.net"));
        assert!(!same_id(ADA, "100000000001@lid"));
        let members = list();
        assert_eq!(find_member(&members, ADA_LID), Some(0));
        assert_eq!(find_member(&members, "777000000000003:4@lid"), Some(2));
        assert_eq!(find_member(&members, "100000000099@s.whatsapp.net"), None);
    }

    #[test]
    fn adds_are_idempotent_and_learn_the_other_id() {
        let mut members = list();
        let add = Change::Add {
            members: vec![
                Member::of("100000000004@s.whatsapp.net", Role::Member),
                with_alt(BEN, "777000000000002@lid", Role::Member),
            ],
        };
        assert_eq!(merge(&mut members, &add), 2);
        assert_eq!(members.len(), 5);
        assert_eq!(members[1].alt.as_deref(), Some("777000000000002@lid"));
        assert_eq!(merge(&mut members, &add), 0);
        // Added by the LID of someone listed by number: still one row.
        let again = Change::Add {
            members: vec![Member::of(ADA_LID, Role::Member)],
        };
        assert_eq!(merge(&mut members, &again), 0);
        assert_eq!(members[0].role, Role::SuperAdmin);
    }

    #[test]
    fn removes_promotions_and_demotions_follow_either_id() {
        let mut members = list();
        let promote = Change::Promote {
            jids: vec!["777000000000003@lid".into(), ADA.into()],
        };
        assert_eq!(merge(&mut members, &promote), 1);
        assert_eq!(members[2].role, Role::Admin);
        assert_eq!(members[0].role, Role::SuperAdmin);
        let demote = Change::Demote {
            jids: vec![ME_LID.into(), ADA.into()],
        };
        assert_eq!(merge(&mut members, &demote), 1);
        assert_eq!(my_role(&members, &[ME.into()]), Some(Role::Member));
        let remove = Change::Remove {
            jids: vec![ME_LID.into()],
        };
        assert_eq!(merge(&mut members, &remove), 1);
        assert_eq!(my_role(&members, &[ME.into(), ME_LID.into()]), None);
        assert_eq!(merge(&mut members, &remove), 0);
    }

    #[test]
    fn a_number_change_moves_the_member() {
        let mut members = list();
        let change = Change::Number {
            old: BEN.into(),
            new: "100000000022@s.whatsapp.net".into(),
        };
        assert_eq!(merge(&mut members, &change), 1);
        assert_eq!(members[1].jid, "100000000022@s.whatsapp.net");
        assert_eq!(merge(&mut members, &change), 0);
    }

    #[test]
    fn the_page_lists_me_then_admins_then_names() {
        let mut members = list();
        members.push(Member::of("100000000005@s.whatsapp.net", Role::Admin));
        let names = |m: &Member| match m.jid.as_str() {
            BEN => Some("ben".to_owned()),
            CEM_LID => Some("Ayla".to_owned()),
            ADA => Some("Zoe".to_owned()),
            _ => None,
        };
        order_members(&mut members, &[ME_LID.into()], names);
        let order: Vec<&str> = members.iter().map(|m| m.jid.as_str()).collect();
        assert_eq!(
            order,
            vec![ME, ADA, "100000000005@s.whatsapp.net", CEM_LID, BEN]
        );
    }

    #[test]
    fn changes_read_from_json() {
        let change: Change =
            serde_json::from_str(r#"{"type":"remove","jids":["1@s.whatsapp.net"]}"#).unwrap();
        assert_eq!(
            change,
            Change::Remove {
                jids: vec!["1@s.whatsapp.net".into()]
            }
        );
    }
}
