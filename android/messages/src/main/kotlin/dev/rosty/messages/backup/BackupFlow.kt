package dev.rosty.messages.backup

import androidx.compose.runtime.Immutable
import dev.rosty.core.model.messages.Strength

// The encrypted backup flow (AD-MSG7) as a pure state machine, ported from the GPUI app's `apps/messages/src/backup_ui.rs`.
// The export form checks the passphrase twice and warns once about a weak one; a picked file asks for its passphrase (a
// wrong one asks again, it never gives up); a readable backup shows what a restore would do before anything is written.
// No platform, no Rust: the work is BackupEngine, the files are BackupFiles.

/** What is wrong with the export form. */
enum class ExportIssue {
    Empty,
    Mismatch,

    /** Shown once: pressing Export again with the same passphrase uses it anyway. */
    Weak,
}

/** What the app is busy with. */
enum class BackupBusy {
    /** Reading the messages, sealing. */
    Sealing,

    /** The system "save as" is open, or the file is being written. */
    Saving,

    /** The system file picker is open, or the picked file is being read. */
    Picking,

    /** Decrypting and comparing with the phone. */
    Opening,

    /** Writing messages into the provider. */
    Restoring,
}

/** Why the flow stopped. */
enum class BackupFailure {
    /** The file could not be read, or it is larger than 64 MiB. */
    Unreadable,
    NotABackup,
    NewerVersion,
    Corrupt,

    /** Restoring messages needs the default-SMS-app role. */
    NeedsRole,

    /** The messages could not be read, sealed or written. */
    Failed,

    /** The chosen place refused the file. */
    NotSaved,
}

/** What a restore would do, shown before it happens. */
@Immutable
data class RestorePreview(
    /** Messages that are not on this phone yet. */
    val insert: Int,
    /** Messages already here, left alone. */
    val skipped: Int,
    /** Scheduled SMS that are still ahead. */
    val scheduled: Int,
    /** Spam rules and always-allowed senders in the backup (missing ones are added, nothing is removed). */
    val spamRules: Int,
)

/** How a finished flow ends. */
sealed interface BackupDone {
    data object Saved : BackupDone

    data class Restored(
        val inserted: Int,
        val skipped: Int,
    ) : BackupDone
}

@Immutable
sealed interface BackupPhase {
    data object Idle : BackupPhase

    /** The export form is open. */
    data class Export(
        val issue: ExportIssue? = null,
    ) : BackupPhase

    data class Busy(
        val what: BackupBusy,
    ) : BackupPhase

    /** A file was picked and wants its passphrase; [wrong] after a wrong one. */
    data class ImportAsk(
        val wrong: Boolean = false,
    ) : BackupPhase

    data class Preview(
        val preview: RestorePreview,
    ) : BackupPhase

    data class Done(
        val done: BackupDone,
    ) : BackupPhase

    data class Failed(
        val failure: BackupFailure,
    ) : BackupPhase
}

/** The transitions of [BackupPhase]; every function answers the next phase. */
object BackupFlow {
    fun isBusy(phase: BackupPhase) = phase is BackupPhase.Busy

    fun beginExport(phase: BackupPhase): BackupPhase = if (isBusy(phase)) phase else BackupPhase.Export()

    /** The form's fields changed: the last complaint no longer applies, and a changed passphrase is warned about again. */
    fun edited(phase: BackupPhase): BackupPhase = if (phase is BackupPhase.Export) BackupPhase.Export() else phase

    /**
     * Export was pressed with [passphrase], [confirm] and the core's [strength] of the passphrase (null when unknown).
     * Answers the next phase; [BackupPhase.Busy] with [BackupBusy.Sealing] means the backup may be made now.
     */
    fun submitExport(
        phase: BackupPhase,
        passphrase: String,
        confirm: String,
        strength: Strength?,
    ): BackupPhase {
        if (phase !is BackupPhase.Export) return phase
        val issue = checkExport(passphrase, confirm, strength, warned = phase.issue == ExportIssue.Weak)
        return if (issue == null) BackupPhase.Busy(BackupBusy.Sealing) else BackupPhase.Export(issue)
    }

    /** The export form's checks, in the order a person fixes them. A weak passphrase passes once [warned]. */
    fun checkExport(
        passphrase: String,
        confirm: String,
        strength: Strength?,
        warned: Boolean,
    ): ExportIssue? =
        when {
            passphrase.isEmpty() -> ExportIssue.Empty
            passphrase != confirm -> ExportIssue.Mismatch
            strength == Strength.Weak && !warned -> ExportIssue.Weak
            else -> null
        }

    /** The picker returned a readable file ([ok]) or not. */
    fun picked(ok: Boolean): BackupPhase =
        if (ok) BackupPhase.ImportAsk() else BackupPhase.Failed(BackupFailure.Unreadable)

    fun cancel(phase: BackupPhase): BackupPhase = if (isBusy(phase)) phase else BackupPhase.Idle
}
