//! The resumable event WebSocket.
//!
//! [`subscribe`] runs a background thread with a one-worker Tokio runtime. It
//! connects, forwards every [`EventEnvelope`], remembers the last `seq` and,
//! when the socket drops, reconnects with backoff and jitter, resuming after
//! that `seq`. A rejected key stops the stream for good.
//!
//! Sequences start over when the gateway restarts. The gateway names its
//! process in the `hello` frame that opens every socket and in `resync`
//! (`epoch`); both are forwarded like any event, and the stream moves its
//! own resume point back when they say the old one is void ([`Cursor`]). The
//! consumer keeps the epoch next to its own last `seq` and refetches when a
//! frame names another one.

use std::io;
use std::thread;
use std::time::Duration;

use futures::channel::mpsc;
use futures::{FutureExt, StreamExt};
use rosty_wa_proto::api::routes;
use rosty_wa_proto::limit::backoff_ms;
use rosty_wa_proto::{ConnectKey, EventEnvelope, GatewayEvent, GatewayUrl};
use std::sync::Arc;

use tokio::sync::{Notify, oneshot};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::header::AUTHORIZATION;
use tokio_tungstenite::tungstenite::{Message, handshake::client::Request};
use tokio_tungstenite::{Connector, connect_async_tls_with_config};

use crate::error::{ClientError, from_ws};
use crate::{http, tls};

/// What the stream tells its consumer.
// Nearly every value is an `Event`, so boxing it would add an allocation per
// event and save nothing.
#[allow(clippy::large_enum_variant)]
#[derive(Clone, Debug, PartialEq)]
pub enum ClientEvent {
    /// The socket is open; events follow.
    Connected,
    Event(EventEnvelope),
    /// The socket dropped; the stream reconnects on its own.
    Disconnected(String),
    /// The key was revoked or is wrong. The stream has stopped.
    Unauthorized,
}

/// Handle of a running subscription. Dropping it stops the thread.
#[derive(Debug)]
pub struct EventStream {
    shutdown: Option<oneshot::Sender<()>>,
    kick: Arc<Notify>,
}

impl EventStream {
    /// Ends the backoff wait and reconnects at once (a push says the gateway
    /// has news). Has no effect while the socket is connected.
    pub fn reconnect_now(&self) {
        self.kick.notify_one();
    }

    pub fn stop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
    }
}

impl Drop for EventStream {
    fn drop(&mut self) {
        self.stop();
    }
}

const PING_EVERY: Duration = Duration::from_secs(25);
const IDLE_LIMIT: Duration = Duration::from_secs(90);

/// The envelope in one text frame. `None` for a frame this build cannot
/// read, which is how an event kind from a newer gateway is skipped: the
/// stream logs it and goes on with the next frame.
pub(crate) fn decode_event(text: &str) -> Option<EventEnvelope> {
    match serde_json::from_str::<EventEnvelope>(text) {
        Ok(envelope) => Some(envelope),
        Err(error) => {
            log::warn!("wa events: undecodable event: {error}");
            None
        }
    }
}

/// Where the stream resumes after a drop: the last `seq` it saw and the
/// gateway process that numbered it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Cursor {
    pub last: Option<u64>,
    epoch: Option<String>,
}

impl Cursor {
    pub(crate) fn new(last: Option<u64>) -> Self {
        Self { last, epoch: None }
    }

    /// Takes one frame into account. Normally the resume point only moves
    /// forward. A `resync`, or a `hello` from another gateway process than
    /// the one seen before, says the numbers start over: the point moves to
    /// the frame's `seq` even when that is lower, so the next reconnect does
    /// not ask for a `seq` of the old run (which would skip the events of
    /// the new run below it).
    pub(crate) fn note(&mut self, envelope: &EventEnvelope) {
        let restarted = match &envelope.event {
            GatewayEvent::Resync { epoch } => {
                if let Some(epoch) = epoch {
                    self.epoch = Some(epoch.clone());
                }
                true
            }
            GatewayEvent::Hello { epoch } => {
                let other = self.epoch.as_ref().is_some_and(|known| known != epoch);
                self.epoch = Some(epoch.clone());
                other
            }
            _ => false,
        };
        if restarted || self.last.is_none_or(|seq| envelope.seq > seq) {
            self.last = Some(envelope.seq);
        }
    }
}

/// Builds a WebSocket upgrade request with the bearer header.
pub(crate) fn ws_request(
    url: &GatewayUrl,
    path: &str,
    key: &ConnectKey,
) -> Result<Request, ClientError> {
    let mut request = url.ws(path).into_client_request().map_err(from_ws)?;
    request
        .headers_mut()
        .insert(AUTHORIZATION, http::bearer(key)?);
    Ok(request)
}

/// Path of the event socket, resuming after the last seen `seq`.
pub(crate) fn events_path(after: Option<u64>) -> String {
    match after {
        Some(seq) => format!("{}?after={seq}", routes::EVENTS),
        None => routes::EVENTS.to_owned(),
    }
}

/// Adds up to a quarter of `base_ms` of jitter, picked by `random`.
pub(crate) fn with_jitter(base_ms: u64, random: u32) -> u64 {
    base_ms + u64::from(random) % (base_ms / 4 + 1)
}

/// How long to wait before the next attempt: nothing when a wake kicked the
/// stream, the (jittered) backoff otherwise.
pub(crate) fn next_delay(kicked: bool, backoff_ms: u64) -> u64 {
    if kicked { 0 } else { backoff_ms }
}

fn random_u32() -> u32 {
    getrandom::u32().unwrap_or(0)
}

/// A one-worker runtime for a client thread.
pub(crate) fn runtime() -> io::Result<tokio::runtime::Runtime> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()
}

/// Starts the subscription. `after` is the last `seq` the caller has already
/// processed (`None` for a fresh start).
pub fn subscribe(
    url: GatewayUrl,
    key: ConnectKey,
    after: Option<u64>,
) -> Result<(EventStream, mpsc::UnboundedReceiver<ClientEvent>), ClientError> {
    let connector = Connector::Rustls(tls::client_config()?);
    // Fail early on a request that can never be built.
    ws_request(&url, &events_path(after), &key)?;

    let (events, receiver) = mpsc::unbounded();
    let (shutdown, stop) = oneshot::channel();
    let kick = Arc::new(Notify::new());
    let kicked = kick.clone();
    thread::Builder::new()
        .name("wa-events".into())
        .spawn(move || {
            let runtime = match runtime() {
                Ok(runtime) => runtime,
                Err(error) => {
                    let _ = events.unbounded_send(ClientEvent::Disconnected(error.to_string()));
                    return;
                }
            };
            runtime.block_on(run(url, key, after, connector, events, stop, kicked));
        })
        .map_err(|e| ClientError::Ws(e.to_string()))?;
    Ok((
        EventStream {
            shutdown: Some(shutdown),
            kick,
        },
        receiver,
    ))
}

enum Ended {
    Unauthorized,
    Lost(String),
    Stopped,
}

async fn run(
    url: GatewayUrl,
    key: ConnectKey,
    last: Option<u64>,
    connector: Connector,
    events: mpsc::UnboundedSender<ClientEvent>,
    mut stop: oneshot::Receiver<()>,
    kick: Arc<Notify>,
) {
    let mut attempt = 0u32;
    let mut cursor = Cursor::new(last);
    loop {
        let ended = tokio::select! {
            _ = &mut stop => Ended::Stopped,
            ended = session(&url, &key, &mut cursor, &connector, &events, &mut attempt, &kick) => ended,
        };
        match ended {
            Ended::Stopped => return,
            Ended::Unauthorized => {
                let _ = events.unbounded_send(ClientEvent::Unauthorized);
                return;
            }
            Ended::Lost(reason) => {
                log::warn!("wa events: {reason}");
                if events
                    .unbounded_send(ClientEvent::Disconnected(reason))
                    .is_err()
                {
                    return;
                }
            }
        }
        // The session swallows kicks while connected, so a pending one means
        // the push raced the drop: retry at once.
        let raced = kick.notified().now_or_never().is_some();
        let delay = next_delay(raced, with_jitter(backoff_ms(attempt), random_u32()));
        attempt = attempt.saturating_add(1);
        let kicked = tokio::select! {
            _ = &mut stop => return,
            () = kick.notified() => true,
            _ = tokio::time::sleep(Duration::from_millis(delay)) => false,
        };
        if kicked {
            attempt = 0;
        }
    }
}

/// One connection, from handshake to the socket closing.
async fn session(
    url: &GatewayUrl,
    key: &ConnectKey,
    cursor: &mut Cursor,
    connector: &Connector,
    events: &mpsc::UnboundedSender<ClientEvent>,
    attempt: &mut u32,
    kick: &Notify,
) -> Ended {
    let request = match ws_request(url, &events_path(cursor.last), key) {
        Ok(request) => request,
        Err(error) => return Ended::Lost(error.to_string()),
    };
    let (mut socket, _) =
        match connect_async_tls_with_config(request, None, true, Some(connector.clone())).await {
            Ok(connected) => connected,
            Err(error) => {
                return match from_ws(error) {
                    ClientError::Unauthorized => Ended::Unauthorized,
                    other => Ended::Lost(other.to_string()),
                };
            }
        };
    *attempt = 0;
    if events.unbounded_send(ClientEvent::Connected).is_err() {
        return Ended::Stopped;
    }

    let mut ping = tokio::time::interval(PING_EVERY);
    ping.tick().await;
    loop {
        let next = tokio::select! {
            // Already connected: a wake has nothing to do.
            () = kick.notified() => continue,
            _ = ping.tick() => {
                if let Err(error) = futures::SinkExt::send(&mut socket, Message::Ping(Vec::new().into())).await {
                    return Ended::Lost(from_ws(error).to_string());
                }
                continue;
            }
            next = tokio::time::timeout(IDLE_LIMIT, socket.next()) => next,
        };
        let message = match next {
            Err(_) => return Ended::Lost("no data from the gateway".into()),
            Ok(None) => return Ended::Lost("connection closed".into()),
            Ok(Some(Err(error))) => return Ended::Lost(from_ws(error).to_string()),
            Ok(Some(Ok(message))) => message,
        };
        match message {
            Message::Text(text) => {
                if let Some(envelope) = decode_event(&text) {
                    cursor.note(&envelope);
                    if events.unbounded_send(ClientEvent::Event(envelope)).is_err() {
                        return Ended::Stopped;
                    }
                }
            }
            Message::Close(_) => return Ended::Lost("closed by the gateway".into()),
            // Pings are answered by tungstenite; pongs and binary are ignored.
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> ConnectKey {
        ConnectKey::from_bytes(&[3; 32])
    }

    #[test]
    fn ws_request_has_upgrade_and_bearer() {
        let url = GatewayUrl::parse("https://wa.example.com").unwrap();
        let req = ws_request(&url, &events_path(Some(41)), &key()).unwrap();
        assert_eq!(
            req.uri().to_string(),
            "wss://wa.example.com/v1/events?after=41"
        );
        assert_eq!(
            req.headers()["authorization"].to_str().unwrap(),
            format!("Bearer {}", key().as_str())
        );
        assert_eq!(req.headers()["upgrade"], "websocket");
    }

    #[test]
    fn local_http_uses_plain_ws() {
        let url = GatewayUrl::parse("http://10.0.2.2:8787").unwrap();
        let req = ws_request(&url, &events_path(None), &key()).unwrap();
        assert_eq!(req.uri().to_string(), "ws://10.0.2.2:8787/v1/events");
    }

    #[test]
    fn identity_events_decode_and_unknown_kinds_are_skipped() {
        use rosty_wa_proto::GatewayEvent;

        let mapped = decode_event(
            r#"{"seq":7,"at_ms":9,"event":{"type":"identity_mapped","lid":"1234@lid","pn":"905550000001@s.whatsapp.net","source":"receipt","learned_ms":8}}"#,
        )
        .unwrap();
        assert_eq!(mapped.seq, 7);
        assert_eq!(
            mapped.event,
            GatewayEvent::IdentityMapped {
                lid: "1234@lid".into(),
                pn: "905550000001@s.whatsapp.net".into(),
                source: "receipt".into(),
                learned_ms: 8,
            }
        );
        let changed = decode_event(
            r#"{"seq":8,"at_ms":9,"event":{"type":"identity_number_changed","old_pn":"905550000001@s.whatsapp.net","new_pn":"905550000002@s.whatsapp.net","new_lid":"1234@lid"}}"#,
        )
        .unwrap();
        assert_eq!(
            changed.event,
            GatewayEvent::IdentityNumberChanged {
                old_pn: "905550000001@s.whatsapp.net".into(),
                new_pn: "905550000002@s.whatsapp.net".into(),
                old_lid: None,
                new_lid: Some("1234@lid".into()),
            }
        );
        // An event with alt ids this build knows, and one from the future.
        let typing = decode_event(
            r#"{"seq":9,"at_ms":9,"event":{"type":"typing","chat":"c","jid":"905550000001@s.whatsapp.net","state":"typing","jid_alt":"1234@lid","later_field":1}}"#,
        );
        assert!(typing.is_some());
        assert_eq!(
            decode_event(r#"{"seq":10,"at_ms":9,"event":{"type":"from_the_future","x":1}}"#),
            None
        );
        assert_eq!(decode_event("not json"), None);
    }

    /// The frames of a gateway that names its epoch, and of one that does
    /// not, as they come off the socket.
    #[test]
    fn epoch_account_and_history_frames_decode() {
        use rosty_wa_proto::{GatewayEvent, HistorySyncType};

        let hello = decode_event(
            r#"{"seq":41,"at_ms":9,"event":{"type":"hello","epoch":"9f2c41d07ab35e68"}}"#,
        )
        .unwrap();
        assert_eq!(hello.seq, 41);
        assert_eq!(
            hello.event,
            GatewayEvent::Hello {
                epoch: "9f2c41d07ab35e68".into()
            }
        );
        assert_eq!(
            decode_event(
                r#"{"seq":41,"at_ms":9,"event":{"type":"resync","epoch":"9f2c41d07ab35e68"}}"#
            )
            .unwrap()
            .event,
            GatewayEvent::Resync {
                epoch: Some("9f2c41d07ab35e68".into())
            }
        );
        // An older gateway.
        assert_eq!(
            decode_event(r#"{"seq":41,"at_ms":9,"event":{"type":"resync"}}"#)
                .unwrap()
                .event,
            GatewayEvent::Resync { epoch: None }
        );
        assert_eq!(
            decode_event(
                r#"{"seq":42,"at_ms":9,"event":{"type":"account_changed","old_pn":"100000000001@s.whatsapp.net","new_pn":"100000000002@s.whatsapp.net"}}"#
            )
            .unwrap()
            .event,
            GatewayEvent::AccountChanged {
                old_pn: Some("100000000001@s.whatsapp.net".into()),
                new_pn: "100000000002@s.whatsapp.net".into(),
            }
        );
        assert_eq!(
            decode_event(
                r#"{"seq":43,"at_ms":9,"event":{"type":"history_synced","chats":4,"progress":60,"sync_type":"recent","messages":900,"oldest_ms":1500000000000}}"#
            )
            .unwrap()
            .event,
            GatewayEvent::HistorySynced {
                chats: 4,
                progress: Some(60),
                sync_type: Some(HistorySyncType::Recent),
                messages: 900,
                oldest_ms: Some(1_500_000_000_000),
            }
        );
        assert_eq!(
            decode_event(
                r#"{"seq":44,"at_ms":9,"event":{"type":"history_synced","chats":4,"progress":null}}"#
            )
            .unwrap()
            .event,
            GatewayEvent::HistorySynced {
                chats: 4,
                progress: None,
                sync_type: None,
                messages: 0,
                oldest_ms: None,
            }
        );
    }

    fn frame(seq: u64, event: rosty_wa_proto::GatewayEvent) -> EventEnvelope {
        EventEnvelope {
            seq,
            at_ms: 0,
            event,
        }
    }

    #[test]
    fn the_resume_point_follows_the_gateway_process() {
        use rosty_wa_proto::GatewayEvent::{Hello, KeyRevoked, Resync};
        let hello = |epoch: &str| Hello {
            epoch: epoch.into(),
        };

        // A fresh stream: `hello` gives it the head to resume from.
        let mut cursor = Cursor::new(None);
        cursor.note(&frame(7, hello("a")));
        assert_eq!(cursor.last, Some(7));
        // Events move it forward only.
        cursor.note(&frame(9, KeyRevoked));
        cursor.note(&frame(8, KeyRevoked));
        assert_eq!(cursor.last, Some(9));
        // Reconnecting to the same process: `hello` at the resume point.
        cursor.note(&frame(9, hello("a")));
        assert_eq!(cursor.last, Some(9));

        // The gateway restarted and has three events: `hello` and `resync`
        // name the new process, and the point moves back to its head.
        cursor.note(&frame(3, hello("b")));
        assert_eq!(cursor.last, Some(3));
        cursor.note(&frame(
            3,
            Resync {
                epoch: Some("b".into()),
            },
        ));
        assert_eq!(cursor.last, Some(3));
        cursor.note(&frame(4, KeyRevoked));
        assert_eq!(cursor.last, Some(4));

        // A stream that starts with a `seq` of an older run cannot know the
        // process changed from `hello` alone; the `resync` still resets it.
        let mut stale = Cursor::new(Some(500));
        stale.note(&frame(3, hello("b")));
        assert_eq!(stale.last, Some(500));
        stale.note(&frame(
            3,
            Resync {
                epoch: Some("b".into()),
            },
        ));
        assert_eq!(stale.last, Some(3));

        // An older gateway: no `hello`, a bare `resync`.
        let mut old = Cursor::new(Some(500));
        old.note(&frame(2, Resync { epoch: None }));
        assert_eq!(old.last, Some(2));
        old.note(&frame(3, KeyRevoked));
        assert_eq!(old.last, Some(3));
    }

    #[test]
    fn kick_skips_the_backoff() {
        assert_eq!(next_delay(true, 30_000), 0);
        assert_eq!(next_delay(false, 30_000), 30_000);
        assert_eq!(next_delay(false, 0), 0);
    }

    #[test]
    fn jitter_stays_within_a_quarter() {
        assert_eq!(with_jitter(1_000, 0), 1_000);
        assert_eq!(with_jitter(1_000, 251), 1_000);
        assert_eq!(with_jitter(1_000, 7), 1_007);
        for r in [1, 99, 100_000, u32::MAX] {
            let j = with_jitter(30_000, r);
            assert!((30_000..=37_500).contains(&j));
        }
    }
}
