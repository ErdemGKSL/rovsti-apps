package dev.rosty.core.contracts

import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.wa.HandleAnswer
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIdParsed
import dev.rosty.core.model.wa.WaSyncAccountChangedRequest
import dev.rosty.core.model.wa.WaSyncApplied
import dev.rosty.core.model.wa.WaSyncCancelled
import dev.rosty.core.model.wa.WaSyncCatchUpRequest
import dev.rosty.core.model.wa.WaSyncCaughtUp
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
import dev.rosty.core.model.wa.WaSyncHistoryAnswer
import dev.rosty.core.model.wa.WaSyncIdentityApplied
import dev.rosty.core.model.wa.WaSyncItemAnswer
import dev.rosty.core.model.wa.WaSyncMedia
import dev.rosty.core.model.wa.WaSyncMediaCap
import dev.rosty.core.model.wa.WaSyncMediaCapRequest
import dev.rosty.core.model.wa.WaSyncMediaDue
import dev.rosty.core.model.wa.WaSyncMediaDueRequest
import dev.rosty.core.model.wa.WaSyncMediaEnqueueRequest
import dev.rosty.core.model.wa.WaSyncMediaKeepRequest
import dev.rosty.core.model.wa.WaSyncMediaKept
import dev.rosty.core.model.wa.WaSyncMediaPolicy
import dev.rosty.core.model.wa.WaSyncMediaPolicyRequest
import dev.rosty.core.model.wa.WaSyncMediaQueued
import dev.rosty.core.model.wa.WaSyncMediaRequest
import dev.rosty.core.model.wa.WaSyncMediaSetRequest
import dev.rosty.core.model.wa.WaSyncMediaSettleRequest
import dev.rosty.core.model.wa.WaSyncMediaSettled
import dev.rosty.core.model.wa.WaSyncMediaStats
import dev.rosty.core.model.wa.WaSyncMediaStatsRequest
import dev.rosty.core.model.wa.WaSyncMessageAnswer
import dev.rosty.core.model.wa.WaSyncMessageRequest
import dev.rosty.core.model.wa.WaSyncNoteStatusRequest
import dev.rosty.core.model.wa.WaSyncNumbersAnswer
import dev.rosty.core.model.wa.WaSyncObserveRequest
import dev.rosty.core.model.wa.WaSyncOlderAnswer
import dev.rosty.core.model.wa.WaSyncOlderRequest
import dev.rosty.core.model.wa.WaSyncOpenRequest
import dev.rosty.core.model.wa.WaSyncOpened
import dev.rosty.core.model.wa.WaSyncOutboxAnswer
import dev.rosty.core.model.wa.WaSyncOutboxEditRequest
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncPlanRequest
import dev.rosty.core.model.wa.WaSyncReconcileRequest
import dev.rosty.core.model.wa.WaSyncReconciled
import dev.rosty.core.model.wa.WaSyncRekeyedAnswer
import dev.rosty.core.model.wa.WaSyncRekeyedRequest
import dev.rosty.core.model.wa.WaSyncResolveRequest
import dev.rosty.core.model.wa.WaSyncResolved
import dev.rosty.core.model.wa.WaSyncRetried
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.core.model.wa.WaSyncSettled
import dev.rosty.core.model.wa.WaSyncStarredPage
import dev.rosty.core.model.wa.WaSyncStarredRequest
import dev.rosty.core.model.wa.WaSyncStatusNoted
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.model.wa.WaSyncThreadPage
import dev.rosty.core.model.wa.WaSyncThreadRequest
import dev.rosty.core.model.wa.WaSyncWipeRequest
import dev.rosty.core.model.wa.WaSyncWiped
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Fixture stem to model for `wa_sync.*` (the WhatsApp offline store, E6.4s) and `wa_identity.*` (reading a JID, which
 * the same facade offers).
 */
object WaSyncContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<WaSyncOpenRequest, WaSyncOpened>("wa_sync.open", err = listOf("no_instance"))
            op<WaSyncHandleRequest, WaSyncClosed>("wa_sync.close", err = listOf("not_open"))
            op<WaSyncPlanRequest, WaSyncPlan>("wa_sync.plan", ok = listOf("gap"))
            op<WaSyncEventsRequest, WaSyncApplied>(
                "wa_sync.apply_events",
                ok =
                    listOf(
                        "resync",
                        "stale",
                        "echo",
                        "identity",
                        "alt_ids",
                        "other_forms",
                        "rejected",
                        "own_ids",
                        "gateway",
                        "account_changed",
                    ),
            )
            op<WaSyncStoreChatsRequest, WaSyncStoredChats>("wa_sync.store_chats", ok = listOf("missed", "alt"))
            op<WaSyncStorePageRequest, WaSyncStoredPage>("wa_sync.store_page", ok = listOf("overlap", "alt", "older"))
            op<WaSyncOlderRequest, WaSyncOlderAnswer>("wa_sync.older", ok = listOf("start"))
            op<WaSyncChatsRequest, WaSyncChatsAnswer>("wa_sync.chats", ok = listOf("empty", "merged"))
            op<WaSyncThreadRequest, WaSyncThreadPage>(
                "wa_sync.thread",
                ok = listOf("older", "media", "pending", "unknown_chat", "old_key"),
            )
            op<WaSyncResolveRequest, WaSyncResolved?>(
                "wa_sync.identity_resolve",
                ok = listOf("number", "unknown_lid", "group", "not_an_id"),
            )
            op<WaSyncRekeyedRequest, WaSyncRekeyedAnswer>("wa_sync.rekeyed_since", ok = listOf("followed", "none"))
            op<WaSyncResolveRequest, WaSyncNumbersAnswer>(
                "wa_sync.identity_numbers",
                ok = listOf("former", "unknown_lid", "unknown_number", "group"),
            )
            op<WaSyncCatchUpRequest, WaSyncCaughtUp>("wa_sync.identity_catch_up", ok = listOf("done", "rejected"))
            op<WaSyncObserveRequest, WaSyncIdentityApplied>(
                "wa_sync.identity_observe",
                ok = listOf("nothing", "gateway", "account_changed"),
            )
            op<WaIdParseRequest, WaIdParsed?>(
                "wa_identity.parse",
                ok = listOf("lid", "c_us", "group", "status", "newsletter", "service", "not_an_id"),
            )
            op<WaSyncMessageRequest, WaSyncMessageAnswer>("wa_sync.message", ok = listOf("unknown"))
            op<WaSyncStarredRequest, WaSyncStarredPage>("wa_sync.starred", ok = listOf("older", "unstarred", "empty"))
            op<WaSyncEnqueueRequest, WaSyncItemAnswer>(
                "wa_sync.outbox_enqueue",
                ok = listOf("media", "edit", "revoke", "react", "mark_read", "star"),
                err = listOf("no_chat"),
            )
            op<WaSyncDueRequest, WaSyncDue>("wa_sync.outbox_due", ok = listOf("sending", "empty"))
            op<WaSyncSettleRequest, WaSyncSettled>(
                "wa_sync.outbox_settle",
                ok = listOf("network", "rate_limited", "rejected", "gone", "star_rejected"),
            )
            op<WaSyncClientIdRequest, WaSyncCancelled>("wa_sync.outbox_cancel", ok = listOf("in_flight"))
            op<WaSyncOutboxEditRequest, WaSyncItemAnswer>("wa_sync.outbox_edit", err = listOf("in_flight", "gone"))
            op<WaSyncClientIdRequest, WaSyncRetried>("wa_sync.outbox_retry", ok = listOf("gone"))
            op<WaSyncHandleRequest, WaSyncOutboxAnswer>("wa_sync.outbox")
            op<WaSyncMediaSetRequest, HandleAnswer>("wa_sync.media_set", err = listOf("unknown", "bad_base64"))
            op<WaSyncMediaRequest, WaSyncMedia>(
                "wa_sync.media",
                ok = listOf("none", "failed_once"),
                err = listOf("unknown"),
            )
            op<WaSyncEvictRequest, WaSyncEviction>(
                "wa_sync.media_evict",
                ok = listOf("under_the_cap", "recently_used", "starred", "stored_cap"),
            )
            op<WaSyncMediaPolicyRequest, WaSyncMediaPolicy>(
                "wa_sync.media_policy",
                ok = listOf("roaming", "low_space", "not_media"),
                err = listOf("unknown"),
            )
            op<WaSyncMediaEnqueueRequest, WaSyncMediaQueued>(
                "wa_sync.media_enqueue",
                ok = listOf("held", "tap", "cached"),
                err = listOf("no_network", "unknown"),
            )
            op<WaSyncMediaDueRequest, WaSyncMediaDue>("wa_sync.media_due", ok = listOf("held", "downloading", "empty"))
            op<WaSyncMediaSettleRequest, WaSyncMediaSettled>(
                "wa_sync.media_settle",
                ok = listOf("failed", "gone", "cancelled", "replaced"),
                err = listOf("unknown"),
            )
            op<WaSyncMediaKeepRequest, WaSyncMediaKept>(
                "wa_sync.media_keep",
                ok = listOf("pinned"),
                err = listOf("unknown"),
            )
            op<WaSyncReconcileRequest, WaSyncReconciled>("wa_sync.media_reconcile", ok = listOf("no_listing", "clean"))
            op<WaSyncMediaStatsRequest, WaSyncMediaStats>("wa_sync.media_stats", ok = listOf("empty"))
            op<WaSyncMediaCapRequest, WaSyncMediaCap>(
                "wa_sync.media_cap",
                ok = listOf("set", "no_limit"),
                err = listOf("both"),
            )
            op<WaSyncNoteStatusRequest, WaSyncStatusNoted>(
                "wa_sync.note_status",
                ok = listOf("same", "gap", "account_changed", "nothing"),
            )
            op<WaSyncAccountChangedRequest, WaSyncIdentityApplied>(
                "wa_sync.account_changed",
                ok = listOf("swept", "same"),
            )
            op<WaSyncHandleRequest, WaSyncHistoryAnswer>("wa_sync.history", ok = listOf("done", "none"))
            op<WaSyncWipeRequest, WaSyncWiped>("wa_sync.wipe", ok = listOf("never_linked"), err = listOf("no_target"))
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
