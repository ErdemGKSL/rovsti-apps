package dev.rovsti.phone.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.confirmDestructive
import dev.rovsti.phone.HostedPhone
import dev.rovsti.phone.PhoneHarness
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneSample
import dev.rovsti.phone.settings.backup.BackupPreviewContent
import dev.rovsti.phone.settings.backup.BackupScreen
import dev.rovsti.phone.settings.data.DataScreen
import dev.rovsti.phone.settings.replies.QuickRepliesScreen
import dev.rovsti.phone.settings.replies.ReplyEditContent
import dev.rovsti.phone.settings.sim.SimChoicesScreen
import dev.rovsti.phone.settings.speeddial.SpeedDialScreen
import dev.rovsti.phone.settings.speeddial.SpeedDialTags
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.9b screenshots: Quick replies, its edit sheet, Speed dial, SIM choices, Data, the delete confirm and the Backup preview. */
@Config(sdk = [35])
class E59ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        route: PhoneRoute,
        page: @Composable () -> Unit,
        fontScale: Float = 1f,
        before: (PhoneHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = fontScale) {
                HostedPhone(h, PhoneSample.setup(), push = route, extra = page)
            }
            advance(600)
            before(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun quickReplies() =
        snap("e5.9-quick-replies", PhoneRoute.QuickReplies, { QuickRepliesScreen(SettingsFixtures.replies(), {}, {}) })

    @Test fun quickRepliesAtLargeFont() =
        snap(
            "e5.9-quick-replies-fs2",
            PhoneRoute.QuickReplies,
            { QuickRepliesScreen(SettingsFixtures.replies(), {}, {}) },
            fontScale = 2f,
        )

    @Test fun quickReplyEditSheet() =
        snap("e5.9-quick-reply-edit-sheet", PhoneRoute.QuickReplies, {
            QuickRepliesScreen(SettingsFixtures.replies(), {}, {})
        }) { h ->
            compose.runOnUiThread {
                h.overlays.present(
                    SheetEntry("Edit reply", persistentListOf(Detent.Large)) { _ ->
                        ReplyEditContent(
                            initial = SettingsFixtures.replyTexts[3],
                            maxChars = 100,
                            counterAt = 30,
                            onSave = {},
                            onCancel = {},
                            onDelete = {},
                        )
                    },
                )
            }
            advance(1_400)
        }

    @Test fun speedDial() =
        snap("e5.9-speed-dial", PhoneRoute.SpeedDialPage, { SpeedDialScreen(SettingsFixtures.speedDial(), {}, {}, {}) })

    @Test fun speedDialAtLargeFont() =
        snap(
            "e5.9-speed-dial-fs2",
            PhoneRoute.SpeedDialPage,
            { SpeedDialScreen(SettingsFixtures.speedDial(), {}, {}, {}) },
            fontScale = 2f,
        ) {
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag(SpeedDialTags.Page, useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.ScrollBy,
            ) { it(0f, 100_000f) }
            compose.mainClock.autoAdvance = false
            advance(900)
        }

    @Test fun simChoices() =
        snap("e5.9-sim-choices", PhoneRoute.SimChoices, {
            SimChoicesScreen(SettingsFixtures.sims(), {})
        })

    @Test fun data() = snap("e5.9-data", PhoneRoute.Data, { DataScreen(SettingsFixtures.data(), {}, {}) })

    @Test fun dataDeleteOlder() =
        snap("e5.9-data-delete-older", PhoneRoute.Data, { DataScreen(SettingsFixtures.data(), {}, {}) }) { h ->
            compose.runOnUiThread {
                h.overlays.confirmDestructive(
                    destructiveLabel = "Delete 118 calls",
                    onConfirm = {},
                    title = "Delete calls older than 90 days?",
                    message = "118 calls will be removed from this phone's call log.",
                    cancelLabel = "Cancel",
                )
            }
            advance(900)
        }

    @Test fun dataExportSheet() =
        snap("e5.9-data-export-sheet", PhoneRoute.Data, { DataScreen(SettingsFixtures.data(), {}, {}) }) { h ->
            compose.runOnUiThread {
                h.overlays.present(
                    ActionSheetEntry(
                        title = "rovsti-calls-2026-11-11.csv",
                        actions =
                            persistentListOf(
                                ActionSheetAction("Save as file") {},
                                ActionSheetAction("Share") {},
                            ),
                        cancelLabel = "Cancel",
                    ),
                )
            }
            advance(900)
        }

    @Test fun backupPreview() =
        snap("e5.9-backup-preview", PhoneRoute.Backup, { BackupScreen({}, {}) }) { h ->
            compose.runOnUiThread {
                h.overlays.present(
                    SheetEntry("Restore this backup?", persistentListOf(Detent.Large)) { _ ->
                        BackupPreviewContent("rovsti-phone-backup-2026-11-11.json", SettingsFixtures.preview(), {}, {})
                    },
                )
            }
            advance(1_400)
        }
}
