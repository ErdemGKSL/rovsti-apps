package dev.rosty.messages.spam

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.messages.AddRuleRequest
import dev.rosty.core.model.messages.InsertRuleRequest
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.PrefsEditAnswer
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.model.messages.RemoveRuleRequest
import dev.rosty.core.model.messages.SpamMark
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.ops.MessagesOps
import dev.rosty.messages.data.MessagesStore
import dev.rosty.platform.calllog.Blocklist
import dev.rosty.platform.calllog.BlocklistRepository

/** Why a rule was not saved (`spam.add_rule`'s `RuleError`). */
enum class RuleProblem { Empty, Duplicate, TooMany, Failed }

/** What Move to Spam answered. */
sealed interface MarkOutcome {
    data class Marked(
        val mark: SpamMark,
    ) : MarkOutcome

    data object Full : MarkOutcome

    data object Failed : MarkOutcome
}

/** What blocking or unblocking answered, worded by the caller. */
enum class BlockOutcome { Done, Refused, Unavailable }

/**
 * Every spam change of the app, in one place for the inbox menus and the Spam filter page (AD-MSG8). The rules live in
 * the `spam` field of `messages-prefs.json`, edited only through Rust (`spam.*`, `messages.prefs_edit`), so the cold
 * SMS receiver reads the same verdicts; the blocked numbers are the system blocklist, shared with Phone. Kotlin keeps
 * no rule of its own here: it hands the prefs over and stores what comes back.
 */
class SpamActions(
    private val store: MessagesStore,
    private val ops: MessagesOps,
    private val blocklist: BlocklistRepository?,
) {
    private suspend fun rules(): SpamRules? {
        store.load()
        return store.prefs.value?.spam
    }

    private suspend fun saveRules(rules: SpamRules) {
        store.updatePrefs { it.copy(spam = rules) }
    }

    /** One named prefs edit; the answer, or null when Rust refused or the prefs are not loaded. */
    private suspend fun edit(request: (MessagesPrefs) -> PrefsEditRequest): PrefsEditAnswer? {
        store.load()
        val prefs = store.prefs.value ?: return null
        val answer = (ops.prefsEdit(request(prefs)) as? Outcome.Ok)?.value ?: return null
        if (answer.changed) store.updatePrefs { answer.prefs }
        return answer
    }

    suspend fun setEnabled(on: Boolean) {
        val rules = rules() ?: return
        if (rules.enabled != on) saveRules(rules.copy(enabled = on))
    }

    /**
     * Adds [rule], or replaces the rule at [index]: the rule leaves its place, `spam.add_rule` judges the new one
     * against the others (so editing a rule into itself is fine) and `spam.insert_rule` puts it back at [index].
     */
    suspend fun saveRule(
        index: Int?,
        rule: SpamRule,
    ): RuleProblem? {
        val rules = rules() ?: return RuleProblem.Failed
        val others =
            if (index == null) {
                rules
            } else {
                (ops.removeRule(RemoveRuleRequest(rules, index)) as? Outcome.Ok)?.value?.rules
                    ?: return RuleProblem.Failed
            }
        val added =
            when (val answer = ops.addRule(AddRuleRequest(others, rule))) {
                is Outcome.Ok -> answer.value.rules
                is Outcome.Err -> return problemOf(answer.error)
            }
        val next =
            if (index == null) {
                added
            } else {
                val saved = added.rules.last()
                (ops.insertRule(InsertRuleRequest(others, index, saved)) as? Outcome.Ok)?.value?.rules
                    ?: return RuleProblem.Failed
            }
        saveRules(next)
        return null
    }

    /** Deletes the rule at [index]; the removed rule, for Undo ([restoreRule]). */
    suspend fun deleteRule(index: Int): SpamRule? {
        val rules = rules() ?: return null
        val rule = rules.rules.getOrNull(index) ?: return null
        val answer = (ops.removeRule(RemoveRuleRequest(rules, index)) as? Outcome.Ok)?.value ?: return null
        if (!answer.removed) return null
        saveRules(answer.rules)
        return rule
    }

    suspend fun restoreRule(
        index: Int,
        rule: SpamRule,
    ): Boolean {
        val rules = rules() ?: return false
        val answer = (ops.insertRule(InsertRuleRequest(rules, index, rule)) as? Outcome.Ok)?.value ?: return false
        if (answer.inserted) saveRules(answer.rules)
        return answer.inserted
    }

    /** Not spam / Always allow: the sender joins the allow list. False when it was blank or already there. */
    suspend fun allow(sender: String): Boolean =
        edit { PrefsEditRequest(it, "allow_sender", sender = sender) }?.changed == true

    suspend fun unallow(sender: String): Boolean =
        edit { PrefsEditRequest(it, "unallow_sender", sender = sender) }?.changed == true

    /** Removes the allowed sender at [index]; its text, for Undo ([allow]). */
    suspend fun removeAllowed(index: Int): String? {
        val sender = rules()?.allow?.getOrNull(index) ?: return null
        val done = edit { PrefsEditRequest(it, "remove_allowed", index = index) }?.changed
        return sender.takeIf { done == true }
    }

    /**
     * Move to Spam: a sender rule, and off the allow list. [MarkOutcome.Marked] carries what Undo hands back to
     * [unmarkSpam]; a full rule list is [MarkOutcome.Full].
     */
    suspend fun markSpam(sender: String): MarkOutcome {
        store.load()
        val prefs = store.prefs.value ?: return MarkOutcome.Failed
        return when (val answer = ops.prefsEdit(PrefsEditRequest(prefs, "mark_spam", sender = sender))) {
            is Outcome.Ok -> {
                if (answer.value.changed) store.updatePrefs { answer.value.prefs }
                answer.value.mark?.let { MarkOutcome.Marked(it) } ?: MarkOutcome.Failed
            }

            is Outcome.Err -> {
                if (problemOf(answer.error) == RuleProblem.TooMany) MarkOutcome.Full else MarkOutcome.Failed
            }
        }
    }

    suspend fun unmarkSpam(
        sender: String,
        mark: SpamMark,
    ) {
        edit { PrefsEditRequest(it, "unmark_spam", sender = sender, mark = mark) }
    }

    suspend fun block(number: String): BlockOutcome = blockResult(blocklist?.block(number))

    suspend fun unblock(number: String): BlockOutcome = blockResult(blocklist?.unblock(number))

    suspend fun canBlock(): Boolean = blocklist?.canBlock() == true

    private fun blockResult(result: Blocklist.Result?): BlockOutcome =
        when (result) {
            Blocklist.Result.OK -> BlockOutcome.Done
            Blocklist.Result.REFUSED -> BlockOutcome.Refused
            else -> BlockOutcome.Unavailable
        }

    companion object {
        /**
         * The `RuleError` behind an `invalid` answer of `spam.add_rule` / `mark_spam`. Rust sends its sentence and no
         * `detail.reason` for these, so the sentence is matched (`rosty_core::spam::RuleError`'s Display).
         */
        fun problemOf(error: RustError): RuleProblem {
            val text = error.message.orEmpty()
            return when {
                error !is RustError.Invalid -> RuleProblem.Failed
                "already exists" in text -> RuleProblem.Duplicate
                "at most" in text -> RuleProblem.TooMany
                "empty" in text -> RuleProblem.Empty
                else -> RuleProblem.Failed
            }
        }
    }
}
