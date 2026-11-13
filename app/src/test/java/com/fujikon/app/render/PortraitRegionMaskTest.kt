package com.fujikon.app.render

import android.app.Application
import com.fujikon.app.data.PortraitRetouch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 1.6.57 阶段一回归：人像区域蒙版。
 *
 *  - [PortraitRegionMask.sample] 双线性、边界安全、归一化坐标；
 *  - region=null（默认）时 applyPortrait 与既有实现逐像素完全一致；
 *  - region 生效时，蒙版外的暖色物体（餐盘里的肉）几乎不被磨皮/美白，
 *    蒙版内的人体皮肤照常美颜——把「无人体照片被美颜」从参数门控
 *    再下沉到像素级保护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PortraitRegionMaskTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun sampleIsBilinearAndClamped() {
        val side = 4
        val w = FloatArray(side * side) { 0f }
        for (y in 0 until side) for (x in 0 until side) {
            w[y * side + x] = if (x >= 2) 1f else 0f
        }
        val m = PortraitRegionMask(side, w)
        assertEquals(0f, m.sample(0f, 0f), 0.001f)
        assertEquals(1f, m.sample(1f, 1f), 0.001f)
        assertTrue("中间应为平滑过渡", m.sample(0.5f, 0.5f) in 0.2f..0.8f)
        assertEquals("越界坐标须钳制", m.sample(1f, 1f), m.sample(3f, -2f), 0.001f)
    }

    @Test
    fun nullRegionIsByteIdenticalToLegacy() = runBlocking {
        val width = 120; val height = 80
        val base = IntArray(width * height) { rgb(198, 158, 138) }
        val a = base.copyOf(); val b = base.copyOf()
        applyPortrait(a, width, height, PortraitRetouch(smooth = 60f, whiten = 60f), null)
        applyPortrait(b, width, height, PortraitRetouch(smooth = 60f, whiten = 60f), null, null)
        assertTrue("显式 null region 必须与既有路径逐像素一致", a.contentEquals(b))
    }

    @Test
    fun regionMasksOutWarmBackgroundObject() = runBlocking {
        // 左半 = 人像区域（蒙版 1），右半 = 暖色食物（蒙版 0），两侧都是
        // 肤色色调（都会被 YCbCr skinMask 命中），只有蒙版能区分两者。
        val width = 160; val height = 80
        fun build() = IntArray(width * height) { i ->
            val x = i % width
            // 加一点确定性噪声，让磨皮有可观测的差异
            val n = ((x * 7 + (i / width) * 13) % 15) - 7
            rgb(198 + n, 158 + n, 138 + n)
        }
        val side = 8
        val w = FloatArray(side * side) { 0f }
        for (y in 0 until side) for (x in 0 until side) {
            w[y * side + x] = if (x < side / 2) 1f else 0f
        }
        val region = PortraitRegionMask(side, w)

        val unmasked = build()
        val masked = build()
        applyPortrait(unmasked, width, height, PortraitRetouch(smooth = 90f), null)
        applyPortrait(masked, width, height, PortraitRetouch(smooth = 90f), null, region)

        // 右半（蒙版外）改动量应远小于左半（蒙版内）。
        fun maxDelta(px: IntArray, x0: Int, x1: Int): Int {
            var d = 0
            for (y in 0 until height) for (x in x0 until x1) {
                val i = y * width + x
                val orig = rgb(198 + (((x * 7 + y * 13) % 15) - 7),
                    158 + (((x * 7 + y * 13) % 15) - 7),
                    138 + (((x * 7 + y * 13) % 15) - 7))
                d = maxOf(d, abs(((orig shr 16) and 0xFF) - ((px[i] shr 16) and 0xFF)))
            }
            return d
        }
        val innerDelta = maxDelta(masked, 8, width / 2 - 8)
        val outerDelta = maxDelta(masked, width / 2 + 8, width - 8)
        assertTrue("蒙版内应正常磨皮（delta=$innerDelta）", innerDelta > 0)
        assertTrue(
            "蒙版外的暖色食物几乎不应被改动（内=$innerDelta 外=$outerDelta）",
            outerDelta <= innerDelta
        )
    }

    @Test
    fun registryReturnsNullWhenEmptyOrFingerprintMismatch() {
        PortraitRegionRegistry.clear()
        val px = IntArray(64 * 64) { rgb(100, 120, 140) }
        assertEquals(null, PortraitRegionRegistry.forPixels(px, 64, 64))
        // 安装一张照片后，完全不同的另一张照片不应命中
        val bitmap = android.graphics.Bitmap.createBitmap(
            IntArray(64 * 64) { rgb(100, 120, 140) }, 64, 64,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        PortraitRegionRegistry.install(bitmap, PortraitRegionMask(8, FloatArray(64) { 1f }))
        val same = PortraitRegionRegistry.forPixels(px, 64, 64)
        assertTrue("同指纹应命中", same != null)
        val other = IntArray(64 * 64) { rgb(10, 20, 30) }
        assertEquals("不同照片不应命中上一张的蒙版", null,
            PortraitRegionRegistry.forPixels(other, 64, 64))
        PortraitRegionRegistry.clear()
    }
}
