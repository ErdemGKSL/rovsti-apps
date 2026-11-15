package dev.rosty.messages.spam

import dev.rosty.core.model.messages.SpamRule

/** What a spam rule looks at (GPUI `settings_ui::RuleKind`): the four kinds `rosty_core::spam::SpamRule` has. */
enum class RuleKind {
    Sender,
    Keyword,
    UnknownLinks,
    ShortCodes,
    ;

    /** Sender and keyword rules carry a text; the other two are on or off. */
    val needsText: Boolean get() = this == Sender || this == Keyword

    /** The rule this kind makes of [text] (Rust trims and judges it). */
    fun build(text: String): SpamRule =
        when (this) {
            Sender -> SpamRule.Sender(text)
            Keyword -> SpamRule.Keyword(text)
            UnknownLinks -> SpamRule.UnknownWithLink
            ShortCodes -> SpamRule.ShortCodeOnly(true)
        }

    companion object {
        fun of(rule: SpamRule): RuleKind =
            when (rule) {
                is SpamRule.Sender -> Sender
                is SpamRule.Keyword -> Keyword
                SpamRule.UnknownWithLink -> UnknownLinks
                is SpamRule.ShortCodeOnly -> ShortCodes
            }

        /** The text of a sender or keyword rule, empty for the others. */
        fun textOf(rule: SpamRule): String =
            when (rule) {
                is SpamRule.Sender -> rule.pattern
                is SpamRule.Keyword -> rule.text
                else -> ""
            }
    }
}
