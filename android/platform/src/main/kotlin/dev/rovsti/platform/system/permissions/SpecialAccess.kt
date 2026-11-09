package dev.rovsti.platform.system.permissions

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContract
import dev.rovsti.platform.location.exactAllowed
import dev.rovsti.platform.system.XiaomiOps

/** A Settings page the user can reach from a permission row. */
enum class SettingsKind(
    /** The Xiaomi page key (`autostart`, `battery`, `permissions`), or null for a stock page. */
    internal val xiaomiKey: String? = null,
) {
    AppDetails,
    DefaultApps,
    FullScreenIntent,
    ExactAlarms,
    Battery,
    XiaomiAutostart("autostart"),
    XiaomiBattery("battery"),
    XiaomiPermissions("permissions"),
}

/**
 * Access the user can revoke in Settings at any time, and the roles (ARCH 7.5). One interface so view models take a
 * [dev.rovsti.platform.system.FakeSpecialAccess]-style fake in tests.
 */
interface SpecialAccess {
    fun exactAlarms(): Boolean

    fun fullScreenIntent(): Boolean

    fun ignoresBatteryOptimisation(): Boolean

    /** `RoleManager.isRoleAvailable(role) && isRoleHeld(role)`; false below Android 10. */
    fun isRoleHeld(role: String): Boolean

    /** Asks for [role]; the result is whether the app holds it afterwards. */
    fun requestRoleContract(role: String): ActivityResultContract<Unit, Boolean>

    /** The pages to try for [kind], best first. The last one is App info, which always exists. */
    fun settingsChain(kind: SettingsKind): List<Intent>

    /** The best page for [kind]. */
    fun settingsIntent(kind: SettingsKind): Intent = settingsChain(kind).first()
}

/** Starts the first intent of [chain] some activity takes; false when none did. */
fun Context.startFirst(chain: List<Intent>): Boolean {
    for (intent in chain) {
        try {
            startActivity(if (this is Activity) intent else Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

class AndroidSpecialAccess(
    private val context: Context,
) : SpecialAccess {
    override fun exactAlarms(): Boolean = context.getSystemService(AlarmManager::class.java)?.exactAllowed() == true

    override fun fullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    override fun ignoresBatteryOptimisation(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    override fun isRoleHeld(role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        return manager.isRoleAvailable(role) && manager.isRoleHeld(role)
    }

    override fun requestRoleContract(role: String): ActivityResultContract<Unit, Boolean> =
        object : ActivityResultContract<Unit, Boolean>() {
            override fun createIntent(
                context: Context,
                input: Unit,
            ): Intent {
                val manager =
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.Q
                    ) {
                        context.getSystemService(RoleManager::class.java)
                    } else {
                        null
                    }
                // A role that cannot be offered sends the user to the default-apps page instead of crashing.
                return if (manager != null && manager.isRoleAvailable(role)) {
                    manager.createRequestRoleIntent(role)
                } else {
                    settingsIntent(SettingsKind.DefaultApps)
                }
            }

            override fun getSynchronousResult(
                context: Context,
                input: Unit,
            ): SynchronousResult<Boolean>? = if (isRoleHeld(role)) SynchronousResult(true) else null

            override fun parseResult(
                resultCode: Int,
                intent: Intent?,
            ): Boolean = resultCode == Activity.RESULT_OK && isRoleHeld(role)
        }

    override fun settingsChain(kind: SettingsKind): List<Intent> {
        val pkg = context.packageName
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val page: List<Intent> =
            when (kind) {
                SettingsKind.AppDetails -> {
                    emptyList()
                }

                SettingsKind.DefaultApps -> {
                    listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }

                SettingsKind.FullScreenIntent -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
                    } else {
                        emptyList()
                    }
                }

                SettingsKind.ExactAlarms -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
                    } else {
                        emptyList()
                    }
                }

                SettingsKind.Battery -> {
                    @Suppress("BatteryLife")
                    listOf(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                }

                SettingsKind.XiaomiAutostart,
                SettingsKind.XiaomiBattery,
                SettingsKind.XiaomiPermissions,
                -> {
                    XiaomiOps.settingsCandidates(
                        kind.xiaomiKey!!,
                        pkg,
                        context.applicationInfo.loadLabel(context.packageManager).toString(),
                    )
                }
            }
        return page + details
    }
}
