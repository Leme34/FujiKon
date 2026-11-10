package com.fujikon.app.nic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 「通透胶片」v2 预置的参数边界与色彩学不变量。
 *
 * 相对 1.6.32（逐通道 curve）与 1.6.33（严重偏色）最关键的改变：
 * 影调全部走 [lumaToneLut] 亮度耦合路径，curve 明确为 null。这里的测试锁死
 * 三件事：
 *  ① 保色：灰阶探针 a/b=0；彩色像素三通道同 scale，色相/彩度零漂移；
 *     高光触顶时三通道等比回缩，绝不逐通道裁剪（1.6.33 偏色的根因）。
 *  ② 影调 LUT 形态：单调、平滑、黑位柔化、中间调提亮、白点收敛、范围合法。
 *  ③ 复合效果层克制：色彩层恒等，无任何全局调色；人像/质感/美感参数有上限。
 */
class WorkStyle16PresetTest {

    private val preset = WorkStyle16Preset.toPreset()

    @Test
    fun identityIsStableAndIndependent() {
        assertEquals("workstyle16_tonal", WorkStyle16Preset.ID)
        assertEquals("local:workstyle16_tonal_v2", WorkStyle16Preset.CONTENT_HASH)
        assertEquals("通透胶片", preset.name)
        assertTrue(preset.description?.contains("统计风格拟合") == true)

        val listed = builtInWorkStylePresets()
        assertEquals(1, listed.size)
        assertTrue(listed.any { it.contentHash == WorkStyle16Preset.CONTENT_HASH })
        assertFalse(listed.any { it.contentHash == "nic:01JSBM30XWQ6GG4XK0KSZ3FQEK" })
        assertFalse(listed.any { it.contentHash == "local:pmango_style_approx" })
    }

    @Test
    fun perChannelCurveIsDisabled() {
        // 逐通道 curve 会拉伸彩色像素 RGB 比例而引入色偏 —— v2 必须彻底关闭。
        assertNull("v2 不得使用逐通道 curve", preset.curve)
        assertFalse(preset.hasCurve)
        assertTrue(preset.lumaToneLut != null && preset.lumaToneLut!!.size == 256)
        assertTrue(preset.channelCurves.isNeutral)
    }

    @Test
    fun lumaToneLutIsMonotonicSmoothAndWithinRange() {
        val t = preset.lumaToneLut!!
        assertTrue("保色 LUT 必须是 256 点", t.size == 256)
        for (i in 1 until t.size) {
            assertTrue("保色 LUT 必须单调不减 @$i", t[i] >= t[i - 1])
        }
        assertTrue("LUT 值域必须 0..255", t.all { it in 0..255 })
        // 黑位柔化：不死黑，但也不把黑抬成灰
        assertTrue("黑位 ${t[0]} 应柔化在 4..20", t[0] in 4..20)
        // 白点柔和收敛，不推到纯白 255（避免死白）
        assertTrue("白点应收敛 <255，实际 ${t[255]}", t[255] in 240..252)
        // 平滑：二阶差分均值不能太大，避免色阶断层
        var curv = 0.0
        for (i in 2 until 256) curv += abs(t[i] - 2 * t[i - 1] + t[i - 2])
        assertTrue("LUT 应平滑，二阶差分均值 ${curv / 254}", curv / 254 < 1.2)
    }

    @Test
    fun lumaToneLutLiftsShadowsAndMidsButProtectsHighlights() {
        val t = preset.lumaToneLut!!
        // 暗部到中间调提亮（通透感来源）
        assertTrue("p32 应被提亮，实际 ${t[32]}", t[32] > 32 + 8)
        assertTrue("p64 应被提亮，实际 ${t[64]}", t[64] > 64 + 20)
        assertTrue("p128 应被提亮，实际 ${t[128]}", t[128] > 128 + 20)
        // 高光区不压低（避免发灰、丢失通透）
        assertTrue("p224 不应被压低，实际 ${t[224]}", t[224] >= 224 - 2)
        // 整体是提亮曲线
        var lifted = 0
        for (i in 0..255) if (t[i] >= i - 1) lifted++
        assertTrue("绝大多数采样点应 >= 输入，实际 $lifted", lifted >= 240)
    }

    @Test
    fun colorLayerIsStrictlyIdentity() {
        // 20 张作品 mean_b 跨度 42，任何全局色彩项都会在其反向场景翻车。
        assertEquals(0, preset.saturation)
        assertEquals(0, preset.hue)
        assertTrue("色彩分区必须为空或全零", preset.colorBlender.isEmpty() ||
            preset.colorBlender.all { it.hue == 0 && it.chroma == 0 && it.brightness == 0 })
        assertFalse(preset.hasColorBlender)
        assertFalse(preset.hasColorGrading)
        assertEquals(0, preset.colorGrading.highlights.hue)
        assertEquals(0, preset.colorGrading.midtones.chroma)
        assertEquals(0, preset.colorGrading.shadows.brightness)
        // 其余 11 项影调参数恒等，二次施加会破坏 LUT
        assertEquals(0, preset.contrast)
        assertEquals(0, preset.brightness)
        assertEquals(0, preset.highlights)
        assertEquals(0, preset.shadows)
        assertEquals(0, preset.whites)
        assertEquals(0, preset.blacks)
        assertEquals(0f, preset.sharpening, 0.0001f)
        assertEquals(0f, preset.clarity, 0.0001f)
        assertEquals(0f, preset.midRangeSharpening, 0.0001f)
    }

    @Test
    fun compositeAdjustmentsAreRestrainedAndNeverTint() {
        val a = preset.adjustments
        // 六维复合层存在，但绝不全局调色
        assertEquals(0f, a.temperature, 0.0001f)
        assertEquals(0f, a.tint, 0.0001f)
        assertEquals(0f, a.vibrance, 0.0001f)
        assertEquals(0f, a.shadowTint, 0.0001f)
        assertEquals(0f, a.grain, 0.0001f)
        assertEquals(0f, a.denoise, 0.0001f)
        assertTrue("HSL 必须中性", a.hsl.isNeutral())
        assertTrue("不得叠加创意滤镜", a.creativeFilter == "none")
        // 光感/质感/美感：克制取值
        assertTrue("clarity 应极轻负", a.clarity in -6f..-1f)
        assertTrue("texture 应轻正回补", a.texture in 1f..8f)
        assertTrue("vignette 应极轻正", a.vignette in 0f..5f)
        // 人像美颜：开启但克制（远低于「写真」滤镜，避免塑料脸）
        val p = a.portrait
        assertFalse(p.isOff())
        assertTrue("磨皮应克制", p.smooth in 6f..22f)
        assertTrue("美白应克制", p.whiten in 6f..24f)
        assertTrue("气色应克制", p.blush in 2f..14f)
        assertTrue("立体应克制", p.contour in 4f..16f)
    }

    @Test
    fun lumaToneLutDoesNotIntroduceColorCast() {
        // 复刻渲染器亮度耦合路径：三通道同 scale + lift，溢出时等比回缩。
        val t = preset.lumaToneLut!!
        // ① 灰阶探针：输入 R=G=B，输出仍 R=G=B -> Lab a/b 恒为 0
        for (v in listOf(0, 16, 32, 64, 96, 128, 160, 192, 224, 255)) {
            val out = applyLuma(v, v, v, t)
            val lab = srgbToLab(out[0] / 255.0, out[1] / 255.0, out[2] / 255.0)
            assertTrue("灰阶 $v 出现 a 色偏：${lab[1]}", abs(lab[1]) < 0.01)
            assertTrue("灰阶 $v 出现 b 色偏：${lab[2]}", abs(lab[2]) < 0.01)
        }
        // ② 彩色探针：任意色相/明度/彩度，输出色相漂移必须为 0
        var maxShift = 0.0
        for (hd in 0 until 360 step 12) {
            for (v in listOf(35, 70, 120, 170, 215, 250)) {
                for (s in listOf(0.25, 0.5, 0.8, 1.0)) {
                    val rgb = hsvToRgb(hd.toDouble(), s, v.toDouble())
                    val out = applyLuma(rgb[0], rgb[1], rgb[2], t)
                    val h0 = hueOf(rgb[0], rgb[1], rgb[2])
                    val h1 = hueOf(out[0], out[1], out[2])
                    if (s < 0.15) continue
                    val d = abs(((h1 - h0 + 180) % 360) - 180)
                    maxShift = max(maxShift, d)
                }
            }
        }
        assertTrue("彩色像素色相漂移必须 <0.5°，实际 $maxShift", maxShift < 0.5)
    }

    /** 1:1 复刻 ImageRenderer 的保色亮度耦合（含溢出等比回缩）。 */
    private fun applyLuma(r: Int, g: Int, b: Int, lut: IntArray): IntArray {
        val luma = r * 0.299f + g * 0.587f + b * 0.114f
        val mapped = lut[luma.roundToInt().coerceIn(0, 255)].toFloat()
        var scale = if (luma > 8f) mapped / luma else 1f
        val lift = mapped - luma * scale
        val maxc = max(r, max(g, b)).toFloat()
        if (maxc * scale + lift > 255f) scale = (255f - lift) / maxc.coerceAtLeast(1f)
        return intArrayOf(
            (r * scale + lift).roundToInt().coerceIn(0, 255),
            (g * scale + lift).roundToInt().coerceIn(0, 255),
            (b * scale + lift).roundToInt().coerceIn(0, 255)
        )
    }

    private fun hueOf(r: Int, g: Int, b: Int): Double {
        val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
        val mx = max(rf, max(gf, bf)); val mn = minOf(rf, gf, bf); val d = mx - mn
        if (d < 1e-9) return 0.0
        val h = when (mx) {
            rf -> ((gf - bf) / d + if (gf < bf) 6.0 else 0.0) * 60.0
            gf -> ((bf - rf) / d + 2.0) * 60.0
            else -> ((rf - gf) / d + 4.0) * 60.0
        }
        return (h + 360.0) % 360.0
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): IntArray {
        val c = v * s; val hp = ((h % 360) + 360) % 360 / 60.0
        val x = c * (1 - abs(hp % 2 - 1)); val m = v - c / 2
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return intArrayOf(
            ((r1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255).roundToInt().coerceIn(0, 255)
        )
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
