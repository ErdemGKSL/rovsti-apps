package dev.rosty.messages.wa

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rosty.core.model.wa.MediaKind
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.GlassIconButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.SizedImage
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.conversation.PlayGlyph
import dev.rosty.messages.conversation.ViewOnceGlyph
import dev.rosty.messages.inbox.duration
import dev.rosty.messages.theme.MediaMetrics
import kotlinx.coroutines.flow.drop

/** Test tags of the send preview. */
object WaBatchTags {
    const val Root = "wa-batch"
    const val Close = "wa-batch-close"
    const val Remove = "wa-batch-remove"
    const val ViewOnce = "wa-batch-view-once"
    const val Caption = "wa-batch-caption"
    const val Send = "wa-batch-send"
    const val Earlier = "wa-batch-earlier"
    const val Later = "wa-batch-later"

    fun thumb(index: Int) = "wa-batch-thumb-$index"
}

/** What the send preview does; the page wires them to [WaThreadViewModel]. */
@Immutable
class WaBatchActions(
    val onSelect: (Int) -> Unit,
    val onCaption: (id: String, text: String) -> Unit,
    val onViewOnce: (id: String) -> Unit,
    val onMove: (id: String, by: Int) -> Unit,
    val onRemove: (id: String) -> Unit,
    val onClose: () -> Unit,
    val onSend: () -> Unit,
) {
    companion object {
        fun of(model: WaThreadViewModel) =
            WaBatchActions(
                onSelect = model::batchSelect,
                onCaption = model::batchCaption,
                onViewOnce = model::batchViewOnce,
                onMove = model::batchMove,
                onRemove = model::batchRemove,
                onClose = model::batchClose,
                onSend = model::batchSend,
            )
    }
}

/**
 * The send preview of picked files (E6-WA R3), over the whole thread: swipe through them, a caption each, view once for
 * a photo or video (WhatsApp's 1 in a circle), remove one, reorder with the strip (move earlier, move later), and Send
 * with the count. Back closes it without sending.
 */
@Composable
fun WaBatchScreen(
    batch: WaSendBatch,
    actions: WaBatchActions,
    size: (Long) -> String,
) {
    BackHandler(onBack = actions.onClose)
    val item = batch.shown ?: return
    val pager = rememberPagerState(initialPage = batch.current) { batch.items.size }
    val select by rememberUpdatedState(actions.onSelect)
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.drop(1).collect { select(it) } }
    LaunchedEffect(batch.current, batch.items.size) {
        if (pager.currentPage != batch.current) pager.scrollToPage(batch.current)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            // Nothing under the preview takes a tap.
            // (a gesture, not a clickable: it adds no semantics, so every control is read on its own)
            .pointerInput(Unit) { detectTapGestures {} }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(WaBatchTags.Root),
    ) {
        TopRow(batch, item, actions)
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.gutter),
            pageSpacing = Spacing.gutter,
            key = { batch.items[it].id },
        ) { page -> Preview(batch.items[page], size) }
        Strip(batch, item, actions)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            key(item.id) {
                val field = rememberTextFieldState(item.caption)
                val caption by rememberUpdatedState(actions.onCaption)
                LaunchedEffect(field) {
                    snapshotFlow { field.text.toString() }.drop(1).collect { caption(item.id, it) }
                }
                GlassTextField(
                    field,
                    placeholder = stringResource(R.string.wa_batch_caption),
                    modifier = Modifier.weight(1f).testTag(WaBatchTags.Caption),
                    maxLines = CAPTION_LINES,
                )
            }
            CapsuleButton(
                text = stringResource(R.string.wa_batch_send, batch.items.size),
                onClick = actions.onSend,
                style = CapsuleStyle.Filled,
                modifier = Modifier.testTag(WaBatchTags.Send),
            )
        }
    }
}

@Composable
private fun TopRow(
    batch: WaSendBatch,
    item: WaBatchItem,
    actions: WaBatchActions,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s)) {
        GlassIconButton(
            RostyIcons.Xmark,
            stringResource(R.string.wa_batch_close),
            onClick = actions.onClose,
            modifier = Modifier.align(Alignment.CenterStart).testTag(WaBatchTags.Close),
            tint = Color.White,
        )
        if (batch.items.size > 1) {
            RostyText(
                stringResource(R.string.wa_batch_position, batch.current + 1, batch.items.size),
                modifier = Modifier.align(Alignment.Center),
                style = RostyTheme.type.headline,
                color = Color.White,
                maxLines = 1,
            )
        }
        Row(
            Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (item.canViewOnce) ViewOnceToggle(item.viewOnce) { actions.onViewOnce(item.id) }
            GlassIconButton(
                RostyIcons.Trash,
                stringResource(R.string.wa_batch_remove),
                onClick = { actions.onRemove(item.id) },
                modifier = Modifier.testTag(WaBatchTags.Remove),
                tint = Color.White,
            )
        }
    }
}

/** WhatsApp's view-once switch: the 1 in a ring, filled in the accent when it is on. */
@Composable
private fun ViewOnceToggle(
    on: Boolean,
    onToggle: () -> Unit,
) {
    val label = stringResource(R.string.wa_view_once_toggle)
    val state = stringResource(if (on) R.string.wa_view_once_toggle_on else R.string.wa_view_once_toggle_off)
    Box(
        Modifier
            .size(MediaMetrics.BatchTouch)
            .clip(CircleShape)
            .clickable(role = Role.Switch, onClickLabel = label, onClick = onToggle)
            .semantics {
                contentDescription = label
                stateDescription = state
            }.testTag(WaBatchTags.ViewOnce),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(MediaMetrics.BatchButton)
                .clip(CircleShape)
                .background(if (on) RostyTheme.colors.accent else Color.White.copy(alpha = CHIP_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            ViewOnceGlyph(
                tint = if (on) RostyTheme.colors.onAccent else Color.White,
                size = MediaMetrics.BatchGlyph,
                on = false,
            )
        }
    }
}

/** One page: the photo as it will look, a video's preview with its length, or a document's name and size. */
@Composable
private fun Preview(
    item: WaBatchItem,
    size: (Long) -> String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (item.kind) {
            MediaKind.Image -> {
                val density = LocalDensity.current
                val px = with(density) { PREVIEW_PX.dp.roundToPx() }
                SizedImage(
                    model = item.file.file,
                    widthPx = px,
                    heightPx = px,
                    contentScale = ContentScale.Fit,
                    contentDescription = item.file.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            MediaKind.Video -> {
                val thumb = item.measure?.thumbnail
                if (thumb != null) {
                    val density = LocalDensity.current
                    val px = with(density) { PREVIEW_PX.dp.roundToPx() }
                    SizedImage(
                        model = thumb,
                        widthPx = px,
                        heightPx = px,
                        contentScale = ContentScale.Fit,
                        contentDescription = item.file.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .size(
                            MediaMetrics.BatchMark,
                        ).clip(CircleShape)
                        .background(Color.Black.copy(alpha = MARK_ALPHA)),
                    contentAlignment = Alignment.Center,
                ) { PlayGlyph(Color.White, MediaMetrics.BatchGlyph) }
                item.measure?.seconds?.let { seconds ->
                    RostyText(
                        duration(seconds),
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(Spacing.s)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = MARK_ALPHA))
                                .padding(horizontal = Spacing.s, vertical = Spacing.textGap),
                        style = RostyTheme.type.caption1,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }

            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Box(
                        Modifier
                            .size(MediaMetrics.BatchDocTile)
                            .clip(RoundedCornerShape(Spacing.m))
                            .background(Color.White.copy(alpha = CHIP_ALPHA)),
                        contentAlignment = Alignment.Center,
                    ) { RostyIcon(RostyIcons.Document, null, tint = Color.White, size = MediaMetrics.BatchDocGlyph) }
                    RostyText(
                        item.file.name.rtlSafe(),
                        modifier = Modifier.widthIn(max = MediaMetrics.BatchDocName),
                        style = RostyTheme.type.headline,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    RostyText(
                        size(item.file.size),
                        style = RostyTheme.type.footnote,
                        color = Color.White.copy(alpha = SECONDARY_ALPHA),
                    )
                }
            }
        }
    }
}

/**
 * The strip of all the files, in the order they go out: a tap shows one; the two arrows move the one on screen
 * earlier or later (buttons rather than a drag: they are reachable with TalkBack and need no drag physics).
 */
@Composable
private fun Strip(
    batch: WaSendBatch,
    item: WaBatchItem,
    actions: WaBatchActions,
) {
    if (batch.items.size < 2) return
    val list = rememberLazyListState()
    LaunchedEffect(batch.current) { list.animateScrollToItem(batch.current) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            RostyIcons.Back,
            stringResource(R.string.wa_batch_earlier),
            onClick = { actions.onMove(item.id, -1) },
            enabled = batch.current > 0,
            modifier = Modifier.testTag(WaBatchTags.Earlier),
            tint = Color.White,
        )
        LazyRow(
            state = list,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            contentPadding = PaddingValues(horizontal = Spacing.xs),
        ) {
            itemsIndexed(batch.items, key = { _, it -> it.id }) { index, entry ->
                Thumb(entry, index == batch.current, Modifier.testTag(WaBatchTags.thumb(index))) {
                    actions.onSelect(index)
                }
            }
        }
        GlassIconButton(
            RostyIcons.ChevronRight,
            stringResource(R.string.wa_batch_later),
            onClick = { actions.onMove(item.id, 1) },
            enabled = batch.current < batch.items.lastIndex,
            modifier = Modifier.testTag(WaBatchTags.Later),
            tint = Color.White,
        )
    }
}

@Composable
private fun Thumb(
    item: WaBatchItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Spacing.s)
    Box(
        modifier
            .size(MediaMetrics.BatchThumb)
            .clip(shape)
            .background(Color.White.copy(alpha = CHIP_ALPHA))
            .then(if (selected) Modifier.border(MediaMetrics.BatchRing, RostyTheme.colors.accent, shape) else Modifier)
            .clickable(role = Role.Tab, onClickLabel = item.file.name, onClick = onClick)
            .semantics { contentDescription = item.file.name },
        contentAlignment = Alignment.Center,
    ) {
        val model: Any? =
            when (item.kind) {
                MediaKind.Image -> item.file.file
                MediaKind.Video -> item.measure?.thumbnail
                else -> null
            }
        if (model != null) {
            val px = with(LocalDensity.current) { MediaMetrics.BatchThumb.roundToPx() }
            SizedImage(model, px, px, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            RostyIcon(RostyIcons.Document, null, tint = Color.White, size = MediaMetrics.BatchGlyph)
        }
        if (item.kind == MediaKind.Video) PlayGlyph(Color.White, MediaMetrics.BatchSmallGlyph)
        if (item.viewOnce) {
            Box(Modifier.align(Alignment.TopEnd).padding(Spacing.textGap)) {
                ViewOnceGlyph(Color.White, MediaMetrics.BatchSmallGlyph, on = true, cutout = RostyTheme.colors.accent)
            }
        }
    }
}

/** The preview is black in both themes, as the viewer is: the photo reads as it will be seen. */
private val Backdrop = Color.Black
private const val PREVIEW_PX = 480
private const val CAPTION_LINES = 4
private const val CHIP_ALPHA = 0.18f
private const val MARK_ALPHA = 0.5f
private const val SECONDARY_ALPHA = 0.7f
