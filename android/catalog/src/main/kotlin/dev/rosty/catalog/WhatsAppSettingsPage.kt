package dev.rosty.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.AppId
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.KeyInfo
import dev.rosty.core.model.wa.LinkRequest
import dev.rosty.core.ops.NativeQrOps
import dev.rosty.platform.files.FakeClipboardRepository
import dev.rosty.platform.files.FakeFilesRepository
import dev.rosty.wa.DemoSettingsMachine
import dev.rosty.wa.FakeQr
import dev.rosty.wa.FakeWaGateway
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaLinkStore
import dev.rosty.wa.settings.WhatsAppSettingsRoute
import dev.rosty.wa.settings.WhatsAppSettingsViewModel

/**
 * E3.10: the shared WhatsApp settings screen on the real view model. The gateway is [FakeWaGateway] and the state
 * machine a Kotlin stand-in, so every path can be walked in the APK: link with a QR (the QR is a stand-in and does
 * not scan), pairing code, paste a key (any `rwk_...` text is accepted), show and copy a key, revoke, unlink, delete.
 */
@Composable
internal fun WhatsAppSettingsPage() {
    val context = LocalContext.current
    val viewModel =
        remember {
            val gateway = FakeWaGateway()
            FakeQr.install(gateway.core)
            gateway.status =
                Outcome.Ok(InstanceStatus("demo", InstanceState.Connected, "905321234567@s.whatsapp.net", "Ada"))
            gateway.keys =
                Outcome.Ok(
                    listOf(
                        KeyInfo("a", "Messages a", 0, DEMO_NOW - 120_000, current = true),
                        KeyInfo("b", "Phone b", 0, null, current = false),
                    ),
                )
            val repo = FakeWaRepository()
            repo.gatewayOpener =
                { (gateway.urlOpener.open("https://rovsti.erdemdev.tr", "rwk_demo") as? Outcome.Ok)?.value }
            // What the gateway sends after an instance is created: a QR, or a code when a number was given.
            repo.onRelinkTo = {
                val asked = gateway.createRequests.lastOrNull()?.link
                repo.emit(
                    if (asked is LinkRequest.PhoneNumber) {
                        GatewayEvent.PairCode(
                            "WXYZ-5678",
                            60_000,
                        )
                    } else {
                        GatewayEvent.Qr("2@demo-${gateway.createRequests.size}", 20_000)
                    },
                )
            }
            WhatsAppSettingsViewModel(
                app = AppId.Messages,
                machine = DemoSettingsMachine(),
                opener = gateway.urlOpener,
                store = WaLinkStore(gateway.ops),
                repo = repo,
                qr = NativeQrOps(gateway.core),
                clipboard = FakeClipboardRepository(),
                files = FakeFilesRepository(),
                stagingDir = context.cacheDir,
                clock = { DEMO_NOW },
            )
        }
    WhatsAppSettingsRoute(viewModel)
}

private const val DEMO_NOW = 1_760_000_000_000L
