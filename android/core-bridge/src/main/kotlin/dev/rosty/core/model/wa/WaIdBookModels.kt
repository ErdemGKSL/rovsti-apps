@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.wa

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `wa_idbook.*` (WA-R2), mirroring `crates/rosty_ffi/fixtures/wa_idbook.*`: Phone's own table of LID and
// number pairs (`wa-identity.json`). Every request may name `dir` (fixtures, tests); apps leave it out and the core
// uses its data directory. Stability: `compose_stability.conf`.

/** A LID and the phone number of the person behind it, both bare (`<user>@lid`, `<digits>@s.whatsapp.net`). */
@Serializable
data class WaIdBookPair(
    val lid: String,
    val pn: String,
)

/** `wa_idbook.snapshot`. A file of another instance than [instanceId] reads empty. */
@Serializable
data class WaIdBookSnapshotRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("instance_id") val instanceId: String,
    /** The table is in the vault cache (Phone since its vault, `plans/wa-vault.md` 13.5), not in `wa-identity.json`. */
    @EncodeDefault(NEVER) val vault: Boolean = false,
)

@Serializable
data class WaIdBookSnapshot(
    /** `since` of the next `wa.identity_mappings` call; null before the first page: ask for every pair. */
    @EncodeDefault(NEVER) val since: String? = null,
    @SerialName("own_pn") @EncodeDefault(NEVER) val ownPn: String? = null,
    @SerialName("own_lid") @EncodeDefault(NEVER) val ownLid: String? = null,
    /** Every LID the table can name with the current number of its person. */
    val pairs: List<WaIdBookPair> = emptyList(),
)

/** `wa_idbook.catch_up`: one page of `wa.identity_mappings`. */
@Serializable
data class WaIdBookCatchUpRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("instance_id") val instanceId: String,
    val items: List<IdentityMapping> = emptyList(),
    @EncodeDefault(NEVER) val next: String? = null,
    /** The table is in the vault cache (Phone since its vault, `plans/wa-vault.md` 13.5), not in `wa-identity.json`. */
    @EncodeDefault(NEVER) val vault: Boolean = false,
)

@Serializable
data class WaIdBookCaughtUp(
    val pairs: Int,
    /** The cursor the table now holds. */
    @EncodeDefault(NEVER) val since: String? = null,
    /** The gateway has more: ask again with [since]. */
    val more: Boolean,
    /** The table or its cursor changed. */
    val changed: Boolean,
)

/** Two ids of one person, in either order: an `identity_mapped` event, a call's `peer` and `peer_alt`. */
@Serializable
data class WaIdBookLearnPair(
    val id: String,
    val alt: String,
    /** The gateway's source name; `call` when left out. */
    @EncodeDefault(NEVER) val source: String? = null,
    @SerialName("learned_ms") @EncodeDefault(NEVER) val learnedMs: Long? = null,
)

/** An `identity_number_changed` event. */
@Serializable
data class WaIdBookNumberChange(
    @SerialName("old_pn") val oldPn: String,
    @SerialName("new_pn") val newPn: String,
    @SerialName("old_lid") @EncodeDefault(NEVER) val oldLid: String? = null,
    @SerialName("new_lid") @EncodeDefault(NEVER) val newLid: String? = null,
)

/** `wa_idbook.learn`. */
@Serializable
data class WaIdBookLearnRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val pairs: List<WaIdBookLearnPair> = emptyList(),
    @SerialName("number_changes") @EncodeDefault(NEVER) val numberChanges: List<WaIdBookNumberChange> = emptyList(),
    /** The table is in the vault cache (Phone since its vault, `plans/wa-vault.md` 13.5), not in `wa-identity.json`. */
    @EncodeDefault(NEVER) val vault: Boolean = false,
)

@Serializable
data class WaIdBookLearned(
    /** A row changed: read the snapshot again. */
    val changed: Boolean,
)

/**
 * `wa_idbook.account`: what the instance status says about the account. Another own number or account epoch than
 * the stored one empties the table; [changed] is the gateway's `account_changed` event and always empties it.
 */
@Serializable
data class WaIdBookAccountRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("own_pn") @EncodeDefault(NEVER) val ownPn: String? = null,
    @SerialName("own_lid") @EncodeDefault(NEVER) val ownLid: String? = null,
    @SerialName("account_epoch") @EncodeDefault(NEVER) val accountEpoch: Int? = null,
    @EncodeDefault(NEVER) val changed: Boolean = false,
    /** The table is in the vault cache (Phone since its vault, `plans/wa-vault.md` 13.5), not in `wa-identity.json`. */
    @EncodeDefault(NEVER) val vault: Boolean = false,
)

@Serializable
data class WaIdBookAccount(
    /** The table and its cursor were emptied: catch up from the start. */
    val reset: Boolean,
)

/** `wa_idbook.wipe`: removes the table (the link was removed). */
@Serializable
data class WaIdBookWipeRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    /** The table is in the vault cache (Phone since its vault, `plans/wa-vault.md` 13.5), not in `wa-identity.json`. */
    @EncodeDefault(NEVER) val vault: Boolean = false,
)
