Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang. Full licence text: `glisse-LICENSE.txt` next to this file.

# Navigation and layout: structure, insets, tab bar, controls, lists, text fields, haptics, Stops map

> **Licence.** Convx navigation/chrome code (`AppNavHost.kt`, `FoldingTabBar.kt`, `FoldGeometry.kt`, `FloatingNavBar.kt`,
> `ScreenTitleBar.kt`, ...) is GPL-3.0: techniques only, code here is `ORIGINAL`. Its `FloatingTabBar.kt` is a modified
> copy of Apache-2.0 compose-floating-tab-bar (`.../floatingtabbar/FloatingTabBar.kt:1-24`): use the upstream if wanted,
> not Convx's copy. Tab bar / segmented / switch / slider / input geometry and gesture rules are `PORTED FROM GLISSE` (MIT)
> and keep the header.

Prefixes: Convx `A/` = `app/src/main/kotlin/com/convx/music/`, `C/` = `A/ui/component/`, `FT/` = `C/floatingtabbar/`,
`I/` = `A/ui/ios/`, `U/` = `A/ui/utils/`; glisse `G/` = `src/`. Detail: `_bmad-output/kotlin-ui/research/convx-navigation.md`.

---

## 1. Root structure (every app)

```kotlin
// ORIGINAL. MainActivity: enableEdgeToEdge() before setContent; manifest windowSoftInputMode="adjustResize",
// android:enableOnBackInvokedCallback="true".
@Composable
fun RovstiRoot(app: AppSpec) {
    val overlay = remember { OverlayController() }
    val chrome = remember { ChromeMetrics() }                     // measured bar heights (section 2)
    val glass = rememberGlassSource()
    RovstiTheme {
        OverlayHost(overlay) {
            Box(Modifier.fillMaxSize().background(RovstiTheme.colors.page)) {
                CompositionLocalProvider(LocalContentInsets provides chrome.contentInsets()) {
                    // 1. content layer: full window, scrolls under chrome
                    AppNavHost(app, Modifier.fillMaxSize().glassSource(glass, RovstiTheme.colors.page))
                }
                // 2. chrome layer: siblings drawn after the content (never inside the glass source)
                RovstiTabBar(app.tabs, glass, Modifier.align(Alignment.BottomCenter).onSizeChanged { chrome.bottomBarPx = it.height })
            }
        }
    }
}
```

Layers are always: content (list or map, full window) -> chrome (bars, floating controls) -> overlay host. Opaque page
background under the NavHost so the incoming screen never shows through (`A/AppRootContent.kt:889-890`).

## 2. Insets: the last row is never covered

Convx builds one inset value that already includes tab bar and mini player heights and screens spend it as list
`contentPadding` (`A/AppRootContent.kt:524-551`), but from constants (80/64/84 dp) that drift from the real bar
(`A/constants/Dimensions.kt:21-28`). Rovsti measures.

```kotlin
// ORIGINAL
@Stable
class ChromeMetrics {
    var bottomBarPx by mutableIntStateOf(0)      // written by onSizeChanged: changes only when the bar resizes
    var topBarPx by mutableIntStateOf(0)
    @Composable
    fun contentInsets(): PaddingValues {
        val d = LocalDensity.current
        val sys = WindowInsets.safeDrawing.asPaddingValues()
        return PaddingValues(
            start = sys.calculateStartPadding(LocalLayoutDirection.current),
            end = sys.calculateEndPadding(LocalLayoutDirection.current),
            top = with(d) { maxOf(sys.calculateTopPadding(), topBarPx.toDp()) },
            bottom = with(d) { bottomBarPx.toDp() } + 8.dp + sys.calculateBottomPadding(),
        )
    }
}
val LocalContentInsets = compositionLocalOf { PaddingValues() }

// Every scrolling screen:
LazyColumn(contentPadding = LocalContentInsets.current, modifier = Modifier.fillMaxSize()) { ... }
```

Rules: insets are `contentPadding`, never outer padding (rows must scroll *under* translucent chrome); the bar's own bottom
margin = system bottom inset + 8 dp; when the bar minimises or hides, the inset follows the bar's animated height in the
layout phase; keyboard: `imePadding()` only on what docks to it (search pill, Messages composer), hide the tab bar while the
IME is up instead of pushing it (`C/FloatingNavBar.kt:559-565`); landscape: add `displayCutout` start/end. A bottom
progressive scrim under the bar (transparent -> page colour, stops 0/.35/.6/.85/1 with alpha 0/.15/.4/.7/1) keeps icons
legible without a hard bar (`A/AppRootContent.kt:1124-1141`).

Test (Compose): scroll a 50-row list to the end and assert the last row's bottom <= bar top (`onNodeWithTag("row-49").assertTopPositionInRootIsEqualTo`/bounds check).

## 3. Navigation

- One `NavHost` per app, typed routes (`@Serializable` destinations, navigation-compose 2.8+), a `sealed interface Route`.
  Chrome visibility is a pure function: `fun chromeFor(route: Route): ChromeSpec` (Convx's string-set policy object idea,
  `A/AppRoutePolicy.kt:1-88`, minus the strings). JVM-test it.
- Tab switching in exactly one helper: `navigate(tab) { popUpTo(graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }`
  (Convx has two code paths with different restore semantics, `A/AppNavigation.kt:7-17` vs `A/AppRootContent.kt:784-800`: do not).
- Reselecting the current tab scrolls to top via a per-tab `SharedFlow` event (not a `SavedStateHandle` flag that can stick, `A/AppRootContent.kt:789-791`).
- "Animate first, navigate after" for morphs that start in chrome (search field growing out of the bar), `A/AppSearchController.kt:138-151`;
  wait for the animation's end signal rather than a fixed delay.

```kotlin
// ORIGINAL: push/pop with depth parallax (U/utils/Motion.kt:157-179; A/AppNavHost.kt:55-98). Tweens, so predictive back can scrub.
NavHost(
    navController, startDestination = start, modifier = modifier,
    enterTransition = { slideInHorizontally(RovstiMotion.push()) { it } },
    exitTransition = {
        slideOutHorizontally(RovstiMotion.push()) { -(it * RovstiMotion.PushParallax).toInt() } +
            fadeOut(RovstiMotion.push(), targetAlpha = RovstiMotion.PushDimAlpha)
    },
    popEnterTransition = {
        slideInHorizontally(RovstiMotion.push()) { -(it * RovstiMotion.PushParallax).toInt() } +
            fadeIn(RovstiMotion.push(), initialAlpha = RovstiMotion.PushDimAlpha)
    },
    popExitTransition = { slideOutHorizontally(RovstiMotion.push()) { it } },
) { /* graph */ }
```

Tab-to-tab changes are a ~200 ms crossfade, not a push (decide inside the lambdas from `initialState`/`targetState` routes).
Detail pages that grow out of a tile use shared bounds (motion.md 8) and the covered screen holds still and dims to 0.7
(`A/AppNavHost.kt:73-75`).

## 4. Floating tab bar

### 4.1 Geometry (PORTED FROM GLISSE: `G/styles.css:233-239,358-377,512-535`)

| | Tab bar, labels only | Tab bar, icon + label | Segmented control |
|---|---|---|---|
| cell w x h | 84 x 32 dp | 72 x 52 dp | 84 x 32 dp |
| bar padding | 4 dp | 4 dp | 0 |
| thumb inset | 0 | 0 | 2 dp |
| outer radius | h/2 + padding | h/2 + padding | h/2 |
| lens grow when lifted (x, y) | 27 x 20 dp | 24 x 16 dp | 24 x 16 dp |
| label | 14 sp | 10 sp medium | 13 sp (selected medium) |
| icon | | 26 dp, gap 1 dp | |
| selected colour | foreground | `tokens.fill` | segment label |

Rovsti uses the icon + label variant (bar 60 dp tall), centred, max width 500 dp, 16 dp from the sides, 8 dp above the system
inset (Convx `AppRootContent.kt:1170-1180`). At font scale >= 1.5 drop labels and keep icons (do not clip). Cells >= 48 dp
tall for touch (`FT/FloatingTabBarBars.kt:116,334`).

### 4.2 Puck behaviour (PORTED FROM GLISSE `G/internal/segments.tsx:55-339`, plus Convx feel)

- Two springs: slide and lift; REST (1024/58.9, 676/47.8) after taps, DRAG (2500/94, 576/44.2) while a finger is down (retune
  per phase, velocity kept). Lifted lens scale `1 + growX/width * lift`, `1 + growY/height * lift`.
- Drag starts after `|dx| > 6 dp`; position = `clamp(anchor + dx, 0, slot*(n-1))`; on up the target is the slot under the
  projected rest point (glisse rounds the release point; we project, motion.md 5.2); a drag selects even if released off the
  bar; a tap counts only if released within 24 dp vertically of the bar.
- Light-page staging (`UNDER = 0.5`): the grey rest selection fades during the first half of the lift, then the lens drops
  *under* the labels and its fill grows from zero, so the selected label never changes colour at once (`segments.tsx:161-172`,
  `G/styles.css:484-489`). Dark page: lens lies over the labels.
- Draw labels once; the lifted lens is the AGSL lens (`glass.md` 3.2, `TabLens`) over the puck rect; the "selected colour inside
  the selection" is a clip of a second coloured label draw (glisse's `tint` row). No stacked DOM-style copies.
- Convx additions (our own code, motion.md 5.2): velocity squash/stretch clamped to 20%, whole bar tugs up to 4 dp toward the
  drag and relaxes with spring(1, 300) (`FT/FloatingTabBarTabs.kt:155-163,205`), press bulge deflates only after the puck
  arrives (<= 400 ms), external selection (navigation) only moves the puck, user taps navigate explicitly (no double navigation).
- Long-press a tab: `onLongClick(boundsInWindow)` -> context menu anchored to that rect (`C/FloatingNavBar.kt:293-298`).
- Semantics: `selectableGroup()` on the bar, `Role.Tab` + `selected` on cells; hidden layer copies `clearAndSetSemantics {}`
  (`FT/FoldingTabBar.kt:138,153,186,200`).

### 4.3 Hold rule (PORTED FROM GLISSE `G/internal/hold.ts:1-9`)

A touch that starts on a control belongs to the page (scrolls) until it has rested 150 ms or a drag is under way, like
`UIScrollView.delaysContentTouches`. Use it in tab bar, switch and slider detectors: before `held`, vertical movement past
touch slop returns the gesture to the scrollable parent (do not consume); after `held`, consume.

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
const val HOLD_MS = 150L
fun held(downAtMs: Long, nowMs: Long, moved: Boolean): Boolean = moved || nowMs - downAtMs >= HOLD_MS
```

### 4.4 Minimise on scroll (ORIGINAL; behaviour from `FT/FloatingTabBar.kt:388-522`)

Convx ships "expanded only at the top"; iOS 26 minimises on scroll down and expands on scroll up. Rovsti: minimise after
~40 dp of accumulated downward scroll, expand after ~8 dp upward or at the top; reset on direction flip; never consume; write
state only on the edge.

```kotlin
// ORIGINAL
class TabBarMinimizer(private val collapsePx: Float, private val expandPx: Float) : NestedScrollConnection {
    var minimized by mutableStateOf(false); private set
    private var accumulated = 0f
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val dy = consumed.y                                   // < 0: content moved up (user scrolls down)
        if (dy == 0f) { if (available.y > 0.5f && minimized) minimized = false; return Offset.Zero }   // pulling at the top
        if (sign(dy) != sign(accumulated)) accumulated = 0f
        accumulated += dy
        if (!minimized && accumulated < -collapsePx) { minimized = true; accumulated = 0f }
        if (minimized && accumulated > expandPx) { minimized = false; accumulated = 0f }
        return Offset.Zero
    }
}
```

Minimised look: translate 40 dp down + 35% fade with `barMinimize` (`I/component/IosTabBar.kt:121-125`), or the full Convx
fold (pill shrinks to the selected-tab circle, search detaches to a circle) driven by **one** progress value with every
position a pure function of it (`FT/FoldingTabBar.kt:45-93`, `FT/FoldGeometry.kt:25-59`; write the geometry yourself). Labels
vanish in the first ~1/3 of the fold so they never cross-fade (`FoldingTabBar.kt:122-137`). Tapping the folded selected tab
expands. Stops defaults to the folded bar so the map gets the screen.

## 5. Controls

### 5.1 Switch (PORTED FROM GLISSE `G/switch.tsx:24-179`, `G/styles.css:653-768`)

Geometry: track 60 x 25.1 dp; thumb drawn at rest scale 0.65 (about 35.6 x 22.4 dp), lifted 0.65 + 0.25*lift; travel 21.71 dp;
hit area at least 44 dp tall and 8 dp wider on each side; disabled alpha 0.35; on colour `tokens.on`. Three springs:
position and colour `Toggle` (1000/80, over-damped), lift `ThumbLift` (2000/80). Face opacity `1 - 0.85*lift`: a held thumb is
clear glass showing the stand-in track drawn smaller (`width (W-24)*(.85+.1*lift)`, `height (H-12)*.75*(.525+.45*lift)`), bent by
the `SwitchThumb` lens.

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
object SwitchRules {
    const val TRAVEL_DP = 57.9f * 0.375f
    /** Position 0..1 under the finger, with a rubber band of 1/22 beyond either end. */
    fun dragPosition(from: Float, dxPx: Float, travelPx: Float): Float {
        val value = from + dxPx / travelPx
        val overshoot = if (value < 0f) value else if (value > 1f) value - 1f else 0f
        return value.coerceIn(0f, 1f) + overshoot / 22f
    }
    /** A quick vertical swipe is a scroll; once held, the switch keeps it. */
    fun isScroll(dxDp: Float, dyDp: Float, held: Boolean) = !held && kotlin.math.abs(dyDp) > 10f && kotlin.math.abs(dyDp) > kotlin.math.abs(dxDp) + 4f
    fun startsDrag(dxDp: Float) = kotlin.math.abs(dxDp) >= 4f
    /** Release: a drag lands where it was let go; a tap toggles only if released within 24 dp vertically. */
    fun onRelease(checked: Boolean, moved: Boolean, value: Float, nearVertically: Boolean): Boolean =
        if (moved) value > 0.5f else if (nearVertically) !checked else checked
    fun colourTarget(value: Float) = if (value > 0.5f) 1f else 0f
}
```

Compose: `Modifier.toggleable(value, role = Role.Switch, onValueChange = ...)` for semantics and keyboard, custom
`pointerInput` for the drag (consume only after `held` or `startsDrag`). Add `RovstiHaptics.toggle(on)` on commit (Convx
has no toggle haptic; iOS does). Convx's improvement worth keeping: release velocity decides when the drag is fast
(`DragAxis` projection on `value * travel`).

### 5.2 Slider (PORTED FROM GLISSE `G/slider.tsx`, `G/styles.css:770-903`)

40 dp tall; rail 6 dp at 21 dp from the top, inset 16 dp, radius 3; fill width `(W - 36dp)*progress + 18dp` (ends under the
thumb centre); thumb 60 x 40 dp drawn at 0.6 scale at rest (36 x 24), `0.6 + 0.4*lift` held; thumb x `(W - 36dp)*progress - 12dp`;
touch target 36 x 40 dp minimum; lift `ThumbLift`; magnified stand-in rail 8 dp * `(.6 + .4*lift)` inside the lens
(`SliderThumb`); near the ends the stand-in fill gives way so an empty slider shows no fill through the thumb. Semantics:
`Modifier.semantics { progressBarRangeInfo = ...; setProgress { ... } }` + `Modifier.progressSemantics`. Volume-like sliders may
add Convx's elastic overflow (drag past the end soft-capped by a sigmoid at 28 dp, released with spring(0.5, 1500),
`C/ElasticVolumeSlider.kt:62,138-149,246-259`; write it yourself).

### 5.3 Segmented control

Geometry from 4.1 ("measured from UIKit: a 32px capsule, 13px labels, thumb inset 2px; held, the thumb fades and the lens lifts
in its place, 24px wider and 16px taller", `G/styles.css:505-511`), same puck engine as the tab bar, `SegmentLens`,
`Role.RadioButton` + `selectableGroup`. Track `tokens.segmentTrack`, thumb `tokens.segmentThumb`.

### 5.4 Buttons (glisse `G/button.tsx`, `G/styles.css:132-227`)

Glass (default) and filled (`tokens.fill`, white label, pressed `fill` mixed 15% toward black). Height 40 dp (44 dp in
Rovsti for touch), padding 0/16 (12 with icon), radius = half height, gap 8, label 15-17 sp, icon 20 dp, icon-only = circle
(needs `contentDescription`), disabled alpha 0.35, focus ring 2 dp `tokens.ring` offset 2 dp. Press = `glassPress` (motion.md 7).
Pressed fill switches to `glassActive` instantly and fades back over 200 ms. Never ripple.

## 6. Lists, rows, chips, sections, spacing (owner complaints)

Spacing tokens (one set, used everywhere): gutter 16 dp, gaps 4 / 8 / 12 / 16 / 24, text gap 2 dp, section gap 24 dp.
Convx's discipline is "the only gutter" + three gaps (`A/ui/theme/AppleTokens.kt:40-47`); copy the discipline.

| Element | Rule | Evidence |
|---|---|---|
| Row | `heightIn(min = 56.dp)` (64 with two lines), leading avatar 40-44 dp, gutter 16, title 16-17 sp + one secondary line 13-15 sp, trailing meta (time/chevron/one icon) | Convx rows 58 dp, art 48, title 15 sp (`A/constants/Dimensions.kt:34-38`, `C/SongItems.kt:100-215`) |
| Avatars | **one** avatar per row; group chats show one composite or the group initial, never an overlapping stack | owner complaint; `convx-navigation.md` 12.7 |
| Source (WhatsApp vs local) | shown **once**: section header, a small 14 dp glyph after the name, or a filter chip. No pill per row | Convx rows: one primary + one secondary line, no per-row pill (`convx-navigation.md` 8.1) |
| Filter chips | single non-wrapping `LazyRow`, 8 dp gaps, 16 dp content padding, selected = filled pill animated with `appear` | `convx-navigation.md` 12.7 |
| Row press | flat fill (system fill) while the finger is down, cleared on lift, drawn behind content; no ripple, no scale | `I/foundation/IosPressable.kt:19-53` |
| Grouped sections (settings) | inset-grouped: first/last rows round outer corners (radius 26 dp in Convx; use 20 dp), separators inset to the text start and hidden after the last row, sentence-case headers, 0.33-0.5 dp separators | `I/component/IosList.kt:88-297`; `I/foundation/IosTheme.kt:135-150` |
| Swipe actions | motion.md 5.3; 76 dp per action | `I/component/IosSwipeActions.kt:49` |
| Alphabet rail (Contacts) | `scrollToItem` (not animated) while scrubbing; read layout info in lambdas | `C/AlphabetScrollBar.kt:316-345` |
| Lazy hygiene | `key` + `contentType` on every item; immutable row models; no allocation in item lambdas | `performance.md` 2 |

## 7. Text fields and search (owner complaint: worse than an HTML input)

Use the platform text stack, do not reimplement it. Convx does nothing special here (`A/ui/ios/compat/IosCompatFields.kt:109-130`),
so this section is Rovsti's own.

- `BasicTextField(state = TextFieldState)` (foundation 1.7+): caret placement by tap, long-press word selection, handles,
  magnifier, platform cut/copy/paste/select-all toolbar, IME composition, autofill all come for free. The state lives in the
  screen's state holder (`rememberTextFieldState()` or ViewModel), never recreated per keystroke. Validation/formatting via
  `inputTransformation` / `outputTransformation`.
- Manifest `adjustResize`, `enableEdgeToEdge()`, `imePadding()` on the docked container; `KeyboardOptions(imeAction = ImeAction.Search/Send, capitalization = None for names/numbers)`;
  `onKeyboardAction` handles Search/Done/Go and hardware Enter (some OEM keyboards do not report the action, `C/FloatingNavBar.kt:600-633`).
- Selection colours: `LocalTextSelectionColors provides TextSelectionColors(handleColor = accent, backgroundColor = accent.copy(alpha = 0.3f))`; `cursorBrush = SolidColor(accent)`.
- Phone dialer: the keypad is custom, but the number is a real text field so paste works.

Glass input / search field (PORTED FROM GLISSE `G/input.tsx`, `G/search-field.tsx`, `G/styles.css:571-651`; sizes adapted for touch):

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
@Composable
fun GlassTextField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    search: Boolean = false,
    imeAction: ImeAction = if (search) ImeAction.Search else ImeAction.Done,
    onSubmit: () -> Unit = {},
) {
    val tokens = RovstiTheme.glass
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill = animateColorAsState(if (focused) tokens.glassActive else tokens.glass, tween(200), label = "fieldFill")
    val focus = remember { FocusRequester() }
    BasicTextField(
        state = state,
        modifier = modifier
            .heightIn(min = 44.dp)                                   // glisse 40 (web); 44 for touch
            .widthIn(max = 360.dp)
            .drawBehind { drawRoundRect(fill.value, cornerRadius = CornerRadius(size.height / 2f)) }   // read in draw
            .focusRequester(focus),
        textStyle = RovstiTheme.type.body.copy(color = tokens.foreground),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        onKeyboardAction = { onSubmit() },
        interactionSource = interaction,
        cursorBrush = SolidColor(tokens.fill),
        decorator = TextFieldDecorator { inner ->
            Row(
                Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides tokens.muted) { if (leading != null) leading() else if (search) SearchGlyph() }
                Box(Modifier.weight(1f)) {
                    if (state.text.isEmpty()) Text(placeholder, style = RovstiTheme.type.body, color = tokens.muted)
                    inner()
                }
                if (search && state.text.isNotEmpty()) {
                    // 32 dp clear button; never takes focus from the field, clears and keeps the caret.
                    Box(
                        Modifier.size(32.dp).offset(x = 8.dp).focusProperties { canFocus = false }
                            .clickable(onClickLabel = "Clear") { state.clearText() },
                        contentAlignment = Alignment.Center,
                    ) { ClearGlyph() }
                }
            }
        },
    )
}
```

(`LocalContentColor`/`Text` here are Rovsti's own design-system equivalents, not Material.) Search glyph: circle r 4.75 at
(7,7) + line to (13.75,13.75) on a 16 grid, stroke 1.5 round; clear glyph: two 7-unit diagonals, stroke 1.5
(`G/search-field.tsx:7-20`, `G/input.tsx:44-60`). Tapping anywhere on the pill focuses the field (`G/input.tsx:38-40`):
`clickable(indication = null, interactionSource = null) { focus.requestFocus() }` on the container.

Search flow (Convx `C/NavSearchState.kt:23-49`, `A/AppSearchController.kt`): hoist `SearchState(query: TextFieldState,
visualActive, keyboardActive, focusRequester)` at app level; open = bar morph, then navigate, then focus when the morph ends
(Convx waits 260 ms, `C/FloatingNavBar.kt:115`); back with keyboard open = hide keyboard and stay in search; back again exits.

Verification (Compose tests + a manual device checklist for the owner): tap places the caret (`performTouchInput { click(offset) }` then assert selection),
long-press selects a word, `performTextInputSelection`, paste via `performTextInput`, IME action triggers submit, field stays
above the keyboard (`imePadding` present), clear keeps focus, font scale 2.0 does not clip.

## 8. Haptics (ORIGINAL)

Convx under-uses haptics (long press only, no tab/toggle/sheet ticks, `convx-navigation.md` 9.2) and drives the vibrator
directly because `View.performHapticFeedback` is silenced on Samsung when "touch interactions" is off (`U/theme/AppHaptics.kt:19-54`).

```kotlin
// ORIGINAL. Constants: CLOCK_TICK (21), TEXT_HANDLE_MOVE (27), CONFIRM/REJECT/GESTURE_START/GESTURE_END (30),
// SEGMENT_TICK/TOGGLE_ON/TOGGLE_OFF (34). VibrationEffect.createPredefined (29) needs the VIBRATE permission.
class RovstiHaptics(private val view: View, private val vibrator: Vibrator?, private val mode: () -> HapticMode) {
    enum class HapticMode { Off, System, Strong }
    fun tick() = play(HapticFeedbackConstants.CLOCK_TICK, VibrationEffect.EFFECT_TICK)
    fun selection() = play(HapticFeedbackConstants.SEGMENT_TICK, VibrationEffect.EFFECT_TICK)
    fun toggle(on: Boolean) = play(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF, VibrationEffect.EFFECT_TICK)
    fun textHandle() = play(HapticFeedbackConstants.TEXT_HANDLE_MOVE, VibrationEffect.EFFECT_TICK)
    fun longPress() = play(HapticFeedbackConstants.LONG_PRESS, VibrationEffect.EFFECT_HEAVY_CLICK)
    fun confirm() = play(HapticFeedbackConstants.CONFIRM, VibrationEffect.EFFECT_CLICK)
    fun reject() = play(HapticFeedbackConstants.REJECT, VibrationEffect.EFFECT_DOUBLE_CLICK)
    private fun play(constant: Int, effect: Int) {
        when (mode()) {
            HapticMode.Off -> Unit
            HapticMode.System -> view.performHapticFeedback(constant)
            HapticMode.Strong -> vibrator?.vibrate(VibrationEffect.createPredefined(effect))
        }
    }
}
```

Fire on: tab reselect (tick), puck lands on a new tab (selection), sheet snaps to a detent (tick), long-press menu opens
(longPress), slide over a new menu row (textHandle), toggle commit (toggle), swipe row crosses full-swipe (tick), destructive
confirm (confirm), invalid action (reject). Never on scroll or continuously during a drag. One app preference: Off / System
(default) / Strong. Compose's `LocalHapticFeedback` gained matching types around 1.7-1.8 (`Confirm`, `Reject`, `SegmentTick`,
`ToggleOn`, ...); verify at the pinned version if you prefer it (it goes through `performHapticFeedback`, so the Samsung problem above remains).

## 9. Stops: full-screen map, everything floats

The owner rejected "a small map box". Layout (ORIGINAL; Convx has no map, rules from `convx-glass.md` 11.6, `convx-motion.md` 12.3 i):

```kotlin
// ORIGINAL
Box(Modifier.fillMaxSize()) {
    MapLayer(camera, Modifier.fillMaxSize().glassSource(glass, page))   // edge to edge, under status and nav bars
    // floating chrome, all siblings over the map
    SearchPill(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
    FilterChipsRow(Modifier.align(Alignment.TopStart).padding(top = searchBottom + 8.dp))  // one LazyRow, never wraps
    Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)                       // locate, layers, compass
        .graphicsLayer { translationY = -min(sheet.visibleHeightPx(), maxRidePx) }) { GlassIconButton(...) }
    StopSheetEntry(...)                         // overlay host entry: peek ~120 dp / medium / large, background interaction on
    RovstiTabBar(folded = true, ...)            // folded to a circle by default on Stops
}
```

- Map camera padding = the glass-covered area (top chrome, sheet's **settled** detent, bar) so the user dot, selected stop and
  attribution stay visible; update on settle, not per frame.
- Floating buttons ride the sheet via `graphicsLayer` translation (no relayout) up to a limit.
- Gestures on the map never recompose chrome: camera in a holder read in draw (`performance.md` 4).
- Glass over a panning map: Frost tier while panning, Liquid when idle (`glass.md` 5.6); if the map is a `SurfaceView`, Flat.
- Tiles come from the user-chosen tile server (default `tiles.versatiles.org`) through the Rust `rovsti_tiles` cache; the UI
  never fetches tiles itself.

## 10. Large titles

Convx: the large title is the first list item that scrolls away; a compact bar overlay fades in by the collapse fraction
(`C/ScreenTitleBar.kt:45-173`); the iOS-layer variant lifts the large title 12 dp while the inline title fades in, smoothed by
spring(1, 500) (`I/component/IosNavigationBar.kt:47-121`). Large title 34 sp bold, tracking -0.6 to -0.8 sp, max 2 lines;
inline title 17 sp semibold in a 52 dp bar under the status bar; top edge = progressive blur (bar + 64 dp run) + page-colour
scrim peaking at 0.42 (`C/TopFadeBlur.kt:49,61-64`), or solid bar + 0.5 dp divider (after collapse) in reduce-transparency.

```kotlin
// ORIGINAL. Title is item 0 with key "title". Read the result only in graphicsLayer lambdas.
@Composable
fun LazyListState.rememberTitleCollapse(titleHeightPx: () -> Float): State<Float> = remember(this) {
    derivedStateOf {
        if (firstVisibleItemIndex > 0) 1f
        else (firstVisibleItemScrollOffset / titleHeightPx().coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}
// Inline bar:  Modifier.graphicsLayer { alpha = collapse.value }
// Large title: Modifier.graphicsLayer { alpha = 1f - collapse.value; translationY = -12.dp.toPx() * collapse.value }
```

Toolbar buttons: 44 dp circular glass buttons grouped in capsules; a spacer between two buttons splits one capsule into two
(`I/component/IosNavigationBar.kt:123-147`). Pushed pages: circular back button at the leading edge.

## 11. Accessibility and dynamic type

- Text in `sp` only; rows `heightIn(min)` never fixed heights (Convx fixed 58/48/68 dp clip at large scales,
  `convx-navigation.md` 11); tab bar drops labels at >= 1.5x.
- Every interactive element >= 48 dp touch target; tabs `Role.Tab` + `selected` + `stateDescription`; rows merge avatar, name,
  preview and time into one node (`Modifier.semantics(mergeDescendants = true)`) with custom actions for row actions.
- Reduce motion (motion.md 2.5) and reduce transparency (glass.md 6) honoured everywhere.
- Tests: semantics assertions, font scale 2.0 screenshots, `composeRule.enableAccessibilityChecks()` where available (verify
  the API at the pinned ui-test version).
