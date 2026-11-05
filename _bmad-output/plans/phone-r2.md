# Plan: Rovsti Phone, round 2 (cold-start WhatsApp calls)

- Base: `bdb3c7884c10499e6aa85bf7000bdfcd0a04cc0f` ("Rovsti Phone: review fixes")
- Unit: Rovsti Phone. Architect plan, no source changed.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`

## State at the base

Round 1 (`_bmad-output/plans/phone.md`) landed all 8 steps (commits `3fca1aa`..`bdb3c78`),
no stash. Checks are green at the base. Present and wired:

- Speed dial + long-press 1 voicemail (`rovsti_core::speed_dial`, `speed_dial_ui.rs`,
  `Backend::call_voicemail`), T9 suggestions on the keypad (`keypad.rs`, `visible_suggestions`).
- WhatsApp calls on the glass Incoming and In-Call screens (`CallSource::WhatsApp`,
  `rovsti_wa_proto::call::{WaScreen, arbitrate}`), Speaker (`wa::call_audio_speaker`),
  placing from Recents, Contact sheet (`sheet-cw-*`) and the keypad chip.
- FCM: `WaIncoming` CallStyle notification from `WaPush.onMessage(kind=call)`, the
  `wa_call_action` event, `AnswerOnce`, optional Firebase source set in the Phone host.
- WhatsApp calls in Recents (`WaCallHistory` in `wa-calls.json`, `rovsti_core::recents` extras).
- Shared settings (`rovsti_wa_ui::WhatsAppSettings`, link / connect with key) in Phone,
  Messages, Contacts and Stops.

## Gaps found against the goal ("incoming WhatsApp call woken by FCM")

1. **Answer from a cold notification does nothing.** A cold process starts the event
   stream with `rovsti_wa_client::subscribe(url, key, None)` (`rovsti_wa_ui/src/view.rs`
   `subscribe`), and the hub sends no replay for `after = None`
   (`services/wa_gateway/src/hub.rs` `subscribe`). The `CallUpdate { phase: Ringing }` was
   published before the app connected, so `Tracker` never shows the call and
   `AnswerOnce::due` (which waits for the call to be on screen ringing) drops the Answer
   after its TTL. Nothing lets the app learn about calls that already exist.
2. **The notification keeps ringing after the caller hangs up.** The gateway pushes only
   `kind=call` (`push/mod.rs` `PushKind`); when the caller cancels, the call is missed or
   ends elsewhere, a phone whose app is not running keeps the full-screen notification up to
   `RING_TIMEOUT_MS`, and Answer then leads nowhere. No missed-call notification either.
3. **Missed WhatsApp calls while the app was closed never reach Recents**, for the same
   reason as 1 (history is only written from live `CallUpdate`s).

Fix: a call snapshot endpoint (`GET /v1/calls`) the app reads when it connects, wakes or gets
a notification action, plus a `call_end` push. Ended calls are kept longer on the gateway (in
memory, bounded) so a missed call can be picked up later; the gateway still keeps no durable
call log (on-device history stays the source for Recents).

Not in scope (device or owner decision): on-device acceptance, video/group calls, a Telecom
`ConnectionService` for WhatsApp calls, first APK, CI.

## Steps

### Step 1. Pure: call snapshot and `call_end` wake (`rovsti_wa_proto`)

Files: `crates/rovsti_wa_proto/src/event.rs` (or `api.rs`), `push.rs`, `history.rs`, `lib.rs`.

- `api.rs`/`event.rs`:
  ```rust
  #[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
  pub struct CallSummary {
      #[serde(flatten)] pub update: CallUpdate,
      pub started_ms: i64,            // wall clock when the gateway first saw the call
      #[serde(default)] pub ended_ms: Option<i64>,
  }
  ```
  `routes::CALLS` is reused for `GET` (list); no new route constant.
- `push.rs`: `Wake::CallEnd { call_id: String, missed: bool }` from
  `{"kind":"call_end","call_id":..,"missed":"1"|"0"}` (FCM values are strings; anything but
  `"1"` is `false`). Update every `match` on `Wake` (grep `Wake::` in `crates/` and `apps/`).
- `history.rs`: `WaCallHistory::record_summary(&mut self, s: &CallSummary, peer_label: &str) -> bool`
  records an **ended** summary once per `call_id` (same dedupe as `record_end`), with
  `started_ms` from the summary, `duration_s` from the Active span if known else 0, `missed`
  = incoming and the end reason is not an answered end (reuse the rule `record_end` uses; put
  it in one private fn both call). Live summaries return `false`.
- Tests: `CallSummary` JSON round-trip (flatten keeps the `CallUpdate` fields at top level),
  `parse_wake` for `call_end` with missed 1/0/absent and a missing `call_id` -> `None`;
  `record_summary` live -> false, ended missed incoming -> missed record, duplicate -> false,
  a call already recorded by `record_end` is not recorded twice.

Acceptance: `cargo test -p rovsti_wa_proto` passes; proto and client still check for Android.

### Step 2. Gateway: `GET /v1/calls`, longer ended retention, `call_end` push (+ client)

Files: `services/wa_gateway/src/wa/calls.rs`, `services/wa_gateway/src/push/mod.rs`,
`push/fcm.rs` (only if it matches on `PushKind`), `crates/rovsti_wa_client/src/http.rs`.

- `CallEntry` gets `started_ms: i64` (`crate::now_ms()` in `CallEntry::new`) and
  `ended_ms: Option<i64>` (set in `apply` where `ended_at` is set). `Calls::list(&self) ->
  Vec<CallSummary>` returns live calls and ended ones still kept, newest first.
- Retention: `KEEP_ENDED` 300 s -> 24 h. `insert` must never refuse a new call because the
  table is full of ended ones: after the `retain`, while `calls.len() >= MAX_CALLS`, remove
  the ended entry with the oldest `ended_at`; refuse only when all `MAX_CALLS` are live.
- Route: `.route(r::CALLS, post(place).get(list))` with
  `async fn list(State, auth: Authed) -> ApiResult<Json<Vec<CallSummary>>>`.
- Push: `PushKind::CallEnd { call_id, missed: bool, from, name }`, data
  `kind=call_end, call_id, missed ("1"/"0"), from, name`, `high_priority() == true` (it must
  stop a ringing phone). Sent from `apply` when an **incoming** entry that was pushed
  (`entry.incoming.is_some()` at insert time; keep a `pushed: bool`) moves from `Ringing` to an
  ended phase. `missed` = the end came from `RemoteTerminate` or `Timeout` while ringing;
  a reject by an app or an answer on another device (`on_ended_elsewhere`) is `missed=false`.
  Never sent for outgoing calls or for calls that were answered here.
- Client: `Gateway::calls(&self) -> Result<Vec<CallSummary>, ClientError>` = `self.get(routes::CALLS)`.
- Tests (gateway, with the existing fake driver / test rig near `calls.rs:1179` and its
  recording pusher used at `calls.rs:1218`): list shows a ringing call then its ended state with
  `ended_ms`; a full table of ended calls still accepts a new call (oldest ended evicted); a full
  table of live calls refuses; caller-cancel while ringing pushes `call_end missed=1`; ring
  timeout pushes `missed=1`; app reject pushes `missed=0`; answered then ended pushes nothing;
  outgoing pushes nothing; `PushKind::CallEnd::data()` keys; the HTTP route answers 401 without
  a key and the list with one (same harness as the other route tests in `routes.rs`).
  Client: a path/JSON decode test like the existing ones in `http.rs`.

Acceptance: checks green; `cargo test -p wa_gateway` covers the cases above;
`cargo check -p rovsti_wa_client --target aarch64-linux-android` green.

### Step 3. Android: `call_end` cancels the ringing notification and posts a missed call

Files: `crates/rovsti_android/kotlin/dev/rovsti/bridge/WaPush.kt`, `WaIncoming.kt`,
`apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/WaIncomingTest.kt`.

- `WaPush.onMessage`: `kind=call_end` -> `WaIncoming.cancel(app, callId)`; when `missed=="1"`
  also `WaIncoming.showMissed(app, notice)`. Then emit `wa_push` as today (Rust re-syncs).
- `WaIncoming`: pure `WaMissedNotice.of(data: Map<String,String>): WaMissedNotice?`
  (`kind=call_end`, `missed=="1"`, non-empty `call_id`; title by the same name / number /
  "WhatsApp call" rule as `WaIncomingNotice`, factor that rule into one function);
  `showMissed` posts a normal-importance notification on channel `wa_missed`
  ("Missed WhatsApp call", auto-cancel, content intent = launch activity), id derived from the
  call id but distinct from the ringing one.
- JVM tests: `WaMissedNotice.of` for missed 1/0, missing id, title fallbacks.
- No Rust API change is needed (`rovsti_android::wa::cancel_incoming` already exists). Rust
  side only: `rovsti_wa_ui::WhatsAppSettings::wake` already reconnects on any `wa_push`.

Acceptance: Rust checks green (no Rust change beyond Step 1's `Wake` match updates); Kotlin
consistent with the existing bridge style (not built here).

### Step 4. Phone: sync calls from the snapshot (cold Answer, missed calls into Recents)

Files: `apps/phone/src/wa_call.rs`, `apps/phone/src/main.rs` (wake / action plumbing near
lines 320-340), `crates/rovsti_wa_ui/src/view.rs` only if a "connected" notification hook is
missing.

- Pure, in `wa_call.rs`:
  ```rust
  pub struct SnapshotPlan { pub show: Option<CallUpdate>, pub ended: Vec<CallSummary> }
  /// Picks what to feed the tracker and the history from GET /v1/calls.
  /// show: the newest live call when the tracker is empty (ringing incoming first,
  /// then any live), None when the tracker already holds a call;
  /// ended: ended summaries not yet in `history` (by call_id).
  pub fn plan_snapshot(tracker_busy: bool, summaries: &[CallSummary], history: &WaCallHistory) -> SnapshotPlan;
  ```
- `WaCallUi::sync_calls(&mut self, cx)`: one in-flight fetch at a time (drop re-entrant calls,
  set a `resync_again` flag), `gateway.calls()` on the background executor, then
  `on_call(plan.show)` (reuses `Tracker::on_update`, `start_ticking`, `run_due_actions`), and
  for each `plan.ended`: `history.record_summary`, `cancel_notice(call_id)`,
  `once.call_ended(call_id)`; persist history once (existing writer) and bump `history_rev`.
- Call `sync_calls` when: the WhatsApp link becomes connected (the place `WaCallUi` learns the
  link today in `new`/its settings observer), every new `wa_push` wake forwarded in `main.rs`,
  every `wa_call_action` before `answer_id` / `decline_id`, and a `GatewayEvent::Resync`.
- Decline from a cold notification already works without a view (`AnswerOnce` decline needs
  no call on screen); keep it, and make sure the snapshot does not re-show a call that was just
  declined (`AnswerOnce` already remembers handled ids: check `plan.show` against it).
- Tests: `plan_snapshot` cases (empty, ringing incoming chosen over an older live outgoing,
  busy tracker shows nothing, ended already in history skipped, ended missed listed); an
  integration-style test of `AnswerOnce` + `Tracker` fed by a snapshot update: an Answer queued
  before the snapshot becomes due once the snapshot shows the call ringing.

Acceptance: checks green; new pure tests pass; no change to the Telecom paths (`incall`/`zone`
tests unchanged).

### Step 5. Docs

Files: `docs/architecture.md` (AD-WA5/AD-WA6: snapshot `GET /v1/calls`, 24 h in-memory ended
retention with eviction, `call_end` push), `docs/android.md` (`call_end`, `wa_missed` channel),
`docs/whatsapp-gateway.md` (API list: `GET /v1/calls`), `docs/phone-acceptance.md` (rows:
Answer from the locked-phone notification with the app killed; caller hangs up while the
killed phone rings -> notification goes and a missed-call notification appears; that call is in
Recents after opening Phone), `docs/roadmap.md` (Phone: "Cold start: Answer from the
notification, call_end push, missed WhatsApp calls in Recents (done without a device)").

Acceptance: checks green; docs match the code.
