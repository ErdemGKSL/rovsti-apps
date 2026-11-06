# util-phone-keypad: plan (round 3)

baseSha: 48b20e914a089f5951733f77f4cea5b242c1c450

## Status: done, no further steps

Round 1 (0e3a8cf..b933611) and round 2 (03bf6b1, 857e714, 475faef, 48b20e9) cover the whole goal:

- Pure, unit-tested size math: `crates/rovsti_core/src/keypad_layout.rs` (`keypad_layout(&KeypadInput)
  -> KeypadLayout`, `key_style`, `number_fit`, `match_plan`). It has 15 tests: 360x640 with insets,
  412x915, tall dp-scaled sizes, raw-pixel clamps, landscape, a tiny compact layout without letters,
  long input, "typing never moves the keys", a sweep of viewports with and without insets, the header
  budget and the one-row landscape header. They assert that no rects overlap and that every rect is
  inside the viewport.
- The view (`apps/phone/src/keypad.rs` `keypad()`): one coordinate origin (`absolute().top_0()
  .left_0().size_full()`). Each section is a clipped box at its layout rect. The header sits in its
  rect (large title in portrait, a single row in landscape). The number shrinks to
  `NUMBER_FONT_MIN` and then shows a leading ellipsis (`elided_head`), so the end stays visible. The
  T9 strip has a capped number of rows and a `+N` more pill. The actions row has a fixed height and
  scrolls horizontally (`overflow_x_scroll`). The insets come from `window.insets().effective()`, and
  the tab bar respects the bottom safe area.
- Headless check: `perf_harness::tests::keypad_draws_inside_its_layout_rects` draws the real app at
  360x640, 412x915, 320x480, 915x412 and 800x360, with an empty and a long number. It asserts that the
  drawn keys, call button and field are inside their rects and the viewport, stay above the tab bar,
  and do not move while typing.

Verified at HEAD: `cargo fmt --all --check` passes, clippy with `-D warnings` is clean,
`rovsti_core keypad_layout` passes 15/15, and `keypad_draws_inside_its_layout_rects` passes.

Optional follow-ups (outside the owner's report, not planned): the headless test could also rebuild T9
for a dialed value with matches, and could run with non-zero insets.
