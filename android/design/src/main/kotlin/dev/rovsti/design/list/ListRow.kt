package dev.rovsti.design.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.Backdrop
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosToggle
import dev.rovsti.design.press.rowPress
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Width of the leading slot. Every row that has a leading element gives it exactly this much, so text lines up. */
val LeadingSlotWidth: Dp = Sizes.avatarLarge

/** Where the text column starts, measured from the row's edge, when the row has a leading slot. */
val TextStartWithLeading: Dp = Spacing.gutter + LeadingSlotWidth + Spacing.m

/** Where the text column starts when there is no leading slot. */
val TextStartPlain: Dp = Spacing.gutter

/** An action a screen reader offers for a row (swipe actions, delete, mark read) as a custom accessibility action. */
@Immutable
class CustomAction(
    val label: String,
    val invoke: () -> Unit,
)

/**
 * The one list row (ORIGINAL): [leading] in a fixed 44 dp slot and a 12 dp gap, then the [title] and at most one
 * [secondary] line (each a single line with an ellipsis), then [meta] (a time, a count) and an optional [trailing]
 * element (chevron, one icon). The height is a minimum (56 dp, 64 dp with a secondary line) and grows with font scale.
 *
 * The row is ONE accessibility node: avatar, texts and meta merge, with [actions] offered as custom actions. Pressing
 * paints a flat fill behind the content; nothing scales and nothing moves. The row draws its own 16 dp gutters, so
 * inside an [InsetGroupedSection] the text starts at [TextStartWithLeading] or [TextStartPlain] from the card edge.
 * [titleMaxLines] and [secondaryMaxLines] let a settings row wrap (settings rows); lists keep the default of one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    meta: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    actions: ImmutableList<CustomAction> = persistentListOf(),
    titleColor: Color = Color.Unspecified,
    extraSemantics: (SemanticsPropertyReceiver.() -> Unit)? = null,
    endInset: Dp = 0.dp,
    secondaryMaxLines: Int = 1,
    titleMaxLines: Int = 1,
    trailingShrinks: Boolean = false,
) {
    val colors = RovstiTheme.colors
    val type = RovstiTheme.type
    val interactive = onClick != null || onLongClick != null
    val source = remember { MutableInteractionSource() }
    val accessibilityActions =
        remember(actions) {
            actions.map { action ->
                CustomAccessibilityAction(action.label) {
                    action.invoke()
                    true
                }
            }
        }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (secondary != null) Sizes.rowMinTwoLine else Sizes.rowMin)
            .then(
                if (interactive) {
                    Modifier
                        .rowPress(source)
                        .combinedClickable(
                            interactionSource = source,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick ?: {},
                            onLongClick = onLongClick,
                        )
                } else {
                    Modifier
                },
            ).semantics(mergeDescendants = true) {
                if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
                extraSemantics?.invoke(this)
            }.padding(
                PaddingValues(
                    start = Spacing.gutter,
                    end = Spacing.gutter + endInset,
                    top = Spacing.s,
                    bottom = Spacing.s,
                ),
            ),
        propagateMinConstraints = true,
    ) {
        // Four slots in a custom layout: the trailing action sits beside the text, or under it when the text would be
        // squeezed (large font, long title, wide action), as iOS stacks accessories at accessibility sizes.
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val gap = with(density) { Spacing.m.roundToPx() }
        val trailGap = with(density) { Spacing.s.roundToPx() }
        val textGap = with(density) { Spacing.textGap.roundToPx() }
        val leadWidth = with(density) { LeadingSlotWidth.roundToPx() }
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Box(Modifier.layoutId(RowSlot.Leading), contentAlignment = Alignment.Center) { leading?.invoke() }
                Column(Modifier.layoutId(RowSlot.Text)) {
                    RovstiText(
                        title,
                        style = type.body,
                        color = titleColor.takeIf { it != Color.Unspecified } ?: colors.labelPrimary,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (secondary != null) {
                        RovstiText(
                            secondary,
                            modifier = Modifier.padding(top = Spacing.textGap),
                            style = type.subheadline,
                            color = colors.labelSecondary,
                            maxLines = secondaryMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(Modifier.layoutId(RowSlot.Meta)) {
                    if (meta != null) {
                        RovstiText(meta, style = type.footnote, color = colors.labelSecondary, maxLines = 1)
                    }
                }
                Box(Modifier.layoutId(RowSlot.Trailing)) { trailing?.invoke() }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth

            fun find(slot: RowSlot) = measurables.first { it.layoutId == slot }
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val lead = if (leading != null) find(RowSlot.Leading).measure(loose.copy(maxWidth = leadWidth)) else null
            val leadSpace = if (lead != null) leadWidth + gap else 0
            val metaP = if (meta != null) find(RowSlot.Meta).measure(loose) else null
            val metaSpace = if (metaP != null) metaP.width + gap else 0
            val trailM = if (trailing != null) find(RowSlot.Trailing) else null
            val half = RowStacking.besideMax(width - leadSpace - metaSpace, trailGap)
            // A value that cuts itself short (one ellipsized line) stays beside the text at its half of the row.
            val shrinks =
                trailM != null &&
                    trailingShrinks &&
                    fontScale < RowStacking.STACK_FONT_SCALE &&
                    trailM.maxIntrinsicWidth(loose.maxHeight).let { full ->
                        full > half &&
                            RowStacking.shrinksInPlace(trailM.minIntrinsicHeight(half), trailM.minIntrinsicHeight(full))
                    }
            val trailP = trailM?.measure(if (shrinks) loose.copy(maxWidth = half) else loose)
            val trailSpace = if (trailP != null) trailP.width + trailGap else 0
            val stack =
                trailP != null &&
                    !shrinks &&
                    !RowStacking.isAccessory(trailP.width, RowStacking.ACCESSORY_MAX.roundToPx()) &&
                    RowStacking.shouldStack(
                        fontScale = fontScale,
                        rowWidth = width - leadSpace,
                        trailingWidth = trailSpace + metaSpace,
                    )
            val textMax = (width - leadSpace - metaSpace - if (stack) 0 else trailSpace).coerceAtLeast(0)
            val textP = find(RowSlot.Text).measure(loose.copy(maxWidth = textMax))
            val stackedTrailH = if (stack && trailP != null) textGap + trailP.height else 0
            val besideTrailH = if (!stack && trailP != null) trailP.height else 0
            val contentH = maxOf(lead?.height ?: 0, textP.height + stackedTrailH, metaP?.height ?: 0, besideTrailH)
            val height = maxOf(contentH, constraints.minHeight)
            layout(width, height) {
                fun mid(h: Int) = Alignment.CenterVertically.align(h, height)
                lead?.placeRelative(0, mid(lead.height))
                if (stack) {
                    val top = mid(textP.height + stackedTrailH)
                    textP.placeRelative(leadSpace, top)
                    trailP?.placeRelative(leadSpace, top + textP.height + textGap)
                    metaP?.placeRelative(width - metaP.width, top)
                } else {
                    textP.placeRelative(leadSpace, mid(textP.height))
                    var x = width
                    if (trailP != null) {
                        x -= trailP.width
                        trailP.placeRelative(x, mid(trailP.height))
                        x -= trailGap
                    }
                    if (metaP != null) {
                        x -= metaP.width
                        metaP.placeRelative(x, mid(metaP.height))
                    }
                }
            }
        }
    }
}

private enum class RowSlot { Leading, Text, Meta, Trailing }

/** When a row's trailing action drops under the text (F35). Pure, so a unit test pins the rule. */
object RowStacking {
    /** At this font scale (accessibility sizes) the trailing action always goes under the text. */
    const val STACK_FONT_SCALE: Float = 1.5f

    /**
     * A trailing element at most this wide (a chevron, a check, one icon) is an accessory: it stays at the row's end at
     * every font size, as on iOS. Only a wide action (a text button, a value) drops under the text.
     */
    val ACCESSORY_MAX: Dp = 48.dp

    /** True when a trailing element [widthPx] wide is an accessory that never stacks ([ACCESSORY_MAX] in pixels). */
    fun isAccessory(
        widthPx: Int,
        accessoryMaxPx: Int,
    ): Boolean = widthPx <= accessoryMaxPx

    /** The widest a trailing element may be and still leave the text at least half of [rowWidth]. */
    fun besideMax(
        rowWidth: Int,
        gapPx: Int,
    ): Int = (rowWidth / 2 - gapPx).coerceAtLeast(0)

    /**
     * True when a trailing element squeezed to its half of the row is no taller than at its natural width: it cuts
     * itself short (an ellipsized value) instead of wrapping, so it stays beside the text instead of dropping under.
     */
    fun shrinksInPlace(
        heightAtHalf: Int,
        heightAtFull: Int,
    ): Boolean = heightAtHalf <= heightAtFull

    /** Stack when the font is large, or the text column beside the action would get under half of [rowWidth]. */
    fun shouldStack(
        fontScale: Float,
        rowWidth: Int,
        trailingWidth: Int,
    ): Boolean = fontScale >= STACK_FONT_SCALE || rowWidth - trailingWidth < rowWidth / 2
}

/** The disclosure chevron at the end of a row that opens something. Decorative. */
@Composable
fun ListRowChevron(modifier: Modifier = Modifier) {
    dev.rovsti.design.ios.component
        .IosChevron(modifier, RovstiTheme.colors.labelTertiary)
}

/**
 * A row with a switch at its end (Settings-style). The row is ONE accessibility node with `Role.Switch` and the on/off
 * state, so a screen reader toggles it from the row; the [IosToggle] inside is hidden from accessibility. The row itself
 * does not toggle on a tap (like iOS Settings): the switch is the control, and it is a full 48 dp touch target, so the row
 * keeps the usual height and nothing in it moves when the switch is pressed. [onCheckedChange] null shows a read-out.
 */
@Composable
fun ListRowSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val interactive = enabled && onCheckedChange != null
    ListRow(
        title = title,
        secondary = secondary,
        leading = leading,
        modifier = modifier,
        trailing = {
            IosToggle(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                backdrop = backdrop,
                exposeSemantics = false,
            )
        },
        extraSemantics = {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            if (!interactive) disabled()
            onClick("toggle") {
                if (!interactive) return@onClick false
                onCheckedChange?.invoke(!checked)
                true
            }
        },
    )
}
