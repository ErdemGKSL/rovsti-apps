/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/FrostedTopBar.kt
 * Changes for Rovsti: package dev.rovsti.design.chrome; Material3 (LinearProgressIndicator, HorizontalDivider) and Coil
 * (TopBarAccountButton, an account-photo button) are gone, the refresh line is drawn with a Canvas; the artwork
 * ambient colour is gone; the preference key became LocalGlassSettings (reduceDynamicBlur or reduceTransparency draw
 * the solid bar); the back button is the 44 dp GlassCircleButton in a 48 dp touch target at the 16 dp gutter;
 * titleSideReserve/titleEndReserve let the scaffold size the reserve to the toolbar instead of Convx's fixed 96 dp;
 * like Convx's symmetric padding(horizontal = 96.dp) the title sits on the bar's centre line when it fits between the larger reserve on both sides; when it only fits in the asymmetric span it is shifted into that span (Convx's fixed 96 dp never meets a wide toolbar, ours can) instead of being truncated to "Con..." while there is room.
 */
package dev.rovsti.design.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.R
import dev.rovsti.design.controls.GlassCircleButton
import dev.rovsti.design.glass.LocalGlassSettings
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.LocalIosContentColor
import dev.rovsti.design.theme.GlassTintTokens
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * The bar's own height, above whatever inset it is sitting under.
 *
 * The single source of truth for it: the bar lays itself out to this, and everything that has to clear the bar (page
 * content padding, [dev.rovsti.design.glass.TopFadeBlur]'s ramp, fixed headers directly beneath it) measures from here
 * rather than from a copy of the number.
 */
val TopBarContentHeight = 52.dp

/** The breathing room between the bar's bottom edge and the first thing under it, so content rests below the glass. */
val TopBarContentGap = 12.dp

/**
 * How far down the window the bar actually ends: the status bar inset it is pinned under, plus its own height. Read at
 * composition rather than baked in: the inset is a property of the device and the window, not of the app.
 */
@Composable
fun topBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarContentHeight

/** Where page content should start: clear of the bar, plus [TopBarContentGap]. */
@Composable
fun topBarContentPadding(): Dp = topBarHeight() + TopBarContentGap

/**
 * Shared frosted header for tab and detail screens. [dev.rovsti.design.glass.TopFadeBlur] draws the progressive
 * backdrop blur underneath. Callers provide [titleAlpha] (or, better, [titleAlphaProvider]) from their scroll progress so
 * the title stays synchronized with the page.
 */
@Composable
fun FrostedTopBar(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    // Long-press-to-home on the back button: many settings screens jump straight back to the root.
    onBackLongClick: (() -> Unit)? = null,
    backLabel: String = stringResource(R.string.design_back),
    title: String = "",
    titleAlpha: Float = 0f,
    // Draw-phase progress source. When supplied, the bar reads it only inside graphicsLayer/derivedStateOf so scroll
    // deltas never recompose callers. Prefer this over [titleAlpha] for any scroll-driven title.
    titleAlphaProvider: (() -> Float)? = null,
    titleColor: Color = Color.Unspecified,
    // Optional content shown at the leading edge when no back button is present.
    leading: @Composable () -> Unit = {},
    leadingInset: Dp = Spacing.gutter,
    largeTitle: Boolean = false,
    refreshing: Boolean = false,
    // A lambda, not a value: the drag changes every frame, and reading it in the caller would recompose the whole bar.
    pullFraction: () -> Float = { 0f },
    // Room each side of the title (back button or actions); Convx reserves a fixed 96 dp.
    titleSideReserve: Dp = 96.dp,
    // The title is centred when it fits inside the larger reserve on both sides, else shifted into the free span.
    titleEndReserve: Dp = titleSideReserve,
    actions: @Composable () -> Unit = {},
) {
    val settings = LocalGlassSettings.current
    // Rovsti: reduce transparency draws the same solid bar as Convx's reduceDynamicBlur.
    val solid = settings.reduceDynamicBlur || settings.reduceTransparency
    // Boolean derived from progress: derivedStateOf only invalidates when the threshold flips, not on every scroll pixel.
    val showDivider by remember(titleAlpha, titleAlphaProvider) {
        derivedStateOf { (titleAlphaProvider?.invoke() ?: titleAlpha) > 0.99f }
    }
    // Only the solid bar wants a hairline under it. A faded one has no edge for the line to mark, and drawing it there
    // would be inventing the very seam the fade exists to remove.
    val dividerColor by animateColorAsState(
        targetValue = RovstiTheme.colors.opaqueSeparator.copy(alpha = if (showDivider && solid) 0.6f else 0f),
        animationSpec = tween(220),
        label = "topBarDivider",
    )

    // The cutout / side inset the back button must clear.
    val direction = LocalLayoutDirection.current
    val sideInset = WindowInsets.displayCutout.asPaddingValues().calculateStartPadding(direction)
    val isDark = IosTheme.colors.isDark
    val resolvedTitleColor =
        if (titleColor.isSpecified) {
            titleColor
        } else {
            GlassTintTokens.glassContentColor(isDark = isDark, activeTint = Color.Unspecified, isTitle = true)
        }
    val resolvedIconColor =
        GlassTintTokens.glassContentColor(
            isDark = isDark,
            activeTint = Color.Unspecified,
            isTitle = false,
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (solid) Modifier.background(RovstiTheme.colors.page) else Modifier)
                // Rovsti: drawn at the bar's bottom edge instead of taking 0.5 dp of layout, so the bar height written to
                // ChromeMetrics stays the status inset plus TopBarContentHeight.
                .drawBehind {
                    val thickness = 0.5.dp.toPx()
                    drawRect(
                        dividerColor,
                        topLeft = Offset(0f, size.height - thickness),
                        size = Size(size.width, thickness),
                    )
                }.testTag("title-bar"),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(if (largeTitle) 44.dp else TopBarContentHeight),
        ) {
            IosText(
                text = title,
                style = RovstiTheme.type.headline.copy(fontWeight = FontWeight.SemiBold),
                color = resolvedTitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        // Reserve room for the back button/logo and the actions so a long title truncates instead of
                        // running under them.
                        .titleSpan(titleSideReserve, titleEndReserve)
                        // A few dp below true center: the fade's readability scrim is strongest right under the
                        // status bar and thins going down, so a title sitting slightly lower reads clearer against it.
                        .padding(top = 4.dp)
                        .graphicsLayer { alpha = titleAlphaProvider?.invoke() ?: titleAlpha }
                        .testTag("inline-title")
                        .clearAndSetSemantics {},
            )
            // On a pushed page the back affordance is always visible, since there is no large in-list header to fall
            // back on.
            if (onBack != null) {
                BackButton(
                    onClick = onBack,
                    onLongClick = onBackLongClick,
                    label = backLabel,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = sideInset + Spacing.gutter - 2.dp),
                )
            } else {
                CompositionLocalProvider(LocalIosContentColor provides resolvedIconColor) {
                    Box(
                        modifier =
                            Modifier
                                .align(if (largeTitle) Alignment.TopStart else Alignment.CenterStart)
                                .padding(start = leadingInset),
                    ) {
                        leading()
                    }
                }
            }
            CompositionLocalProvider(LocalIosContentColor provides resolvedIconColor) {
                Row(
                    modifier =
                        Modifier
                            .align(if (largeTitle) Alignment.TopEnd else Alignment.CenterEnd)
                            .padding(end = Spacing.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            }
        }
        // The loader line sits on the bar's bottom edge; the box only grows to the line's height while a refresh is
        // actually showing.
        Box(Modifier.fillMaxWidth()) {
            RefreshLine(
                refreshing = refreshing,
                pullFraction = pullFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * The refresh indicator: a line along the bottom of the bar, directly under the status bar. It tracks the drag on the
 * way down, then sweeps indefinitely once the refresh is away, so the two phases read as one continuous gesture.
 * (Rovsti: drawn with a Canvas; Convx uses Material's LinearProgressIndicator.)
 */
@Composable
private fun RefreshLine(
    refreshing: Boolean,
    pullFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    val fraction = pullFraction()
    val pulling = fraction > 0.01f
    AnimatedVisibility(
        visible = refreshing || pulling,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        val accent = RovstiTheme.colors.accent
        val sweep = rememberInfiniteTransition(label = "refreshSweep")
        val phase by sweep.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "refreshPhase",
        )
        Canvas(Modifier.fillMaxWidth().height(LINE_HEIGHT)) {
            if (refreshing) {
                val w = size.width * 0.35f
                val x = (size.width + w) * phase - w
                drawRect(
                    accent,
                    topLeft = Offset(x.coerceAtLeast(0f), 0f),
                    size =
                        Size(
                            minOf(
                                w,
                                size.width - x.coerceAtLeast(0f),
                            ).coerceAtLeast(0f),
                            size.height,
                        ),
                )
            } else {
                drawRect(accent, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
            }
        }
    }
}

private val LINE_HEIGHT = 2.5.dp

/**
 * Rovsti: lays the inline title out in the span between the leading and trailing reserves. Centred on the bar when the
 * text fits between the larger reserve on both sides (iOS and Convx look), otherwise slid toward the free side so a
 * short title is not truncated just because the toolbar on the other side is wide.
 */
private fun Modifier.titleSpan(
    leading: Dp,
    trailing: Dp,
): Modifier =
    layout { measurable, constraints ->
        val ltr = layoutDirection == LayoutDirection.Ltr
        val left = (if (ltr) leading else trailing).roundToPx()
        val right = (if (ltr) trailing else leading).roundToPx()
        val total = constraints.maxWidth
        val span = (total - left - right).coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = span))
        val sym = total - 2 * maxOf(left, right)
        val x =
            if (placeable.width <= sym) {
                (total - placeable.width) / 2
            } else {
                left + (span - placeable.width) / 2
            }
        layout(total, placeable.height) { placeable.place(x, 0) }
    }
