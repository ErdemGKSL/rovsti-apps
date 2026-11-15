package dev.rosty.core.ops

import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.call
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.wa.WaIdBookAccount
import dev.rosty.core.model.wa.WaIdBookAccountRequest
import dev.rosty.core.model.wa.WaIdBookCatchUpRequest
import dev.rosty.core.model.wa.WaIdBookCaughtUp
import dev.rosty.core.model.wa.WaIdBookLearnRequest
import dev.rosty.core.model.wa.WaIdBookLearned
import dev.rosty.core.model.wa.WaIdBookSnapshot
import dev.rosty.core.model.wa.WaIdBookSnapshotRequest
import dev.rosty.core.model.wa.WaIdBookWipeRequest

/**
 * Phone's own WhatsApp identity table (WA-R2): which number is behind a LID, kept in `wa-identity.json` by the Rust
 * core (`rosty_app_core::phone::wa_idbook`, the same `IdentityMap` the offline store of Messages uses). No op touches
 * the network; [WaIdentityBook] is the facade the app uses.
 */
interface WaIdBookOps {
    /** Every LID the table can name with its number, and the cursor of the next catch-up. */
    suspend fun snapshot(req: WaIdBookSnapshotRequest): Outcome<WaIdBookSnapshot>

    /** Takes in one page of `wa.identity_mappings`. */
    suspend fun catchUp(req: WaIdBookCatchUpRequest): Outcome<WaIdBookCaughtUp>

    /** Learns pairs from events and calls. */
    suspend fun learn(req: WaIdBookLearnRequest): Outcome<WaIdBookLearned>

    /** Notes the account of the link; another account empties the table. */
    suspend fun account(req: WaIdBookAccountRequest): Outcome<WaIdBookAccount>

    /** Removes the table. */
    suspend fun wipe(req: WaIdBookWipeRequest = WaIdBookWipeRequest()): Outcome<Empty>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "wa_idbook.snapshot",
                "wa_idbook.catch_up",
                "wa_idbook.learn",
                "wa_idbook.account",
                "wa_idbook.wipe",
            )
    }
}

/** [WaIdBookOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeWaIdBookOps(
    private val core: RustCore = NativeRustCore(),
) : WaIdBookOps {
    override suspend fun snapshot(req: WaIdBookSnapshotRequest) =
        core.call("wa_idbook.snapshot", req, WaIdBookSnapshotRequest.serializer(), WaIdBookSnapshot.serializer())

    override suspend fun catchUp(req: WaIdBookCatchUpRequest) =
        core.call("wa_idbook.catch_up", req, WaIdBookCatchUpRequest.serializer(), WaIdBookCaughtUp.serializer())

    override suspend fun learn(req: WaIdBookLearnRequest) =
        core.call("wa_idbook.learn", req, WaIdBookLearnRequest.serializer(), WaIdBookLearned.serializer())

    override suspend fun account(req: WaIdBookAccountRequest) =
        core.call("wa_idbook.account", req, WaIdBookAccountRequest.serializer(), WaIdBookAccount.serializer())

    override suspend fun wipe(req: WaIdBookWipeRequest) =
        core.call("wa_idbook.wipe", req, WaIdBookWipeRequest.serializer(), Empty.serializer())
}
