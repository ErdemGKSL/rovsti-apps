package dev.rovsti.phone.incoming

import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.platform.identity.CallerIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The calls as the call screens show them, every caller named by [ids] (F39): the same resolver and number book as
 * Recents, so a contact saved as `0532 …` names a call to `+90532…`, cellular or WhatsApp. The registry already names
 * cellular calls through the same resolver ([dev.rovsti.platform.identity.CallerLookup]); this adds the contact (and so
 * its photo) to a WhatsApp call, and names a call in the moment before the registry's lookup lands.
 *
 * Never blocks and never flickers: a snapshot is named from what [ids] already knows ([CallerIds.peek]); a number it
 * has not answered yet keeps the base's name (or the number) until the answer lands, then the name is swapped in place.
 * A contact is only ever added, never taken away: a number nobody owns keeps the base's name (a WhatsApp push name).
 * [changes] is the resolver's revision: when the contacts change every live number is asked again.
 */
class NamedCallSource(
    private val base: CallSource,
    private val ids: CallerIds,
    changes: Flow<Long>,
    scope: CoroutineScope,
) : CallSource {
    private val learned = MutableStateFlow(0L)

    override val whatsapp get() = base.whatsapp

    override val snapshot: StateFlow<CallSnapshot> =
        combine(base.snapshot, learned) { s, _ -> named(s) }
            .stateIn(scope, SharingStarted.Eagerly, named(base.snapshot.value))

    init {
        scope.launch {
            val numbers =
                base.snapshot
                    .map { s ->
                        s.calls
                            .map { it.numberRaw.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }.distinctUntilChanged()
            combine(numbers, changes) { n, _ -> n }.collectLatest { wanted ->
                if (wanted.isEmpty() || !ids.ready) return@collectLatest
                ids.resolve(wanted)
                learned.value += 1
            }
        }
    }

    private fun named(snapshot: CallSnapshot): CallSnapshot {
        if (snapshot.calls.isEmpty() || !ids.ready) return snapshot
        var changed = false
        val calls =
            snapshot.calls.map { call ->
                named(call).also { if (it !== call) changed = true }
            }
        return if (changed) snapshot.copy(calls = calls) else snapshot
    }

    private fun named(call: WireCall): WireCall {
        // A conference has no number of its own; its legs are named one by one.
        if (call.children.isNotEmpty()) return call
        val who = ids.peek(call.numberRaw) ?: return call
        val id = who.contactId ?: return call
        val name = who.name?.trim()?.takeIf { it.isNotEmpty() } ?: return call
        if (call.displayName == name && call.contactId == id) return call
        return call.copy(displayName = name, contactId = id, displayNameAlt = null)
    }
}
