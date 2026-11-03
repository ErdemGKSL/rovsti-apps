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
  - apps/phone/_bmad-output/planning-artifacts/gap-analysis-2026-10-28.md
mode: headless (no interactive confirmations; owner decisions taken from the task brief)
appendedV02: 2026-10-28 (Epics 14-22 appended in APPEND mode from PRD v0.2 FR41-FR73, UX S17-S36, spine AD-23..AD-36; Epics 1-13 untouched)
---

# Rovsti Phone - Epic Breakdown

## Overview

This document decomposes the Rovsti Phone PRD (40 FRs, 13 NFRs), the UX contract (DESIGN.md and EXPERIENCE.md, 16 signature moments S1-S16, 9 screens) and the Architecture Spine (AD-1 to AD-22, spikes S1/S2) into implementable stories for single dev-agent runs.

**Goal:** a working, installable debug APK that the owner sideloads on a Xiaomi phone and uses as the default dialer, then a signed release. Epics are ordered by the architecture's de-risking order: spikes first (Epic 1), core logic with tests (2), Kotlin/Rust seam (3), P0 screens (4-6), P1 (7-9), P2 (10), signature moments (11), performance pass (12), signed release (13).

**v0.2 append (2026-10-28).** The owner tested v0.1 and asked for what a modern dialer has (audio route picker, conference shortcuts, and more). The PRD gained FR-41 to FR-73 and NFR-14 to NFR-18, the UX gained 20 signature moments (S17-S36) and acceptance rows P-11 to P-23, and the architecture gained AD-23 to AD-36 and spikes S3 to S14. Epics 14 to 22 below add 33 FRs as stories in the architecture's v0.2 de-risking order: Foundation F0 and audio routes first, then multi-call and conference, Settings and SIM, blocking, incoming controls, the P2 epics, and finally polish, review and the v0.2 signed release. See "v0.2 shared acceptance lines (UIQ) and build rules" before Epic 14.

**Conventions used in every story**

- `Covers:` FR/NFR/AD/S/UX-DR ids. `Prio:` P0/P1/P2 from PRD section 6.1 (spikes and infrastructure carry the priority of what they unblock).
- `Verify:` one or more of `CARGO` (cargo fmt/clippy/test, pure Rust), `ANDROID-CHECK` (`cargo check -p rovsti_android --target aarch64-linux-android`), `BUILD` (`gpuiforge build android` and APK inspection), `DEVICE` (owner tests on the real Xiaomi phone; there may be no emulator), `EMU` (optional emulator with `adb emu gsm call`; DEVICE is the fallback). A story whose Verify contains DEVICE is complete in code when CARGO/BUILD pass, and is accepted after the owner's device check; the dev agent must leave a short device test script in the story's completion notes.
- Stories depend only on earlier stories. Domain logic goes to `rovsti_core` with table-driven tests (AD-14). Only `apps/phone/src/backend.rs` calls `rovsti_android` (AD-15). No `unwrap` (workspace lint). No deferred scope (dual-SIM, blocklist, conference/swap/merge, T9, speed dial, voicemail, Bluetooth picker, contact editing/creation, delete log).
- Always-on checks before finishing any story: `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace` and `cargo check -p rovsti_android --target aarch64-linux-android`.
- Owner decisions applied: teal accent via `rovsti_ui::theme::set_accent`; Xiaomi "Confirm" gated on opening the settings page first; unknown/private numbers in Recents are not callable; tab-bar puck drag, Recents "New call" pill and alphabet rail are out of MVP; performance is gained by code optimisation, never by cutting visual effects (AD-21); DESIGN.md "max 4 refractive surfaces" and "flat glass at rest" are advisory only.
- v0.2 (Epics 14-22) supersedes the line above that lists deferred scope: those features are now in scope. The same story format, Verify tags and always-on checks apply, plus the UIQ lines, the Spike rule and the Epic release rule defined before Epic 14. Story ids with a letter (14.0a to 14.0f) are Foundation F0 part 1 and sort before 14.1.

## Requirements Inventory

### Functional Requirements

FR1: Role eligibility - the APK is eligible for ROLE_DIALER (InCallService with UI metadata and bind permission; activity handling ACTION_DIAL with and without tel:).
FR2: Request the role from the Setup Checklist; if the dialog cannot show, show the Settings path and a button to Default apps.
FR3: Handle dial intents - ACTION_DIAL with tel: opens Keypad pre-filled without calling; without data opens Keypad empty.
FR4: Preview mode without the role - visible banner; calls still go through the system path.
FR5: Checklist contents in order (Default Phone App; Phone and call log; Contacts; Notifications; Full-screen notifications; on Xiaomi only Autostart, Battery saver, Show on lock screen/pop-up, Background pop-ups), each with state and reason.
FR6: Act on a Setup Item (runtime requests, Xiaomi deep links with fallback to app details, AOSP battery dialog).
FR7: Re-check readable items on launch and resume; collapse to "Setup complete" when all done.
FR8: Confirm Xiaomi Settings; persisted locally; cleared on app version change.
FR9: Enter a number (0-9 * #, long-press 0 for +, paste normalisation, delete, holding delete erases right to left with acceleration).
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

### v0.2 Functional Requirements (appended 2026-10-28, PRD section 4.11)

FR41: Audio route list - one row per Android audio endpoint (earpiece, speaker, wired headset, each Bluetooth device by reported name) with type icon and check on the current route; routing is by endpoint identifier, not type; live updates within 500 ms; hearing aids appear as Bluetooth endpoints.
FR42: Audio control behaviour - with only earpiece and speaker one tap toggles; with any other endpoint one tap opens the list; long-press always opens it; fixed-size slot with icon and ellipsised route label so the In-Call layout never moves.
FR43: Bluetooth and hearing-aid shortcuts - route list footer opens "Bluetooth settings" and "Hearing aids" with fallback to Settings; names row asks for `BLUETOOTH_CONNECT` once if needed; Settings shows hearing-aid compatibility (informational).
FR44: Add call - fixed-slot control opens Keypad/Favorites/Contacts over the Call without ending it; placing uses the one call path (FR-11) and the network holds the first call; disabled, not hidden, when Android says no.
FR45: Two calls and swap - active and held Calls with names and timers; tap the held Call or Swap to switch; conference swap action when reported, else hold/unhold; Hold and Swap share one slot.
FR46: Merge - enabled only when Android reports the merge capability; shares the fixed-slot rule; carrier-unsupported leaves it disabled with the reason on long-press; shows conference state afterwards.
FR47: Conference participants - participant list with names and one conference timer; per-row End and Split only when Android reports the capability; carrier-hidden participants show "Conference" with a count and whole-conference End.
FR48: Waiting Call options - Answer and hold, End and answer, Decline, Decline with message; supersedes FR-20's "Answer ends the first Call"; Decline leaves the first Call Active.
FR49: Settings screen - header gear opens one glass page (no fifth tab); sections Calls, Sounds and haptics, Display, SIM, Quick replies, Blocked numbers, Data, System shortcuts; every setting local, immediate, with a default that reproduces v0.1; system shortcuts fall back to app details.
FR50: SIM choice - on two or more call-capable SIMs a glass chooser appears when Android asks and on long-press of any call action; Settings shows the default and opens the system page; invisible on one SIM.
FR51: SIM label - SIM chip (label and slot colour) in a fixed slot on the In-Call Screen and each Recents row with two or more SIMs; no chip for unknown accounts.
FR52: Call quality indicators - HD and Wi-Fi chips live on the In-Call Screen from call properties; HD, Wi-Fi and VoLTE from log features in Call details.
FR53: Block and unblock - from Recents row menu, number sheet, Contact Sheet, Call details and the post-call row on the Incoming Call Screen; writes the system blocked-numbers store; Undo for 5 s; disabled with explanation when the platform refuses.
FR54: Blocked numbers list - Settings page listing blocked numbers (virtualised) with Unblock and an Add number field using FR-9 rules.
FR55: Blocked calls - blocked entries stay out of Recents and the Missed Call Badge; a "Blocked calls" view shows them with time and reason where available (refines FR-26).
FR56: Reject with message - "Message" on the Incoming Call Screen, Waiting card and heads-up notification lists quick replies; choosing one rejects the Call with that text; result is never silent ("Sent" or "Opened Messages"); no SMS permission unless proven required.
FR57: Quick replies - up to 6 editable, reorderable, resettable replies stored locally; an empty list hides Message.
FR58: Silence the ringer - Silence control in a fixed slot on the Incoming Call Screen, Call stays answerable; optional flip-to-silence with the sensor active only while ringing.
FR59: Answer and decline gestures - slide right answers, left declines, up opens replies; thumb tracks the finger with spring release and haptic ticks; buttons remain.
FR60: Create and edit contacts - "Add to contacts" in Recents, number sheet, Call details and Keypad opens the system editor prefilled; lists refresh within 2 s on return.
FR61: Delete a contact - Contact Sheet Delete with a confirmation naming the contact; removes it from the provider, Contacts and Favorites immediately.
FR62: In-app contact editor (stretch, P3) - edit name and numbers for single-raw-contact contacts; may be cut without affecting FR-60/61.
FR63: Contact quick actions - Message, Copy number, Share, Block, Call details, Favorite, Ringtone for this contact; long-press on any shown number copies it.
FR64: Favorites order and frequent - drag to reorder Favorites with spring displacement, persisted locally keyed by contact; optional "Frequent" row from the call log with a hiding setting.
FR65: T9 search on the Keypad - digits match contact names (word start, diacritic folded) as well as numbers; results within one frame, off the UI thread; fixed-height results area.
FR66: Speed dial - digits 2-9 assignable from Settings and the Contact Sheet; long-press on an assigned key on an empty Keypad calls it; digit 1 is voicemail; included in the backup.
FR67: Voicemail shortcut - long-press 1 calls voicemail of the default or chosen SIM; none configured says so and opens call settings; voicemail icon in Recents; badge only if a real signal exists.
FR68: Emergency numbers - emergency numbers recognised and never delayed (no animation hold, chooser, confirmation, setup or block check); Add call, Merge and Block disabled in an emergency Call; Emergency information shortcut if the page resolves.
FR69: Call details - per-number page from the Recents info control and Contact Sheet: header, totals, virtualised history with SIM and quality chips, actions Call, Message, Copy, Share, Block, Add to contacts, Delete history.
FR70: Delete call history - one entry, a grouped row, all entries of a number, all history (double confirmation); disappears from Recents and the badge immediately.
FR71: Sounds, haptics and call behaviour - keypad tones, haptics (off, light, normal), vibrate on connect and on end, proximity toggle, flip to silence, shortcut to system sound settings.
FR72: Display - Sort by (first or last name) and Name format (first last or last, first) applied to Contacts, Favorites, Recents and the In-Call Screen immediately.
FR73: Backup and restore - export one JSON file (settings, Favorites order, speed dial, quick replies) via the system file picker; import after a preview; contacts, call log and blocked numbers excluded.

### v0.2 Non-Functional Requirements

NFR14: Layout stability - the main layout never shifts; In-Call controls occupy fixed slots and are disabled, never removed; SIM chips, quality chips, Audio label, Silence control and T9 area have reserved space; sheets and lists overlay or scroll.
NFR15: Touch feedback - every new control shows press feedback within one frame of the raw touch and gives a haptic tick where the existing equivalent does (honouring the haptics setting); gestures track the finger and release with interruptible springs.
NFR16: Fail-soft platform use - every new Android API call is capability-gated and degrades visibly (disabled control with reason, or system-page fallback); no feature may crash, block a Call or leave the In-Call Screen without End; `[?]` facts are proven on the owner's phone in the first story that needs them.
NFR17: Local-only data - new persistent data lives in the no-backup directory or Android providers; no `INTERNET`, no network code; new permissions justified in the story; SMS permission only if the reject-text spike proves it necessary and the owner accepts.
NFR18: Performance of new surfaces - route changes, conference updates and T9 results apply within one frame; T9 over 2,000 contacts within 8 ms off the UI thread; Blocked, Call details, conference and route lists virtualised and loaded within 1 s for 500 rows; speed by code optimisation, never by cutting effects (AD-21).

### v0.2 Architecture Requirements (ARCHITECTURE-SPINE.md, amended 2026-10-28)

- AD-23 audio endpoints are an id-addressed list on the wire (`audio.endpoints[{id,type,name?,selected,hint?}]`, `route`/`routes` derived, `callRouteEndpoint(id)`, legacy `CallAudioState` fallback, ghost entries, hearing-aid hint best effort); AD-23 also defines the single coalesced `scheduleRebuild()` (12 ms) used by four callbacks only.
- AD-24 multi-call model: Kotlin `Focus.of` selects foreground/background/waiting/ended, Rust renders `CallScene` (`Single|Two|Conference` + `waiting` + `ended`) with a closed `DisabledReason`; `caps`/`props` bitmasks (constants in `rovsti_core::wire` and `WireJson.kt`); lock-screen rule for Add call (`requestDismissKeyguard`).
- AD-25 one `PendingOp` machine (`AnswerReplacing`, `Swap`, `Merge`) with per-op deadlines, `pending`/`pending_failed` on the wire, `swap()`, `merge()`, `split(child)`, `answerHolding(id)`, `endAll()`.
- AD-26 dual SIM: `account_key` equals the call log's component+id pair, `phone_accounts()`, `place_call(raw, account_key?)`, `SimInfo::visible()` (two or more), registry safety net for `STATE_SELECT_PHONE_ACCOUNT` (`uiAttached`).
- AD-27 blocking: system blocklist is the only store (`Blocklist.kt`, `BlockSet`, `blocked_changed`, `BLOCK_GUARD` default true until S6, emergency numbers never blockable, Recents pull excludes type 6, `pullBlockedCalls`).
- AD-28 reject with message through Telecom, no SMS permission; `callRejectWithMessage` returns Int (0 stale, 1 sent, 2 opened, 3 rejected_only); `Caps.REJECT_PATH` default `HANDOFF` until S7 passes.
- AD-29 silence (`silenceRinger`, `ringer_silenced`, `onSilenceRinger`), `FlipDetector` (sensor only while ringing), Kotlin keypad tones, gestures Rust-only, haptic filter `Haptics{Off,Light,Normal}`, Kotlin vibrate on connect/end.
- AD-30 Prefs v2: typed `PrefsV2`, `PrefsV1 -> PrefsV2` upgrade chain with fixtures and `.bak` copies, single `PrefsWriter`, Kotlin settings mirror (`settingsSync`), `BackupFile`/`ImportPreview`, SAF `exportDocument`/`importDocument`, one-shot Kotlin mailbox for `document_result`, `ringtone_result`, `ui_request`.
- AD-31 provider writes: the closed list (`markMissedCallsRead`, `setStarred`, `Blocklist.block/unblock`, `CallLogWrite.delete/deleteAll/restore`, `ContactsWrite.delete/setRingtone`) with the `{v,ok,error?,data?}` envelope; `pullCallHistory`; intents for create/add contact, Message, Copy, Share.
- AD-32 names (`names::display`, `names::sort_key`), Favorites order by `lookup_key`, `call_log::frequent`.
- AD-33 keypad intelligence: prebuilt T9 index with `T9Scratch`, speed dial stores number plus lookup key, voicemail via `voicemail_info`, `VoicemailReceiver` disabled by default, `dial::is_emergency` with `emergency_numbers()`.
- AD-34 manifest/permission additions: `VIBRATE`, `<queries>` block, disabled `VoicemailReceiver`; `WRITE_CALL_LOG`/`WRITE_CONTACTS` requested at first use; `SEND_SMS`, `MODIFY_PHONE_STATE`, `INTERNET` absent (`check-apk.sh` fails on `INTERNET` or `SEND_SMS`); every new `rovsti_android` function returns `Error::Unsupported` on desktop with a test per module.
- AD-35 performance of v0.2 surfaces: fixed-slot constants in `incall.rs`, `SceneDiff` driven `cx.notify()`, virtualised fixed-row lists, no per-frame allocation, perf scenarios S-route, S-twocall, S-reply, S-t9, S-details, no adaptive degradation.
- AD-36 spikes, Diagnostics page and fallback constants (`Caps.REJECT_PATH`, `BLOCK_GUARD`, `VOICEMAIL_RECEIVER`, `LEGACY_AUDIO_FORCED`); every spike ships its safe default and is confirmed from the owner's pasted report in a follow-up commit.
- Amended: AD-2 (third notification action "Message"), AD-3 (v0.2 surfaces under call-only mode, listener not used), AD-4 (snapshot fields), AD-6 (new commands), AD-9 (write list, Recents excludes blocked), AD-10 (additive changes keep `v: 1`), AD-11 (`place_call(raw, account_key?)`), AD-18 (`VIBRATE`, queries, receiver).
- Spikes S3-S14 (pass criteria and fallbacks in the spine): S3 audio names/ids (14.1), S4 swap/merge/conference caps (15.1), S5 account keys (16.3), S6 system blocklist (17.1), S7 reject text path (18.1), S8 silence/volume/flip (18.4), S9 voicemail (20.3), S10 emergency (20.4), S11 ringtone/delete/name order (19.2), S12 call-log delete/restore (21.2), S13 SAF round trip (16.6), S14 TalkBack/system facts/vibration (18.5).

### v0.2 UX Design Requirements (DESIGN.md and EXPERIENCE.md v0.2)

UX-DR26: Touch model - every new control paints press feedback on the raw finger-down (0.97 rows/pills, 0.94 tiles/circles, lens glow, within one frame) and acts on finger-up after the tap guard (10 px slop, no scroll); a finger sliding off releases without acting; destructive/irreversible actions (Block, Delete, Decline with message, End and answer) act on finger-up only; keys and in-call toggles stay on finger-down; haptics follow the Haptics setting.
UX-DR27: Layout stability - In-Call 3x2 grid (Mute, Keypad, Audio / Add call, Hold or Swap, Merge) and End never move across one call, two calls, conference, Waiting card, popover, Add call sheet (P-13); reserved slots (22 px chip row, 96 px Audio caption, Held-card slot, Waiting overlay, conference panel, T9 title slot, Message/Silence slots, 5-tile action strip).
UX-DR28: Flat variants - `control-flat`/`sheet-flat` for held card, conference panel, Waiting card, Notice pill, action tiles, segmented tracks, secondary incoming controls; scaled surfaces paint flat until within 2% of rest, then swap to the refractive pass; refractive counts reported, not gated (AD-21).
UX-DR29: v0.2 components - route popover, Notice pill, action tile, toggle, segmented control, chip (SIM and quality), in-call chip row, Held card, Conference panel, Waiting card, incoming secondary controls, reply sheet, SIM chooser, Settings page and gear, T9 strip and results sheet, speed-dial key badge, Emergency Call button, details header and history, blocked list, hold-to-confirm pill, context (row) menu.
UX-DR30: Signature moments S17-S36 (Route bloom, Device arrival, Hold-away, Swap crossing, Merge weave, Participant eject, Waiting card drop, Gear turn and page push, Toggle snap, SIM chip flight, Block sweep, Notice pill, Incoming slide, Silence hush, Reply sent, Results morph, Speed-dial charge, Reorder lift, Details arrival, Hold to delete) with reduced-motion fallbacks, existing spring tokens only, at most two animating per screen.
UX-DR31: v0.2 state patterns and strings (capability unsupported, platform refuses, pending, single SIM, permission variants, empty quick replies, no voicemail number, emergency, blocked, backup invalid, desktop preview) with the exact copy table.
UX-DR32: v0.2 accessibility - TalkBack custom actions replace every gesture/drag, announcements (route, conference, waiting, merge, silenced, notices), focus return, 10 s Undo with TalkBack, font scale 1.3x (Audio caption one line, rows wrap, strip tiles grow to 76), never colour alone.
UX-DR33: v0.2 acceptance P-11 to P-23 (route popover latency, press/release, grid stability, refractive counts reported, list/T9 budgets, incoming drag, settings persistence/round trip, single-SIM invisibility, block/undo, reduced motion/transparency/font scale/TalkBack, emergency, idle cost, destructive actions on finger-up).
UX-DR34: Navigation model and Back order - Settings is a pushed page with at most one sub-page, Call details a pushed page, overlays (popover, context menu, Notice pill) above any screen, sheets one level and never over a sheet; Back order: Notice ignored, popover/menu, sheet, search focus, In-Call Keypad, top page, tabs do nothing.

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

v0.2 additions (FR41-FR73; stories are listed in build order, which is numeric order):

FR41: Epic 14 (14.0a, 14.0b, 14.0d, 14.1, 14.2, 14.6, 14.7); decision logic in `rovsti_core::audio` (14.0d)
FR42: Epic 14 (14.0d, 14.5, 14.6)
FR43: Epic 14 (14.4 intents, 14.7 names row, 14.8 shortcuts and hearing-aid hint); Settings HAC value 16.10
FR44: Epic 15 (15.1, 15.4, 15.5)
FR45: Epic 15 (15.1, 15.2, 15.4, 15.6)
FR46: Epic 15 (15.1, 15.2, 15.7)
FR47: Epic 15 (15.1, 15.2, 15.7)
FR48: Epic 15 (15.2, 15.8); Message pill wired in 18.7
FR49: Epic 16 (16.1, 16.2, 16.7, 16.10); store in 14.11, 14.12; sections added by 17.5, 18.3, 19.6, 20.2, 21.3
FR50: Epic 16 (16.3, 16.4)
FR51: Epic 16 (16.5)
FR52: Epic 21 (21.4); data path in 21.1; `props` in 14.0a, 15.1
FR53: Epic 17 (17.1, 17.3, 17.4); surfaces in 19.3 (strip), 21.1 (Details)
FR54: Epic 17 (17.5)
FR55: Epic 17 (17.2, 17.6)
FR56: Epic 18 (18.1, 18.2, 18.7)
FR57: Epic 18 (18.3); store in 14.11
FR58: Epic 18 (18.4, 18.6); toggle in 16.7
FR59: Epic 18 (18.5)
FR60: Epic 19 (19.1); intent in 14.4; Details action in 21.1
FR61: Epic 19 (19.2)
FR62: Epic 19 (19.7, P3 stretch, flagged: needs AD-37, skippable)
FR63: Epic 19 (19.3, 19.4); intents in 14.4; row menu entries in 17.3
FR64: Epic 19 (19.5, 19.6); logic in 14.14, store in 14.11
FR65: Epic 20 (20.1); index in 14.14
FR66: Epic 20 (20.2); store in 14.11
FR67: Epic 20 (20.3)
FR68: Epic 20 (20.4); logic in 14.14; In-Call dims in 15.1, 15.4
FR69: Epic 21 (21.1)
FR70: Epic 21 (21.2, 21.3)
FR71: Epic 16 (16.7, 16.8)
FR72: Epic 16 (16.7, 16.9); logic in 14.13
FR73: Epic 16 (16.6); logic in 14.11

v0.2 NFR coverage: NFR14 acceptance lines in 15.4, 15.5, 15.6, 15.7, 15.8, 16.5, 18.2, 20.1, 20.4 plus UIQ-2 in every UI story and P-13 overlay test in 15.4; NFR15 UIQ-1 in every UI story, 14.3, 16.7, 16.8, 22.4; NFR16 UIQ-5 in every Android story and the spike stories 14.1, 15.1, 16.3, 16.6, 17.1, 18.1, 18.4, 18.5, 19.2, 20.3, 20.4, 21.2, reconciled in 22.6; NFR17 14.11, 14.12, 14.15, 16.6, 18.1, 22.7; NFR18 14.0d, 14.14, 15.7, 17.5, 20.1, 21.1, 22.1, 22.2.

v0.2 architecture coverage: AD-23 14.0b, 14.0e, 14.1; AD-24 14.10, 15.1, 15.5; AD-25 15.2; AD-26 16.3, 16.4, 16.5; AD-27 17.1, 17.2; AD-28 18.1; AD-29 18.4, 18.5, 16.8; AD-30 14.11, 14.12, 16.2, 16.6; AD-31 14.4, 14.9, 19.2, 21.1, 21.2; AD-32 14.13, 14.14, 16.9, 19.5; AD-33 14.14, 20.1 to 20.4; AD-34 14.15; AD-35 22.1, 22.2, 22.3; AD-36 14.0c, 14.0f and every spike story. Spikes S3 to S14 map to 14.1, 15.1, 16.3, 17.1, 18.1, 18.4, 20.3, 20.4, 19.2, 21.2, 16.6, 18.5 (S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14).

v0.2 signature-moment coverage: S17 14.6; S18 14.7; S19 15.6; S20 15.6; S21 15.7; S22 15.7; S23 15.8; S24 16.1; S25 16.7; S26 16.4; S27 17.3; S28 14.3; S29 18.5; S30 18.6; S31 18.2; S32 20.1; S33 20.2; S34 19.5; S35 21.1; S36 21.3; all audited in 22.4.

v0.2 coverage check: all 33 new FRs (FR41-FR73) are mapped; all 5 new NFRs mapped; all 20 new moments (S17-S36) mapped to a building story. Flagged gap: FR62 (P3) is mapped to 19.7 but is intentionally skippable and needs a new AD-37 first (see Flagged Gaps item 11).

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

### Epic 14: Audio routes and devices (with Foundation F0)
The owner can see every audio route Android offers during a call, switch to exactly one by identifier, and watch the list follow devices live; Foundation F0 delivers the wire field table, snapshot schema v0.2 with golden fixtures, the Diagnostics page, Prefs v2 migration, the new `rovsti_core` modules and the manifest additions.
**FRs covered:** FR41, FR42, FR43 (NFR14, NFR15, NFR16, NFR17, NFR18; AD-23, AD-34, AD-35, AD-36 plus F0 groundwork for AD-24 to AD-33; spike S3; moments S17, S18, S28)

### Epic 15: Multi-call and conference
The owner can add a call, swap, merge, manage conference participants where the carrier allows it, and handle a waiting call four ways, on a fixed-slot In-Call Screen.
**FRs covered:** FR44, FR45, FR46, FR47, FR48 (NFR14, NFR16; AD-24, AD-25; spike S4; moments S19 to S23)

### Epic 16: Settings, SIM and local data
A Settings page behind a gear, dual-SIM choice and chips (invisible on one SIM), sounds/haptics/display settings that really take effect, system shortcuts and a local backup.
**FRs covered:** FR49, FR50, FR51, FR71, FR72, FR73 (NFR15, NFR17; AD-26, AD-29, AD-30; spikes S5, S13, S14 part; moments S24, S25, S26)

### Epic 17: Blocking
Block and unblock through the system blocklist with Undo, a Blocked numbers page and a Blocked calls view, with blocked calls kept out of Recents and the badge.
**FRs covered:** FR53, FR54, FR55 (NFR16; AD-27, AD-31; spike S6; moments S27, S28)

### Epic 18: Incoming call controls
Decline with a message, edit quick replies, silence the ringer and drag the caller card to answer or decline.
**FRs covered:** FR56, FR57, FR58, FR59 (NFR15, NFR16, NFR17; AD-28, AD-29; spikes S7, S8, S14; moments S29, S30, S31)

### Epic 19: Contacts management and quick actions
Add to contacts, delete a contact, quick actions strip, contact ringtone (gated), Favorites reorder and Frequent; an optional P3 in-app editor.
**FRs covered:** FR60, FR61, FR62 (P3), FR63, FR64 (AD-31, AD-32; spike S11; moment S34)

### Epic 20: Keypad intelligence
T9 name search, speed dial, voicemail shortcut and instant emergency numbers.
**FRs covered:** FR65, FR66, FR67, FR68 (NFR18; AD-33; spikes S9, S10; moments S32, S33)

### Epic 21: Call details and history tools
A per-number details page, delete history at four levels, and HD/Wi-Fi/VoLTE indicators.
**FRs covered:** FR52, FR69, FR70 (AD-31; spike S12; moments S35, S36)

### Epic 22: v0.2 Polish, Performance, Review and Signed Release
Perf scenarios and code optimisation for the new surfaces, accessibility and reduced-motion audit of S17-S36, spike reconciliation, final checks, acceptance document, independent code review and the v0.2 signed release.
**FRs covered:** none new (NFR5, NFR10, NFR14 to NFR18; AD-21, AD-22, AD-34, AD-35, AD-36; UX-DR33)

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

**Given** the Xiaomi settings (Autostart, lock-screen pop-up, Background pop-ups) enabled
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
**Then** the digit is in the field on the first frame, a light haptic fires, `0` held 400 ms becomes `+` once, delete removes one character and, held 500 ms, erases right to left (140 ms stepping down to 50 ms per character, each leaving as the S2 ghost) until release or empty.

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

## v0.2 shared acceptance lines (UIQ) and build rules

Epics 14 to 22 extend the finished v0.1 app (Epics 1 to 13) to PRD v0.2. They follow the architecture's v0.2 order (AD-36 de-risking): foundation F0 and audio routes first (the owner's first complaint), then multi-call and conference (second complaint), then Settings and SIM, blocking, incoming controls, then the P2 epics, then polish and release. Numeric order is the build order; no story depends on a later one.

**Shared acceptance lines.** Every story that changes a screen or control carries the lines it names from this block in its own acceptance criteria (the specifics are written out per story, the ids are the traceability handle):

- **UIQ-1 Touch model (NFR-15, UX-DR26).** Press feedback is painted on the raw finger-down within one frame (scale 0.97 rows/pills, 0.94 tiles and circles, lens glow) and the action runs on finger-up after the tap guard (10 px slop, no scroll). A finger that slides off a control releases it without acting. Block, Delete, Decline with message and End and answer act on finger-up only. A haptic tick follows the Haptics setting.
- **UIQ-2 Layout never shifts (NFR-14, UX-DR27).** New content arrives in overlays (popover, sheet, card, Notice pill) or reserved slots. Nothing already on screen moves when a surface opens or a scene changes; the story names the slot constants and the check (overlay screenshots aligned pixel for pixel).
- **UIQ-3 Fluid, fast, by code (NFR-5, NFR-18, AD-21, AD-35, UX-DR28).** Motion uses existing interruptible kit springs only (no new spring tokens), at most two signature moments animate at once per screen, scaled glass paints flat until within 2% of rest. Speed comes from code: labels and `SharedString`s built when data arrives, `SceneDiff`-gated `cx.notify()`, cached static subtrees, fixed-row virtualised lists, no per-frame allocation. Never from removing glass, refraction or moments. If `liquid_glass_kit` is touched, `shader_validates` and `glass_snapshot` stay green.
- **UIQ-4 Accessibility and reduced motion (NFR-10, UX-DR32).** Labels, roles and states per EXPERIENCE.md; TalkBack custom actions for every gesture or drag; font scale 1.3x clips nothing; the moment's stated reduced-motion and reduced-transparency fallback is implemented.
- **UIQ-5 Fail-soft (NFR-16).** Every new Android API call is capability-gated and degrades visibly (disabled control with a reason, or a system-page fallback); it can never crash, block a Call or leave the In-Call Screen without End. On desktop the action reads "Not available on this platform".
- **Spike rule (AD-36).** A spike story builds the shipped default (the safe path), adds its probe to the Diagnostics page, and writes `docs/spikes/s{n}-{name}.md` with the procedure and a PENDING result table. It never waits for the phone. When the owner pastes the Diagnostics report, a follow-up commit records the result and flips the `Caps` constant if the spike passed (task for the Epic 22 reconciliation story, or earlier at the owner's convenience).
- **Epic release rule.** The last story of each epic ends with `bash scripts/build-release.sh` (signed release APK to `~/Downloads/rovsti-phone-release.apk`, never run while another gpuiforge or gradle build is active, no password in any output) and reports the path (Epics 19 and 20 share one release at 20.4 because 19.7 is optional; Epic 22 ends with the v0.2 release). Always-on checks stay as in the v0.1 conventions plus `scripts/check-apk.sh` (fails on `INTERNET` or `SEND_SMS`).
- **Spike ids versus moment ids.** The spine's spikes S3 to S14 and DESIGN's signature moments S1 to S36 share the letter S. In `Covers:` lines a bare S-number after the AD/NFR list is a spike; moments are written `UX-DR30 (S17)`; in prose a moment is written "moment S3" or by name when it could be confused.
- **Kotlin tests.** Pure Kotlin helpers get JVM unit tests; wire fixtures in `crates/rovsti_core/tests/fixtures/` are read by both Rust and Kotlin tests (AD-10).

---

## Epic 14: Audio routes and devices (with Foundation F0)

The owner can see every audio route Android offers during a call (earpiece, speaker, wired headset, each Bluetooth device by name), switch to exactly one, and watch the list follow devices as they connect and disconnect. The same epic lays Foundation F0: the consolidated wire field table, snapshot schema v0.2 with golden fixtures, the Diagnostics page that runs the spikes on the release APK, Prefs v2 migration, the new `rovsti_core` modules and the manifest additions, so every later epic builds on tested plumbing. F0 part 1 (14.0a to 14.0f) is only what audio needs; F0 part 2 (14.9 to 14.16) follows the first release so the owner gets the audio fix first.

### Foundation F0, part 1 (needed by audio)

### Story 14.0a: Consolidated wire field table and bit constants

As a developer agent,
I want one table of every wire field the v0.2 features add, with the capability and property bit constants in code,
So that Kotlin and Rust cannot drift and every later story points at one place.

Covers: AD-10 (amended), AD-23, AD-24, AD-26, AD-27, AD-30, AD-31, NFR-8. Prio: P1 (unblocks all of v0.2). Verify: CARGO.

**Acceptance Criteria:**

**Given** the architecture amendments AD-23 to AD-33
**When** `docs/phone-wire.md` is written
**Then** it lists, per direction (Kotlin to Rust snapshot, provider pulls, one-shot events, Rust to Kotlin commands), every field with name, type, optionality, owner, introducing story id and the default when absent
**And** it marks which fields are additive (keep `v: 1`) and states the rule that a changed or removed field bumps `v`.

**Given** `rovsti_core::wire`
**When** the constants are added
**Then** `caps` (hold=1, support_hold=2, merge=4, swap=8, manage=16, split=32, disconnect_participant=64, respond_via_text=128, mute=256, add_participant=512), `props` (conference=1, hd=2, wifi=4, emergency=8) and log `features` (hd=1, wifi=2, volte=4, video=8) exist as `const` with doc comments
**And** a unit test pins each value and a second test asserts no two bits of one mask overlap.

**Given** the table
**When** a field appears in it but in no later story's Covers
**Then** the dev agent adds it to the nearest owning story's completion notes (no orphan fields).

### Story 14.0b: Snapshot schema v0.2 and golden fixtures

As a developer agent,
I want the additive call-snapshot fields as serde structs with golden JSON,
So that audio, multi-call and SIM work parse real shapes from the first day.

Covers: AD-4 (amended), AD-10, AD-23, AD-24, AD-25, AD-26, NFR-8. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `rovsti_core::wire::CallSnapshot`
**When** extended
**Then** it carries `audio.endpoints[{id,type,name?,selected,hint?}]`, snapshot `background_call_id?`, `waiting_call_id?`, `ended_call_id?`, `can_add_call`, `can_merge`, `can_swap`, `pending?`, `pending_failed?`, `ringer_silenced?`, `silenced_by?`, `selecting_account?`, `accounts?[]`, and per call `parent_id?`, `children[]`, `caps`, `props`, `account_key?`, `display_name_alt?`, `hold_since_ms?`, all `serde(default)`, with `#[serde(other)] Unknown` on the endpoint type, pending and silenced_by enums
**And** `v` stays 1 and a snapshot without any new field still parses to the v0.1 behaviour (a test with an existing v0.1 fixture).

**Given** fixtures `audio_endpoints.json`, `two_calls.json`, `conference.json`, `selecting_account.json` in `crates/rovsti_core/tests/fixtures/`
**When** the golden tests run
**Then** parse, re-serialise and compare equal; an unknown endpoint type and an unknown extra field both parse without dropping the snapshot
**And** a snapshot with `v` higher than known is rejected by the parser helper so the last good snapshot stays displayed.

**Given** `route` and `routes`
**When** `endpoints` is present
**Then** a helper checks they are derived from it (`route` = type of the selected endpoint, `routes` = distinct types) and v0.1 screens keep reading `route` unchanged.

### Story 14.0c: Kotlin wire constants, `Caps`, `CallEventRing` and JVM goldens

As a developer agent,
I want the Kotlin mirror of the wire constants and the fallback switchboard,
So that the Kotlin side builds the same JSON and every spike default is one constant.

Covers: AD-10, AD-24, AD-36, NFR-8, NFR-16. Prio: P1. Verify: BUILD (gradle JVM tests), ANDROID-CHECK.

**Acceptance Criteria:**

**Given** `WireJson.kt` (in the phone Kotlin package) 
**When** added
**Then** it holds the same bit constants as 14.0a, the endpoint JSON builder and a test-visible `buildSnapshot` helper
**And** JVM unit tests (extending the existing `WireFixturesTest.kt` pattern under `app/src/test/java/dev/rovsti/bridge`) read the four 14.0b fixtures and rebuild matching JSON from structured inputs (order-insensitive equality).

**Given** `Caps.kt`
**When** added
**Then** it exposes `const val`s `REJECT_PATH = HANDOFF`, `BLOCK_GUARD = true`, `VOICEMAIL_RECEIVER = false`, `LEGACY_AUDIO_FORCED = false`, `CONTACT_RINGTONE = false` (the safe defaults from the spike table), each with a comment naming its spike
**And** nothing else in the codebase hard-codes those decisions.

**Given** `CallEventRing.kt`
**When** the registry records events
**Then** it keeps a 50-entry ring of (elapsed ms, event name, call id, state name, caps mask, counts) and never stores numbers or names
**And** a JVM test proves the ring wraps and holds no strings from a number or name field.

### Story 14.0d: `rovsti_core::audio` endpoint list

As a developer agent,
I want the endpoint ordering and arrival/loss diff as pure, tested Rust,
So that the route picker has no branching on domain rules.

Covers: FR41, FR42, AD-23, AD-14, NFR-18. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `EndpointList::from_snapshot(&[Endpoint], baseline)`
**When** built
**Then** display order is Bluetooth newest arrival first, wired headset, Speaker, Phone, with Phone kept as a dimmed placeholder (`unavailable`) when Android omits it, at most 8 rows in a fixed array
**And** an empty Bluetooth name yields "Bluetooth device {n}" by ordinal, and earpiece/speaker are labelled by Rust, not Kotlin.

**Given** two consecutive lists
**When** diffed
**Then** the result names added ids, removed ids (kept as ghost "Disconnected" entries until the popover is next opened) and the selection change, and the first list of a session or a re-attach seeds the baseline with no arrival or loss reported.

**Given** table-driven tests
**When** run
**Then** they cover: only Phone+Speaker, headset replacing earpiece, two Bluetooth devices with the same name, hearing-aid hint plus a whole-word "hearing"/"aid" name check, vanished current endpoint falling back, `selected` none (Rust shows `route`), `streaming` ignored, and a derived `Toggle` vs `OpensList` decision for the Audio control (only Phone+Speaker means toggle).

### Story 14.0e: Coalesced rebuild in `CallRegistry`

As the owner,
I want audio and mute changes to arrive as one tidy update,
So that the call screen never flickers through half-states when a headset connects.

Covers: AD-23, AD-35, NFR-3, NFR-18. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE (EMU optional).

**Acceptance Criteria:**

**Given** `CallRegistry.scheduleRebuild()`
**When** `onCallEndpointChanged`, `onAvailableCallEndpointsChanged`, `onMuteStateChanged` or `onCanAddCallChanged` fire
**Then** exactly one posted Runnable rebuilds the snapshot within a 12 ms window, and every rebuild cancels a pending scheduled one and assigns `seq` when it runs
**And** `onCallAdded`, `onCallRemoved`, `onStateChanged`, `onDetailsChanged`, `onParentChanged`, `onChildrenChanged` and `onConferenceableCallsChanged` rebuild immediately (no transient three-call scene on merge).

**Given** coalescing
**When** answer, reject, notifications, `knownIds`, `ringingIds` or `incomingNow` are updated
**Then** they are never deferred by the window (a unit-level Kotlin test with a fake handler proves ordering).

**Given** a burst of 20 endpoint callbacks in 10 ms
**When** run on the phone or emulator
**Then** at most two snapshots are emitted and the last one is correct; the completion notes record the count seen in `RovstiSpike` logcat (ids and booleans only).

### Story 14.0f: Diagnostics page skeleton and report

As the owner,
I want a hidden Diagnostics page in the release APK that runs the platform probes and lets me paste a report,
So that platform questions get answered on my phone without adb and never block development.

Covers: AD-36, NFR-16, NFR-17. Prio: P1 (unblocks every spike). Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Setup sheet
**When** its title is long-pressed for 800 ms
**Then** a Diagnostics page opens (local only, no navigation entry elsewhere until 16.1 moves the trigger to the Settings title) listing probe groups Audio, Calls, SIM, Blocking, Reject text, Silence, Voicemail, Emergency, Contacts, Call log, TalkBack/system (each group a button that is inert and reads "Not built yet" until its spike story registers it)
**And** the page honours UIQ-1, UIQ-2 (fixed row heights) and UIQ-4.

**Given** `rovsti_android::diagnostics::run(probe: &str) -> Result<String, Error>` and shared `SpikeProbes.kt`
**When** a probe is registered
**Then** it returns one `pass`, `fail` or `unknown` line per check with a short reason, runs off the UI thread, and returns `Error::Unsupported` on desktop with a desktop test
**And** the logcat tag `RovstiSpike` carries ids and booleans only.

**Given** "Copy report"
**When** tapped
**Then** the clipboard holds the app version, device model, Android and HyperOS version, the date, every probe line produced in this session and the `CallEventRing` dump (no numbers, no names), and the Notice-style status line reads "Copied" (the Notice pill arrives in 14.3, which swaps it in).

**Given** the page is closed with Back
**Then** Back order is respected (UX-DR34) and no probe keeps running.

### Audio routes

### Story 14.1: `AudioPath` in Kotlin and spike S3 probe

As the owner,
I want the phone side to send the full endpoint list and route by identifier,
So that two Bluetooth devices can be told apart and chosen.

Covers: FR41, AD-23, AD-6 (amended), AD-36, NFR-16, S3. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `RovstiInCallService`
**When** `onAvailableCallEndpointsChanged` and `onCallEndpointChanged` fire
**Then** `AudioPath` rebuilds `Map<String, CallEndpoint>` on the main Looper, sends `audio.endpoints` (id from `CallEndpoint.getIdentifier().toString()`, type, name for bluetooth/wired/unknown, selected, `hint:"hearing_aid"` only when `AudioManager.getAvailableCommunicationDevices()` holds a `TYPE_HEARING_AID` device whose product name equals the endpoint name), omits `TYPE_STREAMING`, and keeps `route`/`routes` derived
**And** `callRouteEndpoint(id: String): Boolean` calls `requestCallEndpointChange(endpoint, executor, OutcomeReceiver)`; an unknown id returns false; a `CallEndpointException` forces an immediate rebuild so the UI reverts.

**Given** no `onAvailableCallEndpointsChanged` within 1.5 s of the first call, or `getCurrentCallEndpoint()` null, or `Caps.LEGACY_AUDIO_FORCED`
**When** the registry builds a snapshot
**Then** `AudioPath` falls back to `getCallAudioState()` with synthetic endpoint ids `legacy:earpiece|speaker|bluetooth|wired_headset` from `supportedRouteMask`, commands use `setAudioRoute(int)`, and the picker will show types without names (visible degradation)
**And** `callRoute(name)` still toggles Phone/Speaker via the unique earpiece or speaker endpoint.

**Given** the Diagnostics "Audio" group (spike S3)
**When** run during a call
**Then** for 20 s it prints type, name length, id tail and `selected` of each endpoint every second, and "names present with BLUETOOTH_CONNECT denied: pass/fail", "id stable while connected: pass/fail", "selected moved within 500 ms after a route request: pass/fail"
**And** `docs/spikes/s3-audio.md` exists with the owner procedure (connect and disconnect earbuds, a wired headset, the hearing aid if any, tap each in the picker once 14.5 ships), the pass criterion, the shipped default (legacy type-only list; names row asks for `BLUETOOTH_CONNECT`; no hearing-aid hint) and a PENDING result table
**And** `rovsti_android::calls::route_endpoint(id)` returns `Error::Unsupported` on desktop with a test, and `BLUETOOTH_CONNECT` is not requested at startup.

### Story 14.2: Endpoint model in the backend and v0.1 controls kept working

As the owner,
I want the app to hold the live endpoint list without changing what I see yet,
So that audio work proceeds safely behind the existing Speaker toggle.

Covers: FR41, AD-15, AD-23, AD-4 (amended), NFR-3. Prio: P1. Verify: CARGO, ANDROID-CHECK.

**Acceptance Criteria:**

**Given** `backend.rs`
**When** a snapshot arrives
**Then** `CallModel::apply` stores `audio.endpoints`, `EndpointList` (14.0d) is rebuilt only when the endpoint part changed, and `Backend` exposes `route_endpoint(id)` and the existing `route(name)`; views never see `Result`
**And** an unknown future endpoint type renders as an `unknown` row, not a dropped snapshot.

**Given** the v0.1 In-Call Speaker toggle
**When** used with only earpiece and speaker
**Then** it behaves exactly as before (pending look 120 ms, revert after 1 s if unreported, state always from the next snapshot)
**And** desktop sample data provides a Phone/Speaker/two-Bluetooth/wired list so every later audio story can be exercised without a phone.

**Given** `rovsti_core::call` tests
**When** extended
**Then** they cover a snapshot whose `selected` endpoint disappears while a route request is pending (the pending look clears on the next snapshot, never sticks).

### Story 14.3: Notice pill (S28) shared component

As the owner,
I want one tidy transient message surface,
So that confirmations, reasons and Undo look the same everywhere and never push content.

Covers: UX-DR29, UX-DR30 (S28), UX-DR26, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `rovsti_ui` (kit-level, reusable by Messages and Stops)
**When** `NoticePill` is added
**Then** it is a 48 px flat-glass capsule, 16 px side inset, one line (middle ellipsis for numbers), optional trailing accent text button (44 px target), a 2 px bottom hairline (accent at 60%) shrinking over the lifetime (info 2 s, Undo 5 s, 10 s with TalkBack on), rising 16 px with fade on `appear` and leaving on `sheet_dismiss`
**And** one pill at a time, a new one replaces the old in place (the old one's Undo is committed, not run), a finger on the pill holds the timer, Undo acts on finger-up and rewinds the hairline.

**Given** position rules
**When** shown
**Then** it sits 12 px above the Tab Bar, or 12 px above the bottom inset where there is none, and 12 px above the End button on the In-Call Screen; it never pushes content and never covers the Tab Bar (UIQ-2)
**And** it is a polite live region; Back ignores it; reduced motion = plain fade, no hairline (UIQ-4).

**Given** the timed hairline
**When** idle
**Then** no frames are requested after the pill is gone (P-22), and the pill is a cached view during its lifetime except the hairline transform (UIQ-3).

**Given** the Diagnostics "Copy report" button (14.0f)
**When** tapped
**Then** it now shows the pill "Copied".

### Story 14.4: System hand-off intents

As a developer agent,
I want one tested set of Kotlin intent helpers returning the standard envelope,
So that Bluetooth settings, Messages, Copy, Share and Add contact all fail soft the same way.

Covers: FR43, FR60 (intent), FR63 (intents), AD-31, AD-34, NFR-16. Prio: P1. Verify: ANDROID-CHECK, BUILD, CARGO.

**Acceptance Criteria:**

**Given** shared Kotlin and `rovsti_android::telecom`
**When** added
**Then** `open_system(page)` (pages: `Bluetooth`, `HearingDevices`, `Accessibility`, `Sound`, `DefaultApps`, `CallSettings`, `CallAccessibility`, `ChangePhoneAccounts`, `EmergencyInfo`, `AppDetails`) returns `{v,ok,error?,data?}`; each page uses its public action constant or the literal `android.settings.HEARING_DEVICES_SETTINGS` / `android.telephony.action.EMERGENCY_ASSISTANCE`, tries `startActivity` in try/catch and falls back to `ACTION_APPLICATION_DETAILS_SETTINGS` reporting `fallback:true`
**And** `send_sms_to(number)` (`ACTION_SENDTO smsto:`), `copy_text(text)` (`ClipboardManager.setPrimaryClip`), `share_text(text)` (`ACTION_SEND text/plain`) return Boolean, and `add_contact(number)` builds `ACTION_INSERT_OR_EDIT` with `Contacts.CONTENT_ITEM_TYPE` and `Insert.PHONE`.

**Given** desktop
**When** any of these runs
**Then** it returns `Error::Unsupported` and a desktop test per function proves it
**And** `Backend` maps `fallback:true` to the Notice text "Opened app settings instead" and a missing activity to a named reason (for contacts "No contacts app found").

**Given** Kotlin JVM tests
**When** run
**Then** the pure parts (page-to-action table, fallback chain order) are covered.

### Story 14.5: Audio control icon, caption and Phone/Speaker toggle (FR-42)

As the owner,
I want the Audio control to toggle when there is nothing else and to open the list when there is,
So that one tap does the obvious thing and the layout never moves.

Covers: FR42 (control half), FR15 (extended), UX-DR14, UX-DR27, UX-DR26, NFR-14, NFR-15, NFR-3. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the In-Call grid
**When** the Audio control (row 1, column 3, replacing Speaker) renders
**Then** it shows the current route's icon (`LucideBluetoothConnected` for a Bluetooth route, `LucideHeadphones` for wired, `LucideVolume2` speaker, `LucideSmartphone` phone) and a caption in a fixed 96 px one-line end-ellipsis slot ("Audio" when no detail is known), built as a `SharedString` when data arrives (no per-frame allocation), and the grid and End button do not move at font scale 1.3 (caption ellipsises, full name in the TalkBack label)
**And** the slot constants live in `incall.rs` (AD-35).

**Given** only earpiece and speaker (decision from `EndpointList`)
**When** tapped (finger-up)
**Then** it toggles Phone/Speaker with the existing moment S10 tint flood and pending look
**And** until 14.6 adds the list, a tap with other endpoints present keeps the same toggle, and the 6 px dot position (top-right of the icon) is reserved for 14.7.

**Given** the call is Holding
**When** the control is tapped
**Then** it is dimmed with the other controls and does nothing; during Dialing and during an emergency call it stays enabled.

**Given** UIQ-1, UIQ-4
**When** checked
**Then** press feedback is within one frame of the raw touch, sliding off releases without acting, the TalkBack label reads "Audio, {route name}. Double tap to choose audio output" (or "Audio, Speaker off. Double tap for speaker" in the two-route case).

### Story 14.6: Route popover, its open triggers and S17 Route bloom

As the owner,
I want a glass list of every audio route that opens from the Audio control,
So that I can move the call to my earbuds in two taps.

Covers: FR41, FR42, UX-DR29, UX-DR30 (S17), UX-DR26, UX-DR27, UX-DR28, NFR-14, NFR-15, NFR-3, S3 (UI half). Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Audio control from 14.5
**When** any endpoint other than earpiece and speaker is available
**Then** a tap (finger-up) opens the popover, and a long-press (450 ms, light haptic) opens it at any time (it then shows Phone, Speaker, the hint row and the footer); with only earpiece and speaker a tap still toggles
**And** the control presses to 0.97 and stays tinted while the popover is open, and is dimmed with the other controls (no popover) while Holding.

**Given** the route popover (288 px wide, 20 radius, sheet glass, right edge on the 16 px inset, opening upward 8 px above the Audio control row)
**When** opened
**Then** it scales 0.86 to 1.0 from the control's top-centre on `appear` with rows rising 8 px staggered 24 ms, the page behind dims to 0.7, and the popover paints as the flat glass look until within 2% of rest, then swaps to the refractive pass in one frame
**And** the End button and the control grid stay in place and tappable; tapping a control outside the popover only dismisses it and fires nothing.

**Given** rows (52 px: 36 px icon tile, name one line ellipsis, subtitle "Connected"/"Disconnected"/"Hearing aid", 24 px check on the current route) bottom-anchored with the newest device at the top
**When** one is tapped (finger-up, light haptic on press)
**Then** `Backend::route_endpoint(id)` is called with the identifier, the popover closes immediately with S17 (the check circle travels to the Audio control, icon cross-fades, label slides 6 px, one accent ripple for Bluetooth/wired), the control shows the pending look until Android reports the new route (within 500 ms) and reverts after 1 s with the Notice pill "Couldn't switch to {name}"
**And** tapping the current row only closes.

**Given** the list has more than 5 device rows
**When** shown
**Then** it scrolls inside a fixed 5-row window, never exceeds 70% of screen height, and is virtualised with fixed 52 px rows (endpoint array max 8).

**Given** states
**When** no device is connected, the earpiece is missing, Hold begins while open, or a legacy list is active
**Then** the "No Bluetooth device connected" disabled hint row, the dimmed "Unavailable with this device" Phone row, auto-close on Hold, and types-only rows appear exactly as the EXPERIENCE state table says; Back closes the popover first; reduced motion is a 120 ms fade with no ring (UIQ-4)
**And** TalkBack announces "Audio output, {n} options", rows read "{name}, selected" / "{name}. Double tap to switch" / "{name}, unavailable", and "Now using {name}" is announced politely.

**Given** the owner's S3 procedure
**When** the story ends
**Then** the device test script (open 20 times, switch to each device, observe pending look and revert) is in the completion notes.

### Story 14.7: Live updates, S18 Device arrival and the names row

As the owner,
I want the list and the control to follow my devices connecting and disconnecting without me touching anything,
So that the call screen always tells the truth.

Covers: FR41, FR43 (names), UX-DR30 (S18), UX-DR27, NFR-3, NFR-14, NFR-18, AD-23, S3. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the popover is open
**When** an endpoint appears
**Then** the new row fades in at the top edge on `appear` with one halo ring on its icon tile (accent glow 0.5 alpha, 1.0 to 1.6, 380 ms), no existing row moves under the finger
**And** a lost endpoint dims in place with "Disconnected" and is removed on the next open; if it was the current route the check moves to the fallback Android reports on `select` and the control follows.

**Given** the popover is closed
**When** an endpoint arrives
**Then** the control icon and caption update from the snapshot and a 6 px accent dot fades in at the icon's top-right for 3 s without changing the selected route; the first snapshot of a session or a re-attach plays no arrival or loss moment
**And** updates arrive coalesced to at most one render per frame via the endpoint-part change check from 14.2 (`SceneDiff` generalises it in 14.10), with a Bluetooth connect/disconnect x5 check recorded in the completion notes (P-11).

**Given** `BLUETOOTH_CONNECT` is denied and names are missing (S3 default)
**When** the list shows "Bluetooth device 1" etc.
**Then** a top row "Show device names" (accent) asks once per session through the permission bridge, opens the app permission page after two denials, and routing works regardless
**And** if the S3 report later shows names present without the grant, a one-line change hides this row (`Caps`-independent runtime check on whether any name was empty).

**Given** reduced motion
**Then** the halo and dot appear without fade; S18 is among the first effects dropped only under the AD-21 policy (never by default).

### Story 14.8: Shortcut footer, hearing-aid hint and compatibility value

As the owner,
I want shortcuts to Bluetooth and hearing-aid settings under the route list,
So that pairing a new device is one tap away from the call.

Covers: FR43, UX-DR29, UX-DR32, AD-23, AD-34, NFR-16, UX-Q19, S3. Prio: P1. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the popover footer (hairline separated, 44 px rows, accent `subheadline` text with trailing `LucideExternalLink`)
**When** "Bluetooth settings" or "Hearing aids" is tapped (finger-up)
**Then** the popover closes first, `Backend::open_system` opens `ACTION_BLUETOOTH_SETTINGS` or the `HEARING_DEVICES_SETTINGS` literal (the `<queries>` entry that lets `resolveActivity` see the page arrives in 14.15; `startActivity` needs none, so the shortcut works either way), and on a missing activity falls back to the main settings or app details with the Notice "Opened app settings instead"
**And** the call is never affected and the In-Call Screen is still current on return.

**Given** a Bluetooth endpoint with `hint:"hearing_aid"` or a whole-word "hearing"/"aid" name
**When** listed
**Then** its icon is `LucideEar` and the subtitle reads "Hearing aid"; otherwise no special treatment (best effort, never required)
**And** `telecom::hearing_aid_compatible()` (`TelephonyManager.isHearingAidCompatibilitySupported()`) exists with a desktop `Unsupported` test and feeds the Settings informational row in 16.10.

**Given** this is the last audio story
**When** the story ends
**Then** `bash scripts/build-release.sh` runs and the release APK path is reported as owner release 1 ("Audio routes") with the device test script for UJ-7 and the Diagnostics "Audio" procedure; the owner's pasted report is processed by 22.6.

### Foundation F0, part 2 (needed by Epics 15 to 21)

### Story 14.9: Provider and seam wire structs with golden fixtures

As a developer agent,
I want the remaining v0.2 wire shapes as tested serde structs,
So that every provider pull, one-shot event and settings sync has a fixture before any UI reads it.

Covers: AD-10, AD-26, AD-27, AD-30, AD-31, AD-32, AD-33, NFR-8. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `rovsti_core::wire`
**When** extended
**Then** it has: `Envelope<T>{v,ok,error?,data?}` with `error` in `permission|unavailable|refused|not_found|unknown`; contact fields `lookup_key`, `display_name_alt`, `sort_key`, `sort_key_alt`; call-log row fields `account_key`, `duration_s`, `kind` including `voicemail` and `blocked`, `block_reason` (`user|system|unknown`) and details-only `features` bitmask; `PhoneAccounts{accounts[{key,label,slot_index?,color_argb,sim,enabled}],default_key?,ask}`; blocklist rows; `SettingsSync{proximity,flip_to_silence,vibrate_connect,vibrate_end,has_replies,keypad_tones,haptics,name_format}`; `VoicemailInfo`, `EmergencyList`; one-shot events `document_result{op,ok,name?,text?,error?}`, `ringtone_result`, `ui_request{kind,call_id?}`; new event kinds `blocked_changed`, `sims_changed`, `voicemail_changed`
**And** every struct has a golden fixture and test, ignores unknown fields, and uses `Unknown` safe enums.

**Given** the Recents row
**When** parsed from a v0.1 fixture
**Then** all new fields default so nothing breaks.

**Given** Kotlin
**When** the shared fixtures are listed in `docs/phone-wire.md`
**Then** each names the Kotlin JVM test that reads it (added by the story that builds the Kotlin side).

### Story 14.10: `rovsti_core::scene` and `SceneDiff`

As a developer agent,
I want the call scene and the disabled-reason logic as pure, tested Rust,
So that multi-call UI only renders what the core decides.

Covers: FR44 to FR48, AD-24, AD-14, AD-35, NFR-14, NFR-18. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `CallScene::from(&CallModel)`
**When** built
**Then** it yields `Single{primary}`, `Two{primary, held}`, `Conference{host, participants, held: Option}` (Conference wins beside a held call) plus independent `waiting: Option` and `ended: Option`, validating the Kotlin-chosen foreground/background/waiting ids and never re-selecting
**And** participant labels are "Participant n" when a child has neither name nor number, child ids missing from `calls` are skipped and counted, and "n people" is produced when the count is known and no rows exist.

**Given** `DisabledReason`
**When** derived per control
**Then** the closed enum follows the precedence emergency, ringing/pending, carrier, count, capability and yields the exact strings from the Voice and Tone table ("Merge needs two calls", "Your carrier doesn't allow merging calls", "Two calls are already in progress", "Not available during an emergency call")
**And** table tests cover each control x scene x capability combination, including Hold-vs-Swap sharing one slot.

**Given** `CallModel::apply`
**When** a snapshot is applied
**Then** it returns a `SceneDiff` (primary, held, waiting, endpoints, participants flags), applies every snapshot in `seq` order even when no frame is drawn, ignores equal or lower `seq`, and keeps a transient `disconnected`/`ended_call_id` snapshot for the Ended overlay
**And** a test asserts a pure timer tick changes nothing in the diff.

### Story 14.11: `rovsti_core::settings` PrefsV2 and upgrade chain

As the owner,
I want my existing preferences to survive the upgrade and new settings to have safe defaults,
So that nothing I set up is lost or changes behaviour.

Covers: FR49 (store), FR57, FR64, FR66, FR71, FR72, AD-30, NFR-17, NFR-8. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `rovsti_core::settings::PrefsV2`
**When** defined
**Then** it has `schema_version: 2`, `ui{last_tab, missed_seen_ms, app_version, xiaomi_confirmed, hint_counters}`, `settings{haptics, proximity, flip_to_silence, vibrate_connect, vibrate_end, keypad_tones: Option<bool>, sort_by, name_format, show_frequent}`, `quick_replies: Vec<String>`, `speed_dial: BTreeMap<u8, SpeedDialEntry{lookup_key, number_raw, name}>`, `favorites_order: Vec<String>`
**And** defaults reproduce v0.1 (proximity true, haptics Normal, flip/vibrate off, keypad tones follow system, show_frequent true, the three default replies as constants).

**Given** validation
**When** applied on load and on change
**Then** replies are at most 6 and 100 characters, speed-dial digits 2..=9, the favorites order is deduplicated, and invalid input is clamped, never panicking.

**Given** `PrefsV1` (flat v0.1 file, no `schema_version`)
**When** converted by `From<PrefsV1> for PrefsV2`
**Then** every v0.1 field maps (last tab, missed-seen time, app version, Xiaomi confirmations) and fixtures under `crates/rovsti_core/tests/fixtures/prefs/` (v1 typical, v1 minimal, v2 typical, v2 with unknown extra field) cover round trips
**And** `haptics`' filter (`Light` allows tick-class kinds only) is a pure function with tests.

**Given** `BackupFile{v:1, kind:"rovsti-phone-backup", created_ms, app_version, settings, quick_replies, speed_dial, favorites_order}`
**When** built and checked
**Then** `ImportPreview::from(&BackupFile, &current)` validates kind, `v` and bounds and yields the section counts (replace semantics; a duplicated speed-dial digit moves with a "moved from" note); there is no `ui` or Xiaomi confirmation in it, and tests cover the invalid and foreign file cases.

### Story 14.12: Prefs v2 storage migration, `PrefsWriter` and `Backend` settings

As the owner,
I want the upgrade to be safe and settings to save without hitches,
So that a crash or a downgrade never costs me my data.

Covers: FR49, AD-30, NFR-17, NFR-6. Prio: P1. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `rovsti_ui::storage`
**When** loading `prefs.json` from the no-backup data dir
**Then** it reads `Probe{schema_version}` (missing = 1), converts `PrefsV1` through `From`, and the first v2 save copies a version-1 file to `prefs.json.bak-v1`
**And** a higher version than the build knows loads best effort after a copy to `prefs.json.bak-v{n}`, and a corrupt file loads defaults after a copy to `prefs.json.corrupt` before the first save.

**Given** `PrefsWriter`
**When** settings change
**Then** one worker holds the latest snapshot, coalesces saves to one per 250 ms, flushes on pause, writes atomically (tmp then rename) and no two writers share the `.tmp` file
**And** a failed save keeps the in-memory value, logs without content and sets a flag the UI uses for "Couldn't save setting".

**Given** `Backend`
**When** it exposes `Arc<Settings>` snapshots
**Then** views observe it and no render path reads a file; the Xiaomi confirmation and last-tab behaviours of v0.1 are unchanged (existing tests still pass).

**Given** a phone that already holds a v0.1 `prefs.json`
**When** the signed release APK is installed over the previous one
**Then** the app starts on the remembered tab with Xiaomi confirmations intact (device script in the completion notes).

### Story 14.13: `rovsti_core::names` and `blocklist::BlockSet`

As a developer agent,
I want name ordering and block-set matching as pure code,
So that sort/format and Block/Unblock labels never live in views.

Covers: FR72, FR53, FR54, AD-32, AD-27, AD-14. Prio: P1. Verify: CARGO.

**Acceptance Criteria:**

**Given** `names::display(contact, NameFormat)` and `names::sort_key(contact, SortBy)`
**When** used
**Then** display picks primary or alternate (`DISPLAY_NAME_PRIMARY`/`ALTERNATIVE`), the sort key picks the provider sort key then folds it (Turkish-aware), and when alt equals primary the option changes nothing
**And** tests cover "Ayse Yilmaz" vs "Yilmaz, Ayse", "Özgür", a single-name contact, a number-only contact, and a re-sort that runs once per setting change.

**Given** `BlockSet`
**When** built from blocklist rows
**Then** it holds normalized numbers matched with `PhoneNumber` (formatting insensitive, last-digits tolerant as the v0.1 matcher), answers `is_blocked(raw)` for the Block/Unblock label and the chip, and never hides Recents rows itself (hiding is by `kind == blocked`)
**And** emergency numbers can never be added (a pure guard function with tests).

### Story 14.14: T9 index, `frequent` and `is_emergency`

As a developer agent,
I want the keypad intelligence logic fully tested before any UI,
So that the T9 strip, Frequent row and emergency path only render core decisions.

Covers: FR64, FR65, FR68, AD-33, AD-32, NFR-18, AD-14. Prio: P2 (built early as F0). Verify: CARGO.

**Acceptance Criteria:**

**Given** `ContactIndex::new`
**When** built per contacts payload
**Then** names are folded and mapped with ITU E.161 letters (abc2 def3 ghi4 jkl5 mno6 pqrs7 tuv8 wxyz9; any other character ends a word) into one `Box<[u8]>` of digit codes and a `Box<[u16]>` of word starts per contact
**And** `ContactIndex::t9(&self, digits, &mut T9Scratch) -> &[T9Hit]` allocates nothing after warm-up, narrows from the previous candidates when the query extends it, ranks exact number match, name prefix, word start, number substring, call frequency, name, returns the top 50 and each `T9Hit{contact_idx, number_idx, name_span, rank}`.

**Given** tests
**When** run
**Then** they include "7 9 2" finds "Ayşe Yılmaz" through diacritic folding, "6 9 2" finds "Özgür" family, ties by frequency, empty digits, a stale query, a 2,000-contact benchmark asserting a worst-case under 8 ms and recording the typical keypress cost (target p95 1 ms), and a hit span in the displayed name under both name formats.

**Given** `call_log::frequent(entries, now_ms, window_days = 90, limit = 4, exclude_starred)`
**When** run over the in-memory Recents rows
**Then** it counts answered, outgoing and missed rows, breaks ties by latest call, excludes starred and blocked, and tests cover empty, one-contact and tie cases
**And** `dial::is_emergency(&str, &EmergencyList)` is a pure compare that includes the always-present `112` and `911` and ignores formatting characters, with tests including `*#06#` (false) and `1 1 2` typed with separators.

### Story 14.15: Manifest and permission additions, `check-apk.sh` and desktop stubs

As the owner,
I want the manifest to declare exactly what v0.2 needs and nothing else,
So that no unjustified permission or INTERNET ever ships.

Covers: AD-34, AD-18 (amended), NFR-1, NFR-7, NFR-16, NFR-17. Prio: P1. Verify: BUILD, ANDROID-CHECK, CARGO.

**Acceptance Criteria:**

**Given** the ejected `AndroidManifest.xml` and `gpuiforge.json`
**When** updated and mirrored
**Then** `android.permission.VIBRATE` is added; a `<queries>` block holds `<intent>` entries for `android.telephony.action.EMERGENCY_ASSISTANCE`, `android.settings.HEARING_DEVICES_SETTINGS` and `android.telecom.action.SHOW_CALL_SETTINGS`; a `.VoicemailReceiver` (exported, `android:enabled="false"`, `android:permission="android.permission.READ_PHONE_STATE"`, action `android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION`) is declared with a stub class that does nothing while disabled
**And** `SEND_SMS`, `READ_PHONE_NUMBERS`, `MODIFY_PHONE_STATE`, `QUERY_ALL_PACKAGES`, `BIND_SCREENING_SERVICE`, `READ_EXTERNAL_STORAGE` and `INTERNET` are absent.

**Given** `scripts/check-apk.sh`
**When** run on the built APK
**Then** it additionally fails if `SEND_SMS` or `INTERNET` is in the merged manifest, verifies `VIBRATE` and the `<queries>` entries, and verifies the receiver is disabled by default
**And** it still checks the two `Java_*` symbols.

**Given** `rovsti_android`
**When** the v0.2 modules exist as empty facades (`blocklist`, `documents`, `settings`; `diagnostics` already exists from 14.0f; `telecom` and `calls` additions land with their stories)
**Then** each compiles on desktop with a test asserting `Error::Unsupported`, so later stories fill bodies without changing the desktop contract (NFR-7).

**Given** this closes Foundation F0
**When** done
**Then** `docs/phone-wire.md` lists every wire shape as landed, and `bash scripts/build-release.sh` is run once with the report path in the completion notes (a no-visible-change build, installed over the audio release to prove the Prefs v2 migration on the phone).

---

## Epic 15: Multi-call and conference

The owner can add a call, see both calls, swap, merge into a conference, manage participants where the carrier allows it, and handle a waiting call in four ways, all on an In-Call Screen whose grid and End button never move.

### Story 15.1: Kotlin multi-call model and spike S4 probe

As the owner,
I want the phone side to track several calls, conferences and what each can do,
So that the screen can enable only what the carrier really supports.

Covers: FR44 to FR48, AD-24, AD-4 (amended), AD-36, NFR-16, S4. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `CallRegistry`
**When** any `Call` (conference children included) is added
**Then** it gets a stable registry id, and the wire call carries `parent_id?`, `children[]`, `caps` and `props` bitmasks built from `Call.Details` with the 14.0a bit positions (split and disconnect-participant read from the child's details), `account_key?`, `display_name_alt?`, `hold_since_ms?`
**And** `can_hold` equals `caps.hold`, and `props.emergency` comes from `PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL` or a Kotlin emergency match.

**Given** `Focus.of` (replacing the v0.1 rule)
**When** it selects among top-level calls only
**Then** `foreground` = first Active, else Dialing, else Holding (lowest id), else Ringing, else Disconnected (the Ended hold, only when no live non-ringing call exists); a Disconnected top-level call beside a live one is reported as `ended_call_id`; `background_call_id` is the other non-ringing non-disconnected call (extras are logged and unrendered); `waiting_call_id` is the lowest-id Ringing call that is not the foreground; a conference is one top-level call; while a `PendingOp` runs the previous foreground is kept
**And** a pure JVM-testable `Focus` function has tests for each rule, including "the active party hangs up so the held call becomes foreground in `holding` and nothing resumes by itself".

**Given** `can_add_call`, `can_merge`, `can_swap`
**When** computed in Kotlin
**Then** `can_add_call` = `InCallService.canAddCall()` and false for an emergency call or two non-ringing top-level calls; `can_merge` = foreground `caps.merge` or any top-level call with non-empty `conferenceableCalls`; `can_swap` = two non-ringing calls and the held one can resume, or foreground `caps.swap`
**And** the snapshots are validated against the 14.0b `two_calls.json` and `conference.json` fixtures by JVM tests.

**Given** the Diagnostics "Calls" group (spike S4)
**When** run during real calls
**Then** it prints caps masks, props, conferenceable and child counts and the event ring, plus pass/fail lines for the four S4 criteria (Swap changes the Active call within 1 s without both held; Merge yields a top-level call with `props.conference` within 2 s; children and their split/disconnect bits are reported; Hold-and-answer holds the first call)
**And** `docs/spikes/s4-multicall.md` records the owner procedure (Call A, Add call B, Swap twice, Merge, End one participant, a third phone calls in and the four Waiting choices), the shipped defaults (Merge dimmed with reason, conference rows read-only, swap by hold then unhold, Hold-and-answer dimmed when the current call has no hold cap) and a PENDING result table.

### Story 15.2: The `PendingOp` machine and multi-call commands

As the owner,
I want swap, merge and answer-with-hold to be reliable and never leave a call stuck,
So that conference controls are safe to press mid-call.

Covers: FR45, FR46, FR47, FR48, AD-25, AD-6 (amended), NFR-3, NFR-16. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE (EMU optional).

**Acceptance Criteria:**

**Given** `CallRegistry`
**When** an op starts
**Then** it holds at most one `PendingOp` (`AnswerReplacing` as built, `Swap(toResume)`, `Merge`) with deadlines answer 2 s, swap 1 s then one `hold()` retry, merge 2 s, ending through `finish(op, Done|Failed)` (`Done` on the matching `onStateChanged`/`onCallRemoved`/`onChildrenChanged`, `Failed` on the deadline); a new op is refused (false) while another runs except `AnswerReplacing`, which supersedes `Swap` or `Merge`
**And** `Failed` never answers a ringing call unless the op is `AnswerReplacing`, and the wire carries `pending` while it runs and `pending_failed` for exactly one rebuild afterwards.

**Given** the commands
**When** called
**Then** `swap()` uses `swapConference()` when the foreground has `caps.swap`, else `unhold()` on the held call (and a `hold()` on the former active call if it is still Active after 1 s, then failure); `merge()` uses `mergeConference()` or `foreground.conference(other)` taken from `conferenceableCalls`; `split(child_id)` is `splitFromConference()` only with `caps.split`; `disconnect(child_id)` ends one participant; End on a conference is `disconnect(host_id)`; `answerHolding(id)` is `answer(id)` offered only when the current call has `caps.hold` and no held call exists (otherwise the choice is relabelled "End held call and answer"); `endAll()` ends every non-ringing top-level call
**And** all return Boolean, stale ids are no-ops, none blocks the main Looper, and `rovsti_android::calls::{swap, merge, split, answer_holding, end_all}` exist with desktop `Unsupported` tests.

**Given** JVM tests with a fake clock and fake calls
**When** run
**Then** they cover swap success, swap needing the retry, swap failure, merge success, merge timeout clearing the op, `AnswerReplacing` superseding `Swap`, and a refused second op.

### Story 15.3: Rust call scene integration and commands

As the owner,
I want the app to turn the multi-call snapshot into the right scene and send the right commands,
So that the screens in the next stories only render.

Covers: FR44 to FR48, AD-24, AD-25, AD-15, AD-35. Prio: P1. Verify: CARGO, ANDROID-CHECK.

**Acceptance Criteria:**

**Given** `Backend`
**When** a snapshot arrives
**Then** `CallScene` and `SceneDiff` (14.10) drive `cx.notify()` so only the views for changed parts (primary, held, waiting, endpoints, participants) re-render, and a timer tick re-renders only the timer elements
**And** `Backend` exposes `swap()`, `merge()`, `split(id)`, `disconnect(id)`, `answer_holding(id)`, `answer_replacing(id)`, `reject(id)` and `end_all()` returning domain results, and the displayed Hold/Mute/Route/pending state always comes from the next snapshot.

**Given** desktop
**When** run
**Then** sample data supplies the five scenes (single, two calls, conference with 4 participants, conference with hidden participants, waiting during a call) behind a debug scene switcher in the preview so every UI story can be developed and screenshot-tested.

**Given** the v0.1 Waiting banner and single-call screen
**When** this story lands
**Then** they keep working unchanged (the replacements arrive in 15.4 to 15.8).

### Story 15.4: Fixed In-Call control grid, chip row and disabled reasons

As the owner,
I want the same six control slots whatever is happening on the call,
So that my thumb always finds Mute, Add call and Merge in the same place.

Covers: FR42, FR44, FR45, FR46, UX-DR27, UX-DR14 (amended), UX-DR26, UX-DR29, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `incall.rs`
**When** the grid is rebuilt
**Then** it is 3 columns x 2 rows of 76 px controls with 24 px gaps: row 1 Mute, Keypad, Audio; row 2 Add call, Hold (becomes Swap with two calls, `LucideArrowUpDown`), Merge; slot constants (grid origin, 22 px chip row, 96 px Audio caption, Held-card slot, Waiting overlay, conference panel zone, End 220x64) live in one module and every scene uses them
**And** disabled controls dim to 0.4 and keep their place; the enablement table in EXPERIENCE.md (one call, two calls, conference, held) is implemented from `DisabledReason` and unit tests, not from view logic.

**Given** a dimmed control
**When** long-pressed for 450 ms (light haptic)
**Then** its reason shows in the Notice pill for 2 s and the TalkBack hint carries the same text; tapping a dimmed control does nothing
**And** a pending look (Hold, Swap, Merge, Mute, Audio) holds up to 1 s and reverts if the snapshot does not confirm.

**Given** the chip row (22 px reserved, centred, under the number line)
**When** empty
**Then** it keeps its height and nothing moves when chips later appear (SIM chip in 16.5, HD/Wi-Fi chips in 21.4).

**Given** P-13
**When** the six scenes plus the route popover (14.6) are screenshotted on desktop (15.5 extends the same test with the Add call sheet)
**Then** an automated overlay test asserts the grid and End rects are identical across them (pixel-exact rect equality).

**Given** UIQ-1 and UIQ-4
**When** checked
**Then** press feedback is within one frame, labels "Add call", "Swap calls, {name} on hold", "Merge calls" exist, and font scale 1.3x scrolls the in-call zone but never moves the grid.

### Story 15.5: Add call sheet

As the owner,
I want to pick someone from a keypad, my Favorites or my Contacts without leaving the call,
So that adding a third person is quick and the first call keeps running.

Covers: FR44, UX-DR8, UX-DR30 (S16 pattern), UX-DR26, UX-DR27, UX-DR34, AD-24, AD-3 (amended), NFR-14, NFR-16. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Add call control is enabled
**When** tapped (finger-up)
**Then** a sheet rises at the large detent over the In-Call Screen (S16 pattern) with a pill at its top showing the held-to-be call's name and timer ("Ayşe · 2:41", 44 px), a three-segment control Keypad / Favorites / Contacts (remembered for the session, first use Favorites) and the call keeps running underneath
**And** the Keypad segment is the standard 72 px key grid with number field, Call button, validation and paste rules (not the DTMF keypad), Favorites shows the tiles (no long-press, no reorder), Contacts shows search plus list with the number choice inline.

**Given** a number is chosen
**When** the Call is tapped
**Then** `Backend::place_call` (the one call path, FR-11) runs, the sheet dismisses on `sheet_dismiss` while the Hold-away (15.6) plays, and the network holds the first call; a failed placement (busy, blocked, no signal) shows "Call failed: {reason}" in the sheet's pill and the first call stays Active
**And** Back closes the sheet first and places nothing.

**Given** the keyguard is locked
**When** Add call is tapped (state re-read from `KeyguardManager` at the tap, not the last snapshot)
**Then** `KeyguardManager.requestDismissKeyguard(activity, callback)` runs first and the sheet opens only from the success callback; Favorites and Contacts segments never open over a locked keyguard
**And** an emergency call shows Add call dimmed with "Not available during an emergency call".

**Given** the overlay test from 15.4
**When** extended with the Add call sheet open
**Then** the grid and End rects are still pixel-identical

**Given** permissions and empty states
**When** contacts are denied or Favorites is empty
**Then** the State Patterns copy and "Grant access" pill apply; the sheet counts one extra refractive surface only (UIQ-3) and the page behind dims 0.85.

### Story 15.6: Two-call view, Held card, S19 Hold-away and S20 Swap crossing

As the owner,
I want to see both people, how long each has been on, and swap with one tap,
So that juggling two calls feels natural.

Covers: FR45, UX-DR29 (Held card), UX-DR30 (S19, S20), UX-DR26, UX-DR27, UX-DR23, AD-24, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** a `Two` scene
**When** rendered
**Then** the active block (avatar 72, name 28 px, timer, chip row) sits in the primary slot and the Held card (64 px flat-glass capsule tinted warning 12%, avatar 40 with the Holding ring, name, "On hold · m:ss", trailing 44 px swap button) sits in the Held-card slot; both timers tick once per second as single repaint regions (`ElapsedText` reused)
**And** tapping the Held card or the Swap control (finger-up, press scale 0.98 on the wide card) swaps with S20 (arcs with 12 px sideways offset peaking mid-flight on one `morph` spring, shared avatar and timer quads, aura hue cross-fade) and one medium haptic, with the pending look until the snapshot confirms.

**Given** a second call is placed or a call becomes held
**When** the scene changes
**Then** S19 Hold-away plays (the first call's avatar, name and timer shrink 96 to 40 and 34 to 17 px into the Held-card slot, the new call's avatar drops in with overshoot, aura desaturates to the Holding look and the new pair blooms) and the geometry of the grid and End does not change
**And** only content, opacity and scale change; reduced motion is a 120 ms cross-fade.

**Given** hang-ups
**When** the held party hangs up
**Then** the Held card fades out in 120 ms and the zone returns to the one-call layout with no travel
**And** when the active party hangs up the active block shows "Call ended" for 1 s (the `ended_call_id` overlay) and then the held party becomes the active block in `holding` with a prominent "Resume" label; nothing resumes by itself.

**Given** TalkBack
**When** the Held card is focused
**Then** it reads "Held call: {name}, on hold 1:12. Double tap to swap".

### Story 15.7: Merge, conference view, S21 Merge weave and S22 Participant eject

As the owner,
I want to merge two calls and manage who is on the conference,
So that a three-way call works from my own dialer.

Covers: FR46, FR47, UX-DR29 (Conference panel), UX-DR30 (S21, S22), UX-DR26, UX-DR27, UX-DR32, AD-24, AD-25, NFR-14, NFR-18, NFR-16. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Merge control is enabled
**When** tapped (finger-up, medium haptic)
**Then** `Backend::merge()` runs, the control shows the pending look until Android reports the conference, S21 plays only on that report (never on tap) and, if no report arrives within 2 s, the control returns and the Notice pill says "Couldn't merge calls"
**And** a carrier that does not allow merging leaves the control dimmed with "Your carrier doesn't allow merging calls" on long-press.

**Given** a `Conference` scene
**When** rendered
**Then** the header shows an avatar stack (up to 3 overlapped then "+n"), "Conference" `title3` and the conference timer, with the participant panel (26 radius flat glass, fixed three-row window of 156 px, rows 52 px virtualised, scrolls beyond 3) below it; the primary block layout, grid and End do not move
**And** rows show avatar 36, name, number/"Participant" subtitle and per row Split (`LucideSplit`) and End (`LucideUserMinus`, danger) only where the child reports the matching capability, otherwise read-only with the icons not drawn and the row height kept.

**Given** a row action
**When** Split (finger-up) runs
**Then** the participant becomes the active private call and the rest of the conference is held (Android behaviour) with S22 (the avatar scales 1.15 and flies up to the active slot, the stack shrinks by one on the same spring)
**And** End (finger-up, no confirmation) ends one party with the 24 px sideways fade then an instant re-layout; polite announcements "Özgür left the conference" are made.

**Given** the carrier hides participants or the conference drops to one person
**When** the scene changes
**Then** one "Conference" row with "n people" (no actions) shows while the whole-conference End stays, and a drop to one participant returns the zone to the one-call layout
**And** the big End button on a conference ends everyone (UX-Q7 default wording "End"; TalkBack says "End conference"), and Hold on a conference gives the avatar stack the Holding ring.

**Given** P-15
**When** a 6-participant conference is simulated on desktop
**Then** frame cost is recorded in the completion notes (virtualised fixed rows, `SceneDiff`-gated updates).

### Story 15.8: Waiting card, S23 Waiting card drop

As the owner,
I want four choices when someone calls during a call,
So that I decide what happens to both people.

Covers: FR48, UX-DR29 (Waiting card), UX-DR30 (S23), UX-DR26, UX-DR17 (superseded), AD-24, AD-25, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `waiting_call_id` is present
**When** the card appears
**Then** a flat sheet-glass card (38 radius, 16 side inset, 12 px under the top inset, 184 px high) falls 40 px with overshoot (`release`, ζ 0.6, k 420), the zone beneath dims to 0.85 without shrinking, the aura plays its double pulse, the pills spring in staggered 40 ms, and the card is tappable from the first frame
**And** it replaces the v0.1 76 px banner; the card never samples the backdrop (0 refractive surfaces).

**Given** the 2x2 pill grid (44 px pills, 8 px gaps)
**When** a pill is tapped (finger-up, medium haptic for the call-changing ones)
**Then** "Hold and answer" calls `answer_holding`, "End and answer" calls `answer_replacing` with the footnote "Ends {name}", "Decline" calls `reject` leaving the first call Active and the card rising away, and "Message" is dimmed with "Add replies in Settings" or no-op reason until 18.7 wires the reply sheet
**And** with an Active, a Held and a Ringing call the primary pill is relabelled "End held call and answer"; on a conference only the four choices Android allows are enabled, others dimmed not hidden.

**Given** the caller hangs up first
**When** the call is removed
**Then** the card rises away and a Notice pill says "Missed call from {name}" for 2 s
**And** the card never auto-dismisses otherwise; TalkBack announces "Waiting call from {name}" assertively and focuses "Hold and answer".

### Story 15.9: Notifications, End semantics and lock-screen rules for several calls

As the owner,
I want the notification and the lock screen to behave sensibly with two calls,
So that I can always end or return to the right call.

Covers: FR19 (extended), FR47, AD-2, AD-3 (amended), AD-24, AD-25, NFR-6. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** two calls or a conference
**When** the ongoing notification updates
**Then** it follows `foreground`, shows the call count in its subtext, and End calls `endAll()` (documented) while the in-app big End on a Two scene ends the foreground call only and on a Conference ends the conference
**And** a Waiting call posts its own incoming CallStyle notification with Answer/Decline registry-only actions (the Message action arrives in 18.7).

**Given** call-only mode on the lock screen
**When** `keyguard_locked` and calls exist
**Then** the route popover, Waiting card, Held card and merge/swap controls stay available, while Settings, Call details, Contact Sheet and the Add call sheet's Favorites/Contacts segments do not open, and a re-lock closes any open sheet or popover
**And** a Kotlin test or documented script proves the registry never leaves the foreground service running after the last call ends.

**Given** this is the last Epic 15 story
**When** the story ends
**Then** `bash scripts/build-release.sh` runs and the release APK path is reported as owner release 2 ("Multi-call") with the UJ-8 device script (needs two real calls and a carrier that supports three-way calling) and the Diagnostics "Calls" procedure.

---

## Epic 16: Settings, SIM and local data

The owner opens one Settings page from a gear, tunes calls, sounds, haptics and display, picks a SIM on a dual-SIM phone, and exports or imports the app's own data. Single-SIM phones see no SIM UI at all.

### Story 16.1: Gear button, Settings page shell and S24 page push

As the owner,
I want a Settings page behind a gear in every tab title,
So that settings have a home without adding a fifth tab.

Covers: FR49, UX-DR29 (gear, Settings page), UX-DR30 (S24), UX-DR34, AD-36, UX-DR26, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the four tab titles
**When** the reserved trailing 44 px slot is filled
**Then** a flat glass round `LucideSettings` button (20 px) appears on Favorites, Recents, Contacts and Keypad (titles shrink by 56 px of available width only), refractive only while pressed, and the v0.1 Setup header button keeps its place
**And** the gear is absent on the In-Call and Incoming screens.

**Given** the gear
**When** tapped (finger-up)
**Then** the gear rotates 60 degrees on `glow` while held and settles, the Settings page enters from the right (translate 28 px with fade on `appear`, title parallax 12 px extra), the old page does not animate, the Tab Bar fades out, and Back (44 px button, system Back, edge swipe) reverses it
**And** reduced motion is a 120 ms fade with no rotation; a re-lock or a new call closes it (AD-3).

**Given** the page shell
**When** opened
**Then** it shows Back row, `large-title` "Settings" and an empty-section-aware grouped list (rows 58 px, section labels `footnote` 600, 16 px between groups, 35 px between sections) with the sections registered by later stories, a section appearing only when it has something to show
**And** the Diagnostics trigger moves from the Setup sheet title to a 800 ms long-press on the Settings title (the old trigger keeps working until the release after this story).

**Given** depth rules
**When** navigating
**Then** Settings is one level, sub-pages a second level, never a page over a sub-page, and sheets from Settings follow UX-DR34 Back order.

### Story 16.2: Settings store wiring and Kotlin mirror

As the owner,
I want my settings to apply immediately and also reach the parts of the app that run without the screen,
So that notifications, the proximity sensor and vibration respect what I chose.

Covers: FR49, FR71 (plumbing), AD-30, NFR-17, NFR-16. Prio: P1. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the 14.12 store
**When** a setting changes
**Then** `Backend::update_prefs` applies it to `Arc<Settings>`, observers re-render only what depends on it, and `rovsti_android::settings::sync(json)` pushes the `SettingsSync` struct on attach and after every change
**And** a failed save reverts the value and shows the Notice "Couldn't save setting".

**Given** `SettingsMirror.kt` (shared)
**When** it receives `settingsSync`
**Then** it stores the values in `SharedPreferences("rovsti_phone_settings")`, adds `flip_supported` (sensor present) and `system_keypad_tones` (`Settings.System.DTMF_TONE_WHEN_DIALING`), and `system_facts()` reports them back through the `permissions_changed` pull path
**And** before the first push the Kotlin defaults equal v0.1 behaviour; Rust remains the only writer of truth.

**Given** the one-shot mailbox
**When** `document_result`, `ringtone_result` or `ui_request` arrive while no sink exists
**Then** they wait in a Kotlin mailbox, one slot per kind, and are drained once on attach right after the snapshot (AD-5 order); the mailbox has a JVM test and a desktop `Unsupported` facade test.

**Given** a process restart
**When** the app attaches
**Then** settings persist and the mirror matches (completion notes record a restart check).

### Story 16.3: SIM accounts, `place_call(account_key)` and spike S5

As the owner,
I want the app to know my SIMs and place a call on the one I choose,
So that a dual-SIM phone is handled properly and a single-SIM phone is untouched.

Covers: FR50, FR11 (superseded), AD-26, AD-11 (amended), AD-36, NFR-16, S5. Prio: P1. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `SimAccounts.kt` and `telecom::phone_accounts()`
**When** called (runtime `READ_PHONE_STATE`)
**Then** it returns the 14.9 `PhoneAccounts` (`account_key = "{ComponentName.flattenToString()}#{handle.id}"`, label, `highlightColor`, slot from `SubscriptionManager.getActiveSubscriptionInfoList()` matched via `createForPhoneAccountHandle(h).subscriptionId`, `default_key` from `getUserSelectedOutgoingPhoneAccount()`, `ask = default_key == null && accounts >= 2`) and refreshes on resume, after each placement and on `sims_changed` (`OnSubscriptionsChangedListener` registered in `MainActivity.onStart`, removed in `onStop`)
**And** Rust calls `RovstiBridge.uiAttached(true)` once its session is live and `MainActivity` is resumed and `uiAttached(false)` when the session closes, and keeps `Arc<SimInfo>` with `SimInfo::visible()` = two or more accounts gating every SIM element (core unit tests incl. one SIM, two SIMs, missing default).

**Given** `telecom::place_call(raw, account_key: Option<&str>)`
**When** a key is given
**Then** `TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE` is added to the `placeCall` extras; emergency numbers never get a key; the app never calls `setUserSelectedOutgoingPhoneAccount`
**And** the registry safety net handles `STATE_SELECT_PHONE_ACCOUNT` from other apps: auto-select as v0.1 when `!uiReady` (keyguard locked, or Rust has not called `RovstiBridge.uiAttached(true)`) or after 10 s, otherwise report `dialing` with `selecting_account:true` and `accounts` and answer `callSelectAccount(call_id, key)` with `phoneAccountSelected(handle, false)`.

**Given** the Diagnostics "SIM" group (spike S5)
**When** run
**Then** it lists accounts (labels, slots, default, ask) and compares the last placed call's `account_key` with the key built from its new call-log row (`PHONE_ACCOUNT_COMPONENT_NAME`, `PHONE_ACCOUNT_ID`), and says "n/a" with one SIM
**And** `docs/spikes/s5-sim.md` records the procedure (place one call on each SIM), the default (hidden below two accounts; log chips omitted when keys do not match) and a PENDING table.

**Given** a single-SIM phone
**When** the app runs
**Then** nothing visible changes and v0.1 call placement is byte-for-byte the same path.

### Story 16.4: SIM chooser and S26 SIM chip flight

As the owner,
I want to choose the SIM when the phone asks, or whenever I long-press a call button,
So that I never call from the wrong line.

Covers: FR50, UX-DR29 (SIM chooser), UX-DR30 (S26), UX-DR26, UX-DR27, AD-26, NFR-14, NFR-15, NFR-16. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `ask == true` (two or more SIMs, default "ask every time")
**When** Call is tapped on the Keypad, a Recents row call action, a Contact Sheet number or a Favorites tile
**Then** the SIM chooser sheet (medium detent, "Call with", rows 64 px with a 12 px slot-colour dot, "SIM 1" `headline`, carrier and number `footnote`, "Default" `caption` where applicable, final accent row "Change default SIM" when the system page exists) rises before placing, moment S3 does not play yet
**And** finger-up on a row places the call with that `account_key` and plays S26; cancelling (drag down, outside tap, Back) places nothing and keeps the number in the field.

**Given** a default exists
**When** a Call button is long-pressed for 450 ms (light haptic)
**Then** the same chooser opens with the default row captioned, on the Keypad, Recents call action, Contact Sheet number call circle and the Number chooser
**And** emergency numbers never show the chooser (FR-68) and an unavailable account row is dimmed "Unavailable".

**Given** one SIM
**When** any of these surfaces is used
**Then** the chooser never appears and long-press on Call does nothing extra (P-18)
**And** S26 sends the chosen row's dot and label as a chip to the In-Call chip slot on `morph` (or just fades when the screen is not ready), the other row fading with the sheet; reduced motion is a cross-fade.

### Story 16.5: SIM chips in the In-Call Screen and Recents

As the owner,
I want to see which SIM a call used,
So that I recognise work and personal calls at a glance.

Covers: FR51, UX-DR29 (chip, chip row), UX-DR27, AD-26, NFR-14. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `SimInfo::visible()`
**When** true
**Then** the In-Call chip row shows the SIM chip first (6 px dot clamped to contrast at least 3:1 against the chip fill, text always `label`, "SIM 1" or carrier label max 8 characters with ellipsis) and each Recents row shows it at the trailing end of the subtitle line in a reserved 36 px slot
**And** with one SIM, or when the log row's `account_key` matches no account, the slots stay empty and nothing shifts (P-18).

**Given** the Recents pull
**When** it carries `account_key`
**Then** the chip is resolved through a pure `rovsti_core` lookup built once per call-log swap, never per frame
**And** TalkBack reads "Call on SIM 1, {carrier}" and chips are not interactive nor the only carrier of meaning.

**Given** tests
**When** run
**Then** a core test covers the clamp function and the label shortening, and a desktop preview toggle shows 1-SIM and 2-SIM states.

### Story 16.6: Backup and restore of local data and spike S13

As the owner,
I want to export my app's own data to a file and import it back,
So that I can move or recover my settings, speed dial, replies and Favorites order.

Covers: FR73, AD-30, NFR-17, NFR-16, S13. Prio: P1. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `Documents.kt` (shared) and `rovsti_android::documents`
**When** Export runs
**Then** `exportDocument(name, json)` uses a pending slot and `ACTION_CREATE_DOCUMENT` (`application/json`, name `rovsti-phone-backup-{yyyy-mm-dd}.json`), `MainActivity` forwards `onActivityResult` to `RovstiBridge.handleActivityResult`, and the result arrives as the `document_result` event through the mailbox; no storage permission is used
**And** success shows the Notice "Saved to {file name}".

**Given** Import
**When** `importDocument()` (`ACTION_OPEN_DOCUMENT`, json/plain/octet-stream) returns text (read on a worker thread, at most 256 KB)
**Then** `rovsti_ui::storage` parses it outside core, `ImportPreview` (14.11) feeds the Import preview sheet (large detent) "This will replace: settings (n), favorites order (n), speed dial (n), quick replies (n)." with "Import" accent and "Cancel", and one line says it cannot be undone
**And** an invalid or foreign file (over 256 KB, other kind, bad `v`) shows the inline red row "This isn't a Rovsti Phone backup." and changes nothing; a duplicated speed-dial digit shows "{digit} moved from {name}".

**Given** the Data section
**When** rendered
**Then** it lists Export favorites and settings, Import, and a footnote that contacts, call log and blocked numbers are not included; "Delete all call history" is added by 21.3
**And** a round-trip core test exports, wipes and imports and compares the model.

**Given** spike S13
**When** the Diagnostics "Backup" check is run
**Then** it exports a small file and imports it back via the pickers and prints pass/fail; `docs/spikes/s13-saf.md` records the default fallback ("Copy backup" and "Paste backup" through the clipboard, built only if the pickers fail) and a PENDING table.

### Story 16.7: Settings controls (S25 Toggle snap) and the Calls, Sounds and haptics, Display sections

As the owner,
I want tidy toggles and segmented controls for how the app feels,
So that I can tune it and see the change at once.

Covers: FR49, FR71, FR72, UX-DR29 (toggle, segmented), UX-DR30 (S25), UX-DR26, UX-DR32, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the toggle (51 x 31, thumb 27 white, track `fill` off and accent on, whole 58 px row is the 44 px+ target)
**When** pressed
**Then** the thumb stretches to 1.2x width on `press` on the raw touch, travels on `select` (ζ 0.76, k 750) with slight overshoot on finger-up while the track mixes grey to accent, a light haptic follows the Haptics setting, and the segmented control moves its glass indicator on the same spring
**And** reduced motion jumps the thumb and track.

**Given** the Calls section
**When** shown
**Then** it has Proximity screen-off (default on), Flip to silence (default off, footnote "Face down while ringing", dimmed with "Not supported on this phone" if `flip_supported` is false), Vibrate when call connects, Vibrate when call ends
**And** Sounds and haptics has Keypad tones (default follows the system), Haptics segmented Off / Light / Normal, and "Ringtone and sounds" opening `ACTION_SOUND_SETTINGS` through `open_system`.

**Given** the Display section
**When** shown
**Then** it has Sort by (First name / Last name), Name format (First Last / Last, First), a live preview line "Ayşe Yılmaz" and a read-only "Theme: Follows system"
**And** every control applies without restart, defaults reproduce v0.1, and each row's TalkBack text matches EXPERIENCE ("{title}, on/off. Double tap to toggle", "{title}: {selected}, {n} options").

### Story 16.8: Settings take effect: proximity, keypad tones, vibration, haptic filter

As the owner,
I want the toggles to actually change the call behaviour,
So that a setting is never decoration.

Covers: FR71, AD-29, AD-30, NFR-15, NFR-16, S14 (vibration part). Prio: P1. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the mirror
**When** `proximity` is false
**Then** `ProximityController` does not acquire the proximity wake lock; when true it behaves as in v0.1
**And** keypad tones: when the effective value (setting, else `system_keypad_tones`) is true, Kotlin plays `ToneGenerator` on `STREAM_DTMF` for in-app keys and the in-call keypad, never for the app's UI clicks (the one sanctioned app-played sound, noted in the UX amendments).

**Given** `Haptics{Off,Light,Normal}`
**When** any Rust tick fires
**Then** the core filter decides (Off silences the app's own ticks and never the system ringer; Light allows tick-class kinds only), including S25, key presses and all UIQ-1 ticks
**And** Kotlin `Haptics.kt` plays `VibrationEffect.createPredefined(EFFECT_CLICK)` (API 29) on the first Active transition (vibrate_connect) and on the foreground disconnect (vibrate_end) only when the mirror says so; `VIBRATE` comes from 14.15.

**Given** spike S14 (vibration part)
**When** the Diagnostics "TalkBack/system" group is extended with "Vibrate test"
**Then** the owner can place a call against the ear and feel the connect vibration with the screen off; the result goes to `docs/spikes/s14-system.md` (PENDING table), with the default (vibrate options stay off, Settings footnote "May not work with the screen off") 
**And** unit tests cover the haptic filter and the tone-effective-value rule.

### Story 16.9: Name order and sort across the app

As the owner,
I want Contacts, Favorites, Recents and the call screen to use the name order I picked,
So that the app reads names my way everywhere.

Covers: FR72, AD-32, UX-DR7, NFR-18. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `names::display`/`sort_key` (14.13)
**When** the setting changes
**Then** the Contacts index re-sorts once (not per frame) off the UI thread and swaps via Arc, Favorites, Recents titles, the Number chooser, the Contact Sheet title and the In-Call name (using `display_name_alt` from the call snapshot) all update immediately
**And** the Contacts sections (sticky headers and "#" bucket) follow the chosen sort key.

**Given** the call snapshot carries `display_name_alt`
**When** the format is last-first
**Then** the In-Call Screen shows it and falls back to `display_name` when absent; a contact whose alt equals primary changes nothing.

**Given** the HyperOS provider
**When** the Diagnostics "Contacts" group prints the sort/alternative columns for 5 sample contacts (S11 part, details in 19.2)
**Then** the display option stays visible unless alt equals primary for every contact, in which case the Display rows are dimmed with a footnote.

### Story 16.10: System shortcuts section and hearing-aid compatibility value

As the owner,
I want one place to jump to the system pages calls depend on,
So that forwarding, accessibility and default apps are a tap away.

Covers: FR49, FR43 (HAC value), AD-34, AD-31, NFR-16, S10 (row gating). Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the System shortcuts section
**When** shown
**Then** it has Call forwarding and waiting (`TelecomManager.ACTION_SHOW_CALL_SETTINGS`), Accessibility (TTY and RTT via `ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS`, else `ACTION_ACCESSIBILITY_SETTINGS`), Default apps (`ACTION_MANAGE_DEFAULT_APPS_SETTINGS`), a read-only "Hearing-aid compatibility: Supported / Not reported" from `hearing_aid_compatible()`, and an "Emergency information" row only when `resolveActivity` for `android.telephony.action.EMERGENCY_ASSISTANCE` is non-null (the row stays hidden until 20.4's S10 result)
**And** each row opens through `open_system`, falling back to app details with the Notice "Opened app settings instead".

**Given** the desktop preview
**When** rows render
**Then** they say "Not available on this platform" in tertiary-label and are inert.

**Given** the SIM section
**When** `SimInfo::visible()`
**Then** it shows "Default for calls" (value "Ask every time" or "SIM 1") opening `ACTION_CHANGE_PHONE_ACCOUNTS`; with fewer than two SIMs the section is absent
**And** the last Epic 16 story ends with `bash scripts/build-release.sh`, reporting the path as owner release 3 ("Settings, SIM, backup") with the UJ-10 device script and the Diagnostics SIM/Backup procedures.

---

## Epic 17: Blocking

The owner can block a number in two taps from Recents, from Call details and the Contact Sheet (as those arrive), with Undo, manage the list in Settings, and review blocked calls, all through Android's own blocked-numbers store.

### Story 17.1: Kotlin `Blocklist`, `BLOCK_GUARD` and spike S6

As the owner,
I want blocking to use the system block list and a safety net until my phone proves it enforces it,
So that a blocked number never rings, whichever way HyperOS behaves.

Covers: FR53, FR54, AD-27, AD-31, AD-36, NFR-16, S6. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE, CARGO.

**Acceptance Criteria:**

**Given** shared Kotlin `Blocklist` and `rovsti_android::blocklist`
**When** called
**Then** `canBlock()` = `BlockedNumberContract.canCurrentUserBlockNumbers`, `list()` reads `BlockedNumbers.CONTENT_URI` (`COLUMN_ID`, `COLUMN_ORIGINAL_NUMBER`, `COLUMN_E164_NUMBER`), `block(raw)` inserts `COLUMN_ORIGINAL_NUMBER` (a duplicate replaces), `unblock(raw)` is `BlockedNumberContract.unblock`, `isBlocked(raw)` is `BlockedNumberContract.isBlocked`, each returning the AD-31 envelope and never throwing; a `SecurityException` maps to `unavailable` when `!canBlock()`, else `permission`
**And** emergency numbers are refused by Kotlin (`refused`), and every `rovsti_android::blocklist` function returns `Error::Unsupported` on desktop with a test.

**Given** `Caps.BLOCK_GUARD = true`
**When** a ringing call arrives
**Then** `isBlocked(number)` runs on `lookupExecutor` (never on the main Looper, never delaying the notification), and on a hit the main Looper receives `reject(false, null)`, cancels the notification and any raise
**And** a Kotlin unit test with a fake provider proves the notification is not delayed when the lookup is slow.

**Given** one `ContentObserver` on `BlockedNumbers.CONTENT_URI`
**When** it fires
**Then** it emits `blocked_changed` debounced 300 ms with the other AD-9 observers.

**Given** the Diagnostics "Blocking" group (spike S6)
**When** run on a number the owner types
**Then** it blocks it, prints `isBlocked`, and after the second phone calls prints whether a type-6 row appeared and whether the call rang (the owner marks the ring/no-ring answer), then unblocks it on request; the owner also checks whether Xiaomi's own blocked list shows it
**And** `docs/spikes/s6-blocklist.md` records the procedure, the pass criterion (a call from the second phone does not ring and a type-6 row appears), the shipped default (`BLOCK_GUARD = true`, footnote "Xiaomi's own block list may be separate") and a PENDING table.

### Story 17.2: Blocked state in the app: Recents exclusion, badge and pulls

As the owner,
I want blocked calls to stay out of Recents and the missed badge,
So that blocking really makes a number disappear from my daily view.

Covers: FR53, FR55, FR26 (refined), AD-27, AD-9 (amended), NFR-4. Prio: P1. Verify: CARGO, ANDROID-CHECK, BUILD.

**Acceptance Criteria:**

**Given** the Recents pull
**When** run
**Then** it adds `type != 6` (limit 500 intact), `missed_badge` ignores blocked rows, and a `kind == blocked` row received by an older path is still hidden
**And** `pullBlockedCalls(200)` returns type-6 rows with `block_reason` mapped from `CallLog.Calls.BLOCK_REASON` (API 29: `BLOCK_REASON_BLOCKED_NUMBER` to `user`; `CALL_SCREENING_SERVICE`, `DIRECT_TO_VOICEMAIL`, `NOT_IN_CALL`, `PAY_PHONE`, `RESTRICTED_NUMBER`, `UNKNOWN_NUMBER` to `system`; anything else `unknown`).

**Given** `Backend`
**When** `blocked_changed` arrives
**Then** it re-pulls the list off the UI thread, swaps an `Arc<BlockSet>` (14.13) and exposes `is_blocked`, `can_block` and the blocked rows; an empty number never queries history
**And** golden fixtures for the blocklist rows and blocked-call rows (14.9) are exercised and a Kotlin JVM test covers the reason mapping.

**Given** v0.1 Recents behaviour
**When** nothing is blocked
**Then** list, grouping, badge and scroll position are unchanged (existing tests pass).

### Story 17.3: Recents row menu and the Block flow with S27

As the owner,
I want to long-press a Recents row and block the number with Undo,
So that spam is gone in two taps and a mistake is reversible.

Covers: FR53, FR70 (menu host), UX-DR29 (context menu), UX-DR30 (S27, S28), UX-DR26, UX-DR27, UX-DR32, AD-27, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** a Recents row
**When** long-pressed for 450 ms (light haptic)
**Then** a context menu (glass sheet-class, 14 radius, 220 px wide, rows 48 px) opens beside the row, anchored so it never covers the pressed row, with the page behind dimmed 0.85, listing Copy number, Message, Block number (or Unblock), and a reserved place for Call details (21.1) and Delete entry (21.2)
**And** the info control still opens the Contact Sheet or number sheet for now, and tapping outside dismisses the menu with no action.

**Given** Block number
**When** tapped (finger-up only, no confirmation dialog because it is reversible)
**Then** `Backend::block(raw)` runs off the UI thread, S27 plays (a ring-and-slash draws over the avatar in 260 ms, the avatar desaturates, the row fades in 120 ms and the list re-lays instantly) and the Notice pill reads "Blocked {number or name}" with Undo for 5 s (10 s with TalkBack)
**And** Undo unblocks the same raw string and restores the row; Unblock shows "Unblocked {number}" with Undo.

**Given** blocking is unavailable (`canBlock() == false`), the number is empty/private, or the number is an emergency number
**When** the menu opens
**Then** Block is disabled, not hidden, with the subtitle "Blocking isn't available for this user" (or "Unknown number, cannot block")
**And** reduced motion removes the sweep (a 120 ms row fade only).

**Given** TalkBack
**When** focused
**Then** the row announces "{number}. Actions: Call details, Copy, Message, Block" through custom actions, the Block action reads "Block {number}. Double tap to block", and the Undo pill is assertive with focus on Undo.

### Story 17.4: Block from the Incoming screen after a call and the Blocked glyph

As the owner,
I want to block a caller right after a call I did not want,
So that I never need to find the number again.

Covers: FR53, UX-DR16 (extended), AD-27, NFR-14, NFR-16. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** an incoming call that ended (missed, declined or short)
**When** the "Call ended" hold finishes and the app returns to the tabs
**Then** the Notice pill offers "Block {name or number}" as its trailing action for 5 s (10 s with TalkBack) for a non-emergency caller with a number; there is deliberately no Block control while the call rings, so a mistap cannot confuse Decline and Block
**And** tapping it (finger-up) runs the 17.3 Block flow.

**Given** a number that is blocked
**When** it appears in the Contact Sheet number rows
**Then** a `LucideBan` glyph (14 px, secondary-label) follows the number and the tile/menu entry reads Unblock; no badge and no notification for blocked callers (FR-55)
**And** the glyph and the word carry the state (never colour alone).

**Given** the lock screen
**When** `keyguard_locked`
**Then** the post-call Block offer is not shown over the keyguard (call-only mode) and the Settings/Contact Sheet surfaces stay closed.

### Story 17.5: Blocked numbers page in Settings

As the owner,
I want to see, add and remove blocked numbers in Settings,
So that I control the whole list in one place.

Covers: FR54, UX-DR29 (blocked list), UX-DR26, UX-DR27, UX-DR34, AD-27, NFR-18, NFR-16. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Settings section "Blocked numbers"
**When** shown
**Then** the row displays the count and a chevron to a second-level page (large title "Blocked numbers", segmented control "Numbers | Blocked calls", an add field in the search-field shape with `LucideBan`, placeholder "Add a number to block" and a trailing "Block" accent button), disabled with the explanation row "Blocking isn't available for this user" when `canBlock()` is false
**And** the Settings row shows an inline "Allow call log access" row with a "Grant access" pill when the permission is missing.

**Given** the Numbers view
**When** loaded
**Then** a virtualised list of 58 px rows shows a `LucideBan` tile, the contact name if the number matches (else the formatted number), the subtitle number or "Blocked number" and a trailing "Unblock" glass pill (36 px, press 0.94, acts on finger-up, pill "Unblocked {number}" with Undo 5 s); insertions fade in with no layout spring and removals fade out then re-lay instantly
**And** 500 rows load within 1 s (NFR-4) with a skeleton after 150 ms.

**Given** the add field
**When** a value is entered
**Then** it is validated with the FR-9 dial-string rules, invalid input shows "Not a valid number" inline, emergency numbers are refused, "Block" (finger-up) inserts it at the top with a fade and the pill "Blocked {number}"
**And** states: empty "No blocked numbers" with "Block spam from Recents or by typing a number above.", error "Couldn't read blocked numbers" with "Try again"; reduced motion has no sweep.

### Story 17.6: Blocked calls view and reasons

As the owner,
I want to review which calls were blocked and why,
So that I can trust blocking and undo mistakes.

Covers: FR55, UX-DR29, UX-DR27, AD-27, NFR-18. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the "Blocked calls" segment
**When** opened
**Then** rows (58 px, virtualised) show the number or contact name, time (`footnote`) and the reason in the subtitle ("Blocked by you" / "Blocked by the system", absent if unknown); rows have no call action and a tap currently opens the existing number sheet (Call details replaces it in 21.1)
**And** the empty state reads "No blocked calls" and the permission-denied state follows the call-log pattern.

**Given** the S6 result is not yet recorded
**When** the page renders
**Then** a footnote at the bottom reads "Xiaomi's own block list may be separate" (removed by 22.6 only if S6 proves the lists are shared).

**Given** this is the last Epic 17 story
**When** the story ends
**Then** `bash scripts/build-release.sh` runs and the path is reported as owner release 4 ("Blocking") with the UJ-9 device script (block a test number, call it from another phone, check Recents and the badge) and the Diagnostics "Blocking" procedure.

---

## Epic 18: Incoming call controls

The owner can decline with a message, silence the ringer without declining, and answer or decline by dragging the caller card, all with the buttons still working and the Incoming screen's layout unchanged.

### Story 18.1: Reject path in Kotlin and spike S7

As the owner,
I want declining with a text to either really send it or honestly open Messages,
So that I never think a text was sent when it was not.

Covers: FR56, AD-28, AD-6 (amended), AD-36, NFR-16, NFR-17, S7. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE, CARGO.

**Acceptance Criteria:**

**Given** `callRejectWithMessage(call_id, text)`
**When** called
**Then** it requires `caps.respond_via_text` and a non-empty text of at most 160 characters; with `Caps.REJECT_PATH == TELECOM` (set only after S7 passes) it calls `reject(true, text)`; with the shipped default `HANDOFF`, or per call when the capability or the account's subscription is invalid, it calls `reject(false, null)` and starts `ACTION_SENDTO smsto:{number}` with `sms_body`
**And** it returns an Int (0 stale, 1 sent, 2 opened, 3 rejected_only) and the call is rejected exactly once on every path; a caller without a number hides Message (hidden-but-reserved).

**Given** `callRejectAndCompose(call_id)`
**When** called
**Then** it is `reject(false, null)` plus an empty `smsto:` compose for "Write your own..."
**And** no SMS permission is declared or requested (`SEND_SMS` stays absent, checked by `check-apk.sh`).

**Given** `rovsti_android::calls::{reject_with_message, reject_and_compose}`
**When** compiled for desktop
**Then** they return `Error::Unsupported` with tests, and a Kotlin JVM test covers the result-code mapping and the 160-character guard.

**Given** the Diagnostics "Reject text" group (spike S7)
**When** the owner uses it with the second phone calling
**Then** it rejects with "test" through the TELECOM path once (temporary override scoped to the probe) and asks the owner to confirm whether the SMS arrived within 30 s, noting the SIM and cost
**And** `docs/spikes/s7-reject-text.md` records the procedure, the pass criterion, the shipped default `HANDOFF` and a PENDING table (22.6 flips `REJECT_PATH` on pass).

### Story 18.2: Reply sheet and S31 Reply sent

As the owner,
I want to decline a ringing call with a ready-made text in two taps,
So that I can handle a meeting without picking up.

Covers: FR56, UX-DR29 (reply sheet, incoming secondary controls), UX-DR30 (S31), UX-DR26, UX-DR27, UX-DR32, AD-28, NFR-14. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Incoming Call Screen
**When** laid out
**Then** the secondary row (Message left, Silence right, 56 px flat-glass circles, 24 px apart, captions below) sits above Decline/Answer in reserved slots: the Message and Silence positions exist even when one is hidden, and the avatar, name, Decline and Answer positions are identical to v0.1 (an overlay test asserts it); 0 refractive surfaces at rest
**And** with no quick replies or a caller without a number the Message slot is hidden-but-reserved.

**Given** Message
**When** tapped (finger-up)
**Then** the reply sheet (medium detent, title "Decline with message", 56 px rows with `LucideMessageCircle`, text up to two lines, last row "Write your own…" in accent) opens over the ringing screen with one refractive surface once settled; the call keeps ringing, dragging the sheet away or Back closes it and returns to the ringing card
**And** choosing a reply (finger-up) calls `Backend::reject_with_message(id, text)`, plays S31 (the chosen text becomes a flat bubble that travels to the avatar and fades, the sheet dismisses on `sheet_dismiss`), and the card label reads "Sent", "Opened Messages" or "Declined" for 1.2 s from the Int result before the S11 fade.

**Given** "Write your own…"
**When** tapped
**Then** `reject_and_compose` runs, Messages opens with an empty body and the label reads "Opened Messages".

**Given** TalkBack
**When** focused
**Then** the sheet announces "Decline with message, {n} replies", rows read "{text}. Double tap to decline and send", and the focus order is Answer, Decline, Message, Silence
**And** reduced motion is a fade with no travel; a finger sliding off a reply row releases it without acting.

### Story 18.3: Quick replies editor in Settings

As the owner,
I want to write, reorder and reset my quick replies,
So that the texts I send are mine.

Covers: FR57, UX-DR29, UX-DR26, UX-DR32, AD-30, NFR-17, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Settings row "Quick replies" (count, chevron)
**When** opened
**Then** a second-level page lists up to 6 rows of text fields (max 100 characters, counter appears at 80) each with a trailing `LucideGripVertical` handle; dragging the handle reorders with the list reflowing by transform on `select`; a row is cleared with a trailing "Clear" when focused; "Add reply" is disabled at 6 with "Up to 6 replies"; "Reset to defaults" (danger text) restores the three defaults after a confirm sheet
**And** the editor autosaves through `update_prefs` (coalesced 250 ms) and an empty list shows the footnote "With no replies, Message is hidden".

**Given** the three defaults
**When** first run or reset
**Then** they are "Can't talk now. I'll call you back.", "I'm in a meeting. Call you later.", "Can you text me instead?" (constants in `rovsti_core::settings`) and core validation clamps length and count
**And** `has_replies` reaches the Kotlin mirror so the notification action in 18.7 can appear or not.

**Given** TalkBack
**When** a row is focused
**Then** custom actions "Move up" and "Move down" replace the drag (UIQ-4), and font scale 1.3x wraps the field without clipping.

### Story 18.4: `silenceRinger`, `FlipDetector` and spike S8

As the owner,
I want a ringing call silenced from the app, or by turning the phone over, while it stays answerable,
So that I can quiet it without rejecting.

Covers: FR58, AD-29, AD-36, NFR-16, S8. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE, CARGO.

**Acceptance Criteria:**

**Given** `callSilenceRinger()`
**When** called
**Then** it is `TelecomManager.silenceRinger()`; the registry sets per-call `ringer_silenced` and `silenced_by: user|volume|flip` after the command and when `InCallService.onSilenceRinger()` fires, and clears both when the call leaves Ringing; a `SecurityException` returns false and Rust hides the control (slot reserved) for the session
**And** the call stays answerable and rings out as missed if untouched.

**Given** `FlipDetector` (Kotlin, ProximityController pattern)
**When** a call is ringing, not yet silenced and the mirror's `flip_to_silence` is true
**Then** it registers `Sensor.TYPE_GRAVITY` (fallback `TYPE_ACCELEROMETER`) at `SENSOR_DELAY_NORMAL`, triggers when z < -7.0 m/s2 on two consecutive samples after at least one sample at or above -3.0 (a phone already face down at ring start never silences), calls `silenceRinger()` once, plays the flip haptic and unregisters; every exit path unregisters
**And** no sensor reports `flip_supported:false`; a JVM test with a fake sensor feed covers the trigger rule and the exit paths; P-22 confirms the listener exists only while ringing.

**Given** `rovsti_android::calls::silence_ringer`
**When** compiled for desktop
**Then** it returns `Error::Unsupported` with a test.

**Given** the Diagnostics "Silence" group (spike S8)
**When** the second phone calls three times
**Then** the owner taps Silence, presses a volume key, and flips the phone face down (once with the screen locked), and the report prints per attempt whether the ringer stopped, `ringer_silenced`/`silenced_by`, and `onSilenceRinger` firing
**And** `docs/spikes/s8-silence.md` records the procedure, the pass criterion, the default (Silence control hidden-but-reserved if refused; flip cut as P3) and a PENDING table.

### Story 18.5: Answer and decline gestures (S29) and spike S14

As the owner,
I want to drag the caller card to answer, decline or reply, with the buttons untouched,
So that answering feels physical without risking a mistap.

Covers: FR59, UX-DR30 (S29), UX-DR26, UX-DR32, AD-29, NFR-15, NFR-10, S14. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Incoming Call Screen
**When** the card (avatar, name, number) is dragged
**Then** it follows the finger directly with 0.9 gain and no spring while the finger is down, rubber-banded beyond the commit distance; right grows an answer-colour wash (alpha 0 to 0.35) and an Answer bubble 0.6 to 1.2, left the same in danger with Decline, up pulls the reply sheet under the finger (detent medium); a light haptic tick at 50% and a medium one at 96 px
**And** the buttons never move and keep working as taps on finger-up.

**Given** a release
**When** the drag is past 96 px or a fling above 600 px/s in a direction
**Then** right runs the same `answer` command as the button with the Answer morph (moment S8), left sends Decline first then the card flies off with fade on `sheet_dismiss`, up opens the reply sheet; anything shorter springs back on `release` with no action
**And** 30 drags in each direction are logged in the completion notes with no extra frame of latency (P-16).

**Given** first use
**When** the first three incoming calls show
**Then** a `footnote` hint under the number reads "Drag the card to answer or decline" (a local counter in `ui.hint_counters`)
**And** reduced motion removes the follow effect and wash but a drag past 96 px still commits; TalkBack users get the buttons and the focus order Answer, Decline, Message, Silence, with no gesture needed.

**Given** the Diagnostics "TalkBack/system" group (spike S14)
**When** run
**Then** it reports TalkBack state, `DTMF_TONE_WHEN_DIALING`, and whether announcement/custom-action probes fire, and the owner runs the route, conference, reply and reorder flows with TalkBack on
**And** `docs/spikes/s14-system.md` (started in 16.8) gains the TalkBack procedure and PENDING rows, with the default that gestures keep their buttons and hold-to-delete becomes a button.

### Story 18.6: Silence control and S30 Silence hush

As the owner,
I want a Silence button on the ringing screen that visibly hushes the call,
So that the phone quiets down and I still see who is calling.

Covers: FR58, UX-DR29 (incoming secondary controls), UX-DR30 (S30), UX-DR26, UX-DR27, AD-29, NFR-14, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Silence slot (56 px flat circle, `LucideBellOff`, caption "Silence")
**When** tapped (finger-up, press 0.94 on touch)
**Then** `Backend::silence_ringer()` runs, rings in flight finish and fade, the avatar breath eases to rest, aura saturation drops 40% on `select`, the Answer glow pulse stops, the control floods with accent tint (S10) and its caption becomes "Silenced" (or "Silenced by flip" when `silenced_by == flip`, with a medium haptic), and the control is disabled
**And** the call stays fully answerable and the state comes from the snapshot's `ringer_silenced`.

**Given** the platform refuses silencing (a `false` return or S8 failed)
**When** the control would render
**Then** it is hidden-but-reserved (a blank slot) and the volume key (FR-21) still works
**And** the Flip to silence toggle in Settings (16.7) is live and dimmed with the platform reason when refused; reduced motion makes the hush instant (UIQ-4).

**Given** TalkBack
**When** the control is focused
**Then** it reads "Silence ringer" and after use "Silenced" with a polite announcement.

### Story 18.7: Heads-up notification Message action and Waiting card wiring

As the owner,
I want the Message option on the heads-up notification and the Waiting card,
So that declining with a text is available everywhere a call can ring.

Covers: FR56, FR48 (Message), AD-2 (amended), AD-28, NFR-6, NFR-16. Prio: P1. Verify: ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the incoming CallStyle notification
**When** the mirror holds at least one quick reply (`has_replies`)
**Then** a third action "Message" (an activity PendingIntent to `MainActivity` with extra `reply_sheet_call_id`, never a broadcast) is added; Answer and Decline stay registry-only and need no Rust
**And** opening it raises `MainActivity`, the `ui_request` one-shot event (via the mailbox) opens the Incoming screen with the reply sheet already open, and the call keeps ringing until a reply is chosen.

**Given** the Waiting card (15.8)
**When** replies exist
**Then** the Message pill opens the reply sheet over the call, and choosing a reply rejects the waiting call with that text via the same command (the call underneath is untouched); with no replies the pill is dimmed with "Add replies in Settings"
**And** the native fallback curtain (if ever enabled) offers Answer and Decline only and is not extended.

**Given** this is the last Epic 18 story
**When** the story ends
**Then** `bash scripts/build-release.sh` runs and the path is reported as owner release 5 ("Incoming controls") with the UJ-3/UJ-9 device script (decline with a message from the Incoming screen, notification and Waiting card; silence by button, volume key and flip) and the Diagnostics Reject text/Silence/TalkBack procedures.

---

## Epic 19: Contacts management and quick actions

The owner can add a number to Contacts, delete a contact, use quick actions (Message, Copy, Share, Block) from the Contact Sheet, reorder Favorites by dragging and see a Frequent row. Editing stays with the system editor unless the P3 stretch story is built.

### Story 19.1: Add to contacts on every surface

As the owner,
I want "Add to contacts" wherever I see an unsaved number,
So that saving someone takes one tap and the app updates itself afterwards.

Covers: FR60, UX-DR9, AD-31, NFR-16, NFR-4. Prio: P2. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Recents row menu, the number sheet, and the Keypad (a text button "Add to contacts" in the status-line slot, shown when the field holds a valid number with no match)
**When** tapped (finger-up)
**Then** `Backend::add_contact(number)` (14.4) opens the system insert-or-edit screen prefilled, where the owner chooses create-new or add-to-existing; Call details gets the same action in 21.1
**And** a missing contacts app shows the Notice "No contacts app found"; the Keypad status line slot keeps its height so the keys do not move.

**Given** the owner returns
**When** the existing contacts observer fires
**Then** lists refresh within 2 s (as FR-29), the originating row or sheet cross-fades to the new name, and if nothing changed nothing happens
**And** the lock screen never offers the action (call-only mode).

**Given** permissions
**When** contacts access is missing
**Then** the action is shown with the standard "Grant access" path rather than failing silently.

### Story 19.2: Delete a contact and spike S11

As the owner,
I want to delete a contact with a clear confirmation,
So that I can tidy my address book without leaving the app.

Covers: FR61, FR63 (ringtone gate), FR72 (name order check), UX-DR26, UX-DR32, AD-31, AD-34, NFR-16, S11. Prio: P2. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `ContactsWrite.delete(lookup_key)` (shared Kotlin, AD-31 envelope)
**When** called after the runtime `WRITE_CONTACTS` group `ContactsWrite` is requested at first use (never at startup; the dialer role normally grants it)
**Then** it deletes `Contacts.CONTENT_LOOKUP_URI/{lookup_key}` (all raw contacts; synced accounts remove it at next sync), returns `permission` with no side effect when not granted, and `rovsti_android::providers` mirrors it with desktop `Unsupported` tests
**And** it never runs on the UI thread or in render code.

**Given** the Contact Sheet
**When** "Delete contact" (danger text, last row) is tapped (finger-up)
**Then** the row morphs in place (`morph`, 58 to 112 px, sheet content scrolls if needed) into "Delete {name}?" with the body "This removes the contact from this phone and all apps.", Cancel (glass) and Delete (danger, finger-up only, medium haptic)
**And** on success the sheet dismisses, the contact leaves Contacts and Favorites at once (rows fade, list re-lays instantly), and the Notice pill says "Deleted {name}" for 3 s with no Undo (UX-Q18); an error shows inline "Couldn't delete {name}" and the contact stays; missing permission dims the row with "Allow contacts editing" and a "Grant access" pill.

**Given** the Diagnostics "Contacts" group (spike S11)
**When** run
**Then** it creates a throwaway contact, writes `CUSTOM_RINGTONE` (the owner confirms the second phone's call rings with that tone), deletes the contact, and prints the sort-key and alternative-name columns for sample contacts ("display_name_alt is family-first: pass/fail")
**And** `docs/spikes/s11-contacts.md` records the procedure, the defaults (Ringtone row absent via `Caps.CONTACT_RINGTONE = false`; delete falls back to the system Contacts app if refused; the name-format option hidden if alt equals primary everywhere) and a PENDING table.

### Story 19.3: Quick actions strip, Share and long-press copy

As the owner,
I want one strip of quick actions on a contact or number,
So that Message, Copy, Share and Block are one tap away.

Covers: FR63, FR53, UX-DR29 (action tile), UX-DR26, UX-DR27, UX-DR32, AD-31, NFR-14, NFR-15. Prio: P2. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Contact Sheet and the number sheet
**When** the action strip renders
**Then** it is five reserved 64 x 64 flat-glass tiles with 12 px gaps (5 x 64 + 4 x 12 within the 370 px content width), filled left to right: Message, Copy, Share, Block; the fifth slot is the Details tile, filled by 21.1 (empty and non-interactive until then, so the strip never shifts when it arrives)
**And** tiles stagger in with the S16 pattern, press to 0.94 on touch and act on finger-up.

**Given** the actions
**When** used
**Then** Message opens the default messaging app to the contact's default number (the Number chooser when several and no default), Copy copies the default number (chooser when several) and shows the Notice "Copied", Share sends the number as text through the system share sheet, Block runs the 17.3 flow (per number: the tile opens the Number chooser first when a contact has several) and reads Unblock when blocked
**And** a destructive or call-affecting tile never acts on finger-down.

**Given** any displayed number in a sheet
**When** long-pressed for 450 ms
**Then** it copies with a light haptic and the Notice "Copied"
**And** TalkBack labels are "Message {name}", "Copy number", "Share contact", "Block {name}"; at font scale 1.3 tiles keep 64 px and labels wrap to two lines inside (tile height grows to 76 at 1.3x and above).

### Story 19.4: Contact ringtone row (gated by spike S11)

As the owner,
I want to give a contact its own ringtone,
So that I know who is calling before I look.

Covers: FR63, AD-31, AD-36, NFR-16, UX-Q14, S11. Prio: P2. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `Caps.CONTACT_RINGTONE`
**When** false (shipped default)
**Then** the Contact Sheet shows no Ringtone row and none of this code is reachable from the UI
**And** when the S11 result flips it to true (22.6) the row appears without further code changes.

**Given** the row is enabled
**When** "Ringtone: Default" is tapped
**Then** `RingtoneManager.ACTION_RINGTONE_PICKER` runs through the activity-result path (`ringtone_result` via the mailbox), `ContactsWrite.setRingtone(contact_id, uri|null)` updates `Contacts.CONTENT_URI/{id}` `CUSTOM_RINGTONE` with `WRITE_CONTACTS`, and the current value is a single-contact query on demand, never in the list pull
**And** without `WRITE_CONTACTS` the row is dimmed with "Allow contacts editing" and a "Grant access" pill; a refused write shows an inline reason and keeps the old value.

**Given** tests
**When** run
**Then** the Kotlin mailbox/result mapping has a JVM test and the desktop path returns `Unsupported`.

### Story 19.5: Favorites reorder and S34 Reorder lift

As the owner,
I want to drag Favorites into my own order,
So that my most important people are first.

Covers: FR64, UX-DR30 (S34), UX-DR26, UX-DR27, UX-DR32, AD-32, AD-30, NFR-15, NFR-18. Prio: P2. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** a Favorites tile
**When** long-pressed for 450 ms
**Then** it lifts (scale 1.06 on `release`, shadow grows, painted as flat glass, medium haptic); if the finger moves more than 12 px it enters reorder (the tile follows the finger, neighbours translate into their slots by transform only on `select`, the grid cells do not change until drop, edge auto-scroll) and the Contact Sheet does not open; if the finger never moved the Contact Sheet opens on finger-up as before
**And** the drop settles on `release` and the order commits once through one `update_prefs` keyed by `lookup_key`.

**Given** `favorites_order`
**When** the grid is built
**Then** starred contacts are listed by their index in the saved order, unknown keys are appended by name (new stars append at the end), keys of vanished contacts are skipped at render and never auto-pruned, and a drag rewrites the full list
**And** an unstarred contact drops out, with the pure ordering function unit-tested (new, removed, duplicate and missing-key cases).

**Given** TalkBack
**When** a tile is focused
**Then** custom actions "Move earlier" and "Move later" move it and announce the new position; reduced motion jumps tiles to slots with no lift scale
**And** up to 8 translating tiles hold frame budget (S-scenario noted for 22.1) and Favorites keeps 2 refractive surfaces during reorder (tab bar and puck).

### Story 19.6: Frequent row

As the owner,
I want a quiet row of the people I call most who are not yet Favorites,
So that I can open their sheet without searching.

Covers: FR64, UX-DR27, AD-32, UX-Q15, NFR-14. Prio: P2. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `call_log::frequent` (14.14) computed once per call-log swap
**When** the Favorites tab opens
**Then** below the grid a `footnote` label "Frequent" and one row of up to four flat avatar columns (avatar 56, name `footnote`, no glass) appear, hidden when fewer than 2 qualify or when the setting "Show Frequent" (16 settings Favorites row, default on) is off
**And** tapping an avatar opens the Contact Sheet (never a call) and long-press does nothing extra.

**Given** the tab is open
**When** call-log data changes
**Then** nothing reflows until the next open
**And** with an empty Favorites grid the empty state takes the grid slot and Frequent follows it when present.

**Given** the Settings Favorites section
**When** added here
**Then** it holds the "Show Frequent" toggle (S25 component) which applies immediately.

### Story 19.7: In-app contact editor (P3 stretch, cut first)

As the owner,
I want to edit a contact's name and numbers without leaving the app,
So that small fixes need no hand-off.

Covers: FR62, AD-31, NFR-16. Prio: P3 (cut first; FR-62 may be dropped without affecting FR-60/61). Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the architecture defers this editor
**When** the story starts
**Then** the first deliverable is a new `AD-37` in the architecture spine (provider `ContentProviderOperation` batch for a contact with exactly one writable raw contact, aggregation and synced-account rules, failure modes), written before any code
**And** if the owner has not asked for the editor by the time Epic 22 starts, this story is marked skipped in `sprint-status.yaml` with the reason and FR62 stays flagged as intentionally deferred.

**Given** AD-37 exists and the contact has one writable raw contact
**When** "Edit contact" opens (sheet at the large detent, name field, number rows with label segmented Mobile / Home / Work / Other, number field, remove button, "Add number", Save accent, Cancel)
**Then** Save writes through the batch, changes appear in the system Contacts app, and a failure leaves the contact unchanged with the provider's reason inline
**And** any other contact keeps "Edit in Contacts" (system editor) with no explanation beyond the label.

---

## Epic 20: Keypad intelligence

The owner types digits and sees matching people by name, long-presses a digit to speed dial, long-presses 1 for voicemail, and emergency numbers go through instantly.

### Story 20.1: T9 strip, Results sheet and S32 Results morph

As the owner,
I want contact names to appear as I type digits,
So that I can call "Ayşe" by pressing 2 9 7 3.

Covers: FR65, UX-DR29 (T9 strip, results sheet), UX-DR30 (S32), UX-DR26, UX-DR27, AD-33, NFR-14, NFR-18. Prio: P2. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Keypad
**When** a first digit is typed
**Then** the best match row (56 px, avatar 36, name with the matched letters in weight 700, matched number, trailing 36 px answer-colour call circle) appears in the large-title slot by the S32 Results morph (title slides up 12 px and fades, row rises from 12 px below, matched letters 400 to 700 on `release`; changing matches cross-fade 120 ms with no slide), the number field, status line, keys and Call row keep their positions, and a digit is never delayed
**And** on screens under 700 dp where the title is hidden, the strip uses the 56 px the title would have occupied, reserved only while the field is non-empty.

**Given** the best-match row
**When** tapped (finger-up)
**Then** it fills the number field with that contact's number; its call circle calls on finger-up (call-causing controls act on finger-up, keys stay on finger-down)
**And** a "+n" capsule (36 px) opens the Results sheet (medium detent, up to 50 virtualised 58 px rows with highlighted matches) whose rows fill the field and whose call circles call.

**Given** typing at 6 keys/s over 2,000 contacts
**When** results update
**Then** `ContactIndex::t9` runs inline in the key handler using `T9Scratch` (no allocation), applies within one frame, drops stale results, and the typical keypress cost is recorded; if the on-device S-t9 scenario misses the 1 ms p95 target the query moves to the background executor with a one-frame-stale result while digits are still instant (the switch is a documented constant)
**And** states: contacts permission denied shows no strip and a one-time per-session `footnote` row "Allow Contacts to see matches" with "Grant access"; no match shows nothing; index building shows nothing; the feature has no setting.

**Given** TalkBack
**When** focused
**Then** it reads "Best match: {name}, {number}. Double tap to fill the number. Call button: Call {name}" and "{n} more matches"; reduced motion uses a 120 ms cross-fade.

### Story 20.2: Speed dial editor, Contact Sheet row and S33 Speed-dial charge

As the owner,
I want to hold a digit key to call a person,
So that my closest people are one long-press away.

Covers: FR66, UX-DR29 (speed-dial badge), UX-DR30 (S33), UX-DR26, UX-DR32, AD-33, AD-30, NFR-15. Prio: P2. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Settings row "Speed dial" (count, chevron)
**When** opened
**Then** a second-level page lists digits 2 to 9 (58 px rows: a 36 px key glyph, the assigned name and number or "Not assigned", trailing "Assign" or "Change"); "Assign" opens a Contacts picker sheet (search plus list, number choice inline); a reserved first row `1` reads "Voicemail" and is read-only
**And** assigning a digit already used moves it with the Notice "{digit} moved from {name}"; entries store `lookup_key`, `number_raw` and the name, and a contact edit never retargets a digit.

**Given** the Contact Sheet number row
**When** "Speed dial: 5" or "Speed dial: Not assigned" is tapped
**Then** an inline segmented row of eight 36 px key glyphs picks the digit
**And** assigned keys on the Keypad show a 14 px avatar dot (contact hue pair) at the key's top-right.

**Given** an empty field
**When** an assigned key is held
**Then** the digit appears at once, a 2 px accent ring fills clockwise over 450 ms (conic sweep, key lens glow rises to 0.6), at 450 ms the key pops to 1.12 with a ripple and a medium haptic, the digit is removed and `place_call(number_raw, account)` runs through the one call path (chooser rules apply) with the Call bloom (moment S3)
**And** an early release drains the ring on `press` and leaves the digit typed; a non-empty field is never interrupted; an unassigned key held 450 ms shows the status-line text "No speed dial for {digit}. Set it in Settings." for 2 s with "Settings" tappable.

**Given** reduced motion
**When** held
**Then** a static ring appears at 450 ms with the haptic; TalkBack reads "5, speed dial {name}. Long press to call" and offers the custom action "Call speed dial".

### Story 20.3: Voicemail shortcut and spike S9

As the owner,
I want to hold 1 to call my voicemail,
So that checking messages is one gesture.

Covers: FR67, UX-DR29, AD-33, AD-34, AD-36, NFR-16, S9. Prio: P2. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `telecom::voicemail_info(account_key?)`
**When** called (runtime `READ_PHONE_STATE`)
**Then** it returns `{number?, configured}` from `TelecomManager.getVoiceMailNumber(handle)` and the call is `placeCall(Uri.fromParts("voicemail", "", null), extras with the account)`; desktop returns `Unsupported` with a test
**And** the chooser rules of 16.4 apply on dual SIM.

**Given** the Keypad with an empty field
**When** `1` is held 450 ms
**Then** the voicemail call is placed, the `1` key shows `LucideVoicemail` in place of its empty caption, and when no number is configured the status line reads "No voicemail number set" with a tappable "Open call settings" for 3 s (`ACTION_SHOW_CALL_SETTINGS`)
**And** Recents shows the `LucideVoicemail` icon for `kind == voicemail` entries.

**Given** `VoicemailReceiver` (disabled in the manifest, 14.15)
**When** the Diagnostics "Voicemail" group (spike S9) enables it via `setComponentEnabledSetting`
**Then** a new voicemail triggers `ACTION_SHOW_VOICEMAIL_NOTIFICATION`, the report records the count (may be absent; 0 clears), whether Rovsti's own "New voicemail" notification posted (tap uses `EXTRA_CALL_VOICEMAIL_INTENT`) and whether the system notification was suppressed, and it can disable the receiver again
**And** with `Caps.VOICEMAIL_RECEIVER = false` (default) there is no unread badge, never a guessed one; if S9 passes the badge is an 8 px accent dot on the `1` key (never a number), enabled by flipping the constant; `docs/spikes/s9-voicemail.md` records the procedure and a PENDING table.

### Story 20.4: Emergency numbers and spike S10

As the owner,
I want emergency numbers treated as special and never delayed,
So that a real emergency call goes through instantly.

Covers: FR68, UX-DR29 (Emergency Call button), UX-DR30 (S3 variant), AD-33, AD-24, AD-27, NFR-16, S10. Prio: P2. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `telecom::emergency_numbers()`
**When** called once per session and on `permissions_changed` or SIM change
**Then** it returns the digit strings flattened from `TelephonyManager.getEmergencyNumberList()` (API 29, runtime `READ_PHONE_STATE`) always plus `112` and `911`; desktop returns `Unsupported` with a test and `Backend` falls back to the static pair
**And** it never places an emergency call itself.

**Given** `dial::is_emergency` returns true in the key handler
**When** the Keypad renders
**Then** the Call button keeps its 78 px size and gets the caption "Emergency call" 6 px below it in the existing gap, the status line reads "Emergency call" in danger colour, the bloom becomes an instant swap (no ring, no 300 ms hold), and the SIM chooser, blocked/setup checks, speed-dial hold and T9 strip are bypassed and `place_call(raw, None)` runs
**And** the Call gets `props.emergency`, so Add call and Merge are dimmed ("Not available during an emergency call") and Block is not offered (AD-24, AD-27).

**Given** the Diagnostics "Emergency" group (spike S10)
**When** run
**Then** it evaluates `isEmergencyNumber("112")`, prints the list size and whether `resolveActivity` for `EMERGENCY_ASSISTANCE` is non-null, and never places an emergency call
**And** `docs/spikes/s10-emergency.md` records the procedure, the default (static list; Emergency information row hidden) and a PENDING table; the Settings row (16.10) appears when the resolve check passes.

**Given** P-21
**When** the device script runs
**Then** it uses the carrier or emulator test number procedure from EXPERIENCE (never a real emergency service) and checks no animation delay, no chooser, no setup or block check.

**Given** this is the last Epic 20 story
**When** it ends
**Then** `bash scripts/build-release.sh` runs and the path is reported as owner release 6 ("Keypad and contacts", covering Epics 19 and 20) with device scripts for T9, speed dial, voicemail, reorder, delete contact and the Diagnostics Voicemail/Emergency/Contacts procedures.

---

## Epic 21: Call details and history tools

The owner opens a page for any number with its history and totals, deletes history at four levels, and sees HD and Wi-Fi indicators on live calls and in the log.

### Story 21.1: Call details page with S35 Details arrival

As the owner,
I want a page that shows everything about calls with one number,
So that I can see history, totals and act on it.

Covers: FR69, FR52 (data), FR53, FR60, UX-DR29 (details header, history), UX-DR30 (S35), UX-DR26, UX-DR27, UX-DR34, AD-31, NFR-4, NFR-14, NFR-18. Prio: P2. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `pullCallHistory(raw, 1000)` (AD-31)
**When** called off the UI thread
**Then** it selects rows whose `NUMBER` or `CACHED_MATCHED_NUMBER` equals the raw or normalized form or ends with its last 9 digits, Kotlin filters with `ProviderJson.normalize`, Rust confirms with `PhoneNumber::matches`, type-6 rows are excluded, an empty number never queries, and rows carry type, date, duration, `account_key` and `features` bits (hd=1, wifi=2, volte=4, video=8)
**And** a golden fixture and a Kotlin JVM test cover it, and the call-log pull is the only permission needed.

**Given** the entry points (Recents info control replacing the number sheet when no Contact matches, the "Details" tile in the Contact Sheet and number sheet strips, Blocked calls rows, the row menu "Call details")
**When** one is used
**Then** a pushed full page opens (no Tab Bar) with S35: the row's avatar flies to the 96 px header slot on `morph`, header text fades up 12 px, action tiles rise staggered 30 ms, the first 8 visible history rows fade in staggered 20 ms; the sheet that opened it dismisses first
**And** the Details slot of the 19.3 strip and the reserved place in the 17.3 row menu are now filled.

**Given** the page layout
**When** rendered
**Then** the header has avatar 96, name `title2` (or the formatted number), number `subheadline`, a chip row (Blocked in danger outline when blocked, "Contact" when matched); the five-tile strip is Call (long-press = SIM chooser), Message, Copy, Share, Block/Unblock; a group has Add to contacts (or Edit in Contacts when matched) using the 14.4 intents; the totals card shows "{n} calls", "{h} h {m} min talk time", "Last call {relative}"; the history is virtualised 58 px rows with sticky 28 px date headers (direction icon, type label with Missed in danger, date/time, duration `m:ss`/`h:mm:ss` tabular, no duration for Missed/Declined)
**And** a row tap calls the number on finger-up, long-press opens a row menu (Delete entry arrives in 21.2, Copy number now).

**Given** states and updates
**When** the page loads or a call ends while it is open
**Then** the header shows immediately and skeleton rows appear after 150 ms, a new row fades in at the top with no scroll jump, 500 entries load within 1 s, an unknown/private number shows "Unknown" with Call and Block dimmed ("Unknown number, cannot call back"), a call-log permission denial shows the denied pattern in the history area only, an error shows "Couldn't load history" with "Try again", and SIM chips show only with two SIMs
**And** the TalkBack row label is "{type}, {day} {time}, {duration}, {SIM}, {HD}. Double tap to call"; reduced motion makes S35 a fade; the page counts 0 refractive surfaces at rest.

### Story 21.2: Delete call history entries, Undo and spike S12

As the owner,
I want to delete one entry, a grouped row or all calls with a number, with an Undo,
So that my history only holds what I want.

Covers: FR70, UX-DR26, UX-DR32, AD-31, AD-34, NFR-16, S12. Prio: P2. Verify: CARGO, ANDROID-CHECK, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `CallLogWrite` (shared Kotlin, AD-31 envelope)
**When** `delete(ids)` runs
**Then** it deletes with `_id IN (...)` in chunks of 400 after the runtime `WRITE_CALL_LOG` group `CallLogWrite` is requested at first use (never at startup), returns `permission` with no side effect when missing, and `restore(entries[])` re-inserts `NUMBER, TYPE, DATE, DURATION, PHONE_ACCOUNT_ID, PHONE_ACCOUNT_COMPONENT_NAME, FEATURES, IS_READ=1, NEW=0` in one call; a refusal is `refused` ("Couldn't restore")
**And** `rovsti_android::providers` mirrors both with desktop `Unsupported` tests and the missed badge follows the normal `call_log_changed` re-pull.

**Given** the Recents row menu "Delete entry" (danger, finger-up only) and a Details history row menu "Delete entry"
**When** used on a single entry or a grouped row
**Then** the entries disappear at once (fade then instant re-layout; also from the Missed Call Badge) and the Notice pill says "Deleted" with Undo for 5 s (10 s with TalkBack), where Undo re-inserts the same entries; a restore refusal says "Couldn't restore"
**And** swipe-to-delete is not added (UX-Q9); TalkBack uses the row actions.

**Given** the Details "Delete history" group row
**When** tapped
**Then** a confirm sheet "Delete calls with {name}?" with Cancel and Delete (danger) runs `delete` for all entries of the number, returns to the previous screen and shows "Deleted {n} calls"
**And** all destructive paths act on finger-up only and name what they remove (P-23).

**Given** the Diagnostics "Call log" group (spike S12)
**When** run
**Then** it inserts one marked row, deletes it, restores it and deletes it again without error and prints each step
**And** `docs/spikes/s12-calllog.md` records the procedure, the default (delete rows disabled with "Not allowed on this phone", no Undo, if the platform refuses) and a PENDING table.

### Story 21.3: Delete all call history and S36 Hold to delete

As the owner,
I want a deliberate way to wipe the entire call log,
So that I can start clean without an accidental tap ever doing it.

Covers: FR70, UX-DR29 (hold-to-confirm), UX-DR30 (S36), UX-DR26, UX-DR32, AD-31, NFR-15. Prio: P2. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the Settings Data row "Delete all call history" (danger text)
**When** tapped (finger-up)
**Then** a confirm sheet (medium) "Delete all call history?" with the body "This removes every entry from this phone's call log. Missed-call count goes to 0.", a Cancel text button above and a hold-to-confirm pill (52 px capsule, danger at 15%, label "Hold to delete everything") opens
**And** a missing call-log permission shows the inline "Allow call log access" row with "Grant access".

**Given** the pill
**When** held
**Then** a danger fill sweeps left to right over 1 s (transform scale X), haptic ticks at 33% and 66% and a medium one at completion, releasing early drains it on `press`, completion flashes the pill and runs `CallLogWrite.deleteAll()` (empties the table and leaves voicemail content alone)
**And** the Notice pill says "Call history deleted", Recents refreshes at once and the badge goes to 0 with no Undo (the provider cannot restore a whole table).

**Given** accessibility and motion
**When** TalkBack is on or reduced motion is set
**Then** the pill is replaced by a normal "Delete everything" button (TalkBack) or the fill appears in steps with the hold still required (reduced motion); the sweep and timers run only while the finger is held (P-22)
**And** the Settings Data section now lists Export, Import and Delete all call history in that order.

### Story 21.4: Call quality indicators (live and history)

As the owner,
I want to see whether a call is HD or on Wi-Fi,
So that I know the quality I am getting.

Covers: FR52, UX-DR27, UX-DR29 (chip), AD-24, AD-31, NFR-14, NFR-16. Prio: P2. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `props.hd` (`PROPERTY_HIGH_DEF_AUDIO`) and `props.wifi` (`PROPERTY_WIFI`) on the foreground call
**When** Android reports them
**Then** "HD" and "Wi-Fi" (with `LucideWifi` 12) chips appear in the reserved chip row after the SIM chip, fade in 120 ms and vanish when the property clears, with no layout change when absent
**And** the TalkBack label spells them out ("High definition call", "Wi-Fi call") and chips are never the only carrier of meaning.

**Given** Call details history rows
**When** `features` bits are present
**Then** HD, Wi-Fi and VoLTE chips (`secondary-label`, same shape) show under the date on the right and absent chips leave nothing; Recents rows draw no quality chips (the UX has none there)
**And** a core unit test maps `features` bits to chip sets and a golden fixture covers a row with all bits.

**Given** this is the last Epic 21 story
**When** it ends
**Then** `bash scripts/build-release.sh` runs and the path is reported as owner release 7 ("Call details") with the device script (details page, delete one/group/number/all, undo, HD/Wi-Fi chips) and the Diagnostics Call log procedure.

---

## Epic 22: v0.2 Polish, Performance, Review and Signed Release

All v0.2 surfaces get their perf scenarios and measurements, a reduced-motion/transparency/TalkBack/font-scale audit, the owner's pasted spike reports are reconciled into the shipped constants, the checks and acceptance document are updated, an independent code review runs, and a signed v0.2 release APK is produced. Performance is fixed in code, never by cutting visual effects (AD-21).

### Story 22.1: Perf scenarios for the v0.2 surfaces

As the owner,
I want repeatable measurements of the new surfaces,
So that smoothness claims about the route popover, two-call view, reply sheet, T9 and details are backed by data.

Covers: AD-35, AD-22, NFR-5, NFR-18, UX-DR33 (P-11, P-15), UX-DR25. Prio: P1 (tooling). Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the debug-only scenario runner (12.2)
**When** extended
**Then** it adds S-route (open the popover 20 times and apply 5 Bluetooth arrive/leave updates), S-twocall (Hold-away, Swap x10, Merge weave, participant list of 6), S-reply (open the reply sheet, choose, drag the incoming card 30 times), S-t9 (typing at 6 keys/s over 2,000 contacts), S-details (500-row history scroll, delete, Undo) and an S-incoming run warm with a contacts index swap in flight, each logging one `RovstiPerf` report
**And** `scripts/perf-run.sh` is extended to run them and capture SurfaceFlinger latency for the `GpuiView` layer.

**Given** a desktop headless harness
**When** the scenarios run without a phone
**Then** frame-work numbers for each scenario are written to `docs/perf/after-v0.2-desktop.md` as a baseline for comparison with the phone numbers (22.5).

**Given** the idle checks (P-22)
**When** run on Settings, Call details and each v0.2 sheet for 30 s
**Then** zero frames are requested and no timer runs except the call timer.

### Story 22.2: Code optimisation pass on the v0.2 surfaces

As the owner,
I want the new screens to be fast because the code is efficient,
So that nothing had to be cut to hit the frame budget.

Covers: AD-21, AD-35, NFR-5, NFR-18, UX-DR28. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the numbers from 22.1
**When** any p95 frame work exceeds the display-frame budget, or CPU build time exceeds 4 ms, or a frame exceeds 2x budget
**Then** the fixes follow the AD-21 order: idle and re-render discipline (`SceneDiff`-gated notify, `cached` static subtrees checked by view-cache hit rate), allocation discipline (route/chip/reason labels as `SharedString` built on data arrival, `ElapsedText` reuse, `T9Scratch`, drags and reorder in springs and transforms), virtualised fixed-row lists (58/52 px rows, headers 28), then glass pass cost
**And** no effect, glass surface or signature moment is removed; the story's notes list each fix with before/after numbers.

**Given** a code audit
**When** run
**Then** it greps for `String`/`Vec`/`format!` creation in per-frame paths of `incall.rs`, `route_picker.rs`, `conference.rs`, `reply_sheet.rs`, `settings_ui.rs`, `details.rs`, `blocked.rs` and the keypad strip, and fixes or justifies each hit
**And** a main-Looper turn above 8 ms (index swap, large parse, pipeline pre-compile) is split up and S-incoming proves answer latency is unaffected (AD-35).

### Story 22.3: Glass pass and scaled-surface audit for v0.2

As the owner,
I want the glass on the new sheets and popovers to look right and cost little,
So that the full liquid-glass design survives on the phone.

Covers: AD-21, AD-35, UX-DR28, UX-DR33 (P-14), NFR-12. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the route popover, reply sheet, SIM chooser, Add call sheet, quick-reply and Results sheets
**When** they open
**Then** they paint the flat glass look until within 2% of rest and swap to the refractive pass in one frame with no visible pop (side-by-side frame captures in `docs/perf/`)
**And** the refractive-surface count per v0.2 screen state is measured and reported against the DESIGN table (reported, not a release gate, AD-21).

**Given** any change under `liquid_glass_kit`
**When** made
**Then** `shader_validates` and `glass_snapshot` pixel tests stay green and the change is output-equivalent
**And** reduced transparency uses the opaque material on every v0.2 surface.

### Story 22.4: Reduced motion, transparency, font scale and TalkBack audit of S17-S36

As the owner,
I want every new moment and screen to work with my accessibility settings,
So that nothing breaks when I turn them on.

Covers: UX-DR30, UX-DR32, UX-DR33 (P-20), NFR-10, NFR-15. Prio: P1. Verify: CARGO, BUILD, DEVICE.

**Acceptance Criteria:**

**Given** the moments S17 to S36
**When** reduced motion is on
**Then** each shows its stated fallback (S17 120 ms fade, S18 instant, S19/S20 cross-fade, S21 instant swap, S22 fade, S23 fade, S24 fade without rotation, S25 jump, S26 cross-fade, S27 fade, S28 plain fade, S29 no follow effect but commit at 96 px, S30 instant, S31 fade, S32 cross-fade, S33 static ring at completion, S34 jump, S35 fade, S36 stepped fill with hold still required), recorded in a table in `docs/phone-motion-audit.md` with the code location of each
**And** a slow-frame log line (12.x style) is emitted if any v0.2 moment runs two consecutive frames over 2x budget on the phone.

**Given** font scale 1.3x
**When** each v0.2 screen is checked
**Then** the Audio caption stays one line, route and Settings rows wrap to two lines, strip tiles grow to 76 px with two-line labels, the Waiting card pills stay two columns with wrapped labels, the in-call zone scrolls, and nothing clips or moves the grid and End
**And** every gesture or drag has a TalkBack custom action (incoming card buttons, tile Move earlier/later, reply Move up/down, conference Split/End, Recents row actions, speed-dial Call), announcements fire for route changes, conference joins/leaves, waiting call, merge result, silenced and notices, and focus returns to the opener when a popover or sheet closes.

### Story 22.5: Measured run on the phone

As the owner,
I want the v0.2 numbers measured on my Xiaomi phone,
So that smoothness is proven, not assumed.

Covers: AD-21, AD-22, AD-35, NFR-5, NFR-18, UX-DR33 (P-11 to P-16, P-22). Prio: P1. Verify: DEVICE.

**Acceptance Criteria:**

**Given** `scripts/perf-build.sh` and `scripts/perf-run.sh`
**When** the owner runs them with the phone attached (the dev agent leaves the exact commands in the completion notes)
**Then** the v0.1 scenarios and the 22.1 scenarios each log p50/p95/p99/max, over-budget and over-2x counts, refresh rate and thermal state, and `docs/perf/v0.2.md` records the table against the targets (p95 inside one display frame, CPU build under 4 ms, under 1% over-budget frames and none over 2x, T9 typical 1 ms and worst under 8 ms, warm incoming under 1.5 s, cold under 3 s, idle 0 frames)
**And** a missed target stays a blocker handled by a new fix in 22.2's style, never a visual trade.

**Given** the code is complete before the owner's run
**When** the dev agent finishes
**Then** this story is accepted in code (scripts and the table template ready) and accepted for the numbers only after the owner's device check (DEVICE rule).

### Story 22.6: Spike reconciliation and shipped constants

As the owner,
I want my pasted Diagnostics reports turned into recorded results and the right defaults,
So that the app ships what my phone actually supports.

Covers: AD-36, NFR-16, S3 to S14. Prio: P1. Verify: CARGO, ANDROID-CHECK, DEVICE.

**Acceptance Criteria:**

**Given** the owner's pasted reports for each spike
**When** processed (or, if a report is missing, the row stays PENDING and the shipped default is kept)
**Then** each `docs/spikes/s{n}-*.md` gets its result table filled with pass/fail and the device/OS version, and the constants change only for passes: `Caps.REJECT_PATH` to `TELECOM` (S7), `Caps.BLOCK_GUARD` to `false` (S6), `Caps.VOICEMAIL_RECEIVER` to `true` (S9), `Caps.CONTACT_RINGTONE` to `true` (S11), `Caps.LEGACY_AUDIO_FORCED` unchanged unless S3 failed
**And** dependent UI follows from the constants (the "Show device names" row removed if names appear without the grant, the Blocked list footnote removed only if lists are shared, the Emergency information row shown if S10 resolves, flip hidden if S8 failed).

**Given** a spike result contradicts a shipped assumption
**When** found
**Then** the contradiction and the chosen fallback are written into `docs/spikes/` and `sprint-status.yaml` comments, and a blocker (for example S4 showing no merge at all) is reported to the owner rather than patched silently
**And** `docs/phone-device-notes.md` records the HyperOS facts learned.

### Story 22.7: Final workspace checks and audits for v0.2

As the owner,
I want every check green and the privacy rules re-verified,
So that the release is trustworthy.

Covers: NFR-1, NFR-7, NFR-8, NFR-16, NFR-17, AD-34. Prio: P0 (gate). Verify: CARGO, ANDROID-CHECK, BUILD.

**Acceptance Criteria:**

**Given** the workspace
**When** `cargo fmt --all && cargo clippy --workspace --all-targets -- -D clippy::unwrap_used -D clippy::disallowed_methods && cargo test --workspace` and `cargo check -p rovsti_android --target aarch64-linux-android` run
**Then** all pass for Phone-owned crates (the Messages/Stops exception from 13.3 stays flagged) and the Kotlin JVM tests pass
**And** the wire fixture test list in `docs/phone-wire.md` matches the fixtures on disk (no orphan fixture, no untested field).

**Given** grep and manifest audits
**When** run
**Then** `rg "rovsti_android" apps/phone/src` matches only `backend.rs`, no network crate or `INTERNET` or `SEND_SMS` appears, `scripts/check-apk.sh` passes on the release APK, every new `rovsti_android` function has a desktop `Unsupported` test, and no unwrap is added
**And** the new permission requests (`WRITE_CALL_LOG`, `WRITE_CONTACTS` at first use, `BLUETOOTH_CONNECT` only if S3 needs it) are each justified in `docs/phone-device-notes.md`.

### Story 22.8: Owner acceptance document for v0.2

As the owner,
I want a one-page test script for the new features,
So that I can accept v0.2 with evidence.

Covers: UJ-7 to UJ-10, UX-DR33 (P-11 to P-23), SM-1 to SM-4, NFR-14 to NFR-18. Prio: P0. Verify: DEVICE.

**Acceptance Criteria:**

**Given** `docs/phone-acceptance.md`
**When** extended with a v0.2 part (the v0.1 part stays intact)
**Then** it lists device tests for UJ-7 (Bluetooth route, connect/disconnect), UJ-8 (add call, swap, merge, end a participant, four waiting choices), UJ-9 (decline with message, block from Recents and Undo), UJ-10 (default SIM, quick replies, speed dial, reorder, export/import), and acceptance rows P-11 to P-23 each with the scenario text, a pass column and an FR/story id column
**And** it includes single-SIM versus dual-SIM checks (P-18), emergency number test number procedure (P-21), reduced motion/transparency/font scale/TalkBack runs (P-20), layout overlay screenshots for P-13 and the Diagnostics report paste instructions for S3 to S14.

**Given** the 7-day log
**When** continued
**Then** a v0.2 table adds columns for audio route used, conferences made, calls blocked and any fallback to the stock dialer, and failed items map to story ids.

### Story 22.9: Independent code review

As the owner,
I want a fresh set of eyes to review the whole v0.2 change,
So that defects and risks are caught before release.

Covers: NFR-6, NFR-8, NFR-16, AD-17, AD-25, AD-27, AD-30. Prio: P0. Verify: CARGO, ANDROID-CHECK, BUILD.

**Acceptance Criteria:**

**Given** the diff from the v0.1 release commit to the current head
**When** `bmad-code-review` runs in a fresh context (a different agent session or model than the builders)
**Then** several independent reviewers cover: call safety (a Call can always be ended, no new crash path, `PendingOp` races, notification registry-only actions), data safety (Prefs migration backups, backup import bounds, destructive provider writes), privacy and manifest (no `INTERNET`/`SEND_SMS`, no number or name in logs, `RovstiSpike` only ids), threading (no JNI in render or key handlers, main-Looper turns), and performance (allocation in per-frame paths)
**And** findings are triaged blocker/major/minor and written to `docs/phone-review-notes.md` (appending a v0.2 section).

**Given** blocker and major findings
**When** triage completes
**Then** each is fixed in code with a test where pure, or explicitly deferred with the owner-visible reason in the review notes; no blocker remains
**And** the always-on checks pass again after the fixes.

### Story 22.10: v0.2 signed release

As the owner,
I want a signed v0.2 release APK,
So that I can install the finished app on my phone.

Covers: FR39 (extended), AD-20, NFR-1, SM-3. Prio: P0. Verify: BUILD, DEVICE.

**Acceptance Criteria:**

**Given** `source ~/.config/rovsti/signing.env` and no other gpuiforge or gradle build running
**When** `bash scripts/build-release.sh` runs (feature `perf` off, R8/minify off, `proguard-rovsti.pro` retained, app version bumped to 0.2.0 in `gpuiforge.json` and the Setup version-reset logic untouched)
**Then** the APK is signed with the owner's key (`apksigner verify --print-certs` succeeds), `scripts/check-apk.sh` passes (no `INTERNET`, no `SEND_SMS`, both `Java_*` symbols, `VIBRATE`, queries, disabled receiver) and the file lands at `~/Downloads/rovsti-phone-release.apk`
**And** no password appears in any output, log or tracked file (`git status` shows no secrets).

**Given** an install over the previous release
**When** the owner installs it
**Then** the same-key update keeps the default-dialer role and data (Prefs v2 migration verified: last tab, Xiaomi confirmations, `prefs.json.bak-v1` present), and `docs/phone-build.md` records the update and the uninstall-then-install path for a different key
**And** the completion notes list the release path, the version, the signature fingerprint and the pending DEVICE items for the owner.

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
11. **v0.2 append (2026-10-28).** Epics 14 to 22 were appended in headless mode from the updated PRD (FR41-FR73, NFR14-18), UX (S17-S36, P-11 to P-23) and architecture (AD-23 to AD-36, spikes S3 to S14). Epics 1 to 13 and their 89 stories were not renumbered or edited; only the requirements inventory, FR coverage map, Epic List and this section were extended. Item 9 above ("Items intentionally absent") is superseded: dual-SIM, blocklist, swap/merge/conference, T9, speed dial, voicemail, Bluetooth picker, contact creation/deletion and call-log deletion are now Epics 14 to 21; call recording, caller-ID/spam lookup, visual voicemail, video, RTT beyond a shortcut and call notes stay out.
12. **FR-62 (in-app contact editor, P3).** Mapped to story 19.7, but the architecture says it needs its own AD (AD-37) before code, and the PRD allows cutting it. 19.7's first deliverable is AD-37; if the owner has not asked for it by the start of Epic 22 it is marked skipped. This is the only FR with an intentional deferral risk.
13. **Foundation lives in Epic 14, with letters.** The gap analysis puts foundation work inside 14.1 and 16.2; to keep one-agent-run story sizes and keep the spine's story references (S3 in 14.1, S4 in 15.1, S5 in 16.3, S13 in 16.6, S6 in 17.1, S7 in 18.1, S8 in 18.4, S14 in 18.5, S11 in 19.2, S9/S10 in 20.3/20.4, S12 in 21.2) true, F0 part 1 is numbered 14.0a to 14.0f (parsed by sprint tooling as `14-0a` and so on) and F0 part 2 is 14.9 to 14.15, after audio ships. The spine's "16.1/16.2 before Epic 15" ordering is not followed because the task brief puts multi-call before Settings/SIM; Epic 15 needs no Settings (haptics default Normal).
14. **Spike ids versus moment ids.** The spine's spikes S3 to S14 and DESIGN's signature moments S1 to S36 share the letter S. In `Covers:` lines a bare S-number after the AD/NFR list is a spike; moments are written `UX-DR30 (S17)`.
15. **Contact Sheet strip and row menu are filled progressively without shifting.** The five-tile action strip (19.3) and the Recents row menu (17.3) reserve the slot that Details (21.1) and Delete entry (21.2) fill later; no tile or row moves when they arrive. Block on Call details and the Contact Sheet strip arrives with 21.1 and 19.3 respectively.
16. **One signed release per epic, with two exceptions.** Epics 19 and 20 share one release at 20.4 (19.7 is optional and P3), and F0 part 2 plus the manifest additions ship in a no-visible-change build at 14.15. Epic 22 ends with the v0.2 release at 22.10. Owner device acceptance still follows each release (DEVICE rule); constants are flipped from the owner's pasted reports in 22.6.
17. **Open PRD questions with defaults applied.** Q6 (dual SIM may be unused): built and invisible on one SIM, drops to P3 if unused (16.3 to 16.5). Q9 (contact editor): system editor hand-off. Q4/Q5 stay deferred. UX-Q5 to UX-Q19 take the documented defaults except UX-Q13 (no SMS permission row, AD-28), UX-Q12 (badge only if S9 passes), UX-Q14 (ringtone row only if S11 passes) and UX-Q19 (hearing-aid hint best effort), answered by the architecture.
18. **Real platform unknowns remain until the owner runs the spikes** (HyperOS blocklist sharing, reject-text sender, silence permission, IMS conference children, account-key match, voicemail signal, ringtone write). Every one ships a safe default, so no story is blocked; see Spike rule.
19. **PRD wording stale against UX/architecture decisions (epics follow the later documents).** FR-70 says "swipe" but UX-Q9 chose the row menu and Call details (swipe-to-delete stays banned); FR-49 says the SIM section "shows the read-only default" with fewer than two SIMs while FR-50 and UX-Q10 hide it entirely (16.10 hides it); FR-56 allows an SMS permission row but AD-28 settled that none is declared (18.1, 18.2). The PRD text should be tidied in its next update; no story is affected.

## Final Validation Summary

- FR coverage: 40 of 40 FRs mapped (see FR Coverage Map); every FR appears in at least one story's `Covers`.
- Starter/eject check: Epic 1 Story 1.2 is the eject and hello-APK story (the architecture's "starter" equivalent).
- Entities created when needed: wire DTOs in 2.1 are shared contracts; provider pulls are created in 3.4 only for data the lists use.
- Forward dependencies: none; each story uses only earlier stories (6.3 and 8.8 explicitly handle the Contact Sheet arriving later).
- File churn: Epics 4/5 both touch `RovstiInCallService`/`CallRegistry`; the split is justified by the risk boundary (outgoing/in-call vs lock-screen incoming) and the S2 spike feedback loop.
- Story count: 89 stories in 13 epics.

### v0.2 addendum (appended 2026-10-28)

- FR coverage: 73 of 73 FRs mapped (FR41-FR73 added); every new FR appears in at least one story's `Covers`. FR62 is mapped to the optional P3 story 19.7 (needs AD-37 first) and is the only FR with an intentional deferral risk.
- NFR coverage: NFR14-NFR18 are carried as UIQ acceptance lines in every UI story and checked in 22.1 to 22.4 and 22.7.
- Signature moments S17-S36: each maps to exactly one building story (see the v0.2 coverage list) and is audited in 22.4.
- Spikes S3-S14: each is a small story or part of one that ships the safe default and a Diagnostics probe, and none blocks a later story; results are reconciled in 22.6.
- Forward dependencies: none; where a later story fills a reserved slot (Details tile, row menu entries, Message pill, Settings sections) the slot is reserved earlier so nothing shifts.
- Story count: 78 new stories in Epics 14 to 22 (Epic 14: 21, 15: 9, 16: 10, 17: 6, 18: 7, 19: 7, 20: 4, 21: 4, 22: 10); total 167 stories in 22 epics.
