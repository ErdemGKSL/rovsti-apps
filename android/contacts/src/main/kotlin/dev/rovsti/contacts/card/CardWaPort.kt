package dev.rovsti.contacts.card

import dev.rovsti.contacts.data.WaMarks
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.WaLookup
import dev.rovsti.core.model.contacts.WaLookupUser
import dev.rovsti.core.model.contacts.WaPresence
import dev.rovsti.core.model.contacts.WaProfileInput
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * What the card needs from WhatsApp (AD-CT5, AD-CT6), as a seam: the real one reads [WaRepository], tests count the
 * calls. Contacts never sends the address book; only the JIDs of one contact's numbers go to the gateway, once per
 * open card.
 */
interface CardWaPort {
    /** True while the instance is linked. */
    val linked: Flow<Boolean>

    /** User parts of the 1:1 chats. */
    fun chatUsers(): Flow<List<String>>

    /** Asks the gateway which of [jids] are on WhatsApp; a failed lookup is [WaLookup.FAILED]. */
    suspend fun lookup(jids: List<String>): WaLookup

    suspend fun subscribePresence(jid: String)

    /** Presence updates of [jid]. */
    fun presence(jid: String): Flow<WaPresence>

    suspend fun profile(jid: String): WaProfileInput?
}

/** [CardWaPort] over the app's [WaRepository]. */
class RepositoryCardWaPort(
    private val wa: WaRepository,
) : CardWaPort {
    override val linked: Flow<Boolean> = wa.linkState.map { it is WaLinkState.Connected }.distinctUntilChanged()

    override fun chatUsers(): Flow<List<String>> = wa.chats().map(WaMarks::chatUsers)

    override suspend fun lookup(jids: List<String>): WaLookup {
        val gateway = wa.gateway() ?: return WaLookup(WaLookup.FAILED)
        return when (val result = gateway.lookupUsers(jids)) {
            is Outcome.Ok -> WaLookup(WaLookup.DONE, result.value.map { WaLookupUser(it.jid, it.onWhatsapp) })
            is Outcome.Err -> WaLookup(WaLookup.FAILED)
        }
    }

    override suspend fun subscribePresence(jid: String) {
        wa.gateway()?.subscribePresence(jid)
    }

    override fun presence(jid: String): Flow<WaPresence> =
        wa.events
            .filterIsInstance<GatewayEvent.Presence>()
            .filter { it.jid == jid }
            .map { WaPresence(it.available, it.lastSeenMs) }

    override suspend fun profile(jid: String): WaProfileInput? {
        val gateway = wa.gateway() ?: return null
        val profile = (gateway.profiles(listOf(jid)) as? Outcome.Ok)?.value?.firstOrNull() ?: return null
        return WaProfileInput(jid, profile.pushName, profile.about, profile.business, profile.verifiedName)
    }
}
