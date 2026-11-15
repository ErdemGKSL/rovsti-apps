package dev.rosty.messages.conversation

import android.util.Patterns
import androidx.compose.runtime.Immutable
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.DateDetectRequest
import dev.rosty.core.model.chat.DetectActionKind
import dev.rosty.core.model.chat.PlacesRequest
import dev.rosty.core.model.chat.TextDetectRequest
import dev.rosty.core.model.messages.OtpRequest
import dev.rosty.core.ops.ChatOps
import dev.rosty.core.ops.MessagesOps
import java.util.TimeZone

/** What a tap pill under a message does (M8, M19, M25, M35, M40, the OTP code). */
enum class PillKind { Call, Message, CopyNumber, Email, CopyEmail, CopyCode, Calendar, Map, Link }

/** One tap pill under a bubble. [value] is what the action needs: a number, an address, a code, a URL. */
@Immutable
data class PillUi(
    val kind: PillKind,
    val label: String,
    val value: String,
    /** A calendar pill's event: start and end, all day, title. */
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val allDay: Boolean = false,
    val title: String = "",
)

/** The pill words from `strings.xml` (Rust's own labels are English and are not shown). */
interface PillWords {
    fun call(number: String): String

    val message: String
    val copyNumber: String

    fun email(address: String): String

    val copyEmail: String

    fun copyCode(code: String): String

    val calendar: String
    val map: String

    fun link(host: String): String
}

/**
 * The detection pills of a message: phone numbers and addresses (`text_detect.detect`), dates (`date_detect.detect`),
 * places and map links (`places.detect`), a one-time code in an incoming message (`sms.otp`), and web links. Answers are
 * kept per bubble key and text, so scrolling back does not ask the core again.
 */
class MessageDetector(
    private val chat: ChatOps,
    private val ops: MessagesOps,
    private val words: PillWords,
    private val now: () -> Long,
    private val maxPills: Int = MAX_PILLS,
) {
    private val cache = HashMap<String, List<PillUi>>()

    suspend fun pills(bubble: BubbleUi): List<PillUi> {
        if (bubble.deleted || bubble.text.isBlank()) return emptyList()
        val key = bubble.key + "\u0000" + bubble.text
        cache[key]?.let { return it }
        val body = bubble.text
        val out = ArrayList<PillUi>()
        if (!bubble.outgoing) {
            ops.otp(OtpRequest(body)).getOrNull()?.otp?.let {
                out +=
                    PillUi(PillKind.CopyCode, words.copyCode(it.code), it.code)
            }
        }
        chat.textDetect(TextDetectRequest(body)).getOrNull()?.actions?.forEach { a ->
            out +=
                when (a.kind) {
                    DetectActionKind.Call -> PillUi(PillKind.Call, words.call(a.value), a.value)
                    DetectActionKind.Message -> PillUi(PillKind.Message, words.message, a.value)
                    DetectActionKind.CopyNumber -> PillUi(PillKind.CopyNumber, words.copyNumber, a.value)
                    DetectActionKind.Email -> PillUi(PillKind.Email, words.email(a.value), a.value)
                    DetectActionKind.CopyEmail -> PillUi(PillKind.CopyEmail, words.copyEmail, a.value)
                }
        }
        val at = now()
        val dates =
            chat
                .dateDetect(
                    DateDetectRequest(body, at, TimeZone.getDefault().getOffset(at) / MS_PER_S),
                ).getOrNull()
        dates?.dates?.firstOrNull()?.let { d ->
            out += PillUi(PillKind.Calendar, words.calendar, d.text, d.startMs, d.endMs, d.allDay, dates.eventTitle)
        }
        chat.places(PlacesRequest(body)).getOrNull()?.places?.firstOrNull()?.let { p ->
            out += PillUi(PillKind.Map, words.map, p.geoUrl)
        }
        val links = Patterns.WEB_URL.matcher(body)
        if (links.find()) {
            val url = links.group().let { if (it.contains("://")) it else "https://$it" }
            val host =
                android.net.Uri
                    .parse(url)
                    .host
                    ?.removePrefix("www.")
            if (host != null && out.none { it.kind == PillKind.Map && it.value.contains(host) }) {
                out += PillUi(PillKind.Link, words.link(host), url)
            }
        }
        val pills = out.distinctBy { it.kind to it.value }.take(maxPills)
        cache[key] = pills
        return pills
    }

    private companion object {
        const val MAX_PILLS = 4
        const val MS_PER_S = 1000
    }
}
