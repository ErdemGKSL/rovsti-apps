package dev.rosty.messages

import android.content.Intent

/**
 * What a `SENDTO` (`sms:`, `smsto:`, `mms:`, `mmsto:`), `VIEW` or `SEND` intent asks for. [New] has recipients;
 * [Share] is a text or an image shared from another app, so the person picks the chat.
 */
sealed interface ComposeRequest {
    data class New(
        val to: List<String>,
        val body: String?,
    ) : ComposeRequest

    data class Share(
        val text: String?,
        val streamUri: String?,
        val mime: String?,
        /** Every file of a `SEND_MULTIPLE`; empty for a `SEND`. */
        val streamUris: List<String> = emptyList(),
    ) : ComposeRequest

    companion object {
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
            if (pair.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) pair.toInt(HEX) else null

        /** Percent-decoding that leaves `+` alone: it is part of a phone number here, not a space. */
        fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val escaped =
                    if (text[i] == '%' &&
                        i + 3 <= text.length
                    ) {
                        hexByte(text.substring(i + 1, i + 3))
                    } else {
                        null
                    }
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
         * Pure core. [action] is the intent action; [schemeSpecificPart] the data URI's, for `SENDTO`. Null when there
         * is nothing to open: a `SENDTO` without recipients, or a `SEND` with no text and no stream.
         */
        fun build(
            action: String?,
            schemeSpecificPart: String?,
            extraText: String?,
            smsBody: String?,
            streamUri: String?,
            mime: String?,
            streamUris: List<String> = emptyList(),
        ): ComposeRequest? =
            when (action) {
                Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                    val to = recipients(schemeSpecificPart)
                    if (to.isEmpty()) {
                        null
                    } else {
                        val body =
                            queryBody(schemeSpecificPart)
                                ?: smsBody?.takeIf { it.isNotEmpty() }
                                ?: extraText?.takeIf { it.isNotEmpty() }
                        New(to, body)
                    }
                }

                Intent.ACTION_SEND -> {
                    val body = extraText?.takeIf { it.isNotEmpty() }
                    val stream = streamUri?.takeIf { it.isNotEmpty() }
                    if (body == null && stream == null) null else Share(body, stream, mime?.takeIf { it.isNotEmpty() })
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    val files = streamUris.filter { it.isNotEmpty() }
                    val body = extraText?.takeIf { it.isNotEmpty() }
                    if (files.isEmpty() && body == null) {
                        null
                    } else {
                        Share(body, files.firstOrNull(), mime?.takeIf { it.isNotEmpty() }, files)
                    }
                }

                else -> {
                    null
                }
            }

        /** Reads [intent] (null-safe). */
        fun fromIntent(intent: Intent?): ComposeRequest? {
            intent ?: return null
            val stream =
                try {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)?.toString()
                } catch (_: RuntimeException) {
                    null
                }
            val streams =
                if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                    try {
                        intent
                            .getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                            ?.map { it.toString() }
                            .orEmpty()
                    } catch (_: RuntimeException) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            return build(
                intent.action,
                intent.data?.schemeSpecificPart,
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                intent.getStringExtra("sms_body"),
                stream.takeIf { intent.action != Intent.ACTION_SEND_MULTIPLE },
                intent.type,
                streams,
            )
        }

        private const val HEX = 16
    }
}
