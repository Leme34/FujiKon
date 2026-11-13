package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import com.fujikon.app.data.PortraitStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.53 一键优化回归：取消「先套通透胶片滤镜」，改为纯参数化、内容感知地
 * 智能调整 App 现有可调参数，并把整套参数同步进调节列表供二次调整。
 *
 * 对齐 Lightroom「Auto」/ 美图秀秀「一键美颜」的算法本质：只求解参数滑块
 * （影调氛围 / 光感质感层次 / 人像美颜美白），绝不切换或预套任何预设。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoEnhanceOneTapTest {

    /** 偏暗、低反差、略偏冷的问题照片，能触发影调与质感求解。 */
    private fun problemPhoto(): Bitmap {
        val w = 120
        val h = 96
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = x / w.toFloat()
                val ny = y / h.toFloat()
                val l = (0.16f + 0.24f * nx + 0.05f * (ny - 0.5f)).coerceIn(0.05f, 0.55f)
                val r = (l * 0.86f * 255f).toInt().coerceIn(0, 255)
                val g = (l * 0.95f * 255f).toInt().coerceIn(0, 255)
                val b = (l * 1.12f * 255f).toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 一张「人像」：中心大面积肤色（R>G>B、中等彩度、中等亮度），四周中性
     * 灰背景。skinCoverage 明显高于 [AutoEnhance] 的人像判定门槛。
     */
    private fun portraitPhoto(): Bitmap {
        val w = 120
        val h = 96
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = x / w.toFloat()
                val ny = y / h.toFloat()
                val inFace = nx in 0.22f..0.78f && ny in 0.16f..0.84f
                val c = if (inFace) {
                    // 肤色：R>G>B，中等亮度，符合 YCbCr 肤色簇
                    Color.rgb((0.72f * 255).toInt(), (0.52f * 255).toInt(), (0.42f * 255).toInt())
                } else {
                    Color.rgb((0.46f * 255).toInt(), (0.46f * 255).toInt(), (0.46f * 255).toInt())
                }
                px[y * w + x] = c
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun oneTapNeverAppliesAnyPresetOrFilter() = runBlocking {
        // 核心诉求：不再「先套通透胶片滤镜」。optimizeOneTap 用 Preset.none()
        // 作闭环基准，产出的 adjustments 不含任何滤镜痕迹，且摘要不提胶片。
        val source = problemPhoto()
        val result = AutoEnhance.optimizeOneTap(source)
        assertNotNull(result.adjustments)
        assertEquals(
            "一键优化不得切换/预套任何创意滤镜",
            com.fujikon.app.data.CreativeFilter.NONE.id,
            result.adjustments.creativeFilter
        )
        assertFalse(
            "摘要不应再出现「通透胶片」",
            result.summary.contains("通透胶片")
        )
        assertTrue(
            "摘要应点明参数已同步、可二次调整",
            result.summary.contains("可二次调整")
        )
    }

    @Test
    fun oneTapSyncsToneAndTextureParametersForSecondAdjustment() = runBlocking {
        // 问题照片应产出一组非中性的普通参数，用户可在调节列表逐项微调。
        val source = problemPhoto()
        val result = AutoEnhance.optimizeOneTap(source)
        assertTrue("应检出并修正影调问题", result.changed)
        assertFalse("调节参数不能是空的中性集", result.adjustments.isNeutral())
    }

    @Test
    fun oneTapIsIdempotent() = runBlocking {
        val source = problemPhoto()
        val first = AutoEnhance.optimizeOneTap(source)
        val second = AutoEnhance.optimizeOneTap(source)
        assertEquals(
            "连续两次一键优化不得累加、不得漂移",
            first.adjustments,
            second.adjustments
        )
    }

    @Test
    fun portraitPhotoGetsContentAwareBeauty() = runBlocking {
        // 对标美图秀秀：检测到肤色才智能设置磨皮/美白/红润/立体，值保守。
        val source = portraitPhoto()
        val stats = AutoEnhance.analyse(source)!!
        assertTrue(
            "人像测试图的肤色占比应达标，实际=${stats.skinCoverage}",
            stats.skinCoverage > 0.07f
        )

        val result = AutoEnhance.optimizeOneTap(source)
        val p = result.adjustments.portrait
        assertFalse("人像照应启用美颜美白", p.isOff())
        assertTrue("应含磨皮", p.smooth > 0f)
        assertTrue("应含美白", p.whiten > 0f)
        assertTrue("应含红润/气色", p.blush > 0f)
        assertTrue("应含立体", p.contour > 0f)
        assertEquals("强度应标为自定义，便于用户二次调整", PortraitStyle.CUSTOM.id, p.style)
        assertTrue("磨皮强度须保守（不塑料感）", p.smooth <= 36f)
        assertTrue("美白强度须保守", p.whiten <= 28f)
        assertTrue("摘要应点明人像美颜美白", result.summary.contains("人像美颜美白"))
    }

    @Test
    fun landscapePhotoDoesNotForceBeauty() = runBlocking {
        // 非人像（冷色风景）不应被强加美颜，portrait 保持关闭。
        val source = problemPhoto()
        val stats = AutoEnhance.analyse(source)!!
        assertTrue("风景测试图不应被判为人像", stats.skinCoverage < 0.07f)

        val result = AutoEnhance.optimizeOneTap(source)
        assertTrue("非人像照不启用美颜", result.adjustments.portrait.isOff())
        assertFalse("摘要不应谎称做了人像美颜", result.summary.contains("人像美颜美白"))
    }

    @Test
    fun enrichOneTapIsDeterministicAndKeepsStructuralEdits() = runBlocking {
        // 提示与按钮共用 enrichOneTap：幂等且不覆盖用户已有的结构性编辑
        // （颗粒 / 预设强度这类非影调参数），保证用户二次调整不被吞掉。
        val source = portraitPhoto()
        val stats = AutoEnhance.analyse(source)!!
        val current = EditAdjustments.Neutral.copy(grain = 20f, presetStrength = 70f)
        val seed = AutoEnhance.suggestZoned(source, stats, current)
        val a = AutoEnhance.enrichOneTap(seed, stats, current)
        val b = AutoEnhance.enrichOneTap(seed, stats, current)
        assertEquals(a.adjustments, b.adjustments)
        assertEquals(20f, a.adjustments.grain, 0.001f)
        assertEquals(70f, a.adjustments.presetStrength, 0.001f)
    }
}
