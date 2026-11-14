package dev.rovsti.phone.details.photo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import dev.rovsti.design.list.Avatar
import dev.rovsti.people.ui.AvatarFace
import dev.rovsti.people.ui.ContactAvatar
import dev.rovsti.phone.R

/** Test tag of the tappable avatar of the Person and Call details sheets. */
const val PERSON_AVATAR_TAG = "person-avatar"

/**
 * The avatar of a details sheet (E5.13): the contact's photo over the initials. With a [photo] and an opener in
 * [LocalPersonPhotoOpener] a tap opens the photo viewer out of the avatar's own rectangle (read at tap time, so a sheet
 * that moved is followed); without them it is a plain avatar.
 */
@Composable
fun PersonPhotoAvatar(
    name: String,
    colorKey: String,
    size: Dp,
    photo: PersonPhoto?,
    modifier: Modifier = Modifier,
    /** The numbers of the person: the WhatsApp picture is looked up by them when the contact has no photo (X.1d). */
    numbers: List<String> = emptyList(),
) {
    val opener = LocalPersonPhotoOpener.current
    if (photo == null || opener == null) {
        // No photo of their own to open: the WhatsApp picture of the number, else initials.
        ContactAvatar(name, AvatarFace(numbers = numbers), modifier, size, colorKey)
        return
    }
    val bounds = remember { arrayOfNulls<Rect>(1) }
    val label = stringResource(R.string.photo_of, name)
    Avatar(
        name = name,
        size = size,
        colorKey = colorKey,
        photoModel = photo.thumb,
        contentDescription = label,
        modifier =
            modifier
                .testTag(PERSON_AVATAR_TAG)
                .onGloballyPositioned { bounds[0] = it.boundsInWindow() }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.photo_open),
                ) { opener.open(photo, name) { bounds[0] } },
    )
}
