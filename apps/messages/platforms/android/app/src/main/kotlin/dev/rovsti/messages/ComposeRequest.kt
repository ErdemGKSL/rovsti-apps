package dev.rovsti.messages

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * `SENDTO` (`sms:`, `smsto:`, `mms:`, `mmsto:`) and `SEND` intents as the `open_thread` payload Rust
 * opens a new conversation from: `{tag: "new"|"share", to[], body?, stream_uri?, mime?}`.
 * "new" has recipients; "share" is a text or image shared from another app, so the user picks the chat.
 * The parsing is pure so a JVM test covers it.
 */
object ComposeRequest {
    const val TAG_NEW = "new"
    const val TAG_SHARE = "share"

    /** Recipients of `sms:+90555,+90556?body=hi` given its scheme-specific part; blanks dropped. */
    fun recipients(schemeSpecificPart: String?): List<String> {
        val head = schemeSpecificPart.orEmpty().substringBefore('?')
        return percentDecode(head).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The `body=` query parameter of an `sms:` URI, if any. */
    fun queryBody(schemeSpecificPart: String?): String? {
        val query = schemeSpecificPart.orEmpty().substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key.equals("body", ignoreCase = true) && pair.contains('=')) {
                return percentDecode(pair.substringAfter('=')).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun hexByte(pair: String): Int? =
        if (pair.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) pair.toInt(16) else null

    /** Percent-decoding that leaves `+` alone: it is part of a phone number here, not a space. */
    fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val escaped = if (text[i] == '%' && i + 3 <= text.length) hexByte(text.substring(i + 1, i + 3)) else null
            if (escaped != null) {
                bytes.write(escaped)
                i += 3
            } else {
                val next = text.indexOf('%', i + 1).let { if (it < 0) text.length else it }
                bytes.write(text.substring(i, next).toByteArray(Charsets.UTF_8))
                i = next
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    /**
     * Pure core. [action] is the intent action; [schemeSpecificPart] the data URI's, for `SENDTO`.
     * Null when there is nothing to open: a `SENDTO` without recipients, or a `SEND` with no text and no stream.
     */
    fun build(
        action: String?,
        schemeSpecificPart: String?,
        extraText: String?,
        smsBody: String?,
        streamUri: String?,
        mime: String?,
    ): String? {
        when (action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val to = recipients(schemeSpecificPart)
                if (to.isEmpty()) return null
                val body = queryBody(schemeSpecificPart) ?: smsBody?.takeIf { it.isNotEmpty() } ?: extraText?.takeIf { it.isNotEmpty() }
                return payload(TAG_NEW, to, body, streamUri, mime)
            }
            Intent.ACTION_SEND -> {
                val body = extraText?.takeIf { it.isNotEmpty() }
                if (body == null && streamUri.isNullOrEmpty()) return null
                return payload(TAG_SHARE, emptyList(), body, streamUri, mime)
            }
            else -> return null
        }
    }

    private fun payload(tag: String, to: List<String>, body: String?, streamUri: String?, mime: String?): String {
        val o = JSONObject().put("tag", tag).put("to", JSONArray(to))
        body?.let { o.put("body", it) }
        streamUri?.takeIf { it.isNotEmpty() }?.let { o.put("stream_uri", it) }
        mime?.takeIf { it.isNotEmpty() }?.let { o.put("mime", it) }
        return o.toString()
    }

    /** Reads [intent] (null-safe) into the payload. */
    @Suppress("DEPRECATION")
    fun fromIntent(intent: Intent?): String? {
        intent ?: return null
        val stream = try {
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toString()
        } catch (_: RuntimeException) {
            null
        }
        return build(
            intent.action,
            intent.data?.schemeSpecificPart,
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            intent.getStringExtra("sms_body"),
            stream,
            intent.type,
        )
    }
}
