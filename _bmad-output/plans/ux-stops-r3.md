# UX completeness pass: Rovsti Stops (round 3)

Base: `dc50964ffe1c73cbd5c22bf39e7ee8be9e79c09a` (main after merge). No stash, clean tree.
Rounds 1 (`ux-stops.md`) and 2 (`ux-stops-r2.md`) are done:
- The original gap is closed. A pin can be saved from its menu with a name field, prefilled with
  the snapped stop name.
- Saved places can be renamed, reordered, deleted (with undo) and shared.
- Re-picking a saved place shows its name.
- The A/B pair survives a process kill.
- Stop, pin Remove, Clear and the settings resets can be undone.
- History trips can be reused.

The owner asked for every remaining gap to be fixed, so this round also takes the gaps deferred
earlier when they only complete what the app already has.
Scope: `apps/stops`, `crates/rovsti_geo`, `crates/rovsti_tiles`. No new product features, no
backends. Pure logic first, with tests. GPUI glue stays thin. Only use GPUI APIs already used in
this repo:
- `cx.read_from_clipboard()` / `ClipboardItem::text()` (phone `keypad.rs`, `rovsti_wa_ui` `view.rs`)
- `cx.write_to_clipboard`
- `rovsti_android::telecom::share_text`

## Re-audit: remaining gaps

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| T1 | **Medium** | A single History trip cannot be deleted (round-2 R10, deferred). The only way to remove a wrong or private trip is to clear all 500. Its points also stay in Recent trips and Recent in the picker with no way to remove them. | `settings_ui.rs::history_item`, `rovsti_geo::History` |
| T2 | **Medium** | The picker has no Paste button. The hint says "paste its link here", but the field only gets clipboard text if the keyboard happens to offer it. Users are pushed to type coordinates by hand. The same applies to the tile server URL field. | `picker.rs::pick_body`, `settings_ui.rs::tiles_body` |
| T3 | Medium | A pin on the map that is not saved cannot be shared or copied, so the coordinates are not reachable at all. Sharing exists only for saved places. | `map.rs::pin_menu`, `saved_ui.rs::share_place` |
| T4 | Low | Downloaded map tiles are invisible to the user. Settings > Map tiles does not show how much space they take, and there is no way to clear them. Caches of servers no longer in use stay on disk forever (one directory per server). `DiskCache::total_bytes` exists but nothing in the UI uses it. | `rovsti_tiles::cache`, `tiles.rs`, `settings_ui.rs::tiles_body` |
| T5 | Low | The arriving-soon template cannot go back to the default text. An emptied field is refused ("last good text is kept"), so the user has to retype the default from memory. | `settings_ui.rs::notice_item` |
| T6 | Low | During a running trip, Use as A / Use as B on the Saved tab jumps to the Trip tab and shows only the live card. Nothing says that the point was set for the next trip. | `saved_ui.rs::saved_item` |
| T7 | Info (deferred) | Saved places are not drawn on the map. Picking one by tap or drop does show its name (round 1). Drawing markers would be a new map layer, not completing an existing flow. Recorded as an open idea. | `map.rs` |
| T8 | Info | The "Text X when arriving" toggle on the Trip tab resets to On at launch. This is by design: it is a per-trip choice, and the setup lives in Settings. No change. | `trip_ui.rs` |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

### S1. Pure helpers in rovsti_geo and apps/stops, with tests
- `crates/rovsti_geo/src/history.rs`:
  - `pub fn take(&mut self, index: usize) -> Option<TripRecord>`: removes and returns the record
    at `index`, or returns None when the index is out of range.
  - `pub fn put_back(&mut self, index: usize, record: TripRecord)`: inserts the record at
    `index.min(len)` and truncates to `MAX_RECORDS`. It does nothing when a record that is equal
    (`==`) is already there, so a second Undo is safe.
  - Tests:
    - take and put_back round-trip the order.
    - An out-of-range index is None, and put_back clamps it.
    - A double put_back is a no-op.
    - `recent_pairs` and `recent_points` no longer list a point that only the taken record had.
- `apps/stops/src/pick.rs`:
  - `pub fn clean_paste(text: Option<&str>) -> Option<String>`: trims, joins line breaks into one
    space, returns None when the result is empty. Tests: None, whitespace, a multi-line maps
    share, a plain URL.
  - `pub fn queued_text(slot: Slot, dest: &Destination) -> String`: returns
    `"{point_label} is {A|B} for your next trip"`. Tests: a named place, and an unnamed point
    ("Point B is B ...").
- `apps/stops/src/settings_ui.rs`:
  - `pub fn bytes_text(bytes: u64) -> String`: "None" for 0, "< 1 MB" under 1 MiB, otherwise
    `"{:.1} MB"` (MiB) below 10 MB and whole MB from 10 MB up. Tests at the edges.
Acceptance: the new tests pass. No UI change.

### S2. rovsti_tiles: disk usage and clearing (pure fs, with tests)
- `crates/rovsti_tiles/src/cache.rs`:
  - `pub fn disk_usage(root: &Path) -> u64`: the sum of the file sizes under `root`, walked
    recursively, so every server's cache is counted. A missing root counts as 0.
  - `pub fn clear_all(root: &Path) -> io::Result<()>`: `remove_dir_all(root)` and treats
    NotFound as Ok.
- Re-export both from `lib.rs`.
- Tests use the existing `tmp()` helper:
  - Usage counts tiles from two sources.
  - `clear_all` leaves usage at 0, and a `DiskCache::open` afterwards is empty and works.
  - `clear_all` on a missing dir is Ok.
Acceptance: the tests pass. `cargo check -p rovsti_tiles --target aarch64-linux-android` stays
green.

### S3. Delete a single History trip, with undo (T1)
- `main.rs`:
  - Add the field `history_open: Option<usize>`, the History row whose actions are shown.
    Initialise it to None.
  - Add `PillAction::RestoreRecord(rovsti_geo::TripRecord, usize)`. Its handler calls
    `self.restore_record(record, index, cx)`.
  - `can_go_back`/`on_back`: when on the History page with `history_open.is_some()`, Back first
    collapses the row.
- `settings_ui.rs::history_item`:
  - Tapping a record row toggles `history_open = Some(i)`. The trailing text becomes
    "Close"/outcome. The list key of an open row is `lists::key(5, r.id)` so it is measured
    again.
  - An open row shows one pill row:
    - "Use" (accent; only when the record has both `a` and `b`) keeps today's behaviour:
      `dest_from_endpoint` for both ends, then `choose_pair`, `tabs.jump_to(0)` and
      `open_settings(Main)`.
    - "Delete" (red) calls `delete_record(i, cx)`.
  - `fn delete_record(&mut self, i, cx)`:
    - `self.history.take(i)`, then save it with `backend::save_history` on the background
      executor.
    - Set `history_open = None`.
    - Show the pill `"Removed {record_pair(&r)}"` with `RestoreRecord(r, i)`.
  - `pub(crate) fn restore_record(..)`: calls `history.put_back`, saves, then notifies.
  - `open_settings`, `clear_history` and `restore_history` reset `history_open` to None.
Acceptance:
- One trip can be removed in two taps, and Undo puts it back in place.
- Recent trips and Recent in the picker drop its points.
- Use still works.

### S4. Paste buttons (T2)
- `picker.rs::pick_body`: the header row puts the `field_box` (`flex_1`) and a "Paste"
  `glass_pill` (id `dest-paste`, accent) side by side. The click calls `paste_into_picker(cx)`:
  - Read the clipboard: `cx.read_from_clipboard().and_then(|i| i.text())`. Read it only on the
    tap, never while rendering, because Android shows a "pasted" notice on every read.
  - Pass the text through `pick::clean_paste`. When it is None, show the pill
    "Nothing to paste".
  - When `offer(&text)` is `Offer::Confirm(dest)`, open
    `confirm_destination(dest.named_from(&self.saved), cx)` directly.
  - Otherwise put the text in the field with `self.field.update(cx, |f, cx| f.set(&text, cx))`,
    so the search or the refusal shows.
- `settings_ui.rs::tiles_body`: add a "Paste" pill (id `tiles-paste`) next to the URL field. It
  fills the field from the clipboard through `clean_paste`, or shows the pill "Nothing to paste".
  Nothing is saved until the user taps Test and save.
Acceptance: a maps link copied in another app can reach the Confirm sheet with one tap. On desktop
the button uses the desktop clipboard.

### S5. Share a pin from its menu (T3)
- `saved_ui.rs`: move the body of `share_place` into
  `pub(crate) fn share_point(&mut self, name: String, coord: Coord, cx)`. `share_place` then
  calls it. The behaviour does not change: on Android it opens the share sheet, and otherwise it
  copies the text and shows the pill "Copied {name}" or "Could not share {name}".
- `map.rs::pin_menu`: add a "Share" row (id `pin-share`) after Save/Rename. It closes the menu
  and calls `share_point`:
  - the name is `pick::point_label(slot, Some(&dest))`;
  - the text is `share_text(dest.label, coord)`, so an unnamed point shares the link only.
- Set `PIN_MENU_H` to 204.0 (5 rows × 36 + 24). Update `the_menu_rows_fit_the_menu_box` to 5
  rows and its comment, and check that `the_pin_menu_opens_above_and_flips_below_near_the_top`
  still holds (pin y 250, map 340: 250 − 14 − 204 = 32 ≥ 8).
- Update the doc comment above `pin_menu`.
Acceptance: any pin can be shared or copied in two taps. The menu fits on the idle (460) and live
(340) maps.

### S6. Downloaded maps: size and Clear (T4)
- `tiles.rs` (`MapTiles`):
  - `pub fn root(&self) -> Option<PathBuf>`.
  - `pub fn clear_disk(&mut self, cx, done: impl FnOnce(&mut StopsApp, &mut Context<StopsApp>) + 'static)`:
    - Clear the queue and memory, set `cache = None` and bump `generation`, so in-flight results
      are ignored.
    - Spawn `rovsti_tiles::clear_all(&root)` on the background executor.
    - Then, in an update, call `set_source(self.source.clone(), cx)` to reopen an empty cache,
      followed by `done`.
- `main.rs`: add the fields `tile_usage: Option<u64>` and `confirm_clear_tiles: bool`.
- `settings_ui.rs`:
  - `open_settings(MapTiles)` spawns `rovsti_tiles::disk_usage(&root)` and stores the result in
    `tile_usage`.
  - `open_settings` resets `confirm_clear_tiles`.
  - `tiles_body` adds a section with the row "Downloaded maps", subtitle
    `bytes_text(usage)` (or "Checking..." while it is unknown), and the trailing text "Clear".
    The row's text colour is red once armed, and its title is then "Tap again to clear".
  - The first tap arms the row. The second tap calls `clear_disk`, whose `done` sets
    `tile_usage = Some(0)` and shows the pill "Downloaded maps cleared". There is no undo: the
    tiles download again on demand. Clearing is refused with the pill "Not while a trip is
    running" while `self.trip.is_some()`, because the prefetched offline tiles matter then.
  - Add a footnote under the section: "Kept for offline use, up to 128 MB per map server."
Acceptance:
- The size shows on the Map tiles page.
- Clear empties every server's cache after a confirmation, and the map refills the view
  afterwards.

### S7. Default template and next-trip feedback (T5, T6)
- `settings_ui.rs::notice_item` `NoticeItem::Message`: when the saved template is not
  `DEFAULT_TEMPLATE`, add the row "Use the default text" under the preview. It:
  - keeps `old = self.prefs.notice.clone()`;
  - sets `notice.template = DEFAULT_TEMPLATE.to_owned()`, then `save_prefs` and
    `sync_template_field`;
  - shows the pill "Default text restored" with `PillAction::RestoreNotice(old)` when old is
    Some. The handler already exists.
  The item's height changes, so include `template != DEFAULT_TEMPLATE` in the `NoticeItem`
  key, and extend `notice_items_change_key_when_their_height_may`.
- `saved_ui.rs::saved_item` Use as A / Use as B: when `self.trip.is_some()`, call
  `show_pill(pick::queued_text(slot, &dest), None, cx)` after `use_as`.
Acceptance:
- The template can go back to the default in one tap, with undo.
- A pick made during a trip says where it went.

### S8. Docs and status
- `docs/roadmap.md`, Rovsti Stops:
  - Tick R10 as "[x] Delete single History trips, with undo (done without a device)".
  - Extend the save/manage line with "share any pin; paste links into the picker; downloaded map
    size and Clear in Settings > Map tiles".
  - Add "[ ] Idea (open, T7): draw saved places on the map".
- `docs/architecture.md`: in the tile cache paragraph, mention `disk_usage`/`clear_all`.
- Append a Status section to this file listing fixed / deferred.

## Checks (every step)
```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_tiles --target aarch64-linux-android
```
Disk: 7.5 GB free at audit time. Under 6 GB, run `rm -rf target/debug/incremental` first.
