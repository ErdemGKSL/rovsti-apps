//! Pure in-chat find: matching messages and stepping through the hits.

use crate::search::fold;

/// Indices of `texts` whose folded text contains the folded, trimmed `query`, in order.
/// A blank query finds nothing.
pub fn find(texts: &[&str], query: &str) -> Vec<usize> {
    let q = fold(query.trim());
    if q.is_empty() {
        return Vec::new();
    }
    texts
        .iter()
        .enumerate()
        .filter(|(_, t)| fold(t).contains(&q))
        .map(|(i, _)| i)
        .collect()
}

/// Position in a list of hits (ascending indices, oldest first).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FindCursor {
    hits: Vec<usize>,
    at: Option<usize>,
}

impl FindCursor {
    /// Starts on the newest hit, since chats read bottom-up.
    pub fn new(hits: Vec<usize>) -> Self {
        let at = hits.len().checked_sub(1);
        Self { hits, at }
    }

    /// Item index of the current hit.
    pub fn current(&self) -> Option<usize> {
        self.at.map(|a| self.hits[a])
    }

    /// Steps to the previous (older) hit, wrapping to the newest.
    pub fn older(&mut self) -> Option<usize> {
        let n = self.hits.len();
        self.at = self.at.map(|a| (a + n - 1) % n);
        self.current()
    }

    /// Steps to the next (newer) hit, wrapping to the oldest.
    pub fn newer(&mut self) -> Option<usize> {
        let n = self.hits.len();
        self.at = self.at.map(|a| (a + 1) % n);
        self.current()
    }

    /// "2 of 5" (1-based, oldest first) or "No matches".
    pub fn label(&self) -> String {
        match self.at {
            Some(a) => format!("{} of {}", a + 1, self.hits.len()),
            None => "No matches".to_owned(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.hits.is_empty()
    }

    /// The current hit is the oldest one (older pages may hold more).
    pub fn is_oldest(&self) -> bool {
        self.at == Some(0)
    }

    /// A cursor over fresh `hits` after pages loaded: stays on item `keep` when it is a
    /// hit, moving one hit older when `step_older`; else on the newest.
    pub fn resume(hits: Vec<usize>, keep: Option<usize>, step_older: bool) -> Self {
        let mut cursor = Self::new(hits);
        if let Some(pos) = keep.and_then(|k| cursor.hits.iter().position(|&h| h == k)) {
            cursor.at = Some(if step_older {
                pos.saturating_sub(1)
            } else {
                pos
            });
        }
        cursor
    }

    /// Replace the hits (indices may have shifted), keeping the current item when it is
    /// still a hit, else moving to the newest.
    pub fn retarget(&mut self, hits: Vec<usize>) {
        let keep = self
            .current()
            .and_then(|c| hits.iter().position(|&h| h == c));
        self.at = keep.or(hits.len().checked_sub(1));
        self.hits = hits;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn find_folds_case_and_turkish() {
        let texts = ["Hello World", "bye", "ISTANBUL trip", "İstanbul"];
        assert_eq!(find(&texts, "hello"), vec![0]);
        assert_eq!(find(&texts, "  WORLD "), vec![0]);
        assert_eq!(find(&texts, "istanbul"), vec![2, 3]);
        assert_eq!(find(&texts, "zzz"), Vec::<usize>::new());
    }

    #[test]
    fn blank_query_finds_nothing() {
        assert!(find(&["a", "b"], "").is_empty());
        assert!(find(&["a", "b"], "   ").is_empty());
        assert!(find(&[], "a").is_empty());
    }

    #[test]
    fn cursor_starts_at_newest_and_wraps() {
        let mut c = FindCursor::new(vec![2, 5, 9]);
        assert_eq!(c.current(), Some(9));
        assert_eq!(c.older(), Some(5));
        assert_eq!(c.older(), Some(2));
        assert_eq!(c.older(), Some(9));
        assert_eq!(c.newer(), Some(2));
        let mut e = FindCursor::new(vec![]);
        assert_eq!((e.current(), e.older(), e.newer()), (None, None, None));
        assert!(e.is_empty());
    }

    #[test]
    fn label_counts_from_oldest() {
        let mut c = FindCursor::new(vec![1, 4, 6, 8, 9]);
        assert_eq!(c.label(), "5 of 5");
        c.older();
        c.older();
        c.older();
        assert_eq!(c.label(), "2 of 5");
        assert_eq!(FindCursor::default().label(), "No matches");
    }

    #[test]
    fn retarget_keeps_the_current_hit() {
        let mut c = FindCursor::new(vec![2, 5, 9]);
        c.older(); // item 5
        c.retarget(vec![2, 3, 12, 15]); // item 5 gone, falls to newest
        assert_eq!(c.current(), Some(15));
        let mut c = FindCursor::new(vec![2, 5, 9]);
        c.older();
        c.retarget(vec![0, 2, 5, 9]);
        assert_eq!(c.current(), Some(5));
        assert_eq!(c.label(), "3 of 4");
        c.retarget(vec![]);
        assert_eq!(c.label(), "No matches");
    }

    #[test]
    fn resume_steps_to_the_next_older_hit() {
        let c = FindCursor::resume(vec![1, 4, 9], Some(4), true);
        assert_eq!(c.current(), Some(1));
        let c = FindCursor::resume(vec![1, 4, 9], Some(4), false);
        assert_eq!(c.current(), Some(4));
        let c = FindCursor::resume(vec![1, 4], Some(1), true);
        assert_eq!(c.current(), Some(1));
        assert!(c.is_oldest());
        let c = FindCursor::resume(vec![1, 4], Some(7), true);
        assert_eq!(c.current(), Some(4));
        assert!(!c.is_oldest());
    }
}
