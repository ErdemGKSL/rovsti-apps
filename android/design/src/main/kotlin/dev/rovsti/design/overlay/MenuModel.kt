package dev.rovsti.design.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.rovsti.design.ios.component.IosButtonRole
import dev.rovsti.design.ios.component.IosGlyph
import dev.rovsti.design.ios.component.IosMenuItem
import dev.rovsti.design.ios.component.IosMenuSection
import kotlinx.collections.immutable.ImmutableList

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

/**
 * The Convx menu items for [this] list (ORIGINAL facade): a [MenuItem.Separator] starts a new section, a checked row
 * is a selected row of a picker, a destructive row has the destructive role, an icon is drawn 20 dp in the row's colour.
 */
fun ImmutableList<MenuItem>.toSections(): List<IosMenuSection> {
    val sections = mutableListOf<IosMenuSection>()
    var current = mutableListOf<IosMenuItem>()
    for (item in this) {
        when (item) {
            is MenuItem.Separator -> {
                if (current.isNotEmpty()) sections += IosMenuSection(current)
                current = mutableListOf()
            }

            is MenuItem.Action -> {
                val icon = item.icon
                current +=
                    IosMenuItem(
                        title = item.label,
                        icon = icon?.let { vector -> { tint -> IosGlyph(vector, tint, MenuMetrics.IconSize) } },
                        role = if (item.destructive) IosButtonRole.Destructive else null,
                        selected = item.checked == true,
                        enabled = item.enabled,
                        onClick = item.onClick,
                        checkable = item.checked != null,
                    )
            }
        }
    }
    if (current.isNotEmpty()) sections += IosMenuSection(current)
    return sections
}

/** Dimensions of the popover card (SKILL overlays 4.2; glisse `styles.css:912-1067` made touch-sized). Menus use `IosMenuMaterial`. */
object MenuMetrics {
    val CardWidth = 220.dp
    val CardRadius = 20.dp
    val CardPadding = 6.dp

    /** Distance between the card and the safe area's edge. */
    val EdgeMargin = 12.dp
    val RowMinHeight = 48.dp
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
