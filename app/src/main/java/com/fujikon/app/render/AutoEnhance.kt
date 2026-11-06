package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    val lumaHistogram: IntArray
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
 * Lightroom-class automatic photo optimisation.
 *
 * Instead of a handful of hard-coded "if mean is dark, add 0.3 EV" rules, this
 * engine closes the loop: it mirrors the exact tone curve used by
 * [ImageRenderer.buildAdjustmentToneLut], applies candidate adjustments to the
 * measured 256-bin luma histogram and picks the parameter set whose predicted
 * result is closest to a photographic target (mid-tone ~0.47, deep but not
 * crushed blacks, bright but not clipped whites). White balance is solved by
 * inverting the renderer's channel gains against a grey-world estimate, and
 * saturation / clarity / dehaze are driven by the measured colour and detail
 * statistics.
 *
 * The output is a plain [EditAdjustments], so the result stays non-destructive,
 * undoable, editable with the manual sliders and shared by preview and export
 * exactly like any other adjustment in the app.
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
            lumaHistogram = lumaHist
        )
    }

    /**
     * Solves the best adjustment delta for [stats] and adds it to [current].
     */
    fun suggest(stats: ImageStats, current: EditAdjustments = EditAdjustments.Neutral): AutoEnhanceResult {
        val findings = describe(stats)
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

        val delta = solveTone(stats)
        val temperature = solveTemperature(stats)
        val tint = solveTint(stats)
        val vibrance = solveVibrance(stats)
        val clarity = if (stats.detail < 0.012f) 18f else if (stats.detail < 0.020f) 10f else 4f
        val texture = if (stats.detail < 0.012f) 12f else if (stats.detail < 0.020f) 6f else 0f
        val hazy = stats.spread < 0.52f && stats.meanLuma > 0.50f && stats.meanSaturation < 0.20f
        val dehaze = if (hazy) 16f else if (stats.spread < 0.42f) 8f else 0f

        val next = current.copy(
            exposure = current.exposure + delta.exposure,
            contrast = current.contrast + delta.contrast,
            highlights = current.highlights + delta.highlights,
            shadows = current.shadows + delta.shadows,
            whites = current.whites + delta.whites,
            blacks = current.blacks + delta.blacks,
            temperature = current.temperature + temperature,
            tint = current.tint + tint,
            vibrance = current.vibrance + vibrance,
            clarity = current.clarity + clarity,
            texture = current.texture + texture,
            dehaze = current.dehaze + dehaze
        ).normalized()

        val changed = next != current
        return AutoEnhanceResult(
            adjustments = next,
            findings = findings,
            summary = buildSummary(stats, delta, temperature, vibrance, changed, nearlyPerfect),
            changed = changed
        )
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

    private data class Delta(
        val exposure: Float = 0f,
        val contrast: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val whites: Float = 0f,
        val blacks: Float = 0f
    )

    /** Linear-light value for each 0..255 luma bin, computed once. */
    private val LINEAR = FloatArray(256) { (it / 255f).pow(2.2f) }

    /**
     * Grid search over the controls that dominate tone. Every candidate is
     * evaluated against the real 256-bin luma histogram with the exact same
     * curve the renderer applies, then scored against photographic targets.
     *
     * Exposure LUTs are pre-computed per EV level so the ~7000 candidates only
     * do multiply/add work: pow() is by far the most expensive operation and
     * used to make this noticeably janky on a phone.
     */
    private fun solveTone(stats: ImageStats): Delta {
        val hist = stats.lumaHistogram
        val exposures = floatArrayOf(-0.6f, -0.3f, 0f, 0.3f, 0.6f, 0.9f, 1.2f, 1.5f)
        // Lifting shadows is the right move for dark frames; negative values
        // would crush blacks just to widen the histogram artificially.
        val shadows = floatArrayOf(0f, 18f, 36f, 55f, 75f)
        val highlights = floatArrayOf(-35f, -16f, 0f, 14f)
        val contrasts = floatArrayOf(-10f, 0f, 12f, 26f, 42f)
        val whites = floatArrayOf(0f, 14f, 28f)
        val blacks = floatArrayOf(-10f, 0f, 10f)

        val exposedLuts = Array(exposures.size) { e ->
            val scale = 2f.pow(exposures[e])
            FloatArray(256) { i -> (LINEAR[i] * scale).pow(1f / 2.2f) }
        }

        var best = Delta()
        var bestScore = Float.MAX_VALUE
        for ((ei, ev) in exposures.withIndex()) {
            val exposed = exposedLuts[ei]
            for (sh in shadows) {
                for (hi in highlights) {
                    for (ct in contrasts) {
                        for (wh in whites) {
                            for (bl in blacks) {
                                val candidate = Delta(ev, ct, hi, sh, wh, bl)
                                val score = scoreTone(hist, candidate, exposed)
                                if (score < bestScore) {
                                    bestScore = score
                                    best = candidate
                                }
                            }
                        }
                    }
                }
            }
        }
        return best
    }

    private fun scoreTone(hist: IntArray, d: Delta, exposed: FloatArray): Float {
        val mapped = IntArray(256)
        for (i in 0 until 256) {
            val y = (toneTail(exposed[i], d) * 255f).toInt().coerceIn(0, 255)
            mapped[y] += hist[i]
        }
        val total = hist.sum().toFloat().coerceAtLeast(1f)
        var mean = 0f
        var acc = 0
        var p5 = 0f
        var p95 = 1f
        val p5Target = (total * 0.05f).toInt()
        val p95Target = (total * 0.95f).toInt()
        for (i in 0 until 256) {
            val count = mapped[i]
            mean += i / 255f * count
            acc += count
            if (p5 == 0f && acc >= p5Target) p5 = i / 255f
            if (p95 == 1f && acc >= p95Target) p95 = i / 255f
        }
        mean /= total
        val lowClip = (mapped[0] + mapped[1] + mapped[2]) / total
        val highClip = (mapped[253] + mapped[254] + mapped[255]) / total

        // Targets: mid grey 0.47, open-but-deep blacks, bright-but-safe whites.
        // Lower weight on the percentiles so the solver prioritises correct
        // exposure instead of stretching both ends at the cost of clipping.
        var score = 9f * sq(mean - 0.47f) +
            1.3f * sq(p5 - 0.10f) +
            1.3f * sq(p95 - 0.92f) +
            5f * max(0f, highClip - 0.012f) +
            4f * max(0f, lowClip - 0.02f)
        // Regulariser: prefer gentle corrections when two candidates tie.
        score += 0.00010f * (d.exposure * d.exposure * 6f +
            d.shadows * d.shadows * 0.10f +
            d.highlights * d.highlights * 0.10f +
            d.contrast * d.contrast * 0.10f +
            d.whites * d.whites * 0.10f +
            d.blacks * d.blacks * 0.10f)
        return score
    }

    /** Exposure is already baked into [x]; everything after it is pure arithmetic. */
    private fun toneTail(x0: Float, d: Delta): Float {
        var x = x0
        x = contrastCurve(x, d.contrast / 100f * 0.75f)
        if (abs(d.highlights) > 0.001f) {
            x += d.highlights / 100f * smoothstep(0.48f, 1f, x) * 0.24f
        }
        if (abs(d.shadows) > 0.001f) {
            x += d.shadows / 100f * (1f - smoothstep(0f, 0.52f, x)) * 0.24f
        }
        if (abs(d.whites) > 0.001f) {
            x += d.whites / 100f * smoothstep(0.70f, 1f, x) * 0.18f
        }
        if (abs(d.blacks) > 0.001f) {
            x += d.blacks / 100f * (1f - smoothstep(0f, 0.30f, x)) * 0.18f
        }
        return x.coerceIn(0f, 1f)
    }

    private fun contrastCurve(x: Float, amount: Float): Float {
        if (abs(amount) < 0.001f) return x
        val s = x * x * (3f - 2f * x)
        val target = if (amount >= 0f) s else 2f * x - s
        return (x + (target - x) * abs(amount)).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun sq(v: Float) = v * v

    /**
     * Grey-world white balance, inverted through the renderer's channel gains:
     * R=1+0.12t+0.035u, G=1+0.004t-0.065u, B=1-0.12t+0.035u.
     * A conservative 0.8 factor keeps the scene's original atmosphere.
     */
    private fun solveTemperature(stats: ImageStats): Float {
        val r = stats.meanR.coerceAtLeast(0.01f)
        val g = stats.meanG.coerceAtLeast(0.01f)
        val b = stats.meanB.coerceAtLeast(0.01f)
        val kr = g / r
        val kb = g / b
        val t = (kr - kb) / 0.24f
        return (t * 100f * 0.8f).coerceIn(-100f, 100f)
    }

    private fun solveTint(stats: ImageStats): Float {
        val r = stats.meanR.coerceAtLeast(0.01f)
        val g = stats.meanG.coerceAtLeast(0.01f)
        val b = stats.meanB.coerceAtLeast(0.01f)
        val kr = g / r
        val kb = g / b
        val kg = 1f
        val t = (kr - kb) / 0.24f
        // G - (R+B)/2 = 0.004t - 0.1u
        val u = (0.004f * t - (kg - (kr + kb) * 0.5f)) / 0.1f
        return (u * 100f * 0.8f).coerceIn(-100f, 100f)
    }

    private fun solveVibrance(stats: ImageStats): Float {
        val target = 0.28f
        return ((target - stats.meanSaturation) * 95f).coerceIn(-12f, 34f)
    }

    private fun buildSummary(
        stats: ImageStats,
        d: Delta,
        temperature: Float,
        vibrance: Float,
        changed: Boolean,
        nearlyPerfect: Boolean
    ): String {
        if (!changed) return "画面已经很均衡，未做明显改动"
        val parts = buildList {
            if (abs(d.exposure) > 0.05f) {
                add(if (d.exposure > 0) "提亮 ${"%.1f".format(d.exposure)} EV" else "压暗 ${"%.1f".format(-d.exposure)} EV")
            }
            if (d.shadows > 5f) add("提亮暗部")
            if (d.highlights < -5f) add("压回高光")
            if (d.contrast > 5f) add("增强层次")
            else if (d.contrast < -5f) add("柔化反差")
            if (abs(temperature) > 8f) add("校正白平衡")
            if (vibrance > 5f) add("提升自然饱和度")
            else if (vibrance < -5f) add("收敛过饱和")
            if (stats.detail < 0.012f) add("增强细节")
        }
        return if (parts.isEmpty() && nearlyPerfect) {
            "仅做轻微质感增强"
        } else {
            parts.take(4).joinToString(" · ")
        }
    }
}
