package com.fujikon.app.dazz

import android.graphics.Bitmap
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 性能回归防线：Dazz 富士 look 曾因八色带 blender（逐像素 HSL 往返）+ 三分区
 * grading + clarity 卷积在预览分辨率下耗时约为纯曲线预设的 4~5 倍，套用/导出
 * "一直加载中"。1.6.63 先把这些量查表化，1.6.64 进一步把整条胶片映射烘进
 * 主影调 LUT + 逐通道 LUT（buildPlan 阶段合成，热循环仅 3 次查表），彻底移除
 * blender/grading/clarity 重路径，速度与 nikonpc 纯曲线预设相当。
 *
 * 本测试用一个非常宽松的绝对上界拦截"退化回逐像素重算导致的数量级暴增"，
 * 不追求精确计时（JVM wall-clock 随机器负载波动，阈值放大到约 200ms），只保证
 * 不会退回灾难区间。画质/风格健全性由 [DazzFujiPresetsTest] 守护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DazzRenderPerfTest {

    @Test
    fun fujiLooksRenderWithinReasonableTimebound() = runBlocking {
        val w = 720
        val h = 960
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = x * 255 / w
            val g = y * 255 / h
            val b = (x + y) * 255 / (w + h)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val source = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val renderer = ImageRenderer()
        val look = builtInDazzFujiPresets().first()

        // warmup：排除 JIT 编译噪声
        repeat(3) { renderer.render(source, look, false) }

        val samples = LongArray(5)
        repeat(samples.size) {
            val t0 = System.nanoTime()
            renderer.render(source, look, false)
            samples[it] = (System.nanoTime() - t0) / 1_000_000L
        }
        samples.sort()
        val medianMs = samples[samples.size / 2]
        assertTrue(
            "Dazz 富士 look 渲染退化到 ${medianMs}ms（${w}x${h}），疑似逐像素重算回退",
            medianMs < 200L
        )
    }
}
