package dev.rosty.messages.compose

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.search.WireContact
import dev.rosty.core.model.wa.WaTarget
import dev.rosty.core.model.wa.WaTargetRequest
import dev.rosty.core.ops.WaOps
import dev.rosty.design.text.UiText
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.SharePayload
import dev.rosty.messages.data.Book
import dev.rosty.messages.names.PersonNames
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One choice of New Conversation: a typed number, or one number of a contact. */
@Immutable
data class RecipientOption(
    val key: String,
    val title: String,
    /** "{label} · {number}" for a contact, the number for a typed one. */
    val detail: String,
    val number: String,
    val photoUri: String?,
    val typed: Boolean,
)

@Immutable
data class NewConversationUi(
    val options: ImmutableList<RecipientOption> = persistentListOf(),
    val query: String = "",
    val share: SharePayload? = null,
)

sealed interface NewConversationEffect {
    data class Open(
        val route: MessagesRoute,
    ) : NewConversationEffect

    /** WhatsApp is linked: SMS or WhatsApp? */
    data class ChooseChannel(
        val option: RecipientOption,
    ) : NewConversationEffect

    data class Toast(
        val text: UiText,
    ) : NewConversationEffect
}

/**
 * New Conversation (E6.8): a number or a contact, by SMS or WhatsApp (GPUI `new_conversation.rs`). The options are a
 * typed number (3+ digits) first, then the contacts that match, one row per number (default first, no duplicates), at
 * most [MAX_OPTIONS]. SMS opens the existing one-to-one thread of the number or a new draft thread; WhatsApp asks the
 * core where the person is (`wa.target`), then the gateway whether a bare number is on WhatsApp.
 */
class NewConversationViewModel(
    private val book: StateFlow<Book>,
    private val sms: SmsRepository,
    private val wa: WaRepository,
    private val waOps: WaOps,
    private val e164: (String) -> String?,
    share: SharePayload?,
    /** The name-order rule, for a typed number: its contact or WhatsApp name stands above it. */
    private val names: PersonNames = PersonNames({ null }, emptyFlow<Unit>()),
) : ViewModel() {
    private val ui = MutableStateFlow(NewConversationUi(share = share))
    val state: StateFlow<NewConversationUi> = ui.asStateFlow()

    private val effectChannel = Channel<NewConversationEffect>(Channel.BUFFERED)
    val effects: Flow<NewConversationEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch { book.collect { refresh(ui.value.query) } }
        // The WhatsApp name asked for a typed number arrived: the row is named.
        viewModelScope.launch { names.changes.collect { refresh(ui.value.query) } }
    }

    fun setQuery(text: String) {
        viewModelScope.launch { refresh(text) }
    }

    /** A typed number that is a whole phone number, titled by the rule; anything else stays as typed. */
    private suspend fun named(typed: RecipientOption): RecipientOption {
        if (e164(typed.number) == null) return typed
        val who = names.of(typed.number)
        return if (who.named) typed.copy(title = who.text) else typed
    }

    private var latest = ""

    private suspend fun refresh(text: String) {
        latest = text
        val current = book.value
        val query = text.trim()
        val contacts: List<WireContact> =
            if (query.isEmpty()) {
                current.contacts.values.sortedBy { it.displayName.lowercase() }
            } else {
                (current.index?.queryLoose(query, MAX_OPTIONS) as? Outcome.Ok)?.value?.rows.orEmpty()
            }
        val found = options(query, contacts).map { if (it.typed) named(it) else it }
        // A slower answer for an older text never replaces the newer one.
        if (text == latest) ui.value = ui.value.copy(query = text, options = found.toImmutableList())
    }

    fun pick(option: RecipientOption) {
        if (wa.linkState.value is WaLinkState.Connected) {
            effectChannel.trySend(NewConversationEffect.ChooseChannel(option))
        } else {
            openSms(option)
        }
    }

    fun openSms(option: RecipientOption) {
        viewModelScope.launch {
            val existing = threadFor(option.number, sms.threads().first())
            val draft = ui.value.share?.text
            val route =
                if (existing != null) {
                    MessagesRoute.SmsThread(existing, listOf(option.number), draft)
                } else {
                    MessagesRoute.SmsThread(0, listOf(option.number), draft)
                }
            effectChannel.trySend(NewConversationEffect.Open(route))
        }
    }

    fun openWhatsApp(option: RecipientOption) {
        viewModelScope.launch {
            val number = e164(option.number) ?: option.number
            val answer = (waOps.target(WaTargetRequest(listOf(number), emptyList())) as? Outcome.Ok)?.value
            val draft = ui.value.share?.text
            // The files shared in (one or several) open the send preview of the chat (E6-WA R3).
            val files =
                ui.value.share
                    ?.files()
                    .orEmpty()
            when (val target = answer?.target) {
                is WaTarget.Chat -> {
                    effectChannel.trySend(
                        NewConversationEffect.Open(MessagesRoute.WaThread(target.jid, draft, share = files)),
                    )
                }

                is WaTarget.Number -> {
                    val gateway = wa.gateway()
                    val users = gateway?.lookupUsers(listOf(target.jid))
                    val user = (users as? Outcome.Ok)?.value?.firstOrNull()
                    when {
                        users == null || users is Outcome.Err -> {
                            toast(R.string.new_wa_cant_check)
                        }

                        user?.onWhatsapp == true -> {
                            effectChannel.trySend(
                                NewConversationEffect.Open(MessagesRoute.WaThread(user.jid, draft, share = files)),
                            )
                        }

                        else -> {
                            effectChannel.trySend(
                                NewConversationEffect.Toast(UiText.Res(R.string.new_wa_not_on, listOf(option.title))),
                            )
                        }
                    }
                }

                WaTarget.NeedsCountryCode -> {
                    toast(R.string.new_wa_country_code)
                }

                else -> {
                    toast(R.string.new_wa_cant_check)
                }
            }
        }
    }

    private fun toast(id: Int) {
        effectChannel.trySend(NewConversationEffect.Toast(UiText.Res(id)))
    }

    companion object {
        const val MAX_OPTIONS = 500
        private const val MIN_TYPED_DIGITS = 3
        private const val MATCH_DIGITS = 9

        /** The typed number first (when it is one), then each contact number once, default first. */
        fun options(
            query: String,
            contacts: List<WireContact>,
        ): List<RecipientOption> {
            val out = ArrayList<RecipientOption>()
            val seen = HashSet<String>()
            val typedDigits = query.filter(Char::isDigit)
            val looksLikeNumber = typedDigits.length >= MIN_TYPED_DIGITS && query.all { it.isDigit() || it in "+ ()-." }
            if (looksLikeNumber) {
                out += RecipientOption("typed", query, query, query, null, typed = true)
                seen += typedDigits.takeLast(MATCH_DIGITS)
            }
            for (c in contacts) {
                val numbers = c.numbers.sortedByDescending { it.default }
                for (n in numbers) {
                    val tail = n.raw.filter(Char::isDigit).takeLast(MATCH_DIGITS)
                    if (tail.isEmpty() || !seen.add("${c.id}:$tail") || !seen.add(tail)) continue
                    val detail = listOfNotNull(n.label?.takeIf { it.isNotBlank() }, n.raw).joinToString(" · ")
                    out += RecipientOption("${c.id}:$tail", c.displayName, detail, n.raw, c.photoUri, typed = false)
                    if (out.size >= MAX_OPTIONS) return out
                }
            }
            return out
        }

        /** The one-to-one SMS thread of [number] (same last digits), or null. */
        fun threadFor(
            number: String,
            threads: List<SmsThreadRow>,
        ): Long? {
            val tail = number.filter(Char::isDigit).takeLast(MATCH_DIGITS)
            if (tail.length < MIN_TYPED_DIGITS) return null
            return threads
                .firstOrNull { t ->
                    t.addresses
                        .singleOrNull()
                        ?.filter(Char::isDigit)
                        ?.endsWith(tail) == true
                }?.threadId
        }
    }
}
