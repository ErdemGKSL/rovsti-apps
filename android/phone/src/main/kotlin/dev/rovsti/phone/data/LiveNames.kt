package dev.rovsti.phone.data

import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.ExtraCall
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.platform.identity.CallerIds

/**
 * Names at display time (F29): the call log caches the name a dialer saw at call time, and nothing refreshes it while
 * Rovsti is the default dialer, so the cache is stale or empty for most rows. Every row is matched to the current
 * contacts through [CallerIds]; the cached name is kept only for a number no contact owns. Before the resolver can
 * answer (no contacts permission, contacts still loading) the entries stay as the log wrote them.
 */
suspend fun CallerIds.liveEntries(entries: List<CallLogEntry>): List<CallLogEntry> {
    if (entries.isEmpty() || !ready) return entries
    val found = resolve(entries.map { it.numberRaw })
    return entries.map { e ->
        val who = found[e.numberRaw.trim()]
        when {
            who?.contactId != null -> e.copy(cachedName = who.name ?: e.cachedName, contactId = who.contactId)

            // The log's contact link may point at a deleted or merged contact: the number decides.
            e.contactId != null -> e.copy(contactId = null)

            else -> e
        }
    }
}

/** The WhatsApp calls Recents merges in, named by the contact that owns the number before the WhatsApp name (F28). */
suspend fun CallerIds.liveExtras(extras: List<ExtraCall>): List<ExtraCall> {
    if (extras.isEmpty() || !ready) return extras
    val found = resolve(extras.map { it.numberRaw })
    return extras.map { x -> found[x.numberRaw.trim()]?.name?.let { x.copy(label = it) } ?: x }
}

/**
 * The gateway's WhatsApp calls with [CallSummary.peerName] set to the contact that owns the caller's number (F28):
 * the phone JID's number, then the contact, else the WhatsApp push name the gateway sent, else nothing (the screens
 * show the formatted number). A `@lid` peer the gateway could not map to a phone JID has no number and keeps its name.
 */
suspend fun CallerIds.liveCalls(calls: List<CallSummary>): List<CallSummary> {
    if (calls.isEmpty()) return calls
    val numbers = calls.mapNotNull { RecentsRepository.dialNumber(it.peer) }
    if (numbers.isEmpty()) return calls
    val found = resolve(numbers)
    return calls.map { call ->
        val name = RecentsRepository.dialNumber(call.peer)?.let { found[it]?.name }
        if (name != null && name != call.peerName) call.copy(peerName = name) else call
    }
}
