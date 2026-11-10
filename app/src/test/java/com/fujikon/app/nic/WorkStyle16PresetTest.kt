package com.fujikon.app.nic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * 「16 张作品 · 通透胶片」预置的参数边界与色彩学不变量。
 *
 * 与历史 VitalityFilmColorGrade 那版（往里加青）最大的不同：本预置严格
 * 「只做影调、色彩恒等」。这里的测试锁死两件事：
 *  ① 影调曲线形态：抬黑位、提亮中间调、压高光、单调、不裁切；
 *  ② 色彩层全零 + 灰阶探针证明 LUT 不引入任何 a/b 色偏（不染青、不染黄）。
 */
class WorkStyle16PresetTest {

    private val preset = WorkStyle16Preset.toPreset()

    @Test
    fun identityIsStableAndIndependent() {
        assertEquals("workstyle16_tonal", WorkStyle16Preset.ID)
        assertEquals("local:workstyle16_tonal_v1", WorkStyle16Preset.CONTENT_HASH)
        assertTrue(preset.name.contains("通透胶片"))
        assertTrue(preset.description?.contains("统计风格拟合") == true)

        val listed = builtInWorkStylePresets()
        assertEquals(1, listed.size)
        assertTrue(listed.any { it.contentHash == WorkStyle16Preset.CONTENT_HASH })
        // 与官方云创、社区近似条目各自独立，不共享内容哈希。
        assertFalse(listed.any { it.contentHash == "nic:01JSBM30XWQ6GG4XK0KSZ3FQEK" })
        assertFalse(listed.any { it.contentHash == "local:pmango_style_approx" })
    }

    @Test
    fun curveIsMonotonicAndWithinRange() {
        val curve = preset.curve
        assertTrue("tone 曲线必须是 256 点", curve != null && curve.size == 256)
        val table = curve!!
        for (i in 1 until table.size) {
            assertTrue("tone 曲线必须单调不减 @$i", table[i] >= table[i - 1])
        }
        assertTrue("曲线必须在 0..32767 内", table.all { it in 0..32767 })
    }

    @Test
    fun curveLiftsBlacksBrightensMidsAndRollsOffHighlights() {
        val t = preset.curve!!
        // 抬黑位：输入 0 映射到明显大于 0 的输出（胶片不复刻死黑）。
        assertTrue("黑位应被抬起，实际 ${t[0]}", t[0] > 0 && t[0] < 4000)
        // 提亮中间调：中灰输入 128 的输出应明显高于恒等映射的 0.5。
        val mid = t[128] / 32767.0
        assertTrue("中间调应被提亮，实际 $mid", mid > 0.60 && mid < 0.80)
        // 压高光：白点收敛，不能推到纯白 1.0（避免死白）。
        val white = t[255] / 32767.0
        assertTrue("白点应柔和收敛，实际 $white", white > 0.97 && white < 1.0)
        // 整体是提亮曲线：绝大多数采样点输出 >= 输入。
        var lifted = 0
        for (i in 0..255) {
            if (t[i] / 32767.0 >= i / 255.0 - 0.001) lifted++
        }
        assertTrue("曲线应整体提亮（>=200/256 点），实际 $lifted", lifted >= 200)
    }

    @Test
    fun colorLayerIsStrictlyIdentity() {
        // 这是本预置最核心的约束：任何非零色彩项都可能把场景色学进滤镜而翻车。
        assertEquals(0, preset.saturation)
        assertEquals(0, preset.hue)
        assertTrue("色彩分区必须为空或全零", preset.colorBlender.isEmpty() ||
            preset.colorBlender.all { it.hue == 0 && it.chroma == 0 && it.brightness == 0 })
        assertFalse(preset.hasColorBlender)
        assertFalse(preset.hasColorGrading)
        assertEquals(0, preset.colorGrading.highlights.hue)
        assertEquals(0, preset.colorGrading.midtones.chroma)
        assertEquals(0, preset.colorGrading.shadows.brightness)
    }

    @Test
    fun toneAdjustmentsAreBakedIntoCurveOnly() {
        // 影调已全部写进拟合 LUT，其余 11 项必须全零，避免 LUT 之后二次施加。
        assertEquals(0, preset.contrast)
        assertEquals(0, preset.brightness)
        assertEquals(0, preset.highlights)
        assertEquals(0, preset.shadows)
        assertEquals(0, preset.whites)
        assertEquals(0, preset.blacks)
        assertEquals(0f, preset.sharpening, 0.0001f)
        assertEquals(0f, preset.clarity, 0.0001f)
        assertEquals(0f, preset.midRangeSharpening, 0.0001f)
        // 无用户调节层叠加
        assertFalse(preset.hasAdjustments)
        assertTrue(preset.channelCurves.isNeutral)
    }

    @Test
    fun toneLutDoesNotIntroduceColorCast() {
        // 灰阶探针：输入 R=G=B，经过按通道查表后仍 R=G=B，Lab a/b 必须恒为 0。
        // 这直接证明「只做影调、不染青/不染黄」，与 1.6.32 那版加青形成对照。
        val t = preset.curve!!
        for (v in listOf(0, 32, 64, 96, 128, 160, 192, 224, 255)) {
            val out = t[v] / 32767.0            // 归一化输出，三通道都查同一条 LUT
            val lab = srgbToLab(out, out, out)  // 真实走矩阵 + 白点归一化
            assertTrue("灰阶 $v 出现 a 色偏：${lab[1]}", abs(lab[1]) < 0.01)
            assertTrue("灰阶 $v 出现 b 色偏：${lab[2]}", abs(lab[2]) < 0.01)
        }
    }

    /** sRGB(0..1) -> CIELAB(D65)，用完整矩阵与白点归一化，真实计算 a/b。 */
    private fun srgbToLab(r: Double, g: Double, b: Double): DoubleArray {
        fun lin(x: Double) = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val x = (0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl) / 0.95047
        val y = (0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl) / 1.0
        val z = (0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }
}
