package dev.rovsti.contacts.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.contacts.ContactsTags
import dev.rovsti.contacts.R
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosChevron
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.component.IosToggle
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.text.text
import dev.rovsti.shell.settings.CommonSettings
import dev.rovsti.shell.settings.CommonSettingsGroup
import dev.rovsti.shell.settings.CommonSettingsPage
import dev.rovsti.shell.settings.IosSettingsGroup
import dev.rovsti.shell.settings.IosSettingsItem
import dev.rovsti.shell.settings.SettingsFooter
import dev.rovsti.shell.settings.SettingsPage
import dev.rovsti.shell.settings.dev.LocalDevMode
import kotlinx.collections.immutable.persistentListOf

/** Where the rows of the Settings root lead. Every member has a no-op default, so a test names only what it taps. */
@Immutable
class SettingsLinks(
    val openNameFormat: () -> Unit = {},
    val openSortBy: () -> Unit = {},
    val openSaveTo: () -> Unit = {},
    val openSummary: () -> Unit = {},
    val openReminderTime: () -> Unit = {},
    val importFile: () -> Unit = {},
    val exportVcf: () -> Unit = {},
    val exportCsv: () -> Unit = {},
    val importCsv: () -> Unit = {},
    val findDuplicates: () -> Unit = {},
    val cleanUp: () -> Unit = {},
    val scanQr: () -> Unit = {},
    val pasteContact: () -> Unit = {},
    val emergency: () -> Unit = {},
    val trash: () -> Unit = {},
    val whatsApp: () -> Unit = {},
    val openCommon: (CommonSettingsPage) -> Unit = {},
    /** Developer mode's Logs page on the `sync` tag ("Sync Details"). */
    val openSyncDetails: () -> Unit = {},
)

/** Test tags of the Settings pages of Contacts. */
object SettingsTags {
    const val NameFormat = "contacts-settings-name-format"
    const val SortBy = "contacts-settings-sort-by"
    const val SaveTo = "contacts-settings-save-to"
    const val Summary = "contacts-settings-summary"
    const val Reminders = "contacts-settings-reminders"
    const val ReminderTime = "contacts-settings-reminder-time"
    const val ExportVcf = "contacts-settings-export-vcf"
    const val Emergency = "contacts-settings-emergency"
    const val Trash = "contacts-settings-trash"
    const val WhatsApp = "contacts-settings-whatsapp"
    const val PeopleLast = "contacts-settings-people-last"
    const val PeopleSync = "contacts-settings-people-sync"
    const val PeopleStatus = "contacts-settings-people-status"
    const val PeopleContinue = "contacts-settings-people-continue"
    const val PeopleDetails = "contacts-settings-people-details"
    const val PeopleProgress = "contacts-settings-people-progress"

    fun choice(index: Int): String = "contacts-settings-choice-$index"
}

@Composable
private fun Tile(icon: ImageVector) = rememberVectorPainter(icon)

@Composable
private fun ValueChevron(value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsMetrics.Gap),
    ) {
        if (value.isNotEmpty()) {
            // A long value (an account) is cut, so the row's title keeps its line at large font sizes.
            IosText(
                value,
                Modifier.widthIn(max = SettingsMetrics.ValueMax),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IosChevron()
    }
}

/** An action row opens a picker or runs at once, so its slot holds no chevron. */
@Composable
private fun NoChevron() = Spacer(Modifier.size(SettingsMetrics.CheckSlot))

private fun nameFormatName(value: NameFormat) =
    when (value) {
        NameFormat.FirstLast -> R.string.settings_format_first_last
        NameFormat.LastFirst -> R.string.settings_format_last_first
    }

private fun sortName(value: SortBy) =
    when (value) {
        SortBy.FirstName -> R.string.settings_sort_first
        SortBy.LastName -> R.string.settings_sort_last
    }

/**
 * The Settings tab root (E4.15). The groups, top to bottom: Display, New Contacts (when there is more than one
 * account), the Contacts summary, Birthdays, Contacts File, Tools, WhatsApp, then the rows every app shares.
 */
@Composable
fun ContactsSettingsPage(
    state: ContactsSettingsState,
    settings: CommonSettings,
    links: SettingsLinks,
    onAction: (SettingsAction) -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.tab_settings),
        modifier = Modifier.testTag(ContactsTags.SettingsPage),
    ) {
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_display),
            items =
                listOf(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Person),
                        title = {
                            IosText(
                                stringResource(R.string.settings_name_format),
                                Modifier.testTag(SettingsTags.NameFormat),
                            )
                        },
                        trailingContent = { ValueChevron(stringResource(nameFormatName(state.nameFormat))) },
                        onClick = links.openNameFormat,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Reorder),
                        title = {
                            IosText(
                                stringResource(R.string.settings_sort_by),
                                Modifier.testTag(SettingsTags.SortBy),
                            )
                        },
                        trailingContent = { ValueChevron(stringResource(sortName(state.sortBy))) },
                        onClick = links.openSortBy,
                    ),
                ),
        )
        if (state.showSaveTo) {
            IosSettingsGroup(
                title = stringResource(R.string.settings_group_new_contacts),
                items =
                    listOf(
                        IosSettingsItem(
                            icon = Tile(RovstiIcons.PersonAdd),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_save_to),
                                    Modifier.testTag(SettingsTags.SaveTo),
                                )
                            },
                            trailingContent = { ValueChevron(state.saveToLabel) },
                            onClick = links.openSaveTo,
                        ),
                    ),
            )
        }
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_summary),
            items =
                listOf(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.People),
                        title = {
                            IosText(
                                stringResource(R.string.settings_summary),
                                Modifier.testTag(SettingsTags.Summary),
                            )
                        },
                        trailingContent = { ValueChevron(state.summaryText) },
                        onClick = links.openSummary,
                    ),
                ),
        )
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_birthdays),
            items =
                listOfNotNull(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.CalendarPlus),
                        title = {
                            Box(
                                Modifier.testTag(SettingsTags.Reminders).semantics {
                                    role = Role.Switch
                                    selected = state.reminders
                                },
                            ) { IosText(stringResource(R.string.settings_reminders)) }
                        },
                        trailingContent = {
                            IosToggle(
                                state.reminders,
                                onCheckedChange = null,
                                exposeSemantics = false,
                            )
                        },
                        onClick = { onAction(SettingsAction.SetReminders(!state.reminders)) },
                    ),
                    if (state.reminders) {
                        IosSettingsItem(
                            icon = Tile(RovstiIcons.Clock),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_reminder_time),
                                    Modifier.testTag(SettingsTags.ReminderTime),
                                )
                            },
                            trailingContent = { ValueChevron(state.hourLabel) },
                            onClick = links.openReminderTime,
                        )
                    } else {
                        null
                    },
                ),
        )
        SettingsFootnote(stringResource(R.string.settings_reminders_footer))
        IosSettingsGroup(
            title = stringResource(R.string.settings_contacts_file),
            items =
                listOf(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Download),
                        title = {
                            IosText(
                                stringResource(R.string.settings_import_file),
                                Modifier.testTag(ContactsTags.ImportFile),
                            )
                        },
                        trailingContent = { NoChevron() },
                        onClick = links.importFile,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Share),
                        title = {
                            IosText(
                                stringResource(R.string.settings_export_vcf),
                                Modifier.testTag(SettingsTags.ExportVcf),
                            )
                        },
                        trailingContent = { NoChevron() },
                        enabled = state.hasContacts,
                        onClick = links.exportVcf,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Document),
                        title = {
                            IosText(
                                stringResource(R.string.settings_export_csv),
                                Modifier.testTag(ContactsTags.ExportCsv),
                            )
                        },
                        trailingContent = { NoChevron() },
                        enabled = state.hasContacts,
                        onClick = links.exportCsv,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Download),
                        title = {
                            IosText(
                                stringResource(R.string.settings_import_csv),
                                Modifier.testTag(ContactsTags.ImportCsv),
                            )
                        },
                        trailingContent = { NoChevron() },
                        onClick = links.importCsv,
                    ),
                ),
        )
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_tools),
            items =
                listOf(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.People),
                        title = {
                            IosText(
                                stringResource(R.string.settings_find_duplicates),
                                Modifier.testTag(ContactsTags.FindDuplicates),
                            )
                        },
                        onClick = links.findDuplicates,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Checkmark),
                        title = {
                            IosText(
                                stringResource(R.string.settings_clean_up),
                                Modifier.testTag(ContactsTags.CleanUp),
                            )
                        },
                        onClick = links.cleanUp,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.QrCode),
                        title = {
                            IosText(
                                stringResource(R.string.settings_scan_qr),
                                Modifier.testTag(ContactsTags.ScanQr),
                            )
                        },
                        trailingContent = { NoChevron() },
                        onClick = links.scanQr,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Copy),
                        title = {
                            IosText(
                                stringResource(R.string.settings_paste_contact),
                                Modifier.testTag(ContactsTags.PasteContact),
                            )
                        },
                        onClick = links.pasteContact,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.MedicalCross),
                        title = {
                            IosText(
                                stringResource(R.string.settings_emergency),
                                Modifier.testTag(SettingsTags.Emergency),
                            )
                        },
                        onClick = links.emergency,
                    ),
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Trash),
                        title = {
                            IosText(
                                stringResource(R.string.settings_trash),
                                Modifier.testTag(SettingsTags.Trash),
                            )
                        },
                        onClick = links.trash,
                    ),
                ),
        )
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.WhatsApp),
                        title = {
                            IosText(
                                stringResource(R.string.settings_whatsapp),
                                Modifier.testTag(SettingsTags.WhatsApp),
                            )
                        },
                        onClick = links.whatsApp,
                    ),
                ),
        )
        state.peopleSync?.let { PeopleSyncGroup(it, onAction, links.openSyncDetails) }
        CommonSettingsGroup(settings, links.openCommon)
    }
}

/**
 * "WhatsApp Names and Photos" (X.1b, people sync v2 section 9): the status (with a progress bar while a run is active),
 * "Continue Where It Left Off" while a run is resumable, "Sync Now" (it is "Stop" while running and asks "Start over?"
 * first when a run is resumable), and in developer mode "Sync Details" (the Logs page on the `sync` tag). Only while
 * WhatsApp is linked. The rows keep their places as the state changes, so nothing jumps.
 */
@Composable
internal fun PeopleSyncGroup(
    sync: PeopleSyncUi,
    onAction: (SettingsAction) -> Unit,
    onDetails: () -> Unit = {},
) {
    val overlay = LocalOverlayOrNull.current
    val devMode = LocalDevMode.current
    val developer = devMode?.enabled?.collectAsState()?.value == true
    val title = stringResource(R.string.settings_people_restart_title)
    val body = stringResource(R.string.settings_people_restart_body)
    val restart = stringResource(R.string.settings_people_restart)
    val cancel = stringResource(R.string.settings_people_restart_cancel)
    val items =
        buildList {
            add(
                IosSettingsItem(
                    icon = Tile(RovstiIcons.Photo),
                    title = {
                        IosText(
                            stringResource(R.string.settings_people_last_sync),
                            Modifier.testTag(SettingsTags.PeopleLast),
                        )
                    },
                    description = {
                        Column(verticalArrangement = Arrangement.spacedBy(SettingsMetrics.ProgressGap)) {
                            IosText(sync.last.text(), Modifier.testTag(SettingsTags.PeopleStatus))
                            sync.progress?.let { SyncProgressBar(it) }
                        }
                    },
                ),
            )
            if (sync.canContinue) {
                add(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Forward),
                        title = {
                            IosText(
                                stringResource(R.string.settings_people_continue),
                                Modifier.testTag(SettingsTags.PeopleContinue),
                                color = if (sync.continueEnabled) IosTheme.tint else Color.Unspecified,
                            )
                        },
                        trailingContent = { NoChevron() },
                        enabled = sync.continueEnabled && !sync.running,
                        onClick = { onAction(SettingsAction.ContinuePeople) },
                    ),
                )
            }
            add(
                IosSettingsItem(
                    icon = Tile(if (sync.running) RovstiIcons.Pause else RovstiIcons.Rotate),
                    title = {
                        IosText(
                            stringResource(
                                if (sync.running) R.string.settings_people_stop else R.string.settings_people_sync_now,
                            ),
                            Modifier.testTag(SettingsTags.PeopleSync),
                        )
                    },
                    trailingContent = { NoChevron() },
                    enabled = sync.running || sync.syncEnabled,
                    onClick = {
                        when {
                            sync.running -> {
                                onAction(SettingsAction.StopPeople)
                            }

                            sync.confirmRestart && overlay != null -> {
                                overlay.present(
                                    AlertEntry(
                                        heading = title,
                                        message = body,
                                        actions =
                                            persistentListOf(
                                                AlertAction(cancel, AlertRole.Cancel),
                                                AlertAction(restart, AlertRole.Default) {
                                                    onAction(SettingsAction.SyncPeopleNow)
                                                },
                                            ),
                                    ),
                                )
                            }

                            else -> {
                                onAction(SettingsAction.SyncPeopleNow)
                            }
                        }
                    },
                ),
            )
            if (developer) {
                add(
                    IosSettingsItem(
                        icon = Tile(RovstiIcons.Document),
                        title = {
                            IosText(
                                stringResource(R.string.settings_people_details),
                                Modifier.testTag(SettingsTags.PeopleDetails),
                            )
                        },
                        onClick = onDetails,
                    ),
                )
            }
        }
    IosSettingsGroup(title = stringResource(R.string.settings_people_title), items = items)
    SettingsFootnote(stringResource(R.string.settings_people_footer))
}

/** A thin determinate bar; the fraction follows a spring (none with reduced motion), so steps never jump. */
@Composable
private fun SyncProgressBar(fraction: Float) {
    val reduce = LocalReduceMotion.current
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (reduce) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "sync-progress",
    )
    val track = IosTheme.colors.tertiarySystemFill
    val fill = IosTheme.tint
    Box(
        Modifier
            .fillMaxWidth()
            .height(SettingsMetrics.ProgressHeight)
            .clip(RoundedCornerShape(SettingsMetrics.ProgressHeight))
            .background(track)
            .testTag(SettingsTags.PeopleProgress)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .clip(RoundedCornerShape(SettingsMetrics.ProgressHeight))
                .background(fill),
        )
    }
}

/** The footer under a group, in the same inset as the group titles. */
@Composable
private fun SettingsFootnote(text: String) {
    SettingsFooter(text)
    Spacer(Modifier.height(SettingsMetrics.AfterFooter))
}

/** One choice of a [ChoicePage]. */
@Immutable
data class ChoiceOption<T>(
    val value: T,
    val label: String,
)

/** A check-marked list of exclusive choices (Name Format, Sort By, Save To, the reminder time). */
@Composable
fun <T> ChoicePage(
    title: String,
    options: List<ChoiceOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    footer: String? = null,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsPage(title = title, onBack = onBack, onBackLongClick = onBackLongClick) {
        IosSettingsGroup(
            items =
                options.mapIndexed { index, option ->
                    val chosen = option.value == selected
                    IosSettingsItem(
                        title = {
                            Box(
                                Modifier.testTag(SettingsTags.choice(index)).semantics {
                                    role = Role.RadioButton
                                    this.selected = chosen
                                },
                            ) { IosText(option.label) }
                        },
                        trailingContent =
                            if (chosen) {
                                { RovstiIcon(RovstiIcons.Checkmark, null, tint = IosTheme.tint) }
                            } else {
                                { NoChevron() }
                            },
                        onClick = { if (!chosen) onSelect(option.value) },
                    )
                },
        )
        footer?.let { SettingsFootnote(it) }
    }
}

/** The counts `contacts.stats` words (C29): the totals, then one row per account. */
@Composable
fun SummaryPage(
    summary: SummaryUi,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsPage(
        title = stringResource(R.string.settings_summary),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        modifier = Modifier.testTag(SettingsTags.Summary + "-page"),
    ) {
        if (summary.rows.isEmpty()) {
            SettingsFootnote(stringResource(R.string.settings_summary_empty))
            return@SettingsPage
        }
        IosSettingsGroup(
            items =
                summary.rows.map { row ->
                    IosSettingsItem(
                        title = { IosText(row.label) },
                        trailingContent = { IosText(row.value) },
                    )
                },
        )
        if (summary.accounts.isNotEmpty()) {
            IosSettingsGroup(
                title = stringResource(R.string.settings_summary_accounts),
                items =
                    summary.accounts.map { account ->
                        IosSettingsItem(
                            title = { IosText(account.label) },
                            trailingContent = { IosText(account.count.toString()) },
                        )
                    },
            )
        }
    }
}

internal fun nameFormatOptions(labels: (NameFormat) -> String) = NameFormat.entries.map { ChoiceOption(it, labels(it)) }

internal fun sortOptions(labels: (SortBy) -> String) = SortBy.entries.map { ChoiceOption(it, labels(it)) }
