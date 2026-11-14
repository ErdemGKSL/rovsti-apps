# E7 R1 approach notes

## Approach (written by the subagents)

### App shell (E7.1)

**Module.** `:stops` (`dev.rosty.stops`, applicationId unchanged, versionCode `100 + build number`). Files: `RostyStopsApp`
(lazy `StopsContainer`, `PlatformComponents.install(MainActivity)`, `PlatformChannels.followLocale`), `MainActivity :
RostyShellActivity<StopsRoute>`, `StopsContainer : ShellContainer`, `BootReceiver`, `StopsRoute.kt` (routes, tabs, links),
`StopsApp.kt` (one `RostyShell`), `home/` (map home), `data/` (prefs store, common settings, tile source), `settings/`.

**Routes.** The navigator has **one stack** (`TripTab`, root `StopsRoute.Home`). Pushed over the map: `Settings`,
`Appearance`, `About`, `Licences`, `LicenceDetail(id)`, `Logs(tag)`. `chromeFor` hides the host's bar on every route:
the map draws its own bar, pushed pages are full pages with the shell's glass back button. The GPUI tabs (Trip, Saved,
Settings; icons navigation, bookmark, gear) are the home's own `RostyTabBar`, folded to the selected circle: a tap
unfolds it, Trip closes an open sheet, Saved opens the Saved sheet over the map, Settings pushes the Settings page.
Saved, History and Pick are **sheets** (overlay entries), never routes, so the map stays the full window.

Deviation to decide: the F16 owner rule ("every pushed page keeps the full tab bar") is not applied to Stops' pushed
pages (Settings and its sub-pages have no bar). The host can only draw a bar of navigator tabs, and Stops' tabs are not
navigator tabs. If the owner wants the bar there too, the cheapest way is a `selectedTabOverride` on `RostyNavHost`.

**Links.** `stopsLinkFor(action, dataUri, extras): StopsLink?` (pure, `StopsRoutesTest`): a stop shortcut
(`Shortcuts.EXTRA_STOP_ID`, Long, from `StopsOps.savedShortcut`/`ShortcutAnswer.stopId`) wins, then VIEW of `geo:` or an
https link on the GPUI manifest's hosts (`maps.google.com`, `www.google.com/maps*`, `www.openstreetmap.org`,
`maps.apple.com`), then SEND `EXTRA_TEXT`. `stopsRouteFor` maps any link to "reset to the map". The activity puts the
link into `container.pendingLink` and removes the shortcut extra (used once, as GPUI `deliverOpenStop`); the home
consumes it: Stop opens the Saved sheet (E7.7 selects the stop), Place opens the Pick sheet with the text (E7.8 turns it
into the Confirm sheet through `stops.dest_parse`).

**Container.** `ops: StopsOps` (`NativeStopsOps`), `tilesOps`, `store: StopsStore` (`stops-prefs.json` as a flow, writes
under a mutex), `commonSettings: StopsCommonSettings` (appearance from the prefs file, S11; the file has no haptics field,
so that flow is null and the row is hidden), `tiles: StopsTiles` (one `TileHandle` for `prefs.tileUrl` (null =
VersaTiles) and `tile_cache_mb`, reopened when either changes; cache root `<noBackupFilesDir>/tiles`, where the GPUI app
kept it, so cached tiles and offline areas survive the update), `location` / `trips` from `:platform`, `homeData`
(`CoreHomeData`), `pendingLink`. Every member lazy with a `create*` seam; tests use `TestStopsContainer` over a
`FakeRustCore`.

**First screen state.** `CoreHomeData.load()` reads `stops.prefs_load` (via the store), `stops.store_trip_load` (which
renames an old `trip.json` to `trip.legacy.json`, AD-SX8), `stops.store_read` for `saved` and `history`, and
`stops.gtfs_search` for the feed date; `homeStateOf(snapshot)` (pure) builds the `@Immutable HomeState` (trip summary,
saved stops, history count, transit date, tile host, S40 flag). `HomeViewModel` is activity-scoped.

**Home layering** (`home/HomeScreen.kt`, `StopsHome`):
1. `mapLayer(Modifier.fillMaxSize(), camera)` inside a `BackdropScope`, edge to edge under both bars. `typealias MapLayer =
   @Composable (modifier: Modifier, camera: MapCameraPadding) -> Unit`. Default `RadarMapLayer` (flat land tone + faint
   grid, the radar background E7.2b also shows for missing tiles); tests pass `HomeTestMap`. **E7.2a plugs the real map
   in here** (`StopsApp(mapLayer = ...)` default), keeping the test tag `HomeMapTag` on its node.
2. Chrome (siblings after the recording, so glass samples the map): top `SearchPill` (48 dp glass capsule, opens Pick)
   and a one-line `ChipRow` slot (empty until E7.8's nearest chip); bottom block = button column (locate/follow, layers,
   map options: `GlassIconButton`s, no-ops until E7.9) over the home's folded `RostyTabBar`.
3. The map sheet: a non-modal, non-dismissible `SheetEntry` in the root overlay host with detents Peek 120 dp / Medium /
   Large (`compactLargeOnly = false`, a small `:design` addition, so landscape keeps peek and medium). Presented while the
   home is composed, dismissed when a page is pushed, re-presented on the detent it had (saved index).

**Sheet ride and camera padding contract.**
- The bottom block rides the sheet with `graphicsLayer { translationY; alpha }` only (no relayout): `sheetVisiblePx`,
  `sheetRideTranslation`, `sheetRideAlpha`, `rideMaxVisiblePx` (pure, allocation-free, `SheetRideTest`). It keeps an 8 dp
  gap above the sheet up to the medium detent (or less when a short window would push it under the pill), then fades
  over 96 dp; at Large the sheet covers it.
- `MapCameraPadding.insets: PaddingValues` (state) = top: the pill/chip column's bottom edge; end: the button column
  width + 8 dp; bottom: the sheet's **settled** detent height. It is written from a `snapshotFlow` over
  `SheetState.settledOffset` and the measured chrome, so it changes on settle and on resize, never per frame
  (`HomeGestureTest` drags the grabber and checks the count). The map engine reads it to fit/centre the camera.

**Placeholders until later stories.** Map: radar background (E7.2a/b). Sheet body: title, trip-in-progress line or the
A/B hint, rows Saved Stops (count), History (count), Transit Stops (when a feed exists) (E7.5 live card, E7.7, E7.11).
Saved, History, Pick sheets: "Coming soon" empty states (E7.7, E7.11, E7.8). Map buttons and chips: no-ops (E7.9, E7.8).
Settings: Map group with the tile server host, then `CommonSettingsGroup` (Appearance, About with the 10 s developer-mode
hold, Logs in developer mode); E7.10 adds alarm, units, tiles, GTFS, arriving-soon and WhatsApp.

**Manifest.** Rebuilt from the GPUI manifest: the 14 permissions of `stops.permissions.txt`, launcher + `geo:` + four
map-link hosts + SEND text/plain, `dev.rosty.bridge.{TripService (location FGS), TripWatchdogReceiver, AlarmActivity
(showWhenLocked, turnScreenOn, singleInstance, excludeFromRecents), TripAlarmReceiver, RostyShareProvider}`,
`.BootReceiver` (BOOT_COMPLETED, ends a stale trip with headless `trip_end` cancelled, as GPUI), `allowBackup=false`,
`@string/launcher_name` (Stops / Duraklar), `enableOnBackInvokedCallback`, `adjustResize`. No `tools:node="remove"`
needed: every permission `:platform` mirrors is one Stops uses. `StopsManifestTest` (merged release manifest) and the
Rust `stops_kotlin_contract.rs` (AC4) pin it.
