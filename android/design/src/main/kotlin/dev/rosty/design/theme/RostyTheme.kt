package dev.rosty.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.ios.foundation.iosColorsFrom
import dev.rosty.design.text.ltrStyleFor
import dev.rosty.design.text.rostySelectionColors

val LocalRostyColors = staticCompositionLocalOf { LightColors }
val LocalRostyType = staticCompositionLocalOf { RostyType.Default }
val LocalGlassTokens = staticCompositionLocalOf { GlassTokens.Light }

/** Default style of [RostyText] when none is passed. */
val LocalRostyTextStyle = staticCompositionLocalOf { RostyType.Default.body }

object RostyTheme {
    val colors: RostyColors
        @Composable @ReadOnlyComposable
        get() = LocalRostyColors.current

    val type: RostyType
        @Composable @ReadOnlyComposable
        get() = LocalRostyType.current

    val glass: GlassTokens
        @Composable @ReadOnlyComposable
        get() = LocalGlassTokens.current
}

/** [darkTheme] is a parameter, not read from the system only, so tests can force either theme. */
@Composable
fun RostyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalRostyColors provides palette,
        LocalGlassTokens provides if (darkTheme) GlassTokens.Dark else GlassTokens.Light,
        LocalRostyType provides RostyType.Default,
        LocalRostyTextStyle provides RostyType.Default.body,
        LocalTextSelectionColors provides rostySelectionColors(palette),
    ) {
        // Every ported Convx component reads IosTheme; it is fed from the Rosty palette.
        IosTheme(colors = iosColorsFrom(palette), content = content)
    }
}

/** Text on [BasicText]: theme style and colour, no Material. [color] wins over the colour in [style]. */
@Composable
fun RostyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalRostyTextStyle.current,
    color: Color = RostyTheme.colors.labelPrimary,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style =
            ltrStyleFor(style, text, LocalLayoutDirection.current)
                .merge(TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified)),
        maxLines = maxLines,
        overflow = overflow,
    )
}
