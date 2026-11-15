package dev.rosty.stops.pick

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CoordFormatRequest
import dev.rosty.core.model.stops.DestShareRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.GtfsLineChoice
import dev.rosty.core.model.stops.GtfsLinesRequest
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.resolve
import dev.rosty.platform.files.ShareIntents
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.home.HomeMap
import dev.rosty.stops.home.findComponentActivity
import dev.rosty.stops.home.showPair
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The pick sheets on the home (E7.8): opens the pick sheet, takes shared text, and turns what the sheets choose into
 * the map's ends. While a trip runs nothing is placed (the map shows the trip); the user is told why.
 */
class Picker internal constructor(
    private val viewModel: PickSheetViewModel,
    private val container: StopsContainer,
    private val map: HomeMap,
    private val overlays: OverlayController,
    private val context: Context,
    private val scope: CoroutineScope,
    private val tripRunning: () -> Boolean,
) {
    private var sheet: SheetEntry? = null
    private val field = PickField()

    val isOpen: Boolean get() = sheet != null

    /** The pick sheet, for the end the map fills next. */
    fun open() {
        if (sheet != null) return
        viewModel.open(map.pick.state.value.ends)
        lateinit var entry: SheetEntry
        entry =
            // The clipboard is read on the Paste tap only (Android shows a notice on every read).
            overlays.presentPickSheet(viewModel, context.getString(R.string.sheet_pick_title), field, {
                container.clipboard.primaryText()
            }) {
                if (sheet === entry) sheet = null
            }
        sheet = entry
    }

    fun close() {
        sheet?.let(overlays::dismiss)
        sheet = null
    }

    /** A shared link or `geo:` text handed to the app. */
    fun offer(text: String) = viewModel.offerText(text)

    internal suspend fun onEffect(effect: PickEffect) {
        when (effect) {
            is PickEffect.Toast -> {
                overlays.showToast(effect.text.resolve(context))
            }

            is PickEffect.FieldText -> {
                field.set(effect.text)
            }

            is PickEffect.Confirm -> {
                close()
                overlays.presentConfirmSheet(context.getString(R.string.pick_confirm_title), effect.place) { slot ->
                    place(slot, effect.place.dest)
                }
            }

            is PickEffect.SetPair -> {
                if (blocked()) return
                close()
                map.showPair(effect.a, effect.b)
                effect.toast?.let { overlays.showToast(it.resolve(context)) }
            }

            is PickEffect.Place -> {
                place(effect.slot, effect.dest)
            }

            is PickEffect.OpenStop -> {
                openStop(effect.stop)
            }
        }
    }

    private fun blocked(): Boolean {
        if (!tripRunning()) return false
        overlays.showToast(context.getString(R.string.pick_after_trip))
        return true
    }

    private fun place(
        slot: Slot,
        dest: Destination,
    ) {
        if (blocked()) return
        close()
        map.pick.place(slot, dest)
        map.camera.centreOn(dest.coord.lat, dest.coord.lon)
    }

    private suspend fun here(): Coord? = container.location.lastKnown()?.let { Coord(it.lat, it.lon) }

    private suspend fun openStop(stop: PickRow.Stop) {
        val lines =
            (container.ops.gtfsLines(GtfsLinesRequest(stopId = stop.id)) as? Outcome.Ok)?.value?.lines.orEmpty()
        val fromYou =
            here()?.let { position ->
                (
                    container.ops.coordFormat(
                        CoordFormatRequest(stop.coord, here = position),
                    ) as? Outcome.Ok
                )?.value?.fromYou
            }
        overlays.presentStopSheet(StopSheetModel(stop, fromYou, lines.toImmutableList()), stopActions)
    }

    private fun destOf(stop: PickRow.Stop): Destination = Destination(stop.name, stop.coord, gtfsStop = stop.id)

    private fun copy(
        text: String,
        toast: String,
    ) {
        if (container.clipboard.copy(text)) overlays.showToast(toast)
    }

    private val stopActions =
        object : StopSheetActions {
            override fun line(
                stop: PickRow.Stop,
                line: GtfsLineChoice,
            ) = place(Slot.B, destOf(stop).copy(pattern = line.pattern))

            override fun copyName(stop: PickRow.Stop) = copy(stop.name, context.getString(R.string.pick_copied_name))

            override fun copyCode(stop: PickRow.Stop) = copy(stop.id, context.getString(R.string.pick_copied_code))

            override fun share(stop: PickRow.Stop) {
                scope.launch {
                    val answer = container.ops.destShare(DestShareRequest(at = stop.coord, label = stop.name))
                    val text = (answer as? Outcome.Ok)?.value?.text ?: return@launch
                    try {
                        context.startActivity(ShareIntents.text(text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: android.content.ActivityNotFoundException) {
                        copy(text, context.getString(R.string.home_copied))
                    }
                }
            }

            override fun copyAs(stop: PickRow.Stop) {
                scope.launch {
                    val answer = container.ops.coordFormat(CoordFormatRequest(stop.coord, label = stop.name))
                    val formats = (answer as? Outcome.Ok)?.value?.formats.orEmpty()
                    overlays.presentCopyAsSheet(
                        context.getString(R.string.pick_copy_as_title),
                        formats.map { CopyFormat(it.label, it.text) }.toImmutableList(),
                    ) { format -> copy(format.text, context.getString(R.string.pick_copied_format, format.label)) }
                }
            }

            override fun use(
                stop: PickRow.Stop,
                slot: Slot,
            ) = place(slot, destOf(stop))
        }
}

/** The home's [Picker]: its view model is the activity's, its effects are collected while the home is shown. */
@Composable
fun rememberPicker(
    container: StopsContainer,
    map: HomeMap,
    overlays: OverlayController,
    tripRunning: () -> Boolean,
): Picker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by rememberUpdatedState(tripRunning)
    val owner = remember(context) { context.findComponentActivity() }
    val viewModel =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        PickSheetViewModel(
                            ops = container.ops,
                            saved = container.saved,
                            here = { container.location.lastKnown()?.let { Coord(it.lat, it.lon) } },
                        )
                    }
                }
            ViewModelProvider(owner, factory)[PickSheetViewModel::class.java]
        }
    val picker =
        remember(viewModel, map, overlays) { Picker(viewModel, container, map, overlays, context, scope) { running() } }
    LaunchedEffect(picker) { viewModel.effects.collect { picker.onEffect(it) } }
    return picker
}
