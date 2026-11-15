package dev.rosty.stops.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.rosty.design.ios.foundation.LocalIosBackdrop

/**
 * Content on a sheet's own surface: its capsule buttons take the flat bordered fill instead of glass, which would
 * sample the map under the sheet and take its colour (as Phone does in its sheets).
 */
@Composable
fun OnSheet(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIosBackdrop provides null, content = content)
}
