package dev.rosty.shell.wa

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.model.wa.AppId
import dev.rosty.core.ops.NativeQrOps
import dev.rosty.core.ops.NativeWaSettingsOps
import dev.rosty.shell.ShellContainer
import dev.rosty.wa.WaRepository
import dev.rosty.wa.settings.WhatsAppSettingsRoute
import dev.rosty.wa.settings.WhatsAppSettingsViewModel
import dev.rosty.wa.vault.SetupFlow
import dev.rosty.wa.vault.VaultSettingsRepository
import dev.rosty.wa.vault.WaVaultGate
import dev.rosty.wa.vault.WaVaultState
import dev.rosty.wa.vault.vaultSettingsItems
import kotlinx.coroutines.launch

/**
 * [content] behind the WhatsApp vault of the app (`plans/wa-vault.md` 13.3): the link flow while nothing is linked, the
 * setup as soon as a link waits for its vault, the unlock page while locked, the reset when broken; every vault page
 * and [content] while unlocked keep the window secure (FLAG_SECURE).
 */
@Composable
fun WaVaultGated(
    container: ShellContainer,
    title: String,
    content: @Composable () -> Unit,
) {
    val state by container.waVault.state.collectAsState()
    WaVaultGate(state, container.vaultFlow, title, content)
}

/**
 * Settings > WhatsApp of every Rosty app, behind its vault: the shared link screen of `:wa` (link, keys, relink,
 * delete; a new link goes into the vault, never into `whatsapp-link.json`), "Change passphrase" in place of the page
 * while it runs, and the vault group (lock now, auto-lock, a new passphrase) on the page of a linked account.
 * [repo] is the repository the page unlinks through (Messages passes the one that also drops its offline store);
 * [linked] adds the app's own blocks under the vault group.
 */
@Composable
fun WhatsAppVaultSettings(
    container: ShellContainer,
    app: AppId,
    title: String,
    repo: WaRepository = container.wa,
    linked: (LazyListScope.() -> Unit)? = null,
) {
    val state by container.waVault.state.collectAsState()
    val flow = container.vaultFlow
    WaVaultGate(state, flow, title) {
        if (flow.changing) {
            SetupFlow(flow, change = true)
        } else {
            WhatsAppSettingsBody(container, app, repo, unlocked = state == WaVaultState.Unlocked, linked)
        }
    }
}

@Composable
private fun WhatsAppSettingsBody(
    container: ShellContainer,
    app: AppId,
    repo: WaRepository,
    unlocked: Boolean,
    linked: (LazyListScope.() -> Unit)?,
) {
    val context = LocalContext.current
    val owner = remember(context) { context.componentActivity() }
    val viewModel =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        WhatsAppSettingsViewModel(
                            app = app,
                            machine = NativeWaSettingsOps(container.rust),
                            ops = container.waOps,
                            // The vault's link (no key: the gateway takes it from the open vault).
                            store = container.waLinkSource,
                            // A new link goes into the vault (its setup follows); unlinking resets the vault.
                            repo = VaultSettingsRepository(repo, container.waVault),
                            qr = NativeQrOps(container.rust),
                            clipboard = container.clipboard,
                            files = container.files,
                            stagingDir = container.context.cacheDir,
                            clock = System::currentTimeMillis,
                            saved = SavedStateHandle(),
                            // "Use the link of ...": a sibling whose vault is unlocked hands its link over (13.3).
                            handover = container.waLinkHandover,
                        )
                    }
                }
            ViewModelProvider(owner, factory)[WhatsAppSettingsViewModel::class.java]
        }
    // A link made, pasted or handed over on this page went through the vault's setup: show it once it is open.
    LaunchedEffect(unlocked) { if (unlocked) viewModel.vaultOpened() }
    val vault = rememberVaultSettingsBlock(container, unlocked)
    WhatsAppSettingsRoute(viewModel, linked = {
        vault()
        linked?.invoke(this)
    })
}

/** The vault group of a linked account's page while the vault is open: lock now, the auto-lock delay, a passphrase. */
@Composable
private fun rememberVaultSettingsBlock(
    container: ShellContainer,
    unlocked: Boolean,
): LazyListScope.() -> Unit {
    val vault = container.waVault
    val scope = rememberCoroutineScope()
    var autoLock by remember { mutableLongStateOf(vault.autoLockMs) }
    return {
        if (unlocked) {
            vaultSettingsItems(
                autoLockMs = autoLock,
                onLockNow = { scope.launch { vault.lock() } },
                onAutoLock = { ms ->
                    vault.autoLockMs = ms
                    autoLock = ms
                },
                onChange = container.vaultFlow::beginChange,
            )
        }
    }
}

private tailrec fun Context.componentActivity(): ComponentActivity =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.componentActivity()
        else -> error("WhatsApp settings need a ComponentActivity")
    }
