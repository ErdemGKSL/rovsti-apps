@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.messages

import dev.rovsti.core.model.phone.BlockedNumber
import dev.rovsti.core.model.phone.ExternallyTagged
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Models of `spam.*` and `notify.build` (E2.11a), mirroring `crates/rovsti_ffi/fixtures/{spam,notify}.*`.
// Stability: `compose_stability.conf`.

/** One spam rule (`"unknown_with_link"`, `{"keyword": {"text": ..}}`, `{"sender": {"pattern": ..}}`, `{"short_code_only": true}`). */
@Serializable(with = SpamRuleSerializer::class)
sealed interface SpamRule {
    /** Sender id or number; `*` is a wildcard, matching is case-insensitive. */
    data class Sender(
        val pattern: String,
    ) : SpamRule

    /** Case-insensitive substring of the body. */
    data class Keyword(
        val text: String,
    ) : SpamRule

    /** A link from a sender that is not a contact. */
    data object UnknownWithLink : SpamRule

    /** When `true`, short-code senders (3 to 6 digits) are spam; passcodes from them stay ham. */
    data class ShortCodeOnly(
        val enabled: Boolean,
    ) : SpamRule
}

internal object SpamRuleSerializer : ExternallyTagged<SpamRule>("SpamRule") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): SpamRule =
        when (tag) {
            "unknown_with_link" -> {
                SpamRule.UnknownWithLink
            }

            "sender" -> {
                SpamRule.Sender(
                    (value ?: unknown(tag))
                        .jsonObject
                        .getValue("pattern")
                        .jsonPrimitive.content,
                )
            }

            "keyword" -> {
                SpamRule.Keyword(
                    (value ?: unknown(tag))
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive.content,
                )
            }

            "short_code_only" -> {
                SpamRule.ShortCodeOnly((value ?: unknown(tag)).jsonPrimitive.boolean)
            }

            else -> {
                unknown(tag)
            }
        }

    override fun toJson(value: SpamRule): JsonElement =
        when (value) {
            SpamRule.UnknownWithLink -> JsonPrimitive("unknown_with_link")
            is SpamRule.Sender -> tagged("sender", JsonObject(mapOf("pattern" to JsonPrimitive(value.pattern))))
            is SpamRule.Keyword -> tagged("keyword", JsonObject(mapOf("text" to JsonPrimitive(value.text))))
            is SpamRule.ShortCodeOnly -> tagged("short_code_only", JsonPrimitive(value.enabled))
        }
}

/** The spam settings: rules, a master switch and the senders that are never spam. */
@Serializable
data class SpamRules(
    val enabled: Boolean = true,
    val rules: List<SpamRule> = emptyList(),
    /** Senders that are never spam. */
    val allow: List<String> = emptyList(),
)

@Serializable
data class ClassifyRequest(
    @EncodeDefault(NEVER) val rules: SpamRules = SpamRules(),
    val sender: String,
    val body: String,
    /** The sender is in the contacts. */
    @EncodeDefault(NEVER) val known: Boolean = false,
    /** The system blocklist rows. */
    @EncodeDefault(NEVER) val blocked: List<BlockedNumber> = emptyList(),
)

@Serializable
data class ClassifyAnswer(
    val spam: Boolean,
    /** A stable machine key: `blocked`, `sender_rule`, `keyword_rule`, `short_code`, `unknown_link`. */
    val reason: String?,
)

@Serializable
data class AddRuleRequest(
    @EncodeDefault(NEVER) val rules: SpamRules = SpamRules(),
    val rule: SpamRule,
)

/** `spam.add_rule` answer. */
@Serializable
data class RulesAnswer(
    val rules: SpamRules,
)

@Serializable
data class InsertRuleRequest(
    @EncodeDefault(NEVER) val rules: SpamRules = SpamRules(),
    val index: Int,
    val rule: SpamRule,
)

@Serializable
data class InsertRuleAnswer(
    val rules: SpamRules,
    /** `false` when the list is full. */
    val inserted: Boolean,
)

@Serializable
data class RemoveRuleRequest(
    @EncodeDefault(NEVER) val rules: SpamRules = SpamRules(),
    val index: Int,
)

@Serializable
data class RemoveRuleAnswer(
    val rules: SpamRules,
    val removed: Boolean,
)

@Serializable
data class BodyRequest(
    val body: String,
)

@Serializable
data class LinksAnswer(
    /** In order, without duplicates, ready to open (`https://` added when a token has no scheme). */
    val links: List<String>,
)

@Serializable
data class OpenLinksRequest(
    val body: String,
    /** At most this many pills (3 when absent). */
    @EncodeDefault(NEVER) val max: Int? = null,
)

/** One "Open link" pill. */
@Serializable
data class OpenLink(
    val label: String,
    val url: String,
)

@Serializable
data class OpenLinksAnswer(
    val pills: List<OpenLink>,
)

@Serializable
data class ContainsLinkAnswer(
    val contains: Boolean,
)

// ---- notify.build

/** A message that arrived, for the notification digest. */
@Serializable
data class Incoming(
    /** The conversation key and notification tag: `sms:<id>` or `wa:<jid>`. */
    val thread: String,
    @SerialName("sender_title") val senderTitle: String,
    val body: String,
    @SerialName("at_ms") val atMs: Long,
    @SerialName("verdict_spam") val verdictSpam: Boolean,
)

@Serializable
data class NotifyRequest(
    @SerialName("thread_title") val threadTitle: String,
    @SerialName("is_group") @EncodeDefault(NEVER) val isGroup: Boolean = false,
    /** The conversation's recent messages, oldest first. */
    val recent: List<Incoming>,
    /** The passcode found in the newest message (`otp.find`); it turns off the inline reply. */
    @SerialName("otp_code") @EncodeDefault(NEVER) val otpCode: String? = null,
)

/** The notification to post. */
@Serializable
data class NotificationSpec(
    val tag: String,
    val title: String,
    /** Oldest first. */
    val lines: List<String>,
    /** A passcode for a "Copy code" action. */
    val otp: String?,
    /** Whether to offer inline reply. */
    val reply: Boolean,
    /** Update the digest without alerting. */
    val silent: Boolean,
)

@Serializable
data class NotifyAnswer(
    /** `null` when nothing but spam is left. */
    val spec: NotificationSpec?,
    @SerialName("max_lines") val maxLines: Int,
)
