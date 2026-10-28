---
title: "Product Brief: Rovsti Phone"
status: draft
created: 2026-10-27
updated: 2026-10-27
---

# Product Brief: Rovsti Phone

## Executive Summary

Rovsti Phone is a default dialer for Android with an iOS-style liquid-glass interface. It is built in Rust on GPUI (the gpui-fusion fork) and tuned for Xiaomi phones running MIUI and HyperOS. It replaces the phone app completely: it places and answers calls, shows the in-call screen, and holds the keypad, call log, contacts and favorites. Everything runs on the phone. There are no Rovsti servers, no accounts, no analytics and no cloud sync.

It is the first of three sibling apps in the Rovsti monorepo, alongside Rovsti Messages (SMS plus WhatsApp) and Rovsti Stops (offline arrival alarm). All three share the glass design kit (`liquid_glass_kit`, `rovsti_ui`), the domain types (`rovsti_core`) and the Android bridge (`rovsti_android`). Phone goes first because it is the smallest of the three that still exercises the whole stack: an Android role, a Kotlin system service, the JNI bridge, content providers, and a GPUI screen that has to appear on top of the lock screen.

The goal of this first release is narrow: a debug APK that the owner sideloads on their own Xiaomi phone, sets as the default phone app, and uses for real calls.

## The Problem

On Xiaomi phones the stock dialer is functional but visually inconsistent, and it is tied to MIUI/HyperOS conventions the owner does not want. Third-party dialers on Play are usually ad-supported, ask for network access, or sync contacts to their own cloud (caller-ID services). There is no dialer that is fully local and open, and that uses the fluid, spring-driven glass design language of iOS 26.

Xiaomi also makes replacement dialers fragile. Aggressive power management, Autostart revocation and the hidden lock-screen pop-up permission can stop a third-party dialer from ringing for incoming calls. A dialer that misses calls is worse than no dialer. Any replacement therefore has to treat Xiaomi setup as a core feature.

## The Solution

A single GPUI app that holds Android's `ROLE_DIALER`, with a floating, icon-only Lucide tab bar (Favorites, Recents, Contacts, Keypad). It provides:

- **Keypad** with spring-animated keys, paste, a call button, and number validation through `rovsti_core::PhoneNumber` (most of this exists today).
- **In-call screen** driven by an `InCallService`: caller name and number, call timer, and mute, speaker, keypad (DTMF), hold and end. It works for outgoing and incoming calls.
- **Incoming call UI**: a full-screen intent that shows answer/decline over the lock screen, plus a heads-up notification when the phone is unlocked.
- **Recents** read from `CallLog.Calls`: grouped consecutive calls, missed calls in red, a missed-call count on the tab, tap to call back.
- **Contacts** read from `ContactsContract`: alphabetical list, name/number search, a detail sheet with numbers and a call action.
- **Favorites**: a grid of starred contacts (the Android `STARRED` flag, so stars stay consistent with other apps), one tap to call, star/unstar from the contact sheet.
- **Setup checklist**: becoming the default dialer, runtime permissions, and on Xiaomi the Autostart, Battery saver "No restrictions" and "Show on lock screen / pop-up while in background" deep links. It is re-checked on every launch because HyperOS can revoke Autostart after an update.

The design follows `.claude/skills/liquid-glass-gpui`: refractive glass surfaces, interruptible springs, and a theme that follows the system.

## What Makes This Different

- **Fully local, fully open.** There is no network code path. Contacts and the call log stay in Android's own providers.
- **Xiaomi-first reliability.** The OEM permission steps that make other third-party dialers miss calls are surfaced and re-verified inside the app.
- **Real liquid glass on Android.** gpui-fusion's backdrop WGSL shaders give true refraction and spring motion, not a blurred-rectangle approximation.
- **Shared foundation.** Work done for Phone (bridge, roles, Xiaomi setup, glass components) is reused directly by Messages and Stops.

The honest advantage is the owner's control and taste, not a moat. This is a personal, opinionated product.

## Who This Serves

**Primary:** the owner, a developer with a Xiaomi phone who wants their daily dialer to be private, good-looking and under their control. Success means making and receiving every call through Rovsti Phone for a week without falling back to the stock app. `[ASSUMPTION]` The device runs HyperOS 1/2 or MIUI 14 on Android 14+.

**Secondary (later):** privacy-minded Android users, especially Xiaomi owners, who sideload open-source apps.

## Success Criteria (MVP)

1. A debug APK builds with `gpuiforge build android` and installs by sideloading on the owner's Xiaomi phone.
2. The app can be set as the default phone app (directly, or through the documented Settings fallback when MIUI hides the role dialog).
3. An outgoing call placed from the keypad, recents, contacts or favorites connects and shows the Rovsti in-call screen. Mute, speaker, keypad, hold and end all work.
4. An incoming call rings and can be answered or declined from the lock screen and from an unlocked phone, after the Xiaomi checklist is completed.
5. Recents, contacts and favorites show the phone's real data within about one second of opening the tab. `[ASSUMPTION]` Typical sizes are a few hundred contacts and the default call-log window.
6. Workspace checks pass: `cargo fmt`, `clippy`, `cargo test`, and the Android `cargo check`.

## Scope

**In (MVP, one automated BMAD run):**
- Ejected Android host for `apps/phone` with `RovstiBridge`, an `Application` subclass, an `InCallService` and an `ACTION_DIAL`/`tel:` activity filter (the minimum Android needs to grant `ROLE_DIALER`), plus a full-screen incoming-call intent.
- In-call and incoming-call UI in GPUI, with call state pushed from Kotlin to Rust over the bridge.
- Keypad, Recents, Contacts (read-only plus star/unstar), Favorites, and the setup checklist.
- Basic audio route toggle (earpiece/speaker) through the in-call `CallAudioState`.
- Pure logic (call-log grouping, contact search, number matching, call-state model) in `rovsti_core`, with unit tests.

**Out (later releases):**
- Dual-SIM picker (`PhoneAccountHandle`) and per-SIM call-log badges. MVP uses the system default SIM. `[ASSUMPTION]` The owner has one active SIM or accepts the default.
- Blocklist (`BlockedNumberContract`).
- Call swap, merge and conference. Call waiting appears as a second incoming call with answer/decline only.
- T9 contact search on the keypad, speed dial, long-press `1` for voicemail.
- Bluetooth audio route picker beyond what Android does automatically.
- Contact creation and editing (hand off to the system contacts app), call notes, call recording.
- Play Store release, signing, localization.

## Risks and Open Questions

- **Android toolchain is not installed yet.** `ndk`, `gpuiforge` and `adb` are missing on the build machine (`~/Android/Sdk` exists). `scripts/setup-android.sh` must succeed before the first APK. This is the main delivery risk for the run.
- **GPUI as the in-call surface.** The in-call screen has to appear quickly when a call arrives, including from a cold start. Open question for architecture: does the single GPUI activity get re-used and brought to the front with `showWhenLocked`/`turnScreenOn`, or is there a separate activity? A native Kotlin fallback ringing screen may be needed if cold start is too slow.
- **Role grant on MIUI.** Some builds hide the role dialog. The fallback path to Settings must be clear in the checklist.
- **GPU path.** Older Mali GPUs may need the GLES fallback. Check the owner's device with `adb logcat -s GPUI`.
- **No automated device test.** Final acceptance is the owner's manual test on the phone.

## Vision

Rovsti Phone becomes a complete daily dialer: dual-SIM aware, with an on-device blocklist and spam rules, T9 search, conference calls, and a shared people model with Rovsti Messages, so one contact shows calls, SMS and WhatsApp threads together. All of it stays local, open and in liquid glass.
