package dev.rovsti.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

/** E1.14: alerts, an action sheet and the destructive confirmations; the line under the buttons says what happened. */
@Composable
internal fun DialogsPage() {
    val controller = LocalOverlay.current
    var result by remember { mutableStateOf("Nothing chosen yet") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader("Alerts and sheets", "Answer an alert; a destructive action asks first.")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            CapsuleButton(
                text = "Alert",
                onClick = {
                    controller.present(
                        AlertEntry(
                            heading = "Remove from favourites?",
                            message = "Ayla Demir will no longer appear at the top of Phone.",
                            actions =
                                persistentListOf(
                                    AlertAction("Cancel", AlertRole.Cancel) { result = "Alert: cancelled" },
                                    AlertAction("Remove", AlertRole.Destructive) { result = "Alert: removed" },
                                ),
                        ),
                    )
                },
                modifier = Modifier.testTag("dialog-alert"),
            )
            CapsuleButton(
                text = "Alert, three buttons",
                onClick = {
                    controller.present(
                        AlertEntry(
                            heading = "Call with WhatsApp?",
                            message = "This number is linked to WhatsApp.",
                            actions =
                                persistentListOf(
                                    AlertAction("WhatsApp call") { result = "Alert: WhatsApp" },
                                    AlertAction("Phone call") { result = "Alert: phone" },
                                    AlertAction("Cancel", AlertRole.Cancel) { result = "Alert: cancelled" },
                                ),
                        ),
                    )
                },
                modifier = Modifier.testTag("dialog-alert-3"),
            )
            CapsuleButton(
                text = "Alert without cancel",
                onClick = {
                    controller.present(
                        AlertEntry(
                            heading = "Connect key copied",
                            message = null,
                            actions = persistentListOf(AlertAction("OK") { result = "Alert: ok" }),
                        ),
                    )
                },
                modifier = Modifier.testTag("dialog-alert-ok"),
            )
            CapsuleButton(
                text = "Action sheet",
                onClick = {
                    controller.present(
                        ActionSheetEntry(
                            title = "Ayla Demir",
                            message = "Choose what to do with this contact.",
                            actions =
                                persistentListOf(
                                    ActionSheetAction("Share contact") { result = "Sheet: share" },
                                    ActionSheetAction("Block caller") { result = "Sheet: block" },
                                    ActionSheetAction(
                                        "Delete contact",
                                        destructive = true,
                                    ) { result = "Sheet: delete" },
                                ),
                            onCancel = { result = "Sheet: cancelled" },
                        ),
                    )
                },
                modifier = Modifier.testTag("dialog-sheet"),
            )
            CapsuleButton(
                text = "Delete (confirm)",
                onClick = {
                    controller.confirmDestructive(
                        destructiveLabel = "Delete conversation",
                        title = "Delete this conversation?",
                        message = "It cannot be restored.",
                        onConfirm = { result = "Confirm: deleted" },
                        onCancel = { result = "Confirm: cancelled" },
                    )
                },
                modifier = Modifier.testTag("dialog-confirm"),
            )
            CapsuleButton(
                text = "Delete (two taps)",
                onClick = {
                    controller.confirmDestructive(
                        destructiveLabel = "Delete all history",
                        title = "Clear call history?",
                        twoTap = true,
                        armedLabel = "Tap again to delete",
                        onConfirm = { result = "Two-tap: deleted" },
                        onCancel = { result = "Two-tap: cancelled" },
                    )
                },
                modifier = Modifier.testTag("dialog-two-tap"),
            )
            RovstiText(
                result,
                modifier = Modifier.padding(top = Spacing.s).testTag("dialog-result"),
                style = RovstiTheme.type.footnote,
                color = RovstiTheme.colors.labelSecondary,
            )
        }
    }
}
