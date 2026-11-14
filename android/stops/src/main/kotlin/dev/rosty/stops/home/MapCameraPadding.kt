package dev.rosty.stops.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The glass-covered edges of the window, for the map camera: [insets] is the top chrome (pill and chips), the
 * button column at the end, and the sheet at its **settled** detent at the bottom. Written by the home on settle and
 * on resize only; a map reads it where it fits or centres the camera.
 */
@Stable
class MapCameraPadding {
    var insets: PaddingValues by mutableStateOf(PaddingValues())
        internal set

    /** How many times [insets] changed (tests check it moves on settle, not per frame). */
    var updates: Int = 0
        private set

    internal fun update(next: PaddingValues) {
        if (next == insets) return
        insets = next
        updates++
    }
}

/** Test tag of the map layer's node. */
const val HomeMapTag = "home-map"
