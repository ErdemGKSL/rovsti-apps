package dev.rosty.messages.conversation

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.rosty.core.model.chat.GalleryAnswer
import dev.rosty.design.controls.SegmentedControl
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.SectionHeader
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.platform.files.ExportDocument
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.files.ShareIntents
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** The labels of the tools in the app's language. */
@Composable
fun rememberToolLabels(): ToolLabels {
    val context = LocalContext.current
    return remember(context) {
        ToolLabels(
            menu = context.getString(R.string.tools_menu),
            find = context.getString(R.string.tools_find),
            older = context.getString(R.string.tools_older),
            newer = context.getString(R.string.tools_newer),
            close = context.getString(R.string.tools_done),
            jumpLatest = context.getString(R.string.tools_jump_latest),
            position = { at, count -> context.getString(R.string.tools_position, at, count) },
            noMatches = context.getString(R.string.tools_no_matches),
        )
    }
}

/** What the header menu of a conversation can open (GPUI page menu, the E6.6 part). */
class ToolHost(
    val context: Context,
    val overlays: OverlayController,
    val tools: ConversationTools,
    val scope: CoroutineScope,
    val title: () -> String,
    /** "Save as" with the system document picker (the page owns the launcher). */
    val saveAs: (ExportedChat) -> Unit,
    val copy: (String) -> Unit,
)

/**
 * The header menu (E6.6): Search in chat, Jump to date, Media/links/docs, Export chat, Share as text, Copy name/number
 * (M42), then the page's own [extra] rows (mute, call, open contact).
 */
fun headerMenu(
    host: ToolHost,
    copyables: List<Pair<String, String>>,
    extra: List<MenuItem> = emptyList(),
): ImmutableList<MenuItem> {
    val c = host.context
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(c.getString(R.string.tools_find), RostyIcons.Search) { host.tools.openFind() }
    items += MenuItem.Action(c.getString(R.string.tools_jump_date), RostyIcons.CalendarPlus) { presentJump(host) }
    items += MenuItem.Action(c.getString(R.string.tools_gallery), RostyIcons.Photo) { presentGallery(host) }
    items += MenuItem.Separator
    items +=
        MenuItem.Action(c.getString(R.string.tools_export), RostyIcons.Download) {
            host.scope.launch {
                host.tools.export(host.title(), c.getString(R.string.tools_me))?.let(host.saveAs)
                    ?: host.overlays.showToast(c.getString(R.string.tools_export_failed))
            }
        }
    items +=
        MenuItem.Action(c.getString(R.string.tools_share_text), RostyIcons.Share) {
            host.scope.launch {
                val exported = host.tools.export(host.title(), c.getString(R.string.tools_me))
                if (exported == null) {
                    host.overlays.showToast(c.getString(R.string.tools_export_failed))
                } else {
                    c.startActivity(ShareIntents.text(exported.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    if (copyables.isNotEmpty()) {
        items += MenuItem.Separator
        for ((label, value) in copyables) items += MenuItem.Action(label, RostyIcons.Copy) { host.copy(value) }
    }
    if (extra.isNotEmpty()) {
        items += MenuItem.Separator
        items += extra
    }
    return items.toImmutableList()
}

/** Jump to date (M24) with the quick picks of GPUI: Today, Yesterday, 1 week ago, 1 month ago. */
private fun presentJump(host: ToolHost) {
    val c = host.context
    val today = LocalDate.now()
    val picks =
        listOf(
            R.string.conversation_today to today,
            R.string.inbox_yesterday to today.minusDays(1),
            R.string.tools_week_ago to today.minusWeeks(1),
            R.string.tools_month_ago to today.minusMonths(1),
        )
    host.overlays.present(
        ActionSheetEntry(
            title = c.getString(R.string.tools_jump_date),
            actions =
                picks
                    .map { (label, day) ->
                        ActionSheetAction(c.getString(label)) {
                            val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            if (!host.tools.jumpTo(
                                    start,
                                )
                            ) {
                                host.overlays.showToast(c.getString(R.string.tools_further_back))
                            }
                        }
                    }.toImmutableList(),
            cancelLabel = c.getString(R.string.inbox_cancel),
        ),
    )
}

private enum class GalleryTabKind { Media, Links, Docs }

/** Media, links and docs (M13) of what is loaded, by month. A row opens its message. */
private fun presentGallery(host: ToolHost) {
    host.scope.launch {
        val answer = host.tools.gallery() ?: return@launch
        host.overlays.present(
            SheetEntry(
                title = host.context.getString(R.string.tools_gallery),
                detents = persistentListOf(Detent.Medium, Detent.Large),
            ) { sheet ->
                GallerySheet(answer, host) { key ->
                    host.overlays.dismissAll()
                    host.tools.reveal(key)
                }
            },
        )
    }
}

@Composable
private fun GallerySheet(
    answer: GalleryAnswer,
    host: ToolHost,
    onOpen: (String) -> Unit,
) {
    val c = host.context
    val tab = remember { androidx.compose.runtime.mutableStateOf(GalleryTabKind.Media) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(ToolTags.Gallery)) {
        SegmentedControl(
            items =
                persistentListOf(
                    c.getString(R.string.tools_tab_media),
                    c.getString(R.string.tools_tab_links),
                    c.getString(R.string.tools_tab_docs),
                ),
            selected = tab.value.ordinal,
            onSelect = { tab.value = GalleryTabKind.entries[it] },
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        )
        val rows: List<Triple<String, String, String?>> =
            when (tab.value) {
                GalleryTabKind.Media -> answer.media.map { Triple(it.id, c.getString(R.string.wa_photo), null) }
                GalleryTabKind.Links -> answer.links.map { Triple(it.id, it.short, it.host) }
                GalleryTabKind.Docs -> answer.docs.map { Triple(it.id, it.name, it.detail) }
            }
        val sections =
            when (tab.value) {
                GalleryTabKind.Media -> answer.mediaSections
                GalleryTabKind.Links -> answer.linkSections
                GalleryTabKind.Docs -> answer.docSections
            }
        if (rows.isEmpty()) {
            RostyText(
                c.getString(
                    when (tab.value) {
                        GalleryTabKind.Media -> R.string.tools_no_media
                        GalleryTabKind.Links -> R.string.tools_no_links
                        GalleryTabKind.Docs -> R.string.tools_no_docs
                    },
                ),
                modifier = Modifier.padding(Spacing.xl),
                color = RostyTheme.colors.labelSecondary,
            )
        }
        for (section in sections) {
            SectionHeader(section.label)
            for (i in section.start until section.end.coerceAtMost(rows.size)) {
                val (id, title, detail) = rows[i]
                ListRow(title = title, secondary = detail, onClick = { onOpen(id) })
            }
        }
    }
}

object ToolTags {
    const val Gallery = "tools-gallery"
}

/**
 * The tools of a conversation page with their Android side: the Save-as launcher of the export and the clipboard.
 * [bubbles] reads the page's current bubbles.
 */
@Composable
fun rememberToolHost(
    container: MessagesContainer,
    overlays: OverlayController,
    bubbles: () -> List<BubbleUi>,
    title: () -> String,
): ToolHost {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tools = remember { ConversationTools(container.chat, scope, bubbles, System::currentTimeMillis) }
    val pending = remember { arrayOfNulls<ExportedChat>(1) }
    val save =
        rememberLauncherForActivityResult(ExportDocument("text/plain")) { result ->
            val file = pending[0] ?: return@rememberLauncherForActivityResult
            pending[0] = null
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val written = container.files.writeExport(uri, text = file.text)
                overlays.showToast(
                    context.getString(
                        if (written is FileResult.Ok) R.string.tools_exported else R.string.tools_export_failed,
                    ),
                )
            }
        }
    return remember(tools, overlays) {
        ToolHost(
            context = context,
            overlays = overlays,
            tools = tools,
            scope = scope,
            title = title,
            saveAs = { file ->
                pending[0] = file
                save.launch(file.fileName)
            },
            copy = { text ->
                container.clipboard.copy(text)
                overlays.showToast(context.getString(R.string.conversation_copied))
            },
        )
    }
}

/** The bubbles of [items], newest first as listed. */
fun bubblesOf(items: List<ConversationItem>): List<BubbleUi> =
    items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }
