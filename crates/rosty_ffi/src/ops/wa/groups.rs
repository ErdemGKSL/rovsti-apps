//! Groups (E6-WA R6). The gateway ops `wa.group_action`, `wa.group_create`, `wa.group_invite_preview`,
//! `wa.group_join`, `wa.group_requests` and `wa.group_subgroups`, and the pure rules of `rosty_core::wa_group`: the
//! notice line of a group change, finding an invite link in a text, and the summary of a bulk action's results.

use super::Call;
use super::fixture::{case, named};
use super::rest::JidRef;
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rosty_core::wa_group::{
    self, BulkKind, BulkSummary, GroupNote, InviteMatch, MemberAction, Notice, Role,
};
use rosty_wa_proto::{
    CreateGroup, GroupAction, GroupActionDone, GroupCreated, GroupJoined, InvitePreview,
    JoinRequest, ParticipantResult, Subgroup,
};
use serde::{Deserialize, Serialize};
use serde_json::json;

const GROUP: &str = "120363000000000071@g.us";
const ADA: &str = "100000000071@s.whatsapp.net";
const BEN: &str = "100000000072@s.whatsapp.net";
const CEM: &str = "100000000073@s.whatsapp.net";
const CODE: &str = "AbCdEfGhIjKlMnOpQrStUv";

pub(super) const OPS: &[OpSpec] = &[
    wa_op!(
        "wa.group_action",
        Call<GroupAction>,
        GroupActionDone,
        group_action,
        || vec![
            case(
                json!({"group": GROUP, "action": {"type": "add", "participants": [ADA, BEN, CEM]}})
            ),
            named(
                "invite_link",
                json!({"group": GROUP, "action": {"type": "invite_link", "reset": true}})
            ),
            named(
                "leave",
                json!({"group": GROUP, "action": {"type": "leave"}})
            ),
            named(
                "refused",
                json!({"group": GROUP, "action": {"type": "announce", "on": true}})
            ),
            named(
                "invalid",
                json!({"group": GROUP, "action": {"type": "subject", "subject": "  "}})
            ),
        ]
    ),
    wa_op!(
        "wa.group_create",
        Call<CreateGroup>,
        GroupCreated,
        group_create,
        || vec![case(json!({"subject": "Aile", "participants": [ADA, BEN]}))]
    ),
    wa_op!(
        "wa.group_invite_preview",
        Call<CodeReq>,
        InvitePreview,
        invite_preview,
        || vec![
            case(json!({"code": format!("https://chat.whatsapp.com/{CODE}")})),
            named("reset", json!({"code": "OldResetCode0001"})),
        ]
    ),
    wa_op!(
        "wa.group_join",
        Call<CodeReq>,
        GroupJoined,
        group_join,
        || vec![
            case(json!({"code": CODE})),
            named("pending", json!({"code": "NeedsApproval0001"})),
        ]
    ),
    wa_op!(
        "wa.group_requests",
        Call<JidRef>,
        RequestsAns,
        group_requests,
        || vec![case(json!({"jid": GROUP}))]
    ),
    wa_op!(
        "wa.group_subgroups",
        Call<JidRef>,
        SubgroupsAns,
        group_subgroups,
        || vec![case(json!({"jid": "120363000000000070@g.us"}))]
    ),
    op!(
        "wa.group_notice",
        NoticeReq,
        Notice,
        group_notice,
        notice_cases
    ),
    op!("wa.invite_find", FindReq, FindAns, invite_find, find_cases),
    op!(
        "wa.group_results",
        ResultsReq,
        BulkSummary,
        group_results,
        results_cases
    ),
    op!("wa.group_can", CanReq, CanAns, group_can, can_cases),
];

#[derive(Deserialize)]
pub(super) struct CodeReq {
    /// A `chat.whatsapp.com` link or a bare code.
    code: String,
}

#[derive(Serialize)]
struct RequestsAns {
    requests: Vec<JoinRequest>,
}

#[derive(Serialize)]
struct SubgroupsAns {
    groups: Vec<Subgroup>,
}

gateway_fn!(group_action, GroupAction => GroupActionDone, |g, b| g.group_action(&b));
gateway_fn!(group_create, CreateGroup => GroupCreated, |g, b| g.create_group(&b));
gateway_fn!(invite_preview, CodeReq => InvitePreview, |g, b| g.invite_preview(&b.code));
gateway_fn!(group_join, CodeReq => GroupJoined, |g, b| g.join_group(&b.code));
gateway_fn!(group_requests, JidRef => RequestsAns, |g, b| g
    .join_requests(b.jid())
    .map(|requests| RequestsAns { requests }));
gateway_fn!(group_subgroups, JidRef => SubgroupsAns, |g, b| g
    .subgroups(b.jid())
    .map(|groups| SubgroupsAns { groups }));

// ---- wa.group_notice

#[derive(Deserialize)]
struct NoticeReq {
    /// The stored line (`system` of a row with `type: "group"`).
    note: GroupNote,
    /// My ids (number and LID).
    #[serde(default)]
    own: Vec<String>,
}

fn group_notice(req: NoticeReq) -> Result<Notice, FfiError> {
    Ok(wa_group::notice(&req.note, &req.own))
}

fn notice_cases() -> Vec<Case> {
    let own = json!(["100000000079@s.whatsapp.net", "777000000000079@lid"]);
    vec![
        Case::new(json!({"note": {"action": "add", "by": ADA,
            "participants": [BEN, "777000000000079@lid", CEM, "100000000074@s.whatsapp.net", "100000000075@s.whatsapp.net"]},
            "own": own})),
        Case::named(
            "left",
            json!({"note": {"action": "remove", "by": BEN, "participants": [BEN]}, "own": own}),
        ),
        Case::named(
            "subject",
            json!({"note": {"action": "subject", "by": "me", "value": "Aile"}, "own": own}),
        ),
    ]
}

// ---- wa.invite_find

#[derive(Deserialize)]
struct FindReq {
    text: String,
}

#[derive(Serialize)]
struct FindAns {
    /// The first invite link in the text, with where it is (UTF-16).
    #[serde(skip_serializing_if = "Option::is_none")]
    found: Option<InviteMatch>,
    /// The whole text read as a link or a bare code (what a paste box takes).
    #[serde(skip_serializing_if = "Option::is_none")]
    code: Option<String>,
}

fn invite_find(req: FindReq) -> Result<FindAns, FfiError> {
    Ok(FindAns {
        found: wa_group::find_invite(&req.text),
        code: wa_group::invite_code(&req.text),
    })
}

fn find_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": format!("Gel: https://chat.whatsapp.com/{CODE}")})),
        Case::named("pasted", json!({"text": format!(" {CODE} ")})),
        Case::named("none", json!({"text": "https://example.com/AbCdEfGhIjKl"})),
    ]
}

// ---- wa.group_results

#[derive(Deserialize)]
struct ResultsReq {
    kind: BulkKind,
    results: Vec<ParticipantResult>,
}

fn group_results(req: ResultsReq) -> Result<BulkSummary, FfiError> {
    let results: Vec<_> = req.results.iter().map(ParticipantResult::bulk).collect();
    Ok(wa_group::summarize(req.kind, &results))
}

fn results_cases() -> Vec<Case> {
    vec![Case::new(json!({"kind": "add", "results": [
        {"jid": ADA},
        {"jid": BEN, "error": "409"},
        {"jid": CEM, "error": "403", "invite": {"code": "PrivacyInviteCode01", "expiration": 1790000000}},
        {"jid": "100000000074@s.whatsapp.net", "error": "500"},
    ]}))]
}

// ---- wa.group_can

#[derive(Deserialize)]
struct CanReq {
    me: Role,
    target: Role,
    action: MemberAction,
}

#[derive(Serialize)]
struct CanAns {
    allowed: bool,
}

fn group_can(req: CanReq) -> Result<CanAns, FfiError> {
    Ok(CanAns {
        allowed: wa_group::can_act_on(req.me, req.target, req.action),
    })
}

fn can_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"me": "admin", "target": "member", "action": "promote"})),
        Case::named(
            "super_admin",
            json!({"me": "admin", "target": "superadmin", "action": "remove"}),
        ),
    ]
}
