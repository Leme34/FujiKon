package com.fujikon.app.render

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * 验证 1.6.51 导出稳定性修复：
 *  - 导出像素上限按真实可用堆反推（不再把字节当像素高估导致 OOM）；
 *  - 单张导出在渲染阶段 OOM 时按最长边逐级降级重试，最终成功（不失败）；
 *  - 非 OOM 错误（解码/编码失败）只记失败、不无谓重试；
 *  - 进程级 ExportCoordinator 能完整跑通 Idle -> Running -> Done。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportResilienceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    /** 真实可解码的 1x1 PNG 文件 URI（Robolectric 下 file:// 解码/保存均可用）。 */
    private lateinit var sampleUri: Uri
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    )

    @Before
    fun setUp() {
        val f = File(context.cacheDir, "sample.png").apply { writeBytes(png) }
        sampleUri = Uri.fromFile(f)
    }

    @Test
    fun exportPixelCapIsMemorySafeNotOverestimated() {
        val px = BitmapLoader.maxExportPixels()
        // 峰值约 18 字节/像素，24M 像素 ≈ 432MB，已是 largeHeap 设备的上限；
        // 旧实现会到 36M 像素(≈648MB) 必然 OOM。这里必须被 clamp 在安全范围。
        assertTrue("导出像素上限不应被高估到 OOM 区间: $px", px <= 24_000_000L)
        assertTrue("导出像素下限应保留足够分辨率: $px", px >= 8_000_000L)
    }

    @Test
    fun exportLongestCapStaysInSafeRange() {
        val longest = BitmapLoader.maxExportLongest()
        assertTrue("导出最长边上限应安全: $longest", longest in 2600..4900)
    }

    private fun smallPhoto(name: String, id: Long) = PhotoItem(
        id = id,
        uri = sampleUri,
        displayName = name,
        dateTaken = 0L, dateAdded = 0L, bucketId = "b", bucketName = "b",
        width = 64, height = 64, mimeType = "image/jpeg", size = 1L
    )

    /** 首 N 次 render 抛 OOM，其后正常返回，用于验证降级重试。 */
    private class FlakyRenderer(private val failFirstRenders: Int) : ImageRenderer() {
        var renderCalls = 0
            private set
        override suspend fun render(source: Bitmap, preset: Preset, forExport: Boolean): Bitmap {
            renderCalls++
            if (renderCalls <= failFirstRenders) {
                throw OutOfMemoryError("simulated render OOM on call #$renderCalls")
            }
            return source
        }
    }

    @Test
    fun renderOomTriggersDowngradeRetryThenSucceeds() = runBlocking {
        val renderer = FlakyRenderer(failFirstRenders = 1) // 满分辨率渲染 OOM，降级后成功
        val manager = ExportManager(context, renderer, AppSettings(context))
        val photo = smallPhoto("a.jpg", 1L)

        val result = manager.export(
            photos = listOf(photo),
            preset = Preset.none(),
            options = ExportManager.Options(format = "PNG", album = "Test", preserveExif = false),
            onProgress = {}
        )

        assertEquals("降级重试后应当成功导出: ${result.failures}", 1, result.saved.size)
        assertTrue("应触发了至少两次渲染(降级)", renderer.renderCalls >= 2)
        assertEquals("不应记录 OOM 为用户可见失败", 0, result.failures.size)
    }

    @Test
    fun nonOomErrorIsNotRetriedAndRecordedOnce() = runBlocking {
        val renderer = object : ImageRenderer() {
            var calls = 0
                private set
            override suspend fun render(source: Bitmap, preset: Preset, forExport: Boolean): Bitmap {
                calls++
                throw IllegalStateException("解码失败模拟")
            }
        }
        val manager = ExportManager(context, renderer, AppSettings(context))
        val photo = smallPhoto("b.jpg", 2L)

        val result = manager.export(
            photos = listOf(photo),
            preset = Preset.none(),
            options = ExportManager.Options(format = "PNG", album = "Test", preserveExif = false),
            onProgress = {}
        )

        assertEquals("非 OOM 错误不应重试", 1, renderer.calls)
        assertEquals("应记录一条失败", 1, result.failures.size)
        assertTrue(result.failures.first().contains("解码失败模拟"))
    }

    @Test
    fun coordinatorRunsToCompletionAcrossLifecycleScope() = runBlocking {
        val coordinator = ExportCoordinator(
            ExportManager(context, ImageRenderer(), AppSettings(context)),
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
        val photos = listOf(smallPhoto("c.jpg", 3L), smallPhoto("d.jpg", 4L))

        coordinator.export(
            photos = photos,
            preset = Preset.none(),
            options = ExportManager.Options(format = "PNG", album = "Test", preserveExif = false)
        )
        val final = coordinator.status.first {
            it is ExportCoordinator.Status.Done || it is ExportCoordinator.Status.Failed
        }
        assertTrue("协调器应导出到完成: $final", final is ExportCoordinator.Status.Done)
        assertEquals(2, (final as ExportCoordinator.Status.Done).saved)
    }
}
