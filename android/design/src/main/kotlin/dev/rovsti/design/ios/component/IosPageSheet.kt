/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/IosPageSheet.kt
 * Changes for Rovsti: package and imports; the `Dialog` window becomes a modal [OverlayEntry] presented through the
 * OverlayController (a separate window could not share the app's z-order, scrim and Back owner); `BackHandler` is the
 * host's Back (a tween(220) close); SymbolIcon(SFXmark) is RovstiIcon(Xmark); stringResource(R.string.close) is a
 * literal; the full-bleed scrim, 38 dp continuous corners, 10 dp under the status bar, 44 dp close button, critically
 * damped rise (1 / 380) and the 220 ms tween out are as Convx wrote them.
 */
package dev.rovsti.design.ios.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.R
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.LocalIosTextStyle
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayEntry
import dev.rovsti.design.overlay.OverlayScope
import dev.rovsti.design.overlay.OverlayScrim
import dev.rovsti.design.shapes.ContinuousRoundedRectangle

/** Scrim strength of the page sheet at rest (Convx IosPageSheet.kt: `0.4f * (1f - offset)`). */
const val PageSheetScrimAlpha = 0.4f

/** The page sheet's top edge sits this far under the status bar (Convx IosPageSheet.kt `statusTop + 10.dp`). */
val PageSheetTopGap = 10.dp

/** Duration of the page sheet's close tween (Convx IosPageSheet.kt `tween(220)`). */
const val PageSheetCloseMillis = 220

/**
 * The full-screen iOS page sheet the New Playlist sheet uses in Convx: edge to edge, just under the status bar, a
 * close button and a centred title, rising on a critically damped spring. A modal overlay entry: present it with
 * [OverlayController.present] (or [presentPageSheet]).
 */
class IosPageSheetEntry(
    title: String,
    private val onDismissed: () -> Unit = {},
    private val content: @Composable ColumnScope.() -> Unit,
) : OverlayEntry(modal = true, title = title) {
    private var gone by mutableStateOf(false)

    /** The sheet's travel, 1 = off the bottom, 0 = at rest. For tests. */
    val offset = Animatable(1f)

    override val isRemovable: Boolean get() = !state.targetState && gone

    @Composable
    override fun Content(host: OverlayScope) {
        val colors = IosTheme.colors
        val focus = LocalFocusManager.current
        LaunchedEffect(Unit) { offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f)) }
        val open = state.targetState
        LaunchedEffect(open) {
            if (!open) {
                focus.clearFocus()
                offset.animateTo(1f, tween(PageSheetCloseMillis))
                gone = true
                onDismissed()
            }
        }

        fun close() = host.controller.dismiss(this@IosPageSheetEntry)
        val statusTop = with(host.density) { host.safe.top.toDp() }
        val sheetColor = if (colors.isDark) colors.secondarySystemBackground else colors.systemGroupedBackground
        Box(Modifier.fillMaxSize().testTag("page-sheet")) {
            OverlayScrim(
                progress = { 1f - offset.value },
                lightAlpha = PageSheetScrimAlpha,
                darkAlpha = PageSheetScrimAlpha,
                onTap = ::close,
            )
            val travel = host.windowSize.height
            Column(
                Modifier
                    .padding(top = statusTop + PageSheetTopGap)
                    .fillMaxSize()
                    .graphicsLayer { translationY = offset.value * travel }
                    .clip(
                        ContinuousRoundedRectangle(
                            topStart = 38.dp,
                            topEnd = 38.dp,
                            bottomEnd = 0.dp,
                            bottomStart = 0.dp,
                        ),
                    ).background(sheetColor),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
                        .height(44.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(colors.tertiarySystemFill)
                                .clickable(role = Role.Button, onClick = ::close)
                                .testTag("page-sheet-close"),
                    ) {
                        RovstiIcon(RovstiIcons.Xmark, contentDescription = "Close", size = 16.dp, tint = colors.label)
                    }
                    IosText(
                        text = title,
                        style = LocalIosTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.label,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), content = content)
            }
        }
    }
}

/** Presents an [IosPageSheetEntry]. */
fun OverlayController.presentPageSheet(
    title: String,
    onDismissed: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
): IosPageSheetEntry = IosPageSheetEntry(title, onDismissed, content).also { present(it) }
