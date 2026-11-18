package dev.rosty.core.contracts

import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultCreateRequest
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultDirRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultKeys
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.model.wa.VaultLinkUnlockRequest
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultPicturePut
import dev.rosty.core.model.wa.VaultPicturePutRequest
import dev.rosty.core.model.wa.VaultPictures
import dev.rosty.core.model.wa.VaultPicturesRequest
import dev.rosty.core.model.wa.VaultReset
import dev.rosty.core.model.wa.VaultState
import dev.rosty.core.model.wa.VaultStateRequest
import dev.rosty.core.model.wa.VaultStayUnlockRequest
import dev.rosty.core.model.wa.VaultTouchRequest
import dev.rosty.core.model.wa.VaultTouched
import dev.rosty.core.model.wa.VaultTripLink
import dev.rosty.core.model.wa.VaultUnlockRequest
import dev.rosty.core.model.wa.VaultUnlocked
import dev.rosty.core.model.wa.VaultWipeReport
import dev.rosty.core.model.wa.VaultWipeRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `vault.*` (the WhatsApp vault). */
object VaultContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<VaultStateRequest, VaultState>(
                "vault.state",
                ok = listOf("empty", "legacy", "rebooted", "within_90_days", "expired", "clock_back"),
            )
            op<VaultCreateRequest, VaultKeys>(
                "vault.create",
                ok = listOf("link"),
                err = listOf("exists", "no_link", "bad_key"),
            )
            op<VaultCommitRequest, VaultState>("vault.commit", err = listOf("gone", "twice"))
            op<VaultUnlockRequest, VaultUnlocked>(
                "vault.unlock",
                err = listOf("wrong_passphrase", "typo", "no_vault", "damaged"),
            )
            op<VaultDailyUnlockRequest, VaultUnlocked>(
                "vault.unlock_daily",
                ok = listOf("rebooted", "use_reset"),
                err = listOf("idle", "clock_back", "damaged", "no_daily"),
            )
            op<VaultStayUnlockRequest, VaultUnlocked>(
                "vault.unlock_stay",
                ok = listOf("use_reset"),
                err = listOf("idle", "clock_back", "damaged", "no_stay"),
            )
            op<VaultLinkUnlockRequest, VaultUnlocked>(
                "vault.unlock_link",
                err = listOf("other_key", "other_instance", "no_vault"),
            )
            op<VaultDailyStoreRequest, VaultState>("vault.daily_store", err = listOf("gone"))
            op<VaultDirRequest, VaultState>("vault.daily_forget")
            op<VaultDailyStoreRequest, VaultState>("vault.stay_store", err = listOf("nothing", "gone"))
            op<VaultDirRequest, VaultState>("vault.stay_forget")
            op<VaultTouchRequest, VaultUnlocked>("vault.stay_prepare", err = listOf("locked"))
            op<VaultTouchRequest, VaultTouched>("vault.touch", ok = listOf("hour"), err = listOf("idle", "locked"))
            op<VaultLockRequest, VaultState>("vault.lock", ok = listOf("handle", "nothing_open"))
            op<VaultHandleRequest, VaultKeys>("vault.change", err = listOf("locked"))
            op<VaultHandleRequest, VaultLink>("vault.export_link", err = listOf("locked", "not_committed"))
            op<VaultDirRequest, VaultReset>("vault.reset", ok = listOf("nothing"))
            op<VaultHandleRequest, VaultTripLink>("vault.trip_hold", err = listOf("locked"))
            op<Empty, VaultTripLink>("vault.trip_release")
            op<VaultWipeRequest, VaultWipeReport>("vault.wipe_plaintext", err = listOf("no_vault"))
            op<VaultPicturesRequest, VaultPictures>("vault.pictures", ok = listOf("some"), err = listOf("locked"))
            op<VaultPicturePutRequest, VaultPicturePut>(
                "vault.picture_put",
                ok = listOf("removed"),
                err = listOf("locked", "half"),
            )
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        put("$op.request", serializer<Req>())
        put("$op.answer", okAnswer)
        for (label in ok) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", okAnswer)
        }
        for (label in err) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", ErrEnvelope.serializer())
        }
    }
}
