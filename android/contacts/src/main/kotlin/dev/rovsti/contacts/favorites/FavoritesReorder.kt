/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/screens/playlist/LocalPlaylistScreen.kt (lines 536-600, the reorderable list state and the drop effect)
 * and app/src/main/kotlin/com/convx/music/extensions/ListExt.kt (MutableList.move).
 * Changes for Rovsti (the generic part now lives in :design `list/Reorder.kt`; this file keeps the Favorites key and type): the playlist's mutableSongs are any list of keyed items (Favorites, Emergency contacts); the rows are found by key instead of by an offset of
 * header items (the title and the sections above shift the indices); the drop goes to a callback instead of the
 * database transaction; the YouTube Music sync is gone; haptics on the lift and on every row passed (RovstiHaptics).
 * The library is sh.calvin.reorderable 3.0.0 (Apache-2.0, see THIRD_PARTY_NOTICES.md).
 */

package dev.rovsti.contacts.favorites

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.design.list.ReorderList
import dev.rovsti.design.list.rememberReorderList
import kotlinx.collections.immutable.ImmutableList

/** The list key of a favorite's row; [sh.calvin.reorderable.ReorderableItem] and the `item` of the list must use the same one. */
internal fun favoriteKey(id: Long): String = "fav-$id"

/** The favorites as they are being reordered (see [ReorderList]). */
internal typealias FavoritesReorder = ReorderList<PersonItem>

@Composable
internal fun rememberFavoritesReorder(
    lazyListState: LazyListState,
    shown: ImmutableList<PersonItem>,
    scrollThresholdPadding: PaddingValues,
    onDropped: (from: Int, to: Int) -> Unit,
): FavoritesReorder =
    rememberReorderList(lazyListState, shown, { favoriteKey(it.id) }, scrollThresholdPadding, onDropped)
