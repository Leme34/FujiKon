package com.fujikon.app.agnes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Result of a single Agnes image-editing request. */
class AgnesImageResult(
    val bytes: ByteArray,
    val revisedPrompt: String? = null
)

/** An error that can be shown directly to the user. */
class AgnesException(
    message: String,
    val httpCode: Int? = null,
    /** Raw `error.message` returned by Agnes, kept for diagnostics. */
    val serverMessage: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

internal class AgnesPayload(
    val bytes: ByteArray?,
    val url: String?,
    val revisedPrompt: String?
)

/** Outcome of a real key-verification request against the Agnes endpoint. */
enum class AgnesKeyStatus { VALID, INVALID, SERVICE_ISSUE, NETWORK_ISSUE }

data class AgnesKeyCheck(
    val status: AgnesKeyStatus,
    val message: String,
    val httpCode: Int? = null
)

/**
 * Client for the Agnes Image 2.5 Flash image-to-image API.
 *
 * Implements the official integration docs at
 * https://www.agnes-ai.cn/zh-Hans/docs/agnes-image-25-flash :
 *  - endpoint `POST https://api.agnes-ai.cn/v1/images/generations`
 *  - `Authorization: Bearer <key>`
 *  - model `agnes-image-2.5-flash`
 *  - the input image and output format live under `extra_body`;
 *    no top-level `response_format` and no `tags: ["img2img"]`.
 *
 * `apihub.agnes-ai.com` is kept as an optional region so keys issued on the
 * international gateway still work. The active endpoint is resolved on every
 * request through [baseUrlProvider], so a region change in Settings takes
 * effect immediately without rebuilding the ViewModel.
 *
 * Both URLs are injectable so the whole HTTP exchange can be exercised against
 * a local server in tests without reaching the real service.
 */
class AgnesImageService(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val baseUrlProvider: (() -> String)? = null
) {
    private fun endpoint(): String =
        (baseUrlProvider?.invoke()?.takeIf { it.isNotBlank() } ?: baseUrl)
            .trimEnd('/') + ENDPOINT_PATH

    private fun host(): String = runCatching { URL(endpoint()).host }.getOrDefault("Agnes")

    /**
     * OkHttp is used instead of HttpURLConnection because its non-2xx body is
     * always readable: several HttpURLConnection implementations (including
     * the Robolectric test runtime) discard the JSON error body, which used to
     * hide the real reason such as "无效的令牌" from the user.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((connectTimeoutMs + readTimeoutMs).toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
     * Verifies a saved key with one minimal, documented text-to-image request.
     * Only the HTTP status is interpreted, so no generated image is downloaded.
     * A 2xx (and even a 400/429, which prove the auth layer accepted the key)
     * means the key itself is good; 401/403 means it is not.
     */
    suspend fun verifyApiKey(apiKey: String): AgnesKeyCheck = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            return@withContext AgnesKeyCheck(
                AgnesKeyStatus.INVALID,
                "尚未保存密钥，请先输入并保存"
            )
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("prompt", VERIFY_PROMPT)
            put("size", DEFAULT_SIZE)
            put("extra_body", JSONObject().put("response_format", "url"))
        }
        try {
            post(key, body)
            AgnesKeyCheck(
                AgnesKeyStatus.VALID,
                "密钥有效 · 已连通 ${host()}，可以开始 AI 修图",
                200
            )
        } catch (e: AgnesException) {
            val code = e.httpCode
            when {
                code == 401 || code == 403 -> AgnesKeyCheck(
                    AgnesKeyStatus.INVALID,
                    buildString {
                        append("密钥无效或没有权限")
                        e.serverMessage?.let { append("：$it") }
                        append("。请核对密钥，或切换「接入区域」后重试")
                    },
                    code
                )
                code == 429 -> AgnesKeyCheck(
                    AgnesKeyStatus.SERVICE_ISSUE,
                    "密钥已通过认证，但当前额度不足或请求受限，请稍后再试",
                    code
                )
                code != null && code >= 500 -> AgnesKeyCheck(
                    AgnesKeyStatus.SERVICE_ISSUE,
                    "Agnes 服务暂时不可用（HTTP $code），请稍后再试",
                    code
                )
                code == null -> AgnesKeyCheck(
                    AgnesKeyStatus.NETWORK_ISSUE,
                    e.message ?: "网络错误，请检查网络后重试"
                )
                else -> AgnesKeyCheck(
                    AgnesKeyStatus.VALID,
                    buildString {
                        append("密钥已通过认证（端点 ${host()} 返回 HTTP $code")
                        e.serverMessage?.let { append("：$it") }
                        append("），可直接尝试 AI 修图")
                    },
                    code
                )
            }
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
            val serverMessage = extractServerMessage(json)
            throw AgnesException(
                serverMessage?.let { "Agnes：$it" } ?: "Agnes 未返回图片数据，请稍后重试",
                serverMessage = serverMessage
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
        val request = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val server = text.takeIf { it.isNotBlank() }?.let(::extractServerMessage)
                    throw AgnesException(
                        friendlyError(response.code, server),
                        httpCode = response.code,
                        serverMessage = server
                    )
                }
                return text
            }
        } catch (e: AgnesException) {
            throw e
        } catch (e: IOException) {
            throw AgnesException("无法连接 Agnes 服务：${e.message ?: "网络错误"}", cause = e)
        }
    }

    private fun download(imageUrl: String): ByteArray {
        val request = Request.Builder()
            .url(imageUrl)
            .header("Accept", "image/*")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AgnesException(
                        "无法下载 Agnes 生成的图片（HTTP ${response.code}）",
                        httpCode = response.code
                    )
                }
                val bytes = response.body?.byteStream()?.use(::readBytes) ?: ByteArray(0)
                if (bytes.isEmpty()) throw AgnesException("Agnes 生成的图片为空")
                return bytes
            }
        } catch (e: AgnesException) {
            throw e
        } catch (e: IOException) {
            throw AgnesException("无法下载 Agnes 生成的图片：${e.message ?: "网络错误"}", cause = e)
        }
    }

    /**
     * Pulls `error.message` out of an Agnes error body. A valid JSON body that
     * simply has no error field (e.g. `{"data":[]}`) yields null, so callers
     * do not mistake the whole response for a server reason.
     */
    internal fun extractServerMessage(text: String): String? = try {
        val json = JSONObject(text)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("message").takeIf { it.isNotBlank() && it != "null" }
    } catch (e: Exception) {
        text.take(120).takeIf { it.isNotBlank() }
    }

    internal fun friendlyError(code: Int, serverMessage: String?): String {
        val suffix = serverMessage?.let { "：$it" } ?: ""
        return when (code) {
            400 -> "请求参数有误，请检查提示词或图片$suffix"
            401, 403 -> buildString {
                append("API 密钥无效或没有权限$suffix")
                append("。请在「设置 → AI 修图」核对密钥与接入区域")
                append("（当前端点 ${host()}）")
            }
            404 -> "Agnes 接口不存在（HTTP 404），请检查接入区域$suffix"
            429 -> "请求过于频繁或额度不足，请稍后再试$suffix"
            in 500..599 -> "Agnes 服务暂时不可用（HTTP $code），请稍后再试$suffix"
            else -> "Agnes 请求失败（HTTP $code）$suffix"
        }
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
        /**
         * Latest Agnes image model, per
         * https://www.agnes-ai.cn/zh-Hans/docs/agnes-image-25-flash
         */
        const val MODEL = "agnes-image-2.5-flash"

        /** Official China endpoint from the API Reference section of those docs. */
        const val DEFAULT_BASE_URL = "https://api.agnes-ai.cn"

        /** Optional international gateway, for keys issued on apihub. */
        const val GLOBAL_BASE_URL = "https://apihub.agnes-ai.com"

        const val REGION_CN = "cn"
        const val REGION_GLOBAL = "global"

        fun baseUrlFor(region: String): String =
            if (region == REGION_GLOBAL) GLOBAL_BASE_URL else DEFAULT_BASE_URL

        fun hostFor(region: String): String =
            if (region == REGION_GLOBAL) "apihub.agnes-ai.com" else "api.agnes-ai.cn"

        /** Shows just enough of a saved key for the user to recognise it. */
        fun maskKey(key: String): String {
            val value = key.trim()
            return when {
                value.isEmpty() -> ""
                value.length <= 10 -> value.take(2) + "•".repeat(6) + value.takeLast(2)
                else -> value.take(4) + "•".repeat(8) + value.takeLast(4)
            }
        }

        const val DEFAULT_SIZE = "1K"
        const val DEFAULT_MIME = "image/jpeg"
        const val NO_KEY_MESSAGE = "尚未配置 Agnes API 密钥，请先在「设置」中录入并保存"
        private const val ENDPOINT_PATH = "/v1/images/generations"
        private const val RESPONSE_FORMAT = "b64_json"
        private const val VERIFY_PROMPT = "a small solid red square"
        private const val CONNECT_TIMEOUT_MS = 20_000
        // Image generation may take tens of seconds; the docs recommend 60-360s.
        private const val READ_TIMEOUT_MS = 180_000
        private const val MAX_IMAGE_BYTES = 48 * 1024 * 1024
        private const val USER_AGENT = "FujiKon/1.7 Android"
    }
}
