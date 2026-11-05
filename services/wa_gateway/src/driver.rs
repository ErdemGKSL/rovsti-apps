//! The seam between the gateway and WhatsApp itself. The real driver
//! ([`crate::wa::WaRuntime`]) wraps whatsapp-rust; [`FakeDriver`] scripts the same events for tests.

use std::sync::Arc;

use futures::future::BoxFuture;
use rovsti_wa_proto::{
    ChatState, GatewayEvent, LinkRequest, MarkRead, Reaction, SendText, Sent, WaMessage,
};

use crate::hub::Hub;
use crate::now_ms;
use crate::store::{InstanceId, Store};

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
}

impl std::fmt::Debug for EventSink {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "EventSink({})", self.instance)
    }
}

impl EventSink {
    pub fn new(instance: InstanceId, hub: Arc<Hub>, store: Store) -> Self {
        Self {
            instance,
            hub,
            store,
        }
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
                match self
                    .store
                    .insert_message(self.instance.clone(), message.clone(), raw_proto)
                    .await
                {
                    Ok(true) => {}
                    Ok(false) => return,
                    Err(e) => log::warn!("{}: saving message failed: {e}", self.instance),
                }
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
}

#[cfg(any(test, feature = "fake"))]
pub use fake::FakeDriver;

#[cfg(any(test, feature = "fake"))]
mod fake {
    use std::collections::HashMap;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicU64, Ordering};

    use rovsti_wa_proto::{InstanceState, InstanceStatus, MessageBody};

    use super::*;

    /// Scripted in-memory driver: linking emits a `Qr` (or `PairCode`) and
    /// then `State(Connected)`; sent texts come back as `Message` events.
    #[derive(Default)]
    pub struct FakeDriver {
        running: Mutex<HashMap<InstanceId, EventSink>>,
        started: Mutex<Vec<(InstanceId, Option<LinkRequest>)>>,
        ops: Mutex<Vec<String>>,
        counter: AtomicU64,
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
                let sink = self.sink(&instance).ok_or(DriverError::NotConnected)?;
                let n = self.counter.fetch_add(1, Ordering::Relaxed) + 1;
                let timestamp_ms = now_ms();
                let message_id = format!("FAKE{n:06}");
                sink.emit_message(
                    WaMessage {
                        id: message_id.clone(),
                        chat: req.chat,
                        sender: Some(own_jid(&instance)),
                        from_me: true,
                        timestamp_ms,
                        body: MessageBody::Text(req.text),
                        reactions: vec![],
                    },
                    None,
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
    }
}
