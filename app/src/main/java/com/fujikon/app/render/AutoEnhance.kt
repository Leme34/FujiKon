package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.EditCrop
import com.fujikon.app.data.Preset
import com.fujikon.app.data.HslAdjustments
import com.fujikon.app.data.HslBand
import com.fujikon.app.nic.WorkStyle16Preset
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
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
    val brightFraction: Float = 0f,
    /**
     * Fraction of TRUE blown whites: near-white (luma > 0.965) AND effectively
     * colourless (chroma < 0.09), i.e. detail-less clipped pixels. Bright but
     * colourful pixels (warm sun, blue-tinted cloud, a white dress with tone)
     * are deliberately NOT counted — 不过曝 means "no dead white", not "no
     * bright highlights". Keeping them is what preserves 光感 / 色彩 / 层次.
     */
    val blownWhiteClip: Float = 0f,
    /**
     * Raw 64-bin per-channel histograms (counts, before the sqrt normalisation
     * used by the chart). The RGB-histogram tone solver reads these to measure
     * dark / bright mass in EACH channel instead of guessing from one luma
     * histogram. Empty on hand-built stats (unit tests), which then fall back
     * to the luma histogram. Defaults keep every existing constructor valid.
     */
    val redHistogram: IntArray = IntArray(0),
    val greenHistogram: IntArray = IntArray(0),
    val blueHistogram: IntArray = IntArray(0)
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
    val changed: Boolean,
    /** Zones touched by the zoned pass; empty for the plain global pass. */
    val zones: List<String> = emptyList(),
    /** Honest label of the segmentation provider used (may be heuristic). */
    val providerLabel: String = "",
    /** Closed-loop verification report; null for the plain single-pass solvers. */
    val report: OptimizationReport? = null
)

/**
 * The hard quality standard a "true one-tap" optimisation must converge to.
 *
 * 不过曝 / 不死黑 are measured from **real rendered pixels**, not estimated
 * from a parameter object. Crucially, 不过曝 means "no DEAD WHITE" (colourless,
 * detail-less clipped pixels), NOT "no bright pixels": a sunlit frame is
 * supposed to have luminous, colourful highlights. Chasing a near-zero
 * highlight-clip fraction (the 1.6.11 behaviour) forced the loop to hammer
 * highlights/whites/exposure until the frame turned grey, flat and lifeless.
 *
 * So besides the endpoint ceilings there are **retention floors measured
 * against the ORIGINAL**: the optimised render may not lose more than a small
 * fraction of the original's highlight brightness, colour or tonal spread.
 * That is what keeps 光感 / 色彩 / 层次 while still recovering blown detail.
 */
object OptimizationStandard {
    /** True blown whites (near-white AND colourless) must stay rare. */
    const val MAX_BLOWN_WHITE = 0.035f
    /** Bright pixels are allowed — 10% of near-white luma is a sunlit sky, not a fault. */
    const val MAX_HIGHLIGHT_CLIP = 0.10f
    const val MAX_SHADOW_CLIP = 0.030f
    /** Deliberately permissive: an intentionally dark frame is fine as long as
     *  its blacks are not crushed. The loop lifts it toward mid-grey as a soft
     *  goal, without forcing an aggressive exposure that would ruin the mood. */
    const val MIN_MEAN = 0.28f
    const val MAX_MEAN = 0.70f
    const val MAX_SATURATION = 0.72f
    /** Soft target for 通透感 (tonal separation), not a hard rejection gate. */
    const val TARGET_SPREAD = 0.52f
    // ---- "Do not over-press" retention floors, relative to the ORIGINAL ----
    /** p95 of the render must stay within 90% of the original's bright end. */
    const val MIN_BRIGHT_RETENTION = 0.90f
    /** Mean saturation must stay within 82% of the original. */
    const val MIN_SAT_RETENTION = 0.82f
    /** Tonal spread (p95-p5) must stay within 88% — the anti-"dead flat" guard. */
    const val MIN_SPREAD_RETENTION = 0.88f
    /** Overall brightness must stay within 86% — the anti-"dull/gloomy" guard. */
    const val MIN_MEAN_RETENTION = 0.86f
    // ---- Caps on the retention floors (de-contamination) -------------------
    // A blown frame's p95 / spread are inflated by dead white and a neutral
    // low-key frame's saturation is naturally tiny. Requiring a plain 90% of
    // those would forbid the very repair we want, or fail a perfectly good
    // grey card. So each floor is min(original * retention, cap).
    const val BRIGHT_FLOOR_CAP = 0.80f
    const val SPREAD_FLOOR_CAP = 0.46f
    const val SAT_FLOOR_CAP = 0.20f
    /** Caps on the closed loop's corrective sliders (tone only, never colour). */
    const val MIN_WHITES = -26f
    const val MIN_HIGHLIGHTS = -28f
    const val MAX_ITERATIONS = 6
    // ---- 一键优化的「亮度锚定」上界（相对原片，防正常曝光照片被推过曝） ----
    /** 成片整体亮度最多比原片高这么多；正常曝光照片必须基本保持原亮度。 */
    const val MAX_MEAN_RISE = 0.045f
    /** 成片中间调最多比原片高这么多，防止中间调整体发白。 */
    const val MAX_P50_RISE = 0.070f
    /** 原片死黑越多，允许的亮度上升越大 —— 修死黑本身就会提亮画面。 */
    const val MAX_REPAIR_RISE = 0.13f
}

/** What the closed loop actually did and measured on the final render. */
data class OptimizationReport(
    /** Number of real render→measure→refine rounds executed (0 = short-circuit). */
    val iterations: Int,
    /** True when the final render satisfies every hard endpoint of the standard. */
    val converged: Boolean,
    val highlightClip: Float,
    val shadowClip: Float,
    /** Fraction of colourless, detail-less dead whites in the final render. */
    val blownWhiteClip: Float = 0f,
    val meanLuma: Float,
    val spread: Float,
    /** 0 = perfect; lower is better. Used to keep the best round. */
    val defect: Float,
    /** Per-round defect values — proof the loop measured and refined, in order. */
    val trail: List<Float> = emptyList()
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
        var blownWhite = 0

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
            // Dead white = clipped AND colourless. A bright warm highlight or a
            // blue-tinted cloud still has chroma, hence still has colour and
            // detail; it must NOT be treated as over-exposure.
            if (l > 0.965f && chroma < 0.09f) blownWhite++
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
            brightFraction = brightFraction,
            blownWhiteClip = blownWhite / total,
            redHistogram = r,
            greenHistogram = g,
            blueHistogram = b
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
        // Only colourless dead whites justify a firm highlight pull. Bright but
        // colourful pixels ARE the photo's light and must be preserved; the
        // 1.6.11 "chase every bright pixel" behaviour flattened the upper half.
        val hiDeficit = ramp(stats.brightFraction, 0.10f, 0.40f) * 0.25f +
            ramp(stats.highlightClip, 0.02f, 0.12f) * 0.20f +
            ramp(stats.blownWhiteClip, 0.004f, 0.05f) * 0.55f
        val highlights = -(hiDeficit * 16f * toneTouch).coerceIn(0f, 16f)
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
            filterStrength = current.filterStrength,
            // Quality / zone layers are structural user edits: the global
            // solver must never wipe them (same contract as crop / blur).
            denoise = current.denoise,
            grain = current.grain,
            vignette = current.vignette,
            pointColor = current.pointColor,
            local = current.local,
            // Preset strength is a per-photo editing choice, not a tone
            // parameter: one-tap must never silently reset the user's blend.
            presetStrength = current.presetStrength
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
        // Curve roll-off is the PRIMARY dead-white recovery: it compresses only
        // the top of the tone scale (quadratic from x=0.5), so bright midtones
        // keep their light and separation. React to colourless dead white, not
        // to every bright pixel.
        val roll = ((2.0f * highExcess + 1.0f * stats.highlightClip +
            2.4f * stats.blownWhiteClip).coerceIn(0f, 0.085f)) * gain
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

    // ------------------------------------------------------------- zoned pass

    private const val AUTO_PREFIX = "auto:"

    /**
     * Zoned auto-enhance: the proven global curve solver stays primary, and a
     * small number of *evidence-gated* local layers add zone-specific repair.
     *
     * Hard rules (same discipline as [suggest]):
     *  - Good photos are short-circuited to exactly the base result — no zone
     *    layers at all, so "好照片精确 Neutral" and idempotence still hold.
     *  - Every zone layer is triggered only when the segmentation provider
     *    actually finds that region AND the measured histogram shows the
     *    corresponding defect. No fixed "always apply" amounts.
     *  - All zone amounts are hard-capped and much weaker than the global pass.
     *  - Auto layers use stable ids beginning with [AUTO_PREFIX]; each run
     *    replaces the previous auto layers but keeps the user's own painted
     *    layers, so repeated taps are byte-identical (idempotent).
     *  - If no bitmap or no provider result is available it silently degrades
     *    to the plain global solver.
     *
     * [provider] defaults to the offline heuristic; a model-based
     * [SegmentationProvider] can be injected later without touching this code.
     */
    fun suggestZoned(
        source: Bitmap?,
        stats: ImageStats,
        current: EditAdjustments = EditAdjustments.Neutral,
        provider: SegmentationProvider = HeuristicSegmentationProvider
    ): AutoEnhanceResult {
        val base = suggest(stats, current)
        if (!base.changed) return base
        val masks = provider.segment(source, stats) ?: return base

        val skyCoverage = masks.coverage(SemanticRegion.SKY)
        val foliageCoverage = max(masks.coverage(SemanticRegion.FOLIAGE), stats.greenCoverage)
        val subjectCentroid = masks.centroid(SemanticRegion.SUBJECT)
        val skyBottom = (masks.centroid(SemanticRegion.SKY).second * 1.9f + 0.18f).coerceIn(0.25f, 0.80f)

        // Only colourless dead whites justify a firm highlight pull. Bright but
        // colourful pixels ARE the photo's light and must be preserved; the
        // 1.6.11 "chase every bright pixel" behaviour flattened the upper half.
        val hiDeficit = ramp(stats.brightFraction, 0.10f, 0.40f) * 0.25f +
            ramp(stats.highlightClip, 0.02f, 0.12f) * 0.20f +
            ramp(stats.blownWhiteClip, 0.004f, 0.05f) * 0.55f
        val shDeficit = ramp(stats.shadowFraction, 0.10f, 0.50f) * 0.6f +
            ramp(stats.shadowClip, 0.01f, 0.09f) * 0.4f

        val zones = ArrayList<String>(3)
        val auto = ArrayList<LocalLayer>(3)

        // ① Sky: recover only colourless dead whites where sky actually exists.
        //    Never desaturate the sky here — that was a direct cause of the
        //    "lost colour" complaint. A bright, colourful sky keeps its chroma.
        if (skyCoverage > 0.05f && stats.blownWhiteClip > 0.004f && hiDeficit > 0.04f) {
            val amount = (hiDeficit * 11f * ramp(skyCoverage, 0.05f, 0.28f)).coerceIn(0f, 11f)
            if (amount > 0.5f) {
                auto += LocalLayer(
                    id = "${AUTO_PREFIX}sky",
                    name = "天空 · 收敛死白",
                    mask = LocalMask.LinearGradient(
                        startX = 0.5f, startY = 0f, endX = 0.5f, endY = skyBottom,
                        feather = 0.55f, invert = true
                    ),
                    params = LocalParams(highlights = -amount),
                    strength = 100f
                )
                zones += "天空（覆盖 ${(skyCoverage * 100).roundToInt()}%）收敛死白"
            }
        }

        // ② Subject: lift shadows around the compositional centre only when
        //    the frame actually has crushed / heavy dark areas.
        if (shDeficit > 0.05f) {
            val amount = (shDeficit * 16f).coerceIn(0f, 16f)
            if (amount > 0.5f) {
                auto += LocalLayer(
                    id = "${AUTO_PREFIX}subject",
                    name = "主体 · 提阴影",
                    mask = LocalMask.ControlPoint(
                        x = subjectCentroid.first.coerceIn(0.1f, 0.9f),
                        y = subjectCentroid.second.coerceIn(0.1f, 0.9f),
                        radius = 0.44f,
                        feather = 0.78f
                    ),
                    params = LocalParams(
                        shadows = amount,
                        brightness = if (stats.meanLuma < 0.42f) amount * 0.22f else 0f,
                        clarity = amount * 0.16f
                    ),
                    strength = 100f
                )
                zones += "主体区域提阴影"
            }
        }

        // ③ Background: a gentle outside-radial desaturation ONLY when there
        //    is real foliage evidence and the frame is over-saturated. Indoor
        //    portraits, snow and night shots are never tinted.
        if (foliageCoverage > 0.10f && stats.meanSaturation > 0.46f) {
            val amount = (ramp(stats.meanSaturation, 0.46f, 0.72f) * 9f *
                ramp(foliageCoverage, 0.10f, 0.30f)).coerceIn(0f, 9f)
            if (amount > 0.5f) {
                auto += LocalLayer(
                    id = "${AUTO_PREFIX}background",
                    name = "背景 · 收敛过饱和",
                    mask = LocalMask.RadialGradient(
                        x = subjectCentroid.first.coerceIn(0.15f, 0.85f),
                        y = subjectCentroid.second.coerceIn(0.15f, 0.85f),
                        radiusX = 0.42f, radiusY = 0.48f,
                        feather = 0.72f, invert = true
                    ),
                    params = LocalParams(saturation = -amount),
                    strength = 100f
                )
                zones += "绿色背景收敛过饱和"
            }
        }

        if (auto.isEmpty()) return base

        val userLayers = current.local.layers.filterNot { it.id.startsWith(AUTO_PREFIX) }
        val nextLocal = LocalAdjustments(userLayers + auto).normalized()
        val next = base.adjustments.copy(local = nextLocal)
        val summary = if (base.summary.isBlank()) {
            "分区优化：" + zones.joinToString("、")
        } else {
            base.summary + " · 分区：" + zones.joinToString("、")
        }
        return AutoEnhanceResult(
            adjustments = next,
            findings = base.findings,
            summary = summary,
            changed = next != current,
            zones = zones,
            providerLabel = provider.label
        )
    }

    // --------------------------------------------------- closed-loop optimiser

    /** Real render verification runs on a tiny copy (≈37k pixels) so a full
     *  loop costs well under a second even on a mid-range phone. */
    private const val PROBE_SIDE = 192

    /**
     * TRUE one-tap optimisation: iterate until the standard is met.
     *
     * The proven single-pass [suggestZoned] still supplies the whole recipe —
     * master curve, 压高光提阴影, 青蓝绿配柔黄 triggered by colour evidence,
     * 细节/柔光, and evidence-gated zone layers. What this adds is a **closed
     * feedback loop over real rendered pixels**:
     *
     *   candidate → render on the ORIGINAL (downscaled) → analyse() → measure
     *   blown whites / crushed blacks → correct ONLY tone parameters → repeat
     *
     * until the render clears "不过曝 / 不死黑", or the round budget is spent
     * (in which case the best measured candidate is returned, never a guess).
     *
     * Guarantees kept from the regressions:
     *  - Good AND already-safe frames short-circuit to exactly Neutral, with
     *    zero renders (好照片精确不动).
     *  - Colour / texture / filter / zone layers are NEVER touched by [refine],
     *    so the four-step look cannot be overwritten by convergence.
     *  - Absolute parameters recomputed from the ORIGINAL: repeated taps are
     *    byte-identical (no runaway accumulation).
     */
    suspend fun optimize(
        source: Bitmap?,
        basePreset: Preset = Preset.none(),
        current: EditAdjustments = EditAdjustments.Neutral,
        provider: SegmentationProvider = HeuristicSegmentationProvider,
        renderer: ImageRenderer = ImageRenderer()
    ): AutoEnhanceResult {
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) {
            return AutoEnhanceResult(current, emptyList(), "无预览，无法优化", false)
        }
        val stats = analyse(source)
            ?: return AutoEnhanceResult(current, emptyList(), "无法分析画面", false)

        val seed = suggestZoned(source, stats, current, provider)

        // Already good AND already compliant: no rendering at all, exact Neutral.
        // The original is its own reference, so every retention floor is met
        // by definition and only the endpoint ceilings are evaluated.
        if (!seed.changed && meetsStandard(stats, stats)) {
            return seed.copy(report = reportOf(0, true, stats, stats))
        }

        val probe = probeBitmap(source)
        var candidate = seed.adjustments
        var best = candidate
        var bestDefect = Float.POSITIVE_INFINITY
        var bestMeasured: ImageStats? = null
        var iterations = 0
        var converged = false
        val trail = ArrayList<Float>(OptimizationStandard.MAX_ITERATIONS)

        for (i in 0 until OptimizationStandard.MAX_ITERATIONS) {
            iterations = i + 1
            val rendered = renderer.render(
                probe,
                // Crop is composition, not tone: clearing it keeps the probe
                // independent of rotation. The user's crop is preserved in the
                // returned adjustments below.
                basePreset.copy(adjustments = candidate.copy(crop = EditCrop.Full)),
                forExport = false
            )
            val measured = analyse(rendered) ?: break
            val d = defect(measured, stats)
            trail.add(d)

            // Compliance wins over the soft score: never return a round that
            // merely scored better while still clipping an endpoint or while
            // having crushed the original's light / colour / separation away.
            if (meetsStandard(measured, stats)) {
                best = candidate
                bestMeasured = measured
                converged = true
                break
            }

            val previousDefect = bestDefect
            if (d < bestDefect) {
                bestDefect = d
                best = candidate
                bestMeasured = measured
            }
            // A markedly worse round means we overshot; keep the best measured.
            if (i >= 2 && d > previousDefect + 0.10f) break
            if (i == OptimizationStandard.MAX_ITERATIONS - 1) break
            candidate = refine(candidate, measured, i)
        }

        val report = reportOf(iterations, converged, bestMeasured, stats, trail)
        val loopTag = if (converged) "闭环${iterations}轮达标" else "闭环${iterations}轮取最优"
        val summary = if (seed.summary.isBlank()) loopTag else seed.summary + " · " + loopTag
        return AutoEnhanceResult(
            adjustments = best,
            findings = seed.findings,
            summary = summary,
            changed = best != current,
            zones = seed.zones,
            providerLabel = seed.providerLabel,
            report = report
        )
    }

    // ------------------------------- 通透胶片 + RGB 直方图一键优化 (1.6.39)

    private data class RgbTone(
        val p5: Float,
        val p95: Float,
        val spread: Float,
        val darkFraction: Float,
        val brightFraction: Float
    )

    /**
     * Measures dark / bright mass from the RAW R, G and B histograms rather
     * than from the single luma histogram. A channel-separated view matters:
     * a blue sky or a warm sunset can clip one channel while luma still looks
     * fine, and that is exactly the 光感/层次 the one-tap pass must protect.
     * Hand-built stats (unit tests) have no raw bins and fall back to luma.
     */
    private fun rgbTone(stats: ImageStats): RgbTone {
        val channels = listOf(stats.redHistogram, stats.greenHistogram, stats.blueHistogram)
            .filter { it.isNotEmpty() }
        if (channels.isEmpty()) {
            return RgbTone(
                p5 = stats.p5,
                p95 = stats.p95,
                spread = stats.spread,
                darkFraction = stats.shadowFraction,
                brightFraction = stats.brightFraction
            )
        }
        var sumP5 = 0f
        var sumP95 = 0f
        var sumDark = 0f
        var sumBright = 0f
        channels.forEach { h ->
            val total = h.sum().toFloat().coerceAtLeast(1f)
            var acc = 0
            var lo = -1f
            var hi = -1f
            var dark = 0
            var bright = 0
            for (i in h.indices) {
                val x = (i + 0.5f) / h.size
                acc += h[i]
                if (lo < 0f && acc >= total * 0.05f) lo = x
                if (hi < 0f && acc >= total * 0.95f) hi = x
                if (x < 0.14f) dark += h[i]
                if (x > 0.88f) bright += h[i]
            }
            sumP5 += if (lo < 0f) 1f else lo
            sumP95 += if (hi < 0f) 1f else hi
            sumDark += dark / total
            sumBright += bright / total
        }
        val n = channels.size.toFloat()
        val lo = sumP5 / n
        val hi = sumP95 / n
        return RgbTone(
            p5 = lo,
            p95 = hi,
            spread = (hi - lo).coerceIn(0f, 1f),
            darkFraction = sumDark / n,
            brightFraction = sumBright / n
        )
    }

    /**
     * 「通透胶片」基底 LUT 的近似反函数（256 点，值域 0..1）。
     *
     * 一键优化**默认完整保留**这条提亮型 LUT（中间调 128 → 171）—— 那正是
     * 通透胶片的效果本身。只有当原片本身已经过亮或已有大面积死白时，才用
     * 这条反函数做**部分**收敛，避免通透胶片让一张本就过曝的照片雪上加霜；
     * 正常曝光的原片 anchor=0，这条曲线完全不启用。反演方式：找到最小的 x
     * 使 LUT[x] >= y。作用在 luma 域、三通道同 scale+lift，天然保色。
     */
    private val WORKSTYLE_INVERSE: FloatArray by lazy {
        val lut = WorkStyle16Preset.toPreset().lumaToneLut
        if (lut == null || lut.size < 256) {
            FloatArray(256) { it / 255f }
        } else {
            FloatArray(256) { y ->
                var x = 0
                while (x < 255 && lut[x] < y) x++
                x / 255f
            }
        }
    }

    /**
     * 只针对「原片本身已经过亮 / 已有死白」的自适应保护，返回 0..0.9。
     * 正常曝光或偏暗的原片返回 0 —— 通透胶片完整生效（用户要的就是这个
     * 效果）。原片越亮、死白越多，才越用力把通透胶片的提亮收敛一部分。
     */
    private fun workStyleAnchor(original: ImageStats): Float =
        (ramp(original.meanLuma, 0.62f, 0.80f) * 0.75f +
            ramp(original.blownWhiteClip, 0.008f, 0.05f) * 0.85f)
            .coerceIn(0f, 0.9f)

    /** 相对「已套用通透胶片的效果图」允许的亮度上升：优化只修缺陷，
     *  绝不在这张已经提亮过的图上再叠一轮提亮。死黑多的效果图留修复空间。 */
    private fun meanRiseAllowance(ref: ImageStats): Float =
        OptimizationStandard.MAX_MEAN_RISE +
            ramp(ref.shadowClip, 0.03f, 0.24f) * OptimizationStandard.MAX_REPAIR_RISE

    private fun p50RiseAllowance(ref: ImageStats): Float =
        OptimizationStandard.MAX_P50_RISE +
            ramp(ref.shadowClip, 0.03f, 0.24f) * OptimizationStandard.MAX_REPAIR_RISE

    /**
     * Luma-domain master curve (the renderer applies it with the same
     * scale + lift to all three channels, so hue/saturation stay EXACT).
     *
     * The curve's input axis is the brightness AFTER the 通透胶片 LUT (the
     * renderer chains them as userToneLut[presetLut[i]]). One-tap keeps
     * [anchor] = 0 for normal originals, so the base LUT's effect is preserved
     * in full; a non-zero anchor only kicks in for an already-overexposed
     * original, blending toward the LUT inverse to avoid piling on brightness.
     * On top of that, the toe is ZERO both at x=0 and x=0.5: pure black stays
     * black (不死黑 / 不发灰), mid-tones stay put, and only the quarter-tone
     * shadows are lifted. The shoulder compresses the top end smoothly (不过曝).
     */
    private fun workStyleCurve(toe: Float, roll: Float, s: Float, anchor: Float): ToneCurve {
        if (anchor < 0.002f && toe < 0.002f && roll < 0.002f && s < 0.002f) return ToneCurve()
        val inverse = WORKSTYLE_INVERSE
        val points = ArrayList<ToneCurvePoint>(25)
        var prevY = 0f
        val n = 24
        for (i in 0..n) {
            val x = i / n.toFloat()
            // Convex combination of identity and the monotone LUT inverse:
            // both are monotone, so the result is monotone too.
            var y = x + anchor *
                (inverse[(x * 255f).roundToInt().coerceIn(0, 255)] - x)
            if (toe > 0f && x < 0.5f) {
                val t = 1f - x / 0.5f
                y += toe * 4f * t * t * (1f - t)
            }
            if (roll > 0f && x > 0.5f) {
                val t = (x - 0.5f) / 0.5f
                y -= roll * t * t
            }
            if (s > 0f) y -= (s * sin(2.0 * PI * x.toDouble())).toFloat()
            y = y.coerceIn(0f, 1f)
            if (y < prevY) y = prevY // monotonic: no tone reversal
            points.add(ToneCurvePoint(x, y))
            prevY = y
        }
        return ToneCurve(points)
    }

    /**
     * One-tap correction recipe that runs ON TOP of the 「通透胶片」 look.
     *
     * [stats] must describe the frame AFTER the base look has been applied
     * (阶段 1 的产物), not the raw original — this is what makes the one-tap
     * genuinely "先套用通透胶片，再优化". [original] is the untouched source,
     * used only for the already-overexposed safeguard. Everything added here is
     * driven by the measured RGB histogram and is TONE ONLY: temperature /
     * tint / vibrance / HSL / split-tone are forced to exactly zero, so the
     * "绝不偏色" red line cannot be crossed. Exposure stays 0, blacks stay put.
     */
    fun suggestWorkStyle(
        stats: ImageStats,
        current: EditAdjustments = EditAdjustments.Neutral,
        original: ImageStats? = null
    ): AutoEnhanceResult {
        val findings = describe(stats)
        val rgb = rgbTone(stats)
        val base = WorkStyle16Preset.toPreset().adjustments

        // 正常情况下 anchor=0，通透胶片的 LUT 完整生效；只有原片本身已经
        // 过亮/死白时才部分收敛。优化对象已经套过通透胶片（暗部已被抬亮），
        // 提亮型修正再减半，杜绝「基底提亮 + 优化又提亮」的双重过曝。
        val anchor = original?.let { workStyleAnchor(it) } ?: 0f
        val lift = (1f - anchor * 0.88f) * 0.5f

        val shadowDeficit =
            ramp(0.16f - rgb.p5, 0f, 0.16f) * 0.50f +
                ramp(rgb.darkFraction, 0.05f, 0.30f) * 0.30f +
                ramp(stats.shadowClip, 0.004f, 0.05f) * 0.20f
        val highlightExcess =
            ramp(rgb.p95 - 0.86f, 0f, 0.12f) * 0.40f +
                ramp(rgb.brightFraction, 0.03f, 0.22f) * 0.25f +
                ramp(stats.blownWhiteClip, 0.002f, 0.04f) * 0.35f
        val flatness = ramp(0.54f - rgb.spread, 0f, 0.30f)

        val toe = (shadowDeficit * 0.055f * lift).coerceIn(0f, 0.055f)
        // 压高光/白点永远保留：它是「不过曝」的正向修正，不受锚定抑制。
        val roll = (highlightExcess * 0.060f).coerceIn(0f, 0.060f)
        val s = (flatness * 0.075f * lift).coerceIn(0f, 0.075f)
        val curve = workStyleCurve(toe, roll, s, anchor)

        val next = base.copy(
            exposure = 0f,
            contrast = (flatness * 12f * (1f - anchor * 0.6f)).coerceIn(0f, 12f),
            highlights = -(highlightExcess * 15f).coerceIn(0f, 15f),
            shadows = (shadowDeficit * 15f * lift).coerceIn(0f, 15f),
            whites = -(highlightExcess * 9f).coerceIn(0f, 9f),
            blacks = 0f,
            texture = base.texture + 3f + flatness * 6f,
            clarity = base.clarity + 2f + flatness * 6f,
            dehaze = 0f,
            toneCurve = if (curve.isLinear()) null else curve,
            // Colour layers stay EXACTLY neutral — no cast, ever.
            temperature = 0f,
            tint = 0f,
            vibrance = 0f,
            shadowTint = 0f,
            hsl = HslAdjustments.Neutral,
            // Structural user edits survive the one-tap.
            crop = current.crop,
            lensBlur = current.lensBlur,
            creativeFilter = current.creativeFilter,
            filterStrength = current.filterStrength,
            denoise = current.denoise,
            grain = current.grain,
            pointColor = current.pointColor,
            local = current.local,
            presetStrength = current.presetStrength
        ).normalized()

        val parts = buildList {
            if (!curve.isLinear()) add("RGB 直方图理顺光感层次")
            if (next.shadows > 2f) add("提暗部")
            if (next.highlights < -2f) add("压高光")
            if (next.contrast > 2f) add("增强质感")
        }
        return AutoEnhanceResult(
            adjustments = next,
            findings = findings,
            summary = if (parts.isEmpty()) "先套通透胶片 · 画面已均衡" else "先套通透胶片 · " + parts.joinToString(" · "),
            changed = next != current
        )
    }

    /**
     * 一键优化 = 真正的两阶段管线。
     *
     * 阶段 1：把「通透胶片」完整渲染到原图（LUT + 自带 adjustments + 用户的
     *   预设强度），得到 graded。这一步就是「先套用通透胶片的效果」。
     * 阶段 2：在 graded 上量 RGB 直方图，生成提暗部 / 压高光 / 理顺层次的
     *   修正，并在真实渲染像素上闭环验证不死黑 / 不发灰 / 不过曝。
     *
     * 闭环基准是 graded 而不是原图：成片必须看得出通透胶片的效果，但绝不
     * 允许比「单纯套通透胶片」更亮、也不许新增无色死白。这样既保住通透
     * 胶片，又修掉「基底提亮 + 优化又提亮」的双重过曝。重复点击结果一致：
     * 所有量都从原图重新计算。
     */
    suspend fun optimizeWorkStyle(
        source: Bitmap?,
        current: EditAdjustments = EditAdjustments.Neutral,
        renderer: ImageRenderer = ImageRenderer()
    ): AutoEnhanceResult {
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) {
            return AutoEnhanceResult(current, emptyList(), "无预览，无法优化", false)
        }
        val originalStats = analyse(source)
            ?: return AutoEnhanceResult(current, emptyList(), "无法分析画面", false)
        val basePreset = WorkStyle16Preset.toPreset()
        val probe = probeBitmap(source)

        // ---- 阶段 1：真正先把通透胶片套上去 ------------------------------
        // 完整保留基底的 lumaToneLut、自带 adjustments 与用户设定的预设强度；
        // 这张 graded 才是阶段 2 的优化对象（不再是原图）。
        val gradedPreset = basePreset.copy(
            adjustments = basePreset.adjustments.copy(
                presetStrength = current.presetStrength,
                crop = EditCrop.Full
            )
        )
        val graded = renderer.render(probe, gradedPreset, forExport = false)
        val gradedStats = analyse(graded)
            ?: return AutoEnhanceResult(current, emptyList(), "无法分析通透胶片效果", false)

        // ---- 阶段 2：在已套用的效果图上做 RGB 直方图优化 ------------------
        val seed = suggestWorkStyle(gradedStats, current, originalStats)
        var candidate = seed.adjustments
        var best = candidate
        var bestDefect = Float.POSITIVE_INFINITY
        var bestMeasured: ImageStats? = null
        var iterations = 0
        var converged = false
        val trail = ArrayList<Float>(OptimizationStandard.MAX_ITERATIONS)

        for (i in 0 until OptimizationStandard.MAX_ITERATIONS) {
            iterations = i + 1
            val rendered = renderer.render(
                probe,
                basePreset.copy(adjustments = candidate.copy(crop = EditCrop.Full)),
                forExport = false
            )
            val measured = analyse(rendered) ?: break
            val d = workStyleDefect(measured, gradedStats)
            trail.add(d)
            if (meetsWorkStyleStandard(measured, gradedStats, originalStats)) {
                best = candidate
                bestMeasured = measured
                converged = true
                break
            }
            val previousDefect = bestDefect
            if (d < bestDefect) {
                bestDefect = d
                best = candidate
                bestMeasured = measured
            }
            if (i >= 2 && d > previousDefect + 0.10f) break
            if (i == OptimizationStandard.MAX_ITERATIONS - 1) break
            candidate = refineWorkStyle(candidate, measured, gradedStats, i)
        }

        val report = reportOf(
            iterations, converged, bestMeasured, gradedStats, trail, ::workStyleDefect
        )
        val loopTag = if (converged) "闭环${iterations}轮达标" else "闭环${iterations}轮取最优"
        return AutoEnhanceResult(
            adjustments = best,
            findings = seed.findings,
            summary = if (seed.summary.isBlank()) loopTag else seed.summary + " · " + loopTag,
            changed = best != current,
            report = report
        )
    }

    /** Hard standard for the two-stage loop. [ref] is the frame WITH the base
     *  look applied, so the render must visibly keep that look while never
     *  getting brighter than it. [original] guards the real over-exposure
     *  signal: the one-tap may not create colourless dead whites that were
     *  absent from the source. */
    private fun meetsWorkStyleStandard(
        m: ImageStats,
        ref: ImageStats,
        original: ImageStats
    ): Boolean =
        meetsStandard(m, ref) &&
            m.stdLuma >= ref.stdLuma * 0.78f &&
            m.p5 <= ref.p5 + 0.22f &&
            m.meanLuma <= ref.meanLuma + meanRiseAllowance(ref) &&
            m.p50 <= ref.p50 + p50RiseAllowance(ref) &&
            m.blownWhiteClip <= original.blownWhiteClip + 0.012f

    /** Scalar score for the work-style loop: shared endpoint defects plus a
     *  heavy penalty for brightening the frame beyond the original. */
    private fun workStyleDefect(m: ImageStats, ref: ImageStats): Float {
        val overBright =
            max(0f, m.meanLuma - (ref.meanLuma + meanRiseAllowance(ref))) / 0.05f
        val overMid =
            max(0f, m.p50 - (ref.p50 + p50RiseAllowance(ref))) / 0.08f
        return defect(m, ref) + overBright * 3.5f + overMid * 1.5f
    }

    /** Tone-only correction; never lets the loop brighten the whole frame, and
     *  actively pulls it back down when the render came out over-exposed. */
    private fun refineWorkStyle(
        candidate: EditAdjustments,
        m: ImageStats,
        ref: ImageStats,
        round: Int
    ): EditAdjustments {
        val refined = refine(candidate, m, round)
        val over = m.meanLuma - (ref.meanLuma + meanRiseAllowance(ref))
        val pulled = if (over > 0.008f) {
            // Overall brightening = over-exposure. A small negative exposure
            // (linear-light domain) restores the original level, and the
            // lift-type sliders are reined in so the next round cannot relift.
            val ev = -(over * 1.8f).coerceIn(0f, 0.5f)
            refined.copy(
                exposure = (refined.exposure + ev).coerceIn(-1.2f, 0f),
                shadows = (refined.shadows - over * 40f).coerceAtLeast(0f),
                whites = (refined.whites - over * 15f)
                    .coerceIn(OptimizationStandard.MIN_WHITES, 0f)
            )
        } else refined
        return pulled.copy(
            exposure = min(pulled.exposure, candidate.exposure).coerceAtMost(0f),
            blacks = pulled.blacks.coerceAtLeast(0f)
        ).normalized()
    }

    private fun probeBitmap(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        // Never upscale: enlarging a small frame would interpolate clipped
        // pixels away and make the verification disagree with the real render.
        if (longest <= PROBE_SIDE) return source
        val scale = PROBE_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * Hard standard, evaluated against BOTH the absolute endpoints and the
     * ORIGINAL frame. 不过曝 now means "no colourless dead whites", not "no
     * bright pixels"; the retention floors stop the loop from pressing the
     * highlights down until the picture loses its light, colour and depth.
     */
    private fun meetsStandard(m: ImageStats, ref: ImageStats): Boolean {
        val meanFloor = max(
            OptimizationStandard.MIN_MEAN,
            ref.meanLuma * OptimizationStandard.MIN_MEAN_RETENTION
        )
        // A genuinely bright original is allowed to STAY bright; only a frame
        // that became even brighter than the source is rejected here.
        val meanCeil = max(OptimizationStandard.MAX_MEAN, ref.meanLuma * 1.02f)
        // Retention floors are capped so dead white / natural low saturation
        // in the ORIGINAL cannot turn a correct repair into a "failure".
        val brightFloor = min(
            ref.p95 * OptimizationStandard.MIN_BRIGHT_RETENTION,
            OptimizationStandard.BRIGHT_FLOOR_CAP
        )
        val spreadFloor = min(
            ref.spread * OptimizationStandard.MIN_SPREAD_RETENTION,
            OptimizationStandard.SPREAD_FLOOR_CAP
        )
        val satFloor = if (ref.meanLuma > 0.35f) {
            min(
                ref.meanSaturation * OptimizationStandard.MIN_SAT_RETENTION,
                OptimizationStandard.SAT_FLOOR_CAP
            )
        } else 0f // lifting a dark frame legitimately lowers HSV saturation
        return m.blownWhiteClip <= OptimizationStandard.MAX_BLOWN_WHITE &&
            m.highlightClip <= OptimizationStandard.MAX_HIGHLIGHT_CLIP &&
            m.shadowClip <= OptimizationStandard.MAX_SHADOW_CLIP &&
            m.meanLuma >= meanFloor &&
            m.meanLuma <= meanCeil &&
            // 光感 / 色彩 / 层次 must survive the repair.
            m.p95 >= brightFloor &&
            m.spread >= spreadFloor &&
            m.meanSaturation >= satFloor &&
            m.meanSaturation <= OptimizationStandard.MAX_SATURATION
    }

    /**
     * Scalar defect for picking the best round. Besides endpoint violations it
     * penalises **over-pressing**: losing the original's highlight brightness,
     * colour or tonal separation. A compliant round that keeps the original's
     * light scores 0; a grey, flat, desaturated round scores badly even when it
     * has zero clipped pixels.
     */
    private fun defect(m: ImageStats, ref: ImageStats): Float {
        val blown = max(0f, m.blownWhiteClip - OptimizationStandard.MAX_BLOWN_WHITE) / 0.05f
        val hi = max(0f, m.highlightClip - OptimizationStandard.MAX_HIGHLIGHT_CLIP) / 0.12f
        val lo = max(0f, m.shadowClip - OptimizationStandard.MAX_SHADOW_CLIP) / 0.06f
        val meanFloor = max(
            OptimizationStandard.MIN_MEAN,
            ref.meanLuma * OptimizationStandard.MIN_MEAN_RETENTION
        )
        val meanCeil = max(OptimizationStandard.MAX_MEAN, ref.meanLuma * 1.02f)
        val mean = (max(0f, meanFloor - m.meanLuma) +
            max(0f, m.meanLuma - meanCeil)) / 0.18f
        val sat = max(0f, m.meanSaturation - OptimizationStandard.MAX_SATURATION) / 0.20f
        // ---- over-press penalties, using the SAME de-contaminated floors ----
        // as meetsStandard, so a compliant, lively render scores exactly 0 and
        // the report stays self-consistent with the convergence flag.
        val brightFloor = min(
            ref.p95 * OptimizationStandard.MIN_BRIGHT_RETENTION,
            OptimizationStandard.BRIGHT_FLOOR_CAP
        )
        val spreadFloor = min(
            ref.spread * OptimizationStandard.MIN_SPREAD_RETENTION,
            OptimizationStandard.SPREAD_FLOOR_CAP
        )
        val satFloor = if (ref.meanLuma > 0.35f) {
            min(
                ref.meanSaturation * OptimizationStandard.MIN_SAT_RETENTION,
                OptimizationStandard.SAT_FLOOR_CAP
            )
        } else 0f
        val flatBright = max(0f, brightFloor - m.p95) / 0.08f
        val flat = max(0f, spreadFloor - m.spread) / 0.16f
        val desat = max(0f, satFloor - m.meanSaturation) / 0.12f
        val dim = max(0f, ref.meanLuma * OptimizationStandard.MIN_MEAN_RETENTION - m.meanLuma) / 0.10f
        return blown * 3f + lo * 2.5f + hi * 0.6f + mean * 1.2f + sat * 0.5f +
            flatBright * 2.5f + flat * 1.5f + desat * 2f + dim * 2f
    }

    private fun reportOf(
        iterations: Int,
        converged: Boolean,
        m: ImageStats?,
        ref: ImageStats,
        trail: List<Float> = emptyList(),
        score: (ImageStats, ImageStats) -> Float = { a, b -> defect(a, b) }
    ): OptimizationReport = OptimizationReport(
        iterations = iterations,
        converged = converged,
        highlightClip = m?.highlightClip ?: 0f,
        shadowClip = m?.shadowClip ?: 0f,
        blownWhiteClip = m?.blownWhiteClip ?: 0f,
        meanLuma = m?.meanLuma ?: 0f,
        spread = m?.spread ?: 0f,
        defect = m?.let { score(it, ref) } ?: 0f,
        trail = trail
    )

    /**
     * One corrective step. ONLY tone parameters move — colour (HSL,
     * temperature, shadowTint), texture/clarity, filters and zone layers stay
     * exactly as the recipe produced them. Step sizes decay each round so the
     * loop converges instead of oscillating around the endpoint thresholds.
     *
     * The highlight branch now reacts to colourless DEAD WHITE, not to every
     * bright pixel, and prefers the narrow white-point control (x > 0.70) over
     * the broad highlights slider (x > 0.48, which flattens the whole upper
     * half). It never lowers overall exposure — that was the main source of the
     * dull, lifeless look — and lifts shadows slightly to keep the frame airy.
     */
    private fun refine(candidate: EditAdjustments, m: ImageStats, round: Int): EditAdjustments {
        val decay = 1f / (1f + round * 0.7f)
        var exposure = candidate.exposure
        var contrast = candidate.contrast
        var highlights = candidate.highlights
        var shadows = candidate.shadows
        var whites = candidate.whites
        var blacks = candidate.blacks

        // Only colourless dead whites drive highlight compression. A residual
        // signal from extreme bright-luma clipping keeps pathological frames in
        // check, but at one quarter strength so luminous colour survives.
        val blownOver = max(0f, m.blownWhiteClip - OptimizationStandard.MAX_BLOWN_WHITE)
        val clipOver = max(0f, m.highlightClip - OptimizationStandard.MAX_HIGHLIGHT_CLIP) * 0.25f
        val hiOver = max(blownOver, clipOver)
        val loOver = m.shadowClip - OptimizationStandard.MAX_SHADOW_CLIP

        when {
            hiOver > 0.002f && loOver > 0.002f -> {
                // Both ends clipped: the tonal stretch is too aggressive.
                val step = (min(hiOver, loOver) * 220f).coerceIn(1.5f, 10f) * decay
                contrast = (contrast - step).coerceIn(-30f, 100f)
                whites = (whites - step * 0.4f).coerceIn(OptimizationStandard.MIN_WHITES, 0f)
                blacks = (blacks + step * 0.5f).coerceIn(0f, 60f)
            }
            hiOver > 0.002f -> {
                val step = (hiOver * 220f).coerceIn(1.5f, 10f) * decay
                // Narrow white-point control first: it only touches the very top.
                whites = (whites - step).coerceIn(OptimizationStandard.MIN_WHITES, 0f)
                // Only when dead white still persists do we touch the broad
                // highlights slider, and even then gently.
                if (m.blownWhiteClip > OptimizationStandard.MAX_BLOWN_WHITE + 0.008f) {
                    highlights = (highlights - step * 0.28f)
                        .coerceIn(OptimizationStandard.MIN_HIGHLIGHTS, 0f)
                }
                // Keep the frame airy instead of letting it go dull. Overall
                // exposure is deliberately NOT lowered here.
                shadows = (shadows + step * 0.12f).coerceIn(0f, 30f)
            }
            loOver > 0.002f -> {
                val step = (loOver * 240f).coerceIn(2f, 14f) * decay
                blacks = (blacks + step).coerceIn(0f, 60f)
                shadows = (shadows + step * 0.6f).coerceIn(0f, 80f)
                exposure = (exposure + step * 0.010f).coerceIn(-2f, 2f)
            }
            else -> {
                // Endpoints safe. ONLY lift a frame that is still genuinely
                // dark; NEVER darken a bright one toward a fixed 0.47 — that
                // was the 1.6.11 "dull / lifeless" regression. A luminous
                // frame keeps its original brightness, colour and light.
                if (m.meanLuma < 0.34f) {
                    val lift = (0.44f - m.meanLuma) * 1.15f * decay
                    exposure = (exposure + lift).coerceIn(-2f, 2f)
                }
                // A small contrast nudge restores separation on flat frames
                // without pushing the highlight level up into clipping.
                if (m.spread < OptimizationStandard.TARGET_SPREAD) {
                    val step = (OptimizationStandard.TARGET_SPREAD - m.spread) * 22f * decay
                    contrast = (contrast + step).coerceIn(0f, 32f)
                }
            }
        }
        return candidate.copy(
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            whites = whites,
            blacks = blacks
        ).normalized()
    }
}
