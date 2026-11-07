# Unit `text-input`, round 1

baseSha: 1b1dcdc61256890625a62ec98f11d3048219246a

## Status (audited at HEAD, shots re-rendered and opened)

The computed task's "current state" is stale. Rounds `layout-shots-r2/r3`
(8aae8fc..1b1dcdc) already delivered almost all of the unit:

- `rovsti_core::text_edit` (1064 lines, 78 fns/tests): `EditBuffer` with anchor/head in
  UTF-8 bytes, UTF-16 conversion, grapheme-aware movement/deletion (unicode-segmentation),
  `word_at`, replace/mark/unmark, `delete_surrounding`, newline `sanitize`, `History`
  undo/redo with coalescing.
- `crates/rovsti_ui/src/field.rs`: the only `EntityInputHandler` in the workspace (the
  `apps/phone/src/field.rs` and `apps/contacts/src/field.rs` duplicates are gone). Shaped-line
  layout, correct `character_index_for_point` / `bounds_for_range`, scroll-to-caret,
  composing underline, secret mode, purposes, multiline composer (`multiline(1, 6)` in
  Messages WA and SMS), tap/double-tap/long-press, drawn handles + glass edit menu on desktop,
  native Android selection chrome via `TextInputFocusEvent`, finger pan, keyboard shortcuts,
  ~30 `#[gpui::test]`s. Phone keypad has its own Copy/Paste field menu.
- `docs/screenshots.md`, `docs/android.md` device checklist, `docs/architecture.md` are updated.

Opened with Read this round (rendered 19:16, HEAD):
- `target/shots/rovsti_ui/01-fields-dark.png`: all 14 states correct (caret mid-text with
  handle, word selection with handles, menu, long Turkish URL scrolled to caret, ellipsis when
  unfocused, composing underline, 3-line multiline, masked secret, phone, Paste-only menu now
  aligned to the field's left edge, long URL panned to start).
- `target/shots/rovsti_ui/02-field-touch-light.png`: tap handle, long-press word + menu,
  Paste-only, secret with Paste only.

## Remaining gap: max length

The required behaviour lists "max length" in the editing core. It does not exist:
`EditBuffer` has no limit, and apps clip after the fact. `apps/phone/src/replies_ui.rs:676`
clips an over-long quick reply with `field.set(&clipped)`, which (a) cuts the END of the
text even when the user typed in the middle, (b) jumps the caret to the end, (c) wipes undo
history, (d) can split a grapheme (`chars().take`). An HTML `maxlength` / Android
`LengthFilter` instead rejects or truncates only the inserted text and keeps the caret.

## Steps

### 1. Max length in `rovsti_core::text_edit::EditBuffer`
- Add `max_chars: Option<usize>` (counted in `char`s, matching `MAX_REPLY_CHARS` and
  `settings::MAX_FIELD_CHARS`), `pub fn set_max_chars(Option<usize>)`, `pub fn max_chars()`.
- One private `fit(&self, range: Range<usize>, text: &str) -> &str`: room =
  max - (chars outside `range`); truncate `text` to the longest prefix that ends on a grapheme
  boundary and has <= room chars (never splits an emoji/flag/combining sequence). Apply it in
  `replace`, `replace_selection`, `replace_and_mark` (marked text is clipped too, like
  Android's `LengthFilter`), after `sanitize`. Deletions are never limited. `set_text`
  (programmatic) clips at a grapheme boundary as well. Lowering the limit below the current
  length does not cut existing text, it only blocks growth.
- Unit tests: insert at limit is a no-op and caret stays; paste of 10 into room 3 inserts 3;
  selection replace frees room; flag emoji `🇹🇷` at room 1 is not split (nothing inserted);
  `e` + combining accent; marked text clipped; undo after a clipped paste restores exactly;
  `None` unlimited.
- Acceptance: `cargo test -p rovsti_core text_edit` green; all checks green.

### 2. `TextField::set_max_chars` and the Phone quick replies
- `crates/rovsti_ui/src/field.rs`: `pub fn set_max_chars(&mut self, max: Option<usize>)`
  forwarding to the buffer. No other change to the input handler (it already routes every
  insert through `EditBuffer::replace*`, so IME commits, paste, menu Paste and
  `replace_and_mark_text_in_range` all obey it).
- `apps/phone/src/replies_ui.rs`: create each reply field with
  `set_max_chars(Some(MAX_REPLY_CHARS))`; delete the clip-via-`set` block at lines 676-680.
  The `count/MAX` counter (line ~977) stays.
- `#[gpui::test]` in field.rs: field with max 5 holding "abcde", caret at 2, simulate
  `replace_text_in_range(None, "XYZ")` -> text unchanged, caret 2; select "bc", type "Z" ->
  "aZde", caret 2; `replace_and_mark_text_in_range` beyond room is clipped.
- Acceptance: checks green; `cargo test -p rovsti_phone replies` green.

### 3. Gallery state, docs, fresh shots
- `crates/rovsti_ui/src/shots/fields.rs`: `STATES: [State; 15]`, state 15
  "At max length (20), caret mid-text" (Turkish text of exactly 20 chars, caret after
  char 8, a right-aligned `20/20` counter in the caption is enough).
- `docs/screenshots.md`: list state 15. `docs/android.md` checklist: "type in the middle of a
  full quick reply: nothing is inserted, the caret stays".
- Re-render (`df -h .` first, clean incremental if < 6 GB):
  `cargo test -p rovsti_ui --features shots shots -- --ignored --nocapture` and the phone suite.
  Open `target/shots/rovsti_ui/01-fields-dark.png` (state 15 shows the full text, caret
  mid-text, no ellipsis) with Read.
- All checks, plus `cargo clippy -p rovsti_ui -p rovsti_phone --features shots --all-targets
  -- -D warnings` and the three Android `cargo check`s.

## Baseline

Unchanged: target/shots/{phone,messages,contacts,stops}/ and
target/shots/rovsti_ui/{00-smoke,01-fields,02-field-touch}-{dark,light}.png,
docs/screenshots/baseline/*.png. Step 3 changes only `01-fields-*` (one extra row) and no
committed baseline.
