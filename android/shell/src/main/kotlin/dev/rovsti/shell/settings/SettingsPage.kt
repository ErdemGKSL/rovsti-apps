/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/screens/settings/SettingsPage.kt
 * Changes for Rovsti: package dev.rovsti.shell.settings; no NavController (onBack / onBackLongClick are plain callbacks, Rovsti's
 * back stack is a Navigation 3 list); the shell chrome comes from :design (ScreenPage, ScreenLazyPage, ScreenScaffold); Material's
 * LocalContentColor became LocalIosContentColor; the lazy flavour hands the gutter to contentPadding, which is what
 * ScreenLazyPage offers; numeric dp literals are named in SettingsMetrics (ConventionTest); the large title is inset so it lines up with the group headers.
 */

package dev.rovsti.shell.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.GlassScreenChrome
import dev.rovsti.design.chrome.ScreenLazyPage
import dev.rovsti.design.chrome.ScreenPage
import dev.rovsti.design.chrome.ScreenScaffold
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.LocalIosContentColor

/**
 * The settings dialect of ScreenPage and friends: iOS grouped background, iOS large-title type, and the back button
 * wired to the caller's back stack (long-press jumps straight to the root, which is how these pages have always behaved).
 *
 * Everything structural (the blur source, the progressive blur, the title handoff) lives in the shared screen chrome of
 * :design. These three exist only so a settings page does not repeat the same four arguments forty times.
 */
private fun settingsLargeTitle(
    title: String,
    gutter: Dp,
): @Composable () -> Unit =
    {
        IosText(
            text = title,
            style = IosTheme.typography.largeTitle,
            color = IosTheme.colors.label,
            // The title sits 20 dp from the screen edge, the same line as the group headers' left inset.
            modifier =
                Modifier.padding(
                    start = (SettingsTitleInset - gutter).coerceAtLeast(SettingsMetrics.S0),
                    top = SettingsMetrics.S24,
                    bottom = SettingsMetrics.S14,
                ),
        )
    }

/** Distance of the large title from the screen edge. */
private val SettingsTitleInset = SettingsMetrics.S20

/** Readable column width on tablets; phones are narrower so it never applies there. */
private val SettingsContentMaxWidth = SettingsMetrics.S720

/** Side gutter of the cards on a phone. */
val SettingsPhoneGutter: Dp = SettingsMetrics.S16

/** Side gutter that centres a [maxWidth] column on wide windows and falls back to [minimum] on phones. */
@Composable
private fun settingsGutter(
    minimum: Dp,
    maxWidth: Dp,
): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return maxOf(minimum, (screenWidth - maxWidth) / 2)
}

/** A settings page whose body is a plain scrolling Column: most of them. */
@Composable
fun SettingsPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** Jumps straight to the root of the stack. */
    onBackLongClick: (() -> Unit)? = null,
    horizontalPadding: Dp = SettingsPhoneGutter,
    maxContentWidth: Dp = SettingsContentMaxWidth,
    actions: @Composable () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val gutter = settingsGutter(horizontalPadding, maxContentWidth)
    CompositionLocalProvider(LocalIosContentColor provides IosTheme.colors.label) {
        ScreenPage(
            title = title,
            modifier = modifier,
            onBack = onBack,
            onBackLongClick = onBackLongClick,
            // Cards are painted on the iOS grouped fill, so the page behind them must be too.
            pageColor = IosTheme.colors.systemGroupedBackground,
            horizontalPadding = gutter,
            actions = actions,
            scrollState = scrollState,
            largeTitle = settingsLargeTitle(title, gutter),
            content = {
                content()
                Spacer(Modifier.height(SettingsMetrics.S32))
            },
        )
    }
}

/** The LazyColumn flavour, for settings pages whose body is a real list. */
@Composable
fun SettingsLazyPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    horizontalPadding: Dp = SettingsPhoneGutter,
    maxContentWidth: Dp = SettingsContentMaxWidth,
    actions: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val gutter = settingsGutter(horizontalPadding, maxContentWidth)
    CompositionLocalProvider(LocalIosContentColor provides IosTheme.colors.label) {
        ScreenLazyPage(
            title = title,
            modifier = modifier,
            onBack = onBack,
            onBackLongClick = onBackLongClick,
            pageColor = IosTheme.colors.systemGroupedBackground,
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = SettingsMetrics.S112),
            actions = actions,
            listState = listState,
            largeTitle = settingsLargeTitle(title, gutter),
            content = content,
        )
    }
}

/**
 * For a settings page whose body is neither: an icon grid, a WebView, a two-pane layout. It keeps its own container and
 * owns its own [titleAlpha].
 */
@Composable
fun SettingsScaffold(
    title: String,
    titleAlpha: Float,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    pageColor: Color = IosTheme.colors.systemGroupedBackground,
    actions: @Composable () -> Unit = {},
    titleAlphaProvider: (() -> Float)? = null,
    maxContentWidth: Dp = SettingsContentMaxWidth,
    content: @Composable BoxScope.(GlassScreenChrome) -> Unit,
) {
    ScreenScaffold(
        title = title,
        titleAlpha = titleAlpha,
        titleAlphaProvider = titleAlphaProvider,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        pageColor = pageColor,
        actions = actions,
        content = { chrome ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxSize().widthIn(max = maxContentWidth)) { content(chrome) }
            }
        },
    )
}
