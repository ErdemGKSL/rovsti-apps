//! Status updates (stories): the list, their media, read receipts, and the
//! sweeper that drops them after 24 hours.

use std::collections::HashSet;
use std::time::Duration;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::Response;
use axum::routing::{get, post};
use axum::{Json, Router};
use rovsti_wa_proto::api::routes as r;
use rovsti_wa_proto::status::MAX_STATUS_IDS;
use rovsti_wa_proto::{GatewayEvent, MarkStatusViewed, StatusKind, StatusList, group_statuses};
use tokio::task::JoinHandle;

use crate::AppState;
use crate::auth::Authed;
use crate::driver::status_receipts;
use crate::error::HttpError;
use crate::media_cache::{DEFAULT_MAX_BYTES, MediaCache};
use crate::now_ms;
use crate::routes::media_response;
use crate::store::InstanceId;

/// How often expired status updates are deleted.
pub const PURGE_EVERY: Duration = Duration::from_secs(10 * 60);

type ApiResult<T> = Result<T, HttpError>;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(r::STATUSES, get(list))
        .route(r::STATUS_VIEWED, post(viewed))
        .route(r::STATUS_MEDIA, get(media))
        .route(r::STATUS_THUMBNAIL, get(thumbnail))
}

fn check_id(id: &str) -> ApiResult<()> {
    if id.trim().is_empty() || id.len() > 128 {
        return Err(HttpError::bad_request("status id must be 1 to 128 bytes"));
    }
    Ok(())
}

/// The media cache key of a status update (apart from message ids).
fn cache_key(id: &str) -> String {
    format!("status:{id}")
}

fn cache(state: &AppState, instance: &InstanceId) -> MediaCache {
    MediaCache::new(
        state.config().media_dir(instance.as_str()),
        DEFAULT_MAX_BYTES,
    )
}

async fn list(State(state): State<AppState>, auth: Authed) -> ApiResult<Json<StatusList>> {
    let now = now_ms();
    let items = state.store().statuses(auth.instance, now).await?;
    Ok(Json(group_statuses(items, now)))
}

async fn thumbnail(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<String>,
) -> ApiResult<Response> {
    check_id(&id)?;
    let bytes = state
        .store()
        .status_thumbnail(auth.instance, id, now_ms())
        .await?
        .ok_or_else(|| HttpError::not_found("the status update has no thumbnail"))?;
    Ok(media_response("image/jpeg", bytes))
}

async fn media(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<String>,
) -> ApiResult<Response> {
    check_id(&id)?;
    let (item, raw) = state
        .store()
        .status_source(auth.instance.clone(), id.clone(), now_ms())
        .await?
        .ok_or_else(|| HttpError::not_found("no such status update"))?;
    if !matches!(item.kind, StatusKind::Image | StatusKind::Video) {
        return Err(HttpError::bad_request("the status update has no media"));
    }
    let mime = item.media.map(|m| m.mime).unwrap_or_default();
    let cache = cache(&state, &auth.instance);
    let key = cache_key(&id);
    let cached = {
        let (cache, key) = (cache.clone(), key.clone());
        tokio::task::spawn_blocking(move || cache.get(&key))
            .await
            .unwrap_or(None)
    };
    if let Some(bytes) = cached {
        return Ok(media_response(&mime, bytes));
    }
    let raw = raw.ok_or_else(|| HttpError::not_found("the media of this update is not kept"))?;
    let bytes = state.driver().download_media(&auth.instance, raw).await?;
    let to_store = bytes.clone();
    // A failed cache write only costs a download next time.
    match tokio::task::spawn_blocking(move || cache.put(&key, &to_store)).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => log::warn!("{}: caching status media failed: {e}", auth.instance),
        Err(e) => log::warn!("{}: caching status media failed: {e}", auth.instance),
    }
    Ok(media_response(&mime, bytes))
}

/// Marks updates seen: a read receipt per author goes to WhatsApp for the
/// ones not seen before (never for my own), then the store and the apps
/// learn it.
async fn viewed(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<MarkStatusViewed>,
) -> ApiResult<StatusCode> {
    if req.ids.is_empty() || req.ids.len() > MAX_STATUS_IDS {
        return Err(HttpError::bad_request(format!(
            "send 1 to {MAX_STATUS_IDS} status ids"
        )));
    }
    for id in &req.ids {
        check_id(id)?;
    }
    let targets = state
        .store()
        .status_targets(auth.instance.clone(), req.ids, now_ms())
        .await?;
    let fresh: Vec<_> = targets.iter().filter(|t| !t.from_me && !t.viewed).collect();
    if fresh.is_empty() {
        return Ok(StatusCode::NO_CONTENT);
    }
    let receipts = status_receipts(fresh.iter().map(|t| (t.participant.clone(), t.id.clone())));
    state
        .driver()
        .mark_status_viewed(&auth.instance, receipts)
        .await?;
    let ids = fresh.iter().map(|t| t.id.clone()).collect();
    let authors = state
        .store()
        .mark_statuses_viewed(auth.instance.clone(), ids)
        .await?;
    let sink = state.sink(&auth.instance);
    for author in authors {
        sink.publish(GatewayEvent::StatusChanged {
            author: Some(author),
        });
    }
    Ok(StatusCode::NO_CONTENT)
}

/// Deletes expired updates (and their cached media) once and tells each
/// affected instance's apps. Returns how many went.
pub async fn purge(state: &AppState) -> usize {
    let gone = match state.store().purge_statuses(now_ms()).await {
        Ok(gone) => gone,
        Err(e) => {
            log::warn!("purging status updates failed: {e}");
            return 0;
        }
    };
    let count = gone.len();
    let mut told = HashSet::new();
    for (instance, id) in gone {
        let cache = cache(state, &instance);
        let key = cache_key(&id);
        // Best effort: the cache evicts what is left anyway.
        let _ = tokio::task::spawn_blocking(move || cache.remove(&key)).await;
        if told.insert(instance.clone()) {
            state
                .sink(&instance)
                .publish(GatewayEvent::StatusChanged { author: None });
        }
    }
    count
}

/// Starts the background task that purges expired updates every `every`.
pub fn spawn_purger(state: AppState, every: Duration) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(every);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tick.tick().await;
            purge(&state).await;
        }
    })
}
