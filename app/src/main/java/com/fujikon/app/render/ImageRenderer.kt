package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.CreativeFilter
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.EditCrop
import com.fujikon.app.data.FilterProfile
import com.fujikon.app.data.Preset
import com.fujikon.app.data.buildChannelLuts
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
        val adjustments = preset.adjustments.normalized()

        // Skin retouch runs before the grade so the mask sees natural skin
        // tones; strong looks (monochrome, filters) would otherwise hide it.
        applyPortrait(pixels, width, height, adjustments.portrait, job)

        parallelRows(height) { from, to ->
            processRows(pixels, width, from, to, plan, job)
        }

        if (plan.needsDetail) {
            applyDetail(pixels, width, height, plan, job)
        }
        applyLensBlur(pixels, width, height, adjustments.lensBlur, job)
        checkCancelled(job)

        return cropPixels(pixels, width, height, adjustments.crop, job)
    }

    /**
     * Applies a crop / straighten state to an already rendered bitmap without
     * touching the rest of the pipeline. Used by the preview so the crop editor
     * can work on the uncropped frame.
     */
    suspend fun crop(source: Bitmap, crop: EditCrop): Bitmap {
        val state = crop.normalized()
        if (state.isFull()) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        return cropPixels(pixels, width, height, state, currentCoroutineContext()[Job])
    }

    private suspend fun cropPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        crop: EditCrop,
        job: Job?
    ): Bitmap {
        if (crop.isFull()) return bitmapFrom(pixels, width, height)

        // A ratio-only crop is pixel-exact and much faster than resampling.
        if (crop.rotation == 0 && abs(crop.straighten) <= 0.001f) {
            val rect = crop.rect(width.toFloat(), height.toFloat())
            val left = (rect.left * width).roundToInt().coerceIn(0, width - 1)
            val top = (rect.top * height).roundToInt().coerceIn(0, height - 1)
            val right = (rect.right * width).roundToInt().coerceIn(left + 1, width)
            val bottom = (rect.bottom * height).roundToInt().coerceIn(top + 1, height)
            val cropWidth = right - left
            val cropHeight = bottom - top
            val cropped = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
            cropped.setPixels(pixels, top * width + left, width, 0, 0, cropWidth, cropHeight)
            return cropped
        }

        return if (crop.customRect != null) {
            renderFreeCrop(pixels, width, height, crop, job)
        } else {
            renderRotatedCrop(pixels, width, height, crop, job)
        }
    }

    /**
     * Crops a free rectangle out of the rotated / straightened display frame.
     * [EditCrop.customRect] is normalized to that frame, so the output size is
     * the rectangle size in display pixels and the inverse rotation maps every
     * output pixel back into the source bitmap.
     */
    private suspend fun renderFreeCrop(
        pixels: IntArray,
        width: Int,
        height: Int,
        crop: EditCrop,
        job: Job?
    ): Bitmap {
        val state = crop.normalized()
        val rect = state.rect(width.toFloat(), height.toFloat())
        val displayWidth = state.displayWidth(width, height)
        val displayHeight = state.displayHeight(width, height)
        val outputWidth = (rect.width * displayWidth).roundToInt().coerceIn(1, max(width, displayWidth))
        val outputHeight = (rect.height * displayHeight).roundToInt().coerceIn(1, max(height, displayHeight))
        val scale = state.coverScale(width, height)
        val angle = Math.toRadians(state.effectiveAngleDegrees().toDouble()).toFloat()
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val sourceCenterX = (width - 1) * 0.5f
        val sourceCenterY = (height - 1) * 0.5f
        val frameCenterX = (displayWidth - 1) * 0.5f
        val frameCenterY = (displayHeight - 1) * 0.5f
        val left = rect.left * displayWidth
        val top = rect.top * displayHeight
        val output = IntArray(outputWidth * outputHeight)

        parallelRows(outputHeight) { from, to ->
            for (y in from until to) {
                checkCancelled(job)
                val dy = top + y - frameCenterY
                var outputIndex = y * outputWidth
                for (x in 0 until outputWidth) {
                    val dx = left + x - frameCenterX
                    val sourceX = sourceCenterX + (dx * cosAngle + dy * sinAngle) / scale
                    val sourceY = sourceCenterY + (-dx * sinAngle + dy * cosAngle) / scale
                    output[outputIndex] = sampleBilinear(pixels, width, height, sourceX, sourceY)
                    outputIndex++
                }
            }
        }

        return bitmapFrom(output, outputWidth, outputHeight)
    }

    /**
     * Rotates and crops in one pass. Output pixels are mapped back into the
     * source with the inverse rotation; the crop geometry is chosen so the
     * result never contains empty corners.
     */
    private suspend fun renderRotatedCrop(
        pixels: IntArray,
        width: Int,
        height: Int,
        crop: EditCrop,
        job: Job?
    ): Bitmap {
        val geometry = crop.geometry(width, height)
        val outputWidth = geometry.outputWidth
        val outputHeight = geometry.outputHeight
        val output = IntArray(outputWidth * outputHeight)
        val sourceCenterX = (width - 1) * 0.5f
        val sourceCenterY = (height - 1) * 0.5f
        val outputCenterX = (outputWidth - 1) * 0.5f
        val outputCenterY = (outputHeight - 1) * 0.5f
        val cosAngle = geometry.cosAngle
        val sinAngle = geometry.sinAngle

        parallelRows(outputHeight) { from, to ->
            for (y in from until to) {
                checkCancelled(job)
                val dy = y - outputCenterY
                var outputIndex = y * outputWidth
                for (x in 0 until outputWidth) {
                    val dx = x - outputCenterX
                    val sourceX = sourceCenterX + dx * cosAngle + dy * sinAngle
                    val sourceY = sourceCenterY - dx * sinAngle + dy * cosAngle
                    output[outputIndex] = sampleBilinear(pixels, width, height, sourceX, sourceY)
                    outputIndex++
                }
            }
        }

        return bitmapFrom(output, outputWidth, outputHeight)
    }

    private fun bitmapFrom(pixels: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
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
        val x0 = floor(sx).toInt().coerceIn(0, width - 1)
        val y0 = floor(sy).toInt().coerceIn(0, height - 1)
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

        return 0xFF000000.toInt() or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }

    // ---------------------------------------------------------------- plan

    private class Plan(
        val lut: IntArray,
        /** Red / green / blue LUTs, or null when no per-channel curve is active. */
        val channelLuts: Array<IntArray>?,
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
        val clarityRadius: Int,
        val adjustToneLut: IntArray?,
        val whiteBalance: FloatArray?,
        val vibrance: Float,
        val texture: Float,
        val adjustmentClarity: Float,
        val textureRadius: Int,
        val hsl: FloatArray?,
        val filter: FilterPlan?
    ) {
        val needsColor: Boolean
            get() = monochrome || abs(saturation - 1f) > 0.001f || hueMatrix != null ||
                blender != null || gradingChroma != null || hsl != null || filter != null

        val needsDetail: Boolean
            get() = abs(sharpenFine) > 0.01f || abs(sharpenClarity) > 0.01f ||
                abs(texture) > 0.01f || abs(adjustmentClarity) > 0.01f
    }

    private class FilterPlan(
        val toneLut: IntArray?,
        val whiteBalance: FloatArray?,
        val vibrance: Float,
        val saturation: Float,
        val monochrome: Float,
        val shadowTint: FloatArray?,
        val highlightTint: FloatArray?,
        val splitStrength: Float
    )

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
        // Per-channel curves are folded into a LUT once per preset, so the
        // per-pixel hot loop stays a single table lookup per channel.
        val channelLuts = preset.channelCurves
            .takeIf { !it.isNeutral }
            ?.let { buildChannelLuts(lut, it) }

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

        val adjustments = preset.adjustments.normalized()
        val temperature = adjustments.temperature / EditAdjustments.TONE_RANGE
        val tint = adjustments.tint / EditAdjustments.TONE_RANGE
        val whiteBalance = if (abs(temperature) > 0.001f || abs(tint) > 0.001f) {
            floatArrayOf(
                (1f + temperature * 0.12f + tint * 0.035f).coerceIn(0.65f, 1.4f),
                (1f + temperature * 0.004f - tint * 0.065f).coerceIn(0.65f, 1.4f),
                (1f - temperature * 0.12f + tint * 0.035f).coerceIn(0.65f, 1.4f)
            )
        } else null
        val hasToneAdjustment =
            abs(adjustments.exposure) > 0.001f ||
                abs(adjustments.contrast) > 0.001f ||
                abs(adjustments.highlights) > 0.001f ||
                abs(adjustments.shadows) > 0.001f ||
                abs(adjustments.whites) > 0.001f ||
                abs(adjustments.blacks) > 0.001f ||
                abs(adjustments.dehaze) > 0.001f
        val adjustToneLut = if (hasToneAdjustment) buildAdjustmentToneLut(adjustments) else null
        val vibrance = adjustments.vibrance / EditAdjustments.TONE_RANGE +
            adjustments.dehaze / EditAdjustments.TONE_RANGE * 0.18f
        val texture = adjustments.texture / EditAdjustments.TONE_RANGE
        val adjustmentClarity = adjustments.clarity / EditAdjustments.TONE_RANGE
        val hsl = buildUserHsl(adjustments)
        val filter = buildFilterPlan(adjustments)

        val maxDimension = max(width, height)
        val unit = (maxDimension / 1400f).coerceIn(0.35f, 4f)
        val sharp = preset.sharpening.coerceAtLeast(0f) / 9f
        val mid = preset.midRangeSharpening.coerceAtLeast(0f) / 5f
        val presetClarity = preset.clarity.coerceAtLeast(0f) / 5f
        val previewScale = if (forExport) 1f else 0.7f
        val fineAmount = (sharp * 1.10f + mid * 0.55f) * previewScale
        val clarityAmount = (presetClarity * 0.85f) * previewScale
        val clarityRadius = (5f * unit).roundToInt().coerceIn(2, 18)
        val textureRadius = (2.2f * unit).roundToInt().coerceIn(1, 6)

        return Plan(
            lut = lut,
            channelLuts = channelLuts,
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
            clarityRadius = clarityRadius,
            adjustToneLut = adjustToneLut,
            whiteBalance = whiteBalance,
            vibrance = vibrance,
            texture = texture,
            adjustmentClarity = adjustmentClarity,
            textureRadius = textureRadius,
            hsl = hsl,
            filter = filter
        )
    }

    /**
     * Builds the user-adjustment tone map. The preset's own LUT has already
     * been applied at this point, so this map only contains the extra exposure,
     * contrast, zone and dehaze controls.
     */
    private fun buildAdjustmentToneLut(adjustments: EditAdjustments): IntArray {
        val exposure = adjustments.exposure.coerceIn(
            -EditAdjustments.EXPOSURE_RANGE,
            EditAdjustments.EXPOSURE_RANGE
        )
        val exposureScale = 2f.pow(exposure)
        val contrast = adjustments.contrast / EditAdjustments.TONE_RANGE
        val highlights = adjustments.highlights / EditAdjustments.TONE_RANGE
        val shadows = adjustments.shadows / EditAdjustments.TONE_RANGE
        val whites = adjustments.whites / EditAdjustments.TONE_RANGE
        val blacks = adjustments.blacks / EditAdjustments.TONE_RANGE
        val dehaze = adjustments.dehaze / EditAdjustments.TONE_RANGE

        return IntArray(256) { i ->
            var x = i / 255f
            if (abs(exposure) > 0.001f) {
                // Exposure belongs in linear light; the rest of the pipeline is
                // display-referred, so convert in and out of gamma space.
                x = (x.pow(2.2f) * exposureScale).pow(1f / 2.2f)
            }
            if (abs(dehaze) > 0.001f) {
                x = ((x - 0.5f) * (1f + dehaze * 0.55f) + 0.5f).coerceIn(0f, 1f)
                if (dehaze > 0f) {
                    x -= dehaze * (1f - smoothstep(0f, 0.45f, x)) * 0.06f
                }
            }
            x = contrastCurve(x, contrast * 0.75f)
            if (abs(highlights) > 0.001f) {
                x += highlights * smoothstep(0.48f, 1f, x) * 0.24f
            }
            if (abs(shadows) > 0.001f) {
                x += shadows * (1f - smoothstep(0f, 0.52f, x)) * 0.24f
            }
            if (abs(whites) > 0.001f) {
                x += whites * smoothstep(0.70f, 1f, x) * 0.18f
            }
            if (abs(blacks) > 0.001f) {
                x += blacks * (1f - smoothstep(0f, 0.30f, x)) * 0.18f
            }
            (x.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    private fun buildUserHsl(adjustments: EditAdjustments): FloatArray? {
        if (adjustments.hsl.isNeutral()) return null
        val hsl = adjustments.hsl.normalized()
        return FloatArray(24) { index ->
            val band = hsl.band(index / 3)
            when (index % 3) {
                0 -> band.hue / EditAdjustments.TONE_RANGE
                1 -> band.saturation / EditAdjustments.TONE_RANGE
                else -> band.luminance / EditAdjustments.TONE_RANGE
            }
        }
    }

    private fun buildFilterPlan(adjustments: EditAdjustments): FilterPlan? {
        val choice = CreativeFilter.fromId(adjustments.creativeFilter)
        val strength = (adjustments.filterStrength / 100f).coerceIn(0f, 1f)
        if (choice.isNone || strength <= 0.001f) return null
        val profile = choice.profile

        val toneLut = buildFilterToneLut(profile, strength)
        val temperature = profile.temperature
        val tint = profile.tint
        val whiteBalance = if (abs(temperature) > 0.001f || abs(tint) > 0.001f) {
            floatArrayOf(
                1f + (temperature * 0.12f + tint * 0.035f) * strength,
                1f + (temperature * 0.004f - tint * 0.065f) * strength,
                1f + (-temperature * 0.12f + tint * 0.035f) * strength
            )
        } else null
        val vibrance = profile.vibrance * strength
        val saturation = (1f + profile.saturation * strength).coerceIn(0f, 2f)
        val monochrome = if (profile.monochrome) strength else 0f
        val shadowTint = profile.shadowTint?.let(::filterColorChannels)
        val highlightTint = profile.highlightTint?.let(::filterColorChannels)
        val splitStrength = profile.splitStrength * strength

        val hasColor = abs(vibrance) > 0.001f || abs(saturation - 1f) > 0.001f ||
            monochrome > 0.001f || shadowTint != null || highlightTint != null
        if (toneLut == null && whiteBalance == null && !hasColor) return null

        return FilterPlan(
            toneLut = toneLut,
            whiteBalance = whiteBalance,
            vibrance = vibrance,
            saturation = saturation,
            monochrome = monochrome,
            shadowTint = shadowTint,
            highlightTint = highlightTint,
            splitStrength = splitStrength
        )
    }

    private fun buildFilterToneLut(profile: FilterProfile, strength: Float): IntArray? {
        val hasTone = abs(profile.exposure) > 0.001f ||
            abs(profile.contrast) > 0.001f ||
            abs(profile.highlights) > 0.001f ||
            abs(profile.shadows) > 0.001f ||
            abs(profile.whites) > 0.001f ||
            abs(profile.blacks) > 0.001f ||
            abs(profile.fade) > 0.001f
        if (!hasTone) return null

        return IntArray(256) { i ->
            val original = i / 255f
            var x = original
            if (abs(profile.exposure) > 0.001f) {
                x = (x.pow(2.2f) * 2f.pow(profile.exposure)).pow(1f / 2.2f)
            }
            x = contrastCurve(x, profile.contrast * 0.8f)
            if (abs(profile.highlights) > 0.001f) {
                x += profile.highlights * smoothstep(0.48f, 1f, x) * 0.20f
            }
            if (abs(profile.shadows) > 0.001f) {
                x += profile.shadows * (1f - smoothstep(0f, 0.52f, x)) * 0.20f
            }
            if (abs(profile.whites) > 0.001f) {
                x += profile.whites * smoothstep(0.70f, 1f, x) * 0.14f
            }
            if (abs(profile.blacks) > 0.001f) {
                x += profile.blacks * (1f - smoothstep(0f, 0.30f, x)) * 0.14f
            }
            if (profile.fade > 0.001f) {
                x = x * (1f - profile.fade * 0.35f) + profile.fade * 0.10f
            }
            x = original + (x.coerceIn(0f, 1f) - original) * strength
            (x.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    private fun filterColorChannels(color: Int): FloatArray = floatArrayOf(
        ((color shr 16) and 0xFF) / 255f,
        ((color shr 8) and 0xFF) / 255f,
        (color and 0xFF) / 255f
    )

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
        val hslScratch = FloatArray(3)
        val low = plan.lut
        val channels = plan.channelLuts
        val redLut = channels?.get(0) ?: low
        val greenLut = channels?.get(1) ?: low
        val blueLut = channels?.get(2) ?: low
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
        val whiteBalance = plan.whiteBalance
        val adjustToneLut = plan.adjustToneLut
        val vibrance = plan.vibrance
        val userHsl = plan.hsl
        val filter = plan.filter

        for (y in from until to) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var index = y * width
            val end = index + width
            while (index < end) {
                val color = pixels[index]
                var r = redLut[(color shr 16) and 0xFF]
                var g = greenLut[(color shr 8) and 0xFF]
                var b = blueLut[color and 0xFF]

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
                } else {
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
                        rgbToHsl(r, g, b, hslScratch)
                        var h = hslScratch[0]
                        var s = hslScratch[1]
                        var l = hslScratch[2]
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
                }

                // User white balance is applied after the Picture Control
                // colour work so it can also tint a monochrome preset.
                if (whiteBalance != null) {
                    r = (r * whiteBalance[0]).roundToInt()
                    g = (g * whiteBalance[1]).roundToInt()
                    b = (b * whiteBalance[2]).roundToInt()
                }

                // Apply the extra exposure / contrast / zone adjustments through
                // luminance so hue and saturation stay close to the preset look.
                if (adjustToneLut != null) {
                    val luma = r * 0.299f + g * 0.587f + b * 0.114f
                    val toneIndex = luma.roundToInt().coerceIn(0, 255)
                    val mapped = adjustToneLut[toneIndex].toFloat()
                    if (abs(mapped - luma) > 0.01f) {
                        val scale = if (luma > 8f) mapped / luma else 1f
                        val lift = mapped - luma * scale
                        r = (r * scale + lift).roundToInt()
                        g = (g * scale + lift).roundToInt()
                        b = (b * scale + lift).roundToInt()
                    }
                }

                // User HSL is applied after the preset colour blender and before
                // vibrance so the two colour stages remain independent.
                if (userHsl != null && !mono) {
                    rgbToHsl(r, g, b, hslScratch)
                    var h = hslScratch[0]
                    var s = hslScratch[1]
                    var l = hslScratch[2]
                    var hueShift = 0f
                    var satAdd = 0f
                    var lumAdd = 0f
                    for (band in 0 until 8) {
                        var diff = abs(h - BAND_CENTERS[band])
                        if (diff > 180f) diff = 360f - diff
                        if (diff >= BAND_WIDTH) continue
                        val weight = 1f - diff / BAND_WIDTH
                        hueShift += userHsl[band * 3] * weight
                        satAdd += userHsl[band * 3 + 1] * weight
                        lumAdd += userHsl[band * 3 + 2] * weight
                    }
                    if (hueShift != 0f || satAdd != 0f || lumAdd != 0f) {
                        h = (h + hueShift * 30f + 360f) % 360f
                        s = (s * (1f + satAdd * 0.85f)).coerceIn(0f, 1f)
                        l = (l + lumAdd * 0.14f).coerceIn(0f, 1f)
                        val packed = hslToRgb(h, s, l)
                        r = (packed shr 16) and 0xFF
                        g = (packed shr 8) and 0xFF
                        b = packed and 0xFF
                    }
                }

                if (abs(vibrance) > 0.001f) {
                    val luma = r * 0.299f + g * 0.587f + b * 0.114f
                    val maxChannel = max(r, max(g, b)).toFloat()
                    val minChannel = min(r, min(g, b)).toFloat()
                    val chroma = (maxChannel - minChannel) / 255f
                    val amount = if (vibrance >= 0f) {
                        vibrance * (1f - chroma) * 0.85f
                    } else {
                        vibrance * 0.85f
                    }
                    if (abs(amount) > 0.001f) {
                        r = (luma + (r - luma) * (1f + amount)).roundToInt()
                        g = (luma + (g - luma) * (1f + amount)).roundToInt()
                        b = (luma + (b - luma) * (1f + amount)).roundToInt()
                    }
                }

                if (filter != null) {
                    if (filter.toneLut != null) {
                        val luma = r * 0.299f + g * 0.587f + b * 0.114f
                        val mapped = filter.toneLut[luma.roundToInt().coerceIn(0, 255)].toFloat()
                        if (abs(mapped - luma) > 0.01f) {
                            val scale = if (luma > 8f) mapped / luma else 1f
                            val lift = mapped - luma * scale
                            r = (r * scale + lift).roundToInt()
                            g = (g * scale + lift).roundToInt()
                            b = (b * scale + lift).roundToInt()
                        }
                    }

                    if (filter.whiteBalance != null) {
                        r = (r * filter.whiteBalance[0]).roundToInt()
                        g = (g * filter.whiteBalance[1]).roundToInt()
                        b = (b * filter.whiteBalance[2]).roundToInt()
                    }

                    if (abs(filter.vibrance) > 0.001f) {
                        val luma = r * 0.299f + g * 0.587f + b * 0.114f
                        val chroma = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
                        val amount = if (filter.vibrance >= 0f) {
                            filter.vibrance * (1f - chroma) * 0.85f
                        } else {
                            filter.vibrance * 0.85f
                        }
                        r = (luma + (r - luma) * (1f + amount)).roundToInt()
                        g = (luma + (g - luma) * (1f + amount)).roundToInt()
                        b = (luma + (b - luma) * (1f + amount)).roundToInt()
                    }

                    if (abs(filter.saturation - 1f) > 0.001f) {
                        val luma = r * 0.299f + g * 0.587f + b * 0.114f
                        r = (luma + (r - luma) * filter.saturation).roundToInt()
                        g = (luma + (g - luma) * filter.saturation).roundToInt()
                        b = (luma + (b - luma) * filter.saturation).roundToInt()
                    }

                    if (filter.monochrome > 0.001f) {
                        val luma = r * 0.299f + g * 0.587f + b * 0.114f
                        r = (r + (luma - r) * filter.monochrome).roundToInt()
                        g = (g + (luma - g) * filter.monochrome).roundToInt()
                        b = (b + (luma - b) * filter.monochrome).roundToInt()
                    }

                    if (filter.splitStrength > 0.001f &&
                        (filter.shadowTint != null || filter.highlightTint != null)
                    ) {
                        val luma = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                        val high = smoothstep(0.55f, 1f, luma)
                        val shadow = 1f - smoothstep(0f, 0.45f, luma)
                        var dr = 0f
                        var dg = 0f
                        var db = 0f
                        val shadowAmount = filter.splitStrength * shadow
                        val highlightAmount = filter.splitStrength * high
                        filter.shadowTint?.let { tint ->
                            dr += (tint[0] - 0.5f) * shadowAmount * 0.55f
                            dg += (tint[1] - 0.5f) * shadowAmount * 0.55f
                            db += (tint[2] - 0.5f) * shadowAmount * 0.55f
                        }
                        filter.highlightTint?.let { tint ->
                            dr += (tint[0] - 0.5f) * highlightAmount * 0.55f
                            dg += (tint[1] - 0.5f) * highlightAmount * 0.55f
                            db += (tint[2] - 0.5f) * highlightAmount * 0.55f
                        }
                        r = (r + dr * 255f).roundToInt()
                        g = (g + dg * 255f).roundToInt()
                        b = (b + db * 255f).roundToInt()
                    }
                }

                pixels[index] = pack(r, g, b)
                index++
            }
        }
    }

    // ------------------------------------------------------------- detail

    private fun applyDetail(pixels: IntArray, width: Int, height: Int, plan: Plan, job: Job?) {
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
        val clarity = plan.sharpenClarity + plan.adjustmentClarity * 0.85f
        val texture = plan.texture
        val radius = plan.clarityRadius
        val textureRadius = plan.textureRadius

        for (y in 0 until height) {
            if ((y and 0x0F) == 0) checkCancelled(job)
            var i = y * width
            for (x in 0 until width) {
                val center = (luma[i].toInt() and 0xFF).toFloat()
                var delta = 0f
                if (abs(fine) > 0.01f) {
                    val avg = neighborAverage(luma, width, height, x, y, 1)
                    delta += (center - avg) * fine * 1.35f
                }
                if (abs(texture) > 0.01f) {
                    val avg = neighborAverage(luma, width, height, x, y, textureRadius)
                    delta += (center - avg) * texture * 0.95f
                }
                if (abs(clarity) > 0.01f) {
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
