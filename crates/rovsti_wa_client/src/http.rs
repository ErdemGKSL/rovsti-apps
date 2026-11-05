//! Blocking HTTP client for the gateway API.
//!
//! The methods block, so GPUI apps run them on the background executor.
//! Every call sends `Authorization: Bearer <key>`, except
//! [`Gateway::create_instance`], which has no key yet.

use std::time::Duration;

use rovsti_wa_proto::api::routes;
use rovsti_wa_proto::{
    CallStarted, CallSummary, ChatState, ChatSummary, ConnectKey, CreateInstance, CreatedInstance,
    GatewayUrl, GroupInfo, InstanceStatus, IssuedKey, KeyInfo, LinkRequest, MarkRead, MediaKind,
    MintKey, PlaceCall, PushRegistration, SendReaction, SendText, Sent, SetMuted, SetPresence,
    SubscribePresence, WaMessage,
};
use serde::Serialize;
use serde::de::DeserializeOwned;
use ureq::config::Config;
use ureq::http::{self, HeaderValue, Method, Request, Response, header};
use ureq::tls::{RootCerts, TlsConfig, TlsProvider};
use ureq::{Agent, AsSendBody, Body};

use crate::error::{ClientError, from_status};
use crate::tls;

/// Responses are small JSON documents, except media.
const JSON_LIMIT: u64 = 8 * 1024 * 1024;
const MEDIA_LIMIT: u64 = 128 * 1024 * 1024;
const ERROR_BODY_LIMIT: u64 = 64 * 1024;

/// A configured gateway: base URL, connect key and a shared HTTP agent.
#[derive(Clone, Debug)]
pub struct Gateway {
    url: GatewayUrl,
    key: Option<ConnectKey>,
    agent: Agent,
}

/// The `Authorization` header value for a key. Marked sensitive so it is
/// never printed by `Debug`.
pub(crate) fn bearer(key: &ConnectKey) -> Result<HeaderValue, ClientError> {
    let mut value = HeaderValue::from_str(&format!("Bearer {}", key.as_str()))
        .map_err(|e| ClientError::Http(e.to_string()))?;
    value.set_sensitive(true);
    Ok(value)
}

/// Builds a request for `path` (with any query) on the gateway. Pure, so it
/// is unit-tested without a network.
pub(crate) fn request<B>(
    method: Method,
    url: &GatewayUrl,
    path: &str,
    key: Option<&ConnectKey>,
    body: B,
) -> Result<Request<B>, ClientError> {
    let mut builder = Request::builder().method(method).uri(url.join(path));
    if let Some(key) = key {
        builder = builder.header(header::AUTHORIZATION, bearer(key)?);
    }
    builder
        .body(body)
        .map_err(|e: http::Error| ClientError::Http(e.to_string()))
}

/// Percent-encodes one query value or path segment.
fn enc(value: &str) -> String {
    routes::fill("{v}", "v", value)
}

fn kind_name(kind: MediaKind) -> &'static str {
    match kind {
        MediaKind::Image => "image",
        MediaKind::Video => "video",
        MediaKind::Audio => "audio",
        MediaKind::Document => "document",
        MediaKind::Sticker => "sticker",
    }
}

pub(crate) fn messages_path(chat: &str, before_ms: Option<i64>, limit: u32) -> String {
    let mut path = format!(
        "{}?limit={limit}",
        routes::fill(routes::CHAT_MESSAGES, "jid", chat)
    );
    if let Some(before) = before_ms {
        path.push_str(&format!("&before={before}"));
    }
    path
}

pub(crate) fn send_media_path(
    chat: &str,
    kind: MediaKind,
    mime: &str,
    caption: Option<&str>,
) -> String {
    let mut path = format!(
        "{}?chat={}&kind={}&mime={}",
        routes::SEND_MEDIA,
        enc(chat),
        kind_name(kind),
        enc(mime)
    );
    if let Some(caption) = caption.filter(|c| !c.is_empty()) {
        path.push_str(&format!("&caption={}", enc(caption)));
    }
    path
}

enum Payload {
    None,
    Json(Vec<u8>),
    Bytes(Vec<u8>),
}

impl Payload {
    fn json<T: Serialize>(value: &T) -> Result<Self, ClientError> {
        serde_json::to_vec(value)
            .map(Self::Json)
            .map_err(|e| ClientError::Decode(e.to_string()))
    }
}

impl Gateway {
    pub fn new(url: GatewayUrl, key: Option<ConnectKey>) -> Self {
        Self {
            url,
            key,
            agent: build_agent(),
        }
    }

    /// The same gateway with another key (shares the connection pool).
    pub fn with_key(&self, key: ConnectKey) -> Self {
        Self {
            url: self.url.clone(),
            key: Some(key),
            agent: self.agent.clone(),
        }
    }

    pub fn url(&self) -> &GatewayUrl {
        &self.url
    }

    fn run<B: AsSendBody>(&self, req: Request<B>) -> Result<Response<Body>, ClientError> {
        let mut resp = self.agent.run(req)?;
        let status = resp.status();
        if status.is_success() {
            return Ok(resp);
        }
        let body = resp
            .body_mut()
            .with_config()
            .limit(ERROR_BODY_LIMIT)
            .read_to_vec()
            .unwrap_or_default();
        Err(from_status(status.as_u16(), &body))
    }

    fn exec(
        &self,
        method: Method,
        path: &str,
        authed: bool,
        payload: Payload,
    ) -> Result<Response<Body>, ClientError> {
        let key = if authed {
            Some(self.key.as_ref().ok_or(ClientError::Unauthorized)?)
        } else {
            None
        };
        match payload {
            Payload::None => self.run(request(method, &self.url, path, key, ())?),
            Payload::Json(bytes) => {
                let mut req = request(method, &self.url, path, key, bytes)?;
                req.headers_mut().insert(
                    header::CONTENT_TYPE,
                    HeaderValue::from_static("application/json"),
                );
                self.run(req)
            }
            Payload::Bytes(bytes) => {
                let mut req = request(method, &self.url, path, key, bytes)?;
                req.headers_mut().insert(
                    header::CONTENT_TYPE,
                    HeaderValue::from_static("application/octet-stream"),
                );
                self.run(req)
            }
        }
    }

    fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T, ClientError> {
        decode(self.exec(Method::GET, path, true, Payload::None)?)
    }

    fn post<T: DeserializeOwned>(
        &self,
        path: &str,
        body: &impl Serialize,
    ) -> Result<T, ClientError> {
        decode(self.exec(Method::POST, path, true, Payload::json(body)?)?)
    }

    fn post_unit(&self, path: &str, body: &impl Serialize) -> Result<(), ClientError> {
        self.exec(Method::POST, path, true, Payload::json(body)?)
            .map(drop)
    }

    /// Creates a new instance and starts linking. Needs no key; the response
    /// carries the first connect key.
    pub fn create_instance(&self, req: &CreateInstance) -> Result<CreatedInstance, ClientError> {
        decode(self.exec(Method::POST, routes::INSTANCES, false, Payload::json(req)?)?)
    }

    pub fn status(&self) -> Result<InstanceStatus, ClientError> {
        self.get(routes::INSTANCE)
    }

    /// Starts linking again after the account logged out.
    pub fn relink(&self, link: &LinkRequest) -> Result<(), ClientError> {
        self.post_unit(routes::INSTANCE_LINK, link)
    }

    pub fn delete_instance(&self) -> Result<(), ClientError> {
        self.exec(Method::DELETE, routes::INSTANCE, true, Payload::None)
            .map(drop)
    }

    /// Mints a new connect key; the plain key is only visible in this reply.
    pub fn mint_key(&self, label: &str) -> Result<IssuedKey, ClientError> {
        self.post(
            routes::KEYS,
            &MintKey {
                label: label.to_owned(),
            },
        )
    }

    pub fn keys(&self) -> Result<Vec<KeyInfo>, ClientError> {
        self.get(routes::KEYS)
    }

    pub fn revoke_key(&self, key_id: &str) -> Result<(), ClientError> {
        let path = routes::fill(routes::KEY, "key_id", key_id);
        self.exec(Method::DELETE, &path, true, Payload::None)
            .map(drop)
    }

    pub fn register_push(&self, reg: &PushRegistration) -> Result<(), ClientError> {
        self.exec(Method::PUT, routes::PUSH, true, Payload::json(reg)?)
            .map(drop)
    }

    pub fn chats(&self) -> Result<Vec<ChatSummary>, ClientError> {
        self.get(routes::CHATS)
    }

    /// Messages of a chat, newest first as the gateway returns them, older
    /// than `before_ms` when given.
    pub fn messages(
        &self,
        chat: &str,
        before_ms: Option<i64>,
        limit: u32,
    ) -> Result<Vec<WaMessage>, ClientError> {
        self.get(&messages_path(chat, before_ms, limit))
    }

    pub fn send_text(&self, req: &SendText) -> Result<Sent, ClientError> {
        self.post(routes::SEND_TEXT, req)
    }

    pub fn send_media(
        &self,
        chat: &str,
        kind: MediaKind,
        mime: &str,
        caption: Option<&str>,
        bytes: Vec<u8>,
    ) -> Result<Sent, ClientError> {
        let path = send_media_path(chat, kind, mime, caption);
        decode(self.exec(Method::POST, &path, true, Payload::Bytes(bytes))?)
    }

    /// Downloads and decrypts the media of a message.
    pub fn media(&self, message_id: &str) -> Result<Vec<u8>, ClientError> {
        let path = routes::fill(routes::MESSAGE_MEDIA, "message_id", message_id);
        let mut resp = self.exec(Method::GET, &path, true, Payload::None)?;
        resp.body_mut()
            .with_config()
            .limit(MEDIA_LIMIT)
            .read_to_vec()
            .map_err(ClientError::from)
    }

    pub fn react(&self, req: &SendReaction) -> Result<(), ClientError> {
        self.post_unit(routes::REACT, req)
    }

    pub fn mark_read(&self, req: &MarkRead) -> Result<(), ClientError> {
        self.post_unit(routes::MARK_READ, req)
    }

    pub fn set_presence(&self, available: bool) -> Result<(), ClientError> {
        self.post_unit(routes::PRESENCE, &SetPresence { available })
    }

    /// Starts receiving presence updates for a contact; call it the first time
    /// a chat is opened.
    pub fn subscribe_presence(&self, jid: &str) -> Result<(), ClientError> {
        self.post_unit(
            routes::PRESENCE_SUBSCRIBE,
            &SubscribePresence {
                jid: jid.to_owned(),
            },
        )
    }

    pub fn chat_state(&self, req: &ChatState) -> Result<(), ClientError> {
        self.post_unit(routes::CHAT_STATE, req)
    }

    pub fn groups(&self) -> Result<Vec<GroupInfo>, ClientError> {
        self.get(routes::GROUPS)
    }

    pub fn place_call(&self, to: &str) -> Result<CallStarted, ClientError> {
        self.post(routes::CALLS, &PlaceCall { to: to.to_owned() })
    }

    /// The instance's recent calls, newest first.
    pub fn calls(&self) -> Result<Vec<CallSummary>, ClientError> {
        self.get(routes::CALLS)
    }

    pub fn accept_call(&self, call_id: &str) -> Result<(), ClientError> {
        self.call_action(routes::CALL_ACCEPT, call_id)
    }

    pub fn reject_call(&self, call_id: &str) -> Result<(), ClientError> {
        self.call_action(routes::CALL_REJECT, call_id)
    }

    pub fn end_call(&self, call_id: &str) -> Result<(), ClientError> {
        self.call_action(routes::CALL_END, call_id)
    }

    pub fn set_muted(&self, call_id: &str, muted: bool) -> Result<(), ClientError> {
        let path = routes::fill(routes::CALL_MUTE, "call_id", call_id);
        self.post_unit(&path, &SetMuted { muted })
    }

    fn call_action(&self, template: &str, call_id: &str) -> Result<(), ClientError> {
        let path = routes::fill(template, "call_id", call_id);
        self.exec(Method::POST, &path, true, Payload::None)
            .map(drop)
    }
}

fn decode<T: DeserializeOwned>(mut resp: Response<Body>) -> Result<T, ClientError> {
    let bytes = resp
        .body_mut()
        .with_config()
        .limit(JSON_LIMIT)
        .read_to_vec()?;
    serde_json::from_slice(&bytes).map_err(|e| ClientError::Decode(e.to_string()))
}

fn build_agent() -> Agent {
    let tls = TlsConfig::builder()
        .provider(TlsProvider::Rustls)
        .unversioned_rustls_crypto_provider(tls::provider())
        .root_certs(RootCerts::WebPki)
        .build();
    let config = Config::builder()
        .tls_config(tls)
        .http_status_as_error(false)
        .timeout_connect(Some(Duration::from_secs(10)))
        .timeout_global(Some(Duration::from_secs(120)))
        .build();
    Agent::new_with_config(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> ConnectKey {
        ConnectKey::from_bytes(&[7; 32])
    }

    fn url() -> GatewayUrl {
        GatewayUrl::parse("https://wa.example.com").unwrap()
    }

    #[test]
    fn request_has_route_and_bearer() {
        let k = key();
        let req = request(Method::GET, &url(), routes::INSTANCE, Some(&k), ()).unwrap();
        assert_eq!(req.method(), Method::GET);
        assert_eq!(req.uri().to_string(), "https://wa.example.com/v1/instance");
        let auth = req.headers().get(header::AUTHORIZATION).unwrap();
        assert_eq!(auth.to_str().unwrap(), format!("Bearer {}", k.as_str()));
        assert!(auth.is_sensitive());
    }

    #[test]
    fn request_without_key_has_no_auth_header() {
        let req = request(Method::POST, &url(), routes::INSTANCES, None, ()).unwrap();
        assert!(req.headers().get(header::AUTHORIZATION).is_none());
        assert_eq!(req.method(), Method::POST);
    }

    #[test]
    fn request_keeps_query_and_local_http() {
        let local = GatewayUrl::parse("http://10.0.2.2:8787").unwrap();
        let req = request(Method::GET, &local, "/v1/chats?x=1", None, ()).unwrap();
        assert_eq!(req.uri().to_string(), "http://10.0.2.2:8787/v1/chats?x=1");
    }

    #[test]
    fn messages_path_encodes_chat() {
        assert_eq!(
            messages_path("9053@s.whatsapp.net", None, 50),
            "/v1/chats/9053%40s.whatsapp.net/messages?limit=50"
        );
        assert_eq!(
            messages_path("g@g.us", Some(1_700_000_000_000), 20),
            "/v1/chats/g%40g.us/messages?limit=20&before=1700000000000"
        );
    }

    #[test]
    fn send_media_path_encodes_query() {
        assert_eq!(
            send_media_path("9053@s.whatsapp.net", MediaKind::Image, "image/jpeg", None),
            "/v1/messages/media?chat=9053%40s.whatsapp.net&kind=image&mime=image%2Fjpeg"
        );
        let with_caption = send_media_path("c", MediaKind::Document, "a/b", Some("a b&c"));
        assert!(with_caption.ends_with("&caption=a%20b%26c"));
        let empty = send_media_path("c", MediaKind::Audio, "a/b", Some(""));
        assert!(!empty.contains("caption"));
    }

    #[test]
    fn kind_names_match_the_wire_format() {
        for kind in [
            MediaKind::Image,
            MediaKind::Video,
            MediaKind::Audio,
            MediaKind::Document,
            MediaKind::Sticker,
        ] {
            let wire = serde_json::to_string(&kind).unwrap();
            assert_eq!(wire, format!("\"{}\"", kind_name(kind)));
        }
    }

    #[test]
    fn call_list_decodes_and_uses_the_calls_route() {
        let req = request(Method::GET, &url(), routes::CALLS, None, ()).unwrap();
        assert_eq!(req.uri().to_string(), "https://wa.example.com/v1/calls");
        let body = r#"[{"call_id":"C1","peer":"9053@s.whatsapp.net","peer_name":null,
            "direction":"in","phase":"ringing","is_video":false,"started_ms":5}]"#;
        let resp = Response::new(Body::builder().data(body));
        let calls: Vec<CallSummary> = decode(resp).unwrap();
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].update.call_id, "C1");
        assert_eq!((calls[0].started_ms, calls[0].ended_ms), (5, None));
    }

    #[test]
    fn calls_without_a_key_are_unauthorized() {
        let gw = Gateway::new(url(), None);
        assert_eq!(gw.status().unwrap_err(), ClientError::Unauthorized);
    }
}
