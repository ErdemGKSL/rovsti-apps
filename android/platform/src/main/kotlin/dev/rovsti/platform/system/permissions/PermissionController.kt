package dev.rovsti.platform.system.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import dev.rovsti.core.log.RovstiLog
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.PlatformPrefs
import dev.rovsti.platform.wire.WIRE_VERSION
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** The `permissions_changed` event kind Rust re-pulls its states on (AD-5). */
const val PERMISSIONS_CHANGED = "permissions_changed"

/**
 * One [StateFlow] of [PermissionState] per [PermissionGroup]. The host calls [refresh] on every `ON_RESUME` and
 * nowhere else; the checks read SharedPreferences and ask the system, so they run off the main thread. Create one
 * controller per process: it remembers which groups were granted while the process ran, which is how a revocation in
 * Settings shows as [PermissionState.RevokedWhileRunning].
 *
 * Ported from the GPUI bridge (crates/rovsti_android/kotlin/dev/rovsti/bridge): `permissionState`, `requestPermission`
 * and `emitPermissionsChanged`.
 */
class PermissionController(
    private val context: Context,
    val app: AppPermissions,
    private val special: SpecialAccess = AndroidSpecialAccess(context),
    private val prefs: SharedPreferences = context.getSharedPreferences(PlatformPrefs.BRIDGE, Context.MODE_PRIVATE),
    private val executor: Executor = defaultExecutor,
) {
    private val flows =
        PermissionGroup.entries.associateWith {
            MutableStateFlow<PermissionState>(PermissionState.NeverAsked)
        }
    private val grantedThisProcess =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<PermissionGroup>()

    /** The live state of [group]. Groups the app never asks for stay [PermissionState.NeverAsked] unless granted. */
    fun state(group: PermissionGroup): StateFlow<PermissionState> = flows.getValue(group).asStateFlow()

    /** Every `permissions_changed` payload, as the JSON Rust has always received. */
    val permissionsChanged: Flow<String> =
        PlatformEvents.events.filter { it.kind == PERMISSIONS_CHANGED }.map { it.json }

    /** Re-reads every group. Call from `ON_RESUME`; the work runs on a background thread. */
    fun refresh(activity: Activity) {
        try {
            executor.execute {
                try {
                    refreshNow { activity.shouldShowRequestPermissionRationale(it) }
                } catch (e: RuntimeException) {
                    RovstiLog.w("permissions", "permission check failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** The synchronous part of [refresh]: update the flows, then emit once. [rationale] is Android's per-permission answer. */
    fun refreshNow(rationale: (String) -> Boolean) {
        for (group in PermissionGroup.entries) {
            val missing = missing(group)
            // A group the app does not declare can never be held: it reads as a denied row nobody shows.
            val state =
                decide(
                    granted = missing.isEmpty() && app.permissionsOf(group).isNotEmpty(),
                    askedBefore = prefs.getBoolean(PermissionGroup.askedKey(group), false),
                    shouldShowRationale = missing.any(rationale),
                    wasGrantedThisProcess = group in grantedThisProcess,
                )
            if (state == PermissionState.Granted) grantedThisProcess += group else grantedThisProcess -= group
            flows.getValue(group).value = state
        }
        PlatformEvents.emit(PERMISSIONS_CHANGED, payload())
    }

    /** `{v, <group>: "granted" | "denied" | "permanently_denied" ..., full_screen_intent}`: today's payload. */
    fun payload(): String {
        val json = JSONObject().put("v", WIRE_VERSION)
        for (group in PermissionGroup.entries) json.put(group.id, flows.getValue(group).value.wire)
        json.put("full_screen_intent", special.fullScreenIntent())
        return json.toString()
    }

    private fun missing(group: PermissionGroup): List<String> =
        app.permissionsOf(group).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /**
     * Asks for what [group] is missing in one dialog. Launching it writes `asked_<group>` first, the same key the GPUI
     * bridge used, so the next [refresh] can tell "denied for good" from "never asked". The result maps permission to
     * grant; call [refresh] after it.
     */
    fun contract(group: PermissionGroup): ActivityResultContract<Unit, Map<String, Boolean>> =
        object : ActivityResultContract<Unit, Map<String, Boolean>>() {
            private val inner = ActivityResultContracts.RequestMultiplePermissions()

            override fun createIntent(
                context: Context,
                input: Unit,
            ): Intent {
                prefs.edit().putBoolean(PermissionGroup.askedKey(group), true).apply()
                return inner.createIntent(context, missing(group).toTypedArray())
            }

            override fun getSynchronousResult(
                context: Context,
                input: Unit,
            ): SynchronousResult<Map<String, Boolean>>? =
                if (missing(
                        group,
                    ).isEmpty()
                ) {
                    SynchronousResult(app.permissionsOf(group).associateWith { true })
                } else {
                    null
                }

            override fun parseResult(
                resultCode: Int,
                intent: Intent?,
            ): Map<String, Boolean> = inner.parseResult(resultCode, intent)
        }

    private companion object {
        val defaultExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "permissions").apply { isDaemon = true } }
        }
    }
}
