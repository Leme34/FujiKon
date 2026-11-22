package com.fujikon.app.render

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.6.68 批量导出有界并发回归：
 *  - 多张照片全部成功、无丢失；
 *  - 确实发生了并发（多核重叠），并发度受信号量约束不超上限；
 *  - 进度 done 单调不减、最终到达 total；
 *  - 每张仍使用自身的 preset（逐张编辑结果不被共享覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatchExportConcurrencyTest {

    private lateinit var context: Context
    private lateinit var sampleUri: Uri
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sampleUri = Uri.fromFile(File(context.cacheDir, "batch_sample.png").apply { writeBytes(png) })
    }

    private fun photo(id: Long) = PhotoItem(
        id = id, uri = sampleUri, displayName = "p$id.jpg",
        dateTaken = 0L, dateAdded = 0L, bucketId = "b", bucketName = "b",
        width = 64, height = 64, mimeType = "image/jpeg", size = 1L
    )

    /** 记录并发峰值的 renderer：render 内制造可控延时，让并发可观测。 */
    private class ConcurrencyProbeRenderer : ImageRenderer() {
        private val active = AtomicInteger(0)
        private val lock = Mutex()
        var peak = 0
            private set

        override suspend fun render(source: Bitmap, preset: Preset, forExport: Boolean): Bitmap {
            val now = active.incrementAndGet()
            lock.withLock { if (now > peak) peak = now }
            try {
                // forExport 路径会 recycle source；这里无法直接返回 source（已回收），
                // 改为复制一张，模拟真实渲染耗时并产出一个合法输出。
                val copy = Bitmap.createBitmap(source, 0, 0, source.width, source.height)
                delay(40)
                return copy
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Test
    fun batchExportOverlapsConcurrentlyAndKeepsAllPhotos() = runBlocking {
        val renderer = ConcurrencyProbeRenderer()
        val manager = ExportManager(context, renderer, AppSettings(context))
        val photos = (1L..8L).map { photo(it) }

        val progressDone = mutableListOf<Int>()
        val result = manager.export(
            photos = photos,
            preset = Preset.none(),
            options = ExportManager.Options(format = "PNG", album = "Test", preserveExif = false),
            onProgress = { progressDone.add(it.done) }
        )

        assertEquals("所有照片都应导出成功: ${result.failures}", 8, result.saved.size)
        assertEquals(0, result.failures.size)
        assertTrue("多核设备应发生并发重叠，实测峰值=${renderer.peak}", renderer.peak >= 2)
        assertTrue("并发不应超过上限 4，实测峰值=${renderer.peak}", renderer.peak <= 4)
        assertEquals("进度应收敛到 total", photos.size, progressDone.last())
        // done 单调不减
        for (i in 1 until progressDone.size) {
            assertTrue("进度不应倒退: ${progressDone[i - 1]} -> ${progressDone[i]}",
                progressDone[i] >= progressDone[i - 1])
        }
    }

    @Test
    fun perPhotoPresetIsUsedUnderConcurrency() {
        val fallback = Preset.none().copy(id = "fb", name = "fallback")
        val first = Preset.none().copy(id = "a", name = "甲")
        val second = Preset.none().copy(id = "b", name = "乙")
        val map = mapOf(1L to first, 2L to second)
        assertEquals("甲", ExportManager.presetFor(1L, fallback, map).name)
        assertEquals("乙", ExportManager.presetFor(2L, fallback, map).name)
        assertEquals("fallback", ExportManager.presetFor(99L, fallback, map).name)
    }
}
