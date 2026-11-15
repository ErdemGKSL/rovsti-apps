package dev.rosty.stops.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.stops.NoticePrefs
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.notice.NoticeTags
import dev.rosty.stops.notice.linkText
import dev.rosty.wa.WaLinkState

/**
 * The WhatsApp group of the Settings page (E7.10, AD-ST7), drawn by [StopsSettingsPage] right after the Trip group
 * through its `extraGroups` slot: the arriving-soon message (to whom, or off) and the WhatsApp link. Both rows push a
 * page of `dev.rosty.stops.notice` through [onOpen].
 */
@Suppress("UnusedReceiverParameter")
@Composable
fun ColumnScope.ArrivingSoonSettingsGroup(
    container: StopsContainer,
    onOpen: (StopsRoute) -> Unit,
) {
    val prefs by container.store.prefs.collectAsState()
    val link by container.wa.linkState.collectAsState()
    val settled by container.wa.settled.collectAsState()
    ArrivingSoonGroup(prefs.notice, link, settled, onOpen)
}

/** The group as a pure function of what it shows (tests and screenshots). */
@Composable
fun ArrivingSoonGroup(
    notice: NoticePrefs?,
    link: WaLinkState,
    settled: Boolean,
    onOpen: (StopsRoute) -> Unit,
) {
    val to = notice?.chatName?.takeIf { notice.chat.isNotEmpty() }
    IosSettingsGroup(
        title = stringResource(R.string.notice_group),
        items =
            listOf(
                IosSettingsItem(
                    icon = tile(RostyIcons.Message),
                    title = {
                        IosText(
                            stringResource(R.string.notice_title),
                            Modifier.testTag(NoticeTags.SettingsRow),
                        )
                    },
                    description = {
                        IosText(
                            when {
                                to != null -> stringResource(R.string.notice_row_to, to)
                                link is WaLinkState.Connected || !settled -> stringResource(R.string.notice_row_off)
                                else -> stringResource(R.string.notice_row_needs_whatsapp)
                            },
                        )
                    },
                    trailingContent = { ValueChevron("") },
                    onClick = { onOpen(StopsRoute.ArrivingSoon) },
                ),
                IosSettingsItem(
                    icon = tile(RostyIcons.WhatsApp),
                    title = {
                        IosText(stringResource(R.string.notice_whatsapp), Modifier.testTag(NoticeTags.SettingsWhatsApp))
                    },
                    trailingContent = { ValueChevron(linkText(link, settled)) },
                    trailingShrinks = true,
                    onClick = { onOpen(StopsRoute.WhatsApp) },
                ),
            ),
    )
    Footnote(stringResource(R.string.notice_group_footer))
}
