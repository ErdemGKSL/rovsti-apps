# util-phone-keypad: plan (round 2)

baseSha: b9336113a340ababa33a5da1a66a733200ef28c0

## Status after round 1
Round 1 (S1 to S7, commits 0e3a8cf..b933611) is in: the pure `rovsti_core::keypad_layout`
module with tests, per-frame layout, the clipped section boxes, number fit with a leading
ellipsis (`elided_head`), the capped matches strip, the scrolling actions row, the bottom safe
area and the docs. The tree is clean and nothing is stashed.

## What is still wrong (audit of HEAD)
1. **Double vertical offset (this is the owner's bug, and it is still there).** In `main.rs`
   `page()` (keypad branch, around line 1300), the inner wrapper is a plain block div
   (`Style::default()` is `Display::Block`, gpui style.rs:833) with the children `title`
   (large_title, 105 px, in flow), `setup`, `gear` and then `self.keypad(..)`. `keypad()` returns
   `size_full().relative()`, so it sits *below* the 105 px title, and its height resolves to
   auto/0. Every section is `absolute().top(px(r.y))` relative to that box, and the `r.y` values
   already include `header_h` (`top = header_h.max(ins.top)`). So the number, matches, actions,
   grid and call row are all drawn about 105 px lower than the layout says. The call row ends
   up under or past the tab bar, and on 360x640 it goes off-screen. The pure tests cannot see
   this, because only the view is wrong.
2. **The header is not budgeted in the view.** The layout shrinks the header to
   `LANDSCAPE_HEADER_MAX = 48` in landscape. The view still draws the 105 px large title, plus
   the setup button and gear at `top(54)` (44 px, setup_ui.rs:145, settings_ui.rs:1195), so in
   landscape they overlap the number and matches rects. The header rect is
   `ins.top..header_h.max(ins.top)`. With `ins.top = 24` in landscape it is only 24 px tall, too
   small for anything.
3. No test renders the real view and checks the drawn bounds against the layout, which is why
   (1) went unnoticed. The headless `perf_harness::Rig` draws the real `PhoneApp`, and
   `self.marks` already records the bounds of every key (`A_KEY + slot`, keypad.rs:1421), the
   field (`A_FIELD`) and the call button (`A_CALL`).
4. Minor: keypad.rs:600-602 has a stale doc comment ("The number field's font size now...")
   stacked on `keypad_input`.

GPUI APIs to use (all already used in this repo, or checked at
`gpui-fusion-141089add1664e03/d6c8412/crates/gpui`): `absolute`, `top_0`, `left_0`,
`size_full`, `top(px)`, `left(px)`, `w/h`, `overflow_hidden`, `flex`, `items_end`,
`justify_between`, `HeadlessAppContext::open_window(size, ..)`, `Bounds<Pixels>`. No new APIs.

## Steps

### S1. Pure: the header budget holds the title block in every mode
- `crates/rovsti_core/src/keypad_layout.rs`:
  - Add `pub const TITLE_BLOCK_MIN: f32 = 49.0;` (LARGE_TITLE line 41 + bottom padding 8) and
    `pub const LANDSCAPE_HEADER_H: f32 = 48.0;` (it replaces `LANDSCAPE_HEADER_MAX`; it holds the
    44 px gear/setup row plus 2 px above and below).
  - Header rule. Portrait/Compact: `top = input.header_h.max(ins.top + TITLE_BLOCK_MIN)`.
    Landscape: `top = ins.top + LANDSCAPE_HEADER_H`. `header = Rect { x: left, y: ins.top, w:
    avail_w, h: top - ins.top }`. Keep the landscape probe, using the landscape `top`.
  - Add the field `pub header_compact: bool` to `KeypadLayout` (true exactly in Landscape) and
    set it in `finish`.
- Tests (in the existing `mod tests`):
  - `header_holds_the_title_block`: for portrait sizes 360x640, 412x915 and 320x480, with
    `insets.top` in [0, 24, 48, 80], assert `header.h >= TITLE_BLOCK_MIN`,
    `header.bottom() >= 105.0`, `header.bottom() <= number.y`, and that `!header_compact` holds.
  - `landscape_header_is_one_row`: for 915x412 and 800x360, with `insets.top` in [0, 24], assert
    `header.h == LANDSCAPE_HEADER_H`, `header_compact`, and `header.bottom() <= number.y`
    and `<= grid.y`.
  - Extend `sweep_of_viewports_stays_sane` so it also runs with `insets.top = 32, bottom = 24`.
    `assert_sane` already checks the header for overlaps.
- Acceptance: `cargo test -p rovsti_core keypad_layout` passes. The phone app builds unchanged
  (add `header_compact` wherever a `KeypadLayout` literal exists, if any). All checks green.

### S2. View: one coordinate origin, and the header drawn inside its rect
- `apps/phone/src/main.rs` `page()` keypad branch: the inner wrapper (`relative().top(page_lift)
  .left(page_slide)`) gets `.size_full()`. Remove `title`, `setup` and `gear` from it; it now
  holds only `self.keypad(theme, setup, gear, cx)` (pass the two `AnyElement`s in, or build them
  inside `keypad`).
- `apps/phone/src/keypad.rs` `keypad()`: the root becomes `div().absolute().top_0().left_0()
  .size_full()` (it no longer follows the title in flow), so every `section(rect)` is in
  viewport coordinates, the same space as `KeypadLayout`. Add a header box:
  - Portrait/Compact (`!layout.header_compact`): `div().absolute().left_0().w_full()
    .top(px(layout.header.bottom() - HEADER_H)).h(px(HEADER_H))`, holding the same `title`
    (`relative().left(title_shift).pr(56).child(large_title(..))`), `setup` and `gear` as today.
    Their relative positions (title bottom at 105 - 8, buttons at 54..98) do not change, and
    `header.bottom() >= HEADER_H` (from S1) keeps the box at y >= 0.
  - Landscape (`layout.header_compact`): the same box with `top(px(layout.header.y + 2.0 -
    54.0))`, so the 44 px setup/gear row sits at `header.y + 2`. The title is a one-line
    `typography::HEADLINE` text (`whitespace_nowrap`, `truncate`), left-aligned at
    `metrics::ROW_INSET`, at `top(px(54.0))` with `h(px(44.0))`, `flex items_center`, and
    `pr(px(108.0))` so that it ends before the two buttons. Wrap the whole header in
    `section(layout.header)`'s clip (`overflow_hidden`) only if that does not clip the
    buttons' press glow. If it does, leave it unclipped, because its contents are sized to fit.
  - `title_shift()` animation stays on the title.
- Fix the stale doc comment at keypad.rs:600-602 (keep only "The pure layout's input for this
  frame.").
- Acceptance: all checks green, including the `perf_harness` allocation test
  (`a_keypad_frame_stays_inside_its_allocation_budget`) and
  `grid_and_end_never_move_between_scenes`. If the second test asserts in-call positions that
  depended on the old offset, check whether it really does; in-call does not use
  `kp_layout`, so it should not change.

### S3. Headless test: drawn keypad bounds land on the layout rects
- `apps/phone/src/perf_harness.rs`: add `pub fn with_size(w: f32, h: f32) -> Self` to `Rig`
  (move the body of `new` into it, and have `new()` call `with_size(402.0, 874.0)`).
- New test `keypad_draws_inside_its_layout_rects` (in the existing `mod tests`). For each of
  (360, 640), (412, 915), (320, 480), (915, 412) and (800, 360), and for each dialed value in
  `""` and `"+90555123456789012345678901234567890"`:
  - Open `Rig::with_size(w, h)`, `apply(Action::Tab(KEYPAD_TAB))` if the keypad is not the
    default tab, then `with_app` to set `app.dialed`, call `app.dialed_changed`-equivalent (use
    whatever setter `keypad.rs` uses after a digit, so that `shown_digits` and T9 rebuild), and
    `cx.notify()`. Then `settle()`.
  - Read `app.kp_layout` and `app.marks.get(..)`, and convert `Bounds<Pixels>` to
    `keypad_layout::Rect` with a small helper `fn rect(b: Bounds<Pixels>) -> Rect`.
  - Assert, with a 1 px epsilon via `Rect::inside`: each of the 12 `A_KEY + slot` marks is
    inside `layout.grid`, `A_CALL` is inside `layout.call_row`, `A_FIELD` is inside
    `layout.number`, every one of them is inside `Rect { 0, 0, w, h }`, and every
    `bottom() <= layout.tab_bar.y`. Also assert that the grid marks are the same with an empty
    and a long `dialed` (typing never moves the keys).
- This test fails on HEAD (keys about 105 px low) and passes after S2. If S2 was done first,
  check it by temporarily reverting the `top_0` change locally. Do not commit that revert.
- Acceptance: `cargo test -p rovsti_phone keypad_draws_inside` passes. All checks green.

### S4. Docs and final verification
- `docs/ux-audit.md` (keypad entry): note the header budget (`TITLE_BLOCK_MIN`, landscape
  one-row header) and the headless bounds test. `docs/phone-acceptance.md`: add "landscape:
  title is a single row, nothing overlaps the number".
- Run the full checks from the task: `cargo fmt --all && cargo clippy --workspace
  --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android
  --target aarch64-linux-android && cargo check -p rovsti_wa_client --target
  aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`. If
  `df` shows < 6 GB free, run `rm -rf target/debug/incremental` first.
- Commit with the attribution lines from the session.
- Acceptance: all checks green, and the working tree is clean.
