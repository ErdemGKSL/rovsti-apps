# Convx motion system: research report for Rovsti Kotlin UI

Scope: how Convx (cosmictaserdev-creator/Convx, Kotlin + Jetpack Compose) makes its motion, and why it feels fluid.
Licence guard: Convx is GPL-3.0, Rovsti is MIT/Apache. Everything below is a description in our own words plus
numeric parameters (facts and numbers are not code). Pseudo-code is original. No Convx source is to be copied into Rovsti.
Where Convx itself says a file is ported from an Apache-2.0 upstream (see section 11), prefer going to that upstream
directly and re-implementing from the description here.

Path prefix used in all citations (relative to the Convx checkout):
`U = app/src/main/kotlin/com/convx/music/ui`. Example: `U/utils/Motion.kt:77` is
`app/src/main/kotlin/com/convx/music/ui/utils/Motion.kt` line 77.
The git history of the checkout is a single commit, so `git log` carried no extra information; the findings come from
source and in-code comments (which are unusually rich in rationale and reference-video measurements).

---------------------------------------------------------------------------------------------------

## 0. The five-line answer: why it feels fluid

1. **Almost everything is a spring, and springs are only ever retargeted, never restarted.** Compose's
   `Animatable.animateTo` / `animate*AsState` carry the current value AND velocity into the next target, so a tap
   during a settle, a drag during a bounce, or a reversed sheet never "jumps" (sections 1, 2, 5).
2. **Gestures hand their release velocity to the settle spring** (`initialVelocity = fling velocity`) and pick the
   target from the *projected* resting point, not the release point (section 6). The finger and the animation are one
   continuous physical system.
3. **During a drag the finger owns the value: `snapTo`, not `animateTo`.** One synchronous state write per touch
   event, no queued animations. Springs run only after release (section 5.1).
4. **Every per-frame value is read in the draw/layer phase** (`graphicsLayer { }`, `drawBehind { }`, lambdas,
   `Animatable.value` read inside blocks), so a 120 Hz animation costs zero recompositions (section 8).
5. **Surfaces squash/stretch and overshoot a little; content inside them does not.** Press = small scale-down with a
   slightly underdamped spring, drags = velocity-dependent squash, containers morph with bouncy size curves while
   centres travel on non-overshooting curves (sections 4, 7). Small touches of overshoot read as "physical";
   large ones read as "cartoon". Convx deliberately tuned overshoot per element from user feedback.

---------------------------------------------------------------------------------------------------

## 1. API inventory: which Compose animation API is used for what

| API | Where / what for | Citation |
|---|---|---|
| `Animatable<Float>` + `animateTo(spring)` + `snapTo` | The workhorse for anything gesture-coupled: sheet offset, swipe row offset, overscroll return, press progress, mini-player drag | `U/ios/component/IosSwipeActions.kt:78,85,149`; `U/ios/component/IosSheet.kt:101,198,203`; `U/component/BottomSheet.kt:327-345,635-650` |
| `Animatable<Offset>` (`Offset.VectorConverter`) | Context-menu drag-of-preview; interactive highlight finger position | `U/ios/component/AppleContextMenu.kt:202`; `U/component/backdrop/catalog/utils/InteractiveHighlight.kt:47` |
| `Animatable<Dp>` (`Dp.VectorConverter`) | Player sheet height (collapsed/expanded/dismissed anchors) | `U/component/BottomSheet.kt:620` |
| `animateFloatAsState` / `animateDpAsState` / `animateColorAsState` | State-driven small stuff: button press scale, toggle knob, nav-bar collapse, tab-bar minimise, slider knob swell | `U/ios/component/IosButton.kt:123`; `U/ios/component/IosToggle.kt:116-125`; `U/ios/component/IosNavigationBar.kt:56`; `U/ios/component/IosTabBar.kt:121`; `U/ios/component/IosSlider.kt:99` |
| `updateTransition` / `rememberTransition(MutableTransitionState)` + `Transition.animateFloat` with per-direction `transitionSpec` | Context menus: several values (lift, scrim, menu X/Y scale, blur) share one open/closed state but use different specs in each direction | `U/ios/component/IosContextMenu.kt:131-168`; `U/ios/component/AppleContextMenu.kt:262-273,399-406` |
| `AnimatedContent` + `togetherWith` + `SizeTransform(clip=false)` | Play/pause glyph swap (fade + scale 0.3 -> 1) | `U/component/AnimatedPlayPauseIcon.kt:40-62` |
| `AnimatedVisibility` (fadeIn/fadeOut tween) | Playing indicator etc. | `U/component/PlayingIndicator.kt:103-104` |
| `animateContentSize(tween(220))` | Expanding settings groups | `U/component/IosSettingsGroup.kt:61` |
| `keyframes` with `atFraction` | Tab labels fading in only in 50%-80% of a 150 ms window | `U/component/floatingtabbar/FloatingTabBarTabs.kt:599-614` |
| `infiniteRepeatable(tween(LinearEasing))` | Shimmer skeleton, "squiggly"/loading slider phase | `U/component/Skeletons.kt:65`; `U/component/shimmer/ShimmerHost.kt:56-63`; `U/component/PlayerSlider.kt:127-131` |
| `Modifier.animateItem()` | Reorder/insert/remove in lazy lists (queue, playlists) with the `reorderable` library | `U/player/Queue.kt:1244,1301,1512`; `U/screens/playlist/LocalPlaylistScreen.kt:682,696` |
| `SharedTransitionLayout` / `sharedElement` / `sharedBounds` + `BoundsTransform` | Tab pill <-> inline circle morph, artwork and title morph into album/playlist detail | `U/component/floatingtabbar/FloatingTabBarMotion.kt:45-54,334-342`; `U/utils/SharedArtwork.kt:182-190,320-330` |
| NavHost `enterTransition/exitTransition/popEnter/popExit` with slide+fade | Screen push/pop with parallax | `U/screens/NavigationBuilder.kt:244-263,753-785` |
| Predictive back (`BackHandler`-style preview feeding a sheet) | Sheet moves 35% of its travel along the gesture (`previewBack`) | `U/component/BottomSheet.kt:91,490-500` |
| Custom `Easing` classes | Spring step-response squeezed into a fixed duration (`SpringEasing`, `CriticalEasing`), artwork "critically damped" curve | `U/component/floatingtabbar/BarMotion.kt:146-174`; `artwork/src/main/kotlin/com/convx/artwork/ArtworkMotion.kt:5-17` |
| Custom `FiniteAnimationSpec<Rect>` / `VectorizedFiniteAnimationSpec` | Per-axis staggered rect animation (x and y with different delay, duration, curves) | `U/component/floatingtabbar/BarMotion.kt:221-284` |
| `CubicBezierEasing` | iOS nav curve, menu enter/exit, "seamless" ease-in-out sine, lyric easing | `U/utils/Motion.kt:39,59,69,110,164`; `U/ios/component/IosContextMenu.kt:259-260`; `U/player/PlayerSheetMotion.kt:167` |
| `VelocityTracker` + custom projection | Flick decisions (swipe rows, sheets, mini-player) | `U/ios/component/IosSwipeActions.kt:119-139`; `U/utils/FlingProjection.kt:21-56` |
| `OverscrollEffect` (custom `IosOverscrollEffect`) + `NestedScrollConnection` | Global rubber-band overscroll and hero pull-zoom | `U/utils/IosOverscroll.kt:81-268`; `U/utils/MotionModifiers.kt:117-236` |
| `Modifier.Node` (`LayoutModifierNode`, `DrawModifierNode`, `PointerInputModifierNode`) | Overscroll translate (placeWithLayer) and press indication node | `U/utils/IosOverscroll.kt:270-318`; `U/utils/ModifierExt.kt:138-206` |
| `GraphicsLayer` record/replay (`obtainGraphicsLayer`, `drawLayer`) | The mini-player to full-player morph reuses live recorded layers instead of recomposing two UIs | `U/player/PlayerMorphLayers.kt:24-60`; `U/player/PlayerContainerMorph.kt:123-240` |
| `RenderEffect` blur + `ColorMatrixColorFilter` threshold | "Gooey" metaball merge of pill and circle; context-menu "focus-in" blur | `U/component/floatingtabbar/GooeyTransition.kt`; `U/ios/component/IosContextMenu.kt:213-227` |

Not used at all (checked by grep): `exponentialDecay` / `splineBasedDecay` / `rememberSplineBasedDecay` as an
app-level API (flick physics are the analytic projection in section 6 plus the stock LazyList fling), Material
`SwipeToDismissBox`, and `AnchoredDraggable` (the sheet is a hand-written `Animatable` + `draggable`).

---------------------------------------------------------------------------------------------------

## 2. Motion tokens: the table of constants actually used

All app-wide tokens live in one object (`U/utils/Motion.kt`), with a "single source of truth" philosophy: specs are
functions returning `FiniteAnimationSpec<T>`; a reduce-motion gate wraps any spec (`Motion.gate`, `Motion.kt:~131`)
so no call site hand-rolls `if (reduce) snap() else ...`.
Spring notation: `spring(damping ratio zeta, stiffness k)`. Compose mass is 1, so natural frequency = sqrt(k) rad/s
(k=380 -> ~19.5 rad/s -> ~0.31 s period; k=900 -> ~30 rad/s).

### 2.1 Springs

| Role | zeta | stiffness | Where | Citation |
|---|---|---|---|---|
| Press feedback token (scale under finger) | 0.75 | 1220 | token | `Motion.kt:87-91` |
| Press scale on `IosButton` (to 0.96) | 0.70 | 900 | button | `U/ios/component/IosButton.kt:123-127` |
| Press scale on bounce indication (to 0.94) | MediumBouncy (0.5) | MediumLow (400) | any `bounceClick` | `U/utils/ModifierExt.kt:136,157-165` |
| Context-menu item press (card) 0.96 | 0.70 | 600 (release) | list item before long-press | `AppleContextMenu.kt:132,412` |
| Selection moving between two positions (tab puck, segmented thumb) | 0.76 | 750 | token | `Motion.kt:77-82` |
| Appear/disappear in place (sheet, chips, inline error) | 1.0 | 450 | token | `Motion.kt:97-102` |
| Shared-container morph | 1.0 | 950 | token | `Motion.kt:42-47` |
| Collection push (album/playlist slide-in) | 1.0 | 380 | token | `Motion.kt:31-36` |
| Switch knob (20 dp travel) | 0.75 | 900 | iOS toggle | `IosToggle.kt:120-125` |
| Slider knob swell to 1.12 | 0.70 | 900 | iOS slider | `IosSlider.kt:99-103` |
| Nav bar large title -> inline collapse | 1.0 | 500 | iOS nav bar | `IosNavigationBar.kt:56-60` |
| Tab bar minimise (slide 40 dp down + 35% fade) | 0.90 | 500 | iOS tab bar | `IosTabBar.kt:121-125` |
| Sheet present / settle to detent | 0.86 | 380 | iOS sheet | `IosSheet.kt:198,274` |
| Sheet dismiss (off screen, with release velocity) | 1.0 | 400 | iOS sheet | `IosSheet.kt:270` |
| Sheet-behind-presenting scale (0.91) | 1.0 | 380 | page scale-back | `U/component/IosSheetPresentation.kt:153-157` |
| Page sheet rises | 1.0 | 380 | `IosPageSheet.kt:62` |
| Swipe-row settle/close | 1.0 | 500 | `IosSwipeActions.kt:85` |
| Overscroll return, normal | 1.0 | 247 | `IosOverscroll.kt:61,346-351` |
| Overscroll return, hard fling (>5000 px/s) | 1.0 | 130 | `IosOverscroll.kt:64-67` |
| Player sheet open/close | 1.0 (open), 0.72 (close when landing handler present) | 360 | `BottomSheet.kt:83,88,430-435` |
| Mini-player swipe return (title swipe) | 0.73 | 620 | `U/player/FloatingMiniPlayer.kt:234-236` |
| Context menu lift (preview card) | 0.80 | 420 | `AppleContextMenu.kt:267` |
| Context menu land (card returns home) | 1.0 | 520 | `AppleContextMenu.kt:267` |
| Context menu width grow | 0.62 | 560 | `AppleContextMenu.kt:273` |
| Context menu height grow | 0.78 | 330 | `AppleContextMenu.kt:274` |
| Drag the preview, release back | 0.75 | 500 | `AppleContextMenu.kt:159` |
| Popup menu (non-preview) grow | 0.78 | 700 | `IosContextMenu.kt:147` |
| Overlay menu X / Y scale from anchor | 0.72 / 0.78 | 700 / 420 | `U/component/OverlayMenu.kt:110-111` |
| Overlay menu close | 1.0 | 900 | `OverlayMenu.kt:113-114` |
| Damped-drag value (tab puck/slider/toggle) | 1.0 | 1000 | `DampedDragAnimation.kt:45` |
| Damped-drag squash X | 0.6 | 250 | `DampedDragAnimation.kt:48` |
| Damped-drag squash Y | 0.7 | 250 | `DampedDragAnimation.kt:49` |
| Damped-drag velocity decay | 0.5 | 300 | `DampedDragAnimation.kt:46` |
| Interactive highlight press + follow | 0.5 | 300 | `InteractiveHighlight.kt:44-45` |
| Whole tab-bar "tug" back to rest | 1.0 | 300 | `FloatingTabBarTabs.kt:205` |
| Volume slider release overshoot | 0.5 | Medium (1500) | `ElasticVolumeSlider.kt:146` |
| Volume slider grow-while-dragging | MediumBouncy | Low (200) | `ElasticVolumeSlider.kt:103-108` |
| Chips row | MediumBouncy | Medium (1500) | `ChipsRow.kt:129-151,260-281` |
| Navigation-bar spring (anchors) | MediumBouncy | Low (200) | `U/constants/Dimensions.kt:65-68` |
| Bottom sheet default / soft | NoBouncy | MediumLow (400) / Low (200) | `Dimensions.kt:70-78` |

### 2.2 Tweens and curves

| Role | ms | Curve | Citation |
|---|---|---|---|
| Screen push/pop | 400 (doc comment argues 260 but constant is 400) | `cubic(0.32, 0.72, 0, 1)` "UIKit nav curve, not FastOutSlowIn" | `Motion.kt:157-164` |
| Parallax: outgoing screen moves | - | 30% of width, dims to alpha 0.85 | `Motion.kt:173,176,~179` |
| Detail page enter / exit | 400 / 400 | FastOutSlowIn; exit slides only a quarter width | `NavigationBuilder.kt:758,780,806-807` |
| Predictive-back-safe exit ("appear exit") | 400 | `cubic(0.32,0.72,0,1)` | `Motion.kt:108-110` |
| Card growing out of tile (open) | 340 | `cubic(0.3,0.5,0.4,1)` (fitted to a 30 fps capture) | `Motion.kt:54-59` |
| Card shrinking back (close) | 260 | same curve | `Motion.kt:68-69` |
| Artwork/title shared bounds | 350 | custom 1 - (1+x)e^-x with x=9t, normalised so t=1 -> 1 (critically damped step response) | `ArtworkMotion.kt:6-16` |
| Shared artwork (list -> detail) | 500 | same curve | `Motion.kt:26-28` |
| Icon swap (play/pause) | 200 in / 160 out, initial scale 0.3 | FastOutSlowIn | `Motion.kt:119-124` |
| Lyric line alpha / scale (1.05) | 400 / 400 | default | `Lyrics.kt:1170-1181` |
| Lyric blur by distance (2.5 dp/line, max 8) | 300 | `cubic(0.75,0,0.25,1)` (symmetric) | `Lyrics.kt:1186-1195`, `Motion.kt:39` |
| Ambient colour/backdrop fades | 1100 default (900-8000 follows DJ crossfade) | `cubic(0.37,0,0.63,1)` (ease-in-out sine) | `PlayerSheetMotion.kt:167-176` |
| Cupertino action sheet in / out | 400 / 150 | `cubic(0.2833,0.99,0.31833,0.99)` / EaseIn | `U/component/BottomSheetMenu.kt:93-95` |
| Menu enter / exit | 150 / 140 | `cubic(0.2,0.9,0.25,1.03)` (tiny overshoot) / `cubic(0.4,0,0.9,0.5)` | `IosContextMenu.kt:259-260` |
| Menu scrim in / out | 180 / 140, alpha 0.28 | as above | `IosContextMenu.kt:155-161,262` |
| Anchor "lift" of pressed item (1.05x) | 150 / 120 | as above | `IosContextMenu.kt:163-168,263` |
| Release glow ring | 460 | FastOutSlowIn | `InteractiveHighlight.kt:123` |
| Menu blur-in (20 dp, clears with progress) | tied to spring | - | `IosContextMenu.kt:213-227,262` |
| Long press press-dim ramp | 300 | default | `AppleContextMenu.kt:116` |
| Tab labels leave / arrive | 45 / 80 (delay = expand-ms minus 90) | Linear | `FloatingTabBarTabs.kt:580-593` |
| Tab icon fade keyframes | 150 | alpha 0->1 between 50% and 80% | `FloatingTabBarTabs.kt:599-614` |
| Pill shadow returns after landing | 320 | ease-in-out sine | `FloatingMiniPlayer.kt:769` (const), `:261` |
| Tab-bar rim tint | 300 | FastOutSlowIn | `FloatingNavBar.kt:240` |

### 2.3 Fixed-duration "fake springs" (important trick)

Where several clocks must finish on the same frame (container size, fades, shared bounds), Convx does not use a real
`spring()` (a spring never ends at a definite time; a tween and a spring drift apart by a visible hair at the end).
It builds an **Easing that is the exact step response of a spring** and plays it in a `tween`:
`BarMotion.kt:146-174`. Idea: a damped oscillator's step response `1 - e^(-zeta*w*t) * (cos(wd*t) + zeta/sqrt(1-zeta^2) * sin(wd*t))`
sampled with t in 0..1 and `w*duration` = 7.8 (leaves under 0.6% residual), then a linear correction so `f(1) = 1`
exactly. Critical damping variant: `1 - (1 + w*t) * e^(-w*t)`. This yields spring feel (fast start, early crossing,
visible overshoot ~ `exp(-zeta*pi/sqrt(1-zeta^2))`: zeta 0.66 -> ~6%, 0.74 -> ~3%) with a deterministic end.
Used for the pill/circle morph (`FloatingTabBarMotion.kt:65-81`) and the mini-player accessory slide
(`BarMotion.kt:292-330`).

The morph durations and curves, with the reasoning in code comments:

| Morph | Durations (ms) | Curves | Cite |
|---|---|---|---|
| Bar collapse (fades) / expand (fades) | 317 / 251 | - | `FloatingTabBar.kt:168-170` |
| Bar container morph collapse / expand | 560 / 470 (container outlasts fades "so overshoot settles visibly") | surface: critical (collapse), zeta 0.74 (expand), width zeta 0.40 on expand | `FloatingTabBar.kt:172-174`; `BarMotion.kt:300-314` |
| Mini accessory slide, collapse | x: 0 delay + 560; y: 67 ms delay + 560 | size zeta 0.56 (omega*T = 9) x / 0.62 y, centre critical | `BarMotion.kt:296,313,319-324` |
| Mini accessory slide, expand | y: 0 + 470; x: 33 delay + 450 | size zeta 0.34 x / 0.58 y, centre critical | `BarMotion.kt:326-330` |

---------------------------------------------------------------------------------------------------

## 3. Interruptibility and velocity preservation (the heart of "fluid")

Patterns used consistently:

1. **Single Animatable per physical quantity.** There is one `Animatable` for the sheet offset, one for a row's
   offset, one per overscroll axis. Any new target is `animateTo` on that same object, so Compose continues from the
   current value and current velocity (a cancelled `animateTo` leaves the Animatable at its live value with live
   velocity). E.g. mini-player sheet expand/collapse both call `animatable.animateTo(bound, spring, initialVelocity)`
   (`BottomSheet.kt:413-442`).
2. **Cancel-and-replace, never queue.** Rapid taps cancel the previous settle job rather than waiting in a mutex:
   the code comment explains that queueing stranded the press progress at 1 and left a puck at 1.39x scale
   (`DampedDragAnimation.kt:146-181`). A `finally` always deflates the pressed morphology even if superseded.
   Same idea in overscroll: a generation counter invalidates stale rebound jobs (`IosOverscroll.kt:94-95,176-201`).
3. **Generation counters guard late callbacks.** A cancelled fling can finish after the user touches down again;
   `releasedGesture != gestureGeneration` makes the late `applyToFling` a no-op (`IosOverscroll.kt:160-163`;
   `MotionModifiers.kt:200-205`).
4. **Release velocity seeds the spring.** `initialVelocity = flingVelocity` on sheet dismiss/settle
   (`IosSheet.kt:270,274`), swipe rows (`IosSwipeActions.kt:85`), overscroll (clamped to +/-10000 px/s,
   `IosOverscroll.kt:58,342-343`), player sheet (`BottomSheet.kt:448-455`).
5. **Retarget instead of reverse.** Fold/unfold of the tab bar is a single progress `Animatable` (0..1) animated to 1
   or 0; every position is a pure function of that progress, so a half-way reversal retraces the same path
   (`FoldGeometry.kt:19-23,38-59`, `FoldingTabBar.kt:74-87`). The comment in `FoldGeometry` states this design goal explicitly.
6. **Velocity-based rects are interruptible too.** The custom `AxisStaggerSpec` implements
   `getVelocityFromNanos` by central difference over a 2 ms window so a retargeted shared-bounds animation still has a
   defined start velocity (`BarMotion.kt:261-279`).
7. **Hand-over input ownership.** While the sheet morph flies, an enum decides who receives touches: during a drag the
   side that started it keeps input (so a close gesture can reverse from the current frame); mid-flight neither owns
   it, so taps cannot fight the settling spring; mini owns input only while progress <= 0.2
   (`PlayerSheetMotion.kt:60-87`).

---------------------------------------------------------------------------------------------------

## 4. Press feedback

| Element | Scale | Notes | Cite |
|---|---|---|---|
| Generic buttons (`IosButton`) | 0.96 | spring(0.70, 900); no ripple (indication = null); scale applied via `Modifier.scale` | `IosButton.kt:121-127,169,172-178` |
| Bounce indication (list rows, icons) | 0.94 | Implemented as a draw-node `Indication`: tracks multiple simultaneous presses in a set; spring(0.5, 400) so release overshoots slightly; no recomposition | `ModifierExt.kt:136-206` |
| Context-menu source item | 0.96 + black overlay 14% | Ramps 300 ms linear-ish while finger held; released with spring(0.7, 600) | `AppleContextMenu.kt:101-111,116,132` |
| Glass pill/tab bar | whole pill grows by 16 dp (`1 + 16dp/width`) and the selected icon 1.12-1.2x | Driven by `pressProgress` from the damped-drag object | `FloatingTabBarTabs.kt:289-299,362-366`; `LiquidBottomTabs.kt:200-202,216` |
| Tab puck | 78/56 = 1.39x | | `FloatingTabBarTabs.kt:182` |
| Liquid toggle thumb / slider thumb | 1.5x | Plus see section 7 | `LiquidToggle.kt:88`; `LiquidSlider.kt:94` |
| Sliders (iOS style) | knob 1.12x | | `IosSlider.kt:99-103` |
| Small floating glass buttons | "elastic stretch" (section 7.3) | | `U/component/GlassCircleButton.kt:63-91` |
| Press glow (finger-following additive sheen) | alpha 0.08 + radial spot 0.15, radius 1.5 x min dimension | Drawn with `BlendMode.Plus`; spot follows finger via snapped position, returns to origin with spring(0.5, 300) on release | `InteractiveHighlight.kt:29-100` |

Takeaways: pressed scale is small (0.94-0.96), only one spring token is used per control class, and ripple is removed
in favour of scale + (optionally) additive brightening. The press target scale is read in the layer phase.

---------------------------------------------------------------------------------------------------

## 5. Gesture-driven animation

### 5.1 "Finger owns the value, spring owns the release"

`DampedDragAnimation` (`U/component/backdrop/catalog/utils/DampedDragAnimation.kt`) is the shared engine behind the
tab puck, toggle thumb, and sliders. Structure in words:

- Five Animatables: `value` (0..N logical position), `velocity`, `pressProgress` (0..1), `scaleX`, `scaleY`.
- `onDrag`: write the *logical value* via `snapTo` immediately (comment: queueing `animateTo` per delta lagged the
  puck on fast drags, `:130-143`). Each snap also feeds a `VelocityTracker` with the value over time and snaps the
  `velocity` Animatable to the normalised result (`:188-193`).
- `press()`: three parallel animations (pressProgress to 1, scaleX/Y to pressedScale) with different springs so X and Y
  do not move in lockstep (squash/stretch; X zeta .6, Y zeta .7, both k 250).
- `release()`: waits one frame, then waits (max 400 ms, `withTimeoutOrNull`) until the value is within 2.5% of its
  target so the pressed bulge does not deflate while the puck is still travelling, then runs four parallel releases
  (`:110-128`). The 400 ms cap exists so new drag input cannot hold the stretch forever.
- `animateToValue`: tap path; wraps the settle in `press()` ... `release()` so a tap on a tab produces the same bulge
  as a drag. Single flight with cancel.

Pseudo-code (own words):

```
class DragPuck(scope, range):
    pos      = Animatable(0f)           // logical position
    vel      = Animatable(0f)           // normalised velocity, for squash only
    press    = Animatable(0f)           // 0..1
    sx, sy   = Animatable(1f), Animatable(1f)

    onDragDelta(dx):  scope.launch { pos.snapTo((pos.value + dx).clamp(range)); vel.snapTo(tracker.update(pos.value)) }
    onTouchDown():    launch{press.animateTo(1, spring(1,1000))}; launch{sx.animateTo(P, spring(.6,250))}; launch{sy.animateTo(P, spring(.7,250))}
    onTouchUp():      launch { awaitNearTarget(timeout=400ms); launch{vel.animateTo(0, spring(.5,300))}; launch{press.animateTo(0,...)}; ... }
    settleTo(i):      launch { pos.animateTo(i, spring(1,1000)) }  // after release; tap path does press+settle+release
```

Rendering (all in layer block, no recomposition): `scaleX = sx / (1 - clamp(vel/50 * 0.75, -0.2, 0.2))`,
`scaleY = sy * (1 - clamp(vel/50 * 0.25, -0.2, 0.2))`: i.e. the puck stretches along its motion direction by up to
~25% and squashes the other axis, proportional to velocity (`LiquidToggle.kt:200-207`, same lines in
`LiquidSlider.kt:209-214`, `LiquidBottomTabs.kt:292-297`).

Tab bar drag details (`FloatingTabBarTabs.kt`):
- Position is in tab-index units; drag delta divided by tab width (`:208-218`).
- On drag stop, target = round(value); if it changed, navigate; either way `animateToValue(target)` (`:196-206`).
- The *whole pill* is tugged a few dp in the drag direction: `panelOffset = 4dp * sign * EaseOut(|offset/totalWidth|)`
  computed via `derivedStateOf`, applied in `graphicsLayer { translationX = panelOffset }`, and relaxed with
  spring(1, 300) on release (`:155-163,205,289-290`). Result: the shell reacts to the puck.
- `canDrag` checks that both the previous and current touch positions are inside the bar, so dragging out of the
  bar stops steering (`DampedDragAnimation.kt:90-96`, `FloatingTabBarTabs.kt:183-194`).
- If an external pager is moving, the puck follows the pager's fractional page position directly with `snapTo`
  (`:231-238`), avoiding two animations fighting.

### 5.2 Swipe-to-reveal rows (`IosSwipeActions.kt`)

- Row content translated with `graphicsLayer { translationX = offset.value }` (`:116`); action buttons sit *behind*
  the row, pinned to the edge, so sliding the row is what uncovers them (`:95-112`).
- During drag: `offset.snapTo(clamp(offset + drag))` (`:147-149`). A `VelocityTracker` is fed the pointer positions.
- On release: compute the *projected endpoint* of the flick (section 6); if past 55% of the row width
  ("full swipe"), fire the first action and close; else choose the nearest of the snap points {-actionsWidth, 0,
  +leadingWidth} to the projected point and animate there with `spring(1.0, 500)` seeded with release velocity (`:123-141`).
- Row snaps to exactly the combined width of the buttons (does not fly off), and swiping all the way commits.

### 5.3 Bottom sheets (`IosSheet.kt`, `BottomSheet.kt`)

`IosSheet`:
- A single `Animatable<Float>` for translation-from-top; the sheet layer reads it in `graphicsLayer { translationY }`
  (`:101,142,217`). Scrim alpha is *also* read in a graphicsLayer lambda from the same value:
  `0.32 * (1 - offset/containerHeight)` (`:157-161`) so the dim tracks the finger without recomposition.
- Detents: Medium = 50% of container, Large = container - 24 dp, fixed dp, or fraction (`:178-185`).
- Entrance: `animateTo(opened, spring(0.86, 380))` (`:198`).
- Drag: `draggable` with `snapTo(offset + delta)` clamped at >= 0 (no upward overshoot) (`:202-204`).
- Release (`settleSheet`, `:258-275`): project with the velocity, choose the nearest detent *to the projected point*;
  dismiss only if projected > tallest-detent + 12% of container height; dismiss animates with spring(1, 400) and release
  velocity; else detent settle spring(0.86, 380) with release velocity. 0.86 gives a ~0.5-1% overshoot: barely
  visible, but it stops it from feeling "dead".
- `presentationContentInteraction(.scrolls)`: a nested-scroll connection that only moves the sheet when the child
  list is at top and the leftover scroll is downward (`:281-297`).
- Grabber capsule 36x5 dp; on a pull it stretches wider/thinner using `tanh(drag/320dp)`: `scaleX = 1 + 0.7*s`,
  `scaleY = 1 - 0.3*s` (`BottomSheetMenu.kt:210-226`).

`BottomSheet` (player sheet, adapted from ViMusic per the file header, `BottomSheet.kt:~95`):
- State = `Animatable<Dp>` with a lower bound at dismissed and **no upper bound**, so settle springs can overshoot;
  direct drag clamps to the expanded bound, because the comment says rubber-banding the live drag made the sheet jitter
  under the finger (`:620-650`).
- Drag write uses `CoroutineStart.UNDISPATCHED`: the `snapTo` happens inside the same input event, not one
  dispatcher hop later. The comment says the card trailed the finger by a frame without it (`:638-644`).
- Tap-driven open/close use the same spring as drag-driven settle ("so tap and drag feel identical", `:464-472`).
- Fling thresholds: |v| > 250 px/s decides expand vs collapse; below collapsed with onDismiss -> dismiss (`:510-520`).
- Collapse is slightly underdamped (0.72) **only when** a landing handler exists, and the sheet is clamped at the
  collapsed bound, so the arrival velocity is *reported* (`AnimationResult.endState.velocity`) and the mini pill
  re-uses it as an overshoot dip below its slot (see 7.4).

### 5.4 Presenting-screen scale-back (`IosSheetPresentation.kt`)

When any sheet is up the screen behind it scales to 0.91 about its top-centre, translates down by the status-bar inset,
gains a 32 dp corner radius and a 22% black dim; backdrop behind is black so the gap reads as depth (`:30-69`).
Two overloads: a plain float (for state-based progress animated by `spring(1, 380)`, `:153-157`) and a **lambda**
overload that reads `progressProvider()` inside `graphicsLayer` and `drawWithCache` (`:77-100`) so a dragged sheet
drives it with zero recomposition. A counter (`SheetPresentationState.openCount`) supports stacked sheets
(`:109-121`): the screen stays pushed back until the last sheet closes.

### 5.5 Rubber band and overscroll (`IosOverscroll.kt`)

Rubber-band curve (UIKit's): `y = (1 - 1/(x*c/D + 1)) * D / c` with c = 0.55 and D = the container size on that axis
(`:47,70-75`). Properties: slope 1 at 0 (follows the finger initially), asymptote at D/c (about 1.8x the viewport),
and scaling with viewport size, exactly as iOS.

Mechanics (own-words description):
- Two separate counters per axis: `rawPull` (finger-driven accumulated overscroll) and `returnPull` (what's still
  springing back). Draw offset = rubberBand(raw + return). On touch-down, raw is moved into return (so a re-grab
  continues from the displayed position), return rebound cancelled (`:203-215`).
- Stretch is applied only during `UserInput` drags; fling deltas are *not* consumed into stretch so the scrollable can
  report leftover velocity to `applyToFling` (`:125-138`). After `performFling`, leftover velocity = initial -
  consumed; if any pull or leftover exists, start rebound (`:156-174`).
- Opposite-direction scroll first "pays down" existing stretch before scrolling the list (`:117-154`).
- Rebound: `animate(from = returnPull, to = 0, initialVelocity = clamp(leftover, +/-10000), spring(1.0, k))` with
  k = 247 normally and 130 when |v| > 5000 px/s (`:238-253,342-351`). Comment in `Motion.kt:~148`: the return is
  deliberately "velocity-adaptive (a hard flick gets a longer arc), which no single constant can express."
- `isInProgress` only reports true for *raw* pull so a still-returning list does not eat the next tap as a drag (`:104-106`).
- Offsets are applied in the node's `placeWithLayer { translationX = offsetX(); translationY = offsetY() }`
  (`:310-316`): a layout modifier that reads state in the layer block, so no relayout per frame.
- Container size is measured in the same node and used as D (`:300-309`).
- `EdgeAwareFlingBehavior` hands a fling that begins at a boundary to the overscroll effect instead of swallowing
  it in a snap fling (`:354-376`).
- Wired app-wide through an `OverscrollFactory` (`:320-338`).

Hero pull-zoom (`MotionModifiers.kt:50-236`): at the top of a hero list, pull offset = rubberBand(raw+return, viewport);
the list is translated (`graphicsLayer { translationY }`, `:103`) and hero artwork scale = `1 + clamp(offset / maxPull(220dp), 0, 1) * 0.18` (`:66-68`).
Bottom-edge pull is deliberately excluded (comment: "dragged the whole list and left it stuck", `:186`). Pointer events are observed in the
Initial pass to detect touch-down/up independent of children (`:104-113`).

### 5.6 Context-menu with preview (`AppleContextMenu.kt`)

A thorough long-press choreography; the code header says it is timed against a 30 fps Apple Music reference:
press feedback frames 0-8, haptic + page dim frame 9, item expansion 10-14, menu expansion 12-15 (`:72-78`).

1. `awaitEachGesture`: on first down, start the 300 ms press ramp (scale to 0.96, black overlay up to 14%) (`:114-117`).
2. Wait up to `longPressTimeoutMillis`; resolve to one of three outcomes: lifted before timeout (tap), cancelled
   (moved past touch slop, consumed by list/child), or timed out (menu) (`:121-131`). The check happens after children
   have seen the event (Main pass), so a button inside the item keeps its own tap.
3. On timeout: haptic LongPress, `drag.snapTo(0)`, open, and the SAME gesture keeps running: finger still down now
   drags the preview. Events are consumed in the **Initial** pass so the lift does not fire a child `clickable`
   (`:136-150`).
4. Drag mapping = a cheap rubber band: x * 0.6, up * 0.3, down 1.0 (`:178`).
5. On lift: dismiss if dragged down > 64 dp or > 40% of the way back toward home; else spring back with spring(0.75, 500) (`:151-160`).
6. Overlay: preview card scales from `anchor.width / cardInnerWidth` to 1 with a computed translation so that at
   progress 0 its content sits exactly on top of the source item (`:307-322`). Card background alpha follows progress;
   the *source item fades out only through the final 30% of the landing* (LandFade) while the preview fades in so
   there is never a one-frame swap of two different texts (`:213-227,408-409`).
7. Menu panel grows from the card's corner: `scaleX = 0.25 + 0.75x`, `scaleY = 0.08 + 0.92y`, with *independent springs*
   for x (zeta 0.62, k 560, quick) and y (zeta 0.78, k 330, slower) so it widens before it gets tall; on exit a 140 ms
   tween; alpha = clamp(3*p); during enter a blur of up to 20 dp clears as it opens (RenderEffect, API 31+)
   (`:272-274,335-355,399-406`).
8. The popup is a full-window `Popup` with `PopupPositionProvider` returning (0,0), so layout, scrim and card live in
   one coordinate system (`:286-290,415-421`). Anchor rect is captured via `onGloballyPositioned` into a plain
   array, **not snapshot state** (comment: as state it recomposed every tile per scroll frame, `:94-100`).
9. Placement rules: card width = min(78% of window, 340 dp), shrunk to fit menu + text block; keeps the source side
   (right half -> trailing alignment); clamped inside 16 dp margins and system bars (`:357-392`).

The simpler popup variant (`IosContextMenu.kt`): menu scales from the anchor's size per axis (clamped to >= 0.08) about
the *anchor's centre* as transform origin; alpha = clamp(p/0.35) so it is visible as a faint blob in the first frame;
opens above if there is no room below (always, for a bottom bar); exit is a plain fade-and-shrink without blur
(`:196-262`).

`OverlayMenu` (popup menu for generic overflow): X scale springs (0.72, 700) and Y springs (0.78, 420) from 0.12 with
alpha tween 90 ms; close springs both to 0.12 with (1.0, 900) and fades 140 ms after a 40 ms delay (`:105-117`).

---------------------------------------------------------------------------------------------------

## 6. Momentum projection (Apple's "Designing Fluid Interfaces" formula)

`U/utils/FlingProjection.kt:21-56` implements the projection described in WWDC 2018: instead of snapping to the closest
target to where the finger *lifted*, compute where the motion would come to rest and snap to the closest target to that.

```
projectedOffset(velocityPxPerSec, rate = 0.998) = (velocity / 1000) * rate / (1 - rate)
rest = current + projectedOffset
snap = argmin over points |point - rest|     // ties: prefer the one in the direction of travel
```
The in-code comment is explicit that the physics-textbook `v^2 / 2a` is *not* what Apple ships. 0.998 = normal scroll
deceleration, 0.99 = snappier. Used by swipe rows, sheets (`IosSheet.kt:265-266`). The mini-player title swipe uses
a simpler rule (average speed > 0.55 px/ms and > 25% of threshold, or distance beyond a screen-fraction threshold
`width * (0.45 - 0.33 * sensitivity)`, `FloatingMiniPlayer.kt:243-248,381-387`).

---------------------------------------------------------------------------------------------------

## 7. Squash, stretch, morphing

### 7.1 Velocity squash/stretch on glass pucks
See 5.1: scaleX divided and scaleY multiplied by factors of the normalised drag velocity, clamped to +/-0.2, with
independent spring constants so the bulge settles asymmetrically.

### 7.2 Tab-pill-to-circle morph and folding bar
- Collapsing bar: the pill's right edge retracts into a circle sharing the same shared-bounds key ("tabPill") so
  there is a continuous resize and not a cross-fade of two trees (`FloatingTabBarMotion.kt:34-54`).
  Enter/exit fades inside the shared node are very short (60/70 ms) just to hide the swap.
- The selected tab icon travels as a `sharedElement("navIcon")` on a *non-overshooting* curve; only the pill body
  overshoots. User feedback quoted in code: "overshoot of the surface or body only, not the icons or items"
  (`BarMotion.kt:176-184`). Implemented by `AxisCurve`: centre uses `easing`, extent uses `sizeEasing`
  (`:185-209`).
- `foldGeometry` computes every coordinate (height, row Y, widths, search position, selected icon X, player X/Y) from
  `fold` and `search` progress. `fold` is allowed to overshoot 0..1 under a spring; sizes are clamped, positions are
  not (`FoldGeometry.kt:37-59`).
- The labels get their own faster clock so they vanish on the first frame of a collapse and arrive last on expand
  (reference-video comment, `FloatingTabBarTabs.kt:570-593`).
- Icons stay fixed-width while the shell shrinks, and the shell clips only while morphing
  (`derivedStateOf { transition.currentState != targetState }`) so elastic effects at rest are not cut off
  (`:266-283`).
- **Gooey merge**: render the group into an offscreen layer, blur by up to 12 dp, then push alpha through a steep
  linear threshold (slope 10 about 0.5) via `ColorMatrixColorFilter`; this fuses nearby shapes and makes a
  connecting neck. Zero cost at rest (radius 0 -> no offscreen layer), blur radii cached by integer pixel
  (`GooeyTransition.kt:1-79`; peak constant `FloatingTabBar.kt:163`). API 31+ only.

### 7.3 Elastic stretch of small glass buttons (`GlassCircleButton.kt:63-91`)
Per-frame graphicsLayer: scale = `1 + magnitude * 4dp / height * press`; translation follows the finger with
`maxOffset * tanh(0.05 * offset / maxOffset)` on each axis (soft-saturating); scaleX/Y additionally grow by
`4dp/height * |cos(angle)| * |offset.x|/max` etc., so the button bulges in the direction the finger drags.
Magnitude 0.6 for inline pills, 1.2 for wide pills ("toned down per user: 'way too much'", `FloatingMiniPlayer.kt:~254`).

### 7.4 Mini player to full player morph
Core idea: **do not recompose two UIs; record them as `GraphicsLayer`s and draw a card between them in the draw phase.**
- Pill surface, pill content, full background and full content are recorded into `GraphicsLayer`s
  (`PlayerMorphLayers.kt:24-60`), and a single `Spacer` with `drawWithCache` reads `progress()` and draws, each frame,
  a clipped rounded rect interpolating from the pill rect to the full-screen rect (`PlayerContainerMorph.kt:123-240`).
  Plain **linear** rect interpolation, because "the spring driving progress already shapes the motion, so the rect
  itself adds no second curve" (`PlayerSheetMotion.kt:43-58`).
- Artwork: centre X eases out-cubic, centre Y is `0.4 * linear + 0.6 * ease-in-cubic`, size is uniform scale
  (`:112-141`) so the cover leads horizontally then drops vertically, and a square cover never stretches; a later
  `sharedArtworkWindow` variant interpolates width/height separately to land on a non-square hero (`:149-160`).
- Cross-fade windows (all in progress units, not time): layer hand-off at 0.2, mini content gone by 0.3 (ease-out
  cubic), full content from 0.1 to 0.6 (ease-in cubic), glass sampling unfreezes at 0.75 (`BottomSheet.kt:64-80`;
  `PlayerContainerMorph.kt:102-116`).
- Corner radius: lerps from pill half-height to the **physical screen corner radius**, then after settle goes to 0
  over a short 140 ms (separate clock), gated on a 2%-wide band at the top of progress so the first pixel of a
  downward drag does not snap the corners back (`PlayerSheetMotion.kt:17-21,178-263`).
- Close landing: sheet stops on its bound but the *card keeps control*: the arrival velocity (dp/s) is turned into a
  dip below the slot: `dip = maxDip * tanh(-overshoot * travelPx / maxDip)` (linear near zero to keep the arrival
  speed, saturating for hard flings) (`PlayerMorphLayers.kt:47-72`), and the tab-bar pill and side circles echo 40% of
  that dip (`FloatingTabBarMotion.kt:85-95`). The pill shadow is hidden during flight and tweens back in 320 ms
  (`FloatingMiniPlayer.kt:255-264`).
- Predictive back previews the sheet: `eased = 1 - (1 - f)^2`, travel = 35% of the collapse distance, using `snapTo`
  with UNDISPATCHED start (`BottomSheet.kt:490-500`).

### 7.5 Play/pause swap
`AnimatedContent` with `fadeIn(200) + scaleIn(0.3)` against `fadeOut(160) + scaleOut(0.3)` and
`SizeTransform(clip = false)`; layout size constant. Comment explains the 0.3 start scale: at 0.85 the swap
reads as a hard cut (`AnimatedPlayPauseIcon.kt:40-62`, `Motion.kt:112-124`).

### 7.6 Elastic volume slider (React-Bits-style) (`ElasticVolumeSlider.kt`)
Drag past either end is turned into an *overflow* (px beyond the track) that is soft-capped by a sigmoid:
`cap = 2*(1/(1+exp(-x/max)) - 0.5) * max`, max 28 dp (`:62,246-259`). The live drag writes plain float state
synchronously (comment: "drag callbacks aren't suspend, so the write must be synchronous"); only the release springs
(0.5, Medium) from the live overflow to 0 (`:92-96,138-149`). Entering an overflow region triggers a one-shot 1 -> 1.4 -> 1
icon pulse (120 + 130 ms) (`:111-127`). Track height and scale animate with MediumBouncy/Low while dragging (`:98-108`).

### 7.7 Hero pull zoom
See 5.5: pulling past the top grows the hero artwork by up to 18% and moves the list with rubber-band damping.

---------------------------------------------------------------------------------------------------

## 8. How jank is avoided

1. **Read animated state in the draw/layer phase.** `Modifier.graphicsLayer { translationX = anim.value }`, `drawBehind {}`,
   `drawWithContent {}`; lambdas for providers (`progress: () -> Float`, `selected: () -> Boolean`). Exemplars:
   sheet translation and scrim alpha from the same Animatable (`IosSheet.kt:157-161,217`); overscroll
   `placeWithLayer` (`IosOverscroll.kt:310-316`); sheet-presentation lambda overload whose KDoc says "zero-recomposition
   120Hz smoothness" (`IosSheetPresentation.kt:72-100`); landing echo and pill-shadow read in `graphicsLayer`
   (`FloatingTabBarMotion.kt:93-108`); tab-label alpha (`FloatingTabBarTabs.kt:352-355`).
2. **`compositingStrategy = ModulateAlpha`** for plain alpha fades to avoid an offscreen buffer
   (`FloatingTabBarTabs.kt:325-329,352-355`); `Offscreen` only when really required (edge-fade mask, gooey)
   (`FloatingMiniPlayer.kt:~65-85`, `GooeyTransition.kt:404-416`).
3. **No path clips on animating shapes**: a path clip makes Skia rasterise a coverage mask on the CPU and upload it
   every frame. They set `clip = false` and let fills/shadows/glass follow the outline, enabling the clip only while
   pressed (`clipWhilePressed`, `FloatingTabBarMotion.kt:110-120`; comment at `FloatingTabBarTabs.kt:300-303`).
4. **Plain arrays instead of snapshot state for cross-phase scratch values**: scroll timestamps, anchor rects, drag
   clock, "goes above" flag, placed offsets. Reason repeated in comments: an observable write from layout invalidates
   the layout that just made it; a snapshot read in draw adds a dependency that invalidates every frame
   (`AppleContextMenu.kt:94-100`, `IosContextMenu.kt:136-139`, `BottomSheet.kt:~335`, `BackdropFreeze.kt:23-27`,
   `FloatingTabBar.kt:399-407`).
5. **Freeze expensive backdrop captures while motion runs.** Glass pulls a recorded `GraphicsLayer` backdrop; they
   freeze its re-recording while scrolling (plus a 120 ms tail, `ScrollFreezeTailNs`, `FloatingTabBar.kt:184`),
   dragging a sheet (900 ms safety, `BottomSheet.kt:85`), during nav transitions (330 ms window,
   `backdrops/NavTransitionFreeze.kt:17`) and screen entry (300 ms, `BackdropFreeze.kt:20`). Measured in a comment:
   47 ms of `layer.record` per scroll frame with freeze off vs 1.9 ms on (`BackdropFreeze.kt:~15-19`). This is also
   why the push duration is a trade-off (stale-backdrop window).
6. **Frame budget-aware visibility**: infinite animations (marquee) are only attached while on screen, using
   `boundsInWindow().isEmpty` as the signal (`MotionModifiers.kt:238-263`), because a perpetual marquee in every
   off-screen lazy item redraws the whole app every vsync.
7. **Cache render effects** keyed by integer pixel radius (`GooeyTransition.kt:381-392`) and build static
   `ColorMatrixColorFilter` once (`:370-379`).
8. **Synchronous drag writes**: `CoroutineStart.UNDISPATCHED` for `snapTo` inside input handlers
   (`BottomSheet.kt:638-644,497`); direct state writes where callbacks are non-suspending.
9. **`derivedStateOf` for thresholds** (pressed = progress > 0, morphing = current != target, panelOffset from the animatable) to prevent
   recomposition on every float change (`FloatingTabBarTabs.kt:156-163,262-264,269-274`).
10. **Run one clock per visual object.** Comment in `FloatingTabBarMotion.kt:56-64`: the earlier multi-clock version
    (separate stagger and bounce springs on the same pixels) caused an "icon pop"; they went back to one fixed-duration
    tween shared by size, fades and shared bounds. Also: `skipToLookaheadSize` on tabs while the shell animates
    (`FloatingTabBarTabs.kt:370`) so content does not re-layout.
11. **Reduce motion** is an event-driven gate at the spec level (`Motion.kt:~131`), so it cannot be forgotten at a call site.
12. **Compose-specific caution on `Animatable` thresholds**: visibility thresholds are set explicitly (`0.001f` for unit
    values, `Offset.VisibilityThreshold`, `IntOffset(1,1)`) so springs end on a definite frame (`Motion.kt:33-35`,
    `DampedDragAnimation.kt:45-55`, `InteractiveHighlight.kt:44-47`).

---------------------------------------------------------------------------------------------------

## 9. Navigation motion

`NavigationBuilder.kt:244-263`: for a normal push, incoming = `slideInHorizontally { it/3 }` + fade, 400 ms with the
UIKit curve; outgoing = `slideOutHorizontally { -0.30 * width }` + fade to alpha 0.85 (depth parallax: outgoing moves
shorter and slower). Pop reverses. Predictive back uses the same transition, so exit transitions for destinations that
get scrubbed are **tweens, not springs**, with an explicit comment: "Tween, not spring: predictive back scrubs this by
gesture progress" (`:779-780`; also `Motion.kt:~99-110` "appearExit"). Sheet/detail slides: collection push uses
spring(1.0, 380) (`NavigationBuilder.kt:756`), detail routes use 400 ms FastOutSlowIn, others fade.
Hero artwork/titles are `sharedBounds` with `renderInOverlayDuringTransition = true`, `zIndexInOverlay = 2f` and the
350/500 ms critically-damped easing (`SharedArtwork.kt:182-190,320-330`); transition-running flags
(`artworkTransitionRunning()`, `:137-140`) let screens pause heavy work during the flight.

---------------------------------------------------------------------------------------------------

## 10. Staggering

Convx staggers *within a single object's axes and layers*, not across list items:
- Mini accessory: y leads on expand (0 ms) and x follows 33 ms later; on collapse x leads and y follows 67 ms later,
  measured from a 30 fps iPhone recording (`BarMotion.kt:296,319-330`).
- Card entrance in a hero transition: "body staggers 70 ms after" the morph (`Motion.kt:50-54`).
- Tab labels: delayed to arrive after the shell; keyframes hold alpha at 0 until 50% of 150 ms (`FloatingTabBarTabs.kt:580-614`).
- Context menu axes: width spring stiffer than height spring (menu widens first) (`AppleContextMenu.kt:272-274`).
- Content layers in the player morph use progress windows (0.1-0.6, 0.2, 0.3), so no layer is a straight cross-fade.
No per-item list stagger (cascade) was found; lists use `Modifier.animateItem()` only.

---------------------------------------------------------------------------------------------------

## 11. Provenance and licensing notes for our implementation

- `DampedDragAnimation.kt` and `InteractiveHighlight.kt` headers state they are "Ported one-to-one" from a reference
  (`DampedDragAnimation.kt:1-8` cites compose-miuix-ui contributors, SPDX Apache-2.0) and
  `hig/src/main/kotlin/zone/ien/hig/utils/DampedDragAnimation.kt:32-36` carries the same constants. `FloatingTabBarMotion`
  comments name `Kyant0/AndroidLiquidGlass`. So the *puck* physics (spring 1/1000, 0.6/250, 0.7/250, 0.5/300 and the
  velocity-squash formula) come from an Apache-2.0 upstream. For Rovsti, if we want that exact behaviour, go to the
  upstream project directly for the code, honour its licence in NOTICE, and cross-check against the values in this
  report. Otherwise re-implement from section 5.1 (the numbers are facts, the code is ours).
- The `hig` module (`hig/LICENSE`) is a separate vendored Cupertino library; its licence should be read before any reuse. We do not need it.
- Everything else in `U/utils/Motion.kt`, `IosOverscroll.kt`, `AppleContextMenu.kt`, `IosSheet.kt`, `BarMotion.kt`,
  `PlayerSheetMotion.kt` carries `Convx Project (C) 2026, GPL-3.0` headers: **do not copy**, re-derive from this text.
- The rubber-band constant 0.55, the 0.998 deceleration, and cubic-bezier numbers are Apple/UIKit public values or numeric
  facts and are fine to use.
- Observed inconsistencies worth not inheriting: (a) `PushMillis` doc says 260 ms, value 400 (`Motion.kt:148-157`);
  (b) `Motion.collectionPush`'s intent vs. `NavigationBarAnimationSpec` still using Compose defaults (MediumBouncy/Low) which
  is quite bouncy and slow (`Dimensions.kt:65-68`); (c) `DIAG_SLOWDOWN` diagnostic constants left in production code
  (`FloatingTabBar.kt:160`, `BottomSheet.kt:94`).

---------------------------------------------------------------------------------------------------

## 12. Recipe for Rovsti (Kotlin + Compose, our own code)

Package suggestion: `rovsti.ui.motion` in a `:core-ui` module. Rule: **one place defines every spec; no call site
writes a raw number.**

### 12.1 Tokens (our own set, derived from the table in section 2)

Start with these (Compose mass 1; all values tunable from one file):

| Token | Spring | Use |
|---|---|---|
| `Press` | zeta 0.75, k 1200 (scale to 0.96) | every tappable |
| `Select` | zeta 0.78, k 750 | tab indicator, segmented thumb, chip selection |
| `Appear` | zeta 1.0, k 450 | things that arrive in place (banners, chips, inline editors) |
| `Sheet` | zeta 0.86, k 380 (settle), zeta 1.0 k 400 (dismiss) | sheets, detents, bottom panels |
| `Morph` | zeta 1.0, k 900 | shared container resizes |
| `Rebound` | zeta 1.0, k 247; k 130 if |v| > 5000 px/s | overscroll return |
| `Menu` | x: zeta 0.65 k 560; y: zeta 0.78 k 330 | popovers and context menus (exit: 140 ms tween) |
| `Knob` | zeta 0.75, k 900 | switch/slider knobs |
| Push | 400 ms tween, `cubic(0.32,0.72,0,1)`, parallax 30%, dim to 0.85 | screen transitions |
| Fade | 120-200 ms tween, FastOutSlowIn | icon swaps, content swaps |
| Seamless | 900-1100 ms `cubic(0.37,0,0.63,1)` | ambient colour changes |

Wrap with `fun <T> gate(reduceMotion, spec) = if (reduceMotion) snap() else spec` and read the system animator-duration
scale once.

Always set `visibilityThreshold` (0.001 for unit values, 0.5 px for offsets) so springs terminate.

### 12.2 Rules that make it fluid (checklist for every component)

1. State that changes by gesture lives in an `Animatable` (or a plain `mutableFloatStateOf` during drag + `Animatable`
   after release). During a drag: `snapTo` only. After release: `animateTo(target, spring, initialVelocity = v)`.
2. Pick the target by **projected end point** (`rest = pos + v/1000 * 0.998/0.002`) against snap points, not by the
   release position. Sheet dismiss threshold: projected beyond the lowest detent + ~12% of height.
3. Read animation values only in `graphicsLayer { }`, `drawBehind`, `drawWithContent`, layout `placeWithLayer { }` or
   lambdas (`() -> Float`). Never pass a raw `Float` that changes per frame down the composition.
4. Cancel-and-replace job management with a generation counter for anything with a late callback (fling end, rebound).
5. Use `CoroutineStart.UNDISPATCHED` for `snapTo` inside pointer handlers where lag would be visible.
6. Overshoot only on the **surface** (container size, thumb), not on the content inside it; keep text/icons on
   critically damped curves.
7. Scrub-able transitions (predictive back, drag-to-dismiss) must be tweens/progress-driven; settle phases are springs.
8. Press = scale 0.94-0.97 + optional soft brightness; remove ripple on custom surfaces.
9. Keep one clock per object: if two animations must end together, use a fixed-duration tween with a spring-shaped
   easing (see 12.4), not two springs.

### 12.3 Components to build (maps to owner complaints)

**a) `Modifier.pressable(onClick, onLongClick)` (tapping must not push content around)**
- Implement as a `DrawModifierNode`/`graphicsLayer` scale only, driven by `collectIsPressedAsState` read in the layer
  block. Never change layout size on press. This directly fixes "tapping made buttons flow INTO the content and push it":
  all feedback is transform/alpha in the layer, so layout is untouched.

**b) `RovstiOverscroll` (rubber-band for every scroller)**
- Implement `OverscrollEffect` with two accumulators (raw + returning), curve
  `y = (1 - 1/(x*0.55/D + 1)) * D/0.55`, spring(1, k) rebound seeded with leftover fling velocity, offsets applied in
  `placeWithLayer`. Provide via `LocalOverscrollFactory`. Do not consume fling deltas into stretch. Only treat the
  pull as "in progress" when the finger is down.

**c) `ModalSheet` with detents (Medium/Large/Custom)**
- One `Animatable<Float>` for offset; scrim alpha and the screen-behind scale/dim derived from it in layer lambdas.
- Presenting screen: scale 0.91 about top centre, corner radius 32 dp, dim 22%. Counter for stacked sheets.
- Content scroll hand-off via `NestedScrollConnection` (only drag the sheet when the child is at top).
- Grabber stretch via `tanh(drag/320dp)`.
- Predictive back: map progress (eased `1-(1-p)^2`, up to 35% of travel) to `snapTo`, commit with spring.

**d) Popover / context menu (what was missing in the GPUI build)**
- Full-window `Popup` at origin (`PopupPositionProvider` returning zero) so scrim, lifted item and menu share coordinates.
- Open state in `MutableTransitionState` + `rememberTransition`; separate specs per direction (spring open, short tween close);
  keep composed until `currentState == targetState == false`.
- Menu panel grows from the anchor with per-axis scale (start = anchor size / panel size, floor 0.08), transform origin
  at the anchor centre (or the card corner), alpha ramp over the first third, optional blur-in on API 31+.
- Lifted copy of the source scales 1.05 over 150 ms; scrim 0.28 (dark) / 0.14 (light).
- Placement: below if room else above, horizontally centred and clamped to 12-16 dp margins and insets, side-aware.
- Long-press with preview: do the three-outcome `awaitEachGesture` (tap / cancelled / long) with press ramp 300 ms,
  haptic on trigger, keep consuming the same finger in the Initial pass to drag the preview; dismiss on 64 dp
  down or 40% back-toward-home.
- Store anchor rects in plain holders (array), not snapshot state, so scrolling lists don't recompose.
- Use `HapticFeedbackType.LongPress` on open and `SegmentTick` style feedback on highlighted item changes.

**e) Swipe actions row**
- Behind-the-row buttons, `graphicsLayer` on the content, projected-endpoint snapping, full-swipe commit at ~55%
  width, spring(1, 500) with release velocity. Contacts/Messages rows (archive, delete, call) and call-log rows.

**f) Selection indicator / segmented control / tab bar**
- Reusable `DragPuck` (section 5.1) generalised: logical position in item units, snap while dragging,
  spring(1, ~900-1000) after, squash/stretch from normalised velocity limited to +/-20%, press bulge 1.1-1.4x.
- Tab bar must not cover content: reserve its height as content padding (`WindowInsets` + measured height) and let
  lists scroll under it with fade; bar auto-minimise on scroll uses hysteresis (collapse threshold larger than expand
  threshold, accumulated scroll reset on direction flip, smoothed velocity EMA 0.35) (`FloatingTabBar.kt:187,448-470`).
  For Rovsti a simple version is enough: translate down ~40 dp and fade 35% on downward scroll, spring(0.9, 500).

**g) Switch, slider, stepper**
- Switch: 51x31 dp, knob 27 dp, travel 20 dp, `Knob` spring, colour tween 200 ms; make the whole thumb draggable using DragPuck
  (tap toggles; drag past 50% commits).
- Slider: knob 1.12x on drag with spring(0.7, 900); optional elastic overflow (sigmoid cap) for volume-like controls.

**h) Nav bar large-title collapse**
- `collapsedFraction = clamp(firstVisibleItemScrollOffset / 120dp)` via `derivedStateOf`, smoothed with spring(1, 500), applied to
  alpha and translation in `graphicsLayer` (never to layout height of list content).

**i) Stops: full-screen map with floating chrome**
- Controls are overlay layers over a full-bleed map; all show/hide uses translate/alpha in layers with the `Appear` spring.
- Bottom info sheet: detents (peek / half / full) driven by the sheet from (c); the map camera padding follows the sheet's
  *settled* detent (not per-frame) to avoid map relayout; floating buttons ride the sheet via `graphicsLayer` translation = min(sheetOffset, limit).
- Floating search/pill: elastic stretch on press (section 7.3, magnitude ~0.6-1.0) is cheap and distinctive.
- While the map is being panned, freeze any blur/backdrop recapture (see 8.5) to keep 60-120 fps.

**j) Text input (IME geometry)** is out of scope of motion, but the caret/selection handles should animate with 100-150 ms fades and the
   popup toolbar should use the same `Menu` spring from the caret anchor.

### 12.4 Helper code to write ourselves (descriptions)

- `SpringEasing(zeta, omegaT = 7.8)`: implement the closed-form step response above; add the linear correction so
  `f(1) == 1`. Use it for two-clock morphs. Unit-test: f(0)=0, f(1)=1, max overshoot within 1% of
  `exp(-zeta*pi/sqrt(1-zeta^2))`.
- `Rubber.band(raw, dim, c = 0.55f)`: pure function, unit-test monotonic, slope 1 at 0, bounded by `dim/c`.
- `Projection.rest(pos, v, rate = 0.998f)` and `nearestSnap(...)`: pure, JVM-tested (including tie-break toward direction).
- `GenerationGuard`: tiny helper wrapping "increment on start, compare in finally".
- Compose tests: use `mainClock.autoAdvance = false` + `advanceTimeBy` to assert settle values and interruption (start a
  spring, interrupt at 40% and check velocity continuity, i.e. no jump larger than 1 frame of travel).
- Roborazzi screenshots at mid-animation states (set `mainClock.advanceTimeBy(n)`) to verify squash/overshoot visually.

### 12.5 Performance gates to adopt from day one

1. Lint/ktlint or review rule: no `Float` param named `*progress` that is not `() -> Float` for composables that animate.
2. Baseline profile + `JankStats` in debug builds; budget: no recomposition counts > 1 during a 1-second drag
   (verify with Layout Inspector / `Modifier.recomposeHighlighter` in a debug-only wrapper).
3. Avoid path clips on moving shapes (use `RoundedCornerShape` clip or no clip), avoid `Offscreen` compositing unless needed.
4. Gate infinite animations on visibility; avoid marquee in every list row (for Rovsti: no marquee in lists at all).
5. If we use blur/glass: cap blur radius while scrolling and reuse last capture for ~120 ms after scroll end.
6. Always provide reduce-motion path through the token gate.

### 12.6 Suggested order of implementation
1. Tokens + helpers + pure-function tests (JVM).
2. `pressable` + `RovstiOverscroll` (affects every screen immediately).
3. `ModalSheet` + presentation scale-back + predictive back.
4. Popover/context menu + swipe row.
5. `DragPuck` -> tab bar, segmented control, switch, slider.
6. Navigation transitions (push/pop with parallax, shared bounds for avatar -> detail).
7. Stops map chrome (floating controls + sheet).
8. Polish: gooey/blur options, haptics, reduce-motion audit.

---------------------------------------------------------------------------------------------------

## 13. Quick reference: value cheat sheet

- Rubber band: c = 0.55; return spring zeta 1, k = 247 (130 above 5000 px/s), seed velocity clamp +/-10000.
- Fling projection: rate 0.998 (0.99 snappier).
- Press: 0.94-0.96 scale, k ~900-1220, zeta ~0.7-0.75.
- Sheet: settle zeta 0.86 k 380; dismiss zeta 1 k 400; scrim 0.32; behind-scale 0.91, radius 32 dp, dim 0.22.
- Menu: scrim 0.28/0.14, panel start scale 0.08, width spring (0.62, 560), height spring (0.78, 330), exit 140 ms.
- Puck: pos (1, 1000), squash X (0.6, 250), Y (0.7, 250), vel (0.5, 300), press scale 1.39 (tab) / 1.5 (thumb), velocity clamp +/-0.2.
- Push: 400 ms, cubic(0.32, 0.72, 0, 1), parallax 0.30, dim 0.85.
- Icon swap: 200/160 ms, scale 0.3.
- Morph card: 340 ms open / 260 ms close, cubic(0.3, 0.5, 0.4, 1).
