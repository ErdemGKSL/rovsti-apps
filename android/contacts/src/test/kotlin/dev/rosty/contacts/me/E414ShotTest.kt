package dev.rosty.contacts.me

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.contacts.card.CardHarness
import dev.rosty.contacts.card.CardRig
import dev.rosty.contacts.card.ContactCardAction
import dev.rosty.contacts.card.HostedCard
import dev.rosty.contacts.emergency.EmergencyHarness
import dev.rosty.contacts.emergency.EmergencyTags
import dev.rosty.contacts.emergency.EmergencyUi
import dev.rosty.contacts.emergency.HostedEmergency
import dev.rosty.contacts.list.HostedList
import dev.rosty.contacts.list.ListHarness
import dev.rosty.contacts.list.MyCardItem
import dev.rosty.contacts.list.RecentEntry
import dev.rosty.contacts.list.SampleBook
import dev.rosty.contacts.recent.HostedRecent
import dev.rosty.contacts.recent.RecentHarness
import dev.rosty.contacts.recent.RecentUi
import dev.rosty.testing.RecordingHaptics
import dev.rosty.testing.ShotTest
import dev.rosty.testing.ShotVariant
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.14 screenshots: the My card and Emergency rows on the list, the card menu, the Emergency page, Added / Edited. */
@Config(sdk = [35])
class E414ShotTest : ShotTest() {
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

    private fun listState(card: MyCardItem?) =
        SampleBook.state().copy(
            myCard = card,
            emergencyDetail = "3 of 5",
            recentEntry = RecentEntry("Recent", "5 added · 3 edited"),
        )

    @Test fun myCard() =
        shot("e4.14-my-card", variants = setOf(ShotVariant.Fs2)) {
            HostedList(ListHarness(listState(MyCardItem(1, "My card", "Ayşe Yılmaz"))))
        }

    @Test fun myCardNotSetUp() =
        shot("e4.14-my-card-unset") {
            HostedList(ListHarness(listState(MyCardItem(null, "Set up my card", "Choose the contact that is you"))))
        }

    @Test fun cardMenuWithTheEntries() {
        val r = CardRig(1).also { rig = it }
        val state = r.ready()
        for (dark in listOf(false, true)) {
            val h = CardHarness(state)
            composeFrame(dark = dark) { HostedCard(h) }
            advance(400)
            compose.onNodeWithTag("toolbar-overflow").performClick()
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.14-my-card-menu-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun cardIsMyCardAndEmergency() {
        val r = CardRig(1).also { rig = it }
        r.ready()
        r.model.onAction(ContactCardAction.ToggleMyCard)
        r.effect()
        r.model.onAction(ContactCardAction.ToggleEmergency)
        r.effect()
        val state = r.stateWhere { it.header?.isMyCard == true && it.header?.isEmergency == true }
        for (dark in listOf(false, true)) {
            val h = CardHarness(state)
            composeFrame(dark = dark) { HostedCard(h) }
            advance(400)
            compose.onNodeWithTag("toolbar-overflow").performClick()
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.14-my-card-badge-menu-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun emergencyList() =
        shot("e4.14-emergency-list", variants = setOf(ShotVariant.Fs2, ShotVariant.Rtl)) {
            HostedEmergency(EmergencyHarness(EmergencyUi.state()))
        }

    @Test fun emergencyEditing() =
        shot("e4.14-emergency-edit") {
            HostedEmergency(EmergencyHarness(EmergencyUi.state(editing = true)), RecordingHaptics())
        }

    @Test fun emergencyEmpty() =
        shot("e4.14-emergency-empty") {
            HostedEmergency(EmergencyHarness(EmergencyUi.state(people = emptyList())))
        }

    @Test fun emergencyRowMenu() {
        for (dark in listOf(false, true)) {
            val h = EmergencyHarness(EmergencyUi.state())
            composeFrame(dark = dark) { HostedEmergency(h) }
            advance(700)
            compose.onNodeWithTag(EmergencyTags.row(1)).performTouchInput { longClick(durationMillis = 700) }
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e4.14-emergency-row-menu-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun addedEditedTabs() =
        shot("e4.14-added-edited-tabs", variants = setOf(ShotVariant.Fs2)) {
            HostedRecent(RecentHarness(RecentUi.state()))
        }

    @Test fun editedTab() =
        shot("e4.14-added-edited-tabs-edited") {
            HostedRecent(RecentHarness(RecentUi.state(selected = "edited")))
        }

    @Test fun emptyTab() =
        shot("e4.14-added-edited-tabs-empty") {
            HostedRecent(RecentHarness(RecentUi.state(added = emptyList())))
        }
}
