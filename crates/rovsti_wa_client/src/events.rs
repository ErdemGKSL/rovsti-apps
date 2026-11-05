//! The resumable event WebSocket.
//!
//! [`subscribe`] runs a background thread with a one-worker Tokio runtime. It
//! connects, forwards every [`EventEnvelope`], remembers the last `seq` and,
//! when the socket drops, reconnects with backoff and jitter, resuming after
//! that `seq`. A rejected key stops the stream for good.

use std::io;
use std::thread;
use std::time::Duration;

use futures::channel::mpsc;
use futures::{FutureExt, StreamExt};
use rovsti_wa_proto::api::routes;
use rovsti_wa_proto::limit::backoff_ms;
use rovsti_wa_proto::{ConnectKey, EventEnvelope, GatewayUrl};
use std::sync::Arc;

use tokio::sync::{Notify, oneshot};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::header::AUTHORIZATION;
use tokio_tungstenite::tungstenite::{Message, handshake::client::Request};
use tokio_tungstenite::{Connector, connect_async_tls_with_config};

use crate::error::{ClientError, from_ws};
use crate::{http, tls};

/// What the stream tells its consumer.
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
    mut last: Option<u64>,
    connector: Connector,
    events: mpsc::UnboundedSender<ClientEvent>,
    mut stop: oneshot::Receiver<()>,
    kick: Arc<Notify>,
) {
    let mut attempt = 0u32;
    loop {
        let ended = tokio::select! {
            _ = &mut stop => Ended::Stopped,
            ended = session(&url, &key, &mut last, &connector, &events, &mut attempt, &kick) => ended,
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
    last: &mut Option<u64>,
    connector: &Connector,
    events: &mpsc::UnboundedSender<ClientEvent>,
    attempt: &mut u32,
    kick: &Notify,
) -> Ended {
    let request = match ws_request(url, &events_path(*last), key) {
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
            Message::Text(text) => match serde_json::from_str::<EventEnvelope>(&text) {
                Ok(envelope) => {
                    if last.is_none_or(|seq| envelope.seq > seq) {
                        *last = Some(envelope.seq);
                    }
                    if events.unbounded_send(ClientEvent::Event(envelope)).is_err() {
                        return Ended::Stopped;
                    }
                }
                Err(error) => log::warn!("wa events: undecodable event: {error}"),
            },
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
