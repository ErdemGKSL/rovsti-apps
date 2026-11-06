//! Blocking HTTP client for the gateway API.
//!
//! The methods block, so GPUI apps run them on the background executor.
//! Every call sends `Authorization: Bearer <key>`, except
//! [`Gateway::create_instance`], which has no key yet.

use std::time::Duration;

use rovsti_wa_proto::api::routes;
use rovsti_wa_proto::{
    CallStarted, CallSummary, ChatAction, ChatState, ChatSummary, ConnectKey, CreateInstance,
    CreatedInstance, DeleteForMe, EditMessage, GatewayUrl, GroupInfo, InstanceStatus, IssuedKey,
    KeyInfo, LinkRequest, LookupUsers, MarkRead, MediaKind, MediaOptions, MintKey, PlaceCall,
    ProfilesRequest, PushRegistration, RevokeMessage, RichChat, RichMessage, ScheduleText,
    ScheduledText, SendReaction, SendText, Sent, SetMuted, SetPresence, StarMessage,
    SubscribePresence, WaMessage, WaProfile, WaUser,
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

/// [`send_media_path`] plus reply, file name, duration and voice-note flag.
pub(crate) fn send_media_path_with(
    chat: &str,
    kind: MediaKind,
    mime: &str,
    caption: Option<&str>,
    options: &MediaOptions,
) -> String {
    let mut path = send_media_path(chat, kind, mime, caption);
    if let Some(id) = options.quoted_id.as_deref().filter(|v| !v.is_empty()) {
        path.push_str(&format!("&quoted_id={}", enc(id)));
    }
    if let Some(name) = options.file_name.as_deref().filter(|v| !v.is_empty()) {
        path.push_str(&format!("&file_name={}", enc(name)));
    }
    if let Some(seconds) = options.seconds {
        path.push_str(&format!("&seconds={seconds}"));
    }
    if options.voice_note {
        path.push_str("&voice_note=true");
    }
    path
}

pub(crate) fn picture_path(jid: &str, full: bool) -> String {
    let path = routes::fill(routes::PICTURE, "jid", jid);
    if full {
        format!("{path}?full=true")
    } else {
        path
    }
}

/// The picture id an `ETag` header names (`"123"`, `W/"123"` or `123`).
pub(crate) fn etag_id(value: &str) -> Option<String> {
    let value = value.trim();
    let value = value.strip_prefix("W/").unwrap_or(value);
    let id = value.trim_matches('"');
    (!id.is_empty()).then(|| id.to_owned())
}

/// A profile picture as [`Gateway::picture`] found it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Picture {
    /// The picture and its id. Cache the bytes under the id: the id changes
    /// whenever the picture does.
    Image { id: String, bytes: Vec<u8> },
    /// The picture is still the one whose id the caller passed.
    Unchanged,
    /// The person or group has no picture, or hides it from this account.
    None,
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

    /// Schedules a text; the gateway sends it at `req.send_at_ms`.
    pub fn schedule_text(&self, req: &ScheduleText) -> Result<ScheduledText, ClientError> {
        self.post(routes::SCHEDULED, req)
    }

    /// Pending scheduled texts plus those finished in the last 7 days.
    pub fn scheduled(&self) -> Result<Vec<ScheduledText>, ClientError> {
        self.get(routes::SCHEDULED)
    }

    /// Cancels a pending scheduled text; `NotFound` once it was sent.
    pub fn cancel_scheduled(&self, id: &str) -> Result<(), ClientError> {
        let path = routes::fill(routes::SCHEDULED_ONE, "id", id);
        self.exec(Method::DELETE, &path, true, Payload::None)
            .map(drop)
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

    /// One group with its members (named by push name where the gateway
    /// knows it) and description.
    pub fn group(&self, jid: &str) -> Result<GroupInfo, ClientError> {
        self.get(&routes::fill(routes::GROUP, "jid", jid))
    }

    /// Which of the user `jids` (`number@s.whatsapp.net`, at most
    /// `MAX_LOOKUP`) are on WhatsApp. The gateway caches answers and limits
    /// how many numbers an instance may look up.
    pub fn lookup_users(&self, jids: &[String]) -> Result<Vec<WaUser>, ClientError> {
        self.post(
            routes::CONTACTS_LOOKUP,
            &LookupUsers {
                jids: jids.to_vec(),
            },
        )
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

    /// Chats with their meta: picture id, push name, archive, pin and mute
    /// state, and the meta of the newest message.
    pub fn chats_with_meta(&self) -> Result<Vec<RichChat>, ClientError> {
        self.get(routes::CHATS)
    }

    /// Like [`Self::messages`], with each message's meta (reply, ticks,
    /// edit, delete, media details, sender push name).
    pub fn messages_with_meta(
        &self,
        chat: &str,
        before_ms: Option<i64>,
        limit: u32,
    ) -> Result<Vec<RichMessage>, ClientError> {
        self.get(&messages_path(chat, before_ms, limit))
    }

    /// Like [`Self::send_media`], as a reply or with a document name,
    /// duration or voice-note flag.
    pub fn send_media_with(
        &self,
        chat: &str,
        kind: MediaKind,
        mime: &str,
        caption: Option<&str>,
        options: &MediaOptions,
        bytes: Vec<u8>,
    ) -> Result<Sent, ClientError> {
        let path = send_media_path_with(chat, kind, mime, caption, options);
        decode(self.exec(Method::POST, &path, true, Payload::Bytes(bytes))?)
    }

    /// The small preview image that came with a photo, video, document,
    /// sticker or location (`meta.media.thumbnail` says whether there is
    /// one). JPEG, PNG or WebP bytes.
    pub fn thumbnail(&self, message_id: &str) -> Result<Vec<u8>, ClientError> {
        let path = routes::fill(routes::MESSAGE_THUMBNAIL, "message_id", message_id);
        let mut resp = self.exec(Method::GET, &path, true, Payload::None)?;
        resp.body_mut()
            .with_config()
            .limit(JSON_LIMIT)
            .read_to_vec()
            .map_err(ClientError::from)
    }

    /// Edits the text or caption of a message I sent (WhatsApp allows 15
    /// minutes). A `message_meta` event follows.
    pub fn edit_message(&self, req: &EditMessage) -> Result<(), ClientError> {
        self.post_unit(routes::EDIT, req)
    }

    /// Deletes a message for everyone. A `message_meta` event with
    /// `meta.revoked` follows.
    pub fn revoke_message(&self, req: &RevokeMessage) -> Result<(), ClientError> {
        self.post_unit(routes::REVOKE, req)
    }

    /// Deletes a message on this account's devices. A `message_deleted`
    /// event follows.
    pub fn delete_for_me(&self, req: &DeleteForMe) -> Result<(), ClientError> {
        self.post_unit(routes::DELETE_FOR_ME, req)
    }

    pub fn star_message(&self, req: &StarMessage) -> Result<(), ClientError> {
        self.post_unit(routes::STAR, req)
    }

    /// Played receipts for voice and video notes (blue microphone).
    pub fn mark_played(&self, req: &MarkRead) -> Result<(), ClientError> {
        self.post_unit(routes::MARK_PLAYED, req)
    }

    /// Archives, pins, mutes or marks a chat on every device. A `chat_meta`
    /// event follows.
    pub fn chat_action(&self, req: &ChatAction) -> Result<(), ClientError> {
        self.post_unit(routes::CHAT_ACTION, req)
    }

    /// Push name, saved name, about text, picture id and business details of
    /// up to `MAX_PROFILES` user or group JIDs, in request order. The gateway
    /// answers from its store and asks WhatsApp at most once a day per JID.
    pub fn profiles(&self, jids: &[String]) -> Result<Vec<WaProfile>, ClientError> {
        self.post(
            routes::PROFILES,
            &ProfilesRequest {
                jids: jids.to_vec(),
                refresh: false,
            },
        )
    }

    /// Like [`Self::profiles`], asking WhatsApp even for fresh entries.
    pub fn refresh_profiles(&self, jids: &[String]) -> Result<Vec<WaProfile>, ClientError> {
        self.post(
            routes::PROFILES,
            &ProfilesRequest {
                jids: jids.to_vec(),
                refresh: true,
            },
        )
    }

    /// The profile picture of a user or group: the small preview (about
    /// 96 px) or, with `full`, the large one. Pass the id of the picture you
    /// hold as `known_id` to get [`Picture::Unchanged`] instead of the bytes.
    pub fn picture(
        &self,
        jid: &str,
        full: bool,
        known_id: Option<&str>,
    ) -> Result<Picture, ClientError> {
        let key = self.key.as_ref().ok_or(ClientError::Unauthorized)?;
        let mut req = request(
            Method::GET,
            &self.url,
            &picture_path(jid, full),
            Some(key),
            (),
        )?;
        if let Some(id) = known_id.filter(|id| !id.is_empty()) {
            let tag = HeaderValue::from_str(&rovsti_wa_proto::meta::picture_etag(id))
                .map_err(|e| ClientError::Http(e.to_string()))?;
            req.headers_mut().insert(header::IF_NONE_MATCH, tag);
        }
        let mut resp = self.agent.run(req)?;
        let status = resp.status().as_u16();
        match status {
            304 => return Ok(Picture::Unchanged),
            404 => return Ok(Picture::None),
            200..=299 => {}
            _ => {
                let body = resp
                    .body_mut()
                    .with_config()
                    .limit(ERROR_BODY_LIMIT)
                    .read_to_vec()
                    .unwrap_or_default();
                return Err(from_status(status, &body));
            }
        }
        let id = resp
            .headers()
            .get(header::ETAG)
            .and_then(|v| v.to_str().ok())
            .and_then(etag_id)
            .ok_or_else(|| ClientError::Decode("picture without an ETag".to_owned()))?;
        let bytes = resp
            .body_mut()
            .with_config()
            .limit(JSON_LIMIT)
            .read_to_vec()?;
        Ok(Picture::Image { id, bytes })
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
    fn scheduled_paths_and_decoding() {
        let path = routes::fill(routes::SCHEDULED_ONE, "id", "ab/12");
        let req = request(Method::DELETE, &url(), &path, None, ()).unwrap();
        assert_eq!(
            req.uri().to_string(),
            "https://wa.example.com/v1/scheduled/ab%2F12"
        );
        let body = r#"[{"id":"a","chat":"c","text":"t","send_at_ms":9,"created_ms":1,
            "state":{"status":"sent","message_id":"M"}}]"#;
        let list: Vec<ScheduledText> = decode(Response::new(Body::builder().data(body))).unwrap();
        assert_eq!(
            list[0].state,
            rovsti_wa_proto::ScheduledState::Sent {
                message_id: "M".into()
            }
        );
    }

    #[test]
    fn media_options_and_picture_paths() {
        let options = MediaOptions {
            quoted_id: Some("Q 1".into()),
            file_name: Some("rapor 1.pdf".into()),
            seconds: Some(9),
            voice_note: true,
        };
        let path = send_media_path_with("c", MediaKind::Document, "a/b", None, &options);
        assert!(
            path.ends_with("&quoted_id=Q%201&file_name=rapor%201.pdf&seconds=9&voice_note=true"),
            "{path}"
        );
        let plain =
            send_media_path_with("c", MediaKind::Image, "a/b", None, &MediaOptions::default());
        assert_eq!(plain, send_media_path("c", MediaKind::Image, "a/b", None));
        assert_eq!(
            picture_path("9053@s.whatsapp.net", false),
            "/v1/pictures/9053%40s.whatsapp.net"
        );
        assert_eq!(
            picture_path("1-2@g.us", true),
            "/v1/pictures/1-2%40g.us?full=true"
        );
        assert_eq!(etag_id("\"123\"").as_deref(), Some("123"));
        assert_eq!(etag_id("W/\"123\"").as_deref(), Some("123"));
        assert_eq!(etag_id(" 77 ").as_deref(), Some("77"));
        assert_eq!(etag_id("\"\""), None);
    }

    #[test]
    fn rich_lists_decode_old_and_new_gateways() {
        let old = r#"[{"id":"m","chat":"c","sender":null,"from_me":true,"timestamp_ms":1,
            "body":{"text":"hi"},"reactions":[]}]"#;
        let list: Vec<RichMessage> = decode(Response::new(Body::builder().data(old))).unwrap();
        assert!(list[0].meta.is_empty());
        let new = r#"[{"id":"m","chat":"c","sender":null,"from_me":true,"timestamp_ms":1,
            "body":{"text":"hi"},"reactions":[],"meta":{"status":"read","edited_ms":4}}]"#;
        let list: Vec<RichMessage> = decode(Response::new(Body::builder().data(new))).unwrap();
        assert_eq!(list[0].meta.edited_ms, Some(4));
        // The plain method reads the same reply.
        let plain: Vec<WaMessage> = decode(Response::new(Body::builder().data(new))).unwrap();
        assert_eq!(plain[0].id, "m");
    }

    #[test]
    fn calls_without_a_key_are_unauthorized() {
        let gw = Gateway::new(url(), None);
        assert_eq!(gw.status().unwrap_err(), ClientError::Unauthorized);
    }
}
