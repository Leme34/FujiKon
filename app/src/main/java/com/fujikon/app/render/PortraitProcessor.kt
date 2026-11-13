package com.fujikon.app.render

import com.fujikon.app.data.PortraitRetouch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Largest copy the retouch layers are computed on; keeps peak memory low. */
private const val PORTRAIT_WORKING_PIXELS = 2_000_000L

/**
 * 1.6.57 阶段一：可选的「人像区域」软蒙版（归一化 side×side 网格，
 * 0..1）。来自 on-device person 语义分割概率图，与 YCbCr 肤色蒙版相乘，
 * 把磨皮/美白**只作用在识别到的人体上**。为 null 时完全走既有行为
 * （全部现有调用、回归测试、手动美颜都不受影响）。
 *
 * 用归一化网格而非逐像素大图，因此预览与导出的分辨率各不相同也能共享
 * 同一份 mask，且内存开销恒定（128×128 Float = 64KB）。
 */
class PortraitRegionMask(
    val side: Int,
    val weights: FloatArray
) {
    init { require(side > 1 && weights.size == side * side) }

    /** 双线性采样，与 MlMaps.sample 一致。 */
    fun sample(u: Float, v: Float): Float {
        val fx = u.coerceIn(0f, 1f) * (side - 1)
        val fy = v.coerceIn(0f, 1f) * (side - 1)
        val x0 = fx.toInt().coerceIn(0, side - 1)
        val y0 = fy.toInt().coerceIn(0, side - 1)
        val x1 = (x0 + 1).coerceAtMost(side - 1)
        val y1 = (y0 + 1).coerceAtMost(side - 1)
        val a = weights[y0 * side + x0] + (weights[y0 * side + x1] - weights[y0 * side + x0]) * (fx - x0)
        val b = weights[y1 * side + x0] + (weights[y1 * side + x1] - weights[y1 * side + x0]) * (fx - x0)
        return (a + (b - a) * (fy - y0)).coerceIn(0f, 1f)
    }
}

/**
 * Skin-aware portrait retouch: smooth, whiten, blush and contour.
 *
 * Eyes, lips, hair and the background are protected twice: first by an
 * YCbCr skin mask, then by a local-contrast guard inside the smear radius, so
 * feature edges keep their definition. Work happens on a downscaled copy for
 * speed and memory; results are sampled back bilinearly.
 *
 * 1.6.57：[region] 非空时再乘一层人像区域蒙版，使背景里的暖色物体
 * （木船、秋叶、餐盘里的肉）不会被误当皮肤磨皮。默认 null = 原行为。
 *
 * 1.6.58：[regionFloor] 是作用域外的保留地板值。全局人像美颜沿用 0.04
 * 的软地板（避免硬边界可见轮廓，行为零变化）；「蒙版-选择主体」的局部
 * 美颜传 0，让选区外严格不受美颜——与「只作用于选区」的语义一致。
 */
internal suspend fun applyPortrait(
    pixels: IntArray,
    width: Int,
    height: Int,
    retouch: PortraitRetouch,
    job: Job?,
    region: PortraitRegionMask? = null,
    regionFloor: Float = 0.04f
) {
    if (retouch.isOff()) return
    val state = retouch.normalized()

    val scale = portraitWorkingScale(width, height)
    val sw = (width / scale).coerceAtLeast(1)
    val sh = (height / scale).coerceAtLeast(1)
    val work = if (scale == 1) pixels.copyOf() else resamplePixels(pixels, width, height, sw, sh)

    val mask = skinMask(work, sw, sh)
    featherMask(mask, sw, sh, 3)
    // 1.6.57：与人像区域蒙版相乘，把美颜限定在识别到的人体上。region
    // 是归一化网格，按 work 网格坐标双线性采样，预览/导出一致。
    if (region != null) {
        val floor = regionFloor.coerceIn(0f, 1f)
        val span = 1f - floor
        for (y in 0 until sh) {
            val v = (y + 0.5f) / sh
            for (x in 0 until sw) {
                val i = y * sw + x
                // 全局路径保留 4% 软地板避免可见的「蒙版轮廓」；局部路径
                // floor=0 时选区外严格为 0。两者共用同一段逐像素逻辑。
                mask[i] *= (floor + span * region.sample((x + 0.5f) / sw, v))
            }
        }
    }

    val smoothAmount = state.smooth / PortraitRetouch.MAX_STRENGTH
    val contourAmount = state.contour / PortraitRetouch.MAX_STRENGTH

    val baseRadius = (min(sw, sh) * (0.006f + 0.02f * smoothAmount)).roundToInt().coerceIn(1, 64)
    val smoothLayer = if (smoothAmount > 0f) blurPixels(work, sw, sh, baseRadius) else null
    val detailRadius = (baseRadius * 2).coerceIn(3, 96)
    val detailLayer = if (contourAmount > 0f) blurPixels(work, sw, sh, detailRadius) else null

    val whitenAmount = state.whiten / PortraitRetouch.MAX_STRENGTH
    val blushAmount = state.blush / PortraitRetouch.MAX_STRENGTH

    val chunk = ((height / (Runtime.getRuntime().availableProcessors() * 2)).coerceAtLeast(16))

    coroutineScope {
        (0 until height step chunk).map { from ->
            async(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                job?.ensureActive()
                blendRows(
                    pixels = pixels,
                    width = width,
                    from = from,
                    to = min(from + chunk, height),
                    scale = scale,
                    mask = mask,
                    maskWidth = sw,
                    maskHeight = sh,
                    smoothLayer = smoothLayer,
                    smoothWidth = sw,
                    smoothHeight = sh,
                    detailLayer = detailLayer,
                    smoothAmount = smoothAmount,
                    whitenAmount = whitenAmount,
                    blushAmount = blushAmount,
                    contourAmount = contourAmount
                )
            }
        }.awaitAll()
    }
}

private fun portraitWorkingScale(width: Int, height: Int): Int {
    val pixels = width.toLong() * height
    return when {
        pixels <= PORTRAIT_WORKING_PIXELS -> 1
        pixels <= PORTRAIT_WORKING_PIXELS * 4 -> 2
        else -> 4
    }
}

private fun blendRows(
    pixels: IntArray,
    width: Int,
    from: Int,
    to: Int,
    scale: Int,
    mask: FloatArray,
    maskWidth: Int,
    maskHeight: Int,
    smoothLayer: IntArray?,
    smoothWidth: Int,
    smoothHeight: Int,
    detailLayer: IntArray?,
    smoothAmount: Float,
    whitenAmount: Float,
    blushAmount: Float,
    contourAmount: Float
) {
    for (y in from until to) {
        val fy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, (maskHeight - 1).toFloat())
        for (x in 0 until width) {
            val fx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, (maskWidth - 1).toFloat())
            val skin = sampleFloat(mask, maskWidth, maskHeight, fx, fy)
            if (skin <= 0.015f) continue

            val index = y * width + x
            val original = pixels[index]
            val originalLuma = lumaOf(original)
            var r = ((original shr 16) and 0xFF).toFloat()
            var g = ((original shr 8) and 0xFF).toFloat()
            var b = (original and 0xFF).toFloat()

            if (smoothLayer != null && smoothAmount > 0f) {
                val smooth = samplePixel(smoothLayer, smoothWidth, smoothHeight, fx, fy)
                // Strong local contrast marks an edge (lash, lip line, hair)
                // and is left alone so the face does not turn to plastic.
                val edge = 1f - smoothstep(10f, 42f, abs(originalLuma - lumaOf(smooth)))
                val amount = skin * smoothAmount * edge * 0.9f
                r = mix(r, ((smooth shr 16) and 0xFF).toFloat(), amount)
                g = mix(g, ((smooth shr 8) and 0xFF).toFloat(), amount)
                b = mix(b, (smooth and 0xFF).toFloat(), amount)
            }

            if (whitenAmount > 0f) {
                val amount = skin * whitenAmount
                r = (r + (255 - r) * 0.22f * amount)
                g = (g + (255 - g) * 0.20f * amount)
                b = (b + (255 - b) * 0.25f * amount)
            }

            if (blushAmount > 0f) {
                val amount = skin * blushAmount
                r = (r + 16f * amount).coerceAtMost(255f)
                g = (g - 3f * amount).coerceAtLeast(0f)
                b = (b - 8f * amount).coerceAtLeast(0f)
            }

            if (detailLayer != null && contourAmount > 0f) {
                val detail = samplePixel(detailLayer, smoothWidth, smoothHeight, fx, fy)
                val delta = (originalLuma - lumaOf(detail)) * contourAmount * 1.2f * skin
                val clamped = delta.coerceIn(-24f, 24f)
                r = (r + clamped).coerceIn(0f, 255f)
                g = (g + clamped).coerceIn(0f, 255f)
                b = (b + clamped).coerceIn(0f, 255f)
            }

            pixels[index] = argb(r, g, b)
        }
    }
}

/** Builds a soft 0..1 skin weight from YCbCr, the classic skin-tone window. */
private fun skinMask(pixels: IntArray, width: Int, height: Int): FloatArray {
    val mask = FloatArray(pixels.size)
    for (i in pixels.indices) {
        val px = pixels[i]
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

        val cbWeight = bandWeight(cb, 77f, 127f, 14f)
        val crWeight = bandWeight(cr, 133f, 173f, 14f)
        val lumWeight = bandWeight(y, 28f, 246f, 16f)
        mask[i] = cbWeight * crWeight * lumWeight
    }
    return mask
}

/** 1 inside [low, high], fading to 0 over [fade] units on either side. */
private fun bandWeight(value: Float, low: Float, high: Float, fade: Float): Float =
    smoothstep(low - fade, low, value) * (1f - smoothstep(high, high + fade, value))

/** Softens the mask edges so retouch does not end in a visible skin outline. */
private fun featherMask(mask: FloatArray, width: Int, height: Int, radius: Int) {
    if (radius <= 0) return
    val temp = FloatArray(mask.size)
    val window = radius * 2 + 1

    for (y in 0 until height) {
        var sum = 0f
        for (i in -radius..radius) sum += mask[y * width + i.coerceIn(0, width - 1)]
        for (x in 0 until width) {
            temp[y * width + x] = sum / window
            sum -= mask[y * width + (x - radius).coerceIn(0, width - 1)]
            sum += mask[y * width + (x + radius + 1).coerceIn(0, width - 1)]
        }
    }

    for (x in 0 until width) {
        var sum = 0f
        for (i in -radius..radius) sum += temp[i.coerceIn(0, height - 1) * width + x]
        for (y in 0 until height) {
            mask[y * width + x] = sum / window
            sum -= temp[(y - radius).coerceIn(0, height - 1) * width + x]
            sum += temp[(y + radius + 1).coerceIn(0, height - 1) * width + x]
        }
    }
}

/** O(n) separable box blur used as a cheap low-pass for smoothing and contour. */
private fun blurPixels(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
    val r = radius.coerceAtLeast(1)
    val temp = IntArray(source.size)
    val result = IntArray(source.size)
    val window = r * 2 + 1

    for (y in 0 until height) {
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val px = source[y * width + i.coerceIn(0, width - 1)]
            sr += (px shr 16) and 0xFF
            sg += (px shr 8) and 0xFF
            sb += px and 0xFF
        }
        for (x in 0 until width) {
            temp[y * width + x] = argb((sr / window).toFloat(), (sg / window).toFloat(), (sb / window).toFloat())
            val out = source[y * width + (x - r).coerceIn(0, width - 1)]
            sr -= (out shr 16) and 0xFF
            sg -= (out shr 8) and 0xFF
            sb -= out and 0xFF
            val inn = source[y * width + (x + r + 1).coerceIn(0, width - 1)]
            sr += (inn shr 16) and 0xFF
            sg += (inn shr 8) and 0xFF
            sb += inn and 0xFF
        }
    }

    for (x in 0 until width) {
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val px = temp[i.coerceIn(0, height - 1) * width + x]
            sr += (px shr 16) and 0xFF
            sg += (px shr 8) and 0xFF
            sb += px and 0xFF
        }
        for (y in 0 until height) {
            result[y * width + x] = argb((sr / window).toFloat(), (sg / window).toFloat(), (sb / window).toFloat())
            val out = temp[(y - r).coerceIn(0, height - 1) * width + x]
            sr -= (out shr 16) and 0xFF
            sg -= (out shr 8) and 0xFF
            sb -= out and 0xFF
            val inn = temp[(y + r + 1).coerceIn(0, height - 1) * width + x]
            sr += (inn shr 16) and 0xFF
            sg += (inn shr 8) and 0xFF
            sb += inn and 0xFF
        }
    }

    return result
}

private fun resamplePixels(source: IntArray, width: Int, height: Int, sw: Int, sh: Int): IntArray {
    val out = IntArray(sw * sh)
    for (y in 0 until sh) {
        val fy = ((y + 0.5f) * height / sh - 0.5f).coerceIn(0f, (height - 1).toFloat())
        for (x in 0 until sw) {
            val fx = ((x + 0.5f) * width / sw - 0.5f).coerceIn(0f, (width - 1).toFloat())
            out[y * sw + x] = samplePixel(source, width, height, fx, fy)
        }
    }
    return out
}

private fun samplePixel(source: IntArray, width: Int, height: Int, fx: Float, fy: Float): Int {
    val x0 = fx.toInt().coerceIn(0, width - 1)
    val y0 = fy.toInt().coerceIn(0, height - 1)
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val wx = fx - x0
    val wy = fy - y0
    val top = mixPixels(source[y0 * width + x0], source[y0 * width + x1], wx)
    val bottom = mixPixels(source[y1 * width + x0], source[y1 * width + x1], wx)
    return mixPixels(top, bottom, wy)
}

private fun sampleFloat(values: FloatArray, width: Int, height: Int, fx: Float, fy: Float): Float {
    val x0 = fx.toInt().coerceIn(0, width - 1)
    val y0 = fy.toInt().coerceIn(0, height - 1)
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val wx = fx - x0
    val wy = fy - y0
    val top = values[y0 * width + x0] * (1f - wx) + values[y0 * width + x1] * wx
    val bottom = values[y1 * width + x0] * (1f - wx) + values[y1 * width + x1] * wx
    return top * (1f - wy) + bottom * wy
}

private fun mixPixels(a: Int, b: Int, amount: Float): Int {
    if (amount <= 0f) return a
    if (amount >= 1f) return b
    val r = mix(((a shr 16) and 0xFF).toFloat(), ((b shr 16) and 0xFF).toFloat(), amount)
    val g = mix(((a shr 8) and 0xFF).toFloat(), ((b shr 8) and 0xFF).toFloat(), amount)
    val bl = mix((a and 0xFF).toFloat(), (b and 0xFF).toFloat(), amount)
    return argb(r, g, bl)
}

private fun mix(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

private fun argb(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or
        (r.coerceIn(0f, 255f).roundToInt() shl 16) or
        (g.coerceIn(0f, 255f).roundToInt() shl 8) or
        b.coerceIn(0f, 255f).roundToInt()

private fun lumaOf(color: Int): Float {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return 0.299f * r + 0.587f * g + 0.114f * b
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value < edge0) 0f else 1f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
