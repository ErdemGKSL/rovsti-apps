package dev.rosty.messages.compose

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rosty.core.model.messages.Channel
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.text.SearchField
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.inbox.InboxRowUi
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.ui.MessagesAvatar
import kotlinx.collections.immutable.persistentListOf

/** One chat the forward chooser offers: a person's SMS thread or WhatsApp chat (both when they have both, M9). */
@Immutable
data class ForwardTarget(
    val key: String,
    val title: String,
    val avatar: RowAvatar,
    val channel: Channel,
    /** Whether the person has the other channel too: the row then names its channel. */
    val both: Boolean,
    val smsThread: Long? = null,
    val smsAddress: String? = null,
    val waJid: String? = null,
) {
    /** The conversation to open with [text] in its composer. */
    fun route(text: String): MessagesRoute =
        when (channel) {
            Channel.WhatsApp -> MessagesRoute.WaThread(requireNotNull(waJid), draft = text)
            else -> MessagesRoute.SmsThread(requireNotNull(smsThread), listOfNotNull(smsAddress), draft = text)
        }

    companion object {
        /**
         * The targets of the inbox [rows] whose title holds [query], one per thread, leaving out the conversation the
         * message comes from ([exclude], `sms:<thread>` or `wa:<jid>`). Rows keep the inbox order (newest first).
         */
        fun of(
            rows: List<InboxRowUi>,
            query: String,
            exclude: String?,
        ): List<ForwardTarget> {
            val q = query.trim()
            return rows
                .filter { !it.archived && (q.isEmpty() || it.title.contains(q, ignoreCase = true)) }
                .flatMap { row ->
                    val both = row.smsThread != null && row.waChat != null
                    buildList {
                        row.smsThread?.takeIf { "sms:$it" != exclude }?.let { thread ->
                            add(
                                ForwardTarget(
                                    "sms:$thread",
                                    row.title,
                                    row.avatar,
                                    Channel.Sms,
                                    both,
                                    smsThread = thread,
                                    smsAddress = row.smsSender,
                                ),
                            )
                        }
                        row.waChat?.takeIf { "wa:$it" != exclude }?.let { jid ->
                            add(ForwardTarget("wa:$jid", row.title, row.avatar, Channel.WhatsApp, both, waJid = jid))
                        }
                    }
                }
        }
    }
}

object ForwardTags {
    const val Sheet = "forward-sheet"

    fun target(key: String) = "forward-$key"
}

/**
 * "Forward to" (E6.8, M9): a search field over the inbox's chats. A pick opens that conversation with [text] in its
 * composer, so the person sees what goes out and sends it (or edits it) there.
 */
fun OverlayController.presentForward(
    c: Context,
    rows: List<InboxRowUi>,
    exclude: String?,
    text: String,
    onOpen: (MessagesRoute) -> Unit,
) {
    val controller = this
    present(
        SheetEntry(
            title = c.getString(R.string.forward_title),
            detents = persistentListOf(Detent.Medium, Detent.Large),
        ) { _ ->
            ForwardSheet(c, rows, exclude) { target ->
                controller.dismissAll()
                onOpen(target.route(text))
            }
        },
    )
}

@Composable
private fun ForwardSheet(
    c: Context,
    rows: List<InboxRowUi>,
    exclude: String?,
    onPick: (ForwardTarget) -> Unit,
) {
    val field = rememberTextFieldState()
    val targets by remember(rows, exclude) { derivedStateOf { ForwardTarget.of(rows, field.text.toString(), exclude) } }
    val sms = c.getString(R.string.forward_via_sms)
    val wa = c.getString(R.string.forward_via_whatsapp)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(ForwardTags.Sheet)) {
        SearchField(
            field,
            Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            placeholder = c.getString(R.string.forward_search),
        )
        if (targets.isEmpty()) {
            RostyText(
                c.getString(R.string.forward_none),
                modifier = Modifier.padding(Spacing.xl),
                color = RostyTheme.colors.labelSecondary,
            )
        }
        targets.forEachIndexed { i, target ->
            if (i > 0) RowSeparator(inset = TextStartWithLeading)
            ListRow(
                title = target.title,
                modifier = Modifier.testTag(ForwardTags.target(target.key)),
                secondary = if (target.both) (if (target.channel == Channel.WhatsApp) wa else sms) else null,
                leading = { MessagesAvatar(target.title, target.avatar) },
                onClick = { onPick(target) },
            )
        }
    }
}
