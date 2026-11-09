/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/screens/playlist/LocalPlaylistScreen.kt (lines 536-600, the reorderable list state and the drop effect)
 * and app/src/main/kotlin/com/convx/music/extensions/ListExt.kt (MutableList.move).
 * Changes for Rovsti: the playlist's mutableSongs are the favorites; the rows are found by key instead of by an offset of
 * header items (the title and the sections above shift the indices); the drop goes to a callback instead of the
 * database transaction; the YouTube Music sync is gone; haptics on the lift and on every row passed (RovstiHaptics).
 * The library is sh.calvin.reorderable 3.0.0 (Apache-2.0, see THIRD_PARTY_NOTICES.md).
 */

package dev.rovsti.contacts.favorites

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.design.haptics.LocalRovstiHaptics
import kotlinx.collections.immutable.ImmutableList
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The list key of a favorite's row; [ReorderableItem] and the `item` of the list must use the same one. */
internal fun favoriteKey(id: Long): String = "fav-$id"

/**
 * The favorites as they are being reordered: [items] is the local order the rows draw (it follows [shown] whenever no
 * row is dragged), [state] is the library's state for `ReorderableItem` / `draggableHandle`. A drop calls `onDropped`
 * once with the row's first and last position, whatever number of rows it passed on the way.
 */
internal class FavoritesReorder(
    val items: MutableList<PersonItem>,
    val state: ReorderableLazyListState,
)

@Composable
internal fun rememberFavoritesReorder(
    lazyListState: LazyListState,
    shown: ImmutableList<PersonItem>,
    scrollThresholdPadding: PaddingValues,
    onDropped: (from: Int, to: Int) -> Unit,
): FavoritesReorder {
    val mutableFavorites = remember { mutableStateListOf<PersonItem>().apply { addAll(shown) } }
    var dragInfo by remember {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    val haptics = LocalRovstiHaptics.current
    val latestHaptics = rememberUpdatedState(haptics)
    val reorderableState =
        rememberReorderableLazyListState(
            lazyListState = lazyListState,
            scrollThresholdPadding = scrollThresholdPadding,
        ) { from, to ->
            val fromIndex = mutableFavorites.indexOfFirst { favoriteKey(it.id) == from.key }
            val toIndex = mutableFavorites.indexOfFirst { favoriteKey(it.id) == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                val currentDragInfo = dragInfo
                dragInfo =
                    if (currentDragInfo == null) {
                        fromIndex to toIndex
                    } else {
                        currentDragInfo.first to toIndex
                    }

                mutableFavorites.move(fromIndex, toIndex)
                latestHaptics.value.selection()
            }
        }

    // The saved order arrived (or changed from elsewhere): the local one follows it unless a row is in the air.
    LaunchedEffect(shown) {
        if (!reorderableState.isAnyItemDragging && mutableFavorites.toList() != shown.toList()) {
            mutableFavorites.clear()
            mutableFavorites.addAll(shown)
        }
    }

    val latestDropped = rememberUpdatedState(onDropped)
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            // Cleared up front: if the callback throws, or this effect is cancelled/restarted before reaching the end,
            // leaving dragInfo set would make the NEXT drag's onMove silently reuse this stale "from" index.
            val info = dragInfo
            dragInfo = null
            info?.let { (from, to) -> if (from != to) latestDropped.value(from, to) }
        }
    }
    return remember(reorderableState) { FavoritesReorder(mutableFavorites, reorderableState) }
}

fun <T> MutableList<T>.move(
    fromIndex: Int,
    toIndex: Int,
): MutableList<T> {
    add(toIndex, removeAt(fromIndex))
    return this
}
