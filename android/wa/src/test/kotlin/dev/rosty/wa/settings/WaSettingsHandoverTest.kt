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
import dev.rosty.wa.WaLinkSource
import dev.rosty.wa.WaRepository
import dev.rosty.wa.share.HeldLink
import dev.rosty.wa.share.SiblingAnswer
import dev.rosty.wa.share.SiblingLinks
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
 * WA-R2 with the vault (`plans/wa-vault.md` 13.3): the link screen offers the link of an unlocked sibling, takes it
 * only on the person's tap, hands it to this app's vault (never to a link file), and shows the linked page once the
 * vault's setup has opened it. Unlinking keeps the sibling's link and offers it again.
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
    private val link = StoredLink("https://rosty.erdemdev.tr", "inst1", "rwk_" + "A".repeat(43))
    private val marks = WaLinkMarks.InMemory()
    private var sibling: SiblingAnswer = SiblingAnswer.Link(link)

    /** The link waiting for this app's vault (its setup), and the vault's link once it is open (no key). */
    private var waiting: StoredLink? = null
    private var vaultLink: StoredLink? = null
    private val source = WaLinkSource { Outcome.Ok(vaultLink) }

    private val repo: WaRepository =
        object : WaRepository by fake {
            override suspend fun forget(): Outcome<Unit> {
                vaultLink = null
                return fake.forget()
            }
        }

    private val handover =
        WaLinkHandover(
            held = {
                when {
                    vaultLink != null -> HeldLink.Held(vaultLink?.instanceId)
                    waiting != null -> HeldLink.Held(null)
                    else -> HeldLink.None
                }
            },
            siblings = listOf(MESSAGES),
            source =
                object : SiblingLinks {
                    override suspend fun offers(packageName: String): Boolean = sibling is SiblingAnswer.Link

                    override suspend fun ask(packageName: String): SiblingAnswer = sibling
                },
            marks = marks,
            adopt = { taken ->
                waiting = taken
                Outcome.Ok(Unit)
            },
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
            store = source,
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

    /** The vault's setup ran for the waiting link and the vault is open. */
    private fun vaultOpens() {
        vaultLink = waiting?.copy(key = "")
        waiting = null
    }

    @Test
    fun theScreenOffersAnUnlockedSiblingsLinkAndTakesNothingByItself() =
        runTest {
            val vm = viewModel()
            assertEquals("choose", vm.state.value.view.kind)
            assertEquals(MESSAGES, vm.state.value.siblingOffer)
            assertNull("nothing was taken", waiting)
            assertNull(fake.linked)
            assertTrue("no link file was written", gw.core.calls.none { it.op == "wa.link_write" })
        }

    @Test
    fun theTapHandsTheLinkToTheVaultAndThePageFollowsOnceItIsOpen() =
        runTest {
            val vm = viewModel()
            vm.onAction(WaSettingsAction.UseSiblingLink)
            assertEquals("the link waits for this app's vault", link, waiting)
            assertNull("not stored like a pasted key", fake.linked)
            assertTrue("no link file was written", gw.core.calls.none { it.op == "wa.link_write" })
            assertNull(vm.state.value.siblingOffer)

            vaultOpens()
            vm.vaultOpened()
            assertEquals("connected", vm.state.value.view.kind)
            assertEquals(MESSAGES, vm.state.value.sharedFrom)
            // A page that knows its link carries on at the next unlock.
            val before = gw.core.calls.size
            vm.vaultOpened()
            assertEquals(before, gw.core.calls.size)
        }

    @Test
    fun withoutASiblingTheScreenStillOffersPaste() =
        runTest {
            sibling = SiblingAnswer.NoLink
            val vm = viewModel()
            assertEquals("choose", vm.state.value.view.kind)
            assertTrue(
                vm.state.value.view.buttons
                    .any { it.id == "connect_existing" },
            )
            assertNull(vm.state.value.sharedFrom)
            assertNull(vm.state.value.siblingOffer)
            vm.onAction(WaSettingsAction.UseSiblingLink)
            assertNull(waiting)
        }

    @Test
    fun aHostWithoutHandOverBehavesAsBefore() =
        runTest {
            val vm = viewModel(share = null)
            assertEquals("choose", vm.state.value.view.kind)
            assertNull(vm.state.value.siblingOffer)
            assertNull(waiting)
        }

    @Test
    fun unlinkingHereLeavesTheSiblingLinkedAndOffersItsLink() =
        runTest {
            val vm = viewModel()
            vm.onAction(WaSettingsAction.UseSiblingLink)
            vaultOpens()
            vm.vaultOpened()
            vm.onAction(WaSettingsAction.Press(SettingsInput.unit("unlink")))
            assertEquals("choose", vm.state.value.view.kind)
            assertTrue("forget" in fake.log)
            assertNull(vaultLink)
            assertEquals("the sibling was not asked to do anything", SiblingAnswer.Link(link), sibling)
            assertNull(vm.state.value.sharedFrom)
            assertEquals(MESSAGES, vm.state.value.siblingOffer)
            assertNull(waiting)
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
