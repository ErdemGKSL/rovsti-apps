# Round 01 plan: E1 design system, round R1 (stories E1.1-E1.8)

Planner: PLAN agent, round 1. Base: `4b1ed22` (branch `ui-overhaul`). Epic file: `_bmad-output/kotlin-ui/epics.md`
(this file is the "E1-r1" plan the epics file asks for).

## State found (verified, not trusted)

- `git log`: the last commits are the skill (`8958705`, `b424924`), the Gradle root (`cf4fa2d`), research carry-over
  (`931187d`) and the architecture/epics (`a82800f`, `4b1ed22`). No Kotlin UI code has been written.
- `android/` holds only the Gradle root (`settings.gradle.kts` includes **no** module, `build.gradle.kts` applies only
  Spotless), the version catalog and `THIRD_PARTY_NOTICES.md` (glisse MIT text already present). No `build-logic`,
  `design`, `catalog`.
- `scripts/kotlin-ui-check.sh` therefore runs only Rust + `spotlessCheck` today; as soon as step 1 adds a module it
  runs `spotlessCheck lintDebug testDebugUnitTest assembleDebug verifyRoborazziDebug`.
- `git stash@{0}` is the deprecated GPUI layout WIP (`docs/design/layout.md`, GPUI menus). It is not part of the Kotlin
  work: leave it alone, do not pop it.
- No previous plan and no screenshots exist, so there is nothing to re-judge this round.
- Every epic is `todo`. Hard-dependency table: E1 R1 has no prerequisites. First not-done epic = **E1**, first round
  = **R1** = E1.1-E1.8 (exactly 8 stories). This round: one step per story, in story order.
- Pinned versions resolved in the Gradle cache: Compose **1.12.1** (BOM 2026.09.00). Spot-checked in the cached jars:
  `SuspendingPointerInputModifierNode(PointerInputEventHandler)` exists (`ui-android-1.12.1`,
  `SuspendingPointerInputFilterKt`); `HapticFeedbackType` has `Confirm, Reject, SegmentTick, SegmentFrequentTick,
  ToggleOn, ToggleOff, TextHandleMove, LongPress, GestureEnd, GestureThresholdActivate, ContextClick, KeyboardTap,
  VirtualKey`; `androidx.compose.ui.draw` has `dropShadow` nodes (`SimpleDropShadowElement`, `DropShadowScope`).
  Everything else named below must still be checked and written into `api-notes.md` (step 1).

## Rules for every step (read once)

- Read first: `.claude/skills/convx-motion-compose/SKILL.md`, the reference sections named in the step,
  `docs/kotlin-ui/architecture.md` sections 2-4 and 9-10, `docs/kotlin-ui/toolchain.md`.
- Package root is `dev.rovsti.design.<pkg>` (ARCH 4.1). The skill's snippets say `dev.rovsti.ui.*`; rename to
  `dev.rovsti.design.*` when moving them into source.
- **Licence.** Never open Convx files while writing code; work from the skill. glisse
  (`/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/ref/glisse`, MIT) may be
  ported; every file with glisse-derived code (Kotlin or AGSL string) starts with exactly
  `// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`.
  Lens and rim files also keep the line `// Lens shape after glisse's implementation of Aave's web glass; Snell's-law
  profile after kube.io's liquid glass article.` Files marked ORIGINAL in the skill get no header.
- No Material3 anywhere in `:design` and `:catalog` (text is `BasicText`, rows are ours). No `composed {}`; custom
  modifiers are `Modifier.Node`. No `Popup`/`Dialog`. No ripple (`indication = null` + our press nodes).
- Per-frame values are read only in `graphicsLayer {}`, draw or layout lambdas (ARCH 10.1).
- Every spring passes `visibilityThreshold` (0.0001f scales, 0.001f progress, 0.5f px).
- Tests: pure functions -> JVM tests in `:design` (`src/test`); gesture/bounds/semantics tests -> Robolectric
  Compose tests in `:design` with `mainClock.autoAdvance = false`; screenshots -> `:catalog` tests rendering the
  catalog pages, goldens in `android/catalog/screenshots/`.
- Screenshot workflow per step: `cd android && ./gradlew --max-workers=2 :catalog:recordRoborazziDebug`, then **open
  every new or changed PNG with the Read tool**, judge (spacing on the 16/8/4 grid, overlap, clipping, puck inside the
  bar, contrast, rim, last row visible), fix, re-record, then `bash scripts/kotlin-ui-check.sh` must print `ALL
  GREEN`. Write what you saw in the story's **Evidence** line in `epics.md` and set its status to `done`.
- Iterate with `SKIP_RUST=1 bash scripts/kotlin-ui-check.sh`; the final run of each step is the full script (Rust is
  not touched this round, so it should stay green; if `cargo build --target aarch64-linux-android` fails for an
  environment reason, run `scripts/android-setup.sh` first).
- Disk: check `df -h .` before each step (keep >= 6 GB); `rm -rf android/*/build android/build target` if low.
- Commit at the end of each step: `Kotlin UI E1.n: <title>` with the attribution lines from the session.
- Checklist: tick `references/checklist.md` sections A, B, C (and E/F where they apply) in the commit message body or
  write the reason a box does not apply.

---

## Step 1 (E1.1): build-logic, `:design`, `:catalog`, `:testing`, screenshot harness

Files:
- `android/build-logic/settings.gradle.kts`, `android/build-logic/convention/build.gradle.kts` (kotlin-dsl; compileOnly
  AGP, Kotlin, compose-compiler and Roborazzi Gradle plugins from the catalog), and the plugins in
  `android/build-logic/convention/src/main/kotlin/`:
  - `rovsti.android.library` / `rovsti.android.app`: `compileSdk = 37`, `minSdk = 34`, `targetSdk = 36` (app),
    Java/Kotlin 17 (or what AGP 9.5 requires; check), `testOptions.unitTests.isIncludeAndroidResources = true`,
    lint `abortOnError = true`, `warningsAsErrors = false`. SDK numbers live in one `object RovstiSdk` there.
  - `rovsti.compose`: applies `org.jetbrains.kotlin.plugin.compose`, adds the BOM, `ui`, `ui-graphics`, `foundation`,
    `animation` (add `compose-animation` to `libs.versions.toml`), `ui-tooling-preview`; debug `ui-tooling`,
    `ui-test-manifest`. Compose compiler reports: when the Gradle property `composeReports` is set, set
    `composeCompiler.reportsDestination` and `metricsDestination` to `layout.buildDirectory.dir("compose_reports")`
    (AC4). Add `compose_stability.conf` hook (empty file at `android/compose_stability.conf`).
  - `rovsti.screenshots`: applies Roborazzi, adds `robolectric`, `roborazzi`, `roborazzi-compose`,
    `roborazzi-junit-rule`, `compose-ui-test-junit4`, `junit` to `testImplementation`, sets the Roborazzi
    `outputDir` to `<module>/screenshots` (AC3), and `-Droborazzi.test.verify` handling stays the plugin's default.
- `android/settings.gradle.kts`: `pluginManagement { includeBuild("build-logic") }`; `include(":design", ":catalog",
  ":testing")`. Root `build.gradle.kts`: add `alias(libs.plugins.kotlin.compose) apply false` already there; nothing
  else.
- `android/testing` (`:testing`, Android library, package `dev.rovsti.testing`, `api` deps on Robolectric, Roborazzi,
  compose ui-test-junit4; used only as `testImplementation(project(":testing"))`). This is a deliberate addition to
  ARCH 2 (a test-only module avoids AGP test-fixtures Kotlin flags); add it to the module list in
  `docs/kotlin-ui/architecture.md` section 2 and `docs/kotlin-ui/toolchain.md`. Contents:
  - `ShotTest` base class / rule: `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(NATIVE)`,
    `@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")`; `createComposeRule()`; `mainClock.autoAdvance =
    false`; `@Before` sets `Locale.setDefault(Locale.US)` and `TimeZone.setDefault(UTC)`.
  - `fun shot(name: String, variants: Set<ShotVariant> = emptySet(), content: @Composable () -> Unit)`: renders
    `<name>-light.png` and `<name>-dark.png` (forces dark via a `darkTheme` parameter of `RovstiTheme`, not the
    system), plus `-fs2` (`LocalDensity` with `fontScale = 2f`), `-rtl` (`LocalLayoutDirection` Rtl),
    `-landscape` (the test method uses `RuntimeEnvironment.setQualifiers("+land-w891dp-h411dp")` before content).
    Captures with `onRoot().captureRoboImage("screenshots/$file.png", roborazziOptions = RoborazziOptions(
    compareOptions = CompareOptions(changeThreshold = 0.005f)))`. Verify the path resolves to the module's
    `screenshots/` directory both for record and verify (open the produced file to confirm).
  - Determinism (AC6): `ShotTest` provides `LocalRovstiClock provides FixedClock(2026-01-15T10:30:00Z)` and
    `LocalGlassConditions` forced `forTests = true` (the local itself is added in step 5; in step 1 only the clock).
    `RovstiClock` (`fun interface RovstiClock { fun nowMillis(): Long }` + `LocalRovstiClock`) lives in
    `:design` `dev.rovsti.design.time`. Random seed: no randomness exists in R1 (avatar colours are a pure hash in
    step 6); document that. Fake image fetcher: not applicable until Coil arrives in E1.20; leave a `TODO(E1.20)` in
    `ShotTest` naming it.
  - `StripedBackdrop()` composable (diagonal 8-colour stripes + a soft gradient) for every glass shot.
- `android/design` (`:design`, library, `dev.rovsti.design`): an empty `RovstiTheme` stub is enough for step 1
  (real theme is step 2) so the catalog compiles.
- `android/catalog` (`:catalog`, `dev.rovsti.catalog`, app): `CatalogActivity` (`enableEdgeToEdge()` before
  `setContent`, manifest `windowSoftInputMode="adjustResize"`, `android:enableOnBackInvokedCallback="true"`,
  no launcher icon from Material; use an adaptive icon from a vector drawable), `CatalogApp` with an enum
  `CatalogPage` and a `rememberSaveable` current page + `BackHandler` back to home (Navigation 3 comes in E1.18).
  Home is a plain scrolling `Column`/`LazyColumn` of `BasicText` rows for now; step 6 rebuilds it on `ListRow` and
  re-records the shot.
- `docs/kotlin-ui/api-notes.md` (AC5): a table `API | artifact | version | file/class checked | note` for every API
  the skill names that R1 uses: `spring/Animatable/animateTo(initialVelocity)`, `VelocityTracker`,
  `SuspendingPointerInputModifierNode`, `ModifierNodeElement`, `DelegatingNode`, `LayoutModifierNode.placeWithLayer`,
  `DrawModifierNode`, `GlobalPositionAwareModifierNode`, `requireGraphicsContext().createGraphicsLayer()`,
  `GraphicsLayer.record(density, layoutDirection, size)`, `RenderEffect`/`asComposeRenderEffect`,
  `RuntimeShader` (platform), `ShaderBrush`, `dropShadow`/`Modifier.shadow`, `HapticFeedbackType` list,
  `WindowInsets.safeDrawing`, `WindowInsets.isImeVisible`, `NestedScrollConnection`, `LazyListState`,
  `selectableGroup`, `Role.Tab`, `enableAccessibilityChecks` (ui-test 1.12; record whether it runs under Robolectric).
  Check by `javap`/`unzip -l` on the cached aar/jar (`~/.gradle/caches/modules-2/files-2.1/androidx.compose.*`),
  as the planner did; write the jar file name per row.

Tests:
- `:catalog` `CatalogHomeShotTest`: `shot("e1.1-catalog-home")`.
- `:catalog` `DeterminismTest`: records nothing; asserts `Locale.getDefault() == en-US`, UTC, fixed clock.
- AC6 proof: run `./gradlew :catalog:verifyRoborazziDebug` twice in a row after recording; both pass.
- Compose reports proof: `./gradlew :design:assembleDebug -PcomposeReports` writes
  `android/design/build/compose_reports/*.txt` (do not commit build output).

Screenshots: `e1.1-catalog-home-light.png`, `e1.1-catalog-home-dark.png`.
Acceptance: check script green with the Gradle part now running all five tasks; PNGs opened (page background is the
theme page colour, list text legible, nothing under the status bar: the home pads its content with
`WindowInsets.safeDrawing`); api-notes table filled.

## Step 2 (E1.2): theme, typography, spacing, sizes, contrast test

Spec: SKILL `navigation-layout.md` 6, 10, 11; `glass.md` 4.1-4.2; ARCH 4.1 `theme`.
Files (`android/design/src/main/kotlin/dev/rovsti/design/theme/`):
- `RovstiColors.kt` (ORIGINAL): `@Immutable data class RovstiColors(page, groupedBackground,
  groupedSurface, labelPrimary, labelSecondary, labelTertiary, separator, opaqueSeparator, fillPrimary,
  fillSecondary, fillTertiary, accent, onAccent, destructive, success, isDark)`, `LightColors`, `DarkColors`.
  Pick iOS-like system values but **secondary must reach 4.5:1** on page and grouped surface in both themes (fix X5:
  e.g. light secondary `#3C3C43` at alpha ~0.75 instead of 0.6; compute, do not guess) and selected states use filled
  accent with `onAccent` text >= 4.5:1 (X7).
- `GlassTokens.kt` (PORTED FROM GLISSE, header): the `GlassTokens` data class with `Light`/`Dark` exactly as
  `glass.md` 4.1 (glisse `src/styles.css:1-89`).
- `RovstiType.kt` (ORIGINAL): `largeTitle 34sp Bold letterSpacing -0.7sp`, `title1 28sp`, `title2 22sp`,
  `title3 20sp`, `headline 17sp SemiBold`, `body 17sp`, `callout 16sp`, `subheadline 15sp`, `footnote 13sp`,
  `caption1 12sp`, `caption2 11sp`; system font (`FontFamily.Default`), explicit `lineHeight` per style.
- `Spacing.kt`: `object Spacing { gutter = 16.dp; xs = 4; s = 8; m = 12; l = 16; xl = 24; textGap = 2; sectionGap = 24 }`;
  `object Sizes { rowMin = 56; rowMinTwoLine = 64; avatar = 40; avatarLarge = 44; touch = 48; button = 44;
  sourceGlyph = 14; tabBarHeight = 60; tabBarMaxWidth = 500; contentMaxWidth = 640; sheetMaxWidth = 560 }`;
  `RovstiShapes` (grouped 20 dp, card 22, popover 20-32, capsule) plus `concentric(outer, inset)` (floor 4 dp, pure).
- `RovstiTheme.kt`: `RovstiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content)` provides `LocalRovstiColors`,
  `LocalRovstiType`, `LocalGlassTokens` (static locals); `object RovstiTheme { val colors; val type; val glass }`;
  `LocalTextStyle`-like default via `ProvideTextStyle`-equivalent own local + a `RovstiText(text, style, color)`
  wrapper over `BasicText`. Theme colours crossfade 280 ms is not needed this round (note for later).
- `Contrast.kt` (pure): `relativeLuminance(Color)`, `contrastRatio(fg, bg)` with alpha compositing of `fg` over `bg`
  (and of translucent backgrounds over a backdrop).
Tests (JVM, `ContrastTest`): every label colour (primary, secondary, tertiary for >= 3:1 only, accent text,
`onAccent` on accent, destructive) on page, grouped background, grouped surface, and on every glass fill
(`glass`, `surface`) composited over white, black and mid-grey (`#808080`) backdrops: >= 4.5:1 in light and dark
(tertiary >= 3:1, it is decorative). Also `ConcentricTest`, `SpacingTokensTest` pins values.
Catalog: `ThemePage` (palette swatches with names and measured contrast numbers drawn as text; type scale).
Screenshots: `e1.2-palette-{light,dark}`, `e1.2-type-scale-{light,dark}`, `e1.2-type-scale-fs2-{light,dark}`.
Acceptance: contrast test green; in the PNGs labels on swatches legible, the fs2 type scale wraps, nothing clipped.

## Step 3 (E1.3): motion tokens, solver, fling, rubber band, reduce motion, MotionGate

Spec: SKILL `motion.md` 2.1-2.5, 3, 4; `performance.md` 5.
Files (`dev.rovsti.design.motion`):
- `RovstiMotion.kt` (PORTED FROM GLISSE header; `motion.md` 2.3 table): `GlisseSpring(stiffness, damping)` with
  `dampingRatio` and `spec(visibilityThreshold)`, all glisse springs, all ORIGINAL picks (press 0.75/1200, select
  0.76/750, appear 1/450, morph 1/950, sheetSettle .86/380, sheetDismiss 1/400, pushBack 1/380, swipeSettle 1/500,
  menuGrowX .62/560, menuGrowY .78/330, menuLift .80/420, menuLand 1/520, previewReturn .75/500, titleCollapse 1/500,
  barMinimize .90/500, squashDecay .5/300, `rebound(velocity)`), easings (`PushEasing`, `MenuExitEasing`,
  `MenuEnterEasing`, `PlateEasing`, `AmbientEasing`, `FocusFillEasing (0.25,0.1,0.25,1)`), tweens (`push 400`,
  `menuExit 140`, `scrimIn 180`/`scrimOut 140`, `menuPlate 100`, `focusFill 200`, `glowOut 220`), constants
  `PushParallax .30`, `PushDimAlpha .85`, `PressScale .96`. Each ORIGINAL spring function **requires** a threshold
  argument (no `null` default) so call sites cannot forget it.
- `SpringState.kt` (PORTED FROM GLISSE `src/internal/spring.ts:21-64`, header): the analytic solver of `motion.md` 2.4.
- `Fling.kt`, `Rubber.kt` (ORIGINAL, `motion.md` 4).
- `ReduceMotion.kt` (ORIGINAL, `motion.md` 2.5): `rememberReduceMotion()`, `LocalReduceMotion`
  (`staticCompositionLocalOf { false }`), `FiniteAnimationSpec<T>.gated(reduce)`, plus `bounceless(spec)` helper that
  maps any spring to `spring(1f, sameStiffness)` when reduce is on.
- `MotionGate.kt` (ORIGINAL, `performance.md` 5) with edge-only `frozen`, `scrollConnection`, `transition()`,
  `gesture()`, 120 ms tail, plus `watch(LazyListState)` safety via `snapshotFlow { isScrollInProgress }`.
Tests (JVM): `RovstiMotionTokensTest` (pins every number, `GlisseSpring(1024, 58.9).dampingRatio == 0.920 +- .001`);
`SpringStateTest` (under, critical, over damped vs RK4 at 1 kHz within 1e-3; one 16 ms step == two 8 ms steps; retarget
keeps velocity; `GlassPressUp` overshoot ~28 % +- 3 %; dt clamp 0.1 s; settles to exact target); `FlingTest`
(`projectedDistance(1000) == 499 +- .5`, tie rules, zero velocity picks lower); `RubberTest` (odd, monotonic,
slope ~1 at 0, below `D/0.55` at 1e9); `MotionGateTest` with `TestScope`/`StandardTestDispatcher` (edges only:
count `frozen` writes; tail 120 ms; scroll+gesture overlap).
Catalog: `MotionPage` that draws spring curves (normalised step response over 600 ms for GlassPressUp, SelectRest,
Toggle, SheetSettle, MenuGrowX/Y) from `SpringState` in a `Canvas`, labelled with `RovstiText`.
Screenshots: `e1.3-spring-curves-{light,dark}`.
Acceptance: curves look right (GlassPressUp visibly overshoots once, Toggle never overshoots, SelectRest tiny
overshoot), axes labelled, legible.

## Step 4 (E1.4): press feedback, hold rule, haptics

Spec: SKILL `motion.md` 7; `navigation-layout.md` 4.3, 5.4 (press colours), 6 (row press), 8.
Files (`dev.rovsti.design.press`, `dev.rovsti.design.haptics`):
- `GlassPress.kt` (PORTED FROM GLISSE `src/internal/press.ts:1-118`, header): pure `pressTargets(...)` and
  `GlassPressNode` / `Modifier.glassPress(enabled)` as `motion.md` 7 (observe on `PointerEventPass.Final`, never
  consume; four `Animatable`s; release in `finally`; transform via `placeWithLayer`). Touch grow 15 dp, pointer 6 dp,
  stretch 6 dp, shift 8 dp. Also the finger glow (radial 240 dp, white .035 -> .025 -> 0, fade 220 ms) as an
  optional `glow = true` draw in the same node (`DrawModifierNode`).
- `ScalePress.kt` (ORIGINAL): `Modifier.scalePress()` -> 0.96 with `RovstiMotion.press(0.0001f)`, `Modifier.Node`.
- `RowPress.kt` (ORIGINAL): `Modifier.rowPress(interactionSource?)` draws `fillTertiary` behind content while down,
  clears on lift/cancel; no scale, no ripple.
- `Hold.kt` (PORTED FROM GLISSE `src/internal/hold.ts:1-9`, header): `HOLD_MS = 150L`, pure
  `held(downAtMs, nowMs, moved)`, and `suspend fun AwaitPointerEventScope.awaitHoldOrScroll(down, touchSlop):
  HoldResult { Held, Scrolled, Released }` used later by tab bar/switch/slider.
- `RovstiHaptics.kt` (ORIGINAL, `navigation-layout.md` 8): `HapticMode { Off, System, Strong }`, events `tick,
  selection, toggle(on), textHandle, longPress, confirm, reject`; System path through `View.performHapticFeedback`
  with the platform constants; Strong path through `Vibrator` predefined effects; `LocalRovstiHaptics`
  (`staticCompositionLocalOf`) and a `rememberRovstiHaptics(mode)` that reads `LocalView`. A `RecordingHaptics` fake
  in `:testing` for tests. Add `<uses-permission android:name="android.permission.VIBRATE"/>` to the **catalog**
  manifest only (libraries declare nothing).
Tests: JVM `PressTargetsTest` (rest = base scale on both axes, horizontal drag stretches X and shrinks Y, shift
saturates below 8 dp, zero drag no shift); `HoldTest` (149 ms not held, 150 held, moved held). Robolectric Compose
`PressNoLayoutShiftTest` (AC3): a `Column` of a `glassPress` button above a text; capture both nodes' bounds,
`performTouchInput { down(center); moveBy(Offset(30f, 0f)) }`, advance 120 ms, assert both bounds unchanged
(`getUnclippedBoundsInRoot`), then `up()`, advance 500 ms, unchanged again; same for `scalePress` and `rowPress`.
`HapticsTest`: Off plays nothing, System calls `performHapticFeedback` with the expected constant (Robolectric
`ShadowView` or a recording `View` subclass).
Catalog: `PressPage` with a capsule glass-looking button (plain fill + hairline for now; real glass is step 5),
a scale-press card and a row; test tags `press-target`.
Screenshots (manual clock; finger down at the button's right third): `e1.4-press-rest-{light,dark}`,
`e1.4-press-down-+60ms-{light,dark}`, `e1.4-press-release-+120ms-{light,dark}` (down, advance 250 ms, up, advance
120 ms). Acceptance: the pressed frame shows a grown, slightly right-shifted button that does not overlap the
neighbouring text; the release frame shows the overshoot; the text below never moves between the three PNGs.

## Step 5 (E1.5): glass tiers, capture engine, Flat/Frost/Liquid, AGSL lens and rim, probe

Spec: SKILL `glass.md` 1-7; ARCH 9 (Flat in tests, probe).
Files (`dev.rovsti.design.glass`):
- `GlassTier.kt` (ORIGINAL): `GlassTier`, `GlassConditions`, `glassTier()` exactly as `glass.md` 6;
  `LocalGlassConditions` + `rememberGlassConditions()` (reads `ActivityManager.isLowRamDevice`,
  `PowerManager.isPowerSaveMode` with an `ACTION_POWER_SAVE_MODE_CHANGED` receiver registered in a
  `DisposableEffect`, app prefs `reduceTransparency`/`reduceEffects` passed in). `ShotTest` now sets
  `forTests = true`.
- `GlassSpec.kt` (ORIGINAL): `@Immutable GlassSpec(tier, blurDp, saturation, lens: LensOptions?, lensAmount, fill,
  rimShine, edge, cornerDp, shadow)`; presets `Bar`, `Popover`, `Sheet`, `Thumb` from `glass.md` 4.2 resolved per
  theme; `captureScale(blurDp) = 1 - clamp(blurDp / 2.5, 0, 1) * 0.70` (pure).
- `GlassSource.kt` + `GlassNode.kt` (ORIGINAL, `glass.md` 2): `rememberGlassSource()`, `Modifier.glassSource(source,
  background)`, `Modifier.glass(source, spec, shape)` as a `Modifier.Node` (`DrawModifierNode`,
  `GlobalPositionAwareModifierNode`); re-record only on offset/size change; effects built only when (size, spec,
  corner) change; saturation as layer `colorFilter`; `null` effect instead of zero; Flat path draws fill
  `base @ 0.85` + hairline (white .12 dark / black .08 light) + shadow; frozen flag from `MotionGate` read as a
  plain field (no per-frame snapshot read). Inverse-press sampling: when `glassPress` is on the same element, the
  replay is drawn under the inverse of the press transform (`glass.md` 2 rule); test it in the Liquid probe shot
  only if the probe passes, otherwise write it down as a device check (E8.7).
- `Lens.kt` (PORTED FROM GLISSE `src/internal/lens.ts:96-252,525`, header + Aave/kube.io credit): `LensOptions` with
  the five presets, `arc`, `slope`, `roundRectSdf`, `lensPull` CPU twin, `spectrum`; `LensShader.kt`
  (same header): the AGSL source as a `const val` and `lensUniforms(size, corner, options, amount): List<Pair<String,
  FloatArray>>` (pure, testable), `GlassLensShader.effect(...)` building `RuntimeShader` +
  `RenderEffect.createRuntimeShaderEffect` (API 33).
- `Rim.kt` (PORTED FROM GLISSE `src/internal/refraction.ts:28-94`, header + credit): rim AGSL from `glass.md` 3.3 as a
  `const val`, `rimAlpha(x, y, w, h, r, shine, edgeAlpha)` CPU twin, `RimBrush` cache per (size, radius, shine, edge).
  The Flat tier draws the rim with the CPU twin into a cached `ImageBitmap` (so shots show the real rim shape) or a
  plain 0.5 dp hairline if that costs more than 1 ms; decide and note.
- `GlassEffect.kt` (ORIGINAL): the chain of `glass.md` 3.1 (blur first, then lens; `createChainEffect(lens, blur)`).
Tests (JVM): `GlassTierTest` (table: every row of `glass.md` 6, forTests wins, lowRam+powerSave Flat, sdk<33 Frost);
`CaptureScaleTest`; `LensPullTest` (60x30 r15, depth 2, dome 6, splay .4: centre 0, right rim dx>0 (samples
inward), left rim opposite, vertical antisymmetry, relative band same fraction at 40 and 160 px) ported from glisse
`test/lens.test.tsx:10-49`; `SpectrumTest` (`spectrum(0.4)[0] == 1.08f`); `RimTwinTest` (glisse
`test/glass.test.tsx:209-240`: lit edge > .55 of max, ~0 at 1.4 px in, lit corners > .45, unlit corners < .03);
`LensUniformsTest` (names and order). Robolectric `RuntimeShaderProbeTest`: draw a 4x4 node with a trivial AGSL
effect (`return half4(1,0,0,1)`) and read back a pixel via `captureToImage()`; record pass/fail and the exception
text in `api-notes.md` (do not fail the build either way; the test asserts only that it recorded a result).
Catalog: `GlassPage`: `StripedBackdrop` as source, three capsules (Bar preset) and a popover-sized card over it,
plus a rim bitmap rendered by the CPU twin at 4x zoom.
Screenshots: `e1.5-glass-flat-over-stripes-{light,dark}`, `e1.5-glass-frost-over-stripes-{light,dark}` (only if
Robolectric renders `RenderEffect` blur; else omit and write why), `e1.5-rim-cpu-twin-{light,dark}`,
`e1.5-glass-liquid-over-stripes-{light,dark}` only if the probe passed.
Acceptance: Flat surfaces are legible over stripes (label contrast visibly fine), hairline visible on both themes,
rim bright on top-left and bottom-right edges and fading toward the other corners in the twin shot.

## Step 6 (E1.6): buttons, rows, grouped sections, avatars, chip row, badge, empty state

Spec: SKILL `navigation-layout.md` 5.4, 6, 11; ARCH 1 (complaints), 4.3.
Dependency: add `kotlinx-collections-immutable` to the catalog (`ImmutableList` params, ARCH 10.2) and to `:design`.
Files (`dev.rovsti.design.controls`, `dev.rovsti.design.list`):
- `Buttons.kt` (glisse geometry from `src/button.tsx`, `src/styles.css:132-227`, header):
  `GlassIconButton(icon, contentDescription, onClick, glass: GlassSource?)` 44 dp circle (48 dp touch target via
  `minimumInteractiveComponentSize`-equivalent own modifier, not Material), `CapsuleButton(text, icon?, style =
  Glass|Filled, ...)` height 44, padding 16 (12 with icon), gap 8, label 17 sp, `TextButton`, `DestructiveButton`;
  disabled alpha 0.35; pressed fill switches to `glassActive` instantly and fades back over `focusFill` 200 ms;
  `glassPress` for glass styles, `scalePress` for filled; `Role.Button`; no ripple.
- `ListRow.kt` (ORIGINAL): `ListRow(title, modifier, secondary: String? = null, leading: (@Composable () -> Unit)? =
  null, trailing: (@Composable () -> Unit)? = null, meta: String? = null, onClick, onLongClick, actions:
  ImmutableList<CustomAction>)`; `heightIn(min = Sizes.rowMin / rowMinTwoLine)`, leading slot fixed 44 dp wide +
  `Spacing.m` gap, title `body`, secondary `subheadline` in `labelSecondary`, one line each with ellipsis, trailing
  meta `footnote`; `semantics(mergeDescendants = true)` + `customActions`; `rowPress`.
- `SectionHeader.kt`, `InsetGroupedSection.kt` (ORIGINAL): radius 20 dp, `Spacing.gutter` side inset, separators
  0.5 dp inset to the text start, hidden after the last row; sentence-case header in `footnote` `labelSecondary`.
- `Avatar.kt` (ORIGINAL): photo slot (`ImageBitmap?`) or initials; `avatarColor(key: String): Color` pure (stable
  hash -> 8-entry palette, each palette entry >= 4.5:1 with white initials, tested); initials drawn with a cached
  `TextMeasurer` layout inside `drawWithCache`; `GroupAvatar(keys)` = **one** composite circle (2 initials split, or
  the group glyph), never an overlapping stack.
- `ChipRow.kt` (ORIGINAL): `ChipRow(chips: ImmutableList<ChipModel>, selected, onSelect)` = one `LazyRow`,
  `horizontalArrangement = spacedBy(Spacing.s)`, `contentPadding = PaddingValues(horizontal = Spacing.gutter)`,
  `key`+`contentType`; selected = filled `accent` capsule with `onAccent` text animated with `appear`; count as a
  separate `caption1` label after the text with `Spacing.xs` gap.
- `Badge.kt`, `EmptyState.kt` (glyph 48 dp, title `title3`, one line `subheadline`, optional `CapsuleButton`),
  `SourceGlyph.kt` (14 dp SMS / WhatsApp glyph as vector `ImageVector`s drawn by us, `contentDescription`).
- Icons: a small `RovstiIcons` object of hand-written `ImageVector`s (chevron, plus, search, ellipsis, back, phone,
  message, person, map, gear, xmark, checkmark, trash). Do not depend on `material-icons`.
Tests: JVM `AvatarColorTest` (deterministic, palette contrast); Robolectric `ListRowSemanticsTest` (one merged node
with title+secondary+meta text, custom actions present, height >= 56 dp at fs1 and grows at fs2), `ChipRowTest`
(10 chips do not wrap: all chips share one top y; row is horizontally scrollable), `GroupedSectionTest` (no
separator after the last row).
Catalog: `ControlsPage`, `RowsPage` (contacts-like rows with avatars, a two-line row, chevron row, a group row with
`GroupAvatar`, an inset-grouped settings section), `ChipsPage` (12 chips overflowing), `EmptyStatePage`. Rebuild the
catalog home on `ListRow` + `InsetGroupedSection` and re-record `e1.1-catalog-home-*` (open it again).
Screenshots: `e1.6-buttons`, `e1.6-rows`, `e1.6-rows-fs2`, `e1.6-grouped-section`, `e1.6-avatars`,
`e1.6-chip-row-overflowing`, `e1.6-empty-state` (each `-light`/`-dark`).
Acceptance (judge in the PNGs): every row's text starts on one vertical line (leading slot fixed), 16 dp gutters,
no avatar overlap, chips on one line cut off at the right edge (not wrapped), selected chip legible in light,
fs2 rows grow instead of clipping, separators stop at the last row.

## Step 7 (E1.7): floating tab bar with puck, ChromeMetrics, content insets

Spec: SKILL `navigation-layout.md` 1, 2, 4.1-4.4, 11; `motion.md` 3, 5.2; `glass.md` 3.2 `TabLens`, 4.2 `Bar`.
Files (`dev.rovsti.design.chrome`):
- `ChromeMetrics.kt` (ORIGINAL, `navigation-layout.md` 2): `ChromeMetrics` (`bottomBarPx`, `topBarPx` written only
  from `onSizeChanged`), `contentInsets()` = safe drawing + bar + 8 dp, `LocalContentInsets`, `LocalChromeMetrics`.
- `TabBarGeometry.kt` (PORTED FROM GLISSE `src/styles.css:233-239,358-377,512-535` and
  `src/internal/segments.tsx:55-339`, header): pure functions: cell 72x52 dp, padding 4 dp, outer radius h/2 +
  padding, lens grow 24x16 dp, `puckRect(position, lift, squash, barSize)` **clamped to the bar's inner rect**
  (fix X4), `slotUnder(x)`, `isTap(releaseY, barRect, 24.dp)`, drag start 6 dp, `foldGeometry(progress)` for the
  folded circle variant (labels gone in the first third).
- `SelectionPuck.kt` (ORIGINAL, `motion.md` 5.2; uses the glisse springs): position/lift/velocity `Animatable`s;
  `press`, `drag` (undispatched `snapTo`), `release` (projected target via `Fling`, `SelectRest` with initial
  velocity, lift deflates after arrival <= 400 ms), `select(index)`; squash/stretch clamped 20 %, gated by reduce
  motion (no squash, `bounceless`).
- `RovstiTabBar.kt` (PORTED FROM GLISSE `src/tab-bar.tsx` geometry/gestures, header): `RovstiTabBar(tabs:
  ImmutableList<TabItem>, selected: Int, onSelect, onReselect, glass: GlassSource, minimizer: TabBarMinimizer?,
  folded: Boolean = false, imeVisible: () -> Boolean = { WindowInsets.isImeVisible })`; icon + label cells (10 sp
  medium label, 26 dp icon), labels drop at `fontScale >= 1.5`, centred, `widthIn(max = 500.dp)`, 16 dp sides,
  bottom margin = system bottom inset + 8 dp; hold rule from step 4; bar tug up to 4 dp toward the drag relaxing with
  `spring(1, 300)`; light page: lens drops under labels after half lift (UNDER = 0.5); selected label tint by
  clipping a second label draw to the puck rect (labels drawn once each); `selectableGroup()`, cells `Role.Tab`,
  `selected`, `stateDescription`; haptic `selection()` when the puck lands on a new tab, `tick()` on reselect;
  hidden while `imeVisible()`; glass `Bar` preset via `Modifier.glass`.
- `TabBarMinimizer.kt` (ORIGINAL, `navigation-layout.md` 4.4): 40 dp down to minimise, 8 dp up to expand, edge
  writes only; minimised look translate 40 dp + 35 % fade with `barMinimize`; the inset follows the animated bar in
  the layout phase.
- `BottomScrim.kt`: progressive scrim under the bar (stops 0/.35/.6/.85/1, alpha 0/.15/.4/.7/1 of page colour).
Tests: JVM `TabBarGeometryTest` (puck inside inner rect for lift 0..1 and squash 0..0.2 at first and last slot: fixes
X4; slotUnder; tap slop 24 dp; fold geometry monotonic), `TabBarMinimizerTest` (40 dp threshold, direction reset,
expand at 8 dp, top pull). Robolectric: `ListEndAboveBarTest` (AC3: `RovstiRootForTests` = content
`LazyColumn(contentPadding = LocalContentInsets.current)` with 50 rows tagged `row-i` + bar; `scrollToIndex(49)`;
assert `row-49` bottom <= bar top), `TabBarGestureTest` (tap selects; drag 1.6 slots and release selects slot 2
via projection; release > 24 dp below with a tap does nothing; vertical move before 150 ms leaves the gesture to the
list), `TabBarSemanticsTest` (Role.Tab, selected), `PuckBoundsTest` (pressed + stretched puck bounds inside bar
bounds), `ImeHidesBarTest` (`imeVisible = { true }` -> bar not displayed).
Catalog: `TabBarPage`: full-window striped list (glass source) + bar with 4 tabs (Keypad-like icons), a minimise
demo; `FoldedTabBarPage`.
Screenshots: `e1.7-tabbar-rest`, `e1.7-tabbar-drag-mid` (down on tab 0, move 1.5 slots, advance 100 ms),
`e1.7-tabbar-minimised`, `e1.7-tabbar-folded`, `e1.7-list-end-above-bar`, `e1.7-tabbar-fs2` (each light/dark).
Acceptance: puck fully inside the bar in rest and drag shots, centred on its tab in rest; last list row fully
visible above the bar with a gap; fs2 shows icons only, no clipped labels; bar sits 8 dp above the nav inset.

## Step 8 (E1.8): large-title scaffold, inline bar, toolbar capsules, back button

Spec: SKILL `navigation-layout.md` 10, 2, 11; `motion.md` 2.1 `titleCollapse`; ARCH 9 "Adaptive layout".
Files (`dev.rovsti.design.chrome`):
- `TitleCollapse.kt` (ORIGINAL): pure `titleCollapseFraction(firstIndex, firstOffsetPx, titleHeightPx): Float` and
  `LazyListState.rememberTitleCollapse(titleHeightPx: () -> Float): State<Float>` (`derivedStateOf`); smoothed by an
  `Animatable` with `titleCollapse` spring (threshold 0.001f) that is read only in `graphicsLayer`.
- `LargeTitleScaffold.kt` (ORIGINAL): `LargeTitleScaffold(title: String, state: LazyListState, actions:
  ImmutableList<ToolbarAction>, navigation: NavigationButton? = null, glass: GlassSource, content: LazyListScope.()
  -> Unit)`; the large title is item 0 key `"title"` (34 sp bold, max 2 lines, lifts 12 dp and fades), the inline bar
  (52 dp under the status bar, `headline` title) fades in by the fraction; top progressive scrim (bar + 64 dp run,
  page colour peaking at .42); reduce-transparency variant: solid bar + 0.5 dp divider after collapse; content padding
  from `LocalContentInsets`; `topBarPx` written to `ChromeMetrics`. Width cap: content `widthIn(max = 640.dp)` centred
  on wide windows; bars respect `displayCutout` start/end in landscape.
- `Toolbar.kt` (ORIGINAL): `ToolbarCapsule` grouping 44 dp `GlassIconButton`s in one glass capsule; `ToolbarSpacer`
  splits capsules; capsules vertically centred on the large title's first baseline row (fix X6: same 44 dp height
  band as the 34 sp line, never bigger); at most two actions plus an overflow `ellipsis` button whose `onClick` gives
  the anchor rect (`boundsInWindow`) to the caller (the menu itself is E1.10; leave `TODO(E1.10)` in the catalog).
- `BackButton.kt`: circular glass back button (chevron, mirrored in RTL, `contentDescription` "Back").
Tests: JVM `TitleCollapseTest` (0 at top, 1 past item 0, linear in between, zero height safe). Robolectric
`ScaffoldTest`: scroll by half the title height -> inline title alpha between 0 and 1 (read via a test hook
semantics property or a tag on the inline bar with `graphicsLayer` alpha checked through `captureToImage` pixel
sampling: pick one and note it); header actions limited to 2 + overflow (third action goes to overflow); toolbar
capsule height == 44 dp and its centre within 4 dp of the title's first line centre at fs1; `contentMaxWidth`
respected at w840.
Catalog: `LargeTitlePage` (contacts-like list of 40 `ListRow`s, two actions + overflow, a pushed sub-page with the
back button).
Screenshots: `e1.8-title-expanded`, `e1.8-title-collapsed`, `e1.8-title-mid-scroll`, `e1.8-title-fs2`,
`e1.8-title-landscape` (variant Landscape), `e1.8-title-w840` (qualifiers `w840dp-h1200dp-xxhdpi`) (light/dark each).
Acceptance: expanded shot has the title at the gutter with the capsules aligned to its line and not taller; collapsed
shot shows the inline title centred in the bar and rows scrolling under the scrim; mid-scroll shows both partially;
fs2 title wraps to 2 lines without overlapping the capsules; landscape bars usable and not under the cutout; w840
content is a centred 640 dp column.

---

## After the round

- Every story E1.1-E1.8 has `done` and an Evidence line (commit SHA, PNGs opened, what was seen).
- The next planner (round 2) takes E1 R2 (E1.9-E1.16) and must re-open the R1 goldens it builds on (tab bar, rows,
  glass) before planning overlays.
- Open items to carry: Liquid-tier verification depends on the probe (step 5); the overflow menu wiring (E1.10);
  theme crossfade 280 ms (polish); fake image fetcher in `ShotTest` (E1.20).
