package dev.rovsti.phone.incoming

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.chat.CallReminderAnswer
import dev.rovsti.core.model.chat.CallReminderRequest
import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.chat.ReminderSlotOption
import dev.rovsti.core.model.chat.ReminderSlotsRequest
import dev.rovsti.core.model.phone.ReplyChoicesRequest
import dev.rovsti.core.ops.ChatOps
import dev.rovsti.core.ops.PhoneOps

/** The reply choices the core offers for a declined call. */
data class ReplyChoices(
    val choices: List<String>,
    val maxChars: Int,
)

/**
 * What the Incoming screen asks of the Rust core (the saved replies or the defaults, the reminder slots and the armed
 * reminder). A seam: the view model's tests use a fake, one test runs the real core through [CoreIncomingBackend].
 */
interface IncomingBackend {
    suspend fun replyChoices(saved: List<String>): ReplyChoices?

    suspend fun reminderSlots(
        nowMs: Long,
        tzOffsetS: Int,
    ): List<ReminderSlotOption>

    suspend fun reminder(
        number: String,
        name: String?,
        slot: ReminderSlot,
        nowMs: Long,
        tzOffsetS: Int,
    ): CallReminderAnswer?
}

class CoreIncomingBackend(
    private val ops: PhoneOps,
    private val chat: ChatOps,
) : IncomingBackend {
    override suspend fun replyChoices(saved: List<String>): ReplyChoices? =
        (
            ops.replyChoices(
                ReplyChoicesRequest(saved),
            ) as? Outcome.Ok
        )?.value?.let { ReplyChoices(it.choices, it.maxChars) }

    override suspend fun reminderSlots(
        nowMs: Long,
        tzOffsetS: Int,
    ): List<ReminderSlotOption> =
        (chat.reminderSlots(ReminderSlotsRequest(nowMs, tzOffsetS)) as? Outcome.Ok)?.value?.slots.orEmpty()

    override suspend fun reminder(
        number: String,
        name: String?,
        slot: ReminderSlot,
        nowMs: Long,
        tzOffsetS: Int,
    ): CallReminderAnswer? =
        (chat.callReminder(CallReminderRequest(number, name, slot, nowMs, tzOffsetS)) as? Outcome.Ok)?.value
}
