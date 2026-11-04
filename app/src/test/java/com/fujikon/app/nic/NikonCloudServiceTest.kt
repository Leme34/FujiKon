package com.fujikon.app.nic

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * The cloud BFF rejects the detail endpoint with HTTP 503 when an
 * `access_token` header is present, so the client must never send one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NikonCloudServiceTest {

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private var lastToken: String? = "unset"
    private var lastPath = ""

    private val catalogJson = """
        {"recipe":[{"id":"R1","name":"Recipe One","picture_controll_name":"PC_One",
        "registration_date":"2026-10-01","is_new":false,"img":{"url":"https://example.com/a.jpg"}}]}
    """.trimIndent()

    private val detailJson = """
        {"recipe":{"name":"Recipe One","creatorName":"Nikon","pictureControlName":"PC_One",
        "recipeOutline":"","recipeDetail":"创作者的说明\n这款色彩方案让肤色更加通透自然。第二句不显示。",
        "pictureControl":{"baseName":"FLEXIBLE COLOR","sharpening":"+0.75","middleRangeSharp":"0.00",
        "clarity":"0.00","contrast":"-5","saturation":"-7","highlight":"0","shadow":"0",
        "whiteLevel":"0","blackLevel":"0"}}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use(::handle)
                } catch (_: Exception) {
                    if (!server.isClosed) continue
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(2_000)
    }

    @Test
    fun requestsDoNotSendAnAccessTokenHeader() = runBlocking {
        val service = NikonCloudService(baseUrl = "http://127.0.0.1:${server.localPort}/")

        val catalog = service.fetchCatalog()
        assertEquals(1, catalog.size)
        assertEquals("Recipe One", catalog[0].name)
        assertEquals("/bff/recipe/release", lastPath)
        assertNull(lastToken)

        val detail = service.fetchDetail("R1")
        assertEquals("/bff/recipe/release/detail", lastPath)
        assertEquals("这款色彩方案让肤色更加通透自然。", detail.brief)
        assertEquals(-5, detail.control.contrast)
        assertEquals(-7, detail.control.saturation)
        assertEquals(0.75f, detail.control.sharpening, 0.001f)
        assertNull(lastToken)
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        readExactly(input, length)

        lastToken = headers["access_token"]
        lastPath = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val body = if (lastPath.endsWith("/detail")) detailJson else catalogJson
        respond(socket.getOutputStream(), body)
    }

    private fun respond(output: OutputStream, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            when (val value = input.read()) {
                -1 -> return if (builder.isEmpty()) null else builder.toString()
                10 -> return builder.toString()
                13 -> Unit
                else -> builder.append(value.toChar())
            }
        }
    }

    private fun readExactly(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        assertTrue(offset <= length)
        return String(bytes, 0, offset, StandardCharsets.UTF_8)
    }
}
