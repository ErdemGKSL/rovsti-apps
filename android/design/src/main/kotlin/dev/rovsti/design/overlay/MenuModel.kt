package dev.rovsti.design.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One entry of a menu card (ORIGINAL). Menus are `ImmutableList<MenuItem>` so Compose can skip them. */
sealed interface MenuItem {
    /**
     * A tappable row. [checked] is `null` for a plain row, `true`/`false` for a row that shows a checkmark when on.
     * [destructive] rows are red. A disabled row is dimmed and never commits.
     */
    @Immutable
    data class Action(
        val label: String,
        val icon: ImageVector? = null,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val checked: Boolean? = null,
        val onClick: () -> Unit,
    ) : MenuItem

    /** A hairline between two groups of rows. */
    data object Separator : MenuItem
}

/** Dimensions of the menu card (SKILL overlays 4.2; glisse `styles.css:912-1067` made touch-sized). */
object MenuMetrics {
    val CardWidth = 220.dp
    val CardRadius = 20.dp
    val CardPadding = 6.dp

    /** Distance between the card and the safe area's edge. */
    val EdgeMargin = 12.dp
    val RowMinHeight = 44.dp
    val RowPaddingVertical = 6.dp
    val RowPaddingHorizontal = 12.dp
    val RowGap = 10.dp

    /** Concentric with the card: card radius minus its padding. */
    val RowRadius = 14.dp
    val IconSize = 20.dp
    val SeparatorThickness = 0.5.dp
    val SeparatorMarginVertical = 7.dp
    val SeparatorMarginHorizontal = 11.dp
    const val DisabledAlpha = 0.38f
}
