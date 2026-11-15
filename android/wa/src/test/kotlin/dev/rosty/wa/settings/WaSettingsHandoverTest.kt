package dev.rosty.wa.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.AppId
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.SettingsInput
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.core.ops.NativeQrOps
import dev.rosty.platform.files.FakeClipboardRepository
import dev.rosty.platform.files.FakeFilesRepository
import dev.rosty.wa.DemoSettingsMachine
import dev.rosty.wa.FakeQr
import dev.rosty.wa.FakeWaGateway
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaLinkStore
import dev.rosty.wa.WaRepository
import dev.rosty.wa.share.SiblingAnswer
import dev.rosty.wa.share.WaLinkHandover
import dev.rosty.wa.share.WaLinkMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WA-R2: the link screen and the hand-over between Rosty apps. The screen opens without a link, a sibling has one:
 * the page is the linked one and says where the link came from. Unlinking keeps the sibling's link and offers it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaSettingsHandoverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val gw = FakeWaGateway()
    private val fake = FakeWaRepository()
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_" + "A".repeat(43))
    private val marks = WaLinkMarks.InMemory()
    private var sibling: SiblingAnswer = SiblingAnswer.Link(link)

    /** [FakeWaRepository] that also keeps the link in the fake link file, like the real repository. */
    private val repo: WaRepository =
        object : WaRepository by fake {
            override suspend fun relinkTo(link: StoredLink): Outcome<Unit> {
                gw.link = link
                return fake.relinkTo(link)
            }

            override suspend fun forget(): Outcome<Unit> {
                gw.link = null
                return fake.forget()
            }
        }

    private val handover =
        WaLinkHandover(
            store = WaLinkStore(gw.ops),
            siblings = listOf(MESSAGES),
            source = { sibling },
            marks = marks,
            adopt = repo::relinkTo,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        FakeQr.install(gw.core)
        fake.gatewayOpener = { (gw.opener.open(link) as? Outcome.Ok)?.value }
        gw.status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected, "100000000001@s.whatsapp.net", "Ada"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(share: WaLinkHandover? = handover) =
        WhatsAppSettingsViewModel(
            app = AppId.Phone,
            machine = DemoSettingsMachine(),
            opener = gw.urlOpener,
            store = WaLinkStore(gw.ops),
            repo = repo,
            qr = NativeQrOps(gw.core),
            clipboard = FakeClipboardRepository(),
            files = FakeFilesRepository(),
            stagingDir = tmp.newFolder(),
            clock = { 1_760_000_000_000L },
            saved = SavedStateHandle(),
            io = dispatcher,
            handover = share,
        )

    @Test
    fun theScreenOpensLinkedWhenASiblingHasTheLink() =
        runTest {
            val vm = viewModel()
            assertEquals("the link was stored as a pasted key would be", link, fake.linked)
            assertEquals("connected", vm.state.value.view.kind)
            assertEquals(MESSAGES, vm.state.value.sharedFrom)
            assertNull(vm.state.value.siblingOffer)
        }

    @Test
    fun withoutASiblingTheScreenStillOffersPaste() =
        runTest {
            sibling = SiblingAnswer.NotInstalled
            val vm = viewModel()
            assertEquals("choose", vm.state.value.view.kind)
            assertTrue(
                vm.state.value.view.buttons
                    .any { it.id == "connect_existing" },
            )
            assertNull(vm.state.value.sharedFrom)
            assertNull(vm.state.value.siblingOffer)
            assertNull(fake.linked)
        }

    @Test
    fun aHostWithoutHandOverBehavesAsBefore() =
        runTest {
            val vm = viewModel(share = null)
            assertEquals("choose", vm.state.value.view.kind)
            assertNull(fake.linked)
        }

    @Test
    fun unlinkingHereLeavesTheSiblingLinkedAndOffersItsLink() =
        runTest {
            val vm = viewModel()
            vm.onAction(WaSettingsAction.Press(SettingsInput.unit("unlink")))
            assertEquals("choose", vm.state.value.view.kind)
            assertTrue("forget" in fake.log)
            assertNull(gw.link)
            assertEquals("the sibling was not asked to do anything", SiblingAnswer.Link(link), sibling)
            assertNull(vm.state.value.sharedFrom)
            assertEquals(MESSAGES, vm.state.value.siblingOffer)

            // Opening the screen again does not take the link back behind the user's back.
            val again = viewModel()
            assertEquals("choose", again.state.value.view.kind)
            assertNull(gw.link)
            assertEquals(MESSAGES, again.state.value.siblingOffer)

            // The user's own tap does.
            again.onAction(WaSettingsAction.UseSiblingLink)
            assertEquals(link, gw.link)
            assertEquals("connected", again.state.value.view.kind)
            assertEquals(MESSAGES, again.state.value.sharedFrom)
            assertNull(again.state.value.siblingOffer)
        }

    @Test
    fun theLinkedPageSaysWhereTheLinkCameFromAndTheStartPageOffersIt() {
        val harness = Harness(stateOf(fixtureView("connected")).copy(sharedFrom = "dev.rosty.nowhere"))
        compose.setContent { HostedSettings(harness) }
        compose
            .onNodeWithText("This link was taken from another Rosty app. Unlinking here leaves it linked there.")
            .assertIsDisplayed()

        harness.ui = stateOf(fixtureView("")).copy(siblingOffer = "dev.rosty.nowhere")
        compose.waitForIdle()
        compose.onNodeWithText("Use the link from another Rosty app").assertIsDisplayed()
        // The page's own options are still there: paste stays the fallback.
        compose.onNodeWithTag("wa-connect_existing").assertIsDisplayed()
        compose.onNodeWithTag("wa-use-sibling").performClick()
        assertEquals(listOf<WaSettingsAction>(WaSettingsAction.UseSiblingLink), harness.actions)
    }

    private companion object {
        const val MESSAGES = "dev.rosty.messages"
    }
}
