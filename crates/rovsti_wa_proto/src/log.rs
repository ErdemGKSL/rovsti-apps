//! A bounded, sequenced event ring so a reconnecting client can resume.

use std::collections::VecDeque;

use crate::event::{EventEnvelope, GatewayEvent};

#[derive(Clone, Debug, PartialEq)]
pub enum Since {
    /// Events newer than the requested sequence, oldest first.
    Events(Vec<EventEnvelope>),
    /// The requested point is no longer (or never was) in the ring. The
    /// client must refetch its state; the gateway sends `Resync`.
    Gap,
}

#[derive(Clone, Debug)]
pub struct EventLog {
    cap: usize,
    next_seq: u64,
    ring: VecDeque<EventEnvelope>,
}

impl EventLog {
    pub fn new(cap: usize) -> Self {
        let cap = cap.max(1);
        Self {
            cap,
            next_seq: 1,
            ring: VecDeque::with_capacity(cap),
        }
    }

    /// Sequence number of the newest event, 0 when none was pushed yet.
    pub fn last_seq(&self) -> u64 {
        self.next_seq - 1
    }

    pub fn len(&self) -> usize {
        self.ring.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ring.is_empty()
    }

    pub fn push(&mut self, at_ms: i64, event: GatewayEvent) -> EventEnvelope {
        let envelope = EventEnvelope {
            seq: self.next_seq,
            at_ms,
            event,
        };
        self.next_seq += 1;
        if self.ring.len() == self.cap {
            self.ring.pop_front();
        }
        self.ring.push_back(envelope.clone());
        envelope
    }

    /// `after = None` is a fresh subscriber that only wants live events.
    /// `after = Some(n)` resumes with everything newer than `n`.
    pub fn since(&self, after: Option<u64>) -> Since {
        let Some(after) = after else {
            return Since::Events(Vec::new());
        };
        let last = self.last_seq();
        if after == last {
            return Since::Events(Vec::new());
        }
        // After a gateway restart sequences start over, so a client may be
        // ahead of us; that is a gap too.
        if after > last {
            return Since::Gap;
        }
        match self.ring.front() {
            Some(first) if first.seq <= after + 1 => Since::Events(
                self.ring
                    .iter()
                    .filter(|e| e.seq > after)
                    .cloned()
                    .collect(),
            ),
            _ => Since::Gap,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(log: &mut EventLog, n: i64) -> EventEnvelope {
        log.push(
            n,
            GatewayEvent::GroupChanged {
                jid: format!("g{n}"),
            },
        )
    }

    fn seqs(since: Since) -> Vec<u64> {
        match since {
            Since::Events(v) => v.into_iter().map(|e| e.seq).collect(),
            Since::Gap => panic!("unexpected gap"),
        }
    }

    #[test]
    fn seq_is_monotonic() {
        let mut log = EventLog::new(4);
        assert_eq!(log.last_seq(), 0);
        assert!(log.is_empty());
        let a = ev(&mut log, 10);
        let b = ev(&mut log, 20);
        assert_eq!((a.seq, b.seq, b.at_ms), (1, 2, 20));
        assert_eq!(log.last_seq(), 2);
    }

    #[test]
    fn resume_returns_newer_events() {
        let mut log = EventLog::new(4);
        for n in 0..3 {
            ev(&mut log, n);
        }
        assert_eq!(seqs(log.since(Some(0))), [1, 2, 3]);
        assert_eq!(seqs(log.since(Some(1))), [2, 3]);
        assert_eq!(seqs(log.since(Some(3))), Vec::<u64>::new());
        assert_eq!(seqs(log.since(None)), Vec::<u64>::new());
    }

    #[test]
    fn gap_when_the_ring_has_moved_on() {
        let mut log = EventLog::new(3);
        for n in 0..5 {
            ev(&mut log, n);
        }
        assert_eq!(log.len(), 3); // holds 3, 4, 5
        assert_eq!(seqs(log.since(Some(2))), [3, 4, 5]);
        assert_eq!(log.since(Some(1)), Since::Gap);
        assert_eq!(log.since(Some(0)), Since::Gap);
        // A client ahead of the gateway (it restarted) is a gap too.
        assert_eq!(log.since(Some(99)), Since::Gap);
    }

    #[test]
    fn empty_log() {
        let log = EventLog::new(3);
        assert_eq!(seqs(log.since(Some(0))), Vec::<u64>::new());
        assert_eq!(log.since(Some(4)), Since::Gap);
    }
}
