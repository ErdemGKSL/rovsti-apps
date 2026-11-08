package dev.rovsti.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.text.rovstiSelectionColors

val LocalRovstiColors = staticCompositionLocalOf { LightColors }
val LocalRovstiType = staticCompositionLocalOf { RovstiType.Default }
val LocalGlassTokens = staticCompositionLocalOf { GlassTokens.Light }

/** Default style of [RovstiText] when none is passed. */
val LocalRovstiTextStyle = staticCompositionLocalOf { RovstiType.Default.body }

object RovstiTheme {
    val colors: RovstiColors
        @Composable @ReadOnlyComposable
        get() = LocalRovstiColors.current

    val type: RovstiType
        @Composable @ReadOnlyComposable
        get() = LocalRovstiType.current

    val glass: GlassTokens
        @Composable @ReadOnlyComposable
        get() = LocalGlassTokens.current
}

/** [darkTheme] is a parameter, not read from the system only, so tests can force either theme. */
@Composable
fun RovstiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRovstiColors provides if (darkTheme) DarkColors else LightColors,
        LocalGlassTokens provides if (darkTheme) GlassTokens.Dark else GlassTokens.Light,
        LocalRovstiType provides RovstiType.Default,
        LocalRovstiTextStyle provides RovstiType.Default.body,
        LocalTextSelectionColors provides rovstiSelectionColors(if (darkTheme) DarkColors else LightColors),
        content = content,
    )
}

/** Text on [BasicText]: theme style and colour, no Material. [color] wins over the colour in [style]. */
@Composable
fun RovstiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalRovstiTextStyle.current,
    color: Color = RovstiTheme.colors.labelPrimary,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified)),
        maxLines = maxLines,
        overflow = overflow,
    )
}
