//! A throwaway gateway on loopback for the `wa.*` tests: plain HTTP/1.1 answers canned from
//! `tests/data/wa/<stem>.json`, and WebSocket upgrades accepted by `tungstenite` (the pattern of
//! `rovsti_wa_client/tests/loopback.rs`, extended to several requests and to sockets).
//!
//! A route file looks like:
//!
//! ```json
//! { "expect": {"method": "GET", "path": "/v1/chats"},
//!   "status": 200, "headers": {"ETag": "\"55\""}, "body": [...] }
//! ```
//!
//! `expect` is what the client must have sent (`method`, `path` with its query, and optionally `body` as JSON or
//! `body_b64`); `null` means no request at all. The body of the answer is `body` (JSON), `body_text`, `body_b64` or
//! `body_repeat {byte, len}`. `"drop": true` closes the connection without an answer; `"ws": true` accepts a
//! WebSocket upgrade instead of answering HTTP. Every request must carry the fixture bearer key unless the route
//! says `"authed": false`.
#![allow(dead_code, clippy::unwrap_used, clippy::expect_used)]

use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::Value;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use tokio_tungstenite::tungstenite;

pub const BEARER: &str = "Bearer rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU";

#[derive(Debug, Clone)]
pub struct Seen {
    pub method: String,
    pub path: String,
    pub authorization: Option<String>,
    pub body: Vec<u8>,
}

#[derive(Debug, Clone, Default)]
pub struct Route {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    pub ws: bool,
    /// Text frames a WebSocket route sends right after the upgrade (`ws_send`).
    pub ws_send: Vec<String>,
    /// Echo binary frames in pieces of this many bytes (`ws_echo_bytes`), like a gateway that sends 20 ms speaker
    /// frames. `None`: echo them whole, like a gateway from before that.
    pub ws_echo_bytes: Option<usize>,
    pub drop: bool,
    pub authed: bool,
    /// `None`: nothing to check. `Some(None)`: no request expected.
    pub expect: Option<Option<Expect>>,
}

#[derive(Debug, Clone)]
pub struct Expect {
    pub method: String,
    pub path: String,
    pub body: Option<Vec<u8>>,
    pub body_json: Option<Value>,
}

impl Route {
    /// The route file of `stem`, if there is one.
    pub fn load(dir: &Path, stem: &str) -> Option<Route> {
        let text = std::fs::read_to_string(dir.join(format!("{stem}.json"))).ok()?;
        let v: Value = serde_json::from_str(&text).unwrap_or_else(|e| panic!("{stem}.json: {e}"));
        let body = if let Some(b) = v.get("body") {
            serde_json::to_vec(b).unwrap()
        } else if let Some(t) = v.get("body_text").and_then(Value::as_str) {
            t.as_bytes().to_vec()
        } else if let Some(b) = v.get("body_b64").and_then(Value::as_str) {
            STANDARD.decode(b).unwrap()
        } else if let Some(r) = v.get("body_repeat") {
            vec![r["byte"].as_u64().unwrap() as u8; r["len"].as_u64().unwrap() as usize]
        } else {
            Vec::new()
        };
        let headers = v
            .get("headers")
            .and_then(Value::as_object)
            .map(|h| {
                h.iter()
                    .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
                    .collect()
            })
            .unwrap_or_default();
        let expect = v.get("expect").map(|e| {
            e.as_object().map(|e| Expect {
                method: e["method"].as_str().unwrap().to_owned(),
                path: e["path"].as_str().unwrap().to_owned(),
                body: e
                    .get("body_b64")
                    .and_then(Value::as_str)
                    .map(|b| STANDARD.decode(b).unwrap()),
                body_json: e.get("body").cloned(),
            })
        });
        Some(Route {
            status: v.get("status").and_then(Value::as_u64).unwrap_or(200) as u16,
            headers,
            body,
            ws: v.get("ws").and_then(Value::as_bool).unwrap_or(false),
            ws_send: v
                .get("ws_send")
                .and_then(Value::as_array)
                .map(|a| a.iter().map(|t| t.to_string()).collect())
                .unwrap_or_default(),
            ws_echo_bytes: v
                .get("ws_echo_bytes")
                .and_then(Value::as_u64)
                .map(|n| (n as usize).max(1)),
            drop: v.get("drop").and_then(Value::as_bool).unwrap_or(false),
            authed: v.get("authed").and_then(Value::as_bool).unwrap_or(true),
            expect,
        })
    }
}

pub struct Stub {
    pub url: String,
    seen: Arc<Mutex<Vec<Seen>>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    route: Option<Route>,
}

/// Starts a stub answering every request with `route` (404 without one).
pub fn start(route: Option<Route>) -> Stub {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let url = format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port());
    let seen = Arc::new(Mutex::new(Vec::new()));
    let stop = Arc::new(AtomicBool::new(false));
    let thread = {
        let (seen, stop, route) = (seen.clone(), stop.clone(), route.clone());
        thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let (seen, route, stop) = (seen.clone(), route.clone(), stop.clone());
                        thread::spawn(move || serve(stream, &seen, route.as_ref(), &stop));
                    }
                    Err(_) => thread::sleep(Duration::from_millis(2)),
                }
            }
        })
    };
    Stub {
        url,
        seen,
        stop,
        thread: Some(thread),
        route,
    }
}

impl Stub {
    pub fn seen(&self) -> Vec<Seen> {
        self.seen.lock().unwrap().clone()
    }

    /// Waits for `count` requests (a background connect, for example).
    pub fn wait_for(&self, count: usize) -> bool {
        let until = Instant::now() + Duration::from_secs(5);
        while Instant::now() < until {
            if self.seen.lock().unwrap().len() >= count {
                return true;
            }
            thread::sleep(Duration::from_millis(5));
        }
        false
    }

    /// Compares what the client sent with the route's `expect`; the problems found.
    pub fn problems(&self, stem: &str) -> Vec<String> {
        let mut out = Vec::new();
        let seen = self.seen();
        let Some(route) = &self.route else {
            return out;
        };
        if route.authed {
            for s in &seen {
                if s.authorization.as_deref() != Some(BEARER) {
                    out.push(format!(
                        "{stem}: {} {} had no fixture bearer ({:?})",
                        s.method, s.path, s.authorization
                    ));
                }
            }
        }
        match &route.expect {
            None => {}
            Some(None) => {
                if !seen.is_empty() {
                    out.push(format!("{stem}: expected no request, got {seen:?}"));
                }
            }
            Some(Some(want)) => match seen.as_slice() {
                [one] => {
                    if one.method != want.method || one.path != want.path {
                        out.push(format!(
                            "{stem}: sent {} {}, expected {} {}",
                            one.method, one.path, want.method, want.path
                        ));
                    }
                    if let Some(json) = &want.body_json {
                        match serde_json::from_slice::<Value>(&one.body) {
                            Ok(got) if &got == json => {}
                            other => out.push(format!("{stem}: body {other:?}, expected {json}")),
                        }
                    }
                    if let Some(bytes) = &want.body
                        && bytes != &one.body
                    {
                        out.push(format!("{stem}: body bytes differ"));
                    }
                }
                other => out.push(format!("{stem}: expected one request, got {other:?}")),
            },
        }
        out
    }
}

impl Drop for Stub {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn head_end(bytes: &[u8]) -> Option<usize> {
    bytes
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|p| p + 4)
}

fn header(head: &str, name: &str) -> Option<String> {
    head.lines().skip(1).find_map(|line| {
        let (k, v) = line.split_once(':')?;
        k.trim()
            .eq_ignore_ascii_case(name)
            .then(|| v.trim().to_owned())
    })
}

fn serve(mut stream: TcpStream, seen: &Mutex<Vec<Seen>>, route: Option<&Route>, stop: &AtomicBool) {
    stream.set_nonblocking(false).unwrap();
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    // Peek the head so a WebSocket upgrade can still be handed to tungstenite untouched.
    let mut peeked = vec![0u8; 8192];
    let until = Instant::now() + Duration::from_secs(5);
    let end = loop {
        let n = stream.peek(&mut peeked).unwrap_or(0);
        if let Some(end) = head_end(&peeked[..n]) {
            break end;
        }
        if n == 0 && Instant::now() > until || Instant::now() > until {
            return;
        }
        thread::sleep(Duration::from_millis(1));
    };
    let head = String::from_utf8_lossy(&peeked[..end]).into_owned();
    let request_line = head.lines().next().unwrap_or("").to_owned();
    let mut parts = request_line.split(' ');
    let method = parts.next().unwrap_or("").to_owned();
    let path = parts.next().unwrap_or("").to_owned();
    let authorization = header(&head, "authorization");
    let upgrade = header(&head, "upgrade").is_some_and(|u| u.eq_ignore_ascii_case("websocket"));

    if upgrade && route.is_some_and(|r| r.ws) {
        seen.lock().unwrap().push(Seen {
            method,
            path,
            authorization,
            body: Vec::new(),
        });
        let Ok(mut socket) = tungstenite::accept(stream) else {
            return;
        };
        socket
            .get_mut()
            .set_read_timeout(Some(Duration::from_millis(50)))
            .ok();
        for text in route.map(|r| r.ws_send.clone()).unwrap_or_default() {
            if socket.send(tungstenite::Message::text(text)).is_err() {
                return;
            }
        }
        // Echo binary frames (the audio loopback) and hold the socket until the client or the test ends.
        let piece = route.and_then(|r| r.ws_echo_bytes);
        while !stop.load(Ordering::Relaxed) {
            match socket.read() {
                Ok(tungstenite::Message::Binary(bytes)) => {
                    let sent = bytes
                        .chunks(piece.unwrap_or(bytes.len().max(1)))
                        .all(|part| {
                            socket
                                .send(tungstenite::Message::Binary(part.to_vec().into()))
                                .is_ok()
                        });
                    if !sent {
                        break;
                    }
                }
                Ok(tungstenite::Message::Close(_)) | Err(tungstenite::Error::ConnectionClosed) => {
                    break;
                }
                Ok(_) => {}
                Err(tungstenite::Error::Io(e))
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                    ) => {}
                Err(_) => break,
            }
        }
        return;
    }

    // Plain HTTP: consume the head and the body.
    let mut raw = vec![0u8; end];
    stream.read_exact(&mut raw).unwrap();
    let length: usize = header(&head, "content-length")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let mut body = vec![0u8; length];
    if length > 0 {
        stream.read_exact(&mut body).unwrap();
    }
    seen.lock().unwrap().push(Seen {
        method,
        path,
        authorization,
        body,
    });
    let Some(route) = route else {
        let _ = stream
            .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return;
    };
    if route.drop {
        return;
    }
    let mut response = format!("HTTP/1.1 {} X\r\n", route.status);
    if !route
        .headers
        .iter()
        .any(|(k, _)| k.eq_ignore_ascii_case("content-type"))
    {
        response.push_str("Content-Type: application/json\r\n");
    }
    for (k, v) in &route.headers {
        response.push_str(&format!("{k}: {v}\r\n"));
    }
    response.push_str(&format!(
        "Content-Length: {}\r\nConnection: close\r\n\r\n",
        route.body.len()
    ));
    let _ = stream.write_all(response.as_bytes());
    let _ = stream.write_all(&route.body);
    let _ = stream.flush();
}
