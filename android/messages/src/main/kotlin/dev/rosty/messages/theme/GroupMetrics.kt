package dev.rosty.messages.theme

import androidx.compose.ui.unit.dp

/** Sizes of the group pages and sheets (E6-WA R6); the convention keeps raw dp in `theme/`. */
object GroupMetrics {
    /** The group's picture at the top of the info page. */
    val HeaderAvatar = 96.dp

    /** Picture to subject, subject to the count line. */
    val HeaderGap = 12.dp

    /** The role badge after a member's name. */
    val BadgePadH = 8.dp
    val BadgePadV = 2.dp

    /** The longest picture side sent to WhatsApp (it shows 640 px). */
    const val PICTURE_PX = 640

    /** JPEG quality of the sent picture. */
    const val PICTURE_QUALITY = 85
}
