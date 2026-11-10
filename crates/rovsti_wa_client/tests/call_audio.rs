#![allow(clippy::unwrap_used)]

//! Call audio against an in-process fake gateway: a plain WebSocket server on
//! loopback that pushes a 1 kHz tone to the app and checks the tone the app
//! sends back. Proves the frame size (960 samples, 1 920 bytes) and the
//! samples in both directions, with no gateway crate involved.

use std::thread;
use std::time::Duration;

use futures::{SinkExt, StreamExt};
use rovsti_wa_client::call_audio;
use rovsti_wa_proto::audio::{FRAME_BYTES, FRAME_SAMPLES, SAMPLE_RATE, decode_frame, encode_frame};
use rovsti_wa_proto::{ConnectKey, GatewayUrl};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::Message;

const TONE_HZ: f64 = 1_000.0;
const OTHER_HZ: f64 = 2_300.0;
const FRAMES: usize = 6;

/// `FRAME_SAMPLES` of a sine at `hz` starting at sample `offset`, amplitude 12 000.
fn tone(hz: f64, offset: usize) -> Vec<i16> {
    (0..FRAME_SAMPLES)
        .map(|n| {
            let t = (offset + n) as f64 / f64::from(SAMPLE_RATE);
            (12_000.0 * (2.0 * std::f64::consts::PI * hz * t).sin()) as i16
        })
        .collect()
}

/// Signal power at `hz` (Goertzel), normalised so a full-scale sine of that
/// frequency gives about `amplitude^2 / 4`.
fn goertzel(samples: &[i16], hz: f64) -> f64 {
    let n = samples.len() as f64;
    let k = (n * hz / f64::from(SAMPLE_RATE)).round();
    let w = 2.0 * std::f64::consts::PI * k / n;
    let coeff = 2.0 * w.cos();
    let (mut s1, mut s2) = (0.0f64, 0.0f64);
    for &x in samples {
        let s = f64::from(x) + coeff * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (n * n)
}

/// True when `hz` carries the energy and `other` almost none.
fn is_tone(samples: &[i16], hz: f64, other: f64) -> bool {
    let wanted = goertzel(samples, hz);
    // 12 000 amplitude: (A/2)^2 = 3.6e7. Allow a little for rounding.
    wanted > 3.0e7 && goertzel(samples, other) < wanted / 1_000.0
}

/// What the fake gateway saw from the app.
struct Seen {
    path: String,
    authorization: Option<String>,
    sizes: Vec<usize>,
    mic_frames: Vec<Vec<i16>>,
}

/// Serves one audio socket: sends `FRAMES` frames of `TONE_HZ` at 60 ms
/// intervals (scaled down) and records what the app sends.
fn fake_gateway() -> (GatewayUrl, thread::JoinHandle<Seen>) {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let listener = runtime.block_on(TcpListener::bind("127.0.0.1:0")).unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = thread::spawn(move || {
        runtime.block_on(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut path = String::new();
            let mut authorization = None;
            let mut socket = tokio_tungstenite::accept_hdr_async(
                stream,
                |request: &tokio_tungstenite::tungstenite::handshake::server::Request, response| {
                    path = request.uri().path().to_owned();
                    authorization = request
                        .headers()
                        .get("authorization")
                        .map(|v| v.to_str().unwrap().to_owned());
                    Ok(response)
                },
            )
            .await
            .unwrap();
            let mut seen = Seen {
                path: String::new(),
                authorization: None,
                sizes: Vec::new(),
                mic_frames: Vec::new(),
            };
            let mut sent = 0;
            let mut tick = tokio::time::interval(Duration::from_millis(15));
            while seen.mic_frames.len() < FRAMES || sent < FRAMES {
                tokio::select! {
                    _ = tick.tick(), if sent < FRAMES => {
                        let bytes = encode_frame(&tone(TONE_HZ, sent * FRAME_SAMPLES)).unwrap();
                        assert_eq!(bytes.len(), FRAME_BYTES);
                        socket.send(Message::Binary(bytes.into())).await.unwrap();
                        sent += 1;
                    }
                    message = socket.next() => match message {
                        Some(Ok(Message::Binary(bytes))) => {
                            seen.sizes.push(bytes.len());
                            if let Ok(samples) = decode_frame(&bytes) {
                                seen.mic_frames.push(samples);
                            }
                        }
                        Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                        Some(Ok(_)) => {}
                    },
                }
            }
            seen.path = path;
            seen.authorization = authorization;
            seen
        })
    });
    let url = GatewayUrl::parse(&format!("http://127.0.0.1:{port}")).unwrap();
    (url, handle)
}

#[test]
fn a_1_khz_tone_crosses_the_audio_socket_both_ways() {
    let (url, server) = fake_gateway();
    let key = ConnectKey::from_bytes(&[3; 32]);
    let mut audio = call_audio(url, key.clone(), "CALL1").unwrap();

    // App to gateway: the microphone side sends a 1 kHz tone, one frame at a time.
    let sent: Vec<Vec<i16>> = (0..FRAMES)
        .map(|i| tone(TONE_HZ, i * FRAME_SAMPLES))
        .collect();
    for frame in &sent {
        futures::executor::block_on(audio.mic.send(frame.clone())).unwrap();
        thread::sleep(Duration::from_millis(15));
    }

    // Gateway to app: six 960-sample frames of 1 kHz arrive on the speaker.
    let mut heard = Vec::new();
    for _ in 0..FRAMES {
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        let next = loop {
            match audio.speaker.try_recv() {
                Ok(frame) => break frame,
                Err(futures::channel::mpsc::TryRecvError::Closed) => panic!("audio ended early"),
                Err(_) => {
                    assert!(
                        std::time::Instant::now() < deadline,
                        "timed out waiting for a speaker frame"
                    );
                    thread::sleep(Duration::from_millis(5));
                }
            }
        };
        heard.push(next);
    }
    drop(audio);

    for (i, frame) in heard.iter().enumerate() {
        assert_eq!(frame.len(), FRAME_SAMPLES, "speaker frame {i}");
        assert!(
            is_tone(frame, TONE_HZ, OTHER_HZ),
            "speaker frame {i} is not 1 kHz"
        );
    }
    assert_eq!(
        heard[0],
        tone(TONE_HZ, 0),
        "samples survive the wire unchanged"
    );

    let seen = server.join().unwrap();
    assert_eq!(seen.path, "/v1/calls/CALL1/audio");
    assert_eq!(
        seen.authorization.as_deref(),
        Some(format!("Bearer {}", key.as_str()).as_str())
    );
    assert_eq!(
        seen.sizes,
        vec![FRAME_BYTES; FRAMES],
        "binary frames of 1 920 bytes"
    );
    for (i, frame) in seen.mic_frames.iter().enumerate() {
        assert!(
            is_tone(frame, TONE_HZ, OTHER_HZ),
            "mic frame {i} is not 1 kHz"
        );
    }
    assert_eq!(seen.mic_frames, sent);
}

#[test]
fn the_goertzel_check_tells_tones_apart() {
    assert!(is_tone(&tone(TONE_HZ, 0), TONE_HZ, OTHER_HZ));
    assert!(!is_tone(&tone(OTHER_HZ, 0), TONE_HZ, OTHER_HZ));
    assert!(!is_tone(&vec![0; FRAME_SAMPLES], TONE_HZ, OTHER_HZ));
}
