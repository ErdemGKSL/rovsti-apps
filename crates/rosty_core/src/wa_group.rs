//! WhatsApp groups (E6-WA R6): roles and what each role may do, the member list and how group changes merge into it,
//! the notice lines of group changes, invite links, the per-person results of a bulk action and community kinds. No
//! I/O: the gateway, the device store and the apps call these.

mod bulk;
mod community;
mod invite;
mod members;
mod notice;
mod role;

pub use bulk::{BulkKind, BulkResult, BulkSummary, Outcome, ResultView, outcome, summarize};
pub use community::{CommunityKind, Subgroup, arrange_subgroups, community_kind};
pub use invite::{INVITE_HOST, InviteMatch, find_invite, invite_code, invite_link};
pub use members::{Change, Member, find_member, merge, my_role, order_members, same_id};
pub use notice::{Actor, GroupNote, MAX_NAMED, ME, Notice, Target, Template, notice};
pub use role::{
    Abilities, GroupSettings, GroupView, MAX_DESCRIPTION_CHARS, MAX_SUBJECT_CHARS, MemberAction,
    Role, abilities, can_act_on, check_description, check_subject, group_view, leaving_last_admin,
};
