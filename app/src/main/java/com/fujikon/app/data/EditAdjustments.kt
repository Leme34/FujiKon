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
    /**
     * Master RGB tone curve — the PRIMARY tool used by 一键优化. Applied in
     * display space after the linear exposure / contrast / zone controls, so it
     * shapes light sense, colour hierarchy and lifts crushed shadows / blown
     * highlights without the destructive global exposure shift that plain
     * exposure + contrast sliders cause. `null` or a near-linear curve counts
     * as "no change".
     */
    val toneCurve: ToneCurve? = null,
    /**
     * Split-tone: a very small cool-blue tint applied **only to shadows**.
     * 0..100, driven by how warm / muddy the measured dark tones are. Positive
     * values never touch mid-tones or highlights, and the renderer caps the
     * channel shift at ~5% so it can never become a visible colour cast.
     */
    val shadowTint: Float = 0f,
    /** Lightroom-style eight-band HSL colour mixer. */
    val hsl: HslAdjustments = HslAdjustments.Neutral,
    /** Non-destructive crop framing. */
    val crop: EditCrop = EditCrop.Full,
    /** Lightroom-style lens blur around a focus region. */
    val lensBlur: LensBlur = LensBlur.Off,
    /** Skin-aware portrait retouch. */
    val portrait: PortraitRetouch = PortraitRetouch.Off,
    /** Film-inspired creative look id. Use [CreativeFilter.NONE.id] for no look. */
    val creativeFilter: String = CreativeFilter.NONE.id,
    /** 0..100 blend for [creativeFilter]. */
    val filterStrength: Float = 100f,
    /** Independent edge-preserving denoise, 0..100. Runs before sharpening. */
    val denoise: Float = 0f,
    /** Deterministic film grain, 0..100. Preview and export produce identical grain. */
    val grain: Float = 0f,
    /** Vignette, -100 (darken corners) .. 100 (brighten corners). */
    val vignette: Float = 0f,
    /** Point Color / zone-scoped adjustments. */
    val pointColor: PointColor = PointColor.Neutral,
    /** Zone-scoped adjustments (control point / linear / radial / brush). */
    val local: LocalAdjustments = LocalAdjustments.Neutral,
    /**
     * 当前所选预设的效果强度，0..100（默认 100 = 完整套用）。
     *
     * 渲染器在全部像素处理完成后，把「完整效果」与「原图」按该比例做逐像素
     * 线性混合：0 = 原图、100 = 现有行为、中间值平滑过渡。因为只是对同一
     * 像素位置的 RGB 做插值，色相与饱和度不会被拉伸，天然不引入色偏。
     * 它属于当前照片的编辑状态，因此会随历史、复制效果与导出一起生效。
     */
    val presetStrength: Float = 100f
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
        toneCurve = toneCurve,
        shadowTint = shadowTint.coerceIn(0f, TONE_RANGE),
        hsl = hsl.normalized(),
        crop = crop.normalized(),
        lensBlur = lensBlur.normalized(),
        portrait = portrait.normalized(),
        creativeFilter = CreativeFilter.fromId(creativeFilter).id,
        filterStrength = filterStrength.coerceIn(0f, 100f),
        denoise = denoise.coerceIn(0f, TONE_RANGE),
        grain = grain.coerceIn(0f, TONE_RANGE),
        vignette = vignette.coerceIn(-TONE_RANGE, TONE_RANGE),
        pointColor = pointColor.normalized(),
        local = local.normalized(),
        presetStrength = presetStrength.coerceIn(0f, 100f)
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
            (toneCurve == null || toneCurve.isLinear(tolerance)) &&
            abs(shadowTint) <= tolerance &&
            hsl.isNeutral(tolerance) &&
            crop.isFull(tolerance) &&
            lensBlur.isOff(tolerance) &&
            portrait.isOff(tolerance) &&
            (creativeFilter == CreativeFilter.NONE.id || filterStrength <= tolerance) &&
            denoise <= tolerance &&
            grain <= tolerance &&
            abs(vignette) <= tolerance &&
            pointColor.isNeutral(tolerance) &&
            local.isNeutral() &&
            presetStrength >= 100f - tolerance

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
        put("toneCurve", toneCurve?.toJson() ?: JSONObject.NULL)
        put("shadowTint", shadowTint.toDouble())
        put("hsl", hsl.toJson())
        put("crop", crop.toJson())
        put("lensBlur", lensBlur.toJson())
        put("portrait", portrait.toJson())
        put("creativeFilter", creativeFilter)
        put("filterStrength", filterStrength.toDouble())
        put("denoise", denoise.toDouble())
        put("grain", grain.toDouble())
        put("vignette", vignette.toDouble())
        put("pointColor", pointColor.toJson())
        put("local", local.toJson())
        put("presetStrength", presetStrength.toDouble())
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
                toneCurve = json.optJSONObject("toneCurve")?.let(ToneCurve::fromJson),
                shadowTint = json.optDouble("shadowTint", 0.0).toFloat(),
                hsl = HslAdjustments.fromJson(json.optJSONObject("hsl")),
                crop = EditCrop.fromJson(json.optJSONObject("crop")),
                lensBlur = LensBlur.fromJson(json.optJSONObject("lensBlur")),
                portrait = PortraitRetouch.fromJson(json.optJSONObject("portrait")),
                creativeFilter = json.optString("creativeFilter", CreativeFilter.NONE.id),
                filterStrength = json.optDouble("filterStrength", 100.0).toFloat(),
                denoise = json.optDouble("denoise", 0.0).toFloat(),
                grain = json.optDouble("grain", 0.0).toFloat(),
                vignette = json.optDouble("vignette", 0.0).toFloat(),
                pointColor = PointColor.fromJson(json.optJSONObject("pointColor")),
                local = LocalAdjustments.fromJson(json.optJSONObject("local")),
                // Older app versions ignore this key; default keeps the full look.
                presetStrength = json.optDouble("presetStrength", 100.0).toFloat()
            ).normalized()
        }
    }
}
