package dev.rosty.stops.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.StopsRoute

/**
 * HOOK for the WhatsApp work of E7.10 (AD-ST7): the arriving-soon message group (chat, template preview, when) and the
 * WhatsApp row of the Settings page. [StopsSettingsPage] draws it right after the Trip group, through its
 * `extraGroups` slot; [onOpen] pushes a page over the map (add its route to [StopsRoute] and `chromeFor`). Until that
 * work lands it draws nothing. The preferences it edits are `notice` of `stops-prefs.json`, through
 * `StopsStore.edit` (`PrefsEdit.SetNotice` / `SetNoticeWhen` / `SetTemplate` / `ClearNotice`).
 */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
@Composable
fun ColumnScope.ArrivingSoonSettingsGroup(
    container: StopsContainer,
    onOpen: (StopsRoute) -> Unit,
) = Unit
