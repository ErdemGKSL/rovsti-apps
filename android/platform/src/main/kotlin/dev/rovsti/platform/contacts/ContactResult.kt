package dev.rovsti.platform.contacts

/**
 * What a contacts write answers. The repository never throws for a provider failure: a screen maps [Reason] to its
 * notice (the old bridge said `permission`, `not_found`, `refused` and `unknown` in its `{ok:false, error}` envelope).
 */
sealed interface ContactResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : ContactResult<T>

    data class Failed(
        val reason: Reason,
    ) : ContactResult<Nothing>

    enum class Reason { Permission, NotFound, Refused, Unknown }

    companion object {
        val Done: ContactResult<Unit> = Ok(Unit)

        fun failed(reason: Reason): ContactResult<Nothing> = Failed(reason)
    }
}

/** The log tag of contacts provider failures (`adb logcat -s RovstiContacts`). */
internal const val CONTACTS_TAG = "RovstiContacts"

/**
 * Runs a provider write: a `SecurityException` is [ContactResult.Reason.Permission], any other failure `Unknown`. The
 * exception is logged at warn level under [CONTACTS_TAG], since the screens only show a short notice.
 */
internal inline fun <T> guarded(block: () -> ContactResult<T>): ContactResult<T> =
    try {
        block()
    } catch (e: SecurityException) {
        logContacts("contacts write refused: no permission", e)
        ContactResult.failed(ContactResult.Reason.Permission)
    } catch (e: RuntimeException) {
        logContacts("contacts write failed", e)
        ContactResult.failed(ContactResult.Reason.Unknown)
    } catch (e: android.os.RemoteException) {
        logContacts("contacts provider died", e)
        ContactResult.failed(ContactResult.Reason.Unknown)
    } catch (e: android.content.OperationApplicationException) {
        logContacts("contacts batch failed", e)
        ContactResult.failed(ContactResult.Reason.Unknown)
    }

/** A warning under [CONTACTS_TAG]; never throws (the plain-JVM `android.util.Log` is a stub). */
internal fun logContacts(
    message: String,
    error: Throwable? = null,
) {
    try {
        android.util.Log.w(CONTACTS_TAG, message, error)
    } catch (_: RuntimeException) {
        // No logcat here (a unit test without Robolectric): nothing to do.
    }
}
