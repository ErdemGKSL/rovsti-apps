package dev.rovsti.wa.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.wa.AppId
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.KeyInfo
import dev.rovsti.core.model.wa.LinkRequest
import dev.rovsti.core.model.wa.SettingsApplyRequest
import dev.rovsti.core.model.wa.SettingsButton
import dev.rovsti.core.model.wa.SettingsLink
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.core.ops.NativeWaSettingsOps
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.wa.DemoSettingsMachine
import dev.rovsti.wa.FakeQr
import dev.rovsti.wa.FakeWaGateway
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaLinkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * R9-6 / E3.10: one walk per path of the settings screen through the real view model, the real [dev.rovsti.core.ops.WaGateway]
 * and [FakeWaGateway]. Rust's machine is stood in for by [DemoSettingsMachine] (the native library cannot load on the JVM);
 * what is under test is the part that is Kotlin: the effects, the answers fed back, events, the clipboard, saved state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsAppSettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val gw = FakeWaGateway()
    private val repo = FakeWaRepository()
    private val clipboard = FakeClipboardRepository()
    private val files = FakeFilesRepository()
    private val saved = SavedStateHandle()
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_" + "A".repeat(43))

    private fun status(state: InstanceState) = InstanceStatus("inst1", state, "905321234567@s.whatsapp.net", "Ada")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        FakeQr.install(gw.core)
        repo.gatewayOpener = { (gw.opener.open(link) as? Outcome.Ok)?.value }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = saved) =
        WhatsAppSettingsViewModel(
            app = AppId.Messages,
            machine = DemoSettingsMachine(),
            opener = gw.urlOpener,
            store = WaLinkStore(gw.ops),
            repo = repo,
            qr = NativeQrOps(gw.core),
            clipboard = clipboard,
            files = files,
            stagingDir = tmp.newFolder(),
            clock = { NOW },
            saved = handle,
            io = dispatcher,
        )

    private val WhatsAppSettingsViewModel.view get() = state.value.view

    private fun WhatsAppSettingsViewModel.button(id: String): SettingsButton =
        view.buttons.firstOrNull { it.id == id }
            ?: error("no button $id on ${view.kind}: ${view.buttons.map { it.id }}")

    /** What the screen does for a tap: the button's input, with the text of its field. */
    private fun WhatsAppSettingsViewModel.tap(
        id: String,
        text: String? = null,
    ) {
        val b = button(id)
        val input = b.input!!
        onAction(WaSettingsAction.Press(if (text != null) input.withText(text) else input))
    }

    private fun scriptLinkedStart(state: InstanceState = InstanceState.Connected) {
        gw.link = link
        gw.status = Outcome.Ok(status(state))
    }

    @Test
    fun startsOnTheTwoOptionsWithoutALink() =
        runTest {
            val vm = viewModel()
            assertEquals("choose", vm.view.kind)
            assertEquals(listOf("edit_url", "link_new", "connect_existing"), vm.view.buttons.map { it.id })
            assertTrue("nothing was opened without a link", gw.opened.isEmpty())
        }

    @Test
    fun linkNewWithQrThenConnected() =
        runTest {
            val vm = viewModel()
            vm.tap("link_new")
            assertEquals("link_new", vm.view.kind)
            vm.tap("start_qr")
            // CreateInstance ran on a gateway opened with no key, then it was closed; Save and Subscribe followed.
            assertEquals(listOf(DEFAULT_URL to null), gw.opened.toList())
            assertEquals(LinkRequest.Qr, gw.createRequests.single().link)
            assertEquals(StoredLink("https://rovsti.erdemdev.tr", "inst1", FakeWaGateway.NEW_KEY), repo.linked)
            assertTrue("subscribed", "start" in repo.log)
            assertTrue("the creating gateway was closed", FakeWaGateway.GATEWAY in gw.released)
            assertEquals("connecting", vm.view.kind)

            repo.emit(GatewayEvent.Qr("2@first", 20_000))
            assertEquals("show_qr", vm.view.kind)
            assertEquals("2@first", vm.view.qrPayload)
            assertNotNull("the QR matrix came from qr.encode", vm.state.value.qr)
            val first = vm.state.value.qr
            repo.emit(GatewayEvent.Qr("2@second", 20_000))
            assertTrue("a rotated QR is encoded again", vm.state.value.qr !== first)

            repo.emit(GatewayEvent.State("inst1", InstanceState.Connected, "905321234567@s.whatsapp.net", "Ada"))
            assertEquals("connected", vm.view.kind)
            assertEquals(
                "Ada",
                vm.view.rows
                    .single()
                    .label,
            )
            assertNull(vm.state.value.qr)
        }

    @Test
    fun pairingCodeNeedsTheNumberThenShowsTheCode() =
        runTest {
            val vm = viewModel()
            vm.tap("link_new")
            vm.tap("use_code")
            assertEquals(
                "phone",
                vm.view.fields
                    .single()
                    .id,
            )
            vm.tap("get_code", "+90 532 111 22 33")
            assertEquals(LinkRequest.PhoneNumber("+90 532 111 22 33"), gw.createRequests.single().link)
            repo.emit(GatewayEvent.PairCode("ABCD-1234", 60_000))
            assertEquals("show_code", vm.view.kind)
            assertEquals("ABCD-1234", vm.view.code)
            // Cancel gives the half-made instance up: the link is forgotten and the instance deleted.
            vm.tap("cancel")
            assertEquals("choose", vm.view.kind)
            assertTrue("forget" in repo.log)
            assertEquals(1, gw.count("wa.delete_instance"))
        }

    @Test
    fun badKeyThenGoodKey() =
        runTest {
            val vm = viewModel()
            vm.tap("connect_existing")
            vm.tap("connect", "hello")
            assertEquals("failed", vm.view.kind)
            assertEquals("connect key must start with \"rwk_\"", vm.view.error)
            assertTrue("a key that is not rwk_ never reached the gateway", gw.opened.isEmpty())

            vm.tap("retry")
            assertEquals("paste_key", vm.view.kind)
            // A well-formed key the gateway refuses.
            gw.status = Outcome.Err(RustError.Auth("revoked"))
            vm.tap("connect", link.key)
            assertEquals("failed", vm.view.kind)
            assertTrue(vm.view.error!!.contains("did not accept"))
            assertEquals(DEFAULT_URL to link.key, gw.opened.last())
            assertNull("a refused key is never saved", repo.linked)

            vm.tap("retry")
            gw.status = Outcome.Ok(status(InstanceState.Connected))
            vm.tap("connect", link.key)
            assertEquals("connected", vm.view.kind)
            assertEquals(link.key, repo.linked?.key)
            assertEquals("inst1", repo.linked?.instanceId)
        }

    @Test
    fun mintThenCopyMarksTheClipSensitive() =
        runTest {
            scriptLinkedStart()
            val vm = viewModel()
            assertEquals("connected", vm.view.kind)
            val toasts = mutableListOf<WaSettingsEvent>()
            val collector = launchCollect(vm, toasts)

            vm.tap("show_key")
            assertEquals("Messages key", gw.minted.single())
            assertEquals("share_key", vm.view.kind)
            assertEquals(FakeWaGateway.MINTED_KEY, vm.view.keyLines.joinToString(""))
            assertEquals(listOf(FakeWaGateway.MINTED_KEY to true), clipboard.copies)
            assertEquals(WaSettingsEvent.Toast("Copied"), toasts.single())

            vm.tap("copy_key")
            assertEquals(2, clipboard.copies.size)
            assertTrue("every copy of a key is sensitive", clipboard.copies.all { it.second })
            vm.onAction(WaSettingsAction.ShareKey)
            assertEquals(WaSettingsEvent.ShareText(FakeWaGateway.MINTED_KEY), toasts.last())
            vm.onAction(WaSettingsAction.ToggleKeyQr)
            assertNotNull(vm.state.value.keyQr)
            vm.tap("back")
            assertNull("leaving the page drops the key QR", vm.state.value.keyQr)
            collector.cancel()
        }

    @Test
    fun revokeWithConfirm() =
        runTest {
            scriptLinkedStart()
            gw.keys =
                Outcome.Ok(
                    listOf(
                        KeyInfo("a", "Messages a", 1, NOW - 120_000, current = true),
                        KeyInfo("b", "Phone b", 1, null, current = false),
                    ),
                )
            val vm = viewModel()
            vm.tap("keys")
            assertEquals("keys", vm.view.kind)
            assertEquals(listOf("this app · 2 min ago", "never used"), vm.view.keys.map { it.detail })

            // The row's menu sends the key's own input; the machine asks first.
            vm.onAction(WaSettingsAction.Press(vm.view.keys[1].revoke))
            assertEquals("confirm_revoke", vm.view.kind)
            assertEquals("the list stays the page behind the alert", "keys", vm.state.value.page.kind)
            assertTrue("nothing is revoked before the confirmation", gw.revoked.isEmpty())

            vm.tap("revoke_confirm")
            assertEquals(listOf("b"), gw.revoked.toList())
            assertEquals("keys", vm.view.kind)
            assertEquals(listOf("a"), vm.view.keys.map { it.keyId })
        }

    @Test
    fun deleteNeedsTwoTaps() =
        runTest {
            scriptLinkedStart()
            val vm = viewModel()
            vm.tap("delete")
            assertEquals("confirm_delete", vm.view.kind)
            assertEquals(0, gw.count("wa.delete_instance"))
            // Back out: still linked, nothing deleted.
            vm.tap("back")
            assertEquals("connected", vm.view.kind)
            assertEquals(0, gw.count("wa.delete_instance"))

            vm.tap("delete")
            vm.tap("delete_confirm")
            assertEquals(1, gw.count("wa.delete_instance"))
            assertTrue("the link is forgotten after the instance is gone", "forget" in repo.log)
            assertEquals("choose", vm.view.kind)
        }

    @Test
    fun loggedOutThenLinkAgain() =
        runTest {
            scriptLinkedStart(InstanceState.LoggedOut)
            val vm = viewModel()
            assertEquals("logged_out", vm.view.kind)
            vm.tap("relink_qr")
            assertEquals(1, gw.count("wa.relink"))
            assertEquals("connecting", vm.view.kind)
            repo.emit(GatewayEvent.Qr("2@again", 20_000))
            assertEquals("show_qr", vm.view.kind)
            repo.emit(GatewayEvent.State("inst1", InstanceState.Connected, null, "Ada"))
            assertEquals("connected", vm.view.kind)
        }

    @Test
    fun aKeyRevokedEventUnlinksAndAnErrorBecomesAFailurePage() =
        runTest {
            scriptLinkedStart()
            val vm = viewModel()
            repo.emit(GatewayEvent.KeyRevoked)
            assertEquals("failed", vm.view.kind)
            assertTrue("forget" in repo.log)

            // A network failure while verifying is a failure page with Try again, not a crash.
            val second = FakeWaGateway()
            second.link = link
            second.status = Outcome.Err(RustError.Network("no route to host"))
            val failing =
                WhatsAppSettingsViewModel(
                    AppId.Messages,
                    DemoSettingsMachine(),
                    second.urlOpener,
                    WaLinkStore(second.ops),
                    FakeWaRepository(),
                    NativeQrOps(second.core),
                    clipboard,
                    files,
                    tmp.newFolder(),
                    { NOW },
                    SavedStateHandle(),
                    dispatcher,
                )
            assertEquals("failed", failing.view.kind)
            assertEquals("no route to host", failing.view.error)
            assertEquals(
                "retry",
                failing.view.buttons
                    .first { it.role == "primary" }
                    .id,
            )
        }

    @Test
    fun savedStateRestoresThePage() =
        runTest {
            val first = viewModel()
            first.tap("connect_existing")
            assertEquals("paste_key", first.view.kind)
            assertNotNull("the model JSON is in the handle", saved.get<String>(WhatsAppSettingsViewModel.MODEL_KEY))

            // A recreated process: a new view model on the same handle lands on the same page, without a fresh start.
            val second = viewModel(saved)
            assertEquals("paste_key", second.view.kind)
            second.tap("back")
            assertEquals("choose", second.view.kind)
        }

    @Test
    fun aRestoredWorkingPageStartsOver() =
        runTest {
            // The process died while "connecting": its effect is gone, so waiting would be forever.
            scriptLinkedStart()
            val request =
                SettingsApplyRequest(
                    app = AppId.Messages,
                    nowMs = NOW,
                    link = SettingsLink(link.gatewayUrl, link.instanceId, link.key),
                )
            val busy = (DemoSettingsMachine().apply(request) as Outcome.Ok).value
            assertTrue(busy.view.busy)
            val interrupted = SavedStateHandle()
            interrupted[WhatsAppSettingsViewModel.MODEL_KEY] =
                Envelope.json.encodeToString(JsonElement.serializer(), busy.model)
            val restored = viewModel(interrupted)
            assertEquals("connected", restored.view.kind)
        }

    @Test
    fun shareQrStagesAPngAndAsksTheHostToShareIt() =
        runTest {
            val vm = viewModel()
            val events = mutableListOf<WaSettingsEvent>()
            val collector = launchCollect(vm, events)
            vm.tap("link_new")
            vm.tap("start_qr")
            repo.emit(GatewayEvent.Qr("2@share", 20_000))
            files.staged = Uri.parse("content://dev.rovsti.test.rovstishare/share/whatsapp-qr.png")
            vm.onAction(WaSettingsAction.ShareQr)
            val staged = files.stagedPaths.single()
            assertEquals("whatsapp-qr.png", staged.second)
            assertTrue("a PNG was written", java.io.File(staged.first).length() > 100)
            assertEquals(WaSettingsEvent.ShareImage(files.staged!!), events.single())
            collector.cancel()
        }

    @Test
    fun pasteAsksTheScreenToFillTheKeyField() =
        runTest {
            val vm = viewModel()
            vm.tap("connect_existing")
            clipboard.text = link.key
            vm.onAction(WaSettingsAction.PasteClipboard)
            val request = vm.state.value.paste
            assertEquals(link.key, request?.text)
            vm.onAction(WaSettingsAction.PasteDone(request!!.id))
            assertNull(vm.state.value.paste)
            clipboard.text = null
            val events = mutableListOf<WaSettingsEvent>()
            val collector = launchCollect(vm, events)
            vm.onAction(WaSettingsAction.PasteClipboard)
            assertEquals(WaSettingsEvent.Toast("Nothing to paste"), events.single())
            collector.cancel()
        }

    @Test
    fun theRealWrapperAlsoReachesTheMachineThroughCore() =
        runTest {
            // NativeWaSettingsOps over FakeRustCore: the wire request carries the app and the clock.
            gw.core.on("wa.settings_apply") { Outcome.Err(RustError.Invalid("model: bad")) }
            val vm =
                WhatsAppSettingsViewModel(
                    AppId.Phone,
                    NativeWaSettingsOps(gw.core),
                    gw.urlOpener,
                    WaLinkStore(gw.ops),
                    repo,
                    NativeQrOps(gw.core),
                    clipboard,
                    files,
                    tmp.newFolder(),
                    { NOW },
                    SavedStateHandle(),
                    dispatcher,
                )
            assertEquals("a refused call keeps the placeholder page", "connecting", vm.view.kind)
            val sent =
                gw.core.calls
                    .first { it.op == "wa.settings_apply" }
                    .request
                    .toString()
            assertTrue(sent, sent.contains("\"app\":\"phone\"") && sent.contains("\"now_ms\":$NOW"))
        }

    private fun TestScope.launchCollect(
        vm: WhatsAppSettingsViewModel,
        into: MutableList<WaSettingsEvent>,
    ) = backgroundScope.launch(dispatcher) { vm.events.collect { into += it } }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val DEFAULT_URL = "https://rovsti.erdemdev.tr"
    }
}
