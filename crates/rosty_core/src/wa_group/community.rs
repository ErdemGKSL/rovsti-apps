//! Communities, read-only: what kind of group a group is in its community, and the order its groups are listed in.

use serde::{Deserialize, Serialize};

/// A group's place in a community (`whatsapp-rust` `features/community.rs` `group_type`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CommunityKind {
    /// The community itself (its parent group).
    Parent,
    /// The announcement group: only admins send.
    Announcement,
    /// The general chat.
    General,
    /// Another group of the community.
    Sub,
}

/// The kind from the metadata flags, in the library's order; `None` for a group outside any community.
#[must_use]
pub fn community_kind(
    is_parent: bool,
    has_parent: bool,
    default_sub: bool,
    general: bool,
) -> Option<CommunityKind> {
    if default_sub {
        Some(CommunityKind::Announcement)
    } else if general {
        Some(CommunityKind::General)
    } else if has_parent {
        Some(CommunityKind::Sub)
    } else if is_parent {
        Some(CommunityKind::Parent)
    } else {
        None
    }
}

/// A group of a community as the gateway lists it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Subgroup {
    pub jid: String,
    pub subject: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u32>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub announcement: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub general: bool,
}

/// The order a community's groups are listed in: the announcement group, the general chat, then by subject.
pub fn arrange_subgroups(groups: &mut [Subgroup]) {
    groups.sort_by_cached_key(|g| {
        (
            !g.announcement,
            !g.general,
            g.subject.to_lowercase(),
            g.jid.clone(),
        )
    });
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    #[test]
    fn kinds_follow_the_library_order() {
        assert_eq!(
            community_kind(false, true, true, false),
            Some(CommunityKind::Announcement)
        );
        assert_eq!(
            community_kind(false, true, false, true),
            Some(CommunityKind::General)
        );
        assert_eq!(
            community_kind(false, true, false, false),
            Some(CommunityKind::Sub)
        );
        assert_eq!(
            community_kind(true, false, false, false),
            Some(CommunityKind::Parent)
        );
        assert_eq!(community_kind(false, false, false, false), None);
    }

    #[test]
    fn the_announcement_group_comes_first() {
        let group = |jid: &str, subject: &str, announcement, general| Subgroup {
            jid: jid.into(),
            subject: subject.into(),
            size: None,
            announcement,
            general,
        };
        let mut groups = vec![
            group("3@g.us", "zeta", false, false),
            group("2@g.us", "Genel", false, true),
            group("4@g.us", "Alfa", false, false),
            group("1@g.us", "Duyurular", true, false),
        ];
        arrange_subgroups(&mut groups);
        let order: Vec<&str> = groups.iter().map(|g| g.jid.as_str()).collect();
        assert_eq!(order, vec!["1@g.us", "2@g.us", "4@g.us", "3@g.us"]);
    }
}
