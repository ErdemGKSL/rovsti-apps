package dev.rosty.messages.wa

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleMediaKind
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.viewer.ViewerPicture
import dev.rosty.messages.viewer.showConversationPictures
import dev.rosty.platform.files.ExportDocument
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.files.ShareIntents
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The pictures of a WhatsApp thread that are on the phone in full, oldest first: what the viewer pages over. */
fun waPicturesOf(items: List<ConversationItem>): List<Pair<String, ViewerPicture>> =
    items
        .asReversed()
        .mapNotNull { (it as? ConversationItem.Bubble)?.bubble }
        .flatMap { bubble ->
            bubble.media
                .filter { it.kind == BubbleMediaKind.Picture && it.local }
                .mapNotNull { media -> (media.model as? File)?.let { bubble.key to ViewerPicture(it, bubble.atMs) } }
        }

/**
 * The + menu of the WhatsApp composer: a photo or a video from the system picker, any document, or the picture on the
 * clipboard (M46). What is chosen is copied into app storage and waits above the composer for Send.
 */
@Composable
fun rememberWaAttachMenu(
    model: WaThreadViewModel,
    overlays: OverlayController,
): ImmutableList<MenuItem> {
    val context = LocalContext.current
    val res = LocalResources.current
    val media =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { model.attach(it.toString()) }
        }
    val document =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { model.attach(it.toString()) }
        }
    return remember(model, overlays, media, document, res) {
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
 * What the thread's view model asks the page to do with a tapped picture or file: open the viewer, hand a video or
 * document to an app that shows it, save it ("Save as", pictures to Photos) or share it.
 */
@Composable
fun WaMediaEffects(
    model: WaThreadViewModel,
    container: MessagesContainer,
    overlays: OverlayController,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val saving = remember { arrayOfNulls<File>(1) }
    val saveAs =
        rememberLauncherForActivityResult(ExportDocument(ANY_FILE)) { result ->
            val file = saving[0]
            saving[0] = null
            val uri = (result as? FileResult.Ok)?.uri
            if (file != null && uri != null) {
                container.scope.launch {
                    val written = container.files.writeExport(uri, path = file.absolutePath)
                    val ok = written is FileResult.Ok
                    overlays.showToast(res.getString(if (ok) R.string.wa_media_saved else R.string.wa_media_not_saved))
                }
            }
        }
    LaunchedEffect(model, container, overlays) {
        model.mediaEffects.collect { effect ->
            when (effect) {
                is WaMediaEffect.ShowPictures -> {
                    overlays.showConversationPictures(
                        context,
                        waPicturesOf(model.state.value.items),
                        effect.key,
                        model.state.value.title,
                        container.files,
                        container.scope,
                        prefix = PICTURE_PREFIX,
                    )
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
                    if (effect.picture) {
                        val ok =
                            container.files.saveToGallery(
                                effect.file.absolutePath,
                                effect.name,
                                effect.mime.ifEmpty { JPEG },
                            )
                        overlays.showToast(
                            res.getString(if (ok) R.string.viewer_saved else R.string.viewer_not_saved),
                        )
                    } else {
                        saving[0] = effect.file
                        try {
                            saveAs.launch(effect.name)
                        } catch (_: ActivityNotFoundException) {
                            saving[0] = null
                            overlays.showToast(res.getString(R.string.wa_media_not_saved))
                        }
                    }
                }

                is WaMediaEffect.Share -> {
                    val uri = container.files.stageForShare(effect.file.absolutePath, effect.name)
                    if (uri == null || !start(context, ShareIntents.file(uri, effect.mime, effect.name))) {
                        overlays.showToast(res.getString(R.string.wa_media_cant_share))
                    }
                }
            }
        }
    }
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
private const val JPEG = "image/jpeg"
private const val PICTURE_PREFIX = "WhatsApp"
