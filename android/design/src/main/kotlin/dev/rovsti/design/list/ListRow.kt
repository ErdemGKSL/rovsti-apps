package dev.rovsti.design.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    Row(
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.width(LeadingSlotWidth), contentAlignment = Alignment.Center) { leading() }
            Box(Modifier.width(Spacing.m))
        }
        Column(Modifier.weight(1f)) {
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
        if (meta != null) {
            RovstiText(
                meta,
                modifier = Modifier.padding(start = Spacing.m),
                style = type.footnote,
                color = colors.labelSecondary,
                maxLines = 1,
            )
        }
        if (trailing != null) {
            Box(Modifier.padding(start = Spacing.s)) { trailing() }
        }
    }
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
