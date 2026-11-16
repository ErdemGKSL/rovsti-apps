package dev.rosty.messages.wa

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMediaState
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.design.chrome.ToolbarButton
import dev.rosty.design.chrome.ToolbarCapsule
import dev.rosty.design.controls.ActivityIndicator
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosSlider
import dev.rosty.design.media.ViewerAction
import dev.rosty.design.media.ViewerExtras
import dev.rosty.design.media.ViewerPages
import dev.rosty.design.media.ViewerPhoto
import dev.rosty.design.media.showPhotoViewer
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.conversation.MediaAnchors
import dev.rosty.messages.theme.MediaMetrics
import dev.rosty.messages.viewer.MediaIcons
import dev.rosty.messages.viewer.PauseInBackground
import dev.rosty.messages.viewer.VideoSurface
import dev.rosty.messages.viewer.rememberVideoPlayers
import dev.rosty.platform.media.VideoPlayer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Test tags of the WhatsApp media viewer. */
object WaViewerTags {
    const val Caption = "wa-viewer-caption"
    const val Byline = "wa-viewer-byline"
    const val Controls = "wa-viewer-controls"
    const val PlayPause = "wa-viewer-play"
    const val Mute = "wa-viewer-mute"
    const val Seek = "wa-viewer-seek"

    fun download(id: String) = "wa-viewer-download-$id"

    fun gone(id: String) = "wa-viewer-gone-$id"
}

/** Where the full file of an item stands while the viewer shows it. */
enum class WaViewerLoad { Loading, Failed, Gone }

/** What the viewer reads: thumbnails and downloads, the chat's gallery, who wrote a message, where it was saved. */
class WaViewerSources(
    /** The thumbnail file of a message when it is known. */
    val thumb: (WaSyncMessage) -> File?,
    /** Makes the thumbnail a file (store, else gateway); true when a new one appeared. */
    val ensureThumb: suspend (WaSyncMessage) -> Boolean,
    /** The full file, asked for as a tap does; null when it could not come now. */
    val fetch: suspend (WaSyncMessage) -> File?,
    /** True when the gateway no longer has the file. */
    val gone: suspend (WaSyncMessage) -> Boolean,
    val gallery: WaGallerySource?,
    /** The name of whoever sent [row] (not called for my own messages). */
    val sender: suspend (row: WaSyncMessage) -> String,
    /** The copy of [row] saved to the phone, if one is still there. */
    val savedUri: suspend (row: WaSyncMessage) -> String?,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        /** The app's: thumbnails and downloads through [media]. */
        fun of(
            media: WaMedia,
            gallery: WaGallerySource?,
            sender: suspend (WaSyncMessage) -> String,
            savedUri: suspend (WaSyncMessage) -> String?,
        ) = WaViewerSources(media::thumb, media::ensureThumb, media::fetch, media::gone, gallery, sender, savedUri)
    }
}

/** What the viewer's buttons do; each answers once it is done (Save: true when the item is saved now). */
class WaViewerActions(
    val save: suspend (WaSyncMessage) -> Boolean,
    val share: suspend (WaSyncMessage) -> Unit,
    val forward: suspend (WaSyncMessage) -> Unit,
)

/** The words of the viewer, read once from the resources. */
class WaViewerLabels(
    val save: String,
    val saved: String,
    val share: String,
    val forward: String,
    val play: String,
    val pause: String,
    val mute: String,
    val unmute: String,
    val position: String,
    val download: String,
    val downloadSize: (String) -> String,
    val downloading: String,
    val retry: String,
    val cantDownload: String,
    val gone: String,
    val cantPlay: String,
    val you: String,
    val byline: (String, String) -> String,
    val locale: Locale,
    val sizeOf: (Long) -> String,
) {
    companion object {
        fun of(
            res: Resources,
            sizeOf: (Long) -> String = ::plainSize,
        ) = WaViewerLabels(
            save = res.getString(R.string.viewer_save),
            saved = res.getString(R.string.wa_viewer_saved),
            share = res.getString(R.string.viewer_share),
            forward = res.getString(R.string.conversation_forward),
            play = res.getString(R.string.wa_viewer_play),
            pause = res.getString(R.string.wa_viewer_pause),
            mute = res.getString(R.string.wa_viewer_mute),
            unmute = res.getString(R.string.wa_viewer_unmute),
            position = res.getString(R.string.wa_viewer_position),
            download = res.getString(R.string.wa_viewer_download),
            downloadSize = { res.getString(R.string.wa_viewer_download_size, it) },
            downloading = res.getString(R.string.wa_viewer_downloading),
            retry = res.getString(R.string.wa_viewer_retry),
            cantDownload = res.getString(R.string.wa_media_cant_download),
            gone = res.getString(R.string.wa_media_gone),
            cantPlay = res.getString(R.string.wa_viewer_cant_play),
            you = res.getString(R.string.wa_thread_you),
            byline = { who, at -> res.getString(R.string.wa_viewer_byline, who, at) },
            locale = res.configuration.locales[0],
            sizeOf = sizeOf,
        )
    }
}

/**
 * One open viewer over the photos and videos of a WhatsApp chat (E6-WA R3): the [gallery] it pages, what each item
 * shows (thumbnail, file, download state, the sender and time line, whether it was saved) and the video on show. The
 * page on show downloads its photo by itself (a tap on the bubble asked for it); a video that was not the one tapped
 * waits for its Download button. A video keeps its position when the viewer pages away and back.
 */
@Stable
class WaViewerSession(
    opened: WaSyncMessage,
    private val sources: WaViewerSources,
    private val labels: WaViewerLabels,
    private val scope: CoroutineScope,
) : ViewerPages {
    val gallery = WaGallery(opened, sources.gallery)
    private val openedId = opened.id

    override val count: Int get() = gallery.items.size
    override val prepended: Int get() = gallery.prepended

    override fun model(index: Int): Any? = gallery.items.getOrNull(index)?.let(::photoOf)

    // The page on show, kept like the viewer keeps it: the index it was set to plus the items put in front since.
    private var pageAt by mutableIntStateOf(0)
    private var pageShift by mutableIntStateOf(0)

    /** The page on show. */
    val page: Int get() = pageAt + (gallery.prepended - pageShift)

    private val thumbs = mutableStateMapOf<String, File>()
    private val loads = mutableStateMapOf<String, WaViewerLoad>()
    private val saved = mutableStateMapOf<String, Boolean>()
    private val bylines = mutableStateMapOf<String, String>()
    private val positions = HashMap<String, Int>()

    /** Sound of the videos, kept from one video to the next. */
    var muted by mutableStateOf(false)

    /** The video on show and playing in a surface; null for a photo, a GIF or a video that is not on the phone. */
    var player by mutableStateOf<VideoPlayer?>(null)
        internal set

    /** Reads the first pages around the opened message and gets the opened one ready. */
    fun start() {
        visit(0)
        scope.launch {
            gallery.start()
            visit(page)
        }
    }

    /** The viewer moved to [index]. */
    fun onPage(index: Int) {
        pageAt = index
        pageShift = gallery.prepended
        visit(index)
    }

    fun row(index: Int): WaSyncMessage? = gallery.items.getOrNull(index)

    fun isVideo(row: WaSyncMessage) = row.kind == WaSyncKind.Video

    fun isGif(row: WaSyncMessage) = isVideo(row) && row.media?.animated == true

    /** The downloaded file of [row]; the store names it only while it is cached. */
    fun local(row: WaSyncMessage): File? = row.mediaPath?.let(::File)

    fun loadOf(row: WaSyncMessage): WaViewerLoad? =
        loads[row.id] ?: WaViewerLoad.Gone.takeIf { local(row) == null && row.mediaState == WaSyncMediaState.Gone }

    fun isSaved(row: WaSyncMessage): Boolean = saved[row.id] == true

    fun byline(row: WaSyncMessage): String? = bylines[row.id]

    fun caption(row: WaSyncMessage): String? = row.text?.takeIf { it.isNotBlank() }

    /** The position to start a video at: where it was left, else the start. */
    fun positionOf(id: String): Int = positions[id] ?: 0

    /** A video left the screen at [ms]; one that played to its end starts over next time. */
    fun keep(
        id: String,
        ms: Int,
        ended: Boolean,
    ) {
        if (ended || ms <= 0) positions.remove(id) else positions[id] = ms
    }

    private fun photoOf(row: WaSyncMessage): ViewerPhoto {
        val thumb = thumbs[row.id] ?: sources.thumb(row)
        val full = if (isVideo(row)) thumb else local(row) ?: thumb
        return ViewerPhoto(
            full = full,
            thumb =
                thumb.takeIf {
                    it != full
                },
            source = dev.rosty.design.media.PhotoSource.WhatsApp,
        )
    }

    /** The size the message names, so the box has its shape before any picture loads. */
    fun sizeOf(index: Int): Size? {
        val meta = row(index)?.media ?: return null
        val w = meta.width?.takeIf { it > 0 } ?: return null
        val h = meta.height?.takeIf { it > 0 } ?: return null
        return Size(w.toFloat(), h.toFloat())
    }

    private fun visit(index: Int) {
        // The rows first: a page put in front by the read below moves every index.
        val around = (index - 1..index + 1).mapNotNull { i -> row(i)?.let { it to (i == index) } }
        around.forEach { (row, current) -> prepare(row, current) }
        scope.launch { gallery.ensure(index) }
    }

    private fun prepare(
        row: WaSyncMessage,
        current: Boolean,
    ) {
        if (row.id !in thumbs && sources.thumb(row) == null) {
            scope.launch { if (sources.ensureThumb(row)) sources.thumb(row)?.let { thumbs[row.id] = it } }
        }
        if (row.id !in bylines) {
            scope.launch {
                val who = if (row.fromMe) labels.you else sources.sender(row)
                bylines[row.id] = labels.byline(who, timeOf(row.timestampMs))
            }
        }
        if (row.id !in saved && row.media?.viewOnce != true) {
            saved[row.id] = false
            scope.launch { saved[row.id] = sources.savedUri(row) != null }
        }
        val auto = !isVideo(row) || row.id == openedId
        if (current && auto && local(row) == null && loadOf(row) == null) download(row)
    }

    /** Asks for the file of [row] as a tap does (the store's queue, taps first) and shows it once it is here. */
    fun download(row: WaSyncMessage) {
        if (loads[row.id] == WaViewerLoad.Loading) return
        loads[row.id] = WaViewerLoad.Loading
        scope.launch {
            val file = sources.fetch(row)
            if (file != null) {
                gallery.replace(row.copy(mediaPath = file.absolutePath, mediaState = WaSyncMediaState.Ready))
                loads.remove(row.id)
            } else {
                loads[row.id] = if (sources.gone(row)) WaViewerLoad.Gone else WaViewerLoad.Failed
            }
        }
    }

    /** Save, done: the button reads "Saved" from now on. */
    fun markSaved(row: WaSyncMessage) {
        saved[row.id] = true
    }

    private fun timeOf(ms: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(labels.locale)
            .format(Instant.ofEpochMilli(ms).atZone(sources.zone))
}

/**
 * Opens the full-screen viewer of a WhatsApp chat's photos and videos on [session]'s opened message (E6-WA R3): the
 * photo viewer of `:design` (pinch and double-tap zoom, pan, swipe between items, drag down to dismiss, a tap hides the
 * chrome) with, per item, the video and its controls, the download state, the caption and the sender and time line,
 * and Save, Share and Forward. A view-once message has none of the three. The photo flies out of its bubble and back
 * into it while the bubble is on screen ([anchors]).
 */
fun OverlayController.showWaViewer(
    session: WaViewerSession,
    labels: WaViewerLabels,
    actions: WaViewerActions,
    scope: CoroutineScope,
    anchors: MediaAnchors?,
): OverlayEntry {
    session.start()
    val extras =
        ViewerExtras(
            actionsFor = { page -> session.row(page)?.let { actionsOf(it, session, labels, actions, scope) } ?: NONE },
            titleFor = { "" },
            pageOverlay = { index, current -> WaPageOverlay(session, labels, index, current) },
            bottom = { page -> WaViewerBottom(session, labels, page) },
            sizeHint = session::sizeOf,
        )
    return showPhotoViewer(
        pages = session,
        initialPage = 0,
        actions = NONE,
        extras = extras,
        anchorFor = { page -> session.row(page)?.let { anchors?.get(WaThreadViewModel.keyOf(it)) } },
        onPageChanged = session::onPage,
    )
}

/** The toolbar of [row]: Save (or "Saved"), Share, Forward; nothing for a view-once message. */
internal fun actionsOf(
    row: WaSyncMessage,
    session: WaViewerSession,
    labels: WaViewerLabels,
    actions: WaViewerActions,
    scope: CoroutineScope,
): ImmutableList<ViewerAction> {
    if (row.media?.viewOnce == true || row.deleted) return NONE
    val saved = session.isSaved(row)
    return persistentListOf(
        ViewerAction(
            "save",
            if (saved) RostyIcons.Checkmark else RostyIcons.Download,
            if (saved) labels.saved else labels.save,
        ) {
            scope.launch { if (actions.save(row)) session.markSaved(row) }
        },
        ViewerAction("share", RostyIcons.Share, labels.share) { scope.launch { actions.share(row) } },
        ViewerAction("forward", RostyIcons.Forward, labels.forward) { scope.launch { actions.forward(row) } },
    )
}

private val NONE: ImmutableList<ViewerAction> = persistentListOf()

/** Over one page: the playing video of the page on show, else what keeps the file from being shown. */
@Composable
private fun BoxScope.WaPageOverlay(
    session: WaViewerSession,
    labels: WaViewerLabels,
    index: Int,
    current: Boolean,
) {
    val row = session.row(index) ?: return
    val file = session.local(row)
    when {
        file != null && session.isVideo(row) && current -> WaVideoPage(session, row, file)
        file == null -> DownloadMark(session, labels, row)
        else -> Unit
    }
}

/** The video of the page on show, on a texture under the zoom; it starts where it was left. */
@Composable
private fun WaVideoPage(
    session: WaViewerSession,
    row: WaSyncMessage,
    file: File,
) {
    val players = rememberVideoPlayers()
    val gif = session.isGif(row)
    val player =
        remember(file, players) {
            players.open(
                file,
                loop = gif,
                muted = gif || session.muted,
                startMs = if (gif) 0 else session.positionOf(row.id),
                autoplay = true,
            )
        }
    DisposableEffect(player) {
        if (!gif) session.player = player
        onDispose {
            session.keep(row.id, player.positionMs(), player.state.value.ended)
            if (session.player === player) session.player = null
            player.release()
        }
    }
    PauseInBackground(player, resume = gif)
    VideoSurface(player, Modifier.fillMaxSize())
}

/** A file that is not on the phone: Download (with its size), the spinner while it comes, Try Again, or gone. */
@Composable
private fun BoxScope.DownloadMark(
    session: WaViewerSession,
    labels: WaViewerLabels,
    row: WaSyncMessage,
) {
    val load = session.loadOf(row)
    val size = row.size?.takeIf { it > 0 }?.let(labels.sizeOf)
    Column(
        Modifier.align(Alignment.Center).padding(Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        when (load) {
            WaViewerLoad.Gone -> {
                Pill(labels.gone, Modifier.testTag(WaViewerTags.gone(row.id)))
            }

            WaViewerLoad.Loading -> {
                Disc { ActivityIndicator(labels.downloading, color = Color.White, size = MediaMetrics.ViewerDiscGlyph) }
                Pill(listOfNotNull(labels.downloading, size).joinToString(" · "))
            }

            WaViewerLoad.Failed, null -> {
                val label =
                    when {
                        load == WaViewerLoad.Failed -> labels.retry
                        size != null -> labels.downloadSize(size)
                        else -> labels.download
                    }
                Column(
                    Modifier
                        .clip(
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(MediaMetrics.ViewerDownloadCorner),
                        ).clickable(role = Role.Button, onClickLabel = label) { session.download(row) }
                        .semantics { contentDescription = label }
                        .testTag(WaViewerTags.download(row.id)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Disc {
                        RostyIcon(
                            if (load == WaViewerLoad.Failed) RostyIcons.Rotate else RostyIcons.Download,
                            null,
                            tint = Color.White,
                            size = MediaMetrics.ViewerDiscGlyph,
                        )
                    }
                    if (load == WaViewerLoad.Failed) Pill(labels.cantDownload)
                    Pill(label)
                }
            }
        }
    }
}

@Composable
private fun Disc(content: @Composable () -> Unit) {
    Box(
        Modifier.size(MediaMetrics.ViewerDisc).clip(CircleShape).background(Color.Black.copy(alpha = SCRIM)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Pill(
    text: String,
    modifier: Modifier = Modifier,
) {
    RostyText(
        text,
        modifier =
            modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = SCRIM))
                .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        style = RostyTheme.type.footnote,
        color = Color.White,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Above the toolbar: the video's controls, the caption and who sent it when. */
@Composable
private fun ColumnScope.WaViewerBottom(
    session: WaViewerSession,
    labels: WaViewerLabels,
    page: Int,
) {
    val row = session.row(page) ?: return
    val player = session.player
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(bottom = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        session.caption(row)?.let { caption ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(
                        max = MediaMetrics.ViewerCaptionMax,
                    ).verticalScroll(rememberScrollState()),
            ) {
                RostyText(
                    caption,
                    modifier = Modifier.fillMaxWidth().testTag(WaViewerTags.Caption),
                    style = RostyTheme.type.subheadline,
                    color = Color.White,
                )
            }
        }
        session.byline(row)?.let { line ->
            RostyText(
                line,
                modifier = Modifier.fillMaxWidth().testTag(WaViewerTags.Byline),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (player != null && session.isVideo(row) && !session.isGif(row) && session.local(row) != null) {
        VideoControls(player, session, labels)
    }
}

/** Play and pause, the time played, the seek bar, the length, mute. */
@Composable
private fun VideoControls(
    player: VideoPlayer,
    session: WaViewerSession,
    labels: WaViewerLabels,
) {
    val state by player.state.collectAsState()
    var position by remember(player) { mutableIntStateOf(player.positionMs()) }
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player, state.playing) {
        position = player.positionMs()
        while (state.playing) {
            delay(TICK_MS)
            if (scrub == null) position = player.positionMs()
        }
    }
    val duration = state.durationMs.coerceAtLeast(0)
    val fraction = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var held by remember(player) { mutableFloatStateOf(0f) }
    val shown = if (scrub != null) (fraction * duration).toInt() else position
    ToolbarCapsule(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.s)
            .testTag(WaViewerTags.Controls),
    ) {
        ToolbarButton(
            icon = if (state.playing) RostyIcons.Pause else MediaIcons.Play,
            label = if (state.playing) labels.pause else labels.play,
            onClick = { if (state.playing) player.pause() else player.play() },
            modifier = Modifier.testTag(WaViewerTags.PlayPause),
            enabled = state.prepared && !state.failed,
        )
        TimeLabel(clock(shown))
        IosSlider(
            value = fraction,
            onValueChange = {
                held = it
                scrub = it
            },
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.xs)
                    .semantics { contentDescription = labels.position }
                    .testTag(WaViewerTags.Seek),
            tint = Color.White,
            enabled = state.prepared && duration > 0,
            onEditingChanged = { editing ->
                if (!editing) {
                    val to = (held * duration).toInt()
                    player.seekTo(to)
                    position = to
                    scrub = null
                }
            },
        )
        TimeLabel(clock(duration))
        ToolbarButton(
            icon = if (session.muted) MediaIcons.SpeakerSlash else RostyIcons.Speaker,
            label = if (session.muted) labels.unmute else labels.mute,
            onClick = {
                session.muted = !session.muted
                player.setMuted(session.muted)
            },
            modifier = Modifier.testTag(WaViewerTags.Mute),
        )
    }
    if (state.failed) {
        RostyText(
            labels.cantPlay,
            modifier = Modifier.padding(bottom = Spacing.s),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
    }
}

@Composable
private fun TimeLabel(text: String) {
    RostyText(
        text,
        style = RostyTheme.type.caption1.copy(fontFeatureSettings = TABULAR),
        color = Color.White,
        maxLines = 1,
    )
}

/** "0:07", "12:40", "1:02:03". */
internal fun clock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

private const val TABULAR = "tnum"
private const val TICK_MS = 200L
private const val SCRIM = 0.55f
