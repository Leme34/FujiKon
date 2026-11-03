package com.fujikon.app.render

import android.app.Application
import com.fujikon.app.data.PortraitRetouch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PortraitProcessorTest {

    @Test
    fun smoothingRemovesSkinNoiseButLeavesTheBackgroundAlone() = runBlocking {
        val width = 240
        val height = 160
        val random = Random(11)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (x < width / 2) {
                    val noise = random.nextInt(-14, 15)
                    rgb(232 + noise, 184 + noise, 158 + noise)
                } else {
                    rgb(28, 62, 198)
                }
            }
        }
        val before = pixels.copyOf()

        applyPortrait(pixels, width, height, PortraitRetouch(smooth = 85f), null)

        val varianceBefore = lumaVariance(before, width, 8, width / 2 - 8, height)
        val varianceAfter = lumaVariance(pixels, width, 8, width / 2 - 8, height)
        assertTrue("skin should look smoother", varianceAfter < varianceBefore * 0.9f)

        // A saturated blue is not skin, so it must stay untouched even far from
        // the feathered boundary.
        for (y in 0 until height) {
            for (x in width / 2 + 16 until width) {
                assertEquals(before[y * width + x], pixels[y * width + x])
            }
        }
    }

    @Test
    fun whiteningBrightensSkin() = runBlocking {
        val width = 120
        val height = 80
        val pixels = IntArray(width * height) { rgb(198, 158, 138) }
        val lumaBefore = averageLuma(pixels, width, 0, width, height)

        applyPortrait(pixels, width, height, PortraitRetouch(whiten = 100f), null)

        val lumaAfter = averageLuma(pixels, width, 0, width, height)
        assertTrue("whitening must lift skin luminance", lumaAfter > lumaBefore + 5f)
    }

    @Test
    fun blushWarmsSkinTones() = runBlocking {
        val width = 120
        val height = 80
        val pixels = IntArray(width * height) { rgb(198, 158, 138) }

        applyPortrait(pixels, width, height, PortraitRetouch(blush = 100f), null)

        val px = pixels[0]
        val red = (px shr 16) and 0xFF
        val blue = px and 0xFF
        assertTrue("blush adds red", red > 198)
        assertTrue("blush removes blue", blue < 138)
        assertTrue(red - blue > 60)
    }

    @Test
    fun neutralRetouchLeavesPixelsUntouched() = runBlocking {
        val width = 120
        val height = 80
        val pixels = IntArray(width * height) { rgb(198, 158, 138) }
        val before = pixels.copyOf()

        applyPortrait(pixels, width, height, PortraitRetouch.Off, null)

        assertTrue(before.contentEquals(pixels))
    }

    private fun lumaVariance(
        pixels: IntArray,
        width: Int,
        fromX: Int,
        toX: Int,
        height: Int
    ): Float {
        var sum = 0f
        var sumSquares = 0f
        var count = 0
        for (y in 0 until height) {
            for (x in fromX until toX) {
                val l = luma(pixels[y * width + x])
                sum += l
                sumSquares += l * l
                count++
            }
        }
        val mean = sum / count
        return abs(sumSquares / count - mean * mean)
    }

    private fun averageLuma(
        pixels: IntArray,
        width: Int,
        fromX: Int,
        toX: Int,
        height: Int
    ): Float {
        var sum = 0f
        for (y in 0 until height) {
            for (x in fromX until toX) sum += luma(pixels[y * width + x])
        }
        return sum / (height * (toX - fromX))
    }

    private fun luma(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun rgb(r: Int, g: Int, b: Int): Int {
        val rr = r.coerceIn(0, 255)
        val gg = g.coerceIn(0, 255)
        val bb = b.coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
