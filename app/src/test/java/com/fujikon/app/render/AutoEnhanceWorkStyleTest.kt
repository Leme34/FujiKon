package com.fujikon.app.render

import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.EditCrop
import com.fujikon.app.data.Preset
import com.fujikon.app.nic.WorkStyle16Preset
import com.fujikon.app.nic.builtInCloudPresets
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「一键优化 = 先真正套用通透胶片，再在效果图上按 RGB 直方图优化」的回归锁定。
 *
 * 重点守住：色彩层恒等（绝不偏色）、成片确实带通透胶片效果（不再被抵消）、
 * 且绝不在已提亮的基底上再叠一轮提亮（不双重过曝）、不新增无色死白。
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
    fun selectedWorkStylePresetCarriesTheFullLumaLut() {
        // 一键优化按钮按 contentHash 找到这个预置并切过去；如果播种或 JSON
        // 持久化把 LUT 弄丢了，「套用通透胶片」就会只剩一个空壳。
        val preset = builtInCloudPresets().firstOrNull {
            it.contentHash == WorkStyle16Preset.CONTENT_HASH
        } ?: throw AssertionError("内置预置列表缺少「通透胶片」")
        val base = WorkStyle16Preset.toPreset()

        assertNotNull("内置通透胶片必须带保色亮度 LUT", preset.lumaToneLut)
        assertArrayEquals("内置 LUT 必须与基底定义逐点一致",
            base.lumaToneLut, preset.lumaToneLut)
        assertEquals("自带 adjustments 也必须完整", base.adjustments, preset.adjustments)

        val roundTrip = Preset.fromJson(preset.toJson())
        assertNotNull("JSON 往返后 LUT 不得丢失", roundTrip.lumaToneLut)
        assertArrayEquals("JSON 往返后 LUT 必须逐点一致",
            base.lumaToneLut, roundTrip.lumaToneLut)
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

    // ---- 一键优化必须「先套用通透胶片，再在效果图上优化」 -------------------

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
    fun solverOptimizesTheGradedFrameNotTheOriginal() = runBlocking {
        val src = wellExposed()
        val original = AutoEnhance.analyse(src)!!
        val base = WorkStyle16Preset.toPreset()
        val graded = ImageRenderer().render(
            src,
            base.copy(adjustments = base.adjustments.copy(crop = EditCrop.Full)),
            forExport = false
        )
        val gradedStats = AutoEnhance.analyse(graded)!!

        // 通透胶片先把亮端推高了；阶段 2 必须在这张「已套用」的图上分析，
        // 才会产出压高光/白点的修正，而不是拿原图（原图根本没有高光问题）。
        assertTrue(
            "通透胶片应抬高亮端: ${gradedStats.p95} vs ${original.p95}",
            gradedStats.p95 > original.p95 + 0.03f
        )
        val fromOriginal = AutoEnhance.suggestWorkStyle(original).adjustments
        val fromGraded = AutoEnhance.suggestWorkStyle(gradedStats, original = original).adjustments
        assertTrue(
            "基于已套用图的优化必须更用力压高光: ${fromGraded.highlights} vs ${fromOriginal.highlights}",
            fromGraded.highlights < fromOriginal.highlights - 1f
        )
        assertTrue(
            "基于已套用图的优化必须更用力收白点: ${fromGraded.whites} vs ${fromOriginal.whites}",
            fromGraded.whites < fromOriginal.whites - 0.5f
        )
        assertTrue("优化不得再额外提亮暗部: ${fromGraded.shadows}", fromGraded.shadows <= 1f)
        assertEquals("不整体改变曝光", 0f, fromGraded.exposure, 0f)
        assertTrue("色彩层恒等", fromGraded.temperature == 0f && fromGraded.tint == 0f &&
            fromGraded.vibrance == 0f && fromGraded.hsl.isNeutral())
    }

    @Test
    fun oneTapReallyAppliesWorkStyleThenOptimizes() = runBlocking {
        val src = wellExposed()
        val before = AutoEnhance.analyse(src)!!
        val base = WorkStyle16Preset.toPreset()
        val graded = ImageRenderer().render(
            src,
            base.copy(adjustments = base.adjustments.copy(crop = EditCrop.Full)),
            forExport = false
        )
        val gradedStats = AutoEnhance.analyse(graded)!!

        val result = AutoEnhance.optimizeWorkStyle(src)
        val final = ImageRenderer().render(
            src,
            base.copy(adjustments = result.adjustments),
            forExport = false
        )
        val after = AutoEnhance.analyse(final)!!

        // ① 成片必须真的带通透胶片效果，而不是接近原图（上一版的 bug：
        //    反 LUT 亮度锚定把基底效果整个抵消了）。
        assertTrue(
            "一键优化必须真的套上通透胶片: ${after.meanLuma} vs 原图 ${before.meanLuma}",
            after.meanLuma > before.meanLuma + 0.10f
        )
        assertTrue(
            "成片亮度应贴近通透胶片效果图: ${after.meanLuma} vs ${gradedStats.meanLuma}",
            abs(after.meanLuma - gradedStats.meanLuma) < 0.06f
        )
        // ② 但绝不在已提亮的基底上再叠一轮提亮（更早那版过曝的根因）。
        assertTrue(
            "不得比单纯套通透胶片更亮: ${after.meanLuma} vs ${gradedStats.meanLuma}",
            after.meanLuma <= gradedStats.meanLuma + 0.05f
        )
        assertTrue(
            "中间调不得被再抬高: ${after.p50} vs ${gradedStats.p50}",
            after.p50 <= gradedStats.p50 + 0.075f
        )
        assertTrue(
            "不得新增无色死白: ${after.blownWhiteClip} vs ${before.blownWhiteClip}",
            after.blownWhiteClip <= before.blownWhiteClip + 0.01f
        )
        assertTrue(
            "高光应被压回而不是继续冲高: ${after.p95} vs ${gradedStats.p95}",
            after.p95 <= gradedStats.p95 + 0.005f
        )
        assertTrue(
            "亮度层次须保留: ${after.spread} vs ${gradedStats.spread}",
            after.spread >= gradedStats.spread * 0.75f
        )
        assertTrue(
            "色彩不能被洗成灰: ${after.meanSaturation} vs ${before.meanSaturation}",
            after.meanSaturation >= before.meanSaturation * 0.70f
        )
        assertEquals("不整体改变曝光", 0f, result.adjustments.exposure, 0f)
        assertTrue("不再额外提亮暗部: ${result.adjustments.shadows}",
            result.adjustments.shadows <= 1f)
        assertTrue("色彩层必须恒等", result.adjustments.temperature == 0f &&
            result.adjustments.tint == 0f && result.adjustments.vibrance == 0f &&
            result.adjustments.hsl.isNeutral())
        assertTrue("摘要应说明通透胶片基底", result.summary.contains("通透胶片"))
    }
}
