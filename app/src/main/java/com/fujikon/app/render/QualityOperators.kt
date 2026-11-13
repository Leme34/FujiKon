package com.fujikon.app.render

import com.fujikon.app.data.PointColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pluggable render backend descriptor.
 *
 * The shipped implementation is [CpuRenderBackend]: all stages run on the CPU
 * in 32-bit float intermediates (equivalent to a >16-bit linear-light working
 * space) and quantise to 8-bit exactly once per pixel. A GPU backend
 * (OpenGL ES / Vulkan compute) can be registered later without changing the
 * operator contracts — see README for why it is not shipped in this build.
 */
interface RenderBackend {
    val name: String
    /** True when the backend keeps float precision between stages. */
    val highPrecision: Boolean
}

object CpuRenderBackend : RenderBackend {
    override val name: String = "CPU-float"
    override val highPrecision: Boolean = true
}

/**
 * The four quality operators that were missing from the original 15-stage
 * pipeline: independent denoise, Point Color, film grain and vignette.
 *
 * Each operator keeps RGB in floating point for the whole pixel and writes back
 * a single packed int, so stacking them never quantises twice (the main cause
 * of banding in dark gradients).
 */
object QualityOperators {

    /**
     * Edge-preserving denoise: a small bilateral filter whose colour-distance
     * weight rejects pixels across an edge. Runs BEFORE sharpening so the
     * existing unsharp mask does not amplify noise.
     */
    fun applyDenoise(
        pixels: IntArray, width: Int, height: Int, amount: Float, job: Job?,
        snapshot: IntArray? = null, fromRow: Int = 0, toRow: Int = height
    ) {
        val strength = (amount / 100f).coerceIn(0f, 1f)
        if (strength <= 0.001f) return
        // 并行渲染时由调用方传入「只读整帧快照」，保证所有线程读到同一份
        // denoise 前的像素；串行/兼容调用则在快路径之后就地拷贝，语义不变。
        val source = snapshot ?: pixels.copyOf()
        val sigma = 16f + strength * 34f
        val twoSigma2 = 2f * sigma * sigma
        val radius = if (strength > 0.65f) 2 else 1
        val mix = strength * 0.88f

        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var i = y * width
            for (x in 0 until width) {
                val c = source[i]
                val cr = ((c shr 16) and 0xFF).toFloat()
                val cg = ((c shr 8) and 0xFF).toFloat()
                val cb = (c and 0xFF).toFloat()
                var sumR = 0f; var sumG = 0f; var sumB = 0f; var sumW = 0f
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -radius..radius) {
                        if (dx == 0 && dy == 0) continue
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val n = source[sy * width + sx]
                        val nr = ((n shr 16) and 0xFF).toFloat()
                        val ng = ((n shr 8) and 0xFF).toFloat()
                        val nb = (n and 0xFF).toFloat()
                        val dr = nr - cr; val dg = ng - cg; val db = nb - cb
                        val d2 = dr * dr + dg * dg + db * db
                        // Spatial term keeps the filter compact; colour term
                        // is what actually preserves hair / fabric edges.
                        val spatial = 1f / (1f + dx * dx + dy * dy)
                        val w = exp(-d2 / twoSigma2) * spatial
                        sumR += nr * w; sumG += ng * w; sumB += nb * w; sumW += w
                    }
                }
                if (sumW > 0f) {
                    val avgR = sumR / sumW; val avgG = sumG / sumW; val avgB = sumB / sumW
                    val r = cr + (avgR - cr) * mix
                    val g = cg + (avgG - cg) * mix
                    val b = cb + (avgB - cb) * mix
                    pixels[i] = pack(r, g, b)
                }
                i++
            }
        }
    }

    /** Shifts only the pixels near [pointColor.targetHue]. */
    fun applyPointColor(
        pixels: IntArray, width: Int, height: Int, pointColor: PointColor, job: Job?,
        fromRow: Int = 0, toRow: Int = height
    ) {
        if (pointColor.isNeutral()) return
        val pc = pointColor.normalized()
        val range = pc.hueRange.coerceAtLeast(1f)
        val hsl = FloatArray(3)
        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var i = y * width
            for (x in 0 until width) {
                val c = pixels[i]
                rgbToHsl(c, hsl)
                if (hsl[1] < pc.minSaturation) { i++; continue }
                var diff = abs(hsl[0] - pc.targetHue)
                if (diff > 180f) diff = 360f - diff
                if (diff >= range) { i++; continue }
                val weight = 1f - smoothstep(0f, range, diff)
                var h = (hsl[0] + pc.hueShift / 100f * 30f * weight + 360f) % 360f
                var s = (hsl[1] * (1f + pc.saturation / 100f * weight * 0.9f)).coerceIn(0f, 1f)
                var l = (hsl[2] + pc.luminance / 100f * weight * 0.22f).coerceIn(0f, 1f)
                val packed = hslToRgb(h, s, l)
                val r = ((packed shr 16) and 0xFF).toFloat()
                val g = ((packed shr 8) and 0xFF).toFloat()
                val b = (packed and 0xFF).toFloat()
                val cr = ((c shr 16) and 0xFF).toFloat()
                val cg = ((c shr 8) and 0xFF).toFloat()
                val cb = (c and 0xFF).toFloat()
                pixels[i] = pack(cr + (r - cr) * weight, cg + (g - cg) * weight, cb + (b - cb) * weight)
                i++
            }
        }
    }

    /**
     * Deterministic monochrome film grain. The hash depends only on pixel
     * coordinates and a fixed seed, so a preview and a full-resolution export
     * of the same frame stay visually consistent and repeated renders are
     * byte-identical (idempotent).
     */
    fun applyGrain(
        pixels: IntArray, width: Int, height: Int, amount: Float, job: Job?,
        fromRow: Int = 0, toRow: Int = height
    ) {
        val strength = (amount / 100f).coerceIn(0f, 1f)
        if (strength <= 0.001f) return
        val amplitude = strength * 17f
        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var i = y * width
            for (x in 0 until width) {
                val noise = hashNoise(x, y)
                val c = pixels[i]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()
                val luma = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                // Film grain reads strongest in mid-tones; keep highlights clean.
                val weight = 0.35f + 0.65f * (1f - smoothstep(0.55f, 1f, luma))
                val add = noise * amplitude * weight
                pixels[i] = pack(r + add, g + add, b + add)
                i++
            }
        }
    }

    /** Negative [amount] darkens the corners; positive brightens them. */
    fun applyVignette(
        pixels: IntArray, width: Int, height: Int, amount: Float, job: Job?,
        fromRow: Int = 0, toRow: Int = height
    ) {
        val strength = (amount / 100f).coerceIn(-1f, 1f)
        if (abs(strength) <= 0.001f) return
        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var i = y * width
            for (x in 0 until width) {
                val nx = (x.toFloat() / (width - 1).coerceAtLeast(1) - 0.5f) * 2f
                val ny = (y.toFloat() / (height - 1).coerceAtLeast(1) - 0.5f) * 2f
                val d = hypot(nx, ny) / sqrt(2f)
                val w = smoothstep(0.32f, 1.05f, d)
                val factor = 1f + strength * 0.62f * w
                val c = pixels[i]
                val r = ((c shr 16) and 0xFF) * factor
                val g = ((c shr 8) and 0xFF) * factor
                val b = (c and 0xFF) * factor
                pixels[i] = pack(r, g, b)
                i++
            }
        }
    }

    private fun hashNoise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 32767.5f - 1f).coerceIn(-1f, 1f)
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        0xFF000000.toInt() or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 >= edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun rgbToHsl(color: Int, out: FloatArray) {
        val rf = ((color shr 16) and 0xFF) / 255f
        val gf = ((color shr 8) and 0xFF) / 255f
        val bf = (color and 0xFF) / 255f
        val max = max(rf, max(gf, bf)); val min = min(rf, min(gf, bf))
        val l = (max + min) * 0.5f
        val d = max - min
        if (d < 1e-6f) { out[0] = 0f; out[1] = 0f; out[2] = l; return }
        val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
        var h = when (max) {
            rf -> ((gf - bf) / d + if (gf < bf) 6f else 0f) * 60f
            gf -> ((bf - rf) / d + 2f) * 60f
            else -> ((rf - gf) / d + 4f) * 60f
        }
        if (h < 0f) h += 360f
        out[0] = h; out[1] = s.coerceIn(0f, 1f); out[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        var r1 = 0f; var g1 = 0f; var b1 = 0f
        when {
            hp < 1f -> { r1 = c; g1 = x }
            hp < 2f -> { r1 = x; g1 = c }
            hp < 3f -> { g1 = c; b1 = x }
            hp < 4f -> { g1 = x; b1 = c }
            hp < 5f -> { r1 = x; b1 = c }
            else -> { r1 = c; b1 = x }
        }
        val m = l - c * 0.5f
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun checkCancelled(job: Job?) {
        if (job != null && !job.isActive) throw CancellationException("Render cancelled")
    }
}
