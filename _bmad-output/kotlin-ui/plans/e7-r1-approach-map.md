### Map engine (E7.2a/E7.2b/E7.13 AC1)

Package `dev.rosty.stops.map` (`android/stops/src/main/kotlin/dev/rosty/stops/map/`), tests beside it under
`src/test`. Nothing outside the package was changed; no dependency was added to `android/stops/build.gradle.kts`.

**Public surface for the other stories**

| What | Where | Used by |
|---|---|---|
| `StopsMap(camera, tiles, dark, modifier, gesturePolicy, gesturesEnabled, overlay)` | `StopsMap.kt` | E7.1 home (full window, behind chrome) |
| `MapCamera` (`screenX/Y`, `latAt/lonAt`, `panBy`, `zoomAbout`, `fit`, `fitPoints`, `centreOn`, `travelTo`, `animateZoomBy`, `setPadding`, `moving`, `nearestOnScreen`) | `MapCamera.kt` | E7.3 layers, E7.4 picking, E7.9 follow/fit |
| `MapTileStore(source, scope, bitmapBudgetBytes = MapTileStore.bitmapBudget(context))`, `HandleTileSource(TileHandle)` | `MapTileStore.kt`, `TileSource.kt` | the app container (one store per map screen, `close()` with it) |
| `MapGesturePolicy` / `MapDragClaim`, `MapGesturePolicy.PIN_GRAB_DP` | `MapGestures.kt` | E7.4 |
| `MapScaleBar(camera, modifier, units)`, `MapAttribution(modifier)`, `MapUnits` | `ScaleBar.kt`, `StopsMap.kt` | E7.1 chrome (place above the sheet), S43 |
| `MapMotionEffect(camera, motionGate)` | `StopsMap.kt` | chrome: Frost while the map moves |
| `MapFollowMode` | `MapCamera.kt` | E7.9 |
| `FakeTileSource.city() / empty() / synthetic()` (test source set) | `src/test/.../FakeTileSource.kt` | every Stops screen test |

**Camera model and why.** `MapCamera` keeps the centre as a normalised Web-Mercator position (x wraps, y clamped
to 85 degrees), a fractional display zoom (a tile of zoom z is 512 dp at zoom z, as in the GPUI map), the viewport in
px with the density, and a padding (the glass-covered edges; `fit`, `centreOn` and `travelTo` aim at the free area).
The Rust `Viewport` is a local equirectangular approximation; the camera is exact Mercator because bitmaps of
Mercator tiles are drawn as squares and pins must stay on them at every zoom. Over the few kilometres of the
`view.*` fixtures the two agree to under 0.25 px, 1e-5 degrees and 0.1 % of scale, which is what
`MapCameraFixtureTest` asserts for every `view.project`, `view.step`, `view.tiles`, `view.frac_to_coord` and
`view.follow` fixture (tile lists, zooms and the follow table exactly). One thing found on the way: Rust measures
ground on the mean sphere (R = 6 371 008.8 m) but `display_zoom` uses the WGS84 equator, 0.11 % apart. The camera's
`metresPerDp` is on the sphere (so a Rust distance divided by it is the pixels on the map); `camera.displayZoom` is
the Rust formula and only feeds `fetch_zoom`. Limits are the GPUI ones (0.5 to 2000 m per dp at the centre, so the
zoom range depends on latitude). Non-finite input is refused and changes nothing. Fling is the UIKit decay (0.998
per ms), double tap is `zoom_about(2)` eased over 250 ms; GPUI has no two-finger-tap zoom out, so there is none
(`animateZoomBy(0.5)` is there for a button).

**No recomposition.** The camera's numbers are plain fields; every change bumps one snapshot counter that only
`observe()` reads, and `observe()` is called in the draw of the map node and of the scale bar. `moving` is a
`State<Boolean>` written on edges. The tile store has its own counter read in draw. The viewport reaches the camera
in `onRemeasured`, not in composition. `StopsMapRecompositionTest` runs a pan, a pinch and a fling and asserts zero
recompositions of the caller's slots and zero compositions applied by the recomposer (which covers the inside of
`StopsMap`); a chrome composable that reads `moving` recomposes twice per gesture and `MotionGate.frozen` follows.
A caller must pass a stable `overlay` lambda (a new lambda per composition is a new modifier element, though it
still would not recompose per frame).

**Gesture seam for picking.** One `pointerInput` loop (no `composed`): one finger pans and flings; a second finger
may join at any time and the pair pans and zooms about its midpoint; the finger left after a pinch pans on. On the
first touch the policy's `claimDrag(x, y)` may return a `MapDragClaim`: the map then does not move, the claim gets
`onDrag` after the slop, and exactly one of `onDrop(x, y, vx, vy)`, `onTap()` or `onCancel()` (a second finger
landed: the gesture becomes a pinch). A single tap is delivered as `onTap(x, y, lat, lon)` after the 300 ms
double-tap window (or at once when the next gesture turns into a drag), with the coordinate it touched; a tap never
moves the view. A touch that stops a fling is not a tap. `onUserMove()` tells follow-me and auto-fit to let go.
Constants are the GPUI ones in dp (grab 32, double-tap slop 40, minimum pinch 12).

**Scheduling and cancellation.** `MapTileStore` listens to the camera and plans at once when it settles and at most
every 80 ms while it moves. A plan lists the raster tiles of the settled zoom over the viewport, nearest the centre
first, cancels jobs that are no longer wanted and starts the missing ones. Several raster tiles share one request to
the source (one `Deferred` per data tile with a waiter count; when the last waiter is cancelled the request is
cancelled). While a pinch or a zoom animation runs nothing new is requested; the new zoom is planned when it ends.
A failed tile is not asked again for 30 s (`RETRY_AFTER_MS`), then by itself. `setDark` clears and repaints with
the eclipse palette. Decoded tiles are kept in a second, 24 MB LRU so a new zoom repaints without another native
call. A native call that has started cannot be interrupted; its answer is dropped.

**Rasterising.** A bitmap is a *raster tile*: 512 px of slippy tile `rasterZoom/x/y`, where
`rasterZoom = round(zoom + log2(density))`, painted from the data tile that contains it
(`dataZoom = min(14, rasterZoom - round(log2(density)))`). So bitmaps are drawn at 0.71x to 1.41x and are never
blurry at rest, on a 3x phone as well as closer than zoom 14 (a 512 px bitmap of a 512 dp tile would be drawn 3x
enlarged). `PreparedTile` builds one `Path` per polygon (exterior plus holes, even-odd, so overlapping polygons and
half transparent fills never cancel) and per polyline once per buffer, with bounding boxes; cased lines share their
paths with the casing. `TileRasterizer` replays the paths that touch the bitmap with two paints built once (round
joins and caps, width by `line_width` times the density), on `Dispatchers.Default` limited to two threads.

**LRU sizing.** `ByteLru` (pure, open addressing over `LongArray`, no boxing) holds the bitmaps with a budget of
`memoryClass / 8` (at least 16 MB; a tile is 1 MB) and a floor of one and a half screens of tiles, so a small budget
can never evict what is on screen and loop. Drawing stamps a tile as used. Evicted bitmaps are reused once a newer
frame has been drawn without them.

**Stand-in rule.** Per visible tile: its own bitmap; else its nearest cached ancestor up to four zooms up, the part
of it enlarged (`stand_in`, checked case by case against the Rust test and the `tiles.stand_in` fixtures); else its
cached children and grandchildren reduced (zoom-out); else the radar look. The GPUI "radar" where tiles are missing
is its plain plate (`secondary_background`, white / `#1C1C1E`); a hairline grid in its `hairline` colour is added
along the tile edges so missing tiles read as such.

**No allocation in draw.** `TileLayerPainter.draw` uses fields for the rectangles and paints, packed `Long` ids, the
no-boxing cache and whole-pixel edges from shared coordinates (no seams). `TileLayerPainterTest` measures the
thread's allocated bytes over 500 frames of 16 to 25 cached tiles while the camera pans and zooms: 0 bytes.

**Benchmark (host JVM, Robolectric native graphics, median after warm-up).**

| Tile | Vertices | Paths | Prepare once | Whole tile into 512 px | 1/16 of it (one bitmap on a 3x phone) |
|---|---|---|---|---|---|
| fixture `osm-14-9520-6130` | 1 083 | 308 | 0.5 ms | 1.5 ms | 0.3 ms |
| made-up downtown 16 x 16 blocks | 7 746 | 2 372 | 8.8 ms | 2.6 ms | 0.9 ms |
| made-up downtown 32 x 32 blocks | 30 850 | 9 348 | 20 ms | 8.1 ms | 2.1 ms |
| made-up downtown 48 x 48 blocks | 69 314 | 20 932 | 22 ms | 16.9 ms | 4.1 ms |

The AC's budget (25 ms) holds with a wide margin. The fixture is a real tile but not a dense one (a village in a
forest, 6.5 kB), so the made-up downtown tiles were added to answer the real question; the 30 000 vertex one is
asserted, the 69 000 vertex one (above the GPUI cap of 60 000) is recorded only. The host is not a phone: E7.13 AC2
stays the device check.

**Known limits.**
- No tile seams or blur were seen in the goldens, but the only real data is one zoom 14 tile, so the screenshots show
  radar outside it and cannot show a join between two real data tiles (joins between bitmaps of one tile are tested).
- The data zoom follows the raster zoom, so on a 3x screen it changes at display zoom x.915 instead of x.5: up to
  0.42 zooms more enlargement of a data tile than GPUI before the next level loads. Lines keep the documented width.
- The theme flip clears the bitmaps: the radar plate shows for the moment the tiles repaint.
- `fit` does not handle a box across the antimeridian (neither does Rust).
- The scale bar uses the scale at the centre of the view, like GPUI, not at its own row.
- Robolectric draws only when asked, so "a frame was redrawn" is asserted through a capture; frame times need a
  device.
- `MotionGate` exposes `frozen` only; `:design` has no Frost/Liquid tier switch keyed on it for arbitrary chrome, so
  AC3 is covered up to "the gate freezes while the map moves and thaws 120 ms after".
- Map goldens are 150 to 290 kB, above the 150 kB guideline: a map is not flat chrome.

### Radar layers and picking (E7.3/E7.4)

Same package (`dev.rosty.stops.map`), self-contained pieces for the home to plug in. No Rust crate, no shared module
and no dependency changed.

**What the shell plugs in**

| What | Where |
|---|---|
| `RadarState` (immutable: pins, `RadarCrossing`, `RadarPosition`, trail, `RadarStops`, line, `RadarSaved` + `showSaved`, measure), `RadarHolder` | `RadarState.kt` |
| `rememberRadarOverlay(holder, dark)` for `StopsMap(overlay = ...)`; `RadarPainter`, `RadarPalette` | `RadarPainter.kt` |
| `RadarHitTest` (`pinAt`, `stopAt`, `savedAt`, `hitsPosition`, `metresToPx`) | `RadarHitTest.kt` |
| `PickController(ops, scope, camera, listener)`: the map's `gesturePolicy`; `state: StateFlow<PickUiState>`; `radar`; `beginMove/cancelMove/swap/clear/remove/place/setPair/restore`; `pinMenuModel(slot)`, `pinAnchor(slot)`; `enabled`; `savedStops`; `Modifier.pickAnchorOrigin(controller)` | `PickController.kt` |
| `PickOps` / `StopsPickOps(StopsOps)`, `PickListener` | `PickController.kt` |
| `presentPinMenu`, `presentCopyAsMenu`, `showThirdTapHint` on `OverlayController`; `PinMenuModel`, `PinMenuActions`, `rememberPinMenuStrings()` | `PinMenu.kt` |

Wiring: `val pick = PickController(StopsPickOps(ops), scope, camera, listener)`;
`StopsMap(camera, tiles, dark, Modifier.pickAnchorOrigin(pick), gesturePolicy = pick, overlay = rememberRadarOverlay(pick.radar, dark))`;
position, breadcrumb, stops, line and saved markers go in with `pick.radar.update { it.copy(...) }`;
`PickListener.onPinTap(slot)` -> `overlays.presentPinMenu(pick.pinAnchor(slot)!!, pick.pinMenuModel(slot)!!, strings, actions)`;
`onThirdTap` -> `overlays.showThirdTapHint(strings)`; `onSavedStopTap(id)` opens the stop; `onSnapped` is the haptic.

**Radar layers.** The state is coarse and immutable (it changes on a pick, a fix or a loaded list) with flat
`lat, lon` arrays; `RadarHolder` keeps it in a snapshot state that only the draw phase reads, plus plain fields for
the pin in the finger. So new state and a dragged pin redraw the map node and recompose nothing. `RadarPainter`
draws on the native canvas with one `Path` and a few `Paint`s that are fields, in the order and sizes of the GPUI
`scene_paths` (dp for its px): line, stop dots, saved rings on a white disc, the circle (two washed halves, dashed
ring, diameter and end dots, "L"/"R") or a plain red line when A and B make no valid circle, breadcrumb (thinned per
frame like `thin_polyline`), measure line, position with accuracy disc, pins with letter, snap ring and a caption
plate cut to 160 dp. The circle's geometry is not computed in Kotlin: `RadarCrossing.of` takes the answers of
`stops.crossing_validate` and `stops.crossing_read` (`outline_points = 48`) and the painter only projects them;
the ring radius and the accuracy disc are metres over the Mercator scale at their own latitude. Colours are the
GPUI palette on the iOS system colours of each theme. `RadarPainterTest` checks every layer by pixel, the edge
cases (empty and too-short lists, zero-size viewport, NaN/zero/planet-size accuracy, zero-length diameter, more
stops than the cap of 300, hidden saved markers) and measures a frame of all layers with 200 stops, a 500 point
breadcrumb and a dragged pin: 0 bytes over 500 frames.

**Hit tests** are pure functions of the camera and the state with the GPUI radii in dp (pin grab 32 =
`PIN_GRAB_PX` in `apps/stops/src/map.rs`, saved marker 18, position dot 24), tested on the JVM including the
antimeridian, a dense screen, ties and empty data.

**Picking.** The state machine stays in Rust. `PickController` keeps the log of `PickEvent`s and after every
gesture asks `stops.pick_replay` for the state, `stops.pick_labels` for the words ("Point A", "A-B 850 m") and
`stops.crossing_*` for the circle; the calls run one at a time, in order, off the main thread. When nothing
transient is left (no Move in progress) the log is replaced by one `restore` event so it never grows. As the map's
`MapGesturePolicy`: a tap is first offered to the position dot and the saved markers, then becomes a `tap` event
(first A, second B, third answers `full` -> `onThirdTap`); the destination is the transit stop under the finger
when `stops.snap` says `tap_snaps` for its distance, else the raw point. `claimDrag` grabs the pin within 32 dp at
touch-down (no long press) and remembers the finger's offset so the pin does not jump; while dragging, the pin
follows through `RadarHolder.dragTo` (no Rust call on the finger's path) and the circle follows through a conflated
off-thread recompute, a call or two behind; on drop the last 16 finger samples go to `stops.snap` (Rust's own
velocity tracker computes release and peak speed) with the nearest stop within 56 dp, and the answer decides
snap or free; a second finger calls `onCancel`, the pin and the settled circle come back and the map pinches.
A tap never moves the view. `MapDragClaim.onDrag/onDrop` got an `uptimeMs` argument for those samples.
Rust's own `open_menu`/`close_menu` events are not used: the overlay host owns the menu's life (a scrim tap closes
it), and sending them would make the next map tap a `menu_closed`.

**Pin menu.** Built from `stops.coord_format` and `stops.pick_save_offer`: two caption rows (decimal coordinate,
"610 m from you, NE"), Copy as... (S1: the six formats, localised names by format id), QR code (S3), Open in maps
(S4), Measure from here (S34), Save as stop (only when the gate is not saved yet), Lines (only on a transit stop),
then Move, Swap A and B, Remove. It is a `ButtonMenuEntry` anchored to the pin's window rectangle; clipboard,
QR sheet, intents, measure and saving are `PinMenuActions` callbacks for the shell.

**Tests.** `PickControllerTest` and `PickGesturesTest` run against the real Rust core on the host library
(`MapHostRust`), not a scripted fake: 19 controller cases (taps, too-close pair, tap snap in dp, Move/Swap/Remove/
Clear, place/setPair/restore, drag follow and offset, flick snap, slow drop, nudge on a stop, cancel, menu model,
anchor, a failing call, log compaction) and 7 Compose gesture cases with `mainClock.autoAdvance = false` for E7.4
AC1. Screenshots `e7.3-*` and `e7.4-*` (light and dark) render the same pieces over the fixture tile.

**Gaps found (not fixed here, they need a bridge op)**
- **No op gives the transit stops in view or the stops of a pattern.** `stops.gtfs_search` needs a non-empty
  query, so today Kotlin cannot get the coordinates to draw stop dots, to snap to, or to draw the chosen line.
  `RadarStops` and `line` are inputs the shell must fill; a `stops.gtfs_view {bbox, cap, pattern?}` op (the GPUI
  `stops_in_view` and pattern lookup) is the missing piece and belongs to E2.
- The texts from Rust (`pick_labels`, the hint, `coord_format`'s "from you") are English only; the menu's own
  words and the format names are localised (en + tr).

**Known limits.** The pin menu can cover its pin when there is no room above or below (the design system's
placement). Captions and pin letters use dp, not the font scale. The circle lags the dragged pin by a Rust call.
The breadcrumb is thinned per frame rather than cached per zoom. The toast of the third-tap hint is placed by the
overlay host; in the test window it sits over the attribution, which the shell's bottom chrome will move.

### Transit stops in view: `stops.gtfs_view` (closing the gap of E7.3/E7.4)

The gap named above is closed end to end, in three commits (Rust, `:core-bridge`, `:stops`).

**Rust (`rosty_geo::gtfs`).** `BBox {south, west, north, east}` and `Feed::box_hits(bbox, cap) -> (indices, total)`
(`stops_in_box` for the indices alone): the stops inside the box, edges included, at most `cap`. When more are
inside, those nearest the middle of the box win (longitude measured the short way round and scaled by the cosine of
the middle latitude, as the GPUI `stops_in_view` did around the view centre), ties to the lower index; the answer is
in ascending feed order, so one view always answers the same stops. `west > east` is a box across the antimeridian;
180 and -180 are one meridian; a box 360 degrees wide or more takes every longitude; `south > north` is read the
other way round; a NaN or infinite edge holds nothing; `cap = 0` answers nothing but still counts. It takes a box,
not a `Viewport`, because the Kotlin camera is Mercator and knows its own corners. `Feed::pattern_line(pattern)` is
the pattern's stops in travel order, `None` for an unknown pattern, skipping stops the feed lacks. Ten unit tests
(inside and edges, empty feed and empty box, bad edges, antimeridian in three spellings, world-wide box, poles, cap
order and determinism on 2 000 stops, pattern line, missing stop, empty pattern).

**Op (`rosty_ffi`).** `stops.gtfs_view {bbox, cap, pattern?, dir?}` reads the saved `gtfs.json` and answers
`{has_feed, total, capped, stops: [{index, id, name, coord}], line}`; `line` is null without a pattern and for an
unknown one. Six contract fixtures (main, `capped`, `unknown_pattern`, `antimeridian`, `empty_box`, `no_feed`),
`INDEX` and `OPS.md` updated.

**Kotlin (`:core-bridge`).** `GtfsBbox`, `GtfsViewRequest`, `GtfsViewStop`, `GtfsViewAnswer`; `StopsOps.gtfsView`,
`NativeStopsOps`, the `ops` list; the strict contract test decodes all six pairs; `testRustBridge` imports
`gtfs-tiny.zip` with `save` and asks views of it (all stops, both patterns, a smaller box, a cap, an unknown
pattern, a box the long way round, and no feed).

**`:stops`.** `PickController` listens to the camera. When the view is at rest (never while a finger or an
animation moves it) and `TRANSIT_DEBOUNCE_MS` (150 ms) later it asks for the stops in the viewport plus 8 dp, at
most `RadarStops.CAP` (300), and the line of the pattern a picked end carries, and writes them to
`radar.stops` / `radar.line`. The flow is `collectLatest`: a view that changes again first cancels the pending
request and a late answer is never shown. The same request as the one shown is not sent again; without a feed the
stops are left alone; `refreshTransit()` asks again after an import; `close()` stops listening. A view as wide as
the world asks for every longitude, a view over the antimeridian sends `west > east`. Tested against the real Rust
core with the tiny feed (stops follow the camera, a tap snaps to a stop the feed put there, the line of a picked
pattern, debounce and no request while moving, an overtaken request, the two box edge cases, refresh and close).

**Note for the merge.** `StopsOps` has a new abstract method: any other implementation of the interface (a fake in
another module) must add `gtfsView`. `PickOps` likewise gained `gtfsView`.

### Line-aware alarm data

**What the "N stops before" alarm is today.** It no longer exists as an alarm rule, in the GPUI app either.
`docs/architecture.md` AD-ST6: GTFS "names stops (a point source for A or B) and draws them and their lines on the
map; it has no alarm rule", and "the earlier radius, ETA and 'N stops before' rules are gone" (the trigger is the
crossing of the circle on AB). `_bmad-output/plans/stops-crossing.md` turned the stop sheet's "N stops before" rows
into "Use as A" / "Use as B". What is left of it in `apps/stops` is: the list of lines that call at a stop, each with
"N stops earlier on this line"; choosing one draws that line on the map and stores its pattern on the picked point
(`Destination.pattern`). `trip_ui.rs` computes nothing line-aware: at trip start it only keeps
`b.pattern.or(a.pattern)` as the line to draw. **So `stops.trip_start` needs nothing new**: `TripStartRequest` takes
A and B and that is the whole alarm. The parity row "the line-aware 'N stops before' alarm from imported GTFS" is,
in today's product, the stop sheet's line list plus the drawn line.

**Moved to `rosty_geo::transit`** (from `apps/stops/src/transit.rs` and `picker.rs`; the app re-exports and calls
them): `route_name`, `natural_key`, `LineChoice`, `lines_through`, `MAX_LINES`, `grouped`, `feed_summary`,
`civil_from_days`, `date_text`, `imported_text`, `line_title`, `earlier_text`. The file import/remove and the GPUI
sections stay in the app. Their five tests moved with them and fourteen were added: empty feed and unknown stop,
a stop on no pattern, a stop first on every pattern, loops (the first call at a stop counts; a ring's terminal is
its first stop, so its closing call is not offered), forty variants of one line collapsing to the longest, ties,
the limit in reading order, a pattern with a missing route, route name fall-backs, natural order with letters,
empty names, leading zeros and numbers too big for the key, grouping, the civil calendar around leap centuries,
dates before 1970 and at `i64::MIN`/`MAX`, and offsets from -12 to +14 including quarter and half hours.

**What the trip UI (and the stop sheet, Settings) should call**

| Need | Call | Answer |
|---|---|---|
| The lines at a stop, for the stop sheet / line picker | `StopsOps.gtfsLines(GtfsLinesRequest(stop = index))` or `(stopId = dest.gtfsStop)` (`limit`, `dir` optional) | `GtfsLinesAnswer(hasFeed, stop: GtfsViewStop?, lines: List<GtfsLineChoice>, maxLines)`; each `GtfsLineChoice(pattern, name, headsign, before, title, earlier)`: `title` = "Line 34 to Kadıköy", `earlier` = "3 stops earlier on this line" (ready to show; English) |
| Choosing a line | no call: put `line.pattern` on the point, `Destination(label, coord, gtfsStop = stop.id, pattern = line.pattern)`, then `PickController.place(slot, dest)` | the map draws the line by itself: `PickController` asks `stops.gtfs_view` with the pattern of A (else B) |
| "Choose another line" | `place` the same destination with `pattern = null` | the line disappears |
| Starting the trip | `StopsOps.tripStart(TripStartRequest(id, a, b, startedMs, notice?))` with the endpoints from `PickUiState` / `pick_labels` | unchanged; no line, no "N stops" field. Keep `ends.b?.pattern ?: ends.a?.pattern` yourself if the line should stay drawn during the trip and put it back with `pick.radar.update { it.copy(line = ...) }` from `gtfsView(GtfsViewRequest(bbox, cap, pattern))` |
| Settings > Transit data row | `StopsOps.gtfsSummary(GtfsSummaryRequest(nowMs, tzOffsetS, skippedRows?))` | `GtfsSummaryAnswer(hasFeed, summary = "1 234 stops, 87 lines", imported = "Imported 5 Nov[, 1 200 unusable rows skipped]", importedMs, stops, routes, patterns)` |
| After an import | `StopsOps.gtfsImport(GtfsImportRequest(zip, nowMs, save = true))`, then `gtfsSummary(..., skippedRows = answer.report.skippedRows)` and `pick.refreshTransit()` | the import's own failure text is the `RustError` message (`GtfsError` verbatim) |

`tzOffsetS` is seconds east of UTC (`TimeZone.getDefault().getOffset(nowMs) / 1000`).

**Found on the way, left as they are (parity)**
- `lines_through` counts the stops before the *first* call at a stop, so the terminal of a ring line (first and last
  stop of the pattern) is offered no line. Changing it changes what GPUI shows.
- `imported_text` says "1 unusable rows skipped" for one row.
- All of these texts are English (Rust has no localisation), like `pick_labels`.
- The GPUI Stops app compiles (`cargo check -p rosty_stops --profile checks --all-targets`) but its test binary does
  not link on this machine (`-lxkbcommon` is not installed), so the app's own remaining tests were not run here.
- **The shared `target/` is unsafe between worktrees**: cargo keys artifacts by workspace-relative path, so a crate
  built from the other worktree looks "fresh" here. `rosty_ffi` was compiled against the other worktree's
  `rosty_geo` (without the new module) until the sources were touched. Touch the crate sources before building, or
  give each worktree its own `CARGO_TARGET_DIR`.

### R1 gap fixes

- **Arrival clock (S41).** The status the service writes (`TripFixAnswer`, read by the card through `:platform`
  `TripRepository`) and the interactive `FixOut` now carry `eta_min`: the same ETA the status text is built from,
  rounded up and never 0 (`rosty_geo::session::eta_minutes`). It is absent when there is no ETA (standing still,
  no speed yet), and optional on both sides, so a status written by an older build still parses. Kotlin:
  `TripFixAnswer.etaMin: Int? = null`, `FixOut.etaMin: Int? = null`.
- **`stops.store_trip_clear {dir}`** deletes `trip.json` under the trip lock and answers `{removed}`; a missing file
  is `removed: false`, not an error. No history record is written, which is the point: a start Android refused
  never was a trip. Kotlin: `StopsOps.storeTripClear(DirRequest): Outcome<StoreTripClearAnswer>`. **Fakes of
  `StopsOps` must add `storeTripClear`.**
- **Note on a saved stop (S44).** The only spec text is `_bmad-output/plans/util-stops-r6.md` ("free text shown in
  the row and share text"); GPUI never built it. `SavedStop.note` is a string, absent from `saved.json` when empty
  (so the file stays v2 and reads both ways), collapsed to one trimmed line, at most 200 characters
  (`note_too_long` otherwise, nothing is cut silently on edit). `saved_edit` gets `set_note {id, note}` (an empty
  note clears it). The note appears in the list share text (an indented line under the stop), in the stop share
  text when `dest_share` is given `note` (last line; parsing a shared text still yields name and points only), in
  the JSON export/import (an imported note longer than the cap is cut) and in the backup. GPX has no note.
  Kotlin: `SavedStop.note = ""`, `SavedEdit.SetNote`, `IncomingStop.note = ""`, `DestShareRequest.note`.
- **Fit in a tiny free area.** `MapCamera` never works with less than `MIN_FREE_DP` (120 dp) on an axis. Padding
  that leaves less on an axis is ignored on that axis, for `fit`/`fitPoints` and for the focus point alike
  (`centreOn`, travel), so content lands in the middle of the viewport, partly under the sheet, instead of the fit
  zooming out to 2000 m/dp around a 1 px strip. A margin that would leave less than the minimum is shrunk. The
  rule is per axis: in landscape under a sheet the height falls back while the width keeps its padding. Tested with
  an 891 x 411 dp window and a medium sheet (60 dp left), the exact threshold, oversized margins and padding, and
  a viewport smaller than the minimum.
