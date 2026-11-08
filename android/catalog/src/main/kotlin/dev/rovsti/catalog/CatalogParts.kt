package dev.rovsti.catalog

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/** Page padding that only keeps clear of the system bars; components bring their own 16 dp gutters. */
@Composable
internal fun insetsOnly(): PaddingValues =
    WindowInsets.safeDrawing.add(WindowInsets(top = Spacing.gutter, bottom = Spacing.gutter)).asPaddingValues()

/** Large title and one line under it, on the 16 dp gutter. */
@Composable
internal fun PageHeader(
    title: String,
    detail: String,
) {
    RovstiText(
        title,
        style = RovstiTheme.type.largeTitle,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.s),
    )
    RovstiText(
        detail,
        style = RovstiTheme.type.subheadline,
        color = RovstiTheme.colors.labelSecondary,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.l),
    )
}
