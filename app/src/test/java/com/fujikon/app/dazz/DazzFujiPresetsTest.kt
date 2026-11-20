package com.fujikon.app.dazz

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import com.fujikon.app.nic.builtInCloudPresets
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dazz 富士风格预置的参数不变量与「套用后仍可看」渲染健全性。
 *
 * 吸取此前 3D LUT 套用后色彩严重崩坏的教训：这里除锁定曲线/色带/分级/
 * 哈希等结构不变量外，还用真实渲染管线对一张中性偏冷的合成图套用每款
 * 富士 look，断言 ① 亮度落在合理区间（不整体死黑或惨白）、② 色相通道
 * 没有被严重拉偏（红蓝差不会离谱）、③ 颗粒/曝光温和不会整片 clamp。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DazzFujiPresetsTest {

    private val presets = builtInDazzFujiPresets()

    private val expectedNames = listOf(
        "FXN R · 经典负片",
        "Classic U · 暖调胶片",
        "Nostalgic Neg · 怀旧负片"
    )

    @Test
    fun exposesExactlyThreeFujiLooksInOrder() {
        assertEquals(3, presets.size)
        assertEquals(expectedNames, presets.map { it.name })
        assertTrue(presets.all { it.sourceName == DazzFujiPresets.SOURCE_NAME })
        assertTrue(presets.all { it.format == "NP3" })
    }

    @Test
    fun eachLookHasMonotonic256PointCurveInRange() {
        presets.forEach { p ->
            val t = p.curve ?: throw AssertionError("${p.name} 缺少曲线")
            assertEquals("${p.name} 曲线应为 256 点", 256, t.size)
            assertTrue("${p.name} 曲线值域必须 0..32767", t.all { it in 0..32767 })
            for (i in 1 until t.size) {
                assertTrue("${p.name} 曲线必须单调不减 @$i", t[i] >= t[i - 1])
            }
            // 样张是通透高对比观感（p1=8~22 深色保留），黑位几乎不抬：
            // 曲线输入 0 的输出应接近 0（≤0.03 → 983），保留深色不发灰。
            assertTrue("${p.name} 黑位应保持深色不被抬起", t[0] in 0..1000)
            assertTrue("${p.name} 高光应柔和滚降", t[255] in 31000..32767)
        }
    }

    @Test
    fun colourWorkIsBakedIntoChannelCurvesAndHeavyPathsRemoved() {
        presets.forEach { p ->
            // 性能回归：绝不能回退到逐像素 HSL 往返的八色带/三分区重路径。
            assertTrue("${p.name} 必须移除八色带 blender", !p.hasColorBlender)
            assertTrue("${p.name} 必须移除三分区 grading", !p.hasColorGrading)
            // 胶片分色烘进逐通道 LUT（buildPlan 阶段合成，热循环零额外开销）。
            assertTrue("${p.name} 必须有逐通道曲线", !p.channelCurves.isNeutral)
            val a = p.adjustments
            assertTrue("曝光应温和", a.exposure in 0f..0.5f)
            assertTrue("色温应温和", a.temperature in 0f..25f)
            assertTrue("颗粒应温和", a.grain in 0f..30f)
            assertEquals("不应保留暗角 pass", 0f, a.vignette)
            assertEquals("不应保留清晰度卷积", 0f, a.clarity)
            assertTrue("强度为满档", a.presetStrength == 100f)
        }
    }

    @Test
    fun contentHashesAreStableUniqueAndNamespaced() {
        val hashes = presets.map { it.contentHash }
        assertEquals(3, hashes.toSet().size)
        assertTrue(hashes.all { it.startsWith("dazz2:") })
        assertTrue(hashes.none {
            it.startsWith("nic:") || it.startsWith("nikonpc:") || it.startsWith("cloudlut:")
        })
        assertEquals(
            listOf("dazz2:fxn_r_classic_neg", "dazz2:classic_u_superia400", "dazz2:nostalgic_neg"),
            hashes
        )
    }

    @Test
    fun builtInCloudPresetsIncludesAllFujiLooksExactlyOnce() {
        val all = builtInCloudPresets()
        val names = all.map { it.name }
        expectedNames.forEach { assertTrue("内置列表应包含 $it", it in names) }
        // 按内容哈希去重：每款富士 look 只播种一次
        expectedNames.forEach { name ->
            assertEquals("${name} 不应重复", 1, names.count { it == name })
        }
        assertEquals(all.size, all.map { it.contentHash }.toSet().size)
    }

    @Test
    fun jsonRoundTripKeepsCurveChannelsAndAdjustments() = runBlocking {
        presets.forEach { p ->
            val restored = Preset.fromJson(p.toJson())
            assertEquals(p.name, restored.name)
            assertEquals(p.contentHash, restored.contentHash)
            assertEquals(p.curve!!.toList(), restored.curve!!.toList())
            assertEquals(p.channelCurves.isNeutral, restored.channelCurves.isNeutral)
            assertEquals(restored.channelCurves.red.normalizedPoints, p.channelCurves.red.normalizedPoints)
            assertEquals(restored.channelCurves.green.normalizedPoints, p.channelCurves.green.normalizedPoints)
            assertEquals(restored.channelCurves.blue.normalizedPoints, p.channelCurves.blue.normalizedPoints)
            assertTrue(restored.channelCurves.red.normalizedPoints.size >= 2)
            assertEquals(p.adjustments.exposure, restored.adjustments.exposure, 0.0001f)
            assertEquals(p.adjustments.grain, restored.adjustments.grain, 0.0001f)
        }
    }

    @Test
    fun eachFujiLookRendersToAusableImage() = runBlocking {
        val source = neutralSource()
        val renderer = ImageRenderer()
        val baseLuma = averageLuma(source)
        presets.forEach { p ->
            val out = renderer.render(source, p, forExport = false)
            assertEquals(source.width, out.width)
            assertEquals(source.height, out.height)
            val luma = averageLuma(out)
            val rb = averageChannel(out, 16) - averageChannel(out, 0)
            assertTrue("${p.name} 亮度不得整体死黑 ($luma)", luma > 30f)
            assertTrue("${p.name} 亮度不得整体惨白 ($luma)", luma < 235f)
            assertTrue("${p.name} 亮度不应大幅偏离原图 ($luma vs $baseLuma)", kotlin.math.abs(luma - baseLuma) < 55f)
            assertTrue("${p.name} 红蓝通道不得严重拉偏 ($rb)", kotlin.math.abs(rb) < 70f)
        }
    }

    @Test
    fun fujiLooksAreVisuallyDistinctFromNeutral() = runBlocking {
        val source = neutralSource()
        val renderer = ImageRenderer()
        val neutral = renderer.render(source, Preset.none(), forExport = false)
        val warmNeutral = renderer.render(
            source,
            Preset.none().copy(adjustments = EditAdjustments.Neutral),
            forExport = false
        )
        assertEquals(averageLuma(neutral), averageLuma(warmNeutral), 0.5f)
        // 经典负片应比原图更暖（红蓝差更大）
        val fxn = renderer.render(source, presets[0], forExport = false)
        assertTrue(
            "经典负片应带来暖调偏移",
            (averageChannel(fxn, 16) - averageChannel(fxn, 0)) >
                (averageChannel(neutral, 16) - averageChannel(neutral, 0))
        )
    }

    private fun neutralSource(): Bitmap {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 中性灰阶 + 轻度冷色，覆盖暗/中/亮三种影调
                val base = 40 + x * 6
                val r = base.coerceAtMost(255)
                val g = base.coerceAtMost(255)
                val b = (base + 8).coerceAtMost(255)
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun averageLuma(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.map {
            ((it shr 16) and 0xFF) * 0.299f +
                ((it shr 8) and 0xFF) * 0.587f +
                (it and 0xFF) * 0.114f
        }.average().toFloat()
    }

    private fun averageChannel(bitmap: Bitmap, shift: Int): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.map { (it shr shift) and 0xFF }.average().toFloat()
    }
}
