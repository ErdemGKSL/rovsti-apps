package dev.rovsti.phone.settings.speeddial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.controls.GlassIconButton
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.list.CustomAction
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.R
import dev.rovsti.phone.settings.ActionRow
import dev.rovsti.phone.settings.GroupFooter
import dev.rovsti.phone.settings.NoGutter
import dev.rovsti.shell.settings.SettingsPage
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Speed dial page. */
object SpeedDialTags {
    const val Page = "speed-dial-page"
    const val Share = "speed-dial-share"
    const val ClearAll = "speed-dial-clear-all"

    fun row(digit: Int) = "speed-dial-row-$digit"
}

/**
 * Speed dial (E5.9b): keys 1 to 9 in one card. Key 1 is the voicemail and cannot change; a tap on a key picks a person
 * ([onPick] opens the picker), a swipe clears an assigned key. The share button in the top bar sends the list as text
 * (U22); Clear all sits under the card. Stateless.
 */
@Composable
fun SpeedDialScreen(
    ui: SpeedDialUi,
    onAction: (SpeedDialAction) -> Unit,
    onPick: (digit: Int) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsPage(
        title = stringResource(R.string.sd_title),
        modifier = modifier.testTag(SpeedDialTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        horizontalPadding = NoGutter,
        actions = {
            GlassIconButton(
                icon = RovstiIcons.Share,
                contentDescription = stringResource(R.string.sd_share),
                onClick = onShare,
                modifier = Modifier.testTag(SpeedDialTags.Share),
            )
        },
    ) {
        InsetGroupedSection {
            for (key in ui.rows) {
                row { KeyRow(key, onAction, onPick) }
            }
        }
        GroupFooter(stringResource(R.string.sd_footer), Modifier.padding(horizontal = Spacing.gutter))
        if (ui.assignedCount > 0) {
            InsetGroupedSection(Modifier.padding(top = Spacing.l)) {
                row {
                    ActionRow(
                        stringResource(R.string.sd_clear_all),
                        { onAction(SpeedDialAction.ClearAll) },
                        SpeedDialTags.ClearAll,
                        destructive = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    row: SpeedDialRow,
    onAction: (SpeedDialAction) -> Unit,
    onPick: (Int) -> Unit,
) {
    val colors = RovstiTheme.colors
    val clear = stringResource(R.string.sd_clear)
    val title =
        when {
            row.voicemail -> stringResource(R.string.sd_voicemail)
            row.assigned -> row.name.orEmpty()
            else -> stringResource(R.string.set_empty)
        }
    val actions =
        remember(row.digit, row.assigned, clear) {
            if (row.assigned) {
                persistentListOf(CustomAction(clear) { onAction(SpeedDialAction.Clear(row.digit)) })
            } else {
                persistentListOf()
            }
        }
    val swipe =
        if (row.assigned) {
            persistentListOf(SwipeAction(clear, colors.destructive) { onAction(SpeedDialAction.Clear(row.digit)) })
        } else {
            persistentListOf()
        }
    SwipeRow(trailing = swipe, rowKey = row.digit) {
        Box(Modifier.background(colors.groupedSurface)) {
            ListRow(
                title = title,
                secondary = row.number?.takeIf { row.assigned },
                leading = { Digit(row.digit, row.assigned || row.voicemail) },
                trailing = if (row.voicemail) null else ({ ListRowChevron() }),
                titleColor = if (row.assigned || row.voicemail) colors.labelPrimary else colors.labelSecondary,
                onClick = if (row.voicemail) null else ({ onPick(row.digit) }),
                actions = actions,
                modifier = Modifier.testTag(SpeedDialTags.row(row.digit)),
            )
        }
    }
}

@Composable
private fun Digit(
    digit: Int,
    filled: Boolean,
) {
    val colors = RovstiTheme.colors
    Box(
        Modifier
            .size(Sizes.avatar)
            .clip(CircleShape)
            .background(if (filled) colors.accent else colors.fillSecondary),
        contentAlignment = Alignment.Center,
    ) {
        if (digit == SpeedDialRow.VOICEMAIL_DIGIT) {
            RovstiIcon(RovstiIcons.Voicemail, null, tint = colors.onAccent, size = Spacing.xl)
        } else {
            IosText(
                digit.toString(),
                color = if (filled) colors.onAccent else colors.labelSecondary,
            )
        }
    }
}

/** The list as text for the share sheet (U22): one line per assigned key, the voicemail first. */
fun speedDialShareText(
    title: String,
    voicemail: String,
    ui: SpeedDialUi,
): String =
    buildString {
        append(title)
        for (row in ui.rows) {
            val label = if (row.voicemail) voicemail else row.name
            if (label == null) continue
            append('\n').append(row.digit).append("  ").append(label)
            if (!row.voicemail && row.number != null && row.number != row.name) append("  ").append(row.number)
        }
    }
