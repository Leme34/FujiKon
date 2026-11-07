package com.fujikon.app.data

import org.json.JSONObject
import kotlin.math.abs

/**
 * Lightroom-style "Point Color": pick a colour in the frame and shift only the
 * pixels close to it, without touching the rest of the image.
 *
 * [targetHue] is degrees on the HSL wheel (0=red, 120=green, 240=blue).
 * [hueRange] is the half-width of the selection in degrees; [minSaturation]
 * keeps near-greys out of the selection so a sky edit never shifts concrete.
 * All three shift values use the same -100..100 display units as the sliders.
 */
data class PointColor(
    val targetHue: Float = 210f,
    val hueRange: Float = 30f,
    val minSaturation: Float = 0.12f,
    val hueShift: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f
) {
    fun normalized(): PointColor = copy(
        targetHue = ((targetHue % 360f) + 360f) % 360f,
        hueRange = hueRange.coerceIn(5f, 90f),
        minSaturation = minSaturation.coerceIn(0f, 0.9f),
        hueShift = hueShift.coerceIn(-TONE_RANGE, TONE_RANGE),
        saturation = saturation.coerceIn(-TONE_RANGE, TONE_RANGE),
        luminance = luminance.coerceIn(-TONE_RANGE, TONE_RANGE)
    )

    fun isNeutral(tolerance: Float = 0.005f): Boolean =
        abs(hueShift) <= tolerance && abs(saturation) <= tolerance && abs(luminance) <= tolerance

    fun toJson(): JSONObject = JSONObject().apply {
        put("targetHue", targetHue.toDouble())
        put("hueRange", hueRange.toDouble())
        put("minSaturation", minSaturation.toDouble())
        put("hueShift", hueShift.toDouble())
        put("saturation", saturation.toDouble())
        put("luminance", luminance.toDouble())
    }

    companion object {
        const val TONE_RANGE = 100f
        val Neutral = PointColor()

        fun fromJson(json: JSONObject?): PointColor {
            if (json == null) return Neutral
            return PointColor(
                targetHue = json.optDouble("targetHue", 210.0).toFloat(),
                hueRange = json.optDouble("hueRange", 30.0).toFloat(),
                minSaturation = json.optDouble("minSaturation", 0.12).toFloat(),
                hueShift = json.optDouble("hueShift", 0.0).toFloat(),
                saturation = json.optDouble("saturation", 0.0).toFloat(),
                luminance = json.optDouble("luminance", 0.0).toFloat()
            ).normalized()
        }
    }
}
