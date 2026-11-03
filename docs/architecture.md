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
crates/rovsti_whatsapp             whatsapp-rust session (own Tokio thread)
crates/rovsti_android ──JNI──▶ kotlin/dev/rovsti/bridge/RovstiBridge.kt
        │                           (roles, telecom, Xiaomi settings, services)
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
- **Async.** GPUI runs the UI on Android's main Looper. whatsapp-rust runs on a
  dedicated Tokio runtime thread and sends `WaEvent`s through a futures channel to a
  GPUI task.
- **Storage.** Each app keeps SQLite files in its no-backup directory
  (`rovsti_ui::storage::data_dir`). Nothing leaves the device unless the user shares
  it.

## What each app needs from Android

| App | Role / service | Kotlin components (in the ejected host) |
| --- | --- | --- |
| Phone | `ROLE_DIALER` | `InCallService` (in-call UI + ringing via full-screen intent), `ACTION_DIAL` activity filter, call log and contacts providers |
| Messages | `ROLE_SMS` | `SMS_DELIVER` receiver, `WAP_PUSH_DELIVER` (MMS) receiver, `RESPOND_VIA_MESSAGE` service, `SENDTO` activity, foreground `dataSync` service keeping the WhatsApp socket alive |
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
