package dev.rosty.messages.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.schedule.ScheduleDest
import dev.rosty.messages.schedule.ScheduleOutcome
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.platform.files.Documents
import dev.rosty.platform.files.ExportDocument
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.files.FilesRepository
import dev.rosty.platform.files.ImportDocument
import dev.rosty.platform.files.ImportKind
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId

/** [BackupFiles] over the shared [FilesRepository]: a staging file in [cacheDir] for both directions, then deleted. */
class AndroidBackupFiles(
    private val files: FilesRepository,
    private val cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupFiles {
    override suspend fun write(
        target: Uri,
        bytes: ByteArray,
    ): Boolean {
        val staged = withContext(io) { File.createTempFile("backup-", ".rmb", cacheDir).apply { writeBytes(bytes) } }
        return try {
            files.writeExport(target, path = staged.absolutePath) is FileResult.Ok
        } finally {
            withContext(io) { staged.delete() }
        }
    }

    override suspend fun read(source: Uri): PickedFile? {
        val read = files.readUri(source, Documents.MAX_FILE_BYTES, text = false) as? FileResult.Ok ?: return null
        val path = read.path ?: return null
        return withContext(io) {
            val file = File(path)
            try {
                PickedFile(file.readBytes(), read.name)
            } catch (_: java.io.IOException) {
                null
            } finally {
                file.delete()
            }
        }
    }
}

/** What a backup reads and writes in the app: the spam rules (through [dev.rosty.messages.spam.SpamActions]) and the schedule. */
internal class ContainerBackupSources(
    private val container: MessagesContainer,
) : BackupSources {
    override suspend fun spamRules(): SpamRules? {
        container.store.load()
        return container.store.prefs.value
            ?.spam
    }

    override suspend fun addRule(rule: SpamRule): Boolean = container.spamActions.saveRule(null, rule) == null

    override suspend fun allow(sender: String): Boolean = container.spamActions.allow(sender)

    override suspend fun scheduled(): List<ScheduledSms> = container.schedule.state.value.local

    override suspend fun reschedule(item: ScheduledSms): Boolean {
        val target = item.target as? ScheduleTarget.Sms ?: return false
        return container.schedule.schedule(ScheduleDest.Sms(target.to, target.subId), item.body, item.atMs) is
            ScheduleOutcome.Scheduled
    }

    override fun roleHeld(): Boolean = container.smsRole.value
}

private fun Context.appVersion(): String =
    try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        ""
    }.ifEmpty { Build.UNKNOWN }

/** The Backup page's view model, kept by the activity. */
@Composable
fun rememberBackupViewModel(container: MessagesContainer): BackupViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    val random = SecureRandom()
                    val engine =
                        BackupEngine(
                            ops = container.ops,
                            sms = container.sms,
                            sources = ContainerBackupSources(container),
                            random = { n -> ByteArray(n).also(random::nextBytes) },
                            now = SystemRostyClock::nowMillis,
                            appVersion = container.context.appVersion(),
                        )
                    BackupViewModel(
                        engine = engine,
                        files = AndroidBackupFiles(container.files, container.context.cacheDir),
                        roleHeld = { container.smsRole.value },
                        today = {
                            Instant
                                .ofEpochMilli(
                                    SystemRostyClock.nowMillis(),
                                ).atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        },
                    )
                }
            }
        ViewModelProvider(owner, factory)[BackupViewModel::class.java]
    }
}

/** Presents the backup sheet; it follows [model]'s phase and closes itself once the phase leaves it. */
internal fun presentBackupSheet(
    overlays: OverlayController,
    title: String,
    model: BackupViewModel,
    fields: BackupFields,
    onGone: () -> Unit,
) {
    overlays.present(
        SheetEntry(
            title = title,
            detents = persistentListOf(Detent.Large),
            onDismissed = {
                model.onAction(BackupAction.Cancel)
                fields.clear()
                onGone()
            },
        ) { sheet ->
            val ui by model.state.collectAsState()
            val open =
                ui.phase.let {
                    it is BackupPhase.Export || it is BackupPhase.ImportAsk ||
                        it is BackupPhase.Preview
                }
            val working =
                ui.phase.let {
                    it is BackupPhase.Busy && (it.what == BackupBusy.Sealing || it.what == BackupBusy.Opening)
                }
            LaunchedEffect(open, working) { if (!open && !working) sheet.dismiss() }
            BackupSheetContent(ui, fields, model::onAction, onClose = { sheet.dismiss() })
        },
    )
}

/** Settings, Backup: the page over its view model, the system pickers, the sheet and the pills. */
@Composable
fun BackupEntry(
    container: MessagesContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val model = rememberBackupViewModel(container)
    val ui by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val res = LocalResources.current
    val fields = remember { BackupFields() }
    var sheetUp by remember { mutableStateOf(false) }
    val saveAs =
        rememberLauncherForActivityResult(ExportDocument(BackupEngine.MIME)) { result ->
            model.onAction(BackupAction.SaveTo((result as? FileResult.Ok)?.uri))
        }
    val pick =
        rememberLauncherForActivityResult(ImportDocument(kind = ImportKind.Attachment)) { result ->
            model.onAction(BackupAction.Picked((result as? FileResult.Ok)?.uri))
        }
    LaunchedEffect(model) {
        model.effect.collect { effect ->
            when (effect) {
                is BackupEffect.SaveAs -> saveAs.launch(effect.fileName)
                is BackupEffect.Finished -> overlays.showToast(finishedText(effect.phase, res))
            }
        }
    }
    val wantsSheet = ui.phase is BackupPhase.Export || ui.phase is BackupPhase.ImportAsk
    LaunchedEffect(wantsSheet) {
        if (wantsSheet && !sheetUp) {
            sheetUp = true
            presentBackupSheet(overlays, res.getString(R.string.backup_title), model, fields) { sheetUp = false }
        }
    }
    BackupPage(
        ui = ui,
        onExport = {
            fields.clear()
            model.onAction(BackupAction.BeginExport)
        },
        onImport = {
            fields.clear()
            model.onAction(BackupAction.PickStarted)
            pick.launch(Unit)
        },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
