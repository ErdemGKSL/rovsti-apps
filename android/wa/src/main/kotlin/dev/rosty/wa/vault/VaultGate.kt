package dev.rosty.wa.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.core.Outcome
import dev.rosty.design.chrome.ScreenPage
import dev.rosty.design.controls.TextButton
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.ListRowChevron
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.confirmDestructive
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.overlay.showToast
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.wa.R
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the vault screens show and do, over one [WaVault]: the passphrase offer of a setup or a change, the step, the
 * problem of the last try, and whether a system prompt is up. Operations run in [scope] (the app's), so leaving the
 * page does not cut a commit in half.
 */
@Stable
class VaultFlow(
    private val vault: WaVault,
    private val scope: CoroutineScope,
    /** Copies the passphrase through the sensitive path and clears it after a minute. */
    private val copySecret: (String) -> Boolean,
) {
    var offer by mutableStateOf<PassphraseOffer?>(null)
        private set
    var step by mutableStateOf(SetupStep.Show)
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<VaultProblem?>(null)
        private set
    var usePassphrase by mutableStateOf(false)
        private set
    var forgot by mutableStateOf(false)
        private set

    /** A passphrase change is on screen (it replaces the WhatsApp settings while it runs). */
    var changing by mutableStateOf(false)
        private set

    val typed: List<TextFieldState> = listOf(TextFieldState(), TextFieldState())
    val entry = PassphraseEntry()
    private var biometricAsked = false

    /** The hand-over and the automatic prompt were tried for this lock. */
    private var autoTried = false

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    private fun outcome(result: VaultOutcome) {
        problem = (result as? VaultOutcome.Refused)?.problem
        if (problem == VaultProblem.NeedsPassphrase) {
            problem = null
            usePassphrase = true
        }
    }

    fun beginSetup() {
        if (offer != null) return
        run {
            when (val started = vault.startSetup()) {
                is Outcome.Ok -> show(started.value)
                is Outcome.Err -> problem = VaultProblem.Failed
            }
        }
    }

    fun beginChange() {
        changing = true
        offer = null
        run {
            when (val started = vault.startChange()) {
                is Outcome.Ok -> show(started.value)
                is Outcome.Err -> problem = VaultProblem.Failed
            }
        }
    }

    private fun show(next: PassphraseOffer) {
        offer = next
        step = SetupStep.Show
        problem = null
        typed.forEach { it.clearText() }
    }

    fun cancelChange() {
        offer?.let { left -> scope.launch { vault.abandon(left) } }
        offer = null
        changing = false
        problem = null
        typed.forEach { it.clearText() }
    }

    fun copy(): Boolean = offer?.let { copySecret(it.passphrase) } ?: false

    fun saved() {
        step = SetupStep.Confirm
        problem = null
    }

    fun showAgain() {
        step = SetupStep.Show
        problem = null
        typed.forEach { it.clearText() }
    }

    fun confirm() {
        val current = offer ?: return
        val groups = typed.map { it.text.toString() }
        run {
            val result = vault.confirm(current, groups)
            outcome(result)
            if (result == VaultOutcome.Done) {
                offer = null
                changing = false
                typed.forEach { it.clearText() }
            }
        }
    }

    fun unlockWithBiometric() {
        biometricAsked = true
        run { outcome(vault.unlockWithBiometric()) }
    }

    /**
     * Once per lock, as soon as the locked page shows: a sibling Rosty app that is unlocked opens this vault with no
     * prompt (round 7a); otherwise the biometric prompt opens when the daily unlock is there. The person never sees
     * two prompts in a row for one unlock.
     */
    fun autoUnlock(biometric: Boolean) {
        if (autoTried) return
        autoTried = true
        run {
            if (vault.unlockFromSibling()) return@run
            if (biometric && !biometricAsked && !usePassphrase) {
                biometricAsked = true
                outcome(vault.unlockWithBiometric())
            }
        }
    }

    fun choosePassphrase() {
        usePassphrase = true
        problem = null
    }

    fun unlockWithPassphrase() {
        val text = entry.text
        run {
            outcome(vault.unlockWithPassphrase(text))
            entry.clear()
        }
    }

    fun showForgot(show: Boolean) {
        forgot = show
        problem = null
    }

    fun reset() {
        run {
            outcome(vault.reset())
            forgot = false
        }
    }

    /** The vault locked again: the next locked page asks for the biometric again. */
    fun relocked() {
        biometricAsked = false
        autoTried = false
        usePassphrase = false
        problem = null
        forgot = false
        entry.clear()
    }
}

/**
 * The WhatsApp screens behind the vault. [content] shows only while there is nothing to protect yet ([WaVaultState.NoLink]:
 * the link flow) or the vault is open; a plaintext link waits for its setup, a locked vault shows the unlock page, a
 * broken one the reset. Every page here, and [content] while unlocked, keeps the window secure.
 */
@Composable
fun WaVaultGate(
    state: WaVaultState,
    flow: VaultFlow,
    title: String,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(state is WaVaultState.Locked) { if (state is WaVaultState.Locked) flow.relocked() }
    when (state) {
        WaVaultState.Checking -> {
            SecureWindow()
            ScreenPage(title = title, showBack = false) {}
        }

        WaVaultState.NoLink -> {
            content()
        }

        WaVaultState.Unlocked -> {
            SecureWindow()
            content()
        }

        WaVaultState.NeedsSetup -> {
            SetupFlow(flow, change = false)
        }

        is WaVaultState.Locked -> {
            if (flow.forgot) {
                BackHandler { flow.showForgot(false) }
                ForgotFlow(flow, broken = false)
            } else {
                LaunchedEffect(state.biometric) { flow.autoUnlock(state.biometric) }
                VaultUnlockScreen(
                    title = title,
                    state = state,
                    usePassphrase = flow.usePassphrase,
                    busy = flow.busy,
                    problem = flow.problem,
                    entry = flow.entry,
                    onBiometric = flow::unlockWithBiometric,
                    onUsePassphrase = flow::choosePassphrase,
                    onUnlock = flow::unlockWithPassphrase,
                    onForgot = { flow.showForgot(true) },
                )
            }
        }

        WaVaultState.Broken -> {
            ForgotFlow(flow, broken = true)
        }
    }
}

/** The setup of a new vault, or ([change]) a new passphrase for the open one. */
@Composable
fun SetupFlow(
    flow: VaultFlow,
    change: Boolean,
) {
    val overlay = LocalOverlayOrNull.current
    val copied = stringResource(R.string.vault_copied)
    if (!change) LaunchedEffect(Unit) { flow.beginSetup() }
    if (change) BackHandler { flow.cancelChange() }
    val offer = flow.offer
    if (offer == null) {
        SecureWindow()
        val title = stringResource(if (change) R.string.vault_change_title else R.string.vault_setup_title)
        ScreenPage(title = title, onBack = if (change) flow::cancelChange else null, showBack = change) {
            flow.problem?.let { problem ->
                RostyText(
                    stringResource(problemText(problem)),
                    modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.m),
                    color = RostyTheme.colors.destructive,
                )
                Box(Modifier.padding(top = Spacing.s).align(Alignment.CenterHorizontally)) {
                    TextButton(stringResource(R.string.vault_retry_short), onClick = {
                        if (change) flow.beginChange() else flow.beginSetup()
                    })
                }
            }
        }
        return
    }
    VaultSetupScreen(
        groups = offer.groups,
        ask = offer.ask,
        step = flow.step,
        change = change,
        busy = flow.busy,
        problem = flow.problem,
        typed = flow.typed,
        onCopy = { if (flow.copy()) overlay?.showToast(copied) },
        onSaved = flow::saved,
        onShowAgain = flow::showAgain,
        onConfirm = flow::confirm,
        onCancel = if (change) flow::cancelChange else null,
    )
}

@Composable
private fun ForgotFlow(
    flow: VaultFlow,
    broken: Boolean,
) {
    val overlay = LocalOverlayOrNull.current
    val res = LocalResources.current
    VaultForgotScreen(
        broken = broken,
        busy = flow.busy,
        onReset = {
            if (overlay == null) {
                flow.reset()
            } else {
                overlay.confirmDestructive(
                    destructiveLabel = res.getString(R.string.vault_reset),
                    onConfirm = flow::reset,
                    title = res.getString(R.string.vault_reset_confirm),
                    message = res.getString(R.string.vault_reset_confirm_detail),
                    cancelLabel = res.getString(R.string.vault_cancel),
                )
            }
        },
        onCancel = if (broken) null else ({ flow.showForgot(false) }),
    )
}

/** The words of an auto-lock delay. */
@Composable
fun autoLockLabel(ms: Long): String =
    when {
        ms <= 0L -> stringResource(R.string.vault_auto_lock_now)
        ms < MINUTE_MS -> stringResource(R.string.vault_auto_lock_seconds, (ms / SECOND_MS).toInt())
        else -> stringResource(R.string.vault_auto_lock_minutes, (ms / MINUTE_MS).toInt())
    }

/** The vault group of the WhatsApp settings of a linked account: lock now, the auto-lock delay, a new passphrase. */
fun LazyListScope.vaultSettingsItems(
    autoLockMs: Long,
    onLockNow: () -> Unit,
    onAutoLock: (Long) -> Unit,
    onChange: () -> Unit,
) {
    item(key = "vault") {
        VaultSettingsSection(autoLockMs, onLockNow, onAutoLock, onChange)
    }
}

@Composable
fun VaultSettingsSection(
    autoLockMs: Long,
    onLockNow: () -> Unit,
    onAutoLock: (Long) -> Unit,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overlay = LocalOverlayOrNull.current
    val labels = WaVault.AUTO_LOCK_CHOICES_MS.map { it to autoLockLabel(it) }
    val items =
        remember(labels, autoLockMs) {
            labels
                .map { (ms, label) -> MenuItem.Action(label, checked = ms == autoLockMs, onClick = { onAutoLock(ms) }) }
                .toPersistentList()
        }
    val current = autoLockLabel(autoLockMs)
    InsetGroupedSection(
        modifier.padding(top = Spacing.sectionGap - Spacing.s).testTag("vault-settings"),
        header = stringResource(R.string.vault_settings_section),
        footer = stringResource(R.string.vault_settings_footer),
    ) {
        row {
            ListRow(
                title = stringResource(R.string.vault_lock_now),
                titleColor = RostyTheme.colors.accent,
                modifier = Modifier.testTag("vault-lock-now"),
                onClick = onLockNow,
            )
        }
        row {
            val trigger =
                if (overlay != null) {
                    Modifier.menuTrigger(overlay, items, label = stringResource(R.string.vault_auto_lock))
                } else {
                    Modifier
                }
            ListRow(
                title = stringResource(R.string.vault_auto_lock),
                modifier = trigger.testTag("vault-auto-lock"),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        RostyText(current, color = RostyTheme.colors.labelSecondary, maxLines = 1)
                        ListRowChevron()
                    }
                },
            )
        }
        row {
            ListRow(
                title = stringResource(R.string.vault_change),
                modifier = Modifier.testTag("vault-change"),
                onClick = onChange,
                trailing = { ListRowChevron() },
            )
        }
    }
}

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
