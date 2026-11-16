package dev.rosty.contacts.cards

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.rosty.contacts.R
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardTemplate
import dev.rosty.design.controls.SegmentedControl
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.ios.component.IosButton
import dev.rosty.design.ios.component.IosButtonSizing
import dev.rosty.design.ios.component.IosButtonStyle
import dev.rosty.design.ios.component.IosToggle
import dev.rosty.design.motion.RostyMotion
import dev.rosty.design.overlay.ButtonMenuEntry
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.resolve
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fixed numbers of the sheet; the preview's aspect is the same for every template, so nothing jumps on a switch. */
internal object CardStudioMetrics {
    /** Width over height of the preview box (a 4:5 card). */
    const val Aspect = 0.8f
    val Swatch = Sizes.swatch
    val Ring = Sizes.ring
    val RingStroke = Sizes.ringStroke
    val PreviewRadius = RostyShapes.popoverMin
}

/**
 * Opens Card Studio in a medium sheet over the page. Closing the sheet (drag, scrim, back) cancels the running render
 * through [CardStudioViewModel.close]; back closes the sheet before it reaches the page behind.
 */
fun OverlayController.presentCardStudio(
    context: Context,
    model: CardStudioViewModel,
    onShare: (CardStudioEffect.Share) -> Unit = {},
): SheetEntry {
    val controller = this
    val entry =
        SheetEntry(
            title = context.getString(R.string.card_studio_title),
            detents = persistentListOf(Detent.Medium),
            onDismissed = model::close,
        ) { _ ->
            val state by model.state.collectAsState()
            val haptics = LocalRostyHaptics.current
            LaunchedEffect(model) {
                model.effects.collect { effect ->
                    when (effect) {
                        is CardStudioEffect.Share -> onShare(effect)
                        is CardStudioEffect.Toast -> controller.showToast(effect.text.resolve(context))
                        CardStudioEffect.Reject -> haptics.reject()
                    }
                }
            }
            CardStudioContent(state, model::onAction)
        }
    present(entry)
    return entry
}

/** The sheet body: template picker, the Animated switch, preview, one row of colours, then Share and Save. */
@Composable
internal fun ColumnScope.CardStudioContent(
    state: CardStudioState,
    onAction: (CardStudioAction) -> Unit,
) {
    val templates = remember(state.subject) { state.subject.templates }
    val names = templates.map { templateName(it) }.toImmutableList()
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.m)
            .testTag(CardStudioTags.Sheet),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        SegmentedControl(
            items = names,
            selected = templates.indexOf(state.template).coerceAtLeast(0),
            onSelect = { onAction(CardStudioAction.PickTemplate(templates[it])) },
            modifier = Modifier.fillMaxWidth().testTag(CardStudioTags.Templates),
        )
        // Above the preview, not beside the buttons: the "..." menu opens upward from the button row and must never
        // cover the switch.
        IosToggle(
            checked = state.animated && state.canAnimate,
            onCheckedChange = { onAction(CardStudioAction.SetAnimated(it)) },
            modifier = Modifier.testTag(CardStudioTags.Animated),
            label = stringResource(R.string.card_studio_animated),
            enabled = state.canAnimate,
        )
        Preview(state, Modifier.weight(1f).fillMaxWidth())
        Swatches(state.palette) { onAction(CardStudioAction.PickPalette(it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            IosButton(
                title = stringResource(R.string.card_studio_save),
                onClick = { onAction(CardStudioAction.Save) },
                modifier = Modifier.weight(1f).testTag(CardStudioTags.Save),
                style = IosButtonStyle.Bordered,
                sizing = IosButtonSizing.Flexible,
                enabled = state.ready,
            )
            IosButton(
                title = stringResource(R.string.card_studio_share),
                onClick = { onAction(CardStudioAction.Share) },
                modifier = Modifier.weight(1f).testTag(CardStudioTags.Share),
                style = IosButtonStyle.BorderedProminent,
                sizing = IosButtonSizing.Flexible,
                enabled = state.ready,
            )
            if (state.preview?.animated == true) ShareMore(state.ready, onAction)
        }
    }
}

/** The "..." button beside Share of an animated card: its menu offers "Share as still image". */
@Composable
private fun ShareMore(
    enabled: Boolean,
    onAction: (CardStudioAction) -> Unit,
) {
    val overlays = LocalOverlayOrNull.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val label = stringResource(R.string.card_studio_share_still)
    val saveLabel = stringResource(R.string.card_studio_save_still)
    val items =
        remember(label, saveLabel) {
            persistentListOf<MenuItem>(
                MenuItem.Action(label = label, onClick = { onAction(CardStudioAction.ShareAsStill) }),
                MenuItem.Action(label = saveLabel, onClick = { onAction(CardStudioAction.SaveAsStill) }),
            )
        }
    IosButton(
        title = "•••",
        onClick = { overlays?.present(ButtonMenuEntry(anchor, items)) },
        modifier =
            Modifier
                .onGloballyPositioned { anchor = it.boundsInWindow() }
                .semantics { contentDescription = label }
                .testTag(CardStudioTags.ShareMore),
        style = IosButtonStyle.Bordered,
        enabled = enabled,
    )
}

@Composable
private fun templateName(template: CardTemplate): String =
    stringResource(
        when (template) {
            CardTemplate.Birthday -> R.string.card_template_birthday
            CardTemplate.Contact -> R.string.card_template_contact
            CardTemplate.NewNumber -> R.string.card_template_number
        },
    )

@Composable
private fun paletteName(palette: CardPalette): String =
    stringResource(
        when (palette) {
            CardPalette.Dawn -> R.string.card_palette_dawn
            CardPalette.Lagoon -> R.string.card_palette_lagoon
            CardPalette.Meadow -> R.string.card_palette_meadow
            CardPalette.Dusk -> R.string.card_palette_dusk
            CardPalette.Ink -> R.string.card_palette_ink
        },
    )

/**
 * The picture, centred in the room the controls leave. Its box has the template-independent aspect, so a new image
 * never changes the layout; the finished picture crossfades in with the `appear` spring and the old one stays under a
 * small ring while the next render runs.
 */
@Composable
private fun Preview(
    state: CardStudioState,
    modifier: Modifier,
) {
    val description =
        stringResource(
            when (state.template) {
                CardTemplate.Birthday -> R.string.card_studio_preview_birthday
                CardTemplate.Contact -> R.string.card_studio_preview_contact
                CardTemplate.NewNumber -> R.string.card_studio_preview_number
            },
            state.subject.name,
        )
    val rendering = stringResource(R.string.card_studio_rendering)
    val colors = RostyTheme.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(CardStudioMetrics.Aspect, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(CardStudioMetrics.PreviewRadius))
                .background(colors.fillTertiary)
                .semantics { contentDescription = if (state.rendering) "$description, $rendering" else description }
                .testTag(CardStudioTags.Preview),
        ) {
            Crossfade(
                targetState = state.preview?.path,
                animationSpec = RostyMotion.appear(1f),
                modifier = Modifier.fillMaxSize(),
                label = "card-preview",
            ) { path ->
                val image by produceState<ImageBitmap?>(null, path) {
                    value =
                        path
                            ?.let {
                                withContext(
                                    Dispatchers.IO,
                                ) { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                            }?.asImageBitmap()
                }
                image?.let {
                    Image(
                        it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            if (state.rendering) {
                ProgressRing(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.s)
                        .testTag(CardStudioTags.Ring),
                )
            }
        }
    }
}

/** A turning arc on a soft disc: the render is running. */
@Composable
private fun ProgressRing(modifier: Modifier) {
    val turn by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RING_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "ring-turn",
    )
    val colors = RostyTheme.colors
    val track = colors.labelPrimary.copy(alpha = 0.18f)
    val arc = colors.labelPrimary
    Box(
        modifier
            .size(CardStudioMetrics.Ring + Spacing.s)
            .background(colors.page.copy(alpha = 0.72f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(CardStudioMetrics.Ring)) {
            val stroke = Stroke(CardStudioMetrics.RingStroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val box = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = stroke)
            drawArc(arc, turn - 90f, RING_SWEEP, false, Offset(inset, inset), box, style = stroke)
        }
    }
}

/** One non-wrapping row of glass colour swatches; the chosen one wears a ring. They are dp-sized, so text scale never wraps them. */
@Composable
private fun Swatches(
    selected: CardPalette,
    onPick: (CardPalette) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag(CardStudioTags.Swatches),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (palette in StudioPalettes) {
            Swatch(palette, palette == selected, paletteName(palette)) { onPick(palette) }
        }
    }
}

@Composable
private fun Swatch(
    palette: CardPalette,
    chosen: Boolean,
    name: String,
    onClick: () -> Unit,
) {
    val colors = RostyTheme.colors
    val disc = Color(palette.swatchTop) to Color(palette.swatchBottom)
    Box(
        Modifier
            .size(CardStudioMetrics.Swatch)
            .testTag(CardStudioTags.swatch(palette))
            .semantics {
                contentDescription = name
                role = Role.RadioButton
                selected = chosen
            }.clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (chosen) CardStudioMetrics.Swatch else SwatchRest)
                .border(
                    if (chosen) Sizes.selectionBorder else ZeroDp,
                    if (chosen) colors.accent else Color.Transparent,
                    CircleShape,
                ).padding(if (chosen) Sizes.ringStroke else ZeroDp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(disc.first, disc.second)))
                .border(Sizes.hairline, Color.White.copy(alpha = 0.45f), CircleShape),
        )
    }
}

private val SwatchRest: Dp = Sizes.chip
private val ZeroDp: Dp = Sizes.hairline - Sizes.hairline
private const val RING_MS = 900
private const val RING_SWEEP = 100f
