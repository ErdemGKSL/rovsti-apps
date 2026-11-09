package dev.rovsti.contacts.photo

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.editor.EditorRig
import dev.rovsti.contacts.editor.EditorSession
import dev.rovsti.contacts.editor.EditorTags
import dev.rovsti.core.model.contacts.PhotoSize
import dev.rovsti.platform.files.FileResult
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.4 AC2 screenshots: the avatar menu over the editor sheet, and the crop page. */
@Config(sdk = [35])
class E44PhotoShotTest : ShotTest() {
    private var session: EditorSession? = null

    override val imageProducer get() = ::pictureProducer

    @After fun stop() {
        session?.rig?.stop()
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun rig() =
        EditorRig(id = 1, storedPhoto = "content://photos/1", whatsAppJid = "905321112233@s.whatsapp.net").also {
            it.repo.photoSizes = mapOf(PICTURE_PATH to PhotoSize(4000, 3000))
        }

    private fun snap(
        name: String,
        script: (EditorSession) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val s = EditorSession(this, rig()).also { session = it }
            s.open(dark = dark)
            script(s)
            advance(500)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            s.rig.stop()
        }
    }

    @Test fun photoMenu() =
        snap("e4.4-photo-menu") { s ->
            compose.onNodeWithTag(EditorTags.Photo).performClick()
            s.until("the menu") { compose.onAllNodesWithText("Use WhatsApp Photo").fetchSemanticsNodes().isNotEmpty() }
            advance(800)
        }

    @Test fun crop() =
        snap("e4.4-crop") { s ->
            s.rig.extras.photo
                .picked(FileResult.Ok(path = PICTURE_PATH, mime = "image/jpeg"))
            s.until("the crop page") { s.exists(CropTags.Root) }
            advance(900)
        }
}
