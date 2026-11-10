package dev.rovsti.contacts.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.R
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactOptions
import dev.rovsti.core.model.contacts.OptionsAnswer
import dev.rovsti.core.model.contacts.OptionsRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.calllog.Blocklist
import dev.rovsti.platform.calllog.BlocklistRepository
import dev.rovsti.platform.contacts.ContactCallOptions
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "Calls" section of one card (E4.13, C34, C35). The ringtone and voicemail columns are read and written through
 * [ContactsRepository]; every sentence (the ringtone's name, the notices, the voicemail question) is an answer of the
 * Rust op `contacts.options`. Blocking goes through the system blocklist ([BlocklistRepository]) for every number of the
 * contact, and only when this app may use it (the default phone app); otherwise the row says so and opens the role
 * settings.
 *
 * Turning "Send Calls to Voicemail" on asks first ([CallOptionsEffect.AskVoicemail]); turning it off does not. Blocking
 * asks first; unblocking does not.
 */
class CallOptionsViewModel(
    private val id: Long,
    private val repo: ContactsRepository,
    private val ops: ContactsOps,
    private val blocklist: BlocklistRepository,
    /** The contact's display name for the questions; read when a question is asked. */
    private val name: () -> String,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(CallOptionsUiState())
    val state: StateFlow<CallOptionsUiState> = ui.asStateFlow()

    private val outbox = Channel<CallOptionsEffect>(Channel.UNLIMITED)
    val effects: Flow<CallOptionsEffect> = outbox.receiveAsFlow()

    private var options = ContactOptions(ringtoneUri = null, ringtoneTitle = null, toVoicemail = false)
    private var numbers: List<String> = emptyList()

    init {
        scope.launch { loadOptions() }
        // The list follows the system blocklist, so a change made elsewhere shows here too.
        scope.launch { blocklist.numbers().collect { loadBlock() } }
    }

    private suspend fun answer(): OptionsAnswer? =
        (ops.options(OptionsRequest(options = options, name = name())) as? Outcome.Ok)?.value

    private suspend fun loadOptions() {
        (repo.options(id) as? ContactResult.Ok)?.value?.let { options = it }
        val said = answer()
        ui.update {
            it.copy(
                loaded = true,
                ringtone = said?.ringtoneText?.let { UiText.Literal(it) } ?: UiText.Res(R.string.contacts_default),
                voicemail = options.toVoicemail,
            )
        }
    }

    private suspend fun loadBlock() {
        numbers =
            repo
                .detail(id)
                .first()
                ?.numbers
                ?.map { it.value }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        val row =
            when {
                numbers.isEmpty() -> BlockRow.Hidden
                !blocklist.canBlock() -> BlockRow.NeedsRole
                else -> BlockRow.Available(blocked = numbers.all { blocklist.isBlocked(it) })
            }
        ui.update { it.copy(block = row) }
    }

    fun onAction(action: CallOptionsAction) {
        when (action) {
            CallOptionsAction.ChooseRingtone -> {
                outbox.trySend(CallOptionsEffect.PickRingtone(options.ringtoneUri))
            }

            is CallOptionsAction.RingtonePicked -> {
                val chosen = action.pick as? ContactCallOptions.Pick.Chosen ?: return
                scope.launch { setRingtone(chosen.uri) }
            }

            is CallOptionsAction.SetVoicemail -> {
                scope.launch { if (action.on) askVoicemail() else setVoicemail(false) }
            }

            CallOptionsAction.ConfirmVoicemail -> {
                scope.launch { setVoicemail(true) }
            }

            CallOptionsAction.ToggleBlock -> {
                scope.launch { toggleBlock() }
            }

            CallOptionsAction.ConfirmBlock -> {
                scope.launch { block() }
            }
        }
    }

    private fun say(text: UiText) {
        outbox.trySend(CallOptionsEffect.Toast(text))
    }

    private suspend fun setRingtone(uri: String?) {
        if (repo.setRingtone(
                id,
                uri,
            ) !is ContactResult.Ok
        ) {
            return say(UiText.Res(R.string.contacts_cant_change_ringtone))
        }
        // The picked tone's title is read back from the provider.
        (repo.options(id) as? ContactResult.Ok)?.value?.let { options = it }
        val said = answer() ?: return
        ui.update { it.copy(ringtone = UiText.Literal(said.ringtoneText)) }
        say(UiText.Literal(said.ringtoneNotice))
    }

    private suspend fun askVoicemail() {
        val said = answer() ?: return
        outbox.trySend(CallOptionsEffect.AskVoicemail(said.voicemailConfirm))
    }

    private suspend fun setVoicemail(on: Boolean) {
        if (repo.setVoicemail(
                id,
                on,
            ) !is ContactResult.Ok
        ) {
            return say(UiText.Res(R.string.contacts_cant_change_setting))
        }
        options = options.copy(toVoicemail = on)
        ui.update { it.copy(voicemail = on) }
        val said = answer() ?: return
        say(UiText.Literal(if (on) said.voicemailOnNotice else said.voicemailOffNotice))
    }

    private suspend fun toggleBlock() {
        when (val row = ui.value.block) {
            BlockRow.Hidden -> Unit
            BlockRow.NeedsRole -> outbox.trySend(CallOptionsEffect.OpenRoleSettings)
            is BlockRow.Available -> if (row.blocked) unblock() else askBlock()
        }
    }

    private fun askBlock() {
        val who =
            name().trim().let {
                if (it.isEmpty()) {
                    UiText.Res(
                        R.string.contacts_this_contact,
                    )
                } else {
                    UiText.Literal(it)
                }
            }
        val message =
            if (numbers.size > 1) {
                R.string.contacts_block_all_numbers_message
            } else {
                R.string.contacts_block_this_number_message
            }
        outbox.trySend(
            CallOptionsEffect.AskBlock(
                title = UiText.Res(R.string.contacts_block_who_title, listOf(who)),
                message = UiText.Res(message),
                actionLabel = UiText.Res(R.string.contacts_block_contact),
            ),
        )
    }

    private suspend fun block() {
        var done = 0
        var refused = 0
        for (number in numbers) {
            when (blocklist.block(number)) {
                Blocklist.Result.OK -> done++

                Blocklist.Result.REFUSED -> refused++

                Blocklist.Result.UNAVAILABLE, Blocklist.Result.PERMISSION -> return say(
                    UiText.Res(R.string.contacts_cant_block_settings),
                )
            }
        }
        loadBlock()
        say(
            when {
                done == 0 && refused > 0 -> UiText.Res(R.string.contacts_number_cant_block)
                else -> UiText.Res(R.string.contacts_blocked)
            },
        )
    }

    private suspend fun unblock() {
        var failed = false
        for (number in numbers) {
            if (blocklist.isBlocked(number) && blocklist.unblock(number) != Blocklist.Result.OK) failed = true
        }
        loadBlock()
        say(if (failed) UiText.Res(R.string.contacts_cant_unblock) else UiText.Res(R.string.contacts_unblocked))
    }
}
