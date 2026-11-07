//! Multi-select of messages: the selection set, the block copied to the clipboard
//! and the pill texts.

use std::collections::BTreeSet;

use crate::chat_export::{ExportLine, block_text};

/// Most messages selectable at once.
pub const MAX_SELECTED: usize = 200;

/// The selected keys, at most [`MAX_SELECTED`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Selection<K: Ord + Clone> {
    keys: BTreeSet<K>,
}

impl<K: Ord + Clone> Default for Selection<K> {
    fn default() -> Self {
        Self::new()
    }
}

impl<K: Ord + Clone> Selection<K> {
    pub fn new() -> Self {
        Self {
            keys: BTreeSet::new(),
        }
    }

    /// Flips a key. Returns false (and changes nothing) when adding would pass the cap.
    pub fn toggle(&mut self, key: K) -> bool {
        if self.keys.remove(&key) {
            return true;
        }
        if self.keys.len() >= MAX_SELECTED {
            return false;
        }
        self.keys.insert(key);
        true
    }

    pub fn contains(&self, key: &K) -> bool {
        self.keys.contains(key)
    }

    pub fn len(&self) -> usize {
        self.keys.len()
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    pub fn clear(&mut self) {
        self.keys.clear();
    }

    /// Adds keys until the cap; returns how many were newly added.
    pub fn select_all(&mut self, keys: impl IntoIterator<Item = K>) -> usize {
        let mut added = 0;
        for key in keys {
            if self.keys.len() >= MAX_SELECTED {
                break;
            }
            if self.keys.insert(key) {
                added += 1;
            }
        }
        added
    }

    /// Keeps only keys the predicate accepts (for messages that disappeared).
    pub fn retain(&mut self, keep: impl FnMut(&K) -> bool) {
        self.keys.retain(keep);
    }

    pub fn keys(&self) -> impl Iterator<Item = &K> {
        self.keys.iter()
    }
}

/// The action bar title.
pub fn title(n: usize) -> String {
    if n == 0 {
        "Select messages".to_string()
    } else {
        format!("{n} selected")
    }
}

/// Clipboard text for the selected messages, oldest first: a lone message is its
/// trimmed text, several are a chat block with day and time lines.
pub fn copy_block(lines: &[ExportLine<'_>], tz_offset_s: i32) -> String {
    match lines {
        [one] => one.text.trim().to_string(),
        _ => block_text(lines, tz_offset_s),
    }
}

/// Whether the star button stars (true) or unstars (false): unstar only when
/// every selected message is already starred.
pub fn star_all(starred: &[bool]) -> bool {
    starred.is_empty() || !starred.iter().all(|s| *s)
}

fn messages(n: usize) -> String {
    if n == 1 {
        "1 message".to_string()
    } else {
        format!("{n} messages")
    }
}

pub fn copied_notice(n: usize) -> String {
    if n == 1 {
        "Message copied".to_string()
    } else {
        format!("Copied {}", messages(n))
    }
}

pub fn starred_notice(n: usize, star: bool) -> String {
    let verb = if star { "Starred" } else { "Unstarred" };
    format!("{verb} {}", messages(n))
}

pub fn deleted_notice(n: usize) -> String {
    format!("Deleted {} for you", messages(n))
}

pub fn cap_notice() -> String {
    format!("You can select up to {MAX_SELECTED} messages")
}

#[cfg(test)]
mod tests {
    use super::*;

    const T: i64 = 1_794_492_300_000;

    fn line(at_ms: i64, author: &'static str, text: &'static str) -> ExportLine<'static> {
        ExportLine {
            at_ms,
            author,
            text,
        }
    }

    #[test]
    fn toggle_adds_and_removes() {
        let mut s = Selection::new();
        assert!(s.is_empty());
        assert!(s.toggle(1));
        assert!(s.contains(&1));
        assert!(s.toggle(1));
        assert!(!s.contains(&1) && s.is_empty());
    }

    #[test]
    fn toggle_refuses_past_cap_but_allows_removal() {
        let mut s = Selection::new();
        for i in 0..MAX_SELECTED {
            assert!(s.toggle(i));
        }
        assert!(!s.toggle(MAX_SELECTED));
        assert_eq!(s.len(), MAX_SELECTED);
        assert!(s.toggle(0));
        assert!(s.toggle(MAX_SELECTED));
    }

    #[test]
    fn select_all_counts_new_and_caps() {
        let mut s = Selection::new();
        s.toggle(1);
        assert_eq!(s.select_all([1, 2, 3]), 2);
        assert_eq!(s.select_all(0..1000), MAX_SELECTED - 3);
        assert_eq!(s.len(), MAX_SELECTED);
        s.clear();
        assert!(s.is_empty());
    }

    #[test]
    fn retain_drops_vanished_keys() {
        let mut s = Selection::new();
        s.select_all([1, 2, 3, 4]);
        s.retain(|k| k % 2 == 0);
        assert_eq!(s.keys().copied().collect::<Vec<_>>(), [2, 4]);
    }

    #[test]
    fn titles() {
        assert_eq!(title(3), "3 selected");
        assert_eq!(title(0), "Select messages");
    }

    #[test]
    fn copy_block_single_is_plain_text() {
        assert_eq!(
            copy_block(&[line(T, "Ada", "  hi there \n")], 0),
            "hi there"
        );
        assert_eq!(copy_block(&[], 0), "");
    }

    #[test]
    fn copy_block_several_has_headers() {
        let out = copy_block(&[line(T, "Ada", "hi"), line(T + 60_000, "Me", "yo")], 0);
        assert_eq!(out, "-- 12 Nov 2026 --\n[14:05] Ada: hi\n[14:06] Me: yo\n");
    }

    #[test]
    fn star_all_unstars_only_when_all_starred() {
        assert!(star_all(&[true, false]));
        assert!(star_all(&[false, false]));
        assert!(!star_all(&[true, true]));
        assert!(star_all(&[]));
    }

    #[test]
    fn notices() {
        assert_eq!(copied_notice(1), "Message copied");
        assert_eq!(copied_notice(3), "Copied 3 messages");
        assert_eq!(starred_notice(1, true), "Starred 1 message");
        assert_eq!(starred_notice(2, false), "Unstarred 2 messages");
        assert_eq!(deleted_notice(3), "Deleted 3 messages for you");
        assert_eq!(deleted_notice(1), "Deleted 1 message for you");
        assert_eq!(cap_notice(), "You can select up to 200 messages");
    }
}
