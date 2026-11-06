//! Virtualised lists for pages and sheets that grow with the data (Saved,
//! History, the chat picker, the destination search).
//!
//! A [`KeyedList`] owns the GPUI [`ListState`] and the key of every item the
//! list showed last. Each render hands it the new keys; only the run that
//! changed is spliced, so the scroll position and the measured heights of the
//! untouched items stay. Only the items on screen are built and laid out.
//!
//! A key must change whenever the item's height may change (a row that opens,
//! a header that appears). Content that keeps the height (a renamed place)
//! needs no new key: visible items are rebuilt every frame anyway.
//!
//! [`grouped`] gives one row the look of `rovsti_ui::components::section`
//! (inset, rounded group background), so a section can be split into items.

use std::ops::Range;

use gpui::{
    AnyElement, Div, IntoElement, ListAlignment, ListOffset, ListState, ParentElement, Pixels,
    Styled, div, px,
};
use rovsti_ui::{
    theme,
    tokens::{Theme, metrics, radius, typography},
};

/// Extra height laid out above and below the viewport, so rows do not pop in.
const OVERDRAW: Pixels = px(600.0);

/// The span of `old` that `new` replaced: `(old range, new count)`, or `None`
/// when they are equal. The common prefix and suffix are kept.
pub fn changed_span<K: PartialEq>(old: &[K], new: &[K]) -> Option<(Range<usize>, usize)> {
    let prefix = old.iter().zip(new).take_while(|(a, b)| a == b).count();
    if prefix == old.len() && prefix == new.len() {
        return None;
    }
    let max_suffix = old.len().min(new.len()) - prefix;
    let suffix = old
        .iter()
        .rev()
        .zip(new.iter().rev())
        .take(max_suffix)
        .take_while(|(a, b)| a == b)
        .count();
    Some((prefix..old.len() - suffix, new.len() - prefix - suffix))
}

/// A [`ListState`] kept in step with the keys of its items.
pub struct KeyedList {
    state: ListState,
    keys: Vec<u64>,
}

impl KeyedList {
    /// An empty list; `row_hint` is the height assumed for unmeasured items.
    pub fn new(row_hint: Pixels) -> Self {
        Self {
            state: ListState::new(0, ListAlignment::Top, OVERDRAW)
                .with_uniform_item_height(row_hint),
            keys: Vec::new(),
        }
    }

    /// Takes the keys of this render and returns the state to draw with.
    pub fn sync(&mut self, keys: Vec<u64>) -> ListState {
        if let Some((range, count)) = changed_span(&self.keys, &keys) {
            self.state.splice(range, count);
        }
        self.keys = keys;
        self.state.clone()
    }

    /// Back to the first item (a page opened again starts at its top).
    pub fn scroll_to_top(&self) {
        self.state.scroll_to(ListOffset {
            item_ix: 0,
            offset_in_item: px(0.0),
        });
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.state.item_count()
    }
}

impl Default for KeyedList {
    fn default() -> Self {
        Self::new(metrics::ROW_HEIGHT_SUBTITLE)
    }
}

/// A key from an item kind and an id within that kind.
pub const fn key(tag: u8, id: u64) -> u64 {
    ((tag as u64) << 56) ^ (id & 0x00ff_ffff_ffff_ffff)
}

/// Where a row sits in its group, for the rounded corners.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Place {
    pub first: bool,
    pub last: bool,
}

impl Place {
    /// Row `i` of `len`.
    pub fn of(i: usize, len: usize) -> Self {
        Self {
            first: i == 0,
            last: i + 1 == len,
        }
    }

    /// The only row of its group.
    pub const ALONE: Self = Self {
        first: true,
        last: true,
    };
}

/// `row` inside a grouped-list background, rounded where the group starts and
/// ends: one item's share of a `section`.
pub fn grouped(theme: Theme, at: Place, row: impl IntoElement) -> Div {
    let colors = theme::colors(theme);
    let mut group = div()
        .bg(colors.secondary_background)
        .overflow_hidden()
        .child(row);
    if at.first {
        group = group.rounded_t(radius::GROUPED_LIST);
    }
    if at.last {
        group = group.rounded_b(radius::GROUPED_LIST);
    }
    div().px(metrics::ROW_INSET).child(group)
}

/// A section's footnote-sized header, `gap_above` below the previous section.
pub fn section_header(theme: Theme, text: &'static str, gap_above: f32) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .pt(px(gap_above))
        .pb(px(6.0))
        .px(metrics::ROW_INSET * 2.0)
        .text_size(typography::FOOTNOTE.size)
        .text_color(colors.secondary_label)
        .child(text)
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn equal_keys_change_nothing() {
        assert_eq!(changed_span(&[1, 2, 3], &[1, 2, 3]), None);
        assert_eq!(changed_span::<u8>(&[], &[]), None);
    }

    #[test]
    fn only_the_changed_run_is_replaced() {
        // One deleted in the middle.
        assert_eq!(changed_span(&[1, 2, 3, 4], &[1, 2, 4]), Some((2..3, 0)));
        // One inserted at the front.
        assert_eq!(changed_span(&[2, 3], &[1, 2, 3]), Some((0..0, 1)));
        // Appended at the end.
        assert_eq!(changed_span(&[1, 2], &[1, 2, 3, 4]), Some((2..2, 2)));
        // One replaced (a row that opened).
        assert_eq!(changed_span(&[1, 2, 3], &[1, 9, 3]), Some((1..2, 1)));
        // Everything different.
        assert_eq!(changed_span(&[1, 2], &[3]), Some((0..2, 1)));
        // Prefix and suffix never overlap on repeated keys.
        assert_eq!(changed_span(&[1, 1], &[1, 1, 1]), Some((2..2, 1)));
        assert_eq!(changed_span(&[1, 1, 1], &[1]), Some((1..3, 0)));
    }

    #[test]
    fn the_list_follows_the_keys() {
        let mut list = KeyedList::default();
        list.sync(vec![1, 2, 3]);
        assert_eq!(list.len(), 3);
        list.sync(vec![1, 3]);
        assert_eq!(list.len(), 2);
        list.sync(vec![0, 1, 3, 4]);
        assert_eq!(list.len(), 4);
        list.sync(Vec::new());
        assert_eq!(list.len(), 0);
    }

    #[test]
    fn keys_of_different_kinds_differ() {
        assert_ne!(key(1, 7), key(2, 7));
        assert_ne!(key(1, 7), key(1, 8));
        assert_eq!(key(3, 5), key(3, 5));
    }

    #[test]
    fn group_places_mark_the_ends() {
        assert_eq!(Place::of(0, 1), Place::ALONE);
        assert_eq!(
            Place::of(1, 3),
            Place {
                first: false,
                last: false
            }
        );
        assert!(Place::of(2, 3).last);
    }
}
