# PRD Quality Review — Rovsti Phone

Reviewed: `prd.md` and `addendum.md` (2026-10-27). Stakes are a personal solo project, but the PRD feeds one automated implementation run, so done-ness, Android correctness and run size are weighted most heavily.

## Overall verdict

This is a specific, well-traced PRD with a clear thesis: reliability comes first, glass second, and everything stays local. Almost every FR has concrete consequences, the scope is mostly honest, and the "native ringing fallback is acceptable" decision is a good product call. The risk is in the call-handling core (§4.3 to §4.5). Several Android behaviors are missing or misdescribed: outgoing calls stuck in `SELECT_PHONE_ACCOUNT`, MMI/USSD and emergency numbers, self-managed ringing, missed-call notification mechanics, and missing manifest permissions. On top of that, the MVP has no priority tiers or cut line, even though NFR-11 asserts it fits in one run. About ten targeted edits would make it ready for architecture.

## Decision-readiness — adequate

The real decisions are stated as decisions. §4.5 "ringing reliably comes first, glass second" names what is given up (a non-glass ringing screen) and ties it to NFR-2 and SM-C1. Deferrals in §6.2 are explicit. The open questions in §10 are genuinely open and carry owners and defaults (Q4, Q5), so an unattended run will not stall on them.

There are two weak spots. First, some consequential choices are made silently in the addendum rather than surfaced as decisions. The main one is `IN_CALL_SERVICE_RINGING = true` (addendum, "Android components" table), which makes the app responsible for the ringtone, vibration, DND and silence handling. The PRD never weighs this against letting Telecom ring. Second, Q4 frames the missed-call notification as "rely on the system fallback if it appears on the device". That is not device luck: Telecom behaves deterministically depending on whether the default dialer registers a receiver (see Findings). Both look like decisions only an architect would catch.

### Findings
- **high** Self-managed ringing chosen silently (addendum, FR-21 row; PRD FR-21) — With `IN_CALL_SERVICE_RINGING = true`, Telecom stops ringing, and the app must reimplement ringer mode, DND / interruption filter, per-contact custom ringtones, vibration patterns, audio attributes and `onSilenceRinger()`. This also needs the `VIBRATE` permission, which `gpuiforge.json` does not declare. It adds scope and a new way to miss calls, which is exactly what SM-2 forbids. *Fix:* Make it an explicit decision. Recommend omitting the metadata (or setting it to false) so Telecom rings. FR-21's consequences then become "the system ringer behavior is unchanged" and are nearly free to meet.
- **medium** Q4 misdescribes the missed-call mechanism (§10 Q4, FR-25) — When the app holds the dialer role and declares a receiver for `TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION`, Telecom hands the notification to the app and posts none of its own. Without the receiver, Telecom posts its own notification, and its tap action opens the call log through a generic intent that only reaches Rovsti if Rovsti filters for it. So "Tapping it opens Recents" (FR-25) is not guaranteed under the stated default. *Fix:* Decide now. Either (a) no receiver, plus an `ACTION_VIEW` filter for the calls content type so the system notification opens Recents, or (b) a receiver plus Rovsti's own notification. Either way, call `TelecomManager.cancelMissedCallsNotification()` when Recents is viewed (FR-27).
- **medium** Emergency calls are never mentioned (§4.3, §5, §9) — A daily-driver default dialer has to place 112/911 reliably, and the In-Call Screen will be the UI for those calls. *Fix:* Add an FR or NFR: an emergency number entered on the Keypad bypasses any validation or contact logic and goes straight to Telecom. Add a risk line that the owner verifies emergency-call UI behavior once (for example with a carrier test number, or by reading Telecom logs), not by dialing 112.

## Substance over theater — strong

There is no persona theater: one protagonist (Erdem), and the Non-Users list does real work by excluding dual-SIM switchers and Play users. The Vision is specific to this product: Xiaomi, local-only, and "a dialer that misses calls is worse than no dialer". Most NFRs have numbers (NFR-2 to NFR-4).

### Findings
- **low** Adjective NFRs (§5 NFR-5, NFR-10) — "60 fps … in normal use" leaves "normal use" undefined. "44 × 44 pt" is an iOS unit, while Android guidance is 48 × 48 dp. *Fix:* Define NFR-5 as "scrolling the 500-entry Recents list and a tab switch show no dropped-frame bursts visible in `adb shell dumpsys gfxinfo`", and use dp.
- **low** "No network code path" (§1, NFR-1) is asserted, but the only check is that the manifest lacks `INTERNET`. *Fix:* State that the absence of `INTERNET` in the merged manifest is the acceptance test, since GPUI pulls in HTTP-capable crates.

## Strategic coherence — strong

The thesis is clear. It is a reliability-first, fully local daily dialer that also proves the shared Android stack. SM-1 and SM-2 validate it directly (7 days with every call, zero app-caused missed calls), and counter-metrics SM-C1 and SM-C2 guard the obvious failure modes. Feature order follows the thesis: role, then the setup checklist, then calls, then lists.

### Findings
- **medium** No priority within the MVP (§6.1) — Every FR carries equal weight. If the run runs short, nothing says whether to sacrifice FR-12 (keypad contact match) or FR-22 (lock-screen ringing). That conflicts with SM-C2's own warning. *Fix:* Tag the FRs as P0, P1 or P2 (see Scope honesty).

## Done-ness clarity — adequate (thin in §4.3 to §4.5)

Most FRs have crisp, observable consequences. Examples are FR-3 (`tel:+905551112233` pre-filled and not dialed), FR-12, FR-31 ("ozgur" matches "Özgür") and FR-29 (within 2 s). The call-handling FRs are the weak part. Some contradict each other or the existing code, some depend on carrier behavior, and none says how the run (as opposed to the owner) verifies them.

### Findings
- **high** Outgoing call can hang in `SELECT_PHONE_ACCOUNT` (FR-11, §6.2 assumption) — FR-11 says "goes through Telecom on the system default SIM". On a dual-SIM Xiaomi with "Ask every time" (or no default set), Telecom puts the Call in `Call.STATE_SELECT_PHONE_ACCOUNT` and waits for the InCallService to call `phoneAccountSelected()`. That state is not in the Call State glossary, and nothing handles it, so the call sits silently. *Fix:* Add a consequence: "If Telecom asks for an account, the app picks the suggested or first account (or shows a minimal two-row chooser), or disconnects with a visible message. The Call never waits silently." Add `SelectPhoneAccount` (or a "Connecting" pre-state) to the Call State glossary.
- **high** MMI/USSD codes and `*`/`#` are blocked (FR-9 vs FR-10) — FR-9 allows `*` and `#`, but FR-10 rejects anything `PhoneNumber::parse` fails on, and `parse` returns `InvalidCharacter` for `*` and `#` (`crates/rovsti_core/src/phone_number.rs`). So `*#06#`, carrier balance codes such as `*123#`, and pause/wait (`,` `;`) cannot be dialed from the default dialer. *Fix:* FR-10 should validate dialable strings (digits, `+`, `*`, `#`, `,`, `;`) separately from `PhoneNumber` matching. Add a consequence: "`*#06#` is handed to Telecom (or shows IMEI) and is not rejected by the shake animation."
- **high** No automated device-level verification path (§9 "No automated device test", SM-3) — `gpuiforge.json` already builds `x86_64`, and the SDK includes `emulator`. An AVD can hold the dialer role, and `adb emu gsm call <n>` simulates incoming calls. That covers FR-1, FR-2, FR-21 to FR-25 and FR-29 without the owner. *Fix:* Add an NFR or acceptance note: "The run verifies role eligibility, incoming ring, answer, decline and missed-call flow on an x86_64 emulator via `adb emu gsm call` and `adb shell cmd role`." Keep the owner's Xiaomi test for the Xiaomi-only items.
- **medium** Waiting-call consequence depends on the carrier (FR-20) — "Answer puts the first Call on `Holding`" is Telecom/carrier behavior. If the active call cannot be held, Telecom disconnects it instead. It is also hard for the run to test, and it goes beyond the brief (see the reconciliation). *Fix:* Reword it as "Answer calls `answer()` and the screen reflects whatever states Telecom reports". Drop the resume-with-Hold assumption, or move it to P2. Also say what happens when a call arrives while the first Call is `Dialing` or `Holding`.
- **medium** FR-35 contradicts itself — "calls … the first mobile number if none is set" and "If the Contact has several numbers and no default, a small chooser appears" both apply to the same case. *Fix:* Pick one: either the chooser only when there is no default and no mobile number, or always the chooser when there are several numbers.
- **medium** The "unverifiable" state is never assigned (Glossary "Setup Item", FR-5, FR-6, FR-8) — The glossary defines three states, but no FR says which item is ever *unverifiable*. Battery saver has two parts: an AOSP part that can be read back and a MIUI part that cannot. Its combined state is undefined. *Fix:* State it directly, for example "Xiaomi Settings show *unverifiable* until confirmed, then *done*; Battery saver is *done* only when the AOSP check passes and the MIUI part is confirmed."
- **medium** NFR-2 has no measurement method (NFR-2) — "From Telecom reporting `Ringing` to … visible" cannot be checked without defined log points. *Fix:* Require two logcat markers (`onCallAdded` ringing, first frame or notification posted) under one tag, and measure the delta.
- **medium** FR-2's failure trigger is not observable (FR-2) — "If the dialog cannot be shown (request returns false)" is not how the role request fails. After two denials, Android auto-cancels with `RESULT_CANCELED`, and MIUI may just return without showing anything. *Fix:* "If the role is not held after the request returns (any result), show the Settings path."
- **low** FR-8 does not say what "version change" means — With debug sideloads, `versionCode` may never change, or `lastUpdateTime` changes on every `adb install`. *Fix:* Name the key (`versionCode`, or `lastUpdateTime` if the reset is meant to happen on every reinstall).
- **low** Headset return path (FR-15) — The owner can choose speaker while a headset is connected, but nothing says how to get back to the headset. *Fix:* "Turning speaker off restores the headset route if one is connected, otherwise earpiece."
- **low** FR-12 has no consequence for a missing `READ_CONTACTS` (it should silently show no name), and FR-13 does not cover restricted vs. payphone presentation. *Fix:* Add one line to each.

## Scope honesty — adequate

§6.2 and §7 are explicit, and every deferral is named. There are 17 assumptions, 1 NOTE FOR PM and 5 open questions (3 for architecture). That density is fine for a solo project, because each one has a default. Three problems remain. NFR-11 claims single-run buildability without evidence. Several items quietly widen the brief's scope. And some necessary Android pieces are left out silently rather than as stated non-goals.

### Findings
- **high** The MVP is likely too large for one run, and there is no cut line (NFR-11, §6.1) — The run has to: install the toolchain; eject the host; build a two-way JNI event bridge with resync; implement an InCallService with FSI, two CallStyle notifications, proximity wake lock and self-ringing; handle the waiting call with hold; deliver a 7-item setup checklist with persistence and version reset; build three provider-backed tabs with live refresh, Turkish-folding search and a star write; and add desktop sample data. On top of that, the Rust-to-Kotlin call path is unproven, and Q1 to Q3 are still open. *Fix:* Add P0/P1/P2 tags and a stated cut line.
  - P0: FR-1 to FR-4, FR-9 to FR-11, FR-13 to FR-16, FR-18, FR-19, FR-21 to FR-25, FR-26, FR-28, FR-39, FR-40.
  - P1: FR-5 to FR-8, FR-17, FR-20 (decline only), FR-30 to FR-35.
  - P2: FR-12, FR-27's "new" flag, FR-29, FR-36's remember-last-tab, FR-38 sample data, contact photos.
  - Also recommend Telecom-managed ringing, which removes a whole subsystem.
- **medium** Manifest and permission gaps are not acknowledged (addendum "Android components" table and permissions note) — An ongoing CallStyle notification needs a foreground service (`phoneCall` type on Android 14 needs `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_PHONE_CALL`) or a full-screen intent. The proximity lock needs `WAKE_LOCK`. Self-ringing needs `VIBRATE`. None of these is declared in `gpuiforge.json`. *Fix:* List them in the addendum, and add an FR-1 consequence that the merged manifest contains them.
- **medium** Missing Setup Items that gate incoming calls (FR-5) — First, Android 14 full-screen intent permission: it can be read with `NotificationManager.canUseFullScreenIntent()` and fixed via `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Second, on MIUI, per-app "Background pop-ups" (heads-up) is often off by default for sideloaded apps, which would silently break FR-23 (verify on device). *Fix:* Add the FSI permission as a readable Setup Item, and add background pop-ups as a fourth Xiaomi Setting, or as a risk in §9 to verify.
- **low** It is not said that the dialer role default-grants permissions — Android's role definition typically pre-grants phone, call log and contacts permissions when `ROLE_DIALER` is accepted, so FR-5's permission items will usually turn done right after FR-2. *Fix:* A one-line note, so the run's tests do not assume a prompt appears.

## Downstream usability — adequate

There is a glossary, and most terms are used consistently with initial capitals. IDs are contiguous. Each FR names a UJ, which makes epic extraction straightforward. The addendum maps FRs to components, which helps architecture. The drift findings below would confuse story writers.

### Findings
- **medium** The Call State glossary is single-call and screen-scoped, but FRs use more (Glossary "Call State", "In-Call Screen"; FR-18, FR-20) — The In-Call Screen is defined for `Dialing`/`Active`/`Holding`, but it also shows a `Ringing` Waiting Call (FR-20) and "Call ended" in `Disconnected` (FR-18). Telecom also has `Connecting`, `SelectPhoneAccount`, `Disconnecting` and `SimulatedRinging`. The view model needs to be a collection of Calls. *Fix:* Redefine the In-Call Screen as "shown while any Call exists that is not the only `Ringing` Call", list the extra states as mapped to their nearest UI state, and say the model holds up to two Calls.
- **medium** Call-log types are not defined (Glossary "Recents Row", FR-26; addendum `call_log::group`) — The direction icons cover outgoing, incoming and missed, but `CallLog.Calls.TYPE` also has rejected (which is what Decline produces), blocked, voicemail and answered externally. The grouping function cannot be specified without this mapping. *Fix:* Add a Call Log Entry type mapping (for example rejected maps to the incoming icon in grey, or is shown as missed).
- **low** Additional glossary drift:
  - "Audio Route — earpiece or speaker in MVP" vs FR-15, which shows a headset route.
  - "Incoming Call Screen — shown over the lock screen" vs FR-23, where it opens from the notification while unlocked.
  - "Missed Call Badge … not yet seen in Rovsti Phone" vs FR-27 "newer than the last time Recents was viewed" plus Android's `NEW` flag (two sources of truth).
  - "same direction/type" (glossary) vs "same type" (FR-26, addendum) vs "direction" (brief).
  - *Fix:* Align on one definition each.

## Shape fit — strong

The shape suits a solo, chain-top PRD: one named protagonist, six short UJs carrying context inline, a capability-grouped FR list, and the technical "how" pushed into the addendum. It is not over-formalized. UJ-5 and UJ-6 lack Entry states, but they are self-explanatory.

### Findings
- **low** UJ-5 and UJ-6 have no Entry state or Resolution (UJ-6) — This is minor, but it makes the traces look uneven. *Fix:* Add one line each.

## Mechanical notes

- **ID continuity:**
  - FR-1 to FR-40, UJ-1 to UJ-6, NFR-1 to NFR-11, SM-1 to SM-4 and SM-C1/SM-C2 are contiguous and unique.
  - The references to §10 Q1/Q4, §6.2 and NFR-2 resolve.
  - FR-38, FR-39 and FR-40 have no UJ ("builder job"), which is acceptable.
- **Weak traces:**
  - FR-3 → UJ-6 (UJ-6 does not involve an external `ACTION_DIAL`).
  - FR-12 → UJ-6 (a support line will not match a Contact).
  - FR-17 → UJ-2 (UJ-2 never holds).
  - FR-20 → UJ-3 (UJ-3 has no waiting call).
  - FR-37 → UJ-1.
  - Consider a UJ-7 for a waiting call, or retag the FRs as "builder job / reliability".
- **SM traceability:**
  - SM-3 validates FR-24 but not FR-22 (lock-screen surface), which is the riskiest FR.
  - SM-1 lists FR-13 to FR-24 but omits FR-25 to FR-35, even though calls from Recents, Contacts and Favorites are part of "every call".
- **Assumptions Index roundtrip:**
  - All 17 inline `[ASSUMPTION]` tags are indexed, and every index entry appears inline.
  - "English only" (§6.2) is an inference that is not tagged.
  - The PRD memlog records the assumption "Missed-call notification … tab badge only in MVP", which the PRD contradicts (FR-25 requires a notification). The PRD is the better position, but the memlog is stale.
- **Units and platform terms:**
  - "pt" is used in NFR-10 (should be dp).
  - "Battery saver 'No restrictions'" matches `docs/xiaomi.md`.
  - The Xiaomi glossary merges "Show on lock screen" and "Display pop-up windows while running in background" into one Xiaomi Setting. They are two toggles on the same MIUI page, so the confirmation copy should name both.
- **Addendum accuracy:**
  - The permissions note omits `USE_FULL_SCREEN_INTENT` and `BLUETOOTH_CONNECT`, which `gpuiforge.json` declares.
  - `setAudioRoute` is deprecated on API 34 in favor of `requestCallEndpointChange`. The addendum says "or `CallEndpoint` APIs", which is fine.
- **Required sections:** All are present for the stakes: Vision, Users, UJs, Glossary, FRs, NFRs, Scope, Non-Goals, SMs with counter-metrics, Risks, Open Questions and the Assumptions Index.
