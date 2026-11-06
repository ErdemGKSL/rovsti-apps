# Plan: Rovsti Stops, adaptive polling and alarm-clock-grade alarm (round 3)

- Base: `75d8adfcfb54c16c53f931d125bb26cabfd40834`
- Architect: PLAN agent. No source changed by this plan.
- Verdict: **done**. No further steps.

## Verified at the base

- Round 1 (`stops-alarm.md`, 8 steps) and round 2 (`stops-alarm-r2.md`, 4 steps) have all landed
  (commits 87ae20e, f0b15c9, 09502b4, a32cdec, review fix 75d8adf).
- Polling: `rovsti_geo::poll` (`MIN_POLL` 3 s, `MAX_POLL` 300 s, `IDLE_POLL` 60 s, `poll_interval`,
  `retry_after`) with monotonic / cap / floor / no-skip / unknown-input tests; `FixOut.retry_ms`.
- TripService: FGS type `location`; `requestUpdates` re-registers on every (interval, provider) change
  on both API paths; watchdog = interval + grace; direct `onWatchdog` for a live service (also
  re-requests updates); background restart keeps `TripPrefs` and posts "tap to resume";
  `ACCESS_BACKGROUND_LOCATION` declared and offered from a non-blocking Trip-tab row.
- Alarm: `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` + abandon, single `USAGE_ALARM` attribute set,
  STREAM_ALARM raised to >= 80 % with 4 s ramp and restored, fallback chain ending in
  `ToneGenerator(STREAM_ALARM)`, `OnErrorListener`, `VibrationAttributes.USAGE_ALARM`, 10 min ring
  timeout and re-ring, process-death guard, channel IMPORTANCE_HIGH with alarm sound attrs and DND
  bypass, `canUseFullScreenIntent` check with Stops UI settings row, `AlarmActivity` over lock screen.
- Desktop: `rovsti_android` functions (incl. `Group::BackgroundLocation`) return `Error::Unsupported`.
- Docs: `docs/architecture.md` AD-SA1..AD-SA9, `docs/android.md`, `docs/xiaomi.md` Stops section.
- Spot checks at the base: `cargo fmt --all --check` clean; `cargo test -p rovsti_geo -p rovsti_android`
  green (212 + 40).

## Cosmetic note (not a step)

- `TripService.kt` `onWatchdog` (lines ~484-488): the lambda body added in 75d8adf is mis-indented.
  Harmless; fix it opportunistically with the next Kotlin edit.
