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

### Map integration, saved stops, trip, alarm

**Map on the home (052dd08).** `home/HomeMap.kt` builds one `MapCamera`, a `MapTileStore` over
`StopsContainer.createTileSource()` (a `HandleTileSource` on `StopsTiles`, `FakeTileSource.city()` in tests) and the
`PickController` as the map's gesture policy; all three close with the screen (the tile handle in the container
scope). `StopsMap` gets `rememberRadarOverlay`, `pickAnchorOrigin` and the theme's dark flag; `MapMotionEffect` feeds
the chrome's `MotionGate` (the glass keeps its last capture while the map moves: a stand-in for a Frost tier, which
:design has no switch for). The settled sheet detent becomes `camera.setPadding` (`FollowCameraPadding`). The first
view is pure (`firstView`): the trip, else the last fix at street scale (GPUI `STREET_MPP` 1.5 m/px, changed in
8aedd9a, so the idle home draws the map and not the radar grid), else the saved stops, else the default city. Live
position runs only while RESUMED and with the location grant (`LivePosition`). The scale bar and attribution ride
with the bottom chrome; toasts rise above the riding block and the settled sheet, but not past the window middle
(`toastBarPx`, pure, tested). Hint pill / "Point A" / "A-B 850 m" come from `PickUiState`. The pin menu acts for real
(clipboard, share, open in maps, QR sheet, save as stop); measure-from-here (E7.9) and show lines (E7.8) are no-ops.

**F16 (2dd9779, 5d90d2f).** `RostyNavHost` takes an optional `TabSelection` (selected id from the route, taps to a
callback; default unchanged, `TabSelectionTest`). Stops' pushed pages show the full bar with Settings selected; Trip
pops to the map, Saved pops and opens the Saved sheet (`container.pendingSheet`).

**Saved stops, E7.7 (8aedd9a).** `data/SavedStore` holds `saved.json`: every change is one `stops.saved_edit`,
written back with `stops.store_write` under a mutex; the flow feeds the map markers and the pick controller.
`saved/SavedViewModel` lists through `stops.saved_list` (search above five stops, sort saved/name/nearest, nearest
distances, the list text) and words rows with `stops.format_distance` and `stops.history_usage`; share and QR use
`stops.dest_share`, the shortcut `stops.saved_shortcut` + `AndroidShortcutsRepository.pinStop`; "Save as stop" uses
`stops.saved_suggest_name`. Refusals are worded from `detail.reason`. Reorder uses :design's `ReorderList`
(`libs.compose.reorderable` through :design's `api`); a drop becomes a group-relative `MoveBy` (`groupDelta`, pure).
Move up/down are dimmed at a group's edge (`canMove`, pure). A menu trigger inside a clickable row consumes its tap.
Gaps: S44 note (no `note` on `SavedStop`, backlog R7: needs a Rust model + `saved_edit` variant + bridge field; the
`e7.7-saved-edit-note-field` shot is `e7.7-saved-edit-name-field`, the rename field). S19 reverse/duplicate was dropped
upstream (the gate is direction-agnostic).

**Trip, E7.5 (d18165d).** `trip/TripViewModel` starts with `ActiveTripHandle.start(dir = container.dataDir)` (writes
`trip.json`), closes the handle, and starts the service with `TripSpec` (first fix 5 s, high accuracy, the
`stops.alarm_plan` JSON). A refused start leaves nothing: the bridge has no "delete trip file" op, so the trip is
ended and its record taken out of the history again (`stops.store_trip_end` + `history_edit Take` + `store_write`):
a dedicated op would be cleaner. The live card follows `TripRepository.status`; S9 comes from `stops.coord_format`
`from_you`; S20 restores a handle from `trip.json` for `trip_status_share`. Rows: AD-SA8 restart (Resume, and an
automatic resume on ON_RESUME), foreground lost, start refused, and a grant revoked while running (the trip stays,
`PermissionRow` asks again). Preflight is a Kotlin twin of GPUI `Preflight` (`trip/Preflight.kt`, JVM tests):
location then notifications block; background location (offered only after foreground, AD-SA7), full-screen and
battery are recommended `PermissionRow`/`SpecialAccessRow`s. Line-aware (AD-ST6): the lines at a transit end
(`stops.gtfs_lines`) as a menu; a choice is `pick.place(slot, dest.copy(pattern))`. `e7.5-line-aware-alarm-setting`
is `e7.5-line-choice-in-stop-ui`. The arriving-soon toggle is the `ArrivingSoonHook` stub (no notice, no row) for the
:wa work. S41 arrival clock: the headless `trip_status` fix has no `eta_min`, so the card shows the service's text
("about 3 min"); `stops.arrival_clock` needs that field on the status (bridge change).

**Alarm, E7.6.** `trip/AlarmControl` wraps `TripAlarm.dismiss/snooze` and the `trip_alarm` events (parse is pure);
`AlarmViewModel` shows the in-app alarm (an `OverlayEntry` over everything, Back does not close it) on `ringing` or
the service's ring fix, with the snoozes left from the restored trip and `snooze_min` from the prefs. Dismiss: S21
pill "Alarm dismissed" with Re-arm (same A and B through the preflight). Snooze / time-out: "Snoozed. Ringing again
in N min." S27: the last trip card from `stops.history_list` `last_trip` (Start again, Save as stop, Share via
`stops.history_text`, Close hides it). `e7.6-ring-again-menu` is the Re-arm pill (GPUI has no ring-again menu).

**Tests.** The Saved, trip and alarm tests run the real host core: `HostCore` starts `Rust.init` once in a temp data
dir; `HybridCore` answers the fake's scripted file ops and sends every other op to the real core. Screen tests settle
with small clock steps plus short sleeps (`settleRust`); `performScrollTo` hangs under the manual clock, so taps use
semantics clicks or expand the sheet first. Shot passes clear the activity's `ViewModelStore` between light and dark.
