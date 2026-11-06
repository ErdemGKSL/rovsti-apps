# Plan: Rovsti Stops, pick A and B on the map (round 2)

- Base: `edef1af64535ca8457cd92c8a708d5f8132f1fe6`. `git stash list` is empty. Round 1 plan:
  `stops-pick.md` (S1-S7), all landed (commits 29ad5d9 .. c680b00).
- Architect: PLAN agent. No source changed by this plan.
- Verdict: **almost done**. One defect fix below.

## Verified at the base

- All checks green: `cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
  `cargo test --workspace`, `cargo check --target aarch64-linux-android` for `rovsti_android`,
  `rovsti_wa_client`, `rovsti_tiles`.
- Pure `apps/stops/src/pick.rs`: `Picking` (Idle/HasA/HasB/HasAB, tap/place/remove/clear/
  begin_move/menu/drag_*), `Hint` texts ("Tap the map to set A/B", "A-B 850 m", friendly
  too-close/too-far from MIN/MAX_DIAMETER_M), `point_label`, `pair_text`, `details_text`,
  `trip_endpoint`; tests incl. "unnamed points never show a coordinate".
- `map.rs` pure: pin grab/drag/tap/cancel gestures (pan/pinch unaffected, mouse on desktop),
  `snap_destination`, `idle_view` (ends > position > last trip / saved > Istanbul default,
  `STREET_MPP`), `fallback_centre`, `pin_menu_rect`, scene pins + invalid segment; all tested.
- Glue: idle map always shown (`IDLE_MAP_HEIGHT`), tap drops A then B with stop snapping, pins
  with name captions, pin menu (Move / Remove / Lines / details line), "Me" button with the
  `rovsti_android::location` seam (`last_location`, `request_location`, `stops_location` event),
  pick bar (hint, Clear, Swap, Start gated by `can_start`), secondary sources row, A/B rows,
  Back closes the menu, tiles with radar fallback, theme palette.
- Raw lat/lon only in details lines (`details_text`: pin menu, confirm/offer row, stop sheet).
- Docs: AD-ST9 in `docs/architecture.md`, `docs/android.md` seam, `docs/roadmap.md` entries.

## Remaining defect

`MapState::set_device_fix` (map.rs) always does `self.viewport = Viewport::new(at, STREET_MPP)`.
`apply_fix` calls it both for the silent start-up `last_location()` and for the
`stops_location` event. While `follow` is true the next frame's `idle_view` overrides the view
anyway, so the assignment only matters when the user has taken the view over (`follow == false`,
set by every map tap, pan, zoom and drag). So a late start-up fix (async) yanks the view away
from the pins the user is placing. Only an explicit my-location request should recentre.

## Steps

### S1. Device fixes recentre only when asked

Files: `apps/stops/src/map.rs`, `apps/stops/src/main.rs`.

- `map.rs`: add `locate_pending: bool` to `MapState` (default false) and change
  `pub fn set_device_fix(&mut self, at: Coord, accuracy_m: f64)` to: always set `position`;
  if `locate_pending` -> `viewport = Viewport::new(at, STREET_MPP)`, `follow = false`,
  `locate_pending = false`; otherwise leave `viewport`/`follow` untouched (when `follow` is true
  `idle_view` already centres on the position if no end is set).
  Add `pub fn ask_locate(&mut self)` that sets `locate_pending = true`.
- `locate_me` (map.rs glue): call `self.map.ask_locate()` before spawning the request; on
  `Ok(false)` / `Err(_)` / a preflight step, clear it (`self.map.locate_pending = false` through
  a small `cancel_locate()`), so an unrelated later fix does not jump.
- `main.rs` `apply_fix`: unchanged call; the start-up path no longer moves a taken-over view.
- Tests in `map.rs` (pure, `MapState::default()` needs no window):
  `a_startup_fix_does_not_move_a_view_the_user_took_over` (follow=false, viewport set elsewhere,
  `set_device_fix` -> viewport unchanged, position set);
  `an_asked_for_fix_centres_at_street_level_once` (`ask_locate`, fix -> centre == fix,
  `metres_per_px == STREET_MPP`, a second fix leaves the view);
  `a_following_idle_view_uses_the_fix` (follow=true, no ends: `idle_view` with the new position
  centres on it).
- No doc change needed beyond one sentence in AD-ST9 ("a fix recentres the map only after the
  my-location button; otherwise it only feeds the opening view").

Acceptance: all checks from the task green; the three tests pass; grep shows `set_device_fix`
no longer assigns `viewport` unconditionally.

## Check list

```
df -h .    # under 6 GB free: rm -rf target/debug/incremental
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_tiles --target aarch64-linux-android
```
