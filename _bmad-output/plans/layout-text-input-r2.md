# Unit `text-input`, round 2

baseSha: e2af4a1dfdc92d77067409d4f0504602d5e2b1fe

## Verdict: done

All three round-1 steps landed (747efb1 max length in `EditBuffer`, 7b8b8c2
`TextField::set_max_chars` + Phone quick replies, e2af4a1 gallery state 15, docs, shots).
Working tree clean, no stash.

## Audit at HEAD

- One input implementation: `crates/rovsti_ui/src/field.rs` is the only
  `EntityInputHandler` in the workspace; every field in Phone (7 sites), Messages,
  Contacts, Stops and `rovsti_wa_ui` uses `TextField`. No `apps/*/src/field.rs` duplicates.
- Pure core `rovsti_core::text_edit` (34 unit tests): UTF-8 caret/selection with UTF-16
  conversion, grapheme-aware movement/deletion, words, marked range, undo coalescing,
  newline sanitizing, max length that never splits a grapheme.
- `field.rs` has 32 `#[gpui::test]`s, including `delete_surrounding_text`, clipboard
  shortcuts, menu actions via the clipboard, long-text scroll and pan.
- Keyboard avoidance: Messages, Contacts, Stops read `insets.ime - consumed`; Phone pads by
  `window.insets().effective()`, which includes the IME inset.
- Phone keypad keeps its own dial logic with a Copy/Paste field menu (`dial::FieldAction`).
- Android device checklist for the native selection toolbar is in `docs/android.md`.

## Shots opened this round (rendered 19:43, after HEAD)

- `target/shots/rovsti_ui/01-fields-dark.png`: 15 states correct: placeholder, focused
  empty, caret after text, caret mid-text with handle, word selection with two handles,
  glass edit menu (Cut/Copy/Paste/Select all), long Turkish URL scrolled to the caret,
  ellipsis when unfocused, composing underline, 3-line multiline, masked secret, phone,
  Paste-only menu, URL panned to the start, max length 20/20 with caret mid-text.
- `target/shots/rovsti_ui/02-field-touch-light.png`: tap handle, long-press word + menu,
  empty Paste-only, secret with Paste only (no Cut/Copy).

## Steps

None. What remains is on-device verification (the `docs/android.md` text-field checklist),
which this environment cannot run.
