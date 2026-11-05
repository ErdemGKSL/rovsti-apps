//! A small least-recently-used cache (story 10.5: the photo cache holds 64
//! decoded thumbnails). Lookups are O(1); only an insert into a full cache
//! scans for the oldest entry, which at 64 entries is a handful of
//! comparisons and happens once per decoded photo, never per frame.

use std::{collections::HashMap, hash::Hash};

#[derive(Debug)]
pub struct Lru<K, V> {
    capacity: usize,
    clock: u64,
    entries: HashMap<K, (V, u64)>,
}

impl<K: Hash + Eq + Clone, V> Lru<K, V> {
    /// A cache that keeps at most `capacity` entries (at least one).
    pub fn new(capacity: usize) -> Self {
        let capacity = capacity.max(1);
        Self {
            capacity,
            clock: 0,
            entries: HashMap::with_capacity(capacity + 1),
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn contains(&self, key: &K) -> bool {
        self.entries.contains_key(key)
    }

    /// The value for `key`, marked as just used.
    pub fn get(&mut self, key: &K) -> Option<&V> {
        self.clock += 1;
        let now = self.clock;
        self.entries.get_mut(key).map(|(value, used)| {
            *used = now;
            &*value
        })
    }

    /// The value for `key` without touching its age.
    pub fn peek(&self, key: &K) -> Option<&V> {
        self.entries.get(key).map(|(value, _)| value)
    }

    /// Stores `value`; returns the key evicted to make room, if any.
    pub fn insert(&mut self, key: K, value: V) -> Option<K> {
        self.clock += 1;
        let mut evicted = None;
        if !self.entries.contains_key(&key) && self.entries.len() >= self.capacity {
            let oldest = self
                .entries
                .iter()
                .min_by_key(|(_, (_, used))| *used)
                .map(|(k, _)| k.clone());
            if let Some(oldest) = oldest {
                self.entries.remove(&oldest);
                evicted = Some(oldest);
            }
        }
        self.entries.insert(key, (value, self.clock));
        evicted
    }

    /// Drops `key`; its value, if it was stored.
    pub fn remove(&mut self, key: &K) -> Option<V> {
        self.entries.remove(key).map(|(value, _)| value)
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evicts_the_least_recently_used() {
        let mut lru = Lru::new(2);
        assert_eq!(lru.insert(1, "a"), None);
        assert_eq!(lru.insert(2, "b"), None);
        assert_eq!(lru.get(&1), Some(&"a"));
        assert_eq!(
            lru.insert(3, "c"),
            Some(2),
            "2 is the oldest after 1 was read"
        );
        assert!(lru.contains(&1) && lru.contains(&3) && !lru.contains(&2));
        assert_eq!(lru.len(), 2);
    }

    #[test]
    fn remove_drops_one_key() {
        let mut lru = Lru::new(2);
        lru.insert(1, "a");
        lru.insert(2, "b");
        assert_eq!(lru.remove(&1), Some("a"));
        assert_eq!(lru.remove(&1), None);
        assert!(lru.contains(&2));
    }

    #[test]
    fn peek_does_not_refresh() {
        let mut lru = Lru::new(2);
        lru.insert(1, ());
        lru.insert(2, ());
        assert!(lru.peek(&1).is_some());
        assert_eq!(lru.insert(3, ()), Some(1));
    }

    #[test]
    fn replacing_a_key_never_evicts() {
        let mut lru = Lru::new(2);
        lru.insert(1, 10);
        lru.insert(2, 20);
        assert_eq!(lru.insert(1, 11), None);
        assert_eq!(lru.peek(&1), Some(&11));
        assert_eq!(lru.len(), 2);
    }

    #[test]
    fn sixty_four_entries_hold_and_the_65th_evicts_the_oldest() {
        let mut lru = Lru::new(64);
        for key in 0..64 {
            assert_eq!(lru.insert(key, key), None);
        }
        assert_eq!(lru.insert(64, 64), Some(0));
        assert_eq!(lru.len(), 64);
    }

    #[test]
    fn capacity_is_at_least_one() {
        let mut lru = Lru::new(0);
        lru.insert(1, ());
        assert_eq!(lru.insert(2, ()), Some(1));
        assert!(!lru.is_empty());
        lru.clear();
        assert!(lru.is_empty());
    }
}
