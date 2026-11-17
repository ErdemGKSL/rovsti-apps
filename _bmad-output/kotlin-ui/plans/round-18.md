# Round 18 plan: X.2b, the glass optics of the animated card and in-sheet GIF playback

Planned 2026-11-17 at base `7ffcc39` (branch `ui-overhaul`). Epic: **X. Cross-app owner requests**, story **X.2b**
(split from X.2).

## Why X is reopened

Round 17 closed X.2 with this note: "the glisse optics for the animated card (round-17 step 4) are not in the tree,
and the Card Studio preview shows GIF frame 0 instead of playing it". Those optics are an explicit owner requirement in
the X.2 story: "Light bending as the GIFs' signature (owner, 2026-11-10)", plus the one-shot entrance with a lens
sweep, a rim glint and the panel's refraction following the squash. Without them, X.2 is not done.

`epics.md` now has:
- X.2 `done (static, animated without optics, sheet, share/save)`;
- a new row, **X.2b**, `in-progress (round 18)`.

E8 R1 is planned after X.2b closes. E6, E6-WA and E7 still run in parallel sessions and are skipped.

Owner rules stay in force:
- (a) every pushed page has a glass back button and the full tab bar;
- (b) no `RustNative` call before `Rust.init`;
- (c) only `imeOverlapPadding`;
- (d) R8 keep rules, checked with `dexdump`;
- GPL-3.0-or-later. Convx ports keep their header plus the "Ported from Convx" line; glisse ports carry the glisse MIT
  header.

## State found (verified, not trusted)

- **HEAD `7ffcc39 WIP: carry-over`** (Haiku agent, 2 files):
  - `PeopleSyncJobTest` now waits for the settled `Preparing(watched = false)` row. It is a test-only race fix and
    looks right.
  - `android/messages/.../wa/WaBatchScreen.kt` swaps `imePadding()` for `imeOverlapPadding(excludeNavigationBar =
    false)`. That follows owner rule (c), and `ImeOverlap.kt:36` has that parameter. `android/messages` belongs to the
    E6 session, but this edit is a rule fix, so keep it. Note it for the E6 session in `plans/e6-status.md` (one
    line, no code change there).
- **No production `imePadding` or `WindowInsets.ime` is left.** The only other match is
  `ContactEditorScreen.kt:1104`, which reads the inset to compute an overlap; check it is not used as padding.
- **The optics exist, in a stash.** `stash@{0}` ("blocked: kotlin-ui r17 r17-s4", base `c4a6df4`) holds the
  implemented round-17 Step 4:
  - `crates/rosty_app_core/src/cards/refraction.rs`, 523 lines with the glisse header, in the stash's untracked
    commit `stash@{0}^3`;
  - `card.rs` +197, `motion.rs` +61 and `tests.rs` +86;
  - `image` added as a `rosty_app_core` dependency;
  - a `THIRD_PARTY_NOTICES.md` line;
  - regenerated Rust goldens and three `cards.render*.answer.json` fixtures;
  - its "Step 4 result" text, with the constants, in `round-17.md`.
  It also carries unrelated work:
  - a Phone `KeypadViewModel` T9 race fix (drop late results when `dialed != typed`; HEAD does not have it);
  - a Stops `NoticeViewModel` ordering fix (E7 territory; HEAD does not have it);
  - re-recorded `e3.11-licences*` shell shots and x2 shots, both stale now.
  HEAD changed `card.rs` (+32) and `tests.rs` (+112) after the stash base (round-17 steps 3 and 6: the sibling
  `.glass` layer, the budgets, the parallel frame writer), so the stash will conflict.
- Other stashes (round-17 Step 1 never recorded these decisions; `## Stashes

Step 1 decisions (compared with `git diff stash@{n}^1 stash@{n}`, `rovsti` mapped to `rosty`):

- `stash@{1}` (r16-s4, Takumi gif/color_quant/image-webp, `cards/anim`, `scene`, `spring`): superseded. HEAD renders animated cards through `cards/card.rs` + `motion.rs` with the `image` crate's gif feature. Dropped.
- `stash@{2}` (r16-s3, cards FFI op, fixtures, contract tests): superseded by `crates/rosty_ffi/src/ops/cards.rs` in HEAD. Dropped.
- `stash@{3}` (r16-s2): all Kotlin pieces already in HEAD (developer mode, Logs routes and entries, About Version hold, `CommonSettingsPage.Logs`, 25 `dev_*` strings en/tr identical, `contacts_logs`, Phone settings entries, `RustLogPumpTest` sink argument). Rust `cards/mod.rs` spike superseded. Recovered: only the `SegmentedControl` 48 dp touch height, WITHOUT the stash's `.offset(y = ...)`: `requiredHeight` already centres the row on the 36 dp track, and the offset shifted the labels up by 6 dp (test `theLabelsStayCentredOnTheTrack` failed with it). Tests `everySegmentIsAtLeast48DpTall`, `theLabelsStayCentredOnTheTrack`; shot `e1.17-segmented-3-light.png` opened, labels centred. Not recovered: the Robolectric `jvmArgs` in `AndroidCommon.kt` (tests are green without them). Dropped.
- `stash@{0}` (r17-s4): kept for Step 2. Recovered now: Phone `KeypadViewModel` T9 race guard (drop the late result when `dialed != typed`), test `aLateT9ResultForOldTextIsDropped` (note: on a single main thread cancellation already covers most cases; the guard is defensive). Stops `NoticeViewModel` fix NOT applied: diff copied to `plans/e7-status.md` as a hand-over. Stale `e3.11-licences*` and x2 shots are not taken.
- GPUI stash (`stash@{4}` before, `stash@{1}` after the drops): untouched.

## Measurements

Checks profile, 4 cores, `RUN_BUDGETS=1 cargo test -p rosty_app_core --profile checks cards::tests:: -- budget --nocapture
--test-threads=1` (Step 3, optics on):

| What | Result | Target | Asserted |
|---|---|---|---|
| Static 720x900 PNG | 155 ms, 99,460 bytes | 150 ms | 300 ms, pass |
| GIF 720x900, 39 frames (2.6 s timeline) | 1.71 s, 2,564,229 bytes (2.45 MiB) | 1.5 s | 3 s, pass |
| GIF 540x675, 39 frames (shipped `ANIMATED` size) | 0.99 s, 1,786,384 bytes (1.70 MiB) | none | none |
| Animated WebP q75 720x900 (size only, not shipped) | 683,748 bytes, encode 124 ms | none | none |
| `librosty_ffi.so` arm64 release (`cargo build --release -p rosty_ffi --target aarch64-linux-android`, NDK 29) | 20,725,208 bytes (19.8 MiB) | none | none |

The `.so` was 20,682,984 bytes before the optics landed, so they cost 42 KB. Both asserted budgets pass. Both targets
are missed by a small margin (static +5 ms, GIF +0.2 s), which is inside the noise of this box (static ranged 155-198
ms across runs). Fallbacks applied: the lens node was already skipped outside frames 8-20 (`lens.amount > 0.01`) and
the panel filter was already left out in the first drop frames. The cache step was done: the maps' base64 `data:` URIs
are now built once per card (`refraction::png_uri`, `Optics.field_uri/rim_uri/lens.uri`) instead of once per frame;
GIF 1.80 s to 1.71 s, same bytes (goldens and determinism tests unchanged). 540 px stays the shipped size and is not
needed as a fallback.

## Step 3 result (r18-s3, frame proof)

Frames written by `writes_720_px_optics_frames_for_review` (`WRITE_CARD_FRAMES=1`, 720x900, 2.6 s timeline) to
`target/checks/cards/frames/{birthday-dawn-tr,contact-dawn-en}-NN.png`, all opened:

- Birthday Dawn TR frame 0: panel above the card, partly out of frame, faint (alpha low), the text ghosted; no rim
  line tearing. Frame 3: panel landed and tall (stretched, scale y > 1), full-contrast text, tint and rim intact.
  Frame 6: panel squashed wider, flatter (jelly), a few confetti at the top; the rim stays one clean line, the
  corners round, the content scales with it. Frame 9: the lens is a thin circle over "kutlu olsun" and bends
  the glyphs at its rim (the "u" of "kutlu", the "s" and "u" of "olsun" shift), the middle of the lens is sharp, the
  fringe is at most a pixel or two of cyan/orange on a few strokes; no glyph is torn or doubled. Frame 12: the lens
  has moved to "gün"/"olsun", weaker; Frame 15: the lens is past the title at the right edge (one small yellow tick
  on the panel's right edge, where the lens leaves the card; accepted). Frame 18: the lens is gone, a faint rim glint
  runs along the left and bottom-right edge (about 25-70 levels brighter than the rest pose on the rim line, seen by
  differencing against the last frame, subtle to the eye at 720 px but present). Frame 24: the glint has faded (rim
  difference 0 except confetti), confetti drifts down in pastel. Last frame (38): clean still, identical to the rest
  pose.
- Contact Dawn EN frame 0: the panel drops in, name and number ghosted, no QR yet; frame 8: panel landed, QR fully
  drawn, no lens circle anywhere, text sharp; last frame: clean still, QR crisp. The QR of the last 720 px frame
  decodes back to the vCard (asserted in the test through `rosty_core::contact_scan::decode_luma`).
- Constants kept as landed in Step 2, no retuning: band 30, thickness 12, lens shift 9 / scale 18, glint constant
  `5 * strength`, exponent 12, surface scale 6, fringe <= 1.5 px. Rejected values stay rejected: shift 15 and 22 (too
  strong, tears glyphs), glint constant 2 with exponent 22 (invisible).
- Kotlin screenshots: no Kotlin or Compose files changed in this step, so none were regenerated.

## Step 2 result (r18-s2, glisse optics landed on HEAD)

- `cards/refraction.rs` restored from `stash@{0}^3` (glisse header kept, listed in `android/THIRD_PARTY_NOTICES.md`
  with the full glisse MIT text already there). `Cargo.toml` (`image` dependency) and the notices line taken from the
  stash; the diff was checked by hand (3 lines).
- `card.rs`, `motion.rs`, `mod.rs` applied with `git apply -3`; `tests.rs` had one conflict (the budget tests of
  round 17 against the optics tests): both kept. HEAD's `.glass` sibling blur layer, `fit.rs`, the scoped-thread frame
  writer, the cancelled checks and the 264 px QR are untouched. The merge added a `.optic` sibling (`backdrop-filter:
  url()` only, no `blur()` in the same chain; left out while the panel is partly off the card) and a `.lens` capsule
  placed after the panel. The module doc in `card.rs` was corrected to say so.
- Constants as in the stash's Step 4 result (band 30, thickness 12, `refract = clamp(1 + 5(sx-1), 0.5, 1.6)`, lens shift
  9 / scale 18, `menuMorphOpen` 0.939/0.6 over 0.5-1.4 s, strength `sin(pi p)^0.8`, glint constant `5 * strength`,
  exponent 12, surface scale 6 over 0.9-1.6 s, fringe <= 1.5 px). `.glass` blur is 8 px (the stash value, not HEAD's 24):
  with 24 the bend is invisible on the blobs.
- Tests: refraction map symmetry and neutral centre (in `refraction.rs`), lens bends frames 8-20, glint lights the
  rim, no NETSCAPE block, GIF < 3 MB at 720 px, determinism, last frame = rest. New:
  `the_still_frames_carry_no_lens_and_no_glint`. The budget test now expects `frames().len() == 39` (the timeline
  counts the held still inside the 39).
- Measured (checks profile, `RUN_BUDGETS=1`): static 720x900 198 ms; GIF 720x900 2.05 s, 2.56 MB; 540x675 1.09 s, 1.79 MB.
- Goldens regenerated (8, `UPDATE_CARD_GOLDENS=1`) and all opened. Text inside the panel is sharp and un-bent in
  all eight (Latin, Turkish, long name, QR, number-only); the only change from round 17 is a slight bend of the
  background blobs and the tint within the 30 px rim band at the panel edge, the centre is flat; the rim line and the
  shadow are intact; nothing overflows the panel.
- Fixtures `cards.render.answer.json`, `.animated.` (39 frames, 1,835,715 bytes) and `.number_name.` regenerated with
  `UPDATE_FIXTURES=1`. `CardsOpsNativeTest` 5/5 green through `:core-bridge:testRustBridge`.
- Why r17-s4 was blocked (inferred; no log was written): the stash (17:37:36, 2026-11-16, base `c4a6df4 Merge people sync
  v3 app side`) was taken while a parallel session's merge had landed on the branch. Besides the finished Rust work it
  held Phone `KeypadViewModel` and Stops `NoticeViewModel` edits and re-recorded `e3.11` / `x2` Kotlin shots, which
  belong to other sessions or became stale, and it conflicted with round-17 steps 3 and 6 on `card.rs` and `tests.rs`.
  The step could not be committed from a shared, moving tree, so it was stashed as blocked; the Rust itself was
  complete. Not a technical failure of the optics (Takumi 2.18.2 applies the SVG backdrop filter).
- `stash@{0}` dropped.
