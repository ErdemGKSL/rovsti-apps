package dev.rovsti.design.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.rovsti.design.ios.component.IosSwipeAction
import dev.rovsti.design.ios.component.IosSwipeableRow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One button revealed by swiping a [SwipeRow] (ORIGINAL). */
data class SwipeAction(
    val label: String,
    val tint: Color,
    val icon: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * Which row of a list is swiped open (ORIGINAL). At most one row is open: opening or dragging another row closes the
 * open one, and a tap anywhere on a row while one is open only closes it (the tap does not reach the row's content).
 */
@Stable
class SwipeRowGroup {
    /** The key of the open row, or null when every row rests. */
    var openKey: Any? by mutableStateOf(null)
        private set

    /** [key] is open now (or being dragged); every other row of the group closes. */
    fun opened(key: Any) {
        openKey = key
    }

    /** [key] came to rest closed. Ignored when another row has taken over meanwhile. */
    fun closed(key: Any) {
        if (openKey == key) openKey = null
    }

    /** Closes whichever row is open. */
    fun closeAll() {
        openKey = null
    }
}

@Composable
fun rememberSwipeRowGroup(): SwipeRowGroup = remember { SwipeRowGroup() }

/** The group [SwipeRow]s join when none is passed explicitly. */
val LocalSwipeRowGroup = staticCompositionLocalOf<SwipeRowGroup?> { null }

/** Makes every [SwipeRow] inside [content] one group: one open row at a time. */
@Composable
fun SwipeRowGroup(
    group: SwipeRowGroup = rememberSwipeRowGroup(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSwipeRowGroup provides group, content = content)
}

/**
 * A list row with swipe actions: [leading] reveals from the start edge, [trailing] from the end edge, the LAST trailing
 * action sits at the edge and runs on a full swipe (as Mail's Delete does). A facade over `IosSwipeableRow` with our
 * [SwipeAction] model and the [SwipeRowGroup] rule (one row open per list).
 */
@Composable
fun SwipeRow(
    modifier: Modifier = Modifier,
    leading: ImmutableList<SwipeAction> = persistentListOf(),
    trailing: ImmutableList<SwipeAction> = persistentListOf(),
    group: SwipeRowGroup? = LocalSwipeRowGroup.current,
    rowKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val key = rowKey ?: remember { Any() }
    val leadingActions = remember(leading) { leading.map { IosSwipeAction(it.label, it.tint, it.icon, it.onClick) } }
    val trailingActions = remember(trailing) { trailing.map { IosSwipeAction(it.label, it.tint, it.icon, it.onClick) } }
    IosSwipeableRow(
        modifier = modifier,
        leadingActions = leadingActions,
        trailingActions = trailingActions,
        onFullSwipe = trailingActions.lastOrNull()?.onClick,
        group = group,
        rowKey = key,
        content = content,
    )
}
