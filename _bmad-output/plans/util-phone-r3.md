# util-phone: everyday utilities for Rovsti Phone (round 3)

baseSha: d620b2408b0f2be73e6957ff63ec6c57eaf7acff

## Audit (what exists at baseSha)

Round 1 (82abee0..ca837b6) shipped U1-U6, and round 2 (fd3fee0..d620b24) shipped U7-U11 and U17-U19,
as planned in `util-phone.md` and `util-phone-r2.md`. `git stash list` is empty. These are in the tree:
`rovsti_core::{zoom, number_format, call_log_query, call_stats, call_notes}`, `rovsti_android::media`,
`documents::export_calls`, the photo viewer with set/replace/remove (`photo_viewer.rs`,
`photo_edit.rs`), the QR share, the Call details sheet (`details_ui.rs`), the in-call note
(`incall_note.rs`), the stats page (`stats_ui.rs`), Recents search with filter chips, Copy as / Share as,
CSV export with notes, and pause/wait. Earlier work also shipped: block/unblock and the Blocked list,
per-row delete with Undo (`PillAction::RestoreCalls`), per-person "Delete call history" behind a confirm
(`history_ui.rs`), paste-to-dial, T9, speed dial, quick replies, and the in-call controls.

Still missing (checked by grep at baseSha):
- No select mode in Recents. The list is a `uniform_list` of fixed 58 px rows, hit-tested by arithmetic
  (`recents::row_at`, `recents_hit`). Deletion is per row only (`rm-delete`).
- Settings > Data > "Delete all call history" (`history_ui::delete_all_history`) has no Undo. Only the
  pill "Call history deleted" shows.
- Recents search (`call_log_query::matches`) ignores note text.
- No reminders. `rovsti_android::notify` posts message notifications only. The AlarmManager pattern
  lives in `kotlin/dev/rovsti/bridge/ScheduledSms.kt` (store + receiver + `RovstiBridge` entry points).
  The Phone manifest has `POST_NOTIFICATIONS` but neither `SCHEDULE_EXACT_ALARM` nor
  `RECEIVE_BOOT_COMPLETED`, and `scripts/check-apk.sh` guards the permission list. So reminders use
  inexact `setAndAllowWhileIdle` and are re-armed when the app starts, and no permission is added.
- No SMS hand-off with a body. `telecom::send_sms_to(number)` opens an empty composer. The app must
  never hold `SEND_SMS` (check-apk fails on it).
- No per-person CSV export. Only the full-log export in Settings > Backup (`backup_ui.rs`) exists.
- The keypad field has no long-press menu. There is only the "Paste" pill (`keypad.rs` `paste_press`).
- The Speed dial page (`speed_dial_ui.rs`) has no Share list and no Clear all.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| U1 | Photo viewer: full screen, pinch/double-tap zoom, pan, swipe down to close | done R1 |
| U2 | Save photo to gallery, share photo | done R1 |
| U3 | Recents search and filter chips (All/Missed/Incoming/Outgoing/WhatsApp) | done R1 |
| U4 | Copy as / Share as: display, E.164, national, tel: link, vCard | done R1 |
| U5 | Export call log as CSV | done R1 |
| U6 | Keypad pause/wait long-press on `*`/`#` | done R1 |
| U7 | Call statistics (per person, Settings page) | done R2 |
| U8 | Per-call notes | done R2 |
| U9 | Share contact as QR (vCard), Save/Share of the QR image | done R2 |
| U10 | Set/replace/remove the contact photo from the viewer | done R2 |
| U11 | Call details sheet | done R2 |
| U17 | In-call Note button, long-press caller number to copy | done R2 |
| U18 | Share a person's call statistics as text | done R2 |
| U19 | CSV export gains a `note` column | done R2 |
| U21 | Notes search: the Recents search text also matches note text | done R3 (S1, S4) |
| U24 | Delete all call history gains Undo (snapshot, `RestoreCalls`) | done R3 (S4) |
| U12 | Recents select mode: multi-select, Select all, Delete n with a confirm and Undo | done R3 (S1, S5) |
| U20 | Missed-call row menu and Number sheet: "Send a quick reply" (composer opens with the text) | done R3 (S2, S3, S6) |
| U16 | Export one person's call history (CSV) from the Number/Contact sheet | done R3 (S1, S6) |
| U13 | "Call back later" reminder (15 min / 1 h / tonight / tomorrow) with Undo and Cancel | done R3 (S2, S3, S7) |
| U14 | Keypad field long-press menu: Copy / Paste / Clear (with Undo) | done R3 (S2, S8) |
| U22 | Speed dial page: Share the list as text, Clear all with a confirm and Undo | done R3 (S2, S8) |
| U15 | Recents sticky day capsule (Today, Yesterday, weekday, date) over the list | backlog R4 |
| U23 | Haptic and pill feedback audit over every utility | backlog R4 |
| U25 | Recents "Copy number" from a long-press on the row's name (no menu) | backlog R4 |
| U26 | Call details: "Share call details" (text through `share_text`) next to Copy | backlog R4 |
| U27 | Blocked list: Share/Export the blocked numbers as text, Unblock all with a confirm and Undo | backlog R4 |
| U28 | Stats page: Export statistics as CSV (per person totals for the range) | backlog R4 |
| U29 | Keypad: "Add to existing contact" / "Create contact" from the typed number when unknown | backlog R4 (check `rm-add` reuse) |
| U30 | Notes page: list all call notes (search, open the call, delete with Undo) | backlog R4 |

## Steps (round 3)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free, run `rm -rf target/debug/incremental` first (6.8 GB free at baseSha). Make one
focused commit per step (`Rovsti phone: ...`). Read `.claude/skills/liquid-glass-gpui/SKILL.md` before
S4-S8. Never invent GPUI APIs: copy patterns already in `apps/phone/src` (sheets with `SheetKind`,
`touch_surface`, `presses`, `show_notice_pill`/`show_pill_action`, `PillAction`, `SearchField`,
`rovsti_ui::haptics::perform`). Every utility gets a pill, and every destructive one gets a confirm or
an Undo (both for bulk deletes).

### S1. Pure: notes search, selection, per-person export (`rovsti_core`)
- `call_notes.rs`: `impl Notes { pub fn mentions(&self, number: &str, first_ms: i64, last_ms: i64,
  folded_text: &str) -> bool }`. True when a note whose number `PhoneNumber::matches` the row's
  number, with `at_ms` in `[first_ms - MATCH_WINDOW_MS, last_ms + MATCH_WINDOW_MS]`, has
  `search::fold(text)` containing `folded_text`. It is false for empty text or a private/unparsable
  number. Parse the wanted number once.
- `call_log_query.rs`: `pub fn apply_with_notes(rows: &[RecentsRow], query: &Query, notes: &Notes) ->
  Vec<usize>`. A row passes the filter and either `matches` or (for trimmed text of at least 2 chars)
  `notes.mentions(&row.number_raw, row.first_ms, row.last_ms, &fold(text))`. `apply` stays as it is
  (`apply_with_notes(rows, q, &Notes::default())` must equal `apply`, so add a test for that).
- `call_log_query.rs`: selection for U12:
  `#[derive(Clone, Debug, Default, PartialEq, Eq)] pub struct Selection { keys: BTreeSet<EntryRef> }`.
  It is keyed by the row's newest ref (`row.refs` first by `last_ms`; add `fn row_key(row) ->
  Option<EntryRef>`), so a rebuild after a log change keeps the choice. Methods: `toggle(&mut self, row:
  &RecentsRow) -> bool` (now selected), `contains(&self, row) -> bool`, `select_all(&mut self, rows:
  &[RecentsRow], visible: &[usize])`, `clear()`, `len()`, `is_empty()`, `retain_present(&mut self,
  rows: &[RecentsRow])` (drops keys whose row is gone), `refs(&self, rows: &[RecentsRow]) ->
  Vec<EntryRef>` (every ref of every selected row, for delete), `call_count(&self, rows) -> u32` (sum
  of `count`). Add `Ord`/`PartialOrd` derive to `call_log::EntryRef` if it lacks them.
  `pub fn select_title(n: usize) -> String` gives `"Select calls"` for 0, `"1 selected"` and
  `"3 selected"`. `pub fn delete_selected_text(rows: usize, calls: u32) -> (String, String)` gives
  the confirm title/body, e.g. `("Delete 3 rows?", "7 calls will be removed from this phone's call
  log.")`, and `("Delete this row?", "1 call will be ...")` for one.
- `call_log_query.rs`: per-person export for U16: `pub fn entries_for_numbers(entries:
  &[CallLogEntry], numbers: &[&str]) -> Vec<CallLogEntry>` and `pub fn extras_for_numbers(extras:
  &[ExtraCall], numbers: &[&str]) -> Vec<ExtraCall>` (same matching as `call_log::ids_for_numbers` and
  `recents::extra_ids_for_numbers`; reuse those helpers or their matcher, and do not duplicate the
  logic). Also `pub fn person_csv_file_name(who: &str, now_ms: i64, tz_offset_s: i32) -> String`, e.g.
  `rovsti-calls-anna-smith-2026-11-06.csv`. The slug is ASCII-folded, lowercase, `[a-z0-9-]` only,
  dashes collapsed, at most 32 chars, and falls back to `csv_file_name` when empty.
- Tests (in-module): notes hits by text with accents/case; the window edges; another number's note never
  matches; one-digit text ignored; selection survives a rebuild where rows shift; `retain_present` drops
  gone rows; `refs` covers a collapsed row's every ref; `call_count`; the title/confirm texts; per-person
  filters keep national and international forms; the slug cases (`"Ünal Öz"` -> `unal-oz`, emoji-only ->
  fallback name).

### S2. Pure: reminders, quick-reply targets, keypad field menu, speed-dial text (`rovsti_core`)
- New `crates/rovsti_core/src/reminder.rs` (`pub mod reminder;`). The types are serde, so the Kotlin
  store holds the same JSON:
  `#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)] pub struct Reminder { pub id: i64,
  pub number: String, pub name: Option<String>, pub at_ms: i64, pub created_ms: i64 }`.
  `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum Slot { In15Min, In1Hour, Tonight, TomorrowMorning }`
  with `pub const ALL: [Slot; 4]`, `pub fn due_ms(self, now_ms: i64, tz_offset_s: i32) -> i64`
  (Tonight = 20:00 local today, or +1 h when it is already past 19:00; TomorrowMorning = 09:00 local
  the next day), and `pub fn label(self, now_ms, tz) -> String` (`"In 15 minutes"`, `"In 1 hour"`,
  `"Tonight at 20:00"`, `"Tomorrow at 09:00"`). The rest:
  `pub fn new_reminder(number, name, slot, now_ms, tz) -> Option<Reminder>` (None for a private or
  empty number; id = `now_ms`), `pub fn set_text(r: &Reminder, now_ms, tz) -> String` (`"Reminder at
  20:00"`, `"Reminder tomorrow at 09:00"`), `pub fn notification_title(r) -> String` (`"Call back Anna
  Smith"`, or the grouped number), `pub fn notification_body(r) -> String` (`"You missed a call"` when
  set from a missed call, so add a `pub missed: bool` field), and `pub fn parse_list(json: &str) ->
  Vec<Reminder>` (bad JSON gives empty) and `pub fn for_number<'a>(list: &'a [Reminder], number: &str)
  -> Option<&'a Reminder>` (`PhoneNumber::matches`).
- `settings.rs` (or `call_log_query`): `pub fn reply_choices(prefs_replies: &[String]) -> Vec<String>`
  gives the non-empty trimmed quick replies, falling back to `DEFAULT_REPLIES` when none are set (U20).
- `dial.rs`: `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum FieldAction { Copy, Paste, Clear }` and
  `pub fn field_menu(dialed: &str, clipboard_has_text: bool) -> Vec<FieldAction>`. Copy and Clear show
  only when the field is non-empty, and Paste only when the clipboard has text. Also `pub fn
  FieldAction::label(self) -> &'static str`.
- `speed_dial.rs`: `pub fn list_text(map: &BTreeMap<u8, SpeedDialEntry>) -> String` gives
  `"Speed dial\n2  Anna Smith  +44 20 7946 0958\n..."` with digit order and numbers grouped through
  `number_format` display form. It is empty when nothing is assigned. `pub fn clear_all(map: &mut
  BTreeMap<u8, SpeedDialEntry>) -> BTreeMap<u8, SpeedDialEntry>` returns the old map for Undo, and
  keeps digit 1 when it is the voicemail key if `assign` treats 1 that way (check `assign`'s
  `SpeedDialError`).
- Tests: the due times around midnight, 19:30 and a negative tz offset; labels; private numbers
  refused; JSON round-trip and bad JSON; `for_number` with national vs E.164; `reply_choices` fallback;
  `field_menu` matrix; `list_text` order/empty; `clear_all` returns the old map.

### S3. Android glue: SMS composer with body, call-back reminders (`rovsti_android` + Kotlin)
- `crates/rovsti_android/src/telecom.rs`: `pub fn compose_sms(number: &str, body: &str) -> Result<bool>`
  -> `bridge::call_bool("composeSms", &[Arg::Str(number), Arg::Str(body)])`. Kotlin
  `RovstiBridge.composeSms(number, body)` = `start(Intent(ACTION_SENDTO, Uri.fromParts("smsto",
  number, null)).putExtra("sms_body", body))` next to `sendSmsTo` (RovstiBridge.kt:724). There is no
  `SEND_SMS`. Add it to the desktop-unsupported test.
- New `crates/rovsti_android/src/reminders.rs` (`pub mod reminders;` in `lib.rs`, doc header like
  `notify.rs`): `pub fn arm(reminder_json: &str) -> Result<bool>` (`armCallReminder`), `pub fn
  disarm(id: i64) -> Result<bool>` (`disarmCallReminder`, the id as a string as `sms::disarm` does),
  `pub fn armed() -> Result<String>` (`armedCallReminders`, a JSON array), and `pub fn rearm_all() ->
  Result<bool>` (`rearmCallReminders`). Desktop test: all return `Error::Unsupported`.
- New `crates/rovsti_android/kotlin/dev/rovsti/bridge/CallReminders.kt` modelled on
  `ScheduledSms.kt`. `ReminderItem.parse` (pure, null on bad JSON / id<=0 / empty number), a
  SharedPreferences-backed `ReminderStore` (arm/disarm/get/all), and `object CallReminders { arm,
  disarm, all, rearmAll, fire }`. Alarms use `setAndAllowWhileIdle(RTC_WAKEUP, …)` only (no exact-alarm
  permission). `fire` posts a notification on a new channel `"reminders"` ("Call reminders",
  IMPORTANCE_DEFAULT) with the title/body from the JSON (`title`, `body` fields that Rust fills from
  `reminder::notification_title/body`, so Kotlin does not format). The tap intent is `ACTION_DIAL
  tel:<number>` with `setPackage(context.packageName)` (the Phone app answers its own dial intent through
  `DialIntents.kt`). A "Call back" action does the same, and `setAutoCancel(true)`. Then it disarms and
  emits `RovstiBridge.emit("reminder_fired", {id})`. `class CallReminderReceiver : BroadcastReceiver`
  handles only the fire action (no BOOT_COMPLETED). Wire `@JvmStatic` entry points in `RovstiBridge.kt`
  beside `armScheduledSms`. Declare `<receiver android:name="dev.rovsti.bridge.CallReminderReceiver"
  android:exported="false" />` in `apps/phone/platforms/android/app/src/main/AndroidManifest.xml`. Add no
  permission.
- `apps/phone/src/backend.rs`: thin wrappers `pub fn compose_sms(number, body) -> Outcome`, `pub fn
  arm_reminder(r: &Reminder) -> Outcome` (serialises `r` plus `title`/`body`), `pub fn
  disarm_reminder(id) -> Outcome`, and `pub fn armed_reminders() -> Vec<Reminder>` (desktop: empty).
  Call `reminders::rearm_all()` once at start-up where the backend first loads prefs (alarms are lost
  on reboot, and this brings them back at the next launch).
- Docs: `docs/android.md` gets a "Call reminders" paragraph (inexact alarm, no boot receiver, re-armed
  at launch, channel id) and the `composeSms` hand-off.

### S4. UI: notes in Recents search, Undo for Delete all (U21, U24)
- `recents.rs`: where `r.visible` is computed (`set_query` / `rebuild_recents`), call
  `call_log_query::apply_with_notes(&rows, &query, &notes)` with the app's `NotesStore` contents
  (`apps/phone/src/notes.rs`). Recompute when notes change (the store's save path already notifies,
  so hook the same `sync_recents` call). The search field hint becomes "Search names, numbers, notes"
  (find the current placeholder string in `recents_field`). A row matched only by its note shows the
  existing note glyph (if rows have none, show the subtitle `Note: <preview>` through
  `call_notes::preview` for matched-by-note rows only).
- `history_ui.rs::delete_all_history`: before `b.delete_all_calls`, snapshot
  `log.source` entries (`backend::Load::Ready(log)`) and `self.wa_call.read(cx)` WhatsApp records (the
  same types `PillAction::RestoreCalls(Arc<[CallLogEntry]>, Arc<[WaCallRecord]>)` takes; find how
  `delete_call_entries` builds them and reuse that helper). On `Done`, show
  `show_pill_action("Call history deleted", "Undo", PillAction::RestoreCalls(..), cx)`. When the log
  has more than `UNDO_CAP = 5000` entries, keep the old pill and add "This can't be undone." to the
  confirm body. Make it a pure `confirm_text(scope, count)` change with a `count` input for All.
- Tests: `confirm_text(&DeleteScope::All, 6000)` mentions it can't be undone and `(…, 10)` does not.
  `visible` includes a note-only match (a pure helper test, if the wiring has a pure seam).

### S5. UI: Recents select mode (U12)
- State on `RecentsState`: `select: Option<call_log_query::Selection>` (None = normal mode).
- Entry points: a new row-menu item `rm-select` ("Select", `Icon` check-circle from the icons already
  used; grep `Icon::Lucide` for a check icon) in `block_ui.rs::row_menu_tap`, which enters the mode with
  that row selected. In select mode the search field row shows a trailing "Done" glass pill (id
  `rc-sel-done`) and a leading "Select all" pill (`rc-sel-all`; it toggles to "Deselect all").
- In select mode a tap anywhere on a row toggles it (route the tap in `recents_pointer` before
  `recents_call` / `recents_info`). The avatar slot shows a 22 px selection circle (filled accent with
  a check when selected, a hairline ring otherwise), drawn in `recents_row`, with `aria_selected`. Row
  geometry stays 58 px, so `row_at` arithmetic is unchanged. A light `HapticFeedback` on each toggle.
- A floating bottom glass bar (`flat_glass`, the same recipe as the existing sheet pills) shows
  `select_title(n)` and a destructive "Delete" pill (`rc-sel-del`, dimmed when empty). Delete opens the
  confirm sheet: add `DeleteScope::Selected { refs: Arc<[EntryRef]>, rows: usize, calls: u32 }` in
  `history_ui.rs` with `confirm_text` from `delete_selected_text`. Yes calls the existing
  `delete_row_calls(&refs, …)` (the pill says "Deleted n" with Undo) and leaves select mode.
- Back/Escape and switching tabs leave select mode (see `recents_tab_changed`). `sync_recents` calls
  `selection.retain_present(&rows)`.
- Tests: `rm-select` id in the menu id list test; `DeleteScope::Selected` confirm text; pure toggle
  routing helper `fn select_tap(sel, rows, index)` if extracted.

### S6. UI: quick reply on missed calls, per-person CSV (U20, U16)
- Row menu: `rm-reply` ("Send a quick reply") for a callable row whose `call_type == Missed`. The
  Number sheet and Contact sheet number pills get the same action. It opens a new sheet
  `SheetKind::QuickReplyPick { number }` listing `settings::reply_choices(&prefs.quick_replies)` as
  glass rows (reuse the row look of `replies_ui::reply_row`, without the decline semantics), plus
  "Write your own..." (empty body -> `send_sms_to`). Choosing one calls `backend::compose_sms(number,
  text)`. The pill reads "Opened Messages" or, on failure, "Couldn't open Messages" (and on desktop the
  `Unsupported` outcome text the other hand-offs use).
- Number sheet and Contact sheet: an "Export calls" item next to "Delete call history"
  (`sheet-nhist-csv` / `sheet-chist-csv`). It builds the CSV through
  `call_log_query::to_csv_with_notes(&entries_for_numbers(..), &extras_for_numbers(..), tz, &notes)`
  (match the actual signature) and hands it to `backend::export_calls(&person_csv_file_name(who, now,
  tz), &csv)`. Copy the result/pill handling from `backup_ui::backup_export_calls` (the document-picker
  result arrives the same way), with "Exported n calls" or "No calls to export".
- Tests: menu ids, `reply_choices` used for the sheet rows, the empty export pill text.

### S7. UI: "Call back later" reminders (U13)
- Row menu `rm-remind` for callable rows (first for Missed rows, after Call back), and a "Remind me"
  item on the Number sheet. It opens `SheetKind::RemindPick { number, name, missed }` with the four
  `reminder::Slot::ALL` rows labelled by `Slot::label(now, tz)`. Choosing one builds `new_reminder`,
  calls `backend::arm_reminder`, and keeps an in-memory `reminders: Vec<Reminder>` (loaded from
  `backend::armed_reminders()` at start and on `reminder_fired` events; find how `scheduled_fired` or
  other bridge events reach the app in `backend.rs` and mirror that). The pill is `set_text(r)` with
  Undo (`PillAction::CancelReminder(i64)` -> `disarm_reminder`).
- When a reminder exists for a row's number (`reminder::for_number`), the row menu shows
  "Cancel reminder (20:00)" (`rm-unremind`) instead, with the pill "Reminder cancelled" and Undo
  (re-arm: `PillAction::RestoreReminder(Box<Reminder>)`). The Number sheet shows a small "Reminder at
  20:00" caption.
- Desktop: the pill says what the other Android-only hand-offs say when they are unsupported.
- Tests: the menu shows remind vs unremind from a reminder list (a pure helper `fn remind_item(list,
  number) -> (&'static str, String)`), and the PillAction round trip.

### S8. UI: keypad field menu, speed-dial share/clear, docs and backlog (U14, U22)
- `keypad.rs`: long-press on the dialed-number field (new `HoldAction::FieldMenu`, armed through the
  same hold timer path as `Pause`/`Wait`; the field's element id is in `keypad_view`) opens a small
  glass popover above the field with `dial::field_menu(&self.dialed, clipboard_has_text)` items.
  `clipboard_has_text` comes from `cx.read_from_clipboard()` as `paste_press` does. Copy writes
  `shown_number`/raw (`ClipboardItem::new_string`, pill "Copied"). Paste reuses `paste_press`. Clear
  empties the field with the pill "Cleared" and Undo (`PillAction::RestoreDialed(SharedString)`). A tap
  outside closes the popover. Add a medium haptic on open.
- `speed_dial_ui.rs`: a header row with "Share" (`sd-share`, `backend::share_text(&list_text(map))`,
  hidden when empty) and "Clear all" (`sd-clear`, destructive). "Clear all" opens a confirm sheet
  (`SheetKind` alongside the reset-replies sheet pattern in `replies_ui::replies_reset_sheet`: "Clear
  speed dial?" / "n numbers will be removed from keys 2-9."). Yes runs `speed_dial::clear_all` through
  `update_prefs`, with the pill "Speed dial cleared" and Undo (`PillAction::RestoreSpeedDialAll(Box<BTreeMap<u8,
  SpeedDialEntry>>)`).
- Docs: `docs/phone-acceptance.md` gets manual checks for U12-U14, U16, U20-U22 and U24.
  `docs/phone-prefs-v2.md` needs nothing new (reminders live in the Kotlin store). Update this file's backlog:
  the planned R3 rows become `done R3`.
- Tests: the hold action maps to the field menu; `RestoreDialed`/`RestoreSpeedDialAll` pill text; the
  confirm copy.
- Final: the full check list above, then `git status` clean apart from the commit.
