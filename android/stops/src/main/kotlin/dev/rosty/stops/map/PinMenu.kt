package dev.rosty.stops.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalResources
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CoordFormatRow
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.overlay.ButtonMenuEntry
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.stops.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** What the menu of a pin shows; built by [PickController.pinMenuModel] from `stops.coord_format`. */
@Immutable
data class PinMenuModel(
    val slot: Slot,
    /** "Point A" or the stop's name. */
    val title: String,
    val coord: Coord,
    /** S1: decimal, DMS, DDM, Plus Code, geo: link, OpenStreetMap link, with their texts. */
    val formats: List<CoordFormatRow>,
    /** S34: "3.2 km from you, S", when the position is known. */
    val fromYou: String?,
    /** The transit stop the pin sits on, if any ("Lines"). */
    val stopId: String?,
    /** The two ends are not a saved stop yet. */
    val canSave: Boolean,
    val canSwap: Boolean,
) {
    fun text(id: String): String? = formats.firstOrNull { it.id == id }?.text

    /** The first row of the menu: the coordinate as decimal degrees. */
    val details: String get() = text(FORMAT_DECIMAL).orEmpty()

    companion object {
        const val FORMAT_DECIMAL = "decimal"
        const val FORMAT_GEO = "geouri"
    }
}

/**
 * What the pin menu does that needs the app: the shell wires the clipboard, the QR sheet, intents, the measure tool
 * and the saved stops. Move, Swap and Remove go to the [PickController].
 */
interface PinMenuActions {
    /** S1: put [text] on the clipboard; [label] names the format ("Plus Code") for the confirmation. */
    fun copy(
        label: String,
        text: String,
    )

    /** S3: show [geoUri] as a QR code titled [title]. */
    fun showQr(
        title: String,
        geoUri: String,
    )

    /** S4: open [geoUri] in another maps app. */
    fun openInMaps(geoUri: String)

    /** S34: start the measure tool at [from]. */
    fun measureFrom(from: Coord)

    /** Save A and B as a stop. */
    fun saveAsStop()

    /** The lines that call at transit stop [stopId]. */
    fun showLines(stopId: String)

    fun move(slot: Slot)

    fun swap()

    fun remove(slot: Slot)
}

/** The words of the pin menu (en and tr resources). */
@Immutable
class PinMenuStrings(
    val copyAs: String,
    val qr: String,
    val openInMaps: String,
    val measureFromHere: String,
    val saveAsStop: String,
    val move: String,
    val swap: String,
    val remove: String,
    val lines: String,
    val thirdTapHint: String,
    /** By `stops.coord_format` id; an id without an entry shows the label Rust sent. */
    val formats: Map<String, String>,
)

@Composable
fun rememberPinMenuStrings(): PinMenuStrings {
    val resources = LocalResources.current
    return remember(resources) {
        PinMenuStrings(
            copyAs = resources.getString(R.string.pick_menu_copy_as),
            qr = resources.getString(R.string.pick_menu_qr),
            openInMaps = resources.getString(R.string.pick_menu_open_in_maps),
            measureFromHere = resources.getString(R.string.pick_menu_measure),
            saveAsStop = resources.getString(R.string.pick_menu_save),
            move = resources.getString(R.string.pick_menu_move),
            swap = resources.getString(R.string.pick_menu_swap),
            remove = resources.getString(R.string.pick_menu_remove),
            lines = resources.getString(R.string.pick_menu_lines),
            thirdTapHint = resources.getString(R.string.pick_hint_full),
            formats =
                mapOf(
                    "decimal" to resources.getString(R.string.pick_format_decimal),
                    "dms" to resources.getString(R.string.pick_format_dms),
                    "ddm" to resources.getString(R.string.pick_format_ddm),
                    "pluscode" to resources.getString(R.string.pick_format_plus_code),
                    "geouri" to resources.getString(R.string.pick_format_geo),
                    "osmlink" to resources.getString(R.string.pick_format_osm),
                ),
        )
    }
}

/** The rows of a pin's menu, in the order of the GPUI one with "Copy as" (S1) and "Save as stop" added. */
fun pinMenuItems(
    model: PinMenuModel,
    strings: PinMenuStrings,
    actions: PinMenuActions,
    onCopyAs: () -> Unit,
): ImmutableList<MenuItem> {
    val items = ArrayList<MenuItem>()
    // Where the pin is and how far that is: captions, not actions.
    items += MenuItem.Action(model.details, enabled = false) {}
    model.fromYou?.let { items += MenuItem.Action(it, enabled = false) {} }
    items += MenuItem.Separator
    items += MenuItem.Action(strings.copyAs, onClick = onCopyAs)
    model.text(PinMenuModel.FORMAT_GEO)?.let { geo ->
        items += MenuItem.Action(strings.qr) { actions.showQr(model.title, geo) }
        items += MenuItem.Action(strings.openInMaps) { actions.openInMaps(geo) }
    }
    items += MenuItem.Action(strings.measureFromHere) { actions.measureFrom(model.coord) }
    if (model.canSave) items += MenuItem.Action(strings.saveAsStop) { actions.saveAsStop() }
    model.stopId?.let { id -> items += MenuItem.Action(strings.lines) { actions.showLines(id) } }
    items += MenuItem.Separator
    items += MenuItem.Action(strings.move) { actions.move(model.slot) }
    if (model.canSwap) items += MenuItem.Action(strings.swap) { actions.swap() }
    items += MenuItem.Action(strings.remove, destructive = true) { actions.remove(model.slot) }
    return items.toImmutableList()
}

/** S1: one row per coordinate format; a tap copies its text. */
fun copyAsItems(
    model: PinMenuModel,
    strings: PinMenuStrings,
    actions: PinMenuActions,
): ImmutableList<MenuItem> =
    model.formats
        .map { row ->
            val label = strings.formats[row.id] ?: row.label
            MenuItem.Action(label) { actions.copy(label, row.text) }
        }.toImmutableList()

/**
 * Opens the menu of a pin at [anchor] (the pin's window rectangle, [PickController.pinAnchor]) in the overlay host.
 * "Copy as" replaces it with the format list at the same anchor.
 */
fun OverlayController.presentPinMenu(
    anchor: Rect,
    model: PinMenuModel,
    strings: PinMenuStrings,
    actions: PinMenuActions,
): ButtonMenuEntry {
    val items = pinMenuItems(model, strings, actions) { presentCopyAsMenu(anchor, model, strings, actions) }
    return present(ButtonMenuEntry(anchor, items)) as ButtonMenuEntry
}

fun OverlayController.presentCopyAsMenu(
    anchor: Rect,
    model: PinMenuModel,
    strings: PinMenuStrings,
    actions: PinMenuActions,
): ButtonMenuEntry = present(ButtonMenuEntry(anchor, copyAsItems(model, strings, actions))) as ButtonMenuEntry

/** The hint of a third tap with both ends set, as a toast (the GPUI pill). */
fun OverlayController.showThirdTapHint(strings: PinMenuStrings) = showToast(strings.thirdTapHint)
