//! The Saved tab: stops (a gate of two points) kept for one-tap trips. Tapping a
//! row opens the stop's sheet; the row's "..." button (or a long press) opens a
//! popover menu (Show on map, Open in maps, Move, Delete) and nothing is ever
//! inserted into the list. The header's "..." menu holds Copy list, the exports
//! and Import. A delete can be undone from the pill. `share_point` opens the system share sheet (`telecom::share_text`)
//! and falls back to the clipboard when there is no sheet (desktop, or no app
//! answered).

use crate::pick::dest_from_endpoint;
use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, Window, div, list, px, svg,
};
use rovsti_android::Error as AndroidError;
use rovsti_core::wire::DocumentResult;
use rovsti_geo::{
    Coord, SavedStop, SavedStops, StopColour,
    backup::{self, Backup},
    places_io::{self, MergePlan},
    saved_stops::{self, SavedSort},
};
use rovsti_ui::{
    Icon,
    button::{Button, ButtonSize, ButtonStyle},
    components::{empty_state, glass_pill, list_row, section},
    field::field_box,
    layout,
    menu::{
        AnchorSlot, Menu,
        model::{MenuGroup, MenuItem, MenuModel},
    },
    page::{HeaderAction, PageHeader},
    theme,
    tokens::Theme,
};

use crate::{Page, StopsApp, backend, lists, picker::SheetMode, qr_sheet, trip_ui::distance_text};

/// The search field shows when there are more stops than this.
pub const SEARCH_ABOVE: usize = 5;

/// Imported stops listed on the confirm sheet before "and N more".
const PREVIEW_ROWS: usize = 5;

/// Which export the system save dialog is for.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ExportKind {
    #[default]
    SavedStops,
    History,
    Backup,
}

/// Which import the file picker is for.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ImportKind {
    #[default]
    Places,
    Backup,
}

/// The pill when Restore is tapped during a trip, else `None`.
pub fn restore_blocked(trip_running: bool) -> Option<&'static str> {
    trip_running.then_some("Not while a trip is running")
}

/// The restore sheet's text: what the file holds and what restoring does.
pub fn restore_sheet_body(backup: &Backup, tz_offset_s: i32) -> String {
    format!(
        "{}.\n\nThis replaces your saved stops, trip history and settings.",
        backup::summary(backup, tz_offset_s)
    )
}

/// The pill for the answer of a save dialog: `None` when it was cancelled.
pub fn export_pill(kind: ExportKind, ok: bool, error: bool) -> Option<String> {
    if ok {
        Some(
            match kind {
                ExportKind::SavedStops => "Saved stops exported",
                ExportKind::History => "History exported",
                ExportKind::Backup => "Backup saved",
            }
            .to_owned(),
        )
    } else {
        error.then(|| "Can't export: the file was not saved".to_owned())
    }
}

/// Whether History shows its search field for `count` trips.
pub fn history_field_visible(count: usize) -> bool {
    count > SEARCH_ABOVE
}

/// Whether the Saved tab shows its search field for `count` stops.
pub fn show_search(count: usize) -> bool {
    count > SEARCH_ABOVE
}

/// Whether the sort pill is worth showing for `count` stops.
pub fn show_sort(count: usize) -> bool {
    count > 1
}

/// The ids to list, in order: the stops matching `query` (ignored while the
/// search field is hidden) sorted by `sort`, `here` being your position.
pub fn visible_stops(
    saved: &SavedStops,
    query: &str,
    sort: SavedSort,
    here: Option<Coord>,
) -> Vec<u64> {
    let query = if show_search(saved.stops.len()) {
        query
    } else {
        ""
    };
    saved_stops::sorted(saved.search(query), sort, here)
        .into_iter()
        .map(|st| st.id)
        .collect()
}

/// The list row title: a star in front of a favourite.
pub fn row_title(stop: &SavedStop) -> String {
    if stop.favourite {
        format!("\u{2605} {}", stop.name)
    } else {
        stop.name.clone()
    }
}

/// The pill after tapping Favourite; `state` is the new flag.
pub fn favourite_pill(state: bool) -> &'static str {
    if state {
        "Added to favourites"
    } else {
        "Removed from favourites"
    }
}

/// The colour pill's text, also the pill after a change.
pub fn colour_label(c: StopColour) -> String {
    format!("Colour: {}", c.label())
}

/// The pill after copying the visible list.
pub fn copy_list_pill(n: usize) -> String {
    format!("Copied {n} {}", if n == 1 { "stop" } else { "stops" })
}

/// Whether stop `id` has a neighbour with the same favourite flag `delta`
/// places away (-1 up, 1 down); false at a group edge or for an unknown id.
pub fn can_move(saved: &SavedStops, id: u64, delta: isize) -> bool {
    let Some(at) = saved.stops.iter().position(|s| s.id == id) else {
        return false;
    };
    let flag = saved.stops[at].favourite;
    let mut i = at as isize + delta.signum();
    while i >= 0 && (i as usize) < saved.stops.len() {
        if saved.stops[i as usize].favourite == flag {
            return true;
        }
        i += delta.signum();
    }
    false
}

/// Up and Down only make sense in the order the user arranged, unfiltered.
pub fn reorderable(count: usize, query: &str, sort: SavedSort) -> bool {
    sort == SavedSort::Saved && (!show_search(count) || query.trim().is_empty())
}

/// The menu key of a sort order.
pub fn sort_key(sort: SavedSort) -> &'static str {
    match sort {
        SavedSort::Saved => "sort-saved",
        SavedSort::Name => "sort-name",
        SavedSort::Nearest => "sort-nearest",
    }
}

/// The sort order a Sort menu key stands for.
pub fn sort_from_key(key: &str) -> Option<SavedSort> {
    [SavedSort::Saved, SavedSort::Name, SavedSort::Nearest]
        .into_iter()
        .find(|s| sort_key(*s) == key)
}

/// The Sort menu: one choice per order, the current one checked.
pub fn sort_menu_model(current: SavedSort) -> MenuModel {
    MenuModel::new(vec![MenuGroup::new(
        [SavedSort::Saved, SavedSort::Name, SavedSort::Nearest]
            .into_iter()
            .map(|s| MenuItem::new(sort_key(s), sort_label(s)).checked(s == current))
            .collect(),
    )])
}

/// The row "..." menu. Up and Down are disabled (not hidden) at the ends of a
/// group and whenever the list is not in the order the user arranged.
pub fn row_menu_model(can_up: bool, can_down: bool) -> MenuModel {
    let mut up = MenuItem::new("up", "Move up").icon("arrow-up");
    let mut down = MenuItem::new("down", "Move down").icon("arrow-down");
    if !can_up {
        up = up.disabled();
    }
    if !can_down {
        down = down.disabled();
    }
    MenuModel::new(vec![
        MenuGroup::new(vec![
            MenuItem::new("show", "Show on map").icon("map-pin"),
            MenuItem::new("maps", "Open in maps").icon("external-link"),
        ]),
        MenuGroup::new(vec![up, down]),
        MenuGroup::new(vec![
            MenuItem::new("delete", "Delete")
                .icon("trash-2")
                .destructive(),
        ]),
    ])
}

/// The confirm prompt of the row menu's Delete.
pub fn delete_prompt(name: &str) -> String {
    format!("Delete {name}?")
}

/// The header "..." menu. Copy list is disabled when nothing is listed.
pub fn more_menu_model(has_visible: bool) -> MenuModel {
    let mut copy = MenuItem::new("copy", "Copy list").icon("copy");
    if !has_visible {
        copy = copy.disabled();
    }
    MenuModel::new(vec![
        MenuGroup::new(vec![copy]),
        MenuGroup::new(vec![
            MenuItem::new("gpx", "Export GPX").icon("file-down"),
            MenuItem::new("json", "Export JSON").icon("download"),
        ]),
        MenuGroup::new(vec![MenuItem::new("import", "Import").icon("upload")]),
    ])
}

/// The sort pill's text.
pub fn sort_label(sort: SavedSort) -> &'static str {
    match sort {
        SavedSort::Saved => "Saved",
        SavedSort::Name => "Name",
        SavedSort::Nearest => "Nearest",
    }
}

/// The pill after an import was added.
pub fn import_pill(added: usize) -> String {
    if added == 1 {
        "Added 1 stop".to_owned()
    } else {
        format!("Added {added} stops")
    }
}

/// The confirm button of the import sheet.
pub fn add_label(count: usize) -> String {
    if count == 1 {
        "Add 1 stop".to_owned()
    } else {
        format!("Add {count} stops")
    }
}

/// "and 3 more" under the preview, when it cut the list.
pub fn more_text(total: usize) -> Option<String> {
    total
        .checked_sub(PREVIEW_ROWS)
        .filter(|more| *more > 0)
        .map(|more| format!("and {more} more"))
}

/// The date in an export's file name, on the phone's calendar.
pub fn export_stamp(now_ms: i64, tz_offset_s: i32) -> String {
    let (y, m, d) = rovsti_core::contact_dates::local_ymd(now_ms, tz_offset_s);
    format!("{y:04}-{m:02}-{d:02}")
}

/// The row subtitle: the gate length, then how far its middle is when your
/// position is known.
pub fn stop_detail(stop: &SavedStop, here: Option<Coord>, usage: Option<&str>) -> String {
    let gate = format!("Gate {}", distance_text(stop.length_m()));
    let base = match here {
        Some(h) => format!(
            "{gate}, {} away",
            distance_text(h.distance_m(stop.center()))
        ),
        None => gate,
    };
    match usage {
        Some(u) => format!("{base} · {u}"),
        None => base,
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
                    .child(
                        glass_pill(theme, "Import stops")
                            .id("saved-import-empty")
                            .px(px(20.0))
                            .mt(px(10.0))
                            .on_click(cx.listener(|this, _, _, cx| this.import_places(cx))),
                    )
                    .into_any_element(),
            );
        }
        let count = self.saved.stops.len();
        let here = self.map.position.map(|(c, _)| c);
        self.saved_visible = visible_stops(&self.saved, &self.saved_query, self.saved_sort, here);
        let header = u64::from(show_search(count))
            | (u64::from(show_sort(count)) << 1)
            | (u64::from(!self.saved_visible.is_empty()) << 2);
        // Its key changes when its height does, so it is measured again.
        let mut keys = vec![lists::key(0, header)];
        if self.saved_visible.is_empty() {
            keys.push(lists::key(3, 0));
        }
        // Rows never change height, so the key is the stop alone.
        keys.extend(self.saved_visible.iter().map(|id| lists::key(1, *id)));
        let state = self.saved_list.sync(keys);
        Page::List(Box::new(list(
            state,
            cx.processor(move |this, ix: usize, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                match ix.checked_sub(1) {
                    None => this.saved_header(theme, window, cx),
                    Some(_) if this.saved_visible.is_empty() => {
                        empty_state(theme, "No stops match", "Try another name.").into_any_element()
                    }
                    Some(i) => this.saved_item(theme, i, cx),
                }
            }),
        )))
    }

    /// The title with one "..." button, then search and sort on one 44 dp line.
    fn saved_header(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let count = self.saved.stops.len();
        let entity = cx.entity();
        let mut header = div().w_full().flex().flex_col().child(
            PageHeader::new("Saved")
                .action(
                    HeaderAction::new("saved-more", Icon::LucideEllipsis, "More")
                        .anchor(&self.more_anchor)
                        .on_click(move |_, cx| {
                            entity.update(cx, |this, cx| {
                                this.close_saved_menus();
                                if this.more_menu_ui.open_from(&this.more_anchor) {
                                    cx.notify();
                                }
                            });
                        }),
                )
                .render(window, theme),
        );
        if show_search(count) || show_sort(count) {
            let mut row = div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .px(px(layout::MARGIN))
                .min_h(px(layout::HIT))
                .mb(px(8.0));
            if show_search(count) {
                row = row.child(div().flex_1().child(field_box(
                    theme,
                    "saved-search",
                    &self.saved_field,
                    "Search saved stops",
                    window,
                    cx,
                )));
            } else {
                row = row.child(div().flex_1());
            }
            if show_sort(count) {
                row = row.child(
                    Button::new(
                        "saved-sort",
                        format!("Sort: {}", sort_label(self.saved_sort)),
                    )
                    .style(ButtonStyle::Gray)
                    .size(ButtonSize::Small)
                    .build(theme)
                    .relative()
                    .child(self.sort_anchor.track())
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.close_saved_menus();
                        if this.sort_menu_ui.open_from(&this.sort_anchor) {
                            cx.notify();
                        }
                    })),
                );
            }
            header = header.child(row);
        }
        header.into_any_element()
    }

    /// Saved stop `i`: a tap opens its sheet, the "..." button or a long press
    /// its menu. The row never changes height.
    fn saved_item(&mut self, theme: Theme, i: usize, cx: &mut Context<Self>) -> AnyElement {
        let shown = self.saved_visible.len();
        let Some(stop) = self.saved_visible.get(i).and_then(|id| self.saved.get(*id)) else {
            return div().into_any_element();
        };
        let colors = theme::colors(theme);
        let id = stop.id;
        let usage = self
            .history
            .usage_of(stop.a.coord, stop.b.coord)
            .map(|u| rovsti_geo::usage_text(u, crate::store::now_ms(), self.tz_offset_s));
        let detail = stop_detail(stop, self.map.position.map(|(c, _)| c), usage.as_deref());
        // Orange is the default and shows no dot, but the slot stays so every
        // title starts on the same x.
        let dot_colour = (stop.colour != StopColour::Orange)
            .then(|| crate::map::stop_colour_hsla(theme, stop.colour));
        let dot: Option<AnyElement> = Some(
            div()
                .size(px(10.0))
                .flex_none()
                .rounded_full()
                .children(dot_colour.map(|c| div().size_full().rounded_full().bg(c)))
                .into_any_element(),
        );
        let anchor = self.row_anchors.borrow_mut().entry(id).or_default().clone();
        let more = div()
            .id(("saved-stop-more", id as usize))
            .aria_label("More")
            .relative()
            .flex_none()
            .size(px(layout::HIT))
            .flex()
            .items_center()
            .justify_center()
            .child(
                svg()
                    .path(Icon::LucideEllipsis.path())
                    .size(px(20.0))
                    .text_color(colors.secondary_label),
            )
            .child(anchor.track())
            .on_click(cx.listener(move |this, _, _, cx| {
                cx.stop_propagation();
                this.open_row_menu(id, None, cx);
            }));
        let row = list_row(theme, dot, row_title(stop), Some(detail.into()), None)
            .id(("saved-stop", id as usize))
            .pr(px(4.0))
            .child(more)
            .on_click(cx.listener(move |this, _, _, cx| {
                this.close_saved_menus();
                this.open_saved_sheet(id, cx);
            }))
            .on_long_press(
                cx.listener(move |this, event: &gpui::LongPressEvent, _, cx| {
                    this.open_row_menu(id, Some(event.position), cx);
                }),
            );
        lists::grouped(theme, lists::Place::of(i, shown), row).into_any_element()
    }

    /// Opens the "..." menu of stop `id`, hanging off its button (or off the
    /// long-press point).
    fn open_row_menu(
        &mut self,
        id: u64,
        at: Option<gpui::Point<gpui::Pixels>>,
        cx: &mut Context<Self>,
    ) {
        self.close_saved_menus();
        let anchor = match at {
            Some(point) => AnchorSlot::point(point),
            None => self
                .row_anchors
                .borrow()
                .get(&id)
                .cloned()
                .unwrap_or_default(),
        };
        if let Some(bounds) = anchor.get() {
            self.row_menu = Some(id);
            self.row_menu_ui.open_at(bounds);
        }
        cx.notify();
    }

    /// Shots: opens the menu of stop `id` off its painted button.
    #[cfg(all(test, feature = "shots"))]
    pub(crate) fn open_row_menu_for_shot(&mut self, id: u64, cx: &mut Context<Self>) {
        self.open_row_menu(id, None, cx);
    }

    /// Shots: opens the header "..." menu off its painted button.
    #[cfg(all(test, feature = "shots"))]
    pub(crate) fn open_more_menu_for_shot(&mut self, cx: &mut Context<Self>) {
        self.more_menu_ui.open_from(&self.more_anchor);
        cx.notify();
    }

    /// Closes every popover of the Saved tab.
    pub(crate) fn close_saved_menus(&mut self) {
        self.row_menu_ui.close();
        self.more_menu_ui.close();
        self.sort_menu_ui.close();
    }

    /// Whether a Saved popover is open (it takes System Back first).
    pub(crate) fn menus_open(&self) -> bool {
        self.row_menu_ui.is_open() || self.more_menu_ui.is_open() || self.sort_menu_ui.is_open()
    }

    /// The popovers of the Saved tab, drawn over everything else.
    pub(crate) fn saved_menu_overlays(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let reduced = window.prefers_reduced_motion();
        self.row_menu_ui.set_reduced(reduced);
        self.more_menu_ui.set_reduced(reduced);
        self.sort_menu_ui.set_reduced(reduced);
        if self.row_menu.is_some() && !self.row_menu_ui.is_visible() {
            self.row_menu = None;
        }
        let mut out = Vec::new();
        if let Some(id) = self.row_menu
            && let Some(stop) = self.saved.get(id)
        {
            let reorder = reorderable(self.saved.stops.len(), &self.saved_query, self.saved_sort);
            let model = row_menu_model(
                reorder && can_move(&self.saved, id, -1),
                reorder && can_move(&self.saved, id, 1),
            );
            let prompt = delete_prompt(&stop.name);
            let select = cx.entity();
            let nav = cx.entity();
            out.extend(
                Menu::new("Stop menu", model)
                    .confirm("delete", prompt, "Delete")
                    .render(
                        &self.row_menu_ui,
                        theme,
                        window,
                        move |key, _, cx| {
                            select.update(cx, |this, cx| this.row_menu_select(id, key, cx));
                        },
                        move |request, _, cx| {
                            nav.update(cx, |this, cx| {
                                this.row_menu_ui.apply(request);
                                cx.notify();
                            });
                        },
                    ),
            );
        }
        let select = cx.entity();
        let nav = cx.entity();
        out.extend(
            Menu::new("More", more_menu_model(!self.saved_visible.is_empty())).render(
                &self.more_menu_ui,
                theme,
                window,
                move |key, _, cx| select.update(cx, |this, cx| this.more_menu_select(key, cx)),
                move |request, _, cx| {
                    nav.update(cx, |this, cx| {
                        this.more_menu_ui.apply(request);
                        cx.notify();
                    });
                },
            ),
        );
        let select = cx.entity();
        let nav = cx.entity();
        out.extend(Menu::new("Sort", sort_menu_model(self.saved_sort)).render(
            &self.sort_menu_ui,
            theme,
            window,
            move |key, _, cx| select.update(cx, |this, cx| this.sort_menu_select(key, cx)),
            move |request, _, cx| {
                nav.update(cx, |this, cx| {
                    this.sort_menu_ui.apply(request);
                    cx.notify();
                });
            },
        ));
        out
    }

    fn row_menu_select(&mut self, id: u64, key: &'static str, cx: &mut Context<Self>) {
        self.row_menu_ui.close();
        match key {
            "show" => self.show_stop_on_map(id, cx),
            "maps" => self.open_stop_in_maps(id, cx),
            "up" => self.move_stop(id, -1, cx),
            "down" => self.move_stop(id, 1, cx),
            "delete" => self.delete_stop(id, cx),
            _ => {}
        }
        cx.notify();
    }

    fn more_menu_select(&mut self, key: &'static str, cx: &mut Context<Self>) {
        self.more_menu_ui.close();
        match key {
            "copy" => self.copy_list(cx),
            "gpx" => self.export_places(true, cx),
            "json" => self.export_places(false, cx),
            "import" => self.import_places(cx),
            _ => {}
        }
        cx.notify();
    }

    fn sort_menu_select(&mut self, key: &'static str, cx: &mut Context<Self>) {
        self.sort_menu_ui.close();
        if let Some(sort) = sort_from_key(key) {
            self.saved_sort = sort;
            self.saved_list.scroll_to_top();
        }
        cx.notify();
    }

    pub(crate) fn toggle_stop_favourite(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(state) = self.saved.toggle_favourite(id) else {
            return;
        };
        self.save_stops(cx);
        self.saved_visible = visible_stops(
            &self.saved,
            &self.saved_query,
            self.saved_sort,
            self.map.position.map(|(c, _)| c),
        );
        self.show_pill(favourite_pill(state), None, cx);
    }

    /// Moves the stop to its next map colour; Undo on the pill puts the old one back.
    pub(crate) fn cycle_stop_colour(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(before) = self.saved.get(id).map(|s| s.colour) else {
            return;
        };
        let Some(now) = self.saved.cycle_colour(id) else {
            return;
        };
        self.save_stops(cx);
        self.show_pill(
            colour_label(now),
            Some(crate::PillAction::RestoreColour(id, before)),
            cx,
        );
    }

    /// Undo of a colour change.
    pub(crate) fn restore_colour(&mut self, id: u64, colour: StopColour, cx: &mut Context<Self>) {
        if self.saved.set_colour(id, colour) {
            self.save_stops(cx);
        }
        cx.notify();
    }

    pub(crate) fn open_stop_in_maps(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let url = crate::map::maps_url(stop.center(), &stop.name);
        cx.open_url(&url);
        self.show_pill("Opening in maps", None, cx);
    }

    /// Copies the visible stops (search and sort respected) as plain text.
    fn copy_list(&mut self, cx: &mut Context<Self>) {
        let stops: Vec<&SavedStop> = self
            .saved_visible
            .iter()
            .filter_map(|id| self.saved.get(*id))
            .collect();
        if stops.is_empty() {
            return;
        }
        let text = saved_stops::list_text(&stops, self.map.position.map(|(c, _)| c));
        let n = stops.len();
        cx.write_to_clipboard(gpui::ClipboardItem::new_string(text));
        self.show_pill(copy_list_pill(n), None, cx);
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
        self.share_out(name, text, cx);
    }

    /// Shares a saved stop (name and both points) as text.
    pub(crate) fn share_stop(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let text = rovsti_geo::dest::stop_share_text(&stop.name, stop.a.coord, stop.b.coord);
        let name = stop.name.clone();
        self.share_out(name, text, cx);
    }

    /// Asks the launcher to put a shortcut to the stop on the home screen.
    pub(crate) fn pin_stop_home(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id).cloned() else {
            return;
        };
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::pin_stop_shortcut(&stop) })
                .await;
            let text = pin_pill(&result);
            this.update(cx, |this, cx| this.show_pill(text, None, cx))
                .ok();
        })
        .detach();
    }

    /// Opens Copy as for the middle of the gate.
    pub(crate) fn copy_stop_as(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let (label, center) = (stop.name.clone(), stop.center());
        self.open_copy_as(label, center, cx);
    }

    /// The QR code of the stop's share text, which another Stops can read back.
    pub(crate) fn open_stop_qr(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let payload = rovsti_geo::dest::stop_share_text(&stop.name, stop.a.coord, stop.b.coord);
        match qr_sheet::make_card(&payload, &stop.name) {
            Ok(card) => {
                self.sheet_mode = SheetMode::Qr(card);
                self.sheet.open();
            }
            Err(words) => self.show_pill(words, None, cx),
        }
        cx.notify();
    }

    /// Hands `text` to the share sheet; without one it goes to the clipboard.
    pub(crate) fn share_out(&mut self, name: String, text: String, cx: &mut Context<Self>) {
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

    /// Puts both points of the stop on the map and frames the circle.
    pub(crate) fn show_stop_on_map(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let (a, b) = (dest_from_endpoint(&stop.a), dest_from_endpoint(&stop.b));
        self.pick.set_pair(a, b);
        self.map.follow = true;
        self.tabs.jump_to(0);
        cx.notify();
    }

    /// Runs a trip for the stop straight away, unless one is running.
    pub(crate) fn start_saved_stop(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.trip.is_some() {
            self.show_pill("Not while a trip is running", None, cx);
            return;
        }
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let (a, b) = (dest_from_endpoint(&stop.a), dest_from_endpoint(&stop.b));
        self.request_start(a, b, cx);
    }

    /// Deletes the stop (the menu already asked); Undo on the pill brings it back.
    fn delete_stop(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(index) = self.saved.stops.iter().position(|st| st.id == id) else {
            return;
        };
        let stop = self.saved.stops[index].clone();
        self.saved.remove(id);
        self.save_stops(cx);
        let text = format!("Deleted {}", stop.name);
        self.show_pill(text, Some(crate::PillAction::UndoDelete(stop, index)), cx);
    }

    /// Writes the saved stops to a file the user picks the place of.
    fn export_places(&mut self, gpx: bool, cx: &mut Context<Self>) {
        let (ext, mime, text) = if gpx {
            ("gpx", "application/gpx+xml", places_io::to_gpx(&self.saved))
        } else {
            ("json", "application/json", places_io::to_json(&self.saved))
        };
        let stamp = export_stamp(crate::store::now_ms(), self.tz_offset_s);
        let name = places_io::export_file_name(ext, &stamp);
        // The system dialog is the feedback; its answer comes as a document result.
        self.pending_export = Some(ExportKind::SavedStops);
        if let Err(words) = backend::export_text(&name, mime, &text) {
            self.pending_export = None;
            self.show_pill(format!("Can't export: {words}"), None, cx);
        }
    }

    /// Opens the file picker; the answer arrives in `on_places_picked`.
    fn import_places(&mut self, cx: &mut Context<Self>) {
        self.pending_import = Some(ImportKind::Places);
        if let Err(words) = backend::pick_import() {
            self.show_pill(format!("Can't import: {words}"), None, cx);
        }
    }

    /// The picked file: read it, plan the merge and ask before adding.
    pub(crate) fn on_places_picked(&mut self, result: &DocumentResult, cx: &mut Context<Self>) {
        let Some(path) = result.path.as_deref().filter(|_| result.ok) else {
            if result.error.is_some() {
                self.show_pill("Can't import: that file can't be read", None, cx);
            }
            return;
        };
        let text = match backend::read_import(path) {
            Ok(text) => text,
            Err(words) => {
                self.show_pill(format!("Can't import: {words}"), None, cx);
                return;
            }
        };
        match places_io::parse_import(&text) {
            Err(error) => self.show_pill(error.message(), None, cx),
            Ok(items) => {
                let plan = places_io::merge_plan(&self.saved, items);
                if plan.add.is_empty() {
                    self.show_pill(places_io::summary(&plan), None, cx);
                } else {
                    self.sheet_mode = SheetMode::ImportStops(plan);
                    self.sheet.open();
                    cx.notify();
                }
            }
        }
    }

    /// Settings > Backup > Export: one JSON file with stops, trips and settings.
    pub(crate) fn export_backup(&mut self, cx: &mut Context<Self>) {
        let now = crate::store::now_ms();
        let file = Backup::new(
            self.saved.clone(),
            self.history.clone(),
            self.prefs.clone(),
            now,
        );
        let name = backup::export_file_name(&export_stamp(now, self.tz_offset_s));
        self.pending_export = Some(ExportKind::Backup);
        if let Err(words) = backend::export_text(&name, "application/json", &backup::to_json(&file))
        {
            self.pending_export = None;
            self.show_pill(format!("Can't export: {words}"), None, cx);
        }
    }

    /// Settings > Backup > Restore: opens the file picker unless a trip runs.
    pub(crate) fn restore_backup(&mut self, cx: &mut Context<Self>) {
        if let Some(text) = restore_blocked(self.trip.is_some()) {
            self.show_pill(text, None, cx);
            return;
        }
        self.pending_import = Some(ImportKind::Backup);
        if let Err(words) = backend::pick_import() {
            self.pending_import = None;
            self.show_pill(format!("Can't import: {words}"), None, cx);
        }
    }

    /// The picked backup file: parse it and ask before replacing anything.
    pub(crate) fn on_backup_picked(&mut self, result: &DocumentResult, cx: &mut Context<Self>) {
        let Some(path) = result.path.as_deref().filter(|_| result.ok) else {
            if result.error.is_some() {
                self.show_pill("Can't restore: that file can't be read", None, cx);
            }
            return;
        };
        let text = match backend::read_import(path) {
            Ok(text) => text,
            Err(words) => {
                self.show_pill(format!("Can't restore: {words}"), None, cx);
                return;
            }
        };
        match backup::parse(&text) {
            Err(error) => self.show_pill(error.message(), None, cx),
            Ok(file) => {
                // A trip may have started while the picker was open.
                if let Some(text) = restore_blocked(self.trip.is_some()) {
                    self.show_pill(text, None, cx);
                    return;
                }
                self.sheet_mode = SheetMode::RestoreBackup(Box::new(file));
                self.sheet.open();
                cx.notify();
            }
        }
    }

    /// The restore sheet: what the file holds, then Cancel / Replace everything.
    pub(crate) fn restore_body(
        &mut self,
        theme: Theme,
        file: &Backup,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let confirm = file.clone();
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, "Restore backup"))
            .child(Self::footnote(
                theme,
                restore_sheet_body(file, self.tz_offset_s),
            ))
            .child(
                div()
                    .flex()
                    .justify_end()
                    .gap(px(10.0))
                    .px(px(16.0))
                    .pt(px(8.0))
                    .child(
                        glass_pill(theme, "Cancel")
                            .id("restore-cancel")
                            .on_click(cx.listener(|this, _, _, cx| this.close_sheet(cx))),
                    )
                    .child(
                        glass_pill(theme, "Replace everything")
                            .id("restore-confirm")
                            .text_color(colors.red)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.confirm_restore(confirm.clone(), cx);
                            })),
                    ),
            )
            .into_any_element()
    }

    /// Replaces stops, trips and settings by the backup, with Undo on the pill.
    fn confirm_restore(&mut self, file: Backup, cx: &mut Context<Self>) {
        self.sheet.close();
        if let Some(text) = restore_blocked(self.trip.is_some()) {
            self.show_pill(text, None, cx);
            return;
        }
        let before = Box::new((
            std::mem::take(&mut self.saved),
            std::mem::take(&mut self.history),
            self.prefs.clone(),
        ));
        self.apply_snapshot(file.saved, file.history, file.prefs, cx);
        self.show_pill(
            "Backup restored",
            Some(crate::PillAction::UndoRestore(before)),
            cx,
        );
    }

    /// Makes `saved`, `history` and `prefs` the app's data: saves them and
    /// applies what the settings drive. Restore and its Undo share this.
    pub(crate) fn apply_snapshot(
        &mut self,
        saved: SavedStops,
        history: rovsti_geo::History,
        prefs: rovsti_geo::StopsPrefs,
        cx: &mut Context<Self>,
    ) {
        self.saved = saved;
        self.history = history;
        self.prefs = prefs;
        self.save_stops(cx);
        self.save_history_in_background(cx);
        self.save_prefs(cx);
        self.apply_appearance(cx);
        self.use_tile_source(self.prefs.tile_source(), cx);
        let cap = self.prefs.tile_cache_bytes();
        self.map_tiles.set_cap(cap, cx);
        self.saved_visible = visible_stops(
            &self.saved,
            &self.saved_query,
            self.saved_sort,
            self.map.position.map(|(c, _)| c),
        );
        self.close_saved_menus();
        self.last_trip_hidden = None;
        self.history_open = None;
        cx.notify();
    }

    /// The import sheet: what would be added, then Cancel / Add.
    pub(crate) fn import_body(
        &mut self,
        theme: Theme,
        plan: &MergePlan,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let rows = plan
            .add
            .iter()
            .take(PREVIEW_ROWS)
            .enumerate()
            .map(|(i, st)| {
                let gate = format!("Gate {}", distance_text(st.a.distance_m(st.b)));
                list_row(theme, None, st.name.clone(), Some(gate.into()), None)
                    .id(("import-row", i))
                    .into_any_element()
            });
        let mut body = div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, "Import stops"))
            .child(Self::footnote(theme, places_io::summary(plan)))
            .child(section(theme, None, rows));
        if let Some(more) = more_text(plan.add.len()) {
            body = body.child(Self::footnote(theme, more));
        }
        let confirm = plan.clone();
        body.child(
            div()
                .flex()
                .justify_end()
                .gap(px(10.0))
                .px(px(16.0))
                .pt(px(8.0))
                .child(
                    glass_pill(theme, "Cancel")
                        .id("import-cancel")
                        .on_click(cx.listener(|this, _, _, cx| this.close_sheet(cx))),
                )
                .child(
                    glass_pill(theme, add_label(plan.add.len()))
                        .id("import-add")
                        .text_color(colors.accent)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.confirm_import(confirm.clone(), cx);
                        })),
                ),
        )
        .into_any_element()
    }

    /// Adds the planned stops, with Undo on the pill.
    fn confirm_import(&mut self, plan: MergePlan, cx: &mut Context<Self>) {
        let before = self.saved.clone();
        let now = crate::store::now_ms();
        let added = places_io::apply_plan(&mut self.saved, &plan, now);
        self.sheet.close();
        if added == 0 {
            self.show_pill("Nothing new to add", None, cx);
            return;
        }
        self.save_stops(cx);
        self.show_pill(
            import_pill(added),
            Some(crate::PillAction::RestoreSaved(before)),
            cx,
        );
    }

    /// Undo of an import.
    pub(crate) fn restore_saved(&mut self, saved: SavedStops, cx: &mut Context<Self>) {
        self.saved = saved;
        self.close_saved_menus();
        self.save_stops(cx);
        cx.notify();
    }

    /// Undo on the pill.
    pub(crate) fn undo_delete(&mut self, stop: SavedStop, index: usize, cx: &mut Context<Self>) {
        self.saved.restore(stop, index);
        self.save_stops(cx);
        cx.notify();
    }
}

/// The pill after asking the launcher for a shortcut.
pub fn pin_pill(result: &Result<bool, AndroidError>) -> String {
    match result {
        Ok(true) => "Added to the home screen".to_owned(),
        Ok(false) => "The launcher did not add it".to_owned(),
        Err(_) => {
            crate::settings_ui::android_notice("Home screen shortcut", result).unwrap_or_default()
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn the_shortcut_pill_covers_every_answer() {
        assert_eq!(pin_pill(&Ok(true)), "Added to the home screen");
        assert_eq!(pin_pill(&Ok(false)), "The launcher did not add it");
        assert!(pin_pill(&Err(AndroidError::Unsupported)).contains("phone only"));
    }
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
        assert_eq!(stop_detail(&st, None, None), "Gate 420 m");
        let here = st.center();
        assert_eq!(stop_detail(&st, Some(here), None), "Gate 420 m, 0 m away");
        let far = Coord::new(41.1, 29.0);
        let text = stop_detail(&st, Some(far), None);
        assert_eq!(
            stop_detail(&st, None, Some("Used 4 times, last 5 Nov")),
            "Gate 420 m · Used 4 times, last 5 Nov"
        );
        assert!(
            text.starts_with("Gate 420 m, 10.") && text.ends_with(" km away"),
            "{text}"
        );
    }

    #[test]
    fn the_row_menu_groups_actions_and_disables_the_ends() {
        let m = row_menu_model(true, true);
        assert_eq!(m.group_sizes(), vec![2, 2, 1]);
        assert!(m.find("up").unwrap().enabled && m.find("down").unwrap().enabled);
        let m = row_menu_model(false, true);
        assert!(!m.find("up").unwrap().enabled);
        assert!(m.find("down").unwrap().enabled);
        // Disabled, never hidden.
        assert_eq!(row_menu_model(false, false).item_count(), 5);
        let delete = m.find("delete").unwrap();
        assert_eq!(delete.role, rovsti_ui::menu::model::MenuRole::Destructive);
        assert_eq!(delete_prompt("Home"), "Delete Home?");
    }

    #[test]
    fn the_more_menu_holds_the_list_tools() {
        let m = more_menu_model(true);
        assert_eq!(m.group_sizes(), vec![1, 2, 1]);
        let labels: Vec<_> = m.labels().collect();
        assert_eq!(labels, ["Copy list", "Export GPX", "Export JSON", "Import"]);
        assert!(m.find("copy").unwrap().enabled);
        assert!(!more_menu_model(false).find("copy").unwrap().enabled);
    }

    #[test]
    fn the_sort_menu_checks_the_current_order() {
        for current in [SavedSort::Saved, SavedSort::Name, SavedSort::Nearest] {
            let m = sort_menu_model(current);
            let checked: Vec<_> = m
                .groups()
                .iter()
                .flat_map(|g| g.0.iter())
                .filter(|i| i.checked)
                .map(|i| i.key)
                .collect();
            assert_eq!(checked, [sort_key(current)]);
            assert_eq!(sort_from_key(sort_key(current)), Some(current));
        }
        assert_eq!(sort_from_key("nope"), None);
    }

    #[test]
    fn share_outcomes() {
        assert_eq!(share_outcome(&Ok(true), "Home"), ShareOutcome::Shared);
        let copy = ShareOutcome::Copy("Copied Home".into());
        assert_eq!(share_outcome(&Ok(false), "Home"), copy);
        assert_eq!(share_outcome(&Err(AndroidError::Unsupported), "Home"), copy);
    }

    fn six() -> SavedStops {
        let mut s = SavedStops::default();
        let ep = |lat: f64| Endpoint {
            label: String::new(),
            coord: Coord::new(lat, 29.0),
        };
        for (i, name) in ["Zeta", "alpha", "Émile", "Mid", "Near", "Far"]
            .into_iter()
            .enumerate()
        {
            let lat = 40.0 + i as f64;
            s.add(name, ep(lat), ep(lat + 0.003), 0).unwrap();
        }
        s
    }

    fn names(s: &SavedStops, ids: &[u64]) -> Vec<String> {
        ids.iter()
            .map(|id| s.get(*id).unwrap().name.clone())
            .collect()
    }

    #[test]
    fn search_shows_above_five_stops_only() {
        assert!(!show_search(5));
        assert!(show_search(6));
        assert!(!show_sort(1));
        assert!(show_sort(2));
    }

    #[test]
    fn visible_stops_filter_and_sort() {
        let s = six();
        let ids = visible_stops(&s, "", SavedSort::Saved, None);
        assert_eq!(names(&s, &ids)[0], "Zeta");
        let ids = visible_stops(&s, "", SavedSort::Name, None);
        assert_eq!(
            names(&s, &ids),
            ["alpha", "Émile", "Far", "Mid", "Near", "Zeta"]
        );
        let ids = visible_stops(&s, "emi", SavedSort::Saved, None);
        assert_eq!(names(&s, &ids), ["Émile"]);
        assert!(visible_stops(&s, "nothing", SavedSort::Saved, None).is_empty());
        // Nearest to the last stop's latitude.
        let here = Some(Coord::new(45.0, 29.0));
        let ids = visible_stops(&s, "", SavedSort::Nearest, here);
        assert_eq!(names(&s, &ids)[0], "Far");
        // Without a position Nearest keeps the saved order.
        let ids = visible_stops(&s, "", SavedSort::Nearest, None);
        assert_eq!(names(&s, &ids)[0], "Zeta");
    }

    #[test]
    fn a_hidden_search_does_not_filter() {
        let mut s = six();
        for id in [1, 2, 3] {
            s.remove(id);
        }
        assert_eq!(visible_stops(&s, "zzz", SavedSort::Saved, None).len(), 3);
        assert!(reorderable(3, "zzz", SavedSort::Saved));
    }

    #[test]
    fn row_title_stars_favourites() {
        let mut s = six();
        s.toggle_favourite(2);
        assert_eq!(row_title(s.get(1).unwrap()), "Zeta");
        assert_eq!(row_title(s.get(2).unwrap()), "\u{2605} alpha");
    }

    #[test]
    fn colour_label_names_the_tag() {
        assert_eq!(colour_label(StopColour::Orange), "Colour: Orange");
        assert_eq!(colour_label(StopColour::Blue), "Colour: Blue");
    }

    #[test]
    fn favourite_and_copy_pills() {
        assert_eq!(favourite_pill(true), "Added to favourites");
        assert_eq!(favourite_pill(false), "Removed from favourites");
        assert_eq!(copy_list_pill(1), "Copied 1 stop");
        assert_eq!(copy_list_pill(12), "Copied 12 stops");
    }

    #[test]
    fn can_move_stops_at_group_edges() {
        let mut s = six();
        s.toggle_favourite(3);
        s.toggle_favourite(5);
        // Order: 1 2 3* 4 5* 6. Non-favourites: 1 2 4 6; favourites: 3 5.
        assert!(!can_move(&s, 1, -1));
        assert!(can_move(&s, 1, 1));
        assert!(can_move(&s, 4, -1));
        assert!(can_move(&s, 4, 1));
        assert!(!can_move(&s, 6, 1));
        assert!(!can_move(&s, 3, -1));
        assert!(can_move(&s, 3, 1));
        assert!(can_move(&s, 5, -1));
        assert!(!can_move(&s, 5, 1));
        assert!(!can_move(&s, 99, 1));
    }

    #[test]
    fn favourites_lead_under_a_query() {
        let mut s = six();
        s.toggle_favourite(4); // Mid
        s.toggle_favourite(5); // Near
        let ids = visible_stops(&s, "e", SavedSort::Saved, None);
        assert_eq!(names(&s, &ids), ["Near", "Zeta", "Émile"]);
    }

    #[test]
    fn reordering_needs_saved_order_and_no_query() {
        assert!(reorderable(6, "  ", SavedSort::Saved));
        assert!(!reorderable(6, "a", SavedSort::Saved));
        assert!(!reorderable(6, "", SavedSort::Name));
    }

    #[test]
    fn sort_labels() {
        let labels: Vec<_> = [SavedSort::Saved, SavedSort::Name, SavedSort::Nearest]
            .into_iter()
            .map(sort_label)
            .collect();
        assert_eq!(labels, ["Saved", "Name", "Nearest"]);
    }

    #[test]
    fn import_texts() {
        assert_eq!(import_pill(1), "Added 1 stop");
        assert_eq!(import_pill(4), "Added 4 stops");
        assert_eq!(add_label(1), "Add 1 stop");
        assert_eq!(add_label(12), "Add 12 stops");
        assert_eq!(more_text(5), None);
        assert_eq!(more_text(8).as_deref(), Some("and 3 more"));
    }

    #[test]
    fn export_pills_name_the_export() {
        assert_eq!(
            export_pill(ExportKind::History, true, false).as_deref(),
            Some("History exported")
        );
        assert_eq!(
            export_pill(ExportKind::SavedStops, true, false).as_deref(),
            Some("Saved stops exported")
        );
        assert!(export_pill(ExportKind::History, false, true).is_some());
        assert_eq!(export_pill(ExportKind::History, false, false), None);
    }

    #[test]
    fn backup_texts() {
        assert_eq!(
            export_pill(ExportKind::Backup, true, false).as_deref(),
            Some("Backup saved")
        );
        assert_eq!(restore_blocked(true), Some("Not while a trip is running"));
        assert_eq!(restore_blocked(false), None);
        assert_eq!(ImportKind::default(), ImportKind::Places);
        let file = Backup::new(
            SavedStops::default(),
            rovsti_geo::History::default(),
            rovsti_geo::StopsPrefs::default(),
            1_793_000_000_000,
        );
        let body = restore_sheet_body(&file, 0);
        assert!(body.starts_with("0 stops, 0 trips and settings from "));
        assert!(body.ends_with("This replaces your saved stops, trip history and settings."));
    }

    #[test]
    fn history_field_shows_above_five_trips() {
        assert!(!history_field_visible(5));
        assert!(history_field_visible(6));
    }

    #[test]
    fn export_stamp_uses_the_phone_calendar() {
        // 2026-11-07 22:00 UTC is already the 8th in Turkey.
        let ms = 1_794_088_800_000;
        assert_eq!(export_stamp(ms, 0), "2026-11-07");
        assert_eq!(export_stamp(ms, 10_800), "2026-11-08");
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
