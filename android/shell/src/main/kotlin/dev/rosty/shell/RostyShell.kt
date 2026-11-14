package dev.rosty.shell

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.transition.ChromeSpec
import dev.rosty.design.transition.RostyEntries
import dev.rosty.design.transition.RostyNavHost
import dev.rosty.design.transition.TabNavigator
import kotlinx.collections.immutable.ImmutableList

/**
 * The root of every app: [RostyNavHost] over the app's [navigator], with the tab bar drawn from [chromeFor] and
 * [overlays] above everything (lifted from the catalog's `CatalogApp`, so the apps and the catalog run the same shell).
 * [entries] is the route table: `entry<Home> { ... }` per route class.
 *
 * Back goes to the overlays first, then to the current tab's stack, then to the activity (see `RostyNavHost`).
 */
@Composable
fun <R : Any> RostyShell(
    navigator: TabNavigator<R>,
    tabs: ImmutableList<TabItem>,
    chromeFor: (R) -> ChromeSpec,
    overlays: OverlayController,
    modifier: Modifier = Modifier,
    isMorphRoute: (R) -> Boolean = { false },
    chrome: @Composable BoxScope.() -> Unit = {},
    accessory: (@Composable (modifier: Modifier, isInline: Boolean) -> Unit)? = null,
    entries: RostyEntries<R>.() -> Unit,
) {
    RostyNavHost(
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
