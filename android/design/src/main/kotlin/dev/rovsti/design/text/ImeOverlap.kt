package dev.rovsti.design.text

import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Keeps the bottom of the element clear of the keyboard by the amount the keyboard really covers it, not by the
 * keyboard's height.
 *
 * `imePadding()` and `windowInsetsPadding(WindowInsets.ime)` pad by the IME inset and trust that the window was not
 * resized. Some vendor builds (HyperOS/MIUI among them) still shrink an edge-to-edge window that asks for
 * `adjustResize`, and then the element sits above the keyboard already while the IME inset still reports the full
 * keyboard height: the content is pushed up by a second keyboard height and a keyboard-sized gap opens between the
 * focused field and the keyboard. Measuring the element on screen and padding only by the overlap is right in both
 * cases.
 *
 * @param excludeNavigationBar true when the element keeps the navigation bar clear itself (a sheet that pads its end
 *   by it): the overlap with the bar is not padded twice. False for a dock already lifted by `navigationBarsPadding()`.
 */
@Composable
fun Modifier.imeOverlapPadding(excludeNavigationBar: Boolean = true): Modifier {
    val view = LocalView.current
    val ime = WindowInsets.ime
    val exclude = if (excludeNavigationBar) WindowInsets.navigationBars else null
    var bottomOnScreen by remember { mutableIntStateOf(UNKNOWN) }
    val location = remember { IntArray(2) }
    return this
        .onGloballyPositioned { coordinates ->
            view.getLocationOnScreen(location)
            bottomOnScreen = location[1] + (coordinates.positionInWindow().y + coordinates.size.height).roundToInt()
        }.layout { measurable, constraints ->
            val pad =
                if (bottomOnScreen == UNKNOWN) {
                    0
                } else {
                    imeOverlap(
                        elementBottom = bottomOnScreen,
                        screenBottom = screenBottom(view),
                        imeHeight = ime.getBottom(this),
                        excludedHeight = exclude?.getBottom(this) ?: 0,
                    )
                }
            val inner =
                if (constraints.hasBoundedHeight) {
                    val maxH = (constraints.maxHeight - pad).coerceAtLeast(0)
                    constraints.copy(minHeight = constraints.minHeight.coerceAtMost(maxH), maxHeight = maxH)
                } else {
                    constraints
                }
            val placeable = measurable.measure(inner)
            val height =
                (placeable.height + pad).coerceIn(
                    constraints.minHeight,
                    if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity,
                )
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
}

/**
 * Pixels of the element (bottom edge [elementBottom], screen coordinates) that the keyboard covers, less the part of
 * the [excludedHeight] band at the bottom of the screen the element keeps clear itself. The keyboard is docked at
 * [screenBottom] and is [imeHeight] tall (0 when hidden or floating).
 */
fun imeOverlap(
    elementBottom: Int,
    screenBottom: Int,
    imeHeight: Int,
    excludedHeight: Int,
): Int {
    if (imeHeight <= 0) return 0
    val covered = elementBottom - (screenBottom - imeHeight)
    if (covered <= 0) return 0
    val excluded = (elementBottom - (screenBottom - excludedHeight)).coerceIn(0, excludedHeight.coerceAtLeast(0))
    return (covered - excluded).coerceAtLeast(0)
}

private fun screenBottom(view: View): Int {
    val windowManager = view.context.getSystemService(WindowManager::class.java)
    return windowManager
        ?.currentWindowMetrics
        ?.bounds
        ?.bottom
        ?.coerceAtLeast(view.rootView.height)
        ?: view.rootView.height
}

private const val UNKNOWN = Int.MIN_VALUE
