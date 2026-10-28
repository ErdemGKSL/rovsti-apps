---
title: "Rovsti Phone PRD: addendum"
created: 2026-10-27
updated: 2026-10-27
---

# Addendum: technical context for architecture and stories

This file holds the "how" that the PRD deliberately leaves out. The brief's addendum (`../../briefs/brief-rovsti-phone-2026-10-27/addendum.md`) remains valid; this file adds what the PRD's FRs imply.

## Fixed technology

- Rust on gpui-fusion rev `d6c8412` (`gpui_android`, `gpui_icons` for Lucide). One GPUI source per build; never crates.io `gpui`.
- Packaging: GPUiForge (`apps/phone/gpuiforge.json`, app id `dev.rovsti.phone`, ABIs `arm64-v8a` and `x86_64`, features `notifications` and `audio-routing`, scheme `tel`, icon `assets/app.png`).
- Kotlin is used only for Android system components, in the ejected host (`apps/phone/platforms/android`) plus `crates/rovsti_android/kotlin/dev/rovsti/bridge/RovstiBridge.kt`.
- UI: `.claude/skills/liquid-glass-gpui`, `liquid_glass_kit`, `rovsti_ui` (`FOLLOW_SYSTEM_THEME = true`, `ANIMATED_RESIZE = false`).

## Android components implied by the FRs

| FR | Component (Kotlin, ejected host) |
| --- | --- |
| FR-1, FR-3 | Activity filters for `ACTION_DIAL` (with and without `tel:` data) and `ACTION_VIEW` `tel:` |
| FR-1, FR-13 to FR-20 | `InCallService` subclass with `android.telecom.IN_CALL_SERVICE_UI = true`, `BIND_INCALL_SERVICE` permission. `IN_CALL_SERVICE_RINGING` is **not** set, so Telecom keeps playing the ringtone (PRD product decision in §4.5) |
| FR-2 | `RoleManager.createRequestRoleIntent(ROLE_DIALER)`; fallback `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` |
| FR-5 | `NotificationManager.canUseFullScreenIntent()`; fix via `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` |
| FR-5, FR-6 | Runtime permissions: `READ_PHONE_STATE`, `CALL_PHONE`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `READ_CONTACTS`, `WRITE_CONTACTS`, `POST_NOTIFICATIONS` |
| FR-11 | `onCallAdded` with `STATE_SELECT_PHONE_ACCOUNT`: call `phoneAccountSelected(default outgoing or first suggested, false)` |
| §4.4 | `PROXIMITY_SCREEN_OFF_WAKE_LOCK` held while the earpiece route is active |
| FR-14, FR-15 | `InCallService.setMuted`, `setAudioRoute`, `onCallAudioStateChanged` (or `CallEndpoint` APIs on Android 14+) |
| FR-16, FR-17, FR-18, FR-20, FR-24 | `Call.playDtmfTone` / `stopDtmfTone`, `hold` / `unhold`, `disconnect`, `answer(VideoProfile.STATE_AUDIO_ONLY)`, `reject(false, null)` |
| FR-19 | Ongoing `CallStyle.forOngoingCall` notification with End |
| FR-21 to FR-24 | Notification channel with high importance; `setFullScreenIntent` to the incoming surface; `Notification.CallStyle.forIncomingCall` (Android 12+) for the heads-up with Answer/Decline; activity with `showWhenLocked` and `turnScreenOn` |
| FR-25 | `TelecomManager.cancelMissedCallsNotification()` when Recents is viewed |
| FR-26 to FR-29 | `CallLog.Calls` query (sorted `DATE DESC`, limit 500) and a `ContentObserver` |
| FR-30 to FR-35 | `ContactsContract` queries (`Contacts` with `HAS_PHONE_NUMBER`, `CommonDataKinds.Phone`); `STARRED` update on `Contacts` |

`gpuiforge.json` already declares the permissions. `MANAGE_OWN_CALLS`, `BLUETOOTH_SCAN` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are declared too; architecture may drop `MANAGE_OWN_CALLS` and `BLUETOOTH_SCAN` because the MVP is not a self-managed `ConnectionService` and has no BT picker.

Missing from `gpuiforge.json` and needed by the FRs: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_PHONE_CALL` (ongoing CallStyle notification from the `InCallService` on Android 14+), `WAKE_LOCK` (proximity wake lock), `VIBRATE` (only if the app ever vibrates itself; not needed while Telecom rings). The merged manifest of the built APK must contain the first three.

Outgoing dial strings: `PhoneNumber::parse` rejects `*`, `#`, `,` and `;`, so the call path must pass the raw dial string to `placeCall` and use `PhoneNumber` only for matching and display (PRD FR-10). Emergency numbers go through `TelecomManager.placeCall` like any other.

## Bridge: what exists and what the PRD needs

Existing (Rust → Kotlin, static methods, strings/ints/bools): `isRoleHeld`, `requestRole`, `placeCall`, `isIgnoringBatteryOptimizations`, `requestIgnoreBatteryOptimizations`, `openXiaomiSetting`. Rust wrappers: `roles::{is_held, request}`, `telecom::place_call`, `xiaomi::{detect, open, ...}`, all returning `Error::Unsupported` on desktop.

New calls the FRs need (names are illustrative):

- Rust → Kotlin: `callAnswer`, `callReject`, `callDisconnect`, `callHold(bool)`, `callMute(bool)`, `callRoute(str)`, `callDtmf(char)`, `queryCallLog(limit) -> String`, `queryContacts() -> String`, `queryFavorites() -> String`, `setStarred(id, bool)`, `hasPermission(group)`, `requestPermission(group)`, `openDefaultAppsSettings()`, `markMissedCallsRead()`.
- Kotlin → Rust events: call added, call state changed, call removed, audio state changed, call-log changed, contacts changed. Each event carries an id and a small payload (number, presentation, capabilities).

Transport options (open question Q2 in the PRD):

| Option | Pros | Cons |
| --- | --- | --- |
| JSON strings over the existing seam; Rust polls or Kotlin calls one `nativeOnEvent(String)` | One new JNI symbol; serde in Rust; easy to test | Serialization cost (negligible at these sizes) |
| Typed native callbacks registered in `nativeInit` | No serialization | Many JNI signatures to keep in sync with Kotlin |

Either way, events must be delivered onto the GPUI main thread (Android main Looper) and the Rust side must be able to resync full state on attach (Q3), because the `InCallService` can outlive or precede the GPUI view.

## In-call surface options (open question Q1)

| Option | Notes |
| --- | --- |
| A. Reuse the GPUiForge activity; bring to front with `setShowWhenLocked(true)` / `setTurnScreenOn(true)` while a Call exists | One GPUI window; simplest Rust side; cold start includes GPUI + wgpu init, which may exceed NFR-2 |
| B. Separate activity hosting a second GPUI view | Unclear whether gpui_android supports two hosts; likely not worth it |
| C. Native Kotlin ringing screen; GPUI In-Call Screen after answer | Meets NFR-2 reliably; ringing screen is not glass. Allowed by the PRD's product decision in §4.5 |

A pragmatic path is to build A, measure cold start on the device, and keep C behind a flag.

## Pure logic to add to `rovsti_core`

- `call_log::group(entries) -> Vec<RecentsRow>`: consecutive entries with the same normalized number and the same type collapse; count kept; tests for missed/incoming/outgoing mixes and unknown numbers.
- `search::ContactIndex`: diacritic- and case-insensitive name fold (Turkish `ı/İ`, `ş`, `ğ`, `ö`, `ü`, `ç` included) plus digits-only number match; designed to back T9 later.
- `call::CallState` view model: `Dialing | Ringing | Active { since } | Holding | Disconnected { cause }`, flags `muted`, `route`, capabilities `can_hold`; a reducer from bridge events; timer formatting `m:ss` / `h:mm:ss`.
- `PhoneNumber` already exists (`parse`, `matches`, `is_international`).

## Current code state (commit 5008049)

`apps/phone/src/main.rs` (455 lines): `PhoneApp` with tab selection, keypad (`key_down`, `backspace`, `call`), spring tick, number display, setup card (role + three Xiaomi links), placeholder pages for Favorites, Recents, Contacts. The host has not been ejected; no APK has been built.

## Toolchain snapshot (2026-10-27)

`~/Android/Sdk` has build-tools, platforms, platform-tools and emulator, but no `ndk/`. `adb` and `gpuiforge` are not on PATH. `scripts/setup-android.sh` installs the NDK (r29+) and GPUiForge. `platform-tools/adb` exists in the SDK, so adding it to PATH is enough for `adb`. APK path after build: `target/gpuiforge/android/app/build/outputs/apk/debug/app-debug.apk`.
