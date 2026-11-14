package dev.rovsti.contacts.people

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.media.RowImage
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.theme.Sizes
import dev.rovsti.people.AvatarResolver
import dev.rovsti.people.PersonAvatar

/**
 * What a Contacts surface knows about the picture of a person: the contact's own photo (a content URI, null: none) and
 * the numbers the WhatsApp picture is looked up by. Surfaces pass this to [ContactAvatar] and never read the photo
 * themselves; [AvatarResolver] decides (own photo, else WhatsApp picture, else initials, X.1c).
 */
@Immutable
data class AvatarFace(
    val own: String? = null,
    val numbers: List<String> = emptyList(),
)

/** The resolver of the app's people cache; null (previews, tests without a cache) means own photo or initials. */
val LocalAvatarResolver = staticCompositionLocalOf<AvatarResolver?> { null }

/** Which of the three an avatar drew: `own`, `whatsapp` or `initials`. For tests and accessibility tooling. */
val AvatarKind = SemanticsPropertyKey<String>("AvatarKind")

/** The alpha of the WhatsApp picture layer (1 when there is none or it is in): the fade, readable by tests. */
val AvatarPictureAlpha = SemanticsPropertyKey<Float>("AvatarPictureAlpha")

private var SemanticsPropertyReceiver.avatarKind by AvatarKind
private var SemanticsPropertyReceiver.pictureAlpha by AvatarPictureAlpha

/** How long a WhatsApp picture that arrives after the avatar was drawn takes to fade in (alpha only). */
const val PICTURE_FADE_MS = 220

/**
 * The avatar of a contact on any Contacts surface. The photo comes from the resolver: the contact's own photo wins, then
 * the WhatsApp picture of the same person, then the monogram. The monogram is always the layer below, so a picture
 * that arrives later fades in on top of it (220 ms alpha, no layout change, none under reduce motion), and a picture
 * that was already known when the avatar was first composed is there on the first frame. Nothing is ever written to
 * the Android contact from here; "Use WhatsApp Photo" in the editor is the only action that does.
 */
@Composable
fun ContactAvatar(
    name: String,
    face: AvatarFace,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatar,
    colorKey: String = name,
    contentDescription: String? = null,
) {
    val resolver = LocalAvatarResolver.current
    val version = resolver?.version?.collectAsState()?.value ?: 0L
    val avatar =
        remember(resolver, version, face) {
            resolver?.resolve(face.own, face.numbers)
                ?: face.own?.takeIf { it.isNotBlank() }?.let { PersonAvatar.Own(it) }
                ?: PersonAvatar.Initials
        }
    // A picture that shows up while the avatar is on screen (the sync finished) fades in; one known at the first
    // composition does not, and neither does anything under reduce motion.
    val seen = remember { Seen() }
    val picture = avatar as? PersonAvatar.WhatsApp
    if (picture == null) seen.withoutPicture = true
    val animate = seen.withoutPicture && !LocalReduceMotion.current
    val alpha = remember(picture?.pictureId) { Animatable(if (picture != null && animate) 0f else 1f) }
    LaunchedEffect(picture?.pictureId, animate) {
        if (picture == null) return@LaunchedEffect
        if (animate) alpha.animateTo(1f, tween(PICTURE_FADE_MS, easing = FastOutSlowInEasing)) else alpha.snapTo(1f)
    }
    val kind =
        when (avatar) {
            is PersonAvatar.Own -> "own"
            is PersonAvatar.WhatsApp -> "whatsapp"
            PersonAvatar.Initials -> "initials"
        }
    Box(
        modifier.semantics {
            avatarKind = kind
            pictureAlpha = alpha.value
        },
    ) {
        Avatar(
            name,
            size = size,
            colorKey = colorKey,
            contentDescription = contentDescription,
            photoModel = (avatar as? PersonAvatar.Own)?.uri,
        )
        if (picture != null) {
            RowImage(
                picture.uri,
                size,
                modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
                shape = CircleShape,
            )
        }
    }
}

private class Seen {
    var withoutPicture = false
}
