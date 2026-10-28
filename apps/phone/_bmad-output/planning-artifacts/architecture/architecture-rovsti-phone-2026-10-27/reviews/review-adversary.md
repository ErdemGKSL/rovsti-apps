# Adversarial review: Rovsti Phone ARCHITECTURE-SPINE

Method: pairs of units one level down, each obeying every AD literally, that still fail to integrate. Evidence checked against `gpui_android/docs/hosting.md` at the pinned rev (main runs once per new session; config changes retain the session; session is released when hosting closes).

Verdict: **Conditional pass. Strong on ownership (AD-4) and answerability (AD-2), but the Kotlin/Rust seam contract is under-specified at exactly the places two authors will diverge: JSON field semantics, attach ordering, native-library-not-loaded pushes, and notification/activity raising. 14 holes below; H1 to H6 are build-breaking or call-losing and must be closed before stories start.**

Severity: S1 = call lost / crash / privacy leak. S2 = wrong behaviour, rework. S3 = polish/ambiguity.

---

## H1 (S1) Kotlin pushes `nativeOnEvent` before the library is loaded
- Pair: `CallRegistry`/`RovstiInCallService` author vs `MainActivity` author.
- AD-8 forbids loading the `.so` in `Application`; AD-4/AD-5 say `CallRegistry` calls `nativeOnEvent` "on every change". On a cold incoming call, the service runs with no activity yet, so the `external fun` throws `UnsatisfiedLinkError` inside `onCallAdded`. Both units obey their ADs.
- Fix **AD-5a**: `RovstiBridge.emit(kind, json)` is the only Kotlin caller of `nativeOnEvent`; it is a no-op until `attachNative()` has completed (volatile flag) and swallows `UnsatisfiedLinkError`. `CallRegistry` never calls `nativeOnEvent` directly. A registry mutation must never be able to throw out of a Telecom callback.

## H2 (S1) Attach/resync race and sink lifetime
- Pair: `main()` author vs registry author.
- AD-5 says `main()` "installs a channel, drains" and AD-4 says "calls `snapshot()` once, then consumes pushes". Order unspecified. Snapshot-then-install loses a push in the gap (a call ended in the gap leaves a ghost call, since `seq` only protects against old events, not missed ones). Also: after a session closes the static sender holds a dead receiver; sends silently fail and a later push is mistaken as delivered. `call_log_changed`/`contacts_changed`/`permissions_changed` carry no seq and are dropped while detached, but only `calls` is said to be re-pulled on attach.
- Fix **AD-5b**: attach order is (1) install new channel, (2) pull `calls::snapshot()` and feed it into the same channel as a normal event, (3) pull providers and permissions. Install replaces and drops the old sender; on session close, `main`'s guard clears the sink to `None`. Dropped provider events are recovered by an unconditional full re-pull at attach. `snapshot()` and pushes share one `seq` counter assigned under the same lock that builds the JSON (see H3).

## H3 (S1) Registry thread-safety and `seq`/content atomicity
- Pair: `RovstiInCallService` (Looper) vs Rust `calls::snapshot()` on the GPUI thread vs `CallActionReceiver` (receiver thread) vs debounce threads.
- Nothing says who may mutate `CallRegistry` or that `seq` is assigned with the JSON. Thread A assigns seq 5 then builds JSON, thread B assigns 6 and builds an older JSON; the reducer keeps the older content because 6 > 5. Also AD-16 forbids blocking JNI but a registry lock held across a binder call to Telecom makes `snapshot()` block the GPUI thread (ANR/jank).
- Fix **AD-4a**: all registry mutation and snapshot building happens on the main Looper (post from receivers); `seq` is incremented and JSON built in one synchronous step; `snapshot()` is served from a volatile last-built string, never computing under a binder call. No lock is held across any Telecom call.

## H4 (S1) Waiting-call replace is a race, and "primary call" has two owners
- Pair: Kotlin `answerReplacing` author vs `core::call` author.
- AD-6 makes `answerReplacing(call_id)` "disconnect then answer", but disconnect is async: answering before `onCallRemoved` makes Telecom auto-hold the dying call or fail. Separately AD-14 gives `core::call` "waiting-call selection" while Kotlin builds the call list; Kotlin might order by creation, Rust may pick by state, so the two disagree on which call is foreground/waiting.
- Fix **AD-6a**: `answerReplacing` is a registry-owned pending operation: disconnect, answer in the matching `onCallRemoved` (or on a 2 s timeout answer anyway), cleared on any new snapshot where the target is gone. **AD-14a**: snapshot carries explicit `foreground_call_id` and `waiting_call_id` computed once in Kotlin; Rust reducer must not re-derive them, only validate presence.

## H5 (S1) Who raises the activity after Answer, and Android 12+ notification trampolines
- Pair: `CallNotifications` author vs `CallActionReceiver` author vs `MainActivity` author.
- AD-2 mandates Answer as a PendingIntent to a broadcast receiver. AD-1 launches the activity only by fullScreenIntent. When the device is unlocked, the incoming notification is a heads-up (no fullscreen launch); Answer then calls the registry and nobody opens the in-call UI. A receiver calling `startActivity` is blocked by the trampoline restriction on targetSdk 31+. Same gap for calls placed by other apps, Bluetooth, Assistant (`ACTION_CALL`): outgoing calls from the background never launch UI (fullScreenIntent is incoming only).
- Fix **AD-1a**: a single `CallSurface.raise(reason)` owned by `RovstiInCallService`: incoming uses fullScreenIntent; for outgoing/answered calls the service starts `MainActivity` itself (allowed: bound InCallService of the default dialer; verify in S2 on HyperOS, with the Xiaomi "pop-up from background" permission as a Setup item). Answer/Decline PendingIntents never trampoline: Answer is `PendingIntent.getActivity` to MainActivity with an `answer` extra handled in `onNewIntent` + registry call, or a broadcast that only touches the registry and relies on `raise()`. S2 must prove all five paths: locked/unlocked x incoming, outgoing-from-other-app.

## H6 (S1) Lock-screen leak on call end and stale `keyguard_locked`
- Pair: `MainActivity` (clears showWhenLocked on last call) vs renderer (AD-3).
- AD-3 shows only call screens when `keyguard_locked && calls non-empty`. When the last call ends while locked, the snapshot has no calls and `keyguard_locked` is true, so the renderer shows the tab bar and Recents for the moment until showWhenLocked is cleared. Also `keyguard_locked` is re-pushed only on USER_PRESENT/onResume: the device can re-lock mid-call (screen timeout after proximity-off) with no event, leaving tabs showing over the lock screen.
- Fix **AD-3a**: when `keyguard_locked` and no calls, render nothing (blank glass) and call `moveTaskToBack`; derive `keyguard_locked` from `KeyguardManager.addKeyguardLockedStateListener` (API 33+, min_sdk is 34) plus SCREEN_OFF/ON, registered by `CallRegistry` (not the activity) while a call exists; push a snapshot on each change. Rust must treat missing/unknown `keyguard_locked` as `true`.

## H7 (S2) `moveTaskToBack` ejects a user who is browsing
- Pair: `MainActivity` author vs UX author of the call-end flow.
- AD-1: "When a call surface ends, moveTaskToBack(true)". If the user opened Recents manually and a call ends, they are thrown to the launcher; if they answered a call and then navigated, same.
- Fix **AD-1b**: only move to back if the activity was brought forward by `CallSurface.raise` and the user has not navigated since (flag `launched_for_call` cleared on first non-call tab interaction); otherwise remain on the last tab. Flag lives in Kotlin, delivered as snapshot field `surface_reason`.

## H8 (S2) Wire shape is unspecified where it matters
- Pair: `SnapshotJson.kt` author vs `core::wire` author.
- AD-10 only says `v: 1` and unknown-field tolerance. Open: enum string values (an unknown call state like `pulling`, `simulated_ringing`, `dialing`, `connecting` makes serde fail and the whole snapshot is dropped, so the call vanishes from UI); null vs omitted; `connect_time_ms` is 0 before connect; `normalized` format (E.164 vs national digits, who strips); `contact_id` as Long fits JSON number but `ContactId(i64)` vs `Int` call ids; error envelope; route names; camelCase vs snake_case (org.json easily emits either).
- Fix **AD-10a**: snake_case only; every enum has `#[serde(other)] Unknown` and unknown calls still render with a safe default (ended/dialing) instead of failing the snapshot; absent and `null` are equivalent for optionals; `connect_time_ms: 0` means "not connected"; `normalized` is `PhoneNumberUtils.normalizeNumber` output, digits and leading `+` only, Rust never normalizes itself, and the PRD matching examples are run against this form. Golden fixtures are required, not "or documented": one shared `fixtures/wire/*.json` file read by both the Rust test and a Kotlin JVM test (with `testImplementation("org.json:json")` so `JSONObject` is not stubbed).

## H9 (S2) No error envelope: permission denial is indistinguishable
- Pair: `providers` author (Rust) vs `CallLogQuery.kt` author.
- Convention says `backend.rs` maps errors to `PermissionDenied`, but `rovsti_android` is generic and a Kotlin `SecurityException` surfaces as `Error::JavaException`, with no typed reason. Missing `READ_CALL_LOG` and zero rows look the same; `permissions_changed` has no defined emitter (Android has no callback).
- Fix **AD-9a**: provider methods never throw; they return `{v:1, ok:false, error:"permission_denied"|"unavailable"}` or `{v:1, ok:true, data:...}`. `permissions_changed` is emitted by `MainActivity.onResume` only (plus after the runtime-permission result). Rust maps the envelope to domain errors in `backend.rs`.

## H10 (S2) Missed-call state has three owners and no notification owner
- Pair: Recents badge author vs `markMissedCallsRead()` author vs notification author.
- AD-9 says provider write `markMissedCallsRead()`; Conventions say "missed-seen time = JSON in data_dir, written by Rust only"; AD-14 computes badge "given last-seen". If both are implemented the badge clears in one and not the other. As default dialer the system no longer posts the missed-call notification, and AD-2's incoming notification is removed at call removal, so a missed call leaves no trace (PRD Q4 deferred, yet FR-25 is mapped).
- Fix **AD-9b**: missed badge = count of `CallLog` rows with `NEW=1 AND TYPE=MISSED` (provider is the single owner); opening Recents calls `markMissedCallsRead()` and nothing writes a Rust last-seen. Delete the "missed-seen time" row from Conventions. **AD-2a**: `CallNotifications` posts a minimal missed-call notification (channel `missed`, default importance, no number in the lock-screen public version) when an incoming call is removed unanswered; Q4 is thereby closed for MVP.

## H11 (S2) Notification and foreground-service ownership
- Pair: `CallNotifications` author vs `RovstiInCallService.startForeground` author.
- AD-18/Kotlin components: the FGS starts "on first live call" with `ONGOING_ID` ongoing notification, but the first live call is ringing and AD-2 posts a separate incoming notification. Result: two notifications, or the FGS notification shown low-importance while ringing. A second call has no defined notification (which call does End target? PendingIntent extras bake in a stale `call_id`). Stale actions after a call ends: a late Decline for an ended id is a no-op (AD-6) but the notification remains.
- Fix **AD-2b**: one notification id per `call_id` (`base + call_id`); the FGS notification is whichever call is foreground and is swapped in-place with `startForeground` re-call; actions carry `call_id` and `seq`; the registry cancels a notification in the same step it removes the call. Channels: `incoming` (high), `ongoing` (low), `missed`.

## H12 (S2) Permission list is a delta, not a contract
- Pair: manifest author vs Setup (`core::setup`) author.
- AD-18 lists adds/drops only. Not named: `POST_NOTIFICATIONS` (runtime, Android 13+; without it the CallStyle notification, and thus AD-2 answerability, silently fails), `USE_FULL_SCREEN_INTENT`, `CALL_PHONE`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `READ_CONTACTS`, `WRITE_CONTACTS` (`setStarred`), `READ_PHONE_STATE`. `core::setup` derives from a "readable-facts struct" no AD defines.
- Fix **AD-18a**: list the complete merged-permission set and checks it with `aapt2` in `check-apk.sh` (allowlist, not just INTERNET absence). Setup facts struct = `{role_held, post_notifications, full_screen_intent, read_contacts, write_contacts, read_call_log, write_call_log, call_phone, xiaomi_*}` defined in `rovsti_core::wire`, produced by one Kotlin function. `POST_NOTIFICATIONS` denial is a blocking Setup item with a native fallback explanation.

## H13 (S2) Session lifecycle: main() reruns against surviving statics
- Pair: Rust app author vs Kotlin attach author.
- Per hosting.md `main` runs once per new session and config changes retain the session, but a swipe-away destroys the Activity while the process (and live call) stays; reopening runs `main()` again in the same process. AD-8 makes only `BRIDGE.set` idempotent. Other process statics (ContactIndex cache, storage handles, perf rings, accent `set_accent`, the sink) are unaddressed. Also AD-17 says keep notification on error, but not what happens to `launched_for_call` or the in-flight Rust-only dial string.
- Fix **AD-8a**: no process-static in Rust except the sink and bridge; everything else is created in `main()` and owned by the session; `set_accent`, logger init and storage opening are idempotent. Test: close and reopen session with a live emulated call.

## H14 (S3) Storage writers and unowned pulls
- `storage` JSON in `data_dir`: Setup writes Xiaomi confirmations on the main thread, last tab on another; read-modify-write of one file loses updates. Fix **AD-14b**: one file per key, atomic write-rename, written only from the main thread.
- Contact photos (AD-21 decodes to 128 px) have no data path: AD-9 lists no photo method and AD-10 says bridge moves strings only. Fix **AD-9c**: `providers::photo(contact_id) -> Option<Vec<u8>>` (compressed bytes via a byte-array JNI, sized thumbnails, off main), or Kotlin pre-decodes to a cache directory path returned in the contacts JSON.
- DTMF: press/release ownership undefined (Kotlin fixed 100 ms vs Rust press-hold); post-dial `;` wait (`onPostDialWait`) has no snapshot field; the `tel:` URI must be built with `Uri.fromParts("tel", raw, null)` so `#`/`*` survive. Fix **AD-11a**: `dtmf_start(call_id,char)`/`dtmf_stop(call_id)` pair; `post_dial` field in snapshot; Kotlin must use `Uri.fromParts`.
- AD-16 states GPUI mutates "on the main Looper"; hosting.md confirms drawing is on the main Looper, so the assumption holds; keep it and say so explicitly so `main()`'s `snapshot()` call cannot be moved to a background thread.
- InCallService unbind/role revoked mid-call: no spec to clear the registry and push an empty snapshot on `onUnbind`/`onDestroy`. Fix inside **AD-4a**.

---

## Suggested AD edits (summary)
AD-1a/1b (surface raise, task back rule), AD-2a/2b (missed notification, notification ids), AD-3a (locked-empty and keyguard listener), AD-4a (single-thread registry, seq atomicity, unbind clear), AD-5a/5b (guarded emit, attach order), AD-6a (answerReplacing as pending op), AD-8a (no process statics), AD-9a/9b/9c (error envelope, missed-badge owner, photos), AD-10a (wire semantics, shared fixtures), AD-11a (DTMF, tel URI), AD-14a/14b (explicit foreground/waiting ids, storage), AD-18a (full permission allowlist and Setup facts).
