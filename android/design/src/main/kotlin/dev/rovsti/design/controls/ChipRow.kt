package dev.rovsti.design.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.gated
import dev.rovsti.design.press.scalePress
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList

/** One filter chip. [count] is shown as a separate small label after the text, never glued into it. */
@Immutable
data class ChipModel(
    val id: String,
    val label: String,
    val count: Int? = null,
)

/** Prefix of the test tag of every chip: `chip-<id>`. */
const val ChipTagPrefix = "chip-"

/**
 * A single horizontally scrolling row of filter chips (ORIGINAL). One `LazyRow`: chips never wrap, they run off the right
 * edge and scroll. 8 dp between chips, 16 dp before the first and after the last. The selected chip is a filled accent
 * capsule with on-accent text; the fill and the text colour animate with the `appear` spring. Every chip is at least
 * 48 dp tall to touch (the capsule itself is drawn shorter).
 */
@Composable
fun ChipRow(
    chips: ImmutableList<ChipModel>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        state = state,
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(chips, key = { it.id }, contentType = { "chip" }) { chip ->
            Chip(chip, selected = chip.id == selected, onClick = { onSelect(chip.id) })
        }
    }
}

@Composable
private fun Chip(
    chip: ChipModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RovstiTheme.colors
    val type = RovstiTheme.type
    val reduce = LocalReduceMotion.current
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = RovstiMotion.appear(0.001f).gated(reduce),
        label = "chip",
    )
    Box(
        Modifier
            .testTag(ChipTagPrefix + chip.id)
            .heightIn(min = Sizes.touch)
            .scalePress()
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = ChipHeight)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(colors.fillTertiary, cornerRadius = radius)
                    drawRoundRect(colors.accent, cornerRadius = radius, alpha = progress.coerceIn(0f, 1f))
                }.padding(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = chip.label,
                style = type.subheadline,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = { lerp(colors.labelPrimary, colors.onAccent, progress.coerceIn(0f, 1f)) },
            )
            if (chip.count != null) {
                BasicText(
                    text = chip.count.toString(),
                    style = type.caption1,
                    maxLines = 1,
                    color = {
                        lerp(
                            colors.labelSecondary,
                            colors.onAccent.copy(alpha = CountOnAccent),
                            progress.coerceIn(0f, 1f),
                        )
                    },
                )
            }
        }
    }
}

private val ChipHeight = Sizes.chip
private const val CountOnAccent = 0.85f
