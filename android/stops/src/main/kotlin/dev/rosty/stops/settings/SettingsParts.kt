package dev.rosty.stops.settings

import android.content.Context
import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosChevron
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.component.IosToggle
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.motion.LocalReduceMotion
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.OverlayController
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsFooter
import dev.rosty.stops.R
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun tile(icon: ImageVector): Painter = rememberVectorPainter(icon)

/** A value with the chevron of a row that opens something; a long value is cut to one line. */
@Composable
internal fun ValueChevron(
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsMetrics.Gap),
    ) {
        if (value.isNotEmpty()) Value(value, modifier.weight(1f, fill = false))
        IosChevron()
    }
}

/** A one-line value that cuts itself short (`trailingShrinks`). */
@Composable
internal fun Value(
    value: String,
    modifier: Modifier = Modifier,
) {
    IosText(
        value,
        modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The trailing slot of an action row: no chevron (it plays, opens a picker or asks). */
@Composable
internal fun NoChevron() = Spacer(Modifier.size(SettingsMetrics.CheckSlot))

/** A footer under a group, with room before the next one. */
@Composable
internal fun Footnote(text: String) {
    SettingsFooter(text)
    Spacer(Modifier.height(SettingsMetrics.AfterFooter))
}

/** A row with a switch; the whole row toggles, the switch is a read-out of [checked]. */
internal fun toggleItem(
    icon: Painter,
    title: String,
    checked: Boolean,
    tag: String,
    description: String? = null,
    onToggle: () -> Unit,
): IosSettingsItem =
    IosSettingsItem(
        icon = icon,
        title = {
            Box(
                Modifier.testTag(tag).semantics {
                    role = Role.Switch
                    selected = checked
                },
            ) { IosText(title) }
        },
        description = description?.let { { IosText(it) } },
        trailingContent = { IosToggle(checked, onCheckedChange = null, exposeSemantics = false) },
        onClick = onToggle,
    )

/** One row of an exclusive choice: a tick on the chosen one, never a chevron. */
internal fun choiceItem(
    title: String,
    chosen: Boolean,
    tag: String,
    onChoose: () -> Unit,
): IosSettingsItem =
    IosSettingsItem(
        title = {
            Box(
                Modifier.testTag(tag).semantics {
                    role = Role.RadioButton
                    selected = chosen
                },
            ) { IosText(title) }
        },
        trailingContent =
            if (chosen) {
                { RostyIcon(RostyIcons.Checkmark, null, tint = IosTheme.tint) }
            } else {
                { NoChevron() }
            },
        onClick = { if (!chosen) onChoose() },
    )

/** A thin determinate bar; the fraction follows a spring (none with reduced motion) and is read in the draw phase. */
@Composable
internal fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (reduce) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "offline-progress",
    )
    val track = IosTheme.colors.tertiarySystemFill
    val fill = IosTheme.tint
    Box(
        modifier
            .fillMaxWidth()
            .height(SettingsMetrics.ProgressHeight)
            .clip(RoundedCornerShape(SettingsMetrics.ProgressHeight))
            .background(track)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .drawBehind {
                    val r = size.height / 2
                    drawRoundRect(fill, size = Size(size.width * shown, size.height), cornerRadius = CornerRadius(r, r))
                },
        )
    }
}

/** A confirmation alert: Cancel, and [action] (red when [destructive]) which runs [onConfirm]. */
internal fun OverlayController.confirm(
    resources: Resources,
    heading: String,
    message: String?,
    action: String,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
) {
    present(
        AlertEntry(
            heading = heading,
            message = message,
            actions =
                persistentListOf(
                    AlertAction(resources.getString(R.string.settings_cancel), AlertRole.Cancel),
                    AlertAction(action, if (destructive) AlertRole.Destructive else AlertRole.Default, onConfirm),
                ),
        ),
    )
}

/** A size in the phone's units ("4.2 MB"); zero is "None". */
internal fun bytesText(
    context: Context,
    bytes: Long,
): String =
    if (bytes <=
        0
    ) {
        context.getString(R.string.tiles_size_none)
    } else {
        Formatter.formatShortFileSize(context, bytes)
    }
