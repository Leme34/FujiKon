package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Objective, pixel-level acceptance tests for 一键优化.
 *
 * A synthetic photo with four measured defects (underexposed, cool cast, flat
 * contrast, muted colour) is rendered through the REAL [ImageRenderer] twice:
 * once neutral and once with the solved [EditAdjustments]. Every claim below
 * is measured from the resulting pixels, not from the parameter object.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoEnhanceTest {

    /**
     * A photo-like frame: sky / foliage / warm foreground colour zones, all
     * underexposed, low-contrast and covered by a global cool cast. Keeping
     * real chroma in each zone lets us verify colour survives white balance.
     */
    private fun problemBitmap(): Bitmap {
        val w = 160
        val h = 120
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = x / w.toFloat()
                val ny = y / h.toFloat()
                val radial = 1f - sqrt((nx - 0.5f) * (nx - 0.5f) + (ny - 0.5f) * (ny - 0.5f)) * 1.15f
                var l = 0.14f + 0.26f * radial.coerceIn(0f, 1f)
                l += 0.010f * sin(x * 0.55f) * cos(y * 0.42f)
                l = l.coerceIn(0.02f, 0.62f)
                val (cr, cg, cb) = when {
                    ny < 0.34f -> Triple(0.82f, 0.95f, 1.20f)  // blue sky
                    ny < 0.67f -> Triple(0.86f, 1.14f, 0.80f)  // green foliage
                    else -> Triple(1.18f, 0.96f, 0.80f)        // warm foreground
                }
                // Global cool cast on top of the real scene colours.
                val r = (l * cr * 0.93f * 255f).toInt().coerceIn(0, 255)
                val g = (l * cg * 0.99f * 255f).toInt().coerceIn(0, 255)
                val b = (l * cb * 1.08f * 255f).toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A well-exposed, neutral, detailed frame that must NOT be over-processed. */
    private fun goodBitmap(): Bitmap {
        val w = 160
        val h = 120
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = x / w.toFloat()
                val ny = y / h.toFloat()
                val checker = if (((x / 8) + (y / 8)) % 2 == 0) 0.16f else -0.16f
                val l = (0.48f + checker + 0.10f * sin(nx * 6f) * cos(ny * 5f)).coerceIn(0.05f, 0.95f)
                val r = (l * 1.02f * 255f).toInt().coerceIn(0, 255)
                val g = (l * 1.00f * 255f).toInt().coerceIn(0, 255)
                val b = (l * 0.98f * 255f).toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun report(tag: String, s: ImageStats): String =
        "$tag mean=${"%.3f".format(s.meanLuma)} std=${"%.3f".format(s.stdLuma)} " +
            "p5=${"%.3f".format(s.p5)} p95=${"%.3f".format(s.p95)} " +
            "warmth=${"%.3f".format(s.warmth)} sat=${"%.3f".format(s.meanSaturation)} " +
            "detail=${"%.4f".format(s.detail)} clipLo=${"%.3f".format(s.shadowClip)} " +
            "clipHi=${"%.3f".format(s.highlightClip)} quality=${"%.3f".format(s.toneQuality)}"

    @Test
    fun oneTapOptimisationMeasurablyImprovesRealRenderedPixels() = runBlocking {
        val source = problemBitmap()
        val stats = AutoEnhance.analyse(source)!!
        val result = AutoEnhance.suggest(stats)
        val renderer = ImageRenderer()
        val neutralPreset = Preset.none()

        val before = renderer.render(source, neutralPreset, forExport = false)
        val after = renderer.render(
            source,
            neutralPreset.copy(adjustments = result.adjustments),
            forExport = false
        )
        val beforeStats = AutoEnhance.analyse(before)!!
        val afterStats = AutoEnhance.analyse(after)!!

        println("DIAG " + report("source ", stats))
        println("DIAG " + report("before", beforeStats))
        println("DIAG " + report("after ", afterStats))
        println("DIAG adjustments=${result.adjustments.toJson()}")
        println("DIAG findings=${result.findings} summary=${result.summary} changed=${result.changed}")

        assertTrue("必须检出问题", result.changed)
        assertTrue("必须检出偏暗/偏蓝/反差偏平", result.findings.isNotEmpty())

        // Exposure moves toward photographic mid-grey (0.47).
        assertTrue(
            "亮度应更接近中灰: before=${beforeStats.meanLuma} after=${afterStats.meanLuma}",
            abs(afterStats.meanLuma - 0.47f) < abs(beforeStats.meanLuma - 0.47f) - 0.02f
        )
        // Contrast / tonal separation genuinely increases.
        assertTrue(
            "反差应提升: before=${beforeStats.stdLuma} after=${afterStats.stdLuma}",
            afterStats.stdLuma > beforeStats.stdLuma + 0.01f
        )
        // Grey-world white balance: the blue cast is reduced.
        assertTrue(
            "白平衡应更中性: before=${beforeStats.warmth} after=${afterStats.warmth}",
            abs(afterStats.warmth) < abs(beforeStats.warmth) - 0.02f
        )
        // Colour survives white-balance correction: not washed out, not overdone.
        assertTrue(
            "色彩不应被洗白: before=${beforeStats.meanSaturation} after=${afterStats.meanSaturation}",
            afterStats.meanSaturation > beforeStats.meanSaturation * 0.45f
        )
        assertTrue(
            "色彩不应过饱和: after=${afterStats.meanSaturation}",
            afterStats.meanSaturation < 0.70f
        )
        // Clarity / texture genuinely sharpen local detail.
        assertTrue(
            "细节应增强: before=${beforeStats.detail} after=${afterStats.detail}",
            afterStats.detail > beforeStats.detail
        )
        // No new highlight blow-out introduced by the brightening.
        assertTrue(
            "不应制造大面积高光溢出: before=${beforeStats.highlightClip} after=${afterStats.highlightClip}",
            afterStats.highlightClip < 0.12f
        )
        // Overall tonal quality score must improve markedly.
        assertTrue(
            "综合画质分应提升: before=${beforeStats.toneQuality} after=${afterStats.toneQuality}",
            afterStats.toneQuality > beforeStats.toneQuality + 0.10f
        )
    }

    @Test
    fun goodPhotosAreLeftAlone() {
        val stats = AutoEnhance.analyse(goodBitmap())!!
        val result = AutoEnhance.suggest(stats)
        println("DIAG " + report("good   ", stats))
        println("DIAG good changed=${result.changed} adjustments=${result.adjustments.toJson()}")
        assertFalse("已经均衡的照片不应被过度处理", result.changed)
        assertEquals(EditAdjustments.Neutral, result.adjustments)
    }

    @Test
    fun localPromptIsSpecificToTheMeasuredDefects() {
        val stats = AutoEnhance.analyse(problemBitmap())!!
        val prompt = AutoEnhance.describeForPrompt(stats)
        println("DIAG localPrompt=$prompt")
        assertTrue(prompt.length <= 500)
        assertTrue("应提到曝光", prompt.contains("曝光") || prompt.contains("暗部"))
        assertTrue("应提到白平衡或偏蓝", prompt.contains("白平衡") || prompt.contains("偏蓝"))
        assertTrue("必须要求保留构图", prompt.contains("保留原构图"))
    }
}
