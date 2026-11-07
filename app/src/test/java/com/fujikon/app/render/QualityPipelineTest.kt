package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.PointColor
import com.fujikon.app.data.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Pixel-level tests for the new quality / local / zoned-enhance pipeline.
 * Every assertion reads back real IntArray pixels, not parameter objects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QualityPipelineTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    private fun lum(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r * 0.299f + g * 0.587f + b * 0.114f
    }

    private fun flat(w: Int, h: Int, color: Int): IntArray = IntArray(w * h) { color }

    // ------------------------------------------------------------- denoise

    @Test
    fun denoiseReducesNoiseButPreservesAStrongEdge() {
        val w = 40; val h = 40
        val px = IntArray(w * h)
        var seed = 12345L
        fun noise(): Int {
            seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
            return (seed % 41).toInt() - 20
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = if (x < w / 2) 100 else 220
                px[y * w + x] = rgb(
                    (base + noise()).coerceIn(0, 255),
                    (base + noise()).coerceIn(0, 255),
                    (base + noise()).coerceIn(0, 255)
                )
            }
        }
        val before = px.copyOf()

        fun variance(array: IntArray, from: Int, to: Int): Double {
            var sum = 0.0; var sum2 = 0.0; var n = 0
            for (y in 5 until h - 5) {
                for (x in from until to) {
                    val v = lum(array[y * w + x]).toDouble()
                    sum += v; sum2 += v * v; n++
                }
            }
            val mean = sum / n
            return sum2 / n - mean * mean
        }

        val varBefore = variance(before, 2, w / 2 - 2)
        QualityOperators.applyDenoise(px, w, h, 100f, null)
        val varAfter = variance(px, 2, w / 2 - 2)
        assertTrue("方差应下降: $varBefore -> $varAfter", varAfter < varBefore * 0.75)

        // The step across the hard edge must survive the bilateral filter.
        val left = lum(px[20 * w + (w / 2 - 3)])
        val right = lum(px[20 * w + (w / 2 + 3)])
        assertTrue("强边缘不应被抹平: $left vs $right", right - left > 80f)
    }

    // --------------------------------------------------------------- grain

    @Test
    fun grainIsDeterministicAndStrongerInMidTones() {
        val w = 32; val h = 32
        val base = IntArray(w * h) { i -> if (i % (w * h) < (w * h) / 2) rgb(120, 120, 120) else rgb(245, 245, 245) }
        val first = base.copyOf()
        val second = base.copyOf()
        QualityOperators.applyGrain(first, w, h, 80f, null)
        QualityOperators.applyGrain(second, w, h, 80f, null)
        assertTrue("颗粒必须确定性（预览/导出一致）", first.contentEquals(second))

        var midDelta = 0f; var highDelta = 0f
        for (i in base.indices) {
            val d = abs(lum(first[i]) - lum(base[i]))
            if (base[i] == rgb(120, 120, 120)) midDelta += d else highDelta += d
        }
        assertTrue("中间调颗粒应强于高光: $midDelta vs $highDelta", midDelta > highDelta)
    }

    // ------------------------------------------------------------ vignette

    @Test
    fun vignetteDarkensCornersButNotCentre() {
        val w = 64; val h = 64
        val px = flat(w, h, rgb(180, 180, 180))
        QualityOperators.applyVignette(px, w, h, -100f, null)
        assertTrue("四角应变暗", lum(px[0]) < 160f)
        assertEquals("中心应保持不变", 180f, lum(px[(h / 2) * w + w / 2]), 1.5f)
    }

    // ---------------------------------------------------------- point color

    @Test
    fun pointColorOnlyTouchesTheSelectedHue() {
        val w = 16; val h = 16
        val px = IntArray(w * h) { i -> if (i < w * h / 2) rgb(40, 80, 200) else rgb(210, 50, 40) }
        val blueBefore = px[0]
        val redBefore = px[w * h - 1]
        QualityOperators.applyPointColor(
            px, w, h,
            PointColor(targetHue = 220f, hueRange = 35f, saturation = 60f),
            null
        )
        assertTrue("蓝色应被修改", px[0] != blueBefore)
        assertEquals("红色不应被修改", redBefore, px[w * h - 1])
    }

    // ---------------------------------------------------------------- masks

    @Test
    fun controlPointMaskFallsOffFromCentre() {
        val grid = LocalMaskRenderer.buildGrid(LocalMask.ControlPoint(0.5f, 0.5f, 0.3f, 0.7f), 100, 100)
        assertTrue("中心应接近 1", grid.sample(0.5f, 0.5f) > 0.95f)
        assertTrue("角落应接近 0", grid.sample(0f, 0f) < 0.05f)
    }

    @Test
    fun radialInvertAndLinearGradientBehave() {
        val radial = LocalMaskRenderer.buildGrid(
            LocalMask.RadialGradient(0.5f, 0.5f, 0.25f, 0.25f, 0.6f, invert = true), 100, 100
        )
        assertTrue("反选径向：中心为 0", radial.sample(0.5f, 0.5f) < 0.05f)
        assertTrue("反选径向：角落为 1", radial.sample(0f, 0f) > 0.9f)

        val linear = LocalMaskRenderer.buildGrid(
            LocalMask.LinearGradient(0.5f, 0f, 0.5f, 1f, 0.4f, invert = true), 100, 100
        )
        assertTrue("线性渐变上部权重更高", linear.sample(0.5f, 0.05f) > linear.sample(0.5f, 0.95f))
    }

    @Test
    fun brushMaskPaintsOnlyTheStroke() {
        val grid = LocalMaskRenderer.buildGrid(
            LocalMask.Brush(listOf(com.fujikon.app.data.BrushStroke(listOf(0.2f, 0.5f, 0.3f, 0.5f))), 0.05f, 0.5f),
            100, 100
        )
        assertTrue("笔迹上应有权重", grid.sample(0.25f, 0.5f) > 0.2f)
        assertTrue("远处不应有权重", grid.sample(0.85f, 0.9f) < 0.05f)
    }

    @Test
    fun localAdjustmentOnlyAffectsTheSelectedZone() {
        val w = 80; val h = 80
        val px = flat(w, h, rgb(120, 120, 120))
        val local = LocalAdjustments(
            listOf(
                LocalLayer(
                    id = "center",
                    mask = LocalMask.ControlPoint(0.5f, 0.5f, 0.18f, 0.7f),
                    params = LocalParams(brightness = 80f)
                )
            )
        )
        val layers = LocalMaskRenderer.prepare(local, w, h, null)
        LocalMaskRenderer.apply(px, w, h, layers, null)
        assertTrue("中心应被提亮", lum(px[(h / 2) * w + w / 2]) > 150f)
        assertEquals("角落应完全不变", 120f, lum(px[0]), 0.5f)
    }

    // ------------------------------------------------------- zoned enhance

    private fun statsFor(
        meanLuma: Float, p5: Float, p95: Float,
        shadowClip: Float, highlightClip: Float,
        brightFraction: Float, shadowFraction: Float,
        warmth: Float, greenCoverage: Float, meanSaturation: Float
    ): ImageStats {
        val hist = IntArray(256) { 4 }
        return ImageStats(
            bins = 64,
            red = FloatArray(64) { 1f / 64 },
            green = FloatArray(64) { 1f / 64 },
            blue = FloatArray(64) { 1f / 64 },
            meanLuma = meanLuma,
            stdLuma = (p95 - p5) / 4f,
            p1 = 0.02f, p5 = p5, p50 = meanLuma, p95 = p95, p99 = 0.97f,
            shadowClip = shadowClip, highlightClip = highlightClip,
            meanSaturation = meanSaturation,
            meanR = meanLuma, meanG = meanLuma, meanB = meanLuma,
            warmth = warmth, greenCast = 0f, detail = 0.02f,
            lumaHistogram = hist,
            greenCoverage = greenCoverage,
            shadowFraction = shadowFraction,
            brightFraction = brightFraction
        )
    }

    /** Upper half blue sky, lower half green foliage — real colour evidence. */
    private fun outdoorBitmap(): Bitmap {
        val w = 96; val h = 72
        val px = IntArray(w * h) { i ->
            if (i < w * h / 2) rgb(150, 180, 235) else rgb(70, 110, 65)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun goodPhotoGetsNoZoneLayers() {
        val good = statsFor(0.47f, 0.10f, 0.85f, 0f, 0f, 0.10f, 0.10f, 0f, 0f, 0.3f)
        val result = AutoEnhance.suggestZoned(outdoorBitmap(), good)
        assertFalse(result.changed)
        assertTrue(result.zones.isEmpty())
        assertTrue(result.adjustments.local.isNeutral())
    }

    @Test
    fun problemPhotoGetsEvidenceGatedZoneLayersAndIsIdempotent() {
        val bad = statsFor(0.30f, 0.05f, 0.52f, 0.05f, 0.02f, 0.30f, 0.55f, -0.06f, 0.22f, 0.42f)
        val bitmap = outdoorBitmap()
        val first = AutoEnhance.suggestZoned(bitmap, bad)
        assertTrue(first.changed)
        assertTrue("应有分区层: ${first.zones}", first.adjustments.local.activeLayers().isNotEmpty())
        assertTrue("分区说明应含天空或主体", first.zones.any { it.contains("天空") || it.contains("主体") })
        assertTrue("必须如实标注启发式而非 AI 模型", first.providerLabel.contains("非 AI"))

        val autoIds = first.adjustments.local.layers.map { it.id }
        assertTrue(autoIds.all { it.startsWith("auto:") })

        val second = AutoEnhance.suggestZoned(bitmap, bad, first.adjustments)
        assertEquals("连点必须幂等", first.adjustments, second.adjustments)
    }

    @Test
    fun userPaintedLayersSurviveTheZonedPass() {
        val bad = statsFor(0.30f, 0.05f, 0.52f, 0.05f, 0.02f, 0.30f, 0.55f, -0.06f, 0.22f, 0.42f)
        val mine = LocalAdjustments(listOf(
            LocalLayer("my-layer", params = LocalParams(brightness = 15f))
        ))
        val current = EditAdjustments(local = mine)
        val result = AutoEnhance.suggestZoned(outdoorBitmap(), bad, current)
        assertTrue(result.adjustments.local.layers.any { it.id == "my-layer" })
        assertTrue(result.adjustments.local.layers.any { it.id.startsWith("auto:") })
    }

    @Test
    fun globalSolverPreservesQualityFields() {
        val bad = statsFor(0.30f, 0.05f, 0.52f, 0.05f, 0.02f, 0.30f, 0.55f, -0.06f, 0.22f, 0.42f)
        val current = EditAdjustments(denoise = 30f, grain = 20f, vignette = -25f)
        val result = AutoEnhance.suggest(bad, current)
        assertEquals(30f, result.adjustments.denoise, 0.001f)
        assertEquals(20f, result.adjustments.grain, 0.001f)
        assertEquals(-25f, result.adjustments.vignette, 0.001f)
    }

    @Test
    fun zonedPassDegradesToGlobalWhenNoBitmap() {
        val bad = statsFor(0.30f, 0.05f, 0.52f, 0.05f, 0.02f, 0.30f, 0.55f, -0.06f, 0.22f, 0.42f)
        val result = AutoEnhance.suggestZoned(null, bad)
        assertTrue(result.changed)
        assertTrue(result.adjustments.local.isNeutral())
        assertTrue(result.zones.isEmpty())
    }

    // -------------------------------------------------------- recommendation

    @Test
    fun recommenderPrefersBrighteningPresetForADarkFrame() {
        val brighten = Preset.none().copy(id = "bright", name = "提亮风景", brightness = 30, shadows = 25)
        val darken = Preset.none().copy(id = "dark", name = "压暗", brightness = -30, highlights = -25)
        val dark = statsFor(0.28f, 0.03f, 0.45f, 0.06f, 0f, 0.05f, 0.60f, 0f, 0.30f, 0.35f)
        val ranked = PresetRecommender.rank(dark, listOf(darken, brighten))
        assertEquals("bright", ranked.first().presetId)
        assertTrue(ranked.first().reason.isNotBlank())
    }
}
