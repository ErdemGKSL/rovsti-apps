package dev.rosty.stops.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.rosty.core.model.headless.Side
import dev.rosty.core.model.headless.TripState
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.GtfsLineChoice
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.SheetState
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.platform.system.permissions.PermissionController
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.PermissionState
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.system.permissions.SpecialKind
import dev.rosty.shell.permissions.LocalPermissionController
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.shell.permissions.PermissionRow
import dev.rosty.shell.permissions.SpecialAccessRow
import dev.rosty.shell.permissions.SpecialRowTexts
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet
import dev.rosty.stops.notice.noticeLine
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the trip UI. */
object TripTags {
    const val Start = "trip-start"
    const val Clear = "trip-clear"
    const val Line = "trip-line"
    const val Card = "trip-card"
    const val Distance = "trip-distance"
    const val Side = "trip-side"
    const val Copy = "trip-copy-status"
    const val Share = "trip-share-status"
    const val Stop = "trip-stop"
    const val Restart = "trip-restart"
    const val Resume = "trip-resume"
    const val Foreground = "trip-foreground"
    const val Preflight = "trip-preflight"
    const val PreflightStart = "trip-preflight-start"
    const val ArrivingSoon = "trip-arriving-soon"
}

/** Reads the [Preflight] from the shell's permission flows and special access (re-read when composed again). */
@Composable
fun rememberPreflight(
    controller: PermissionController = LocalPermissionController.current,
    special: SpecialAccess = LocalSpecialAccess.current,
): Preflight {
    val location by controller.state(PermissionGroup.LOCATION).collectAsState()
    val notifications by controller.state(PermissionGroup.NOTIFICATIONS).collectAsState()
    val background by controller.state(PermissionGroup.BACKGROUND_LOCATION).collectAsState()
    // Special access has no flow: it is read again each time the screen resumes (back from Settings).
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    return Preflight(
        location = location,
        notifications = notifications,
        background = background,
        fullScreen = remember(special, resumes) { special.fullScreenIntent() },
        battery = remember(special, resumes) { special.ignoresBatteryOptimisation() },
    )
}

/**
 * The trip part of the map sheet. With a trip: the live card, then the rows of what keeps it from tracking (AD-SA8,
 * a revoked grant) and the recommended rows. Without one, once A and B are on the map: Start and Clear, and for a
 * transit stop the line it is reached by. [onStart] runs the preflight first.
 */
@Composable
fun TripSection(
    viewModel: TripViewModel,
    a: Destination?,
    b: Destination?,
    onStart: (Destination, Destination) -> Unit,
    onClear: () -> Unit,
    onLine: (Slot, Destination) -> Unit,
    overlays: OverlayController,
    arrivingSoon: ArrivingSoonHook = ArrivingSoonHook.None,
    lastTrip: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val preflight = rememberPreflight()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        val trip = state.trip
        if (trip != null) {
            LiveCard(state, trip, viewModel::onAction)
            ProblemRows(state.problem, preflight, viewModel::onAction)
            RecommendedRows(preflight)
        } else if (a != null && b != null) {
            StartRow(state.busy, { onStart(a, b) }, onClear)
            arrivingSoon.PlanRow()
            val slot = if (b.gtfsStop != null) Slot.B else Slot.A
            val stop = if (slot == Slot.B) b else a
            if (stop.gtfsStop != null) LineRow(viewModel, slot, stop, overlays, onLine)
            if (state.problem == TripProblem.CouldNotStart) ProblemRows(state.problem, preflight, viewModel::onAction)
        }
        if (trip == null) lastTrip()
    }
}

@Composable
private fun StartRow(
    busy: Boolean,
    onStart: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleButton(
            stringResource(if (busy) R.string.trip_starting else R.string.trip_start),
            onStart,
            Modifier.weight(1f).testTag(TripTags.Start),
            style = CapsuleStyle.Filled,
            enabled = !busy,
            fillWidth = true,
        )
        CapsuleButton(stringResource(R.string.trip_clear), onClear, Modifier.testTag(TripTags.Clear))
    }
}

/**
 * The line a transit stop is reached by (the stop sheet's line list of GPUI, AD-ST6): a row that opens the lines of
 * the stop as a menu, the chosen one checked; choosing puts its pattern on the point (the map draws the line), "Any
 * line" takes it off.
 */
@Composable
private fun LineRow(
    viewModel: TripViewModel,
    slot: Slot,
    stop: Destination,
    overlays: OverlayController,
    onLine: (Slot, Destination) -> Unit,
) {
    var lines by remember { mutableStateOf<List<GtfsLineChoice>>(emptyList()) }
    LaunchedEffect(stop.gtfsStop) { lines = viewModel.lines(stop) }
    if (lines.isEmpty()) return
    val any = stringResource(R.string.trip_line_any)
    val chosen = lines.firstOrNull { it.pattern == stop.pattern }
    val items =
        remember(lines, stop, any) {
            (
                listOf<MenuItem>(
                    MenuItem.Action(any, checked = stop.pattern == null) { onLine(slot, stop.copy(pattern = null)) },
                ) +
                    MenuItem.Separator +
                    lines.map { line ->
                        MenuItem.Action(line.title, checked = line.pattern == stop.pattern) {
                            onLine(slot, stop.copy(pattern = line.pattern))
                        }
                    }
            ).toImmutableList()
        }
    InsetGroupedSection(footer = chosen?.earlier) {
        row {
            ListRow(
                title = stringResource(R.string.trip_line),
                modifier = Modifier.menuTrigger(overlays, items).testTag(TripTags.Line),
                meta = chosen?.title ?: any,
                secondary = stop.label,
            )
        }
    }
}

/** The live card (GPUI `live_card`): the distance to the line, the side, the rule, the details, S20 and Stop. */
@Composable
private fun LiveCard(
    state: TripUiState,
    trip: RunningTrip,
    onAction: (TripAction) -> Unit,
) {
    val colors = RostyTheme.colors
    val type = RostyTheme.type
    val live = state.live
    val crossed = live != null && live.state != TripState.Tracking
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .background(colors.groupedSurface, CARD_SHAPE)
            .padding(Spacing.l)
            .testTag(TripTags.Card),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val big =
            when {
                live == null -> stringResource(R.string.trip_locating)
                crossed -> stringResource(R.string.trip_crossed)
                else -> live.distance
            }
        val small =
            when {
                live == null -> stringResource(R.string.trip_near_line, trip.label)
                crossed -> trip.label
                else -> stringResource(R.string.trip_to_line)
            }
        RostyText(big, Modifier.testTag(TripTags.Distance), style = type.largeTitle, maxLines = 1)
        RostyText(
            small,
            style = type.headline,
            color = colors.labelSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (live != null && !crossed) SideChip(live.side)
        Detail(stringResource(R.string.trip_rule, trip.label))
        if (live != null && !crossed) {
            if (live.inside) {
                Detail(stringResource(R.string.trip_inside))
            } else {
                live.circle?.let {
                    Detail(stringResource(R.string.trip_to_circle, it))
                }
            }
            Detail(live.text)
            // S41: the arrival clock, when the service has an estimate.
            if (live.etaMin != null &&
                live.arrive != null
            ) {
                Detail(stringResource(R.string.trip_eta, live.etaMin, live.arrive))
            }
        }
        state.fromYou?.let { Detail(it) }
        live?.nextFixMs?.takeIf { it > 0 }?.let { Detail(stringResource(R.string.trip_next_check, waitText(it))) }
        // AD-ST7: what became of the arriving-soon message of this trip.
        noticeLine(trip.notice, live?.noticeSent == true, live?.noticeFailed == true)?.let {
            Detail(stringResource(it), Modifier.testTag(TripTags.ArrivingSoon))
        }
        Row(
            Modifier.padding(top = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            CapsuleButton(stringResource(R.string.trip_copy_status), {
                onAction(TripAction.CopyStatus)
            }, Modifier.testTag(TripTags.Copy))
            CapsuleButton(stringResource(R.string.trip_share_status), {
                onAction(TripAction.ShareStatus)
            }, Modifier.testTag(TripTags.Share))
        }
        CapsuleButton(
            stringResource(if (state.busy) R.string.trip_stopping else R.string.trip_stop),
            { onAction(TripAction.Stop) },
            Modifier.padding(top = Spacing.s).testTag(TripTags.Stop),
            tint = colors.destructive,
            enabled = !state.busy,
        )
    }
}

@Composable
private fun Detail(
    text: String,
    modifier: Modifier = Modifier,
) {
    RostyText(
        text,
        modifier,
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.labelSecondary,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "Left side", "Right side" or "On the line", tinted like the radar's halves. */
@Composable
private fun SideChip(side: Side?) {
    val colors = RostyTheme.colors
    val (text, tint) =
        when (side) {
            Side.Left -> stringResource(R.string.trip_side_left) to colors.accent
            Side.Right -> stringResource(R.string.trip_side_right) to colors.success
            null -> stringResource(R.string.trip_on_line) to colors.labelSecondary
        }
    RostyText(
        text,
        Modifier
            .background(tint.copy(alpha = CHIP_ALPHA), CARD_SHAPE)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
            .testTag(TripTags.Side),
        style = RostyTheme.type.headline,
        color = tint,
    )
}

/** "Next check in 12 s" / "2 min": seconds under a minute, else whole minutes. */
@Composable
private fun waitText(ms: Long): String {
    val seconds = ((ms + MS_PER_S - 1) / MS_PER_S).toInt()
    return if (seconds < S_PER_MIN) {
        pluralStringResource(R.plurals.trip_wait_seconds, seconds, seconds)
    } else {
        val minutes = (seconds + S_PER_MIN / 2) / S_PER_MIN
        pluralStringResource(R.plurals.trip_wait_minutes, minutes, minutes)
    }
}

/**
 * The rows of a trip that is not tracking as it should, each with its next action: AD-SA8 (Android refused a
 * background restart: Resume), the service lost its foreground (Resume), a start Android refused, and a grant that
 * went away while the trip runs (the trip stays, the row asks again).
 */
@Composable
private fun ProblemRows(
    problem: TripProblem?,
    preflight: Preflight,
    onAction: (TripAction) -> Unit,
) {
    val revoked = runningProblem(preflight.location, preflight.notifications)
    if (problem == null && revoked == null) return
    InsetGroupedSection {
        when (problem) {
            TripProblem.Restart, TripProblem.Foreground -> {
                row {
                    ListRow(
                        title = stringResource(R.string.trip_restart_title),
                        modifier =
                            Modifier.testTag(
                                if (problem ==
                                    TripProblem.Restart
                                ) {
                                    TripTags.Restart
                                } else {
                                    TripTags.Foreground
                                },
                            ),
                        secondary =
                            stringResource(
                                if (problem ==
                                    TripProblem.Restart
                                ) {
                                    R.string.trip_restart_text
                                } else {
                                    R.string.trip_foreground_text
                                },
                            ),
                        secondaryMaxLines = 3,
                        trailing = {
                            CapsuleButton(
                                stringResource(R.string.trip_resume),
                                { onAction(TripAction.Resume) },
                                Modifier.testTag(TripTags.Resume),
                            )
                        },
                    )
                }
            }

            TripProblem.CouldNotStart -> {
                row {
                    ListRow(
                        title = stringResource(R.string.trip_could_not_start),
                        secondary = stringResource(R.string.trip_could_not_start_text),
                        secondaryMaxLines = 3,
                    )
                }
            }

            TripProblem.Permission, null -> {
                Unit
            }
        }
        if (problem == TripProblem.Permission || revoked == PreflightStep.Location) {
            row { PermissionRow(PermissionGroup.LOCATION) }
        }
        if (revoked == PreflightStep.Notifications) row { PermissionRow(PermissionGroup.NOTIFICATIONS) }
    }
}

/** "Keep tracking in the background" (AD-SA7) and the full-screen and battery rows: recommended, never blocking. */
@Composable
private fun RecommendedRows(preflight: Preflight) {
    if (!preflight.backgroundRow && !preflight.fullScreenRow && !preflight.batteryRow) return
    InsetGroupedSection(footer = stringResource(R.string.trip_recommended_footer)) {
        if (preflight.backgroundRow) row { PermissionRow(PermissionGroup.BACKGROUND_LOCATION) }
        if (preflight.fullScreenRow) row { SpecialAccessRow(SpecialKind.FullScreenIntent, texts = fullScreenTexts()) }
        if (preflight.batteryRow) row { SpecialAccessRow(SpecialKind.BatteryOptimisation) }
    }
}

/**
 * The preflight sheet (ARCH 7.5): location, then notifications, block the start; "all the time" location, full-screen
 * alerts and the battery are recommended. Start is enabled once nothing blocks; [onStart] then starts the trip.
 */
fun OverlayController.presentPreflightSheet(
    title: String,
    onStart: () -> Unit,
): SheetEntry =
    SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
        OnSheet { PreflightBody(title, sheet, onStart) }
    }.also { present(it) }

@Composable
private fun PreflightBody(
    title: String,
    sheet: SheetState,
    onStart: () -> Unit,
) {
    val preflight = rememberPreflight()
    Column(
        Modifier.fillMaxWidth().testTag(TripTags.Preflight),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            RostyText(title, style = RostyTheme.type.title2)
            RostyText(
                stringResource(
                    when (preflight.nextStep) {
                        PreflightStep.Location -> R.string.trip_preflight_location
                        PreflightStep.Notifications -> R.string.trip_preflight_notifications
                        null -> R.string.trip_preflight_ready
                    },
                ),
                style = RostyTheme.type.subheadline,
                color = RostyTheme.colors.labelSecondary,
            )
        }
        InsetGroupedSection(header = stringResource(R.string.trip_preflight_needed)) {
            row { PermissionRow(PermissionGroup.LOCATION) }
            row { PermissionRow(PermissionGroup.NOTIFICATIONS) }
        }
        InsetGroupedSection(
            header = stringResource(R.string.trip_preflight_recommended),
            footer = stringResource(R.string.trip_recommended_footer),
        ) {
            // AD-SA7: "all the time" is a separate second step, offered once foreground location is granted.
            if (preflight.location ==
                PermissionState.Granted
            ) {
                row { PermissionRow(PermissionGroup.BACKGROUND_LOCATION) }
            }
            row { SpecialAccessRow(SpecialKind.FullScreenIntent, texts = fullScreenTexts()) }
            row { SpecialAccessRow(SpecialKind.BatteryOptimisation) }
        }
        CapsuleButton(
            stringResource(R.string.trip_start),
            {
                sheet.dismiss()
                onStart()
            },
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(TripTags.PreflightStart),
            style = CapsuleStyle.Filled,
            enabled = preflight.nextStep == null,
            fillWidth = true,
        )
    }
}

private val CARD_SHAPE = RostyShapes.card

/** Stops' full-screen access is for the alarm over the lock screen, not for calls. */
@Composable
private fun fullScreenTexts(): SpecialRowTexts =
    SpecialRowTexts(
        title = stringResource(R.string.trip_full_screen),
        on = stringResource(R.string.trip_full_screen_on),
        off = stringResource(R.string.trip_full_screen_off),
    )

private const val CHIP_ALPHA = 0.18f
private const val MS_PER_S = 1000L
private const val S_PER_MIN = 60
