package dev.rosty.messages.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.messages.inbox.InboxSample
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** E6.8 screenshots: New Conversation with contacts, and while a number is typed. */
@Config(sdk = [35])
class E68ShotTest : ShotTest() {
    private val contacts =
        listOf(
            RecipientOption("1:01", "Ada Lovelace", "Mobile · +90 555 000 00 01", "+905550000001", null, false),
            RecipientOption("1:02", "Ada Lovelace", "Work · +90 555 000 00 02", "+905550000002", null, false),
            RecipientOption("2:03", "Deniz Aydın", "Mobile · +90 555 000 00 03", "+905550000003", null, false),
            RecipientOption("3:04", "Grace Hopper", "+90 555 000 00 04", "+905550000004", null, false),
        )

    @Test fun newConversation() =
        shot("e6.8-new-conversation") {
            OverlayHost(OverlayController()) {
                NewConversationScreen(
                    NewConversationUi(contacts.toImmutableList()),
                    rememberTextFieldState(),
                    false,
                    {},
                    {},
                )
            }
        }

    @Test fun typingANumber() {
        val typed = RecipientOption("typed", "0555 000 00", "0555 000 00", "0555 000 00", null, true)
        shot("e6.8-new-conversation-typing-number") {
            OverlayHost(OverlayController()) {
                NewConversationScreen(
                    NewConversationUi((listOf(typed) + contacts.take(1)).toImmutableList(), "0555 000 00"),
                    rememberTextFieldState("0555 000 00"),
                    false,
                    {},
                    {},
                )
            }
        }
    }

    @Test fun shareIn() =
        shot("e6.8-share-in") {
            OverlayHost(OverlayController()) {
                NewConversationScreen(
                    NewConversationUi(contacts.toImmutableList()),
                    rememberTextFieldState(),
                    true,
                    {},
                    {},
                )
            }
        }

    @Test fun forwardChooser() {
        val app = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark) { OverlayHost(overlays) { Box(Modifier.fillMaxSize()) } }
            advance(500)
            compose.runOnUiThread {
                overlays.presentForward(app, InboxSample.state.rows, "wa:ada@s.whatsapp.net", "See you at six", {})
            }
            // The sheet's spring needs frames: one long advance runs only its first.
            repeat(15) { advance(100) }
            compose.onRoot().captureRoboImage("screenshots/e6.8-forward-chooser-${if (dark) "dark" else "light"}.png")
        }
    }
}
