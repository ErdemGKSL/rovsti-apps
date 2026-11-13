//! Call audio over a WebSocket: binary frames of 960 `i16` samples (1920
//! bytes, 16 kHz mono, 60 ms) in both directions.
//!
//! Reading and writing run side by side, so a write that waits on the network
//! never stops the peer's voice from being read, and the reverse. The client
//! pings every [`PING_EVERY`]; anything from the gateway (a frame, the pong)
//! proves the connection moves. When nothing arrives for [`STALL_LIMIT`] the
//! connection is stuck (a phone switching networks, a tunnel that stopped
//! forwarding): the socket is dropped and opened again, up to [`MAX_REOPENS`]
//! times in a row. The gateway hands the call's audio to the newest socket.
//! A close from the gateway (the call ended) or an error ends the audio.

use std::sync::mpsc as std_mpsc;
use std::thread;
use std::time::Duration;

use futures::channel::mpsc;
use futures::{SinkExt, StreamExt};
use rovsti_wa_proto::api::routes;
use rovsti_wa_proto::audio::{decode_frame, encode_frame};
use rovsti_wa_proto::{ConnectKey, GatewayUrl};
use tokio::sync::oneshot;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{
    Connector, MaybeTlsStream, WebSocketStream, connect_async_tls_with_config,
};

use crate::error::{ClientError, from_ws};
use crate::events::{runtime, ws_request};
use crate::tls;

/// Frames buffered per direction (about half a second of audio).
const QUEUE_FRAMES: usize = 8;
/// How often the client pings, so a quiet but healthy socket still answers.
pub const PING_EVERY: Duration = Duration::from_secs(1);
/// Nothing from the gateway for this long: the connection is stuck.
pub const STALL_LIMIT: Duration = Duration::from_secs(3);
/// Reopens in a row (without a frame in between) before the audio ends.
pub const MAX_REOPENS: u32 = 5;
/// Wait before a reopen; doubled after each failed one.
const REOPEN_DELAY: Duration = Duration::from_millis(250);
/// How long a polite close may take before the socket is just dropped.
const CLOSE_WAIT: Duration = Duration::from_secs(1);

type Socket = WebSocketStream<MaybeTlsStream<tokio::net::TcpStream>>;

/// The two audio directions of a call. Dropping it closes the socket.
#[derive(Debug)]
pub struct CallAudio {
    /// Microphone frames to send to the peer. Each must hold 960 samples.
    pub mic: mpsc::Sender<Vec<i16>>,
    /// Frames of the peer's voice. The receiver ends when the call ends.
    pub speaker: mpsc::Receiver<Vec<i16>>,
    _shutdown: Shutdown,
}

#[derive(Debug)]
struct Shutdown(Option<oneshot::Sender<()>>);

impl Drop for Shutdown {
    fn drop(&mut self) {
        if let Some(tx) = self.0.take() {
            let _ = tx.send(());
        }
    }
}

/// Path of the audio socket for a call.
pub(crate) fn audio_path(call_id: &str) -> String {
    routes::fill(routes::CALL_AUDIO, "call_id", call_id)
}

/// Where and how to (re)open one call's audio socket.
struct Opener {
    url: GatewayUrl,
    key: ConnectKey,
    path: String,
    connector: Connector,
}

impl Opener {
    async fn open(&self) -> Result<Socket, ClientError> {
        let request = ws_request(&self.url, &self.path, &self.key)?;
        connect_async_tls_with_config(request, None, true, Some(self.connector.clone()))
            .await
            .map(|(socket, _)| socket)
            .map_err(from_ws)
    }
}

/// Opens the audio socket of `call_id` and returns once it is connected, so a
/// rejected key or unknown call is an error here, not a silent stream.
pub fn call_audio(
    url: GatewayUrl,
    key: ConnectKey,
    call_id: &str,
) -> Result<CallAudio, ClientError> {
    let opener = Opener {
        connector: Connector::Rustls(tls::client_config()?),
        path: audio_path(call_id),
        url,
        key,
    };
    // A bad URL or key fails here, before a thread starts.
    ws_request(&opener.url, &opener.path, &opener.key)?;

    let (mic, mic_rx) = mpsc::channel(QUEUE_FRAMES);
    let (speaker_tx, speaker) = mpsc::channel(QUEUE_FRAMES);
    let (shutdown, stop) = oneshot::channel();
    let (ready_tx, ready_rx) = std_mpsc::channel();

    thread::Builder::new()
        .name("wa-audio".into())
        .spawn(move || {
            let runtime = match runtime() {
                Ok(runtime) => runtime,
                Err(error) => {
                    let _ = ready_tx.send(Err(ClientError::Ws(error.to_string())));
                    return;
                }
            };
            runtime.block_on(async move {
                let socket = match opener.open().await {
                    Ok(socket) => socket,
                    Err(error) => {
                        let _ = ready_tx.send(Err(error));
                        return;
                    }
                };
                let _ = ready_tx.send(Ok(()));
                run(socket, &opener, mic_rx, speaker_tx, stop).await;
            });
        })
        .map_err(|e| ClientError::Ws(e.to_string()))?;

    ready_rx.recv().map_err(|_| ClientError::Closed)??;
    Ok(CallAudio {
        mic,
        speaker,
        _shutdown: Shutdown(Some(shutdown)),
    })
}

/// How one socket ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Ended {
    /// The app closed the audio (or stopped listening).
    Stopped,
    /// The gateway closed it, or the connection failed: the audio ends.
    Closed,
    /// Nothing arrived for [`STALL_LIMIT`]: open the socket again.
    Stalled,
}

/// Shuttles audio over `socket`, and over a reopened one after each stall,
/// until the app stops, the gateway closes or reopening fails.
async fn run(
    mut socket: Socket,
    opener: &Opener,
    mut mic: mpsc::Receiver<Vec<i16>>,
    mut speaker: mpsc::Sender<Vec<i16>>,
    mut stop: oneshot::Receiver<()>,
) {
    let mut reopens = 0u32;
    loop {
        let mut heard = false;
        let ended = pump(
            socket,
            &mut mic,
            &mut speaker,
            &mut stop,
            STALL_LIMIT,
            &mut heard,
        )
        .await;
        if heard {
            reopens = 0;
        }
        if ended != Ended::Stalled {
            return;
        }
        socket = loop {
            if reopens >= MAX_REOPENS {
                log::warn!("wa audio: the socket stalled {reopens} times in a row; giving up");
                return;
            }
            let delay = REOPEN_DELAY * 2u32.pow(reopens);
            reopens += 1;
            log::warn!(
                "wa audio: no data for {STALL_LIMIT:?}; reopening the socket (try {reopens})"
            );
            tokio::select! {
                _ = &mut stop => return,
                () = tokio::time::sleep(delay) => {}
            }
            match opener.open().await {
                Ok(socket) => break socket,
                // The gateway answered: the call is over or the key is gone.
                Err(error @ (ClientError::Unauthorized | ClientError::Api(_))) => {
                    log::warn!("wa audio: reopening refused: {error}");
                    return;
                }
                Err(error) => log::warn!("wa audio: reopening failed: {error}"),
            }
        };
    }
}

/// Moves frames both ways over one socket until it ends. Reading and writing
/// run side by side; `stall` without any message from the gateway ends it as
/// [`Ended::Stalled`]. `heard` turns true once a frame arrived.
pub(crate) async fn pump<S>(
    socket: WebSocketStream<S>,
    mic: &mut mpsc::Receiver<Vec<i16>>,
    speaker: &mut mpsc::Sender<Vec<i16>>,
    stop: &mut oneshot::Receiver<()>,
    stall: Duration,
    heard: &mut bool,
) -> Ended
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let (mut sink, mut stream) = socket.split();
    let writer = async {
        let mut ping = tokio::time::interval(PING_EVERY);
        ping.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        ping.tick().await;
        loop {
            let message = tokio::select! {
                frame = mic.next() => {
                    let Some(samples) = frame else { return Ended::Stopped };
                    match encode_frame(&samples) {
                        Ok(bytes) => Message::Binary(bytes.into()),
                        Err(error) => {
                            log::warn!("wa audio: dropping mic frame: {error}");
                            continue;
                        }
                    }
                }
                _ = ping.tick() => Message::Ping(Vec::new().into()),
            };
            if sink.send(message).await.is_err() {
                return Ended::Closed;
            }
        }
    };
    let reader = async {
        loop {
            let message = match tokio::time::timeout(stall, stream.next()).await {
                Err(_) => return Ended::Stalled,
                Ok(Some(Ok(message))) => message,
                Ok(Some(Err(_)) | None) => return Ended::Closed,
            };
            match message {
                Message::Binary(bytes) => match decode_frame(&bytes) {
                    Ok(samples) => {
                        *heard = true;
                        // A full queue means the player is behind: drop the
                        // frame rather than add latency. A closed one means
                        // nobody listens any more.
                        if let Err(error) = speaker.try_send(samples)
                            && error.is_disconnected()
                        {
                            return Ended::Stopped;
                        }
                    }
                    Err(error) => log::warn!("wa audio: dropping frame: {error}"),
                },
                Message::Close(_) => return Ended::Closed,
                // Pings are answered by tungstenite; a pong only proves the
                // connection moves.
                _ => {}
            }
        }
    };
    let ended = tokio::select! {
        _ = stop => Ended::Stopped,
        ended = writer => ended,
        ended = reader => ended,
    };
    // A stalled connection may never finish a close; the others get a short one.
    if ended != Ended::Stalled
        && let Ok(mut socket) = sink.reunite(stream)
    {
        let _ = tokio::time::timeout(CLOSE_WAIT, socket.close(None)).await;
    }
    ended
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn audio_path_fills_the_call_id() {
        assert_eq!(audio_path("ABC123"), "/v1/calls/ABC123/audio");
        assert_eq!(audio_path("a/b"), "/v1/calls/a%2Fb/audio");
    }

    #[test]
    fn audio_request_has_bearer() {
        let url = GatewayUrl::parse("https://wa.example.com").unwrap();
        let key = ConnectKey::from_bytes(&[9; 32]);
        let req = ws_request(&url, &audio_path("X1"), &key).unwrap();
        assert_eq!(
            req.uri().to_string(),
            "wss://wa.example.com/v1/calls/X1/audio"
        );
        assert!(req.headers().contains_key("authorization"));
    }

    use rovsti_wa_proto::audio::FRAME_SAMPLES;
    use tokio_tungstenite::tungstenite::protocol::Role;

    /// A client socket and the gateway's end over an in-memory pipe of
    /// `buffer` bytes; a small buffer behaves like a TCP connection that
    /// stopped moving once it is full.
    async fn pipe(
        buffer: usize,
    ) -> (
        WebSocketStream<tokio::io::DuplexStream>,
        WebSocketStream<tokio::io::DuplexStream>,
    ) {
        let (a, b) = tokio::io::duplex(buffer);
        let client = WebSocketStream::from_raw_socket(a, Role::Client, None).await;
        let gateway = WebSocketStream::from_raw_socket(b, Role::Server, None).await;
        (client, gateway)
    }

    fn frame(value: i16) -> Vec<i16> {
        vec![value; FRAME_SAMPLES]
    }

    /// The owner's call: the connection stopped carrying data both ways and
    /// nothing noticed. Now the pump notices within the stall limit, although
    /// its writes are stuck too.
    #[tokio::test]
    async fn a_connection_that_stops_moving_is_reported_as_stalled() {
        let (client, _gateway) = pipe(4_096).await;
        let (mut mic_tx, mut mic) = mpsc::channel(64);
        let (mut speaker, _speaker_rx) = mpsc::channel(8);
        let (_shutdown, mut stop) = oneshot::channel();
        for i in 0..32 {
            mic_tx.try_send(frame(i)).unwrap();
        }
        let mut heard = false;
        let started = std::time::Instant::now();
        let ended = pump(
            client,
            &mut mic,
            &mut speaker,
            &mut stop,
            Duration::from_millis(200),
            &mut heard,
        )
        .await;
        assert_eq!(ended, Ended::Stalled);
        assert!(!heard);
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    /// The gateway sends the peer's voice but reads nothing (its side of the
    /// connection is full): the speaker still gets every frame, because
    /// reading no longer waits for a stuck microphone write.
    #[tokio::test]
    async fn the_speaker_keeps_flowing_while_microphone_writes_are_stuck() {
        let (client, mut gateway) = pipe(8_192).await;
        let (mut mic_tx, mut mic) = mpsc::channel(64);
        let (mut speaker, mut speaker_rx) = mpsc::channel(64);
        let (shutdown, mut stop) = oneshot::channel();
        for i in 0..32 {
            mic_tx.try_send(frame(i)).unwrap();
        }
        let sender = tokio::spawn(async move {
            for i in 0..20 {
                let bytes = encode_frame(&frame(100 + i)).unwrap();
                gateway.send(Message::Binary(bytes.into())).await.unwrap();
            }
            gateway
        });
        let pumping = tokio::spawn(async move {
            let mut heard = false;
            let ended = pump(
                client,
                &mut mic,
                &mut speaker,
                &mut stop,
                Duration::from_secs(5),
                &mut heard,
            )
            .await;
            (ended, heard)
        });
        let mut got = Vec::new();
        while got.len() < 20 {
            let next = tokio::time::timeout(Duration::from_secs(5), speaker_rx.next())
                .await
                .expect("the speaker starved")
                .unwrap();
            got.push(next[0]);
        }
        assert_eq!(got, (100..120).collect::<Vec<i16>>());
        let _gateway = sender.await.unwrap();
        drop(shutdown);
        let (ended, heard) = pumping.await.unwrap();
        assert_eq!(ended, Ended::Stopped);
        assert!(heard);
    }

    /// Pongs keep a quiet connection alive; a close from the gateway ends it.
    #[tokio::test]
    async fn pongs_keep_a_quiet_socket_alive_and_a_close_ends_it() {
        let (client, mut gateway) = pipe(65_536).await;
        let (_mic_tx, mut mic) = mpsc::channel(8);
        let (mut speaker, _speaker_rx) = mpsc::channel(8);
        let (_shutdown, mut stop) = oneshot::channel();
        let answering = tokio::spawn(async move {
            // Reading answers the client's pings (tungstenite queues the pong).
            let until = tokio::time::Instant::now() + PING_EVERY * 3;
            while let Ok(Some(Ok(_))) = tokio::time::timeout_at(until, gateway.next()).await {}
            gateway.close(None).await.unwrap();
        });
        let mut heard = false;
        let started = std::time::Instant::now();
        let ended = pump(
            client,
            &mut mic,
            &mut speaker,
            &mut stop,
            PING_EVERY + Duration::from_millis(500),
            &mut heard,
        )
        .await;
        answering.await.unwrap();
        assert_eq!(ended, Ended::Closed);
        assert!(started.elapsed() >= PING_EVERY * 3 - Duration::from_millis(100));
    }
}
