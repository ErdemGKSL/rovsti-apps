# Performance: recomposition, frame time, lists, images, tiles, ambient motion, threading

> **Licence.** Convx (GPL-3.0) is described, not copied. All code here is `ORIGINAL`.
> Evidence limits: Convx has one git commit, no committed benchmarks, no screenshot or Compose UI tests; numbers quoted
> from its comments are the authors' own device readings (orders of magnitude, unreproducible here)
> (`_bmad-output/kotlin-ui/research/convx-performance.md` 0, "Evidence limits").

Prefix: Convx `A/` = `app/src/main/kotlin/com/convx/music/`, `UI/` = `A/ui/`.

Budget: 120 Hz = 8.33 ms per frame. Aim for <= ~4 ms composition + layout and <= ~4 ms draw on the main thread, with the
RenderThread (blur, lens) under ~3 ms for chrome while scrolling. Convx reasons against 8.33 ms in comments
(`UI/screens/HomeSectionOrder.kt:61-65`: a comment records the Home content alone costing about 14 ms per frame against that budget).

---

## 1. The deferred-read rule (the single most important technique)

Every per-frame value travels as a `State<T>` holder or `() -> T` lambda and is **read only** inside
`Modifier.graphicsLayer {}`, `drawBehind {}`, `drawWithContent {}`, `drawWithCache { onDrawBehind {} }`, `Canvas {}`,
`Modifier.offset { IntOffset }`, `Modifier.layout {}` / `placeWithLayer {}`. Composition reacts only to coarse booleans or
enums derived with `derivedStateOf`. Convx applies this to sheet scrim and translation (`UI/ios/component/IosSheet.kt:157-161,217`),
sheet pushback (`UI/component/IosSheetPresentation.kt:72-100`, (the stated goal is zero recomposition at 120 Hz)), hero scroll progress
returned as `State<Float>` (`UI/component/HeroArtwork.kt:342-372`), seek bar isolated in its own composable
(`UI/player/Player_v2.kt:1579-1590`), shimmer read in `drawWithCache` (`UI/component/Skeletons.kt:50-80`).

```kotlin
// ORIGINAL: wrong vs right
@Composable fun Scrim(alpha: Float) = Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))   // recomposes per frame
@Composable fun Scrim(alpha: () -> Float) = Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = alpha()) })  // draw only
```

Traps:
- A `@Composable` that **returns** a value (`rememberX(): Float`) is not restartable; its snapshot reads are charged to the
  caller, which then recomposes per frame. Return `State<Float>` or `() -> Float` (Convx finding F1,
  `automation/progress/005-perf-120fps-pass.md:35-80`).
- A snapshot read inside a draw lambda that changes every frame re-invalidates draw every frame. Freeze flags and scratch
  values written from layout (anchor rects, "opened above", scroll clocks) live in plain fields or arrays, not state
  (`UI/ios/component/AppleContextMenu.kt:94-100`, `UI/component/backdrop/backdrops/BackdropFreeze.kt:25-34`).
- Never write state during composition; side effects in `LaunchedEffect`/`DisposableEffect`; `rememberUpdatedState` for
  callbacks captured by long-lived effects.
- Use `derivedStateOf` to turn continuous values into thresholds (`pressed = progress > 0f`, `morphing = current != target`,
  `showDivider = alpha > 0.99f`) (`UI/component/FrostedTopBar.kt:105-130`).

## 2. Stability and skipping

- Strong skipping is the default from Kotlin 2.0.20; do not add flags. Apply the Compose compiler plugin in **every** UI
  module so no model silently turns unstable.
- Add `compose_stability.conf` for types from modules without the Compose plugin, especially Kotlin classes generated for the
  Rust bridge, but only for truly immutable `val` classes whose `equals` is a correct skip test (Convx: without it "every
  YouTube row/tile was unskippable", `app/build.gradle.kts:229-235`). Mutable generated types get a small `@Immutable` UI model.
- `List<T>` parameters: a fresh list instance defeats skipping even with strong skipping (identity compare for unstable types).
  Wrap in an `@Immutable` holder or use `kotlinx.collections.immutable`; build in the ViewModel, not inline
  (Convx anti-pattern: `list.map { ... }` at 12 call sites per recomposition, finding F2).
- `remember(keys)` anything allocated per composition and handed down: lambdas capturing unstable values, image requests,
  shapes, brushes, effect builders (Convx rebuilt six stand-in models per recomposition, `UI/screens/library/LibraryMixScreen.kt:211-243`;
  stable effect lambdas, `UI/component/GlassEffect.kt:374-420`).
- One `@Immutable` prefs/theme-extras object in a `staticCompositionLocalOf`, never a per-row preference read
  (`UI/component/ItemPrefs.kt:22-60`).
- Custom modifiers are `Modifier.Node` + `ModifierNodeElement`, never `composed {}` (Convx still had four `composed` uses,
  `UI/utils/ModifierExt.kt:118`). Press feedback is an `IndicationNodeFactory` or a node that owns its `Animatable`.
- Compiler reports: a Gradle property that sets `reportsDestination`/`metricsDestination` to
  `layout.buildDirectory.dir("compose_reports")` (Convx's version interpolates a `DirectoryProperty` into a string and is
  probably broken, root `build.gradle.kts:36-47`); a CI check that named hot composables (rows, overlay content, map chrome)
  are skippable and restartable.

## 3. Lists

- Every `item`/`items` has `key` (stable id) and `contentType` (row kind). One composable per row kind.
- No allocation, `map`, `filter`, `sortedBy` inside item lambdas or arguments; precompute in the ViewModel / Rust layer.
- No per-row blur, shadow, glass, marquee or infinite animation. If something must animate in a row, gate it on visibility
  (`boundsInWindow()` non-empty; Convx `UI/utils/MotionModifiers.kt:239-275`: a plain marquee keeps requesting frames forever
  for every row Compose keeps around, including prefetched ones).
- Avoid `Modifier.animateItem()` on huge lists unless reordering/inserting is visible; it adds per-item layout work.
- Scrubbing (alphabet rail): `scrollToItem`, never `animateScrollToItem` (each animated scroll would cancel the previous one,
  `UI/component/AlphabetScrollBar.kt:333-338`).
- Cap simultaneously composed heavy sections (Convx caps Home sections because each added ~12 composed rows,
  `UI/screens/HomeSectionOrder.kt:61-72`).
- Observe scroll with `snapshotFlow { state.isScrollInProgress }` in an effect, not by keying composition on offsets
  (`UI/player/Thumbnail.kt:360-395`).
- Tabs hosted in a pager: never animate across several pages (intermediate pages compose inside animation frames); jump to
  the neighbour in one frame, then animate one page (`UI/screens/MainTabsPager.kt:23-47`). Rovsti prefers NavHost + crossfade.

## 4. Images, avatars, map tiles

- One image loader configured at app start: memory cache ~25% (15% on `isLowRamDevice`), disk cache in `filesDir` for
  avatars, hardware bitmaps, **crossfade off for list rows** (a crossfade per row per scroll is an animation per row),
  `allowHardware(true)` (`A/App.kt:349-398`). `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` clears memory caches (`App.kt:400-407`).
- Decode to the slot: size = `dp.roundToPx()` of the actual slot, request built in `remember(url, px)`, disk key includes the
  size (a flat 544 px decode for a 48 dp row was "~10x the pixels the row can show", `UI/component/ItemThumbnails.kt:61-150`).
- `AsyncImage` with a painter placeholder, not `SubcomposeAsyncImage` (subcomposition per load, `ItemThumbnails.kt:63-90`).
- Guard degenerate sizes (Coil throws on size <= 0, measured crash, `UI/player/PlayerArtworkMorph.kt:200-206`).
- Initials avatars: colour from a pure function (Rust or Kotlin), text drawn with a cached `TextLayoutResult` in
  `drawWithCache`, not a `Text` per row.
- **Stops map tiles** (Rovsti's own design): one `Canvas`/draw node draws visible tiles from an in-memory bitmap LRU; camera
  (pan/zoom/rotation) in a holder read in the draw phase only, so a pinch recomposes nothing; fetch/decode through
  `rovsti_tiles` on IO/Default with a bounded queue that cancels tiles leaving the viewport; draw the parent zoom scaled while
  children load; never decode on Main. Markers/clusters computed off-main when the camera settles
  (`snapshotFlow { camera }.debounce(...)`), drawn in the same pass. Controls observe coarse state (selected stop, sheet detent),
  never the camera.

## 5. Freeze gate for expensive effects (ORIGINAL; fixes Convx's polling smell)

Convx freezes glass re-recording while content moves: scroll (900 ms safety), screen entry 300 ms, nav push 330 ms, pager
swipe, sheet drag, tab-bar morph; measured "47ms of layer.record per scroll frame with the freeze off, 1.9ms with it on"
(`UI/component/backdrop/backdrops/BackdropFreeze.kt:14-25`, `NavTransitionFreeze.kt:17-62`). Its plain-lambda clocks cannot
invalidate, so it polls (120 ms mirror, 48 ms thaw poll, `A/AppBackdropState.kt:79-102`, `DrawBackdropModifier.kt:631-632`).
Ours changes state only on edges:

```kotlin
// ORIGINAL
@Stable
class MotionGate(private val scope: CoroutineScope, private val tailMs: Long = 120) {
    private var scrolling = false; private var transitioning = false; private var gesturing = false
    private var tailJob: Job? = null
    var frozen by mutableStateOf(false); private set           // flips on edges only

    val scrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { set(scroll = true); return Offset.Zero }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { set(scroll = false); return Velocity.Zero }
    }
    fun transition(running: Boolean) = set(transition = running)
    fun gesture(active: Boolean) = set(gesture = active)

    private fun set(scroll: Boolean = scrolling, transition: Boolean = transitioning, gesture: Boolean = gesturing) {
        scrolling = scroll; transitioning = transition; gesturing = gesture
        val moving = scrolling || transitioning || gesturing
        tailJob?.cancel()
        if (moving) { if (!frozen) frozen = true }
        else tailJob = scope.launch { delay(tailMs); frozen = false }
    }
}
```

Plus a safety: if a scroll never delivers `onPostFling` (rare), also watch `LazyListState.isScrollInProgress` through
`snapshotFlow` and clear. Glass nodes, ambient animations (shimmer, glows), and map overlays take `frozen`. A draw node that
observes `frozen` (read in `draw()`) is invalidated exactly twice per gesture.

## 6. Ambient and continuous animation

Run only when all hold: visible (bounds in window non-empty), lifecycle RESUMED (`repeatOnLifecycle`), `!frozen`,
`MotionDurationScale > 0` (read `currentCoroutineContext()[MotionDurationScale]`), reduce motion off
(`UI/component/AnimatedChromeMesh.kt:50-100`). One `withFrameNanos` driver per effect, capped at 30 fps for ambient visuals,
delta clamped to 100 ms so a stall never jumps; the phase is a `mutableFloatStateOf` read in draw. Expensive backgrounds render
to a small bitmap on `Dispatchers.Default`, double-buffered (`UI/component/ArtworkMeshBackground.kt:118-175`). One shared
shimmer `InfiniteTransition` per screen, stopped when real content arrives (1400 ms linear, `UI/component/Skeletons.kt:40-80`).
Rovsti has no music: candidates are call-duration tickers, "connecting" pulses, skeletons. Tickers emit once per displayed
change (once per second for a clock), `flowOn(Default)`, `conflate()`.

## 7. Compositing details

- `CompositingStrategy.ModulateAlpha` for plain alpha fades (no offscreen buffer) (`UI/component/floatingtabbar/FloatingTabBarTabs.kt:325-355`);
  `Offscreen` only when required (masks, blend tricks).
- No arbitrary path clips on animating shapes (CPU coverage mask uploaded per frame); rounded-rect outlines are the hardware
  fast path; clip only while needed (`FloatingTabBarTabs.kt:300-303`, `FloatingTabBarMotion.kt:110-120`).
- Never set an effect to a zero value (`graphicsLayer { alpha = 1f }`, `BlurEffect(0f, 0f)`): use `null` / skip the modifier;
  identity effects still allocate a RenderNode pass (finding F4).
- Cache `RenderEffect`s keyed by integer px radius when they animate (`UI/component/floatingtabbar/GooeyTransition.kt:381-392`).
- `skipToLookaheadSize()` on children while a container animates its size, so content does not re-layout per frame
  (`FloatingTabBarTabs.kt:370`).

## 8. Frame rate

- `window.attributes.preferredDisplayModeId` (API 23+; minSdk 34 here) to the highest-refresh mode with the **same resolution** as the
  current mode (avoid reflow); user toggle to force 60 Hz (`A/AppStartupEffects.kt:67-105`).
- Convx never uses frame-rate hints. On the compileSdk we pin, check for window/view frame-rate category APIs (for example
  `View.setRequestedFrameRate` on API 35) in the SDK sources before use; give static screens a low category, animating ones high.
- Name custom nodes in traces (`androidx.tracing.trace("RovstiGlass") { }`) so Perfetto shows them.

## 9. Threading, JNI, startup, release

- Rust calls are blocking JNI: **never on Main**. One dispatcher (`Dispatchers.IO.limitedParallelism(n)`) behind a repository per
  crate; results as `Flow`/`suspend`, mapped to `@Immutable` UI models on `Default`. Debug-only `assertNotMain()` in bridge calls.
- ViewModels: `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`; UI collects with
  `collectAsStateWithLifecycle` (Convx: 531 vs 139 plain `collectAsState`).
- WhatsApp connection state (gateway via `rovsti_wa_client`): app-scope collector with `distinctUntilChanged()`, coarse UI state.
- Startup: `Application.onCreate` does only what the first frame needs (debug logging, non-blocking prefs snapshot, Rust library load);
  everything else after first frame. No `runBlocking` on Main (Convx keeps one guarded fallback, `A/utils/DataStore.kt:25-68`).
- StrictMode thread + VM policies in debug only (`A/App.kt:93-112`); strip `Log.v/d` in release with `-assumenosideeffects`.
- Baseline profile: add `androidx.profileinstaller` explicitly; start with hand-written rules for the first screen, row
  composables, overlay host and map node, not a package wildcard (Convx ships a wildcard because sideloaded installs have no cloud
  profile, `app/src/main/baseline-prof.txt:1-13`; the argument transfers, the wildcard does not). Generate a real profile with
  Macrobenchmark when a device or managed emulator exists.
- R8 with `isShrinkResources`; minimal keep rules (JNI owners, serialization, exact reflection targets), never `-keep class pkg.** { *; }`
  (Convx keeps `io.ktor.**`, `kotlin.reflect.**`, whole app packages, `app/proguard-rules.pro`).
- Agent machine: keep 6 GB free; remove `android/**/build` and unused Gradle caches before big builds; low-memory Gradle flags via
  `-D`/`-P` overrides only, not in the committed `gradle.properties` (Convx's committed profile disables daemon, parallel,
  caching: `gradle.properties:15-34`).

## 10. Performance tests without a device

1. **Pure JVM tests** for every numeric policy (projection, rubber band, placement, collapse fraction, tile visibility, glass
   tier, capture scale, motion tokens pinned like Convx's `MotionTokensTest`).
2. **Recomposition counts** (ORIGINAL helper; replaces Convx's visual `recomposeHighlighter`, `A/utils/ComposeDebugUtils.kt:28-80`):

```kotlin
// ORIGINAL (test source set)
class RecompositionCounter { val counts = mutableMapOf<String, Int>() }
@Composable
fun CountRecompositions(tag: String, counter: RecompositionCounter) {
    SideEffect { counter.counts[tag] = (counter.counts[tag] ?: 0) + 1 }
}
// In a Robolectric Compose test: drive a 1 s drag / scroll / popover open with mainClock, then
// assertThat(counter.counts["row-3"]).isAtMost(2); assertThat(counter.counts["tabBar"]).isAtMost(2)
```
3. **Compiler-report gate** (section 2) and a JVM test that item keys are unique and `contentType`s are set.
4. **Convention tests**: ambient-animation composables take a `frozen`/`paused` parameter; no `composed {` in UI sources
   (grep in a unit test).
5. **Screenshots** catch layout regressions, not jank: glass in the Flat tier, fixed animation frames via `mainClock.advanceTimeBy`.
6. When a device exists: Macrobenchmark (cold/warm start, scroll each list, open popover, pan map), frame p50/p90/p99 and jank
   count, verified 120 Hz mode, one change per trace pair, A/B against the previous APK (Convx's own process lessons,
   `automation/progress/005-perf-120fps-pass.md:255-300`).

## 11. Performance checklist (paste into reviews)

1. No per-frame value reaches composition; it is read in a layer/draw/layout lambda.
2. Every lazy item has `key` and `contentType`; no collection allocation inside items.
3. Every image/tile request is sized from the slot in px and remembered.
4. Overlays live in the root host and cannot move the content under them.
5. Anything animating forever checks `visible && resumed && !frozen && animatorScale > 0 && !reduceMotion`.
6. Glass is on chrome only, has a tier, a capture scale, and a Flat fallback.
7. No main-thread JNI, no `runBlocking` on Main, no logging or `String.format` in hot paths.
8. New custom modifiers are `Modifier.Node`; no `composed {}`.
9. Recomposition-count test for the screen's hottest interaction stays <= 2 per composable.
10. Keep rules and profile rules are minimal and documented.
