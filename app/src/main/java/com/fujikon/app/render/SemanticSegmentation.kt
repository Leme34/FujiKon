package com.fujikon.app.render

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SemanticRegion { SKY, FOLIAGE, SKIN, SUBJECT }

/**
 * Low-resolution soft masks plus the centroid / coverage of each region.
 * Coordinates and centroids are normalised to the frame.
 */
class SemanticMasks(
    val side: Int,
    val sky: FloatArray,
    val foliage: FloatArray,
    val skin: FloatArray,
    val subject: FloatArray
) {
    fun coverage(region: SemanticRegion): Float {
        val values = values(region)
        var sum = 0f
        for (v in values) sum += v
        return sum / values.size
    }

    fun centroid(region: SemanticRegion): Pair<Float, Float> {
        val values = values(region)
        var sx = 0f; var sy = 0f; var sw = 0f
        for (y in 0 until side) {
            for (x in 0 until side) {
                val w = values[y * side + x]
                sx += x * w; sy += y * w; sw += w
            }
        }
        if (sw < 1e-5f) return 0.5f to 0.5f
        return (sx / sw / (side - 1)) to (sy / sw / (side - 1))
    }

    fun values(region: SemanticRegion): FloatArray = when (region) {
        SemanticRegion.SKY -> sky
        SemanticRegion.FOLIAGE -> foliage
        SemanticRegion.SKIN -> skin
        SemanticRegion.SUBJECT -> subject
    }
}

/**
 * Contract for a zone-segmentation provider.
 *
 * Implementations may be a real on-device model (TFLite / ONNX Runtime) or a
 * lightweight heuristic. Callers must surface [isModelBased] and [label] in the
 * UI so a heuristic result is never presented as an AI segmentation.
 */
interface SegmentationProvider {
    val label: String
    val isModelBased: Boolean
    fun segment(source: Bitmap?, stats: ImageStats?): SemanticMasks?
}

/**
 * **Heuristic, NOT an AI model.** Infers soft sky / foliage / skin / subject
 * masks from per-pixel colour, luminance and position only — no neural network,
 * no model file, no network access, no extra memory beyond a 96x96 grid.
 *
 * This is the offline, zero-compute-cost provider used by zoned auto-enhance.
 * A model-based provider can be dropped in via [SegmentationProvider] without
 * changing [AutoEnhance.suggestZoned] or the renderer.
 */
object HeuristicSegmentationProvider : SegmentationProvider {
    override val label: String = "本机色彩/位置启发式分区（非 AI 模型）"
    override val isModelBased: Boolean = false

    private const val SIDE = 96

    override fun segment(source: Bitmap?, stats: ImageStats?): SemanticMasks? {
        if (source == null || source.width < 2 || source.height < 2 || source.isRecycled) return null
        val w = source.width
        val h = source.height
        val sky = FloatArray(SIDE * SIDE)
        val foliage = FloatArray(SIDE * SIDE)
        val skin = FloatArray(SIDE * SIDE)
        val subject = FloatArray(SIDE * SIDE)
        val row = IntArray(w)

        for (gy in 0 until SIDE) {
            val sy = ((gy + 0.5f) / SIDE * h).toInt().coerceIn(0, h - 1)
            source.getPixels(row, 0, w, 0, sy, w, 1)
            for (gx in 0 until SIDE) {
                // Average a small horizontal span so a single noisy pixel
                // cannot decide a region.
                val sx = ((gx + 0.5f) / SIDE * w).toInt().coerceIn(0, w - 1)
                var r = 0f; var g = 0f; var b = 0f; var n = 0
                for (k in -1..1) {
                    val px = (sx + k).coerceIn(0, w - 1)
                    val c = row[px]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                r /= n; g /= n; b /= n
                val ny = gy.toFloat() / (SIDE - 1)
                val nx = gx.toFloat() / (SIDE - 1)
                val luma = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
                val sat = if (maxC < 1f) 0f else (maxC - minC) / maxC

                // Sky: upper frame, blue-dominant or bright neutral, low texture.
                val blueish = (b - r) / 255f
                val upper = 1f - smoothstep(0.35f, 0.85f, ny)
                val skyScore = when {
                    blueish > 0.02f && luma > 0.32f -> upper * (0.55f + blueish * 2f).coerceIn(0f, 1f)
                    sat < 0.12f && luma > 0.68f -> upper * 0.55f
                    else -> 0f
                }
                sky[gy * SIDE + gx] = skyScore.coerceIn(0f, 1f)

                // Foliage: green-dominant with meaningful chroma.
                val green = (g - (r + b) * 0.5f) / 255f
                foliage[gy * SIDE + gx] = if (green > 0.015f && sat > 0.10f && luma > 0.08f) {
                    (green * 6f).coerceIn(0f, 1f) * smoothstep(0.10f, 0.28f, sat)
                } else 0f

                // Skin: warm, mid-luma, moderate chroma (YCbCr-like rule).
                val warm = (r - b) / 255f
                skin[gy * SIDE + gx] = if (warm in 0.06f..0.36f && g > b && luma > 0.18f && luma < 0.92f) {
                    smoothstep(0.06f, 0.14f, warm) * (1f - smoothstep(0.28f, 0.40f, warm))
                } else 0f

                // Subject: centre-weighted saliency. This is a compositional
                // prior, not object detection — documented as such on purpose.
                val d = hypot((nx - 0.5f) * 1.5f, (ny - 0.52f) * 1.7f)
                subject[gy * SIDE + gx] = (1f - smoothstep(0.18f, 0.72f, d)).coerceIn(0f, 1f)
            }
        }

        smooth(sky); smooth(foliage); smooth(skin)
        return SemanticMasks(SIDE, sky, foliage, skin, subject)
    }

    private fun smooth(values: FloatArray) {
        val temp = values.copyOf()
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                var sum = 0f; var count = 0
                for (k in -1..1) {
                    val sx = (x + k).coerceIn(0, SIDE - 1)
                    sum += temp[y * SIDE + sx]; count++
                }
                values[y * SIDE + x] = sum / count
            }
        }
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                var sum = 0f; var count = 0
                for (k in -1..1) {
                    val sy = (y + k).coerceIn(0, SIDE - 1)
                    sum += temp[sy * SIDE + x]; count++
                }
                values[y * SIDE + x] = (values[y * SIDE + x] + sum / count) * 0.5f
            }
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 >= edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
