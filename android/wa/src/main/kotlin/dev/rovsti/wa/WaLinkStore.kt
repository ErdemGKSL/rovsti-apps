package dev.rovsti.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.map
import dev.rovsti.core.model.wa.LinkReadRequest
import dev.rovsti.core.model.wa.LinkWriteRequest
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.WaOps

/**
 * The link of this app to its WhatsApp instance: gateway URL, instance id and connect key. Rust keeps it in
 * `whatsapp-link.json` under `noBackupFilesDir` (ARCH 7.1), so it is never backed up; this class only reads and writes
 * it through `wa.link_read` and `wa.link_write`. [dir] is null for the app's own directory (tests pass another).
 *
 * The key is a bearer secret: [StoredLink.toString] already hides it, and nothing here logs it.
 */
class WaLinkStore(
    private val ops: WaOps,
    private val dir: String? = null,
) {
    /** The stored link, or null when this app has none. A damaged file is an error, not "no link". */
    suspend fun read(): Outcome<StoredLink?> = ops.linkRead(LinkReadRequest(dir)).map { it.link }

    /** Saves [link]; a bad URL or key is `RustError.Invalid` and nothing is written. */
    suspend fun write(link: StoredLink): Outcome<Unit> = ops.linkWrite(LinkWriteRequest(dir, link)).map { }

    /** Removes the link (the instance itself stays on the gateway). */
    suspend fun forget(): Outcome<Unit> = ops.linkWrite(LinkWriteRequest(dir, null)).map { }
}
