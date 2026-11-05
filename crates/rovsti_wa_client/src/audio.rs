//! Call audio over a WebSocket: binary frames of 960 `i16` samples (1920
//! bytes, 16 kHz mono, 60 ms) in both directions.

use std::sync::mpsc as std_mpsc;
use std::thread;

use futures::channel::mpsc;
use futures::{SinkExt, StreamExt};
use rovsti_wa_proto::api::routes;
use rovsti_wa_proto::audio::{decode_frame, encode_frame};
use rovsti_wa_proto::{ConnectKey, GatewayUrl};
use tokio::sync::oneshot;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{Connector, connect_async_tls_with_config};

use crate::error::{ClientError, from_ws};
use crate::events::{runtime, ws_request};
use crate::tls;

/// Frames buffered per direction (about half a second of audio).
const QUEUE_FRAMES: usize = 8;

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

/// Opens the audio socket of `call_id` and returns once it is connected, so a
/// rejected key or unknown call is an error here, not a silent stream.
pub fn call_audio(
    url: GatewayUrl,
    key: ConnectKey,
    call_id: &str,
) -> Result<CallAudio, ClientError> {
    let connector = Connector::Rustls(tls::client_config()?);
    let request = ws_request(&url, &audio_path(call_id), &key)?;

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
                let socket =
                    match connect_async_tls_with_config(request, None, true, Some(connector)).await
                    {
                        Ok((socket, _)) => socket,
                        Err(error) => {
                            let _ = ready_tx.send(Err(from_ws(error)));
                            return;
                        }
                    };
                let _ = ready_tx.send(Ok(()));
                pump(socket, mic_rx, speaker_tx, stop).await;
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

async fn pump<S>(
    mut socket: tokio_tungstenite::WebSocketStream<S>,
    mut mic: mpsc::Receiver<Vec<i16>>,
    mut speaker: mpsc::Sender<Vec<i16>>,
    mut stop: oneshot::Receiver<()>,
) where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    loop {
        tokio::select! {
            _ = &mut stop => break,
            frame = mic.next() => {
                let Some(samples) = frame else { break };
                match encode_frame(&samples) {
                    Ok(bytes) => {
                        if socket.send(Message::Binary(bytes.into())).await.is_err() {
                            break;
                        }
                    }
                    Err(error) => log::warn!("wa audio: dropping mic frame: {error}"),
                }
            }
            message = socket.next() => match message {
                Some(Ok(Message::Binary(bytes))) => match decode_frame(&bytes) {
                    Ok(samples) => {
                        // A full queue means the player is behind: drop the
                        // frame rather than add latency. A closed one means
                        // nobody listens any more.
                        if let Err(error) = speaker.try_send(samples)
                            && error.is_disconnected()
                        {
                            break;
                        }
                    }
                    Err(error) => log::warn!("wa audio: dropping frame: {error}"),
                },
                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                Some(Ok(_)) => {}
            },
        }
    }
    let _ = socket.close(None).await;
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
}
