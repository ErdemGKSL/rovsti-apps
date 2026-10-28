# Reconcile review: spine v0.2 (AD-23..AD-36) against PRD 4.11, gap analysis, EXPERIENCE v0.2, DESIGN v0.2

Lens: for each load-bearing input, what did the updated spine fail to land. The spine was read in full; PRD 4.11 (FR-41..FR-73), NFR-14..18, Q6..Q9, the gap analysis, EXPERIENCE v0.2 (surfaces, State Patterns, Interaction Primitives, Accessibility, Voice, Acceptance P-11..P-23, UX-Q5..Q19) and DESIGN S17..S36 were read. The v0.1 code was grepped where a claim depended on it (`incall.rs` ENDED_HOLD, `MainActivity.handleCallIntent`, `GpuiActivity.onBackPressed`). The spine was not edited.

Verdict: the structure (wire, registry, pending-op machine, spikes) is sound and covers almost every FR. What is missing is mostly the quiet layer: data the UX renders that no wire field carries, a few places where the AD rule contradicts the UX/PRD, and several PRD/UX constraints that no AD owns. 8 contradictions (C1..C8) need a recorded decision, 17 misses (M1..M17) need a sentence or a field. Items are ordered by how likely they are to cause rework in a story.

Severity: A = will cause a wrong build or a rework unless fixed before stories; B = a story will hit it, cheap to fix now; C = note.

---

## Contradictions that need a recorded decision

### C1 (A). The "Call ended" hold cannot render when a second call exists (AD-24 vs EXPERIENCE two-call view, S11, Add call failure)

- AD-24 `Focus.of`: foreground = Active, else Dialing, else **Holding**, else Ringing, else Disconnected. A Disconnected call is foreground only when nothing else is alive.
- EXPERIENCE (Two-call view): when the active party hangs up "the active block shows 'Call ended' for 1 s and then the held party takes the active block, status 'On hold'". Add call: a failed second call shows "Call failed: {reason}" for 2 s while the first call stays on screen. S11 says the hold is 1 s, 2 s for failures.
- With the AD-24 order the Holding call wins at once, the Disconnected call is never the primary, and the "Call ended" and "Call failed" phases for the second call never appear. v0.1 only works because with one call the Disconnected call is the only candidate (`incall.rs` `ENDED_HOLD`, `Phase::Ended`).
- Decision to record: the ended phase is an overlay slot owned by Rust, keyed by `call_id`, not a foreground choice. Kotlin keeps a Disconnected top-level call in `calls[]` until the next snapshot after `onCallRemoved` only so Rust can read `disconnect_cause`.
- Add to AD-24, after the Selection bullet:
  > **Ended phase.** `Focus.of` never lets a Disconnected call outrank a live one. `CallRegistry` emits the removal of a call as one snapshot in which that call is `disconnected` with its `disconnect_cause` (and `name`/`number_raw`), then drops it on the next change. `CallScene` gains `ended: Option<EndedCall{call_id, display_name, cause, was_foreground, hold_ms}>`, set by `CallModel::apply` when a foreground or waiting call goes `disconnected`, with `hold_ms` 1000 (2000 when the cause is a failure) and expired by Rust (a core timer value, not Kotlin). While `ended` is `Some` the primary block shows its text and the live call that outranks it is drawn in the Held-card slot (held calls) or the Waiting overlay (ringing); when it expires the live call takes the primary block with no geometry change. A lagging snapshot of an ended `call_id` never re-opens it (as built).

### C2 (A). AD-21/AD-22 forbid visual degradation; DESIGN/EXPERIENCE define one (and P-14 is a gate)

- AD-21: "A missed target stays a blocker and is never closed by a visual trade"; "Refractive-surface counts ... are advisory, not constraints"; AD-22: thermal state "not used to alter visuals".
- DESIGN v0.2 and EXPERIENCE State Patterns "Degradation": "S18 dot, S21 pulse, S24 rotation, S29 wash, S34 displacement, S36 sweep stop first". P-14 in EXPERIENCE: "At most 4 refractive surfaces per v0.2 screen; scaled surfaces use the flat look until settled". EXPERIENCE also caps signature moments: "At most two run at once on a screen; the later one waits 120 ms".
- Three different rules, no owner. A story writer reading the UX would implement an adaptive degrader the architecture forbids.
- Decision to record (consistent with AD-21): no adaptive degradation. The "at most two moments" collision rule is choreography, not a performance trade, and stays. P-14 is reported, not gated.
- Add to AD-21 (after the Rule):
  > **UX counterparts.** The DESIGN/EXPERIENCE "Degradation" rule is not implemented: no moment is dropped because frames run long. P-14's "at most 4 refractive surfaces" and "flat look until settled" are measured and logged by the perf harness (count per screen state in `RovstiPerf`) but are not release gates; a screen over 4 is optimised under measures (1) to (5), not trimmed. The one cap that stays is choreographic: `rovsti_ui::moments::Scheduler` runs at most two signature moments per screen, a third waits for the first 120 ms of the earlier one, S3 and S8 bypass it (EXPERIENCE Signature Moments). Reduced-motion fallbacks remain the only per-moment visual variants.

### C3 (A). T9 on the UI thread (AD-33) vs "off the UI thread" (NFR-18, FR-65)

- NFR-18: "T9 search over 2,000 contacts completes within 8 ms **off the UI thread**"; FR-65: "never block the UI"; EXPERIENCE: result "within one frame of the digit".
- AD-33: `ContactIndex::t9` "runs on the key handler thread", benchmark under 8 ms. 8 ms on the UI thread is half of a 60 Hz frame and all of a 120 Hz one, and AD-21 holds CPU frame work under 4 ms.
- Decision to record: keep inline, because narrowing is incremental, but make the budget the frame's, not 8 ms.
- Add to AD-33 (T9 bullet):
  > **Budget and thread.** The inline call is allowed only because it narrows from the previous candidate set: p95 under 1 ms for a 2,000-contact index on the device for digits 2 and beyond. The first digit uses a per-digit precomputed bucket (built in `ContactIndex::new`), so it costs a slice copy. The 8 ms figure from NFR-18 is the worst-case ceiling of the unit benchmark (cold query, no narrowing), not the per-key cost, and the perf scenario S-t9 asserts the 1 ms p95 on device. If S-t9 misses, the fallback is a one-frame-stale result computed on `cx.background_executor()` (the digit is never delayed; a stale result is dropped by a query sequence number). NFR-18's "off the UI thread" is read as "never blocks a digit".

### C4 (B). Keypad tones: UX bans app-played sounds; FR-71 and AD-30 add a toggle

- EXPERIENCE Banned list: "app-played sounds". FR-71: "keypad tones (on/off, follows the system flag by default)". AD-30 mirrors `keypad_tones` for "components that run without Rust" but nothing says who plays a tone, on which screen, or how the "follows system" default is read.
- Decision to record: tones are DTMF feedback, played by Kotlin; the UX ban is amended to "app-played sounds other than the keypad tones the owner enabled".
- Add to AD-29 (Haptics bullet):
  > **Keypad tones.** `KeypadTones` (Kotlin) plays `ToneGenerator(STREAM_DTMF, 80)` for the Keypad tab keys only (the in-call keypad's audible tone is the DTMF Telecom plays for the call; `dtmf(char)` starts and stops the tone). A key down on the Keypad calls `RovstiBridge.keyTone(char)` (a static, non-blocking, Boolean method; no-op when disabled or the generator failed), never from the render path. Effective value = `settings.keypad_tones` when `Some`, else `Settings.System.DTMF_TONE_WHEN_DIALING` read in Kotlin on each resume and pushed in the settings mirror as `system_keypad_tones`. Settings shows the toggle in the effective state with the footnote "Follows system" while `None`. EXPERIENCE "Banned: app-played sounds" is amended to exclude these tones; there is no other app sound.

### C5 (B). PRD FR-52 puts quality chips on Recents rows; EXPERIENCE does not

- FR-52: "Call details **and Recents rows** show HD, Wi-Fi and VoLTE". EXPERIENCE Recents row: SIM chip in a 36 px reserved end slot, voicemail icon; quality chips only in Call details history rows and the live In-Call chip row. DESIGN chip is 20 px high with no Recents place. AD-31 puts `features` in the Recents pull, so Rust parses and holds data for 500 rows nobody draws.
- Decision to record: Recents rows show no quality chips (UX wins; the PRD wording is superseded), VoLTE appears only in Details, and the live In-Call row shows HD and Wi-Fi only (`props` has no VoLTE).
- Add to AD-31 (Number history bullet), and amend the AD-9 sentence "carries `account_key` and `features`":
  > The Recents pull carries `account_key` only; `features` and `duration_s` are read only by `pullCallHistory` (Call details). `video` (bit 8) is parsed and ignored (video calls are out of scope). PRD FR-52's "Recents rows" is superseded by EXPERIENCE: quality chips are Details-only.

### C6 (B). Undo for a deleted grouped row (FR-70, UX) vs single-entry `restore` (AD-31)

- FR-70/EXPERIENCE: Recents rows are grouped (consecutive same type, count n); "Delete entry" on the row menu deletes the row; the Notice pill offers Undo 5 s "re-inserts it with the same fields". AD-31: `restore(entry)` is "single-entry Undo".
- A group of 3 missed calls has 3 provider rows. Either Undo restores 3 or there is no Undo for a group.
- Decision: restore takes the whole deletion, as one write.
- Replace in AD-31: `CallLogWrite.restore(entry)` by `restore(entries[])` (one `applyBatch`, all rows or none, same field set). The Recents row menu deletes a group's `ids` in one `delete(ids)` and keeps the full `entries` in the Notice pill's Undo closure for the pill's lifetime only (5 s, 10 s with TalkBack). A restored row gets a new `_id`; Rust re-pulls and never reuses the old id. A group delete shows "Deleted {n} calls". Fields not restored (`CACHED_NAME`, `GEOCODED_LOCATION`, `NUMBER_PRESENTATION` for allowed numbers) are rebuilt by the system; for an unknown or private number `NUMBER_PRESENTATION` is restored.

### C7 (B). "Held card" timer: "On hold · 1:12" has no source (AD-7, AD-24, EXPERIENCE)

- EXPERIENCE: Held card "On hold · {m:ss}" and TalkBack "on hold 1:12". AD-24: "each call keeps its own `connect_time_ms`", which is time since the call connected, not time on hold. The two readings differ by minutes for a call held late.
- No wire field has the moment the call was put on hold.
- Decision: the card shows time on hold, not call length; Kotlin owns the timestamp (it survives a GPUI restart).
- Add to AD-24 (wire call fields): `hold_since_ms?` (epoch ms, set by the registry on the first `onStateChanged` to Holding, cleared on leaving Holding, 0 means unknown). `format_elapsed(now_ms, hold_since_ms)` renders the Held card; the active block keeps `connect_time_ms`. If `hold_since_ms == 0` (a call already held at attach) the card reads "On hold" with no timer. A conference held as one call has one `hold_since_ms`.

### C8 (C). Wording differences with no architectural effect, record once

- PRD/FR-48 "Answer and hold" vs EXPERIENCE "Hold and answer" vs AD-25 `answerHolding`: command name is internal; the UI string is "Hold and answer" (EXPERIENCE Voice and Tone wins).
- FR-49 (SIM section "read-only default only") vs FR-50 (invisible) is already UX-Q10 and the spine's AD-26 follows UX-Q10; the PRD text is stale and should be edited at the next PRD pass. Same for FR-70 "swipe" (UX-Q9, spine Deferred line).
- PRD says the Waiting banner and EXPERIENCE says a 184 px Waiting **card**; no wire difference.

---

## Misses: data the UX needs that no AD carries

### M1 (A). Lost endpoint "Disconnected" row, no-arrival-on-attach, arrival dot (AD-23, S18, UX-Q16)

- UX: while the popover is open a lost device "dims in place with 'Disconnected' and is removed on the next open"; a new device fades in; the Audio control shows a 6 px dot "when a device appeared since the list was last opened" (S18 also says 3 s: UX-internal inconsistency, see below). AD-23 says `EndpointList` "yields the added/removed ids" but not that it keeps the removed row's name and type, so the ghost row has nothing to draw, and does not say a first snapshot is not an arrival.
- Add to AD-23, after the `EndpointList` sentence:
  > `EndpointList::apply(&mut self, endpoints: &[Endpoint], popover_open: bool)` keeps, for each id that disappeared while `popover_open`, a ghost entry `{id, type, name, lost_at_seq}` rendered dimmed with "Disconnected" and not selectable; ghosts are dropped when the popover closes or the call ends. The first snapshot of a call, and the first snapshot after a reattach (new `main()` session), define the baseline: no arrival is reported for endpoints present in it, so a GPUI restart never replays S18. `arrived_since_open: bool` is set by an arrival while the popover is closed and cleared when the popover opens; the Audio control draws the dot while it is true (this is the rule; S18's "3 s" is a fade-out of the dot's pulse, not of the flag). If the lost endpoint was `selected`, the check follows the new `selected` from the next snapshot and the ghost keeps no check.

### M2 (A). Conference participant rows: names, numbers, unnamed children, "left the conference" (AD-24)

- EXPERIENCE rows: name with number as subtitle, avatar stack of up to 3, per-row Split/End, TalkBack "Özgür left the conference" (polite). AD-24 carries children as full calls, so `display_name`/`number_raw` exist, but:
  - IMS and many circuit-switched conferences report children with no handle (no number) or with a placeholder; the UX has no label for that row.
  - A child that leaves is already gone from `calls[]` in the next snapshot, so Rust has no name to announce.
  - `CallScene::Conference{host, participants}` does not say participants are resolved with the same Kotlin lookup as calls.
- Add to AD-24 (CallScene bullet):
  > Participant rows come from child calls and use the call-level `display_name`/`number_raw` Kotlin resolves for any call (the contact lookup runs for children too). A child with neither shows "Participant {n}" (ordinal in `children` order, stable while the child lives) and no subtitle. `SceneDiff.participants` carries `left: Vec<(call_id, label)>` taken from the previous scene's label for the id, which feeds the polite announcement "{label} left the conference" and the S22 End fade. When the carrier hides participants (`children` empty with `props.conference`) the panel is one row "Conference" plus "{n} people" only if `conference_count` (optional wire field from `Call.Details.getExtras` / `EXTRA_*` when present, else absent) is known; the avatar stack then shows the host avatar only.

### M3 (A). Rust cannot see notification intent extras (AD-2 amended, AD-28)

- AD-2 amended: the Message action is an activity PendingIntent with extra `reply_sheet_call_id`; EXPERIENCE: "Message opens the Incoming Call Screen with the reply sheet already open." The v0.1 code handles `answer_call_id` entirely in Kotlin (`MainActivity.handleCallIntent` calls `CallRegistry.answerRinging`); nothing tells Rust to open a sheet. There is no event kind or snapshot field for it, so the third action opens an ordinary Incoming screen.
- Add to AD-5 (event kinds) and AD-2:
  > Kind `ui_intent {action:"reply_sheet", call_id}` is emitted by `MainActivity.handleCallIntent` for the `reply_sheet_call_id` extra (exactly once, extra removed, stale id ignored, same token guard as `answer_call_id`). If the sink is not yet attached the intent is held as `RovstiBridge.pendingUiIntent` and delivered with the attach snapshot. Rust opens the reply sheet only if that call is still ringing (or is the waiting call) and `keyguard_locked` rules (AD-3) allow it.

### M4 (A). "Write your own..." and no-number Message (AD-28, UX Reply sheet)

- EXPERIENCE: the reply sheet ends with "Write your own...", which rejects the call and opens Messages with an empty body. AD-28 `callRejectWithMessage(call_id, text)` requires a non-empty text, and has no command for this path. AD-28 also "hides Message" for a caller with no number, but EXPERIENCE requires Message to be hidden-but-reserved, never removed (NFR-14).
- Add to AD-28:
  > `callRejectAndCompose(call_id)` is `reject(false, null)` followed by `ACTION_SENDTO smsto:{number}` with no `sms_body`; returns `opened|rejected_only|stale`. Used only by the sheet's "Write your own..." row. A ringing call with no number, or `!caps.respond_via_text` while `REJECT_PATH == TELECOM`, draws the Message slot dimmed-and-reserved on the Incoming screen (not removed) and dims the Waiting-card pill with the reason "No number to message"; an empty `quick_replies` list hides it per AD-30 with the slot reserved and the pill reason "Add replies in Settings".

### M5 (A). Silence source: "Silenced by flip", medium haptic (AD-29)

- EXPERIENCE: caption "Silenced" for a tap, "Silenced by flip" for the flip, plus a medium haptic on flip. AD-29: the flip runs in Kotlin and calls `silenceRinger()`; the wire carries `ringer_silenced` (a boolean). Rust cannot tell flip from tap from volume key, and a Rust haptic call has nothing to trigger it.
- Change in AD-24/AD-29: `ringer_silenced?` becomes `silenced_by?: "user"|"flip"|"system"` (absent = not silenced; `system` = `onSilenceRinger()` from a volume or power key). The registry sets it from the command's caller (`callSilenceRinger(source)`) and from `onSilenceRinger()`. The flip haptic is played by Kotlin (`Vibrator`, `EFFECT_CLICK`, gated by `haptics != Off` in the mirror), because Rust may not be attached (screen off, locked). Rust shows the caption from `silenced_by` only. Add `haptics` to the mirror subset (see M11).

### M6 (B). Notice pill outcomes have no wire shapes (AD-31, AD-34, EXPERIENCE Notice pill)

The Notice pill's "uses (complete list)" implies results the spine returns only as Booleans:

- "Opened app settings instead": `open_system(kind)` must return `opened | fallback | failed`, not Boolean. Add to AD-34: `telecom::open_system(kind) -> "opened"|"fallback"|"failed"`; `fallback` means `ACTION_APPLICATION_DETAILS_SETTINGS` was used.
- "No contacts app found" (FR-60): `addContact`/`editContact` return `opened|no_handler`.
- "Missed call from {name}" (Waiting card caller hangs up): the waiting call vanishes from `calls[]`, so no name. Covered by C1's one-snapshot `disconnected` with `disconnect_cause` (`MISSED`/`REMOTE`) plus name; Rust raises the pill when a `waiting` call ends with a non-local cause.
- "Call failed: {reason}" (Add call): needs the mapping `DisconnectCause.getCode()` to a fixed reason set. Add to AD-4/AD-24: wire `disconnect_cause` is one of `local|remote|missed|rejected|busy|no_signal|restricted|error|unknown`; Rust owns the strings. Reasons are labels only, never raw codes; `getReason()` text is not logged above debug.
- "Deleted {n} calls", "Saved to {file}", "Imported": `delete` returns `data:{count}`; `document_result` carries `name` (already).
- Add to AD-31: write envelopes carry `data:{count?}` where a count is shown.

### M7 (B). Blocked reason mapping, blocked rows in Call details, unknown/private history (AD-27, AD-31, UX Call details)

- UX strings "Blocked by you" / "Blocked by the system" (absent if unknown). AD-27 says only "block_reason mapped from BLOCK_REASON (API 29) to user|system|unknown". The mapping is not stated; the provider may leave the column 0 on HyperOS.
- Call details for a blocked number shows a "Blocked" chip and a history; `pullCallHistory` is not stated to include type 6 rows (the Recents pull excludes them), so a number's history would miss the very blocked calls the user came to review. UX Blocked calls rows open Details for the number.
- UX Details for "Unknown / private number" shows header "Unknown" and a history. `pullCallHistory(raw, ...)` with an empty `raw` and the "ends with last 9 digits" LIKE would match every row.
- Add to AD-27 and AD-31:
  > `block_reason`: `BLOCK_REASON_BLOCKED_NUMBER` -> `user`; `BLOCK_REASON_CALL_SCREENING_SERVICE`, `DIRECT_TO_VOICEMAIL`, `UNKNOWN_NUMBER`, `RESTRICTED_NUMBER`, `PAY_PHONE`, `NOT_IN_CONTACTS` -> `system`; `NOT_BLOCKED`/0 or any other value -> `unknown`. Constants live in `WireJson.kt`; the value is read per row and never inferred.
  > `pullCallHistory` includes type-6 rows (kind `blocked`, with `block_reason`) so Details shows them as "Blocked" rows (no duration); Details totals count answered calls only and exclude `blocked`. For an unknown or private number (empty `raw` or `NUMBER_PRESENTATION != PRESENTATION_ALLOWED`) the query is `NUMBER_PRESENTATION = ?` plus the same `NUMBER` empty check, never a LIKE; log rows carry `presentation` (`allowed|unknown|private|payphone`, additive wire) and Rust treats any non-`allowed` row as the "Unknown" group, so Recents rows keep their inert behaviour (UX-Q4) and Details for them is read-only with Call and Block dimmed.

### M8 (B). Frequent: what counts, and the contacts argument (AD-32, UX-Q15, FR-64)

- AD-32 `frequent(entries, now_ms, window_days = 90, limit = 4, exclude_starred)` "counts calls". UX: "the most-called non-starred contacts", at most four, hidden when fewer than 2 qualify, tap opens the Contact Sheet. The signature has no contacts argument (needed for `exclude_starred` and to ignore numbers that are not contacts), and does not say which call types count (missed, rejected, blocked and voicemail rows would make a spam caller "frequent").
- AD-32 also recomputes "once per call-log swap", while UX says "No reflow when data changes while the tab is open (refreshes on the next open)".
- Replace the `frequent` sentence in AD-32 with:
  > `rovsti_core::call_log::frequent(entries, &ContactIndex, now_ms, FrequentConfig{window_days: 90, limit: 4, min_shown: 2})` counts only `outgoing` and answered `incoming` rows (not `missed`, `rejected`, `blocked`, `voicemail`), keyed by contact via `PhoneNumber::matches` (numbers without a contact are dropped), excludes `starred` contacts, ranks by count then latest call then name, and returns `[]` when fewer than `min_shown` qualify. The view takes a snapshot of the result when the Favorites tab is opened (and when the setting toggles); a call-log swap while the tab is open does not recompute it, so nothing reflows under the user. The 90-day window is UX-Q15's accepted default.

### M9 (B). `T9Hit` shape: highlight, matched number, name basis, photos (AD-33, S32)

- UX: "the matching letters in weight 700", the row shows "the matched number (the number that matched, or the default)", a "+n" capsule and a Results sheet (up to 50 rows), avatar with photo or initials, ranking "exact number match first". `T9Hit` is not defined, and `name_format` (AD-32) can make the displayed string differ from the one indexed.
- Add to AD-33:
  > `T9Hit{contact: ContactRef, number_ix: u8, kind: ExactNumber|NamePrefix|WordStart|NumberSubstring, name_span: Option<(u16,u16)>}`. `name_span` is a character range of the **displayed** name (after `names::display`), computed by mapping folded positions back through the fold (diacritic folding is 1:1 per character; a multi-character fold such as `ß` is not folded to more than one). The index is built over both `display_name` and `display_name_alt` words so a query matches either order regardless of `name_format`; word-start and prefix rank use the displayed order. `number_ix` selects which of the contact's numbers matched (default number when the hit is by name). Photos use the existing off-thread 128 px decode and the 64-entry LRU (AD-21); the strip draws initials until the photo is ready and never waits for it. The LRU is raised to 128 entries when the Results sheet is open.

### M10 (B). Dual SIM wire details (AD-26, UX Dual SIM, DESIGN chip)

AD-26 covers the account key, `label`, `slot`, `color`, `sim`, `default_key` and `ask`. Not covered, all cheap:
- `color` encoding and the unset case (`highlightColor` is 0 or alpha 0 for many carriers). Add: `color` is `#RRGGBB` or absent; absent draws the dot in `secondary-label`; the clamp to 3:1 contrast is `rovsti_ui`.
- "Unavailable" row (EXPERIENCE: "account unavailable: the row dims"). Add `enabled: bool` to the account entry (`PhoneAccount.isEnabled`) and dim the row when false; a disabled account is never chosen by the chooser.
- Chip text rule ("SIM 1"/"SIM 2" or the carrier label, max 8 characters). Add: `SimInfo::chip_label(key)` returns the carrier `label` when the labels of all accounts are distinct and non-empty, else "SIM {slot+1}"; truncation to 8 is the view's.
- Call-time chip for a call whose account is unknown: no chip (already), including emergency.

### M11 (A). Accessibility facts and capabilities have no owner (EXPERIENCE Accessibility Floor, P-20, P-23)

EXPERIENCE depends on TalkBack-specific behaviour that no AD mentions:
- TalkBack on extends Undo to 10 s and replaces hold-to-delete with a plain button (AD-27 says "10 s with TalkBack" but nothing defines where Rust learns the state).
- Custom actions replace every drag and gesture (Split, End, Move earlier, Move later, Move up, Move down, Call details, Block, Delete, Call speed dial); a polite live region for the Notice pill and route changes; assertive announcement for the Waiting card; focus return to the opener.
- Whether the pinned gpui-fusion exposes AccessKit custom actions, live regions and assertive priority is unverified. This is a spike-sized unknown that sits under about 20 FR consequences.
- Add a spike row and a fact:

  | Id | Question | Pass criterion | Procedure | Fallback shipped by default | First needed |
  | --- | --- | --- | --- | --- | --- |
  | S14 | AccessKit custom actions, live regions (polite and assertive) and focus return in gpui_android at rev d6c84124 | TalkBack on: a row exposes "Actions" with the custom items; a Notice pill is read once; the Waiting card is read assertively | Diagnostics "Accessibility" adds a test row with two actions and a live region; owner runs TalkBack | Visible buttons for every custom action (a "More" row menu, the 44 px minimum), Notice read as a normal label, no assertive priority | 15.4 |

  > Add to AD-30's mirror and to `PrefsV2`-adjacent facts: `rovsti_android::system_facts()` returns `{touch_exploration, font_scale, reduce_motion, hac_supported, system_keypad_tones}` (Kotlin: `AccessibilityManager.isTouchExploration`, `Configuration.fontScale`, `Settings.Global.ANIMATOR_DURATION_SCALE == 0`, `TelephonyManager.isHearingAidCompatibilitySupported()`), pulled on resume and sent in `permissions_changed`; Rust never infers them. TalkBack mode drives the Undo length (10 s), the hold-to-delete replacement and custom-action registration.

### M12 (B). Hearing-aid compatibility row has no source (FR-43, UX Settings)

- FR-43 and the Settings table: "Hearing-aid compatibility: read-only value Supported / Not reported". Gap analysis names `TelephonyManager.isHearingAidCompatibilitySupported()` (API 23, no permission). AD-34 lists the Settings shortcuts but not this value; it appears in no wire shape or `rovsti_android` function. Folded into M11's `system_facts()` (`hac_supported`); desktop returns `Unsupported`, the row reads "Not available on this platform".

### M13 (B). Disabled-reason table has no owner (NFR-16, EXPERIENCE State Patterns "Capability unsupported")

- AD-24 says `CallScene::disabled_reason` derives the reason from the bits and counts but does not list the reasons. EXPERIENCE lists some strings (merge, two calls, emergency) and leaves others unwritten (carrier refuses add; hold unsupported; Hold and answer when the current call cannot hold; split/disconnect; Swap).
- Add to AD-24:
  > `DisabledReason` is a closed core enum with one string each (English, in `rovsti_core::scene`): `AddCall`: `Emergency` ("Not available during an emergency call"), `TwoCalls` ("Two calls are already in progress"), `Carrier` ("Your carrier doesn't allow adding calls"); `Merge`: `NeedsTwoCalls` ("Merge needs two calls"), `Carrier` ("Your carrier doesn't allow merging calls"), `Emergency`; `Hold`: `Unsupported` ("This call can't be put on hold"); `HoldAndAnswer`: `CurrentCallCannotHold` ("Ends the current call instead: it can't be held"); `Swap`: `Unsupported`. Precedence: emergency, then two calls, then carrier. The Waiting-card pill and the control's TalkBack hint use the same string.

### M14 (B). Settings mirror subset is too small for Kotlin consumers (AD-30)

- Subset: `proximity, flip_to_silence, vibrate_connect, vibrate_end, has_replies, keypad_tones`. Kotlin consumers also need `name_format` (the incoming notification `Person` name comes from `display_name`/`display_name_alt` resolved in Kotlin, AD-2, so it must follow FR-72 or the notification disagrees with the screen), `haptics` (the flip-to-silence haptic, M5), and `system_keypad_tones` (C4).
- Add `name_format`, `haptics` and the Kotlin-written `system_keypad_tones`/`flip_supported` to the AD-30 subset sentence. The notification title uses `display_name` or `display_name_alt` per `name_format`; the posted notification is updated on a settings change only while a call is live.

### M15 (C). Default quick replies and speed-dial assignment semantics (AD-30, FR-57, FR-66)

- AD-30 says "three default replies" with no text. The strings are in EXPERIENCE Voice and Tone; put them in `rovsti_core::settings::DEFAULT_REPLIES` and test them (reset-to-defaults uses them).
- EXPERIENCE: "Assigning a digit already used moves it with a confirmation line '5 moved from Mehmet'". `BTreeMap<u8, SpeedDialEntry>` is by digit, so "moves" means the digit's previous holder is replaced. Define `SpeedDial::assign(digit, entry) -> Replaced(Option<name>)`, one digit per contact is not enforced (a contact may hold two digits), digit 1 is rejected.
- The stored `name` in `SpeedDialEntry` can go stale; the Settings editor and Contact Sheet show the live name by `lookup_key` and fall back to the stored name when the contact is gone. A deleted contact (FR-61) leaves its speed-dial entry in place (the number still dials; it reads as the stored name), unlike `favorites_order` which is pruned.

### M16 (C). Timed (non-spring) animations and the idle rule (AD-21, P-22, S28, S33, S36)

- S28's hairline, S33's 450 ms ring and S36's 1 s fill are time-based, not springs; EXPERIENCE calls S28's hairline "the only timed animation". AD-21 measure (1) is "stateful springs request frames only while moving". The kit has no stated timed primitive, and P-22 requires zero frames at idle and timers only while visible or held.
- Add to AD-21 measures: a `rovsti_ui::timeline::Linear{start, duration}` primitive requests frames only while `now < start + duration`, and is dropped when the Notice pill leaves or the finger lifts. It is the only timed primitive; a spring is used wherever a shape can overshoot. S33's ring and S36's fill are `Linear` driven by finger-down time, started and cancelled in the touch handler.

### M17 (C). Contact ringtone: title and current value (AD-31, UX Contact Sheet)

- UX row reads "Ringtone: Default"/the chosen tone's name. AD-31 reads `CUSTOM_RINGTONE` as a URI on demand. Add: `ContactsWrite.ringtone(contact_id)` returns `{uri?, title?}` (title from `RingtoneManager.getRingtone(ctx, uri).getTitle(ctx)`, off the UI thread); `null` or an unparseable URI shows "Default". The row is dimmed with "Allow contacts editing" when the `WRITE_CONTACTS` group is not granted (AD-34).

---

## Items where the spine landed correctly (checked, no action)

- SIM chip colour: `color` is in `phone_accounts` and chips resolve through `account_key`, so no wire field is missing except the encoding detail in M10.
- Held card name and avatar: carried by the call's `display_name`/`number_raw` (the hold timer is C7).
- Frequent window: 90 days in AD-32 matches UX-Q15 (counting rule is M8).
- Emergency: `props.emergency`, `is_emergency`, no chooser or gate, Add/Merge/Block disabled (AD-24, AD-27, AD-33) match FR-68 and P-21.
- Reply-via-SMS sender (UX-Q13), voicemail badge (UX-Q12), ringtone write (UX-Q14) are answered with spikes and fallbacks; the UX "Allow sending texts" row is correctly dropped by AD-28.
- Pending looks: the spine's `pending: answer|swap|merge` plus Rust-side 1 s/2 s limits match State Patterns.
- Lock screen (AD-3 amendment) covers Settings, Details, Contact Sheet and Add call; the reply sheet and Waiting card stay.
- PRD Q6, Q7, Q8, Q9: Q6 and Q9 take their stated defaults, Q7 is AD-23/AD-24, Q8 is S3..S13.
- Back handling is already built as the spine says (`handleSystemBack` then `onBackUnhandled`, no-op while ringing), consistent with the EXPERIENCE Back order.

## Smaller drift worth one line each

1. EXPERIENCE S18 says the Audio dot lasts 3 s, State text says it shows "when a device appeared since the list was last opened". M1 fixes the data rule; the UX should drop "3 s".
2. EXPERIENCE Add call: "the sheet dismisses on `sheet_dismiss` while S19 runs" and "if placing fails the sheet's pill reads 'Call failed'". The sheet is gone by then; with C1 the failure is a Notice pill over the In-Call screen. UX text to fix, no spine impact beyond C1.
3. `story` anchors in the spine (14.1, 15.1, 16.1..16.6, 17.1, 18.1, 18.4, 19.2, 20.3, 20.4, 21.2) match the gap analysis story list, except: gap analysis has no 18.4 for the flip sensor beyond "Silence ringer button and flip-to-silence" (it is 18.4, fine) and no story for T9 performance (20.1) or for the a11y spike (M11 proposes S14 at 15.4).
4. FR-62 (in-app editor, P3) is correctly deferred; no AD exists for it, which is what AD-31 and the Deferred list say.
5. AD-14's list of `rovsti_core` modules omits the Details helpers (per-date grouping of a number's history, totals, `first/last call`). Add `call_log::{details_group, totals}` (pure, tested with the C6/M7 rules) to the amended AD-14 list so Details is not computed in `details.rs`.
