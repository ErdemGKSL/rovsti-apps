//! Roles and what each may do.
//!
//! WhatsApp has three roles: the super admin (the creator, unless they left), admins and members. Admins add,
//! remove, promote and demote, change the settings and see the invite link. A super admin cannot be removed or
//! demoted by another admin. In announcement mode only admins send; when the group is locked only admins edit its
//! subject, description and picture; with "only admins add" members cannot add. Someone who left the group (or was
//! removed) can do nothing but read.

use serde::{Deserialize, Serialize};

/// The most characters of a subject (`wacore::iq::groups::GROUP_SUBJECT_MAX_LENGTH`).
pub const MAX_SUBJECT_CHARS: usize = 100;
/// The most characters of a description (`wacore::iq::groups::GROUP_DESCRIPTION_MAX_LENGTH`).
pub const MAX_DESCRIPTION_CHARS: usize = 2048;

/// A member's role.
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize,
)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    #[default]
    Member,
    Admin,
    #[serde(rename = "superadmin")]
    SuperAdmin,
}

impl Role {
    /// The role a gateway names (`superadmin`, `admin`, `member`), falling back to the `admin` flag older gateways
    /// send alone.
    #[must_use]
    pub fn from_wire(role: Option<&str>, admin: bool) -> Self {
        match role {
            Some("superadmin") => Self::SuperAdmin,
            Some("admin") => Self::Admin,
            Some("member") => Self::Member,
            _ if admin => Self::Admin,
            _ => Self::Member,
        }
    }

    /// The wire name.
    #[must_use]
    pub fn wire(self) -> &'static str {
        match self {
            Self::SuperAdmin => "superadmin",
            Self::Admin => "admin",
            Self::Member => "member",
        }
    }

    /// An admin or the super admin.
    #[must_use]
    pub fn is_admin(self) -> bool {
        self != Self::Member
    }
}

/// The settings of a group that decide who may do what.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct GroupSettings {
    /// Only admins send ("announcement" group; a community's announcement group always is).
    pub announce: bool,
    /// Only admins edit the subject, description and picture.
    pub locked: bool,
    /// New members wait for an admin's approval.
    pub approval: bool,
    /// Only admins add members.
    pub admins_add: bool,
    /// I left the group or was removed.
    pub left: bool,
}

/// What I may do in a group.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Abilities {
    /// Write in the thread.
    pub send: bool,
    /// Change the subject, description and picture.
    pub edit_info: bool,
    /// Add members.
    pub add: bool,
    /// Remove, promote and demote members (each target still checked by [`can_act_on`]).
    pub manage: bool,
    /// Change the group's settings (announcement, locked, approval).
    pub settings: bool,
    /// See, copy, share and reset the invite link.
    pub invite_link: bool,
    /// Approve or reject join requests.
    pub requests: bool,
    /// Leave the group.
    pub leave: bool,
}

/// What `me` may do in a group with `settings`; `me` is `None` when I am not in its member list.
#[must_use]
pub fn abilities(me: Option<Role>, settings: &GroupSettings) -> Abilities {
    let Some(me) = me.filter(|_| !settings.left) else {
        return Abilities::default();
    };
    let admin = me.is_admin();
    Abilities {
        send: admin || !settings.announce,
        edit_info: admin || !settings.locked,
        add: admin || !settings.admins_add,
        manage: admin,
        settings: admin,
        invite_link: admin,
        requests: admin,
        leave: true,
    }
}

/// An action on one member.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MemberAction {
    Remove,
    Promote,
    Demote,
}

/// Whether `me` may do `action` to a member whose role is `target` (never to myself: leaving is its own action).
#[must_use]
pub fn can_act_on(me: Role, target: Role, action: MemberAction) -> bool {
    if !me.is_admin() {
        return false;
    }
    match action {
        MemberAction::Promote => target == Role::Member,
        MemberAction::Demote => target == Role::Admin,
        MemberAction::Remove => target != Role::SuperAdmin,
    }
}

/// Whether leaving would leave the group without an admin I chose: I am its only admin and others stay (WhatsApp then
/// picks an admin itself, as remembered; the app warns first).
#[must_use]
pub fn leaving_last_admin(me: Option<Role>, admins: usize, members: usize) -> bool {
    me.is_some_and(Role::is_admin) && admins <= 1 && members > 1
}

/// What the info page shows about me.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct GroupView {
    /// My role; `None` when I am not a member.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub my_role: Option<Role>,
    pub abilities: Abilities,
    /// Leaving needs the "only admin" warning.
    pub last_admin: bool,
    /// The composer gives way to "Only admins can send messages".
    pub announce_only: bool,
}

/// The info page's view of me in a group of `members` people, `admins` of them admins.
#[must_use]
pub fn group_view(
    me: Option<Role>,
    settings: &GroupSettings,
    admins: usize,
    members: usize,
) -> GroupView {
    let me = me.filter(|_| !settings.left);
    let abilities = abilities(me, settings);
    GroupView {
        my_role: me,
        abilities,
        last_admin: leaving_last_admin(me, admins, members),
        announce_only: me.is_some() && settings.announce && !abilities.send,
    }
}

/// The subject as it is sent: trimmed; `None` when empty or longer than [`MAX_SUBJECT_CHARS`] characters.
#[must_use]
pub fn check_subject(subject: &str) -> Option<String> {
    let subject = subject.trim();
    let count = subject.chars().count();
    (count > 0 && count <= MAX_SUBJECT_CHARS).then(|| subject.to_owned())
}

/// The description as it is sent: trimmed (empty deletes it); `None` when longer than [`MAX_DESCRIPTION_CHARS`].
#[must_use]
pub fn check_description(description: &str) -> Option<String> {
    let description = description.trim();
    (description.chars().count() <= MAX_DESCRIPTION_CHARS).then(|| description.to_owned())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    fn settings(announce: bool, locked: bool) -> GroupSettings {
        GroupSettings {
            announce,
            locked,
            ..GroupSettings::default()
        }
    }

    #[test]
    fn roles_read_the_wire_and_the_old_flag() {
        assert_eq!(Role::from_wire(Some("superadmin"), false), Role::SuperAdmin);
        assert_eq!(Role::from_wire(Some("admin"), false), Role::Admin);
        assert_eq!(Role::from_wire(Some("member"), true), Role::Member);
        assert_eq!(Role::from_wire(None, true), Role::Admin);
        assert_eq!(Role::from_wire(Some("future"), false), Role::Member);
        assert_eq!(Role::SuperAdmin.wire(), "superadmin");
        assert!(Role::SuperAdmin.is_admin() && Role::Admin.is_admin() && !Role::Member.is_admin());
        assert_eq!(
            serde_json::to_string(&Role::SuperAdmin).unwrap(),
            "\"superadmin\""
        );
    }

    #[test]
    fn a_member_sends_unless_announcement_mode_and_edits_unless_locked() {
        let open = abilities(Some(Role::Member), &settings(false, false));
        assert!(open.send && open.edit_info && open.add && open.leave);
        assert!(!open.manage && !open.settings && !open.invite_link && !open.requests);
        let closed = abilities(Some(Role::Member), &settings(true, true));
        assert!(!closed.send && !closed.edit_info && closed.leave);
        let only_admins_add = GroupSettings {
            admins_add: true,
            ..GroupSettings::default()
        };
        assert!(!abilities(Some(Role::Member), &only_admins_add).add);
    }

    #[test]
    fn admins_do_everything_and_someone_who_left_nothing() {
        let all = abilities(Some(Role::Admin), &settings(true, true));
        assert!(all.send && all.edit_info && all.add && all.manage && all.settings);
        assert!(all.invite_link && all.requests && all.leave);
        assert_eq!(
            abilities(Some(Role::SuperAdmin), &settings(true, true)),
            all
        );
        let left = GroupSettings {
            left: true,
            ..GroupSettings::default()
        };
        assert_eq!(abilities(Some(Role::Admin), &left), Abilities::default());
        assert_eq!(
            abilities(None, &GroupSettings::default()),
            Abilities::default()
        );
    }

    #[test]
    fn who_may_act_on_whom() {
        use MemberAction::{Demote, Promote, Remove};
        assert!(can_act_on(Role::Admin, Role::Member, Promote));
        assert!(!can_act_on(Role::Admin, Role::Admin, Promote));
        assert!(can_act_on(Role::Admin, Role::Admin, Demote));
        assert!(!can_act_on(Role::Admin, Role::SuperAdmin, Demote));
        assert!(!can_act_on(Role::Admin, Role::Member, Demote));
        assert!(can_act_on(Role::Admin, Role::Admin, Remove));
        assert!(!can_act_on(Role::Admin, Role::SuperAdmin, Remove));
        assert!(can_act_on(Role::SuperAdmin, Role::Admin, Remove));
        assert!(!can_act_on(Role::Member, Role::Member, Remove));
    }

    #[test]
    fn leaving_the_last_admin_seat_warns() {
        assert!(leaving_last_admin(Some(Role::Admin), 1, 5));
        assert!(leaving_last_admin(Some(Role::SuperAdmin), 1, 2));
        // Alone in the group, or with another admin: nothing to warn about.
        assert!(!leaving_last_admin(Some(Role::Admin), 1, 1));
        assert!(!leaving_last_admin(Some(Role::Admin), 2, 5));
        assert!(!leaving_last_admin(Some(Role::Member), 1, 5));
        assert!(!leaving_last_admin(None, 0, 5));
    }

    #[test]
    fn the_view_says_when_the_composer_gives_way() {
        // A community announcement group where I am a member.
        let view = group_view(Some(Role::Member), &settings(true, false), 1, 900);
        assert!(view.announce_only && !view.abilities.send && !view.last_admin);
        let admin = group_view(Some(Role::Admin), &settings(true, false), 1, 900);
        assert!(!admin.announce_only && admin.last_admin);
        // After leaving the composer is the "left" line, not the announcement one.
        let left = GroupSettings {
            left: true,
            announce: true,
            ..GroupSettings::default()
        };
        let gone = group_view(Some(Role::Admin), &left, 1, 3);
        assert_eq!(gone.my_role, None);
        assert!(!gone.announce_only && !gone.last_admin);
    }

    #[test]
    fn subjects_and_descriptions_are_checked() {
        assert_eq!(check_subject("  Aile  ").as_deref(), Some("Aile"));
        assert_eq!(check_subject("   "), None);
        assert!(check_subject(&"ş".repeat(100)).is_some());
        assert_eq!(check_subject(&"ş".repeat(101)), None);
        assert_eq!(check_description("  ").as_deref(), Some(""));
        assert!(check_description(&"a".repeat(2048)).is_some());
        assert_eq!(check_description(&"a".repeat(2049)), None);
    }
}
