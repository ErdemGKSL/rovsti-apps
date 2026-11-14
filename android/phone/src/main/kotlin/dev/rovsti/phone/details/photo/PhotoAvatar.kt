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
import dev.rovsti.people.PersonAvatar
import dev.rovsti.people.ui.AvatarFace
import dev.rovsti.people.ui.ContactAvatar
import dev.rovsti.people.ui.rememberPersonAvatar
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
    val bounds = remember { arrayOfNulls<Rect>(1) }
    if (photo?.hasPhoto != true) {
        // No photo of their own: the WhatsApp picture of the number opens read-only in the viewer (S12); a contact
        // with initials only keeps the add-photo sheet, a stranger with initials is a plain avatar.
        val face = AvatarFace(numbers = numbers)
        val wa = rememberPersonAvatar(face) as? PersonAvatar.WhatsApp
        val target = wa?.let { PersonPhoto.whatsApp(photo?.contactId, it) } ?: photo
        if (opener == null || target == null) {
            ContactAvatar(name, face, modifier, size, colorKey)
            return
        }
        ContactAvatar(
            name,
            face,
            modifier
                .testTag(PERSON_AVATAR_TAG)
                .onGloballyPositioned { bounds[0] = it.boundsInWindow() }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.photo_open),
                ) { opener.open(target, name) { bounds[0] } },
            size,
            colorKey,
            contentDescription =
                if (wa !=
                    null
                ) {
                    stringResource(R.string.photo_whatsapp_of, name)
                } else {
                    stringResource(R.string.photo_of, name)
                },
        )
        return
    }
    if (opener == null) {
        ContactAvatar(name, AvatarFace(numbers = numbers), modifier, size, colorKey)
        return
    }
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
