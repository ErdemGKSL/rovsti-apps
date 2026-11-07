//! The Saved tab: stops (a gate of two points) kept for one-tap trips. Tapping a
//! row opens its actions (Rename, Up, Down, Delete); a delete can be undone from
//! the pill. `share_point` opens the system share sheet (`telecom::share_text`)
//! and falls back to the clipboard when there is no sheet (desktop, or no app
//! answered).

use crate::pick::dest_from_endpoint;
use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, Window, div, list, px,
};
use rovsti_android::Error as AndroidError;
use rovsti_core::wire::DocumentResult;
use rovsti_geo::{
    Coord, SavedStop, SavedStops,
    places_io::{self, MergePlan},
    saved_stops::{self, SavedSort},
};
use rovsti_ui::{
    components::{empty_state, glass_pill, large_title, list_row, section},
    field::field_box,
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
}

/// The pill for the answer of a save dialog: `None` when it was cancelled.
pub fn export_pill(kind: ExportKind, ok: bool, error: bool) -> Option<String> {
    if ok {
        Some(
            match kind {
                ExportKind::SavedStops => "Saved stops exported",
                ExportKind::History => "History exported",
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

/// Up and Down only make sense in the order the user arranged, unfiltered.
pub fn reorderable(count: usize, query: &str, sort: SavedSort) -> bool {
    sort == SavedSort::Saved && (!show_search(count) || query.trim().is_empty())
}

/// The next order when the sort pill is tapped.
pub fn next_sort(sort: SavedSort) -> SavedSort {
    match sort {
        SavedSort::Saved => SavedSort::Name,
        SavedSort::Name => SavedSort::Nearest,
        SavedSort::Nearest => SavedSort::Saved,
    }
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

/// What a tap on Delete does for stop `id` given the stop awaiting confirmation.
#[derive(Debug, PartialEq, Eq)]
pub struct DeleteStep {
    /// The stop awaiting confirmation after this tap.
    pub confirm: Option<u64>,
    /// Delete now (this was the second tap on the same stop).
    pub delete: bool,
}

/// The first tap arms `id`, the second tap on the same stop deletes it, and a
/// tap on another stop's Delete arms that one instead.
pub fn delete_step(armed: Option<u64>, id: u64) -> DeleteStep {
    if armed == Some(id) {
        DeleteStep {
            confirm: None,
            delete: true,
        }
    } else {
        DeleteStep {
            confirm: Some(id),
            delete: false,
        }
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
        let header = u64::from(show_search(count)) | (u64::from(show_sort(count)) << 1);
        // Its key changes when its height does, so it is measured again.
        let mut keys = vec![lists::key(0, header)];
        if self.saved_visible.is_empty() {
            keys.push(lists::key(3, 0));
        }
        keys.extend(self.saved_visible.iter().map(|id| {
            // An opened stop is taller, and taller again with the Cancel row.
            let open = self.expanded == Some(*id);
            let armed = open && self.confirm_delete == Some(*id);
            lists::key(1 + u8::from(open) + u8::from(armed), *id)
        }));
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

    /// The title, the export / import pills, then search and sort.
    fn saved_header(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let count = self.saved.stops.len();
        let tools = div()
            .flex()
            .gap(px(10.0))
            .px(px(16.0))
            .pb(px(10.0))
            .child(
                glass_pill(theme, "Export GPX")
                    .id("saved-export-gpx")
                    .flex_1()
                    .on_click(cx.listener(|this, _, _, cx| this.export_places(true, cx))),
            )
            .child(
                glass_pill(theme, "Export JSON")
                    .id("saved-export-json")
                    .flex_1()
                    .on_click(cx.listener(|this, _, _, cx| this.export_places(false, cx))),
            )
            .child(
                glass_pill(theme, "Import")
                    .id("saved-import")
                    .flex_1()
                    .text_color(colors.accent)
                    .on_click(cx.listener(|this, _, _, cx| this.import_places(cx))),
            );
        let mut header = div()
            .flex()
            .flex_col()
            .child(large_title(theme, "Saved", None))
            .child(tools);
        if show_search(count) || show_sort(count) {
            let mut row = div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .px(px(16.0))
                .pb(px(10.0));
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
                    glass_pill(theme, format!("Sort: {}", sort_label(self.saved_sort)))
                        .id("saved-sort")
                        .text_color(colors.accent)
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.saved_sort = next_sort(this.saved_sort);
                            this.expanded = None;
                            this.confirm_delete = None;
                            this.saved_list.scroll_to_top();
                            cx.notify();
                        })),
                );
            }
            header = header.child(row);
        }
        header.into_any_element()
    }

    /// Saved stop `i` with its actions when it is open.
    fn saved_item(&mut self, theme: Theme, i: usize, cx: &mut Context<Self>) -> AnyElement {
        let shown = self.saved_visible.len();
        let count = self.saved.stops.len();
        let Some(stop) = self.saved_visible.get(i).and_then(|id| self.saved.get(*id)) else {
            return div().into_any_element();
        };
        let reorder = reorderable(count, &self.saved_query, self.saved_sort);
        let at = self
            .saved
            .stops
            .iter()
            .position(|st| st.id == stop.id)
            .unwrap_or(0);
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
            this.confirm_delete = None;
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
            let armed = self.confirm_delete == Some(id);
            let delete_label = if armed {
                format!("Delete {}?", stop.name)
            } else {
                "Delete".to_string()
            };
            let up: AnyElement = if reorder && at > 0 {
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
            let down: AnyElement = if reorder && at + 1 < count {
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
                        glass_pill(theme, "Show on map")
                            .id(("stop-show", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.show_stop_on_map(id, cx);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Start")
                            .id(("stop-start", id as usize))
                            .flex_1()
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.start_saved_stop(id, cx);
                            })),
                    ),
            );
            item = item.child(
                pill_row()
                    .child(
                        glass_pill(theme, "Share")
                            .id(("stop-share", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.share_stop(id, cx);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Copy as")
                            .id(("stop-copy-as", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.copy_stop_as(id, cx);
                            })),
                    )
                    .child(
                        glass_pill(theme, "QR")
                            .id(("stop-qr", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_stop_qr(id, cx);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Home screen")
                            .id(("stop-pin", id as usize))
                            .flex_1()
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.pin_stop_home(id, cx);
                            })),
                    ),
            );
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
                        glass_pill(theme, delete_label)
                            .id(("stop-delete", id as usize))
                            .flex_1()
                            .text_color(colors.red)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.delete_stop(id, cx);
                            })),
                    ),
            );
            if armed {
                item = item.child(
                    pill_row().child(
                        glass_pill(theme, "Cancel")
                            .id(("stop-delete-cancel", id as usize))
                            .flex_1()
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.confirm_delete = None;
                                cx.notify();
                            })),
                    ),
                );
            }
        }
        lists::grouped(theme, lists::Place::of(i, shown), item).into_any_element()
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
    fn share_stop(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let text = rovsti_geo::dest::stop_share_text(&stop.name, stop.a.coord, stop.b.coord);
        let name = stop.name.clone();
        self.share_out(name, text, cx);
    }

    /// Asks the launcher to put a shortcut to the stop on the home screen.
    fn pin_stop_home(&mut self, id: u64, cx: &mut Context<Self>) {
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
    fn copy_stop_as(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let (label, center) = (stop.name.clone(), stop.center());
        self.open_copy_as(label, center, cx);
    }

    /// The QR code of the stop's share text, which another Stops can read back.
    fn open_stop_qr(&mut self, id: u64, cx: &mut Context<Self>) {
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
    fn share_out(&mut self, name: String, text: String, cx: &mut Context<Self>) {
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
    fn show_stop_on_map(&mut self, id: u64, cx: &mut Context<Self>) {
        let Some(stop) = self.saved.get(id) else {
            return;
        };
        let (a, b) = (dest_from_endpoint(&stop.a), dest_from_endpoint(&stop.b));
        self.pick.set_pair(a, b);
        self.map.follow = true;
        self.expanded = None;
        self.confirm_delete = None;
        self.tabs.jump_to(0);
        cx.notify();
    }

    /// Runs a trip for the stop straight away, unless one is running.
    fn start_saved_stop(&mut self, id: u64, cx: &mut Context<Self>) {
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

    /// Two taps: the first asks, the second deletes.
    fn delete_stop(&mut self, id: u64, cx: &mut Context<Self>) {
        let step = delete_step(self.confirm_delete, id);
        self.confirm_delete = step.confirm;
        if !step.delete {
            cx.notify();
            return;
        }
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
        self.expanded = None;
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
        self.expanded = None;
        self.confirm_delete = None;
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
    fn delete_needs_two_taps_on_the_same_stop() {
        let first = delete_step(None, 3);
        assert_eq!(
            first,
            DeleteStep {
                confirm: Some(3),
                delete: false
            }
        );
        assert!(delete_step(first.confirm, 3).delete);
        assert_eq!(delete_step(first.confirm, 3).confirm, None);
        // Another stop's Delete re-arms instead of deleting.
        let other = delete_step(Some(3), 4);
        assert_eq!((other.confirm, other.delete), (Some(4), false));
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
    fn reordering_needs_saved_order_and_no_query() {
        assert!(reorderable(6, "  ", SavedSort::Saved));
        assert!(!reorderable(6, "a", SavedSort::Saved));
        assert!(!reorderable(6, "", SavedSort::Name));
    }

    #[test]
    fn the_sort_pill_cycles() {
        let mut sort = SavedSort::Saved;
        let mut seen = Vec::new();
        for _ in 0..4 {
            seen.push(sort_label(sort));
            sort = next_sort(sort);
        }
        assert_eq!(seen, ["Saved", "Name", "Nearest", "Saved"]);
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
