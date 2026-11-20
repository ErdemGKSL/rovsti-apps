//! Routes for groups (E6-WA R6): one action route for every change of a group, create, the invite preview and
//! join, join requests and a community's groups. The gateway keeps no group table: WhatsApp answers, and the gateway
//! only remembers that I left a group (`chat_settings.left_ms`).

use axum::extract::{Path, Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use rosty_core::wa_group::invite_code;
use rosty_wa_proto::api::routes as r;
use rosty_wa_proto::{
    CreateGroup, GatewayEvent, GroupAction, GroupActionDone, GroupActionKind, GroupCreated,
    GroupInfo, GroupJoined, InvitePreview, JoinGroup, JoinRequest, Subgroup,
};
use serde::Deserialize;

use crate::AppState;
use crate::auth::Authed;
use crate::driver::DriverError;
use crate::error::HttpError;
use crate::now_ms;
use crate::routes::check_jid;
use crate::store::InstanceId;

type ApiResult<T> = Result<T, HttpError>;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(r::GROUP_ACTION, post(action))
        .route(r::GROUP_INVITE, get(invite_preview))
        .route(r::GROUP_JOIN, post(join))
        .route(r::GROUP_REQUESTS, get(requests))
        .route(r::GROUP_SUBGROUPS, get(subgroups))
}

/// A group JID (`…@g.us`).
fn check_group(jid: &str) -> ApiResult<()> {
    check_jid(jid)?;
    if !jid.trim().ends_with("@g.us") {
        return Err(HttpError::bad_request("not a group"));
    }
    Ok(())
}

fn people(action: &GroupActionKind) -> &[String] {
    match action {
        GroupActionKind::Add { participants }
        | GroupActionKind::Remove { participants }
        | GroupActionKind::Promote { participants }
        | GroupActionKind::Demote { participants }
        | GroupActionKind::Approve { participants }
        | GroupActionKind::Reject { participants } => participants,
        _ => &[],
    }
}

/// Records that I left `group` (or am in it again) and tells the apps.
async fn set_left(
    state: &AppState,
    instance: &InstanceId,
    group: &str,
    left: bool,
) -> ApiResult<()> {
    let now = now_ms();
    if let Some(meta) = state
        .store()
        .set_group_left(instance.clone(), group.to_owned(), left, now)
        .await?
    {
        state.hub().publish(
            instance,
            now,
            GatewayEvent::ChatMeta {
                jid: group.to_owned(),
                meta,
            },
        );
    }
    Ok(())
}

async fn action(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<GroupAction>,
) -> ApiResult<Json<GroupActionDone>> {
    check_group(&req.group)?;
    let action = req
        .action
        .checked()
        .map_err(|e| HttpError::bad_request(e.to_string()))?;
    for person in people(&action) {
        check_jid(person)?;
    }
    if let GroupActionKind::SendInvite { to, .. } = &action {
        check_jid(to)?;
    }
    let leave = action == GroupActionKind::Leave;
    let done = state
        .driver()
        .group_action(
            &auth.instance,
            GroupAction {
                group: req.group.clone(),
                action,
            },
        )
        .await?;
    if leave {
        set_left(&state, &auth.instance, &req.group, true).await?;
    }
    Ok(Json(done))
}

/// `POST /v1/groups` (the route is shared with `GET`, see `routes.rs`).
pub async fn create(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<CreateGroup>,
) -> ApiResult<Json<GroupCreated>> {
    let req = req
        .checked()
        .map_err(|e| HttpError::bad_request(e.to_string()))?;
    for person in &req.participants {
        check_jid(person)?;
    }
    Ok(Json(
        state.driver().create_group(&auth.instance, req).await?,
    ))
}

#[derive(Debug, Deserialize)]
struct CodeQuery {
    code: String,
}

/// The code of a link or a bare code; 400 for anything else.
fn code_of(text: &str) -> ApiResult<String> {
    invite_code(text).ok_or_else(|| HttpError::bad_request("not a WhatsApp group invite"))
}

async fn invite_preview(
    State(state): State<AppState>,
    auth: Authed,
    Query(query): Query<CodeQuery>,
) -> ApiResult<Json<InvitePreview>> {
    let code = code_of(&query.code)?;
    Ok(Json(
        state.driver().invite_preview(&auth.instance, code).await?,
    ))
}

async fn join(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<JoinGroup>,
) -> ApiResult<Json<GroupJoined>> {
    let code = code_of(&req.code)?;
    let joined = state.driver().join_group(&auth.instance, code).await?;
    if !joined.pending {
        set_left(&state, &auth.instance, &joined.jid, false).await?;
    }
    Ok(Json(joined))
}

async fn requests(
    State(state): State<AppState>,
    auth: Authed,
    Path(jid): Path<String>,
) -> ApiResult<Json<Vec<JoinRequest>>> {
    check_group(&jid)?;
    Ok(Json(
        state.driver().join_requests(&auth.instance, jid).await?,
    ))
}

async fn subgroups(
    State(state): State<AppState>,
    auth: Authed,
    Path(jid): Path<String>,
) -> ApiResult<Json<Vec<Subgroup>>> {
    check_group(&jid)?;
    Ok(Json(state.driver().subgroups(&auth.instance, jid).await?))
}

/// What `GET /v1/groups/{jid}` answers for a group WhatsApp no longer shows me: I left it or was removed. The
/// subject is the stored chat's name; the members are not known any more.
pub async fn left_group(
    state: &AppState,
    instance: &InstanceId,
    jid: &str,
    error: DriverError,
) -> ApiResult<GroupInfo> {
    let refused = matches!(error, DriverError::Refused(_) | DriverError::NotFound(_));
    if refused
        && state
            .store()
            .group_left(instance.clone(), jid.to_owned())
            .await?
    {
        return Ok(GroupInfo {
            jid: jid.to_owned(),
            left: true,
            ..GroupInfo::default()
        });
    }
    Err(error.into())
}
