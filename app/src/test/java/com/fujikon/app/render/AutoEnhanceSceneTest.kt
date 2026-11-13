package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.PortraitRetouch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.57 阶段二回归：场景门控 + 场景化参数策略。
 *
 * 核心不变量：
 *  - 美食/风景/文档/夜景场景，即使人体检测误判为「有人体」，也不得美颜；
 *  - 场景策略只改现有可调滑杆、与既有求解幂等、不覆盖用户手动编辑；
 *  - 不传 scene 时（全部既有调用）行为与 1.6.56 完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoEnhanceSceneTest {

    private fun problemPhoto(): Bitmap {
        val w = 120; val h = 96
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val l = (0.16f + 0.24f * x / w + 0.05f * (y / h - 0.5f)).coerceIn(0.05f, 0.55f)
            val r = (l * 0.86f * 255).toInt().coerceIn(0, 255)
            val g = (l * 0.95f * 255).toInt().coerceIn(0, 255)
            val b = (l * 1.12f * 255).toInt().coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun portraitPhoto(): Bitmap {
        val w = 120; val h = 96
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = x / w.toFloat(); val ny = y / h.toFloat()
            val inFace = nx in 0.22f..0.78f && ny in 0.16f..0.84f
            px[y * w + x] = if (inFace) {
                Color.rgb((0.72f * 255).toInt(), (0.52f * 255).toInt(), (0.42f * 255).toInt())
            } else Color.rgb((0.46f * 255).toInt(), (0.46f * 255).toInt(), (0.46f * 255).toInt())
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun scene(s: PhotoScene) = SceneEvidence(s, 0.5f, "测试")
    private val body = PersonEvidence.present(0.35f, "测试：已识别人体")

    @Test
    fun foodSceneBlocksBeautyEvenWhenPersonDetectorIsWrong() = runBlocking {
        // 双保险：人体检测（分割噪声）误判为「有人体」，美食场景仍须挡住。
        val r = AutoEnhance.optimizeOneTap(
            portraitPhoto(), personEvidence = body, scene = scene(PhotoScene.FOOD)
        )
        assertTrue("美食照绝不启用人像美颜", r.adjustments.portrait.isOff())
        assertFalse("摘要不得谎称做了美颜", r.summary.contains("人像美颜美白"))
        assertTrue("摘要应点明美食场景", r.summary.contains("美食"))
    }

    @Test
    fun landscapeDocumentNightAlsoBlockBeauty() = runBlocking {
        for (s in listOf(PhotoScene.LANDSCAPE, PhotoScene.DOCUMENT, PhotoScene.NIGHT)) {
            val r = AutoEnhance.optimizeOneTap(
                portraitPhoto(), personEvidence = body, scene = scene(s)
            )
            assertTrue("$s 场景不得启用人像美颜", r.adjustments.portrait.isOff())
        }
    }

    @Test
    fun portraitSceneStillAllowsBeauty() = runBlocking {
        val r = AutoEnhance.optimizeOneTap(
            portraitPhoto(), personEvidence = body, scene = scene(PhotoScene.PORTRAIT)
        )
        assertFalse("人像场景 + 确证人体应启用美颜", r.adjustments.portrait.isOff())
    }

    @Test
    fun sceneStrategyAppliesToExistingSliders() = runBlocking {
        val src = problemPhoto()
        val stats = AutoEnhance.analyse(src)!!
        val cur = EditAdjustments.Neutral
        val seed = AutoEnhance.suggestZoned(src, stats, cur)
        // 无场景基线：与 1.6.56 完全一致
        val base = AutoEnhance.enrichOneTap(seed, stats, cur, null, null)

        // 美食：鲜艳度 / 暖调 / 纹理在基线之上被提升
        val food = AutoEnhance.enrichOneTap(seed, stats, cur, null, scene(PhotoScene.FOOD))
        assertTrue("美食策略应提升鲜艳度", food.adjustments.vibrance > base.adjustments.vibrance)
        assertTrue("美食策略应加一点暖调", food.adjustments.temperature > base.adjustments.temperature)
        assertTrue("美食策略应保质感", food.adjustments.texture > base.adjustments.texture)

        // 风景：去朦胧
        val land = AutoEnhance.enrichOneTap(seed, stats, cur, null, scene(PhotoScene.LANDSCAPE))
        assertTrue("风景策略应加去朦胧", land.adjustments.dehaze > base.adjustments.dehaze)

        // 夜景：降噪
        val night = AutoEnhance.enrichOneTap(seed, stats, cur, null, scene(PhotoScene.NIGHT))
        assertTrue("夜景策略应降噪", night.adjustments.denoise > base.adjustments.denoise)

        // 文档：清晰度
        val doc = AutoEnhance.enrichOneTap(seed, stats, cur, null, scene(PhotoScene.DOCUMENT))
        assertTrue("文档策略应提清晰度", doc.adjustments.clarity > base.adjustments.clarity)
    }

    @Test
    fun sceneStrategyIsIdempotentAcrossRepeatedEnrich() = runBlocking {
        val src = problemPhoto()
        val stats = AutoEnhance.analyse(src)!!
        val cur = EditAdjustments.Neutral
        val seed = AutoEnhance.suggestZoned(src, stats, cur)
        val s = scene(PhotoScene.FOOD)
        val a = AutoEnhance.enrichOneTap(seed, stats, cur, null, s)
        val b = AutoEnhance.enrichOneTap(seed, stats, cur, null, s)
        assertEquals("场景策略须幂等，重复调用结果一致", a.adjustments, b.adjustments)
    }

    @Test
    fun sceneStrategyKeepsStructuralEditsAndManualBeauty() = runBlocking {
        // 一键优化从原图绝对重算影调（既有 1.6.53 语义），但**结构性编辑**
        // 与用户手动美颜必须原样保留，场景策略不得吞掉它们。
        val src = problemPhoto()
        val stats = AutoEnhance.analyse(src)!!
        val manual = EditAdjustments.Neutral.copy(
            grain = 20f, presetStrength = 70f, denoise = 15f,
            portrait = PortraitRetouch(smooth = 50f)
        )
        val seed = AutoEnhance.suggestZoned(src, stats, manual)
        val r = AutoEnhance.enrichOneTap(seed, stats, manual, null, scene(PhotoScene.FOOD))
        assertEquals("颗粒须保留", 20f, r.adjustments.grain, 0.001f)
        assertEquals("预设强度须保留", 70f, r.adjustments.presetStrength, 0.001f)
        assertEquals("用户手动美颜不得被覆盖", 50f, r.adjustments.portrait.smooth, 0.001f)
        assertTrue("美食场景仍不得强加自动美颜", r.summary.contains("美食"))
    }

    @Test
    fun noSceneKeepsLegacyBehaviourByteIdentical() = runBlocking {
        // 不传 scene（全部既有调用）时，结果与 1.6.56 完全一致：无场景字样、
        // 无场景策略字段变化、有人体证据才美颜。
        val stats = AutoEnhance.analyse(problemPhoto())!!
        val seed = AutoEnhanceResult(EditAdjustments.Neutral, emptyList(), "seed", true)
        val legacy = AutoEnhance.enrichOneTap(seed, stats, EditAdjustments.Neutral, null)
        val explicit = AutoEnhance.enrichOneTap(seed, stats, EditAdjustments.Neutral, null, null)
        assertEquals(legacy.adjustments, explicit.adjustments)
        assertEquals(legacy.summary, explicit.summary)
        assertFalse(explicit.summary.contains("场景"))
    }
}
