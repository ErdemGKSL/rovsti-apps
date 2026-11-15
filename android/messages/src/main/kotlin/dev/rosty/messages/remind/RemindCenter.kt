package dev.rosty.messages.remind

import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.MakeReminderRequest
import dev.rosty.core.model.chat.ReminderKind
import dev.rosty.core.model.chat.ReminderSlot
import dev.rosty.core.model.chat.ReminderSlotOption
import dev.rosty.core.model.chat.ReminderSlotsRequest
import dev.rosty.core.ops.ChatOps
import dev.rosty.messages.starred.MarkTarget
import dev.rosty.platform.notify.MessageReminderItem
import dev.rosty.platform.notify.RemindersRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** A reminder that was just armed: what the pill says and how to take it back. */
data class ArmedReminder(
    val id: Long,
    val atMs: Long,
    val slot: ReminderSlot,
)

/**
 * "Remind me" on a message (M33, E6.13). Rust decides the slots and their times (`message_reminder.slots`) and builds
 * the reminder with the payload the alarm layer stores (`message_reminder.make`); `:platform` keeps the alarm and
 * posts the notification, whose tap opens the conversation on the message. This class only joins the two and follows
 * the armed list for the badges on the bubbles.
 */
class RemindCenter(
    private val chat: ChatOps,
    private val repo: RemindersRepository<MessageReminderItem>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val tzOffsetS: (Long) -> Int = { TimeZone.getDefault().getOffset(it) / MS_PER_S },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val started = AtomicBoolean(false)
    private val list = MutableStateFlow<List<MessageReminderItem>>(emptyList())

    /** The armed reminders, now and after every change (one set, cancelled or fired). */
    val armed: StateFlow<List<MessageReminderItem>> = list.asStateFlow()

    /** Sets the stored alarms again (a restart of the phone clears them) and follows the list. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            withContext(io) { repo.rearmAll() }
            repo.armed().flowOn(io).collect { list.value = it }
        }
    }

    /** When the reminder of each message of the conversation [tag] fires, by message id. */
    fun badges(tag: String): Flow<Map<String, Long>> =
        armed
            .map { all ->
                all.filter { it.tag == tag && it.messageId.isNotBlank() }.associate { it.messageId to it.atMs }
            }.distinctUntilChanged()

    /** The reminder armed on the message [id], or null. */
    fun of(id: String): MessageReminderItem? = armed.value.firstOrNull { it.messageId == id }

    /** The choices and when each would fire; empty when the core did not answer. */
    suspend fun slots(): List<ReminderSlotOption> {
        val at = now()
        return chat
            .reminderSlots(ReminderSlotsRequest(at, tzOffsetS(at)))
            .getOrNull()
            ?.slots
            .orEmpty()
    }

    /**
     * Arms a reminder on [target] at [slot], in place of the one it may have. [notificationTitle] is the title of the
     * notification in the phone's language, given the chat's title. Null when it could not be armed.
     */
    suspend fun arm(
        target: MarkTarget,
        slot: ReminderSlot,
        notificationTitle: (String) -> String,
    ): ArmedReminder? {
        val at = now()
        val request =
            MakeReminderRequest(
                kind = ReminderKind.Message,
                tag = target.tag,
                messageId = target.id,
                messageAtMs = target.atMs,
                title = target.title,
                preview = target.preview,
                slot = slot,
                nowMs = at,
                tzOffsetS = tzOffsetS(at),
            )
        val made = chat.makeReminder(request).getOrNull() ?: return null
        val payload =
            try {
                JSONObject(made.armJson).put("notif_title", notificationTitle(target.title)).toString()
            } catch (_: JSONException) {
                made.armJson
            }
        return withContext(io) {
            val old = list.value.filter { it.messageId == target.id && it.id != made.reminder.id }
            if (!repo.arm(payload)) return@withContext null
            old.forEach { repo.disarm(it.id) }
            ArmedReminder(made.reminder.id, made.reminder.atMs, slot)
        }
    }

    /** Takes the reminder [id] back. */
    suspend fun cancel(id: Long): Boolean = withContext(io) { repo.disarm(id) }

    private companion object {
        const val MS_PER_S = 1000
    }
}
