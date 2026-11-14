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
