# util-phone: everyday utilities for Rovsti Phone (round 4)

baseSha: 53e0513746b31a6868d296925af0c1204b6216eb

## Audit (what exists at baseSha)

Round 3 (ae48e3b..53e0513) shipped every R3 row of the backlog: notes search, Recents select mode
with multi-delete, Undo for Delete all, the quick reply on missed calls, per-person CSV, call-back
reminders (`rovsti_core::reminder`, `rovsti_android::reminders`, `CallReminders.kt`, `remind_ui.rs`),
the keypad field menu and Speed dial Share/Clear all. `git stash list` is empty. Disk had 7.0 GB free.

The R3 backlog rows still open, checked by grep at baseSha:
- U15: there are no day sections in Recents. `recents.rs` draws a `uniform_list` of fixed 58 px rows
  (`ROW_H`, `row_at`). `call_log::relative_time` already knows "Yesterday" and weekdays.
- U25 (long-press the name to copy) is dropped. The row menu already has `rm-copy`, and a second
  long-press meaning on the same row would collide with the menu.
- U26: the Call details sheet (`details_ui.rs`) has `sheet-dt-copy` ("Copy details" from
  `call_notes::details_text`) but no Share.
- U27: the Blocked page (`blocked_ui.rs`, `Sub::Blocked`, prefix `set-bk-`) unblocks one row at a time
  (`unblock_pill`, `PillAction::Block` Undo). It has no Share list and no Unblock all.
  `rovsti_core::blocklist` has `numbers_line`, `parse_input`, `can_block`.
- U28: the Stats page (`stats_ui.rs`, `Sub::Stats`, `Range::{Week, Days7, Weeks8}`, `top_people`) has
  no export. Only a person's stats can be shared (`NUMBER_SHARE`/`CONTACT_SHARE`).
- U29 is already done. The keypad has `kp-add` ("Add to contacts", `backend::add_contact`), and the
  row menu has `rm-add`.
- U30: there is no page that lists every note. Notes are reached only through a call
  (`details_ui.rs`, `notes.rs` `NotesStore`, `call_notes::Notes { notes: Vec<CallNote> }`).
- U23: the haptic/pill audit was never done as its own step.

New gaps found in this audit:
- The call button on an empty keypad does nothing. `call_press_on` runs `dial::validate("")`, which
  fails and calls `invalid()`. Phones usually fill in the last dialed number (redial).
- `dial::normalise_paste` keeps every dial char of the whole clipboard. So "Office 0212 555 01 01 or
  mobile 0532 111 22 33" becomes one long number, which is wrong. No paste ever offers a choice.
- The Blocked page's add field (`set-bk-field`/`set-bk-add`) takes one number. Pasting a list of spam
  numbers cannot block them all at once.
- Notes cannot be exported or shared as a whole.

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
| U12 | Recents select mode with multi-delete, confirm and Undo | done R3 |
| U13 | "Call back later" reminders with Undo and Cancel | done R3 |
| U14 | Keypad field long-press menu: Copy / Paste / Clear (with Undo) | done R3 |
| U16 | Export one person's call history (CSV) | done R3 |
| U20 | "Send a quick reply" on missed calls | done R3 |
| U21 | Notes search in Recents | done R3 |
| U22 | Speed dial Share list / Clear all with Undo | done R3 |
| U24 | Delete all call history gains Undo | done R3 |
| U29 | Keypad "Add to contacts" for an unknown typed number | done (pre-existing `kp-add`) |
| U25 | Recents long-press name to copy | dropped (covered by `rm-copy`) |
| U31 | Redial: Call on an empty keypad fills the last dialed number (second tap calls) | planned R4 (S2, S7) |
| U32 | Smart paste: clipboard with several numbers opens a "Which number?" chooser | planned R4 (S2, S7) |
| U26 | Call details: "Share" (text through `share_text`) next to Copy | planned R4 (S1, S3) |
| U27 | Blocked page: Share the list as text; Unblock all with a confirm and Undo | planned R4 (S1, S3) |
| U30 | Notes page (Settings > Call notes): list, search, open the call, delete with Undo | planned R4 (S2, S6) |
| U33 | Notes page: Share all notes as text | planned R4 (S2, S6) |
| U15 | Recents sticky day capsule (Today, Yesterday, weekday, date) over the list | planned R4 (S1, S5) |
| U28 | Stats page: Export CSV of per-person totals for the chosen range | planned R4 (S1, S4) |
| U34 | Blocked page: paste several numbers and block them all at once (with Undo) | planned R4 (S2, S7) |
| U23 | Haptic and pill feedback audit over every utility | planned R4 (S8) |
| U35 | Recents filter chip "With notes" | backlog R5 |
| U36 | Contact sheet: "Copy all numbers" / share every number as text | backlog R5 |
| U37 | In-call: copy the call timer / "Share call summary" after the call ends | backlog R5 |
| U38 | Blocked page: Export/Import the block list as a text file (document picker) | backlog R5 |
| U39 | Recents: "Mark all missed as seen" (clears the missed badge) if a badge exists | backlog R5 (check first) |

## Steps (round 4)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free, run `rm -rf target/debug/incremental` first. Make one focused commit per step
(`Rovsti phone: ...`). Read `.claude/skills/liquid-glass-gpui/SKILL.md` before S3-S8. Never invent GPUI
APIs: copy patterns that already exist in `apps/phone/src` (`Sub` pages and their `prefix()`, sheets with
`SheetKind`, `presses`, `show_notice_pill`/`show_pill_action`, `PillAction`, `SearchField`,
`rovsti_ui::haptics::perform`, `uniform_list`). Every utility gets a pill. Every destructive one gets a
confirm and an Undo. No step needs new Android glue: `share_text`, `export_calls` (CSV mime), `block` and
`unblock` exist already.

### S1. Pure: day sections, blocked list text, stats CSV, details share (`rovsti_core`)
- `call_log.rs`: `pub fn day_section(now_ms: i64, at_ms: i64, tz_offset_s: i32) -> String` gives
  `"Today"`, `"Yesterday"`, the weekday (`"Monday"`) for 2-6 days back, and `"3 Nov"` (or
  `"3 Nov 2025"` in another year) further back. Use local civil days, the same day maths as
  `relative_time`, and do not duplicate it: factor a private `local_day(ms, tz) -> i64` if needed.
  Also `pub fn day_key(at_ms: i64, tz_offset_s: i32) -> i64` (the local day number).
- `recents.rs` (core): `pub fn section_for(rows: &[RecentsRow], visible: &[usize], first_visible:
  usize, now_ms, tz) -> Option<String>` gives the label of the row at `visible[first_visible]` by its
  `last_ms`. `pub fn section_starts(rows, visible, tz) -> Vec<usize>` gives the visible positions
  where the day changes (position 0 included), for the capsule's push-up handoff.
- `blocklist.rs`: `pub fn list_text(rows: &[BlockedNumber], name_of: impl Fn(&str) -> Option<String>)
  -> String` gives `"Blocked numbers\n+44 20 7946 0958\nAnna Smith  +90 532 111 22 33\n..."`
  (grouped through `number_format` display form, in list order, empty for no rows). `pub fn
  unblock_all_text(n: usize) -> (String, String)` gives `("Unblock all numbers?", "3 numbers can call
  and text you again.")`, with a singular form for 1.
- `call_stats.rs`: `pub fn people_csv(people: &[PersonStat], range: Range) -> String` with the header
  `name,number,calls,incoming,outgoing,missed,talk_seconds,longest_seconds,range`. Escape it the way
  `call_log_query`'s CSV does (reuse its escaper; make it `pub(crate)` if it is private). Also `pub fn
  stats_csv_file_name(range: Range, now_ms, tz) -> String`, for example
  `rovsti-call-stats-this-week-2026-11-06.csv`.
- `call_notes.rs`: `pub fn share_text(d: &CallDetails, who: Option<&str>, note: Option<&str>) -> String`
  gives `"Call with Anna Smith\n"` + `details_text(d, note)`, and leaves out the header for `None` or an
  empty `who`.
- Tests (in-module): day_section at midnight edges, a negative and a positive tz, the year change;
  section_starts over mixed days and an empty `visible`; list_text order, names and empty; the
  unblock_all singular and plural; CSV header, a comma or quote in a name, an empty list (header only);
  the file name per range; share_text with and without who.

### S2. Pure: smart paste, redial, bulk block, notes list (`rovsti_core`)
- `dial.rs`: `pub fn extract_numbers(text: &str) -> Vec<String>` splits the text into runs that look
  like phone numbers. A run is digits with `+ ( ) - . space` inside it. It ends at a letter, a newline,
  `,`/`;`/`/` followed by a space, or " or ". Each run is cleaned through `normalise_paste`, and a run
  is kept when it has 3 to `MAX_LEN` digits. Duplicates by `PhoneNumber::matches` are removed (the first
  one is kept), and at most 8 are returned. A `tel:` URI or a plain single number gives one entry. So
  `normalise_paste`'s behaviour for single numbers is unchanged.
  `pub enum PastePlan { Nothing, One(String), Choose(Vec<String>) }` and `pub fn paste_plan(text:
  &str) -> PastePlan`.
- `dial.rs`: `pub fn redial_target(entries: &[CallLogEntry]) -> Option<String>` gives the `number_raw`
  of the newest `CallType::Outgoing` entry by `date_ms` that is callable (not private or empty).
  WhatsApp calls are not in `CallLogEntry`, so they are excluded on purpose.
- `blocklist.rs`: `pub fn parse_many(text: &str, emergency: &EmergencyList, already: &BlockSet) ->
  BulkParse` with `pub struct BulkParse { pub to_block: Vec<String>, pub skipped_emergency: usize,
  pub already_blocked: usize }`. It uses `dial::extract_numbers` and then `parse_input` on each entry.
  Use the actual blocked-set type in `blocklist.rs` (the struct with `from_rows`/`is_blocked`).
  `pub fn bulk_text(b: &BulkParse) -> String` gives `"Blocked 3 numbers"` or `"Blocked 2 numbers, 1
  already blocked"`.
- `call_notes.rs`: `#[derive(Clone, Debug, PartialEq, Eq)] pub struct NoteRow { pub index: usize,
  pub number: String, pub at_ms: i64, pub preview: String }` and `impl Notes { pub fn rows(&self,
  query: &str, name_of: impl Fn(&str) -> Option<String>) -> Vec<NoteRow> }`, newest first. It filters
  by folded text, number digits or name when the trimmed query has at least 1 char. `pub fn
  all_text(&self, name_of, tz_offset_s) -> String` gives one block per note (`"Anna Smith, Thu 5 Nov
  2026, 14:32\n<text>"`, separated by blank lines, newest first, empty when there are none). Reuse
  `when_text`.
- Tests: extract_numbers on a sentence with two numbers, `tel:` URI, single grouped number (same as
  `normalise_paste`), duplicates in national and international form, digit-poor text ("Room 12") ->
  empty, cap of 8; paste_plan variants; redial skips incoming/missed and private, picks newest;
  parse_many counts emergency and already-blocked; NoteRow order and query by name/number/text;
  all_text format.

### S3. UI: Call details Share, Blocked Share and Unblock all (U26, U27)
- `details_ui.rs`: add `const SHARE: &str = "sheet-dt-share"` next to `COPY`, `Icon` share (grep the
  share icon that `stats_ui` uses), `"Share"`. On tap call `backend::share_text(&call_notes::share_text(..))`
  with the pill `outcome.why_not("Couldn't open the share sheet")` on failure. The success needs no
  pill, because the system sheet opens. Use a light haptic. Extend the id test.
- `blocked_ui.rs`: a header row above the list with two glass pills, "Share" (`set-bk-share`) and
  "Unblock all" (`set-bk-unall`, destructive tint). They are hidden when the list is empty. Share sends
  `blocklist::list_text` through `share_text`. Unblock all opens a confirm sheet: add
  `SheetKind::UnblockAll` and copy the `ClearSpeedDial` sheet (`speed_dial_ui::speed_dial_clear_sheet`)
  with `unblock_all_text(n)`. On Yes it runs `backend` unblock for each row on the background
  executor, the same way `unblock_pill`'s handler does, then refreshes the list. The pill reads
  "Unblocked n numbers" with Undo `PillAction::BlockAll(Arc<[SharedString]>)`, which blocks each one
  again and shows the pill "Blocked n numbers". Route `set-bk-share`/`set-bk-unall` through the
  `Sub::Blocked` prefix handler.
- Tests: the `PillAction::BlockAll` label/ids; the confirm copy comes from core; `sheet-dt-share` in the ids.

### S4. UI: Stats CSV export (U28)
- `stats_ui.rs`: an "Export CSV" pill (`set-st-csv`) at the end of the range segment row, or under the
  top-people list if the segment row has no room (keep to the existing layout recipes). It builds
  `call_stats::people_csv(&top_people(calls, range.since_ms(..), usize::MAX or the existing cap), range)`
  and calls `backend::export_calls(&stats_csv_file_name(..), &csv)`. Copy the result handling of
  `backup_ui::backup_export_calls` (the document-picker result event), with the pills "Exported n
  people" and "No calls in this range". The id must not collide with `SEGMENT_PREFIX`/`PERSON_PREFIX`,
  and `settings_ui.rs` routes `set-st-` already.
- Tests: the id is not under the segment or person prefixes; the empty-range pill text helper.

### S5. UI: Recents sticky day capsule (U15)
- `recents.rs` (app): over the top of the list, under the search/filter row, draw a small flat-glass
  capsule (the `flat_glass` recipe, not refractive, 24 px high, caption font) with
  `section_for(rows, visible, first_visible_index, now, tz)`. `first_visible_index` = `(scrolled /
  ROW_H).floor()`, using the same `scrolled()` that `row_at` uses. When the next `section_starts` entry
  is within one capsule height of the top, the capsule slides up by the overlap (a push-up handoff)
  and then swaps its label. Row geometry stays 58 px, so `row_at` is unchanged. Hide the capsule when
  `visible` is empty, when scrolled is under 4 px (the first row's own time is enough), and in select
  mode. It fades in and out with the existing `Fade` helper, and with reduced motion it snaps.
  `aria_label` = the label. The capsule has no hit target: pointer events pass through to the list.
- Cache the label per (first index, rebuild rev) so a frame does no string work unless the index changes.
- Tests: a pure helper `fn capsule_offset(first_visible_px: f32, next_start_px: Option<f32>, h: f32) ->
  f32` with its edge cases. `perf_harness` must still pass. If the harness counts elements per
  frame, bump the expectation only by the capsule's one element.

### S6. UI: Notes page (U30, U33)
- `settings_ui.rs`: `Sub::Notes` (prefix `set-nt-`, title "Call notes", back id `set-nt-back`, added to
  the back-id match and the prefix test). A row "Call notes" with the count as `Control::Info` in the
  Data section near Call statistics opens it.
- New `apps/phone/src/notes_ui.rs`: a `SearchField` (`set-nt-field`, hint "Search notes") and a list
  of `Notes::rows(query, name_of)`. Each row has a title (the name or grouped number), a subtitle (the
  date via `when_text` through the core `CallDetails` path, then the `preview`) and a trailing delete
  glyph (`set-nt-del-{i}`). A row tap (`set-nt-row-{i}`) opens the Call details sheet for that
  `(number, at_ms)`, the same way `history_section` opens it. Find that entry point in
  `details_ui.rs` and reuse it. Delete removes the note through the `NotesStore` and shows the pill
  "Note deleted" with Undo (`PillAction::RestoreNote`, which exists). A header pill "Share all"
  (`set-nt-share`, hidden when empty) sends `Notes::all_text` through `share_text`. The empty state
  reads "No call notes yet. Add one from a call's details." Notes change -> the list rebuilds (hook
  the same notify path that `recents` uses for notes search).
- Tests: the prefix and back id; the ids; the empty-state text constant.

### S7. UI: redial, smart paste chooser, bulk block (U31, U32, U34)
- `keypad.rs` `call_press_on`: when `self.dialed` is empty and `account.is_none()`, take
  `dial::redial_target(&log.source)` (the loaded call log, `backend::Load::Ready`). If there is one,
  fill the field (with the same digit-pop and delete-shown animation `paste_press` uses), set the status
  "Tap Call again to redial", and use a light haptic. If there is none, keep `invalid()` with the status
  "No recent calls to redial". It never places the call on the first tap.
- `keypad.rs` `paste_press` (and the field menu's Paste, which reuses it): switch on `dial::paste_plan`.
  `One` keeps today's behaviour. `Choose(list)` opens a new `SheetKind::PasteChoose` (copy the
  `QuickReplyPick` sheet from `person_ui.rs`: the title "Which number?", one glass row per number
  shown in display format, ids `sheet-pc-{i}`). Choosing one fills the field with the pill "Pasted".
  `Nothing` keeps "Nothing to paste".
- `blocked_ui.rs` add field: on Add, run `blocklist::parse_many`. With one number, keep the current
  path. With more, block them all on the background executor, then show the pill `bulk_text(..)` with
  Undo `PillAction::Unblock`-for-all, which is a new `PillAction::UnblockAll(Arc<[SharedString]>)`.
  It mirrors S3's `BlockAll`. An emergency-only paste keeps the current inline refusal text.
- Tests: the empty-field redial decision as a pure helper `fn redial_decision(dialed: &str, target:
  Option<&str>) -> Redial { Call, Fill(String), Nothing }`; the paste sheet ids; `UnblockAll` labels.

### S8. Feedback audit, docs, backlog (U23)
- Audit every utility U1-U34 for a pill on success and on failure, a haptic on the action
  (`HapticFeedback::Confirm` for completions, the light tick for toggles, as the existing utilities
  use), a confirm or Undo on destructive actions, and the desktop `Unsupported` wording through
  `Outcome::why_not`. Fix the gaps you find in place, keeping the changes small. List each finding in
  `docs/phone-review-notes.md` under "Utilities feedback audit (R4)" (utility, gap, fix).
- `docs/phone-acceptance.md`: add manual checks for U15, U26-U28, U30-U34.
- This file: the R4 rows become `done R4`. Leave U35-U39 as `backlog R5`.
- Final: run the full check list above, then confirm `git status` is clean apart from the commit.
