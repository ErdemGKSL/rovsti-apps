# Convx overlays: research report for Rovsti Kotlin UI

Scope: popovers, context menus, dropdown menus, sheets, dialogs, toasts in Convx
(cosmictaserdev-creator/Convx, Kotlin + Jetpack Compose). How each is triggered, how the source lifts and the page dims,
placement and flip math, the source-to-menu morph, enter/exit specs, dismissal, submenus, preview, action sheets,
detent sheets and drag physics, which window layer is used, z-order, focus, accessibility, list stability.

Licence guard: Convx is GPL-3.0, Rovsti is MIT/Apache. Everything below is a description in our own words plus numeric
parameters (numbers and observed behaviour are facts, not code). Pseudo-code is original. No Convx source may be copied
into Rovsti. Convx's `hig` module is a different case (Apache-2.0, a vendored fork of Compose Cupertino, see section 12).

Path prefixes used in every citation:
- `U` = `app/src/main/kotlin/com/convx/music/ui` (example: `U/ios/component/IosMenu.kt:147`)
- `H` = `hig/src/main/kotlin/zone/ien/hig` (Apache-2.0 vendored Cupertino library)
- `A` = `app/src/main/kotlin/com/convx/music`

The git history of the checkout is a single commit, so `git log` gave nothing. The findings come from source and from
in-code comments, which often state the measured reference-video timings ("clip f9", "30 fps") that the numbers were fitted to.

Sibling reports: `convx-motion.md` (spring tokens, gestures, fling projection) and `convx-glass.md` (backdrop, lens, blur).
This report cites them instead of repeating them.

---------------------------------------------------------------------------------------------------

## 0. The short answer: why Convx overlays feel good

1. **One overlay never owns layout.** Every overlay is drawn in its own layer above the page (a popup window, a dialog
   window, or a full-size sibling box). Opening a menu never changes the size or position of anything underneath, so
   nothing "flows into" or gets pushed by it. Trigger buttons keep their exact size.
2. **A menu is a morph, not a fade.** The panel is a scaled copy of itself that starts at the size and position of the
   pressed thing, grows with two different springs (width leads, height lags), is heavily blurred on its first frames,
   and the blur clears as it lands. Exit is plain and faster. (section 2, `U/ios/component/IosContextMenu.kt:145-226`)
3. **The pressed thing is lifted, not replaced.** Either a copy is drawn above the scrim at the same coordinates, scaled
   about 5 percent (simple menu), or the item becomes a floating preview card that travels to a new place and back
   (Apple-style menu). The original is hidden by alpha, never removed from the layout. (sections 2, 3)
4. **The finger stays live.** A press on a menu panel tracks the finger across rows, highlights the row under it, ticks a
   haptic when the row changes, and commits on release. "Press, slide, release" works from the trigger as well. (section 4)
5. **Sheets are position-driven, spring-settled and projection-aware.** The sheet offset is a single animated float; the
   finger writes it with `snapTo`, the release picks a detent from the *projected* endpoint, a spring carries the release
   velocity. The page behind scales back, rounds, and dims with the same progress. (section 7)
6. **Everything is retargetable.** Open and close share state; a tap during a close re-opens from where it is.

What Convx does badly (do not inherit, details in section 11): no accessibility semantics at all in the overlay code,
no submenus, no exit animation for dialogs and for programmatic sheet dismissal in the in-window sheet, no rubber band
past the top detent, a global "last touch point" hack, and Popup windows for the context menu (not testable by pixel).

---------------------------------------------------------------------------------------------------

## 1. Inventory: which overlay is which, and which window it lives in

| Overlay | Component | Window layer | Citation |
|---|---|---|---|
| Hold-to-open menu, simple (lift + panel) | `IosContextMenu` / `IosContextMenuOverlay` | `Popup` window, full-size via a position provider returning (0,0), `focusable = true`, `usePlatformDefaultWidth = false` | `U/ios/component/IosContextMenu.kt:170-173, 279-287` |
| Hold-to-open menu, Apple Music style (preview card + panel) | `AppleContextMenu` | same: full-window `Popup`, focusable | `U/ios/component/AppleContextMenu.kt:286-290, 415-422` |
| Tap-open "pull-down" menu (SwiftUI `Menu`) | `IosMenuAnchor` | anchored `Popup`, focusable, default placement | `U/ios/component/IosMenu.kt:320-344` |
| Dropdown (Material API reskinned) | compat `DropdownMenu` | anchored `Popup` with own `PopupPositionProvider` | `U/ios/compat/IosCompatMenus.kt:124-243` |
| Dropdown in the vendored library | `CupertinoDropdownMenu` | anchored `Popup`, `focusable = true, clippingEnabled = false` | `H/CupertinoDropdownMenu.kt:121-166` |
| Touch-point menu ("more" buttons, about 30 call sites) | `OverlayMenu` | NOT a window: a full-size sibling `Box` in the activity content, with its own scrim and `BackHandler` | `U/component/OverlayMenu.kt:85-210` |
| Detent sheet, own implementation | `IosSheet` | NOT a window: sibling boxes in a window-filling `Box`. Doc says why: a second window cannot sample the app backdrop, so glass would be empty | `U/ios/component/IosSheet.kt:107-117` |
| Modal sheet used by almost all menus | compat `ModalBottomSheet` wrapping `CupertinoModalBottomSheet` | `Dialog` window, dim amount forced to 0, own scrim | `U/ios/compat/IosCompatTail.kt:122-165`, `H/CupertinoModalBottomSheet.kt:61-175` |
| Menu sheet (items list) | `BottomSheetMenu` / `MenuSheet` | in-window `AnimatedVisibility` box around the library sheet | `U/component/BottomSheetMenu.kt:107-205` |
| Full-screen page sheet (New Playlist) | `IosPageSheet` | `Dialog` window, `decorFitsSystemWindows = false` | `U/component/IosPageSheet.kt:212-279` |
| Alert | compat `AlertDialog` over `CupertinoAlertDialog` | `Dialog` window | `U/ios/compat/IosCompatFields.kt:171-224`, `H/CupertinoDialogs.kt:199-260` |
| Action sheet | `CupertinoActionSheet` | `Dialog` window | `H/CupertinoDialogs.kt:269+, 470-560` |
| App dialogs (prompt, list, text field) | `DefaultDialog`, `ListDialog`, `TextFieldDialog` | `Dialog` window | `U/component/Dialog.kt:65-123, 200-230, 249-` |
| Toast | `IosToast` inside Material `SnackbarHost` | slot in the scaffold | `U/ios/compat/IosCompatBars.kt:264-301` |
| Tooltip | `PlainTooltip` | inline box, no window | `U/ios/compat/IosCompatTail.kt:178-203` |

Z-order is simply "later window wins". There is no overlay manager and no stacking policy beyond one counter used for the
page scale-back (section 7.6).

---------------------------------------------------------------------------------------------------

## 2. Simple hold-to-open context menu (`IosContextMenu`)

### 2.1 Trigger

- The wrapper is a plain `Box` around the content. It records its window bounds into state on every layout and runs one
  tap-gesture detector with a long-press callback and a tap callback.
  `U/ios/component/IosContextMenu.kt:77-96`
- Long press = the platform default timeout from the gesture detector, then one `LongPress` haptic, then `open = true`.
  `:87-89`
- A tap calls the optional `onClick` instead, so the item stays clickable. `:90`
- A variant `IosContextMenuOverlay(anchor: Rect?)` exists for callers that detect their own long press (the floating tab bar
  cells, which cannot give their gesture away). Non-null anchor opens, null closes, and the overlay stays composed until the
  exit has finished. `:109-125`, used at `U/component/FloatingNavBar.kt:293-299, 404`.

### 2.2 Layers and what moves

Inside the popup, three things are drawn bottom to top: a full-size black scrim, a lifted copy of the pressed content, and
the menu panel. `:178-233`

- **Scrim**: black at 0.28 alpha, animated with a 180 ms tween on open and 140 ms on close. Drawn in `drawBehind`
  so its alpha is read in the draw phase. A tap anywhere on it dismisses. `:155-160, 178-184, 276`
- **Lifted copy**: the same `content` lambda composed a second time inside the popup, placed at the anchor's exact
  window coordinates, scaled 1.0 to 1.05 about its center (150 ms in, 120 ms out). `:162-168, 193-201, 260, 277`
  So nothing visibly moves when the menu opens; the item just grows slightly above the dim.
- **Panel**: see 2.3.

### 2.3 The morph (the important part)

One progress float `p` (0 closed, 1 open) drives the panel:
- open: spring, damping 0.78, stiffness 700. close: 150 ms tween on the cubic (0.4, 0, 0.9, 0.5). `:145-150, 271-272`
- alpha = `clamp(p / 0.35)`: fully opaque by 35 percent of the travel, so a faint blob is visible on frame one. `:204-206`
- scale per axis starts at `anchorSize / panelSize`, clamped to a minimum of 0.08, and interpolates to 1.
  Scale is *per axis*, so a wide button grows into a tall panel without distortion at the start. `:207-211`
- transform origin = the anchor's center expressed as a fraction of the panel rect, clamped to [0,1]. The panel therefore
  grows out of the pressed thing wherever that is relative to the panel. `:212-217`
- blur: a render-effect blur of up to 20 dp on the very first frame, fading to 0 as `p` goes to 1. Only while *opening*;
  closing uses no blur ("the reference's dismissal is a straight fade"). Skipped below API 31. `:218-225, 274-275`
  The comment explains the intent: "a blur that clears, not just an alpha ramp ... reads as glass condensing".

Measured target from the comment: the panel is up in about four frames at 30 fps (about 130 ms). `:55-59`

### 2.4 Placement and flip math

Done in a custom `Layout` that measures both children loose and places them. `:234-263`

```text
spaceBelow = windowHeight - anchor.bottom
above      = spaceBelow < menuHeight + gap + margin           // flip rule
menuY      = above ? anchor.top - gap - menuHeight : anchor.bottom + gap
menuY      = clamp(menuY, margin, windowHeight - menuHeight - margin)   // never off screen
menuX      = clamp(anchor.centerX - menuWidth/2, margin, windowWidth - menuWidth - margin)
```
`gap` = 8 dp, `margin` = 12 dp (`:68-71`). The code comment says "above when there isn't room below, which is always
the case for a bottom bar". Weakness: the clamp uses only `margin`, not the status and navigation bar insets, so on a
gesture-nav phone the menu can sit under the nav bar area. The Apple variant fixes that (3.5). Also the flag "goes above"
is written during layout into a plain array, not state, to avoid invalidating the layout that produced it. `:138-141`

### 2.5 Dismissal

Scrim tap, platform back (the popup is focusable so back goes to `onDismissRequest`), and choosing a row (the panel calls
`onDismissRequest` after the row's action). `:170-174, 182-184`; `U/ios/component/IosMenu.kt:153-156`.

---------------------------------------------------------------------------------------------------

## 3. Apple-Music-style context menu: preview card + panel (`AppleContextMenu`)

The more elaborate one, used for covers and tiles (`U/screens/HomeShelf.kt:454`, `U/screens/library/apple/AppleLibraryScreen.kt:601`,
`.../YtPopMenu.kt:150`). The header comment gives the reference timeline at 30 fps: press feedback frames 0-8, haptic and dim
at frame 9, item expansion frames 10-14, menu expansion frames 12-15. `U/ios/component/AppleContextMenu.kt:72-78`

### 3.1 Press phase (before the menu exists)

- While a finger is down the item sinks to 0.96 scale and a black veil of 0.14 alpha is drawn over it, both driven by one
  `press` float animated with a 300 ms tween toward 1. `:101-111, 116, 411-412`
- The gesture is hand-written (`awaitEachGesture`), not a tap detector, to resolve four cases at the long-press timeout:
  - finger lifted, nothing consumed: a tap, call `onClick`
  - the list claimed the gesture (scroll) or the finger moved past touch slop: cancel, no menu
  - a child already consumed the event (a "..." button inside the row): the child wins, no menu
  - timeout reached with finger still down: open. `:114-163` The explanation is in the comment at `:117-120`.
- On any end the press float springs back to 0 (damping 0.7, stiffness 600). `:132`

### 3.2 Open phase: finger still down

- On timeout: one `LongPress` haptic, drag offset reset, `menu.open()`. `:137-139`
- The same finger now drags the preview. The loop reads the *initial* pointer pass and consumes every event, so a
  clickable inside the item cannot fire on release. `:140-150` (`awaitPointerEvent(PointerEventPass.Initial)` at `:143`)
- Drag mapping is a rubber band: x scaled by 0.6, upward y scaled by 0.3, downward y 1:1. `:177-178`

### 3.3 Release rules

```text
home        = vector from card's resting place back to the item's place
backHome    = dot(drag, home)/|home| > 0.4 * |home|          // dragged 40 percent of the way home
if drag.y > 64dp or backHome:  close          // drag down or toward home dismisses
else:                          spring drag back to zero (damping 0.75, stiffness 500)
```
`:151-160`; 64 dp is `dismissDown` at `:113`.
While dragging, the panel fades with distance: `alpha *= 1 - clamp(dragDistance / 120dp)`. `:284, 339`

### 3.4 Lift and land

- Lift progress `p`: spring damping 0.8, stiffness 420 (slight overshoot). Land (close): damping 1.0, stiffness 520 so
  the card settles onto the item instead of bouncing. `:263-270`
- Preview card layer: scale from `itemWidth / cardInnerWidth` (clamped at least 0.05) to 1, origin top-left, translation
  interpolated from the item's top-left (minus card padding) to the resting top-left, plus the finger drag
  weighted by `p`. So at `p = 0` the card's *content* sits exactly on the item. `:307-332`
  Card: 22 dp continuous corner, padding 10 dp, fill white (light) or `#2C2C2E` (dark) faded in with `p`. `:278, 323-324, 413`
- The original item is hidden (alpha 0) the whole time the card is up. On the way back, over the last 30 percent of the
  travel (`LandFade = 0.3`) the item fades in under the landing card while the card fades out, because the preview text
  and the item text differ and a one-frame swap would show. `:213-228, 408-409`
- Page dim: black, 0.28 alpha in dark mode, 0.14 in light, scaled by `p`. No blur on the page. `:279, 292-298`

### 3.5 Panel under the card

- Panel grows with two independent springs: x with damping 0.62, stiffness 560 (bouncy, leads); y with damping 0.78,
  stiffness 330 (softer, lags). Exit: 140 ms tween on both. `:272-274, 399-406`
- Panel scale: x from 0.25 to 1, y from 0.08 to 1. Alpha = `clamp(3 * p)`. Origin: the top corner on the item's side (right
  corner when the item is in the right half). Same 20 dp enter-blur as the simple menu. `:335-355`
- Layout constants: side margin 16 dp, gap card to panel 8 dp, top limit = status bar + 8 dp, bottom limit = window height -
  nav bar - 16 dp. `:360-363`
- Card width = min(78 percent of window width, 340 dp), then shrunk so card + gap + panel + a 76 dp text block fit in the
  vertical space, never below 140 dp. `:365-371`
- Side logic: item center in the right half means right-align card and panel to the item's right edge, else left-align.
  Card Y = item top, but pulled up so card + panel fit above the bottom limit and never above the top limit. Panel is
  placed directly below the card. Everything clamped to the 16 dp side margin. `:373-382`
- Layout results (card target, home position, side) are written to a plain `FloatArray`, because they are read by the
  layer blocks on the same frame. `:204-208, 384-388`

### 3.6 Reorderable lists (hold means two things)

For the drag-to-reorder pinned grid the same menu is driven by an external controller: the reorder library's long press
calls `controller.open()` plus a haptic, and a separate observer watches the same pointer (never consuming it): if the
finger travels past touch slop while the menu is up, `controller.cancel()` replaces the state object so the menu vanishes
with no landing animation and the drag takes over. A quick lift counts as a tap. A dragged tile scales to 1.08.
`U/screens/library/apple/AppleLibraryScreen.kt:380-430`; `AppleContextMenu.kt:181-195, 239-243`

---------------------------------------------------------------------------------------------------

## 4. The menu panel itself (`IosMenu`), and press-slide-release

### 4.1 Structure and metrics

Data: an item has title, optional description line, icon, role (destructive), selected flag, enabled flag, click.
A section is a list of items with an optional small header. Optional "quick actions" row on top. `U/ios/component/IosMenu.kt:63-82`

| Part | Value | Citation |
|---|---|---|
| panel width | 238 dp | `U/ios/foundation/IosMenuMaterial.kt:72`, `IosMenu.kt:130` |
| panel corner | 32 dp continuous (smoothing 0.6) | `IosMenuMaterial.kt:63-71` |
| panel max height | window height - 24 dp, content scrolls | `IosMenu.kt:101-105, 131, 159` |
| panel vertical padding | 10 dp | `IosMenu.kt:160` |
| row | min height 44 dp, outer horizontal padding 10, inner start 12, end 14, vertical 12 | `:275-281` |
| row text | 17 sp, line height 20, letter spacing -0.43 | `:299-303` |
| description | footnote style, 2 dp under title | `:295, 305-311` |
| icon column | 28 dp wide, 8 dp gap | `:289-294` |
| check column (when any row in section is selected) | 24 dp + 6 dp gap, 16x12 dp checkmark, stroke 2 dp round | `:196-198, 283-288, 346-365` |
| destructive | system red text | `:271` |
| disabled | secondary label color | `:270` |
| separator | 21 dp tall slot, 1 dp line, inset 16 dp | `:211-227`, `IosMenuMaterial.kt:75` |
| section header | footnote, padding 22 start/end, 4 top/bottom | `:186-194` |
| quick action row | 56 dp tall, 10 dp side padding, 6 dp gap, equal weight; icon 22 dp over a 12 sp medium label | `:163-181, 229-259` |

### 4.2 Press, slide, release

This is the single most "iOS" behaviour. It lives in one pointer observer on the whole panel (it observes, never consumes):
`IosMenu.kt:135-158`.

```text
on finger down: hovered = rowAt(pos)
on every move:  now = rowAt(pos); if now != hovered and now >= 0: haptic(TextHandleMove); hovered = now
                remember "scrolled" if the event was consumed by the scroll container while moving
on up:          if hovered >= 0 and not scrolled and row.enabled: run row.onClick(); dismiss
```
- Row hit-testing uses stored layout coordinates, converted into the panel's frame at hit time with
  `localBoundingBoxOf(...)`, so nesting (quick actions inside a Row) and panel padding need no bookkeeping. `:113-126`
- Disabled rows never become hovered. `:125`
- A scroll gesture inside a tall panel suppresses commit on release. `:139, 143, 153`
- There is no per-row pressed highlight in this variant. A single bright radial "bloom" follows the finger across the
  whole panel and the panel leans toward it (see 4.4). `IosMenu.kt:84-92, 108-111`
- The compat dropdown variant does highlight the hovered row: a rounded (pill) fill of 7 percent black (light) or 9 percent
  white (dark), plus a smaller glow (spot alpha 0.2). `IosCompatMenus.kt:332-358`; colors `IosMenuMaterial.kt:91, 101`.

### 4.3 Slide from the trigger (finger never left the trigger)

For tap-to-open dropdowns the touch begins on the trigger, which lives in another window from the panel. A trigger
modifier solves it: it opens the menu on *down*, then keeps reading the finger, converts it to screen coordinates, and
publishes it through a global holder (`pointer`) plus a release counter that ticks on up. The popup listens to the
pointer and sets `hovered` on the row whose screen rect contains it (haptic on change), and when the counter changes
after the popup opened it fires the hovered row's click. `U/ios/compat/IosCompatMenus.kt:197-214, 247-303`.
The comment states there is one press at a time so one global suffices (`:247-249`). A plain tap just opens it, and then
rows are ordinary taps through the row's press modifier (`:354`).

### 4.4 Bloom and lean (feedback on the panel)

- The bloom is an additive radial gradient centred on the finger. Parameters for menus: radius 0.6 of the min panel
  dimension, flat sheen 0.06, spot alpha 0.32. Buttons use radius 1.5x, sheen 0.08, spot 0.15. The press fades in with a
  spring (damping 0.5, stiffness 300) and the position follows the finger with the same spring. `U/component/backdrop/catalog/utils/InteractiveHighlight.kt:29-50, 102-116`; menu params `IosMenu.kt:108-111`
- Release pulse (menus only): a soft ring starts at the finger on release, radius from 0.5 to 1.8 times the base, alpha
  fading from `1.1 * spot`, over 460 ms FastOutSlowIn. `InteractiveHighlight.kt:53-71, 118-130`
- Lean: the panel translates toward the finger with a saturating tanh curve and scales slightly more along the pull
  direction. Menu strength factor 2.6 (a glass button is 1.0). `U/component/GlassCircleButton.kt:63-91`, `IosMenuMaterial.kt:161-162`;
  see `convx-motion.md` section 7 for the formula.

### 4.5 Haptics

Convx does not use Compose's haptic call directly. It drives the vibrator with predefined click and tick effects, because
the system path is silenced on Samsung when "touch interactions" vibration is off. Strong types (long press, confirm,
reject) map to a heavy click, everything else to a tick; pre-Q fallback one-shots of 20 ms and 8 ms.
`U/theme/AppHaptics.kt:19-54`. Uses in overlays: long press on open (`IosContextMenu.kt:88`, `AppleContextMenu.kt:137`),
row change while sliding (`IosMenu.kt:147`, `IosCompatMenus.kt:205`).

---------------------------------------------------------------------------------------------------

## 5. Menu material (what the panel is made of)

All in `U/ios/foundation/IosMenuMaterial.kt` unless noted. Rendering technique (captured backdrop layer, blur, lens) is in
`convx-glass.md`; this is the overlay-specific parameter set.

| Item | Value | Line |
|---|---|---|
| blur radius (frost variant) | 8 dp | 78 |
| saturation boost on backdrop | 1.8 | 80 |
| long-press menu: blur / lens height / lens amount | 8 dp / 12 dp / 12 dp | 108-110 |
| liquid (non long-press) lens height / amount | 8 dp / 12 dp | 115-116 |
| fill, dark | `#1C1C1E` at 0.82 (long press: 0.80) | 60, 96-97, 111 |
| fill, light (frost) | `#F5F5F5` at 0.72 | 87 |
| fill, light (liquid) | white at 0.55 | 127 |
| label dark / light | `#F8F8FA` (white in liquid) / `#1A1A1A` | 97, 128 |
| secondary label | 68-70 percent white / 50 percent of `#1A1A1A` | 99, 129 |
| separator | white 6-12 percent / black 10 percent | 100, 123, 130 |
| rim alpha | 0.20 | 159 |
| shadow | radius 40 dp, offset y 8 dp, black 0.12 | 83 |
| backdrop freeze | the backdrop is frozen while the menu is open (the transition scales the recorded layer) | 209, 223-232 |
| backdrop resolution | user slider, clamps 0.3 to 1.0, scales blur and lens px | 216-222 |
| base frost drawn first | `#2C2C2E` dark / `#EDEDED` light, so frame one is not transparent | 196, 243, 266, 287 |
| no RenderEffect | flat opaque fill `#1C1C1E` / `#F2F2F2` | 203-206 |
| flat sheet fill (menu inside an opaque sheet) | shadow 20 dp + flat fill, no backdrop sampling | 199-202 |

The panel clips its own shadow when inside a popup window, so the dropdown adds 44 dp of transparent room around the
panel inside the popup and offsets the popup by the same amount. `IosCompatMenus.kt:158-161, 187, 216, 245`

---------------------------------------------------------------------------------------------------

## 6. Anchored dropdowns / popovers

### 6.1 Compat `DropdownMenu` (iOS 27 style: opens over its button)

- Stays composed until its `MutableTransitionState` finishes. `IosCompatMenus.kt:133-138`
- Same morph as 2.3 but with split axes: progress spring (0.78 / 700; exit 150 ms), `growX` spring (0.62 / 560; exit 140 ms),
  `growY` spring (0.78 / 330; exit 140 ms). Start scale per axis = anchor size / panel size, minimum 0.08. Alpha = `p/0.35`.
  Origin = anchor center within the panel. Blur 20 dp fading out while opening. `:141-155, 219-234`
- Placement (inside a `PopupPositionProvider`, window coordinates):
  `x = anchor.right - panelW` if the anchor center is in the right half, else `anchor.left`; clamp to [12 dp, window - panelW - 12 dp].
  `y = anchor.top` (menu top level with the trigger), pulled up only as far as needed: `min(y, windowH - panelH - 12dp)`, then `max(y, 12dp)`.
  So there is no "flip below/above" decision: the menu covers its trigger and is shifted. The anchor rect relative to the
  popup is written to a float array so the layer can set its transform origin. `:164-190`
- The 12 dp margin is not inset-aware; the provider receives the window size, not the safe area.

### 6.2 Vendored `CupertinoDropdownMenu` (placement with fallbacks)

First-fit candidate lists. For LTR:
```text
x candidates: [anchor.left, anchor.right - menuW, (anchor.left >= 0 ? windowW - menuW : 0)]
y candidates: [anchor.top, anchor.bottom - menuH, windowH - menuH - verticalMargin]
pick the first candidate that keeps the menu inside the window (with the safe padding tolerance)
```
`H/CupertinoDropdownMenu.kt:584-640`. RTL swaps the x order. Transform origin is computed from the overlap of anchor and menu
bounds on each axis (0 if the menu is beyond the anchor's end, 1 if before the start, else the overlap center), so a menu
that opens downward pivots on its top and one that opens upward pivots on its bottom. `:561-582`
Enter: scale 0.1 to 1 and alpha 0 to 1 on one spring (damping 0.825, `StiffnessMediumLow`); exit: 350 ms LinearOutSlowIn.
`:449-466, 649-657`. Max height 600 dp, min row height 48, title row 32. `:643-657`
It also reserves a 32 dp transparent margin around the panel inside the popup for the shadow. `:127, 166`
Rows share a leading icon column if any row has an icon, and an extra picker column if any row is a picker.
`:200-250` (scope flags `hasIcon`, `hasPicker`).

### 6.3 `OverlayMenu` (touch-point menu without a window)

Used by the roughly 30 `menuState.show { }` call sites. Instead of an anchor rect it grows from the last finger-down point.
- The activity overrides touch dispatch and stores the last `ACTION_DOWN` point in a global. `A/MainActivity.kt:144-149`,
  `U/component/OverlayMenu.kt:66-68`. It is a hack (call sites pass nothing) but it works for "..." buttons.
- Open: scale starts at 0.12 on both axes; alpha 0 to 1 in 90 ms; x spring (0.72 / 700); y spring (0.78 / 420) softer.
  Close: both scales to 0.12 with spring (1.0 / 900), alpha out over 140 ms after a 40 ms delay. The composable stays
  mounted until the close finishes (`shown` flag). `:97-118`
- Dim: black 0.18 alpha times the animated alpha, no blur; tap anywhere outside dismisses; the panel swallows taps with an
  empty clickable. `:135-146, 202-208`
- Placement: panel's right edge sits 12 dp past the finger; below the finger if it fits above the safe-bottom, else above;
  then clamped into the safe-drawing area (insets read from `WindowInsets.safeDrawing`). Origin = finger point inside the
  panel. Width 270 dp, max height = window height - 24 dp. `:148-171, 191-193, 252`
- Glass is only used when the host allows it; over the expanded player it falls back to a flat 0.94-alpha fill since the
  sampled backdrop would be the wrong page. `:178-187, 200-205`
- Contents are a plain `Column` with `LocalInMenuPanel = true` so inner groups drop their card backgrounds. It is
  deliberately NOT scrollable because the call sites put lazy lists inside (a scroll parent would give them infinite height).
  `:210-231, 235-250`

---------------------------------------------------------------------------------------------------

## 7. Sheets

### 7.1 Own detent sheet (`IosSheet`)

API mirrors SwiftUI: detents medium, large, fixed height, fraction; content interaction `Resizes` (drag content moves the
sheet first) or `Scrolls` (content scrolls first, sheet moves only at top); background interaction; interactive-dismiss
lock. `U/ios/component/IosSheet.kt:59-82, 116-132`

State is a single `Animatable<Float>` = distance of the sheet top from fully-open position (0 open, container height
dismissed). `:140-143`

- Anchors in px, from container height `H`: Large = `H - 24 dp`; Medium = `0.5 H`; Height(d) = d; Fraction(f) = `f H`.
  Sorted ascending. `:176-185`
- Entrance: snap to `H`, then spring (damping 0.86, stiffness 380) to the first (smallest) detent. `:186-188, 195-200`
- Scrim: black, alpha `0.32 * (1 - offset/H)`, computed in a layer block, tap dismisses unless locked. If background
  interaction is Enabled there is no scrim and no tap blocker. `:145-170`
- The sheet body is translated with `translationY = offset` in a graphics layer, vertical `draggable` on the whole body,
  clipped to a top-rounded shape (default corner from `IosMetrics`) with the grouped background color, or glass.
  `:214-241`
- Grabber: 36x5 dp capsule, top padding 6, bottom 2, tertiary label color. `:244-252`
- Drag writes `snapTo(offset + delta)` clamped at >= 0 (no overdrag upward, no resistance). `:202-204`
- Release (`settleSheet`): `:259-275`
```text
targets   = anchors converted to offsets
projected = offset + velocity/1000 * 0.998 / (1 - 0.998)        // see FlingProjection
nearest   = detent target closest to projected (ties go in the fling direction)
if dismissAllowed and projected > lowestDetentOffset + 0.12 * H:
        spring to H (damping 1.0, stiffness 400, initialVelocity = release velocity); then onDismissRequest()
else:   spring to nearest (damping 0.86, stiffness 380, initialVelocity = release velocity)
```
  Projection helper: `U/utils/FlingProjection.kt:21-56`. 0.998 is the UIKit scroll deceleration; the factor works out to
  about 0.5 s worth of velocity. The doc says the textbook `v^2/(2a)` is wrong and this form is "what Apple ships".
- Nested scroll in `Scrolls` mode: the sheet consumes only the leftover downward scroll after the inner list has reached its
  top (post-scroll hook), and does nothing on pre-fling. `:206-212, 277-297`
- Caveats: `if (!isPresented) return` at the top means a programmatic close has no exit animation (only drag-dismiss does).
  `:133`. The doc says it must be placed in a window-filling Box and deliberately avoids a `Dialog`. `:107-114`

### 7.2 Library modal sheet (`CupertinoModalBottomSheet`), the one most screens use

Described in `H/CupertinoModalBottomSheet.kt:26-175` (its header says the base library has state but no presenter, so this
file adds scrim, drag, detent positioning).
- Values: Hidden, PartiallyExpanded(detent), Expanded. Detents: Large (full), Medium (fraction 0.5), Height(dp), Fraction(f).
  `H/CupertinoBottomSheet.kt:178-250, 253-275`. Presentation style Modal carries `contentInteraction` (Resize or Scroll),
  `isBackgroundInteractive(detent)` and `dismissOnClickOutside`. `:300-303`
- Anchor offsets from container height `h`: Hidden = h; detent = `h - clamp(detentHeight, 0, h - topInset)`, with
  `topInset = statusBarTop + 24 dp`. `H/CupertinoModalBottomSheet.kt:88, 150-154`
- Sheet is not translated; it is **resized live**: layout forces both min and max height to `h - offset`, so the content is
  always laid out at its visible size (this lets a lazy list inside work; a fixed tall translated card would measure the
  list unbounded). `:160-168`
- Scrim alpha = `1 - offset/h`, color default black 0.32; Convx's wrapper passes black 0.12 and forces the dialog window's own
  dim to 0, because the app draws a stronger pushback effect itself. `:76, 125-141`; `U/ios/compat/IosCompatTail.kt:148-159`
- Swipe machinery: tween 400 ms on the cubic (0.2833, 0.99, 0.31833, 0.99) for programmatic moves, positional threshold 50
  percent of the distance between anchors, default velocity threshold 125 dp/s. `H/CupertinoBottomSheet.kt:505-514`,
  `H/CupertinoTween.kt:26-45`, `H/SwipeableV2.kt:596-602` (the comment there says "1.8 dp per millisecond" which does not
  match 125 dp/s; do not rely on the comment).
- It only reports dismissal after the swipe has *settled* on Hidden, so the sheet never disappears mid-flight.
  `H/CupertinoModalBottomSheet.kt:109-119`
- Nested scrolling for Resize mode: the sheet takes upward scroll first, then downward leftover; pre-fling consumes the fling
  when the sheet is above its top detent and settles by tween. `H/CupertinoBottomSheet.kt:581-658`
- Drag handle: 38x5 dp, 4 dp vertical padding in the library; Convx replaces it with a 36x5 grabber (5 dp above, 8 below,
  `systemGray3`) that widens and thins as the sheet is dragged: `scaleX = 1 + 0.7*tanh(drag/320dp)`, `scaleY = 1 - 0.3*tanh(...)`.
  `H/CupertinoBottomSheet.kt:690-692`; `U/component/BottomSheetMenu.kt:97-98, 207-227`
- Convx menu sheet shape/metrics: top corners 28 dp continuous (user-tunable via preference), content inset 16 dp, inner
  row radius derived as `outer - inset` (concentric, minimum 4 dp), content width cap 500 dp, library cap 640 dp. Compact
  landscape (height < 480 dp) gets only the Large detent, otherwise Medium + Large. `U/component/BottomSheetMenu.kt:80-90, 113-150, 195`;
  `U/utils/ModifierExt.kt:344`
- Back: the menu sheet installs `BackHandler`, which runs `sheetState.hide()` (the real slide-down) and clears focus first.
  `U/component/BottomSheetMenu.kt:152-157, 166`

### 7.3 Full-screen page sheet (`IosPageSheet`)

Window: `Dialog` with `decorFitsSystemWindows = false`. Content top padding = status bar + 10 dp, top corners 38 dp, header 44 dp
(circular close button on tertiary fill at start, centered 17 sp semibold title), 16 dp side padding. Offset animatable
1 to 0 with spring (1.0 / 380) on open, 220 ms tween on close then dismiss; scrim `0.4 * (1 - offset)`. `U/component/IosPageSheet.kt:212-279`

### 7.4 Content behind a sheet: scale-back ("card in front of a card")

`U/component/IosSheetPresentation.kt`
- At progress 1 the presenting screen is scaled to 0.91, origin top-center, moved down by the status bar height, clipped to a
  32 dp continuous rounded rect, and darkened by 0.22 black. A solid black is drawn *behind* the layer in unscaled coordinates
  to fill the gap the shrink opens. `:29-70`
- A draw-phase overload reads progress from a lambda so gesture-driven progress never recomposes. `:72-100`
- Progress = spring(1.0 / 380) toward 1 if any sheet is registered. Sheets register through a counter (`opened/closed`)
  incremented in a `DisposableEffect`, so stacked sheets keep the page pushed back until the last closes. All library
  sheets register automatically via one call in the shared `ModalBottomSheet`. `:102-159`; `U/ios/compat/IosCompatTail.kt:141`
- It is applied twice at the app root: once to the scaffold and once to the layer holding player and navigation, with the
  same progress. `A/AppRootContent.kt:775-780, 860, 1117`. Note: only *sheet pages* push the screen back; popup menus leave
  the page in place. `:774`

### 7.5 Sheet math you can reuse (facts)

```text
scrim      = maxAlpha * (1 - offset/H)
resistance = sheet drag past the top detent should be damped (Convx does not do this; iOS does)
settle     = project(offset, v) then nearest detent; if projected beyond lowest detent + 12 percent H, dismiss
spring     = damping ~0.86, stiffness ~380 for settle; critically damped (1.0) for dismiss and landing
```

---------------------------------------------------------------------------------------------------

## 8. Dialogs, alerts, action sheets, toasts

### 8.1 Alert (`CupertinoAlertDialog`)
- Width 270 dp, min height 110, title/message spacing 4 dp, container `systemGray7`, 1 dp shadow, medium continuous shape.
  Title headline centered, message footnote centered. Buttons default horizontal; vertical orientation optional.
  `H/CupertinoDialogs.kt:199-260, 783-790`
- Scrim black 0.4 (dark) / 0.2 (light), animated color. Enter: `scaleIn(initialScale = 1.2f) + fadeIn()`; scrim fades in
  with the content. Window: dismiss on back, dismiss on outside tap is **false** by default; system bars + IME padding applied
  inside. `H/CupertinoDialogs.kt:215, 368-372, 401-455`
- Exit has no animation, because the `Dialog` window is destroyed on dismiss. Convx's own dialog helper says so directly
  ("Entry-only, the platform Dialog window is torn down on dismiss"). `U/component/Dialog.kt:366-371`
- Convx's `AlertDialog` wrapper claims 30 dp corners and "actions stacked as full-width capsules" in its doc comment, but the
  code passes the caller's buttons into the library's button scope (`:216-222`). Treat the capsule claim as unverified.
  `U/ios/compat/IosCompatFields.kt:171-224`
- App's own dialogs: 30 dp continuous corners, 0.5 dp white 15 percent hairline border, 24 dp padding; entrance scale 0.96
  to 1 with fade on spring (no bounce, stiffness 450); entry only. `U/component/Dialog.kt:65-95, 200-225, 366-392`;
  `U/utils/Motion.kt:97-102`. Note the inconsistency: alerts start at 1.2, app dialogs at 0.96.

### 8.2 Action sheet (`CupertinoActionSheet`)
Bottom group, max width 500 dp, 8 dp side padding, button height 56 dp, title padding 12 dp (24 dp bottom when title and
message), 6 dp title/message gap, insets = nav bars + 8 dp. Enter: the sheet slides from below by `translationY = height*(1-p)`
over 400 ms on the Cupertino cubic; exit 150 ms EaseIn; scrim fades on the same clocks. Tap outside dismisses when allowed.
`H/CupertinoDialogs.kt:269-330, 497-560, 770-780, 789-807`

### 8.3 Toast (`IosToast`)
Dark capsule: label color at 0.92 alpha, text in the background color, subheadline, max 2 lines; padding 18/12 inside,
16/8 outside; optional action label in the tint color with headline weight. It keeps Material's `SnackbarHostState` for the
queue, so enter/exit and timing are Material's, not iOS. `U/ios/compat/IosCompatBars.kt:257-301`

### 8.4 Tooltip
A 10 dp-radius box with label color at 0.92 alpha and 12/8 dp padding; no arrow, no placement logic, no window.
`U/ios/compat/IosCompatTail.kt:178-203`

---------------------------------------------------------------------------------------------------

## 9. Window layer, z-order, focus, back, accessibility

1. **Popups** (context menus, dropdowns) are focusable `Popup` windows. Focusable means back and outside-touch route to
   `onDismissRequest` and the popup takes touches. For the full-screen ones the position provider returns (0,0) and the
   content is `fillMaxSize`, so the *popup window* is the whole screen and Convx draws its own scrim inside it.
   `IosContextMenu.kt:170-185`, `AppleContextMenu.kt:286-298`
2. **Dialogs** use the `Dialog` window with default-width off. Sheets use a `Dialog` and zero its window dim in a
   `SideEffect`, then draw an animated scrim. `IosCompatTail.kt:144-159`
3. **In-window** overlays (`OverlayMenu`, `IosSheet`, `BottomSheetMenu`) are sibling boxes at the end of the activity's
   content. Z-order = composition order. They install their own `BackHandler`. `OverlayMenu.kt:146`, `BottomSheetMenu.kt:166`.
   Reason stated in code: glass backdrop sampling does not work across windows (`IosSheet.kt:111-114`), and the menu's glass
   is "the app root's recorded layer, not a same-window haze source" (`IosMenuMaterial.kt:181-182`), i.e. each popup re-reads
   the root's recorded layer.
4. **Focus**: on dismiss the menu sheet and `OverlayMenu` call `focusManager.clearFocus()` so the IME closes with the
   overlay. `BottomSheetMenu.kt:154-157`, `OverlayMenu.kt:130-133`. Dialog content applies `imePadding` itself.
5. **Predictive back**: only the player sheet uses `PredictiveBackHandler`, to preview a collapse over 35 percent of its
   travel with a squared ease. Overlays use plain `BackHandler`. `U/component/BottomSheet.kt:90-91, 238-249, 492-500`
6. **Accessibility: effectively absent.** A search of the overlay files for `semantics`, `contentDescription`, `Role` finds
   nothing except one enum use. The library's accessibility object hard-codes high contrast and reduced transparency to
   `false`. No traversal groups, no modal semantics, no custom accessibility action for long-press menus, no
   announcement on open, no screen reader focus move. `H/Accessibility.kt:25-35`. This is the main thing Rovsti must add.
7. **Material is banned for new overlays** by the project's own skill: no Material menus, dialogs or bottom sheets.
   `contributor/skills/convx-android-motion/SKILL.md:34-35`. It also demands that menus feel connected to their trigger (`:28`).

---------------------------------------------------------------------------------------------------

## 10. How lists stay stable while a menu is open

Collected evidence; none of it is one named mechanism.

1. **Nothing in layout changes.** The overlay is a different window or a sibling box, so the list never re-measures.
2. **The source is hidden by alpha, not removed.** `graphicsLayer { alpha = itemAlpha() }`, where itemAlpha is 0 while open
   and crossfades back in over the last 30 percent of the landing. `AppleContextMenu.kt:101-106, 213-228`
3. **Anchor capture without recomposition.** The simple menu stores the bounds in state (written on every layout, so every
   scrolled frame recomposes the wrapper). The Apple menu fixed that: a one-element array is written on layout and read once
   when the overlay composes on open; the comment says "as state it recomposed every tile per frame".
   Compare `IosContextMenu.kt:78, 83` with `AppleContextMenu.kt:94-96, 100`. The anchor is snapshotted once with
   `remember(menu.transition)` at open. `:259-260`
4. **Stable keys.** Grid and list items use `key = id` and the reorder wrapper takes the same key, so identity survives
   reordering and the menu controller (remembered per item) stays with its item.
   `U/screens/library/apple/AppleLibraryScreen.kt:383-384`
5. **The scrim eats input.** While open the page receives no touches, so the list cannot scroll under the overlay and the
   captured anchor cannot go stale.
6. **Menu content is memoised.** `entries` and the coordinate arrays are `remember`ed on `(sections, quickActions)`.
   `IosMenu.kt:113-119` Callers that rebuild the section list per recomposition would break the hover index; build once per open.
7. **State replacement for abandon.** `cancel()` swaps the transition state for a fresh closed one, so nothing animates and
   no half-open state leaks. `AppleContextMenu.kt:197-203, 239-243`
8. **Duplicated content composes twice.** The lifted copy (simple menu) is a second composition of the same lambda, so any
   per-composition state inside the item (image loading, animations) starts again in the overlay. Cheap for covers, risky
   for stateful rows. The Apple menu instead takes a dedicated `preview` composable. `IosContextMenu.kt:193-201`,
   `AppleContextMenu.kt:80-88`

---------------------------------------------------------------------------------------------------

## 11. Pitfalls found in Convx (do not inherit)

1. No semantics anywhere (section 9.6). Long press is the only way to open item menus; no alternative action path.
2. No exit animation for dialogs and for `IosSheet` when closed from outside (sections 7.1, 8.1).
3. Sheet drag cannot overshoot the top detent (clamped). iOS applies resistance. `IosSheet.kt:203`, `H/SwipeableV2.kt:423-433`
4. Insets ignored in the simple menu's clamp and in the dropdown provider (2.4, 6.1).
5. Global mutable "last touch" and "slide pointer" holders (`OverlayMenu.kt:66-68`, `IosCompatMenus.kt:251-254`) break with
   multi-touch, hardware keyboard activation, and tests. Pass explicit anchors.
6. No submenus. A search for submenu terms finds only a player menu file, not an overlay mechanism. The `hig` dropdown has no
   nested level. If Rovsti needs one (for example contact actions with a "Send as" choice) it has to be designed fresh (section 12.7).
7. Three different scrim values for similar surfaces (0.28 context menu, 0.18 touch-point menu, 0.12 / 0.32 sheet) and two
   different entrance scales for dialogs (1.2 and 0.96): pick a token set once.
8. Doc/code mismatches: velocity threshold comment, alert "capsule" comment (sections 7.2, 8.1).
9. Heavy blur on the whole panel at open (20 dp render-effect over a 238 x 400 dp layer) plus a captured backdrop is
   expensive; Convx added a resolution slider and downscaled sampling to cope (`IosMenuMaterial.kt:216-222, 246-290`, comments
   mention 15-25 ms RenderThread frames at `:270-273`). Budget this on mid-range phones.
10. `Accessibility.isReduceTransparencyEnabled` is stubbed false, so there is no reduced-transparency or reduce-motion path.

---------------------------------------------------------------------------------------------------

## 12. Provenance and licensing notes for our implementation

- `U/ios/component/IosContextMenu.kt`, `AppleContextMenu.kt`, `IosMenu.kt`, `IosSheet.kt`, `U/component/IosSheetPresentation.kt`,
  `OverlayMenu.kt`, `BottomSheetMenu.kt`, `IosPageSheet.kt`, `FlingProjection.kt` carry GPL-3.0 headers: do not copy.
  Re-derive from this text.
- `H/*` is Apache-2.0 (headers at the top of each file: "Compose Cupertino project", Scott Lanoue 2025, IENGROUND 2026;
  `hig/LICENSE`). It is a fork of the upstream open-source Compose Cupertino library. The right course for Rovsti is not to
  take the fork: either use the upstream directly as a dependency if it fits (license-compatible with MIT/Apache, needs a
  NOTICE entry) or write our own from the descriptions here. Do not pull Convx's modified files.
- The `InteractiveHighlight` and the damped-drag numbers in the sibling reports are noted there as ported from Apache-2.0
  upstreams (see `convx-motion.md` section 11), so prefer those upstreams as the source if exact behaviour is wanted.
- The 0.998 deceleration constant, the 0.55 rubber-band constant, cubic-bezier control points, and every dp/spring number
  in this report are facts and free to use.
- The sheet pushback numbers (0.91, 32 dp, 0.22) and the menu timings are Convx's fit to an iOS reference video; they are
  starting points, not requirements. Rovsti should validate by screenshot sequence.

---------------------------------------------------------------------------------------------------

## 13. Recipe for Rovsti (Kotlin + Compose, our own code, no Material overlays)

Constraints from the owner's complaints: no layout jumps when a button is tapped; a full-screen map in Stops with controls
floating on top; text editing with a proper selection/copy/paste menu; popovers and context menus must exist; layouts
must stay uncluttered. Pseudo-code below is original and illustrative.

### 13.1 Architecture decision: one in-window overlay host

Put a single `OverlayHost` at the root of the activity content, as the last child of a window-filling `Box`. All menus,
popovers, sheets, dialogs and toasts are *entries* the host draws; callers never create windows.

Why (against Convx's mixed Popup/Dialog approach):
- one back-press owner, one scrim policy, one z-order rule (list order), one place to trap accessibility focus;
- glass/blur can sample the same layer tree (Convx itself moved sheets in-window for this reason, `IosSheet.kt:111-114`);
- pixel tests (Roborazzi/Paparazzi) capture the activity window and may omit separate `Popup`/`Dialog` windows, so in-window
  overlays are far easier to screenshot-test and judge visually. Verify this on our test setup early (it is the one
  assumption here that is not proven by Convx).
- exit animations are possible (a destroyed `Dialog` window cannot animate out, `Dialog.kt:366-371`).

Cost: you must handle focus trapping, IME insets and semantics yourself (13.6). Keep a `Dialog`/`Popup` escape hatch only
for the system IME-heavy cases if one turns up.

Host API sketch:
```text
class OverlayController {
    val stack: SnapshotStateList<OverlayEntry>
    fun present(entry): Handle        // returns a handle to dismiss from anywhere
    fun dismiss(handle)               // starts the exit; entry leaves the stack when its exit completes
}
sealed OverlayEntry { ContextMenu(anchorRect, ...), Popover(anchorRect, ...), Sheet(detents, ...), Alert(...), Toast(...) }
```
Each entry owns a small `Transition<Boolean>` (or an Animatable progress) and stays in the stack until progress is 0 and
target is closed. Back pops the top entry only. The host reads `anchorRect` once at present time (a plain value, never
state that updates on scroll, see section 10 point 3).

Layout hygiene (the "buttons flow into content" complaint): triggers are never replaced by their open state. A button that
shows a menu keeps identical size and position; the menu is drawn by the host. A pressed button changes only draw-phase
properties (scale, alpha, overlay) via `graphicsLayer`/`drawWithContent`, never padding or size. Add a lint-style unit test
that measures a trigger before and after `present()` and asserts equal bounds.

### 13.2 Tokens (single source, replaces Convx's inconsistent values)

| Token | Start value | Source evidence |
|---|---|---|
| scrim, menu | black 0.28 dark / 0.14 light | `AppleContextMenu.kt:279`, `IosContextMenu.kt:276` |
| scrim, sheet | black 0.32 times (1 - offset/H) | `IosSheet.kt:159` |
| scrim, alert | black 0.4 dark / 0.2 light | `H/CupertinoDialogs.kt:370` |
| menu panel width / corner | 238 dp / 32 dp continuous (check against our own screens; 28 for dense layouts) | `IosMenuMaterial.kt:63-72` |
| menu row | min 44, 17 sp / 20 | `IosMenu.kt:275-303` |
| menu open | progress spring 0.78 / 700; x spring 0.62 / 560; y spring 0.78 / 330 | `IosCompatMenus.kt:141-155` |
| menu close | 140-150 ms tween, no blur | same |
| enter blur | 20 dp to 0 over the open progress (api 31+, we are minSdk 34) | `IosContextMenu.kt:275` |
| lift scale (simple) | 1.05, 150 ms in / 120 ms out | `IosContextMenu.kt:277` |
| press-down | scale 0.96, veil 0.14 black, 300 ms | `AppleContextMenu.kt:101-111, 411` |
| sheet settle spring | damping 0.86 / stiffness 380 | `IosSheet.kt:198, 274` |
| sheet dismiss spring | damping 1.0 / stiffness 400 with release velocity | `IosSheet.kt:270` |
| sheet dismiss rule | projected > lowest detent + 12 percent H | `IosSheet.kt:268` |
| sheet pushback | scale 0.91, corner 32 dp, dim 0.22, spring 1.0 / 380, origin top-center, shift = status bar | `IosSheetPresentation.kt:29-70, 150-158` |
| sheet corner / grabber | 28 dp (38 for full page) / 36x5 | `BottomSheetMenu.kt:84`, `IosPageSheet.kt:247` |
| alert | 270 dp wide, enter scale 1.2 to 1 + fade | `H/CupertinoDialogs.kt:215, 786` |
| toast | capsule, label color 0.92, 18/12 padding | `IosCompatBars.kt:272-282` |

Our own motion tokens (spring/period naming from `convx-motion.md`) should carry these; do not scatter literals.

### 13.3 Anchored context menu / popover: building blocks

1. `Modifier.contextMenu(items, preview = null, onTap)`: a pointer-input that does the four-way resolution of 3.1
   (tap, scroll claimed, child claimed, timeout) using the Main pass for "child consumed?" and the Initial pass once open.
   Haptic on open. Report anchor `Rect` captured from `onGloballyPositioned` into a plain holder (not state).
2. `ContextMenuEntry` composes: scrim, optional lifted copy or preview card, panel.
3. Panel layout function (pure, unit-testable in a platform-free file):

```text
fun placeMenu(anchor: Rect, menu: Size, window: Size, insets: Insets, gap, margin): Placement {
    safe  = window minus insets, shrunk by margin
    below = anchor.bottom + gap
    above = anchor.top - gap - menu.h
    fitsBelow = below + menu.h <= safe.bottom
    fitsAbove = above >= safe.top
    y = fitsBelow ? below : fitsAbove ? above : clamp(below, safe.top, safe.bottom - menu.h)   // prefer below, then above, then clamp
    // horizontal: align to the anchor's edge nearest the window edge, not centered, for tall-thin menus
    x = (anchor.center.x > window.w/2) ? anchor.right - menu.w : anchor.left
    x = clamp(x, safe.left, safe.right - menu.w)
    origin = fraction of anchor center inside the final rect, clamped to [0,1]
    return Placement(x, y, origin, openedAbove = (y == above))
}
```
   Note we deliberately improve on Convx (inset-aware, third fallback, preferring below like iOS pull-down menus, and
   leaving "cover the trigger" as a separate mode for toolbar buttons if wanted). Unit tests: anchor at each corner and
   centre, menu taller than the free space (scrolls, uses max height = safe height), RTL, landscape, display cutout.
4. Morph: one `progress` spring plus the split `growX`/`growY`. The panel layer block reads them:
   alpha = clamp(p/0.35); scale per axis from `anchorSize/menuSize` (min 0.08) to 1; pivot = origin; blur = enterBlur*(1-p) only when opening.
5. Press-slide-release: one pointer observer over the panel computing hovered index from stored `LayoutCoordinates`
   (or, better, a plain list of item rects measured once after layout, since the panel does not move while open),
   haptic tick on index change, commit on up. For tap-opened popovers pass the pointer stream from the trigger through the
   host controller instead of a global singleton: the trigger calls `controller.beginSlide(pointerFlow)`.
6. Row feedback: while the finger is on a row, fill that row's pill with a 7-9 percent contrast fill (the dropdown variant);
   optional bloom as a later polish. Release pulse is a nice-to-have, not v1.
7. Preview card variant (for contact/place/message previews): follow 3.4. The card is a dedicated `preview` composable, not
   a re-composition of the row. Keep the source row's alpha at 0 while open, crossfade over the last 30 percent on land.
8. Reorder or drag conflict: expose a `cancel()` that swaps in a closed state with no animation, as in 3.6.

### 13.4 Sheets

Implement `SheetEntry(detents, contentInteraction, dismissable)` with:
- offset `Animatable`, anchors from container height (Medium 0.5, Large H minus top inset of status bar + about 24 dp,
  fixed dp, fraction);
- live-resize layout (section 7.2) rather than translate, so lazy lists work; content gets bounded height at every frame;
- drag writes with `snapTo` (undispatched) and uses velocity tracking; add resistance beyond the top detent
  (for example `overshoot * 0.55` or the tanh form, not a hard clamp);
- release: project with 0.998, pick nearest detent, dismiss rule 12 percent, springs from 13.2, initial velocity = release velocity;
- nested scroll: `Resizes` (default) and `Scrolls` modes as in 7.1; make the grabber area always draggable;
- scrim tied to offset; pushback of the page by the same progress via a draw-phase modifier on the root content, with a
  stack-aware counter so nested sheets do not flicker (7.4). Make pushback optional per sheet (a sheet with a map visible behind
  it, like the Stops place sheet, should not push the map back; use `backgroundInteraction = enabled` and no scrim at small detents);
- programmatic dismiss plays the same exit as a drag (fix Convx's gap, 7.1 last bullet);
- detents on small landscape: Large only (7.2).

Stops: place/route sheet over a full-screen map uses detents (peek ~ 120 dp, medium, large) with background interaction
enabled at peek and medium so the map stays pannable; the sheet is an overlay entry, never part of the map's layout.

### 13.5 Dialogs, action sheets, toasts

- Alert: use the single token set (270 dp, 1.2 to 1 scale + fade, spring no bounce; exit reverses in 150 ms). Our own host
  allows an exit animation.
- Action sheet: bottom group + separate cancel group, 56 dp rows, 8 dp side, insets-aware; enter 400 ms ease, exit 150 ms.
  Prefer it for destructive choices triggered from a row.
- Toast: own queue in the host (single visible, replace policy), capsule above the bottom bars (it has to know the floating
  tab bar height, so provide it through the host as an inset), 3 s default, swipe to dismiss, optional action. Live-region
  semantic so TalkBack reads it.
- Text editing popover (copy/paste/select): Convx has nothing; provide a custom text toolbar through Compose's text-toolbar
  hook rendered as a small capsule popover anchored to the selection rect, using the same placement function (prefer above the
  selection, flip below near the top). Flag this as outside Convx evidence; validate against the text-field research.

### 13.6 Accessibility, focus, back (all new work)

- Modal semantics: while a modal entry is on top, mark the page behind with cleared semantics or a traversal group excluded
  from accessibility, and give the entry `isTraversalGroup` + a pane title; announce its title on open.
- Every long-press menu needs an accessible equivalent: set a custom accessibility action "Show actions" (and
  `onLongClick` semantics) on the item, and for hover/slide menus ensure rows are normal clickable nodes with roles
  (`Role.Button`, destructive described in the label).
- Rows: min 48 dp touch target for TalkBack (44 visual, hit padding), checkmark rows expose `selected`/`toggleable` state.
- Back: host handles back for the top entry; sheets use predictive-back progress to preview collapse (as the player sheet
  does, `BottomSheet.kt:238-249, 492-500`).
- Focus/IME: clear focus when an entry closes if it hosted a text field; entries with fields apply `imePadding`.
- Reduce motion / reduce transparency: read the platform animation scale (zero means skip springs, use a short fade) and
  fall back from blur/glass to the opaque fill; Convx has the opaque fallback path (`IosMenuMaterial.kt:203-206`) but never
  wires it to a setting.
- Hardware keyboard: arrow keys move hovered row, Enter commits, Escape dismisses.

### 13.7 Submenus (design from scratch, no Convx evidence)

Recommended: drill-in inside the same panel (header row with back chevron, content slides horizontally 24 dp with fade,
panel height animates on the y spring), since a cascading second panel is awkward at phone width and with the finger-slide
model. Keep press-slide-release working across levels: sliding onto a "more" row opens it after a short dwell (about 350 ms)
or on release.

### 13.8 Testing plan (JVM / Robolectric / Compose tests / screenshots)

- Pure unit tests: `placeMenu`, sheet anchor math, `projectedEndpoint`/nearest-detent selection, dismiss rule, hover hit-test,
  rubber band, preview-card geometry (card width fitting rule 3.5), token consistency.
- Compose tests: long press opens (with test clock), tap does not; child click inside the item wins; slide then release
  commits; scroll cancels; back pops only the top entry; trigger bounds unchanged after open; sheet drag to each detent with
  fling velocities via `performTouchInput` swipe with `endVelocity`; stacked sheet pushback counter.
- Screenshot tests (Roborazzi/Paparazzi) at fixed frames of the open animation by driving the compose test clock
  (`mainClock.advanceTimeBy`): closed, +40 ms (blob + blur), +100 ms, settled; light and dark; narrow and wide widths; menu
  above vs below; menu clamped near edges; sheet at each detent; alert; toast. Open the PNGs and judge them.
- A frame-budget check for blur/glass cost on the lowest tier we claim to support is not possible without a device; fall
  back to the opaque fill when `RenderEffect` is unavailable and when the user chooses reduced transparency.

### 13.9 Build order

1. Tokens + `OverlayController` + `OverlayHost` (scrim, stack, back, a11y group) with a trivial toast and alert.
2. `placeMenu` + pure tests, then the simple context menu (lift copy + panel) and dropdown/pull-down popover.
3. Press-slide-release and haptics layer (our own haptics wrapper, direct vibrator effects like Convx's reason, `AppHaptics.kt:19-26`).
4. Preview-card context menu for the Phone/Contacts/Messages item types.
5. Sheets with detents, resistance, nested scroll, pushback; Stops place sheet.
6. Action sheet, text-selection popover, submenu drill-in.
7. Screenshot suite, accessibility pass, reduce-motion/transparency paths.

---------------------------------------------------------------------------------------------------

## 14. Quick reference: value cheat sheet

```text
Context menu (simple)   scrim 0.28 | lift 1.05 (150/120 ms) | panel spring 0.78/700 | exit 150 ms (0.4,0,0.9,0.5) | blur 20dp->0
                        gap 8 | margin 12 | alpha=p/0.35 | min start scale 0.08 | width 238 | corner 32
Apple-style menu        press 0.96 + veil 0.14 (300 ms) | lift spring 0.8/420 | land spring 1.0/520 | LandFade 0.3
                        menu x 0.62/560, y 0.78/330 | card min(0.78W, 340) | card radius 22 | pad 10 | rubber band x0.6, up x0.3
                        dismiss: drag down 64dp or 40% toward home | return spring 0.75/500 | margin 16 | gap 8
Menu rows               44 min | 17sp/20 | icon col 28+8 | check col 24+6 | separator 21 tall, 1 line, inset 16 | quick row 56
Touch-point menu        x spring 0.72/700, y 0.78/420 | close 1.0/900 | alpha in 90, out 140 (+40 delay) | dim 0.18 | width 270
Dropdown (compat)       as simple menu, split axes | provider: top-aligned, trailing-aligned in right half, pushed up only | margin 12
Dropdown (library)      scale 0.1->1 spring 0.825 mediumLow | exit 350 LinearOutSlowIn | first-fit x/y candidates | max h 600 | min row 48
Sheet                   detents Medium 0.5, Large H-24dp(+status in library) | settle 0.86/380 | dismiss 1.0/400 | dismiss rule +12% H
                        project 0.998 | scrim 0.32*(1-off/H) | grabber 36x5 | corner 28 | page sheet corner 38, close 220 ms
Pushback                scale 0.91 | corner 32 | dim 0.22 | spring 1.0/380 | origin top-center | shift = status bar
Alert / action sheet    alert 270 wide, scale 1.2->1 | sheet 500 max, row 56, side 8 | enter 400 ms (0.2833,0.99,0.31833,0.99), exit 150 EaseIn
Toast                   capsule, 0.92 label color, pad 18/12 | queue/timing from host
Haptics                 open: heavy click | row change: tick (TextHandleMove) | direct vibrator effects
```
