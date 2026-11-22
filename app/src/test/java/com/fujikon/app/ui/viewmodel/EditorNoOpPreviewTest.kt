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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.6.70 回归：预设「无」且无任何调节时没有可施加的像素处理。修复前即使
 * "无" 也会跑完整条多 pass 渲染管线 + 多份全帧拷贝，在低内存真机上表现为
 * 打开照片/套用预设转圈很久。修复后应直接复用解码位图、跳过 renderer.render。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorNoOpPreviewTest {

    /** 记录 render() 调用次数；不真正改像素。 */
    private class CountingRenderer : ImageRenderer() {
        val calls = AtomicInteger(0)
        override suspend fun render(source: Bitmap, preset: Preset, forExport: Boolean): Bitmap {
            calls.incrementAndGet()
            return super.render(source, preset, forExport)
        }
    }

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var photo: PhotoItem
    private lateinit var sourceFile: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "noop_preview_test").apply { mkdirs() }
        sourceFile = File(cacheDir, "source.jpg")
        // 一张真实可读的 64x64 图，保证 decodePreview 成功
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(sourceFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        photo = PhotoItem(
            id = 1L, uri = Uri.fromFile(sourceFile), displayName = "source.jpg",
            dateTaken = 0L, dateAdded = 0L, bucketId = "b", bucketName = "b",
            width = 64, height = 64, mimeType = "image/jpeg", size = sourceFile.length()
        )
    }

    @After
    fun tearDown() {
        Thread.sleep(100)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun newViewModel(renderer: ImageRenderer): EditorViewModel {
        val settings = AppSettings(context)
        return EditorViewModel(
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

    private suspend fun awaitIdle(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = vm.state.value
            if (s.photos.size == 1 && !s.loading && s.rendered != null) return
            delay(10)
        }
        throw AssertionError("预览未落定: ${vm.state.value}")
    }

    @Test
    fun noOpPresetSkipsFullRenderAndSettles() = runBlocking {
        val renderer = CountingRenderer()
        val vm = newViewModel(renderer)
        awaitIdle(vm)

        assertFalse("无操作预设不应卡在 loading", vm.state.value.loading)
        assertEquals("无操作预设绝不能跑渲染管线", 0, renderer.calls.get())
        // 成片与原图、裁剪底图共享同一解码位图，无任何像素拷贝
        val s = vm.state.value
        assertSame("rendered 应直接复用 original", s.original, s.rendered)
        assertSame("cropBase 应直接复用 original", s.original, s.cropBase)
        assertTrue("预览必须有可见像素", s.rendered!!.width == 64 && s.rendered.height == 64)
    }

    @Test
    fun realAdjustmentStillRunsRender() = runBlocking {
        val renderer = CountingRenderer()
        val vm = newViewModel(renderer)
        awaitIdle(vm)

        vm.setAdjustments(EditAdjustments(exposure = 20f))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && renderer.calls.get() == 0) delay(10)
        // 等待该次渲染落定
        while (System.currentTimeMillis() < deadline && vm.state.value.loading) delay(10)

        assertTrue("有真实调节时必须走渲染管线", renderer.calls.get() >= 1)
        assertFalse("调节后不应卡在 loading", vm.state.value.loading)
    }
}
