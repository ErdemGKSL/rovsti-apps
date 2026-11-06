# UX completeness pass: Rovsti Stops (round 4)

Base: `336377f6554994566bbe66d5190c082c007be491` (main after merge). No stash, clean tree.
Rounds 1-3 (`ux-stops.md`, `ux-stops-r2.md`, `ux-stops-r3.md`) are done and committed
(`6d81570`..`4a93d17`):
- The owner's gap is closed: any pin or picked point can be saved from the pin menu with a name
  field prefilled with the snapped stop name.
- Saved places can be renamed, reordered, deleted with undo, and shared.
- A re-picked saved place shows its name.
- Share works for any pin. Paste works in the picker and in the tile URL field.
- Single History trips can be deleted with undo.
- Downloaded map size and Clear are in Settings > Map tiles. The default notice text can be
  restored.

The owner asked again for every gap of this kind to be fixed, so this round re-audits the whole
app. It also takes T7 (saved places on the map), which was deferred twice. The reason: saved
places are invisible on the map today. A tap near one takes its name but keeps the tapped
coordinate, so a saved place cannot be picked precisely from the map. That is an incomplete pick
flow, not a new feature. The stop-dot layer already shows how to draw such points.

Scope: `apps/stops`, `crates/rovsti_geo`, `crates/rovsti_tiles`. No new product features and no
backends. Pure logic goes first, with tests. GPUI glue stays thin. Only GPUI APIs already used in
this app are allowed:
- `field_box` with `Entity<TextField>` and `cx.observe` on the field, as for `tile_field`
- `PathBuilder` / `add_circle` / `fill_circle` in `map.rs`
- `list` with `ListState` sync

## Re-audit: remaining gaps

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| U1 | **High** | The arriving-soon chat picker shows only the first 60 chats (`chat_rows` uses `.take(MAX_CHATS)`) and has no search. A chat that is not among the 60 most recent cannot be chosen at all. If the selected chat is outside those 60, the list never shows it as "Selected". | `settings_ui.rs::chat_rows/notice_page/notice_item` |
| U2 | **Medium** | The chat list is a dead end once it is empty. `load_chats(false)` returns early for `ChatList::Ready(_)`, so a first load that returned no chats (before history sync) shows "No chats yet / Chats appear after history sync." for the rest of the process, with no Retry. A non-empty list is never refreshed either, so new chats stay missing until the app restarts. | `settings_ui.rs::load_chats/open_settings/NoticeItem::Status` |
| U3 | **Medium** | Saved places are not drawn on the map (round-3 T7). A tap or a pin drop near one takes its name through `named_from` (50 m) but keeps the raw tapped coordinate. Map snapping knows only GTFS stops. The user cannot see where their places are, or pick one exactly from the map. | `map.rs::snap_destination/Scene/scene_paths`, `map_tap`, `PinDrop` |
| U4 | Low | A picker search with no matches shows an empty list under the field, with no text. Without a feed, a stop name typed into the field silently finds nothing, and nothing says that stop search needs a GTFS feed. | `picker.rs::pick_rows/PickItem` |
| U5 | Info | Saved places cannot be moved to a new location; only the name can be edited. Delete plus Save covers it, with undo. This is new editing UI, so it stays an open idea. | `saved_ui.rs` |
| U6 | Info | Settings > Exact alarms has no action. `USE_EXACT_ALARM` is granted at install and cannot be revoked (AndroidManifest), so the row is informational. No change. | `settings_ui.rs` |
| U7 | Info | After the alarm is dismissed, the Trip tab goes back to empty. The trip is in History and in Recent trips (one tap plus Swap), so this is no dead end. No change. | `trip_ui.rs::alarm_action` |
| U8 | Info | The Saved tab has no search field. The picker field already searches saved places by name (`SavedPlaces::search`). No change. | `saved_ui.rs` |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

### S1. Pure helpers, with tests (no UI change)

`apps/stops/src/settings_ui.rs`:
- `chat_rows(chats)` maps every chat and no longer applies `.take(MAX_CHATS)`. Keep `MAX_CHATS`
  as the display cap.
- New `pub fn visible_chats(rows: &[ChatRow], query: &str, selected: Option<(&str, &str)>, cap: usize) -> Vec<ChatRow>`.
  `selected` is `(jid, chat_name)` from `prefs.notice`.
  - Fold the query with `rovsti_core::search::fold(query.trim())`.
  - An empty query gives the first `cap` rows.
  - Otherwise keep the rows whose folded title contains the query, or whose jid user part
    (digits) contains the query's digits when the query has any. Take `cap` of them.
  - When the query is empty and the selected jid is not in the result, prepend it. Use the row
    from `rows` if it is there; otherwise build
    `ChatRow { jid, title: name or "Selected chat", group: jid.ends_with("@g.us") }`.
  - Tests:
    - An empty query caps at `cap`.
    - The query "fam" finds "Family" at index 80 of 100.
    - A diacritic query ("kadikoy" vs "Kadıköy"/"Kadiköy") matches.
    - A digits query "5321" matches `905321234567@s.whatsapp.net`.
    - A selected chat beyond the cap is first and is not duplicated.
    - A selected chat that is already in the first `cap` is not moved.
  - Update the existing test that asserts `chat_rows(&chats).len() == MAX_CHATS`: `chat_rows`
    keeps all of them, and `visible_chats(.., "", None, MAX_CHATS)` has length `MAX_CHATS`.
- New `pub fn chat_status_text(list: &ChatList, query: &str) -> Option<(&'static str, Option<String>, Option<&'static str>)>`.
  It returns (title, subtitle, trailing) for the single status row, or None when chat rows show:
  - `Idle`/`Loading` → ("Loading chats...", None, None)
  - `Failed(r)` → ("Could not load your chats", Some(r), Some("Retry"))
  - `Ready(empty)` → ("No chats yet", Some("Chats appear after history sync."), Some("Refresh"))
  - `Ready(non-empty)` with no match for `query` → ("No chats match", None, None)
  The test covers each state.

`apps/stops/src/pick.rs`:
- New `pub fn no_match_text(query: &str, has_feed: bool) -> String`. The query is trimmed and
  shown in typographic quotes.
  - With a feed: `No stops or saved places match "{q}".`
  - Without a feed: `No saved places match "{q}". Import a GTFS feed in Settings to search stops too.`
  - Tests cover both, and a query with surrounding spaces.

`apps/stops/src/map.rs` (pure section):
- New `pub fn pick_saved(places: &[Place], viewport: &Viewport, size: (f32, f32), at: (f32, f32), max_px: f32) -> Option<usize>`.
  It collects the place coords and returns `viewport.nearest_on_screen(&coords, at, max_px)`.
- Change `snap_destination` to take the saved places:
  `snap_destination(feed: Option<&Feed>, saved: &SavedPlaces, viewport: &Viewport, size: (f32, f32), at: (f32, f32)) -> Destination`.
  The order is:
  1. A saved place within `PICK_RADIUS_PX` wins: `Destination::from_place(place)`, with its
     exact coord, name and `gtfs_stop`.
  2. Else a stop as today, then `.named_from(saved)`.
  3. Else `Destination::dropped(..).named_from(saved)`.
  Callers no longer chain `.named_from`.
- New `pub fn saved_key(saved: &SavedPlaces) -> u64`, a fingerprint of `(id, lat.to_bits(), lon.to_bits())` per place. Use
  `std::hash::{DefaultHasher, Hash, Hasher}`.
- Tests:
  - Update `a_tap_near_a_stop_snaps_to_it_and_far_away_stays_a_raw_point` to pass
    `&SavedPlaces::default()`.
  - New `a_tap_near_a_saved_place_picks_it_exactly`. A saved place 30 px from the tap beats a
    stop 10 px from it. The label, the exact coord and `gtfs_stop` come from the place. A place
    60 px away is ignored, so the stop wins.
  - New `saved_key_changes_with_the_places`. Adding or removing a place changes the key, and
    equal lists give equal keys.

Acceptance: the new tests pass. `map_tap` and `PinDrop` call the new signature, and their
behaviour is unchanged apart from saved-place snapping.

### S2. Saved places drawn on the map (U3)

`apps/stops/src/map.rs`:
- `SceneInput` gains `pub saved: &'a [Coord]`.
- `Scene` gains `pub saved: Vec<(f32, f32)>`: the projected places inside the view plus a 24 px
  margin.
  - Extract a pure `fn in_view(p: (f32, f32), size: (f32, f32), margin: f32) -> bool` if none
    exists.
  - No cap is needed, because `MAX_PLACES` is 200.
- `SceneKey` gains `saved: u64` (`saved_key(&self.saved)`), so a save, rename or delete redraws
  the map.
- In `render` where `SceneInput` is built, pass
  `let saved: Vec<Coord> = self.saved.places.iter().map(|p| p.coord).collect();`.
- `Palette` gains `saved: Hsla`, set to `c.orange`. It is drawn as a hollow marker so it does
  not read as a stop dot or as the orange line:
  - In `scene_paths`, after the stop dots and before the diameter, call
    `fill_circle(out, p, 6.5, pal.halo)` for each saved point.
  - Then build one `PathBuilder::stroke(px(2.5))` with `add_circle(.., 5.0, false)` per point
    and push it with `pal.saved`.
  - Only the helpers already in `map.rs` are used.
- Tests:
  - Extend the scene test fixture (`SceneInput { .. }` at about line 2702) with `saved: &[]`.
  - New `saved_places_in_view_are_projected`: one place in view and one far away give exactly
    one point, at `vp.project(coord)`.
- Update the doc comment on `Scene` and the module doc that lists the layers.

Acceptance:
- Saved places show as rings on the idle and live maps.
- A tap on a ring gives a pin with the place's name and exact position. Its menu then offers
  Rename, not Save.
- Saving, renaming or deleting a place redraws the map.

### S3. Search every chat in the arriving-soon picker (U1)

`main.rs`:
- Add the field `chat_query: Entity<TextField>`, created with `cx.new(TextField::new)`.
- Observe it with `cx.observe(&chat_query, |_, _, cx| cx.notify()).detach()`, as for
  `tile_field`.

`settings_ui.rs`:
- `NoticeItem` gains `Search`, with key `lists::key(8, 0)`. In `notice_page`, insert it after
  `SendTo` when the list is `ChatList::Ready(rows)` with `rows.len() > 8`. It renders
  `field_box(theme, "chat-search", &self.chat_query, "Search chats", window, cx)` inside
  `div().px(px(16.0)).pb(px(6.0))`.
- `notice_page` computes `let shown = visible_chats(rows, query, selected, MAX_CHATS)` and stores
  it in a new `StopsApp` field `chat_view: Vec<ChatRow>`. `NoticeItem::Chat(i)` renders
  `self.chat_view[i]`, not `rows[i]`.
  - The `Chat(i)` key becomes `lists::key(4, hash of jid)`, so a filtered list re-measures. Add a
    small pure `fn chat_key(jid: &str) -> u64` with a test.
  - `NoticeItem::Status` uses `chat_status_text(&self.chats, query)`. Push it when
    `chat_status_text` is Some.
- `open_settings(SettingsPage::Notice)` clears the query:
  `self.chat_query.update(cx, |f, cx| f.set("", cx))`.
- Extend `notice_items_change_key_when_their_height_may` for `Search` and the new `Chat` key.

Acceptance:
- Any chat the gateway returns can be found by name or number and selected.
- The selected chat is always visible at the top of the unfiltered list.
- A search with no match says "No chats match".

### S4. The chat list refreshes, with no empty dead end (U2)

`settings_ui.rs::load_chats(force)`:
- Keep the early return only for `ChatList::Loading`.
- Switch to `Loading` only when the current list is not `Ready(non-empty)`. A refresh keeps the
  rows on screen.
- On failure while rows are shown, keep the old rows and show the pill "Could not refresh your
  chats". Otherwise set `Failed` as today.
- `open_settings(SettingsPage::Notice)` calls `self.load_chats(true, cx)`, so every visit
  refreshes.
- The status row's trailing "Refresh" (`Ready(empty)`) and "Retry" (`Failed`) both get id
  `chats-retry` and `on_click(load_chats(true))`. Take the texts from `chat_status_text`.
- Optional pure helper with a test, if the branching grows:
  `fn reload_plan(list: &ChatList) -> (bool /*fetch*/, bool /*show loading*/)`.

Acceptance:
- After history sync, opening the page again or tapping Refresh shows the chats.
- A failed refresh keeps the list.

### S5. Picker empty state for no matches (U4)

`picker.rs`:
- `PickItem` gains `NoMatch(String)`, with key `lists::key(8, text.len() as u64)`.
- In `pick_rows`, when `!query.is_empty()` and `items.is_empty()` after the offer, stops and
  saved groups, push `PickItem::NoMatch(pick::no_match_text(query, self.gtfs.is_some()))`. A
  `Refusal` counts as an item, so a bad link still shows its reason instead.
- `pick_item` renders it with `Self::footnote(theme, text)`.

Acceptance: typing a name that matches nothing explains why. Without a feed, it points to the
import in Settings.

### S6. Docs and status

`docs/roadmap.md` Rovsti Stops:
- Replace "[ ] Idea (open, T7): draw saved places on the map" with "[x] Saved places drawn on the
  map; a tap on one picks it exactly (done without a device)".
- Extend the save/manage line with "search every WhatsApp chat for the arriving-soon message;
  the chat list refreshes".
- Add "[ ] Idea (open, U5): move a saved place to a new location".

`docs/architecture.md`: if the Stops map layers are listed, add the saved-place layer.

Append a Status section to this file listing fixed, deferred and by-design gaps.

## Checks (every step)
```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_tiles --target aarch64-linux-android
```
Disk: 9.5 GB free at audit time. Under 6 GB, run `rm -rf target/debug/incremental` first.
Other people push to main in parallel, so keep one focused commit per step, with the prefix
"Rovsti stops:".

## Status

- Fixed: U1 (search every chat in the arriving-soon picker), U2 (the chat list refreshes, no
  empty dead end), U3 (saved places drawn on the map, a tap picks one exactly), U4 (picker empty
  state for no matches).
- Deferred: U5 (move a saved place to a new location). Open idea in `docs/roadmap.md`.
- By design or covered: U6 (Exact alarms row is informational), U7 (the trip is in History and
  Recent trips), U8 (the picker field already searches saved places).
