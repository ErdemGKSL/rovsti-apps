//! Size-capped LRU bookkeeping. No I/O: the caller deletes whatever `evict_to` returns.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// Tracks entry sizes and recency; the disk cache and the in-memory tile cache use it.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LruIndex<K: Ord> {
    entries: BTreeMap<K, (u64, u64)>,
    total: u64,
    clock: u64,
}

impl<K: Ord> Default for LruIndex<K> {
    fn default() -> Self {
        Self {
            entries: BTreeMap::new(),
            total: 0,
            clock: 0,
        }
    }
}

impl<K: Ord + Clone> LruIndex<K> {
    pub fn new() -> Self {
        Self::default()
    }

    fn tick(&mut self) -> u64 {
        self.clock += 1;
        self.clock
    }

    /// Marks `k` as most recently used. `false` when it is not tracked.
    pub fn touch(&mut self, k: &K) -> bool {
        let now = self.tick();
        match self.entries.get_mut(k) {
            Some(e) => {
                e.1 = now;
                true
            }
            None => false,
        }
    }

    /// Adds or replaces `k` (size and recency), keeping `total` consistent.
    pub fn insert(&mut self, k: K, size: u64) {
        let now = self.tick();
        if let Some((old, _)) = self.entries.insert(k, (size, now)) {
            self.total = self.total.saturating_sub(old);
        }
        self.total = self.total.saturating_add(size);
    }

    pub fn remove(&mut self, k: &K) -> Option<u64> {
        let (size, _) = self.entries.remove(k)?;
        self.total = self.total.saturating_sub(size);
        Some(size)
    }

    pub fn contains(&self, k: &K) -> bool {
        self.entries.contains_key(k)
    }

    pub fn total(&self) -> u64 {
        self.total
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Keys to delete (least recently used first) so `total <= cap`; removes them.
    pub fn evict_to(&mut self, cap: u64) -> Vec<K> {
        if self.total <= cap {
            return Vec::new();
        }
        let mut order: Vec<(u64, &K)> = self.entries.iter().map(|(k, v)| (v.1, k)).collect();
        order.sort_by_key(|(used, _)| *used);
        let mut victims = Vec::new();
        let mut total = self.total;
        for (_, k) in order {
            if total <= cap {
                break;
            }
            total = total.saturating_sub(self.entries[k].0);
            victims.push(k.clone());
        }
        for k in &victims {
            self.remove(k);
        }
        victims
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn filled() -> LruIndex<u32> {
        let mut l = LruIndex::new();
        for k in 1..=4 {
            l.insert(k, 10);
        }
        l
    }

    #[test]
    fn evicts_oldest_first() {
        let mut l = filled();
        assert_eq!(l.total(), 40);
        assert_eq!(l.evict_to(25), vec![1, 2]);
        assert_eq!((l.total(), l.len()), (20, 2));
    }

    #[test]
    fn touch_protects_entry() {
        let mut l = filled();
        assert!(l.touch(&1));
        assert!(!l.touch(&99));
        assert_eq!(l.evict_to(20), vec![2, 3]);
        assert!(l.contains(&1) && l.contains(&4));
    }

    #[test]
    fn under_cap_evicts_nothing() {
        let mut l = filled();
        assert!(l.evict_to(40).is_empty());
        assert_eq!(l.len(), 4);
    }

    #[test]
    fn evict_to_zero_empties() {
        let mut l = filled();
        assert_eq!(l.evict_to(0).len(), 4);
        assert!(l.is_empty());
        assert_eq!(l.total(), 0);
    }

    #[test]
    fn replace_updates_total_and_recency() {
        let mut l = filled();
        l.insert(1, 100);
        assert_eq!(l.total(), 130);
        assert_eq!(l.len(), 4);
        assert_eq!(l.evict_to(30), vec![2, 3, 4, 1]);
    }

    #[test]
    fn remove_returns_size() {
        let mut l = filled();
        assert_eq!(l.remove(&2), Some(10));
        assert_eq!(l.remove(&2), None);
        assert_eq!(l.total(), 30);
    }

    #[test]
    fn serde_round_trip_keeps_order() {
        let mut l = filled();
        l.touch(&1);
        let json = serde_json::to_string(&l).unwrap();
        let mut back: LruIndex<u32> = serde_json::from_str(&json).unwrap();
        assert_eq!(back.total(), 40);
        assert_eq!(back.evict_to(20), vec![2, 3]);
    }
}
