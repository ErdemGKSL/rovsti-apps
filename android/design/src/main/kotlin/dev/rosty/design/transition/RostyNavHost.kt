package dev.rosty.design.transition

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import dev.rosty.design.chrome.BottomScrim
import dev.rosty.design.chrome.ChromeMetrics
import dev.rosty.design.chrome.LocalChromeMetrics
import dev.rosty.design.chrome.LocalContentInsets
import dev.rosty.design.chrome.RostyBackdropRoot
import dev.rosty.design.chrome.RostyTabBar
import dev.rosty.design.chrome.SystemInsets
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.chrome.contentInsetsOf
import dev.rosty.design.chrome.rememberTabBarMinimizer
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.theme.RostyTheme
import kotlinx.collections.immutable.ImmutableList

/**
 * The route table of [RostyNavHost]: `entry<Detail> { route -> DetailPage(route) }` for each route class (ORIGINAL; a
 * thin stand-in for Navigation 3's `entryProvider {}` DSL, which is keyed by the wrapped [StackKey] and not by the route).
 */
class RostyEntries<R : Any> {
    @PublishedApi
    internal val matchers = mutableListOf<Pair<(R) -> Boolean, @Composable (R) -> Unit>>()

    inline fun <reified K : R> entry(noinline content: @Composable (K) -> Unit) {
        matchers += ({ route: R -> route is K }) to ({ route: R -> content(route as K) })
    }

    @Composable
    internal fun Render(route: R) {
        val match = matchers.firstOrNull { it.first(route) } ?: error("No entry registered for $route")
        match.second(route)
    }
}

/**
 * Which bar item is selected and what a tap on one does, for an app whose bar items are not all navigator tabs
 * (ORIGINAL; Stops: one stack under a Trip / Saved / Settings bar, so pushed pages keep the full bar, owner rule F16).
 * [selectedId] names the item of the top route; [onSelect] gets the id of a tapped item, the selected one included.
 */
class TabSelection<R : Any>(
    val selectedId: (R) -> String,
    val onSelect: (String) -> Unit,
)

/** Test tag of the page container of the stack entry `<tab>-<depth>` (depth 0 is the tab's root). */
fun pageTag(
    tab: Tab,
    depth: Int,
): String = "page-${tab.id}-$depth"

/**
 * The navigation scaffold (ORIGINAL; Convx's `AppNavHost` + `AppRootContent` split into one composable):
 *
 * 1. [RostyBackdropRoot] records the pages once; the tab bar, [chrome] and the overlay entries come after it. The root
 *    is given the route and `navigator.lastNavWasPop`, so the glass freezes (`NavTransitionFreeze`) for a push or a tab
 *    switch and stays live for a pop.
 * 2. [SharedTransitionLayout] around Navigation 3's `NavDisplay`: pages push with the Convx slide, parallax and dim
 *    ([NavTransitions]), pop and predictive-back with the mirrored pop, and tabs cross-fade. System back goes to
 *    the overlay host first (its `BackHandler` is registered later), then `NavDisplay` pops [navigator]'s stack, and at
 *    the root the activity finishes.
 * 3. Every page gets its own `LocalContentInsets` from `chromeFor(route)`: a page whose [ChromeSpec.tabBar] is Hidden
 *    leaves no room for the bar, so a page never jumps when the bar slides in or out while it leaves.
 *
 * Reselecting the current tab pops to the root, or emits [ScrollToTop] (collect `navigator.scrollToTop` in the root page).
 * A tap on the folded bar only unfolds it; it is not a reselect.
 *
 * @param isMorphRoute a page that grows out of a tile of the page below: it slides in with the artwork easing and the
 *   page below holds still and dims.
 * @param chrome extra floating chrome (a mini player, a call banner), drawn with the glass locals of the root.
 * @param tabSelection null (the default): the bar's items are the navigator's tabs. Otherwise it decides the selected
 *   item and what a tap does.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <R : Any> RostyNavHost(
    navigator: TabNavigator<R>,
    tabs: ImmutableList<TabItem>,
    chromeFor: (R) -> ChromeSpec,
    overlays: OverlayController,
    modifier: Modifier = Modifier,
    isMorphRoute: (R) -> Boolean = { false },
    chrome: @Composable BoxScope.() -> Unit = {},
    // The pill that rides on the tab bar (folded: beside the circle). Null: none. The bar's measured height includes it,
    // so pages leave room for it through `LocalContentInsets`.
    accessory: (@Composable (modifier: Modifier, isInline: Boolean) -> Unit)? = null,
    tabSelection: TabSelection<R>? = null,
    entries: RostyEntries<R>.() -> Unit,
) {
    val keys = navigator.keys()
    val top = keys.last()
    val routeId = "${top.tab.id}/${top.depth}/${top.route}"
    val hostMetrics = remember { ChromeMetrics() }
    val minimizer = rememberTabBarMinimizer()
    val chromeForNow by rememberUpdatedState(chromeFor)
    val registry = RostyEntries<R>().apply(entries)
    val registryNow by rememberUpdatedState(registry)
    val transitions = remember(isMorphRoute) { NavTransitions(isMorphRoute) }
    val barMode = chromeFor(top.route).tabBar
    val selectedId = tabSelection?.selectedId?.invoke(top.route) ?: navigator.currentTab.id
    val selectedIndex = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    LaunchedEffect(routeId) { minimizer.expand() }

    RostyBackdropRoot(
        overlays = overlays,
        modifier = modifier,
        route = routeId,
        lastNavWasPop = { navigator.lastNavWasPop },
        chrome = {
            CompositionLocalProvider(LocalChromeMetrics provides hostMetrics) {
                AnimatedVisibility(
                    visible = barMode != TabBarMode.Hidden,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter =
                        slideInVertically(
                            RostyMotion.appear(
                                androidx.compose.ui.unit
                                    .IntOffset(1, 1),
                            ),
                        ) { it } +
                            fadeIn(),
                    exit =
                        slideOutVertically(
                            RostyMotion.appear(
                                androidx.compose.ui.unit
                                    .IntOffset(1, 1),
                            ),
                        ) { it } +
                            fadeOut(),
                ) {
                    Box {
                        BottomScrim(hostMetrics, Modifier.align(Alignment.BottomCenter))
                        RostyTabBar(
                            tabs = tabs,
                            selected = selectedIndex,
                            onSelect = { pickTab(navigator, tabSelection, tabs[it].id) },
                            onReselect = { pickTab(navigator, tabSelection, tabs[it].id) },
                            modifier = Modifier.align(Alignment.BottomCenter).testTag("tab-bar"),
                            minimizer = minimizer,
                            // A tap on the folded circle only unfolds the bar (the minimizer): it is not a reselect, so
                            // it never pops the stack or scrolls the page to the top.
                            folded = barMode == TabBarMode.Folded,
                            chrome = hostMetrics,
                            accessory = accessory,
                        )
                    }
                }
                chrome()
            }
        },
    ) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            val sharedTransition = this
            val entryProvider =
                remember(sharedTransition) {
                    { key: StackKey<R> ->
                        NavEntry(key, metadata = mapOf(StackKeyMetadata to key)) {
                            val pageBack =
                                if (key.depth > 0) {
                                    PageBack(
                                        onBack = { navigator.pop() },
                                        onLongClick = { navigator.select(navigator.currentTab) },
                                    )
                                } else {
                                    null
                                }
                            check(pageBack == null || chromeForNow(key.route).tabBar != TabBarMode.Folded) {
                                "TabBarMode.Folded is for tab roots only, but ${key.route} is pushed (depth ${key.depth})"
                            }
                            CompositionLocalProvider(LocalPageBack provides pageBack) {
                                RostyPage(
                                    key = key,
                                    sharedTransition = sharedTransition,
                                    spec = chromeForNow(key.route),
                                    hostMetrics = hostMetrics,
                                    minimizer = minimizer,
                                ) { registryNow.Render(key.route) }
                            }
                        }
                    }
                }
            NavDisplay(
                backStack = keys,
                modifier = Modifier.fillMaxSize(),
                onBack = { navigator.pop() },
                sharedTransitionScope = sharedTransition,
                transitionSpec = transitions.transitionSpec,
                popTransitionSpec = transitions.popTransitionSpec,
                predictivePopTransitionSpec = transitions.predictivePopTransitionSpec,
                entryProvider = entryProvider,
            )
        }
    }
}

private fun <R : Any> pickTab(
    navigator: TabNavigator<R>,
    selection: TabSelection<R>?,
    id: String,
) {
    if (selection != null) selection.onSelect(id) else navigator.select(Tab(id))
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun <R : Any> RostyPage(
    key: StackKey<R>,
    sharedTransition: androidx.compose.animation.SharedTransitionScope,
    spec: ChromeSpec,
    hostMetrics: ChromeMetrics,
    minimizer: dev.rosty.design.chrome.TabBarMinimizer,
    content: @Composable () -> Unit,
) {
    val pageMetrics = remember { ChromeMetrics() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val bottomBar =
        if (spec.tabBar == TabBarMode.Hidden) {
            0.dp
        } else {
            with(density) { hostMetrics.bottomBarPx.toDp() }
        }
    // A toast or the undo pill floats over the end of the page: the list gets room to scroll its last row clear of it.
    val toastPx = LocalOverlayOrNull.current?.toastClearancePx ?: 0
    val toastRoom by animateDpAsState(
        targetValue = with(density) { toastPx.toDp() },
        animationSpec = RostyMotion.bottomSheetMount(),
        label = "toast-room",
    )
    val base =
        contentInsetsOf(
            system = SystemInsets.of(WindowInsets.safeDrawing.asPaddingValues(), direction),
            topBar = with(density) { pageMetrics.topBarPx.toDp() },
            bottomBar = bottomBar,
        )
    val insets =
        if (toastRoom > 0.dp) {
            PaddingValues.Absolute(
                left = base.calculateLeftPadding(LayoutDirection.Ltr),
                right = base.calculateRightPadding(LayoutDirection.Ltr),
                top = base.calculateTopPadding(),
                bottom = base.calculateBottomPadding() + toastRoom,
            )
        } else {
            base
        }
    val scopes = SharedScopes(sharedTransition, LocalNavAnimatedContentScope.current)
    CompositionLocalProvider(
        LocalSharedScopes provides scopes,
        LocalContentInsets provides insets,
        LocalChromeMetrics provides pageMetrics,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(RostyTheme.colors.page)
                .nestedScroll(minimizer)
                .testTag(pageTag(key.tab, key.depth)),
        ) { content() }
    }
}
