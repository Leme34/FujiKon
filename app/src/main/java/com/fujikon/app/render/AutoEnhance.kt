package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
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
    val neutralCoverage: Float = 0f
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
 * The PRIMARY tool is a single master tone [ToneCurve] derived from the
 * measured 256-bin luma histogram. The curve — applied exactly like
 * [ImageRenderer] applies `preset.toneCurve` — lifts crushed shadows (fixing
 * 死黑), gently rolls off blown highlights (fixing 死白) and adds a small
 * S-shape for 色彩层次 / 光感. Because every amount is scaled by the *actual*
 * deficiency, a well-exposed frame gets a near-identity curve and is left
 * alone, instead of being hammered by linear exposure + contrast sliders.
 *
 * Exposure / contrast are only a tiny SECONDARY correction when the curve alone
 * cannot reach photographic mid-grey, and they are hard-capped so the engine
 * can never "destroy" an already-good photo the way over-driven exposure did.
 * White balance uses the neutral-anchor method (no correction without a
 * trustworthy grey reference), and vibrance / clarity / dehaze follow the stats.
 *
 * The output is a plain [EditAdjustments] (now carrying a [ToneCurve]), so the
 * result stays non-destructive, undoable, editable with the manual sliders and
 * shared by preview and export exactly like any other adjustment.
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
            neutralCoverage = neutralCoverage
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

        // ---- PRIMARY: a master tone curve built from the histogram ----------
        val curve = solveToneCurve(stats)
        // ---- SECONDARY: a tiny exposure nudge so the curve can reach mid-grey
        var exposure = solveExposure(stats, curve)
        val predicted = predictStats(stats.lumaHistogram, adjustmentLut(exposure, curve))
        // Safety net: never introduce heavy clipping while brightening.
        if (predicted.highClip > 0.03f) exposure *= 0.6f
        if (predicted.lowClip > 0.05f) exposure *= 0.6f
        // A touch of contrast only if the curve alone left the frame flat.
        val contrast = when {
            predicted.spread < 0.25f -> 55f
            predicted.spread < 0.40f -> 34f
            else -> 0f
        }

        // ---- Colour / detail, all modest -----------------------------------
        val temperature = solveTemperature(stats)
        val tint = solveTint(stats)
        val vibrance = solveVibrance(stats)
        val clarity = if (stats.detail < 0.012f) 18f else if (stats.detail < 0.020f) 10f else 4f
        val texture = if (stats.detail < 0.012f) 12f else if (stats.detail < 0.020f) 6f else 0f
        val hazy = stats.spread < 0.52f && stats.meanLuma > 0.50f && stats.meanSaturation < 0.20f
        val dehaze = if (hazy) 16f else if (stats.spread < 0.42f) 8f else 0f

        val next = current.copy(
            exposure = current.exposure + exposure,
            contrast = current.contrast + contrast,
            temperature = current.temperature + temperature,
            tint = current.tint + tint,
            vibrance = current.vibrance + vibrance,
            clarity = current.clarity + clarity,
            texture = current.texture + texture,
            dehaze = current.dehaze + dehaze,
            toneCurve = if (curve.isLinear()) null else (current.toneCurve ?: curve)
        ).normalized()

        val changed = next != current
        return AutoEnhanceResult(
            adjustments = next,
            findings = findings,
            summary = buildSummary(stats, curve, exposure, contrast, temperature, vibrance, changed, nearlyPerfect),
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
    private fun solveToneCurve(stats: ImageStats): ToneCurve {
        val shadowDeficit = max(0f, 0.10f - stats.p5)
        val highExcess = max(0f, stats.p95 - 0.92f)
        val toe = (2.2f * shadowDeficit + 1.6f * stats.shadowClip).coerceIn(0f, 0.07f)
        val roll = (2.0f * highExcess + 1.8f * stats.highlightClip).coerceIn(0f, 0.09f)
        val s = if (stats.spread < 0.55f) (0.18f * (0.55f - stats.spread)).coerceIn(0f, 0.12f) else 0f

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
                // +at quarter-tones, −at three-quarter-tones => gentle S.
                y += (s * sin(2.0 * PI * x.toDouble())).toFloat()
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
     * White balance is solved from the **neutral anchor** only: the average colour
     * of near-neutral pixels, inverted through the renderer's channel gains
     * R=1+0.12t+0.035u, G=1+0.004t-0.065u, B=1-0.12t+0.035u.
     *
     * Crucially, if the scene has almost no neutral pixels (a pure blue sky, a
     * colourful memory-colour frame), there is no trustworthy white reference, so
     * we return 0 and leave the colour exactly as shot — grey-world would otherwise
     * drag that blue sky toward orange, which is the colour distortion users hate.
     * Correction is also capped at ±60 and damped on saturated frames.
     */
    private fun wbFactor(stats: ImageStats): Float =
        0.7f * (1f - 0.4f * ((stats.meanSaturation - 0.30f) / 0.30f).coerceIn(0f, 1f))

    private fun solveTemperature(stats: ImageStats): Float {
        if (stats.neutralCoverage < 0.06f) return 0f
        val r = stats.neutralR.coerceAtLeast(0.01f)
        val g = stats.neutralG.coerceAtLeast(0.01f)
        val b = stats.neutralB.coerceAtLeast(0.01f)
        val kr = g / r
        val kb = g / b
        val t = (kr - kb) / 0.24f
        return (t * 100f * wbFactor(stats)).coerceIn(-60f, 60f)
    }

    private fun solveTint(stats: ImageStats): Float {
        if (stats.neutralCoverage < 0.06f) return 0f
        val r = stats.neutralR.coerceAtLeast(0.01f)
        val g = stats.neutralG.coerceAtLeast(0.01f)
        val b = stats.neutralB.coerceAtLeast(0.01f)
        val kr = g / r
        val kb = g / b
        val kg = 1f
        val t = (kr - kb) / 0.24f
        // G - (R+B)/2 = 0.004t - 0.1u
        val u = (0.004f * t - (kg - (kr + kb) * 0.5f)) / 0.1f
        return (u * 100f * wbFactor(stats)).coerceIn(-60f, 60f)
    }

    private fun solveVibrance(stats: ImageStats): Float {
        val target = 0.28f
        return ((target - stats.meanSaturation) * 95f).coerceIn(-12f, 34f)
    }

    private fun buildSummary(
        stats: ImageStats,
        curve: ToneCurve,
        exposure: Float,
        contrast: Float,
        temperature: Float,
        vibrance: Float,
        changed: Boolean,
        nearlyPerfect: Boolean
    ): String {
        if (!changed) return "画面已经很均衡，未做明显改动"
        val usesCurve = !curve.isLinear()
        val parts = buildList {
            if (usesCurve) add("曲线优化光感与层次")
            if (abs(exposure) > 0.05f) {
                add(if (exposure > 0) "提亮 ${"%.1f".format(exposure)} EV" else "压暗 ${"%.1f".format(-exposure)} EV")
            }
            if (contrast > 5f) add("增强层次")
            else if (contrast < -5f) add("柔化反差")
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
