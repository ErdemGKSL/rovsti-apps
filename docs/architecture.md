# Architecture

## Why GPUI on Android works here

Upstream GPUI (Zed) supports only macOS, Windows, Linux and the web. Three community
efforts add Android:

| Option | Backend | Liquid glass shaders | Verdict |
| --- | --- | --- | --- |
| **gpui-fusion** `gpui_android` + GPUiForge | wgpu (Vulkan, then GLES fallback), hosted in an Android View | **Yes**: the same fork the `liquid-glass-gpui` skill targets | **Chosen** |
| longbridge/aidant `gpui-mobile` | vendored older wgpu renderer on a Zed rev | No (upstream GPUI has no backdrop shaders) | AGPL, and its example does not build on the git backend yet |
| Zdroid `gpui_android` (zed-android-port) | wgpu/Vulkan | No | Built for running the Zed editor itself |

Only one GPUI source can exist per build. Because the glass material needs
gpui-fusion, its own Android backend is the only option that gives both. It covers
touch, inertial scrolling, IME, clipboard, lifecycle, sharing, notifications,
foreground data sync, accessibility through AccessKit, and Vulkan with a GLES fallback.

The pinned rev is `d6c8412` (2026-10-25). It includes `gpui_android` and
`gpui_icons` (Lucide icons as typed GPUI assets). `liquid_glass_kit` was written
against `cc05989`, and its shader validation still passes on this rev.

## Layers

Text input: every text box in every app is `rosty_ui::field::TextField`, drawn on top of
`rosty_core::text_edit::EditBuffer`. The buffer owns text, selection and composing range
and has no GPUI types, so the logic is tested on the desktop. The old per-app copies
(`apps/contacts/src/field.rs`, `apps/phone/src/field.rs`) are gone; add new inputs with
`TextField::new`, `with_input` or `multiline`, never a second `EntityInputHandler`.

Selection chrome. `rosty_ui::field::selection_chrome()` is `Native` on Android and `Drawn`
everywhere else (`set_selection_chrome(Some(..))` overrides it for tests and the shots).
- Android (`Native`): the field hands the platform its own selection UI, as an HTML input
  does. A tap reaches the field as `TextInputFocusEvent`: it focuses, raises the keyboard
  and puts the caret at the touch point. The host then shows the one native insertion
  handle. A long press is not claimed, so the host runs its own word selection (locale
  aware), floating toolbar (Cut, Copy, Paste, Select all), draggable handles and the
  magnifier while a handle is dragged. Toolbar items arrive as ctrl-a/c/x/v key events,
  which `TextField::handle_key` already handles. The field draws no handles or menu and
  never calls `show_insertion`.
- Desktop and the shots (`Drawn`): the field draws its own teardrop handles and the
  capsule edit menu (kept inside the field's horizontal bounds).
- Finger pan: a drag in a field arrives as `ScrollWheelEvent`s. The pure `pan` function
  moves the horizontal scroll (single line) or vertical scroll (multiline) and reports
  whether it consumed the delta. At an edge it does not, so the page scrolls on, like a
  browser. A long URL in Stops search can be panned by finger.
- Undo and redo: `rosty_core::text_edit::History` (100 steps) records before each edit.
  Runs of typing and runs of deletes coalesce into one step; paste, cut, newline, a
  selection replace or a caret jump close the group, and IME composition is one step.
  Ctrl/Cmd+Z undoes, Ctrl/Cmd+Shift+Z and Ctrl+Y redo. `set` and `clear` from an app
  reset the history.

```
apps/{phone,messages,contacts,stops}  GPUI views, one window per app
        │
crates/rosty_ui                   glass components, tab bar, theme, data dirs, text field
                                   (`rosty_ui::field`, the only IME input handler)
crates/rosty_core::text_edit      pure edit model behind the field (selection, IME
                                   composing range, graphemes, UTF-16 offsets; unit tested)
crates/liquid_glass_kit            glass material (WGSL), springs, tokens
        │
crates/rosty_wa_ui                shared WhatsApp settings screen (two options)
crates/rosty_wa_client ═══ HTTP + WebSocket (TLS) ═══▶ services/wa_gateway
        │                                                  owns the whatsapp-rust sessions
crates/rosty_wa_proto             wire types, connect keys, events, call state (pure,
        │                          shared by the client and the gateway)
crates/rosty_tiles ═══ HTTPS (rustls) ═══▶ the user's tile server (default tiles.versatiles.org)
        │                          fetch + disk cache of map tiles, used by Stops
crates/rosty_android ──JNI──▶ kotlin/dev/rosty/bridge/RostyBridge.kt
        │                           (roles, telecom, Xiaomi settings, services,
        │                            WaPush, WaCallAudio)
crates/rosty_core, rosty_geo     pure Rust, no I/O, unit-tested
```

- **Pure-core rule.** Logic that can be tested without a phone goes in `rosty_core`
  or `rosty_geo`: phone number matching, the alarm state machine, and later thread
  merging and search. The dial keypad's layout budget is `rosty_core::keypad_layout`
  (rects and sizes from viewport, insets and text length); the view only places boxes.
- **JNI seam.** `gpui_android` keeps its `JavaVM` private, so the Kotlin host passes
  its own through `RostyBridge.install(app)` and, after the activity loaded the library, `RostyBridge.attachNative()` → `nativeInit`. Rust
  calls static Kotlin methods that take only strings, ints and bools. Android logic
  such as RoleManager, Telecom, providers and services lives in Kotlin. Desktop
  builds return `Error::Unsupported`.
- **Async.** GPUI runs the UI on Android's main Looper. WhatsApp runs on the
  gateway; `rosty_wa_client` keeps its event WebSocket on its own thread and
  `rosty_wa_ui` forwards `GatewayEvent`s to the app through a GPUI task.
- **Map tiles.** Stops draws Shortbread vector tiles under its radar map. The logic is pure
  and tested in `rosty_geo::tiles` (slippy math, MVT decoding, styles for colorful and
  eclipse, clipping, the LRU index and fetch queue); the I/O is in `rosty_tiles` (ureq over
  rustls, disk cache). It is the only network traffic besides the WhatsApp gateway.
  The server is a setting (`tile_url` in `stops-prefs.json`).
- **Storage.** Each app keeps its files in its no-backup directory
  (`rosty_ui::storage::data_dir`). Nothing leaves the device except WhatsApp traffic
  to the user's own gateway, map tile requests to the chosen tile server, and what the
  user chooses to share. Stops caches tiles in `tiles/<server key>/`, one directory per
  server, capped at 128 MiB with least-recently-used eviction. `rosty_tiles::disk_usage` and
  `clear_all` back the downloaded size and the Clear button in Settings > Map tiles. WhatsApp data is
  never on disk in plaintext: the link (gateway URL, instance id, connect key) is in each app's
  encrypted vault, messages live in memory only, and the identity map and profile pictures are in an
  encrypted cache (see [WhatsApp vault](#whatsapp-vault-ad-wa12)). An update from a build before the vault
  deletes the old plaintext files once (`vault.wipe_plaintext`).

- **QR scanning.** Contacts decodes a picked picture with `rqrr` (pure Rust, no
  native code; licence `(MIT OR Apache-2.0) AND ISC`) in `rosty_core::contact_scan`.
  The payload parser reads vCard, MECARD, `tel:` and `mailto:`.

- **Contacts trash file.** Deleted contacts are kept as drafts in `contacts-trash.json` in the
  app's data directory (`apps/contacts/src/trash_store.rs`, atomic temp file + rename, size
  guarded, a damaged file reads as an empty Trash). The model is `rosty_core::contact_trash`:
  30 days, at most 500 entries. Merge deletes are not trashed; select-mode and Clean up deletes are.

## What each app needs from Android

| App | Role / service | Kotlin components (in the ejected host) |
| --- | --- | --- |
| Phone | `ROLE_DIALER` | `InCallService` (in-call UI + ringing via full-screen intent), `ACTION_DIAL` activity filter, call log and contacts providers; `RECORD_AUDIO` for WhatsApp calls |
| Messages | `ROLE_SMS` | `SMS_DELIVER` receiver, `WAP_PUSH_DELIVER` (MMS) receiver, `RESPOND_VIA_MESSAGE` service, `SENDTO`/`SEND` activity filters, inline-reply, scheduled-SMS and boot receivers; one headless JNI call into Rust for cold decisions (AD-MSG4); WhatsApp wakes through optional FCM push and fetches its text through that same call (AD-MSG5); no foreground service |
| Contacts | none | contacts provider (`READ_CONTACTS`, `WRITE_CONTACTS`); `INTERNET` for the gateway (WhatsApp only); Photo Picker for contact photos (no permission); handoff to Phone and Messages (`dev.rosty.permission.HANDOFF`, signature); call, SMS, share and edit go through the same `RostyBridge` intents as Phone |
| Stops | none | foreground service of type `location` (`TripService`) feeding fixes to `rosty_geo::ActiveTrip` through the headless call, `AlarmActivity` (native Views over the lock screen, full-screen intent), `TripAlarm`/`AlarmSound` (alarm-usage audio focus, `STREAM_ALARM` volume raise and restore, `CATEGORY_ALARM` channel), `USE_EXACT_ALARM` for snooze and the watchdog (`setAlarmClock`, `setExactAndAllowWhileIdle` as fallback), boot receiver that ends a stale trip; `INTERNET` for the gateway (the arriving-soon message only); `geo:` and maps-link `VIEW` filters and `SEND text/plain` |

### Stops: decisions (AD-ST1 to AD-ST9, AD-SA1 to AD-SA10, AD-SX1 to AD-SX8)

- **AD-ST1 Pure first.** The trip session, destination parsing, saved stops, history and
  statistics, prefs, the arriving-soon template, GTFS parsing and the map projection are
  `rosty_geo` modules with unit tests. Kotlin and GPUI only map.
- **AD-ST2 The trip runs headless.** `TripService` owns the location updates and calls
  `Headless.call("trip_fix", json)` on every fix. Rust (`apps/stops/src/headless.rs`) loads
  `trip.json`, runs `ActiveTrip::on_fix`, saves, maybe sends the WhatsApp notice, and answers
  `FixOut`. The UI, when attached, gets the same answer as a `trip_status` event, so a trip survives
  the activity being killed. Every change of `trip.json` holds `store::lock()`.
- **AD-ST3 The alarm needs no GPUI.** `TripAlarm` posts a `CATEGORY_ALARM` full-screen-intent
  notification to the native `AlarmActivity` (over the lock screen), plays sound through
  `AlarmSound` and vibrates with alarm usage (each can be switched off in Settings, never both).
  Snooze re-rings through `AlarmManager.setAlarmClock`; a watchdog alarm asks for a fix when none
  arrived. GPUI draws its own alarm screen only while the app is open. AD-SA1 to AD-SA6 fix the
  details: polling curve, retry window, policy, fallback chain, timeout and routing.
- **AD-SA1 One explicit polling curve** (`rosty_geo::poll`). Interval =
  `slack / (2 * MAX_SPEED_MPS)` clamped to `[MIN_POLL = 3 s, MAX_POLL = 300 s]`, with
  `slack = line_m - acc - max(DEAD_BAND_M, acc)`. The closer to the A-B diameter, the shorter;
  never longer than 300 s. The `/2` keeps two fixes at worst-case speed from jumping the band
  unseen. Unknown distance (no usable fix yet) gives 300 s; unknown or unusable accuracy counts as
  `MAX_ACCURACY_M` (pessimistic); a garbage fix reuses the last usable distance; a trip that is not
  tracking polls at `IDLE_POLL` (60 s). Tests: monotonic, exact 300 s cap, 3 s floor, safety bound.
  Since E7 R4 this curve is the floor of the trip's cadence (AD-SA10), not the cadence itself.
- **AD-SA2 Retry window.** `retry_after(interval) = interval + clamp(interval / 4, 5 s, 30 s)`.
  Rust sends it as `FixOut.retry_ms`; `TripService` arms the watchdog with it (a mirrored formula
  covers the start spec), so a missing fix triggers a retry, never silence. On a `null` location
  the service tries the other provider once, then reports `searching`. Every changed interval
  re-registers the location request (both the `LocationRequest` and the legacy path).
- **AD-SA3 Alarm policy is Rust data.** `rosty_geo::alarm::AlarmPlan` (volume percent, ramp,
  ring timeout, sound order, vibration pattern) goes to Kotlin inside the start spec (`"alarm"`) and
  is saved with the trip prefs. Kotlin has identical defaults so the alarm rings even if Rust never
  answered; a cargo contract test (`apps/stops/src/kotlin_contract.rs`) keeps both in sync. The
  volume target (`volume_target`, at least 80 % of max, never lowering a louder level) and the ramp
  (`ramp_volume`, full loudness within 5 s) are pure functions mirrored in Kotlin.
- **AD-SA4 Never silent.** Sound sources in order: `user_alarm`, `default_alarm`,
  `default_ringtone`, `default_notification`, `tone` (a `ToneGenerator` on `STREAM_ALARM`; no
  bundled raw sound because `rosty_android` ships no resources). A null URI, an exception or a
  `MediaPlayer` `OnErrorListener` callback advances to the next source. Vibration with alarm usage
  runs independently of the sound.
- **AD-SA5 Ring timeout and guard.** After the ring timeout (10 min) the alarm stops and re-rings
  after the user's snooze length (`trip_alarm` action `timeout`, event `timed_out`). Timeouts do
  not consume `MAX_SNOOZES` and are capped at `MAX_TIMEOUT_RERINGS` (6); past the cap the
  notification stays and the sound stops. A guard alarm armed at ring start (`setAlarmClock` at
  now + timeout + default snooze, same PendingIntent) re-rings after process death. Stopping
  releases player, tone generator, audio focus, vibration, wake lock and restores the user's alarm
  volume, idempotently; `TripAlarm.recover` restores a stale saved volume after a crash.
- **AD-SA6 Routing like the stock clock.** Every audio, focus and vibration call uses one
  `ALARM_ATTRS` (`USAGE_ALARM`, `CONTENT_TYPE_SONIFICATION`) with
  `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, so music pauses and Android plays the alarm on the speaker
  as well as a wired or Bluetooth headset. No preferred device, no communication device, no
  `STREAM_MUSIC`. The channel `trip_alarm_v2` is `IMPORTANCE_HIGH` with alarm sound attributes, so
  the Do Not Disturb alarm exception applies.
- **AD-SA7 Background location is recommended, never blocking.** `ACCESS_BACKGROUND_LOCATION`
  ("Allow all the time") is asked from a Trip-tab row ("Keep tracking in the background") only after
  foreground location is granted, because Android 11+ refuses a location foreground service that is
  started from the background without it. `Preflight.background_location` carries the state; a trip
  starts without it.
- **AD-SA8 A refused background restart keeps the trip.** When Android refuses to start the location
  service from the background (a restart with `START_FLAG_REDELIVERY`, a retry, the watchdog),
  `TripService` keeps `TripPrefs` and `trip.json`, posts a "tap to resume" notification on the channel
  `trip_problem` and emits `{"state":"error","error":"restart"}` on `trip_status`. Stops resumes the
  service when its window is activated, through `backend::resume_trip`, which restarts it only if
  `trip.json` holds a trip and no service runs.
- **AD-SA9 The watchdog prefers a live service.** `TripService.onWatchdog` reaches a live instance
  directly (re-arm, then ask for a fix on the service's worker) and calls `startForegroundService`
  only when no instance exists or it is shutting down, so a watchdog tick never needs a foreground
  start from the background while the service is alive.
- **AD-SA10 The cadence follows the speed, never the line** (`rosty_geo::cadence`, E7 R4). The next fix is
  asked for as late as possible such that the user cannot reach the line before it: with
  `slack` as in AD-SA1 and `v` an upper bound of the speed now, `T = clamp(max(R0, min(R1, R2)), 3 s, 300 s)`
  rounded down to a ladder (3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 75, 90, 120, 150, 180, 240,
  300 s), where `R0` is the AD-SA1 curve, `R1 = slack / (2 v)` and `R2` the largest `T` with
  `reach(T + grace(T), v) <= slack` (`reach`: accelerating at `A_MAX = 2 m/s²` up to `V_MAX = 60 m/s`;
  `grace` the AD-SA2 window). `v = max(bound, 1.5 * 5-minute average, 2 m/s)`, the bound being
  `sqrt(mean_step^2 + 2 * A_MAX * step)` with `step = displacement + both accuracies` (tightened by a GPS
  speed `s` to `1.2 s + 1`), and `V_MAX` on a first fix. Safety: under top speed, top acceleration, fixes
  wrong by at most their accuracy and the next fix within `T + grace`, no interval longer than 3 s lets
  the user reach the line (sweep and simulation tests), and the cadence never asks more often than
  `R0` rounded down. A longer rung needs 15 % margin, a shorter one is taken at once; high accuracy
  is on at 15 s or less or under 75 s to the line at the measured speed, off at 25 s and 120 s. A
  pending arriving-soon message adds its own target. The history (48 fixes, 5 minutes, monotonic
  clock `FixIn.elapsed_ms`) lives in `trip.json`. When no provider finds a fix the service calls the
  headless `trip_no_fix`, which dead-reckons from the last usable fix (denser retries, GPS near the
  line) and never rings; the status stays `searching`. The request asks for exactly `T` (minimum
  interval `T`, no minimum distance, no batching). While a trip runs the map's blue dot follows the
  trip's fixes and asks for its own only while following.
- **AD-ST4 Map: vector tiles under a vector radar.** No region is bundled with the app (it bloats
  the APK and goes stale). Stops downloads Shortbread 1.0 vector tiles (MVT, zoom 0-14, overzoomed
  beyond 14) from a VersaTiles-compatible server that the user sets (default
  `https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}`). `rosty_geo::tiles` decodes and styles them
  (colorful in the light theme, eclipse in the dark one). `rosty_tiles` fetches them and keeps the
  disk cache. The app paints them with `canvas` and `PathBuilder` under the radar layers (saved stops,
  A and B, the circle with the diameter line and its halves, position, breadcrumb, GTFS stops and line) over
  `rosty_geo::view`. Where a tile is missing, the radar look shows. Tiles around the trip circle
  are prefetched when a trip starts. Text labels are not drawn. The map shows
  "(c) OpenStreetMap contributors".
- **AD-ST5 Points are local.** A and B each come from the same sources: are `geo:` URIs and maps links (shared, opened or
  pasted), saved stops (a stop is a pair, restoring both points), recent trips and GTFS stops. No geocoding: a link without coordinates is
  refused and nothing is fetched to resolve it. The picking state (A, B, pin menu, drag, the
  hint line, labels) is the pure `apps/stops/src/pick.rs`; a point without a name is shown as
  "Point A" / "Point B" and the pair as "A-B 850 m", never as coordinates.
- **AD-ST6 GTFS is optional and local.** A `.zip` picked with the file picker is parsed on the
  background executor into `gtfs.json` (caps on stops, patterns and rows). It names stops
  (a point source for A or B) and draws them and their lines on the map; it has no alarm rule.
- **AD-ST7 The arriving-soon message is opt-in and goes through the gateway.** Settings picks one
  chat (`Gateway::chats()`, groups allowed), a template (`{stop}` is B, `{min}`, at most 500 characters,
  validated by `notice::validate`, previewed with `notice::render`) and when: with the alarm, or
  5, 10 or 15 minutes before. The page shows only while WhatsApp is connected, otherwise a row points
  to Settings > WhatsApp. It is saved in `stops-prefs.json` and can be switched off per trip on the
  Trip tab. It is sent at most once per trip; a transport failure is retried on the next fixes (3
  attempts), an auth or API error is not. This is the only Stops data that leaves the phone.
- **AD-ST8 History is local.** `history.json` (cap 500, newest first) holds each ended trip. The
  Settings > History page lists the latest 100 and shows `History::stats` (trips, arrived share,
  total distance, average length, last 30 days, top three places). Clear takes two taps.
- **AD-ST9 The map is the picker.** A device fix recentres the map only after the my-location
  button; otherwise it only feeds the opening view. The idle Trip tab is a tall map with a pick bar; the first
  tap drops A, the second drops B and the circle shows at once, with no sheet and no
  coordinates. A tap within `PICK_RADIUS_PX` of a drawn GTFS stop snaps to that stop (its name,
  id and coordinate); a dragged pin follows the finger exactly and snaps on release only after a quick flick (see
  `docs/stops-saved-and-snapping.md`, which also defines the stop = gate AB model and saved.json v2); otherwise the point is unnamed and
  shown as "Point A" / "Point B" by its slot (Swap stays right), frozen to that text only when the
  trip starts. With A and B set a third tap on empty map places nothing and says to drag a pin or
  Clear; "Move" in the pin menu arms a pin so the next tap relocates it. Pins drag directly (no
  long-press, which would delay every pan): a touch within `PIN_GRAB_PX` of a pin grabs it, a
  second finger cancels the drag into a pinch. A map tap never moves the view. Sheet picks
  (saved, recent, stop search, stop sheet, shared links through the Confirm sheet, which
  emphasises the open end) refit the view. The idle view is the pair, else your position at
  street level, else the last trip or first saved stop, else the default city. Your position
  comes from the `stops_location` seam in `rosty_android::location` (last known location plus
  one fresh fix on request; no background tracking, nothing leaves the phone). All state and
  label text is the pure, tested `apps/stops/src/pick.rs`; coordinates survive only in the pin
  menu's and the Confirm sheet's details lines.

The trigger is a diameter crossing (`rosty_geo::crossing`); the earlier radius, ETA and
"N stops before" rules are gone.

- **AD-SX1 Geometry is local metres.** Equirectangular projection around the midpoint of AB
  (`EARTH_RADIUS_M`, longitude wrapped); fine for city-scale circles, so the diameter is capped.
- **AD-SX2 Side.** The signed offset from the line A to B decides: positive is left (facing B),
  negative right. Within max(10 m, fix accuracy) of the line the side is unknown and the last
  confident side is kept.
- **AD-SX3 Detection by segment test.** When a confident fix lands on the other side (either
  direction), the alarm fires only if the path from the last confident fix to this one intersects
  the segment AB. Sparse fixes still fire; going round the circle never does. Fixes worse than
  150 m are ignored. One fire per trip, then Arrived.
- **AD-SX4 Input limits.** A and B must be 20 m to 50 km apart and valid coordinates;
  `DiameterError::message` gives the user sentence, and a stored `Diameter` that breaks it is
  refused on load.
- **AD-SX5 Polling.** Slack is the distance to AB minus accuracy and dead band; next fix in
  `(slack / 60 m/s / 2)` clamped to 3..300 s, high accuracy at 15 s or less (as the old trip).
  Superseded for a running trip by the speed-aware cadence of AD-SA10, which keeps this as its floor.
- **AD-SX6 Notice.** "With the alarm" sends with the crossing; "N min before" sends when the
  time to the circle edge is within N minutes, or when the fix is already inside the circle.
- **AD-SX7 Superseded code is removed.** `AlarmRule`, `RuleChoice`, the "Wake me" chips,
  per-place and per-trip rules, the line-aware stop-wake helpers. GTFS import stays.
- **AD-SX8 Old state.** A `trip.json` of the old shape does not parse; `store::load_trip` renames
  it to `trip.legacy.json` and answers "ended". Old history records still load and appear as
  single recent points, never as pairs; unknown `rule` keys in places and prefs are ignored.

### Contacts: decisions (AD-CT1 to AD-CT6)

- **AD-CT1 Pure first.** Draft rules, accounts, the photo crop plan, the handoff payload,
  presence text, a contact's WhatsApp target and the lookup cache are pure (`rosty_core`,
  `rosty_wa_proto`, `apps/contacts/src/whatsapp.rs`) with unit tests. Kotlin and GPUI only map.
- **AD-CT2 Number-less contacts are Contacts-only.** Phone keeps `pullContacts`; Contacts uses
  `pullContactsAll`, and a draft needs a name or organisation.
- **AD-CT3 Accounts.** A writable account is `type|name` (empty = phone only), listed from
  sync adapters without `GET_ACCOUNTS`; new contacts use the "Save to" account, remembered in
  `contacts-prefs.json`.
- **AD-CT4 Photos.** Android Photo Picker (no permission); Rust plans a centred square crop
  (`contact_photo::plan`), Kotlin decodes, crops and writes a JPEG to the display photo.
- **AD-CT4a Camera write rule.** `RostyShareProvider` is read-only except for the camera
  capture: `openFile` accepts mode "w"/"wt" only for files named `camera-<digits>.jpg` in
  `cache/share/` (`MediaShare.writable`). The camera app gets a write grant on that one URI
  through `EXTRA_OUTPUT`; every other name stays "r". The app declares no `CAMERA` permission.
- **AD-CT5 WhatsApp goes through the apps that own it.** Chat and call are handed to Messages
  and Phone by an explicit intent (`dev.rosty.action.WA_CHAT` / `WA_CALL`, extras `jid`,
  `name`) guarded by the signature permission `dev.rosty.permission.HANDOFF`; Kotlin keeps only
  same-signed packages. A missing app is reported ("Install Rosty Phone to call on WhatsApp").
- **AD-CT6 Availability is lazy.** The list mark comes from the instance's existing chats
  (`GET /v1/chats`, read on connect and on `Resync`, computed per pull and per refresh, never
  per frame). Only an opened card sends that one contact's international numbers (at most 12)
  to `POST /v1/contacts/lookup` and subscribes presence once per JID per run. The gateway
  caches (24 h, 4096) and rate-limits lookups. A local number without a country code only
  matches an existing chat, otherwise the card asks for the country code. No push in Contacts.
- **AD-CT7 Contacts permissions.** Besides READ/WRITE_CONTACTS, INTERNET and HANDOFF, Contacts
  declares `READ_CALL_LOG` (recent calls on the card, read-only, requested only after the owner
  taps "Show recent calls") and `POST_NOTIFICATIONS` (birthday reminders, requested when the
  owner turns them on). Neither is requested at launch.
- **AD-CT8 `open_contact` one-shot.** A birthday notification starts Contacts with
  `EXTRA_CONTACT_ID`; Kotlin posts one `open_contact {id}` event through the mailbox
  (`deliverOpenContact`, called from `onCreate` and `onNewIntent`, like Messages' open thread).
  `rosty_android::events::parse_open_contact` reads it; the app opens the card, or keeps the id
  as `pending_open` until the book has loaded.
- **AD-CT9 Group tools.** Message group and Email group build one `smsto:` / `mailto:` intent
  (`telecom::compose_group_sms`, `email_group`) from `rosty_core::group_tools` (at most 30
  numbers / 50 addresses, one per distinct number). The cards are read off the UI thread.
- **AD-CT10 Round 4 intake and writes.** Contacts receives shared and opened vCards through
  `on_receive_share` (SEND) and `on_open_urls` (VIEW, `content://`, copied to `cacheDir/shared/`
  by `readSharedUri` and read off the UI thread); the manifest filters (`text/x-vcard`,
  `text/vcard`, `text/directory`) are mirrored in `gpuiforge.json`. `rosty_core::contact_intake`
  decides what a file is. Per-contact `CUSTOM_RINGTONE` and `SEND_TO_VOICEMAIL` are written
  through `ContactOptions.kt` (voicemail on asks first). Pinned Home screen shortcuts launch the
  app with `EXTRA_CONTACT_ID` and reuse `open_contact` (AD-CT8). The Favorites order is a list
  of lookup keys in `contacts-prefs.json` (`favorites_order`, up to 300, file cap 64 KiB).
- **AD-CT11 Round 5 data.** vCard `PHOTO` is written only for single cards (share, save, copy;
  bulk exports and the QR payload stay photo-free), built off the UI thread. CSV export goes
  through the `text/csv` save picker and import reads Google / Outlook headers
  (`rosty_core::contact_csv`). "My card" and the emergency contacts (up to 5) are lookup keys in
  `contacts-prefs.json` (`my_card`, `emergency`). Frequently contacted is computed from the call
  log, read-only, and never written back to the provider.
- **AD-CT12 Round 6 data.** `contacts-prefs.json` also holds the first-seen marks
  (`known_max_id`, `added`: at most 50 ids from the last 30 days, noted on each pull and on an
  in-app create) and the Recently viewed lookup keys (`viewed`, at most 8); the worst-case file
  stays far below the 64 KiB cap (unit-tested) and prefs are written on the background executor
  only when they changed. `Relation` rows and the phonetic name parts are written and replaced
  with the other edited mimes in `ContactsEdit.kt` (`EDITED_MIMES`). The Kotlin import answers
  `{added, ids}` (ids aligned with the sent cards, 0 for a failure) so photos of a multi-card
  import are set afterwards through the single-photo path. The calendar file
  (`rosty_core::contact_ics`) is written to the temp dir and handed to
  `documents::export_file`; its answer arrives as `DocumentOp::ExportMessages`.

### Messages: decisions (AD-MSG1 to AD-MSG9)

- **AD-MSG1 Pure first.** SMS wire types, the unified inbox merge, the SMS timeline, the MMS
  PDU codec, OTP, spam rules, the schedule queue, the notification digest and the backup
  archive are `rosty_core` modules with unit tests. GPUI and Kotlin only map.
- **AD-MSG2 SMS lives in the provider.** Messages keeps no SMS database. Rust pulls through
  `rosty_android::sms` (JSON envelopes, off the UI thread) and pulls again on `sms_changed`.
- **AD-MSG3 One person, one inbox row.** The key is the contact id when the number matches a
  contact, else the canonical number; a WhatsApp chat joins by its JID's number. Groups never
  merge.
- **AD-MSG4, AD-MSG5** Cold paths and WhatsApp notifications, below.
- **AD-MSG6 Scheduled send.** SMS: the local queue (`scheduled.json`) plus one Android alarm
  per item, sent by Kotlin when it fires; the app settles what fired with `sms::pull_fired`
  on start and on `scheduled_fired`. WhatsApp: the gateway owns the item (`POST /v1/scheduled`).
  The sheet is opened by a long press on Send (right click on desktop) and every conversation
  lists what still waits in a Scheduled strip, local and gateway items together.
- **AD-MSG7 Encrypted backup.** `RMB1`: PBKDF2-HMAC-SHA256 (600 000 rounds), AES-256-GCM,
  header as AAD. Salt and nonce come from `getrandom` in the app, so `msg_backup` stays
  pure. The archive is written under the data dir and handed to the system "save as" by path;
  import runs the other way, shows a restore preview, then `sms::insert_restored`. The
  WhatsApp link key is never in a backup.
- **AD-MSG8 Spam is on-device.** Block list (system blocklist), user rules, an allow list and
  a fixed scorer, all in `messages-prefs.json`; the same file is what the cold receivers read.
  Spam goes to a Spam folder and is never deleted or announced loudly.
- **AD-MSG9 WhatsApp media goes up through the gateway.** Messages picks a file (`DocumentOp::Attachment`),
  `rosty_wa_proto::media` sizes (16 MiB) and classifies it, and `Gateway::send_media` uploads it; the
  bubble is optimistic (`push_pending_media`) and a retry re-reads the cached file. Inline reply stays text.

While attached, the app tells Kotlin (`uiAttached(true)`) and posts notifications itself:
`notifications::should_notify` filters (not mine, not the open chat), a per-conversation digest
feeds `rosty_core::notify::build`, and `msg_reply` events go out through the channel the
notification came from.

### Messages: cold paths (AD-MSG4, AD-MSG5)

- **AD-MSG4 Cold paths stay in Kotlin, logic stays in Rust.** An SMS can arrive, an inline
  reply can be sent and a scheduled SMS can fire with no activity and no UI process. Those
  paths are Kotlin receivers (`SmsDeliverReceiver`, `MmsWapPushReceiver`, `ReplyReceiver`,
  `ScheduledSmsReceiver`). When one needs a decision it calls
  `RostyBridge.nativeHeadless(kind, json)`, which `apps/messages/src/main.rs` exports with
  `rosty_android::headless_entry!(headless::handle)`. `System.loadLibrary` from a receiver
  only runs `JNI_OnLoad`, which registers native methods and does not start GPUI. The handler
  is plain blocking Rust (`apps/messages/src/headless.rs`): `sms_classify` (spam rules from
  `messages-prefs.json` plus passcode detection), `mms_notification`, `mms_retrieved`,
  `mms_send_req`, `wa_digest`, `wa_reply`. The seam stays strings: JSON in, JSON out. **Fallback:**
  if the library cannot be loaded, the symbol is missing, Rust panics or the answer is empty,
  Kotlin gets no decision and posts a plain notification (no passcode chip, no spam filter,
  no MMS decode). The message itself is always stored first.
- **AD-MSG5 WhatsApp notifications.** The FCM payload carries no text (AD-WA6). Since the vault the
  push makes no gateway request: with the vault open in the process the event socket brings the
  message and the app posts the per-chat notification itself; otherwise (locked, or a cold process)
  one content-free notice counts the new messages ("WhatsApp", "N new messages", no action, public on
  the lock screen; the count lives in the `rosty_wa_locked` preferences until the unlock or a reboot).
  The old `nativeHeadless("wa_digest")` path, which read `whatsapp-link.json`, is gone.

### Phone: incoming calls

`RostyInCallService` -> `CallRegistry` posts a `CallStyle.forIncomingCall`
notification (Decline to `CallActionReceiver`, Answer an activity PendingIntent
carrying `answer_call_id`, full-screen intent when allowed) before anything else
happens, so a call is answerable without GPUI. `MainActivity` sets
`setShowWhenLocked`/`setTurnScreenOn` dynamically while a call is live. Rust draws
`apps/phone/src/incoming.rs` from the same snapshot and reports its first frame back
(`RostyPerf` log, `docs/spikes/s2-incoming.md`). The native fallback screen
(`NativeIncoming.kt`) is off unless `CURTAIN_ENABLED` is flipped; it is always the
error view if GPUI dies while ringing. Telecom rings (the app plays no sound);
`ProximityController` blanks the screen at the ear.

Android only grants the dialer and SMS roles to apps that declare all of these
components. Until they exist, the apps run in read-only preview mode.

## WhatsApp gateway

Everything in the apps is local except WhatsApp. WhatsApp runs on a gateway the user
hosts (`services/wa_gateway`, Rust, axum and tokio). It owns every whatsapp-rust
session and does all WhatsApp work server-side: linking (QR and pairing code),
history sync, text, media, reactions, groups, presence and voice calls. The apps are
thin clients of it through `rosty_wa_client`. Setup and operation:
[whatsapp-gateway.md](whatsapp-gateway.md). The decision record is
`_bmad-output/plans/wa-gateway.md`.

- **AD-WA1 Crates.** `rosty_wa_proto` is pure (wire types, connect keys, URL rules,
  the audio frame codec, the event log, the call state machine, `WaCallView`).
  `rosty_wa_client` is the HTTP and WebSocket client (rustls with a pure-Rust crypto
  provider, so it builds for Android without the NDK). `rosty_wa_ui` is the settings
  widget the four apps share. `services/wa_gateway` is the server. The "binary
  packages only" rule covers `apps/*`; the gateway also has a `lib` for its tests.
- **Instances and connect keys (AD-WA2).** A linked WhatsApp account is an
  *instance*. Each app's WhatsApp settings screen offers exactly two options: link
  WhatsApp for the first time (creates an instance, shows a QR or pairing code), or
  connect to an existing instance by pasting a connect key. A connect key is
  `rwk_` plus 32 random bytes in base64url. It is a bearer secret: the server stores
  only its SHA-256, compares in constant time, and can revoke it. "Show connect
  key" in any connected app mints a fresh key for that share, so each can be revoked
  alone. Each app keeps `{gateway_url, instance_id, key}` in its own encrypted vault
  (AD-WA12), never in a plaintext file.
- **Transport (AD-WA3).** The gateway speaks plain HTTP behind a TLS-terminating
  reverse proxy. `Authorization: Bearer <key>` goes on every request and on the
  WebSocket upgrade. An app accepts `https://` anywhere and `http://` only for
  loopback, `10.0.2.2`, private ranges and `.local`.
- **Events (AD-WA4).** `GET /v1/events?after=<seq>` is a WebSocket of JSON
  `EventEnvelope { seq, at_ms, event }`. The gateway keeps a 1024-event ring per
  instance so a client resumes after a reconnect; a gap becomes `Resync` and the
  client refetches `GET /v1/chats`.
- **Call audio (AD-WA5).** The call runs on the gateway through whatsapp-rust's voip
  module (`voip-mlow`, pure Rust). The phone supplies the microphone and speaker:
  `GET /v1/calls/{id}/audio` is a WebSocket of binary frames, each 960 mono `i16`
  samples at 16 kHz (1920 bytes, 60 ms), in both directions. On Android, Kotlin's
  `WaCallAudio` (`AudioRecord` and `AudioTrack` with the voice-communication
  attributes, `MODE_IN_COMMUNICATION`) hands frames to Rust as base64 strings across
  the strings-only JNI seam, about 2.6 KB seventeen times a second each way. Phone's
  `wa_call.rs` owns the gateway and audio work (one pump task between
  `rosty_android::wa` and the client's `CallAudio`) but draws nothing itself: the
  glass In-Call and Incoming screens show a WhatsApp call through `CallSource::WhatsApp`.
  Mute, Speaker (`call_audio_speaker`) and End work; Keypad, Hold, Add, Merge and Swap
  are dimmed with "Not available on WhatsApp calls".
  **Call snapshot.** `GET /v1/calls` returns every call the gateway knows for the
  instance, newest first, as `CallSummary` (a `CallUpdate` plus `started_ms` and, once
  over, `ended_ms`). The call table is in memory only (the gateway keeps no durable call
  log; on-device history stays the source for Recents). Ended calls stay for 24 hours
  and are then dropped; the table holds at most 64 calls, and when it is full the
  oldest ended call is evicted to make room. Live calls are never evicted, so a table
  full of live calls refuses a new one. A cold Phone has missed the live `CallUpdate`
  events (the event stream sends no replay for a fresh `after`), so it reads this
  snapshot instead: `rosty_wa_proto::plan_snapshot` picks the newest live call (a
  ringing incoming call first) when no call is tracked, and the ended calls whose id is
  not in `wa-calls.json` yet.
- **One call owns the screen (arbitration).** `rosty_wa_proto::call::arbitrate`
  decides from three inputs: a cellular call is live, a cellular call is ringing, and
  the WhatsApp call. A WhatsApp call already placed or active keeps the screen, and a
  cellular call that rings beside it gets its own banner. A ringing WhatsApp offer is
  shown when no cellular call exists, shown as a waiting call (Decline only) while a
  cellular call is live, and declined at once (`reject_call`, once per call id) while a
  cellular call rings. The cellular call always wins; the WhatsApp offer never
  interrupts it.
- **Push (AD-WA6).** FCM is on only when the gateway has `fcm_project_id` and
  `fcm_service_account`; otherwise push is a no-op. Phone and Messages register their
  FCM token per instance (`POST /v1/push`). The gateway sends data-only,
  high-priority messages that carry no message text, only
  `{"kind":"call","call_id","from","name"}` or `{"kind":"message","chat","count"}`.
  The app wakes, opens the event socket and fetches. Firebase is optional in the app;
  see [android.md](android.md#whatsapp-push-and-call-audio).
- **People sync (people sync v3).** The names and photos of the people the apps meet
  on WhatsApp live in `people.json` (`rosty_app_core::people`), filled by a resumable
  sync whose planner is pure Rust (`rosty_app_core::people_sync`, driven step by step
  through the `people.sync_*` ops); nothing is written to the device's contacts. Since
  v3 the **gateway prepares and the phone downloads once**: the planner sends the
  gateway its numbers, each with the hash of what it last got and the picture id whose
  file it holds (`job_start`, `POST /v1/people/sync`); the gateway's people job does the
  lookups, profiles and pictures at WhatsApp's pace while the phone long-polls it
  (`job_watch`); the result comes as one bundle of only the changed records and new
  pictures (`job_fetch`, 8 MiB parts), committed with one book write per part and none
  at all when nothing changed; the job is then closed (`job_done`). The hashes live in
  `people-sync.json`; a new number has none and is looked up at the next run. Against a
  gateway without the job routes the planner falls back to the v2 steps (15 JIDs per
  20 s, one picture per 3 s, backoff on a 429 without undercutting `retry_after_ms`)
  and asks again after a day. Kotlin's `PeopleSyncRunner` (`:people`) only performs
  the gateway call a step asks for (it downscales and stores each bundle picture) and
  opts in with `jobs = true`. Every run is a WorkManager foreground worker with the
  ongoing notification (the gateway's phase and count, then the download); the
  periodic work runs every 12 h (`ExistingPeriodicWorkPolicy.UPDATE`), the app coming
  to the foreground starts a run only when the last is 12 h old and the last attempt
  30 min (`PeopleSyncTriggers`), and there is no contacts-changed trigger. The user's
  "Stop" also cancels the job on the gateway; a stop by the system leaves it working
  and the continuation follows it by id. Contacts shows the job live in its settings
  row; Phone without Contacts runs the same engine on its own data directory under the
  same trigger rules. Writers of `people.json` are serialized in Rust. Decision records:
  `_bmad-output/plans/people-sync-v2.md`, `_bmad-output/kotlin-ui/plans/people-sync-v3.md`.
- **Logging and developer mode.** App code logs through `RostyLog`
  (`:core-bridge`, `dev.rosty.core.log`; a source scan forbids direct
  `android.util.Log`): every line is masked (`LogMask`, the same rules as
  `rosty_core::redact`, one shared fixture), kept in a memory ring, and written to
  `noBackupFilesDir/logs` only while developer mode is on. Rust logs go to a bounded
  ring in `rosty_ffi` that Kotlin drains (`core.logs_drain`) while developer mode is
  on. Holding the Version row of About for 10 s toggles developer mode; its Logs page
  filters, copies and shares (the only way a line leaves the phone). Names and message
  text are never logged.
  **`call_end`.** For an incoming call that rang and was pushed, the gateway sends
  `{"kind":"call_end","call_id","missed","from","name"}` when the ring ends without the
  call being answered here. `missed` is `"1"` when the caller hung up or the ring timed
  out, and `"0"` when this user rejected it or another device answered or ended it.
  Answered and outgoing calls send none, and a repeat for the same call sends nothing.
  `call_end` is high priority and never coalesced.
  **When Phone re-syncs.** Phone fetches `GET /v1/calls` (one fetch at a time, a request
  meanwhile runs one more afterwards) when the WhatsApp link becomes connected, on
  every `wa_push` event, before it runs a notification's Answer or Decline, and on a
  `Resync` event. A live call from the snapshot becomes the tracked call, so a queued
  Answer from a cold start runs once the call is on screen ringing (a call that was
  just declined is not shown again). Ended calls this device has not recorded are
  added to `wa-calls.json` (missed when incoming and never answered) and their
  notification is cancelled, so they appear in Recents.
- **Server storage (AD-WA7).** `<data>/gateway.db` (SQLite) holds instances, hashed
  keys, push tokens, chats, messages and media; `<data>/instances/<id>/wa.db` is
  whatsapp-rust's store, one per instance. The gateway keeps message bodies, because
  it is the user's own server and history sync needs them. Creating an instance needs
  no key, so the number of instances is capped (`max_instances`, default 4), and
  failed bearer authentication is rate limited per IP.
- **Key management and relink (AD-WA8).** The settings widget lists the instance's
  connect keys (`GET /v1/keys`) and revokes any of them, the current one marked.
  After a logout it offers "Link again" (QR or pairing code through
  `POST /v1/instance/link`, `Gateway::relink`) and "Delete instance".
- **Wake (AD-WA9).** When an FCM push arrives, the app calls the event stream's
  `reconnect_now`, which skips the reconnect backoff; the widget's `wake` falls back to
  a verify when no stream exists yet.
- **Outgoing calls (AD-WA10).** Phone places a WhatsApp call with the client's
  `place_call`; the gateway starts it through `voip-mlow` and the same audio WebSocket
  (AD-WA5) carries the media. The entry points are the Recents row menu, the Contact
  sheet (a WhatsApp circle per number) and a WhatsApp chip on the keypad; all shown
  only while WhatsApp is connected. Ended calls are kept in `wa-calls.json` and Recents
  merges them into the grouped list with a WhatsApp badge.
- **Conversation view (AD-WA11).** Messages opens a chat from `GET /v1/chats` and the
  stored history, sends text, media and reactions through the client, and applies live
  events from the resumable stream.

### WhatsApp vault (AD-WA12)

The Kotlin apps (Messages, Phone, Contacts, Stops) protect what they hold of WhatsApp with a vault per
app. Decision record and implementation notes: `_bmad-output/kotlin-ui/plans/wa-vault.md` (sections 13
to 15); device checks: `_bmad-output/kotlin-ui/plans/wa-vault-device-tests.md`.

- **Key hierarchy.** The passphrase is app-generated: 26 Crockford base32 data characters (130 bits)
  plus 2 Reed-Solomon check characters, shown once as `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`
  (`rosty_core::vault::passphrase`; a single wrong character or a swap of two is caught before any
  prompt). KEK_p = Argon2id(passphrase, 16-byte salt; 64 MiB, t = 3, p = 1, calibrated down to at
  most (46 MiB, 1) or (19 MiB, 2); `argon2 =0.5.3`; PBKDF2-HMAC-SHA256 600 000 only with the
  `vault-pbkdf2` feature). A random 256-bit data key DK is wrapped by KEK_p (AES-256-GCM) and that
  blob is sealed again by K_hw, an AES-256-GCM key in Android Keystore (`rosty.wa.k.<id>`,
  non-exportable, StrongBox when present, `setUnlockedDeviceRequired`, one user authentication per
  use, Class 3 biometric or screen lock). Both layers are needed: a disk image is useless, and each
  passphrase guess costs one hardware-authenticated Keystore operation. DK encrypts the vault body
  (`{gateway_url, instance_id, connect_key}`) and the cache. A passphrase change makes a new passphrase,
  salt, DK and K_hw alias and deletes the old key, so an older copy of the vault never opens.
- **Daily unlock.** After a passphrase unlock DK is also wrapped by a random daily key DDK and that
  blob sealed by K_daily (`rosty.wa.d.b.<id>`, Class 3 biometric only, invalidated by a new
  enrolment; `rosty.wa.d.c.<id>`, screen lock, only on a phone with no Class 3 biometric). The blob
  carries the boot count and the passphrase-unlock time as authenticated data: Rust refuses it and
  deletes it after a reboot (the boot receivers also drop it), 72 h after the last passphrase unlock,
  or with the clock set back more than 5 minutes; five failed biometric attempts, a lockout or an
  invalidated key drop it too. The passphrase is then asked once.
- **On disk** (data dir, owner-only, atomic temp + rename): `wa-vault.json` {version, `hw_alias`,
  `hw_sealed` = K_hw(KEK_p(DK)), body = DK(link)}; `wa-vault-daily.json` {alias, sealed =
  K_daily(DDK(DK)), `daily_key` = DDK, stamp}; `wa-vault-cache.bin` = DK(identity map with its cursor,
  Phone's LID table, profile pictures up to 4 MiB). Blobs are AES-256-GCM with the header as
  associated data and distinct magics (`RWVK`, `RWVB`, `RWVC`, `RWVD`). Not WhatsApp data and kept
  plaintext: Contacts' people cache (`people.json`, `people-sync.json`, the people pictures), which the
  people provider hands every app; `wa_calls_forgotten` (opaque gateway ids); reminders and exports the
  person makes (a WhatsApp reminder names the chat only).
- **In memory only.** DK, KEK_p and the connect key live in Rust (`Zeroizing`) inside one `vault`
  session handle per process; Kotlin holds opaque blobs and a handle, and the passphrase only while it
  is typed or shown (the JNI buffers of `vault.*` are zeroized). Messages' store is memory-only and
  lazy (`wa_sync.open {memory, lazy, vault_cache}`); media, thumbnails, Status pictures and files to
  send are `memfd_create` files read through `/proc/self/fd/<n>` (64 MiB LRU); drafts, live names and
  the rekey cursor are in memory. Phone's full-size pictures (`cacheDir/wa-full`, also Contacts') are
  emptied on lock.
- **Lock lifecycle** (`:shell` `VaultLockLifecycle`): screen off, the auto-lock delay in the background
  (default 60 s; at once, 30 s, 1 min, 5 min), "Lock WhatsApp now", and process death. Locking closes the
  request budget first, then the event socket, the store and the RAM media, the repository's gateway,
  every WhatsApp notification with text, and `vault.lock` zeroizes DK and the connect key. The process
  is not ended (Messages is also the SMS app). FLAG_SECURE covers every vault page, the WhatsApp pages
  while unlocked, and the whole Messages and Phone windows while their vault is open.
- **While locked.** No request reaches the gateway (`WaRequestGate` refuses at once, the repository and
  the store start locked). Messages posts the content-free count notice (AD-MSG5); Phone shows a
  ringing call as "WhatsApp call" with no name, no number, no full-screen intent, and Answer asks for
  the screen unlock and the vault first; Contacts' and Phone's people sync defers to the next unlock;
  a Stops trip started while unlocked keeps a RAM copy of the link for its arriving-soon message
  (`vault.trip_hold`) until the trip ends.
- **Hand-over between apps.** Each app has its own vault and Keystore keys. `WaLinkProvider`
  (`dev.rosty.permission.WA_LINK`, signature-level) answers `/link` only while the sender's process has
  its vault unlocked (`vault.export_link`); the receiver asks only on the person's tap and passes the
  link straight to `vault.create {link}`, so it is never written in plaintext. Stops takes no part.
- **Request budget** (`:wa` `WaRequestBudget`, `WaBackoff`, `WaRequestGate`): a cold start or unlock
  costs at most 4 requests (event socket live without replay, status, one identity page, chat list);
  an opened chat 1 page of 50, older history 1 page per scroll; media only on a tap; history and media
  one at a time; 6 requests in the first 10 s after an unlock, 30 per sliding minute, one cold sync per
  30 s; back-off 2 s to 5 min with jitter honouring `Retry-After`/`retry_after_ms`; nothing while locked.
- **Recovery and migration.** No key recovery: "Forgot passphrase" deletes the vault and its keys and the
  person links again (history comes back from the gateway). The first start after an update from a build
  before the vault sets the vault up from `whatsapp-link.json` (`vault.create` without a link) and, once
  the vault exists and the state allows it, deletes every plaintext leftover once
  (`WaPlaintextMigration`: `vault.wipe_plaintext` erases the old store in place with `secure_delete` and
  `VACUUM`, then deletes it, `whatsapp-link.json`, the names, rekeys and identity files, the `wa:`
  drafts, the media, Status and avatar folders; Kotlin drops the `wa:` mutes, the `declined` hand-over
  mark, the text of WhatsApp reminders and `cacheDir/wa-full`). Flash deletion is not provable erasure.

WhatsApp does not endorse third-party clients; see the warning in
[whatsapp-gateway.md](whatsapp-gateway.md#terms-of-service).

Layout and spacing for every app is specified in [design/layout.md](design/layout.md).
