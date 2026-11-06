//! Choosing where to go: the sheet with a paste field, saved places and recent
//! destinations, and the confirmation that follows a shared location.
//!
//! [`offer`] is the pure core: text from a share, a link or the paste field
//! becomes a destination to confirm, or a sentence that says why not.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ListSizingBehavior, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, Window, div, list, px,
};
use rovsti_geo::{Coord, Diameter, DiameterError, Feed, Place, SavedPlaces, dest::parse_location};
use rovsti_ui::{
    components::{glass_pill, list_row, section},
    field::field_box,
    theme,
    tokens::{Theme, typography},
};

use crate::{
    StopsApp, lists, pick,
    transit::{self, LineChoice, MAX_LINES},
    trip_ui,
};

/// Stops listed for a search.
const MAX_STOP_HITS: usize = 20;

/// Where a trip is headed.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Destination {
    pub label: String,
    pub coord: Coord,
    /// The transit stop this is, when it came from an imported feed.
    #[serde(default)]
    pub gtfs_stop: Option<String>,
    /// The line it was chosen on in the stop sheet, drawn on the map.
    #[serde(default)]
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

    /// This point under the name the user gave a saved place within reach.
    /// Coordinate and line stay; a missing GTFS stop is filled from the place.
    pub fn named_from(mut self, saved: &SavedPlaces) -> Self {
        if let Some(place) = saved.near(self.coord) {
            self.label = place.name.clone();
            if self.gtfs_stop.is_none() {
                self.gtfs_stop = place.gtfs_stop.clone();
            }
        }
        self
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
#[derive(Clone, Debug, Default, PartialEq, serde::Serialize, serde::Deserialize)]
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
    /// A name for a point that is not saved yet; `slot` is the pin it came from.
    SavePoint {
        dest: Destination,
        slot: Option<Slot>,
    },
    /// A transit stop (index into the feed): its lines, Use as A / Use as B.
    Stop(u32),
}

/// The stop search for one query, and the lines of the stop the sheet shows,
/// so typing searches once and not once per frame of the sheet's animation.
/// Reset with the feed.
#[derive(Default)]
pub struct StopHits {
    query: String,
    /// Matching stops with the number of lines through each.
    hits: Vec<(u32, usize)>,
    /// The stop sheet's stop and its lines.
    lines: Option<(u32, Vec<LineChoice>)>,
}

/// Space the sheet keeps around its body: the gap above the panel (56), the
/// margin under it (10), the grabber (30) and the body's bottom padding (12).
/// Mirrors `rovsti_ui::sheet`.
const SHEET_CHROME: f32 = 108.0;

/// One row of the destination search, built once per render of the sheet;
/// the list builds elements only for the rows on screen.
#[derive(Clone, Debug, PartialEq)]
pub enum PickItem {
    /// The pasted link or coordinates, ready to confirm.
    Offer(Destination),
    /// Why the pasted link is no destination.
    Refusal(&'static str),
    Header(&'static str),
    /// A stop of the feed with the number of lines through it.
    Stop {
        index: u32,
        lines: usize,
        at: lists::Place,
    },
    Saved {
        id: u64,
        dest: Destination,
        /// What the place is and how far it is.
        detail: String,
        at: lists::Place,
    },
    Pair {
        i: usize,
        a: Destination,
        b: Destination,
        at: lists::Place,
    },
    Recent {
        i: usize,
        dest: Destination,
        at: lists::Place,
    },
    /// How to get a destination in at all.
    Hint,
    /// Why a typed query found nothing.
    NoMatch(String),
}

impl PickItem {
    /// The list key: it changes whenever the item's height may.
    fn key(&self) -> u64 {
        match self {
            Self::Offer(_) => lists::key(0, 0),
            Self::Refusal(why) => lists::key(1, why.len() as u64),
            Self::Header(text) => lists::key(2, text.len() as u64),
            Self::Stop { index, .. } => lists::key(3, u64::from(*index)),
            Self::Saved { id, .. } => lists::key(4, *id),
            Self::Pair { i, .. } => lists::key(5, *i as u64),
            Self::Recent { i, .. } => lists::key(6, *i as u64),
            Self::Hint => lists::key(7, 0),
            Self::NoMatch(text) => lists::key(8, text.len() as u64),
        }
    }
}

/// `rows` as items under `header`, each knowing its place in the group.
fn group<T>(
    items: &mut Vec<PickItem>,
    header: &'static str,
    rows: Vec<T>,
    item: impl Fn(usize, T, lists::Place) -> PickItem,
) {
    if rows.is_empty() {
        return;
    }
    items.push(PickItem::Header(header));
    let len = rows.len();
    items.extend(
        rows.into_iter()
            .enumerate()
            .map(|(i, row)| item(i, row, lists::Place::of(i, len))),
    );
}

impl StopsApp {
    /// Opens the picker from the Trip tab.
    pub(crate) fn open_picker(&mut self, cx: &mut Context<Self>) {
        self.field.update(cx, |f, cx| f.clear(cx));
        self.pick_list.scroll_to_top();
        self.sheet_mode = SheetMode::Pick;
        self.save_place = false;
        self.sheet.open();
        cx.notify();
    }

    /// Shared text arrived (a link opened with Stops, or a share): confirm it
    /// or say why not.
    pub(crate) fn offer_text(&mut self, text: &str, cx: &mut Context<Self>) {
        match offer(text) {
            Offer::Confirm(dest) => {
                let dest = dest.named_from(&self.saved);
                self.confirm_destination(dest, cx);
            }
            Offer::Refused(why) => self.show_pill(why, None, cx),
        }
    }

    /// Paste button: a location opens the Confirm sheet, other text lands in
    /// the field. The clipboard is read on the tap only (Android shows a
    /// notice on every read).
    fn paste_into_picker(&mut self, cx: &mut Context<Self>) {
        let clip = cx.read_from_clipboard().and_then(|item| item.text());
        let Some(text) = pick::clean_paste(clip.as_deref()) else {
            self.show_pill("Nothing to paste", None, cx);
            return;
        };
        if let Offer::Confirm(dest) = offer(&text) {
            let dest = dest.named_from(&self.saved);
            self.confirm_destination(dest, cx);
        } else {
            self.field.update(cx, |f, cx| f.set(&text, cx));
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
        if matches!(self.sheet_mode, SheetMode::Stop(_)) {
            // The line previewed on the stop sheet leaves the map again.
            self.map.pattern = self
                .pick
                .ends
                .get(self.pick.sheet_slot())
                .and_then(|d| d.pattern);
        }
        self.field.update(cx, |f, cx| f.set(&name, cx));
        self.sheet_mode = SheetMode::Rename(id);
        self.sheet.open();
        cx.notify();
    }

    /// Opens the Save sheet for `dest` with its name in the field.
    pub(crate) fn open_save_point(
        &mut self,
        dest: Destination,
        slot: Option<Slot>,
        cx: &mut Context<Self>,
    ) {
        if matches!(self.sheet_mode, SheetMode::Stop(_)) {
            // The line previewed on the stop sheet leaves the map again.
            self.map.pattern = self
                .pick
                .ends
                .get(self.pick.sheet_slot())
                .and_then(|d| d.pattern);
        }
        let name = pick::save_prefill(&dest);
        self.field.update(cx, |f, cx| f.set(&name, cx));
        self.sheet_mode = SheetMode::SavePoint { dest, slot };
        self.sheet.open();
        cx.notify();
    }

    /// Saves the point of the Save sheet under the name in the field.
    fn commit_save_point(&mut self, cx: &mut Context<Self>) {
        let SheetMode::SavePoint { dest, .. } = self.sheet_mode.clone() else {
            return;
        };
        let name = self.field.read(cx).text().to_owned();
        if self.save_named(&name, &dest, cx) {
            self.sheet.close();
        }
        cx.notify();
    }

    /// Saves `dest` as `name` and shows the pill. True when the place is now
    /// in Saved (new or already there); false when the name was refused.
    fn save_named(&mut self, name: &str, dest: &Destination, cx: &mut Context<Self>) -> bool {
        let result = self.saved.save(name, dest.coord, dest.gtfs_stop.clone());
        let text = pick::save_result_text(&result, &self.saved);
        let id = match result {
            Ok(id) => {
                self.save_places(cx);
                Some(id)
            }
            Err(rovsti_geo::PlaceError::Duplicate(id)) => Some(id),
            Err(_) => None,
        };
        if let Some(id) = id
            && let Some(place) = self.saved.get(id)
        {
            let (coord, label) = (place.coord, place.name.clone());
            self.pick.relabel_near(coord, &label);
        }
        self.show_pill(text, None, cx);
        id.is_some()
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
            let name = self.field.read(cx).text().to_owned();
            if !self.save_named(&name, &dest, cx) {
                // The sheet stays open so the name can be fixed.
                cx.notify();
                return;
            }
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

    fn rename_place(&mut self, id: u64, cx: &mut Context<Self>) {
        let name = self.field.read(cx).text().to_owned();
        let coord = self.saved.get(id).map(|p| p.coord);
        match self.saved.rename(id, &name) {
            Ok(()) => {
                let new_name = self.saved.get(id).map_or(name, |p| p.name.clone());
                if let Some(coord) = coord {
                    self.pick.relabel_near(coord, &new_name);
                }
                self.save_places(cx);
                self.sheet.close();
                self.show_pill(format!("Renamed to {new_name}"), None, cx);
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
        let want_back = self.can_go_back();
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
            SheetMode::Confirm(dest) => (
                "Use this point",
                self.confirm_body(theme, &dest, window, cx),
            ),
            SheetMode::Rename(id) => ("Rename place", self.rename_body(theme, id, window, cx)),
            SheetMode::SavePoint { dest, .. } => {
                ("Save place", self.save_point_body(theme, &dest, window, cx))
            }
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

    /// The destination search: the title and the field stay put, the results
    /// below are a virtualised list that scrolls inside the sheet.
    fn pick_body(&mut self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let query = self.field.read(cx).text().trim().to_owned();
        self.pick_items = self.pick_rows(&query);
        let keys = self.pick_items.iter().map(PickItem::key).collect();
        let state = self.pick_list.sync(keys);

        let insets = window.insets();
        let keyboard = f32::from((insets.ime.bottom - insets.consumed.bottom).max(px(0.0)));
        let body_max =
            (f32::from(window.viewport_size().height) - SHEET_CHROME - keyboard).max(160.0);
        let header = div()
            .flex_none()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .pb(px(8.0))
            .child(Self::sheet_title(
                theme,
                self.pick.sheet_slot().picker_title(),
            ))
            .child(
                div()
                    .px(px(16.0))
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .child(div().flex_1().child(field_box(
                        theme,
                        "dest-field",
                        &self.field,
                        "Search stops and saved places, or paste a link",
                        window,
                        cx,
                    )))
                    .child(
                        glass_pill(theme, "Paste")
                            .id("dest-paste")
                            .text_color(theme::colors(theme).accent)
                            .on_click(cx.listener(|this, _, _, cx| this.paste_into_picker(cx))),
                    ),
            );
        // The list is as tall as its rows up to what the sheet can hold; past
        // that it scrolls, building only the rows on screen.
        let results = list(
            state,
            cx.processor(|this, ix: usize, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                match this.pick_items.get(ix).cloned() {
                    Some(item) => this.pick_item(theme, item, cx),
                    None => div().into_any_element(),
                }
            }),
        )
        .with_sizing_behavior(ListSizingBehavior::Infer)
        .w_full()
        .min_h_0()
        .flex_shrink_1();
        div()
            .flex()
            .flex_col()
            .max_h(px(body_max))
            .child(header)
            .child(results)
            .into_any_element()
    }

    /// The rows for `query`: the pasted point, stops, saved places and, with
    /// no query, the recent trips and points.
    fn pick_rows(&mut self, query: &str) -> Vec<PickItem> {
        let mut items = Vec::new();
        if !query.is_empty() {
            match offer(query) {
                Offer::Confirm(dest) => {
                    items.push(PickItem::Offer(dest.named_from(&self.saved)));
                }
                // Typing a name searches Saved below: only a link or a pair of
                // numbers that failed is worth an explanation.
                Offer::Refused(why) if looks_like_link(query) => {
                    items.push(PickItem::Refusal(why));
                }
                Offer::Refused(_) => {}
            }
        }
        let stops = self.stop_search(query).to_vec();
        group(&mut items, "Stops", stops, |_, (index, lines), at| {
            PickItem::Stop { index, lines, at }
        });
        let here = self.map.position.map(|p| p.0);
        let saved: Vec<_> = self
            .saved
            .search(query)
            .into_iter()
            .map(|p| {
                (
                    p.id,
                    Destination::from_place(p),
                    crate::saved_ui::place_detail(p, here),
                )
            })
            .collect();
        group(&mut items, "Saved", saved, |_, (id, dest, detail), at| {
            PickItem::Saved {
                id,
                dest,
                detail,
                at,
            }
        });
        if !query.is_empty() && items.is_empty() {
            items.push(PickItem::NoMatch(pick::no_match_text(
                query,
                self.gtfs.is_some(),
            )));
        }
        if query.is_empty() {
            let pairs = self.history.recent_pairs(3);
            let saved = &self.saved;
            group(&mut items, "Recent trips", pairs, |i, (a, b), at| {
                PickItem::Pair {
                    i,
                    a: pick::dest_from_endpoint(&a, saved),
                    b: pick::dest_from_endpoint(&b, saved),
                    at,
                }
            });
            let recent = self.history.recent_points(5);
            let saved = &self.saved;
            group(&mut items, "Recent", recent, |i, point, at| {
                PickItem::Recent {
                    i,
                    dest: pick::dest_from_endpoint(&point, saved),
                    at,
                }
            });
            if self.saved.places.is_empty() && self.history.records.is_empty() {
                items.push(PickItem::Hint);
            }
        }
        items
    }

    /// One row of the destination search.
    fn pick_item(&mut self, theme: Theme, item: PickItem, cx: &mut Context<Self>) -> AnyElement {
        match item {
            PickItem::Offer(dest) => {
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
                lists::grouped(theme, lists::Place::ALONE, row).into_any_element()
            }
            PickItem::Refusal(why) => Self::footnote(theme, why),
            PickItem::NoMatch(text) => Self::footnote(theme, text),
            PickItem::Header(text) => lists::section_header(theme, text, 8.0),
            PickItem::Stop { index, lines, at } => {
                let Some(stop) = self
                    .gtfs
                    .as_ref()
                    .and_then(|feed| feed.stops.get(index as usize))
                else {
                    return div().into_any_element();
                };
                let mut detail = match lines {
                    0 => "No lines".to_owned(),
                    1 => "1 line".to_owned(),
                    n => format!("{n} lines"),
                };
                if let Some((here, _)) = self.map.position {
                    detail = format!(
                        "{}, {detail}",
                        trip_ui::distance_text(here.distance_m(stop.coord))
                    );
                }
                let row = list_row(theme, None, stop.name.clone(), Some(detail.into()), None)
                    .id(("pick-stop", index as usize))
                    .on_click(cx.listener(move |this, _, _, cx| this.open_stop(index, cx)));
                lists::grouped(theme, at, row).into_any_element()
            }
            PickItem::Saved {
                id,
                dest,
                detail,
                at,
            } => {
                let row = list_row(
                    theme,
                    None,
                    pick::row_label(&dest.label),
                    Some(detail.into()),
                    None,
                )
                .id(("pick-saved", id as usize))
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.choose_destination(dest.clone(), cx);
                }));
                lists::grouped(theme, at, row).into_any_element()
            }
            PickItem::Pair { i, a, b, at } => {
                let title = format!(
                    "{} – {}",
                    pick::row_label(&a.label),
                    pick::row_label(&b.label)
                );
                let row = list_row(theme, None, title, None, None)
                    .id(("pick-pair", i))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.choose_pair(a.clone(), b.clone(), cx);
                    }));
                lists::grouped(theme, at, row).into_any_element()
            }
            PickItem::Recent { i, dest, at } => {
                let row = list_row(theme, None, pick::row_label(&dest.label), None, None)
                    .id(("pick-recent", i))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.choose_destination(dest.clone(), cx);
                    }));
                lists::grouped(theme, at, row).into_any_element()
            }
            PickItem::Hint => Self::footnote(
                theme,
                "Share a location from any maps app to Rovsti Stops, or paste its link here.",
            ),
        }
    }

    /// The stops whose name matches `query`, nearest the last position first,
    /// with their line counts. Searched once per query. Empty without a feed
    /// or a query.
    fn stop_search(&mut self, query: &str) -> &[(u32, usize)] {
        let Some(feed) = self.gtfs.clone() else {
            return &[];
        };
        if query.is_empty() {
            return &[];
        }
        if self.stop_hits.query != query {
            let near = self.map.position.map(|p| p.0);
            self.stop_hits.query = query.to_owned();
            self.stop_hits.hits = feed
                .search(query, near, MAX_STOP_HITS)
                .into_iter()
                .map(|index| (index, transit::lines_through(&feed, index, MAX_LINES).len()))
                .collect();
        }
        &self.stop_hits.hits
    }

    /// The lines through `stop`, worked out once per stop the sheet opens on.
    fn stop_lines(&mut self, feed: &Feed, stop: u32) -> Vec<LineChoice> {
        match &self.stop_hits.lines {
            Some((at, lines)) if *at == stop => lines.clone(),
            _ => {
                let lines = transit::lines_through(feed, stop, MAX_LINES);
                self.stop_hits.lines = Some((stop, lines.clone()));
                lines
            }
        }
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
        let lines = self.stop_lines(&feed, stop);
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
        let already = self.saved.near(coord).map(|p| (p.id, p.name.clone()));
        let save_row = match already {
            Some((id, name)) => list_row(
                theme,
                None,
                format!("Saved as {name}"),
                None,
                Some("Rename".into()),
            )
            .id("stop-rename")
            .on_click(cx.listener(move |this, _, _, cx| this.open_rename(id, cx))),
            None => list_row(theme, None, "Save this stop", None, None)
                .id("stop-save")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.open_save_point(save_dest.clone(), None, cx);
                })),
        };
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
                save_row.into_any_element(),
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

    fn confirm_body(
        &mut self,
        theme: Theme,
        dest: &Destination,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let prefill = dest.clone();
        let already = self.saved.near(dest.coord).map(|p| (p.id, p.name.clone()));
        let is_saved = already.is_some();
        let save_row = match already {
            Some((id, name)) => list_row(
                theme,
                None,
                format!("Saved as {name}"),
                None,
                Some("Rename".into()),
            )
            .id("save-rename")
            .on_click(cx.listener(move |this, _, _, cx| this.open_rename(id, cx))),
            None => list_row(
                theme,
                None,
                "Save this place",
                None,
                Some(if self.save_place { "On" } else { "Off" }.into()),
            )
            .id("save-toggle")
            .on_click(cx.listener(move |this, _, _, cx| {
                this.save_place = !this.save_place;
                if this.save_place {
                    let name = pick::save_prefill(&prefill);
                    this.field.update(cx, |f, cx| f.set(&name, cx));
                }
                cx.notify();
            })),
        };
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
            .children((self.save_place && !is_saved).then(|| {
                div().px(px(16.0)).child(field_box(
                    theme,
                    "confirm-save-field",
                    &self.field,
                    "Name",
                    window,
                    cx,
                ))
            }))
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
                                let save = this.save_place && !is_saved;
                                this.use_as(Slot::A, as_a.clone(), save, cx);
                            })),
                    )
                    .child(
                        pill("Use as B", Slot::B)
                            .id("confirm-use-b")
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                let save = this.save_place && !is_saved;
                                this.use_as(Slot::B, as_b.clone(), save, cx);
                            })),
                    ),
            )
            .into_any_element()
    }

    fn save_point_body(
        &mut self,
        theme: Theme,
        dest: &Destination,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, "Save place"))
            .child(Self::footnote(theme, pick::details_text(dest)))
            .child(div().px(px(16.0)).child(field_box(
                theme,
                "save-field",
                &self.field,
                "Name",
                window,
                cx,
            )))
            .child(
                div()
                    .flex()
                    .gap(px(12.0))
                    .px(px(16.0))
                    .pt(px(8.0))
                    .child(
                        glass_pill(theme, "Cancel")
                            .id("save-cancel")
                            .flex_1()
                            .text_color(colors.secondary_label)
                            .on_click(cx.listener(|this, _, _, cx| this.close_sheet(cx))),
                    )
                    .child(
                        glass_pill(theme, "Save")
                            .id("save-commit")
                            .flex_1()
                            .text_color(colors.accent)
                            .on_click(cx.listener(|this, _, _, cx| this.commit_save_point(cx))),
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
                div()
                    .flex()
                    .justify_end()
                    .gap(px(10.0))
                    .px(px(16.0))
                    .pt(px(8.0))
                    .child(
                        glass_pill(theme, "Cancel")
                            .id("rename-cancel")
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.sheet.close();
                                cx.notify();
                            })),
                    )
                    .child(
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

    fn saved_home() -> SavedPlaces {
        let mut s = SavedPlaces::default();
        s.save("Home", Coord::new(41.0, 29.0), Some("H1".into()))
            .unwrap();
        s
    }

    #[test]
    fn a_raw_point_near_a_saved_place_takes_its_name() {
        let d = Destination::dropped(Coord::new(41.0, 29.0002)).named_from(&saved_home());
        assert_eq!(d.label, "Home");
        assert_eq!(d.coord, Coord::new(41.0, 29.0002));
        assert_eq!(d.gtfs_stop.as_deref(), Some("H1"));
    }

    #[test]
    fn a_stop_near_a_saved_place_takes_the_user_name_and_keeps_its_id() {
        let stop = Destination {
            gtfs_stop: Some("S9".into()),
            pattern: Some(3),
            ..Destination::new("Kadikoy Iskele", Coord::new(41.0, 29.0001))
        };
        let d = stop.named_from(&saved_home());
        assert_eq!(d.label, "Home");
        assert_eq!(d.gtfs_stop.as_deref(), Some("S9"));
        assert_eq!(d.pattern, Some(3));
    }

    #[test]
    fn a_far_point_stays_unnamed() {
        let d = Destination::dropped(Coord::new(41.0, 29.01)).named_from(&saved_home());
        assert!(!d.is_named());
        assert_eq!(d.gtfs_stop, None);
    }

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
    fn a_group_gets_a_header_and_rounded_ends() {
        let mut items = Vec::new();
        group(&mut items, "Saved", Vec::<u64>::new(), |_, id, at| {
            PickItem::Saved {
                id,
                dest: dest("x", 29.0),
                detail: String::new(),
                at,
            }
        });
        assert!(items.is_empty(), "no rows, no header");
        group(&mut items, "Saved", vec![1, 2, 3], |_, id, at| {
            PickItem::Saved {
                id,
                dest: dest("x", 29.0),
                detail: String::new(),
                at,
            }
        });
        assert_eq!(items.len(), 4);
        assert_eq!(items[0], PickItem::Header("Saved"));
        let places: Vec<_> = items[1..]
            .iter()
            .map(|item| match item {
                PickItem::Saved { at, .. } => (at.first, at.last),
                other => panic!("{other:?}"),
            })
            .collect();
        assert_eq!(places, [(true, false), (false, false), (false, true)]);
        // Rows of different kinds never share a key.
        let keys: std::collections::HashSet<_> = items.iter().map(PickItem::key).collect();
        assert_eq!(keys.len(), 4);
        assert_ne!(
            PickItem::Stop {
                index: 1,
                lines: 0,
                at: lists::Place::ALONE
            }
            .key(),
            PickItem::Saved {
                id: 1,
                dest: dest("x", 29.0),
                detail: String::new(),
                at: lists::Place::ALONE
            }
            .key()
        );
    }

    #[test]
    fn no_match_key_differs_from_hint_and_refusal() {
        let k = PickItem::NoMatch("abc".into()).key();
        assert_ne!(k, PickItem::Hint.key());
        assert_ne!(k, PickItem::Refusal("abc").key());
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

    #[test]
    fn a_saved_pin_takes_the_name_and_is_found_again() {
        let mut saved = SavedPlaces::default();
        let coord = Coord::new(41.0, 29.0);
        let dest = Destination::new("Stop X", coord);
        let name = pick::save_prefill(&dest);
        let id = saved.save(&name, coord, None).unwrap();
        assert_eq!(saved.near(coord).map(|p| p.id), Some(id));
        let again = Destination::dropped(coord).named_from(&saved);
        assert_eq!(again.label, "Stop X");
        assert!(matches!(
            saved.save("Other", coord, None),
            Err(rovsti_geo::PlaceError::Duplicate(d)) if d == id
        ));
    }
}
