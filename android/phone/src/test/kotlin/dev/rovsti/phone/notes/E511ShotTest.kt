package dev.rovsti.phone.notes

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.text.rememberSearchFocusHandoff
import dev.rovsti.phone.HostedPhone
import dev.rovsti.phone.PhoneHarness
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneSample
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.11 screenshots: the Notes page on the real shell, light and dark. */
@Config(sdk = [35])
class E511ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    @Composable
    private fun Page(
        ui: NotesUi,
        h: PhoneHarness,
    ) {
        HostedPhone(h, PhoneSample.setup(), push = PhoneRoute.Notes, notes = {
            val search = rememberTextFieldState(ui.query)
            NotesScreen(ui, search, rememberSearchFocusHandoff(search), onAction = {}, onBack = {})
        })
    }

    private fun snap(
        name: String,
        ui: NotesUi,
        fontScale: Float = 1f,
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = fontScale) { Page(ui, h) }
            advance(2_000)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun notes() = snap("e5.11-notes", NotesSample.all())

    @Test fun search() = snap("e5.11-notes-search", NotesSample.search())

    @Test fun empty() = snap("e5.11-notes-empty", NotesSample.empty())

    @Test fun noMatch() = snap("e5.11-notes-no-match", NotesSample.noMatch())

    @Test fun notesBigFont() = snap("e5.11-notes-fs2", NotesSample.all(), fontScale = 2f)
}
