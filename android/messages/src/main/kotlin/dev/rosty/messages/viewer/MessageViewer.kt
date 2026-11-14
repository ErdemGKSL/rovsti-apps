package dev.rosty.messages.viewer

import android.content.Context
import android.content.Intent
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.ViewerAction
import dev.rosty.design.media.showPhotoViewer
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.platform.files.FilesRepository
import dev.rosty.platform.files.ShareIntents
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One picture of a conversation the viewer pages through. */
data class ViewerPicture(
    val file: File,
    val atMs: Long,
)

/** Every picture of the loaded conversation, oldest first (the order the viewer pages in, M3). */
fun picturesOf(items: List<ConversationItem>): List<Pair<String, ViewerPicture>> =
    items
        .asReversed()
        .mapNotNull { (it as? ConversationItem.Bubble)?.bubble }
        .flatMap { b: BubbleUi ->
            b.media.filter { it.isImage }.mapNotNull { m ->
                (m.model as? File)?.let { b.key to ViewerPicture(it, b.atMs) }
            }
        }

/** `MMS-Ada-Lovelace-20261107-101500.jpg`: what a saved picture is called (GPUI M2). */
fun savedName(
    prefix: String,
    title: String,
    atMs: Long,
    extension: String,
): String {
    val who =
        title
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .ifEmpty { "chat" }
    val stamp =
        DateTimeFormatter
            .ofPattern(
                "yyyyMMdd-HHmmss",
            ).format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))
    return "$prefix-$who-$stamp.$extension"
}

/**
 * The full-screen viewer over the pictures of a conversation (E6.7, M1-M3): paging "3 of 7", Save to Photos and Share.
 * Opens on the picture of [bubbleKey].
 */
fun OverlayController.showConversationPictures(
    context: Context,
    pictures: List<Pair<String, ViewerPicture>>,
    bubbleKey: String,
    title: String,
    files: FilesRepository,
    scope: CoroutineScope,
    prefix: String = "MMS",
) {
    if (pictures.isEmpty()) return
    val start = pictures.indexOfFirst { it.first == bubbleKey }.coerceAtLeast(0)
    val controller = this

    fun pictureAt(page: Int) = pictures[page.coerceIn(pictures.indices)].second
    val actions =
        persistentListOf(
            ViewerAction("save", RostyIcons.Download, context.getString(R.string.viewer_save)) { page ->
                val p = pictureAt(page)
                scope.launch {
                    val name = savedName(prefix, title, p.atMs, p.file.extension.ifEmpty { "jpg" })
                    val ok = files.saveToGallery(p.file.absolutePath, name, mimeOf(p.file))
                    controller.showToast(
                        context.getString(if (ok) R.string.viewer_saved else R.string.viewer_not_saved),
                    )
                }
            },
            ViewerAction("share", RostyIcons.Share, context.getString(R.string.viewer_share)) { page ->
                val p = pictureAt(page)
                scope.launch {
                    val uri =
                        files.stageForShare(
                            p.file.absolutePath,
                            savedName(prefix, title, p.atMs, p.file.extension.ifEmpty { "jpg" }),
                        )
                    if (uri == null) {
                        controller.showToast(context.getString(R.string.viewer_cant_share))
                    } else {
                        context.startActivity(
                            ShareIntents.image(uri, mimeOf(p.file), title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            },
        )
    showPhotoViewer(pictures.map { it.second.file }.toImmutableList(), start, actions)
}

private fun mimeOf(file: File): String =
    when (file.extension.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
