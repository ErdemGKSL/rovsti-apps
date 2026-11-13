@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.people

import dev.rovsti.core.model.wa.DiskFile
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `people.*` (X.1a), mirroring `crates/rovsti_ffi/fixtures/people.*`. Every request may name `dir`
// (fixtures); apps leave it out and the core uses its data directory. Stability: `compose_stability.conf`.

/** What a caller knows about one person; only the fields that are set change the stored record. */
@Serializable
data class PersonUpdate(
    val jid: String,
    @EncodeDefault(NEVER) val number: String? = null,
    @EncodeDefault(NEVER) val lid: String? = null,
    @SerialName("push_name") @EncodeDefault(NEVER) val pushName: String? = null,
    @SerialName("verified_name") @EncodeDefault(NEVER) val verifiedName: String? = null,
    @SerialName("on_whatsapp") @EncodeDefault(NEVER) val onWhatsapp: Boolean? = null,
    @SerialName("picture_id") @EncodeDefault(NEVER) val pictureId: String? = null,
    /** The gateway says there is no picture (or it is hidden): the file goes. */
    @SerialName("picture_removed") @EncodeDefault(NEVER) val pictureRemoved: Boolean? = null,
    /** A picture file was just stored for [pictureId]. */
    @SerialName("picture_file") @EncodeDefault(NEVER) val pictureFile: String? = null,
)

/** `people.upsert`. */
@Serializable
data class PeopleUpsertRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("now_ms") val nowMs: Long,
    val people: List<PersonUpdate>,
)

@Serializable
data class PeopleUpsertAnswer(
    val changed: Int,
    /** JIDs whose picture is now stale (the old file still shows until the new one is in). */
    val stale: List<String>,
    /** File names that belong to nobody any more. */
    @SerialName("drop_files") val dropFiles: List<String>,
)

/** A person as the provider shows it. */
@Serializable
data class PersonRow(
    val jid: String,
    val numbers: List<String> = emptyList(),
    val lid: String? = null,
    val name: String? = null,
    @SerialName("on_whatsapp") val onWhatsapp: Boolean = false,
    @SerialName("picture_id") val pictureId: String? = null,
    @SerialName("picture_file") val pictureFile: String? = null,
    @SerialName("picture_stale") val pictureStale: Boolean = false,
)

/** `people.lookup`: [key] is an E.164 number in any spelling, a phone JID, a LID JID or a bare LID. */
@Serializable
data class PeopleLookupRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val key: String,
)

@Serializable
data class PeopleLookupAnswer(
    val person: PersonRow? = null,
)

/** `people.plan`: which pictures to fetch and which files to delete, given the files on disk. */
@Serializable
data class PeoplePlanRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val files: List<DiskFile> = emptyList(),
    @SerialName("max_bytes") val maxBytes: Long,
)

@Serializable
data class PictureRefresh(
    val jid: String,
    @SerialName("picture_id") val pictureId: String? = null,
)

@Serializable
data class PeoplePlanAnswer(
    val refresh: List<PictureRefresh>,
    val remove: List<String>,
)

/** `people.snapshot`: every person, by name. */
@Serializable
data class PeopleSnapshotRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class PeopleSnapshotAnswer(
    val people: List<PersonRow>,
)
