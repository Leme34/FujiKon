package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.data.AppSettings
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression tests for the multi-photo "回退上一步" defect (1.6.27), kept as
 * guards after the 1.6.28 per-photo history refactor.
 *
 * Undo / redo / jumping through the CURRENT PHOTO's history must restore that
 * photo's look while keeping the user on the photo they are editing — it must
 * never teleport back to the first photo, and (1.6.28) must never leak into
 * another photo's grade or stack.
 *
 * Root cause of the original defect: the history stack used to be shared
 * across all photos, the initial "打开照片" step always pointed at photo #1,
 * `show()` (switching photos) did not record a step, and jumpHistory() used to
 * force `index = photoIndex` derived from the snapshot's photoId. So editing a
 * non-first photo and then undoing jumped the user straight back to photo #1.
 * Since 1.6.28 each photo has its own EditHistory, so every step in a stack
 * belongs to the photo being previewed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorHistoryPhotoJumpTest {

    private lateinit var context: Context
    private lateinit var vm: EditorViewModel
    private lateinit var cacheDir: File

    // NOTE: name deliberately differs from SelectionStore.photos (a StateFlow)
    // so the `apply { setPhotos(...) }` receiver does not shadow this list.
    private val testPhotos = (1L..3L).map { id ->
        PhotoItem(
            id = id,
            uri = Uri.parse("file:///fujikon_test/photo$id.jpg"),
            displayName = "photo$id.jpg",
            dateTaken = 0L,
            dateAdded = 0L,
            bucketId = "b",
            bucketName = "b",
            width = 64,
            height = 64,
            mimeType = "image/jpeg",
            size = 1L
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val settings = AppSettings(context)
        val selection = SelectionStore().apply {
            setPhotos(testPhotos)
            setPreset(Preset.NONE_ID)
        }
        cacheDir = File(context.cacheDir, "hist_jump_test").apply { mkdirs() }
        vm = EditorViewModel(
            context = context,
            presetRepository = PresetRepository(context),
            selectionStore = selection,
            renderer = ImageRenderer(),
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir
        )
    }

    @After
    fun tearDown() {
        // `presetRepository.load()` switches to Dispatchers.IO; let any
        // in-flight continuation drain before swapping the Main dispatcher,
        // otherwise resetMain() races with it across test classes in a full run.
        Thread.sleep(150)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    /** init records the initial "打开照片" step (photoId = first photo). */
    private suspend fun awaitInit() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (vm.state.value.historyIndex == 0 && vm.state.value.photos.size == 3) return
            delay(10)
        }
        throw AssertionError("初始化未完成: ${vm.state.value}")
    }

    @Test
    fun undoOnNonFirstPhotoKeepsCurrentPhotoAndRestoresLook() = runBlocking {
        awaitInit()
        assertEquals(0, vm.state.value.index)
        assertEquals(0, vm.state.value.historyIndex)

        // Switch to the 3rd photo. show() must NOT add a history step.
        vm.show(2)
        assertEquals(2, vm.state.value.index)
        assertEquals(0, vm.state.value.historyIndex)

        // One edit on the 3rd photo -> step 1, whose photoId is photo #3.
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.5f), "曝光度")
        assertEquals(1, vm.state.value.historyIndex)
        assertEquals(0.5f, vm.state.value.adjustments.exposure, 0.001f)

        // Undo restores the global look but stays on the 3rd photo.
        vm.undo()
        assertEquals("回退上一步不能跳回第一张照片", 2, vm.state.value.index)
        assertEquals(0, vm.state.value.historyIndex)
        assertEquals("回退应撤销曝光调节", 0f, vm.state.value.adjustments.exposure, 0.001f)
    }

    @Test
    fun redoOnNonFirstPhotoKeepsCurrentPhoto() = runBlocking {
        awaitInit()
        vm.show(1) // 2nd photo
        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 0.3f), "对比度")
        vm.undo()
        assertEquals(1, vm.state.value.index)
        assertEquals(0f, vm.state.value.adjustments.contrast, 0.001f)

        vm.redo()
        assertEquals("下一步也不能跳回第一张照片", 1, vm.state.value.index)
        assertEquals(0.3f, vm.state.value.adjustments.contrast, 0.001f)
    }

    @Test
    fun jumpToHistoryOnNonFirstPhotoKeepsCurrentPhoto() = runBlocking {
        awaitInit()
        vm.show(2) // 3rd photo
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.4f), "曝光度")
        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 0.2f), "对比度")
        assertEquals(2, vm.state.value.historyIndex)

        vm.jumpToHistory(0)
        assertEquals("点击历史首步不能跳回第一张照片", 2, vm.state.value.index)
        assertEquals(0, vm.state.value.historyIndex)
        assertEquals(0f, vm.state.value.adjustments.exposure, 0.001f)
        assertEquals(0f, vm.state.value.adjustments.contrast, 0.001f)
    }
}
