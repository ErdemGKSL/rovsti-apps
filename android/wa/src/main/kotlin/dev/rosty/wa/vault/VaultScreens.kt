package dev.rosty.wa.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.rosty.design.chrome.ScreenPage
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.TextButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.wa.R

// The WhatsApp vault screens (plan 4, 5, 13.2): setup and change (the passphrase shown once, two groups typed back),
// unlock (biometric, or the passphrase), and "Forgot passphrase" / a vault that can no longer be opened. Each one is
// stateless: [VaultFlow] owns what happens, these draw it.

/** Test tags of the vault screens. */
object VaultTags {
    const val Setup = "vault-setup"
    const val Unlock = "vault-unlock"
    const val Forgot = "vault-forgot"
    const val PassphraseCard = "vault-passphrase"
    const val PassphraseField = "vault-passphrase-field"
    const val Copy = "vault-copy"
    const val Saved = "vault-saved"
    const val Confirm = "vault-confirm"
    const val ShowAgain = "vault-show-again"
    const val Biometric = "vault-biometric"
    const val UsePassphrase = "vault-use-passphrase"
    const val UnlockPassphrase = "vault-unlock-passphrase"
    const val ForgotLink = "vault-forgot-link"
    const val Reset = "vault-reset"
    const val Problem = "vault-problem"

    fun group(index: Int) = "vault-group-$index"
}

/** Where the setup is: the passphrase on show, or two of its groups typed back. */
enum class SetupStep { Show, Confirm }

/** Text sits where the text of a grouped row sits (card inset plus row gutter), as in the WhatsApp settings. */
private val TextInset = Spacing.gutter + Spacing.gutter
private val BlockGap = Spacing.sectionGap - Spacing.s
private const val LARGE_FONT = 1.3f
private const val GROUPS_PER_ROW = 4
private const val GROUPS_PER_ROW_LARGE = 2

/**
 * Setup ([change] false) or a new passphrase ([change] true). [typed] are the fields of the groups [ask] names; [onCancel]
 * is the back button (a change can be left, a setup cannot: WhatsApp stays closed until it is done).
 */
@Composable
fun VaultSetupScreen(
    groups: List<String>,
    ask: List<Int>,
    step: SetupStep,
    change: Boolean,
    busy: Boolean,
    problem: VaultProblem?,
    typed: List<TextFieldState>,
    onCopy: () -> Unit,
    onSaved: () -> Unit,
    onShowAgain: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    SecureWindow()
    ScreenPage(
        title = stringResource(if (change) R.string.vault_change_title else R.string.vault_setup_title),
        modifier = modifier.testTag(VaultTags.Setup),
        onBack = onCancel,
        showBack = onCancel != null,
    ) {
        Note(stringResource(if (change) R.string.vault_change_intro else R.string.vault_setup_intro))
        when (step) {
            SetupStep.Show -> {
                PassphraseCard(groups, Modifier.padding(top = BlockGap))
                Note(stringResource(R.string.vault_setup_save_note))
                InsetGroupedSection(Modifier.padding(top = BlockGap)) {
                    row {
                        ListRow(
                            title = stringResource(R.string.vault_copy),
                            titleColor = RostyTheme.colors.accent,
                            modifier = Modifier.testTag(VaultTags.Copy),
                            onClick = onCopy,
                        )
                    }
                }
                Primary(stringResource(R.string.vault_saved_it), VaultTags.Saved, enabled = true, onClick = onSaved)
            }

            SetupStep.Confirm -> {
                val numbers = ask.map { it + 1 }
                Note(stringResource(R.string.vault_confirm_note, numbers.first(), numbers.last()))
                for ((i, index) in ask.withIndex()) {
                    GlassTextField(
                        state = typed[i],
                        placeholder = stringResource(R.string.vault_group, index + 1),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.gutter)
                                .padding(top = if (i == 0) BlockGap else Spacing.s)
                                .testTag(VaultTags.group(i)),
                        enabled = !busy,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                            ),
                        onSubmit = onConfirm,
                    )
                }
                ProblemLine(problem)
                Primary(
                    stringResource(if (change) R.string.vault_use_new else R.string.vault_protect),
                    VaultTags.Confirm,
                    enabled = !busy,
                    onClick = onConfirm,
                )
                Secondary(stringResource(R.string.vault_show_again), VaultTags.ShowAgain, onShowAgain)
            }
        }
        if (busy) Note(stringResource(R.string.vault_working))
    }
}

/**
 * The locked WhatsApp. [Locked.biometric]: one button opens the biometric prompt (the page also opens it on its own),
 * and the passphrase is one tap away. Otherwise the passphrase field, with the reason it is needed.
 */
@Composable
fun VaultUnlockScreen(
    title: String,
    state: WaVaultState.Locked,
    usePassphrase: Boolean,
    busy: Boolean,
    problem: VaultProblem?,
    entry: PassphraseEntry,
    onBiometric: () -> Unit,
    onUsePassphrase: () -> Unit,
    onUnlock: () -> Unit,
    onForgot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureWindow()
    ScreenPage(title = title, modifier = modifier.testTag(VaultTags.Unlock), showBack = false) {
        Heading(stringResource(R.string.vault_locked_title))
        if (state.biometric && !usePassphrase) {
            Centered(stringResource(R.string.vault_locked_biometric))
            Primary(stringResource(R.string.vault_unlock), VaultTags.Biometric, enabled = !busy, onClick = onBiometric)
            ProblemLine(problem)
            Secondary(stringResource(R.string.vault_use_passphrase), VaultTags.UsePassphrase, onUsePassphrase)
        } else {
            Centered(stringResource(reasonText(state.reason)))
            PassphraseField(
                entry,
                onSubmit = onUnlock,
                modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = BlockGap),
                enabled = !busy,
            )
            Note(stringResource(R.string.vault_passphrase_note))
            ProblemLine(problem)
            Primary(
                stringResource(R.string.vault_unlock),
                VaultTags.UnlockPassphrase,
                enabled = !busy,
                onClick = onUnlock,
            )
            Secondary(stringResource(R.string.vault_forgot), VaultTags.ForgotLink, onForgot)
        }
    }
}

/**
 * "Forgot passphrase" (plan 5), and the vault that can no longer be opened ([broken]): what a reset does and does not
 * lose, honestly, and the one way forward.
 */
@Composable
fun VaultForgotScreen(
    broken: Boolean,
    busy: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    SecureWindow()
    ScreenPage(
        title = stringResource(if (broken) R.string.vault_broken_title else R.string.vault_forgot_title),
        modifier = modifier.testTag(VaultTags.Forgot),
        onBack = onCancel,
        showBack = onCancel != null,
    ) {
        if (broken) {
            Body(stringResource(R.string.vault_broken_body))
        } else {
            Body(stringResource(R.string.vault_forgot_body1))
            Body(stringResource(R.string.vault_forgot_body2))
        }
        Body(stringResource(R.string.vault_forgot_body3))
        CapsuleButton(
            text = stringResource(R.string.vault_reset),
            onClick = onReset,
            style = CapsuleStyle.Filled,
            enabled = !busy,
            tint = RostyTheme.colors.destructive,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter)
                    .padding(top = BlockGap)
                    .testTag(VaultTags.Reset),
        )
        if (onCancel != null) Secondary(stringResource(R.string.vault_cancel), "vault-forgot-cancel", onCancel)
    }
}

/** The passphrase in groups of four, monospaced, four groups a row (two at a large font size). */
@Composable
fun PassphraseCard(
    groups: List<String>,
    modifier: Modifier = Modifier,
) {
    val style = RostyTheme.type.title2.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
    val perRow = if (LocalDensity.current.fontScale > LARGE_FONT) GROUPS_PER_ROW_LARGE else GROUPS_PER_ROW
    val spoken = groups.joinToString(", ") { it.toList().joinToString(" ") }
    Box(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(RostyTheme.colors.groupedSurface, RoundedCornerShape(Spacing.xl))
                .padding(vertical = Spacing.xl, horizontal = Spacing.l)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .testTag(VaultTags.PassphraseCard),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            for (line in groups.chunked(perRow)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    for (group in line) RostyText(group, style = style, maxLines = 1)
                }
            }
        }
    }
}

/** The words of a [VaultProblem]. */
fun problemText(problem: VaultProblem): Int =
    when (problem) {
        VaultProblem.WrongPassphrase -> R.string.vault_problem_wrong
        VaultProblem.Typo -> R.string.vault_problem_typo
        VaultProblem.Malformed -> R.string.vault_problem_malformed
        VaultProblem.GroupsDiffer -> R.string.vault_problem_groups
        VaultProblem.NoScreenLock -> R.string.vault_problem_screen_lock
        VaultProblem.LockedOut -> R.string.vault_problem_locked_out
        VaultProblem.NeedsPassphrase -> R.string.vault_reason_none
        VaultProblem.Broken -> R.string.vault_broken_body
        VaultProblem.Failed -> R.string.vault_problem_failed
    }

/** Why the passphrase is needed. */
fun reasonText(reason: PassphraseReason?): Int =
    when (reason) {
        null -> R.string.vault_reason_none
        PassphraseReason.Idle -> R.string.vault_reason_idle
        PassphraseReason.ClockBack -> R.string.vault_reason_clock
        PassphraseReason.BiometricChanged -> R.string.vault_reason_biometric
        PassphraseReason.TooManyAttempts -> R.string.vault_reason_attempts
        PassphraseReason.NewPassphrase -> R.string.vault_reason_new
    }

// ---- pieces

@Composable
private fun Note(text: String) {
    RostyText(
        text,
        modifier = Modifier.padding(horizontal = TextInset).padding(top = Spacing.m),
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.labelSecondary,
    )
}

@Composable
private fun Body(text: String) {
    RostyText(
        text,
        modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.m),
        style = RostyTheme.type.body,
    )
}

@Composable
private fun ColumnScope.Heading(text: String) {
    RostyIcon(
        RostyIcons.Shield,
        null,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = BlockGap),
        tint = RostyTheme.colors.labelSecondary,
        size = Sizes.emptyGlyph,
    )
    RostyText(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.m),
        style = RostyTheme.type.title3,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Centered(text: String) {
    RostyText(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.s),
        style = RostyTheme.type.subheadline,
        color = RostyTheme.colors.labelSecondary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ProblemLine(problem: VaultProblem?) {
    if (problem == null) return
    RostyText(
        stringResource(problemText(problem)),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(top = Spacing.m)
                .testTag(VaultTags.Problem),
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.destructive,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Primary(
    text: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    CapsuleButton(
        text = text,
        onClick = onClick,
        style = CapsuleStyle.Filled,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(top = BlockGap)
                .testTag(tag),
    )
}

@Composable
private fun ColumnScope.Secondary(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    TextButton(
        text,
        onClick = onClick,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Spacing.s).testTag(tag),
    )
}
