package dev.rosty.wa.share

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.wa.WaLinkStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** What a sibling app answered when asked for its WhatsApp link. */
sealed interface SiblingAnswer {
    /** The app is not installed (or not visible), or it has no hand-over provider. */
    data object NotInstalled : SiblingAnswer

    /** A package of that name exists but is not signed like this app, or it refused the read. Never trusted. */
    data object Untrusted : SiblingAnswer

    /** The app is there and has no link. */
    data object NoLink : SiblingAnswer

    /** The app answered something that is not a link. */
    data object Invalid : SiblingAnswer

    /** The app's link. */
    data class Link(
        val link: StoredLink,
    ) : SiblingAnswer
}

/** Asks one sibling app for its link. The real one is [ResolverSiblingLinks]; tests pass a fake. */
fun interface SiblingLinks {
    suspend fun ask(packageName: String): SiblingAnswer
}

/** A link this app took from a sibling: which instance, and from which app. */
data class AdoptedLink(
    val instanceId: String,
    val fromPackage: String,
)

/**
 * The two notes the hand-over keeps between runs. [PrefsWaLinkMarks] stores them in the app's private preferences;
 * neither holds the key.
 */
interface WaLinkMarks {
    /** The link in use was taken from a sibling. */
    var adopted: AdoptedLink?

    /** The fingerprint ([WaLinkHandover.fingerprint]) of the link the user removed from this app. */
    var declined: String?

    /** Marks kept in memory, for tests and previews. */
    class InMemory(
        override var adopted: AdoptedLink? = null,
        override var declined: String? = null,
    ) : WaLinkMarks
}

/**
 * The asking side of the link hand-over (WA-R2): an app without a WhatsApp link asks the installed sibling apps for
 * theirs and, when one answers, stores it and connects exactly as if the user had pasted the key ([adopt] is
 * `WaRepository.relinkTo`).
 *
 * Rules:
 * - an app that has a link never asks;
 * - siblings are asked in the order of [siblings]; the first usable link the store accepts wins (they normally all
 *   hold the same instance). An app that is not installed, is signed with another key, has no link or answers
 *   rubbish is skipped;
 * - unlinking is per app: removing the link here does not touch the siblings, and [onForget] remembers the removed
 *   link so it is not taken back behind the user's back. That same link is only taken again when the user asks
 *   ([adoptIfUnlinked] with `explicit`); a sibling that holds another link (a new key, another instance) is adopted
 *   as usual.
 */
class WaLinkHandover(
    private val store: WaLinkStore,
    private val siblings: List<String>,
    private val source: SiblingLinks,
    private val marks: WaLinkMarks,
    private val adopt: suspend (StoredLink) -> Outcome<Unit>,
) {
    /** What [adoptIfUnlinked] did. */
    sealed interface Result {
        /** This app has a link (or its link file cannot be read): nobody was asked. */
        data object AlreadyLinked : Result

        /** No sibling had a link to give. */
        data object Nothing : Result

        /** [fromPackage] holds the link the user removed here; it was left alone. */
        data class Declined(
            val fromPackage: String,
        ) : Result

        /** The link of [fromPackage] is now this app's link. */
        data class Adopted(
            val fromPackage: String,
        ) : Result
    }

    private val lock = Mutex()

    /**
     * Asks the siblings when this app has no link. [explicit] is the user's own request ("use the link of ..."):
     * it also takes a link that was removed here before.
     */
    suspend fun adoptIfUnlinked(explicit: Boolean = false): Result =
        lock.withLock {
            // A damaged link file is an error the settings page shows, not "no link": do not write over it.
            val current = store.read()
            if (current !is Outcome.Ok || current.value != null) return@withLock Result.AlreadyLinked
            var declinedFrom: String? = null
            for (sibling in siblings) {
                val link = (source.ask(sibling) as? SiblingAnswer.Link)?.link ?: continue
                if (!WaLinkShare.usable(link)) continue
                if (!explicit && marks.declined == fingerprint(link)) {
                    declinedFrom = declinedFrom ?: sibling
                    continue
                }
                if (adopt(link) is Outcome.Ok) {
                    marks.adopted = AdoptedLink(link.instanceId, sibling)
                    marks.declined = null
                    return@withLock Result.Adopted(sibling)
                }
            }
            declinedFrom?.let { Result.Declined(it) } ?: Result.Nothing
        }

    /**
     * The first sibling that holds a usable link, without taking it: what the link screen offers ("use the link
     * of ...") while this app has none.
     */
    suspend fun offer(): String? =
        siblings.firstOrNull { sibling ->
            (source.ask(sibling) as? SiblingAnswer.Link)?.link?.let { WaLinkShare.usable(it) } == true
        }

    /** The app the link in use was taken from; null when this app has no link or got it another way. */
    suspend fun adoptedFrom(): String? {
        val adopted = marks.adopted ?: return null
        val link = (store.read() as? Outcome.Ok)?.value ?: return null
        return adopted.fromPackage.takeIf { link.instanceId == adopted.instanceId }
    }

    /**
     * The user removed [link] from this app (call it with the link that was stored, before or after it is gone).
     * The siblings keep theirs.
     */
    fun onForget(link: StoredLink?) {
        marks.adopted = null
        if (link != null) marks.declined = fingerprint(link)
    }

    companion object {
        /** A one-way mark of a link (SHA-256 of instance id and key): tells "the same link" without storing the key. */
        fun fingerprint(link: StoredLink): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest("${link.instanceId}\n${link.key}".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
