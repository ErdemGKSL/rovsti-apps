package dev.rosty.messages.conversation

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rosty.core.model.chat.GalleryAnswer
import dev.rosty.design.controls.SegmentedControl
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.SectionHeader
import dev.rosty.design.media.SizedImage
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
import dev.rosty.design.time.LocalRostyClock
import dev.rosty.design.time.RostyClock
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.platform.files.ExportDocument
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.files.ShareIntents
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The labels of the tools in the app's language. */
@Composable
fun rememberToolLabels(): ToolLabels {
    val context = LocalContext.current
    val res = LocalResources.current
    return remember(context) {
        ToolLabels(
            menu = res.getString(R.string.tools_menu),
            find = res.getString(R.string.tools_find),
            older = res.getString(R.string.tools_older),
            newer = res.getString(R.string.tools_newer),
            close = res.getString(R.string.tools_done),
            jumpLatest = res.getString(R.string.tools_jump_latest),
            position = { at, count -> res.getString(R.string.tools_position, at, count) },
            noMatches = res.getString(R.string.tools_no_matches),
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
    /** "Now", from the injected clock (tests fix it). */
    val clock: RostyClock = SystemRostyClock,
) {
    /** Today in the phone's zone. */
    fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
}

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
    host.overlays.presentJumpToDate(c, host.today()) { start ->
        val found = host.tools.jumpTo(start)
        if (!found) host.overlays.showToast(c.getString(R.string.tools_further_back))
    }
}

/** The jump-to-date sheet: [onPick] gets the start of the picked day in the phone's zone. */
internal fun OverlayController.presentJumpToDate(
    c: Context,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    onPick: (Long) -> Unit,
) {
    val picks =
        listOf(
            R.string.conversation_today to today,
            R.string.inbox_yesterday to today.minusDays(1),
            R.string.tools_week_ago to today.minusWeeks(1),
            R.string.tools_month_ago to today.minusMonths(1),
        )
    present(
        ActionSheetEntry(
            title = c.getString(R.string.tools_jump_date),
            actions =
                picks
                    .map { (label, day) ->
                        ActionSheetAction(
                            c.getString(label),
                        ) { onPick(day.atStartOfDay(zone).toInstant().toEpochMilli()) }
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
        val models = host.tools.mediaModels()
        host.overlays.presentGallery(host.context, answer, models::get) { key ->
            host.overlays.dismissAll()
            host.tools.reveal(key)
        }
    }
}

/** The gallery sheet over Rust's [answer]; [modelOf] gives a media item's picture (a file or URI) for the grid. */
internal fun OverlayController.presentGallery(
    c: Context,
    answer: GalleryAnswer,
    modelOf: (String) -> Any?,
    onOpen: (String) -> Unit,
) {
    present(
        SheetEntry(
            title = c.getString(R.string.tools_gallery),
            detents = persistentListOf(Detent.Medium, Detent.Large),
        ) { _ -> GallerySheet(c, answer, modelOf, onOpen) },
    )
}

@Composable
private fun GallerySheet(
    c: Context,
    answer: GalleryAnswer,
    modelOf: (String) -> Any?,
    onOpen: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(GalleryTabKind.Media) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(ToolTags.Gallery)) {
        SegmentedControl(
            items =
                persistentListOf(
                    c.getString(R.string.tools_tab_media),
                    c.getString(R.string.tools_tab_links),
                    c.getString(R.string.tools_tab_docs),
                ),
            selected = tab.ordinal,
            onSelect = { tab = GalleryTabKind.entries[it] },
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        )
        val count =
            when (tab) {
                GalleryTabKind.Media -> answer.media.size
                GalleryTabKind.Links -> answer.links.size
                GalleryTabKind.Docs -> answer.docs.size
            }
        val sections =
            when (tab) {
                GalleryTabKind.Media -> answer.mediaSections
                GalleryTabKind.Links -> answer.linkSections
                GalleryTabKind.Docs -> answer.docSections
            }
        if (count == 0) {
            RostyText(
                c.getString(
                    when (tab) {
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
            val range = section.start until section.end.coerceAtMost(count)
            when (tab) {
                GalleryTabKind.Media -> {
                    MediaGrid(range.map { answer.media[it].id }, modelOf, c, onOpen)
                }

                GalleryTabKind.Links -> {
                    for (i in range) {
                        val link = answer.links[i]
                        ListRow(title = link.short, secondary = link.host, onClick = { onOpen(link.id) })
                    }
                }

                GalleryTabKind.Docs -> {
                    for (i in range) {
                        val doc = answer.docs[i]
                        ListRow(title = doc.name, secondary = doc.detail, onClick = { onOpen(doc.id) })
                    }
                }
            }
        }
    }
}

/** Square thumbnails, [GRID_COLUMNS] a row with hairline gaps, as Photos lays out a month. */
@Composable
private fun MediaGrid(
    ids: List<String>,
    modelOf: (String) -> Any?,
    c: Context,
    onOpen: (String) -> Unit,
) {
    val photo = c.getString(R.string.wa_photo)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        val gap = MessagesMetrics.GalleryGap
        val side = (maxWidth - gap * (GRID_COLUMNS - 1)) / GRID_COLUMNS
        val px = with(LocalDensity.current) { side.roundToPx() }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in ids.chunked(GRID_COLUMNS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (id in row) {
                        Box(
                            Modifier
                                .size(side)
                                .clip(RoundedCornerShape(MessagesMetrics.GalleryCorner))
                                .background(RostyTheme.colors.fillSecondary)
                                .testTag(ToolTags.media(id))
                                .clickable { onOpen(id) },
                        ) {
                            val model = modelOf(id)
                            if (model != null) {
                                SizedImage(
                                    model = model,
                                    widthPx = px,
                                    heightPx = px,
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.Crop,
                                    contentDescription = photo,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 3

object ToolTags {
    const val Gallery = "tools-gallery"

    fun media(id: String) = "tools-media-$id"
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
    val res = LocalResources.current
    val clock = LocalRostyClock.current
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
                    res.getString(
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
            clock = clock,
            copy = { text ->
                container.clipboard.copy(text)
                overlays.showToast(res.getString(R.string.conversation_copied))
            },
        )
    }
}

/** The bubbles of [items], newest first as listed. */
fun bubblesOf(items: List<ConversationItem>): List<BubbleUi> =
    items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }
