package dev.rosty.wa.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.Process
import dev.rosty.core.model.wa.StoredLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app whose `Application` hands its WhatsApp link to [WaLinkProvider]. `:shell`'s `HasContainer` implements it
 * for every Rosty app, so an app only declares the provider in its manifest.
 */
interface WaLinkShareHost {
    /**
     * The link of this app's WhatsApp vault while this process has it unlocked, else null (a locked vault, a cold
     * process, no link). Never read from a file; the provider bounds the wait.
     */
    suspend fun waLinkToShare(): StoredLink?

    /** Whether [waLinkToShare] would answer now, without reading the link. */
    suspend fun waLinkOffered(): Boolean
}

/**
 * Hands this app's WhatsApp link to a sibling Rosty app (WA-R2): `content://<applicationId>.walink/link`, see
 * [WaLinkShare]. `:wa`'s library manifest declares nothing (ARCH 7.2); each app declares this class with
 * `android:exported="true"` and `readPermission` / `writePermission` = [WaLinkShare.PERMISSION].
 *
 * Who may read it:
 * - Android only lets a caller that holds the signature permission reach [query] (the manifest attributes);
 * - [query] checks the caller again itself: the same uid, or a uid whose signing certificates match this app's
 *   (`PackageManager.checkSignatures` for every package of the calling uid). So a wrong manifest merge, or the permission defined by another app first,
 *   cannot open it.
 *
 * It only reads, and only from this app's unlocked vault in memory (`plans/wa-vault.md` 13.3): a locked vault or a
 * cold process answers no row, and no link file is read. Nothing is logged: the row is a bearer secret. [onCreate]
 * does no work (a provider is created before `Application.onCreate` returns); a query waits at most [READ_WAIT_MS].
 */
class WaLinkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: throw IllegalStateException("No context")
        if (!callerIsSibling()) throw SecurityException("Not a Rosty app")
        val own = WaLinkShare.linkUri(context.packageName)
        val offer = WaLinkShare.offerUri(context.packageName)
        val asksOffer = uri.authority == offer.authority && uri.pathSegments == offer.pathSegments
        if (!asksOffer && (uri.authority != own.authority || uri.pathSegments != own.pathSegments)) {
            throw IllegalArgumentException("Unknown URI")
        }
        val host = context.applicationContext as? WaLinkShareHost
        // The binder identity is cleared so the read runs as this app, whatever the caller may do.
        val token = Binder.clearCallingIdentity()
        try {
            if (asksOffer) {
                val available = runBlocking { withTimeoutOrNull(READ_WAIT_MS) { host?.waLinkOffered() } } == true
                return WaLinkShare.offerCursorOf(available)
            }
            val link = runBlocking { withTimeoutOrNull(READ_WAIT_MS) { host?.waLinkToShare() } }
            return WaLinkShare.cursorOf(link?.takeIf { WaLinkShare.usable(it) })
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor = query(uri, projection, selection, selectionArgs, sortOrder)

    /** The caller is this app or an app signed with the same key. */
    private fun callerIsSibling(): Boolean {
        val caller = Binder.getCallingUid()
        if (caller == Process.myUid()) return true
        val context = context ?: return false
        val packages = context.packageManager
        // A caller that reached this provider is visible to this app, so its packages can be named.
        val names = packages.getPackagesForUid(caller)?.takeIf { it.isNotEmpty() } ?: return false
        return names.all { packages.checkSignatures(context.packageName, it) == PackageManager.SIGNATURE_MATCH }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.dev.rosty.walink"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("Read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    private companion object {
        /** How long a query waits for the vault's answer. */
        const val READ_WAIT_MS = 3_000L
    }
}
