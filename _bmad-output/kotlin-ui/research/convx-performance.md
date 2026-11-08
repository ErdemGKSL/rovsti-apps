# Convx performance and smoothness engineering: research report

Topic: performance (recomposition, lists, images, profiles, R8, refresh rate, threading, Gradle setup, testing).
Author: research agent. Audience: whoever writes the Rovsti Kotlin/Compose UI and its performance skill.

## 0. Licence rule and how to read this report

- Convx is GPL-3.0 (`LICENSE`; every Convx-authored file header says "Licensed under GPL-3.0"). Rovsti is MIT/Apache.
  Everything below is a technique, a parameter or an architecture idea described in my own words. No Convx source is
  reproduced; the pseudo-code is written fresh. When we implement, we write our own code from the "Recipe for Rovsti"
  section and never open a Convx file while typing the implementation.
- Some Convx code is NOT Convx-authored: `app/src/main/kotlin/com/convx/music/ui/component/backdrop/**` is vendored from
  Kyant0/backdrop v2.0.0 under Apache-2.0 (header of `backdrop/Platform.kt:1-9`, `backdrop/RuntimeShaderCache.kt:1-9`), and the
  app depends on Haze (`dev.chrisbanes.haze`, `gradle/libs.versions.toml` `haze = "1.3.1"`). Convx's own edits to the vendored
  files (freeze, pool, static regions: `GlassPool.kt`, `BackdropFreeze.kt`, `NavTransitionFreeze.kt`) are carried under GPL.
  Rovsti can legally depend on the upstream Apache-2.0 artifacts directly (`io.github.kyant0:backdrop`, Haze). Re-check each
  upstream licence at the version we pin before depending on it. Do not copy Convx's modified versions.
- Path shorthand. `CONVX` = `/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/ref/Convx`.
  `A/` = `CONVX/app/src/main/kotlin/com/convx/music/`. `UI/` = `A/ui/`. Line numbers are from the checked-out tree.

### Evidence limits (read before trusting any "measured" claim)

1. The Convx git history is ONE commit (`55b11a2`, "Fix banner", 2026-10-31). `git log -n 200 --stat` yields nothing to mine.
   Everything historical comes from code comments, `changelog.json`, `.github/release-notes/*.md`, `automation/progress/*.md`.
2. `docs/profiling/` contains one device shell script and no results (`docs/profiling/convx_prof.sh`). `docs/CODEBASE_REVIEW_2026-10.md`
   is a readability review and says repeatedly "No device performance benchmark was run" (lines 7, 82, 94, 142, 218).
3. `automation/progress/005-perf-120fps-pass.md` is explicitly "STATIC CODE REVIEW ONLY, NOT VERIFIED" (line 1) and is STALE
   against the current tree: its finding F1 (hero scroll progress returned as a plain `Float`) is already fixed in code (section 2.3).
   Treat that file as a catalogue of bug classes, not as current state.
4. Numbers quoted inside source comments (for example "47ms of layer.record per scroll frame with the freeze off, 1.9ms with it on",
   `UI/component/backdrop/backdrops/BackdropFreeze.kt:21-24`; "content alone measured 14ms/frame against an 8.33ms budget",
   `UI/screens/HomeSectionOrder.kt:61-65`; "36.8ms to 24.4ms p50", `automation/tasks/005-perf-120fps-pass.md:36`) are the authors'
   own device readings. They are plausible and useful as orders of magnitude, but unreproducible from this repo.
5. `contributor/skills/*` (android-performance-skill, android-compose-ui, improve-animations, ...) are generic, third-party-style
   guidance bundled for contributors (the animation audit cites Emil Kowalski's web-CSS philosophy). They are NOT evidence of what
   the app does. Only `contributor/skills/convx-android-motion/SKILL.md` is Convx-specific, and it is a short checklist.
6. There is no screenshot testing, no Macrobenchmark module, no baseline-profile generator, no LeakCanary in Convx. See section 11.

## 1. Executive summary (the ten things that matter)

1. The whole "everything is fluid" feel comes from one discipline: per-frame values never travel through composition.
   They travel as `State<Float>` holders or `() -> Float` lambdas and are read only inside `graphicsLayer {}`, `drawWithContent`,
   `drawBehind`, `Canvas {}` or layout/offset lambdas. Composition only reacts to coarse booleans derived from them.
2. Press feedback, sheet drag, scroll-driven chrome, seek progress, shimmer and mesh backgrounds all follow rule 1.
3. Expensive glass (blur + refraction) is made cheap by *not re-recording* it: a family of "freeze" clocks (scroll, nav push,
   pager swipe, sheet drag, tab-bar morph) lets the glass reuse its last capture while content moves. Captures are also taken at
   about a third of screen resolution and clipped to just the region the glass actually covers.
4. Continuous animations are gated by visibility, lifecycle, an app-wide "motion frozen" flag, the system animator-duration scale and a
   user "reduce dynamic blur" preference. Nothing animates forever off screen.
5. Lazy lists get stable keys, `contentType`, hoisted preferences (a single `@Immutable` prefs object in a composition local, never a
   per-row DataStore read) and pre-built, `remember`ed image requests sized to the on-screen pixels.
6. Compose compiler: Kotlin 2.3.10, Compose 1.10.2, strong skipping on by default (no flag), plus a stability-configuration file for
   model classes from modules that do not apply the Compose plugin. About 138 `@Immutable`/`@Stable` annotations app-wide.
7. Startup: wildcard baseline profile (everything hot), preference snapshot mirror warmed off-main, Coil singleton built lazily with a
   pre-seeded cache size, StrictMode only in debug. R8 full shrink on release but with very broad keep rules.
8. High refresh rate: explicit `preferredDisplayModeId` for the fastest mode at the current resolution (API 30+), `preferredRefreshRate`
   fallback below, a user toggle to force 60 Hz. No frame-rate API (`Surface.setFrameRate`) is used.
9. Weak spots to NOT copy: no measured profile, no benchmarks, no screenshot tests, a wildcard profile, over-broad ProGuard keeps,
   a few main-thread tickers, one `composed {}` modifier still in use, a probably broken compose-metrics flag.
10. For Rovsti we can reach the same smoothness with far less machinery, because we do not need liquid-glass refraction on every
    surface. The expensive subset (full-screen Stops map plus floating controls, popovers, sheets) needs exactly the same freeze and
    deferred-read discipline. Section 13 turns this into a build recipe.

## 2. Compose compiler, stability and recomposition control

### 2.1 Toolchain and Gradle module setup

- Versions come from a catalog: `gradle/libs.versions.toml:1-12`: `androidGradlePlugin = "9.4.1"`, `kotlin = "2.3.10"`, `compose = "1.10.2"`,
  `lifecycle = "2.10.0"`, `material3 = "1.5.0-alpha18"` (plus a second `material3Version = "1.4.0"` used by the app dependency, line 56 and
  `app/build.gradle.kts:269`), `coil = "3.3.0"`, `haze = "1.3.1"`. There is no Compose BOM; each Compose artifact is pinned by the same `compose` version ref
  (`libs.versions.toml:81-88`). Compose compiler is the Kotlin plugin `org.jetbrains.kotlin.plugin.compose` at the Kotlin version
  (`libs.versions.toml:[plugins]`, `app/build.gradle.kts:25`). `compileSdk = 37`, `minSdk = 26`, `targetSdk = 36`, JVM 21 (`app/build.gradle.kts:30-41,161-180`).
- Strong skipping: not configured anywhere (grep of every `*.kts`, `*.toml`, `*.properties` finds no `strongSkipping`/`featureFlags`). It is the default
  from Kotlin 2.0.20 on, so every composable with unstable parameters is still skippable by instance equality, and lambdas are auto-remembered.
- `composeCompiler { stabilityConfigurationFiles.add(compose_stability.conf) }` (`app/build.gradle.kts:229-235`). `app/compose_stability.conf:1-35`
  lists package globs for classes that come from modules without the Compose plugin (innertube models, DB entities, media3 `MediaItem`, `Timeline`...).
  Rationale in its header: without it "every YouTube row/tile was unskippable" (`app/build.gradle.kts:230-231`). The tradeoff is explicit in the file: only
  declare immutable models whose `equals()` is a correct skip test.
- Compose compiler reports: root `build.gradle.kts:36-47` adds `reportsDestination`/`metricsDestination` when `-PenableComposeCompilerReports=true`.
  Caution: it interpolates `${project.layout.buildDirectory}` (a `DirectoryProperty`) directly into a string; that renders the property object,
  not a path. Probably broken; do not copy it. Use `layout.buildDirectory.dir("compose_metrics").get().asFile`.
- Gradle properties are tuned for a small CI/laptop, not for speed: `gradle.properties:15-34` `-Xmx2560M`, `UseSerialGC`, `TieredStopAtLevel=1`,
  `kotlin.compiler.execution.strategy=in-process`, `org.gradle.workers.max=1`, `parallel=false`, `daemon=false`, `caching=false`,
  `configuration-cache=false`, `android.newDsl=false`. Useful to know for our disk/memory-limited agent environment (cheap on RAM, slow), not a model for a dev machine.
- Modules (`settings.gradle.kts:15-24`): `:app :canvas :innertube :lyrics :integrations :spotify :artwork :network-storage :spine :hig`. Only `:app` and `:hig`
  apply the Compose plugin (`hig/build.gradle.kts:1-5`); data modules do not, hence the stability file. `:hig` (117 files, vendored Cupertino components built on
  `io.github.kyant0:backdrop` and `zone.ien.capsule`) has no test directory (`docs/CODEBASE_REVIEW_2026-10.md:216`).

### 2.2 Stable models and immutable carriers

- Preferences object: one `@Immutable data class ItemPrefs(...)` built once at the app root from five DataStore preferences and published through a
  `staticCompositionLocalOf` (`UI/component/ItemPrefs.kt:22-60`). Rows read `LocalItemPrefs.current.cropAlbumArt` instead of each calling
  `rememberPreference`. The comment states the reason: "read once to avoid blocking DataStore reads per lazy item". The progress doc records the previous
  state (a `runBlocking` seed per arriving row) as removed (`automation/progress/005-perf-120fps-pass.md`, "Checked and cleared").
- Download badges: `LocalDownloads` is a (non-static) `compositionLocalOf<Map<String, Download>>` so only readers of the map recompose (`ItemPrefs.kt:62`).
- Wrapper for lists: `@Immutable data class MeshPalette(val colors: List<Color>)` ("wrapped so the backdrop can skip recomposition",
  `UI/player/MeshGradientBackground.kt:89-90`). `List<T>` is unstable to the compiler; a one-field `@Immutable` wrapper is the cheap fix when strong skipping alone
  is not enough (strong skipping compares by `===` for unstable types, so a freshly allocated `List` still defeats skipping).
- State holders for per-frame values: `@Stable class ProgressState(positionState, durationState)` exposes `val progress: Float get()` and is only read in a draw block
  (`UI/player/MiniPlayer.kt:178-195`, used at `MiniPlayer.kt:553-600`). The holder is stable and constant; the changing numbers are inside snapshot state that nobody
  reads during composition.
- `@Stable class AppBackdropState(...)` bundles backdrop, freeze provider and the motion-frozen flag so passing it down costs one stable parameter (`A/AppBackdropState.kt:30-38`).
- No `kotlinx.collections.immutable` anywhere (grep: 0 matches). Convx relies on strong skipping + the config file + `remember` instead.
- Counts: 132 `@Immutable`, 84 `@Stable` text matches across `app` (grep).

### 2.3 The deferred-read pattern (the single most important technique)

Every item below is one application of the same rule: *write animation/scroll/gesture state into a holder; read it where it is consumed, in a phase after composition.*

| Use | Where | What it does |
|---|---|---|
| Hero scroll progress | `UI/component/HeroArtwork.kt:342-372`, `UI/component/ChromeScrim.kt:20-41` | A `derivedStateOf` over `firstVisibleItemIndex`/`firstVisibleItemScrollOffset`, clamped to 0..1 over a 300 px ramp, returned as `State<Float>` (not `Float`). Consumers read `.value` only inside `graphicsLayer {}`/hazeEffect lambdas. |
| Top-bar title alpha | `UI/component/FrostedTopBar.kt:105-130` | Takes `titleAlphaProvider: (() -> Float)?` and `pullFraction: () -> Float`; derives a coarse `showDivider` boolean (`> 0.99f`) so composition changes only when it flips. |
| Sheet-behind scale/dim | `UI/component/IosSheetPresentation.kt:50-100` | Two overloads: a value overload and a `progressProvider: () -> Float` overload that reads inside `drawWithCache`+`graphicsLayer` ("zero-recomposition 120Hz smoothness while tracking card gestures", lines 72-76). |
| Artwork morph | `UI/player/PlayerArtworkMorph.kt:184-200, 431` | `progress: () -> Float`; the rect slots are read inside `Snapshot.withoutReadObservation {}` except while `flying`, so the overlay does not recompose on every frame of the bar's own morph (that was "scroll-triggered jank", lines 187-188). |
| Mini-player progress ring | `UI/player/MiniPlayer.kt:553-600, 883` | `drawWithContent` reads `progressState.progress`; no recomposition when position ticks. |
| Seek bar | `UI/player/Player_v2.kt:1579-1590, 1906-1950` | The position ticker is isolated in its own composable `V2SeekSection(positionProvider, durationProvider, ...)`; only the bar and the two time labels recompose on a tick (comment: "20Hz position ticker ... 120Hz panels"). The time label re-lays out only when the displayed second changes (1913-1914). |
| Mesh background | `UI/player/MeshGradientBackground.kt:117-170` | An `Animatable` phase is read inside `Canvas {}`; colour tweens are `animateColorAsState` whose `.value` is read in the draw lambda. |
| Shimmer | `UI/component/Skeletons.kt:50-80` | `rememberInfiniteTransition().animateFloat` read inside `drawWithCache { onDrawBehind {...} }` "a screenful of these would otherwise recompose on every animation frame". |
| Melt-morph shader | `UI/player/Thumbnail.kt:1115-1125` | `MeltMorphOverlay(progress = { morph.value }, timeSeconds = { timeSeconds })`; "passing the values recomposed this whole composable every frame of the melt". |
| Alphabet scroll rail | `UI/component/AlphabetScrollBar.kt:316-345` | `layoutInfo`/`firstVisibleItemIndex` are read inside lambdas only; comment: reading in composition "would recompose the whole screen on every scrolled pixel". |
| Draw-phase glass colour | `UI/component/GlassEffect.kt:355-377` | Light/dark tint `animateColorAsState` is read in the draw phase so a theme crossfade "never recomposes or rebuilds the backdrop effects". |
| Press feedback | `UI/utils/ModifierExt.kt:84-200` | Click wrappers use `Modifier.clickable` + a custom `IndicationNodeFactory`; the indication node owns an `Animatable` scale and reads it in `ContentDrawScope.draw()`. Spring: damping ratio medium-bouncy, stiffness medium-low, pressed scale 0.94 (136-190). |

Related coarse-gating trick: when a continuous value only matters inside a band, derive booleans and compose the layer only while true
(`HeroScrollBlur` composes three blur plates only while the progress is inside their bands, `HeroArtwork.kt:268-320`; `HeroTopBlur` swaps to an empty
same-size `Box` while progress is ~0 so a motionless hero pays no blur cost, `HeroArtwork.kt:197-245`).

Why the non-`Unit` return trap matters: a `@Composable` that returns a value (here `Float`) is not restartable, so a snapshot read at the `return` is charged to the
CALLER's scope. `automation/progress/005-perf-120fps-pass.md:35-80` (finding F1) documents this and its fix shape (return `State<Float>` or `() -> Float`, read in
draw). The tree now follows that fix (`HeroArtwork.kt:342-372`, `ChromeScrim.kt:30`), so F1 is solved.

### 2.4 Remember keys, allocation-free arguments, and lambdas

- Constant strings and fixed domain objects hoisted and `remember`ed: `UI/screens/library/LibraryMixScreen.kt:211-243` builds six `Playlist` stand-ins from
  `stringResource` values with `remember(name)`. The comment explains the bug: they "were rebuilt on every recomposition and passed straight into keyed lazy items,
  a new instance each time defeats skipping, so all six rows recomposed on any state change".
- Image requests are built inside `remember(url, px)` so a request object is stable across recompositions (`UI/component/ItemThumbnails.kt:137-150`,
  `UI/component/PlaylistItems.kt:358-373`, `SeededHeroChrome.kt:77-100`).
- Pinning lambda identity where an unstable identity would restart a heavy effect: `val shapeBlock: () -> Shape = remember(shape) { { shape } }` and a
  `remember(...)` keyed `effectsBlock` for the backdrop effect chain ("Stable lambda identities keep recomposition from invalidating the backdrop",
  `UI/component/GlassEffect.kt:374-420`). Likewise a draw lambda keyed on the base colour so the app backdrop is not re-recorded (`A/AppBackdropState.kt:53-62`).
- Shared defaults: a single default effect object so each icon does not restart its effect (`UI/component/SymbolIcon.kt:57-60`); an entry-bounce set so scrolling or
  recomposition never replays bounces (`SymbolIcon.kt:60`).
- Preferences in non-fatal form: `rememberPreference` seeds the initial value from a synchronously readable snapshot, then collects `distinctUntilChanged()` with
  `collectAsStateWithLifecycle` (`A/utils/DataStore.kt:83-120`). Setter launches in `rememberCoroutineScope`. Consequence: first frame has the real value, not a flash of the default.
- `derivedStateOf` is used 118 times, almost always to turn a continuous value into a boolean or a clamped fraction (examples above). `collectAsStateWithLifecycle`
  531 vs plain `collectAsState` 139 uses (grep counts).

### 2.5 Known anti-patterns recorded in Convx (do not repeat)

All from `automation/progress/005-perf-120fps-pass.md` unless noted; each is a hypothesis in that file, not a measured result.
- `rememberHeroSource(songs = list.map { ... })` at 12 call sites allocates one `Pair` per song per recomposition just to read one element (F2, lines 83-112). The fix is to
  pass a single nullable fallback URL. The current signature (`HeroArtwork.kt:387-403`) still has the `List<Pair>` parameter, so F2 is NOT yet fixed.
- A 4 Hz main-thread ticker plus unconditional `"%.2f".format` for a log-only string in the crossfade planner (F3, lines 114-155). Moral: loggers behind `isLoggable`, tickers off-main.
- `graphicsLayer { alpha = 1f }` (identity effect still allocates a RenderNode): "effect lambdas that return a zero value instead of null" (F4, lines 157-175). Gate effects with `null`/branches, never with zero-valued parameters. They claim a full sweep for `renderEffect =`, `BlurEffect(0f`, zero-radius blur found everything else null-gated.
- `data class` config copied per composition (`withFixedStyle()` on a 25-field `GlassEffectConfig`, F5). Allocation pressure, not invalidation.
- `Modifier.composed {}` regression: click wrappers were once `composed`, now plain `clickable` + indication node (`UI/utils/ModifierExt.kt:84-135`). Four `composed` uses remain
  (`ModifierExt.kt:118` `pressWobble`, `ComposeDebugUtils.kt:37`, plus two others); the progress doc says none are in a lazy item path. Prefer `Modifier.Node` + `ModifierNodeElement`
  (as the glass pool and static-region modifiers do, `backdrop/GlassPool.kt:75-110`, `backdrops/BackdropStaticRegion.kt:25-78`).
- Writing state during composition "is what a recomposition loop is made of" (`UI/ios/compat/IosCompatTail.kt:212`).

## 3. Lazy lists

Convx lists are ordinary `LazyColumn`/`LazyRow`/`LazyVerticalGrid` with discipline, no custom prefetch API.

- Every `item`/`items` carries a `key` and a `contentType`: 134 `key = {` and 206 `contentType` matches. Examples: `UI/screens/HomeScreen.kt:559-741` (`item(key = "sign_in_card", contentType = "sign_in_card")`, "loading_shimmer", "bottom_spacer" ...),
  `UI/screens/HomeShelf.kt:148-270` (`items(entries, key = { it.id }, contentType = { "shelf_card" })`, separate content types for spotlight/video/shelf-list columns).
  Effect: row slots are reused across items of the same type, and composition state survives reorder.
- Rails inside a feed: each Home shelf is one lazy `item` hosting a `LazyRow`; snapping rails use `rememberSnapFlingBehavior(listState, SnapPosition.Start)` (`HomeShelf.kt:259-265`).
- Cap what is composed at once: Home limits similar-recommendation sections and home-page sections because "each extra section is ~12 more simultaneously composed rows, the single largest driver
  of Home's frame cost (content alone measured 14ms/frame against an 8.33ms budget)" (`UI/screens/HomeSectionOrder.kt:61-72`, constants `MaxSimilarSections`/`MaxHomePageSections`).
- Hoist per-row dependencies out of the row: prefs (`ItemPrefs`) and downloads via composition locals (section 2.2). Per-row `swipe` wrapper only when enabled: rows are built as
  `content()` and wrapped in `SwipeToSongBox` only if the preference is on (`UI/component/SongItems.kt:150-165`).
- Never `animateScrollToItem` while scrubbing: use `scrollToItem` because "each animation would be cancelled by the next, so the list would crawl behind the thumb" (`AlphabetScrollBar.kt:333-338`).
  The scroll rail computes header offset as `layoutInfo.totalItemsCount - itemCount` inside lambdas (`AlphabetScrollBar.kt:325-345`).
- Visibility-gated infinite effects inside lists: `Modifier.marqueeWhenVisible()` turns on `basicMarquee()` only when `boundsInWindow()` is non-empty, because a plain marquee "drives an animation
  frame forever" for every row Compose keeps around, including prefetched ones (`UI/utils/MotionModifiers.kt:239-275`).
- Pager tabs: `HorizontalPager(userScrollEnabled = false)` for the three top-level tabs and a custom `slideToPage` that first jumps to the page adjacent to the target in one frame and
  then animates only one page, so intermediate tabs are never composed inside animation frames (`UI/screens/MainTabsPager.kt:27-70`). `beyondViewportPageCount` stays default.
  A companion `rememberPagerTransitionFreeze` marks the backdrop frozen while a pager scroll is in progress (`backdrops/NavTransitionFreeze.kt:46-62`).
- Position tracking without recomposition: observing `isScrollInProgress` through `snapshotFlow` in a `LaunchedEffect`, not keying composition on scroll offset
  (`UI/player/Thumbnail.kt:360-395`).
- Lyrics: follow scroll is one suspend function owned by one effect; it scrolls to the item then `withFrameNanos {}` once so layout info exists, then animates by the exact distance with a 550 ms
  cubic-bezier tween (`UI/component/LyricsFollowScroll.kt:12-35`); line index polled at 100 ms because "line positions only need ~10Hz" (`UI/component/Lyrics.kt:757`).
- Not used: `LazyLayoutCacheWindow`, custom prefetch strategies, nested prefetch tuning, `ImmutableList`. Compose 1.10 defaults apply.

## 4. Image loading (Coil 3)

- One `SingletonImageLoader.Factory` in `Application` (`A/App.kt:66-67, 349-398`). Settings: crossfade off globally; `allowHardware(true)` + `bitmapConfig(Bitmap.Config.HARDWARE)`
  ("direct zero-copy GPU texture uploads", lines 353-356); decoders: `AnimatedImageDecoder` on API 28+ else `GifDecoder`, `SvgDecoder`, a custom interceptor that steps YouTube art down a
  size on 404 (`ArtworkQualityInterceptor`), a network-artwork fetcher.
- Memory cache: `maxSizePercent(context, 0.25)`, or `0.15` on `isLowRamDevice` (lines 369-381). Disk cache: `filesDir/coil` (not `cacheDir`, so Android cannot purge it under storage pressure),
  size from user setting default 512 MB, 0 disables disk caching (lines 382-395). The cache size is read once at startup on `Dispatchers.IO` into a `@Volatile` field so the factory never blocks Main (`App.kt:150-155, 345-350`).
- `onTrimMemory(level >= TRIM_MEMORY_RUNNING_LOW)` clears the Coil memory cache (`App.kt:400-407`).
- Decode to the on-screen size: `ItemThumbnail` takes `targetSizePx`, with `thumbnailPx(dp) = dp.roundToPx()` for list rows (`UI/component/ItemThumbnails.kt:61, 96-135`). KDoc on the parameter is a perf lesson: a flat 544 px decode
  for a 48 dp row is "~10x the pixels the row can show", yet a flat constant was *under*-sized for big grid tiles on high-density phones and looked soft. Hence: always compute px from dp and density, never a global constant.
- Requests are `remember(url, px)`, set an explicit `.size(w, h)`, set `diskCacheKey` to the resolved (resized) URL so each size tier caches independently, and explicitly enable memory/disk/network policies (`ItemThumbnails.kt:137-150`).
- Use `AsyncImage` with painter placeholders, not `SubcomposeAsyncImage`: the no-artwork placeholder is a custom `Painter` so rows avoid "a subcomposition cost on every successful load" (`ItemThumbnails.kt:63-90`).
- Thumb-then-full hero: the lower layer repeats the grid tile's request (same URL/size/key, so a memory hit, opaque on frame 1), the top layer fades the full-res image in with a 1024 px request (`UI/component/SeededHeroChrome.kt:60-110`). Shimmer hero uses the thumb only (112-135).
- Artwork-derived colours: `rememberArtworkTint` keeps a 128-entry `LruCache` keyed by URL, seeds `remember` from the cache so a revisit paints immediately, and runs decode+analysis on `Dispatchers.IO` with a 200x200 software bitmap (`allowHardware(false)`)
  (`UI/theme/ArtworkTint.kt:28-75`). `ColorEngine` has a 60-entry LRU in memory plus a disk cache (`UI/theme/colorengine/ColorEngine.kt:33-105`). Mesh palette extraction runs on `Dispatchers.Default` (`UI/player/MeshGradientBackground.kt:75-85`, `UI/component/ArtworkMeshBackground.kt:93`).
- Crash-safety learned the hard way: Coil's `Dimension` throws on size <= 0, so the morph overlay returns early for degenerate rects (`PlayerArtworkMorph.kt:200-206`, "measured crash, Samsung").

## 5. Controlling the cost of glass and blur (backdrop architecture, performance view)

The visual recipe is covered by other research files (convx-glass.md, convx-overlays.md). This section covers only the performance mechanics.

### 5.1 Cost model Convx works from

- RenderEffect/RuntimeShader need API 31/33 (`backdrop/Platform.kt:13-17`). Glass is gated by `isGlassSupported() && !isLowRamDevice()` (`UI/component/GlassEffect.kt:170, 181-183`); otherwise a clipped translucent fill is drawn (`GlassEffect.kt:300-307, 363-368`). Three styles: LIQUID (blur + lens + rim), BLUR (no lens, "the lens is the most expensive link"), TRANSPARENT (no capture) (`GlassEffect.kt:125-139, 378-381`).
- Capture resolution scales with blur: `glassResolutionScale(blurDp) = 1 - clamp(blurDp/2.5, 0, 1) * (1 - 0.30)`, i.e. capture at 30% size once blur >= 2.5 dp (`GlassEffect.kt:146-163`). Pixel-sized effect params are multiplied by the same scale (`GlassEffect.kt:320-330`).
- Shared pool: instead of every glass surface capturing and blurring on its own, one `glassPool` records the app backdrop once at `GlassPoolScale = 0.35` of screen size (lowered from 0.5 "after scrolling profiles showed fewer janky frames", `backdrop/GlassPool.kt:38-42`), applies saturation and blur once,
  applies refraction for up to `MultiRefractionMaxShapes = 8` rounded-rect samplers in one shader pass (`backdrop/internal/Shaders.kt:106`), and masks to each sampler's outline in one flatten step (`GlassPool.kt:130-250, 300-345`). A surface uses the pool only when its blur radius and vibrancy match the pool's and it needs no chromatic dispersion (`GlassEffect.kt:311-318`).
- Region clipping: the capture keeps full-screen coordinates but only replays inside the union of sampler rects plus a 32 dp margin (`CoverageMarginDp`, `GlassPool.kt:54, 205-228`), grown on demand and never shrunk. Their note: "scale cuts GPU fill, not op submission" (`GlassPool.kt:110-117`); the replay was the dominant RenderThread cost. Layers are also sized to the region, not the screen, because tiled GPUs load/store the whole target per pass (`GlassPool.kt:277-290`).
- Shader objects are cached by key (`backdrop/RuntimeShaderCache.kt:20-32`).
- Haze (used for hero top blur): `blurRadius = 36.dp`, `inputScale = HazeInputScale.Fixed(0.33f)`, `noiseFactor = 0f`, progressive gradient, alpha driven by the draw-phase scroll state (`UI/component/HeroArtwork.kt:222-243`). Below API 33 it falls back to a plain page-colour scrim.
- "Hero background blur baked once instead of per frame" (`.github/release-notes/2.0.0-nightly.md:69`) corresponds to `HeroScrollBlur`: three pre-blurred plates at 0.3x/0.6x/1x radius crossfaded by alpha (`HeroArtwork.kt:268-320`, `decodeSize = 160`).

### 5.2 Freeze clocks (the idea Rovsti should adopt)

Principle: the source content under glass changes every frame while scrolling, but the user cannot see a blur lag by ~100 ms. So when content is moving, glass keeps drawing its last capture, then re-captures when motion stops.

- Implementation shape: each freeze is an object holding a plain `LongArray(1)` timestamp (NOT snapshot state) and exposing `frozen: () -> Boolean`. The comment explains why: reading snapshot state in the draw phase registers a draw dependency, "every write re-invalidates the frame"
  (`backdrops/BackdropFreeze.kt:25-34`). A `NestedScrollConnection` stamps the clock in `onPreScroll` and clears it in `onPostFling`; a 900 ms safety timeout covers gestures that never deliver a fling end (`BackdropFreezeSafetyNs`, `BackdropFreeze.kt:14, 55-60`).
- Entry freeze: a screen-local backdrop starts frozen for 300 ms (`BackdropEntryFreezeWindowNs`) because every screen is mid push-animation the first time it composes; set at construction, not in an effect, so the very first draw is covered (`BackdropFreeze.kt:20, 36-48`).
- Navigation freeze: 330 ms window after a push, not for predictive-back pops (`NavTransitionFreeze.kt:17-45`); pager freeze loops `withFrameNanos` while `isScrollInProgress` (`NavTransitionFreeze.kt:46-62`).
- Tab bar: scroll tail of 120 ms after the last scroll event (`ScrollFreezeTailNs`, `UI/component/floatingtabbar/FloatingTabBar.kt:184, 388-440`); frozen also while the bar morph runs or the player morph is active (`FloatingTabBar.kt:266-285`).
- Sheets: the sheet publishes `backdropFrozen` while dragging plus while a tap-driven morph runs (`UI/component/BottomSheet.kt:360-373`).
- App-level aggregation: the app backdrop's `frozenProvider` ORs scroll, nav and tab-bar freezes (`A/AppBackdropState.kt:64-77`). The same signal is mirrored into a coarse `MutableState<Boolean>` `appMotionFrozen`, set on the first `onPreScroll` of a gesture and re-synchronised every 120 ms by a polling effect (`AppBackdropState.kt:79-102`), then exposed as `LocalAppMotionFrozen` (`UI/component/GlassEffect.kt:265`, provided at `A/AppRootContent.kt:766`). Ambient animations read it to pause (section 6).
- The glass consumer: a draw-modifier node re-records its layer only when its geometry (offset or size) changed, its element/effects changed (`surfaceDirty`), or the source version changed and it is not frozen. While frozen, geometry changes still re-record (a moved surface would otherwise show the wrong region); a version change schedules a "thaw" redraw polled every 48 ms (`ThawPollMs`) because a plain lambda that flips to false cannot invalidate drawing by itself (`backdrop/DrawBackdropModifier.kt:350-365, 470-512, 631-632`).
- Static regions: parts of the source known not to change declare themselves (`backdropStaticRegion`), so a version bump caused elsewhere does not re-record glass that sits entirely over a static region (`backdrops/BackdropStaticRegion.kt`, consumed at `DrawBackdropModifier.kt:480-492`).
- Per-route gate: only routes that have a glass consumer record the app backdrop (`backdropHasConsumer`, per `automation/progress/005-perf-120fps-pass.md`, "Checked and cleared"). The line numbers in that doc point into a pre-refactor `MainActivity`; the logic lives in `A/AppRootContent.kt` now.
- Double `graphicsLayer()` around a screen-local backdrop is deliberate: outer to stop sibling redraws re-recording the list, inner to collapse the content into one RenderNode (same doc; "load-bearing, leave it").

### 5.3 Escape hatches for slow devices

- `ReduceDynamicBlurKey` ("Reduce dynamic blur" preference) is consulted in 17+ places: top bar becomes a solid background with a hairline divider (`FrostedTopBar.kt:123-160`), hero blur disabled (`HeroArtwork.kt:143, 205, 268`), mesh backgrounds snap instead of tween or never animate (`MeshGradientBackground.kt:105-112`, `ArtworkMeshBackground.kt:113`, `AnimatedChromeMesh.kt:55`), `TopFadeBlur` returns early (`TopFadeBlur.kt:83-85`).
- `isLowRamDevice` halves the image memory cache and disables glass. High refresh rate and dynamic blur are separate user toggles.

## 6. Ambient and continuous animation discipline

- Mesh phase driver (`UI/component/AnimatedChromeMesh.kt:50-100`): one effect that only runs while (a) lifecycle RESUMED (`repeatOnLifecycle`), (b) the composable is on screen (bounds in window non-empty, written to a coarse `visible` state), (c) the app motion-frozen flag is false, (d) the caller's `paused()` lambda is false, (e) the system `MotionDurationScale` is > 0, and (f) reduce-motion is off. Inside, a `withFrameNanos` loop updates a phase only every 33 ms (`MeshFrameIntervalNanos = 33_000_000L`, i.e. 30 fps cap) and clamps the elapsed delta to 0.1 s so a stall never makes a jump. The phase is a `mutableFloatStateOf` read in `drawWithCache`.
- Same pattern for the artwork mesh: bitmap-backed mesh double-buffered (two bitmaps, render on `Dispatchers.Default`, swap a `displayed` image state), 33 ms cap, pauses while navigation animates or scrolling (`UI/component/ArtworkMeshBackground.kt:118-175`). The bitmap is drawn by `Canvas`, so the mesh never recomposes; the only state write per render is the image swap.
- Home hero mesh pauses on scroll: `meshPaused = { motionFrozen.value || deps.isScrolling() }`, remembered with keys (`UI/screens/HomeFeedSections.kt:150-160`).
- Mini-player glow: `rememberInfiniteTransition` only created when `isPlaying && !motionFrozen.value`, otherwise a constant state; the comment calls out "one recomposition per scroll start/end" (`UI/player/MiniPlayer.kt:1215-1250`).
- Player mesh background intentionally does NOT drift continuously: "Blobs drift during opening and track changes only; continuous drift would blur a full-screen layer every frame" (`MeshGradientBackground.kt:96-100`). It scales the canvas 1.3x and uses `Modifier.blur(64.dp)` once (`:147-154`).
- Video: canvas/background videos cap decode at 30 fps via `setMaxVideoFrameRate(30)` (`UI/player/CanvasArtworkPlayer.kt:97`, `PlayerV2Canvas.kt:417`).
- Respect the system animator scale: effects read `currentCoroutineContext()[MotionDurationScale]` and divide phase speed by it or stop at 0 (`AnimatedChromeMesh.kt:60, 80-90`; `ArtworkMeshBackground.kt:149-171`). `convx-android-motion/SKILL.md` repeats the rule.
- Looping progress/skeleton shimmer: 1400 ms linear period, band = half the width, highlight = on-surface-variant at 16% alpha composited over the base (`Skeletons.kt:40-80`).
- Other timers: playing indicator polls at 50 ms (`UI/component/PlayingIndicator.kt:60`); lyric line index at 100 ms; seek position 20 Hz (comment at `Player_v2.kt:1582`). Convx does not use `Choreographer` directly (0 matches).

## 7. High refresh rate and frame pacing

- One effect keyed on a user preference `enableHighRefreshRate` (`A/AppStartupEffects.kt:67-105`): on API 30+ it sets `window.attributes.preferredDisplayModeId` to the highest-refresh mode whose physical width/height equal the current mode ("so refresh rate changes do not reflow the UI"); `0` means no preference. When the toggle is off it picks the mode closest to 60 Hz. Below API 30 it sets `preferredRefreshRate` to the max of `supportedRefreshRates` (or 60).
- Not used: `Surface.setFrameRate`, `Window.setFrameRate`, the frame-rate category APIs (`FRAME_RATE_CATEGORY_*`), `View.setRequestedFrameRate`. Therefore on devices with LTPO/VRR panels Convx never tells the system "this content only needs 30 fps" or "this can idle", it only picks a display mode. For Rovsti, add `Modifier`-level or window-level frame-rate hints only after verifying API availability (grep in the Android SDK, do not invent).
- Frame budget awareness: authors reason against 8.33 ms (120 Hz) (`HomeSectionOrder.kt:61-65`, `Player_v2.kt:1582`, `IosSheetPresentation.kt:74-75`). The 120 fps task acceptance criteria require a verified 120 Hz mode, scripted scenarios and p50/p90/p99 + jank count via Macrobenchmark/Perfetto (`automation/tasks/005-perf-120fps-pass.md:17-30`), and a process lesson that Android Studio device mirroring alone swung numbers more than any fix (`automation/progress/005-perf-120fps-pass.md`, "next round" item 1). They never ran it because the device was busy, so no pacing numbers exist in the repo.
- Transition clocks line up: nav push tween is 260 ms, freeze windows are 300-330 ms ("plus frame-delivery slop") (`NavTransitionFreeze.kt:15-17`, `BackdropFreeze.kt:16-20`); tests pin the token values (`app/src/test/.../MotionTokensTest.kt`).

## 8. Threading and coroutine model

- Application scope: a Hilt `@ApplicationScope CoroutineScope` injected into `App` (`A/App.kt:69-71`, `A/di/AppModule.kt:38-39`). Startup fan-out is `applicationScope.launch(Dispatchers.IO)`: preference mirror warm-up (`App.kt:132`), native engine init, and five long-lived `dataStore.data.map{}.distinctUntilChanged().collect{}` pipes that push settings into singletons (cookie, locale, IP version, Apple-art flag, Last.fm) (`App.kt:134-270`).
- Auth seed bridge: values mirrored into MMKV so the first read is synchronous and cannot block on DataStore ("MusicService can start playback first; a null cookie would trigger guest fallback", `App.kt:111-125, 162-170`). `MMKV.initialize` + SharedPreferences->MMKV migrations run on the main thread inside `Application.onCreate`, the only notable synchronous startup work. A secondary-process guard returns early for the `:crash` process (`App.kt:79-90`).
- DataStore snapshot mirror: a process-wide `AtomicReference<Preferences?>` fed by a collector on `Dispatchers.IO`, started once (`preferenceMirrorStarted` CAS). `cachedPreferenceSnapshot()` never blocks; a private `snapshot()` falls back to `runBlocking(Dispatchers.IO) { data.first() }` only if nothing is cached yet (`A/utils/DataStore.kt:25-68`). `rememberPreference` uses the non-blocking one so composition never waits for disk (`:83-120`).
- ViewModels use `stateIn(viewModelScope, SharingStarted.Lazily | WhileSubscribed(5_000), initial)` (for example `viewmodels/RadioViewModel.kt:33`, `LibraryViewModels.kt:177-305`, `StatsViewModel.kt:76-160`). Sorting and filtering moved off Main ("Artist/list sorting moved off the main thread; per-item database flows are remembered", `changelog.json:78-80`). Room's own executors run DAO flows.
- Pure computation off Main inside effects: `withContext(Dispatchers.Default)` for palettes and mesh rendering (section 6); `Dispatchers.IO` for decode+analysis (section 4). 391 `Dispatchers.IO`, 47 `Dispatchers.Default`, 89 `Dispatchers.Main` matches across the tree.
- Throttled main-thread posts: ringtone download progress dispatches to Main at most once per percent instead of per 8 KB read (`docs/CODEBASE_REVIEW_2026-10.md:142`).
- Debug guard rails: `StrictMode` thread policy (disk reads/writes, network, custom slow calls, log) + VM policy (leaked closables, activity leaks) only when `BuildConfig.DEBUG` (`App.kt:93-112`). Release logging: `-assumenosideeffects` strips `Log.v/d/isLoggable` in R8 (`app/proguard-rules.pro:"Logging"`).
- Known smell: `MusicService` creates `CoroutineScope(Dispatchers.Main) + SupervisorJob()` and the crossfade ticker runs `tick()` on Main every 250 ms idle / 40 ms arm / 30 ms fade / 15 ms bail (`automation/progress/005-perf-120fps-pass.md:114-155`). Avoid: run tickers on Default and publish coarse state.
- Cancellation hygiene seen in the motion skill: "Never start unmanaged animation coroutines from a composable" (`contributor/skills/convx-android-motion/SKILL.md`). Interruptible press animation: cancel the previous job, `animateTo` from the current value (`ModifierExt.kt:150-165`).

## 9. Startup, baseline profile, R8 and release build

- `app/src/main/baseline-prof.txt` (13 lines) is a wildcard profile: mark every method in `com.convx.music.**` and `com.music.innertube.**` hot-startup-compiled (`HSPL...->**(**)**` plus class lines). Header (lines 1-9): the APK shipped only library profiles, so app code ran interpreted and JIT-compiled through launch and first scrolls (traced on one phone: `status=verify`, JIT thread busy 4.3 s of the first 4 s). Sideloaded installs have no Play cloud profile, so `profileinstaller` (arrives transitively via Compose) installs it and dexopt compiles it. The header itself says: replace with a Macrobenchmark-generated profile (launch + Home scroll + open player) if odex size or install-time dexopt matters.
  Cost: a wildcard grows the odex and install-time dexopt time; benefit for them is eliminating first-minute jank on sideload. Rovsti ships sideloaded too, so the argument transfers, but we should write targeted rules.
- No `baselineprofile` Gradle plugin, no `:benchmark`/macrobenchmark module, no Startup Profile, no `androidx.startup`/`ProfileVerifier` (grep: 0 matches for roborazzi/paparazzi/macrobenchmark/baselineprofile/profileinstaller/leakcanary across `*.kts`, `*.toml`, `*.kt`, `*.yml`).
- Splash: `androidx.core:core-splashscreen` is a dependency (`app/build.gradle.kts:` `libs.androidx.core.splashscreen`).
- R8: release `isMinifyEnabled = true`, `isShrinkResources = true`, `isCrunchPngs = false`, `proguard-android-optimize.txt` (`app/build.gradle.kts:138-152`). `dependenciesInfo.includeInApk = false` (187-190). `packaging.jniLibs.useLegacyPackaging = false` (uncompressed native libs memory-mapped from the APK) and a few `keepDebugSymbols` (210-217). ABI splits via product flavors (arm64, arm32, x86_64, universal) rather than only AAB splits (68-109).
- Keep rules (`app/proguard-rules.pro`): serialization boilerplate, WebView JS interfaces, `-keep class io.ktor.** { *; }`, `-keep class kotlin.reflect.** { *; }`, `-keep class com.convx.music.playback.queues.** { *; }`, `-keep class com.convx.music.cast.** { *; }`, `-keep class ai.onnxruntime.** { *; }`, `-keep class com.atilika.kuromoji.** { *; }`. These are broad (whole-package `{ *; }`), so shrinking and optimisation gains are partly lost. Pitfall: keep exactly what reflection or JNI needs, not packages.
- Release stripping of verbose logging via `-assumenosideeffects` on `android.util.Log` (v, d, isLoggable) while Timber trees are planted in debug only (`App.kt:146-147`).
- Release signing material only from `local.properties`/env (`app/build.gradle.kts:111-136`), never from a literal.
- CI builds a debug APK plus lint on every PR (`.github/workflows/build_pr.yml`). No benchmark, no test run in that workflow.

## 10. Profiling and measurement artifacts

- `docs/profiling/convx_prof.sh`: an on-device `sh` script that loops for 57 minutes (`END=$(date +%s)+3420`) driving the UI with `input tap/swipe` through Home tabs, opening playlists, search and play, Now-Playing controls, lyrics, Settings, and a long "just listening" window. Per block it does `dumpsys gfxinfo <pkg> reset` before and writes `dumpsys gfxinfo <pkg>` after, a `top -H -b -n 1` thread snapshot, network delta from `/proc/net/dev`, and battery level/temperature per cycle, aborts below 15% battery, and heartbeats to `/sdcard/convx_prof`. Output is meant to be pulled and compared by hand. It shows the intended measurement vocabulary: per-screen blocks, gfxinfo janky-frame percentage, per-thread CPU, battery, bytes over the network.
- Task contract (`automation/tasks/005-perf-120fps-pass.md`): fixed scripted interaction every round, frame-time p50/p90/p99 + jank count, 120 Hz confirmation, pixel-equivalent screenshot diff on key screens, "Report cites the actual trace numbers, not a vibe assessment".
- Process lessons recorded in the progress doc (lines 255-300): kill Android Studio device mirroring first; verify the panel is in a 120 Hz mode, not merely capable; baseline on a clean tree; keep the baseline APK for A/B; check charging state before and after; change one thing per trace pair, because F1 and F2 touch the same screens and would confound each other.
- In-app debug tool: `Modifier.recomposeHighlighter()` draws a border that grows blue to green to yellow to red as recomposition counts rise inside a 3 s quiet window; it keeps its counter in a plain array so it does not itself recompose (`A/utils/ComposeDebugUtils.kt:28-80`). It has no call sites in the tree (grep), so it is a manual debugging aid.
- Compose compiler metrics flag exists (section 2.1) but the reports are not committed.
- Per-cost evidence available in comments: layer.record 47 ms/frame without freeze vs 1.9 ms with (`BackdropFreeze.kt:21-24`); glass effect-lambda fix 36.8 to 24.4 ms p50 (task 005 context); scale 0.5 to 0.35 gave "fewer janky frames" (`GlassPool.kt:38-41`).

## 11. Testing approach

- JVM unit tests only: about 52-68 files (`docs/CODEBASE_REVIEW_2026-10.md:32, 216`), JUnit4, `unitTests.isReturnDefaultValues = true` instead of Robolectric ("Robolectric would be heavier than these token tests warrant", `app/build.gradle.kts:167-172`). One instrumented test (`app/src/androidTest/.../NetworkSecurityInstrumentation.kt`).
- UI-adjacent logic is deliberately extracted into pure functions/objects so it can be tested without Compose or a device: motion tokens (`MotionTokensTest` pins durations, easings, stiffness numbers so "a retune lands everywhere"), fling projection, overscroll bounce, sheet motion edges, glass tint tokens, glass resolution scale, `heroBandOn`, `lyricCenterDistance`, alphabet scroll index math, waveform seek bar, thumbnail resize/fallback, home section ordering, singer badge names (`app/src/test/kotlin/com/convx/music/ui/**`). Examples of pure seams in main code: `shouldUseTranslucentGlassFallback(style, supported)`, `glassResolutionScale(blurDp)`, `isGlassSupported(sdkInt)` (`GlassEffect.kt:139-170`).
- Initialisation-order regression test using an isolated ClassLoader (`ScreensInitializationTest`).
- No Compose UI tests (`createComposeRule` 0 matches), no screenshot tests (no Roborazzi/Paparazzi), no golden images, no Macrobenchmark, no baseline-profile generation. The full-suite status in the review doc: 262 tests, 3 failing (`GlassTintTokensTest` x2, `BotDetectionMitigatorTest`) (`CODEBASE_REVIEW_2026-10.md:82`). `GlassTintTokensTest` failing means even the colour policy tests were not gating merges.
- Visual verification at Convx is by eye on a device ("Motion was reviewed on an emulator or device", `convx-android-motion/SKILL.md` checklist) and by the automation loop's device screenshot diff.
- Consequence for Rovsti: we cannot copy a screenshot-testing setup from Convx. We must design our own (section 13.9), keeping the Convx idea of pure, pinned, testable motion/perf tokens.

## 12. What not to copy (summary of Convx weak spots)

1. Wildcard baseline profile, no benchmarks, no way to prove the profile helps.
2. Over-broad ProGuard keeps (`io.ktor.**`, `kotlin.reflect.**`, whole app sub-packages).
3. Plain `List<Pair<..>>` arguments built inline per recomposition (`rememberHeroSource`); `GlassEffectConfig` with 25 fields copied per composition.
4. Main-thread tickers in services; `String.format` building log strings in hot paths.
5. `Modifier.composed {}` remnants; zero-valued effect lambdas (`graphicsLayer { alpha = 1f }`).
6. Glass everywhere as a design assumption (needs pooled capture, freeze clocks, region clipping, low-RAM fallback and a user "reduce blur" setting just to stay smooth). Rovsti should use real glass only on a few chrome surfaces.
7. Metrics flag using an object in a string path; reliance on hand-run on-device scripts.
8. State polled by timers: the 120 ms `appMotionFrozen` poll and 48 ms thaw poll are workarounds for plain-lambda freeze clocks that cannot invalidate. A cleaner design drives the freeze with an explicit state/`snapshotFlow` on scroll-in-progress and asks the draw node to invalidate on thaw.

## 13. Recipe for Rovsti (how to build the equivalent in our own Compose code)

Scope: four apps (Phone, Messages, Contacts, Stops), Kotlin + Compose, domain logic in Rust crates reached via JNI/UniFFI-style bindings, WhatsApp via our gateway, Stops with a FULL-SCREEN map and floating controls. No device available, so verification is Gradle builds, JVM/Robolectric tests, Compose UI tests and screenshot tests. All code below is to be written fresh; pseudo-code describes behaviour only.

### 13.1 Gradle and module layout

- Version catalog with one Kotlin version, the Kotlin Compose compiler plugin at that version, and either the Compose BOM or one `compose` version ref. Choose the newest stable versions available when we set up (verify on Maven at that moment; Convx's 2.3.10 / 1.10.2 is only a reference point for what is known to work together with strong skipping).
- Strong skipping: leave the default (on), and do not add flags. Add a `compose_stability.conf` for types that come from modules without the Compose plugin, especially the Kotlin classes generated from Rust (UniFFI/JNI data classes for contacts, messages, stops, geo). Rule of thumb for what goes in: immutable `val` data classes only; if a generated type has `var` or mutable collections, wrap it in a small `@Immutable` UI model in the Kotlin layer instead.
- Module split (each with its own tests, no giant `:app`): `:ui-core` (tokens, motion, glass-lite, modifiers, text field), `:ui-feedback` (popover, context menu, sheet host), `:feature-phone`, `:feature-messages`, `:feature-contacts`, `:feature-stops` (map), `:data-rust` (JNI bridge adapter). This fixes Convx's "app module is the architecture" finding (`CODEBASE_REVIEW_2026-10.md:18-22`). Apply the Compose plugin in every UI module so no model silently becomes unstable.
- Compiler reports: add a Gradle property that sets `reportsDestination`/`metricsDestination` using `layout.buildDirectory.dir("compose_reports")`, and a CI task that fails if a listed hot composable (rows, popover content, map overlay controls) is reported non-skippable or non-restartable. This gives us a measurable proxy for recomposition on a machine with no device.
- Keep `gradle.properties` for dev machines normal (daemon on, parallel on, build cache on, configuration cache on). Use the low-memory profile only on the agent via `-D` overrides, and keep 6 GB free (`rm -rf` build dirs between large steps).

### 13.2 State and recomposition rules (write these as our own skill rules)

1. Per-frame values are never function parameters of a composable. Pass `State<T>` or `() -> T` and read inside `graphicsLayer {}`, `drawBehind`, `drawWithContent`, `Modifier.offset { }`, `Modifier.layout`, `Canvas {}`. Scroll, drag, zoom, sheet progress, press scale, popover scale, seek position, shimmer.
2. Composition may only react to coarse derived booleans or small enums: `derivedStateOf { progress > 0.01f }`, `isScrolling`, `isOpen`.
3. Hoist per-row dependencies (prefs, theme extras, selection set, download/unread map) out of the row into one `@Immutable` object or one composition local; rows get an id plus a stable immutable UI model.
4. Lists: `key = { stable id }`, `contentType = { rowKind }` always; no inline `map`/`filter` inside item lambdas or composable arguments; precompute in the ViewModel/Rust layer and pass `@Immutable` models. For `List<T>` in state classes, use either `@Immutable` wrappers or `kotlinx.collections.immutable`; do not rely on identity of a fresh list.
5. Functions that return a value from `@Composable` (`rememberX(): Float`) are a trap: return `State<T>` instead.
6. `remember(keys)` anything that is allocated per composition and handed to a child: lambdas that capture unstable values, `ImageRequest`/tile requests, shapes, brushes, painters.
7. Prefer `Modifier.Node` (`ModifierNodeElement`) over `composed {}`. Write press-scale, shimmer, parallax and "draw-phase alpha" modifiers as nodes that own an `Animatable`/state and read it in `draw()` or the layer block. Deliver a custom `IndicationNodeFactory` for press feedback: a spring scale to about 0.94-0.97 on press, spring back on release, interruptible, no recomposition (matches the Convx press-feedback technique in our own code; tune numbers ourselves).
8. Never write state during composition; side effects in `LaunchedEffect(key)`/`DisposableEffect`; `rememberUpdatedState` for callbacks captured by long-lived effects.
9. Text fields (owner complaint): use `BasicTextField2`/`TextFieldState` style state-based text input (current foundation API; verify exact name at the pinned version) so selection, caret, handles, copy/paste toolbar and IME geometry are the platform's. Keep text state in a `TextFieldState` that lives in the screen holder, never rebuilt per recomposition; format/validate in `inputTransformation`/`outputTransformation`; never put the text in a `mutableStateOf<String>` that is re-created with `remember { }` on each keystroke.

### 13.3 List performance

- `LazyColumn` rows: fixed or predictable heights where possible; one composable per row kind; one `contentType` per kind; avoid `Modifier.animateItem()` on huge lists unless needed (reorder only), because it adds per-item layout work.
- Conversation lists (Messages) and call logs (Phone): the avatar stack that overlapped in the GPUI UI becomes one composable `AvatarStack(models)` with a fixed overlap offset and fixed width, preceded by a pure function that decides how many avatars + "+N" fit. Test that function with JVM tests.
- No per-row blur or shadow (blur only on top chrome). A big source pill per row is a layout, not a perf, issue, but remove it for the draw cost too: one label per row, group by source in headers.
- Bottom padding: apply `contentPadding = PaddingValues(bottom = barHeight + insets)` on the lazy list and read `barHeight` from a measured `Modifier.onSizeChanged` into a plain remembered holder, so the last row is never covered and no extra recomposition occurs.
- Sections: collapse long lists into paged chunks; cap simultaneously composed rails (Convx's Home cap is a reminder that "rows composed at once" dominates frame cost).
- Scroll-driven chrome (large title to inline title, scrim, divider): `derivedStateOf` over `firstVisibleItemIndex/Offset` returned as `State<Float>`, read in the chrome's `graphicsLayer`. Test the pure mapping function (scroll offset to progress) on the JVM.
- Fast scroll/alphabet rails (Contacts): `scrollToItem`, not `animateScrollToItem`, while scrubbing; compute header offset inside lambdas.
- Prefetch: start with defaults. Only consider `LazyLayoutCacheWindow` after profiling (verify availability at the pinned Compose version; do not guess its API).

### 13.4 Images, avatars and map tiles

- Avatars/contact photos: one image loader (Coil 3 or an equivalent) configured once at app start. Memory cache about 25% of the app's memory class, 15% on low-RAM; disk cache in `filesDir` for avatar sets the user would miss offline; hardware bitmaps on; crossfade off for list rows (a crossfade in a row means an animation per row per scroll); size always = `dp.roundToPx()` of the actual slot; request built in `remember(url, px)`; `diskCacheKey` includes the size; `AsyncImage` with a painter placeholder, not subcomposition.
- Contact initials fallback and deterministic colours: pure Kotlin or Rust function; draw initials with `drawWithCache` (Paint/TextLayout cached), not with a `Text` composable per row, to cut node count.
- Stops full-screen map: this is our own design (Convx has no map). The tile layer is the performance-critical piece: a custom `Canvas`/`DrawModifier` that draws visible tiles from an in-memory bitmap LRU; pan/zoom/rotate are held in a `State` (or a `GraphicsLayer` transform for pure pan) read in the draw phase only, so a pinch does not recompose anything; tile fetch and decode on `Dispatchers.IO`/`Default` with a bounded queue, cancelling requests for tiles that left the viewport; draw coarse (parent zoom) tiles scaled while fine tiles load; per-frame budget: never decode on Main. All floating controls (search, layer chooser, locate, bottom stop sheet) are siblings above the map in a `Box`, and only the map surface is invalidated by gestures. Controls observe coarse state only (selected stop, sheet detent), never the camera.
- Marker/cluster overlay: compute clusters off-main when the camera settles (`snapshotFlow` + `debounce`/`conflate`), not per frame; draw markers in the same canvas pass.
- Memory hygiene: `ComponentCallbacks2.onTrimMemory` clears image and tile memory caches at RUNNING_LOW and below.

### 13.5 Glass, blur, popovers and sheets (performance policy)

- Decision: real backdrop blur/refraction on at most a small number of chrome elements (tab bar, floating map controls, sheet grabber area, top bar once scrolled). Everything else is a flat translucent surface with a hairline. The map and lists are the content; glass over a full-screen map that pans every frame is the worst case, so for Stops prefer a tinted translucent surface with a cheap blur only on the bottom sheet and only when the map is idle.
- If we implement backdrop blur: depend on the upstream Apache-2.0 `io.github.kyant0:backdrop` and/or Haze (after re-checking licence and API at the pinned version), do not copy Convx's vendored variants. Use the same cost levers (all re-derived from first principles, they are general GPU facts): capture at 1/3 to 1/2 resolution when the radius is large; share one captured+blurred layer across all surfaces; restrict re-record to the region covered; gate on API 31/33 and `isLowRamDevice`; provide a "reduce transparency/blur" setting; fall back to a solid tint.
- Freeze clock design for Rovsti (own version, fix Convx's polling smell): a small `class MotionGate` with three inputs — scroll-in-progress (from a `NestedScrollConnection` on the main scroll container), navigation/transition running (from the nav host), gesture-active (sheet/popover drag). It exposes `val frozen: State<Boolean>` backed by a snapshot state that changes only on edges (start/stop), plus a short tail (about 100-150 ms) implemented with a delayed state write in a coroutine, so there is no per-frame state write and no polling loop. Glass draw nodes observe `frozen` and, when it flips to false, call `invalidateDraw()` themselves. All ambient animations (mesh, shimmer, glow) also take `frozen`.
- Popovers and context menus (owner complaint: none existed; tapping pushed content): host them in a top-level overlay layer (a `Popup` or a dedicated `Box` at the root) so opening never changes layout of the content. Content scale/alpha/offset animate through a node reading a single `Animatable` in `graphicsLayer {}`; origin = the trigger's anchor corner; open spring about 0.35-0.45 s duration with low bounce; close shorter and non-bouncy; dismiss on outside tap with a scrim whose alpha is a layer property, not a composition value. Measure the anchor with `onGloballyPositioned` once and store it in a plain holder; do not recompose the trigger when the popover opens. Test the placement function (anchor rect + screen rect + content size to final rect and origin) on the JVM.
- Sheets: progress as `() -> Float` shared by the sheet and the content behind it (scale/dim in a layer block), as in the Convx `progressProvider` overload idea; sheet content is lazily composed only when the detent is not "hidden".
- Buttons that "flowed into content" (owner complaint): never animate size/padding of a sibling with `animateContentSize` on the content column. Animate `graphicsLayer` scale/alpha/translation of the control only, or reserve its final space; reading animation values in a layout lambda is fine, in composition is not.

### 13.6 Ambient animation

- Only when visible + resumed + `!frozen` + animator scale > 0 + not reduce-motion. A single `withFrameNanos` driver per effect; cap at 30 fps for ambient visuals; clamp delta time; keep the phase in a state read only in the draw phase. For backgrounds that are expensive to compute (mesh), render to a small bitmap on `Dispatchers.Default`, double-buffer it, draw it scaled in a `Canvas`.
- Skeleton shimmer: one shared `InfiniteTransition` per screen exposed as `State<Float>`/provider to all skeleton blocks, each reading it in `drawWithCache`; stop it as soon as real content arrives.
- Marquee text only when visible, using a visibility holder written from `onGloballyPositioned` (own implementation).

### 13.7 High refresh rate and frame pacing

- Request the highest mode at the current resolution on API 30+ via `preferredDisplayModeId`, with `preferredRefreshRate` below that, and expose a user toggle (default on) as Convx does. Do not change resolution (avoid reflow).
- Additionally (our improvement): where the SDK we compile against exposes frame-rate hints for a window/surface (verify in the SDK sources; do not invent signatures), give static screens a lower category/rate and animating ones a higher one, so VRR panels idle. Re-check the exact API names at the pinned compileSdk.
- Keep per-frame main-thread work below roughly 4 ms of composition+layout and 4 ms of draw to leave headroom on 120 Hz (8.33 ms budget); use trace sections (`androidx.tracing`/`Trace.beginSection`) around custom nodes so Perfetto traces name them.

### 13.8 Startup, profiles and R8

- Add the `androidx.profileinstaller` dependency explicitly. Start with a small hand-written rules file (class and method rules for the app's `MainActivity`, Application, theme, the first screen's composables, `LazyColumn` row composables, popover host, the map view) rather than a package wildcard. Generate a real profile later with a Baseline Profile Gradle plugin + Macrobenchmark when a device or managed emulator is available; document the command in the repo (`docs/profiling`). Until then keep the wildcard fallback only for `:feature-*` UI packages and delete it once a generated profile exists.
- Startup: `Application.onCreate` does only what must happen before the first frame: logging in debug, a non-blocking preference snapshot read, Rust bridge library load. Everything else after first frame: `applicationScope.launch(Dispatchers.Default)` or `androidx.startup` initialisers. A prefs snapshot mirror like Convx's (settings readable synchronously after a one-time warmup, writes async) fits Rovsti, but keep it small and typed (a settings holder object), not a global map.
- R8 on release with `isShrinkResources`; keep rules minimal: JNI native method owners, serialization, the exact reflection targets. Add a CI step that runs `assembleRelease` and fails on R8 warnings introduced by new keep rules. Strip `Log.v/d` via `-assumenosideeffects`.
- StrictMode (thread: disk, network, custom slow calls; VM: leaked closables) in debug only. Add `penaltyLog` in debug and `penaltyDeath` in a dedicated "strict" variant used by instrumented tests when available.

### 13.9 Threading model

- Rust calls are blocking JNI: never on Main. A single `Dispatcher` wrapper (`Dispatchers.IO.limitedParallelism(n)`) behind a repository interface per crate; results exposed as `Flow` or `suspend` and converted to `@Immutable` UI models on `Default` before reaching the UI.
- ViewModels expose `StateFlow<UiState>` built with `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`; UI collects with `collectAsStateWithLifecycle`.
- Tickers (call duration, message "sending" states, stop ETAs): drive from one shared flow per screen, `flowOn(Default)`, `conflate`, emit only on displayed-value change (once per second for a clock), never on Main loops.
- Long-lived collectors in app scope (WhatsApp connection state) must use `distinctUntilChanged()` and expose a coarse state; the UI reads that state only.
- Add a debug-only assertion helper `assertNotMain()` to Rust bridge calls so accidental main-thread JNI fails tests/StrictMode.

### 13.10 Testing and screenshot approach (no device)

- Layers: (1) pure JVM tests for every numeric policy (scroll-to-progress mappings, popover placement, avatar-stack fit, tile visibility math, glass resolution scale, motion tokens pinned like `MotionTokensTest`); (2) Compose UI tests with `createComposeRule` under Robolectric for semantics, focus, IME actions and recomposition counts; (3) screenshot tests with Roborazzi or Paparazzi whose PNGs we open and judge; (4) Gradle compile checks plus Compose compiler report assertions.
- Recomposition-count tests: wrap a composable in a test-only counter (a `SideEffect` incrementing an `AtomicInteger`), drive state changes (scroll, press, popover open) in a Robolectric Compose test, and assert the counts stay at 1-2 for rows and static chrome. This replaces Convx's visual `recomposeHighlighter` for a headless environment. Our own implementation; do not copy theirs.
- Screenshot caveats: JVM renderers do not execute `RenderEffect`/`RuntimeShader` the way a GPU does, so blurred/refractive glass will not look right in Paparazzi/Roborazzi. Test glass surfaces with the fallback (flat translucent) style in screenshots, and test the glass-vs-fallback decision as pure logic. Verify what the chosen renderer supports before relying on it.
- Screenshot sets: each screen at light/dark, small/large font scale, RTL, with an empty/short/long list, with the soft keyboard open (Messages), with a popover open, with a sheet at each detent, with Stops map replaced by a deterministic fake tile layer. Name PNGs `<screen>-<state>-<theme>.png` and keep the golden folder reviewed by opening images (as the task requires).
- Performance regression gates without a device: (a) compiler-report check for skippability of named composables; (b) a JVM test that builds the lazy item key/contentType functions and asserts uniqueness; (c) a build-time check that ambient-animation composables take a `frozen`/`paused` parameter (lint or a convention test); (d) APK size and method count tracked in CI via `assembleRelease` + `bundletool`/size report.
- When a device becomes available: add a `:benchmark` Macrobenchmark module (startup cold/warm, scroll each list, open popover, pan map) and a Baseline Profile generator that uses the same scripted journeys; record frame-time p50/p90/p99 and janky-frame counts, and check the display is truly in the high-refresh mode before trusting numbers. Keep the discipline recorded in Convx's progress doc: one change per trace pair, clean tree, fixed script, A/B the previous APK.

### 13.11 A short checklist the Rovsti UI skill can embed

1. No per-frame value reaches composition; read it in a layer/draw/layout lambda.
2. Every lazy item has `key` and `contentType`; no allocation of collections inside items.
3. Every image request is sized from the actual slot in pixels and `remember`ed.
4. Overlays (popover, menu, sheet, toast) live in a top-level layer and cannot move the content under them.
5. Anything animating forever consults `frozen && visible && resumed && animatorScale > 0 && !reduceMotion`.
6. Backdrop blur is optional, pooled, low-resolution, region-limited and has a flat fallback.
7. No main-thread JNI, no `runBlocking` on Main, no `String.format` in hot paths, no logging without a guard.
8. Every new custom modifier is a `Modifier.Node`, not `composed`.
9. Every numeric policy has a JVM test; every screen has screenshot states; compiler reports show hot composables as skippable.
10. Profile rules and R8 keeps are minimal, documented, and checked in CI.

## 14. Index of files read (for reproducibility)

Build/config: `gradle.properties`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/compose_stability.conf`, `app/proguard-rules.pro`, `app/src/main/baseline-prof.txt`, `hig/build.gradle.kts`, `spine/build.gradle.kts`, `.github/workflows/build_pr.yml`.
Docs/notes: `docs/CODEBASE_REVIEW_2026-10.md`, `docs/profiling/convx_prof.sh`, `automation/tasks/005-perf-120fps-pass.md`, `automation/progress/005-perf-120fps-pass.md`, `automation/loop-structure.md`, `changelog.json`, `.github/release-notes/2.0.0-nightly.md`, `contributor/skills/convx-android-motion/SKILL.md`, `contributor/skills/android-performance-skill/SKILL.md` (generic), `contributor/skills/improve-animations/AUDIT.md` (generic), `contributor/skills/android-compose-ui/SKILL.md` (generic).
Code (all under `app/src/main/kotlin/com/convx/music/`): `App.kt`, `AppStartupEffects.kt`, `AppBackdropState.kt`, `utils/DataStore.kt`, `utils/ComposeDebugUtils.kt`, `ui/component/{ItemPrefs,ItemThumbnails,SongItems,PlaylistItems,SeededHeroChrome,HeroArtwork,ChromeScrim,FrostedTopBar,AlphabetScrollBar,Skeletons,IosSheetPresentation,GlassEffect,AnimatedChromeMesh,ArtworkMeshBackground,BottomSheet,Lyrics,LyricsFollowScroll}.kt`, `ui/component/backdrop/{GlassPool,DrawBackdropModifier,Platform,RuntimeShaderCache}.kt`, `ui/component/backdrop/backdrops/{BackdropFreeze,NavTransitionFreeze,BackdropStaticRegion,LayerBackdropModifier}.kt`, `ui/component/floatingtabbar/FloatingTabBar.kt`, `ui/player/{MiniPlayer,Player_v2,PlayerArtworkMorph,MeshGradientBackground,Thumbnail}.kt`, `ui/screens/{HomeScreen,HomeShelf,HomeSectionOrder,HomeFeedSections,MainTabsPager}.kt`, `ui/screens/library/LibraryMixScreen.kt`, `ui/theme/ArtworkTint.kt`, `ui/utils/{ModifierExt,MotionModifiers}.kt`, selected `viewmodels/*`.
Tests skimmed: `app/src/test/kotlin/com/convx/music/ui/utils/MotionTokensTest.kt`, `ui/screens/ScreensInitializationTest.kt`, and the file list of `app/src/test` and `app/src/androidTest`.
