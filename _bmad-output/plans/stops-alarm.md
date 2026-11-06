# Plan: Rovsti Stops, adaptive polling and alarm-clock-grade alarm (round 1)

- Base: `e5d05671787e6ab3782eeb7924da0c509287c587` ("WIP: carry-over before sync")
- Architect: PLAN agent. No source changed by this plan.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
- No device and no Gradle run here. Kotlin is verified by reading plus the cargo "Kotlin contract"
  test introduced in step 5 (it `include_str!`s the Kotlin sources and asserts the required calls and
  mirrored constants). Disk: if `df` shows < 6 GB free, `rm -rf target/debug/incremental` first.

## What exists at the base (verified)

- `crates/rovsti_geo/src/crossing.rs` `CrossingTrip::next_fix_in` (private): Tracking ->
  `slack = line_m - acc - max(DEAD_BAND_M, acc)`, `slack / MAX_SPEED_MPS / 2` clamped to 3..300 s;
  not Tracking -> 60 s. A garbage coordinate answers a hard-coded 3 s. NaN accuracy counts as 0
  (optimistic, wrong). Only one test (`trip_next_fix_and_eta`) touches the curve (300 s far, 3 s near).
  `MAX_SPEED_MPS = 60`, `MAX_ACCURACY_M = 150` are `pub(crate)` in `trip.rs`.
- `crates/rovsti_geo/src/session.rs` `FixOut { next_fix_ms, high_accuracy (<= 15 s), ... }`;
  `ActiveTrip::snooze` caps at `MAX_SNOOZES = 5`.
- `TripService.kt`: foreground service type `location` (`ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION`,
  manifest `foregroundServiceType="location"`), `LocationManager` only, GPS / `FUSED_PROVIDER` /
  network via `tripProvider`, `LocationRequest.Builder` on S+ and the deprecated overload below;
  `requestUpdates` re-registers whenever `(interval, provider)` changes (OK). Watchdog
  `tripWatchdogMs = 2 * interval + 30 s` (630 s at the 300 s cap: breaks the "never longer than
  5 min" spec when fixes stop). Watchdog -> `getCurrentLocation`; a `null` location is dropped
  silently (no status, no other provider). Missing accuracy is sent as 1000 m.
- `TripAlarm.kt`: `RingtoneManager` TYPE_ALARM actual -> default alarm, then **nothing** (silent if
  both fail); `MediaPlayer` with USAGE_ALARM, no `OnErrorListener`; no audio focus; no alarm-volume
  raise/restore; vibration with `VibrationAttributes.USAGE_ALARM` (OK); wake lock capped at 10 min
  (OK); after 10 min it just goes silent (no re-ring); channel `trip_alarm` IMPORTANCE_HIGH with
  `setSound(null, null)`; `setFullScreenIntent` without checking `canUseFullScreenIntent`. Process
  death mid-ring leaves nothing to re-ring.
- `AlarmActivity.kt`: `setShowWhenLocked`, `setTurnScreenOn`, `FLAG_KEEP_SCREEN_ON` (OK).
- Facade already there: `rovsti_android::permissions::{can_use_full_screen_intent,
  open_full_screen_intent_settings}` (desktop `Unsupported`), `Preflight.full_screen`, and the
  "Let the alarm wake the screen" row in `apps/stops/src/trip_ui.rs:212`. Gap: `Preflight` is
  gathered only at startup (`main.rs:273`) and after the row is tapped it is set to `None`, so the
  notice never comes back or refreshes after the user returns from Settings.
- `apps/stops/src/headless.rs` `trip_alarm` accepts only `dismiss | snooze`.
- GPUI API verified at the pinned rev: `Context::observe_window_activation(window, cb)`
  (`crates/gpui/src/app/context.rs:462`), already used in `apps/contacts/src/main.rs:183`.

## Decisions

- **AD-SA1 One explicit polling curve** in a new pure module `rovsti_geo::poll`. Interval =
  `slack / (2 * MAX_SPEED_MPS)` clamped to `[MIN_POLL = 3 s, MAX_POLL = 300 s]`, where
  `slack = line_m - acc - max(DEAD_BAND_M, acc)`. Unknown or unusable accuracy counts as
  `MAX_ACCURACY_M` (pessimistic). Unknown distance (no usable fix yet) -> `MAX_POLL`. A garbage or
  unusable fix reuses the last usable distance. Not Tracking -> `IDLE_POLL = 60 s`.
- **AD-SA2 Retry window** `retry_after(interval) = interval + grace`, `grace = (interval / 4)`
  clamped to 5..30 s. Rust sends it as `FixOut.retry_ms`; Kotlin uses it for the watchdog and keeps
  a mirrored fallback formula for the start spec.
- **AD-SA3 Alarm policy is Rust data** (`rovsti_geo::alarm::AlarmPlan`), sent to Kotlin inside the
  start spec (`"alarm": {...}`) and saved with `TripPrefs`. Kotlin has identical defaults so it
  rings even if Rust never answered. The cargo contract test keeps both in sync.
- **AD-SA4 Never silent**: sound sources in order `user_alarm, default_alarm, default_ringtone,
  default_notification, tone` (ToneGenerator on STREAM_ALARM; no bundled raw sound because
  `rovsti_android` ships no resources). MediaPlayer errors (`OnErrorListener`, exceptions) advance
  to the next source. Vibration with alarm usage runs independently.
- **AD-SA5 Ring timeout**: after `ring_timeout` (10 min) the alarm stops and re-rings after the
  user's snooze length; timeouts do not consume `MAX_SNOOZES` and are capped at
  `MAX_TIMEOUT_RERINGS = 6` (about 90 min of attempts). A guard alarm armed at ring start
  (`setAlarmClock` at `now + timeout + rering`) re-rings after process death.
- **AD-SA6 Routing**: every audio, focus and vibration call uses one `ALARM_ATTRS`
  (`USAGE_ALARM`, `CONTENT_TYPE_SONIFICATION`); no `setPreferredDevice`, no communication device,
  no STREAM_MUSIC. Android then plays alarms on the speaker and the headset like the stock clock.

## Steps

### 1. Explicit polling curve (pure, `rovsti_geo::poll`)

- New `crates/rovsti_geo/src/poll.rs`, `pub mod poll;` in `lib.rs`. Make `MAX_SPEED_MPS` and
  `MAX_ACCURACY_M` usable from it (they are `pub(crate)` already; re-export as `pub` consts in
  `poll` with docs).
- API:
  ```rust
  pub const MIN_POLL: Duration = Duration::from_secs(3);
  pub const MAX_POLL: Duration = Duration::from_secs(300);
  pub const IDLE_POLL: Duration = Duration::from_secs(60);
  /// Never longer than MAX_POLL; None distance -> MAX_POLL; None/NaN/inf/>MAX accuracy -> MAX_ACCURACY_M.
  pub fn poll_interval(line_m: Option<f64>, accuracy_m: Option<f64>) -> Duration;
  /// interval + clamp(interval / 4, 5 s, 30 s).
  pub fn retry_after(interval: Duration) -> Duration;
  ```
  Module doc: the curve formula, why `/2` (a fix at worst-case speed covers at most half the
  slack, so two consecutive fixes cannot jump over the band unseen), table of example values
  (0 m -> 3 s, 1 km -> ~8 s, 10 km -> ~83 s, >= 36 km -> 300 s at 5 m accuracy).
- `CrossingTrip::next_fix_in` delegates: Tracking + usable fix -> `poll_interval(Some(line_m),
  Some(acc))`; unusable accuracy -> `poll_interval(self.last_line_m (before this fix), None)`;
  garbage coordinate -> `poll_interval(self.last_line_m, None)` (replaces the hard-coded 3 s);
  not Tracking -> `IDLE_POLL`.
- Tests in `poll.rs`: monotonic (sweep 0..100 km step 50 m x accuracies {0, 5, 50, 150, NaN,
  None}: farther never shorter); `MAX_POLL` exactly when far and never exceeded (also for
  `f64::INFINITY`, huge values); `MIN_POLL` at 0 m, negative and NaN distance; safety:
  for every unclamped interval `interval_s * MAX_SPEED_MPS <= slack / 2 + 1e-6`; unknown distance
  -> 300 s; unknown accuracy is never longer than accuracy 150; `retry_after` bounds (3 s -> 8 s,
  300 s -> 330 s, monotonic). In `crossing.rs`: update `trip_next_fix_and_eta`; extend
  `garbage_fixes_neither_panic_nor_change_state` to assert the interval equals the curve at the
  last known distance; a new test that a first fix with accuracy 1000 m gives 300 s.
- Acceptance: checks green; `grep -n "clamp(3.0, 300.0)" crates/rovsti_geo` finds nothing.

### 2. `FixOut.retry_ms` and the alarm policy (pure, `rovsti_geo`)

- `session.rs`: `FixOut` gains `#[serde(default)] pub retry_ms: u64` = `retry_after(next_fix_in)`.
  Test in `session.rs` (far and near) and update the serde fixture there.
- New `crates/rovsti_geo/src/alarm.rs` (`pub mod alarm;`, re-export `AlarmPlan, SoundSource`):
  ```rust
  pub const MIN_ALARM_VOLUME_PCT: u8 = 80;
  pub const RAMP: Duration = Duration::from_secs(4);          // full loudness within 5 s
  pub const RING_TIMEOUT: Duration = Duration::from_secs(600);
  pub const MAX_TIMEOUT_RERINGS: u8 = 6;
  #[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)] #[serde(rename_all = "snake_case")]
  pub enum SoundSource { UserAlarm, DefaultAlarm, DefaultRingtone, DefaultNotification, Tone }
  pub const SOUND_ORDER: [SoundSource; 5];
  impl SoundSource { pub fn key(self) -> &'static str; pub fn next(self) -> Option<Self>; }
  /// max(current, ceil(max * 80%)), clamped to min..=max; max <= 0 -> max.
  pub fn volume_target(current: i32, min: i32, max: i32) -> i32;
  /// Linear from max(ceil(target / 2), min, 1) to target over RAMP; target after RAMP.
  pub fn ramp_volume(target: i32, min: i32, elapsed: Duration) -> i32;
  #[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
  pub struct AlarmPlan { pub volume_pct: u8, pub ramp_ms: u64, pub ring_timeout_ms: u64,
                         pub sound_order: Vec<SoundSource>, pub vibrate_pattern_ms: Vec<u64> }
  impl Default for AlarmPlan { /* the consts above, VIBRATION = [0,700,500,700,1500] */ }
  ```
- `session.rs` `ActiveTrip`: `#[serde(default)] pub timeouts: u8`;
  `pub fn ring_timed_out(&mut self, now_ms: i64, minutes: u8) -> Option<i64>` (sets `ring_at_ms`,
  does not touch `snoozes`, `None` after `MAX_TIMEOUT_RERINGS` or when not Arrived). Old
  `trip.json` without `timeouts` still loads (test).
- Tests: `volume_target` never lowers a louder user level, reaches >= 80 % for every max 1..=30
  and current 0..=max, handles max 0 / min > current; `ramp_volume` monotonic, >= half target at
  0, == target at and after `RAMP <= 5 s`; `SOUND_ORDER` ends in `Tone`, has no duplicates,
  `next` walks it; `AlarmPlan` serde round-trip and default JSON keys (`volume_pct`, `ramp_ms`,
  `ring_timeout_ms`, `sound_order`, `vibrate_pattern_ms`); `ring_timed_out` cap, independence from
  snoozes.

### 3. Stops wiring of the policy (Rust, `apps/stops`)

- `backend.rs` `start_spec` adds `"alarm": AlarmPlan::default()` (test in `backend.rs` asserts
  the key and `volume_pct >= 80`).
- `headless.rs`: `answer()` includes `retry_ms`; `alarm_in` accepts `"timeout"`: loads prefs'
  `snooze_min`, calls `trip.ring_timed_out`, saves, answers `{"ring_at_ms": n}` or
  `{"silenced": true}` (trip stays Arrived, not ended). Update the module table doc. Tests:
  timeout re-arms without consuming snoozes, cap answers `silenced`, unknown trip answers `ENDED`.
- `rovsti_android::trip`: `TripAlarmEvent::TimedOut { at_ms }` parsed from
  `{"event":"timed_out","at_ms":n}` (extend `alarm_events_parse`); `AlarmAction` unchanged.
- `main.rs`/`trip_ui.rs`: handle `TimedOut` like `Snoozed` in the live card text ("Rings again at
  HH:MM").

### 4. Preflight refresh and full-screen notice (Rust + tiny Kotlin)

- `apps/stops/src/main.rs`: in the window setup, `cx.observe_window_activation(window, |this,
  window, cx| if window.is_window_active() { this.refresh_preflight(cx) })` (same shape as
  `apps/contacts/src/main.rs:183`); keep the subscription in a `_subscriptions` field.
- `trip_ui.rs`: tapping "Open" no longer sets `full_screen = None` permanently; the activation
  refresh brings the row back if still denied. Also show the row on the live card while a trip
  runs (it already shows on the Trip tab; keep one row).
- `RovstiBridge.canUseFullScreenIntent`: `Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()`
  (the API only exists on 14+; the shared bridge is also built for minSdk 26 hosts).
- Test: a pure helper `fn full_screen_notice(pre: &Preflight) -> Option<&'static str>` in
  `backend.rs` with tests for `Some(false)` / `Some(true)` / `None`.

### 5. TripService hardening + Kotlin contract test

- `TripService.kt`:
  - `TripAnswer` parses `retry_ms`; `TripSpec` parses and persists `alarm` (raw JSON string,
    `TripPrefs.alarmPlan(context)`).
  - `tripWatchdogMs(intervalMs) = interval + (interval / 4).coerceIn(5_000, 30_000)` (mirror of
    `poll::retry_after`); `armWatchdog(answer.retryMs.takeIf { it > 0 } ?: tripWatchdogMs(...))`.
  - `askForFix`: on `null` location try the other provider once (`tripProvider(!highAccuracy, ...)`),
    then emit `{"state":"searching"}` (watchdog stays armed from `onStartCommand`), never silent.
  - Keep re-request on every changed interval (already there) for both the `LocationRequest`
    (S+) and legacy paths; when `IllegalArgumentException` drops the provider, re-arm the
    watchdog with `tripWatchdogMs(TRIP_MIN_INTERVAL_MS * 10)`.
- New `apps/stops/src/kotlin_contract.rs` (`#[cfg(test)] mod kotlin_contract;` in `main.rs`):
  `include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/TripService.kt")` and
  assert: `FOREGROUND_SERVICE_TYPE_LOCATION`, `requestLocationUpdates` twice (both paths),
  `retry_ms`, `TRIP_MAX_INTERVAL_MS = 300_000L` equals `poll::MAX_POLL`, the watchdog grace
  literals `5_000` and `30_000`; manifest
  (`apps/stops/platforms/android/app/src/main/AndroidManifest.xml`) has
  `foregroundServiceType="location"` and `FOREGROUND_SERVICE_LOCATION`.

### 6. TripAlarm audio: focus, volume, routing, fallback chain (Kotlin, thin)

- New `crates/rovsti_android/kotlin/dev/rovsti/bridge/AlarmSound.kt` (`internal class
  AlarmSound(context, plan)`), used by `TripAlarm`:
  - `ALARM_ATTRS = AudioAttributes.Builder().setUsage(USAGE_ALARM).setContentType(CONTENT_TYPE_SONIFICATION).build()`.
  - Focus: `AudioFocusRequest.Builder(AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(ALARM_ATTRS)
    .setOnAudioFocusChangeListener { }` (loss is ignored: the alarm keeps ringing);
    `requestAudioFocus` before playing, `abandonAudioFocusRequest` in `stop()`.
  - Volume: save `getStreamVolume(STREAM_ALARM)` into SharedPreferences `rovsti_alarm`
    (`saved_volume`) before changing; target `alarmVolumeTarget(cur, min, max, plan.volumePct)`
    and ramp every 250 ms with `rampVolume` (Kotlin mirrors of step 2, same formulas), wrapped in
    `try { setStreamVolume } catch (SecurityException)`; `stop()` restores and clears the pref.
    `TripAlarm.recover(context)` restores a stale saved volume when nothing rings (called from
    `TripService.onCreate`, `TripAlarmReceiver.onReceive`, `RovstiBridge` init).
  - Sources in `plan.soundOrder`: `RingtoneManager.getActualDefaultRingtoneUri(TYPE_ALARM)`,
    `getDefaultUri(TYPE_ALARM)`, `getDefaultUri(TYPE_RINGTONE)`, `getDefaultUri(TYPE_NOTIFICATION)`,
    then `ToneGenerator(AudioManager.STREAM_ALARM, 100)` repeating `TONE_CDMA_ALERT_CALL_GUARD` via
    the main Handler. Each MediaPlayer gets `setAudioAttributes(ALARM_ATTRS)`, `isLooping = true`,
    `setOnErrorListener { _, _, _ -> next(); true }`; `IOException` / `RuntimeException` /
    null URI -> next. No `setPreferredDevice`, no `STREAM_MUSIC`.
  - Vibration: `VibrationAttributes.createForUsage(USAGE_ALARM)` with `plan.vibratePatternMs`,
    repeat index 0 (works in silent/vibrate). The prefs rule "never both off" stays.
- Contract test additions (`kotlin_contract.rs`, `AlarmSound.kt` + `TripAlarm.kt`):
  `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, `abandonAudioFocusRequest`, `STREAM_ALARM`,
  `setStreamVolume`, `setOnErrorListener`, `ToneGenerator`, `TYPE_RINGTONE`, `TYPE_NOTIFICATION`,
  `VibrationAttributes.USAGE_ALARM`; absent: `setPreferredDevice`, `setCommunicationDevice`,
  `STREAM_MUSIC`; Kotlin `80`, `4_000`, `600_000` defaults equal `AlarmPlan::default()`.

### 7. TripAlarm lifecycle: timeout re-ring, guard, channel, full screen (Kotlin, thin)

- Ring timeout: the cap Runnable calls `onRingTimeout`: stop sound/vibration/focus/volume/wake
  lock, `Headless.call("trip_alarm", {dir, action:"timeout"})`, arm `ring_at_ms` with `armSnooze`
  (fallback `snoozeAt(now, TRIP_DEFAULT_SNOOZE_MIN)` when no answer), emit
  `{"event":"timed_out","at_ms":n}`; `silenced` keeps the notification only. Test rings just stop.
- Guard: at `ring()` (non-test) arm `armSnooze(now + ringTimeout + defaultSnooze)` on the same
  `ACTION_RING` PendingIntent, so process death mid-ring re-rings; dismiss/snooze already cancel it.
- `silence()` releases player, ToneGenerator, focus, volume, vibration and wake lock in
  `try/finally`, idempotent; `TripService.onDestroy` does not touch the alarm.
- Channel: new id `trip_alarm_v2` (`deleteNotificationChannel("trip_alarm")`), IMPORTANCE_HIGH,
  `setSound(defaultAlarmUri, ALARM_ATTRS)`, `setBypassDnd(true)`, `lockscreenVisibility = PUBLIC`,
  `enableVibration(false)`; notification `CATEGORY_ALARM`, `VISIBILITY_PUBLIC`,
  `setOnlyAlertOnce(true)`.
- Full screen: if `Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()` log it and
  emit `{"state":"error","error":"full_screen"}` on `trip_status` (UI shows the step 4 row); the
  heads-up notification and sound still run. `AlarmActivity` keeps `setShowWhenLocked`,
  `setTurnScreenOn`, `FLAG_KEEP_SCREEN_ON`, and requests
  `KeyguardManager.requestDismissKeyguard` only on Dismiss tap (not on show).
- Contract test additions: `trip_alarm_v2`, `setBypassDnd`, `canUseFullScreenIntent`,
  `"timeout"`, `timed_out`, `CATEGORY_ALARM`, `setShowWhenLocked`, `setTurnScreenOn`.

### 8. Docs

- `docs/architecture.md`: AD-ST3 rewritten with AD-SA1..AD-SA6 (curve, retry window, policy
  in Rust, fallback chain, timeout + guard, routing); Stops row of the host table mentions audio
  focus and alarm volume.
- `docs/android.md` "Stops host": channel `trip_alarm_v2`, DND alarm category, full-screen check.
- `docs/xiaomi.md`: Stops alarm needs Autostart, Battery saver "No restrictions", "Show on lock
  screen" and "Display pop-up windows while running in background"; MIUI "Silent mode/DND"
  keeps alarms when the alarm channel is IMPORTANCE_HIGH with USAGE_ALARM; note that MIUI may
  clamp `setStreamVolume`, and that the alarm still rings at the user's level then.
- Acceptance: checks green; docs mention no API that the code does not use.
