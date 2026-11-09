package dev.rovsti.contacts.intake

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.AppFixture
import dev.rovsti.contacts.ContactsTags
import dev.rovsti.contacts.SettingsTab
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.people.presentContactChooser
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.10 screenshots (part 1): the import preview, the contact chooser over a single card, the card, "Nothing to paste". */
@Config(sdk = [35])
class E410ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        harness: () -> IntakeHarness,
        before: (IntakeHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = harness()
            composeFrame(dark = dark) { HostedIntake(h) }
            advance(1_400)
            before(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun importPreview() = snap("e4.10-import-preview", { IntakeHarness(previewState()) })

    @Test fun addToExistingPicker() =
        snap(
            "e4.10-add-to-existing-picker",
            { IntakeHarness(previewState(count = 1, duplicates = 0, single = true)) },
        ) { h ->
            val people =
                listOf(
                    "Ayşe Yılmaz",
                    "Burak Demir",
                    "Elif Kaya",
                    "Mehmet Öz",
                    "Zeynep Arslan",
                    "Özgür Şahin",
                    "Ahmet Çelik",
                ).mapIndexed { i, name -> PersonItem(i + 1L, name, "+90 5${i}2 111 22 33") }
            compose.runOnUiThread {
                h.overlays.presentContactChooser("Add to Existing Contact", persistentListOf(*people.toTypedArray())) {}
            }
            advance(1_400)
        }

    @Test fun cardPreview() =
        snap("e4.10-card-preview", { IntakeHarness(previewState()) }) { h ->
            h.ui =
                h.ui.copy(
                    preview =
                        CardPreview(
                            0,
                            "Ada Lovelace",
                            "Analytical Engines, Countess",
                            h.ui.rows[0].photo,
                            persistentListOf(
                                PreviewSection(
                                    "Phone",
                                    persistentListOf(
                                        PreviewLine("Mobile", "+44 20 7946 0958"),
                                        PreviewLine("Work", "+44 20 7946 0100"),
                                    ),
                                ),
                                PreviewSection("Email", persistentListOf(PreviewLine("Home", "ada@example.org"))),
                                PreviewSection("Dates", persistentListOf(PreviewLine("Birthday", "1815-12-10"))),
                                PreviewSection("Notes", persistentListOf(PreviewLine("Notes", "First programmer."))),
                            ),
                        ),
                )
            advance(600)
        }

    @Test fun pasteNothingToast() {
        val fixture = AppFixture(granted = true)
        fixture.navigator.select(SettingsTab)
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) { fixture.Content() }
            advance(900)
            compose.onNodeWithTag(ContactsTags.PasteContact, useUnmergedTree = true).performClick()
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.10-paste-nothing-toast-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }
}
