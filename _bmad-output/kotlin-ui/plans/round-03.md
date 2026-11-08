# Round 03 plan (re-planned): E1 R3 = relicense to GPL, port Convx's glass stack and iOS component library

Planner: PLAN agent, round 3 (second pass). Base: `6bebfbe` (branch `ui-overhaul`; never push to `main`).
Epic file: `_bmad-output/kotlin-ui/epics.md`. Supersedes the first round-3 plan, kept as
`_bmad-output/kotlin-ui/plans/round-03-e1.17-e1.22.md` (only its Step 1, R2 fixes F3/F4, was implemented, in
`6bebfbe`; its Steps 2-8 move to round R4 after this round, re-scoped below).

Owner decisions this plan follows (user message: "tamam okey projemizi gpl yap kodları çok yüksek benzerlikte taşı" =
"OK, make our project GPL and port the code with very high similarity"):

1. Rovsti becomes **GPL-3.0-or-later**. Convx (GPL-3.0) code is ported **as close to verbatim as possible**: keep its
   structure, algorithms, constants, shaders and names where sensible; adapt only package names, our theme/tokens, our
   data models, and remove music-player specifics. The old "never copy Convx" rule is revoked.
2. **Glass bug, top priority**: on a real phone the catalog APK shows no liquid glass anywhere. Our hand-written engine
   (`android/design/src/main/kotlin/dev/rovsti/design/glass/*`) is replaced by Convx's device-proven stack.
3. Then Convx's iOS component library replaces our hand-written equivalents where Convx's is better.

`$CX` below = `/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/ref/Convx`,
`$CXK` = `$CX/app/src/main/kotlin/com/convx/music`, `D` = `android/design/src/main/kotlin/dev/rovsti/design`,
`DT` = `android/design/src/test/kotlin/dev/rovsti/design`, `CAT` = `android/catalog/src/{main,test}/kotlin/dev/rovsti/catalog`.

## State found (verified, not trusted)

- `git log`: R2 complete (`d0f16a5`..`d0dcf08`); the first R3 plan was committed as `078ca6b`; `6bebfbe` implements its
  Step 1: `SheetMath.kt:190 stackedTopPx`, `:199 upperDetentOffset`, `OverlayHost.kt`/`SheetEntry.kt` step-back,
  `ActionSheet.kt` single shadow, tests `SheetGestureTest.aSecondSheetStepsTheFirstBackAndClosingItRestoresScaleOne`,
  `predictiveBackOnTheTopSheetLeavesTheLowerOneAlone`, `ActionSheetTest` haze patch. F3/F4 are done.
- Working tree: the old plan file is staged as a rename (`round-03.md -> round-03-e1.17-e1.22.md`, kept and committed with
  this plan). **Untracked WIP** `D/controls/SwitchRules.kt` (153 lines, glisse header, pure gesture rules for a switch,
  no test, no caller). Decision: it is superseded by Convx's `IosToggle`/`LiquidToggle` (Step 7). Step 1 moves it out of
  the tree into the scratchpad (`$SCRATCH/wip/SwitchRules.kt`) so it cannot break spotless/lint; Step 7 may reuse its
  numbers only if Convx's toggle lacks a rule (e.g. the 24 dp "near" tap tolerance).
- `stash@{0}` is the deprecated GPUI layout WIP: leave it, do not pop it.
- Licence today: `Cargo.toml:9 license = "MIT OR Apache-2.0"` (`[workspace.package]`; every crate uses
  `license.workspace = true`, verified for `crates/*`, `apps/*`, `services/wa_gateway`). No root `LICENSE` file. README has
  no licence section. `android/THIRD_PARTY_NOTICES.md` lists only glisse (full MIT text). No `deny.toml`.
  `.claude/skills/convx-motion-compose/SKILL.md:16-31` section 0 says "Convx: never copy"; `docs/kotlin-ui/architecture.md:19`
  ground rule says the same.
- Our glass engine: `D/glass/` `GlassTier.kt` (125), `GlassSource.kt` (63), `GlassNode.kt` (266), `GlassSpec.kt` (173),
  `Lens.kt` (181), `LensShader.kt` (130), `Rim.kt` (202), `GlassEffect.kt` (27). Callers: `D/chrome/{BackButton,
  LargeTitleScaffold,RovstiTabBar,Toolbar}.kt`, `D/controls/Buttons.kt`, `D/overlay/{ActionSheet,Alert,ButtonMenu,
  ContextMenuEntry,ContextMenuPanel,Popover,PreviewCardEntry}.kt`, catalog `ButtonsPage`, `GlassPage`, `LargeTitlePage`,
  `TabBarPage`, `ToastsPage`; tests `DT/glass/*` (6 files), `CAT/{E18ShotTest,FlatLegibilityTest,GlassPageTest,
  RuntimeShaderProbeTest,ScaffoldTest,TabBarGestureTest}.kt`, `android/testing/.../ShotTest.kt`.
- **Why there is no glass on the device (what the code shows):**
  1. Overlays get no source: `Popover.kt:80-81`, `ContextMenuPanel.kt:84-85`, `ButtonMenu.kt:257-270` compute
     `glassTier(conditions.copy(sourceAvailable = source != null))`; the catalog never provides a source to overlays,
     so every menu, popover and context menu is `Flat` (opaque fill). `ButtonMenu.kt:270` even falls back to a fresh,
     empty `rememberGlassSource()`.
  2. Only 4 catalog pages create a source (`GlassPage`, `ButtonsPage`, `TabBarPage`, `LargeTitlePage`); the catalog home
     and every other page have none.
  3. Tests never exercise the real path: `ShotTest` forces `Flat` (E1.5 evidence: "ShotTest forces Flat"), so the
     goldens hide whether `GlassNode` ever draws a backdrop. Screenshot `e1.7-tabbar-rest-light` (opened this round):
     the tab bar is an opaque near-white capsule with a hairline, list rows only faintly visible through it: no blur,
     no lens, no rim. `e1.13-sheet-pushback-light` (opened): the upper sheet is opaque white, the lower one flat grey;
     no material. `e1.5-glass-liquid-over-stripes-light` (opened): blur, saturation and lens fringes DO render under
     Robolectric NATIVE when forced, so the effect chain itself runs; what fails is the wiring (sources, tiers,
     recording) on real screens.
  The port replaces all of this; Step 4 adds a test that fails if any glass surface on API >= 33 with default settings
  does not draw through the backdrop effect.
- Robolectric runs `RenderEffect` blur and AGSL (`RuntimeShaderProbeTest`, result in `docs/kotlin-ui/api-notes.md`), so
  the real Convx path can be screenshot-tested: **no test may force a flat fallback from now on** except tests that
  test the fallback itself.
- Convx sources for this round (sizes in lines):
  - Backdrop engine `$CXK/ui/component/backdrop/**` 42 files, 5141 lines. Headers: most "Vendored from Kyant0/backdrop
    v2.0.0 ... Apache License 2.0" (with Convx changes), `catalog/components/Liquid*.kt` + `DragGestureInspector.kt`
    "Vendored from Kyant0/AndroidLiquidGlass (kmp branch) ... Apache License 2.0", `catalog/utils/InteractiveHighlight.kt`
    and `DampedDragAnimation.kt` "Copyright 2026, compose-miuix-ui contributors, SPDX Apache-2.0", Convx-original
    (GPL-3.0): `GlassPool.kt`, `backdrops/BackdropFreeze.kt`, `NavTransitionFreeze.kt`, `BackdropStaticRegion.kt`.
  - Shapes `$CXK/ui/component/shapes/**` (1781 lines, "Vendored from Kyant0/Capsule ... Apache License 2.0"):
    `ContinuousRoundedRectangle`, `ContinuousCapsule`, continuities, path, core.
  - Glass layer: `$CXK/ui/component/GlassEffect.kt` (515: `GlassEffectConfig`, `GlassStyle`, `GlassComponent`,
    `glassResolutionScale`, `isGlassAllowed`, `LocalAppBackdrop`, `LocalAppBackdropFrozen`, `Modifier.liquidGlass`),
    `TopFadeBlur.kt` (267, uses Haze 1.3.1, Apache-2.0), `$CXK/ui/theme/AppleTokens.kt` (190), `GlassTintTokens.kt`
    (234), `$CXK/AppBackdropState.kt` (107), helpers `$CXK/ui/utils/ModifierExt.kt` (`additiveChrome` :63,
    `outerDarkRim` :293), `$CXK/ui/component/GlassCircleButton.kt:63 elasticStretchLayerBlock`, `projectedEndpoint` /
    `nearestProjectedSnap` in `$CXK/ui/utils/`.
  - iOS foundation `$CXK/ui/ios/foundation/`: `IosGlass.kt` (185), `IosMenuMaterial.kt` (303), `IosTheme.kt` (150),
    `IosColors.kt` (157), `IosDesignSystem.kt` (134), `IosShapes.kt` (60), `IosTypography.kt` (69), `IosPressable.kt` (53).
    **Not ported**: `HigBridge.kt` and `IosMaterialBridge.kt` (they bridge to `zone.ien.hig` and Material3; our `:design`
    has neither, ARCH 0). The `hig/` module (116 files, 19.6k lines) is not needed by any file we port (grep: only
    `HigBridge.kt` and `ios/compat/*` import `zone.ien.hig`), so it is not ported this round.
  - iOS components `$CXK/ui/ios/component/`: `AppleContextMenu` (422), `IosContextMenu` (287), `IosMenu` (365),
    `IosSheet` (298), `IosTabBar` (257), `IosNavigationBar` (164), `IosList` (373), `IosSwipeActions` (188),
    `IosButton` (223), `IosToggle` (143), `IosSlider` (186), `IosText` (86), `IosGlyph` (50); plus
    `$CXK/ui/component/{IosPageSheet (120), IosSheetPresentation (159), OverlayMenu (246), BottomSheet (658)}.kt`.
    Convx tests to port: `$CX/app/src/test/kotlin/com/convx/music/ui/component/GlassEffectTest.kt`,
    `.../ui/theme/GlassTintTokensTest.kt`, `.../ui/theme/AppleColorsTest.kt`, `.../ui/component/MenuActionColumnCountTest.kt`
    (the research notes `docs/CODEBASE_REVIEW_2026-10.md:82` in Convx: some of these had failing assertions; fix the
    assertion or the code, never `@Ignore`).
  - Convx presents menus and page sheets in `Popup`/`Dialog` windows (`IosContextMenu.kt:170`, `AppleContextMenu.kt:286`,
    `IosMenu.kt:331`, `IosPageSheet.kt:70`) and has them sample the app root's recorded layer
    (`IosMenuMaterial.kt:178-188`, "Works inside popups and sheets: the backdrop is the app root's recorded layer").
    **Our adaptation**: we keep our in-window `OverlayHost` (ARCH 4.5: one back owner, one z-order, screenshots capture
    it) and place it, like Convx's chrome, OUTSIDE the recorded subtree, so it samples the same root `LayerBackdrop`
    exactly as Convx's popups do. Every ported file that swaps `Popup`/`Dialog` for an `OverlayEntry` says so in a
    one-line comment under its header.

## Rules for this round (in addition to round-01 "Rules for every step" and round-02 "Rules changed or added")

- **Header of every file that contains Convx code** (keep the upstream header lines Convx had, then add ours):

  ```kotlin
  /*
   * <original header lines exactly as in the Convx file, e.g.
   *  "Convx Project (C) 2026 / Licensed under GPL-3.0 | See git history for contributors"
   *  or "Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
   *      https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0" + Convx's notes>
   *
   * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <original path from repo root>
   * Changes for Rovsti: <one line: package, tokens, removed music specifics, Popup -> OverlayEntry, ...>
   */
  ```
  Files that also contain glisse code keep the glisse line as well. `LicenceHeaderTest` (Step 1) enforces this.
- Port **close to verbatim**: same file split, class/function names (prefix nothing; `Ios*`, `liquidGlass`,
  `drawBackdrop`, `LayerBackdrop` keep their names), same constants and AGSL strings. Allowed changes: package
  (`dev.rovsti.design.*`), imports, our tokens (`RovstiTheme.colors` feeds `IosColors`), Rovsti data models, removal of
  music-player code paths (player morph, lyrics, artwork tints, loop buckets for video, Apple-Music-UI switches,
  `rememberPreference` keys -> parameters or our `GlassSettings` local), and Kotlin/Compose API drift at BOM 2026.09
  (note each in `docs/kotlin-ui/api-notes.md`).
- Package map: `$CXK/ui/component/backdrop/**` -> `D/backdrop/**` (package `dev.rovsti.design.backdrop.*`, same
  sub-packages `backdrops`, `effects`, `highlight`, `shadow`, `internal`, `catalog.components`, `catalog.utils`);
  `shapes/**` -> `D/shapes/**`; `GlassEffect.kt`, `TopFadeBlur.kt` -> `D/glass/`; `AppleTokens.kt`, `GlassTintTokens.kt`
  -> `D/theme/`; `ios/foundation/*` -> `D/ios/foundation/`; `ios/component/*` and the four `component/Ios*`/`OverlayMenu`/
  `BottomSheet` files -> `D/ios/component/`; `AppBackdropState.kt` -> `D/backdrop/AppBackdropState.kt`; Convx utils
  needed by these -> `D/ios/foundation/ConvxUtils.kt` (one file, each function with its original path in a comment).
- No test may set a flat/fallback mode to make a golden pass. The fallback is tested only by tests named `*Fallback*`.
- Glass screenshots are recorded with the real effect path. Robolectric config `@GraphicsMode(NATIVE)`, SDK as today.
- Disk: keep >= 6 GB free (`df -h .`, 16 GB free at planning time). Before Step 4 and Step 8 builds run
  `rm -rf android/*/build android/build target/debug/incremental` if under 8 GB.
- Commit message prefix: `Kotlin UI (E1 R3, Convx port): ...`, with the attribution lines from the session reminder.
- Checks per step: `bash scripts/kotlin-ui-check.sh` (Steps 1 also needs the CLAUDE.md Rust checks because
  `Cargo.toml` changes: `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace` and
  `cargo check -p rovsti_android --target aarch64-linux-android`).

---

## Step 1 (E1.23): relicense Rovsti to GPL-3.0-or-later; notices; skill and docs

Files:
- `Cargo.toml` `[workspace.package] license = "GPL-3.0-or-later"`. Grep every `Cargo.toml` (crates, apps, services,
  `android/**` if any) for a non-workspace `license`; set it to the same. `cargo metadata --format-version 1 | jq
  '.packages[] | select(.source==null) | .license'` prints only `GPL-3.0-or-later`.
- `LICENSE` (repo root): byte-for-byte copy of `$CX/LICENSE` (GPL-3.0 text). `cmp $CX/LICENSE LICENSE` is silent.
- `README.md`: new section "Licence": Rovsti is GPL-3.0-or-later (see `LICENSE`); the Android UI contains code ported from
  Convx (GPL-3.0) and from Apache-2.0 / MIT projects listed in `android/THIRD_PARTY_NOTICES.md`; each ported file names
  its source in its header. Note that Convx states "GPL-3.0" without "or later", so the combined Android apps are
  distributed under GPL-3.0 (version 3), which "GPL-3.0-or-later" permits.
- `android/THIRD_PARTY_NOTICES.md`: keep the glisse section (full MIT text). Add sections, each with source URL, licence,
  copyright line, what we use and where (`D/...` paths, filled in by later steps; list the planned paths now):
  Convx (GPL-3.0, `cosmictaserdev-creator/Convx`, "Convx Project (C) 2026 ... See git history for contributors"; the
  text is the root `LICENSE`); Kyant0/backdrop v2.0.0 (Apache-2.0, Copyright 2025 Kyant0); Kyant0/AndroidLiquidGlass kmp
  branch (Apache-2.0, Copyright 2025 Kyant0); Kyant0/Capsule (Apache-2.0, Copyright 2025 Kyant0); compose-miuix-ui
  (Apache-2.0, "Copyright 2026, compose-miuix-ui contributors"); compose-hig (Apache-2.0, IENGROUND of IENLAB, Compose
  Cupertino / Scott Lanoue; listed as "not used yet" until a hig file is ported); Haze (Apache-2.0, Chris Banes; only if
  Step 3 adds the dependency). Append the full Apache License 2.0 text once (copy `$CX/hig/LICENSE`) and state that the
  Apache-licensed files keep their own notices. Mention Flamingo / MeloX / BitChord / Square (GPL-3.0, from
  `$CX/NOTICE`) only as "not ported: music-player specific".
- `.claude/skills/convx-motion-compose/SKILL.md`: rewrite section 0: Convx is GPL-3.0 and Rovsti is GPL-3.0-or-later, so
  Convx code is portable close to verbatim with the header above; upstream Apache files keep their attribution; glisse
  rule unchanged; the frontmatter description line "Convx research (GPL: techniques only)" becomes "Convx (GPL-3.0,
  ported with attribution)". `references/glass.md`, `overlays.md`, `motion.md`, `navigation-layout.md`,
  `performance.md`, `checklist.md`: replace every "ORIGINAL (do not copy Convx)" / "never copy" sentence with a pointer to
  the Convx file to port (path), and checklist A gets: "every file with Convx code has the 'Ported from Convx' header;
  `LicenceHeaderTest` passes". Glass reference section 2 ("Capture engine (ORIGINAL)") gets a banner: "Superseded by the
  Convx backdrop port (`D/backdrop`), see round-03 plan".
- `CLAUDE.md`: one line under the bullets: "- Licence: GPL-3.0-or-later (`LICENSE`). Ported code keeps its upstream
  header and adds `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>` (or the glisse
  line); list new sources in `android/THIRD_PARTY_NOTICES.md`."
- `docs/kotlin-ui/architecture.md` section 0: the Convx row becomes "Convx (GPL-3.0) is ported with attribution; Rovsti
  is GPL-3.0-or-later." Section 4.1 `glass` row: "Convx backdrop engine (`backdrop/`), `liquidGlass`, freeze, pool;
  fallbacks only as Convx does them (pre-31, reduce transparency, low RAM)".
- Move the untracked `D/controls/SwitchRules.kt` to the scratchpad `wip/` folder (not deleted: Step 7 may read it).
- New JVM test `DT/LicenceHeaderTest.kt`: walks `android/*/src/**/*.kt`; for each file containing the word `Convx` in its
  first 20 lines asserts it has `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, ` followed
  by a path; files under `D/backdrop/`, `D/shapes/`, `D/ios/` must contain that line; files containing `glisse` in the
  first 5 lines must contain the exact glisse line. Passes on zero matching files today.

Tests/acceptance: Rust checks from CLAUDE.md and `scripts/kotlin-ui-check.sh` green; `cmp LICENSE $CX/LICENSE`; `grep -rn
"MIT OR Apache" Cargo.toml crates apps services` empty; no screenshots change.
Commit: `Kotlin UI (E1 R3, Convx port): relicense to GPL-3.0-or-later, notices, skill and docs`.

---

## Step 2 (E1.24): port the Convx backdrop engine and the continuous shapes (no callers yet)

Files (close to verbatim, package `dev.rovsti.design.backdrop...`):
- All 42 files of `$CXK/ui/component/backdrop/**` -> `D/backdrop/**`, same names and sub-packages: `Backdrop.kt`,
  `BackdropEffectScope.kt`, `DrawBackdropModifier.kt` (keep `backdropScale`, `frozen`, `ThawPollMs = 48`, padding logic,
  composite colour filter, geometry-change rules; drop the `loopBucket` cache only if it pulls player code, otherwise keep
  it, it is inert), `Platform.kt`, `RuntimeShader.kt`, `RuntimeShaderCache.kt`, `GlassPool.kt`, `backdrops/*`
  (`LayerBackdrop`, `LayerBackdropModifier`, `CanvasBackdrop`, `CombinedBackdrop`, `EmptyBackdrop`, `BackdropFreeze`
  (900 ms safety, 300 ms entry), `NavTransitionFreeze` (330 ms), `BackdropStaticRegion`), `effects/*` (`Blur`,
  `ColorFilter` incl. `vibrancy`/`colorControls`, `Lens`, `RenderEffect`), `highlight/*`, `shadow/*`, `internal/*`
  (`Shaders.kt` AGSL strings verbatim: `RoundedRectRefractionShaderString`, `...WithDispersion...`,
  `MultiRefractionShaderString`), `catalog/components/{LiquidButton,LiquidToggle,LiquidSlider,LiquidBottomTab,
  LiquidBottomTabs}.kt`, `catalog/utils/{InteractiveHighlight,DampedDragAnimation,DragGestureInspector}.kt`.
- `$CXK/ui/component/shapes/**` -> `D/shapes/**` (`ContinuousRoundedRectangle`, `ContinuousCapsule`,
  `LerpContinuousRoundedRectangle`, `NavContinuousCapsule`, continuities, path, core, `MathUtils`).
- `$CXK/AppBackdropState.kt` -> `D/backdrop/AppBackdropState.kt`: `AppBackdropState` + `rememberAppBackdropState(
  baseBg: Color, currentRoute: String?, extraFrozen: () -> Boolean = { false })` (Convx's `pureBlack`/`AppTheme` and the
  floating-tab-bar connection become the `baseBg` and `extraFrozen` parameters; the 120 ms mirror loop stays).
- If a catalog component imports app-specific code (preferences, `SymbolIcon`), replace it with a parameter or our
  `RovstiIcon`; note it in the header "Changes" line.
- `docs/kotlin-ui/api-notes.md`: one row per API that changed between Convx's Compose version and BOM 2026.09.

Tests:
- Port any upstream-style pure helpers into JVM tests: `DT/backdrop/BackdropFreezeTest` (stamp in `onPreScroll` -> frozen;
  `onPostFling` clears; 900 ms safety timeout with an injected clock if Convx's class takes one, else via `System.nanoTime`
  offset helper), `NavTransitionFreezeTest` (push freezes 330 ms, pop does not), `StaticRegionTest` (capture rect
  inside one declared region skips re-record; straddling two does not).
- Robolectric `CAT/BackdropEngineTest` (`@GraphicsMode(NATIVE)`): a 300x300 dp stripe source with `layerBackdrop`, a
  sibling 200x80 dp capsule with `drawBackdrop(backdrop, shape = { ContinuousCapsule }, effects = { vibrancy(); blur(8.dp
  .toPx()); lens(16.dp.toPx(), 32.dp.toPx()) })`. Capture: inside the capsule the 8x8 patch variance is < 25 % of the same
  patch outside it (blur ran); a patch at the capsule edge differs from the straight-through pixel (lens ran); moving the
  capsule 40 dp re-records (pixels follow the stripes under it).
- Screenshot `e1.24-backdrop-engine-{light,dark}`: stripes, three Convx catalog pieces (`LiquidButton`, `LiquidToggle`
  on and off, `LiquidSlider` at 40 %) and one capsule. Judge: blur and saturation visible inside every piece, lens bends
  stripes near edges, white specular rim top-left/bottom-right, shadows soft; nothing clipped.
- `LicenceHeaderTest` passes for all new files.

Commit: `Kotlin UI (E1 R3, Convx port): backdrop engine (Kyant0 backdrop + Convx), continuous shapes`.

---

## Step 3 (E1.25): Convx glass layer, tokens and iOS foundation

Files:
- `D/glass/GlassEffect.kt` <- `$CXK/ui/component/GlassEffect.kt` verbatim minus music specifics: `GlassEffectConfig`,
  `GlassStyle`, `shouldUseTranslucentGlassFallback`, `GlassComponent`, `glassResolutionScale` (0.30 floor, 2.5 dp full
  quality), `isGlassSupported`, `isLowRamDevice`, `isGlassAllowed`, `glassSaturation`, `glassContentColorFor`,
  `LocalGlassEffectConfig`, `LocalAppBackdrop`, `PooledGlass`/`LocalPooledGlass`, `LocalAppBackdropFrozen`,
  `LocalAppMotionFrozen`, `Modifier.liquidGlass(...)`. Drop `LocalAppleMusicUi`, `LocalBackdropLoopBucket` (or keep as
  inert defaults if removing them changes `liquidGlass`'s body). The old file of the same name (our 27-line effect chain)
  is deleted in Step 4; in this step put the port at `D/glass/convx/GlassEffect.kt` (package `dev.rovsti.design.glass`
  is shared, so name the old file's function out of the way if they clash, e.g. keep our old file until Step 4).
- `D/glass/TopFadeBlur.kt` <- `TopFadeBlur.kt`. Add `dev.chrisbanes.haze:haze` + `haze-materials` (Convx pins 1.3.1;
  take the newest stable that resolves against BOM 2026.09, write the check in `api-notes.md`) as `:design`
  dependencies, list Haze in THIRD_PARTY_NOTICES. If Haze does not resolve or breaks, port only the `backdrop` branch of
  `TopFadeBlur` (the strip outside the recorded subtree that samples `LocalAppBackdrop`) and say so in the header.
  `rememberPreference(ReduceDynamicBlurKey)` / `TopBarBlurStyleKey` -> fields of a new `GlassSettings` (below).
- `D/theme/AppleTokens.kt`, `D/theme/GlassTintTokens.kt` <- Convx verbatim (`LocalMetadataColor` included).
- `D/ios/foundation/` <- `IosGlass.kt` (`IosGlassStyle`, `IosGlassSpec`, `Modifier.iosGlass`, `iosGlassContentColor`),
  `IosMenuMaterial.kt` (`IosMenuColors`, `IosMenuMaterial`, `LocalMenuLiquid`, `LocalMenuLongPress`, `menuVibrancy`,
  `Modifier.iosMenuSurface` with the 8 dp blur / 25 % lens / 68 % fill and the "draw frost while the snapshot
  initialises" path; `MenuBackdropResolutionKey` -> `GlassSettings.menuBackdropResolution`), `IosTheme.kt`
  (`IosTheme(...)`, locals incl. `LocalIosBackdrop`, `IosMetrics`), `IosColors.kt`, `IosDesignSystem.kt` (minus
  `HigBridge` use: `rememberDesignBackground` reads `RovstiTheme.colors.page`), `IosShapes.kt`, `IosTypography.kt`
  (system font), `IosPressable.kt`; `ConvxUtils.kt` with `additiveChrome`, `outerDarkRim`, `elasticStretchLayerBlock`,
  `projectedEndpoint`, `nearestProjectedSnap` (each with its Convx path).
- Bridge to our theme: `RovstiTheme` provides `IosTheme(colors = iosColorsFrom(RovstiTheme.colors, dark))` inside it, so
  every ported component sees our palette; `iosColorsFrom` maps label/secondary/separator/fill/accent/destructive/page
  from `RovstiColors` and keeps Convx defaults for the rest. Where our `GlassTokens` (glisse) and Convx's
  `GlassTintTokens`/`AppleTokens` disagree, Convx wins for glass surfaces; `GlassTokens` stays only for glisse-ported
  pieces not replaced yet.
- New `D/glass/GlassSettings.kt`: `@Immutable data class GlassSettings(reduceTransparency = false, reduceDynamicBlur =
  false, menuBackdropResolution = 1f, topBarBlurStyle = HazeProgressive)` + `LocalGlassSettings`. Convx's fallback rules
  only: `isGlassAllowed()` (API >= 31 and not low RAM) and `reduceTransparency` -> translucent fallback
  (`shouldUseTranslucentGlassFallback`). Nothing else turns glass off.

Tests (JVM, ported from Convx with our package; fix any failing assertion, explain in the commit body):
`DT/glass/GlassEffectTest` (<- `GlassEffectTest.kt`, incl. `glassResolutionScale(0)=1`, `(2.5)=0.30`, `(1.25)=0.65`),
`DT/theme/GlassTintTokensTest`, `DT/theme/AppleColorsTest`, new `DT/ios/IosColorsBridgeTest` (every mapped colour equals
our token, light and dark; label on page passes `meetsContrast` 4.5).
Screenshot `e1.25-ios-glass-materials-{light,dark}` over stripes: `iosGlass` Regular and Prominent, `iosMenuSurface`
panel, `liquidGlass` with `GlassComponent` presets (bar, button, popover), `TopFadeBlur` strip at the top. Judge: each
surface shows blurred, saturated stripes; Prominent tinted; menu panel lighter fill (light) / dark fill (dark) with text
readable; top strip fades from blurred to clear with no hard edge.
Commit: `Kotlin UI (E1 R3, Convx port): liquidGlass, tokens, iOS foundation, TopFadeBlur`.

---

## Step 4 (E1.26): one root backdrop, migrate every caller, delete the old engine, prove the real path; APK

Root:
- `D/chrome/RovstiRoot.kt` (new; Convx `AppRootContent` pattern, ported structure where it exists): `@Composable fun
  RovstiBackdropRoot(baseBg: Color = RovstiTheme.colors.page, route: String?, content: @Composable () -> Unit, chrome:
  @Composable BoxScope.() -> Unit, overlays: OverlayController)`: builds `rememberAppBackdropState`, then
  `Box { Box(Modifier.fillMaxSize().layerBackdrop(state.appBackdrop).nestedScroll(state.scrollConnection)) { content() };
  CompositionLocalProvider(LocalAppBackdrop provides state.appBackdrop, LocalAppBackdropFrozen provides
  state.frozenProvider, LocalIosBackdrop provides state.appBackdrop, LocalAppMotionFrozen provides state.motionFrozen) {
  chrome(); OverlayHost(...) } }`. Chrome and overlays are siblings AFTER the recorded subtree (Convx comment: "avoid a
  recursive RenderNode draw"). Page pushback (`PushBack.kt`) is applied to the recorded content box so sheets sample the
  pushed page as it is seen.
- Catalog `CatalogApp.kt` uses `RovstiBackdropRoot`; every page draws into it. Pages that showed glass over stripes
  (`GlassPage`, `ButtonsPage`, `TabBarPage`, `LargeTitlePage`, `ToastsPage`) put `StripedBackdrop` in the content slot;
  no page creates its own source any more (a page may use a local `rememberLayerBackdrop()` only where Convx does,
  e.g. `CombinedBackdrop` for the tab bar puck).

Migration (every caller listed in "State found"):
- `Modifier.glass(source, spec)` -> `Modifier.liquidGlass(config, shape, ...)` with the matching `GlassComponent` preset
  (bar, button, popover, sheet, thumb); `rememberGlassSource`/`glassSource` -> `LocalAppBackdrop`; `GlassTier` checks ->
  `isGlassAllowed()` / `LocalGlassSettings.reduceTransparency`. Overlay panels (`Popover`, `ContextMenuPanel`,
  `ButtonMenu`, `PreviewCardEntry`, `Alert`, `ActionSheet`, `Toast`) use `Modifier.iosMenuSurface(backdrop =
  LocalAppBackdrop.current)` (menus/popovers/action sheets) or `iosGlass` (toast, alert) now; `SheetEntry` uses Convx's
  sheet material from `IosSheet`/`BottomSheet` (material only; physics stays until Step 6). Text fields, toolbar
  capsules, back button, buttons: `iosGlass`/`liquidGlass` per the Convx component that plays the same role.
- Delete `D/glass/{GlassNode,GlassSource,GlassSpec,GlassTier,Lens,LensShader,Rim}.kt` and our old `GlassEffect.kt` (move
  the Convx port from `D/glass/convx/` to `D/glass/GlassEffect.kt`), tests `DT/glass/{CaptureScaleTest,FlatAlphaTest,
  GlassTierTest,LensPullTest,LensUniformsTest,RimTwinTest}.kt`, the catalog `RimTwinPanel`. Remove the Flat forcing from
  `android/testing/.../ShotTest.kt` (and `forTests` everywhere). Keep `RuntimeShaderProbeTest`.
  `FlatLegibilityTest` becomes `TranslucentFallbackLegibilityTest` (reduce transparency on: labels readable through the
  fallback, same contrast rule).
- Real-path instrumentation: internal `object BackdropDebug { @VisibleForTesting val draws: MutableList<Record> }`
  written by `DrawBackdropNode.draw()` only when `BackdropDebug.enabled` (a plain `@JvmField var`, false in release;
  costs one branch): `Record(tag, hasRenderEffect, backdropKind, recorded)`. Each glass call site passes a stable
  `debugTag` (e.g. "tabbar", "menu.panel").

Tests:
- `CAT/GlassRealPathTest` (Robolectric, `@Config(sdk = [33, 36])`, default `GlassSettings`, `BackdropDebug.enabled =
  true`): for each catalog page with glass and each overlay kind (button menu open, context menu open, popover, sheet
  medium, alert, action sheet, toast, focused text field, tab bar, large-title inline bar, toolbar capsule, back
  button) asserts at least one record, and that **every** record has `hasRenderEffect == true`, `backdropKind ==
  Layer` and `recorded == true`. Fails on the pre-port code by construction (no records).
- `CAT/GlassFallbackTest`: `reduceTransparency = true` -> records show the translucent fallback and no render effect;
  `@Config(sdk = [30])` is below our minSdk so it is not tested (note it).
- Source-scan JVM test `DT/NoFlatInTestsTest`: no `forTests`, `GlassTier`, `glassSource(` in `android/**/src`.
- All existing tests updated to the new API and green (no behaviour assertion deleted without a replacement; list any
  in the commit body).

Screenshots: re-record all goldens (`RECORD=1`); OPEN and judge at least: `e1.5-glass-liquid-over-stripes` (rename to
`e1.26-liquid-glass-over-stripes`), `e1.7-tabbar-rest` (bar must show blurred, saturated rows through it, rim visible, no
opaque white), `e1.7-tabbar-drag-mid`, `e1.8-*inline*` (inline bar is glass over the list), `e1.10-menu-open*`,
`e1.11-*`, `e1.12-popover*`, `e1.13-sheet-medium`, `e1.13-sheet-pushback`, `e1.14-alert`, `e1.14-action-sheet`,
`e1.15-toast*`, `e1.16-field-focused`, and the new `e1.26-overlays-over-stripes-{light,dark}` (catalog `GlassPage`
state with a menu and a popover open over stripes: panels must show the stripes blurred through them). Any golden that
still looks flat is a failure of this step.

APK: `cd android && ./gradlew :catalog:assembleDebug`; state the path
`android/catalog/build/outputs/apk/debug/catalog-debug.apk` and its size in the step summary and the commit body.
Commit: `Kotlin UI (E1 R3, Convx port): root backdrop, callers migrated, old engine removed, real-path test`.

---

## Step 5 (E1.27): Convx menus and context menus

Files: `D/ios/component/IosMenu.kt` (+ `IosMenuAnchor`, `IosCheckmark`), `AppleContextMenu.kt`
(`AppleContextMenu`, `AppleMenuController`, `rememberAppleMenuController`), `IosContextMenu.kt` (`IosContextMenu`,
`IosContextMenuOverlay`), `OverlayMenu.kt` (`liquidGlass` variant), `IosText.kt`, `IosGlyph.kt` (`SymbolIcon` ->
`RovstiIcon`). Close to verbatim; the `Popup(...)` blocks become `OverlayEntry`s presented through our
`OverlayController` (same z-order, scrim, back owner) at the same position Convx computes; Convx's open/close
animations, scale-from-anchor, item press highlight, separators, destructive colour, submenus and long-press preview
lift stay as Convx wrote them.
Replacement: `D/overlay/{MenuPanel,ContextMenuPanel,PreviewCardEntry}.kt` rendering is replaced by the Convx panels;
our public `ButtonMenu(...)` / `Modifier.contextMenu(...)` / `MenuModel` stay as facades that build Convx's item lists,
so press-slide-release (`MenuSlideTest`), `placePanel` edge rules (`PlacePanelTest`) and `ContextMenuGestureTest`
keep passing; where Convx's behaviour differs from a test (timing, sizes), Convx wins and the test is updated to Convx's
numbers with a comment citing the Convx line. `MenuMorph.kt` (glisse) is removed if Convx's menu open transition
replaces it; keep `MenuMorphTest` only if `MenuMorph` stays.
Tests: port `MenuActionColumnCountTest`; `GlassRealPathTest` cases for the menus still pass; Robolectric
`IosMenuTest` (open from anchor, tap item fires once and closes, back closes, TalkBack pane title).
Screenshots (OPEN): `e1.27-menu-open-{light,dark}`, `e1.27-context-menu-preview-{light,dark}`,
`e1.27-context-menu-submenu-{light,dark}` over a list page. Judge: panel is glass over the page, rows 44+ dp, icons
right-aligned, destructive red, preview card lifted with shadow, nothing pushes the page layout.
Commit: `Kotlin UI (E1 R3, Convx port): IosMenu, AppleContextMenu, IosContextMenu, OverlayMenu`.

---

## Step 6 (E1.28): Convx sheets

Files: `D/ios/component/IosSheet.kt` (`rememberIosSheetState`, `BoxScope.IosSheet`, projected snap via
`nearestProjectedSnap`), `IosPageSheet.kt` (`Dialog` -> modal `OverlayEntry`), `IosSheetPresentation.kt`,
`BottomSheet.kt` minus everything under `ui.player.*` (player morph, `recordPlayerLayer`, screen-corner expansion) and
`NavigationBarAnimationSpec` (-> our `RovstiMotion` token of the same duration, cited).
Replacement: `D/overlay/SheetEntry.kt` / `SheetState.kt` / `SheetMath.kt` physics and drawing are replaced by Convx's;
our detents API (`Peek`/`Medium`/`Large`), non-modal mode, predictive back, page pushback and the F4 stacked step-back
are kept as the facade (Convx's presentation decides scale/corner numbers if it has them). All `SheetGestureTest` and
`SheetMathTest` behaviours still hold (update numbers to Convx's with citations); the sheet material stays the Step 4
real-path glass.
Screenshots (OPEN): `e1.13-sheet-{peek,medium,large,pushback,nonmodal-over-stripes}` re-recorded and
`e1.28-page-sheet-{light,dark}`. Judge: grabber centred, sheet glass shows the pushed page blurred under its top edge,
stacked sheet's top edge visible, page card corners on black, no white haze.
Commit: `Kotlin UI (E1 R3, Convx port): IosSheet, IosPageSheet, presentation, BottomSheet`.

---

## Step 7 (E1.17, re-scoped): switch, slider, segmented control from Convx

Files: `D/ios/component/IosToggle.kt`, `IosSlider.kt` (both on `backdrop/catalog/components/LiquidToggle.kt` /
`LiquidSlider.kt` + `DampedDragAnimation` + `InteractiveHighlight`, as Convx composes them); segmented control =
Convx's `LiquidBottomTabs` puck pattern (`CombinedBackdrop` of page + tinted row) wrapped as
`D/controls/SegmentedControl.kt` (`SegmentedControl(items, selected, onSelect)`); `ListRow` trailing switch variant using
`IosToggle`. Read the scratchpad `wip/SwitchRules.kt`: if Convx's toggle lacks the 24 dp near-release tolerance or the
hold-blocks-scroll rule, add it inside `IosToggle` with a glisse line in the header; then delete the WIP file.
Tests: Robolectric `ToggleTest` (tap toggles + one haptic, drag past half turns on, vertical swipe in a `LazyColumn`
scrolls and does not toggle, cancel restores, `Role.Switch` + state semantics, >= 48 dp target, no layout shift while
pressed), `SliderTest` (drag sets value, keyboard/semantics `setProgress`, step snapping if Convx supports steps),
`SegmentedTest` (tap selects, drag puck releases on nearest segment, semantics `Role.Tab` selected). Real-path records
for thumb glass.
Screenshots (OPEN): `e1.17-switch-off-on`, `e1.17-switch-pressed`, `e1.17-slider`, `e1.17-segmented-3`,
`e1.17-segmented-drag` light/dark, in a settings section. Judge: thumb is lens glass when pressed (stripes/track bent
through it), track colours from our tokens, disabled faded, nothing clipped by the row.
Commit: `Kotlin UI (E1 R3, Convx port): IosToggle, IosSlider, segmented control`.

---

## Step 8 (E1.29): tab bar, navigation bar, buttons, list, swipe actions; catalog APK

Files: `D/ios/component/IosTabBar.kt` (+ `rememberIosTabBarState`, `IosBadge`) with `LiquidBottomTabs` puck;
`IosNavigationBar.kt` (+ `IosToolbarGroup`, `IosToolbarSpacer`, `rememberIosNavigationBarCollapse`); `IosButton.kt`;
`IosList.kt` (`IosList`, `IosNavigationRow`, `IosChevron`); `IosSwipeActions.kt` (`IosSwipeableRow`).
Replacement: `RovstiTabBar` becomes a facade over `IosTabBar` (keep `TabBarMinimizer`, `ChromeMetrics`,
`LocalContentInsets`, IME hide, list-end-above-bar rule: `ListEndAboveBarTest`, `ImeHidesBarTest`, `PuckBoundsTest`,
`TabBarSemanticsTest` keep passing, numbers updated to Convx with citations); `LargeTitleScaffold` inline bar and
`Toolbar` capsules use `IosNavigationBar` collapse + `IosToolbarGroup`; `Buttons.kt` glass buttons delegate to
`IosButton`/`LiquidButton`; `InsetGroupedSection`/`ListRow` adopt `IosList` drawing where it differs (separator inset,
row height, chevron); new swipe row = `IosSwipeableRow` (covers the swipe half of E1.19, with custom accessibility
actions added if Convx lacks them).
Tests: existing chrome tests green; `SwipeRowTest` (reveal by drag, full swipe fires the action once with haptic,
settle back, accessibility actions listed). Real-path test cases for tab bar, nav bar, buttons.
Screenshots (OPEN): `e1.7-tabbar-{rest,drag-mid,minimised,folded,fs2,360x640}`, `e1.8-*`, `e1.6-buttons*`,
`e1.29-swipe-revealed`, `e1.29-swipe-full`, light/dark. Judge: owner complaints: the bar never covers the last row,
rows do not jump when a button is tapped, one spacing rhythm, the tab bar is glass with a lens puck.
Close the round: `scripts/kotlin-ui-check.sh` green, rebuild the catalog APK
(`android/catalog/build/outputs/apk/debug/catalog-debug.apk`, path and size in the summary), update epics.md evidence
for E1.23-E1.29 and E1.17 (commits, PNGs opened, what was seen).
Commit: `Kotlin UI (E1 R3, Convx port): IosTabBar, IosNavigationBar, IosButton, IosList, swipe actions; close R3`.

---

## After this round (round R4 of E1)

E1.18 navigation scaffold (Navigation 3 1.2.1; freeze glass during transitions with `NavTransitionFreeze`), E1.19 rest
(alphabet rail: Convx `$CXK/ui/component/AlphabetScrollBar.kt` + its test), E1.20 images and photo viewer (Coil 3.6.3),
E1.21 performance harness and convention tests (add "no `Popup(`/`Dialog(` outside `D/overlay` and `D/ios/component`"),
E1.22 catalog complete and contact sheet. Read `round-03-e1.17-e1.22.md` Steps 4-8 for their detail; replace any
"ORIGINAL"/"never copy Convx" instruction there with the Convx file to port.
