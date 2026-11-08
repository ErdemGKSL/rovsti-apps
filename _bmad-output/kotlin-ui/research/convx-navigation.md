# Convx research: navigation, app structure, chrome, lists, search, input, haptics, accessibility

Scope: how Convx (Kotlin + Jetpack Compose) structures navigation and chrome, and what Rovsti's own Compose UI should do. This is a research input for the Rovsti Kotlin UI rewrite (the GPUI apps are being deprecated).

Source: `cosmictaserdev-creator/Convx` at `$REF` = `/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/ref/Convx`.

Path shorthand used in every citation below (all relative to `$REF`):

- `A/` = `app/src/main/kotlin/com/convx/music/`
- `C/` = `A/ui/component/`
- `FT/` = `A/ui/component/floatingtabbar/`
- `P/` = `A/ui/player/`
- `I/` = `A/ui/ios/`
- `U/` = `A/ui/utils/`

Sibling reports: `convx-glass.md` (glass rendering, tokens) and `convx-motion.md` (motion). This report stays on navigation and structure and only repeats their facts where needed.

## 0. Licence and provenance (read first)

- Convx is GPL-3.0 (`LICENSE`; file headers such as `A/AppNavHost.kt:1-4`). Rovsti is MIT/Apache. Nothing here is a copy of Convx code. The pseudo-code in this file is written from scratch and paraphrases behaviour. Numbers (durations, stiffness, dp) are facts about a design, not expression, and may be reused.
- Rule for the implementer: read this file, not the Convx source, while writing Rovsti code. Do not paste or lightly rename Convx functions. Names like `foldGeometry`, `SpringEasing`, `DampedDragAnimation`, `AxisStaggerSpec` are Convx identifiers; use your own names and your own code structure.
- Several Convx pieces are vendored from permissive upstreams, which are the only legitimate copy sources:
  - `FT/FloatingTabBar.kt:1-24` header: "FloatingTabBar v1.0.1 by Elyes Mansour ... Apache License 2.0" (`github.com/elyesmansour/compose-floating-tab-bar`), with Convx modifications on top (centred layout, draggable puck).
  - `C/backdrop/catalog/utils/DampedDragAnimation.kt:1-7`: "Copyright 2026, compose-miuix-ui contributors, Apache-2.0", ported from the Kyant0 "AndroidLiquidGlass" catalog (`LiquidBottomTabs`). Kyant0/backdrop is Apache-2.0 (see `convx-glass.md` section 0).
  - `hig/**` (`hig/src/main/kotlin/zone/ien/hig/Accessibility.kt:1-17`): "Compose Cupertino project ... Apache License 2.0".
  - So for the tab bar, the real upstreams (compose-floating-tab-bar, Kyant0 backdrop catalog, compose-cupertino) are Apache-2.0 and can be used or copied with attribution. The owner's second option (an MIT-licensed iOS-26-style library) is not needed for this topic if we depend on Apache-2.0 libraries or write our own.
- Documentation drift warning: several KDoc comments in Convx disagree with the code (for example `U/Motion.kt:141-157` says the push is "260ms" but the constant is `PushMillis = 400`; `C/backdrop/backdrops/BackdropFreeze.kt:17` says "tween(200)" while `NavTransitionFreeze.kt:17` has a 330 ms window). Trust the constants, not the prose.

## 1. Executive summary (what makes Convx feel iOS-like)

1. Navigation is plain AndroidX Navigation Compose with string routes, one `NavHost`, and iOS-style push/pop (slide plus 30% parallax and a dim). Tabs are not separate destinations in the usual sense: tab roots share one back stack root and are switched with save/restore state (`A/AppNavigation.kt:7-17`).
2. The bottom chrome is one custom layout (`FoldingTabBar`) driven by two animated numbers (fold and search), not a stack of animated visibilities. This is the most important structural idea: one layout, two progress values, positions derived from them (`FT/FoldingTabBar.kt:76-93`, `FT/FoldGeometry.kt:25-59`).
3. The tab bar collapses when the list leaves its top and expands when it returns, via a `NestedScrollConnection` that reads unconsumed scroll (`FT/FloatingTabBar.kt:388-509`, used at `A/AppRootContent.kt:297`).
4. Screens never pad by hard-coded bar heights. Root computes one `WindowInsets` value that already includes the bar and mini player heights and passes it down (`A/AppRootContent.kt:524-551`).
5. Large titles are a normal first list item that scrolls away, with a compact title bar overlaid on top and faded in by a derived collapse fraction (`C/ScreenTitleBar.kt:45-173`). The top edge is a progressive blur plus a scrim (`C/TopFadeBlur.kt`).
6. The mini player is a single composable node reused in three places (expanded slot, inline slot, and the morph). Its expansion to the full player is driven by a draggable sheet with a spring (stiffness 360) and a velocity-projected snap (`C/BottomSheet.kt:83,510-540`).
7. Feedback is consistent: rows get an instant flat highlight, buttons get a bouncy scale to 0.94, selection uses one spring (stiffness 750, damping 0.76) (`U/Motion.kt:77-93`, `U/ModifierExt.kt:136-166`).
8. Weak spots to avoid copying: accessibility is mostly `contentDescription` strings (831 occurrences) while Cupertino's high-contrast and reduce-transparency flags are hard-coded to `false` (`hig/.../Accessibility.kt:34,37`); "reduce motion" is a custom preference named `ReduceDynamicBlurKey` rather than the system animator scale; text input is a bare `BasicTextField` with no custom selection or toolbar work (`A/ui/ios/compat/IosCompatFields.kt:109-130,314-333`). This matters because the owner's text input complaints (caret placement, selection, copy/paste menu, IME geometry) are not solved by Convx; we must use the stock Compose text stack correctly and test it.

## 2. Navigation architecture

### 2.1 Library and route model

- Navigation Compose (`androidx.navigation.compose.NavHost`) with string routes; `AppNavHost` is a thin wrapper that supplies four transition lambdas and calls `navigationBuilder(...)` for the destination graph (`A/AppNavHost.kt:35-102`, graph in `A/ui/screens/NavigationBuilder.kt`, 807 lines). Compose BOM version 1.10.2 (`gradle/libs.versions.toml:5`).
- Route policy lives in a single object, `AppRoutePolicy` (`A/AppRoutePolicy.kt:1-88`): sets of strings decide, per route, whether the tab bar shows (`showsNavigation`, line 68), whether the mini player shows (`showsPlayer`, line 69), whether the top blur appears, and so on. Tab roots are a fixed set (`tabRootRoutes`, line 7). Detail routes are matched by prefix (`album/`, `artist/`, `local_playlist/`, ... lines 76-88).
- Idea worth copying: chrome visibility is a pure function of the current route (a policy object plus unit-testable functions), not scattered booleans in each screen. Idea not worth copying: stringly-typed prefixes. Rovsti should use typed routes (Navigation Compose 2.8+ `@Serializable` destinations) or a sealed `Route` hierarchy and keep the policy as a `when` over that type.
- Navigating to a tab root uses the standard multi-back-stack recipe: pop to the graph start destination with `saveState = true`, then `launchSingleTop = true`, `restoreState = true` (`A/AppNavigation.kt:7-17`). Note the live tab click handler is slightly different: it pops to the start destination without saving state and `restoreState = false` (`A/AppRootContent.kt:784-800`), so tab state is not restored on click in practice. Two code paths with different semantics is a Convx inconsistency; Rovsti should have one.
- Reselecting the current tab does not navigate. It writes a `scrollToTop` flag into the current back stack entry's `SavedStateHandle`, and each list screen watches that flag and scrolls (`A/AppRootContent.kt:789-791`; consumer example `A/ui/screens/library/LibraryAlbumsScreen.kt:181-190`). The app bar's height offset is also reset.
- Search is an overlay-like route, not a tab content page: `enterSearch` first sets a visual override so the bar can start its morph, waits `SearchNavTransitionDelayMs`, pops to the tab root if needed, then navigates with `launchSingleTop` (`A/AppSearchController.kt:138-151`). This is "animate first, navigate after" and avoids two transitions fighting.

### 2.2 Tab container: pager vs NavHost

- There is also a `HorizontalPager` hosting the top-level tab screens (Home, Library, Settings) with user swiping disabled (`A/ui/screens/MainTabsPager.kt:20-21,61-81`, `userScrollEnabled = false` at line 67). Swiping is disabled because screens use horizontal gestures. Tab taps animate the pager.
- Useful trick: `slideToPage` (`MainTabsPager.kt:41-47`) never animates across more than one page. For a jump of more than one page it first `scrollToPage`s to the page next to the target (invisible, one frame), then animates exactly one page. Reason documented in the KDoc (`MainTabsPager.kt:23-40`): animating across intermediate pages composes those pages from scratch inside animation frames and stutters.
- Which tabs are shown is data: `rememberAppNavItems` (`A/AppNavItems.kt`) combines preferences (local-only mode, hidden routes) into the list of items. Search is always allowed; others can be hidden by the user.

### 2.3 Screen transitions (push and pop)

Constants in `U/Motion.kt`:

| Token | Value | Where |
|---|---|---|
| Push/pop duration | 400 ms (`PushMillis`) | `U/Motion.kt:157` |
| Push/pop easing | cubic-bezier(0.32, 0.72, 0, 1), described as UIKit's navigation curve | `U/Motion.kt:164` |
| Outgoing screen travel | 30% of width (`PushParallax = 0.30`) | `U/Motion.kt:173` |
| Outgoing dim | alpha 0.85 | `U/Motion.kt:176` |
| Shared artwork morph | 500 ms | `U/Motion.kt:28` |
| Card morph open / close | 340 ms / 260 ms, easing cubic-bezier(0.3, 0.5, 0.4, 1), fitted to a captured iOS reference | `U/Motion.kt:54-71` |

Wiring in `A/AppNavHost.kt:55-98`, in words:

- Enter: the incoming screen slides in from the full width using the push spec. If the target is a "morph route" (a detail page that grows out of an artwork tile) it uses the artwork spec instead.
- Exit (screen being covered): slides out left by 30% of its width and fades to 0.85 alpha, on the same curve as the incoming slide so the parallax stays locked. If the next screen is a morph route, the covered screen does not slide at all; it holds still and dims to 0.7 so the tile it came from stays put.
- Pop enter: the screen underneath slides back from its parallax offset (or no transition if the departing route was a morph route).
- Pop exit: slides out to the right and fades.
- The comment at `AppNavHost.kt:73-75` states the incoming screen stays opaque to avoid a see-through overlap. An opaque `background` is placed under the NavHost for the same reason (`A/AppRootContent.kt:889-890`).

Pseudo-code (own words), for the Rovsti equivalent:

```
val push = tween<IntOffset>(durationMillis = 380..400, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
NavHost(
  enter     = slideInHorizontally(push) { fullWidth -> fullWidth },
  exit      = slideOutHorizontally(push) { fullWidth -> -0.30f * fullWidth } + fadeOut(alpha -> 0.85),
  popEnter  = slideInHorizontally(push) { fullWidth -> -0.30f * fullWidth },
  popExit   = slideOutHorizontally(push) { fullWidth -> fullWidth } + fadeOut,
)
```

- Predictive back: manifest sets `android:enableOnBackInvokedCallback="true"` (`app/src/main/AndroidManifest.xml:59`). The player sheet uses `PredictiveBackHandler` and feeds the swipe progress into the sheet as a preview (collapse travel capped at 35%, `PREDICTIVE_BACK_TRAVEL`) and commits or restores on release (`C/BottomSheet.kt:91,240-251,493-496`).
- Sheets that cover the screen apply an iOS "card behind" effect to the presenting content: scale 0.91, corner radius 32 dp, dim 0.22, transform origin top-centre, slide down by the status bar height (`C/IosSheetPresentation.kt:30-36,50-70`). A counter (`SheetPresentationState`, line 110) tracks stacked sheets so the screen stays pushed back until the last one closes. The draw-phase overload takes a lambda so a drag drives it with zero recomposition (`IosSheetPresentation.kt:77-100`).

### 2.4 Glass freeze during transitions (perf pattern)

Because the chrome samples a recorded backdrop, Convx freezes backdrop re-recording for a time window after a nav change (`C/backdrop/backdrops/NavTransitionFreeze.kt:17`, 330 ms; also a pager variant at line 50). This is a glass-specific cost; Rovsti only needs it if it re-implements real-time backdrop blur. If we use `RenderEffect` blur on API 31+ with a fixed layer, skip this. Take away: any effect that re-records the whole tree per frame must be suspended while a full-screen transition or fast scroll runs.

## 3. Root layout and insets

### 3.1 Edge-to-edge

- Window is edge-to-edge via `WindowCompat.setDecorFitsSystemWindows(window, false)` (`A/MainActivity.kt:156`) and `windowSoftInputMode="adjustResize"` (`AndroidManifest.xml:97`). `configChanges` handles orientation and size so rotation does not recreate the activity (`AndroidManifest.xml:93`).
- Rovsti on current Android should use `enableEdgeToEdge()` (androidx.activity) instead, with `adjustResize` and Compose's `imePadding()`; the effect is the same but with correct system bar scrim handling for API 29+ gesture navigation.

### 3.2 One computed inset for "player-aware" padding

`A/AppRootContent.kt:524-551` builds `playerAwareWindowInsets` and exposes it as a CompositionLocal (`LocalPlayerAwareWindowInsets`, provided at line 753). It is: horizontal and top system bars, plus the top app bar height (64 dp, `A/constants/Dimensions.kt:32`), plus a bottom value that is built up step by step:

1. system bottom inset;
2. plus `NavigationBarHeight = 80 dp` when the tab bar is shown and the layout is not tablet (`Dimensions.kt:21`, `AppRootContent.kt:535`);
3. plus `MiniPlayerHeight = 64 dp` only when a standalone collapsed mini row is reserved (`Dimensions.kt:23`);
4. plus `DockedAccessoryHeight = 84 dp` when the mini player is docked inside the tab bar (`Dimensions.kt:28`, `AppRootContent.kt:508-509`);
5. plus left inset for the floating side bar on tablets.

Screens spend it as list `contentPadding`; the list itself spans the whole window and scrolls under the translucent chrome. This directly answers the owner's complaint "floating tab bar covering the last row": the last row can always scroll fully above the bar because the content padding includes the bar plus accessory height. The nav bar height itself animates with a bouncy spring (`NavigationBarAnimationSpec`, `Dimensions.kt:65`), so the reserved padding eases when the bar hides on a route.

Known weakness: heights are constants (80, 64, 84 dp) that must match the real measured bar; the bar's actual height differs (row height 68 dp, accessory 64 dp plus spacing). Convx notes the 84 dp accessory constant exists because under-reserving clips content (`Dimensions.kt:24-27`). Better for Rovsti: measure the real bar with `onSizeChanged` and publish that as the bottom inset, so the number can never drift.

Other inset facts:

- Top chrome: `FrostedTopBar` height is `statusBars top + 52 dp`, with 12 dp gap before content (`C/FrostedTopBar.kt:68,74,84-89`). `appTopBarWindowInsets()` = status bar top + display cutout start/end + player-aware horizontal (`U/AppBar.kt:52-55`) so landscape cutouts and the tablet side bar do not hide titles.
- A `FloatingChromeSpacer` of 64 dp is used by hero-style screens that draw under the bar and still need the first row clear of it (`U/AppBar.kt:69-77`).
- Keyboard: the nav bar's search input uses `.imePadding().navigationBarsPadding()` on the pill (`C/FloatingNavBar.kt:559-560`), sits tight above the keyboard (6 dp top, 2 dp bottom padding, lines 564-565), and the tab bar is replaced by the pill rather than pushed (see 5.2). `WindowInsets.isImeVisible` is read at root (`AppRootContent.kt:433`).
- Screen rounded corners: the player expansion reads the real device corner radius from `RoundedCorner` insets on API 31+, falling back to a default, and returns 0 in split screen or PiP (`P/ScreenCorners.kt:19-90`). Good detail for any full-screen sheet.

### 3.3 Tablet / wide layout

- At container width >= 840 dp the app switches to a floating side bar ("rail") instead of the bottom bar (`C/FloatingSideBar.kt:100`, `A/AppRootContent.kt:453-454`). Rovsti is phone-first; keep it as a later `WindowSizeClass` branch, not a day-one feature.

## 4. The floating tab bar

Convx has two generations in the tree. The older `FloatingTabBar` swaps three composable states with `AnimatedContent` plus `SharedTransitionLayout` and shared bounds (`FT/FloatingTabBar.kt:265-330`). The one actually used by the app is `FoldingTabBar` (`C/FloatingNavBar.kt:305`), which replaced that with a single layout. Study both, build the second.

### 4.1 Structure and sizes

- Tabs: a pill (capsule) containing equal-width cells, plus search as the last entry. Default tab slot width 88 dp (ceiling; callers shrink to fit) (`FT/FloatingTabBarModel.kt:152`). Bar content padding 4 dp (`FloatingTabBarModel.kt:205`). Component spacing 8 dp (line 211). Elevations: inline 6 dp, expanded 12 dp (lines 231-232).
- App wiring: expanded row height 68 dp (`C/FloatingNavBar.kt:112`), search row 48 dp (line 109), folded row 48 dp, icon box 32 dp (`FT/FoldingTabBar.kt:271-276`). Icon and label stacked inside each cell; unselected tabs at 0.6 alpha and selected at 1.0 in the older bar (`FT/FloatingTabBarTabs.kt:327`), 0.82 on-surface for unselected in the app colours (`C/FloatingNavBar.kt:234`).
- Shape is a continuous-corner (superellipse) capsule via `ContinuousRoundedRectangle(percent = 50)` (`FT/FloatingTabBarModel.kt` shapes defaults), not a circular-arc capsule. For us: Android's `RoundedCornerShape(50)` is acceptable; a superellipse shape from the Kyant0 "capsule" library (Apache-2.0) is the polished option.

### 4.2 Collapse and expand trigger ("minimize on scroll")

`FloatingTabBarScrollConnection` (`FT/FloatingTabBar.kt:388`) is a `NestedScrollConnection` placed above the content. Behaviours (`FloatingTabBar.kt:466-509`):

- Policy used by the app: `ExpandedAtTopOnly` (`A/AppRootContent.kt:297-299`). Expanded only while the list is at its top. The moment the list scrolls away from the top in either direction, it goes inline (folded). It expands again when scroll arrives back at the top.
- How "at top" is detected without querying the list: in `onPostScroll`, scroll the list could not consume while pulling down (`available.y > 0.5 px`) means it is at the top, so expand; a consumed movement larger than 0.5 px means it moved away, so go inline (`FloatingTabBar.kt:490-505`; slop constant `AtTopSlopPx = 0.5f` at line 512).
- Other policies exist: `OnScrollDown` and `OnScrollUp` accumulate scroll distance with hysteresis (collapse threshold 50 dp, expand threshold 8 dp, `FloatingTabBar.kt:521-522`); the accumulator resets on direction change. Not used by the shipping app.
- It never consumes scroll (returns zero), so it is a pure observer.
- Scroll bookkeeping kept off snapshot state: last-scroll timestamp and an exponentially smoothed velocity (alpha 0.35) live in plain arrays because they are read in draw code (`FloatingTabBar.kt:184-187,433-444`). `isScrolling()` is true during scroll and for 120 ms after, used to freeze glass capture. Tapping the folded selected tab calls `expand()` (`FoldingTabBar.kt:150`).

Compare with iOS 26: the Tab bar minimizes on scroll down and re-expands on scroll up (`tabBarMinimizeBehavior`). Convx chose the "only at top" variant for its list-heavy screens. Rovsti's choice (see recipe): expand on scroll up or at top, minimize on scroll down; it feels closer to iOS and keeps navigation reachable.

### 4.3 The fold: one layout, two numbers

`FoldingTabBar` (`FT/FoldingTabBar.kt:54-260`) keeps two `Animatable<Float>`s:

- `fold` 0..1 (0 expanded, 1 folded to inline), animated with a fixed-duration tween whose easing is an under-damped spring step response: collapse 560 ms with damping ratio ~0.55, expand 470 ms with ratio ~0.74 (`FoldingTabBar.kt:76-86,275`, durations at `FT/FloatingTabBar.kt:172,174`; ratios `FT/BarMotion.kt` values 0.55 fold collapse, 0.74 bar surface expand).
- `search` 0..1, animated with a real spring: damping ratio 0.86, stiffness `StiffnessMediumLow` (`FoldingTabBar.kt:87-93`).

A custom `Layout` measures seven children (surface, full tab group, selected-tab button, search surface, search control, search field, mini player accessory) and places them using a pure function of the two numbers (`FoldingTabBar.kt:218-258`, geometry function `FT/FoldGeometry.kt:25-59`). Behaviour described in words:

- Row height eases between expanded height and folded height by `fold`; the circle size equals the row height.
- With a mini player present, the player sits above the tab row when expanded (offset equals player height plus spacing) and slides into the row when folded, between the selected-tab circle and the search circle. Player width shrinks from full width to full width minus two circles and gaps.
- The tab group is clipped (rounded path) to a width that shrinks from full width to a circle as `fold` rises, and its alpha drops quickly (fully gone by `fold` about 0.33) so labels disappear in about two frames instead of cross-fading (`FoldingTabBar.kt:122-137`).
- The selected tab's circle fades in and slides to the left edge; search detaches to the right edge.
- Because positions come only from the two numbers, reversing mid-fold retraces the same path, and nothing changes parent mid-transition (no shared-element overlay, no flash). The KDoc says this is the reason for the rewrite (`FoldingTabBar.kt:45-53`).

Pseudo-code of the idea (own words):

```
fun layoutBar(width, fold, search, hasPlayer): Placements {
  rowH   = lerp(expandedRowH, foldedRowH, fold)
  circle = min(rowH, width)
  navW   = lerp(lerp(width, circle, search), circle, fold)
  playerW= lerp(width, width - 2*(circle+gap), fold)
  rowY   = if (hasPlayer) (playerH + gap) * (1 - fold) else 0     // tabs sit below the player when expanded
  playerY= rowY - lerp(playerH + gap, (playerH - rowH)/2, fold)   // player drops into the row
  searchW= lerp(circle, width - circle - gap, search * (1 - fold)) // search field grows out of its circle
  ...
}
```

Do not copy `foldGeometry`; re-derive the maths from this description and your own measurements.

### 4.4 Selection puck: drag, spring, squash

(`FT/FloatingTabBarTabs.kt`, class at `C/backdrop/catalog/utils/DampedDragAnimation.kt`.)

- A "puck" (selection highlight) is a separate node that slides under the tab icons. Its position is a float "index" (0..n-1), not pixels.
- State springs (`DampedDragAnimation.kt:45-49`): value spring damping 1.0, stiffness 1000; drag velocity spring damping 0.5, stiffness 300; press progress spring damping 1.0, stiffness 1000; horizontal scale spring damping 0.6, stiffness 250; vertical scale spring damping 0.7, stiffness 250. Pressed scale is 78/56 = 1.39 (`FloatingTabBarTabs.kt:182`).
- While dragging, the finger owns the value: each drag event snaps (does not animate) the value, one synchronous write per event, to avoid lag behind the finger (`DampedDragAnimation.kt:136-150`). Settling springs run only on release or on programmatic changes. Rapid taps cancel the previous settle instead of queuing, and a `finally` always deflates the press state (`DampedDragAnimation.kt:151-182`).
- Velocity squash and stretch on the puck: scale X divided by (1 - 0.75 * v) and scale Y multiplied by (1 - 0.25 * v) with v clamped to +/-0.2 (`FloatingTabBarTabs.kt:491`). So a fast drag stretches the puck horizontally and flattens it.
- Release: the target index is the rounded value, clamped; if it changed, the tab's `onClick` navigates; else the puck animates back (`FloatingTabBarTabs.kt:205` region). After release the press state waits until the value is within 2.5% of the target, capped at 400 ms, before deflating (`DampedDragAnimation.kt:112-122`).
- The whole pill also nudges up to 4 dp in the drag direction (eased), with a spring (damping 1, stiffness 300, threshold 0.5) back to rest (`FloatingTabBarTabs.kt:160,205`). While pressed the pill scales up by 16 dp worth of width (`FloatingTabBarTabs.kt:294`) and the selected tab's content zooms 1.12x (lines 363, 422).
- External selection (navigation or pager) only moves the puck; user taps and completed drags call navigation explicitly, avoiding double navigation (`FloatingTabBarTabs.kt` comments near the `LaunchedEffect`s). A pager-driven mode snaps the puck to the pager's fractional position (`snapValue`, `DampedDragAnimation.kt:184`).
- Press-only lens: the refractive "magnifier" pass for the puck is only composed while pressed (a hidden copy of the tab row is recorded and refracted), so there is no idle cost (`FloatingTabBarTabs.kt:393-420`). Lens parameters: refraction height 10 dp, amount 14 dp, chromatic aberration 0.5, scaled by press progress; inner shadow radius 8 dp at 15% black (lines ~462-485). Rest state: a flat translucent fill at roughly 10% white (dark) or black (light) (`FloatingTabBarTabs.kt:139` for the 0.90 rest alpha variant).
- For Rovsti: the glass/lens part is covered in `convx-glass.md`. The motion model (index-valued spring with squash/stretch and nudge) is independent and cheap, and is the part to build first.

### 4.5 Long-press on tabs

Each tab has an `onLongClick` that receives its own bounds in window coordinates; the nav bar stores the held tab plus rect and opens a context menu anchored at that rect (`FT/FloatingTabBarModel.kt` tab signature; `C/FloatingNavBar.kt:293-298`, `IosContextMenuOverlay` at line 296). Convx also records the last touch-down position in `MainActivity.dispatchTouchEvent` so menus can anchor to the finger (`A/MainActivity.kt:144-150`). Recipe: pass `boundsInWindow` of the item, not a point.

### 4.6 The mini player inside the bar

- One accessory composable is created once and moved between slots (expanded above the tabs, inline between circles), passing `isInline` only to restyle it (`FT/FoldingTabBar.kt:206-216`, `C/FloatingNavBar.kt:247-263`). Separate composition in each slot would rebuild content every switch.
- The older shared-transition design flew the accessory between slot rects with a per-axis staggered spec: on collapse x leads and y follows 67 ms later; on expand y leads and x follows 33 ms later, each fitted to a 30 fps screen recording of the iOS player (`FT/BarMotion.kt:101-215`; delays at lines 176, 199-212). Durations 560/470 ms. Overshoot is placed only on the surface's size, not on its centre or on icons, so icons never wobble (`BarMotion.kt:60-70,164-172`). The mini width bounces more (damping 0.34 to 0.56) than the bar (0.74).
- The reusable maths: an under-damped spring step response squeezed to fixed duration, so every concurrent animation ends on the same frame. Convx implements this as a custom `Easing` (`FT/BarMotion.kt:26-60`): overshoot of about `exp(-zeta*pi/sqrt(1-zeta^2))` (zeta 0.62 gives about 6%), with the residual removed linearly so the value at 1.0 is exactly 1. Rationale: real `spring()` animations have different end times, causing a visible pop when a shared-element overlay hands back to real layout.

## 5. Mini player to full player

### 5.1 Sheet mechanics

(`C/BottomSheet.kt`, adapted from ViMusic per its KDoc at line 96.)

- A `Dp`-valued `Animatable` with three anchors: dismissed, collapsed (mini), expanded (full height) (`BottomSheet.kt:603-607`). State is saved across process-kept rotation with `rememberSaveable` anchor.
- Open and close spring: stiffness 360, no bounce for open, damping 0.72 for the close landing so the mini pill settles with a small dip (`BottomSheet.kt:83,88,431-455`). The landing velocity is reported (`onCollapseLanded`, line 399) and the tab bar echoes 40% of that dip (`FT/FloatingTabBarMotion.kt` `LandingEchoShare = 0.4`) so the bar lands as one piece.
- Drag: the finger snaps the value (no animation per event) with UNDISPATCHED launch so the card does not trail the finger by a frame; only clamped above the expanded bound while the animation bound stays open so settle springs can overshoot (`BottomSheet.kt:640-650`).
- Release: velocity > 250 px/s expands, < -250 collapses or dismisses; slower releases snap to the nearest anchor to the projected endpoint (`BottomSheet.kt:510-540`). The projection uses the WWDC "Designing Fluid Interfaces" formula: projected = current + (v/1000) * rate / (1 - rate), rate 0.998 (`U/FlingProjection.kt:21-45`). Do not use v^2/(2a); the file explicitly warns against it.
- Nested scroll hand-off: when the sheet is expanded and the inner content (queue, lyrics) is at its top, a downward drag is routed to the sheet (`isTopReached` logic, `BottomSheet.kt:545-598`), and the fling continues as a sheet fling. This is how the sheet "pulls down from the scroll".
- Predictive back drives the sheet progress during the gesture (`BottomSheet.kt:240-251`).

### 5.2 Morph choreography (what moves, when)

Geometry helpers in `P/PlayerSheetMotion.kt`; alpha curves in `P/PlayerContainerMorph.kt`; layers in `P/PlayerMorphLayers.kt`.

- Container: bounds interpolate linearly between mini pill rect and full rect with the sheet's spring progress (`PlayerSheetMotion.kt:50`); no second easing, the spring supplies it.
- Artwork leads: centre x uses ease-out cubic, centre y uses a mix of 40% linear and 60% ease-in cubic (`ARTWORK_VERTICAL_LINEAR_WEIGHT = 0.4`, line 14), size scales uniformly from the source so a non-square mini cover does not stretch (`PlayerSheetMotion.kt:119-150`); a separate window variant interpolates per axis when the target is not square.
- Content handoff: mini title and buttons fade out by progress 0.3 (ease-out cubic); the full player page fades in from 0.1 to 0.6 (ease-in cubic); the full player background fades over the first 0.2 (ease-in) (`BottomSheet.kt:67-76`; curves `P/PlayerContainerMorph.kt:102-118`). The mini title stays anchored while the controls travel right with the expanding edge.
- Both ends are recorded into two `GraphicsLayer`s created at a level above the player and the tab bar (so they survive the gesture) and drawn into one interpolated rectangle by an overlay (`P/PlayerMorphLayers.kt:18-23,167-194`). The mini player's own artwork is hidden while the overlay draws the flying cover (`PlayerMorphLayers.kt:225-231`). Guard: the overlay does not trust a layer until a non-zero-size recording has completed at the current size (`markReady`, line 202).
- Input ownership during the flight: dragging, the end where the drag started owns input; at rest the mini owns it until progress passes 0.2; the full player owns it once settled; in between nobody does, so taps do not fight the spring (`PlayerSheetMotion.kt:60-92`).
- Corner radius: collapsed pill radius interpolates to the device screen corner radius, then the corners open to square over 140 ms after the sheet is fully expanded (`SCREEN_CORNER_EXPANSION_MILLIS`, `PlayerSheetMotion.kt:21`; applied `BottomSheet.kt:140-150,225-235`).
- Progress sanitisation: NaN or out-of-range progress is clamped to 0..1 in one function used by all geometry (`PlayerSheetMotion.kt:29`). Cheap safety worth replicating.
- Performance: progress is read in `graphicsLayer {}` lambdas so the player does not recompose per frame (`P/Player.kt:854-858` comment). The glass backdrop stays frozen through the morph and unfreezes at progress 0.75 (`BottomSheet.kt:80`).
- The whole Convx implementation is heavy (Player.kt is 3,800 lines). A simpler Rovsti design is in the recipe (section 12).

## 6. Large titles, top bar and scroll-linked effects

### 6.1 Large title in the list

- `LargeScreenTitle` is the first item of the lazy list: 34 sp-class headline (extra bold, tight tracking), 2 lines max, 20 dp side gutter, 24 dp vertical padding, a 40 dp spacer above for the status bar (`C/ScreenTitleBar.kt:57-83`; 34 sp / 41 sp line height / -0.6 sp tracking in the app top bar at `A/AppTopBar.kt:136-140`; large title token 34 sp weight 800, tracking -0.8 at `I/foundation/IosTypography.kt:56`).
- The compact title is a separate `CollapsedTitleBar` overlay (64 dp tall under the status bar) that draws nothing at progress 0 and fades in with `alpha = progress` (`ScreenTitleBar.kt:137-173`).
- Collapse progress is a `derivedStateOf`: find the title item in `LazyListState.layoutInfo.visibleItemsInfo` by key or index 0; progress = scrolled-off distance divided by that item's height (0..1); if the item is not visible at all, progress is 1. Grid variant uses `firstVisibleItemScrollOffset / 220 px` (`ScreenTitleBar.kt:90-129`). Reading it inside `graphicsLayer {}` avoids recomposition per scroll pixel (`C/FrostedTopBar.kt:104-108` doc for `titleAlphaProvider`).
- Design reason (KDoc `ScreenTitleBar.kt:45-53`): not `LargeTopAppBar`, because hero artwork behind the list must scroll with the content.
- A second, more native-feeling variant exists in the iOS component layer: `IosNavigationBar` keeps a 52 dp inline row and shows the large title beneath it; as `collapsedFraction` goes 0..1, the inline title alpha rises while the large title fades out and translates up 12 dp, and the fraction passes through a critically damped spring (stiffness 500) (`I/component/IosNavigationBar.kt:47-121`, spring at line 58, 12 dp at line 107). Toolbar buttons sit in separate glass capsules and a spacer between two buttons splits one capsule into two, like iOS 26 (lines 123-147).

### 6.2 Top edge: blur ramp and scrim

- `TopFadeBlur` draws a strip of height (bar height + 64 dp fade run) (`C/TopFadeBlur.kt:49,88-89`). Inside: a progressive backdrop blur whose intensity ramps from 1 to 0 with an ease-out cubic (`TopFadeBlur.kt:106-114`) via the Haze library, rendered at reduced input scale (0.5 progressive, 0.25 otherwise, line 104), plus a page-colour scrim whose peak alpha is 0.42 at the top fading to 0 with ease-out cubic over 12 stops (lines 61-64,161-166).
- Alternative mask path: a smoothstep alpha mask over 16 stops (`TopFadeBlur.kt:92-99`). Haze takes either a progressive spec or a mask, never both (comment at line 147).
- Preference "reduce dynamic blur" replaces it with a solid bar and a 0.5 dp divider that fades in once the title alpha passes 0.99 (`C/FrostedTopBar.kt:123-138,226`).
- The bottom edge has the same idea: a progressive scrim behind the tab bar, height = nav bar total + 56 dp (`A/AppRootContent.kt:1121-1130`). This is what keeps tab icons legible over scrolling rows without a hard bar.
- Compose 1.8+ has `Modifier.blur` and `RenderEffect` on API 31+; on older devices use the scrim only. Haze (Apache-2.0) is an acceptable dependency for the progressive variant.

### 6.3 Overscroll and pull

- iOS rubber band: displayed offset follows a diminishing curve with constant 0.55 (`U/IosOverscroll.kt:47,70-80`); spring-back stiffness 247, and 130 for flicks above 5,000 px/s; seed velocity capped at 10,000 (`IosOverscroll.kt:55-67,342-350`). Hero lists scale the artwork up to 1.18x while pulled (`U/MotionModifiers.kt:50-69`) and disable stock Android overscroll glow when enabled.
- Nested-scroll bookkeeping uses a "generation" counter so a cancelled fling finishing after a new touch cannot move the list (`MotionModifiers.kt:117-209`). Subtle bug source worth remembering.
- Material `TopAppBarScrollBehavior` is still used for the screen title bar offset (`U/AppBar.kt:81-95`), with spring snap and spline decay.

## 7. Search

### 7.1 Behaviour model (iOS 26 style)

Three visual states in the bottom bar, owned by a hoisted state object `NavSearchState` provided through a CompositionLocal (`C/NavSearchState.kt:23-49`): `visualActive` (bar shows search even before the route changes), `keyboardActive`, the query as a `TextFieldValue` (so the cursor survives), and callbacks `onQueryChange`, `onSubmit`, `onTapSearchIcon`, `onTapBar`, `onExit`, `onCloseKeyboard`, plus a `FocusRequester`.

Flow:

1. Search is the last tab (a circle on the right when folded, or inside the pill). Tap: bar morphs (search value 0 to 1, spring damping 0.86) so the field grows out of the circle (`FoldingTabBar.kt:87-93,196-205`).
2. After a delay (`SearchNavTransitionDelayMs`) the app navigates to the search route (`A/AppSearchController.kt:138-151`).
3. Tapping the unfocused bar sets `keyboardActive`. The whole bar is swapped (spring slide plus fade; entering medium-bouncy, leaving no bounce) for a keyboard-docked input pill (`C/FloatingNavBar.kt:142-160`).
4. The input waits 260 ms before requesting focus so the mini player and icons finish leaving before the keyboard appears (`FloatingNavBar.kt:115,552-555`).
5. System back with keyboard open: hide keyboard, clear focus, return to the unfocused search bar, do not leave search (`FloatingNavBar.kt:540-546`). The visible back arrow or system back from unfocused search exits fully (`BackHandler(enabled = searchModeActive, onBack = onExit)`, line 212).
6. From a results screen, tapping the bar pops back to the search input, then opens the keyboard (`AppSearchController.kt:73-79`).
7. Query is `rememberSaveable` with `TextFieldValue.Saver` (`AppSearchController.kt:101`), restored from the route argument when returning to results (`A/AppRootContent.kt:560-575`), and cleared when leaving search onto a tab.
8. Submit paths: IME search action, plus done and go, plus raw Enter key because some OEM keyboards (Samsung named) do not report the search action reliably (`FloatingNavBar.kt:600-633`). History insert is skipped if the user paused search history; local-only mode does not navigate (results are live) (`AppSearchController.kt:117-135`).
9. A source toggle (local or online) is a trailing button; in local-only mode it is hidden.

Field metrics: 48 dp tall pill, 17 sp text, 48 dp tappable icon cells with 12 dp padding, placeholder at 60% opacity, clear (xmark) button appears when non-empty (`FloatingNavBar.kt:109,566-659`). Fonts 17 sp match iOS.

Pitfalls Convx hit (visible in comments): focus fires automatically on tablets, so `onFocusChanged` flips state (`FloatingNavBar.kt:618-622`); keyboard opening at the same time as the bar animation looks bad; route templates with the same destination ID drop new queries under `launchSingleTop`, so it pops `search/{query}` inclusively first (`AppSearchController.kt:121-125`).

### 7.2 Differences to adopt

- The owner wants search behaviour and a proper text field: caret placement by tap, selection handles, magnifier, copy/paste toolbar, correct IME geometry. Convx does none of that explicitly. The recipe uses stock `BasicTextField` (or Material3 `TextField` styled flat) with `TextFieldValue`, system `TextToolbar`, default selection colours, `imePadding`, `windowSoftInputMode=adjustResize`, and tests.

## 8. List rows and sections

### 8.1 Rows

- Dimensions (`A/constants/Dimensions.kt:34-38,52-53`): list row min height 58 dp (artwork 48 plus 5 above and below); list thumbnail 48 dp; list art corner radius 5 dp; grid card art radius 10 dp; thumbnail default corner radius 8 dp (`Dimensions.kt:47`); grid min column width 108 dp so phones get three columns (line 43); small grid thumb 128 dp, grid thumb 164 dp, album 144 dp.
- Text: title 15 sp, -0.23 sp tracking, subtitle line height 20 sp (`C/SongItems.kt:109,199-215`); thumbnail start inset 12 dp (line 100).
- Spacing tokens: gutter 20 dp ("the only gutter"), item gap 16 dp, section gap 24 dp, text gap 2 dp (`A/ui/theme/AppleTokens.kt:40-47`). One gutter value and a few gaps is how Convx avoids the "inconsistent spacing" the owner complained about; copy the discipline, not necessarily the numbers.
- Each row has one primary line plus one secondary line; there is no per-row source pill. Artwork, text and a trailing overflow button; long-press or overflow opens a menu. Swipe actions: fixed 76 dp action width, spring damping 1 stiffness 500 (`I/component/IosSwipeActions.kt:49,85`).
- Alphabet fast scroller exists as a component (`C/AlphabetScrollBar.kt`). Sorting uses a header with a popup (`C/SortHeader.kt`, `C/SortPopupButton.kt`). Filter chips live in `C/ChipsRow.kt` (not studied in depth).

### 8.2 Grouped sections (settings look)

`IosList` DSL (`I/component/IosList.kt`):

- Styles: Plain, Inset, Grouped, InsetGrouped, Sidebar (line 51).
- A section collects its rows first so the first and last row can round their outer corners while joins stay square (`IosList.kt:88-132,224-262`). Row corner radius 26 dp, section spacing 35 dp, row min height 44 dp (58 dp with subtitle), inset 16 dp, separator thickness 0.33 dp (`I/foundation/IosTheme.kt:135-150`). Row default padding 16 dp horizontally, 11 dp vertically (`IosList.kt:256`).
- Separator starts at the text inset, not the card edge, and is hidden after the last row (lines 258-262,284-297).
- Section headers use sentence case (iOS 26 dropped all caps), secondary label colour, 8 dp bottom padding (`IosList.kt:188-204`).
- Press state: flat system fill while the finger is down, cleared immediately on lift, no ripple, drawn behind content (`I/foundation/IosPressable.kt:19-53`). This is the iOS row feedback.
- Rows become individual lazy items so long lists stay lazy.

### 8.3 Typography scale

`I/foundation/IosTypography.kt:36-70`: large title 34/800/-0.8; title 28/800/-0.7; title2 22/700; title3 20/700; headline 16/600/-0.2; body 16/400; subheadline 15/20; callout 14; footnote 13/18; caption 12/16; caption2 11/600. Note body is 16 sp, not iOS's 17 (the search field is 17 sp) which is an internal inconsistency.

## 9. Press feedback and haptics

### 9.1 Press feedback

- Buttons and bar items: custom indication scaling content to 0.94 with a medium-bouncy, medium-low-stiffness spring (`U/ModifierExt.kt:136-166`); `bounceClick` replaces ripple (lines 208-217). Multiple concurrent presses are tracked in a set so releasing one finger does not unscale while another is down (`ModifierExt.kt:176-200`).
- "Wobble" variant for components that own an interaction source: pressed scale 0.86, spring damping 0.38 (`ModifierExt.kt:115-130`).
- Motion tokens in `U/Motion.kt`: select spring (stiffness 750, damping 0.76) for puck and segmented thumbs; press spring (stiffness 1220, damping 0.75); appear spring (stiffness 450, no bounce) for sheets and chips; appear exit 400 ms tween (0.32, 0.72, 0, 1) for predictive-back-scrubbable leaves; icon swap 200 ms in, 160 ms out with 0.3 initial scale pop (`Motion.kt:77-131`). Springs for interruptible gesture-linked motion, tweens only where a fixed end time or scrubbing is needed.
- Reduce-motion gate: `Motion.gate(reduce, spec)` returns `snap()` when reduced (`Motion.kt:134`). Convx wires it to its own `ReduceDynamicBlurKey` preference, not to the system setting; the only read of `ANIMATOR_DURATION_SCALE` is in the intro (`C/ConvxIntro.kt:59`). Rovsti should read the system scale (see section 11).

### 9.2 Haptics

- Custom `HapticFeedback` implementation drives the `Vibrator` directly with predefined effects: `EFFECT_TICK` for light feedback, `EFFECT_HEAVY_CLICK` for long press, confirm and reject, with a plain `createOneShot` (8 ms or 20 ms) below API 29 (`A/ui/theme/AppHaptics.kt:19-54`, effect pick at line 34). Reason in the KDoc: `View.performHapticFeedback` is suppressed whenever the system "touch vibration" setting is off, and on Samsung that setting is off by default.
- Usage is sparse and unified: 58 uses of `HapticFeedbackType.LongPress` (menus, row long-press, drag reorder), 2 `TextHandleMove`, 1 `Reject`, 1 `Confirm`. There is no haptic on tab selection, puck drag, sheet snap, or toggles. The bar and sheet code contain no haptic calls (searched `FT/*`, `C/BottomSheet.kt`, `P/FloatingMiniPlayer.kt`).
- Take-away: Convx under-uses haptics. iOS plays a light tick on tab reselect, picker detents, toggles and sheet snap. Recommendation in the recipe.

## 10. Context menus and popovers (navigation-related notes only)

Detailed popover design is covered by another report; navigation-relevant facts:

- Tabs and the mini player have long-press context menus anchored to the item's window bounds (`C/FloatingNavBar.kt:293-298`, `A/AppNavTabContextMenu.kt`). Row menus use `MenuAnchor.lastDown` finger position (`A/MainActivity.kt:144-150`).
- Menus that must sample a backdrop use a separate `menuBackdrop` layer, switching to the player's backdrop while the player is expanded (`A/AppRootContent.kt:822-824`).
- Back button closes menus, sheets, selection modes through scoped `BackHandler(enabled = ...)` (examples: `C/BottomSheetMenu.kt:166`, `C/OverlayMenu.kt:146`, `P/Queue.kt:335`).

## 11. Accessibility and dynamic type

What Convx actually has:

- Content descriptions on icons: 831 occurrences in `A/ui`; `Role` used 51 times; `semantics` 78; `clearAndSetSemantics` 24 (counts from a repository grep). Inactive tab layers are removed from the accessibility tree with `clearAndSetSemantics {}` (`FT/FoldingTabBar.kt:138,153,186,200`) so TalkBack does not read a hidden copy.
- Tab bar tap targets: cells at least 48 dp tall (`FT/FloatingTabBarBars.kt:116,334`); 44 to 48 dp icon buttons elsewhere.
- Not present: `stateDescription` for tabs (only 2 uses overall), live regions (0), a high-contrast or reduce-transparency path (`hig/.../Accessibility.kt:34,37` return false constantly), reading system `ANIMATOR_DURATION_SCALE` for motion gating. Material `minimumInteractiveComponentSize` is not used (0).
- Dynamic type: text sizes are fixed `sp` values in design tokens, so they follow system font scale automatically, but several layouts have fixed dp heights (row 58 dp, bars 48 and 68 dp) that clip at large font scales. Convx has a density-shrinking helper for the player on short screens (`U/FittedDensity.kt:18-34`: scale density by available/design height, floor 0.7, keep font scale) which is a hack, not an accessibility feature.
- Contributor guidance (not enforced in code): `contributor/skills/convx-android-motion/SKILL.md:38-44` says motion must remain accessible and avoid flashing; `contributor/skills/android-compose-ui/SKILL.md:214` has an accessibility section.

Recommendation: treat these as gaps to fix in Rovsti (section 12.9).

## 12. Recipe for Rovsti (how to build the equivalent in our own Compose code)

All code below is design guidance, not code to paste. Keep domain logic in the existing platform-free Rust crates; this recipe is UI-only Kotlin. Module naming is a suggestion for the research-to-plan handoff.

### 12.1 Module and package layout

- `:core:designsystem` (Compose only): tokens (colours, type, spacing, shapes), motion tokens, press indications, haptics, `RovstiTheme`.
- `:core:chrome`: `RovstiTabBar`, `RovstiTopBar`, `LargeTitleScaffold`, sheet and popover host, `RovstiInsets`.
- `:feature:phone`, `:feature:messages`, `:feature:contacts`, `:feature:stops` consume chrome. Stops is a full-screen map: its screen composes the map as the root layer and floats search, chips, locate and tab bar on top using the same chrome pieces (the tab bar can fold to a single circle by default on Stops).
- One `:app` with a single `NavHost`, typed routes, and a route policy object (pure Kotlin, unit-tested) deciding chrome visibility.

### 12.2 Navigation

- Navigation Compose with `@Serializable` route types; a sealed `TopLevel` enum for tabs. Keep the policy as `fun chromeFor(route: Route): ChromeSpec(tabBar: Boolean, miniPlayer: Boolean, topBar: TopBarKind)`.
- Tab switching: `navigate(tab) { popUpTo(start) { saveState = true }; launchSingleTop = true; restoreState = true }` in one helper only. Reselect emits a `ScrollToTop(tab)` event through a `SharedFlow` held by a per-tab holder, not through `SavedStateHandle` flags, so it cannot get stuck true.
- Transitions: use the push recipe in section 2.3: 380 to 400 ms, cubic-bezier(0.32, 0.72, 0, 1), outgoing 30% parallax, dim to 0.85, opaque background under the host. Tab changes should be a short crossfade (about 200 ms), not a push; Convx uses a pager slide for tabs, which is optional.
- Predictive back: `android:enableOnBackInvokedCallback="true"`, and for custom sheets use `PredictiveBackHandler` with progress mapped to a capped preview (35% travel).
- Search: hoist `SearchState` (query as `TextFieldValue`, `visualActive`, `keyboardActive`, `focusRequester`) at the app level so the bottom bar and the search screens share it. Order of operations when opening: animate bar, then navigate, then focus after a delay (about 250 ms or, better, wait for the bar's animation to report finished rather than a fixed delay).

### 12.3 Edge-to-edge and insets

- `enableEdgeToEdge()`, `adjustResize`, one root `Box`: content layer fills the window; chrome layers float above.
- Publish a single `LocalContentInsets: PaddingValues` computed from measured chrome: top = status bar + (title bar height if pinned), bottom = system bottom + measured tab bar height + measured accessory height + 8 dp gap. Every scrolling screen applies it as `contentPadding` and never as outer padding, so rows scroll under translucent chrome and the last row can always scroll clear of the bar. This directly fixes "bar covers the last row".
- Measure the bar with `onSizeChanged` and animate the inset with the bar (the inset follows the animated height), not a constant like Convx's 80/84 dp.
- Keyboard: `imePadding()` on whatever is docked to the keyboard (search pill, composer in Messages); hide the tab bar while the keyboard is up instead of pushing it.
- Landscape cutouts: add `displayCutout` start/end to horizontal insets on top bars and list rows.

### 12.4 Floating tab bar (build in this order)

1. Static pill: capsule, equal cells, 4 dp padding, icon 28 to 32 dp plus label, bar row 56 to 64 dp, side margin 12 to 16 dp from edges, bottom margin = system bottom inset + 8 dp.
2. Selection puck: separate node whose x is a float index driven by a spring (damping about 0.76, stiffness about 750 for taps; critically damped stiffness 1000 for the value while dragging). Tap on a tab animates the index; drag snaps the index to the finger and settles on release. Press scales puck to about 1.2 to 1.4x with squash and stretch tied to horizontal velocity (clamped to +/-0.2).
3. Minimize on scroll: a `NestedScrollConnection` above the content. Policy for Rovsti: minimize after about 40 dp of accumulated downward scroll; expand after about 8 dp of upward scroll or when at the top; reset the accumulator when direction flips; never consume scroll; no snapshot state writes per scroll event except the single boolean.
4. Fold animation: one `Animatable` `fold` and a custom `Layout` placing (surface, tab group, selected circle, search circle, accessory) as a pure function of `fold` and `search`. Use fixed-duration tweens with an under-damped easing (zeta about 0.6 to 0.75, about 450 to 560 ms) so every concurrent value ends on the same frame, or a single spring-driven `fold` and derive everything from it (preferred: simpler than two clocks).
5. Accessory slot: one mini player composable instance placed by the same layout, with `isInline` restyle.
6. Long-press menu: expose `onLongClick(boundsInWindow)`.
7. Glass: use whatever `convx-glass.md` recommends (Kyant0 backdrop is Apache-2.0); fall back to a tinted scrim plus blur on API < 31.

Test plan: Compose test with `mainClock.autoAdvance = false` stepping `fold` to 0, 0.5, 1 and asserting placements; screenshot tests (Roborazzi) at fold 0, 0.5, 1 and in search; no-overlap assertions against the content inset.

### 12.5 Mini player to full player

- Start simple: `AnchoredDraggableState` (foundation) or a hand-rolled `Animatable<Float>` progress with three anchors, spring stiffness about 360, no bounce open, damping about 0.72 on close landing. Release: |v| > 250 px/s picks direction; otherwise snap to the anchor nearest the projected endpoint (projection with rate 0.998 as in section 5.1).
- Morph using one `progress` value: container rect lerp (pill to full), cover rect with its own ease (x ease-out cubic, y mixed linear and ease-in), mini content alpha = 1 - easeOut(progress/0.3), full content alpha = easeIn((progress-0.1)/0.5). Use `graphicsLayer {}` lambdas for all of it (no recomposition).
- Draw the shared artwork once, in an overlay, hidden in both host positions while moving.
- Input rule: while dragging the end that started the drag owns input; taps ignored between 0.2 and 1.0 until settled.
- Predictive back: map back progress to sheet progress via the same state.
- Corners: radius from `RoundedCorner` insets (API 31+) with a fallback; open to square after settle.
- Defer pixel-perfect shared recording of layers (Convx's GraphicsLayer dual-record) until the simple version works; it exists mainly for glass.

### 12.6 Large titles and top bars

- `LargeTitleScaffold(title, listState)`: first lazy item is the title block; overlay a compact bar whose alpha = collapse fraction; fraction = scrolled-off distance of the title item divided by its height (keyed lookup in `visibleItemsInfo`, 1 when absent), read inside `graphicsLayer`/`derivedStateOf`.
- Large title 34 sp bold, tracking about -0.6 to -0.8 sp; inline title 17 sp semibold; inline bar 52 dp below the status bar; title block bottom padding about 8 dp; actions in 44 dp circular glass buttons grouped in capsules.
- Pinned top edge: progressive blur ramp (bar + 48 to 64 dp) plus a scrim peaking near 40% of page colour; reduce-transparency users get a solid bar plus 0.5 dp divider after collapse.
- Pushed pages: circular back button 36 to 44 dp at the leading edge, centred title faded by the same collapse fraction. Add a long-press-to-root on back for deep stacks (Convx has this on settings).
- Stops (full-screen map): no large title. A floating search pill at the top, floating filter chips beneath it in a single horizontally scrolling row (no wrapping), floating locate and layers buttons on the trailing edge, all in glass capsules over the map, with the tab bar folded to a circle.

### 12.7 Lists, sections, spacing

- Tokens: one horizontal gutter (16 or 20 dp, pick one), item gap 12 to 16, section gap 24, text gap 2, row min height 56 to 64 with 44 to 48 dp leading art/avatar, row inset 16, separator 0.33 to 0.5 dp inset to the text start, hidden on the last row of a card.
- Avatar: one avatar per row (no overlapping stacks). Group chats show a single composite avatar or the group initial, never a fan of overlapped circles.
- Chips: a single non-wrapping `LazyRow` with 8 dp gaps and 16 dp content padding; selected chip becomes a filled pill (animated colour with the appear spring).
- No source pill on every row: show the source (WhatsApp vs local) once, as a section header, a per-conversation icon, or a filter chip.
- Row press: flat highlight on press, no ripple, no scale. Buttons: scale 0.94 with bouncy spring. Selection or toggles: select spring.
- Sections: Inset-grouped list with first/last rounding and sentence-case headers for settings-like screens.
- Lazy performance: stable keys, `contentType`, no per-item `Modifier.composed`, read scroll offsets in draw/graphicsLayer lambdas.

### 12.8 Text input and search

- Use `BasicTextField` with `TextFieldValue` (or the new `TextFieldState` API in recent Compose) so selection and composition are preserved; do not reimplement selection. Verify: tap places the caret, long-press selects a word and shows handles with magnifier, selection toolbar shows cut/copy/paste, double-tap selects, IME composition underline is visible, `Modifier.imePadding()` keeps the field above the keyboard, `windowSoftInputMode=adjustResize`.
- Style: 44 to 48 dp tall rounded field, fill = tertiary system fill, cursor colour = accent, selection handle colour = accent (`LocalTextSelectionColors`), placeholder 60% opacity.
- Search submit: handle IME search/done/go and hardware Enter; keep the query saveable; show a clear button when non-empty; keyboard actions `ImeAction.Search`, `KeyboardType.Text`, capitalisation off for names.
- Contacts and Phone dialer: dialer pad is a custom control, but any number field must still be a real text field so paste works.
- Add instrumented tests in Compose UI tests for caret placement via `performTouchInput { click(offset) }` and selection via `performTextInputSelection`; Robolectric cannot show the real IME, so add a manual check list.

### 12.9 Haptics

- Use Compose `LocalHapticFeedback` as the default; wrap it in `RovstiHaptics` with semantic methods (`tick()`, `selection()`, `confirm()`, `reject()`, `longPress()`), implemented via `View.performHapticFeedback` constants (`CLOCK_TICK`, `CONFIRM`, `REJECT`, `LONG_PRESS`, `SEGMENT_TICK`, `TEXT_HANDLE_MOVE`) with the `FLAG_IGNORE_GLOBAL_SETTING` fallback to a `VibrationEffect.createPredefined` call when the system toggle is off, as Convx found necessary on Samsung (`AppHaptics.kt:19-26`).
- Fire on: tab reselect (tick), puck snap to a new tab (selection), sheet snap to anchor (tick), long-press menu open (long press), toggle flips (tick), destructive confirm (confirm), invalid action (reject). Never on scroll, never continuously during drags except detents.
- Provide a single app preference "Haptics" and honour the system setting by default.

### 12.10 Accessibility and dynamic type

- Reduce motion: read `Settings.Global.ANIMATOR_DURATION_SCALE` (and API 33 `Settings.Global.TRANSITION_ANIMATION_SCALE`) once as a state; when 0, use `snap()` for transitions and drop parallax, squash and bounce. Make `Motion.gate` the only place that decides.
- Reduce transparency: if the user enables a high-contrast text or "remove animations" system setting, or our own toggle, replace glass with opaque surfaces and a divider.
- Every interactive item at least 48 dp; use `Modifier.minimumInteractiveComponentSize()` or explicit sizes; set `Role.Tab` with `selected` semantics on tab cells and `stateDescription`; collapsed bar items keep semantics, hidden copies use `clearAndSetSemantics`.
- Text uses `sp` only; rows use `heightIn(min = ...)` not fixed height; at font scale 1.5+ the tab bar keeps icon-only (labels dropped) rather than clipping; top bars cap large-title line count at 2 with ellipsis.
- TalkBack: group avatar plus name plus preview plus time into one merged node per row; actions as custom accessibility actions on the row; swipe actions expose `customActions`.
- Test with `Modifier.semantics` assertions, font scale 2.0 screenshot variants, and Accessibility Test Framework checks in Compose tests (`enableAccessibilityChecks()`).

### 12.11 Performance guardrails (carried from Convx)

- Read animated values only in `graphicsLayer {}`/`drawWithCache`/`Layout` lambdas.
- Keep scroll-derived state in `derivedStateOf` that flips only at thresholds.
- Suspend expensive effects (backdrop re-capture, blur) while scrolling, while a nav transition runs, and during the player morph.
- One composition of shared widgets (mini player), moved by layout, not duplicated.
- Fixed-duration tweens with spring-shaped easing when several clocks must end together; real springs for single interruptible values.

### 12.12 Suggested build order for the rewrite

1. Tokens and motion specs plus unit tests for the maths (easing end values, projection, collapse fraction).
2. Insets provider and a `LargeTitleScaffold` with a Roborazzi test.
3. Navigation host, typed routes, route policy tests.
4. Tab bar static, then puck, then scroll minimize, then fold and search, then accessory slot.
5. Lists and sections (Messages list, Contacts list, Phone recents) with spacing tokens.
6. Search state and screen, text field verification.
7. Mini to full player (Phone call screen and any media), then sheets and popovers.
8. Stops full-screen map with floating controls over it.
9. Haptics and accessibility passes, then glass polish.

## 13. Concrete number table (facts to reuse)

| Item | Value | Citation |
|---|---|---|
| Push duration / easing | 400 ms; (0.32, 0.72, 0, 1) | `U/Motion.kt:157,164` |
| Parallax / dim | 30% / alpha 0.85 | `U/Motion.kt:173,176` |
| Card morph open/close | 340/260 ms; (0.3,0.5,0.4,1) | `U/Motion.kt:54-71` |
| Select spring | damping 0.76, stiffness 750 | `U/Motion.kt:77-81` |
| Press spring | damping 0.75, stiffness 1220 | `U/Motion.kt:87-91` |
| Appear spring | damping 1, stiffness 450 | `U/Motion.kt:97-101` |
| Button press scale | 0.94, medium-bouncy | `U/ModifierExt.kt:136,160` |
| Wobble press scale | 0.86, damping 0.38 | `U/ModifierExt.kt:115-123` |
| Bar collapse / expand morph | 560 / 470 ms | `FT/FloatingTabBar.kt:172-174` |
| Bar label/fade windows | 317 / 251 ms | `FT/FloatingTabBar.kt:168-169` |
| Fold zeta (collapse / expand) | 0.55 / 0.74 | `FT/FoldingTabBar.kt:275`, `FT/BarMotion.kt` |
| Search morph spring | damping 0.86, MediumLow | `FT/FoldingTabBar.kt:87-93` |
| Scroll thresholds (non-top policies) | 50 dp collapse, 8 dp expand | `FT/FloatingTabBar.kt:521-522` |
| At-top slop | 0.5 px | `FT/FloatingTabBar.kt:512` |
| Scroll freeze tail | 120 ms | `FT/FloatingTabBar.kt:184` |
| Puck springs | v 1.0/1000; vel 0.5/300; scaleX 0.6/250; scaleY 0.7/250 | `DampedDragAnimation.kt:45-49` |
| Puck pressed scale | 1.39 | `FT/FloatingTabBarTabs.kt:182` |
| Tab slot / bar padding | 88 dp / 4 dp | `FT/FloatingTabBarModel.kt:152,205` |
| Rows: bar / search / folded | 68 / 48 / 48 dp | `C/FloatingNavBar.kt:109-112`, `FoldingTabBar.kt:271-272` |
| Reserved heights | nav 80, mini 64, docked 84, app bar 64 dp | `A/constants/Dimensions.kt:21-32` |
| Sheet spring | stiffness 360 | `C/BottomSheet.kt:83` |
| Sheet landing damping | 0.72 | `C/BottomSheet.kt:88` |
| Fling thresholds | +/-250 px/s; decel 0.998 | `C/BottomSheet.kt:512-514`, `U/FlingProjection.kt:21` |
| Mini fade end / full appear / bg handoff | 0.3 / 0.1-0.6 / 0.2 | `C/BottomSheet.kt:67-76` |
| Corner open to square | 140 ms | `P/PlayerSheetMotion.kt:21` |
| Sheet presentation | scale 0.91, radius 32 dp, dim 0.22 | `C/IosSheetPresentation.kt:30-36` |
| Top bar | 52 dp + inset, gap 12 dp | `C/FrostedTopBar.kt:68,74` |
| Top blur run / scrim peak | 64 dp / 0.42 | `C/TopFadeBlur.kt:49,62` |
| Large title | 34 sp / 41 sp / -0.6 sp | `A/AppTopBar.kt:137-139` |
| Inline nav collapse | spring stiffness 500; title lifts 12 dp | `I/component/IosNavigationBar.kt:58,107` |
| Rubber band | 0.55; spring 247 / 130 | `U/IosOverscroll.kt:47,61,67` |
| Row heights | 58 dp row, 44 dp plain list row | `Dimensions.kt:35`, `I/foundation/IosTheme.kt:136` |
| Grouped card radius / spacing | 26 dp / 35 dp | `I/foundation/IosTheme.kt:139,142` |
| Gutter / gap / section gap | 20 / 16 / 24 dp | `A/ui/theme/AppleTokens.kt:41-45` |
| Tablet breakpoint | 840 dp | `C/FloatingSideBar.kt:100` |
| Search keyboard delay | 260 ms | `C/FloatingNavBar.kt:115` |
| Swipe action width | 76 dp | `I/component/IosSwipeActions.kt:49` |

## 14. Mapping to the owner's complaints

| Complaint | Cause in the GPUI apps (from the brief) | Convx technique | Rovsti recipe section |
|---|---|---|---|
| Overlapping avatar stacks, big source pill per row, wrapping chips | no row spec | one art, two text lines, one gutter; chips in a single row | 12.7 |
| Floating tab bar covers the last row | fixed or missing bottom padding | player-aware inset in content padding; list scrolls under chrome | 3.2, 12.3 |
| Tapping makes buttons flow into content | layout-affecting press state | press feedback by scale/graphicsLayer only, no layout change; iOS flat row highlight | 9.1, 12.7 |
| Text box worse than HTML input | custom text widget | stock text stack with `TextFieldValue`, IME padding, tests (Convx does not solve this itself) | 7.2, 12.8 |
| No popovers or context menus | none | anchor to item bounds; stacked presentation | 10, 12.4 |
| Stops must be full-screen map | map in a box | floating chrome over a root-layer map; bar folded | 12.1, 12.6 |

## 15. Open questions and risks

- Convx's tab handler and navigation helper disagree on state restore (section 2.1); decide Rovsti's tab-state policy early (recommend save/restore).
- Convx's pager-based tab hosting plus NavHost doubles the navigation model; Rovsti should pick one (NavHost only, tab content crossfade).
- The fixed-duration spring easing trick is nice but adds a custom `Easing`; a single real spring driving `fold` is simpler and interruptible. Decide after a prototype.
- Real glass requires API 33+ (AGSL) for the refractive puck; on lower APIs fall back to a blurred or opaque capsule. Gate on `Build.VERSION.SDK_INT`, and mind the Convx note that clip-path masks are CPU-expensive on the glass node (`FT/FloatingTabBarTabs.kt` comments around the pill graphicsLayer).
- Verification without a device: motion maths unit tests, Compose tests with a manual clock for fold/transition positions, Roborazzi screenshots at key progress values. Haptics and IME behaviour cannot be verified on JVM; add a manual device checklist for the owner.
