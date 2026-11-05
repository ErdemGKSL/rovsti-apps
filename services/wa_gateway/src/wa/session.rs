//! [`WaRuntime`]: the [`WaDriver`] that runs one whatsapp-rust `Bot` per
//! instance. Each instance has its own SQLite file
//! (`<data>/instances/<id>/wa.db`) holding its keys, so several accounts live
//! side by side in one process.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures::future::BoxFuture;
use rovsti_wa_proto::{
    ChatState, GatewayEvent, GroupInfo, InstanceState, InstanceStatus, LinkRequest, MarkRead,
    MessageBody, Reaction, SendReaction, SendText, Sent, TypingState, WaMessage,
    event::ReceiptKind,
};
use whatsapp_rust::pair_code::PairCodeOptions;
use whatsapp_rust::prelude::*;
use whatsapp_rust::upload::UploadOptions;
use whatsapp_rust::wacore::types::events::ConnectFailureReason;
use whatsapp_rust::wacore::types::presence::{ChatPresence, ChatPresenceMedia, ReceiptType};
use whatsapp_rust::waproto::buffa::Message as _;

use super::calls::{CallBackend, WaCalls};
use super::convert::{self, Converted};
use super::{groups, history, media, reaction};
use crate::driver::{DriverError, EventSink, SendMedia, WaDriver};
use crate::now_ms;
use crate::store::InstanceId;

/// How long a bot gets to flush and stop before it is abandoned.
const STOP_TIMEOUT: Duration = Duration::from_secs(10);

struct Running {
    generation: u64,
    client: Arc<Client>,
    handle: BotHandle,
    sink: EventSink,
}

struct Shared {
    data_dir: PathBuf,
    running: Mutex<HashMap<InstanceId, Running>>,
    next_generation: AtomicU64,
}

/// Runs the real WhatsApp sessions.
#[derive(Clone)]
pub struct WaRuntime(Arc<Shared>);

impl std::fmt::Debug for WaRuntime {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("WaRuntime")
    }
}

fn failed(e: impl std::fmt::Display) -> DriverError {
    DriverError::Failed(e.to_string())
}

fn status(
    instance: &InstanceId,
    state: InstanceState,
    own_jid: Option<String>,
    push_name: Option<String>,
) -> GatewayEvent {
    GatewayEvent::State(InstanceStatus {
        instance_id: instance.to_string(),
        state,
        own_jid,
        push_name,
    })
}

fn millis(d: Duration) -> u64 {
    u64::try_from(d.as_millis()).unwrap_or(u64::MAX)
}

/// The delivery state a receipt reports, if it is one the apps show.
pub fn receipt_kind(kind: &ReceiptType) -> Option<ReceiptKind> {
    match kind {
        ReceiptType::Delivered => Some(ReceiptKind::Delivered),
        ReceiptType::Read | ReceiptType::ReadSelf | ReceiptType::Played => Some(ReceiptKind::Read),
        _ => None,
    }
}

/// A typing indicator as the apps know it.
pub fn typing_state(state: ChatPresence, media: ChatPresenceMedia) -> TypingState {
    match (state, media) {
        (ChatPresence::Paused, _) => TypingState::Paused,
        (ChatPresence::Composing, ChatPresenceMedia::Audio) => TypingState::Recording,
        (ChatPresence::Composing, ChatPresenceMedia::Text) => TypingState::Typing,
    }
}

/// The reply context for quoting `quoted_id`. `quoted` is the stored message
/// when we have it, `own_jid` our account.
pub fn quote_context(
    chat: &str,
    quoted_id: &str,
    quoted: Option<&WaMessage>,
    own_jid: Option<&str>,
) -> wa::ContextInfo {
    let participant = match quoted {
        Some(m) if m.from_me => own_jid.map(str::to_owned),
        Some(m) => m.sender.clone(),
        None => None,
    }
    // One-to-one chats are quoted by the other side's JID.
    .or_else(|| (!convert::is_group(chat)).then(|| chat.to_owned()));
    let preview = quoted.and_then(|m| match &m.body {
        MessageBody::Text(t) => Some(wa::Message {
            conversation: Some(t.clone()),
            ..Default::default()
        }),
        _ => None,
    });
    wa::ContextInfo {
        stanza_id: Some(quoted_id.to_owned()),
        participant,
        quoted_message: preview.map_or_else(MessageField::none, MessageField::some),
        ..Default::default()
    }
}

impl WaRuntime {
    pub fn new(data_dir: PathBuf) -> Self {
        Self(Arc::new(Shared {
            data_dir,
            running: Mutex::new(HashMap::new()),
            next_generation: AtomicU64::new(1),
        }))
    }

    fn instance_dir(&self, instance: &InstanceId) -> PathBuf {
        self.0.data_dir.join("instances").join(instance.as_str())
    }

    fn running(&self) -> std::sync::MutexGuard<'_, HashMap<InstanceId, Running>> {
        self.0.running.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn client_of(&self, instance: &InstanceId) -> Result<(Arc<Client>, EventSink), DriverError> {
        let running = self.running();
        let r = running.get(instance).ok_or(DriverError::NotConnected)?;
        if !r.client.is_connected() {
            return Err(DriverError::NotConnected);
        }
        Ok((Arc::clone(&r.client), r.sink.clone()))
    }

    /// Stops the bot of `instance` (when it is still the run `generation`, if
    /// one is given) and optionally deletes its WhatsApp database.
    async fn teardown(&self, instance: &InstanceId, generation: Option<u64>, delete: bool) {
        let taken = {
            let mut running = self.running();
            match running.get(instance) {
                Some(r) if generation.is_none_or(|g| g == r.generation) => running.remove(instance),
                Some(_) => return, // a newer run owns this instance now
                None => None,
            }
        };
        if let Some(r) = taken {
            if tokio::time::timeout(STOP_TIMEOUT, r.handle.shutdown())
                .await
                .is_err()
            {
                log::warn!("{instance}: the bot did not stop in time");
            }
            drop(r.client);
        }
        if delete {
            let dir = self.instance_dir(instance);
            match tokio::fs::remove_dir_all(&dir).await {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => log::warn!("{instance}: cannot delete {}: {e}", dir.display()),
            }
        }
    }

    async fn start_instance(
        &self,
        instance: InstanceId,
        link: Option<LinkRequest>,
        sink: EventSink,
    ) -> Result<(), DriverError> {
        let pair_phone = match &link {
            Some(link) => link
                .pair_phone()
                .map_err(|e| DriverError::BadRequest(e.to_string()))?,
            None => None,
        };
        // A restart replaces the running bot.
        self.teardown(&instance, None, false).await;

        let dir = self.instance_dir(&instance);
        tokio::fs::create_dir_all(&dir)
            .await
            .map_err(|e| failed(format!("cannot create {}: {e}", dir.display())))?;
        let database = dir.join("wa.db");
        let backend = SqliteStore::new(&database.to_string_lossy())
            .await
            .map_err(|e| failed(format!("opening {}: {e}", database.display())))?;

        let generation = self.0.next_generation.fetch_add(1, Ordering::Relaxed);
        let was_connected = Arc::new(AtomicBool::new(false));
        let mut builder = Bot::builder()
            .with_backend(backend)
            .on_qr_code({
                let sink = sink.clone();
                move |payload, valid_for| {
                    let sink = sink.clone();
                    async move {
                        sink.emit(GatewayEvent::Qr {
                            payload,
                            valid_for_ms: millis(valid_for),
                        })
                        .await;
                    }
                }
            })
            .on_pair_code({
                let sink = sink.clone();
                move |code, valid_for| {
                    let sink = sink.clone();
                    async move {
                        sink.emit(GatewayEvent::PairCode {
                            code,
                            valid_for_ms: millis(valid_for),
                        })
                        .await;
                    }
                }
            })
            .on_connected({
                let sink = sink.clone();
                let instance = instance.clone();
                let was_connected = Arc::clone(&was_connected);
                move |client| {
                    let sink = sink.clone();
                    let instance = instance.clone();
                    was_connected.store(true, Ordering::Relaxed);
                    let device = client.persistence_manager().get_device_snapshot();
                    let own_jid = device.pn.as_ref().map(|jid| jid.to_non_ad().to_string());
                    let push_name = Some(device.push_name.clone()).filter(|n| !n.is_empty());
                    async move {
                        sink.emit(status(
                            &instance,
                            InstanceState::Connected,
                            own_jid,
                            push_name,
                        ))
                        .await;
                    }
                }
            })
            .on_logged_out({
                let sink = sink.clone();
                let instance = instance.clone();
                let runtime = self.clone();
                move |_info| {
                    let sink = sink.clone();
                    let instance = instance.clone();
                    let runtime = runtime.clone();
                    async move {
                        sink.emit(status(&instance, InstanceState::LoggedOut, None, None))
                            .await;
                        // The bot is running this handler; stop it from a task
                        // of its own so it can finish.
                        tokio::spawn(async move {
                            runtime.teardown(&instance, Some(generation), true).await;
                        });
                    }
                }
            })
            .on_message({
                let sink = sink.clone();
                move |ctx| {
                    let (converted, raw) = convert::message(&ctx);
                    let sink = sink.clone();
                    async move {
                        match converted {
                            Converted::Message(message) => sink.emit_message(message, raw).await,
                            Converted::Reaction {
                                chat,
                                target_id,
                                reaction,
                            } => sink.emit_reaction(chat, target_id, reaction).await,
                            Converted::Ignore => {}
                        }
                    }
                }
            })
            .on_event_for(
                &[
                    EventKind::Receipt,
                    EventKind::Presence,
                    EventKind::ChatPresence,
                    EventKind::HistorySync,
                    EventKind::GroupUpdate,
                    EventKind::Disconnected,
                    EventKind::StreamReplaced,
                    EventKind::TemporaryBan,
                    EventKind::ConnectFailure,
                    EventKind::IncomingCall,
                    EventKind::MissedCall,
                    EventKind::CallEndedElsewhere,
                ],
                {
                    let sink = sink.clone();
                    let instance = instance.clone();
                    let was_connected = Arc::clone(&was_connected);
                    move |event, _client| {
                        let sink = sink.clone();
                        let instance = instance.clone();
                        let was_connected = Arc::clone(&was_connected);
                        async move { handle_event(&sink, &instance, &was_connected, &event).await }
                    }
                },
            );
        if let Some(phone_number) = pair_phone {
            builder = builder.with_pair_code(PairCodeOptions {
                phone_number,
                ..Default::default()
            });
        }

        let bot = builder.build().await.map_err(failed)?;
        let client = bot.client();
        let handle = bot.spawn();
        self.running().insert(
            instance,
            Running {
                generation,
                client,
                handle,
                sink,
            },
        );
        Ok(())
    }

    async fn send_media_inner(
        &self,
        instance: &InstanceId,
        req: SendMedia,
    ) -> Result<Sent, DriverError> {
        let (client, sink) = self.client_of(instance)?;
        let to = convert::parse_jid(&req.chat)?;
        let chat = to.to_non_ad().to_string();
        let size = req.data.len() as u64;

        let uploaded: media::Uploaded = client
            .upload(
                req.data,
                media::media_type(req.kind),
                UploadOptions::default(),
            )
            .await
            .map_err(failed)?
            .into();
        let message = media::build_message(req.kind, &uploaded, &req.mime, req.caption.as_deref());
        let raw = message.encode_to_vec();
        let result = client.send_message(to, message).await.map_err(failed)?;

        let timestamp_ms = now_ms();
        let sender = client
            .persistence_manager()
            .get_device_snapshot()
            .pn
            .as_ref()
            .and_then(|jid| convert::group_sender(&chat, jid));
        sink.emit_message(
            WaMessage {
                id: result.message_id.clone(),
                chat,
                sender,
                from_me: true,
                timestamp_ms,
                body: MessageBody::Media {
                    kind: req.kind,
                    caption: req.caption.filter(|c| !c.is_empty()),
                    mime: req.mime,
                    size,
                },
                reactions: Vec::new(),
            },
            Some(raw),
        )
        .await;
        Ok(Sent {
            message_id: result.message_id,
            timestamp_ms,
        })
    }

    async fn react_inner(
        &self,
        instance: &InstanceId,
        req: SendReaction,
    ) -> Result<(), DriverError> {
        let (client, sink) = self.client_of(instance)?;
        let to = convert::parse_jid(&req.chat)?;
        let chat = to.to_non_ad().to_string();
        let own = client
            .persistence_manager()
            .get_device_snapshot()
            .pn
            .as_ref()
            .map(|jid| jid.to_non_ad());
        let own_jid = own.as_ref().map(ToString::to_string);

        let stored = sink.find_message(&chat, &req.message_id).await;
        let author = reaction::target_author(&req, stored.as_ref(), own_jid.as_deref());
        let key = reaction::message_key(&chat, &req.message_id, req.from_me, author.as_deref());
        client
            .send_reaction(to, key, &req.emoji)
            .await
            .map_err(failed)?;

        sink.emit_reaction(
            chat.clone(),
            req.message_id,
            Reaction {
                sender: own
                    .as_ref()
                    .and_then(|jid| convert::group_sender(&chat, jid)),
                from_me: true,
                emoji: req.emoji,
            },
        )
        .await;
        Ok(())
    }

    async fn group_infos(
        &self,
        instance: &InstanceId,
        only: Option<Jid>,
    ) -> Result<Vec<GroupInfo>, DriverError> {
        let (client, sink) = self.client_of(instance)?;
        let metadata: Vec<_> = match only {
            Some(jid) => vec![client.groups().get_metadata(&jid).await.map_err(failed)?],
            None => client
                .groups()
                .get_participating()
                .await
                .map_err(failed)?
                .into_values()
                .collect(),
        };
        let mut infos = Vec::with_capacity(metadata.len());
        for group in &metadata {
            let info = groups::group_info(
                &group.id,
                &group.subject,
                group.participants.iter().map(|p| {
                    (
                        groups::participant_jid(&p.jid, p.phone_number.as_ref()),
                        p.is_admin(),
                    )
                }),
            );
            sink.rename_chat(&info.jid, &info.subject).await;
            infos.push(info);
        }
        infos.sort_by_key(|g| g.subject.to_lowercase());
        Ok(infos)
    }

    async fn send_text_inner(
        &self,
        instance: &InstanceId,
        req: SendText,
    ) -> Result<Sent, DriverError> {
        let (client, sink) = self.client_of(instance)?;
        let to = convert::parse_jid(&req.chat)?;
        let chat = to.to_non_ad().to_string();
        let own = client
            .persistence_manager()
            .get_device_snapshot()
            .pn
            .as_ref()
            .map(|jid| jid.to_non_ad());

        let result = match req.quoted_id.as_deref().filter(|id| !id.is_empty()) {
            None => client.send_text(to, req.text.clone()).await,
            Some(quoted_id) => {
                let quoted = sink.find_message(&chat, quoted_id).await;
                let own_jid = own.as_ref().map(ToString::to_string);
                let context = quote_context(&chat, quoted_id, quoted.as_ref(), own_jid.as_deref());
                let message = wa::Message {
                    extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                        text: Some(req.text.clone()),
                        context_info: MessageField::some(context),
                        ..Default::default()
                    }),
                    ..Default::default()
                };
                client.send_message(to, message).await
            }
        }
        .map_err(failed)?;

        let timestamp_ms = now_ms();
        let sender = own
            .as_ref()
            .and_then(|jid| convert::group_sender(&chat, jid));
        sink.emit_message(
            WaMessage {
                id: result.message_id.clone(),
                chat,
                sender,
                from_me: true,
                timestamp_ms,
                body: MessageBody::Text(req.text),
                reactions: Vec::new(),
            },
            None,
        )
        .await;
        Ok(Sent {
            message_id: result.message_id,
            timestamp_ms,
        })
    }
}

async fn handle_event(
    sink: &EventSink,
    instance: &InstanceId,
    was_connected: &AtomicBool,
    event: &Event,
) {
    match event {
        Event::Receipt(receipt) => {
            let Some(kind) = receipt_kind(&receipt.r#type) else {
                return;
            };
            if receipt.message_ids.is_empty() {
                return;
            }
            sink.publish(GatewayEvent::Receipt {
                chat: receipt.source.chat.to_non_ad().to_string(),
                ids: receipt
                    .message_ids
                    .iter()
                    .map(ToString::to_string)
                    .collect(),
                kind,
            });
        }
        Event::Presence(update) => sink.publish(GatewayEvent::Presence {
            jid: update.from.to_non_ad().to_string(),
            available: !update.unavailable,
            last_seen_ms: update.last_seen.map(|t| t.timestamp_millis()),
        }),
        Event::ChatPresence(update) => sink.publish(GatewayEvent::Typing {
            chat: update.source.chat.to_non_ad().to_string(),
            jid: update.source.sender.to_non_ad().to_string(),
            state: typing_state(update.state, update.media),
        }),
        Event::GroupUpdate(update) => {
            let jid = update.group_jid.to_non_ad().to_string();
            if let Some(subject) = groups::new_subject(&update.action) {
                sink.rename_chat(&jid, subject).await;
            }
            sink.publish(GatewayEvent::GroupChanged { jid });
        }
        Event::HistorySync(lazy) => {
            let progress = lazy.progress();
            let lazy = (**lazy).clone();
            let chats = tokio::task::spawn_blocking(move || history::drain(&lazy)).await;
            match chats {
                Ok(Ok(chats)) => sink.import_history(chats, progress).await,
                Ok(Err(e)) => log::warn!("{instance}: history sync unreadable: {e}"),
                Err(e) => log::warn!("{instance}: history sync task failed: {e}"),
            }
        }
        Event::Disconnected(d) => {
            log::info!("{instance}: disconnected: {}", d.reason);
            // Before the first connection this is just a pairing socket
            // recycling; the instance stays `Linking`.
            if was_connected.load(Ordering::Relaxed) {
                sink.emit(status(instance, InstanceState::Disconnected, None, None))
                    .await;
            }
        }
        Event::IncomingCall(call) => sink.calls().on_incoming(call),
        Event::MissedCall(missed) => sink.calls().on_missed(&missed.call_id),
        Event::CallEndedElsewhere(ended) => sink.calls().on_ended_elsewhere(&ended.call_id),
        Event::StreamReplaced(_) => {
            log::warn!("{instance}: another client took over this WhatsApp session");
            sink.emit(status(instance, InstanceState::Disconnected, None, None))
                .await;
        }
        Event::TemporaryBan(ban) => {
            log::warn!("{instance}: temporarily banned for {:?}", ban.expire);
            sink.emit(status(instance, InstanceState::Banned, None, None))
                .await;
        }
        Event::ConnectFailure(failure) => {
            log::warn!(
                "{instance}: connect failure {:?}: {}",
                failure.reason,
                failure.message.as_deref().unwrap_or("")
            );
            if matches!(failure.reason, ConnectFailureReason::TempBanned) {
                sink.emit(status(instance, InstanceState::Banned, None, None))
                    .await;
            }
        }
        _ => {}
    }
}

impl WaDriver for WaRuntime {
    fn start(
        &self,
        instance: InstanceId,
        link: Option<LinkRequest>,
        sink: EventSink,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(self.start_instance(instance, link, sink))
    }

    fn stop(&self, instance: &InstanceId) -> BoxFuture<'_, ()> {
        let instance = instance.clone();
        Box::pin(async move { self.teardown(&instance, None, false).await })
    }

    fn logout(&self, instance: &InstanceId) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let client = self.running().get(&instance).map(|r| Arc::clone(&r.client));
            if let Some(client) = client {
                // Unlinks on WhatsApp's side; the `LoggedOut` handler follows.
                client.logout().await;
            }
            self.teardown(&instance, None, true).await;
            Ok(())
        })
    }

    fn send_text(
        &self,
        instance: &InstanceId,
        req: SendText,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move { self.send_text_inner(&instance, req).await })
    }

    fn mark_read(
        &self,
        instance: &InstanceId,
        req: MarkRead,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let chat = convert::parse_jid(&req.chat)?;
            let sender = req
                .sender
                .as_deref()
                .filter(|s| !s.trim().is_empty())
                .map(convert::parse_jid)
                .transpose()?;
            let (client, sink) = self.client_of(&instance)?;
            let ids: Vec<&str> = req.ids.iter().map(String::as_str).collect();
            client
                .mark_as_read(&chat, sender.as_ref(), &ids)
                .await
                .map_err(failed)?;
            sink.mark_chat_read(&chat.to_non_ad().to_string()).await;
            Ok(())
        })
    }

    fn set_presence(
        &self,
        instance: &InstanceId,
        available: bool,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let (client, _) = self.client_of(&instance)?;
            let presence = client.presence();
            if available {
                presence.set_available().await
            } else {
                presence.set_unavailable().await
            }
            .map_err(failed)
        })
    }

    fn chat_state(
        &self,
        instance: &InstanceId,
        req: ChatState,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let chat = convert::parse_jid(&req.chat)?;
            let (client, _) = self.client_of(&instance)?;
            let state = client.chatstate();
            match req.state {
                TypingState::Typing => state.send_composing(&chat).await,
                TypingState::Recording => state.send_recording(&chat).await,
                TypingState::Paused => state.send_paused(&chat).await,
            }
            .map_err(failed)
        })
    }

    fn subscribe_presence(
        &self,
        instance: &InstanceId,
        jid: String,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let jid = convert::parse_jid(&jid)?;
            let (client, _) = self.client_of(&instance)?;
            client.presence().subscribe(jid).await.map_err(failed)
        })
    }

    fn send_media(
        &self,
        instance: &InstanceId,
        req: SendMedia,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move { self.send_media_inner(&instance, req).await })
    }

    fn react(
        &self,
        instance: &InstanceId,
        req: SendReaction,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move { self.react_inner(&instance, req).await })
    }

    fn download_media(
        &self,
        instance: &InstanceId,
        raw_proto: Vec<u8>,
    ) -> BoxFuture<'_, Result<Vec<u8>, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let (client, _) = self.client_of(&instance)?;
            let message = media::decode(&raw_proto)?;
            let found = media::media_of(&message)
                .ok_or_else(|| DriverError::BadRequest("the message has no media".to_owned()))?;
            client.download(found.downloadable()).await.map_err(failed)
        })
    }

    fn groups(&self, instance: &InstanceId) -> BoxFuture<'_, Result<Vec<GroupInfo>, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move { self.group_infos(&instance, None).await })
    }

    fn call_backend(&self, instance: &InstanceId) -> Result<Arc<dyn CallBackend>, DriverError> {
        let (client, _) = self.client_of(instance)?;
        Ok(Arc::new(WaCalls::new(client)))
    }

    fn group(
        &self,
        instance: &InstanceId,
        jid: String,
    ) -> BoxFuture<'_, Result<GroupInfo, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let jid = convert::parse_jid(&jid)?;
            self.group_infos(&instance, Some(jid))
                .await?
                .pop()
                .ok_or_else(|| DriverError::Failed("group query returned nothing".to_owned()))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stored(from_me: bool, sender: Option<&str>, text: &str) -> WaMessage {
        WaMessage {
            id: "Q".into(),
            chat: "c".into(),
            sender: sender.map(str::to_owned),
            from_me,
            timestamp_ms: 1,
            body: MessageBody::Text(text.into()),
            reactions: vec![],
        }
    }

    #[test]
    fn receipts_map_to_delivered_or_read() {
        assert_eq!(
            receipt_kind(&ReceiptType::Delivered),
            Some(ReceiptKind::Delivered)
        );
        assert_eq!(receipt_kind(&ReceiptType::Read), Some(ReceiptKind::Read));
        assert_eq!(receipt_kind(&ReceiptType::Played), Some(ReceiptKind::Read));
        assert_eq!(receipt_kind(&ReceiptType::Retry), None);
        assert_eq!(receipt_kind(&ReceiptType::Sender), None);
    }

    #[test]
    fn typing_states() {
        use ChatPresence::{Composing, Paused};
        assert_eq!(
            typing_state(Composing, ChatPresenceMedia::Text),
            TypingState::Typing
        );
        assert_eq!(
            typing_state(Composing, ChatPresenceMedia::Audio),
            TypingState::Recording
        );
        assert_eq!(
            typing_state(Paused, ChatPresenceMedia::Audio),
            TypingState::Paused
        );
    }

    #[test]
    fn quote_context_names_the_quoted_author() {
        let dm = "905321234567@s.whatsapp.net";
        let group = "123-456@g.us";

        let theirs = quote_context(
            dm,
            "Q",
            Some(&stored(false, None, "hi")),
            Some("me@s.whatsapp.net"),
        );
        assert_eq!(theirs.stanza_id.as_deref(), Some("Q"));
        assert_eq!(theirs.participant.as_deref(), Some(dm));
        let preview = theirs.quoted_message.as_option().unwrap();
        assert_eq!(preview.conversation.as_deref(), Some("hi"));

        let mine = quote_context(
            dm,
            "Q",
            Some(&stored(true, None, "yo")),
            Some("me@s.whatsapp.net"),
        );
        assert_eq!(mine.participant.as_deref(), Some("me@s.whatsapp.net"));

        let in_group = quote_context(
            group,
            "Q",
            Some(&stored(false, Some("4915@s.whatsapp.net"), "x")),
            None,
        );
        assert_eq!(in_group.participant.as_deref(), Some("4915@s.whatsapp.net"));

        // Not in our history: a DM can still be quoted, a group cannot name an author.
        assert_eq!(
            quote_context(dm, "Q", None, None).participant.as_deref(),
            Some(dm)
        );
        assert_eq!(quote_context(group, "Q", None, None).participant, None);
        assert!(
            quote_context(dm, "Q", None, None)
                .quoted_message
                .as_option()
                .is_none()
        );
    }

    #[test]
    fn instance_files_live_under_the_data_dir() {
        let runtime = WaRuntime::new(PathBuf::from("/data"));
        let id = InstanceId::new("abc");
        assert_eq!(
            runtime.instance_dir(&id),
            PathBuf::from("/data/instances/abc")
        );
    }
}
