package dev.rovsti.bridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
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
import org.json.JSONObject

/**
 * Static entry points that Rust (`rovsti_android::bridge`) calls over JNI.
 *
 * Install once from the app's `Application.onCreate`:
 *
 * ```kotlin
 * class RovstiApp : Application() {
 *     override fun onCreate() { super.onCreate(); RovstiBridge.install(this) }
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

    /** True once `nativeInit` has run; guards [emit] (AD-5). */
    @Volatile private var nativeReady = false

    /** One-shot events wait here until Rust's session is live (story 16.2). */
    private val mailbox = Mailbox { kind, json -> emit(kind, json) }

    /** Rust's session is live and the activity is resumed ([uiAttached]); false answers system pickers itself. */
    @Volatile var uiReady = false
        private set

    /** The app's call owner, set once from `Application.onCreate`. */
    @Volatile var calls: CallsApi? = null

    @JvmStatic external fun nativeInit()

    /** Enqueues one event in Rust. Call only through [emit]. */
    @JvmStatic external fun nativeOnEvent(kind: String, json: String)

    /**
     * The only way Kotlin delivers an event to Rust (AD-5). A silent no-op
     * until [attachNative] has run, and swallows a missing native symbol, so a
     * registry mutation never throws out of a Telecom callback.
     */
    @JvmStatic
    fun emit(kind: String, json: String) {
        if (!nativeReady) return
        try {
            nativeOnEvent(kind, json)
        } catch (_: LinkageError) {
            // Library not (or no longer) linked: drop the event; attach re-pulls.
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Records the application and lifecycle hooks. Does not load the native
     * library (AD-8): the GPUI activity loads it, then calls [attachNative].
     */
    fun install(application: Application) {
        app = application
        SettingsMirror.restore(application)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { foreground = activity }
            override fun onActivityPaused(activity: Activity) { if (foreground === activity) foreground = null }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Call from `MainActivity.onCreate` after `super.onCreate`, once the library is loaded. */
    fun attachNative() {
        try {
            nativeInit()
            nativeReady = true
        } catch (_: LinkageError) {
            nativeReady = false
        }
    }

    /** Registers the call-log and contacts observers (Rovsti Phone). Idempotent. */
    fun observeProviders() = ProviderObservers.register(app)

    /** Delivers a one-shot event now, or when Rust attaches (story 16.2). */
    fun postOneShot(kind: String, json: String) = mailbox.post(kind, json)

    /**
     * Rust calls this with `true` once its session is live and the activity resumed, and with `false`
     * when the session closes. `true` drains the mailbox (right after the snapshot, AD-5).
     */
    @JvmStatic
    fun uiAttached(attached: Boolean): Boolean {
        uiReady = attached
        if (attached) mailbox.attach() else mailbox.detach()
        return true
    }

    // ---- Settings mirror, documents, SIMs, tones (Epic 16) -------------

    @JvmStatic fun settingsSync(json: String): Boolean = SettingsMirror.apply(app, json)

    @JvmStatic
    fun systemFacts(): String = SettingsMirror.factsJson(SettingsMirror.flipSupported(app), SettingsMirror.systemKeypadTones(app))

    @JvmStatic
    fun exportDocument(name: String, json: String): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.start(activity, true, name, json) }
        return true
    }

    @JvmStatic
    fun importDocument(): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.start(activity, false, "", "") }
        return true
    }

    /** `MainActivity.onActivityResult`: true when the request was a document picker's. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        Documents.handle(app, requestCode, resultCode, data, documentExecutor) { json -> postOneShot("document_result", json) }

    private val documentExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "documents").apply { isDaemon = true } }
    }

    @JvmStatic fun phoneAccounts(): String = SimAccounts.collect(app)

    @JvmStatic fun callSelectAccount(callId: Int, key: String): Boolean = calls?.selectAccount(callId, key) ?: false

    /** Plays a dial-pad tone when the effective Keypad tones value is on (negative digit stops). */
    @JvmStatic
    fun keypadTone(digit: Int): Boolean {
        if (digit >= 0 && !SettingsMirror.effectiveKeypadTones(SettingsMirror.values, SettingsMirror.systemKeypadTones(app))) return false
        return KeypadTone.play(digit)
    }

    // ---- Calls (delegated to the app's CallsApi; AD-4, AD-6) ----------

    @JvmStatic fun callsSnapshot(): String =
        calls?.snapshot() ?: SnapshotJson.toJson(SnapshotJson.empty(0, System.currentTimeMillis(), true))
    @JvmStatic fun callAnswer(id: Int): Boolean = calls?.answer(id) ?: false
    @JvmStatic fun callReject(id: Int): Boolean = calls?.reject(id) ?: false
    @JvmStatic fun callDisconnect(id: Int): Boolean = calls?.disconnect(id) ?: false
    @JvmStatic fun callHold(id: Int, on: Boolean): Boolean = calls?.hold(id, on) ?: false
    @JvmStatic fun callMute(on: Boolean): Boolean = calls?.mute(on) ?: false
    @JvmStatic fun callRoute(name: String): Boolean = calls?.route(name) ?: false
    @JvmStatic fun callRouteEndpoint(id: String): Boolean = calls?.routeEndpoint(id) ?: false
    @JvmStatic fun callDtmf(digit: Int): Boolean = calls?.dtmf(digit.toChar()) ?: false
    @JvmStatic fun callDtmfStop(): Boolean = calls?.dtmfStop() ?: false
    @JvmStatic fun callAnswerReplacing(id: Int): Boolean = calls?.answerReplacing(id) ?: false
    @JvmStatic fun callSwap(): Boolean = calls?.swap() ?: false
    @JvmStatic fun callMerge(): Boolean = calls?.merge() ?: false
    @JvmStatic fun callSplit(id: Int): Boolean = calls?.split(id) ?: false
    @JvmStatic fun callAnswerHolding(id: Int): Boolean = calls?.answerHolding(id) ?: false
    @JvmStatic fun callAnswerEndingHeld(id: Int): Boolean = calls?.answerEndingHeld(id) ?: false
    @JvmStatic fun callEndAll(): Boolean = calls?.endAll() ?: false
    @JvmStatic fun callIncomingFrame(id: Int): Boolean = calls?.incomingFrame(id) ?: false

    /**
     * Runs a Diagnostics probe (AD-36). Blocking, up to ~20 s: Rust calls it off the UI thread.
     * Never throws; a failure is one `unknown:` line.
     */
    @JvmStatic fun diagnostics(probe: String): String = try {
        SpikeProbes.run(probe, app, calls)
    } catch (e: RuntimeException) {
        "unknown: probe crashed (${e.javaClass.simpleName})"
    }

    /** One slow-frame report from Rust (story 11.10); logcat tag `RovstiPerf`. */
    @JvmStatic fun perfLog(line: String): Boolean {
        android.util.Log.w("RovstiPerf", line)
        return true
    }

    // ---- Provider pulls (blocking; Rust calls them off the UI thread) --

    @JvmStatic fun pullCallLog(limit: Int): String = CallLogQuery.json(app, limit)
    @JvmStatic fun pullContacts(): String = ContactsQuery.contactsJson(app)
    @JvmStatic fun pullFavorites(): String = ContactsQuery.favoritesJson(app)

    /** [contactId] is a decimal string (the bridge passes String, Int and Boolean only). */
    @JvmStatic fun setStarred(contactId: String, starred: Boolean): Boolean =
        contactId.toLongOrNull()?.let { ContactsQuery.setStarred(app, it, starred) } ?: false

    /** Story 10.5: path of a PNG thumbnail (at most [size] px) for a photo URI, or "". */
    @JvmStatic fun contactPhoto(uri: String, size: Int): String = ContactPhotos.thumbnailPath(app, uri, size)

    @JvmStatic fun markMissedCallsRead(): Boolean = MissedCalls.markRead(app)

    @JvmStatic
    fun localTimeInfo(): String = LocaleTimeInfo.encode(
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()),
        android.text.format.DateFormat.is24HourFormat(app),
        android.text.format.DateFormat.getDateFormatOrder(app),
    )

    // ---- Permissions ---------------------------------------------------

    private val groups = mapOf(
        "phone" to listOf(
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.WRITE_CALL_LOG,
        ),
        "contacts" to listOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
        ),
        "notifications" to listOf(android.Manifest.permission.POST_NOTIFICATIONS),
        "bluetooth" to listOf(android.Manifest.permission.BLUETOOTH_CONNECT),
    )

    private fun missing(group: String): List<String>? =
        groups[group]?.filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun prefs() = app.getSharedPreferences("rovsti_bridge", Context.MODE_PRIVATE)

    /** 1 granted, 0 denied (dialog can still show), 2 permanently denied, 0 for an unknown group. */
    @JvmStatic
    fun permissionState(group: String): Int {
        val missing = missing(group) ?: return 0
        if (missing.isEmpty()) return 1
        val activity = foreground ?: return 0
        val asked = prefs().getBoolean("asked_$group", false)
        val canAsk = missing.any { activity.shouldShowRequestPermissionRationale(it) }
        return if (asked && !canAsk) 2 else 0
    }

    @JvmStatic
    fun requestPermission(group: String): Boolean {
        val missing = missing(group) ?: return false
        if (missing.isEmpty() || permissionState(group) == 2) return false
        val activity = foreground ?: return false
        prefs().edit().putBoolean("asked_$group", true).apply()
        activity.runOnUiThread { activity.requestPermissions(missing.toTypedArray(), REQUEST_PERMISSION) }
        return true
    }

    @JvmStatic
    fun openAppDetails(): Boolean =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))

    @JvmStatic
    fun openDefaultApps(): Boolean = start(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

    @JvmStatic
    fun canUseFullScreenIntent(): Boolean =
        app.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    @JvmStatic
    fun openFullScreenIntentSettings(): Boolean = start(
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${app.packageName}"))
    )

    /**
     * Emits `permissions_changed`. Called from `MainActivity.onResume` and
     * nowhere else (AD-5); Rust re-pulls the states and the provider data.
     */
    fun emitPermissionsChanged() {
        // The checks read SharedPreferences and ask the system: off the main thread (onResume).
        try {
            permissionsExecutor.execute {
                try {
                    emitPermissionsNow()
                } catch (e: RuntimeException) {
                    android.util.Log.w("Rovsti", "permission check failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private val permissionsExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "permissions").apply { isDaemon = true } }
    }

    private fun emitPermissionsNow() {
        val json = JSONObject().put("v", WIRE_VERSION)
        for (key in groups.keys) {
            json.put(key, when (permissionState(key)) { 1 -> "granted"; 2 -> "permanently_denied"; else -> "denied" })
        }
        json.put("full_screen_intent", canUseFullScreenIntent())
        emit("permissions_changed", json.toString())
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
    fun placeCall(number: String): Boolean = place(number, null)

    /**
     * Story 16.3: places the call on the account [accountKey]. An emergency number never gets a key, and the
     * app never changes the system's default account.
     */
    @JvmStatic
    @SuppressLint("MissingPermission")
    fun placeCallOn(number: String, accountKey: String): Boolean = place(number, accountKey)

    @SuppressLint("MissingPermission")
    private fun place(number: String, accountKey: String?): Boolean {
        val uri = Uri.fromParts("tel", number, null)
        if (app.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            start(Intent(Intent.ACTION_DIAL, uri))
            return false
        }
        val extras = Bundle()
        val emergency = try {
            android.telephony.PhoneNumberUtils.isEmergencyNumber(number)
        } catch (_: RuntimeException) {
            false
        }
        if (accountKey != null && !emergency) {
            SimAccounts.handleFor(app, accountKey)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        app.getSystemService(TelecomManager::class.java)?.placeCall(uri, extras) ?: return false
        return true
    }

    /** Opens the system "add contact" screen (create or add to existing) with [number] filled in. */
    @JvmStatic
    fun addContact(number: String): Boolean = start(
        Intent(Intent.ACTION_INSERT_OR_EDIT)
            .setType(android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, number)
    )

    /**
     * Story 14.4: opens a settings page by key (see [SystemPages]). Returns the envelope
     * `{v, ok, error?, data?:{fallback}}`; a missing activity falls back to App info and says so.
     */
    @JvmStatic
    fun openSystem(page: String): String = SystemPages.open(page) { action ->
        val intent = Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
        if (action == SystemPages.ACTION_DETAILS) intent.data = Uri.parse("package:${app.packageName}")
        start(intent)
    }

    /** Story 14.4: the SMS composer for [number] (`smsto:`). */
    @JvmStatic
    fun sendSmsTo(number: String): Boolean = start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    /** Story 14.4: puts [text] on the clipboard. */
    @JvmStatic
    fun copyText(text: String): Boolean = try {
        app.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Rovsti", text)) != null
    } catch (_: RuntimeException) {
        false
    }

    /** Story 14.4: the system share sheet with [text]. */
    @JvmStatic
    fun shareText(text: String): Boolean = start(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
    )

    /** Story 14.8: `TelephonyManager.isHearingAidCompatibilitySupported()` (API 23). */
    @JvmStatic
    fun hearingAidCompatible(): Boolean = try {
        app.getSystemService(android.telephony.TelephonyManager::class.java)?.isHearingAidCompatibilitySupported == true
    } catch (_: RuntimeException) {
        false
    }

    /** Opens the system Contacts app (the empty Contacts state). */
    @JvmStatic
    fun openContacts(): Boolean = start(
        Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI)
    )

    /** Opens the system Contacts app on [contactId] to edit it (story 8.4). */
    @JvmStatic
    fun editContact(contactId: String): Boolean {
        val id = contactId.toLongOrNull() ?: return false
        val uri = android.content.ContentUris.withAppendedId(
            android.provider.ContactsContract.Contacts.CONTENT_URI, id,
        )
        return start(Intent(Intent.ACTION_EDIT).setDataAndType(uri, android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE))
    }

    // ---- Window --------------------------------------------------------

    /** Sends the task to the background (never `finish()`, AD-3a); keeps the GPUI session warm. */
    @JvmStatic
    fun moveTaskToBack(): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { activity.moveTaskToBack(true) }
        return true
    }

    /** The keyguard state re-read from the system at the moment of a tap (story 15.5). */
    @JvmStatic
    fun isKeyguardLocked(): Boolean =
        app.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked ?: true

    /**
     * Asks the system to dismiss the keyguard (the Add call sheet needs the phone unlocked for
     * Favorites and Contacts). The answer is the `keyguard_dismissed` event; an unlocked phone
     * answers at once and a refused or cancelled request never answers.
     */
    @JvmStatic
    fun requestDismissKeyguard(): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread {
            val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
            if (keyguard == null || !keyguard.isKeyguardLocked) {
                emit("keyguard_dismissed", "{}")
                return@runOnUiThread
            }
            keyguard.requestDismissKeyguard(
                activity,
                object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        emit("keyguard_dismissed", "{}")
                    }
                },
            )
        }
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

    /**
     * Tri-state of a Xiaomi AppOps permission: 1 granted, 0 denied, -1 unknown
     * (not Xiaomi, reflection failed, or an unexpected mode). `key` is
     * `autostart`, `lock_screen` or `popups`.
     */
    @JvmStatic
    fun xiaomiOpState(key: String): Int = XiaomiOps.state(app, key)

    /**
     * Opens a Xiaomi settings page for this app. `key` is one of `autostart`,
     * `battery`, `permissions` (see `xiaomi.rs`). Returns true when a
     * dedicated page opened and false when only the generic App info page did,
     * in which case Rust shows on-screen steps.
     *
     * Actions and components below are the ones MIUI helper projects use
     * (miui.intent.action.OP_AUTO_START, APP_PERM_EDITOR,
     * POWER_HIDE_MODE_APP_LIST and the securitycenter / powerkeeper
     * activities). MIUI/HyperOS may mark an activity non-exported, in which
     * case start() fails and the next candidate is tried.
     */
    @JvmStatic
    fun openXiaomiSetting(key: String): Boolean {
        val pkg = app.packageName
        val candidates = when (key) {
            "autostart" -> listOf(
                action("miui.intent.action.OP_AUTO_START"),
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                component("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
                    .putExtra("package_name", pkg).putExtra("package_label", label()),
            )
            "battery" -> listOf(
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg).putExtra("package_label", label()),
                action("miui.intent.action.POWER_HIDE_MODE_APP_LIST"),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            "permissions" -> listOf(
                action("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
                action("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
                action("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
                component("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
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
    private const val REQUEST_PERMISSION = 0x5045 // "PE"

    private fun label(): String = app.applicationInfo.loadLabel(app.packageManager).toString()

    private fun action(name: String) = Intent(name).addCategory(Intent.CATEGORY_DEFAULT)

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
