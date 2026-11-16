package dev.rosty.messages.wa

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.OverlayController
import dev.rosty.messages.R
import dev.rosty.messages.conversation.RecordActions
import dev.rosty.platform.system.permissions.NextAction
import dev.rosty.platform.system.permissions.PermissionController
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.PermissionState
import dev.rosty.platform.system.permissions.SettingsKind
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.system.permissions.nextAction
import dev.rosty.platform.system.permissions.startFirst
import dev.rosty.shell.permissions.PermissionGate
import kotlinx.collections.immutable.persistentListOf

/** What a finger on the microphone leads to, by the state of the microphone permission. */
enum class MicStep {
    /** Granted: it records. */
    Record,

    /** Android still shows its dialog: the rationale sheet, then the dialog. */
    Ask,

    /** Denied for good or revoked: a short explanation with the way to Settings. */
    Settings,
}

/** Pure: [MicStep] of a permission state. */
fun micStep(state: PermissionState): MicStep =
    when (nextAction(state)) {
        NextAction.None -> MicStep.Record
        NextAction.Ask -> MicStep.Ask
        NextAction.OpenSettings -> MicStep.Settings
    }

/**
 * The microphone of the WhatsApp composer wired to the thread's recorder (E6-WA R3); null when the thread has none.
 *
 * The microphone permission is asked at the first hold, with the app's own flow: the rationale sheet of
 * [PermissionGate] (Continue opens Android's dialog, Not now closes it for this visit). Once Android no longer shows
 * the dialog, a hold opens a short alert that says why and leads to the app's page in Settings. A recording ends and
 * waits for review when the app goes to the background or the page is left.
 */
@Composable
fun rememberRecordActions(
    model: WaThreadViewModel,
    permissions: PermissionController,
    special: SpecialAccess,
    overlays: OverlayController,
): RecordActions? {
    val recording = model.recording ?: return null
    val context = LocalContext.current
    val res = LocalResources.current
    // Each ask is a fresh gate, so a sheet answered "Not now" comes back at the next hold.
    var asks by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        key(asks) {
            val now =
                PermissionGate(PermissionGroup.MICROPHONE, controller = permissions, onNotNow = { asking = false })
            LaunchedEffect(now) { if (now == PermissionState.Granted) asking = false }
        }
    }
    FollowLifecycle(recording)
    return remember(model, recording, permissions, special, overlays, context, res) {
        RecordActions(
            onStart = {
                when (micStep(permissions.state(PermissionGroup.MICROPHONE).value)) {
                    MicStep.Record -> {
                        // One sound at a time: a note that plays stops.
                        model.stopVoice()
                        recording.start()
                    }

                    MicStep.Ask -> {
                        asks++
                        asking = true
                        false
                    }

                    MicStep.Settings -> {
                        overlays.present(micDeniedAlert(context, special))
                        false
                    }
                }
            },
            onRelease = recording::release,
            onCancel = recording::cancel,
            onLock = recording::lock,
            onStop = recording::stop,
            onPreview = recording::togglePreview,
            onDelete = recording::delete,
            onSend = recording::send,
        )
    }
}

/** "Microphone is off": why, Not now, and Open Settings (the app's page, where the permission is turned on). */
fun micDeniedAlert(
    context: Context,
    special: SpecialAccess,
): AlertEntry {
    val res = context.resources
    return AlertEntry(
        heading = res.getString(R.string.wa_record_denied_title),
        message = res.getString(R.string.wa_record_denied_body),
        actions =
            persistentListOf(
                AlertAction(res.getString(R.string.wa_record_not_now), AlertRole.Cancel),
                AlertAction(res.getString(R.string.wa_record_settings)) {
                    context.startFirst(special.settingsChain(SettingsKind.AppDetails))
                },
            ),
    )
}

/** The app goes to the background or the page is left: a recording stops and waits for review. */
@Composable
private fun FollowLifecycle(recording: VoiceRecording) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(recording, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) recording.interrupt()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            recording.interrupt()
        }
    }
}
