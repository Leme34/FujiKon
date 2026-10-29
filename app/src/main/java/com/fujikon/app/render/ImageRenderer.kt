package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CPU image pipeline that approximates Nikon's Picture Control rendering.
 *
 * Pipeline: tone LUT (the file's 256-point curve) -> base-look saturation ->
 * contrast / brightness / highlight / shadow / white / black -> saturation ->
 * hue -> color blender -> color grading -> monochrome toning -> unsharp mask.
 *
 * The 256x256 LUT is built once per preset, then applied per channel.
 * Work is split across CPU cores; previews use a downscaled bitmap.
 */
class ImageRenderer {

    suspend fun render(source: Bitmap, preset: Preset, forExport: Boolean): Bitmap {
        val job = currentCoroutineContext()[Job]
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        if (forExport) {
            // The export path no longer needs the source bitmap; freeing it here
            // keeps peak memory low on high-resolution photos.
            source.recycle()
        }
        checkCancelled(job)

        val plan = buildPlan(preset, forExport, width, height)

        parallelRows(height) { from, to ->
            processRows(pixels, width, from, to, plan, job)
        }

        if (plan.sharpenFine > 0.01f || plan.sharpenClarity > 0.01f) {
            applySharpening(pixels, width, height, plan, job)
        }
        checkCancelled(job)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    // ---------------------------------------------------------------- plan

    private class Plan(
        val lut: IntArray,
        val saturation: Float,
        val hueMatrix: FloatArray?,
        val monochrome: Boolean,
        val monoWeights: FloatArray,
        val toningTint: FloatArray?,
        val toningStrength: Float,
        val blender: FloatArray?,
        val gradingChroma: FloatArray?,
        val gradingBright: FloatArray?,
        val gradingTint: FloatArray?,
        val gradingBlend: Float,
        val gradingBalance: Float,
        val sharpenFine: Float,
        val sharpenClarity: Float,
        val clarityRadius: Int
    ) {
        val needsColor: Boolean
            get() = monochrome || abs(saturation - 1f) > 0.001f || hueMatrix != null ||
                blender != null || gradingChroma != null
    }

    private fun buildPlan(preset: Preset, forExport: Boolean, width: Int, height: Int): Plan {
        val isNcp = preset.format == "NCP" || preset.format == "NP2"
        val scale = if (isNcp) 3f else 100f

        val baseSaturation = when (preset.baseProfile) {
            BaseProfiles.STANDARD -> 1.05f
            BaseProfiles.VIVID -> 1.20f
            BaseProfiles.PORTRAIT -> 1.03f
            BaseProfiles.LANDSCAPE -> 1.14f
            BaseProfiles.MONOCHROME -> 0f
            else -> 1f
        }
        val baseContrast = when (preset.baseProfile) {
            BaseProfiles.STANDARD -> 0.08f
            BaseProfiles.VIVID -> 0.18f
            BaseProfiles.PORTRAIT -> -0.05f
            BaseProfiles.LANDSCAPE -> 0.12f
            else -> 0f
        }

        val lut = buildToneLut(
            preset = preset,
            baseContrast = if (preset.hasCurve) 0f else baseContrast,
            contrastScale = scale
        )

        val monochrome = preset.baseProfile == BaseProfiles.MONOCHROME
        val saturation = if (monochrome) 0f else (baseSaturation * (1f + preset.saturation / scale)).coerceIn(0f, 2.4f)
        val hueDegrees = if (isNcp) preset.hue / 3f * 10f else preset.hue / 100f * 30f
        val hueMatrix = if (abs(hueDegrees) > 0.1f) buildHueMatrix(hueDegrees) else null

        val blender = if (preset.colorBlender.size >= 8 && preset.hasColorBlender) {
            FloatArray(24) { i ->
                val value = preset.colorBlender[i / 3]
                when (i % 3) {
                    0 -> value.hue / 100f
                    1 -> value.chroma / 100f
                    else -> value.brightness / 100f
                }
            }
        } else null

        val grading = preset.colorGrading
        val gradingActive = preset.hasColorGrading
        val gradingTint = if (gradingActive) {
            val zones = listOf(grading.highlights, grading.midtones, grading.shadows)
            FloatArray(9) { i ->
                val zone = zones[i / 3]
                val packed = hslToRgb(zone.hue.toFloat(), 0.78f, 0.5f)
                val channel = when (i % 3) {
                    0 -> (packed shr 16) and 0xFF
                    1 -> (packed shr 8) and 0xFF
                    else -> packed and 0xFF
                }
                channel / 255f
            }
        } else null
        val gradingChroma = if (gradingActive) {
            val zones = listOf(grading.highlights, grading.midtones, grading.shadows)
            FloatArray(3) { zones[it].chroma / 100f }
        } else null
        val gradingBright = if (gradingActive) {
            val zones = listOf(grading.highlights, grading.midtones, grading.shadows)
            FloatArray(3) { zones[it].brightness / 100f }
        } else null

        val monoWeights = when (preset.filter) {
            1 -> floatArrayOf(0.50f, 0.45f, 0.05f)  // yellow
            2 -> floatArrayOf(0.62f, 0.33f, 0.05f)  // orange
            3 -> floatArrayOf(0.82f, 0.15f, 0.03f)  // red
            4 -> floatArrayOf(0.18f, 0.72f, 0.10f)  // green
            else -> floatArrayOf(0.299f, 0.587f, 0.114f)
        }

        val toning = toningTint(preset.toning)
        val toningStrength = if (toning != null) {
            val raw = if (preset.toningStrength > 0) preset.toningStrength / 100f else 0.65f
            raw.coerceIn(0.1f, 1f)
        } else 0f

        val maxDimension = max(width, height)
        val unit = (maxDimension / 1400f).coerceIn(0.35f, 4f)
        val sharp = preset.sharpening.coerceAtLeast(0f) / 9f
        val mid = preset.midRangeSharpening.coerceAtLeast(0f) / 5f
        val clarity = preset.clarity.coerceAtLeast(0f) / 5f
        val previewScale = if (forExport) 1f else 0.7f
        val fineAmount = (sharp * 1.10f + mid * 0.55f) * previewScale
        val clarityAmount = (clarity * 0.85f) * previewScale
        val clarityRadius = (5f * unit).roundToInt().coerceIn(2, 18)

        return Plan(
            lut = lut,
            saturation = saturation,
            hueMatrix = hueMatrix,
            monochrome = monochrome,
            monoWeights = monoWeights,
            toningTint = toning,
            toningStrength = toningStrength,
            blender = blender,
            gradingChroma = gradingChroma,
            gradingBright = gradingBright,
            gradingTint = gradingTint,
            gradingBlend = grading.blending / 100f,
            gradingBalance = grading.balance / 100f,
            sharpenFine = fineAmount,
            sharpenClarity = clarityAmount,
            clarityRadius = clarityRadius
        )
    }

    private fun buildToneLut(preset: Preset, baseContrast: Float, contrastScale: Float): IntArray {
        val curve = preset.curve
        val contrast = (preset.contrast / contrastScale + baseContrast).coerceIn(-1f, 1f)
        val ncpScale = preset.format == "NCP" || preset.format == "NP2"
        val brightness = preset.brightness / if (ncpScale) 1f else 100f
        val highlights = preset.highlights / 100f
        val shadows = preset.shadows / 100f
        val whites = preset.whites / 100f
        val blacks = preset.blacks / 100f
        val toneCurve = preset.toneCurve

        return IntArray(256) { i ->
            var x = if (curve != null && curve.size >= 256) {
                curve[i].coerceIn(0, 32767) / 32767f
            } else {
                i / 255f
            }
            x = contrastCurve(x, contrast)
            if (abs(brightness) > 0.001f) x += brightness * 0.16f
            if (abs(highlights) > 0.001f) x += highlights * smoothstep(0.48f, 1f, x) * 0.22f
            if (abs(shadows) > 0.001f) x += shadows * (1f - smoothstep(0f, 0.52f, x)) * 0.22f
            if (abs(whites) > 0.001f) x += whites * smoothstep(0.72f, 1f, x) * 0.16f
            if (abs(blacks) > 0.001f) x += blacks * (1f - smoothstep(0f, 0.28f, x)) * 0.16f
            x = x.coerceIn(0f, 1f)
            if (toneCurve != null) x = toneCurve.apply(x)
            (x * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    // -------------------------------------------------------------- pixels

    private fun processRows(
        pixels: IntArray,
        width: Int,
        from: Int,
        to: Int,
        plan: Plan,
        job: Job?
    ) {
        val hsl = FloatArray(3)
        val low = plan.lut
        val sat = plan.saturation
        val hueMatrix = plan.hueMatrix
        val mono = plan.monochrome
        val monoWeights = plan.monoWeights
        val toningTint = plan.toningTint
        val toningStrength = plan.toningStrength
        val blender = plan.blender
        val gradingChroma = plan.gradingChroma
        val gradingTint = plan.gradingTint
        val gradingBright = plan.gradingBright

        for (y in from until to) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var index = y * width
            val end = index + width
            while (index < end) {
                val color = pixels[index]
                var r = low[(color shr 16) and 0xFF]
                var g = low[(color shr 8) and 0xFF]
                var b = low[color and 0xFF]

                if (mono) {
                    var luma = r * monoWeights[0] + g * monoWeights[1] + b * monoWeights[2]
                    if (toningTint != null && toningStrength > 0f) {
                        val tr = luma * toningTint[0]
                        val tg = luma * toningTint[1]
                        val tb = luma * toningTint[2]
                        r = (luma + (tr - luma) * toningStrength).roundToInt()
                        g = (luma + (tg - luma) * toningStrength).roundToInt()
                        b = (luma + (tb - luma) * toningStrength).roundToInt()
                    } else {
                        r = luma.roundToInt(); g = r; b = r
                    }
                    pixels[index] = pack(r, g, b)
                    index++
                    continue
                }

                if (abs(sat - 1f) > 0.001f) {
                    val luma = r * 0.299f + g * 0.587f + b * 0.114f
                    r = (luma + (r - luma) * sat).roundToInt()
                    g = (luma + (g - luma) * sat).roundToInt()
                    b = (luma + (b - luma) * sat).roundToInt()
                }

                if (hueMatrix != null) {
                    val nr = r * hueMatrix[0] + g * hueMatrix[1] + b * hueMatrix[2]
                    val ng = r * hueMatrix[3] + g * hueMatrix[4] + b * hueMatrix[5]
                    val nb = r * hueMatrix[6] + g * hueMatrix[7] + b * hueMatrix[8]
                    r = nr.roundToInt(); g = ng.roundToInt(); b = nb.roundToInt()
                }

                if (blender != null) {
                    rgbToHsl(r, g, b, hsl)
                    var h = hsl[0]
                    var s = hsl[1]
                    var l = hsl[2]
                    var hueShift = 0f
                    var chromaAdd = 0f
                    var brightAdd = 0f
                    for (band in 0 until 8) {
                        var diff = abs(h - BAND_CENTERS[band])
                        if (diff > 180f) diff = 360f - diff
                        if (diff >= BAND_WIDTH) continue
                        val weight = 1f - diff / BAND_WIDTH
                        hueShift += blender[band * 3] * weight
                        chromaAdd += blender[band * 3 + 1] * weight
                        brightAdd += blender[band * 3 + 2] * weight
                    }
                    if (hueShift != 0f || chromaAdd != 0f || brightAdd != 0f) {
                        h = (h + hueShift * 28f + 360f) % 360f
                        s = (s * (1f + chromaAdd * 0.75f)).coerceIn(0f, 1f)
                        l = (l + brightAdd * 0.075f).coerceIn(0f, 1f)
                        val packed = hslToRgb(h, s, l)
                        r = (packed shr 16) and 0xFF
                        g = (packed shr 8) and 0xFF
                        b = packed and 0xFF
                    }
                }

                if (gradingChroma != null && gradingTint != null) {
                    val luma = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                    val adjusted = (luma + plan.gradingBalance * 0.18f).coerceIn(0f, 1f)
                    val soft = 0.18f + plan.gradingBlend * 0.42f
                    val high = smoothstep(0.58f - soft * 0.35f, 1f, adjusted)
                    val shadow = 1f - smoothstep(0f, 0.42f + soft * 0.35f, adjusted)
                    val mid = (1f - high - shadow).coerceAtLeast(0f)
                    val w0 = high
                    val w1 = mid
                    val w2 = shadow
                    var dr = 0f
                    var dg = 0f
                    var db = 0f
                    val s0 = gradingChroma[0] * w0
                    val s1 = gradingChroma[1] * w1
                    val s2 = gradingChroma[2] * w2
                    dr += (gradingTint[0] - 0.5f) * s0 * 0.55f
                    dg += (gradingTint[1] - 0.5f) * s0 * 0.55f
                    db += (gradingTint[2] - 0.5f) * s0 * 0.55f
                    dr += (gradingTint[3] - 0.5f) * s1 * 0.55f
                    dg += (gradingTint[4] - 0.5f) * s1 * 0.55f
                    db += (gradingTint[5] - 0.5f) * s1 * 0.55f
                    dr += (gradingTint[6] - 0.5f) * s2 * 0.55f
                    dg += (gradingTint[7] - 0.5f) * s2 * 0.55f
                    db += (gradingTint[8] - 0.5f) * s2 * 0.55f
                    val bright = gradingBright!![0] * w0 + gradingBright[1] * w1 + gradingBright[2] * w2
                    if (bright != 0f) {
                        dr += bright * 0.09f
                        dg += bright * 0.09f
                        db += bright * 0.09f
                    }
                    r = (r + dr * 255f).roundToInt()
                    g = (g + dg * 255f).roundToInt()
                    b = (b + db * 255f).roundToInt()
                }

                pixels[index] = pack(r, g, b)
                index++
            }
        }
    }

    // ---------------------------------------------------------- sharpening

    private fun applySharpening(pixels: IntArray, width: Int, height: Int, plan: Plan, job: Job?) {
        val luma = ByteArray(pixels.size)
        for (i in pixels.indices) {
            if ((i and 0xFFFF) == 0) checkCancelled(job)
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            luma[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }

        val fine = plan.sharpenFine
        val clarity = plan.sharpenClarity
        val radius = plan.clarityRadius

        for (y in 0 until height) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var i = y * width
            for (x in 0 until width) {
                val center = (luma[i].toInt() and 0xFF).toFloat()
                var delta = 0f
                if (fine > 0.01f) {
                    val avg = neighborAverage(luma, width, height, x, y, 1)
                    delta += (center - avg) * fine * 1.35f
                }
                if (clarity > 0.01f) {
                    val avg = neighborAverage(luma, width, height, x, y, radius)
                    delta += (center - avg) * clarity * 0.9f
                }
                if (delta != 0f) {
                    val color = pixels[i]
                    val r = ((color shr 16) and 0xFF) + delta
                    val g = ((color shr 8) and 0xFF) + delta
                    val b = (color and 0xFF) + delta
                    pixels[i] = pack(r.roundToInt(), g.roundToInt(), b.roundToInt())
                }
                i++
            }
        }
    }

    private fun neighborAverage(luma: ByteArray, width: Int, height: Int, x: Int, y: Int, radius: Int): Float {
        var sum = 0
        var count = 0
        val x0 = (x - radius).coerceAtLeast(0)
        val x1 = (x + radius).coerceAtMost(width - 1)
        val y0 = (y - radius).coerceAtLeast(0)
        val y1 = (y + radius).coerceAtMost(height - 1)

        sum += luma[y * width + x0].toInt() and 0xFF; count++
        sum += luma[y * width + x1].toInt() and 0xFF; count++
        sum += luma[y0 * width + x].toInt() and 0xFF; count++
        sum += luma[y1 * width + x].toInt() and 0xFF; count++
        if (radius > 1) {
            val xm = (x - radius / 2).coerceIn(0, width - 1)
            val xp = (x + radius / 2).coerceIn(0, width - 1)
            val ym = (y - radius / 2).coerceIn(0, height - 1)
            val yp = (y + radius / 2).coerceIn(0, height - 1)
            sum += luma[ym * width + xm].toInt() and 0xFF; count++
            sum += luma[ym * width + xp].toInt() and 0xFF; count++
            sum += luma[yp * width + xm].toInt() and 0xFF; count++
            sum += luma[yp * width + xp].toInt() and 0xFF; count++
        }
        return sum.toFloat() / count
    }

    // --------------------------------------------------------------- utils

    private fun checkCancelled(job: Job?) {
        if (job != null && !job.isActive) throw CancellationException("Render cancelled")
    }

    private suspend fun parallelRows(height: Int, block: (Int, Int) -> Unit) = coroutineScope {
        val threads = min(8, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        val chunk = (height + threads - 1) / threads
        (0 until threads).map { t ->
            async(Dispatchers.Default) {
                val from = t * chunk
                val to = min(height, from + chunk)
                if (from < to) block(from, to)
            }
        }.awaitAll()
    }

    private fun contrastCurve(x: Float, amount: Float): Float {
        if (abs(amount) < 0.001f) return x
        val s = x * x * (3f - 2f * x)
        val target = if (amount >= 0f) s else (2f * x - s)
        return (x + (target - x) * abs(amount)).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pack(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun rgbToHsl(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = max(rf, max(gf, bf))
        val min = min(rf, min(gf, bf))
        val l = (max + min) * 0.5f
        val d = max - min
        if (d < 1e-6f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = l
            return
        }
        val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
        var h = when (max) {
            rf -> ((gf - bf) / d + if (gf < bf) 6f else 0f) * 60f
            gf -> ((bf - rf) / d + 2f) * 60f
            else -> ((rf - gf) / d + 4f) * 60f
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = s.coerceIn(0f, 1f)
        out[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        var r1 = 0f
        var g1 = 0f
        var b1 = 0f
        when {
            hp < 1f -> { r1 = c; g1 = x; b1 = 0f }
            hp < 2f -> { r1 = x; g1 = c; b1 = 0f }
            hp < 3f -> { r1 = 0f; g1 = c; b1 = x }
            hp < 4f -> { r1 = 0f; g1 = x; b1 = c }
            hp < 5f -> { r1 = x; g1 = 0f; b1 = c }
            else -> { r1 = c; g1 = 0f; b1 = x }
        }
        val m = l - c * 0.5f
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun buildHueMatrix(degrees: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f,
            0.715f - c * 0.715f - s * 0.715f,
            0.072f - c * 0.072f + s * 0.928f,
            0.213f - c * 0.213f + s * 0.143f,
            0.715f + c * 0.285f + s * 0.140f,
            0.072f - c * 0.072f - s * 0.283f,
            0.213f - c * 0.213f - s * 0.787f,
            0.715f - c * 0.715f + s * 0.715f,
            0.072f + c * 0.928f + s * 0.072f
        )
    }

    private fun toningTint(toning: Int): FloatArray? {
        if (toning <= 0) return null
        return when (toning % 9) {
            1 -> floatArrayOf(1.08f, 0.98f, 0.82f)
            2 -> floatArrayOf(0.82f, 0.94f, 1.12f)
            3 -> floatArrayOf(1.10f, 0.86f, 0.84f)
            4 -> floatArrayOf(1.08f, 1.04f, 0.80f)
            5 -> floatArrayOf(0.86f, 1.08f, 0.88f)
            6 -> floatArrayOf(0.84f, 0.94f, 1.10f)
            7 -> floatArrayOf(1.02f, 0.86f, 1.08f)
            else -> floatArrayOf(1f, 1f, 1f)
        }
    }

    companion object {
        private val BAND_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
        private const val BAND_WIDTH = 52f
    }
}
