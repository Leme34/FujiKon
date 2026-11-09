package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.LocalMask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pixel-accurate, CONTENT-AWARE selection generator for the three new mask
 * tools (select subject / select sky / select colour range).
 *
 * This is deliberately a **deterministic on-device heuristic, NOT an AI model**.
 * There is no neural network, no model file and no network call. Precision
 * comes from classic image segmentation building blocks instead of a coarse
 * "centre disc / upper half" guess:
 *
 *  - a downsampled feature analysis of the UNEDITED original photo
 *    (colour, HSL, YCbCr skin weight, edge energy, local texture);
 *  - foreground/background colour probability (a lightweight GrabCut seed
 *    model) plus centre and skin priors for the subject;
 *  - a dynamically detected horizon, top-edge colour distance flood fill and
 *    a top-connected-component constraint for the sky;
 *  - ring hue distance + HSL/RGB distance + grey rejection for the colour
 *    range, with an optional seed-connected component (magic wand);
 *  - region growing with edge barriers, morphological clean-up (remove
 *    isolated specks / fill pinholes) and a feathered boundary.
 *
 * Nothing pixel-sized is persisted: callers store only the generation
 * parameters and (optionally) a seed point, so preview and export rebuild the
 * identical mask from the identical original pixels.
 *
 * Everything runs on a grid whose longest side is at most [SIDE] (256), then
 * the renderer samples it bilinearly. That bounds memory to a few MB even for
 * a 36 MP export and keeps the per-render cost in the low tens of ms.
 */
object SelectionMaskGenerator {

    const val SIDE = 256

    /**
     * Per-cell features of the ORIGINAL photo, all indexed as `y * w + x`.
     * `r/g/b/luma` are 0..255, `hue` 0..360, `sat/lig` 0..1, and
     * `skin/edge/texture` are soft 0..1 weights.
     */
    class Analysis(
        val w: Int,
        val h: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
        val luma: FloatArray,
        val hue: FloatArray,
        val sat: FloatArray,
        val lig: FloatArray,
        val skin: FloatArray,
        val edge: FloatArray,
        val texture: FloatArray
    ) {
        val size: Int get() = w * h

        /** Nearest cell for a normalised (0..1) frame coordinate. */
        fun seedIndex(nx: Float, ny: Float): Int {
            val x = (nx.coerceIn(0f, 1f) * (w - 1)).roundToInt().coerceIn(0, w - 1)
            val y = (ny.coerceIn(0f, 1f) * (h - 1)).roundToInt().coerceIn(0, h - 1)
            return y * w + x
        }

        /** Normalised (0..1) Euclidean RGB distance from a cell to a colour. */
        fun colorTo(i: Int, cr: Float, cg: Float, cb: Float): Float {
            val dr = (r[i] - cr) / 255f
            val dg = (g[i] - cg) / 255f
            val db = (b[i] - cb) / 255f
            return sqrt((dr * dr + dg * dg + db * db) / 3f).coerceIn(0f, 1f)
        }
    }

    /**
     * Builds the analysis grid from UNEDITED ARGB pixels. Sampling a 2x2
     * patch per cell (instead of a single pixel) suppresses sensor noise and
     * thin lines without a separate blur pass.
     */
    fun analyze(pixels: IntArray, sw: Int, sh: Int, side: Int = SIDE): Analysis {
        val long = max(sw, sh).coerceAtLeast(1)
        val scale = min(1f, side.toFloat() / long)
        val w = (sw * scale).roundToInt().coerceIn(8, side)
        val h = (sh * scale).roundToInt().coerceIn(8, side)
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        val luma = FloatArray(n); val hue = FloatArray(n)
        val sat = FloatArray(n); val lig = FloatArray(n); val skin = FloatArray(n)
        val edge = FloatArray(n); val texture = FloatArray(n)

        for (gy in 0 until h) {
            val sy0 = (gy * sh / h).coerceIn(0, sh - 1)
            val sy1 = ((gy + 1) * sh / h).coerceIn(sy0 + 1, sh)
            for (gx in 0 until w) {
                val sx0 = (gx * sw / w).coerceIn(0, sw - 1)
                val sx1 = ((gx + 1) * sw / w).coerceIn(sx0 + 1, sw)
                var sr = 0f; var sg = 0f; var sb = 0f; var count = 0
                for (ty in 0..1) {
                    for (tx in 0..1) {
                        val sx = (sx0 + (sx1 - sx0) * (tx + 0.5f) / 2f).toInt().coerceIn(0, sw - 1)
                        val sy = (sy0 + (sy1 - sy0) * (ty + 0.5f) / 2f).toInt().coerceIn(0, sh - 1)
                        val c = pixels[sy * sw + sx]
                        sr += (c shr 16) and 0xFF
                        sg += (c shr 8) and 0xFF
                        sb += c and 0xFF
                        count++
                    }
                }
                val i = gy * w + gx
                r[i] = sr / count; g[i] = sg / count; b[i] = sb / count
                luma[i] = r[i] * 0.299f + g[i] * 0.587f + b[i] * 0.114f
                val mx = max(r[i], max(g[i], b[i])); val mn = min(r[i], min(g[i], b[i]))
                val li = (mx + mn) * 0.5f / 255f
                val d = mx - mn
                lig[i] = li
                if (d < 1f) {
                    hue[i] = 0f; sat[i] = 0f
                } else {
                    val s = (d / 255f) / (1f - abs(2f * li - 1f)).coerceAtLeast(1e-6f)
                    val hh = when (mx) {
                        r[i] -> ((g[i] - b[i]) / d + if (g[i] < b[i]) 6f else 0f) * 60f
                        g[i] -> (b[i] - r[i]) / d * 60f + 120f
                        else -> (r[i] - g[i]) / d * 60f + 240f
                    }
                    hue[i] = (hh + 360f) % 360f
                    sat[i] = s.coerceIn(0f, 1f)
                }
                // Classic YCbCr skin-tone window (same constants as PortraitProcessor).
                val cb = 128f - 0.168736f * r[i] - 0.331264f * g[i] + 0.5f * b[i]
                val cr = 128f + 0.5f * r[i] - 0.418688f * g[i] - 0.081312f * b[i]
                skin[i] = bandWeight(cb, 77f, 127f, 14f) *
                    bandWeight(cr, 133f, 173f, 14f) *
                    bandWeight(luma[i], 28f, 246f, 16f)
            }
        }

        // Edge energy and local texture from the luma grid.
        for (gy in 0 until h) {
            for (gx in 0 until w) {
                val i = gy * w + gx
                val l = luma[gy * w + (gx - 1).coerceAtLeast(0)]
                val rr = luma[gy * w + (gx + 1).coerceAtMost(w - 1)]
                val t = luma[(gy - 1).coerceAtLeast(0) * w + gx]
                val bb = luma[(gy + 1).coerceAtMost(h - 1) * w + gx]
                edge[i] = ((abs(rr - l) + abs(bb - t)) / 255f * 1.6f).coerceIn(0f, 1f)
                var sum = 0f; var sum2 = 0f; var c = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val v = luma[(gy + dy).coerceIn(0, h - 1) * w + (gx + dx).coerceIn(0, w - 1)]
                        sum += v; sum2 += v * v; c++
                    }
                }
                val mean = sum / c
                val variance = (sum2 / c - mean * mean).coerceAtLeast(0f)
                texture[i] = (sqrt(variance) / 255f * 5f).coerceIn(0f, 1f)
            }
        }
        return Analysis(w, h, r, g, b, luma, hue, sat, lig, skin, edge, texture)
    }

    /** UI-side entry point: scales the original bitmap down before reading it. */
    fun fromBitmap(bitmap: Bitmap?, side: Int = SIDE): Analysis? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return null
        val sw = bitmap.width; val sh = bitmap.height
        val scale = min(1f, side.toFloat() / max(sw, sh).coerceAtLeast(1))
        val tw = (sw * scale).roundToInt().coerceIn(8, side)
        val th = (sh * scale).roundToInt().coerceIn(8, side)
        val small = if (tw == sw && th == sh) bitmap else Bitmap.createScaledBitmap(bitmap, tw, th, true)
        val pixels = IntArray(tw * th)
        small.getPixels(pixels, 0, tw, 0, 0, tw, th)
        if (small !== bitmap) small.recycle()
        return analyze(pixels, tw, th, side)
    }

    /**
     * Builds a soft 0..1 selection grid for a content-aware mask. Returns
     * null for geometric masks. When [a] is null (no original photo available,
     * e.g. a pure unit test of the raster) it degrades to a well-defined
     * geometric approximation rather than an all-zero or all-one grid.
     */
    fun build(
        mask: LocalMask, a: Analysis?, job: Job? = null,
        targetW: Int = 64, targetH: Int = 64
    ): FloatArray? = when (mask) {
        is LocalMask.SubjectMask -> {
            if (a == null) disc(targetW, targetH, mask.seedX, mask.seedY, 0.34f, mask.feather, mask.invert)
            else subject(a, mask, job)
        }
        is LocalMask.SkyMask -> {
            if (a == null) topGradient(targetW, targetH, mask.feather, mask.invert)
            else sky(a, mask, job)
        }
        is LocalMask.ColorRangeMask -> {
            if (a == null) disc(targetW, targetH, mask.seedX, mask.seedY, 0.30f, mask.feather, mask.invert)
            else colorRange(a, mask, job)
        }
        else -> null
    }

    // ---------------------------------------------------------------- subject

    private fun subject(a: Analysis, m: LocalMask.SubjectMask, job: Job?): FloatArray {
        val w = a.w; val h = a.h; val n = a.size
        val seed = a.seedIndex(m.seedX, m.seedY)
        val tol = m.tolerance.coerceIn(0f, 1f)
        val fg = averageColor(a, seed, 3)
        val bg = borderAverageColor(a)
        val score = FloatArray(n)

        for (y in 0 until h) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            val ny = y.toFloat() / (h - 1).coerceAtLeast(1)
            for (x in 0 until w) {
                val i = y * w + x
                val nx = x.toFloat() / (w - 1).coerceAtLeast(1)
                val dFg = a.colorTo(i, fg[0], fg[1], fg[2])
                val dBg = a.colorTo(i, bg[0], bg[1], bg[2])
                // Foreground/background colour probability: a cell is "subject"
                // when it looks more like the tapped seed than like the frame border.
                val colorProb = dBg / (dFg + dBg + 0.015f)
                val centre = 1f - smoothstep(
                    0.10f, 0.60f,
                    sqrt((nx - m.seedX) * (nx - m.seedX) * 1.25f +
                        (ny - m.seedY) * (ny - m.seedY) * 1.7f)
                )
                score[i] = (0.42f * colorProb + 0.19f * a.skin[i] +
                    0.24f * centre + 0.15f * localDetail(a, x, y)).coerceIn(0f, 1f)
            }
        }
        val threshold = (0.60f - tol * 0.32f).coerceIn(0.20f, 0.74f)
        val soft = 0.11f
        val weight = FloatArray(n) { smoothstep(threshold - soft, threshold + soft, score[it]) }
        // Region growing out of the seed, stopped by strong edges: the selected
        // blob follows object contours rather than a radial falloff.
        val connected = keepConnected(
            weight, w, h, bestSeed(weight, seed, w, h), 0.34f, a.edge, 0.74f
        )
        return finish(clean(connected, w, h), w, h, m.feather, m.invert)
    }

    // -------------------------------------------------------------------- sky

    private fun sky(a: Analysis, m: LocalMask.SkyMask, job: Job?): FloatArray {
        val w = a.w; val h = a.h; val n = a.size
        val tol = m.tolerance.coerceIn(0f, 1f)
        // Reference sky colour from only the BLUEST top pixels, so a white dress
        // / glass / ceiling at the top cannot drag the reference towards grey
        // and then re-match itself as "sky".
        val skyColor = topBlueColor(a)
        val rowMean = FloatArray(h)

        // Blueness is the dominant cue. A white dress, glass reflection, wood
        // floor or green plant has b≈r, so blueish≈0 and is NOT mistaken for sky.
        val blueishAt: (Int) -> Float = { i -> ((a.b[i] - a.r[i]) / 255f * 2.4f).coerceIn(0f, 1f) }

        for (y in 0 until h) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var sum = 0f
            for (x in 0 until w) {
                val i = y * w + x
                val blueish = blueishAt(i)
                val brightNeutral = if (a.sat[i] < 0.18f && a.luma[i] > 0.62f * 255f) 0.4f else 0f
                val lowTex = 1f - a.texture[i]
                val colW = 1f - smoothstep(0.10f, 0.46f, a.colorTo(i, skyColor[0], skyColor[1], skyColor[2]))
                sum += (0.62f * blueish + 0.16f * colW + 0.16f * lowTex + 0.06f * brightNeutral).coerceIn(0f, 1f)
            }
            rowMean[y] = sum / w
        }

        // Dynamic horizon: first row whose sky likelihood collapses relative to
        // the top band. Falls back to a conservative 0.8 of the frame.
        val topRows = (h * 0.12f).roundToInt().coerceIn(2, h)
        var peak = 0f
        for (y in 0 until topRows) peak += rowMean[y]
        peak = (peak / topRows).coerceAtLeast(0.06f)
        var horizon = 0.82f
        for (y in 2 until h) {
            if (rowMean[y] < peak * 0.45f) {
                horizon = y.toFloat() / (h - 1).coerceAtLeast(1)
                break
            }
        }
        horizon = horizon.coerceIn(0.16f, 0.94f)

        val threshold = (0.52f - tol * 0.28f).coerceIn(0.20f, 0.62f)
        val weight = FloatArray(n)
        for (y in 0 until h) {
            val ny = y.toFloat() / (h - 1).coerceAtLeast(1)
            val upper = 1f - smoothstep(horizon - 0.10f, horizon + 0.03f, ny)
            for (x in 0 until w) {
                val i = y * w + x
                val blueish = blueishAt(i)
                val brightNeutral = if (a.sat[i] < 0.18f && a.luma[i] > 0.62f * 255f) 0.4f else 0f
                val lowTex = 1f - a.texture[i]
                val colW = 1f - smoothstep(0.10f, 0.46f, a.colorTo(i, skyColor[0], skyColor[1], skyColor[2]))
                val likelihood = (0.62f * blueish + 0.16f * colW + 0.16f * lowTex + 0.06f * brightNeutral).coerceIn(0f, 1f)
                weight[i] = smoothstep(threshold - 0.10f, threshold + 0.10f, likelihood) * upper
            }
        }

        // Seeds must be BLUISH and near the TOP edge. A bright but colourless
        // top (white dress / window / ceiling) seeds nothing, and a photo with
        // no sky therefore yields an (almost) empty selection instead of
        // flooding the whole frame. Blue clothing / water lower in the frame is
        // excluded because it never touches the blue top edge.
        val seedRows = max(2, (h * 0.06f).roundToInt())
        val topSeeds = ArrayList<Int>()
        for (y in 0 until seedRows) {
            for (x in 0 until w) {
                val i = y * w + x
                if (blueishAt(i) > 0.18f && weight[i] > 0.30f) topSeeds += i
            }
        }
        if (topSeeds.isEmpty()) {
            // No blue sky touching the top: nothing to select.
            return finish(FloatArray(n), w, h, m.feather, m.invert)
        }
        val seeds = IntArray(topSeeds.size) { topSeeds[it] }
        val connected = keepConnected(weight, w, h, seeds, 0.22f, null, 1f)
        return finish(clean(connected, w, h), w, h, m.feather, m.invert)
    }

    // ------------------------------------------------------------ colour range

    private fun colorRange(a: Analysis, m: LocalMask.ColorRangeMask, job: Job?): FloatArray {
        val w = a.w; val h = a.h; val n = a.size
        val range = m.hueRange.coerceIn(5f, 90f)
        val tol = (0.10f + m.tolerance.coerceIn(0f, 1f) * 0.42f).coerceIn(0.06f, 0.60f)
        val minSat = m.minSaturation.coerceIn(0f, 0.9f)
        val targetRgb = hslToRgb(m.targetHue, m.targetSat.coerceIn(0f, 1f), m.targetLum.coerceIn(0f, 1f))
        val weight = FloatArray(n)

        for (y in 0 until h) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            for (x in 0 until w) {
                val i = y * w + x
                var dh = abs(a.hue[i] - m.targetHue)
                if (dh > 180f) dh = 360f - dh
                // Narrower hueRange => a hue difference costs more.
                val hueTerm = (dh / 180f) * (60f / range)
                val ds = abs(a.sat[i] - m.targetSat)
                val dl = abs(a.lig[i] - m.targetLum)
                val dRgb = a.colorTo(i, targetRgb[0], targetRgb[1], targetRgb[2])
                val d = 0.46f * hueTerm + 0.20f * ds + 0.14f * dl + 0.20f * dRgb
                var v = 1f - smoothstep(tol * 0.55f, tol, d)
                if (a.sat[i] < minSat) {
                    v *= (a.sat[i] / minSat.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                }
                weight[i] = v.coerceIn(0f, 1f)
            }
        }

        val out = if (m.contiguous) {
            val seed = a.seedIndex(m.seedX, m.seedY)
            keepConnected(weight, w, h, bestSeed(weight, seed, w, h), 0.20f, null, 1f)
        } else weight.copyOf()
        val cleaned = if (m.contiguous) clean(out, w, h) else out
        return finish(cleaned, w, h, m.feather, m.invert)
    }

    // ------------------------------------------------------------ shared tools

    /**
     * Region growing / connected component. Starting from [seeds], visits
     * 8-neighbour cells whose [weight] clears [threshold]. A strong [edge]
     * between two cells blocks orthogonal growth, which keeps the selection
     * from bleeding across object contours.
     */
    private fun keepConnected(
        weight: FloatArray, w: Int, h: Int, seeds: IntArray,
        threshold: Float, edge: FloatArray?, maxEdge: Float
    ): FloatArray {
        val out = FloatArray(weight.size)
        val visited = BooleanArray(weight.size)
        val queue = IntArray(weight.size)
        var head = 0; var tail = 0
        for (s in seeds) {
            if (s in 0 until weight.size && !visited[s] && weight[s] >= threshold * 0.55f) {
                visited[s] = true; queue[tail++] = s
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            out[i] = weight[i]
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val j = ny * w + nx
                    if (visited[j] || weight[j] < threshold) continue
                    if (edge != null && (dx == 0 || dy == 0) && edge[j] > maxEdge) continue
                    visited[j] = true; queue[tail++] = j
                }
            }
        }
        return out
    }

    /** If the tapped seed misses the likely region, snap to its best neighbour. */
    private fun bestSeed(weight: FloatArray, seed: Int, w: Int, h: Int): IntArray {
        val x = seed % w; val y = seed / w
        var best = seed; var bestV = weight[seed]
        for (dy in -3..3) {
            for (dx in -3..3) {
                val nx = (x + dx).coerceIn(0, w - 1); val ny = (y + dy).coerceIn(0, h - 1)
                val j = ny * w + nx
                if (weight[j] > bestV) { bestV = weight[j]; best = j }
            }
        }
        return intArrayOf(best)
    }

    /** Morphological clean-up: drop isolated specks, fill single-cell holes. */
    private fun clean(values: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(values.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var on = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (x + dx).coerceIn(0, w - 1); val ny = (y + dy).coerceIn(0, h - 1)
                        if (values[ny * w + nx] > 0.5f) on++
                    }
                }
                val v = values[i]
                out[i] = when {
                    v > 0.5f && on <= 2 -> 0f
                    v <= 0.5f && on >= 7 -> 1f
                    else -> v
                }
            }
        }
        return out
    }

    /** Feathered, optionally inverted, clamped final grid. */
    private fun finish(
        values: FloatArray, w: Int, h: Int, feather: Float, invert: Boolean
    ): FloatArray {
        val radius = (feather.coerceIn(0f, 1f) * 6f).roundToInt().coerceIn(0, 6)
        if (radius > 0) boxBlur(values, w, h, radius)
        for (i in values.indices) {
            val v = values[i].coerceIn(0f, 1f)
            values[i] = if (invert) 1f - v else v
        }
        return values
    }

    private fun boxBlur(values: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val temp = FloatArray(values.size)
        val window = radius * 2 + 1
        for (y in 0 until h) {
            var sum = 0f
            for (k in -radius..radius) sum += values[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                temp[y * w + x] = sum / window
                sum -= values[y * w + (x - radius).coerceIn(0, w - 1)]
                sum += values[y * w + (x + radius + 1).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -radius..radius) sum += temp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                values[y * w + x] = sum / window
                sum -= temp[(y - radius).coerceIn(0, h - 1) * w + x]
                sum += temp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
            }
        }
    }

    private fun localDetail(a: Analysis, x: Int, y: Int): Float {
        var sum = 0f; var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, a.w - 1); val ny = (y + dy).coerceIn(0, a.h - 1)
                sum += a.edge[ny * a.w + nx]; count++
            }
        }
        return (sum / count * 2.2f).coerceIn(0f, 1f)
    }

    private fun averageColor(a: Analysis, seed: Int, radius: Int): FloatArray {
        val x = seed % a.w; val y = seed / a.w
        var sr = 0f; var sg = 0f; var sb = 0f; var count = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val i = (y + dy).coerceIn(0, a.h - 1) * a.w + (x + dx).coerceIn(0, a.w - 1)
                sr += a.r[i]; sg += a.g[i]; sb += a.b[i]; count++
            }
        }
        return floatArrayOf(sr / count, sg / count, sb / count)
    }

    private fun borderAverageColor(a: Analysis): FloatArray {
        val bw = (a.w * 0.08f).roundToInt().coerceAtLeast(1)
        val bh = (a.h * 0.08f).roundToInt().coerceAtLeast(1)
        var sr = 0f; var sg = 0f; var sb = 0f; var count = 0
        for (y in 0 until a.h) {
            for (x in 0 until a.w) {
                if (x >= bw && x < a.w - bw && y >= bh && y < a.h - bh) continue
                val i = y * a.w + x
                sr += a.r[i]; sg += a.g[i]; sb += a.b[i]; count++
            }
        }
        if (count == 0) return floatArrayOf(128f, 128f, 128f)
        return floatArrayOf(sr / count, sg / count, sb / count)
    }

    /** Average colour of the BLUEST pixels in the top band — a true sky-blue
     *  reference, not the bright average that a white dress would pull grey. */
    private fun topBlueColor(a: Analysis): FloatArray {
        val rows = (a.h * 0.10f).roundToInt().coerceAtLeast(1)
        var sr = 0f; var sg = 0f; var sb = 0f; var count = 0
        for (y in 0 until rows) {
            for (x in 0 until a.w) {
                val i = y * a.w + x
                // Ignore very dark pixels (roof / tree silhouettes) at the top.
                if (a.luma[i] < 0.22f * 255f) continue
                // Only genuinely blue-ish pixels contribute to the reference.
                val blueish = ((a.b[i] - a.r[i]) / 255f * 2.4f).coerceIn(0f, 1f)
                if (blueish < 0.12f) continue
                sr += a.r[i]; sg += a.g[i]; sb += a.b[i]; count++
            }
        }
        if (count == 0) return floatArrayOf(150f, 185f, 225f)
        return floatArrayOf(sr / count, sg / count, sb / count)
    }

    /** HSL (degrees / 0..1 / 0..1) -> 0..255 RGB, matching the picker's HSL. */
    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
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
        val mm = l - c * 0.5f
        return floatArrayOf((r1 + mm) * 255f, (g1 + mm) * 255f, (b1 + mm) * 255f)
    }

    /** Well-defined geometric fallback used when no original photo is supplied. */
    private fun disc(
        w: Int, h: Int, cx: Float, cy: Float, radius: Float, feather: Float, invert: Boolean
    ): FloatArray {
        val out = FloatArray(w * h)
        val inner = 1f - feather.coerceIn(0.05f, 0.95f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = x.toFloat() / (w - 1); val ny = y.toFloat() / (h - 1)
                val d = sqrt((nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)) / radius
                out[y * w + x] = 1f - smoothstep(inner, 1f, d)
            }
        }
        if (invert) for (i in out.indices) out[i] = 1f - out[i]
        return out
    }

    private fun topGradient(w: Int, h: Int, feather: Float, invert: Boolean): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val ny = y.toFloat() / (h - 1)
            val v = 1f - smoothstep(0.45f - feather * 0.1f, 0.75f + feather * 0.1f, ny)
            for (x in 0 until w) out[y * w + x] = if (invert) 1f - v else v
        }
        return out
    }

    /** Semi-transparent amber overlay for the mask editor preview. */
    fun toOverlay(values: FloatArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val v = values[i].coerceIn(0f, 1f)
                var boundary = 0f
                for (d in intArrayOf(-1, 1, -w, w)) {
                    val j = (i + d).coerceIn(0, w * h - 1)
                    boundary = max(boundary, abs(v - values[j].coerceIn(0f, 1f)))
                }
                val alpha = if (boundary > 0.28f) (v * 190f).roundToInt() else (v * 74f).roundToInt()
                out[i] = (alpha.coerceIn(0, 210) shl 24) or 0x00FFB030.toInt()
            }
        }
        return out
    }

    private fun bandWeight(value: Float, low: Float, high: Float, fade: Float): Float =
        smoothstep(low - fade, low, value) * (1f - smoothstep(high, high + fade, value))

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 >= edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun checkCancelled(job: Job?) {
        if (job != null && !job.isActive) throw CancellationException("Selection cancelled")
    }
}
