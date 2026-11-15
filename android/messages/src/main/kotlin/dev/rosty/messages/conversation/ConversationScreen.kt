package dev.rosty.messages.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.rosty.design.chrome.ScreenScaffold
import dev.rosty.design.chrome.topBarContentPadding
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.GlassIconButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.SizedImage
import dev.rosty.design.overlay.ContextMenuStyle
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.longPressMenu
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.overlay.popoverTrigger
import dev.rosty.design.text.ComposerField
import dev.rosty.design.text.SearchField
import dev.rosty.design.text.imeOverlapPadding
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.access.DefaultSmsCard
import dev.rosty.messages.theme.MediaMetrics
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.messages.ui.MessagesAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Test tags of a conversation page. */
object ConversationTags {
    const val Root = "conversation"
    const val List = "conversation-list"
    const val Composer = "conversation-composer"
    const val LoadOlder = "conversation-load-older"
    const val Notice = "conversation-notice"
    const val Sim = "conversation-sim"
    const val Counter = "conversation-counter"
    const val Menu = "conversation-menu"
    const val Find = "conversation-find"
    const val FindLabel = "conversation-find-label"
    const val JumpLatest = "conversation-jump-latest"

    fun bubble(key: String) = "bubble-$key"

    fun sender(key: String) = "sender-$key"
}

/** What a conversation page does with taps; the view model behind it (SMS or WhatsApp) answers. */
class ConversationActions(
    val onBack: () -> Unit,
    val onSend: (String) -> Unit,
    val onLoadOlder: () -> Unit,
    val onBubbleTap: (BubbleUi) -> Unit = {},
    val menuFor: (BubbleUi) -> ImmutableList<MenuItem>,
    val onAvatar: () -> Unit = {},
    val onSim: () -> Unit = {},
    val onRoleResult: (Boolean) -> Unit = {},
    val headerMenu: ImmutableList<MenuItem> = kotlinx.collections.immutable.persistentListOf(),
    val onFindQuery: (String) -> Unit = {},
    val onFindOlder: () -> Unit = {},
    val onFindNewer: () -> Unit = {},
    val onFindClose: () -> Unit = {},
    val onScrolled: () -> Unit = {},
    /** M31: a swipe to the right on a bubble that [BubbleUi.canReply]; null: bubbles do not swipe. */
    val onSwipeReply: ((BubbleUi) -> Unit)? = null,
    val onCancelReply: () -> Unit = {},
    val onRemoveAttachment: () -> Unit = {},
    /** What the + beside the composer offers; empty: no button. */
    val attachMenu: ImmutableList<MenuItem> = kotlinx.collections.immutable.persistentListOf(),
    /** Save or Share on a file row. */
    val onMediaAction: (BubbleUi, MediaAction) -> Unit = { _, _ -> },
    val onVoiceToggle: (BubbleUi) -> Unit = {},
    val onVoiceSeek: (BubbleUi, Float) -> Unit = { _, _ -> },
    val onVoiceSpeed: () -> Unit = {},
    /** M50: the card a tap on a group sender's name opens; null: names are plain text. */
    val senderCard: (@Composable (BubbleUi) -> Unit)? = null,
)

/** The labels of the tools on a conversation page. */
class ToolLabels(
    val menu: String,
    val find: String,
    val older: String,
    val newer: String,
    val close: String,
    val jumpLatest: String,
    val position: (Int, Int) -> String,
    val noMatches: String,
)

/**
 * One conversation, SMS or WhatsApp (E6.3, E6.4a): the frosted header with the avatar, a reverse-layout list (newest at
 * the bottom, the last bubble stays above the composer with the keyboard open), and the composer docked to the IME.
 * Without the SMS role the composer is the default-SMS ask.
 */
@Composable
fun ConversationScreen(
    state: ConversationUi,
    composer: TextFieldState,
    listState: LazyListState,
    actions: ConversationActions,
    modifier: Modifier = Modifier,
    sendLabel: String,
    olderLabel: String,
    tools: ToolsUi = ToolsUi(),
    labels: ToolLabels? = null,
    /** The voice note in the player (M17). */
    voice: VoiceUi? = null,
    /** Added to the composer's text field (a content receiver for pasted pictures, M46). */
    composerModifier: Modifier = Modifier,
) {
    val overlays = LocalOverlayOrNull.current
    // Find, jump to date: scroll the target into view once, then let the tools know.
    LaunchedEffect(tools.scrollTo) {
        val target = tools.scrollTo ?: return@LaunchedEffect
        val index = state.items.indexOfFirst { it.key == target }
        if (index >= 0) listState.animateScrollToItem(index)
        actions.onScrolled()
    }
    ScreenScaffold(
        title = state.title,
        titleAlpha = 1f,
        onBack = actions.onBack,
        modifier = modifier.testTag(ConversationTags.Root),
        actions = {
            if (overlays != null && actions.headerMenu.isNotEmpty() && labels != null) {
                Box(
                    Modifier
                        .size(Sizes.touch)
                        .menuTrigger(overlays, actions.headerMenu, labels.menu)
                        .testTag(ConversationTags.Menu),
                    contentAlignment = Alignment.Center,
                ) {
                    RostyIcon(
                        RostyIcons.Ellipsis,
                        null,
                        tint = RostyTheme.colors.accent,
                        size = MessagesMetrics.FolderGlyph,
                    )
                }
            }
            Box(
                Modifier
                    .padding(end = Spacing.xs)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = actions.onAvatar,
                    ),
            ) {
                MessagesAvatar(state.title, state.avatar, size = MessagesMetrics.JumpButton)
            }
        },
    ) { chrome ->
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).then(chrome.screenModifier())) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().then(chrome.contentModifier()).testTag(ConversationTags.List),
                    contentPadding =
                        PaddingValues(
                            top = topBarContentPadding() + subtitleRoom(state),
                            bottom = Spacing.s,
                        ),
                ) {
                    items(state.items, key = { it.key }, contentType = { it::class }) { item ->
                        when (item) {
                            is ConversationItem.Day -> {
                                CenteredLabel(item.label)
                            }

                            is ConversationItem.Divider -> {
                                CenteredLabel(item.label, accent = true)
                            }

                            is ConversationItem.Bubble -> {
                                BubbleRow(
                                    item.bubble,
                                    actions,
                                    glow =
                                        item.key == tools.highlight,
                                    voice = voice?.takeIf { it.key == item.key },
                                    speedLabel = state.voiceSpeed,
                                )
                            }
                        }
                    }
                    if (state.hasOlder) {
                        item(key = "older", contentType = "older") {
                            Box(Modifier.fillMaxWidth().padding(Spacing.s), contentAlignment = Alignment.Center) {
                                CapsuleButton(
                                    olderLabel,
                                    onClick = actions.onLoadOlder,
                                    modifier = Modifier.testTag(ConversationTags.LoadOlder),
                                    enabled = !state.loadingOlder,
                                )
                            }
                        }
                    }
                }
                if (labels != null) {
                    JumpToLatest(listState, state, labels, Modifier.align(Alignment.BottomEnd))
                    tools.find?.let { find -> FindBar(find, labels, actions, Modifier.align(Alignment.TopCenter)) }
                }
                val lines =
                    if (tools.find !=
                        null
                    ) {
                        emptyList()
                    } else {
                        listOfNotNull(state.subtitle, state.notice, state.error)
                    }
                if (lines.isNotEmpty()) {
                    RostyText(
                        lines.joinToString(" · "),
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = topBarContentPadding())
                                .padding(horizontal = Spacing.gutter)
                                .clip(CircleShape)
                                .background(RostyTheme.colors.page.copy(alpha = NOTICE_ALPHA))
                                .padding(horizontal = Spacing.m, vertical = Spacing.xs)
                                .testTag(ConversationTags.Notice),
                        style = RostyTheme.type.footnote,
                        color = RostyTheme.colors.labelSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
            when (val mode = state.composer) {
                is ComposerMode.Ready -> {
                    if (mode.simLabel != null || mode.counter != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            mode.simLabel?.let {
                                CapsuleButton(
                                    it,
                                    onClick = actions.onSim,
                                    modifier = Modifier.testTag(ConversationTags.Sim),
                                    icon = RostyIcons.Sim,
                                )
                            }
                            Box(Modifier.weight(1f))
                            mode.counter?.let {
                                RostyText(
                                    it,
                                    modifier = Modifier.testTag(ConversationTags.Counter),
                                    style = RostyTheme.type.caption1,
                                    color = RostyTheme.colors.labelSecondary,
                                )
                            }
                        }
                    }
                    state.reply?.let { ReplyBar(it, actions.onCancelReply) }
                    state.attachment?.let {
                        AttachmentBar(
                            it,
                            sendLabel,
                            onRemove = actions.onRemoveAttachment,
                            onSend = { actions.onSend(composer.text.toString()) },
                        )
                    }
                    val attach = actions.attachMenu.isNotEmpty()
                    Box {
                        ComposerField(
                            state = composer,
                            onSend = actions.onSend,
                            modifier =
                                if (attach) {
                                    Modifier.padding(start = MediaMetrics.AttachButton + Spacing.xs)
                                } else {
                                    Modifier
                                },
                            fieldModifier = Modifier.testTag(ConversationTags.Composer).then(composerModifier),
                            placeholder = mode.placeholder,
                            sendLabel = sendLabel,
                        )
                        if (attach) {
                            AttachButton(
                                actions.attachMenu,
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .navigationBarsPadding()
                                    .imeOverlapPadding(excludeNavigationBar = false)
                                    .padding(start = Spacing.gutter, bottom = Spacing.s + MediaMetrics.AttachLift),
                            )
                        }
                    }
                }

                ComposerMode.NeedsRole -> {
                    DefaultSmsCard(
                        onResult = actions.onRoleResult,
                        Modifier.navigationBarsPadding().padding(bottom = Spacing.s),
                    )
                }

                is ComposerMode.ReadOnly -> {
                    RostyText(
                        mode.reason,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.l),
                        style = RostyTheme.type.footnote,
                        color = RostyTheme.colors.labelSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun subtitleRoom(state: ConversationUi) =
    if (state.subtitle != null || state.notice != null || state.error != null) Spacing.xl else MessagesMetrics.None

@Composable
private fun CenteredLabel(
    text: String,
    accent: Boolean = false,
) {
    RostyText(
        text,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s),
        style = RostyTheme.type.caption1,
        color = if (accent) RostyTheme.colors.accent else RostyTheme.colors.labelSecondary,
        textAlign = TextAlign.Center,
    )
}

/** The fill of a bubble: mine in the accent (SMS) or WhatsApp-like green tone is left to the theme's accent. */
@Composable
private fun bubbleColors(outgoing: Boolean): Pair<Color, Color> {
    val colors = RostyTheme.colors
    return if (outgoing) colors.accent to colors.onAccent else colors.fillSecondary to colors.labelPrimary
}

/**
 * The round button that brings the newest message back (M12), shown once the list is scrolled away from the end; its
 * badge counts what arrived meanwhile ("99+" above 99).
 */
@Composable
private fun JumpToLatest(
    listState: LazyListState,
    state: ConversationUi,
    labels: ToolLabels,
    modifier: Modifier = Modifier,
) {
    val away by remember { derivedStateOf { listState.firstVisibleItemIndex > JUMP_AFTER_ITEMS } }
    var seenNewest by remember { mutableStateOf<String?>(null) }
    val keys = state.items.mapNotNull { (it as? ConversationItem.Bubble)?.key }
    LaunchedEffect(keys.firstOrNull(), away) {
        if (!away || seenNewest == null) seenNewest = keys.firstOrNull()
    }
    // Every bubble above the newest one seen at the end arrived while the list was scrolled away.
    val arrived = seenNewest?.let { keys.indexOf(it) }?.coerceAtLeast(0) ?: 0
    if (!away) return
    val scope = rememberCoroutineScope()
    Box(modifier.padding(Spacing.gutter)) {
        GlassIconButton(
            RostyIcons.ArrowDown,
            labels.jumpLatest,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier.testTag(ConversationTags.JumpLatest),
        )
        if (arrived > 0) {
            // Above the glass button, which draws in its own layer.
            Box(Modifier.align(Alignment.TopEnd).zIndex(1f)) {
                dev.rosty.messages.inbox
                    .UnreadBadge(minOf(arrived, JUMP_CAP), quiet = false)
            }
        }
    }
}

/** The find bar (M5): the field, "2 of 5", older/newer (wrapping) and Done. */
@Composable
private fun FindBar(
    find: FindUi,
    labels: ToolLabels,
    actions: ConversationActions,
    modifier: Modifier = Modifier,
) {
    val field = rememberTextFieldState(find.query)
    val latest by rememberUpdatedState(actions.onFindQuery)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { latest(it) } }
    Row(
        modifier
            .padding(top = topBarContentPadding())
            .fillMaxWidth()
            .background(RostyTheme.colors.page)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .testTag(ConversationTags.Find),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SearchField(field, Modifier.weight(1f), placeholder = labels.find)
        RostyText(
            when {
                find.query.isBlank() -> ""
                find.count == 0 -> labels.noMatches
                else -> labels.position((find.position ?: 0) + 1, find.count)
            },
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
            maxLines = 1,
            modifier = Modifier.testTag(ConversationTags.FindLabel),
        )
        GlassIconButton(RostyIcons.ArrowUp, labels.older, onClick = actions.onFindOlder, enabled = find.count > 0)
        GlassIconButton(RostyIcons.ArrowDown, labels.newer, onClick = actions.onFindNewer, enabled = find.count > 0)
        GlassIconButton(RostyIcons.Xmark, labels.close, onClick = actions.onFindClose)
    }
}

@Composable
private fun BubbleRow(
    bubble: BubbleUi,
    actions: ConversationActions,
    glow: Boolean = false,
    voice: VoiceUi? = null,
    speedLabel: String = "",
) {
    val overlays = LocalOverlayOrNull.current
    val maxWidth = (LocalConfiguration.current.screenWidthDp * MessagesMetrics.BubbleMaxFraction).dp
    val (fill, ink) = bubbleColors(bubble.outgoing)
    val menu = remember(bubble, actions) { actions.menuFor(bubble) }
    val tap = remember(bubble, actions) { { actions.onBubbleTap(bubble) } }
    val swipe = remember { ReplySwipeState() }
    val onSwipe = actions.onSwipeReply
    val swipes = bubble.canReply && onSwipe != null
    Box(Modifier.fillMaxWidth().swipeToReply(swipe, swipes) { onSwipe?.invoke(bubble) }) {
        if (swipes) {
            ReplyGlyph(swipe.offset, Modifier.align(Alignment.CenterStart).padding(start = Spacing.gutter))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipe.offset.value.roundToInt(), 0) }
                .padding(
                    start = Spacing.gutter,
                    end = Spacing.gutter,
                    top = if (bubble.firstOfRun) MessagesMetrics.BubbleGroupGap else MessagesMetrics.BubbleGap,
                ).testTag(ConversationTags.bubble(bubble.key)),
            horizontalAlignment = if (bubble.outgoing) Alignment.End else Alignment.Start,
        ) {
            if (bubble.sender != null && bubble.firstOfRun) {
                val card = actions.senderCard
                val opensCard =
                    if (card != null && bubble.senderId != null && overlays != null) {
                        Modifier.popoverTrigger(overlays, stringResource(R.string.wa_sender_card, bubble.sender)) {
                            card(bubble)
                        }
                    } else {
                        Modifier
                    }
                RostyText(
                    bubble.sender.rtlSafe(),
                    modifier =
                        opensCard
                            .padding(start = MessagesMetrics.BubblePadH, bottom = Spacing.textGap)
                            .testTag(ConversationTags.sender(bubble.key)),
                    style = RostyTheme.type.caption1,
                    color = RostyTheme.colors.accent,
                    maxLines = 1,
                )
            }
            val press =
                if (overlays != null && menu.isNotEmpty()) {
                    Modifier.longPressMenu(
                        overlays,
                        menu,
                        ContextMenuStyle.Lifted(preview = {
                            BubbleBody(bubble, fill, ink, maxWidth, actions, voice, speedLabel)
                        }),
                        onTap = tap,
                    )
                } else {
                    Modifier.clickable(onClick = tap)
                }
            val glowAlpha by animateFloatAsState(if (glow) GLOW_ALPHA else 0f, label = "glow")
            Box(
                press
                    .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
                    .drawBehind { drawRect(GlowColor.copy(alpha = glowAlpha)) }
                    .padding(MessagesMetrics.GlowPad),
            ) { BubbleBody(bubble, fill, ink, maxWidth, actions, voice, speedLabel) }
            bubble.reactions?.let {
                RostyText(
                    it,
                    modifier = Modifier.padding(horizontal = MessagesMetrics.BubblePadH),
                    style = RostyTheme.type.footnote,
                )
            }
        }
    }
}

@Composable
private fun BubbleBody(
    bubble: BubbleUi,
    fill: Color,
    ink: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
    actions: ConversationActions,
    voice: VoiceUi? = null,
    speedLabel: String = "",
) {
    val corner = MessagesMetrics.BubbleCorner
    val tail = MessagesMetrics.BubbleTail
    val shape =
        if (bubble.outgoing) {
            RoundedCornerShape(corner, corner, if (bubble.firstOfRun) tail else corner, corner)
        } else {
            RoundedCornerShape(corner, corner, corner, if (bubble.firstOfRun) tail else corner)
        }
    val failed = bubble.state == BubbleState.Failed
    val secondary = if (bubble.outgoing) ink.copy(alpha = META_ALPHA) else RostyTheme.colors.labelSecondary
    Column(
        Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(if (bubble.deleted) RostyTheme.colors.fillTertiary else fill)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    listOf(bubble.text, bubble.meta).joinToString(", ")
            }.padding(horizontal = MessagesMetrics.BubblePadH, vertical = MessagesMetrics.BubblePadV),
    ) {
        if (bubble.forwarded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RostyIcon(RostyIcons.Forward, null, tint = secondary, size = MessagesMetrics.TickGlyph)
                RostyText(
                    stringResource(R.string.conversation_forwarded),
                    modifier = Modifier.padding(start = Spacing.xs),
                    style = RostyTheme.type.caption1.copy(fontStyle = FontStyle.Italic),
                    color = secondary,
                )
            }
        }
        bubble.quote?.let { q ->
            Column(
                Modifier
                    .padding(bottom = Spacing.xs)
                    .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner))
                    .background(secondary.copy(alpha = QUOTE_ALPHA))
                    .padding(Spacing.s),
            ) {
                RostyText(
                    q.author,
                    style = RostyTheme.type.caption1,
                    color = if (bubble.outgoing) ink else RostyTheme.colors.accent,
                    maxLines = 1,
                )
                RostyText(q.text, style = RostyTheme.type.footnote, color = secondary, maxLines = 2)
            }
        }
        for (media in bubble.media) {
            if (media.kind != null) {
                BubbleMediaView(media, MediaHost(bubble, ink, secondary, maxWidth, actions, voice, speedLabel))
            } else if (media.isImage && media.model != null) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                SizedImage(
                    model = media.model,
                    widthPx = widthPx,
                    heightPx = (widthPx / (media.aspect ?: DEFAULT_ASPECT)).toInt(),
                    contentScale = ContentScale.Crop,
                    contentDescription = media.label,
                    modifier =
                        Modifier
                            .padding(bottom = Spacing.xs)
                            .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner))
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                            .aspectRatio(media.aspect ?: DEFAULT_ASPECT),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.xs)) {
                    RostyIcon(RostyIcons.Document, null, tint = ink, size = MessagesMetrics.FolderGlyph)
                    RostyText(
                        media.label,
                        modifier = Modifier.padding(start = Spacing.xs),
                        style = RostyTheme.type.subheadline,
                        color = ink,
                    )
                }
            }
        }
        if (bubble.text.isNotEmpty()) {
            RostyText(
                bubble.text.rtlSafe(),
                style =
                    if (bubble.deleted) {
                        RostyTheme.type.body.copy(
                            fontStyle = FontStyle.Italic,
                        )
                    } else {
                        RostyTheme.type.body
                    },
                color = if (bubble.deleted) RostyTheme.colors.labelSecondary else ink,
            )
        }
        Row(
            Modifier.align(Alignment.End).padding(top = Spacing.textGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            RostyText(
                bubble.meta,
                style = RostyTheme.type.caption2,
                // On my blue bubble red text would not read: the words stay in the ink, the glyph is red.
                color = if (failed && !bubble.outgoing) RostyTheme.colors.destructive else secondary,
                maxLines = 1,
            )
            if (bubble.outgoing) StateGlyph(bubble.state, secondary, read = ink)
        }
    }
}

/** The clock of a waiting message, one tick sent, two delivered, two blue read (GPUI `ticks.rs`). */
@Composable
private fun StateGlyph(
    state: BubbleState,
    tint: Color,
    /** Read ticks: the bubble's own text colour at full strength (teal did not read on the accent bubble). */
    read: Color,
) {
    when (state) {
        BubbleState.Waiting, BubbleState.Sending -> {
            RostyIcon(
                RostyIcons.Clock,
                null,
                tint = tint,
                size = MessagesMetrics.TickGlyph,
            )
        }

        BubbleState.Sent -> {
            RostyIcon(RostyIcons.Checkmark, null, tint = tint, size = MessagesMetrics.TickGlyph)
        }

        BubbleState.Delivered -> {
            DoubleTick(tint)
        }

        BubbleState.Read, BubbleState.Played -> {
            DoubleTick(read)
        }

        BubbleState.Failed -> {
            // A red mark on a white disc, so it reads on my blue bubble as well as on grey.
            Box(Modifier.clip(CircleShape).background(Color.White)) {
                RostyIcon(
                    RostyIcons.Info,
                    null,
                    tint = RostyTheme.colors.destructive,
                    size = MessagesMetrics.TickGlyph,
                )
            }
        }

        BubbleState.None -> {
            Unit
        }
    }
}

@Composable
private fun DoubleTick(tint: Color) {
    Box {
        RostyIcon(RostyIcons.Checkmark, null, tint = tint, size = MessagesMetrics.TickGlyph)
        RostyIcon(
            RostyIcons.Checkmark,
            null,
            tint = tint,
            size = MessagesMetrics.TickGlyph,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}

private const val META_ALPHA = 0.75f
private const val GLOW_ALPHA = 0.45f
private const val NOTICE_ALPHA = 0.9f
private val GlowColor = Color(0xFFFFD60A)
private const val JUMP_AFTER_ITEMS = 2
private const val JUMP_CAP = 99
private const val QUOTE_ALPHA = 0.18f
private const val DEFAULT_ASPECT = 4f / 3f
