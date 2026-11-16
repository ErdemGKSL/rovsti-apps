#![allow(clippy::unwrap_used)]

//! Talks to a throwaway HTTP/1.1 stub on loopback: no server crate involved.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use rosty_wa_client::{ClientError, Gateway, Picture};
use rosty_wa_proto::{
    ChatAction, ChatActionKind, ConnectKey, ErrorCode, GatewayUrl, IdentitySource, InstanceState,
    MediaKind, MediaOptions,
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

#[test]
fn identity_mappings_page_with_the_cursor() {
    let body = r#"{"items":[{"lid":"1234@lid","pn":"905550000001@s.whatsapp.net","source":"peer_message","learned_ms":1700000000000},{"lid":"77@lid","pn":"905550000002@s.whatsapp.net","source":"from_a_newer_gateway","learned_ms":5}],"next":"12"}"#;
    let (url, request) = stub("200 OK", body);
    let page = Gateway::new(url, Some(key()))
        .identity_mappings(Some("10"))
        .unwrap();
    assert_eq!(page.items.len(), 2);
    assert_eq!(page.items[0].lid, "1234@lid");
    assert_eq!(page.items[0].source(), IdentitySource::PeerMessage);
    // A source this build does not know is kept as text and reads as other.
    assert_eq!(page.items[1].source, "from_a_newer_gateway");
    assert_eq!(page.items[1].source(), IdentitySource::Other);
    assert_eq!(page.next.as_deref(), Some("12"));
    let raw = request.join().unwrap().to_ascii_lowercase();
    assert!(
        raw.starts_with("get /v1/identity/mappings?since=10 http/1.1"),
        "{raw}"
    );
    assert!(raw.contains("authorization: bearer "), "{raw}");

    // The first page asks without a cursor; an empty page ends the paging.
    let (url, request) = stub("200 OK", r#"{"items":[],"next":null}"#);
    let page = Gateway::new(url, Some(key()))
        .identity_mappings(None)
        .unwrap();
    assert!(page.items.is_empty());
    assert_eq!(page.next, None);
    let raw = request.join().unwrap().to_ascii_lowercase();
    assert!(
        raw.starts_with("get /v1/identity/mappings http/1.1"),
        "{raw}"
    );
}

#[test]
fn an_older_gateway_has_no_identity_mappings() {
    // A gateway from before the route: the router's 404, no API body.
    let (url, request) = stub_with("404 Not Found", "", "");
    let err = Gateway::new(url, Some(key()))
        .identity_mappings(None)
        .unwrap_err();
    assert!(err.is_not_found(), "{err:?}");
    match err {
        ClientError::Api(api) => {
            assert_eq!(api.code, ErrorCode::NotFound);
            assert_eq!(api.message, "this gateway does not list identity mappings");
        }
        other => panic!("unexpected error: {other:?}"),
    }
    request.join().unwrap();

    // Other answers keep their meaning.
    let (url, request) = stub(
        "400 Bad Request",
        r#"{"code":"bad_request","message":"since is not a cursor"}"#,
    );
    let err = Gateway::new(url, Some(key()))
        .identity_mappings(Some("x"))
        .unwrap_err();
    assert!(matches!(&err, ClientError::Api(e) if e.code == ErrorCode::BadRequest));
    assert!(!err.is_not_found());
    request.join().unwrap();

    let (url, request) = stub("401 Unauthorized", "");
    let err = Gateway::new(url, Some(key()))
        .identity_mappings(None)
        .unwrap_err();
    assert_eq!(err, ClientError::Unauthorized);
    request.join().unwrap();

    let (url, request) = stub_with("502 Bad Gateway", "", "down");
    let err = Gateway::new(url, Some(key()))
        .identity_mappings(None)
        .unwrap_err();
    assert!(matches!(err, ClientError::Http(_)), "{err:?}");
    request.join().unwrap();
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

/// One request seen by a stub: the request line and the body.
type Asked = (String, Vec<u8>);

/// Answers one request per entry of `answers` (`(status, body)`), each on its
/// own connection, and returns what was asked: the request line and the body.
fn stub_each(
    answers: Vec<(&'static str, &'static str)>,
) -> (GatewayUrl, thread::JoinHandle<Vec<Asked>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = thread::spawn(move || {
        let mut asked = Vec::new();
        for (status, body) in answers {
            let (mut stream, _) = listener.accept().unwrap();
            let mut seen = Vec::new();
            let mut buf = [0u8; 1024];
            let head_end = loop {
                if let Some(at) = seen.windows(4).position(|w| w == b"\r\n\r\n") {
                    break at + 4;
                }
                let n = stream.read(&mut buf).unwrap();
                assert!(n > 0, "the request ended before its headers");
                seen.extend_from_slice(&buf[..n]);
            };
            let head = String::from_utf8_lossy(&seen[..head_end]).into_owned();
            let length: usize = head
                .lines()
                .find_map(|line| {
                    let (name, value) = line.split_once(':')?;
                    name.eq_ignore_ascii_case("content-length")
                        .then(|| value.trim().parse().unwrap())
                })
                .unwrap_or(0);
            while seen.len() < head_end + length {
                let n = stream.read(&mut buf).unwrap();
                assert!(n > 0, "the request ended before its body");
                seen.extend_from_slice(&buf[..n]);
            }
            let response = format!(
                "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            stream.write_all(response.as_bytes()).unwrap();
            let line = head.lines().next().unwrap_or("").to_owned();
            asked.push((line, seen[head_end..].to_vec()));
        }
        asked
    });
    let url = GatewayUrl::parse(&format!("http://127.0.0.1:{port}")).unwrap();
    (url, handle)
}

const SENT: &str = r#"{"message_id":"M1","timestamp_ms":5}"#;
const JPEG: [u8; 4] = [0xFF, 0xD8, 0xFF, 0xE0];

fn with_preview() -> MediaOptions {
    MediaOptions {
        seconds: Some(4),
        thumbnail_b64: Some(rosty_wa_proto::encode_b64(&JPEG)),
        width: Some(640),
        height: Some(360),
        ..MediaOptions::default()
    }
}

fn send_video(
    url: GatewayUrl,
    options: &MediaOptions,
) -> Result<rosty_wa_proto::Sent, ClientError> {
    Gateway::new(url, Some(key())).send_media_with(
        "c",
        MediaKind::Video,
        "video/mp4",
        None,
        options,
        b"FILE".to_vec(),
    )
}

#[test]
fn newer_media_options_go_to_their_own_route_with_the_preview_in_front_of_the_file() {
    let (url, asked) = stub_each(vec![("200 OK", SENT)]);
    let sent = send_video(url, &with_preview()).unwrap();
    assert_eq!(sent.message_id, "M1");
    let asked = asked.join().unwrap();
    assert_eq!(
        asked[0].0,
        "POST /v1/messages/media/options?chat=c&kind=video&mime=video%2Fmp4&seconds=4&width=640&height=360&thumbnail_len=4 HTTP/1.1"
    );
    assert_eq!(asked[0].1, [&JPEG[..], b"FILE"].concat());

    // A send without them is the request it always was.
    let (url, asked) = stub_each(vec![("200 OK", SENT)]);
    let plain = MediaOptions {
        seconds: Some(4),
        ..MediaOptions::default()
    };
    send_video(url, &plain).unwrap();
    let asked = asked.join().unwrap();
    assert_eq!(
        asked[0].0,
        "POST /v1/messages/media?chat=c&kind=video&mime=video%2Fmp4&seconds=4 HTTP/1.1"
    );
    assert_eq!(asked[0].1, b"FILE");
}

#[test]
fn an_older_gateway_gets_the_file_the_old_way_but_never_a_view_once_one() {
    // The router's 404 (no API body), then the plain route takes the file alone.
    let (url, asked) = stub_each(vec![("404 Not Found", ""), ("200 OK", SENT)]);
    let sent = send_video(url, &with_preview()).unwrap();
    assert_eq!(sent.message_id, "M1");
    let asked = asked.join().unwrap();
    assert!(
        asked[0].0.starts_with("POST /v1/messages/media/options?"),
        "{}",
        asked[0].0
    );
    assert_eq!(
        asked[1].0,
        "POST /v1/messages/media?chat=c&kind=video&mime=video%2Fmp4&seconds=4 HTTP/1.1"
    );
    assert_eq!(asked[1].1, b"FILE");

    // View once is not sent as an ordinary message: one request, then an error.
    let (url, asked) = stub_each(vec![("404 Not Found", "")]);
    let once = MediaOptions {
        view_once: true,
        ..MediaOptions::default()
    };
    let err = send_video(url, &once).unwrap_err();
    assert!(err.is_not_found(), "{err:?}");
    assert_eq!(asked.join().unwrap().len(), 1);

    // A 404 the gateway explains is not "no such route": no second request.
    let (url, asked) = stub_each(vec![(
        "404 Not Found",
        r#"{"code":"not_found","message":"no such chat"}"#,
    )]);
    let err = send_video(url, &with_preview()).unwrap_err();
    assert!(
        matches!(&err, ClientError::Api(e) if e.message == "no such chat"),
        "{err:?}"
    );
    assert_eq!(asked.join().unwrap().len(), 1);
}
