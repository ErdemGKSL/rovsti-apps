package dev.rosty.messages.schedule

import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.messages.QueueAnswer
import dev.rosty.core.model.messages.ScheduleAddRequest
import dev.rosty.core.model.messages.ScheduleCancelAnswer
import dev.rosty.core.model.messages.ScheduleCancelRequest
import dev.rosty.core.model.messages.ScheduleLoadRequest
import dev.rosty.core.model.messages.ScheduleReconcileAnswer
import dev.rosty.core.model.messages.ScheduleReconcileRequest
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.wa.ScheduledState
import dev.rosty.core.model.wa.ScheduledText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The words of the sheet and the strip in English, as `values/strings.xml` has them. */
internal object EnglishScheduleWords {
    val words =
        ScheduleWords(
            title = "Send later",
            quick = { it },
            day = "Day",
            time = "Time",
            earlierDay = "A day earlier",
            laterDay = "A day later",
            earlierTime = "15 minutes earlier",
            laterTime = "15 minutes later",
            confirm = { "Schedule for $it" },
            tooSoon = "Pick a time at least a minute from now",
            lateNote = "Android may send it a few minutes late. Tap to allow exact alarms.",
            stripTitle = "Scheduled",
            cancel = "Cancel",
            cancelFor = { "Cancel the message scheduled for $it" },
            waiting = { time, text -> "$time · $text" },
            notSent = { "Not sent · $it" },
            more = { "Show $it more" },
        )
}

/** The same in Turkish, as `values-tr/strings.xml` has them. */
internal object TurkishScheduleWords {
    val words =
        ScheduleWords(
            title = "Sonra gönder",
            quick = {
                when (it) {
                    "In 1 hour" -> "1 saat sonra"
                    "This evening" -> "Bu akşam"
                    "Tomorrow evening" -> "Yarın akşam"
                    "Tomorrow morning" -> "Yarın sabah"
                    else -> it
                }
            },
            day = "Gün",
            time = "Saat",
            earlierDay = "Bir gün önce",
            laterDay = "Bir gün sonra",
            earlierTime = "15 dakika önce",
            laterTime = "15 dakika sonra",
            confirm = { "$it için zamanla" },
            tooSoon = "En az bir dakika sonrasını seçin",
            lateNote = "Android birkaç dakika geç gönderebilir. Tam zamanlı alarmlara izin vermek için dokunun.",
            stripTitle = "Zamanlandı",
            cancel = "İptal",
            cancelFor = { "$it için zamanlanan mesajı iptal et" },
            waiting = { time, text -> "$time · $text" },
            notSent = { "Gönderilemedi · $it" },
            more = { "$it tane daha göster" },
        )
}

/**
 * Rust's `schedule.*` ops in miniature over a [FakeRustCore]: the queue string is the JSON list of its items, kept in
 * time order. Enough for the centre's own rules (what it saves, arms, cancels and announces).
 */
internal fun FakeRustCore.scriptScheduleQueue() {
    val json = Json
    val list = ListSerializer(ScheduledSms.serializer())

    fun read(queue: String): List<ScheduledSms> =
        try {
            if (queue.isBlank()) emptyList() else json.decodeFromString(list, queue)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    fun write(items: List<ScheduledSms>): String = json.encodeToString(list, items.sortedBy { it.atMs })

    onTyped("schedule.load", ScheduleLoadRequest.serializer(), QueueAnswer.serializer()) { req ->
        val items = read(req.queueJson)
        Outcome.Ok(QueueAnswer(write(items), items.sortedBy { it.atMs }))
    }
    onTyped("schedule.add", ScheduleAddRequest.serializer(), QueueAnswer.serializer()) { req ->
        val items = read(req.queueJson)
        if (items.any { it.id == req.item.id }) {
            Outcome.Err(RustError.Invalid("a scheduled message with this id exists"))
        } else {
            val next = (items + req.item).sortedBy { it.atMs }
            Outcome.Ok(QueueAnswer(write(next), next))
        }
    }
    onTyped("schedule.cancel", ScheduleCancelRequest.serializer(), ScheduleCancelAnswer.serializer()) { req ->
        val items = read(req.queueJson)
        val next = items.filterNot { it.id == req.id }
        Outcome.Ok(ScheduleCancelAnswer(write(next), next, removed = next.size < items.size))
    }
    onTyped("schedule.reconcile", ScheduleReconcileRequest.serializer(), ScheduleReconcileAnswer.serializer()) { req ->
        val items = read(req.queueJson)
        val fired = req.fired.associateBy { it.id }
        val next = items.filterNot { it.id in fired }
        Outcome.Ok(ScheduleReconcileAnswer(write(next), next, failed = items.filter { fired[it.id]?.ok == false }))
    }
}

/** A gateway that keeps scheduled texts in memory; [linked] false answers `NotFound` like an unlinked app. */
internal class FakeWaScheduleRemote : WaScheduleRemote {
    val items = ArrayList<ScheduledText>()
    var linked = true
    private var next = 1

    private fun <T> unlinked(): Outcome<T> = Outcome.Err(RustError.NotFound("WhatsApp is not linked"))

    override suspend fun schedule(
        chat: String,
        text: String,
        atMs: Long,
    ): Outcome<ScheduledText> {
        if (!linked) return unlinked()
        val item = ScheduledText("s${next++}", chat, text, atMs, 0, ScheduledState.Pending)
        items += item
        return Outcome.Ok(item)
    }

    override suspend fun list(): Outcome<List<ScheduledText>> = if (linked) Outcome.Ok(items.toList()) else unlinked()

    override suspend fun cancel(id: String): Outcome<Unit> =
        when {
            !linked -> unlinked()
            items.removeAll { it.id == id } -> Outcome.Ok(Unit)
            else -> Outcome.Err(RustError.NotFound("no such scheduled text"))
        }
}

/** A [QueueStore] in memory. */
internal class MemoryQueueStore(
    var content: String = "",
) : QueueStore {
    var writes = 0

    override fun read(): String = content

    override fun write(json: String): Boolean {
        content = json
        writes++
        return true
    }
}
