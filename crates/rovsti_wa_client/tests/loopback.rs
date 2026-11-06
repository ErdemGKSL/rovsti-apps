#![allow(clippy::unwrap_used)]

//! Talks to a throwaway HTTP/1.1 stub on loopback: no server crate involved.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use rovsti_wa_client::{ClientError, Gateway, Picture};
use rovsti_wa_proto::{
    ChatAction, ChatActionKind, ConnectKey, ErrorCode, GatewayUrl, InstanceState,
};

/// Answers one request with `status` and `body`, returning the raw request
/// text through the join handle.
fn stub(status: &str, body: &str) -> (GatewayUrl, thread::JoinHandle<String>) {
    stub_with(status, "Content-Type: application/json\r\n", body)
}

/// Like [`stub`] with the given header lines (each ending in `\r\n`).
fn stub_with(status: &str, headers: &str, body: &str) -> (GatewayUrl, thread::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let response = format!(
        "HTTP/1.1 {status}\r\n{headers}Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut seen = Vec::new();
        let mut buf = [0u8; 1024];
        while !seen.windows(4).any(|w| w == b"\r\n\r\n") {
            let n = stream.read(&mut buf).unwrap();
            if n == 0 {
                break;
            }
            seen.extend_from_slice(&buf[..n]);
        }
        stream.write_all(response.as_bytes()).unwrap();
        String::from_utf8_lossy(&seen).into_owned()
    });
    let url = GatewayUrl::parse(&format!("http://127.0.0.1:{port}")).unwrap();
    (url, handle)
}

fn key() -> ConnectKey {
    ConnectKey::from_bytes(&[5; 32])
}

#[test]
fn status_parses_and_sends_the_bearer() {
    let body = r#"{"instance_id":"abc","state":"connected","own_jid":"90532@s.whatsapp.net","push_name":"Ada"}"#;
    let (url, request) = stub("200 OK", body);
    let status = Gateway::new(url, Some(key())).status().unwrap();
    assert_eq!(status.instance_id, "abc");
    assert_eq!(status.state, InstanceState::Connected);
    assert_eq!(status.push_name.as_deref(), Some("Ada"));

    let raw = request.join().unwrap().to_ascii_lowercase();
    assert!(raw.starts_with("get /v1/instance http/1.1"), "{raw}");
    assert!(
        raw.contains(&format!(
            "authorization: bearer {}",
            key().as_str().to_ascii_lowercase()
        )),
        "{raw}"
    );
}

#[test]
fn a_401_is_unauthorized() {
    let (url, request) = stub(
        "401 Unauthorized",
        r#"{"code":"unauthorized","message":"bad key"}"#,
    );
    let err = Gateway::new(url, Some(key())).status().unwrap_err();
    assert_eq!(err, ClientError::Unauthorized);
    request.join().unwrap();
}

#[test]
fn api_error_bodies_come_through() {
    let (url, request) = stub(
        "409 Conflict",
        r#"{"code":"limit_reached","message":"too many instances"}"#,
    );
    match Gateway::new(url, Some(key())).status().unwrap_err() {
        ClientError::Api(api) => {
            assert_eq!(api.code, ErrorCode::LimitReached);
            assert_eq!(api.message, "too many instances");
        }
        other => panic!("unexpected error: {other:?}"),
    }
    request.join().unwrap();
}

#[test]
fn garbage_success_bodies_are_decode_errors() {
    let (url, request) = stub("200 OK", "not json");
    let err = Gateway::new(url, Some(key())).status().unwrap_err();
    assert!(matches!(err, ClientError::Decode(_)), "{err:?}");
    request.join().unwrap();
}

#[test]
fn a_picture_comes_with_its_id() {
    let (url, request) = stub_with(
        "200 OK",
        "Content-Type: image/jpeg\r\nETag: \"55\"\r\n",
        "JPEG",
    );
    let picture = Gateway::new(url, Some(key()))
        .picture("905321234567@s.whatsapp.net", true, None)
        .unwrap();
    assert_eq!(
        picture,
        Picture::Image {
            id: "55".into(),
            bytes: b"JPEG".to_vec()
        }
    );
    let raw = request.join().unwrap().to_ascii_lowercase();
    assert!(
        raw.starts_with("get /v1/pictures/905321234567%40s.whatsapp.net?full=true http/1.1"),
        "{raw}"
    );
    assert!(!raw.contains("if-none-match"), "{raw}");
}

#[test]
fn a_known_picture_is_unchanged_and_a_missing_one_is_none() {
    let (url, request) = stub_with("304 Not Modified", "ETag: \"55\"\r\n", "");
    let picture = Gateway::new(url, Some(key()))
        .picture("1-2@g.us", false, Some("55"))
        .unwrap();
    assert_eq!(picture, Picture::Unchanged);
    let raw = request.join().unwrap().to_ascii_lowercase();
    assert!(raw.contains("if-none-match: \"55\""), "{raw}");

    let (url, request) = stub(
        "404 Not Found",
        r#"{"code":"not_found","message":"no profile picture"}"#,
    );
    let picture = Gateway::new(url, Some(key()))
        .picture("1-2@g.us", false, None)
        .unwrap();
    assert_eq!(picture, Picture::None);
    request.join().unwrap();

    let (url, request) = stub(
        "429 Too Many Requests",
        r#"{"code":"rate_limited","message":"slow down"}"#,
    );
    let err = Gateway::new(url, Some(key()))
        .picture("1-2@g.us", false, None)
        .unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::RateLimited));
    request.join().unwrap();
}

#[test]
fn profiles_and_chat_actions_use_their_routes() {
    let (url, request) = stub(
        "200 OK",
        r#"[{"jid":"1@s.whatsapp.net","push_name":"Ada","picture_id":"9","fetched_ms":5}]"#,
    );
    let profiles = Gateway::new(url, Some(key()))
        .profiles(&["1@s.whatsapp.net".to_owned()])
        .unwrap();
    assert_eq!(profiles[0].display_name(), Some("Ada"));
    assert_eq!(profiles[0].picture_id.as_deref(), Some("9"));
    let raw = request.join().unwrap().to_ascii_lowercase();
    assert!(
        raw.starts_with("post /v1/contacts/profiles http/1.1"),
        "{raw}"
    );

    let (url, request) = stub("204 No Content", "");
    Gateway::new(url, Some(key()))
        .chat_action(&ChatAction {
            chat: "1@s.whatsapp.net".into(),
            action: ChatActionKind::Pin { pinned: true },
        })
        .unwrap();
    let raw = request.join().unwrap().to_ascii_lowercase();
    assert!(raw.starts_with("post /v1/chats/action http/1.1"), "{raw}");
}

/// An `https` gateway builds the rustls config (WebPKI roots) on the first
/// connection. Without ureq's `rustls-webpki-roots` feature that panicked
/// ("WebPki is disabled") instead of returning an error.
#[test]
fn https_builds_tls_and_fails_cleanly() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = thread::spawn(move || drop(listener.accept().unwrap()));
    let url = GatewayUrl::parse(&format!("https://127.0.0.1:{port}")).unwrap();
    let err = Gateway::new(url, Some(key())).status().unwrap_err();
    assert!(matches!(err, ClientError::Http(_)), "{err:?}");
    handle.join().unwrap();
}
