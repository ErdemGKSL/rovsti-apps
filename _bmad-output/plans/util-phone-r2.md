# util-phone: everyday utilities for Rovsti Phone (round 2)

baseSha: ca837b65b5fb0475775b60b6f4c7385569e64d2f

## Audit (what exists at baseSha)

Round 1 (82abee0..ca837b6) shipped U1-U6 as planned in `util-phone.md`. These are in the tree:
`rovsti_core::{zoom, number_format, call_log_query}`, `rovsti_android::media`
(`save_image_to_gallery`, `share_image`), `documents::export_calls`, `MediaShare.kt` with
`RovstiShareProvider`, `apps/phone/src/photo_viewer.rs` (full screen, pinch, double-tap, pan, swipe to
close, Save, Share), Recents search and filter chips, the Copy as / Share as chooser
(`SheetKind::CopyAs`), CSV export in Settings > Backup, and pause/wait long-press on `*` and `#`.
Earlier work also shipped: block/unblock and the Blocked list, delete with Undo, the Recents row menu
(`block_ui.rs`, with `rm-copy/add/msg/wa/block/delete`), paste-to-dial, T9, speed dial, quick replies,
prefs backup, and the in-call controls (mute, keypad, route, add, hold/swap, merge, timer).
`git stash list` is empty.

Still missing:
- No call statistics anywhere. `call_log::call_counts` and `frequent` exist but report no talk time.
- No per-call notes. Sheet history lines (`recents::HistoryVm`, `history_section`) can only be deleted in
  Edit mode, and tapping a line does nothing. No call-details view exists (`blocked_ui.rs:450` points
  at a future "Call details").
- No QR. `rovsti_wa_ui::qr_png(payload, min_px) -> Result<Vec<u8>, QrError>` is public and Phone already
  depends on `rovsti_wa_ui`, so no new dependency is needed.
- The photo viewer cannot set, replace or remove a photo. The building blocks exist:
  `documents::pick_contact_photo`, `contacts_edit::{photo_size, set_photo, remove_photo}`,
  `rovsti_core::contact_photo::plan`, `DocumentOp::ContactPhoto`, and a working pattern in
  `apps/contacts/src/backend.rs` (`change_photo`, `decode_size`, `photo_words`). The Phone manifest
  already has `WRITE_CONTACTS`.
- The in-call screen has no note and no copy-number. Its 3x2 grid is full: mute, keypad, audio / add,
  hold, merge.

Persistence facts: the app's own JSON files sit next to `prefs.json` through
`rovsti_ui::storage::{save_json, load_json}` (see `wa_call.rs` `HISTORY_FILE`). The data dir comes from
`rovsti_ui::storage::data_dir`/the Backend's prefs writer. Notes use the same pattern, so the
Android layer is not needed for them.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| U1 | Photo viewer: full screen, pinch/double-tap zoom, pan, swipe down to close | done R1 |
| U2 | Save photo to gallery, share photo | done R1 |
| U3 | Recents search and filter chips (All/Missed/Incoming/Outgoing/WhatsApp) | done R1 |
| U4 | Copy as / Share as: display, E.164, national, tel: link, vCard | done R1 |
| U5 | Export call log as CSV | done R1 |
| U6 | Keypad pause/wait long-press on `*`/`#` | done R1 |
| U7 | Call statistics: per person in the Number/Contact sheet; a Settings "Call statistics" page (this week, last 7 days per day, last 8 weeks, top people by talk time) | R2 S1, S7 |
| U8 | Per-call notes: add/edit/delete on a call (Call details sheet), note glyph on history lines, confirm on delete with Undo | R2 S2, S4, S5 |
| U11 | Call details sheet: tap a history line to see date, time, direction, duration, SIM and WhatsApp, with Copy details, Note and Delete this call | R2 S2, S5 |
| U17 | In-call: Note button (saved and matched to the logged call afterwards), long-press the caller's number to copy it | R2 S6 |
| U9 | Share contact as QR (vCard), with Save/Share of the QR image | R2 S3, S8 |
| U10 | Set/replace/remove the contact photo from the viewer (confirm remove) | R2 S4, S8 |
| U18 | Share a person's call statistics as text (from the stats block) | R2 S7 |
| U19 | CSV export gains a `note` column | R2 S2, S5 |
| U12 | Recents select mode with multi-delete and Undo | backlog R3 |
| U13 | "Call back later" reminder on a missed call (local notification `notify::show`, 15 min / 1 h / tonight) | backlog R3 |
| U14 | Keypad field long-press menu: Copy / Paste / Clear (with Undo) | backlog R3 |
| U15 | Recents date-section headers (Today, Yesterday, weekday, date) | backlog R3 |
| U16 | Export one person's call history (CSV) from the Contact sheet | backlog R3 |
| U20 | Row menu "Send a quick reply" on missed calls (reuses quick replies, `send_sms_to` with body) | backlog R3 |
| U21 | Notes search: the Recents search text also matches note text | backlog R3 |
| U22 | Speed dial page: Share the speed-dial list as text, clear all with confirm and Undo | backlog R3 |
| U23 | Haptic and pill feedback audit: every new utility has a pill and a haptic (`HapticFeedback`) | backlog R3 |

## Steps (round 2)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free, run `rm -rf target/debug/incremental` first. Make one focused commit per step
(`Rovsti phone: ...`). Read `.claude/skills/liquid-glass-gpui/SKILL.md` before S5-S8. Never invent GPUI
APIs: copy patterns already in `apps/phone/src` (sheets, `touch_surface`, `presses`, pills,
`SearchField`).

### S1. Pure call statistics: `rovsti_core::call_stats`
- New `crates/rovsti_core/src/call_stats.rs`, `pub mod call_stats;` in `lib.rs`. No new deps.
- Input type (so both sources feed one path):
  `#[derive(Clone, Debug, PartialEq, Eq)] pub struct StatCall { pub number: String /*raw*/, pub name:
  Option<String>, pub date_ms: i64, pub duration_s: u64, pub dir: StatDir, pub whatsapp: bool }` with
  `pub enum StatDir { Incoming, Outgoing, Missed }`.
  `pub fn from_sources(entries: &[CallLogEntry], extras: &[recents::ExtraCall]) -> Vec<StatCall>`.
  It skips `Blocked` and `Unknown`, counts `Rejected` and `Voicemail` as `Missed`, and extras map by
  `missed`. `ExtraCall` (recents.rs:242, not serde, built in 8 places) has no direction today. Add
  `pub outgoing: bool` to it. Fill it in `apps/phone/src/wa_call.rs::extra_calls` from
  `record.direction == CallDirection::Out` (`rovsti_wa_proto::history::WaCallRecord.direction`), and
  set it at every other construction site, which are tests and samples (`grep -rn "ExtraCall {"`).
  `into_row` can then use `CallType::Outgoing`/`Incoming` instead of `Unknown` only if the existing
  recents tests still pass; otherwise leave `into_row` as it is.
- `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)] pub struct Totals { pub calls: u32, pub
  incoming: u32, pub outgoing: u32, pub missed: u32, pub talk_s: u64, pub longest_s: u64 }` with
  `fn add(&mut self, c: &StatCall)`. Talk time counts only answered calls (`duration_s` > 0, not missed).
- `pub fn for_numbers(calls: &[StatCall], numbers: &[&str]) -> Totals`. It matches with
  `PhoneNumber::parse` + `matches`, and on a parse failure falls back to `normalized` equality.
- `pub struct DayBar { pub day_start_ms: i64, pub label: SmallText<…> or String /*"Mon"*/, pub talk_s:
  u64, pub calls: u32 }`. `pub fn last_days(calls, now_ms, tz_offset_s, n: usize) -> Vec<DayBar>` gives
  the oldest bar first and today last. Days are local civil days through `call_log::civil_from_days`
  (already `pub(crate)`). Weekday labels are English three-letter.
- `pub fn last_weeks(calls, now_ms, tz_offset_s, n) -> Vec<DayBar>`. Weeks start on Monday, and the
  label is `"3 Nov"` (day + month short).
- `pub struct PersonStat { pub key: String /*canonical number*/, pub name: Option<String>, pub
  number: String, pub totals: Totals }`. `pub fn top_people(calls, since_ms: i64, limit: usize) ->
  Vec<PersonStat>` groups by `PhoneNumber` match (canonical key; national and international forms of
  one number collapse, with the same `infer_home_cc` evidence approach or `PhoneNumber::matches` over a
  small key list). It sorts by `talk_s` desc, then `calls` desc, then key. The newest non-empty name
  wins. The work is bounded: the first 5000 calls.
- `pub fn talk_text(seconds: u64) -> String` gives `"0 min"`, `"45 s"`, `"12 min"`, `"1 h 05 min"` and
  `"26 h 10 min"`. Also `pub fn summary_line(t: &Totals) -> String` gives `"12 calls · 1 h 05 min"`,
  and `pub fn share_text(name: &str, t: &Totals, week: &Totals) -> String` produces the multi-line plain
  text for U18.
- Tests: from_sources skips blocked and maps rejected to missed; totals ignore the duration of missed
  calls; national and international forms of one number add up in `for_numbers` and `top_people`; day
  bars across a tz offset (a call at 23:30 local lands on the right day with `tz_offset_s = 10800`); a
  week boundary on Monday 00:00 local; `last_days(…, 7)` always has 7 bars, including empty ones;
  talk_text edges (0, 59, 60, 3599, 3600, 3900); top_people ordering, ties and limit.
- Acceptance: `cargo test -p rovsti_core call_stats` passes, and the app is unchanged.

### S2. Pure call notes and call details: `rovsti_core::call_notes`
- New `crates/rovsti_core/src/call_notes.rs`, `pub mod call_notes;`. It uses serde (already a dep).
- `#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)] pub struct CallNote { pub number:
  String /*raw as dialed*/, pub at_ms: i64 /*call start*/, pub text: String, pub edited_ms: i64 }`.
  The key is number + time, not `EntryRef`, because call-log ids change after a backup restore and a
  note typed in-call exists before the call is logged.
- `#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] pub struct Notes {
  #[serde(default)] pub schema: u32, #[serde(default)] pub notes: Vec<CallNote> }` with:
  - `pub const FILE: &str = "call-notes.json"`, `pub const MAX_LEN: usize = 1000` (chars),
    `pub const MAX_NOTES: usize = 2000`, `pub const MATCH_WINDOW_MS: i64 = 120_000`.
  - `pub fn clean(text: &str) -> Option<String>` trims, collapses runs of 3+ newlines to 2, drops
    control chars except `\n`, and clips to `MAX_LEN` on a char boundary. Empty input gives `None`.
  - `pub fn find(&self, number: &str, date_ms: i64) -> Option<usize>` returns the note with a
    `PhoneNumber::matches` number (an empty or private number never matches) and the smallest
    `|at_ms - date_ms|` within `MATCH_WINDOW_MS`.
  - `pub fn set(&mut self, number: &str, at_ms: i64, text: &str, now_ms: i64) -> SetOutcome` (`enum
    SetOutcome { Added, Updated, Removed /*cleaned to empty*/, Unchanged }`). It replaces the matched
    note. When over `MAX_NOTES` it drops the oldest by `at_ms`.
  - `pub fn remove(&mut self, number: &str, date_ms: i64) -> Option<CallNote>` (returned for Undo),
    and `pub fn restore(&mut self, note: CallNote)`.
  - `pub fn for_calls(&self, calls: &[(String, i64)]) -> Vec<bool>` (glyph flags for history lines,
    one pass, no per-frame use).
  - `pub fn parse(json: &str) -> Notes` never fails (bad JSON gives empty), plus `pub fn preview(text:
    &str) -> String` (first line, 60 chars, `…`).
- Call details text (U11), in the same module or `recents.rs`:
  `pub struct CallDetails { pub title: String /*"Incoming call"*/, pub when: String /*"Thu 5 Nov 2026,
  14:32"*/, pub duration: Option<String>, pub channel: String /*"Mobile" | "WhatsApp" | SIM label*/,
  pub number: String }` and `pub fn details(line: &recents::HistoryLine, number: &str, sim:
  Option<&str>, locale: recents::LocaleTime) -> CallDetails`. It uses `call_log::format_duration`, the
  `DateOrder` of `locale.format`, and 12/24 h. `pub fn details_text(d: &CallDetails, note:
  Option<&str>) -> String` builds the multi-line copy text: number, when, direction, duration, then
  `Note: …`.
- U19: in `call_log_query::to_csv`, add an optional notes argument as a new fn `to_csv_with_notes(entries,
  extras, tz, notes: &Notes) -> String` that adds a `note` column (quoted and injection-guarded like the
  other cells). Keep `to_csv` as a wrapper with empty notes so existing tests stay unchanged.
- Tests: clean (whitespace, control chars, clip at 1000 with multibyte chars, empty gives None); find
  matches national vs international within the window and not outside it; the nearest of two candidates
  wins; a private number never matches; set Added, Updated, Removed and Unchanged; the cap drops the
  oldest; remove then restore round-trips; JSON round-trip, and garbage gives empty; details text in dmy
  and mdy order, 12 h and 24 h, a missed call has no duration, WhatsApp channel; CSV with notes has the
  column and an injection-guarded note `=cmd`.
- Acceptance: `cargo test -p rovsti_core call_notes call_log_query` passes.

### S3. Pure contact QR payload: `rovsti_core::number_format::contact_qr`
- Add to `crates/rovsti_core/src/number_format.rs`: `pub const QR_MAX_BYTES: usize = 1200` and
  `pub fn contact_qr(name: &str, numbers: &[(&str /*raw*/, Option<&str> /*label*/)], home_cc:
  Option<&str>) -> Result<String, QrTooBig>`. It builds one vCard 3.0 through `vcard::write` with a
  `ContactDraft` (`given` = name and the rest empty; check how `vcard::write` emits `FN`/`N`). Each
  number is rendered as E.164 when known (`render(.., E164, ..)`, falling back to the raw trimmed
  number). Labels map to `TYPE=` the way `vcard.rs` already maps `DraftField` labels. Duplicates
  are dropped. If the card is over `QR_MAX_BYTES`, numbers are dropped from the end until it fits, and
  `QrTooBig` comes back only when even one number does not fit. Return the count kept in a small struct
  `ContactQr { pub payload: String, pub kept: usize, pub dropped: usize }` instead of `String` so the UI
  can say "2 numbers left out".
- Also add `pub fn qr_file_name(name: &str) -> String` (`rovsti-<slug>-qr.png`, the same slug rules as
  `photo_viewer::photo_file_name`; move that slug helper here as `pub fn file_slug(name) -> String`
  and have `photo_file_name` call it).
- Tests: the payload parses back through `vcard::parse` with name and all numbers in E.164; duplicates
  (national plus international of the same number) appear once; a long list is trimmed to fit, with the
  right `kept`/`dropped`; a name with commas, semicolons and non-ASCII is escaped and round-trips;
  `file_slug` for Turkish characters, emoji and an empty name (`"contact"`).
- Acceptance: `cargo test -p rovsti_core number_format` passes, and `photo_viewer` tests still pass.

### S4. Glue: notes store, QR image, contact photo edit (`apps/phone/src/backend.rs`, new `notes.rs`)
- New `apps/phone/src/notes.rs`: `pub(crate) struct NotesStore { notes: call_notes::Notes, dir:
  Option<PathBuf>, rev: u64 }` held on `PhoneApp` (or the Backend entity, whichever already owns
  `data_dir`; follow `wa_call.rs`: load in the background with `rovsti_ui::storage::load_json(dir,
  call_notes::FILE)`, save off the UI thread with `save_json`, and bump `rev` on change so views rebuild
  their glyph flags only when `rev` moves). Methods: `get(number, at) -> Option<&CallNote>`, `set(..) ->
  SetOutcome`, `remove(..) -> Option<CallNote>`, `restore(note)`, `snapshot() -> call_notes::Notes`
  (for CSV). Include notes in nothing else (not prefs backup this round; write that in docs).
- Backup export (U19): `backup_ui.rs`'s CSV export calls `to_csv_with_notes` with the store snapshot.
- QR image: `pub fn qr_image(payload: &str) -> Option<(Arc<RenderImage>, PathBuf)>` in `backend.rs`.
  It calls `rovsti_wa_ui::qr_png(payload, 720)`, writes the PNG into the temp/cache dir the photo viewer
  already uses for its export (`photo_viewer::export_png`'s dir; reuse or lift that helper), and
  decodes it into a `RenderImage` the same way `photo_viewer` builds one. Desktop works fully (save and
  share then report Unsupported through the existing `save_to_gallery`/`share_image` wrappers).
- Contact photo edit (port from `apps/contacts/src/backend.rs`, do not import from that app):
  `pub fn pick_contact_photo() -> Outcome` (`documents::pick_contact_photo`), `pub fn
  change_contact_photo(contact_id: i64, path: &str) -> Result<(), String>` (`photo_size` ->
  `contact_photo::plan` -> `set_photo`, with the same `decode_size`/`photo_words` helpers moved
  into the phone backend), and `pub fn remove_contact_photo(contact_id: i64) -> Outcome`. In the
  `document_result` hand-back (`backup_ui.rs` `DocumentOp` match, or `main.rs` where it is routed),
  add `DocumentOp::ContactPhoto` and keep a `pending_photo_for: Option<i64>` on `PhoneApp`, so only a
  pick the phone itself started is applied. Run `change_contact_photo` on the background executor,
  then refresh the contact's photo (invalidate its `photos.rs` LRU entry, check the LRU's API).
- Tests: the backend tests in `backend.rs` (`#[cfg(test)]`, desktop) get pick/remove returning the
  Unsupported outcome, `qr_image` producing a decodable PNG file, and `decode_size` for bare,
  enveloped, `ok:false` and zero answers (port the contacts tests). `NotesStore` get, set and remove
  without a dir (in memory) and `rev` bumps.
- Acceptance: all checks green, including the three aarch64 checks.

### S5. Call details sheet with notes (U11, U8, U19 reachable)
- `recents.rs` `history_section`: each history line becomes a tap target (id `sheet-nhl-{i}` /
  `sheet-chl-{i}`, routed through `history_edit_act`'s prefix logic) that opens the details sheet. In
  Edit mode the trash button keeps priority. Lines with a note show a small `Icon::LucideStickyNote`
  (or the nearest existing Lucide icon in `rovsti_ui::Icon`; grep the enum) before the detail text.
  `HistoryVm` gains `pub(crate) number: SharedString`, `pub(crate) date_ms: i64` and `pub(crate) note:
  bool`, filled once in `history_for`/`blocked_history_for` from `NotesStore::for_calls`.
- New `SheetKind::CallDetails` and `apps/phone/src/details_ui.rs`: `pub(crate) struct DetailsSheet {
  details: call_notes::CallDetails, entry: EntryRef, number: SharedString, at_ms: i64, back_to:
  SheetKind /*Number|Contact*/, editing: bool, field: Entity<SearchField> }`. Layout follows the
  liquid-glass sheet recipe: the title, then grouped rows (When, Duration, Channel, Number), then a
  Note card that shows the note or "Add note". Below sit action rows: Copy details (`telecom::copy_text`
  -> pill "Details copied"), Edit note / Add note (the `SearchField` with `TextInputAction::Done`, the
  same as `replies_ui.rs`, saving on Done or on leaving the sheet, pill "Note saved"), Delete note
  (red, confirm through a second tap state "Tap again to delete" or the existing confirm-sheet pattern,
  then a pill "Note deleted" with Undo through `show_pill_action` restoring the `CallNote`), and Delete
  this call (red, reuses `delete_row_calls(Arc::from([entry]))`, which already gives Undo, then returns
  to the previous sheet). Back or close returns to `back_to` with its scroll kept.
- Tests (pure, in `details_ui.rs`): id round-trip `details_line_id`/`details_line_index` for both
  prefixes; the router sends `sheet-nhl-3` to the Number sheet and `sheet-chl-3` to the Contact sheet;
  the note glyph flags line up with lines.
- Acceptance: all checks green. Extend `perf_harness` with a headless test that opens the Number sheet
  of a sample number, taps line 0, and asserts that `SheetKind::CallDetails` is open with the sample
  call's `when` text.

### S6. In-call note and copy number (U17)
- `incall.rs`: under the status/timer line, a small glass capsule "Note" (`Icon` pencil; id
  `ic-note`) shows for a connected or held call with a callable number. It opens a bottom note card
  over the in-call screen with a `SearchField` (Done saves through `NotesStore::set(number,
  call_start_ms, ..)`). `call_start_ms` is the time the call was placed or rang
  (`connect_time_ms` is when it connected, so add `pub started_ms: i64` to `CallView`, filled from the
  snapshot/Tracker `started_ms` the same way `connect_time_ms` is). The pill "Note saved" appears, and
  the capsule then reads "Note ✓" (use a check icon, not the glyph character, if a check `Icon`
  exists).
- Long-press (the existing hold machinery in `incall.rs`/`presses`) on the caller's name/number block:
  copy the number as shown (`telecom::copy_text`, haptic `LongPress`, pill "Number copied"). A private
  caller gets the pill "No number to copy".
- The note matches the logged call later through `MATCH_WINDOW_MS` (S2), so no extra wiring is needed.
  Write in `docs/phone-acceptance.md` that a call longer than the window after ringing still matches,
  because the log `DATE` is the start.
- Tests: `CallView` from a sample snapshot carries `started_ms`; the note capsule visibility helper
  `fn note_allowed(view: &CallView) -> bool` (not for private numbers or a ringing incoming call); the
  copy text helper for private and normal numbers.
- Acceptance: all checks green, and the in-call perf_harness tests still pass.

### S7. Call statistics UI (U7, U18)
- Number and Contact sheets: a "Call statistics" block under Recent calls, built once at open from
  `call_stats::for_numbers` over `from_sources(log.source, extras)`. Cache the `Vec<StatCall>` on the
  Backend `CallLog` snapshot (compute when the log or extras change, never per frame). It shows
  `summary_line`, then In/Out/Missed counts, the longest call, and a 7-bar mini chart (`last_days`,
  `div` bars with heights proportional to `talk_s`, tinted by theme accent; follow the dataviz rules:
  no legend needed, label today). A trailing Share button (`sheet-nst-share`/`sheet-cst-share`) sends
  `share_text` through `backend::share_text`, with a pill on failure. The block is hidden when
  `calls == 0`.
- Settings: new `Sub::Stats` ("Call statistics", prefix `set-st-`) and a `RowId::CallStats` row in the
  Data section (keep `RowId::COUNT` in sync). The page has three segments, This week / 7 days /
  8 weeks (reuse `segmented_control`), a totals header, a bar chart of `last_days(7)` or
  `last_weeks(8)`, and a top-people list (`top_people(since, 10)`), where each row shows name or number
  plus `talk_text`. Tapping a row opens that person's Number sheet. Empty state: "No calls yet".
  It is disabled with a note while the call log is not loaded (same as `export_calls_note`).
- Tests: the settings rows include CallStats with the right note when not loaded; `Sub::Stats.prefix()`
  is unique among subs (extend the existing prefix test if any); the bar height helper `fn
  bar_frac(v: u64, max: u64) -> f32` (0 max gives 0, and a non-zero value has a 0.04 minimum so it
  stays visible).
- Acceptance: all checks green. A headless perf_harness test opens Settings > Call statistics with the
  sample log and asserts 7 bars are laid out inside the viewport.

### S8. QR share and photo set/replace/remove in the UI, docs, verification
- Contact sheet: a "Share as QR code" row (id `sheet-c-qr`) opens the photo viewer (`PhotoViewer::open`)
  in a QR mode (`PhotoViewer.kind: ViewerKind::{Photo, Qr}`), titled with the contact name and showing
  the `backend::qr_image(contact_qr(..).payload)` image on a white rounded card. Zoom and pan are off
  for a QR (double-tap does nothing, and pinch is clamped to 1). Save stores it as `qr_file_name`
  (pill "Saved to Photos"), and Share uses `share_image`. If numbers were dropped, a pill says
  "2 numbers left out to fit". The Number sheet gets the same row for a single number with the name
  as the number.
- Photo viewer, photo mode, contact with an id: the bottom bar gains an "Edit" button that opens a
  small action sheet (reuse `SheetKind`-style menu or the row-menu component) with Choose new photo
  (or Add photo when the contact has none; from the Contact sheet avatar slot open this menu directly
  when there is no photo), and Remove photo (red). Remove asks first with the confirm sheet "Remove
  photo of {name}?" before `remove_contact_photo`. Then come a pill "Photo removed", the viewer
  closing, and the photo cache entry being cleared. Choose runs `pick_contact_photo`, and the
  `ContactPhoto` hand-back from S4 applies it with the pill "Photo updated", or `why_not` text on
  failure. Desktop shows the Unsupported outcome pill.
- Docs: `docs/phone-acceptance.md` sections for U7-U11, U17, U18 and U19 (each with steps to check on
  a device), `docs/phone-prefs-v2.md` (or a new short section) describing `call-notes.json` and that
  it is not in the prefs backup, and `docs/android.md` noting that Phone now uses the contact-photo
  picker hand-back. Update this file's backlog: U7, U8, U9, U10, U11, U17, U18 and U19 become
  `done R2`.
- Tests: `ViewerKind::Qr` blocks pinch and double-tap (pure `PhotoViewer` tests next to
  `it_opens_only_with_a_photo`); the confirm text for removal; menu ids round-trip.
- Run the full checks. Acceptance: all green, and the working tree is clean after the commit.
