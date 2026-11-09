// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/ScreenChrome.kt (the page, bar and
// fade come from ScreenChrome.kt, FrostedTopBar.kt and TopFadeBlur.kt). The facade below, the
// title cross-fade (TitleCollapse.kt) and the toolbar grouping are Rovsti's own.
package dev.rovsti.design.chrome

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.LocalGlassSettings
import dev.rovsti.design.motion.IosOverscrollEffect
import dev.rovsti.design.motion.rememberIosOverscrollFactory
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList

/** Numbers of the large-title scaffold (SKILL navigation-layout.md 10). */
object ScaffoldSpec {
    /** The inline bar under the status bar; Convx `TopBarContentHeight`. The large title's first line is centred in it. */
    val BarHeight: Dp = TopBarContentHeight

    /** The large title rises this far while it fades. */
    val TitleLift: Dp = 12.dp
}

/** A back button on the leading edge of a pushed page. */
class NavigationButton(
    val label: String = "Back",
    val onClick: () -> Unit,
)

/**
 * A scrolling page with an iOS large title.
 *
 * The page is Convx's [ScreenLazyPage]: the title is item 0 of the list (key `"title"`, 34 sp bold, at most two lines),
 * and [FrostedTopBar] floats above it with [dev.rovsti.design.glass.TopFadeBlur] behind the inline title, so rows
 * passing under the collapsed bar are blurred and faded rather than printed under the title. As the large title scrolls
 * away it lifts [ScaffoldSpec.TitleLift] and fades while the inline title fades in (both by the spring-smoothed
 * [rememberTitleCollapse], read only in `graphicsLayer`), and never both readable at once.
 *
 * - The title's first line is centred on the toolbar capsules (never taller than 44 dp). With a [navigation] button the
 *   bar row belongs to the button and the title starts below it.
 * - With reduce transparency the bar is solid and a 0.5 dp divider appears once the title has collapsed.
 * - [LocalContentInsets] (or, without a screen root, this scaffold's own metrics) pad the list left, right and bottom;
 *   the top is the status inset only. The bar height including the status inset goes to [ChromeMetrics.topBarPx].
 * - Content is a column of at most 640 dp, centred. The list uses Convx's rubber-band overscroll.
 *
 * [onCollapseChanged] reports the smoothed collapse fraction (tests; it adds no recomposition).
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    state: LazyListState,
    actions: ImmutableList<ToolbarAction>,
    modifier: Modifier = Modifier,
    navigation: NavigationButton? = null,
    onOverflow: ((anchor: Rect, hidden: ImmutableList<ToolbarAction>) -> Unit)? = null,
    onCollapseChanged: ((Float) -> Unit)? = null,
    // Replaces the icon [actions] with custom content (text capsules such as Cancel / Select All); it is measured the
    // same way, so the large title keeps clear of it.
    actionsContent: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = RovstiTheme.colors
    val density = LocalDensity.current
    val root = LocalChromeMetrics.current
    val metrics = root ?: remember { ChromeMetrics() }
    val insets = if (root != null) LocalContentInsets.current else metrics.contentInsets()
    val statusTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val settings = LocalGlassSettings.current

    var titleHeightPx by remember { mutableFloatStateOf(0f) }
    var toolbarWidthPx by remember { mutableIntStateOf(0) }
    val collapse = state.rememberTitleCollapse { titleHeightPx }
    if (onCollapseChanged != null) {
        LaunchedEffect(collapse, onCollapseChanged) { snapshotFlow { collapse.value }.collect(onCollapseChanged) }
    }
    DisposableEffect(metrics) { onDispose { metrics.topBarPx = 0 } }

    val contentPadding =
        PaddingValues.Absolute(
            left = insets.calculateLeftPadding(LayoutDirection.Ltr),
            right = insets.calculateRightPadding(LayoutDirection.Ltr),
            top = statusTop,
            bottom = insets.calculateBottomPadding(),
        )
    val toolbarWidth = with(density) { toolbarWidthPx.toDp() }
    val capped = Modifier.widthIn(max = Sizes.contentMaxWidth)
    val leading = if (navigation != null) Spacing.gutter + Sizes.button else Spacing.gutter
    val startReserve = leading + Spacing.s
    val endReserve = Spacing.gutter + toolbarWidth + Spacing.s

    CompositionLocalProvider(LocalOverscrollFactory provides rememberIosOverscrollFactory()) {
        ScreenLazyPage(
            title = title,
            modifier = modifier.fillMaxSize().testTag("large-title-scaffold"),
            onBack = navigation?.onClick,
            backLabel = navigation?.label ?: "Back",
            pageColor = colors.page,
            contentPadding = contentPadding,
            listState = state,
            titleProgress = collapse,
            titleAlphaProvider = { inlineTitleAlpha(it) },
            titleSideReserve = startReserve,
            titleEndReserve = endReserve,
            barModifier =
                capped
                    .onSizeChanged { metrics.topBarPx = it.height }
                    .padding(horizontalInsets(insets)),
            listModifier = capped.fillMaxSize().testTag("scaffold-list"),
            actions = {
                if (actionsContent != null) {
                    Box(Modifier.onSizeChanged { toolbarWidthPx = it.width }) { actionsContent() }
                } else {
                    Toolbar(
                        actions = actions,
                        onOverflow = onOverflow,
                        modifier = Modifier.onSizeChanged { toolbarWidthPx = it.width },
                    )
                }
            },
            largeTitle = {
                LargeTitle(
                    title = title,
                    collapse = collapse,
                    hasNavigation = navigation != null,
                    endReserve = if (navigation == null) toolbarWidth + Spacing.m else 0.dp,
                    onHeight = { titleHeightPx = it },
                )
            },
            content = content,
        )
    }
}

private fun horizontalInsets(insets: PaddingValues): PaddingValues =
    PaddingValues.Absolute(
        left = insets.calculateLeftPadding(LayoutDirection.Ltr),
        right = insets.calculateRightPadding(LayoutDirection.Ltr),
    )

@Composable
private fun LargeTitle(
    title: String,
    collapse: androidx.compose.runtime.State<Float>,
    hasNavigation: Boolean,
    endReserve: Dp,
    onHeight: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val line =
        with(density) {
            RovstiTheme.type.largeTitle.lineHeight
                .toDp()
        }
    // Without a back button the first line is centred in the 52 dp band, level with the capsules; with one, the title
    // starts below the button row. A tall line (large font scale) simply starts at the top.
    val top = if (hasNavigation) ScaffoldSpec.BarHeight else ((ScaffoldSpec.BarHeight - line) / 2).coerceAtLeast(0.dp)
    val lift = with(density) { ScaffoldSpec.TitleLift.toPx() }
    ScreenLargeTitle(
        title = title,
        top = top,
        endReserve = endReserve,
        modifier =
            Modifier
                .onSizeChanged { onHeight(it.height.toFloat()) }
                .graphicsLayer {
                    alpha = largeTitleAlpha(collapse.value)
                    translationY = -lift * collapse.value
                },
        textModifier = Modifier.semantics { heading() }.testTag("large-title"),
    )
}
