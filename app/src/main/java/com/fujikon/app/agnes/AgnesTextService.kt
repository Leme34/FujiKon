package com.fujikon.app.agnes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Client for the Agnes 3.0 Flash TEXT model, used to look at the current photo
 * and write an image-editing prompt for it.
 *
 * Implements the official docs at
 * https://www.agnes-ai.cn/zh-Hans/docs/agnes-30-flash :
 *  - endpoint `POST https://api.agnes-ai.cn/v1/chat/completions`
 *  - `Authorization: Bearer <key>`, `Content-Type: application/json`
 *  - model `agnes-3.0-flash`
 *  - `messages[].content` may be an array of `text` and `image_url` blocks
 *  - generated text lives at `choices[0].message.content`
 *
 * Unlike the Pro variant, 3.0 Flash is generally available: the docs list
 * pricing and do not mark it as "即将上线".
 *
 * The official docs describe image input as a publicly reachable `image_url`.
 * A base64 Data URI is used by default because that is the OpenAI-compatible
 * vision format accepted by most gateways and it lets the app analyse the
 * *current* photo without a third-party image host. Pass [imageUrlOverride]
 * when the account requires a real public URL instead.
 *
 * Transient failures (404 / model-not-found 400 / 5xx / network) still let
 * the caller fall back to on-device analysis via [AgnesException.httpCode]
 * and [AgnesException.serverMessage] instead of leaving the button dead.
 */
class AgnesTextService(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val baseUrlProvider: (() -> String)? = null
) {
    private fun endpoint(): String =
        (baseUrlProvider?.invoke()?.takeIf { it.isNotBlank() } ?: baseUrl)
            .trimEnd('/') + ENDPOINT_PATH

    private fun host(): String = runCatching { URL(endpoint()).host }.getOrDefault("Agnes")

    private val http = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((connectTimeoutMs + readTimeoutMs).toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends the current preview image to the text model and returns one clean,
     * ready-to-edit Chinese prompt (always <= 500 chars, no markdown/quotes).
     */
    suspend fun generateEditPrompt(
        apiKey: String,
        imageBytes: ByteArray,
        mimeType: String = DEFAULT_MIME,
        imageUrlOverride: String? = null,
        extraInstruction: String? = null
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw AgnesException(AgnesImageService.NO_KEY_MESSAGE)
        if (imageBytes.isEmpty() && imageUrlOverride.isNullOrBlank()) {
            throw AgnesException("没有可用于分析的图片")
        }
        val body = buildRequestBody(imageBytes, mimeType, imageUrlOverride, extraInstruction)
        parseCompletion(post(key, body))
    }

    /** Builds the exact Chat Completions payload from the docs. */
    internal fun buildRequestBody(
        imageBytes: ByteArray,
        mimeType: String,
        imageUrlOverride: String?,
        extraInstruction: String?
    ): JSONObject {
        val url = imageUrlOverride?.takeIf { it.isNotBlank() }
            ?: "data:$mimeType;base64," + Base64.getEncoder().encodeToString(imageBytes)
        val instruction = buildString {
            append(USER_INSTRUCTION)
            if (!extraInstruction.isNullOrBlank()) append(" 补充要求：").append(extraInstruction.trim())
        }
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", instruction)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", url))
            })
        }
        return JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("temperature", 0.4)
            put("max_tokens", 300)
            put("stream", false)
        }
    }

    /** Reads `choices[0].message.content`, tolerating array-shaped content. */
    internal fun parseCompletion(json: String): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw AgnesException("Agnes 文本模型返回了无法解析的内容")
        }
        val choices = root.optJSONArray("choices")
        val first = choices?.optJSONObject(0)
            ?: throw AgnesException(
                extractMessage(json)?.let { "Agnes：$it" } ?: "Agnes 未返回生成内容"
            )
        val message = first.optJSONObject("message")
        val raw = when (val content = message?.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") in setOf("text", "output_text")) {
                        append(block.optString("text"))
                    }
                }
            }
            else -> first.optString("text", "")
        }
        return cleanPrompt(raw)
    }

    /** Strips quotes, markdown fences and label prefixes; keeps it editable. */
    internal fun cleanPrompt(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) throw AgnesException("Agnes 文本模型返回了空提示词，请重试")
        // ``` fences
        if (text.startsWith("```")) {
            text = text.removePrefix("```").trimStart('\n', ' ')
            text = text.substringBefore("```").trim()
        }
        // Wrapping quotes, possibly with a label prefix.
        text = text.removePrefix("提示词：").removePrefix("提示词:").trim()
        text = text.trim('"', '\'', '`', '「', '」', '“', '”', '‘', '’', ' ', '\n', '\t')
        text = text.replace(Regex("\\s*\\n\\s*"), " ").trim()
        if (text.isEmpty()) throw AgnesException("Agnes 文本模型返回了空提示词，请重试")
        return text.take(500)
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
                    val server = text.takeIf { it.isNotBlank() }?.let(::extractMessage)
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
            throw AgnesException("无法连接 Agnes 文本模型：${e.message ?: "网络错误"}", cause = e)
        }
    }

    private fun extractMessage(text: String): String? = try {
        val json = JSONObject(text)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("message").takeIf { it.isNotBlank() && it != "null" }
    } catch (e: Exception) {
        text.take(120).takeIf { it.isNotBlank() }
    }

    private fun friendlyError(code: Int, serverMessage: String?): String {
        val suffix = serverMessage?.let { "：$it" } ?: ""
        return when (code) {
            400 -> "文本模型请求被拒绝（HTTP 400）$suffix。请确认账户已开通 $MODEL 访问权限"
            401, 403 -> "API 密钥无效或没有权限$suffix。请在「设置 → AI 修图」核对密钥与接入区域（当前端点 ${host()}）"
            404 -> "Agnes 文本模型接口不存在（HTTP 404）$suffix。请确认接入区域与 $MODEL 的账户权限"
            429 -> "请求过于频繁或额度不足，请稍后再试$suffix"
            in 500..599 -> "Agnes 文本模型服务暂时不可用（HTTP $code），请稍后再试$suffix"
            else -> "Agnes 文本模型请求失败（HTTP $code）$suffix"
        }
    }

    companion object {
        /** Official model name from https://www.agnes-ai.cn/zh-Hans/docs/agnes-30-flash */
        const val MODEL = "agnes-3.0-flash"

        /** Official China base URL; region mapping matches the image service. */
        const val DEFAULT_BASE_URL = "https://api.agnes-ai.cn"
        const val GLOBAL_BASE_URL = "https://apihub.agnes-ai.com"

        fun baseUrlFor(region: String): String =
            if (region == AgnesImageService.REGION_GLOBAL) GLOBAL_BASE_URL else DEFAULT_BASE_URL

        const val DEFAULT_MIME = "image/jpeg"

        private const val ENDPOINT_PATH = "/v1/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val USER_AGENT = "FujiKon Android"

        private const val SYSTEM_PROMPT =
            "你是资深摄影后期师和 AI 图像编辑提示词专家。用户会给你一张照片。" +
                "请用一句不超过 80 字的中文，针对这张照片实际存在的曝光、白平衡、影调层次、" +
                "色彩、清晰度或灰雾问题，给出最有效的优化方向，要具体可执行。" +
                "必须强调保留原构图、主体与画面内容，不做元素增删，输出自然真实的摄影级效果。" +
                "只输出这句提示词本身，不要解释、不要引号、不要编号、不要 Markdown、不要前后缀标签。"

        private const val USER_INSTRUCTION =
            "分析这张照片的曝光、色偏、反差、色彩与细节问题，给出一段可直接用于 AI 图像编辑的中文优化提示词。"
    }
}
