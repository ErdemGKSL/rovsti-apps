//! Deletes that wait out a short Undo window before the real delete runs.

/// How long a delete can be undone, in milliseconds.
pub const UNDO_MS: i64 = 5_000;

/// Keys scheduled for deletion, each with the time it was queued.
#[derive(Clone, Debug)]
pub struct PendingDeletes<K> {
    items: Vec<(K, i64)>,
}

impl<K> Default for PendingDeletes<K> {
    fn default() -> Self {
        Self { items: Vec::new() }
    }
}

impl<K: PartialEq + Clone> PendingDeletes<K> {
    /// Queues `key`; queueing it again restarts its window.
    pub fn add(&mut self, key: K, now_ms: i64) {
        self.items.retain(|(k, _)| *k != key);
        self.items.push((key, now_ms));
    }

    /// Cancels a pending delete. False when `key` was not pending.
    pub fn undo(&mut self, key: &K) -> bool {
        let before = self.items.len();
        self.items.retain(|(k, _)| k != key);
        self.items.len() != before
    }

    /// Whether any delete is waiting.
    pub fn has_pending(&self) -> bool {
        !self.items.is_empty()
    }

    pub fn contains(&self, key: &K) -> bool {
        self.items.iter().any(|(k, _)| k == key)
    }

    /// Removes and returns the keys whose window has passed.
    pub fn due(&mut self, now_ms: i64) -> Vec<K> {
        let mut out = Vec::new();
        self.items.retain(|(k, at)| {
            if now_ms.saturating_sub(*at) >= UNDO_MS {
                out.push(k.clone());
                false
            } else {
                true
            }
        });
        out
    }

    /// Removes and returns everything (leaving the page commits all).
    pub fn drain(&mut self) -> Vec<K> {
        self.items.drain(..).map(|(k, _)| k).collect()
    }
}

/// The pill text after a delete.
pub fn notice(count: usize) -> String {
    if count == 1 {
        "Message deleted".to_owned()
    } else {
        format!("{count} messages deleted")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn due_after_window() {
        let mut p = PendingDeletes::default();
        p.add(1_u32, 1_000);
        assert!(p.due(1_000 + UNDO_MS - 1).is_empty());
        assert!(p.contains(&1));
        assert_eq!(p.due(1_000 + UNDO_MS), vec![1]);
        assert!(!p.contains(&1));
    }

    #[test]
    fn has_pending_follows_the_queue() {
        let mut p = PendingDeletes::default();
        assert!(!p.has_pending());
        p.add(1_u32, 0);
        assert!(p.has_pending());
        p.undo(&1);
        assert!(!p.has_pending());
    }

    #[test]
    fn undo_removes() {
        let mut p = PendingDeletes::default();
        p.add("a".to_owned(), 0);
        assert!(p.undo(&"a".to_owned()));
        assert!(!p.undo(&"a".to_owned()));
        assert!(p.due(100_000).is_empty());
    }

    #[test]
    fn re_add_refreshes() {
        let mut p = PendingDeletes::default();
        p.add(1_u32, 0);
        p.add(1, 4_000);
        assert!(p.due(5_000).is_empty());
        assert_eq!(p.due(9_000), vec![1]);
    }

    #[test]
    fn drain_returns_all() {
        let mut p = PendingDeletes::default();
        p.add(1_u32, 0);
        p.add(2, 10);
        assert_eq!(p.drain(), vec![1, 2]);
        assert!(p.drain().is_empty());
        assert_eq!(notice(1), "Message deleted");
        assert_eq!(notice(3), "3 messages deleted");
    }
}
