package dev.rosty.stops.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.rosty.design.ios.foundation.LocalIosBackdrop
import dev.rosty.stops.StopsSizes

/**
 * Content on a sheet's own surface: its capsule buttons take the flat bordered fill instead of glass, which would
 * sample the map under the sheet and take its colour (as Phone does in its sheets). In a sheet ([titleGap], the
 * default) it starts [StopsSizes.sheetTitleTop] under the grabber, so every Stops sheet's title has the same room above
 * it; a full-screen surface (the alarm) passes false.
 */
@Composable
fun OnSheet(
    titleGap: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Emitted into the sheet's column, above the content.
    if (titleGap) Spacer(Modifier.height(StopsSizes.sheetTitleTop))
    CompositionLocalProvider(LocalIosBackdrop provides null, content = content)
}
