// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// Only the glass buttons in the bar come from glisse; the scaffold, collapse and scrim are Rovsti's own.
package dev.rovsti.design.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassSource
import dev.rovsti.design.glass.GlassTier
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.design.glass.glassSource
import dev.rovsti.design.glass.glassTier
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.max

/** Numbers of the large-title scaffold (SKILL navigation-layout.md 10). */
object ScaffoldSpec {
    /** The inline bar under the status bar; the large title's first line is centred in this same band. */
    val BarHeight: Dp = 52.dp

    /** How far the top scrim runs on below the bar. */
    val ScrimRun: Dp = 64.dp

    /** The large title rises this far while it fades. */
    val TitleLift: Dp = 12.dp

    /** Default peak of the scrim stop curve (the live scrim scales it by the title collapse, see TopScrim). */
    const val ScrimPeak = 0.42f

    /** Once the title has collapsed, rows pass under the inline title, so the scrim over the bar firms up to this. */
    const val ScrimPeakCollapsed = 0.94f

    val Divider: Dp = 0.5.dp
}

/** A back button on the leading edge of a pushed page. */
class NavigationButton(
    val label: String = "Back",
    val onClick: () -> Unit,
)

/**
 * Stops of the top scrim as (position, share of the page colour) pairs. It holds [ScaffoldSpec.ScrimPeak] over the bar
 * (the first [barFraction] of the run) and then falls to 0 along the same curve as the bottom scrim.
 */
internal fun topScrimStops(
    barFraction: Float,
    peak: Float = ScaffoldSpec.ScrimPeak,
    bandPeak: Float = peak,
): List<Pair<Float, Float>> {
    val bar = barFraction.coerceIn(0f, 1f)
    // The bottom scrim's stops (ScrimStops), walked from the far end: 1 at the bar, 0 at the end of the run.
    val run = ScrimStops.reversed().map { (at, share) -> (1f - at) to share }
    return listOf(0f to bandPeak, bar to bandPeak) +
        run.drop(1).map { (at, share) -> (bar + (1f - bar) * at) to (peak * share) }
}

/**
 * A scrolling page with an iOS large title (ORIGINAL).
 *
 * - The large title is item 0 of the list, key `"title"`: 34 sp bold, at most two lines. As it scrolls away it lifts
 *   [ScaffoldSpec.TitleLift] and fades while the inline title in the 52 dp bar fades in (both by the spring-smoothed
 *   [rememberTitleCollapse], read only in `graphicsLayer`).
 * - The title's first line is centred on the toolbar capsules, which are never taller than 44 dp. With a [navigation]
 *   button the bar row belongs to the buttons and the title starts below it.
 * - Above the list sits a top scrim (bar + 64 dp, page colour up to 0.42, firming to 0.94 over the bar once collapsed). With reduce transparency the bar is solid
 *   instead and a 0.5 dp divider appears once the title has collapsed.
 * - [LocalContentInsets] (or, without a screen root, this scaffold's own metrics) pad the list left, right and bottom;
 *   the top is the status inset only, since the title row lives in the bar band. The bar height including the status
 *   inset goes to [ChromeMetrics.topBarPx].
 * - Content is a column of at most 640 dp, centred. Bars and list keep clear of the display cutout.
 *
 * [onCollapseChanged] reports the smoothed collapse fraction (tests and analytics of nothing; it adds no recomposition).
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    state: LazyListState,
    actions: ImmutableList<ToolbarAction>,
    glass: GlassSource,
    modifier: Modifier = Modifier,
    navigation: NavigationButton? = null,
    onOverflow: ((anchor: Rect, hidden: ImmutableList<ToolbarAction>) -> Unit)? = null,
    onCollapseChanged: ((Float) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = RovstiTheme.colors
    val density = LocalDensity.current
    val root = LocalChromeMetrics.current
    val metrics = root ?: remember { ChromeMetrics() }
    val insets = if (root != null) LocalContentInsets.current else metrics.contentInsets()
    val statusTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val conditions = LocalGlassConditions.current
    val reduceTransparency = conditions.reduceTransparency
    // The top chrome samples `glass`, so the tier it would get decides whether rows may show through the bar band.
    val flat = glassTier(conditions.copy(sourceAvailable = true)) == GlassTier.Flat

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
    val barBand = statusTop + ScaffoldSpec.BarHeight
    val toolbarWidth = with(density) { toolbarWidthPx.toDp() }
    val capped = Modifier.widthIn(max = Sizes.contentMaxWidth)

    Box(modifier.fillMaxSize().background(colors.page).testTag("large-title-scaffold")) {
        Box(capped.fillMaxSize().align(Alignment.TopCenter).glassSource(glass, colors.page)) {
            LazyColumn(
                state = state,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize().testTag("scaffold-list"),
            ) {
                item(key = "title") {
                    LargeTitle(
                        title = title,
                        collapse = collapse,
                        hasNavigation = navigation != null,
                        endReserve = if (navigation == null) toolbarWidth + Spacing.m else 0.dp,
                        onHeight = { titleHeightPx = it },
                    )
                }
                content()
            }
        }

        if (reduceTransparency) {
            SolidBar(collapse, barBand)
        } else {
            TopScrim(barBand, collapse, flat, Modifier.align(Alignment.TopCenter))
        }

        Box(
            capped
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onSizeChanged { metrics.topBarPx = it.height }
                .padding(
                    PaddingValues.Absolute(
                        left = insets.calculateLeftPadding(LayoutDirection.Ltr),
                        right = insets.calculateRightPadding(LayoutDirection.Ltr),
                        top = statusTop,
                    ),
                ).height(ScaffoldSpec.BarHeight)
                .testTag("title-bar"),
        ) {
            val leading = if (navigation != null) Spacing.gutter + Sizes.button else Spacing.gutter
            val side = maxOf(leading, Spacing.gutter + toolbarWidth) + Spacing.s
            RovstiText(
                title,
                style = RovstiTheme.type.headline,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = side)
                        .graphicsLayer { alpha = inlineTitleAlpha(collapse.value) }
                        .clearAndSetSemantics {}
                        .testTag("inline-title"),
            )
            if (navigation != null) {
                BackButton(
                    onClick = navigation.onClick,
                    glass = glass,
                    label = navigation.label,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.gutter - 2.dp),
                )
            }
            Toolbar(
                actions = actions,
                glass = glass,
                onOverflow = onOverflow,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Spacing.gutter)
                        .onSizeChanged { toolbarWidthPx = it.width },
            )
        }
    }
}

@Composable
private fun LargeTitle(
    title: String,
    collapse: State<Float>,
    hasNavigation: Boolean,
    endReserve: Dp,
    onHeight: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val style = RovstiTheme.type.largeTitle
    val line = with(density) { style.lineHeight.toDp() }
    // Without a back button the first line is centred in the 52 dp band, level with the capsules; with one, the title
    // starts below the button row. A tall line (large font scale) simply starts at the top.
    val top = if (hasNavigation) ScaffoldSpec.BarHeight else ((ScaffoldSpec.BarHeight - line) / 2).coerceAtLeast(0.dp)
    val lift = with(density) { ScaffoldSpec.TitleLift.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height.toFloat()) }
            .graphicsLayer {
                alpha = largeTitleAlpha(collapse.value)
                translationY = -lift * collapse.value
            }.padding(start = Spacing.gutter, end = Spacing.gutter + endReserve, top = top, bottom = Spacing.s),
    ) {
        RovstiText(
            title,
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() }.testTag("large-title"),
        )
    }
}

/** The progressive page-colour fade over the top of the content: bar band plus [ScaffoldSpec.ScrimRun]. */
@Composable
private fun TopScrim(
    barBand: Dp,
    collapse: State<Float>,
    flat: Boolean,
    modifier: Modifier = Modifier,
) {
    val page = RovstiTheme.colors.page
    val total = barBand + ScaffoldSpec.ScrimRun
    val fraction = barBand / total
    Box(
        modifier
            .fillMaxWidth()
            .height(total)
            .drawBehind {
                // The expanded title lives in the list under this scrim, so no page colour may lie over it (it would grey the
                // title out); the scrim only firms up as the title leaves and rows start to pass under the bar.
                val c = collapse.value
                val peak = ScaffoldSpec.ScrimPeakCollapsed * c
                // Flat has no blur: once collapsed the bar band is fully opaque so no row text can be read through it.
                val bandPeak = if (flat) max(peak, c * c * c) else peak
                val brush =
                    Brush.verticalGradient(
                        colorStops =
                            topScrimStops(fraction, peak, bandPeak)
                                .map { (at, share) ->
                                    at to page.copy(alpha = share)
                                }.toTypedArray(),
                        startY = 0f,
                        endY = size.height,
                    )
                drawRect(brush)
            }.testTag("top-scrim"),
    )
}

/** Reduce transparency: an opaque bar that fades in as the title collapses, with a 0.5 dp divider at its edge. */
@Composable
private fun SolidBar(
    collapse: State<Float>,
    height: Dp,
) {
    val colors = RovstiTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = collapse.value }
            .background(colors.page)
            .drawBehind {
                val thickness = ScaffoldSpec.Divider.toPx()
                drawRect(
                    colors.opaqueSeparator,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
                )
            }.testTag("solid-bar"),
    )
}
