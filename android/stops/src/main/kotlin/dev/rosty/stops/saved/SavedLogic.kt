package dev.rosty.stops.saved

import dev.rosty.core.model.stops.SavedStop

/** The search field shows only for a list longer than this (GPUI `SEARCH_ABOVE`). */
const val SEARCH_ABOVE = 5

/** Whether the search field is worth showing for [count] stops. */
fun showSearch(count: Int): Boolean = count > SEARCH_ABOVE

/** Whether the sort pill is worth showing for [count] stops. */
fun showSort(count: Int): Boolean = count > 1

/**
 * Whether stop [id] has a neighbour with the same favourite flag [delta] places away (-1 up, 1 down) in [stops] (the
 * file order); false at a group edge or for an unknown id. Favourites and the rest are ordered separately (Rust's
 * `move_by` stays in the group), so Move up and Move down are dimmed at a group's edge.
 */
fun canMove(
    stops: List<SavedStop>,
    id: Long,
    delta: Int,
): Boolean {
    val at = stops.indexOfFirst { it.id == id }
    if (at < 0 || delta == 0) return false
    val flag = stops[at].favourite
    val step = if (delta < 0) -1 else 1
    var i = at + step
    while (i in stops.indices) {
        if (stops[i].favourite == flag) return true
        i += step
    }
    return false
}

/**
 * The `MoveBy` delta of a drag in the file order [stops] that took the row at [from] to [to]: the number of places it
 * moved among the stops of its own group (favourites or not). A drop past the group's edge counts up to the edge; Rust
 * clamps it there.
 */
fun groupDelta(
    stops: List<SavedStop>,
    from: Int,
    to: Int,
): Int {
    if (from !in stops.indices || to !in stops.indices || from == to) return 0
    val flag = stops[from].favourite
    val (low, high) = if (from < to) from + 1 to to else to to from - 1
    val passed = (low..high).count { stops[it].favourite == flag }
    return if (from < to) passed else -passed
}

/** The list row title: a star in front of a favourite (GPUI `row_title`). */
fun rowTitle(stop: SavedStop): String = if (stop.favourite) "★ ${stop.name}" else stop.name
