package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * Acceptance tests for the TRUE one-tap closed loop.
 *
 * Every "after" number is measured from pixels of a REAL [ImageRenderer]
 * render — the same path the app uses for preview and export — not from the
 * parameter object or the LUT predictor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClosedLoopOptimizerTest {

    private fun bitmap(
        w: Int,
        h: Int,
        f: (nx: Float, ny: Float, x: Int, y: Int) -> Triple<Float, Float, Float>
    ): Bitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (r, g, b) = f(x / w.toFloat(), y / h.toFloat(), x, y)
                px[y * w + x] = (0xFF shl 24) or
                    ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
                    ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or
                    (b.coerceIn(0f, 1f) * 255).toInt()
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Outdoor snapshot with a badly blown-out sky. */
    private fun blownHighlights() = bitmap(160, 120) { _, ny, x, _ ->
        val j = 0.012f * sin(x * 0.5f)
        when {
            ny < 0.42f -> Triple(1.0f, 1.0f, 0.99f)
            ny < 0.70f -> Triple(0.30f + j, 0.48f + j, 0.26f + j)
            else -> Triple(0.86f + j, 0.70f + j, 0.32f + j)
        }
    }

    /** Underexposed frame with大面积 crushed blacks. */
    private fun crushedShadows() = bitmap(160, 120) { _, ny, x, y ->
        val j = 0.010f * sin(x * 0.4f) * cos(y * 0.3f)
        when {
            ny < 0.55f -> Triple(0.008f, 0.010f, 0.012f)
            else -> Triple(0.22f + j, 0.30f + j, 0.24f + j)
        }
    }

    /** Well exposed, neutral, detailed — must be left byte-identically alone. */
    private fun good() = bitmap(160, 120) { nx, ny, x, y ->
        val checker = if (((x / 8) + (y / 8)) % 2 == 0) 0.14f else -0.14f
        val l = (0.48f + checker + 0.08f * sin(nx * 6f) * cos(ny * 5f)).coerceIn(0.06f, 0.94f)
        Triple(l * 1.01f, l, l * 0.99f)
    }

    /** The reference 日常户外抓拍 scene the palette is tuned for. */
    private fun outdoor() = bitmap(160, 120) { nx, ny, _, _ ->
        val (r, g, b) = when {
            ny < 0.33f -> Triple(0.90f, 0.92f, 0.88f)
            ny < 0.66f -> Triple(0.26f, 0.40f, 0.24f)
            else -> Triple(0.82f, 0.66f, 0.30f)
        }
        val shade = if (nx < 0.28f && ny > 0.35f) 0.62f else 1f
        Triple(r * shade, g * shade, b * shade)
    }

    private suspend fun renderStats(source: Bitmap, a: EditAdjustments): ImageStats {
        val out = ImageRenderer().render(source, Preset.none().copy(adjustments = a), false)
        return AutoEnhance.analyse(out)!!
    }

    private fun fmt(s: ImageStats) =
        "mean=${"%.3f".format(s.meanLuma)} std=${"%.3f".format(s.stdLuma)} " +
            "spread=${"%.3f".format(s.spread)} hi=${"%.4f".format(s.highlightClip)} " +
            "lo=${"%.4f".format(s.shadowClip)} sat=${"%.3f".format(s.meanSaturation)} " +
            "q=${"%.3f".format(s.toneQuality)}"

    @Test
    fun blownHighlightsAreRecoveredByTheLoop() = runBlocking {
        val source = blownHighlights()
        val before = AutoEnhance.analyse(source)!!
        val result = AutoEnhance.optimize(source, Preset.none())
        val after = renderStats(source, result.adjustments)
        val r = result.report!!
        println("DIAG HL before=${fmt(before)}")
        println("DIAG HL after =${fmt(after)}")
        println("DIAG HL report=$r")
        println("DIAG HL adjustments=${result.adjustments.toJson()}")

        assertTrue("样张必须真的过曝", before.highlightClip > 0.10f)
        assertTrue("闭环必须至少真实渲染验证 1 轮", r.iterations >= 1)
        assertTrue("轮数不得超出上限", r.iterations <= OptimizationStandard.MAX_ITERATIONS)
        assertEquals("trail 必须逐轮记录", r.iterations, r.trail.size)
        assertTrue("闭环应收敛到最优标准", r.converged)
        assertTrue("缺陷应逐轮收敛: ${r.trail}", r.trail.last() <= r.trail.first() + 0.001f)
        assertTrue(
            "最终真实渲染不得过曝: hi=${after.highlightClip}",
            after.highlightClip <= OptimizationStandard.MAX_HIGHLIGHT_CLIP + 0.01f
        )
        assertTrue(
            "高光剪切必须显著改善: ${before.highlightClip} -> ${after.highlightClip}",
            after.highlightClip < before.highlightClip * 0.5f
        )
    }

    @Test
    fun crushedShadowsAreRecoveredByTheLoop() = runBlocking {
        val source = crushedShadows()
        val before = AutoEnhance.analyse(source)!!
        val result = AutoEnhance.optimize(source, Preset.none())
        val after = renderStats(source, result.adjustments)
        val r = result.report!!
        println("DIAG SH before=${fmt(before)}")
        println("DIAG SH after =${fmt(after)}")
        println("DIAG SH report=$r")
        println("DIAG SH adjustments=${result.adjustments.toJson()}")

        assertTrue("样张必须真的死黑", before.shadowClip > 0.10f)
        assertTrue("闭环必须至少真实渲染验证 1 轮", r.iterations >= 1)
        assertEquals("trail 必须逐轮记录", r.iterations, r.trail.size)
        assertTrue("闭环应收敛到最优标准", r.converged)
        assertTrue("缺陷应逐轮收敛: ${r.trail}", r.trail.last() <= r.trail.first() + 0.001f)
        assertTrue(
            "最终真实渲染不得死黑: lo=${after.shadowClip}",
            after.shadowClip <= OptimizationStandard.MAX_SHADOW_CLIP + 0.01f
        )
        assertTrue(
            "暗部剪切必须显著改善: ${before.shadowClip} -> ${after.shadowClip}",
            after.shadowClip < before.shadowClip * 0.5f
        )
        // The report is what the loop measured on its probe; it must agree
        // with an independent full-size render of the returned parameters.
        assertTrue(
            "闭环报告须与真实渲染一致: report=${r.shadowClip} real=${after.shadowClip}",
            kotlin.math.abs(r.shadowClip - after.shadowClip) < 0.01f
        )

        // This frame needs several rounds, so refine() really ran. Prove it
        // changed TONE only and left the whole four-step recipe untouched.
        val s = AutoEnhance.suggestZoned(source, AutoEnhance.analyse(source)!!).adjustments
        val l = result.adjustments
        assertTrue(
            "闭环必须真的迭代修改了光感参数",
            l.exposure != s.exposure || l.contrast != s.contrast ||
                l.highlights != s.highlights || l.shadows != s.shadows ||
                l.whites != s.whites || l.blacks != s.blacks
        )
        assertEquals("HSL 配色不得被改写", s.hsl.toJson().toString(), l.hsl.toJson().toString())
        assertEquals("暗部蓝调不得被改写", s.shadowTint, l.shadowTint, 0.001f)
        assertEquals("色温不得被改写", s.temperature, l.temperature, 0.001f)
        assertEquals("纹理不得被改写", s.texture, l.texture, 0.001f)
        assertEquals("清晰度（微柔焦）不得被改写", s.clarity, l.clarity, 0.001f)
        assertEquals("分区局部层不得被改写", s.local, l.local)
        assertEquals("主曲线不得被改写", s.toneCurve, l.toneCurve)
    }

    @Test
    fun goodFramesShortCircuitToExactNeutral() = runBlocking {
        val source = good()
        val result = AutoEnhance.optimize(source, Preset.none())
        println("DIAG GOOD report=${result.report} changed=${result.changed}")
        assertEquals(0, result.report!!.iterations)
        assertTrue(result.report!!.converged)
        assertEquals(EditAdjustments.Neutral, result.adjustments)
        assertFalse(result.changed)
    }

    @Test
    fun closedLoopIsIdempotent() = runBlocking {
        val source = blownHighlights()
        val first = AutoEnhance.optimize(source, Preset.none())
        val second = AutoEnhance.optimize(source, Preset.none(), first.adjustments)
        println("DIAG IDEM first =${first.adjustments.toJson()}")
        println("DIAG IDEM second=${second.adjustments.toJson()}")
        assertEquals("连点两次一键优化不得累加、不得漂移", first.adjustments, second.adjustments)
    }

    @Test
    fun closedLoopNeverRewritesTheFourStepRecipe() = runBlocking {
        val source = outdoor()
        val seed = AutoEnhance.suggestZoned(source, AutoEnhance.analyse(source)!!)
        val loop = AutoEnhance.optimize(source, Preset.none())
        val s = seed.adjustments
        val l = loop.adjustments
        println("DIAG RECIPE report=${loop.report}")
        assertEquals("青蓝绿配柔黄 HSL 不得被闭环改写", s.hsl.toJson().toString(), l.hsl.toJson().toString())
        assertEquals("暗部蓝调不得被改写", s.shadowTint, l.shadowTint, 0.001f)
        assertEquals("色温不得被改写", s.temperature, l.temperature, 0.001f)
        assertEquals("自然饱和度不得被改写", s.vibrance, l.vibrance, 0.001f)
        assertEquals("纹理（保细节）不得被改写", s.texture, l.texture, 0.001f)
        assertEquals("清晰度（微柔焦）不得被改写", s.clarity, l.clarity, 0.001f)
        assertEquals("分区局部层不得被改写", s.local, l.local)
        assertEquals("主曲线不得被改写", s.toneCurve, l.toneCurve)
    }

    @Test
    fun missingBitmapDegradesGracefully() = runBlocking {
        val r = AutoEnhance.optimize(null, Preset.none())
        assertFalse(r.changed)
        assertNull(r.report)
    }
}
