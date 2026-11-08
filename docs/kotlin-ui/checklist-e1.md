# E1 checklist record (E1.22 AC3)

The pass of `.claude/skills/convx-motion-compose/references/checklist.md` sections A-H over the whole catalog
(`android/catalog`, 31 `CatalogPage` entries), done in round R5 step 4 at base `d61a709`. Each item is `pass`,
`fixed (<commit>)` or `deviation: <reason>`. "Fixed" items were found by this pass and are in the commit
`Kotlin UI (E1 R5): E1.22 checklist pass over the catalog`. Items that only make sense once an app exists (Stops map,
Messages keyboard, tile layer) are `later: <story>` with the story that owns them: they are not unexplained.

## How the pass was done

1. Read the code of every page and of the `:design` pieces it uses for the rule (grep for `Popup(`, `Dialog(`, material,
   raw `.dp`, lazy `key`/`contentType`, `OverlayHost` entries, haptics, `gated(`), and read the test that proves it.
2. New sweep test `ChecklistSweepTest` renders **every** page at font scale 2.0 (light) and in RTL (dark) with the
   accessibility checks on, writing PNGs to `android/catalog/build/checklist-sweep/` (not goldens). All 62 PNGs were
   tiled into 8 sheets and opened with the Read tool.
3. The existing goldens (242 PNGs, 12 of them the E1.22 states, plus `docs/kotlin-ui/screenshots/design.png`) were
   judged in rounds R1-R5; this pass re-opened the ones named below.

## Findings of the sweep (what was seen)

Font scale 2.0, light (31 pages) and RTL dark (31 pages):

- All page titles, subtitles and rows scale; rows grow (`heightIn(min)`) and nothing is cut by a fixed height. The tab bar
  drops its labels at 2.0 (Tab bar, Home, Lists, Large title pages) and keeps a 48 dp puck.
- RTL: titles, rows, chevrons, trailing meta, switches, sliders (fill from the right), segmented control, toolbar
  capsules, the alphabet rail (moves to the left edge), tab order and the page-sheet rows all mirror. Numbers inside
  English strings keep Unicode bidi order (for example `+9.4%` reads `9.4%+` in the Motion legend and trailing full stops
  move to the left); that is bidi of English demo text, not a layout bug.
- **Found and fixed (3):**
  1. Lists page, fs 2.0: the phone numbers ran under the alphabet rail (`0100` touching the letters). `ListRow` got
     `endInset: Dp = 0.dp` (extra end padding inside the row, the press fill stays full bleed); `ListScrollRailWidth` is
     public; the Lists page passes it. Test `ListRailInsetTest` (fs 1.0 and 2.0: row text ends before the rail).
  2. Glass page, fs 2.0: the capsule label "Messages" wrapped to two lines and overflowed the capsule. Labels are now one
     line (ellipsis if too wide).
  3. Avatars page, fs 2.0: the caption "Two, 56" / "Empty" was cut to "Two," / "Empt" by a fixed 64 dp caption width. The
     width is now `widthIn(64..96)` and the caption may take two lines.
  4. The accessibility checker (`:testing` `Accessibility.kt`) flagged an overflowing chip half scrolled out of the chip
     row in RTL as a 26 dp touch target. The chip is laid out at 48 dp high and wide enough; it is only clipped by its
     scroller. The checker now judges a clipped node by its laid-out size (a real 40 dp control is still a finding).
- **Seen, not a defect:** Buttons page fs 2.0 shows "L..." of a third text button clipped at the screen edge (an
  overflowing row, intended); Controls page fs 2.0 truncates "Messages"/"Contacts" in the segmented control (iOS truncates
  too); Tab bar page: a red smear left of the puck is the lens refracting the red avatar of "Contact 07" under the bar.
- No golden changed: a re-record (`:catalog:recordRoborazziDebug`) left every PNG byte-identical (the three fixes are only
  visible at fs 2.0 or in widths the goldens do not use), so the contact sheet was not re-run.

## A. Licence and provenance

| Item | Result |
|---|---|
| Convx files keep upstream header plus `Ported from Convx (...)`; `LicenceHeaderTest` | pass (`design/src/test/.../LicenceHeaderTest.kt` runs in `testDebugUnitTest`) |
| glisse-derived files carry the MIT header (shader files too) | pass (same test; Kyant0/Aave credit lines kept in lens/rim shaders) |
| `android/THIRD_PARTY_NOTICES.md` has glisse's full MIT text and every ported source with its licence | pass (glisse, Convx, Kyant0 backdrop/AndroidLiquidGlass/Capsule, compose-miuix-ui, compose-hig, compose-floating-tab-bar, Haze, Coil, Navigation 3) |
| No GPUI, no Material menus/dialogs/sheets, no non-redistributable font | pass (`ConventionTest` rule `material3` and `window`: no `androidx.compose.material*`, `Popup`/`Dialog` only under `design/.../overlay/`; system font via `RovstiTheme.type`; the unused `compose-material3` alias in `libs.versions.toml` is not referenced by any module) |
| New test files carry the Rovsti GPL header | pass (`ChecklistSweepTest`, `ListRailInsetTest`: "Rovsti Project (C) 2026, GPL-3.0-or-later. Original code.") |

## B. Layout (owner complaints first)

Per page: Home, Toolchain, ThemePalette, ThemeType, Motion, Press, Glass, BackdropEngine, IosGlass, Buttons, Controls, Rows,
Swipe, Lists, GroupedSection, Avatars, Photos, Chips, EmptyState, TabBar, FoldedTabBar, LargeTitle, Overlays, ButtonMenu,
ContextMenu, Popover, Dialogs, Sheets, TextFields, Toasts, OverlayStates.

| Item | Result |
|---|---|
| Layers content -> chrome -> overlay host; nothing chrome-like in the glass source | pass on all 31 pages (`BackdropScope(content = ..., chrome = ...)`; `OverlayHost` wraps the app in `CatalogApp`) |
| Lists use `LocalContentInsets`; last row scrolls above the tab bar | pass: Home, Lists, LargeTitle, TabBar, ContextMenu, Rows (insets 16 usages in the catalog); golden `e1.7-list-end-above-bar-*` and `e1.19-lists-page-*` show the last row above the bar |
| One avatar per row, no overlapping stacks | pass: Rows, Lists, ContextMenu, TabBar, Demo show one `Avatar` per row; `GroupAvatar` is a split circle, not an overlap (Avatars page, fs2 and RTL sheets) |
| No source pill on every row | pass: the source is a small glyph in meta (Rows page, `e1.6-rows`) |
| Filter chips are one non-wrapping row | pass: Chips page, `ConventionTest` rule `flowrow`, golden `e1.6-chip-row-overflowing`, fs2 and RTL sweep |
| Spacing only from the token set | deviation: component specimen sizes and demo anchor offsets use raw dp in the catalog (avatar sizes in `AvatarsPage`/`ContextMenuPage`, `ButtonMenuPage` `top = 120.dp` and `OverlaysPage` `96.dp` stack offset that place triggers near the screen top/bottom to test placement, `SheetsPage` `heightIn(min = 44.dp)`, preview widths in `IosGlassPage`, chart strokes in `MotionPage`). None is a gap, padding or gutter between content; every gutter/gap is `Spacing.*`. The `raw-dp` convention rule applies to app modules (`contacts`, `phone`, `messages`, `stops`, `shell`), which do not exist yet. Written against E2.1 so that it is on from the first app module |
| Rows use `heightIn(min)`; nothing clips at fs 2.0 | fixed (this commit): Lists rail overlap, Glass capsule wrap, Avatars caption cut (see findings); all other 28 pages pass in the sweep |
| Stops: full-screen map, floating controls | later: E4.1 (Stops module). The catalog proves the pattern in `FoldedTabBar` (full-bleed backdrop with floating `Expand` pill, folded tab) and `BackdropEngine`/`Glass` pages |

## C. Motion

| Item | Result |
|---|---|
| Specs from `RovstiMotion`; every spring has a `visibilityThreshold` | pass (`RovstiMotionTokensTest`; 17 `visibilityThreshold` sites in `:design`; Convx-ported chrome keeps Convx's own specs, which carry thresholds) |
| Press changes only transform/alpha/overlay: no layout shift | pass (`PressNoLayoutShiftTest`, `PressPageTest`, `TriggerBoundsTest`: bounds before/after press and open) |
| Drags: snapTo during drag, fling release with velocity | pass (`SheetGestureTest`, `SheetMathTest`, `FlingRubberTest`, `TabBarGestureTest`, `SwipeRowTest`) |
| Interruptions retarget the same Animatable; press deflates in `finally` | pass (`MenuSlideTest`, `ContextMenuGestureTest`, `SpringStateTest`, `HoldTest`) |
| Overshoot only on surfaces | pass (`MotionPage` chart: surfaces overshoot, `Toggle` curve is the no-overshoot dashed line) |
| Predictive back scrubbed transitions are tweens; sheets preview 35% | pass (`NavTest`, golden `e1.18-predictive-back-50pct`; `SheetEntry` back preview) |
| Reduce motion goes through `gated(...)` | pass (`MotionGateTest`; `MotionGate.kt` used by overlay, toast, chrome) |
| Light push dim (`NavTransitions.kt:75`) | deviation: the outgoing page fades to `RovstiMotion.PushDimAlpha = 0.85` (`motion/RovstiMotion.kt:134`). It is Convx's spec, ported; on a light page the dim is barely visible in `e1.18-push-mid-light` while it reads clearly in dark. Kept for parity; revisit in E4.16 polish only if the owner dislikes it on device |

## D. Overlays

| Item | Result |
|---|---|
| Every popover/menu/sheet/alert/toast is an `OverlayHost` entry | pass (`ConventionTest` rule `window`: `Popup(`/`Dialog(` only under `overlay/`; the two ported `Popup`s of `IosContextMenu`/`AppleContextMenu` became `OverlayEntry`, see their KDoc) |
| Anchor captured once; placement via `placePanel` | pass (`PlacePanelTest`, `PopoverTest`, goldens `e1.12-popover-above/below`, `e1.10-menu-clamped-edge`) |
| Menus morph from the anchor, short plain exit | pass (`MenuMorphTest`, goldens `e1.10-menu-+40ms/+100ms`) |
| Press-slide-release on menus, haptic tick per row | pass (`MenuSlideTest`, `ContextMenuGestureTest`, `HapticsTest`) |
| Long-press menus have a TalkBack equivalent | pass (`ContextMenuGesture.kt`/`ListRow.kt` expose `onLongClick` and `customActions`; `ListRowSemanticsTest`) |
| Modal entries: page behind cleared from semantics, `paneTitle`, back closes only the top, focus cleared | pass (`OverlayHostTest`, `AlertTest`, `ActionSheetTest`; `paneTitle` set in `OverlayHost`/`SheetEntry`) |
| Exit animations play for programmatic dismiss | pass (`OverlayHostTest`; `OverlayStates` page dismisses by button and by scrim, `CatalogNavigationTest.overlayStatesPageTriggersEveryOverlay`) |

## E. Glass

| Item | Result |
|---|---|
| Glass only on chrome; rows and lists flat translucent | pass (Rows/Lists/GroupedSection use `IosColors` fills; glass only in bars, circle buttons, popovers, sheets) |
| Tier/fallback logic; Flat in tests is not used on API >= 33 | pass (`GlassRealPathTest`, `NoFlatInTestsTest`, `GlassFallbackTest`). "Frost while the map pans" = later: E4.4 (no map in the catalog) |
| Effects built only on size/spec change; saturation as colour filter | pass (Convx `GlassEffect.kt` stack ported; `GlassEffectTest`, `BackdropFreezeTest`, `FrozenRecordCountTest`) |
| Pressed glass samples under the inverse transform | pass (golden `e1.26-liquid-glass-over-stripes`, `e1.4-press-*`: stripes stay still under a pressed button) |
| Label contrast >= 4.5:1 on every preset in light and dark | pass (`ContrastTest`, `TranslucentFallbackLegibilityTest`, Palette page prints the ratios: 18.8, 7.4, 4.3 (tertiary needs 3:1), accent 6.7, destructive 5.7, success 6.0) |
| Tab-bar blur parity (Convx `GlassEffect.kt:57`) | deviation: Convx's default glass blur is 3 dp (`GlassEffectConfig.blurRadius = 3f`, "Nav spec = 3dp (Q16)"; side panels 2 dp) and ours is the same number (`design/glass/GlassEffect.kt:61`). Text under the bar therefore shows doubled and smeared, not frosted (`e1.7-tabbar-rest-light`, "Contact 10" under the capsule; Tab bar page fs2 sheet). This is Convx and iOS 26 behaviour: the bar labels stay legible. Not changed. The rows under the *top* bar are blurred by `TopFadeBlur` (F6) |

## F. Controls and input

| Item | Result |
|---|---|
| Tab bar puck springs, `Role.Tab` + `selected`, labels drop at 1.5x font scale | pass (`TabBarSemanticsTest`, `TabBarGestureTest`, `PuckBoundsTest`; labels drop in the fs2 sweep) |
| Tab bar hold 150 ms / tap slop 24 dp / drag start 6 dp (glisse numbers) | deviation: the bar is Convx's device-proven `floatingtabbar` (ported in E1.29), which arbitrates tap vs drag with Compose touch slop and its own spring set; the glisse numbers belong to our retired hand-written bar. Behaviour tested in `TabBarGestureTest` (tap selects, 1.6 slots rounds to slot 2, release off the bar is ignored, wobble inside the cell still taps, vertical move before the hold belongs to the page) |
| Switch/slider/segmented follow glisse geometry; toggles give haptics | pass (`ToggleTest`, `SliderTest`, `SegmentedTest`, `IosControlsRulesTest`; `IosToggle` and `SegmentedControl` call `RovstiHaptics`) |
| Text fields: `BasicTextField(TextFieldState)`, caret, selection handles, magnifier, toolbar, IME action, above keyboard, clear keeps focus | pass (`TextFieldsPage`, `TextFieldsTest`, `ImeHidesBarTest`, goldens `e1.16-field-selection-toolbar`, `e1.16-composer-ime-open`; `ShadowNoopMagnifier` only for Robolectric) |
| Haptics through `RovstiHaptics` only, never during scroll | pass (`HapticEventTest`, `HapticsTest`; no page or component outside `haptics/` calls `performHapticFeedback` (grep)) |
| Touch targets >= 48 dp, merged semantics, custom actions for swipe | pass (`CatalogAccessibilityTest` over all 31 pages light and dark, and now `ChecklistSweepTest` at fs2/RTL; `ListRowSemanticsTest`) |

## G. Performance

| Item | Result |
|---|---|
| Deferred reads; recomposition count <= 2 on the hottest interaction | pass (`RecompositionTest`) |
| Lazy items have `key` + `contentType`; immutable models | pass (every `item(`/`items(` in catalog pages carries `key`; `DemoPages.kt` items are static and keyed; `:design:checkComposeStability` runs in `scripts/kotlin-ui-check.sh`) |
| Images/tiles sized from the slot; no crossfade in rows | pass (`ImageSlotTest`; `crossfade(false)` in the viewer, none in rows) |
| No main-thread JNI; tickers off Main | later: E3.x (`:shell` is the first JNI user; the catalog has no JNI) |
| Ambient animations gated on visible/frozen/animator scale/reduce motion | pass (`BackdropFreezeTest`, `NavTransitionFreezeTest`, `StaticRegionTest`, `MotionGateTest`) |

## H. Tests and evidence

| Item | Result |
|---|---|
| JVM tests for every new pure function | pass (`design/src/test`: placement, projection, collapse, tier, geometry; plus `ListRailInsetTest` for the new `endInset`) |
| Compose tests with `autoAdvance = false` for gestures incl. an interruption | pass (`ShotTest` sets `mainClock.autoAdvance = false`; interruptions in `MenuSlideTest`, `SheetGestureTest`, `TabBarGestureTest`) |
| Screenshots: light/dark, fs 1.0/2.0, RTL, empty/short/long list, popover above/below, sheet detents, list end with tab bar | pass: goldens for each (`e1.x-*`), and every page at fs2 and RTL via `ChecklistSweepTest`. Keyboard open on Messages = later: E5 (the composer golden `e1.16-composer-ime-open` covers the component). Stops with a fake tile layer = later: E4.1 |
| Every PNG opened and judged, written in the PR | pass: the 62 sweep PNGs (8 sheets), the 12 states goldens (R5 step 2), `design.png` (R5 step 3), and the R1-R5 goldens in their evidence lines |
| Repo checks pass | pass: `bash scripts/kotlin-ui-check.sh` (Gradle spotless, lint, tests, assemble, verify screenshots, stability, orphan goldens; Rust checks unchanged, no crate touched) |

## Larger items written down against stories

- E2.1 (first app module): turn on the `raw-dp` and `literal-text` convention rules (already implemented, they apply to
  `contacts`, `phone`, `messages`, `stops`, `shell`).
- E3.11 (`:shell`): `ListRow(endInset = ListScrollRailWidth)` for every list that shows the alphabet rail.
- E4.1 / E4.4 (Stops): full-screen map, Frost tier while the map pans, fake-tile screenshot.
- E4.16 (polish): re-judge the light push dim (0.85) and the 3 dp tab-bar blur on the owner's phone; both are Convx
  values. Bidi isolation of numeric strings in English demo text (`+9.4%`) is catalog-only and left alone.
