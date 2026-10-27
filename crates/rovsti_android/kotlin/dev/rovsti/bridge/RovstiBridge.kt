package dev.rovsti.bridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * Static entry points that Rust (`rovsti_android::bridge`) calls over JNI.
 *
 * Install once from the app's `Application.onCreate`:
 *
 * ```kotlin
 * class RovstiApp : Application() {
 *     override fun onCreate() { super.onCreate(); RovstiBridge.install(this, "rovsti_phone") }
 * }
 * ```
 *
 * Keep every method `@JvmStatic`, argument types to String/Int/Boolean, and
 * mirror any rename in Rust; R8 keeps them through `proguard-rovsti.pro`.
 */
@SuppressLint("StaticFieldLeak")
object RovstiBridge {
    private lateinit var app: Application
    private var foreground: Activity? = null

    @JvmStatic external fun nativeInit()

    /** `libName` is the Rust library GPUiForge packages: the app crate name, e.g. `rovsti_phone`. */
    fun install(application: Application, libName: String) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { foreground = activity }
            override fun onActivityPaused(activity: Activity) { if (foreground === activity) foreground = null }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // The GPUI host loads the library when its Activity starts; load it
        // here too so services and receivers can call in without a UI.
        // Loading twice is a no-op.
        System.loadLibrary(libName)
        nativeInit()
    }

    // ---- Roles --------------------------------------------------------

    @JvmStatic
    fun isRoleHeld(role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = app.getSystemService(RoleManager::class.java) ?: return false
        return manager.isRoleAvailable(role) && manager.isRoleHeld(role)
    }

    @JvmStatic
    fun requestRole(role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = app.getSystemService(RoleManager::class.java) ?: return false
        if (!manager.isRoleAvailable(role)) return false
        val activity = foreground ?: return false
        activity.runOnUiThread {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(manager.createRequestRoleIntent(role), REQUEST_ROLE)
        }
        return true
    }

    // ---- Telecom ------------------------------------------------------

    @JvmStatic
    @SuppressLint("MissingPermission")
    fun placeCall(number: String): Boolean {
        val uri = Uri.fromParts("tel", number, null)
        if (app.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            start(Intent(Intent.ACTION_DIAL, uri))
            return false
        }
        app.getSystemService(TelecomManager::class.java)?.placeCall(uri, Bundle()) ?: return false
        return true
    }

    // ---- Battery / Xiaomi ---------------------------------------------

    @JvmStatic
    fun isIgnoringBatteryOptimizations(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true

    @JvmStatic
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(): Boolean = start(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
    )

    /** `key` is one of `autostart`, `battery`, `permissions` (see `xiaomi.rs`). */
    @JvmStatic
    fun openXiaomiSetting(key: String): Boolean {
        val pkg = app.packageName
        val candidates = when (key) {
            "autostart" -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                component("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
                    .putExtra("package_name", pkg).putExtra("package_label", label()),
            )
            "battery" -> listOf(
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg).putExtra("package_label", label()),
            )
            "permissions" -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
                component("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
            )
            else -> emptyList()
        }
        for (intent in candidates) if (start(intent)) return true
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        return false
    }

    // ---- Helpers ------------------------------------------------------

    private const val REQUEST_ROLE = 0x524f // "RO"

    private fun label(): String = app.applicationInfo.loadLabel(app.packageManager).toString()

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun start(intent: Intent): Boolean {
        val context: Context = foreground ?: app.also { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
