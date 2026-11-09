package dev.rovsti.shell

import android.view.Display
import android.view.Window

/** One display mode, reduced to what the choice needs. */
data class ModeSpec(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/**
 * The id of the mode with the highest refresh rate at the resolution [current] runs at, or null when [current] already
 * is that mode (or nothing better exists). Changing the resolution would make the panel blank for a moment, so only
 * modes of the same size are candidates. Ties keep the lower id, which is stable across calls.
 */
fun highestRefreshModeId(
    current: ModeSpec,
    modes: List<ModeSpec>,
): Int? {
    val best =
        modes
            .filter { it.width == current.width && it.height == current.height }
            .sortedWith(compareByDescending<ModeSpec> { it.refreshRate }.thenBy { it.id })
            .firstOrNull() ?: return null
    return best.id.takeIf { best.refreshRate > current.refreshRate + 0.5f && it != current.id }
}

private fun Display.Mode.spec() = ModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)

/**
 * Asks the system for the highest refresh rate of [display] at its current resolution
 * (`WindowManager.LayoutParams.preferredDisplayModeId`). Returns the mode id that was requested, or null when nothing
 * was changed. The system may still lower the rate (battery saver, thermal); this is a preference, not a demand.
 */
fun Window.preferHighestRefreshRate(display: Display?): Int? {
    display ?: return null
    val id = highestRefreshModeId(display.mode.spec(), display.supportedModes.map { it.spec() }) ?: return null
    val params = attributes
    params.preferredDisplayModeId = id
    attributes = params
    return id
}
