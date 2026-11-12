package com.fujikon.app.data

import com.fujikon.app.ncp.ColorBlendValue
import com.fujikon.app.ncp.ColorGrading
import com.fujikon.app.ncp.ColorGradingZone
import com.fujikon.app.ncp.PictureControl
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.math.roundToInt

/**
 * App-side preset. It keeps the original binary for re-export, the decoded
 * curve, and a few user-editable fields (name / note / favourite).
 */
data class Preset(
    val id: String,
    val name: String,
    val format: String,
    val version: String,
    val sourceName: String,
    val fileName: String,
    val fileSize: Int,
    val baseProfile: Int,
    val baseProfileName: String,
    val curve: IntArray?,
    /** Optional RGB master curve layered on top of the Nikon rendering. */
    val toneCurve: ToneCurve? = null,
    /** Optional red / green / blue curves layered after the master curve. */
    val channelCurves: ChannelToneCurves = ChannelToneCurves.Neutral,
    /** Lightroom-style global adjustments layered after the preset rendering. */
    val adjustments: EditAdjustments = EditAdjustments.Neutral,
    val sharpening: Float,
    val clarity: Float,
    val midRangeSharpening: Float,
    val contrast: Int,
    val brightness: Int,
    val saturation: Int,
    val hue: Int,
    val highlights: Int,
    val shadows: Int,
    val whites: Int,
    val blacks: Int,
    val filter: Int,
    val toning: Int,
    val toningStrength: Int,
    val colorBlender: List<ColorBlendValue>,
    val colorGrading: ColorGrading,
    val description: String?,
    val note: String,
    val favorite: Boolean,
    val importedAt: Long,
    val isSample: Boolean,
    val contentHash: String = "",
    /**
     * 保色影调 LUT（256 点，0..255 的 luma 域）。渲染时按像素亮度查表，
     * 三通道按同一 scale + lift 缩放，且高光触顶时等比回缩，RGB 比例严格
     * 不变 —— 只改变亮度与层次，不改变色相/饱和度。逐通道 curve 会拉伸
     * 彩色像素的 RGB 比例而引入色偏，这里从根上规避。仅内置影调预置使用，
     * 其余预置为 null，行为完全不变。
     */
    val lumaToneLut: IntArray? = null,
    /**
     * 可选 3D LUT 的 assets 路径（如 `cloud_luts/cloud_lut_01.bin`）。非空时
     * [com.fujikon.app.render.ImageRenderer] 通过
     * [com.fujikon.app.render.CubeLutProvider] 加载对应 LUT，用亮度锚定的相对
     * 变换叠加官方色彩风格。仅云创 LUT 方案使用，其余预置为 null，行为不变。
     */
    val cubeLutAsset: String? = null
) {
    val hasCurve: Boolean get() = curve != null && curve.size >= 256

    val isNone: Boolean get() = id == NONE_ID

    val hasToneCurve: Boolean
        get() = (toneCurve != null && !toneCurve.isLinear()) || !channelCurves.isNeutral

    val hasAdjustments: Boolean get() = !adjustments.isNeutral()

    /** Presets saved from the in-app curve editor have no binary source file. */
    val isDerived: Boolean get() = fileName.isBlank()

    val hasColorBlender: Boolean
        get() = colorBlender.size >= 8 && colorBlender.any {
            it.hue != 0 || it.chroma != 0 || it.brightness != 0
        }

    val hasColorGrading: Boolean
        get() = listOf(colorGrading.highlights, colorGrading.midtones, colorGrading.shadows)
            .any { it.hue != 0 || it.chroma != 0 || it.brightness != 0 }

    /** Curve points of the original Nikon LUT, used as the editor reference. */
    fun baseCurvePoints(count: Int = 64): List<Pair<Float, Float>> {
        val safeCount = count.coerceAtLeast(2)
        val source = curve
        return (0..safeCount).map { i ->
            val x = i / safeCount.toFloat()
            val y = if (source != null && source.size >= 256) {
                source[(x * 255f).roundToInt().coerceIn(0, 255)] / 32767f
            } else {
                x
            }
            x to y.coerceIn(0f, 1f)
        }
    }

    /**
     * Effective curve shown in cards and the preset detail screen. Only the
     * master curve can be drawn as a single line; per-channel curves are
     * reflected in the rendered thumbnail instead.
     */
    fun effectiveCurvePoints(count: Int = 64): List<Pair<Float, Float>> {
        val curveOverride = toneCurve
        return baseCurvePoints(count).map { (x, y) ->
            x to (curveOverride?.apply(y) ?: y).coerceIn(0f, 1f)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("format", format)
        put("version", version)
        put("sourceName", sourceName)
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("baseProfile", baseProfile)
        put("baseProfileName", baseProfileName)
        put("curve", curve?.let { encodeCurve(it) } ?: JSONObject.NULL)
        put("toneCurve", toneCurve?.toJson() ?: JSONObject.NULL)
        // Older app versions ignore this key and still honour "toneCurve",
        // so presets with only a master curve keep their old JSON shape.
        if (!channelCurves.isNeutral) put("channelCurves", channelCurves.toJson())
        put("adjustments", adjustments.toJson())
        put("sharpening", sharpening.toDouble())
        put("clarity", clarity.toDouble())
        put("midRangeSharpening", midRangeSharpening.toDouble())
        put("contrast", contrast)
        put("brightness", brightness)
        put("saturation", saturation)
        put("hue", hue)
        put("highlights", highlights)
        put("shadows", shadows)
        put("whites", whites)
        put("blacks", blacks)
        put("filter", filter)
        put("toning", toning)
        put("toningStrength", toningStrength)
        put("colorBlender", JSONArray().apply {
            colorBlender.forEach { value ->
                put(JSONArray().put(value.hue).put(value.chroma).put(value.brightness))
            }
        })
        put("colorGrading", colorGrading.toJson())
        put("description", description ?: JSONObject.NULL)
        put("note", note)
        put("favorite", favorite)
        put("importedAt", importedAt)
        put("isSample", isSample)
        put("contentHash", contentHash)
        // 保色影调 LUT：旧版本忽略该键，仍能正常读取其余字段。
        if (lumaToneLut != null && lumaToneLut.size >= 256) {
            put("lumaToneLut", JSONArray().apply { lumaToneLut.forEach { put(it) } })
        }
        // 3D LUT assets 路径：旧版本忽略该键，仍能正常读取其余字段。
        if (!cubeLutAsset.isNullOrBlank()) put("cubeLutAsset", cubeLutAsset)
    }

    companion object {
        const val NONE_ID = "__none__"

        /** Picker-only sentinel that leaves the source photo untouched. */
        fun none(): Preset = Preset(
            id = NONE_ID,
            name = "无",
            format = "NONE",
            version = "",
            sourceName = "原图",
            fileName = "",
            fileSize = 0,
            baseProfile = 0,
            baseProfileName = "原图",
            curve = null,
            toneCurve = null,
            channelCurves = ChannelToneCurves.Neutral,
            adjustments = EditAdjustments.Neutral,
            sharpening = 0f,
            clarity = 0f,
            midRangeSharpening = 0f,
            contrast = 0,
            brightness = 0,
            saturation = 0,
            hue = 0,
            highlights = 0,
            shadows = 0,
            whites = 0,
            blacks = 0,
            filter = 0,
            toning = 0,
            toningStrength = 0,
            colorBlender = emptyList(),
            colorGrading = ColorGrading(),
            description = null,
            note = "",
            favorite = false,
            importedAt = 0L,
            isSample = false,
            contentHash = ""
        )

        fun from(control: PictureControl, id: String, fileName: String, fileSize: Int, isSample: Boolean, importedAt: Long = System.currentTimeMillis(), contentHash: String = "", sourceName: String = fileName): Preset =
            Preset(
                id = id,
                name = control.name,
                format = control.format.name,
                version = control.version,
                sourceName = sourceName,
                fileName = fileName,
                fileSize = fileSize,
                baseProfile = control.baseProfile,
                baseProfileName = control.baseProfileName,
                curve = control.curve,
                toneCurve = null,
                channelCurves = ChannelToneCurves.Neutral,
                adjustments = EditAdjustments.Neutral,
                sharpening = control.sharpening,
                clarity = control.clarity,
                midRangeSharpening = control.midRangeSharpening,
                contrast = control.contrast,
                brightness = control.brightness,
                saturation = control.saturation,
                hue = control.hue,
                highlights = control.highlights,
                shadows = control.shadows,
                whites = control.whites,
                blacks = control.blacks,
                filter = control.filter,
                toning = control.toning,
                toningStrength = control.toningStrength,
                colorBlender = control.colorBlender,
                colorGrading = control.colorGrading,
                description = control.description,
                note = "",
                favorite = false,
                importedAt = importedAt,
                isSample = isSample,
                contentHash = contentHash
            )

        fun fromJson(json: JSONObject): Preset = Preset(
            id = json.getString("id"),
            name = json.optString("name", "未命名预设"),
            format = json.optString("format", "NCP"),
            version = json.optString("version", ""),
            sourceName = json.optString("sourceName", ""),
            fileName = json.optString("fileName", ""),
            fileSize = json.optInt("fileSize", 0),
            baseProfile = json.optInt("baseProfile", 0x03C2),
            baseProfileName = json.optString("baseProfileName", "未知"),
            curve = if (json.isNull("curve")) null else decodeCurve(json.optString("curve")),
            toneCurve = if (json.isNull("toneCurve")) null else {
                json.optJSONObject("toneCurve")?.let(ToneCurve::fromJson)
            },
            // Presets saved before per-channel curves existed have no such key.
            channelCurves = ChannelToneCurves.fromJson(json.optJSONObject("channelCurves")),
            adjustments = EditAdjustments.fromJson(json.optJSONObject("adjustments")),
            sharpening = json.optDouble("sharpening", 0.0).toFloat(),
            clarity = json.optDouble("clarity", 0.0).toFloat(),
            midRangeSharpening = json.optDouble("midRangeSharpening", 0.0).toFloat(),
            contrast = json.optInt("contrast", 0),
            brightness = json.optInt("brightness", 0),
            saturation = json.optInt("saturation", 0),
            hue = json.optInt("hue", 0),
            highlights = json.optInt("highlights", 0),
            shadows = json.optInt("shadows", 0),
            whites = json.optInt("whites", 0),
            blacks = json.optInt("blacks", 0),
            filter = json.optInt("filter", 0),
            toning = json.optInt("toning", 0),
            toningStrength = json.optInt("toningStrength", 0),
            colorBlender = json.optJSONArray("colorBlender")?.let { array ->
                (0 until array.length()).map { i ->
                    val triple = array.getJSONArray(i)
                    ColorBlendValue(triple.optInt(0), triple.optInt(1), triple.optInt(2))
                }
            } ?: emptyList(),
            colorGrading = json.optJSONObject("colorGrading")?.let(::colorGradingFromJson) ?: ColorGrading(),
            description = if (json.isNull("description")) null else json.optString("description"),
            note = json.optString("note", ""),
            favorite = json.optBoolean("favorite", false),
            importedAt = json.optLong("importedAt", 0L),
            isSample = json.optBoolean("isSample", false),
            contentHash = json.optString("contentHash", ""),
            lumaToneLut = json.optJSONArray("lumaToneLut")?.let { array ->
                if (array.length() >= 256) IntArray(256) { array.optInt(it).coerceIn(0, 255) }
                else null
            },
            cubeLutAsset = json.optString("cubeLutAsset", "").takeIf { it.isNotBlank() }
        )

        private fun encodeCurve(curve: IntArray): String {
            val buffer = ByteBuffer.allocate(curve.size * 2)
            curve.forEach { buffer.putShort((it.coerceIn(0, 32767)).toShort()) }
            return Base64.getEncoder().encodeToString(buffer.array())
        }

        private fun decodeCurve(text: String): IntArray? = runCatching {
            val bytes = Base64.getDecoder().decode(text)
            val buffer = ByteBuffer.wrap(bytes)
            IntArray(bytes.size / 2) { buffer.short.toInt() and 0xFFFF }
        }.getOrNull()
    }
}

private fun ColorGrading.toJson(): JSONObject = JSONObject().apply {
    put("highlights", highlights.toJson())
    put("midtones", midtones.toJson())
    put("shadows", shadows.toJson())
    put("blending", blending)
    put("balance", balance)
}

private fun ColorGradingZone.toJson(): JSONObject = JSONObject().apply {
    put("hue", hue)
    put("chroma", chroma)
    put("brightness", brightness)
}

private fun colorGradingFromJson(json: JSONObject): ColorGrading = ColorGrading(
    highlights = zoneFromJson(json.optJSONObject("highlights")),
    midtones = zoneFromJson(json.optJSONObject("midtones")),
    shadows = zoneFromJson(json.optJSONObject("shadows")),
    blending = json.optInt("blending", 50),
    balance = json.optInt("balance", 0)
)

private fun zoneFromJson(json: JSONObject?): ColorGradingZone {
    if (json == null) return ColorGradingZone()
    return ColorGradingZone(
        hue = json.optInt("hue", 0),
        chroma = json.optInt("chroma", 0),
        brightness = json.optInt("brightness", 0)
    )
}
