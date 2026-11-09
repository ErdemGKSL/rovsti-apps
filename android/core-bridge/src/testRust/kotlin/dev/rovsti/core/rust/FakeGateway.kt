package dev.rovsti.core.rust

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A WhatsApp gateway on loopback for the native tests: canned HTTP answers per `METHOD path`, and WebSocket upgrades
 * (RFC 6455, enough for a text-and-binary peer) that send [wsScript] after the handshake and echo binary frames back
 * like a peer that repeats what it hears. [close] stops the server and ends every socket.
 */
internal class FakeGateway : AutoCloseable {
    class Reply(
        val status: Int = 200,
        val body: ByteArray = ByteArray(0),
        val contentType: String = "application/json",
    ) {
        constructor(status: Int = 200, json: String) : this(status, json.encodeToByteArray())
    }

    class Seen(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: ByteArray,
    )

    private val server = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
    private val routes = HashMap<String, Reply>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val connected = CountDownLatch(1)

    val seen = CopyOnWriteArrayList<Seen>()
    val url: String = "http://127.0.0.1:${server.localPort}"

    /** Text frames sent to every WebSocket right after the handshake. */
    @Volatile var wsScript: List<String> = emptyList()

    /** Answers that WebSocket upgrades are refused with, by path prefix; absent means accept. */
    val wsRefusals = HashMap<String, Reply>()

    init {
        thread(isDaemon = true, name = "fake-gateway") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                thread(isDaemon = true, name = "fake-gateway-conn") { runCatching { serve(socket) } }
            }
        }
    }

    fun route(
        method: String,
        path: String,
        reply: Reply,
    ) {
        synchronized(routes) { routes["$method $path"] = reply }
    }

    /** Waits until a WebSocket finished its handshake. */
    fun awaitSocket(seconds: Long = 10) = connected.await(seconds, TimeUnit.SECONDS)

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
        val headers =
            lines
                .drop(1)
                .filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        if (headers["upgrade"].equals("websocket", ignoreCase = true)) {
            seen += Seen(method, path, headers["authorization"], ByteArray(0))
            val refusal = wsRefusals.entries.firstOrNull { path.startsWith(it.key) }?.value
            if (refusal != null) {
                respond(output, refusal)
                return
            }
            webSocket(socket, input, output, headers.getValue("sec-websocket-key"))
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        seen += Seen(method, path, headers["authorization"], body)
        val reply =
            synchronized(routes) { routes["$method $path"] }
                ?: Reply(404, """{"code":"not_found","message":"no route"}""")
        respond(output, reply)
        socket.close()
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val bytes = out.toByteArray()
            if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) {
                return bytes.decodeToString().removeSuffix("\r\n\r\n")
            }
        }
    }

    private fun respond(
        output: OutputStream,
        reply: Reply,
    ) {
        output.write(
            (
                "HTTP/1.1 ${reply.status} X\r\nContent-Type: ${reply.contentType}\r\n" +
                    "Content-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n"
            ).encodeToByteArray(),
        )
        output.write(reply.body)
        output.flush()
    }

    private fun webSocket(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        key: String,
    ) {
        val accept =
            Base64.getEncoder().encodeToString(
                MessageDigest
                    .getInstance(
                        "SHA-1",
                    ).digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encodeToByteArray()),
            )
        output.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
            ).encodeToByteArray(),
        )
        output.flush()
        connected.countDown()
        for (text in wsScript) writeFrame(output, 1, text.encodeToByteArray())
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return
            val b1 = input.read()
            var length = (b1 and 0x7f).toLong()
            if (length == 126L) {
                length = (input.read().toLong() shl 8) or input.read().toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readNBytes(it, 0, 4) } else null
            val payload = input.readNBytes(length.toInt())
            if (mask !=
                null
            ) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (b0 and 0x0f) {
                2 -> {
                    writeFrame(output, 2, payload)
                }

                9 -> {
                    writeFrame(output, 10, payload)
                }

                8 -> {
                    writeFrame(output, 8, payload)
                    socket.close()
                    return
                }
            }
        }
    }

    @Synchronized
    private fun writeFrame(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
    ) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> {
                output.write(payload.size)
            }

            payload.size < 65536 -> {
                output.write(126)
                output.write(payload.size shr 8)
                output.write(payload.size and 0xff)
            }

            else -> {
                output.write(127)
                for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() shr shift).toInt() and 0xff)
            }
        }
        output.write(payload)
        output.flush()
    }

    /** Drops every open socket (the peer hangs up) without stopping the server. */
    fun hangUp() {
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        runCatching { server.close() }
        hangUp()
    }
}
