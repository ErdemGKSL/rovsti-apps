package dev.rovsti.core.model.contacts

import kotlinx.serialization.Serializable

// Answers of the contacts provider repository in `:platform` (E3.2) that no Rust op defines. They are plain data the
// repository returns; the shapes the ops already have (`ContactDraft`, `Group`, `Account`, `PhotoPlan`,
// `ContactOptions`, `PinShortcut`) are reused, not repeated. Stability: `compose_stability.conf`.

/** What a group member change did: contacts [added], [removed], and [skipped] (no live raw contact in the group's account). */
@Serializable
data class GroupMembersResult(
    val added: Int,
    val removed: Int,
    val skipped: Int,
)

/** What an import wrote: [added] contacts, and [ids] aligned with the drafts (0 where the id is unknown). */
@Serializable
data class ImportResult(
    val added: Int,
    val ids: List<Long>,
)

/** The size of a picture file in pixels, EXIF rotation applied; 0 x 0 when it cannot be read. */
@Serializable
data class PhotoSize(
    val width: Int,
    val height: Int,
)
