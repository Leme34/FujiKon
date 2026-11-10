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
            curve = buildToneCurve(baseId),
            sharpening = sharpening * CLOUD_DETAIL_SCALE,
            clarity = clarity * CLOUD_DETAIL_SCALE,
            midRangeSharpening = middleRangeSharp * CLOUD_DETAIL_SCALE,
            contrast = contrast ?: 0,
            saturation = saturation,
            highlights = highlight ?: 0,
            shadows = shadow ?: 0,
            whites = whiteLevel ?: 0,
            blacks = blackLevel ?: 0,
            description = description
        )
    }

    /**
     * Synthesizes a 256-point tone curve approximating the base Picture Control.
     *
     * Nikon Imaging Cloud publishes only simple parameters and returns the
     * contrast/zone fields as "gamma", so the real LUT is not in the payload.
     * A per-profile S-curve gives the imported recipe a visible, Nikon-like
     * rendering instead of the previous identity curve (which made the recipe
     * look like it changed nothing).
     */
    private fun buildToneCurve(baseProfile: Int): IntArray? {
        // Per-profile baseline contrast approximating Nikon factory curves.
        // FLEXIBLE_COLOR gets a mild S-curve so cloud recipes are not linear.
        val amount = when (baseProfile) {
            BaseProfiles.VIVID -> 0.20f
            BaseProfiles.PORTRAIT -> 0.02f
            BaseProfiles.LANDSCAPE -> 0.14f
            BaseProfiles.MONOCHROME -> 0.16f
            BaseProfiles.NEUTRAL -> 0.07f
            BaseProfiles.STANDARD -> 0.11f
            else -> 0.12f // FLEXIBLE_COLOR
        }
        return IntArray(256) { i ->
            val x = i / 255f
            val s = x * x * (3f - 2f * x)
            val target = if (amount >= 0f) s else (2f * x - s)
            var y = x + (target - x) * amount
            y = if (y < 0f) 0f else if (y > 1f) 1f else y
            (y * 32767f + 0.5f).toInt()
        }
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
    val preset = Preset.from(
        control = control,
        id = "",
        fileName = "",
        fileSize = 0,
        isSample = false,
        contentHash = contentHash(),
        sourceName = SOURCE_NAME
    ).copy(name = name)
    return CloudLookCalibration.apply(id, preset)
}

fun NikonCloudRecipe.contentHash(): String = "nic:$id"

private const val SOURCE_NAME = "Nikon Imaging Cloud"

/**
 * 随 App 一起发布的云创预置：摄影师「芒果小明」（B 站 @芒果和小明，UID 30982770）
 * 的 Vitality Film by Peng Mango。
 *
 * 依据：
 *  - recipeId、名称与 pictureControl 数值来自 imagingcloud.nikon.com 的公开详情接口
 *    （color-audit/data/raw/detail_01JSBM30XWQ6GG4XK0KSZ3FQEK.json）。
 *  - 创作者归属来自尼康官方账号 2025-04-23 的上线公告，以及第三方实拍笔记
 *    （见 color-audit 报告；不使用读音猜测）。
 *
 * 官方没有公开 256 点色调曲线，因此这里复用 CloudLookCalibration 中同一 recipeId
 * 的统计风格拟合 LUT。该 LUT 已承担 contrast −4 / highlight +54 / shadow −17 /
 * white 0 / black −3 的影调倾向；toPreset 后这五项会被校准表归零，避免重复施加。
 * 下列官方数值保留在数据里仅用于对照记录。
 *
 * Preset 模型没有对应字段、文章也未给出数值的参数：白平衡偏移、动态 D-Lighting、
 * 颗粒、暗角。第三方视频只给出「白平衡提高 / 自然光自适应约 5400K 固定 / 曝光补偿
 * 拉高 / D-Lighting 关闭」的拍摄建议，没有可写入 Preset 的具体档位，故不填猜测值。
 */
val PENG_MANGO_VITALITY_FILM: NikonCloudRecipe = NikonCloudRecipe(
    id = "01JSBM30XWQ6GG4XK0KSZ3FQEK",
    name = "Vitality Film by Peng Mango",
    creator = "芒果小明",
    pictureControlName = "VitalityFilm_Pmango",
    publishedDate = "2025-04-24T01:32:28+00:00"
)

val PENG_MANGO_VITALITY_FILM_DETAIL: NikonCloudDetail = NikonCloudDetail(
    brief = "模拟胶片的风格，对比度适中，色彩较浓郁，加强中暗部的青色。",
    note = "创作者的说明\n该色彩方案模拟了胶片的风格，对比度适中，色彩较浓郁。" +
        "其加强了中暗部的青色，并且保留了人物健康温暖的肤色，可以应对不同天气和环境，" +
        "是一个长期可以使用的色彩方案。",
    control = CloudPictureControl(
        baseName = "FLEXIBLE COLOR",
        sharpening = 3.50f,
        middleRangeSharp = 1.50f,
        clarity = -1.00f,
        saturation = 0,
        contrast = -4,
        highlight = 54,
        shadow = -17,
        whiteLevel = 0,
        blackLevel = -3
    ),
    camera = "NIKON Z f",
    lens = "AF-S NIKKOR 24-70mm f/2.8E ED VR"
)

/** 首次启动或恢复内置示例时写入预置列表的云创条目。 */
fun builtInCloudPresets(): List<Preset> = listOf(
    PENG_MANGO_VITALITY_FILM.toPreset(PENG_MANGO_VITALITY_FILM_DETAIL)
) + builtInCommunityPresets() + builtInWorkStylePresets()

/**
 * 云创锐化整体缩放（对所有云创 recipe 生效，不是 4 条专属）。
 *
 * 官方样张的边缘能量约为 App 现渲染的 0.6 倍；在 color-audit 的拟合中，
 * 把 sharpening/clarity/midRange 同乘该系数后，边缘能量比从 1.39~1.99
 * 降到 1.15~1.24，SSIM 与 ΔE00 同步改善。下限取 0.30，再低等于关掉
 * 发布值里的锐化，属于极端参数。
 */
private const val CLOUD_DETAIL_SCALE = 0.30f

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
    val satRaw = controlJson?.optString("saturation")
    val saturation = satRaw?.trim().orEmpty().let { if (it.isEmpty()) 0 else intOrNull(it) ?: 0 }
    val intField = { raw: String? ->
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t.equals("gamma", ignoreCase = true)) null else intOrNull(t)
    }
    val control = CloudPictureControl(
        baseName = controlJson?.optString("baseName")?.trim().orEmpty().ifBlank { "FLEXIBLE COLOR" },
        sharpening = numberOrZero(controlJson?.optString("sharpening")),
        middleRangeSharp = numberOrZero(controlJson?.optString("middleRangeSharp")),
        clarity = numberOrZero(controlJson?.optString("clarity")),
        saturation = saturation,
        contrast = intField(controlJson?.optString("contrast")),
        highlight = intField(controlJson?.optString("highlight")),
        shadow = intField(controlJson?.optString("shadow")),
        whiteLevel = intField(controlJson?.optString("whiteLevel")),
        blackLevel = intField(controlJson?.optString("blackLevel"))
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
