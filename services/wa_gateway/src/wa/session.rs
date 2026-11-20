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
use rosty_wa_proto::meta::body_preview;
use rosty_wa_proto::{
    ChatAction, ChatActionKind, ChatState, DeliveryStatus, EditMessage, ForwardMessage,
    GatewayEvent, GroupInfo, IdentitySource, InstanceState, InstanceStatus, LinkPreview,
    LinkRequest, MarkRead, MessageBody, MessageMeta, Quoted, Reaction, RevokeMessage, SendReaction,
    SendText, Sent, TypingState, WaMessage, WaUser, event::ReceiptKind,
};
use whatsapp_rust::RevokeType;
use whatsapp_rust::features::GroupMetadata;
use whatsapp_rust::pair_code::PairCodeOptions;
use whatsapp_rust::prelude::*;
use whatsapp_rust::send::SendOptions;
use whatsapp_rust::upload::UploadOptions;
use whatsapp_rust::wacore::client::context::GroupInfo as WaGroupInfo;
use whatsapp_rust::wacore::stanza::groups::GroupNotificationAction;
use whatsapp_rust::wacore::types::events::{ConnectFailureReason, Receipt};
use whatsapp_rust::wacore::types::message::AddressingMode;
use whatsapp_rust::wacore::types::presence::{ChatPresence, ChatPresenceMedia, ReceiptType};
use whatsapp_rust::wacore_binary::JidExt as _;
use whatsapp_rust::waproto::buffa::Message as _;
use whatsapp_rust::{ContactError, IqError};

use super::calls::{self, CallBackend, WaCalls};
use super::convert;
use super::lid::{self, LidMap, Pair, Resolver};
use super::{app_state, groups, history, media, picture, reaction, status};
use crate::driver::{
    DriverError, EventSink, MessageRef, PictureFetch, SendMedia, StatusReceipt, TimerChange,
    WaDriver,
};
use crate::now_ms;
use crate::store::{
    FetchedProfile, GroupReceipt, HISTORY_ROWS_PER_TX, HistoryImport, InstanceId, MessageExtras,
    NewPair, ProfileChange, WireIds,
};

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
    /// Pair new devices asking for the full chat history
    /// ([`history::device_props`]).
    full_history: bool,
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
        own_lid: None,
        epoch: None,
        account_epoch: None,
        history: None,
    })
}

/// The `connected` state of an account: its phone-number JID, its LID and
/// its push name, as the paired device knows them.
fn connected(
    instance: &InstanceId,
    pn: Option<&Jid>,
    lid: Option<&Jid>,
    push_name: &str,
) -> GatewayEvent {
    let bare = |jid: &Jid| jid.to_non_ad().to_string();
    GatewayEvent::State(InstanceStatus {
        instance_id: instance.to_string(),
        state: InstanceState::Connected,
        own_jid: pn.map(bare),
        push_name: Some(push_name.to_owned()).filter(|n| !n.is_empty()),
        own_lid: lid.map(bare),
        epoch: None,
        account_epoch: None,
        history: None,
    })
}

/// The members of a group as its metadata lists them.
fn members(group: &GroupMetadata) -> Vec<groups::Member<'_>> {
    group
        .participants
        .iter()
        .map(|p| groups::Member {
            jid: &p.jid,
            phone_number: p.phone_number.as_ref(),
            lid: p.lid.as_ref(),
            admin: p.is_admin(),
        })
        .collect()
}

/// The rows to record for `pairs` learned now from `source`.
fn pairs_from(pairs: &[Pair], source: IdentitySource) -> Vec<NewPair> {
    pairs.iter().map(|pair| pair.learned(source)).collect()
}

/// Who a message I send is for and from, as the apps and the store get it.
struct Outgoing {
    /// The chat by phone number when known.
    chat: String,
    chat_alt: Option<String>,
    /// The chat as the app addressed it, bare: what WhatsApp is sent and
    /// what a message key names. Not stored with the message: which id the
    /// library puts on the wire for it is its own decision.
    wire_chat: String,
    /// Me, for groups.
    sender: Option<String>,
    sender_alt: Option<String>,
    own_jid: Option<String>,
}

impl Outgoing {
    async fn new(client: &Client, resolver: &Resolver, to: &Jid) -> Self {
        let (chat, chat_alt) = resolver.resolve_with_alt(to, None).await;
        let chat = chat.to_string();
        let device = client.persistence_manager().get_device_snapshot();
        let own = device.pn.as_ref().map(|jid| jid.to_non_ad());
        let sender = own
            .as_ref()
            .and_then(|jid| convert::group_sender(&chat, jid));
        Self {
            sender_alt: sender
                .as_ref()
                .and(device.lid.as_ref())
                .map(|lid| lid.to_non_ad().to_string()),
            sender,
            chat,
            chat_alt,
            wire_chat: to.to_non_ad().to_string(),
            own_jid: own.as_ref().map(ToString::to_string),
        }
    }

    /// The message as stored and announced once WhatsApp took it.
    fn message(&self, id: String, timestamp_ms: i64, body: MessageBody) -> WaMessage {
        WaMessage {
            id,
            chat: self.chat.clone(),
            sender: self.sender.clone(),
            from_me: true,
            timestamp_ms,
            body,
            reactions: Vec::new(),
            chat_alt: self.chat_alt.clone(),
            sender_alt: self.sender_alt.clone(),
        }
    }
}

/// This account's own ids, bare: its devices are nobody's group member.
fn own_ids(own: &[&Jid]) -> Vec<String> {
    own.iter().map(|jid| jid.to_non_ad().to_string()).collect()
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

/// The chat to address a read receipt to: the id the messages arrived
/// under when the gateway kept it (a LID while the app knows the chat by its
/// number), else the chat the app named.
fn receipt_chat(requested: &Jid, wire_chat: Option<&str>) -> Jid {
    wire_chat
        .and_then(|id| id.parse::<Jid>().ok())
        .filter(|jid| jid.is_lid() || jid.is_pn())
        .unwrap_or_else(|| requested.clone())
}

/// The author a group receipt or message key names for a stored message:
/// the id it arrived under when the gateway kept it, else `known` (what the
/// app named, or the stored author).
fn wire_author(wire: &WireIds, known: Option<String>) -> Option<String> {
    wire.sender.clone().or(known)
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
/// when we have it, `wire` the ids it arrived under and `own_jid` our
/// account. The quoted author is named as WhatsApp named them (a LID in a
/// LID-addressed group), not as the apps know them.
pub fn quote_context(
    chat: &str,
    quoted_id: &str,
    quoted: Option<&WaMessage>,
    wire: &WireIds,
    own_jid: Option<&str>,
) -> wa::ContextInfo {
    let participant = match quoted {
        Some(m) if m.from_me => wire.sender.clone().or_else(|| own_jid.map(str::to_owned)),
        Some(m) if convert::is_group(chat) => wire_author(wire, m.sender.clone()),
        // One-to-one chats are quoted by the other side's JID.
        Some(_) => wire.chat.clone(),
        None => None,
    }
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

/// The directories under an instance's own that cache what its account
/// downloaded: decrypted media and profile pictures
/// (`Config::media_dir`, `Config::pictures_dir`), and the picture bytes a
/// people job holds for the device (`Config::people_job_dir`).
const ACCOUNT_CACHE_DIRS: [&str; 3] = ["media", "pictures", "people-job"];

/// What the handlers of one instance need to tell that another account was
/// linked, and to clean up after it.
#[derive(Clone)]
struct Account {
    /// The account's cache directories.
    cache_dirs: Arc<[PathBuf]>,
    full_history: bool,
}

impl Account {
    /// Checks the account that just paired or connected against the one the
    /// instance holds data for ([`EventSink::account_linked`]); the cached
    /// files of another account are deleted before the apps hear of it.
    async fn check(&self, sink: &EventSink, pn: Option<&Jid>, paired: bool) {
        let pn = pn.map(|jid| jid.to_non_ad().to_string());
        let paired = paired.then_some(self.full_history);
        sink.account_linked(pn, paired, async || {
            for dir in self.cache_dirs.iter() {
                match tokio::fs::remove_dir_all(dir).await {
                    Ok(()) => {}
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                    Err(e) => {
                        log::warn!("{}: cannot delete {}: {e}", sink.instance(), dir.display())
                    }
                }
            }
        })
        .await;
    }
}

impl WaRuntime {
    /// `full_history`: pair new devices asking WhatsApp for the full chat
    /// history instead of the recent messages only.
    pub fn new(data_dir: PathBuf, full_history: bool) -> Self {
        Self(Arc::new(Shared {
            data_dir,
            full_history,
            running: Mutex::new(HashMap::new()),
            next_generation: AtomicU64::new(1),
        }))
    }

    fn instance_dir(&self, instance: &InstanceId) -> PathBuf {
        self.0.data_dir.join("instances").join(instance.as_str())
    }

    /// The account context of `instance`.
    fn account(&self, instance: &InstanceId) -> Account {
        let dir = self.instance_dir(instance);
        Account {
            cache_dirs: ACCOUNT_CACHE_DIRS.iter().map(|d| dir.join(d)).collect(),
            full_history: self.0.full_history,
        }
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
        let account = self.account(&instance);
        let mut builder = Bot::builder().with_backend(backend);
        // Sent when a device registers, so this decides the history of the
        // next pairing, not of an account that is already linked.
        if let Some(props) = history::device_props(account.full_history) {
            builder = builder.with_device_props(props);
        }
        let mut builder = builder
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
                let account = account.clone();
                move |client| {
                    let sink = sink.clone();
                    let instance = instance.clone();
                    let account = account.clone();
                    was_connected.store(true, Ordering::Relaxed);
                    let device = client.persistence_manager().get_device_snapshot();
                    let state = connected(
                        &instance,
                        device.pn.as_ref(),
                        device.lid.as_ref(),
                        &device.push_name,
                    );
                    let own = device
                        .pn
                        .as_ref()
                        .zip(device.lid.as_ref())
                        .and_then(|(pn, lid)| Pair::of(lid, pn));
                    async move {
                        // Before the state names the new number: the rows of
                        // another account must be gone first.
                        account.check(&sink, device.pn.as_ref(), false).await;
                        sink.emit(state).await;
                        tokio::spawn(async move {
                            // My own two ids are a pair like any other.
                            if let Some(own) = own {
                                sink.learn_pairs(vec![own.learned(IdentitySource::Other)])
                                    .await;
                            }
                            // The library learns pairs without telling: take
                            // over its table, then fold whatever is stored
                            // under a LID whose phone number is known into
                            // that number.
                            lid::seed(&client, &sink).await;
                            let resolver = Resolver::recording(client, sink.clone());
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
                        // What the envelope itself states comes first: a pair
                        // that is new moves whatever was stored under the LID
                        // to the number before this message is stored.
                        let stated = convert::envelope_pairs(&ctx.info);
                        if !stated.is_empty() {
                            sink.learn_pairs(pairs_from(&stated, IdentitySource::PeerMessage))
                                .await;
                        }
                        // Then the mappings the library keeps, so a message
                        // without a phone-number alt still lands in the
                        // phone-number chat, and the people it mentions and
                        // quotes are named like everybody else.
                        let resolver = Resolver::recording(Arc::clone(&ctx.client), sink.clone());
                        let named = convert::context_jids(&ctx.message);
                        let mut jids = lid::source_jids(&ctx.info.source);
                        jids.extend(ctx.info.peer_recipient_pn.iter());
                        jids.extend(named.iter());
                        let mut map = resolver.prefetch(&jids).await;
                        for pair in &stated {
                            map.insert_new(pair);
                        }
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
                        // A message of a chat with a timer names the timer
                        // and when it was set.
                        if let Some((seconds, at_ms)) = convert::timer_hint(&ctx.message) {
                            let to = convert::addressed(
                                &ctx.info.source,
                                ctx.info.peer_recipient_pn.as_ref(),
                                &map,
                            );
                            sink.learn_chat_timer(&to.chat, seconds, at_ms).await;
                        }
                        // A business names itself in the envelope of its
                        // messages; no profile query is needed for that.
                        if let Some(name) = convert::verified_name(&ctx.info) {
                            let to = convert::addressed(
                                &ctx.info.source,
                                ctx.info.peer_recipient_pn.as_ref(),
                                &map,
                            );
                            let author = to.sender.unwrap_or(to.chat);
                            sink.change_profile(
                                &author,
                                ProfileChange::VerifiedName(name.to_owned()),
                            )
                            .await;
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
                    EventKind::PairSuccess,
                    EventKind::UndecryptableMessage,
                ],
                {
                    let sink = sink.clone();
                    let instance = instance.clone();
                    let was_connected = Arc::clone(&was_connected);
                    let account = account.clone();
                    move |event, client| {
                        let sink = sink.clone();
                        let instance = instance.clone();
                        let was_connected = Arc::clone(&was_connected);
                        let account = account.clone();
                        async move {
                            let resolver = Resolver::recording(client, sink.clone());
                            let context = Handling {
                                instance: &instance,
                                was_connected: &was_connected,
                                account: &account,
                            };
                            handle_event(&sink, &resolver, &context, &event).await;
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
        let resolver = Arc::new(Resolver::recording(Arc::clone(&client), sink.clone()));
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
        let out = Outgoing::new(&client, &resolver, &to).await;
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
            thumbnail: req.thumbnail.as_deref(),
            width: req.width,
            height: req.height,
            waveform: req.waveform.as_deref(),
            animated: req.animated,
            view_once: req.view_once,
        };
        let mut message = media::build_message_with(
            req.kind,
            &uploaded,
            &req.mime,
            req.caption.as_deref(),
            &details,
        );
        let own_jid = out.own_jid.as_deref();
        let mut quoted = None;
        if let Some(quoted_id) = req.quoted_id.as_deref() {
            let stored = sink.find_message(&out.chat, quoted_id).await;
            let wire = sink.wire_ids(&out.chat, quoted_id).await;
            message.set_context_info(quote_context(
                &out.chat,
                quoted_id,
                stored.as_ref(),
                &wire,
                own_jid,
            ));
            quoted = Some(quoted_meta(quoted_id, stored.as_ref(), own_jid));
        }
        // After the reply context: that is set on the media message itself,
        // which the wrapper would hide.
        if req.view_once {
            message = media::wrap_view_once(message);
        }
        let raw = message.encode_to_vec();
        let mut extras = convert::extras(&message);
        let timer = sink.chat_timer(&out.chat).await;
        let result = client
            .send_message_with_options(to, message, send_options(timer))
            .await
            .map_err(failed)?;

        let timestamp_ms = now_ms();
        extras.raw_proto = Some(raw);
        extras.meta.status = Some(DeliveryStatus::Sent);
        extras.meta.expires_s = timer;
        extras.meta.quoted = quoted;
        sink.emit_full(
            out.message(
                result.message_id.clone(),
                timestamp_ms,
                MessageBody::Media {
                    kind: req.kind,
                    caption: req.caption.filter(|c| !c.is_empty()),
                    mime: req.mime,
                    size,
                },
            ),
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
        let out = Outgoing::new(&client, &resolver, &to).await;

        let stored = sink.find_message(&out.chat, &req.message_id).await;
        let wire = sink.wire_ids(&out.chat, &req.message_id).await;
        // The key names the author as WhatsApp named them: a LID in a group
        // that addresses its members by LID.
        let author = reaction::target_author(
            &req,
            stored.as_ref(),
            wire.sender.as_deref(),
            out.own_jid.as_deref(),
        );
        let key = reaction::message_key(
            &out.wire_chat,
            &req.message_id,
            req.from_me,
            author.as_deref(),
        );
        client
            .send_reaction(to, key, &req.emoji)
            .await
            .map_err(failed)?;

        sink.emit_reaction(
            out.chat,
            req.message_id,
            Reaction {
                sender: out.sender,
                from_me: true,
                emoji: req.emoji,
                sender_alt: out.sender_alt,
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
        // The member lists state pairs; the rest comes from the mappings.
        let stated: Vec<Pair> = metadata
            .iter()
            .flat_map(members)
            .filter_map(|m| m.pair())
            .collect();
        sink.learn_pairs(pairs_from(&stated, IdentitySource::GroupMetadata))
            .await;
        let unmapped: Vec<&Jid> = metadata
            .iter()
            .flat_map(|g| g.participants.iter())
            .filter(|p| p.phone_number.is_none() || p.lid.is_none())
            .map(|p| &p.jid)
            .collect();
        let map = resolver.prefetch(&unmapped).await;
        let mut infos = Vec::with_capacity(metadata.len());
        for group in &metadata {
            let mut info = groups::group_info(
                &group.id,
                &group.subject,
                members(group)
                    .iter()
                    .map(|member| groups::participant(member, &map)),
            );
            info.addressing_mode = Some(groups::addressing(group.addressing_mode).to_owned());
            info.description = group
                .description
                .as_deref()
                .map(str::trim)
                .filter(|d| !d.is_empty())
                .map(str::to_owned);
            info.announce = group.is_announcement;
            sink.rename_chat(&info.jid, &info.subject).await;
            // The metadata names the timer as it is now (none: off).
            let timer = group.ephemeral.and_then(|e| e.expiration).unwrap_or(0);
            sink.learn_chat_timer(&info.jid, timer, now_ms()).await;
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
        let out = Outgoing::new(&client, &resolver, &to).await;

        let mut meta = MessageMeta {
            status: Some(DeliveryStatus::Sent),
            ..Default::default()
        };
        let mut context: Option<wa::ContextInfo> = None;
        if let Some(quoted_id) = req.quoted_id.as_deref().filter(|id| !id.is_empty()) {
            let quoted = sink.find_message(&out.chat, quoted_id).await;
            let wire = sink.wire_ids(&out.chat, quoted_id).await;
            let own_jid = out.own_jid.as_deref();
            meta.quoted = Some(quoted_meta(quoted_id, quoted.as_ref(), own_jid));
            context = Some(quote_context(
                &out.chat,
                quoted_id,
                quoted.as_ref(),
                &wire,
                own_jid,
            ));
        }
        let mut text = req.text.clone();
        if !req.mentions.is_empty() {
            // A group that names its members by LID is told who is meant by
            // LID too, token and JID.
            let lid_group = to.is_group()
                && client
                    .groups()
                    .query_info(&to)
                    .await
                    .is_ok_and(|g| g.addressing_mode == AddressingMode::Lid);
            let mut wire_mentions = req.mentions.clone();
            if lid_group {
                let mut pairs = Vec::new();
                for mention in &req.mentions {
                    if let Some(lid) = sink.lid_of_pn(mention).await {
                        pairs.push((mention.clone(), lid));
                    }
                }
                (text, wire_mentions) = convert::mentions_by_lid(&text, &req.mentions, &pairs);
            }
            context.get_or_insert_with(Default::default).mentioned_jid = wire_mentions;
            meta.mentions = req.mentions.clone();
        }
        let mut thumbnail = None;
        let mut extended = wa::message::ExtendedTextMessage {
            text: Some(text.clone()),
            ..Default::default()
        };
        if let Some(preview) = &req.link_preview {
            let picture = preview
                .thumbnail()
                .map_err(|e| DriverError::BadRequest(e.to_string()))?;
            extended.matched_text = Some(preview.url.clone());
            extended.title = preview.title.clone();
            extended.description = preview.description.clone();
            extended.jpeg_thumbnail = picture.clone();
            meta.link_preview = Some(LinkPreview {
                url: preview.url.clone(),
                title: preview.title.clone(),
                description: preview.description.clone(),
                thumbnail: picture.is_some(),
            });
            thumbnail = picture;
        }
        let message = if context.is_some() || req.link_preview.is_some() {
            if let Some(context) = context {
                extended.context_info = MessageField::some(context);
            }
            wa::Message {
                extended_text_message: MessageField::some(extended),
                ..Default::default()
            }
        } else {
            wa::Message {
                conversation: Some(text.clone()),
                ..Default::default()
            }
        };
        let timer = sink.chat_timer(&out.chat).await;
        meta.expires_s = timer;
        let result = client
            .send_message_with_options(to, message, send_options(timer))
            .await
            .map_err(failed)?;

        let timestamp_ms = now_ms();
        sink.emit_full(
            out.message(
                result.message_id.clone(),
                timestamp_ms,
                MessageBody::Text(text),
            ),
            MessageExtras {
                meta,
                thumbnail,
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

/// The send options of a message into a chat whose timer is `timer`.
fn send_options(timer: Option<u32>) -> SendOptions {
    match timer.filter(|t| *t > 0) {
        Some(seconds) => SendOptions::default().with_ephemeral_expiration(seconds),
        None => SendOptions::default(),
    }
}

impl WaRuntime {
    /// Sends a copy of a stored message to `req.to`, marked forwarded
    /// (`prepare_for_forward`: score raised, quote and mentions dropped), with
    /// the target chat's timer, and stores the copy.
    async fn forward_inner(
        &self,
        instance: &InstanceId,
        req: ForwardMessage,
    ) -> Result<Sent, DriverError> {
        let (client, sink) = self.client_of(instance)?;
        let resolver = self.resolver_of(instance)?;
        let to = convert::parse_jid(&req.to)?;
        let out = Outgoing::new(&client, &resolver, &to).await;
        let (source, raw, meta) = sink
            .forward_source(&req.chat, &req.message_id)
            .await
            .ok_or_else(|| DriverError::BadRequest("no such message".into()))?;
        let original = convert::forwardable(&source.body, raw.as_deref(), &meta)?;
        let copy = *original.get_base_message().prepare_for_forward();
        let mut extras = convert::extras(&copy);
        if matches!(source.body, MessageBody::Media { .. }) {
            extras.raw_proto = Some(copy.encode_to_vec());
        }
        let timer = sink.chat_timer(&out.chat).await;
        let result = client
            .send_message_with_options(to, copy, send_options(timer))
            .await
            .map_err(failed)?;
        let timestamp_ms = now_ms();
        extras.meta.status = Some(DeliveryStatus::Sent);
        extras.meta.expires_s = timer;
        extras.meta.mentions.clear();
        sink.emit_full(
            out.message(result.message_id.clone(), timestamp_ms, source.body),
            extras,
        )
        .await;
        Ok(Sent {
            message_id: result.message_id,
            timestamp_ms,
        })
    }
}

impl WaRuntime {
    /// Sends a poll, a place or contact cards (E6-WA R5) with the chat's timer and a reply context, and stores it.
    /// The poll protobuf is the library's (`polls().create`), built here so the timer can go along.
    async fn send_rich_inner(
        &self,
        instance: &InstanceId,
        req: rosty_wa_proto::SendRich,
    ) -> Result<Sent, DriverError> {
        let (client, sink) = self.client_of(instance)?;
        let resolver = self.resolver_of(instance)?;
        let to = convert::parse_jid(&req.chat)?;
        let out = Outgoing::new(&client, &resolver, &to).await;
        let mut quoted = None;
        let context = match req.quoted_id.as_deref().filter(|id| !id.is_empty()) {
            Some(quoted_id) => {
                let message = sink.find_message(&out.chat, quoted_id).await;
                let wire = sink.wire_ids(&out.chat, quoted_id).await;
                let own_jid = out.own_jid.as_deref();
                quoted = Some(quoted_meta(quoted_id, message.as_ref(), own_jid));
                Some(quote_context(
                    &out.chat,
                    quoted_id,
                    message.as_ref(),
                    &wire,
                    own_jid,
                ))
            }
            None => None,
        };
        let (message, secret) = super::rich::rich_message(&req.content, context)?;
        let mut extras = convert::extras(&message);
        extras.secret = secret.or(extras.secret);
        extras.meta.quoted = quoted.or(extras.meta.quoted);
        extras.meta.status = Some(DeliveryStatus::Sent);
        let timer = sink.chat_timer(&out.chat).await;
        extras.meta.expires_s = timer;
        let result = client
            .send_message_with_options(to, message, send_options(timer))
            .await
            .map_err(failed)?;
        let timestamp_ms = now_ms();
        sink.emit_full(
            out.message(
                result.message_id.clone(),
                timestamp_ms,
                MessageBody::Unsupported,
            ),
            extras,
        )
        .await;
        Ok(Sent {
            message_id: result.message_id,
            timestamp_ms,
        })
    }

    /// My vote in a stored poll (E6-WA R5): sent with the poll's secret under the author's id as the poll
    /// arrived, then counted.
    async fn poll_vote_inner(
        &self,
        instance: &InstanceId,
        req: rosty_wa_proto::PollVote,
    ) -> Result<(), DriverError> {
        let (client, sink) = self.client_of(instance)?;
        let source = sink
            .poll_source(&req.chat, &req.message_id)
            .await
            .ok_or_else(|| DriverError::BadRequest("no such poll".into()))?;
        let secret = source
            .secret
            .clone()
            .ok_or_else(|| DriverError::BadRequest("the poll's secret is not kept".into()))?;
        let device = client.persistence_manager().get_device_snapshot();
        let creator = if source.from_me {
            device.pn.as_ref().map(|jid| jid.to_non_ad().to_string())
        } else if convert::is_group(&req.chat) {
            source.wire_sender.clone().or(source.sender.clone())
        } else {
            source.wire_chat.clone().or(Some(req.chat.clone()))
        }
        .ok_or_else(|| DriverError::Failed("the poll's author is unknown".into()))?;
        let creator = convert::parse_jid(&creator)?;
        let chat = convert::parse_jid(source.wire_chat.as_deref().unwrap_or(&req.chat))?;
        client
            .polls()
            .vote(chat, &req.message_id, &creator, &secret, &req.options)
            .await
            .map_err(failed)?;
        sink.my_poll_vote(&req.chat, &req.message_id, req.options)
            .await;
        Ok(())
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
    // When it reached them, for message info.
    let at_ms = receipt.timestamp.timestamp_millis();
    let source = &receipt.source;
    // A receipt names its sender by both ids when WhatsApp sends both.
    if let Some(pair) = source
        .sender_alt
        .as_ref()
        .and_then(|alt| Pair::of(&source.sender, alt))
    {
        sink.learn_pairs(vec![pair.learned(IdentitySource::Receipt)])
            .await;
    }
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
        let receipt = GroupReceipt {
            participant,
            ids,
            status,
            members,
            own: own_ids(&own),
        };
        sink.group_receipt_at(&chat, receipt, at_ms).await;
    } else {
        sink.set_status_at(&chat, ids, status, at_ms).await;
    }
}

/// What [`handle_event`] knows about the instance an event belongs to.
struct Handling<'a> {
    instance: &'a InstanceId,
    was_connected: &'a AtomicBool,
    account: &'a Account,
}

async fn handle_event(
    sink: &EventSink,
    resolver: &Resolver,
    context: &Handling<'_>,
    event: &Event,
) {
    let Handling {
        instance,
        was_connected,
        ..
    } = *context;
    match event {
        Event::Receipt(receipt) => on_receipt(sink, resolver, receipt).await,
        // Presence and typing never carry the other id: the mappings are
        // all there is to match them with.
        Event::Presence(update) => {
            let (jid, jid_alt) = resolver.resolve_with_alt(&update.from, None).await;
            sink.publish(GatewayEvent::Presence {
                jid: jid.to_string(),
                available: !update.unavailable,
                last_seen_ms: update.last_seen.map(|t| t.timestamp_millis()),
                jid_alt,
            });
        }
        Event::ChatPresence(update) => {
            let hint = update.source.sender_alt.as_ref();
            let (jid, jid_alt) = resolver.resolve_with_alt(&update.source.sender, hint).await;
            sink.publish(GatewayEvent::Typing {
                chat: resolver
                    .resolve(&update.source.chat, hint)
                    .await
                    .to_string(),
                jid: jid.to_string(),
                state: typing_state(update.state, update.media),
                jid_alt,
            });
        }
        Event::GroupUpdate(update) => {
            // In a LID group the notification names who acted by both ids.
            if let Some(pair) = update
                .participant
                .as_ref()
                .zip(update.participant_pn.as_ref())
                .and_then(|(lid, pn)| Pair::of(lid, pn))
            {
                sink.learn_pairs(vec![pair.learned(IdentitySource::GroupMetadata)])
                    .await;
            }
            let jid = update.group_jid.to_non_ad().to_string();
            if let Some(subject) = groups::new_subject(&update.action) {
                sink.rename_chat(&jid, subject).await;
            }
            // The group's disappearing timer: a line in the chat when it
            // changed (my own change through the gateway already left one).
            if let GroupNotificationAction::Ephemeral { expiration, .. } = &update.action {
                let by = update
                    .participant_pn
                    .as_ref()
                    .or(update.participant.as_ref())
                    .map(|p| p.to_non_ad().to_string());
                let at_ms = update.timestamp.timestamp_millis();
                sink.chat_timer_changed(TimerChange {
                    chat: jid.clone(),
                    seconds: *expiration,
                    at_ms,
                    by,
                    note_id: format!(
                        "{}-{}",
                        update.notification_id.as_deref().unwrap_or("GTIMER"),
                        update.action_index
                    ),
                })
                .await;
            }
            sink.publish(GatewayEvent::GroupChanged { jid });
        }
        // A device was paired. The library reconnects next and the history
        // sync follows: whatever is stored for another account goes now.
        Event::PairSuccess(pair) => {
            context.account.check(sink, Some(&pair.id), true).await;
        }
        Event::HistorySync(lazy) => {
            let progress = lazy.progress();
            let kind = history::sync_kind(lazy.sync_type());
            let lazy = (**lazy).clone();
            let now = now_ms();
            let own = resolver
                .client()
                .persistence_manager()
                .get_device_snapshot();
            // Never store one account's history next to another's rows
            // (the check is one read when nothing changed).
            context.account.check(sink, own.pn.as_ref(), false).await;
            let own_pn = own.pn.as_ref().map(|jid| jid.to_non_ad().to_string());
            // Look up every LID the sync names before the blocking walk,
            // and take the pairs the sync states itself.
            let scan = {
                let lazy = lazy.clone();
                tokio::task::spawn_blocking(move || history::scan(&lazy)).await
            };
            let map = match scan {
                Ok(Ok(scan)) => {
                    sink.learn_pairs(pairs_from(&scan.pairs, IdentitySource::History))
                        .await;
                    let known = resolver
                        .prefetch(&scan.lids.iter().collect::<Vec<_>>())
                        .await;
                    scan.complete(known)
                }
                _ => LidMap::default(),
            };
            // The walk hands the conversations over in batches and waits
            // until each is stored: a large chunk is never decoded whole.
            let (batches, mut stored) = tokio::sync::mpsc::channel(1);
            let walk = tokio::task::spawn_blocking(move || {
                history::drain_in_batches(
                    &lazy,
                    now,
                    own_pn.as_deref(),
                    &map,
                    HISTORY_ROWS_PER_TX,
                    |batch| batches.blocking_send(batch).is_ok(),
                )
            });
            let mut report = HistoryImport::default();
            while let Some(batch) = stored.recv().await {
                if let Some(part) = sink.import_history(batch).await {
                    report.absorb(part);
                }
            }
            match walk.await {
                Ok(Ok(rest)) => {
                    sink.set_push_names(rest.push_names).await;
                    sink.import_statuses(rest.statuses).await;
                    sink.history_synced(kind, progress, report).await;
                    lid::sweep(resolver, sink).await;
                }
                Ok(Err(e)) => log::warn!("{instance}: history sync unreadable: {e}"),
                Err(e) => log::warn!("{instance}: history sync task failed: {e}"),
            }
        }
        // The library learns LID <-> phone-number pairs from these; there is
        // no dedicated event, so record what the event names and look at the
        // stored LIDs again.
        Event::DeviceListUpdate(update) => {
            if let Some(pair) = update
                .lid_user
                .as_ref()
                .and_then(|lid| Pair::of(lid, &update.user))
            {
                sink.learn_pairs(vec![pair.learned(IdentitySource::DeviceNotification)])
                    .await;
            }
            lid::sweep(resolver, sink).await;
        }
        Event::ContactNumberChanged(change) => {
            app_state::on_number_changed(sink, change).await;
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
            // An offer from a LID carries the caller's number next to it.
            if let Some(pair) = calls::offer_pair(call) {
                sink.learn_pairs(vec![pair.learned(IdentitySource::Call)])
                    .await;
            }
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
        Event::UndecryptableMessage(event) => on_unavailable(sink, resolver, event).await,
        other => app_state::handle(sink, resolver, other).await,
    }
}

/// Stores a message WhatsApp keeps from linked devices (view once, bot,
/// hosted business) as a placeholder, so it does not vanish. Other
/// undecryptable messages come back on their own (phone resend, retry).
async fn on_unavailable(
    sink: &EventSink,
    resolver: &Resolver,
    event: &whatsapp_rust::wacore::types::events::UndecryptableMessage,
) {
    let Some(reason) = convert::unavailable_reason(event) else {
        return;
    };
    let info = &event.info;
    if info.source.chat.is_status_broadcast() {
        return;
    }
    let stated = convert::envelope_pairs(info);
    if !stated.is_empty() {
        sink.learn_pairs(pairs_from(&stated, IdentitySource::PeerMessage))
            .await;
    }
    let mut jids = lid::source_jids(&info.source);
    jids.extend(info.peer_recipient_pn.iter());
    let mut map = resolver.prefetch(&jids).await;
    for pair in &stated {
        map.insert_new(pair);
    }
    log::info!("[msg:{}] stored as a placeholder ({reason:?})", info.id);
    app_state::apply_converted(sink, convert::placeholder(info, reason, &map)).await;
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
                // The receipt goes to the id the messages arrived under.
                let wire = match req.ids.first() {
                    Some(id) => sink.wire_ids(&chat_key, id).await,
                    None => WireIds::default(),
                };
                let to = receipt_chat(&chat, wire.chat.as_deref());
                let ids: Vec<&str> = req.ids.iter().map(String::as_str).collect();
                client.mark_as_read(&to, None, &ids).await.map_err(failed)?;
                sink.mark_chat_read(&chat_key).await;
                return Ok(());
            }
            // A group receipt names the author of the messages it covers:
            // as WhatsApp named them when the gateway kept that, else as the
            // app says. Older apps leave it out: then the stored author.
            let named = sender.as_ref().map(ToString::to_string);
            let mut authors = Vec::with_capacity(req.ids.len());
            for id in &req.ids {
                let wire = sink.wire_ids(&chat_key, id).await;
                let known = match &named {
                    Some(named) => Some(named.clone()),
                    None => sink
                        .find_message(&chat_key, id)
                        .await
                        .and_then(|m| m.sender),
                };
                authors.push(wire_author(&wire, known));
            }
            let batches = split_by_author(&req.ids, &authors)
                .into_iter()
                .map(|(author, ids)| {
                    let author = author.as_deref().map(convert::parse_jid).transpose()?;
                    Ok((author, ids))
                })
                .collect::<Result<Vec<_>, DriverError>>()?;
            // Messages from before the gateway kept wire ids name their
            // author by phone number; a LID group wants the LID.
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
            let (client, sink) = self.client_of(&instance)?;
            let found = client
                .contacts()
                .is_on_whatsapp(&parsed)
                .await
                .map_err(contact_failed)?;
            // A lookup answers with the LID of each number it knows.
            let stated: Vec<Pair> = found
                .iter()
                .filter_map(|r| Pair::of(r.lid.as_ref()?, r.pn_jid.as_ref().unwrap_or(&r.jid)))
                .collect();
            sink.learn_pairs(pairs_from(&stated, IdentitySource::Usync))
                .await;
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
            let (client, sink) = self.client_of(&instance)?;
            let revoke_type = match req.sender {
                // An admin's delete names the author in the message key: as
                // WhatsApp named them when the gateway kept that.
                Some(sender) => {
                    let chat_key = self
                        .resolver_of(&instance)?
                        .resolve(&to, None)
                        .await
                        .to_string();
                    let wire = sink.wire_ids(&chat_key, &req.message_id).await;
                    let author = wire_author(&wire, Some(sender)).unwrap_or_default();
                    RevokeType::Admin {
                        original_sender: convert::parse_jid(&author)?,
                    }
                }
                None => RevokeType::Sender,
            };
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
            if let ChatActionKind::Disappearing { seconds } = req.action {
                return if chat.is_group() {
                    client
                        .groups()
                        .set_ephemeral(chat, seconds)
                        .await
                        .map_err(failed)
                } else {
                    client
                        .set_chat_disappearing_timer(chat, seconds)
                        .await
                        .map(|_| ())
                        .map_err(failed)
                };
            }
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
                ChatActionKind::Disappearing { .. } => Ok(()),
            }
            .map_err(failed)
        })
    }

    fn forward_message(
        &self,
        instance: &InstanceId,
        req: ForwardMessage,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move { self.forward_inner(&instance, req).await })
    }

    fn send_rich(
        &self,
        instance: &InstanceId,
        req: rosty_wa_proto::SendRich,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        let instance = instance.clone();
        Box::pin(async move { self.send_rich_inner(&instance, req).await })
    }

    fn poll_vote(
        &self,
        instance: &InstanceId,
        req: rosty_wa_proto::PollVote,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move { self.poll_vote_inner(&instance, req).await })
    }

    fn unsubscribe_presence(
        &self,
        instance: &InstanceId,
        jid: String,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        let instance = instance.clone();
        Box::pin(async move {
            let jid = convert::parse_jid(&jid)?;
            let (client, _) = self.client_of(&instance)?;
            client.presence().unsubscribe(&jid).await.map_err(failed)
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
            let (client, sink) = self.client_of(&instance)?;
            let chat_key = self
                .resolver_of(&instance)?
                .resolve(&chat, None)
                .await
                .to_string();
            // As for read receipts: the ids the message arrived under.
            let wire = match req.ids.first() {
                Some(id) => sink.wire_ids(&chat_key, id).await,
                None => WireIds::default(),
            };
            let (to, sender) = if chat.is_group() {
                let sender = wire_author(&wire, sender.map(|s| s.to_string()))
                    .as_deref()
                    .map(convert::parse_jid)
                    .transpose()?;
                // Without a kept wire id, a LID group wants the author's LID.
                let sender = match sender {
                    Some(sender) => match client.groups().query_info(&chat).await {
                        Ok(group) => Some(group.phone_device_jid_into_lid(sender)),
                        Err(_) => Some(sender),
                    },
                    None => None,
                };
                (chat, sender)
            } else {
                (receipt_chat(&chat, wire.chat.as_deref()), sender)
            };
            let ids: Vec<&str> = req.ids.iter().map(String::as_str).collect();
            client
                .mark_as_played(&to, sender.as_ref(), &ids)
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
            let (client, sink) = self.client_of(&instance)?;
            let (groups, users): (Vec<&Jid>, Vec<&Jid>) = parsed.iter().partition(|j| j.is_group());
            let users: Vec<Jid> = users.into_iter().cloned().collect();
            let mut out = Vec::with_capacity(parsed.len());
            if !users.is_empty() {
                let infos = client
                    .contacts()
                    .get_user_info(&users)
                    .await
                    .map_err(contact_failed)?;
                // A profile query answers with the LID of each number too.
                let stated: Vec<Pair> = infos
                    .iter()
                    .filter_map(|(jid, info)| Pair::of(info.lid.as_ref()?, jid))
                    .collect();
                sink.learn_pairs(pairs_from(&stated, IdentitySource::Usync))
                    .await;
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
            let chat = convert::parse_jid(rosty_wa_proto::STATUS_BROADCAST)?;
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
            chat_alt: None,
            sender_alt: None,
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

        let none = WireIds::default();
        let theirs = quote_context(
            dm,
            "Q",
            Some(&stored(false, None, "hi")),
            &none,
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
            &none,
            Some("me@s.whatsapp.net"),
        );
        assert_eq!(mine.participant.as_deref(), Some("me@s.whatsapp.net"));

        let in_group = quote_context(
            group,
            "Q",
            Some(&stored(false, Some("4915@s.whatsapp.net"), "x")),
            &none,
            None,
        );
        assert_eq!(in_group.participant.as_deref(), Some("4915@s.whatsapp.net"));

        // Not in our history: a DM can still be quoted, a group cannot name an author.
        assert_eq!(
            quote_context(dm, "Q", None, &none, None)
                .participant
                .as_deref(),
            Some(dm)
        );
        assert_eq!(
            quote_context(group, "Q", None, &none, None).participant,
            None
        );
        assert!(
            quote_context(dm, "Q", None, &none, None)
                .quoted_message
                .as_option()
                .is_none()
        );
    }

    #[test]
    fn quotes_name_the_author_as_whatsapp_did() {
        let dm = "905550000001@s.whatsapp.net";
        let group = "123-456@g.us";
        let me = Some("905550000009@s.whatsapp.net");
        // A group that addresses its members by LID: stored by number, the
        // quote says the LID the message arrived under.
        let wire = WireIds {
            chat: Some(group.into()),
            sender: Some("1234@lid".into()),
        };
        let quoted = stored(false, Some("905550000001@s.whatsapp.net"), "x");
        assert_eq!(
            quote_context(group, "Q", Some(&quoted), &wire, me)
                .participant
                .as_deref(),
            Some("1234@lid")
        );
        // A one-to-one chat stored under the number that WhatsApp moved to
        // the LID: their message is quoted by the LID.
        let wire = WireIds {
            chat: Some("1234@lid".into()),
            sender: None,
        };
        assert_eq!(
            quote_context(dm, "Q", Some(&stored(false, None, "x")), &wire, me)
                .participant
                .as_deref(),
            Some("1234@lid")
        );
        // My own message there is still mine.
        assert_eq!(
            quote_context(dm, "Q", Some(&stored(true, None, "x")), &wire, me)
                .participant
                .as_deref(),
            me
        );
        // My group message echoed from another device under my LID.
        let wire = WireIds {
            chat: Some(group.into()),
            sender: Some("777@lid".into()),
        };
        assert_eq!(
            quote_context(group, "Q", Some(&stored(true, None, "x")), &wire, me)
                .participant
                .as_deref(),
            Some("777@lid")
        );
    }

    #[test]
    fn read_receipts_go_to_the_id_the_messages_arrived_under() {
        let pn = jid("905550000001@s.whatsapp.net");
        assert_eq!(receipt_chat(&pn, Some("1234@lid")).to_string(), "1234@lid");
        // Nothing kept (a message from before wire ids), or nonsense: the
        // chat the app named.
        assert_eq!(receipt_chat(&pn, None), pn);
        assert_eq!(receipt_chat(&pn, Some("not a jid@@")), pn);
        assert_eq!(receipt_chat(&pn, Some("1-2@g.us")), pn);

        // Group receipts name the wire author, else what is known.
        let kept = WireIds {
            chat: None,
            sender: Some("1234@lid".into()),
        };
        let known = Some("905550000001@s.whatsapp.net".to_owned());
        assert_eq!(
            wire_author(&kept, known.clone()).as_deref(),
            Some("1234@lid")
        );
        assert_eq!(wire_author(&WireIds::default(), known.clone()), known);
        assert_eq!(wire_author(&WireIds::default(), None), None);
    }

    #[test]
    fn the_account_caches_are_the_configured_directories() {
        let config = crate::config::Config::from_parts(None, &|_| None).unwrap();
        let runtime = WaRuntime::new(config.data_dir.clone(), false);
        let account = runtime.account(&InstanceId::new("abc"));
        assert!(!account.full_history);
        assert_eq!(
            account.cache_dirs.to_vec(),
            [
                config.media_dir("abc"),
                config.pictures_dir("abc"),
                config.people_job_dir("abc")
            ]
        );
    }

    /// Another account on the instance: its cached files are gone before the
    /// apps hear `account_changed`; the same account loses nothing.
    #[tokio::test]
    async fn another_account_loses_its_cached_files_first() {
        use crate::store::{NewKey, Store};
        use rosty_wa_proto::{ConnectKey, KeyId};

        let dir = tempfile::tempdir().unwrap();
        let runtime = WaRuntime::new(dir.path().to_owned(), true);
        let instance = InstanceId::new("abc");
        let account = runtime.account(&instance);
        let store = Store::open_in_memory().unwrap();
        let key = ConnectKey::from_bytes(&[1; 32]);
        let new = NewKey {
            key_id: KeyId::from_bytes(&[1; 8]),
            hash: key.hash(),
            label: "k".into(),
        };
        store
            .create_instance(instance.clone(), new, 1, 4)
            .await
            .unwrap();
        let hub = Arc::new(crate::hub::Hub::new());
        let pusher: Arc<dyn crate::push::Pusher> = Arc::new(crate::push::NoopPusher);
        let calls = crate::wa::calls::CallTable::new(
            instance.clone(),
            Arc::clone(&hub),
            Arc::clone(&pusher),
            crate::wa::calls::RING_TIMEOUT,
        );
        let sink = EventSink::new(
            instance.clone(),
            Arc::clone(&hub),
            store.clone(),
            pusher,
            calls,
        );
        let fill = || {
            for cache in account.cache_dirs.iter() {
                std::fs::create_dir_all(cache).unwrap();
                std::fs::write(cache.join("file"), b"x").unwrap();
            }
        };
        let filled = || account.cache_dirs.iter().all(|c| c.join("file").exists());
        let old = jid("100000000001:3@s.whatsapp.net");
        let new = jid("100000000002:1@s.whatsapp.net");
        let mut events = hub.subscribe(&instance, None).events;

        // The first account, its reconnect and its relink keep everything.
        sink.emit(connected(&instance, Some(&old), None, "Old"))
            .await;
        fill();
        account.check(&sink, Some(&old), false).await;
        account.check(&sink, Some(&old), true).await;
        account.check(&sink, None, true).await;
        assert!(filled());
        assert!(matches!(
            events.try_recv().unwrap().event,
            GatewayEvent::State(_)
        ));
        assert!(events.try_recv().is_err());

        account.check(&sink, Some(&new), true).await;
        assert!(account.cache_dirs.iter().all(|c| !c.exists()));
        assert_eq!(
            events.try_recv().unwrap().event,
            GatewayEvent::AccountChanged {
                old_pn: Some("100000000001@s.whatsapp.net".into()),
                new_pn: "100000000002@s.whatsapp.net".into(),
            }
        );
        // The `connected` state that follows names the new epoch.
        sink.emit(connected(&instance, Some(&new), None, "New"))
            .await;
        match events.try_recv().unwrap().event {
            GatewayEvent::State(status) => {
                assert_eq!(status.account_epoch, Some(1));
                assert_eq!(status.epoch.as_deref(), Some(hub.epoch()));
                assert_eq!(
                    status.own_jid.as_deref(),
                    Some("100000000002@s.whatsapp.net")
                );
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn the_connected_state_names_both_own_ids() {
        let id = InstanceId::new("abc");
        let pn = jid("905550000009:4@s.whatsapp.net");
        let lid = jid("777:4@lid");
        assert_eq!(
            connected(&id, Some(&pn), Some(&lid), "Ada"),
            GatewayEvent::State(InstanceStatus {
                instance_id: "abc".into(),
                state: InstanceState::Connected,
                own_jid: Some("905550000009@s.whatsapp.net".into()),
                push_name: Some("Ada".into()),
                own_lid: Some("777@lid".into()),
                epoch: None,
                account_epoch: None,
                history: None,
            })
        );
        // Before the LID is known, and without a push name.
        assert_eq!(
            connected(&id, Some(&pn), None, ""),
            GatewayEvent::State(InstanceStatus {
                instance_id: "abc".into(),
                state: InstanceState::Connected,
                own_jid: Some("905550000009@s.whatsapp.net".into()),
                push_name: None,
                own_lid: None,
                epoch: None,
                account_epoch: None,
                history: None,
            })
        );
        assert_eq!(
            own_ids(&[&pn, &lid]),
            ["905550000009@s.whatsapp.net", "777@lid"]
        );
    }

    #[test]
    fn instance_files_live_under_the_data_dir() {
        let runtime = WaRuntime::new(PathBuf::from("/data"), true);
        let id = InstanceId::new("abc");
        assert_eq!(
            runtime.instance_dir(&id),
            PathBuf::from("/data/instances/abc")
        );
    }
}
