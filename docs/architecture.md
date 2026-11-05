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

```
apps/{phone,messages,contacts,stops}  GPUI views, one window per app
        │
crates/rovsti_ui                   glass components, tab bar, theme, data dirs
crates/liquid_glass_kit            glass material (WGSL), springs, tokens
        │
crates/rovsti_wa_ui                shared WhatsApp settings screen (two options)
crates/rovsti_wa_client ═══ HTTP + WebSocket (TLS) ═══▶ services/wa_gateway
        │                                                  owns the whatsapp-rust sessions
crates/rovsti_wa_proto             wire types, connect keys, events, call state (pure,
        │                          shared by the client and the gateway)
crates/rovsti_android ──JNI──▶ kotlin/dev/rovsti/bridge/RovstiBridge.kt
        │                           (roles, telecom, Xiaomi settings, services,
        │                            WaPush, WaCallAudio)
crates/rovsti_core, rovsti_geo     pure Rust, no I/O, unit-tested
```

- **Pure-core rule.** Logic that can be tested without a phone goes in `rovsti_core`
  or `rovsti_geo`: phone number matching, the alarm state machine, and later thread
  merging and search.
- **JNI seam.** `gpui_android` keeps its `JavaVM` private, so the Kotlin host passes
  its own through `RovstiBridge.install(app)` and, after the activity loaded the library, `RovstiBridge.attachNative()` → `nativeInit`. Rust
  calls static Kotlin methods that take only strings, ints and bools. Android logic
  such as RoleManager, Telecom, providers and services lives in Kotlin. Desktop
  builds return `Error::Unsupported`.
- **Async.** GPUI runs the UI on Android's main Looper. WhatsApp runs on the
  gateway; `rovsti_wa_client` keeps its event WebSocket on its own thread and
  `rovsti_wa_ui` forwards `GatewayEvent`s to the app through a GPUI task.
- **Storage.** Each app keeps its files in its no-backup directory
  (`rovsti_ui::storage::data_dir`). Nothing leaves the device except WhatsApp traffic
  to the user's own gateway, and what the user chooses to share. An app keeps its
  WhatsApp link (`whatsapp-link.json`: gateway URL, instance id, connect key) there.

## What each app needs from Android

| App | Role / service | Kotlin components (in the ejected host) |
| --- | --- | --- |
| Phone | `ROLE_DIALER` | `InCallService` (in-call UI + ringing via full-screen intent), `ACTION_DIAL` activity filter, call log and contacts providers; `RECORD_AUDIO` for WhatsApp calls |
| Messages | `ROLE_SMS` | `SMS_DELIVER` receiver, `WAP_PUSH_DELIVER` (MMS) receiver, `RESPOND_VIA_MESSAGE` service, `SENDTO` activity; WhatsApp wakes through optional FCM push (see [WhatsApp gateway](#whatsapp-gateway)), no foreground service |
| Contacts | none | contacts provider (`READ_CONTACTS`, `WRITE_CONTACTS` for favorites); call, SMS, share and edit go through the same `RovstiBridge` intents as Phone |
| Stops | none | foreground `location` service feeding fixes to `rovsti_geo::Trip`, full-screen alarm activity, exact alarm as a fallback |

### Phone: incoming calls

`RovstiInCallService` -> `CallRegistry` posts a `CallStyle.forIncomingCall`
notification (Decline to `CallActionReceiver`, Answer an activity PendingIntent
carrying `answer_call_id`, full-screen intent when allowed) before anything else
happens, so a call is answerable without GPUI. `MainActivity` sets
`setShowWhenLocked`/`setTurnScreenOn` dynamically while a call is live. Rust draws
`apps/phone/src/incoming.rs` from the same snapshot and reports its first frame back
(`RovstiPerf` log, `docs/spikes/s2-incoming.md`). The native fallback screen
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
thin clients of it through `rovsti_wa_client`. Setup and operation:
[whatsapp-gateway.md](whatsapp-gateway.md). The decision record is
`_bmad-output/plans/wa-gateway.md`.

- **AD-WA1 Crates.** `rovsti_wa_proto` is pure (wire types, connect keys, URL rules,
  the audio frame codec, the event log, the call state machine, `WaCallView`).
  `rovsti_wa_client` is the HTTP and WebSocket client (rustls with a pure-Rust crypto
  provider, so it builds for Android without the NDK). `rovsti_wa_ui` is the settings
  widget the four apps share. `services/wa_gateway` is the server. The "binary
  packages only" rule covers `apps/*`; the gateway also has a `lib` for its tests.
- **Instances and connect keys (AD-WA2).** A linked WhatsApp account is an
  *instance*. Each app's WhatsApp settings screen offers exactly two options: link
  WhatsApp for the first time (creates an instance, shows a QR or pairing code), or
  connect to an existing instance by pasting a connect key. A connect key is
  `rwk_` plus 32 random bytes in base64url. It is a bearer secret: the server stores
  only its SHA-256, compares in constant time, and can revoke it. "Show connect
  key" in any connected app mints a fresh key for that share, so each can be revoked
  alone. Apps keep `{gateway_url, instance_id, key}` in their own data directory.
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
  `rovsti_android::wa` and the client's `CallAudio`) but draws nothing itself: the
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
  snapshot instead: `rovsti_wa_proto::plan_snapshot` picks the newest live call (a
  ringing incoming call first) when no call is tracked, and the ended calls whose id is
  not in `wa-calls.json` yet.
- **One call owns the screen (arbitration).** `rovsti_wa_proto::call::arbitrate`
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

WhatsApp does not endorse third-party clients; see the warning in
[whatsapp-gateway.md](whatsapp-gateway.md#terms-of-service).
