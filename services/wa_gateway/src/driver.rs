//! The seam between the gateway and WhatsApp itself. The real driver
//! ([`crate::wa::WaRuntime`]) wraps whatsapp-rust; [`FakeDriver`] scripts the same events for tests.

use std::sync::Arc;

use futures::future::BoxFuture;
use rosty_wa_proto::{
    ChatAction, ChatState, DELETED_BY_ME, DeliveryStatus, EditMessage, ForwardMessage,
    GatewayEvent, GroupInfo, HistorySyncType, IdentitySource, LinkRequest, MarkRead, MediaKind,
    MessageMeta, Reaction, RevokeMessage, SendReaction, SendText, Sent, WaMessage, WaProfile,
    WaUser, event::ReceiptKind, is_status_chat,
};

use crate::hub::Hub;
use crate::now_ms;
use crate::push::{MessagePreview, PushKind, Pusher, should_push};
use crate::store::{
    ChatChange, FetchedProfile, GroupReceipt, InstanceId, LearnedPair, MessageChange,
    MessageExtras, NewPair, ProfileChange, Store, StoredStatus, WireIds,
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

/// Pairs learned in one go (a history sync, the library's whole table) are
/// announced one by one up to this many. Above it the apps get one `resync`
/// instead and page `GET /v1/identity/mappings` from their cursor: thousands
/// of events would only push everything else out of the event log.
pub const MAX_PAIR_EVENTS: usize = 200;

/// What the apps hear about `learned` pairs: an `identity_mapped` each, or
/// one `resync` when there are more than [`MAX_PAIR_EVENTS`].
pub fn pair_events(learned: &[LearnedPair]) -> Vec<GatewayEvent> {
    if learned.len() > MAX_PAIR_EVENTS {
        return vec![GatewayEvent::Resync { epoch: None }];
    }
    learned
        .iter()
        .map(|pair| GatewayEvent::IdentityMapped {
            lid: pair.mapping.lid.clone(),
            pn: pair.mapping.pn.clone(),
            source: pair.mapping.source.clone(),
            learned_ms: pair.mapping.learned_ms,
        })
        .collect()
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
    /// A JPEG preview of a photo, video or document (checked by the route).
    pub thumbnail: Option<Vec<u8>>,
    /// Pixel size of a photo, video or sticker.
    pub width: Option<u32>,
    pub height: Option<u32>,
    /// Waveform of a voice note: 64 samples, each 0..=100 (checked by the
    /// route).
    pub waveform: Option<Vec<u8>>,
    /// A video that plays as a GIF, or an animated sticker.
    pub animated: bool,
    /// View once: photo, video or audio only (checked by the route).
    pub view_once: bool,
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
    /// WhatsApp answered "rate-overlimit" (code 429); `retry_after_s` is its
    /// `backoff` hint, when it sent one.
    #[error("WhatsApp rate limit reached")]
    RateLimited { retry_after_s: Option<u32> },
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
                    .set_state_with_lid(
                        self.instance.clone(),
                        status.state,
                        status.own_jid.clone(),
                        status.push_name.clone(),
                        status.own_lid.clone(),
                    )
                    .await;
                if let Err(e) = saved {
                    log::warn!("{}: saving state failed: {e}", self.instance);
                }
                self.publish_state(status.clone()).await;
                return;
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

    /// Publishes a `state` event with what only the gateway knows added: the
    /// process epoch, the account epoch and the history sync. The fields the
    /// driver set stay as it set them.
    async fn publish_state(&self, mut status: rosty_wa_proto::InstanceStatus) {
        status.epoch = Some(self.hub.epoch().to_owned());
        match self.store.instance_status(self.instance.clone()).await {
            Ok(Some(stored)) => {
                status.account_epoch = stored.account_epoch;
                status.history = stored.history;
            }
            Ok(None) => {}
            Err(e) => log::warn!("{}: reading the status failed: {e}", self.instance),
        }
        self.hub
            .publish(&self.instance, now_ms(), GatewayEvent::State(status));
    }

    /// The account `pn` (a phone-number JID) linked or connected. When the
    /// instance holds the data of another number, that data is cleared and
    /// the account epoch raised; `cleanup` then runs (the driver deletes the
    /// account's cached files there) and only after it the apps hear
    /// `account_changed`. Nothing happens when either number is unknown or
    /// they are the same.
    ///
    /// `paired` is set for a new pairing and says whether the full history
    /// was asked for ([`Store::link_account`]).
    pub async fn account_linked<F>(&self, pn: Option<String>, paired: Option<bool>, cleanup: F)
    where
        F: AsyncFnOnce(),
    {
        let change = match self
            .store
            .link_account(self.instance.clone(), pn, paired)
            .await
        {
            Ok(change) => change,
            Err(e) => {
                log::warn!("{}: checking the linked account failed: {e}", self.instance);
                return;
            }
        };
        let Some(change) = change else {
            return;
        };
        cleanup().await;
        self.publish(GatewayEvent::AccountChanged {
            old_pn: Some(change.old_pn),
            new_pn: change.new_pn,
        });
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

    /// Stores a status update and tells the apps. Status updates are never a
    /// chat, an unread count or a push.
    pub async fn emit_status(&self, status: StoredStatus) {
        let author = status.item.author.clone();
        match self
            .store
            .insert_status(self.instance.clone(), status, now_ms())
            .await
        {
            Ok(true) => self.publish(GatewayEvent::StatusChanged {
                author: Some(author),
            }),
            Ok(false) => {}
            Err(e) => log::warn!("{}: saving a status update failed: {e}", self.instance),
        }
    }

    /// Status updates from a history sync, announced with one event.
    pub async fn import_statuses(&self, statuses: Vec<StoredStatus>) {
        let mut fresh = false;
        let now = now_ms();
        for status in statuses {
            match self
                .store
                .insert_status(self.instance.clone(), status, now)
                .await
            {
                Ok(new) => fresh |= new,
                Err(e) => {
                    log::warn!("{}: saving a status update failed: {e}", self.instance);
                    break;
                }
            }
        }
        if fresh {
            self.publish(GatewayEvent::StatusChanged { author: None });
        }
    }

    /// The author deleted a status update.
    pub async fn revoke_status(&self, id: &str) {
        match self
            .store
            .delete_status(self.instance.clone(), id.to_owned())
            .await
        {
            Ok(Some(author)) => self.publish(GatewayEvent::StatusChanged {
                author: Some(author),
            }),
            Ok(None) => {}
            Err(e) => log::warn!("{}: deleting a status update failed: {e}", self.instance),
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

    /// Stores the chats of one history-sync chunk, in bounded transactions
    /// ([`Store::import_history`]). The chunk is announced by
    /// [`Self::history_synced`] once all of it is stored. `None` when the
    /// store failed.
    pub async fn import_history(
        &self,
        chats: Vec<crate::store::HistoryChat>,
    ) -> Option<crate::store::HistoryImport> {
        match self
            .store
            .import_history(self.instance.clone(), chats)
            .await
        {
            Ok(report) => Some(report),
            Err(e) => {
                log::warn!("{}: saving history failed: {e}", self.instance);
                None
            }
        }
    }

    /// One chunk of a history sync is stored: remembers how far the sync got
    /// (kept across a restart) and announces the chunk with a single
    /// `HistorySynced` event (not one event per message).
    pub async fn history_synced(
        &self,
        sync_type: HistorySyncType,
        progress: Option<u32>,
        report: crate::store::HistoryImport,
    ) {
        let noted = self
            .store
            .note_history(self.instance.clone(), sync_type, progress, now_ms())
            .await;
        if let Err(e) = noted {
            log::warn!("{}: saving the history state failed: {e}", self.instance);
        }
        self.publish(GatewayEvent::HistorySynced {
            chats: report.chats,
            progress,
            sync_type: Some(sync_type),
            messages: report.messages,
            oldest_ms: report.oldest_ms,
        });
    }

    /// The stored chats addressed by a LID.
    pub async fn lid_chats(&self) -> Result<Vec<String>, crate::store::StoreError> {
        self.store.lid_chats(self.instance.clone()).await
    }

    /// Every LID something is stored under: chats, authors, reactors,
    /// receipts, quoted authors, mentions and profiles.
    pub async fn lids_seen(&self) -> Result<Vec<String>, crate::store::StoreError> {
        self.store.lids_seen(self.instance.clone()).await
    }

    /// Moves everything stored under `from` (a LID) to `into` (its phone
    /// number): the chat, when there is one, and every row of any chat that
    /// names the person. The apps hear `chat_merged` when a chat moved.
    /// `None` when nothing was stored under `from` or the store failed.
    pub async fn merge_chat(&self, from: &str, into: &str) -> Option<crate::store::MergeReport> {
        match self
            .store
            .merge_chat(self.instance.clone(), from.to_owned(), into.to_owned())
            .await
        {
            Ok(report) if report.merged || report.rewritten > 0 => {
                log::info!(
                    "{}: lid-merge: {from} -> {into}: {} moved, {} deduped, {} rows renamed",
                    self.instance,
                    report.moved,
                    report.deduped,
                    report.rewritten
                );
                if report.merged {
                    self.publish(GatewayEvent::ChatMerged {
                        from: from.to_owned(),
                        into: into.to_owned(),
                    });
                }
                Some(report)
            }
            Ok(_) => None,
            Err(e) => {
                log::warn!("{}: merging {from} into {into} failed: {e}", self.instance);
                None
            }
        }
    }

    /// Records that `lid` and `pn` (bare ids) are one person. See
    /// [`Self::learn_pairs`].
    pub async fn learn_pair(&self, lid: &str, pn: &str, source: IdentitySource) {
        self.learn_pairs(vec![NewPair::new(lid, pn, source, now_ms())])
            .await;
    }

    /// Records LID and phone-number pairs. For each one that is new, or
    /// whose LID moved to another number, the apps hear `identity_mapped`
    /// ([`pair_events`]) and whatever is stored under the LID moves to the
    /// number ([`Self::merge_chat`]). Returns those pairs.
    pub async fn learn_pairs(&self, pairs: Vec<NewPair>) -> Vec<LearnedPair> {
        let learned = match self.store.learn_pairs(self.instance.clone(), pairs).await {
            Ok(learned) => learned,
            Err(e) => {
                log::warn!("{}: saving identity pairs failed: {e}", self.instance);
                return Vec::new();
            }
        };
        if learned.is_empty() {
            return learned;
        }
        for event in pair_events(&learned) {
            self.publish(event);
        }
        // A batch names many people nothing is stored about: look once at
        // what is stored instead of searching every table per pair.
        let stored = if learned.len() > 1 {
            self.lids_seen().await.ok()
        } else {
            None
        };
        for pair in &learned {
            let mapping = &pair.mapping;
            if stored
                .as_ref()
                .is_none_or(|lids| lids.contains(&mapping.lid))
            {
                self.merge_chat(&mapping.lid, &mapping.pn).await;
            }
        }
        learned
    }

    /// A contact changed number: tells the apps and records the pairs
    /// WhatsApp named. The ids are bare.
    pub async fn number_changed(
        &self,
        old_pn: &str,
        new_pn: &str,
        old_lid: Option<&str>,
        new_lid: Option<&str>,
    ) {
        self.publish(GatewayEvent::IdentityNumberChanged {
            old_pn: old_pn.to_owned(),
            new_pn: new_pn.to_owned(),
            old_lid: old_lid.map(str::to_owned),
            new_lid: new_lid.map(str::to_owned),
        });
        let now = now_ms();
        let mut pairs = Vec::new();
        // The old pair is history: only worth knowing when nothing better
        // is, and only when the LID is not the one that moves to the new
        // number.
        if let Some(old_lid) = old_lid.filter(|lid| Some(*lid) != new_lid) {
            pairs.push(NewPair::new(old_lid, old_pn, IdentitySource::Other, now));
        }
        if let Some(new_lid) = new_lid {
            pairs.push(NewPair::new(
                new_lid,
                new_pn,
                IdentitySource::NumberChange,
                now,
            ));
        }
        self.learn_pairs(pairs).await;
    }

    /// The phone-number JID the gateway learned for `lid` (bare).
    pub async fn pn_of_lid(&self, lid: &str) -> Option<String> {
        self.store
            .pn_of_lid(self.instance.clone(), lid.to_owned())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading an identity pair failed: {e}", self.instance);
                None
            })
    }

    /// The LID the gateway learned for the phone-number JID `pn` (bare).
    pub async fn lid_of_pn(&self, pn: &str) -> Option<String> {
        self.store
            .lid_of_pn(self.instance.clone(), pn.to_owned())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading an identity pair failed: {e}", self.instance);
                None
            })
    }

    /// The ids a stored message arrived under (empty when not recorded).
    pub async fn wire_ids(&self, chat: &str, id: &str) -> WireIds {
        self.store
            .wire_ids(self.instance.clone(), chat.to_owned(), id.to_owned())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading wire ids failed: {e}", self.instance);
                WireIds::default()
            })
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
        // Status updates go through `emit_status`; they are never a chat.
        if is_status_chat(&message.chat) {
            log::debug!("{}: a status update reached the chat path", self.instance);
            return;
        }
        let meta = extras.meta.clone();
        match self
            .store
            .insert_message_full(self.instance.clone(), message.clone(), extras)
            .await
        {
            Ok(true) => {
                // A system line (the disappearing timer changed) is not news.
                if meta.system.is_none() && should_push(&message, now_ms()) {
                    self.pusher.notify(
                        &self.instance,
                        PushKind::Message {
                            chat: message.chat.clone(),
                            count: 1,
                            // Sealed to an app's push key, else never sent.
                            preview: Some(Box::new(MessagePreview::of(
                                &message,
                                meta.sender_name.clone(),
                            ))),
                        },
                    );
                }
            }
            Ok(false) => return,
            Err(e) => log::warn!("{}: saving message failed: {e}", self.instance),
        }
        let (chat, id) = (message.chat.clone(), message.id.clone());
        let is_poll = meta.poll.is_some();
        let announce = worth_announcing(&chat, &meta);
        self.publish(GatewayEvent::Message(message));
        if announce {
            self.publish(GatewayEvent::MessageMeta {
                chat: chat.clone(),
                id: id.clone(),
                body: None,
                meta: Box::new(meta),
            });
        }
        // An edit or delete for everyone that came before the message.
        let pending = self
            .store
            .take_pending(self.instance.clone(), chat.clone(), id.clone())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading early changes failed: {e}", self.instance);
                Vec::new()
            });
        for change in pending {
            self.change_message(&chat, &id, change).await;
        }
        // Votes that came before their poll (E6-WA R5).
        if is_poll {
            self.refresh_poll(&chat, &id).await;
        }
    }

    /// A stored message to forward: as apps see it, its stored protobuf
    /// (media only) and its meta. `None` when unknown.
    pub async fn forward_source(
        &self,
        chat: &str,
        id: &str,
    ) -> Option<(WaMessage, Option<Vec<u8>>, MessageMeta)> {
        let rich = self
            .store
            .message_rich(self.instance.clone(), chat.to_owned(), id.to_owned())
            .await
            .ok()
            .flatten()?;
        let raw = match rich.message.body {
            rosty_wa_proto::MessageBody::Media { .. } => self
                .store
                .media_source(self.instance.clone(), id.to_owned())
                .await
                .ok()
                .flatten()
                .and_then(|(_, raw)| raw),
            _ => None,
        };
        Some((rich.message, raw, rich.meta))
    }

    /// The disappearing timer of `chat` in seconds; `None` when off.
    pub async fn chat_timer(&self, chat: &str) -> Option<u32> {
        self.store
            .chat_timer(self.instance.clone(), chat.to_owned())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading a chat timer failed: {e}", self.instance);
                None
            })
    }

    /// Records the chat's disappearing timer as of `at_ms` without a line in
    /// the conversation (a message or the group's metadata named it). The
    /// apps hear the new chat meta when it changed.
    pub async fn learn_chat_timer(&self, chat: &str, seconds: u32, at_ms: i64) -> bool {
        match self
            .store
            .set_chat_timer(self.instance.clone(), chat.to_owned(), seconds, at_ms)
            .await
        {
            Ok(Some(meta)) => {
                self.publish(GatewayEvent::ChatMeta {
                    jid: chat.to_owned(),
                    meta,
                });
                true
            }
            Ok(None) => false,
            Err(e) => {
                log::warn!("{}: saving a chat timer failed: {e}", self.instance);
                false
            }
        }
    }

    /// Someone set the chat's disappearing timer (a protocol message, a group
    /// notification, or me through the gateway): records it and, when it
    /// changed, stores and announces the line "turned on / off" as message
    /// `note_id` from `by` ([`DELETED_BY_ME`] for me).
    pub async fn chat_timer_changed(&self, change: TimerChange) {
        if !self
            .learn_chat_timer(&change.chat, change.seconds, change.at_ms)
            .await
        {
            return;
        }
        let from_me = change.by.as_deref() == Some(DELETED_BY_ME);
        let sender = change
            .by
            .clone()
            .filter(|_| !from_me && change.chat.ends_with("@g.us"));
        let meta = MessageMeta {
            system: Some(rosty_wa_proto::SystemNote::Disappearing {
                seconds: change.seconds,
                by: change.by.clone(),
            }),
            ..MessageMeta::default()
        };
        self.emit_full(
            WaMessage {
                id: change.note_id,
                chat: change.chat,
                sender,
                from_me,
                timestamp_ms: change.at_ms,
                body: rosty_wa_proto::MessageBody::Unsupported,
                reactions: Vec::new(),
                chat_alt: None,
                sender_alt: None,
            },
            MessageExtras {
                meta,
                ..MessageExtras::default()
            },
        )
        .await;
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
        self.set_status_at(chat, ids, status, now_ms()).await;
    }

    /// [`Self::set_status`] for a receipt that came at `at_ms` (kept for
    /// message info).
    pub async fn set_status_at(
        &self,
        chat: &str,
        ids: Vec<String>,
        status: DeliveryStatus,
        at_ms: i64,
    ) {
        match self
            .store
            .set_status_at(self.instance.clone(), chat.to_owned(), ids, status, at_ms)
            .await
        {
            Ok(moved) => self.publish_ticks(chat, moved),
            Err(e) => log::warn!("{}: saving receipts failed: {e}", self.instance),
        }
    }

    /// Stores one group member's receipt for my messages and tells the apps
    /// about the messages whose group-wide ticks moved (see
    /// [`crate::store::group_status`]).
    pub async fn group_receipt(&self, chat: &str, receipt: GroupReceipt) {
        self.group_receipt_at(chat, receipt, now_ms()).await;
    }

    /// [`Self::group_receipt`] for a receipt that came at `at_ms`.
    pub async fn group_receipt_at(&self, chat: &str, receipt: GroupReceipt, at_ms: i64) {
        match self
            .store
            .record_group_receipt_at(self.instance.clone(), chat.to_owned(), receipt, at_ms)
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

    /// Marks a message deleted on this account's devices (its content is
    /// kept) and tells the apps.
    pub async fn delete_message(&self, chat: &str, id: &str) {
        let at_ms = now_ms();
        match self
            .store
            .delete_message(self.instance.clone(), chat.to_owned(), id.to_owned(), at_ms)
            .await
        {
            Ok(true) => self.publish(GatewayEvent::MessageDeleted {
                chat: chat.to_owned(),
                id: id.to_owned(),
                deleted_at: Some(at_ms),
                deleted_by: Some(DELETED_BY_ME.to_owned()),
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

    /// The poll `poll_id` of `chat` with what voting in it needs; `None` when unknown.
    pub async fn poll_source(&self, chat: &str, poll_id: &str) -> Option<crate::store::PollSource> {
        self.store
            .poll_source(self.instance.clone(), chat.to_owned(), poll_id.to_owned())
            .await
            .unwrap_or_else(|e| {
                log::warn!("{}: reading a poll failed: {e}", self.instance);
                None
            })
    }

    /// A vote arrived, still encrypted (E6-WA R5): it is kept (the voter's newest only) and the poll counted again
    /// when it is stored; a vote that came before its poll waits for it ([`Self::emit_full`]).
    pub async fn poll_vote(&self, chat: &str, poll_id: &str, vote: crate::store::NewVote) {
        match self
            .store
            .record_vote(
                self.instance.clone(),
                chat.to_owned(),
                poll_id.to_owned(),
                vote,
            )
            .await
        {
            Ok(true) => self.refresh_poll(chat, poll_id).await,
            Ok(false) => {}
            Err(e) => log::warn!("{}: saving a poll vote failed: {e}", self.instance),
        }
    }

    /// My vote went out (E6-WA R5): it is counted at once, as [`DELETED_BY_ME`].
    pub async fn my_poll_vote(&self, chat: &str, poll_id: &str, options: Vec<String>) {
        self.poll_vote(
            chat,
            poll_id,
            crate::store::NewVote {
                voter: DELETED_BY_ME.to_owned(),
                voter_alt: None,
                at_ms: now_ms(),
                options: Some(options),
                encrypted: None,
            },
        )
        .await;
    }

    /// Every id of `ids` with its other form where a pair is known.
    async fn with_alts(&self, ids: Vec<String>) -> Vec<String> {
        let mut out = Vec::new();
        for id in ids {
            let alt = if id.ends_with("@lid") {
                self.pn_of_lid(&id).await
            } else {
                self.lid_of_pn(&id).await
            };
            out.push(id);
            out.extend(alt);
        }
        out
    }

    /// Opens the poll's encrypted votes with its secret, counts them and publishes the new tally (`message_meta`)
    /// when it changed. Nothing happens while the poll is unknown. A vote that does not open (an id the gateway never
    /// learned) stays encrypted and is tried again with the next vote.
    pub async fn refresh_poll(&self, chat: &str, poll_id: &str) {
        let (instance, chat_s, id_s) = (self.instance.clone(), chat.to_owned(), poll_id.to_owned());
        let source = match self
            .store
            .poll_source(instance.clone(), chat_s.clone(), id_s.clone())
            .await
        {
            Ok(Some(source)) => source,
            Ok(None) => return,
            Err(e) => {
                log::warn!("{}: reading a poll failed: {e}", self.instance);
                return;
            }
        };
        let stored = match self
            .store
            .poll_votes(instance.clone(), chat_s.clone(), id_s.clone())
            .await
        {
            Ok(votes) => votes,
            Err(e) => {
                log::warn!("{}: reading poll votes failed: {e}", self.instance);
                return;
            }
        };
        let own: Vec<String> = source
            .own_jid
            .iter()
            .chain(source.own_lid.iter())
            .cloned()
            .collect();
        let creator = if source.from_me {
            own.clone()
        } else if crate::wa::convert::is_group(chat) {
            let ids: Vec<String> = source
                .sender
                .iter()
                .chain(source.wire_sender.iter())
                .cloned()
                .collect();
            self.with_alts(ids).await
        } else {
            let ids: Vec<String> = std::iter::once(chat.to_owned())
                .chain(source.wire_chat.iter().cloned())
                .collect();
            self.with_alts(ids).await
        };
        let creator = crate::wa::poll::Ids::of(creator.iter().map(String::as_str));
        let names = source.poll.option_names();
        let mut votes = Vec::new();
        for vote in stored {
            if let Some(open) = vote.vote() {
                votes.push(open);
                continue;
            }
            let (Some((payload, iv)), Some(secret)) = (&vote.encrypted, &source.secret) else {
                continue;
            };
            let voter_ids = if vote.voter == DELETED_BY_ME {
                own.clone()
            } else {
                let ids: Vec<String> = std::iter::once(vote.voter.clone())
                    .chain(vote.voter_alt.clone())
                    .collect();
                self.with_alts(ids).await
            };
            let voter = crate::wa::poll::Ids::of(voter_ids.iter().map(String::as_str));
            let Some(picked) =
                crate::wa::poll::open_vote(secret, poll_id, &creator, &voter, payload, iv, &names)
            else {
                log::debug!("{}: a vote of poll {poll_id} did not open", self.instance);
                continue;
            };
            if let Err(e) = self
                .store
                .open_vote(
                    instance.clone(),
                    chat_s.clone(),
                    id_s.clone(),
                    vote.voter.clone(),
                    vote.at_ms,
                    picked.clone(),
                )
                .await
            {
                log::warn!("{}: saving an opened vote failed: {e}", self.instance);
            }
            votes.push(rosty_core::wa_rich::Vote {
                voter: vote.voter,
                voter_alt: vote.voter_alt,
                options: picked,
                at_ms: vote.at_ms,
            });
        }
        let counted = crate::wa::poll::counted(&source.poll, &votes);
        self.change_message(chat, poll_id, MessageChange::Poll(counted))
            .await;
    }
}

/// A change of a chat's disappearing timer ([`EventSink::chat_timer_changed`]).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TimerChange {
    pub chat: String,
    pub seconds: u32,
    /// When it was set (unix ms).
    pub at_ms: i64,
    /// Who set it: [`DELETED_BY_ME`], a JID, or `None` when unknown.
    pub by: Option<String>,
    /// The id of the line stored for it.
    pub note_id: String,
}

/// Whether a new message's meta deserves its own `MessageMeta` event after
/// the `Message` event. The first tick of my own messages is implied, and in
/// a one-to-one chat the sender's push name is the chat's (`ChatMeta`), so
/// those alone do not double the event traffic.
pub fn worth_announcing(chat: &str, meta: &rosty_wa_proto::MessageMeta) -> bool {
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

    /// Sends a copy of a stored message to another chat, marked forwarded
    /// (media relayed from its stored protobuf), and stores the copy.
    fn forward_message(
        &self,
        _instance: &InstanceId,
        _req: ForwardMessage,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// Sends a poll, a place or contact cards with the chat's timer, and
    /// stores it (E6-WA R5). `req.content` was checked.
    fn send_rich(
        &self,
        _instance: &InstanceId,
        _req: rosty_wa_proto::SendRich,
    ) -> BoxFuture<'_, Result<Sent, DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// Votes in a stored poll (an empty choice takes my vote back) and counts
    /// my vote (E6-WA R5).
    fn poll_vote(
        &self,
        _instance: &InstanceId,
        _req: rosty_wa_proto::PollVote,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }

    /// Stops presence updates of a contact (E6-WA R5).
    fn unsubscribe_presence(
        &self,
        _instance: &InstanceId,
        _jid: String,
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

    /// Read receipts for status updates, one batch per author: the receipt
    /// goes to `status@broadcast` and names `participant` (the author as
    /// WhatsApp addressed the update).
    fn mark_status_viewed(
        &self,
        _instance: &InstanceId,
        _batches: Vec<StatusReceipt>,
    ) -> BoxFuture<'_, Result<(), DriverError>> {
        Box::pin(async { Err(DriverError::NotConnected) })
    }
}

/// The status updates of one author that were seen.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatusReceipt {
    pub participant: String,
    pub ids: Vec<String>,
}

/// Groups seen updates into one receipt per author, in first-seen order.
pub fn status_receipts(seen: impl IntoIterator<Item = (String, String)>) -> Vec<StatusReceipt> {
    let mut out: Vec<StatusReceipt> = Vec::new();
    for (participant, id) in seen {
        match out.iter_mut().find(|r| r.participant == participant) {
            Some(receipt) => receipt.ids.push(id),
            None => out.push(StatusReceipt {
                participant,
                ids: vec![id],
            }),
        }
    }
    out
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

    use rosty_wa_proto::{
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
        lids: Mutex<crate::wa::lid::LidMap>,
        failures: Mutex<HashMap<&'static str, std::collections::VecDeque<DriverError>>>,
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
                sink.calls().on_incoming(
                    &offer(call_id, from, name),
                    &crate::wa::lid::LidMap::default(),
                );
            }
        }

        /// What `profiles` answers for `profile.jid`.
        pub fn set_profile(&self, profile: FetchedProfile) {
            lock(&self.profiles).insert(profile.jid.clone(), profile);
        }

        /// Makes the next call of `op` (`"lookup"`, `"profiles"` or
        /// `"picture"`) fail with `error`; several calls queue up. The call
        /// still counts in [`Self::ops`]: it reached "WhatsApp".
        pub fn fail_next(&self, op: &'static str, error: DriverError) {
            lock(&self.failures).entry(op).or_default().push_back(error);
        }

        fn scripted_failure(&self, op: &str) -> Result<(), DriverError> {
            match lock(&self.failures).get_mut(op).and_then(|q| q.pop_front()) {
                Some(error) => Err(error),
                None => Ok(()),
            }
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

        /// The LID -> phone-number mappings the fake "client" knows.
        pub fn set_lid_map(&self, map: crate::wa::lid::LidMap) {
            *lock(&self.lids) = map;
        }

        /// Runs the LID-merge sweep against the scripted mappings, as the
        /// real driver does on connect. Returns the chats merged.
        pub async fn sweep_lids(&self, instance: &InstanceId) -> usize {
            let Some(sink) = self.sink(instance) else {
                return 0;
            };
            let map = lock(&self.lids).clone();
            crate::wa::lid::sweep_with(&sink, |jid| {
                let pn = map.get(&jid.user).map(str::to_owned);
                async move { pn }
            })
            .await
        }

        /// The event sink of a running instance, to script what WhatsApp
        /// would report (incoming messages, edits, app-state changes).
        pub fn sink_of(&self, instance: &InstanceId) -> Option<EventSink> {
            self.sink(instance)
        }

        /// Delivers a status update as WhatsApp would.
        pub async fn deliver_status(&self, instance: &InstanceId, status: StoredStatus) {
            if let Some(sink) = self.sink(instance) {
                sink.emit_status(status).await;
            }
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

        /// Hands a converted live item (a vote, a keep) to the instance as the real driver would.
        pub async fn deliver_converted(
            &self,
            instance: &InstanceId,
            converted: crate::wa::convert::Converted,
        ) {
            if let Some(sink) = self.sink(instance) {
                crate::wa::app_state::apply_converted(&sink, converted).await;
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
                    own_lid: None,
                    epoch: None,
                    account_epoch: None,
                    history: None,
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
                        own_lid: None,
                        epoch: None,
                        account_epoch: None,
                        history: None,
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
                    mentions: req.mentions.clone(),
                    link_preview: req
                        .link_preview
                        .as_ref()
                        .map(|p| rosty_wa_proto::LinkPreview {
                            url: p.url.clone(),
                            title: p.title.clone(),
                            description: p.description.clone(),
                            thumbnail: p.thumbnail_b64.is_some(),
                        }),
                    expires_s: sink.chat_timer(&req.chat).await,
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
                        chat_alt: None,
                        sender_alt: None,
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
                // The real driver reads these back from the message it
                // built; the fake applies the same per-kind rules.
                let sized = matches!(
                    req.kind,
                    MediaKind::Image | MediaKind::Video | MediaKind::Sticker
                );
                let audio = req.kind == MediaKind::Audio;
                let thumbnail = req.thumbnail.clone().filter(|_| {
                    matches!(
                        req.kind,
                        MediaKind::Image | MediaKind::Video | MediaKind::Document
                    )
                });
                let media = MediaMeta {
                    file_name: req.file_name.clone(),
                    seconds: req.seconds,
                    voice_note: req.voice_note,
                    width: req.width.filter(|_| sized),
                    height: req.height.filter(|_| sized),
                    animated: req.animated
                        && matches!(req.kind, MediaKind::Video | MediaKind::Sticker),
                    view_once: req.view_once,
                    thumbnail: thumbnail.is_some(),
                    waveform_b64: req
                        .waveform
                        .as_deref()
                        .filter(|_| audio)
                        .map(rosty_wa_proto::encode_b64),
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
                        chat_alt: None,
                        sender_alt: None,
                    },
                    MessageExtras {
                        meta,
                        raw_proto: Some(req.data),
                        thumbnail,
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
                        sender_alt: None,
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
                .and_then(|()| self.scripted_failure("lookup"))
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

        fn forward_message(
            &self,
            instance: &InstanceId,
            req: ForwardMessage,
        ) -> BoxFuture<'_, Result<Sent, DriverError>> {
            let instance = instance.clone();
            Box::pin(async move {
                self.record(
                    &instance,
                    format!("forward {} {} {}", req.chat, req.message_id, req.to),
                )?;
                let sink = self.sink(&instance).ok_or(DriverError::NotConnected)?;
                let source = sink
                    .find_message(&req.chat, &req.message_id)
                    .await
                    .ok_or_else(|| DriverError::BadRequest("no such message".into()))?;
                let timestamp_ms = now_ms();
                let message_id = self.next_id();
                let expires_s = sink.chat_timer(&req.to).await;
                sink.emit_full(
                    WaMessage {
                        id: message_id.clone(),
                        chat: req.to,
                        sender: Some(own_jid(&instance)),
                        from_me: true,
                        timestamp_ms,
                        body: source.body,
                        reactions: vec![],
                        chat_alt: None,
                        sender_alt: None,
                    },
                    MessageExtras {
                        meta: MessageMeta {
                            forwarded: true,
                            status: Some(DeliveryStatus::Sent),
                            expires_s,
                            ..Default::default()
                        },
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

        /// Builds the message the real driver would send and stores it as sent, with the chat's timer.
        fn send_rich(
            &self,
            instance: &InstanceId,
            req: rosty_wa_proto::SendRich,
        ) -> BoxFuture<'_, Result<Sent, DriverError>> {
            let instance = instance.clone();
            Box::pin(async move {
                let kind = match &req.content {
                    rosty_wa_proto::RichContent::Poll { .. } => "poll",
                    rosty_wa_proto::RichContent::Location { .. } => "location",
                    rosty_wa_proto::RichContent::Contacts { .. } => "contacts",
                };
                self.record(&instance, format!("rich {} {kind}", req.chat))?;
                self.scripted_failure("rich")?;
                let sink = self.sink(&instance).ok_or(DriverError::NotConnected)?;
                let (message, secret) = crate::wa::rich::rich_message(&req.content, None)?;
                let mut extras = crate::wa::convert::extras(&message);
                extras.secret = secret.or(extras.secret);
                extras.meta.status = Some(DeliveryStatus::Sent);
                extras.meta.expires_s = sink.chat_timer(&req.chat).await;
                if let Some(quoted) = req.quoted_id.clone() {
                    extras.meta.quoted = Some(rosty_wa_proto::Quoted {
                        id: quoted,
                        sender: None,
                        preview: None,
                    });
                }
                let timestamp_ms = now_ms();
                let message_id = self.next_id();
                sink.emit_full(
                    WaMessage {
                        id: message_id.clone(),
                        chat: req.chat.clone(),
                        sender: crate::wa::convert::is_group(&req.chat).then(|| own_jid(&instance)),
                        from_me: true,
                        timestamp_ms,
                        body: rosty_wa_proto::MessageBody::Unsupported,
                        reactions: vec![],
                        chat_alt: None,
                        sender_alt: None,
                    },
                    extras,
                )
                .await;
                Ok(Sent {
                    message_id,
                    timestamp_ms,
                })
            })
        }

        /// Records the vote as sent and counts it as mine.
        fn poll_vote(
            &self,
            instance: &InstanceId,
            req: rosty_wa_proto::PollVote,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let instance = instance.clone();
            Box::pin(async move {
                self.record(
                    &instance,
                    format!(
                        "vote {} {} {}",
                        req.chat,
                        req.message_id,
                        req.options.join(",")
                    ),
                )?;
                self.scripted_failure("vote")?;
                let sink = self.sink(&instance).ok_or(DriverError::NotConnected)?;
                sink.my_poll_vote(&req.chat, &req.message_id, req.options)
                    .await;
                Ok(())
            })
        }

        fn unsubscribe_presence(
            &self,
            instance: &InstanceId,
            jid: String,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let result = self.record(instance, format!("unsubscribe {jid}"));
            Box::pin(async move { result })
        }

        fn profiles(
            &self,
            instance: &InstanceId,
            jids: Vec<String>,
        ) -> BoxFuture<'_, Result<Vec<FetchedProfile>, DriverError>> {
            let result = self
                .record(instance, format!("profiles {}", jids.len()))
                .and_then(|()| self.scripted_failure("profiles"))
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
            let result = self
                .record(instance, format!("picture {jid} {size}"))
                .and_then(|()| self.scripted_failure("picture"))
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

        fn mark_status_viewed(
            &self,
            instance: &InstanceId,
            batches: Vec<StatusReceipt>,
        ) -> BoxFuture<'_, Result<(), DriverError>> {
            let mut result = Ok(());
            for batch in batches {
                result = result.and_then(|()| {
                    self.record(
                        instance,
                        format!("status-read {} {}", batch.participant, batch.ids.join(",")),
                    )
                });
            }
            Box::pin(async move { result })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rosty_wa_proto::Quoted;

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
    fn learned_pairs_are_announced_one_by_one_up_to_a_limit() {
        use rosty_wa_proto::IdentityMapping;
        let learned = |n: usize| -> Vec<LearnedPair> {
            (0..n)
                .map(|i| LearnedPair {
                    mapping: IdentityMapping::new(
                        format!("{i}@lid"),
                        format!("9055500{i:05}@s.whatsapp.net"),
                        IdentitySource::History,
                        7,
                    ),
                    previous_pn: None,
                })
                .collect()
        };
        assert!(pair_events(&[]).is_empty());
        assert_eq!(
            pair_events(&learned(2)),
            [
                GatewayEvent::IdentityMapped {
                    lid: "0@lid".into(),
                    pn: "905550000000@s.whatsapp.net".into(),
                    source: "history".into(),
                    learned_ms: 7,
                },
                GatewayEvent::IdentityMapped {
                    lid: "1@lid".into(),
                    pn: "905550000001@s.whatsapp.net".into(),
                    source: "history".into(),
                    learned_ms: 7,
                },
            ]
        );
        assert_eq!(
            pair_events(&learned(MAX_PAIR_EVENTS)).len(),
            MAX_PAIR_EVENTS
        );
        // A whole table at once: the apps page the mappings instead.
        assert_eq!(
            pair_events(&learned(MAX_PAIR_EVENTS + 1)),
            [GatewayEvent::Resync { epoch: None }]
        );
    }

    #[test]
    fn status_receipts_batch_by_author() {
        let got = status_receipts([
            ("a@lid".to_owned(), "1".to_owned()),
            ("b@lid".to_owned(), "2".to_owned()),
            ("a@lid".to_owned(), "3".to_owned()),
        ]);
        assert_eq!(
            got,
            [
                StatusReceipt {
                    participant: "a@lid".into(),
                    ids: vec!["1".into(), "3".into()],
                },
                StatusReceipt {
                    participant: "b@lid".into(),
                    ids: vec!["2".into()],
                },
            ]
        );
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
