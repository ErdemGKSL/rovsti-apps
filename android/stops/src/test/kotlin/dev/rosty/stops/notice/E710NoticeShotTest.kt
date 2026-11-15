package dev.rosty.stops.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.saved.settleRust
import dev.rosty.stops.trip.TripFixtures
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The WhatsApp screenshots of E7.10 (AD-ST7), light and dark, on the real core behind the fake's files: the
 * arriving-soon page (chat, message with its preview, when), the message field with a selection, the chat picker,
 * the page without a link, the page in Turkish and at font scale 2, and the per-trip switch under Start.
 */
@Config(sdk = [35])
class E710NoticeShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ShotTest.CHANGE_THRESHOLD))

    private var fixture: AppFixture? = null

    @Composable
    private fun App(
        route: StopsRoute?,
        linked: Boolean,
    ) {
        val made =
            remember {
                val core =
                    FakeStopsCore(
                        prefs = StopsPrefs(notice = NoticeFixtures.notice),
                        saved = SampleStops.saved,
                        draft = Ends(TripFixtures.a, TripFixtures.b),
                        real = HostCore.real(),
                    )
                AppFixture(core).also { f ->
                    f.container.fakeWa.setChats(NoticeFixtures.listedByName)
                    if (linked) f.container.fakeWa.setState(NoticeFixtures.connected)
                    if (route != null) {
                        f.navigator.push(StopsRoute.Settings)
                        f.navigator.push(StopsRoute.ArrivingSoon)
                        if (route != StopsRoute.ArrivingSoon) f.navigator.push(route)
                    }
                }
            }
        fixture = made
        made.Content()
    }

    private fun noticeShot(
        name: String,
        route: StopsRoute? = StopsRoute.ArrivingSoon,
        linked: Boolean = true,
        fontScale: Float = 1f,
        before: () -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            // The view models belong to the activity: each pass starts as a new launch.
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            composeFrame(dark = dark, fontScale = fontScale) { App(route, linked) }
            settleRust()
            before()
            settleRust()
            compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png", options)
        }
    }

    @Test fun arrivingSoon() = noticeShot("e7.10-arriving-soon")

    /** The message field in use: "{stop}" selected. */
    @Test fun templateFieldSelection() =
        noticeShot("e7.10-template-field-selection") {
            compose.onNodeWithTag(NoticeTags.Template).performClick()
            settleRust(300)
            compose.onNodeWithTag(NoticeTags.Template).performTextInputSelection(TextRange(STOP_START, STOP_END))
        }

    @Test fun chatPicker() = noticeShot("e7.10-arriving-soon-chat-picker", StopsRoute.ArrivingSoonChat)

    @Test fun notConnected() = noticeShot("e7.10-arriving-soon-not-connected", linked = false)

    @Test fun turkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        noticeShot("e7.10-arriving-soon-tr")
    }

    @Test fun fontScale2() = noticeShot("e7.10-arriving-soon-fs2", fontScale = 2f)

    /** The plan sheet at its large detent: "Text Ayşe Demir when arriving" with its switch under Start. */
    @Test fun perTripSwitch() =
        noticeShot("e7.10-trip-notice-switch", route = null) {
            repeat(2) {
                compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
                settleRust(800)
            }
        }

    private companion object {
        /** "{stop}" in "Arriving at {stop} in about {min} min.". */
        const val STOP_START = 12
        const val STOP_END = 18
    }
}
