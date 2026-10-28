---
status: final
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/prd.md
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/addendum.md
  - apps/phone/_bmad-output/planning-artifacts/architecture/architecture-rovsti-phone-2026-10-27/ARCHITECTURE-SPINE.md
  - apps/phone/_bmad-output/planning-artifacts/architecture/architecture-rovsti-phone-2026-10-27/reviews/review-reality-check.md
  - apps/phone/_bmad-output/planning-artifacts/architecture/architecture-rovsti-phone-2026-10-27/reviews/review-adversary.md
  - apps/phone/_bmad-output/planning-artifacts/ux-designs/ux-rovsti-phone-2026-10-27/DESIGN.md
  - apps/phone/_bmad-output/planning-artifacts/ux-designs/ux-rovsti-phone-2026-10-27/EXPERIENCE.md
  - apps/phone/_bmad-output/planning-artifacts/briefs/brief-rovsti-phone-2026-10-27/brief.md
mode: headless (no interactive confirmations; owner decisions taken from the task brief)
---

# Rovsti Phone - Epic Breakdown

## Overview

This document decomposes the Rovsti Phone PRD (40 FRs, 13 NFRs), the UX contract (DESIGN.md and EXPERIENCE.md, 16 signature moments S1-S16, 9 screens) and the Architecture Spine (AD-1 to AD-22, spikes S1/S2) into implementable stories for single dev-agent runs.

**Goal:** a working, installable debug APK that the owner sideloads on a Xiaomi phone and uses as the default dialer, then a signed release. Epics are ordered by the architecture's de-risking order: spikes first (Epic 1), core logic with tests (2), Kotlin/Rust seam (3), P0 screens (4-6), P1 (7-9), P2 (10), signature moments (11), performance pass (12), signed release (13).

**Conventions used in every story**

- `Covers:` FR/NFR/AD/S/UX-DR ids. `Prio:` P0/P1/P2 from PRD section 6.1 (spikes and infrastructure carry the priority of what they unblock).
- `Verify:` one or more of `CARGO` (cargo fmt/clippy/test, pure Rust), `ANDROID-CHECK` (`cargo check -p rovsti_android --target aarch64-linux-android`), `BUILD` (`gpuiforge build android` and APK inspection), `DEVICE` (owner tests on the real Xiaomi phone; there may be no emulator), `EMU` (optional emulator with `adb emu gsm call`; DEVICE is the fallback). A story whose Verify contains DEVICE is complete in code when CARGO/BUILD pass, and is accepted after the owner's device check; the dev agent must leave a short device test script in the story's completion notes.
- Stories depend only on earlier stories. Domain logic goes to `rovsti_core` with table-driven tests (AD-14). Only `apps/phone/src/backend.rs` calls `rovsti_android` (AD-15). No `unwrap` (workspace lint). No deferred scope (dual-SIM, blocklist, conference/swap/merge, T9, speed dial, voicemail, Bluetooth picker, contact editing/creation, delete log).
- Always-on checks before finishing any story: `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace` and `cargo check -p rovsti_android --target aarch64-linux-android`.
- Owner decisions applied: teal accent via `rovsti_ui::theme::set_accent`; Xiaomi "Confirm" gated on opening the settings page first; unknown/private numbers in Recents are not callable; tab-bar puck drag, Recents "New call" pill and alphabet rail are out of MVP; performance is gained by code optimisation, never by cutting visual effects (AD-21); DESIGN.md "max 4 refractive surfaces" and "flat glass at rest" are advisory only.

## Requirements Inventory

### Functional Requirements

FR1: Role eligibility - the APK is eligible for ROLE_DIALER (InCallService with UI metadata and bind permission; activity handling ACTION_DIAL with and without tel:).
FR2: Request the role from the Setup Checklist; if the dialog cannot show, show the Settings path and a button to Default apps.
FR3: Handle dial intents - ACTION_DIAL with tel: opens Keypad pre-filled without calling; without data opens Keypad empty.
FR4: Preview mode without the role - visible banner; calls still go through the system path.
FR5: Checklist contents in order (Default Phone App; Phone and call log; Contacts; Notifications; Full-screen notifications; on Xiaomi only Autostart, Battery saver, Show on lock screen/pop-up, Floating notifications), each with state and reason.
FR6: Act on a Setup Item (runtime requests, Xiaomi deep links with fallback to app details, AOSP battery dialog).
FR7: Re-check readable items on launch and resume; collapse to "Setup complete" when all done.
FR8: Confirm Xiaomi Settings; persisted locally; cleared on app version change.
FR9: Enter a number (0-9 * #, long-press 0 for +, paste normalisation, delete, long-press delete clears).
FR10: Validate before calling - reject only strings without digit/*/#; service codes and emergency numbers pass through.
FR11: Place a call from Keypad, Recents row, Contact Sheet number, Favorites tile via one path; SIM-select auto-resolved; CALL_PHONE requested on demand.
FR12: Match the entered number to a Contact and show the name on Keypad.
FR13: Show the active Call - name/number/Unknown, Calling.../timer/On hold, timer survives background.
FR14: Mute reflecting the reported state.
FR15: Speaker/route toggle reflecting the reported route (headset shown).
FR16: In-Call Keypad sends exactly one DTMF tone per press and echoes digits.
FR17: Hold/Resume shown only when supported.
FR18: End the Call; "Call ended" about 1 s then return; remote hang-up same.
FR19: Return to the Call - ongoing-call notification with End; opening the app during a Call shows the In-Call Screen or Return-to-call pill.
FR20: Waiting Call - Answer (ends current call) or Decline.
FR21: Ring through the system ringer; silent mode still shows UI.
FR22: Incoming Call Screen over the lock screen, turns screen on, works on cold start.
FR23: Incoming Call Notification (Answer/Decline) when unlocked and in use.
FR24: Answer and decline from the Incoming Call Screen.
FR25: Missed calls recorded and visible; system missed-call notification cancelled when Recents is viewed.
FR26: Grouped Recents (consecutive same number+type, count, max 500, missed red, blocked hidden, permission empty state).
FR27: Missed Call Badge on the Recents tab icon.
FR28: Call back by tapping a Recents row; info control opens Contact Sheet or number sheet.
FR29: Recents live refresh within 2 s of a call ending.
FR30: List Contacts with numbers alphabetically with sections; permission empty state.
FR31: Search Contacts by name (case and diacritic insensitive) or number (formatting insensitive).
FR32: Contact Sheet with numbers, call action per number, star toggle, Edit in Contacts hand-off.
FR33: Star/unstar writes STARRED (the only provider write).
FR34: Favorites grid of starred contacts with a number; empty state.
FR35: Call a Favorite with one tap (default number, single number, or chooser); long-press opens Contact Sheet.
FR36: Tab navigation with interruptible puck spring; remembers last tab (first launch Keypad).
FR37: Theme follows the system without restart, including In-Call.
FR38: Desktop preview of every screen; Android-only actions say "not available"; sample data.
FR39: Debug APK built with gpuiforge, app id dev.rovsti.phone, arm64-v8a, icon from assets/app.png.
FR40: APK installs via adb and launches to Keypad with the Setup Checklist; logcat GPUI shows renderer init.

### NonFunctional Requirements

NFR1: Privacy/locality - no network code path, no INTERNET permission, no analytics/sync.
NFR2: Incoming latency - under 1.5 s warm, under 3 s cold; ringtone within 1 s.
NFR3: Call control latency - effect within 300 ms, state reflected within 500 ms.
NFR4: List load time - under 1 s for about 500 contacts/500 log entries; provider queries never block the UI thread.
NFR5: Smoothness - 60 fps scrolling and springs on the owner's phone.
NFR6: Reliability - no crash during a Call; Call survives GPUI failure and can end from the notification.
NFR7: Platform rules - Android logic in Kotlin behind rovsti_android; all functions compile on desktop returning Error::Unsupported; GPUI/JNI thin.
NFR8: Testability - grouping, search, number matching, Call State model in rovsti_core with unit tests; workspace checks pass.
NFR9: Device support - Android 14+ on Xiaomi (HyperOS/MIUI); Vulkan with GLES fallback.
NFR10: Accessibility - labels via AccessKit, 44x44 targets, font scale up to 1.3x without clipping in-call controls.
NFR11: Single-run buildability - scope sized and ordered by priority.
NFR12: Glass fidelity - kit backdrop refraction material; kit springs.
NFR13: Shared foundation - new Android plumbing in rovsti_android/rovsti_ui reusable by Messages and Stops.

### Additional Requirements

Architecture (all stories must honour; binding per story in `Covers`):

- AD-1 single GPUI activity is the call surface; `CallSurface.raise()` in `RovstiInCallService`; dynamic showWhenLocked/turnScreenOn only while a call exists; Back = `moveTaskToBack(true)`.
- AD-2 answerability never depends on GPUI (CallStyle notifications, registry-only actions, Kotlin-resolved display name).
- AD-3 lock-screen call-only mode with `keyguard_locked` in every snapshot (missing = locked); blank + moveTaskToBack when locked and no calls.
- AD-4 Kotlin `CallRegistry` owns call state; snapshot-on-attach; main-Looper mutation; `answerReplacing` pending op (2 s timeout).
- AD-5 one native method `nativeOnEvent(kind, json)`; `RovstiBridge.emit` no-op until attached; attach order: install sink, push snapshot, re-pull providers.
- AD-6 idempotent id-addressed commands returning Boolean.
- AD-7 elapsed time from `connect_time_ms` and wall clock.
- AD-8 native library loaded by the activity, never by `Application`.
- AD-9 provider data pulled as JSON off the UI thread; debounced ContentObservers; Arc-swapped indexes.
- AD-10 wire DTOs in `rovsti_core::wire` with golden fixtures shared with Kotlin JVM tests.
- AD-11 dial strings stay raw; `PhoneNumber` only for matching/display.
- AD-12 `ACTION_DIAL` normalised to `ACTION_VIEW` in `MainActivity`.
- AD-13 per-app accent through `rovsti_ui::theme`.
- AD-14 pure logic placement and test floor.
- AD-15 only `backend.rs` calls `rovsti_android`.
- AD-16 threading rules; no Tokio.
- AD-17 failure containment (`onGpuiError`).
- AD-18 manifest and permission contract; `INTERNET` absent; min_sdk 34, target 36.
- AD-19 eject once into `apps/phone/platforms/android`; shared Kotlin by symlink; `scripts/fix-eject-paths.sh`.
- AD-20 build and signing pipeline (debug APK path after eject: `apps/phone/platforms/android/app/build/outputs/apk/debug/app-debug.apk`).
- AD-21 performance engineered in code, never by visual cuts; kit `shader_validates` and `glass_snapshot` stay green.
- AD-22 profiling hooks (`rovsti_ui::perf`, `FrameStats`, `RovstiPerf` logcat tag, scenarios S-tab, S-scroll-recents, S-keypad, S-incall, S-incoming).
- Reviews: CallStyle argument order is `(person, declineIntent, answerIntent)`; Decline never starts an activity; Answer is an activity PendingIntent (no broadcast trampoline); `GpuiActivity` must be edited post-eject (Back, `onGpuiError`); `nm -D` must show both `Java_*` symbols; the InCallService needs the `android.telecom.InCallService` intent filter and no `IN_CALL_SERVICE_CAR_MODE_UI`; `canUseFullScreenIntent()` false falls back to heads-up.
- Toolchain: `scripts/setup-android.sh` must succeed (NDK 29, GPUiForge, adb on PATH) before any BUILD verification.
- Logging: `log` crate, Kotlin tag `Rovsti`, never log numbers/names above debug.

### UX Design Requirements

UX-DR1: Per-app teal accent (light `#08806E`, dark `#2DD4B4`, glow `#14C8A8`) applied to selected tab, on-states, links, progress.
UX-DR2: Tab Bar behaviour - interruptible puck, tap selected tab scrolls to top, hidden on In-Call/Incoming and while the keyboard is up in Contacts search; icon labels and Missed Call Badge ("99+" cap, hidden at 0).
UX-DR3: Key component - fires on finger-down, light haptic, 78 px slot stable, long-press 0 = + after 400 ms, 2-3 px stable layout; Call button debounce 800 ms.
UX-DR4: Number field - never wraps, font steps 36/32/28/24 at 11/14/18 chars, max 32 chars then shake once, status line (matched name / "Not a valid number" / "Calling...").
UX-DR5: Large title that collapses to a 44 px bar past 24 px scroll (opacity/translate only).
UX-DR6: Recents row (avatar 40, name/number, count, direction subtitle, time format rules, red missed, info button 44 px as separate target; unknown number inert).
UX-DR7: Contact row, sticky section headers (28 px), "#" bucket, sticky glass search field (44 px), result rows show matched number, sections flatten while searching.
UX-DR8: Sheet component - medium/large detents, grabber, fling projection and rubber band, Back dismisses, never a sheet over a sheet.
UX-DR9: Contact Sheet, Number sheet and Number chooser layouts and copy.
UX-DR10: Favorites tile grid (2 columns, 3 at 440 dp+, height 132), monogram then photo fade-in.
UX-DR11: Setup Checklist card, items table (9 items), progress "{done} of {total} done", unverifiable "Needs your confirmation. Rovsti can't check this setting.", header "Something changed: {item}", Settings-path help for hidden role dialog, "Setup complete" collapsed row.
UX-DR12: Empty, permission-denied, permanently-denied, loading skeleton (shown only after 150 ms) states with exact copy.
UX-DR13: Preview banner with "Fix" scrolling to the checklist; precedence Setup Checklist > preview banner > Return-to-call pill.
UX-DR14: In-Call Screen layout (96 px avatar, name, status, 3x2 control grid with Hold centred row 2, End 220x64), reported-state toggles with 120 ms pending look and 1 s revert, disabled Hold dim, "Call ended" 1 s / "Call failed" 2 s with reason.
UX-DR15: In-Call Keypad layout (72 px keys, digit line last 24 chars, Hide, no Call/Delete, no + on long-press).
UX-DR16: Incoming Call Screen layout (120 px avatar, label with lock glyph, solid Decline/Answer 76 px, first frame never blank, Back does nothing, "Missed call from {name}" 1.2 s) and fallback skin contingency.
UX-DR17: Waiting Call banner (76 px, Decline/Answer 36 px circles, "Answer ends current call" footnote).
UX-DR18: Return-to-call pill ("Return to call - m:ss").
UX-DR19: Ongoing-call and Incoming notifications (CallStyle, chronometer, End).
UX-DR20: Accessibility floor - AccessKit labels/roles/live regions per EXPERIENCE.md, focus order, 44 px targets, font scale 1.3x, state never colour alone.
UX-DR21: Reduced motion and reduced transparency fallbacks for every moment; haptics map.
UX-DR22: Voice and tone - sentence case, exact microcopy strings from EXPERIENCE.md.
UX-DR23: Expressive avatars - six hue pairs from a stable hash, monogram gradient + rim, state ring (Dialing/Active/Holding/Muted/Failed).
UX-DR24: Signature moments S1-S16 (specs in DESIGN.md), max two concurrent per screen, S3/S8 always immediate.
UX-DR25: Performance budget P-1..P-10 (frame time, lists, idle zero frames, UI thread never blocks); P-2 (refractive surface count) and P-8 (auto flat-glass degrade) are advisory/overridden by AD-21.

### FR Coverage Map

FR1: Epic 1 (1.2, 1.4) - role-eligible manifest; Epic 7 (7.3) role flow
FR2: Epic 7 (7.3) - request role and Settings-path fallback
FR3: Epic 4 (4.4) - dial intents normalised to Keypad prefill
FR4: Epic 7 (7.6) - Preview mode banner and system hand-off
FR5: Epic 7 (7.1)
FR6: Epic 7 (7.2)
FR7: Epic 7 (7.4)
FR8: Epic 7 (7.5)
FR9: Epic 4 (4.2) logic in 2.7
FR10: Epic 4 (4.2, 4.3) logic in 2.7
FR11: Epic 4 (4.3) call path; reused by 6.3, 8.4, 8.7
FR12: Epic 10 (10.1) logic in 2.6/2.7
FR13: Epic 4 (4.5) logic in 2.2/2.3
FR14: Epic 4 (4.6)
FR15: Epic 4 (4.6)
FR16: Epic 4 (4.7)
FR17: Epic 9 (9.1)
FR18: Epic 4 (4.8)
FR19: Epic 4 (4.9) ongoing notification; Epic 9 (9.3) pill
FR20: Epic 9 (9.2)
FR21: Epic 5 (5.4)
FR22: Epic 5 (5.2)
FR23: Epic 5 (5.1)
FR24: Epic 5 (5.3)
FR25: Epic 5 (5.5), Epic 6 (6.4)
FR26: Epic 6 (6.1, 6.2) logic in 2.4
FR27: Epic 10 (10.2) logic in 2.5
FR28: Epic 6 (6.3); info to Contact Sheet in Epic 8 (8.8)
FR29: Epic 10 (10.3)
FR30: Epic 8 (8.1)
FR31: Epic 8 (8.2) logic in 2.6
FR32: Epic 8 (8.4)
FR33: Epic 8 (8.5)
FR34: Epic 8 (8.6)
FR35: Epic 8 (8.7)
FR36: Epic 9 (9.4); remembered last tab Epic 10 (10.6)
FR37: Epic 4 (4.1); runtime theme melt Epic 11 (11.9)
FR38: Epic 3 (3.5) Unsupported-safe backend and sample data scaffold; Epic 10 (10.4) full sample data
FR39: Epic 1 (1.2, 1.5); Epic 13 (13.2)
FR40: Epic 1 (1.4)

NFR coverage: NFR1 1.2/1.5; NFR2 1.7/5.6/12.7; NFR3 3.2/4.6/12.7; NFR4 3.4/6.1/12.5; NFR5 Epic 12; NFR6 4.10/1.6; NFR7 3.1-3.5; NFR8 Epic 2 + 13.3; NFR9 1.4/12.7; NFR10 UX-DR20 in every screen story + 13.4; NFR11 whole ordering; NFR12 Epic 11/12 (12.6); NFR13 3.1-3.4, 7.1.

Coverage check: all 40 FRs mapped; no uncovered FR. Flagged gaps are listed at the end of this document.

## Epic List

### Epic 1: Installable Hello APK and Android De-risking Spikes
The owner can build, sideload and launch a role-eligible Rovsti Phone APK on the Xiaomi phone, and the two architectural risks (JNI export/manifest, incoming-call surface over the lock screen) are proven or their contingency chosen.
**FRs covered:** FR1, FR39, FR40 (NFR1, NFR2, NFR9; AD-1, AD-8, AD-18, AD-19, AD-20; spikes S1, S2)

### Epic 2: Domain Core with Tests
The call model, call-log grouping, contact search, dial-string rules, setup derivation, wire DTOs and frame stats exist as platform-free, unit-tested Rust.
**FRs covered:** logic for FR9, FR10, FR12, FR13, FR26, FR27, FR31, FR5-FR8 (NFR8; AD-7, AD-10, AD-14)

### Epic 3: Kotlin/Rust Seam and Backend
Android state (calls, providers, permissions) reaches the Rust app as snapshots and commands flow back, with identical desktop behaviour via `Error::Unsupported` and sample data.
**FRs covered:** FR38 (base) (NFR3, NFR4, NFR7, NFR13; AD-4, AD-5, AD-6, AD-9, AD-15, AD-16)

### Epic 4: Dial and Talk (P0)
The owner can key in or paste a number, place a call and run the In-Call Screen (timer, mute, speaker, DTMF, End), with a safe ongoing-call notification.
**FRs covered:** FR3, FR9, FR10, FR11, FR13, FR14, FR15, FR16, FR18, FR19, FR37 (NFR3, NFR6; AD-11, AD-12, AD-13, AD-17)

### Epic 5: Never Miss a Call (P0)
Incoming calls ring and can be answered or declined over the lock screen or from a heads-up notification, missed calls are recorded, and ring latency is measured against NFR-2.
**FRs covered:** FR21, FR22, FR23, FR24, FR25 (NFR2, NFR6; AD-1, AD-2, AD-3)

### Epic 6: Recents (P0)
The owner sees grouped call history and calls back with one tap.
**FRs covered:** FR25, FR26, FR28 (NFR4)

### Epic 7: Setup Checklist and Default Phone App (P1)
The owner is led through role, permissions and Xiaomi settings, re-checked on every launch.
**FRs covered:** FR1, FR2, FR4, FR5, FR6, FR7, FR8

### Epic 8: Contacts and Favorites (P1)
The owner browses and searches contacts, opens a Contact Sheet, stars people and calls Favorites in one tap.
**FRs covered:** FR28 (info), FR30, FR31, FR32, FR33, FR34, FR35

### Epic 9: Hold, Waiting Call, Return-to-Call and Tab Navigation (P1)
In-call completeness and navigation polish for daily-driver use.
**FRs covered:** FR17, FR19, FR20, FR36

### Epic 10: P2 Polish
Keypad contact match, Missed Call Badge, live Recents refresh, desktop sample data, photos on Favorites, remembered last tab.
**FRs covered:** FR12, FR27, FR29, FR36 (last tab), FR38

### Epic 11: Signature Moments and Motion
All 16 signature moments (S1-S16) ship with reduced-motion fallbacks, built from transforms and opacity.
**FRs covered:** UX-DR21, UX-DR23, UX-DR24 (NFR5, NFR12; AD-21)

### Epic 12: Performance Pass
Frame, glass, list and idle costs are measured on the phone and fixed in code, with no visual cuts.
**FRs covered:** none new (NFR2, NFR4, NFR5, NFR12; AD-21, AD-22; UX-DR25)

### Epic 13: Signed Release and Acceptance
A signed release APK is produced without leaking secrets, all workspace checks pass, and the 7-day acceptance checklist exists.
**FRs covered:** FR39 (NFR8; AD-20; SM-1..SM-4)

---

## Epic 1: Installable Hello APK and Android De-risking Spikes

Prove the toolchain, the ejected host, the JNI seam and the lock-screen incoming path before building features. Kotlin written in spikes 1.6 to 1.8 is the starting code for `CallRegistry`/`RovstiInCallService`, not throwaway.

### Story 1.1: Android toolchain ready

As the owner,
I want `adb`, NDK 29, GPUiForge and JDK available in my shell,
So that every later build and install step can run.

Covers: FR39 (prereq); PRD section 9 toolchain risk; AD-20. Prio: P0. Verify: BUILD (shell checks), DEVICE (adb sees phone).

**Acceptance Criteria:**

**Given** a clean shell
**When** `scripts/setup-android.sh` has run and `~/Android/Sdk/platform-tools` is on PATH
**Then** `adb version`, `gpuiforge --version`, `echo $ANDROID_NDK_HOME` (ends with 29.x) and `java -version` all succeed
**And** `gpuiforge doctor` (or `gpuiforge info android`) reports no missing component.

**Given** the phone has USB debugging on
**When** `adb devices` runs
**Then** the phone is listed, and the dev agent records the serial and Android/HyperOS version in `docs/phone-device-notes.md`.

**Given** the script fails or a tool is missing
**When** the story ends
**Then** the failing step and the exact fix command are written in the completion notes (no silent skip).

### Story 1.2: Eject the Android host and build a hello debug APK

As the owner,
I want an ejected, owned Android host that builds to a debug APK,
So that Android components (InCallService, receivers) can be added later.

Covers: FR1, FR39, NFR1, AD-18, AD-19, AD-20, S1. Prio: P0. Verify: BUILD.

**Acceptance Criteria:**

**Given** `apps/phone/gpuiforge.json`
**When** `gpuiforge platform eject android` is run once and committed
**Then** `apps/phone/platforms/android` exists with the shared Kotlin symlink `app/src/main/kotlin/dev/rovsti/bridge` pointing at `crates/rovsti_android/kotlin/dev/rovsti/bridge`
**And** `scripts/fix-eject-paths.sh` re-writes the baked absolute paths in `app/build.gradle.kts`.

**Given** the permission list in `gpuiforge.json`
**When** it is updated per AD-18 (add FOREGROUND_SERVICE, FOREGROUND_SERVICE_PHONE_CALL, WAKE_LOCK; remove MANAGE_OWN_CALLS and BLUETOOTH_SCAN; `variables.min_sdk` 34) and mirrored in the ejected `build.gradle.kts`
**Then** `gpuiforge build android` succeeds and produces `apps/phone/platforms/android/app/build/outputs/apk/debug/app-debug.apk`
**And** the PRD path difference (`target/gpuiforge/...` vs post-eject path) is documented in `docs/phone-build.md`.

**Given** the built APK
**When** `aapt2 dump badging` and `aapt2 dump permissions` run
**Then** package is `dev.rovsti.phone`, `arm64-v8a` is present, and `INTERNET` is absent (if the generated host adds it, it is removed in the ejected manifest).

### Story 1.3: Manifest components for role eligibility

As the owner,
I want the manifest to declare the components Android needs for the default phone role,
So that the system picker lists Rovsti Phone.

Covers: FR1, AD-12 (filters), AD-18, review notes. Prio: P0. Verify: BUILD.

**Acceptance Criteria:**

**Given** the ejected `AndroidManifest.xml`
**When** edited
**Then** it holds `RovstiInCallService` (exported, `BIND_INCALL_SERVICE`, meta-data `android.telecom.IN_CALL_SERVICE_UI=true`, `foregroundServiceType="phoneCall"`, the `android.telecom.InCallService` intent filter, and no `IN_CALL_SERVICE_RINGING` or `IN_CALL_SERVICE_CAR_MODE_UI`)
**And** `MainActivity` (singleTop, MAIN/LAUNCHER, `ACTION_DIAL` with and without `tel:` data, `VIEW` `tel` BROWSABLE), a non-exported `CallActionReceiver`, and `android:name=".RovstiPhoneApp"`.

**Given** stub Kotlin classes `RovstiPhoneApp`, `MainActivity` (extends `GpuiActivity`), `RovstiInCallService` (empty callbacks), `CallActionReceiver`
**When** the APK is built
**Then** the merged manifest passes `aapt2 dump xmltree` inspection for each filter above and the build has no lint-blocking errors.

**Given** `allowBackup`
**When** the manifest is inspected
**Then** `android:allowBackup="false"` is set.

### Story 1.4: Install, launch and verify the role picker (spike S1 on device)

As the owner,
I want to install the hello APK and see Rovsti Phone in the default phone app picker,
So that I know the foundation works on my phone.

Covers: FR1, FR40, NFR9, S1. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** the debug APK from 1.3
**When** `adb install -r app-debug.apk` and launch run
**Then** the app opens a GPUI window on the Keypad tab without crashing
**And** `adb logcat -s GPUI` shows the renderer initialising (Vulkan or GLES fallback), recorded in the completion notes.

**Given** Settings, Default apps, Phone app
**When** the owner opens the picker
**Then** "Rovsti Phone" is listed and selectable, or the failure is recorded with the manifest dump for fixing in 1.3.

**Given** the app is not yet default
**When** it launches
**Then** the existing Keypad UI still works (preview behaviour) and no `UnsatisfiedLinkError` appears in `adb logcat`.

### Story 1.5: JNI symbol export check and `check-apk.sh`

As the owner,
I want a scripted check that the native library exports the bridge symbols and the manifest is clean,
So that silent link failures are caught at build time.

Covers: NFR1, AD-5, AD-8, AD-18, S1. Prio: P0. Verify: BUILD, ANDROID-CHECK.

**Acceptance Criteria:**

**Given** `RovstiBridge.kt`
**When** a `nativeOnEvent(kind: String, json: String)` stub (logs only) is added next to `nativeInit`, `RovstiBridge.install(app)` no longer calls `System.loadLibrary`, and `attachNative()` is called from `MainActivity.onCreate` after `super.onCreate`
**Then** `cargo check -p rovsti_android --target aarch64-linux-android` passes and the Rust `Java_dev_rovsti_bridge_RovstiBridge_nativeInit` and `..._nativeOnEvent` are `#[no_mangle] extern "system"`.

**Given** the built `librovsti_phone.so`
**When** `scripts/check-apk.sh` runs `nm -D` on it
**Then** both `Java_*` symbols are listed; if missing, the script fails with the S1 remedy (force a reference from `backend.rs` or `RegisterNatives`) and the dev applies it.

**Given** the merged manifest
**When** `check-apk.sh` runs `aapt2 dump permissions`
**Then** it fails if `INTERNET` is present or if any of the AD-18 permissions is missing, and it reads the APK path from `gpuiforge info android`.

### Story 1.6: Spike S2a - InCallService, minimal registry, foreground service

As the owner,
I want the InCallService to bind, track calls and start its foreground service without crashing,
So that Android 14 foreground-service rules are proven on my phone.

Covers: FR1, NFR6, AD-1, AD-4 (minimal), AD-18, S2. Prio: P0. Verify: DEVICE (EMU optional).

**Acceptance Criteria:**

**Given** Rovsti Phone is the default phone app (owner sets it via the picker proven in 1.4)
**When** a call arrives or is placed
**Then** `RovstiInCallService.onCallAdded` fires, a minimal `CallRegistry` (main-Looper only, stable `call_id`) logs add/state/remove with tag `Rovsti`, and `startForeground(ONGOING_ID, n, FOREGROUND_SERVICE_TYPE_PHONE_CALL)` succeeds with no `ForegroundServiceStartNotAllowedException`
**And** the foreground service stops when the last call is removed.

**Given** the app process is cold (not running)
**When** a call arrives
**Then** the service starts without loading the native library (AD-8) and `RovstiBridge.emit` is a no-op without throwing.

**Given** the spike results
**When** done
**Then** `docs/spikes/s2-incoming.md` records pass/fail for: phoneCall FGS start, `emit` safety, ringtone still played by Telecom with `IN_CALL_SERVICE_RINGING` unset on HyperOS.

### Story 1.7: Spike S2b - incoming notification, full-screen intent and `CallSurface.raise()`

As the owner,
I want an incoming call to turn the screen on and show the GPUI activity over the lock screen,
So that I know the single-activity design meets the ring-latency budget.

Covers: FR21, FR22, FR23, NFR2, AD-1, AD-2, AD-3 (derivation), S2. Prio: P0. Verify: DEVICE (EMU optional).

**Acceptance Criteria:**

**Given** an incoming call
**When** `CallNotifications` posts `Notification.CallStyle.forIncomingCall(person, declineIntent, answerIntent)` (channel `incoming`, importance high) with a full-screen intent to `MainActivity`
**Then** with the phone locked the screen turns on and `MainActivity` shows over the lock screen via dynamic `setShowWhenLocked(true)`/`setTurnScreenOn(true)` (never in the manifest), reset when the last call is removed
**And** Decline goes to `CallActionReceiver` (no activity start) and Answer is an activity PendingIntent with extra `answer_call_id`.

**Given** the five raise paths (incoming locked, incoming unlocked heads-up Answer, outgoing from another app, outgoing from Bluetooth/Assistant, after Answer)
**When** each is tried
**Then** `CallSurface.raise(reason)` brings `MainActivity` forward in each case or the failure is recorded, and Back calls `moveTaskToBack(true)` (edit in the owned `GpuiActivity.onBackPressed`), never `finish()`.

**Given** timestamps logged at `onCallAdded` and at first GPUI frame
**When** run warm and cold on the phone
**Then** both latencies are recorded; the decision "curtain needed: yes/no" is written into `docs/spikes/s2-incoming.md` against NFR-2 (1.5 s warm, 3 s cold)
**And** `canUseFullScreenIntent()` false is tested and the heads-up with Answer/Decline still shows.

### Story 1.8: Spike S2c - HyperOS lock-screen behaviour and proximity lock

As the owner,
I want HyperOS-specific behaviours verified,
So that later stories rely on facts, not assumptions.

Covers: FR22, AD-3, NFR2, review items 6(d)(e), 9. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** the Xiaomi settings (Autostart, lock-screen pop-up, Floating notifications) enabled
**When** an incoming call arrives locked and unlocked
**Then** the result with and without each Xiaomi setting is tabulated in `docs/spikes/s2-incoming.md`, identifying which setting breaks which path.

**Given** `KeyguardManager.isKeyguardLocked` and `addKeyguardLockedStateListener`
**When** logged through lock, unlock, secure-lock-with-showing-over, and screen-off
**Then** the notes state whether the value is trustworthy on this device and the AD-3 "missing counts as locked" rule is confirmed or amended.

**Given** a `PROXIMITY_SCREEN_OFF_WAKE_LOCK` prototype
**When** an earpiece call is made
**Then** the screen blanks at the ear and the lock is released with the correct release flag when the call ends, recorded as pass/fail.

---

## Epic 2: Domain Core with Tests

All logic below lives in `crates/rovsti_core` (depends on serde and thiserror only), is table-driven, and runs with `cargo test -p rovsti_core`. Verify for every story in this epic: CARGO.

### Story 2.1: Wire DTOs with golden fixtures

As a developer,
I want versioned JSON shapes shared by Kotlin and Rust,
So that the seam cannot drift.

Covers: AD-10, AD-4 (snapshot fields), NFR8. Prio: P0. Verify: CARGO.

**Acceptance Criteria:**

**Given** `rovsti_core::wire`
**When** `CallSnapshot { v, seq, now_ms, keyguard_locked, audio{muted,route,routes[]}, foreground_call_id, waiting_call_id, calls[]{id,state,number_raw,normalized,display_name?,contact_id?,connect_time_ms,can_hold,is_incoming,disconnect_cause?} }`, `CallLogPayload`, `ContactsPayload`, `FavoritesPayload` and the `{v, ok, error?, data?}` envelope are defined
**Then** each has snake_case serde, ignores unknown fields, and every enum has a `#[serde(other)] Unknown` variant that still renders.

**Given** golden fixtures in `crates/rovsti_core/tests/fixtures/*.json`
**When** deserialised and re-serialised in tests
**Then** they round-trip, an unknown state does not drop the snapshot, `connect_time_ms == 0` means not connected, and `ok:false,error:"permission"` maps to a permission error value.

**Given** the fixtures
**When** the story ends
**Then** the Kotlin JVM unit-test location that will read the same files is documented (consumed by 3.2).

### Story 2.2: Call model and reducer

As a developer,
I want a `CallModel` that stores the last snapshot,
So that the UI renders what Android reported, never inferred state.

Covers: FR13, FR14, FR15, FR17, AD-4, AD-14. Prio: P0. Verify: CARGO.

**Acceptance Criteria:**

**Given** `rovsti_core::call::{CallModel, CallState, AudioRoute}` (`Dialing | Ringing | Active | Holding | Disconnected{cause} | Unknown`)
**When** `apply(snapshot)` is called with a snapshot whose `seq` is lower than the held one
**Then** it is ignored; an equal or higher `seq` replaces the state.

**Given** a snapshot with `foreground_call_id` and `waiting_call_id`
**When** the model is queried
**Then** it exposes foreground and waiting calls exactly as given (validates presence, never re-selects) and a missing id yields `None` without panic.

**Given** `keyguard_locked` absent from a payload
**When** parsed
**Then** the model reports locked (AD-3 default).

### Story 2.3: Elapsed-time formatting and call display name

As a developer,
I want pure formatting helpers for the timer and caller label,
So that the In-Call Screen needs no logic.

Covers: FR13, AD-7. Prio: P0. Verify: CARGO.

**Acceptance Criteria:**

**Given** `format_elapsed(now_ms, connect_time_ms)`
**When** called with 0:00..0:59, 1:05, 59:59, 1:00:00, 12:03:07, a clock skew (`now < connect`) and `connect == 0`
**Then** output is `0:00`, `1:05`, `59:59`, `1:00:00`, `12:03:07`, `0:00`, and an empty/"not connected" result respectively.

**Given** `caller_label(display_name, number_raw, hidden)`
**When** name present, name absent with parsable number, unparsable number, hidden/empty number
**Then** results are the name, the formatted number, the number as entered, and "Unknown".

**Given** a status function `status_text(state, now_ms, connect_ms)`
**When** state is Dialing/Active/Holding/Disconnected
**Then** it returns "Calling...", the timer, "On hold", "Call ended" (or "Call failed" with reason line mapping "Number busy", "Out of service", "Number not reachable").

### Story 2.4: Call-log grouping

As a developer,
I want consecutive call-log entries grouped,
So that Recents shows "Mehmet (2)".

Covers: FR26, NFR8, AD-14. Prio: P0. Verify: CARGO.

**Acceptance Criteria:**

**Given** `call_log::group(entries) -> Vec<RecentsRow>`
**When** entries are (missed A, missed A, incoming B, missed A) newest first
**Then** rows are `A x2 missed`, `B incoming`, `A x1 missed` (only consecutive same normalized number and same type collapse).

**Given** numbers "+905551112233" and "05551112233"
**When** grouped
**Then** they collapse as the same normalized number (via `PhoneNumber::matches`), and unknown/private (empty) numbers group only with each other and are flagged `callable=false`.

**Given** blocked entries and more than 500 entries
**When** grouped
**Then** blocked are dropped and the input is capped at the 500 newest.

**And** rejected entries produce a `Declined` type, and each row carries first/last timestamp, type, count and cached name/label.

### Story 2.5: Missed badge and Recents time formatting

As a developer,
I want the badge count and row time labels as pure functions,
So that UI code has no branching.

Covers: FR26, FR27, UX-DR6, AD-14. Prio: P0 (time format) / P2 (badge use). Verify: CARGO.

**Acceptance Criteria:**

**Given** `missed_badge(entries, last_seen_ms)`
**When** entries include missed newer and older than `last_seen_ms`, non-missed newer, and 100+ missed
**Then** only newer missed count, and `badge_label(n)` returns hidden at 0, "n", and "99+" above 99.

**Given** `relative_time(now, then, locale_hour12)`
**When** same day, yesterday, 3 days ago, 7 days ago, a previous year
**Then** outputs are "3:14 pm", "Yesterday", the weekday, and a short date "12/10/26" (format injected so tests do not depend on host locale).

### Story 2.6: Contact search index

As a developer,
I want diacritic- and format-insensitive search,
So that "ays" finds "Ayşe".

Covers: FR31, FR12 (match core), NFR8, AD-14. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `search::ContactIndex` built once per contact payload
**When** querying "ays", "AYS", "ozgur", "ığ" against "Ayşe", "Özgür", "Işık", "İlker", "Çağla", "Ünal"
**Then** Turkish folding (ı/İ, ş, ğ, ö, ü, ç) matches the expected names, case-insensitively.

**Given** a query of digits ("5551112") or with formatting ("555 111")
**When** matched against "+90 555 111 22 33"
**Then** it matches by digits-only substring; a query with letters never matches digits-only.

**Given** `ContactIndex::match_number("05551112233")`
**When** a contact has "+905551112233"
**Then** it returns that contact (uses `PhoneNumber::matches`), and an empty query returns the full list in provider order.

**And** a 500-contact benchmark test asserts search completes in a single call with no per-keystroke index rebuild.

### Story 2.7: Dial-string rules and paste normalisation

As a developer,
I want dial-entry rules as pure functions,
So that Keypad behaviour is tested without a UI.

Covers: FR9, FR10, AD-11, UX-DR3, UX-DR4. Prio: P0. Verify: CARGO.

**Acceptance Criteria:**

**Given** `dial::normalise_paste("+90 (555) 111-22-33")`
**When** called, also with "1,2;3", "abc", "tel:+90555"
**Then** results are "+905551112233", "1,2;3", "" and "+90555".

**Given** `dial::validate(s)`
**When** s is "", "abc", "*#06#", "*123#", "112", "+90555", ",,"
**Then** only "" , "abc" and ",," are invalid; the rest are accepted and returned unchanged (raw).

**Given** `dial::push(field, key)` and `font_step(len)`
**When** the field reaches 32 characters, and at lengths 10/11/14/18
**Then** the 33rd key is refused with a `Shake` signal, and the size steps are 36/32/28/24 at 11/14/18.

**And** `long_press_zero` replaces the last typed `0` with `+` exactly once.

### Story 2.8: Setup checklist derivation

As a developer,
I want the Setup Item states derived from a facts struct,
So that FR-5 to FR-8 are testable.

Covers: FR5, FR7, FR8, AD-14, UX-DR11. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `setup::derive(facts, xiaomi: bool, confirmations, app_version)`
**When** facts mark role held, permissions granted, full-screen allowed
**Then** items are ordered per FR-5; Xiaomi items are absent when `xiaomi == false` and states are done/not done/unverifiable.

**Given** Xiaomi confirmations stored for version "0.1.0"
**When** the app version is "0.1.1"
**Then** confirmations are cleared and Xiaomi items show unverifiable again.

**Given** a Xiaomi item
**When** `can_confirm(item, opened_this_session)` is queried
**Then** it is false until the settings page was opened once in this session (UX-Q3 gating).

**Given** all items done
**When** progress is computed
**Then** it returns "{done} of {total}" with confirmed Xiaomi counted done and `complete == true`; a regression reports the names of reopened items in `something_changed`.

### Story 2.9: Frame statistics ring

As a developer,
I want an allocation-free frame stats ring,
So that profiling hooks exist before the perf pass.

Covers: AD-22, NFR5. Prio: P0 (infra). Verify: CARGO.

**Acceptance Criteria:**

**Given** `rovsti_core::perf::FrameStats` (fixed-size ring, no heap use after construction)
**When** 10,000 samples are pushed
**Then** p50/p95/p99/max and `over_budget(budget_ms)` / `over_double_budget` counts are correct for a known distribution and no panics occur at wrap-around.

**Given** `budget_ms_for_hz(60|90|120)`
**When** called
**Then** it returns 16.6 / 11.1 / 8.3 and an unknown rate falls back to 60 Hz.

---

## Epic 3: Kotlin/Rust Seam and Backend

Generic, app-agnostic plumbing in `rovsti_android` (reusable by Messages and Stops). Every Rust function compiles on desktop and returns `Error::Unsupported` there.

### Story 3.1: Event channel and `RovstiBridge.emit`

As a developer,
I want one Kotlin-to-Rust event path,
So that state pushes are safe before and after GPUI exists.

Covers: AD-5, AD-8, AD-16, NFR7, NFR13. Prio: P0. Verify: CARGO, ANDROID-CHECK, DEVICE (log).

**Acceptance Criteria:**

**Given** `rovsti_android::events` with `install_sink() -> Receiver<RawEvent>` and the JNI `nativeOnEvent(kind, json)`
**When** an event arrives with no sink
**Then** it is dropped without panic; with a sink it is enqueued only (no GPUI types touched); installing a new sink replaces the old one and `clear_sink()` sets `None`.

**Given** Kotlin `RovstiBridge.emit(kind, json)`
**When** called before `attachNative()` or when `nativeOnEvent` throws `LinkageError`
**Then** it returns silently (volatile flag, try/catch), and `CallRegistry` calls only `emit`, never `nativeOnEvent`.

**Given** a desktop build
**When** `events::install_sink` is called
**Then** it returns a channel that never yields, with no `Unsupported` panic; `BRIDGE.set` is idempotent across sessions.

### Story 3.2: Call snapshot, commands and `CallRegistry`

As a developer,
I want Kotlin to own call state and Rust to command it by id,
So that the UI always agrees with Telecom.

Covers: FR14-FR18, FR20, FR24, NFR3, AD-4, AD-6, AD-7. Prio: P0. Verify: ANDROID-CHECK, CARGO (Kotlin JVM test via gradle if available), DEVICE.

**Acceptance Criteria:**

**Given** `rovsti_android::calls` with `snapshot() -> String` and `answer, reject, disconnect, hold(on), mute(on), route(name), dtmf(char)`
**When** called with an unknown or stale `call_id`
**Then** they return `Ok(false)` and do nothing; on desktop they return `Error::Unsupported`.

**Given** the Kotlin `CallRegistry` (promoted from 1.6)
**When** a Telecom callback fires
**Then** mutation and snapshot building (including `seq` and `keyguard_locked`) occur on the main Looper in one synchronous step, no lock spans a binder call, `snapshot()` returns the last-built string, audio uses `CallEndpoint` only, and `onUnbind` pushes an empty snapshot.

**Given** `answerReplacing(call_id)`
**When** a second call is ringing and one is active
**Then** the active call is disconnected and the target is answered on the matching `onCallRemoved`, or after a 2 s timeout, and the pending op clears on any snapshot where the target is gone.

**And** a Kotlin JVM test (or documented manual script) reads the 2.1 fixtures and builds a matching JSON via `SnapshotJson.kt`.

### Story 3.3: Permissions and settings bridge

As a developer,
I want permission and settings calls behind `rovsti_android`,
So that Setup and list screens can check and request access.

Covers: FR5, FR6, FR26 (empty state), NFR7, NFR13, AD-5. Prio: P0. Verify: ANDROID-CHECK, CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `rovsti_android::permissions::{has(group), request(group), open_app_details(), open_default_apps(), can_use_full_screen_intent(), open_full_screen_intent_settings()}`
**When** called on Android
**Then** groups map to `PHONE (CALL_PHONE, READ_PHONE_STATE, READ_CALL_LOG, WRITE_CALL_LOG)`, `CONTACTS (READ_CONTACTS, WRITE_CONTACTS)`, `NOTIFICATIONS` and results return as Boolean; permanently denied is reported distinctly.

**Given** `MainActivity.onResume`
**When** it runs
**Then** it emits `permissions_changed` (the only emitter) via `RovstiBridge.emit`.

**Given** a desktop build
**When** any function is called
**Then** it returns `Error::Unsupported`.

### Story 3.4: Provider pulls and observers (call log, contacts, favorites)

As a developer,
I want JSON provider pulls off the UI thread,
So that lists load without blocking.

Covers: FR26, FR30, FR34, NFR4, AD-9, AD-10. Prio: P0 (call log), P1 (contacts, favorites). Verify: ANDROID-CHECK, CARGO, DEVICE.

**Acceptance Criteria:**

**Given** Kotlin `CallLogQuery` (limit 500, `DATE DESC`) and `ContactsQuery` (`HAS_PHONE_NUMBER`, `CommonDataKinds.Phone`, `STARRED`, `LOOKUP`, super-primary flag, label) plus `lookup(number)`
**When** `rovsti_android::providers::{call_log(limit), contacts(), favorites()}` run on `cx.background_executor()`
**Then** each returns one JSON envelope `{v, ok, data|error}` and a `SecurityException` returns `ok:false,error:"permission"`, never an exception.

**Given** the `ContentObserver`s for `CallLog.Calls` and `ContactsContract`
**When** the provider changes
**Then** `call_log_changed` / `contacts_changed` are emitted debounced 300 ms.

**Given** Kotlin JVM tests reading the 2.1 fixtures
**When** run
**Then** envelopes match the golden JSON.

### Story 3.5: `backend.rs` facade, sample data and attach order

As a developer,
I want one facade between Android and the views,
So that desktop shows sample data and views never see `Result`.

Covers: FR38 (base), AD-5 (attach order), AD-15, AD-16, NFR7. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `apps/phone/src/backend.rs`
**When** a screen requests calls, recents, contacts or favorites
**Then** it returns domain types (`Arc` snapshots); on `Error::Unsupported` it serves sample data and permission failures map to `Unavailable`/`PermissionDenied`; views contain no `Result`.

**Given** `main()` on a new GPUI session
**When** attaching
**Then** the order is (1) install the new sink, (2) push `calls::snapshot()` through the same channel, (3) pull permissions and providers; the sink is cleared when the session closes; events drain in a `cx.spawn` task on the main thread.

**Given** `cargo run -p rovsti_phone` on desktop
**When** any Android-only action is triggered
**Then** the UI shows "Not available on this platform" and does not panic; `rg "rovsti_android" apps/phone/src` matches only `backend.rs`.

### Story 3.6: Local preferences store

As a developer,
I want a small JSON prefs store in the no-backup data dir,
So that last tab, missed-seen time and Xiaomi confirmations persist.

Covers: FR8, FR36, FR27, NFR1, AD (State convention), NFR13. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `rovsti_ui::storage` with `Prefs { last_tab, missed_seen_ms, xiaomi_confirmed{item: version}, app_version }`
**When** saved and loaded through a temp dir in tests
**Then** it round-trips, a corrupt or missing file yields defaults, and writes are atomic (temp file + rename) and written by Rust only.

**Given** Android
**When** the data dir is resolved
**Then** it is the app's no-backup directory and no network type is referenced.

---

## Epic 4: Dial and Talk (P0)

### Story 4.1: Teal accent and system theme

As the owner,
I want the app in teal and following system light/dark,
So that it matches the icon and my phone.

Covers: FR37, UX-DR1, AD-13, NFR13. Prio: P0 (theme base). Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `rovsti_ui::theme::{AccentPair, set_accent, colors}`
**When** `set_accent` is called in Phone's `main()` with light `#08806E`, dark `#2DD4B4`, glow `#14C8A8`
**Then** `colors(theme).accent` returns the pair, all other colors equal `Theme::colors()`, and `liquid_glass_kit` is unmodified.

**Given** phone code
**When** built
**Then** `apps/phone` and `rovsti_ui` call only `rovsti_ui::theme::colors`; the workspace `disallowed-methods` ban is enabled for `rovsti_ui` and `apps/phone` (workspace-wide ban for Messages/Stops is a flagged follow-up).

**Given** the system theme changes while the app is open (`FOLLOW_SYSTEM_THEME`)
**When** it flips
**Then** every existing screen updates without restart (DEVICE check recorded).

### Story 4.2: Keypad number entry

As the owner,
I want to type, paste and delete digits reliably,
So that I can dial any number.

Covers: FR9, FR10 (shake), UX-DR3, UX-DR4, UX-DR22. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the Keypad tab with `dial` rules from 2.7
**When** a key goes down
**Then** the digit is in the field on the first frame, a light haptic fires, `0` held 400 ms becomes `+` once, delete removes one character and held 500 ms clears the field.

**Given** the field is empty and the clipboard holds "+90 (555) 111-22-33"
**When** the "Paste" pill is tapped
**Then** the field shows "+905551112233" and the pill hides.

**Given** 32 characters are entered
**When** another key is pressed
**Then** nothing is added and the field shakes once; with reduced motion the status text "Not a valid number" shows instead of a shake.

**And** key labels are "1", "2 A B C" ... "star", "pound", "0 plus" with hint "Long press for plus", and targets are at least 44 px (NFR-10).

### Story 4.3: One call path

As the owner,
I want every call to go through one path,
So that Keypad, Recents, Contacts and Favorites behave the same.

Covers: FR10, FR11, AD-11, UX-DR3 (debounce). Prio: P0. Verify: ANDROID-CHECK, CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `backend.place_call(raw)`
**When** the string is valid per 2.7 (including "*#06#" and "112")
**Then** `telecom::place_call(raw)` passes it unchanged to Kotlin `TelecomManager.placeCall`; no Setup Item gates it; a second press within 800 ms is ignored.

**Given** `CALL_PHONE` is missing
**When** a call is requested
**Then** the permission is requested and the call is placed after it is granted; if denied, the status line reads "Allow Phone access so Rovsti Phone can place calls." with "Grant access" and no call is placed.

**Given** the registry sees `STATE_SELECT_PHONE_ACCOUNT`
**When** the call is added
**Then** Kotlin calls `phoneAccountSelected(default outgoing or first suggested, false)` and the status stays "Calling...".

**And** with an empty field or no digit/*/# the call is not placed and the shake/"Not a valid number" shows.

### Story 4.4: Dial intents

As the owner,
I want links and other apps to open the Keypad with a number,
So that tapping a phone number in a browser works.

Covers: FR3, AD-12. Prio: P0. Verify: CARGO (parser), DEVICE.

**Acceptance Criteria:**

**Given** `MainActivity.onCreate/onNewIntent`
**When** `ACTION_DIAL` with `tel:+905551112233` arrives
**Then** it is rewritten to `ACTION_VIEW` before super and the app switches to Keypad with the number entered and the cursor at the end, placing no call.

**Given** `ACTION_DIAL` without data
**When** it arrives
**Then** the Keypad opens empty.

**Given** `tel:` URIs with `%23`, `*`, `,` or spaces
**When** parsed by a pure helper in `rovsti_core::dial`
**Then** the field gets the decoded dial string; `adb shell am start -a android.intent.action.DIAL -d tel:...` is the documented DEVICE test.

### Story 4.5: In-Call Screen rendering

As the owner,
I want to see who I'm talking to and for how long,
So that I know the call state at a glance.

Covers: FR13, UX-DR14, UX-DR20, AD-7, AD-3, AD-4. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a snapshot with a foreground call
**When** `incall.rs` renders
**Then** it shows avatar 96 (monogram or `LucideUser`), name (middle-truncated numbers), secondary number line when the name is a contact, and status via `status_text` ("Calling...", timer, "On hold"), with no Tab Bar.

**Given** the state is Active
**When** time passes
**Then** only the timer text repaints once per second (derived from `connect_time_ms`), and after the app is backgrounded and returned the timer is correct.

**Given** the app is launched from the launcher during a call
**When** it opens
**Then** the In-Call Screen shows instead of the tabs; the status line is an accessible live region.

**And** with `keyguard_locked && calls non-empty` no tab chrome is rendered (AD-3).

### Story 4.6: Mute and Speaker

As the owner,
I want mute and speaker controls that show the real state,
So that I never think I'm muted when I'm not.

Covers: FR14, FR15, NFR3, UX-DR14. Prio: P0. Verify: CARGO (model), DEVICE.

**Acceptance Criteria:**

**Given** the control grid
**When** Mute is tapped
**Then** `calls::mute(!current)` is issued on finger-down, the control shows a 120 ms pending press, and the displayed state changes only when the next snapshot reports it (within 500 ms); if unchanged after 1 s the control reverts silently.

**Given** a headset is connected
**When** the route list contains headset
**Then** the Speaker control shows `LucideHeadphones`/`LucideBluetooth`, tapping selects speaker, tapping again returns to the headset.

**Given** mute is toggled from a headset button
**When** the snapshot arrives
**Then** the control reflects it; labels read "Mute, on/off" and "Speaker, on/off".

### Story 4.7: In-Call Keypad and DTMF

As the owner,
I want to send touch tones,
So that I can use phone menus.

Covers: FR16, UX-DR15. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the In-Call Screen
**When** "Keypad" is tapped
**Then** the grid swaps to the 4x3 In-Call Keypad (72 px keys), a digits line shows the last 24 characters with left ellipsis, End stays visible, and "Hide" returns to the controls without affecting the call.

**Given** a key press
**When** finger down then up
**Then** exactly one `dtmf(char)` starts on down and stops on up, the digit is appended, and long-press `0` does not produce `+`.

**Given** Back is pressed with the In-Call Keypad open
**When** handled
**Then** it closes the keypad first (EXPERIENCE Back order).

### Story 4.8: End the call

As the owner,
I want End to disconnect and return me where I was,
So that ending a call is instant and clean.

Covers: FR18, UX-DR14, NFR3. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** an active call
**When** End is tapped
**Then** `disconnect(call_id)` runs on finger-down with no confirmation and the status shows "Call ended" for about 1 s, then the screen returns to the previous tab.

**Given** the remote party hangs up, or the call fails with a cause
**When** the snapshot reports Disconnected
**Then** the same flow runs, and a failure shows "Call failed" plus the reason line for 2 s.

**Given** the call began locked
**When** it ends
**Then** the app renders blank, calls `moveTaskToBack(true)` and the lock screen returns (AD-3a); End is reachable with Keypad open and in Hold.

### Story 4.9: Ongoing-call notification and return

As the owner,
I want a notification with End while a call is live,
So that I can always end or return to a call.

Covers: FR19, NFR6, UX-DR19, AD-2. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** a live call
**When** the service starts foreground
**Then** a `CallStyle.forOngoingCall` notification (channel `ongoing`, low) shows the caller, chronometer and End; End calls `CallRegistry` directly with no Rust involvement.

**Given** the notification body is tapped
**When** the app is in the background
**Then** the In-Call Screen opens (activity PendingIntent, no broadcast trampoline).

**Given** the call ends
**When** the last call is removed
**Then** the notification and foreground state are removed.

### Story 4.10: Failure containment

As the owner,
I want a crash or GPUI error never to drop my call,
So that I can trust the dialer.

Covers: NFR6, AD-17, AD-1 (Back). Prio: P0. Verify: BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the owned `GpuiActivity.kt`
**When** edited
**Then** `onCreate` routes `GpuiSession.setOnError` to an overridable `onGpuiError(error)` which `MainActivity` implements by keeping the call notification and showing a native message only; `CallRegistry` has no GPUI dependency.

**Given** a debug-only broadcast `dev.rovsti.phone.DEBUG_KILL_GPUI`
**When** sent during a call
**Then** the call continues and can be ended from the notification; the owner records the result.

**Given** Back on the root of the app
**When** pressed
**Then** `moveTaskToBack(true)` runs (never `finish()`), keeping the session warm.

---

## Epic 5: Never Miss a Call (P0)

### Story 5.1: Incoming Call Notification (unlocked)

As the owner,
I want a heads-up with Answer and Decline when I'm using my phone,
So that I can handle calls without leaving what I'm doing.

Covers: FR23, AD-2, UX-DR19. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** the unlocked phone with an incoming call
**When** the notification posts
**Then** it is a `CallStyle.forIncomingCall(person, decline, answer)` with the caller name resolved in Kotlin (`ContactsQuery.lookup`, number if none), and it shows even when GPUI never started.

**Given** the notification actions
**When** Decline is tapped
**Then** the call is rejected via `CallActionReceiver` without opening the app; Answer answers and opens the In-Call Screen via an activity PendingIntent; tapping the body opens the Incoming Call Screen.

**Given** `canUseFullScreenIntent()` is false
**When** a call arrives
**Then** the heads-up with Answer/Decline is the only surface and still works.

### Story 5.2: Incoming Call Screen over the lock screen

As the owner,
I want the incoming screen to wake the phone and show over the lock screen,
So that I can answer without unlocking.

Covers: FR22, UX-DR16, UX-DR20, AD-1, AD-3. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** a ringing call, phone locked
**When** `MainActivity` is raised
**Then** `incoming.rs` shows label "Incoming call" with `LucideLock`, avatar 120, name (or "Unknown"), the formatted number for contacts, solid 76 px Decline (left) and Answer (right) with captions, and the first frame is never blank (wash + name).

**Given** `keyguard_locked` and a ringing call
**When** rendering
**Then** no Tab Bar, tabs or sheets render; Back and Home do nothing while ringing.

**Given** a cold start
**When** the call arrives
**Then** the notification is visible first and the screen follows; the S2 timestamps are re-logged for 5.6.

**And** TalkBack announces "Incoming call from {name}" assertively; focus order is caller, Answer, Decline.

### Story 5.3: Answer and decline from the screen

As the owner,
I want one tap to answer or decline,
So that I never fumble a call.

Covers: FR24, NFR3, AD-6, UX-DR16. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** the Incoming Call Screen
**When** Answer is tapped
**Then** `answer(call_id)` is issued on finger-down, the state becomes Active and the In-Call Screen takes over with no unlock and no animation delaying the action.

**Given** Decline is tapped
**When** handled
**Then** `reject(call_id)` runs, the screen dismisses and the lock screen returns.

**Given** the notification Answer was used (5.1)
**When** the app opens
**Then** `answer_call_id` extra is honoured exactly once and stale ids are no-ops.

### Story 5.4: Ringer behaviour

As the owner,
I want ringing and silent mode to behave as the system dictates,
So that Do Not Disturb and per-contact ringtones work.

Covers: FR21, PRD 4.5 decision. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** `IN_CALL_SERVICE_RINGING` unset
**When** a call rings in normal mode
**Then** Telecom plays the ringtone and vibration within 1 s of the screen/notification (NFR-2), and the app plays no sound itself.

**Given** silent mode or Do Not Disturb
**When** a call arrives
**Then** there is no sound, but the Incoming Call Screen or notification still appears.

**Given** a volume key press
**When** ringing
**Then** the ring is silenced by the system and the screen stays until answered, declined or remote end.

### Story 5.5: Missed calls

As the owner,
I want a ringing-out call recorded as missed and visible,
So that I can call back.

Covers: FR25, UX-DR16. Prio: P0. Verify: CARGO (model), DEVICE.

**Acceptance Criteria:**

**Given** a ringing call that disconnects without being answered
**When** the screen is still showing
**Then** it shows "Missed call from {name}" for 1.2 s then dismisses (moves to back if locked).

**Given** the call log provider
**When** the missed entry is written by Telecom
**Then** `call_log_changed` fires and Recents will list it (6.1); the app posts no notification of its own and declares no missed-calls broadcast handler (PRD Q4).

**Given** a missed call while GPUI is not running
**When** the owner later opens the app
**Then** the missed entry appears (no state kept outside the provider).

### Story 5.6: Ring-latency measurement and surface decision

As the owner,
I want measured ring latency recorded,
So that I know whether the glass incoming screen is fast enough or a fallback is needed.

Covers: NFR2, AD-1, SM-C1, UX-DR16 fallback. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** logging at `onCallAdded` and first GPUI frame (tag `RovstiPerf`)
**When** 5 warm and 5 cold incoming calls are made, locked and unlocked
**Then** min/median/max are tabulated in `docs/spikes/s2-incoming.md` against 1.5 s / 3 s.

**Given** a cold-start median over 3 s or any blank frame
**When** the table is reviewed
**Then** the "native Kotlin curtain over the SurfaceView" contingency (removed on first GPUI frame; same layout and copy, solid fills per UX fallback skin) is implemented in this story; otherwise the story records "curtain not needed".

### Story 5.7: Proximity screen-off

As the owner,
I want the screen to blank at my ear,
So that I don't hit buttons with my cheek.

Covers: PRD 4.4 assumption, AD (ProximityController). Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** an Active or Dialing call on the earpiece route
**When** `ProximityController` acquires `PROXIMITY_SCREEN_OFF_WAKE_LOCK` (needs `WAKE_LOCK`)
**Then** the screen turns off near the ear and on when removed; with speaker or headset route the screen stays on.

**Given** the call ends or the route changes
**When** the controller updates
**Then** the lock is released using the flag verified in 1.8 with no leak (checked with `dumpsys power`).

---

## Epic 6: Recents (P0)

### Story 6.1: Recents list

As the owner,
I want my call history grouped and readable,
So that I see who called.

Covers: FR26, NFR4, UX-DR5, UX-DR6, UX-DR20. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** call log data from `backend`
**When** the Recents tab opens
**Then** `call_log::group` rows render in a virtualised list of fixed 58 px rows (only viewport plus one screen built), showing avatar 40, title, "(n)" when n>1, direction icon + label, and time per 2.5; missed rows are red with the word "missed" in the accessible label; rejected rows show `LucidePhoneOff` and "Declined".

**Given** 500 entries
**When** the tab opens
**Then** first rows show within 1 s and the provider query runs off the UI thread; scroll offsets are arithmetic (no per-row measurement).

**And** row labels read "{Name}, missed call, 2 calls, yesterday 3:14 pm, mobile. Double tap to call."

### Story 6.2: Recents empty, loading and permission states

As the owner,
I want clear states when there is nothing or no access,
So that I know what to do.

Covers: FR26, UX-DR12, UX-DR22. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `READ_CALL_LOG` is missing
**When** Recents opens
**Then** the TriangleAlert state shows "Allow call log access" with pill "Grant access"; after two denials the pill reads "Open settings" and opens the app details page.

**Given** no entries
**When** opened
**Then** `LucideClock`, "No recent calls", "Calls you make and receive show up here." (plus the preview sentence in Preview mode).

**Given** loading takes over 150 ms
**When** the tab opens
**Then** skeleton rows fade in (no spinner, no shimmer); under 150 ms none flash.

### Story 6.3: Call back from Recents

As the owner,
I want to tap a row to call that number,
So that calling back takes one tap.

Covers: FR28, FR11, UX-DR6. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a row with a number
**When** tapped
**Then** `backend.place_call(raw)` runs (4.3) with the row's raw number and the In-Call Screen takes over.

**Given** an unknown or private (empty) number row
**When** tapped
**Then** nothing is called, the row is dimmed with no press feedback, and TalkBack says "Unknown number, cannot call back".

**Given** the info button (44 px, separate target) or a row long-press
**When** activated for a number with no contact match
**Then** the Number sheet (UX-DR9) opens with "Call" and "Add to contacts" (system insert intent), and for a contact match the Contact Sheet opens once Epic 8 exists (until then the Number sheet is used for all).

**Note:** the Sheet used here is a minimal medium-detent sheet; 8.3 upgrades it to the full component.

### Story 6.4: Cancel the system missed-call notification when Recents is viewed

As the owner,
I want viewing Recents to clear the system missed-call notification,
So that stale alerts disappear.

Covers: FR25, AD (Deferred note: `markMissedCallsRead`). Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the Recents tab is selected and its first rows have been on screen for 1 s
**When** the view timer fires once
**Then** `markMissedCallsRead()` runs (clears Android's `NEW` flag and calls `TelecomManager.cancelMissedCallsNotification()`), and `missed_seen_ms` is stored in prefs.

**Given** rows that were new
**When** the owner keeps looking
**Then** they stay red until the next view cycle (no colour change while viewing).

**Given** the permission is missing
**When** Recents is viewed
**Then** nothing is written and no error shows.

---

## Epic 7: Setup Checklist and Default Phone App (P1)

### Story 7.1: Setup Checklist card

As the owner,
I want a checklist of everything needed for reliable calls,
So that I can see and fix what is missing.

Covers: FR5, UX-DR11, UX-DR13, UX-DR20, NFR13. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `setup::derive` (2.8) and `rovsti_android::xiaomi::detect`
**When** the card renders at the top of each tab (scrolls with the page, not pinned)
**Then** it lists items in FR-5 order with state icon (open circle / check / "needs confirmation"), title, one-line reason (UX copy), progress "{done} of {total} done", and Xiaomi items only on Xiaomi.

**Given** a non-Xiaomi device or desktop
**When** rendered
**Then** the four Xiaomi items are absent (5 items).

**And** the card is a glass sheet (kit sheet material); labels read "{title}, done/not done/needs confirmation" and action "Set up {title}"; only one banner/card shows above a list (precedence UX-DR13).

### Story 7.2: Act on a Setup Item

As the owner,
I want each item's button to take me to the fix,
So that setup is a few taps.

Covers: FR6, UX-DR11. Prio: P1. Verify: ANDROID-CHECK, DEVICE.

**Acceptance Criteria:**

**Given** a permission item
**When** "Set up" is tapped
**Then** the runtime request for its group is issued; after permanent denial the button becomes "Open settings" opening the app details page with the copy "Turn on Phone, Call logs and Contacts under Permissions."

**Given** the Full-screen notifications item (Android 14+)
**When** not done
**Then** it opens `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` and turns done after the resume re-check.

**Given** a Xiaomi item
**When** "Open settings" is tapped
**Then** the MIUI/HyperOS deep link opens; if the component is missing, the app details page opens and the note "This phone's menu is different. Look for Autostart, Battery saver, Other permissions and Notifications." shows; Battery saver first shows the AOSP ignore-battery-optimizations dialog and marks the AOSP half done from its read-back result.

### Story 7.3: Request the default phone role

As the owner,
I want one tap to become the default phone app,
So that Rovsti Phone handles every call.

Covers: FR1, FR2, UX-DR11. Prio: P1. Verify: DEVICE.

**Acceptance Criteria:**

**Given** the Default phone app item is not done
**When** "Set up" is tapped
**Then** `roles::request(Role::Dialer)` opens the system dialog; accepting it marks the item done without restarting, and the Phone/Contacts permission items may flip to done without a prompt (tests must not assume a prompt).

**Given** the request returns false or the dialog does not appear
**When** the result is handled
**Then** the item expands with "Your phone didn't show the dialog. Open Settings, Apps, Manage apps, Default apps, Phone, then choose Rovsti Phone." and an "Open default apps" button (`ACTION_MANAGE_DEFAULT_APPS_SETTINGS`).

**Given** the role is held
**When** the item renders
**Then** it shows an inert "Open settings" link only.

### Story 7.4: Re-check on launch and resume; collapse when complete

As the owner,
I want the checklist to notice changes,
So that a HyperOS update never silently breaks calls.

Covers: FR7, UX-DR11. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the app launches or resumes
**When** `permissions_changed` and role/full-screen/battery read-backs are collected
**Then** readable items update with a quiet cross-fade and no layout motion; revoking a permission in system settings shows that item as not done on return.

**Given** all items done (Xiaomi confirmed)
**When** evaluated
**Then** the card collapses to a 44 px "Setup complete" row (tap expands) and stays collapsed across launches.

**Given** a previously done item regresses
**When** the app resumes
**Then** the card re-expands showing open items first with the header "Something changed: {item name}" and never blocks calling.

### Story 7.5: Confirm Xiaomi settings

As the owner,
I want to mark a Xiaomi setting as done after I checked it,
So that the checklist reflects reality without false "done".

Covers: FR8, UX-Q3 (gated Confirm), UX-DR11. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** an unverifiable Xiaomi item never opened this session
**When** rendered
**Then** "Confirm" is disabled with the hint "Open settings first, then confirm here"; after "Open settings" was used once it is enabled.

**Given** "Confirm" is tapped
**When** stored
**Then** the confirmation persists across restarts in prefs (3.6) and is cleared when the app version changes (2.8).

**Given** the unverifiable item
**When** read by TalkBack
**Then** it says "needs confirmation" and the visual copy is "Needs your confirmation. Rovsti can't check this setting."

### Story 7.6: Preview mode

As the owner,
I want the app to say when it isn't the default phone app,
So that I know calls will hand off to the system.

Covers: FR4, UX-DR13. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the role is not held
**When** any tab renders
**Then** a 44 px glass banner "Not the default phone app" with `LucideSmartphone` (warning) and "Fix" shows below the title unless the checklist already shows the default-app item; "Fix" scrolls to the checklist's first item.

**Given** a call is placed in Preview mode
**When** it goes through the system path
**Then** it works as in 4.3 (system in-call UI acceptable) and a one-line status reads "Placed through the system phone app".

**Given** the role becomes held
**When** the banner re-evaluates
**Then** it disappears without restart.

---

## Epic 8: Contacts and Favorites (P1)

### Story 8.1: Contacts list

As the owner,
I want to browse my contacts alphabetically,
So that I can find a person.

Covers: FR30, NFR4, UX-DR7, UX-DR12. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** contacts from `backend`
**When** the tab opens
**Then** only contacts with a number list in provider order, sections by first letter with non-letters under "#", sticky 28 px headers, fixed 58 px rows, virtualised, subtitle "mobile and 2 more" only for multiple numbers.

**Given** `READ_CONTACTS` is missing or the list is empty
**When** the tab opens
**Then** "Allow contacts access" + "Grant access"/"Open settings", or `LucideUsers` "No contacts with a phone number" + pill "Open Contacts" show.

**And** a list of 500+ contacts shows first rows under 1 s with provider loads off the UI thread.

### Story 8.2: Contact search

As the owner,
I want to search by name or number as I type,
So that I find people fast.

Covers: FR31, UX-DR7. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the sticky 44 px glass search field ("Search name or number")
**When** the owner types "ays"
**Then** `ContactIndex` (2.6) filters on every keystroke (off-thread above 200 contacts), "Ayşe" matches, sections flatten, and number matches show the matched number as the subtitle.

**Given** no match
**When** shown
**Then** "No matches" and "No contact matches \"{query}\"." (query truncated to 24 characters).

**Given** focus and Back
**When** the field has focus
**Then** the clear button empties and keeps focus, first Back clears focus, second does nothing; the Tab Bar hides while the keyboard is up; text input has autocorrect/suggestions off.

### Story 8.3: Sheet component

As a developer,
I want a reusable draggable glass sheet,
So that Contact Sheet, Number sheet and chooser share behaviour.

Covers: UX-DR8, NFR12, AD-21. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `rovsti_ui::sheet`
**When** opened
**Then** it rises to a medium detent (about 55%) with a 36x5 grabber and 38 radius, drags to large (about 92%) using the kit's `gesture::project` and `rubber_band`, dismisses by drag-down, dim-tap or Back, and is never nested.

**Given** a sheet is dragging
**When** it moves
**Then** only its position changes (glass tint fixed, blur not animated) and the page behind dims to 0.85 and scales to 0.97 only via transform/opacity.

**Given** the sheet opens
**When** TalkBack is on
**Then** it announces the title, focus moves to the first control and Back returns focus to the origin; the 6.3 minimal sheet is replaced by this one.

### Story 8.4: Contact Sheet with call actions

As the owner,
I want a sheet with a person's numbers,
So that I choose which number to call.

Covers: FR32, FR11, UX-DR9. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a contact row tap
**When** the Contact Sheet opens at medium detent
**Then** it shows avatar 80, name `title2`, every number with label (mobile/home/work/other), a "Default" caption on the super-primary number, and a trailing 36 px answer-colour call button per row; the whole row also calls.

**Given** a number tapped
**When** handled
**Then** `backend.place_call` runs (no sheet-animation blocking) and the In-Call Screen shows.

**Given** "Edit in Contacts"
**When** tapped
**Then** `ACTION_EDIT` opens the system Contacts app and returning refreshes the contact.

### Story 8.5: Star and unstar

As the owner,
I want to star someone from their sheet,
So that they appear in Favorites.

Covers: FR33, UX-DR9, AD-9. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the 44 px star toggle in the Contact Sheet
**When** tapped
**Then** it toggles visually at once with a medium haptic and `setStarred(contact_id, bool)` writes `STARRED`, visible in the system Contacts app.

**Given** the write fails (for example no `WRITE_CONTACTS`)
**When** the result returns false
**Then** the toggle reverts and the inline error "Couldn't update favorite" shows for 3 s.

**Given** the star is set
**When** Favorites is next shown
**Then** the contact appears (read from the provider; no local favorites store); this is the only provider write besides `markMissedCallsRead`.

### Story 8.6: Favorites grid

As the owner,
I want my starred contacts as tiles,
So that I see my people at a glance.

Covers: FR34, NFR4, UX-DR10, UX-DR12. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `favorites()` data
**When** the Favorites tab opens
**Then** a 2-column grid (3 at 440 dp or wider) of 132 px tiles shows monogram avatar 64 and the name on one line, in provider order, scrolling, virtualised by rows.

**Given** a contact starred in another app
**When** the tab is next opened
**Then** it appears (no live refresh required while open).

**Given** none starred
**When** opened
**Then** `LucideStar`, "No favorites yet", "Star someone in Contacts to call them with one tap." and pill "Go to Contacts" switch tab; permission state mirrors 8.1.

### Story 8.7: Call a Favorite

As the owner,
I want one tap on a tile to call,
So that calling my sister is instant.

Covers: FR35, FR11, UX-DR9 (chooser). Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a contact with a default (super-primary) number
**When** the tile is tapped
**Then** that number is called via the call path.

**Given** a contact with a single number and no default
**When** tapped
**Then** it is called directly; with several numbers and no default the Number chooser sheet ("Call {name}") opens and a pick calls that number.

**Given** a long-press of 450 ms
**When** held
**Then** a light haptic fires and the Contact Sheet opens.

### Story 8.8: Recents info opens the Contact Sheet

As the owner,
I want a Recents row's info button to open the person,
So that I can star or pick another number from history.

Covers: FR28, UX-DR9. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a Recents row whose number matches a contact (`ContactIndex::match_number`)
**When** the info button or long-press is used
**Then** the Contact Sheet opens; otherwise the Number sheet (6.3) opens.

**Given** an unknown/private row
**When** the info control is used
**Then** no call action is offered ("Add to contacts" only if a number exists).

---

## Epic 9: Hold, Waiting Call, Return-to-Call and Tab Navigation (P1)

### Story 9.1: Hold and Resume

As the owner,
I want to put a call on hold,
So that I can step away briefly.

Covers: FR17, UX-DR14. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `can_hold` is true
**When** Hold is tapped
**Then** `hold(call_id, true)` runs, state becomes Holding per snapshot, the control becomes "Resume" (play icon, on-state), status shows "On hold" in warning colour, and Mute, Speaker, Keypad dim to 0.4 while End stays enabled.

**Given** `can_hold` is false or state is Dialing
**When** the grid renders
**Then** Hold is disabled and dim with no error text.

**Given** Resume is tapped
**When** the snapshot reports Active
**Then** the controls return to normal.

### Story 9.2: Waiting Call

As the owner,
I want to answer or decline a second incoming call,
So that I don't lose either caller unknowingly.

Covers: FR20, NFR3, AD-6, UX-DR17. Prio: P1. Verify: CARGO, DEVICE (carrier-dependent).

**Acceptance Criteria:**

**Given** `waiting_call_id` is present
**When** the In-Call Screen renders
**Then** a 76 px banner slides down with avatar 40, name, "Waiting", Decline and Answer 36 px circles and the footnote "Answer ends current call"; the first call's timer keeps running.

**Given** Decline is tapped
**When** handled
**Then** `reject(waiting_id)` runs and the first call stays Active.

**Given** Answer is tapped
**When** handled
**Then** `answerReplacing(waiting_id)` ends the first call and answers the second on `onCallRemoved` (2 s timeout), and if the waiting caller hangs up first the banner slides away.

### Story 9.3: Return-to-call pill

As the owner,
I want to get back to a call from the tabs,
So that leaving the call screen is safe.

Covers: FR19, UX-DR18, UX-DR13. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a live call and the In-Call Screen is left via Back or home gesture
**When** the tabs show
**Then** a glass capsule "Return to call - m:ss" (answer colour at 20%, `LucidePhone`) shows above the checklist/banner stack with a live timer, and tapping it returns to the In-Call Screen.

**Given** the call ends
**When** the snapshot reports no calls
**Then** the pill disappears.

**And** no sheet can open over a live call and the pill is not tappable through an open sheet.

### Story 9.4: Tab navigation behaviour

As the owner,
I want responsive tab switching,
So that moving around feels instant.

Covers: FR36, UX-DR2, UX-DR5. Prio: P1. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the Tab Bar
**When** a second tap lands during the puck flight
**Then** the puck retargets (interruptible spring) without restart; tapping the selected tab scrolls the list to top (Keypad: no-op).

**Given** the In-Call or Incoming screen, or the keyboard in Contacts search
**When** shown
**Then** the Tab Bar is hidden, not dismissed.

**Given** a first launch
**When** the app opens
**Then** Keypad is selected; large titles collapse to a 44 px bar past 24 px scroll with opacity/translate only; tab labels are "Favorites", "Recents", "Contacts", "Keypad" and Recents appends ", {n} missed calls" when a badge exists.

---

## Epic 10: P2 Polish

### Story 10.1: Keypad contact match

As the owner,
I want the Keypad to show who I'm dialing,
So that I avoid mistakes.

Covers: FR12, UX-DR4. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a contact stored as "+905551112233"
**When** "05551112233" is typed
**Then** the status line under the field shows that name via `ContactIndex::match_number`, computed off-thread and never blocking typing.

**Given** no match, invalid text, or a call in progress
**When** rendered
**Then** the status line shows nothing, "Not a valid number" or "Calling..." respectively.

### Story 10.2: Missed Call Badge

As the owner,
I want a red count on the Recents tab,
So that I see missed calls from anywhere in the app.

Covers: FR27, UX-DR2. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `missed_badge(entries, missed_seen_ms)` > 0
**When** the Tab Bar renders
**Then** an 18 px danger capsule with white `caption2` count sits 6 px up and right of the Recents icon, "99+" above 99, hidden at 0, and it clears when Recents is viewed (6.4).

**Given** first load or a re-render with the same count
**When** it renders
**Then** no pop plays (pop behaviour is added in 11.8).

### Story 10.3: Recents live refresh

As the owner,
I want a finished call to show in Recents without refresh,
So that history is always current.

Covers: FR29, UX-DR6, AD-9. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** Recents is open
**When** a call ends and `call_log_changed` arrives (debounced 300 ms)
**Then** the new row appears at the top within 2 s, fading in, with no layout spring; a user scrolled down keeps their position (no jump) and the data swap is an Arc swap.

### Story 10.4: Desktop sample data

As a developer,
I want every screen previewable on desktop,
So that I can iterate on UI without a phone.

Covers: FR38, UX-DR22. Prio: P2. Verify: CARGO (cargo run -p rovsti_phone).

**Acceptance Criteria:**

**Given** `cargo run -p rovsti_phone` on Linux
**When** Recents, Contacts, Favorites, Incoming and In-Call (with mute/hold/waiting variants) are opened via a debug switcher
**Then** each shows sample data with the caption "Preview data" in tertiary-label under the title; the debug switcher is compiled only for desktop.

**Given** an Android-only action
**When** tapped on desktop
**Then** "Not available on this platform" shows and nothing panics.

### Story 10.5: Contact photos on Favorites

As the owner,
I want contact photos on tiles,
So that Favorites feels personal.

Covers: PRD 4.8 assumption, UX-DR10, AD-21. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a contact with a photo
**When** its tile is visible
**Then** the monogram shows first, the photo is decoded off-thread to 128 px, cached in a 64-entry LRU keyed by contact id, and fades in with `appear`.

**Given** scrolling
**When** tiles leave and return
**Then** no decode runs on the UI thread and LRU eviction is unit-tested.

### Story 10.6: Remember the last tab

As the owner,
I want the app to reopen on the tab I used last,
So that Favorites is one step away.

Covers: FR36, UX-DR2. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** prefs (3.6)
**When** the app relaunches
**Then** the stored tab opens; a dial intent forces Keypad; the first launch opens Keypad; a corrupt value falls back to Keypad.

---

## Epic 11: Signature Moments and Motion

Each moment is built from transforms, opacity and colour mixes of already-painted quads (DESIGN.md); none adds a blur change or layout spring. At most two moments run at once per screen except S3/S8, which always run immediately. All honour reduced motion. Verify for all: CARGO (spring/state logic unit tests), DEVICE (feel and perf), and kit tests `shader_validates` / `glass_snapshot` remain green.

### Story 11.1: Moment infrastructure

As a developer,
I want shared helpers for moments,
So that each moment is small and consistent.

Covers: UX-DR21, UX-DR24, AD-21. Prio: P2. Verify: CARGO.

**Acceptance Criteria:**

**Given** `rovsti_ui::moments`
**When** built
**Then** it provides `ReducedMotion` detection (system animation scale 0 / `prefers_reduced_motion`), a `RippleRing` (RELEASE_RING bezier [0,0,0.2,1]), a collision scheduler (max two concurrent, later waits 120 ms, S3/S8 exempt), and spring-token wrappers using fixed-size arrays (no per-frame allocation).

**Given** springs settle
**When** the scheduler is idle
**Then** no frames are requested (unit-tested with a fake clock).

### Story 11.2: S1 Drop key and S2 Liquid number field

As the owner,
I want the keypad to feel liquid,
So that dialing is a pleasure.

Covers: S1, S2, UX-DR3, UX-DR4. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** a key down
**When** it plays
**Then** the key presses to 0.94 and swaps to refractive `control`+`lens` for the held key, a ripple ring scales 1.0 to 1.5 over 380 ms, and a digit copy drops to the field (about 220 ms, up to 3 in flight) while the real digit is already in the field on the first frame and pops 1.18 to 1.0.

**Given** delete
**When** pressed
**Then** the last digit shrinks to 0.6 and fades on `morph` and the string re-lays out once; font step changes cross-fade on `morph`.

**Given** reduced motion
**When** a key is pressed
**Then** only press scale applies; no ripple, no drop.

### Story 11.3: S3 Call blooms, S4 Shared-element avatar, S8 Answer morph

As the owner,
I want the Call and Answer buttons to become the End button,
So that going into a call feels continuous.

Covers: S3, S4, S8, UX-DR14, UX-DR16. Prio: P2. Verify: DEVICE.

**Acceptance Criteria:**

**Given** a valid Call tap
**When** the call is placed
**Then** the call is placed first (never delayed), the circle pulses 0.94, a ring expands 1.0 to 2.2 over 460 ms, and when the In-Call Screen is ready the circle morphs to the End button (78 to 220 px, answer to danger, phone to phone-off); if not ready within 300 ms it stays pressed with "Calling..." and morphs when ready.

**Given** a tile/row tap that places a call
**When** it plays
**Then** the avatar lifts 1.08 and flies to the 96 px In-Call slot on `morph` (about 250 ms) and flies back on End if still on screen.

**Given** Answer is tapped
**When** it plays
**Then** Decline fades in 120 ms, Answer becomes End, the avatar scales 120 to 96, the connect pulse plays and total is about 300 ms; the call is already answered when the finger lifts.

**Given** reduced motion
**When** any of the three would play
**Then** a 120 ms cross-fade replaces it.

### Story 11.4: S5 Tab flow

As the owner,
I want tab changes to flow with direction,
So that navigation feels physical.

Covers: S5, FR36, UX-DR2. Prio: P2. Verify: DEVICE.

**Acceptance Criteria:**

**Given** a tab selection
**When** it plays
**Then** the puck squashes and stretches (`select` X/Y ζ 0.6/0.7 k 250), the new page enters 28 px from the direction of travel with fade on `appear`, the selected icon hops 1.0 to 1.12, and the title slides 12 px extra; the old page does not animate.

**Given** the already-selected tab or a rapid second tap
**When** tapped
**Then** nothing replays and the animation retargets.

**Given** reduced motion
**When** a tab is selected
**Then** a 120 ms fade and the puck jumps.

### Story 11.5: S6 Call aura, avatar hue pairs and state ring

As the owner,
I want a living background and expressive avatars during calls,
So that each call has its own atmosphere.

Covers: S6, UX-DR23, UX-DR14. Prio: P2. Verify: CARGO (hue hash, state mapping), DEVICE.

**Acceptance Criteria:**

**Given** `avatar_hue(contact_id_or_normalized_number)`
**When** called
**Then** it is stable across runs, maps to one of six pairs, and unknown numbers use the base pair on grey (unit-tested); monogram = 135 degree gradient with 1.5 px inner rim.

**Given** the In-Call Screen with the screen on
**When** it is visible
**Then** three radial-gradient quads orbit on 7/9/11 s from one shared phase value (position and 0.96-1.04 scale only); Dialing pulses 0.8 Hz 6%, Active calm, first answer connect pulse 1.12, Holding desaturates with warning tint and 40% speed, Muted 60% alpha and speed, Speaker 1.15 widen, Waiting a double-pulse, Disconnected collapses (danger tint on failure); it does not react to audio level.

**Given** the state ring (3 px)
**When** state changes
**Then** it cross-fades per mapping (Dialing glow 60%, Active hue, Holding warning, Muted dashed 8 segments, failed danger).

**And** drift pauses with proximity screen-off and in the background; reduced motion shows static blobs with colour cross-fades only.

### Story 11.6: S7 Incoming entrance

As the owner,
I want the incoming screen to arrive with presence,
So that a call is unmistakable.

Covers: S7, UX-DR16, FR22. Prio: P2. Verify: DEVICE.

**Acceptance Criteria:**

**Given** the Incoming Call Screen appears
**When** it plays
**Then** the first frame shows wash and name; aura blobs bloom from 0.6 on `appear`, the avatar drops 40 px with overshoot (ζ 0.6, k 420), text fades up 12 px, Decline then Answer spring in 80 ms apart from scale 0.8; both buttons are tappable from the first frame they show.

**Given** ringing
**When** looping
**Then** two sonar rings (1.0 to 1.9, 3 px, alpha 0.35 to 0) repeat every 1.6 s 0.8 s apart, the avatar breathes 1.0-1.04 at 0.5 Hz, Answer glow pulses 0 to 0.25 over 1.2 s; all stop on answer, decline or remote end.

**Given** reduced motion
**When** shown
**Then** static blobs, one static ring, buttons appear with a 120 ms fade.

### Story 11.7: S9 Timer lands, S10 Control tint flood, S11 Call ended collapse

As the owner,
I want call state changes to be felt,
So that I trust what the screen says.

Covers: S9, S10, S11, FR14, FR15, FR17, FR18. Prio: P2. Verify: DEVICE.

**Acceptance Criteria:**

**Given** the call becomes Active
**When** S9 plays
**Then** "Calling..." slides up 8 px while the timer slides in from 8 px below, the state ring flips on `select`, and a light haptic fires.

**Given** Mute, Speaker or Hold reported state changes
**When** S10 plays
**Then** an accent 20% circle scales 0 to 1 inside the control (`select` ζ 0.76 k 750) with a 6 degree icon wobble; it plays only on reported state (never on a reverted press).

**Given** Disconnected
**When** S11 plays
**Then** controls dim to 0.4, the aura collapses, the avatar scales 0.9 and fades to 0.6, "Call ended" holds about 1 s (2 s on failure), then the screen fades out as the tab page fades in; reduced motion is a fade only.

### Story 11.8: S12 Star burst, S13 Badge pop, S14 Setup complete

As the owner,
I want small rewards at the right moments,
So that the app feels crafted without being noisy.

Covers: S12, S13, S14, FR27, FR33, FR7. Prio: P2. Verify: DEVICE.

**Acceptance Criteria:**

**Given** a star set on
**When** the write succeeds
**Then** the star scales 1.35 to 1.0 with 6 dots flying 24 px over 400 ms and a medium haptic; off is 0.85 and back with no dots; a failed write plays nothing.

**Given** the missed count increases
**When** the badge updates
**Then** it pops 1.0 to 1.25 and the Recents icon wiggles 8 degrees once; not on first load or equal re-render.

**Given** the last Setup Item turns done in this session
**When** the card collapses
**Then** it morphs once on `morph`, the check pops 0.6 to 1.0 and the wash brightens 0.10 for 600 ms; never replayed on launch; reduced motion is instant.

### Story 11.9: S15 Theme melt and S16 Sheet rise

As the owner,
I want theme and sheet changes to be smooth,
So that nothing snaps.

Covers: S15, S16, FR37, UX-DR8. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the system theme changes
**When** S15 plays
**Then** one `theme-fade` spring (ζ 1, k 600, about 260 ms) cross-fades colours (RGB mix), glass tint, shadow and aura palette together; if the previous frame exceeded 2x budget it snaps.

**Given** a Contact Sheet opens
**When** S16 plays
**Then** the sheet rises (ζ 1, k 450), the page scales 0.97 and dims to 0.85, the avatar scales 0.8 to 1.0 after 60 ms and number rows fade up 8 px staggered 30 ms; reduced motion is a 120 ms fade.

### Story 11.10: Reduced-motion audit and slow-frame logging

As the owner,
I want every moment to honour reduced motion and slow frames to be logged,
So that accessibility holds and problems are visible without removing effects.

Covers: UX-DR21, UX-DR25 (P-8 reinterpreted), AD-21. Prio: P2. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the system animation scale is 0
**When** each screen and moment S1-S16 is exercised
**Then** the documented fallback applies and the audit table (moment, fallback, verified) is stored in `docs/phone-motion-audit.md`.

**Given** two consecutive frames exceed 2x budget
**When** detected
**Then** a `RovstiPerf` warning is logged with the scenario name; no visual effect, glass or aura is degraded or removed (AD-21 overrides UX P-8); only the S15 theme snap rule above applies.

---

## Epic 12: Performance Pass

Performance is achieved by optimising code, not by cutting effects (AD-21). Order of measures: idle discipline, allocation discipline, virtualised lists, glass pass cost, kit tests green.

### Story 12.1: Perf feature and RovstiPerf logging

As the owner,
I want frame timing recorded on the phone,
So that claims about smoothness have data.

Covers: AD-22, NFR5. Prio: P0 (tooling). Verify: CARGO, ANDROID-CHECK, DEVICE.

**Acceptance Criteria:**

**Given** Cargo feature `perf` on `rovsti_phone` (off in release)
**When** enabled in debug/perf builds
**Then** `rovsti_ui::perf` calls `Window::set_frame_diagnostics_enabled(true)` and pushes `build_time`, `platform_draw_time` and `view_cache` into `FrameStats` each frame with no allocation.

**Given** leaving a scenario
**When** the report is emitted
**Then** logcat tag `RovstiPerf` prints p50/p95/p99/max, over-budget and over-2x counts, refresh rate and thermal state (`App::on_thermal_state_change`), with no names or numbers logged.

### Story 12.2: Scripted perf scenarios

As the owner,
I want repeatable scenarios,
So that before/after comparisons are fair.

Covers: AD-22, UX-DR25. Prio: P0 (tooling). Verify: DEVICE.

**Acceptance Criteria:**

**Given** a debug-only scenario runner
**When** S-tab (20 ping-pongs), S-scroll-recents (500 rows down/up), S-keypad (6 keys/s), S-incall (controls + timer) and S-incoming (entrance + rings) run
**Then** each logs one `RovstiPerf` report and `adb shell dumpsys SurfaceFlinger --latency` on the `GpuiView` layer is captured by a documented script `scripts/perf-run.sh`.

**Given** the baseline run
**When** completed
**Then** the numbers are saved in `docs/perf/baseline.md` for later comparison.

### Story 12.3: Idle discipline and view caching

As the owner,
I want zero frames at idle,
So that the battery and heat stay low.

Covers: NFR5, AD-21(1), UX-DR25 P-7. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** no spring moving and no call
**When** idle for 30 s on each tab
**Then** `RovstiPerf` reports 0 frames requested and no timers other than the call timer exist.

**Given** static subtrees (Tab Bar, list rows, checklist)
**When** `cached` views and `view_cache` hit rate are checked
**Then** hit rate is logged per scenario and `cx.notify()` is not called per tick for non-visual state (audited list in the completion notes).

### Story 12.4: Allocation discipline

As the owner,
I want no allocation in per-frame paths,
So that frame time stays steady.

Covers: NFR5, AD-21(2). Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** the per-frame paths (spring ticks, key rendering, list row builders, timer text, aura)
**When** audited
**Then** no `String`/`Vec`/`SharedString` is created per frame (pre-interned labels, reused buffers, fixed-size spring arrays), with a short allocation-count test using a counting allocator in a unit test for spring/row-building helpers.

**Given** S-scroll-recents is re-run
**When** compared to baseline
**Then** `build_time` p95 is equal or lower and recorded.

### Story 12.5: Virtualised lists and photo cache audit

As the owner,
I want lists that scroll at full rate,
So that 500 rows feel as light as 50.

Covers: NFR4, NFR5, AD-21(3), UX-DR25 P-3, P-9. Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** Recents and Contacts with 500 items
**When** scrolled
**Then** only visible rows plus one screen each way are built, heights are fixed (58/58/28), refreshes keep the scroll offset, and Android StrictMode in debug shows no disk/contentprovider access on the main thread.

**Given** the LRU photo cache
**When** stress-tested
**Then** capacity stays 64 and decode runs off-thread (unit test).

### Story 12.6: Glass pass cost

As the owner,
I want glass cheap to draw without looking different,
So that all the glass can stay.

Covers: NFR12, AD-21(4)(5). Prio: P0. Verify: CARGO, DEVICE.

**Acceptance Criteria:**

**Given** `liquid_glass_kit`
**When** optimised
**Then** blur passes are skipped when `blur == 0`, backdrop copies stay scissored to bounds plus 2x radius, adjacent same-spec glass (12 keys, in-call control row) can be merged into one backdrop effect with an instance list, a cheaper non-pressed shader variant exists, and pipelines pre-compile at startup.

**Given** each change
**When** tested
**Then** `shader_validates` and `glass_snapshot` pass pixel-equivalent and the change is kept only if S-keypad or S-incall `build_time`/pacing improves on the phone (before/after in `docs/perf/`).

**Given** the work
**When** reviewed
**Then** no visual effect, refraction, dispersion or glass surface is removed; refractive-surface counts are not enforced.

### Story 12.7: Measured run on the phone

As the owner,
I want a final measurement against targets,
So that perf is signed off with evidence.

Covers: NFR2, NFR3, NFR5, NFR9, AD-21 targets, UX-DR25. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** a perf-realistic APK (`gpuiforge build android --abi arm64-v8a --rust-profile release`, debug-signed)
**When** all five scenarios and the incoming warm/cold runs are executed
**Then** `docs/perf/final.md` lists p95 within one display frame at the panel Hz, under 1% frames over budget, none over 2x during tab switch/scroll/key/call flows, CPU frame work (`build_time`) under 4 ms, cold start to first interactive frame under 1.2 s, idle 0 frames, warm incoming under 1.5 s and cold under 3 s.

**Given** any target missed
**When** reported
**Then** it is recorded as a blocker with the next measure from the AD-21 order; it is never closed by a visual trade-off.

---

## Epic 13: Signed Release and Acceptance

### Story 13.1: Signing configuration

As the owner,
I want release signing set up without secrets in the repo,
So that I can install a signed build.

Covers: AD-20, NFR1. Prio: P1 (release). Verify: BUILD.

**Acceptance Criteria:**

**Given** `scripts/make-keystore.sh` has produced `~/.config/rovsti/rovsti-release.jks` and `signing.env`
**When** `gpuiforge.json` gets the `signing` object (absolute keystore path, alias `rovsti`, `store-password-env`, `key-password-env`)
**Then** no password appears in any tracked file, `*.jks` and `signing.env` are git-ignored, and `git status` shows no secrets.

**Given** the build scripts
**When** run
**Then** passwords are never printed, logged or passed as arguments.

### Story 13.2: Signed release APK

As the owner,
I want a signed release APK,
So that I can run the final build and update it later.

Covers: FR39, AD-20, SM-3. Prio: P1. Verify: BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `source ~/.config/rovsti/signing.env`
**When** `gpuiforge build android --release` runs (feature `perf` off, R8/minify off, `proguard-rovsti.pro` retained)
**Then** the APK is signed (`apksigner verify --print-certs` succeeds) and `scripts/check-apk.sh` passes (no `INTERNET`, both `Java_*` symbols).

**Given** the release APK
**When** installed over the debug build is refused for a different key
**Then** the documented step is `adb uninstall dev.rovsti.phone` then install, and the role/Setup state is re-verified.

### Story 13.3: Final workspace checks

As the owner,
I want every check green,
So that the build is trustworthy.

Covers: NFR8, SM-4. Prio: P0 (gate). Verify: CARGO, ANDROID-CHECK, BUILD.

**Acceptance Criteria:**

**Given** the workspace
**When** `cargo fmt --all && cargo clippy --workspace --all-targets -- -D clippy::unwrap_used -D clippy::disallowed_methods && cargo test --workspace` and `cargo check -p rovsti_android --target aarch64-linux-android` run
**Then** all pass for Phone-owned crates; the CLAUDE.md checks list is updated to include the extra clippy flags only after the Messages/Stops migration to `rovsti_ui::theme::colors` is done (flagged gap).

**Given** grep audits
**When** run
**Then** `rg "rovsti_android" apps/phone/src` matches only `backend.rs`, and no `INTERNET`, analytics or network crate appears in phone dependencies.

### Story 13.4: Owner acceptance checklist

As the owner,
I want a one-page test script and a 7-day log,
So that I can accept the app with evidence.

Covers: SM-1, SM-2, SM-3, NFR10, UJ-1..UJ-6. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** `docs/phone-acceptance.md`
**When** written
**Then** it lists device tests for UJ-1 to UJ-6 (setup, call Favorite, incoming locked, call back missed, search and star, DTMF), FR-1/2/11/24 first-session checks, unlocked heads-up, silent mode, cold-start incoming, GPUI-kill resilience, font scale 1.3x on In-Call, TalkBack pass on Keypad/In-Call/Incoming, and a 7-day table (date, calls made/received, any fallback to stock dialer, any missed-by-app call).

**Given** a failed item
**When** logged
**Then** the table has columns for FR/story id so follow-ups map to stories.

---

## Flagged Gaps and Decisions

1. **AD-13 lint ban vs Messages/Stops.** The workspace-wide `Theme::colors` ban requires migrating callers in `apps/messages` and `apps/stops`, which this run must not edit. Story 4.1 enables the ban for `rovsti_ui` and `apps/phone` only; 13.3 adds the extra clippy flags to CLAUDE.md only after Messages/Stops migrate. Follow-up needed in their own planning.
2. **PRD vs AD-20 APK path.** FR-39 names `target/gpuiforge/android/...`; after eject the artifact is `apps/phone/platforms/android/app/build/outputs/apk/debug/app-debug.apk`. Handled in 1.2 and `check-apk.sh`; the PRD text is stale.
3. **UX P-2 and P-8 vs AD-21.** The 4-refractive-surface limit, "flat glass at rest" and automatic flat-glass degradation are advisory/overridden: no story enforces or implements a visual degrade (see 11.10 and 12.6). UX-DR25 is satisfied for P-1, P-3..P-7, P-9, P-10.
4. **Snapshot `contact_id`.** Hue pairs (S6, UX-DR23) need a stable key; AD-4's field list has no `contact_id`. Story 2.1 adds it as an optional field (additive, v1) and 11.5 falls back to the normalized number.
5. **Device-only acceptance.** NFR-2, NFR-3, FR-21 to FR-24, Xiaomi settings, proximity, HyperOS lock-screen behaviour and all performance targets can only be accepted on the owner's phone; code completion is gated by CARGO/BUILD, acceptance by 13.4.
6. **Call waiting (FR-20) depends on the carrier;** 9.2 may be untestable on some plans.
7. **UX-DR20 / NFR-10 accessibility** is carried as acceptance lines inside screen stories and audited in 13.4; there is no standalone accessibility story. If the owner wants one, add a "TalkBack and font-scale pass" story after Epic 9.
8. **Missed-call notification (PRD Q4)** and Xiaomi confirmation reset policy (Q5) stay deferred; no story posts its own missed-call notification.
9. **Items intentionally absent:** dual-SIM picker, blocklist, swap/merge/conference, T9, speed dial, voicemail, Bluetooth picker, contact editing/creation, call-log deletion, tab-bar puck drag, "New call" pill, alphabet rail.
10. **S2 Kotlin is kept, not thrown away:** 1.6 to 1.8 produce the base for `CallRegistry`, `CallNotifications` and `CallSurface`; 3.2, 4.9 and 5.1 harden them.

## Final Validation Summary

- FR coverage: 40 of 40 FRs mapped (see FR Coverage Map); every FR appears in at least one story's `Covers`.
- Starter/eject check: Epic 1 Story 1.2 is the eject and hello-APK story (the architecture's "starter" equivalent).
- Entities created when needed: wire DTOs in 2.1 are shared contracts; provider pulls are created in 3.4 only for data the lists use.
- Forward dependencies: none; each story uses only earlier stories (6.3 and 8.8 explicitly handle the Contact Sheet arriving later).
- File churn: Epics 4/5 both touch `RovstiInCallService`/`CallRegistry`; the split is justified by the risk boundary (outgoing/in-call vs lock-screen incoming) and the S2 spike feedback loop.
- Story count: 89 stories in 13 epics.
