package dev.rosty.messages.starred

import dev.rosty.core.Outcome
import dev.rosty.core.model.chat.LocalStar
import dev.rosty.core.model.chat.LocalStarAction
import dev.rosty.core.model.chat.LocalStarsAnswer
import dev.rosty.core.model.chat.LocalStarsRequest
import dev.rosty.core.model.chat.MakeReminderAnswer
import dev.rosty.core.model.chat.MakeReminderRequest
import dev.rosty.core.model.chat.MessageReminder
import dev.rosty.core.model.chat.ReminderSlot
import dev.rosty.core.model.chat.ReminderSlotOption
import dev.rosty.core.model.chat.ReminderSlotsAnswer
import dev.rosty.core.model.chat.ReminderSlotsRequest
import dev.rosty.core.model.chat.StarredRow
import dev.rosty.core.model.chat.StarredRowsAnswer
import dev.rosty.core.model.chat.StarredRowsRequest
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.ops.NativeChatOps
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.remind.RemindCenter
import dev.rosty.messages.spam.SpamCore
import dev.rosty.messages.spam.prefsWith
import dev.rosty.platform.notify.MessageReminderItem
import dev.rosty.platform.notify.RemindersRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * A fake core for the star and reminder ops with a small Kotlin model of `rosty_core::starred` (set, unset, rows
 * newest first on one line) and `rosty_core::reminder` (the four slots; "tonight" is 20:00, "tomorrow" 09:00, UTC). The
 * real rules are tested in Rust (`crates/rosty_ffi/fixtures/starred.*`, `message_reminder.*`); this lets the Kotlin
 * side run.
 */
class MarkCore(
    prefs: MessagesPrefs = prefsWith(),
) {
    val spam = SpamCore(prefs)
    val core = spam.core
    val chat = NativeChatOps(core)

    init {
        core.onTyped("starred.local", LocalStarsRequest.serializer(), LocalStarsAnswer.serializer()) { req ->
            val id = req.id.orEmpty()
            val star = req.star
            val next =
                when (req.action) {
                    LocalStarAction.Set -> if (star == null) req.stars else req.stars + (id to star)
                    LocalStarAction.Unset -> req.stars - id
                    LocalStarAction.ForgetThread -> req.stars.filterValues { it.threadId != req.threadId }
                    LocalStarAction.Show -> req.stars
                }
            val mine = next.filterValues { it.threadId == req.threadId }
            Outcome.Ok(
                LocalStarsAnswer(
                    stars = next,
                    changed = next != req.stars,
                    starred = id in next,
                    rows = rows(mine),
                    threadCount = mine.size,
                    total = next.size,
                    emptyText = EMPTY,
                ),
            )
        }
        core.onTyped("starred.rows", StarredRowsRequest.serializer(), StarredRowsAnswer.serializer()) { req ->
            val rows =
                req.items
                    .filter { it.starred }
                    .sortedByDescending { it.atMs }
                    .map { StarredRow(it.id, it.author, oneLine(it.preview), it.atMs) }
            Outcome.Ok(StarredRowsAnswer(rows, EMPTY))
        }
        core.onTyped("message_reminder.slots", ReminderSlotsRequest.serializer(), ReminderSlotsAnswer.serializer()) {
            Outcome.Ok(
                ReminderSlotsAnswer(
                    ReminderSlot.entries.map { s ->
                        ReminderSlotOption(s, s.name, due(s, it.nowMs))
                    },
                ),
            )
        }
        core.onTyped(
            "message_reminder.make",
            MakeReminderRequest.serializer(),
            MakeReminderAnswer.serializer(),
        ) { req ->
            val at = due(req.slot, req.nowMs)
            val reminder =
                MessageReminder(req.nowMs, req.tag, req.messageId, req.messageAtMs, at, req.title, req.preview)
            val arm =
                JSONObject()
                    .put("id", reminder.id)
                    .put("tag", req.tag)
                    .put("message_id", req.messageId)
                    .put("message_at_ms", req.messageAtMs)
                    .put("at_ms", at)
                    .put("notif_title", "Reminder: ${req.title}")
                    .put("notif_body", req.preview)
            Outcome.Ok(
                MakeReminderAnswer(
                    reminder,
                    arm.toString(),
                    "Reminder: ${req.title}",
                    req.preview,
                    "Reminder set",
                    "Reminder",
                    "",
                ),
            )
        }
    }

    fun store(
        scope: CoroutineScope,
        io: CoroutineDispatcher,
    ) = MessagesStore(spam.ops, scope, io = io, debounceMs = 0)

    private fun rows(stars: Map<String, LocalStar>) =
        stars.entries
            .sortedByDescending { it.value.atMs }
            .map { (id, s) -> StarredRow(id, s.author, oneLine(s.preview), s.atMs) }

    private fun oneLine(text: String) = text.lineSequence().joinToString(" ").take(PREVIEW)

    companion object {
        const val EMPTY = "No starred messages"
        private const val PREVIEW = 120
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR

        /** When [slot] fires, in UTC. */
        fun due(
            slot: ReminderSlot,
            now: Long,
        ): Long =
            when (slot) {
                ReminderSlot.In15Min -> now + 15 * MIN
                ReminderSlot.In1Hour -> now + HOUR
                ReminderSlot.Tonight -> now - now % DAY + 20 * HOUR
                ReminderSlot.TomorrowMorning -> now - now % DAY + DAY + 9 * HOUR
            }
    }
}

/**
 * The WhatsApp side of [StarCenter] as a script of what the store does: a queued star sets the flag of a message it
 * [knows] at once, and [starred] answers the flagged ones newest first. Not [linked]: nothing is queued or answered.
 */
class FakeWaStars {
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    var linked = true

    /** The stored messages, by id. */
    val knows = mutableMapOf<String, WaStarRow>()

    /** The ids the store has as starred. */
    val flagged = mutableSetOf<String>()

    /** Every star queued in the outbox: chat, message id, starred. */
    val queued = mutableListOf<Triple<String, String, Boolean>>()

    val seams =
        WaStarSeams(
            changes = changes,
            starred = { if (linked) knows.values.filter { it.id in flagged }.sortedByDescending { it.atMs } else null },
            star = { chat, id, starred ->
                linked.also {
                    if (it) {
                        queued += Triple(chat, id, starred)
                        if (starred) flagged += id else flagged -= id
                    }
                }
            },
        )
}

/** Message reminders in memory instead of `SharedPreferences` and `AlarmManager`. */
class FakeReminders : RemindersRepository<MessageReminderItem> {
    private val items = MutableStateFlow<List<MessageReminderItem>>(emptyList())
    var rearmed = 0

    val now: List<MessageReminderItem> get() = items.value

    override fun arm(json: String): Boolean {
        val item = MessageReminderItem.parse(json) ?: return false
        items.value = items.value.filter { it.id != item.id } + item
        return true
    }

    override fun disarm(id: Long): Boolean {
        val had = items.value.any { it.id == id }
        items.value = items.value.filter { it.id != id }
        return had
    }

    override fun armed(): Flow<List<MessageReminderItem>> = items

    override fun rearmAll(): Boolean {
        rearmed++
        return true
    }
}

/** Both centers over the fakes, started, with every dispatcher the caller's. */
class MarkKit(
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    val now: () -> Long,
    prefs: MessagesPrefs = prefsWith(),
) {
    val core = MarkCore(prefs)
    val store = core.store(scope, io)
    val wa = FakeWaStars()
    val repo = FakeReminders()
    val stars = StarCenter(core.chat, store, wa.seams, scope).also { it.start() }
    val reminders = RemindCenter(core.chat, repo, scope, now, tzOffsetS = { 0 }, io = io).also { it.start() }
}
