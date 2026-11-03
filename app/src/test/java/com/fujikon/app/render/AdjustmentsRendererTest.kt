package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.CropRect
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.EditCrop
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.PictureControlParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdjustmentsRendererTest {

    @Test
    fun neutralAdjustmentsMatchTheOriginalRender() = runBlocking {
        val source = sourceBitmap()
        val preset = samplePreset()
        val renderer = ImageRenderer()

        val base = renderer.render(source, preset, forExport = false)
        val neutral = renderer.render(
            source,
            preset.copy(adjustments = EditAdjustments.Neutral),
            forExport = false
        )

        assertEquals(averageLuma(base), averageLuma(neutral), 0.001f)
    }

    @Test
    fun positiveExposureBrightensThePreview() = runBlocking {
        val source = sourceBitmap()
        val preset = samplePreset()
        val renderer = ImageRenderer()

        val base = renderer.render(source, preset, forExport = false)
        val brighter = renderer.render(
            source,
            preset.copy(adjustments = EditAdjustments(exposure = 1f)),
            forExport = false
        )

        assertTrue(averageLuma(brighter) > averageLuma(base) + 8f)
    }

    @Test
    fun warmTemperatureAddsRedAndRemovesBlue() = runBlocking {
        val source = sourceBitmap()
        val preset = samplePreset()
        val renderer = ImageRenderer()

        val base = renderer.render(source, preset, forExport = false)
        val warm = renderer.render(
            source,
            preset.copy(adjustments = EditAdjustments(temperature = 80f)),
            forExport = false
        )

        assertTrue(averageRed(warm) - averageBlue(warm) > averageRed(base) - averageBlue(base))
    }

    @Test
    fun localAndColourAdjustmentsProduceAValidBitmap() = runBlocking {
        val source = sourceBitmap()
        val preset = samplePreset().copy(
            adjustments = EditAdjustments(
                temperature = 20f,
                tint = -12f,
                vibrance = 35f,
                exposure = 0.3f,
                contrast = 18f,
                highlights = -20f,
                shadows = 25f,
                whites = 8f,
                blacks = -10f,
                texture = 30f,
                clarity = 20f,
                dehaze = 12f
            )
        )

        val output = ImageRenderer().render(source, preset, forExport = false)

        assertEquals(source.width, output.width)
        assertEquals(source.height, output.height)
        assertTrue(output.getPixel(output.width / 2, output.height / 2) != 0)
    }

    @Test
    fun freeCropProducesTheRequestedPixelSize() = runBlocking {
        val cropped = ImageRenderer().crop(
            sourceBitmap(),
            EditCrop(customRect = CropRect(0.25f, 0.25f, 0.75f, 0.75f))
        )

        assertEquals(12, cropped.width)
        assertEquals(12, cropped.height)
    }

    private fun samplePreset(): Preset {
        val bytes = javaClass.classLoader!!.getResourceAsStream("agfa_optima.ncp")!!.readBytes()
        val control = PictureControlParser.parse(bytes, "agfa_optima.ncp")
        return Preset.from(
            control = control,
            id = "render-test",
            fileName = "agfa_optima.ncp",
            fileSize = bytes.size,
            isSample = true
        )
    }

    private fun sourceBitmap(): Bitmap {
        val width = 24
        val height = 24
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (35 + x * 6).coerceAtMost(255)
                val g = (45 + y * 5).coerceAtMost(255)
                val b = (65 + x * 2 + y).coerceAtMost(255)
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun averageLuma(bitmap: Bitmap): Float {
        var sum = 0.0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { color ->
            sum += ((color shr 16) and 0xFF) * 0.299 +
                ((color shr 8) and 0xFF) * 0.587 +
                (color and 0xFF) * 0.114
        }
        return (sum / pixels.size).toFloat()
    }

    private fun averageRed(bitmap: Bitmap): Float = averageChannel(bitmap, 16)

    private fun averageBlue(bitmap: Bitmap): Float = averageChannel(bitmap, 0)

    private fun averageChannel(bitmap: Bitmap, shift: Int): Float {
        var sum = 0L
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { color -> sum += (color shr shift) and 0xFF }
        return sum.toFloat() / pixels.size
    }
}
