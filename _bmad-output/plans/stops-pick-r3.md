# Plan: Rovsti Stops, pick A and B on the map (round 3)

- Base: `1cbe7921572d916aea1da3ac602bb8e739362cd3`. `git stash list` is empty; working tree clean.
- Earlier rounds: `stops-pick.md` (S1-S7, commits 29ad5d9 .. c680b00) and `stops-pick-r2.md`
  (S1 "device fixes recentre only when asked", commit 1cbe792). All landed.
- Architect: PLAN agent. No source changed by this plan.
- Verdict: **done**. No steps.

## Verified at the base

- Checks green: `cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
  `cargo test --workspace` (all suites ok), `cargo check --target aarch64-linux-android` for
  `rovsti_android`, `rovsti_wa_client`, `rovsti_tiles`.
- Round-2 defect fixed: `MapState::set_device_fix` moves the view only when `locate_pending`
  (`ask_locate` from `locate_me`, `cancel_locate` on preflight step / refused / error); tests
  `a_startup_fix_does_not_move_a_view_the_user_took_over`,
  `an_asked_for_fix_centres_at_street_level_once`, `a_following_idle_view_uses_the_fix`.
  AD-ST9 carries the sentence.
- Owner's spec, item by item:
  - Map is the main idle surface (`IDLE_MAP_HEIGHT` 460), `idle_view` centres on ends >
    device position > last trip / saved place > default at `STREET_MPP`; my-location button via
    the `rovsti_android::location` seam.
  - Hints "Tap the map to set A" / "Tap the map to set B" (`pick::Hint`); tap 1 drops A, tap 2
    drops B, circle at once; no sheet, no coordinates.
  - Pins drag directly (pure gestures in `map.rs`, pan/pinch unaffected, tested); pin tap opens
    the pin menu (Move / Remove / Lines / details line); Clear resets both.
  - Snap to GTFS stops within `PICK_RADIUS_PX` (tap and drop); saved places, recent pairs,
    paste/link and stop search stay in the secondary sheet; shared `geo:` links still work.
  - Labels: stop/saved name or "Point A"/"Point B", "A-B 850 m"; raw lat/lon only through
    `pick::details_text` (pin menu, confirm and stop sheet footnotes); test
    "unnamed points never show a coordinate".
  - Start gated by `Picking::can_start` with friendly MIN/MAX diameter messages.
  - Real tile map with radar fallback, theme palette (light/dark), bottom pick bar for one hand.
  - Docs: AD-ST9 in `docs/architecture.md`, `docs/android.md` location seam, `docs/roadmap.md`.

## Not verifiable here

No physical device: on-device feel (pin grab radius, tile load at street zoom, one-handed reach)
is untested beyond the pure tests. Suggested owner check: fresh install, idle Trip tab, tap twice,
drag a pin, tap a stop, Start.
