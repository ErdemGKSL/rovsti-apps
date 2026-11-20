package dev.rosty.messages.wa

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.compose.presentForward
import dev.rosty.messages.conversation.BubbleMediaKind
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.MediaAction
import dev.rosty.messages.conversation.MediaAnchors
import dev.rosty.messages.inbox.InboxViewModel
import dev.rosty.messages.inbox.rememberInboxViewModel
import dev.rosty.platform.files.GalleryTarget
import dev.rosty.platform.files.MediaStoreGalleryWriter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The + menu of the WhatsApp composer: photos or videos from the system picker (up to [WaSendBatch.MAX_PICK] at once),
 * documents, or the picture on the clipboard (M46). Picked files open the send preview (E6-WA R3: a caption each, view
 * once, reorder); the pasted picture waits above the composer for Send.
 */
@Composable
fun rememberWaAttachMenu(
    model: WaThreadViewModel,
    overlays: OverlayController,
    rich: dev.rosty.messages.wa.rich.RichHost? = null,
): ImmutableList<MenuItem> {
    val context = LocalContext.current
    val res = LocalResources.current
    val media =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(WaSendBatch.MAX_PICK),
        ) { uris ->
            model.pick(uris.map { it.toString() })
        }
    val document =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            model.pick(uris.map { it.toString() })
        }
    return remember(model, overlays, media, document, res, rich) {
        persistentListOf(
            MenuItem.Action(res.getString(R.string.wa_attach_photo), RostyIcons.Photo) {
                media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            MenuItem.Action(res.getString(R.string.wa_attach_video), RostyIcons.Camera) {
                media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            MenuItem.Action(res.getString(R.string.wa_attach_document), RostyIcons.Document) {
                document.launch(ANY_FILE)
            },
            // E6-WA R5: a place, a contact, a poll.
            MenuItem.Action(res.getString(R.string.wa_attach_location), RostyIcons.Map) { rich?.sendPlace?.invoke() },
            MenuItem.Action(
                res.getString(R.string.wa_attach_contact),
                RostyIcons.Person,
            ) { rich?.pickContact?.invoke() },
            MenuItem.Action(res.getString(R.string.wa_attach_poll), RostyIcons.BarChart) { rich?.createPoll?.invoke() },
            MenuItem.Separator,
            MenuItem.Action(res.getString(R.string.wa_attach_paste), RostyIcons.Copy) {
                val uri = clipboardPicture(context)
                if (uri != null) {
                    model.attach(uri.toString())
                } else {
                    overlays.showToast(res.getString(R.string.wa_paste_nothing))
                }
            },
        )
    }
}

/** The picture on the clipboard, when its first item is one. */
private fun clipboardPicture(context: Context): Uri? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    return try {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        val described = clip.description.hasMimeType("image/*")
        val typed = context.contentResolver.getType(uri)?.startsWith("image/") == true
        uri.takeIf { described || typed }
    } catch (_: SecurityException) {
        null
    }
}

/**
 * M46: a picture pasted into the composer, or sent by the keyboard (a GIF, a sticker, a screenshot from its clipboard
 * row), is attached like a picked one; text keeps going into the field.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pastedPictures(model: WaThreadViewModel): Modifier =
    contentReceiver { content ->
        if (!content.hasMediaType(MediaType.Image)) {
            content
        } else {
            content.consume { item ->
                val uri = item.uri
                if (uri != null) model.attach(uri.toString())
                uri != null
            }
        }
    }

/**
 * What the thread's view model asks the page to do with a tapped picture or file: open the viewer, hand a document to
 * an app that shows it, save it (MediaStore, recorded on the message), share it or forward it.
 */
@Composable
fun WaMediaEffects(
    model: WaThreadViewModel,
    container: MessagesContainer,
    overlays: OverlayController,
    navigator: TabNavigator<MessagesRoute>,
    anchors: MediaAnchors?,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val inbox = rememberInboxViewModel(container)
    val files =
        remember(container, overlays, navigator, inbox, res, context) {
            WaMediaFiles(
                context = context,
                res = res,
                container = container,
                overlays = overlays,
                saver =
                    WaMediaSaver(
                        {
                            container.waOffline.session.value
                                ?.store
                        },
                        MediaStoreGalleryWriter(context, Dispatchers.IO),
                    ),
                forwardTo = { file -> file.presentTo(overlays, context, inbox, model, navigator) },
            )
        }
    LaunchedEffect(model, container, overlays, files) {
        model.mediaEffects.collect { effect ->
            when (effect) {
                is WaMediaEffect.ShowViewer -> {
                    val labels = WaViewerLabels.of(res) { bytes -> Formatter.formatShortFileSize(context, bytes) }
                    val session =
                        WaViewerSession(
                            effect.row,
                            WaViewerSources.of(
                                media = container.waMedia,
                                gallery = WaGallerySource.of(container.waOffline, model.jid),
                                sender = { row -> container.names.sender(row.sender, row.senderName) },
                                savedUri = { row -> files.saver.savedUri(row.chat, row.id) },
                            ),
                            labels,
                            scope,
                        )
                    overlays.showWaViewer(session, labels, files.viewerActions(), scope, anchors)
                }

                is WaMediaEffect.Open -> {
                    val uri = container.files.stageForShare(effect.file.absolutePath, effect.name)
                    val view =
                        uri?.let {
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(it, effect.mime.ifEmpty { ANY_FILE })
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    if (view == null || !start(context, view)) {
                        overlays.showToast(res.getString(R.string.wa_media_cant_open))
                    }
                }

                is WaMediaEffect.Save -> {
                    files.save(effect.chat, effect.id, effect.file, effect.name, effect.mime)
                }

                is WaMediaEffect.Share -> {
                    files.share(effect.file, effect.mime, effect.name)
                }

                is WaMediaEffect.Forward -> {
                    files.forward(effect.file, effect.name, effect.caption)
                }
            }
        }
    }
}

/** Opens the forward chooser over the inbox's WhatsApp chats with the staged file [this] (a content URI). */
private fun ForwardFile.presentTo(
    overlays: OverlayController,
    context: Context,
    inbox: InboxViewModel,
    model: WaThreadViewModel,
    navigator: TabNavigator<MessagesRoute>,
) {
    overlays.presentForward(
        context,
        inbox.state.value.rows,
        exclude = "wa:${model.jid}",
        text = caption,
        onOpen = { navigator.push(it) },
        attach = uri,
    )
}

/** A file staged for the forward chooser. */
class ForwardFile(
    val uri: String,
    val caption: String,
)

/**
 * Save, Share and Forward of a WhatsApp file, the same from the viewer's toolbar, the message menu and a file row
 * (E6-WA R3). Each tells how it went with a toast.
 */
class WaMediaFiles(
    private val context: Context,
    private val res: Resources,
    private val container: MessagesContainer,
    private val overlays: OverlayController,
    val saver: WaMediaSaver,
    private val forwardTo: (ForwardFile) -> Unit,
) {
    /** Copies [file] to the phone's gallery or downloads once; true when it is saved now (or was before). */
    suspend fun save(
        chat: String,
        id: String,
        file: File,
        name: String,
        mime: String,
    ): Boolean {
        val result = saver.save(chat, id, file, name, mime)
        val text =
            when (result) {
                is WaSaved.Saved -> {
                    when (result.target) {
                        GalleryTarget.Pictures -> R.string.wa_viewer_saved_photos
                        GalleryTarget.Movies -> R.string.wa_viewer_saved_movies
                        GalleryTarget.Downloads -> R.string.wa_viewer_saved_downloads
                    }
                }

                is WaSaved.Already -> {
                    R.string.wa_viewer_already_saved
                }

                WaSaved.Failed, WaSaved.NotAllowed -> {
                    R.string.wa_media_not_saved
                }
            }
        overlays.showToast(res.getString(text))
        return result is WaSaved.Saved || result is WaSaved.Already
    }

    suspend fun share(
        file: File,
        mime: String,
        name: String,
    ) {
        val uri = container.files.stageForShare(file.absolutePath, name)
        if (uri == null || !start(context, waShareIntent(uri, mime, name))) {
            overlays.showToast(res.getString(R.string.wa_media_cant_share))
        }
    }

    suspend fun forward(
        file: File,
        name: String,
        caption: String,
    ) {
        val uri = container.files.stageForShare(file.absolutePath, name)
        if (uri == null) {
            overlays.showToast(res.getString(R.string.wa_media_cant_share))
        } else {
            forwardTo(ForwardFile(uri.toString(), caption))
        }
    }

    /** The buttons of the viewer: each downloads the file first when it is not on the phone. */
    fun viewerActions(): WaViewerActions =
        WaViewerActions(
            save = { row ->
                fileOf(row)?.let { save(row.chat, row.id, it, nameOf(row, it), row.mime.orEmpty()) } == true
            },
            share = { row -> fileOf(row)?.let { share(it, row.mime.orEmpty(), nameOf(row, it)) } },
            forward = { row -> fileOf(row)?.let { forward(it, nameOf(row, it), row.text.orEmpty()) } },
        )

    private suspend fun fileOf(row: WaSyncMessage): File? {
        val media = container.waMedia
        val file = row.mediaPath?.let(::File)?.takeIf { it.isFile } ?: media.fetch(row)
        if (file == null) {
            val why = if (media.gone(row)) R.string.wa_media_gone else R.string.wa_media_cant_download
            overlays.showToast(res.getString(why))
        }
        return file
    }

    private fun nameOf(
        row: WaSyncMessage,
        file: File,
    ): String = row.media?.fileName?.takeIf { it.isNotBlank() } ?: file.name
}

/**
 * Save, Share and Forward in the menu of a message with a file (E6-WA R3); none for a view-once message, a deleted one
 * or one still waiting to go out.
 */
fun waMediaMenu(
    bubble: BubbleUi,
    res: Resources,
    queued: Boolean,
    onAction: (MediaAction) -> Unit,
): List<MenuItem> {
    val media = bubble.media.firstOrNull() ?: return emptyList()
    if (bubble.deleted || queued || media.viewOnce || media.kind == BubbleMediaKind.Unavailable) return emptyList()
    return listOf(
        MenuItem.Action(res.getString(R.string.viewer_save), RostyIcons.Download) { onAction(MediaAction.Save) },
        MenuItem.Action(res.getString(R.string.viewer_share), RostyIcons.Share) { onAction(MediaAction.Share) },
        MenuItem.Action(res.getString(R.string.conversation_forward), RostyIcons.Forward) {
            onAction(MediaAction.Forward)
        },
    )
}

private fun start(
    context: Context,
    intent: Intent,
): Boolean =
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

/**
 * The sender card of a group bubble (M50) as the page wires it: Message privately opens the one-to-one WhatsApp chat,
 * Call hands the number to the dialer, the last row opens the contact or offers to add the number.
 */
@Composable
fun WaSenderCard(
    bubble: BubbleUi,
    model: WaThreadViewModel,
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
    overlays: OverlayController,
) {
    val jid = bubble.senderId ?: return
    val context = LocalContext.current
    val res = LocalResources.current
    val card by produceState<SenderCardUi?>(null, model, jid) { value = model.senderCard(jid) }
    val actions =
        remember(model, container, navigator, overlays, context, res) {
            fun open(intent: Intent) {
                overlays.dismissTop()
                if (!start(context, intent)) overlays.showToast(res.getString(R.string.wa_no_app))
            }
            SenderCardActions(
                onMessage = { who ->
                    overlays.dismissTop()
                    navigator.push(MessagesRoute.WaThread(who.jid))
                },
                onCall = { who ->
                    who.number?.let { open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", it, null))) }
                },
                onContact = { who ->
                    val number = who.number
                    if (number != null) {
                        container.scope.launch {
                            val contact = container.book.owner(number)
                            val intent =
                                if (contact != null) {
                                    val uri = ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)
                                    Intent(Intent.ACTION_VIEW, uri)
                                } else {
                                    Intent(Intent.ACTION_INSERT_OR_EDIT)
                                        .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
                                        .putExtra(
                                            ContactsContract.Intents.Insert.NAME,
                                            who.name.takeIf { it != number },
                                        )
                                }
                            withContext(Dispatchers.Main) { open(intent) }
                        }
                    }
                },
            )
        }
    card?.let { SenderMiniCard(it, actions) }
}

private const val ANY_FILE = "*/*"
