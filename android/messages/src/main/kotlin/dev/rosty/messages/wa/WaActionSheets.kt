package dev.rosty.messages.wa

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.core.model.wa.ReceiptSection
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the message-action sheets (E6-WA R4). */
object WaActionSheetTags {
    const val Info = "wa-info-sheet"
    const val InfoProblem = "wa-info-problem"
    const val Timer = "wa-timer-sheet"

    fun section(section: ReceiptSection) = "wa-info-${section.name.lowercase()}"

    fun timer(seconds: Long) = "wa-timer-$seconds"
}

/** The header of a section of message info. */
@Composable
private fun sectionTitle(section: ReceiptSection): String =
    stringResource(
        when (section) {
            ReceiptSection.Played -> R.string.wa_info_played
            ReceiptSection.Read -> R.string.wa_info_read
            ReceiptSection.Delivered -> R.string.wa_info_delivered
            ReceiptSection.Waiting -> R.string.wa_info_waiting
        },
    )

/**
 * Message info (E6-WA R4): who read, played or got one of my messages and when, members who left marked; one row in a
 * one-to-one chat. [info] null and [problem] null: still loading.
 */
@Composable
fun WaInfoContent(
    info: WaInfoUi?,
    problem: WaInfoProblem?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(WaActionSheetTags.Info)) {
        if (problem != null) {
            RostyText(
                stringResource(
                    if (problem == WaInfoProblem.OldGateway) R.string.wa_info_unavailable else R.string.wa_info_failed,
                ),
                modifier = Modifier.padding(Spacing.xl).testTag(WaActionSheetTags.InfoProblem),
                color = RostyTheme.colors.labelSecondary,
            )
            return@Column
        }
        val left = stringResource(R.string.wa_info_left)
        for (section in info?.sections.orEmpty()) {
            InsetGroupedSection(
                Modifier.padding(top = Spacing.sectionGap).testTag(WaActionSheetTags.section(section.section)),
                header = sectionTitle(section.section),
            ) {
                for (row in section.rows) {
                    row {
                        ListRow(
                            title = row.name,
                            secondary = left.takeIf { row.left },
                            trailing =
                                row.time?.let { time ->
                                    {
                                        RostyText(
                                            time,
                                            style = RostyTheme.type.subheadline,
                                            color = RostyTheme.colors.labelSecondary,
                                            maxLines = 1,
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Opens message info of the bubble [key] in a sheet; it loads from the gateway when it opens. */
fun OverlayController.presentWaInfo(
    res: Resources,
    model: WaThreadViewModel,
    key: String,
) {
    present(
        SheetEntry(
            title = res.getString(R.string.wa_info_title),
            detents = persistentListOf(Detent.Medium, Detent.Large),
        ) { _ ->
            var loaded by remember { mutableStateOf<Pair<WaInfoUi?, WaInfoProblem?>>(null to null) }
            LaunchedEffect(model, key) { loaded = model.info(key) }
            WaInfoContent(loaded.first, loaded.second)
        },
    )
}

/** The label of a disappearing timer, as the choices name it. */
fun timerLabel(
    res: Resources,
    seconds: Long,
): String =
    when (seconds) {
        0L -> res.getString(R.string.wa_timer_off)
        WaWords.DAY_S -> res.getString(R.string.wa_timer_day)
        WaWords.WEEK_S -> res.getString(R.string.wa_timer_week)
        WaWords.DAYS_90_S -> res.getString(R.string.wa_timer_90)
        else -> res.getString(R.string.wa_timer_other, seconds.toInt())
    }

/** The choices of the disappearing timer, the current one checked; a tap sets it through the gateway. */
@Composable
fun WaTimerContent(
    res: Resources,
    current: Long,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(WaActionSheetTags.Timer)) {
        InsetGroupedSection(Modifier.padding(top = Spacing.sectionGap)) {
            for (seconds in ChatActionKind.Disappearing.CHOICES) {
                row {
                    ListRow(
                        title = timerLabel(res, seconds),
                        modifier = Modifier.testTag(WaActionSheetTags.timer(seconds)),
                        trailing =
                            if (seconds == current) {
                                { RostyIcon(RostyIcons.Checkmark, null, tint = RostyTheme.colors.accent) }
                            } else {
                                null
                            },
                        onClick = { onPick(seconds) },
                    )
                }
            }
        }
    }
}

/** Opens the timer choices of the thread. */
fun OverlayController.presentWaTimer(
    res: Resources,
    model: WaThreadViewModel,
) {
    val controller = this
    present(
        SheetEntry(title = res.getString(R.string.wa_disappearing), detents = persistentListOf(Detent.Medium)) { _ ->
            WaTimerContent(res, model.timer ?: 0L, onPick = { seconds ->
                controller.dismissAll()
                if (seconds != (model.timer ?: 0L)) model.setTimer(seconds)
            })
        },
    )
}
