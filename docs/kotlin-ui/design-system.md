# Rovsti design system (Kotlin, `android/design`)

A short usage guide for the `:design` module. It says what to reach for and the rules that matter; it does not repeat
numbers (springs, radii, tints, thresholds). Those live in `.claude/skills/convx-motion-compose/` and in the code's KDoc.
Every component has a page in the catalog (`android/catalog`, `CatalogPage`), and `docs/kotlin-ui/screenshots/design.png`
is a contact sheet of them all (`./gradlew :catalog:contactSheet`). Navigation wiring is in `shell-usage.md`; where a port
differs from its source see `api-notes.md`.

Licence: the module is GPL-3.0-or-later. Ported files keep the original header plus
`Ported from Convx (...), GPL-3.0, <path>` (or the glisse line); `android/THIRD_PARTY_NOTICES.md` lists every source and
`LicenceHeaderTest` enforces the headers.

## Rules that apply everywhere

1. **Overlays go through `LocalOverlay.current`** (menus, popovers, sheets, alerts, toasts, the photo viewer). Never open a
   window `Popup` or `Dialog` outside `design/overlay` (a convention test fails the build). Opening an overlay never changes
   the page's layout: entries are siblings of the page in the host.
2. **Lists take `LocalContentInsets`** as content padding, so the last row ends above the floating tab bar and the first
   row starts below the top chrome. Pages never hard-code bar heights.
3. **Press is transform-only.** Pressing scales, lifts or tints; it never changes a layout size, so nothing reflows under
   the finger (`PressNoLayoutShiftTest`). Use `scalePress`, `rowPress` or `glassPress`, not a custom `clickable` indication.
4. **One gutter, one scale.** Spacing comes from `Spacing`, text from `RovstiTheme.type` through `RovstiText`, colour from
   `RovstiTheme.colors`. App modules use no raw `dp` and no string literals in `Text` (convention tests).
5. **Motion goes through `RovstiMotion`** and respects `LocalReduceMotion`. Every drag hands its release velocity to a spring.
6. **Glass is the backdrop engine, not a blur.** On API 33+ with default settings every glass surface samples the backdrop
   (`GlassRealPathTest`). Surfaces that sample must live under a `RovstiBackdropRoot`/`BackdropScope` source.
7. **Text comes from `strings.xml` or Rust sentences**, time from the injected clock, no `Thread.sleep`.

## Packages

### theme
`RovstiTheme { }` supplies `colors`, `type`, light/dark following the system. Use `Spacing.gutter/s/m/l`, `Sizes.touch`,
`Sizes.button`.
```kotlin
RovstiText("Recents", style = RovstiTheme.type.title3, color = RovstiTheme.colors.labelSecondary)
```

### motion
`RovstiMotion` holds the named springs and tweens; `Fling`, `Rubber`, `IosOverscroll` give momentum and rubber-banding.
```kotlin
val progress by animateFloatAsState(if (open) 1f else 0f, RovstiMotion.appear())
```

### press, haptics
`scalePress()` for buttons and tiles, `rowPress()` for list rows, `glassPress()` for glass controls.
`rememberRovstiHaptics(mode)` for the tick on selection changes.
```kotlin
Modifier.scalePress().clickable(role = Role.Button, onClick = onTap)
```

### glass, backdrop, ios/foundation
`Modifier.rovstiGlass`, `panelGlass`, `sheetGlass` draw the glass; `backdrop/` is the ported engine (GlassPool, freeze
helpers) and `ios/foundation` the iOS materials. Apps normally use the components below and never call the engine.
```kotlin
Box(Modifier.panelGlass(RoundedCornerShape(24.dp), debugTag = "my-panel")) { content() }
```

### controls
`CapsuleButton`, `TextButton`, `DestructiveButton`, `GlassIconButton`, `GlassCircleButton`, `SegmentedControl`, `ChipRow`
(a single scrolling row, never wrapping), `Badge`, `EmptyState`; `ios/component` has `IosToggle` and `IosSlider`.
```kotlin
CapsuleButton(text = "Call", onClick = ::call)
```

### list
`InsetGroupedSection { row { ListRow(...) } }` for settings-like groups, `ListRow` with `Avatar` for contacts,
`SwipeRow` plus one `rememberSwipeRowGroup()` for swipe actions, `AlphabetScrollBar` for index scrubbing.
```kotlin
InsetGroupedSection(header = "Pages") { row { ListRow(title = name, onClick = open) } }
```

### chrome
`RovstiBackdropRoot` at the app root, `LargeTitleScaffold` per page (large title that collapses into the frosted bar),
`Toolbar`/`ToolbarButton` for the capsules, `RovstiTabBar` (floating, draggable puck, folds on scroll) with an accessory slot.
```kotlin
LargeTitleScaffold("Contacts", listState, actions, navigation = NavigationButton { navigator.pop() }) {
    items(rows) { ListRow(title = it.name) }
}
```

### overlay
Anchored: `Modifier.menuTrigger(controller, items)`, `Modifier.popoverTrigger(controller) { content }`,
`Modifier.longPressMenu(controller, items, style)`. Modal: `SheetEntry` (detents, non-modal option), `presentPageSheet`,
`AlertEntry`, `ActionSheetEntry`, `confirmDestructive`. Feedback: `showToast`, `showUndo`.
```kotlin
controller.confirmDestructive("Delete conversation", onConfirm = ::delete, title = "Delete this conversation?")
```

### text
`GlassTextField`, `SearchField`, `ComposerField` have caret placement, selection handles, the copy/paste menu and correct
IME geometry; use them instead of a bare `BasicTextField`.

### transition
`RovstiNavHost` with `TabNavigator`: per-tab stacks, push/pop with parallax, predictive back, tab cross-fade.
`Modifier.rovstiSharedBounds(key)` joins an avatar to its detail page.
```kotlin
RovstiNavHost(navigator, tabItems, ::chromeFor, overlays) { entry<Home> { HomePage() } }
```

### media
`RowImage` loads at the size of its slot (Coil 3 loader from `rovstiImageLoader`); `OverlayController.showPhotoViewer`
opens the pager with pinch/double-tap zoom and swipe-down dismiss.

## Testing a screen
Extend `ShotTest` (`:testing`): `screenTest { }` turns on the accessibility checks (48 dp targets, labels, roles),
`shot(name)` records light and dark goldens (Roborazzi), `CountRecompositions` bounds recomposition of hot slots.
Run `bash scripts/kotlin-ui-check.sh` before finishing; it ends with `ALL GREEN`.
