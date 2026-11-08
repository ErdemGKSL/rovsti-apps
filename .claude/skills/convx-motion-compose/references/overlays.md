Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang. Full licence text: `glisse-LICENSE.txt` next to this file.

# Overlays: host, context menu, popover, sheet, dialog, toast, text toolbar

> **Licence.** Rovsti is GPL-3.0-or-later: port Convx's overlay files (`ui/ios/IosContextMenu.kt`, `AppleContextMenu.kt`,
> `IosMenu.kt`, `IosSheet.kt`, `IosSheetPresentation.kt`, `OverlayMenu.kt`, `BottomSheetMenu.kt`, `FlingProjection.kt`
> under `app/src/main/kotlin/com/convx/music/ui/`) with the header from `SKILL.md` section 0. The button-anchored menu morph
> is `PORTED FROM GLISSE` (`src/menu.tsx`, MIT) and keeps that header. Convx's `hig/` module is an Apache-2.0 fork of
> Compose Cupertino; if a file is ported from it, keep its Apache header and list compose-hig in the notices.

Prefixes: Convx `U/` = `app/src/main/kotlin/com/convx/music/ui/`, `H/` = `hig/src/main/kotlin/zone/ien/hig/`;
glisse `G/` = `src/`. Detail: `_bmad-output/kotlin-ui/research/convx-overlays.md`, `glisse.md` 9.

---

## 1. Architecture: one in-window overlay host

Convx mixes `Popup` windows (context menus, dropdowns), `Dialog` windows (alerts, most sheets) and in-window sibling
boxes (`OverlayMenu`, `IosSheet`) (`U/ios/component/IosContextMenu.kt:170-173`, `U/ios/compat/IosCompatTail.kt:122-165`,
`U/component/OverlayMenu.kt:85-210`). It moved sheets in-window because a second window cannot sample the app's glass
backdrop (`U/ios/component/IosSheet.kt:107-117`), and its dialogs cannot animate out because the `Dialog` window is
destroyed on dismiss (`U/component/Dialog.kt:366-371`).

Rovsti: **every overlay is an entry in one `OverlayHost`**, the last child of the window-filling root `Box`.

Why: one back owner, one scrim policy, one z-order (list order), one place for modal accessibility, glass can sample the
same source, exit animations always play, and Roborazzi/Paparazzi capture it (separate windows may be missing from
screenshots: verify once on our setup). Keep a `Popup` escape hatch only if an IME-heavy case requires it.

```kotlin
// ORIGINAL
@Stable
class OverlayController {
    internal val entries = mutableStateListOf<OverlayEntry>()
    fun present(entry: OverlayEntry): OverlayEntry { entries += entry; entry.state.targetState = true; return entry }
    fun dismiss(entry: OverlayEntry) { entry.state.targetState = false }          // exit plays, then removal
    fun dismissTop(): Boolean = entries.lastOrNull { it.state.targetState }?.also(::dismiss) != null
    internal fun removeFinished() { entries.removeAll { !it.state.currentState && !it.state.targetState } }
}

abstract class OverlayEntry(val modal: Boolean, val title: String) {
    val state = MutableTransitionState(false)
    @Composable abstract fun Content(controller: OverlayController)
}

val LocalOverlay = staticCompositionLocalOf<OverlayController> { error("No OverlayHost") }

@Composable
fun OverlayHost(controller: OverlayController, content: @Composable () -> Unit) {
    val anyModal by remember { derivedStateOf { controller.entries.any { it.modal && it.state.targetState } } }
    CompositionLocalProvider(LocalOverlay provides controller) {
        Box(Modifier.fillMaxSize()) {
            // While a modal entry is up, the page is removed from the accessibility tree (Convx has no semantics at all, H/Accessibility.kt:25-35).
            Box(if (anyModal) Modifier.clearAndSetSemantics { } else Modifier) { content() }
            controller.entries.forEach { entry ->
                key(entry) {
                    Box(Modifier.fillMaxSize().semantics { paneTitle = entry.title; isTraversalGroup = true }) {
                        entry.Content(controller)
                    }
                }
            }
        }
        BackHandler(enabled = controller.entries.any { it.state.targetState }) { controller.dismissTop() }
        LaunchedEffect(controller) {
            snapshotFlow { controller.entries.map { it.state.currentState to it.state.targetState } }
                .collect { controller.removeFinished() }
        }
    }
}
```

Layout hygiene (owner complaint "tapping made buttons flow into the content"):
- A trigger keeps identical size and position while its overlay is open. Pressed state changes only draw-phase
  properties (`graphicsLayer` scale/alpha, overlay fill) never padding or size.
- Unit test: measure the trigger's bounds, `present()`, advance the clock, assert equal bounds.
- Anchor rects are captured once at present time into a plain value (`Rect`), never as state updated on scroll
  (Convx: state written on every layout recomposed every tile per scroll frame; fixed with a plain array,
  `U/ios/component/AppleContextMenu.kt:94-100`).
- The scrim eats input while open, so lists cannot scroll under a menu and anchors cannot go stale.

---

## 2. Placement (ORIGINAL pure function; JVM-tested)

Convx's simple menu flips below/above but clamps with a 12 dp margin only, ignoring insets (`IosContextMenu.kt:234-263`);
its dropdown covers its trigger and is pushed up (`U/ios/compat/IosCompatMenus.kt:164-190`); the Apple-style menu is
inset-aware (status bar + 8 dp, nav bar + 16 dp, `AppleContextMenu.kt:360-363`). glisse has no collision logic at all
(`G/styles.css:912-932`). Ours combines the good parts:

```kotlin
// ORIGINAL
package dev.rovsti.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

enum class Side { Below, Above }

data class Placement(val topLeft: Offset, val side: Side, val origin: Offset /* fraction 0..1 of the panel */)

fun placePanel(
    anchor: Rect, panel: Size, window: Size,
    safe: Rect,                 // window minus system bars/cutout/IME
    gap: Float, margin: Float,  // 8 dp, 12 dp in px
    rtl: Boolean = false,
): Placement {
    val left = safe.left + margin
    val right = safe.right - margin
    val top = safe.top + margin
    val bottom = safe.bottom - margin
    val below = anchor.bottom + gap
    val above = anchor.top - gap - panel.height
    val (y, side) = when {
        below + panel.height <= bottom -> below to Side.Below          // iOS pull-down: prefer below
        above >= top -> above to Side.Above
        else -> below.coerceIn(top, maxOf(top, bottom - panel.height)) to Side.Below   // too tall: clamp, panel scrolls
    }
    // Align to the anchor edge nearest the window edge (tall-thin menus), mirrored in RTL.
    val trailingHalf = (anchor.center.x > window.width / 2f) != rtl
    val x0 = if (trailingHalf) anchor.right - panel.width else anchor.left
    val x = x0.coerceIn(left, maxOf(left, right - panel.width))
    val origin = Offset(
        ((anchor.center.x - x) / panel.width).coerceIn(0f, 1f),
        ((anchor.center.y - y) / panel.height).coerceIn(0f, 1f),
    )
    return Placement(Offset(x, y), side, origin)
}
```

Panel max height = `safe.height - 2*margin` (Convx: window - 24 dp, `U/ios/component/IosMenu.kt:101-105`); content scrolls.
Tests: anchor in each corner and the centre, panel taller than free space, RTL, landscape, display cutout, IME open.

---

## 3. Long-press context menu (rows, avatars, stop cards, messages)

### 3.1 Gesture: resolve four outcomes (ORIGINAL)

Convx's Apple-style menu resolves, at the long-press timeout: finger lifted (tap), list claimed the gesture or finger
moved past slop (cancel), a child consumed it (child wins), or timeout with finger down (open). The check runs after
children saw the event (Main pass) so a "..." button inside a row keeps its tap; once open, the same finger keeps going and
events are consumed in the **Initial** pass so the lift does not fire a child click (`AppleContextMenu.kt:114-163`).

```kotlin
// ORIGINAL
class AnchorHolder { var rect: Rect = Rect.Zero }        // plain field, not state

private enum class Outcome { Tap, Cancel, ChildTook, Long }

fun Modifier.contextMenu(
    anchor: AnchorHolder,
    press: PressRamp,                                     // drives 0.96 scale + 0.14 black veil, 300 ms
    onTap: (() -> Unit)?,
    onOpen: (Rect) -> Unit,
    onPreviewDrag: (Offset) -> Unit,
    onPreviewRelease: () -> Unit,
): Modifier = this
    .onGloballyPositioned { anchor.rect = it.boundsInWindow() }
    .graphicsLayer { val p = press.value(); scaleX = 1f - 0.04f * p; scaleY = scaleX }
    .drawWithContent { drawContent(); val p = press.value(); if (p > 0f) drawRect(Color.Black.copy(alpha = 0.14f * p)) }
    .pointerInput(onTap, onOpen) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            press.start()
            val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull Outcome.Cancel
                    if (change.changedToUp()) return@withTimeoutOrNull if (change.isConsumed) Outcome.ChildTook else Outcome.Tap
                    if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop)
                        return@withTimeoutOrNull Outcome.Cancel
                }
                @Suppress("UNREACHABLE_CODE") Outcome.Cancel
            } ?: Outcome.Long
            when (outcome) {
                Outcome.Tap -> { press.release(); onTap?.invoke() }
                Outcome.Cancel, Outcome.ChildTook -> press.release()
                Outcome.Long -> {
                    onOpen(anchor.rect)                     // haptic LongPress happens in the menu entry on open
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) break
                        onPreviewDrag(change.position - down.position)
                    }
                    press.release()
                    onPreviewRelease()
                }
            }
        }
    }
    .semantics { onLongClick(label = "Show actions") { onOpen(anchor.rect); true } }
```

`AwaitPointerEventScope.withTimeoutOrNull`, `changedToUp`, `viewConfiguration.longPressTimeoutMillis/touchSlop`: compose-ui.
`press.start()` animates a 300 ms tween to 1; `release()` springs to 0 with (0.7, 600) (`AppleContextMenu.kt:116,132`).
Reorderable lists: if the reorder library starts a drag while the menu is open, call the entry's `cancel()`, which swaps in a
closed state with no landing animation (`AppleContextMenu.kt:197-203,239-243`; `AppleLibraryScreen.kt:380-430`).

### 3.2 Simple variant: lifted copy + panel (rows, chips)

Layers inside the entry, bottom to top (`IosContextMenu.kt:178-233`):

| Layer | Spec | Source |
|---|---|---|
| Scrim | black 0.28 dark / 0.14 light; in 180 ms, out 140 ms; tap dismisses; alpha read in `drawBehind` | `IosContextMenu.kt:155-160,276`; `AppleContextMenu.kt:279` |
| Lifted source | a **dedicated preview composable** (not a second composition of a stateful row; Convx's re-composition restarts row state, `IosContextMenu.kt:193-201`) placed at the anchor's window rect, scale 1.0 -> 1.05, 150 ms in / 120 ms out; the real row stays in layout at alpha 0 | `IosContextMenu.kt:162-168,277` |
| Panel | progress spring (0.78, 700) open / 150 ms `menuExit` close; alpha `clamp(p/0.35)`; per-axis scale from `anchorSize/panelSize` (min 0.08) to 1; `transformOrigin` = `Placement.origin`; blur 20 dp -> 0 while **opening only** (API 31 `RenderEffect`), plain fade-shrink on close ((a blur that clears while opening reads as glass condensing)) | `IosContextMenu.kt:145-150,204-226,271-275` |

```kotlin
// ORIGINAL: panel layer block (p = progress, gx/gy = per-axis grow springs, all Animatables read here only)
Modifier.graphicsLayer {
    val p = progress.value
    alpha = (p / 0.35f).coerceIn(0f, 1f)
    val sx0 = (anchor.width / size.width).coerceIn(0.08f, 1f)
    val sy0 = (anchor.height / size.height).coerceIn(0.08f, 1f)
    scaleX = sx0 + (1f - sx0) * growX.value
    scaleY = sy0 + (1f - sy0) * growY.value
    transformOrigin = TransformOrigin(placement.origin.x, placement.origin.y)
    val blurPx = if (opening) 20.dp.toPx() * (1f - p) else 0f
    renderEffect = if (blurPx >= 0.5f) BlurEffect(blurPx, blurPx) else null      // cache by rounded px if profiling asks
}
```

Measured target: panel fully up in about four 30 fps frames (~130 ms) (`IosContextMenu.kt:55-59`). Haptic
`LongPress` on open (`IosContextMenu.kt:88`).

### 3.3 Preview-card variant (contact, stop, message previews)

Timeline fitted to an iOS reference at 30 fps: press feedback frames 0-8, haptic + dim frame 9, card expansion 10-14,
menu expansion 12-15 (`AppleContextMenu.kt:72-78`).

- Card lift spring (0.80, 420) (slight overshoot); land (1.0, 520) (settles onto the item without bounce) (`:263-270`).
- Card scale from `itemWidth / cardInnerWidth` (min 0.05) to 1, origin top-left, translation from the item's top-left
  (minus card padding 10 dp) to its resting place, plus the finger drag weighted by progress, so at progress 0 the card's
  content sits exactly on the item (`:307-332`). Card corner 22 dp, fill white / `#2C2C2E`, faded in with progress.
- Land cross-fade: the item fades back in over the last 30% of the landing while the card fades out (texts differ, a
  one-frame swap would show) (`:213-228,408-409`).
- Panel under the card: X spring (0.62, 560) leads, Y spring (0.78, 330) lags; scale X 0.25 -> 1, Y 0.08 -> 1; alpha
  `clamp(3p)`; origin = the card's top corner on the item's side; same 20 dp clearing blur; exit 140 ms (`:272-274,335-355,399-406`).
- Layout: side margin 16 dp, card-to-panel gap 8 dp, top limit status bar + 8 dp, bottom limit nav bar + 16 dp; card width
  `min(0.78 * windowWidth, 340 dp)`, shrunk so card + gap + panel + 76 dp text fit, never below 140 dp; right-half items
  right-align card and panel (`:360-392`). Write it as a pure function next to `placePanel` and test it.
- Drag the preview while the finger is down: rubber mapping x * 0.6, up * 0.3, down 1:1 (`:177-178`); panel alpha
  `*= 1 - clamp(dragDistance / 120 dp)` (`:284,339`); on release dismiss if dragged down > 64 dp or > 40% of the way
  toward home, else spring back (0.75, 500) (`:151-160`).

---

## 4. Button-anchored menu / popover (overflow "...", toolbar menus)

### 4.1 The morph (PORTED FROM GLISSE: `G/menu.tsx:72-124`)

The glass grows out of the trigger: one master spring `t` (open 600/46, close 900/57, retuned on direction change),
everything else derived per frame. The trigger fades into the surface in the first 20%; the trigger's glyph drifts toward
the surface centre, swells to 1.6x and blurs away; items scale up 0.5 -> 1 from the trigger centre, unblur 6 px -> 0 and
fade in later than the glass (`(t-0.15)/0.6`), and fade out earlier on close (`(t-0.55)/0.45`). Items are clipped to the
growing glass rect mapped back through their own scale.

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Rovsti extension: `upward` (grow from the trigger's bottom when placePanel chose Side.Above).
package dev.rovsti.ui.overlay

import androidx.compose.ui.geometry.Size

data class MenuMorphFrame(
    val surfaceLeft: Float, val surfaceTop: Float, val surfaceWidth: Float, val surfaceHeight: Float,
    val surfaceAlpha: Float, val triggerAlpha: Float,
    val glyphAlpha: Float, val glyphDx: Float, val glyphDy: Float, val glyphScale: Float, val glyphBlurPx: Float,
    val contentAlpha: Float, val contentScale: Float, val contentBlurPx: Float,
    val originX: Float, val originY: Float,
    val clipLeft: Float, val clipTop: Float, val clipRight: Float, val clipBottom: Float, val clipRadius: Float,
    val settled: Boolean,
)

private fun unit(v: Float) = v.coerceIn(0f, 1f)

fun menuMorphFrame(
    value: Float, from: Size, to: Size, cornerPx: Float,
    trailing: Boolean, upward: Boolean, leaving: Boolean, pxPerDp: Float,
): MenuMorphFrame {
    val t = unit(value)
    val width = from.width + (to.width - from.width) * t
    val height = from.height + (to.height - from.height) * t
    val scale = 0.5f + 0.5f * t
    val originX = if (trailing) to.width - from.width / 2f else from.width / 2f
    val originY = if (upward) to.height - from.height / 2f else from.height / 2f
    val surfaceLeft = if (trailing) to.width - width else 0f
    val surfaceTop = if (upward) to.height - height else 0f
    fun localX(edge: Float) = originX + (edge - originX) / scale
    fun localY(edge: Float) = originY + (edge - originY) / scale
    val drift = 0.3f * t
    return MenuMorphFrame(
        surfaceLeft = surfaceLeft, surfaceTop = surfaceTop, surfaceWidth = width, surfaceHeight = height,
        surfaceAlpha = unit(t / 0.2f),
        triggerAlpha = if (t == 0f) 1f else 1f - unit(t / 0.2f),
        glyphAlpha = 1f - unit(t / 0.45f),
        glyphDx = (surfaceLeft + width / 2f - originX) * drift,
        glyphDy = (surfaceTop + height / 2f - originY) * drift,
        glyphScale = 1f + 0.6f * t,
        glyphBlurPx = 8f * t * pxPerDp,
        contentAlpha = if (leaving) unit((t - 0.55f) / 0.45f) else unit((t - 0.15f) / 0.6f),
        contentScale = scale,
        contentBlurPx = 6f * (1f - t) * pxPerDp,
        originX = originX, originY = originY,
        clipLeft = localX(surfaceLeft), clipTop = localY(surfaceTop),
        clipRight = localX(surfaceLeft + width), clipBottom = localY(surfaceTop + height),
        clipRadius = cornerPx / scale,
        settled = t == 1f,
    )
}

/** Card follows a finger pressed on it with resistance saturating at 7 dp (G/menu.tsx:32). */
fun menuResistance(dPx: Float, pxPerDp: Float): Float {
    val d = dPx / pxPerDp
    return Math.signum(d) * 7f * (1f - kotlin.math.exp(-kotlin.math.abs(d) * 0.12f / 7f)) * pxPerDp
}

/** Pressed card grows by 5 dp on its longer side, never more than 2.5% (G/menu.tsx:187-195). */
fun menuPressScale(w: Float, h: Float, pxPerDp: Float): Float = 1f + 5f * pxPerDp / maxOf(w, h, 200f * pxPerDp)
```

Rendering (ORIGINAL): the card box has its final size from the first frame (no relayout per frame). The glass surface is
drawn in `drawBehind` as a rounded rect at `(surfaceLeft, surfaceTop, surfaceWidth, surfaceHeight)` with alpha
`surfaceAlpha`; the trigger glyph copy is a small layer with `glyph*`; the content column uses
`graphicsLayer { scaleX = contentScale; scaleY = contentScale; transformOrigin = TransformOrigin(originX / size.width, originY / size.height); alpha = contentAlpha; renderEffect = blur(contentBlurPx); clip = !settled; shape = InsetRoundRectShape(clip*) }`.
A rounded-rect outline clip is the hardware fast path; avoid arbitrary path clips on animating shapes (CPU mask per frame,
`U/component/floatingtabbar/FloatingTabBarTabs.kt:300-303`). When `settled`, clear clip and effects (glisse clears all
inline styles at `t == 1`). Keep the entry composed until the close spring reaches 0 (glisse `leaving -> closed`).
Reduced motion: 100 ms fade only (`G/styles.css:1069-1083`).

### 4.2 Card metrics and rows (glisse `G/styles.css:912-1067`; Convx `IosMenu.kt:275-365`)

| Item | Value |
|---|---|
| card width | 200 dp (glisse) to 238 dp (Convx panel); pick 220 dp, min/max via content |
| card radius | 20 dp (glisse) / 32 dp (Convx); concentric with rows: row radius = card radius - 6 dp padding |
| card padding | 6 dp |
| row | min height 44 dp (glisse 32 is web-dense; use 44 for touch), padding 6/12, gap 10, radius 14, icon 16-20 dp, label 15-17 sp |
| destructive | `tokens.destructive`; disabled alpha 0.38 |
| separator | 0.5 dp `tokens.separator`, margin 7/11 |
| highlight plate | one plate that slides between rows: `tween(100, PlateEasing)` translate, 80 ms opacity; first highlight appears in place without sliding (`G/menu.tsx:159-181`) |
| pressed card sheen | diagonal white gradient 0.11 -> 0.025, 120 ms (`G/styles.css:987-1000`) |
| material | `Popover` preset (glass.md 4.2) |

### 4.3 Press, slide, release (the most "iOS" behaviour)

One observer over the whole panel (never consumes) (`IosMenu.kt:135-158`; glisse `G/menu.tsx:196-222`):
finger down -> hovered = row under finger; move -> if the row changed and is enabled: `HapticFeedbackType.TextHandleMove`
(or SEGMENT_TICK, navigation-layout.md 8) and move the plate; up -> if a row is hovered, the panel did not scroll, and the row is
enabled: dismiss then run the row action. Rows are hit-tested from a plain list of row rects measured once after layout
(the panel does not move while open). Card follows the finger with `menuResistance`, springs back with `MenuCardRelease`.

From the trigger: a tap-opened menu starts on *down*; the trigger keeps reading the finger and forwards positions to the
open entry through the controller (`controller.beginSlide(entry, pointerFlow)`), so press-on-trigger, slide, release
selects in one gesture. Convx does this with a global pointer singleton (`IosCompatMenus.kt:197-214,247-303`), which breaks
with multi-touch and tests: do not.

Dismissal: scrim tap, back, selecting a row, trigger tap toggles, hardware Escape; arrow keys move focus with wrap; first
enabled row gets focus on open (`G/menu.tsx:128,237-262`).

### 4.4 Submenus (no Convx/glisse evidence: own design)

Drill-in inside the same panel: header row with back chevron, content slides 24 dp horizontally with fade, panel height
animates on `menuGrowY`. Sliding onto a submenu row opens it after ~350 ms dwell or on release.

---

## 5. Sheets with detents

### 5.1 Physics (ORIGINAL code, Convx numbers)

`IosSheet.kt:140-297` and `U/component/BottomSheetMenu.kt:80-227`: one `Animatable<Float>` offset (0 = fully open,
H = dismissed); detents Medium = 0.5 H, Large = H - (status bar + 24 dp), fixed dp, fraction; entrance snap to H then
`sheetSettle`; release projects with 0.998, picks the nearest detent to the projected point; dismiss if projected beyond
the lowest detent + 12% H with `sheetDismiss` and release velocity; scrim `0.32 * (1 - offset/H)` in a layer lambda.

```kotlin
// ORIGINAL
@Stable
class SheetState(private val scope: CoroutineScope, val dismissible: Boolean = true) {
    val offset = Animatable(Float.MAX_VALUE)
    var containerHeight = 0f; private set
    private var detentOffsets = floatArrayOf()                 // ascending offsets, [0] = tallest
    private val tracker = VelocityTracker()
    var onDismissed: () -> Unit = {}

    fun layout(height: Float, detents: List<Detent>, topInsetPx: Float) {
        containerHeight = height
        detentOffsets = detents.map { height - it.heightPx(height, topInsetPx) }.sorted().toFloatArray()
        offset.updateBounds(lowerBound = null, upperBound = height)
    }

    fun tallestOffset(): Float = detentOffsets.first()

    fun present() = scope.launch { offset.snapTo(containerHeight); offset.animateTo(detentOffsets.last(), RovstiMotion.sheetSettle(0.5f)) }

    /** Finger drag. Past the tallest detent the sheet resists instead of stopping dead (Convx clamps; iOS resists). */
    fun drag(change: PointerInputChange?, dy: Float) {
        change?.let(tracker::addPointerInputChange)
        val top = detentOffsets.first()
        val raw = offset.value + dy
        val next = if (raw < top) top - Rubber.band(top - raw, containerHeight) else raw
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
    }

    fun release(velocityY: Float = tracker.calculateVelocity().y) = scope.launch {
        tracker.resetTracking()
        val rest = Fling.restingPoint(offset.value, velocityY)
        if (dismissible && rest > detentOffsets.last() + 0.12f * containerHeight) {
            offset.animateTo(containerHeight, RovstiMotion.sheetDismiss(0.5f), initialVelocity = velocityY)
            onDismissed()
        } else {
            val target = Fling.nearestSnap(detentOffsets, rest, velocityY)
            offset.animateTo(target, RovstiMotion.sheetSettle(0.5f), initialVelocity = velocityY)
        }
    }

    /** Programmatic dismiss plays the same exit as a drag (Convx's IosSheet has none, IosSheet.kt:133). */
    fun dismiss() = release(velocityY = 3000f)

    /** 0 dismissed .. 1 at the lowest detent or above: drives scrim and pushback. */
    fun presentedFraction(): Float =
        if (containerHeight <= 0f) 0f else (1f - (offset.value - detentOffsets.last()).coerceAtLeast(0f) /
            (containerHeight - detentOffsets.last()).coerceAtLeast(1f)).coerceIn(0f, 1f)
}
```

### 5.2 Layout, scrolling content, grabber

- **Live resize, not translate**, when the sheet hosts a lazy list: the library sheet forces min = max height = `H - offset`
  so the list always measures at its visible size (`H/CupertinoModalBottomSheet.kt:160-168`). Implement with a `Layout` whose
  height reads `offset` in the **layout** phase (no recomposition). For short static content, translate in
  `graphicsLayer { translationY = offset }` (cheaper).
- Content interaction modes (`IosSheet.kt:206-212,277-297`): `Resizes` (drag on content moves the sheet first) and `Scrolls`
  (content scrolls; the sheet moves only when the list is at its top and the leftover scroll is downward). Nested scroll:

```kotlin
// ORIGINAL
fun sheetScrollConnection(sheet: SheetState) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val top = sheet.tallestOffset()
        // Sheet pulled below its top detent and the user scrolls up: move the sheet first.
        if (available.y < 0f && sheet.offset.value > top) { sheet.drag(null, available.y); return Offset(0f, available.y) }
        return Offset.Zero
    }
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // List at its top, leftover downward drag: the sheet takes it.
        if (available.y > 0f && source == NestedScrollSource.UserInput) { sheet.drag(null, available.y); return Offset(0f, available.y) }
        return Offset.Zero
    }
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (sheet.offset.value > sheet.tallestOffset() + 0.5f) { sheet.release(available.y); return available }
        return Velocity.Zero
    }
}
```
  (`NestedScrollSource.UserInput` replaced `Drag` in Compose 1.7; verify at the pinned version.)
- Grabber: 36 x 5 dp capsule, `systemGray3`, 5 dp above / 8 dp below, always draggable; while pulled it stretches:
  `s = tanh(drag / 320 dp)`, `scaleX = 1 + 0.7 s`, `scaleY = 1 - 0.3 s` (`U/component/BottomSheetMenu.kt:97-98,207-227`).
- Shape: 28 dp top corners (38 dp full-page sheets), content inset 16 dp, inner radius = outer - inset (min 4 dp), content width
  cap 500-640 dp; compact landscape (height < 480 dp) gets Large only (`BottomSheetMenu.kt:80-150`, `U/component/IosPageSheet.kt:247`).
- Predictive back: preview 35% of the travel with `1-(1-p)^2` (motion.md 9).
- Focus: clear focus (closes the IME) when a sheet with text fields closes (`BottomSheetMenu.kt:152-157`); fields inside use `imePadding()`.

### 5.3 Pushback of the page behind (ORIGINAL)

Scale 0.91 about top-centre, shift down by the status bar, corner 32 dp, dim 0.22, black behind in unscaled coordinates; a
counter keeps the page pushed back until the last stacked sheet closes; a **lambda** overload so a dragged sheet drives it
with zero recomposition (`U/component/IosSheetPresentation.kt:29-159`). Menus and popovers never push the page back.

```kotlin
// ORIGINAL. Parent supplies the black: Box(Modifier.background(Color.Black)) { Page(Modifier.sheetPushBack(...)) }
fun Modifier.sheetPushBack(progress: () -> Float, statusBarPx: Float, cornerPx: Float): Modifier = this
    .graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        val s = 1f - 0.09f * p
        scaleX = s; scaleY = s
        transformOrigin = TransformOrigin(0.5f, 0f)
        translationY = statusBarPx * p
        shape = RoundedCornerShape(cornerPx * p)
        clip = p > 0f
    }
    .drawWithContent { drawContent(); val p = progress(); if (p > 0f) drawRect(Color.Black.copy(alpha = 0.22f * p)) }
```

**Stops exception**: the place/route sheet over the full-screen map has no pushback and no scrim at peek/medium, and
background interaction is enabled so the map stays pannable (detents peek ~120 dp, medium, large). The map camera padding
follows the sheet's *settled* detent, not every frame (`convx-motion.md` 12.3 i).

---

## 6. Alerts, action sheets, toasts, text toolbar

| Overlay | Spec (one token set; Convx is inconsistent: alerts 1.2, dialogs 0.96, three scrim values) | Source |
|---|---|---|
| Alert | 270 dp wide, min 110 dp tall, title headline + message footnote centred, buttons horizontal (vertical if > 2 or long); enter scale 1.2 -> 1 + fade on `appear`; exit fade 150 ms (we can, the host keeps it composed); scrim 0.4 dark / 0.2 light; no dismiss on outside tap; back dismisses if a cancel action exists | `H/CupertinoDialogs.kt:199-260,368-372,783-790`; `U/component/Dialog.kt:366-371` |
| Action sheet | bottom group + separate cancel group, max width 500 dp, 8 dp side padding, 56 dp rows, insets nav bar + 8 dp; enter `translationY = h(1-p)` 400 ms `(0.2833,0.99,0.31833,0.99)`, exit 150 ms EaseIn. Prefer it for destructive confirmations triggered from a row | `H/CupertinoDialogs.kt:269-330,497-560` |
| Toast | own queue in the host (one visible, replace policy), dark capsule (label colour 0.92, background-coloured text, subheadline, max 2 lines, padding 18/12, outer 16/8), sits above the **measured** tab bar inset, 3 s default, swipe to dismiss, optional action in tint colour, `liveRegion = LiveRegionMode.Polite` | `U/ios/compat/IosCompatBars.kt:257-301` (Convx uses Material's host; we do not) |
| Tooltip | 10 dp radius, label 0.92, padding 12/8, placed with `placePanel` (Convx has no placement) | `IosCompatTail.kt:178-203` |

**Text selection toolbar** (owner complaint: no copy/paste menu). The platform's floating toolbar that Compose shows for
`BasicTextField` and `SelectionContainer` is already correct (positioning, IME, cut/copy/paste/select all/autofill): keep it
as the default. Only if a glass-styled one is wanted, provide `LocalTextToolbar` with our own `TextToolbar`
(`showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)`, `hide()`, `status`; Compose 1.8
added an autofill overload: implement every overload of the pinned interface) and render a small capsule entry placed with
`placePanel(anchor = selection rect, prefer above)`. Convx has nothing here (`convx-navigation.md` 1 point 8).

---

## 7. Haptics in overlays

Open a long-press menu: `LongPress` (heavy click). Row change while sliding: `TextHandleMove`/segment tick. Sheet snaps to a
new detent: light tick. Destructive confirm: `Confirm`; invalid: `Reject`. Convx drives the vibrator directly with predefined
effects because `View.performHapticFeedback` is silenced on Samsung when "touch interactions" vibration is off
(`U/theme/AppHaptics.kt:19-54`). Implementation and API levels: `navigation-layout.md` 8.

---

## 8. Accessibility (Convx has effectively none: no semantics in overlay code, `H/Accessibility.kt:25-35`)

- Modal entries: page behind gets `clearAndSetSemantics {}`; entry gets `paneTitle` + `isTraversalGroup`; focus moves to the
  entry's first focusable on open; title announced.
- Every long-press menu has an equivalent: `semantics { onLongClick(label = "Show actions") { ...; true } }` and
  `customActions = listOf(CustomAccessibilityAction("Delete") { ...; true })` on the row; swipe actions are custom actions too.
- Rows in menus: `Role.Button` (or `Role.Checkbox`/`selected` for checkmark rows), destructive described in the label, 48 dp
  touch target (44 visual + hit padding).
- Back: host handles the top entry; hardware keyboard: arrows move the highlight, Enter commits, Escape dismisses.
- Reduce motion: fade only. Reduce transparency: opaque `Popover` fill (`#1C1C1E` / `#F2F2F2`, `IosMenuMaterial.kt:203-206`).

---

## 9. Testing overlays (no device)

- JVM: `placePanel`, preview-card fitting, sheet detent math, `Fling` projection + dismiss rule, hover hit-test,
  `menuMorphFrame` invariants (t=0: surface = trigger rect, content alpha 0; t=1: surface = card, settled; clip rect always
  inside the card in content-local space), `menuResistance` saturates at 7 dp.
- Compose (Robolectric, manual clock): long press opens, tap does not; child click inside a row wins; slide then release
  commits; scroll cancels; back pops only the top entry; trigger bounds unchanged after open; sheet drags with
  `performTouchInput { swipe(..., durationMillis) }` to each detent and dismiss; stacked sheets keep pushback until the last closes.
- Screenshots at fixed animation frames (`mainClock.advanceTimeBy`): closed, +40 ms (blob + blur), +100 ms, settled; menu
  above vs below; clamped at edges; light/dark; sheet at each detent; alert; toast above the tab bar. Open and judge every PNG.

---

## 10. Cheat sheet

```text
Context menu (simple)  scrim .28/.14 | lift 1.05 (150/120) | panel spring .78/700 | exit 150 (0.4,0,0.9,0.5) | blur 20dp->0 open only
                       gap 8 | margin 12 + safe insets | alpha p/.35 | start scale >= .08 | haptic LongPress
Preview card           press .96 + veil .14 (300) | lift .80/420 | land 1.0/520 | land fade .3 | panel x .62/560, y .78/330
                       card min(.78W, 340) | radius 22 | pad 10 | rubber x.6 up.3 | dismiss 64dp down or 40% home | return .75/500
Button menu (glisse)   morph open 600/46, close 900/57 | surface a t/.2 | items .5->1, blur 6->0, a (t-.15)/.6, out (t-.55)/.45
                       glyph 1->1.6, blur 8t, a 1-t/.45 | card press +5dp (<=2.5%) 650/36 | drag resist 7dp | plate 100ms (.2,.8,.2,1)
Sheet                  detents .5H, H-(status+24) | settle .86/380 | dismiss 1/400 | +12% H rule | 0.998 | scrim .32 | grabber 36x5 tanh/320
Pushback               .91 | 32dp | dim .22 | spring 1/380 | top-centre | shift status bar | not for Stops
Alert / action sheet   270dp, 1.2->1 + fade, out 150 | 500 max, 56 rows, in 400 (.2833,.99,.31833,.99), out 150
Toast                  capsule | above measured bar | 3 s | swipe dismiss | live region
```
