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

(filled in by Step 3)
