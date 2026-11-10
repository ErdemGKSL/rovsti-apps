package dev.rovsti.phone.details

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import kotlinx.coroutines.awaitCancellation

/**
 * Test double for the platform's selection toolbar (cut, copy, paste, select all). Production code never provides
 * `LocalTextContextMenuToolbarProvider` (the platform toolbar is kept, see api-notes); a test provides this one
 * only to see
 * that the request was made and to press its items.
 */
internal class RecordingToolbar : TextContextMenuProvider {
    var shown = 0
        private set
    private var latest: TextContextMenuDataProvider? = null

    private val session =
        object : TextContextMenuSession {
            override fun close() = Unit
        }

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        shown++
        latest = dataProvider
        awaitCancellation()
    }

    private fun items(): List<TextContextMenuItem> =
        latest
            ?.data()
            ?.components
            .orEmpty()
            .filterIsInstance<TextContextMenuItem>()

    fun offers(key: Any): Boolean = items().any { it.key == key }

    fun press(key: Any) {
        val item = items().first { it.key == key }
        item.onClick(session)
    }

    val copyKey: Any get() = TextContextMenuKeys.CopyKey
    val pasteKey: Any get() = TextContextMenuKeys.PasteKey
}
