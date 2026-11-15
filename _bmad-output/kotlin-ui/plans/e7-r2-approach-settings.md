# E7 R2 approach: Settings (E7.10 without WhatsApp)

Branch `stops-settings` (worktree `wt-set`), on `stops-kotlin` c010671 (merged in).

## What exists

**Pages** (`android/stops/.../settings/`), all on `:shell` `SettingsPage` / `IosSettingsGroup`, pushed over the map with
the full Trip / Saved / Settings bar (F16):
- `StopsSettings.kt` - Settings root. Groups: **Trip** (Alarm row, summary under the title), then the WhatsApp slot
  `extraGroups` = `ArrivingSoonSettingsGroup` (see Hook below), **Map** (Map Tiles row with the host under it; while a
  download runs, a progress row with bar and Cancel), **Transit Data** (feed summary + Replace + Remove, or Import),
  then `CommonSettingsGroup`.
- `AlarmSettingsPage.kt` - route `StopsRoute.AlarmSettings`: Sound / Vibration switches, Alarm Sound (system picker),
  Snooze (tap cycles), Volume choices + Test Volume, Vibration Pattern choices (preview buzz on choose), Test Alarm,
  Reset Alarm Settings (only when `alarm_is_default` is false; confirm alert; Undo pill).
- `TilesSettingsPage.kt` - route `StopsRoute.MapTiles`: URL field (`GlassTextField` = `BasicTextField(TextFieldState)`,
  state owned by the view model, wraps to 3 lines), Test and Save, Reset to Default (Undo), Downloaded Maps (this
  server, Other Servers + Clear, Clear Downloaded Maps; confirms), Cache Size choices (shrinking below the usage asks),
  Offline Area (estimate, Download with confirm, progress + Cancel, a done row).

**View models** (activity-scoped, like the home's): `SettingsViewModel` (root + alarm + transit), `TilesViewModel`.

## Rules stay in Rust
- Every preference change is one `stops.prefs_edit` (`StopsStore.edit`). A value picked from `stops.prefs_choices`
  (volume, pattern, cache size) is reached by applying Rust's own `next_*` edit until the prefs hold it (in memory,
  one write); Kotlin never writes a chosen value itself. Sound/vibration "never both off" is Rust's `toggle_*`
  answering `changed: false`; the page shows a toast. `alarm_is_default` decides the Reset row.
  `StopsStore.describe()` answers the derived fields of the held prefs (a no-op edit).
- Volume test / test ring play `stops.alarm_plan`'s `plan` / `test_ring` JSON through `:platform` `AlarmPreview`.
- Tile server: `tiles.test_server` first, then `prefs_edit set_tile_url`; failures are worded from `detail.reason`
  (`serverError`, our strings en + tr), never the English message.
- Transit: `stops.gtfs_import {save}`, `stops.gtfs_summary`, new `stops.store_gtfs_remove`. A GTFS parse failure has no
  reason code: its message is shown inside our sentence ("This feed could not be read: ...").
- Offline estimate: new `tiles.offline_plan {bbox}` (the same planner as `tiles.offline_start`).

## New Rust / bridge
- `tiles.offline_plan` (rosty_ffi tiles.rs; planning factored into `plan_area`), `stops.store_gtfs_remove`
  (+ `rosty_app_core::stops::store::remove_gtfs`), fixtures, INDEX, OPS.md; Kotlin `TilesOps.offlinePlan`,
  `StopsOps.storeGtfsRemove`, contract pairs.

## Offline area download (S10)
- `offline/OfflineDownloads` (container `offline`): `start(area)` -> `scheduleOffline` (WorkManager unique expedited
  work `offline-area`), `run(area)` (the worker's part: `tiles.handle()`, `startOffline`, follows `tiles.offline`
  events), `cancel()` (`OfflineJob.cancel`, tiles in flight stay cached; a queued request is withdrawn), `dismiss()`.
  `state: StateFlow<OfflineState>` (Idle, Starting, Running, Finished, Failed) is what Settings and the notification draw.
- `offline/OfflineAreaWorker` (CoroutineWorker): `setForeground` as `dataSync` (refusal logged, the run goes on),
  `OfflineNotices` on the new low/silent channel `map_download` (trip glyph, accent, glass large icon via
  `NotificationIcon.apply`), Cancel action -> `OfflineCancelReceiver` (not exported), a closing notification under its
  own id. R8 keep rule for the worker constructor.
- Manifest: `FOREGROUND_SERVICE_DATA_SYNC`, `SystemForegroundService` with `foregroundServiceType="dataSync"`
  (`tools:node="merge"`), the receiver. dataSync because it moves user-chosen data (map tiles) from the network to the
  phone. `stops.permissions.txt` and `StopsManifestTest` updated.
- The area is the map's view as the map leaves the screen: the home writes `container.mapView` on dispose (`SettingsHooks` in `home/HomeMap.kt`,
  `visibleArea(camera)`; a collector following the camera shifted the E7.5 golden timing, so it is read once); before the map was shown in this run the row says "Open the map first".

## Hooks for others
- **WhatsApp (Fable):** `settings/ArrivingSoonSettings.kt` `ColumnScope.ArrivingSoonSettingsGroup(container, onOpen)`
  draws nothing today; fill it with the arriving-soon group and the WhatsApp row (add routes to `StopsRoute`,
  `chromeFor`, `StopsApp`). Prefs through `container.store.edit(PrefsEdit.SetNotice / SetNoticeWhen / SetTemplate /
  ClearNotice)`.
- **Home:** `container.transitVersion` is bumped after an import or removal; `SettingsHooks` calls
  `pick.refreshTransit()`. `container.mapView` as above.

## Gaps / decisions
- **S43 units:** there is no unit preference in Rust (`StopsPrefs` has none; util-stops-r6 lists S43 as backlog R7,
  "touches every distance string"). Not built in Kotlin; needs a Rust field + edit + the distance texts first.
- Rust labels (`prefs_choices` vibration labels, `gtfs_summary` texts) are English: the pattern names are our strings
  keyed by the enum; the feed summary stays the core's (Fable's localisation work).
- Clear refuses during a trip (GPUI parity) by reading `stops.store_trip_load`.
