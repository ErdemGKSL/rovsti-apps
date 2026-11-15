package dev.rosty.messages.backup

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.messages.BackupBody
import dev.rosty.core.model.messages.BackupExportRequest
import dev.rosty.core.model.messages.BackupParseRequest
import dev.rosty.core.model.messages.MmsRow
import dev.rosty.core.model.messages.RestorePlan
import dev.rosty.core.model.messages.RestorePlanRequest
import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduleValidateRequest
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.messages.SmsRow
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.model.messages.Strength
import dev.rosty.core.model.messages.StrengthRequest
import dev.rosty.core.ops.MessagesOps
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.platform.sms.SmsResult
import java.util.Base64

/** The parts of the app a backup reads and a restore writes, besides the SMS provider. */
interface BackupSources {
    /** The spam rules now; null while the preferences cannot be read. */
    suspend fun spamRules(): SpamRules?

    /** Adds a backed-up rule; false when it was already there or refused. */
    suspend fun addRule(rule: dev.rosty.core.model.messages.SpamRule): Boolean

    /** Adds a backed-up always-allowed sender; false when it was already there. */
    suspend fun allow(sender: String): Boolean

    /** The scheduled SMS waiting now. */
    suspend fun scheduled(): List<ScheduledSms>

    /** Schedules a backed-up SMS again (with its alarm); false when it could not be. */
    suspend fun reschedule(item: ScheduledSms): Boolean

    /** Whether this app holds the SMS role (needed to write messages). */
    fun roleHeld(): Boolean
}

/** A backup opened with its passphrase: what it holds and what a restore would write. */
class OpenedBackup(
    val body: BackupBody,
    val plan: RestorePlan,
    val scheduled: List<ScheduledSms>,
    val preview: RestorePreview,
)

/** What opening a backup answered. */
sealed interface OpenResult {
    data class Opened(
        val backup: OpenedBackup,
    ) : OpenResult

    data object WrongPassphrase : OpenResult

    data class Unusable(
        val failure: BackupFailure,
    ) : OpenResult
}

/**
 * The work of the encrypted backup (AD-MSG7): every decision is a `msg_backup.*` op in Rust (sealing with a key from the
 * passphrase, opening, which rows are missing, the passphrase strength), the spam merge goes through `spam.add_rule` and
 * `messages.prefs_edit` ([BackupSources]), a scheduled SMS is re-checked by `schedule.validate`. Kotlin reads the SMS
 * provider, makes the random salt and nonce, and writes back what the plan says.
 */
class BackupEngine(
    private val ops: MessagesOps,
    private val sms: SmsRepository,
    private val sources: BackupSources,
    private val random: (Int) -> ByteArray,
    private val now: () -> Long,
    private val appVersion: String,
    /** Key derivation rounds; null is the core's 600 000 (tests pass fewer). */
    private val iterations: Int? = null,
) {
    suspend fun strength(passphrase: String): Strength? =
        (ops.passphraseStrength(StrengthRequest(passphrase)) as? Outcome.Ok)?.value?.strength

    /**
     * Every SMS and MMS on the phone, newest first; empty without `READ_SMS`. The provider is read in pages by date,
     * MMS as text parts and addresses only (no attachment is copied to the cache).
     */
    suspend fun snapshot(): Pair<List<SmsRow>, List<MmsRow>> = smsRows() to mmsRows()

    private suspend fun smsRows(): List<SmsRow> =
        ArrayList<SmsRow>().also { all -> sms.backupSms().collect { all += it } }

    private suspend fun mmsRows(): List<MmsRow> =
        ArrayList<MmsRow>().also { all -> sms.backupMms().collect { all += it } }

    /** The encrypted backup file of everything now; null when it could not be made. */
    suspend fun seal(passphrase: String): ByteArray? {
        val (smsRows, mmsRows) = snapshot()
        val body =
            BackupBody(
                createdMs = now(),
                appVersion = appVersion,
                sms = smsRows,
                mms = mmsRows,
                spam = sources.spamRules() ?: return null,
                scheduled = sources.scheduled(),
            )
        val request =
            BackupExportRequest(
                body = body,
                passphrase = passphrase,
                salt = b64(random(SALT_BYTES)),
                nonce = b64(random(NONCE_BYTES)),
                iterations = iterations,
            )
        val answer = (ops.backupExport(request) as? Outcome.Ok)?.value ?: return null
        return Base64.getDecoder().decode(answer.bytes)
    }

    /** Opens [bytes] with [passphrase] and compares it with the phone. */
    suspend fun open(
        bytes: ByteArray,
        passphrase: String,
    ): OpenResult {
        val body =
            when (val parsed = ops.backupParse(BackupParseRequest(b64(bytes), passphrase))) {
                is Outcome.Ok -> parsed.value.body
                is Outcome.Err -> return failureOf(parsed.error)
            }
        // The plan compares SMS only: the MMS are not read.
        val existing = smsRows()
        val plan =
            (ops.backupPlan(RestorePlanRequest(body, existing)) as? Outcome.Ok)?.value
                ?: return OpenResult.Unusable(BackupFailure.Failed)
        val scheduled = restorable(body.scheduled)
        val preview =
            RestorePreview(
                insert = plan.insert.size,
                skipped = plan.skippedDuplicates,
                scheduled = scheduled.size,
                spamRules = body.spam.rules.size + body.spam.allow.size,
            )
        return OpenResult.Opened(OpenedBackup(body, plan, scheduled, preview))
    }

    /**
     * Writes the missing messages into the provider, then folds the backup's spam rules into the current ones (nothing
     * is removed) and schedules its future SMS again. Without the SMS role nothing is written.
     */
    suspend fun restore(backup: OpenedBackup): BackupPhase {
        if (backup.plan.insert.isNotEmpty() && !sources.roleHeld()) {
            return BackupPhase.Failed(BackupFailure.NeedsRole)
        }
        var inserted = 0
        var skipped = backup.plan.skippedDuplicates
        if (backup.plan.insert.isNotEmpty()) {
            when (val written = sms.insertRestored(backup.plan.insert)) {
                is SmsResult.Ok -> {
                    if (written.value.roleMissing) return BackupPhase.Failed(BackupFailure.NeedsRole)
                    inserted = written.value.inserted
                    skipped += written.value.skipped
                }

                is SmsResult.Failed -> {
                    return BackupPhase.Failed(
                        if (written.reason == SmsResult.Reason.Permission) {
                            BackupFailure.NeedsRole
                        } else {
                            BackupFailure.Failed
                        },
                    )
                }
            }
        }
        for (rule in backup.body.spam.rules) sources.addRule(rule)
        for (sender in backup.body.spam.allow) sources.allow(sender)
        val waiting = sources.scheduled()
        for (item in backup.scheduled) {
            val known = waiting.any { it.atMs == item.atMs && it.body == item.body && it.target == item.target }
            if (!known) sources.reschedule(item)
        }
        return BackupPhase.Done(BackupDone.Restored(inserted, skipped))
    }

    /** The backed-up scheduled SMS the core would still accept now (WhatsApp items stay with the gateway). */
    private suspend fun restorable(items: List<ScheduledSms>): List<ScheduledSms> {
        val at = now()
        return items.filter { item ->
            val answer =
                if (item.target is ScheduleTarget.Sms) {
                    (ops.scheduleValidate(ScheduleValidateRequest(item.atMs, at, item.body)) as? Outcome.Ok)?.value
                } else {
                    null
                }
            answer != null && answer.error == null
        }
    }

    private fun failureOf(error: RustError): OpenResult =
        when {
            error is RustError.Auth -> {
                OpenResult.WrongPassphrase
            }

            error.reason == "not_a_backup" -> {
                OpenResult.Unusable(BackupFailure.NotABackup)
            }

            error.reason == "newer_version" -> {
                OpenResult.Unusable(BackupFailure.NewerVersion)
            }

            error.reason == "corrupt" -> {
                OpenResult.Unusable(BackupFailure.Corrupt)
            }

            else -> {
                OpenResult.Unusable(BackupFailure.Failed)
            }
        }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        /** The backup file's name for the local day: `rosty-messages-20261105.rmb` (the GPUI app's name). */
        fun fileName(day: java.time.LocalDate): String =
            "rosty-messages-%04d%02d%02d.rmb".format(day.year, day.monthValue, day.dayOfMonth)

        const val MIME = "application/octet-stream"
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
    }
}
