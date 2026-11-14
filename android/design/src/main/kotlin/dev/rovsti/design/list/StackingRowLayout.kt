package dev.rovsti.design.list

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

private enum class StackSlot { Leading, Text, Trailing }

/**
 * A row of an optional [leading] element, the [text] column and a [trailing] action that drops UNDER the text when it
 * would squeeze it (F35, [RowStacking]); an accessory (a chevron, one icon) never drops: at accessibility font sizes, or when the text column would get less than half of
 * the row. Stacked, the action starts at the text column's left edge; side by side, it sits at the row's end. Children
 * are centred vertically in the row's minimum height. [leadingGap] separates [leading] from the text, [trailingGap] the
 * text from a side-by-side action, [stackGap] the text from a stacked one.
 */
@Composable
fun StackingTrailingRow(
    text: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    leadingGap: Dp,
    trailingGap: Dp,
    stackGap: Dp,
    trailingShrinks: Boolean = false,
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val leadGapPx = with(density) { leadingGap.roundToPx() }
    val trailGapPx = with(density) { trailingGap.roundToPx() }
    val stackGapPx = with(density) { stackGap.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.layoutId(StackSlot.Leading)) { leading?.invoke() }
            Box(Modifier.layoutId(StackSlot.Text)) { text() }
            Box(Modifier.layoutId(StackSlot.Trailing)) { trailing() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        fun find(slot: StackSlot) = measurables.first { it.layoutId == slot }
        val lead = if (leading != null) find(StackSlot.Leading).measure(loose) else null
        val leadSpace = if (lead != null) lead.width + leadGapPx else 0
        val trailM = find(StackSlot.Trailing)
        val trailFull = trailM.maxIntrinsicWidth(loose.maxHeight)
        val half = RowStacking.besideMax(width - leadSpace, trailGapPx)
        // A value that cuts itself short (one ellipsized line) stays beside the text at its half of the row.
        val shrinks =
            trailingShrinks &&
                fontScale < RowStacking.STACK_FONT_SCALE &&
                trailFull > half &&
                RowStacking.shrinksInPlace(trailM.minIntrinsicHeight(half), trailM.minIntrinsicHeight(trailFull))
        val trail = trailM.measure(if (shrinks) loose.copy(maxWidth = half) else loose)
        val stack =
            !shrinks &&
                !RowStacking.isAccessory(trail.width, RowStacking.ACCESSORY_MAX.roundToPx()) &&
                RowStacking.shouldStack(
                    fontScale = fontScale,
                    rowWidth = width - leadSpace,
                    trailingWidth = trail.width + trailGapPx,
                )
        val textMax = (width - leadSpace - if (stack) 0 else trail.width + trailGapPx).coerceAtLeast(0)
        val textP = find(StackSlot.Text).measure(loose.copy(maxWidth = textMax))
        val block = if (stack) textP.height + stackGapPx + trail.height else maxOf(textP.height, trail.height)
        val height = maxOf(block, lead?.height ?: 0, constraints.minHeight)
        layout(width, height) {
            fun mid(h: Int) = Alignment.CenterVertically.align(h, height)
            lead?.placeRelative(0, mid(lead.height))
            if (stack) {
                val top = mid(block)
                textP.placeRelative(leadSpace, top)
                trail.placeRelative(leadSpace, top + textP.height + stackGapPx)
            } else {
                textP.placeRelative(leadSpace, mid(textP.height))
                trail.placeRelative(width - trail.width, mid(trail.height))
            }
        }
    }
}
