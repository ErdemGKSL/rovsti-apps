package dev.rosty.platform.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.rosty.platform.media.MediaRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.nio.charset.Charset

/** The first bytes of a page as text, after redirects. */
class FetchedPage(
    /** Where the page was in the end. */
    val url: String,
    val html: String,
)

/**
 * Fetches what a link card needs (E6-WA R4, owner decision in `plans/e6wa-r4.md`): the head of a web page and its
 * preview image. The phone fetches, the gateway never does. Tests use fakes.
 */
interface PagePreviewFetcher {
    /** At most [maxBytes] of the HTML page at [url]; null when it is not one or cannot be read. */
    suspend fun page(
        url: String,
        maxBytes: Int,
    ): FetchedPage?

    /** At most [maxBytes] of the image at [url]; null when it is not an image, too large or cannot be read. */
    suspend fun image(
        url: String,
        maxBytes: Int,
    ): ByteArray?
}

/** The pure rules of the fetch: which links and which addresses may be asked, and how a body is cut. */
object PagePreviewRules {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 5_000
    const val MAX_REDIRECTS = 4

    /** The image is read up to this size before it is downscaled. */
    const val MAX_IMAGE_BYTES = 1024 * 1024

    /** A generic browser-like name that tells the site nothing about the phone or the app. */
    const val USER_AGENT = "Mozilla/5.0 (compatible; LinkPreview)"

    /** Only `http` and `https`, with a host. */
    fun allowedUrl(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return parsed.protocol.lowercase() in setOf("http", "https") && !parsed.host.isNullOrEmpty() &&
            parsed.userInfo == null
    }

    /** A loopback, private, link-local, wildcard or multicast address is never asked (cheap SSRF guard). */
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        // IPv6 unique local addresses (fc00::/7) are private too; Java does not call them site-local.
        if (address is Inet6Address && (address.address[0].toInt() and ULA_MASK) == ULA_PREFIX) return false
        // 100.64.0.0/10 (carrier-grade NAT).
        val bytes = address.address
        if (bytes.size == IPV4_BYTES && bytes[0].toInt() and BYTE == CGNAT_FIRST &&
            (bytes[1].toInt() and CGNAT_MASK) == CGNAT_SECOND
        ) {
            return false
        }
        return true
    }

    /** The charset a `Content-Type` names; UTF-8 when it names none or one the platform lacks. */
    fun charsetOf(contentType: String?): Charset {
        val name =
            contentType
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', ' ')
        return runCatching { name?.let(Charset::forName) }.getOrNull() ?: Charsets.UTF_8
    }

    /** Reads at most [max] bytes of [input]. */
    fun readCapped(
        input: InputStream,
        max: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (out.size() < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private const val ULA_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC
    private const val IPV4_BYTES = 4
    private const val BYTE = 0xFF
    private const val CGNAT_FIRST = 100
    private const val CGNAT_MASK = 0xC0
    private const val CGNAT_SECOND = 64
    private const val BUFFER = 8 * 1024
}

/**
 * [PagePreviewFetcher] over [HttpURLConnection]: GET only, no cookies (no cookie handler is consulted; the request
 * carries none), no cache, the generic [PagePreviewRules.USER_AGENT], short timeouts, redirects followed by hand so a
 * redirect to another scheme or to a private address is refused, and every body read only up to its cap.
 */
class HttpPagePreviewFetcher(
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PagePreviewFetcher {
    override suspend fun page(
        url: String,
        maxBytes: Int,
    ): FetchedPage? =
        withContext(io) {
            fetch(url, maxBytes) { type -> type.startsWith("text/html") || type.startsWith("application/xhtml") }
                ?.let { (finalUrl, type, bytes) -> FetchedPage(finalUrl, String(bytes, PagePreviewRules.charsetOf(type))) }
        }

    override suspend fun image(
        url: String,
        maxBytes: Int,
    ): ByteArray? =
        withContext(io) {
            fetch(url, maxBytes + 1) { type -> type.startsWith("image/") }
                ?.third
                ?.takeIf { it.size <= maxBytes }
        }

    /** `(final url, content type, body)`, or null. */
    private fun fetch(
        start: String,
        maxBytes: Int,
        accept: (String) -> Boolean,
    ): Triple<String, String, ByteArray>? {
        var current = start
        repeat(PagePreviewRules.MAX_REDIRECTS + 1) {
            if (!PagePreviewRules.allowedUrl(current)) return null
            val url = URL(current)
            val addresses = runCatching { resolve(url.host) }.getOrNull() ?: return null
            if (addresses.isEmpty() || !addresses.all(PagePreviewRules::publicAddress)) return null
            val connection = runCatching { open(url) }.getOrNull() ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = PagePreviewRules.CONNECT_TIMEOUT_MS
                connection.readTimeout = PagePreviewRules.READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", PagePreviewRules.USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,image/*;q=0.8")
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = runCatching { URL(url, location).toString() }.getOrNull() ?: return null
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                val type = connection.contentType?.lowercase().orEmpty()
                if (!accept(type)) return null
                val body = connection.inputStream.use { PagePreviewRules.readCapped(it, maxBytes) }
                return Triple(current, type, body)
            } catch (_: IOException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Downscales a fetched preview image into the small JPEG a link card carries. */
object LinkThumbnails {
    /** The longer side of a link card's picture. */
    const val LONGEST_SIDE = 192

    /** The thumbnail limit the gateway takes (`MediaOptions.MAX_THUMBNAIL_BYTES`). */
    const val MAX_BYTES = 64 * 1024

    /** A JPEG of at most [LONGEST_SIDE] px and [MAX_BYTES]; null when [bytes] is not an image. */
    fun jpeg(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = MediaRules.sampleSize(bounds.outWidth, bounds.outHeight, LONGEST_SIDE)
            }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val (w, h) = MediaRules.preview(decoded.width, decoded.height, LONGEST_SIDE)
        val scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
        return try {
            MediaRules.jpeg { quality ->
                ByteArrayOutputStream()
                    .also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                    .toByteArray()
                    .takeIf { it.size <= MAX_BYTES }
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }
}
