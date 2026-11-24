package com.fujikon.app.render

import com.fujikon.app.data.PointColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.exp
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
     * 1.6.63 性能：把每像素重复的 smoothstep 查表化。grain 的影调权重只依赖
     * 0..255 亮度，vignette 的平滑权重只依赖 0..255 归一化距离，二者都可在
     * 进入像素循环前一次性算好（256 桶，0.25 灰阶精度，量化不可见）。
     */
    private const val LUT_SIZE = 256
    private val GRAIN_WEIGHT_LUT = FloatArray(LUT_SIZE) { i ->
        val luma = i / (LUT_SIZE - 1).toFloat()
        0.35f + 0.65f * (1f - smoothstep(0.55f, 1f, luma))
    }
    private val VIGNETTE_WEIGHT_LUT = FloatArray(LUT_SIZE) { i ->
        smoothstep(0.32f, 1.05f, i / (LUT_SIZE - 1).toFloat())
    }
    /** 1.6.82：闪光距离/高光保护表提升为共享常量，合并 pass 与独立 pass 完全同源。 */
    private val FLASH_WEIGHT_LUT = FloatArray(LUT_SIZE) { i ->
        val d = i / (LUT_SIZE - 1).toFloat()
        (1f - d * d * sqrt(d)).coerceIn(0f, 1f)
    }
    private val FLASH_GUARD_LUT = FloatArray(LUT_SIZE) { i ->
        1f - smoothstep(0.68f, 0.92f, i / (LUT_SIZE - 1).toFloat())
    }

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
                // 影调权重只依赖亮度，查表取代每像素 smoothstep。
                val lumaIdx = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
                    .coerceIn(0, LUT_SIZE - 1)
                val add = noise * amplitude * GRAIN_WEIGHT_LUT[lumaIdx]
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
        // 归一化坐标只依赖 x/y，每行/每列各算一次；距离与 smoothstep 合并查表，
        // 省掉每像素 hypot + sqrt + smoothstep。d∈[0,1] 直接映射 256 桶。
        val invW = 2f / (width - 1).coerceAtLeast(1)
        val invH = 2f / (height - 1).coerceAtLeast(1)
        val invSqrt2 = 1f / sqrt(2f)
        val colNx = FloatArray(width) { x -> (x * invW - 1f) }
        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            val ny = y * invH - 1f
            var i = y * width
            for (x in 0 until width) {
                val d = sqrt(colNx[x] * colNx[x] + ny * ny) * invSqrt2
                val w = VIGNETTE_WEIGHT_LUT[(d * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]
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

    /**
     * 1.6.82 性能合并：把闪光 → 颗粒 → 暗角三个逐像素 pass 合并为一趟，
     * 只读写一次整帧像素数组，省掉两轮全帧内存带宽（大图导出收益明显）。
     *
     * 逐像素严格模拟原先三次独立调用：每步都按原顺序取整并钳制到 0..255，
     * 后一步读取的是前一步取整后的整数值，因此输出结果与原三趟完全一致，
     * 画质无损。任一项为 0 时对应分支直接跳过，与独立算子的早退一致。
     */
    fun applyFinish(
        pixels: IntArray, width: Int, height: Int,
        flash: Float, grain: Float, vignette: Float, job: Job?,
        fromRow: Int = 0, toRow: Int = height
    ) {
        val flashStrength = (flash / 100f).coerceIn(0f, 1f)
        val grainStrength = (grain / 100f).coerceIn(0f, 1f)
        val vigStrength = (vignette / 100f).coerceIn(-1f, 1f)
        if (flashStrength <= 0.001f && grainStrength <= 0.001f && abs(vigStrength) <= 0.001f) return

        // 分支条件在循环外固定，热循环里只做整数通道运算，避免逐像素 abs /
        // float↔int 往返（这是合并 pass 初版偏慢的主因）。
        val doFlash = flashStrength > 0.001f
        val doGrain = grainStrength > 0.001f
        val doVignette = abs(vigStrength) > 0.001f
        val grainAmp = grainStrength * 17f
        val invW = 2f / (width - 1).coerceAtLeast(1)
        val invH = 2f / (height - 1).coerceAtLeast(1)
        val invSqrt2 = 1f / sqrt(2f)
        val colNx = FloatArray(width) { x -> (x * invW - 1f) }

        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            val ny = y * invH - 1f
            val flashDy = ny - 0.35f
            var i = y * width
            for (x in 0 until width) {
                val c = pixels[i]
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF

                // 1) 闪光：亮度桶取闪光前的整数通道，与独立算子一致。
                if (doFlash) {
                    val nx = colNx[x]
                    val d = (sqrt(nx * nx + flashDy * flashDy) * invSqrt2).coerceIn(0f, 1f)
                    val w = FLASH_WEIGHT_LUT[(d * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]
                    val lumaBucket = ((r * 77 + g * 150 + b * 29) shr 8).coerceIn(0, LUT_SIZE - 1)
                    val factor = 1f + flashStrength *
                        (0.30f * w * FLASH_GUARD_LUT[lumaBucket] - 0.18f * (1f - w))
                    r = (r * factor).roundToInt().coerceIn(0, 255)
                    g = (g * factor).roundToInt().coerceIn(0, 255)
                    b = (b * factor).roundToInt().coerceIn(0, 255)
                }

                // 2) 颗粒：亮度权重取闪光后已取整的通道。
                if (doGrain) {
                    val add = hashNoise(x, y) * grainAmp *
                        GRAIN_WEIGHT_LUT[(r * 0.299f + g * 0.587f + b * 0.114f)
                            .toInt().coerceIn(0, LUT_SIZE - 1)]
                    r = (r + add).roundToInt().coerceIn(0, 255)
                    g = (g + add).roundToInt().coerceIn(0, 255)
                    b = (b + add).roundToInt().coerceIn(0, 255)
                }

                // 3) 暗角：只依赖坐标，乘算后取整钳制。
                if (doVignette) {
                    val nx = colNx[x]
                    val d = sqrt(nx * nx + ny * ny) * invSqrt2
                    val w = VIGNETTE_WEIGHT_LUT[(d * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]
                    val factor = 1f + vigStrength * 0.62f * w
                    r = (r * factor).roundToInt().coerceIn(0, 255)
                    g = (g * factor).roundToInt().coerceIn(0, 255)
                    b = (b * factor).roundToInt().coerceIn(0, 255)
                }

                pixels[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                i++
            }
        }
    }

    /**
     * 全局闪光照明的亮度增益系数（纯函数，便于单测）。
     * [nx],[ny] 为 -1..1 的归一化坐标，原点在画面中心、ny 向上为正；
     * [strength] 为 0..1 的闪光强度。[luma] 为该像素归一化亮度（0..1），用于
     * 高光保护：亮度超过 0.68 后提亮增益平滑衰减，到 0.92 完全不再提亮，
     * 避免本就明亮的高光区（皮肤反光、背景亮部）被闪光乘法推到 255 死白。
     * 闪光中心上移到 y=+0.35（人像面部通常在上半部），中心最多提亮 +30%、
     * 边缘最多压暗 -18%，中间按 1.6 次幂平滑衰减——形成「主体被打亮、
     * 背景随距离衰减」的真实机顶直闪光感。默认 [luma]=0 表示无高光保护，
     * 兼容只考察空间光照分布的调用。
     */
    fun flashFactor(nx: Float, ny: Float, strength: Float, luma: Float = 0f): Float {
        if (strength <= 0.001f) return 1f
        val dy = ny - 0.35f
        val d = (sqrt(nx * nx + dy * dy) / sqrt(2f)).coerceIn(0f, 1f)
        val w = (1f - d * d * sqrt(d)).coerceIn(0f, 1f) // 1.6 次幂近似：d²·√d
        // 高光保护：只衰减「提亮」正项，边缘「压暗」负项保留。
        val highlightGuard = 1f - smoothstep(0.68f, 0.92f, luma)
        return 1f + strength * (0.30f * w * highlightGuard - 0.18f * (1f - w))
    }

    /**
     * 机顶闪光灯的径向光照：中心提亮、四周衰减，并对高光做保护——亮度越高
     * 闪光提亮越弱（0.68→0.92 平滑归零），避免高光区乘以增益后溢出死白，
     * 保留亮部层次。只依赖像素坐标与亮度，可安全按行并行；每像素两次查表
     * （距离 LUT + 高光保护 LUT）和一次乘加，成本与 vignette 相当。
     * [amount] 为 0..100，0 时早退、完全不改变画面。
     */
    fun applyFlash(
        pixels: IntArray, width: Int, height: Int, amount: Float, job: Job?,
        fromRow: Int = 0, toRow: Int = height
    ) {
        val strength = (amount / 100f).coerceIn(0f, 1f)
        if (strength <= 0.001f) return
        // d∈[0,1] → 闪光权重 w∈[0,1] 与高光保护系数均已预计算为共享 LUT。
        val wLut = FLASH_WEIGHT_LUT
        val guardLut = FLASH_GUARD_LUT
        val invW = 2f / (width - 1).coerceAtLeast(1)
        val invH = 2f / (height - 1).coerceAtLeast(1)
        val invSqrt2 = 1f / sqrt(2f)
        val colNx = FloatArray(width) { x -> (x * invW - 1f) }
        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            val ny = y * invH - 1f
            val dy = ny - 0.35f
            var i = y * width
            for (x in 0 until width) {
                val nx = colNx[x]
                val d = (sqrt(nx * nx + dy * dy) * invSqrt2).coerceIn(0f, 1f)
                val w = wLut[(d * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)]
                val c = pixels[i]
                val r8 = (c shr 16) and 0xFF
                val g8 = (c shr 8) and 0xFF
                val b8 = c and 0xFF
                // 整数近似亮度 (0.299R+0.587G+0.114B)*256，避免每像素浮点除法。
                val lumaBucket = ((r8 * 77 + g8 * 150 + b8 * 29) shr 8).coerceIn(0, LUT_SIZE - 1)
                val lift = 0.30f * w * guardLut[lumaBucket]
                val fall = 0.18f * (1f - w)
                val factor = 1f + strength * (lift - fall)
                pixels[i] = pack(r8 * factor, g8 * factor, b8 * factor)
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
