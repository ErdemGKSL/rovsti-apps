---
title: "Rovsti Phone brief: addendum"
created: 2026-10-27
updated: 2026-10-27
---

# Addendum: context for PRD and architecture

## Current state (as of commit 5008049)

- `apps/phone/src/main.rs` already has the glass tab bar (Favorites, Recents, Contacts, Keypad; Lucide icons), a spring-animated keypad with digit pop, a delete control, an invalid-number shake, and a call button that calls `telecom::place_call`. It also has a setup card that requests `Role::Dialer` and opens the three Xiaomi deep links. Favorites, Recents and Contacts are empty states.
- `crates/rovsti_android` exposes `roles::{is_held, request}`, `telecom::place_call`, `xiaomi::{detect, open, is_ignoring_battery_optimizations, request_ignore_battery_optimizations}`, and a string/bool-only JNI bridge to `RovstiBridge.kt`. Desktop builds return `Error::Unsupported`.
- `gpuiforge.json` declares `dev.rovsti.phone`, the `notifications` and `audio-routing` features, the `tel` scheme, and permissions including `CALL_PHONE`, `READ/WRITE_CALL_LOG`, `READ/WRITE_CONTACTS`, `READ_PHONE_STATE`, `USE_FULL_SCREEN_INTENT` and `POST_NOTIFICATIONS`.
- The Android host has not been ejected yet, and no APK has been built (see the roadmap Foundation items).

## Build environment snapshot (2026-10-27)

`~/Android/Sdk` exists with build-tools, platforms and platform-tools, but no `ndk/`. `gpuiforge` and `adb` are not on PATH. `scripts/setup-android.sh` installs the NDK and GPUiForge. The APK path after a build is `target/gpuiforge/android/app/build/outputs/apk/debug/app-debug.apk`.

## Android requirements for `ROLE_DIALER` (from docs/architecture.md)

The role is granted only when the app declares:
- an `InCallService` with `android.telecom.IN_CALL_SERVICE_UI = true` and the `BIND_INCALL_SERVICE` permission;
- an activity that handles `ACTION_DIAL` (with and without a `tel:` URI).

Without these, the app runs in read-only preview mode.

## Bridge shape implied by the MVP

The current bridge only goes Rust → Kotlin (static methods taking strings, ints and bools). The in-call screen also needs Kotlin → Rust events: call added or removed, state changes, `CallAudioState` changes, and the caller handle. The Recents and Contacts tabs also need list queries (call log, contacts) that return more than a bool. Options to decide in architecture: JSON strings over the existing string-only seam, or a native callback registered in `nativeInit`. Keep to the rule that Android logic stays in Kotlin and the Rust side stays thin.

## Platform-free logic to place in `rovsti_core`

- Call-log grouping: consecutive calls with the same normalized number and direction collapse into one row with a count.
- Contact search: diacritic- and case-insensitive name match plus a digits-only number match. T9 comes later but should reuse the same index.
- Call-state model: `Dialing`, `Ringing`, `Active`, `Holding`, `Disconnected` with mute/speaker flags. This is the view model the GPUI in-call screen renders.
- Number matching already exists (`PhoneNumber`).

## Deferred items and why

| Item | Reason deferred |
| --- | --- |
| Dual-SIM picker | Needs `PhoneAccountHandle` enumeration and per-SIM UI. The system default SIM works for a first test. |
| Blocklist | `BlockedNumberContract` writes are only allowed for the default dialer. Easy to add once the role works, but not needed to validate it. |
| Swap, merge, conference | Multi-call state is the hardest in-call logic. MVP handles one active call plus a waiting call (answer/decline). |
| T9, speed dial, voicemail | Polish on top of working contacts and keypad. |
| Bluetooth route picker | Android routes to a connected headset automatically. A manual picker comes later. |
| Contact editing | Hand off to the system contacts app with `ACTION_EDIT` / `ACTION_INSERT`. |

