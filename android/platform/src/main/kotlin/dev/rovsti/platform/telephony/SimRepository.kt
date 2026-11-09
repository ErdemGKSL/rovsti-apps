package dev.rovsti.platform.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SubscriptionManager
import dev.rovsti.core.model.phone.PhoneAccounts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/** The call-capable accounts (SIMs) and the user's default for outgoing calls. */
interface SimRepository {
    /**
     * The accounts, read on collect and again after every subscription or default-account change. [PhoneAccounts] is
     * what `sim.build` takes to make the chips; it is empty when `READ_PHONE_STATE` is not granted.
     */
    fun accounts(): Flow<PhoneAccounts>

    /** The S5 diagnostics lines for the accounts now (see [SimProbe]); [placedKey] and [logKey] as there. */
    suspend fun probe(
        placedKey: String?,
        logKey: String?,
    ): List<String>
}

/**
 * [SimRepository] on Telecom and `SubscriptionManager`. [changes] fires on a subscription change
 * ([systemChanges]); a burst is one re-query.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AndroidSimRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val changes: Flow<Unit> = systemChanges(context),
    private val collect: (Context) -> PhoneAccounts? = SimAccounts::collect,
) : SimRepository {
    companion object {
        const val CHANGE_DEBOUNCE_MS = 100L

        /** Protected broadcasts of the user changing the default SIM (system-only; no permission needed to listen). */
        const val ACTION_DEFAULT_SUBSCRIPTION_CHANGED = "android.intent.action.ACTION_DEFAULT_SUBSCRIPTION_CHANGED"
        const val ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED"

        /** Subscription listener plus the default-subscription broadcasts, as a conflated flow of `Unit`. */
        fun systemChanges(context: Context): Flow<Unit> =
            callbackFlow {
                val subs = context.getSystemService(SubscriptionManager::class.java)
                val executor = Executor { it.run() }
                val listener =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            trySend(Unit)
                        }
                    }
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            c: Context?,
                            intent: Intent?,
                        ) {
                            trySend(Unit)
                        }
                    }
                val registered =
                    try {
                        subs?.addOnSubscriptionsChangedListener(executor, listener)
                        true
                    } catch (_: SecurityException) {
                        false
                    } catch (_: RuntimeException) {
                        false
                    }
                val filter =
                    IntentFilter().apply {
                        addAction(ACTION_DEFAULT_SUBSCRIPTION_CHANGED)
                        addAction(ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED)
                    }
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                awaitClose {
                    if (registered) subs?.removeOnSubscriptionsChangedListener(listener)
                    context.unregisterReceiver(receiver)
                }
            }.buffer(Channel.CONFLATED)
    }

    override fun accounts(): Flow<PhoneAccounts> =
        changes
            .debounce(CHANGE_DEBOUNCE_MS)
            .onStart { emit(Unit) }
            .conflate()
            .mapLatest { collect(context) ?: PhoneAccounts() }
            .flowOn(io)

    override suspend fun probe(
        placedKey: String?,
        logKey: String?,
    ): List<String> =
        withContext(io) {
            val now = collect(context) ?: PhoneAccounts()
            SimProbe.lines(now.accounts, now.defaultKey, placedKey, logKey)
        }
}
