package dev.rosty.core.ops

import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.call
import dev.rosty.core.model.contacts.WaCardAnswer
import dev.rosty.core.model.contacts.WaCardRequest
import dev.rosty.core.model.contacts.WaMarksAnswer
import dev.rosty.core.model.contacts.WaMarksRequest
import dev.rosty.core.model.contacts.WaProfileLinesAnswer
import dev.rosty.core.model.contacts.WaProfileLinesRequest

/**
 * The WhatsApp decisions of the Contacts list and card (AD-CT6, `rosty_app_core::contacts::whatsapp`). Every op is
 * stateless: Contacts never sends the address book to WhatsApp, the marks come from the chats the instance already has.
 */
interface ContactsWaOps {
    /** The contacts whose numbers match a 1:1 chat, each with that chat's JID. */
    suspend fun marks(req: WaMarksRequest): Outcome<WaMarksAnswer>

    /** What the card says and asks about WhatsApp for one contact. */
    suspend fun card(req: WaCardRequest): Outcome<WaCardAnswer>

    /** The "WhatsApp name / Business / About" lines of a profile. */
    suspend fun profileLines(req: WaProfileLinesRequest): Outcome<WaProfileLinesAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> = listOf("contacts.wa_marks", "contacts.wa_card", "contacts.wa_profile_lines")
    }
}

/** [ContactsWaOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeContactsWaOps(
    private val core: RustCore = NativeRustCore(),
) : ContactsWaOps {
    override suspend fun marks(req: WaMarksRequest) =
        core.call("contacts.wa_marks", req, WaMarksRequest.serializer(), WaMarksAnswer.serializer())

    override suspend fun card(req: WaCardRequest) =
        core.call("contacts.wa_card", req, WaCardRequest.serializer(), WaCardAnswer.serializer())

    override suspend fun profileLines(req: WaProfileLinesRequest) =
        core.call(
            "contacts.wa_profile_lines",
            req,
            WaProfileLinesRequest.serializer(),
            WaProfileLinesAnswer.serializer(),
        )
}
