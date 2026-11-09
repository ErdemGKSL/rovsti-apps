package dev.rovsti.contacts.editor

import android.os.Looper
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/** Drives the editor sheet in the real shell for the screen tests and the shots. */
internal class EditorSession(
    private val test: ShotTest,
    val rig: EditorRig,
) {
    val overlays = OverlayController()
    var closed = 0
    var savedIds = mutableListOf<Long>()

    /** Renders the shell, waits for the model and presents the editor sheet; returns once the sheet has risen. */
    fun open(
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        test.composeFrame(dark = dark, fontScale = fontScale) { EditorHost(overlays) }
        test.advance(300)
        rig.ready()
        test.compose.runOnUiThread {
            presentContactEditor(
                rig.model,
                overlays,
                rig.scope,
                outcome = { id, _ -> savedIds += id },
                onClosed = { closed++ },
                extras = rig.extras,
            )
        }
        until("the editor form is on screen") { exists(EditorTags.Scroll) }
        test.advance(900)
    }

    /**
     * Scrolls the form until [tag] is on screen, 300 px at a time through the column's own scroll action (the library's
     * `performScrollTo` loops for ever when the node cannot be reached, which hides the real failure).
     */
    fun scrollTo(tag: String) {
        val rootHeight =
            test.compose
                .onRoot()
                .fetchSemanticsNode()
                .size.height
        repeat(40) {
            val bounds =
                test.compose
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val scroll =
                test.compose
                    .onNodeWithTag(EditorTags.Scroll)
                    .fetchSemanticsNode()
                    .boundsInRoot
            if (bounds.bottom <= scroll.bottom && bounds.top >= scroll.top) return
            test.compose.runOnUiThread {
                test.compose
                    .onNodeWithTag(EditorTags.Scroll)
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy]
                    .action
                    ?.invoke(0f, 300f)
            }
            test.advance(50)
        }
        val bounds =
            test.compose
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .boundsInRoot
        throw AssertionError("$tag could not be scrolled into view: $bounds of a $rootHeight px screen")
    }

    fun exists(tag: String): Boolean =
        test.compose
            .onNodeWithTag(tag)
            .fetchSemanticsNodeOrNull() != null

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodeOrNull() =
        try {
            fetchSemanticsNode()
        } catch (_: AssertionError) {
            null
        }

    /** Advances the manual clock in steps, letting the model's threads answer in between, until [done]. */
    fun until(
        what: String,
        limitMs: Long = 8_000,
        done: () -> Boolean,
    ) {
        var waited = 0L
        while (!done()) {
            assertTrue("timed out waiting for $what", waited < limitMs)
            Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idle()
            test.advance(50)
            waited += 75
        }
    }
}
