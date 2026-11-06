//! The seam between the gateway and WhatsApp itself. The real driver
//! ([`crate::wa::WaRuntime`]) wraps whatsapp-rust; [`FakeDriver`] scripts the same events for tests.

use std::sync::Arc;

use futures::future::BoxFuture;
use rovsti_wa_proto::{
    ChatAction, ChatState, DeliveryStatus, EditMessage, GatewayEvent, GroupInfo, LinkRequest,
    MarkRead, MediaKind, MessageMeta, Reaction, RevokeMessage, SendReaction, SendText, Sent,
    WaMessage, WaProfile, WaUser, event::ReceiptKind,
};

use crate::hub::Hub;
use crate::now_ms;
use crate::push::{PushKind, Pusher, should_push};
use crate::store::{
    ChatChange, FetchedProfile, InstanceId, MessageChange, MessageExtras, ProfileChange, Store,
};
use crate::wa::calls::{CallBackend, CallTable};

/// What the apps hear when the ticks of my messages `moved` in `chat`: one
/// `Receipt` for those now delivered and one for those now read or played,
/// then a `MessageMeta` per played message, which is how an app learns a voice
/// note was heard (a `Receipt` only says delivered or read).
pub fn tick_events(chat: &str, moved: Vec<(String, MessageMeta)>) -> Vec<GatewayEvent> {
    let mut delivered = Vec::new();
    let mut read = Vec::new();
    let mut played = Vec::new();
    for (id, meta) in moved {
        match meta.status {
            Some(DeliveryStatus::Delivered) => delivered.push(id),
            Some(DeliveryStatus::Read) => read.push(id),
            Some(DeliveryStatus::Played) => {
                read.push(id.clone());
                played.push((id, meta));
            }
            Some(DeliveryStatus::Sent) | None => {}
        }
    }
    let receipts = [
        (ReceiptKind::Delivered, delivered),
        (ReceiptKind::Read, read),
    ]
    .into_iter()
    .filter(|(_, ids)| !ids.is_empty())
    .map(|(kind, ids)| GatewayEvent::Receipt {
        chat: chat.to_owned(),
        ids,
        kind,
    });
    let metas = played
        .into_iter()
        .map(|(id, meta)| GatewayEvent::MessageMeta {
            chat: chat.to_owned(),
            id,
            body: None,
            meta: Box::new(meta),
        });
    receipts.chain(metas).collect()
}

/// A file to send as a media message.
#[derive(Clone)]
pub struct SendMedia {
    pub chat: String,
    pub kind: MediaKind,
    pub mime: String,
    pub caption: Option<String>,
    pub data: Vec<u8>,
    /// Reply to this message.
    pub quoted_id: Option<String>,
    /// File name shown for a document.
    pub file_name: Option<String>,
    /// Duration of audio or video, in seconds.
    pub seconds: Option<u32>,
    /// Send audio as a voice note.
    pub voice_note: bool,
}

/// A stored message an action targets.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MessageRef {
    pub chat: String,
    pub id: String,
    pub from_me: bool,
    /// The author in a group chat.
    pub sender: Option<String>,
    pub timestamp_ms: i64,
}

impl From<&WaMessage> for MessageRef {
    fn from(m: &WaMessage) -> Self {
        Self {
            chat: m.chat.clone(),
            id: m.id.clone(),
            from_me: m.from_me,
            sender: m.sender.clone(),
            timestamp_ms: m.timestamp_ms,
        }
    }
}

/// What asking for a profile picture found.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PictureFetch {
    /// No picture, or it is hidden from this account.
    None,
    /// The picture is still `known_id`; nothing was downloaded.
    Unchanged,
    /// A picture other than `known_id`, downloaded.
    New { id: String, bytes: Vec<u8> },
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DriverError {
    #[error("WhatsApp is not connected")]
    NotConnected,
    #[error("not supported by this driver")]
    Unsupported,
    #[error("{0}")]
    BadRequest(String),
    #[error("{0}")]
    Failed(String),
}

/// What a driver talks back through. Cloneable and bound to one instance: it
/// persists what needs persisting, then publishes to the instance's hub.
#[derive(Clone)]
pub struct EventSink {
    instance: InstanceId,
    hub: Arc<Hub>,
    store: Store,
    pusher: Arc<dyn Pusher>,
    calls: Arc<CallTable>,
}

impl std::fmt::Debug for EventSink {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "EventSink({})", self.instance)
    }
}

impl EventSink {
    pub fn new(
        instance: InstanceId,
        hub: Arc<Hub>,
        store: Store,
        pusher: Arc<dyn Pusher>,
        calls: Arc<CallTable>,
    ) -> Self {
        Self {
            instance,
            hub,
            store,
            pusher,
            calls,
        }
    }

    /// The instance's voice calls: drivers report call events here.
    pub fn calls(&self) -> &Arc<CallTable> {
        &self.calls
    }

    pub fn instance(&self) -> &InstanceId {
        &self.instance
    }

    /// Persists the event when it carries state (`State`, `Message`), then
    /// publishes it. A message that is already stored is not published again.
    pub async fn emit(&self, event: GatewayEvent) {
        self.emit_with_raw(event, None).await;
    }

    /// Like [`emit`](Self::emit); `raw_proto` is the encoded WhatsApp message
    /// kept for media download and reactions.
    pub async fn emit_with_raw(&self, event: GatewayEvent, raw_proto: Option<Vec<u8>>) {
        match &event {
            GatewayEvent::State(status) => {
                let saved = self
                    .store
                    .set_state(
                        self.instance.clone(),
                        status.state,
                        status.own_jid.clone(),
                        status.push_name.clone(),
                    )
                    .await;
                if let Err(e) = saved {
                    log::warn!("{}: saving state failed: {e}", self.instance);
                }
            }
            GatewayEvent::Message(message) => {
                let extras = MessageExtras {
                    raw_proto,
                    ..Default::default()
                };
                self.emit_full(message.clone(), extras).await;
                return;
            }
            _ => {}
        }
        self.hub.publish(&self.instance, now_ms(), event);
    }

    /// Records a reaction (an empty emoji removes it) and publishes the
    /// message's new reaction list.
    pub async fn emit_reaction(&self, chat: String, id: String, reaction: Reaction) {
        let saved = self
            .store
            .set_reaction(self.instance.clone(), chat.clone(), id.clone(), reaction)
            .await;
        if let Err(e) = saved {
            log::warn!("{}: saving reaction failed: {e}", self.instance);
            return;
        }
        match self
            .store
            .message(self.instance.clone(), chat.clone(), id.clone())
            .await
        {
            Ok(Some(message)) => {
                self.hub.publish(
                    &self.instance,
                    now_ms(),
                    GatewayEvent::MessageUpdate {
                        chat,
                        id,
                        reactions: message.reactions,
                        read: false,
                    },
                );
            }
            // The reacted-to message is not in history; nothing to update.
            Ok(None) => {}
            Err(e) => log::warn!("{}: reading message failed: {e}", self.instance),
        }
    }

    /// Names a group chat once its subject is known (only if the chat exists).
    pub async fn rename_chat(&self, jid: &str, name: &str) {
        if let Err(e) = self
            .store
            .rename_chat(self.instance.clone(), jid.to_owned(), name.to_owned())
            .await
        {
            log::warn!("{}: renaming chat failed: {e}", self.instance);
        }
    }

    /// Zeroes the unread counter of `chat` once the user has read it.
    pub async fn mark_chat_read(&self, chat: &str) {
        if let Err(e) = self
            .store
            .clear_unread(self.instance.clone(), chat.to_owned())
            .await
        {
            log::warn!("{}: clearing unread failed: {e}", self.instance);
        }
    }

    /// A stored message, for quoting.
    pub async fn find_message(&self, chat: &str, id: &str) -> Option<WaMessage> {
        self.store
            .message(self.instance.clone(), chat.to_owned(), id.to_owned())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading message failed: {e}", self.instance);
                None
            })
    }

    /// Stores history-sync chats in one transaction and announces them with a
    /// single `HistorySynced` event (not one event per message).
    pub async fn import_history(
        &self,
        chats: Vec<crate::store::HistoryChat>,
        progress: Option<u32>,
    ) {
        match self
            .store
            .import_history(self.instance.clone(), chats)
            .await
        {
            Ok(chats) => {
                self.hub.publish(
                    &self.instance,
                    now_ms(),
                    GatewayEvent::HistorySynced { chats, progress },
                );
            }
            Err(e) => log::warn!("{}: saving history failed: {e}", self.instance),
        }
    }

    /// Publishes without persisting (receipts, presence, typing, ...).
    pub fn publish(&self, event: GatewayEvent) {
        self.hub.publish(&self.instance, now_ms(), event);
    }

    pub async fn emit_message(&self, message: WaMessage, raw_proto: Option<Vec<u8>>) {
        self.emit_with_raw(GatewayEvent::Message(message), raw_proto)
            .await;
    }

    /// Stores a message with its extras and publishes it, followed by a
    /// `MessageMeta` event when its meta is not empty. A message that is
    /// already stored is not published again.
    pub async fn emit_full(&self, message: WaMessage, extras: MessageExtras) {
        let meta = extras.meta.clone();
        match self
            .store
            .insert_message_full(self.instance.clone(), message.clone(), extras)
            .await
        {
            Ok(true) => {
                if should_push(&message, now_ms()) {
                    self.pusher.notify(
                        &self.instance,
                        PushKind::Message {
                            chat: message.chat.clone(),
                            count: 1,
                        },
                    );
                }
            }
            Ok(false) => return,
            Err(e) => log::warn!("{}: saving message failed: {e}", self.instance),
        }
        let (chat, id) = (message.chat.clone(), message.id.clone());
        let announce = worth_announcing(&chat, &meta);
        self.publish(GatewayEvent::Message(message));
        if announce {
            self.publish(GatewayEvent::MessageMeta {
                chat,
                id,
                body: None,
                meta: Box::new(meta),
            });
        }
    }

    /// Applies an edit, delete for everyone or star to a stored message and
    /// publishes the new meta. Unknown messages and no-ops publish nothing.
    pub async fn change_message(&self, chat: &str, id: &str, change: MessageChange) {
        match self
            .store
            .change_message(
                self.instance.clone(),
                chat.to_owned(),
                id.to_owned(),
                change,
            )
            .await
        {
            Ok(Some((body, meta))) => self.publish(GatewayEvent::MessageMeta {
                chat: chat.to_owned(),
                id: id.to_owned(),
                body,
                meta: Box::new(meta),
            }),
            Ok(None) => {}
            Err(e) => log::warn!("{}: updating a message failed: {e}", self.instance),
        }
    }

    /// Stores the ticks a one-to-one receipt puts on my messages and tells
    /// the apps about the ones that moved.
    pub async fn set_status(&self, chat: &str, ids: Vec<String>, status: DeliveryStatus) {
        match self
            .store
            .set_status(self.instance.clone(), chat.to_owned(), ids, status)
            .await
        {
            Ok(moved) => self.publish_ticks(chat, moved),
            Err(e) => log::warn!("{}: saving receipts failed: {e}", self.instance),
        }
    }

    /// Stores one group member's receipt for my messages and tells the apps
    /// about the messages whose group-wide ticks moved (see
    /// [`crate::store::group_status`]).
    pub async fn group_receipt(
        &self,
        chat: &str,
        participant: String,
        ids: Vec<String>,
        status: DeliveryStatus,
        members: Option<usize>,
    ) {
        match self
            .store
            .record_group_receipt(
                self.instance.clone(),
                chat.to_owned(),
                participant,
                ids,
                status,
                members,
            )
            .await
        {
            Ok(moved) => self.publish_ticks(chat, moved),
            Err(e) => log::warn!("{}: saving receipts failed: {e}", self.instance),
        }
    }

    /// A `Receipt` event per new tick state, and for played voice notes also
    /// the new meta (a `Receipt` only knows delivered and read).
    fn publish_ticks(&self, chat: &str, moved: Vec<(String, MessageMeta)>) {
        for event in tick_events(chat, moved) {
            self.publish(event);
        }
    }

    /// The ids among `ids` that are not my own messages (unknown ids too).
    pub async fn not_mine(&self, chat: &str, ids: Vec<String>) -> Vec<String> {
        let mut out = Vec::with_capacity(ids.len());
        for id in ids {
            if !self
                .find_message(chat, &id)
                .await
                .is_some_and(|m| m.from_me)
            {
                out.push(id);
            }
        }
        out
    }

    /// Deletes a message on this account's devices and tells the apps.
    pub async fn delete_message(&self, chat: &str, id: &str) {
        match self
            .store
            .delete_message(self.instance.clone(), chat.to_owned(), id.to_owned())
            .await
        {
            Ok(true) => self.publish(GatewayEvent::MessageDeleted {
                chat: chat.to_owned(),
                id: id.to_owned(),
            }),
            Ok(false) => {}
            Err(e) => log::warn!("{}: deleting a message failed: {e}", self.instance),
        }
    }

    /// Changes a chat setting and publishes the chat's new meta.
    pub async fn change_chat(&self, jid: &str, change: ChatChange) {
        match self
            .store
            .change_chat(self.instance.clone(), jid.to_owned(), change)
            .await
        {
            Ok(Some(meta)) => self.publish(GatewayEvent::ChatMeta {
                jid: jid.to_owned(),
                meta,
            }),
            Ok(None) => {}
            Err(e) => log::warn!("{}: saving chat settings failed: {e}", self.instance),
        }
    }

    /// Deletes (or, with `keep_chat`, clears) a chat and tells the apps.
    pub async fn delete_chat(&self, jid: &str, keep_chat: bool) {
        match self
            .store
            .delete_chat(self.instance.clone(), jid.to_owned(), keep_chat)
            .await
        {
            Ok(true) => {
                let jid = jid.to_owned();
                self.publish(if keep_chat {
                    GatewayEvent::ChatCleared { jid }
                } else {
                    GatewayEvent::ChatDeleted { jid }
                });
            }
            Ok(false) => {}
            Err(e) => log::warn!("{}: deleting a chat failed: {e}", self.instance),
        }
    }

    /// Records what changed about a person or group and publishes the
    /// profile when an app would show the difference.
    pub async fn change_profile(&self, jid: &str, change: ProfileChange) -> Option<WaProfile> {
        match self
            .store
            .change_profile(self.instance.clone(), jid.to_owned(), change, now_ms())
            .await
        {
            Ok(Some(profile)) => {
                self.publish(GatewayEvent::Profile(profile.clone()));
                Some(profile)
            }
            Ok(None) => None,
            Err(e) => {
                log::warn!("{}: saving a profile failed: {e}", self.instance);
                None
            }
        }
    }

    /// Push names from a history sync (not announced one by one).
    pub async fn set_push_names(&self, names: Vec<(String, String)>) {
        if names.is_empty() {
            return;
        }
        if let Err(e) = self
            .store
            .set_push_names(self.instance.clone(), names)
            .await
        {
            log::warn!("{}: saving push names failed: {e}", self.instance);
        }
    }

    /// The secret of a stored message, for decrypting an edit of it.
    pub async fn message_secret(&self, chat: &str, id: &str) -> Option<Vec<u8>> {
        self.store
            .message_secret(self.instance.clone(), chat.to_owned(), id.to_owned())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading a message secret failed: {e}", self.instance);
                None
            })
    }
}

/// Whether a new message's meta deserves its own `MessageMeta` event after
/// the `Message` event. The first tick of my own messages is implied, and in
/// a one-to-one chat the sender's push name is the chat's (`ChatMeta`), so
/// those alone do not double the event traffic.
pub fn worth_announcing(chat: &str, meta: &rovsti_wa_proto::MessageMeta) -> bool {
    let mut rest = meta.clone();
    if rest.status == Some(DeliveryStatus::Sent) {
        rest.status = None;
    }
    if !chat.ends_with("@g.us") {
        rest.sender_name = None;
    }
    !rest.is_empty()
}

/// Everything the gateway asks of WhatsApp. Grows in steps 4 to 6 (reactions,
/// read receipts, presence, media, groups, calls).
pub trait WaDriver: Send + Sync + 'static {
    /// Starts (or restarts) the session. `link` is set for a first link or a
    /// relink and `None` when resuming a stored session at boot.
    fn start(
        &self,
        instance: InstanceId,
        link: Option<LinkRequest>,
        sink: EventSink,
    ) -> BoxFuture<'_, Result<(), DriverError>>;

    fn stop(&self, instance: &InstanceId) -> BoxFuture<'_, ()>;

    /// Unlinks the account on WhatsApp's side and ends the session.
    fn logout(&self, instance: &InstanceId) -> BoxFuture<'_, Result<(), DriverError>>;

    fn send_text(
        &self,
        instance: &InstanceId,
        req: SendText,
    ) -> BoxFuture<'_, Result<Sent, DriverError>>;

    /// Sends read receipts for `req.ids`.
    fn mark_read(
        &self,
        instance: &InstanceId,
        req: MarkRead,
    ) -> BoxFuture<'_, Result<(), DriverError>>;

    /// Announces this account as online or offline.
    fn set_presence(
        &self,
        instance: &InstanceId,
        available: bool,
    ) -> BoxFuture<'_, Result<(), DriverError>>;

    /// Typing / recording / paused indicator in a chat.
    fn chat_state(
        &self,
        instance: &InstanceId,
        req: ChatState,
    ) -> BoxFuture<'_, Result<(), DriverError>>;

    /// Starts receiving presence updates for a contact.
    fn subscribe_presence(
        &self,
        instance: &InstanceId,
        jid: String,
    ) -> BoxFuture<'_, Result<(), DriverError>>;

    /// Uploads a file and sends it as a media message.
    fn send_media(
        &self,
        instance: &InstanceId,
        req: SendMedia,
    ) -> BoxFuture<'_, Result<Sent, DriverError>>;

    /// Reacts to a message; an empty emoji removes the reaction.
    fn react(
        &self,
        instance: &InstanceId,
        req: SendReaction,
    ) -> BoxFuture<'_, Result<(), DriverError>>;

    /// Downloads and decrypts the media of a stored message, given its
    /// encoded WhatsApp message.
    fn download_media(
        &self,
        instance: &InstanceId,
        raw_proto: Vec<u8>,
    ) -> BoxFuture<'_, Result<Vec<u8>, DriverError>>;

    /// Every group the account takes part in.
    fn groups(&self, instance: &InstanceId) -> BoxFuture<'_, Result<Vec<GroupInfo>, DriverError>>;

    fn group(
        &self,
        instance: &InstanceId,
        jid: String,
    ) -> BoxFuture<'_, Result<GroupInfo, DriverError>>;

    /// Asks WhatsApp which of the user `jids` have an account. Every queried
    /// JID is answered, with `on_whatsapp: false` when WhatsApp did not know it.
    fn lookup_users(
        &self,
        _instance: &InstanceId,
        _jids: Vec<String>,
    ) -> BoxFuture<'_, Result<Vec<WaUser>, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// What the voice-call table asks of WhatsApp for `instance`.
    fn call_backend(&self, _instance: &InstanceId) -> Result<Arc<dyn CallBackend>, DriverError> {
        Err(DriverError::NotConnected)
    }

    /// Replaces the text (or caption) of a message I sent.
    fn edit_message(
        &self,
        _instance: &InstanceId,
        _req: EditMessage,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// Deletes a message for everyone: mine, or (`req.sender` set) someone
    /// else's in a group I administer.
    fn revoke_message(
        &self,
        _instance: &InstanceId,
        _req: RevokeMessage,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// Deletes a message on this account's devices only.
    fn delete_for_me(
        &self,
        _instance: &InstanceId,
        _target: MessageRef,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn star_message(
        &self,
        _instance: &InstanceId,
        _target: MessageRef,
        _starred: bool,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// Archive, pin, mute or mark a chat (synced to the other devices).
    fn chat_action(
        &self,
        _instance: &InstanceId,
        _req: ChatAction,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// Sends played receipts for voice and video notes.
    fn mark_played(
        &self,
        _instance: &InstanceId,
        _req: MarkRead,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// About text, picture id and business details of users and groups.
    fn profiles(
        &self,
        _instance: &InstanceId,
        _jids: Vec<String>,
    ) -> BoxFuture<'_, Result<Vec<FetchedProfile>, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// The profile picture of a user or group: the small preview or the full
    /// image. Nothing is downloaded when it is still `known_id`.
    fn fetch_picture(
        &self,
        _instance: &InstanceId,
        _jid: String,
        _preview: bool,
        _known_id: Option<String>,
    ) -> BoxFuture<'_, Result<PictureFetch, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }
}

/// Refuses to start sessions, so the gateway can serve its API without
/// WhatsApp (tests and builds without a driver).
#[derive(Debug, Default)]
pub struct UnlinkedDriver;

impl WaDriver for UnlinkedDriver {
    fn start(
        &self,
        _instance: InstanceId,
        _link: Option<LinkRequest>,
        _sink: EventSink,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async {
            Err(DriverError::Failed(
                "this build has no WhatsApp driver".to_owned(),
            ))
        })
    }

    fn stop(&self, _instance: &InstanceId) -> BoxFuture<'_, ()> {
        Box::pin(async {})
    }

    fn logout(&self, _instance: &InstanceId) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Ok(()) })
    }

    fn send_text(
        &self,
        _instance: &InstanceId,
        _req: SendText,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn mark_read(
        &self,
        _instance: &InstanceId,
        _req: MarkRead,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn set_presence(
        &self,
        _instance: &InstanceId,
        _available: bool,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn chat_state(
        &self,
        _instance: &InstanceId,
        _req: ChatState,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn subscribe_presence(
        &self,
        _instance: &InstanceId,
        _jid: String,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn send_media(
        &self,
        _instance: &InstanceId,
        _req: SendMedia,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn react(
        &self,
        _instance: &InstanceId,
        _req: SendReaction,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn download_media(
        &self,
        _instance: &InstanceId,
        _raw_proto: Vec<u8>,
    ) -> BoxFuture<'_, Result<Vec<u8>, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn groups(&self, _instance: &InstanceId) -> BoxFuture<'_, Result<Vec<GroupInfo>, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    fn group(
        &self,
        _instance: &InstanceId,
        _jid: String,
    ) -> BoxFuture<'_, Result<GroupInfo, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }
}

#[cfg(any(test, feature = "fake"))]
pub use fake::FakeDriver;

#[cfg(any(test, feature = "fake"))]
mod fake {
    use std::collections::HashMap;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicU64, Ordering};

    use rovsti_wa_proto::{
        InstanceState, InstanceStatus, MediaMeta, MessageBody, MessageMeta, Quoted,
    };

    use super::*;
    use crate::wa::calls::fake::{FakeCalls, offer};

    /// Scripted in-memory driver: linking emits a `Qr` (or `PairCode`) and
    /// then `State(Connected)`; sent texts come back as `Message` events.
    #[derive(Default)]
    pub struct FakeDriver {
        running: Mutex<HashMap<InstanceId, EventSink>>,
        started: Mutex<Vec<(InstanceId, Option<LinkRequest>)>>,
        ops: Mutex<Vec<String>>,
        groups: Mutex<Vec<GroupInfo>>,
        registered: Mutex<Vec<String>>,
        send_error: Mutex<Option<DriverError>>,
        calls: Arc<FakeCalls>,
        counter: AtomicU64,
        profiles: Mutex<HashMap<String, FetchedProfile>>,
        pictures: Mutex<HashMap<String, (String, Vec<u8>)>>,
    }

    impl std::fmt::Debug for FakeDriver {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("FakeDriver")
        }
    }

    impl FakeDriver {
        pub fn new() -> Self {
            Self::default()
        }

        /// Every `start` call so far, in order.
        pub fn started(&self) -> Vec<(InstanceId, Option<LinkRequest>)> {
            self.started
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        }

        /// Receipts, presence and chat-state calls so far, as short strings.
        pub fn ops(&self) -> Vec<String> {
            self.ops.lock().unwrap_or_else(|e| e.into_inner()).clone()
        }

        /// The groups `groups()` and `group()` report.
        pub fn set_groups(&self, groups: Vec<GroupInfo>) {
            *self.groups.lock().unwrap_or_else(|e| e.into_inner()) = groups;
        }

        /// The JIDs `lookup_users` reports as being on WhatsApp.
        pub fn set_registered(&self, jids: Vec<String>) {
            *self.registered.lock().unwrap_or_else(|e| e.into_inner()) = jids;
        }

        /// Makes every `send_text` fail with `error` (`None` restores it).
        pub fn fail_sends(&self, error: Option<DriverError>) {
            *self.send_error.lock().unwrap_or_else(|e| e.into_inner()) = error;
        }

        /// The scripted call backend every instance shares.
        pub fn calls(&self) -> &Arc<FakeCalls> {
            &self.calls
        }

        /// Makes `instance` ring: an incoming call `call_id` from `from`.
        pub fn ring(&self, instance: &InstanceId, call_id: &str, from: &str, name: Option<&str>) {
            if let Some(sink) = self.sink(instance) {
                sink.calls().on_incoming(&offer(call_id, from, name));
            }
        }

        /// What `profiles` answers for `profile.jid`.
        pub fn set_profile(&self, profile: FetchedProfile) {
            lock(&self.profiles).insert(profile.jid.clone(), profile);
        }

        /// The picture `fetch_picture` finds for `jid` (`None` removes it).
        pub fn set_picture(&self, jid: &str, picture: Option<(&str, &[u8])>) {
            let mut pictures = lock(&self.pictures);
            match picture {
                Some((id, bytes)) => {
                    pictures.insert(jid.to_owned(), (id.to_owned(), bytes.to_vec()));
                }
                None => {
                    pictures.remove(jid);
                }
            }
        }

        /// The event sink of a running instance, to script what WhatsApp
        /// would report (incoming messages, edits, app-state changes).
        pub fn sink_of(&self, instance: &InstanceId) -> Option<EventSink> {
            self.sink(instance)
        }

        /// Delivers an incoming message with its extras.
        pub async fn deliver(
            &self,
            instance: &InstanceId,
            message: WaMessage,
            extras: MessageExtras,
        ) {
            if let Some(sink) = self.sink(instance) {
                sink.emit_full(message, extras).await;
            }
        }

        fn next_id(&self) -> String {
            let n = self.counter.fetch_add(1, Ordering::Relaxed) + 1;
            format!("FAKE{n:06}")
        }

        fn record(&self, instance: &InstanceId, op: String) -> Result<(), DriverError> {
            self.sink(instance).ok_or(DriverError::NotConnected)?;
            self.ops.lock().unwrap_or_else(|e| e.into_inner()).push(op);
            Ok(())
        }

        fn sink(&self, instance: &InstanceId) -> Option<EventSink> {
            self.running
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .get(instance)
                .cloned()
        }
    }

    fn own_jid(instance: &InstanceId) -> String {
        format!("fake{instance}@s.whatsapp.net")
    }

    fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
        m.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn quoted(id: Option<&str>) -> Option<Quoted> {
        id.filter(|id| !id.is_empty()).map(|id| Quoted {
            id: id.to_owned(),
            ..Default::default()
        })
    }

    impl WaDriver for FakeDriver {
        fn start(
            &self,
            instance: InstanceId,
            link: Option<LinkRequest>,
            sink: EventSink,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            Box::pin(async move {
                self.started
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push((instance.clone(), link.clone()));
                self.running
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .insert(instance.clone(), sink.clone());
                match &link {
                    Some(LinkRequest::Qr) => {
                        sink.emit(GatewayEvent::Qr {
                            payload: format!("fake-qr:{instance}"),
                            valid_for_ms: 20_000,
                        })
                        .await;
                    }
                    Some(LinkRequest::PhoneNumber { .. }) => {
                        sink.emit(GatewayEvent::PairCode {
                            code: "FAKE-1234".to_owned(),
                            valid_for_ms: 120_000,
                        })
                        .await;
                    }
                    None => {}
                }
                sink.emit(GatewayEvent::State(InstanceStatus {
                    instance_id: instance.to_string(),
                    state: InstanceState::Connected,
                    own_jid: Some(own_jid(&instance)),
                    push_name: Some("Fake".to_owned()),
                }))
                .await;
                Ok(())
            })
        }

        fn stop(&self, instance: &InstanceId) -> BoxFuture<'_, ()> {
            let instance = instance.clone();
            Box::pin(async move {
                self.running
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&instance);
            })
        }

        fn logout(&self, instance: &InstanceId) -> BoxFuture<'_, Result<(), DriverError>> {
            let instance = instance.clone();
            Box::pin(async move {
                let sink = self
                    .running
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&instance);
                if let Some(sink) = sink {
                    sink.emit(GatewayEvent::State(InstanceStatus {
                        instance_id: instance.to_string(),
                        state: InstanceState::LoggedOut,
                        own_jid: None,
                        push_name: None,
                    }))
                    .await;
                }
                Ok(())
            })
        }

        fn send_text(
            &self,
            instance: &InstanceId,
            req: SendText,
        ) -> BoxFuture<'_, Result<Sent, DriverError>> {
            let instance = instance.clone();
            Box::pin(async move {
                let failure = self
                    .send_error
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .clone();
                if let Some(error) = failure {
                    return Err(error);
                }
                let sink = self.sink(&instance).ok_or(DriverError::NotConnected)?;
                let timestamp_ms = now_ms();
                let message_id = self.next_id();
                let meta = MessageMeta {
                    quoted: quoted(req.quoted_id.as_deref()),
                    status: Some(DeliveryStatus::Sent),
                    ..Default::default()
                };
                sink.emit_full(
                    WaMessage {
                        id: message_id.clone(),
                        chat: req.chat,
                        sender: Some(own_jid(&instance)),
                        from_me: true,
                        timestamp_ms,
                        body: MessageBody::Text(req.text),
                        reactions: vec![],
                    },
                    MessageExtras {
                        meta,
                        ..Default::default()
                    },
                )
                .await;
                Ok(Sent {
                    message_id,
                    timestamp_ms,
                })
            })
        }

        fn mark_read(
            &self,
            instance: &InstanceId,
            req: MarkRead,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(instance, format!("read {} {}", req.chat, req.ids.join(",")));
            Box::pin(async move { result })
        }

        fn set_presence(
            &self,
            instance: &InstanceId,
            available: bool,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(instance, format!("presence {available}"));
            Box::pin(async move { result })
        }

        fn chat_state(
            &self,
            instance: &InstanceId,
            req: ChatState,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(instance, format!("chatstate {} {:?}", req.chat, req.state));
            Box::pin(async move { result })
        }

        fn subscribe_presence(
            &self,
            instance: &InstanceId,
            jid: String,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(instance, format!("subscribe {jid}"));
            Box::pin(async move { result })
        }

        /// Sent media come back as `Media` messages whose stored "encoded
        /// message" is the file itself, which `download_media` returns as is.
        fn send_media(
            &self,
            instance: &InstanceId,
            req: SendMedia,
        ) -> BoxFuture<'_, Result<Sent, DriverError>> {
            let instance = instance.clone();
            Box::pin(async move {
                let sink = self.sink(&instance).ok_or(DriverError::NotConnected)?;
                let timestamp_ms = now_ms();
                let message_id = self.next_id();
                let size = req.data.len() as u64;
                let media = MediaMeta {
                    file_name: req.file_name.clone(),
                    seconds: req.seconds,
                    voice_note: req.voice_note,
                    ..Default::default()
                };
                let meta = MessageMeta {
                    quoted: quoted(req.quoted_id.as_deref()),
                    status: Some(DeliveryStatus::Sent),
                    media: Some(media).filter(|m| *m != MediaMeta::default()),
                    ..Default::default()
                };
                sink.emit_full(
                    WaMessage {
                        id: message_id.clone(),
                        chat: req.chat,
                        sender: Some(own_jid(&instance)),
                        from_me: true,
                        timestamp_ms,
                        body: MessageBody::Media {
                            kind: req.kind,
                            caption: req.caption,
                            mime: req.mime,
                            size,
                        },
                        reactions: vec![],
                    },
                    MessageExtras {
                        meta,
                        raw_proto: Some(req.data),
                        ..Default::default()
                    },
                )
                .await;
                Ok(Sent {
                    message_id,
                    timestamp_ms,
                })
            })
        }

        fn react(
            &self,
            instance: &InstanceId,
            req: SendReaction,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let instance = instance.clone();
            Box::pin(async move {
                self.record(
                    &instance,
                    format!("react {} {} {:?}", req.chat, req.message_id, req.emoji),
                )?;
                let sink = self.sink(&instance).ok_or(DriverError::NotConnected)?;
                sink.emit_reaction(
                    req.chat,
                    req.message_id,
                    Reaction {
                        sender: None,
                        from_me: true,
                        emoji: req.emoji,
                    },
                )
                .await;
                Ok(())
            })
        }

        fn download_media(
            &self,
            instance: &InstanceId,
            raw_proto: Vec<u8>,
        ) -> BoxFuture<'_, Result<Vec<u8>, DriverError>> {
            let result = self
                .record(instance, "download".to_owned())
                .map(|()| raw_proto);
            Box::pin(async move { result })
        }

        fn groups(
            &self,
            instance: &InstanceId,
        ) -> BoxFuture<'_, Result<Vec<GroupInfo>, DriverError>> {
            let result = self
                .sink(instance)
                .ok_or(DriverError::NotConnected)
                .map(|_| {
                    self.groups
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .clone()
                });
            Box::pin(async move { result })
        }

        fn group(
            &self,
            instance: &InstanceId,
            jid: String,
        ) -> BoxFuture<'_, Result<GroupInfo, DriverError>> {
            let result = self
                .sink(instance)
                .ok_or(DriverError::NotConnected)
                .and_then(|_| {
                    self.groups
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .iter()
                        .find(|g| g.jid == jid)
                        .cloned()
                        .ok_or_else(|| DriverError::BadRequest(format!("unknown group {jid}")))
                });
            Box::pin(async move { result })
        }

        fn lookup_users(
            &self,
            instance: &InstanceId,
            jids: Vec<String>,
        ) -> BoxFuture<'_, Result<Vec<WaUser>, DriverError>> {
            let result = self
                .record(instance, format!("lookup {}", jids.len()))
                .map(|()| {
                    let registered = self.registered.lock().unwrap_or_else(|e| e.into_inner());
                    jids.into_iter()
                        .map(|jid| WaUser {
                            on_whatsapp: registered.contains(&jid),
                            jid,
                            business: false,
                            verified_name: None,
                        })
                        .collect()
                });
            Box::pin(async move { result })
        }

        fn call_backend(&self, instance: &InstanceId) -> Result<Arc<dyn CallBackend>, DriverError> {
            self.sink(instance).ok_or(DriverError::NotConnected)?;
            Ok(Arc::clone(&self.calls) as Arc<dyn CallBackend>)
        }

        fn edit_message(
            &self,
            instance: &InstanceId,
            req: EditMessage,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(
                instance,
                format!("edit {} {} {:?}", req.chat, req.message_id, req.text),
            );
            Box::pin(async move { result })
        }

        fn revoke_message(
            &self,
            instance: &InstanceId,
            req: RevokeMessage,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let by = req.sender.as_deref().unwrap_or("me");
            let result = self.record(
                instance,
                format!("revoke {} {} {by}", req.chat, req.message_id),
            );
            Box::pin(async move { result })
        }

        fn delete_for_me(
            &self,
            instance: &InstanceId,
            target: MessageRef,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(instance, format!("delete {} {}", target.chat, target.id));
            Box::pin(async move { result })
        }

        fn star_message(
            &self,
            instance: &InstanceId,
            target: MessageRef,
            starred: bool,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(
                instance,
                format!("star {} {} {starred}", target.chat, target.id),
            );
            Box::pin(async move { result })
        }

        fn chat_action(
            &self,
            instance: &InstanceId,
            req: ChatAction,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(instance, format!("chat {} {:?}", req.chat, req.action));
            Box::pin(async move { result })
        }

        fn mark_played(
            &self,
            instance: &InstanceId,
            req: MarkRead,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(
                instance,
                format!("played {} {}", req.chat, req.ids.join(",")),
            );
            Box::pin(async move { result })
        }

        fn profiles(
            &self,
            instance: &InstanceId,
            jids: Vec<String>,
        ) -> BoxFuture<'_, Result<Vec<FetchedProfile>, DriverError>> {
            let result = self
                .record(instance, format!("profiles {}", jids.len()))
                .map(|()| {
                    let known = lock(&self.profiles);
                    jids.into_iter()
                        .map(|jid| {
                            known.get(&jid).cloned().unwrap_or(FetchedProfile {
                                jid,
                                ..Default::default()
                            })
                        })
                        .collect()
                });
            Box::pin(async move { result })
        }

        fn fetch_picture(
            &self,
            instance: &InstanceId,
            jid: String,
            preview: bool,
            known_id: Option<String>,
        ) -> BoxFuture<'_, Result<PictureFetch, DriverError>> {
            let size = if preview { "preview" } else { "full" };
            let result =
                self.record(instance, format!("picture {jid} {size}"))
                    .map(|()| match lock(&self.pictures).get(&jid) {
                        None => PictureFetch::None,
                        Some((id, _)) if Some(id) == known_id.as_ref() => PictureFetch::Unchanged,
                        Some((id, bytes)) => {
                            // The full picture is the preview bytes twice, so tests
                            // can tell them apart.
                            let bytes = if preview {
                                bytes.clone()
                            } else {
                                [bytes.as_slice(), bytes.as_slice()].concat()
                            };
                            PictureFetch::New {
                                id: id.clone(),
                                bytes,
                            }
                        }
                    });
            Box::pin(async move { result })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::Quoted;

    #[test]
    fn moved_ticks_become_receipts_and_played_meta() {
        let at = |status| MessageMeta {
            status: Some(status),
            ..Default::default()
        };
        let events = tick_events(
            "c",
            vec![
                ("d".into(), at(DeliveryStatus::Delivered)),
                ("r".into(), at(DeliveryStatus::Read)),
                ("p".into(), at(DeliveryStatus::Played)),
            ],
        );
        assert_eq!(
            events,
            [
                GatewayEvent::Receipt {
                    chat: "c".into(),
                    ids: vec!["d".into()],
                    kind: ReceiptKind::Delivered,
                },
                GatewayEvent::Receipt {
                    chat: "c".into(),
                    ids: vec!["r".into(), "p".into()],
                    kind: ReceiptKind::Read,
                },
                GatewayEvent::MessageMeta {
                    chat: "c".into(),
                    id: "p".into(),
                    body: None,
                    meta: Box::new(at(DeliveryStatus::Played)),
                },
            ]
        );
        assert!(tick_events("c", Vec::new()).is_empty());
    }

    #[test]
    fn only_meta_worth_a_second_event_is_announced() {
        let dm = "1@s.whatsapp.net";
        let group = "1-2@g.us";
        let sent = MessageMeta {
            status: Some(DeliveryStatus::Sent),
            ..Default::default()
        };
        assert!(!worth_announcing(dm, &MessageMeta::default()));
        assert!(!worth_announcing(dm, &sent));
        let named = MessageMeta {
            sender_name: Some("Ada".into()),
            ..Default::default()
        };
        assert!(!worth_announcing(dm, &named));
        assert!(worth_announcing(group, &named));
        let reply = MessageMeta {
            quoted: Some(Quoted::default()),
            ..sent
        };
        assert!(worth_announcing(dm, &reply));
    }
}
