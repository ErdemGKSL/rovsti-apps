// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// The press fill follows glisse's glass button; the capsule is Convx's IosToolbarGroup
// (Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosNavigationBar.kt);
// the grouping rules are Rovsti's own.
package dev.rovsti.design.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.rememberPressFill
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosToolbarGroup
import dev.rovsti.design.press.scalePress
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One toolbar button. [label] is its accessibility name; [id] becomes the test tag `toolbar-<id>`. */
@Immutable
class ToolbarAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** Most actions shown as buttons; every further action goes to the overflow menu. */
const val MaxToolbarActions = 2

private val IconSize = 20.dp

/**
 * A glass capsule that groups 44 dp buttons: Convx's [IosToolbarGroup] (a 44 dp tall capsule of liquid glass over the app
 * backdrop in a 48 dp layout box, so every button keeps a 48 dp tall touch target).
 */
@Composable
fun ToolbarCapsule(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    IosToolbarGroup(modifier, debugTag = "toolbar.capsule", content = content)
}

/** The gap between two capsules: it splits one group of buttons into two capsules. */
@Composable
fun ToolbarSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.width(Spacing.s))
}

/**
 * A 44 dp wide, 48 dp tall button for a [ToolbarCapsule]: no surface of its own (the capsule is the glass), a round
 * fill that lights up while pressed, and the scale press. Tagged `toolbar-<id>`.
 */
@Composable
fun ToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val press = rememberPressFill(source)
    val tokens = RovstiTheme.glass
    Box(
        modifier
            .size(width = Sizes.button, height = Sizes.touch)
            .scalePress(true)
            .semantics { contentDescription = label }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(Sizes.button).drawBehind {
                val amount = press.value
                if (amount > 0f) drawCircle(tokens.glassActive.copy(alpha = tokens.glassActive.alpha * amount))
            },
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(icon, null, tint = tokens.foreground, size = IconSize)
        }
    }
}

/**
 * The buttons at the trailing edge of a title bar: at most [MaxToolbarActions] of [actions] in one capsule and, when
 * there is an overflow, a second capsule with the ellipsis button. The overflow exists when more actions are given than
 * fit, or when [onOverflow] is set. Tapping the ellipsis calls [onOverflow] with the button's bounds in the window (the
 * anchor of the menu) and the actions that did not fit.
 */
@Composable
fun Toolbar(
    actions: ImmutableList<ToolbarAction>,
    modifier: Modifier = Modifier,
    onOverflow: ((anchor: Rect, hidden: ImmutableList<ToolbarAction>) -> Unit)? = null,
) {
    val shown = remember(actions) { actions.take(MaxToolbarActions) }
    val hidden = remember(actions) { persistentListOf<ToolbarAction>().addAll(actions.drop(MaxToolbarActions)) }
    val hasOverflow = hidden.isNotEmpty() || onOverflow != null
    if (shown.isEmpty() && !hasOverflow) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (shown.isNotEmpty()) {
            ToolbarCapsule {
                for (action in shown) {
                    ToolbarButton(
                        icon = action.icon,
                        label = action.label,
                        onClick = action.onClick,
                        modifier = Modifier.testTag("toolbar-${action.id}"),
                    )
                }
            }
        }
        if (shown.isNotEmpty() && hasOverflow) ToolbarSpacer()
        if (hasOverflow) {
            var anchor by remember { mutableStateOf(Rect.Zero) }
            ToolbarCapsule {
                ToolbarButton(
                    icon = RovstiIcons.Ellipsis,
                    label = "More",
                    onClick = { onOverflow?.invoke(anchor, hidden) },
                    modifier =
                        Modifier
                            .testTag("toolbar-overflow")
                            .onGloballyPositioned { anchor = it.boundsInWindow() },
                )
            }
        }
    }
}
