package com.fujikon.app.render

import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.EraserStroke
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.PortraitRetouch
import com.fujikon.app.data.isContentAware
import com.fujikon.app.data.isInverted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rasterises the four local-adjustment selection shapes into low-resolution
 * float grids and blends zone-scoped parameters back onto the image.
 *
 * Masks are built on a grid whose longest side is at most [GRID_SIDE] and then
 * sampled bilinearly per pixel. That keeps memory bounded (~1 MB per layer even
 * for a 36 MP export) and guarantees preview and export see the *same* mask,
 * because all coordinates are normalised to the frame.
 */
object LocalMaskRenderer {

    private const val GRID_SIDE = 384

    class MaskGrid(val width: Int, val height: Int, val values: FloatArray) {
        /** Bilinear sample with normalised coordinates. */
        fun sample(x: Float, y: Float): Float {
            val fx = (x.coerceIn(0f, 1f) * (width - 1))
            val fy = (y.coerceIn(0f, 1f) * (height - 1))
            val x0 = fx.toInt().coerceIn(0, width - 1)
            val y0 = fy.toInt().coerceIn(0, height - 1)
            val x1 = (x0 + 1).coerceAtMost(width - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val tx = fx - x0
            val ty = fy - y0
            val a = values[y0 * width + x0] + (values[y0 * width + x1] - values[y0 * width + x0]) * tx
            val b = values[y1 * width + x0] + (values[y1 * width + x1] - values[y1 * width + x0]) * tx
            return (a + (b - a) * ty).coerceIn(0f, 1f)
        }

        /**
         * 1.6.58：把任意尺寸的选区 grid 重采样成 [PortraitRegionMask] 的
         * side×side 归一化网格，供 [applyPortrait] 把美颜限定在选区内。
         * 用归一化坐标采样，因此预览与导出（不同分辨率）得到同一作用域。
         */
        fun toPortraitRegion(side: Int = PORTRAIT_REGION_SIDE, strength: Float = 1f): PortraitRegionMask {
            val out = FloatArray(side * side)
            for (y in 0 until side) {
                val v = y.toFloat() / (side - 1)
                for (x in 0 until side) {
                    val u = x.toFloat() / (side - 1)
                    out[y * side + x] = (sample(u, v) * strength).coerceIn(0f, 1f)
                }
            }
            return PortraitRegionMask(side, out)
        }
    }

    private const val PORTRAIT_REGION_SIDE = 128

    /**
     * 1.6.58：一个图层除影调参数外，还可携带一组「仅选择主体」的局部人像
     * 美颜及其作用域。[portraitRegion] 由该图层最终 grid（含 eraser / 反选 /
     * 精修 / 羽化）重采样而来，保证美颜与影调作用于完全相同的区域。
     */
    data class PreparedLayer(
        val grid: MaskGrid,
        val params: LocalParams,
        val strength: Float,
        val portrait: PortraitRetouch = PortraitRetouch.Off,
        val portraitRegion: PortraitRegionMask? = null
    )

    /** Legacy entry point for geometric masks / unit tests (no photo needed). */
    fun buildGrid(mask: LocalMask, frameWidth: Int, frameHeight: Int): MaskGrid =
        buildGrid(mask, frameWidth, frameHeight, null, null)

    /**
     * Rasterises a mask. Content-aware selections are rebuilt from [analysis]
     * (features of the UNEDITED original photo) at that analysis grid's own
     * resolution, which is already resolution-independent because [MaskGrid]
     * samples by normalised coordinates. Geometric masks keep the 384 grid.
     */
    fun buildGrid(
        mask: LocalMask,
        frameWidth: Int,
        frameHeight: Int,
        analysis: SelectionMaskGenerator.Analysis?,
        job: Job?
    ): MaskGrid {
        if (mask.isContentAware) {
            val gw = analysis?.w ?: 64
            val gh = analysis?.h ?: 64
            val built = SelectionMaskGenerator.build(mask, analysis, job, gw, gh)
            if (built != null && built.size == gw * gh) {
                return MaskGrid(gw, gh, built)
            }
        }
        val long = max(frameWidth, frameHeight).coerceAtLeast(1)
        val scale = min(1f, GRID_SIDE.toFloat() / long)
        val w = (frameWidth * scale).roundToInt().coerceIn(8, GRID_SIDE)
        val h = (frameHeight * scale).roundToInt().coerceIn(8, GRID_SIDE)
        val values = FloatArray(w * h)
        // Aspect correction keeps a "round" control point round on non-square frames.
        val aspect = frameWidth.toFloat() / frameHeight.coerceAtLeast(1).toFloat()

        when (mask) {
            is LocalMask.ControlPoint -> {
                val radius = mask.radius.coerceIn(0.01f, 1.5f)
                val inner = (1f - mask.feather.coerceIn(0.05f, 0.98f))
                for (y in 0 until h) {
                    val ny = y.toFloat() / (h - 1)
                    for (x in 0 until w) {
                        val nx = x.toFloat() / (w - 1)
                        val d = hypot((nx - mask.x) * aspect.coerceAtMost(2f), ny - mask.y) / radius
                        // 1.6.59：反选不再在此处烘焙，统一在 prepare() 的 eraser
                        // 之后执行（见 applyInversion），保证反选覆盖「当前选区之外
                        // 的全部区域」（含被橡皮擦擦除的洞）。
                        values[y * w + x] = 1f - smoothstep(inner, 1f, d)
                    }
                }
            }
            is LocalMask.LinearGradient -> {
                val dx = mask.endX - mask.startX
                val dy = mask.endY - mask.startY
                val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
                val feather = mask.feather.coerceIn(0.02f, 1f)
                // Gate: pixels projected BEFORE the start or BEYOND the end are
                // outside the selection. The old code CLAMPED t to 0..1, so both
                // outer half-planes inherited the endpoint value: every pixel
                // past the end stayed at full strength (and, when inverted, the
                // whole area before the start was full too). The mask therefore
                // leaked far outside its two endpoints. Keeping the raw
                // (unclamped) projection and gating it confines the effect to
                // the strip actually spanned by the endpoints.
                val edge = (feather * 0.15f + 0.01f).coerceIn(0.01f, 0.12f)
                for (y in 0 until h) {
                    val ny = y.toFloat() / (h - 1)
                    for (x in 0 until w) {
                        val nx = x.toFloat() / (w - 1)
                        val t = ((nx - mask.startX) * dx + (ny - mask.startY) * dy) / len2
                        // Keep the original soft ramp (feather widens the start
                        // transition, then it saturates): only the gate changes,
                        // so existing auto-zone/linear recipes keep their look.
                        val ramp = smoothstep(0f, feather, t)
                        val gate = smoothstep(-edge, 0f, t) * (1f - smoothstep(1f, 1f + edge, t))
                        // 1.6.59：方向翻转（反选）统一在 prepare() 的 eraser 之后
                        // 执行，这里只负责带 gate 的软斜坡本身。
                        values[y * w + x] = ramp * gate
                    }
                }
            }
            is LocalMask.RadialGradient -> {
                val inner = 1f - mask.feather.coerceIn(0.05f, 0.98f)
                for (y in 0 until h) {
                    val ny = y.toFloat() / (h - 1)
                    for (x in 0 until w) {
                        val nx = x.toFloat() / (w - 1)
                        // Rotation-aware distance: the ellipse can now be spun
                        // with its handle; raster and outline share the maths.
                        val d = mask.rotatedDistance(nx, ny, aspect)
                        // 1.6.59：反选统一在 prepare() 的 eraser 之后执行。
                        values[y * w + x] = 1f - smoothstep(inner, 1f, d)
                    }
                }
            }
            is LocalMask.Brush -> {
                // 1.6.59：反选统一在 prepare() 的 eraser 之后执行。
                rasteriseBrush(values, w, h, mask, aspect)
            }
            // Content-aware masks are returned early above when an analysis is
            // available. This fallback (a soft disc / top gradient around the
            // seed) only applies when no photo is supplied, so the grid is
            // never blank in tests or degraded preview paths.
            is LocalMask.SubjectMask,
            is LocalMask.SkyMask,
            is LocalMask.ColorRangeMask -> {
                val fallback = SelectionMaskGenerator.build(mask, null, null, w, h)
                if (fallback != null && fallback.size == values.size) {
                    System.arraycopy(fallback, 0, values, 0, values.size)
                }
            }
        }
        return MaskGrid(w, h, values)
    }

    private fun rasteriseBrush(
        values: FloatArray, w: Int, h: Int, mask: LocalMask.Brush, aspect: Float
    ) {
        val radiusPx = (mask.radius.coerceIn(0.005f, 0.5f) * max(w, h)).coerceAtLeast(1.2f)
        val feather = mask.feather.coerceIn(0.05f, 1f)
        // Raw stroke points are LOCAL; apply the brush's rotation + offset so
        // a dragged / spun selection renders exactly where its outline shows.
        fun map(x: Float, y: Float) = mask.mapPoint(x, y, aspect)
        for (stroke in mask.strokes) {
            val pts = stroke.points
            var i = 0
            while (i + 1 < pts.size) {
                val a = map(pts[i], pts[i + 1])
                val b = map(pts.getOrElse(i + 2) { pts[i] }, pts.getOrElse(i + 3) { pts[i + 1] })
                stampSegment(values, w, h, a.first, a.second, b.first, b.second,
                    radiusPx, feather, stroke.strength)
                i += 2
            }
            if (pts.size == 2) {
                val a = map(pts[0], pts[1])
                stampSegment(values, w, h, a.first, a.second, a.first, a.second,
                    radiusPx, feather, stroke.strength)
            }
        }
        // A light box blur gives painted edges a soft, feathered falloff.
        if (mask.strokes.isNotEmpty()) boxBlur(values, w, h, (radiusPx * 0.35f).roundToInt().coerceIn(1, 6))
    }

    private fun stampSegment(
        values: FloatArray, w: Int, h: Int,
        x0: Float, y0: Float, x1: Float, y1: Float,
        radiusPx: Float, feather: Float, strength: Float
    ) {
        val ax = x0 * (w - 1); val ay = y0 * (h - 1)
        val bx = x1 * (w - 1); val by = y1 * (h - 1)
        val minX = (min(ax, bx) - radiusPx).toInt().coerceAtLeast(0)
        val maxX = (max(ax, bx) + radiusPx).toInt().coerceAtMost(w - 1)
        val minY = (min(ay, by) - radiusPx).toInt().coerceAtLeast(0)
        val maxY = (max(ay, by) + radiusPx).toInt().coerceAtMost(h - 1)
        val dx = bx - ax; val dy = by - ay
        val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
        val inner = radiusPx * (1f - feather)
        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
                val cx = ax + t * dx; val cy = ay + t * dy
                val d = hypot(px - cx, py - cy)
                val falloff = 1f - smoothstep(inner, radiusPx, d)
                val value = falloff * strength
                val idx = py * w + px
                if (value > values[idx]) values[idx] = value
            }
        }
    }

    private fun boxBlur(values: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val temp = FloatArray(values.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f; var count = 0
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    sum += values[row + sx]; count++
                }
                temp[row + x] = sum / count
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f; var count = 0
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    sum += temp[sy * w + x]; count++
                }
                values[y * w + x] = sum / count
            }
        }
    }

    /**
     * Removes painted ERASER strokes from a rasterised mask. It works for
     * EVERY mask kind because it acts on the final float grid, after the shape
     * (or the AI selection) has already been built. Both preview and export go
     * through [prepare], so the erased scope is identical in both.
     */
    fun applyEraser(values: FloatArray, w: Int, h: Int, strokes: List<EraserStroke>) {
        if (strokes.isEmpty() || w <= 0 || h <= 0) return
        val long = max(w, h).toFloat()
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.size < 2) continue
            val radiusPx = (stroke.radius.coerceIn(0.005f, 0.4f) * long).coerceAtLeast(1.2f)
            val inner = radiusPx * (1f - stroke.feather.coerceIn(0.05f, 1f))
            var k = 0
            while (k + 3 < pts.size) {
                eraseSegment(values, w, h, pts[k], pts[k + 1], pts[k + 2], pts[k + 3],
                    radiusPx, inner, stroke.strength)
                k += 2
            }
            if (pts.size == 2) {
                eraseSegment(values, w, h, pts[0], pts[1], pts[0], pts[1],
                    radiusPx, inner, stroke.strength)
            }
        }
    }

    /** One soft round dab along a stroke segment; multiplies scope down. */
    private fun eraseSegment(
        values: FloatArray, w: Int, h: Int,
        x0: Float, y0: Float, x1: Float, y1: Float,
        radiusPx: Float, inner: Float, strength: Float
    ) {
        val ax = x0 * (w - 1); val ay = y0 * (h - 1)
        val bx = x1 * (w - 1); val by = y1 * (h - 1)
        val minX = (min(ax, bx) - radiusPx).toInt().coerceAtLeast(0)
        val maxX = (max(ax, bx) + radiusPx).toInt().coerceAtMost(w - 1)
        val minY = (min(ay, by) - radiusPx).toInt().coerceAtLeast(0)
        val maxY = (max(ay, by) + radiusPx).toInt().coerceAtMost(h - 1)
        val dx = bx - ax; val dy = by - ay
        val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
                val d = hypot(px - (ax + t * dx), py - (ay + t * dy))
                if (d > radiusPx) continue
                val falloff = 1f - smoothstep(inner, radiusPx, d)
                val amount = (falloff * strength).coerceIn(0f, 1f)
                val i = py * w + px
                values[i] = (values[i] * (1f - amount)).coerceIn(0f, 1f)
            }
        }
    }

    /** Prepares every active layer once per render (geometric-only path). */
    fun prepare(local: LocalAdjustments, width: Int, height: Int, job: Job?): List<PreparedLayer> =
        prepare(local, width, height, job, null)

    /**
     * Full path: [analysis] is computed once from the untouched original
     * pixels and shared by every content-aware layer, so a preset with several
     * selections does not re-analyse the photo for each one.
     */
    /**
     * 1.6.59：把已构建（且已擦除）的蒙版 grid 整体取补集，作为「反选」的最后一步。
     * 关键顺序：必须在 [applyEraser] 之后执行，否则反选只会翻转原始选区、而用户
     * 用橡皮擦擦出的「洞」因在原选区内（翻转后为 0）再被擦除乘法清零，导致洞内区域
     * 在反选后仍然未被选中。改在 eraser 之后取补集后，反选即覆盖「当前选区（含被擦
     * 除区域之外）的全部区域」，符合「选中蒙版覆盖范围之外所有区域」的语义。
     */
    fun invertGrid(values: FloatArray) {
        for (i in values.indices) values[i] = (1f - values[i]).coerceIn(0f, 1f)
    }

    fun prepare(
        local: LocalAdjustments,
        width: Int,
        height: Int,
        job: Job?,
        analysis: SelectionMaskGenerator.Analysis?
    ): List<PreparedLayer> {
        val layers = local.activeLayers()
        if (layers.isEmpty()) return emptyList()
        return layers.map { layer ->
            checkCancelled(job)
            val grid = buildGrid(layer.mask, width, height, analysis, job)
            // The eraser is layer-scoped and mask-agnostic: apply it to the
            // final grid so geometric shapes and AI selections behave alike.
            applyEraser(grid.values, grid.width, grid.height, layer.eraser)
            // 1.6.59：反选必须放在 eraser 之后执行（见 [invertGrid]），
            // 否则被擦除的洞在反选后不会被选中。
            if (layer.mask.isInverted) invertGrid(grid.values)
            // 1.6.58：仅「选择主体」图层可带局部人像美颜。作用域取自已经过
            // eraser / 反选 / 精修 / 羽化的最终 grid，并叠加图层强度，保证
            // 美颜与影调落在完全相同的区域、浓淡随图层强度一致。
            val hasPortrait = layer.mask is LocalMask.SubjectMask && !layer.portrait.isOff()
            val portrait = if (hasPortrait) layer.portrait.normalized() else PortraitRetouch.Off
            val portraitRegion = if (hasPortrait) {
                grid.toPortraitRegion(strength = layer.strength / 100f)
            } else null
            PreparedLayer(
                grid,
                layer.params,
                layer.strength / 100f,
                portrait,
                portraitRegion
            )
        }
    }

    /**
     * 1.6.58：对「选择主体」图层执行局部人像美颜。逐图层在 grade 之前、
     * 全局美颜之后调用 [applyPortrait]，用该图层的选区 region 与
     * regionFloor=0 把磨皮/美白严格限制在选区内；region 为空（无主体美颜）
     * 时完全跳过，既有渲染路径逐像素不变。预览与导出共用 [prepare]，一致。
     */
    suspend fun applyLocalPortrait(
        pixels: IntArray,
        width: Int,
        height: Int,
        layers: List<PreparedLayer>,
        job: Job?
    ) {
        for (layer in layers) {
            if (layer.portrait.isOff()) continue
            val region = layer.portraitRegion ?: continue
            applyPortrait(pixels, width, height, layer.portrait, job, region, 0f)
        }
    }

    /** 整帧平均亮度，供并行 apply 在进入前一次性计算。 */
    fun computeMeanLuma(pixels: IntArray): Float {
        var sum = 0f
        for (i in pixels.indices) {
            val c = pixels[i]
            sum += ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
        }
        return sum / pixels.size.coerceAtLeast(1)
    }

    /**
     * Blends the prepared layers onto the pixels. All arithmetic stays in
     * floating point until the single final write, so stacking several masks
     * cannot introduce 8-bit banding.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        layers: List<PreparedLayer>,
        job: Job?,
        meanLumaValue: Float? = null,
        fromRow: Int = 0,
        toRow: Int = height
    ) {
        if (layers.isEmpty()) return
        // 全局平均亮度：并行渲染时由调用方在进入本 pass 之前算好并传入，
        // 保证每个行区间用的是同一份「整帧」均值，与串行逐像素完全一致。
        // 兼容调用（未传）则在快路径之后就地计算，语义不变。
        val meanLuma = meanLumaValue ?: computeMeanLuma(pixels)

        for (y in fromRow until toRow) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            val ny = y.toFloat() / (height - 1).coerceAtLeast(1)
            var index = y * width
            for (x in 0 until width) {
                val nx = x.toFloat() / (width - 1).coerceAtLeast(1)
                val color = pixels[index]
                var r = ((color shr 16) and 0xFF).toFloat()
                var g = ((color shr 8) and 0xFF).toFloat()
                var b = (color and 0xFF).toFloat()

                for (layer in layers) {
                    val w = layer.grid.sample(nx, ny) * layer.strength
                    if (w <= 0.001f) continue
                    val p = layer.params
                    var lr = r; var lg = g; var lb = b
                    val luma = lr * 0.299f + lg * 0.587f + lb * 0.114f

                    if (abs(p.brightness) > 0.001f) {
                        val add = p.brightness * 0.60f
                        lr += add; lg += add; lb += add
                    }
                    if (abs(p.contrast) > 0.001f || abs(p.clarity) > 0.001f) {
                        val amount = 1f + p.contrast / 100f * 0.55f + p.clarity / 100f * 0.30f
                        lr = 128f + (lr - 128f) * amount + (luma - meanLuma) * (p.clarity / 100f * 0.18f)
                        lg = 128f + (lg - 128f) * amount + (luma - meanLuma) * (p.clarity / 100f * 0.18f)
                        lb = 128f + (lb - 128f) * amount + (luma - meanLuma) * (p.clarity / 100f * 0.18f)
                    }
                    if (abs(p.highlights) > 0.001f) {
                        val weight = smoothstep(0.55f, 1f, luma / 255f)
                        val add = p.highlights * 0.45f * weight
                        lr += add; lg += add; lb += add
                    }
                    if (abs(p.shadows) > 0.001f) {
                        val weight = 1f - smoothstep(0f, 0.45f, luma / 255f)
                        val add = p.shadows * 0.45f * weight
                        lr += add; lg += add; lb += add
                    }
                    if (abs(p.saturation) > 0.001f) {
                        val lum = lr * 0.299f + lg * 0.587f + lb * 0.114f
                        val sat = 1f + p.saturation / 100f * 0.9f
                        lr = lum + (lr - lum) * sat
                        lg = lum + (lg - lum) * sat
                        lb = lum + (lb - lum) * sat
                    }
                    if (abs(p.temperature) > 0.001f) {
                        val t = p.temperature / 100f * 20f
                        lr += t; lb -= t
                    }

                    r += (lr - r) * w
                    g += (lg - g) * w
                    b += (lb - b) * w
                }
                pixels[index] = 0xFF000000.toInt() or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
                index++
            }
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 >= edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun checkCancelled(job: Job?) {
        if (job != null && !job.isActive) throw CancellationException("Render cancelled")
    }
}
