package dev.rovsti.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Minimal palette so the catalog and the screenshot harness have a page colour and legible text.
 * E1.2 replaces it with the full colour set, typography and contrast tests.
 */
@Immutable
class RovstiColors(
    val page: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val separator: Color,
    val accent: Color,
    val isDark: Boolean,
)

private val LightColors =
    RovstiColors(
        page = Color(0xFFF2F2F7),
        labelPrimary = Color(0xFF000000),
        labelSecondary = Color(0xFF4A4A4F),
        separator = Color(0x33000000),
        accent = Color(0xFF0062CC),
        isDark = false,
    )

private val DarkColors =
    RovstiColors(
        page = Color(0xFF000000),
        labelPrimary = Color(0xFFFFFFFF),
        labelSecondary = Color(0xFFAEAEB2),
        separator = Color(0x33FFFFFF),
        accent = Color(0xFF4DA3FF),
        isDark = true,
    )

private val LocalRovstiColors = staticCompositionLocalOf { LightColors }

object RovstiTheme {
    val colors: RovstiColors
        @Composable get() = LocalRovstiColors.current
}

/** [darkTheme] is a parameter, not read from the system, so tests can force either theme. */
@Composable
fun RovstiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRovstiColors provides if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
