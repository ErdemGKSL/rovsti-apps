package dev.rovsti.contacts.card

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.contacts.WaPresence
import dev.rovsti.core.model.contacts.WaProfileInput
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.design.list.ListRow
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.testing.ShotTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.3 screenshots: the card in the real shell, drawn from the REAL view model's state. */
@Config(sdk = [35])
class E43ShotTest : ShotTest() {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(kotlinx.coroutines.Job)
            ?.cancel()
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        state: ContactCardUiState,
        fontScale: Float = 1f,
        gate: (@androidx.compose.runtime.Composable () -> Unit)? = null,
        before: (CardHarness) -> Unit = {},
        after: (CardHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val h = CardHarness(state)
            composeFrame(dark = dark, fontScale = fontScale) { HostedCard(h, gate = gate) }
            advance(400)
            before(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            after(h)
        }
    }

    private fun rig(
        id: Long,
        build: () -> CardRig = { CardRig(id) },
    ): CardRig = build().also { rig = it }

    @Test fun full() = snap("e4.3-card-full", rig(1).ready())

    @Test fun minimal() = snap("e4.3-card-minimal", rig(2).ready())

    @Test fun whatsappOnline() {
        val wa =
            FakeCardWa(
                linked = true,
                chatUsers = listOf("905337654321"),
                profile =
                    WaProfileInput(
                        "905337654321@s.whatsapp.net",
                        pushName = "Elif K.",
                        about = "Busy, call after 5",
                    ),
            )
        val r = rig(3) { CardRig(3, wa = wa) }
        wa.updates.tryEmit(WaPresence(available = true))
        val state =
            r.stateWhere {
                it.header?.presence == "Online" &&
                    it.sections.any { s -> s.kind == RowKind.Whatsapp }
            }
        snap("e4.3-card-whatsapp-online", state)
    }

    @Test fun fieldMenuOpen() =
        snap("e4.3-field-menu-open", rig(1).ready(), before = { h ->
            compose.onNodeWithTag(CardTags.row("number-1")).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
        })

    @Test fun recentCalls() {
        val calls =
            listOf(
                CardRig.call(1, "+90 532 111 22 33", CallLogKind.Outgoing, 3_600_000, 125),
                CardRig.call(2, "+90 532 111 22 33", CallLogKind.Missed, 86_400_000L * 2),
                CardRig.call(3, "+49 30 1234567", CallLogKind.Incoming, 86_400_000L * 9, 840),
            )
        val r = rig(1) { CardRig(1, calls = calls) }
        snap("e4.3-card-recent-calls", r.stateWhere { it.recent is CardRecent.Calls }, before = { h ->
            compose.runOnUiThread { kotlinx.coroutines.runBlocking { h.listState.scrollToItem(100) } }
            advance(400)
        })
    }

    @Test fun recentNeedsTheCallLog() {
        val r = rig(1) { CardRig(1, permission = MutableStateFlow(PermissionState.NeverAsked)) }
        snap("e4.3-card-recent-permission", r.ready(), gate = { ListRow(title = "Call log") }, before = { h ->
            compose.runOnUiThread { kotlinx.coroutines.runBlocking { h.listState.scrollToItem(100) } }
            advance(400)
        })
    }

    @Test fun fs2() = snap("e4.3-card-fs2", rig(1).ready(), fontScale = 2f)
}
