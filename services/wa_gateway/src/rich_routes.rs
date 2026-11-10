//! Routes for the richer WhatsApp features: edits, deletes, stars, played
//! receipts, chat settings, profiles, profile pictures and thumbnails.

use axum::extract::{Path, Query, State};
use axum::http::header::{CACHE_CONTROL, CONTENT_TYPE, ETAG, HeaderValue, IF_NONE_MATCH};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse as _, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use rovsti_wa_proto::api::routes as r;
use rovsti_wa_proto::meta::picture_etag;
use rovsti_wa_proto::{
    ChatAction, ChatActionKind, DELETED_BY_ME, DeleteForMe, EditMessage, ErrorCode, MAX_PROFILES,
    MUTED_FOREVER, MarkRead, MessageBody, ProfilesRequest, RevokeMessage, StarMessage, WaMessage,
    WaProfile,
};
use serde::Deserialize;

use crate::AppState;
use crate::auth::Authed;
use crate::driver::{DriverError, MessageRef, PictureFetch};
use crate::error::HttpError;
use crate::media_cache::MediaCache;
use crate::now_ms;
use crate::routes::{MAX_TEXT_BYTES, check_jid, check_read, media_response};
use crate::store::{ChatChange, InstanceId, MessageChange, ProfileChange};

/// WhatsApp lets the author edit a message for 15 minutes.
pub const EDIT_WINDOW_MS: i64 = 15 * 60 * 1000;
/// Profiles and picture ids are asked again after a day.
pub const PROFILE_TTL_MS: i64 = 24 * 60 * 60 * 1000;
/// Per-instance budget of the profile picture cache.
pub const PICTURE_CACHE_BYTES: u64 = 64 * 1024 * 1024;

type ApiResult<T> = Result<T, HttpError>;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(r::MESSAGE_THUMBNAIL, get(thumbnail))
        .route(r::EDIT, post(edit))
        .route(r::REVOKE, post(revoke))
        .route(r::DELETE_FOR_ME, post(delete_for_me))
        .route(r::STAR, post(star))
        .route(r::MARK_PLAYED, post(mark_played))
        .route(r::CHAT_ACTION, post(chat_action))
        .route(r::PROFILES, post(profiles))
        .route(r::PICTURE, get(picture))
}

fn check_id(id: &str) -> ApiResult<()> {
    if id.trim().is_empty() || id.len() > 128 {
        return Err(HttpError::bad_request("message_id must be 1 to 128 bytes"));
    }
    Ok(())
}

/// The stored message `id` of `chat`, or 404.
async fn stored(
    state: &AppState,
    instance: &InstanceId,
    chat: &str,
    id: &str,
) -> ApiResult<WaMessage> {
    check_jid(chat)?;
    check_id(id)?;
    state
        .store()
        .message(instance.clone(), chat.to_owned(), id.to_owned())
        .await?
        .ok_or_else(|| HttpError::not_found("no such message"))
}

/// A JID whose profile can be asked for: a user (number or LID) or a group.
pub fn profile_jid(jid: &str) -> ApiResult<String> {
    let jid = jid.trim();
    let parsed = crate::wa::convert::parse_jid(jid)?;
    let user_ok = !parsed.user.is_empty() && !jid.contains(':');
    let ok = if parsed.server == "s.whatsapp.net" {
        user_ok && parsed.user.bytes().all(|b| b.is_ascii_digit())
    } else {
        user_ok && (parsed.server == "lid" || parsed.server == "g.us")
    };
    if ok {
        Ok(jid.to_owned())
    } else {
        Err(HttpError::bad_request(format!(
            "{jid:?} is not a user or group jid"
        )))
    }
}

/// The image type of a thumbnail or picture, by its first bytes.
fn image_mime(bytes: &[u8]) -> &'static str {
    if bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
        "image/png"
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        "image/webp"
    } else {
        "image/jpeg"
    }
}

async fn thumbnail(
    State(state): State<AppState>,
    auth: Authed,
    Path(message_id): Path<String>,
) -> ApiResult<Response> {
    check_id(&message_id)?;
    let bytes = state
        .store()
        .thumbnail(auth.instance, message_id)
        .await?
        .ok_or_else(|| HttpError::not_found("the message has no thumbnail"))?;
    Ok(media_response(image_mime(&bytes), bytes))
}

async fn edit(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<EditMessage>,
) -> ApiResult<StatusCode> {
    if req.text.trim().is_empty() || req.text.len() > MAX_TEXT_BYTES {
        return Err(HttpError::bad_request("text must be 1 byte to 64 KiB"));
    }
    let message = stored(&state, &auth.instance, &req.chat, &req.message_id).await?;
    if !message.from_me {
        return Err(HttpError::bad_request(
            "only your own messages can be edited",
        ));
    }
    if !crate::wa::app_state::is_editable(&message.body) {
        return Err(HttpError::bad_request("this message cannot be edited"));
    }
    let now = now_ms();
    if now.saturating_sub(message.timestamp_ms) > EDIT_WINDOW_MS {
        return Err(HttpError::bad_request(
            "messages can only be edited for 15 minutes",
        ));
    }
    state
        .driver()
        .edit_message(&auth.instance, req.clone())
        .await?;
    let change = MessageChange::Edit {
        body: MessageBody::Text(req.text),
        edited_ms: now,
    };
    state
        .sink(&auth.instance)
        .change_message(&message.chat, &message.id, change)
        .await;
    Ok(StatusCode::NO_CONTENT)
}

async fn revoke(
    State(state): State<AppState>,
    auth: Authed,
    Json(mut req): Json<RevokeMessage>,
) -> ApiResult<StatusCode> {
    let message = stored(&state, &auth.instance, &req.chat, &req.message_id).await?;
    req.sender = req.sender.filter(|s| !s.trim().is_empty());
    match &req.sender {
        None if !message.from_me => {
            return Err(HttpError::bad_request(
                "name the sender to delete someone else's message (group admins only)",
            ));
        }
        Some(sender) => {
            check_jid(sender)?;
            if !crate::wa::convert::is_group(&message.chat) {
                return Err(HttpError::bad_request(
                    "only group admins can delete other people's messages",
                ));
            }
        }
        None => {}
    }
    state.driver().revoke_message(&auth.instance, req).await?;
    state
        .sink(&auth.instance)
        .change_message(
            &message.chat,
            &message.id,
            MessageChange::Revoke {
                by: DELETED_BY_ME.to_owned(),
                at_ms: now_ms(),
            },
        )
        .await;
    Ok(StatusCode::NO_CONTENT)
}

async fn delete_for_me(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<DeleteForMe>,
) -> ApiResult<StatusCode> {
    let message = stored(&state, &auth.instance, &req.chat, &req.message_id).await?;
    state
        .driver()
        .delete_for_me(&auth.instance, MessageRef::from(&message))
        .await?;
    state
        .sink(&auth.instance)
        .delete_message(&message.chat, &message.id)
        .await;
    Ok(StatusCode::NO_CONTENT)
}

async fn star(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<StarMessage>,
) -> ApiResult<StatusCode> {
    let message = stored(&state, &auth.instance, &req.chat, &req.message_id).await?;
    state
        .driver()
        .star_message(&auth.instance, MessageRef::from(&message), req.starred)
        .await?;
    state
        .sink(&auth.instance)
        .change_message(&message.chat, &message.id, MessageChange::Star(req.starred))
        .await;
    Ok(StatusCode::NO_CONTENT)
}

async fn mark_played(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<MarkRead>,
) -> ApiResult<StatusCode> {
    check_read(&req)?;
    state.driver().mark_played(&auth.instance, req).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// The stored setting a chat action leads to.
fn chat_change(action: &ChatActionKind) -> ChatChange {
    match action {
        ChatActionKind::Archive { archived } => ChatChange::Archived(*archived),
        ChatActionKind::Pin { pinned } => ChatChange::Pinned(*pinned),
        ChatActionKind::Mute { until_ms } => {
            ChatChange::Muted(Some(until_ms.unwrap_or(MUTED_FOREVER)))
        }
        ChatActionKind::Unmute => ChatChange::Muted(None),
        ChatActionKind::MarkRead { read } => ChatChange::MarkedUnread(!read),
    }
}

async fn chat_action(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<ChatAction>,
) -> ApiResult<StatusCode> {
    check_jid(&req.chat)?;
    if let ChatActionKind::Mute {
        until_ms: Some(until),
    } = req.action
        && until <= now_ms()
    {
        return Err(HttpError::bad_request("until_ms must be in the future"));
    }
    let change = chat_change(&req.action);
    let chat = req.chat.trim().to_owned();
    state.driver().chat_action(&auth.instance, req).await?;
    state.sink(&auth.instance).change_chat(&chat, change).await;
    Ok(StatusCode::NO_CONTENT)
}

/// Validates a profile request: 1 to `MAX_PROFILES` distinct JIDs, trimmed.
fn check_profiles(req: &ProfilesRequest) -> ApiResult<Vec<String>> {
    if req.jids.is_empty() || req.jids.len() > MAX_PROFILES {
        return Err(HttpError::bad_request(format!(
            "jids must hold 1 to {MAX_PROFILES} jids"
        )));
    }
    let mut jids: Vec<String> = Vec::with_capacity(req.jids.len());
    for jid in &req.jids {
        let jid = profile_jid(jid)?;
        if !jids.contains(&jid) {
            jids.push(jid);
        }
    }
    Ok(jids)
}

/// Whether a stored profile must be asked for again at `now`.
pub fn profile_is_stale(profile: Option<&WaProfile>, refresh: bool, now: i64) -> bool {
    refresh
        || profile
            .is_none_or(|p| p.fetched_ms <= 0 || now.saturating_sub(p.fetched_ms) >= PROFILE_TTL_MS)
}

async fn profiles(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<ProfilesRequest>,
) -> ApiResult<Json<Vec<WaProfile>>> {
    let jids = check_profiles(&req)?;
    let store = state.store();
    let mut known = store.profiles(auth.instance.clone(), jids.clone()).await?;
    let now = now_ms();
    let stale: Vec<String> = jids
        .iter()
        .filter(|jid| profile_is_stale(known.iter().find(|p| &p.jid == *jid), req.refresh, now))
        .cloned()
        .collect();
    if !stale.is_empty() {
        if !state.charge_profiles(&auth.instance, stale.len()) {
            return Err(HttpError::new(
                ErrorCode::RateLimited,
                "too many profile requests",
            ));
        }
        match state.driver().profiles(&auth.instance, stale).await {
            Ok(fetched) => {
                let sink = state.sink(&auth.instance);
                for profile in fetched {
                    let jid = profile.jid.clone();
                    sink.change_profile(&jid, ProfileChange::Fetched(profile))
                        .await;
                }
                known = store.profiles(auth.instance.clone(), jids.clone()).await?;
            }
            Err(DriverError::BadRequest(message)) => return Err(HttpError::bad_request(message)),
            // Serve what is known; the next request asks again.
            Err(e) => log::warn!("{}: profile query failed: {e}", auth.instance),
        }
    }
    let ordered = jids
        .into_iter()
        .map(|jid| {
            known
                .iter()
                .find(|p| p.jid == jid)
                .cloned()
                .unwrap_or(WaProfile {
                    jid,
                    ..Default::default()
                })
        })
        .collect();
    Ok(Json(ordered))
}

#[derive(Debug, Deserialize)]
struct PictureQuery {
    /// The full image instead of the small preview.
    #[serde(default)]
    full: bool,
}

/// True when the client already holds picture `id`.
fn client_has(headers: &HeaderMap, id: &str) -> bool {
    let etag = picture_etag(id);
    headers
        .get_all(IF_NONE_MATCH)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .any(|tag| {
            let tag = tag.trim();
            tag == "*" || tag == etag || tag.strip_prefix("W/") == Some(etag.as_str())
        })
}

fn picture_headers(id: &str) -> [(axum::http::HeaderName, HeaderValue); 2] {
    let etag = HeaderValue::from_str(&picture_etag(id))
        .unwrap_or_else(|_| HeaderValue::from_static("\"\""));
    [
        (ETAG, etag),
        (CACHE_CONTROL, HeaderValue::from_static("private, no-cache")),
    ]
}

fn picture_response(headers: &HeaderMap, id: &str, bytes: Vec<u8>) -> Response {
    if client_has(headers, id) {
        return (StatusCode::NOT_MODIFIED, picture_headers(id)).into_response();
    }
    let mut response = media_response(image_mime(&bytes), bytes);
    response.headers_mut().extend(picture_headers(id));
    if !response.headers().contains_key(CONTENT_TYPE) {
        response
            .headers_mut()
            .insert(CONTENT_TYPE, HeaderValue::from_static("image/jpeg"));
    }
    response
}

/// The cache key of one picture size of one picture id.
fn picture_key(jid: &str, preview: bool, id: &str) -> String {
    let size = if preview { "preview" } else { "full" };
    format!("{jid}\n{size}\n{id}")
}

async fn cached(cache: &MediaCache, key: String) -> Option<Vec<u8>> {
    let cache = cache.clone();
    tokio::task::spawn_blocking(move || cache.get(&key))
        .await
        .unwrap_or(None)
}

async fn picture(
    State(state): State<AppState>,
    auth: Authed,
    Path(jid): Path<String>,
    Query(query): Query<PictureQuery>,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let jid = profile_jid(&jid)?;
    let preview = !query.full;
    let (known, checked_ms) = state
        .store()
        .picture_state(auth.instance.clone(), jid.clone())
        .await?;
    let cache = MediaCache::new(
        state.config().pictures_dir(auth.instance.as_str()),
        PICTURE_CACHE_BYTES,
    );
    let fresh = checked_ms > 0 && now_ms().saturating_sub(checked_ms) < PROFILE_TTL_MS;
    let held = match &known {
        Some(id) => cached(&cache, picture_key(&jid, preview, id))
            .await
            .map(|bytes| (id.clone(), bytes)),
        None => None,
    };
    if fresh {
        match (&known, held) {
            (None, _) => return Err(HttpError::not_found("no profile picture")),
            (Some(id), _) if client_has(&headers, id) => {
                return Ok((StatusCode::NOT_MODIFIED, picture_headers(id)).into_response());
            }
            (Some(_), Some((id, bytes))) => return Ok(picture_response(&headers, &id, bytes)),
            (Some(_), None) => {
                return fetch_picture(state, auth, jid, preview, None, &headers).await;
            }
        }
    }
    fetch_picture(state, auth, jid, preview, held, &headers).await
}

/// Asks WhatsApp for the picture of `jid`; `held` is the cached copy of the
/// picture the gateway knows, if any.
async fn fetch_picture(
    state: AppState,
    auth: Authed,
    jid: String,
    preview: bool,
    held: Option<(String, Vec<u8>)>,
    headers: &HeaderMap,
) -> ApiResult<Response> {
    if !state.charge_profiles(&auth.instance, 1) {
        return Err(HttpError::new(
            ErrorCode::RateLimited,
            "too many profile picture requests",
        ));
    }
    let known_id = held.as_ref().map(|(id, _)| id.clone());
    let fetched = state
        .driver()
        .fetch_picture(&auth.instance, jid.clone(), preview, known_id)
        .await?;
    let sink = state.sink(&auth.instance);
    match (fetched, held) {
        (PictureFetch::None, _) => {
            sink.change_profile(&jid, ProfileChange::Picture(None))
                .await;
            Err(HttpError::not_found("no profile picture"))
        }
        (PictureFetch::Unchanged, Some((id, bytes))) => {
            sink.change_profile(&jid, ProfileChange::Picture(Some(id.clone())))
                .await;
            Ok(picture_response(headers, &id, bytes))
        }
        (PictureFetch::Unchanged, None) => Err(HttpError::new(
            ErrorCode::Internal,
            "WhatsApp request failed",
        )),
        (PictureFetch::New { id, bytes }, _) => {
            let cache = MediaCache::new(
                state.config().pictures_dir(auth.instance.as_str()),
                PICTURE_CACHE_BYTES,
            );
            let (key, to_store) = (picture_key(&jid, preview, &id), bytes.clone());
            match tokio::task::spawn_blocking(move || cache.put(&key, &to_store)).await {
                Ok(Ok(())) => {}
                Ok(Err(e)) => log::warn!("{}: caching a picture failed: {e}", auth.instance),
                Err(e) => log::warn!("{}: caching a picture failed: {e}", auth.instance),
            }
            sink.change_profile(&jid, ProfileChange::Picture(Some(id.clone())))
                .await;
            Ok(picture_response(headers, &id, bytes))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_jids() {
        for ok in [
            "905321234567@s.whatsapp.net",
            "120363-1@g.us",
            "1234567@lid",
            " 905321234567@s.whatsapp.net ",
        ] {
            assert!(profile_jid(ok).is_ok(), "{ok}");
        }
        for bad in [
            "",
            "905321234567:3@s.whatsapp.net",
            "abc@s.whatsapp.net",
            "status@broadcast",
            "x@newsletter",
        ] {
            assert!(profile_jid(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn staleness() {
        let p = |fetched_ms| WaProfile {
            jid: "j".into(),
            fetched_ms,
            ..Default::default()
        };
        let now = 10 * PROFILE_TTL_MS;
        assert!(profile_is_stale(None, false, now));
        assert!(profile_is_stale(Some(&p(0)), false, now));
        assert!(!profile_is_stale(Some(&p(now - 1)), false, now));
        assert!(profile_is_stale(Some(&p(now - 1)), true, now));
        assert!(profile_is_stale(Some(&p(now - PROFILE_TTL_MS)), false, now));
    }

    #[test]
    fn if_none_match_forms() {
        let mut headers = HeaderMap::new();
        assert!(!client_has(&headers, "7"));
        headers.insert(IF_NONE_MATCH, HeaderValue::from_static("\"6\", W/\"7\""));
        assert!(client_has(&headers, "7"));
        assert!(!client_has(&headers, "8"));
        headers.insert(IF_NONE_MATCH, HeaderValue::from_static("*"));
        assert!(client_has(&headers, "8"));
    }

    #[test]
    fn chat_actions_map_to_settings() {
        assert_eq!(
            chat_change(&ChatActionKind::Mute { until_ms: None }),
            ChatChange::Muted(Some(MUTED_FOREVER))
        );
        assert_eq!(
            chat_change(&ChatActionKind::Mute { until_ms: Some(5) }),
            ChatChange::Muted(Some(5))
        );
        assert_eq!(
            chat_change(&ChatActionKind::Unmute),
            ChatChange::Muted(None)
        );
        assert_eq!(
            chat_change(&ChatActionKind::MarkRead { read: true }),
            ChatChange::MarkedUnread(false)
        );
    }

    #[test]
    fn image_types_are_sniffed() {
        assert_eq!(image_mime(&[0x89, b'P', b'N', b'G', 1]), "image/png");
        assert_eq!(image_mime(b"RIFF\0\0\0\0WEBPVP8 "), "image/webp");
        assert_eq!(image_mime(&[0xFF, 0xD8]), "image/jpeg");
        assert_ne!(picture_key("j", true, "1"), picture_key("j", false, "1"));
    }
}
