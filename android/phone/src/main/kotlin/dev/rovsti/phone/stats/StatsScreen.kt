package dev.rovsti.phone.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rovsti.core.model.phone.StatDir
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.controls.SegmentedControl
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.sheetScrollConnection
import dev.rovsti.design.text.rtlSafe
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.PhoneMetrics
import dev.rovsti.phone.R
import dev.rovsti.phone.STACK_FONT_SCALE
import dev.rovsti.phone.StatsMetrics
import dev.rovsti.phone.StatsMotion
import dev.rovsti.phone.people.PhoneAvatar
import dev.rovsti.shell.settings.IosSettingsGroup
import dev.rovsti.shell.settings.IosSettingsItem
import dev.rovsti.shell.settings.SettingsPage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

/** Test tags of the Statistics page. */
object StatsTags {
    const val Page = "stats-page"
    const val Range = "stats-range"
    const val Cells = "stats-cells"
    const val Chart = "stats-chart"
    const val Empty = "stats-empty"
    const val Share = "stats-share"
    const val Export = "stats-export"
    const val Bucket = "stats-bucket"
    const val Busiest = "stats-record-busiest"
    const val Longest = "stats-record-longest"

    fun bar(index: Int) = "stats-bar-$index"

    fun person(key: String) = "stats-person-$key"

    fun line(index: Int) = "stats-line-$index"
}

/** How far a bar has grown, 0..1: a test reads it to see the bars at full height in the first frame. */
val StatsBarFill = SemanticsPropertyKey<Float>("StatsBarFill")

private var SemanticsPropertyReceiver.statsBarFill by StatsBarFill

/** What the page needs from Android: the share sheet and the CSV picker. */
interface StatsHost {
    fun share(text: String)

    fun saveCsv(
        fileName: String,
        csv: String,
    )
}

/**
 * The Statistics page (E5.10), stateless: the range control, the count cells, the bar chart, the people, the records and
 * the two exports. Bars grow on a staggered spring; with reduce motion they are at full height in the first frame.
 */
@Composable
fun StatsScreen(
    state: StatsUi,
    onAction: (StatsAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    // Large type: the short names fit the three slots of the control.
    val short = LocalDensity.current.fontScale > STACK_FONT_SCALE
    val ranges =
        persistentListOf(
            stringResource(if (short) R.string.stats_range_week_short else R.string.stats_range_week),
            stringResource(if (short) R.string.stats_range_days7_short else R.string.stats_range_days7),
            stringResource(if (short) R.string.stats_range_weeks8_short else R.string.stats_range_weeks8),
        )
    SettingsPage(
        title = stringResource(R.string.stats_title),
        modifier = modifier.testTag(StatsTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        SegmentedControl(
            items = ranges,
            selected = state.rangeIndex,
            onSelect = { onAction(StatsAction.SelectRange(it)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.l).testTag(StatsTags.Range),
        )
        val cells = state.cells
        when {
            !state.ready || cells == null -> {
                Unit
            }

            state.empty -> {
                StatsEmpty()
            }

            else -> {
                CellsCard(cells)
                ChartCard(state, onAction)
                PeopleGroup(state, onAction)
                RecordsGroup(state)
                ActionsGroup(onAction)
            }
        }
    }
}

@Composable
private fun StatsEmpty() {
    Box(
        Modifier.fillMaxWidth().padding(top = Spacing.xl).testTag(StatsTags.Empty),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = RovstiIcons.Clock,
            title = stringResource(R.string.stats_empty_title),
            message = stringResource(R.string.stats_empty_message),
        )
    }
}

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xl)
            .clip(RoundedCornerShape(PhoneMetrics.CardRadius))
            .background(IosTheme.colors.secondarySystemGroupedBackground),
        content = content,
    )
}

// ---- count cells ----

@Composable
private fun CellsCard(cells: StatsCellsUi) {
    val colors = RovstiTheme.colors
    val stack = LocalDensity.current.fontScale > STACK_FONT_SCALE
    Card(Modifier.testTag(StatsTags.Cells)) {
        val calls = stringResource(R.string.stats_cell_calls)
        val talk = stringResource(R.string.stats_cell_talk)
        val incoming = stringResource(R.string.stats_cell_incoming)
        val outgoing = stringResource(R.string.stats_cell_outgoing)
        val missed = stringResource(R.string.stats_cell_missed)
        if (stack) {
            // Large type: one line per figure, label left, value right.
            val rows =
                listOf(
                    calls to cells.calls.toString(),
                    talk to cells.talk,
                    incoming to cells.incoming.toString(),
                    outgoing to cells.outgoing.toString(),
                    missed to cells.missed.toString(),
                )
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) RowSeparator(inset = Spacing.l)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.l, vertical = Spacing.m)
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RovstiText(label, Modifier.weight(1f), style = RovstiTheme.type.body, color = colors.labelSecondary)
                    RovstiText(
                        value,
                        style = RovstiTheme.type.title3,
                        color = if (label == missed && cells.missed > 0) colors.destructive else colors.labelPrimary,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Cell(cells.calls.toString(), calls, Modifier.weight(1f))
                Cell(cells.talk, talk, Modifier.weight(1f))
            }
            RowSeparator(inset = Spacing.l)
            Row(Modifier.fillMaxWidth()) {
                Cell(cells.incoming.toString(), incoming, Modifier.weight(1f))
                Cell(cells.outgoing.toString(), outgoing, Modifier.weight(1f))
                Cell(
                    cells.missed.toString(),
                    missed,
                    Modifier.weight(1f),
                    valueColor = if (cells.missed > 0) colors.destructive else colors.labelPrimary,
                )
            }
        }
    }
}

@Composable
private fun Cell(
    value: String,
    label: String,
    modifier: Modifier,
    valueColor: androidx.compose.ui.graphics.Color = RovstiTheme.colors.labelPrimary,
) {
    Column(
        modifier.padding(horizontal = Spacing.l, vertical = Spacing.m).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.textGap),
    ) {
        RovstiText(
            value,
            style = RovstiTheme.type.title2,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RovstiText(
            label,
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- chart ----

@Composable
private fun ChartCard(
    state: StatsUi,
    onAction: (StatsAction) -> Unit,
) {
    val colors = RovstiTheme.colors
    Card(Modifier.testTag(StatsTags.Chart)) {
        RovstiText(
            stringResource(if (state.weekly) R.string.stats_chart_weeks else R.string.stats_chart_days),
            Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.l).semantics { heading() },
            style = RovstiTheme.type.subheadline,
            color = colors.labelSecondary,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(StatsMetrics.ChartHeight)
                .padding(horizontal = Spacing.s, vertical = Spacing.m),
        ) {
            for (bar in state.bars) {
                BarCell(
                    bar = bar,
                    weekly = state.weekly,
                    dimmed = state.selected != null && state.selected != bar.index,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = { onAction(StatsAction.TapBar(bar.index)) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
    }
}

/**
 * One bar: a Canvas slot with the count above the bar and the label under it. The bar grows with a spring (0.8 damping,
 * 300 stiffness) after `index * 30 ms`; with reduce motion it is at full height in the first frame and moves by a jump.
 * The slot is a button for a screen reader ("Tuesday, 6 calls") and a 40 dp+ touch target.
 */
@Composable
private fun BarCell(
    bar: StatsBarUi,
    weekly: Boolean,
    dimmed: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val colors = RovstiTheme.colors
    val reduce = LocalReduceMotion.current
    val grow = remember(bar.index, reduce) { Animatable(if (reduce) bar.fraction else 0f) }
    LaunchedEffect(bar.fraction, reduce) {
        if (reduce) {
            grow.snapTo(bar.fraction)
        } else {
            delay(bar.index * StatsMotion.STAGGER_MS)
            grow.animateTo(bar.fraction, spring(StatsMotion.DAMPING, StatsMotion.STIFFNESS))
        }
    }
    val dim by animateFloatAsState(if (dimmed) DIM_ALPHA else 1f, RovstiMotion.select(1f), label = "bar-dim")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) PRESS_ALPHA else 1f, RovstiMotion.press(1f), label = "bar-press")
    val haptics = LocalRovstiHaptics.current
    val name = bar.name ?: stringResource(R.string.stats_week_of, bar.label)
    val described =
        stringResource(
            R.string.stats_bar_desc,
            name,
            pluralStringResource(R.plurals.stats_calls, bar.calls, bar.calls),
        )
    val measurer = rememberTextMeasurer()
    val countStyle = RovstiTheme.type.caption2
    val labelStyle =
        if (LocalDensity.current.fontScale >
            STACK_FONT_SCALE
        ) {
            RovstiTheme.type.caption2
        } else {
            RovstiTheme.type.caption1
        }
    val accent = colors.accent
    val stub = colors.fillSecondary
    val countColor = colors.labelSecondary
    val countText = bar.calls.toString()
    Column(
        modifier
            .semantics {
                contentDescription = described
                role = Role.Button
                statsBarFill = grow.value
            }.clickable(interactionSource = source, indication = null) {
                haptics.tick()
                onTap()
            }.testTag(StatsTags.bar(bar.index)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = dim * press }) {
            val fill = grow.value
            val countHeight =
                measurer
                    .measure(countText, countStyle)
                    .size.height
                    .toFloat()
            val gap = StatsMetrics.CountGap.toPx()
            val room = (size.height - countHeight - gap).coerceAtLeast(0f)
            val width = minOf(size.width * BAR_FILL_OF_SLOT, StatsMetrics.BarMaxWidth.toPx())
            val stubPx = StatsMetrics.BarStub.toPx()
            val height = if (bar.calls == 0) stubPx else maxOf(stubPx, room * fill)
            val left = (size.width - width) / 2f
            val top = size.height - height
            drawRoundRect(
                color = if (bar.calls == 0) stub else accent,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(StatsMetrics.BarRadius.toPx().coerceAtMost(width / 2f)),
            )
            if (bar.calls > 0) {
                val measured =
                    measurer.measure(
                        countText,
                        countStyle.copy(color = countColor.copy(alpha = fill.coerceIn(0f, 1f))),
                    )
                drawText(
                    measured,
                    topLeft =
                        Offset(
                            (size.width - measured.size.width) / 2f,
                            (top - gap - measured.size.height).coerceAtLeast(0f),
                        ),
                )
            }
        }
        RovstiText(
            bar.label,
            Modifier.padding(top = StatsMetrics.LabelGap).clearAndSetSemantics {},
            style = labelStyle,
            color = if (dimmed) colors.labelTertiary else colors.labelSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val BAR_FILL_OF_SLOT = 0.62f
private const val DIM_ALPHA = 0.4f
private const val PRESS_ALPHA = 0.6f

// ---- people, records, exports ----

@Composable
private fun PeopleGroup(
    state: StatsUi,
    onAction: (StatsAction) -> Unit,
) {
    if (state.people.isEmpty()) return
    IosSettingsGroup(
        title = stringResource(R.string.stats_people),
        items =
            state.people.map { person ->
                IosSettingsItem(
                    leadingContent = {
                        PhoneAvatar(
                            person.title,
                            person.number,
                            size = StatsMetrics.PersonAvatar,
                            colorKey = person.number,
                        )
                    },
                    title = {
                        IosText(
                            person.title,
                            Modifier.testTag(StatsTags.person(person.key)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    description = { IosText(person.summary.rtlSafe(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onAction(StatsAction.OpenPerson(person.key)) },
                )
            },
    )
}

@Composable
private fun RecordsGroup(state: StatsUi) {
    val busiest = state.records.busiest
    val longest = state.records.longest
    if (busiest == null && longest == null) return
    val items = ArrayList<IosSettingsItem>()
    if (busiest != null) {
        items +=
            IosSettingsItem(
                icon = rememberVectorPainter(RovstiIcons.Clock),
                title = {
                    IosText(
                        stringResource(
                            if (state.weekly) R.string.stats_record_busiest_week else R.string.stats_record_busiest_day,
                        ),
                        Modifier.testTag(StatsTags.Busiest),
                    )
                },
                description = { IosText("${busiest.title} · ${busiest.detail}".rtlSafe()) },
            )
    }
    if (longest != null) {
        items +=
            IosSettingsItem(
                icon = rememberVectorPainter(RovstiIcons.Phone),
                title = { IosText(stringResource(R.string.stats_record_longest), Modifier.testTag(StatsTags.Longest)) },
                description = { IosText("${longest.title} · ${longest.detail}".rtlSafe()) },
            )
    }
    IosSettingsGroup(title = stringResource(R.string.stats_records), items = items)
}

@Composable
private fun ActionsGroup(onAction: (StatsAction) -> Unit) {
    IosSettingsGroup(
        items =
            listOf(
                IosSettingsItem(
                    icon = rememberVectorPainter(RovstiIcons.Share),
                    title = { IosText(stringResource(R.string.stats_share), Modifier.testTag(StatsTags.Share)) },
                    onClick = { onAction(StatsAction.Share) },
                ),
                IosSettingsItem(
                    icon = rememberVectorPainter(RovstiIcons.Download),
                    title = { IosText(stringResource(R.string.stats_export), Modifier.testTag(StatsTags.Export)) },
                    onClick = { onAction(StatsAction.ExportCsv) },
                ),
            ),
    )
}

// ---- bucket sheet (U46) ----

/** Opens the bucket sheet of a tapped bar: its title and summary, then one row per call; a row opens the number sheet. */
fun presentBucketSheet(
    overlays: OverlayController,
    bucket: BucketUi,
    onOpen: (BucketLineUi) -> Unit,
    onDismissed: () -> Unit,
): SheetEntry {
    val entry =
        SheetEntry(
            title = bucket.title,
            detents = persistentListOf(Detent.Medium, Detent.Large),
            initialDetent = Detent.Medium,
            onDismissed = onDismissed,
        ) { sheet ->
            BucketSheetContent(bucket, sheet, onOpen)
        }
    overlays.present(entry)
    return entry
}

@Composable
internal fun BucketSheetContent(
    bucket: BucketUi,
    sheet: SheetState?,
    onOpen: (BucketLineUi) -> Unit,
) {
    val colors = RovstiTheme.colors
    Column(Modifier.testTag(StatsTags.Bucket)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RovstiText(
                bucket.title,
                Modifier.semantics { heading() },
                style = RovstiTheme.type.headline,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RovstiText(
                bucket.summary.rtlSafe(),
                style = RovstiTheme.type.footnote,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
        LazyColumn(scroll) {
            itemsIndexed(bucket.lines) { index, line ->
                Column {
                    val missed = line.dir == StatDir.Missed
                    ListRow(
                        title = line.title,
                        secondary = line.detail?.rtlSafe(),
                        modifier = Modifier.testTag(StatsTags.line(index)),
                        titleColor = if (missed) colors.destructive else androidx.compose.ui.graphics.Color.Unspecified,
                        leading = {
                            RovstiIcon(
                                if (line.dir == StatDir.Outgoing) RovstiIcons.ArrowUp else RovstiIcons.ArrowDown,
                                null,
                                tint = if (missed) colors.destructive else colors.labelSecondary,
                                size = StatsMetrics.Glyph,
                            )
                        },
                        onClick = { onOpen(line) },
                    )
                    RowSeparator(inset = TextStartWithLeading)
                }
            }
        }
    }
}

private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    list: ImmutableList<T>,
    crossinline row: @Composable (Int, T) -> Unit,
) {
    items(list.size, key = { it }) { row(it, list[it]) }
}

/** [StatsScreen] over its view model: the effects open the bucket sheet, the number sheet, the share sheet and the CSV picker. */
@Composable
fun StatsPage(
    viewModel: StatsViewModel,
    host: StatsHost,
    onOpenNumber: (name: String?, number: String) -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val latestHost by rememberUpdatedState(host)
    val latestOpen by rememberUpdatedState(onOpenNumber)
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is StatsEffect.OpenBucket -> {
                    presentBucketSheet(
                        overlays,
                        effect.bucket,
                        onOpen = { latestOpen(it.name, it.number) },
                        onDismissed = { viewModel.onAction(StatsAction.BucketClosed) },
                    )
                }

                is StatsEffect.OpenNumber -> {
                    latestOpen(effect.name, effect.number)
                }

                is StatsEffect.SaveCsv -> {
                    latestHost.saveCsv(effect.fileName, effect.csv)
                }

                is StatsEffect.ShareText -> {
                    latestHost.share(effect.text)
                }
            }
        }
    }
    StatsScreen(state, viewModel::onAction, onBack = onBack, onBackLongClick = onBackLongClick)
}
