package com.fujikon.app.data

import org.json.JSONObject

/**
 * Portrait retouch layer, modelled after the PixCake portrait filters.
 *
 * Only skin is affected: the renderer builds a skin mask from YCbCr so eyes,
 * hair, lips and the background keep their detail. The four strengths are
 * 0..100 sliders; [style] records which preset produced them so the panel can
 * highlight it (editing a slider switches it to [PortraitStyle.CUSTOM]).
 */
data class PortraitRetouch(
    val style: String = PortraitStyle.NONE.id,
    /** Skin smoothing (磨皮). */
    val smooth: Float = 0f,
    /** Skin brightening (美白). */
    val whiten: Float = 0f,
    /** Warm pink blush (红润/气色). */
    val blush: Float = 0f,
    /** Skin micro-contrast for dimension (立体). */
    val contour: Float = 0f
) {
    fun normalized(): PortraitRetouch = copy(
        style = PortraitStyle.fromId(style).id,
        smooth = smooth.coerceIn(0f, MAX_STRENGTH),
        whiten = whiten.coerceIn(0f, MAX_STRENGTH),
        blush = blush.coerceIn(0f, MAX_STRENGTH),
        contour = contour.coerceIn(0f, MAX_STRENGTH)
    )

    fun isOff(tolerance: Float = 0.5f): Boolean =
        smooth <= tolerance && whiten <= tolerance && blush <= tolerance && contour <= tolerance

    /** Applies a style preset to the four strengths. */
    fun withStyle(choice: PortraitStyle): PortraitRetouch = copy(
        style = choice.id,
        smooth = choice.smooth,
        whiten = choice.whiten,
        blush = choice.blush,
        contour = choice.contour
    ).normalized()

    /** Marks the sliders as customised after a manual change. */
    fun asCustom(): PortraitRetouch =
        if (style == PortraitStyle.CUSTOM.id) this else copy(style = PortraitStyle.CUSTOM.id).normalized()

    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style)
        put("smooth", smooth.toDouble())
        put("whiten", whiten.toDouble())
        put("blush", blush.toDouble())
        put("contour", contour.toDouble())
    }

    companion object {
        const val MAX_STRENGTH = 100f

        val Off = PortraitRetouch()

        fun fromJson(json: JSONObject?): PortraitRetouch {
            if (json == null) return Off
            return PortraitRetouch(
                style = json.optString("style", PortraitStyle.NONE.id),
                smooth = json.optDouble("smooth", 0.0).toFloat(),
                whiten = json.optDouble("whiten", 0.0).toFloat(),
                blush = json.optDouble("blush", 0.0).toFloat(),
                contour = json.optDouble("contour", 0.0).toFloat()
            ).normalized()
        }
    }
}

/**
 * Portrait looks shipped with the editor. Values are deliberately moderate:
 * the preview is meant to look retouched, not plastic.
 */
enum class PortraitStyle(
    val id: String,
    val label: String,
    val smooth: Float,
    val whiten: Float,
    val blush: Float,
    val contour: Float
) {
    NONE("none", "关闭", 0f, 0f, 0f, 0f),
    NATURAL("natural", "自然", 25f, 12f, 8f, 6f),
    CLEAR("clear", "清透", 35f, 28f, 6f, 10f),
    CREAM("cream", "奶油肌", 55f, 18f, 14f, 4f),
    AIRY("airy", "日系", 20f, 32f, 16f, 2f),
    TEXTURE("texture", "质感", 14f, 8f, 6f, 18f),
    CUSTOM("custom", "自定义", 0f, 0f, 0f, 0f);

    companion object {
        /** Styles shown as chips in the panel. */
        val presets: List<PortraitStyle> = listOf(NATURAL, CLEAR, CREAM, AIRY, TEXTURE)

        fun fromId(id: String?): PortraitStyle =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
