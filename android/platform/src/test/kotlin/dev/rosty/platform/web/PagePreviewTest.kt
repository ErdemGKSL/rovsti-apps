package dev.rosty.platform.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class PagePreviewTest {
    /** A scripted answer per URL. */
    private class Answer(
        val code: Int = 200,
        val type: String? = "text/html; charset=utf-8",
        val body: ByteArray = ByteArray(0),
        val location: String? = null,
    )

    private class FakeConnection(
        url: URL,
        private val answer: Answer,
        val seen: MutableList<FakeConnection>,
    ) : HttpURLConnection(url) {
        val headers = mutableMapOf<String, String>()

        init {
            seen += this
        }

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getResponseCode() = answer.code

        override fun getContentType() = answer.type

        override fun getHeaderField(name: String?): String? = if (name == "Location") answer.location else null

        override fun getInputStream(): InputStream = ByteArrayInputStream(answer.body)

        override fun setRequestProperty(
            key: String,
            value: String,
        ) {
            headers[key] = value
        }

        fun follows() = instanceFollowRedirects
    }

    private val public = listOf(InetAddress.getByName("93.184.216.34"))
    private val seen = mutableListOf<FakeConnection>()

    private fun fetcher(
        answers: Map<String, Answer>,
        addresses: (String) -> List<InetAddress> = { public },
    ) = HttpPagePreviewFetcher(
        resolve = addresses,
        open = { url -> FakeConnection(url, answers[url.toString()] ?: Answer(code = 404), seen) },
        io = Dispatchers.Unconfined,
    )

    @Test
    fun readsAPageUpToItsCapWithAGenericNameAndNoRedirectsOfItsOwn() =
        runBlocking {
            val html = "<title>Hi</title>" + "x".repeat(100)
            val page =
                fetcher(mapOf("https://example.com/a" to Answer(body = html.toByteArray())))
                    .page("https://example.com/a", 20)!!
            assertEquals("https://example.com/a", page.url)
            assertEquals(20, page.html.length)
            assertEquals(PagePreviewRules.USER_AGENT, seen.single().headers["User-Agent"])
            assertFalse(seen.single().follows())
            assertFalse(seen.single().headers.containsKey("Cookie"))
        }

    @Test
    fun followsHttpRedirectsButNotToAnotherScheme() =
        runBlocking {
            val answers =
                mapOf(
                    "http://example.com/" to Answer(code = 301, location = "https://example.com/new"),
                    "https://example.com/new" to Answer(body = "<title>New</title>".toByteArray()),
                    "https://example.com/file" to Answer(code = 302, location = "file:///etc/hosts"),
                )
            assertEquals("https://example.com/new", fetcher(answers).page("http://example.com/", 1000)?.url)
            assertNull(fetcher(answers).page("https://example.com/file", 1000))
        }

    @Test
    fun privateAddressesAndOtherSchemesAreNeverAsked() =
        runBlocking {
            val local = listOf(InetAddress.getByName("127.0.0.1"))
            assertNull(fetcher(emptyMap()) { local }.page("http://router.example/", 1000))
            assertTrue(seen.isEmpty())
            assertNull(fetcher(emptyMap()).page("ftp://example.com/", 1000))
            assertFalse(PagePreviewRules.publicAddress(InetAddress.getByName("192.168.1.1")))
            assertFalse(PagePreviewRules.publicAddress(InetAddress.getByName("10.0.0.1")))
            assertFalse(PagePreviewRules.publicAddress(InetAddress.getByName("169.254.1.1")))
            assertFalse(PagePreviewRules.publicAddress(InetAddress.getByName("100.64.0.1")))
            assertFalse(PagePreviewRules.publicAddress(InetAddress.getByName("fd00::1")))
            assertFalse(PagePreviewRules.publicAddress(InetAddress.getByName("::1")))
            assertTrue(PagePreviewRules.publicAddress(public.single()))
            assertFalse(PagePreviewRules.allowedUrl("https://user@example.com/"))
        }

    @Test
    fun aPageMustBeHtmlAndAnImageAnImageWithinItsCap() =
        runBlocking {
            val answers =
                mapOf(
                    "https://example.com/data.json" to Answer(type = "application/json", body = "{}".toByteArray()),
                    "https://example.com/a.jpg" to Answer(type = "image/jpeg", body = ByteArray(10)),
                    "https://example.com/big.jpg" to Answer(type = "image/jpeg", body = ByteArray(100)),
                )
            assertNull(fetcher(answers).page("https://example.com/data.json", 1000))
            assertEquals(10, fetcher(answers).image("https://example.com/a.jpg", 50)?.size)
            assertNull(fetcher(answers).image("https://example.com/big.jpg", 50))
            assertNull(fetcher(answers).image("https://example.com/data.json", 50))
        }

    @Test
    fun charsets() {
        assertEquals(Charsets.ISO_8859_1, PagePreviewRules.charsetOf("text/html; charset=ISO-8859-1"))
        assertEquals(Charsets.UTF_8, PagePreviewRules.charsetOf("text/html"))
        assertEquals(Charsets.UTF_8, PagePreviewRules.charsetOf("text/html; charset=nope"))
    }
}
