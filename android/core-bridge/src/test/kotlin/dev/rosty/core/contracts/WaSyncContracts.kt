package dev.rosty.core.contracts

import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.wa.HandleAnswer
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncCancelled
import dev.rosty.core.model.wa.WaSyncChatsAnswer
import dev.rosty.core.model.wa.WaSyncChatsRequest
import dev.rosty.core.model.wa.WaSyncClientIdRequest
import dev.rosty.core.model.wa.WaSyncClosed
import dev.rosty.core.model.wa.WaSyncDue
import dev.rosty.core.model.wa.WaSyncDueRequest
import dev.rosty.core.model.wa.WaSyncEnqueueRequest
import dev.rosty.core.model.wa.WaSyncEventsRequest
import dev.rosty.core.model.wa.WaSyncEvictRequest
import dev.rosty.core.model.wa.WaSyncEviction
import dev.rosty.core.model.wa.WaSyncHandleRequest
import dev.rosty.core.model.wa.WaSyncItemAnswer
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMessageAnswer
import dev.rosty.core.model.wa.WaSyncMessageRequest
import dev.rosty.core.model.wa.WaSyncOlderAnswer
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncOpenRequest
import dev.rosty.core.model.wa.WaSyncOpened
import dev.rosty.core.model.wa.WaSyncOutboxAnswer
import dev.rosty.core.model.wa.WaSyncOutboxEditRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncPlanRequest
import dev.rosty.core.model.wa.WaSyncRetried
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `wa_sync.*` (the WhatsApp offline store, E6.4s). */
object WaSyncContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<WaSyncOpenRequest, WaSyncOpened>("wa_sync.open", err = listOf("no_instance"))
            op<WaSyncHandleRequest, WaSyncClosed>("wa_sync.close", err = listOf("not_open"))
            op<WaSyncPlanRequest, WaSyncPlan>("wa_sync.plan", ok = listOf("gap"))
            op<WaSyncEventsRequest, WaSyncApplied>("wa_sync.apply_events", ok = listOf("resync", "stale", "echo"))
            op<WaSyncStoreChatsRequest, WaSyncStoredChats>("wa_sync.store_chats", ok = listOf("missed"))
            op<WaSyncStorePageRequest, WaSyncStoredPage>("wa_sync.store_page", ok = listOf("overlap", "older"))
            op<WaSyncOlderRequest, WaSyncOlderAnswer>("wa_sync.older", ok = listOf("start"))
            op<WaSyncChatsRequest, WaSyncChatsAnswer>("wa_sync.chats", ok = listOf("empty"))
            op<WaSyncThreadRequest, WaSyncThreadPage>(
                "wa_sync.thread",
                ok = listOf("older", "media", "pending", "unknown_chat"),
            )
            op<WaSyncMessageRequest, WaSyncMessageAnswer>("wa_sync.message", ok = listOf("unknown"))
            op<WaSyncEnqueueRequest, WaSyncItemAnswer>(
                "wa_sync.outbox_enqueue",
                ok = listOf("media", "edit", "revoke", "react", "mark_read"),
                err = listOf("no_chat"),
            )
            op<WaSyncDueRequest, WaSyncDue>("wa_sync.outbox_due", ok = listOf("sending", "empty"))
            op<WaSyncSettleRequest, WaSyncSettled>(
                "wa_sync.outbox_settle",
                ok = listOf("network", "rate_limited", "rejected", "gone"),
            )
            op<WaSyncClientIdRequest, WaSyncCancelled>("wa_sync.outbox_cancel", ok = listOf("in_flight"))
            op<WaSyncOutboxEditRequest, WaSyncItemAnswer>("wa_sync.outbox_edit", err = listOf("in_flight", "gone"))
            op<WaSyncClientIdRequest, WaSyncRetried>("wa_sync.outbox_retry", ok = listOf("gone"))
            op<WaSyncHandleRequest, WaSyncOutboxAnswer>("wa_sync.outbox")
            op<WaSyncMediaSetRequest, HandleAnswer>("wa_sync.media_set", err = listOf("unknown", "bad_base64"))
            op<WaSyncMediaRequest, WaSyncMedia>("wa_sync.media", ok = listOf("none"), err = listOf("unknown"))
            op<WaSyncEvictRequest, WaSyncEviction>("wa_sync.media_evict", ok = listOf("under_the_cap"))
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
