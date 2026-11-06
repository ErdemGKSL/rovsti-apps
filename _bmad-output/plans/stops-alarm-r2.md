# Plan: Rovsti Stops, adaptive polling and alarm-clock-grade alarm (round 2)

- Base: `d5fc511ef962ef7d8f9669c567988f95960153cc`
- Architect: PLAN agent. No source changed by this plan.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
- No device and no Gradle. Kotlin is verified by reading and by `apps/stops/src/kotlin_contract.rs`.
  Disk: if `df` shows < 6 GB free, `rm -rf target/debug/incremental` first.

## Round 1 status (verified at the base)

All 8 steps of `stops-alarm.md` landed (commits 0131b91 .. 5ce0040, docs b6b5594):

- `rovsti_geo::poll` (`poll_interval`, `retry_after`, `MIN_POLL 3 s`, `MAX_POLL 300 s`,
  `IDLE_POLL 60 s`) with monotonic / cap / floor / no-skip / unknown-input tests; `crossing.rs`
  delegates to it. `FixOut.retry_ms`. `rovsti_geo::alarm` (`AlarmPlan`, `SoundSource`,
  `volume_target`, `ramp_volume`, ring timeout, re-ring cap). `cargo test -p rovsti_geo`: 212 pass.
- `AlarmSound.kt`: `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` + abandon, one `USAGE_ALARM` attribute
  set (no preferred device, no STREAM_MUSIC), STREAM_ALARM raised to >= 80 % with a 4 s ramp and
  restored (saved in prefs, `TripAlarm.recover` after process death), fallback user alarm ->
  default alarm -> ringtone -> notification -> `ToneGenerator(STREAM_ALARM)`, `OnErrorListener`,
  `VibrationAttributes.USAGE_ALARM` repeating waveform.
- `TripAlarm.kt`: channel `trip_alarm_v2` IMPORTANCE_HIGH, alarm sound attrs, bypass DND,
  `CATEGORY_ALARM`, full-screen intent with Android 14 `canUseFullScreenIntent` check and
  `{"error":"full_screen"}`; ring timeout -> Rust `timeout` -> re-ring; process-death guard alarm;
  `silence()` releases everything idempotently. `AlarmActivity` shows when locked, turns screen on.
- Stops UI: full-screen settings row, refreshed on window activation.
- `TripService.kt`: FGS type `location`, re-requests updates on every changed (interval, provider)
  on both API paths, watchdog = Rust `retry_ms` (mirror `tripWatchdogMs`), null fix -> other
  provider -> `searching`.

## Remaining gaps (why round 2)

1. **Restart without "Allow all the time" location is silent.** Stops has `minSdk 34`. A location
   foreground service started from the background (system restart after process death via
   `START_REDELIVER_INTENT`, or `TripWatchdogReceiver.startForegroundService` when the process is
   gone) has no while-in-use location access unless `ACCESS_BACKGROUND_LOCATION` is granted;
   `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` then throws. `onStartCommand` catches it,
   emits `error/foreground` to a UI that is not there, and `shutdown()` clears `TripPrefs`: tracking
   ends with no sound and no notification, and the arrival alarm never rings. The manifest has no
   `ACCESS_BACKGROUND_LOCATION` and nothing offers it.
2. **The watchdog re-promotes a running service.** Every watchdog tick goes through
   `startForegroundService` -> `onStartCommand` -> `promote()`; a failure there (same while-in-use
   rule, or FGS-start limits) shuts a healthy trip down. A running service does not need an FGS
   start to ask for a fix.
3. **Redelivered intent wins over the saved spec.** After a restart `onStartCommand` prefers the
   original start intent's spec over `TripPrefs` (which holds the latest `next_fix_ms` /
   `high_accuracy`), so the first interval after restart can be stale.
4. **`searching` is invisible and does not re-arm.** `parse_status` drops `{"state":"searching"}`
   (no fields -> not a `FixOut`); `askOtherProvider`'s null result does not re-arm the watchdog in a
   path that did not come through `onStartCommand`.
5. **No auto-resume on return.** `resume_service` runs only on first load; returning to a live
   process (e.g. from the new "tap to resume" notification) does not restart a dead service.

## Decisions

- **AD-SA7 Background location is recommended, not required.** New permission group
  `Group::BackgroundLocation` (`ACCESS_BACKGROUND_LOCATION`), Stops-only, asked from a non-blocking
  Trip-tab row after foreground location is granted. Without it, Stops still works while the
  service stays alive; with it, restarts after process death keep tracking.
- **AD-SA8 Never end a trip from Kotlin on a restart failure.** If the service cannot go foreground
  on a start that no user caused (watchdog, redelivery, null intent), it keeps `TripPrefs`, posts a
  high-importance "Tap to resume tracking" notification (channel `trip_problem`), emits
  `{"state":"error","error":"restart"}` and stops itself. Rust's `trip.json` stays; opening Stops
  resumes through `backend::resume_trip`.
- **AD-SA9 The watchdog talks to a live service directly**; it uses `startForegroundService` only
  when no instance exists.

## Steps

### 1. `Group::BackgroundLocation` facade (rovsti_android, pure Rust + map entry)

- `crates/rovsti_android/src/permissions.rs`: add
  `/// ACCESS_BACKGROUND_LOCATION ("Allow all the time"), Rovsti Stops only; not in ALL. Android 11+ answers a request with the app's location page.`
  `BackgroundLocation` to `Group`; `key()` -> `"background_location"`. `Group::ALL` unchanged.
  Extend the desktop test: `state(Group::BackgroundLocation)` and `request(...)` are
  `Err(Error::Unsupported)`; a test that every `Group` key is unique and `ALL` excludes
  `Location` and `BackgroundLocation`.
- `RovstiBridge.kt` `groups`: `"background_location" to listOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)`.
- `apps/stops/platforms/android/app/src/main/AndroidManifest.xml`: add
  `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />` after
  `ACCESS_COARSE_LOCATION`.
- `kotlin_contract.rs`: new test `background_location_is_declared_and_mapped` (manifest contains
  `ACCESS_BACKGROUND_LOCATION`; `RovstiBridge.kt` contains `"background_location"`; equals
  `Group::BackgroundLocation.key()`). Add a `const BRIDGE: &str = include_str!(".../RovstiBridge.kt")`.
- Acceptance: checks green; `grep -n BackgroundLocation crates/rovsti_android/src/permissions.rs`.

### 2. Stops pure logic: preflight row, `searching`, `restart` (apps/stops, Rust, tested)

- `backend.rs` `Preflight`: `pub background_location: Option<Access>`, gathered with
  `answer(permissions::state(Group::BackgroundLocation))`. `next_step()` unchanged (not a blocker).
- `backend.rs`:
  ```rust
  /// The "keep tracking if Android closes Stops" row: only when foreground location is granted
  /// and background location is known to be missing.
  pub fn background_location_notice(pre: &Preflight) -> Option<&'static str>;
  /// Asks for "Allow all the time" (Denied) or opens the app page (PermanentlyDenied). Blocking (JNI).
  pub fn request_background_location(pre: &Preflight);
  ```
  Tests: `None` when location is not granted, when background is `Some(Granted)` or `None`;
  `Some` for `Denied` and `PermanentlyDenied` with location granted; `next_step` ignores it.
- `trip_ui.rs`: `Status::Searching`; `parse_status` maps `"searching"` before the `FixOut`
  fallback; `error_message("restart")` = "Android closed Stops and tracking paused. It resumes
  when Stops is open; allow location all the time to avoid this.". `on_status_json`:
  `Searching` -> `show_pill("Looking for your location...", None, cx)` only when a trip runs
  (no state change). Tests: `parse_status` for `searching` and `error/restart`.
- `trip_ui.rs` `trip_tab`: a second row under the full-screen row, same shape
  (`list_row(theme, None, "Keep tracking in the background", Some(hint), Some("Allow"))`, id
  `"background-location-row"`), click spawns `backend::request_background_location(&pre)` on the
  background executor (copy `Preflight`, it is `Copy`) and `cx.notify()`; the activation refresh
  re-reads it. Use only APIs already used in this file.
- `trip_ui.rs` `refresh_trip`: after the trip is reloaded, call `self.resume_service(cx)` when
  `self.trip.is_some()` (`resume_trip` is a no-op when the service runs). Keep the existing pill
  for a failed resume.
- Acceptance: checks green; new unit tests in `backend.rs` and `trip_ui.rs` pass.

### 3. TripService: direct watchdog, single promote, saved spec first, restart notice (Kotlin, thin)

- `TripService.kt` pure helpers (top level, `internal`):
  ```kotlin
  /** Pure: true when nobody in the UI caused this start (system restart, redelivery, watchdog). */
  internal fun tripStartIsBackground(action: String?, flags: Int, hasSpecExtra: Boolean): Boolean =
      action == TripService.ACTION_WATCHDOG || (flags and Service.START_FLAG_REDELIVERY) != 0 ||
          (flags and Service.START_FLAG_RETRY) != 0 || !hasSpecExtra
  /** Pure: the `trip_status` payload for a background start Android refused. */
  internal fun tripRestartErrorJson(): String
  ```
- `onStartCommand`:
  - Spec precedence: background start -> `spec ?: TripPrefs.load(this) ?: TripSpec.parse(extra)`;
    user start -> `TripSpec.parse(extra) ?: spec ?: TripPrefs.load(this)` (as today).
  - `promote()` only when `!foreground` (new `private var foreground = false`, set true after a
    successful `startForeground`, false in `shutdown`/`onDestroy`); otherwise just `notify()` the
    trip notification.
  - On promote failure: user start -> today's behaviour (`error/foreground`, `shutdown`);
    background start -> `emitStatus(tripRestartErrorJson())`, `postRestartNotice()`, `removeUpdates()`,
    `cancelWatchdog()`, `stopSelf()` **without** `TripPrefs.clear` (AD-SA8).
  - `postRestartNotice()`: channel `trip_problem` ("Trip paused", IMPORTANCE_HIGH, default sound),
    id `NOTIFICATION_ID + 1`, title "Stops paused your trip", text "Tap to resume tracking.",
    content intent = launch intent (same as `notification()`), `setAutoCancel(true)`,
    `CATEGORY_ERROR`. Cancel it in a successful `promote()`.
- Direct watchdog (AD-SA9): companion `@Volatile private var instance: TripService?` set in
  `onCreate`, cleared in `onDestroy`; `fun onWatchdog(context: Context)`: if `instance` and its
  `spec` exist -> `armWatchdog(tripWatchdogMs(spec.nextFixMs))` then `worker.execute { askForFix(spec) }`
  (catch `RejectedExecutionException`); else `startForegroundService(ACTION_WATCHDOG)` as today.
  `TripWatchdogReceiver.onReceive` calls `TripService.onWatchdog(context)`.
- `askOtherProvider`: on a null location also `armWatchdog(tripWatchdogMs(spec.nextFixMs))`
  (pass the spec in), so `searching` always retries within interval + grace (<= 330 s).
- `kotlin_contract.rs` additions: `START_FLAG_REDELIVERY`, `trip_problem`, `"restart"` (and
  `trip_ui::parse_status` of `tripRestartErrorJson`'s literal JSON yields
  `Status::Error(..)` with the restart text: expose `error_message` as `pub(crate)` if needed),
  `TripService.onWatchdog(` in the receiver, `if (!foreground)` guard, and that `shutdown(`
  is not the only consequence of a promote failure (assert `postRestartNotice()` present).
- Acceptance: checks green; reading `onStartCommand` shows no path where a background start clears
  `TripPrefs` without the user stopping the trip or Rust answering `ended`.

### 4. Docs

- `docs/architecture.md` Stops section: AD-SA7..AD-SA9 (background location recommended; restart
  failure keeps the trip and posts "tap to resume"; watchdog reaches a live service directly; Stops
  resumes the service on window activation).
- `docs/android.md` "Stops host": `ACCESS_BACKGROUND_LOCATION` in the manifest and why (Android 11+
  while-in-use rule for location FGS started from the background), channel `trip_problem`.
- `docs/xiaomi.md`: MIUI "Location -> Allow all the time" for Stops, plus the existing Autostart /
  battery "No restrictions" note (MIUI kills the process more often, making AD-SA7 matter more).
- Acceptance: checks green; docs mention no API the code does not use.
