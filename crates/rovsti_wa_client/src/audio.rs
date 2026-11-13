//! Call audio over a WebSocket: binary frames of `i16` samples, 16 kHz mono.
//! The microphone goes out in frames of 960 samples (1920 bytes, 60 ms). The
//! peer's voice comes in frames of 20 ms when the app asked for them
//! ([`SpeakerFrames::Step`], what [`call_audio_with`] is for) and the gateway
//! knows how; otherwise in whole 60 ms frames. The receiver takes both.
//!
//! Between the socket and the app sits one short queue per direction
//! ([`crate::queue`]): it drops the oldest audio, never the newest, and does
//! not let a delay stand. Every [`STATS_EVERY`] the queue depths are logged
//! and sent to the gateway as a text message (an older gateway ignores it).
//!
//! Reading and writing run side by side, so a write that waits on the network
//! never stops the peer's voice from being read, and the reverse. The client
//! pings every [`PING_EVERY`]; anything from the gateway (a frame, the pong)
//! proves the connection moves. When nothing arrives for [`STALL_LIMIT`] the
//! connection is stuck (a phone switching networks, a tunnel that stopped
//! forwarding): the socket is dropped and opened again, up to [`MAX_REOPENS`]
//! times in a row. The gateway hands the call's audio to the newest socket.
//! A close from the gateway (the call ended) or an error ends the audio.

use std::sync::Arc;
use std::sync::mpsc as std_mpsc;
use std::thread;
use std::time::Duration;

use futures::{SinkExt, StreamExt};
use rovsti_wa_proto::api::routes;
use rovsti_wa_proto::audio::{SpeakerFrames, decode_speaker_frame, encode_frame};
use rovsti_wa_proto::{ConnectKey, GatewayUrl};
use tokio::sync::oneshot;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{
    Connector, MaybeTlsStream, WebSocketStream, connect_async_tls_with_config,
};

use crate::error::{ClientError, from_ws};
use crate::events::{runtime, ws_request};
use crate::queue::{FrameQueue, Frames, MIC_LIMITS, Mic, QueueStats, SPEAKER_LIMITS, Speaker};
use crate::tls;

/// How often the queue depths are logged and reported to the gateway.
pub const STATS_EVERY: Duration = Duration::from_secs(10);
/// Longest stats line the gateway takes (it logs it).
pub const STATS_MAX_LEN: usize = 200;
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
    pub mic: Mic,
    /// Frames of the peer's voice. It ends when the call ends.
    pub speaker: Speaker,
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

/// Path (and query) of the audio socket for a call.
pub(crate) fn audio_path(call_id: &str, frames: SpeakerFrames) -> String {
    let path = routes::fill(routes::CALL_AUDIO, "call_id", call_id);
    match frames.query() {
        Some(query) => format!("{path}?{query}"),
        None => path,
    }
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

/// Opens the audio socket of `call_id` with whole 60 ms speaker frames, for a
/// player that takes nothing else. See [`call_audio_with`].
pub fn call_audio(
    url: GatewayUrl,
    key: ConnectKey,
    call_id: &str,
) -> Result<CallAudio, ClientError> {
    call_audio_with(url, key, call_id, SpeakerFrames::Whole)
}

/// Opens the audio socket of `call_id` and returns once it is connected, so a
/// rejected key or unknown call is an error here, not a silent stream.
///
/// `frames` is what the gateway is asked for. With [`SpeakerFrames::Step`]
/// the speaker yields 20 ms frames from a gateway that knows them and whole
/// 60 ms frames from an older one, so the player must take both.
pub fn call_audio_with(
    url: GatewayUrl,
    key: ConnectKey,
    call_id: &str,
    frames: SpeakerFrames,
) -> Result<CallAudio, ClientError> {
    let opener = Opener {
        connector: Connector::Rustls(tls::client_config()?),
        path: audio_path(call_id, frames),
        url,
        key,
    };
    // A bad URL or key fails here, before a thread starts.
    ws_request(&opener.url, &opener.path, &opener.key)?;

    let mic = FrameQueue::new(MIC_LIMITS);
    let speaker = FrameQueue::new(SPEAKER_LIMITS);
    let (mic_queue, speaker_queue) = (Arc::clone(&mic), Arc::clone(&speaker));
    let (shutdown, stop) = oneshot::channel();
    let (ready_tx, ready_rx) = std_mpsc::channel();

    thread::Builder::new()
        .name("wa-audio".into())
        .spawn(move || {
            // However the thread ends, both sides of the app see the end.
            let _closing = CloseOnDrop(&mic_queue, &speaker_queue);
            let (mic_queue, speaker_queue) = (&*mic_queue, &*speaker_queue);
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
                run(socket, &opener, mic_queue, speaker_queue, stop).await;
            });
        })
        .map_err(|e| ClientError::Ws(e.to_string()))?;

    ready_rx.recv().map_err(|_| ClientError::Closed)??;
    Ok(CallAudio {
        mic: Mic(mic),
        speaker: Speaker(speaker),
        _shutdown: Shutdown(Some(shutdown)),
    })
}

struct CloseOnDrop<'a>(&'a FrameQueue, &'a FrameQueue);

impl Drop for CloseOnDrop<'_> {
    fn drop(&mut self) {
        self.0.close();
        self.1.close();
    }
}

/// The queue depths of one stats period as one line, for the app's log and
/// the gateway's. Milliseconds of audio: `mean` and `max` are what a new
/// frame found waiting, `cut` is old audio dropped to get back to the target.
pub(crate) fn stats_line(speaker: QueueStats, mic: QueueStats) -> String {
    format!(
        "spk_q mean={}ms max={}ms cut={}ms frames={} | mic_q mean={}ms max={}ms cut={}ms frames={}",
        speaker.mean_ms,
        speaker.max_ms,
        speaker.dropped_ms,
        speaker.frames,
        mic.mean_ms,
        mic.max_ms,
        mic.dropped_ms,
        mic.frames,
    )
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
    mic: &FrameQueue,
    speaker: &FrameQueue,
    mut stop: oneshot::Receiver<()>,
) {
    let mut reopens = 0u32;
    loop {
        let mut heard = false;
        let ended = pump(socket, mic, speaker, &mut stop, STALL_LIMIT, &mut heard).await;
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
    mic: &FrameQueue,
    speaker: &FrameQueue,
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
        let mut stats = tokio::time::interval(STATS_EVERY);
        stats.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        stats.tick().await;
        let mut mic_frames = Frames(mic);
        loop {
            let message = tokio::select! {
                frame = mic_frames.next() => {
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
                _ = stats.tick() => {
                    let line = stats_line(speaker.take_stats(), mic.take_stats());
                    log::info!("wa audio: {line}");
                    Message::Text(line.into())
                }
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
                Message::Binary(bytes) => match decode_speaker_frame(&bytes) {
                    Ok(samples) => {
                        *heard = true;
                        // The queue drops its oldest audio when the player is
                        // behind. A closed one means nobody listens any more.
                        if speaker.push(samples).is_err() {
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
        let whole = SpeakerFrames::Whole;
        assert_eq!(audio_path("ABC123", whole), "/v1/calls/ABC123/audio");
        assert_eq!(audio_path("a/b", whole), "/v1/calls/a%2Fb/audio");
        // 20 ms frames are asked for in the query; an old gateway ignores it.
        assert_eq!(
            audio_path("ABC123", SpeakerFrames::Step),
            "/v1/calls/ABC123/audio?spk_ms=20"
        );
    }

    #[test]
    fn audio_request_has_bearer() {
        let url = GatewayUrl::parse("https://wa.example.com").unwrap();
        let key = ConnectKey::from_bytes(&[9; 32]);
        let req = ws_request(&url, &audio_path("X1", SpeakerFrames::Whole), &key).unwrap();
        assert_eq!(
            req.uri().to_string(),
            "wss://wa.example.com/v1/calls/X1/audio"
        );
        assert!(req.headers().contains_key("authorization"));
        let req = ws_request(&url, &audio_path("X1", SpeakerFrames::Step), &key).unwrap();
        assert_eq!(
            req.uri().to_string(),
            "wss://wa.example.com/v1/calls/X1/audio?spk_ms=20"
        );
    }

    use crate::queue::{Limits, RecvError};
    use rovsti_wa_proto::audio::{FRAME_SAMPLES, STEP_SAMPLES, encode_speaker_frame};
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

    /// A queue that never cuts, for tests about the socket itself.
    fn roomy() -> Arc<FrameQueue> {
        FrameQueue::new(Limits {
            high_ms: 60_000,
            target_ms: 60_000,
        })
    }

    /// The owner's call: the connection stopped carrying data both ways and
    /// nothing noticed. Now the pump notices within the stall limit, although
    /// its writes are stuck too.
    #[tokio::test]
    async fn a_connection_that_stops_moving_is_reported_as_stalled() {
        let (client, _gateway) = pipe(4_096).await;
        let (mic, speaker) = (roomy(), roomy());
        let (_shutdown, mut stop) = oneshot::channel();
        for i in 0..32 {
            mic.push(frame(i)).unwrap();
        }
        let mut heard = false;
        let started = std::time::Instant::now();
        let ended = pump(
            client,
            &mic,
            &speaker,
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
        let (mic, speaker) = (roomy(), roomy());
        let mut speaker_rx = Speaker(Arc::clone(&speaker));
        let (shutdown, mut stop) = oneshot::channel();
        for i in 0..32 {
            mic.push(frame(i)).unwrap();
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
                &mic,
                &speaker,
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
        let (mic, speaker) = (roomy(), roomy());
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
            &mic,
            &speaker,
            &mut stop,
            PING_EVERY + Duration::from_millis(500),
            &mut heard,
        )
        .await;
        answering.await.unwrap();
        assert_eq!(ended, Ended::Closed);
        assert!(started.elapsed() >= PING_EVERY * 3 - Duration::from_millis(100));
    }

    /// Runs the pump against a gateway that sends `messages` and closes;
    /// answers what reached the speaker queue.
    async fn heard_from(messages: Vec<Message>, speaker: Arc<FrameQueue>) -> Vec<Vec<i16>> {
        let (client, mut gateway) = pipe(1 << 20).await;
        let mic = roomy();
        let (_shutdown, mut stop) = oneshot::channel();
        let sending = tokio::spawn(async move {
            for message in messages {
                gateway.send(message).await.unwrap();
            }
            gateway.close(None).await.unwrap();
            // Let the client finish the close.
            while gateway.next().await.is_some() {}
        });
        let mut heard = false;
        let ended = pump(
            client,
            &mic,
            &speaker,
            &mut stop,
            Duration::from_secs(5),
            &mut heard,
        )
        .await;
        sending.await.unwrap();
        assert_eq!(ended, Ended::Closed);
        std::iter::from_fn(|| speaker.try_pop().ok()).collect()
    }

    fn binary(samples: &[i16]) -> Message {
        Message::Binary(encode_speaker_frame(samples).unwrap().into())
    }

    /// The new gateway sends 20 ms frames, an old one whole 60 ms frames:
    /// the same client plays both, sample for sample. Other lengths and
    /// text are skipped.
    #[tokio::test]
    async fn frames_of_20_ms_and_of_60_ms_both_reach_the_speaker() {
        let step: Vec<i16> = (0..STEP_SAMPLES as i16).collect();
        let whole: Vec<i16> = (0..FRAME_SAMPLES as i16).map(|n| -n).collect();
        let heard = heard_from(
            vec![
                binary(&step),
                binary(&whole),
                Message::Binary(vec![0u8; 100].into()),
                Message::Text("hello".into()),
                binary(&step[..]),
                binary(&whole[..2 * STEP_SAMPLES]),
            ],
            roomy(),
        )
        .await;
        assert_eq!(
            heard,
            vec![
                step.clone(),
                whole.clone(),
                step,
                whole[..2 * STEP_SAMPLES].to_vec()
            ]
        );
    }

    /// A burst after a stall (half a second of 20 ms frames at once) leaves
    /// only the newest audio in the speaker queue, not the oldest.
    #[tokio::test]
    async fn a_burst_from_the_socket_leaves_the_newest_frames_for_the_speaker() {
        let burst: Vec<Message> = (0..25).map(|i| binary(&[i; STEP_SAMPLES])).collect();
        let speaker = FrameQueue::new(SPEAKER_LIMITS);
        let heard = heard_from(burst, Arc::clone(&speaker)).await;
        let values: Vec<i16> = heard.iter().map(|f| f[0]).collect();
        assert!(values.len() <= 6, "{values:?}");
        assert_eq!(values.last(), Some(&24));
        assert!(values.windows(2).all(|w| w[1] == w[0] + 1), "{values:?}");
        assert_eq!(speaker.try_pop(), Err(RecvError::Empty));
    }

    #[test]
    fn the_stats_line_names_both_queues() {
        let speaker = FrameQueue::new(SPEAKER_LIMITS);
        let mic = FrameQueue::new(MIC_LIMITS);
        for i in 0..7 {
            speaker.push(vec![i; STEP_SAMPLES]).unwrap();
        }
        mic.push(frame(1)).unwrap();
        let line = stats_line(speaker.take_stats(), mic.take_stats());
        assert_eq!(
            line,
            "spk_q mean=60ms max=120ms cut=100ms frames=7 | mic_q mean=0ms max=0ms cut=0ms frames=1"
        );
        assert!(line.len() <= STATS_MAX_LEN);
        // The longest it can get still fits what the gateway logs.
        let big = QueueStats {
            frames: u64::MAX,
            mean_ms: 99_999,
            max_ms: 99_999,
            dropped_ms: 9_999_999,
            now_ms: 0,
        };
        assert!(stats_line(big, big).len() <= STATS_MAX_LEN);
    }
}
