//! Which tiles to fetch next: dedupes, caps concurrency, remembers failures for
//! `RETRY_AFTER_MS`, and lets view requests jump ahead of prefetch. No I/O.

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use super::slippy::TileId;

pub const MAX_IN_FLIGHT: usize = 4;
pub const RETRY_AFTER_MS: i64 = 30_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Priority {
    /// Needed for what is on screen now.
    View,
    /// Background download for offline use.
    Prefetch,
}

#[derive(Debug, Default)]
pub struct FetchQueue {
    wanted: VecDeque<(TileId, Priority)>,
    in_flight: BTreeSet<TileId>,
    failed: BTreeMap<TileId, i64>,
}

impl FetchQueue {
    pub fn new() -> Self {
        Self::default()
    }

    /// Queues tiles not already queued or in flight. A queued prefetch is upgraded
    /// when asked for again as `View`.
    pub fn want(&mut self, ids: impl IntoIterator<Item = TileId>, p: Priority, _now_ms: i64) {
        for id in ids {
            if self.in_flight.contains(&id) {
                continue;
            }
            match self.wanted.iter_mut().find(|(q, _)| *q == id) {
                Some(entry) => {
                    if p == Priority::View {
                        entry.1 = Priority::View;
                    }
                }
                None => self.wanted.push_back((id, p)),
            }
        }
    }

    /// Drops queued `View` items not in `ids`; prefetch items stay.
    pub fn set_view(&mut self, ids: &[TileId]) {
        self.wanted
            .retain(|(id, p)| *p != Priority::View || ids.contains(id));
    }

    /// Drops every queued `Prefetch` item (an offline download was cancelled);
    /// `View` items and in-flight downloads stay. Returns how many were dropped.
    pub fn drop_prefetch(&mut self) -> usize {
        let before = self.wanted.len();
        self.wanted.retain(|(_, p)| *p != Priority::Prefetch);
        before - self.wanted.len()
    }

    /// The next tile to download, or `None` at `MAX_IN_FLIGHT` or when nothing is eligible.
    pub fn next(&mut self, now_ms: i64) -> Option<TileId> {
        if self.in_flight.len() >= MAX_IN_FLIGHT {
            return None;
        }
        let eligible = |id: &TileId| match self.failed.get(id) {
            Some(&t) => now_ms.saturating_sub(t) >= RETRY_AFTER_MS,
            None => true,
        };
        let pos = [Priority::View, Priority::Prefetch]
            .into_iter()
            .find_map(|want| {
                self.wanted
                    .iter()
                    .position(|(id, p)| *p == want && eligible(id) && !self.in_flight.contains(id))
            })?;
        let (id, _) = self.wanted.remove(pos)?;
        self.in_flight.insert(id);
        Some(id)
    }

    /// Reports a finished download; a failure blocks the tile for `RETRY_AFTER_MS`.
    pub fn done(&mut self, id: TileId, ok: bool, now_ms: i64) {
        self.in_flight.remove(&id);
        if ok {
            self.failed.remove(&id);
        } else {
            self.failed.insert(id, now_ms);
        }
    }

    /// Forgets everything (the tile server changed).
    pub fn clear(&mut self) {
        self.wanted.clear();
        self.in_flight.clear();
        self.failed.clear();
    }

    pub fn pending(&self) -> usize {
        self.wanted.len()
    }

    pub fn in_flight(&self) -> usize {
        self.in_flight.len()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn t(x: u32) -> TileId {
        TileId::new(10, x, 0).unwrap()
    }

    #[test]
    fn caps_concurrency() {
        let mut q = FetchQueue::new();
        q.want((0..10).map(t), Priority::View, 0);
        for _ in 0..MAX_IN_FLIGHT {
            assert!(q.next(0).is_some());
        }
        assert_eq!(q.next(0), None);
        assert_eq!((q.in_flight(), q.pending()), (4, 6));
        q.done(t(0), true, 0);
        assert_eq!(q.next(0), Some(t(4)));
    }

    #[test]
    fn dedupes_queued_and_in_flight() {
        let mut q = FetchQueue::new();
        q.want([t(1), t(1), t(2)], Priority::View, 0);
        q.want([t(1)], Priority::Prefetch, 0);
        assert_eq!(q.pending(), 2);
        let a = q.next(0).unwrap();
        q.want([a], Priority::View, 0);
        assert_eq!(q.pending(), 1);
        assert_eq!(q.in_flight(), 1);
    }

    #[test]
    fn view_beats_prefetch() {
        let mut q = FetchQueue::new();
        q.want([t(1), t(2)], Priority::Prefetch, 0);
        q.want([t(3)], Priority::View, 0);
        assert_eq!(q.next(0), Some(t(3)));
        assert_eq!(q.next(0), Some(t(1)));
    }

    #[test]
    fn prefetch_upgraded_by_view_want() {
        let mut q = FetchQueue::new();
        q.want([t(1), t(2)], Priority::Prefetch, 0);
        q.want([t(2)], Priority::View, 0);
        assert_eq!(q.next(0), Some(t(2)));
    }

    #[test]
    fn set_view_drops_stale_view_keeps_prefetch() {
        let mut q = FetchQueue::new();
        q.want([t(1), t(2)], Priority::View, 0);
        q.want([t(3)], Priority::Prefetch, 0);
        q.set_view(&[t(2)]);
        assert_eq!(q.pending(), 2);
        assert_eq!(q.next(0), Some(t(2)));
        assert_eq!(q.next(0), Some(t(3)));
        assert_eq!(q.next(0), None);
    }

    #[test]
    fn failed_tile_waits_for_retry() {
        let mut q = FetchQueue::new();
        q.want([t(1)], Priority::View, 0);
        let id = q.next(0).unwrap();
        q.done(id, false, 1_000);
        q.want([id], Priority::View, 1_000);
        assert_eq!(q.next(1_000 + RETRY_AFTER_MS - 1), None);
        assert_eq!(q.pending(), 1);
        assert_eq!(q.next(1_000 + RETRY_AFTER_MS), Some(id));
        q.done(id, true, 40_000);
        q.want([id], Priority::View, 40_000);
        assert_eq!(q.next(40_000), Some(id));
    }

    #[test]
    fn failed_tile_does_not_block_others() {
        let mut q = FetchQueue::new();
        q.want([t(1), t(2)], Priority::View, 0);
        let a = q.next(0).unwrap();
        q.done(a, false, 0);
        q.want([a], Priority::View, 0);
        assert_eq!(q.next(1), Some(t(2)));
        assert_eq!(q.next(1), None);
    }

    #[test]
    fn clear_resets() {
        let mut q = FetchQueue::new();
        q.want([t(1), t(2)], Priority::View, 0);
        let a = q.next(0).unwrap();
        q.done(a, false, 0);
        q.want([t(3)], Priority::Prefetch, 0);
        q.next(0);
        q.clear();
        assert_eq!((q.pending(), q.in_flight()), (0, 0));
        q.want([a], Priority::View, 1);
        assert_eq!(q.next(1), Some(a));
    }

    #[test]
    fn drop_prefetch_keeps_view() {
        let mut q = FetchQueue::new();
        q.want([t(1), t(2)], Priority::View, 0);
        q.want([t(3), t(4), t(5)], Priority::Prefetch, 0);
        assert_eq!(q.drop_prefetch(), 3);
        assert_eq!(q.pending(), 2);
        assert_eq!(q.next(0), Some(t(1)));
        assert_eq!(q.next(0), Some(t(2)));
        assert_eq!(q.next(0), None);
        assert_eq!(q.drop_prefetch(), 0);
    }
}
