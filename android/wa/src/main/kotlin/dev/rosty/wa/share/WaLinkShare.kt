package dev.rosty.wa.share

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.rosty.core.model.wa.StoredLink

/**
 * The wire of the WhatsApp link hand-over between Rosty apps (WA-R2, owner decision: apps signed with the same key
 * share the connect key, pasting it stays as the fallback). Every app that can hold a link declares [WaLinkProvider]
 * under its own authority ([authority]) and both sides agree on this object: the signature permission that guards
 * the provider, the one path and the columns of its one row.
 *
 * - `content://<package>.walink/link`: no row unless that app has its WhatsApp vault unlocked right now, else one
 *   row with the vault's link (`vault.export_link`, memory only: no app reads or writes a link file for it);
 * - `content://<package>.walink/offer`: one row ([Columns.AVAILABLE]) when `link` would answer now; no key crosses.
 *
 * The row carries the connect key, a bearer secret. Three things keep it among Rosty apps: the provider is exported
 * only behind [PERMISSION] (`protectionLevel="signature"`, read and write), the provider checks the caller's
 * signature itself, and the reader only asks packages of [PACKAGES] that are signed like itself.
 */
object WaLinkShare {
    /** Signature permission: only apps signed with the same key may read another app's link. */
    const val PERMISSION = "dev.rosty.permission.WA_LINK"

    /** The provider's authority is the app's package plus this. */
    const val AUTHORITY_SUFFIX = ".walink"

    const val LINK_PATH = "link"

    /** Whether the app would hand its link over now, without the link. */
    const val OFFER_PATH = "offer"

    /**
     * The application ids of the Rosty apps that can hold a WhatsApp link, in the order a reader asks them
     * (`applicationId` of `android/<app>/build.gradle.kts`; the `plain` and `firebase` flavors share the id).
     * Messages first: it is the app people link in.
     */
    val PACKAGES: List<String> = listOf("dev.rosty.messages", "dev.rosty.phone", "dev.rosty.contacts")

    /** The column names of the link row. */
    object Columns {
        const val GATEWAY_URL = "gateway_url"
        const val INSTANCE_ID = "instance_id"
        const val KEY = "key"

        val all: Array<String> = arrayOf(GATEWAY_URL, INSTANCE_ID, KEY)

        /** The one column of the `offer` row. */
        const val AVAILABLE = "available"
    }

    /** The authority of the provider of [packageName]. */
    fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

    /** The URI of the link row of [packageName]. */
    fun linkUri(packageName: String): Uri = Uri.parse("content://${authority(packageName)}/$LINK_PATH")

    /** The URI of the offer row of [packageName]. */
    fun offerUri(packageName: String): Uri = Uri.parse("content://${authority(packageName)}/$OFFER_PATH")

    /** A cursor with the offer column and, when [available], its one row. */
    fun offerCursorOf(available: Boolean): MatrixCursor =
        MatrixCursor(arrayOf(Columns.AVAILABLE), 1).apply {
            if (available) addRow(arrayOf<Any?>(1))
        }

    /** The apps [own] asks: every Rosty app but itself. */
    fun siblingsOf(own: String): List<String> = PACKAGES.filter { it != own }

    /** A cursor with the contract's columns and, when [link] is not null, its one row. */
    fun cursorOf(link: StoredLink?): MatrixCursor =
        MatrixCursor(Columns.all, 1).apply {
            if (link != null) addRow(arrayOf<Any?>(link.gatewayUrl, link.instanceId, link.key))
        }

    /**
     * The link of the row [cursor] stands on; null when a column is missing or a value is not usable ([usable]).
     * Never throws on a foreign cursor.
     */
    fun linkOf(cursor: Cursor): StoredLink? {
        fun text(column: String): String? {
            val index = cursor.getColumnIndex(column)
            return if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
        }
        return try {
            val link =
                StoredLink(
                    gatewayUrl = text(Columns.GATEWAY_URL) ?: return null,
                    instanceId = text(Columns.INSTANCE_ID) ?: return null,
                    key = text(Columns.KEY) ?: return null,
                )
            link.takeIf { usable(it) }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Whether [link] looks like a link at all: an `http(s)` gateway address, an instance id and a key, none with
     * white space. The store (`wa.link_write`) still validates the address and the key before it writes.
     */
    fun usable(link: StoredLink): Boolean {
        val url = link.gatewayUrl
        val scheme = url.startsWith("https://") || url.startsWith("http://")
        return scheme &&
            url.substringAfter("://").isNotEmpty() &&
            link.instanceId.isNotEmpty() &&
            link.key.isNotEmpty() &&
            listOf(url, link.instanceId, link.key).none { text -> text.any { it.isWhitespace() } }
    }
}
