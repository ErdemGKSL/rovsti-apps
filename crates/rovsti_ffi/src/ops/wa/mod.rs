//! `wa.*`: the WhatsApp gateway (E2.14).
//!
//! `wa.open {url, key?, cache_dir?}` builds a `rovsti_wa_client::Gateway` and answers its handle; every network
//! op then takes `{handle, ...}` and runs the blocking client call (the Kotlin facade runs it on `RustDispatcher`).
//! The registry is not locked while a request is in flight: an op clones the cheap `Gateway` out first, so a slow
//! gateway never stalls `release` or another handle.
//!
//! * `rest`: one op per `Gateway` method; bytes travel as base64, or, above [`INLINE_LIMIT`], as a temp file in the
//!   handle's cache directory (the answer then holds `path` instead of `data_b64`; Kotlin deletes the file).
//! * `pure`: the ops that need no gateway (`wa.push_parse`, `wa.link_*`, `wa.avatars_plan`, `wa.names`, `wa.target`).
//! * `live`: `wa.events_start` (a stream handle that emits `wa.event` through the sink) and `wa.call_audio_open`
//!   (a handle for the `audioPush` / `audioPull` exports).
//!
//! Failures keep one mapping ([`map_error`]): `network`, `auth` (401 and 403), `invalid` with the gateway's own
//! sentence (`detail.code` holds its error code) and `parse` for an answer that does not decode.

/// Like `op!`, with the fixture cases written inline as a closure.
macro_rules! wa_op {
    ($name:literal, $req:ty, $ans:ty, $handler:path, $cases:expr) => {
        $crate::ops::registry::OpSpec {
            name: $name,
            run: |request| $crate::ops::registry::run_typed::<$req, $ans>(request, $handler),
            cases: $cases,
        }
    };
}

/// Defines the handler of a gateway op: the client call runs on the gateway of `req.handle`, outside the registry
/// lock, and its `ClientError` goes through `map_error`.
macro_rules! gateway_fn {
    ($fn:ident, $body:ty => $ans:ty, |$g:ident, $b:ident| $call:expr) => {
        fn $fn(req: $crate::ops::wa::Call<$body>) -> Result<$ans, $crate::envelope::FfiError> {
            let $g = $crate::ops::wa::ctx(req.handle)?;
            let $b = req.body;
            $call.map_err($crate::ops::wa::map_error)
        }
    };
}

mod live;
mod pure;
mod rest;
mod settings;

pub use live::{PULL_WAIT, audio_pull, audio_pull_within, audio_push};

use super::registry::{Case, OpSpec};
use crate::envelope::{ErrorKind, FfiError};
use crate::{handles, state};
use rovsti_wa_client::{ClientError, Gateway};
use rovsti_wa_proto::{ConnectKey, GatewayUrl};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::ops::Deref;
use std::path::PathBuf;

/// The op tables of the submodules, in the order the registry lists them.
pub(super) const TABLES: &[&[OpSpec]] = &[
    rest::OPS,
    rest::MEDIA_OPS,
    pure::OPS,
    settings::OPS,
    live::OPS,
];

/// Media and pictures up to this size travel inside the JSON answer; larger ones become a file.
pub(crate) const INLINE_LIMIT: usize = 1024 * 1024;

/// What `wa.open` keeps.
pub(crate) struct Wa {
    pub gateway: Gateway,
    pub url: GatewayUrl,
    pub key: Option<ConnectKey>,
    pub cache_dir: PathBuf,
}

/// A request that names a handle and carries the rest of its fields flat next to it.
#[derive(Deserialize)]
pub(crate) struct Call<T> {
    pub handle: i64,
    #[serde(flatten)]
    pub body: T,
}

/// The body of an op that sends nothing but its handle.
#[derive(Deserialize)]
pub(crate) struct Nothing {}

/// The answer `{}` of an op that has nothing to say but "done".
#[derive(Serialize)]
pub(crate) struct Empty {}

/// Turns a unit result into the answer `{}`.
pub(crate) fn done(result: Result<(), ClientError>) -> Result<Empty, ClientError> {
    result.map(|()| Empty {})
}

#[derive(Serialize)]
pub(crate) struct HandleAns {
    pub handle: i64,
}

/// A gateway taken out of the registry: the client plus the facts an op needs.
pub(crate) struct Ctx {
    gateway: Gateway,
    pub url: GatewayUrl,
    pub key: Option<ConnectKey>,
    pub cache_dir: PathBuf,
}

impl Deref for Ctx {
    type Target = Gateway;
    fn deref(&self) -> &Gateway {
        &self.gateway
    }
}

pub(crate) fn not_found(what: &str, handle: i64) -> FfiError {
    FfiError::new(
        ErrorKind::NotFound,
        format!("{what} handle {handle} is not open"),
    )
}

/// The gateway of `handle`; a released or foreign handle is `not_found`.
pub(crate) fn ctx(handle: i64) -> Result<Ctx, FfiError> {
    handles::global()
        .with(handle, |wa: &mut Wa| Ctx {
            gateway: wa.gateway.clone(),
            url: wa.url.clone(),
            key: wa.key.clone(),
            cache_dir: wa.cache_dir.clone(),
        })
        .ok_or_else(|| not_found("wa", handle))
}

/// `ClientError` to the envelope kinds the Kotlin side knows.
pub(crate) fn map_error(error: ClientError) -> FfiError {
    match error {
        ClientError::Unauthorized => FfiError::new(
            ErrorKind::Auth,
            "the gateway rejected the connect key".to_owned(),
        ),
        ClientError::Api(api) => {
            let mut detail = json!({ "code": api.code });
            if let Some(ms) = api.retry_after_ms {
                detail["retry_after_ms"] = json!(ms);
            }
            if let Some(source) = api.limit_source {
                detail["source"] = json!(source);
            }
            FfiError::invalid(api.message).with_detail(detail)
        }
        ClientError::Url(error) => FfiError::invalid(error.to_string()),
        ClientError::Key(error) => FfiError::invalid(error.to_string()),
        ClientError::Decode(message) => FfiError::new(ErrorKind::Parse, message),
        // A 403 without an API body (a proxy, a firewall) still means "not allowed in".
        ClientError::Http(message) if forbidden(&message) => {
            FfiError::new(ErrorKind::Auth, "the gateway refused access".to_owned())
        }
        ClientError::Http(message) | ClientError::Ws(message) | ClientError::Tls(message) => {
            FfiError::new(ErrorKind::Network, message)
        }
        ClientError::Closed => FfiError::new(ErrorKind::Network, "connection closed".to_owned()),
    }
}

fn forbidden(message: &str) -> bool {
    message.ends_with("(status 403)")
}

/// Fixture helpers shared by the tables.
pub(crate) mod fixture {
    use super::*;

    /// The connect key of every fixture: `ConnectKey::from_bytes(&[5; 32])`.
    pub const KEY: &str = "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU";
    /// A chat and a person that appear in many canned answers.
    pub const CHAT: &str = "905321234567@s.whatsapp.net";

    /// `wa.open` against the fixture stub (`$WA` is its URL).
    pub fn open() -> (&'static str, Value) {
        ("wa.open", json!({"url": "$WA", "key": KEY}))
    }

    /// A main case: `body` plus the handle of a freshly opened gateway.
    pub fn case(mut body: Value) -> Case {
        body["handle"] = json!(crate::ops::registry::PLACEHOLDER_HANDLE);
        let (op, req) = open();
        Case::new(body).after(op, req)
    }

    /// A named extra case on the same footing.
    pub fn named(label: &'static str, mut body: Value) -> Case {
        body["handle"] = json!(crate::ops::registry::PLACEHOLDER_HANDLE);
        let (op, req) = open();
        Case::named(label, body).after(op, req)
    }
}

/// `wa.open {url, key?, cache_dir?}`.
#[derive(Deserialize)]
pub(super) struct OpenReq {
    url: String,
    #[serde(default)]
    key: Option<String>,
    #[serde(default)]
    cache_dir: Option<String>,
}

pub(super) fn open(req: OpenReq) -> Result<HandleAns, FfiError> {
    let url = GatewayUrl::parse(&req.url).map_err(|e| map_error(e.into()))?;
    let key = match req.key.as_deref().map(str::trim).filter(|k| !k.is_empty()) {
        Some(key) => Some(ConnectKey::parse(key).map_err(|e| map_error(e.into()))?),
        None => None,
    };
    let cache_dir = match req.cache_dir {
        Some(dir) => PathBuf::from(dir),
        None => state::dirs()
            .map(|dirs| dirs.cache_dir.join("wa-media"))
            .unwrap_or_else(|| std::env::temp_dir().join("rovsti-wa-media")),
    };
    let gateway = Gateway::new(url.clone(), key.clone());
    let handle = handles::global().insert(Wa {
        gateway,
        url,
        key,
        cache_dir,
    });
    Ok(HandleAns { handle })
}

pub(super) fn open_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"url": "$WA", "key": fixture::KEY})),
        Case::named("no_key", json!({"url": "$WA"})),
        Case::named(
            "bad_url",
            json!({"url": "wa.example.com", "key": fixture::KEY}),
        ),
        Case::named(
            "insecure",
            json!({"url": "http://wa.example.com", "key": fixture::KEY}),
        ),
        Case::named("bad_key", json!({"url": "$WA", "key": "rwk_short"})),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::{ApiError, ErrorCode};

    #[test]
    fn client_errors_map_to_the_documented_kinds() {
        let kind = |e: ClientError| map_error(e).kind;
        assert_eq!(kind(ClientError::Unauthorized), ErrorKind::Auth);
        assert_eq!(
            kind(ClientError::Http("unexpected response (status 403)".into())),
            ErrorKind::Auth
        );
        assert_eq!(
            kind(ClientError::Http("unexpected response (status 502)".into())),
            ErrorKind::Network
        );
        assert_eq!(kind(ClientError::Ws("x".into())), ErrorKind::Network);
        assert_eq!(kind(ClientError::Tls("x".into())), ErrorKind::Network);
        assert_eq!(kind(ClientError::Closed), ErrorKind::Network);
        assert_eq!(kind(ClientError::Decode("x".into())), ErrorKind::Parse);
        let api = map_error(ClientError::Api(ApiError::new(
            ErrorCode::LimitReached,
            "too many instances",
        )));
        assert_eq!(api.kind, ErrorKind::Invalid);
        assert_eq!(api.message, "too many instances");
        assert_eq!(api.detail, Some(json!({"code": "limit_reached"})));
        let limited = map_error(ClientError::Api(ApiError::rate_limited(
            "slow",
            Some(61_000),
            rovsti_wa_proto::LimitSource::Whatsapp,
        )));
        assert_eq!(limited.kind, ErrorKind::Invalid);
        assert_eq!(
            limited.detail,
            Some(json!({"code": "rate_limited", "retry_after_ms": 61_000, "source": "whatsapp"}))
        );
    }

    #[test]
    fn a_released_handle_is_not_found() {
        let opened = open(OpenReq {
            url: "https://wa.example.com".into(),
            key: Some(fixture::KEY.into()),
            cache_dir: None,
        })
        .unwrap();
        assert!(ctx(opened.handle).is_ok());
        assert!(handles::global().release(opened.handle));
        assert_eq!(ctx(opened.handle).err().unwrap().kind, ErrorKind::NotFound);
    }
}
