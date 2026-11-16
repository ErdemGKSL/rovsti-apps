package dev.rosty.messages.wa

import dev.rosty.core.model.wa.MediaKind
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.platform.media.MediaMeasure
import java.util.Base64

/** One picked file in the send preview (E6-WA R3): its own caption and, for a photo or video, view once. */
data class WaBatchItem(
    /** Stable while the item is in the preview (moves and removals keep it). */
    val id: String,
    val file: WaStagedFile,
    val caption: String = "",
    val viewOnce: Boolean = false,
    /** What the phone measured when the file was added; null for a document or a file that did not read. */
    val measure: MediaMeasure? = null,
) {
    val kind: MediaKind get() = WaThreadViewModel.mediaKindOf(file.mime)

    /** WhatsApp sends a photo or a video as view once; nothing else. */
    val canViewOnce: Boolean get() = kind == MediaKind.Image || kind == MediaKind.Video

    /**
     * The outbox action that sends this item, as its own message: the caption, the reply ([quoted]) and the options
     * the phone measured (dimensions, a video's length, the preview, view once). A GIF goes out as the picture it is,
     * as before (`animated` stays false: only a video can play as a GIF).
     */
    fun action(quoted: String?): WaSyncAction.SendMedia {
        val visual = kind == MediaKind.Image || kind == MediaKind.Video
        val seen = measure.takeIf { visual }
        return WaSyncAction.SendMedia(
            file = file.file.absolutePath,
            media = kind,
            mime = file.mime,
            size = file.size,
            caption = caption.trim().ifEmpty { null },
            quotedId = quoted,
            fileName = file.name,
            seconds = seen?.seconds?.takeIf { kind == MediaKind.Video },
            thumbnailB64 = seen?.thumbnail?.let { Base64.getEncoder().encodeToString(it) },
            width = seen?.width,
            height = seen?.height,
            viewOnce = viewOnce && canViewOnce,
        )
    }
}

/**
 * The files of the send preview, in the order they go out, and the one on screen ([current]). Pure: the view model
 * keeps one and replaces it on every change.
 */
data class WaSendBatch(
    val items: List<WaBatchItem> = emptyList(),
    val current: Int = 0,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** The item on screen; null when the preview is empty. */
    val shown: WaBatchItem? get() = items.getOrNull(current)

    /** [more] at the end; the first of them comes on screen. */
    fun add(more: List<WaBatchItem>): WaSendBatch = if (more.isEmpty()) this else WaSendBatch(items + more, items.size)

    /** Without [id]; the one after it (or before, at the end) comes on screen. */
    fun remove(id: String): WaSendBatch {
        val at = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return this
        val rest = items.filterIndexed { index, _ -> index != at }
        val current =
            when {
                rest.isEmpty() -> 0
                at < this.current -> this.current - 1
                else -> this.current.coerceAtMost(rest.lastIndex)
            }
        return WaSendBatch(rest, current)
    }

    /** Moves [id] [by] places (negative: earlier), within the list; the item stays on screen. */
    fun move(
        id: String,
        by: Int,
    ): WaSendBatch {
        val from = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return this
        val to = (from + by).coerceIn(0, items.lastIndex)
        if (to == from) return this
        val list = items.toMutableList()
        list.add(to, list.removeAt(from))
        return WaSendBatch(list, to)
    }

    fun select(index: Int): WaSendBatch = if (index in items.indices) copy(current = index) else this

    fun caption(
        id: String,
        text: String,
    ): WaSendBatch = update(id) { it.copy(caption = text) }

    /** Turns view once on or off for a photo or video; anything else is left as it is. */
    fun toggleViewOnce(id: String): WaSendBatch =
        update(id) { if (it.canViewOnce) it.copy(viewOnce = !it.viewOnce) else it }

    /** One action per item, in order; only the first answers [quoted]. */
    fun actions(quoted: String?): List<WaSyncAction.SendMedia> =
        items.mapIndexed { index, item -> item.action(quoted.takeIf { index == 0 }) }

    private fun update(
        id: String,
        change: (WaBatchItem) -> WaBatchItem,
    ): WaSendBatch = copy(items = items.map { if (it.id == id) change(it) else it })

    companion object {
        /** The photo picker lets this many be picked at once. */
        const val MAX_PICK = 30
    }
}
