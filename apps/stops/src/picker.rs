//! Choosing where to go: the sheet with a paste field, saved places and recent
//! destinations, and the confirmation that follows a shared location.
//!
//! [`offer`] is the pure core: text from a share, a link or the paste field
//! becomes a destination to confirm, or a sentence that says why not.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_geo::{Coord, Diameter, DiameterError, Place, dest::parse_location};
use rovsti_ui::{
    components::{glass_pill, list_row, section},
    field::field_box,
    theme,
    tokens::{Theme, typography},
};

use crate::{
    StopsApp, pick,
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
    /// The transit stop this is, when it came from an imported feed.
    pub gtfs_stop: Option<String>,
    /// The line it was chosen on in the stop sheet, drawn on the map.
    pub pattern: Option<u32>,
}

impl Destination {
    pub fn from_place(place: &Place) -> Self {
        Self {
            label: place.name.clone(),
            coord: place.coord,
            gtfs_stop: place.gtfs_stop.clone(),
            pattern: None,
        }
    }

    pub fn new(label: impl Into<String>, coord: Coord) -> Self {
        Self {
            label: label.into(),
            coord,
            gtfs_stop: None,
            pattern: None,
        }
    }

    /// A raw point dropped on the map: it has no name.
    pub fn dropped(coord: Coord) -> Self {
        Self::new(String::new(), coord)
    }

    /// Whether the point has a name worth showing.
    pub fn is_named(&self) -> bool {
        !self.label.trim().is_empty()
    }

    /// The confirmation sheet's heading.
    pub fn title(&self) -> String {
        if self.is_named() {
            format!("Use {}", self.label)
        } else {
            "Use this point".to_owned()
        }
    }
}

/// Which end of the diameter a point goes to.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Slot {
    #[default]
    A,
    B,
}

impl Slot {
    pub fn label(self) -> &'static str {
        match self {
            Self::A => "A",
            Self::B => "B",
        }
    }

    /// The picker sheet's heading while this end is being chosen.
    pub fn picker_title(self) -> &'static str {
        match self {
            Self::A => "Choose A",
            Self::B => "Choose B",
        }
    }
}

/// The two points chosen so far.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Ends {
    pub a: Option<Destination>,
    pub b: Option<Destination>,
}

impl Ends {
    pub fn get(&self, slot: Slot) -> Option<&Destination> {
        match slot {
            Slot::A => self.a.as_ref(),
            Slot::B => self.b.as_ref(),
        }
    }

    pub fn set(&mut self, slot: Slot, dest: Destination) {
        match slot {
            Slot::A => self.a = Some(dest),
            Slot::B => self.b = Some(dest),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.a.is_none() && self.b.is_none()
    }

    /// Where the next picked point goes: A until it is set, then B.
    pub fn next_slot(&self) -> Slot {
        if self.a.is_none() { Slot::A } else { Slot::B }
    }

    /// Fills both ends at once (a recent trip).
    pub fn set_pair(&mut self, a: Destination, b: Destination) {
        self.a = Some(a);
        self.b = Some(b);
    }

    /// Exchanges A and B (the left and right sides trade places).
    pub fn swap(&mut self) {
        std::mem::swap(&mut self.a, &mut self.b);
    }

    /// The circle on AB. `None` until both points are set; `Some(Err)` when
    /// they cannot form a diameter.
    pub fn diameter(&self) -> Option<Result<Diameter, DiameterError>> {
        let (a, b) = (self.a.as_ref()?, self.b.as_ref()?);
        Some(Diameter::new(a.coord, b.coord))
    }
}

/// `41.0370, 28.9850`: for a details line only, never a name or a main-flow label.
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
/// coordinates). Nothing is fetched. A pin without a name stays unnamed.
pub fn offer(text: &str) -> Offer {
    match parse_location(text) {
        Ok(place) => {
            let label = place
                .label
                .map(|l| l.trim().to_owned())
                .filter(|l| !l.is_empty())
                .unwrap_or_default();
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
    /// "Use ...": Use as A / Use as B, Save toggle.
    Confirm(Destination),
    /// A new name for a saved place.
    Rename(u64),
    /// A transit stop (index into the feed): its lines, Use as A / Use as B.
    Stop(u32),
}

/// The stop search for one query, so typing searches once and not once per
/// frame of the sheet's animation.
#[derive(Default)]
pub struct StopHits {
    query: String,
    hits: Vec<u32>,
}

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
            self.map.pattern = self
                .pick
                .ends
                .get(self.pick.sheet_slot())
                .and_then(|d| d.pattern);
        }
        self.sheet.close();
        cx.notify();
    }

    /// A saved or recent point was tapped: it fills the active slot, and the
    /// next pick goes to the other end.
    pub(crate) fn choose_destination(&mut self, dest: Destination, cx: &mut Context<Self>) {
        let slot = self.pick.sheet_slot();
        self.use_as(slot, dest, false, cx);
    }

    /// Puts `dest` into `slot`, optionally keeping it in Saved too.
    pub(crate) fn use_as(
        &mut self,
        slot: Slot,
        dest: Destination,
        save: bool,
        cx: &mut Context<Self>,
    ) {
        if save {
            self.save_point(&dest, cx);
        }
        self.map.pattern = dest.pattern;
        self.map.follow = true;
        self.pick.place(slot, dest);
        self.sheet.close();
        cx.notify();
    }

    /// Both ends at once, from a recent trip.
    pub(crate) fn choose_pair(&mut self, a: Destination, b: Destination, cx: &mut Context<Self>) {
        self.pick.set_pair(a, b);
        self.map.pattern = None;
        self.map.follow = true;
        self.sheet.close();
        cx.notify();
    }

    /// Keeps a point in Saved; a duplicate is fine.
    fn save_point(&mut self, dest: &Destination, cx: &mut Context<Self>) {
        match self.saved.add(&dest.label, dest.coord) {
            Ok(id) => {
                if let Some(place) = self.saved.get_mut(id) {
                    place.gtfs_stop.clone_from(&dest.gtfs_stop);
                }
                self.save_places(cx);
            }
            Err(rovsti_geo::PlaceError::Duplicate(_)) => {}
            Err(error) => self.show_pill(error.message(), None, cx),
        }
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
            SheetMode::Pick => (
                self.pick.sheet_slot().picker_title(),
                self.pick_body(theme, window, cx),
            ),
            SheetMode::Confirm(dest) => ("Use this point", self.confirm_body(theme, &dest, cx)),
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
            .child(Self::sheet_title(
                theme,
                self.pick.sheet_slot().picker_title(),
            ))
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
                        Some(pick::details_text(&dest).into()),
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
                list_row(theme, None, pick::row_label(&dest.label), None, None)
                    .id(("pick-saved", id as usize))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.choose_destination(dest.clone(), cx);
                    }))
                    .into_any_element()
            });
            body = body.child(section(theme, Some("Saved".into()), rows));
        }
        if query.is_empty() {
            let pairs = self.history.recent_pairs(3);
            if !pairs.is_empty() {
                let rows = pairs.into_iter().enumerate().map(|(i, (a, b))| {
                    let title = format!(
                        "{} – {}",
                        pick::row_label(&a.label),
                        pick::row_label(&b.label)
                    );
                    let (a, b) = (
                        Destination::new(a.label, a.coord),
                        Destination::new(b.label, b.coord),
                    );
                    list_row(theme, None, title, None, None)
                        .id(("pick-pair", i))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.choose_pair(a.clone(), b.clone(), cx);
                        }))
                        .into_any_element()
                });
                body = body.child(section(theme, Some("Recent trips".into()), rows));
            }
            let recent = self.history.recent_points(5);
            if !recent.is_empty() {
                let rows = recent.into_iter().enumerate().map(|(i, point)| {
                    let dest = Destination::new(point.label, point.coord);
                    list_row(theme, None, pick::row_label(&dest.label), None, None)
                        .id(("pick-recent", i))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.choose_destination(dest.clone(), cx);
                        }))
                        .into_any_element()
                });
                body = body.child(section(theme, Some("Recent".into()), rows));
            }
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

    /// A stop of the feed: a line to draw, Use as A / Use as B, Save.
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
            .child(Self::footnote(theme, pick::details_text(&here)));

        let chosen = self.stop_pattern;
        let lines = transit::lines_through(&feed, stop, MAX_LINES);
        match chosen.and_then(|p| lines.iter().find(|l| l.pattern == p)) {
            Some(line) => {
                let title = line_title(&line.name, &line.headsign);
                body = body.child(section(
                    theme,
                    Some("Drawn on the map".into()),
                    [
                        list_row(theme, None, title, None, None)
                            .id("stop-chosen-line")
                            .into_any_element(),
                        list_row(theme, None, "Choose another line", None, None)
                            .id("stop-other-line")
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.stop_pattern = None;
                                this.map.pattern = None;
                                cx.notify();
                            }))
                            .into_any_element(),
                    ],
                ));
            }
            None if !lines.is_empty() => {
                let rows = lines.into_iter().map(|line| {
                    let pattern = line.pattern;
                    let title = line_title(&line.name, &line.headsign);
                    let before = if line.before == 1 {
                        "1 stop earlier on this line".to_owned()
                    } else {
                        format!("{} stops earlier on this line", line.before)
                    };
                    list_row(theme, None, title, Some(before.into()), None)
                        .id(("stop-line", pattern as usize))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.stop_pattern = Some(pattern);
                            this.map.pattern = Some(pattern);
                            cx.notify();
                        }))
                        .into_any_element()
                });
                body = body.child(section(theme, Some("Show a line on the map".into()), rows));
            }
            None => {}
        }

        let a_dest = Destination {
            pattern: self.stop_pattern,
            ..here.clone()
        };
        let b_dest = a_dest.clone();
        let save_dest = here;
        body = body.child(section(
            theme,
            Some("This stop".into()),
            [
                list_row(theme, None, "Use as A", None, None)
                    .id("stop-use-a")
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.use_as(Slot::A, a_dest.clone(), false, cx);
                    }))
                    .into_any_element(),
                list_row(theme, None, "Use as B", None, None)
                    .id("stop-use-b")
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.use_as(Slot::B, b_dest.clone(), false, cx);
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
        let as_a = dest.clone();
        let as_b = dest.clone();
        let default = self.pick.confirm_slot();
        let pill = |label: &'static str, slot: Slot| {
            let p = glass_pill(theme, label);
            if slot == default {
                p.text_color(colors.accent)
            } else {
                p
            }
        };
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, dest.title()))
            .child(Self::footnote(theme, pick::details_text(dest)))
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
                        pill("Use as A", Slot::A)
                            .id("confirm-use-a")
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                let save = this.save_place;
                                this.use_as(Slot::A, as_a.clone(), save, cx);
                            })),
                    )
                    .child(
                        pill("Use as B", Slot::B)
                            .id("confirm-use-b")
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                let save = this.save_place;
                                this.use_as(Slot::B, as_b.clone(), save, cx);
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

/// "Line 34 to Kadikoy", or "Line 34" without a headsign.
fn line_title(name: &str, headsign: &str) -> String {
    if headsign.is_empty() {
        format!("Line {name}")
    } else {
        format!("Line {name} to {headsign}")
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
        assert_eq!(d.title(), "Use Taksim Square");
        assert!((d.coord.lat - 41.0370).abs() < 1e-9);
    }

    #[test]
    fn a_pin_without_a_name_stays_unnamed() {
        let d = confirm("geo:40.9917,29.0233");
        assert!(!d.is_named());
        assert_eq!(d.title(), "Use this point");
        assert_eq!(coord_text(d.coord), "40.9917, 29.0233");
        let d = confirm("https://www.google.com/maps/@41.0082,28.9784,15z");
        assert!(d.label.is_empty());
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

    fn dest(label: &str, lon: f64) -> Destination {
        Destination::new(label, Coord::new(41.0, lon))
    }

    #[test]
    fn a_saved_place_becomes_a_point() {
        let place = Place {
            id: 3,
            name: "Home".into(),
            coord: Coord::new(41.0, 29.0),
            gtfs_stop: Some("S1".into()),
        };
        let d = Destination::from_place(&place);
        assert_eq!(d.label, "Home");
        assert_eq!(d.gtfs_stop.as_deref(), Some("S1"));
        assert_eq!(d.coord, place.coord);
    }

    #[test]
    fn the_next_slot_is_a_until_it_is_set_then_b() {
        let mut ends = Ends::default();
        assert!(ends.is_empty());
        assert_eq!(ends.next_slot(), Slot::A);
        ends.set(Slot::A, dest("A", 28.99));
        assert_eq!(ends.next_slot(), Slot::B);
        ends.set(Slot::B, dest("B", 29.01));
        assert_eq!(ends.next_slot(), Slot::B);
        // B picked first leaves A for next.
        let mut ends = Ends::default();
        ends.set(Slot::B, dest("B", 29.01));
        assert_eq!(ends.next_slot(), Slot::A);
        assert_eq!(Slot::B.label(), "B");
    }

    #[test]
    fn set_replaces_one_end() {
        let mut ends = Ends::default();
        ends.set(Slot::A, dest("One", 28.99));
        ends.set(Slot::A, dest("Two", 28.98));
        assert_eq!(ends.get(Slot::A).unwrap().label, "Two");
        assert!(ends.get(Slot::B).is_none());
    }

    #[test]
    fn swap_exchanges_the_ends() {
        let mut ends = Ends::default();
        ends.set(Slot::A, dest("A", 28.99));
        ends.swap();
        assert!(ends.a.is_none());
        assert_eq!(ends.b.as_ref().unwrap().label, "A");
        ends.set(Slot::A, dest("B", 29.01));
        ends.swap();
        assert_eq!(ends.a.as_ref().unwrap().label, "A");
        assert_eq!(ends.b.as_ref().unwrap().label, "B");
    }

    #[test]
    fn the_picker_is_titled_by_the_slot() {
        assert_eq!(Slot::A.picker_title(), "Choose A");
        assert_eq!(Slot::B.picker_title(), "Choose B");
    }

    #[test]
    fn a_recent_pair_fills_both_ends() {
        let mut ends = Ends::default();
        ends.set(Slot::A, dest("Old", 28.90));
        ends.set_pair(dest("A", 28.99), dest("B", 29.01));
        assert_eq!(ends.get(Slot::A).unwrap().label, "A");
        assert_eq!(ends.get(Slot::B).unwrap().label, "B");
        assert_eq!(ends.next_slot(), Slot::B);
        assert!(matches!(ends.diameter(), Some(Ok(_))));
        ends.swap();
        assert_eq!(ends.get(Slot::A).unwrap().label, "B");
    }

    #[test]
    fn the_diameter_needs_two_valid_points() {
        let mut ends = Ends::default();
        assert!(ends.diameter().is_none());
        ends.set(Slot::A, dest("A", 28.99));
        assert!(ends.diameter().is_none());
        ends.set(Slot::B, dest("B", 28.99));
        assert_eq!(ends.diameter(), Some(Err(DiameterError::SamePoint)));
        ends.set(Slot::B, dest("B", 29.01));
        let d = ends.diameter().unwrap().unwrap();
        assert!((d.length_m() - 1_680.0).abs() < 30.0, "{}", d.length_m());
        // Swapping keeps the circle and flips the direction.
        ends.swap();
        let swapped = ends.diameter().unwrap().unwrap();
        assert_eq!(swapped, d.swapped());
    }

    #[test]
    fn line_titles_drop_an_empty_headsign() {
        assert_eq!(line_title("34", "Kadikoy"), "Line 34 to Kadikoy");
        assert_eq!(line_title("34", ""), "Line 34");
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
