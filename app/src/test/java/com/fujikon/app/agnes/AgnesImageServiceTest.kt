package com.fujikon.app.agnes

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * Exercises the real HTTP exchange against a local server. Nothing here is a
 * stub: the request goes through HttpURLConnection over a socket and the reply
 * is parsed by the production [AgnesImageService] code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgnesImageServiceTest {

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread

    @Volatile private var lastMethod = ""
    @Volatile private var lastPath = ""
    @Volatile private var lastHeaders = emptyMap<String, String>()
    @Volatile private var lastBody = ""
    @Volatile private var requestCount = 0
    @Volatile private var status = 200
    @Volatile private var response = ""

    private val png: ByteArray = Base64.getDecoder().decode(ONE_PIXEL_PNG)

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

    private fun service(): AgnesImageService = AgnesImageService(
        baseUrl = "http://127.0.0.1:${server.localPort}",
        connectTimeoutMs = 3_000,
        readTimeoutMs = 5_000
    )

    private fun b64Response(): String =
        """{"created":1,"data":[{"url":null,"b64_json":"${Base64.getEncoder().encodeToString(png)}","revised_prompt":"增强光影"}]}"""

    @Test
    fun imageToImageRequestMatchesTheAgnesSpec() = runBlocking {
        response = b64Response()
        val input = byteArrayOf(1, 2, 3, 4, 5, -1, 0, 42)

        val result = service().editImage(
            apiKey = "sk-test-123",
            prompt = "把天空改成星空，保留原构图",
            imageBytes = input,
            mimeType = "image/jpeg",
            size = "1K",
            ratio = "4:3"
        )

        assertEquals("POST", lastMethod)
        assertEquals("/v1/images/generations", lastPath)
        assertEquals("Bearer sk-test-123", lastHeaders["authorization"])
        assertTrue(lastHeaders["content-type"]?.contains("application/json") == true)

        val body = JSONObject(lastBody)
        assertEquals(AgnesImageService.MODEL, body.getString("model"))
        assertEquals("把天空改成星空，保留原构图", body.getString("prompt"))
        assertEquals("1K", body.getString("size"))
        assertEquals("4:3", body.getString("ratio"))
        // Both are explicitly forbidden by the Agnes docs.
        assertFalse("tags must not be sent", body.has("tags"))
        assertFalse("response_format must not be top-level", body.has("response_format"))

        val extra = body.getJSONObject("extra_body")
        assertEquals("b64_json", extra.getString("response_format"))
        val dataUri = extra.getJSONArray("image").getString(0)
        assertTrue(dataUri.startsWith("data:image/jpeg;base64,"))
        assertArrayEquals(input, Base64.getDecoder().decode(dataUri.removePrefix("data:image/jpeg;base64,")))

        assertArrayEquals(png, result.bytes)
        assertEquals("增强光影", result.revisedPrompt)
    }

    @Test
    fun optionalRatioIsOmittedWhenNotProvided() = runBlocking {
        response = b64Response()
        service().editImage("sk", "prompt", byteArrayOf(7), ratio = null)
        assertFalse(JSONObject(lastBody).has("ratio"))
    }

    @Test
    fun urlResponseIsDownloaded() = runBlocking {
        response = """{"data":[{"url":"http://127.0.0.1:${server.localPort}/generated.png","b64_json":null}]}"""
        val result = service().editImage("sk", "prompt", byteArrayOf(9))
        assertArrayEquals(png, result.bytes)
    }

    @Test
    fun missingKeyFailsBeforeAnyNetworkCall() {
        val error = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("   ", "prompt", byteArrayOf(1)) }
        }
        assertTrue(error.message!!.contains("密钥"))
        assertEquals(0, requestCount)
    }

    @Test
    fun emptyPromptAndEmptyImageAreRejectedLocally() {
        assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "   ", byteArrayOf(1)) }
        }
        assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "prompt", byteArrayOf()) }
        }
        assertEquals(0, requestCount)
    }

    @Test
    fun invalidKeyProducesActionableMessage() {
        status = 401
        // Captured verbatim from https://api.agnes-ai.cn on 2026-11-06.
        response = """{"error":{"code":"","message":"无效的令牌 (request id: 20261106080435901262164sHWFxsCt)","type":"AgnesAI_error"}}"""
        val error = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("bad-key", "prompt", byteArrayOf(1)) }
        }
        assertTrue(error.message!!.contains("密钥无效"))
        // The raw server reason and the active endpoint must not be swallowed:
        // previously the UI only said "密钥无效" with no way to self-diagnose.
        assertTrue("实际=${error.message} / server=${error.serverMessage}", error.message!!.contains("无效的令牌"))
        // The live endpoint host is 127.0.0.1 in this socket test; what
        // matters is that the message names the endpoint actually used.
        assertTrue(error.message!!.contains("当前端点"))
        assertTrue(error.message!!.contains("127.0.0.1"))
        assertEquals("无效的令牌 (request id: 20261106080435901262164sHWFxsCt)", error.serverMessage)
        assertEquals(401, error.httpCode)
    }

    @Test
    fun officialEndpointModelAndRegionMappingMatchTheDocs() {
        assertEquals("agnes-image-2.5-flash", AgnesImageService.MODEL)
        assertEquals("https://api.agnes-ai.cn", AgnesImageService.DEFAULT_BASE_URL)
        assertEquals("https://apihub.agnes-ai.com", AgnesImageService.GLOBAL_BASE_URL)
        assertEquals(
            AgnesImageService.DEFAULT_BASE_URL,
            AgnesImageService.baseUrlFor(AgnesImageService.REGION_CN)
        )
        assertEquals(
            AgnesImageService.GLOBAL_BASE_URL,
            AgnesImageService.baseUrlFor(AgnesImageService.REGION_GLOBAL)
        )
        // The docs require exactly this path on either host.
        assertEquals(
            "https://api.agnes-ai.cn/v1/images/generations",
            AgnesImageService.DEFAULT_BASE_URL + "/v1/images/generations"
        )
    }

    @Test
    fun regionProviderSwitchesEndpointPerRequest() = runBlocking {
        var region = AgnesImageService.REGION_CN
        val service = AgnesImageService(
            baseUrl = "http://127.0.0.1:${server.localPort}",
            connectTimeoutMs = 3_000,
            readTimeoutMs = 5_000,
            baseUrlProvider = {
                if (region == AgnesImageService.REGION_GLOBAL) "http://127.0.0.1:${server.localPort}/alt"
                else "http://127.0.0.1:${server.localPort}"
            }
        )
        response = b64Response()
        service.editImage("sk", "p", byteArrayOf(1))
        assertEquals("/v1/images/generations", lastPath)
        region = AgnesImageService.REGION_GLOBAL
        service.editImage("sk", "p", byteArrayOf(1))
        assertEquals("/alt/v1/images/generations", lastPath)
    }

    @Test
    fun verifyApiKeyUsesMinimalDocumentedRequest() = runBlocking {
        response = """{"data":[{"url":"https://example.com/x.png"}]}"""
        val check = service().verifyApiKey("sk-valid")
        assertEquals(AgnesKeyStatus.VALID, check.status)
        assertTrue(check.message.contains("127.0.0.1"))
        val body = JSONObject(lastBody)
        assertEquals(AgnesImageService.MODEL, body.getString("model"))
        assertEquals("1K", body.getString("size"))
        assertTrue(body.getString("prompt").isNotBlank())
        assertFalse("verification must not upload an image", body.has("image"))
        assertFalse(body.getJSONObject("extra_body").has("image"))
        assertEquals("url", body.getJSONObject("extra_body").getString("response_format"))
        assertFalse(body.has("response_format"))
        assertEquals("Bearer sk-valid", lastHeaders["authorization"])
    }

    @Test
    fun verifyApiKeyRejectsInvalidKeyWithServerReason() = runBlocking {
        status = 401
        response = """{"error":{"message":"无效的令牌"}}"""
        val check = service().verifyApiKey("bad-key")
        assertEquals(AgnesKeyStatus.INVALID, check.status)
        assertEquals(401, check.httpCode)
        assertTrue("实际=$check", check.message.contains("无效的令牌"))
        assertTrue("实际=$check", check.message.contains("切换"))
    }

    @Test
    fun verifyApiKeySeparatesRateLimitAndOutOfKeyFromInvalidKey() = runBlocking {
        status = 429
        response = """{"error":{"message":"quota"}}"""
        assertEquals(AgnesKeyStatus.SERVICE_ISSUE, service().verifyApiKey("sk").status)

        status = 500
        assertEquals(AgnesKeyStatus.SERVICE_ISSUE, service().verifyApiKey("sk").status)
    }

    @Test
    fun verificationRetargetsWhenRegionProviderChanges() = runBlocking {
        var region = AgnesImageService.REGION_CN
        val service = AgnesImageService(
            baseUrl = "http://127.0.0.1:${server.localPort}",
            connectTimeoutMs = 3_000,
            readTimeoutMs = 5_000,
            baseUrlProvider = { "http://127.0.0.1:${server.localPort}/$region" }
        )
        status = 401
        response = """{"error":{"message":"无效的令牌"}}"""
        assertEquals(AgnesKeyStatus.INVALID, service.verifyApiKey("sk").status)
        assertEquals("/cn/v1/images/generations", lastPath)

        // Settings switches the persisted region; the provider is read on
        // every request, so the retry hits the other gateway immediately.
        region = AgnesImageService.REGION_GLOBAL
        status = 200
        response = """{"data":[{"url":"https://example.com/x.png"}]}"""
        assertEquals(AgnesKeyStatus.VALID, service.verifyApiKey("sk").status)
        assertEquals("/global/v1/images/generations", lastPath)
    }

    @Test
    fun verifyApiKeyWithoutSavedKeySkipsTheNetwork() = runBlocking {
        val check = service().verifyApiKey("   ")
        assertEquals(AgnesKeyStatus.INVALID, check.status)
        assertEquals(0, requestCount)
    }

    @Test
    fun maskKeyShowsOnlyTheEdges() {
        assertEquals("", AgnesImageService.maskKey(""))
        val masked = AgnesImageService.maskKey("sk-abcdefgh-1234567890")
        assertTrue(masked.startsWith("sk-a"))
        assertTrue(masked.endsWith("7890"))
        assertFalse(masked.contains("abcdefgh"))
    }

    @Test
    fun rateLimitAndServerErrorsAreExplained() {
        status = 429
        response = """{"error":{"message":"quota"}}"""
        val limited = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "prompt", byteArrayOf(1)) }
        }
        assertTrue(limited.message!!.contains("频繁"))
        assertEquals(429, limited.httpCode)

        status = 500
        response = """{"error":{"message":"oops"}}"""
        val broken = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "prompt", byteArrayOf(1)) }
        }
        assertTrue(broken.message!!.contains("暂时不可用"))
    }

    @Test
    fun emptyDataOrMalformedPayloadIsReported() {
        response = """{"data":[]}"""
        val empty = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "prompt", byteArrayOf(1)) }
        }
        assertTrue(empty.message!!.contains("未返回图片数据"))

        response = """{"error":{"message":"prompt blocked"}}"""
        val blocked = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "prompt", byteArrayOf(1)) }
        }
        assertTrue(blocked.message!!.contains("prompt blocked"))

        response = "not-json-at-all"
        val malformed = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "prompt", byteArrayOf(1)) }
        }
        assertTrue(malformed.message!!.contains("无法解析"))
    }

    @Test
    fun corruptedBase64IsRejected() {
        response = """{"data":[{"b64_json":"@@@not-base64@@@"}]}"""
        val error = assertThrows(AgnesException::class.java) {
            runBlocking { service().editImage("sk", "prompt", byteArrayOf(1)) }
        }
        assertTrue(error.message!!.contains("损坏"))
    }

    @Test
    fun connectionFailureIsWrappedInFriendlyMessage() {
        val dead = AgnesImageService(
            baseUrl = "http://127.0.0.1:1",
            connectTimeoutMs = 500,
            readTimeoutMs = 500
        )
        val error = assertThrows(AgnesException::class.java) {
            runBlocking { dead.editImage("sk", "prompt", byteArrayOf(1)) }
        }
        assertTrue(error.message!!.contains("无法连接"))
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
        requestCount++

        val isImage = lastMethod == "GET" && lastPath == "/generated.png"
        val code = if (isImage) 200 else status
        val body = if (isImage) {
            png
        } else {
            (response.ifBlank { "{}" }).toByteArray(StandardCharsets.UTF_8)
        }
        val reason = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: ${if (isImage) "image/png" else "application/json"}\r\n" +
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
        return out.toString(StandardCharsets.UTF_8.name())
    }

    companion object {
        // A real 1x1 PNG so BitmapFactory can decode it in the ViewModel tests.
        private const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    }
}
