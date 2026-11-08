package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.CreativeFilter
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pixel-level acceptance for the 写真 (studio portrait) composite look.
 *
 * 1.6.20 washed the subject out into a grey haze: lifted blacks, faded
 * contrast, global de-saturation, negative clarity/texture and heavy
 * smoothing. These tests are the guardrails for the reworked recipe — a
 * solid black point, preserved contrast/chroma, real skin micro-texture and
 * a still-brighter, whiter skin tone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PortraitStudioRendererTest {

    private val renderer = ImageRenderer()
    private val studio = CreativeFilter.PORTRAIT_STUDIO.id

    @Test
    fun zeroStrengthIsPixelIdentical() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val zero = renderer.render(
            source,
            Preset.none().copy(
                adjustments = EditAdjustments(creativeFilter = studio, filterStrength = 0f)
            ),
            forExport = false
        )
        assertArrayEquals(pixelsOf(base), pixelsOf(zero))
    }

    @Test
    fun studioLookActuallyChangesTheImage() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val filtered = renderStudio(source)
        assertTrue(differentPixelCount(base, filtered) > 100)
    }

    @Test
    fun keepsBlackPointSolidAndDoesNotBlowWhites() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val filtered = renderStudio(source)

        val blackBefore = meanLuma(base, Patch.BLACK)
        val blackAfter = meanLuma(filtered, Patch.BLACK)
        // The black point must NOT be lifted into a milky grey. 1.6.20 raised
        // a level-18 black to ~32; the reworked grade keeps it dark.
        assertTrue("black point stayed dark: $blackBefore -> $blackAfter",
            blackAfter < blackBefore + 8f)
        assertTrue("black patch stays dark: $blackAfter", blackAfter < 60f)

        // Positive texture + the hard band edges of this synthetic patchwork
        // can push a handful of boundary pixels to 255. The real reference
        // portraits clip <0.001% and keep full dress detail, so the guardrail
        // targets a washed-out white FIELD and large dead-white areas rather
        // than isolated edge pixels.
        val whiteField = meanLuma(filtered, Patch.WHITE)
        assertTrue("white field must retain detail: $whiteField", whiteField < 248f)
        val whiteBefore = pureWhiteCount(base)
        val whiteAfter = pureWhiteCount(filtered)
        assertTrue("no large dead-white areas: $whiteBefore -> $whiteAfter", whiteAfter <= 60)
    }

    @Test
    fun preservesGlobalContrastInsteadOfFlatteningIt() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val filtered = renderStudio(source)
        val before = meanLuma(base, Patch.WHITE) - meanLuma(base, Patch.BLACK)
        val after = meanLuma(filtered, Patch.WHITE) - meanLuma(filtered, Patch.BLACK)
        // 1.6.20 compressed the white/black span to ~89%; rolling off the
        // highlights to avoid clipped whites costs a little, but the span
        // must stay well above a washed-out grade.
        assertTrue("contrast must not collapse: $before -> $after",
            after >= before * 0.93f)
    }

    @Test
    fun keepsChromaInsteadOfGreyingTheFrame() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val filtered = renderStudio(source)
        val before = globalSaturation(base)
        val after = globalSaturation(filtered)
        // 1.6.20 lost ~25% chroma (global de-saturation + whiten + all-eight
        // HSL cuts). A small drop from the whitening step is expected and is
        // the price of keeping skin fair; a wash-out is not.
        assertTrue("global chroma must not be washed out: $before -> $after",
            after >= before * 0.88f)
    }

    @Test
    fun preservesSkinMicroTexture() = runBlocking {
        val source = texturedSkin()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val filtered = renderStudio(source)
        val before = microContrast(base)
        val after = microContrast(filtered)
        // Positive texture + contour payback must keep pores / fine detail.
        // Negative clarity/texture + heavy smoothing used to flatten them.
        assertTrue("skin micro-texture must survive: $before -> $after",
            after >= before * 0.85f)
    }

    @Test
    fun brightensSkin() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val filtered = renderStudio(source)
        val before = meanLuma(base, Patch.SKIN)
        val after = meanLuma(filtered, Patch.SKIN)
        assertTrue("skin should still brighten (whitening): $before -> $after", after > before)
    }

    @Test
    fun deSaturatesAndCoolsFoliage() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val filtered = renderStudio(source)
        val before = meanSaturation(base, Patch.GREEN)
        val after = meanSaturation(filtered, Patch.GREEN)
        assertTrue("greens should de-saturate: $before -> $after", after < before)
        // Cyan shift: blue rises relative to red on the foliage patch.
        val beforeGap = meanB(base, Patch.GREEN) - meanR(base, Patch.GREEN)
        val afterGap = meanB(filtered, Patch.GREEN) - meanR(filtered, Patch.GREEN)
        assertTrue("greens should shift toward cyan: $beforeGap -> $afterGap", afterGap > beforeGap)
    }

    @Test
    fun lowerStrengthIsCloserToTheOriginal() = runBlocking {
        val source = patchwork()
        val base = renderer.render(source, Preset.none(), forExport = false)
        val full = renderStudio(source, 100f)
        val soft = renderStudio(source, 35f)
        // Compare accumulated colour distance, not "different pixel count" —
        // a global tone LUT nudges nearly every pixel at any strength.
        assertTrue(totalDifference(base, soft) < totalDifference(base, full))
    }

    private suspend fun renderStudio(source: Bitmap, strength: Float = 100f): Bitmap =
        renderer.render(
            source,
            Preset.none().copy(
                adjustments = EditAdjustments(creativeFilter = studio, filterStrength = strength)
            ),
            forExport = false
        )

    /** Five horizontal bands, each a flat colour, with a thin gradient overlay. */
    private fun patchwork(): Bitmap {
        val w = 48
        val h = 40
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val patch = Patch.entries[y * Patch.entries.size / h]
            for (x in 0 until w) {
                val jitter = ((x % 5) - 2)
                val r = (patch.rgb[0] + jitter).coerceIn(0, 255)
                val g = (patch.rgb[1] + jitter).coerceIn(0, 255)
                val b = (patch.rgb[2] + jitter).coerceIn(0, 255)
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A skin-tone field carrying a fine 2x2 checkerboard "pore" texture. */
    private fun texturedSkin(): Bitmap {
        val w = 64
        val h = 48
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = if (((x / 2) + (y / 2)) % 2 == 0) 11 else -11
                val r = (200 + t).coerceIn(0, 255)
                val g = (150 + t).coerceIn(0, 255)
                val b = (130 + t).coerceIn(0, 255)
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private enum class Patch(val rgb: IntArray) {
        BLACK(intArrayOf(18, 20, 22)),
        WHITE(intArrayOf(242, 240, 238)),
        SKIN(intArrayOf(200, 150, 130)),
        GREEN(intArrayOf(70, 150, 80)),
        WOOD(intArrayOf(150, 100, 70));
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun bandRange(bitmap: Bitmap, patch: Patch): IntRange {
        val h = bitmap.height
        val index = patch.ordinal
        val from = index * h / Patch.entries.size + 2
        val to = (index + 1) * h / Patch.entries.size - 2
        return from.coerceAtLeast(0)..to.coerceAtMost(h - 1)
    }

    private fun channelMean(bitmap: Bitmap, patch: Patch, channel: Int): Float {
        val pixels = pixelsOf(bitmap)
        val w = bitmap.width
        var sum = 0f
        var count = 0
        for (y in bandRange(bitmap, patch)) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val shift = when (channel) { 0 -> 16; 1 -> 8; else -> 0 }
                sum += (c shr shift) and 0xFF
                count++
            }
        }
        return sum / count
    }

    private fun meanR(b: Bitmap, p: Patch) = channelMean(b, p, 0)
    private fun meanB(b: Bitmap, p: Patch) = channelMean(b, p, 2)

    private fun meanLuma(bitmap: Bitmap, patch: Patch): Float =
        0.299f * channelMean(bitmap, patch, 0) +
            0.587f * channelMean(bitmap, patch, 1) +
            0.114f * channelMean(bitmap, patch, 2)

    private fun meanSaturation(bitmap: Bitmap, patch: Patch): Float {
        val pixels = pixelsOf(bitmap)
        val w = bitmap.width
        var sum = 0f
        var count = 0
        for (y in bandRange(bitmap, patch)) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                sum += if (max == 0) 0f else (max - min).toFloat() / max
                count++
            }
        }
        return sum / count
    }

    private fun globalSaturation(bitmap: Bitmap): Float {
        val pixels = pixelsOf(bitmap)
        var sum = 0f
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            sum += if (max == 0) 0f else (max - min).toFloat() / max
        }
        return sum / pixels.size
    }

    /** Mean absolute luma step between neighbours = fine detail energy. */
    private fun microContrast(bitmap: Bitmap): Float {
        val pixels = pixelsOf(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        var sum = 0f
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = pixels[y * w + x]
                sum += kotlin.math.abs(lumaOf(c) - lumaOf(pixels[y * w + x + 1]))
                sum += kotlin.math.abs(lumaOf(c) - lumaOf(pixels[(y + 1) * w + x]))
                count += 2
            }
        }
        return sum / count
    }

    private fun lumaOf(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun pureWhiteCount(bitmap: Bitmap): Int {
        val pixels = pixelsOf(bitmap)
        return pixels.count { c ->
            ((c shr 16) and 0xFF) >= 254 &&
                ((c shr 8) and 0xFF) >= 254 &&
                (c and 0xFF) >= 254
        }
    }

    private fun differentPixelCount(a: Bitmap, b: Bitmap): Int {
        val pa = pixelsOf(a)
        val pb = pixelsOf(b)
        return pa.indices.count { pa[it] != pb[it] }
    }

    /** Sum of per-channel absolute differences; a stable "how far" measure. */
    private fun totalDifference(a: Bitmap, b: Bitmap): Long {
        val pa = pixelsOf(a)
        val pb = pixelsOf(b)
        var sum = 0L
        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            sum += kotlin.math.abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
            sum += kotlin.math.abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF))
            sum += kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))
        }
        return sum
    }
}
