# Shell usage: navigation, chrome, overlays (E1.18)

The contract an app follows; `:catalog` (`CatalogApp.kt`, `CatalogRoute.kt`) is the working example and E3.11 `:shell` moves it into a module.

1. **Routes.** One `@Serializable sealed interface Route`; one class per page, data in the class (`ContactDetail(val id: Int)`).
   Tabs are `Tab("id")` values; each tab has a root route.
2. **`chromeFor(route): ChromeSpec`.** A pure function (`ChromeSpec(tabBar = Visible | Folded | Hidden, largeTitle)`), unit-tested on the JVM
   (`ChromeSpecTest`). `RovstiNavHost` draws the tab bar from it and gives every page its own `LocalContentInsets` (a Hidden page leaves no
   room for the bar and does not jump when the bar slides out while the page leaves).
3. **`routeFor(extras)`.** A pure function from intent extras to `Pair<Tab, List<Route>>`; the activity calls
   `navigator.resetTo(tab, routes)` for a deep link.
4. **Wiring.** `val navigator = rememberSaveable(saver = TabNavigator.saver(tabs, roots, Route.serializer())) { TabNavigator(tabs, roots) }`,
   `val overlays = remember { OverlayController() }`, then
   `RovstiNavHost(navigator, tabItems, ::chromeFor, overlays, isMorphRoute = { ... }) { entry<Home> { ... }; entry<Detail> { r -> ... } }`.
   The host composes, in this order: `RovstiBackdropRoot` (records the pages), `SharedTransitionLayout { NavDisplay }`, the tab bar and
   the `chrome` slot (siblings after the recording), and the overlay entries last. System back: overlay, then the tab stack, then the activity.
5. **Pages.** A page reads `LocalContentInsets` for list padding, uses `LargeTitleScaffold(navigation = NavigationButton { navigator.pop() })`
   and collects `navigator.scrollToTop` (filter on its tab) to scroll to the top on reselect.
6. **Shared elements.** `Modifier.rovstiSharedBounds("avatar-$id")` on both ends (500 ms `Motion.sharedArtwork()`, drawn in the overlay above both
   pages). It is a no-op outside the host. `isTransitionActive()` lets heavy work wait for the motion.
7. **Motion.** Push: the new page slides a full width, the old one moves 0.3 W under it and dims to 0.85; pop mirrors it; predictive back scrubs
   the pop; a tab switch is a 220/160 ms cross-fade; a `isMorphRoute` page holds the old one still and dims it to 0.7. The glass freezes for a push or
   tab switch (`NavTransitionFreeze`) and stays live for a pop (`navigator.lastNavWasPop`).
