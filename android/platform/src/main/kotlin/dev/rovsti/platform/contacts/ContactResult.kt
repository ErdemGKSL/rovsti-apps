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

/** Runs a provider write: a `SecurityException` is [ContactResult.Reason.Permission], any other failure `Unknown`. */
internal inline fun <T> guarded(block: () -> ContactResult<T>): ContactResult<T> =
    try {
        block()
    } catch (_: SecurityException) {
        ContactResult.failed(ContactResult.Reason.Permission)
    } catch (_: RuntimeException) {
        ContactResult.failed(ContactResult.Reason.Unknown)
    } catch (_: android.os.RemoteException) {
        ContactResult.failed(ContactResult.Reason.Unknown)
    } catch (_: android.content.OperationApplicationException) {
        ContactResult.failed(ContactResult.Reason.Unknown)
    }
