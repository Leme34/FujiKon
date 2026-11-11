package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.nic.WorkStyle16Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 「预设强度」回归：强度是最终像素与原图的线性混合，因此 0 = 精确原图、
 * 100 = 现有满效果、中间值逐通道线性，且绝不拉伸 RGB 比例（不偏色）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PresetStrengthRendererTest {

    private val renderer = ImageRenderer()
    private val preset = WorkStyle16Preset.toPreset()

    private fun render(source: Bitmap, strength: Float): Bitmap = runBlocking {
        renderer.render(
            source,
            preset.copy(
                adjustments = preset.adjustments.copy(presetStrength = strength)
            ),
            forExport = false
        )
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val out = IntArray(bmp.width * bmp.height)
        bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return out
    }

    private fun gradient(): Bitmap {
        val w = 32
        val h = 32
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (20 + x * 7).coerceAtMost(255)
            val g = (40 + y * 6).coerceAtMost(255)
            val b = (60 + (x + y) * 3).coerceAtMost(255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun zeroStrengthReturnsTheExactOriginal() {
        val src = gradient()
        assertArrayEquals(pixels(src), pixels(render(src, 0f)))
    }

    @Test
    fun fullStrengthIsIdenticalToTheLegacyDefault() = runBlocking {
        val src = gradient()
        val explicit = renderer.render(
            src,
            preset.copy(adjustments = preset.adjustments.copy(presetStrength = 100f)),
            forExport = false
        )
        val legacy = renderer.render(src, preset, forExport = false)
        assertArrayEquals(pixels(explicit), pixels(legacy))
    }

    @Test
    fun fullLookActuallyChangesPixels() {
        val src = gradient()
        val origin = pixels(src)
        val full = pixels(render(src, 100f))
        var changed = 0
        for (i in origin.indices) {
            if (origin[i] != full[i]) changed++
        }
        assertTrue("满强度预设必须真的改变画面", changed > origin.size / 4)
    }

    @Test
    fun midStrengthIsAPerChannelLinearBlend() {
        val src = gradient()
        val origin = pixels(src)
        val full = pixels(render(src, 100f))
        val half = pixels(render(src, 50f))
        for (i in origin.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val a = (origin[i] shr shift) and 0xFF
                val b = (full[i] shr shift) and 0xFF
                val m = (half[i] shr shift) and 0xFF
                val expected = (a + b) / 2f
                assertTrue(
                    "像素 $i 通道 $shift 未线性混合: $a / $m / $b",
                    abs(m - expected) <= 1.5f
                )
            }
        }
    }

    @Test
    fun greysStayNeutralAtEveryStrength() {
        for (v in listOf(0, 32, 96, 160, 224, 255)) {
            for (s in listOf(0f, 25f, 50f, 75f, 100f)) {
                val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                bmp.setPixel(0, 0, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
                val out = render(bmp, s).getPixel(0, 0)
                val r = (out shr 16) and 0xFF
                val g = (out shr 8) and 0xFF
                val b = out and 0xFF
                // 端到端含肤色感知人像层，允许 < 3/255 的通道差（肉眼不可见）。
                assertTrue(
                    "灰阶 $v @ 强度 $s 被染成 ($r,$g,$b)",
                    maxOf(r, g, b) - minOf(r, g, b) <= 3
                )
            }
        }
    }

    @Test
    fun strengthIsClampedAndCountsAsAnAdjustment() {
        assertEquals(100f, EditAdjustments(presetStrength = 500f).normalized().presetStrength, 0f)
        assertEquals(0f, EditAdjustments(presetStrength = -20f).normalized().presetStrength, 0f)
        assertTrue(EditAdjustments().isNeutral())
        assertTrue(!EditAdjustments(presetStrength = 60f).isNeutral())
        assertEquals(100f, EditAdjustments.Neutral.presetStrength, 0f)
    }

    @Test
    fun strengthSurvivesJsonRoundTrip() {
        val source = EditAdjustments(exposure = 0.4f, presetStrength = 42f)
        val parsed = EditAdjustments.fromJson(source.toJson())
        assertEquals(42f, parsed.presetStrength, 0.001f)
        assertEquals(0.4f, parsed.exposure, 0.001f)
        // Old JSON without the key still means "full look".
        val legacy = source.toJson().apply { remove("presetStrength") }
        assertEquals(100f, EditAdjustments.fromJson(legacy).presetStrength, 0f)
    }
}
