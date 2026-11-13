package dev.rovsti.platform.identity

import android.content.Context
import dev.rovsti.core.model.search.WantedNumber
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.platform.contacts.ContactsQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Where [CallerIds] learns who a number is. Sources are pluggable: the device contacts now ([DeviceContactsSource]);
 * later the Contacts app's provider, or the WhatsApp names and photos synced from the gateway into memory, add
 * [CallerIdentity.waPushName] and [CallerIdentity.waPhotoUri].
 */
interface IdentitySource {
    /** Changes whenever an answer may have changed (contacts edited, a WhatsApp sync). */
    val revision: StateFlow<Long>

    /** True once the source can answer; before, an empty answer means "not yet", not "nobody". */
    val ready: Boolean

    /** What the source knows about each of [numbers]; a number it knows nothing about is absent. Never throws. */
    suspend fun lookup(numbers: List<String>): Map<String, CallerIdentity>
}

/**
 * The device contacts behind a matching index. The phone app's index is the Rust core's number book
 * (`search.match_numbers`, built from the provider's contacts and refreshed on every contacts change), so Phone and
 * Contacts match numbers the same way.
 */
interface ContactBook {
    /** Changes after every rebuild of the index. */
    val revision: StateFlow<Long>

    /** True while an index exists (contacts read, permission held). */
    val ready: Boolean

    /** The contact behind each of [numbers] (null: a stranger), in order; null while there is no index. */
    suspend fun owners(
        numbers: List<WantedNumber>,
        homeCc: String?,
    ): List<WireContact?>?
}

/**
 * [IdentitySource] over a [ContactBook]: each number goes with its E.164 form ([e164], Android's own normalization in
 * the home region) so the core keys it like the provider's `NORMALIZED_NUMBER`. [name] words a contact in the user's
 * name order; [homeCc] is the calling code of home for national numbers Android could not normalize.
 */
class DeviceContactsSource(
    private val book: ContactBook,
    private val e164: (String) -> String?,
    private val homeCc: suspend () -> String? = { null },
    private val name: suspend (WireContact) -> String = { it.displayName },
) : IdentitySource {
    override val revision: StateFlow<Long> get() = book.revision

    override val ready: Boolean get() = book.ready

    override suspend fun lookup(numbers: List<String>): Map<String, CallerIdentity> {
        if (numbers.isEmpty()) return emptyMap()
        val wanted = numbers.map { WantedNumber(it, e164(it)) }
        val owners = book.owners(wanted, homeCc()) ?: return emptyMap()
        val named = HashMap<Long, CallerIdentity>()
        val out = HashMap<String, CallerIdentity>()
        numbers.zip(owners).forEach { (number, owner) ->
            if (owner != null) out[number] = named.getOrPut(owner.id) { CallerIdentity.of(owner, name(owner)) }
        }
        return out
    }
}

/**
 * The single-number lookup of a cold process (an FCM push, the `InCallService` before the app runs): Android's
 * `PhoneLookup`, with the same rules as the index where it can (E.164 first, withheld numbers match nobody, the starred
 * then most recently updated then lowest-id contact wins). Reads run on [io].
 */
class PhoneLookupSource(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : IdentitySource {
    private val changes = MutableStateFlow(1L)
    override val revision: StateFlow<Long> = changes.asStateFlow()
    override val ready: Boolean = true

    override suspend fun lookup(numbers: List<String>): Map<String, CallerIdentity> =
        withContext(io) {
            val region = PhoneNumbers.homeRegion(context)
            numbers.mapNotNull { n -> ContactsQuery.identity(context, n, region)?.let { n to it } }.toMap()
        }
}

/**
 * Who a number is, for every Phone surface: Recents rows and call details, favorites, speed dial, statistics, the
 * keypad, search, the person sheet, the call screens and the call notifications (cellular and WhatsApp). The first
 * [sources] entry with a contact names the number; later ones add what it lacks (a WhatsApp name or photo).
 *
 * Answers are cached until any source's [IdentitySource.revision] changes; [revision] is their sum, so a screen that
 * combines it rebuilds when contacts change. [peek] reads the cache without suspending (the call screens).
 */
class CallerIds(
    private val sources: List<IdentitySource>,
) {
    private val cache = ConcurrentHashMap<String, Answer>()

    @Volatile private var cachedAt = -1L

    /** The sum of the sources' revisions; changes when an answer may have. */
    val revision: Long get() = sources.sumOf { it.revision.value }

    /** The sources' revisions, to combine into a flow. */
    val revisions: List<StateFlow<Long>> get() = sources.map { it.revision }

    /** True once every source can answer. */
    val ready: Boolean get() = sources.all { it.ready }

    private class Answer(
        val identity: CallerIdentity?,
    )

    /** The cached identity of [number], null when unknown or not resolved yet. */
    fun peek(number: String): CallerIdentity? {
        if (cachedAt != revision) return null
        return cache[number.trim()]?.identity
    }

    suspend fun resolve(number: String): CallerIdentity? = resolve(listOf(number))[number.trim()]

    /** The identity of each of [numbers] that someone owns (keys are the trimmed numbers). */
    suspend fun resolve(numbers: Collection<String>): Map<String, CallerIdentity> {
        val now = revision
        if (cachedAt != now) {
            cache.clear()
            cachedAt = now
        }
        val keys = numbers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val missing = keys.filterNot { cache.containsKey(it) }
        if (missing.isNotEmpty()) {
            var merged = emptyMap<String, CallerIdentity>()
            for (source in sources) {
                val found = source.lookup(missing)
                merged =
                    (merged.keys + found.keys).associateWith { k ->
                        val a = merged[k]
                        val b = found[k]
                        if (a != null && b != null) a.orElse(b) else a ?: b!!
                    }
            }
            // Only a ready answer is remembered: "nobody" from a source still loading would stick.
            if (ready && revision == now) for (k in missing) cache[k] = Answer(merged[k])
            return keys.mapNotNull { k -> (cache[k]?.identity ?: merged[k])?.let { k to it } }.toMap()
        }
        return keys.mapNotNull { k -> cache[k]?.identity?.let { k to it } }.toMap()
    }
}
