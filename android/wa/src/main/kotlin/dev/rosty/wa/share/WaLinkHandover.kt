package dev.rosty.wa.share

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.StoredLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a sibling app answered when asked for its WhatsApp link. */
sealed interface SiblingAnswer {
    /** The app is not installed (or not visible), or it has no hand-over provider. */
    data object NotInstalled : SiblingAnswer

    /** A package of that name exists but is not signed like this app, or it refused the read. Never trusted. */
    data object Untrusted : SiblingAnswer

    /** The app is there and gives no link: it has none, or its WhatsApp vault is locked. */
    data object NoLink : SiblingAnswer

    /** The app answered something that is not a link. */
    data object Invalid : SiblingAnswer

    /** The app's link. */
    data class Link(
        val link: StoredLink,
    ) : SiblingAnswer
}

/**
 * Asks one sibling app. The real one is [ResolverSiblingLinks]; tests pass a fake.
 *
 * [offers] only asks whether the sibling would hand its link over now (its vault is unlocked); no key crosses. [ask]
 * reads the link itself, key included: only for the hand-over the person asked for.
 */
interface SiblingLinks {
    suspend fun offers(packageName: String): Boolean

    suspend fun ask(packageName: String): SiblingAnswer
}

/** A link this app took from a sibling: which instance, and from which app. */
data class AdoptedLink(
    val instanceId: String,
    val fromPackage: String,
)

/** The note the hand-over keeps between runs ([PrefsWaLinkMarks]); it never holds the key. */
interface WaLinkMarks {
    /** The link in use was taken from a sibling. */
    var adopted: AdoptedLink?

    /** Marks kept in memory, for tests and previews. */
    class InMemory(
        override var adopted: AdoptedLink? = null,
    ) : WaLinkMarks
}

/** What this app holds, as the hand-over sees it. */
sealed interface HeldLink {
    /** No link and no vault: the app may take a sibling's link. */
    data object None : HeldLink

    /** A link or a vault. [instanceId] is null while the vault is locked (the instance is sealed in it). */
    data class Held(
        val instanceId: String?,
    ) : HeldLink

    /** The app's link or vault cannot be read: nothing is written over it. */
    data object Unreadable : HeldLink
}

/**
 * The asking side of the link hand-over (WA-R2, `plans/wa-vault.md` 13.3): an app without a WhatsApp link takes the
 * link of a sibling Rosty app, in memory, for its own vault ([adopt] is `WaVault.adopt`: the vault setup follows and
 * nothing is written in plaintext).
 *
 * Rules:
 * - only the person's own tap takes a link ([take], "Use the link of ..." on the link screen, which starts this
 *   app's vault setup); nothing is taken in the background;
 * - an app that holds a link or a vault never asks;
 * - only a sibling whose vault is unlocked gives its link (its provider answers nothing while locked, and never
 *   reads a link file); siblings are asked in the order of [siblings], and the first usable link this app's vault
 *   accepts wins. An app that is not installed, is signed with another key, gives nothing or answers rubbish is
 *   skipped;
 * - unlinking is per app: removing the link here does not touch the siblings ([onForget]).
 */
class WaLinkHandover(
    private val held: suspend () -> HeldLink,
    private val siblings: List<String>,
    private val source: SiblingLinks,
    private val marks: WaLinkMarks,
    private val adopt: suspend (StoredLink) -> Outcome<Unit>,
) {
    /** What [take] did. */
    sealed interface Result {
        /** This app has a link or a vault (or it cannot be read): nobody was asked. */
        data object AlreadyLinked : Result

        /** No sibling had a link to give (none installed, none unlocked, none linked). */
        data object Nothing : Result

        /** The link of [fromPackage] now waits for this app's vault (the setup). */
        data class Adopted(
            val fromPackage: String,
        ) : Result
    }

    private val lock = Mutex()

    /** The person asked for a sibling's link: the first sibling that gives a usable one; this app's setup starts. */
    suspend fun take(): Result =
        lock.withLock {
            if (held() != HeldLink.None) return@withLock Result.AlreadyLinked
            for (sibling in siblings) {
                val link = (source.ask(sibling) as? SiblingAnswer.Link)?.link ?: continue
                if (!WaLinkShare.usable(link)) continue
                if (adopt(link) is Outcome.Ok) {
                    marks.adopted = AdoptedLink(link.instanceId, sibling)
                    return@withLock Result.Adopted(sibling)
                }
            }
            Result.Nothing
        }

    /**
     * The first sibling that would hand its link over now, without taking it (no key crosses): what the link screen
     * offers ("use the link of ...") while this app has none.
     */
    suspend fun offer(): String? {
        if (held() != HeldLink.None) return null
        return siblings.firstOrNull { source.offers(it) }
    }

    /** The app the link in use was taken from; null when this app has no link or got it another way. */
    suspend fun adoptedFrom(): String? {
        val adopted = marks.adopted ?: return null
        val now = held() as? HeldLink.Held ?: return null
        return adopted.fromPackage.takeIf { now.instanceId == null || now.instanceId == adopted.instanceId }
    }

    /** The person removed the link from this app. The siblings keep theirs. */
    fun onForget() {
        marks.adopted = null
    }
}
