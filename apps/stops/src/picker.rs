//! Choosing where to go: the sheet with a paste field, saved places and recent
//! destinations, and the confirmation that follows a shared location.
//!
//! [`offer`] is the pure core: text from a share, a link or the paste field
//! becomes a destination to confirm, or a sentence that says why not.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_geo::{AlarmRule, Coord, Place, dest::parse_location};
use rovsti_ui::{
    components::{glass_pill, list_row, section},
    field::field_box,
    theme,
    tokens::{Theme, typography},
};

use crate::{
    StopsApp,
    transit::{self, MAX_LINES},
    trip_ui,
};

/// Stops listed for a search.
const MAX_STOP_HITS: usize = 20;

/// Where a trip is headed.
#[derive(Clone, Debug, PartialEq)]
pub struct Destination {
    pub label: String,
    pub coord: Coord,
    /// A saved place's own rule; `None` follows the "Wake me" chips.
    pub rule: Option<AlarmRule>,
    /// The transit stop this is, when it came from an imported feed.
    pub gtfs_stop: Option<String>,
    /// The line it was chosen on ("N stops before"), drawn on the map.
    pub pattern: Option<u32>,
}

impl Destination {
    pub fn from_place(place: &Place) -> Self {
        Self {
            label: place.name.clone(),
            coord: place.coord,
            rule: place.rule,
            gtfs_stop: place.gtfs_stop.clone(),
            pattern: None,
        }
    }

    pub fn new(label: impl Into<String>, coord: Coord) -> Self {
        Self {
            label: label.into(),
            coord,
            rule: None,
            gtfs_stop: None,
            pattern: None,
        }
    }

    /// The confirmation sheet's heading.
    pub fn title(&self) -> String {
        format!("Wake me near {}", self.label)
    }

    pub fn coords_text(&self) -> String {
        coord_text(self.coord)
    }
}

/// `41.0370, 28.9850`: the name of a pin that came without one.
pub fn coord_text(coord: Coord) -> String {
    format!("{:.4}, {:.4}", coord.lat, coord.lon)
}

/// What shared or pasted text leads to.
#[derive(Clone, Debug, PartialEq)]
pub enum Offer {
    /// A destination to confirm.
    Confirm(Destination),
    /// Why this text is no destination, for the user.
    Refused(&'static str),
}

/// Reads a location out of `text` (a `geo:` URI, a maps link, bare
/// coordinates). Nothing is fetched. A pin without a name is labelled with its
/// coordinates.
pub fn offer(text: &str) -> Offer {
    match parse_location(text) {
        Ok(place) => {
            let label = place
                .label
                .map(|l| l.trim().to_owned())
                .filter(|l| !l.is_empty())
                .unwrap_or_else(|| coord_text(place.coord));
            Offer::Confirm(Destination::new(label, place.coord))
        }
        Err(error) => Offer::Refused(error.message()),
    }
}

/// What the sheet is showing.
#[derive(Clone, Debug, PartialEq)]
pub enum SheetMode {
    /// Paste field, Saved, Recent.
    Pick,
    /// "Wake me near ...", rule chips, Save toggle.
    Confirm(Destination),
    /// A new name for a saved place.
    Rename(u64),
    /// A transit stop (index into the feed): its lines and "N stops before".
    Stop(u32),
}

/// The stop search for one query, so typing searches once and not once per
/// frame of the sheet's animation.
#[derive(Default)]
pub struct StopHits {
    query: String,
    hits: Vec<u32>,
}

const PICKER_LABEL: &str = "Choose destination";

impl StopsApp {
    /// Opens the picker from the Trip tab.
    pub(crate) fn open_picker(&mut self, cx: &mut Context<Self>) {
        self.field.update(cx, |f, cx| f.clear(cx));
        self.sheet_mode = SheetMode::Pick;
        self.save_place = false;
        self.sheet.open();
        cx.notify();
    }

    /// Shared text arrived (a link opened with Stops, or a share): confirm it
    /// or say why not.
    pub(crate) fn offer_text(&mut self, text: &str, cx: &mut Context<Self>) {
        match offer(text) {
            Offer::Confirm(dest) => self.confirm_destination(dest, cx),
            Offer::Refused(why) => self.show_pill(why, None, cx),
        }
    }

    pub(crate) fn confirm_destination(&mut self, dest: Destination, cx: &mut Context<Self>) {
        self.tabs.jump_to(0);
        self.alarm = None;
        self.sheet_mode = SheetMode::Confirm(dest);
        self.save_place = false;
        self.sheet.open();
        cx.notify();
    }

    pub(crate) fn open_rename(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(place) = self.saved.get(id) else {
            return;
        };
        let name = place.name.clone();
        self.field.update(cx, |f, cx| f.set(&name, cx));
        self.sheet_mode = SheetMode::Rename(id);
        self.sheet.open();
        cx.notify();
    }

    pub(crate) fn close_sheet(&mut self, cx: &mut Context<Self>) {
        if matches!(self.sheet_mode, SheetMode::Stop(_)) {
            // A line looked at but not chosen leaves the map again.
            self.map.pattern = self.dest.as_ref().and_then(|d| d.pattern);
        }
        self.sheet.close();
        cx.notify();
    }

    /// A saved or recent destination was tapped: it becomes the trip's
    /// destination, ready for Start.
    pub(crate) fn choose_destination(&mut self, dest: Destination, cx: &mut Context<Self>) {
        self.map.pattern = dest.pattern;
        self.map.follow = true;
        self.dest = Some(dest);
        self.sheet.close();
        cx.notify();
    }

    fn rename_place(&mut self, id: u64, cx: &mut Context<Self>) {
        let name = self.field.read(cx).text().to_owned();
        match self.saved.rename(id, &name) {
            Ok(()) => {
                self.save_places(cx);
                self.sheet.close();
            }
            Err(error) => self.show_pill(error.message(), None, cx),
        }
        cx.notify();
    }

    /// The sheet over the page, or `None` while it is closed.
    pub(crate) fn sheet_view(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.sheet.set_reduced(window.prefers_reduced_motion());
        self.sheet.set_viewport(window.viewport_size());
        let want_back = self.sheet.is_open() || self.alarm.is_some();
        if want_back != self.back_enabled {
            self.back_enabled = want_back;
            window.set_back_enabled(want_back);
        }
        if !self.sheet.is_visible() {
            return None;
        }
        // The keyboard covers the bottom of the screen: lift the content.
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let (label, content) = match self.sheet_mode.clone() {
            SheetMode::Pick => (PICKER_LABEL, self.pick_body(theme, window, cx)),
            SheetMode::Confirm(dest) => ("Wake me", self.confirm_body(theme, &dest, cx)),
            SheetMode::Rename(id) => ("Rename place", self.rename_body(theme, id, window, cx)),
            SheetMode::Stop(stop) => ("Transit stop", self.stop_body(theme, stop, cx)),
        };
        let body = div()
            .flex()
            .flex_col()
            .pb(px(12.0) + keyboard)
            .child(content);
        let entity = cx.entity();
        let drag = entity.clone();
        self.sheet.render(
            theme,
            window,
            label,
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    fn sheet_title(theme: Theme, text: impl Into<SharedString>) -> AnyElement {
        div()
            .px(px(20.0))
            .pb(px(8.0))
            .text_size(typography::TITLE3.size)
            .font_weight(typography::TITLE3.weight)
            .text_color(theme::colors(theme).label)
            .child(text.into())
            .into_any_element()
    }

    fn footnote(theme: Theme, text: impl Into<SharedString>) -> AnyElement {
        div()
            .px(px(20.0))
            .py(px(6.0))
            .text_size(typography::FOOTNOTE.size)
            .text_color(theme::colors(theme).secondary_label)
            .child(text.into())
            .into_any_element()
    }

    fn pick_body(&mut self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let query = self.field.read(cx).text().trim().to_owned();
        let mut body = div().flex().flex_col().gap(px(8.0));
        body = body
            .child(Self::sheet_title(theme, PICKER_LABEL))
            .child(div().px(px(16.0)).child(field_box(
                theme,
                "dest-field",
                &self.field,
                "Paste a link or coordinates",
                window,
                cx,
            )));

        if !query.is_empty() {
            match offer(&query) {
                Offer::Confirm(dest) => {
                    let row = list_row(
                        theme,
                        None,
                        dest.title(),
                        Some(dest.coords_text().into()),
                        None,
                    )
                    .id("offer-row")
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.confirm_destination(dest.clone(), cx);
                    }));
                    body = body.child(section(theme, None, [row.into_any_element()]));
                }
                // Typing a name searches Saved below: only a link or a pair of
                // numbers that failed is worth an explanation.
                Offer::Refused(why) if looks_like_link(&query) => {
                    body = body.child(Self::footnote(theme, why));
                }
                Offer::Refused(_) => {}
            }
        }

        if let Some(rows) = self.stop_rows(theme, &query, cx) {
            body = body.child(section(theme, Some("Stops".into()), rows));
        }

        let saved: Vec<_> = self
            .saved
            .search(&query)
            .into_iter()
            .map(|p| (p.id, Destination::from_place(p)))
            .collect();
        if !saved.is_empty() {
            let rows = saved.into_iter().map(|(id, dest)| {
                let subtitle = trip_ui::rule_text(dest.rule.unwrap_or_else(|| self.rule.to_rule()));
                list_row(theme, None, dest.label.clone(), Some(subtitle.into()), None)
                    .id(("pick-saved", id as usize))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.choose_destination(dest.clone(), cx);
                    }))
                    .into_any_element()
            });
            body = body.child(section(theme, Some("Saved".into()), rows));
        }
        let recent = self.history.recent_destinations(5);
        if !recent.is_empty() && query.is_empty() {
            let rows = recent.into_iter().enumerate().map(|(i, (label, coord))| {
                let dest = Destination::new(label, coord);
                list_row(
                    theme,
                    None,
                    dest.label.clone(),
                    Some(dest.coords_text().into()),
                    None,
                )
                .id(("pick-recent", i))
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.choose_destination(dest.clone(), cx);
                }))
                .into_any_element()
            });
            body = body.child(section(theme, Some("Recent".into()), rows));
        }
        if self.saved.places.is_empty() && self.history.records.is_empty() && query.is_empty() {
            body = body.child(Self::footnote(
                theme,
                "Share a location from any maps app to Rovsti Stops, or paste its link here.",
            ));
        }
        body.into_any_element()
    }

    /// Rows for the stops whose name matches `query`, nearest the last
    /// position first. `None` without a feed, a query or a match.
    fn stop_rows(
        &mut self,
        theme: Theme,
        query: &str,
        cx: &mut Context<Self>,
    ) -> Option<Vec<AnyElement>> {
        let feed = self.gtfs.clone()?;
        if query.is_empty() {
            return None;
        }
        if self.stop_hits.query != query {
            let near = self.map.position.map(|p| p.0);
            self.stop_hits = StopHits {
                query: query.to_owned(),
                hits: feed.search(query, near, MAX_STOP_HITS),
            };
        }
        if self.stop_hits.hits.is_empty() {
            return None;
        }
        let near = self.map.position.map(|p| p.0);
        let rows = self
            .stop_hits
            .hits
            .iter()
            .filter_map(|&index| {
                let stop = feed.stops.get(index as usize)?;
                let lines = transit::lines_through(&feed, index, MAX_LINES).len();
                let mut detail = match lines {
                    0 => "No lines".to_owned(),
                    1 => "1 line".to_owned(),
                    n => format!("{n} lines"),
                };
                if let Some(here) = near {
                    detail = format!(
                        "{}, {detail}",
                        trip_ui::distance_text(here.distance_m(stop.coord))
                    );
                }
                Some(
                    list_row(theme, None, stop.name.clone(), Some(detail.into()), None)
                        .id(("pick-stop", index as usize))
                        .on_click(cx.listener(move |this, _, _, cx| this.open_stop(index, cx)))
                        .into_any_element(),
                )
            })
            .collect();
        Some(rows)
    }

    /// A stop of the feed: wake near it, or a few stops before it on a line.
    fn stop_body(&mut self, theme: Theme, stop: u32, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let Some(feed) = self.gtfs.clone() else {
            return Self::footnote(theme, "The transit data is gone.");
        };
        let Some(info) = feed.stops.get(stop as usize) else {
            return Self::footnote(theme, "That stop is not in the transit data.");
        };
        let name = info.name.clone();
        let coord = info.coord;
        let gtfs_id = info.id.clone();
        let here = Destination {
            gtfs_stop: Some(gtfs_id),
            ..Destination::new(name.clone(), coord)
        };
        let mut body = div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, name.clone()))
            .child(Self::footnote(theme, coord_text(coord)));

        let chosen = self
            .stop_pattern
            .filter(|&p| !transit::wake_options(&feed, p, stop).is_empty());
        match chosen {
            Some(pattern) => {
                let line = feed
                    .patterns
                    .get(pattern as usize)
                    .and_then(|p| Some((p, feed.routes.get(p.route as usize)?)));
                let title = line.map_or_else(String::new, |(p, route)| {
                    format!("Line {} to {}", transit::route_name(route), p.headsign)
                });
                let rows = transit::wake_options(&feed, pattern, stop)
                    .into_iter()
                    .map(|(n, wake)| {
                        let dest = Destination {
                            rule: Some(wake.rule),
                            pattern: Some(pattern),
                            gtfs_stop: here.gtfs_stop.clone(),
                            ..Destination::new(wake.label.clone(), coord)
                        };
                        let text = if n == 1 {
                            "Wake me 1 stop before".to_owned()
                        } else {
                            format!("Wake me {n} stops before")
                        };
                        list_row(theme, None, text, Some(wake.label.into()), None)
                            .id(("stop-before", n))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.choose_destination(dest.clone(), cx);
                            }))
                            .into_any_element()
                    })
                    .chain(std::iter::once(
                        list_row(theme, None, "Choose another line", None, None)
                            .id("stop-other-line")
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.stop_pattern = None;
                                this.map.pattern = None;
                                cx.notify();
                            }))
                            .into_any_element(),
                    ));
                body = body.child(section(theme, Some(title.into()), rows));
            }
            None => {
                let lines = transit::lines_through(&feed, stop, MAX_LINES);
                if !lines.is_empty() {
                    let rows = lines.into_iter().map(|line| {
                        let pattern = line.pattern;
                        let title = if line.headsign.is_empty() {
                            format!("Line {}", line.name)
                        } else {
                            format!("Line {} to {}", line.name, line.headsign)
                        };
                        let stops = if line.before == 1 {
                            "1 stop before".to_owned()
                        } else {
                            format!("{} stops before", line.before)
                        };
                        list_row(theme, None, title, Some(stops.into()), None)
                            .id(("stop-line", pattern as usize))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.stop_pattern = Some(pattern);
                                this.map.pattern = Some(pattern);
                                cx.notify();
                            }))
                            .into_any_element()
                    });
                    body = body.child(section(
                        theme,
                        Some("Wake me before this stop on".into()),
                        rows,
                    ));
                } else {
                    body = body.child(Self::footnote(
                        theme,
                        "No line in the feed arrives here after another stop.",
                    ));
                }
            }
        }

        let near_dest = here.clone();
        let save_dest = here;
        let rule = trip_ui::rule_text(self.rule.to_rule());
        body = body.child(section(
            theme,
            Some("Or".into()),
            [
                list_row(
                    theme,
                    None,
                    "Wake me near this stop",
                    Some(rule.into()),
                    None,
                )
                .id("stop-near")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.choose_destination(near_dest.clone(), cx);
                }))
                .into_any_element(),
                list_row(theme, None, "Save this stop", None, None)
                    .id("stop-save")
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.save_stop(&save_dest, cx);
                    }))
                    .into_any_element(),
            ],
        ));
        body.child(
            div().flex().justify_end().px(px(16.0)).pt(px(4.0)).child(
                glass_pill(theme, "Close")
                    .id("stop-close")
                    .text_color(colors.secondary_label)
                    .on_click(cx.listener(|this, _, _, cx| this.close_sheet(cx))),
            ),
        )
        .into_any_element()
    }

    /// Keeps a transit stop in Saved, remembering which stop of the feed it is.
    fn save_stop(&mut self, dest: &Destination, cx: &mut Context<Self>) {
        match self.saved.add(&dest.label, dest.coord) {
            Ok(id) => {
                if let Some(place) = self.saved.get_mut(id) {
                    place.gtfs_stop.clone_from(&dest.gtfs_stop);
                }
                self.save_places(cx);
                self.show_pill("Saved.", None, cx);
            }
            Err(rovsti_geo::PlaceError::Duplicate(_)) => {
                self.show_pill("You already saved a place here.", None, cx);
            }
            Err(error) => self.show_pill(error.message(), None, cx),
        }
        cx.notify();
    }

    fn confirm_body(
        &mut self,
        theme: Theme,
        dest: &Destination,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let running = self.trip.is_some();
        let save_row = list_row(
            theme,
            None,
            "Save this place",
            None,
            Some(if self.save_place { "On" } else { "Off" }.into()),
        )
        .id("save-toggle")
        .on_click(cx.listener(|this, _, _, cx| {
            this.save_place = !this.save_place;
            cx.notify();
        }));
        let start = dest.clone();
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, dest.title()))
            .child(Self::footnote(theme, dest.coords_text()))
            .child(trip_ui::rule_chips(self, theme, "confirm-rule", cx))
            .child(section(theme, None, [save_row.into_any_element()]))
            .child(
                div()
                    .flex()
                    .gap(px(12.0))
                    .px(px(16.0))
                    .pt(px(8.0))
                    .child(
                        glass_pill(theme, "Cancel")
                            .id("confirm-cancel")
                            .flex_1()
                            .text_color(colors.secondary_label)
                            .on_click(cx.listener(|this, _, _, cx| this.close_sheet(cx))),
                    )
                    .child(
                        glass_pill(theme, if running { "Replace trip" } else { "Start" })
                            .id("confirm-start")
                            .flex_1()
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                let save = this.save_place;
                                this.sheet.close();
                                this.request_start(start.clone(), save, cx);
                            })),
                    ),
            )
            .into_any_element()
    }

    fn rename_body(
        &mut self,
        theme: Theme,
        id: u64,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, "Rename place"))
            .child(div().px(px(16.0)).child(field_box(
                theme,
                "rename-field",
                &self.field,
                "Name",
                window,
                cx,
            )))
            .child(
                div().flex().justify_end().px(px(16.0)).pt(px(8.0)).child(
                    glass_pill(theme, "Save")
                        .id("rename-save")
                        .text_color(colors.accent)
                        .on_click(cx.listener(move |this, _, _, cx| this.rename_place(id, cx))),
                ),
            )
            .into_any_element()
    }
}

/// Text that was meant as a link or as coordinates, not as a name to search.
fn looks_like_link(text: &str) -> bool {
    let t = text.trim_start().to_ascii_lowercase();
    t.starts_with("geo:")
        || t.contains("://")
        || t.chars()
            .next()
            .is_some_and(|c| c.is_ascii_digit() || c == '-')
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn confirm(text: &str) -> Destination {
        match offer(text) {
            Offer::Confirm(d) => d,
            other => panic!("{text:?} gave {other:?}"),
        }
    }

    #[test]
    fn a_geo_uri_with_a_label_names_the_trip() {
        let d = confirm("geo:41.0370,28.9850?q=41.0370,28.9850(Taksim%20Square)");
        assert_eq!(d.label, "Taksim Square");
        assert_eq!(d.title(), "Wake me near Taksim Square");
        assert!((d.coord.lat - 41.0370).abs() < 1e-9);
        assert_eq!(d.rule, None);
    }

    #[test]
    fn a_pin_without_a_name_is_named_by_its_coordinates() {
        let d = confirm("geo:40.9917,29.0233");
        assert_eq!(d.label, "40.9917, 29.0233");
        assert_eq!(d.coords_text(), "40.9917, 29.0233");
        let d = confirm("https://www.google.com/maps/@41.0082,28.9784,15z");
        assert_eq!(d.label, "41.0082, 28.9784");
    }

    #[test]
    fn a_two_line_share_finds_the_link() {
        let d = confirm("Kadikoy Pier\nhttps://www.openstreetmap.org/?mlat=40.9917&mlon=29.0233");
        assert!((d.coord.lon - 29.0233).abs() < 1e-9);
    }

    #[test]
    fn refusals_carry_the_users_sentence() {
        assert_eq!(
            offer("https://maps.app.goo.gl/abc123"),
            Offer::Refused(
                "This link has no coordinates. Share the pin from your maps app instead."
            )
        );
        assert_eq!(
            offer("geo:95.0,10.0"),
            Offer::Refused("These coordinates are not on Earth. Check the link.")
        );
        assert_eq!(
            offer("see you at six"),
            Offer::Refused("This does not look like a location.")
        );
        assert_eq!(
            offer(""),
            Offer::Refused("This does not look like a location.")
        );
    }

    #[test]
    fn a_saved_place_keeps_its_own_rule() {
        let place = Place {
            id: 3,
            name: "Home".into(),
            coord: Coord::new(41.0, 29.0),
            rule: Some(AlarmRule::Radius { metres: 800.0 }),
            gtfs_stop: None,
        };
        let d = Destination::from_place(&place);
        assert_eq!(d.label, "Home");
        assert_eq!(d.rule, Some(AlarmRule::Radius { metres: 800.0 }));
    }

    #[test]
    fn only_links_and_numbers_earn_an_explanation() {
        assert!(looks_like_link("geo:1,2"));
        assert!(looks_like_link("https://example.com"));
        assert!(looks_like_link("41.0, 28.9"));
        assert!(looks_like_link("-3, 4"));
        assert!(!looks_like_link("home"));
        assert!(!looks_like_link("  kad"));
    }
}
