package dev.rosty.messages.spam

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.resolve
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.settings.findComponentActivity
import kotlinx.collections.immutable.persistentListOf

/** The Spam filter page's view model, kept by the activity. */
@Composable
fun rememberSpamSettingsViewModel(container: MessagesContainer): SpamSettingsViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    SpamSettingsViewModel(
                        SpamSources(
                            actions = container.spamActions,
                            prefs = container.store.prefs,
                            blocked = container.blocklist.numbers(),
                            cleanup = container.codeCleanup,
                        ),
                    )
                }
            }
        ViewModelProvider(owner, factory)[SpamSettingsViewModel::class.java]
    }
}

/** Settings, Spam filter: [SpamSettingsPage] over its view model, the editor sheets, the M47 confirm and the pills. */
@Composable
fun SpamSettingsEntry(
    container: MessagesContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val model = rememberSpamSettingsViewModel(container)
    val ui by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    val res = LocalResources.current
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            when (effect) {
                is SpamSettingsEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is SpamSettingsEffect.Undo -> {
                    overlays.showUndo(effect.text.resolve(context), effect.undo, res.getString(R.string.inbox_undo))
                }
            }
        }
    }
    val latest = rememberUpdatedState(ui)
    val links =
        remember(model, overlays, res) {
            SpamPageLinks(
                onAction = model::onAction,
                editRule = { rule -> presentRuleEditor(overlays, res, rule, model::onAction) },
                addAllowed = { presentAllowEditor(overlays, res, model::onAction) },
                blockNumber = { presentBlockEditor(overlays, res, model::onAction) },
                setDeleteCodes = { on ->
                    if (on && !latest.value.deleteCodes) {
                        confirmDeleteCodes(overlays, res) { model.onAction(SpamSettingsAction.SetDeleteCodes(true)) }
                    } else {
                        model.onAction(SpamSettingsAction.SetDeleteCodes(on))
                    }
                },
            )
        }
    SpamSettingsPage(ui, links, onBack = onBack, onBackLongClick = onBackLongClick)
}

/** The rule sheet: [rule] to edit, or null for a new one. */
internal fun presentRuleEditor(
    overlays: OverlayController,
    res: Resources,
    rule: RuleRow?,
    onAction: (SpamSettingsAction) -> Unit,
) {
    val title = res.getString(if (rule == null) R.string.spam_rule_new else R.string.spam_rule_edit)
    overlays.present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            RuleEditorContent(
                initial = rule,
                onSave = { kind, text, done ->
                    onAction(
                        SpamSettingsAction.SaveRule(rule?.index, kind, text) { problem ->
                            done(problem)
                            if (problem == null) sheet.dismiss()
                        },
                    )
                },
                onCancel = { sheet.dismiss() },
                onDelete =
                    rule?.let {
                        {
                            onAction(SpamSettingsAction.DeleteRule(it.index))
                            sheet.dismiss()
                        }
                    },
            )
        },
    )
}

internal fun presentAllowEditor(
    overlays: OverlayController,
    res: Resources,
    onAction: (SpamSettingsAction) -> Unit,
) {
    val title = res.getString(R.string.spam_add_allow)
    overlays.present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            SenderEditorContent(
                title = title,
                placeholder = res.getString(R.string.spam_field_allow),
                onSave = { text, done ->
                    onAction(
                        SpamSettingsAction.Allow(text) { added ->
                            if (added) {
                                done(null)
                                sheet.dismiss()
                            } else {
                                done(res.getString(R.string.spam_allow_exists))
                            }
                        },
                    )
                },
                onCancel = { sheet.dismiss() },
            )
        },
    )
}

internal fun presentBlockEditor(
    overlays: OverlayController,
    res: Resources,
    onAction: (SpamSettingsAction) -> Unit,
) {
    val title = res.getString(R.string.spam_block_number)
    overlays.present(
        SheetEntry(title = title, detents = persistentListOf(Detent.Large)) { sheet ->
            SenderEditorContent(
                title = title,
                placeholder = res.getString(R.string.spam_field_block),
                phone = true,
                onSave = { text, done ->
                    onAction(
                        SpamSettingsAction.Block(text) { outcome ->
                            when (outcome) {
                                BlockOutcome.Done -> {
                                    done(null)
                                    sheet.dismiss()
                                }

                                BlockOutcome.Refused -> {
                                    done(res.getString(R.string.spam_block_refused))
                                }

                                BlockOutcome.Unavailable -> {
                                    done(res.getString(R.string.spam_block_failed))
                                }
                            }
                        },
                    )
                },
                onCancel = { sheet.dismiss() },
            )
        },
    )
}

/** M47 asks before it is turned on: deleted messages do not come back. */
internal fun confirmDeleteCodes(
    overlays: OverlayController,
    res: Resources,
    onConfirm: () -> Unit,
) {
    overlays.present(
        AlertEntry(
            heading = res.getString(R.string.spam_delete_codes_confirm_title),
            message = res.getString(R.string.spam_delete_codes_confirm_message),
            actions =
                persistentListOf(
                    AlertAction(res.getString(R.string.inbox_cancel), AlertRole.Cancel),
                    AlertAction(res.getString(R.string.spam_turn_on), AlertRole.Destructive, onConfirm),
                ),
        ),
    )
}
