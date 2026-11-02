package com.fujikon.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One Lightroom-style HSL colour band. Values use the same -100..100 scale as the sliders. */
data class HslBand(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f
) {
    fun normalized(): HslBand = copy(
        hue = hue.coerceIn(-EditAdjustments.TONE_RANGE, EditAdjustments.TONE_RANGE),
        saturation = saturation.coerceIn(-EditAdjustments.TONE_RANGE, EditAdjustments.TONE_RANGE),
        luminance = luminance.coerceIn(-EditAdjustments.TONE_RANGE, EditAdjustments.TONE_RANGE)
    )

    fun isNeutral(tolerance: Float = 0.005f): Boolean =
        abs(hue) <= tolerance && abs(saturation) <= tolerance && abs(luminance) <= tolerance
}

/**
 * Eight-band HSL adjustment (red, orange, yellow, green, aqua, blue, purple,
 * magenta). The bands are stored in a fixed order so persisted presets remain
 * stable across app versions.
 */
data class HslAdjustments(
    val bands: List<HslBand> = List(BAND_COUNT) { HslBand() }
) {
    fun normalized(): HslAdjustments {
        val safe = List(BAND_COUNT) { index -> bands.getOrElse(index) { HslBand() }.normalized() }
        return copy(bands = safe)
    }

    fun isNeutral(tolerance: Float = 0.005f): Boolean = bands.all { it.isNeutral(tolerance) }

    fun band(index: Int): HslBand = bands.getOrElse(index) { HslBand() }.normalized()

    fun withBand(index: Int, band: HslBand): HslAdjustments {
        if (index !in 0 until BAND_COUNT) return this
        val updated = List(BAND_COUNT) { position ->
            if (position == index) band.normalized() else this.band(position)
        }
        return copy(bands = updated)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("bands", JSONArray().apply {
            List(BAND_COUNT) { index ->
                val band = band(index)
                put(JSONArray().put(band.hue.toDouble()).put(band.saturation.toDouble()).put(band.luminance.toDouble()))
            }
        })
    }

    companion object {
        const val BAND_COUNT = 8

        val BandNames = listOf("红", "橙", "黄", "绿", "青", "蓝", "紫", "洋红")

        /** Approximate sRGB colours used by the band selector in the UI. */
        val BandColors = listOf(
            0xFFE5484D.toInt(),
            0xFFF08A3C.toInt(),
            0xFFE8C547.toInt(),
            0xFF63B36B.toInt(),
            0xFF4CB8C4.toInt(),
            0xFF4B7BD9.toInt(),
            0xFF8B67D8.toInt(),
            0xFFD45B9E.toInt()
        )

        val Neutral = HslAdjustments()

        fun fromJson(json: JSONObject?): HslAdjustments {
            if (json == null) return Neutral
            val array = json.optJSONArray("bands") ?: return Neutral
            val parsed = List(BAND_COUNT) { index ->
                val values = array.optJSONArray(index) ?: return@List HslBand()
                HslBand(
                    hue = values.optDouble(0, 0.0).toFloat(),
                    saturation = values.optDouble(1, 0.0).toFloat(),
                    luminance = values.optDouble(2, 0.0).toFloat()
                )
            }
            return HslAdjustments(parsed).normalized()
        }
    }
}
