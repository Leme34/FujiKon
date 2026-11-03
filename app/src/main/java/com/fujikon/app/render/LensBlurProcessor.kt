package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.LensBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightroom-style lens blur for the CPU pipeline.
 *
 * The focus region stays untouched and everything outside it fades into a
 * progressive box blur (three passes approximate a Gaussian). Large photos are
 * blurred on a downscaled copy to keep memory and time bounded.
 */
internal suspend fun applyLensBlur(
    pixels: IntArray,
    width: Int,
    height: Int,
    blur: LensBlur,
    job: Job?
) {
    val state = blur.normalized()
    if (state.isOff() || width < 8 || height < 8) return

    val minEdge = min(width, height).toFloat()
    val radius = (minEdge * BLUR_RATIO * state.amount / 100f).roundToInt().coerceAtLeast(1)
    val scale = workingScale(width, height)

    val smallWidth = (width / scale).coerceAtLeast(1)
    val smallHeight = (height / scale).coerceAtLeast(1)
    val small = if (scale == 1) {
        // The highlight boost writes into the blur source, so keep the
        // rendered pixels untouched.
        pixels.copyOf()
    } else {
        downsample(pixels, width, height, smallWidth, smallHeight)
    }
    if (state.highlights > 0.5f) {
        boostHighlights(small, smallWidth, smallHeight, state.highlights / 100f)
    }

    val blurred = IntArray(smallWidth * smallHeight)
    val temp = IntArray(smallWidth * smallHeight)
    val blurRadius = (radius.toFloat() / scale).roundToInt().coerceAtLeast(1)
    repeat(3) {
        job?.ensureActive()
        horizontalPass(small, temp, smallWidth, smallHeight, blurRadius)
        verticalPass(temp, blurred, smallWidth, smallHeight, blurRadius)
    }

    blend(pixels, blurred, width, height, smallWidth, smallHeight, scale, state, job)
}

/** Picks the most detailed area near the centre, a stand-in for AI subject detection. */
internal fun autoFocusPoint(bitmap: Bitmap): Pair<Float, Float>? {
    val width = bitmap.width
    val height = bitmap.height
    if (width < 4 || height < 4) return null

    val grid = 48
    val luma = FloatArray(grid * grid)
    for (gy in 0 until grid) {
        for (gx in 0 until grid) {
            // Average a 3x3 patch of the grid cell so a single hot pixel does
            // not decide the focus point.
            var sum = 0f
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val x = ((gx + 0.5f) * width / grid + dx * width / (grid * 4f))
                        .roundToInt().coerceIn(0, width - 1)
                    val y = ((gy + 0.5f) * height / grid + dy * height / (grid * 4f))
                        .roundToInt().coerceIn(0, height - 1)
                    val pixel = bitmap.getPixel(x, y)
                    sum += 0.299f * ((pixel shr 16) and 0xFF) +
                        0.587f * ((pixel shr 8) and 0xFF) +
                        0.114f * (pixel and 0xFF)
                    count++
                }
            }
            luma[gy * grid + gx] = sum / count
        }
    }

    val energy = FloatArray(grid * grid)
    for (gy in 0 until grid) {
        for (gx in 0 until grid) {
            val left = luma[gy * grid + max(0, gx - 1)]
            val right = luma[gy * grid + min(grid - 1, gx + 1)]
            val top = luma[max(0, gy - 1) * grid + gx]
            val bottom = luma[min(grid - 1, gy + 1) * grid + gx]
            energy[gy * grid + gx] = kotlin.math.abs(right - left) + kotlin.math.abs(bottom - top)
        }
    }

    var best = -1f
    var bestX = grid / 2
    var bestY = grid / 2
    for (gy in 0 until grid) {
        for (gx in 0 until grid) {
            var detail = 0f
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val sx = (gx + dx).coerceIn(0, grid - 1)
                    val sy = (gy + dy).coerceIn(0, grid - 1)
                    detail += energy[sy * grid + sx]
                }
            }
            val nx = (gx + 0.5f) / grid - 0.5f
            val ny = (gy + 0.5f) / grid - 0.5f
            val centreBias = 1f - 0.9f * sqrt(nx * nx + ny * ny) * 2f
            val score = detail * centreBias.coerceAtLeast(0.05f)
            if (score > best) {
                best = score
                bestX = gx
                bestY = gy
            }
        }
    }
    return (bestX + 0.5f) / grid to (bestY + 0.5f) / grid
}

private fun workingScale(width: Int, height: Int): Int {
    val count = width.toLong() * height.toLong()
    return when {
        count > MAX_WORKING_PIXELS * 4 -> 4
        count > MAX_WORKING_PIXELS -> 2
        else -> 1
    }
}

private fun downsample(
    pixels: IntArray,
    width: Int,
    height: Int,
    smallWidth: Int,
    smallHeight: Int
): IntArray {
    val scaleX = width.toFloat() / smallWidth
    val scaleY = height.toFloat() / smallHeight
    val output = IntArray(smallWidth * smallHeight)
    for (y in 0 until smallHeight) {
        val top = (y * scaleY).toInt()
        val bottom = ((y + 1) * scaleY).toInt().coerceAtLeast(top + 1)
        for (x in 0 until smallWidth) {
            val left = (x * scaleX).toInt()
            val right = ((x + 1) * scaleX).toInt().coerceAtLeast(left + 1)
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (sy in top until bottom.coerceAtMost(height)) {
                val row = sy * width
                for (sx in left until right.coerceAtMost(width)) {
                    val pixel = pixels[row + sx]
                    r += (pixel shr 16) and 0xFF
                    g += (pixel shr 8) and 0xFF
                    b += pixel and 0xFF
                    count++
                }
            }
            output[y * smallWidth + x] = argb(r / count, g / count, b / count)
        }
    }
    return output
}

/** Brightens specular details before blurring so highlights bloom like bokeh. */
private fun boostHighlights(pixels: IntArray, width: Int, height: Int, strength: Float) {
    for (index in 0 until width * height) {
        val pixel = pixels[index]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luma = luma(r, g, b)
        val weight = smoothstep(0.55f, 0.95f, luma) * strength
        if (weight <= 0f) continue
        pixels[index] = argb(
            (r + (255 - r) * weight * 0.9f).roundToInt(),
            (g + (255 - g) * weight * 0.9f).roundToInt(),
            (b + (255 - b) * weight * 0.9f).roundToInt()
        )
    }
}

private suspend fun blend(
    pixels: IntArray,
    blurred: IntArray,
    width: Int,
    height: Int,
    smallWidth: Int,
    smallHeight: Int,
    scale: Int,
    state: LensBlur,
    job: Job?
) {
    val minEdge = min(width, height).toFloat()
    val centreX = state.focusX * width
    val centreY = state.focusY * height
    val sharpRadius = (state.focusSize / 100f * minEdge).coerceAtLeast(1f)
    val feather = sharpRadius * (0.15f + state.feather / 100f * 1.35f)
    val outerRadius = sharpRadius + feather.coerceAtLeast(1f)

    coroutineScope {
        val chunks = rowsPerChunk(height)
        (0 until height step chunks).map { start ->
            async(Dispatchers.Default) {
                val end = min(height, start + chunks)
                for (y in start until end) {
                    job?.ensureActive()
                    val row = y * width
                    for (x in 0 until width) {
                        val dx = x - centreX
                        val dy = y - centreY
                        val distance = sqrt(dx * dx + dy * dy)
                        val weight = smoothstep(sharpRadius, outerRadius, distance)
                        if (weight <= 0f) continue
                        val sampleX = (x + 0.5f) / scale - 0.5f
                        val sampleY = (y + 0.5f) / scale - 0.5f
                        val blurredPixel = if (scale == 1) {
                            blurred[y * width + x]
                        } else {
                            sampleBilinear(blurred, smallWidth, smallHeight, sampleX, sampleY)
                        }
                        pixels[row + x] = mix(pixels[row + x], blurredPixel, weight)
                    }
                }
            }
        }.awaitAll()
    }
}

private fun horizontalPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val window = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var r = 0
        var g = 0
        var b = 0
        var a = 0
        for (i in -radius..radius) {
            val pixel = src[row + i.coerceIn(0, width - 1)]
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
            a += (pixel ushr 24)
        }
        for (x in 0 until width) {
            dst[row + x] = ((a / window) shl 24) or
                ((r / window) shl 16) or
                ((g / window) shl 8) or
                (b / window)
            val outgoing = src[row + (x - radius).coerceIn(0, width - 1)]
            val incoming = src[row + (x + radius + 1).coerceIn(0, width - 1)]
            r += ((incoming shr 16) and 0xFF) - ((outgoing shr 16) and 0xFF)
            g += ((incoming shr 8) and 0xFF) - ((outgoing shr 8) and 0xFF)
            b += (incoming and 0xFF) - (outgoing and 0xFF)
            a += (incoming ushr 24) - (outgoing ushr 24)
        }
    }
}

private fun verticalPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val window = radius * 2 + 1
    for (x in 0 until width) {
        var r = 0
        var g = 0
        var b = 0
        var a = 0
        for (i in -radius..radius) {
            val pixel = src[i.coerceIn(0, height - 1) * width + x]
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
            a += (pixel ushr 24)
        }
        for (y in 0 until height) {
            dst[y * width + x] = ((a / window) shl 24) or
                ((r / window) shl 16) or
                ((g / window) shl 8) or
                (b / window)
            val outgoing = src[(y - radius).coerceIn(0, height - 1) * width + x]
            val incoming = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            r += ((incoming shr 16) and 0xFF) - ((outgoing shr 16) and 0xFF)
            g += ((incoming shr 8) and 0xFF) - ((outgoing shr 8) and 0xFF)
            b += (incoming and 0xFF) - (outgoing and 0xFF)
            a += (incoming ushr 24) - (outgoing ushr 24)
        }
    }
}

private fun sampleBilinear(
    pixels: IntArray,
    width: Int,
    height: Int,
    x: Float,
    y: Float
): Int {
    val sx = x.coerceIn(0f, (width - 1).toFloat())
    val sy = y.coerceIn(0f, (height - 1).toFloat())
    val x0 = sx.toInt().coerceIn(0, width - 1)
    val y0 = sy.toInt().coerceIn(0, height - 1)
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val fx = sx - x0
    val fy = sy - y0

    val p00 = pixels[y0 * width + x0]
    val p10 = pixels[y0 * width + x1]
    val p01 = pixels[y1 * width + x0]
    val p11 = pixels[y1 * width + x1]

    fun channel(shift: Int): Int {
        val c00 = (p00 shr shift) and 0xFF
        val c10 = (p10 shr shift) and 0xFF
        val c01 = (p01 shr shift) and 0xFF
        val c11 = (p11 shr shift) and 0xFF
        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
    }

    return argb(channel(16), channel(8), channel(0))
}

private fun mix(from: Int, to: Int, weight: Float): Int {
    val r = ((from shr 16) and 0xFF) + (((to shr 16) and 0xFF) - ((from shr 16) and 0xFF)) * weight
    val g = ((from shr 8) and 0xFF) + (((to shr 8) and 0xFF) - ((from shr 8) and 0xFF)) * weight
    val b = (from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * weight
    return argb(r.roundToInt(), g.roundToInt(), b.roundToInt())
}

private fun argb(r: Int, g: Int, b: Int): Int =
    0xFF000000.toInt() or
        (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or
        b.coerceIn(0, 255)

private fun luma(r: Int, g: Int, b: Int): Float =
    (0.299f * r + 0.587f * g + 0.114f * b) / 255f

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun rowsPerChunk(height: Int): Int =
    max(1, height / max(1, Runtime.getRuntime().availableProcessors() * 2))

private const val BLUR_RATIO = 0.03f
private const val MAX_WORKING_PIXELS = 2_000_000L
