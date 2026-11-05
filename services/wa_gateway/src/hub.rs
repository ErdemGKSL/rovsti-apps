//! Per-instance event fan-out: a resumable ring (`EventLog`) plus a Tokio
//! broadcast channel for live subscribers.

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard};

use rovsti_wa_proto::{EventEnvelope, EventLog, GatewayEvent, KeyId, Since};
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
    /// Live events. Everything in here is newer than everything in `replay`.
    pub events: broadcast::Receiver<EventEnvelope>,
    /// Ids of revoked keys.
    pub revoked: broadcast::Receiver<KeyId>,
}

#[derive(Default)]
pub struct Hub {
    inner: Mutex<HashMap<InstanceId, Instance>>,
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

    /// Appends `event` to the instance's log and sends it to subscribers.
    pub fn publish(&self, instance: &InstanceId, at_ms: i64, event: GatewayEvent) -> EventEnvelope {
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
        let replay = match slot.log.since(after) {
            Since::Events(events) => Replay::Events(events),
            Since::Gap => Replay::Gap {
                head_seq: slot.log.last_seq(),
            },
        };
        Subscription {
            replay,
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
        hub.publish(&id(), 1, GatewayEvent::Resync);
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
            hub.publish(&id(), n, GatewayEvent::Resync);
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
    fn old_events_fall_out_of_the_ring() {
        let hub = Hub::new();
        for n in 0..(LOG_CAPACITY as i64 + 10) {
            hub.publish(&id(), n, GatewayEvent::Resync);
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
        hub.publish(&other, 1, GatewayEvent::Resync);
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
