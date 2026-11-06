# util-phone-keypad: plan (round 1)

baseSha: 98f0f1e0bb4de75894bc716282d68b5c08db2593

## Audit (apps/phone/src/keypad.rs `keypad()`, main.rs `page()`)
What causes the overlap and off-screen reports today:
1. **T9 strip floats** (`t9_strip`, absolute `bottom_full` above the number display). Up to
   3 x 48 + 2 x 6 + 36 = 192 px tall, but only about 130 px (title block 105 + `pt(24)`) sit above
   the number. It covers the large title, the setup button and the gear, and on short screens
   it starts above y = 0.
2. **WhatsApp chip** (non-inline `whatsapp_chip`, absolute `top_full().mt(4)`) lands on the
   status line.
3. **Status line** puts "Add to contacts" + "Message" + "WhatsApp" side by side with no width
   limit or scrolling, so on a 360 px screen it runs off the right edge.
4. **Fixed key size** (`KEY_SIZE = 78`, gap 24, row gap 12). The column needs about 105 + 24 +
   52 + 12 + 36 + 12 + 348 + 12 + 78 = 679 px, plus 80 px for the tab bar. That is more than
   640 px, so on small phones the page (`overflow_y_scroll`) puts the call row under the tab
   bar. Landscape does not work at all.
5. **Number field** (`field_font`): `min(font_step, fit)` has no lower bound, so long input
   shrinks to unreadable sizes, and nothing stops `head`/`last` from wrapping. The width
   comes from the window, not from the field. There is no tail ellipsis.
6. The paste pill is absolute `top(4)` over the number row. Insets
   (`window.insets().effective()`) are not read anywhere in the phone app, and the tab bar
   ignores the bottom safe area.

GPUI APIs checked at the pinned rev (`gpui-fusion-141089add1664e03/d6c8412/crates/gpui`):
`Window::insets() -> WindowInsets { safe_area, ime, consumed }` with `.effective() -> Edges<Pixels>`
(window.rs:1694, platform.rs:996), `Styled::whitespace_nowrap`, `text_ellipsis_start`, `truncate`
(styled.rs:82-139), `overflow_x_scroll` (div.rs:1504, needs `.id`), `overflow_hidden`,
`flex_none`, `absolute/left/top/w/h`. Only these are used. No new APIs.

## Design
A pure function computes every rect, and the view only places clipped boxes at those rects.
The rects are proven disjoint and inside the viewport by the tests, and each box has
`overflow_hidden`, so its content cannot spill into a neighbouring box. Absolute placement at
rects that are disjoint by construction is the "no overlapping absolute positioning" rule.
Portrait stack, top to bottom: header (title, setup, gear), number, matches (flexes),
actions, grid, call row, then the reserved tab bar. The key size is
`min(width-derived, height-derived)`, clamped. Below `KEY_MIN` the layout goes compact (smaller
number row, smaller gaps, smaller glyphs, no letters under `LETTERS_MIN`). When the content
area is wider than it is tall, the layout goes landscape: the left column holds number,
matches, actions and call row, and the right column holds the 4-row grid.

## Steps

### S1. Pure `rovsti_core::keypad_layout` module with tests
- New `crates/rovsti_core/src/keypad_layout.rs`, `pub mod keypad_layout;` in `lib.rs`. No deps,
  no allocation, all types `Copy + Debug + PartialEq`.
- Types:
  - `pub struct Rect { pub x: f32, pub y: f32, pub w: f32, pub h: f32 }` with `right()`,
    `bottom()`, `overlaps(&Rect) -> bool` (strict: touching edges do not overlap, empty rects
    never overlap), `inside(&Rect) -> bool` (epsilon 0.5).
  - `pub struct Insets { pub top: f32, pub right: f32, pub bottom: f32, pub left: f32 }` (Default 0).
  - `pub struct KeypadInput { pub viewport_w: f32, pub viewport_h: f32, pub insets: Insets,
    pub text_len: usize /* chars */, pub header_h: f32 /* title block: 56 + 41 + 8 = 105 */,
    pub tab_bar_h: f32 /* slot: TAB_BAR_HEIGHT * 1.1 */, pub tab_bar_margin: f32 /* 12 */ }`.
  - `pub enum KeypadMode { Portrait, Compact, Landscape }`.
  - `pub struct KeyStyle { pub glyph: f32, pub caption_h: f32, pub letters: bool }`.
  - `pub struct NumberFit { pub font: f32, pub keep: Option<usize> }`. `keep` = how many trailing
    chars to show after a leading `…` (None = everything fits).
  - `pub struct KeypadLayout { pub mode, pub header: Rect, pub number: Rect, pub matches: Rect,
    pub actions: Rect, pub grid: Rect, pub call_row: Rect, pub tab_bar: Rect, pub key: f32,
    pub col_gap: f32, pub row_gap: f32, pub key_style: KeyStyle, pub match_rows: usize,
    pub more_inline: bool, pub number_fit: NumberFit }`, plus `pub fn rects(&self) -> [Rect; 7]`.
- Functions:
  - `pub fn key_style(key: f32) -> KeyStyle`: glyph `(key * 0.41).clamp(20.0, 32.0)`, caption_h
    `if key < 78 {11} else {12}`, letters `key >= LETTERS_MIN (60)`. At 78 this gives 32/12/true,
    and at 72 (in-call) 30/11/true, which matches today's look.
  - `pub fn number_fit(len: usize, width: f32) -> NumberFit`: `font = dial::font_step(len)
    .min(width / (len.max(1) * CHAR_W(0.6))).max(NUMBER_FONT_MIN (22))`. If
    `len * 0.6 * font > width`, then `keep = Some(floor(width / (0.6 * font)) - 1)` (one slot for `…`).
  - `pub fn match_plan(h: f32) -> (usize, bool)`: rows of `MATCH_ROW_H 48` with gap 6, max 3, min 1.
    It reserves a 36 px "N more" line when the space allows, and otherwise returns
    `more_inline = true` (the last row carries a trailing "+N" pill).
  - `pub fn keypad_layout(input: &KeypadInput) -> KeypadLayout`. Starting constants (tune if
    tests need it): KEY_MAX 78, KEY_MIN 56, KEY_FLOOR 36, col_gap = `(key * 24/78).clamp(10, 24)`,
    row_gap = `(key * 12/78).clamp(6, 12)`, SIDE_PAD 16, NUMBER_H 52 (compact 40), ACTIONS_H 36
    (compact 32), MATCH min one row (48, compact 40), SECTION_GAP 8 (compact 4), BAR_GAP 8.
    Content top = `header_h.max(insets.top)`. Content bottom = `viewport_h - insets.bottom -
    tab_bar_margin - tab_bar_h - BAR_GAP`. Horizontal limits come from `insets.left/right`.
    The tab_bar rect is `y = viewport_h - insets.bottom - tab_bar_margin - tab_bar_h`.
    Portrait: `key_h = (avail_h - fixed) / (5 + 4 * 12/78)`, `key_w = (avail_w - 2 * SIDE_PAD) /
    (3 + 2 * 24/78)`. Grid and call row are bottom-anchored. Matches gets the leftover height
    (capped at the 3-row + more height, with any extra above the number as top spacing). If
    key < KEY_MIN, go Compact and recompute. Landscape (`avail_w > avail_h`) is the two-column
    split described above, with the grid sized for 4 rows. KEY_FLOOR applies only while it still
    fits; "inside the viewport" wins over the floor. Width is capped (grid width ≤ avail_w, the
    number/matches/actions columns at most 420 px wide, centred).
- Tests (`#[cfg(test)]`): helper `assert_sane(&KeypadLayout, &KeypadInput)` checks that all 7
  rects are pairwise non-overlapping, all are inside the viewport minus insets (the tab bar
  only inside the viewport), the grid is ≥ `3 * key + 2 * col_gap` wide and
  `4 * key + 3 * row_gap` tall, key ≤ 78, and the call row is below the grid in Portrait/Compact.
  Cases:
  - small 360x640 (insets top 24, bottom 48)
  - normal 412x915
  - tall 1080x2400 dp-scaled (411.4x914.3 at 2.625, and 360x800 at 3.0)
  - raw 1080x2400 (key clamps to 78, everything centred)
  - landscape 915x412 and 800x360 (mode Landscape, key ≥ 36)
  - tiny 320x480 (Compact, letters hidden, still inside)
  - long input (text_len 40 on 360 wide: font == 22, keep is Some, and
    `(keep + 1) * 0.6 * 22 <= number.w - 48`)
  - typing invariance: the grid and call_row rects are identical for text_len 0, 5, 15 and 40
    (typing never moves the keys)
  - sweep: widths 280..=1400 step 40 x heights 280..=2400 step 40, `assert_sane`
  - unit tests for `key_style`, `number_fit` (monotone non-increasing font with length, never
    below 22) and `match_plan`
- Acceptance: `cargo test -p rovsti_core keypad_layout` passes. The phone app is untouched. All
  checks green.

### S2. Compute the layout every frame and place keypad sections at its rects
- `apps/phone/src/main.rs`: add the field `kp_layout: rovsti_core::keypad_layout::KeypadLayout`
  (initialise it from a 412x915 input). In `render`, before `self.font.set_target`, build
  `KeypadInput` from `window.viewport_size()`, `window.insets().effective()` (convert
  `Edges<Pixels>` to `Insets` via `f32::from`), `self.dialed.chars().count()`, `header_h = 56.0 +
  typography::LARGE_TITLE.line + 8.0` (a const in keypad.rs; `LARGE_TITLE.line` is 41), and
  `tab_bar_h = f32::from(metrics::TAB_BAR_HEIGHT) * 1.1`, `tab_bar_margin =
  f32::from(metrics::TAB_BAR_MARGIN)`.
- `page()` keypad branch: drop `overflow_y_scroll()` and `.pb(content_bottom_inset())`. The page
  wrapper stays `size_full().relative()` (keep `page_lift`/`page_slide`).
- `keypad.rs` `keypad()`: take `layout: &KeypadLayout` (or read `self.kp_layout`). Return a
  `size_full().relative()` div whose children are `section(rect)` boxes: `div().absolute()
  .left(px(r.x)).top(px(r.y)).w(px(r.w)).h(px(r.h)).overflow_hidden().flex()...`. These hold the
  number (S3 refines it), the status line (S5 refines it), `dial_pad(theme, layout.key,
  layout.col_gap, layout.row_gap, false, cx)` and the call row (spacer/call/backspace each
  `layout.key`, gap `layout.col_gap`, call glyph `(key * 0.38).clamp(20, 30)`). For now the T9
  strip goes inside the `matches` box, top-aligned, still with its old content. Remove
  `pt(24)`/`gap(12)` stacking.
- `dial_pad`: replace `const ROW_GAP` with a `row_gap: f32` parameter. Update the callers in
  `incall.rs:2154` (pass 12.0) and `addcall.rs:593` (pass 12.0).
- `dial_key`: use `rovsti_core::keypad_layout::key_style(size)` for glyph and caption height,
  and render `key.letters` only when `style.letters` (keep the voicemail icon on "1" when
  letters are shown). Keep `KEY_SIZE` only for the in-call/addcall callers, or delete it if unused.
- `field_font`: return `self.kp_layout.number_fit.font` (S3 replaces it).
- Acceptance: all checks green, including `perf_harness` `a_keypad_frame_stays_inside_its_allocation_budget`
  (the layout allocates nothing). The keypad does not scroll and fits 360x640 in the desktop
  preview.

### S3. Number field: shrink to the minimum, then keep the end visible
- `number_display(theme, layout)`: the width comes from `layout.number.w - 48`. When
  `number_fit.keep == Some(n)`, compute head as `"…" + the last n-1 chars before the final
  digit`, keep `last` as the final digit, and do it in `render` next to `shown_digits` (main.rs
  ~1417). Store it in `shown_digits` together with the `keep` it was built for, so it is rebuilt
  only when `dialed` or `keep` changes, not on every frame. Add `.whitespace_nowrap()` and
  `.overflow_hidden()` to the number row, and `flex_none` to the head/last/ghost children, so
  the row never wraps.
- The paste pill becomes a normal child of the number box (centred, shown only while `dialed`
  is empty) instead of `absolute().top(4)`.
- `key_fx` (keypad.rs:575) keeps `font_step(len).min(self.font.target)`, so the landing size
  follows the fitted font.
- Add an aria label on the number box with the full number (`SharedString` from `dialed`) so
  the ellipsis does not hide digits from TalkBack.
- Tests (keypad.rs `mod tests`): a pure helper `elided_head(dialed: &str, keep: Option<usize>) ->
  (String, String)` with tests for no elision, elision that keeps the last `keep` chars, and
  multi-byte safety (`+`, `*`, `#`).
- Acceptance: all checks green.

### S4. Matches strip in its fixed box, rows capped by the layout
- `visible_suggestions(hits, exact, cap: usize)`: replace the `T9_SHOWN` take with `cap.min(T9_SHOWN)`
  and update its tests (including a cap = 1 case where the remainder counts the hidden ones).
- `RowsKey` gains `cap: usize` (from `self.kp_layout.match_rows`), so `sync_t9_rows` re-resolves
  only when the cap changes. It still never runs per frame.
- `t9_strip(theme, layout)`: width `layout.matches.w.min(360)` instead of a fixed 320, and
  bottom-aligned inside the matches box (the rows sit next to the number field). There is no
  `absolute().bottom_full()`. When `layout.more_inline`, the last row gets a trailing flex_none
  glass pill "+N" with id `t9-more` (same `t9_more` handler and `more_aria`). Otherwise the 36 px
  "N more" line stays.
- Acceptance: all checks green. Rows never extend past the matches rect (box clipped, height
  from `match_plan`).

### S5. Actions row: fixed height, horizontal scroll, WhatsApp chip inline
- `status_line(theme, layout)`: `h(layout.actions.h)`, `w(layout.actions.w)`, `.id("status")
  .overflow_x_scroll()`, children `flex_none` with `.whitespace_nowrap()`. Centre with
  `justify_center` when the content fits. Overflowing content scrolls, which is acceptable.
  Status buttons and chips keep their ids, touch surfaces and finger-up behaviour.
- `whatsapp_chip` loses the `inline` param and the `absolute().top_full()` branch. In the
  `!add_offered()` case it renders inside the actions row too (the same row that shows a
  found name), so no element floats.
- The found-name button: `.max_w(px(layout.actions.w - 32))` instead of the fixed 260.
  NeedsPhone keeps its truncating sentence.
- Acceptance: all checks green. On 360 px wide, "Add to contacts / Message / WhatsApp" stays
  inside the actions box (it scrolls or fits).

### S6. Tab bar and floating pill respect the bottom safe area (phone app only)
- `main.rs`: add `fn safe_bottom(window) -> Pixels` = `window.insets().effective().bottom`.
  Tab-bar slot `.bottom(metrics::TAB_BAR_MARGIN + safe_bottom - px(away * 140.0))`. Add
  safe_bottom to `pill_bottom` (pass it in, or store `self.safe_bottom: f32` set in render),
  and to the list bottom limits in `recents.rs:719`, `contacts.rs:824` and `contacts.rs:1961`,
  and to the `notice.rs` uses of `content_bottom_inset()`. `KeypadInput.insets.bottom` already
  reserves the same amount, so `layout.tab_bar` matches the drawn bar.
- Add a unit test in `keypad_layout` that the `tab_bar` rect bottom equals
  `viewport_h - insets.bottom - margin` (this already exists from S1, so assert it explicitly here).
- Acceptance: all checks green. With zero effective insets (desktop), nothing moves.

### S7. Docs and final verification
- `docs/phone-acceptance.md` (keypad section) and `docs/ux-audit.md`: describe the layout
  budget, modes (Portrait/Compact/Landscape), the thresholds (KEY 36..78, letters ≥ 60, number
  font ≥ 22 then leading ellipsis) and the pure module path. Mention in `docs/architecture.md`
  next to the other rovsti_core modules if it lists them.
- Run the full checks from the task (fmt, clippy `-D warnings`, test, the three aarch64 checks).
  If `df` shows < 6 GB free, `rm -rf target/debug/incremental` first. Commit with the
  attribution lines.
- Acceptance: all checks green, and the working tree is clean after the commit.
