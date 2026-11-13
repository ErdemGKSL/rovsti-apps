package dev.rovsti.phone.settings.blocked

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.design.time.SystemRovstiClock
import dev.rovsti.phone.PhoneContainer
import dev.rovsti.phone.data.DeviceLocaleTime
import dev.rovsti.phone.findComponentActivity
import dev.rovsti.platform.files.ExportDocument
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.ImportDocument
import dev.rovsti.platform.files.ImportKind
import dev.rovsti.platform.files.ShareIntents
import kotlinx.coroutines.launch

/**
 * Settings, Blocked numbers on the app: the view model (kept by the activity, like the other settings pages), the file
 * pickers of U38 (export a CSV, import a list; the core's `parse_file` reads what comes back), the share sheet of U27
 * and the number sheet a tap on a blocked call opens ([onOpenNumber]).
 */
@Composable
fun PhoneBlockedEntry(
    container: PhoneContainer,
    onOpenNumber: (String) -> Unit,
) {
    val context = LocalContext.current
    val owner = context.findComponentActivity()
    val model =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        BlockedViewModel(
                            block = container.block,
                            ops = container.ops,
                            callLog = container.callLog,
                            localeTime = { DeviceLocaleTime.now(container.context, SystemRovstiClock.nowMillis()) },
                        )
                    }
                }
            ViewModelProvider(owner, factory)[BlockedViewModel::class.java]
        }
    val scope = rememberCoroutineScope()
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val exporter =
        rememberLauncherForActivityResult(ExportDocument("text/csv")) { result ->
            val csv = pendingCsv ?: return@rememberLauncherForActivityResult
            pendingCsv = null
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val written = container.files.writeExport(uri, text = csv)
                model.onAction(
                    BlockedAction.FileNotice(
                        if (written is FileResult.Ok) BlockedNotice.Exported else BlockedNotice.ExportFailed,
                    ),
                )
            }
        }
    val importContract = remember { ImportDocument(kind = ImportKind.Calls) }
    val importer =
        rememberLauncherForActivityResult(importContract) { result ->
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                when (val read = container.files.readUri(uri, importContract.cap, text = true)) {
                    is FileResult.Ok -> model.onAction(BlockedAction.Import(read.text.orEmpty()))
                    FileResult.TooLarge -> model.onAction(BlockedAction.FileNotice(BlockedNotice.TooLarge))
                    else -> model.onAction(BlockedAction.FileNotice(BlockedNotice.ImportFailed))
                }
            }
        }
    val host =
        remember(context, container, exporter, importer, onOpenNumber) {
            AndroidBlockedHost(
                context = context,
                copyText = { container.clipboard.copy(it) },
                saveCsv = { name, csv ->
                    pendingCsv = csv
                    exporter.launch(name)
                },
                pickFile = { importer.launch(Unit) },
                openNumber = onOpenNumber,
            )
        }
    BlockedScreen(model, host)
}

/** [BlockedHost] on Android: the share chooser, the pickers and the clipboard. */
private class AndroidBlockedHost(
    private val context: Context,
    private val copyText: (String) -> Unit,
    private val saveCsv: (String, String) -> Unit,
    private val pickFile: () -> Unit,
    private val openNumber: (String) -> Unit,
) : BlockedHost {
    override fun share(text: String) {
        try {
            context.startActivity(
                Intent.createChooser(ShareIntents.text(text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            // No app handles it: nothing to show.
        }
    }

    override fun export(
        fileName: String,
        csv: String,
    ) = saveCsv(fileName, csv)

    override fun importFile() = pickFile()

    override fun copy(text: String) = copyText(text)

    override fun openNumber(number: String) = openNumber.invoke(number)
}
