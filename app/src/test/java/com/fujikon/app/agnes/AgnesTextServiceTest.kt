package com.fujikon.app.agnes

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Exercises the real Agnes 3.0 Pro Chat Completions exchange against a local
 * socket server. The production [AgnesTextService] builds the request and
 * parses the reply; nothing here is stubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgnesTextServiceTest {

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread

    @Volatile private var lastMethod = ""
    @Volatile private var lastPath = ""
    @Volatile private var lastHeaders = emptyMap<String, String>()
    @Volatile private var lastBody = ""
    @Volatile private var status = 200
    @Volatile private var response = ""

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use(::handle)
                } catch (_: Exception) {
                    if (server.isClosed) break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(2_000)
    }

    private fun service(): AgnesTextService = AgnesTextService(
        baseUrl = "http://127.0.0.1:${server.localPort}",
        connectTimeoutMs = 3_000,
        readTimeoutMs = 5_000
    )

    private fun completion(content: String): String =
        """{"id":"chatcmpl_x","object":"chat.completion","model":"agnes-3.0-pro",""" +
            """"choices":[{"index":0,"message":{"role":"assistant","content":""" +
            JSONObject.quote(content) +
            """},"finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":40}}"""

    @Test
    fun chatCompletionsRequestMatchesTheOfficialSpec() = runBlocking {
        response = completion("提升整体曝光与暗部细节，校正偏蓝白平衡，保留原构图")
        val image = byteArrayOf(1, 2, 3, 4, 5)

        val prompt = service().generateEditPrompt(
            apiKey = "sk-text-123",
            imageBytes = image,
            mimeType = "image/jpeg"
        )

        assertEquals("提升整体曝光与暗部细节，校正偏蓝白平衡，保留原构图", prompt)
        assertEquals("POST", lastMethod)
        assertEquals("/v1/chat/completions", lastPath)
        assertEquals("Bearer sk-text-123", lastHeaders["authorization"])
        assertTrue(lastHeaders["content-type"]?.contains("application/json") == true)

        val body = JSONObject(lastBody)
        assertEquals(AgnesTextService.MODEL, body.getString("model"))
        assertEquals("agnes-3.0-pro", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertTrue(body.getInt("max_tokens") > 0)

        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val user = messages.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        val parts = user.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        val url = parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertTrue("必须是 base64 Data URI", url.startsWith("data:image/jpeg;base64,"))
        val decoded = Base64.getDecoder().decode(url.removePrefix("data:image/jpeg;base64,"))
        assertEquals(5, decoded.size)
        assertEquals(1.toByte(), decoded[0])
    }

    @Test
    fun publicImageUrlOverrideIsUsedWhenProvided() = runBlocking {
        response = completion("增强天空层次，保留主体曝光")
        service().generateEditPrompt(
            apiKey = "sk",
            imageBytes = byteArrayOf(9),
            imageUrlOverride = "https://cdn.example.com/photo.jpg"
        )
        val parts = JSONObject(lastBody).getJSONArray("messages")
            .getJSONObject(1).getJSONArray("content")
        assertEquals(
            "https://cdn.example.com/photo.jpg",
            parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
    }

    @Test
    fun promptIsCleanedOfQuotesMarkdownAndLabels() {
        val svc = service()
        assertEquals("提升曝光，保留原构图", svc.cleanPrompt("  \"提升曝光，保留原构图\"  "))
        assertEquals("校正偏蓝，增强层次", svc.cleanPrompt("提示词：校正偏蓝，增强层次"))
        assertEquals("增强反差与色彩", svc.cleanPrompt("```\n增强反差与色彩\n```"))
        assertEquals("提升暗部细节", svc.cleanPrompt("「提升暗部细节」"))
        // Newlines collapse into a single editable line.
        assertEquals("提亮暗部 增强层次", svc.cleanPrompt("提亮暗部\n增强层次"))
        // Long output is truncated to the input field's 500-char limit.
        assertEquals(500, svc.cleanPrompt("a".repeat(900)).length)
    }

    @Test
    fun arrayShapedContentIsAlsoParsed() {
        val svc = service()
        val json = """{"choices":[{"message":{"content":[{"type":"text","text":"校正白平衡，"}]}}]}"""
        // First block wins the concatenation; multi-block is supported.
        val withTwo = """{"choices":[{"message":{"content":[{"type":"text","text":"提升曝光，"},{"type":"text","text":"保留原构图"}]}}]}"""
        assertEquals("校正白平衡，", svc.parseCompletion(json))
        assertEquals("提升曝光，保留原构图", svc.parseCompletion(withTwo))
    }

    @Test
    fun emptyOrMalformedCompletionIsRejected() {
        val svc = service()
        assertThrows(AgnesException::class.java) { svc.parseCompletion("""{"choices":[]}""") }
        assertThrows(AgnesException::class.java) { svc.parseCompletion("not-json") }
        assertThrows(AgnesException::class.java) {
            svc.parseCompletion("""{"choices":[{"message":{"content":"   "}}]}""")
        }
    }

    @Test
    fun authQuotaAndModelErrorsAreActionable() {
        status = 401
        response = """{"error":{"message":"无效的令牌"}}"""
        val auth = assertThrows(AgnesException::class.java) {
            runBlocking { service().generateEditPrompt("sk", byteArrayOf(1)) }
        }
        assertEquals(401, auth.httpCode)
        assertTrue(auth.message!!.contains("无效的令牌"))
        assertTrue(auth.message!!.contains("密钥"))

        status = 404
        response = """{"error":{"message":"model not found"}}"""
        val missing = assertThrows(AgnesException::class.java) {
            runBlocking { service().generateEditPrompt("sk", byteArrayOf(1)) }
        }
        assertEquals(404, missing.httpCode)
        assertTrue(missing.message!!.contains("即将上线") || missing.message!!.contains("不存在"))

        status = 429
        response = """{"error":{"message":"rate limited"}}"""
        val limited = assertThrows(AgnesException::class.java) {
            runBlocking { service().generateEditPrompt("sk", byteArrayOf(1)) }
        }
        assertEquals(429, limited.httpCode)
        assertTrue(limited.message!!.contains("频繁") || limited.message!!.contains("额度"))
    }

    @Test
    fun emptyKeyOrImageIsRejectedBeforeAnyRequest() {
        assertThrows(AgnesException::class.java) {
            runBlocking { service().generateEditPrompt("", byteArrayOf(1)) }
        }
        assertThrows(AgnesException::class.java) {
            runBlocking { service().generateEditPrompt("sk", ByteArray(0)) }
        }
        assertEquals("", lastBody)
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        lastMethod = parts.getOrElse(0) { "" }
        lastPath = parts.getOrElse(1) { "" }
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
        lastHeaders = headers
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        lastBody = String(buffer, 0, offset, StandardCharsets.UTF_8)

        val body = (response.ifBlank { "{}" }).toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val char = input.read()
            if (char == -1) return null
            if (char == '\n'.code) break
            if (char != '\r'.code) out.write(char)
        }
        return out.toString(StandardCharsets.US_ASCII.name())
    }
}
