//! A virtualised `gpui::list` whose items are named by keys.
//!
//! Every screen that grows with the user's data (the inbox, both
//! conversations) draws through `gpui::list`, which only builds the rows on
//! screen and caches the heights of the others. The list must be told when
//! items change; [`KeyedList::sync`] does that from two key lists: the span
//! between the common head and tail is re-measured (same length) or spliced
//! (inserts and removals), and the item the reader was looking at keeps its
//! place on screen. The diff itself is pure ([`changed_span`]) and tested.

use std::{
    hash::{DefaultHasher, Hash, Hasher},
    ops::Range,
};

use gpui::{ListAlignment, ListOffset, ListState, px};

/// Pixels measured above and below the viewport, so a fling does not pop rows in.
const OVERDRAW: f32 = 600.0;

/// A stable key for an item: the hash of what it shows (and so of its height).
pub(crate) fn key_of(value: impl Hash) -> u64 {
    let mut hasher = DefaultHasher::new();
    value.hash(&mut hasher);
    hasher.finish()
}

/// The part of a key list that differs from the one before.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Change {
    /// The old items that were replaced.
    pub removed: Range<usize>,
    /// How many new items stand in their place.
    pub inserted: usize,
}

/// The span between the common head and tail of `old` and `new`, `None` when
/// they are equal.
pub(crate) fn changed_span(old: &[u64], new: &[u64]) -> Option<Change> {
    if old == new {
        return None;
    }
    let head = old.iter().zip(new).take_while(|(a, b)| a == b).count();
    let room = old.len().min(new.len()) - head;
    let tail = old
        .iter()
        .rev()
        .zip(new.iter().rev())
        .take(room)
        .take_while(|(a, b)| a == b)
        .count();
    Some(Change {
        removed: head..old.len() - tail,
        inserted: new.len() - tail - head,
    })
}

/// A `ListState` plus the keys of the items it holds.
pub(crate) struct KeyedList {
    state: ListState,
    keys: Vec<u64>,
    alignment: ListAlignment,
    /// Items before this index never anchor the scroll position: a reader at
    /// the "Load earlier" slot keeps the message under it on screen.
    anchor_from: usize,
}

impl KeyedList {
    pub(crate) fn new(alignment: ListAlignment, anchor_from: usize) -> Self {
        Self {
            state: ListState::new(0, alignment, px(OVERDRAW)),
            keys: Vec::new(),
            alignment,
            anchor_from,
        }
    }

    /// The state the `list` element draws from.
    pub(crate) fn state(&self) -> &ListState {
        &self.state
    }

    /// Tells the list which items it holds now. Unchanged items keep their
    /// measured heights; the item at the top of the screen stays there.
    pub(crate) fn sync(&mut self, keys: Vec<u64>) {
        let Some(change) = changed_span(&self.keys, &keys) else {
            return;
        };
        let top = self.state.logical_scroll_top();
        // A bottom-aligned list that is scrolled to its end follows new items.
        let pinned = self.alignment == ListAlignment::Bottom && top.item_ix >= self.keys.len();
        let anchor = if pinned {
            None
        } else {
            let index = top.item_ix.max(self.anchor_from);
            let lifted = index != top.item_ix;
            let offset = if lifted { px(0.0) } else { top.offset_in_item };
            self.keys
                .get(index)
                .map(|&key| (key, index, offset, lifted))
        };

        if change.removed.len() == change.inserted {
            self.state.remeasure_items(change.removed);
        } else {
            self.state.splice(change.removed, change.inserted);
        }

        if let Some((key, index, offset, lifted)) = anchor
            && let Some(found) = keys.iter().position(|&k| k == key)
            && !(lifted && found == index)
        {
            self.state.scroll_to(ListOffset {
                item_ix: found,
                offset_in_item: offset,
            });
        }
        self.keys = keys;
    }

    /// Shows the end of the list (a bottom-aligned list then follows new items).
    pub(crate) fn scroll_to_end(&self) {
        self.state.scroll_to_end();
    }

    /// Shows the start of the list.
    pub(crate) fn scroll_to_top(&self) {
        self.state.scroll_to(ListOffset::default());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn equal_lists_have_no_change() {
        assert_eq!(changed_span(&[1, 2, 3], &[1, 2, 3]), None);
        assert_eq!(changed_span(&[], &[]), None);
    }

    #[test]
    fn an_append_is_an_insert_at_the_end() {
        assert_eq!(
            changed_span(&[0, 1, 2], &[0, 1, 2, 3]),
            Some(Change {
                removed: 3..3,
                inserted: 1
            })
        );
        assert_eq!(
            changed_span(&[], &[7, 8]),
            Some(Change {
                removed: 0..0,
                inserted: 2
            })
        );
    }

    #[test]
    fn older_items_insert_after_the_head() {
        // The slot at 0 stays, two older messages come in under it.
        assert_eq!(
            changed_span(&[0, 5, 6], &[0, 3, 4, 5, 6]),
            Some(Change {
                removed: 1..1,
                inserted: 2
            })
        );
    }

    #[test]
    fn an_edit_in_place_keeps_the_length() {
        assert_eq!(
            changed_span(&[0, 1, 2, 3], &[0, 1, 9, 3]),
            Some(Change {
                removed: 2..3,
                inserted: 1
            })
        );
    }

    #[test]
    fn removals_and_repeats_never_overlap() {
        assert_eq!(
            changed_span(&[1, 2, 2, 3], &[1, 2, 3]),
            Some(Change {
                removed: 2..3,
                inserted: 0
            })
        );
        // Repeated keys: head and tail may not claim the same item twice.
        assert_eq!(
            changed_span(&[4, 4], &[4, 4, 4]),
            Some(Change {
                removed: 2..2,
                inserted: 1
            })
        );
        assert_eq!(
            changed_span(&[1, 2, 3], &[]),
            Some(Change {
                removed: 0..3,
                inserted: 0
            })
        );
    }

    #[test]
    fn keys_follow_content() {
        assert_eq!(key_of(("msg", "a")), key_of(("msg", "a")));
        assert_ne!(key_of(("msg", "a")), key_of(("msg", "b")));
    }
}
