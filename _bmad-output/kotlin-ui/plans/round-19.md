# Round 19 plan: X.3, readable text on accent and photo backgrounds

Planned 2026-11-17 at base `bc22e17` (branch `ui-overhaul`). Epic: **X. Cross-app owner requests**, story **X.3**
(the only open X row). E6, E6-WA and E7 run in parallel sessions and are skipped. The next planner after X.3 plans
**E8 R1**.

Owner rules stay in force:
- (a) every pushed page has a glass back button and the full tab bar;
- (b) no `RustNative` call before `Rust.init`;
- (c) only `imeOverlapPadding`;
- (d) R8 keep rules for anything reached by name, checked with `dexdump`;
- GPL-3.0-or-later. Convx ports keep their header and the "Ported from Convx" line; glisse ports carry the glisse MIT
  header.
- X.3 rule: **avatar initials are not touched** (owner).

## State found (verified in code and PNGs, not trusted)

- `git status` is clean, and `stash@{0}` is the deprecated GPUI WIP, which is not touched. HEAD `bc22e17` closes
  X.2b (round 18).
- **Disk: 1.4 GB free (97 %).** The 6 GB rule is broken before any build. The space is in:
  - `android/*/build` (about 3.1 GB: catalog 610M, phone 676M, messages 638M, core-bridge 373M, contacts 311M, stops
    281M, ...);
  - `target/checks` 1.5G, `target/aarch64-linux-android` 895M, `target/release` 144M;
  - `~/.gradle/caches` 2.3G;
  - the scratchpad, 456M.
- **Cause 1 is confirmed in code.** `android/design/src/main/kotlin/dev/rosty/design/ios/foundation/IosColors.kt:68`
  has `val onAccent: Color get() = Color.White`.
  - `IosColorsBridge.kt` (`iosColorsFrom`) does not map it.
  - `ios/component/IosButton.kt:160-163` uses it for BorderedProminent/GlassProminent.
  - `controls/Buttons.kt:96-104` sends every `CapsuleStyle.Filled` capsule there (43 call sites), with tint defaulting
    to the accent. Some call sites pass their own tint: `phone/.../incoming/WaitingCallCard.kt:122,169`
    (`ANSWER_GREEN`, `DECLINE_RED`) and Messages `MessagesColors.OutgoingBubble` (#0051D5).
  - `RostyColors` already has the right pair: light accent #0051D5 / onAccent white, dark accent #5AB0FF / onAccent
    #001A3A (`theme/RostyColors.kt:49-50,70-71`). The bridge just drops it.
  - The same hard-coded white is in:
    - `ios/foundation/IosDesignSystem.kt:133` (`chromeOnProminent`, unused today);
    - `ios/component/IosSwipeActions.kt:260,268` (label and icon on the action colour; orange or green actions are
      light);
    - `IosTabBar.kt:271` (badge on systemRed).
- **Screenshots opened in this planning round (Read):**
  - `contacts/screenshots/e4.4-crop-light.png`: "Choose" is white on the #5AB0FF capsule (the crop page forces dark),
    clearly faint (about 2.3:1). Cancel and the title are fine.
  - `phone/screenshots/e5.1-setup-sheet-dark.png`: "Set as default", "Allow access" and "Open settings" are white on
    light blue, about 2.3:1. "2 of 5" in accent on the dark sheet is fine.
  - `phone/screenshots/e5.13-viewer-light.png` shows two failures on a warm, light photo:
    - the title "1 of 1" is pale grey on beige (about 1.9:1);
    - the back button is a yellow-tinted glass circle with a white chevron, also unreadable. **New finding, not in
      the X.3 audit list.**
    - The bottom action capsule over the dark area is fine.
  - `phone/screenshots/e5.6-incoming-photo-light.png`: "Incoming call" (white 78 %) and the number on a pale blue-grey
    top are weak. The name is large and passes 3:1 only just. The bottom labels on the darker half are fine.
  - `contacts/screenshots/x2-card-preview-gif-light.png` (round 18's last PNG): the sheet layout is right, and Share
    (white on #0051D5) passes. Inside the Rust-rendered card, "Turning 36" (mid grey on pink) looks borderline. The
    card art goes into the Step 6 sweep. Its "AY" initials stay as they are (owner rule).
- **Contrast tools that exist already:**
  - `design/.../theme/Contrast.kt`: `relativeLuminance`, `contrastRatio`, `glassOver`;
  - `ContrastTest` (token pairs);
  - `catalog/src/test/.../CollapsedBarLegibilityTest.kt` (the pattern: `captureToImage` plus node bounds plus a
    ratio);
  - `design/.../media/CallBackdrop.kt:47-57` `CallScrim` (fixed 0.25 at the top, 0.55 at the bottom) with
    `CallBackdropTest` (`contrastWhiteOn`);
  - the Phone captions `IncomingScreen.kt:463-464` `OnAura` = white and `OnAuraSecondary` = white 78 %, also used by
    `incall/InCallScreen.kt`, `MultiCallViews.kt` and `ToneKeypadPanel.kt`.
- `PhotoViewer` callers: `contacts/.../card/CardPhotoViewer.kt`, `ContactCardScreen.kt`, `ContactCardEntry.kt`;
  `phone/.../details/photo/PersonPhotoHost.kt`; `catalog/PhotosPage.kt`, `OverlayStatesPage.kt`; Messages
  (`MessageViewer.kt`, `WaViewer.kt`, `SmsThreadPage.kt`, E6 territory: their goldens may change, see Step 3).
- WCAG target for X.3:
  - body text 4.5:1;
  - large text (at least 24 sp regular or 18.7 sp bold) and glyph-only controls 3:1.
  - The 17 sp semibold capsule label counts as body text.

## Rules decided for this round

- **`readableOn(fill)`.** Text or glyph colour for a filled surface:
  - white when white reaches 4.5:1 on the fill;
  - otherwise whichever of white and `InkOnFill` (#0B0B0F, or the theme's `onAccent` when the fill is the theme
    accent) has the higher ratio.
  - The theme pair wins whenever `fill == colors.accent`, so the palette keeps its designed navy-on-light-blue look.
  - With this rule the light accent (#0051D5), the light destructive (#C8202A) and the outgoing bubble keep white.
    The dark accent, `ANSWER_GREEN` (#34C759), the dark destructive (#FF8A84) and orange/yellow swipe actions get
    dark ink.
  - Notification badges (white on systemRed, 3.5:1, bold numerals) stay white: an iOS convention, outside the owner's
    "white on light background". The reason is recorded in the checklist.
- **Scrims over photos adapt to the photo.** A scrim keeps today's alpha as its floor and rises only when the photo
  under the text band is bright enough to push white below the target. Dark photos stay as vivid as now.

## Steps

### Step 1: disk, then the contrast helpers (pure, JVM-tested)

1. Disk first, so that at least 6 GB is free (`df -h .`):
   - `rm -rf android/*/build`;
   - `rm -rf target/release` (CLAUDE.md allows `target/debug`, then `target/checks` or one module's build; never
     `target/` itself, never `cargo clean`);
   - prune stale Gradle caches (`~/.gradle/caches/transforms-*` and `build-cache-*` older than this week);
   - if still short, `target/aarch64-linux-android`.
   - Record the before and after numbers.
2. `design/.../theme/Contrast.kt`: add these (original code, GPL header of the file):
   - `fun readableOn(fill: Color, preferred: Color = Color.White, ink: Color = InkOnFill, minRatio: Double =
     4.5): Color`;
   - `fun RostyColors.onColorFor(fill: Color): Color` (returns `onAccent` when `fill == accent`, otherwise
     `readableOn(fill)`);
   - `fun scrimAlphaFor(bandLuminance: Double, foreground: Color, target: Double, floor: Float, max: Float = 0.8f):
     Float`, the smallest black-scrim alpha at which `foreground` reaches `target` over a band of that mean linear
     luminance. Solved in closed form (L' = L·(1-a)), clamped to [floor, max].
3. `:testing`: new `TextContrast.kt` with `fun textContrast(image: ImageBitmap, bounds: Rect): Double`.
   - It splits the region's pixels into two luminance clusters (2-means, at most 8 iterations), drops the
     anti-aliased middle 20 %, and returns the WCAG ratio of the two cluster medians.
   - Add `assertReadable(image, bounds, min, what)`, with a message that prints the ratio and both colours.
4. Tests:
   - `ContrastHelpersTest` (design): white stays on #0051D5, #C8202A and #007AFF-like fills that pass;
     #5AB0FF -> #001A3A via `onColorFor`; #34C759 and #FF9500 -> ink; `scrimAlphaFor` on white returns at least 0.53
     for white text at 4.5, and on a dark band returns the floor; the alpha is monotonic in luminance.
   - `TextContrastTest` (testing): synthetic bitmaps of black on white (about 21:1), white on #5AB0FF (2.3 ± 0.1), and
     anti-aliased text drawn with `Canvas.drawText` (within 10 % of the ratio of the pure colours).

Acceptance: the checks are green (`SKIP_RUST=1 bash scripts/kotlin-ui-check.sh` is enough here, because no Rust
changed), there is at least 6 GB free, and there are no golden changes.

### Step 2: filled controls in `:design` read on every fill

- `IosColors.kt`: `onAccent` becomes a constructor property with default `Color.White`, which keeps Convx's defaults
  for `lightIosColors`/`darkIosColors`. The "Ported from Convx" header gets a "Changes for Rosty" note.
  `IosColorsBridge.iosColorsFrom` maps `onAccent = colors.onAccent`.
- `IosButton.kt` (BorderedProminent, GlassProminent): `contentColor` = `colors.onAccent` when `resolvedTint ==
  colors.accent`, otherwise `readableOn(resolvedTint)`. The pressed or disabled alpha is unchanged.
- `chromeOnProminent` (IosDesignSystem.kt:133) becomes a `@Composable` getter over `IosTheme.colors.onAccent`, or is
  deleted if it is still unused.
- `IosSwipeActions.kt:260,268`: the label and icon use `readableOn(action.background)`.
- `IosList.kt:404` (icon tile glyph): use `readableOn(tile)` with `minRatio = 3.0` (glyph only).
- Do not touch: `Avatar.kt` initials and glyphs (owner), `IosTabBar` badge (see Rules), `PhotoCrop.kt` (dark
  surface).
- Tests:
  - `FilledButtonContrastTest` (design, Robolectric): `CapsuleButton(style = Filled)` in light and dark, with the
    default accent and with tints `colors.destructive`, `colors.success` and `Color(0xFF34C759)`. Each case is
    captured with `captureToImage`, and `assertReadable(..., 4.5)` runs on the label bounds.
  - `SwipeActionContrastTest`: orange, red and blue actions, both themes.
  - `ContrastTest` gains `iosColorsFrom(theme).onAccent` on accent ≥ 4.5 for both themes.
- Goldens: re-record the `:design` and `:catalog` shots that change (`RECORD=1`, then verify). Open and judge, at
  minimum:
  - `catalog` `e1.29-*` (buttons, swipe) dark;
  - `e1.6-*` buttons dark;
  - `e1.17-*`, if a prominent control is in them.
  - In each, the filled labels are dark navy on light blue in dark, white on deep blue in light, and the layout is
    unchanged.

### Step 3: app goldens that show filled controls, with contrast proof

- Re-record the changed goldens in `:contacts`, `:phone`, `:stops`, `:shell` and `:messages`. The gate runs Messages
  shots. Messages source is not edited. Add one line to `plans/e6-status.md` and one to `plans/e7-status.md` saying
  that the goldens changed because of the `:design` `onAccent` fix (round 19 step 3), so that the parallel sessions
  re-record after merging.
- Contrast assertions inside the existing shot tests, where the screen is composed:
  - `contacts` `E44*` crop: "Choose";
  - `phone` setup sheet (`e5.1-setup-sheet-*`): "Set as default", "Allow access", "Open settings";
  - `WaitingCallCard` Answer/Decline capsules (green and red: they now get ink);
  - Contacts Card Studio "Share" (`x2-card-preview-*`).
  - Each one calls `assertReadable(..., 4.5)` in light and dark.
- Open with Read and judge:
  - `contacts/e4.4-crop-light`, `contacts/e4.4-crop-dark`;
  - `phone/e5.1-setup-sheet-dark`, `phone/e5.1-setup-sheet-light`;
  - the waiting-call golden (`e5.7b-*waiting*` or `e5.6-waiting*`, whichever exists);
  - `contacts/x2-card-preview-gif-dark`;
  - one Messages golden with a filled capsule (schedule or backup sheet).
- Expected: the labels are clearly legible, and nothing else in the frame moved (diff the old and new PNG sizes,
  then eyeball).

### Step 4: photo viewer chrome over light photos

- `design/.../media/PhotoViewer.kt` (around lines 391-410):
  - Add a `ViewerScrim` layer under `TopFadeBlur` and `FrostedTopBar`: a vertical black gradient from `topAlpha` at
    y=0 to 0 at (status bar + bar height + 24 dp). The same applies to the bottom action band (from the bottom edge to
    the actions' top minus 24 dp).
  - `topAlpha`/`bottomAlpha` = `scrimAlphaFor(bandLuminance, Color.White, 4.5, floor = 0.35f)`.
  - `bandLuminance` is the mean linear luminance of the photo's top band and bottom band. It is computed once per
    page from the decoded bitmap: downsample to 16 x 32 on `Dispatchers.Default`, through the existing Coil image
    state. Before it is known, use `floor`.
  - The scrim alpha animates with the existing chrome alpha (no new motion). It follows the page with
    `snapshotFlow { viewer.page }` and a 200 ms `tween`; under reduce motion it snaps.
- The viewer chrome is dark by design: provide a dark `RostyTheme` (or the existing dark colour set) to the chrome
  `Box`. The title then takes white `labelPrimary` in both app themes, and `FrostedTopBar`'s glass back circle takes
  the dark glass tint and a white glyph. Check that `LocalIosBackdrop` still samples (the real-path check from E1.26
  must stay green).
- Tests:
  - `ViewerChromeContrastTest` (design): photos of pure white, beige (#EBC9A0, as in the golden) and dark. Each is
    checked in light and dark: title ≥ 4.5, back chevron ≥ 3.0 (glyph), action glyphs ≥ 3.0, and the dark photo's
    scrim equals the floor.
  - `ViewerScrimTest` (pure): the band-luminance math on a synthetic bitmap.
- Goldens:
  - re-record `phone/e5.13-viewer*`, `contacts/e4.12-*` viewer shots and the catalog photos page;
  - add `phone/e5.13-viewer-white-light` and `-dark` (a near-white test photo from `:testing` `portraitImage` with a
    light wall, or a flat #F4F4F4 image) as the worst case;
  - open `e5.13-viewer-light`, `e5.13-viewer-white-light` and `e5.13-viewer-dark`: the title reads, the back circle is
    dark glass with a white chevron, and the photo is still visible under a soft top shade (no hard band).

### Step 5: call screens over light contact photos

- `design/.../media/CallBackdrop.kt`:
  - `CallPhotoState` gains `topLuminance`/`bottomLuminance`, computed once after load from a 16 x 32 downsample of the
    decoded bitmap (top 40 % and bottom 35 % bands) on `Dispatchers.Default`.
  - `CallScrim` gains `topAlphaFor(l)` = `scrimAlphaFor(l, OnAuraSecondaryColor, 4.5, floor = TopAlpha)` and
    `bottomAlphaFor(l)` (`floor = BottomAlpha`).
  - The gradient uses those values, and the fade-in stays the 300 ms `FadeMillis`.
- Phone: move `OnAura`/`OnAuraSecondary` to one `phone/.../call/CallInk.kt` (shared by Incoming, In-call, MultiCall,
  ToneKeypad), and raise `OnAuraSecondary` to white 85 % if the solver still exceeds 0.8 for a pure white photo.
  WhatsApp incoming uses the same path.
- Tests:
  - `CallBackdropTest`: white photo → top alpha such that `contrastWhiteOn` ≥ 4.5 for white 78-85 %; dark photo →
    alphas equal 0.25/0.55; mid grey in between.
  - `IncomingScreenTest`/`InCallScreenTest`: `assertReadable` on "Incoming call", the number, "Remind me", Decline,
    Answer, and the in-call timer and caption, with a white photo (`imageProducer` returning a flat #F2F2F2 bitmap),
    both themes.
- Goldens:
  - re-record `phone/e5.6-incoming-photo-*`, `e5.8-wa-incoming-photo-*`, `e5.7-incall-photo-*`, and the
    `x1-wa-avatar-incoming-*` goldens if they change;
  - add `e5.6-incoming-photo-white-light`;
  - open `e5.6-incoming-photo-light`, `e5.6-incoming-photo-white-light` and `e5.7-incall-photo-light`: the captions
    read, the photo still shows through at the middle, and there is no visible band edge.

### Step 6: sweep the rest so nothing else regresses

- Scratch audit (not committed): a Python script in the scratchpad, run with `python3 -I` and PIL. It walks every
  `android/*/screenshots/*-light.png` and `*-dark.png`, finds text-like connected components (small, high local
  contrast, height 8-40 px) and reports any whose 2-cluster ratio is below 3.0. Triage the list by opening each hit.
  Expected false positives: avatar initials (owner: leave them), map tiles, glass blur. Known candidates to check:
  - the Stops floating controls and labels over the map (`stops/screenshots`);
  - toasts and undo pills over light content;
  - `ChipRow` selected chips in dark;
  - the Contacts Card Studio swatch row;
  - the Rust card art's secondary text: "Turning 36" on the pink `dawn` palette. If a card palette's secondary text is
    below 4.5:1 on the panel, darken that palette's `secondary` in `crates/rosty_app_core/src/cards/` (Rust test that
    checks every palette's text against the panel colour at 4.5) and regenerate the Rust card goldens and the x2
    Kotlin goldens. This is the only Rust change of the round. Then run the CLAUDE.md Rust checks for the gate
    package set.
- Fix each real hit with the Step 1 helpers (`onColorFor`/`readableOn` or a scrim), and give each fix an
  `assertReadable` in its existing test.
- Opened PNGs and the audit's before/after hit counts go into the X.3 Evidence.

### Step 7: checklist, full gate, close X.3

- `docs/kotlin-ui/checklist-e4.md` and `checklist-e5.md`: an "X.3 checklist record" (contrast section of
  `references/checklist.md`), including the badge exception and its reason.
- Full gate:
  - `cargo fmt --all`;
  - clippy and test with `--profile checks` on the gate's package set (the retired GPUI crates do not compile);
  - `cargo check --release -p rosty_android --target aarch64-linux-android`;
  - `bash scripts/kotlin-ui-check.sh` (EXIT 0).
- No new class is reached by name, so there is no keep rule to add. Still run `dexdump` on the Phone release APK to
  confirm that `RustNative` is kept. State the APK paths and sizes, and the catalog APK path
  (`android/catalog/build/outputs/apk/release/`), in the Evidence.
- Disk: leave at least 6 GB free (delete `android/*/build` of modules not needed after the gate).
- `epics.md`:
  - X.3 → `done (round 19)`, with an Evidence block: commits, every test named above, the PNGs opened and what was
    seen, and the audit hit counts;
  - the X header note "Next planner: E8 R1".

## Commit plan

One commit per step, message `Kotlin UI (X. Cross-app owner requests: X.3 readable text on accent and photo
backgrounds): <step title>`, ending with the session attribution lines.
