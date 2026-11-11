package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.EditCrop
import com.fujikon.app.nic.WorkStyle16Preset
import com.fujikon.app.nic.builtInCloudPresets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「一键优化 = 通透胶片基底 + RGB 直方图分区影调」的回归锁定。
 *
 * 重点守住三条红线：色彩层恒等（绝不偏色）、纯黑不被抬灰（不发灰 /
 * 不死黑）、白点平滑收敛（不过曝），并且所有量都跟随实测 RGB 直方图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoEnhanceWorkStyleTest {

    /** 上 1/3 死白、中 1/3 彩色中间调、下 1/3 死黑。 */
    private fun problemBitmap(): Bitmap {
        val w = 96
        val h = 72
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                y < h / 3 -> (0xFF shl 24) or (250 shl 16) or (250 shl 8) or 250
                y < 2 * h / 3 -> (0xFF shl 24) or
                    ((40 + x) shl 16) or ((90 + x / 2) shl 8) or (150 + x / 3)
                else -> (0xFF shl 24) or (2 shl 16) or (2 shl 8) or 2
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun mostlyDark(): Bitmap {
        val w = 64
        val h = 64
        val px = IntArray(w * h) { i ->
            val x = i % w
            val v = (8 + x / 4).coerceAtMost(60)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun mostlyBlown(): Bitmap {
        val w = 64
        val h = 64
        val px = IntArray(w * h) { i ->
            val x = i % w
            val v = (225 + x / 4).coerceAtMost(255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 正常曝光：中间调为主、有完整明暗层次，无死黑、无无色死白、带轻微色彩。 */
    private fun wellExposed(): Bitmap {
        val w = 96
        val h = 72
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = (58 + x * 1.55f + y * 0.35f).toInt().coerceIn(0, 255)
            val r = (v + 10).coerceAtMost(255)
            val b = (v - 10).coerceAtLeast(0)
            (0xFF shl 24) or (r shl 16) or (v shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun workStylePresetIsDiscoverableByContentHash() {
        // 一键优化按 contentHash 找「通透胶片」的真实 id（内置预置入库时
        // id 会被替换成 UUID），这条测试守住该前提不被破坏。
        val preset = builtInCloudPresets().firstOrNull {
            it.contentHash == WorkStyle16Preset.CONTENT_HASH
        }
        assertTrue("内置预置列表必须包含「通透胶片」", preset != null)
        assertEquals(WorkStyle16Preset.NAME, preset!!.name)
    }

    @Test
    fun analyseFillsRawPerChannelHistograms() {
        val stats = AutoEnhance.analyse(problemBitmap())!!
        assertEquals(64, stats.redHistogram.size)
        assertEquals(64, stats.greenHistogram.size)
        assertEquals(64, stats.blueHistogram.size)
        val total = stats.redHistogram.sum()
        assertTrue("R 直方图应有真实计数", total > 0)
        assertEquals(total, stats.greenHistogram.sum())
        assertEquals(total, stats.blueHistogram.sum())
    }

    @Test
    fun recipeKeepsEveryColourLayerExactlyNeutral() {
        val stats = AutoEnhance.analyse(problemBitmap())!!
        val current = EditAdjustments(
            exposure = 0.3f,
            temperature = 30f,
            presetStrength = 40f
        )
        val a = AutoEnhance.suggestWorkStyle(stats, current).adjustments

        assertEquals(0f, a.temperature, 0f)
        assertEquals(0f, a.tint, 0f)
        assertEquals(0f, a.vibrance, 0f)
        assertEquals(0f, a.shadowTint, 0f)
        assertTrue(a.hsl.isNeutral())
        assertTrue(a.pointColor.isNeutral())
        // 不整体提亮、不动黑位、不加去朦胧 —— 三条「不发灰」保障。
        assertEquals(0f, a.exposure, 0f)
        assertEquals(0f, a.blacks, 0f)
        assertEquals(0f, a.dehaze, 0f)
        assertTrue(a.highlights <= 0f)
        assertTrue(a.whites <= 0f)
        assertTrue(a.shadows >= 0f)
        // 通透胶片基底配方仍在。
        val base = WorkStyle16Preset.toPreset().adjustments
        assertEquals(base.portrait, a.portrait)
        assertEquals(base.vignette, a.vignette, 0f)
        // 结构性编辑与强度不丢。
        assertEquals(40f, a.presetStrength, 0f)
    }

    @Test
    fun workStyleCurveKeepsPureBlackAndRollsOffWhiteSmoothly() {
        val stats = AutoEnhance.analyse(problemBitmap())!!
        val curve = AutoEnhance.suggestWorkStyle(stats).adjustments.toneCurve
            ?: throw AssertionError("缺陷画面应产出主曲线")
        val pts = curve.normalizedPoints
        assertEquals("纯黑必须仍是黑，不能抬成灰", 0f, pts.first().y, 0.0001f)
        assertTrue("白点不能塌成死白平台: ${pts.last().y}", pts.last().y > 0.85f)
        assertTrue("曲线必须单调", (1 until pts.size).all { pts[it].y >= pts[it - 1].y - 1e-6f })
        assertTrue("曲线必须真的改变影调", !curve.isLinear())
    }

    @Test
    fun shadowAndHighlightAmountsFollowTheRgbHistogram() {
        val dark = AutoEnhance.suggestWorkStyle(AutoEnhance.analyse(mostlyDark())!!).adjustments
        val blown = AutoEnhance.suggestWorkStyle(AutoEnhance.analyse(mostlyBlown())!!).adjustments
        assertTrue("暗片应更用力提暗部: ${dark.shadows} vs ${blown.shadows}",
            dark.shadows > blown.shadows)
        assertTrue("过曝片应更用力压高光: ${blown.highlights} vs ${dark.highlights}",
            blown.highlights < dark.highlights)
        assertTrue("压高光幅度必须有上限", blown.highlights >= -15f)
    }

    @Test
    fun closedLoopMeasuresRealPixelsAndImprovesBothEnds() = runBlocking {
        val src = problemBitmap()
        val before = AutoEnhance.analyse(src)!!
        val result = AutoEnhance.optimizeWorkStyle(src)
        val report = result.report ?: throw AssertionError("闭环应产出验证报告")

        assertTrue("闭环应真实渲染至少一轮", report.iterations >= 1)
        assertTrue("闭环后死黑不应比原片更多: ${report.shadowClip} vs ${before.shadowClip}",
            report.shadowClip < before.shadowClip)
        assertTrue("颜色层必须保持中性", result.adjustments.temperature == 0f &&
            result.adjustments.tint == 0f && result.adjustments.vibrance == 0f &&
            result.adjustments.hsl.isNeutral())
        assertTrue("摘要应说明通透胶片基底", result.summary.contains("通透胶片"))
    }

    @Test
    fun finalRenderIsCleanerWithoutWashingOutColour() = runBlocking {
        val src = problemBitmap()
        val before = AutoEnhance.analyse(src)!!
        val result = AutoEnhance.optimizeWorkStyle(src)
        val final = ImageRenderer().render(
            src,
            WorkStyle16Preset.toPreset().copy(adjustments = result.adjustments),
            forExport = false
        )
        val after = AutoEnhance.analyse(final)!!

        assertTrue("成片死黑应少于原片: ${after.shadowClip} vs ${before.shadowClip}",
            after.shadowClip < before.shadowClip)
        assertTrue("成片死白不应多于原片: ${after.blownWhiteClip} vs ${before.blownWhiteClip}",
            after.blownWhiteClip <= before.blownWhiteClip + 0.001f)
        assertTrue("色彩不能被洗成灰: ${after.meanSaturation} vs ${before.meanSaturation}",
            after.meanSaturation >= before.meanSaturation * 0.70f)
    }

    @Test
    fun oneTapPreservesStructuralEditsAndStrength() = runBlocking {
        val current = EditAdjustments(
            crop = EditCrop(rotation = 90),
            grain = 12f,
            presetStrength = 35f
        )
        val result = AutoEnhance.optimizeWorkStyle(problemBitmap(), current)
        assertEquals(35f, result.adjustments.presetStrength, 0f)
        assertEquals(12f, result.adjustments.grain, 0f)
        assertEquals(current.crop, result.adjustments.crop)
        // 闭环渲染时清 crop 只影响探针，返回的仍应是用户的构图。
        assertTrue(result.summary.isNotBlank())
    }

    // ---- 正常曝光照片不得被一键优化推过曝（用户反馈回归） -------------------

    @Test
    fun baseLookAloneBrightensAWellExposedPhoto() = runBlocking {
        // 修复前提：通透胶片 v2 的 LUT 是提亮型的，单独套用就会明显抬高中间调。
        val src = wellExposed()
        val before = AutoEnhance.analyse(src)!!
        val graded = ImageRenderer().render(src, WorkStyle16Preset.toPreset(), forExport = false)
        val after = AutoEnhance.analyse(graded)!!
        assertTrue(
            "通透胶片基底本身确实会提亮中间调: ${after.p50} vs ${before.p50}",
            after.p50 > before.p50 + 0.05f
        )
    }

    @Test
    fun anchorCurveCancelsTheBaseLutBrightening() {
        val stats = AutoEnhance.analyse(wellExposed())!!
        val a = AutoEnhance.suggestWorkStyle(stats).adjustments
        val curve = a.toneCurve ?: throw AssertionError("正常曝光照片应产出亮度锚定曲线")
        val pts = curve.normalizedPoints
        assertEquals("纯黑仍是黑", 0f, pts.first().y, 0.0001f)
        assertTrue("白点不塌成死白平台", pts.last().y > 0.85f)
        assertTrue("曲线必须单调", (1 until pts.size).all { pts[it].y >= pts[it - 1].y - 1e-6f })
        val mid = pts.firstOrNull { it.x >= 0.6f }
            ?: throw AssertionError("曲线应覆盖中间调")
        assertTrue(
            "锚定曲线必须在中间调压回基底提亮: y=${mid.y} @ x=${mid.x}",
            mid.y < mid.x - 0.05f
        )
        assertTrue("正常曝光照片不应再大幅提暗部: ${a.shadows}", a.shadows < 3f)
        assertEquals("不整体改变曝光", 0f, a.exposure, 0f)
        assertEquals("不抬黑位", 0f, a.blacks, 0f)
        assertTrue("色彩层恒等", a.temperature == 0f && a.tint == 0f &&
            a.vibrance == 0f && a.hsl.isNeutral())
    }

    @Test
    fun oneTapDoesNotOverexposeAWellExposedPhoto() = runBlocking {
        val src = wellExposed()
        val before = AutoEnhance.analyse(src)!!
        val result = AutoEnhance.optimizeWorkStyle(src)
        val final = ImageRenderer().render(
            src,
            WorkStyle16Preset.toPreset().copy(adjustments = result.adjustments),
            forExport = false
        )
        val after = AutoEnhance.analyse(final)!!
        val bare = ImageRenderer().render(src, WorkStyle16Preset.toPreset(), forExport = false)
        val bareStats = AutoEnhance.analyse(bare)!!

        assertTrue(
            "正常曝光照片优化后不得整体变亮过曝: ${after.meanLuma} vs ${before.meanLuma}",
            after.meanLuma <= before.meanLuma + 0.05f
        )
        assertTrue(
            "一键优化必须显著抵消通透胶片基底的提亮: ${after.meanLuma} vs 裸基底 ${bareStats.meanLuma}",
            after.meanLuma < bareStats.meanLuma - 0.08f
        )
        assertTrue(
            "中间调不得被整体抬高: ${after.p50} vs ${before.p50}",
            after.p50 <= before.p50 + 0.075f
        )
        assertTrue(
            "不得产生新的无色死白: ${after.blownWhiteClip} vs ${before.blownWhiteClip}",
            after.blownWhiteClip <= before.blownWhiteClip + 0.01f
        )
        assertTrue(
            "画面仍须保留亮度层次: ${after.spread} vs ${before.spread}",
            after.spread >= before.spread * 0.75f
        )
        assertTrue("色彩层必须恒等", result.adjustments.temperature == 0f &&
            result.adjustments.tint == 0f && result.adjustments.vibrance == 0f &&
            result.adjustments.hsl.isNeutral())
    }
}
