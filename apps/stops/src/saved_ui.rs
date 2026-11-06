//! The Saved tab: places kept for one-tap trips. Tapping a row opens its
//! actions (Use as A, Use as B, Rename, Delete); a delete can be undone from the pill.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, div, list, px,
};
use rovsti_geo::{Place, SavedPlaces};
use rovsti_ui::{
    components::{empty_state, glass_pill, large_title, list_row},
    theme,
    tokens::Theme,
};

use crate::{
    Page, StopsApp, lists,
    picker::{Destination, Slot},
};

/// Puts a deleted place back where it was (or at the end if the list shrank).
pub fn restore(saved: &mut SavedPlaces, place: Place, index: usize) {
    if saved.places.iter().any(|p| p.id == place.id) {
        return;
    }
    let at = index.min(saved.places.len());
    saved.places.insert(at, place);
}

impl StopsApp {
    /// The Saved tab: a virtualised list whose first item is the title, so the
    /// title scrolls away with the places as before.
    pub(crate) fn saved_tab(&mut self, theme: Theme, cx: &mut Context<Self>) -> Page {
        if self.saved.places.is_empty() {
            return Page::Flow(
                empty_state(
                    theme,
                    "No Saved Stops",
                    "Turn on Save this place when you pick a point, and it appears here for one-tap use as A or B.",
                )
                .into_any_element(),
            );
        }
        // An opened place is taller: its key changes so it is measured again.
        let keys = std::iter::once(lists::key(0, 0))
            .chain(
                self.saved
                    .places
                    .iter()
                    .map(|p| lists::key(1 + u8::from(self.expanded == Some(p.id)), p.id)),
            )
            .collect();
        let state = self.saved_list.sync(keys);
        Page::List(Box::new(list(
            state,
            cx.processor(move |this, ix: usize, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                match ix.checked_sub(1) {
                    None => large_title(theme, "Saved", None).into_any_element(),
                    Some(i) => this.saved_item(theme, i, cx),
                }
            }),
        )))
    }

    /// Saved place `i` with its actions when it is open.
    fn saved_item(&mut self, theme: Theme, i: usize, cx: &mut Context<Self>) -> AnyElement {
        let count = self.saved.places.len();
        let Some(place) = self.saved.places.get(i) else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let id = place.id;
        let open = self.expanded == Some(id);
        let row = list_row(
            theme,
            None,
            place.name.clone(),
            None,
            Some(if open { "Close" } else { "" }.into()),
        )
        .id(("place", id as usize))
        .on_click(cx.listener(move |this, _, _, cx| {
            this.expanded = if this.expanded == Some(id) {
                None
            } else {
                Some(id)
            };
            cx.notify();
        }));
        let mut item = div().flex().flex_col().child(row);
        if open {
            let dest_a = Destination::from_place(place);
            let dest_b = dest_a.clone();
            item = item.child(
                div()
                    .flex()
                    .gap(px(10.0))
                    .px(px(12.0))
                    .py(px(10.0))
                    .child(
                        glass_pill(theme, "Use as A")
                            .id(("place-use-a", id as usize))
                            .flex_1()
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.expanded = None;
                                this.use_as(Slot::A, dest_a.clone(), false, cx);
                                this.tabs.jump_to(0);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Use as B")
                            .id(("place-use-b", id as usize))
                            .flex_1()
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.expanded = None;
                                this.use_as(Slot::B, dest_b.clone(), false, cx);
                                this.tabs.jump_to(0);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Rename")
                            .id(("place-rename", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_rename(id, cx);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Delete")
                            .id(("place-delete", id as usize))
                            .flex_1()
                            .text_color(colors.red)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.delete_place(id, cx);
                            })),
                    ),
            );
        }
        lists::grouped(theme, lists::Place::of(i, count), item).into_any_element()
    }

    fn delete_place(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(index) = self.saved.places.iter().position(|p| p.id == id) else {
            return;
        };
        let place = self.saved.places[index].clone();
        self.saved.remove(id);
        self.expanded = None;
        self.save_places(cx);
        let text = format!("Deleted {}", place.name);
        self.show_pill(text, Some(crate::PillAction::UndoDelete(place, index)), cx);
    }

    /// Undo on the pill.
    pub(crate) fn undo_delete(&mut self, place: Place, index: usize, cx: &mut Context<Self>) {
        restore(&mut self.saved, place, index);
        self.save_places(cx);
        cx.notify();
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use rovsti_geo::Coord;

    fn saved() -> SavedPlaces {
        let mut s = SavedPlaces::default();
        s.add("Home", Coord::new(41.0, 29.0)).unwrap();
        s.add("Work", Coord::new(41.1, 29.1)).unwrap();
        s.add("Gym", Coord::new(41.2, 29.2)).unwrap();
        s
    }

    #[test]
    fn a_deleted_place_returns_to_its_slot() {
        let mut s = saved();
        let work = s.places[1].clone();
        s.remove(work.id);
        restore(&mut s, work.clone(), 1);
        let names: Vec<_> = s.places.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["Home", "Work", "Gym"]);
    }

    #[test]
    fn restoring_twice_or_past_the_end_is_safe() {
        let mut s = saved();
        let gym = s.places[2].clone();
        s.remove(gym.id);
        restore(&mut s, gym.clone(), 99);
        restore(&mut s, gym, 0);
        assert_eq!(s.places.len(), 3);
        assert_eq!(s.places[2].name, "Gym");
    }
}
