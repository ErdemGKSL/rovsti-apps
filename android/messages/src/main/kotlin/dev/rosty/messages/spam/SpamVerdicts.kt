package dev.rosty.messages.spam

import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.messages.ClassifyRequest
import dev.rosty.core.model.messages.InboxRow
import dev.rosty.core.model.messages.PersonKey
import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.model.messages.ThreadId
import dev.rosty.core.model.phone.BlockedNumber
import dev.rosty.core.ops.MessagesOps

/** What the inbox needs to know about its SMS threads (AD-MSG8): which are spam and whose sender is blocked. */
data class SpamFacts(
    /** SMS threads judged spam. */
    val spam: Set<Long> = emptySet(),
    /** One-to-one SMS threads whose sender is on the system blocklist (the row menu offers Unblock). */
    val blocked: Set<Long> = emptySet(),
) {
    companion object {
        val None = SpamFacts()
    }
}

/**
 * The spam verdicts of the inbox rows, the port of GPUI `inbox::is_spam`: only a one-to-one conversation that lives
 * purely in SMS can be spam (anyone also on WhatsApp, a contact's row or a group never is) and the verdict is on the
 * newest message, the same `spam.classify` the cold SMS receiver asks. Rust decides; this class only picks the rows
 * and remembers the answers until the rules or the blocklist change, so a rebuild of the inbox does not ask again.
 */
class SpamVerdicts(
    private val ops: MessagesOps,
) {
    private data class Question(
        val sender: String,
        val body: String,
        val known: Boolean,
    )

    private var rules: SpamRules? = null
    private var blocklist: List<BlockedNumber>? = null
    private val spamAnswers = HashMap<Question, Boolean>()
    private val blockedAnswers = HashMap<String, Boolean>()

    suspend fun judge(
        rows: List<InboxRow>,
        sms: Map<Long, SmsThreadRow>,
        rules: SpamRules,
        blocked: List<BlockedNumber>,
    ): SpamFacts {
        if (rules != this.rules || blocked != blocklist) {
            spamAnswers.clear()
            blockedAnswers.clear()
            this.rules = rules
            blocklist = blocked
        }
        val spam = HashSet<Long>()
        val blockedThreads = HashSet<Long>()
        for (row in rows) {
            val (thread, sender) = singleSender(row, sms) ?: continue
            if (blocked.isNotEmpty() && isBlocked(sender, blocked)) blockedThreads += thread
            if (!candidate(row)) continue
            val question = Question(sender, row.threads.first().last.body, row.key is PersonKey.Contact)
            val verdict =
                spamAnswers.getOrPut(question) {
                    val request = ClassifyRequest(rules, question.sender, question.body, question.known, blocked)
                    (ops.classify(request) as? Outcome.Ok)?.value?.spam == true
                }
            if (verdict) spam += thread
        }
        return SpamFacts(spam, blockedThreads)
    }

    /**
     * Whether [sender] is on the blocklist, asked of the same `spam.classify` with no rules: with nothing else to go on
     * a sender is spam exactly when the system blocklist holds it (Rust matches the numbers, any spelling).
     */
    private suspend fun isBlocked(
        sender: String,
        blocked: List<BlockedNumber>,
    ): Boolean =
        blockedAnswers.getOrPut(sender) {
            val request = ClassifyRequest(SpamRules(enabled = false), sender, "", known = false, blocked = blocked)
            (ops.classify(request) as? Outcome.Ok)?.value?.reason == BLOCKED
        }

    companion object {
        /** `spam.classify`'s reason for a sender on the system blocklist. */
        const val BLOCKED = "blocked"

        /** The SMS thread of a one-to-one row and its one address; null for groups and WhatsApp-only rows. */
        fun singleSender(
            row: InboxRow,
            sms: Map<Long, SmsThreadRow>,
        ): Pair<Long, String>? {
            val newest = row.threads.firstOrNull { it.id is ThreadId.Sms } ?: return null
            if (newest.isGroup) return null
            val id = (newest.id as ThreadId.Sms).id
            val sender = sms[id]?.addresses?.singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return id to sender
        }

        /** GPUI `is_spam`'s shape test: SMS only, the newest thread one-to-one. */
        fun candidate(row: InboxRow): Boolean {
            val newest = row.threads.firstOrNull() ?: return false
            return row.channels == listOf(Channel.Sms) && newest.id is ThreadId.Sms && !newest.isGroup
        }
    }
}
