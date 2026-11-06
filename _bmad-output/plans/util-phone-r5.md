# util-phone: everyday utilities for Rovsti Phone (round 5)

baseSha: 835bcba4e24d1ff38bccdcf8c209fe3b8f223346

## Audit (what exists at baseSha)

Round 4 (e7b7fad..835bcba) shipped every R4 row: day capsule, Call details Share, Blocked Share /
Unblock all / bulk block, Stats CSV, Notes page with Share all, redial, smart paste chooser, and the
feedback audit. `git stash list` is empty. `df` showed 6.5 GB free (close to the 6 GB line: run
`rm -rf target/debug/incremental` first whenever it drops below).

The R5 backlog rows, checked by grep at baseSha:
- U35 (filter chip "With notes"): open. `call_log_query::RecentsFilter` has 5 variants (`ALL`, `label`,
  `keeps`), the chip bar in `recents.rs` (~line 1836, `rc-chip-{label}`, `flex_1` chips) and
  `apply_with_notes` already join notes. `Notes::matching` returns `None` for an empty folded text, so
  there is no "has any note" test yet.
- U36 (contact: copy all numbers / share as text): open. `ContactCard::share_text` (name + `Label:
  number` lines) exists in `contact_card.rs` but nothing in `apps/phone` calls it. On a multi-number
  contact, Copy / Share open the "Copy which number?" chooser (`ContactSheet { chooser, pending:
  Option<StripAction> }`, rows `sheet-cn-{i}` from `number_row`), which offers one number at a time.
- U37 (after-call summary): open, reworked. `incall.rs` (~line 1513) only offers "Block" after a
  short incoming call (`offer_block_after_call`). After any other connected call nothing is offered.
  The in-call note stores under `note_call_ms(view)`, so a note can be added after the call by number
  and time, and `notes_ui::notes_open_row` already finds the logged call by
  `history_for(..)` + `call_notes::MATCH_WINDOW_MS` and opens Call details.
- U38 (Blocked export/import as a file): open. No new Kotlin glue is needed. Export:
  `backend::export_calls(name, csv)` saves any CSV. Import: `rovsti_android::documents::import_file
  (max_bytes)` copies the file into the cache and answers `document_result` op `ImportMessages` with
  `path`. Phone ignores that op today (`backup_ui::sync_documents` `_ => {}`), so it is free in Phone,
  guarded by a pending flag.
- U39 (mark missed as seen): done, pre-existing. `recents.rs` marks missed calls seen after
  `VIEW_DELAY` through `backend.mark_missed_seen` -> `providers::mark_missed_calls_read`.

New gaps found in this audit:
- Wrong pill (a bug): the Stats CSV export (`stats_ui::stats_export_csv`) goes through `export_calls`,
  so its `document_result` (op `ExportCalls`) shows `export_calls_pill` = "Call history exported"
  after the user saved a statistics file. Every new CSV export (blocked list, notes) would repeat it.
- The Stats page cannot be shared as text. Only one person's stats can (`call_stats::share_text`).
- Notes can be shared as text but not saved as a file (CSV).
- There is no way to clear old calls. Settings > Data has only "Delete all call history".

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
| U31 | Redial on an empty keypad | done R4 |
| U32 | Smart paste "Which number?" chooser | done R4 |
| U26 | Call details Share | done R4 |
| U27 | Blocked page Share list, Unblock all with confirm and Undo | done R4 |
| U30 | Notes page (Settings > Call notes) | done R4 |
| U33 | Notes page Share all | done R4 |
| U15 | Recents sticky day capsule | done R4 |
| U28 | Stats page Export CSV | done R4 |
| U34 | Blocked page bulk block from a pasted list | done R4 |
| U23 | Haptic and pill feedback audit | done R4 |
| U39 | Mark missed calls as seen | done (pre-existing: auto after `VIEW_DELAY`) |
| U35 | Recents filter chip "Notes" (calls with a note) | R5 S3 |
| U40 | Stats page "Share" summary text for the chosen range | R5 S3 |
| U36 | Contact chooser "All numbers": copy every number / share the contact as text | R5 S4 |
| U37 | After a connected call: pill "Call with Anna, 4:32" with "Add note" | R5 S5 |
| U41 | Export pills name what was exported (fixes "Call history exported" after a stats export) | R5 S6 |
| U38 | Blocked page Export CSV and Import from a file (with Undo) | R5 S6 |
| U42 | Notes page Export CSV | R5 S6 |
| U43 | Settings > Data "Delete older calls" (30 days / 90 days / 1 year), confirm and Undo | R5 S7 |
| U44 | Call details: "Remind me" entry (reuse `remind_ui`) when the call was missed | backlog R6 |
| U45 | Recents select mode: "Block selected" and "Share numbers" | backlog R6 |
| U46 | Stats page: tap a day bar to list that day's calls | backlog R6 (check chart first) |
| U47 | Keypad: long-press Call opens "Call with SIM…" / WhatsApp choice when not already | backlog R6 (check `sim_ui` first) |

## Steps (round 5)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free, run `rm -rf target/debug/incremental` first. Make one focused commit per step
(`Rovsti phone: ...`). Read `.claude/skills/liquid-glass-gpui/SKILL.md` before S3-S7. Never invent GPUI
APIs: copy patterns that already exist in `apps/phone/src` (`Sub` pages and `prefix()`, `SheetKind` sheets
such as `RemindPick`/`PasteChoose`/`ConfirmDelete`, `presses`, `show_notice_pill`/`show_pill_action`,
`PillAction`, `rovsti_ui::haptics::perform`). Every utility gets a pill, every destructive one a confirm
and/or Undo, desktop failures go through `Outcome::why_not`. No step adds Android or Kotlin glue.

### S1. Pure: notes filter, contact numbers text, stats summary, older calls (`rovsti_core`)
- `call_notes.rs`: `impl Notes { pub fn has_note(&self, number: &str, first_ms: i64, last_ms: i64) ->
  bool }`: a note for that number (`PhoneNumber::matches`) inside `first_ms - MATCH_WINDOW_MS ..=
  last_ms + MATCH_WINDOW_MS`, any text. Share the window/number test with `matching` through one
  private helper (no duplicated maths).
- `call_log_query.rs`: add `RecentsFilter::WithNotes` (label `"Notes"`, last in `ALL`, so `ALL.len()
  == 6`). `keeps(WithNotes)` is `false` (plain `apply` has no notes). `apply_with_notes` keeps a
  `WithNotes` row when `notes.has_note(&r.number_raw, r.first_ms, r.last_ms)` and the text matches
  (name/number or note text, as today). `empty_text`: `"No calls with notes"`. Update the
  `ALL.len()` test and the loop test at ~line 719. If `RecentsFilter` is persisted anywhere (grep),
  keep the stored form backward compatible.
- `contacts_view.rs`: `pub fn numbers_text(numbers: &[NumberVm]) -> String` (`"Mobile: +90 532 111 22
  33\nWork: +44 20 7946 0958"`, empty for none) and `pub fn contact_text(name: &str, numbers:
  &[NumberVm]) -> String` (name, newline, `numbers_text`; just the name when there are no numbers).
  Make `ContactCard::share_text` call `contact_text` (its output must not change; keep its test).
  `pub fn copied_numbers_text(n: usize) -> String`: `"Copied number"` / `"Copied 3 numbers"`.
- `call_stats.rs`: `pub fn range_share_text(people: &[PersonStat], range: Range) -> String`:
  `"Call statistics, this week\n12 calls · 1 h 4 min\nAnna Smith: 5 calls · 32 min\n..."`. The first
  line uses `range.label()` in lower case after the comma, the second sums `Totals` over `people`
  through `summary_line`, then one line per person (name, else the grouped number) for the first 10.
  Empty string when `people` is empty or every total is 0.
- `call_log_query.rs`: `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum PruneAge { Days30,
  Days90, Year }` with `pub const ALL: [Self; 3]`, `pub fn label(self) -> &'static str` (`"Older than
  30 days"`, `"Older than 90 days"`, `"Older than 1 year"`), `pub fn cutoff_ms(self, now_ms: i64) ->
  i64` (now minus 30/90/365 days). `pub fn ids_older_than(entries: &[CallLogEntry], extras:
  &[ExtraCall], cutoff_ms: i64) -> (Vec<i64>, Vec<String>)` (log `id` where `date_ms < cutoff_ms`,
  WhatsApp `call_id` where `started_ms < cutoff_ms`). `pub fn prune_text(age: PruneAge, count: usize)
  -> (String, String)`: `("Delete calls older than 30 days?", "42 calls will be removed from this
  phone's call log.")`, singular for 1. `pub fn prune_none_text(age) -> String`: `"No calls older than
  30 days"`.
- Tests (in-module): `has_note` inside/outside the window, national vs international number, other
  number; `WithNotes` with and without text, `apply` (no notes) gives nothing for `WithNotes`,
  `empty_text`; `numbers_text` / `contact_text` empty and two numbers, `share_text` unchanged;
  `copied_numbers_text` 1 and 3; `range_share_text` empty, cap of 10, unnamed person shows the
  number; `cutoff_ms` per age; `ids_older_than` boundary (`date_ms == cutoff` is kept) with log and
  extras; `prune_text` singular and plural.

### S2. Pure: blocked list file, notes CSV, after-call text (`rovsti_core`)
- `blocklist.rs`: `pub const CSV_HEADER: &str = "number,name"`; `pub fn to_csv(rows: &[BlockedNumber],
  name_of: impl Fn(&str) -> Option<String>) -> String` (header + one CRLF row per number in list
  order, the raw number and the name, both through `call_log_query::cell`). `pub fn csv_file_name
  (now_ms: i64, tz_offset_s: i32) -> String` gives `rovsti-blocked-numbers-2026-11-06.csv` (reuse the
  date helper `csv_file_name` in `call_log_query` uses; make it `pub(crate)` if private).
- `blocklist.rs`: `pub const IMPORT_MAX: usize = 2000`; `#[derive(Clone, Debug, Default, PartialEq,
  Eq)] pub struct FileImport { pub bulk: BulkParse, pub skipped_lines: usize, pub truncated: bool }`;
  `pub fn parse_file(text: &str, emergency: &EmergencyList, already: &BlockSet) -> FileImport`. Line
  based (`\r\n` or `\n`): skip blank lines, lines starting with `#`, and a first line that starts
  with `number` (our header). Take the first CSV field (strip surrounding quotes and a leading `'`
  that `cell` adds as an injection guard), then `parse_input`. Emergency -> `skipped_emergency`,
  already on the list or repeated in the file (by `PhoneNumber::matches`) -> `already_blocked`, any
  other failure -> `skipped_lines`. Stop after `IMPORT_MAX` numbers to block and set `truncated`.
  So the file `to_csv` writes reads back to the same numbers (round trip test).
  `pub fn import_text(f: &FileImport) -> String`: `"Blocked 12 numbers"`, `"Blocked 12 numbers, 3
  already blocked, 1 line skipped"`, `"No new numbers to block"` when `to_block` is empty. Reuse
  `bulk_text` for the first part.
- `call_notes.rs`: `impl Notes { pub fn to_csv(&self, name_of: impl Fn(&str) -> Option<String>,
  tz_offset_s: i32) -> String }` with header `date,time,name,number,note`, newest first, cells
  through `call_log_query::cell`, date/time the way `call_log_query::to_csv` writes them (reuse its
  helper). `pub fn notes_csv_file_name(now_ms, tz) -> String` -> `rovsti-call-notes-2026-11-06.csv`.
- `call.rs`: `pub fn after_call_text(who: &str, talk_s: u64) -> String` -> `"Call with Anna Smith,
  4:32"` through `call_log::format_duration`; just `"Call ended, 4:32"` for an empty `who`.
- Tests: `to_csv` header only for no rows, a name with a comma, a number that starts with `+` or `=`
  (guard); `parse_file` header skip, comments, quoted cells, emergency, repeats, junk lines, the cap
  (2001 numbers -> 2000 + `truncated`), the `to_csv` round trip; `import_text` variants; notes CSV
  order/escaping/empty; file names; `after_call_text` with and without who.

### S3. UI: Recents "Notes" chip and Stats "Share" (U35, U40)
- `recents.rs`: the chip bar maps `RecentsFilter::ALL`, so the sixth chip appears by itself. Check it
  still fits on a 360 dp phone at 12 px (`flex_1`, `min_w_0`, `overflow_hidden` are already there).
  If "WhatsApp" now clips, lower the chip font to 11.5 px only for this bar, nothing else. The chip
  id is `rc-chip-Notes`. When notes change (the same notify path notes search uses) and the filter is
  `WithNotes`, the visible list rebuilds. The empty state comes from `empty_text`. `main.rs:889`
  (reset to `All`) needs no change. `perf_harness` must still pass.
- `stats_ui.rs`: a "Share" pill `set-st-share` next to "Export CSV" (`CSV_ID`), same recipe, share
  icon as `NUMBER_SHARE` uses. It sends `call_stats::range_share_text(&people, range)` through
  `backend::share_text`. Empty text -> pill `"No calls in this range"` (reuse `export_pill_text(0)` if
  it says that). Failure -> `outcome.why_not("Couldn't open the share sheet")`. Light haptic
  (`HapticFeedback::Selection`). Route it in the `set-st-` handler (~line 286). The id must not fall
  under `SEGMENT_PREFIX`/`PERSON_PREFIX`.
- Tests: `rc-chip-Notes` label; `set-st-share` is not under the segment/person prefixes.

### S4. UI: contact chooser "All numbers" (U36)
- `contacts.rs`: when the sheet is a chooser with `pending == Some(StripAction::Copy)` or
  `Some(StripAction::Share)` and the contact has at least 2 numbers, add one more row under the
  numbers block, `const ALL_NUMBERS_ID: &str = "sheet-cn-all"`, title `"All numbers"`, subtitle the
  count (`"3 numbers"`), the same row recipe as `number_row` (no WhatsApp button). Make sure the
  `sheet-cn-` index parser (~line 1349) does not treat `all` as an index (it parses a number, so it
  returns `None`; add a test) and route `ALL_NUMBERS_ID` before it.
- Copy: `backend::copy_text(&contacts_view::numbers_text(&numbers))` (fallback
  `cx.write_to_clipboard` as `copy_as_act` does), pill `copied_numbers_text(n)`, `Confirm` haptic,
  close the sheet. Share: `backend::share_text(&contacts_view::contact_text(&name, &numbers))`,
  failure pill through `why_not`, close the sheet.
- Tests: the id and parser; that the row shows only for Copy/Share with 2+ numbers, via a small pure
  helper `fn all_numbers_row(pending: Option<StripAction>, count: usize) -> bool` in `actions_ui.rs`.

### S5. UI: after-call "Add note" pill (U37)
- `incall.rs`: a pure helper `pub(crate) fn after_call_note(view: &CallView, began_locked: bool,
  now_ms: i64) -> Option<AfterCall>` with `pub(crate) struct AfterCall { number: SharedString, at_ms:
  i64, who: SharedString, talk_s: u64 }`. `Some` only when `connect_time_ms > 0`, the call is a
  telecom call (`source.telecom_id().is_some()`), `callable_number(&view.number)` is `Some`, not
  `began_locked`, and the block offer does not apply (factor the existing `offer` predicate at
  ~line 1514 into `pub(crate) fn offers_block(view, began_locked, now_ms) -> bool` and use it in both
  places). `at_ms = note_call_ms(view)`, `talk_s = (now_ms - connect_time_ms) / 1000`.
- In the end-of-call path (~line 1513, where `offer` is computed): when `after_call_note` is `Some`
  and the call has no note yet (`self.notes.get(&number, at_ms).is_none()`), show
  `show_pill_action(call::after_call_text(&who, talk_s), "Add note", PillAction::AddNote { number,
  at_ms, who }, cx)`. If the block offer fires, nothing else is shown (one pill only).
- `block_ui.rs`: `PillAction::AddNote { number: SharedString, at_ms: i64, who: SharedString }`. In
  `pill_fired`: find the logged call like `notes_ui::notes_open_row` does (factor that lookup into
  `pub(crate) fn logged_call_near(&self, number: &str, at_ms: i64, cx) -> Option<HistoryVm>` in
  `details_ui.rs` and use it in both places), then `open_details_for(&vm, Some(who),
  SheetKind::CallDetails, true, window, cx)`, `self.sheet.open()`, and start editing the note the
  way the `NOTE` action in `details_act` does (call the same code, do not copy it). When the log
  has not caught up, show `"The call isn't in Recents yet. Try again in a moment"`.
- Tests: `after_call_note` for a connected outgoing call (Some), never connected, locked, private
  number, a short incoming call (None, the block offer wins); `offers_block` unchanged behaviour;
  the `AddNote` pill label.

### S6. UI: export pills, Blocked export/import, Notes CSV (U41, U38, U42)
- `backup_ui.rs`: `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)] pub(crate) enum ExportKind
  { #[default] Calls, Stats, Blocked, Notes }` and a field `export_kind` on `BackupUi`. Change
  `export_calls_pill(kind, ok, failed)`: ok -> `"Call history exported"` / `"Call statistics
  exported"` / `"Blocked numbers exported"` / `"Call notes exported"`; failed -> `"Couldn't export"`;
  cancelled -> `"Export cancelled"`. Every `backend::export_calls` caller sets the kind first: the call
  log (`backup_export_calls`), per-person CSV (`person_ui`, `Calls`), Stats (`stats_ui`, `Stats`).
  In `stats_ui::stats_export_csv` drop the early "Exported n people" pill on success (the result pill
  now says it) or keep it and skip the result pill for `Stats`; pick one so the user sees one pill.
- `blocked_ui.rs`: two more header pills beside Share and Unblock all, `set-bk-export` ("Export",
  hidden when the list is empty) and `set-bk-import` ("Import", always shown). If the header row has
  no room, move Export and Import into a second row with the same recipe.
  Export: `ExportKind::Blocked`, `backend::export_calls(&blocklist::csv_file_name(..),
  &blocklist::to_csv(rows, name_of))`. Import: set `self.blocked.import_pending = true`, call
  `rovsti_android::documents::import_file(256 * 1024)` through a new thin `backend::import_file`
  (`Outcome::from(.., "importing")`), failure pill `why_not("Couldn't open the file picker")`.
  `backup_ui::sync_documents`: `DocumentOp::ImportMessages` when `import_pending` (clear it): a
  cancel shows nothing, an error shows "Couldn't read the file", ok reads `result.path` with
  `std::fs::read_to_string` on the background executor, runs `blocklist::parse_file`, blocks
  `to_block` through the R4 bulk-block code in `blocked_ui.rs` (factor it into a function and reuse it, do not copy it), then the
  pill `import_text(..)` with Undo `PillAction::UnblockAll`, and removes the cached file. Not pending:
  ignore it as today.
- `notes_ui.rs`: an "Export" pill `set-nt-export` beside "Share all" (hidden when empty),
  `ExportKind::Notes`, `backend::export_calls(&call_notes::notes_csv_file_name(..),
  &self.notes.snapshot().to_csv(name_of, tz))`.
- Tests: `export_calls_pill` per kind and result; the new ids are routed by `Sub::Blocked` /
  `Sub::Notes` prefixes and do not collide with `set-bk-del-`/`set-nt-del-` style prefixes.

### S7. UI: Settings > Data "Delete older calls" (U43)
- `settings_ui.rs`: `RowId::DeleteOlder` (`"set-delete-older"`, `"Delete older calls"`,
  `Control::Danger`), right above "Delete all call history". Update the Data section row test
  (indices shift by one).
- Tap opens `SheetKind::PruneAge` (copy the `RemindPick` chooser recipe in `remind_ui.rs`): title
  "Delete older calls", one row per `PruneAge::ALL` (`sheet-pa-{i}`), each with the count it would
  remove as the subtitle (`ids_older_than` over the loaded log and `wa_call.recents_extras()`), and
  Cancel. A row with 0 calls is dimmed and shows `prune_none_text`.
- Choosing a row: `history_ui::DeleteScope::Older { age: PruneAge, cutoff_ms: i64 }` through
  `open_confirm_delete`. `confirm_text` uses `prune_text`; `scope_ids` uses `ids_older_than`. On Yes
  call `delete_call_ids(ids, wa_ids, ..)`, which shows "n calls deleted" with Undo. If the count is
  over `UNDO_CAP`, add "This can't be undone." to the body and show the plain pill, as Delete all
  does (factor the cap check, do not duplicate it).
- Tests: the new row id and order; `DeleteScope::Older` confirm text; the sheet row ids.

### S8. Feedback audit, docs, backlog
- Audit U35-U43 for a pill on success and failure, haptics, confirm/Undo on destructive actions and
  the desktop `Unsupported` wording. Fix gaps in place. Add the findings to
  `docs/phone-review-notes.md` under "Utilities feedback audit (R5)".
- `docs/phone-acceptance.md`: manual checks for U35-U43 (including: export the block list, clear it
  with Unblock all, import the file back, and see every number again; stats export now says
  "Call statistics exported").
- This file: mark the R5 rows `done R5`, keep U44-U47 as `backlog R6`.
- Final: run the full check list, then confirm `git status` is clean apart from the commit.
