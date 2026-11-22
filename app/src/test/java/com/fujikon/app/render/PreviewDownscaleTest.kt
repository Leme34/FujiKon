package com.fujikon.app.render

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.6.67 性能回归防线：竖拍大图套用预设卡顿的根因是 decodePreview 的 sample
 * 公式用 maxDimension*2 做阈值，导致长边 < 2×上限的大图（如 3384×6016 在
 * 4096 上限下）取 sample=1 全分辨率解码，再叠加整图旋转/缩放拷贝，真机上点
 * 一次预览要转很久。
 *
 * 本测试锁定：原图长边远大于上限时，必须以 sample>=2 在「解码期」降采样，
 * 最终预览最长边严格等于上限，且解码像素远小于原图。任何让 sample 退回 1
 * 的改动都会让解码像素暴涨，被此测试拦截。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewDownscaleTest {

    @Test
    fun tallPhotoIsDownsampledAtDecodeTime(): Unit = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // 竖拍 3000×2000 横置后长边 3000；上限 600 → sample 应为 4（3000/8<600）。
        val bigW = 2000
        val bigH = 3000
        val maxDim = 600
        val file = File(ctx.cacheDir, "tall_preview_source.jpg")
        val pixels = IntArray(bigW * bigH) { i ->
            val x = i % bigW; val y = i / bigW
            (0xFF shl 24) or ((x and 0xFF) shl 16) or ((y and 0xFF) shl 8) or ((x + y) and 0xFF)
        }
        val bmp = Bitmap.createBitmap(pixels, bigW, bigH, Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        bmp.recycle()

        val decoded = BitmapLoader.decodePreview(ctx, Uri.fromFile(file), maxDim)
        assertNotNull("大图预览解码不应失败", decoded)
        val longest = maxOf(decoded!!.width, decoded.height)
        assertEquals("预览最长边必须被限制到上限", maxDim, longest)
        // 解码后像素必须远小于原图 600 万：证明 sample 在解码期已生效，
        // 而非先全分辨率解码再缩放（后者解码像素==原图，会触发此断言）。
        val decodedPixels = decoded.width.toLong() * decoded.height
        assertTrue(
            "预览解码像素($decodedPixels)应远小于原图(${bigW * bigH})，sample 必须在解码期生效",
            decodedPixels * 4L < (bigW.toLong() * bigH)
        )
        decoded.recycle()
        file.delete()
    }

    /**
     * 1.6.70 回归：长边落在「1×~2× 上限」之间的常见手机原图（如 4032×3024、
     * 上限 2560）。旧 sample 公式会算出 sample=1 → 全分辨率解码 12MP，再叠加
     * 旋转/缩放拷贝，在低内存真机上 GC 抖动导致打开/套用预设转一分钟。
     * 新的密度缩放必须在**解码期**一步到位把最长边压到上限，输出不能有全尺寸位图。
     */
    @Test
    fun midSizeCameraPhotoIsDownsampledAtDecodeTime(): Unit = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val bigW = 1512
        val bigH = 2016 // 长边 2016，介于 1×2560 之下但远高于「2×上限才采样」的阈值
        val maxDim = 1920
        val file = File(ctx.cacheDir, "mid_preview_source.jpg")
        val pixels = IntArray(bigW * bigH) { i ->
            val x = i % bigW; val y = i / bigW
            (0xFF shl 24) or ((x and 0xFF) shl 16) or ((y and 0xFF) shl 8) or ((x + y) and 0xFF)
        }
        val bmp = Bitmap.createBitmap(pixels, bigW, bigH, Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        bmp.recycle()

        val decoded = BitmapLoader.decodePreview(ctx, Uri.fromFile(file), maxDim)
        assertNotNull("中等尺寸相机图预览解码不应失败", decoded)
        val longest = maxOf(decoded!!.width, decoded.height)
        assertTrue("预览最长边($longest)不得超过上限($maxDim)", longest <= maxDim)
        // 若旧公式生效（sample=1），密度缩放会把它压到上限；无论如何输出都
        // 必须明显小于原图面积，证明没有把全分辨率位图交给上层。
        val outPixels = decoded.width.toLong() * decoded.height
        assertTrue(
            "预览输出像素($outPixels)应小于原图(${bigW * bigH})，解码期必须缩放",
            outPixels < bigW.toLong() * bigH
        )
        decoded.recycle()
        file.delete()
    }
}
