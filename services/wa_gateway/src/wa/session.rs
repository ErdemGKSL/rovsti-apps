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
use rovsti_wa_proto::meta::body_preview;
use rovsti_wa_proto::{
    ChatAction, ChatActionKind, ChatState, DeliveryStatus, EditMessage, GatewayEvent, GroupInfo,
    InstanceState, InstanceStatus, LinkRequest, MarkRead, MessageBody, MessageMeta, Quoted,
    Reaction, RevokeMessage, SendReaction, SendText, Sent, TypingState, WaMessage, WaUser,
    event::ReceiptKind,
};
use whatsapp_rust::RevokeType;
use whatsapp_rust::pair_code::PairCodeOptions;
use whatsapp_rust::prelude::*;
use whatsapp_rust::upload::UploadOptions;
use whatsapp_rust::wacore::client::context::GroupInfo as WaGroupInfo;
use whatsapp_rust::wacore::types::events::{ConnectFailureReason, Receipt};
use whatsapp_rust::wacore::types::presence::{ChatPresence, ChatPresenceMedia, ReceiptType};
use whatsapp_rust::wacore_binary::JidExt as _;
use whatsapp_rust::waproto::buffa::Message as _;
use whatsapp_rust::{ContactError, IqError};

use super::calls::{CallBackend, WaCalls};
use super::convert;
use super::lid::{self, LidMap, Resolver};
use super::{app_state, groups, history, media, picture, reaction, status};
use crate::driver::{
    DriverError, EventSink, MessageRef, PictureFetch, SendMedia, StatusReceipt, WaDriver,
};
use crate::now_ms;
use crate::store::{FetchedProfile, InstanceId, MessageExtras};

/// How long a bot gets to flush and stop before it is abandoned.
const STOP_TIMEOUT: Duration = Duration::from_secs(10);

struct Running {
    generation: u64,
    client: Arc<Client>,
    resolver: Arc<Resolver>,
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

/// WhatsApp's "rate-overlimit" answer to a contact query (usync lookup,
/// user info, profile picture).
const RATE_LIMIT_CODE: u16 = 429;

/// A failed contact query: WhatsApp's 429 becomes [`DriverError::RateLimited`]
/// (with its `backoff` hint), everything else [`DriverError::Failed`].
fn contact_failed(e: ContactError) -> DriverError {
    match &e {
        ContactError::Iq(IqError::ServerError { code, backoff, .. })
            if *code == RATE_LIMIT_CODE =>
        {
            DriverError::RateLimited {
                retry_after_s: *backoff,
            }
        }
        _ => rate_limited_text(&e.to_string()).unwrap_or_else(|| failed(e)),
    }
}

/// Fallback should the 429 arrive wrapped in another error type: the
/// `IqError` text is `received a server error response: code=429, ...`.
fn rate_limited_text(text: &str) -> Option<DriverError> {
    text.contains("code=429")
        .then_some(DriverError::RateLimited {
            retry_after_s: None,
        })
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

/// The ticks a receipt from the other side puts on my messages. `ReadSelf`
/// (I read their message on another of my devices) and `PlayedSelf` are
/// about their messages, never ticks on mine.
pub fn receipt_status(kind: &ReceiptType) -> Option<DeliveryStatus> {
    match kind {
        ReceiptType::Delivered => Some(DeliveryStatus::Delivered),
        ReceiptType::Read => Some(DeliveryStatus::Read),
        ReceiptType::Played => Some(DeliveryStatus::Played),
        _ => None,
    }
}

/// Whether `jid` is one of my own accounts (`own`: my phone-number and LID
/// JIDs), whatever the device.
fn is_own(jid: &Jid, own: &[&Jid]) -> bool {
    own.iter().any(|me| me.user == jid.user)
}

/// The other members of a group: everyone but me.
fn members_besides_me(participants: &[Jid], own: &[&Jid]) -> usize {
    participants.iter().filter(|p| !is_own(p, own)).count()
}

/// One name per group member for counting receipts, whether WhatsApp named
/// them by phone number or by LID: the phone number when it is known (from
/// the receipt, else from the group's LID map), else the LID.
fn participant_key(
    sender: &Jid,
    alt: Option<&Jid>,
    group: Option<&WaGroupInfo>,
    map: &LidMap,
) -> String {
    let jid = lid::resolve(sender, alt, map);
    if jid.is_lid()
        && let Some(pn) = group.and_then(|g| g.phone_jid_for_lid_user(&jid.user))
    {
        return pn.to_non_ad().to_string();
    }
    jid.to_string()
}

/// Read receipt requests for `ids` in group `chat` when the app did not name
/// the author: one per author of the stored messages (`None` for unknown
/// ones, which WhatsApp then has to match by id alone).
fn split_by_author(
    ids: &[String],
    authors: &[Option<String>],
) -> Vec<(Option<String>, Vec<String>)> {
    let mut out: Vec<(Option<String>, Vec<String>)> = Vec::new();
    for (id, author) in ids.iter().zip(authors) {
        match out.iter_mut().find(|(a, _)| a == author) {
            Some((_, group)) => group.push(id.clone()),
            None => out.push((author.clone(), vec![id.clone()])),
        }
    }
    out
}

/// The reply meta stored with a message I send quoting `quoted`.
fn quoted_meta(quoted_id: &str, quoted: Option<&WaMessage>, own_jid: Option<&str>) -> Quoted {
    Quoted {
        id: quoted_id.to_owned(),
        sender: quoted.and_then(|m| {
            if m.from_me {
                own_jid.map(str::to_owned)
            } else {
                m.sender.clone().or_else(|| Some(m.chat.clone()))
            }
        }),
        preview: quoted.and_then(|m| body_preview(&m.body, 120)),
    }
}

/// The parsed author of a stored message (`None`: mine, or a one-to-one chat).
fn author_jid(target: &MessageRef) -> Result<Option<Jid>, DriverError> {
    target
        .sender
        .as_deref()
        .filter(|_| !target.from_me && convert::is_group(&target.chat))
        .map(convert::parse_jid)
        .transpose()
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

    fn resolver_of(&self, instance: &InstanceId) -> Result<Arc<Resolver>, DriverError> {
        let running = self.running();
        let r = running.get(instance).ok_or(DriverError::NotConnected)?;
        Ok(Arc::clone(&r.resolver))
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
                        // The one-time migration: fold LID chats whose phone
                        // number is known into their phone-number chat.
                        let resolver = Resolver::new(client);
                        tokio::spawn(async move {
                            lid::sweep(&resolver, &sink).await;
                        });
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
                    let sink = sink.clone();
                    async move {
                        // Mappings first (the library keeps them), so a
                        // message without a phone-number alt still lands in
                        // the phone-number chat.
                        let resolver = Resolver::new(Arc::clone(&ctx.client));
                        let map = resolver.prefetch(&lid::source_jids(&ctx.info.source)).await;
                        // Status updates are stored apart, never as a chat.
                        if status::is_status(&ctx) {
                            status::apply(&sink, status::live(&ctx, &map)).await;
                            return;
                        }
                        // An encrypted edit needs the stored secret of the
                        // edited message; the rest converts without any lookup.
                        let converted = match app_state::encrypted_edit(&ctx, &map) {
                            Some(edit) => edit.resolve(&sink).await,
                            None => convert::message(&ctx, &map),
                        };
                        app_state::apply_converted(&sink, converted).await;
                        // A chat that was stored under its LID before the
                        // mapping was known now has a phone number.
                        let source = &ctx.info.source;
                        if source.chat.is_lid() {
                            let resolved = lid::resolve(
                                &source.chat,
                                source.sender_alt.as_ref().or(source.recipient_alt.as_ref()),
                                &map,
                            );
                            if resolved.is_pn() {
                                sink.merge_chat(
                                    &source.chat.to_non_ad().to_string(),
                                    &resolved.to_string(),
                                )
                                .await;
                            }
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
                    EventKind::DeviceListUpdate,
                    EventKind::ContactNumberChanged,
                    EventKind::GroupUpdate,
                    EventKind::Disconnected,
                    EventKind::StreamReplaced,
                    EventKind::TemporaryBan,
                    EventKind::ConnectFailure,
                    EventKind::IncomingCall,
                    EventKind::MissedCall,
                    EventKind::CallEndedElsewhere,
                    EventKind::PictureUpdate,
                    EventKind::UserAboutUpdate,
                    EventKind::ContactUpdated,
                    EventKind::ContactUpdate,
                    EventKind::PushNameUpdate,
                    EventKind::PinUpdate,
                    EventKind::MuteUpdate,
                    EventKind::ArchiveUpdate,
                    EventKind::StarUpdate,
                    EventKind::MarkChatAsReadUpdate,
                    EventKind::DeleteChatUpdate,
                    EventKind::ClearChatUpdate,
                    EventKind::DeleteMessageForMeUpdate,
                ],
                {
                    let sink = sink.clone();
                    let instance = instance.clone();
                    let was_connected = Arc::clone(&was_connected);
                    move |event, client| {
                        let sink = sink.clone();
                        let instance = instance.clone();
                        let was_connected = Arc::clone(&was_connected);
                        async move {
                            let resolver = Resolver::new(client);
                            handle_event(&sink, &resolver, &instance, &was_connected, &event).await;
                        }
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
        let resolver = Arc::new(Resolver::new(Arc::clone(&client)));
        let handle = bot.spawn();
        self.running().insert(
            instance,
            Running {
                generation,
                client,
                resolver,
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
        let resolver = self.resolver_of(instance)?;
        let to = convert::parse_jid(&req.chat)?;
        // Stored under the phone number; delivered to what the app sent.
        let chat = resolver.resolve(&to, None).await.to_string();
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
        let details = media::Details {
            file_name: req.file_name.as_deref(),
            seconds: req.seconds,
            voice_note: req.voice_note,
        };
        let mut message = media::build_message_with(
            req.kind,
            &uploaded,
            &req.mime,
            req.caption.as_deref(),
            &details,
        );
        let own = client
            .persistence_manager()
            .get_device_snapshot()
            .pn
            .as_ref()
            .map(|jid| jid.to_non_ad());
        let own_jid = own.as_ref().map(ToString::to_string);
        let mut quoted = None;
        if let Some(quoted_id) = req.quoted_id.as_deref() {
            let stored = sink.find_message(&chat, quoted_id).await;
            message.set_context_info(quote_context(
                &chat,
                quoted_id,
                stored.as_ref(),
                own_jid.as_deref(),
            ));
            quoted = Some(quoted_meta(quoted_id, stored.as_ref(), own_jid.as_deref()));
        }
        let raw = message.encode_to_vec();
        let mut extras = convert::extras(&message);
        let result = client.send_message(to, message).await.map_err(failed)?;

        let timestamp_ms = now_ms();
        let sender = own
            .as_ref()
            .and_then(|jid| convert::group_sender(&chat, jid));
        extras.raw_proto = Some(raw);
        extras.meta.status = Some(DeliveryStatus::Sent);
        extras.meta.quoted = quoted;
        sink.emit_full(
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
            extras,
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
        let resolver = self.resolver_of(instance)?;
        let to = convert::parse_jid(&req.chat)?;
        let wire_chat = to.to_non_ad().to_string();
        let chat = resolver.resolve(&to, None).await.to_string();
        let own = client
            .persistence_manager()
            .get_device_snapshot()
            .pn
            .as_ref()
            .map(|jid| jid.to_non_ad());
        let own_jid = own.as_ref().map(ToString::to_string);

        let stored = sink.find_message(&chat, &req.message_id).await;
        let author = reaction::target_author(&req, stored.as_ref(), own_jid.as_deref());
        let key =
            reaction::message_key(&wire_chat, &req.message_id, req.from_me, author.as_deref());
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
        let resolver = self.resolver_of(instance)?;
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
        let unmapped: Vec<&Jid> = metadata
            .iter()
            .flat_map(|g| g.participants.iter())
            .filter(|p| p.phone_number.is_none())
            .map(|p| &p.jid)
            .collect();
        let map = resolver.prefetch(&unmapped).await;
        let mut infos = Vec::with_capacity(metadata.len());
        for group in &metadata {
            let mut info = groups::group_info(
                &group.id,
                &group.subject,
                group.participants.iter().map(|p| {
                    (
                        groups::participant_jid(&p.jid, p.phone_number.as_ref(), &map),
                        p.is_admin(),
                    )
                }),
            );
            info.description = group
                .description
                .as_deref()
                .map(str::trim)
                .filter(|d| !d.is_empty())
                .map(str::to_owned);
            info.announce = group.is_announcement;
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
        let resolver = self.resolver_of(instance)?;
        let to = convert::parse_jid(&req.chat)?;
        let chat = resolver.resolve(&to, None).await.to_string();
        let own = client
            .persistence_manager()
            .get_device_snapshot()
            .pn
            .as_ref()
            .map(|jid| jid.to_non_ad());

        let mut meta = MessageMeta {
            status: Some(DeliveryStatus::Sent),
            ..Default::default()
        };
        let result = match req.quoted_id.as_deref().filter(|id| !id.is_empty()) {
            None => client.send_text(to, req.text.clone()).await,
            Some(quoted_id) => {
                let quoted = sink.find_message(&chat, quoted_id).await;
                let own_jid = own.as_ref().map(ToString::to_string);
                meta.quoted = Some(quoted_meta(quoted_id, quoted.as_ref(), own_jid.as_deref()));
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
        sink.emit_full(
            WaMessage {
                id: result.message_id.clone(),
                chat,
                sender,
                from_me: true,
                timestamp_ms,
                body: MessageBody::Text(req.text),
                reactions: Vec::new(),
            },
            MessageExtras {
                meta,
                ..Default::default()
            },
        )
        .await;
        Ok(Sent {
            message_id: result.message_id,
            timestamp_ms,
        })
    }
}

/// A delivered, read or played receipt. One-to-one: the ticks of my messages
/// move. Group: the receipt is one member's, and the ticks move once every
/// other member got that far. `ReadSelf`: I read their messages on another
/// of my devices, which clears the unread count and marks their messages
/// read in the apps, never mine. Receipts from my own devices are ignored.
async fn on_receipt(sink: &EventSink, resolver: &Resolver, receipt: &Receipt) {
    let client = resolver.client();
    if receipt.message_ids.is_empty() {
        return;
    }
    let source = &receipt.source;
    let chat_jid = resolver
        .resolve(&source.chat, source.sender_alt.as_ref())
        .await;
    let chat = chat_jid.to_string();
    let ids: Vec<String> = receipt
        .message_ids
        .iter()
        .map(ToString::to_string)
        .collect();
    if receipt.r#type == ReceiptType::ReadSelf {
        sink.mark_chat_read(&chat).await;
        let ids = sink.not_mine(&chat, ids).await;
        if !ids.is_empty() {
            sink.publish(GatewayEvent::Receipt {
                chat,
                ids,
                kind: ReceiptKind::Read,
            });
        }
        return;
    }
    let Some(status) = receipt_status(&receipt.r#type) else {
        return;
    };
    let device = client.persistence_manager().get_device_snapshot();
    let own: Vec<&Jid> = device.pn.iter().chain(device.lid.iter()).collect();
    let from_me = is_own(&source.sender, &own)
        || source
            .sender_alt
            .as_ref()
            .is_some_and(|alt| is_own(alt, &own));
    // My other devices ack what I send; that says nothing about the
    // recipient (a chat with myself excepted).
    if from_me && !is_own(&chat_jid, &own) {
        return;
    }
    if chat_jid.is_group() {
        let group = client.groups().query_info(&source.chat).await;
        if let Err(e) = &group {
            log::debug!("group of a receipt unknown, ticks stay grey: {e}");
        }
        let group = group.ok();
        let members = group
            .as_deref()
            .map(|g| members_besides_me(&g.participants, &own));
        let sender_jids: Vec<&Jid> = std::iter::once(&source.sender)
            .chain(source.sender_alt.iter())
            .collect();
        let map = resolver.prefetch(&sender_jids).await;
        let participant = participant_key(
            &source.sender,
            source.sender_alt.as_ref(),
            group.as_deref(),
            &map,
        );
        sink.group_receipt(&chat, participant, ids, status, members)
            .await;
    } else {
        sink.set_status(&chat, ids, status).await;
    }
}

async fn handle_event(
    sink: &EventSink,
    resolver: &Resolver,
    instance: &InstanceId,
    was_connected: &AtomicBool,
    event: &Event,
) {
    match event {
        Event::Receipt(receipt) => on_receipt(sink, resolver, receipt).await,
        Event::Presence(update) => sink.publish(GatewayEvent::Presence {
            jid: resolver.resolve(&update.from, None).await.to_string(),
            available: !update.unavailable,
            last_seen_ms: update.last_seen.map(|t| t.timestamp_millis()),
        }),
        Event::ChatPresence(update) => sink.publish(GatewayEvent::Typing {
            chat: resolver
                .resolve(&update.source.chat, update.source.sender_alt.as_ref())
                .await
                .to_string(),
            jid: resolver
                .resolve(&update.source.sender, update.source.sender_alt.as_ref())
                .await
                .to_string(),
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
            let now = now_ms();
            let own_pn = resolver
                .client()
                .persistence_manager()
                .get_device_snapshot()
                .pn
                .as_ref()
                .map(|jid| jid.to_non_ad().to_string());
            // Look up every LID the sync names before the blocking walk.
            let lids = {
                let lazy = lazy.clone();
                tokio::task::spawn_blocking(move || history::lids(&lazy)).await
            };
            let map = match lids {
                Ok(Ok(lids)) => resolver.prefetch(&lids.iter().collect::<Vec<_>>()).await,
                _ => LidMap::default(),
            };
            let drained = tokio::task::spawn_blocking(move || {
                history::drain(&lazy, now, own_pn.as_deref(), &map)
            })
            .await;
            match drained {
                Ok(Ok(drained)) => {
                    sink.set_push_names(drained.push_names).await;
                    sink.import_statuses(drained.statuses).await;
                    sink.import_history(drained.chats, progress).await;
                    lid::sweep(resolver, sink).await;
                }
                Ok(Err(e)) => log::warn!("{instance}: history sync unreadable: {e}"),
                Err(e) => log::warn!("{instance}: history sync task failed: {e}"),
            }
        }
        // The library learns LID <-> phone-number pairs from these; there is
        // no dedicated event, so look at the stored LID chats again.
        Event::DeviceListUpdate(_) | Event::ContactNumberChanged(_) => {
            lid::sweep(resolver, sink).await;
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
        Event::IncomingCall(call) => {
            let map = resolver.prefetch(&[&call.from]).await;
            sink.calls().on_incoming(call, &map);
        }
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
        other => app_state::handle(sink, resolver, other).await,
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
            let chat_key = self
                .resolver_of(&instance)?
                .resolve(&chat, None)
                .await
                .to_string();
            if !chat.is_group() {
                let ids: Vec<&str> = req.ids.iter().map(String::as_str).collect();
                client
                    .mark_as_read(&chat, None, &ids)
                    .await
                    .map_err(failed)?;
                sink.mark_chat_read(&chat_key).await;
                return Ok(());
            }
            // A group receipt names the author of the messages it covers.
            // Older apps leave it out: take it from the stored messages.
            let batches = match sender {
                Some(sender) => vec![(Some(sender), req.ids)],
                None => {
                    let mut authors = Vec::with_capacity(req.ids.len());
                    for id in &req.ids {
                        let stored = sink.find_message(&chat_key, id).await;
                        authors.push(stored.and_then(|m| m.sender));
                    }
                    split_by_author(&req.ids, &authors)
                        .into_iter()
                        .map(|(author, ids)| {
                            let author = author.as_deref().map(convert::parse_jid).transpose()?;
                            Ok((author, ids))
                        })
                        .collect::<Result<Vec<_>, DriverError>>()?
                }
            };
            // The gateway keeps authors by phone number; a LID group wants
            // their LID.
            let group = client.groups().query_info(&chat).await.ok();
            for (author, ids) in batches {
                let author = match (author, group.as_deref()) {
                    (Some(a), Some(g)) => Some(g.phone_device_jid_into_lid(a)),
                    (author, _) => author,
                };
                let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
                client
                    .mark_as_read(&chat, author.as_ref(), &ids)
                    .await
                    .map_err(failed)?;
            }
            sink.mark_chat_read(&chat_key).await;
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

    fn lookup_users(
        &self,
        instance: &InstanceId,
        jids: Vec<String>,
    ) -> BoxFuture<'_, Result<Vec<WaUser>, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let parsed = jids
                .iter()
                .map(|jid| convert::parse_jid(jid))
                .collect::<Result<Vec<_>, _>>()?;
            let (client, _) = self.client_of(&instance)?;
            let found = client
                .contacts()
                .is_on_whatsapp(&parsed)
                .await
                .map_err(contact_failed)?;
            let answers = found.into_iter().map(|r| {
                let jid = r.pn_jid.unwrap_or(r.jid).to_non_ad().to_string();
                let verified_name = r.verified_name.and_then(|v| v.name);
                (jid, r.is_registered, r.is_business, verified_name)
            });
            Ok(convert::lookup_answers(&jids, answers))
        })
    }

    fn call_backend(&self, instance: &InstanceId) -> Result<Arc<dyn CallBackend>, DriverError> {
        let (client, _) = self.client_of(instance)?;
        Ok(Arc::new(WaCalls::new(client)))
    }

    fn edit_message(
        &self,
        instance: &InstanceId,
        req: EditMessage,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let to = convert::parse_jid(&req.chat)?;
            let (client, sink) = self.client_of(&instance)?;
            let chat_key = self
                .resolver_of(&instance)?
                .resolve(&to, None)
                .await
                .to_string();
            let stored = sink.find_message(&chat_key, &req.message_id).await;
            let content = match stored.map(|m| m.body) {
                // A caption edit resends the media message with the new caption.
                Some(MessageBody::Media { kind, .. }) => app_state::caption_edit(kind, &req.text)
                    .ok_or_else(|| {
                    DriverError::BadRequest("this message cannot be edited".to_owned())
                })?,
                _ => wa::Message {
                    conversation: Some(req.text.clone()),
                    ..Default::default()
                },
            };
            client
                .edit_message(to, req.message_id, content)
                .await
                .map(drop)
                .map_err(failed)
        })
    }

    fn revoke_message(
        &self,
        instance: &InstanceId,
        req: RevokeMessage,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let to = convert::parse_jid(&req.chat)?;
            let revoke_type = match req.sender.as_deref() {
                Some(sender) => RevokeType::Admin {
                    original_sender: convert::parse_jid(sender)?,
                },
                None => RevokeType::Sender,
            };
            let (client, _) = self.client_of(&instance)?;
            client
                .revoke_message(to, req.message_id, revoke_type)
                .await
                .map_err(failed)
        })
    }

    fn delete_for_me(
        &self,
        instance: &InstanceId,
        target: MessageRef,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let chat = convert::parse_jid(&target.chat)?;
            let author = author_jid(&target)?;
            let (client, _) = self.client_of(&instance)?;
            client
                .chat_actions()
                .delete_message_for_me(
                    &chat,
                    author.as_ref(),
                    &target.id,
                    target.from_me,
                    true,
                    Some(target.timestamp_ms / 1000),
                )
                .await
                .map_err(failed)
        })
    }

    fn star_message(
        &self,
        instance: &InstanceId,
        target: MessageRef,
        starred: bool,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let chat = convert::parse_jid(&target.chat)?;
            let author = author_jid(&target)?;
            let (client, _) = self.client_of(&instance)?;
            let actions = client.chat_actions();
            if starred {
                actions
                    .star_message(&chat, author.as_ref(), &target.id, target.from_me)
                    .await
            } else {
                actions
                    .unstar_message(&chat, author.as_ref(), &target.id, target.from_me)
                    .await
            }
            .map_err(failed)
        })
    }

    fn chat_action(
        &self,
        instance: &InstanceId,
        req: ChatAction,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let chat = convert::parse_jid(&req.chat)?;
            let (client, _) = self.client_of(&instance)?;
            let actions = client.chat_actions();
            match req.action {
                ChatActionKind::Archive { archived: true } => {
                    actions.archive_chat(&chat, None).await
                }
                ChatActionKind::Archive { archived: false } => {
                    actions.unarchive_chat(&chat, None).await
                }
                ChatActionKind::Pin { pinned: true } => actions.pin_chat(&chat).await,
                ChatActionKind::Pin { pinned: false } => actions.unpin_chat(&chat).await,
                ChatActionKind::Mute { until_ms: None } => actions.mute_chat(&chat).await,
                ChatActionKind::Mute {
                    until_ms: Some(until),
                } => actions.mute_chat_until(&chat, until).await,
                ChatActionKind::Unmute => actions.unmute_chat(&chat).await,
                ChatActionKind::MarkRead { read } => {
                    actions.mark_chat_as_read(&chat, read, None).await
                }
            }
            .map_err(failed)
        })
    }

    fn mark_played(
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
            let (client, _) = self.client_of(&instance)?;
            // As for read receipts: a LID group wants the author's LID.
            let sender = match sender {
                Some(sender) if chat.is_group() => match client.groups().query_info(&chat).await {
                    Ok(group) => Some(group.phone_device_jid_into_lid(sender)),
                    Err(_) => Some(sender),
                },
                sender => sender,
            };
            let ids: Vec<&str> = req.ids.iter().map(String::as_str).collect();
            client
                .mark_as_played(&chat, sender.as_ref(), &ids)
                .await
                .map_err(failed)
        })
    }

    fn profiles(
        &self,
        instance: &InstanceId,
        jids: Vec<String>,
    ) -> BoxFuture<'_, Result<Vec<FetchedProfile>, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let parsed = jids
                .iter()
                .map(|jid| convert::parse_jid(jid))
                .collect::<Result<Vec<_>, _>>()?;
            let (client, _) = self.client_of(&instance)?;
            let (groups, users): (Vec<&Jid>, Vec<&Jid>) = parsed.iter().partition(|j| j.is_group());
            let users: Vec<Jid> = users.into_iter().cloned().collect();
            let mut out = Vec::with_capacity(parsed.len());
            if !users.is_empty() {
                let infos = client
                    .contacts()
                    .get_user_info(&users)
                    .await
                    .map_err(contact_failed)?;
                for jid in &users {
                    let key = jid.to_non_ad();
                    let info = infos
                        .iter()
                        .find(|(k, _)| k.to_non_ad() == key)
                        .map(|(_, v)| v);
                    out.push(FetchedProfile {
                        jid: key.to_string(),
                        about: info
                            .and_then(|i| i.status.clone())
                            .filter(|s| !s.is_empty()),
                        picture_id: info
                            .and_then(|i| i.picture_id.clone())
                            .filter(|p| !p.is_empty()),
                        business: info.is_some_and(|i| i.is_business),
                        verified_name: info
                            .and_then(|i| i.verified_name.as_ref())
                            .and_then(|v| v.name.clone()),
                    });
                }
            }
            for group in groups {
                let picture = client
                    .contacts()
                    .get_profile_picture(group, true)
                    .await
                    .map_err(contact_failed)?;
                out.push(FetchedProfile {
                    jid: group.to_non_ad().to_string(),
                    picture_id: picture.map(|p| p.id).filter(|id| !id.is_empty()),
                    ..Default::default()
                });
            }
            Ok(out)
        })
    }

    fn fetch_picture(
        &self,
        instance: &InstanceId,
        jid: String,
        preview: bool,
        known_id: Option<String>,
    ) -> BoxFuture<'_, Result<PictureFetch, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let jid = convert::parse_jid(&jid)?;
            let (client, _) = self.client_of(&instance)?;
            let Some(found) = client
                .contacts()
                .get_profile_picture(&jid, preview)
                .await
                .map_err(contact_failed)?
                .filter(|p| !p.id.is_empty() && !p.url.is_empty())
            else {
                return Ok(PictureFetch::None);
            };
            if known_id.as_deref() == Some(found.id.as_str()) {
                return Ok(PictureFetch::Unchanged);
            }
            let bytes = picture::fetch(found.url).await?;
            Ok(PictureFetch::New {
                id: found.id,
                bytes,
            })
        })
    }

    /// whatsapp-rust sends a status read to `status@broadcast` with the
    /// author as `participant` (and adds the author's number when it is a
    /// LID), like WhatsApp Web.
    fn mark_status_viewed(
        &self,
        instance: &InstanceId,
        batches: Vec<StatusReceipt>,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let (client, _) = self.client_of(&instance)?;
            let chat = convert::parse_jid(rovsti_wa_proto::STATUS_BROADCAST)?;
            for batch in batches {
                let author = convert::parse_jid(&batch.participant)?;
                let ids: Vec<&str> = batch.ids.iter().map(String::as_str).collect();
                client
                    .mark_as_read(&chat, Some(&author), &ids)
                    .await
                    .map_err(failed)?;
            }
            Ok(())
        })
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

    fn server_error(code: u16, backoff: Option<u32>) -> ContactError {
        ContactError::Iq(IqError::ServerError {
            code,
            text: "rate-overlimit".into(),
            error_type: None,
            backoff,
        })
    }

    #[test]
    fn whatsapp_429_is_rate_limited() {
        assert_eq!(
            contact_failed(server_error(429, Some(30))),
            DriverError::RateLimited {
                retry_after_s: Some(30)
            }
        );
        assert_eq!(
            contact_failed(server_error(429, None)),
            DriverError::RateLimited {
                retry_after_s: None
            }
        );
        assert!(matches!(
            contact_failed(server_error(500, None)),
            DriverError::Failed(_)
        ));
        assert!(matches!(
            contact_failed(ContactError::Iq(IqError::Timeout)),
            DriverError::Failed(_)
        ));
        assert!(matches!(
            contact_failed(ContactError::InvalidJid("x".into())),
            DriverError::Failed(_)
        ));
    }

    #[test]
    fn a_wrapped_429_is_found_by_its_text() {
        let text = server_error(429, Some(5)).to_string();
        assert!(text.contains("code=429"), "{text}");
        assert_eq!(
            rate_limited_text(&format!("usync: {text}")),
            Some(DriverError::RateLimited {
                retry_after_s: None
            })
        );
        assert_eq!(rate_limited_text("code=500, text='x'"), None);
    }

    #[test]
    fn only_the_other_sides_receipts_tick_my_messages() {
        use DeliveryStatus::*;
        assert_eq!(receipt_status(&ReceiptType::Delivered), Some(Delivered));
        assert_eq!(receipt_status(&ReceiptType::Read), Some(Read));
        assert_eq!(receipt_status(&ReceiptType::Played), Some(Played));
        // Read or played by me on another device: no ticks on my messages.
        assert_eq!(receipt_status(&ReceiptType::ReadSelf), None);
        assert_eq!(receipt_status(&ReceiptType::PlayedSelf), None);
        assert_eq!(receipt_status(&ReceiptType::Sender), None);
        assert_eq!(receipt_status(&ReceiptType::Retry), None);
    }

    fn jid(s: &str) -> Jid {
        convert::parse_jid(s).unwrap()
    }

    #[test]
    fn my_devices_are_not_group_members() {
        let pn = jid("90500@s.whatsapp.net");
        let lid = jid("777@lid");
        let own = [&pn, &lid];
        assert!(is_own(&jid("90500:3@s.whatsapp.net"), &own));
        assert!(is_own(&jid("777:2@lid"), &own));
        assert!(!is_own(&jid("4915@s.whatsapp.net"), &own));
        let members = [jid("777@lid"), jid("1@lid"), jid("2@lid")];
        assert_eq!(members_besides_me(&members, &own), 2);
        assert_eq!(members_besides_me(&members, &[]), 3);
    }

    #[test]
    fn a_member_counts_once_by_number_or_lid() {
        use whatsapp_rust::wacore::types::message::AddressingMode;
        let lid = jid("111:4@lid");
        let pn = jid("4915@s.whatsapp.net");
        assert_eq!(
            participant_key(&lid, Some(&pn), None, &LidMap::default()),
            "4915@s.whatsapp.net"
        );
        let none = LidMap::default();
        assert_eq!(participant_key(&lid, None, None, &none), "111@lid");
        let known = LidMap::from_entries([("111", "4915")]);
        assert_eq!(
            participant_key(&lid, None, None, &known),
            "4915@s.whatsapp.net"
        );
        let mut group = WaGroupInfo::new(Vec::new(), AddressingMode::Lid);
        group.add_participants([(&jid("111@lid"), Some(&pn))]);
        assert_eq!(
            participant_key(&lid, None, Some(&group), &none),
            "4915@s.whatsapp.net"
        );
        assert_eq!(
            participant_key(&jid("4915:1@s.whatsapp.net"), None, Some(&group), &none),
            "4915@s.whatsapp.net"
        );
    }

    #[test]
    fn group_reads_split_by_author() {
        let ids: Vec<String> = ["a", "b", "c", "d"].map(String::from).into();
        let ada = Some("1@s.whatsapp.net".to_owned());
        let bob = Some("2@s.whatsapp.net".to_owned());
        let split = split_by_author(&ids, &[ada.clone(), bob.clone(), ada.clone(), None]);
        assert_eq!(
            split,
            [
                (ada, vec!["a".to_owned(), "c".to_owned()]),
                (bob, vec!["b".to_owned()]),
                (None, vec!["d".to_owned()]),
            ]
        );
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
