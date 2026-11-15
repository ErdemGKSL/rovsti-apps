# E7 R2 approach notes (app: E7.8, E7.9, E7.11, E7.12)

Branch `stops-app` (worktree `../wt-app`). Every rule is an existing op; what has no op is listed under "Missing rules"
with the hook left for it.

## E7.8 Pick sheets (`dev.rosty.stops.pick`)

- `PickSheetViewModel` (activity-owned): one field; rows from `stops.dest_parse` + `stops.pick_labels` (the typed point,
  the empty line), `stops.gtfs_search` (stops nearest first, line counts), `saved_list` (saved stops), `history_recent`
  (recent pairs and points); 150 ms debounce. Paste reads the clipboard on the tap only. A pasted stop share fills A and
  B; a point asks to confirm; other text goes to the field.
- `PickSheets.kt`: the pick sheet, the Confirm sheet (shared links, `geo:` intents), the S7 stop sheet (lines from
  `stops.gtfs_lines`, Copy name / code, Share `dest_share`, Copy as `coord_format`, Use as A / B), the Copy as sheet.
- `Picker` glues it to the home: the search pill opens it, `StopsLink.Place` offers the text, placing is blocked while a
  trip runs. S37 nearest chip from `saved_list.nearest`.

## E7.9 Map tools (`dev.rosty.stops.tools`)

- `MapTools` is the map's gesture policy around the `PickController`: while measuring, a tap adds a point and every drag
  pans; otherwise the pick controller has the map. Any user pan ends following.
- S17 follow: the Me button asks `view.follow` (`me_tap` / `pan`); Centred puts the fix at street scale, Following
  re-centres on every fix (`radar.position`), the button is tinted and toasts say Following / Stopped following.
- S25 My location: the Me button held or the blue dot tapped (`PickListener.onPositionTap`): Copy as, Share, QR, Use as
  A / B.
- Layers popover: S40 saved stops on map (`stops-prefs.json show_saved_on_map`), the measure tool. Map options popover:
  S11 the common Appearance (`StopsCommonSettings`, the Settings page's own setting), S42 copy map link / share view
  (`coord_format` with `zoom`: `view` links) / copy centre as (the middle of the free area).
- `MeasureTool` (S8, S26): points in tap order; `stops.measure` answers legs, total, GPX, file name, the gate and the
  point cap; distances from `format_distance`. The card under the search pill: summary, Done, Undo last, Clear (Undo),
  More (copy, GPX "Save as", Save as stop). Pin menu "Measure from here" starts it.

## E7.11 History (`dev.rosty.stops.history`)

- `HistoryStore` (`history.json`, edits through `stops.history_edit`, `replace` for Undo / restore); read again on every
  open (the trip service appends records).
- `HistoryViewModel`: `history_list` (query, filter), `stats`, `history_prune_plan`, `history_text` (S33 share / copy),
  `history_export` (S16, the rows on screen). Effects go to `rememberHistory` (home): toasts, Undo, share, "Save as".
- `HistorySheet`: statistics, Trips header, search field, All / Arrived / Cancelled chips, trip rows (date, Rust summary
  line, outcome); a row opens its menu; the header "..." exports, backs up, restores, prunes, clears.
- S32 `Backup`: `backup_export` file, `backup_parse` then a confirm sheet; restore writes the saved stops, the history and
  the prefs and answers the old three for Undo. Blocked while a trip runs.

## E7.12 Import / export (`dev.rosty.stops.saved`)

- `PlacesTransfer`: `places_export` (GPX, JSON), `places_import` as a plan, then applied on confirm; Undo writes the old
  list back (`SavedStore.replace`). `importSummary` words the plan in the app's strings. The Saved sheet's "..." menu.

## Files and SAF

`dev.rosty.stops.files.rememberTextExport` / `rememberTextImport`: `:platform`'s `ExportDocument` / `ImportDocument` and
`FilesRepository.writeExport` / `readUri` (E3.8a).

## Shared home files changed

`HomeEntry.kt` (picker, map tools, history, saved files wiring), `HomeScreen.kt` (`HomeActions.onLocateLong`,
`onLayers(Rect)` / `onMapOptions(Rect)`, `topCard`, `following`, Me button with long press). New in `home/`:
`HomeTools.kt`, `HomeHistory.kt`. `Picker.place` is public.

## Missing rules (hooks left, Rust to add)

- GPUI `looks_like_link` (when a refused typed query earns an explanation): `PickRules.showRefusal`, off.
- GPUI `clear_step` (Clear asks a second tap above two points): not ported; Clear offers Undo instead.
- GPUI `history_field_visible` (search field above five trips): the field always shows.
- GPUI prune / clear two-tap confirms: Undo instead.

## Evidence

Screenshots in `android/stops/screenshots/` (`e7.8-*`, `e7.9-*`, `e7.11-*`, `e7.12-*`, light and dark, Turkish and fs2
where text matters); tests `PickSheetViewModelTest`, `PickAppTest`, `MeasureToolTest`, `MapToolsAppTest`,
`HistoryViewModelTest`, `HistoryAppTest`, `BackupTest`, `PlacesTransferTest`.
