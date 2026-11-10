package dev.rovsti.contacts.card

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.photo.PhotoMenuActions
import dev.rovsti.core.model.contacts.ContactOptions
import dev.rovsti.design.media.PhotoViewerTags
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E4.12 and E4.13 screenshots on the real card: the photo viewer with its toolbar and Edit menu, the "Calls" section
 * and the two questions (voicemail, block). Each runs on its own rig so the effects belong to one composition.
 */
@Config(sdk = [35])
class E413ShotTest : ShotTest() {
    private val rigs = mutableListOf<CardRig>()

    @After fun stop() {
        rigs.forEach { it.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        id: Long,
        canBlock: Boolean = true,
        before: (CardHarness, CardRig) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val r = CardRig(id, canBlock = canBlock).also { rigs += it }
            r.repo.seedOptions(id, ContactOptions("content://media/9", "Beacon", false))
            r.ready()
            r.callsWhere { it.loaded && it.block != BlockRow.Hidden }
            val h = CardHarness(r.ready())
            composeFrame(
                dark = dark,
            ) { HostedCard(h, live = r.model, callsModel = r.callOptions, photoViewer = viewer(r, h)) }
            advance(500)
            before(h, r)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun viewer(
        r: CardRig,
        h: CardHarness,
    ) = cardPhotoViewer(
        context = RuntimeEnvironment.getApplication(),
        id = 5,
        files = r.files,
        scope = r.scope,
        overlays = { h.overlays },
        jid = { "905551112233@s.whatsapp.net" },
        menu = PhotoMenuActions({}, {}, {}, {}),
        share = { _, _, _ -> },
    )

    @Test fun viewer() =
        snap("e4.12-viewer", 5) { _, _ ->
            compose.onNodeWithTag(CardTags.Avatar).performClick()
            advance(900)
            compose.onNodeWithTag(PhotoViewerTags.Root).assertExists()
        }

    @Test fun viewerMenu() =
        snap("e4.12-viewer-menu", 5) { _, _ ->
            compose.onNodeWithTag(CardTags.Avatar).performClick()
            advance(900)
            compose.onNodeWithTag(PhotoViewerTags.action("edit")).performClick()
            advance(900)
        }

    @Test fun cardOptions() =
        snap("e4.13-card-options", 2) { h, _ ->
            compose.onNodeWithTag(CallOptionsTags.Section).assertExists()
        }

    @Test fun voicemailConfirm() =
        snap("e4.13-voicemail-confirm", 2) { _, _ ->
            compose.onNodeWithTag(CallOptionsTags.Voicemail).performSemanticsAction(SemanticsActions.OnClick)
            repeat(30) {
                advance(50)
                Thread.sleep(10)
            }
            advance(500)
        }

    @Test fun blockConfirm() =
        snap("e4.13-block-confirm", 2) { _, _ ->
            compose.onNodeWithTag(CallOptionsTags.Block).performClick()
            repeat(30) {
                advance(50)
                Thread.sleep(10)
            }
            advance(500)
        }

    @Test fun blockNeedsRole() =
        snap("e4.13-block-needs-role", 2, canBlock = false) { _, _ ->
            compose.onNodeWithText(NEEDS_ROLE_TEXT).assertExists()
        }
}
