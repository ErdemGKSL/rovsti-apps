package dev.rovsti.shell

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.rovsti.design.chrome.TabItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.ChromeSpec
import dev.rovsti.design.transition.RovstiEntries
import dev.rovsti.design.transition.RovstiNavHost
import dev.rovsti.design.transition.TabNavigator
import kotlinx.collections.immutable.ImmutableList

/**
 * The root of every app: [RovstiNavHost] over the app's [navigator], with the tab bar drawn from [chromeFor] and
 * [overlays] above everything (lifted from the catalog's `CatalogApp`, so the apps and the catalog run the same shell).
 * [entries] is the route table: `entry<Home> { ... }` per route class.
 *
 * Back goes to the overlays first, then to the current tab's stack, then to the activity (see `RovstiNavHost`).
 */
@Composable
fun <R : Any> RovstiShell(
    navigator: TabNavigator<R>,
    tabs: ImmutableList<TabItem>,
    chromeFor: (R) -> ChromeSpec,
    overlays: OverlayController,
    modifier: Modifier = Modifier,
    isMorphRoute: (R) -> Boolean = { false },
    chrome: @Composable BoxScope.() -> Unit = {},
    accessory: (@Composable (modifier: Modifier, isInline: Boolean) -> Unit)? = null,
    entries: RovstiEntries<R>.() -> Unit,
) {
    RovstiNavHost(
        navigator = navigator,
        tabs = tabs,
        chromeFor = chromeFor,
        overlays = overlays,
        modifier = modifier,
        isMorphRoute = isMorphRoute,
        chrome = chrome,
        accessory = accessory,
        entries = entries,
    )
}
