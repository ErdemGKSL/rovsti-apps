# UX completeness pass: Rovsti Stops (round 2)

Base: `223537392be9714fe722b4be7cb9e13d4c751faf` (main after merge). No stash, clean tree.
Round 1 (`ux-stops.md`) is done: save from the pin menu with a prefilled name, saved names shown
on re-pick, name fields in the confirm and stop sheets, Saved tab rename / reorder / delete with
undo, History reuse and clear undo, Trip-tab Clear undo, picker placeholder.
Scope: `apps/stops`, `crates/rovsti_geo`, `crates/rovsti_tiles`. No new product features, no
backends. Pure logic first, tested; GPUI glue thin; only GPUI and `rovsti_android` APIs that already
exist (`telecom::share_text`, `ClipboardItem` + `cx.write_to_clipboard` as used in Messages/Phone).

## Re-audit: remaining gaps

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| R1 | **High** | The chosen A and B (before Start) are kept only in memory. Picking A on the map, then leaving to share B from a maps app, can lose A when Android kills the process. Nothing restores it. | `main.rs` load, `backend.rs`, `store.rs` |
| R2 | **High** | Stop on the live card ends the trip with one tap and no way back. `begin_trip` cleared the pair, so after a stray tap the user must rebuild A and B (or find them in Recent trips) while on the bus. | `trip_ui.rs::stop_trip`, `live_card` |
| R3 | Medium | Recent trips and Recent points in the picker, and History's "Use", build `Destination::new(label, coord)` straight from history. A saved place that was renamed shows its old name, its GTFS stop is lost, and an unnamed point comes back literally named "Point A" (even when it is used as B). | `picker.rs::pick_rows`, `settings_ui.rs::history_item` |
| R4 | Medium | "Remove transit data" deletes the imported feed with one tap. Re-importing means finding the zip again. No confirmation. | `transit.rs::transit_section/remove_feed` |
| R5 | Medium | Settings resets have no undo: "Reset to default" (map tiles) drops a typed custom URL, and "Turn off" (arriving-soon message) drops the chat, template and timing with no pill at all. | `settings_ui.rs::reset_tiles/turn_notice_off` |
| R6 | Medium | A saved place cannot be shared or copied out of the app (round-1 G9). The seam exists after all: Stops' Android build includes the shared bridge (`telecom::share_text`), and GPUI has a clipboard. | `saved_ui.rs` |
| R7 | Low | The pin menu's Remove has no undo, unlike Clear. | `map.rs::pin_menu` |
| R8 | Low | When the point is already saved, the confirm sheet still offers "Save this place" and the stop sheet still offers "Save this stop". Both only lead to "Already saved as X". They should show the saved name and offer Rename. | `picker.rs::confirm_body/stop_body` |
| R9 | Low | Stale or wrong copy: the transit note promises "wake up 1, 2 or 3 stops before yours", but no such option exists (the trigger is the AB crossing). The Saved empty state is titled "No Saved Stops", though it holds places. Saved rows in the picker have no detail line (stop or place, distance), unlike the Saved tab. | `transit.rs`, `saved_ui.rs`, `picker.rs` |
| R10 | Low (deferred) | Single History records cannot be deleted, only the whole list (which can be undone). Adding a per-record menu is more than completing what exists. Noted, not planned. | `settings_ui.rs` |
| R11 | Info | `rovsti_tiles`: no UI-facing gap beyond R5. GTFS import errors have recovery text. The template editor shows "last good text is kept". | |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

### S1. Pure helpers: endpoint → destination, share text, serialisable draft (+ tests)
- `apps/stops/src/pick.rs`:
  - `pub fn dest_from_endpoint(ep: &Endpoint, saved: &SavedPlaces) -> Destination`. A label that
    trims to "", "Point", "Point A" or "Point B" becomes unnamed (`Destination::dropped`).
    Otherwise use `Destination::new(label, coord)`. Then apply `.named_from(saved)`.
  - Tests: "Point A" used as B reads `point_label(Slot::B, ..) == "Point B"`. A record near a
    saved place that was renamed takes the new name and its `gtfs_stop`. A named far point keeps
    its label.
- `crates/rovsti_geo/src/dest.rs`:
  - `pub fn share_text(label: &str, coord: Coord) -> String`. It returns the trimmed label (when
    there is one) on its own line, then
    `https://www.openstreetmap.org/?mlat={lat:.5}&mlon={lon:.5}#map=17/{lat:.5}/{lon:.5}`.
  - Re-export it from `lib.rs` next to `parse_location`.
  - Tests:
    - `parse_location(&share_text("Home", c))` gives `c` back to within 1e-5.
    - An empty label gives the link only.
- `apps/stops/src/picker.rs`:
  - Add `#[derive(Serialize, Deserialize)]` to `Destination` (with `#[serde(default)]` on
    `gtfs_stop`/`pattern`) and to `Ends`.
- `apps/stops/src/store.rs`:
  - `pub const DRAFT_FILE: &str = "draft.json";`.
- `apps/stops/src/backend.rs`:
  - `Data` gains `pub draft: Ends`, which `load` reads with
    `storage::load_json(dir, store::DRAFT_FILE).unwrap_or_default()`.
  - New `pub fn save_draft(dir: &Path, ends: &Ends)`.
  - Tests with `store::tests::tmp`: a save/load round-trip keeps labels, `gtfs_stop` and
    `pattern`, and a corrupt file reads as empty.
Acceptance: the new tests pass. No UI change yet.

### S2. Keep the chosen A/B across a process kill (R1)
- `main.rs`: add the field `draft_saved: picker::Ends`.
- New `fn persist_draft(&mut self, cx)`. When `self.loaded`, `self.pick.dragging().is_none()` and
  `self.pick.ends != self.draft_saved`, it clones the ends into `draft_saved` and spawns
  `backend::save_draft` on the background executor. Call it at the top of `Render::render`. The
  comparison is cheap, and writes happen only on change, never per drag frame.
- In the load callback: when `data.trip.is_none()` and `this.pick.ends.is_empty()` (the user has
  not picked anything during the load), call `this.pick.restore(data.draft.clone())`, set
  `this.map.follow = true`, then set `this.draft_saved = data.draft`.
- After `begin_trip` clears the pair, the next render saves an empty draft. Nothing else changes.
Acceptance:
- Placing A, killing the app and reopening shows A again.
- A started trip leaves an empty draft.
- Patterns are dropped by `set_feed` as before (the restore runs after `set_feed`).

### S3. Recent and History reuse use the current names (R3)
- `picker.rs::pick_rows`: `PickItem::Pair` and `PickItem::Recent` build their destinations with
  `pick::dest_from_endpoint(&ep, &self.saved)`.
- `settings_ui.rs::history_item`: the "Use" click does the same, replacing the two
  `Destination::new(..).named_from(..)` calls.
- Each `PickItem::Saved` gets a `detail: String` from `saved_ui::place_detail(place,
  self.map.position.map(|p| p.0))`, shown as the row subtitle (R9). Its key is unchanged.
Acceptance: the S1 tests cover the logic, and the UI uses only the helper.

### S4. Stop can be undone; pin Remove can be undone (R2, R7)
- `main.rs`: add `PillAction::RestartTrip(Destination, Destination)`. Its handler calls
  `self.request_start(a, b, cx)`.
- `trip_ui.rs::stop_trip`: before the spawn, take
  `(trip.trip.a.clone(), trip.trip.b.clone())` from `self.trip` (or the `ActiveTrip`'s
  endpoints, wherever they live; grep `ActiveTrip`), then map them through
  `pick::dest_from_endpoint`. On completion:
  - Call `trip_over`.
  - Then `self.pick.set_pair(a.clone(), b.clone())`, so the pair is back on the Trip tab: Start
    runs it again, and Swap gives the way back.
  - Then show the pill "Trip stopped" with `Some(PillAction::RestartTrip(a, b))`.
- `map.rs::pin_menu` Remove: snapshot `this.pick.ends.clone()` before `pick.remove(slot)`, then
  show the pill `format!("Removed {}", pick::point_label(slot, Some(&dest)))` with
  `PillAction::RestoreEnds(snapshot)`.
Acceptance:
- After Stop, A and B are shown, and Undo on the pill starts the same trip (through preflight).
- Removing a pin can be undone.

### S5. Confirm or undo destructive settings (R4, R5)
- `transit.rs`: add the field `confirm_remove_feed: bool` on `StopsApp`, set to false in
  `open_settings` and after an import. The first tap on "Remove transit data" arms it: the row
  becomes "Tap again to remove" in red. The second tap calls `remove_feed`.
- `settings_ui.rs::reset_tiles`: keep `old = self.prefs.tile_url.clone()`. Skip the pill's Undo
  when `old.is_none()`. Otherwise use the pill "Map server reset to default" with
  `PillAction::RestoreTileUrl(old)`. The handler sets `prefs.tile_url = old`, then calls
  `use_tile_source(self.prefs.tile_source(), cx)`, then `sync_tile_field`.
- `turn_notice_off`: keep `old = self.prefs.notice.take()`. When it is Some, show the pill
  "Arriving-soon message off" with `PillAction::RestoreNotice(NoticePrefs)`. The handler restores
  it, then `save_prefs`, then `sync_template_field`.
- Test the pure part only if it is extracted (e.g. a `prefs` restore helper). Otherwise this is
  glue over existing tested prefs.
Acceptance: none of the three loses user data on one tap, and each gives a pill.

### S6. Share a saved place (R6)
- `saved_ui.rs`: the open row's first pill row becomes Use as A, Use as B, Share.
  `fn share_place(&mut self, id, cx)` builds `rovsti_geo::share_text(&p.name, p.coord)` and
  runs `rovsti_android::telecom::share_text(&text)` on the background executor. Then:
  - `Ok(true)`: no pill (the system sheet is the feedback).
  - `Ok(false)` or `Err(Unsupported)`: `cx.write_to_clipboard(ClipboardItem::new_string(text))`
    and the pill "Copied {name}".
  - Other errors: the pill "Could not share {name}".
- Update the module doc comment.
- `docs/android.md`: note that Stops uses `telecom::share_text`.
Acceptance: Share is reachable in two taps from Saved. On desktop it copies the text, and the pill
says so.

### S7. Already-saved state and copy fixes (R8, R9)
- `picker.rs::confirm_body`: when `self.saved.near(dest.coord)` is `Some(p)`, replace the toggle
  with the row "Saved as {p.name}" and the trailing "Rename", which calls `open_rename(p.id)`.
  Force `save_place = false`.
- `stop_body`: the same for "Save this stop".
- `transit.rs` note: "With a feed, stops and their lines show on the map and in search, and any
  stop can be A or B."
- `saved_ui.rs` empty state title: "No Saved Places".
Acceptance: a saved point never offers Save again. The copy matches what the app does.

### S8. Docs and status
- `docs/roadmap.md` Rovsti Stops: extend the save/manage line with "share a place; the chosen A/B
  survive a restart; Stop and settings resets can be undone (done without a device)".
- Record R10 as an open idea.
- Append a Status section to this file listing fixed / deferred.

## Checks (every step)
```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_tiles --target aarch64-linux-android
```
Disk: 7.7 GB free at audit time. Under 6 GB, run `rm -rf target/debug/incremental` first.

## Status (after S8)

Fixed: R1 (A/B survive a restart), R2 (Stop can be undone), R3 (history and recent rows use the
saved place name and stop), R4 (feed removal is confirmed), R5 (settings resets can be undone),
R6 (share a saved place), R7 (pin Remove can be undone), R8 (a saved point shows its name and
Rename instead of Save), R9 (copy fixes and detail lines).

Deferred: R10 (delete single History records), recorded in `docs/roadmap.md` as an open idea.
R11 needed no change.
