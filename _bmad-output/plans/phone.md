# Plan: Rovsti Phone, finish the buildable roadmap items and WhatsApp calling

- Base: `60259d6a5bdb65ec627a144d8a540b967f89004f` ("WIP: carry-over before sync")
- Unit: Rovsti Phone. Architect plan, no source changed.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`
- Nothing runs on a device here. Kotlin changes are kept small, pure helpers get a
  JVM test next to the existing ones (`apps/phone/platforms/android/app/src/test/...`),
  but the required checks only cover Rust.

## What the code already has (source of truth, not the roadmap)

The Phone checklist in `docs/roadmap.md` is stale: most items are built.

| Roadmap item | Code today |
|---|---|
| InCallService + glass in-call (mute, speaker, keypad, hold, swap, merge) | Built: `RovstiInCallService.kt`, `apps/phone/src/incall.rs`, `zone.rs` (S19 hold, S20 swap, S21 merge), `rovsti_core::scene` |
| Full-screen incoming incl. lock screen | Built: `IncomingCall.kt`, `CallRegistry.kt`, `apps/phone/src/incoming.rs` |
| Call log grouped, missed badges | Built: `rovsti_core::{call_log,recents}`, `apps/phone/src/recents.rs` |
| Contacts, search, favourites | Built: `contacts.rs`, `rovsti_core::search`. **T9 on the keypad is not wired**: `rovsti_core::t9` (`ContactIndex::t9`, `T9Scratch`) exists and is tested, but `keypad.rs` only does `match_number` (story 10.1) |
| Speed dial, long-press 1 voicemail | **Missing in the UI**: `rovsti_core::settings::{SpeedDialEntry, SPEED_DIAL_DIGITS}` and `PrefsV2::speed_dial` exist (and backup carries them), but no keypad hold, no settings page, no voicemail call |
| Dual-SIM picker | Built: `sim_ui.rs`, `rovsti_core::sim`, `telecom::place_call_on` |
| Blocklist | Built: `block_ui.rs`, `blocked_ui.rs`, `rovsti_core::blocklist`, `Blocklist.kt` |
| Audio routing | Built: `route.rs`, `rovsti_core::audio` |

WhatsApp (gateway unit, `wa-gateway*.md`, all landed):

- Gateway (`services/wa_gateway/src/wa/calls.rs`) does offer/accept/reject/terminate and
  bridges call audio; client has `Gateway::{place_call, accept_call, reject_call,
  end_call, set_muted}` and `rovsti_wa_client::call_audio`. No new whatsapp-rust API is
  needed for this unit; the gateway side is complete.
- Shared settings (`rovsti_wa_ui::WhatsAppSettings`, two options: link / connect with key,
  key list and revoke) is in all four apps; Phone shows it under Settings, `Sub::WhatsApp`.
- Phone: `apps/phone/src/wa_call.rs` (`Tracker`, `WaCallUi`) shows WhatsApp calls as a
  **separate glass card**, not on the glass In-Call screen. Outgoing WhatsApp call exists
  only in the Recents row menu (`block_ui.rs`, `"rm-wa"`). **Not** on the Contact sheet,
  not on the keypad.
- Audio (`WaCallAudio.kt`): `MODE_IN_COMMUNICATION`, no speaker toggle.
- FCM: `WaPush.onMessage` only emits `wa_push`; the Phone host has **no**
  `FirebaseMessagingService` and nothing posts an incoming WhatsApp call notification, so a
  cold phone is woken but shows nothing until the app is opened.
  `rovsti_wa_proto::push::parse_wake` drops the `from` / `name` fields the gateway sends
  (`services/wa_gateway/src/push/mod.rs`).
- A WhatsApp call never appears in Recents.

Gaps this unit closes, in order: speed dial + voicemail, T9 on the keypad, WhatsApp
calls on the glass In-Call and Incoming screens, WhatsApp speaker, the FCM incoming
notification, WhatsApp call from the Contact sheet and keypad, WhatsApp calls in Recents,
and the roadmap/docs. Shared settings need no new work (checked: two options, key
copy/paste, revoke, all four apps).

Not in scope (needs a device or an owner decision): device acceptance runs, video and
group calls, a Telecom `ConnectionService` for WhatsApp calls (self-managed), first APK
via GPUiForge, CI.

## Steps

### Step 1. Pure: speed dial and voicemail resolution (`rovsti_core`)

Files: `crates/rovsti_core/src/speed_dial.rs` (new), `crates/rovsti_core/src/lib.rs`.

```rust
pub enum HoldDigit { Voicemail, Call { number_raw: String, name: String }, Unassigned(u8), Ignore }
/// Long-press on a digit key. Only acts when the field held nothing before the press
/// (the pressed digit itself may already be in `dialed`: strip it).
pub fn on_hold(digit: char, dialed_before: &str, map: &BTreeMap<u8, SpeedDialEntry>) -> HoldDigit;
pub fn assign(map: &mut BTreeMap<u8, SpeedDialEntry>, digit: u8, entry: SpeedDialEntry) -> Result<(), SpeedDialError>;
pub fn unassign(map: &mut BTreeMap<u8, SpeedDialEntry>, digit: u8) -> bool;
pub fn free_digits(map: &BTreeMap<u8, SpeedDialEntry>) -> impl Iterator<Item = u8>;
#[derive(thiserror::Error)] pub enum SpeedDialError { Digit(u8), EmptyNumber, TooLong }
```
Reuse `SPEED_DIAL_DIGITS` and the 200-char limit from `settings.rs` (make `MAX_FIELD_CHARS`
`pub(crate)`). Tests: `1` -> Voicemail; `0` -> Ignore (zero keeps `+`, `dial::long_press_zero`);
assigned 2..9 -> Call; unassigned -> Unassigned; non-empty `dialed_before` -> Ignore;
assign out of range / empty number refused; long name refused; unassign twice false.

Acceptance: `cargo test -p rovsti_core speed_dial` passes, all checks green.

### Step 2. Pure: WhatsApp call on the call screens, wake details, local call history (`rovsti_wa_proto`)

Files: `crates/rovsti_wa_proto/src/call.rs` (extend), `push.rs`, `history.rs` (new), `lib.rs`.

- `push.rs`: `Wake::Call { call_id: String, from: Option<String>, name: Option<String> }`
  (serde `#[serde(default)]`), so the notification can name the caller. Update the existing
  tests and every match on `Wake::Call` (grep `Wake::Call` in `crates/rovsti_wa_ui` and apps).
- `call.rs`: what the glass screens need, so Phone's GPUI code stays a mapping:
```rust
pub enum WaControl { Mute, Speaker, Keypad, Hold, AddCall, End }
pub enum WaScreenPhase { Ringing, Calling, Connecting, Active, Ended(EndReason) }
pub struct WaScreen { pub call_id: String, pub title: String, pub phase: WaScreenPhase,
                      pub incoming: bool, pub muted: bool, pub speaker: bool, pub elapsed_s: u64 }
impl WaScreen { pub fn from_view(view: &WaCallView, speaker: bool) -> Self;
                pub fn enabled(&self, c: WaControl) -> bool; /* Keypad, Hold, AddCall always false */ }
/// Who owns the screen when a cellular call and a WhatsApp call meet.
pub enum Arbitration { ShowWa, WaWaiting, RejectWaBusy, Telecom }
pub fn arbitrate(telecom_live: bool, telecom_ringing: bool, wa: Option<&WaCallView>) -> Arbitration;
```
  Rule: a ringing cellular call or a live cellular call keeps the screen; a WhatsApp offer
  during a live cellular call is `WaWaiting` (the waiting card, Decline only); during a ringing
  cellular call it is `RejectWaBusy`. A WhatsApp call already active keeps the screen and a new
  cellular call shows as the existing waiting card (Telecom handles it).
- `history.rs`: local WhatsApp call history for Recents (on-device only, the gateway keeps none):
```rust
pub struct WaCallRecord { pub call_id: String, pub peer: String, pub peer_label: String,
    pub direction: CallDirection, pub started_ms: i64, pub duration_s: u64, pub missed: bool }
pub struct WaCallHistory { records: VecDeque<WaCallRecord> } // newest first, cap 200
impl WaCallHistory { pub fn record_end(&mut self, view: &WaCallView, started_ms: i64, ended: &EndReason);
    pub fn to_json(&self) -> String; pub fn from_json(s: &str) -> Self /* corrupt -> empty */;
    pub fn iter(&self) -> impl Iterator<Item=&WaCallRecord>; }
```
  A record is written once per `call_id` (duplicate ends ignored). Missed = incoming and never
  Active.

Tests: wake with and without from/name; `enabled()` per phase; every `arbitrate` row; history
dedupe, cap, missed rule, JSON round-trip, garbage JSON.

Acceptance: `cargo test -p rovsti_wa_proto` passes; `rovsti_wa_proto` and `rovsti_wa_client`
still check for `aarch64-linux-android`.

### Step 3. Android facades: voicemail, WhatsApp speaker, WhatsApp incoming notification (`rovsti_android` + Kotlin bridge)

Rust (`crates/rovsti_android/src`), each returning `Error::Unsupported` on desktop, with a
`desktop_is_unsupported` test like `wa.rs` has:

- `telecom.rs`: `pub fn call_voicemail() -> Result<bool>` -> `bridge::call_bool0("callVoicemail")`.
- `wa.rs`: `pub fn call_audio_speaker(on: bool) -> Result<bool>` ->
  `bridge::call_bool("waSpeaker", &[Arg::Bool(on)])` (`bridge::Arg::Bool` exists);
  `pub fn show_incoming(call_id: &str, title: &str) -> Result<bool>` and
  `pub fn cancel_incoming(call_id: &str) -> Result<bool>`.
- `events.rs`: `kind::WA_CALL_ACTION = "wa_call_action"` with payload
  `{"call_id":..,"action":"answer"|"decline"}`; add a pure decoder
  `pub fn parse_wa_call_action(json: &str) -> Option<(String, WaCallAction)>` + tests.

Kotlin (`crates/rovsti_android/kotlin/dev/rovsti/bridge/`):

- `RovstiBridge.kt`: `callVoicemail()` = `TelecomManager.placeCall(Uri.fromParts("voicemail", "", null), Bundle())`
  behind the same `CALL_PHONE` check as `place()`; `waSpeaker(on)`, `waShowIncoming`, `waCancelIncoming`.
- `WaCallAudio.kt`: `setSpeaker(context, on)`: API 31+ `AudioManager.setCommunicationDevice`
  with the `TYPE_BUILTIN_SPEAKER` / `TYPE_BUILTIN_EARPIECE` device from
  `availableCommunicationDevices`, else `isSpeakerphoneOn`. Pure `pickDevice(types: List<Int>, speaker: Boolean): Int?`.
- `WaIncoming.kt` (new): pure `WaIncomingNotice.of(data: Map<String,String>): WaIncomingNotice?`
  (title = name, else number from `from`'s user part, else "WhatsApp call"), and `show/cancel`
  posting a `Notification.CallStyle.forIncomingCall` on a high-importance channel
  `wa_calls` with a full-screen intent to the package's launch activity. Answer and Decline
  are activity `PendingIntent`s carrying `wa_call_id` + `wa_call_action`.
- `WaPush.onMessage`: for `kind=call` call `WaIncoming.show(app, data)` **before** emitting
  `wa_push`, so a cold process still rings. For `kind=call_end` (if the gateway sends it, else
  skip) cancel.
- `RovstiBridge`: a helper `deliverWaCallAction(intent)` that pulls the two extras and queues
  `wa_call_action` through the existing `mailbox` (so it survives until Rust is live).
- JVM test `WaIncomingTest.kt` in `apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/`.

Acceptance: Rust checks green incl. the Android target; desktop tests prove `Unsupported`.

### Step 4. Phone: speed dial and long-press 1 for voicemail

Files: `apps/phone/src/keypad.rs`, `apps/phone/src/settings_ui.rs`, new `apps/phone/src/speed_dial_ui.rs`, `main.rs` (mod).

- `HoldAction::Digit(u8)`: armed in the digit key's finger-down like `HoldAction::Plus`
  (reuse `arm_hold`, 500 ms). `hold_fired` calls `rovsti_core::speed_dial::on_hold` and:
  Voicemail -> `rovsti_android::telecom::call_voicemail()` through a new
  `Backend::call_voicemail(cx) -> Placement` (handles `Unsupported` like `place_call` does);
  Call -> `place_call_from(number_raw)`; Unassigned -> notice pill "No speed dial on N" with
  action "Set" opening the page; the digit that went into `dialed` on finger-down is removed
  (`dial::backspace`). Haptic `HapticFeedback::LongPress`.
- Settings: `RowId::SpeedDial` + `Sub::SpeedDial` ("set-sd-" prefix, add to the prefix
  uniqueness test at `settings_ui.rs` ~1867). Page lists 1 (Voicemail, fixed) and 2..9; tapping
  a free digit opens the existing contact picker pattern used by Favorites/contact sheet (reuse
  `open_contact_sheet`'s number list; no new GPUI API), tapping an assigned digit offers
  Change / Remove. Writes go through `Backend::update_prefs(cx, |p| speed_dial::assign(...))`.
- Unit tests in `speed_dial_ui.rs` for the pure row-building function
  (`fn rows(map) -> Vec<Row>`); GPUI stays thin.

Acceptance: checks green; `Sub::SpeedDial.prefix()` test; the keypad hold path compiles on Android.

### Step 5. Phone: T9 suggestions on the keypad

Files: `apps/phone/src/keypad.rs` (`sync_dial_match`), maybe `apps/phone/src/recents.rs` for the row widget.

- Keep `DialMatch` for the exact-number line (story 10.1). Add `T9Match { query, index, hits: Vec<Suggestion>, scratch: T9Scratch, task }`
  run on the background executor exactly like `sync_dial_match` (index is the same
  `Arc<ContactIndex>` from `Backend::contacts()`; move the scratch into the task and back).
- Show up to 3 suggestions above the field as glass rows (name with the matched span bold,
  number); tap calls (`place_call_from`), "N more" opens Contacts with the search prefilled.
  Name format from `prefs.settings.name_format` via `T9Scratch::set_format`.
- Hidden while in-call keypad, while the field has `+*#`, or with fewer than 1 digit.
- Pure helper `fn visible_suggestions(hits: &[T9Hit], exact: Option<i64>) -> (Vec<T9Hit>, usize)`
  (drops the contact already shown as the exact match, caps 3, returns the rest count) with tests.

Acceptance: checks green; helper tests pass.

### Step 6. Phone: WhatsApp calls on the glass Incoming and In-Call screens

Files: `apps/phone/src/wa_call.rs`, `incall.rs`, `incoming.rs`, `zone.rs`, `main.rs`, `rovsti_core/src/scene.rs`.

- `wa_call.rs`: `WaCallUi` keeps the gateway/audio work (`answer`, `place`, `hang_up`,
  `toggle_mute`, pump) but stops rendering its own card: `render` returns an empty `div()`
  unless the In-Call screen is not mounted (desktop fallback only). Add
  `pub fn screen(&self) -> Option<WaScreen>` (Step 2) and `pub fn set_speaker(&mut self, on, cx)`
  calling `rovsti_android::wa::call_audio_speaker`. Make `answer`, `hang_up`, `toggle_mute`
  `pub(crate)`.
- `incall.rs`: add `pub(crate) enum CallSource { Telecom(i32), WhatsApp(String) }` to
  `CallView` (replace bare `id` reads with `source`; Telecom keeps `i32`). `CallView::from_wa(&WaScreen)`
  maps title/phase/muted/elapsed (`connect_time_ms = now - elapsed`), `route = Speaker|Earpiece`,
  `can_hold = false`. In `sync_calls`, take `rovsti_wa_proto::call::arbitrate(...)`: `ShowWa` puts
  the WhatsApp view in front; `WaWaiting` fills `waiting` (Decline only; add
  `DisabledReason::WhatsAppCall` with text "Not available on WhatsApp calls" to
  `rovsti_core::scene` and its `text()` test).
- `incall_tap`: for `CallSource::WhatsApp`, Mute -> `wa_call.toggle_mute`, Audio/Speaker ->
  `wa_call.set_speaker`, End -> `wa_call.hang_up(false)`; Keypad, Hold, Add, Merge, Swap are dimmed
  with `DisabledReason::WhatsAppCall` (the existing `HoldAction::Reason` explains on hold).
- `incoming.rs`: a ringing WhatsApp call uses the same Incoming screen with a small "WhatsApp"
  caption under the name; Answer/Decline call `wa_call.answer` / `wa_call.hang_up(true)`.
  The aura/fx are reused unchanged.
- `RejectWaBusy` from `arbitrate` -> `gateway.reject_call` once per call id.
- Tests: `CallView::from_wa` mapping per phase, the source-aware control routing as a pure
  `fn wa_control(slot: Slot) -> Option<WaControl>` in `zone.rs`, `Tracker` tests stay green.

Acceptance: checks green; no telecom path changed (existing `incall`/`zone` tests unchanged and passing).

### Step 7. Phone: place from Contact sheet and keypad, FCM wake to Incoming, WhatsApp calls in Recents

Files: `contacts.rs`, `keypad.rs`, `block_ui.rs`, `recents.rs`, `main.rs`, `backend.rs`, phone Android host.

- Contact sheet: per number a second circle "WhatsApp" (`sheet-cw-{i}`, `Icon::LucidePhone` with
  the WhatsApp caption) shown only when `whatsapp.read(cx).is_connected()`; handled in
  `contact_sheet_act` -> `wa_call.place(&raw, cx)`.
- Keypad: long-press on Call (when no SIM chooser is due) or a "WhatsApp" chip under the field
  while connected and the number is valid -> `wa_call.place`. Pick one: the chip (a long-press on
  Call already means the SIM chooser, `HoldAction::SimChooser`).
- Events: drain `kind::WA_CALL_ACTION` in the app's event loop (where `wa_push` is handled in
  `backend.rs`, ~line 268/882): answer -> `wa_call.answer_id(call_id)`, decline ->
  `wa_call.decline_id(call_id)`; both idempotent through a pure `AnswerOnce`-style guard in
  `wa_call.rs` (tested). A `Wake::Call` also calls `WhatsAppSettings::wake` (already) and, when the
  call ends or is answered in-app, `rovsti_android::wa::cancel_incoming`.
- `MainActivity.kt` (phone host): in `onCreate`/`onNewIntent` pass the intent to
  `RovstiBridge.deliverWaCallAction(intent)`; Decline moves the task back (`moveTaskToBack(true)`)
  so it does not open the UI, matching telephony Decline (acceptance row 19).
- Optional Firebase in the phone host: `app/src/firebase/kotlin/dev/rovsti/phone/RovstiMessagingService.kt`
  (the snippet in `docs/android.md`, now calling only `WaPush.onMessage`) and in
  `app/build.gradle.kts`: only when `google-services.json` exists, apply
  `com.google.gms.google-services`, add `firebase-messaging`, add the source dir and merge a
  manifest entry for `com.google.firebase.MESSAGING_EVENT`. Without the file the build is unchanged.
- Recents: persist `WaCallHistory` (Step 2) to `wa-calls.json` in `rovsti_ui::storage::data_dir()`
  through a small writer in `wa_call.rs` on `Change::Ended`; Recents merges these rows into the
  grouped list with a WhatsApp badge (pure merge in `rovsti_core::recents` takes a generic
  `ExtraCall { number_raw, started_ms, duration_s, missed, label }` so rovsti_core does not depend
  on rovsti_wa_proto; tests for ordering, grouping and the missed badge count). The row menu
  "WhatsApp call" stays.

Acceptance: checks green; new pure tests pass; the Android host files are syntactically
consistent with the existing ones (not built here).

### Step 8. Docs and roadmap

Files: `docs/roadmap.md`, `docs/architecture.md` (AD-WA5 now uses the In-Call screen; arbitration
rule), `docs/android.md` (WaIncoming, optional Firebase flavour, `wa_call_action`),
`docs/phone-acceptance.md` (new section "WhatsApp calls": place from Recents / Contact sheet /
keypad chip; incoming with app open, with screen locked after FCM; Mute, Speaker, End; busy
rules; Recents row), and `CLAUDE.md`'s line "Do not add a backend" reworded to "Everything is
local except WhatsApp, which runs on the user's gateway (`services/wa_gateway`)" as
`docs/whatsapp-notes.md` already records.

Roadmap: tick the Phone items the code already ships (table above), tick speed dial and T9
with this unit, add "WhatsApp calls on the In-Call screen, FCM incoming notification, Recents"
as done-without-device, keep "Try it on a phone" open.

Acceptance: checks green; docs match the code.
