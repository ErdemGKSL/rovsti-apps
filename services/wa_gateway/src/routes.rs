//! The HTTP API (route constants come from `rovsti_wa_proto::api::routes`).

use std::time::Duration;

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::Response;
use axum::routing::{delete, get, post, put};
use axum::{Json, Router};
use rovsti_wa_proto::api::routes as r;
use rovsti_wa_proto::{
    ChatSummary, CreateInstance, CreatedInstance, ErrorCode, EventEnvelope, GatewayEvent,
    InstanceState, InstanceStatus, IssuedKey, KeyId, KeyInfo, LinkRequest, MintKey,
    PushRegistration, SendText, Sent, WaMessage,
};
use serde::Deserialize;
use tokio::sync::broadcast::error::RecvError;

use crate::auth::Authed;
use crate::error::HttpError;
use crate::hub::Replay;
use crate::now_ms;
use crate::store::{InstanceId, NewKey, parse_key_id};
use crate::{AppState, store::StoreError};

const MAX_LABEL_CHARS: usize = 64;
const MAX_PUSH_TOKEN_BYTES: usize = 4096;
const MAX_TEXT_BYTES: usize = 64 * 1024;
const DEFAULT_PAGE: u32 = 50;
const MAX_PAGE: u32 = 200;
const SERVER_PING: Duration = Duration::from_secs(30);

pub fn router(state: AppState) -> Router {
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
    Ok(StatusCode::NO_CONTENT)
}

async fn list_keys(State(state): State<AppState>, auth: Authed) -> ApiResult<Json<Vec<KeyInfo>>> {
    Ok(Json(state.store().list_keys(auth.instance).await?))
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

#[cfg(test)]
mod tests {
    use super::*;

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
    fn private_events_keep_the_head_seq() {
        let e = private_event(7, GatewayEvent::Resync);
        assert_eq!(e.seq, 7);
    }
}
