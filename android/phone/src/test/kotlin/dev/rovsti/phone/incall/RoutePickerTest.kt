package dev.rovsti.phone.incall

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.core.model.phone.RowKind
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.7a: the route list tolerates a missing `BLUETOOTH_CONNECT` grant, an empty list and a vanished device (ARCH 7.5). */
@Config(sdk = [35])
class RoutePickerTest : ShotTest() {
    private val picked = mutableListOf<String>()

    private val rows =
        persistentListOf(
            RouteRowUi("e1", RowKind.Phone, "Phone", "", selected = true, unavailable = false),
            RouteRowUi("s1", RowKind.Speaker, "Speaker", "", selected = false, unavailable = false),
            RouteRowUi(
                "b1",
                RowKind.Bluetooth,
                "Pixel Buds Pro",
                "Battery 80 %",
                selected = false,
                unavailable = false,
            ),
            RouteRowUi("w1", RowKind.WiredHeadset, "Headset", "", selected = false, unavailable = true),
        )

    private fun show(list: kotlinx.collections.immutable.ImmutableList<RouteRowUi> = rows) {
        composeFrame { RoutePickerContent(list, inCallStrings, { picked += it }) }
        advance(100)
    }

    @Test fun everyRowIsListedWithItsLabel() {
        show()
        for (id in listOf("e1", "s1", "b1", "w1")) compose.onNodeWithTag(InCallTags.route(id)).assertIsDisplayed()
    }

    @Test fun tappingARowPicksIt() {
        show()
        compose.onNodeWithTag(InCallTags.route("b1")).performClick()
        assertEquals(listOf("b1"), picked)
    }

    @Test fun theSelectedRowIsMarkedSelected() {
        show()
        val node = compose.onNodeWithTag(InCallTags.route("e1")).fetchSemanticsNode()
        assertEquals(true, node.config.getOrNull(SemanticsProperties.Selected))
        val other = compose.onNodeWithTag(InCallTags.route("s1")).fetchSemanticsNode()
        assertEquals(false, other.config.getOrNull(SemanticsProperties.Selected))
    }

    @Test fun aVanishedDeviceCannotBePicked() {
        show()
        compose.onNodeWithTag(InCallTags.route("w1")).assertIsNotEnabled()
        compose.onNodeWithTag(InCallTags.route("w1")).performClick()
        assertTrue(picked.isEmpty())
    }

    @Test fun anEmptyListDoesNotCrash() {
        show(persistentListOf())
        compose.onNodeWithTag(InCallTags.RouteList).assertIsDisplayed()
    }

    @Test fun withoutTheBluetoothGrantTheModelListsNoBluetoothRow() {
        val h = InCallHarness(liveSnapshot(endpoints = listOf(earpiece, speaker, headset)), bluetooth = false)
        assertTrue(h.ui.routeRows.none { it.kind == RowKind.Bluetooth })
        show(h.ui.routeRows)
        compose.onNodeWithTag(InCallTags.route("b1")).assertDoesNotExist()
        compose.onNodeWithTag(InCallTags.route("s1")).assertIsDisplayed()
    }

    @Test fun anEndpointListThatFailsInTheCoreLeavesAnEmptyListAndNoCrash() {
        val failing =
            object : FakeInCallBackend() {
                override suspend fun audioRows(
                    endpoints: List<dev.rovsti.core.model.phone.Endpoint>,
                    previous: List<dev.rovsti.core.model.phone.Endpoint>?,
                ) = null
            }
        val h = InCallHarness(backend = failing)
        assertTrue(h.ui.routeRows.isEmpty())
        assertTrue(h.ui.live)
    }
}
