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
 * 1.6.63 性能回归防线：Dazz 富士 look 曾因八色带 blender + 三分区 grading +
 * grain/vignette 全部逐像素重算（含 HSL 往返、smoothstep、hypot/sqrt），
 * 在预览分辨率下耗时约为纯曲线预设的 4~5 倍，滑动套用时"一直加载中"。
 *
 * 现把这些只依赖色相/亮度/坐标的量预计算成查找表。本测试用一个非常宽松的
 * 绝对上界拦截"退化回逐像素重算导致的数量级暴增"，不追求精确计时（JVM 的
 * wall-clock 会随机器负载波动，故阈值放大到基线 35ms 的 ~6 倍），只保证
 * 不会退回 200ms+ 的灾难区间。画质等价性由 [DazzFujiPresetsTest] 的渲染
 * 健全性用例守护。
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
