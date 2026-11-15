package dev.rosty.messages.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.rosty.design.list.Avatar
import dev.rosty.design.theme.Sizes
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.people.PersonAvatar
import dev.rosty.people.ui.AvatarFace
import dev.rosty.people.ui.ContactAvatar
import dev.rosty.people.ui.LocalAvatarResolver
import dev.rosty.wa.avatars.WaAvatar

/** Asks `WaAvatars` for the picture of a chat about to be drawn (null in tests and previews). */
val LocalWantAvatar = staticCompositionLocalOf<((String, String?) -> Unit)?> { null }

/**
 * The one avatar of a row or a conversation header (AC1: one avatar, a group one composite). Own photo first, then the
 * person's WhatsApp picture from the people cache (Contacts' sync), then the chat's own WhatsApp picture through
 * `WaAvatars` (the Coil fetcher of `:wa`, 429 back-off in Rust), then initials.
 */
@Composable
fun MessagesAvatar(
    name: String,
    avatar: RowAvatar,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.avatar,
    contentDescription: String? = null,
) {
    val want = LocalWantAvatar.current
    val jid = avatar.waJid
    if (want != null && jid != null) LaunchedEffect(jid, avatar.pictureId) { want(jid, avatar.pictureId) }
    val resolver = LocalAvatarResolver.current
    val known =
        resolver?.resolve(avatar.photoUri, avatar.numbers)
            ?: avatar.photoUri?.let { PersonAvatar.Own(it) }
    if (known != null && known !is PersonAvatar.Initials) {
        ContactAvatar(
            name = name,
            face = AvatarFace(avatar.photoUri, avatar.numbers),
            modifier = modifier,
            size = size,
            colorKey = avatar.colorKey,
            contentDescription = contentDescription,
        )
        return
    }
    Avatar(
        name = if (avatar.group) "" else name,
        modifier = modifier,
        size = size,
        colorKey = avatar.colorKey,
        contentDescription = contentDescription,
        photoModel = avatar.waJid?.let(::WaAvatar),
    )
}
