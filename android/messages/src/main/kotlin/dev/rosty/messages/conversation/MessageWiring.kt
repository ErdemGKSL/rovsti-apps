package dev.rosty.messages.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** [PillWords] from the app's resources. */
class ResourcePillWords(
    private val res: Resources,
) : PillWords {
    override fun call(number: String) = res.getString(R.string.pill_call, number)

    override val message = res.getString(R.string.pill_message)
    override val copyNumber = res.getString(R.string.pill_copy_number)

    override fun email(address: String) = res.getString(R.string.pill_email, address)

    override val copyEmail = res.getString(R.string.pill_copy_email)

    override fun copyCode(code: String) = res.getString(R.string.pill_copy_code, code)

    override val calendar = res.getString(R.string.pill_calendar)
    override val map = res.getString(R.string.pill_map)

    override fun link(host: String) = res.getString(R.string.pill_link, host)
}

/** The detector of a conversation page, with the app's words. */
@Composable
fun rememberDetector(container: MessagesContainer): MessageDetector {
    val res = LocalResources.current
    return remember(container, res) {
        MessageDetector(container.chat, container.ops, ResourcePillWords(res), System::currentTimeMillis)
    }
}

/** The words of select mode (M20). */
@Composable
fun rememberSelectLabels(): SelectLabels {
    val res = LocalResources.current
    return remember(res) {
        SelectLabels(
            count = { n -> res.getQuantityString(R.plurals.menu_selected, n, n) },
            copy = res.getString(R.string.conversation_copy),
            forward = res.getString(R.string.conversation_forward),
            delete = res.getString(R.string.inbox_delete),
            cancel = res.getString(R.string.inbox_cancel),
        )
    }
}

/**
 * What a detection pill does: dial (never calls by itself), write an SMS here, write an e-mail, copy (a code as a
 * sensitive clip), add to the calendar, open the map or the link in the phone's apps.
 */
fun handlePill(
    c: Context,
    pill: PillUi,
    overlays: OverlayController,
    copy: (text: String, sensitive: Boolean) -> Unit,
) {
    fun open(intent: Intent) {
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            overlays.showToast(c.getString(R.string.pill_no_app))
        }
    }
    when (pill.kind) {
        PillKind.Call -> {
            open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", pill.value, null)))
        }

        PillKind.Message -> {
            open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", pill.value, null)).setPackage(c.packageName))
        }

        PillKind.Email -> {
            open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", pill.value, null)))
        }

        PillKind.CopyNumber, PillKind.CopyEmail, PillKind.CopyCode -> {
            copy(pill.value, pill.kind == PillKind.CopyCode)
            overlays.showToast(c.getString(R.string.conversation_copied))
        }

        PillKind.Calendar -> {
            open(
                Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, pill.startMs)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, pill.endMs)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, pill.allDay)
                    .putExtra(CalendarContract.Events.TITLE, pill.title),
            )
        }

        PillKind.Map, PillKind.Link -> {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(pill.value)))
        }
    }
}

/** M14: the rows of a message's info sheet: when it was sent, where it stands, by what. */
fun infoRows(
    res: Resources,
    bubble: BubbleUi,
    channel: String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<String, String>> {
    val at =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(res.configuration.locales[0])
            .format(Instant.ofEpochMilli(bubble.atMs).atZone(zone))
    val state =
        when (bubble.state) {
            BubbleState.Waiting -> R.string.info_waiting
            BubbleState.Sending -> R.string.info_sending
            BubbleState.Sent -> R.string.info_sent_state
            BubbleState.Delivered -> R.string.info_delivered
            BubbleState.Read, BubbleState.Played -> R.string.info_read
            BubbleState.Failed -> R.string.info_failed
            else -> null
        }
    return listOfNotNull(
        res.getString(if (bubble.outgoing) R.string.info_sent else R.string.info_received) to at,
        state?.let { res.getString(R.string.info_status) to res.getString(it) },
        res.getString(R.string.info_channel) to channel,
    )
}
