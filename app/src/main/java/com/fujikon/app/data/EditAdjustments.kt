package com.fujikon.app.data

import org.json.JSONObject
import kotlin.math.abs

/**
 * Lightroom-style global adjustments layered on top of a Picture Control.
 *
 * The values are stored in display units so the editor and saved presets use
 * the same numbers. [exposure] is measured in EV; every other slider uses a
 * -100..100 range. A neutral instance leaves the preset rendering untouched.
 */
data class EditAdjustments(
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    /** Lightroom-style eight-band HSL colour mixer. */
    val hsl: HslAdjustments = HslAdjustments.Neutral,
    /** Non-destructive crop framing. */
    val crop: EditCrop = EditCrop.Full,
    /** Film-inspired creative look id. Use [CreativeFilter.NONE.id] for no look. */
    val creativeFilter: String = CreativeFilter.NONE.id,
    /** 0..100 blend for [creativeFilter]. */
    val filterStrength: Float = 100f
) {
    fun normalized(): EditAdjustments = copy(
        temperature = temperature.coerceIn(-TONE_RANGE, TONE_RANGE),
        tint = tint.coerceIn(-TONE_RANGE, TONE_RANGE),
        vibrance = vibrance.coerceIn(-TONE_RANGE, TONE_RANGE),
        exposure = exposure.coerceIn(-EXPOSURE_RANGE, EXPOSURE_RANGE),
        contrast = contrast.coerceIn(-TONE_RANGE, TONE_RANGE),
        highlights = highlights.coerceIn(-TONE_RANGE, TONE_RANGE),
        shadows = shadows.coerceIn(-TONE_RANGE, TONE_RANGE),
        whites = whites.coerceIn(-TONE_RANGE, TONE_RANGE),
        blacks = blacks.coerceIn(-TONE_RANGE, TONE_RANGE),
        texture = texture.coerceIn(-TONE_RANGE, TONE_RANGE),
        clarity = clarity.coerceIn(-TONE_RANGE, TONE_RANGE),
        dehaze = dehaze.coerceIn(-TONE_RANGE, TONE_RANGE),
        hsl = hsl.normalized(),
        crop = crop.normalized(),
        creativeFilter = CreativeFilter.fromId(creativeFilter).id,
        filterStrength = filterStrength.coerceIn(0f, 100f)
    )

    fun isNeutral(tolerance: Float = 0.005f): Boolean =
        abs(temperature) <= tolerance &&
            abs(tint) <= tolerance &&
            abs(vibrance) <= tolerance &&
            abs(exposure) <= tolerance &&
            abs(contrast) <= tolerance &&
            abs(highlights) <= tolerance &&
            abs(shadows) <= tolerance &&
            abs(whites) <= tolerance &&
            abs(blacks) <= tolerance &&
            abs(texture) <= tolerance &&
            abs(clarity) <= tolerance &&
            abs(dehaze) <= tolerance &&
            hsl.isNeutral(tolerance) &&
            crop.isFull(tolerance) &&
            (creativeFilter == CreativeFilter.NONE.id || filterStrength <= tolerance)

    fun toJson(): JSONObject = JSONObject().apply {
        put("temperature", temperature.toDouble())
        put("tint", tint.toDouble())
        put("vibrance", vibrance.toDouble())
        put("exposure", exposure.toDouble())
        put("contrast", contrast.toDouble())
        put("highlights", highlights.toDouble())
        put("shadows", shadows.toDouble())
        put("whites", whites.toDouble())
        put("blacks", blacks.toDouble())
        put("texture", texture.toDouble())
        put("clarity", clarity.toDouble())
        put("dehaze", dehaze.toDouble())
        put("hsl", hsl.toJson())
        put("crop", crop.toJson())
        put("creativeFilter", creativeFilter)
        put("filterStrength", filterStrength.toDouble())
    }

    companion object {
        const val TONE_RANGE = 100f
        const val EXPOSURE_RANGE = 5f

        val Neutral = EditAdjustments()

        fun fromJson(json: JSONObject?): EditAdjustments {
            if (json == null) return Neutral
            return EditAdjustments(
                temperature = json.optDouble("temperature", 0.0).toFloat(),
                tint = json.optDouble("tint", 0.0).toFloat(),
                vibrance = json.optDouble("vibrance", 0.0).toFloat(),
                exposure = json.optDouble("exposure", 0.0).toFloat(),
                contrast = json.optDouble("contrast", 0.0).toFloat(),
                highlights = json.optDouble("highlights", 0.0).toFloat(),
                shadows = json.optDouble("shadows", 0.0).toFloat(),
                whites = json.optDouble("whites", 0.0).toFloat(),
                blacks = json.optDouble("blacks", 0.0).toFloat(),
                texture = json.optDouble("texture", 0.0).toFloat(),
                clarity = json.optDouble("clarity", 0.0).toFloat(),
                dehaze = json.optDouble("dehaze", 0.0).toFloat(),
                hsl = HslAdjustments.fromJson(json.optJSONObject("hsl")),
                crop = EditCrop.fromJson(json.optJSONObject("crop")),
                creativeFilter = json.optString("creativeFilter", CreativeFilter.NONE.id),
                filterStrength = json.optDouble("filterStrength", 100.0).toFloat()
            ).normalized()
        }
    }
}
