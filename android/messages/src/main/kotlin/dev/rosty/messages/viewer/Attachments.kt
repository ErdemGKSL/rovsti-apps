package dev.rosty.messages.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.MimeRequest
import dev.rosty.core.model.chat.SharedCard
import dev.rosty.core.model.chat.SharedCardRequest
import dev.rosty.core.ops.ChatOps
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.ListRow
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleMedia
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.messages.ui.MessagesAvatar
import dev.rosty.platform.files.FilesRepository
import dev.rosty.platform.files.ShareIntents
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object AttachmentTags {
    const val Card = "contact-card"
}

/**
 * What a tap on a non-picture attachment does (E6.7): a vCard opens the contact card sheet (M26, parsed by
 * `contacts.shared_card`), any other file offers Share and Save (M28).
 */
suspend fun OverlayController.openAttachment(
    c: Context,
    media: BubbleMedia,
    chat: ChatOps,
    files: FilesRepository,
    scope: CoroutineScope,
) {
    val file = media.model as? File ?: return
    val mime = media.mime.orEmpty()
    val kind = chat.cardMime(MimeRequest(mime)).getOrNull()
    if (kind?.vcard == true && file.length() <= kind.maxBytes) {
        val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() } ?: return
        val answer = chat.sharedCard(SharedCardRequest(c.getString(R.string.card_unnamed), text)).getOrNull() ?: return
        val vcf = File(File(c.cacheDir, "cards").apply { mkdirs() }, answer.cacheFileName)
        withContext(Dispatchers.IO) { vcf.writeText(answer.vcf) }
        presentContactCard(
            c,
            answer.card,
        ) { scope.launch { shareFile(c, files, vcf, "text/x-vcard", answer.fileName) } }
        return
    }
    val controller = this
    present(
        ActionSheetEntry(
            title = media.label,
            actions =
                persistentListOf(
                    ActionSheetAction(c.getString(R.string.viewer_share)) {
                        scope.launch {
                            if (!shareFile(c, files, file, mime, file.name)) {
                                controller.showToast(c.getString(R.string.viewer_cant_share))
                            }
                        }
                    },
                ),
            cancelLabel = c.getString(R.string.inbox_cancel),
        ),
    )
}

private suspend fun shareFile(
    c: Context,
    files: FilesRepository,
    file: File,
    mime: String,
    name: String,
): Boolean {
    val uri = files.stageForShare(file.absolutePath, name) ?: return false
    return try {
        c.startActivity(
            Intent.createChooser(ShareIntents.file(uri, mime, name), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** M26: a contact card from a message: name, numbers, e-mails, Add to Contacts and Share. */
fun OverlayController.presentContactCard(
    c: Context,
    card: SharedCard,
    onShare: () -> Unit,
) {
    val controller = this
    present(
        SheetEntry(title = card.name, detents = persistentListOf(Detent.Medium, Detent.Large)) { _ ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = Spacing.l, bottom = Spacing.l)
                    .testTag(AttachmentTags.Card),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MessagesAvatar(card.name, RowAvatar(colorKey = card.name), size = MessagesMetrics.CardAvatar)
                RostyText(
                    card.name,
                    modifier = Modifier.padding(top = Spacing.s),
                    style = RostyTheme.type.title2,
                    textAlign = TextAlign.Center,
                )
                if (card.org.isNotBlank()) {
                    RostyText(card.org, style = RostyTheme.type.subheadline, color = RostyTheme.colors.labelSecondary)
                }
                Column(Modifier.fillMaxWidth().padding(top = Spacing.m)) {
                    card.numbers.forEach { n ->
                        ListRow(title = n.value, onClick = {
                            c.startSafely(Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", n.value, null)))
                        })
                    }
                    card.emails.forEach { e ->
                        ListRow(title = e.value, onClick = { c.startSafely(ShareIntents.sendEmailTo(e.value)) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    CapsuleButton(
                        c.getString(R.string.card_add),
                        onClick = {
                            controller.dismissAll()
                            c.startSafely(insertIntent(card))
                        },
                        modifier = Modifier.weight(1f),
                        icon = RostyIcons.PersonAdd,
                        fillWidth = true,
                    )
                    CapsuleButton(
                        c.getString(R.string.viewer_share),
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        icon = RostyIcons.Share,
                        fillWidth = true,
                    )
                }
            }
        },
    )
}

/** The system's new-contact screen filled from [card]. */
private fun insertIntent(card: SharedCard): Intent =
    Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, card.name)
        if (card.org.isNotBlank()) putExtra(ContactsContract.Intents.Insert.COMPANY, card.org)
        card.numbers.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it.value) }
        card.numbers.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it.value) }
        card.emails.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it.value) }
        if (card.note.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NOTES, card.note)
    }

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone handles it; the sheet stays open.
    }
}
