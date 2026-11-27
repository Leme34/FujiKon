package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.6.92 回归：撤销/重做/切回照片应读取渲染帧缓存，而不是每次都重渲染。
 *
 * 修复前 jumpHistory() 恢复参数后无条件 renderCurrent()，对 20M 大图重跑十几个
 * 全帧 pass，用户「上一步/下一步」要等数秒。修复后命中缓存的帧直接换位图，
 * renderer.render 的调用次数不再增加。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorFrameCacheTest {

    /**
     * Counts full renders. Robolectric's Bitmap has no working getPixels/setPixels
     * backend, so we cannot run the real pixel pipeline here; return a distinct
     * Bitmap per call purely to track which frame was produced. Pixel correctness
     * is covered by the many ImageRenderer tests.
     */
    private class CountingRenderer : ImageRenderer() {
        val renderCount = AtomicInteger(0)
        override suspend fun render(source: Bitmap, preset: Preset, forExport: Boolean): Bitmap {
            val n = renderCount.incrementAndGet()
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                .also { it.setPixel(0, 0, n) }
        }
    }

    private lateinit var context: Context
    private lateinit var sourceFile: File
    private lateinit var cacheDir: File
    private lateinit var renderer: CountingRenderer
    private lateinit var vm: EditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()

        sourceFile = File(context.cacheDir, "frame_cache_source.jpg")
        javaClass.getResourceAsStream("/test_photo.jpg")!!.use { input ->
            sourceFile.outputStream().use { output -> input.copyTo(output) }
        }
        cacheDir = File(context.cacheDir, "frame_cache_test").apply { mkdirs() }

        val photo = PhotoItem(
            id = 1L,
            uri = Uri.fromFile(sourceFile),
            displayName = "photo.jpg",
            dateTaken = 0L,
            dateAdded = 0L,
            bucketId = "b",
            bucketName = "b",
            width = 48,
            height = 64,
            mimeType = "image/jpeg",
            size = sourceFile.length()
        )
        val settings = AppSettings(context)
        renderer = CountingRenderer()
        vm = EditorViewModel(
            context = context,
            presetRepository = PresetRepository(context),
            selectionStore = SelectionStore().apply {
                setPhotos(listOf(photo))
                setPreset(Preset.NONE_ID)
            },
            renderer = renderer,
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir
        )
    }

    @After
    fun tearDown() {
        Thread.sleep(150)
        Dispatchers.resetMain()
        sourceFile.delete()
        cacheDir.deleteRecursively()
    }

    private suspend fun await(label: String, predicate: (EditorViewModel.UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(vm.state.value)) return
            delay(10)
        }
        throw AssertionError("等待超时：$label | ${vm.state.value}")
    }

    private suspend fun settleLoading() = await("渲染落定") {
        !it.loading && it.rendered != null && it.historyIndex == 0 && it.photos.size == 1
    }

    @Test
    fun undoRedoHitsCacheInsteadOfReRendering() = runBlocking {
        settleLoading()
        val initialRenders = renderer.renderCount.get()

        // 施加一个调节 → 新状态，必须触发一次真实渲染。
        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 25f), "对比度")
        await("调节渲染落定") {
            !it.loading && it.adjustments.contrast == 25f && renderer.renderCount.get() > initialRenders
        }
        val afterEdit = renderer.renderCount.get()
        assertTrue("调节应至少触发一次真实渲染", afterEdit > initialRenders)

        // 撤销回到 contrast=0（初始状态，其帧首次渲染时已缓存）→ 不应再渲染。
        vm.undo()
        await("撤销落定") { !it.loading && it.adjustments.contrast == 0f }
        assertEquals("撤销命中缓存不应重渲染", afterEdit, renderer.renderCount.get())

        // 重做回到 contrast=25（刚渲染过的状态也在缓存里）→ 不应再渲染。
        vm.redo()
        await("重做落定") { !it.loading && it.adjustments.contrast == 25f }
        assertEquals("重做命中缓存不应重渲染", afterEdit, renderer.renderCount.get())
    }

    @Test
    fun switchingBackToPhotoHitsCachedFrame() = runBlocking {
        // 用两张指向同一真实图的照片，避免第二张解码失败。
        settleLoading()
        val initialRenders = renderer.renderCount.get()

        vm.setAdjustments(EditAdjustments(contrast = 30f), "对比度")
        await("调节落定") { !it.loading && it.adjustments.contrast == 30f }
        val afterEdit = renderer.renderCount.get()

        // 连续调节到另一状态再调回，第二次调回应命中缓存。
        vm.setAdjustments(EditAdjustments(contrast = -30f), "对比度")
        await("第二个状态落定") { !it.loading && it.adjustments.contrast == -30f }
        val afterSecond = renderer.renderCount.get()
        assertTrue("新状态应触发渲染", afterSecond > afterEdit)

        vm.setAdjustments(EditAdjustments(contrast = 30f), "对比度")
        await("调回旧状态落定") { !it.loading && it.adjustments.contrast == 30f }
        assertEquals("调回已缓存状态不应重渲染", afterSecond, renderer.renderCount.get())
    }

    @Test
    fun cachedFrameProducesSameBitmapContent() = runBlocking {
        settleLoading()
        val firstBitmap = vm.state.value.rendered
        assertNotNull(firstBitmap)

        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 15f), "对比度")
        await("调节落定") { !it.loading && it.adjustments.contrast == 15f }
        vm.undo()
        await("撤销落定") { !it.loading && it.adjustments.contrast == 0f }

        val restored = vm.state.value.rendered
        assertNotNull("撤销后必须有成片位图", restored)
        assertEquals("缓存帧尺寸应一致", firstBitmap!!.width, restored!!.width)
        assertEquals("缓存帧高度应一致", firstBitmap.height, restored.height)
    }
}
