/*
 * Structure follows Convx's AppRootContent (Convx: MainActivity.kt / AppBackdropState.kt): the content is recorded once
 * by layerBackdrop; the bars and the overlay entries are siblings drawn AFTER it, never children, so a glass surface
 * samples the recorded layer without recursing into itself.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/MainActivity.kt
 * Changes for Rovsti: a composable of its own (no Activity, no navigation graph); the overlay host is split into a page half
 * (recorded, so a sheet's push-back is part of what menus and sheets sample) and an entries half (outside the recording).
 */
package dev.rovsti.design.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.rememberAppBackdropState
import dev.rovsti.design.glass.LocalAppBackdrop
import dev.rovsti.design.glass.LocalAppBackdropFrozen
import dev.rovsti.design.glass.LocalAppMotionFrozen
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayEntries
import dev.rovsti.design.overlay.OverlayPage
import dev.rovsti.design.overlay.OverlayProvider
import dev.rovsti.design.theme.RovstiTheme

/**
 * The one backdrop of the app. [content] (the current page, behind a sheet's push-back) is recorded by `layerBackdrop`
 * together with a [nested scroll connection][dev.rovsti.design.backdrop.AppBackdropState.scrollConnection] that freezes
 * the capture while a list scrolls. [chrome] (floating bars) and the overlay entries come after it as siblings, with
 * [LocalAppBackdrop], [LocalIosBackdrop], [LocalAppBackdropFrozen] and [LocalAppMotionFrozen] provided, so every glass
 * surface in them samples the recorded page. [route] names the current page: a change freezes the capture for a
 * moment while the transition runs.
 *
 * No page creates its own source. A page that has its own floating bars over its own scrolling content uses
 * [BackdropScope] for them; everything else just draws.
 */
@Composable
fun RovstiBackdropRoot(
    overlays: OverlayController,
    modifier: Modifier = Modifier,
    baseBg: Color = RovstiTheme.colors.page,
    route: String? = null,
    chrome: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state = rememberAppBackdropState(baseBg = baseBg, currentRoute = route)
    OverlayProvider(overlays) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(state.appBackdrop).nestedScroll(state.scrollConnection)) {
                OverlayPage(overlays, content = content)
            }
            // Siblings AFTER the recorded subtree (Convx: "avoid a recursive RenderNode draw").
            CompositionLocalProvider(
                LocalAppBackdrop provides state.appBackdrop,
                LocalIosBackdrop provides state.appBackdrop,
                LocalAppBackdropFrozen provides state.frozenProvider,
                LocalAppMotionFrozen provides state.motionFrozen,
            ) {
                Box(Modifier.fillMaxSize()) { chrome() }
                OverlayEntries(overlays)
            }
        }
    }
}

/**
 * A scrolling [content] with its own floating [chrome] on top (a large-title bar, a tab bar): [content] is recorded
 * into a local backdrop that only [chrome] samples. Needed because chrome that is a child of the root's recorded
 * subtree cannot sample the root's layer (a layer cannot draw into itself). Capture freezing follows the root's.
 */
@Composable
fun BackdropScope(
    modifier: Modifier = Modifier,
    baseBg: Color = RovstiTheme.colors.page,
    chrome: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val local =
        dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop(
            onDraw =
                androidx.compose.runtime.remember(baseBg) {
                    val bg = baseBg
                    {
                        drawRect(bg)
                        drawContent()
                    }
                },
        )
    Box(modifier) {
        Box(Modifier.fillMaxSize().layerBackdrop(local), content = content)
        CompositionLocalProvider(
            LocalAppBackdrop provides local,
            LocalIosBackdrop provides local,
        ) {
            Box(Modifier.fillMaxSize()) { chrome() }
        }
    }
}
