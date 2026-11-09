/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/ScreenChrome.kt
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/GlassScreenChrome.kt
 * Changes for Rovsti: package dev.rovsti.design.chrome; LocalPlayerAwareWindowInsets became the insets the caller passes
 * (LocalContentInsets: gutters and bottom bar); AppTheme became RovstiTheme; the large title is 34 sp bold at the 16 dp
 * gutter; the glass locals (LocalAppBackdrop, LocalIosBackdrop) are provided to the bar and the fade only, never to the
 * page content, because content inside the recorded layer cannot sample it (Convx does the same for its own screens by
 * drawing their buttons flat); reduce transparency draws the solid bar; ScreenLazyPage takes the title progress, the
 * content padding and a width cap from the caller, since Rovsti smooths the collapse (TitleCollapse.kt).
 */
package dev.rovsti.design.chrome

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.rovsti.design.backdrop.backdrops.BackdropFreeze
import dev.rovsti.design.backdrop.backdrops.LayerBackdrop
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberBackdropFreeze
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.glass.LocalAppBackdrop
import dev.rovsti.design.glass.LocalGlassSettings
import dev.rovsti.design.glass.TopFadeBlur
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * Provides attached backdrop and Haze wiring for flat screens. Keep [FrostedTopBar] a sibling of the captured scrolling
 * content to avoid self-capture.
 */
@Stable
class GlassScreenChrome internal constructor(
    val backdrop: LayerBackdrop,
    val hazeState: HazeState,
    private val freeze: BackdropFreeze,
) {
    // Reuse the scrolling content's frozen state to avoid re-running its effects on every scroll frame.
    val frozen: () -> Boolean get() = freeze.frozen

    // Keep the haze source around scrollable content only. If it contains the blur overlay, Haze captures that overlay
    // itself and the blur has no useful source.

    /** Apply to a Box that wraps only the screen's scrolling content. */
    fun screenModifier(): Modifier = Modifier.hazeSource(hazeState)

    /** Apply to the screen's scrolling root (LazyColumn/Column/etc). */
    fun contentModifier(): Modifier =
        Modifier
            .nestedScroll(freeze.connection)
            // Isolate sibling redraws and flatten list content for the backdrop capture.
            .graphicsLayer()
            .layerBackdrop(backdrop, frozen = freeze.frozen)
            .graphicsLayer()
}

@Composable
fun rememberGlassScreenChrome(background: Color): GlassScreenChrome {
    val freeze = rememberBackdropFreeze()
    val backdrop =
        rememberLayerBackdrop(
            // Fills the screen background into the capture BEFORE content, same reason MainActivity's appBackdrop does:
            // without it the list records onto a transparent canvas and the blur reads as part-transparent / ghosted
            // instead of solid frosted content.
            onDraw =
                remember(background) {
                    val bg = background
                    {
                        drawRect(bg)
                        drawContent()
                    }
                },
        )
    val hazeState = remember { HazeState() }
    return remember(backdrop, hazeState, freeze) { GlassScreenChrome(backdrop, hazeState, freeze) }
}

/**
 * Shared layout for pushed screens. The scrolling content is the Haze source; the fade blur and top bar stay as
 * siblings so neither samples itself. The bar keeps its back button visible and reveals the compact title after
 * scrolling.
 */
@Composable
fun ScreenChromeBox(
    title: String,
    titleAlpha: Float,
    pageColor: Color,
    chrome: GlassScreenChrome,
    onBack: (() -> Unit)?,
    onBackLongClick: (() -> Unit)?,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    barModifier: Modifier = Modifier,
    backLabel: String = "Back",
    titleSideReserve: Dp = 96.dp,
    titleEndReserve: Dp = titleSideReserve,
    titleAlphaProvider: (() -> Float)? = null,
    // Rovsti: 0..1 collapse of a large title. While the large title is still on screen the strip under the bar must stay
    // sharp, so the fade blur and a firming page-colour scrim over the bar band follow it. Null = always fully blurred.
    collapse: (() -> Float)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalGlassSettings.current
    val solidBar = settings.reduceDynamicBlur || settings.reduceTransparency
    Box(
        modifier =
            modifier
                .fillMaxSize()
                // Explicit rather than inherited from whatever the NavHost paints: a page that paints nothing shows the
                // app background through the blur's own tint, which is what makes a top strip read as a different
                // colour from the rest of the screen.
                .background(pageColor),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Inside the source, so the blur samples a filled canvas rather than transparency at the top of
                    // the page.
                    .background(pageColor)
                    .then(chrome.screenModifier()),
            content = content,
        )

        // The bar and the fade sample this screen's own backdrop; the page content above never does.
        CompositionLocalProvider(
            LocalAppBackdrop provides chrome.backdrop,
            LocalIosBackdrop provides chrome.backdrop,
        ) {
            if (!solidBar) {
                TopFadeBlur(
                    hazeState = chrome.hazeState,
                    pageColor = pageColor,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer { alpha = collapse?.invoke()?.coerceIn(0f, 1f) ?: 1f },
                )
                if (collapse != null) {
                    BarScrim(pageColor, collapse, Modifier.align(Alignment.TopCenter))
                }
            }

            FrostedTopBar(
                onBack = onBack,
                onBackLongClick = onBackLongClick,
                backLabel = backLabel,
                title = title,
                titleAlpha = titleAlpha,
                titleAlphaProvider = titleAlphaProvider,
                titleSideReserve = titleSideReserve,
                titleEndReserve = titleEndReserve,
                actions = actions,
                modifier = barModifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Rovsti: a page-colour wash over the bar band that firms up as the large title collapses (up to [BarScrimPeak]), so a
 * row passing under the inline title is never legible next to it, on top of the blur of TopFadeBlur.
 */
@Composable
private fun BarScrim(
    pageColor: Color,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
) {
    val band = topBarHeight()
    Box(
        modifier
            .fillMaxWidth()
            .height(band + 24.dp)
            .drawBehind {
                val peak = BarScrimPeak * collapse().coerceIn(0f, 1f)
                val bar = band.toPx() / size.height
                drawRect(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0f to pageColor.copy(alpha = peak),
                                bar to pageColor.copy(alpha = peak),
                                1f to pageColor.copy(alpha = 0f),
                            ),
                    ),
                )
            }.testTag("bar-scrim"),
    )
}

private const val BarScrimPeak = 0.8f

/**
 * The chrome around a body the caller lays out itself: a grid, a WebView, a two-pane layout, an existing list you do
 * not want to restructure.
 *
 * The body is handed the screen's [GlassScreenChrome]: put [GlassScreenChrome.contentModifier] on the scrolling root so
 * the bar's glass has something to refract. The caller also owns [titleAlpha], since only it knows what scrolling means
 * on that layout: pass [rememberTitleCollapseProgressState] where there is a list, `1f` where the page does not scroll
 * and the title should simply stay.
 */
@Composable
fun ScreenScaffold(
    title: String,
    titleAlpha: Float,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    pageColor: Color = RovstiTheme.colors.page,
    actions: @Composable () -> Unit = {},
    titleAlphaProvider: (() -> Float)? = null,
    modifier: Modifier = Modifier,
    barModifier: Modifier = Modifier,
    backLabel: String = "Back",
    titleSideReserve: Dp = 96.dp,
    titleEndReserve: Dp = titleSideReserve,
    content: @Composable BoxScope.(GlassScreenChrome) -> Unit,
) {
    val chrome = rememberGlassScreenChrome(background = pageColor)
    ScreenChromeBox(
        title = title,
        titleAlpha = titleAlpha,
        titleAlphaProvider = titleAlphaProvider,
        pageColor = pageColor,
        chrome = chrome,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        actions = actions,
        modifier = modifier,
        barModifier = barModifier,
        backLabel = backLabel,
        titleSideReserve = titleSideReserve,
        titleEndReserve = titleEndReserve,
    ) {
        content(chrome)
    }
}

/**
 * A screen's large title, the one the compact bar takes over from. [top] lets the scaffold centre its first line in the
 * bar band; [endReserve] keeps a long title clear of the toolbar.
 */
@Composable
fun ScreenLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    endReserve: Dp = 0.dp,
    textModifier: Modifier = Modifier,
) {
    Box(
        modifier.padding(
            start = Spacing.gutter,
            end = Spacing.gutter + endReserve,
            top = top,
            bottom = Spacing.s,
        ),
    ) {
        IosText(
            text = title,
            style = RovstiTheme.type.largeTitle,
            color = RovstiTheme.colors.labelPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = textModifier,
        )
    }
}

/**
 * A page whose body is a plain scrolling [Column].
 *
 * The status-bar clearance is a [Spacer] *inside* the scroll rather than padding around it, which is the whole point:
 * content has to travel under the bar for the blur to have anything to frost.
 */
@Composable
fun ScreenPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    pageColor: Color = RovstiTheme.colors.page,
    horizontalPadding: Dp = 0.dp,
    actions: @Composable () -> Unit = {},
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    scrollState: ScrollState = rememberScrollState(),
    largeTitle: @Composable () -> Unit = { ScreenLargeTitle(title) },
    content: @Composable ColumnScope.() -> Unit,
) {
    val chrome = rememberGlassScreenChrome(background = pageColor)
    // Measured, not guessed: the title block's height is the exact distance the page has to travel before the compact
    // title should be fully in.
    var titleBlockHeight by remember { mutableIntStateOf(1) }

    ScreenChromeBox(
        title = title,
        titleAlpha = 0f,
        titleAlphaProvider = {
            // Read ScrollState in draw phase via provider chain: FrostedTopBar invokes this only inside
            // graphicsLayer/derivedStateOf.
            (scrollState.value / titleBlockHeight.toFloat()).coerceIn(0f, 1f)
        },
        pageColor = pageColor,
        chrome = chrome,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        actions = actions,
        // The strip under the bar stays sharp while the large title is on screen, as in ScreenLazyPage.
        collapse = { (scrollState.value / titleBlockHeight.toFloat()).coerceIn(0f, 1f) },
    ) {
        Column(
            modifier =
                modifier
                    .then(chrome.contentModifier())
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPadding),
            verticalArrangement = verticalArrangement,
        ) {
            Column(Modifier.onSizeChanged { titleBlockHeight = it.height.coerceAtLeast(1) }) {
                Spacer(Modifier.statusBarsPadding())
                largeTitle()
            }

            content()

            Spacer(Modifier.height(80.dp))
        }
    }
}

/**
 * Progress from 0 to 1 as the large title scrolls out. Uses its measured height so the compact title appears only
 * after the large title is fully gone. Convx: ui/component/ScreenTitleBar.kt `rememberTitleCollapseProgressState`.
 */
@Composable
fun rememberTitleCollapseProgressState(
    state: LazyListState,
    titleItemKey: Any? = null,
): State<Float> =
    remember(state, titleItemKey) {
        derivedStateOf {
            val visible = state.layoutInfo.visibleItemsInfo
            val titleItem =
                if (titleItemKey != null) {
                    visible.firstOrNull { it.key == titleItemKey }
                } else {
                    visible.firstOrNull { it.index == 0 }
                }
            if (titleItem == null) {
                // Not in the visible range at all: scrolled fully past.
                1f
            } else {
                (-titleItem.offset.toFloat() / titleItem.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        }
    }

/**
 * The [LazyColumn] flavour, for a page whose body is a real list.
 *
 * Emits the title block as `item("title")`, so the collapse progress can be measured off the list's own layout info.
 * [titleProgress] replaces Convx's linear progress when the caller smooths it (Rovsti's LargeTitleScaffold does).
 */
@Composable
fun ScreenLazyPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    pageColor: Color = RovstiTheme.colors.page,
    contentPadding: PaddingValues = PaddingValues(),
    actions: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    titleProgress: State<Float>? = null,
    titleAlphaProvider: ((progress: Float) -> Float)? = null,
    barModifier: Modifier = Modifier,
    backLabel: String = "Back",
    titleSideReserve: Dp = 96.dp,
    titleEndReserve: Dp = titleSideReserve,
    listModifier: Modifier = Modifier,
    largeTitle: @Composable () -> Unit = { ScreenLargeTitle(title) },
    content: LazyListScope.() -> Unit,
) {
    val chrome = rememberGlassScreenChrome(background = pageColor)
    val lazyTitleProgress = titleProgress ?: rememberTitleCollapseProgressState(listState, titleItemKey = "title")

    ScreenChromeBox(
        title = title,
        titleAlpha = 0f,
        titleAlphaProvider = { titleAlphaProvider?.invoke(lazyTitleProgress.value) ?: lazyTitleProgress.value },
        pageColor = pageColor,
        chrome = chrome,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        actions = actions,
        modifier = modifier,
        barModifier = barModifier,
        backLabel = backLabel,
        titleSideReserve = titleSideReserve,
        titleEndReserve = titleEndReserve,
        collapse = { lazyTitleProgress.value },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopCenter).then(listModifier).then(chrome.contentModifier()),
            contentPadding = contentPadding,
        ) {
            item(key = "title") {
                Column { largeTitle() }
            }

            content()
        }
    }
}
