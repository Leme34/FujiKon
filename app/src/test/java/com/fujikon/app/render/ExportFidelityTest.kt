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
 * 1.6.71 导出画质回归防线。
 *
 * 用户反馈「原图十几 M，导出后只有 500K，画质损失太严重」。根因有两处：
 *  ① ExportManager 旧并发策略把单张解码上限按 √并发砍半（4900→2450）；
 *  ② [BitmapLoader.decodeFullLimited] 旧实现只用 2 的幂采样，2450 又断崖到
 *     1225，十几 MP 原图最终只剩约 1.5MP，JPG 即使 quality=100 也只有几百 K。
 *
 * 本测试锁定：decodeFullLimited 的输出最长边必须**贴近**给定上限（不再被
 * 2 的幂砍到上限的一半以下），从而保证导出分辨率不再被无故腰斩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportFidelityTest {

    /** 生成一张指定尺寸的渐变图并写入缓存，返回 file:// Uri。 */
    private fun writeImage(ctx: Context, name: String, w: Int, h: Int): Uri {
        val file = File(ctx.cacheDir, name)
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (0xFF shl 24) or ((x and 0xFF) shl 16) or ((y and 0xFF) shl 8) or ((x + y) and 0xFF)
        }
        val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
        return Uri.fromFile(file)
    }

    /**
     * 典型相机竖拍：长边 6016、上限 4900。
     * 旧 2 的幂公式 sample=2 → 3008（仅上限的 61%）；新实现必须精调到接近 4900。
     */
    @Test
    fun tallCameraPhotoDecodesNearTheCapNotHalf(): Unit = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val uri = writeImage(ctx, "export_tall.jpg", 3384, 6016)
        val cap = 4900

        val decoded = BitmapLoader.decodeFullLimited(ctx, uri, cap)
        assertNotNull("导出解码不应失败", decoded)
        val longest = maxOf(decoded!!.width, decoded.height)
        assertTrue("导出最长边($longest)不得超过上限($cap)", longest <= cap)
        assertTrue(
            "导出最长边($longest)必须贴近上限($cap)，不应被 2 的幂砍到一半以下",
            longest >= cap * 0.9
        )
        decoded.recycle()
    }

    /**
     * 临界尺寸：长边 5200、上限 4900（刚超过上限）。
     * 旧公式 sample=2 → 2600（腰斩）；新实现密度精调应≈4900。
     */
    @Test
    fun justOverCapStillDecodesNearCap(): Unit = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val uri = writeImage(ctx, "export_near.jpg", 3900, 5200)
        val cap = 4900

        val decoded = BitmapLoader.decodeFullLimited(ctx, uri, cap)
        assertNotNull(decoded)
        val longest = maxOf(decoded!!.width, decoded.height)
        assertTrue("不得超过上限: $longest", longest <= cap)
        assertTrue("临界尺寸也不应腰斩($longest)", longest >= cap * 0.9)
        decoded.recycle()
    }

    /** 小于上限的图必须原样保留，绝不放大。 */
    @Test
    fun smallPhotoIsNotUpscaled(): Unit = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val uri = writeImage(ctx, "export_small.jpg", 1200, 1600)
        val cap = 4900

        val decoded = BitmapLoader.decodeFullLimited(ctx, uri, cap)
        assertNotNull(decoded)
        assertEquals(1600, maxOf(decoded!!.width, decoded.height))
        decoded.recycle()
    }
}
