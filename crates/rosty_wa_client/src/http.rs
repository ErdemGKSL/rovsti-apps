//! Blocking HTTP client for the gateway API.
//!
//! The methods block, so GPUI apps run them on the background executor.
//! Every call sends `Authorization: Bearer <key>`, except
//! [`Gateway::create_instance`], which has no key yet.

use std::time::Duration;

use rosty_wa_proto::api::routes;
use rosty_wa_proto::{
    ApiError, CallStarted, CallSummary, ChatAction, ChatState, ChatSummary, ConnectKey,
    CreateInstance, CreatedInstance, DeleteForMe, EditMessage, ErrorCode, ForwardMessage,
    GatewayUrl, GroupInfo, IdentityMappings, InstanceStatus, IssuedKey, KeyInfo, LinkRequest,
    LookupUsers, MarkRead, MarkStatusViewed, MediaKind, MediaOptionError, MediaOptions,
    MessageReceipts, MintKey, PeopleJobStatus, PeopleRecord, PeopleSyncStart, PlaceCall,
    ProfilesRequest, PushKeyedRegistration, PushRegistration, RevokeMessage, RichChat, RichMessage,
    ScheduleText, ScheduledText, SendReaction, SendText, Sent, SetMuted, SetPresence, StarMessage,
    StatusList, SubscribePresence, WaMessage, WaProfile, WaUser,
};
use serde::Serialize;
use serde::de::DeserializeOwned;
use ureq::config::Config;
use ureq::http::{self, HeaderValue, Method, Request, Response, header};
use ureq::tls::{RootCerts, TlsConfig, TlsProvider};
use ureq::{Agent, AsSendBody, Body};

use crate::error::{ClientError, from_status, from_status_optional};
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

/// `GET /v1/messages/{id}/receipts?chat=`.
pub(crate) fn receipts_path(chat: &str, message_id: &str) -> String {
    format!(
        "{}?chat={}",
        routes::fill(routes::MESSAGE_RECEIPTS, "message_id", message_id),
        enc(chat)
    )
}

pub(crate) fn send_media_path(
    chat: &str,
    kind: MediaKind,
    mime: &str,
    caption: Option<&str>,
) -> String {
    media_path(routes::SEND_MEDIA, chat, kind, mime, caption)
}

fn media_path(
    route: &str,
    chat: &str,
    kind: MediaKind,
    mime: &str,
    caption: Option<&str>,
) -> String {
    let mut path = format!(
        "{route}?chat={}&kind={}&mime={}",
        enc(chat),
        kind_name(kind),
        enc(mime)
    );
    if let Some(caption) = caption.filter(|c| !c.is_empty()) {
        path.push_str(&format!("&caption={}", enc(caption)));
    }
    path
}

/// [`send_media_path`] plus reply, file name, duration and voice-note flag,
/// and on the route for them the options of the media round.
pub(crate) fn send_media_path_with(
    chat: &str,
    kind: MediaKind,
    mime: &str,
    caption: Option<&str>,
    options: &MediaOptions,
) -> String {
    let route = if options.has_newer() {
        routes::SEND_MEDIA_OPTIONS
    } else {
        routes::SEND_MEDIA
    };
    let mut path = media_path(route, chat, kind, mime, caption);
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
    if let Some(width) = options.width {
        path.push_str(&format!("&width={width}"));
    }
    if let Some(height) = options.height {
        path.push_str(&format!("&height={height}"));
    }
    if let Some(waveform) = options.waveform_b64.as_deref().filter(|v| !v.is_empty()) {
        path.push_str(&format!("&waveform_b64={}", enc(waveform)));
    }
    if options.animated {
        path.push_str("&animated=true");
    }
    if options.view_once {
        path.push_str("&view_once=true");
    }
    path
}

/// A media send as it goes on the wire.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct MediaRequest {
    /// The route with its query.
    pub path: String,
    /// The preview image, when there is one, then the file.
    pub body: Vec<u8>,
    /// Bytes of preview in front of the file.
    pub thumbnail_len: usize,
    /// The send carries an option of the media round, so it asks
    /// [`routes::SEND_MEDIA_OPTIONS`].
    pub newer: bool,
    /// What to ask a gateway that has no such route: the plain route with
    /// the options it knows, and the file alone as the body. `None` for a
    /// plain send, and for view once, which must not go out as an ordinary
    /// message.
    pub fallback: Option<String>,
}

impl MediaRequest {
    /// The file without the preview in front of it.
    pub fn file(&self) -> &[u8] {
        self.body.get(self.thumbnail_len..).unwrap_or(&[])
    }
}

/// The path and body of a media send. The preview image does not fit a URL
/// (a proxy in front of the gateway cuts those at a few kilobytes), so it
/// travels in the body: `thumbnail_len` bytes of JPEG, then the file.
/// Options the gateway would refuse are refused here, before the upload.
pub(crate) fn media_request(
    chat: &str,
    kind: MediaKind,
    mime: &str,
    caption: Option<&str>,
    options: &MediaOptions,
    bytes: Vec<u8>,
) -> Result<MediaRequest, MediaOptionError> {
    options.check(kind)?;
    let newer = options.has_newer();
    let mut path = send_media_path_with(chat, kind, mime, caption, options);
    let (body, thumbnail_len) = match options.thumbnail()? {
        Some(mut body) => {
            let len = body.len();
            path.push_str(&format!("&thumbnail_len={len}"));
            body.extend_from_slice(&bytes);
            (body, len)
        }
        None => (bytes, 0),
    };
    let fallback = (newer && !options.view_once)
        .then(|| send_media_path_with(chat, kind, mime, caption, &options.without_newer()));
    Ok(MediaRequest {
        path,
        body,
        thumbnail_len,
        newer,
        fallback,
    })
}

pub(crate) fn picture_path(jid: &str, full: bool) -> String {
    let path = routes::fill(routes::PICTURE, "jid", jid);
    if full {
        format!("{path}?full=true")
    } else {
        path
    }
}

/// The path of one page of identity mappings: every pair, or those after
/// the cursor `since`.
pub(crate) fn mappings_path(since: Option<&str>) -> String {
    match since.filter(|cursor| !cursor.is_empty()) {
        Some(cursor) => format!("{}?since={}", routes::IDENTITY_MAPPINGS, enc(cursor)),
        None => routes::IDENTITY_MAPPINGS.to_owned(),
    }
}

/// The status path of a people sync job; with `rev` (and `wait_ms`) the gateway answers when the job changed past
/// that revision, or when `wait_ms` is over.
pub(crate) fn people_status_path(job_id: &str, rev: Option<u64>, wait_ms: Option<u64>) -> String {
    let mut path = routes::fill(routes::PEOPLE_SYNC_JOB, "job_id", job_id);
    let mut sep = '?';
    if let Some(rev) = rev {
        path.push_str(&format!("{sep}rev={rev}"));
        sep = '&';
    }
    if let Some(wait) = wait_ms {
        path.push_str(&format!("{sep}wait_ms={wait}"));
    }
    path
}

/// The path of one bundle part: the records after `after`, about `max_bytes` (the gateway's default when `None`).
pub(crate) fn people_bundle_path(job_id: &str, after: u32, max_bytes: Option<u64>) -> String {
    let mut path = format!(
        "{}?after={after}",
        routes::fill(routes::PEOPLE_SYNC_BUNDLE, "job_id", job_id)
    );
    if let Some(max) = max_bytes {
        path.push_str(&format!("&max_bytes={max}"));
    }
    path
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

/// What the gateway said to a request that reached it.
enum Answer {
    /// A 2xx response.
    Ok(Response<Body>),
    /// Any other status, with the start of its body.
    Refused { status: u16, body: Vec<u8> },
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

    /// Runs `req`; a non-2xx answer comes back as [`Answer::Refused`].
    fn run_raw<B: AsSendBody>(&self, req: Request<B>) -> Result<Answer, ClientError> {
        let mut resp = self.agent.run(req)?;
        let status = resp.status();
        if status.is_success() {
            return Ok(Answer::Ok(resp));
        }
        let body = resp
            .body_mut()
            .with_config()
            .limit(ERROR_BODY_LIMIT)
            .read_to_vec()
            .unwrap_or_default();
        Ok(Answer::Refused {
            status: status.as_u16(),
            body,
        })
    }

    fn run<B: AsSendBody>(&self, req: Request<B>) -> Result<Response<Body>, ClientError> {
        match self.run_raw(req)? {
            Answer::Ok(resp) => Ok(resp),
            Answer::Refused { status, body } => Err(from_status(status, &body)),
        }
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

    /// [`Self::register_push`] with the app's push key (round 7b): one request
    /// all the same. A gateway before the sealed push ignores the key.
    pub fn register_push_keyed(&self, reg: &PushKeyedRegistration) -> Result<(), ClientError> {
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

    /// Sends a text, with mentions and a link preview when set. A preview
    /// picture the gateway would refuse comes back as a `bad_request` API
    /// error without a request.
    pub fn send_text(&self, req: &SendText) -> Result<Sent, ClientError> {
        if let Some(preview) = &req.link_preview {
            preview.thumbnail().map_err(|e| {
                ClientError::Api(ApiError::new(ErrorCode::BadRequest, e.to_string()))
            })?;
        }
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
    /// duration, voice-note flag, preview image, pixel size, waveform, GIF
    /// flag or view once. An option the gateway would refuse comes back as a
    /// `bad_request` API error without a request.
    ///
    /// A gateway from before the media round has no route for the newer
    /// options: the file is then sent the old way, without them (as every
    /// send was before). Not a view-once one: that comes back as a
    /// `not_found` API error, never as an ordinary message.
    pub fn send_media_with(
        &self,
        chat: &str,
        kind: MediaKind,
        mime: &str,
        caption: Option<&str>,
        options: &MediaOptions,
        bytes: Vec<u8>,
    ) -> Result<Sent, ClientError> {
        let req = media_request(chat, kind, mime, caption, options, bytes)
            .map_err(|e| ClientError::Api(ApiError::new(ErrorCode::BadRequest, e.to_string())))?;
        let key = self.key.as_ref().ok_or(ClientError::Unauthorized)?;
        let send = |path: &str, body: &[u8]| -> Result<Answer, ClientError> {
            let mut request = request(Method::POST, &self.url, path, Some(key), body)?;
            request.headers_mut().insert(
                header::CONTENT_TYPE,
                HeaderValue::from_static("application/octet-stream"),
            );
            self.run_raw(request)
        };
        let (status, body) = match send(&req.path, &req.body)? {
            Answer::Ok(resp) => return decode(resp),
            Answer::Refused { status, body } => (status, body),
        };
        // The router's own 404 has no API body: a gateway without the route.
        let no_route =
            req.newer && status == 404 && serde_json::from_slice::<ApiError>(&body).is_err();
        if !no_route {
            return Err(from_status(status, &body));
        }
        let Some(plain) = &req.fallback else {
            return Err(ClientError::Api(ApiError::new(
                ErrorCode::NotFound,
                "this gateway cannot send view once media",
            )));
        };
        match send(plain, req.file())? {
            Answer::Ok(resp) => decode(resp),
            Answer::Refused { status, body } => Err(from_status(status, &body)),
        }
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

    /// The current status updates (stories): mine, and everyone else's
    /// grouped by author, unseen first. Expired ones are left out.
    pub fn statuses(&self) -> Result<StatusList, ClientError> {
        self.get(routes::STATUSES)
    }

    /// The photo or video of a status update.
    pub fn status_media(&self, id: &str) -> Result<Vec<u8>, ClientError> {
        let path = routes::fill(routes::STATUS_MEDIA, "id", id);
        let mut resp = self.exec(Method::GET, &path, true, Payload::None)?;
        resp.body_mut()
            .with_config()
            .limit(MEDIA_LIMIT)
            .read_to_vec()
            .map_err(ClientError::from)
    }

    /// The small preview image of a photo or video status update.
    pub fn status_thumbnail(&self, id: &str) -> Result<Vec<u8>, ClientError> {
        let path = routes::fill(routes::STATUS_THUMBNAIL, "id", id);
        let mut resp = self.exec(Method::GET, &path, true, Payload::None)?;
        resp.body_mut()
            .with_config()
            .limit(JSON_LIMIT)
            .read_to_vec()
            .map_err(ClientError::from)
    }

    /// Marks status updates seen; their authors get a read receipt. A
    /// `status_changed` event follows.
    pub fn mark_status_viewed(&self, ids: &[String]) -> Result<(), ClientError> {
        self.post_unit(
            routes::STATUS_VIEWED,
            &MarkStatusViewed { ids: ids.to_vec() },
        )
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

    /// Forwards a stored message to another chat, marked forwarded (media is
    /// relayed, not uploaded again). The copy arrives as a `message` event in
    /// `req.to`. A gateway before E6-WA R4 answers `not_found`.
    pub fn forward(&self, req: &ForwardMessage) -> Result<Sent, ClientError> {
        self.post(routes::FORWARD, req)
    }

    /// Who got, read or played one of my messages, and when. A gateway before
    /// E6-WA R4 answers `not_found`.
    pub fn message_receipts(
        &self,
        chat: &str,
        message_id: &str,
    ) -> Result<MessageReceipts, ClientError> {
        self.get(&receipts_path(chat, message_id))
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
            let tag = HeaderValue::from_str(&rosty_wa_proto::meta::picture_etag(id))
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

    /// One page of the LID and phone-number pairs the gateway learned:
    /// every pair when `since` is `None`, else those learned or changed after
    /// that cursor. Page on with [`IdentityMappings::next`] until it is
    /// `None`, and keep the last cursor for the next catch-up.
    ///
    /// A gateway from before this route answers 404: that comes back as an
    /// API error with [`rosty_wa_proto::ErrorCode::NotFound`]
    /// ([`ClientError::is_not_found`]), which here means "not supported",
    /// not "try again".
    pub fn identity_mappings(&self, since: Option<&str>) -> Result<IdentityMappings, ClientError> {
        let key = self.key.as_ref().ok_or(ClientError::Unauthorized)?;
        let req = request(Method::GET, &self.url, &mappings_path(since), Some(key), ())?;
        match self.run_raw(req)? {
            Answer::Ok(resp) => decode(resp),
            Answer::Refused { status, body } => Err(from_status_optional(
                status,
                &body,
                "this gateway does not list identity mappings",
            )),
        }
    }

    /// People sync v3: starts the instance's people job, or joins the running one with the same request.
    ///
    /// A gateway from before the job routes answers the router's bare 404: that comes back as an API error with
    /// [`rosty_wa_proto::ErrorCode::NotFound`] ([`ClientError::is_not_found`]), which here means "use the
    /// per-contact routes", not "try again".
    pub fn people_sync_start(&self, req: &PeopleSyncStart) -> Result<PeopleJobStatus, ClientError> {
        let key = self.key.as_ref().ok_or(ClientError::Unauthorized)?;
        let body = serde_json::to_vec(req).map_err(|e| ClientError::Decode(e.to_string()))?;
        let mut req = request(
            Method::POST,
            &self.url,
            routes::PEOPLE_SYNC,
            Some(key),
            body,
        )?;
        req.headers_mut().insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static("application/json"),
        );
        match self.run_raw(req)? {
            Answer::Ok(resp) => decode(resp),
            Answer::Refused { status, body } => Err(from_status_optional(
                status,
                &body,
                "this gateway has no people sync job",
            )),
        }
    }

    /// The status of people job `job_id`. With `rev` (the revision the caller holds) and `wait_ms`, the gateway
    /// answers as soon as the job changes, or after `wait_ms` (at most 25 s) with the same status. A job the
    /// gateway no longer has (expired, dropped, the account changed) is an API `not_found`.
    pub fn people_sync_status(
        &self,
        job_id: &str,
        rev: Option<u64>,
        wait_ms: Option<u64>,
    ) -> Result<PeopleJobStatus, ClientError> {
        self.get(&people_status_path(job_id, rev, wait_ms))
    }

    /// Reads one part of a ready job's bundle: the records after `after` (each with its picture bytes, if any)
    /// go to `on_record` as they arrive; the answer is the part's end line (`last_seq` is the next `after`, `more`
    /// says another part follows). A part that ends early is [`ClientError::Http`] ("cut"): keep what was
    /// committed and ask again from the cursor.
    pub fn people_sync_bundle<F>(
        &self,
        job_id: &str,
        after: u32,
        max_bytes: Option<u64>,
        on_record: F,
    ) -> Result<rosty_wa_proto::BundleEnd, ClientError>
    where
        F: FnMut(PeopleRecord, Option<Vec<u8>>) -> Result<(), ClientError>,
    {
        let mut resp = self.exec(
            Method::GET,
            &people_bundle_path(job_id, after, max_bytes),
            true,
            Payload::None,
        )?;
        // ureq 3.5: `Body::with_config` -> `BodyWithConfig::limit` -> `reader` (src/body/mod.rs:421, 502, 557).
        let reader = resp
            .body_mut()
            .with_config()
            .limit(rosty_wa_proto::people::BUNDLE_READ_LIMIT)
            .reader();
        let mut reader = std::io::BufReader::new(reader);
        let (header, end) = rosty_wa_proto::people::read_bundle(&mut reader, on_record)?;
        if header.job_id != job_id || header.after != after {
            return Err(ClientError::Decode(
                "the bundle part is not the one asked for".to_owned(),
            ));
        }
        Ok(end)
    }

    /// Cancels people job `job_id` (it stops asking WhatsApp), or drops a finished one. A job that is already
    /// gone (or a gateway without the route) is not an error.
    pub fn people_sync_cancel(&self, job_id: &str) -> Result<(), ClientError> {
        let key = self.key.as_ref().ok_or(ClientError::Unauthorized)?;
        let path = routes::fill(routes::PEOPLE_SYNC_JOB, "job_id", job_id);
        let req = request(Method::DELETE, &self.url, &path, Some(key), ())?;
        match self.run_raw(req)? {
            Answer::Ok(_) => Ok(()),
            Answer::Refused { status, body } => {
                match from_status_optional(status, &body, "no such people job") {
                    error if error.is_not_found() => Ok(()),
                    error => Err(error),
                }
            }
        }
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
    fn receipts_path_encodes_id_and_chat() {
        assert_eq!(
            receipts_path("120363000000000001@g.us", "3EB0/1"),
            "/v1/messages/3EB0%2F1/receipts?chat=120363000000000001%40g.us"
        );
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
            rosty_wa_proto::ScheduledState::Sent {
                message_id: "M".into()
            }
        );
    }

    #[test]
    fn media_request_carries_the_new_options() {
        let jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0, 1, 2];
        let waveform = rosty_wa_proto::encode_b64(&[50; rosty_wa_proto::WAVEFORM_LEN]);
        let options = MediaOptions {
            seconds: Some(4),
            thumbnail_b64: Some(rosty_wa_proto::encode_b64(&jpeg)),
            width: Some(640),
            height: Some(480),
            waveform_b64: Some(waveform.clone()),
            animated: true,
            view_once: true,
            ..Default::default()
        };
        let req = media_request(
            "c",
            MediaKind::Video,
            "video/mp4",
            None,
            &options,
            vec![9, 8, 7],
        )
        .unwrap();
        // "MjIy..." has no character a query must escape; padding does.
        let tail = format!(
            "&seconds=4&width=640&height=480&waveform_b64={}&animated=true&view_once=true&thumbnail_len=6",
            waveform.replace('=', "%3D")
        );
        assert!(
            req.path
                .starts_with("/v1/messages/media/options?chat=c&kind=video"),
            "{}",
            req.path
        );
        assert!(req.path.ends_with(&tail), "{}", req.path);
        assert!(!req.path.contains("thumbnail_b64"), "{}", req.path);
        assert_eq!(req.body, [jpeg.as_slice(), &[9, 8, 7]].concat());
        assert_eq!((req.thumbnail_len, req.file()), (6, &[9u8, 8, 7][..]));
        // View once never falls back to a gateway that would send it as an ordinary message.
        assert!(req.newer);
        assert_eq!(req.fallback, None);

        // Anything else of the media round falls back to the plain route, without it.
        let sized = MediaOptions {
            view_once: false,
            ..options.clone()
        };
        let req = media_request("c", MediaKind::Video, "video/mp4", None, &sized, vec![9]).unwrap();
        assert_eq!(
            req.fallback.as_deref(),
            Some("/v1/messages/media?chat=c&kind=video&mime=video%2Fmp4&seconds=4")
        );

        // A send without those options is the request it always was.
        let plain = MediaOptions {
            file_name: Some("a.pdf".into()),
            ..Default::default()
        };
        let req = media_request("c", MediaKind::Document, "a/b", None, &plain, vec![1]).unwrap();
        assert_eq!(
            req,
            MediaRequest {
                path: "/v1/messages/media?chat=c&kind=document&mime=a%2Fb&file_name=a.pdf".into(),
                body: vec![1],
                thumbnail_len: 0,
                newer: false,
                fallback: None,
            }
        );
    }

    #[test]
    fn media_request_refuses_what_the_gateway_would() {
        let refuse = |kind, options: MediaOptions| {
            media_request("c", kind, "a/b", None, &options, vec![1]).unwrap_err()
        };
        assert_eq!(
            refuse(
                MediaKind::Image,
                MediaOptions {
                    thumbnail_b64: Some("iVBORw0K".into()),
                    ..Default::default()
                }
            ),
            MediaOptionError::ThumbnailNotJpeg
        );
        assert_eq!(
            refuse(
                MediaKind::Audio,
                MediaOptions {
                    waveform_b64: Some("AAEC".into()),
                    ..Default::default()
                }
            ),
            MediaOptionError::WaveformLength { len: 3 }
        );
        assert_eq!(
            refuse(
                MediaKind::Document,
                MediaOptions {
                    view_once: true,
                    ..Default::default()
                }
            ),
            MediaOptionError::ViewOnceKind
        );
    }

    #[test]
    fn media_options_and_picture_paths() {
        let options = MediaOptions {
            quoted_id: Some("Q 1".into()),
            file_name: Some("rapor 1.pdf".into()),
            seconds: Some(9),
            voice_note: true,
            ..Default::default()
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
        assert_eq!(
            gw.identity_mappings(None).unwrap_err(),
            ClientError::Unauthorized
        );
    }

    #[test]
    fn mappings_path_carries_the_cursor() {
        assert_eq!(mappings_path(None), "/v1/identity/mappings");
        assert_eq!(mappings_path(Some("")), "/v1/identity/mappings");
        assert_eq!(mappings_path(Some("41")), "/v1/identity/mappings?since=41");
        // A cursor is opaque: whatever it holds stays one query value.
        assert_eq!(
            mappings_path(Some("a b&c")),
            "/v1/identity/mappings?since=a%20b%26c"
        );
        let host = GatewayUrl::parse("https://rosty.erdemdev.tr").unwrap();
        let req = request(Method::GET, &host, &mappings_path(Some("41")), None, ()).unwrap();
        assert_eq!(
            req.uri().to_string(),
            "https://rosty.erdemdev.tr/v1/identity/mappings?since=41"
        );
    }

    #[test]
    fn mapping_pages_decode() {
        let body = r#"{"items":[{"lid":"1234@lid","pn":"905550000001@s.whatsapp.net",
            "source":"usync","learned_ms":1700000000000}],"next":"7"}"#;
        let page: IdentityMappings = decode(Response::new(Body::builder().data(body))).unwrap();
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items[0].lid, "1234@lid");
        assert_eq!(page.items[0].pn, "905550000001@s.whatsapp.net");
        assert_eq!(
            page.items[0].source(),
            rosty_wa_proto::IdentitySource::Usync
        );
        assert_eq!(page.next.as_deref(), Some("7"));
        let last: IdentityMappings = decode(Response::new(
            Body::builder().data(r#"{"items":[],"next":null}"#),
        ))
        .unwrap();
        assert_eq!(last, IdentityMappings::default());
    }

    #[test]
    fn lists_decode_with_and_without_identity_fields() {
        let old = r#"[{"jid":"905550000001@s.whatsapp.net","name":null,"last":null,"unread":0,
            "is_group":false}]"#;
        let chats: Vec<ChatSummary> = decode(Response::new(Body::builder().data(old))).unwrap();
        assert_eq!(chats[0].alt, None);
        let new = r#"[{"jid":"905550000001@s.whatsapp.net","name":null,"unread":0,
            "is_group":false,"alt":"1234@lid","last":{"id":"m","chat":"905550000001@s.whatsapp.net",
            "sender":null,"from_me":false,"timestamp_ms":1,"body":{"text":"hi"},"reactions":[],
            "chat_alt":"1234@lid"}}]"#;
        let chats: Vec<RichChat> = decode(Response::new(Body::builder().data(new))).unwrap();
        assert_eq!(chats[0].chat.alt.as_deref(), Some("1234@lid"));
        let last = chats[0].chat.last.as_ref().unwrap();
        assert_eq!(last.chat_alt.as_deref(), Some("1234@lid"));
        assert_eq!(last.sender_alt, None);

        let status: InstanceStatus = decode(Response::new(Body::builder().data(
            r#"{"instance_id":"i","state":"connected","own_jid":"905550000009@s.whatsapp.net",
            "push_name":null,"own_lid":"9@lid"}"#,
        )))
        .unwrap();
        assert_eq!(status.own_lid.as_deref(), Some("9@lid"));
        // A gateway that knows its epoch, the account epoch and the history.
        let status: InstanceStatus = decode(Response::new(Body::builder().data(
            r#"{"instance_id":"i","state":"connected","own_jid":"100000000001@s.whatsapp.net",
            "push_name":null,"epoch":"9f2c41d07ab35e68","account_epoch":2,
            "history":{"state":"syncing","progress":40,"chats":12,"messages":3400,
            "oldest_ms":1500000000000}}"#,
        )))
        .unwrap();
        assert_eq!(status.epoch.as_deref(), Some("9f2c41d07ab35e68"));
        assert_eq!(status.account_epoch, Some(2));
        let history = status.history.unwrap();
        assert_eq!(history.state, rosty_wa_proto::HistoryState::Syncing);
        assert_eq!((history.progress, history.chats), (Some(40), 12));
        assert_eq!(
            (history.messages, history.oldest_ms),
            (3400, Some(1_500_000_000_000))
        );
        // One from before: all three are unset.
        let status: InstanceStatus = decode(Response::new(
            Body::builder()
                .data(r#"{"instance_id":"i","state":"connected","own_jid":null,"push_name":null}"#),
        ))
        .unwrap();
        assert_eq!(
            (status.epoch, status.account_epoch, status.history),
            (None, None, None)
        );
        let group: GroupInfo = decode(Response::new(Body::builder().data(
            r#"{"jid":"1-2@g.us","subject":"s","addressing_mode":"lid","participants":[
            {"jid":"905550000001@s.whatsapp.net","admin":false,"lid":"1234@lid",
            "pn":"905550000001@s.whatsapp.net"}]}"#,
        )))
        .unwrap();
        assert_eq!(group.addressing_mode.as_deref(), Some("lid"));
        assert_eq!(group.participants[0].lid.as_deref(), Some("1234@lid"));
    }
}
