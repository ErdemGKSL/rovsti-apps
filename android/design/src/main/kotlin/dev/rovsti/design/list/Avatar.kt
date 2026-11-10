package dev.rovsti.design.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.media.RowImage
import dev.rovsti.design.theme.Sizes
import kotlinx.collections.immutable.ImmutableList

/**
 * Eight avatar backgrounds, each at least 4.5:1 against white initials (`AvatarColorTest` measures every entry). The
 * same palette is used in light and dark: white text on a saturated mid-dark colour reads in both.
 */
internal val AvatarPalette: List<Color> =
    listOf(
        Color(0xFF0A5FC4),
        Color(0xFF5B49C8),
        Color(0xFF8A3AAE),
        Color(0xFFB8265B),
        Color(0xFFBD2F29),
        Color(0xFFA64D07),
        Color(0xFF1C7436),
        Color(0xFF0B7480),
    )

/** Stable colour for [key] (a contact id or name): FNV-1a over the trimmed, lower-cased key picks a palette entry. */
fun avatarColor(key: String): Color {
    var hash = FNV_OFFSET
    for (ch in key.trim().lowercase()) {
        hash = (hash xor ch.code) * FNV_PRIME
    }
    return AvatarPalette[(hash and Int.MAX_VALUE) % AvatarPalette.size]
}

/**
 * Up to two capital letters: the first letter of the first and of the last word. Empty when the name does not start
 * with a letter (a bare phone number), so the caller draws a person glyph instead.
 */
fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty() || !Character.isLetter(words.first().codePointAt(0))) return ""

    fun first(word: String): String = String(Character.toChars(word.codePointAt(0))).uppercase()
    val last = words.last()
    return if (words.size == 1 ||
        !Character.isLetter(last.codePointAt(0))
    ) {
        first(words.first())
    } else {
        first(words.first()) + first(last)
    }
}

private val AuraFill = Color.White.copy(alpha = 0.22f)
private val AuraRim = Color.White.copy(alpha = 0.40f)
private val AuraRimWidth = 0.5.dp
private const val FNV_OFFSET = -2128831035
private const val FNV_PRIME = 16777619
private const val INITIALS_RATIO = 0.4f
private const val SPLIT_RATIO = 0.34f
private const val GLYPH_RATIO = 0.55f
private const val CAP_HEIGHT = 0.72f

/**
 * A round avatar: a [photo] or [photoModel], or the initials of [name] on [avatarColor], or a person glyph when there are none. The
 * initials are measured once in `drawWithCache` (the layout is rebuilt only when the size or text changes) and the
 * text size follows the avatar's pixels, not the font scale. Decorative unless [contentDescription] is given.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatar,
    photo: ImageBitmap? = null,
    colorKey: String = name,
    contentDescription: String? = null,
    // A Coil model (content:// or file URI): decoded at the avatar's own pixels over the initials, which stay when
    // the model is empty or fails to load. No SubcomposeAsyncImage: the initials are always the layer below.
    photoModel: Any? = null,
    // On a call's aura the palette colour can match the backdrop: the initials sit on a 22 % white disc with a 0.5 dp
    // white 40 % rim instead (photos are unchanged).
    onAura: Boolean = false,
) {
    if (photoModel == null) {
        AvatarFace(name, modifier, size, photo, colorKey, contentDescription, onAura)
        return
    }
    Box(modifier.size(size).clip(CircleShape)) {
        AvatarFace(name, Modifier, size, photo, colorKey, contentDescription, onAura)
        RowImage(photoModel, size)
    }
}

@Composable
private fun AvatarFace(
    name: String,
    modifier: Modifier,
    size: Dp,
    photo: ImageBitmap?,
    colorKey: String,
    contentDescription: String?,
    onAura: Boolean = false,
) {
    val described =
        if (contentDescription !=
            null
        ) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    val color = avatarColor(colorKey)
    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).then(described).clip(CircleShape),
        )
        return
    }
    val initials = initialsOf(name)
    if (initials.isEmpty()) {
        Box(
            modifier
                .size(size)
                .then(described)
                .clip(CircleShape)
                .background(if (onAura) AuraFill else color)
                .then(if (onAura) Modifier.border(AuraRimWidth, AuraRim, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(RovstiIcons.Person, null, tint = Color.White, size = size * GLYPH_RATIO)
        }
        return
    }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    Box(
        modifier
            .size(size)
            .then(described)
            .drawWithCache {
                val layout = measurer.measureInitials(initials, size.toPx() * INITIALS_RATIO, this)
                val topLeft = layout.centeredAt(Offset(this.size.width / 2f, this.size.height / 2f))
                onDrawBehind {
                    if (onAura) {
                        drawCircle(AuraFill)
                        drawCircle(
                            AuraRim,
                            radius = this.size.minDimension / 2f - AuraRimWidth.toPx() / 2f,
                            style = Stroke(AuraRimWidth.toPx()),
                        )
                    } else {
                        drawCircle(color)
                    }
                    drawText(layout, topLeft = topLeft)
                }
            },
    )
}

/**
 * A group chat's avatar: ONE circle, never a stack of overlapping ones. Exactly two members are shown as two halves
 * with one initial each; any other size shows the people glyph on a colour derived from all the keys.
 */
@Composable
fun GroupAvatar(
    keys: ImmutableList<String>,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatar,
    contentDescription: String? = null,
) {
    val described =
        if (contentDescription !=
            null
        ) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    if (keys.size != 2) {
        Box(
            modifier
                .size(size)
                .then(described)
                .clip(CircleShape)
                .background(avatarColor(keys.joinToString("|"))),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(RovstiIcons.People, null, tint = Color.White, size = size * GLYPH_RATIO)
        }
        return
    }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val left = avatarColor(keys[0])
    val right = avatarColor(keys[1])
    val firstInitial = initialsOf(keys[0]).take(1)
    val secondInitial = initialsOf(keys[1]).take(1)
    val seam = Color.White
    Box(
        modifier
            .size(size)
            .then(described)
            .drawWithCache {
                val w = this.size.width
                val h = this.size.height
                val fontPx = size.toPx() * SPLIT_RATIO
                val a = if (firstInitial.isEmpty()) null else measurer.measureInitials(firstInitial, fontPx, this)
                val b = if (secondInitial.isEmpty()) null else measurer.measureInitials(secondInitial, fontPx, this)
                val clip = Path().apply { addOval(Rect(0f, 0f, w, h)) }
                onDrawBehind {
                    clipPath(clip) {
                        drawRect(left, size = Size(w / 2f, h))
                        drawRect(right, topLeft = Offset(w / 2f, 0f), size = Size(w / 2f, h))
                        drawRect(seam, topLeft = Offset(w / 2f - 0.75f, 0f), size = Size(1.5f, h))
                    }
                    if (a != null) drawText(a, topLeft = a.centeredAt(Offset(w / 4f, h / 2f)))
                    if (b != null) drawText(b, topLeft = b.centeredAt(Offset(w * 3f / 4f, h / 2f)))
                }
            },
    )
}

private fun TextMeasurer.measureInitials(
    text: String,
    fontPx: Float,
    density: Density,
): TextLayoutResult =
    measure(
        text = text,
        style =
            TextStyle(
                color = Color.White,
                fontSize = with(density) { fontPx.toSp() },
                fontWeight = FontWeight.SemiBold,
            ),
        maxLines = 1,
        softWrap = false,
        density = density,
    )

/** Top-left that puts the text's capital letters (not its line box) on [center]. */
private fun TextLayoutResult.centeredAt(center: Offset): Offset {
    val capHeight = layoutInput.density.run { layoutInput.style.fontSize.toPx() } * CAP_HEIGHT
    return Offset(center.x - size.width / 2f, center.y + capHeight / 2f - firstBaseline)
}
