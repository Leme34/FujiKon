package com.fujikon.app.ncp

/** The three Nikon custom Picture Control containers. */
enum class PictureControlFormat {
    NCP, NP2, NP3
}

/** One of the eight Nikon "Color Blender" hue bands. */
data class ColorBlendValue(
    val hue: Int = 0,
    val chroma: Int = 0,
    val brightness: Int = 0
)

/** Nikon "Color Grading" tonal zone. Hue is 0..360, the rest -100..100. */
data class ColorGradingZone(
    val hue: Int = 0,
    val chroma: Int = 0,
    val brightness: Int = 0
)

data class ColorGrading(
    val highlights: ColorGradingZone = ColorGradingZone(),
    val midtones: ColorGradingZone = ColorGradingZone(),
    val shadows: ColorGradingZone = ColorGradingZone(),
    val blending: Int = 50,
    val balance: Int = 0
)

/**
 * A decoded Nikon Picture Control.
 *
 * [curve] is the 256-entry, 0..32767 tone LUT stored inside the file. It is the
 * heart of an NCP recipe and is also present in many NP3 recipes.
 */
data class PictureControl(
    val format: PictureControlFormat,
    val version: String,
    val name: String,
    val baseProfile: Int,
    val baseProfileName: String,
    val curve: IntArray?,
    val sharpening: Float = 0f,
    val clarity: Float = 0f,
    val midRangeSharpening: Float = 0f,
    val contrast: Int = 0,
    val brightness: Int = 0,
    val saturation: Int = 0,
    val hue: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val whites: Int = 0,
    val blacks: Int = 0,
    val filter: Int = 0,
    val toning: Int = 0,
    val toningStrength: Int = 0,
    val colorBlender: List<ColorBlendValue> = emptyList(),
    val colorGrading: ColorGrading = ColorGrading(),
    val description: String? = null
) {
    val isMonochrome: Boolean
        get() = baseProfile == BaseProfiles.MONOCHROME

    val hasCurve: Boolean
        get() = curve != null && curve.size >= 256

    val hasColorBlender: Boolean
        get() = colorBlender.any { it.hue != 0 || it.chroma != 0 || it.brightness != 0 }

    val hasColorGrading: Boolean
        get() = colorGrading.highlights != ColorGradingZone() ||
            colorGrading.midtones != ColorGradingZone() ||
            colorGrading.shadows != ColorGradingZone()

    /** A compact list of 33 points suitable for drawing the curve. */
    fun curvePreviewPoints(): List<Pair<Float, Float>> {
        val source = curve ?: return listOf(0f to 0f, 1f to 1f)
        return (0..32).map { i ->
            val index = (i * 255 / 32).coerceIn(0, 255)
            (index / 255f) to (source[index] / 32767f)
        }
    }
}

object BaseProfiles {
    const val FLEXIBLE_COLOR = 0x0020
    const val STANDARD = 0x0001
    const val VIVID = 0x00C3
    const val NEUTRAL = 0x03C2
    const val PORTRAIT = 0x0486
    const val LANDSCAPE = 0x04C7
    const val MONOCHROME = 0x064D

    fun nameOf(id: Int): String = when (id) {
        FLEXIBLE_COLOR -> "Flexible Color"
        STANDARD -> "标准 Standard"
        VIVID -> "鲜艳 Vivid"
        NEUTRAL -> "自然 Neutral"
        PORTRAIT -> "人像 Portrait"
        LANDSCAPE -> "风景 Landscape"
        MONOCHROME -> "单色 Monochrome"
        0x0000 -> "自动 Auto"
        else -> "基准 0x%04X".format(id)
    }
}
