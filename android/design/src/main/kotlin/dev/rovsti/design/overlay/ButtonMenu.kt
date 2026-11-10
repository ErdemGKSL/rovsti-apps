// Facade (ORIGINAL): the rendering, gestures and motion of the menus are `IosContextMenuEntry` / `IosMenu`
// (ios/component, the ported ones); this file only keeps Rovsti's API (`ButtonMenuEntry`, `menuTrigger`).
package dev.rovsti.design.overlay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.rovsti.design.R
import dev.rovsti.design.ios.component.IosContextMenuEntry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The menu that opens off a button: the ported context menu without a lifted copy ([IosContextMenuEntry], placed above or
 * below [anchor], growing out of it with a blur that clears). [anchor] is the trigger's rect in window pixels, captured
 * once by the caller. A finger that went down on the trigger can go on to slide over the rows (see
 * [OverlayController.beginSlide]).
 */
class ButtonMenuEntry(
    anchor: Rect,
    items: ImmutableList<MenuItem>,
) : IosContextMenuEntry(anchor = anchor, sections = items.toSections())

/**
 * Makes this element open a [ButtonMenuEntry] on *down* (not on lift), anchored to the element's bounds as they were
 * at that moment. The element keeps its size and position while the menu is open (a pressed look is the caller's
 * business and must only change draw-phase properties). Use it on an element that has no click action of its own.
 * Touching it while its menu is open dismisses the menu (the scrim normally catches that touch first).
 */
@Composable
fun Modifier.menuTrigger(
    controller: OverlayController,
    items: ImmutableList<MenuItem>,
    label: String = stringResource(R.string.design_open_menu),
): Modifier {
    val holder = remember { AnchorHolder() }
    val live = remember { arrayOfNulls<ButtonMenuEntry>(1) }
    val latestItems by rememberUpdatedState(items)

    /** The entry this touch opened, or `null` when it closed the menu that was already open. */
    fun open(): ButtonMenuEntry? {
        val current = live[0]
        if (current != null && current.state.targetState) {
            controller.dismiss(current)
            return null
        }
        val entry = ButtonMenuEntry(holder.rect, latestItems)
        live[0] = entry
        controller.present(entry)
        return entry
    }

    return this
        .captureAnchor(holder)
        .pointerInput(controller) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val entry = open() ?: return@awaitEachGesture
                // The same finger goes on to slide over the rows: its window positions are buffered until the card
                // is composed, then replayed in order.
                val positions = Channel<Offset>(Channel.UNLIMITED)
                controller.beginSlide(entry, positions.receiveAsFlow())
                val origin = holder.rect.topLeft
                positions.trySend(origin + down.position)
                var lifted = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        positions.trySend(origin + change.position)
                        if (!change.pressed) {
                            lifted = true
                            break
                        }
                    }
                } finally {
                    if (!lifted) positions.trySend(Offset.Unspecified)
                    positions.close()
                }
            }
        }.semantics {
            role = Role.Button
            onClick(label) {
                open()
                true
            }
        }
}
