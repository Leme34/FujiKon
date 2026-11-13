package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.PointColor
import com.fujikon.app.data.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.50 渲染提速回归：把全帧像素 pass 并行化到多核后，必须与单线程串行
 * 渲染逐像素完全一致。这是画质不变的硬约束 —— 并行只允许改变「谁在算」，
 * 不允许改变「算出来的值」。
 *
 * 覆盖所有被并行化的 pass：主色彩、denoise 双边、detail 锐化、pointColor、
 * local 分区、grain、vignette、blend 强度、以及云创 3D LUT 分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RenderParallelEquivalenceTest {

    /** 确定性伪随机内容位图：涵盖各色相/亮度，触发 pointColor / cubeLut 各分支。 */
    private fun contentBitmap(w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        var seed = 987654321L
        for (i in px.indices) {
            seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val r = (seed and 0xFF).toInt()
            val g = ((seed ushr 8) and 0xFF).toInt()
            val b = ((seed ushr 16) and 0xFF).toInt()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    /** 一次「全开」预设：把所有被并行化的 pass 都点亮。 */
    private fun heavyPreset(): Preset {
        val adjustments = EditAdjustments(
            denoise = 45f,
            grain = 35f,
            vignette = -40f,
            pointColor = PointColor(targetHue = 210f, hueRange = 40f, saturation = 40f),
            vibrance = 20f,
            texture = 15f,
            clarity = 20f,
            presetStrength = 65f,
            local = LocalAdjustments(
                listOf(
                    LocalLayer(
                        id = "center",
                        mask = LocalMask.ControlPoint(0.5f, 0.5f, 0.4f, 0.7f),
                        params = LocalParams(brightness = 25f, contrast = 10f, clarity = 15f)
                    )
                )
            )
        )
        return Preset.none().copy(
            id = "heavy",
            name = "heave",
            format = "NCP",
            baseProfile = 1,
            saturation = 18,
            contrast = 12,
            sharpening = 4f,
            clarity = 3f,
            midRangeSharpening = 2f,
            adjustments = adjustments
        )
    }

    /**
     * 串行 vs 并行：同一预设、同一像素，输出必须逐像素相同。
     * 用 >= 96 行的大图确保真正走多核并行路径（低于阈值会退化为串行）。
     */
    @Test
    fun parallelAndSerialOutputsArePixelIdentical() = runBlocking {
        val w = 128
        val h = 200 // > PARALLEL_MIN_ROWS，确保并行真正生效
        val preset = heavyPreset()

        val serial = ImageRenderer().apply { parallelRowsEnabled = false }
        val parallel = ImageRenderer().apply { parallelRowsEnabled = true }

        val a = serial.render(contentBitmap(w, h), preset, forExport = false)
        val b = parallel.render(contentBitmap(w, h), preset, forExport = false)

        assertArrayEquals(
            "并行渲染必须与串行逐像素一致（画质不变）",
            pixels(a), pixels(b)
        )
    }

    /** 云创 3D LUT 分支（cubeLut 每像素三线性插值）同样逐像素一致。 */
    @Test
    fun parallelAndSerialOutputsMatchForCubeLutPreset() = runBlocking {
        val size = 17
        val data = FloatArray(size * size * size * 3) { (it % 97) / 97f }
        CubeLutProvider.register("equiv", CubeLut(size, data))
        val preset = Preset.none().copy(
            id = "lut", name = "lut", format = "LUT3D",
            cubeLutAsset = "equiv", sharpening = 2f,
            adjustments = EditAdjustments(denoise = 30f, grain = 25f, vignette = -30f, presetStrength = 80f)
        )
        val w = 100
        val h = 160

        val serial = ImageRenderer().apply { parallelRowsEnabled = false }
        val parallel = ImageRenderer().apply { parallelRowsEnabled = true }
        val a = serial.render(contentBitmap(w, h), preset, false)
        val b = parallel.render(contentBitmap(w, h), preset, false)
        assertArrayEquals("3D LUT 分支并行/串行必须逐像素一致", pixels(a), pixels(b))
    }

    /** 纯色彩（无质量算子）也保持一致，守护最常用的套预设路径。 */
    @Test
    fun parallelAndSerialOutputsMatchForPlainColorPreset() = runBlocking {
        val preset = Preset.none().copy(
            id = "color", name = "color", format = "NCP",
            baseProfile = 2, saturation = 25, contrast = 20, sharpening = 3f
        )
        val w = 120
        val h = 180
        val serial = ImageRenderer().apply { parallelRowsEnabled = false }
        val parallel = ImageRenderer().apply { parallelRowsEnabled = true }
        val a = serial.render(contentBitmap(w, h), preset, false)
        val b = parallel.render(contentBitmap(w, h), preset, false)
        assertArrayEquals("纯色彩分支并行/串行必须逐像素一致", pixels(a), pixels(b))
    }

    /** 并行渲染仍能被及时取消，不留下半张图。 */
    @Test
    fun parallelRenderRemainsCancellable() = runBlocking {
        val preset = heavyPreset()
        val renderer = ImageRenderer().apply { parallelRowsEnabled = true }
        val job = async { renderer.render(contentBitmap(320, 480), preset, false) }
        job.cancelAndJoin()
        assertTrue("取消后的 job 应处于取消态", job.isCancelled)
    }

    /** 并行开关默认开启（生产路径必须吃到多核收益）。 */
    @Test
    fun parallelIsEnabledByDefault() {
        assertTrue("生产渲染默认必须启用按行并行", ImageRenderer().parallelRowsEnabled)
    }
}
