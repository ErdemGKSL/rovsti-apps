package dev.rovsti.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.GroupAvatar
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

private val Names =
    listOf("Ali Kaya", "Ayşe Yılmaz", "Can Demir", "Deniz Aydın", "Elif Şahin", "Fatma Çelik", "Gökhan Arı", "Hale Koç")

/** Palette, sizes, a photo, a number without initials, and group avatars that are one circle each. */
@Composable
internal fun AvatarsPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader("Avatars", "One circle per row. Colour is a pure function of the name.")
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            SectionLabel("Palette")
            for (rowNames in Names.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (name in rowNames) Labeled(name.substringBefore(' ')) { Avatar(name, size = Sizes.avatarLarge) }
                }
            }
            SectionLabel("Sizes, photo and no initials")
            val photo = remember { samplePhoto() }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Labeled("32") { Avatar("Ali Kaya", size = 32.dp) }
                Labeled("40") { Avatar("Ali Kaya") }
                Labeled("56") { Avatar("Ali Kaya", size = 56.dp) }
                Labeled("Photo") { Avatar("Ali Kaya", photo = photo, size = Sizes.avatarLarge) }
                Labeled("Number") { Avatar("+90 532 111 22 33", size = Sizes.avatarLarge) }
            }
            SectionLabel("Groups")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Labeled("Two") { GroupAvatar(persistentListOf("Ali Kaya", "Can Demir"), size = Sizes.avatarLarge) }
                Labeled(
                    "Three",
                ) { GroupAvatar(persistentListOf("Ali Kaya", "Can Demir", "Elif Şahin"), size = Sizes.avatarLarge) }
                Labeled("Two, 56") { GroupAvatar(persistentListOf("Deniz Aydın", "Hale Koç"), size = 56.dp) }
                Labeled("Empty") { GroupAvatar(persistentListOf(), size = Sizes.avatarLarge) }
            }
        }
    }
}

@Composable
private fun Labeled(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.widthIn(min = 64.dp, max = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        content()
        RovstiText(
            label,
            style = RovstiTheme.type.caption1,
            color = RovstiTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** A 96 px diagonal gradient standing in for a contact photo. */
private fun samplePhoto(): ImageBitmap {
    val bitmap = ImageBitmap(96, 96)
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        canvas,
        androidx.compose.ui.geometry
            .Size(96f, 96f),
    ) {
        drawRect(Brush.linearGradient(listOf(Color(0xFFF5A524), Color(0xFFD6409F)), Offset.Zero, Offset(96f, 96f)))
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 18f, center = Offset(48f, 40f))
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 34f, center = Offset(48f, 100f))
    }
    return bitmap
}
