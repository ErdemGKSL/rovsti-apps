//! Per-instance event fan-out: a resumable ring (`EventLog`) plus a Tokio
//! broadcast channel for live subscribers.

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard};

use rosty_wa_proto::{EventEnvelope, EventLog, GatewayEvent, KeyId, Since};
use tokio::sync::broadcast;

use crate::store::InstanceId;

/// Events kept per instance for resuming clients (AD-WA4).
pub const LOG_CAPACITY: usize = 1024;
/// Live events a slow subscriber may fall behind by before it is resynced.
const CHANNEL_CAPACITY: usize = 256;

struct Instance {
    log: EventLog,
    tx: broadcast::Sender<EventEnvelope>,
    revoked: broadcast::Sender<KeyId>,
}

impl Instance {
    fn new() -> Self {
        Self {
            log: EventLog::new(LOG_CAPACITY),
            tx: broadcast::channel(CHANNEL_CAPACITY).0,
            revoked: broadcast::channel(16).0,
        }
    }
}

/// A fresh id for this gateway process: 16 hex digits from the system's
/// random source. Should that fail, the start time stands in; it differs
/// between two runs as well.
fn new_epoch() -> String {
    let mut bytes = [0u8; 8];
    if getrandom::fill(&mut bytes).is_err() {
        bytes = crate::now_ms().to_be_bytes();
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// What a new subscriber starts with.
#[derive(Debug)]
pub enum Replay {
    /// Events newer than `after`, oldest first (empty for a live-only start).
    Events(Vec<EventEnvelope>),
    /// `after` is unknown here; send `Resync`. Carries the current head.
    Gap { head_seq: u64 },
}

#[derive(Debug)]
pub struct Subscription {
    pub replay: Replay,
    /// The `seq` this socket starts from: `after` when it resumes there, the
    /// newest event otherwise. The `hello` frame carries it.
    pub start_seq: u64,
    /// Live events. Everything in here is newer than everything in `replay`.
    pub events: broadcast::Receiver<EventEnvelope>,
    /// Ids of revoked keys.
    pub revoked: broadcast::Receiver<KeyId>,
}

pub struct Hub {
    /// The id of this gateway process. Sequences start at 1 in every run, so
    /// a device tells two runs apart by this, not by `seq`.
    epoch: String,
    inner: Mutex<HashMap<InstanceId, Instance>>,
}

impl Default for Hub {
    fn default() -> Self {
        Self {
            epoch: new_epoch(),
            inner: Mutex::default(),
        }
    }
}

impl std::fmt::Debug for Hub {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Hub")
    }
}

impl Hub {
    pub fn new() -> Self {
        Self::default()
    }

    fn lock(&self) -> MutexGuard<'_, HashMap<InstanceId, Instance>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The id of this gateway process (`InstanceStatus.epoch`).
    pub fn epoch(&self) -> &str {
        &self.epoch
    }

    /// A `resync` that names this process.
    pub fn resync(&self) -> GatewayEvent {
        GatewayEvent::Resync {
            epoch: Some(self.epoch.clone()),
        }
    }

    /// Appends `event` to the instance's log and sends it to subscribers. A
    /// `resync` gets this process's epoch.
    pub fn publish(
        &self,
        instance: &InstanceId,
        at_ms: i64,
        mut event: GatewayEvent,
    ) -> EventEnvelope {
        if let GatewayEvent::Resync { epoch } = &mut event {
            epoch.get_or_insert_with(|| self.epoch.clone());
        }
        let mut map = self.lock();
        let slot = map.entry(instance.clone()).or_insert_with(Instance::new);
        let envelope = slot.log.push(at_ms, event);
        // No receivers is normal: nobody is connected right now.
        let _ = slot.tx.send(envelope.clone());
        envelope
    }

    pub fn subscribe(&self, instance: &InstanceId, after: Option<u64>) -> Subscription {
        let mut map = self.lock();
        let slot = map.entry(instance.clone()).or_insert_with(Instance::new);
        let head_seq = slot.log.last_seq();
        let (replay, start_seq) = match slot.log.since(after) {
            Since::Events(events) => (Replay::Events(events), after.unwrap_or(head_seq)),
            Since::Gap => (Replay::Gap { head_seq }, head_seq),
        };
        Subscription {
            replay,
            start_seq,
            events: slot.tx.subscribe(),
            revoked: slot.revoked.subscribe(),
        }
    }

    /// Sequence number of the newest event, 0 for none.
    pub fn head_seq(&self, instance: &InstanceId) -> u64 {
        self.lock().get(instance).map_or(0, |s| s.log.last_seq())
    }

    /// Tells the sockets that authenticated with `key_id` to close.
    pub fn revoke(&self, instance: &InstanceId, key_id: &KeyId) {
        if let Some(slot) = self.lock().get(instance) {
            let _ = slot.revoked.send(key_id.clone());
        }
    }

    /// Forgets an instance. Its sockets see their channels close.
    pub fn remove(&self, instance: &InstanceId) {
        self.lock().remove(instance);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id() -> InstanceId {
        InstanceId::new("i")
    }

    fn seqs(replay: Replay) -> Vec<u64> {
        match replay {
            Replay::Events(v) => v.into_iter().map(|e| e.seq).collect(),
            Replay::Gap { .. } => panic!("unexpected gap"),
        }
    }

    #[tokio::test]
    async fn live_subscribers_see_new_events_only() {
        let hub = Hub::new();
        hub.publish(&id(), 1, GatewayEvent::Resync { epoch: None });
        let mut sub = hub.subscribe(&id(), None);
        assert!(seqs(sub.replay).is_empty());
        hub.publish(&id(), 2, GatewayEvent::KeyRevoked);
        let got = sub.events.recv().await.unwrap();
        assert_eq!((got.seq, got.at_ms), (2, 2));
    }

    #[test]
    fn resume_replays_newer_events() {
        let hub = Hub::new();
        for n in 0..4 {
            hub.publish(&id(), n, GatewayEvent::Resync { epoch: None });
        }
        assert_eq!(seqs(hub.subscribe(&id(), Some(0)).replay), [1, 2, 3, 4]);
        assert_eq!(seqs(hub.subscribe(&id(), Some(2)).replay), [3, 4]);
        assert!(seqs(hub.subscribe(&id(), Some(4)).replay).is_empty());
        assert!(matches!(
            hub.subscribe(&id(), Some(99)).replay,
            Replay::Gap { head_seq: 4 }
        ));
        assert_eq!(hub.head_seq(&id()), 4);
        assert_eq!(hub.head_seq(&InstanceId::new("other")), 0);
    }

    #[test]
    fn every_process_has_its_own_epoch_and_stamps_resyncs() {
        let hub = Hub::new();
        assert_eq!(hub.epoch().len(), 16);
        assert!(hub.epoch().bytes().all(|b| b.is_ascii_hexdigit()));
        assert_ne!(hub.epoch(), Hub::new().epoch());

        let sent = hub.publish(&id(), 1, GatewayEvent::Resync { epoch: None });
        assert_eq!(sent.event, hub.resync());
        assert_eq!(
            sent.event,
            GatewayEvent::Resync {
                epoch: Some(hub.epoch().to_owned())
            }
        );
        // The replayed copy carries it too.
        match hub.subscribe(&id(), Some(0)).replay {
            Replay::Events(events) => assert_eq!(events[0].event, hub.resync()),
            Replay::Gap { .. } => panic!("unexpected gap"),
        }
    }

    #[test]
    fn a_socket_starts_where_it_resumes_or_at_the_head() {
        let hub = Hub::new();
        assert_eq!(hub.subscribe(&id(), None).start_seq, 0);
        for n in 0..4 {
            hub.publish(&id(), n, GatewayEvent::KeyRevoked);
        }
        // Live only: from the newest event.
        assert_eq!(hub.subscribe(&id(), None).start_seq, 4);
        // Resuming: from where the client was, so `hello` moves nothing.
        assert_eq!(hub.subscribe(&id(), Some(2)).start_seq, 2);
        assert_eq!(hub.subscribe(&id(), Some(4)).start_seq, 4);
        // A gap: the `resync` that follows is stamped with the head as well.
        assert_eq!(hub.subscribe(&id(), Some(99)).start_seq, 4);
    }

    #[test]
    fn old_events_fall_out_of_the_ring() {
        let hub = Hub::new();
        for n in 0..(LOG_CAPACITY as i64 + 10) {
            hub.publish(&id(), n, GatewayEvent::Resync { epoch: None });
        }
        assert!(matches!(
            hub.subscribe(&id(), Some(1)).replay,
            Replay::Gap { .. }
        ));
    }

    #[tokio::test]
    async fn instances_are_isolated_and_remove_closes_channels() {
        let hub = Hub::new();
        let other = InstanceId::new("o");
        let mut sub = hub.subscribe(&id(), None);
        hub.publish(&other, 1, GatewayEvent::Resync { epoch: None });
        assert!(sub.events.try_recv().is_err());
        hub.remove(&id());
        assert!(matches!(
            sub.events.recv().await,
            Err(broadcast::error::RecvError::Closed)
        ));
    }

    #[tokio::test]
    async fn revocation_reaches_subscribers() {
        let hub = Hub::new();
        let mut sub = hub.subscribe(&id(), None);
        let key = KeyId::from_bytes(&[1; 8]);
        hub.revoke(&id(), &key);
        assert_eq!(sub.revoked.recv().await.unwrap(), key);
    }
}
