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
 * 1.6.82 性能回归防线：胶片直闪人像是套用/导出最重的内置 look（3D LUT +
 * 闪光 + 颗粒 + 暗角）。合并 finishing pass、共享色值缓存之后，预览分辨率
 * 的渲染必须保持在同一数量级，防止回退到逐像素重算或多趟全帧拷贝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashPortraitRenderPerfTest {

    @Test
    fun flashPortraitRendersWithinReasonableTimebound() = runBlocking {
        val w = 720
        val h = 960
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = x * 255 / w
            val g = y * 255 / h
            val b = (x * 3 + y * 5) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val source = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val renderer = ImageRenderer()
        val flash = builtInDazzFujiPresets().first { it.filmLook == DazzFilmLut.LOOK_FLASH_PORTRAIT }

        // warmup：排除 JIT 与首次缓存填充噪声。
        repeat(3) { renderer.render(source, flash, false) }

        val samples = LongArray(5)
        repeat(samples.size) {
            val t0 = System.nanoTime()
            renderer.render(source, flash, false)
            samples[it] = (System.nanoTime() - t0) / 1_000_000L
        }
        samples.sort()
        val medianMs = samples[samples.size / 2]
        assertTrue(
            "胶片直闪人像渲染退化到 ${medianMs}ms（${w}x${h}）",
            medianMs < 300L
        )
    }
}
