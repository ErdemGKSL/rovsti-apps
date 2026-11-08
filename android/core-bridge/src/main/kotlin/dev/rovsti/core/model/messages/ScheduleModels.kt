@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.messages

import dev.rovsti.core.model.phone.ExternallyTagged
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Models of `schedule.*` (E2.11a), mirroring `crates/rovsti_ffi/fixtures/schedule.*`. The queue is a JSON string the
// caller keeps (`scheduled.json`); every op answers the new string. Stability: `compose_stability.conf`.

/** Where a scheduled message goes (`{"Sms": {"to": .., "sub_id": ..}}`, `{"WhatsApp": {"chat": ..}}`). */
@Serializable(with = ScheduleTargetSerializer::class)
sealed interface ScheduleTarget {
    data class Sms(
        val to: String,
        val subId: Int?,
    ) : ScheduleTarget

    data class WhatsApp(
        val chat: String,
    ) : ScheduleTarget
}

internal object ScheduleTargetSerializer : ExternallyTagged<ScheduleTarget>("ScheduleTarget") {
    override fun fromTag(
        tag: String,
        value: JsonElement?,
    ): ScheduleTarget {
        val body = (value ?: unknown(tag)).jsonObject
        return when (tag) {
            "Sms" -> {
                ScheduleTarget.Sms(
                    body.getValue("to").jsonPrimitive.content,
                    body["sub_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int,
                )
            }

            "WhatsApp" -> {
                ScheduleTarget.WhatsApp(body.getValue("chat").jsonPrimitive.content)
            }

            else -> {
                unknown(tag)
            }
        }
    }

    override fun toJson(value: ScheduleTarget): JsonElement =
        when (value) {
            is ScheduleTarget.Sms -> {
                tagged(
                    "Sms",
                    JsonObject(
                        mapOf(
                            "to" to JsonPrimitive(value.to),
                            "sub_id" to (value.subId?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    ),
                )
            }

            is ScheduleTarget.WhatsApp -> {
                tagged("WhatsApp", JsonObject(mapOf("chat" to JsonPrimitive(value.chat))))
            }
        }
}

/** A message waiting for its time. */
@Serializable
data class ScheduledSms(
    val id: Long,
    @SerialName("at_ms") val atMs: Long,
    val target: ScheduleTarget,
    val body: String,
    @SerialName("created_ms") val createdMs: Long,
)

/** What the platform reports after trying to send an item that was due. */
@Serializable
data class FiredSms(
    val id: Long,
    val ok: Boolean,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class ScheduleValidateRequest(
    @SerialName("at_ms") val atMs: Long,
    @SerialName("now_ms") val nowMs: Long,
    val body: String,
)

@Serializable
data class ScheduleValidateAnswer(
    /** Why the message cannot be scheduled, in the user's words. */
    val error: String?,
    @SerialName("min_lead_ms") val minLeadMs: Long,
)

@Serializable
data class ScheduleAddRequest(
    @SerialName("queue_json") @EncodeDefault(NEVER) val queueJson: String = "",
    val item: ScheduledSms,
    /** Validates the item against this time first when given. */
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
)

/** The queue after an op: save [queueJson] as is; [items] is the same queue parsed, ordered by time. */
@Serializable
data class QueueAnswer(
    @SerialName("queue_json") val queueJson: String,
    val items: List<ScheduledSms>,
)

@Serializable
data class ScheduleCancelRequest(
    @SerialName("queue_json") @EncodeDefault(NEVER) val queueJson: String = "",
    val id: Long,
)

@Serializable
data class ScheduleCancelAnswer(
    @SerialName("queue_json") val queueJson: String,
    val items: List<ScheduledSms>,
    val removed: Boolean,
)

@Serializable
data class ScheduleReconcileRequest(
    @SerialName("queue_json") @EncodeDefault(NEVER) val queueJson: String = "",
    val fired: List<FiredSms>,
)

@Serializable
data class ScheduleReconcileAnswer(
    @SerialName("queue_json") val queueJson: String,
    val items: List<ScheduledSms>,
    /** The items that failed, for the "could not send" notice. */
    val failed: List<ScheduledSms>,
)

@Serializable
data class DueSoonRequest(
    @SerialName("queue_json") @EncodeDefault(NEVER) val queueJson: String = "",
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("within_ms") val withinMs: Long,
)

@Serializable
data class DueSoonAnswer(
    /** Oldest first: the list to (re)register with the alarm service. */
    val items: List<ScheduledSms>,
)

@Serializable
data class ScheduleLoadRequest(
    @SerialName("queue_json") @EncodeDefault(NEVER) val queueJson: String = "",
)

@Serializable
data class QuickTimesRequest(
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class QuickTime(
    val label: String,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class QuickTimesAnswer(
    /** "In 1 hour", this/tomorrow evening and tomorrow morning, each at least a minute away. */
    val times: List<QuickTime>,
)
