package dev.rosty.messages.spam

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.phone.BlockedNumber
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One rule as the list shows it; the label is worded by the page from [kind] and [text]. */
@Immutable
data class RuleRow(
    val index: Int,
    val kind: RuleKind,
    val text: String,
    /** False only for a short-code rule saved switched off by the GPUI app. */
    val on: Boolean = true,
)

/** The Spam filter page (E6.11, AD-MSG8). */
@Immutable
data class SpamSettingsUi(
    val loaded: Boolean = false,
    val enabled: Boolean = true,
    val rules: ImmutableList<RuleRow> = persistentListOf(),
    val allow: ImmutableList<String> = persistentListOf(),
    /** The system blocklist, as the numbers were typed. */
    val blocked: ImmutableList<String> = persistentListOf(),
    /** Whether this app may write the system blocklist (it holds the SMS role). */
    val canBlock: Boolean = false,
    /** M47. */
    val deleteCodes: Boolean = false,
)

sealed interface SpamSettingsAction {
    data class SetEnabled(
        val on: Boolean,
    ) : SpamSettingsAction

    /** Adds a rule ([index] null) or replaces rule [index]; [done] hears why it was refused, or null. */
    data class SaveRule(
        val index: Int?,
        val kind: RuleKind,
        val text: String,
        val done: (RuleProblem?) -> Unit = {},
    ) : SpamSettingsAction

    data class DeleteRule(
        val index: Int,
    ) : SpamSettingsAction

    /** [done] hears whether the sender was added (false: blank or already allowed). */
    data class Allow(
        val sender: String,
        val done: (Boolean) -> Unit = {},
    ) : SpamSettingsAction

    data class RemoveAllowed(
        val index: Int,
    ) : SpamSettingsAction

    data class Block(
        val number: String,
        val done: (BlockOutcome) -> Unit = {},
    ) : SpamSettingsAction

    data class Unblock(
        val number: String,
    ) : SpamSettingsAction

    /** M47 on or off; the page asks before turning it on. */
    data class SetDeleteCodes(
        val on: Boolean,
    ) : SpamSettingsAction
}

sealed interface SpamSettingsEffect {
    data class Toast(
        val text: UiText,
    ) : SpamSettingsEffect

    data class Undo(
        val text: UiText,
        val undo: () -> Unit,
    ) : SpamSettingsEffect
}

/** What the page reads; the container wires the real ones, tests pass fakes. */
class SpamSources(
    val actions: SpamActions,
    val prefs: Flow<MessagesPrefs?>,
    val blocked: Flow<List<BlockedNumber>>,
    val cleanup: CodeCleanup,
)

/**
 * The Spam filter page's state: the rules, the allow list and the switch from `messages-prefs.json`, the system
 * blocklist, and M47. Every change goes through [SpamActions] (Rust decides) and answers a pill, with Undo for deletes.
 */
class SpamSettingsViewModel(
    private val sources: SpamSources,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(SpamSettingsUi())
    val state: StateFlow<SpamSettingsUi> = stateFlow.asStateFlow()

    private val effectChannel = Channel<SpamSettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SpamSettingsEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch { sources.cleanup.load() }
        viewModelScope.launch {
            combine(
                sources.prefs,
                sources.blocked.onStart { emit(emptyList()) },
                sources.cleanup.enabled,
            ) { prefs, blocked, codes -> Triple(prefs, blocked, codes) }
                .collect { (prefs, blocked, codes) ->
                    stateFlow.update { it.from(prefs, blocked, codes) }
                }
        }
        viewModelScope.launch {
            val can = sources.actions.canBlock()
            stateFlow.update { it.copy(canBlock = can) }
        }
    }

    private fun SpamSettingsUi.from(
        prefs: MessagesPrefs?,
        blocked: List<BlockedNumber>,
        codes: Boolean,
    ): SpamSettingsUi {
        val spam = prefs?.spam
        return copy(
            loaded = prefs != null,
            enabled = spam?.enabled ?: true,
            rules =
                spam
                    ?.rules
                    .orEmpty()
                    .mapIndexed(::rowOf)
                    .toImmutableList(),
            allow = spam?.allow.orEmpty().toImmutableList(),
            blocked = blocked.map { it.numberRaw.ifBlank { it.normalized } }.toImmutableList(),
            deleteCodes = codes,
        )
    }

    fun onAction(action: SpamSettingsAction) {
        val actions = sources.actions
        viewModelScope.launch {
            when (action) {
                is SpamSettingsAction.SetEnabled -> {
                    actions.setEnabled(action.on)
                }

                is SpamSettingsAction.SaveRule -> {
                    val problem = actions.saveRule(action.index, action.kind.build(action.text))
                    action.done(problem)
                    if (problem == null) {
                        toast(if (action.index == null) R.string.spam_rule_added else R.string.spam_rule_saved)
                    }
                }

                is SpamSettingsAction.DeleteRule -> {
                    val rule = actions.deleteRule(action.index) ?: return@launch
                    undo(UiText.Res(R.string.spam_rule_deleted)) { actions.restoreRule(action.index, rule) }
                }

                is SpamSettingsAction.Allow -> {
                    val sender = action.sender.trim()
                    val added = actions.allow(sender)
                    action.done(added)
                    if (added) toast(R.string.spam_allow_added, sender)
                }

                is SpamSettingsAction.RemoveAllowed -> {
                    val sender = actions.removeAllowed(action.index) ?: return@launch
                    undo(UiText.Res(R.string.spam_allow_removed)) { actions.allow(sender) }
                }

                is SpamSettingsAction.Block -> {
                    val number = action.number.trim()
                    val outcome = actions.block(number)
                    action.done(outcome)
                    if (outcome == BlockOutcome.Done) toast(R.string.spam_blocked_done, number)
                }

                is SpamSettingsAction.Unblock -> {
                    when (actions.unblock(action.number)) {
                        BlockOutcome.Done -> {
                            undo(UiText.Res(R.string.spam_unblocked_done, listOf(action.number))) {
                                actions.block(action.number)
                            }
                        }

                        BlockOutcome.Refused, BlockOutcome.Unavailable -> {
                            toast(R.string.spam_block_failed)
                        }
                    }
                }

                is SpamSettingsAction.SetDeleteCodes -> {
                    sources.cleanup.setEnabled(action.on)
                }
            }
        }
    }

    private fun toast(
        id: Int,
        vararg args: Any,
    ) {
        effectChannel.trySend(SpamSettingsEffect.Toast(UiText.Res(id, args.toList())))
    }

    private fun undo(
        text: UiText,
        block: suspend () -> Unit,
    ) {
        effectChannel.trySend(SpamSettingsEffect.Undo(text) { viewModelScope.launch { block() } })
    }

    companion object {
        fun rowOf(
            index: Int,
            rule: SpamRule,
        ) = RuleRow(
            index,
            RuleKind.of(rule),
            RuleKind.textOf(rule),
            (rule as? SpamRule.ShortCodeOnly)?.enabled != false,
        )
    }
}
