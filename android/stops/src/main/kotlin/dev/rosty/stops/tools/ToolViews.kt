package dev.rosty.stops.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.TextButton
import dev.rosty.design.glass.GlassPreset
import dev.rosty.design.glass.rostyGlass
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.overlay.ButtonMenuEntry
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

object ToolTags {
    const val MeasureCard = "measure-card"
    const val MeasureSummary = "measure-summary"
    const val MeasureUndo = "measure-undo"
    const val MeasureClear = "measure-clear"
    const val MeasureMore = "measure-more"
    const val MeasureDone = "measure-done"
    const val MyLocation = "my-location"
    const val MyLocationCopy = "my-location-copy"
    const val MyLocationShare = "my-location-share"
    const val MyLocationQr = "my-location-qr"
    const val MyLocationA = "my-location-a"
    const val MyLocationB = "my-location-b"
}

/** The headline of the measure card (GPUI `measure_summary`): what to tap next, then "Total 1.4 km, 3 legs". */
@Composable
fun measureSummary(state: MeasureUiState): String {
    val result = state.result
    return when {
        state.points.isEmpty() -> stringResource(R.string.measure_start)
        state.points.size == 1 -> stringResource(R.string.measure_second)
        result == null -> ""
        else -> pluralStringResource(R.plurals.measure_summary, result.legs.size, result.total, result.legs.size)
    }
}

/**
 * The measure card under the search pill (GPUI `measure_card`): the summary and Undo, Clear, More (from two points:
 * copy, GPX, save as stop) and Done. Glass over the map, like the rest of the floating chrome.
 */
@Composable
fun MeasureCard(
    tools: MapTools,
    modifier: Modifier = Modifier,
) {
    val state by tools.measure.state.collectAsState()
    val overlays = LocalOverlay.current
    val summary = measureSummary(state)
    val result = state.result
    // Written on placement, read on the tap (not state: a move must not recompose the card).
    val moreAnchor = remember { arrayOf(Rect.Zero) }
    // It comes in like the other floating chrome: a critically damped fade and a slight grow from the pill above
    // (no overshoot, so nothing to gate for reduce motion); read in the layer, not in composition.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(shown) { shown.animateTo(1f, RostyMotion.appear(APPEAR_THRESHOLD)) }
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val t = shown.value
                alpha = t
                scaleX = APPEAR_FROM + (1f - APPEAR_FROM) * t
                scaleY = scaleX
                transformOrigin = TransformOrigin(0.5f, 0f)
            }.rostyGlass(GlassPreset.Bar, RostyShapes.card, "stops.measure")
            .padding(Spacing.m)
            .testTag(ToolTags.MeasureCard),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        // The summary with Done at its end; the edits below, sharing the width (they never truncate at one).
        Row(verticalAlignment = Alignment.CenterVertically) {
            RostyText(
                summary,
                Modifier.weight(1f).testTag(ToolTags.MeasureSummary),
                style = RostyTheme.type.headline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                stringResource(R.string.measure_done),
                tools::toggleMeasure,
                Modifier.testTag(ToolTags.MeasureDone),
            )
        }
        // Equal thirds; with large text each keeps its own width, one under the other, instead of cutting labels.
        val large = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
        val more = {
            result?.let {
                overlays.present(
                    ButtonMenuEntry(moreAnchor[0], tools.moreItems(summary, it).toImmutableList()),
                )
            }
        }
        val buttons: @Composable (Modifier) -> Unit = { share ->
            CapsuleButton(
                stringResource(R.string.measure_undo_last),
                tools.measure::undo,
                share.testTag(ToolTags.MeasureUndo),
                enabled = state.points.isNotEmpty(),
                fillWidth = !large,
            )
            CapsuleButton(
                stringResource(R.string.measure_clear),
                tools::clearMeasure,
                share.testTag(ToolTags.MeasureClear),
                enabled = state.points.isNotEmpty(),
                fillWidth = !large,
            )
            CapsuleButton(
                stringResource(R.string.measure_more),
                { more() },
                share
                    .onGloballyPositioned { moreAnchor[0] = it.boundsInWindow() }
                    .testTag(ToolTags.MeasureMore),
                enabled = result != null,
                fillWidth = !large,
            )
        }
        if (large) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) { buttons(Modifier) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) { buttons(Modifier.weight(1f)) }
        }
    }
}

/** What the "My location" sheet shows. */
class MyLocationModel(
    val title: String,
    /** "Accurate to 15 m". */
    val accuracy: String,
)

interface MyLocationActions {
    fun copyAs()

    fun share()

    fun qr()

    fun use(slot: Slot)
}

/** S25 (GPUI `my_location_body`): Copy as, Share, QR code, Use as A, Use as B. */
fun OverlayController.presentMyLocationSheet(
    model: MyLocationModel,
    actions: MyLocationActions,
): SheetEntry =
    SheetEntry(title = model.title, detents = persistentListOf(Detent.Medium)) { sheet ->
        fun go(action: () -> Unit): () -> Unit =
            {
                sheet.dismiss()
                action()
            }
        OnSheet {
            Column(
                Modifier.fillMaxWidth().testTag(ToolTags.MyLocation),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    RostyText(model.title, style = RostyTheme.type.title2)
                    RostyText(
                        model.accuracy,
                        style = RostyTheme.type.subheadline,
                        color = RostyTheme.colors.labelSecondary,
                    )
                }
                InsetGroupedSection {
                    row {
                        ListRow(
                            stringResource(R.string.pick_copy_as),
                            Modifier.testTag(ToolTags.MyLocationCopy),
                            onClick = go(actions::copyAs),
                        )
                    }
                    row {
                        ListRow(
                            stringResource(R.string.pick_share),
                            Modifier.testTag(ToolTags.MyLocationShare),
                            onClick = go(actions::share),
                        )
                    }
                    row {
                        ListRow(
                            stringResource(R.string.pick_menu_qr),
                            Modifier.testTag(ToolTags.MyLocationQr),
                            onClick = go(actions::qr),
                        )
                    }
                }
                InsetGroupedSection {
                    row {
                        ListRow(
                            stringResource(R.string.pick_use_a),
                            Modifier.testTag(ToolTags.MyLocationA),
                            onClick = go { actions.use(Slot.A) },
                        )
                    }
                    row {
                        ListRow(
                            stringResource(R.string.pick_use_b),
                            Modifier.testTag(ToolTags.MyLocationB),
                            onClick = go { actions.use(Slot.B) },
                        )
                    }
                }
            }
        }
    }.also { present(it) }

/** From this font scale the measure card's buttons keep their own widths, one under the other. */
private const val LARGE_FONT_SCALE = 1.5f

/** The card grows from this scale as it appears. */
private const val APPEAR_FROM = 0.96f

private const val APPEAR_THRESHOLD = 0.001f
