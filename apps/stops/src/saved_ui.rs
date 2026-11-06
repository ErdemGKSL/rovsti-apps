//! The Saved tab: stops (a gate of two points) kept for one-tap trips. Tapping a
//! row opens its actions (Rename, Up, Down, Delete); a delete can be undone from
//! the pill. `share_point` opens the system share sheet (`telecom::share_text`)
//! and falls back to the clipboard when there is no sheet (desktop, or no app
//! answered).

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, div, list, px,
};
use rovsti_android::Error as AndroidError;
use rovsti_geo::{Coord, SavedStop};
use rovsti_ui::{
    components::{empty_state, glass_pill, large_title, list_row},
    theme,
    tokens::Theme,
};

use crate::{Page, StopsApp, lists, trip_ui::distance_text};

/// The row subtitle: the gate length, then how far its middle is when your
/// position is known.
pub fn stop_detail(stop: &SavedStop, here: Option<Coord>) -> String {
    let gate = format!("Gate {}", distance_text(stop.length_m()));
    match here {
        Some(h) => format!(
            "{gate}, {} away",
            distance_text(h.distance_m(stop.center()))
        ),
        None => gate,
    }
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

/// Maps the result of `telecom::share_text` to the follow-up for `name`.
pub fn share_outcome(result: &Result<bool, AndroidError>, name: &str) -> ShareOutcome {
    match result {
        Ok(true) => ShareOutcome::Shared,
        Ok(false) | Err(AndroidError::Unsupported) => ShareOutcome::Copy(format!("Copied {name}")),
        Err(_) => ShareOutcome::Failed(format!("Could not share {name}")),
    }
}

impl StopsApp {
    /// The Saved tab: a virtualised list whose first item is the title, so the
    /// title scrolls away with the stops.
    pub(crate) fn saved_tab(&mut self, theme: Theme, cx: &mut Context<Self>) -> Page {
        if self.saved.stops.is_empty() {
            let colors = theme::colors(theme);
            return Page::Flow(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .child(empty_state(
                        theme,
                        "No Saved Stops",
                        "Set point A and point B on the map, then tap Save stop.",
                    ))
                    .child(
                        glass_pill(theme, "Open the map")
                            .id("saved-open-map")
                            .px(px(20.0))
                            .text_color(colors.accent)
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.tabs.jump_to(0);
                                cx.notify();
                            })),
                    )
                    .into_any_element(),
            );
        }
        // An opened stop is taller: its key changes so it is measured again.
        let keys = std::iter::once(lists::key(0, 0))
            .chain(
                self.saved
                    .stops
                    .iter()
                    .map(|st| lists::key(1 + u8::from(self.expanded == Some(st.id)), st.id)),
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

    /// Saved stop `i` with its actions when it is open.
    fn saved_item(&mut self, theme: Theme, i: usize, cx: &mut Context<Self>) -> AnyElement {
        let count = self.saved.stops.len();
        let Some(stop) = self.saved.stops.get(i) else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let id = stop.id;
        let detail = stop_detail(stop, self.map.position.map(|(c, _)| c));
        let open = self.expanded == Some(id);
        let row = list_row(
            theme,
            None,
            stop.name.clone(),
            Some(detail.into()),
            Some(if open { "Close" } else { "" }.into()),
        )
        .id(("saved-stop", id as usize))
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
            let pill_row = || div().flex().gap(px(10.0)).px(px(12.0)).pb(px(10.0));
            let up: AnyElement = if i > 0 {
                glass_pill(theme, "Up")
                    .id(("stop-up", id as usize))
                    .flex_1()
                    .on_click(cx.listener(move |this, _, _, cx| this.move_stop(id, -1, cx)))
                    .into_any_element()
            } else {
                glass_pill(theme, "Up")
                    .flex_1()
                    .text_color(colors.tertiary_label)
                    .into_any_element()
            };
            let down: AnyElement = if i + 1 < count {
                glass_pill(theme, "Down")
                    .id(("stop-down", id as usize))
                    .flex_1()
                    .on_click(cx.listener(move |this, _, _, cx| this.move_stop(id, 1, cx)))
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
                            .id(("stop-rename", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_rename(id, cx);
                            })),
                    )
                    .child(up)
                    .child(down)
                    .child(
                        glass_pill(theme, "Delete")
                            .id(("stop-delete", id as usize))
                            .flex_1()
                            .text_color(colors.red)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.delete_stop(id, cx);
                            })),
                    ),
            );
        }
        lists::grouped(theme, lists::Place::of(i, count), item).into_any_element()
    }

    fn move_stop(&mut self, id: u64, delta: isize, cx: &mut Context<Self>) {
        if self.saved.move_by(id, delta) {
            self.save_stops(cx);
            cx.notify();
        }
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

    fn delete_stop(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(index) = self.saved.stops.iter().position(|st| st.id == id) else {
            return;
        };
        let stop = self.saved.stops[index].clone();
        self.saved.remove(id);
        self.expanded = None;
        self.save_stops(cx);
        let text = format!("Deleted {}", stop.name);
        self.show_pill(text, Some(crate::PillAction::UndoDelete(stop, index)), cx);
    }

    /// Undo on the pill.
    pub(crate) fn undo_delete(&mut self, stop: SavedStop, index: usize, cx: &mut Context<Self>) {
        self.saved.restore(stop, index);
        self.save_stops(cx);
        cx.notify();
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use rovsti_geo::{Endpoint, SavedStops};

    fn stop(name: &str, lat_b: f64) -> SavedStop {
        let mut s = SavedStops::default();
        let ep = |lat| Endpoint {
            label: String::new(),
            coord: Coord::new(lat, 29.0),
        };
        let id = s.add(name, ep(41.0), ep(lat_b), 0).unwrap();
        s.get(id).unwrap().clone()
    }

    #[test]
    fn detail_names_the_gate_and_distance() {
        // 0.0038 degrees of latitude is about 420 m.
        let st = stop("Pier", 41.0038);
        assert_eq!(stop_detail(&st, None), "Gate 420 m");
        let here = st.center();
        assert_eq!(stop_detail(&st, Some(here)), "Gate 420 m, 0 m away");
        let far = Coord::new(41.1, 29.0);
        let text = stop_detail(&st, Some(far));
        assert!(
            text.starts_with("Gate 420 m, 10.") && text.ends_with(" km away"),
            "{text}"
        );
    }

    #[test]
    fn share_outcomes() {
        assert_eq!(share_outcome(&Ok(true), "Home"), ShareOutcome::Shared);
        let copy = ShareOutcome::Copy("Copied Home".into());
        assert_eq!(share_outcome(&Ok(false), "Home"), copy);
        assert_eq!(share_outcome(&Err(AndroidError::Unsupported), "Home"), copy);
    }

    fn saved() -> SavedStops {
        let mut s = SavedStops::default();
        for (i, name) in ["Home", "Work", "Gym"].into_iter().enumerate() {
            let lat = 41.0 + i as f64;
            let ep = |lat| Endpoint {
                label: String::new(),
                coord: Coord::new(lat, 29.0),
            };
            s.add(name, ep(lat), ep(lat + 0.003), 0).unwrap();
        }
        s
    }

    #[test]
    fn a_deleted_stop_returns_to_its_slot() {
        let mut s = saved();
        let work = s.stops[1].clone();
        s.remove(work.id);
        s.restore(work, 1);
        let names: Vec<_> = s.stops.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["Home", "Work", "Gym"]);
    }

    #[test]
    fn restoring_twice_or_past_the_end_is_safe() {
        let mut s = saved();
        let gym = s.stops[2].clone();
        s.remove(gym.id);
        s.restore(gym.clone(), 99);
        s.restore(gym, 0);
        assert_eq!(s.stops.len(), 3);
        assert_eq!(s.stops[2].name, "Gym");
    }
}
