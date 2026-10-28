# Adversary review: Architecture Spine v0.2 (AD-23..AD-36 and the 2026-10-28 amendments)

Lens: build two units one level down that each obey every AD to the letter and still do not fit. Spine read in full; code read: `CallRegistry.kt`, `MainActivity.kt`, `RovstiBridge.kt`, `crates/rovsti_core/src/{call,wire}.rs`, `crates/rovsti_ui/src/storage.rs`, `apps/phone/src/{backend,incall}.rs`. The spine was not edited.

## Verdict

Not safe to hand to parallel story authors as written. The v0.1 core (AD-1..AD-9) is sound and the v0.2 additions mostly extend it well, but there are 6 holes that will produce incompatible builds or real field bugs, plus a second tier of 8 smaller gaps. Each hole below has a pasteable AD wording fix. Highest risk: H1 (one-shot UI requests and results vanish when no GPUI sink exists), H2 (the coalescing list leaves `onDetailsChanged` unclassified and splits structural callbacks that feed `Focus`), H3 (the pending-operation machine is under-specified and "complete" means two opposite things).

The claim "12 ms coalescing never delays answerability" is true for the Kotlin notification path and false as an end-to-end statement. See section "Answerability claim".

---

## Top holes

### H1. One-shot Kotlin-to-Rust requests are lost when there is no sink (reply sheet, SAF, ringtone)

Units: Kotlin story "Message notification action" (AD-2 amendment) and Kotlin story "SAF export/import" (AD-30), against the Rust story "event drain".

Both obey the ADs:
- AD-2 says the Message action is an activity PendingIntent with extra `reply_sheet_call_id`. No event kind, no field and no pull carries that extra to Rust. The event kinds in AD-5 and the Consistency table are `calls, call_log_changed, contacts_changed, permissions_changed, blocked_changed, sims_changed, document_result, ringtone_result, voicemail_changed`. None is a "UI request".
- AD-5: "Events with no sink are dropped; attach re-pulls." Calls and providers can be re-pulled. `document_result`, `ringtone_result` and the reply-sheet request cannot: they are one-shot facts with no pull.
- Real sequence 1: the user taps Message on the heads-up while GPUI is warm-but-stopped, or the session is closed (activity destroyed, process alive because of the FGS). `onNewIntent` or `onCreate` runs, the sink is absent or is the previous session's cleared one, and the request is dropped. The sheet never opens and the call keeps ringing.
- Real sequence 2: `onActivityResult` for SAF or the ringtone picker runs in `onCreate` to `onResume` of a recreated activity, before `main()` has installed the new sink (the session is created at first Surface). `document_result` is dropped. Export "succeeds" in Kotlin and Rust never learns it.
- Real sequence 3 (Xiaomi low RAM): the process is killed while the picker is up. AD-30's "pending slot" in `exportDocument` is in-process, so it is gone. The result arrives with no op and no JSON text to write.

Fix (add to AD-5 and AD-30):
> **One-shot events (`document_result`, `ringtone_result`, `ui_request`) are mailboxed.** Kotlin keeps a process-static single-slot-per-kind mailbox in `RovstiBridge`. `emit` for these kinds stores the payload and, if a sink exists, also pushes it. Rust drains the mailbox once per attach, right after the `calls` snapshot (`RovstiBridge.takeMailbox() -> String`, a JSON array), and acknowledges by clearing it. A request carries `call_id`/`op` plus `token`; Rust ignores an unknown or stale `call_id`. The `reply_sheet_call_id` and `answer_call_id` extras become `ui_request{kind:"reply_sheet", call_id}`. Request codes are stateless: `requestCode` encodes the op (export 0x5201, import 0x5202, ringtone 0x5203). Export does not keep the JSON in Kotlin: Rust answers `document_result{op:"export_uri", uri}` with `RovstiBridge.writeDocument(uri, text)` on a worker thread.

### H2. Coalescing list is incomplete and splits callbacks that feed `Focus`

Units: Kotlin story "scheduleRebuild() coalescing" (F0) and Kotlin story "Focus.of / conference merge" (15.x), plus the Rust `SceneDiff` story.

- AD-23 lists the coalesced callbacks: `onCallEndpointChanged`, `onAvailableCallEndpointsChanged`, `onMuteStateChanged`, `onChildrenChanged`, `onParentChanged`, `onConferenceableCallsChanged`, `onCanAddCallChanged`. Immediate: `onCallAdded`, `onCallRemoved`, `onStateChanged`.
- Not classified: `Call.Callback.onDetailsChanged` (built code rebuilds immediately on it). It is where `caps` (`hold`, `merge`, `swap`, `respond_via_text`, `split`) and `props` change, i.e. the inputs of `can_hold`, `can_merge`, `can_swap` and the Waiting card choices. A Kotlin author may legitimately choose either class.
- `onParentChanged`/`onChildrenChanged` are coalesced, but `Focus.of` (AD-24) selects "top-level calls only (`parent_id` absent)" and runs inside the immediate rebuild of `onCallAdded` for the new conference call. Sequence on merge: the conference call is added (immediate rebuild) before Telecom has delivered `onParentChanged` for the two originals. That snapshot has 3 top-level calls: foreground = conference, background = the lowest-id original, a second original "extra" (logged, unrendered). Rust renders `Two{conference, held}` for up to one rebuild window, then `Conference`. A visible scene flip and a spurious `CallEventRing` entry, in the one flow the owner complained about.
- Also unspecified: whether an immediate rebuild cancels the pending 12 ms Runnable. If not, every state change produces an immediate snapshot plus a redundant one 12 ms later, and the `SceneDiff` is computed twice.

Fix (replace the AD-23 coalescing sentence):
> `scheduleRebuild()` is only for events that do not feed `Focus.of`, `caps`, `props`, `can_*` or `pending`: `onCallEndpointChanged`, `onAvailableCallEndpointsChanged`, `onMuteStateChanged`, `onCanAddCallChanged`, `onSilenceRinger`. `onCallAdded`, `onCallRemoved`, `onStateChanged`, `onDetailsChanged`, `onParentChanged`, `onChildrenChanged` and `onConferenceableCallsChanged` rebuild immediately. Every rebuild (immediate or scheduled) cancels the pending Runnable, reads all state live from the `Call` objects and the stored endpoint list at execution time, and assigns `seq` at execution time. Endpoint and mute fields are stored in the callback itself, not inside the Runnable. Rust's `Focus` consumer treats a snapshot with more than 2 non-ringing top-level calls as transitional: it keeps the previous `CallScene` until the next snapshot (max one frame).

### H3. The pending-operation machine: "complete" means two opposite things, and nothing defines supersession

Units: Kotlin story "Swap/Merge" (15.x), Kotlin built `answerReplacing` (as built), Rust story "pending look" (`incall.rs`).

- As built, `completePending()` performs the final action: it calls `answer()` on the target. AD-25 says "starting a new op first completes the old one". For `AnswerReplacing` that means a second op started within 2 s answers the waiting call immediately, before the replaced call is gone, so Telecom holds the active call instead of dropping it (the opposite of End-and-answer). For `Swap` the same sentence means running the 1 s fallback `hold()`. For `Merge` it means nothing. Three behaviours behind one word.
- Double tap on Swap: op1 `Swap(toResume=B)` pending; op2 `Swap(toResume=A)` "completes" op1 (fallback `hold()` on A if still Active) and then `unhold()`s A. Result: unpredictable, possibly both held.
- One `timeout` Runnable of 2 s is shared. Swap needs a 1 s check and Merge a 2 s check. A single Runnable cannot carry both, and `rebuild()` calls `removeCallbacks(timeout)` when the target disappears, which would also cancel a newer op's timer.
- "Matching change in `onStateChanged`" is undefined for Swap: any state change (including the remote party putting the call on hold or hanging up) matches.
- Rust-side owner clash: `incall.rs` today clears its local `pending` on every applied snapshot (line ~715) and has its own 120 ms `PENDING_LOOK`. AD-25 adds a wire `pending` field and says Rust "clears it on the report or at its own 2 s limit". The Rust author owns two timers and two clearing rules; a coalesced endpoint snapshot arriving 12 ms after the tap clears the look before the state flips (flicker). Two owners of the pending look.

Fix (rewrite the second sentence of AD-25):
> `CallRegistry` holds at most one `PendingOp` with its own deadline Runnable (answer 2 s, swap 1 s then fail, merge 2 s) and an op id. `finish(op, reason)` has exactly two outcomes: `Done` (clear, no side effect) and `Failed` (clear, run the op's one documented fallback, set `op_failed:"swap"|"merge"|"answer"` for one snapshot). `AnswerReplacing` answers the target only from `onCallRemoved` of the replaced call or from its own timeout, never from supersession. Starting a new op while one is pending is refused (`false`) except `AnswerReplacing`, which cancels the old op with `Done` and no fallback. The matching change for Swap is: the call to resume is Active and the other is Holding; for Merge: a top-level call with `props.conference`. Rust shows the pending look if and only if the latest applied snapshot carries `pending`; the local 120 ms press look is separate, never cleared by a snapshot that lacks `pending` unless the reported state changed; Rust does not run its own 2 s timer.

### H4. The AD-26 safety net asks Kotlin to know whether a UI exists, and cannot

Units: Kotlin story "SELECT_PHONE_ACCOUNT safety net" and Rust story "sink lifecycle / SIM chooser".

- AD-26: the registry auto-selects "when no UI can choose (no native sink, keyguard locked, or 10 s elapsed)". "Native sink" lives in Rust (`events::install_sink_owned`, cleared on release). Kotlin only knows `RovstiBridge.attachNative()` ran, a one-way latch. After the first session closes (activity destroyed, task backgrounded, process alive for the FGS), Kotlin still believes native is attached and `emit` keeps firing into a cleared sink.
- Result: a dual-SIM call placed from Bluetooth/Assistant reports `selecting_account:true` to nobody and waits 10 s in dialing. The spine's "a call is never stuck" claim becomes "stuck for 10 s".
- The same blindness hits any Kotlin decision that depends on "Rust is listening": the vibrate/flip logic (no), `ui_request` delivery (H1), and the Message action's `has_replies`.

Fix (add to AD-5):
> Rust reports its attach state: `RovstiBridge.uiAttached(on: Boolean)` (Rust to Kotlin static, already within the allowed shapes), called after step 2 of the attach order and from `on_release` of the session. `CallRegistry.uiReady = uiAttached && surface?.resumed == true`. AD-26's "no native sink" is `!uiReady`; the safety net uses `uiReady` and, when the account chooser is shown, the 10 s timer restarts on every Rust acknowledgement `callSelectAccountShown(call_id)`.

### H5. Prefs persistence: unordered detached saves and a lossy downgrade

Units: Rust story "Settings store" (16.2) and any Rust story calling `update_prefs` (speed dial, replies, favorites order, backup import).

- Built `Backend::save_prefs` clones the prefs and spawns a detached background `prefs.save(&dir)` per call. AD-30 adds "coalesce to one per 250 ms and flush on pause", but a flush-on-pause can overlap a still-running coalesced save. Both write the same `prefs.json.tmp`; the rename order is not the clone order, so an older snapshot can win (lost settings) and a shared temp file can be truncated mid-write. This is the single-writer rule violated by the writer itself.
- v1 to v2: AD-30 backs up only a higher-than-known file (`.bak-v{n}`). A v1 file is overwritten on the first v2 save. Sideloading a per-epic APK makes a downgrade plausible; the old build then loads the v2 file (its `#[serde(default)]` struct drops speed dial, replies, favorites order) and saves, destroying them. Re-upgrading yields defaults.
- Favorites pruning: "Keys of contacts that no longer exist are pruned only after a successful non-empty contacts load." `LOOKUP_KEY` changes when contacts are re-aggregated (sync, merge). A routine contacts sync therefore deletes the owner's drag order permanently; a backup imported from another phone loses its order on the first load.

Fix (extend AD-30):
> All saves go through one `PrefsWriter` task owned by `Backend`: a single-slot channel holding the latest `Arc<Prefs>`; one background worker writes `prefs.json.tmp.{n}` then renames; flush-on-pause sends and awaits that worker. Before the first v2 save over a file with no `schema_version`, copy it to `prefs.json.bak-v1`. Favorites order never prunes: unknown keys are skipped at render and kept in the stored order; pruning happens only on an explicit user action (Reset order) or when a key stays unresolvable across 3 successful loads over at least 7 days (`unseen` counter in `ui`).

### H6. Wire shapes outside `rovsti_core::wire` have no schema owner, so two authors will diverge

Units: Kotlin `SimAccounts.kt` and Rust `sim_chooser.rs`; Kotlin `Blocklist.kt` and Rust `blocked.rs`; Kotlin `Documents.kt`; Rust `settings.rs`.

AD-10: "All JSON shapes are serde structs in `rovsti_core::wire`" with goldens read by Kotlin tests. The amendment excludes "Settings and backup JSON". The following are defined only inline or not at all, which is the exact drift AD-10 is meant to prevent:

- AD-26 `phone_accounts`: `color` (ARGB Int or `#RRGGBB` string?), `slot` (0- or 1-based, absent vs 0?), `sim` (bool, string, or `physical|esim`?), `ask`.
- AD-27 `list()` and `pullBlockedCalls`: no row shape; `block_reason` strings given, ids and the number field names not.
- AD-28 `callRejectWithMessage` returns the string `sent|opened|rejected_only|stale`, while AD-6 says commands return Boolean and AD-31 says writes return envelopes. Three result conventions on one seam; the Rust JNI signature (`String` vs `bool`) differs by author.
- AD-31 `delete(ids)` and `restore(entry)`: JNI "stays String, Int, Boolean" so the id list and the entry need an encoding; call-log row id type (Long vs String) is undefined (`contact_id` is already passed as a String in `setStarred`).
- AD-30 `settingsSync(json)` has a fixed key subset but no schema or golden; Rust builds it with serde, Kotlin reads with `org.json`.
- `document_result {op, ok, name?, text?, error?}` and `ringtone_result` are not DTOs either; `ringtone_result` has no fields at all.

Fix (replace the AD-10 amendment's last sentence and add to AD-6):
> Every JSON that crosses the seam, including `phone_accounts`, `voicemail_info`, blocklist rows, `settingsSync`, `document_result`, `ringtone_result` and `ui_request`, is a serde struct in `rovsti_core::wire` with a golden fixture read by a Kotlin JVM test. Only backup file contents and `prefs.json` are exempt. Rules: ARGB colors are signed `Int` named `color_argb`; slots are 0-based `Int` named `slot_index`; all provider ids are `String`; id lists are JSON arrays in a `String` parameter. Every Rust-to-Kotlin call that can fail returns the AD-31 envelope string, except the call commands of AD-6, which return Boolean; `rejectWithMessage` returns an envelope with `data:{outcome}`.

---

## Second tier (smaller, still concrete)

### M1. `Two` versus `Conference` scene precedence is undefined (two Rust screens)

`CallScene` is `Single | Two{primary,held} | Conference{host,participants}` plus `waiting`. A conference (active, top-level) next to a held single call matches both `Two` (two top-level calls) and `Conference` (foreground has `props.conference`). `incall.rs` and `conference.rs` authors each pick one. Fix: "`Conference` wins when the foreground has `props.conference`; its variant carries `held: Option<Call>`; `Two` is only for two non-conference calls."

### M2. Name format is applied in two places (two owners of one display)

AD-2: the caller name is resolved once in Kotlin and the notification shows it. AD-32: Rust applies `NameFormat` (primary or alternate) to `display_name`/`display_name_alt`. The Kotlin settings mirror has no `name_format`. The notification and the lock-screen call surface can show "Yilmaz, Ayse" while the in-app screen shows "Ayse Yilmaz" for the same caller. Fix: add `name_format` to the AD-30 mirror subset and make `ContactsQuery.lookup` return the formatted name in `display_name`, with Rust never re-picking for calls.

### M3. Mirror reader for `flip_supported`, and mirror defaults

AD-29: "No sensor means `flip_supported:false` in the mirror and a dimmed row", but the mirror is a read-only cache fed by Rust, and no JNI getter or event carries `flip_supported` to Rust. Also the mirror defaults "equal v0.1" (no replies), but PrefsV2 defaults include three replies, so until the app has been opened once after install the notification has no Message action. And `settingsSync` must not clear Kotlin-written keys. Fix: "`flip_supported` and `keypad_tones_system` are Kotlin-written keys in a separate `SharedPreferences` file `rovsti_phone_kt`; Rust reads them through `RovstiBridge.hostFacts() -> envelope` on attach. Mirror defaults equal PrefsV2 defaults (`has_replies:true`)."

### M4. Keyguard sheet race on Add call, and the re-lock direction

AD-24/AD-3: Add call runs `requestDismissKeyguard`, then opens the sheet "only from a success callback". After the callback, the snapshot's `keyguard_locked` can still be true (listener or `ACTION_USER_PRESENT` arrive later), so Rust either closes the sheet at once (call-only mode) or the sheet opens against a stale lock flag. In the other direction, the AD says the sheets do not *open* while locked but not that they *close* when the screen locks with them open (Favorites/Contacts segments of Add call, Call details), a privacy leak on re-lock. Fix: "The success callback calls `CallRegistry.refreshKeyguard()` synchronously and Rust opens the sheet on the first snapshot with `keyguard_locked:false`. Any change to `keyguard_locked:true` closes every non-call surface within the same applied snapshot."

### M5. `keyguard_locked` default and the blank backdrop

AD-3: "a missing value counts as locked" and "locked with no calls: blank backdrop and `moveTaskToBack`". `CallModel` and `CallSnapshot::default()` default to locked. Any code path that renders before the first snapshot (desktop, a cold launch from the launcher where the attach snapshot has not been applied, `reset()`) blanks the app. Also unspecified whether the blank/back rule fires on the *transition* (call ended) or on any state (user presses power while in Contacts; the keyguard locks; the app would drop itself to the background). Fix: "Blank backdrop plus `moveTaskToBack` fire only on a transition from calls non-empty to empty while locked, never on a first snapshot or on a screen-off with no call. Before the first snapshot Rust renders its normal launch state unlocked."

### M6. Endpoint diff baseline, legacy flip

`EndpointList` yields added/removed ids "for the arrival and loss moments". On every attach (a new GPUI session mid-call) every endpoint would be "added", firing haptics and moments, and the "newest arrival first" Bluetooth order is lost; the 1.5 s legacy switch changes all ids from UUIDs to `legacy:*` mid-call with the same effect. Fix: "The first snapshot of a session, and the first after an id-scheme change (`audio.scheme: "endpoint"|"legacy"`), seeds the baseline with no moments."

### M7. Snapshot application "once per frame" when no frame is drawn

AD-35: "at most one snapshot is applied per frame (highest `seq`)". If this is implemented in the frame callback, a stopped/hidden window (task in back, surface destroyed, idle = 0 frames per AD-21) never applies snapshots, so `incomingFrame` (story 5.6) and the ring state never advance. If implemented in the drain task (as `Backend::handle` does today) it is not per frame. Fix: "The drain task applies the highest `seq` of everything currently queued per wake-up; `cx.notify()` is what coalesces to a frame. Idle (no window) still applies, without rendering."

### M8. Contact-edit flows, minor

`display_name` for a call is resolved once; a rename during a call is not reflected. Acceptable, but say so ("resolved at `onCallAdded`; not refreshed").

---

## Answerability claim: "a 12 ms rebuild coalescing never delays answerability"

Verdict: holds for the notification path, fails as written for the end-to-end claim, and the AD does not state its own preconditions.

What holds. In the built code the notification, `knownIds`, `ringingIds` and `incomingNow` are all produced inside `rebuild()`. If the three state-driven callbacks stay immediate (AD-23 says they do) a new or changed ringing call posts its CallStyle notification in the same main-Looper turn, independent of the 12 ms window. Answer and Decline are registry-only.

What does not hold, or is unstated:
1. **Classification gap (H2).** `onDetailsChanged` and the structural callbacks are unclassified or coalesced. A call that becomes answerable via a caps change (for example the Hold-and-answer offer depends on the current call's `caps.hold`) is offered 12 ms plus a frame late. Not a missed call, but "never delays" is wrong for the Waiting-card choices.
2. **Same Looper (AD-16).** GPUI foreground tasks and Telecom callbacks share the main Looper. Answer from the notification is `main.post { call.answer }`; a long GPUI turn (first-frame wgpu pipeline compile AD-21 "pre-compile at startup", a 500-row call-log swap, a 2,000-contact index swap, a snapshot parse with a conference of many children) delays it. AD-2 says answerability "never depends on GPUI" but the thread it runs on does. A cold Answer tap goes through `MainActivity.onCreate`, which runs `handleCallIntent` before `super.onCreate`: good, but on a warm session it queues behind GPUI. Fix: "No GPUI turn on the main Looper may exceed 8 ms; pipeline pre-compile and index builds run off the Looper. The notification Answer for a warm session is `CallActionReceiver`-style direct (registry-only), not an activity intent; the activity intent variant is used only when the surface is absent."
3. **Coalesced Runnable versus an immediate rebuild.** If the immediate rebuild does not cancel the pending Runnable, a state change in the window produces two snapshots; harmless for answerability but doubles `SceneDiff` work (H2 fix covers it).
4. **Perceived latency.** Rust applying one snapshot per frame adds up to 16.6 ms on top, so tap-to-"Active" in the UI is up to 12 ms + one frame + the callback. Within NFR-3's 500 ms, so not a defect, but the sentence "never delays" should read "does not delay the answer command or the notification; it can delay the displayed state by at most 12 ms plus one frame".

Suggested replacement sentence for AD-23: "Coalescing applies only to snapshots of audio, mute and `can_add_call`. It never defers `Call.answer`, `reject`, the incoming notification, `knownIds`, `ringingIds` or `incomingNow`; those are updated in the callback that carries the state change."

---

## Checked and found sound (no action)

- Attach order and seq: a queue holding `[N+1 pushed, N pulled]` is handled by "ignore lower seq"; `reset()` per session is correct because the Kotlin `seq` is process-lifetime and Rust's model is per-session.
- Scheduled rebuilds assign `seq` at execution time from live state, so a late coalesced rebuild cannot publish a stale snapshot with a higher `seq`.
- Stale endpoint ids return false and `CallEndpointException` forces a rebuild (AD-23), so a picker tap on a vanished device reverts cleanly.
- Unknown enum values degrade safely in the Rust wire parser (`#[serde(other)]`), including the new `pending` optional enum.
- Emergency: Rust (`is_emergency`) and Kotlin (`props.emergency`) can disagree only on the displayed lock of add/merge/block, never on placing a call, because placement skips every prompt. Low risk, but the single source should be one list (Kotlin `props.emergency` for incoming/foreign calls, Rust for dialled), which AD-33 leaves implied.
