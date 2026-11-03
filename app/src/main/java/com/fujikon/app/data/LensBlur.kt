package com.fujikon.app.data

import org.json.JSONObject

/**
 * Lightroom-style "lens blur" (镜头模糊).
 *
 * Lightroom derives a depth map in the cloud and keeps the detected subject
 * sharp. FujiKon stays fully on-device, so the same look is approximated with a
 * focus region: pixels inside [focusSize] around ([focusX], [focusY]) stay
 * sharp, everything outside fades into the blur with a soft transition.
 */
data class LensBlur(
    /** Blur strength, 0..100. Zero keeps the pipeline untouched. */
    val amount: Float = 0f,
    /** Focus point, normalized to the un-cropped rendered frame. */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    /** Radius of the sharp region as a percentage of the short edge, 5..80. */
    val focusSize: Float = 32f,
    /** Transition width outside the sharp region, 0..100. */
    val feather: Float = 50f,
    /** Brightens out-of-focus highlights so they read as bokeh, 0..100. */
    val highlights: Float = 0f
) {
    fun normalized(): LensBlur = copy(
        amount = amount.coerceIn(0f, MAX_AMOUNT),
        focusX = focusX.coerceIn(0f, 1f),
        focusY = focusY.coerceIn(0f, 1f),
        focusSize = focusSize.coerceIn(MIN_FOCUS_SIZE, MAX_FOCUS_SIZE),
        feather = feather.coerceIn(0f, MAX_AMOUNT),
        highlights = highlights.coerceIn(0f, MAX_AMOUNT)
    )

    fun isOff(tolerance: Float = 0.5f): Boolean = amount <= tolerance

    fun toJson(): JSONObject = JSONObject().apply {
        put("amount", amount.toDouble())
        put("focusX", focusX.toDouble())
        put("focusY", focusY.toDouble())
        put("focusSize", focusSize.toDouble())
        put("feather", feather.toDouble())
        put("highlights", highlights.toDouble())
    }

    companion object {
        const val MIN_FOCUS_SIZE = 5f
        const val MAX_FOCUS_SIZE = 80f
        const val MAX_AMOUNT = 100f

        val Off = LensBlur()

        fun fromJson(json: JSONObject?): LensBlur {
            if (json == null) return Off
            return LensBlur(
                amount = json.optDouble("amount", 0.0).toFloat(),
                focusX = json.optDouble("focusX", 0.5).toFloat(),
                focusY = json.optDouble("focusY", 0.5).toFloat(),
                focusSize = json.optDouble("focusSize", 32.0).toFloat(),
                feather = json.optDouble("feather", 50.0).toFloat(),
                highlights = json.optDouble("highlights", 0.0).toFloat()
            ).normalized()
        }
    }
}
