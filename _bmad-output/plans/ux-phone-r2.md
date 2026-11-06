# UX completeness pass: Rovsti Phone (round 2)

Base: `93c27bccbb69753ea25dbabc8e5102dcd3eeab5d` (main, clean tree, no stash).
Scope: `apps/phone`, `crates/rovsti_core`, `crates/rovsti_wa_proto` (the WhatsApp call history
Phone keeps locally), `crates/rovsti_android` (shared Kotlin bridge). No new product features and
no backends: every item below is in the app's own plan (`apps/phone/_bmad-output/planning-artifacts/epics.md`
stories 20.2, 21.2, 21.3; gap analysis row 25) and its data or permission already exists
(`WRITE_CALL_LOG` is declared in `apps/phone/platforms/android/app/src/main/AndroidManifest.xml`,
`CallLogEntry.id` is pulled, `PrefsV2.speed_dial` is stored).

Round 1 (`ux-phone.md`, commits `08bc2d8`..`bac54ab`, `011826b`) closed G1 to G11. This round
re-audits from the user's side.

## Re-audit: gaps that remain

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| R1 | **High** | **Call history cannot be deleted at all.** No Recents row, sheet or setting removes a call. A user who wants one embarrassing call gone, or wants to start clean, has no path in Phone (round-1 G12, stories 21.2 and 21.3). WhatsApp calls in Recents come from Phone's own `wa-calls.json` and cannot be removed either. | `recents.rs`, `block_ui.rs` (row menu), `settings_ui.rs` (Data), `rovsti_android::providers`, `wa_call.rs` |
| R2 | **High** | **Deleting WhatsApp calls would not stick.** `plan_snapshot` re-records every ended call in the gateway snapshot whose `call_id` is not in the history, so a removed record comes back on the next sync. Needs tombstones before R1 can include WhatsApp rows. | `rovsti_wa_proto::history`, `wa_call.rs` |
| R3 | Medium | **Speed dial can only be set from Settings > Speed dial.** The Contact sheet shows no "Speed dial: 5" / "Add to speed dial" row (story 20.2, second Given). Looking at a person, the user cannot make them a speed dial, see which digit they hold, or remove it. | `contacts.rs::contact_sheet_body/contact_sheet_act`, `rovsti_core::speed_dial` |
| R4 | Low | The Number sheet and Contact sheet show "Recent calls" but give no way to act on that history (delete all calls with this number, story 21.2 third Given). Folded into R1. | `recents.rs`, `contacts.rs` |
| R5 | Info (not planned) | Delete contact stays reachable via "Edit in Contacts". Contact ringtone is gated off (`Caps.CONTACT_RINGTONE = false`). In-app editor (19.7) is P3. Call quality chips (21.4) need feature bits the seam does not carry. The S36 hold-to-confirm pill is replaced by a confirm sheet with a danger button (the story's own TalkBack fallback), so no new gesture component is needed. Diagnostics spike S12 (insert/delete/restore probe) needs a device; this round records the procedure in `docs/spikes/s12-calllog.md` as PENDING. Blocked calls (type 6) are not offered for single delete; "Delete all call history" removes them with everything else, as the confirm text says. | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(if `df` shows under 6 GB free, `rm -rf target/debug/incremental` first; it showed 5.7 GB at planning time).

### S1. Pure logic: entry refs on Recents rows, delete sets, restore JSON, speed-dial lookups (+tests)
`crates/rovsti_core/src/call_log.rs`:
- `#[derive(Clone, Debug, PartialEq, Eq, Hash)] pub enum EntryRef { Log(i64), WhatsApp(String) }`.
- `RecentsRow` gains `pub refs: Vec<EntryRef>`: `group` pushes `EntryRef::Log(entry.id)` for each
  entry of a run, in input order. Update every `RecentsRow { .. }` literal (call_log.rs, recents.rs tests).
- `pub fn entries_by_id(source: &[CallLogEntry], ids: &[i64]) -> Vec<CallLogEntry>`: clones of the
  matching entries, newest first (for Undo).
- `pub fn ids_for_numbers(entries: &[CallLogEntry], numbers: &[&str]) -> Vec<i64>`: same matching
  rule as `recents::history` (move its private matcher to a `pub(crate) fn number_matches` both
  use); blank numbers match nothing, Blocked entries are left out.
- `pub fn deleted_text(n: usize) -> String`: "Deleted 1 call" / "Deleted {n} calls".

`crates/rovsti_core/src/recents.rs`:
- `ExtraCall` gains `pub call_id: String`; `into_row` sets `refs = vec![EntryRef::WhatsApp(call_id)]`;
  `merge_extras` appends `next.refs` when it collapses WhatsApp rows. Update the 7 `ExtraCall { .. }`
  literals (incl. `apps/phone/src/wa_call.rs::extra_calls`, which fills `record.call_id`).
- `pub fn split_refs(refs: &[EntryRef]) -> (Vec<i64>, Vec<String>)`.
- `pub fn extra_ids_for_numbers(extras: &[ExtraCall], numbers: &[&str]) -> Vec<String>`.

`crates/rovsti_core/src/wire.rs`:
- `pub fn call_log_restore_json(entries: &[CallLogEntry]) -> String`: `{"entries":[...]}` with
  `number_raw, kind, date_ms, duration_s, account_key` (serde, same names as the pull).
- `#[derive(Deserialize)] pub struct WriteCount { pub count: u32 }` and
  `pub fn parse_write_count(envelope: &str) -> Result<u32, WriteError>` with
  `pub enum WriteError { Permission, Refused, Other(String) }` (reuse `Envelope` parsing; map
  `error:"permission"` and `error:"refused"`).

`crates/rovsti_core/src/speed_dial.rs`:
- `pub fn digit_for(map: &BTreeMap<u8, SpeedDialEntry>, numbers: &[&str]) -> Option<u8>`: lowest
  digit whose `number_raw` matches any of `numbers` (`PhoneNumber::matches`, trimmed-raw equality
  when unparsable).
- `pub fn assigned_text(digit: u8, name: &str, previous: Option<&SpeedDialEntry>) -> String`:
  "Speed dial {d}: {name}", or "Speed dial {d}: {name} (was {prev})" when another person held it.

`crates/rovsti_wa_proto/src/history.rs` (fixes R2):
- `WaCallHistory` gains `forgotten: VecDeque<String>` (capped at `MAX_RECORDS`, newest first).
- `pub fn knows(&self, call_id: &str) -> bool` (record or forgotten); `record_end`,
  `record_summary`, `extend_newest` and `plan_snapshot` use it instead of the records-only check.
- `pub fn forget(&mut self, call_ids: &[String]) -> Vec<WaCallRecord>` (removes, tombstones,
  returns the removed records for Undo), `pub fn forget_all(&mut self) -> usize`,
  `pub fn restore(&mut self, records: Vec<WaCallRecord>)` (drops their tombstones, re-inserts in
  `started_ms` descending order, skips duplicates, truncates).
- `pub fn forgotten_to_json(&self) -> String` / `pub fn with_forgotten_json(self, json: &str) -> Self`
  (corrupt input gives no tombstones).

Tests: grouping fills `refs` in order and merges WhatsApp refs; `entries_by_id` order and missing
ids; `ids_for_numbers` national/international, blank, blocked; `deleted_text` 1/3;
`call_log_restore_json` round-trips through the wire `CallLogEntry` parser; `parse_write_count`
ok / permission / refused / garbage; `digit_for` match, no match, lowest digit; `assigned_text`
with and without a previous owner; `forget` then `record_summary` of the same call id is refused;
`restore` re-sorts and clears the tombstone; tombstone JSON round-trip and corrupt input.

### S2. Android seam: `CallLogWrite` (shared Kotlin) and `rovsti_android::providers` mirrors
- New `crates/rovsti_android/kotlin/dev/rovsti/bridge/CallLogWrite.kt`, never throws, AD-31 envelope
  via `ProviderJson.ok/error`:
  - `delete(context, ids: String)`: comma-separated ids, `_id IN (...)` in chunks of 400 →
    `{count}`.
  - `restore(context, json)`: parses `{entries}`; inserts `NUMBER, TYPE (inverse of
    WireJson.kindName; add `ProviderJson.kindCode(name)`), DATE, DURATION,
    PHONE_ACCOUNT_COMPONENT_NAME / PHONE_ACCOUNT_ID (split `account_key` at the last '#', see
    `SimAccounts.keyOf`), IS_READ=1, NEW=0` with one `bulkInsert` → `{count}`; zero inserted →
    `error("refused")`.
  - `deleteAll(context)`: deletes every row → `{count}`.
  - `SecurityException` → `error("permission")`, anything else → `error("unknown")`.
- `RovstiBridge.kt`: `@JvmStatic fun deleteCallLog(ids: String)`, `restoreCallLog(json: String)`,
  `deleteAllCallLog()` returning `String`, next to `pullCallLog`.
- `crates/rovsti_android/src/providers.rs`: `pub fn delete_call_log(ids: &[i64]) -> Result<String>`,
  `pub fn restore_call_log(entries_json: &str) -> Result<String>`,
  `pub fn delete_all_call_log() -> Result<String>`; extend `desktop_is_unsupported`.
- No UI yet. Run the Android check for `rovsti_android`.

### S3. Backend and WhatsApp history: delete, restore, delete all (no UI yet)
- `apps/phone/src/backend.rs`:
  - `pub enum WriteOutcome { Done(u32), Permission, Refused }`.
  - `pub fn delete_calls(&mut self, ids: Vec<i64>, cx) -> Task<WriteOutcome>`,
    `pub fn restore_calls(&mut self, entries: Vec<CallLogEntry>, cx) -> Task<WriteOutcome>`,
    `pub fn delete_all_calls(&mut self, cx) -> Task<WriteOutcome>`: run the provider call on the
    background executor (pattern of `set_starred`), parse with `wire::parse_write_count`, then
    `refresh_call_log` on success (the `call_log_changed` observer also fires; the pull generation
    already de-duplicates).
  - Desktop: `Err(Unsupported)` mutates the sample log (`sample.rs`: `delete_calls(ids)`,
    `restore_calls(entries)`, `clear_calls()` on the same `Mutex` the preview uses; mirror
    `write_starred`), `ROVSTI_PREVIEW_CALLLOG_FAIL=1` rehearses `Permission`.
- `apps/phone/src/wa_call.rs`:
  - `pub fn forget_calls(&mut self, ids: &[String], cx) -> Vec<WaCallRecord>`,
    `pub fn restore_calls(&mut self, records: Vec<WaCallRecord>, cx)`,
    `pub fn forget_all_calls(&mut self, cx) -> usize`: each bumps `history_rev`, calls
    `save_history` and `cx.notify()`.
  - `save_history` also writes `wa-calls-forgotten.json` (`forgotten_to_json`); the loader reads it
    and applies `with_forgotten_json` before `extend_newest`.
- Tests: `extra_calls` carries `call_id`; a pure `fn delete_plan(refs: &[EntryRef]) -> (Vec<i64>, Vec<String>)`
  wrapper is not needed (use `recents::split_refs`).

### S4. Recents row menu "Delete" with Undo (fixes R1 for single rows)
- `block_ui.rs`:
  - `MenuTarget` gains `refs: Arc<[EntryRef]>` and `count: u32` (from the row's `RecentsRow`;
    keep the merged rows in `RecentsView` next to `rows`, or store `refs` on `RowVm`).
  - New last item `rm-delete` (`Icon::LucideTrash2`, danger text): "Delete from Recents", or
    "Delete {n} calls" when `count > 1`. Finger-up only, like every other item.
  - `row_menu_tap("rm-delete")`: close the menu; split the refs; snapshot the log entries with
    `call_log::entries_by_id(&log.source, &ids)`; call `wa_call.forget_calls(&wa_ids)` (gives the
    records); run `backend.delete_calls(ids)`. On `Done`: `show_pill_action(deleted_text(n), "Undo",
    PillAction::RestoreCalls(entries, records))`. On `Permission`: pill "Allow call log access" with
    the existing Grant path (`recents_grant`). On `Refused`: restore the WhatsApp records and pill
    "Not allowed on this phone".
- `PillAction::RestoreCalls(Arc<[CallLogEntry]>, Arc<[WaCallRecord]>)`: `pill_fired` runs
  `backend.restore_calls` and `wa_call.restore_calls`; a refusal shows "Couldn't restore".
- The missed badge follows the re-pull; no extra code.
- Pure test in `block_ui.rs`: the delete label for count 1 and 3.

Acceptance: hold a grouped "Ayşe (3)" row, Delete 3 calls, the row disappears, Undo brings it back;
a WhatsApp row is deleted and does not return after a gateway sync.

### S5. "Delete call history" in the Number sheet and Contact sheet, behind a confirm sheet (fixes R1, R4)
- `main.rs`: `SheetKind::ConfirmDelete` and `PhoneApp.confirm_delete: Option<DeleteScope>` with
  `pub(crate) enum DeleteScope { Numbers { numbers: Arc<[SharedString]>, who: SharedString }, All }`.
  Drawn like `replies_reset_sheet` (`replies_ui.rs` around l.706, the ResetReplies sheet): title,
  body, Cancel (`sheet-del-no`) and a danger Delete (`sheet-del-yes`). Put the body and
  handler in a new thin `apps/phone/src/history_ui.rs`.
- Pure `pub(crate) fn confirm_text(scope: &DeleteScope, count: usize) -> (String, String)` in
  `history_ui.rs`: ("Delete calls with {who}?", "{count} calls will be removed from this phone's call
  log.") and ("Delete all call history?", "This removes every entry from this phone's call log.
  Missed-call count goes to 0."). Tested.
- Number sheet and Contact sheet: under the "Recent calls" section (only when it is drawn), a danger
  text button "Delete call history" (`sheet-nhist-del`, `sheet-chist-del`). It closes the person
  sheet and opens the confirm sheet with the sheet's numbers.
- `sheet-del-yes` for `Numbers`: ids from `call_log::ids_for_numbers(&log.entries, ..)`, WhatsApp ids
  from `recents::extra_ids_for_numbers`, then the S4 path (same pill: "Deleted {n} calls" with Undo).
  Check that `contact_sheet_act`'s `sheet-c` prefix and the number sheet's `sheet-n` prefix do not
  swallow `sheet-del-*`.

Acceptance: a contact with calls shows "Delete call history"; confirming removes every call with
that person from Recents and the sheet, Undo restores them; Cancel changes nothing.

### S6. Settings > Data "Delete all call history" (fixes R1, story 21.3)
- `settings_ui.rs`: `RowId::DeleteHistory` (`"set-delete-history"`, `COUNT` 26 to 27), danger text
  row in the Data section after Export and Import. Tap (finger-up) opens the confirm sheet with
  `DeleteScope::All`. Extend the `build_sections` test for the row's place.
- `sheet-del-yes` for `All`: `backend.delete_all_calls` plus `wa_call.forget_all_calls`. On success
  pill "Call history deleted" (no Undo; the confirm sheet already said so), Recents refreshes. On
  `Permission` the same Grant pill as S4.

Acceptance: Settings > Data > Delete all call history > Delete leaves Recents empty (empty state
shown), the missed badge at 0, and WhatsApp calls do not return after a sync.

### S7. Speed dial on the Contact sheet (fixes R3, story 20.2)
- `contacts.rs`: under the number rows (not in chooser mode), one row:
  - "Speed dial {d}" with a "Remove" text button (`sheet-csd-x`) when
    `speed_dial::digit_for(&prefs.speed_dial, &numbers)` is `Some(d)`;
  - otherwise "Add to speed dial" (`sheet-csd`).
  - Tapping "Add to speed dial" (or the assigned label to change it) toggles
    `ContactSheet.sd_open: bool`, showing one inline row of eight 36 px key glyphs
    (`sheet-csd-2`..`sheet-csd-9`, flat glass like the quick-action tiles). An assigned digit shows a
    small dot and its aria says "{d}, {name}".
- Picking a digit assigns `contacts_view::call_target(&numbers)`: `Direct(i)` assigns at once;
  `Choose` reuses the round-1 chooser with a new `StripAction::SpeedDial(u8)` (header "Speed dial
  which number?"); `Nothing` dims the row.
- Write through `speed_dial_write`; pill `speed_dial::assigned_text(d, name, previous)` with "Undo"
  → `PillAction::RestoreSpeedDial(d, previous)` when a previous entry was replaced, else "Undo" that
  unassigns (add `PillAction::ClearSpeedDial(u8)`). Remove shows "Removed speed dial {d}" with the
  existing `RestoreSpeedDial` Undo.
- Tests: chooser header text for `StripAction::SpeedDial`; `sd` ids keep the `sheet-c` prefix;
  `digit_of`-style parse `fn sd_digit(id: &str) -> Option<u8>` accepts 2..=9 only.

Acceptance: open a contact, Add to speed dial, pick 5: the row reads "Speed dial 5", Settings >
Speed dial shows the person on 5, holding 5 on the empty Keypad calls them; Remove with Undo works.

### S8. Docs and plan status
- `docs/phone-acceptance.md`: rows for S4 to S7 (row Delete with Undo, sheet Delete call history,
  Delete all, WhatsApp deletes stick, Contact sheet speed dial).
- `docs/roadmap.md` (Rovsti Phone): tick "Delete call history (CallLogWrite, spike S12)" and note
  that the device check of S12 is pending.
- New `docs/spikes/s12-calllog.md`: procedure (insert a marked row, delete, restore, delete), the
  fallback ("Not allowed on this phone", no Undo) and a PENDING result table.
- `apps/phone/_bmad-output/implementation-artifacts/sprint-status.yaml`: 21-2 and 21-3 to `review`;
  20-2 stays as is unless already `review`.
- Append a "Status" section to this file with the commits.

## Next round (candidates)
- Re-audit after S1 to S8. Expected to be empty apart from R5 items that need a device or are
  gated by design.

## Status

S1 to S7 are committed on main after base `93c27bc`:

- 6a188b7 Rovsti phone: Pure logic: entry refs, delete sets, restore JSON, speed-dial lookup, WhatsApp tombstones (+tests)
- 8e596ce Rovsti phone: Android seam: shared Kotlin CallLogWrite and rovsti_android::providers mirrors
- d54900a Rovsti phone: Backend and WhatsApp history: delete, restore, delete all (no UI yet)
- 1870cb2 Rovsti phone: Recents row menu Delete with Undo
- 529fa4c Rovsti phone: Delete call history from Number and Contact sheets via confirm sheet
- 1945934 Rovsti phone: Settings > Data: Delete all call history
- 15bbb7f Rovsti phone: Speed dial on the Contact sheet

S8 (docs: phone-acceptance D1 to D12, roadmap tick, `docs/spikes/s12-calllog.md`, sprint-status 21-2 and 21-3 to review) is in the working tree, not committed. The S12 device check is PENDING; the re-audit after S8 found nothing else reachable without a device.
