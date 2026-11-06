# Plan: Rovsti Stops, diameter-crossing alarm (round 2)

- Base: `2185f5511ed7e7c8d964f1933efabf55ebd9c2b9` ("Rovsti Stops: diameter crossing review fixes")
- Architect: PLAN agent. No source changed by this plan. No stash entries.

## Verdict: done, no further steps

All seven steps of `stops-crossing.md` landed (commits `0ddd148` .. `2185f55`), and the owner's
clarification ("works both ways, left->right or right->left; left->right was only an example")
is met:

- `CrossingWatch::observe` (`crates/rovsti_geo/src/crossing.rs`) fires on any confident side
  change (`was != now`) whose path segment crosses AB; tests `fires_left_to_right_and_back`,
  `trip_rings_once_each_direction`, `session::fix_out_rings_in_both_directions`,
  `headless::crossing_right_to_left_rings_too`.
- Dead band `max(10 m, accuracy)`, segment intersection, degenerate-input limits (20 m .. 50 km),
  adaptive `next_fix_in`, persistence (`ActiveTrip { a, b, trip: CrossingTrip }`,
  `trip.legacy.json` rename for old state, legacy history records), A/B/swap picking UI, map
  halves, live side/distance text, alarm texts and docs (`docs/roadmap.md`,
  `docs/architecture.md` AD-SX1..8) are in place. UI text says "either way".
- `rg "AlarmRule|RuleChoice|BeforeStop|stop_wake|wake_options" crates apps` finds nothing.
- Checks at the base: fmt, clippy `-D warnings`, `cargo test --workspace`, and both
  `aarch64-linux-android` checks all pass.

Open (outside this goal): the on-device check in `docs/roadmap.md` (no device available).
