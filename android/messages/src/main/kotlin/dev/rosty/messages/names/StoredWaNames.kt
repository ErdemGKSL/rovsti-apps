package dev.rosty.messages.names

import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.WaIds
import dev.rosty.messages.wa.WaOffline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WhatsApp names the offline store already has, by number: what the one-to-one chat of a number carries (push
 * name, chat name). It lets a surface that only holds a number (an SMS thread, an SMS notification, the Scheduled
 * page) name a person the store knows, without asking the gateway. The chat list is read once and again after the
 * store changed; nothing is read before the first question.
 */
class StoredWaNames(
    /** The offline side, or null while nothing has opened it yet (then nothing is known, and nothing is started). */
    private val offline: () -> WaOffline?,
    /** The number's digits in international form (the user part of its JID), or null for what is not a number. */
    private val key: (number: String) -> String?,
    private val scope: CoroutineScope,
) {
    private val following = AtomicBoolean(false)
    private val lock = Any()
    private var generation = 0L
    private var cache: Map<String, WaNames>? = null

    suspend fun of(number: String): WaNames? {
        val digits = key(number) ?: return null
        val source = offline() ?: return null
        follow(source)
        val known = synchronized(lock) { cache } ?: load(source)
        return known[digits]
    }

    private suspend fun load(source: WaOffline): Map<String, WaNames> {
        val at = synchronized(lock) { generation }
        val store = source.session.value?.store ?: return emptyMap()
        val chats = store.chats(includeArchived = true).getOrNull() ?: return emptyMap()
        val names = HashMap<String, WaNames>()
        for (chat in chats) {
            if (chat.isGroup) continue
            val digits = WaIds.number(chat.jid)?.removePrefix("+") ?: continue
            val wa = WaNames.of(chat)
            if (PersonNames.pick(null, wa, null) != null) names[digits] = wa
        }
        synchronized(lock) { if (generation == at) cache = names }
        return names
    }

    private fun follow(source: WaOffline) {
        if (!following.compareAndSet(false, true)) return
        scope.launch {
            merge(source.session.map { }, source.changes.map { }).collect {
                synchronized(lock) {
                    generation += 1
                    cache = null
                }
            }
        }
    }
}
