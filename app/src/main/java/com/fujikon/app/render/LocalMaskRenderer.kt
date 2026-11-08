package com.fujikon.app.render

import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
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
    }

    data class PreparedLayer(val grid: MaskGrid, val params: LocalParams, val strength: Float)

    fun buildGrid(mask: LocalMask, frameWidth: Int, frameHeight: Int): MaskGrid {
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
                        // invert flips the direction INSIDE the band only; the
                        // gate keeps everything outside the endpoints at 0.
                        values[y * w + x] = (if (mask.invert) 1f - ramp else ramp) * gate
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
                        val inside = 1f - smoothstep(inner, 1f, d)
                        values[y * w + x] = if (mask.invert) 1f - inside else inside
                    }
                }
            }
            is LocalMask.Brush -> {
                rasteriseBrush(values, w, h, mask, aspect)
                if (mask.invert) {
                    for (i in values.indices) values[i] = 1f - values[i]
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

    /** Prepares every active layer once per render. */
    fun prepare(local: LocalAdjustments, width: Int, height: Int, job: Job?): List<PreparedLayer> {
        val layers = local.activeLayers()
        if (layers.isEmpty()) return emptyList()
        return layers.map { layer ->
            checkCancelled(job)
            PreparedLayer(buildGrid(layer.mask, width, height), layer.params, layer.strength / 100f)
        }
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
        job: Job?
    ) {
        if (layers.isEmpty()) return
        var meanLuma = 0f
        for (i in pixels.indices) {
            val c = pixels[i]
            meanLuma += ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
        }
        meanLuma /= pixels.size.coerceAtLeast(1)

        for (y in 0 until height) {
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
