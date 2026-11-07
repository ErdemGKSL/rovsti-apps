# Unit `shots`, round 3: native Android text selection, finger pan, undo

baseSha: e3b623081adfc6ff90bf67d48abde9a6c3614ddd

## Status

- **Harness: done.** `rovsti_ui` feature `shots`, per-app `shots` features, `src/shots.rs`
  modules, `docs/screenshots.md`, `docs/screenshots/baseline/{phone,messages,contacts,stops}.png`
  are committed. target/shots/{phone,messages,contacts,stops,rovsti_ui} were rendered at
  18:31-18:34 today; since then only `crates/rovsti_ui/src/shots.rs` changed (8 lines, review
  fix), so they reflect HEAD. Baseline file list: `layout-shots.md`, section Baseline.
- **Round 2 (text fields): implemented** in 8aae8fc..e3b6230: `rovsti_core::text_edit::EditBuffer`,
  one `EntityInputHandler` (`crates/rovsti_ui/src/field.rs`, the only impl in the workspace),
  shaped-line rendering, scroll-to-caret, marked underline, Password mode, multiline composer,
  drawn handles and edit menu, Contacts/Phone ported.
- Opened with Read this round:
  - `target/shots/rovsti_ui/01-fields-dark.png`: all 13 states correct (caret mid-text with
    handle, word selection, menu, long URL scrolled to caret with no ellipsis, composing
    underline, 3-line multiline, masked secret).
  - `target/shots/rovsti_ui/02-field-touch-light.png`: tap handle, long-press menu,
    Paste-only, secret without Cut/Copy.
  - `target/shots/messages/06-composer-typing-dark.png`: composer wraps to 3 lines, caret at end.
  - Small desktop-only flaw seen: the Paste-only menu (gallery row 13 and touch row 3) is
    centred on a caret at x=0 and hangs 8 dp left of the field edge.

## Why there is still work (the user's request wins)

The user's own message for this run asks for text-box mechanics that work at least as well as an
HTML input. The computed task's layout harness is done; the request is not, because of what
happens **on the device**, which the headless shots cannot show:

1. **Two insertion handles on every tap.** The Android host (`apps/*/platforms/android/host/
   .../GpuiView.kt` `performClick` -> `syncInput` -> `TextEditMenu.tapped`) shows the native
   insertion handle whenever a tap lands in `element_bounds` with a collapsed selection. Our
   field also paints its own teardrop for 3 s (`show_insertion`, `paint_handles`). Both are
   draggable and fight each other.
2. **The native selection UI is switched off by us.** `touch_layer` claims every
   `LongPressEvent` with `window.prevent_default()`, so `TextEditMenu.longPress` never runs: no
   Android floating toolbar, no theme handles, no magnifier while dragging (API 28+), no
   locale-aware `BreakIterator` word selection, no autofill entry. The field never handles
   `TextInputFocusEvent`, which the host needs for that path
   (`gpui_android/docs/hosting.md` "Text input"; reference: `uic/src/components/input/element.rs`
   lines 295-310 and `state.rs::focus_at`). This is exactly what Chrome's input uses, which is
   why "the default HTML input works better".
3. **A finger drag in a field scrolls the page, not the text.** The host turns a drag past
   touch slop into `ScrollWheelEvent`s; the field has no `on_scroll_wheel`, so a long URL or a
   tall composer cannot be panned by finger (an HTML input can).
4. **No undo/redo** (Ctrl/Cmd+Z, Ctrl+Shift+Z, Ctrl+Y) for hardware keyboards and desktop.

GPUI/host APIs used below were grepped at d6c8412, not invented: `TextInputFocusEvent { position }`
(`gpui/src/interactive.rs:156`, a mouse event for `window.on_mouse_event`), `LongPressEvent`,
`window.prevent_default/default_prevented`, `ScrollWheelEvent { position, delta, .. }` and
`ScrollDelta::pixel_delta(line_height)` (`interactive.rs:554/586/646`), `div().on_scroll_wheel`
(`elements/div.rs:362`), `VisualTestContext::simulate_event<E: InputEvent>`
(`app/test_context.rs:909`). Host menu items arrive as key "a"/"c"/"x"/"v" with modifier bit 2 =
`control` (`gpui_android/src/bridge.rs:1431`), which `TextField::handle_key` already accepts.

## Steps

### 1. Selection-chrome policy in `rovsti_ui::field`
- Add `pub enum SelectionChrome { Native, Drawn }` and `pub fn selection_chrome() ->
  SelectionChrome`: `Native` when `cfg!(target_os = "android")`, else `Drawn`; plus
  `pub fn set_selection_chrome(Option<SelectionChrome>)` (an atomic/thread-local override, like
  `set_blink`) for tests.
- Pure helper `fn chrome_for(policy, gesture) -> ChromeAction` covering: tap, double tap, tap on
  caret, long press, empty-field long press. Unit tests for the table.
- No behaviour change yet (Drawn everywhere on desktop). Acceptance: checks green.

### 2. Native path: hand Android its own selection UI
In `crates/rovsti_ui/src/field.rs`, when `selection_chrome() == Native`:
- `touch_layer`: register `window.on_mouse_event(|e: &TextInputFocusEvent, phase, ..|)`; when
  `phase.bubble()` and `bounds.contains(&e.position)`: focus, `buf.unmark()`, caret at
  `byte_at(e.position)` (reset `scroll` to 0 first if the field was unfocused), `cx.notify()`,
  then `window.prevent_default()` and `cx.stop_propagation()`. `LongPressEvent`: only
  `cx.stop_propagation()` (do **not** prevent default, do not call `long_press`) so
  `TextEditMenu.longPress` -> `focusTextInput` -> `selectWord` -> `ActionMode` runs.
  `TouchEvent`: return without claiming (no drawn handles exist).
- `tap`: single tap places the caret, double tap selects the word; never `show_insertion`,
  never toggle the drawn menu.
- `paint_handles`, `handle_shields`, `edit_menu` render nothing.
- Keep `element_bounds` = the visible content rect (the host's `hit` test and handle clamping
  use it) and `bounds_for_range` = 2 dp x line-height caret rect (native handle anchors).
- Desktop/shots stay `Drawn`, so the galleries do not change.
- `#[gpui::test]`s with `set_selection_chrome(Some(Native))`: `simulate_event(TextInputFocusEvent)`
  at the x of byte 5 focuses the field and puts the caret at 5; a `LongPressEvent` leaves the
  selection and menu untouched; a tap leaves `insertion_visible()` false; menu shortcuts
  ctrl-a/c/x/v (`simulate_keystrokes("ctrl-a")` etc.) still work.
- Acceptance: tests green; `cargo check -p rovsti_android --target aarch64-linux-android` and
  the two other Android checks green; `target/shots/rovsti_ui/0{1,2}-*.png` re-rendered and
  pixel-identical to before (Drawn on desktop).

### 3. Finger pan inside a field
- Pure fn in field.rs `pan(scroll: f32, delta: f32, content: f32, viewport: f32, margin: f32)
  -> (f32, bool)` reusing `clamp_scroll` / `clamp_scroll_y`; `bool` = consumed (false when
  already at the edge in that direction, so the page scrolls on, like a browser).
- `on_scroll_wheel` on both shells in `field_box_with` (glass box and bare): single-line uses
  the dominant axis of `event.delta.pixel_delta(typography::BODY.line)` (`delta.x`, or `delta.y`
  when |y| > |x| and the text overflows); multiline uses `delta.y` on `scroll_y`. Mark
  `seen_activity = activity` so scroll-to-caret does not snap back; `cx.stop_propagation()` only
  when consumed. Focused or not (an unfocused truncated field keeps its ellipsis and ignores pans).
- Tests: `pan` table (start, middle, both edges, content shorter than viewport); a
  `#[gpui::test]` simulating `ScrollWheelEvent` on a long URL moves `scroll` and stops at the
  end; on a short text it is not consumed.
- Acceptance: add gallery state 14 "Long URL panned to the start while focused" in
  `crates/rovsti_ui/src/shots/fields.rs` (state set by the same scroll call); visible in
  `01-fields-{dark,light}.png`.

### 4. Undo / redo in `rovsti_core::text_edit`
- `pub struct History { undo: Vec<Snapshot>, redo: Vec<Snapshot>, open: Option<Kind> }`,
  `Snapshot { text, anchor, head }`, cap 100. `record(kind, &EditBuffer)` before each edit;
  consecutive `Insert` of non-whitespace graphemes and consecutive `Delete` coalesce; a caret
  jump, paste, cut, newline or selection replace closes the group. Marked (composing) text is
  not recorded until `unmark`/commit, so IME composition is one step.
- `TextField::mutate` records; `handle_key`: ctrl/cmd-z undo, ctrl/cmd-shift-z and ctrl-y redo;
  `set`/`clear` from the app reset the history.
- Unit tests in rovsti_core (Turkish words coalesce per word, paste is one step, composition
  is one step, redo cleared by a new edit, cap). One `#[gpui::test]` for the keys.
- Acceptance: `cargo test -p rovsti_core text_edit` and `-p rovsti_ui field` green.

### 5. Drawn menu stays over its field (desktop polish)
- In `edit_menu` clamp the capsule's left to `max(field_left, viewport_left + 8 dp)` and its
  right to `min(field_right, viewport_right - 8 dp)` when the field is wider than the menu.
- Acceptance: in `01-fields-*.png` row 13 and `02-field-touch-*.png` row 3 the Paste pill
  starts at the field's left edge (16 dp), not 8 dp outside it.

### 6. Docs
- `docs/architecture.md` (field section): Android uses the host's native handles, toolbar,
  magnifier and word selection via `TextInputFocusEvent`; desktop draws its own; pan; undo.
- `docs/android.md`: a device checklist for the owner (the only proof available without a
  device): tap mid-word places the caret with ONE handle; long-press shows the Android toolbar
  with Cut/Copy/Paste/Select all and the magnifier while dragging a handle; Gboard space-bar
  cursor drag and swipe-replace mid-text; autocorrect of a word in the middle; paste; finger
  pan of a long URL in Stops search; composer grows to 6 lines then scrolls; secret field
  shows no Copy/Cut.
- `docs/screenshots.md`: gallery state 14 and the `set_selection_chrome` note.

### 7. Fresh shots and full checks
- Re-run the rovsti_ui and four app shot suites (`docs/screenshots.md` commands; check
  `df -h .` first). Open `rovsti_ui/01-fields-dark.png`, `rovsti_ui/02-field-touch-light.png`,
  `messages/06-composer-typing-dark.png`, `contacts/06-editor-dark.png` with Read. Baselines in
  `docs/screenshots/baseline/` change only if step 5 altered a scene in them.
- All standard checks, the `--features shots` clippy runs, and the three Android
  `cargo check`s.

## Baseline

Unchanged from round 1 (full file list in `layout-shots.md` under Baseline):
target/shots/{phone,messages,contacts,stops}/ (13/13/13/12 scenes x dark+light, `-dark-small`
of scene 01, `contact_sheet.png`), target/shots/rovsti_ui/{00-smoke,01-fields,02-field-touch}
-{dark,light}.png, docs/screenshots/baseline/{phone,messages,contacts,stops}.png.
