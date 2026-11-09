#![allow(clippy::unwrap_used, clippy::expect_used)]

//! E2.14 through the public API with a loopback gateway: the event stream turns every socket message into a
//! `wa.event`, and call audio frames loop through the socket unchanged (the stub echoes binary frames, like a
//! peer that repeats what it hears).

mod support;

use rovsti_ffi::envelope::{Answer, decode};
use rovsti_ffi::events::{self, EventSink};
use rovsti_ffi::handles;
use rovsti_ffi::ops::{audio_pull, audio_push, call};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use support::{Route, start};

const KEY: &str = "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU";

/// The sink is process wide; the two tests that use it run one at a time.
static SERIAL: Mutex<()> = Mutex::new(());

#[derive(Default)]
struct Collect(Mutex<Vec<(String, Value)>>);

impl EventSink for Collect {
    fn emit(&self, kind: &str, json: &[u8]) {
        self.0
            .lock()
            .unwrap()
            .push((kind.to_owned(), serde_json::from_slice(json).unwrap()));
    }
}

fn ok(op: &str, request: Value) -> Value {
    match decode(&call(op, request.to_string().as_bytes())).unwrap() {
        Answer::Ok(value) => value,
        Answer::Err(error) => panic!("{op}: {error:?}"),
    }
}

fn open(url: &str) -> i64 {
    ok("wa.open", json!({"url": url, "key": KEY}))["handle"]
        .as_i64()
        .unwrap()
}

fn route(extra: Value) -> Route {
    static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!("rovsti-wa-live-{}-{n}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("r.json"), extra.to_string()).unwrap();
    Route::load(&dir, "r").unwrap()
}

fn wait_until(what: &str, mut done: impl FnMut() -> bool) {
    let until = Instant::now() + Duration::from_secs(10);
    while Instant::now() < until {
        if done() {
            return;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    panic!("timed out waiting for {what}");
}

#[test]
fn socket_messages_arrive_as_wa_events_in_order() {
    let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let envelope = |seq: u64, text: &str| {
        json!({"seq": seq, "at_ms": 1_790_000_000_000_i64 + seq as i64,
               "event": {"type": "message", "id": format!("m{seq}"), "chat": "1@s.whatsapp.net",
                         "sender": null, "from_me": false, "timestamp_ms": 5,
                         "body": {"text": text}, "reactions": []}})
    };
    let stub = start(Some(route(json!({
        "ws": true,
        "ws_send": [envelope(1, "one"), envelope(2, "two"), {"seq": 3, "at_ms": 9, "event": {"type": "resync"}}],
    }))));
    let sink = Arc::new(Collect::default());
    events::set_sink(Some(sink.clone()));

    let gateway = open(&stub.url);
    let stream = ok(
        "wa.events_start",
        json!({"handle": gateway, "instance": "inst1", "after": 0}),
    )["handle"]
        .as_i64()
        .unwrap();
    wait_until("four events", || sink.0.lock().unwrap().len() >= 4);

    let got = sink.0.lock().unwrap().clone();
    let kinds: Vec<&str> = got
        .iter()
        .map(|(_, v)| v["event"]["kind"].as_str().unwrap())
        .collect();
    assert_eq!(kinds, ["connected", "event", "event", "event"]);
    assert!(
        got.iter()
            .all(|(kind, v)| kind == "wa.event" && v["instance"] == "inst1")
    );
    let seqs: Vec<u64> = got[1..]
        .iter()
        .map(|(_, v)| v["event"]["seq"].as_u64().unwrap())
        .collect();
    assert_eq!(seqs, [1, 2, 3]);
    assert_eq!(got[1].1["event"]["event"]["body"]["text"], "one");
    assert_eq!(got[3].1["event"]["event"]["type"], "resync");
    let request = &stub.seen()[0];
    assert_eq!(request.path, "/v1/events?after=0");
    assert_eq!(request.authorization.as_deref(), Some(support::BEARER));

    // Reconnect-now on a live stream is accepted; release stops it and the handle is gone.
    ok("wa.events_reconnect_now", json!({"handle": stream}));
    assert!(handles::global().release(stream));
    let answer = decode(&call(
        "wa.events_reconnect_now",
        json!({"handle": stream}).to_string().as_bytes(),
    ))
    .unwrap();
    assert!(
        matches!(answer, Answer::Err(e) if e.kind == rovsti_ffi::envelope::ErrorKind::NotFound)
    );
    events::set_sink(None);
    handles::global().release(gateway);
}

#[test]
fn a_revoked_key_ends_the_stream_with_unauthorized() {
    let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let stub = start(Some(route(json!({
        "status": 401, "body": {"code": "unauthorized", "message": "revoked"}
    }))));
    let sink = Arc::new(Collect::default());
    events::set_sink(Some(sink.clone()));
    let gateway = open(&stub.url);
    let stream = ok(
        "wa.events_start",
        json!({"handle": gateway, "instance": "i"}),
    )["handle"]
        .as_i64()
        .unwrap();
    wait_until("unauthorized", || !sink.0.lock().unwrap().is_empty());
    assert_eq!(sink.0.lock().unwrap()[0].1["event"]["kind"], "unauthorized");
    handles::global().release(stream);
    handles::global().release(gateway);
    events::set_sink(None);
}

fn frame(seed: i16) -> Vec<u8> {
    (0..960_i16)
        .flat_map(|n| seed.wrapping_mul(31).wrapping_add(n * 7).to_le_bytes())
        .collect()
}

#[test]
fn audio_frames_loop_through_the_socket_unchanged() {
    let stub = start(Some(route(json!({"ws": true}))));
    let gateway = open(&stub.url);
    let audio = ok(
        "wa.call_audio_open",
        json!({"handle": gateway, "call_id": "call1"}),
    )["handle"]
        .as_i64()
        .unwrap();
    assert_eq!(stub.seen()[0].path, "/v1/calls/call1/audio");

    // Wrong sizes are dropped: nothing comes back for them.
    audio_push(audio, &[1, 2, 3]);
    audio_push(audio, &vec![0; 1921]);
    let sent: Vec<Vec<u8>> = (0..6).map(|n| frame(n + 1)).collect();
    for pcm in &sent {
        audio_push(audio, pcm);
        // The queue holds half a second; pace the pushes like the 60 ms microphone does.
        std::thread::sleep(Duration::from_millis(5));
    }
    let mut received: Vec<Vec<u8>> = Vec::new();
    let mut out = vec![0u8; 1920];
    wait_until("six frames", || {
        loop {
            match audio_pull(audio, &mut out) {
                1920 => received.push(out.clone()),
                0 => break,
                other => panic!("pull answered {other}"),
            }
        }
        received.len() >= sent.len()
    });
    assert_eq!(
        received, sent,
        "N frames in, N identical frames out, in order"
    );

    // A buffer smaller than a frame is refused without consuming anything.
    assert_eq!(audio_pull(audio, &mut [0u8; 100]), 0);

    // Released: the handle ends.
    assert!(handles::global().release(audio));
    assert_eq!(audio_pull(audio, &mut out), -1);
    audio_push(audio, &sent[0]);
    handles::global().release(gateway);
}

#[test]
fn a_call_that_ends_answers_minus_one() {
    let stub = start(Some(route(json!({"ws": true}))));
    let gateway = open(&stub.url);
    let audio = ok(
        "wa.call_audio_open",
        json!({"handle": gateway, "call_id": "call1"}),
    )["handle"]
        .as_i64()
        .unwrap();
    // Dropping the stub stops its sockets: the client sees the close and the speaker side ends.
    drop(stub);
    let mut out = vec![0u8; 1920];
    wait_until("the call to end", || audio_pull(audio, &mut out) == -1);
    handles::global().release(audio);
    handles::global().release(gateway);
}
