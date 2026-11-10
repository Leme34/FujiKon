package com.fujikon.app.nic

import android.graphics.Bitmap
import com.fujikon.app.data.Preset
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 端到端渲染验证：走真实 [ImageRenderer]，确认「通透胶片」v2 的
 * 保色亮度耦合路径在完整渲染链路里确实零色相漂移，且预设确实改变画面。
 *
 * 这是对 1.6.33「偏色非常严重」事故的回归锁定：那一版逐通道 curve 会把
 * 彩色像素拉伸到另一个色相，这里用真实渲染器证明 v2 不会。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkStyleV2RendererTest {

    private val renderer = ImageRenderer()
    private val preset = WorkStyle16Preset.toPreset()

    private fun render(r: Int, g: Int, b: Int, p: Preset = preset): Int = runBlocking {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        val out = renderer.render(bmp, p, forExport = false)
        out.getPixel(0, 0)
    }

    private fun rgb(c: Int) = intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    private fun hue(r: Int, g: Int, b: Int): Double {
        val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
        val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf); val d = mx - mn
        if (d < 1e-9) return 0.0
        val h = when (mx) {
            rf -> ((gf - bf) / d + if (gf < bf) 6.0 else 0.0) * 60.0
            gf -> ((bf - rf) / d + 2.0) * 60.0
            else -> ((rf - gf) / d + 4.0) * 60.0
        }
        return (h + 360.0) % 360.0
    }

    @Test
    fun realRenderKeepsHueOfColoredPixels() {
        // 覆盖暖橙、青蓝、绿、品红、蓝 —— 正是 20 张作品里跨度最大的场景色。
        val probes = listOf(
            intArrayOf(230, 120, 60),   // 暖橙高光
            intArrayOf(60, 140, 210),  // 青蓝天空
            intArrayOf(70, 160, 90),   // 绿叶
            intArrayOf(200, 90, 160),  // 品红
            intArrayOf(250, 200, 40),  // 黄
            intArrayOf(30, 45, 60)    // 深蓝阴影（1.6.33 曾被拉成 43,69,124）
        )
        var maxShift = 0.0
        for ((r, g, b) in probes) {
            val out = rgb(render(r, g, b))
            val h0 = hue(r, g, b)
            val h1 = hue(out[0], out[1], out[2])
            val shift = abs(((h1 - h0 + 180) % 360) - 180)
            maxShift = maxOf(maxShift, shift)
            assertTrue(
                "像素 ($r,$g,$b) 色相漂移 ${"%.2f".format(shift)}° 超标 -> (${out.joinToString(",")})",
                shift < 1.5
            )
        }
        println("v2 真实渲染最大色相漂移 = ${"%.3f".format(maxShift)}°")
    }

    @Test
    fun realRenderKeepsNeutralGraysNeutral() {
        // 端到端含人像美颜：YCbCr 肤色窗口会把近中性灰判为肤色并施加极轻的
        // 美白/红润，因此允许 <3/255 的通道差（约 1.2% 满量程，肉眼不可见）。
        // 纯影调 LUT 本身的零色偏由 WorkStyle16PresetTest 的灰阶 a/b 探针严格锁定。
        // 这里守的是「端到端仍接近中性」，而非 1.6.33 那种色相整体漂移。
        for (v in listOf(0, 32, 64, 96, 128, 160, 192, 224, 255)) {
            val out = rgb(render(v, v, v))
            val spread = out.max() - out.min()
            assertTrue(
                "灰阶 $v 被染成 ${out.joinToString(",")}，通道差 $spread（疑似偏色事故）",
                spread <= 3
            )
        }
    }

    @Test
    fun realRenderActuallyBrightensMidtonesWithoutClippingHighlights() {
        // 中间调应被提亮（通透感）
        val mid = rgb(render(96, 96, 96))[0]
        assertTrue("中间调 96 应被提亮，实际 $mid", mid > 110)
        // 高光不能被推成死白
        val hi = rgb(render(240, 240, 240))[0]
        assertTrue("高光 240 不应死白，实际 $hi", hi < 255)
        // 黑位被柔化但不抬成灰
        val black = rgb(render(0, 0, 0))[0]
        assertTrue("黑位应柔化但不发灰，实际 $black", black in 1..20)
    }

    @Test
    fun noneVsV2DiffersSoPresetIsNotANoop() {
        // 确认预设不是空转：与「无」预设的结果必须有差异
        val none = Preset.none()
        val src = intArrayOf(120, 120, 120)
        val a = rgb(render(src[0], src[1], src[2], none))[0]
        val b = rgb(render(src[0], src[1], src[2], preset))[0]
        assertTrue("通透胶片应对画面产生实际作用 (none=$a, v2=$b)", abs(b - a) > 10)
    }

    @Test
    fun builtInPresetJsonRoundTripKeepsLumaToneLut() {
        // 内置预设会经 index.json 持久化；确认 LUT 与复合参数不丢
        val json = preset.toJson()
        val restored = Preset.fromJson(json)
        assertEquals(preset.name, restored.name)
        assertEquals(preset.contentHash, restored.contentHash)
        assertTrue("反序列化后 lumaToneLut 不能丢失", restored.lumaToneLut != null)
        assertEquals(256, restored.lumaToneLut!!.size)
        assertEquals(preset.lumaToneLut!!.toList(), restored.lumaToneLut!!.toList())
        assertTrue("反序列化后人像美颜不能丢失", !restored.adjustments.portrait.isOff())
        assertEquals(preset.adjustments.portrait.smooth, restored.adjustments.portrait.smooth, 0.001f)
    }
}
