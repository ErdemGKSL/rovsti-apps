package dev.rovsti.phone.setup

import android.content.Context
import dev.rovsti.core.model.phone.Reading
import dev.rovsti.core.model.phone.SetupFacts
import dev.rovsti.core.model.phone.XiaomiConfirmations
import dev.rovsti.platform.system.XiaomiOps
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionController
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.system.permissions.SpecialAccess

/** What Android lets the app read about itself, as the facts `setup.checklist` takes (the Rust core decides the rows). */
interface SetupFactsSource {
    /** The facts now; cheap reads, but the Xiaomi ones go through AppOps, so not on a hot path. */
    fun facts(): SetupFacts

    /** MIUI or HyperOS: the four Xiaomi rows appear only there. */
    val xiaomi: Boolean

    /** The app version a hand confirmation is valid for. */
    val appVersion: String

    /**
     * Whether call-back reminders can be exact (Android 12 and up needs the "Alarms and reminders" grant). Without it
     * they still arrive, a few minutes late, and Setup shows a row that opens the page (E5.12).
     */
    fun exactAlarmsAllowed(): Boolean = true
}

/** The state the sheet keeps between visits: the Xiaomi rows confirmed by hand, and whether the sheet was offered. */
interface SetupMemory {
    suspend fun confirmations(): XiaomiConfirmations

    suspend fun saveConfirmations(value: XiaomiConfirmations)

    /** Whether the sheet was already shown on its own once (it opens by itself only the first time). */
    suspend fun offered(): Boolean

    suspend fun markOffered()
}

/** [SetupMemory] that lives as long as the process; `PhoneStore` (Step 2) replaces it with the preferences file. */
class InMemorySetupMemory(
    private var confirmations: XiaomiConfirmations = XiaomiConfirmations(),
    private var offered: Boolean = false,
) : SetupMemory {
    override suspend fun confirmations(): XiaomiConfirmations = confirmations

    override suspend fun saveConfirmations(value: XiaomiConfirmations) {
        confirmations = value
    }

    override suspend fun offered(): Boolean = offered

    override suspend fun markOffered() {
        offered = true
    }
}

/** [SetupFactsSource] over the permission controller, [SpecialAccess] and the AppOps reader. */
class AndroidSetupFacts(
    private val context: Context,
    private val permissions: PermissionController,
    private val special: SpecialAccess,
    private val xiaomiProperty: (String) -> String? = ::systemProperty,
) : SetupFactsSource {
    override fun facts(): SetupFacts =
        SetupFacts(
            roleHeld = special.isRoleHeld(AppPermissions.ROLE_DIALER),
            phoneAndCallLogGranted = granted(PermissionGroup.PHONE),
            contactsGranted = granted(PermissionGroup.CONTACTS),
            notificationsGranted = granted(PermissionGroup.NOTIFICATIONS),
            fullScreenAllowed = special.fullScreenIntent(),
            batteryExempt = special.ignoresBatteryOptimisation(),
            autostart = reading("autostart"),
            lockScreen = reading("lock_screen"),
            backgroundPopups = reading("popups"),
        )

    override fun exactAlarmsAllowed(): Boolean = special.exactAlarms()

    override val xiaomi: Boolean by lazy {
        !xiaomiProperty("ro.mi.os.version.name").isNullOrEmpty() ||
            !xiaomiProperty("ro.miui.ui.version.name").isNullOrEmpty()
    }

    override val appVersion: String by lazy {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            ""
        }
    }

    private fun granted(group: PermissionGroup) = permissions.state(group).value == PermissionState.Granted

    private fun reading(key: String): Reading =
        if (!xiaomi) {
            Reading.Unknown
        } else {
            when (XiaomiOps.state(context, key)) {
                XiaomiOps.GRANTED -> Reading.Granted
                XiaomiOps.DENIED -> Reading.Denied
                else -> Reading.Unknown
            }
        }
}

/** `SystemProperties.get` by reflection (the class is hidden); null when it cannot be read. */
internal fun systemProperty(key: String): String? =
    try {
        Class
            .forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }
