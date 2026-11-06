# UX completeness pass: Rovsti Phone (round 1)

Base: `a3113fc2bea0f28f7ce7b1f5a09d0ffa6cf0637c` (main). No stash, clean tree.
Scope: `apps/phone`, `crates/rovsti_core`, `crates/rovsti_android` (Phone seams), WhatsApp calls
through `rovsti_wa_client`/`rovsti_wa_ui`. No new product features and no backends. Intent is
taken from the app's own planning (`apps/phone/_bmad-output/planning-artifacts/epics.md`,
Epics 19 to 21, still `backlog` in `sprint-status.yaml`) and from model fields that already exist.
Pure logic goes first and is tested. GPUI glue stays thin and uses only GPUI APIs the app
already uses (`cx.write_to_clipboard`, `touch_surface`, `drag_surface`, `uniform_list`, `Spring`).

Earlier Phone plans (`phone.md`, `phone-r2.md`, `phone-r3.md`) closed the roadmap items. This pass
audits from the user's side.

## Audit: gaps found (from the point of view of a user holding the phone)

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| G1 | **High** | **The Contact sheet has no Message, Copy, Share or Block.** The only actions are Call, WhatsApp, star and "Edit in Contacts". `telecom::share_text` and `telecom::copy_text` exist in `rovsti_android` but Phone never uses them. You cannot text, copy, share or block a saved person from Phone (story 19.3). | `contacts.rs::contact_sheet_body/contact_sheet_act` |
| G2 | **High** | **The Number sheet (Recents info for an unknown number) only offers Call and Add to contacts.** Message, Copy, Block and WhatsApp call exist only in the hidden 450 ms long-press menu (`block_ui::RowMenu`). | `recents.rs::number_sheet_body` |
| G3 | **High** | **No call history anywhere.** `CallLogEntry.duration_s` and `ExtraCall.duration_s` are loaded but never shown. A grouped row ("Ayşe (3)") cannot be opened to see when each call happened or how long it lasted. Info on a known number opens the Contact sheet, which shows no calls at all (story 21.1 data). | `recents.rs`, `contacts.rs`, `rovsti_core::recents` |
| G4 | **High** | **Favorites cannot be reordered.** `PrefsV2.favorites_order` is stored, validated, exported and imported, but the grid ignores it (alphabetical `rows_of`) and no UI writes it (story 19.5, FR-64). | `contacts.rs::sync_contacts`, `rovsti_core::settings` |
| G5 | **High** | **The Frequent row is missing.** `call_log::frequent` (tested) and `Settings.show_frequent` (default on, part of backup) exist, but Favorites has no Frequent row and Settings has no "Show Frequent" toggle (story 19.6). | `contacts.rs::favorites_body`, `settings_ui.rs` |
| G6 | Medium | **Keypad: a typed number that matches no contact has no "Add to contacts" or "Message".** Story 19.1 puts "Add to contacts" in the status-line slot. | `keypad.rs::status_line` |
| G7 | Medium | **Tapping a WhatsApp row in Recents calls back over the carrier.** A missed WhatsApp call is called back as a cellular call. The WhatsApp call-back is only in the long-press menu. | `recents.rs::recents_call` |
| G8 | Medium | **The Recents row menu has no "Add to contacts"** for an unsaved number (story 19.1 lists it). | `block_ui.rs::row_menu_overlay/row_menu_tap` |
| G9 | Low | **Speed dial "Remove" acts at once with no feedback or Undo.** Every other destructive action in Phone (Block, Unblock) gives a pill with Undo. | `speed_dial_ui.rs::speed_dial_tap` |
| G10 | Low | **Quick replies "Clear" empties a field with no Undo.** | `replies_ui.rs` (CLEAR_IDS branch) |
| G11 | Low | A stale comment says "Holding a row opens the same sheet as its info button", but holding a row opens the row menu. | `recents.rs` ~l.704 |
| G12 | High (deferred, round 2) | **Delete call history (one entry, a grouped row, or everything) does not exist** (stories 21.2, 21.3). This needs a new shared Kotlin `CallLogWrite` (`WRITE_CALL_LOG`), a `rovsti_android::providers` mirror and spike S12 on a device. It is a seam plus a permission, not glue, so it is planned on its own next round. | `crates/rovsti_android`, Kotlin bridge |
| G13 | Info (not planned) | Delete contact (19.2) is reachable through "Edit in Contacts" (Rovsti Contacts has Delete). Contact ringtone (19.4) is gated off by design (`Caps.CONTACT_RINGTONE = false`). The in-app editor (19.7) is P3 and cut first. Call quality (21.4) needs feature bits that no seam carries. The full Call details page with S35 (21.1) is covered for the user by G3's in-sheet history. Blocked numbers, Backup, Quick replies reset, the WhatsApp settings (link and connect key) and Diagnostics copy are already complete, with pills. | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(if `df` shows under 6 GB free, `rm -rf target/debug/incremental` first).

### P1. rovsti_core pure logic: history lines, duration, favorites order (+ tests)
`crates/rovsti_core/src/call_log.rs`:
- `pub fn format_duration(seconds: u64) -> SmallText<16>`: `m:ss`, or `h:mm:ss` from an hour on.
  Make `apps/phone/src/wa_call.rs::format_elapsed` call it (`.to_string()`) so the two never drift.
- `pub fn clock_text(then_ms: i64, tz_offset_s: i32, hour12: bool) -> RelativeTime`: the
  existing private `push_clock` exposed for one timestamp.

`crates/rovsti_core/src/recents.rs`:
- `#[derive(Clone, Debug, PartialEq, Eq)] pub struct HistoryLine { pub call_type: CallType, pub whatsapp: bool, pub date_ms: i64, pub duration_s: u64 }`
- `pub fn history(entries: &[CallLogEntry], extras: &[ExtraCall], numbers: &[&str], limit: usize) -> Vec<HistoryLine>`:
  - Takes call-log entries and WhatsApp extras whose number matches any of `numbers`
    (`PhoneNumber::matches`, with exact trimmed-raw equality for numbers that do not parse).
  - An empty or blank number never matches, and Blocked entries are dropped.
  - Returns them newest first, at most `limit`, reading only the newest `call_log::MAX_ENTRIES`.
  - An extra maps to `Missed` when `missed`, else `Unknown`, with `whatsapp: true`.
- `pub fn history_text(line: &HistoryLine, now_ms: i64, locale: LocaleTime) -> (String, String)`:
  - The title is "Missed call", "Incoming call", "Outgoing call", "Declined", "Voicemail" or
    "Call", prefixed with "WhatsApp " for WhatsApp lines.
  - The detail is `relative_time`. When that is not today's clock, it also gets a space and
    `clock_text`. When `duration_s > 0` and the type is not Missed or Declined, it ends with
    `" · " + format_duration`.

`crates/rovsti_core/src/contacts_view.rs`:
- `pub fn order_favorites<'a>(favorites: &'a [WireContact], order: &[String]) -> Vec<&'a WireContact>`:
  - Contacts whose `lookup_key` is in `order` come first, by their index there.
  - The rest (unknown key or no key) follow, sorted by `display_name`, case-insensitive, then by
    `id`.
  - Keys of vanished contacts are skipped and never pruned.
- `pub fn reordered_keys(shown: &[&WireContact], from: usize, to: usize) -> Vec<String>`:
  - Returns the full key list after moving `from` to `to` (reuse `settings::move_reply`).
  - Contacts without a key are left out.
  - An out-of-range index returns the unchanged list.

Tests:
- `format_duration`: 0, 59, 61 and 3 725 seconds.
- `clock_text`: 12 h and 24 h.
- `history`:
  - It matches a national and an international form of one number.
  - It merges extras in date order and respects `limit`.
  - It drops blocked entries, and an empty number gives nothing.
- `history_text`:
  - Today shows the clock only; yesterday reads "Yesterday 14:03".
  - Missed has no duration; WhatsApp gets its prefix.
- `order_favorites`: saved order, a new star appended by name, a duplicate key, a missing key,
  and a contact without a key.
- `reordered_keys`: up, down, the ends, and out of range.

### P2. Quick actions on the Contact sheet and the Number sheet (fixes G1, G2)
- `apps/phone/src/backend.rs`: `pub fn share_text(text: &str) -> Outcome` (via
  `telecom::share_text`, `"sharing"`), next to `send_sms_to`. Copy keeps using
  `cx.write_to_clipboard(ClipboardItem::new_string(..))`, as `rm-copy` does.
- New `apps/phone/src/actions_ui.rs` (thin):
  - `pub(crate) enum StripAction { Message, Copy, Share, Block }`.
  - `pub(crate) fn action_strip(app, theme, ids: [&'static str; 4], blocked: bool, cx) -> AnyElement`:
    a row of four 64 x 64 `flat_glass` tiles with a 12 px gap. Each tile has an icon
    (`LucideMessageCircle`, `LucideCopy`, `LucideShare2` (check the icon exists in
    `rovsti_ui::Icon`, else `LucideShare`), `LucideBan`) and a caption: Message, Copy, Share,
    and Block or Unblock. Tiles press on touch and act on finger-up through `touch_surface`.
  - `PhoneApp::run_strip_action(&mut self, action, number: SharedString, who: SharedString, window, cx)`:
    - Message calls `backend::send_sms_to`; a failure goes to the pill with `why_not`.
    - Copy copies and shows the pill "Number copied".
    - Share calls `backend::share_text(&shown_number(number))`; a failure goes to the pill.
    - Block runs `start_block(number, who, None, ..)`, or `run_unblock(.., true, ..)` when
      blocked. The sheet closes first.
- Contact sheet (`contacts.rs`):
  - Add the strip under the header (not in chooser mode), with ids `sheet-ca-msg`, `sheet-ca-copy`,
    `sheet-ca-share` and `sheet-ca-block`.
  - The strip acts on `contacts_view::call_target(&numbers)`. With `Direct(i)` it acts on that
    number.
  - With `Choose`, set `ContactSheet.pending: Option<StripAction>` and flip `chooser = true`.
    The header reads "Message which number?", "Copy which number?" and so on. A number tap in
    chooser mode with a pending action runs it instead of calling. The speed-dial pick keeps
    priority over a pending action.
  - With `Nothing`, the tiles are dimmed and inert.
  - Route the new ids in `contact_sheet_act`.
- Number sheet (`recents.rs::number_sheet_body/number_sheet_act`):
  - When `row.number` is non-empty, add the same strip under the header with ids `sheet-na-msg`,
    `sheet-na-copy`, `sheet-na-share` and `sheet-na-block`. Block is dimmed with the
    `refusal_note` when `block_refusal` is Some.
  - Add a "WhatsApp" pill (`sheet-nwa`) under Call when `row.callable` and WhatsApp is connected.
    It calls `place_whatsapp`.
  - Make sure `number_sheet_act` does not send `sheet-na-*` to `contact_sheet_act` (its prefix
    check is `sheet-c`, so it does not).
- Pure test in `actions_ui.rs`: the chooser header text for each `StripAction`, and the strip
  ids keep the `sheet-c` and `sheet-n` prefixes the router uses.

Acceptance:
- A contact with one number: Message opens the SMS app, Copy shows "Number copied", Share opens
  the share sheet, and Block blocks with Undo.
- A contact with two numbers and no default: the tile opens the chooser, and tapping a number
  performs the action.
- Unknown Recents number: the info sheet shows the strip and WhatsApp.

### P3. Recent calls in the Number sheet and the Contact sheet (fixes G3)
- Add `pub(crate) struct HistoryVm { title: SharedString, detail: SharedString, icon: Icon, missed: bool }`
  in `recents.rs`, built from `recents::history_text` when the sheet opens, never per frame.
  - The icon follows `direction_icon`. A WhatsApp line uses the existing `whatsapp_badge`.
- `PhoneApp::history_for(&self, numbers: &[&str], cx) -> Arc<[HistoryVm]>`:
  - Reads `backend.call_log()`; only `Load::Ready` counts, otherwise it is empty.
  - Uses `wa_call.read(cx).recents_extras()` and `recents::history(.., 10)`.
- Store the result in `RecentsView.number_history` (set in `recents_info` and in the Blocked-calls
  path that opens the number sheet) and in a new `ContactSheet.history`.
  - `open_contact_sheet` builds it from the contact's numbers.
  - `refresh_open_sheet` rebuilds it when the log changes.
- Both sheets draw a "Recent calls" `footnote` label and up to 10 fixed 44 px lines: icon,
  title (danger colour when missed), and detail on the right in tabular figures.
  - The section is not drawn when the list is empty.
  - Each line's aria is "{title}, {detail}".
  - Lines are not tappable: the sheet already has Call.
- Desktop preview: the sample call log (`sample.rs`) must give the sample contacts a few lines,
  with durations, so `ROVSTI_PREVIEW_CONTACT=1` shows the section.

Acceptance:
- Info on "Ayşe (3)" shows three lines with times and durations.
- A missed WhatsApp call shows "WhatsApp missed call" without a duration.
- A contact with no calls shows no section.

### P4. Favorites in the user's order, with reorder (fixes G4)
- `sync_contacts`:
  - Build the Favorites tiles from `contacts_view::order_favorites(list, &prefs.favorites_order)`
    (not `rows_of`'s alphabetical order).
  - Rebuild when the `favorites_order` Vec changes. Remember a cheap key (`favorites_order.len()`
    plus a hash, or an `order_rev` counter bumped by the writer) in `FavoritesView`.
- `PhoneApp::favorites_move(&mut self, from: usize, to: usize, window, cx)`:
  - Writes `contacts_view::reordered_keys` through one `backend.update_prefs`.
  - Gives a selection haptic and re-syncs the tiles.
- Accessible path, always available: when the Contact sheet is opened from a Favorites tile
  (record `ContactSheet.fav_index: Option<usize>`), show two small pills, "Move earlier"
  (`sheet-cfav-up`) and "Move later" (`sheet-cfav-down`), under the star row. Dim them at the
  ends, mirroring the replies editor's up/down buttons.
- Drag (S34-lite) in `favorites_pointer`:
  - The long-press timer no longer opens the sheet at once. It sets
    `FavoritesView.lifted = Some(index)` (scale 1.06, medium haptic).
  - If the finger then moves past `TAP_SLOP`, enter reorder. The lifted tile is drawn at the
    finger as an overlay, and the target slot comes from `tile_at` (pure, already tested).
  - On finger-up after a move, call `favorites_move(from, target)`.
  - On finger-up without a move, open the Contact sheet as before.
  - Reduced motion: no scale.
  - Gate scrolling: while `lifted.is_some()`, ignore the list's scroll offset changes for hit
    testing only. Do not invent a scroll-lock API. If the list scrolls during a drag, the sheet
    pills remain the reliable path.
- Tests (in `contacts.rs` tests): a pure helper `reorder_target(from, to, len) -> Option<(usize, usize)>`
  that rejects same-slot and out-of-range moves.

Acceptance:
- "Move earlier" on the second favorite swaps it with the first, and the order survives a
  restart and Export/Import.
- Dragging a tile to another slot commits once.
- Starring a new contact appends it at the end.

### P5. Frequent row and "Show Frequent" (fixes G5)
- `FavoritesView.frequent: Arc<[ContactRow]>`:
  - Computed once per call-log swap or favorites change in `sync_contacts` / `sync_recents`
    (whichever sees the new `Arc`).
  - Uses `call_log::frequent(&log.entries, now_ms(), FREQUENT_WINDOW_DAYS, FREQUENT_LIMIT, |id| starred.contains(&id))`.
  - Drops blocked numbers (`backend.is_blocked`).
  - Maps each to a `ContactRow`: the matched contact (`contact_for_number`), else
    `ContactRow`-like data from the number (`RowVm::for_number`-style; add
    `ContactRow::for_number(number, title)` if needed).
  - Hidden when fewer than 2 qualify or when `settings.show_frequent` is false.
- `favorites_body`:
  - Below the grid (as extra trailing item(s) of the same `uniform_list`, so nothing above moves),
    draw a `footnote` label "Frequent" and one row of up to four flat 56 px avatar columns with
    names. Use `person_avatar`; no glass.
  - Tapping one opens the Contact sheet for a contact, or the Number sheet for a bare number.
    It never places a call.
  - With an empty Favorites grid, the empty state stays and Frequent follows it when present.
- `settings_ui.rs`:
  - Add `RowId::ShowFrequent` (`"set-frequent"`, `COUNT` 25 to 26) as a Toggle, "Show Frequent",
    note "People you call most, under Favorites", in the Display section.
  - Wire it in the toggle handler via `update_settings(|s| s.show_frequent = !s.show_frequent)`
    and the S25 snap.
  - Extend the `build_sections` tests to check that the row exists.

Acceptance:
- With 2 or more frequent non-starred numbers, the row shows under Favorites. Turning the
  setting off hides it at once.

### P6. Keypad and Recents: Add to contacts, Message, and WhatsApp call-back (fixes G6, G7, G8, G11)
- `keypad.rs`:
  - Pure `fn offer_add(dialed: &str, settled: bool, found: bool, status_none: bool) -> bool`
    (true for a valid number, a settled lookup (`dial_match.query == dialed && task.is_none()`),
    no match and no status). Tested.
  - When true, the 36 px status line shows two text buttons, "Add to contacts" (`kp-add`, calls
    `backend::add_contact`) and "Message" (`kp-msg`, calls `backend::send_sms_to`).
    - A failure sets the status to `why_not("Couldn't open the Contacts app")`.
    - The slot height does not change.
  - Move the WhatsApp chip into the same row when both show, so nothing overlaps. Otherwise it
    keeps its place.
  - Add `kp-add` and `kp-msg` to `activate`; they act on finger-up, not to `acts_on_down`.
- `block_ui.rs` row menu: add "Add to contacts" (`rm-add`, `LucideUserPlus`) after Copy number
  when the row is not named (add `named: bool` to `MenuTarget` from `RowVm.named`). The menu
  height follows `rows.len()` already.
- `recents.rs::recents_call`:
  - Use pure `fn call_back_on_whatsapp(row_whatsapp: bool, connected: bool) -> bool`. When true,
    call `place_whatsapp(&number, cx)` instead of the carrier, with no SIM chooser and no flight.
    Tested.
  - The row aria for WhatsApp rows gets ", WhatsApp" (check `rovsti_core::recents::build`;
    extend it there with a test if the word is missing).
- Fix the stale comment at the long-press timer ("Holding a row opens its menu").

Acceptance:
- Typing an unsaved valid number shows "Add to contacts" and "Message". A saved number shows
  the name instead.
- Tapping a missed WhatsApp row starts a WhatsApp call.

### P7. Undo for the small destructive actions (fixes G9, G10)
- `block_ui::PillAction`:
  - Add `RestoreSpeedDial(u8, SpeedDialEntry)` (derive or impl `PartialEq`/`Eq` as needed) and
    `RestoreReply(usize, SharedString)`.
  - Handle them in `pill_fired` (`speed_dial_write(digit, Some(entry))`, and set the field text
    at that slot, then `replies_field_changed`).
- `speed_dial_ui.rs` REMOVE_ID: read the entry before unassigning, then
  `show_pill_action("Removed speed dial {d}", "Undo", RestoreSpeedDial(..))`.
- `replies_ui.rs` CLEAR branch: keep the old text, and when it was non-empty, show
  `show_pill_action("Reply cleared", "Undo", RestoreReply(slot, old))`.
- Tests: the pure `speed_dial::assign` round-trip (remove then re-assign gives the same map) in
  `speed_dial_ui.rs` tests.

### P8. Docs and plan status
- `docs/phone-acceptance.md`: add rows for P2 to P7 (quick actions, chooser, recent calls,
  favorites order, Frequent, keypad Add/Message, WhatsApp call-back, Undo pills).
- `docs/roadmap.md` (Rovsti Phone): tick "Favorites order and Frequent", "Quick actions",
  "Call history in sheets". Leave an unticked "Delete call history (needs `CallLogWrite`, spike
  S12)".
- `apps/phone/_bmad-output/implementation-artifacts/sprint-status.yaml`: move 19-1, 19-3, 19-5
  and 19-6 to `review`. Leave 21-x as is.
- Append a "Status" section to this file with the commits.

## Next round (candidates)
- G12: `CallLogWrite.delete(ids)` / `restore(entries)` / `deleteAll()` in the shared Kotlin, a
  `rovsti_android::providers` mirror (desktop `Unsupported` + tests), Recents menu "Delete entry"
  with Undo, and Settings > Data "Delete all call history" behind a confirm sheet.
- Re-audit after P1 to P8.
