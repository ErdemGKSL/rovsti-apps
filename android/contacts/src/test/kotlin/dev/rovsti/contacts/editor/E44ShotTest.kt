package dev.rovsti.contacts.editor

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.4 screenshots: the editor sheet in the real shell, drawn from the REAL model's state. */
@Config(sdk = [35])
class E44ShotTest : ShotTest() {
    private var session: EditorSession? = null

    @After fun stop() {
        session?.rig?.stop()
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    /** One shot per theme; each theme gets its own rig and session because a frame cannot be recomposed twice here. */
    private fun snap(
        name: String,
        build: () -> EditorRig,
        script: (EditorSession) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val s = EditorSession(this, build()).also { session = it }
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

    @Test fun new() = snap("e4.4-editor-new", { EditorRig() })

    @Test fun filled() = snap("e4.4-editor-filled", { EditorRig(id = 1) })

    @Test fun keyboardOpen() =
        snap("e4.4-editor-keyboard-open", { EditorRig(id = 1) }) { s ->
            s.scrollTo(EditorTags.field("note"))
            advance(300)
            compose.onNodeWithTag(EditorTags.field("note")).performClick()
            setSimulatedIme(300)
            advance(900)
            advance(600)
        }

    @Test fun labelPopover() =
        snap("e4.4-editor-label-popover", { EditorRig(id = 1) }) { s ->
            s.scrollTo(EditorTags.label(EditorList.Emails, 0))
            advance(300)
            compose.onNodeWithTag(EditorTags.label(EditorList.Emails, 0)).performClick()
            s.until("the popover") { s.exists(EditorTags.LabelPopover) }
            advance(800)
        }

    @Test fun discardConfirm() =
        snap("e4.4-discard-confirm", { EditorRig(id = 1) }) { s ->
            compose.onNodeWithTag(EditorTags.field("org")).performClick()
            compose.onNodeWithTag(EditorTags.field("org")).performTextInput(" Ltd")
            s.until("dirty") { s.rig.model.state.value.dirty }
            advance(300)
            compose.onNodeWithTag(EditorTags.Cancel).performClick()
            s.until("the question") { compose.onAllNodesWithText("Discard Changes").fetchSemanticsNodes().isNotEmpty() }
            advance(800)
        }

    @Test fun validationError() =
        snap("e4.4-validation-error", { EditorRig() }) { s ->
            compose.onNodeWithTag(EditorTags.field("given")).performClick()
            compose.onNodeWithTag(EditorTags.field("given")).performTextInput("Ada")
            s.scrollTo(EditorTags.add(EditorList.Emails))
            compose.onNodeWithTag(EditorTags.add(EditorList.Emails)).performClick()
            val tag = EditorTags.rowField(EditorList.Emails, 0)
            s.until("the email row") { s.exists(tag) }
            advance(700)
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithTag(tag).performTextInput("ada-at-example")
            s.until("dirty") { s.rig.model.state.value.canSave }
            advance(200)
            compose.onNodeWithTag(EditorTags.Done).performClick()
            s.until("the sentence") {
                s.rig.model.state.value.issues
                    .isNotEmpty()
            }
            advance(600)
        }
}
