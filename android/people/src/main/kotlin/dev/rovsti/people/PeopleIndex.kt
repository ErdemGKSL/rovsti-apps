package dev.rovsti.people

import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.platform.identity.PhoneNumbers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** A person of the cache with the URI of the picture to draw (null: no picture file). */
data class IndexedPerson(
    val row: PersonRow,
    val pictureUri: String?,
)

/**
 * The people cache in memory: lookups by number, JID or LID that never block and never touch Rust or the network.
 * Screens read it while composing, so it keeps immutable maps behind one atomic reference, and [version] is a
 * [StateFlow] they collect (or read in a `derivedStateOf`) to recompose when the data changes.
 *
 * [normalize] turns a number into the key the index uses (E.164, from `PhoneNumbers.e164` in the home region); numbers
 * it cannot normalize fall back to their digits, so `+90 532 123 45 67` finds `+905321234567` even without Android's
 * metadata. The Rust core (`people.lookup`) answers the same question for the cache owner; this index is the read
 * model of the readers.
 */
class PeopleIndex(
    private val normalize: (String) -> String? = { null },
) {
    private class Data(
        val byNumber: Map<String, IndexedPerson> = emptyMap(),
        val byJid: Map<String, IndexedPerson> = emptyMap(),
        val byLid: Map<String, IndexedPerson> = emptyMap(),
        val people: List<IndexedPerson> = emptyList(),
    )

    private val data = AtomicReference(Data())
    private val versionState = MutableStateFlow(0L)

    /** Counts up whenever [replace] changed what the index holds. */
    val version: StateFlow<Long> = versionState.asStateFlow()

    val size: Int get() = data.get().people.size

    /** Every person, in the order the source gave. */
    val all: List<IndexedPerson> get() = data.get().people

    /**
     * Swaps in [rows] (with [pictureUri] naming each picture). Answers whether anything changed; an identical reload
     * does not bump [version], so a provider notification that changed nothing recomposes nothing.
     */
    fun replace(
        rows: List<PersonRow>,
        pictureUri: (PersonRow) -> String?,
    ): Boolean {
        val people = rows.map { IndexedPerson(it, if (it.pictureId != null) pictureUri(it) else null) }
        val old = data.get()
        if (old.people == people) return false
        val byNumber = HashMap<String, IndexedPerson>()
        val byJid = HashMap<String, IndexedPerson>()
        val byLid = HashMap<String, IndexedPerson>()
        for (person in people) {
            val row = person.row
            byJid[row.jid] = person
            row.lid?.let {
                byLid[it] = person
                byJid[lidJid(it)] = person
            }
            for (number in row.numbers) byNumber[key(number)] = person
        }
        data.set(Data(byNumber, byJid, byLid, people))
        versionState.value += 1
        return true
    }

    /** The person with this phone number in any spelling (national, international, with spaces), or null. */
    fun byNumber(raw: String): IndexedPerson? {
        val map = data.get().byNumber
        if (map.isEmpty()) return null
        return map[key(raw)]
    }

    /** The person with this phone JID, LID JID (`123@lid`) or bare LID. */
    fun byJid(jid: String): IndexedPerson? {
        val current = data.get()
        val bare = jid.substringBefore(':').substringBefore('@')
        return current.byJid[jid]
            ?: current.byLid[bare]
            ?: current.byNumber[digitsKey(bare)]
    }

    /**
     * Follows [source] on [scope]: loads once, then again after every change notification (conflated, so a burst of
     * notifications is one reload). Loading runs where the source runs it; this only swaps the maps.
     */
    fun attach(
        scope: CoroutineScope,
        source: PeopleSource,
    ): Job =
        scope.launch {
            source.changes
                .onStart { emit(Unit) }
                .conflate()
                .collect { replace(source.load(), source::pictureUri) }
        }

    private fun key(raw: String): String = normalize(raw) ?: digitsKey(raw)

    companion object {
        /** An index that reads numbers as people of [region] (the SIM's country) write them. */
        fun forRegion(region: String): PeopleIndex = PeopleIndex { PhoneNumbers.e164(it, region) }

        private fun digitsKey(raw: String): String = "+" + raw.filter(Char::isDigit)

        private fun lidJid(lid: String) = "$lid@lid"
    }
}
