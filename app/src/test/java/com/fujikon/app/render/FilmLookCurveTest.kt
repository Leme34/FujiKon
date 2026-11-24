package com.fujikon.app.render

import android.app.Application
import android.graphics.Bitmap
import com.fujikon.app.data.ChannelToneCurves
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurveChannel
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.dazz.DazzFujiPresets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归：套用 3D LUT 胶片滤镜后，用户手调的 RGB 主曲线 / 逐通道曲线必须仍然生效。
 *
 * 1.6.74 之前，ImageRenderer 的 filmLut 分支直接采用 3D LUT 采样结果，跳过了
 * 烘有用户曲线的 1D LUT 链，导致 curve 菜单调整对 5 款 Dazz 滤镜完全无效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FilmLookCurveTest {

    private val renderer = ImageRenderer()

    /** 对全部 5 款 3D LUT 滤镜：主曲线压暗后整体亮度必须下降。 */
    @Test
    fun masterCurveChangesEveryDazzFilmLook() = runBlocking {
        val source = grayRamp()
        val darkCurve = ToneCurve(
            listOf(ToneCurvePoint(0f, 0f), ToneCurvePoint(1f, 0.35f))
        )
        DazzFujiPresets.all().forEach { preset ->
            val base = renderer.render(source, preset, forExport = false)
            val curved = renderer.render(
                source, preset.copy(toneCurve = darkCurve), forExport = false
            )
            val delta = averageLuma(base) - averageLuma(curved)
            assertTrue(
                "${preset.name}：套用主曲线后应显著变暗，实测 delta=$delta",
                delta > 12f
            )
        }
    }

    /** 逐通道曲线：红通道压暗后，红色均值必须下降且低于未调整版本。 */
    @Test
    fun channelCurveChangesDazzFilmLook() = runBlocking {
        val source = grayRamp()
        val preset = DazzFujiPresets.all().first()
        val redDown = ToneCurve(
            listOf(ToneCurvePoint(0f, 0f), ToneCurvePoint(1f, 0.3f))
        )
        val curves = ChannelToneCurves.Neutral.withCurve(ToneCurveChannel.RED, redDown)
        val base = renderer.render(source, preset, forExport = false)
        val curved = renderer.render(
            source, preset.copy(channelCurves = curves), forExport = false
        )
        val delta = averageChannel(base, 16) - averageChannel(curved, 16)
        assertTrue("红通道曲线应压低红色均值，实测 delta=$delta", delta > 12f)
    }

    /** 未调曲线时画面不变——确保串接 LUT 不改动滤镜原本影调。 */
    @Test
    fun linearCurveLeavesFilmLookUntouched() = runBlocking {
        val source = grayRamp()
        val preset = DazzFujiPresets.all().first()
        val base = renderer.render(source, preset, forExport = false)
        val linear = renderer.render(
            source, preset.copy(toneCurve = ToneCurve.linear()), forExport = false
        )
        assertTrue(
            "线性曲线不应改变滤镜效果",
            kotlin.math.abs(averageLuma(base) - averageLuma(linear)) < 0.01f
        )
    }

    private fun grayRamp(): Bitmap {
        val size = 48
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = (x * 255 / (size - 1)).coerceIn(0, 255)
                pixels[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun averageLuma(bitmap: Bitmap): Float {
        var sum = 0.0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { c ->
            sum += ((c shr 16) and 0xFF) * 0.299 +
                ((c shr 8) and 0xFF) * 0.587 +
                (c and 0xFF) * 0.114
        }
        return (sum / pixels.size).toFloat()
    }

    private fun averageChannel(bitmap: Bitmap, shift: Int): Float {
        var sum = 0L
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { c -> sum += (c shr shift) and 0xFF }
        return sum.toFloat() / pixels.size
    }
}
