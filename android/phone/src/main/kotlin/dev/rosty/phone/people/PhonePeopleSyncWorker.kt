package dev.rosty.phone.people

import android.content.Context
import android.content.Intent
import androidx.work.WorkerParameters
import dev.rosty.people.sync.ForegroundPeopleSyncWorker
import dev.rosty.people.sync.PeopleSyncHost
import dev.rosty.people.sync.PeopleSyncWork
import dev.rosty.phone.MainActivity
import dev.rosty.phone.PhoneContainer
import dev.rosty.shell.HasContainer

/**
 * The people sync of Phone without Contacts as a foreground worker (people sync v2 S11): the same
 * [ForegroundPeopleSyncWorker] as Contacts', over [StandalonePeople]. WorkManager creates it by name, so R8 keeps the
 * constructor (`proguard-rules.pro`). When Contacts is installed Phone reads its provider and syncs nothing: the work
 * fails at once (no host).
 */
class PhonePeopleSyncWorker(
    context: Context,
    params: WorkerParameters,
) : ForegroundPeopleSyncWorker(context, params) {
    override suspend fun host(): PeopleSyncHost? =
        ((applicationContext as? HasContainer<*>)?.container as? PhoneContainer)?.standaloneSync()

    override fun openIntent(): Intent =
        Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    override fun continueLater(delayMs: Long) =
        PeopleSyncWork.continueLater(applicationContext, PhonePeopleSyncWorker::class.java, delayMs)

    companion object {
        /** Starts a run of Phone's own people sync (`SyncWire.START_*`) in this worker. */
        fun startNow(
            context: Context,
            start: String,
        ) = PeopleSyncWork.startNow(context, PhonePeopleSyncWorker::class.java, start)
    }
}
