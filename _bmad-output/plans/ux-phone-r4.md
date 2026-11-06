# UX completeness pass: Rovsti Phone (round 4)

Base: `0415c282fc45280618b7d02eb8211e6cf931ed55` (main, clean tree, no stash).
Scope: `apps/phone`, `crates/rovsti_core`. No new product features and no backends. Intent comes
from the app's own planning (`apps/phone/_bmad-output/planning-artifacts/epics.md` story 20.1
"Results sheet" and its permission footnote; 16.6 backup; 16.7/16.8 settings) and from code that
already exists (`ContactIndex::t9` returns up to `t9::MAX_HITS` = 50 hits, `Backend::update_settings`,
`Settings: Default + PartialEq`, `ImportPreview::apply_to`, `PrefsV2` sections).

Rounds 1 to 3 (`ux-phone.md`, `ux-phone-r2.md`, `ux-phone-r3.md`) closed G1-G11, R1-R4, T1-T8:
sheet actions, history, favorites order, Frequent, delete call history (row, one call, sheet, all,
blocked), speed dial on the Contact sheet, voicemail glyph, search number actions, Setup undo and
"Something changed". This round re-audits the parts earlier rounds did not walk: the Keypad's T9
strip, the WhatsApp call notices, Backup and the Settings data rows.

## Re-audit: gaps that remain

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| U1 | **Medium** | **"N more" on the Keypad loses the matches it promises.** The T9 strip shows three names and "4 more contacts"; tapping it puts the digits into Contacts search, but `ContactIndex::search` treats a digit query as a number substring (plus literal name match), not T9. "2973" (Ayşe) finds nothing there, so the extra matches are unreachable. Story 20.1: "+n opens the Results sheet (medium detent, up to 50 rows with highlighted matches) whose rows fill the field and whose call circles call". | `keypad.rs::t9_more`, `T9Rows`, `SheetKind` |
| U2 | Medium | **"WhatsApp is not connected" is a dead end.** Tapping a WhatsApp row in Recents (or its call-back) while the gateway link is gone shows the danger notice with no action (`Notice::action` is `None` for `WhatsApp(_)`). The user has to know the link lives in Settings > WhatsApp. | `notice.rs::Notice::action/notice_act`, `wa_call.rs::can_place/place` |
| U3 | Low | **The T9 strip is silent without contacts access.** With contacts denied, typing digits shows no names and no hint. Story 20.1: a one-time per-session footnote "Allow Contacts to see matches" with "Grant access". | `keypad.rs::status_line`, `t9_strip` |
| U4 | Low | **Import cannot be taken back.** "Import" replaces settings, quick replies, speed dial and favorites order; the pill "Backup imported" has no Undo, although every other replace in Phone (speed dial, replies, block, delete) has one. | `backup_ui.rs::import_confirm`, `rovsti_core::settings::ImportPreview` |
| U5 | Low | **Settings have no reset.** Calls, Sounds and haptics and Display toggles can only be put back one by one (quick replies have "Reset to defaults", the rest has nothing). | `settings_ui.rs` (Data section), `Backend::update_settings` |
| U6 | Info (not planned) | Same as round 3 T9 row: Delete contact via "Edit in Contacts"; contact ringtone gated (`Caps.CONTACT_RINGTONE = false`); in-app editor 19.7 is P3; Call details page and quality chips (21.1, 21.4) need seams and feature bits; emergency labelling (20.4) and voicemail badge need device spikes; S9/S12 device checks PENDING. Call on an empty Keypad field shakes by design (story 4.2: "with an empty field the call is not placed"); there is no redial. Missed-call notifications are the system's by design (story 5.5, PRD Q4). Diagnostics is hidden by design (long-press the Settings title). The "Missed call from {name}" pill after a missed waiting call stays without Call back (Recents has it one tap away and the user is still in a call). | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(`df` showed 6.1 GB free at planning time; if it shows under 6 GB, `rm -rf target/debug/incremental` first).
Never invent GPUI APIs: reuse only what these files already call (`modal_sheet`, `touch_surface`,
`overflow_y_scroll`, `uniform_list`, `show_pill_action`, `show_notice_pill`, `presses`).

### S1. Pure logic (+tests)
`crates/rovsti_core/src/settings.rs`:
- `impl ImportPreview { pub fn restoring(prefs: &PrefsV2) -> Self }`: a preview whose `clean`
  sections are a copy of `prefs.settings`, `quick_replies`, `speed_dial`, `favorites_order`; all
  counts 0, `moved` empty, `clamped` false. Applying it with `apply_to` puts those four sections
  back and leaves `prefs.ui` alone (the Undo snapshot of an import).
- Tests: snapshot, change all four sections and `ui.last_tab`, apply: the four sections equal the
  snapshot, `ui.last_tab` keeps the changed value; a snapshot of `PrefsV2::default()` applies cleanly.

`apps/phone/src/wa_call.rs`:
- `pub(crate) const NOT_CONNECTED: &str = "WhatsApp is not connected";` used by `can_place`,
  `place`, `answer` and the `fail(...)` calls that say it today (no text change; existing tests keep passing).

`apps/phone/src/notice.rs`:
- `Notice::action`: `Self::WhatsApp(why) if why == crate::wa_call::NOT_CONNECTED => Some("Connect")`;
  other WhatsApp reasons stay `None`.
- Unit test module in `notice.rs`: `WhatsApp(NOT_CONNECTED).action() == Some("Connect")`,
  `WhatsApp("Another call is in progress").action() == None`, `Preview` → "Fix", `NeedsPhone` → "Grant".

`apps/phone/src/keypad.rs` (pure helpers, tested in the existing test module):
- `pub(crate) fn results_title(digits: &str, count: usize) -> String`: "1 match for {digits}" /
  "{count} matches for {digits}".
- `pub(crate) fn contacts_hint_shown(denied: bool, digits_typed: bool, dismissed: bool) -> bool`:
  true only when all of `denied`, `digits_typed`, `!dismissed`.

### S2. Keypad: T9 Results sheet behind "N more" (fixes U1)
- `main.rs::SheetKind` gains `T9Results` (doc: "Every T9 match of the typed digits (story 20.1)").
  Its `modal_sheet` label is "Matches"; add it to the `match self.sheet_kind` in the overlay render.
- `keypad.rs`: extract the per-hit body of `t9_rows`' `filter_map` into
  `fn suggestion_of(index: &ContactIndex, hit: &T9Hit, format: NameFormat) -> Option<Suggestion>`
  (same output as today). New `T9Match.all: Arc<[Suggestion]>` built lazily by
  `fn t9_all_rows(&mut self, cx)` from every `self.t9.hits` (≤ `t9::MAX_HITS`), keyed like `T9Rows`
  (`RowsKey`), so it is never rebuilt per frame.
- `t9_more`: no longer prefills Contacts. It builds `t9.all`, sets `sheet_kind = T9Results` and
  opens the sheet when `can_open_sheet()`. The `t9-more` aria stays "{n} more contacts".
- `fn t9_results_sheet(&mut self, theme, window, cx) -> Option<AnyElement>` (new, in `keypad.rs`):
  a header line `results_title(&self.dialed, all.len())`, then a scrolling column
  (`overflow_y_scroll`, or `uniform_list` like Recents if row count matters) of 58 px rows reusing
  the strip's row drawing (avatar monogram/hue, name with `span` in weight 700, number, trailing
  36 px answer-colour call circle). Ids: row `sheet-t9-{i}`, call circle `sheet-t9c-{i}`; aria as in
  the strip ("Best match" wording is only for the strip).
  - Row tap (finger-up): fill the field with the row's number (`self.dialed = number`, the same
    path the strip's fill uses, clear status, refresh match), close the sheet.
  - Call circle (finger-up): close the sheet, `place_call_from(&number, window, cx)`.
  - Route `sheet-t9` ids from the same sheet dispatcher the other sheets use (see `history_ui.rs`
    button pattern: `on_mouse_down` → `presses.down`, `touch_surface(Self::touch_listener(..))`).
    Pure `pub(crate) fn t9_result_index(id: &str) -> Option<(usize, bool)>` parses
    `sheet-t9-{i}` → `(i, false)` and `sheet-t9c-{i}` → `(i, true)`; test valid, wrong prefix,
    non-number.
- When the typed digits change while the sheet is open (not possible from the sheet itself, but a
  hardware key could): close it if `t9_digits(&self.dialed)` is `None`.

Acceptance: desktop sample contacts, type 2 6: strip shows 3 names and "N more"; tap it: "Matches"
sheet lists every hit with highlighted letters; tap a row: the field holds that number and the
sheet closes; tap a call circle: the call is placed.

### S3. WhatsApp "not connected" notice gets "Connect" (fixes U2)
- `notice.rs`: `notice_act(&mut self, window: &mut Window, cx)` (the listener already has
  `window`). New arm `Some(Notice::WhatsApp(why)) if why == wa_call::NOT_CONNECTED`:
  `self.call_note = None; self.settings_open(window, cx); if self.settings.is_open() {
  self.settings_open_sub(settings_ui::Sub::WhatsApp, window, cx) }` (the same two calls as
  `speed_dial_open_page`).
- `main.rs` WhatsApp error observer: the pill path over call screens stays as is (the call screen
  covers Settings anyway).

Acceptance: desktop preview without a gateway link: Recents > a WhatsApp row: the notice reads
"WhatsApp is not connected" with "Connect"; tapping it opens Settings > WhatsApp.

### S4. Keypad: "Allow Contacts to see matches" (fixes U3)
- `PhoneApp.t9_hint_dismissed: bool` (session only, not persisted).
- `keypad.rs::status_line`: when `self.status` is `None` and
  `contacts_hint_shown(matches!(backend.contacts(), Load::PermissionDenied), !self.dialed.is_empty(), self.t9_hint_dismissed)`,
  draw the footnote "Allow Contacts to see matches" with the same "Grant access" text button shape
  as `Status::NeedsPhone` (id `kp-ct-grant`, finger-up). The status slot keeps its 36 px height, so
  no key moves.
- `kp-ct-grant`: set `t9_hint_dismissed = true`, then the `contacts_pill("cs-grant", ..)` path
  (request, or app details when permanently denied). Add `kp-ct-grant` to `activate` (not
  `acts_on_down`), next to `kp-vm-set`.

### S5. Undo for Import (fixes U4)
- `backup_ui.rs::import_confirm`: before `apply_import`, take
  `let undo = ImportPreview::restoring(self.backend.read(cx).prefs())`; after it, replace the plain
  pill with `self.show_pill_action("Backup imported", "Undo", PillAction::RestorePrefs(Box::new(undo)), cx)`.
- `block_ui.rs::PillAction` gains `RestorePrefs(Box<rovsti_core::settings::ImportPreview>)`
  (`ImportPreview` already derives `Clone`). Its handler: `backend.apply_import(*preview, cx)`, `sync_settings_rows(cx, false)`,
  pill "Import undone".

### S6. Settings > Data: "Reset settings" with Undo (fixes U5)
- `settings_ui.rs`: `RowId::ResetSettings`, a `Control::Danger` row "Reset settings" after
  "Delete all call history", with note "Calls, sounds and display go back to their defaults".
  Disabled (`row.disabled("Already the defaults")`) when the key's settings equal
  `Settings::default()` (add `settings_default: bool` to the rows key; it is rebuilt when settings
  change, never per frame).
- Tap: `let old = Settings::clone(&self.backend.read(cx).settings());` →
  `backend.update_settings(cx, |s| *s = Settings::default())` →
  `show_pill_action("Settings reset", "Undo", PillAction::RestoreSettings(Box::new(old)), cx)`;
  Undo runs `update_settings(cx, |s| *s = *old)`. Quick replies, speed dial, favorites order are not
  touched (they have their own reset/remove).
- Tests: extend the Data section test (`ids` now ends with `RowId::ResetSettings`; with
  `ImportError` it is at index 4); a key with default settings gives a disabled reset row.

### S7. Docs and plan status
- `docs/phone-acceptance.md`: rows for S2 (Results sheet), S3 (Connect), S4 (contacts hint),
  S5 (Import Undo), S6 (Reset settings).
- `apps/phone/_bmad-output/implementation-artifacts/sprint-status.yaml`: 20-1 stays `backlog`
  unless every 20.1 AC is now met (the S32 morph and the 56 px strip slot are separate); no other changes.
- Append a "Status" section to this file with the commits.

## Next round (candidates)
- Re-audit after S1 to S7. Expected to leave only U6 items (device spikes, gated or P3 by design).
