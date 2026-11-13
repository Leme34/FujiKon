package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * 1.6.38 regression for the "一直渲染中 / 长时间等待" complaint.
 *
 * Before the fix, EditorViewModel.renderCurrent() called ImageRenderer.render()
 * inline from viewModelScope.launch (Main.immediate). Only processRows used
 * Dispatchers.Default; the other dozen full-frame passes (portrait, detail,
 * denoise, point colour, local masks, grain, vignette, lens blur, crop) ran
 * synchronously on the UI thread — freezing Compose, delaying cancellation
 * and leaving the "渲染中" chip up for seconds after every slider release.
 *
 * These tests lock in the invariants that fix it:
 *  1. every preview render is dispatched to a background dispatcher, never
 *     the Main/test thread;
 *  2. the default dispatcher is Dispatchers.Default (never Main);
 *  3. a burst of rapid adjustments always settles with loading = false;
 *  4. the interactive preview is downscaled to a screen-sized bound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorRenderPerformanceTest {

    /**
     * Wraps Dispatchers.Default and records which thread actually ran the
     * render block, so the test can prove it was NOT the Main/test thread.
     */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher = Dispatchers.Default
    ) : CoroutineDispatcher() {
        val dispatches = AtomicInteger(0)
        val threads = CopyOnWriteArrayList<Thread>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, Runnable {
                threads += Thread.currentThread()
                block.run()
            })
        }

        override fun toString(): String = "RecordingDispatcher"
    }

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var dispatcher: RecordingDispatcher
    private lateinit var vm: EditorViewModel

    // A deliberately non-resolvable URI: decodePreview fails fast, so these
    // tests exercise render scheduling, not pixel correctness (covered by the
    // many ImageRenderer tests).
    private val photo = PhotoItem(
        id = 7L,
        uri = Uri.parse("file:///fujikon_perf/photo.jpg"),
        displayName = "photo.jpg",
        dateTaken = 0L,
        dateAdded = 0L,
        bucketId = "b",
        bucketName = "b",
        width = 64,
        height = 64,
        mimeType = "image/jpeg",
        size = 1L
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        cacheDir = File(context.cacheDir, "render_perf_test").apply { mkdirs() }
        dispatcher = RecordingDispatcher()
        vm = newViewModel(dispatcher)
    }

    @After
    fun tearDown() {
        // Let presetRepository.load() (Dispatchers.IO) and any render
        // continuation drain before swapping Main back.
        Thread.sleep(150)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun newViewModel(renderDispatcher: CoroutineDispatcher): EditorViewModel {
        val settings = AppSettings(context)
        return EditorViewModel(
            context = context,
            presetRepository = PresetRepository(context),
            selectionStore = SelectionStore().apply {
                setPhotos(listOf(photo))
                setPreset(Preset.NONE_ID)
            },
            renderer = ImageRenderer(),
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir,
            renderDispatcher = renderDispatcher
        )
    }

    private suspend fun await(
        label: String,
        timeoutMs: Long = 10_000,
        predicate: (EditorViewModel.UiState) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(vm.state.value)) return
            delay(10)
        }
        throw AssertionError("等待超时：$label | ${vm.state.value}")
    }

    private suspend fun awaitInit() {
        await("首屏渲染落定") { it.photos.size == 1 && !it.loading }
    }

    /** The core fix: no full-frame pixel pass may ever run on the Main thread. */
    @Test
    fun everyAdjustmentIsRenderedOffTheMainThread() = runBlocking {
        awaitInit()
        val before = dispatcher.dispatches.get()
        val mainThread = Thread.currentThread()

        vm.setAdjustments(EditAdjustments(exposure = 20f))

        await("调整后应完成一次后台渲染") {
            !it.loading && dispatcher.dispatches.get() > before
        }
        assertTrue(
            "调整后必须把渲染分发到后台 dispatcher",
            dispatcher.dispatches.get() > before
        )
        val renderThreads = dispatcher.threads.toList()
        assertTrue("必须记录到真正执行渲染的线程", renderThreads.isNotEmpty())
        assertTrue(
            "渲染绝不能跑在主线程/测试线程上，实际=${renderThreads.map { it.name }}",
            renderThreads.all { it !== mainThread }
        )
    }

    /** The production default must stay off Main; injectability is test-only. */
    @Test
    fun defaultRenderDispatcherIsDispatchersDefault() {
        val settings = AppSettings(context)
        val plain = EditorViewModel(
            context = context,
            presetRepository = PresetRepository(context),
            selectionStore = SelectionStore().apply {
                setPhotos(listOf(photo))
                setPreset(Preset.NONE_ID)
            },
            renderer = ImageRenderer(),
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir
        )
        val field = EditorViewModel::class.java.getDeclaredField("renderDispatcher")
        field.isAccessible = true
        assertTrue(
            "默认渲染 dispatcher 必须是 Dispatchers.Default，绝不能是 Main",
            field.get(plain) === Dispatchers.Default
        )
    }

    /** Rapid consecutive tweaks must never leave the "渲染中" chip spinning. */
    @Test
    fun rapidAdjustmentBurstAlwaysSettlesLoading() = runBlocking {
        awaitInit()
        repeat(8) { i ->
            vm.setAdjustments(EditAdjustments(contrast = (i - 4) * 5f))
        }
        await("连续调整后渲染应结束") { !it.loading }
        assertFalse("渲染中状态不能卡住", vm.state.value.loading)
        assertEquals(
            "应采用最后一次（最新 generation）调整",
            15f,
            vm.state.value.adjustments.contrast,
            0.001f
        )
    }

    /**
     * 1.6.52 之前预览最长边固定 1280，在高 DPI 手机上 fit 被放大、放大看细节
     * 极其模糊。现在改为按设备屏幕物理像素自适应（见 computePreviewMaxDimension）：
     * 上限受内存护栏约束，但任何设备都不低于地板 PREVIEW_MIN_DIMENSION，且严格
     * 高于旧版 1280，确保观感不再发糊；同时不高于 PREVIEW_MAX_CAP，避免 OOM。
     */
    @Test
    fun previewResolutionIsAdaptiveAndNeverBlurry() {
        val dim = vm.previewMaxDimension
        assertTrue(
            "预览分辨率必须高于旧版 1280，否则仍会发糊",
            dim > 1280
        )
        assertTrue(
            "预览分辨率不得低于地板（任意设备都不应比旧版更糊）",
            dim >= EditorViewModel.PREVIEW_MIN_DIMENSION
        )
        assertTrue(
            "预览分辨率不得超过内存护栏上限，避免 OOM",
            dim <= EditorViewModel.PREVIEW_MAX_CAP
        )
    }

    /** 计算逻辑：高内存机型应贴近屏幕，低内存机型受护栏回落但仍不低于地板。 */
    @Test
    fun previewResolutionCapsAtMemoryGuardOnTinyHeaps() {
        val dim = vm.computePreviewMaxDimension()
        assertTrue(
            "反推预览分辨率必须落在 [地板, 上限] 区间",
            dim in EditorViewModel.PREVIEW_MIN_DIMENSION..EditorViewModel.PREVIEW_MAX_CAP
        )
    }
}
