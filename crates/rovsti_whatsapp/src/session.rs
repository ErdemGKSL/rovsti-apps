use std::{path::PathBuf, sync::Arc, thread, time::Duration};

use futures::channel::mpsc;
use rovsti_core::{Message, MessageDirection, PhoneNumber, ThreadId};
use whatsapp_rust::{pair_code::PairCodeOptions, prelude::*};

/// How the user approves this device from their primary WhatsApp app.
#[derive(Clone, Debug)]
pub enum LinkMethod {
    Qr,
    PhoneNumber(PhoneNumber),
}

/// Everything the UI needs to hear about, already converted to Rovsti types.
#[derive(Clone, Debug)]
pub enum WaEvent {
    /// A new pairing QR payload; it rotates every `valid_for`.
    Qr {
        payload: String,
        valid_for: Duration,
    },
    /// An 8-character code to type into WhatsApp on the primary phone.
    PairCode {
        code: String,
        valid_for: Duration,
    },
    Connected,
    LoggedOut,
    Message(Message),
    Failed(String),
}

#[derive(Debug, thiserror::Error)]
pub enum SessionError {
    #[error("phone-number linking needs a number with a country code (e.g. +90…)")]
    NeedsCountryCode,
    #[error("could not start the WhatsApp worker thread: {0}")]
    Thread(#[from] std::io::Error),
}

/// A running companion-device session. Dropping it stops the client.
pub struct Session {
    shutdown: Option<tokio::sync::oneshot::Sender<()>>,
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session")
            .field("running", &self.shutdown.is_some())
            .finish()
    }
}

impl Session {
    /// Starts the client on its own Tokio runtime thread. `database` is the
    /// SQLite file holding keys and history; reuse it to stay linked across
    /// launches (no QR the second time).
    pub fn start(
        database: PathBuf,
        method: LinkMethod,
    ) -> Result<(Self, mpsc::UnboundedReceiver<WaEvent>), SessionError> {
        let pair_phone = match &method {
            LinkMethod::Qr => None,
            LinkMethod::PhoneNumber(number) => Some(
                number
                    .whatsapp_user()
                    .ok_or(SessionError::NeedsCountryCode)?
                    .to_owned(),
            ),
        };
        let (events, receiver) = mpsc::unbounded();
        let (shutdown, stop) = tokio::sync::oneshot::channel();

        thread::Builder::new()
            .name("whatsapp".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_multi_thread()
                    .worker_threads(2)
                    .enable_all()
                    .build()
                {
                    Ok(runtime) => runtime,
                    Err(error) => {
                        let _ = events.unbounded_send(WaEvent::Failed(error.to_string()));
                        return;
                    }
                };
                runtime.block_on(run(database, pair_phone, events, stop));
            })?;

        Ok((
            Self {
                shutdown: Some(shutdown),
            },
            receiver,
        ))
    }

    pub fn stop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.stop();
    }
}

async fn run(
    database: PathBuf,
    pair_phone: Option<String>,
    events: mpsc::UnboundedSender<WaEvent>,
    stop: tokio::sync::oneshot::Receiver<()>,
) {
    let fail = |events: &mpsc::UnboundedSender<WaEvent>, error: String| {
        log::error!("whatsapp: {error}");
        let _ = events.unbounded_send(WaEvent::Failed(error));
    };

    let store = match SqliteStore::new(&database.to_string_lossy()).await {
        Ok(store) => store,
        Err(error) => return fail(&events, format!("opening {}: {error}", database.display())),
    };

    let tx = Arc::new(events.clone());
    let mut builder = Bot::builder()
        .with_backend(store)
        .on_qr_code({
            let tx = tx.clone();
            move |payload, valid_for| {
                let _ = tx.unbounded_send(WaEvent::Qr { payload, valid_for });
                async {}
            }
        })
        .on_pair_code({
            let tx = tx.clone();
            move |code, valid_for| {
                let _ = tx.unbounded_send(WaEvent::PairCode { code, valid_for });
                async {}
            }
        })
        .on_connected({
            let tx = tx.clone();
            move |_client| {
                let _ = tx.unbounded_send(WaEvent::Connected);
                async {}
            }
        })
        .on_logged_out({
            let tx = tx.clone();
            move |_info| {
                let _ = tx.unbounded_send(WaEvent::LoggedOut);
                async {}
            }
        })
        .on_message({
            let tx = tx.clone();
            move |ctx| {
                if let Some(message) = to_rovsti(&ctx) {
                    let _ = tx.unbounded_send(WaEvent::Message(message));
                }
                async {}
            }
        });

    if let Some(phone_number) = pair_phone {
        builder = builder.with_pair_code(PairCodeOptions {
            phone_number,
            ..Default::default()
        });
    }

    let bot = match builder.build().await {
        Ok(bot) => bot,
        Err(error) => return fail(&events, error.to_string()),
    };
    let client = bot.client();
    let handle = bot.spawn();

    let _ = stop.await;
    client.disconnect().await;
    drop(handle);
}

fn to_rovsti(ctx: &MessageContext) -> Option<Message> {
    let body = ctx.message.text_content()?.to_owned();
    let source = &ctx.info.source;
    let direction = if source.is_from_me {
        MessageDirection::Outgoing
    } else {
        MessageDirection::Incoming
    };
    let sender = (!source.is_from_me)
        .then(|| PhoneNumber::parse(&format!("+{}", source.sender.user)).ok())
        .flatten();
    Some(Message {
        thread: ThreadId::WhatsApp(source.chat.to_string()),
        direction,
        sender,
        body,
        timestamp_ms: ctx.info.timestamp.timestamp_millis(),
        read: source.is_from_me,
    })
}
