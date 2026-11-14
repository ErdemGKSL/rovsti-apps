package dev.rosty.messages.wa.status

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.rosty.core.model.wa.StatusItem
import dev.rosty.core.model.wa.StatusKind
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.GlassIconButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.SizedImage
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.OverlayScope
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.ui.MessagesAvatar
import kotlinx.collections.immutable.ImmutableList
import java.io.File

/** The words of the viewer, from `strings.xml`. */
class StatusViewerWords(
    val title: String,
    val close: String,
    val message: String,
    val loading: String,
    val photoFailed: String,
    val video: String,
    val videoLength: (String) -> String,
    val videoNote: String,
    val unsupported: String,
    val position: (Int, Int) -> String,
    /** "12 min ago" for an update's time. */
    val ago: (Long) -> String,
)

/** What the viewer asks of the app. */
class StatusViewerActions(
    /** The picture of an update (`thumbnail`: its preview) as a file, or null. */
    val picture: suspend (item: StatusItem, thumbnail: Boolean) -> File?,
    /** The update is on screen: mark it seen. */
    val onViewed: (StatusItem) -> Unit,
    /** "Message": open the chat with the author. */
    val onMessage: (jid: String) -> Unit,
)

/** Opens the viewer on the updates of `people[start]`. */
fun OverlayController.showStatusViewer(
    people: ImmutableList<StatusPerson>,
    start: Int,
    words: StatusViewerWords,
    actions: StatusViewerActions,
): OverlayEntry? {
    val player =
        StatusPlayer.of(people.map { it.items.size }, start, firstUnseen(people.getOrNull(start))) ?: return null
    return present(StatusViewerEntry(people, player, words, actions))
}

/** A person's updates start at the first one not seen yet (all seen: from the beginning). */
internal fun firstUnseen(person: StatusPerson?): Int =
    person?.items?.indexOfFirst { !it.viewed && !person.mine }?.takeIf { it >= 0 } ?: 0

/**
 * The full-screen Status viewer (E6.7, GPUI `status_ui`): the updates of one person after the other with a progress
 * bar each, then the next person. A tap on the right goes on, on the left back; holding pauses; a drag down or Back
 * closes. Each update is marked seen while it shows. Videos are not played here (as in the GPUI app).
 */
class StatusViewerEntry(
    private val people: ImmutableList<StatusPerson>,
    private val player: StatusPlayer,
    private val words: StatusViewerWords,
    private val actions: StatusViewerActions,
) : OverlayEntry(modal = true, title = words.title) {
    @Composable
    override fun Content(host: OverlayScope) {
        val close = { host.controller.dismiss(this) }
        var author by remember { mutableIntStateOf(player.author) }
        var index by remember { mutableIntStateOf(player.item) }
        val fill = remember { mutableFloatStateOf(0f) }

        fun moved(step: StatusStep) {
            if (step == StatusStep.Close) {
                close()
                return
            }
            author = player.author
            index = player.item
            fill.floatValue = player.fill(player.item)
        }

        val person = people[author]
        val item = person.items[index]
        LaunchedEffect(item.id) { if (!person.mine) actions.onViewed(item) }
        LaunchedEffect(player) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val step = player.advance((now - last) / NANOS_PER_MS)
                last = now
                if (step == StatusStep.Stay) fill.floatValue = player.fill(player.item) else moved(step)
            }
        }
        // The viewer is dark whatever the app's theme: its glass buttons and words sit on a photo or a colour.
        RostyTheme(darkTheme = true) { Stage(person, item, index, { fill.floatValue }, ::moved, close) }
    }

    @Composable
    private fun Stage(
        person: StatusPerson,
        item: StatusItem,
        index: Int,
        fill: () -> Float,
        moved: (StatusStep) -> Unit,
        close: () -> Unit,
    ) {
        val transition = rememberTransition(state, label = "status-viewer")
        val appear by transition.animateFloat(label = "appear") { if (it) 1f else 0f }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear }
                .background(Color.Black)
                .testTag(StatusTags.Viewer)
                .semantics { contentDescription = words.position(index + 1, person.items.size) }
                .pointerInput(player) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        player.paused = true
                        val up = waitForUpOrCancellation()
                        player.paused = false
                        if (up == null) return@awaitEachGesture
                        val dx = up.position.x - down.position.x
                        val dy = up.position.y - down.position.y
                        val held = up.uptimeMillis - down.uptimeMillis
                        val slop = viewConfiguration.touchSlop
                        when {
                            dy > SWIPE_CLOSE_SLOPS * slop && dy > kotlin.math.abs(dx) -> close()
                            kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop || held > HOLD_MS -> Unit
                            down.position.x < size.width * BACK_ZONE -> moved(player.back())
                            else -> moved(player.forward())
                        }
                    }
                },
        ) {
            Body(item, words, actions)
            val caption = item.text?.takeIf { item.kind != StatusKind.Text && it.isNotBlank() }
            val target = statusMessageTarget(person.author, person.mine || item.fromMe)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (target != null) {
                    CapsuleButton(
                        words.message,
                        onClick = {
                            close()
                            actions.onMessage(target)
                        },
                        modifier =
                            Modifier
                                .padding(bottom = if (caption == null) StatusMetrics.MessageLift else Spacing.l)
                                .then(if (caption == null) Modifier.navigationBarsPadding() else Modifier)
                                .testTag(StatusTags.Message),
                        icon = RostyIcons.Message,
                    )
                }
                if (caption != null) {
                    RostyText(
                        caption.rtlSafe(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = CAPTION_SCRIM))
                                .navigationBarsPadding()
                                .padding(horizontal = Spacing.xl, vertical = Spacing.l),
                        style = RostyTheme.type.body,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Bars(person.items.size, index, fill)
                Header(person, words.ago(item.timestampMs), words.close, close)
            }
        }
    }
}

/** One bar per update of the person: full for the ones played, filling for the one on screen. */
@Composable
private fun Bars(
    count: Int,
    current: Int,
    fill: () -> Float,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(StatusMetrics.BarGap),
    ) {
        repeat(count) { at ->
            Box(
                Modifier
                    .weight(1f)
                    .height(StatusMetrics.Bar)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = BAR_TRACK))
                    .drawBehind {
                        // Read in the draw phase: the timer never recomposes the viewer.
                        val part =
                            when {
                                at < current -> 1f
                                at > current -> 0f
                                else -> fill()
                            }
                        drawRect(Color.White, size = Size(size.width * part, size.height))
                    },
            )
        }
    }
}

@Composable
private fun Header(
    person: StatusPerson,
    time: String,
    closeLabel: String,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        MessagesAvatar(person.title, person.avatar, size = StatusMetrics.ViewerAvatar)
        Column(Modifier.weight(1f)) {
            RostyText(person.title.rtlSafe(), style = RostyTheme.type.headline, color = Color.White, maxLines = 1)
            RostyText(
                time,
                style = RostyTheme.type.caption1,
                color = Color.White.copy(alpha = NOTE_ALPHA),
                maxLines = 1,
            )
        }
        GlassIconButton(RostyIcons.Xmark, closeLabel, onClick = onClose, modifier = Modifier.testTag(StatusTags.Close))
    }
}

/** The update itself, filling the screen. */
@Composable
private fun Body(
    item: StatusItem,
    words: StatusViewerWords,
    actions: StatusViewerActions,
) {
    when (item.kind) {
        StatusKind.Text -> {
            Box(
                Modifier.fillMaxSize().background(argb(item.backgroundArgb ?: DEFAULT_BACKGROUND)),
                contentAlignment = Alignment.Center,
            ) {
                // WhatsApp's own fonts are not here: script fonts read as italic, the heavy ones as bold.
                val base = RostyTheme.type.title1
                RostyText(
                    item.text.orEmpty().rtlSafe(),
                    modifier = Modifier.widthIn(max = StatusMetrics.TextMaxWidth).padding(horizontal = Spacing.xl),
                    style =
                        when (item.font) {
                            FONT_SCRIPT -> base.copy(fontStyle = FontStyle.Italic)
                            in FONTS_HEAVY -> base.copy(fontWeight = FontWeight.Bold)
                            else -> base
                        },
                    color = item.textArgb?.let(::argb) ?: Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }

        StatusKind.Image -> {
            val picture by pictureOf(item, actions, full = true)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Picture(picture.file)
                if (!picture.full) Note(if (picture.failed) words.photoFailed else words.loading)
            }
        }

        StatusKind.Video -> {
            val picture by pictureOf(item, actions, full = false)
            val seconds = item.media?.seconds ?: 0
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Picture(picture.file)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Box(
                        Modifier.size(StatusMetrics.Play).clip(CircleShape).background(Color.Black.copy(CAPTION_SCRIM)),
                        contentAlignment = Alignment.Center,
                    ) {
                        // A triangle from the chevron would look like navigation: the camera glyph says "video".
                        RostyIcon(RostyIcons.Camera, null, tint = Color.White, size = StatusMetrics.PlayGlyph)
                    }
                    Note(if (seconds > 0) words.videoLength(lengthOf(seconds)) else words.video)
                    Note(words.videoNote)
                }
            }
        }

        StatusKind.Other -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Note(words.unsupported) }
        }
    }
}

private data class Shown(
    val file: File? = null,
    /** The photo itself is shown, not only its preview. */
    val full: Boolean = false,
    val failed: Boolean = false,
)

/** The preview first, then (for a photo) the photo itself. */
@Composable
private fun pictureOf(
    item: StatusItem,
    actions: StatusViewerActions,
    full: Boolean,
) = produceState(Shown(), item.id) {
    val preview = actions.picture(item, true)
    value = Shown(preview)
    if (full) {
        val photo = actions.picture(item, false)
        value = if (photo != null) Shown(photo, full = true) else Shown(preview, failed = true)
    }
}

@Composable
private fun Picture(file: File?) {
    if (file == null) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        SizedImage(
            model = file,
            widthPx = constraints.maxWidth,
            heightPx = constraints.maxHeight,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun Note(text: String) {
    RostyText(
        text,
        modifier = Modifier.padding(horizontal = Spacing.xl),
        style = RostyTheme.type.subheadline,
        color = Color.White.copy(alpha = NOTE_ALPHA),
        textAlign = TextAlign.Center,
    )
}

/** A WhatsApp `0xAARRGGBB` colour. */
internal fun argb(value: Long): Color = Color(value.toInt())

/** "0:14" for a video's length. */
internal fun lengthOf(seconds: Int): String =
    "%d:%02d".format(
        seconds / SECONDS_PER_MINUTE,
        seconds % SECONDS_PER_MINUTE,
    )

private const val NANOS_PER_MS = 1_000_000L
private const val SECONDS_PER_MINUTE = 60
private const val HOLD_MS = 300L
private const val SWIPE_CLOSE_SLOPS = 6f
private const val BACK_ZONE = 0.33f
private const val BAR_TRACK = 0.35f
private const val NOTE_ALPHA = 0.85f
private const val CAPTION_SCRIM = 0.45f
private const val FONT_SCRIPT = 2
private val FONTS_HEAVY = setOf(6, 8, 9, 10)

/** The background of a text update that names none (WhatsApp's teal). */
private const val DEFAULT_BACKGROUND = 0xFF1E6E4FL
