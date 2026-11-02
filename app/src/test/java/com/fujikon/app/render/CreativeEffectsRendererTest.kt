package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.CreativeFilter
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.EditCrop
import com.fujikon.app.data.HslAdjustments
import com.fujikon.app.data.HslBand
import com.fujikon.app.data.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CreativeEffectsRendererTest {

    @Test
    fun nonePresetLeavesTheSourceUntouched() = runBlocking {
        val source = gradientBitmap(20, 16)
        val output = ImageRenderer().render(source, Preset.none(), forExport = false)

        assertEquals(source.width, output.width)
        assertEquals(source.height, output.height)
        assertArrayEquals(pixelsOf(source), pixelsOf(output))
    }

    @Test
    fun cropChangesTheOutputDimensionsAndPixels() = runBlocking {
        val source = gradientBitmap(40, 30)
        val preset = Preset.none().copy(
            adjustments = EditAdjustments(
                crop = EditCrop(aspect = "1:1", zoom = 1f)
            )
        )
        val output = ImageRenderer().render(source, preset, forExport = false)

        assertEquals(30, output.width)
        assertEquals(30, output.height)
        val sourcePixels = pixelsOf(source)
        val rect = preset.adjustments.crop.rect(40f, 30f)
        val left = kotlin.math.round(rect.left * 40f).toInt()
        assertEquals(sourcePixels[left], output.getPixel(0, 0))
    }

    @Test
    fun zeroStrengthCreativeFilterIsNeutral() = runBlocking {
        val source = gradientBitmap(24, 18)
        val base = ImageRenderer().render(source, Preset.none(), forExport = false)
        val filtered = ImageRenderer().render(
            source,
            Preset.none().copy(
                adjustments = EditAdjustments(
                    creativeFilter = CreativeFilter.FUJI.id,
                    filterStrength = 0f
                )
            ),
            forExport = false
        )

        assertArrayEquals(pixelsOf(base), pixelsOf(filtered))
    }

    @Test
    fun creativeFiltersActuallyChangeTheImage() = runBlocking {
        val source = gradientBitmap(24, 18)
        val base = ImageRenderer().render(source, Preset.none(), forExport = false)
        val fuji = ImageRenderer().render(
            source,
            Preset.none().copy(
                adjustments = EditAdjustments(
                    creativeFilter = CreativeFilter.FUJI.id,
                    filterStrength = 100f
                )
            ),
            forExport = false
        )

        assertTrue(differentPixelCount(base, fuji) > 20)
    }

    @Test
    fun monochromePortraitFilterReducesChannelSpread() = runBlocking {
        val source = colourfulBitmap(24, 18)
        val base = ImageRenderer().render(source, Preset.none(), forExport = false)
        val mono = ImageRenderer().render(
            source,
            Preset.none().copy(
                adjustments = EditAdjustments(
                    creativeFilter = CreativeFilter.PORTRAIT_MONO.id,
                    filterStrength = 100f
                )
            ),
            forExport = false
        )

        assertTrue(averageChannelSpread(mono) < averageChannelSpread(base) * 0.35f)
    }

    @Test
    fun hslBandSaturationCanDesaturateTheSelectedColour() = runBlocking {
        val source = colourfulBitmap(20, 16)
        val hsl = HslAdjustments.Neutral.withBand(
            0,
            HslBand(saturation = -100f)
        )
        val base = ImageRenderer().render(source, Preset.none(), forExport = false)
        val desaturated = ImageRenderer().render(
            source,
            Preset.none().copy(adjustments = EditAdjustments(hsl = hsl)),
            forExport = false
        )

        assertTrue(differentPixelCount(base, desaturated) > 20)
    }

    private fun gradientBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (30 + x * 8).coerceAtMost(255)
                val g = (40 + y * 9).coerceAtMost(255)
                val b = (70 + x * 3 + y).coerceAtMost(255)
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun colourfulBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        val colours = intArrayOf(
            0xFFD64040.toInt(),
            0xFFE39A3B.toInt(),
            0xFFD7C84A.toInt(),
            0xFF50A65A.toInt(),
            0xFF4AA5B8.toInt(),
            0xFF4D6FCC.toInt(),
            0xFF9A58C7.toInt()
        )
        for (i in pixels.indices) {
            pixels[i] = colours[i % colours.size]
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun differentPixelCount(a: Bitmap, b: Bitmap): Int {
        val pa = pixelsOf(a)
        val pb = pixelsOf(b)
        return pa.indices.count { pa[it] != pb[it] }
    }

    private fun averageChannelSpread(bitmap: Bitmap): Float {
        val pixels = pixelsOf(bitmap)
        var sum = 0f
        for (color in pixels) {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            sum += maxOf(r, g, b) - minOf(r, g, b)
        }
        return sum / pixels.size
    }
}
