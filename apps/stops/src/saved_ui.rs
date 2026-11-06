//! The Saved tab: places kept for one-tap trips. Tapping a row opens its
//! actions (Use as A, Use as B, Share, Rename, Up, Down, Delete); a delete can be undone from the
//! pill. Share opens the system share sheet (`telecom::share_text`) and falls back to the
//! clipboard when there is no sheet (desktop, or no app answered).

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, div, list, px,
};
use rovsti_android::Error as AndroidError;
use rovsti_geo::{Coord, Place, SavedPlaces};
use rovsti_ui::{
    components::{empty_state, glass_pill, large_title, list_row},
    theme,
    tokens::Theme,
};

use crate::{
    Page, StopsApp, lists, pick,
    picker::{Destination, Slot},
    trip_ui::distance_text,
};

/// The row subtitle: what the place is, then how far it is when your position is known.
pub fn place_detail(place: &Place, here: Option<Coord>) -> String {
    let kind = if place.gtfs_stop.is_some() {
        "Transit stop"
    } else {
        "Place"
    };
    match here {
        Some(h) => format!("{kind}, {}", distance_text(h.distance_m(place.coord))),
        None => kind.to_owned(),
    }
}

/// Puts a deleted place back where it was (or at the end if the list shrank).
pub fn restore(saved: &mut SavedPlaces, place: Place, index: usize) {
    if saved.places.iter().any(|p| p.id == place.id) {
        return;
    }
    let at = index.min(saved.places.len());
    saved.places.insert(at, place);
}

/// What to do after the share call returned.
#[derive(Debug, PartialEq, Eq)]
pub enum ShareOutcome {
    /// The system sheet is the feedback.
    Shared,
    /// No sheet: copy the text and say so on the pill.
    Copy(String),
    /// The call failed: only the pill.
    Failed(String),
}

/// Maps the result of `telecom::share_text` to the follow-up for place `name`.
pub fn share_outcome(result: &Result<bool, AndroidError>, name: &str) -> ShareOutcome {
    match result {
        Ok(true) => ShareOutcome::Shared,
        Ok(false) | Err(AndroidError::Unsupported) => ShareOutcome::Copy(format!("Copied {name}")),
        Err(_) => ShareOutcome::Failed(format!("Could not share {name}")),
    }
}

impl StopsApp {
    /// The Saved tab: a virtualised list whose first item is the title, so the
    /// title scrolls away with the places as before.
    pub(crate) fn saved_tab(&mut self, theme: Theme, cx: &mut Context<Self>) -> Page {
        if self.saved.places.is_empty() {
            return Page::Flow(
                empty_state(
                    theme,
                    "No Saved Places",
                    "Tap a pin on the map and choose Save, or save a stop or a shared place. Saved places appear here for one-tap use as A or B.",
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
        let detail = place_detail(place, self.map.position.map(|(c, _)| c));
        let open = self.expanded == Some(id);
        let row = list_row(
            theme,
            None,
            place.name.clone(),
            Some(detail.into()),
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
                    .pt(px(10.0))
                    .pb(px(6.0))
                    .child(
                        glass_pill(theme, "Use as A")
                            .id(("place-use-a", id as usize))
                            .flex_1()
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.expanded = None;
                                this.use_as(Slot::A, dest_a.clone(), false, cx);
                                if this.trip.is_some() {
                                    let text = pick::queued_text(Slot::A, &dest_a);
                                    this.show_pill(text, None, cx);
                                }
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
                                if this.trip.is_some() {
                                    let text = pick::queued_text(Slot::B, &dest_b);
                                    this.show_pill(text, None, cx);
                                }
                                this.tabs.jump_to(0);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Share")
                            .id(("place-share", id as usize))
                            .flex_1()
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.share_place(id, cx);
                            })),
                    ),
            );
            let pill_row = || div().flex().gap(px(10.0)).px(px(12.0)).pb(px(10.0));
            let up: AnyElement = if i > 0 {
                glass_pill(theme, "Up")
                    .id(("place-up", id as usize))
                    .flex_1()
                    .on_click(cx.listener(move |this, _, _, cx| this.move_place(id, -1, cx)))
                    .into_any_element()
            } else {
                glass_pill(theme, "Up")
                    .flex_1()
                    .text_color(colors.tertiary_label)
                    .into_any_element()
            };
            let down: AnyElement = if i + 1 < count {
                glass_pill(theme, "Down")
                    .id(("place-down", id as usize))
                    .flex_1()
                    .on_click(cx.listener(move |this, _, _, cx| this.move_place(id, 1, cx)))
                    .into_any_element()
            } else {
                glass_pill(theme, "Down")
                    .flex_1()
                    .text_color(colors.tertiary_label)
                    .into_any_element()
            };
            item = item.child(
                pill_row()
                    .child(
                        glass_pill(theme, "Rename")
                            .id(("place-rename", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_rename(id, cx);
                            })),
                    )
                    .child(up)
                    .child(down)
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

    fn move_place(&mut self, id: u64, delta: isize, cx: &mut Context<Self>) {
        if self.saved.move_by(id, delta) {
            self.save_places(cx);
            cx.notify();
        }
    }

    /// Opens the share sheet with the place's text; copies it when there is none.
    fn share_place(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(place) = self.saved.places.iter().find(|p| p.id == id) else {
            return;
        };
        let name = place.name.clone();
        self.share_point(name.clone(), place.coord, &name, cx);
    }

    /// Shares a point as text (`label` plus the map link; the link alone when
    /// the label is empty). Falls back to the clipboard with a pill naming `name`.
    pub(crate) fn share_point(
        &mut self,
        name: String,
        coord: Coord,
        label: &str,
        cx: &mut Context<Self>,
    ) {
        let text = rovsti_geo::share_text(label, coord);
        cx.spawn(async move |this, cx| {
            let sent = text.clone();
            let result = cx
                .background_executor()
                .spawn(async move { rovsti_android::telecom::share_text(&sent) })
                .await;
            this.update(cx, |this, cx| match share_outcome(&result, &name) {
                ShareOutcome::Shared => {}
                ShareOutcome::Copy(pill) => {
                    cx.write_to_clipboard(gpui::ClipboardItem::new_string(text));
                    this.show_pill(pill, None, cx);
                }
                ShareOutcome::Failed(pill) => this.show_pill(pill, None, cx),
            })
            .ok();
        })
        .detach();
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

    #[test]
    fn detail_names_the_kind_and_distance() {
        let mut p = Place {
            id: 1,
            name: "Home".into(),
            coord: Coord::new(41.0, 29.0),
            gtfs_stop: None,
        };
        assert_eq!(place_detail(&p, None), "Place");
        let here = Coord::new(41.0, 29.0);
        assert_eq!(place_detail(&p, Some(here)), "Place, 0 m");
        p.gtfs_stop = Some("S1".into());
        assert_eq!(place_detail(&p, None), "Transit stop");
        assert!(place_detail(&p, Some(Coord::new(41.1, 29.0))).starts_with("Transit stop, 11."));
    }

    #[test]
    fn share_outcomes() {
        assert_eq!(share_outcome(&Ok(true), "Home"), ShareOutcome::Shared);
        let copy = ShareOutcome::Copy("Copied Home".into());
        assert_eq!(share_outcome(&Ok(false), "Home"), copy);
        assert_eq!(share_outcome(&Err(AndroidError::Unsupported), "Home"), copy);
    }

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
