package dev.rovsti.phone.incall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.press.glassPress
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.InCallMetrics
import dev.rovsti.phone.keypad.KeyRows

/** How many of the sent tones the digit line shows (the rest is cut at the start). */
private const val SHOWN_DIGITS = 18

/**
 * The in-call keypad: the tones sent so far (touch and hold copies them, U50), the twelve keys (a tone sounds while a key
 * is down), the Paste-as-tones button that becomes Stop while it plays (U49), the tones remembered for this number
 * (U51) and Hide.
 */
@Composable
internal fun TonePanel(
    ui: InCallUi,
    strings: InCallStrings,
    onAction: (InCallAction) -> Unit,
) {
    val pad = ui.keypad
    val latest by rememberUpdatedState(onAction)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DigitLine(pad.digits, strings) { latest(InCallAction.CopyDigits) }
        Column(verticalArrangement = Arrangement.spacedBy(InCallMetrics.KeyGap)) {
            for (row in KeyRows) {
                Row(horizontalArrangement = Arrangement.spacedBy(InCallMetrics.KeyGap)) {
                    for ((char, letters) in row) {
                        ToneKey(
                            char,
                            letters,
                            onDown = { latest(InCallAction.Digit(it)) },
                            onUp = { latest(InCallAction.DigitUp) },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.padding(top = Spacing.m).height(Sizes.touch),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pad.sending) {
                PillButton(strings.stopTones, InCallTags.Paste) { latest(InCallAction.StopTones) }
            } else {
                pad.remembered?.let {
                    PillButton(
                        strings.sendTones(it),
                        InCallTags.Remembered,
                    ) { latest(InCallAction.SendRemembered) }
                }
                pad.pasteLabel?.let { PillButton(it, InCallTags.Paste) { latest(InCallAction.PasteTones) } }
            }
            PillButton(strings.hideKeypad, InCallTags.Hide) { latest(InCallAction.ToggleKeypad) }
        }
    }
}

@Composable
private fun DigitLine(
    digits: String,
    strings: InCallStrings,
    onCopy: () -> Unit,
) {
    Box(
        Modifier
            .height(InCallMetrics.DigitLine)
            .sizeIn(minWidth = Sizes.touch)
            .testTag(InCallTags.Digits)
            .pointerInput(digits) { detectTapGestures(onLongPress = { onCopy() }) }
            .semantics {
                contentDescription = digits.ifEmpty { strings.digitsHint }
                onLongClick(label = strings.digitsHint) {
                    onCopy()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(
            digits.takeLast(SHOWN_DIGITS),
            style = RovstiTheme.type.title1.copy(fontWeight = FontWeight.Normal),
            color = OnAura,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** A round key: [onDown] the moment the finger lands (the tone starts), [onUp] when it lifts or is taken away. */
@Composable
private fun ToneKey(
    char: Char,
    letters: String,
    onDown: (Char) -> Unit,
    onUp: () -> Unit,
) {
    val describe = if (letters.isEmpty() || !letters[0].isLetter()) char.toString() else "$char $letters"
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    Box(
        Modifier
            .size(InCallMetrics.Key)
            .glassPress()
            .clip(CircleShape)
            .background(KeyFill)
            .pointerInput(char) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down(char)
                    waitForUpOrCancellation()
                    up()
                }
            }.semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = describe
                onClick {
                    down(char)
                    up()
                    true
                }
            }.testTag(InCallTags.key(char)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RovstiText(
                char.toString(),
                style = RovstiTheme.type.title1.copy(fontWeight = FontWeight.Normal),
                color = OnAura,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (letters.isNotEmpty() && letters[0].isLetter()) {
                RovstiText(
                    letters,
                    style = RovstiTheme.type.caption2.copy(fontWeight = FontWeight.SemiBold),
                    color = OnAuraSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A text capsule on the aura: white on a translucent white fill. */
@Composable
internal fun PillButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .testTag(tag)
            .sizeIn(minHeight = Sizes.touch)
            .clip(CircleShape)
            .background(PillFill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.l),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(
            text,
            style = RovstiTheme.type.callout,
            color = OnAura,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val KeyFill = Color.White.copy(alpha = 0.16f)
private val PillFill = Color.White.copy(alpha = 0.20f)
