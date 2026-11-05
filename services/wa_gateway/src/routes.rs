//! The HTTP API (route constants come from `rovsti_wa_proto::api::routes`).

use std::time::Duration;

use axum::body::Bytes;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::http::StatusCode;
use axum::http::header::{CONTENT_TYPE, HeaderValue};
use axum::response::{IntoResponse as _, Response};
use axum::routing::{delete, get, post, put};
use axum::{Json, Router};
use rovsti_wa_proto::api::routes as r;
use rovsti_wa_proto::{
    ChatState, ChatSummary, CreateInstance, CreatedInstance, ErrorCode, EventEnvelope,
    GatewayEvent, GroupInfo, InstanceState, InstanceStatus, IssuedKey, KeyId, KeyInfo, LinkRequest,
    LookupUsers, MAX_LOOKUP, MarkRead, MediaKind, MessageBody, MintKey, PushRegistration,
    ScheduleText, ScheduledState, ScheduledText, SendReaction, SendText, Sent, SetPresence,
    SubscribePresence, WaMessage, WaUser,
};
use serde::Deserialize;
use tokio::sync::broadcast::error::RecvError;

use crate::auth::Authed;
use crate::driver::SendMedia;
use crate::error::HttpError;
use crate::hub::Replay;
use crate::media_cache::{DEFAULT_MAX_BYTES, MediaCache};
use crate::now_ms;
use crate::store::{InstanceId, NewKey, parse_key_id};
use crate::{AppState, store::StoreError};

const MAX_LABEL_CHARS: usize = 64;
const MAX_PUSH_TOKEN_BYTES: usize = 4096;
const MAX_TEXT_BYTES: usize = 64 * 1024;
/// Pending scheduled texts one instance may hold.
const MAX_PENDING_SCHEDULED: u32 = 200;
/// A scheduled text must be due between these offsets from now.
const MIN_SCHEDULE_LEAD_MS: i64 = 30_000;
const MAX_SCHEDULE_LEAD_MS: i64 = 365 * 24 * 60 * 60 * 1000;
const MAX_READ_IDS: usize = 200;
const MAX_MIME_BYTES: usize = 127;
const MAX_CAPTION_BYTES: usize = 8 * 1024;
const MAX_EMOJI_BYTES: usize = 64;
const DEFAULT_PAGE: u32 = 50;
const MAX_PAGE: u32 = 200;
const SERVER_PING: Duration = Duration::from_secs(30);

pub fn router(state: AppState) -> Router {
    let media_limit = state.config().max_media_bytes;
    Router::new()
        .route(r::HEALTHZ, get(healthz))
        .route(r::INSTANCES, post(create_instance))
        .route(r::INSTANCE, get(get_instance).delete(delete_instance))
        .route(r::INSTANCE_LINK, post(relink))
        .route(r::KEYS, get(list_keys).post(mint_key))
        .route(r::KEY, delete(revoke_key))
        .route(r::PUSH, put(register_push))
        .route(r::EVENTS, get(events))
        .route(r::CHATS, get(chats))
        .route(r::CHAT_MESSAGES, get(chat_messages))
        .route(r::SEND_TEXT, post(send_text))
        .route(r::SCHEDULED, get(list_scheduled).post(schedule_text))
        .route(r::SCHEDULED_ONE, delete(cancel_scheduled))
        .route(r::MARK_READ, post(mark_read))
        .route(r::PRESENCE, post(set_presence))
        .route(r::CHAT_STATE, post(chat_state))
        .route(r::PRESENCE_SUBSCRIBE, post(subscribe_presence))
        .route(
            r::SEND_MEDIA,
            post(send_media).layer(DefaultBodyLimit::max(media_limit)),
        )
        .route(r::MESSAGE_MEDIA, get(message_media))
        .route(r::REACT, post(react))
        .route(r::CONTACTS_LOOKUP, post(lookup_users))
        .route(r::GROUPS, get(groups))
        .route(r::GROUP, get(group))
        .merge(crate::wa::calls::routes())
        .with_state(state)
}

type ApiResult<T> = Result<T, HttpError>;

/// Fresh random key material: the key to hand out once and the hash row to
/// store.
fn fresh_key(label: &str) -> ApiResult<(rovsti_wa_proto::ConnectKey, NewKey)> {
    let mut secret = [0u8; 32];
    let mut id = [0u8; 8];
    getrandom::fill(&mut secret)
        .and_then(|()| getrandom::fill(&mut id))
        .map_err(|e| {
            log::error!("no randomness: {e}");
            HttpError::new(ErrorCode::Internal, "internal error")
        })?;
    let key = rovsti_wa_proto::ConnectKey::from_bytes(&secret);
    let new = NewKey {
        key_id: KeyId::from_bytes(&id),
        hash: key.hash(),
        label: label.to_owned(),
    };
    Ok((key, new))
}

fn fresh_instance_id() -> ApiResult<InstanceId> {
    let mut bytes = [0u8; 12];
    getrandom::fill(&mut bytes).map_err(|e| {
        log::error!("no randomness: {e}");
        HttpError::new(ErrorCode::Internal, "internal error")
    })?;
    Ok(InstanceId::from_bytes(&bytes))
}

fn clean_label(label: &str) -> ApiResult<String> {
    let label = label.trim();
    if label.is_empty() || label.chars().count() > MAX_LABEL_CHARS {
        return Err(HttpError::bad_request(format!(
            "label must be 1 to {MAX_LABEL_CHARS} characters"
        )));
    }
    Ok(label.to_owned())
}

fn check_link(link: &LinkRequest) -> ApiResult<()> {
    link.pair_phone()
        .map(drop)
        .map_err(|e| HttpError::bad_request(e.to_string()))
}

async fn healthz() -> &'static str {
    "ok"
}

async fn create_instance(
    State(state): State<AppState>,
    Json(req): Json<CreateInstance>,
) -> ApiResult<(StatusCode, Json<CreatedInstance>)> {
    check_link(&req.link)?;
    let id = fresh_instance_id()?;
    let (key, new_key) = fresh_key(&format!("{:?}", req.app).to_lowercase())?;
    let key_id = new_key.key_id.to_string();
    state
        .store()
        .create_instance(id.clone(), new_key, now_ms(), state.config().max_instances)
        .await
        .map_err(|e| match e {
            StoreError::LimitReached => HttpError::new(
                ErrorCode::LimitReached,
                format!(
                    "this gateway allows {} WhatsApp instances",
                    state.config().max_instances
                ),
            ),
            other => other.into(),
        })?;

    let sink = state.sink(&id);
    if let Err(e) = state.driver().start(id.clone(), Some(req.link), sink).await {
        // Do not leave an instance nobody holds a working session for.
        if let Err(del) = state.store().delete_instance(id.clone()).await {
            log::error!("cleanup of {id} failed: {del}");
        }
        state.hub().remove(&id);
        return Err(e.into());
    }
    Ok((
        StatusCode::CREATED,
        Json(CreatedInstance {
            instance_id: id.to_string(),
            key: key.as_str().to_owned(),
            key_id,
        }),
    ))
}

async fn get_instance(
    State(state): State<AppState>,
    auth: Authed,
) -> ApiResult<Json<InstanceStatus>> {
    state
        .store()
        .instance_status(auth.instance)
        .await?
        .map(Json)
        .ok_or_else(|| HttpError::not_found("instance not found"))
}

async fn relink(
    State(state): State<AppState>,
    auth: Authed,
    Json(link): Json<LinkRequest>,
) -> ApiResult<StatusCode> {
    check_link(&link)?;
    let status = state
        .store()
        .instance_status(auth.instance.clone())
        .await?
        .ok_or_else(|| HttpError::not_found("instance not found"))?;
    if matches!(
        status.state,
        InstanceState::Connected | InstanceState::Linking
    ) {
        return Err(HttpError::new(
            ErrorCode::Conflict,
            "the instance is already linked or linking",
        ));
    }
    state
        .store()
        .set_state(auth.instance.clone(), InstanceState::Linking, None, None)
        .await?;
    let sink = state.sink(&auth.instance);
    state
        .driver()
        .start(auth.instance.clone(), Some(link), sink)
        .await?;
    Ok(StatusCode::ACCEPTED)
}

async fn delete_instance(State(state): State<AppState>, auth: Authed) -> ApiResult<StatusCode> {
    if let Err(e) = state.driver().logout(&auth.instance).await {
        // The account may already be unlinked; deleting must still work.
        log::warn!("logout of {} failed: {e}", auth.instance);
    }
    state.driver().stop(&auth.instance).await;
    state.store().delete_instance(auth.instance.clone()).await?;
    state.hub().remove(&auth.instance);
    state.forget_calls(&auth.instance);
    state.forget_lookups(&auth.instance);
    Ok(StatusCode::NO_CONTENT)
}

async fn list_keys(State(state): State<AppState>, auth: Authed) -> ApiResult<Json<Vec<KeyInfo>>> {
    let mut keys = state.store().list_keys(auth.instance).await?;
    for key in &mut keys {
        key.current = key.key_id == auth.key_id.as_str();
    }
    Ok(Json(keys))
}

async fn mint_key(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<MintKey>,
) -> ApiResult<(StatusCode, Json<IssuedKey>)> {
    let label = clean_label(&req.label)?;
    let (key, new_key) = fresh_key(&label)?;
    let key_id = new_key.key_id.to_string();
    state
        .store()
        .mint_key(auth.instance, new_key, now_ms())
        .await
        .map_err(|e| match e {
            StoreError::LimitReached => {
                HttpError::new(ErrorCode::LimitReached, "too many connect keys; revoke one")
            }
            other => other.into(),
        })?;
    Ok((
        StatusCode::CREATED,
        Json(IssuedKey {
            key_id,
            key: key.as_str().to_owned(),
            label,
        }),
    ))
}

async fn revoke_key(
    State(state): State<AppState>,
    auth: Authed,
    Path(key_id): Path<String>,
) -> ApiResult<StatusCode> {
    let revoked = state
        .store()
        .revoke_key(auth.instance.clone(), key_id.clone())
        .await?;
    if !revoked {
        return Err(HttpError::not_found("no such connect key"));
    }
    if let Some(id) = parse_key_id(&key_id) {
        state.hub().revoke(&auth.instance, &id);
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn register_push(
    State(state): State<AppState>,
    auth: Authed,
    Json(reg): Json<PushRegistration>,
) -> ApiResult<StatusCode> {
    let token = reg.token.trim();
    if token.is_empty() || token.len() > MAX_PUSH_TOKEN_BYTES {
        return Err(HttpError::bad_request("invalid push token"));
    }
    state
        .store()
        .set_push_token(auth.instance, reg.app, token.to_owned())
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
struct EventsQuery {
    after: Option<u64>,
}

async fn events(
    State(state): State<AppState>,
    auth: Authed,
    Query(query): Query<EventsQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    ws.on_upgrade(move |socket| event_socket(socket, state, auth, query.after))
}

async fn send_envelope(socket: &mut WebSocket, envelope: &EventEnvelope) -> bool {
    match serde_json::to_string(envelope) {
        Ok(text) => socket.send(Message::Text(text.into())).await.is_ok(),
        Err(e) => {
            log::error!("cannot encode event: {e}");
            true
        }
    }
}

/// An event that belongs to this socket alone (it is not in the shared log),
/// stamped with the current head so a client's resume point stays valid.
fn private_event(head_seq: u64, event: GatewayEvent) -> EventEnvelope {
    EventEnvelope {
        seq: head_seq,
        at_ms: now_ms(),
        event,
    }
}

async fn event_socket(mut socket: WebSocket, state: AppState, auth: Authed, after: Option<u64>) {
    let hub = state.hub();
    let mut sub = hub.subscribe(&auth.instance, after);

    // The key may have been revoked between the handshake and now.
    match state.store().key_active(auth.key_id.clone()).await {
        Ok(true) => {}
        Ok(false) => {
            let end = private_event(hub.head_seq(&auth.instance), GatewayEvent::KeyRevoked);
            send_envelope(&mut socket, &end).await;
            let _ = socket.send(Message::Close(None)).await;
            return;
        }
        Err(e) => {
            log::error!("key check failed: {e}");
            return;
        }
    }

    match sub.replay {
        Replay::Events(events) => {
            for envelope in &events {
                if !send_envelope(&mut socket, envelope).await {
                    return;
                }
            }
        }
        Replay::Gap { head_seq } => {
            if !send_envelope(&mut socket, &private_event(head_seq, GatewayEvent::Resync)).await {
                return;
            }
        }
    }

    let mut ping = tokio::time::interval(SERVER_PING);
    ping.tick().await;
    loop {
        tokio::select! {
            live = sub.events.recv() => match live {
                Ok(envelope) => {
                    if !send_envelope(&mut socket, &envelope).await {
                        return;
                    }
                }
                Err(RecvError::Lagged(missed)) => {
                    log::warn!("{}: socket lagged by {missed} events", auth.instance);
                    let resync = private_event(hub.head_seq(&auth.instance), GatewayEvent::Resync);
                    if !send_envelope(&mut socket, &resync).await {
                        return;
                    }
                }
                // The instance was deleted.
                Err(RecvError::Closed) => break,
            },
            revoked = sub.revoked.recv() => match revoked {
                Ok(id) if id == auth.key_id => {
                    let end = private_event(hub.head_seq(&auth.instance), GatewayEvent::KeyRevoked);
                    send_envelope(&mut socket, &end).await;
                    break;
                }
                Ok(_) | Err(RecvError::Lagged(_)) => {}
                Err(RecvError::Closed) => break,
            },
            _ = ping.tick() => {
                if socket.send(Message::Ping(Vec::new().into())).await.is_err() {
                    return;
                }
            }
            incoming = socket.recv() => match incoming {
                None | Some(Err(_)) | Some(Ok(Message::Close(_))) => return,
                // Pings are answered by axum; the client sends nothing else.
                Some(Ok(_)) => {}
            },
        }
    }
    let _ = socket.send(Message::Close(None)).await;
}

async fn chats(State(state): State<AppState>, auth: Authed) -> ApiResult<Json<Vec<ChatSummary>>> {
    Ok(Json(state.store().chats(auth.instance).await?))
}

#[derive(Debug, Deserialize)]
struct PageQuery {
    before: Option<i64>,
    limit: Option<u32>,
}

async fn chat_messages(
    State(state): State<AppState>,
    auth: Authed,
    Path(jid): Path<String>,
    Query(page): Query<PageQuery>,
) -> ApiResult<Json<Vec<WaMessage>>> {
    let limit = page.limit.unwrap_or(DEFAULT_PAGE).clamp(1, MAX_PAGE);
    Ok(Json(
        state
            .store()
            .messages(auth.instance, jid, page.before, limit)
            .await?,
    ))
}

async fn send_text(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<SendText>,
) -> ApiResult<Json<Sent>> {
    if req.chat.trim().is_empty() {
        return Err(HttpError::bad_request("chat is empty"));
    }
    if req.text.is_empty() || req.text.len() > MAX_TEXT_BYTES {
        return Err(HttpError::bad_request("text must be 1 byte to 64 KiB"));
    }
    Ok(Json(state.driver().send_text(&auth.instance, req).await?))
}

/// Checks that `send_at_ms` is 30 s to 365 days after `now`.
fn check_send_at(send_at_ms: i64, now: i64) -> ApiResult<()> {
    let lead = send_at_ms.saturating_sub(now);
    if (MIN_SCHEDULE_LEAD_MS..=MAX_SCHEDULE_LEAD_MS).contains(&lead) {
        Ok(())
    } else {
        Err(HttpError::bad_request(
            "send_at_ms must be 30 seconds to 365 days from now",
        ))
    }
}

async fn schedule_text(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<ScheduleText>,
) -> ApiResult<(StatusCode, Json<ScheduledText>)> {
    check_jid(&req.chat)?;
    if req.text.is_empty() || req.text.len() > MAX_TEXT_BYTES {
        return Err(HttpError::bad_request("text must be 1 byte to 64 KiB"));
    }
    let now = now_ms();
    check_send_at(req.send_at_ms, now)?;
    let mut id = [0u8; 12];
    getrandom::fill(&mut id).map_err(|e| {
        log::error!("no randomness: {e}");
        HttpError::new(ErrorCode::Internal, "internal error")
    })?;
    let item = ScheduledText {
        id: InstanceId::from_bytes(&id).to_string(),
        chat: req.chat,
        text: req.text,
        send_at_ms: req.send_at_ms,
        created_ms: now,
        state: ScheduledState::Pending,
    };
    state
        .store()
        .add_scheduled(auth.instance.clone(), item.clone(), MAX_PENDING_SCHEDULED)
        .await
        .map_err(|e| match e {
            StoreError::LimitReached => HttpError::new(
                ErrorCode::LimitReached,
                format!("at most {MAX_PENDING_SCHEDULED} texts can be scheduled at once"),
            ),
            other => other.into(),
        })?;
    state
        .hub()
        .publish(&auth.instance, now, GatewayEvent::Scheduled(item.clone()));
    Ok((StatusCode::CREATED, Json(item)))
}

async fn list_scheduled(
    State(state): State<AppState>,
    auth: Authed,
) -> ApiResult<Json<Vec<ScheduledText>>> {
    let since = now_ms().saturating_sub(crate::schedule::KEEP_FINISHED_MS);
    Ok(Json(
        state.store().list_scheduled(auth.instance, since).await?,
    ))
}

async fn cancel_scheduled(
    State(state): State<AppState>,
    auth: Authed,
    Path(id): Path<String>,
) -> ApiResult<StatusCode> {
    if state.store().cancel_scheduled(auth.instance, id).await? {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(HttpError::not_found("no such pending scheduled text"))
    }
}

/// Rejects a malformed JID before it reaches the driver.
fn check_jid(jid: &str) -> ApiResult<()> {
    crate::wa::convert::parse_jid(jid)
        .map(drop)
        .map_err(HttpError::from)
}

async fn mark_read(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<MarkRead>,
) -> ApiResult<StatusCode> {
    check_jid(&req.chat)?;
    if let Some(sender) = req.sender.as_deref().filter(|s| !s.trim().is_empty()) {
        check_jid(sender)?;
    }
    if req.ids.is_empty() || req.ids.len() > MAX_READ_IDS || req.ids.iter().any(String::is_empty) {
        return Err(HttpError::bad_request(format!(
            "ids must hold 1 to {MAX_READ_IDS} message ids"
        )));
    }
    state.driver().mark_read(&auth.instance, req).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn set_presence(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<SetPresence>,
) -> ApiResult<StatusCode> {
    state
        .driver()
        .set_presence(&auth.instance, req.available)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn chat_state(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<ChatState>,
) -> ApiResult<StatusCode> {
    check_jid(&req.chat)?;
    state.driver().chat_state(&auth.instance, req).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn subscribe_presence(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<SubscribePresence>,
) -> ApiResult<StatusCode> {
    check_jid(&req.jid)?;
    state
        .driver()
        .subscribe_presence(&auth.instance, req.jid)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize)]
struct SendMediaQuery {
    chat: String,
    kind: MediaKind,
    mime: String,
    caption: Option<String>,
}

/// A MIME type the gateway is willing to store and echo in a header.
fn check_mime(mime: &str) -> ApiResult<()> {
    let ok = !mime.is_empty()
        && mime.len() <= MAX_MIME_BYTES
        && mime.contains('/')
        && mime.bytes().all(|b| (0x20..0x7f).contains(&b));
    if ok {
        Ok(())
    } else {
        Err(HttpError::bad_request(
            "mime must be a MIME type such as image/jpeg",
        ))
    }
}

async fn send_media(
    State(state): State<AppState>,
    auth: Authed,
    Query(query): Query<SendMediaQuery>,
    body: Bytes,
) -> ApiResult<Json<Sent>> {
    check_jid(&query.chat)?;
    check_mime(&query.mime)?;
    if body.is_empty() {
        return Err(HttpError::bad_request("the request body is empty"));
    }
    let caption = query.caption.filter(|c| !c.is_empty());
    if caption
        .as_ref()
        .is_some_and(|c| c.len() > MAX_CAPTION_BYTES)
    {
        return Err(HttpError::bad_request("caption is too long"));
    }
    let req = SendMedia {
        chat: query.chat,
        kind: query.kind,
        mime: query.mime,
        caption,
        data: body.into(),
    };
    Ok(Json(state.driver().send_media(&auth.instance, req).await?))
}

fn media_response(mime: &str, bytes: Vec<u8>) -> Response {
    let content_type = HeaderValue::from_str(mime)
        .unwrap_or_else(|_| HeaderValue::from_static("application/octet-stream"));
    (
        [
            (CONTENT_TYPE, content_type),
            // The bytes come from other people: never let a browser sniff them.
            (
                axum::http::header::X_CONTENT_TYPE_OPTIONS,
                HeaderValue::from_static("nosniff"),
            ),
        ],
        bytes,
    )
        .into_response()
}

async fn message_media(
    State(state): State<AppState>,
    auth: Authed,
    Path(message_id): Path<String>,
) -> ApiResult<Response> {
    let (message, raw) = state
        .store()
        .media_source(auth.instance.clone(), message_id.clone())
        .await?
        .ok_or_else(|| HttpError::not_found("no such message"))?;
    let MessageBody::Media { mime, .. } = message.body else {
        return Err(HttpError::bad_request("the message has no media"));
    };

    let cache = MediaCache::new(
        state.config().media_dir(auth.instance.as_str()),
        DEFAULT_MAX_BYTES,
    );
    let cached = {
        let (cache, key) = (cache.clone(), message_id.clone());
        tokio::task::spawn_blocking(move || cache.get(&key))
            .await
            .unwrap_or(None)
    };
    if let Some(bytes) = cached {
        return Ok(media_response(&mime, bytes));
    }

    let raw = raw.ok_or_else(|| HttpError::not_found("the media of this message is not kept"))?;
    let bytes = state.driver().download_media(&auth.instance, raw).await?;
    let to_store = bytes.clone();
    // A failed cache write only costs a download next time.
    match tokio::task::spawn_blocking(move || cache.put(&message_id, &to_store)).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => log::warn!("{}: caching media failed: {e}", auth.instance),
        Err(e) => log::warn!("{}: caching media failed: {e}", auth.instance),
    }
    Ok(media_response(&mime, bytes))
}

async fn react(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<SendReaction>,
) -> ApiResult<StatusCode> {
    check_jid(&req.chat)?;
    if let Some(sender) = req.sender.as_deref().filter(|s| !s.trim().is_empty()) {
        check_jid(sender)?;
    }
    if req.message_id.is_empty() {
        return Err(HttpError::bad_request("message_id is empty"));
    }
    if req.emoji.len() > MAX_EMOJI_BYTES {
        return Err(HttpError::bad_request("emoji is too long"));
    }
    state.driver().react(&auth.instance, req).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// A one-to-one user JID (`<number>@s.whatsapp.net`), trimmed. Groups,
/// devices and other servers are not looked up.
fn user_jid(jid: &str) -> ApiResult<String> {
    let jid = jid.trim();
    let parsed = crate::wa::convert::parse_jid(jid)?;
    if parsed.server != "s.whatsapp.net"
        || parsed.user.is_empty()
        || !parsed.user.bytes().all(|b| b.is_ascii_digit())
        || jid.contains(':')
    {
        return Err(HttpError::bad_request(format!(
            "{jid:?} is not a user jid (number@s.whatsapp.net)"
        )));
    }
    Ok(jid.to_owned())
}

/// Validates a lookup body: 1 to `MAX_LOOKUP` user JIDs. Returns them
/// trimmed.
fn check_lookup(req: LookupUsers) -> ApiResult<Vec<String>> {
    if req.jids.is_empty() || req.jids.len() > MAX_LOOKUP {
        return Err(HttpError::bad_request(format!(
            "jids must hold 1 to {MAX_LOOKUP} user jids"
        )));
    }
    let mut jids: Vec<String> = Vec::with_capacity(req.jids.len());
    for jid in &req.jids {
        let jid = user_jid(jid)?;
        if !jids.contains(&jid) {
            jids.push(jid);
        }
    }
    Ok(jids)
}

async fn lookup_users(
    State(state): State<AppState>,
    auth: Authed,
    Json(req): Json<LookupUsers>,
) -> ApiResult<Json<Vec<WaUser>>> {
    let jids = check_lookup(req)?;
    let (mut users, misses) = state
        .lookup_cached(&auth.instance, &jids)
        .ok_or_else(|| HttpError::new(ErrorCode::RateLimited, "too many number lookups"))?;
    if !misses.is_empty() {
        let fresh = state.driver().lookup_users(&auth.instance, misses).await?;
        state.lookup_store(&auth.instance, &fresh);
        users.extend(fresh);
    }
    // Answer in request order, each JID once.
    let mut ordered: Vec<WaUser> = Vec::with_capacity(jids.len());
    for jid in &jids {
        if ordered.iter().any(|u| &u.jid == jid) {
            continue;
        }
        if let Some(user) = users.iter().find(|u| &u.jid == jid) {
            ordered.push(user.clone());
        }
    }
    Ok(Json(ordered))
}

async fn groups(State(state): State<AppState>, auth: Authed) -> ApiResult<Json<Vec<GroupInfo>>> {
    Ok(Json(state.driver().groups(&auth.instance).await?))
}

async fn group(
    State(state): State<AppState>,
    auth: Authed,
    Path(jid): Path<String>,
) -> ApiResult<Json<GroupInfo>> {
    check_jid(&jid)?;
    Ok(Json(state.driver().group(&auth.instance, jid).await?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lookup_jids_must_be_plain_users() {
        assert_eq!(
            user_jid(" 905321234567@s.whatsapp.net ").unwrap(),
            "905321234567@s.whatsapp.net"
        );
        for bad in [
            "120363-1@g.us",
            "905321234567",
            "905321234567:3@s.whatsapp.net",
            "@s.whatsapp.net",
            "123@lid",
            "12ab@s.whatsapp.net",
            "",
        ] {
            assert!(user_jid(bad).is_err(), "{bad}");
        }
        let many = (0..=MAX_LOOKUP)
            .map(|n| format!("{n}@s.whatsapp.net"))
            .collect();
        assert!(check_lookup(LookupUsers { jids: many }).is_err());
        assert!(check_lookup(LookupUsers { jids: vec![] }).is_err());
    }

    #[test]
    fn mime_types_are_checked() {
        assert!(check_mime("image/jpeg").is_ok());
        assert!(check_mime("audio/ogg; codecs=opus").is_ok());
        assert!(check_mime("").is_err());
        assert!(check_mime("nonsense").is_err());
        assert!(check_mime("image/jp\neg").is_err());
        assert!(check_mime(&format!("a/{}", "b".repeat(200))).is_err());
    }

    #[test]
    fn hostile_mime_never_breaks_the_response() {
        let r = media_response("text/html\u{7f}\u{0}", vec![1]);
        assert_eq!(r.headers()[CONTENT_TYPE], "application/octet-stream");
        let r = media_response("image/png", vec![1]);
        assert_eq!(r.headers()[CONTENT_TYPE], "image/png");
        assert_eq!(r.headers()["x-content-type-options"], "nosniff");
    }

    #[test]
    fn labels_are_trimmed_and_bounded() {
        assert_eq!(clean_label("  Phone  ").unwrap(), "Phone");
        assert!(clean_label("   ").is_err());
        assert!(clean_label(&"x".repeat(65)).is_err());
        assert!(clean_label(&"x".repeat(64)).is_ok());
    }

    #[test]
    fn keys_are_random_and_only_the_hash_is_kept() {
        let (a, na) = fresh_key("a").unwrap();
        let (b, nb) = fresh_key("b").unwrap();
        assert_ne!(a, b);
        assert_ne!(na.key_id, nb.key_id);
        assert!(na.hash.ct_eq(&a.hash()));
        assert!(!na.hash.ct_eq(&b.hash()));
        assert_eq!(a.as_str().len(), rovsti_wa_proto::key::KEY_LEN);
    }

    #[test]
    fn links_are_validated() {
        assert!(check_link(&LinkRequest::Qr).is_ok());
        let number = |n: &str| LinkRequest::PhoneNumber { number: n.into() };
        assert!(check_link(&number("+90 532 123 45 67")).is_ok());
        assert!(check_link(&number("0532 123 45 67")).is_err());
    }

    #[test]
    fn jids_are_checked() {
        assert!(check_jid("905321234567@s.whatsapp.net").is_ok());
        let err = check_jid("").unwrap_err();
        assert_eq!(err.0.code, ErrorCode::BadRequest);
    }

    #[test]
    fn send_time_window() {
        let now = 1_000_000;
        assert!(check_send_at(now + 29_999, now).is_err());
        assert!(check_send_at(now + 30_000, now).is_ok());
        assert!(check_send_at(now + MAX_SCHEDULE_LEAD_MS, now).is_ok());
        assert!(check_send_at(now + MAX_SCHEDULE_LEAD_MS + 1, now).is_err());
        assert!(check_send_at(now - 1, now).is_err());
        assert!(check_send_at(i64::MIN, now).is_err());
        assert!(check_send_at(i64::MAX, i64::MIN).is_err());
    }

    #[test]
    fn private_events_keep_the_head_seq() {
        let e = private_event(7, GatewayEvent::Resync);
        assert_eq!(e.seq, 7);
    }
}
