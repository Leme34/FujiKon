package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.HslAdjustments
import com.fujikon.app.data.HslBand
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurvePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Objective statistics of the current preview, sampled on a downscaled copy.
 *
 * Everything here is measured from real pixels, not guessed from a single
 * mean value. [AutoEnhance] uses these numbers to solve for a set of
 * Lightroom-style adjustments, and the same numbers drive the histogram card
 * and the local prompt-generation fallback.
 */
class ImageStats internal constructor(
    /** 64-bin normalised channel histograms for the RGB chart. */
    val bins: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
    /** 0..1 mean perceptual luma and its standard deviation (contrast).   */
    val meanLuma: Float,
    val stdLuma: Float,
    /** Luma percentiles, 0..1. */
    val p1: Float,
    val p5: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    /** Fractions of pixels crushed to near-black / clipped to near-white.  */
    val shadowClip: Float,
    val highlightClip: Float,
    /** Mean HSV saturation, 0..1. */
    val meanSaturation: Float,
    /** Mean channel levels, 0..1. */
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
    /** (R-B)/G: positive means a warm/yellow cast, negative a cool/blue cast. */
    val warmth: Float,
    /** (G - (R+B)/2)/G: positive means a green cast, negative a magenta cast. */
    val greenCast: Float,
    /** Mean absolute luminance gradient, a proxy for local detail/sharpness. */
    val detail: Float,
    /** Raw 256-bin luma histogram, used by the tone solver. */
    val lumaHistogram: IntArray,
    /** Mean RGB of near-neutral (low-chroma, mid-luma) pixels — the white-balance anchor. */
    val neutralR: Float = 0f,
    val neutralG: Float = 0f,
    val neutralB: Float = 0f,
    /** Fraction of pixels that are near-neutral; ~0 means no usable white reference. */
    val neutralCoverage: Float = 0f,
    // ---- "Follow the photo" colour evidence, used to TRIGGER (not force) the
    // outdoor-snapshot palette. All are measured fractions / means of real pixels.
    /** Fraction of green / teal-green pixels (foliage, grass, park background). */
    val greenCoverage: Float = 0f,
    /** Fraction of warm yellow / orange pixels (sunlit subject, dress, skin). */
    val warmCoverage: Float = 0f,
    /** Fraction of skin-tone pixels — high values suppress the warm white-balance bias. */
    val skinCoverage: Float = 0f,
    /** Mean (R-B) of dark pixels: positive = warm/muddy shadows, negative = already cool. */
    val shadowWarmth: Float = 0f,
    /** Fraction of dark pixels (luma < 0.40) — gates the shadow blue split-tone. */
    val shadowFraction: Float = 0f,
    /** Fraction of bright pixels (luma > 0.72) — drives how firmly highlights are pulled down. */
    val brightFraction: Float = 0f
) {
    val spread: Float get() = p95 - p5
    val dynamicRange: Float get() = p99 - p1

    /** 0..1 overall "already well exposed" score, used to protect good shots. */
    val toneQuality: Float
        get() {
            val exposureScore = 1f - (abs(meanLuma - 0.47f) / 0.22f).coerceIn(0f, 1f)
            val rangeScore = (spread / 0.72f).coerceIn(0f, 1f)
            val clipScore = 1f - ((shadowClip + highlightClip) / 0.08f).coerceIn(0f, 1f)
            return exposureScore * 0.45f + rangeScore * 0.40f + clipScore * 0.15f
        }
}

/** Outcome of one auto-optimisation pass. */
data class AutoEnhanceResult(
    /** The complete adjustment set to apply (current + solved delta). */
    val adjustments: EditAdjustments,
    /** Human-readable problems detected in the preview, in Chinese. */
    val findings: List<String>,
    /** One-line summary of what the optimisation changed. */
    val summary: String,
    /** False when the photo is already good and nothing meaningful was done. */
    val changed: Boolean
)

/**
 * Lightroom-class automatic photo optimisation, tuned to the "daily outdoor
 * snapshot" look (park / street / everyday life): airy light, teal-green
 * backgrounds with soft warm subjects, retained detail and a whisper of
 * summer soft-focus — while never ruining a frame that was already good.
 *
 * Two hard rules, learned from the regressions:
 *  1. **The tone curve is primary.** A single master [ToneCurve] built from the
 *     measured histogram fixes 死黑/死白, 光感 and 色彩层次. Linear exposure /
 *     contrast are only a tiny, capped secondary nudge. No frame is ever
 *     hammered by big exposure+contrast swings.
 *  2. **Everything follows the photo.** Every amount is a *continuous function*
 *     of a measured deficiency, not a fixed preset. Style colour (teal-green
 *     background, soft warm subject, cool blue shadows) is TRIGGERED only when
 *     the pixels actually contain that colour — an indoor portrait, a snow
 *     scene or a night shot is never tinted. Good frames are short-circuited to
 *     exactly [EditAdjustments.Neutral].
 *
 * The result is a plain [EditAdjustments]: non-destructive, undoable, editable,
 * idempotent, and shared by preview and export like any other adjustment.
 */
object AutoEnhance {

    private const val SAMPLE_SIDE = 384
    private const val HIST_BINS = 64

    /** Downscales, then measures histogram, tone, colour and detail statistics. */
    fun analyse(source: Bitmap?): ImageStats? {
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val longest = max(source.width, source.height)
        val sample = if (longest > SAMPLE_SIDE) {
            val scale = SAMPLE_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }
        val w = sample.width
        val h = sample.height
        val pixels = IntArray(w * h)
        sample.getPixels(pixels, 0, w, 0, 0, w, h)
        if (sample !== source) sample.recycle()

        val r = IntArray(HIST_BINS)
        val g = IntArray(HIST_BINS)
        val b = IntArray(HIST_BINS)
        val lumaHist = IntArray(256)
        val lumaPixels = FloatArray(w * h)
        var sumL = 0f
        var sumL2 = 0f
        var sumSat = 0f
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var shadow = 0
        var highlight = 0
        var nR = 0f
        var nG = 0f
        var nB = 0f
        var nCount = 0
        var greenCount = 0
        var warmCount = 0
        var skinCount = 0
        var darkCount = 0
        var darkWarmSum = 0f
        var brightCount = 0

        for (i in pixels.indices) {
            val px = pixels[i]
            val cr = (px shr 16) and 0xFF
            val cg = (px shr 8) and 0xFF
            val cb = px and 0xFF
            r[cr * HIST_BINS / 256]++
            g[cg * HIST_BINS / 256]++
            b[cb * HIST_BINS / 256]++
            val rf = cr / 255f
            val gf = cg / 255f
            val bf = cb / 255f
            val l = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
            lumaPixels[i] = l
            lumaHist[(l * 255f).toInt().coerceIn(0, 255)]++
            sumL += l
            sumL2 += l * l
            if (l < 0.02f) shadow++
            if (l > 0.98f) highlight++
            val mx = max(rf, max(gf, bf))
            val mn = min(rf, min(gf, bf))
            if (mx > 0.001f) sumSat += (mx - mn) / mx
            sumR += rf
            sumG += gf
            sumB += bf
            // Near-neutral pixels (low chroma, mid luma) are the white-balance
            // anchor: they should be colourless, so their average reveals the
            // scene's actual illumination. Colourful / very dark / very bright
            // pixels are excluded — they are memory colours, not a grey card.
            val chroma = mx - mn
            if (chroma < 0.18f && l > 0.15f && l < 0.90f) {
                nR += rf
                nG += gf
                nB += bf
                nCount++
            }
            // ---- Colour evidence for the "follow the photo" palette --------
            // These are cheap chroma/order tests on the RGB triple, not a full
            // HSL conversion, so the analysis stays O(1) per sampled pixel.
            if (l > 0.72f) brightCount++
            if (l < 0.40f) {
                darkCount++
                darkWarmSum += rf - bf
            }
            if (chroma > 0.05f) {
                // Green family: green dominates, includes deep green and teal.
                if (gf > rf * 1.04f && gf >= bf * 0.98f) greenCount++
                // Warm yellow / orange: red dominates, blue is clearly lower.
                if (rf > bf * 1.10f && rf >= gf * 0.96f) warmCount++
                // Skin: red > green > blue, moderate chroma, mid luma.
                if (rf > gf && gf > bf && rf - bf < 0.45f && l > 0.22f && l < 0.88f) skinCount++
            }
        }

        val total = pixels.size.toFloat().coerceAtLeast(1f)
        var maxBin = 1
        for (i in 0 until HIST_BINS) maxBin = max(maxBin, max(r[i], max(g[i], b[i])))
        fun normalise(values: IntArray): FloatArray = FloatArray(HIST_BINS) { i ->
            sqrt(values[i].toFloat() / maxBin)
        }

        val meanL = sumL / total
        val stdL = sqrt(max(0f, sumL2 / total - meanL * meanL))
        val meanR = sumR / total
        val meanG = sumG / total
        val meanB = sumB / total

        val neutralCoverage = nCount / total
        val neutralR = if (nCount > 0) nR / nCount else meanR
        val neutralG = if (nCount > 0) nG / nCount else meanG
        val neutralB = if (nCount > 0) nB / nCount else meanB

        val greenCoverage = greenCount / total
        val warmCoverage = warmCount / total
        val skinCoverage = skinCount / total
        val shadowFraction = darkCount / total
        val shadowWarmth = if (darkCount > 0) darkWarmSum / darkCount else 0f
        val brightFraction = brightCount / total

        // Mean absolute neighbour gradient: cheap local-detail estimator.
        var gradSum = 0f
        var gradCount = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                if (x + 1 < w) {
                    gradSum += abs(lumaPixels[idx] - lumaPixels[idx + 1])
                    gradCount++
                }
                if (y + 1 < h) {
                    gradSum += abs(lumaPixels[idx] - lumaPixels[idx + w])
                    gradCount++
                }
            }
        }
        val detail = if (gradCount > 0) gradSum / gradCount else 0f

        fun percentile(fraction: Float): Float {
            val target = (total * fraction).toInt()
            var acc = 0
            for (i in 0 until 256) {
                acc += lumaHist[i]
                if (acc >= target) return i / 255f
            }
            return 1f
        }

        return ImageStats(
            bins = HIST_BINS,
            red = normalise(r),
            green = normalise(g),
            blue = normalise(b),
            meanLuma = meanL,
            stdLuma = stdL,
            p1 = percentile(0.01f),
            p5 = percentile(0.05f),
            p50 = percentile(0.50f),
            p95 = percentile(0.95f),
            p99 = percentile(0.99f),
            shadowClip = shadow / total,
            highlightClip = highlight / total,
            meanSaturation = sumSat / total,
            meanR = meanR,
            meanG = meanG,
            meanB = meanB,
            warmth = if (meanG > 0.01f) (meanR - meanB) / meanG else 0f,
            greenCast = if (meanG > 0.01f) (meanG - (meanR + meanB) * 0.5f) / meanG else 0f,
            detail = detail,
            lumaHistogram = lumaHist,
            neutralR = neutralR,
            neutralG = neutralG,
            neutralB = neutralB,
            neutralCoverage = neutralCoverage,
            greenCoverage = greenCoverage,
            warmCoverage = warmCoverage,
            skinCoverage = skinCoverage,
            shadowWarmth = shadowWarmth,
            shadowFraction = shadowFraction,
            brightFraction = brightFraction
        )
    }

    /**
     * Solves the best adjustment delta for [stats] and adds it to [current].
     */
    fun suggest(stats: ImageStats, current: EditAdjustments = EditAdjustments.Neutral): AutoEnhanceResult {
        val findings = describe(stats)
        // Truly excellent frames are left untouched (same gate as the 1.6.4
        // engine the user preferred). Borderline ones still get a gentle pass.
        val nearlyPerfect = stats.toneQuality > 0.80f &&
            abs(stats.warmth) < 0.065f &&
            abs(stats.greenCast) < 0.05f &&
            stats.shadowClip < 0.03f &&
            stats.highlightClip < 0.03f

        // Good photos must be left alone: Lightroom Auto also refuses to
        // "improve" a frame that is already well exposed and neutral.
        if (nearlyPerfect) {
            return AutoEnhanceResult(
                adjustments = current,
                findings = findings,
                summary = "画面已经很均衡，未做改动",
                changed = false
            )
        }

        // "Follow the photo": how much tonal repair is still needed at all.
        // Already-good frames get ~0, so no curve / contrast / zone work can
        // ever ruin a shot that was fine to begin with.
        val deficit = 1f - ramp(stats.toneQuality, 0.72f, 0.92f)

        // High-key frames (snow / white backdrop): stretching their mid-tones
        // with an S-curve only pushes already-bright whites higher. Damp the S
        // component there while keeping the highlight roll-off (recovery).
        val highKeyGuard = 1f - ramp(stats.meanLuma, 0.70f, 0.86f) * 0.85f

        // ===== ① PRIMARY: tone curve — 光感 / 色彩层次 / 死黑死白 =========
        // Kept from the previous (user-approved) version, but its amplitude is
        // now scaled by [deficit] so a well-toned frame gets a near-identity
        // curve instead of an unnecessary re-grade. [sGain] additionally fades
        // the S-stretch on high-key frames without touching toe / roll.
        val curve = solveToneCurve(stats, 0.35f + 0.65f * deficit, highKeyGuard)

        // ===== ④ SECONDARY exposure nudge — 二次调色让画面更均匀 ==========
        var exposure = solveExposure(stats, curve)
        val predicted = predictStats(stats.lumaHistogram, adjustmentLut(exposure, curve))
        if (predicted.highClip > 0.03f) exposure *= 0.6f
        if (predicted.lowClip > 0.05f) exposure *= 0.6f

        // Contrast is a CONTINUOUS function of how flat the frame still is,
        // gated by [deficit]. Bad flat frames get the full stretch; good ones 0.
        // High-key frames (snow / white backdrop) are further damped: pulling
        // contrast there just pushes whites toward clipping. Highlight pull-down
        // below is kept, so this only limits the *stretch*, not the recovery.
        val flat = ramp(0.42f - predicted.spread, 0f, 0.22f)
        val contrast = (flat * 55f * deficit * highKeyGuard).coerceIn(0f, 55f)

        // ===== ① 通透感: 压高光 + 提阴影 (跟着原片，偏曝光的重手) ========
        // Both are continuous in the measured bright / dark mass. Hard caps
        // keep them far below anything that could flatten or blow the frame,
        // and [deficit] fades them out on already-good frames.
        val toneTouch = 0.35f + 0.65f * deficit
        val hiDeficit = ramp(stats.brightFraction, 0.06f, 0.34f) * 0.6f +
            ramp(stats.highlightClip, 0.004f, 0.06f) * 0.4f
        val highlights = -(hiDeficit * 30f * toneTouch).coerceIn(0f, 30f)
        val shDeficit = ramp(stats.shadowFraction, 0.10f, 0.50f) * 0.6f +
            ramp(stats.shadowClip, 0.01f, 0.09f) * 0.4f
        // The curve toe already lifts crushed blacks; the slider only adds a
        // gentle, complementary lift so the two never stack into a wash-out.
        val shadows = (shDeficit * 22f * toneTouch).coerceIn(0f, 22f)

        // ===== ① / ③ 质感: 纹理+ 保细节, 清晰度− 轻微柔焦 ================
        val soft = ramp(0.024f - stats.detail, 0f, 0.018f)
        val texture = (6f + soft * 12f).coerceIn(0f, 18f)     // 轻微加锐，保发丝/褶皱
        val clarity = -(4f + soft * 8f).coerceIn(4f, 12f)     // 轻微柔焦，不糊细节
        // 柔光收尾全部交给 texture+ / clarity−（保留细节 + 轻微柔焦）。
        // dehaze 保持 0：负去朦胧会加雾发灰，正是配方要去掉的「闷沉沉」。
        val dehaze = 0f

        // ===== ① 色温: 冷偏 → 校到「略暖」, 暖调氛围一律保留 =============
        // The recipe asks for "a slight warm lean". We implement that as the
        // SAME thing as gentle cold-cast correction: nudge a cold frame toward
        // warmth≈+0.02. Warm moods, snow, night and blue sky are preserved.
        val temperature = solveTemperature(stats)
        val tint = solveTint(stats)
        val vibrance = solveVibrance(stats)

        // ===== ② 色彩定调: 按原片触发，绝不染色无关照片 ==================
        val hsl = solveHsl(stats)
        // ===== ② 阴影蓝调: 仅「户外绿背景 + 暗部实测偏暖/发闷」 ==========
        // Gated by green coverage: a warm indoor portrait's dark tones are
        // atmosphere, not muddiness, and must never be cooled.
        val outdoor = ramp(stats.greenCoverage, 0.02f, 0.14f)
        val coolNeed = ramp(stats.shadowWarmth, 0.004f, 0.09f) *
            ramp(stats.shadowFraction, 0.12f, 0.45f) * outdoor
        val shadowTint = (coolNeed * 40f).coerceIn(0f, 40f)

        // ABSOLUTE parameters. One tap always yields the same result (idempotent)
        // and never accumulates — the old "current + delta" could run away on a
        // second tap. Structural edits (crop / blur / portrait / filter) are kept.
        val next = EditAdjustments(
            temperature = temperature,
            tint = tint,
            vibrance = vibrance,
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            texture = texture,
            clarity = clarity,
            dehaze = dehaze,
            toneCurve = if (curve.isLinear()) null else curve,
            shadowTint = shadowTint,
            hsl = hsl,
            crop = current.crop,
            lensBlur = current.lensBlur,
            portrait = current.portrait,
            creativeFilter = current.creativeFilter,
            filterStrength = current.filterStrength
        ).normalized()

        val changed = next != current
        return AutoEnhanceResult(
            adjustments = next,
            findings = findings,
            summary = buildSummary(stats, curve, next, changed, nearlyPerfect),
            changed = changed
        )
    }

    /** Linear 0..1 ramp — the backbone of "follow the photo, no fixed values". */
    private fun ramp(value: Float, lo: Float, hi: Float): Float =
        ((value - lo) / (hi - lo)).coerceIn(0f, 1f)

    /**
     * ② Colours are TRIGGERED by measured evidence:
     *  - 深绿/灰绿背景 → 清透青蓝绿 (green band hue toward aqua, sat down a touch)
     *  - 暖黄主体 → 柔和不刺眼 (yellow/orange sat down a touch)
     * If a frame has neither (indoor portrait, snow, night), this returns Neutral
     * and no colour styling whatsoever is applied.
     */
    private fun solveHsl(stats: ImageStats): HslAdjustments {
        var out = HslAdjustments.Neutral
        val green = ramp(stats.greenCoverage, 0.08f, 0.34f)
        if (green > 0f) {
            out = out.withBand(3, HslBand(hue = green * 16f, saturation = -green * 10f, luminance = green * 4f))
                .withBand(4, HslBand(hue = green * 6f, saturation = -green * 5f))
        }
        val warm = ramp(stats.warmCoverage, 0.05f, 0.28f)
        if (warm > 0f) {
            out = out.withBand(2, HslBand(saturation = -warm * 8f, luminance = warm * 3f))
                .withBand(1, HslBand(saturation = -warm * 5f))
        }
        return out
    }

    /** Chinese, user-facing list of problems actually detected in the pixels. */
    fun describe(stats: ImageStats): List<String> = buildList {
        when {
            stats.meanLuma < 0.34f -> add("整体偏暗")
            stats.meanLuma > 0.68f -> add("整体偏亮")
        }
        if (stats.highlightClip > 0.025f) add("高光溢出")
        if (stats.shadowClip > 0.04f) add("暗部死黑")
        if (stats.spread < 0.48f || stats.stdLuma < 0.15f) add("反差偏平")
        if (stats.warmth < -0.10f) add("偏蓝冷色")
        else if (stats.warmth > 0.12f) add("偏黄暖色")
        if (stats.greenCast > 0.07f) add("偏绿")
        else if (stats.greenCast < -0.07f) add("偏洋红")
        if (stats.meanSaturation < 0.16f) add("色彩偏淡")
        else if (stats.meanSaturation > 0.52f) add("色彩过艳")
        if (stats.detail < 0.012f) add("细节偏软")
    }

    /**
     * Local, offline prompt used when the Agnes text model is unavailable.
     * It is derived from the same measured problems, so it is always specific
     * to the photo instead of a generic sentence.
     */
    fun describeForPrompt(stats: ImageStats): String {
        val parts = buildList {
            when {
                stats.meanLuma < 0.36f -> add("提升整体曝光与暗部细节，恢复阴影层次")
                stats.meanLuma > 0.66f -> add("压低高光、找回亮部细节")
            }
            if (stats.highlightClip > 0.02f) add("收敛高光溢出，保留云层与灯光细节")
            if (stats.shadowClip > 0.04f) add("提亮死黑暗部但保持自然黑位")
            if (stats.spread < 0.50f || stats.stdLuma < 0.16f) add("拉开明暗反差，增强画面层次与立体感")
            when {
                stats.warmth < -0.10f -> add("校正偏蓝冷色偏，还原自然白平衡")
                stats.warmth > 0.12f -> add("校正偏黄暖色偏，还原自然白平衡")
            }
            if (stats.greenCast > 0.07f) add("校正偏绿色调")
            else if (stats.greenCast < -0.07f) add("校正偏洋红色调")
            when {
                stats.meanSaturation < 0.17f -> add("适度提升自然饱和度，让色彩通透但不过艳")
                stats.meanSaturation > 0.50f -> add("收敛过饱和，保持色彩自然")
            }
            if (stats.spread < 0.52f && stats.meanLuma > 0.50f && stats.meanSaturation < 0.20f) {
                add("去除灰雾感，提升通透度")
            }
            if (stats.detail < 0.012f) add("轻微增强细节与清晰度，避免锐化过度")
        }.take(5)
        val core = if (parts.isEmpty()) {
            "保持当前曝光与色彩，轻微增强质感、层次与清晰度"
        } else {
            parts.joinToString("；")
        }
        return "$core。严格保留原构图、主体与画面内容，不做元素增删，输出自然真实的摄影级优化效果。".take(500)
    }

    // ------------------------------------------------------------- solver

    /**
     * Builds the PRIMARY tone curve from the measured histogram. Each of the
     * three components is scaled by the *actual* deficiency, so a well-exposed
     * frame (deep-enough blacks, safe-enough whites, decent spread) gets a
     * near-identity curve and is effectively untouched:
     *
     *  - [toe]: lifts the shadow "toe" to fix 死黑. Zero at x=0.5 and capped, so
     *    pure black stays black and the lift never washes the frame out.
     *  - [roll]: rolls off the highlights to fix 死白. Zero at x=0.5 and capped,
     *    so pure white is preserved while blown detail is recovered.
     *  - [s]: a small S-shape that separates mid-tones for 色彩层次 / 光感.
     */
    private fun solveToneCurve(stats: ImageStats, gain: Float, sGain: Float): ToneCurve {
        val shadowDeficit = max(0f, 0.10f - stats.p5)
        val highExcess = max(0f, stats.p95 - 0.92f)
        val toe = ((2.2f * shadowDeficit + 1.6f * stats.shadowClip).coerceIn(0f, 0.07f)) * gain
        val roll = ((2.0f * highExcess + 1.8f * stats.highlightClip).coerceIn(0f, 0.09f)) * gain
        val s = (if (stats.spread < 0.55f) (0.18f * (0.55f - stats.spread)).coerceIn(0f, 0.12f) else 0f) * gain * sGain

        if (toe < 0.004f && roll < 0.004f && s < 0.004f) return ToneCurve()

        val points = ArrayList<ToneCurvePoint>(25)
        var prevY = 0f
        val n = 24
        for (i in 0..n) {
            val x = i / n.toFloat()
            var y = x
            if (toe > 0f && x < 0.5f) {
                val t = 1f - x / 0.5f // 1 at black, 0 at mid
                y += toe * t * t
            }
            if (roll > 0f && x > 0.5f) {
                val t = (x - 0.5f) / 0.5f // 0 at mid, 1 at white
                y -= roll * t * t
            }
            if (s > 0f) {
                // True S-curve: dark quarter-tones dip slightly, bright
                // three-quarter-tones rise slightly => separates mid-tones
                // and builds 色彩层次 / 立体感 (the old +sin sign flattened them).
                y -= (s * sin(2.0 * PI * x.toDouble())).toFloat()
            }
            y = y.coerceIn(0f, 1f)
            if (y < prevY) y = prevY // keep the curve monotonically increasing
            points.add(ToneCurvePoint(x, y))
            prevY = y
        }
        return ToneCurve(points)
    }

    /**
     * Mirrors [ImageRenderer.buildAdjustmentToneLut] (exposure in linear light,
     * then the master curve in display space) so the engine can predict the
     * result of its own adjustments without rendering a full bitmap.
     */
    private fun adjustmentLut(exposure: Float, curve: ToneCurve?): FloatArray {
        val exp = Math.pow(2.0, exposure.toDouble()).toFloat()
        val useExp = abs(exposure) > 0.001f
        return FloatArray(256) { i ->
            var x = i / 255f
            if (useExp) x = (x.pow(2.2f) * exp).pow(1f / 2.2f)
            if (curve != null) x = curve.apply(x)
            (x.coerceIn(0f, 1f) * 255f)
        }
    }

    private data class Predicted(
        val mean: Float,
        val std: Float,
        val p5: Float,
        val p95: Float,
        val spread: Float,
        val lowClip: Float,
        val highClip: Float
    )

    private fun predictStats(hist: IntArray, lut: FloatArray): Predicted {
        val mapped = IntArray(256)
        for (i in 0 until 256) {
            mapped[lut[i].roundToInt().coerceIn(0, 255)] += hist[i]
        }
        val total = hist.sum().toFloat().coerceAtLeast(1f)
        var mean = 0f
        var sum2 = 0f
        var acc = 0
        var p5 = 0f
        var p95 = 1f
        val p5Target = (total * 0.05f).toInt()
        val p95Target = (total * 0.95f).toInt()
        for (i in 0 until 256) {
            val c = mapped[i]
            val v = i / 255f
            mean += v * c
            sum2 += v * v * c
            acc += c
            if (p5 == 0f && acc >= p5Target) p5 = v
            if (p95 == 1f && acc >= p95Target) p95 = v
        }
        mean /= total
        val std = sqrt(max(0f, sum2 / total - mean * mean))
        val lowClip = (mapped[0] + mapped[1] + mapped[2]) / total
        val highClip = (mapped[253] + mapped[254] + mapped[255]) / total
        return Predicted(mean, std, p5, p95, p95 - p5, lowClip, highClip)
    }

    /**
     * SECONDARY exposure nudge. A genuinely under-exposed frame (well below
     * mid-grey) is lifted toward photographic mid-grey by targeting it directly
     * in linear light; a frame that is already near mid-grey (and therefore any
     * "already good" photo, which is also caught by the [suggest] gate) gets 0 EV.
     * Hard-capped at 1.4 EV so it can never repeat the "over-driven exposure
     * destroys the photo" regression — that regression came from hammering
     * already-fine frames, which this never does.
     */
    private fun solveExposure(stats: ImageStats, curve: ToneCurve): Float {
        val pred = predictStats(stats.lumaHistogram, adjustmentLut(0f, curve))
        val m = pred.mean
        if (m > 0.42f) return 0f
        val target = 0.47f
        val e = 2.2f * (ln(target / max(m, 0.05f)) / ln(2f)).toFloat()
        return e.coerceIn(-1.4f, 1.4f)
    }

    /**
     * "轻微往暖靠" implemented as gentle cold-cast correction toward a slightly
     * warm target (warmth ≈ +0.02). Deliberately does NOT neutralise warm moods.
     *
     * Multiple gates make sure we never tint atmosphere:
     *  - warm frame (warmth > 0.02) → 0 (warm sun / indoor tungsten preserved)
     *  - neutral anchor not blue-dominant → 0 (the cold is a blue SKY, a memory
     *    colour, not a cast — this is what stopped the sky turning orange)
     *  - high-key / very low-key → faded out (snow's cold white, night's cool
     *    blue are moods, not casts)
     *  - lots of skin → faded out (never let skin turn yellow)
     * Capped at +20, so it stays a gentle lean rather than a ±60 hammer.
     */
    private fun solveTemperature(stats: ImageStats): Float {
        // Hard gates: warm mood, high-key (snow / overexposed white) and very
        // low-key (night) are atmosphere, never colour casts to "fix".
        if (stats.warmth > 0.02f) return 0f
        if (stats.meanLuma > 0.62f || stats.meanLuma < 0.22f) return 0f
        if (stats.neutralCoverage < 0.006f) return 0f
        val r = stats.neutralR.coerceAtLeast(0.01f)
        val g = stats.neutralG.coerceAtLeast(0.01f)
        val b = stats.neutralB.coerceAtLeast(0.01f)
        val t = (g / r - g / b) / 0.24f
        if (t <= 0.02f) return 0f // anchor isn't blue → cold is memory colour
        // Skin guard keeps at least half of the correction: with a hard +34 cap
        // and the hard gates above, a slight warm lean can never turn skin
        // yellow — but a warm foreground must not fully cancel a real cast fix.
        val skinGuard = 1f - ramp(stats.skinCoverage, 0.04f, 0.22f) * 0.5f
        // A small but clean grey card is a perfectly reliable anchor, so ~1%
        // neutral pixels is already full confidence. This is a *technical*
        // cold-cast correction, so it needs enough strength to actually help.
        val conf = (stats.neutralCoverage / 0.012f).coerceIn(0f, 1f)
        return (t * 100f * 0.85f * skinGuard * conf).coerceIn(0f, 34f)
    }

    /** Tint is capped at ±6; green/foliage frames are excluded (that green is memory colour). */
    private fun solveTint(stats: ImageStats): Float {
        if (stats.neutralCoverage < 0.12f || stats.greenCoverage > 0.30f) return 0f
        val r = stats.neutralR.coerceAtLeast(0.01f)
        val g = stats.neutralG.coerceAtLeast(0.01f)
        val b = stats.neutralB.coerceAtLeast(0.01f)
        val kr = g / r
        val kb = g / b
        val t = (kr - kb) / 0.24f
        // G - (R+B)/2 = 0.004t - 0.1u
        val u = (0.004f * t - (1f - (kr + kb) * 0.5f)) / 0.1f
        return (u * 100f * 0.10f).coerceIn(-6f, 6f)
    }

    private fun solveVibrance(stats: ImageStats): Float {
        val target = 0.28f
        return ((target - stats.meanSaturation) * 95f).coerceIn(-12f, 34f)
    }

    private fun buildSummary(
        stats: ImageStats,
        curve: ToneCurve,
        a: EditAdjustments,
        changed: Boolean,
        nearlyPerfect: Boolean
    ): String {
        if (!changed) return "画面已经很均衡，未做改动"
        val parts = buildList {
            if (!curve.isLinear()) add("曲线理顺光感层次")
            if (a.highlights < -3f) add("压回高光")
            if (a.shadows > 3f) add("提亮暗部")
            if (abs(a.exposure) > 0.05f) {
                add(if (a.exposure > 0) "提亮 ${"%.1f".format(a.exposure)} EV" else "压暗 ${"%.1f".format(-a.exposure)} EV")
            }
            if (a.temperature > 8f) add("轻微暖调")
            else if (a.temperature < -8f) add("校正白平衡")
            if (a.texture > 3f) add("保留细节")
            if (a.clarity < -3f) add("轻微柔焦")
            if (!a.hsl.isNeutral()) add("青蓝绿配柔黄")
            if (a.shadowTint > 3f) add("暗部清爽")
            if (a.vibrance > 5f) add("提升自然饱和度")
            else if (a.vibrance < -5f) add("收敛过饱和")
        }
        return if (parts.isEmpty() && nearlyPerfect) {
            "仅做轻微质感增强"
        } else {
            parts.take(4).joinToString(" · ")
        }
    }
}
