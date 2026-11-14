package dev.rosty.catalog

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing

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
    RostyText(
        title,
        style = RostyTheme.type.largeTitle,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.s),
    )
    RostyText(
        detail,
        style = RostyTheme.type.subheadline,
        color = RostyTheme.colors.labelSecondary,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.l),
    )
}
