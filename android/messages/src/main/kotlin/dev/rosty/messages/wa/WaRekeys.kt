package dev.rosty.messages.wa

import dev.rosty.core.getOrNull
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.WaSyncRekeyed
import dev.rosty.core.ops.WaSyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** The rows of the chat [from] now live under [into]: one person had two chats (a LID and a number, an old number). */
data class Rekey(
    val from: String,
    val into: String,
)

/**
 * One kind of state the app keeps per chat outside the store (a draft, a mute, a notification). [move] carries it
 * from the chat [Rekey.from] to [Rekey.into]. It must be safe to run twice for the same step: the second run finds
 * nothing left under `from`.
 */
fun interface RekeyMover {
    suspend fun move(step: Rekey)
}

/** The `seq` of the last key change followed, per WhatsApp instance. */
interface RekeyCursor {
    suspend fun read(instanceId: String): Long

    suspend fun write(
        instanceId: String,
        seq: Long,
    )
}

/** [RekeyCursor] in memory: tests, and a run without storage. */
class MemoryRekeyCursor : RekeyCursor {
    private val seqs = HashMap<String, Long>()

    override suspend fun read(instanceId: String): Long = synchronized(seqs) { seqs[instanceId] ?: 0L }

    override suspend fun write(
        instanceId: String,
        seq: Long,
    ) {
        synchronized(seqs) { seqs[instanceId] = seq }
    }
}

/**
 * [RekeyCursor] as a small JSON file (`wa-rekeys.json`: instance id to `seq`), like the other small state of the
 * module. A file that cannot be read starts at 0: every step is followed again, which changes nothing twice.
 */
class FileRekeyCursor(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RekeyCursor {
    private var seqs: MutableMap<String, Long>? = null

    private fun loaded(): MutableMap<String, Long> =
        seqs ?: try {
            if (file.isFile) JSON.decodeFromString(FORMAT, file.readText()).toMutableMap() else mutableMapOf()
        } catch (_: IOException) {
            mutableMapOf()
        } catch (_: IllegalArgumentException) {
            mutableMapOf()
        }.also { seqs = it }

    override suspend fun read(instanceId: String): Long =
        withContext(io) { synchronized(this@FileRekeyCursor) { loaded()[instanceId] ?: 0L } }

    override suspend fun write(
        instanceId: String,
        seq: Long,
    ) {
        withContext(io) {
            synchronized(this@FileRekeyCursor) {
                val all = loaded()
                all[instanceId] = seq
                try {
                    file.parentFile?.mkdirs()
                    val temp = File(file.path + ".tmp")
                    temp.writeText(JSON.encodeToString(FORMAT, all))
                    if (!temp.renameTo(file)) {
                        file.writeText(temp.readText())
                        temp.delete()
                    }
                } catch (e: IOException) {
                    // Kept in memory for this run; the next start follows the steps again.
                    RostyLog.w(TAG, "the rekey cursor could not be written", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "wa-rekeys"
        val JSON = Json { ignoreUnknownKeys = true }
        val FORMAT = MapSerializer(String.serializer(), Long.serializer())
    }
}

/**
 * The one place the app follows a merge of two WhatsApp chats (E6-WA R1). The store moves its own rows when it learns
 * that a LID and a number are one person, or that a contact changed number, and logs the step
 * (`from_key -> into_key`, with a `seq`). Everything kept per chat outside the store is moved here, by the [movers]
 * the container lists: the draft, the mute and its end, the notification and its digest, the reminders, the
 * conversation on screen. Then [rekeys] tells the open screens.
 *
 * The store's log is the source: an answer of the engine that lists steps ([follow]) only says "there are new ones",
 * and the steps after the stored cursor are read from the log, in order. So a step is followed once, in order,
 * whoever reported it, and a merge that happened while the app was not running (at the store's open, in the outbox
 * job's process) is followed by [replay] when the store opens. A mover that fails stops the run before the cursor
 * moves: the step is tried again next time.
 */
class WaRekeys(
    private val cursor: RekeyCursor = MemoryRekeyCursor(),
    private val movers: List<RekeyMover> = emptyList(),
) {
    private val running = Mutex()
    private val bus =
        MutableSharedFlow<Rekey>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every key change, after what is kept per chat was moved: an open thread swaps its key. */
    val rekeys: SharedFlow<Rekey> = bus.asSharedFlow()

    /** The engine reported [steps] (an apply, a stored chat list or page, a catch-up, an observe): follow the log. */
    suspend fun follow(
        store: WaSyncStore,
        steps: List<WaSyncRekeyed>,
    ) {
        if (steps.isNotEmpty()) replay(store)
    }

    /** Follows every step logged after the stored cursor. Run it when the store opens. Answers the steps followed. */
    suspend fun replay(store: WaSyncStore): List<Rekey> =
        running.withLock {
            val instance = store.instanceId
            val from = cursor.read(instance)
            val logged =
                store
                    .rekeyedSince(from)
                    .getOrNull()
                    .orEmpty()
                    .sortedBy { it.seq }
            val done = ArrayList<Rekey>()
            for (step in logged) {
                if (step.seq <= from || step.fromKey == step.intoKey) continue
                val rekey = Rekey(step.fromKey, step.intoKey)
                if (!moved(rekey)) break
                cursor.write(instance, step.seq)
                done += rekey
                bus.tryEmit(rekey)
            }
            done
        }

    /**
     * The store of [instanceId] was emptied or deleted (another account, an unlink): its merge log starts again at
     * 0, so the cursor does too. Otherwise the first merges of the new account would be skipped.
     */
    suspend fun forget(instanceId: String) {
        running.withLock { cursor.write(instanceId, 0L) }
    }

    private suspend fun moved(step: Rekey): Boolean {
        for (mover in movers) {
            try {
                mover.move(step)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                RostyLog.w(TAG, "a rekey could not be followed", e)
                return false
            }
        }
        return true
    }

    companion object {
        private const val TAG = "wa-rekeys"
        private const val BUFFER = 64

        /** The file of [FileRekeyCursor] in the app's no-backup directory. */
        const val FILE = "wa-rekeys.json"

        /** The draft and notification tag of a WhatsApp chat. */
        fun tagOf(jid: String): String = "wa:$jid"
    }
}
