//! People sync v3 routes (`_bmad-output/kotlin-ui/plans/people-sync-v3.md`, section 3.1): start or join the
//! instance's people job, watch it by long-poll, download its bundle in parts, cancel or drop it.

use std::time::Duration;

use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::http::header::{CONTENT_TYPE, HeaderValue};
use axum::response::{IntoResponse as _, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use rosty_wa_proto::api::routes as r;
use rosty_wa_proto::people::{
    BUNDLE_CONTENT_TYPE, BUNDLE_MAX_BYTES, MAX_PEOPLE_NUMBERS, PEOPLE_WAIT_MAX_MS, accept_entries,
    start_digest,
};
use rosty_wa_proto::{BundleWriter, ErrorCode, PeopleJobState, PeopleJobStatus, PeopleSyncStart};
use serde::Deserialize;

use crate::AppState;
use crate::auth::Authed;
use crate::error::HttpError;
use crate::people_job;
use crate::store::{BeginOutcome, InstanceId, JobRow, NewJob};

type ApiResult<T> = Result<T, HttpError>;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(r::PEOPLE_SYNC, post(start))
        .route(r::PEOPLE_SYNC_JOB, get(status).delete(cancel))
        .route(r::PEOPLE_SYNC_BUNDLE, get(bundle))
}

/// A job id: 16 lower-case hex digits from `getrandom` (as `hub.rs` makes the epoch).
fn fresh_job_id() -> ApiResult<String> {
    let mut bytes = [0u8; 8];
    getrandom::fill(&mut bytes).map_err(|e| {
        log::error!("no randomness: {e}");
        HttpError::new(ErrorCode::Internal, "internal error")
    })?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn check_job_id(job_id: &str) -> ApiResult<()> {
    if job_id.is_empty() || job_id.len() > 64 || !job_id.bytes().all(|b| b.is_ascii_alphanumeric())
    {
        return Err(HttpError::bad_request("not a people job id"));
    }
    Ok(())
}

fn gone() -> HttpError {
    HttpError::not_found("no such people job")
}

/// The instance's job if it is `job_id`, after the age rules; otherwise 404.
async fn current(state: &AppState, instance: &InstanceId, job_id: &str) -> ApiResult<JobRow> {
    check_job_id(job_id)?;
    state
        .store()
        .people_job(instance.clone(), state.now())
        .await?
        .filter(|job| job.job_id == job_id)
        .ok_or_else(gone)
}

async fn start(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<PeopleSyncStart>,
) -> ApiResult<Json<PeopleJobStatus>> {
    let instance = auth.instance;
    let entries = accept_entries(&req.entries).ok_or_else(|| {
        HttpError::bad_request(format!(
            "entries must hold at most {MAX_PEOPLE_NUMBERS} numbers"
        ))
    })?;
    let digest = start_digest(req.mode, &entries);
    let numbers = entries.len();
    let new = NewJob {
        job_id: fresh_job_id()?,
        digest,
        mode: req.mode,
        entries,
    };
    let (outcome, job) = state
        .store()
        .people_begin(instance.clone(), new, state.now())
        .await?;
    match &outcome {
        BeginOutcome::Started { replaced } => {
            log::info!(
                "instance={instance} people job started mode={} numbers={numbers} replaced={}",
                req.mode.as_str(),
                replaced.is_some()
            );
            state.people().abort(&instance);
            people_job::clean_spool(&state, &instance, Some(&job.job_id)).await;
            people_job::ensure(&state, &instance, &job.job_id);
            state.people().notify(&instance, job.rev);
        }
        BeginOutcome::Joined => people_job::ensure(&state, &instance, &job.job_id),
        BeginOutcome::Other => {
            log::info!("instance={instance} people job busy with another request");
        }
        BeginOutcome::Ready => {}
    }
    Ok(Json(job.status(outcome != BeginOutcome::Other)))
}

#[derive(Debug, Deserialize)]
struct StatusQuery {
    /// The revision the caller holds: with `wait_ms`, answer when the job is past it.
    #[serde(default)]
    rev: Option<u64>,
    #[serde(default)]
    wait_ms: Option<u64>,
}

async fn status(
    State(state): State<AppState>,
    auth: Authed,
    Path(job_id): Path<String>,
    Query(query): Query<StatusQuery>,
) -> ApiResult<Json<PeopleJobStatus>> {
    let instance = auth.instance;
    // Subscribed before the read: a change right after it still wakes the wait.
    let mut changes = state.people().subscribe(&instance);
    let mut job = current(&state, &instance, &job_id).await?;
    if job.state.is_active() && !state.people().is_running(&instance, &job.job_id) {
        people_job::ensure(&state, &instance, &job.job_id);
    }
    let wait = query.wait_ms.unwrap_or(0).min(PEOPLE_WAIT_MAX_MS);
    if query.rev == Some(job.rev) && job.state.is_active() && wait > 0 {
        let _ = tokio::time::timeout(Duration::from_millis(wait), changes.changed()).await;
        job = current(&state, &instance, &job_id).await?;
    }
    Ok(Json(job.status(true)))
}

#[derive(Debug, Deserialize)]
struct BundleQuery {
    #[serde(default)]
    after: u32,
    #[serde(default)]
    max_bytes: Option<u64>,
}

async fn bundle(
    State(state): State<AppState>,
    auth: Authed,
    Path(job_id): Path<String>,
    Query(query): Query<BundleQuery>,
) -> ApiResult<Response> {
    let instance = auth.instance;
    let job = current(&state, &instance, &job_id).await?;
    if job.state != PeopleJobState::Ready {
        return Err(HttpError::new(ErrorCode::Conflict, "the job is not ready"));
    }
    let max = query
        .max_bytes
        .unwrap_or(BUNDLE_MAX_BYTES)
        .clamp(1, BUNDLE_MAX_BYTES);
    let planned = people_job::plan_records(&state, &instance, &job_id, query.after).await?;
    let mut writer = BundleWriter::new(&job_id, query.after);
    let mut more = false;
    for item in &planned {
        if !writer.is_empty() && writer.len() >= max {
            more = true;
            break;
        }
        let picture = match &item.picture {
            Some(path) => tokio::fs::read(path).await.ok(),
            None => None,
        };
        let mut record = item.record.clone();
        if item.picture.is_some() && picture.is_none() {
            record.picture_missing = true;
        }
        writer.record(&record, picture.as_deref());
    }
    let records = writer.records();
    let body = writer.finish(more);
    log::info!(
        "instance={instance} people job bundle after={} records={records} bytes={} more={more}",
        query.after,
        body.len()
    );
    Ok((
        [(CONTENT_TYPE, HeaderValue::from_static(BUNDLE_CONTENT_TYPE))],
        body,
    )
        .into_response())
}

async fn cancel(
    State(state): State<AppState>,
    auth: Authed,
    Path(job_id): Path<String>,
) -> ApiResult<StatusCode> {
    check_job_id(&job_id)?;
    people_job::cancel(&state, &auth.instance, &job_id).await?;
    Ok(StatusCode::NO_CONTENT)
}
