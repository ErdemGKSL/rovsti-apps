package dev.rovsti.phone.settings.data

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.core.model.phone.PruneAge
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.R
import dev.rovsti.phone.settings.ActionRow
import dev.rovsti.phone.settings.GroupFooter
import dev.rovsti.phone.settings.NoGutter
import dev.rovsti.phone.settings.SavedWhat
import dev.rovsti.phone.settings.SettingsFilesHost
import dev.rovsti.shell.settings.SettingsPage
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Data page. */
object DataTags {
    const val Page = "data-page"
    const val ExportHistory = "data-export-history"
    const val ExportNotes = "data-export-notes"
    const val ExportContacts = "data-export-contacts"
    const val DeleteOlder = "data-delete-older"
    const val DeleteAll = "data-delete-all"
}

/** What the Data page can ask for besides the model's actions: the confirm of "Delete all" is the screen's. */
@Composable
fun DataScreen(
    ui: DataUi,
    onAction: (DataAction) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsPage(
        title = stringResource(R.string.data_title),
        modifier = modifier.testTag(DataTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        horizontalPadding = NoGutter,
    ) {
        InsetGroupedSection(header = stringResource(R.string.data_group_export)) {
            row {
                ExportRow(
                    stringResource(R.string.data_export_history),
                    stringResource(R.string.data_export_history_detail, ui.calls),
                    RovstiIcons.Clock,
                    DataTags.ExportHistory,
                ) { onAction(DataAction.ExportHistory) }
            }
            row {
                ExportRow(
                    stringResource(R.string.data_export_notes),
                    stringResource(R.string.data_export_notes_detail, ui.notes),
                    RovstiIcons.Document,
                    DataTags.ExportNotes,
                ) { onAction(DataAction.ExportNotes) }
            }
            row {
                ExportRow(
                    stringResource(R.string.data_export_contacts),
                    stringResource(R.string.data_export_contacts_detail, ui.contacts),
                    RovstiIcons.People,
                    DataTags.ExportContacts,
                ) { onAction(DataAction.ExportContacts) }
            }
        }
        InsetGroupedSection(Modifier.padding(top = Spacing.l), header = stringResource(R.string.data_group_delete)) {
            row {
                ListRow(
                    title = stringResource(R.string.data_delete_older),
                    trailing = { ListRowChevron() },
                    onClick = { onAction(DataAction.ChooseOlder) },
                    modifier = Modifier.testTag(DataTags.DeleteOlder),
                )
            }
            row {
                ActionRow(
                    stringResource(R.string.data_delete_all),
                    onDeleteAll,
                    DataTags.DeleteAll,
                    destructive = true,
                    enabled = ui.calls > 0 && !ui.deletePending,
                )
            }
        }
        GroupFooter(stringResource(R.string.data_delete_footer), Modifier.padding(horizontal = Spacing.gutter))
    }
}

@Composable
private fun ExportRow(
    title: String,
    detail: String,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        secondary = detail,
        leading = { RovstiIcon(icon, null, tint = RovstiTheme.colors.accent, size = Spacing.xl) },
        trailing = {
            RovstiIcon(
                RovstiIcons.Download,
                null,
                tint = RovstiTheme.colors.labelTertiary,
                size = Spacing.xl,
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/** The pill text of a finished export (U41): it names what was saved. */
@Composable
fun savedMessage(what: SavedWhat): String =
    stringResource(
        when (what) {
            SavedWhat.History -> R.string.data_saved_history
            SavedWhat.Notes -> R.string.data_saved_notes
            SavedWhat.Contacts -> R.string.data_saved_contacts
            SavedWhat.Backup -> R.string.data_saved_backup
        },
    )

/** The Data route: the screen over its view model, the save / share sheet, the chooser, the confirms and the pills. */
@Composable
fun DataEntry(
    model: DataViewModel,
    host: SettingsFilesHost,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val latest = rememberUpdatedState(ui)
    val overlays = LocalOverlay.current
    val resources = LocalContext.current.resources
    val undo = stringResource(R.string.set_undo)
    val cancel = stringResource(R.string.set_cancel)
    LaunchedEffect(model) { model.onAction(DataAction.Recount) }
    LaunchedEffect(model) {
        model.effect.collect { effect ->
            when (effect) {
                is DataEffect.Export -> {
                    val file = effect.file
                    overlays.present(
                        ActionSheetEntry(
                            title = file.fileName,
                            actions =
                                persistentListOf(
                                    ActionSheetAction(resources.getString(R.string.data_save_file)) {
                                        host.save(file.fileName, file.mime, file.text, file.what)
                                    },
                                    ActionSheetAction(
                                        resources.getString(R.string.data_share),
                                    ) { host.share(file.text) },
                                ),
                            cancelLabel = cancel,
                        ),
                    )
                }

                is DataEffect.NothingToExport -> {
                    overlays.showToast(
                        resources.getString(
                            when (effect.what) {
                                SavedWhat.History -> R.string.data_nothing_history
                                SavedWhat.Notes -> R.string.data_nothing_notes
                                SavedWhat.Contacts -> R.string.data_nothing_contacts
                                SavedWhat.Backup -> R.string.data_nothing_backup
                            },
                        ),
                    )
                }

                is DataEffect.OlderChoices -> {
                    overlays.present(
                        ActionSheetEntry(
                            title = resources.getString(R.string.data_older_title),
                            actions =
                                persistentListOf(
                                    *effect.choices
                                        .map { choice ->
                                            ActionSheetAction(
                                                olderLabel(choice) { id, n -> resources.getString(id, n) },
                                            ) {
                                                model.onAction(DataAction.AskOlder(choice.age))
                                            }
                                        }.toTypedArray(),
                                ),
                            cancelLabel = cancel,
                        ),
                    )
                }

                is DataEffect.ConfirmOlder -> {
                    if (effect.plan.count == 0) {
                        overlays.showToast(resources.getString(R.string.data_older_none))
                    } else {
                        overlays.confirmDestructive(
                            destructiveLabel = resources.getString(R.string.data_older_confirm, effect.plan.count),
                            onConfirm = { model.onAction(DataAction.DeleteOlder(effect.age)) },
                            title =
                                resources.getString(
                                    R.string.data_older_ask,
                                    resources.getString(spanOf(effect.age)),
                                ),
                            message = resources.getString(R.string.data_older_body, effect.plan.count),
                            cancelLabel = cancel,
                        )
                    }
                }

                is DataEffect.DeletedOlder -> {
                    overlays.showUndo(
                        resources.getString(R.string.data_older_deleted, effect.count),
                        { model.onAction(DataAction.UndoOlder(effect.rows)) },
                        undo,
                    )
                }

                is DataEffect.DeleteAllPending -> {
                    overlays.showUndo(
                        resources.getString(R.string.data_all_pending),
                        { model.onAction(DataAction.UndoDeleteAll) },
                        undo,
                        durationMs = effect.windowMs,
                    )
                }

                DataEffect.DeletedAll -> {
                    overlays.showToast(resources.getString(R.string.data_all_done))
                }

                DataEffect.Failed -> {
                    overlays.showToast(resources.getString(R.string.data_failed))
                }
            }
        }
    }
    val deleteTitle = stringResource(R.string.data_all_title)
    val deleteLabel = stringResource(R.string.data_delete_all)
    DataScreen(
        ui = ui,
        onAction = model::onAction,
        onDeleteAll = {
            overlays.confirmDestructive(
                destructiveLabel = deleteLabel,
                onConfirm = { model.onAction(DataAction.DeleteAll) },
                title = deleteTitle,
                message = resources.getString(R.string.data_all_message, latest.value.calls),
                cancelLabel = cancel,
            )
        },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

/** The words for an age in the confirm title. */
internal fun spanOf(age: PruneAge): Int =
    when (age) {
        PruneAge.Days30 -> R.string.data_span_30
        PruneAge.Days90 -> R.string.data_span_90
        PruneAge.Year -> R.string.data_span_year
    }

/** The chooser row of one age; [format] fills the resource (kept apart so a test can use plain strings). */
internal fun olderLabel(
    choice: OlderChoice,
    format: (Int, Int) -> String,
): String =
    format(
        when (choice.age) {
            PruneAge.Days30 -> R.string.data_older_30
            PruneAge.Days90 -> R.string.data_older_90
            PruneAge.Year -> R.string.data_older_year
        },
        choice.count,
    )
