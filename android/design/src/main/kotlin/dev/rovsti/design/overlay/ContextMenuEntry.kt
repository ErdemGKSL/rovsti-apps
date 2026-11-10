package dev.rovsti.design.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.R
import dev.rovsti.design.ios.component.AppleContextMenuEntry
import dev.rovsti.design.ios.component.AppleContextMenuState
import dev.rovsti.design.ios.component.IosContextMenuEntry
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * What a long-pressed element shares with its menu: its window bounds ([anchor]) and how much of it is hidden
 * ([cover], 0..1) while a lifted copy stands in for it. A plain field and a float state read in the draw phase only.
 */
@Stable
class ContextMenuTarget {
    val anchor = AnchorHolder()
    val cover = mutableFloatStateOf(0f)
}

/** An entry that follows the finger that opened it: see [contextMenu]'s `onPreviewDrag` and `onPreviewRelease`. */
interface PreviewDragTarget {
    /** The finger is [offset] away from where it went down. */
    fun drag(offset: Offset)

    /** The finger was lifted or taken away. */
    fun release()
}

/** How a long-pressed element is shown while its menu is open. */
sealed interface ContextMenuStyle {
    /** The element itself, drawn by [preview], lifts 5 % and the action panel sits next to it (rows, chips). */
    class Lifted(
        val preview: @Composable () -> Unit,
    ) : ContextMenuStyle

    /** The element grows into a [card] [cardHeight] tall and the action panel hangs under it (contacts, stops). */
    class Card(
        val card: @Composable () -> Unit,
        val cardHeight: Dp,
    ) : ContextMenuStyle
}

/**
 * Long-press menu on this element, all wired (ORIGINAL): the gesture of [contextMenu] with its press feedback, the
 * entry for [style], the element hidden while its copy is shown, and the finger handed to the entry for dragging.
 * A tap calls [onTap]; the element's own click handling must be left to this modifier (a child button still wins its
 * own taps). [items] are the actions of the panel and, for screen readers, the custom accessibility actions.
 */
@Composable
fun Modifier.longPressMenu(
    controller: OverlayController,
    items: ImmutableList<MenuItem>,
    style: ContextMenuStyle,
    onTap: (() -> Unit)? = null,
    label: String = stringResource(R.string.design_show_actions),
): Modifier {
    val target = remember { ContextMenuTarget() }
    val press = rememberPressRamp()
    val live = remember { arrayOfNulls<OverlayEntry>(1) }
    val latestItems = rememberUpdatedState(items)
    val latestStyle = rememberUpdatedState(style)
    return contextMenu(
        anchor = target.anchor,
        press = press,
        onTap = onTap,
        onOpen = { rect ->
            val current = live[0]
            if (current == null || !current.state.targetState) {
                val entry =
                    when (val s = latestStyle.value) {
                        is ContextMenuStyle.Lifted -> {
                            ContextMenuEntry(rect, latestItems.value, target, s.preview)
                        }

                        is ContextMenuStyle.Card -> {
                            PreviewCardEntry(
                                rect,
                                latestItems.value,
                                target,
                                s.card,
                                s.cardHeight,
                            )
                        }
                    }
                live[0] = entry
                controller.present(entry)
            }
        },
        onPreviewDrag = { (live[0] as? PreviewDragTarget)?.drag(it) },
        onPreviewRelease = { (live[0] as? PreviewDragTarget)?.release() },
        actions = items,
        label = label,
        cover = { target.cover.floatValue },
    )
}

private val LiftRadius = 16.dp

/**
 * The context menu of rows and chips: Convx's [IosContextMenuEntry] (scrim, the element lifted 5 % with a shadow, the
 * glass menu grown out of it). A *dedicated* copy of the element, drawn by [preview] at the element's exact window
 * rect, stands in while the real one goes transparent through [target] (its layout is untouched). The `LongPress`
 * haptic plays when it opens.
 */
class ContextMenuEntry(
    anchor: Rect,
    items: ImmutableList<MenuItem> = persistentListOf(),
    target: ContextMenuTarget? = null,
    preview: @Composable () -> Unit,
) : IosContextMenuEntry(
        anchor = anchor,
        sections = items.toSections(),
        lifted = {
            val colors = RovstiTheme.colors
            Box(
                Modifier
                    .testTag("lifted-row")
                    .clip(RoundedCornerShape(LiftRadius))
                    .background(colors.groupedSurface)
                    .clearAndSetSemantics { },
            ) { preview() }
        },
        target = target,
        openHaptic = true,
        panelTag = "context-panel",
        debugTag = "context.panel",
        titleRes = R.string.design_actions,
    )

/**
 * The context menu of contacts, stops and messages: Convx's Apple-Music-style preview card with the menu hanging under
 * it (`AppleContextMenuEntry`). The long-pressed element grows into [card] [cardHeight] tall; while the finger that
 * opened the menu is still down the card follows it ([PreviewDragTarget]), and releasing past 64 dp downward, or 40 %
 * of the way back to the element, dismisses.
 */
@Suppress("FunctionName")
fun PreviewCardEntry(
    anchor: Rect,
    items: ImmutableList<MenuItem> = persistentListOf(),
    target: ContextMenuTarget? = null,
    card: @Composable () -> Unit,
    cardHeight: Dp = 200.dp,
): OverlayEntry =
    AppleContextMenuEntry(
        menu = AppleContextMenuState(),
        anchor = anchor,
        sections = items.toSections(),
        quickActions = emptyList(),
        preview = { Box(Modifier.fillMaxWidth().height(cardHeight)) { card() } },
        onPreviewClick = null,
        target = target,
        openHaptic = true,
        panelTag = "context-panel",
        debugTag = "context.panel",
    )
