package dev.rovsti.phone.incall

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.avatarColor
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.DECLINE_RED
import dev.rovsti.phone.InCallMetrics
import dev.rovsti.phone.incoming.AuraMode

/** The In-Call page over its model: the sheets it opens come from [sheets], the timer ticks from the model's clock. */
@Composable
fun InCallScreen(
    model: InCallViewModel,
    sheets: InCallSheets,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    aura: AuraMode = AuraMode(),
) {
    val ui by model.state.collectAsState()
    val strings = InCallStrings.current()
    val timer = rememberCallTimer(ui.call, model::now, model::elapsed)
    InCallContent(
        ui = ui,
        strings = strings,
        timer = timer,
        onAction = model::onAction,
        onAudio = { anchor -> sheets.routes(anchor, model.state) },
        onTimerMenu = { anchor ->
            val call = ui.call
            val talkS = ((model.now() - (call?.connectTimeMs ?: 0L)) / MS_PER_S).coerceAtLeast(0)
            val summary =
                strings.summary(
                    call?.title.orEmpty(),
                    EndedUi(call?.callId ?: 0, true, talkS, timer.value),
                    named = call != null && call.title != call.number,
                )
            sheets.timerMenu(anchor, timer.value, summary)
        },
        onCollapse = onCollapse,
        modifier = modifier,
        aura = aura,
    )
}

/**
 * The mini pill over the app while a call is live and the In-Call page is not showing: the caller, the timer and a red
 * End circle. [visible] false slides it away. Its progress is "how far it has grown into the full screen" (0 = the
 * pill); [CallSheetMotion] decides when its content is gone and when it takes touches. Tapping it opens the call page.
 */
@Composable
fun MiniCallPill(
    ui: InCallUi,
    strings: InCallStrings,
    timer: State<String>,
    visible: Boolean,
    onExpand: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val call = ui.call ?: return
    val reduce = LocalReduceMotion.current
    val expand by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec =
            if (reduce) {
                androidx.compose.animation.core
                    .snap()
            } else {
                RovstiMotion.sheetSettle(0.001f)
            },
        label = "mini-call",
    )
    if (expand >= HIDDEN) return
    val status =
        when {
            ui.ended != null -> strings.ended
            call.phase == CallPhase.Holding -> strings.held
            call.connectTimeMs <= 0 -> strings.calling
            else -> null
        }
    val owns = CallSheetMotion.miniOwnsInput(expand)
    val fill =
        avatarColor(call.avatarKey).let {
            Color(
                red = it.red * FILL_DARKEN,
                green = it.green * FILL_DARKEN,
                blue =
                    it.blue * FILL_DARKEN,
            )
        }
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(top = InCallMetrics.PillTop, start = InCallMetrics.PillSide, end = InCallMetrics.PillSide)
            .fillMaxWidth()
            .height(InCallMetrics.PillHeight)
            .graphicsLayer {
                alpha = CallSheetMotion.miniAlpha(expand)
                translationY = -expand * (size.height + InCallMetrics.PillTop.toPx() * 2)
            }.clip(CircleShape)
            .background(fill)
            .testTag(InCallTags.Mini)
            .then(
                if (owns) {
                    Modifier.clickable(role = Role.Button, onClickLabel = strings.expand, onClick = onExpand)
                } else {
                    Modifier
                },
            ).padding(horizontal = Spacing.m)
            .semantics(
                mergeDescendants = false,
            ) { contentDescription = strings.mini(call.title, status ?: timer.value) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Avatar(name = call.title, size = InCallMetrics.PillAvatar, colorKey = call.avatarKey)
        Column(Modifier.weight(1f)) {
            RovstiText(
                call.title,
                style = RovstiTheme.type.headline,
                color = OnAura,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                RovstiText(status, style = RovstiTheme.type.footnote, color = OnAuraSecondary, maxLines = 1)
            } else {
                RovstiText(
                    timer.value,
                    style = RovstiTheme.type.footnote.copy(fontFeatureSettings = "tnum"),
                    color = OnAuraSecondary,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .testTag(InCallTags.MiniEnd)
                .size(InCallMetrics.PillEnd)
                .clip(CircleShape)
                .background(Color(DECLINE_RED))
                .then(
                    if (owns &&
                        ui.live
                    ) {
                        Modifier.clickable(role = Role.Button, onClickLabel = strings.end, onClick = onEnd)
                    } else {
                        Modifier
                    },
                ).semantics { contentDescription = strings.end },
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(
                RovstiIcons.Phone,
                null,
                modifier = Modifier.rotate(HANG_UP),
                tint = Color.White,
                size = InCallMetrics.TopIcon,
            )
        }
    }
}

/** The pill is gone (and leaves the tree) once its spring is this close to the end. */
private const val HIDDEN = 0.995f
private const val MS_PER_S = 1000L
private const val HANG_UP = 135f
private const val FILL_DARKEN = 0.55f
