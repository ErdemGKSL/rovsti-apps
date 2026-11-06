# UX completeness pass: Rovsti Phone (round 3)

Base: `da3614bdb8dd9115f7851def09e398d5c6db8776` (main, clean tree, no stash).
Scope: `apps/phone`, `crates/rovsti_core`, `crates/rovsti_android` (Phone seams). No new product
features and no backends. Intent comes from the app's own planning
(`apps/phone/_bmad-output/planning-artifacts/epics.md`: stories 7.4, 7.5, 19.1, 20.3, 21.1, 21.2)
and from code that already exists but has no UI path (`SetupChecklist::reopened_since`,
`setup::XiaomiConfirmations::unconfirm`, `Backend::set_xiaomi_confirmed(.., false, ..)`, the
blocked-calls pull with call-log ids, `CallLogWrite.delete` by id).

Round 1 (`ux-phone.md`) closed G1 to G11. Round 2 (`ux-phone-r2.md`, commits `6a188b7`..`da3614b`)
closed delete call history (row, sheet, all), WhatsApp tombstones and speed dial on the Contact
sheet. This round re-audits from the side of a user holding the phone.

## Re-audit: gaps that remain

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| T1 | **High** | **One call inside a grouped row cannot be deleted.** Recents groups consecutive calls ("Ayşe (3)"); the row menu deletes all three, the sheets delete every call with the person. The sheet's "Recent calls" lines are inert, so there is no way to remove just one call (story 21.1/21.2 "Delete entry"). | `recents.rs::history_section`, `HistoryVm`, `rovsti_core::recents::HistoryLine` |
| T2 | Medium | **Sheet history goes stale while the sheet is open.** `ContactSheet.history` is rebuilt only when contacts change (`refresh_open_sheet`), `RecentsView.number_history` only when the sheet opens. A call that ends, a delete (T1) or an Undo while the sheet is up is not shown. | `contacts.rs::refresh_open_sheet`, `recents.rs::rebuild_recents`, `sync_wa_history` |
| T3 | Medium | **Blocked calls are a dead end.** Settings > Blocked numbers > Blocked calls opens the Number sheet, but `recents::history` drops Blocked entries (and the main pull excludes type 6), so the sheet shows no calls and no "Delete call history". The only way to remove a blocked call is "Delete all call history". `delete_call_ids` also snapshots Undo entries from `log.source`, which never holds blocked rows, so a blocked delete would be a silent no-op. | `blocked_ui.rs::blocked_call_tap`, `history_ui.rs`, `block_ui.rs::delete_call_ids`, `backend.rs::call_log_write`, `sample.rs` |
| T4 | Medium | **Voicemail is hidden and can dead-end.** Holding `1` calls voicemail, but the `1` key shows no voicemail glyph (story 20.3), so nobody finds it. With no voicemail number set the status still says "Calling voicemail..." and nothing happens; story 20.3 wants "No voicemail number set" with "Open call settings". | `keypad.rs::dial_key`, `voicemail_press`, `rovsti_android::telecom`, `RovstiBridge.kt` |
| T5 | Medium | **Contacts search "No matches" is a dead end for a number.** Typing or pasting an unsaved number in Contacts search shows only "No contact matches ...". The user expects Call, Message and Add to contacts (story 19.1 "wherever I see an unsaved number"). | `contacts.rs::no_matches`, `contacts_results` |
| T6 | Low | **The Keypad's matched name is not tappable.** When the typed number belongs to a contact, the status line shows the name and avatar but tapping it does nothing; there is no way from the Keypad to that person's sheet. | `keypad.rs::status_line` |
| T7 | Low | **A Xiaomi "I did it" cannot be taken back.** Once confirmed, the row is done with no buttons. `set_xiaomi_confirmed(item, false)` and `XiaomiConfirmations::unconfirm` exist with no UI (story 7.5: "without false done"). | `setup_ui.rs::buttons`, `Act` |
| T8 | Low | **"Something changed: {item}" never shows.** `SetupChecklist::reopened_since` is unused; when a permission or the default-app role is lost, the sheet header just says "Finish setup" (story 7.4, UX-DR11). | `setup_ui.rs::sheet_header`, `main.rs::sync_setup_celebration` |
| T9 | Info (not planned) | Delete contact (19.2) stays reachable via "Edit in Contacts". Contact ringtone is gated off (`Caps.CONTACT_RINGTONE = false`). In-app editor (19.7) is P3. The full Call details page and call quality chips (21.1, 21.4) need the `pullCallHistory` seam and feature bits: the sheets already give the user history, actions and delete. Emergency-number labelling on the Keypad (20.4) and the voicemail unread badge (S9) need device spikes. A "New contact" button on a non-empty Contacts tab is not in the app's plan (hand-off design). Blocking a number from the Blocked page by picking a contact is covered by Block on the Contact sheet. S12 device check stays PENDING. | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(`df` showed 6.4 GB free at planning time; if it shows under 6 GB, `rm -rf target/debug/incremental` first).
Never invent GPUI APIs: reuse only what these files already call (`touch_surface`, `svg().path(icon.path())`,
`show_pill_action`, `presses`, `uniform_list`). Icons: `LucideTrash2` and `LucideVoicemail` are already used in Phone.

### S1. Pure logic in rovsti_core (+tests)
`crates/rovsti_core/src/recents.rs`:
- `HistoryLine` gains `pub entry: EntryRef` and `pub blocked: bool`. `history` fills
  `EntryRef::Log(entry.id)` for call-log lines and `EntryRef::WhatsApp(extra.call_id.clone())` for
  extras; `blocked: false`. Update every `HistoryLine { .. }` literal (core tests, `apps/phone/src/recents.rs` tests).
- `pub fn blocked_history(entries: &[CallLogEntry], numbers: &[&str], limit: usize) -> Vec<HistoryLine>`:
  only `CallLogKind::Blocked` entries whose number matches (same `wanted_numbers`/`number_in` rule,
  blank numbers never match), newest first, at most `limit`; `call_type: CallType::Incoming`,
  `blocked: true`, `whatsapp: false`, `entry: EntryRef::Log(id)`.
- `history_text`: a `blocked` line has the title "Blocked call" and never a duration.

`crates/rovsti_core/src/call_log.rs`:
- `pub fn blocked_ids_for_numbers(entries: &[CallLogEntry], numbers: &[&str]) -> Vec<i64>`: the mirror
  of `ids_for_numbers` that keeps only Blocked entries.

`crates/rovsti_core/src/contacts_view.rs`:
- `pub fn query_number(query: &str) -> Option<String>`: `None` when the trimmed query contains any
  alphabetic character; otherwise `dial::normalise_paste(query)`, returned only when non-empty and
  `dial::validate` accepts it.

`crates/rovsti_core/src/setup.rs`:
- `pub fn changed_title(reopened: &[SetupItem]) -> Option<String>`: `None` for an empty slice;
  "Something changed: {title}" for one item; "Something changed: {first} and {n} more" for more
  (titles from `SetupItem::title`).
- `pub fn still_reopened(reopened: &[SetupItem], now: &SetupChecklist) -> Vec<SetupItem>`: keeps the
  items that are still not done in `now` (so the header clears as items are fixed).

Tests: `history` fills `entry` for both kinds; `blocked_history` matches national/international forms,
skips non-blocked entries, respects `limit`, blank number gives nothing; `history_text` for a blocked
line (title, no duration); `blocked_ids_for_numbers` vs `ids_for_numbers` on a mixed list;
`query_number` for "0555 111 22 33", "+90 (555) 111-2233", "Ayşe", "05a", "", "12" (whatever
`validate` rejects); `changed_title` for 0, 1 and 3 items; `still_reopened` drops a fixed item.

### S2. Sheet history: per-call delete with Undo, and live refresh (fixes T1, T2)
`apps/phone/src/recents.rs`:
- `HistoryVm` gains `entry: EntryRef` (from `line.entry`) and `blocked: bool`.
- `pub(crate) fn history_section(theme, lines: &[HistoryVm], edit: Option<HistoryEdit>, cx: &mut Context<PhoneApp>) -> Option<AnyElement>`
  with `pub(crate) struct HistoryEdit { pub editing: bool, pub toggle_id: &'static str, pub line_prefix: &'static str }`.
  - The "Recent calls" label row gets a right-aligned accent text button "Edit"/"Done"
    (`toggle_id`: `sheet-nhist-edit` on the Number sheet, `sheet-chist-edit` on the Contact sheet;
    both keep their `sheet-n` / `sheet-c` routing prefixes and must not start with `sheet-del-`).
  - While `editing`, each 44 px line shows a trailing 44 px `LucideTrash2` danger button with id
    `{line_prefix}{i}` (`sheet-nhx-` / `sheet-chx-`) and aria "Delete {title}, {detail}". The line
    height does not change. Acts on finger-up through `touch_surface`, like the strip tiles.
- Pure `pub(crate) fn history_index(id: &str, prefix: &str) -> Option<usize>` (tested: valid,
  wrong prefix, non-number, out of a 10-line range).
- State: `RecentsView.number_history_edit: bool`, `ContactSheet.history_edit: bool`; both reset to
  false whenever a sheet opens.
- Tap on a line: `self.delete_row_calls(Arc::from([vm.entry.clone()]), window, cx)` (the existing
  "Deleted 1 call" + Undo path). The sheet stays open.
- `PhoneApp::refresh_sheet_history(&mut self, cx: &gpui::App)`: rebuilds `recents.number_history`
  when the Number sheet is the visible sheet (from `recents.number`, honouring the T3 flag of S4) and
  `contact_sheet.history` (move that part out of `refresh_open_sheet`, which then calls it). Call it
  at the end of `rebuild_recents` and `sync_wa_history`. When the list becomes empty, editing turns off.
- Route `sheet-nhist-edit`/`sheet-nhx-*` in `number_sheet_act` and `sheet-chist-edit`/`sheet-chx-*`
  in `contact_sheet_act` (before the generic `sheet-c` handling, in the same place as
  `CONTACT_SHEET_DELETE`).

Acceptance: open "Ayşe (3)" info, Edit, delete the middle call: the pill says "Deleted 1 call",
the sheet shows two lines and Recents shows "Ayşe (2)"; Undo brings the third back in the open sheet.

### S3. Delete path that takes its own Undo snapshot; blocked-call refresh (seam for T3, no UI yet)
- `apps/phone/src/block_ui.rs`: split `delete_call_ids` into
  `pub(crate) fn delete_call_entries(&mut self, entries: Vec<CallLogEntry>, wa_ids: Vec<String>, window, cx)`
  (ids = `entries.iter().map(|e| e.id)`; same pill, Grant and refusal handling as today) and keep
  `delete_call_ids(ids, wa_ids, ..)` as the wrapper that snapshots from `log.source` with
  `entries_by_id`. No behaviour change for existing callers.
- `apps/phone/src/backend.rs::call_log_write`: after `Done`, also `refresh_blocked_calls` when
  `blocked_calls_wanted` (Android also gets `CALL_LOG_CHANGED`; the pull generation de-duplicates).
- `apps/phone/src/sample.rs` (desktop): `blocked_calls(now_ms)` applies `EDITS` (drops deleted ids,
  includes restored entries whose kind is Blocked); `LogEdits::delete` accepts ids that exist in
  `blocked_calls` as well as in the visible log. Unit test in `sample.rs`: delete a blocked id, it is
  gone from `blocked_calls`, restore brings it back.

### S4. Blocked calls: history and delete in the Number sheet (fixes T3)
- `RecentsView.number_blocked: bool`: true only when `blocked_call_tap` opened the sheet (false in
  `recents_info` and the Contacts path at `contacts.rs` ~l.1085).
- `PhoneApp::blocked_history_for(&self, numbers: &[&str], cx) -> Arc<[HistoryVm]>`: from
  `backend.blocked_calls()` (`Load::Ready` only) with `recents::blocked_history(.., HISTORY_LIMIT)`.
  `blocked_call_tap` and `refresh_sheet_history` use it when `number_blocked`.
- `history_ui::DeleteScope::Numbers` gains `blocked: bool`. `scope_ids` uses
  `call_log::blocked_ids_for_numbers` over `backend.blocked_calls()` (and no WhatsApp ids) when set;
  `sheet-del-yes` then calls `delete_call_entries` with the matching blocked entries (S3), so Undo works.
- `confirm_text`: with `blocked`, ("Delete blocked calls from {who}?", "{count} blocked calls will be
  removed from this phone's call log."). Extend its test.
- The Number sheet's "Delete call history" button and the S2 Edit mode work unchanged on blocked lines.

Acceptance: Settings > Blocked numbers > Blocked calls > a row: the sheet lists "Blocked call" lines;
Edit deletes one with Undo; "Delete call history" > Delete empties that number's blocked calls and the
Blocked calls list updates (desktop preview included).

### S5. Voicemail seam and Keypad: glyph on 1, "No voicemail number set" (fixes T4)
- Kotlin `crates/rovsti_android/kotlin/dev/rovsti/bridge/RovstiBridge.kt`:
  `@JvmStatic fun voicemailConfigured(): Boolean`, never throws: true when `READ_PHONE_STATE` is not
  granted or on any exception (unknown never blocks the call); otherwise
  `!TelecomManager.getVoiceMailNumber(handle).isNullOrBlank()` with `handle` =
  `getDefaultOutgoingPhoneAccount("tel")` ?: first of `callCapablePhoneAccounts` (true when none).
- `crates/rovsti_android/src/telecom.rs`: `pub fn voicemail_configured() -> Result<bool>` via
  `bridge::call_bool0("voicemailConfigured")`; add it to the desktop `Unsupported` test.
- `apps/phone/src/backend.rs`: `pub fn voicemail_configured(&self) -> bool` (Android result, `true`
  on error; desktop: false only when `ROVSTI_PREVIEW_NO_VOICEMAIL=1`). `call_voicemail` returns a new
  `Placement::NoVoicemail` before placing when it is false (handle the new arm everywhere `Placement`
  is matched: `keypad.rs`, `recents.rs::place_call_from_on`, `speed_dial_ui.rs` if any).
- `apps/phone/src/main.rs::Status` gains `NoVoicemail`. `keypad.rs::voicemail_press` sets it; the
  status line draws "No voicemail number set" plus a `status_button` "Open call settings"
  (`kp-vm-set`, finger-up, `backend::open_system(SystemPage::CallSettings)`; a failure sets the
  status text from `why_not`). It clears like other statuses (next key press).
- `dial_key`: on the Keypad tab only (`!in_call`), the `1` key's empty caption slot draws a 12 px
  `LucideVoicemail` svg in `secondary_label`; its aria is "1, hold for voicemail" (add
  `Key.hold_label: Option<&'static str>`, `Some` only for `1`, and keep the `expected_label` test for
  `label`). Same slot height, so no key moves.
- Pure test in `keypad.rs`: `KEYS[0].hold_label` is the only `Some`.

### S6. Contacts search and Keypad: act on a number with no match, open a matched contact (fixes T5, T6)
- `ContactsView.query_number: Option<SharedString>`, set in `apply_contacts_query` from
  `contacts_view::query_number` (never per frame).
- `no_matches(theme, echo, number: Option<&SharedString>, presses..)`: when a number is given, the
  text reads "No contact has {shown_number}." and three pills follow (`sheet_pill`/state-pill style):
  "Call" (`cs-nm-call`), "Message" (`cs-nm-msg`), "Add to contacts" (`cs-nm-add`). Route them in
  `contacts_pill` (finger-up): Call → `place_call_from(&number, window, cx)` (needs `window`: route
  through the same handler that already has it, or store the tap and act in the next
  `contacts_pointer`, whichever the existing state pills use); Message → `backend::send_sms_to`;
  Add → `backend::add_contact`; failures → `show_notice_pill(outcome.why_not(..))`.
- `keypad.rs`: the matched-name status line becomes a button (`kp-name`, aria "{name}, open
  contact"), finger-up: find the `ContactRow` with `Found.id` in `self.contacts.all` and call
  `open_contact_sheet(row, false, window, cx)`; nothing when it is not found. Add `kp-name` to
  `activate` (not to `acts_on_down`).
- Tests: `query_number` is in S1; in `contacts.rs` tests assert the three ids keep the `cs-` prefix
  routed by `state_pill`.

### S7. Setup: undo a Xiaomi confirmation, "Something changed" header (fixes T7, T8)
- `setup_ui.rs`: `Act::Unconfirm`. In `buttons`, a Xiaomi item that is `Done` and confirmed by hand
  (new `Backend::xiaomi_confirmed(item) -> bool`, true when `prefs.ui.xiaomi_confirmed[item.key()] ==
  APP_VERSION`) gets one secondary button "Mark as not done". `setup_row` must draw buttons for
  done rows when the list is non-empty (today it skips buttons for every done row). `run_act`:
  `set_xiaomi_confirmed(item, false)`; `setup_note` maps it to `None`.
- `main.rs`: `PhoneApp.setup_seen: Option<SetupChecklist>` and `setup_reopened: Vec<SetupItem>`. In
  `sync_setup_celebration` (already gated on `permissions_known() && prefs_ready()`): when the
  checklist differs from `setup_seen`, add `now.reopened_since(&seen)` to `setup_reopened` (no
  duplicates), then `setup_reopened = setup::still_reopened(&setup_reopened, &now)`, then store `now`.
  The first observation only stores (a launch never reports a change).
- `sheet_header`: when not complete, the title is `setup::changed_title(&self.setup_reopened)` or
  "Finish setup". Cache the `SharedString` when `setup_reopened` changes (the sheet renders every
  frame while it moves).
- Pure test in `setup_ui.rs`: a done, hand-confirmed Xiaomi item gets exactly "Mark as not done";
  a done readable item gets no buttons (extract the button choice into a pure
  `fn done_buttons(item: SetupItem, confirmed_by_hand: bool) -> Vec<Button>` if `buttons` needs
  `self`).

### S8. Docs and plan status
- `docs/phone-acceptance.md`: rows for S2 (one-call delete, live sheet history), S4 (blocked calls
  history and delete), S5 (voicemail glyph, no-voicemail status), S6 (search number actions, Keypad
  name opens contact), S7 (Mark as not done, Something changed).
- `docs/roadmap.md` (Rovsti Phone): note per-call delete and blocked-call delete under the existing
  "Delete call history" tick; note voicemail "not set" handling (device check of S9 still PENDING).
- `apps/phone/_bmad-output/implementation-artifacts/sprint-status.yaml`: leave 20-3 and 21-1 as
  `backlog` (only parts are done); no other changes.
- Append a "Status" section to this file with the commits.

## Next round (candidates)
- Re-audit after S1 to S8. Expected to leave only T9 items (device spikes, gated or P3 by design).
