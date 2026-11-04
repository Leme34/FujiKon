package com.fujikon.app.nic

import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One picture-control entry as published on Nikon Imaging Cloud. */
data class NikonCloudRecipe(
    val id: String,
    val name: String,
    val creator: String = "",
    val pictureControlName: String = "",
    val thumbnailUrl: String? = null,
    val publishedDate: String = "",
    val isNew: Boolean = false
)

/** Settings decoded from the recipe detail endpoint. */
data class NikonCloudDetail(
    val brief: String,
    val note: String,
    val control: CloudPictureControl,
    val camera: String? = null,
    val lens: String? = null,
    val thumbnailUrl: String? = null
)

/**
 * The parameters Nikon publishes for an Imaging Recipe's picture control.
 * Fields the cloud returns as "gamma" are left null: the actual tone curve is
 * not part of the public payload, so the app cannot reproduce it exactly.
 */
data class CloudPictureControl(
    val baseName: String = "FLEXIBLE COLOR",
    val sharpening: Float = 0f,
    val middleRangeSharp: Float = 0f,
    val clarity: Float = 0f,
    val saturation: Int = 0,
    val contrast: Int? = null,
    val highlight: Int? = null,
    val shadow: Int? = null,
    val whiteLevel: Int? = null,
    val blackLevel: Int? = null
) {
    fun toPictureControl(name: String, description: String?): PictureControl {
        val baseId = baseProfileId(baseName)
        return PictureControl(
            format = PictureControlFormat.NP3,
            version = "1.0",
            name = name,
            baseProfile = baseId,
            baseProfileName = if (baseId == BaseProfiles.FLEXIBLE_COLOR) {
                "Flexible Color"
            } else {
                BaseProfiles.nameOf(baseId)
            },
            curve = null,
            sharpening = sharpening,
            clarity = clarity,
            midRangeSharpening = middleRangeSharp,
            contrast = contrast ?: 0,
            saturation = saturation,
            highlights = highlight ?: 0,
            shadows = shadow ?: 0,
            whites = whiteLevel ?: 0,
            blacks = blackLevel ?: 0,
            description = description
        )
    }

    companion object {
        private fun baseProfileId(baseName: String): Int = when (baseName.trim().uppercase()) {
            "STANDARD" -> BaseProfiles.STANDARD
            "VIVID" -> BaseProfiles.VIVID
            "NEUTRAL" -> BaseProfiles.NEUTRAL
            "PORTRAIT" -> BaseProfiles.PORTRAIT
            "LANDSCAPE" -> BaseProfiles.LANDSCAPE
            "MONOCHROME", "MONOCHROME.01" -> BaseProfiles.MONOCHROME
            else -> BaseProfiles.FLEXIBLE_COLOR
        }
    }
}

/** Builds the in-app preset for a downloaded recipe. */
fun NikonCloudRecipe.toPreset(detail: NikonCloudDetail): Preset {
    val control = detail.control.toPictureControl(
        name = pictureControlName.ifBlank { name },
        description = detail.note.ifBlank { detail.brief }
    )
    return Preset.from(
        control = control,
        id = "",
        fileName = "",
        fileSize = 0,
        isSample = false,
        contentHash = contentHash(),
        sourceName = SOURCE_NAME
    ).copy(name = name)
}

fun NikonCloudRecipe.contentHash(): String = "nic:$id"

private const val SOURCE_NAME = "Nikon Imaging Cloud"

/**
 * Read-only client for the public recipe library on imagingcloud.nikon.com.
 *
 * The web app talks to the same BFF endpoints without requiring a Nikon
 * account, so the app can browse the official recipes and import the published
 * picture-control parameters directly.
 */
class NikonCloudService(
    baseUrl: String = DEFAULT_BASE_URL,
    private val country: String = "cn",
    private val language: String = "zh-cn"
) {
    private val base = baseUrl.trimEnd('/') + "/"

    suspend fun fetchCatalog(): List<NikonCloudRecipe> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("ownerUserId", "nikon")
            put("bookId", "")
            put("sortType", 0)
            put("lang_code", language)
            put("country", country)
        }
        val json = post("bff/recipe/release", body)
        val recipes = parseCloudCatalog(json.toString())
        if (recipes.isEmpty()) {
            throw NikonCloudException("尼康云创没有返回色彩方案，接口可能已经变化")
        }
        recipes
    }

    suspend fun fetchDetail(recipeId: String): NikonCloudDetail = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("country", country)
            put("language", language)
            put("recipeId", recipeId)
            put("sortType", "0")
            put("isPagination", "false")
            put("isQueryLang", "false")
        }
        val json = post("bff/recipe/release/detail", body)
        parseCloudDetail(json.toString())
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            doInput = true
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setFixedLengthStreamingMode(payload.size)
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.let(::readText)?.takeIf { it.isNotBlank() }
                throw NikonCloudException(
                    "尼康云创请求失败（HTTP $code）" + (message?.let { "：${it.take(120)}" } ?: "")
                )
            }
            val text = connection.inputStream.use(::readText)
            return JSONObject(text)
        } catch (e: IOException) {
            throw NikonCloudException("无法连接尼康云创：${e.message ?: "网络错误"}")
        } finally {
            connection.disconnect()
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.user.cwp.imagingcloud.nikon.com/"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 20000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}

class NikonCloudException(message: String) : Exception(message)

/** Parses the JSON returned by `bff/recipe/release`. */
internal fun parseCloudCatalog(json: String): List<NikonCloudRecipe> {
    val root = JSONObject(json)
    val array = root.optJSONArray("recipe") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            add(
                NikonCloudRecipe(
                    id = id,
                    name = name,
                    creator = item.optString("creatorName").trim(),
                    pictureControlName = item.optString("picture_controll_name").trim(),
                    thumbnailUrl = item.optJSONObject("img")?.optString("url")?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    publishedDate = item.optString("registration_date").trim(),
                    isNew = item.optBoolean("is_new", false)
                )
            )
        }
    }
}

/** Parses the JSON returned by `bff/recipe/release/detail`. */
internal fun parseCloudDetail(json: String): NikonCloudDetail {
    val root = JSONObject(json)
    val recipe = root.optJSONObject("recipe")
        ?: throw NikonCloudException("尼康云创返回的说明为空")
    val controlJson = recipe.optJSONObject("pictureControl")
    val control = CloudPictureControl(
        baseName = controlJson?.optString("baseName")?.trim().orEmpty().ifBlank { "FLEXIBLE COLOR" },
        sharpening = numberOrZero(controlJson?.optString("sharpening")),
        middleRangeSharp = numberOrZero(controlJson?.optString("middleRangeSharp")),
        clarity = numberOrZero(controlJson?.optString("clarity")),
        saturation = intOrNull(controlJson?.optString("saturation")) ?: 0,
        contrast = intOrNull(controlJson?.optString("contrast")),
        highlight = intOrNull(controlJson?.optString("highlight")),
        shadow = intOrNull(controlJson?.optString("shadow")),
        whiteLevel = intOrNull(controlJson?.optString("whiteLevel")),
        blackLevel = intOrNull(controlJson?.optString("blackLevel"))
    )
    val note = recipe.optString("recipeDetail").trim()
    val brief = cloudBrief(
        outline = recipe.optString("recipeOutline"),
        detail = note
    )
    val thumbnail = recipe.optString("thumbnail").trim().takeIf { it.isNotEmpty() }
    return NikonCloudDetail(
        brief = brief,
        note = note,
        control = control,
        camera = recipe.optString("camera").trim().takeIf { it.isNotEmpty() },
        lens = recipe.optString("lens").trim().takeIf { it.isNotEmpty() },
        thumbnailUrl = thumbnail
    )
}

/** A list-friendly one-liner taken from the creator's note. */
internal fun cloudBrief(outline: String?, detail: String?): String {
    val outlineText = collapse(outline)
    if (outlineText.isNotEmpty()) return shorten(outlineText)

    var text = (detail ?: "").trim()
    for (prefix in BRIEF_PREFIXES) {
        if (text.startsWith(prefix)) {
            text = text.removePrefix(prefix).trimStart()
        }
    }
    val collapsed = collapse(text)
    if (collapsed.isEmpty()) return ""
    return shorten(collapsed)
}

private val BRIEF_PREFIXES = listOf(
    "创作者的说明",
    "創作者的說明",
    "Creator's note",
    "Creator’s note",
    "Creator's Note"
)

private fun collapse(value: String?): String =
    value.orEmpty().replace(Regex("\\s+"), " ").trim()

private fun shorten(text: String): String {
    val limit = 96
    val sentenceEnd = text.indexOfFirst { it in "。！？.!?" }
    if (sentenceEnd in 12 until limit) {
        return text.take(sentenceEnd + 1)
    }
    if (text.length <= limit) return text
    return text.take(limit).trimEnd() + "…"
}

private fun numberOrZero(raw: String?): Float =
    raw.orEmpty().trim().toFloatOrNull() ?: 0f

private fun intOrNull(raw: String?): Int? =
    raw.orEmpty().trim().toIntOrNull()
