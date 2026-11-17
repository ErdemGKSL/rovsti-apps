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
- Other stashes (round-17 Step 1 never recorded these decisions; `## Stashes` in round-17.md is still empty):
  - `stash@{1}` (r16-s4, Takumi Cargo lines): superseded by HEAD.
  - `stash@{2}` (r16-s3, `rovsti_*` paths, cards ops): superseded by `crates/rosty_ffi/src/ops/cards.rs`.
  - `stash@{3}` (r16-s2, `rovsti_*` paths): shell `AboutPage`/`CommonSettingsGroup`/`IosSettingsGroup` plus 26 en/tr
    strings, `SegmentedControl` (+12), Phone settings entries/route, `RustLogPumpTest`, and the cards `mod.rs` spike.
    Compare each with HEAD.
  - `stash@{4}`: the deprecated GPUI WIP. **Never touch it.**
- **Disk: 2.9 GB free (rule: 6 GB).**
  - `target/checks` 1.4 GB, `target/aarch64-linux-android` 698 MB, `target/release` 144 MB;
  - `~/.gradle/caches` 2.9 GB;
  - `android/catalog/build/outputs/apk/release/catalog-release-unsigned.apk` exists.
- minSdk is 34 (`RostySdk.MIN`), so `ImageDecoder` and `AnimatedImageDrawable` are always available.
- The preview in `CardStudioSheet.kt` (around line 270) decodes a still with `BitmapFactory`. A GIF therefore shows only
  frame 0.
- The sheet has only `Detent.Medium` (`CardStudioSheet.kt:110`).

## Screenshots opened in this planning round

- `android/contacts/screenshots/x2-share-still-menu-tr-light.png`:
  - The menu opens upward from "…", right-aligned. "Sabit görsel olarak paylaş / kaydet" fits, and the Hareketli
    toggle is readable. OK.
  - **R18-F1:** at Medium the card preview is only about 37 % of the sheet width (about 340 of 923 px), with wide
    empty margins on both sides. The card cannot be judged at that size.
- `android/contacts/screenshots/x2-card-preview-gif-dark.png`:
  - The headline wraps inside the panel. The swatches sit on one row. Save and Share are dimmed while the render runs.
    The ring overlaps the card corner (accepted in round 17).
  - **R18-F2:** this "gif" shot is identical to the still shot. Nothing shows that the card moves.
- `android/contacts/screenshots/x2-share-animated-fs2-light.png`: at fs2 the segmented labels and the buttons fit, and
  the swatches stay on one row. OK.
- `android/catalog/screenshots/e1.17-switch-off-on-light.png`: the resting switch thumb is the iOS 26 capsule (about
  60 % of the track), the same shape as in the x2 shots. **R17-F4 is not a bug**: no change.
- The grey side shading of the light sheet is the glass nuance (CLAUDE.md), so no fix.

## Steps

### Step 1. Disk, the carry-over, stash triage

- Make sure no other agent is writing:
  - `ps aux | grep -E 'cargo|gradle'` shows nothing from another session;
  - `git status` stays the same for a minute.
- Free disk until `df -h .` shows 6 GB or more:
  1. delete `target/debug` if present;
  2. delete `target/checks` (1.4 GB);
  3. delete the `android/<module>/build` folders except `android/catalog/build/outputs`;
  4. if still short, run `rm -rf ~/.gradle/caches/build-cache-* ~/.gradle/caches/transforms-*` (re-downloaded or
     rebuilt on demand).
  - Never `cargo clean`, and never delete `target/` itself.
- Review the carry-over `7ffcc39` (above). Then run:
  - `:contacts:testReleaseUnitTest --tests '*PeopleSyncJobTest*'`, three times in a row, to prove the race fix holds;
  - `:messages:compileReleaseKotlin`.
  - Add the E6 note line to `plans/e6-status.md`.
- Stashes:
  - For each of `stash@{1..3}`, compare file by file with HEAD (`git diff stash@{n}^1 stash@{n}`, mapping `rovsti` to
    `rosty`).
  - From `stash@{3}`, recover only what HEAD lacks and what is still correct: the About/licence rows and strings, the
    `SegmentedControl` change, the Phone settings entries. Each recovered piece needs a test or a screenshot that
    proves it.
  - Recover the Phone `KeypadViewModel` race fix from `stash@{0}`, with a `KeypadViewModelTest` case: type "12", then
    "1" quickly; the late T9 result for "12" is dropped.
  - Do **not** apply the Stops `NoticeViewModel` fix. Copy its diff into `plans/e7-status.md` as a hand-over for the
    E7 session.
  - Write every decision under "Stashes" below.
  - Keep `stash@{0}` until Step 2 has landed it. Drop `stash@{3}`, `stash@{2}`, `stash@{1}` in that order. Never touch
    the GPUI stash.
- Acceptance:
  - 6 GB or more free;
  - the targeted Gradle tests are green;
  - a commit "X.2b: carry-over reviewed, stash triage";
  - the stash decisions are recorded.

### Step 2. Land the optics from `stash@{0}` onto HEAD

- Restore the files:
  - `git show stash@{0}^3:crates/rosty_app_core/src/cards/refraction.rs > crates/rosty_app_core/src/cards/refraction.rs`;
  - `git checkout stash@{0} -- crates/rosty_app_core/Cargo.toml android/THIRD_PARTY_NOTICES.md`, then check the diff
    against HEAD by hand;
  - for `card.rs`, `motion.rs`, `mod.rs` and `tests.rs`, use a 3-way merge
    (`git diff stash@{0}^1 stash@{0} -- <file> | git apply -3`) and resolve against HEAD.
- Keep HEAD's round-17 work:
  - the sibling `.glass` layer (blur + saturate + tint, radius-clipped);
  - `fit.rs`;
  - the scoped-thread frame writer and `cancelled` checks;
  - the 264 px QR.
- Add the stash's `.optic` sibling layer: `backdrop-filter: url(data:...)` only. **Never put `blur()` and `url()` in
  the same `backdrop-filter`** (the measured `MirroredRegion` misalignment from the stash notes). Leave `.optic` out
  while the panel is partly off the card.
- Keep the `.lens` capsule after the panel, so its backdrop holds the title text.
- Constants (from the stash notes; record any change):
  - panel band 30 design px, thickness 12;
  - `refract = clamp(1 + 5*(sx-1), 0.5, 1.6)`;
  - lens shift 9 (scale 18), dome sag 0.5, depth 0.4, `menuMorphOpen` 0.939/0.6 from 0.5 to 1.4 s, strength
    `sin(pi*p)^0.8`;
  - glint: light 20 px above the top edge, x from -0.2 to 1.2 over 0.9-1.6 s with smoothstep,
    `specularConstant = 5*strength`, exponent 12, `surfaceScale 6`;
  - fringe `1 +- min(0.035, 0.75/scale)`, at most 1.5 px.
- Glisse attribution:
  - `refraction.rs` keeps its glisse header;
  - any glisse formula added to `motion.rs` needs the header there too;
  - `THIRD_PARTY_NOTICES.md` lists `refraction.rs` (glisse's full MIT text is already there; check it).
- Tests, in `cards/tests.rs` (from the stash, adapted):
  - maps are symmetric and have zero displacement at the centre;
  - the lens displacement is non-zero in frames 8-20 and zero in the still;
  - the glint is zero in the still;
  - the last frame equals `Motion::rest()`'s render;
  - play-once bytes (no NETSCAPE block);
  - the GIF at 720 px is under 3 MB;
  - the output is deterministic.
- Regenerate the goldens with `UPDATE_CARD_GOLDENS=1` (the rest pose now keeps the edge bend) and the three
  `cards.render*.answer.json` fixtures.
  - Open all 8 goldens with Read. Check that every text block is still inside the panel (`assert_text_inside`), the
    edge bend shows only in the rim band, and the name is not distorted in the still.
- Find out why r17-s4 was "blocked". Run the Rust half of the gate and record the cause here. Likely suspects:
  - the fixture answers;
  - the stale x2 shots;
  - the budget assert.
- Then `git stash drop stash@{0}`.
- Checks:
  - `cargo fmt --all`;
  - clippy and tests for `rosty_app_core` and `rosty_ffi` with `--profile checks`;
  - `cargo check --release -p rosty_android --target aarch64-linux-android`;
  - `:core-bridge:testRustBridge` (`CardsOpsNativeTest`: the GIF still decodes, with no NETSCAPE block).

### Step 3. Frame proof and budgets with the optics on

- Write frames 0, 3, 6, 9, 12, 15, 18, 24 and the last frame of birthday-dawn-tr (720 px) to
  `target/checks/cards/frames/`, plus the contact-dawn-en frames 0, 8 and the last frame (behind an
  `#[ignore]`-gated test, or `WRITE_CARD_FRAMES=1`).
- Open each one with Read and judge:
  - the drop and the jelly squash (area kept, no tearing at the rim);
  - the lens bends the letters at its rim and leaves the middle sharp, with a thin fringe and no torn glyphs;
  - the glint is visible on the top rim and then fades;
  - the still is clean;
  - the Contact card has no lens (its panel settles with `morph` and the QR fades in) and its QR still decodes on the
    last frame.
  - Write what was seen into this file. Tune if needed. Rejected values from round 17: shift 15 and 22, glint
    constant 2 with exponent 22.
- Re-run the budgets with the optics on: `RUN_BUDGETS=1 cargo test -p rosty_app_core --profile checks cards::tests::
  -- budget --nocapture --test-threads=1`.
  - Static: under 150 ms (asserted at 300 ms).
  - Birthday GIF at 720 px, 39 frames: under 1.5 s (asserted at 3 s).
  - If a budget is missed: render the lens filter only on frames 8-20 (already zero elsewhere, so skip the node), then
    cache the panel-filter data URI per `refract` value. The shipped 540 px remains the last fallback.
  - Update "Measurements" with the GIF size at 540 and 720 px and the `librosty_ffi.so` arm64 release size.

### Step 4. In-sheet GIF playback, played once

- New file `android/design/src/main/kotlin/dev/rosty/design/media/OneShotAnimatedImage.kt`. It is our own code; its
  API is generic so Messages can adopt it later (do not touch `android/messages`).
  - `@Composable fun OneShotAnimatedImage(path: String, contentDescription: String?, modifier: Modifier, playKey: Any,
    contentScale: ContentScale)`.
  - It decodes off the main thread with `ImageDecoder.createSource(File(path))` into `AnimatedImageDrawable`, sets
    `repeatCount = 0` (play once and hold the last frame), and draws it through a `drawBehind`/`Canvas` that is
    invalidated by a `Drawable.Callback` driven by a frame clock. `start()` runs once per `playKey`.
  - With `LocalReduceMotion` on, it does not start, and it shows the last frame (the still). A still file (not
    animated) falls back to a `Bitmap`.
  - A decoder seam (`AnimatedImageSource`) lets Robolectric tests inject a fake, because Robolectric cannot animate a
    real GIF.
- `CardStudioSheet.kt` preview:
  - When `preview.animated` is true, use `OneShotAnimatedImage`; the still path keeps `Image`.
  - It plays once when the render lands (`playKey = preview.path`).
  - A tap on the preview replays it, with the glass press scale from `:design` and a `selection` haptic.
  - TalkBack action "Replay" in en, "Tekrar oynat" in tr.
  - Keep the `Crossfade` on path change.
- Tests:
  - `OneShotAnimatedImageTest` (fake source): start is called once per key, never under reduce motion, and the drawable
    is released on dispose.
  - `CardStudioSheetTest`: a tap on an animated preview replays it; a still preview has no replay action.
- Shots (re-record and open):
  - `x2-card-preview-gif-light|dark`, with the fake source frozen mid-lens (a Rust frame-12 PNG is copied into the
    test resources as the fake's current frame). **R18-F2:** the golden must visibly differ from the still shot (the
    bent name and the glint);
  - `x2-card-preview-gif-reduce-motion-light`, which shows the still.

### Step 5. Sheet layout: a bigger preview (R18-F1)

- `CardStudioSheet`:
  - `detents = persistentListOf(Detent.Medium, Detent.Large)`;
  - the preview takes the leftover height (`weight(1f)`, `aspectRatio` with `matchHeightConstraintsFirst`);
  - at Medium it is at least 55 % of the sheet width on a 411 dp wide phone: let the segmented control and the toggle
    row share one compact header if needed, as in the `layout.md` sheet anatomy;
  - at Large the preview grows up to the card's natural 3:4 at the full content width (minus 2 x `Spacing.l`).
  - Swatches stay on one row and the buttons stay above the gesture bar (`navigationBarsPadding` inside the sheet
    content), at fs2 too.
- Compose test (`CardStudioSheetTest`):
  - the preview width is at least 0.55 x the sheet width at Medium;
  - at Large the preview width equals the content width or its height reaches the available height;
  - no node overlaps another (the toggle, swatches and buttons are disjoint).
- Re-record every `x2-*` golden in `android/contacts/screenshots/` (the stills changed in Step 2, so all of them are
  stale): light, dark, tr and fs2, plus a new `x2-card-preview-birthday-large-light`. Open every one. Check:
  - no text crosses the card panel;
  - the swatches stay on one row;
  - the menu leaves the toggle readable;
  - the buttons are clear of the bottom edge;
  - the preview is visibly larger than in round 17.

### Step 6. Release path, checklist, close X.2b

- Run `.claude/skills/convx-motion-compose/references/checklist.md` over the preview playback and the sheet
  (motion, reduce motion, a11y and TalkBack order, haptics, insets). Add an "X.2b checklist record" to
  `docs/kotlin-ui/checklist-e4.md`.
- Full gate:
  - `cargo fmt --all && cargo clippy --workspace --all-targets --profile checks && cargo test --workspace --profile
    checks`. Use the gate's package set if the retired `apps/*` fail (CLAUDE.md).
  - `cargo check --release -p rosty_android --target aarch64-linux-android`
  - `bash scripts/kotlin-ui-check.sh`
- Build `:contacts:assembleRelease`.
  - Run `bash scripts/check-catalog-apk.sh` on it.
  - Run `dexdump -d` and grep (names from `mapping.txt`): `RustNative`, `NativeCardsOps`, the card model serializers,
    and the new `OneShotAnimatedImage` class (reached by code, so no keep rule is expected; confirm it). Record the
    output here.
  - State the APK path `android/contacts/build/outputs/apk/release/contacts-release-unsigned.apk` and its size.
- `docs/kotlin-ui/acceptance.md`, Contacts entry: "the birthday GIF in Card Studio plays once with the lens over the
  name and the rim glint, then holds the still; tap replays; shared GIF plays once in the target app".
- `epics.md`:
  - set X.2b `done` with Evidence: commits, the frames and goldens opened and what was seen, the budgets, the sizes,
    the dexdump result;
  - set the next planner to **E8 R1**.

## Stashes

(filled in by Step 1 and Step 2)

## Measurements

(filled in by Step 3)
