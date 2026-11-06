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

    /** The application installed by [install]; for services (FCM) that run without an activity. */
    fun application(): Application = app

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
     * Headless request/answer (AD-MSG4), implemented by the app with `rovsti_android::headless_entry!`.
     * Callable from a receiver with no activity once the library is loaded; go through [Headless.call],
     * which treats a missing library or symbol as "no decision".
     */
    @JvmStatic external fun nativeHeadless(kind: String, json: String): String

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
        TripAlarm.recover(application)
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

    /** 0 stale, 1 sent, 2 opened Messages, 3 rejected only (story 18.1). */
    @JvmStatic fun callRejectWithMessage(id: Int, text: String): Int = calls?.rejectWithMessage(id, text) ?: 0
    @JvmStatic fun callRejectAndCompose(id: Int): Boolean = calls?.rejectAndCompose(id) ?: false
    @JvmStatic fun callSilenceRinger(): Boolean = calls?.silenceRinger() ?: false

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
    @JvmStatic fun pullContactsAll(): String = ContactsQuery.allContactsJson(app)
    @JvmStatic fun pullFavorites(): String = ContactsQuery.favoritesJson(app)

    // ---- The system blocklist (AD-27, story 17.1) ----------------------

    private val blocklist by lazy { Blocklist.System(app) }
    private fun emergency(raw: String): Boolean = try {
        android.telephony.PhoneNumberUtils.isEmergencyNumber(raw)
    } catch (_: RuntimeException) {
        false
    }

    @JvmStatic fun pullBlocklist(): String = Blocklist.pullJson(blocklist)
    @JvmStatic fun blockNumber(number: String): Boolean = Blocklist.block(blocklist, number, ::emergency) == Blocklist.Result.OK
    @JvmStatic fun unblockNumber(number: String): Boolean = Blocklist.unblock(blocklist, number) == Blocklist.Result.OK
    @JvmStatic fun canBlockNumbers(): Boolean = Blocklist.canBlock(blocklist)
    @JvmStatic fun isNumberBlocked(number: String): Boolean = Blocklist.isBlocked(blocklist, number)
    @JvmStatic fun pullBlockedCalls(limit: Int): String = CallLogQuery.blockedJson(app, limit)

    /** [contactId] is a decimal string (the bridge passes String, Int and Boolean only). */
    @JvmStatic fun setStarred(contactId: String, starred: Boolean): Boolean =
        contactId.toLongOrNull()?.let { ContactsQuery.setStarred(app, it, starred) } ?: false

    // ---- Rovsti Contacts: whole-contact edits, groups, vCard files -------------------

    /** [contactId] is a decimal string. The answer is `{contact: ContactDraft}`. */
    @JvmStatic fun contactDetail(contactId: String): String =
        contactId.toLongOrNull()?.let { ContactsEdit.detailJson(app, it) } ?: ProviderJson.error("not_found")

    @JvmStatic fun contactsFull(): String = ContactsEdit.fullJson(app)

    /** Creates or updates a contact from a `ContactDraft`; the answer is `{id}`. */
    @JvmStatic fun saveContact(json: String): String = ContactsEdit.saveJson(app, json)

    @JvmStatic fun deleteContact(contactId: String): Boolean =
        contactId.toLongOrNull()?.let { ContactsEdit.delete(app, it) } ?: false

    @JvmStatic fun importContacts(json: String): String = ContactsEdit.importJson(app, json)

    @JvmStatic fun contactGroups(): String = ContactsEdit.groupsJson(app)

    /** [groupId] "0" creates a group; the answer is `{id}`. */
    @JvmStatic fun saveContactGroup(groupId: String, title: String, account: String): String =
        groupId.toLongOrNull()?.let { ContactsEdit.saveGroup(app, it, title, account) } ?: ProviderJson.error("not_found")

    /** The accounts contacts can be saved to; the answer is `{accounts}`. */
    @JvmStatic fun contactAccounts(): String = ContactsEdit.accountsJson(app)

    /** `{"w":..,"h":..}` of the image at [path], rotation applied. */
    @JvmStatic fun contactPhotoSize(path: String): String = ContactPhotos.sizeJson(path)

    /** [contactId] is a decimal string; [planJson] is a `PhotoPlan`. The cached file at [path] is deleted. */
    @JvmStatic fun setContactPhoto(contactId: String, path: String, planJson: String): Boolean =
        contactId.toLongOrNull()?.let { ContactPhotos.set(app, it, path, planJson) } ?: false

    @JvmStatic fun removeContactPhoto(contactId: String): Boolean =
        contactId.toLongOrNull()?.let { ContactPhotos.remove(app, it) } ?: false

    /** Photo Picker for a contact photo; the answer is `document_result` op `contact_photo` with `path` and `mime`. */
    @JvmStatic
    fun pickContactPhoto(): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.startPhotoPick(activity) }
        return true
    }

    /** Asks a signature-matching sibling app to handle [action] (WhatsApp call or chat); false when none does. */
    @JvmStatic fun handoff(action: String, jid: String, name: String): Boolean = Handoff.start(app, action, jid, name)

    /**
     * Call from the launch activity's `onCreate` and `onNewIntent`. When the intent is a hand-off from a
     * sibling app, queues a one-shot `handoff` `{action, jid, name}` (through the mailbox, so it survives
     * a cold start) and removes the extras. True when it did.
     */
    fun deliverHandoff(intent: Intent?): Boolean {
        // Only the permission-guarded `.WaHandoff` alias may start a hand-off. An explicit intent
        // straight at the (unguarded, exported) launcher activity carries the same action, so the
        // component is checked too.
        if (intent?.component?.className?.endsWith(Handoff.ALIAS_SUFFIX) != true) return false
        val json = Handoff.payload(intent?.action, intent?.getStringExtra(Handoff.EXTRA_JID), intent?.getStringExtra(Handoff.EXTRA_NAME))
            ?: return false
        intent?.removeExtra(Handoff.EXTRA_JID)
        intent?.removeExtra(Handoff.EXTRA_NAME)
        intent?.action = null
        postOneShot("handoff", json)
        return true
    }

    @JvmStatic fun deleteContactGroup(groupId: String): Boolean =
        groupId.toLongOrNull()?.let { ContactsEdit.deleteGroup(app, it) } ?: false

    /** Opens "save as" for a `.vcf`; the answer is a `document_result` with op `export_contacts`. */
    @JvmStatic
    fun exportContactsDocument(name: String, text: String): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.startContacts(activity, true, name, text) }
        return true
    }

    /** Opens the file picker for a `.vcf`; the answer is a `document_result` with op `import_contacts`. */
    @JvmStatic
    fun importContactsDocument(): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.startContacts(activity, false, "", "") }
        return true
    }

    // ---- SMS, MMS, message notifications, scheduled SMS (Rovsti Messages) ----
    // Ids and timestamps cross the seam as decimal strings; "" means "none" for an optional one.

    @JvmStatic fun pullSmsThreads(limit: Int): String = SmsQuery.threadsJson(app, limit)

    @JvmStatic
    fun pullSmsMessages(threadId: String, beforeMs: String, limit: Int): String {
        val thread = threadId.toLongOrNull() ?: return SmsJson.error("unknown")
        return SmsQuery.messagesJson(app, thread, beforeMs.toLongOrNull(), limit)
    }

    @JvmStatic fun pullSmsSims(): String = SmsQuery.simsJson(app)

    /** `{local_id, to[], body, sub_id?}`; progress arrives as `sms_status`. */
    @JvmStatic fun smsSend(json: String): Boolean = SmsSend.send(app, json)

    /**
     * `{local_id, to[], text, image_path?, sub_id?}`. Rust builds the PDU (`nativeHeadless("mms_send_req")`
     * answers `{"pdu_b64":..}`), [MmsCodec] only moves the file. False when no PDU came back.
     */
    @JvmStatic
    fun smsSendMms(json: String): Boolean {
        val request = try { JSONObject(json) } catch (_: org.json.JSONException) { return false }
        val pdu = Headless.call("mms_send_req", json)?.let {
            try { JSONObject(it).optString("pdu_b64", "") } catch (_: org.json.JSONException) { "" }
        }.orEmpty()
        if (pdu.isEmpty()) return false
        val sub = if (request.has("sub_id") && !request.isNull("sub_id")) request.optInt("sub_id") else null
        return MmsCodec.send(app, request.optLong("local_id", 0), pdu, sub)
    }

    @JvmStatic fun smsMarkRead(threadId: String): Boolean = threadId.toLongOrNull()?.let { SmsQuery.markRead(app, it) } ?: false

    @JvmStatic fun smsDeleteThread(threadId: String): Boolean = threadId.toLongOrNull()?.let { SmsQuery.deleteThread(app, it) } ?: false

    @JvmStatic fun smsInsertRestored(json: String): String = SmsQuery.insertRestored(app, json)

    @JvmStatic fun armScheduledSms(json: String): Boolean = ScheduledSms.arm(app, json)

    @JvmStatic fun disarmScheduledSms(id: String): Boolean = id.toLongOrNull()?.let { ScheduledSms.disarm(app, it) } ?: false

    @JvmStatic fun pullFiredSms(): String = ScheduledSms.pullFired(app)

    @JvmStatic fun showMessageNotification(specJson: String): Boolean = MessageNotifications.show(app, specJson)

    @JvmStatic fun cancelMessageNotification(tag: String): Boolean = MessageNotifications.cancel(app, tag)

    @JvmStatic fun copyToClipboard(text: String, sensitive: Boolean): Boolean = MessageNotifications.copyToClipboard(app, text, sensitive)

    /** Save-as for the file at [path]; the answer is a `document_result` with op `export_messages`. */
    @JvmStatic
    fun exportFile(name: String, mime: String, path: String): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.startFileExport(activity, name, mime, path) }
        return true
    }

    /** Picker for an archive of at most [maxBytes]; the answer is `document_result` op `import_messages` with `path`. */
    @JvmStatic
    fun importFile(maxBytes: Int): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.startFileImport(activity, maxBytes, attachment = false) }
        return true
    }

    /** Picker for a GTFS `.zip` of at most [maxBytes]; the answer is `document_result` op `import_gtfs` with `path`. */
    @JvmStatic
    fun importGtfs(maxBytes: Int): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.startFileImport(activity, maxBytes, gtfs = true) }
        return true
    }

    /** Picker for a file to attach of at most [maxBytes]; the answer is `document_result` op `attachment` with `path` and `mime`. */
    @JvmStatic
    fun pickAttachment(maxBytes: Int): Boolean {
        val activity = foreground ?: return false
        activity.runOnUiThread { Documents.startFileImport(activity, maxBytes, attachment = true) }
        return true
    }

    /**
     * Call from the launch activity's `onCreate` and `onNewIntent`: a tap on a message notification
     * queues `open_thread` `{tag}` (through the mailbox, so it survives a cold start). True when it did.
     */
    fun deliverOpenThread(intent: Intent?): Boolean {
        val tag = intent?.getStringExtra(MessageNotifications.EXTRA_TAG) ?: return false
        intent.removeExtra(MessageNotifications.EXTRA_TAG)
        postOneShot("open_thread", JSONObject().put("tag", tag).toString())
        MessageNotifications.cancel(app, tag)
        return true
    }

    /** Story 10.5: path of a PNG thumbnail (at most [size] px) for a photo URI, or "". */
    @JvmStatic fun contactPhoto(uri: String, size: Int): String = ContactPhotos.thumbnailPath(app, uri, size)

    @JvmStatic fun markMissedCallsRead(): Boolean = MissedCalls.markRead(app)

    @JvmStatic
    fun localTimeInfo(): String = LocaleTimeInfo.encode(
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()),
        android.text.format.DateFormat.is24HourFormat(app),
        android.text.format.DateFormat.getDateFormatOrder(app),
    )

    // ---- WhatsApp wake and call audio (AD-WA5, AD-WA6) -----------------

    /** The FCM token, or "" when the host packages no Firebase. Blocks for up to 3 s; call off the UI thread. */
    @JvmStatic fun waPushToken(): String = WaPush.token()

    /** Starts the call microphone and speaker in `MODE_IN_COMMUNICATION`; false without `RECORD_AUDIO`. */
    @JvmStatic fun waAudioStart(): Boolean = WaCallAudio.start(app)

    @JvmStatic fun waAudioStop(): Boolean = WaCallAudio.stop(app)

    /** The next base64 microphone frame (960 samples, 16 kHz mono), or "". */
    @JvmStatic fun waMicFrame(): String = WaCallAudio.readFrame()

    /** Plays one base64 frame of the peer's voice. */
    @JvmStatic fun waPlayFrame(frame: String): Boolean = WaCallAudio.playFrame(frame)

    /** Loudspeaker (true) or earpiece (false) for the call audio; false when no such route exists. */
    @JvmStatic fun waSpeaker(on: Boolean): Boolean = WaCallAudio.setSpeaker(app, on)

    /** Posts the incoming WhatsApp call notification (full-screen, Answer, Decline). */
    @JvmStatic fun waShowIncoming(callId: String, title: String): Boolean = WaIncoming.show(app, callId, title)

    @JvmStatic fun waCancelIncoming(callId: String): Boolean = WaIncoming.cancel(app, callId)

    /**
     * Call from the launch activity's `onCreate` and `onNewIntent` with the intent it received. When it
     * carries an Answer or Decline from the incoming-call notification, queues `wa_call_action`
     * (through the mailbox, so it survives a cold start) and dismisses the notification. True when it did.
     */
    fun deliverWaCallAction(intent: Intent?): Boolean {
        val id = intent?.getStringExtra(WaIncoming.EXTRA_CALL_ID) ?: return false
        val action = intent.getStringExtra(WaIncoming.EXTRA_ACTION) ?: return false
        val json = WaIncoming.actionJson(id, action) ?: return false
        intent.removeExtra(WaIncoming.EXTRA_ACTION)
        WaIncoming.cancel(app, id)
        postOneShot("wa_call_action", json)
        return true
    }

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
        "location" to listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
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

    // ---- Rovsti Stops: trip service and alarm --------------------------

    /** Starts or updates the trip service; [specJson] is `{dir,title,text,next_fix_ms,high_accuracy}`. */
    @JvmStatic fun tripStart(specJson: String): Boolean = TripService.start(app, specJson)

    @JvmStatic fun tripStop(): Boolean = TripService.stop(app)

    @JvmStatic fun tripRunning(): Boolean = TripService.running

    /** [action] is `dismiss`, `snooze` or `test`. */
    @JvmStatic
    fun tripAlarm(action: String): Boolean = when (action) {
        "dismiss" -> { TripAlarm.dismiss(app); true }
        "snooze" -> { TripAlarm.snooze(app); true }
        "test" -> { TripAlarm.ring(app, test = true); true }
        else -> false
    }

    /** Arms the snooze re-ring at [atMs], epoch milliseconds as a string (the seam has no 64-bit argument). */
    @JvmStatic
    fun tripSnooze(atMs: String): Boolean = atMs.toLongOrNull()?.takeIf { it > 0 }?.let { TripAlarm.armSnooze(app, it) } ?: false

    @JvmStatic
    fun canScheduleExactAlarms(): Boolean = app.getSystemService(android.app.AlarmManager::class.java)?.exactAllowed() == true

    @JvmStatic
    fun openAppDetails(): Boolean =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))

    @JvmStatic
    fun openDefaultApps(): Boolean = start(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

    @JvmStatic
    fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < 34 ||
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

    /** Calls the voicemail box; without `CALL_PHONE` opens the dial pad on the voicemail URI instead. */
    @JvmStatic
    @SuppressLint("MissingPermission")
    fun callVoicemail(): Boolean {
        val uri = Uri.fromParts("voicemail", "", null)
        if (app.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            start(Intent(Intent.ACTION_DIAL, uri))
            return false
        }
        app.getSystemService(TelecomManager::class.java)?.placeCall(uri, Bundle()) ?: return false
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
