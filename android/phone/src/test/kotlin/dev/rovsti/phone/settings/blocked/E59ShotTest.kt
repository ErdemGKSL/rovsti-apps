package dev.rovsti.phone.settings.blocked

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.phone.HostedPhone
import dev.rovsti.phone.PhoneHarness
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneSample
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** E5.9a screenshots: Settings (normal, at twice the font size, in Turkish) and the Blocked numbers page with its sheets. */
@Config(sdk = [35])
class E59ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    private fun settings(
        name: String,
        fontScale: Float = 1f,
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = fontScale) {
                HostedPhone(h, PhoneSample.setup(), push = PhoneRoute.Settings)
            }
            advance(1_200)
            capture(name, dark)
        }
    }

    @Test fun settings() = settings("e5.9-settings")

    @Test fun settingsBigFont() = settings("e5.9-settings-fs2", fontScale = 2f)

    @Test fun settingsTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        settings("e5.9-settings-tr")
    }

    private fun blocked(
        ui: BlockedUi,
        name: String,
        fontScale: Float = 1f,
        before: (PhoneHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = fontScale) {
                HostedPhone(
                    h,
                    PhoneSample.setup(),
                    push = PhoneRoute.Blocked,
                    extra = {
                        BlockedContent(ui, onAction = {}, onPaste = {}, onPrefix = {}, onImport = {})
                    },
                )
            }
            advance(1_200)
            before(h)
            capture(name, dark)
        }
    }

    @Test fun blockedList() = blocked(BlockedSample.ui(), "e5.9-blocked-list")

    @Test fun blockedListTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        blocked(BlockedSample.ui(), "e5.9-blocked-list-tr")
    }

    @Test fun blockedEmpty() = blocked(BlockedSample.ui(emptyList(), emptyList(), emptyList()), "e5.9-blocked-empty")

    @Test fun blockedNoRole() =
        blocked(BlockedSample.ui(emptyList(), emptyList(), emptyList(), canWrite = false), "e5.9-blocked-no-role")

    private fun sheet(
        name: String,
        content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
        field: String,
        ime: Boolean = false,
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark) {
                HostedPhone(
                    h,
                    PhoneSample.setup(),
                    push = PhoneRoute.Blocked,
                    extra = {
                        BlockedContent(BlockedSample.ui(), onAction = {}, onPaste = {}, onPrefix = {}, onImport = {})
                    },
                )
            }
            advance(800)
            compose.runOnUiThread {
                h.overlays.present(
                    SheetEntry("Sheet", persistentListOf(Detent.Medium, Detent.Large), Detent.Large) { _ -> content() },
                )
            }
            advance(1_500)
            compose.onNodeWithTag(field).performClick()
            // The IME rig (imeOverlapPadding): the window keeps its size, the keyboard covers the bottom, the sheet makes room.
            if (ime) setSimulatedIme(IME_DP)
            advance(500)
            capture(name, dark)
        }
    }

    private fun pasteMany(
        name: String,
        ime: Boolean,
    ) = sheet(
        name,
        {
            val field = rememberTextFieldState()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                field.setTextAndPlaceCursorAtEnd(
                    "Spam: 0532 111 22 33, +49 151 2345678, 112\n0850 333 44 55\n+1 415 555 0132, not a number",
                )
            }
            PasteSheetContent(
                BlockedSample.ui(paste = PastePreview(toBlock = 3, emergency = 1, alreadyBlocked = 1)),
                field,
                onAction = {},
                onClose = {},
            )
        },
        BlockedTags.PasteField,
        ime,
    )

    @Test fun pasteMany() = pasteMany("e5.9-block-paste-many", ime = false)

    @Test fun pasteManyIme() = pasteMany("e5.9-block-paste-many-ime", ime = true)

    @Test fun prefix() =
        sheet(
            "e5.9-block-prefix",
            {
                val field = rememberTextFieldState()
                androidx.compose.runtime.LaunchedEffect(Unit) { field.setTextAndPlaceCursorAtEnd("+90 850") }
                PrefixSheetContent(
                    BlockedSample.ui(prefix = PrefixPreview(display = "+90 850")),
                    field,
                    onAction = {},
                    onClose = {},
                )
            },
            BlockedTags.PrefixField,
            ime = true,
        )

    private companion object {
        const val IME_DP = 300
    }
}
