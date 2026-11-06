package com.fujikon.app.agnes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Result of a single Agnes image-editing request. */
class AgnesImageResult(
    val bytes: ByteArray,
    val revisedPrompt: String? = null
)

/** An error that can be shown directly to the user. */
class AgnesException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

internal class AgnesPayload(
    val bytes: ByteArray?,
    val url: String?,
    val revisedPrompt: String?
)

/**
 * Client for the Agnes Image 2.1 Flash image-to-image API.
 *
 * The current preview is uploaded as a Data URI inside `extra_body.image`,
 * with `extra_body.response_format = "b64_json"` so the edited image comes
 * back in the same response. The API also supports returning a temporary URL;
 * that path is handled by [download].
 *
 * `baseUrl` is injectable so the whole HTTP exchange can be exercised against
 * a local server in tests without reaching the real service.
 */
class AgnesImageService(
    baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) {
    private val endpoint = baseUrl.trimEnd('/') + ENDPOINT_PATH

    suspend fun editImage(
        apiKey: String,
        prompt: String,
        imageBytes: ByteArray,
        mimeType: String = DEFAULT_MIME,
        size: String = DEFAULT_SIZE,
        ratio: String? = null
    ): AgnesImageResult = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw AgnesException(NO_KEY_MESSAGE)
        val text = prompt.trim()
        if (text.isEmpty()) throw AgnesException("请先输入修图提示词")
        if (imageBytes.isEmpty()) throw AgnesException("没有可用于 AI 修图的图片")

        val body = buildRequestBody(text, imageBytes, mimeType, size, ratio)
        val payload = parseResponse(post(key, body))
        when {
            payload.bytes != null -> AgnesImageResult(payload.bytes, payload.revisedPrompt)
            payload.url != null -> AgnesImageResult(download(payload.url), payload.revisedPrompt)
            else -> throw AgnesException("Agnes 未返回图片，请稍后重试或调整提示词")
        }
    }

    /**
     * Builds the request body exactly as documented for image-to-image edits:
     * the input image and output format live under `extra_body`; `tags` and a
     * top-level `response_format` are explicitly NOT sent (both cause errors).
     */
    internal fun buildRequestBody(
        prompt: String,
        imageBytes: ByteArray,
        mimeType: String,
        size: String,
        ratio: String?
    ): JSONObject {
        val dataUri = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(imageBytes)
        val extra = JSONObject().apply {
            put("image", JSONArray().put(dataUri))
            put("response_format", RESPONSE_FORMAT)
        }
        return JSONObject().apply {
            put("model", MODEL)
            put("prompt", prompt)
            put("size", size)
            if (!ratio.isNullOrBlank()) put("ratio", ratio)
            put("extra_body", extra)
        }
    }

    /** Parses an OpenAI-compatible image response into bytes or a download URL. */
    internal fun parseResponse(json: String): AgnesPayload {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw AgnesException("Agnes 返回了无法解析的内容")
        }
        val data = root.optJSONArray("data")
        if (data == null || data.length() == 0) {
            val serverMessage = root.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() && it != "null" }
            throw AgnesException(
                serverMessage?.let { "Agnes：$it" } ?: "Agnes 未返回图片数据，请稍后重试"
            )
        }
        val first = data.optJSONObject(0)
            ?: throw AgnesException("Agnes 返回的图片数据格式异常")
        val b64 = first.opt("b64_json") as? String
        val url = first.opt("url") as? String
        val revised = first.opt("revised_prompt") as? String
        val bytes = b64?.takeIf { it.isNotBlank() }?.let { encoded ->
            try {
                Base64.getDecoder().decode(encoded.filter { !it.isWhitespace() })
            } catch (e: IllegalArgumentException) {
                throw AgnesException("Agnes 返回的图片数据已损坏")
            }
        }?.takeIf { it.isNotEmpty() }
        return AgnesPayload(
            bytes = bytes,
            url = url?.takeIf { it.isNotBlank() },
            revisedPrompt = revised?.takeIf { it.isNotBlank() }
        )
    }

    private fun post(apiKey: String, body: JSONObject): String {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", USER_AGENT)
            setFixedLengthStreamingMode(payload.size)
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.let(::readText)?.takeIf { it.isNotBlank() }
                throw AgnesException(friendlyError(code, detail), httpCode = code)
            }
            return connection.inputStream.use(::readText)
        } catch (e: AgnesException) {
            throw e
        } catch (e: IOException) {
            throw AgnesException("无法连接 Agnes 服务：${e.message ?: "网络错误"}", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(imageUrl: String): ByteArray {
        val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw AgnesException("无法下载 Agnes 生成的图片（HTTP $code）", httpCode = code)
            }
            val bytes = connection.inputStream.use(::readBytes)
            if (bytes.isEmpty()) throw AgnesException("Agnes 生成的图片为空")
            return bytes
        } catch (e: AgnesException) {
            throw e
        } catch (e: IOException) {
            throw AgnesException("无法下载 Agnes 生成的图片：${e.message ?: "网络错误"}", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun friendlyError(code: Int, detail: String?): String {
        val serverMessage = detail?.let { text ->
            runCatching {
                val json = JSONObject(text)
                json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() && it != "null" }
                    ?: text.take(120)
            }.getOrDefault(text.take(120))
        }
        return when (code) {
            400 -> "请求参数有误，请检查提示词或图片${serverMessage?.let { "：$it" } ?: ""}"
            401, 403 -> "API 密钥无效或没有权限，请到「设置」检查 Agnes 密钥"
            404 -> "Agnes 接口不存在（HTTP 404），服务可能已升级"
            429 -> "请求过于频繁或额度不足，请稍后再试"
            in 500..599 -> "Agnes 服务暂时不可用（HTTP $code），请稍后再试"
            else -> "Agnes 请求失败（HTTP $code）${serverMessage?.let { "：$it" } ?: ""}"
        }
    }

    private fun readText(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
            if (out.size() > MAX_RESPONSE_BYTES) break
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun readBytes(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
            if (out.size() > MAX_IMAGE_BYTES) break
        }
        return out.toByteArray()
    }

    companion object {
        /** Latest Agnes image model as of 2026-11. */
        const val MODEL = "agnes-image-2.1-flash"
        const val DEFAULT_BASE_URL = "https://apihub.agnes-ai.com"
        const val DEFAULT_SIZE = "1K"
        const val DEFAULT_MIME = "image/jpeg"
        const val NO_KEY_MESSAGE = "尚未配置 Agnes API 密钥，请先在「设置」中录入并保存"
        private const val ENDPOINT_PATH = "/v1/images/generations"
        private const val RESPONSE_FORMAT = "b64_json"
        private const val CONNECT_TIMEOUT_MS = 20_000
        // Image generation may take tens of seconds; the docs recommend 60-360s.
        private const val READ_TIMEOUT_MS = 180_000
        private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 48 * 1024 * 1024
        private const val USER_AGENT = "FujiKon/1.7 Android"
    }
}
