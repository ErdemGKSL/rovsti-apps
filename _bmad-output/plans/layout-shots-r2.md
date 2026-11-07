# Unit `shots`, round 2: the harness is done; the user's actual request is the text fields

baseSha: d073c57a9c5f1dd7be78a6ca4aa21041ad714ace

## Status of the harness (verified this round)

- `crates/rovsti_ui` feature `shots`, per-app `shots` features and `src/shots.rs` modules,
  `docs/screenshots.md` and `docs/screenshots/baseline/{phone,messages,contacts,stops}.png`
  are all committed (commits aa4ae28..d073c57). The Baseline list is in `layout-shots.md`.
- target/shots/{phone,messages,contacts,stops,rovsti_ui} hold the full sets (13 scenes x
  dark/light + `-small`, plus `contact_sheet.png`), written at 16:35-16:50 today, i.e. at HEAD.
- Opened with Read: `target/shots/rovsti_ui/01-fields-dark.png`,
  `target/shots/messages/06-composer-typing-dark.png`, `docs/screenshots/baseline/stops.png`.
  All show the real UI (glass, Turkish text, tab bar, map placeholder); none blank.
- The harness goal of the computed task is therefore met.

## Why this round still plans work

The user's own message for this run is: *"bide Allah için şu text boxların mekaniğini düzgün
kodla htmldeki varsayılan input bile daha iyi çalışıyo"* ("for God's sake code the mechanics of
these text boxes properly, even the default HTML input works better"). The workflow text
rephrased it as a layout overhaul; it is not. Round 1 already deferred it ("a dedicated unit
should do it next") and no later unit in the workflow covers it. The relayed request wins, so
the plan below fixes the text-field mechanics, using the harness as proof.

## What is wrong (read in code, seen in the gallery)

Three copies of the same broken field: `crates/rovsti_ui/src/field.rs` (TextField, used by
Messages, Stops, rovsti_wa_ui), `apps/contacts/src/field.rs` (with a multiline Config) and
`apps/phone/src/field.rs` (SearchField). In all of them:

1. `selected_text_range` always returns `end..end`; `set_selected_text_range` is not
   implemented (trait default no-op). gpui_android (`crates/gpui_android/src/input.rs`)
   calls `set_selected_text_range` after **every** commitText/setComposingText (ops 0/1/7) and
   for setSelection (op 3), and setComposingRegion (op 4) re-marks a range and restores the
   selection. With our no-op, the keyboard's idea of the cursor and ours diverge: autocorrect,
   swipe replacement, cursor-drag on the space bar and mid-word edits misbehave.
2. `character_index_for_point` always answers the end: a tap cannot place the caret.
3. `bounds_for_range` returns the whole box: IME candidate/cursor anchor is wrong.
4. `surrounding_text` claims the caret is at the end; `delete_surrounding_text` refuses
   `after > 0`.
5. No selection, no select-all/cut/copy/paste, no double-tap word, no long-press menu, no
   arrow/Home/End, delete key removes the last char even when the caret is elsewhere.
6. Rendering: fixed 2x20 dp bar appended after the text, no blink, start ellipsis instead of
   scrolling to the caret (gallery row 4), composing text has no underline (row 6), the
   secret field asks the keyboard for SingleLine instead of `TextInputMode::Password`.
7. The Messages composer is single-line: a long message shows "...a, çilekli olsun lütfen!"
   (06-composer-typing-dark.png); line breaks are replaced with spaces.

GPUI APIs available at the pinned rev (grepped, not invented): `EntityInputHandler::
set_selected_text_range`, `element_bounds`, `scroll_text_input`,
`replace_and_mark_text_with_selection` (`crates/gpui/src/input.rs`); `TextInputMode::
{SingleLine, Multiline, Password}`; `window.text_system().shape_line` + `ShapedLine::
{x_for_index, closest_index_for_x}`; `shape_text` + wrapped `position_for_index /
closest_index_for_position` (`text_system/line_layout.rs`); `div().on_long_press`
(`LongPressEvent`); `cx.write_to_clipboard / read_from_clipboard` (gpui_android has
`clipboard.rs`); `examples/input.rs` is a complete reference input (selection, mouse, actions).

## Steps

### 1. Pure edit model in `rovsti_core::text_edit`
- New `crates/rovsti_core/src/text_edit.rs`: `EditBuffer { text, anchor, head, marked,
  multiline }` in UTF-8 bytes, always on grapheme boundaries. Ops: `replace(range, text)`,
  `replace_and_mark(range, text, sel_in_new)`, `set_selection_utf16(range, reversed)` (clamped),
  `selection_utf16()`, `move_left/right(extend)` by grapheme, `word_left/right(extend)`,
  `home/end(extend)`, `select_all`, `word_at(byte)` (double-tap), `delete_backward`
  (selection first, else previous grapheme), `delete_forward`, `delete_surrounding(before16,
  after16)` around the selection, `surrounding()`, utf8<->utf16 helpers, newline policy
  (single line: `\n`/`\r` -> space; multiline keeps `\n`, normalises `\r\n`).
- Add `unicode-segmentation = "1.10"` to `[workspace.dependencies]` and rovsti_core.
- Unit tests: Turkish (İ, ğ, ş), emoji ZWJ and flags, surrogate pairs in UTF-16 offsets, edit
  inside the marked range, delete_surrounding both sides, reversed selection, out-of-range
  clamps.
- Acceptance: `cargo test -p rovsti_core text_edit` green; all checks green.

### 2. `rovsti_ui::field::TextField` on the model (handler correctness)
- Replace the ad-hoc state in `crates/rovsti_ui/src/field.rs` with `EditBuffer`. Keep the public
  API (`new`, `with_input`, `set`, `text`, `clear`, `backspace`, `edits`, `actions`,
  `set_keep_focus_on_action`, `field_box`, `secret_field_box`, `masked`). Add `selection()`,
  `select_all`, `set_selection` for tests.
- Implement `selected_text_range` (real range + `reversed`), `set_selected_text_range`,
  `replace_text_in_range` / `replace_and_mark_text_in_range` honouring the selection when
  `range` is None, `replace_and_mark_text_with_selection`, `surrounding_text` with the real
  selection, `delete_surrounding_text` both sides, `text_input_mode` = Password for secret
  fields (field gets a `secret` flag instead of only the box knowing), `element_bounds`.
- Key handling on the box: left/right (+shift), home/end, backspace/delete via the model,
  ctrl/cmd-A/C/X/V. `backspace()` deletes the selection or the grapheme before the caret.
- `#[gpui::test]`s: simulate the Android op sequence (commit "kar" then set_selected 2..2,
  insert mid-text, setComposingRegion over a word, delete_surrounding(1,1)).
- Acceptance: tests green; Messages/Stops/WA call sites compile unchanged.

### 3. Rendering: caret, selection, scroll-to-caret, marked underline, real geometry
- Draw the text with a small custom element (or `canvas`) that shapes the line with
  `window.text_system().shape_line` at `typography::BODY`; cache the `ShapedLine` and the
  content origin in the field for `bounds_for_range` (caret rect 2 x line-height dp at
  `x_for_index`) and `character_index_for_point` (`closest_index_for_x` + scroll offset).
- Horizontal scroll: keep the caret at least 12 dp inside the 16 dp padded content box; no
  ellipsis while focused; unfocused keeps end ellipsis (`truncate`). Implement
  `scroll_text_input` for the horizontal offset.
- Selection fill `accent` at 28 % alpha, corner 3 dp; marked text underlined 1.5 dp in label
  colour; caret 2 dp x 22 dp, `accent`, blinks 530 ms on/off, solid for 500 ms after any edit
  or move (timer via `cx.spawn`; a `rovsti_ui` flag turns blink off under `shots`).
- Acceptance: gallery rows 4 and 6 change (URL scrolled to caret without ellipsis, composing
  text underlined) in `target/shots/rovsti_ui/01-fields-{dark,light}.png`.

### 4. Touch: tap, double-tap, long-press, drag, edit menu
- Tap on an unfocused field: focus, caret at the tapped index (not the end), show keyboard.
  Tap in a focused field: move caret; tap on the caret itself toggles the edit menu.
- Double-tap: select the word (`word_at`). Long-press (`on_long_press`): select the word and
  open the menu; on an empty field open the menu with Paste only. Drag after long-press
  extends the selection by `closest_index_for_x`, auto-scrolling at the edges.
- Selection handles: two 22 dp teardrops under the selection ends, draggable (44 dp hit
  target); a single insertion handle under the caret for 3 s after a tap.
- Edit menu: a glass capsule (`GlassSpec::control`, height 40 dp, 8 dp above the field, kept
  on screen) with Cut, Copy, Paste, Select all (Turkish labels per the app's string tables;
  Copy/Cut hidden without a selection and for secret fields; Paste hidden with an empty
  clipboard). Uses `cx.write_to_clipboard` / `read_from_clipboard`.
- Tests: tap index mapping, word selection, menu item set per state (pure helper).

### 5. Multiline mode and the Messages composer
- `TextField::multiline(min_lines, max_lines)`: `TextInputMode::Multiline`, Enter inserts
  `\n` (no action key), wrapped layout via `shape_text`, caret/selection via
  `position_for_index` / `closest_index_for_position`, grows 1..=6 lines (line height 22 dp)
  then scrolls vertically to the caret.
- Use it for the Messages composer (`apps/messages/src/conversation.rs`,
  `sms_conversation.rs`; send stays on the send button; capsule radius becomes 22 dp when
  more than one line) and for the WA composer in `crates/rovsti_wa_ui/src/view.rs`.
- Acceptance: `target/shots/messages/06-composer-typing-dark.png` shows the long message on
  3-4 wrapped lines with the caret at the end; the list above shrinks accordingly.

### 6. One field for every app
- Port `apps/contacts/src/field.rs` (Config SEARCH/TEXT/PHONE/EMAIL/NOTE -> `with_input` /
  `multiline`) and `apps/phone/src/field.rs` (SearchField) to `rovsti_ui::field`; delete the
  two copies and update call sites (contacts editor.rs, ui.rs, groups.rs, picker.rs; phone
  search). Phone keypad number display stays its own (not an IME field).
- Acceptance: `grep -rn "impl EntityInputHandler" apps crates` lists only
  `crates/rovsti_ui/src/field.rs`; contacts 06-editor and phone 06-contacts-search-typing
  shots unchanged in layout.

### 7. Gallery states and fresh shots
- Extend `STATES` in `crates/rovsti_ui/src/shots/fields.rs`: caret mid-text, selected word
  with handles, edit menu open, long URL scrolled to the caret, composing underline,
  multiline 3 lines, secret (Password mode), empty field with Paste-only menu.
- Re-run all five shot suites (commands in docs/screenshots.md), open
  `01-fields-{dark,light}.png`, `messages/06-composer-typing-dark.png`,
  `contacts/06-editor-dark.png`; refresh `docs/screenshots/baseline/*.png`.

### 8. Docs and checks
- `docs/architecture.md`: the field lives in `rovsti_ui::field` over `rovsti_core::text_edit`.
  `docs/screenshots.md`: the new gallery states and the blink-off flag.
- All standard checks plus the four `--features shots` clippy runs and the three Android
  `cargo check`s.

## Baseline

Unchanged from round 1 (see `layout-shots.md`); confirmed present this round under
target/shots/{phone,messages,contacts,stops,rovsti_ui}/ and docs/screenshots/baseline/.
