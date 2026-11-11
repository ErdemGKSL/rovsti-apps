package dev.rovsti.phone.incoming

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * The caller's name on the Incoming, In-call and WhatsApp call screens (F25): iOS 26 draws it thin, so the answer morph
 * does not jump weight. Light 36 sp, centred, two lines, then it shrinks to fit (down to 22 sp) instead of cutting.
 * With [compact] (the in-call keypad is open) it is one title2-sized Light 22 sp line with an ellipsis.
 */
@Composable
fun CallNameText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    compact: Boolean = false,
) {
    if (compact) {
        BasicText(
            text,
            modifier = modifier,
            style = CompactStyle.merge(TextStyle(color = color, textAlign = TextAlign.Center)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        BasicText(
            text,
            modifier = modifier,
            style = NameStyle.merge(TextStyle(color = color, textAlign = TextAlign.Center)),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = NameMinSize, maxFontSize = NameSize, stepSize = 1.sp),
        )
    }
}

private val NameSize = 36.sp
private val NameMinSize = 22.sp
private val NameStyle = TextStyle(fontSize = NameSize, fontWeight = FontWeight.Light, letterSpacing = (-0.4).sp)
private val CompactStyle =
    TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.3).sp)
