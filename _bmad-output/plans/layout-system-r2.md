# Unit `system`, round 2: make the shots honest, then finish the shared system

baseSha: ec5523dbe6d8d3cd88bdc123f20cb78af5da775b

## Scope note (read first)

The relayed user request for this run is still the Turkish text-box complaint ("code the
mechanics of these text boxes properly, even the default HTML input works better"). The
`shots` and `text-input` units already worked on that, and `layout-text-input-r2.md` marked
it done. This unit's computed task is the layout overhaul. This plan only covers that
overhaul, and it must not regress text fields. Step 1 changes every shot's colours, the
field galleries included, so the regression guard is: after step 1, `rovsti_ui/01-fields-*`
and `02-field-touch-*` are re-rendered once and must then stay byte-identical through
steps 2 to 7. The orchestrator should still ask the owner on a device which text-box
behaviour is wrong. Headless shots cannot show IME, selection-handle or keyboard problems.

## What I looked at

- The owner's device shot (`054d28c4-image.jpg`, Messages inbox, dark).
- Fresh renders at HEAD (made in this round):
  `target/shots/rovsti_ui/03-layout-{dark,light}.png`,
  `target/shots/messages/01-inbox-{dark,light}.png`, `10-inbox-end-dark.png`.
- Round 1 plan `layout-system.md`, `docs/design/layout.md` (2.14 "Migrated screens"),
  `docs/screenshots.md`, `crates/rovsti_ui/src/{theme,row,components,tab_bar,shots,layout}.rs`,
  `gpui_wgpu/src/offscreen_renderer.rs` at the pinned rev. git log shows round 1's 8 steps
  all landed (`7deff5f..ec5523d`). There is no stash.

## Status of round 1 (what the shots show)

Done and visibly right: single-row scrolling filter chips with separate count badges, no
per-row WhatsApp/SMS pill (a 16 dp source badge on the avatar instead), a quiet Spam row,
40 dp header buttons with only Search and Compose, a trailing time and badge column that
right-aligns, separators inset to the text, and a tab bar that floats above a 16 dp safe
inset. In `10-inbox-end-dark` the last row clears the bar.

## What is still wrong (measured, not guessed)

1. **The shots draw every colour too light, so every contrast judgement so far is wrong.**
   `WgpuOffscreenRenderer` renders into `Rgba8UnormSrgb` (offscreen_renderer.rs:40). GPUI
   already outputs sRGB-encoded values, so the hardware encodes them a second time. The
   proof: the kit dark accent `#0A84FF` comes out as (56,190,255) in
   `01-inbox-dark.png`'s tab icon, but as (58,130,248) in the owner's device shot. The
   light accent `#007AFF` renders as (0,184,255). The light secondary label (expected
   blend 111) renders as 176. Every value matches `linear_to_srgb(v)` exactly, so the
   real device is darker than the shots.
2. **Light-mode text contrast is unproven, and accent text fails even on the device.**
   Pixel samples from `messages/01-inbox-light.png` (all inflated by bug 1): preview 2.16:1,
   unread time 2.26:1, chip counts 2.13:1, Spam badge 2.26:1. Corrected for bug 1, the
   secondary label is about 5:1 on white, but `#007AFF` text on white is 4.0:1. That is
   under the 4.5:1 the spec asks for, and the light unread time ("15:35"), the OTP "Copy"
   chip and the selected-chip text all use it. The theme test only checks
   `secondary_label` on pure white. It ignores the wash, `grouped_background`, the chip
   fill and accent text.
3. **The Status row breaks the text column.** Its `avatar_stack` (3 x 28 dp, step 18) is
   about 64 dp wide, so its title starts at 92 dp. Every other row starts at 76 dp
   (16 + 48 + 12). The separator under it also starts at 92 dp. Seen in
   `messages/01-inbox-*` and `rovsti_ui/03-layout-*` (Status row). The "+3" disc and rings
   crowd the 28 dp discs.
4. **Avatar initials are too big.** `components::avatar` uses `size * 0.4` at Headline
   weight, which on 48 dp is about 19 dp bold. "MK", "MD", "CÖ" and "HS" nearly touch the
   rim, and the 16 dp source badge sits on the second letter ("MK" and "AY" in
   `03-layout-light`, "AY" and "MD" in `01-inbox-light`).
5. **The dark backdrop wash has a hard edge.** `components::backdrop` is a 160° gradient
   from 28 % accent to 0 at the 55 % stop. In `01-inbox-dark` and `03-layout-dark` it
   reads as a bright blue upper half that ends in a visible diagonal band at about
   y = 500 to 530 dp (behind "Can Öztürk", "Garanti BBVA"). That is not restrained
   colour. Re-judge it after step 1, because part of the glare is bug 1.
6. **Content stays legible through the light tab bar.** `GlassSpec::chrome` has a 2 px
   blur, so in `01-inbox-light` the row "Mert Demir / Tamam yarın görüşürüz" reads through
   the bar and competes with the icons. The bar needs real frost.

## Steps (each leaves every check green)

### 1. Fix the colour pipeline of the headless shots
- In `crates/rovsti_ui/src/shots.rs`, `WgpuHeadless::render_scene_to_image` decodes the
  readback once with an sRGB-to-linear table applied to R, G and B (not A). That undoes the
  extra hardware encode of the `Rgba8UnormSrgb` target. The table is a pure function
  `srgb_decode_lut() -> [u8; 256]` in a new `crates/rovsti_ui/src/shots/color.rs`, with a
  `#[cfg(test)]` unit test that is not ignored: endpoints 0 and 255 are fixed,
  `lut[linear_to_srgb(122)] == 122 ± 1`, and the table is monotonic. Do not change
  gpui-fusion. Read `WgpuOffscreenRenderer` and `WgpuRenderer::new_external` at the pinned
  rev only to confirm that the target format cannot be chosen from outside.
- Add an ignored shot test `color_probe` that renders 3 solid 40 dp quads (`#007AFF`,
  `#0A84FF`, `#3C3C43` at 74 % over white) and asserts that the centre pixels are within
  ±3 of (0,122,255), (10,132,255) and (111,111,115).
- Add a pure helper in `shots/color.rs`:
  `contrast_in(img, rect) -> f32` (darkest and lightest pixel in a rect, WCAG ratio via
  `layout::geom::contrast_ratio`). Unit-test it on a synthetic 2-colour image.
- Re-render `rovsti_ui` (smoke, fields, field_touch, layout) and `messages`. Copy the new
  `01-fields-*` and `02-field-touch-*` to the scratchpad as the text-field guard for
  steps 2 to 7. Add a "Colour accuracy" paragraph to `docs/screenshots.md`.
**Acceptance:** `cargo test -p rovsti_ui --features shots color` passes, including
`color_probe` with `--ignored`. Open `messages/01-inbox-dark.png`: the tab icon accent
samples about (10,132,255), which matches the device shot. All checks pass, plus the
`shots` clippy line.

### 2. Text colour roles that pass 4.5:1 on every surface they sit on
- In `crates/rovsti_ui/src/layout.rs` `geom`, add
  `ensure_contrast(fg: [f32;3], bg: [f32;3], min: f32) -> [f32;3]`. It keeps the hue and
  lowers or raises lightness in 1 % steps until `contrast_ratio >= min`. Tests: `#007AFF`
  on white comes back darker with a ratio of at least 4.5; a colour that already passes
  comes back unchanged.
- In `crates/rovsti_ui/src/theme.rs`, add `accent_text(theme) -> Hsla`: the accent adjusted
  against the theme background, about `#0068DA` in light, and dark unchanged. Use it
  wherever accent is text, not fill: `ListRow` unread meta, the OTP chip label, the
  `PageHeader` subtitle link, and empty-state link text. The selected filter chip keeps
  the solid accent fill with `row::readable_on` ink.
- Replace the single `secondary_text_reaches_4_5_on_white` test with a table test. Every
  text role (`label`, `secondary_label`, `accent_text`, the chip label and count badge
  ink) is checked against every surface it sits on in both themes: `background`,
  `grouped_background`, `secondary_background`, the strongest point of the backdrop wash,
  and the unselected chip fill composited over the wash. Each must be at least 4.5:1, with
  alpha colours blended before the check. Raise `secondary_label` alpha or make it opaque
  only where a case fails. `tertiary_label` stays icon-only (3:1, separate assert), and the
  test fails if it is used as text (grep check in docs only).
**Acceptance:** `cargo test -p rovsti_ui theme` passes. In the new
`messages/01-inbox-light.png`, `contrast_in` is at least 4.5 on the preview, time, chip
count and Spam badge rects. Assert these in the messages shots test with the rects listed
in a const table next to the test. All checks pass.

### 3. The leading slot is always 48 dp, so the text column is always 76 dp
- `layout::geom::stack_fit(n: usize, slot: f32) -> (size, step)` returns the disc size
  and step so that n discs (n ≤ `STACK_MAX`, "+k" counted as a disc) fit exactly in
  `slot`, with a minimum disc of 24 dp. For n = 3 and slot 48 it gives 24 + 12 + 12 = 48.
  Tests cover n = 1..4 and assert that width ≤ slot.
- `row::avatar_stack` takes `slot` instead of `size, overlap`. `ListRow` drops
  `leading_width` for stacks. `apps/messages/src/status_ui.rs` and the `leading_width`
  call at `apps/messages/src/main.rs:5549` use the fixed slot. The "+k" disc text is
  Caption2 (11 dp) w600.
- Unit test in `row.rs`: `text_inset()` = `MARGIN + AVATAR + space::M` = 76 for every
  leading kind (avatar, stack, icon tile).
**Acceptance:** in `messages/01-inbox-*`, `09-status-*` and `rovsti_ui/03-layout-*`,
"Status" starts at x = 152 px (76 dp), like the other titles, and its separator starts at
152 px. All checks pass.

### 4. Avatar initials and source-badge placement
- `layout::geom::initials_size(avatar) = (avatar * 0.36).round()`, which gives 17 dp at
  48 and 10 dp at 28. Weight 600, not Headline bold. The test checks 48 → 17 and 40 → 14.
- `components::avatar` uses it. Add a pure `rovsti_core` initials function (or reuse the
  existing one; grep `fn initials`) that takes at most 2 graphemes and keeps Turkish
  dotted capitals ("İG", "Şİ") intact, with tests.
- The source badge centre sits on the avatar rim at 45° (centre at
  `avatar/2 + avatar/2 * cos45` from the top-left), not inside the disc. That is
  `geom::source_badge_offset(avatar, badge)`, with a test that the badge centre is at
  least `avatar/2 * 0.95` from the avatar centre.
**Acceptance:** in `03-layout-*` and `01-inbox-*`, two-letter initials keep at least 6 dp
from the rim, and no badge covers a letter ("MK", "AY", "MD"). All checks pass.

### 5. A calm backdrop: header-only wash with no visible edge
- `components::backdrop`: the wash becomes vertical (180°) and covers only the top
  `content_top + 200 dp`. Peak alpha is 0.14 in dark and 0.10 in light, and it fades with
  3 stops (0 → peak, 0.5 → peak/3, 1 → 0) so there is no band. Use only
  `linear_gradient` and `linear_color_stop` as now (grep the pinned gpui for the stop
  count the gradient supports; if it only supports 2 stops, stack 2 gradient divs).
- Add a test helper `max_vertical_step(img, x, y0, y1) -> u8` in `shots/color.rs` (pure,
  unit-tested). The messages shots test asserts that the column at x = 8 px (inside the
  margin, no content) never jumps more than 2 levels between neighbouring rows in either
  theme.
**Acceptance:** open `messages/01-inbox-dark.png` and `rovsti_ui/03-layout-dark.png`. The
upper half is near-black with a faint tint behind the title, and there is no diagonal
band. The step assert passes. All checks pass.

### 6. Tab bar frost and final geometry check
- In `crates/rovsti_ui/src/tab_bar.rs`, use a bar-specific `GlassSpec` built from
  `GlassSpec::chrome(theme)`, changing only existing fields (`blur`, `tint`,
  `saturation`; grep `liquid_glass_kit/src/glass.rs`): blur 12 px, and a tint of the
  background at 0.72 in light and 0.60 in dark. The puck keeps its spec. Follow
  `.claude/skills/liquid-glass-gpui/SKILL.md`'s tab bar recipe for the values. If the
  skill gives different values, use the skill's and note it in `docs/design/layout.md`
  2.x "Tab bar".
- Re-run the existing puck geometry tests. Add one more: for 3 and 4 tabs at rest, the puck
  centre equals the icon centre to within 0.5 dp.
**Acceptance:** in `messages/01-inbox-light.png`, the row behind the bar (bar region
y ≈ 1670 to 1780 px) is not readable as text. Its `contrast_in` over the bar body,
excluding icons, is under 1.6. The icons and the puck read clearly, and the puck is
centred on the first icon. Re-render Phone and Stops too (the bar is shared) and open
`phone/03-recents-light.png` and the Stops saved list: the bar matches. All checks pass.

### 7. Docs, baselines, full verification
- `docs/design/layout.md`: add the colour-pipeline note, the new text roles table with the
  measured ratios, `stack_fit`, the initials size, backdrop and tab-bar values, and update
  2.14 with any Messages screens touched.
- `docs/screenshots.md`: `color_probe`, `contrast_in` and `max_vertical_step`, and the
  instruction to judge contrast only after step 1's fix.
- Refresh all four `docs/screenshots/baseline/*.png`, because every colour changed with
  step 1. Note in the commit that Phone, Contacts and Stops baselines are still the
  per-app "before" layouts, now with correct colours.
- Confirm that `rovsti_ui/01-fields-*` and `02-field-touch-*` are byte-identical to the
  step 1 copies.
- Run every check in the unit list plus the `shots` clippy line. Watch disk (6.2 GB free
  at plan time): clean `target/debug/incremental` and stale test binaries before the
  final runs.
**Acceptance:** all checks pass, the docs match the code, the field shots are unchanged
since step 1, and `messages/01-inbox-{dark,light}`, `09-status-*`, `10-inbox-end-*` and
`rovsti_ui/03-layout-*` were opened and match the spec.
