package dev.rosty.messages.spam

import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.messages.AddRuleRequest
import dev.rosty.core.model.messages.ChatTextSize
import dev.rosty.core.model.messages.ClassifyAnswer
import dev.rosty.core.model.messages.ClassifyRequest
import dev.rosty.core.model.messages.DirRequest
import dev.rosty.core.model.messages.DraftsAnswer
import dev.rosty.core.model.messages.InsertRuleAnswer
import dev.rosty.core.model.messages.InsertRuleRequest
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.PrefsEditAnswer
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.model.messages.PrefsLoadAnswer
import dev.rosty.core.model.messages.PrefsSaveRequest
import dev.rosty.core.model.messages.RemoveRuleAnswer
import dev.rosty.core.model.messages.RemoveRuleRequest
import dev.rosty.core.model.messages.RulesAnswer
import dev.rosty.core.model.messages.SmsMarks
import dev.rosty.core.model.messages.SpamMark
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.model.messages.VoiceSpeed
import dev.rosty.core.ops.NativeMessagesOps

/** Prefs with the given spam settings and nothing else. */
fun prefsWith(spam: SpamRules = SpamRules(rules = listOf(SpamRule.UnknownWithLink))) =
    MessagesPrefs(
        spam = spam,
        simChoices = emptyMap(),
        notifications = true,
        otpCopy = true,
        muted = emptyList(),
        quickReplies = emptyList(),
        voiceSpeed = VoiceSpeed.X1,
        smsStars = emptyMap(),
        muteUntil = emptyMap(),
        smsMarks = SmsMarks(),
        chatTextSize = ChatTextSize.Default,
    )

/**
 * A [FakeRustCore] that answers the `spam.*` and `messages.prefs_*` ops the spam code calls, with a small Kotlin model
 * of `rosty_core::spam` (trimmed, case-insensitive duplicates, 64 rules at most; allow beats rules, a blocked sender
 * is spam, then sender and keyword rules). The real rules are tested in Rust; this only lets the view models run.
 */
class SpamCore(
    initial: MessagesPrefs = prefsWith(),
) {
    val core = FakeRustCore()
    val ops = NativeMessagesOps(core)

    /** The prefs as last saved through `messages.prefs_save`. */
    var saved: MessagesPrefs? = null
        private set

    private var stored = initial

    init {
        core.onTyped("messages.prefs_load", DirRequest.serializer(), PrefsLoadAnswer.serializer()) {
            Outcome.Ok(PrefsLoadAnswer(stored, isReset = false))
        }
        core.onTyped("messages.prefs_save", PrefsSaveRequest.serializer(), Empty.serializer()) {
            stored = it.prefs
            saved = it.prefs
            Outcome.Ok(Empty)
        }
        core.onTyped("messages.drafts_load", DirRequest.serializer(), DraftsAnswer.serializer()) {
            Outcome.Ok(DraftsAnswer(emptyMap()))
        }
        core.onTyped("spam.add_rule", AddRuleRequest.serializer(), RulesAnswer.serializer()) { req ->
            add(req.rules, req.rule)
        }
        core.onTyped("spam.remove_rule", RemoveRuleRequest.serializer(), RemoveRuleAnswer.serializer()) { req ->
            val ok = req.index in req.rules.rules.indices
            val rest = req.rules.rules.filterIndexed { i, _ -> i != req.index }
            Outcome.Ok(RemoveRuleAnswer(req.rules.copy(rules = rest), ok))
        }
        core.onTyped("spam.insert_rule", InsertRuleRequest.serializer(), InsertRuleAnswer.serializer()) { req ->
            if (req.rules.rules.size >= MAX) return@onTyped Outcome.Ok(InsertRuleAnswer(req.rules, false))
            val list = req.rules.rules.toMutableList()
            list.add(req.index.coerceIn(0, list.size), req.rule)
            Outcome.Ok(InsertRuleAnswer(req.rules.copy(rules = list), true))
        }
        core.onTyped("messages.prefs_edit", PrefsEditRequest.serializer(), PrefsEditAnswer.serializer(), ::edit)
        core.onTyped("spam.classify", ClassifyRequest.serializer(), ClassifyAnswer.serializer()) { req ->
            Outcome.Ok(classify(req))
        }
    }

    private fun add(
        rules: SpamRules,
        rule: SpamRule,
    ): Outcome<RulesAnswer> {
        val trimmed =
            when (rule) {
                is SpamRule.Sender -> SpamRule.Sender(rule.pattern.trim())
                is SpamRule.Keyword -> SpamRule.Keyword(rule.text.trim())
                else -> rule
            }
        val text = RuleKind.textOf(trimmed)
        if (RuleKind.of(trimmed).needsText && text.replace("*", "").isBlank()) {
            return Outcome.Err(RustError.Invalid("the rule is empty"))
        }
        val dup =
            rules.rules.any {
                RuleKind.of(it) == RuleKind.of(trimmed) && RuleKind.textOf(it).equals(text, ignoreCase = true)
            }
        if (dup) return Outcome.Err(RustError.Invalid("the rule already exists"))
        if (rules.rules.size >= MAX) return Outcome.Err(RustError.Invalid("at most $MAX rules are allowed"))
        return Outcome.Ok(RulesAnswer(rules.copy(rules = rules.rules + trimmed)))
    }

    private fun edit(req: PrefsEditRequest): Outcome<PrefsEditAnswer> {
        val prefs = req.prefs
        val spam = prefs.spam
        val sender = req.sender.trim()

        fun answer(
            next: SpamRules,
            mark: SpamMark? = null,
        ) = Outcome.Ok(PrefsEditAnswer(prefs.copy(spam = next), next != spam, mark))
        return when (req.edit) {
            "allow_sender" -> {
                if (sender.isEmpty() || spam.allow.any { it.equals(sender, true) }) {
                    answer(spam)
                } else {
                    answer(spam.copy(allow = spam.allow + sender))
                }
            }

            "unallow_sender" -> {
                answer(spam.copy(allow = spam.allow.filterNot { it.equals(sender, true) }))
            }

            "remove_allowed" -> {
                answer(spam.copy(allow = spam.allow.filterIndexed { i, _ -> i != req.index }))
            }

            "mark_spam" -> {
                val wasAllowed = spam.allow.any { it.equals(sender, true) }
                val unallowed = spam.copy(allow = spam.allow.filterNot { it.equals(sender, true) })
                when (val added = add(unallowed, SpamRule.Sender(sender))) {
                    is Outcome.Ok -> {
                        answer(added.value.rules, SpamMark(ruleAdded = true, wasAllowed = wasAllowed))
                    }

                    is Outcome.Err -> {
                        if (added.error.message?.contains("already") == true) {
                            answer(unallowed, SpamMark(ruleAdded = false, wasAllowed = wasAllowed))
                        } else {
                            added
                        }
                    }
                }
            }

            "unmark_spam" -> {
                val mark = req.mark ?: return Outcome.Err(RustError.Invalid("no mark"))
                var next = spam
                if (mark.ruleAdded) {
                    next =
                        next.copy(
                            rules =
                                next.rules.filterNot {
                                    it is SpamRule.Sender &&
                                        it.pattern.equals(sender, true)
                                },
                        )
                }
                if (mark.wasAllowed) next = next.copy(allow = next.allow + sender)
                answer(next)
            }

            else -> {
                Outcome.Err(RustError.Invalid("unknown edit ${req.edit}"))
            }
        }
    }

    private fun classify(req: ClassifyRequest): ClassifyAnswer {
        val sender = req.sender.trim()
        if (req.known || req.rules.allow.any { it.equals(sender, true) }) return ClassifyAnswer(false, null)
        if (req.blocked.any { it.numberRaw.filter(Char::isDigit) == sender.filter(Char::isDigit) }) {
            return ClassifyAnswer(true, "blocked")
        }
        if (!req.rules.enabled) return ClassifyAnswer(false, null)
        for (rule in req.rules.rules) {
            when (rule) {
                is SpamRule.Sender -> {
                    val pattern = rule.pattern.lowercase()
                    val hit =
                        if (pattern.endsWith("*")) {
                            sender.lowercase().startsWith(pattern.dropLast(1))
                        } else {
                            sender.equals(rule.pattern, true)
                        }
                    if (hit) return ClassifyAnswer(true, "sender_rule")
                }

                is SpamRule.Keyword -> {
                    if (req.body.contains(rule.text, ignoreCase = true)) return ClassifyAnswer(true, "keyword_rule")
                }

                else -> {}
            }
        }
        if (SpamRule.UnknownWithLink in req.rules.rules &&
            "http" in req.body
        ) {
            return ClassifyAnswer(true, "unknown_link")
        }
        return ClassifyAnswer(false, null)
    }

    companion object {
        const val MAX = 64
    }
}
