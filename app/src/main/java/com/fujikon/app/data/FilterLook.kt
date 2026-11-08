package com.fujikon.app.data

import kotlin.math.abs

/**
 * Extra layers a creative filter can carry beyond its plain tone / colour
 * profile (see [FilterProfile]). The stock film looks only reshape tones; a
 * studio portrait look also needs a colour mixer, skin-aware retouch and a
 * touch of softening. Keeping those here lets one filter chip apply a whole
 * retouch recipe, while [EditAdjustments] still stores just a filter id and a
 * strength — no new persisted fields, so old installs degrade safely.
 */
data class FilterLook(
    /** Eight-band colour mixer, driven by the sampled skin / foliage tones. */
    val hsl: HslAdjustments = HslAdjustments.Neutral,
    /** Skin-mask retouch (smooth / whiten / blush / contour). */
    val portrait: PortraitRetouch = PortraitRetouch.Off,
    /** Negative clarity gives the subject a soft, creamy falloff. */
    val clarity: Float = 0f,
    /** Slightly negative texture keeps skin clean without waxing over pores. */
    val texture: Float = 0f,
    /** Positive values gently lift the corners for an airy frame. */
    val vignette: Float = 0f
) {
    fun isEmpty(): Boolean =
        hsl.isNeutral() && portrait.isOff() &&
            abs(clarity) < 0.001f && abs(texture) < 0.001f && abs(vignette) < 0.001f
}

private fun HslAdjustments.scaled(k: Float): HslAdjustments =
    HslAdjustments(
        bands = List(HslAdjustments.BAND_COUNT) { index ->
            val band = band(index)
            HslBand(band.hue * k, band.saturation * k, band.luminance * k)
        }
    ).normalized()

private fun PortraitRetouch.scaled(k: Float): PortraitRetouch =
    copy(
        smooth = smooth * k,
        whiten = whiten * k,
        blush = blush * k,
        contour = contour * k
    ).normalized()

/**
 * Resolves the selected [CreativeFilter]'s composite [FilterLook] into concrete
 * adjustments for the renderer.
 *
 * Rules that keep the filter predictable:
 *  * everything is scaled by `filterStrength`, so 0% is a true no-op and the
 *    existing strength slider fades the whole recipe in and out;
 *  * a layer is only injected when the user has left that layer neutral — the
 *    first manual HSL / portrait / clarity edit takes over that layer instead
 *    of being silently doubled or overwritten;
 *  * the tone / colour half of [FilterProfile] is deliberately NOT touched
 *    here: `ImageRenderer.buildFilterPlan` already applies it with the same
 *    strength, so resolving it twice would double the grade.
 */
fun EditAdjustments.withFilterLook(): EditAdjustments {
    val filter = CreativeFilter.fromId(creativeFilter)
    val k = (filterStrength / 100f).coerceIn(0f, 1f)
    if (filter.isNone || k <= 0.001f) return this
    val look = filter.profile.look
    if (look.isEmpty()) return this

    var result = this
    if (result.hsl.isNeutral() && !look.hsl.isNeutral()) {
        result = result.copy(hsl = look.hsl.scaled(k))
    }
    if (result.portrait.isOff() && !look.portrait.isOff()) {
        result = result.copy(portrait = look.portrait.scaled(k))
    }
    if (abs(result.clarity) < 0.001f && look.clarity != 0f) {
        result = result.copy(clarity = look.clarity * k)
    }
    if (abs(result.texture) < 0.001f && look.texture != 0f) {
        result = result.copy(texture = look.texture * k)
    }
    if (abs(result.vignette) < 0.001f && look.vignette != 0f) {
        result = result.copy(vignette = look.vignette * k)
    }
    return result.normalized()
}
