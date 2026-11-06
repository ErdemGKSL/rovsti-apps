# util-phone: everyday utilities for Rovsti Phone (round 6)

baseSha: 191b3bc88af97859cc51801258d7417ac1ee1ca9

## Audit (what exists at baseSha)

Round 5 (b1555f5..191b3bc) shipped every R5 row (U35-U43) and the R5 feedback audit. `git stash list`
is empty. `df` showed 7.2 GB free (run `rm -rf target/debug/incremental` first whenever it drops below
6 GB).

The R6 backlog rows, checked by grep at baseSha:
- U44 (Call details "Remind me"): open. `details_ui.rs` has Back / Copy / Share / Note / Done /
  Delete note / Delete call (`sheet-dt-*`), nothing for reminders. The Number sheet already has
  `remind_ui::NUMBER_REMIND` (`sheet-n-remind`, routed in `recents.rs` ~line 2302) and
  `open_remind_pick(number, name, missed, cx)` swaps the open sheet to `SheetKind::RemindPick`.
  `remind_caption` / `remind_cancel` exist.
- U45 (Recents select mode "Block selected" / "Share numbers"): open. `select_bar` (`recents.rs`
  ~line 1198) holds the title and one Delete button (`SEL_DEL`). `Selection` has `refs`,
  `call_count`, no number list. The Blocked page's `bulk_block_run(many, text_of, cx)`
  (`blocked_ui.rs` ~line 743) blocks a `BulkParse` and shows Undo `PillAction::UnblockAll`.
- U46 (Stats: tap a day bar): open. `stats_ui::bar_chart` draws `st-bar-{i}` cells with an aria
  label but no handler. `DayBar { day_start_ms, label, talk_s, calls }` has no end, and the
  `Weeks8` range draws week buckets (`last_weeks`), so a bar is a day or a week.
- U47 (Keypad long-press Call: SIM / WhatsApp choice): done, pre-existing. `HoldAction::SimChooser`
  -> `sim_long_press` opens the SIM chooser on hold, and the keypad shows the `wa-call` WhatsApp chip
  whenever WhatsApp is connected.

New gaps found in this audit:
- An unknown international number shows only digits. Nothing in `rovsti_core` maps a calling code
  to a country (`number_format` only knows `infer_home_cc` / `clean_cc`), so a "+49 ..." caller is
  not labelled "Germany" anywhere (Recents, Number sheet, Call details, Incoming).
- The in-call keypad sends one tone per tap (`ik-{d}` -> `CallCommand::Dtmf` + `DtmfStop`). A
  conference PIN or an extension copied from a message must be typed by hand; nothing pastes tones.
- The in-call digit line (`incall.digits`, `digit_line`) cannot be copied (for example a reference
  number typed into a hotline menu).

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
| U35 | Recents filter chip "Notes" (calls with a note) | done R5 |
| U40 | Stats page "Share" summary text for the chosen range | done R5 |
| U36 | Contact chooser "All numbers": copy every number / share the contact as text | done R5 |
| U37 | After a connected call: pill "Call with Anna, 4:32" with "Add note" | done R5 |
| U41 | Export pills name what was exported | done R5 |
| U38 | Blocked page Export CSV and Import from a file (with Undo) | done R5 |
| U42 | Notes page Export CSV | done R5 |
| U43 | Settings > Data "Delete older calls", confirm and Undo | done R5 |
| U47 | Keypad long-press Call: SIM choice / WhatsApp | done (pre-existing `SimChooser`, `wa-call` chip) |
| U48 | Country of an unknown international number (Recents row, Number sheet, Call details, Incoming) | R6 (S1, S3, S7) |
| U49 | In-call keypad "Paste": send a copied PIN/extension as tones, with Stop | R6 (S1, S6) |
| U45 | Recents select mode: "Block" and "Share" for the chosen rows (confirm + Undo for Block) | R6 (S2, S4) |
| U46 | Stats page: tap a bar to list that day's (or week's) calls | R6 (S2, S5) |
| U44 | Call details: "Remind me" / "Cancel reminder" on a missed call | R6 (S3) |
| U50 | In-call keypad: long-press the digit line to copy the typed tones | R6 (S6) |
| U51 | Remember tones per number: after a paste on a number, offer "Send 4821#" next time it connects | backlog R7 |
| U52 | Stats page "Records" card: longest call, busiest day, most called this range | backlog R7 |
| U53 | Keypad field menu gains "Share" and "Copy as +E.164" for the typed number | backlog R7 |
| U54 | Settings > Data "Export contacts (.vcf)" through the existing `documents::export_contacts` | backlog R7 |
| U55 | Block a number range by prefix (for example `+90 850*`) | backlog R7 (check Kotlin call screening first; needs glue) |
| U56 | Recents row menu "Copy country / Copy number with country" | dropped (U48 shows it; U4 copies E.164) |

## Steps (round 6)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free, run `rm -rf target/debug/incremental` first. Make one focused commit per step
(`Rovsti phone: ...`). Read `.claude/skills/liquid-glass-gpui/SKILL.md` before S3-S7. Never invent GPUI
APIs: copy patterns that already exist in `apps/phone/src` (`SheetKind` sheets such as `RemindPick` /
`PruneAge` / `ConfirmDelete`, `presses`, `touch_surface`, `show_notice_pill` / `show_pill_action`,
`PillAction`, `rovsti_ui::haptics::perform`, `cx.background_executor().timer(..)` as in `blocked_ui.rs`
~line 361, `cx.read_from_clipboard()` as in `keypad.rs` ~line 775). Every utility gets a pill, every
destructive one a confirm and/or Undo, desktop failures go through `Outcome::why_not`. No step adds
Android or Kotlin glue (DTMF uses the existing `calls::dtmf` / `calls::dtmf_stop`).

### S1. Pure: calling codes and the tone plan (`rovsti_core`)
- New module `crates/rovsti_core/src/calling_code.rs` (add `pub mod calling_code;` in `lib.rs`).
  `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub struct Country { pub cc: &'static str, pub name:
  &'static str }`. A sorted `const COUNTRIES: &[(&str, &str)]` table of ITU calling codes to an
  English short name, covering at least every 2-digit code and the common 3-digit ones (Europe,
  Middle East, Africa, Asia, Latin America; `"1"` -> `"US / Canada"`, `"7"` -> `"Russia / Kazakhstan"`,
  `"44"` -> `"United Kingdom"`, `"90"` -> `"Turkey"`, `"49"` -> `"Germany"`, `"971"` -> `"United Arab
  Emirates"`, `"380"` -> `"Ukraine"`). No data files, no crate dependency.
  `pub fn country_of_cc(cc: &str) -> Option<Country>` (exact lookup, binary search).
  `pub fn country_of(raw: &str) -> Option<Country>`: only for a `PhoneNumber::parse` that
  `is_international()`; longest-prefix match over 3, 2, 1 leading digits; `None` for a short code,
  a sender id or a national number.
  `pub fn foreign_country(raw: &str, home_cc: Option<&str>) -> Option<Country>`: `country_of`, but
  `None` when the country's cc equals `home_cc` (a number from home is not labelled).
  `pub fn country_line(raw: &str, home_cc: Option<&str>) -> Option<String>`: `"Germany"` (the
  caption text the UI shows; keep it as a function so wording stays in one place).
- `dial.rs`: `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum ToneStep { Tone(char), Pause }`
  and `#[derive(Clone, Debug, Default, PartialEq, Eq)] pub struct TonePlan { pub steps:
  Vec<ToneStep>, pub tones: usize, pub truncated: bool }`. `pub const TONE_MAX: usize = 40`,
  `pub const TONE_ON_MS: u64 = 160`, `pub const TONE_GAP_MS: u64 = 90`, `pub const PAUSE_MS: u64 =
  2000`. `pub fn tone_plan(text: &str) -> TonePlan`: keeps `0-9 * #` as `Tone`, `,` / `;` / `p` /
  `w` (any case) as `Pause` (no two pauses in a row, none leading or trailing), drops spaces,
  dashes, dots, brackets and everything else; a `+` is dropped. Stops after `TONE_MAX` tones and
  sets `truncated`. `pub fn tones_text(plan: &TonePlan) -> String`: `"Sent 8 tones"`, `"Sent 1
  tone"`, `"Sent the first 40 tones"` when truncated. `pub fn tone_preview(plan: &TonePlan) ->
  String`: the plan as typed text (`"4821#,12"`) for the button's aria label and the confirm line.
  `pub fn paste_tones_label(clip: Option<&str>) -> Option<String>`: `Some("Paste 4821#")` when the
  clipboard plan has at least one tone (the preview cut to 12 chars with `…`), else `None`.
- Tests (in-module): `country_of` for `+49 30 1234567`, `+1 212 ...`, `+971 ...` (the 3-digit
  code wins over any shorter prefix), `+380 ...`, unknown code `+999 ...`
  -> `None`, national `0532...` -> `None`, short code `112` -> `None`; `foreign_country` hides the
  home cc and shows another; `COUNTRIES` is sorted and has no duplicate codes (a test walks it);
  `tone_plan` for `"PIN: 4821 #"`, `"123,,456"` (one pause), `",12;"` (no leading/trailing pause),
  `"+90 (532) 111-22-33"` (digits only), letters only (empty plan), 41 digits (40 + `truncated`);
  `tones_text` singular, plural, truncated; `paste_tones_label` with `None`, junk, a long PIN.

### S2. Pure: selection numbers and the stats bucket list (`rovsti_core`)
- `call_log_query.rs`: `impl Selection { pub fn numbers(&self, rows: &[RecentsRow]) ->
  Vec<String> }`: the `number_raw` of each selected, `callable` row in row order, without repeats
  (by `PhoneNumber::matches`; a row whose number does not parse is compared as trimmed text).
  `pub fn select_share_text(rows: &[RecentsRow], sel: &Selection, name_of: impl Fn(&str) ->
  Option<String>) -> String`: one line per number, `"Anna Smith: +90 532 111 22 33"` or just the
  grouped number (`call::format_number` when it parses). Empty when nothing callable is selected.
  `pub fn select_block_text(n: usize) -> (String, String)`: `("Block 3 numbers?", "Calls and
  messages from them will be blocked on this phone.")`, singular `"Block this number?"`.
  `pub fn select_nothing_callable_text() -> &'static str`: `"No numbers to block or share"`.
- `call_stats.rs`: add `pub end_ms: i64` to `DayBar`, filled in `buckets` as the next bucket's
  start (`((start + span) * DAY_S - tz) * 1000`). Fix every `DayBar { .. }` literal (grep tests and
  `apps/phone`). `pub fn calls_in(calls: &[StatCall], start_ms: i64, end_ms: i64) -> Vec<StatCall>`
  (`start <= date < end`, newest first, at most 200). `pub fn bucket_title(bar: &DayBar, range:
  Range, tz_offset_s: i32) -> String`: a day bar -> `"Tue 3 Nov"`, a `Weeks8` bar -> `"Week of 2
  Nov"` (reuse the `WEEKDAYS` / `MONTHS` and `civil_from_days` helpers already in the file).
  `pub fn bucket_summary(calls: &[StatCall]) -> String`: `summary_line` over their `Totals`
  (`"4 calls · 12 min"`); `"No calls"` for none. `pub fn call_line(c: &StatCall, tz_offset_s: i32,
  hour12: bool) -> (String, String)`: title = name, else the grouped number; subtitle = `"14:32 ·
  Outgoing · 4:32"` (`call_log::clock_text`, `"Incoming"` / `"Outgoing"` / `"Missed"`, a missed
  call has no duration, `" · WhatsApp"` appended for WhatsApp calls; duration through
  `call_log::format_duration`).
- Tests: `numbers` skips private rows and repeats (`+905321112233` vs `05321112233` with the same
  normalized form), keeps row order; `select_share_text` named / unnamed / empty;
  `select_block_text` 1 and 3; `DayBar::end_ms` equals the next bar's start for days and weeks, and
  the last bar ends one span after its start; `calls_in` boundaries (`date == end` excluded,
  `date == start` kept), order and the 200 cap; `bucket_title` for a day and a week; `call_line`
  for a missed WhatsApp call and an outgoing call in 12 h and 24 h clocks.

### S3. UI: Call details "Remind me" and the country caption (U44, U48 part 1)
- `details_ui.rs`: `const REMIND: &str = "sheet-dt-remind";`. When the call shown is missed (the
  same test the sheet already uses to label a missed line) and `remind_ui::can_remind(&number)`,
  show a pill in the action row beside Copy / Share / Note, labelled `"Remind me"`; when
  `remind_caption(..)` is `Some` for that number, the pill reads `"Cancel reminder"` instead. If the
  row has no room at 360 dp, put it on the second row with Delete call, same recipe.
  Route it in `details_act`: Remind -> `open_remind_pick(number, name, true, cx)` (the RemindPick
  sheet replaces Call details exactly as it replaces the Number sheet; Back/Cancel there needs no
  return path); Cancel -> `remind_cancel(&number, cx)` (it already shows its pill with Undo).
  `Selection` haptic on tap.
- Country caption: wherever the Number sheet and Call details headers render their subtitle for an
  unnamed number (grep `details_sheet` and the Number sheet header in `recents.rs`), append
  `calling_code::country_line(&number, home_cc)` as `"Germany"` (`"Mobile · Germany"` if a label is
  already shown). `home_cc` comes from `backend.call_log()` `Load::Ready(log).home_cc` as
  `actions_ui.rs` ~line 235 reads it. Nothing changes for contacts.
- Tests: `REMIND` is a `sheet-dt-` id and is routed (a small pure `fn remind_label(armed: bool) ->
  &'static str` in `details_ui.rs`, tested for both); a pure `pub(crate) fn header_caption(label:
  Option<&str>, country: Option<&str>) -> Option<String>` in `details_ui.rs` joining with `" · "`,
  tested for none / label only / country only / both.

### S4. UI: Recents select mode Block and Share (U45)
- `recents.rs`: `pub(crate) const SEL_BLOCK: &str = "rc-sel-block";`, `pub(crate) const SEL_SHARE:
  &str = "rc-sel-share";`. In `select_bar`, two icon buttons left of Delete (36 px circles, the
  glass control recipe the bar already uses; icons from `rovsti_ui` the Blocked page and Share
  pills already use, grep `IconName` there), aria labels `"Block"` / `"Share"`, dimmed and inert
  when `select.numbers(rows)` is empty. Check the bar still fits at 360 dp with the title
  `"12 selected"` truncating (it is `flex_1` + `min_w_0` + `truncate` already).
- Share: `backend::share_text(&call_log_query::select_share_text(..))`, failure pill through
  `outcome.why_not("Couldn't open the share sheet")`; select mode stays on.
- Block: open a confirm with `select_block_text(n)` (reuse the `ConfirmDelete` / Unblock-all
  confirm sheet recipe; add `SheetKind::ConfirmBlockSelected` with `sheet-bs-yes` / `sheet-bs-no`
  if no generic confirm exists). On Yes: build a `blocklist::BulkParse` from `numbers` through
  `blocklist::parse_many` (so emergency numbers and numbers already blocked are skipped and
  counted, joined by newlines), then call the existing `bulk_block_run(many, blocklist::bulk_text,
  cx)` (make it `pub(crate)`; do not copy it), which shows `"Blocked 3 numbers"` with Undo
  (`PillAction::UnblockAll`). Leave select mode after Yes. `Confirm` haptic. Nothing callable ->
  pill `select_nothing_callable_text()`.
- Route `SEL_BLOCK` / `SEL_SHARE` in `recents_select_act` (~line 1150) next to `SEL_DEL`.
- Tests: the ids are under the `rc-sel-` prefix and distinct; the confirm sheet ids route; a pure
  `pub(crate) fn select_tools_enabled(numbers: usize) -> bool`.

### S5. UI: Stats bar tap lists the bucket's calls (U46)
- `stats_ui.rs`: `pub(crate) const BAR_PREFIX: &str = "st-bar-";` (already the id format; make
  `bar_chart` use it). On the page chart (`marks == true`) only, give each bar with `calls > 0` a
  `Role::Button`, `touch_surface` and the finger-up handler the page already uses for its pills;
  `Selection` haptic. Bars with 0 calls stay inert. Keep the sheet chart (person stats) inert.
- Tap: `SheetKind::StatsBucket` (copy the `PruneAge` chooser sheet recipe from `prune_ui.rs`):
  title `bucket_title(bar, range, tz)`, a summary line `bucket_summary(&calls)`, then one row per
  `calls_in(&all, bar.day_start_ms, bar.end_ms)` (`sheet-sb-{i}`, title/subtitle from
  `call_line`), and Close (`sheet-sb-close`). The list scrolls (copy the scroll recipe of the Notes
  page or Blocked list). Tapping a row opens the Number sheet for that number through the existing
  person-open code at ~line 375 (`RowVm::for_number`), so Call back / Message / Details are one tap
  away. The bucket index and range are stored on the stats state, so a log refresh rebuilds the list.
- Tests: `BAR_PREFIX` parse of `st-bar-3` -> 3 (pure `fn bar_index(id) -> Option<usize>`), the
  `sheet-sb-` ids parse and do not collide with other `sheet-s*` prefixes (grep them: `sheet-sd-`,
  `sheet-st-`), a pure `pub(crate) fn bar_tappable(bar: &BarVm, page: bool) -> bool` (`BarVm` gains
  `calls: u32`).

### S6. UI: in-call keypad Paste tones and Copy digits (U49, U50)
- `incall.rs`: when the keypad is open on a live telecom call (the same `live &&
  zone.enabled(Slot::Keypad)` test), read the clipboard once on opening (`cx.read_from_clipboard()`
  -> `text()`) and keep `paste_label: Option<SharedString> = dial::paste_tones_label(..)` on
  `InCallUi`. Show a text button `ic-paste` with that label beside `ic-hide` (the same
  `text_button` recipe, line ~2248) only when `Some`. Do not poll the clipboard per frame.
- Tap `ic-paste`: build `tone_plan`, set `incall.sending: Option<u64>` (a generation counter like
  `hold_gen`), and spawn a task that, per step, sends `CallCommand::Dtmf(d)`, waits `TONE_ON_MS`,
  sends `DtmfStop`, waits `TONE_GAP_MS` (a `Pause` waits `PAUSE_MS`), appends each tone to
  `incall.digits` / `digit_line` as a typed key does (`push_digit`, `digit_line`). It stops early
  when the generation changes, the call is no longer live, or the call source changes. While
  sending, the button reads `"Stop"` (`ic-paste` again; a tap bumps the generation and sends
  `DtmfStop`). At the end: pill `tones_text(&plan)`, `Confirm` haptic. A key tap while sending
  stops the paste first. The WhatsApp call screen does not show the button (no DTMF there;
  `wa_control_of_id("ic-paste")` must be `None`, add it to that test).
- Copy digits: a long press on the digit line (`ic-digits`, give the line an id; use the
  `arm_hold` / `HOLD_MS` pattern the keypad field uses in `keypad.rs` ~line 492, or the existing
  long-press-to-copy on the caller number from U17, whichever already lives in `incall.rs`) copies
  `incall.digits` through `backend::copy_text` (fallback `cx.write_to_clipboard`), pill `"Copied
  tones"`, `LongPress` haptic. Empty digits: nothing happens.
- Tests: pure `pub(crate) fn paste_shown(live: bool, telecom: bool, label: Option<&str>) -> bool`;
  `ic-paste` / `ic-digits` are not WhatsApp controls; a pure step-timing helper
  `pub(crate) fn step_wait_ms(step: ToneStep) -> (u64, u64)` (on, gap) for both step kinds.

### S7. UI: country caption in Recents rows and on Incoming (U48 part 2)
- `recents.rs`: in the row view model (`RowVm`, where an unnamed row picks its subtitle such as the
  number label or `"Unknown"`), for a row with no contact name use `header_caption(label,
  country_line(..))` from S3 so an unknown `+49` caller reads `"Germany"` under the number. Compute
  it when the rows are built (not per frame) with the `home_cc` the log already carries.
  `perf_harness` must still pass.
- `incoming.rs`: for a caller with no contact, show the country line in the existing secondary
  line under the number (where the label or "Mobile" goes), through the same helper. The same rule
  applies to a WhatsApp incoming call from an unknown number.
- Tests: the row view model for a named contact (no country), an unnamed home number (no
  country), an unnamed foreign number (`"Germany"`), a private number (nothing).

### S8. Feedback audit, docs, backlog
- Audit U44-U50 for a pill on success and failure, haptics, confirm/Undo on destructive actions
  (Block selected), the desktop `Unsupported` wording and screen-reader labels. Fix gaps in place.
  Add the findings to `docs/phone-review-notes.md` under "Utilities feedback audit (R6)".
- `docs/phone-acceptance.md`: manual checks for U44-U50 (including: copy "PIN 4821#" in Messages,
  open the in-call keypad on a conference line, see "Paste 4821#", tap it and hear five tones, tap
  Stop mid-way; select three Recents rows, Block, Undo; tap Tuesday's bar on the Stats page).
- This file: mark the R6 rows `done R6`, keep U51-U55 as `backlog R7`.
- Final: run the full check list, then confirm `git status` is clean apart from the commit.
