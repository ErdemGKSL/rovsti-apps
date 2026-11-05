# Plan: Rovsti Stops

- Base: `87ac870d88f80fae45a4df46d3307a0de7abd8fd` ("WIP: carry-over before sync")
- Unit: Rovsti Stops. Architect: PLAN agent. No source changed by this plan.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`
- Nothing runs on a device here. Kotlin is checked by reading only; it keeps the strings-only
  JNI seam, and every Rust seam function returns `Error::Unsupported` on desktop.

## What the code already has (source of truth at the base)

- `apps/stops/src/main.rs` (235 lines) is a scaffold: three tabs (Trip, Saved, Settings), a
  four-chip "Wake me" picker over `RULES` (300 m, 500 m, 1 km, 2 min ETA), an empty state and a
  dead "Choose destination" pill. Settings is the shared `rovsti_wa_ui::WhatsAppSettings`
  (`AppId::Stops`, data dir `"stops"`), wired with `WhatsAppSettings::forward_push`.
- `rovsti_geo` (pure, serde only): `Coord` (haversine), `Trip`, `AlarmRule::{Radius, Eta}`, `Fix`,
  `TripState`, `TripUpdate { state, distance_m, ring, next_fix_in }`, adaptive polling 3..300 s,
  fixes worse than 150 m ignored. Six unit tests. `Trip` is not serde (private fields).
- Shared WhatsApp: link first time / connect with key, keys hashed at rest, revocable, link in
  `whatsapp-link.json` (done for all four apps, `docs/whatsapp-notes.md`).
  `rovsti_wa_ui::headless` already sends text without GPUI (`wa_reply`, private `gateway_in`).
  `Gateway::send_text(&SendText { chat, text, quoted_id })` and `Gateway::chats()` exist
  (`crates/rovsti_wa_client/src/http.rs`), blocking (ureq).
- `rovsti_android`: strings-only bridge (`bridge::call_bool/int/string`), event sink
  (`events::install_sink_owned`), one-shot `Mailbox`, headless JNI entry (`headless_entry!`,
  Kotlin `Headless.call(kind, json)` from receivers/services with no UI), permission groups
  (`permissions::Group`, Kotlin map in `RovstiBridge.missing`), `documents::import_file` (op
  hard-wired to `import_messages`), `xiaomi::request_ignore_battery_optimizations`,
  `permissions::can_use_full_screen_intent`.
- GPUI (verified at `~/.cargo/git/checkouts/gpui-fusion-141089add1664e03/d6c8412`):
  `Application::on_open_urls(FnMut(Vec<String>))` (`crates/gpui/src/app.rs:273`), fed by the host
  for every `ACTION_VIEW` intent (`GpuiSession.onOpenIntent`); `Application::on_receive_share`
  with `ReceivedShare { text, mime_type, files }` (`platform/share.rs:16`) for `ACTION_SEND`;
  `canvas(prepaint, paint)` (`elements/canvas.rs:10`), `PathBuilder::{stroke(px), fill(),
  move_to, line_to, arc_to, add_polygon, close, build}` (`path_builder.rs`),
  `Window::paint_path(path, color)` and `Window::paint_quad(quad(..)/fill(..)/outline(..))`
  (`window/paint/primitives.rs`).

Gaps found while studying (fixed by this plan):

1. Stops has **no ejected Android host** (`apps/stops/platforms` missing; `gpuiforge.json` is
   unmanaged), so no service, alarm activity or receivers can exist.
2. Stops' `gpuiforge.json` has **no `INTERNET`** permission: the shared WhatsApp settings cannot
   reach the gateway on a phone.
3. `WhatsAppSettings::forward_push` installs the process-wide event sink and drops every
   non-`wa_push` event. Stops will need its own Kotlin events, so it must own the sink and call
   `whatsapp.update(cx, |w, cx| w.wake(cx))` itself (as Phone and Contacts do).
4. `documents::import_file` always answers op `import_messages`; GTFS needs its own op.

## Decisions (AD-ST)

- **AD-ST1 Pure first.** The trip session (fix handling, snooze, the arriving-soon notice),
  destination parsing, saved places, history and statistics, prefs, GTFS parsing and the map
  projection are `rovsti_geo` modules with unit tests. Kotlin and GPUI only map.
- **AD-ST2 The trip runs headless.** A Kotlin foreground service (`foregroundServiceType=
  "location"`) owns location updates. On every fix it calls `Headless.call("trip_fix", json)`;
  Rust (`apps/stops/src/headless.rs`) loads `trip.json`, runs `ActiveTrip::on_fix`, saves, maybe
  sends the WhatsApp notice, and answers `{state, ring, next_fix_ms, high_accuracy, title, text}`.
  Kotlin re-requests updates at `next_fix_ms`, updates the ongoing notification and, on `ring`,
  starts the alarm. The UI, when attached, gets the same answer as a `trip_status` event. So a
  trip survives the activity being killed, and the logic stays in tested Rust. All trip-file
  mutations in the process go through one `static TRIP_LOCK: Mutex<()>` in `apps/stops/src/store.rs`.
- **AD-ST3 The alarm does not need GPUI.** Ringing is Kotlin: a full-screen-intent notification
  (`CATEGORY_ALARM`) opening a small native `AlarmActivity` (`setShowWhenLocked`,
  `setTurnScreenOn`, Dismiss and Snooze buttons), `MediaPlayer` on the default alarm URI with
  `AudioAttributes.USAGE_ALARM`, looping vibration with `VibrationAttributes.USAGE_ALARM`, a
  partial wake lock only while ringing (cap 10 min). Snooze re-rings through
  `AlarmManager.setAlarmClock` (exempt from Doze); `USE_EXACT_ALARM` is declared (Stops is an alarm
  app), with `setExactAndAllowWhileIdle` as fallback when `canScheduleExactAlarms()` is false. A
  watchdog exact alarm (2 x expected interval + 30 s) asks `getCurrentLocation` when no fix
  arrived, so Doze cannot silently starve the trip. GPUI draws its own alarm screen only when the
  app is open.
- **AD-ST4 Map: no tiles.** A bundled region bloats the APK and goes stale; importing MBTiles
  needs a tile/vector style renderer that does not exist for GPUI here. Decision: a minimal
  vector "radar" map drawn with `canvas` + `PathBuilder`: destination, alarm ring, your position
  with its accuracy, the breadcrumb of this trip, and (after a GTFS import) nearby stops and the
  chosen line's pattern. Equirectangular projection around the view centre (`rovsti_geo::view`),
  pinch/drag to pan and zoom, tap the nearest stop to pick it. Raster tiles stay out of scope; the
  roadmap item is ticked as "decided: vector radar map, no tiles".
- **AD-ST5 Destinations are local.** Sources: shared `geo:` URIs and maps links (`on_open_urls`,
  `on_receive_share`, or pasted text), saved places, recent trip destinations, GTFS stop search.
  No geocoding: a `geo:0,0?q=<address>` or a short link (`maps.app.goo.gl`, `goo.gl/maps`) that
  carries no coordinates is refused with "This link has no coordinates. Share the pin from your
  maps app instead." Nothing is fetched to resolve it.
- **AD-ST6 GTFS is optional and local.** Import a GTFS `.zip` through the file picker (op
  `import_gtfs`, 64 MiB cap from Kotlin `Documents.MAX_FILE_BYTES`). `zip` 2.6 (deflate via
  `flate2`, pure Rust) extracts `stops.txt`, `routes.txt`, `trips.txt`, `stop_times.txt`;
  `rovsti_geo::gtfs` streams them into a compact `Feed { stops, routes, patterns }` (distinct
  ordered stop lists per route and headsign) saved as `gtfs.json`. Caps: 100 000 stops,
  20 000 patterns, 8 000 000 stop_times rows; over a cap the import is refused with a reason.
  "N stops before" (1 to 3) is a new rule `AlarmRule::BeforeStop { trigger, metres }` whose
  trigger is the stop N positions earlier in the chosen pattern.
- **AD-ST7 Arriving-soon WhatsApp message is opt-in and through the gateway.** Settings picks
  one chat (from `Gateway::chats()`, groups allowed), a template (default
  `"Arriving at {stop} in about {min} min."`, max 500 chars) and when: with the alarm, or N
  minutes before (5/10/15, an `AlarmRule::Eta` of its own). It is per-trip toggleable on the Trip
  tab. The headless fix handler sends it with `rovsti_wa_ui::headless::send_text_in` (blocking on
  the service thread). At most once per trip: `notice.sent` is persisted before the send; a send
  that failed with a transport error (`ClientError::Http`/`Tls`, no response) is retried on the
  next fixes, at most 3 attempts; `Unauthorized`/`Api` errors stop retrying. No WhatsApp link or
  no chat chosen means the option is hidden. Nothing else leaves the phone.
- **AD-ST8 History is local.** `history.json` (cap 500, newest first) holds each ended trip:
  label, destination, rule, started/ended ms, outcome (`Arrived`, `Cancelled`), travelled metres,
  snoozes, notice sent. Stats are computed in `rovsti_geo::history`.

## Steps

### Step 1. Pure trip session and notice (`rovsti_geo`)

Files: `crates/rovsti_geo/src/{trip.rs, session.rs (new), notice.rs (new), lib.rs}`.

- `trip.rs`: derive `Serialize, Deserialize` on `Fix`, `TripState`, `Trip` (private fields stay
  private). Move the threshold test into `pub fn AlarmRule::is_met(&self, distance_m: f64, fix: &Fix) -> bool`
  (behaviour unchanged; `Trip::should_ring` calls it). Add
  `AlarmRule::BeforeStop { trigger: Coord, metres: f64 }`: met when the effective distance to
  `trigger` is `<= metres`, or when the distance to the destination is already
  `<= trigger.distance_m(destination)` (passed it). `Trip::update` needs the destination for that;
  pass it via `is_met_for(&self, destination: Coord, distance_m, fix)`; keep `is_met` for the
  two old variants. `next_fix_in` threshold for `BeforeStop` = `trigger.distance_m(dest) + metres`.
  Add `TripUpdate::eta: Option<Duration>` (distance / speed when speed >= `MIN_SPEED_MPS`).
- `session.rs`: `ActiveTrip { id: u64, label: String, trip: Trip, started_ms: i64,
  snoozes: u8, ring_at_ms: Option<i64>, track: Track, notice: Option<NoticeState> }`,
  `Track { last: Option<Coord>, travelled_m: f64, fixes: u32 }` (adds only fixes with accuracy
  <= 150 m, ignores moves shorter than the sum of both accuracies),
  `NoticeState { chat: String, text_template: String, rule: AlarmRule, sent: bool, attempts: u8 }`.
  `pub fn on_fix(&mut self, fix: Fix, at_ms: i64) -> FixOutcome` with
  `FixOutcome { update: TripUpdate, send_notice: Option<String> }` (the rendered text when the
  notice rule is met, not yet sent and attempts < 3; "with the alarm" = the trip's own rule).
  `pub fn notice_result(&mut self, delivered: NoticeDelivery)` (`Sent`, `RetryLater`, `GiveUp`).
  `pub fn snooze(&mut self, now_ms: i64, minutes: u8) -> Option<i64>` (the next ring time;
  minutes clamped 1..=15; `None` after 5 snoozes, the caller then dismisses), `dismiss()`.
  Wire types (serde): `FixIn { lat, lon, accuracy_m, speed_mps: Option<f64>, at_ms }`,
  `FixOut { state, distance_m, next_fix_ms: u64, high_accuracy: bool, ring: bool, title, text,
  notice_sent: bool }` with `pub fn fix_out(&self, o: &FixOutcome) -> FixOut`
  (`high_accuracy` when `next_fix_in <= 15 s`; text like "1.2 km to Kadikoy" / "about 4 min").
- `notice.rs`: `pub const DEFAULT_TEMPLATE`, `pub fn render(template: &str, stop: &str,
  eta: Option<Duration>) -> String` (`{stop}`, `{min}`; `{min}` without an ETA becomes "a few";
  output trimmed and capped at 500 chars on a char boundary), `pub fn validate(template) ->
  Result<(), TemplateError>` (empty, too long).
- Tests: `is_met` parity for Radius/Eta with the old tests; BeforeStop rings at the trigger and
  when the trigger was skipped between fixes; serde round trip of `Trip` mid-trip keeps state;
  `ActiveTrip`: travelled distance ignores jitter, notice fires once and before the alarm when
  its ETA rule is earlier, retry stops after 3 attempts and on `GiveUp`, snooze clamps and caps;
  `render` placeholders, caps, unicode boundary.

Acceptance: `cargo test -p rovsti_geo` covers all new behaviour; existing tests unchanged and green.

### Step 2. Pure destinations, places, history, prefs (`rovsti_geo`)

Files: `crates/rovsti_geo/src/{dest.rs, places.rs, history.rs, prefs.rs}` (new), `Cargo.toml`
(add `rovsti_core.workspace = true` for `rovsti_core::search::fold`).

- `dest.rs`: `pub struct SharedPlace { pub coord: Coord, pub label: Option<String> }`,
  `pub enum DestError { NoCoordinates, OutOfRange, NotALocation }` with user-facing `message()`.
  `pub fn parse_location(text: &str) -> Result<SharedPlace, DestError>`: finds the first URI-ish
  token in free text (shares often add a title line). Handles `geo:lat,lon[;u=..][?z=..]`,
  `geo:0,0?q=lat,lon(Label)`, `geo:lat,lon?q=Label`; Google Maps `/@lat,lon,zoom`, `?q=lat,lon`,
  `?ll=`, `!3dlat!4dlon` (preferred over `@` for `/place/`), `query=lat,lon`; OpenStreetMap
  `?mlat=&mlon=` and `#map=z/lat/lon`; Apple `maps.apple.com/?ll=`/`q=`/`daddr=`; plain
  `41.0370, 28.9850`. Percent-decoding and `+` in labels by hand. Short links and address-only
  `q=` give `NoCoordinates`. Lat in -90..=90, lon in -180..=180, `0,0` without `q` is refused.
- `places.rs`: `Place { id: u64, name: String, coord: Coord, rule: Option<AlarmRule>,
  gtfs_stop: Option<String> }`, `SavedPlaces { next_id, places }` with `add(name, coord) ->
  Result<u64, PlaceError>` (dedupe within 50 m -> `Duplicate(id)`, cap 200, name 1..=60 chars),
  `rename`, `remove`, `search(query) -> Vec<&Place>` (folded substring).
- `history.rs`: `Outcome { Arrived, Cancelled }`, `TripRecord { id, label, destination, rule,
  started_ms, ended_ms, outcome, travelled_m, snoozes, notice_sent }`, `History { records }` with
  `push` (newest first, cap 500), `from_session(&ActiveTrip, ended_ms, outcome) -> TripRecord`,
  `recent_destinations(limit) -> Vec<(String, Coord)>` (deduped within 100 m, newest first),
  `stats(now_ms) -> Stats { trips, arrived, cancelled, total_m, total_ms, avg_ms,
  last_30_days, top: Vec<(String, u32)> (3) }`.
- `prefs.rs`: `StopsPrefs { rule: RuleChoice, sound: bool, vibrate: bool, snooze_min: u8,
  notice: Option<NoticePrefs> }`, `RuleChoice { Radius300, Radius500, Radius1k, Eta2min }` with
  `to_rule()` (keeps today's four chips, default `Radius500`), `NoticePrefs { chat, chat_name,
  template, when: NoticeWhen { WithAlarm, MinutesBefore(u8) } }` with
  `to_notice_state() -> NoticeState`. `#[serde(default)]` everywhere so old files load.
- Tests: a table of real-world share strings for each provider (incl. a two-line Google share,
  a `geo:` with label and spaces, a short link refused, out-of-range refused); places dedupe,
  cap and fold search ("kadikoy" finds "Kadıköy"); history cap, recent dedupe, stats sums and
  the 30-day window; prefs default and an empty `{}` JSON load.

Acceptance: all parsers are pure and covered; `rovsti_geo` still has no I/O.

### Step 3. Pure GTFS feed and map projection (`rovsti_geo`), zip dependency

Files: root `Cargo.toml` `[workspace.dependencies]`: `zip = { version = "2.6", default-features =
false, features = ["deflate-flate2"] }`, `flate2 = "1.1"` (already in `Cargo.lock` 1.1.10; its
default `rust_backend` gives zip a pure-Rust inflater). `crates/rovsti_geo/Cargo.toml`: `zip`,
`flate2`. New `crates/rovsti_geo/src/{csv.rs, gtfs.rs, view.rs}`.

- `csv.rs`: minimal RFC 4180 reader over `impl BufRead`: BOM, quoted fields with `""`, CRLF,
  header map by column name; `CsvReader::next_record(&mut self) -> io::Result<Option<&[String]>>`
  reusing buffers.
- `gtfs.rs`: `Stop { id: String, name: String, coord: Coord, parent: Option<u32> }`,
  `Route { id, short_name, long_name, kind: u16 }`, `Pattern { route: u32, headsign: String,
  stops: Vec<u32> }`, `Feed { stops, routes, patterns, imported_ms }`.
  `FeedBuilder::{stops(r), routes(r), trips(r), stop_times(r), finish() -> Result<(Feed,
  FeedReport), GtfsError>}`. `stop_times` is streamed: rows grouped by `trip_id` (a trip is
  finalized when the id changes; a reappearing id is counted in `FeedReport::ungrouped_trips` and
  treated as its own partial trip), sorted by `stop_sequence`, then deduped into patterns by
  `(route, headsign, stops)` hash. Caps from AD-ST6 -> `GtfsError::TooLarge(what)`; missing
  required file or column -> `GtfsError::Missing(name)`. `pub fn read_zip(r: impl Read + Seek)
  -> Result<(Feed, FeedReport), GtfsError>` using `zip::ZipArchive::new` and `by_name`
  (verified in zip 2.6.1 `read.rs:753`, `read.rs:996`).
  Queries: `search(&self, query, near: Option<Coord>, limit) -> Vec<u32>` (folded name, then
  distance), `patterns_through(stop) -> Vec<u32>`, `stops_before(pattern, stop, n) -> Option<u32>`,
  `nearest(coord, max_m) -> Option<u32>`.
- `view.rs`: `Viewport { center: Coord, metres_per_px: f64 }` with `project(Coord) -> (f32, f32)`
  (local equirectangular, cos(lat) scaling), `unproject((f32, f32)) -> Coord`,
  `fit(points: &[Coord], width, height, padding) -> Viewport`, `zoom_about(factor, (x, y))`,
  `pan(dx, dy)`, `nearest_on_screen(points, (x, y), max_px) -> Option<usize>`.
  `metres_per_px` clamped to 0.5..=2000.
- Tests: CSV quoting/BOM/CRLF; a tiny in-memory feed (2 routes, 3 trips, one duplicate pattern)
  built through `zip::ZipWriter` in the test -> 2 patterns, `stops_before(…, 2)` correct,
  reappearing trip counted, missing `stops.txt` and caps refused; viewport round trip within 1 cm,
  fit contains all points, zoom keeps the focal point.

Acceptance: workspace builds for desktop and `aarch64-linux-android` with the new deps;
`cargo test -p rovsti_geo` green.

### Step 4. Android seam: trip service, alarm, location permission, GTFS pick

Rust: `crates/rovsti_android/src/trip.rs` (new, `pub mod trip` in `lib.rs`):
`pub fn start(spec_json: &str) -> Result<bool>` (`RovstiBridge.tripStart(json)`: `{dir, title,
text, next_fix_ms, high_accuracy}`), `pub fn stop() -> Result<bool>` (`tripStop`),
`pub fn is_running() -> Result<bool>` (`tripRunning`), `pub fn alarm(action: AlarmAction) ->
Result<bool>` (`tripAlarm(String)`: `"dismiss"|"snooze"|"test"`), `pub fn arm_snooze(at_ms: i64)`
(`tripSnooze(String)` with the ms as text, ints are 32-bit), `pub fn can_schedule_exact_alarms()`.
`events::kind::{TRIP_STATUS = "trip_status", TRIP_ALARM = "trip_alarm"}` plus
`parse_trip_alarm(json) -> Option<TripAlarmEvent { Ringing, Snoozed { at_ms }, Dismissed }>`.
`permissions::Group::Location` (key `"location"`, not in `Group::ALL`). `documents::import_gtfs(
max_bytes)` (`importGtfs`, op `import_gtfs`) and `rovsti_core::wire::DocumentOp::ImportGtfs`.
Desktop tests: every new function is `Err(Error::Unsupported)`; `parse_trip_alarm` table test;
`DocumentOp` serde for `"import_gtfs"`.

Kotlin, shared dir `crates/rovsti_android/kotlin/dev/rovsti/bridge/`:
- `TripService.kt`: `Service`; `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)`;
  `LocationManager.requestLocationUpdates(provider, LocationRequest.Builder(intervalMs)
  .setQuality(QUALITY_HIGH_ACCURACY | QUALITY_BALANCED_POWER_ACCURACY).setMinUpdateIntervalMillis(..)
  .build(), executor, listener)` (API 31), provider `GPS_PROVIDER` when `high_accuracy`, else
  `FUSED_PROVIDER` if `hasProvider`, else `NETWORK_PROVIDER`. Each fix -> background executor ->
  `Headless.call("trip_fix", FixIn json + dir)`; null answer keeps the last interval and notifies
  "Tracking (no decision)". Applies `next_fix_ms`, updates the ongoing notification
  (`CATEGORY_NAVIGATION`, Stop action), emits `trip_status` with the answer, and on `ring`
  calls `TripAlarm.ring`. Watchdog per AD-ST3 with `getCurrentLocation`. `state=="ended"` ->
  `stopSelf()`. Started with `startForegroundService` only from `tripStart` (UI in foreground).
- `TripAlarm.kt`: `ring(context, title, text)`, `stopRinging()`, full-screen notification to
  `AlarmActivity`, `MediaPlayer` + `RingtoneManager.getDefaultUri(TYPE_ALARM)` with
  `USAGE_ALARM`, `VibratorManager.defaultVibrator.vibrate(VibrationEffect.createWaveform(..., 0),
  VibrationAttributes.createForUsage(USAGE_ALARM))`, wake lock (10 min cap), snooze through
  `AlarmManager.setAlarmClock(AlarmClockInfo(at, showIntent), firePendingIntent)` or
  `setExactAndAllowWhileIdle`. `TripAlarmReceiver` (snooze fire, Dismiss/Snooze actions).
  Dismiss and Snooze call `Headless.call("trip_alarm", {dir, action})` and emit `trip_alarm`.
- `AlarmActivity.kt`: plain Views (no GPUI): title, distance text, big Dismiss and Snooze;
  `setShowWhenLocked(true)`, `setTurnScreenOn(true)`; Back = Snooze.
- `RovstiBridge.kt`: `"location"` permission group (`ACCESS_FINE_LOCATION`,
  `ACCESS_COARSE_LOCATION`), `tripStart/tripStop/tripRunning/tripAlarm/tripSnooze/
  canScheduleExactAlarms/importGtfs`; `Documents.fileOp` maps a new request code to `import_gtfs`.
- Kotlin unit tests are not run here; keep pure helpers (`TripAlarm.snoozeAt`, interval and
  provider choice) as top-level functions so the Stops host can add JUnit tests later.

Acceptance: green checks incl. the Android `cargo check`; manifests of Phone, Messages and
Contacts are untouched (the new classes compile in every host but are declared only by Stops).

### Step 5. Stops Android host and the headless trip handler

- Eject by copying `apps/contacts/platforms/android` to `apps/stops/platforms/android`
  (no `gpuiforge` CLI here): rename namespace/applicationId to `dev.rovsti.stops`, library
  `rovsti_stops`, `stopsAppDir`, Kotlin `dev/rovsti/stops/{MainActivity.kt, RovstiStopsApp.kt}`
  (`RovstiBridge.install(this)`, `Headless.libName = "rovsti_stops"`). `gpuiforge.json`:
  `management: manual`, `project-dir`, `activity`, `min_sdk 34`, `target_sdk 36`, add
  `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` (no more
  `SCHEDULE_EXACT_ALARM`).
- `AndroidManifest.xml`: permissions as above; `<service android:name="dev.rovsti.bridge.TripService"
  android:foregroundServiceType="location" android:exported="false"/>`; `AlarmActivity`
  (`showWhenLocked`, `turnScreenOn`, `excludeFromRecents`, `launchMode=singleInstance`);
  `TripAlarmReceiver` (not exported); `MainActivity` intent filters: `VIEW` + `geo` scheme,
  `VIEW` for `https` hosts `maps.google.com`, `www.google.com` path `/maps`,
  `www.openstreetmap.org`, `maps.apple.com` (no `autoVerify`), `SEND` `text/plain`.
  A boot receiver ends a trip left running before a reboot (`trip_end` reason `cancelled`).
- Rust: `crates/rovsti_wa_ui/src/headless.rs`: make `gateway_in` `pub` and add
  `pub fn send_text_in(dir: &str, chat: &str, text: &str) -> Result<(), SendFailure>` with
  `SendFailure { NoLink, Retryable(String), Fatal(String) }` (`Http`/`Tls` retryable;
  `Unauthorized`/`Api`/`Url`/`Key`/`Decode` fatal); `wa_reply` uses it. Unit test the mapping.
- `apps/stops/src/store.rs`: file names `trip.json`, `history.json`, `saved.json`,
  `stops-prefs.json`, `gtfs.json`; `static TRIP_LOCK`; `load_trip/save_trip/end_trip(dir,
  outcome, now_ms)` (moves the session into history).
- `apps/stops/src/headless.rs`: `handle(kind, json)` via `rovsti_android::headless::dispatch` with
  `trip_fix` (FixIn + dir -> FixOut; sends the notice when `send_notice` is set and records
  `notice_result`), `trip_alarm` (`dismiss` -> end Arrived, `snooze` -> `{ring_at_ms}` or
  `{dismissed:true}` when snoozes ran out, using prefs `snooze_min`), `trip_end` (`{dir,
  reason}`). No trip -> `{"state":"ended"}`. `main.rs`: `rovsti_android::headless_entry!(headless::handle);`
  and `rovsti_android::bridge::keep_symbols()` at start.
- Tests (desktop, temp dir): a sequence of FixIn moves the trip to `ring` once and the file
  persists the state; snooze cap; dismiss writes one history record; a bad JSON answers `""`;
  notice with no link records `GiveUp` and never retries.

Acceptance: green checks; `apps/stops/platforms/android` mirrors the Contacts host layout and
the shared Kotlin dir is a Gradle source dir (not a copy).

### Step 6. Stops app: destination picking, trip start/stop, live status, alarm screen

`apps/stops/src/` split: `main.rs` (window, tabs, events), `trip_ui.rs`, `picker.rs`,
`saved_ui.rs`, `backend.rs`.
- `main.rs`: `app.on_open_urls` and `app.on_receive_share` feed an `mpsc` of raw strings (like
  Phone's `dial_tx`), drained into `picker::offer(text)` -> `dest::parse_location` -> a
  confirmation sheet ("Wake me near <label>", rule chips, Save toggle) or a notice pill with
  `DestError::message()`. Replace `forward_push`: own `events::install_sink_owned()`, route
  `wa_push` -> `whatsapp.wake`, `trip_status` -> live card, `trip_alarm` -> alarm screen,
  `permissions_changed`, `document_result` (`ImportGtfs`).
- `backend.rs`: `storage::data_dir("stops")`, loads prefs/saved/history/trip/gtfs on the
  background executor; saves through `storage::save_json`.
- Start: check `permissions::state(Group::Location)` (request, or open app details when
  permanently denied), `Group::Notifications`, `can_use_full_screen_intent` (row linking to
  `open_full_screen_intent_settings`), then write `trip.json` (`ActiveTrip` with the chosen rule
  and, when enabled for this trip, `NoticeState`) and `trip::start`. Stop: `trip::stop` then
  `end_trip(Cancelled)`. On launch with a running trip (`trip::is_running` or a saved trip),
  show the live card from `trip.json`.
- Trip tab: rule chips (existing `RULES` -> `RuleChoice`), destination row with Change, live
  card (distance, ETA, next fix, Stop), "Text <chat> when arriving" toggle when a notice is
  configured and WhatsApp is connected. Picker sheet: paste field, Saved, Recent
  (`history.recent_destinations(5)`), and (step 7) GTFS search.
- Alarm screen (GPUI, when attached): big distance, Dismiss / Snooze buttons calling
  `trip::alarm`. Saved tab: list, swipe-free row menu (Start, Rename, Delete), empty state kept.
- Desktop: `ROVSTI_STOPS_SIMULATE=1` feeds synthetic fixes straight to `headless::handle`
  ("trip_fix") every second so the UI can be exercised with `cargo run -p rovsti_stops`.
- Follow `.claude/skills/liquid-glass-gpui` (sheet, glass pills, `rovsti_ui::components`).

Acceptance: green checks; a pure `picker::offer` mapping (text -> sheet or error) is unit-tested.

### Step 7. Map view, GTFS import and line-aware alarm

- `apps/stops/src/map.rs`: `canvas` element over a `Viewport` (`rovsti_geo::view`). Paint:
  accuracy disc and position dot (`PathBuilder::fill` + `arc_to` circles), alarm ring
  (`PathBuilder::stroke(px(2.))`, dashed with `dash_array`), breadcrumb polyline (last 500 fixes
  kept in memory from `trip_status`), destination pin, GTFS stops in view (cap 300 per frame,
  culled by bounds) and the chosen pattern as a polyline. Pan with drag, zoom with pinch or
  double-tap; tap picks `Feed::nearest`/`nearest_on_screen` -> picker sheet. Colors from the
  theme tokens; no per-frame allocation beyond the paths.
- GTFS import (Settings > Transit data): `documents::import_gtfs(64 MiB)` -> `document_result`
  `ImportGtfs { path }` -> background `gtfs::read_zip(File::open(path))` -> `gtfs.json`, a report
  row ("1 234 stops, 87 lines, imported 5 Nov"), Remove. Errors shown verbatim from `GtfsError`.
- Picker: "Stops" section searching `Feed::search(query, last position, 20)`; choosing a stop
  offers its lines (`patterns_through`) and "Wake me 1 / 2 / 3 stops before"; that builds
  `AlarmRule::BeforeStop { trigger: stop coord N back, metres: 150.0 }` and labels the trip
  "Kadıköy (2 stops before, line 34)". Saved places may keep `gtfs_stop`.
- Tests: pure helpers in `map.rs` (which stops are in view, polyline thinning) and the rule
  builder from a pattern.

Acceptance: green checks; map draws only with verified GPUI APIs listed above.

### Step 8. History and stats, Settings, WhatsApp arriving-soon, docs

- History (a section on the Saved tab or a "History" row in Settings, keep three tabs): list of
  `TripRecord`s (label, date, duration, outcome, distance) with Clear, and a stats card from
  `History::stats` (trips, arrived %, total km, average duration, last 30 days, top 3 places).
- Settings tab becomes a list: Alarm (sound, vibration, snooze minutes, "Test alarm" ->
  `trip::alarm(Test)`), Battery (`xiaomi::request_ignore_battery_optimizations`,
  `xiaomi::open` autostart when `xiaomi::detect()`), Exact alarms status, Transit data (step 7),
  Arriving-soon message, WhatsApp (pushes the shared `WhatsAppSettings` page like Contacts'
  `whatsapp_page`, unchanged two options: link first time / connect with a key).
- Arriving-soon page: shown when `whatsapp.is_connected()`; chat picker from
  `whatsapp.gateway().chats()` on the background executor (name or number, groups marked),
  template field validated by `notice::validate` with a live preview from `notice::render`,
  When (with the alarm / 5 / 10 / 15 min before), Off. Saved in `stops-prefs.json`. Not
  connected -> a row "Link WhatsApp in Settings > WhatsApp to use this".
- Docs: `docs/roadmap.md` ticks the six Stops items "(done without a device)", map item as
  "decided: vector radar map, no tiles"; adds "Arriving-soon WhatsApp message" and "Check the
  trip, alarm and Doze on a phone" (open). `docs/architecture.md`: "Stops: decisions (AD-ST1 to
  AD-ST8)" and the Stops row of the Android table (service type, `AlarmActivity`,
  `USE_EXACT_ALARM`, `INTERNET` for the gateway). `docs/android.md`: "Stops host" section like
  Messages'. `README.md` if it lists app features.

Acceptance: green checks; roadmap and architecture match the code.

## Risks and notes

- Doze and HyperOS can still delay location for a foreground service; the watchdog alarm and the
  battery/autostart rows mitigate, the on-device check stays open in the roadmap.
- `USE_EXACT_ALARM` is a Play policy permission for alarm apps; Rovsti is sideloaded and Stops is
  an alarm app. If refused later, the fallback is `SCHEDULE_EXACT_ALARM` + `setAlarmClock`.
- Large GTFS feeds (country-wide) hit the caps by design; the message tells the user to pick a
  city or agency feed.
- The arriving-soon message is the only Stops data that leaves the phone, and only to the
  user's own gateway after an explicit opt-in.
