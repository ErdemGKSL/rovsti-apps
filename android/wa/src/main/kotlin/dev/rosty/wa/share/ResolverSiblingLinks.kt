package dev.rosty.wa.share

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SiblingLinks] over the content resolver: reads `content://<package>.walink/link` (and `/offer`) of a sibling
 * ([WaLinkShare]).
 *
 * A package is only asked when its provider exists, belongs to that package and the package is signed with the same
 * key as this app (`PackageManager.checkSignatures`). The signature permission already keeps strangers from reading
 * a link; this check is for the other direction: a look-alike app that took a Rosty package name must not be able
 * to hand this app a gateway of its own.
 *
 * The app manifest carries the `<queries>` entries for the sibling packages (Android 11 package visibility) and
 * `uses-permission` [WaLinkShare.PERMISSION].
 */
class ResolverSiblingLinks(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SiblingLinks {
    private val app = context.applicationContext

    /** [SiblingAnswer.NotInstalled] or [SiblingAnswer.Untrusted] when [packageName] must not be read, else null. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun refusal(packageName: String): SiblingAnswer? {
        val packages = app.packageManager
        val provider =
            try {
                packages.resolveContentProvider(WaLinkShare.authority(packageName), 0)
            } catch (_: RuntimeException) {
                null
            } ?: return SiblingAnswer.NotInstalled
        val trusted =
            provider.packageName == packageName &&
                packages.checkSignatures(app.packageName, packageName) == PackageManager.SIGNATURE_MATCH
        return if (trusted) null else SiblingAnswer.Untrusted
    }

    override suspend fun offers(packageName: String): Boolean =
        withContext(io) {
            if (refusal(packageName) != null) return@withContext false
            try {
                app.contentResolver.query(WaLinkShare.offerUri(packageName), null, null, null, null)?.use {
                    it.moveToFirst()
                } == true
            } catch (_: RuntimeException) {
                // A sibling of an older build has no offer row, or its process died.
                false
            }
        }

    override suspend fun ask(packageName: String): SiblingAnswer =
        withContext(io) {
            refusal(packageName)?.let { return@withContext it }
            try {
                val cursor =
                    app.contentResolver.query(WaLinkShare.linkUri(packageName), null, null, null, null)
                        ?: return@withContext SiblingAnswer.NotInstalled
                cursor.use { row ->
                    when {
                        !row.moveToFirst() -> SiblingAnswer.NoLink
                        else -> WaLinkShare.linkOf(row)?.let { SiblingAnswer.Link(it) } ?: SiblingAnswer.Invalid
                    }
                }
            } catch (_: SecurityException) {
                SiblingAnswer.Untrusted
            } catch (_: RuntimeException) {
                // The sibling's process died, or it answered something no cursor can read.
                SiblingAnswer.Invalid
            }
        }
}

/** [WaLinkMarks] in the app's private preferences (`wa-link-share`): where the link came from, never the key. */
class PrefsWaLinkMarks(
    context: Context,
) : WaLinkMarks {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wa-link-share", Context.MODE_PRIVATE)

    override var adopted: AdoptedLink?
        get() {
            val instance = prefs.getString(ADOPTED_INSTANCE, null) ?: return null
            val from = prefs.getString(ADOPTED_FROM, null) ?: return null
            return AdoptedLink(instance, from)
        }
        set(value) {
            prefs
                .edit()
                .apply {
                    // The one-way mark of a removed link of earlier builds is not kept any more.
                    remove(DECLINED)
                    if (value == null) {
                        remove(ADOPTED_INSTANCE).remove(ADOPTED_FROM)
                    } else {
                        putString(ADOPTED_INSTANCE, value.instanceId).putString(ADOPTED_FROM, value.fromPackage)
                    }
                }.apply()
        }

    override var autoOff: Boolean
        get() = prefs.getBoolean(AUTO_OFF, false)
        set(value) {
            prefs.edit().putBoolean(AUTO_OFF, value).apply()
        }

    private companion object {
        const val ADOPTED_INSTANCE = "adopted_instance"
        const val ADOPTED_FROM = "adopted_from"
        const val DECLINED = "declined"
        const val AUTO_OFF = "auto_off"
    }
}
