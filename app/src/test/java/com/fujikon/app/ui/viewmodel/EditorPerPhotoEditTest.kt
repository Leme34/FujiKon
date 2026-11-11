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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.6.28 requirement: "下一步" applies the chosen preset to the whole
 * selection exactly ONCE. From then on the user edits photos ONE BY ONE, so a
 * grade change, a preset change, an undo, a redo or a history jump on one photo
 * must never leak into any other photo, and batch export must use each photo's
 * own resulting look.
 *
 * Before 1.6.28 the editor held ONE global grade and ONE global history stack,
 * and exported one preset onto every photo — i.e. individual edits were
 * silently applied to the entire batch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorPerPhotoEditTest {

    private lateinit var context: Context
    private lateinit var vm: EditorViewModel
    private lateinit var cacheDir: File

    private val testPhotos = (1L..3L).map { id ->
        PhotoItem(
            id = id,
            uri = Uri.parse("file:///fujikon_per_photo/photo$id.jpg"),
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
        // No explicit preset: the editor now defaults to 「无（保留原图）」.
        val selection = SelectionStore().apply { setPhotos(testPhotos) }
        cacheDir = File(context.cacheDir, "per_photo_test").apply { mkdirs() }
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

    private suspend fun awaitInit() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (vm.state.value.historyIndex == 0 && vm.state.value.photos.size == 3) return
            delay(10)
        }
        throw AssertionError("初始化未完成: ${vm.state.value}")
    }

    /** "下一步" seeds every photo with the SAME preset as its own first step. */
    @Test
    fun batchPresetIsAppliedOnceAsEveryPhotosBaseline() = runBlocking {
        awaitInit()
        val baselineId = vm.state.value.presetId
        val baselineGrade = vm.state.value.adjustments

        repeat(3) { i ->
            vm.show(i)
            assertEquals("第 ${i + 1} 张应套用同一批量基线预设", baselineId, vm.state.value.presetId)
            assertEquals("第 ${i + 1} 张基线参数应一致", baselineGrade, vm.state.value.adjustments)
            assertEquals("第 ${i + 1} 张应有自己独立的第一步", 1, vm.state.value.historyEntries.size)
            assertEquals(0, vm.state.value.historyIndex)
            assertEquals("打开照片", vm.state.value.currentHistoryLabel)
        }
    }

    /** Editing photo #2 must not change what photo #3 shows or exports. */
    @Test
    fun editingOnePhotoDoesNotChangeAnotherPhotosGrade() = runBlocking {
        awaitInit()
        val baselineExposure = vm.state.value.adjustments.exposure

        vm.show(1)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.6f), "曝光度")
        assertEquals(0.6f, vm.state.value.adjustments.exposure, 0.001f)

        vm.show(2)
        assertEquals(
            "第三张不能继承第二张的曝光调节",
            baselineExposure, vm.state.value.adjustments.exposure, 0.001f
        )

        vm.show(1)
        assertEquals(
            "切回第二张应恢复它自己的调节",
            0.6f, vm.state.value.adjustments.exposure, 0.001f
        )
    }

    /** Each photo owns a separate stack; switching swaps grade + history. */
    @Test
    fun eachPhotoHasItsOwnHistoryStack() = runBlocking {
        awaitInit()
        vm.show(1)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.5f), "曝光度")
        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 0.2f), "对比度")
        assertEquals(2, vm.state.value.historyIndex)
        assertEquals(3, vm.state.value.historyEntries.size)

        vm.show(2)
        assertEquals("第三张历史应从头开始", 0, vm.state.value.historyIndex)
        assertEquals(1, vm.state.value.historyEntries.size)

        vm.setAdjustments(vm.state.value.adjustments.copy(vibrance = 0.3f), "鲜艳度")
        assertEquals(1, vm.state.value.historyIndex)
        assertEquals(2, vm.state.value.historyEntries.size)

        vm.show(1)
        assertEquals("切回第二张应恢复它的历史位置", 2, vm.state.value.historyIndex)
        assertEquals(3, vm.state.value.historyEntries.size)
        assertEquals(0.5f, vm.state.value.adjustments.exposure, 0.001f)
        assertEquals(0.2f, vm.state.value.adjustments.contrast, 0.001f)

        vm.show(2)
        assertEquals("第三张历史应保留自己的位置", 1, vm.state.value.historyIndex)
        assertEquals(2, vm.state.value.historyEntries.size)
        assertEquals(0.3f, vm.state.value.adjustments.vibrance, 0.001f)
    }

    /** Undo / redo on photo #1 must not alter photo #2. */
    @Test
    fun undoAndRedoOnlyAffectTheCurrentPhoto() = runBlocking {
        awaitInit()
        vm.show(0)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.5f), "曝光度")
        vm.show(1)
        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 0.4f), "对比度")

        vm.show(0)
        vm.undo()
        assertEquals(0, vm.state.value.historyIndex)
        assertEquals(0f, vm.state.value.adjustments.exposure, 0.001f)
        assertEquals(0, vm.state.value.index)

        vm.show(1)
        assertEquals(
            "第一张的回退不能影响第二张的对比度",
            0.4f, vm.state.value.adjustments.contrast, 0.001f
        )
        assertEquals(1, vm.state.value.historyIndex)

        vm.show(0)
        vm.redo()
        assertEquals(0.5f, vm.state.value.adjustments.exposure, 0.001f)
        vm.show(1)
        assertEquals(
            "第一张的重做也不能影响第二张",
            0.4f, vm.state.value.adjustments.contrast, 0.001f
        )
    }

    /** Jump-to-history is also scoped to the photo being previewed. */
    @Test
    fun jumpToHistoryOnOnePhotoDoesNotTouchAnother() = runBlocking {
        awaitInit()
        vm.show(2)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.3f), "曝光度")
        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 0.2f), "对比度")
        assertEquals(2, vm.state.value.historyIndex)

        vm.show(0)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.7f), "曝光度")
        assertEquals(1, vm.state.value.historyIndex)

        vm.show(2)
        vm.jumpToHistory(0)
        assertEquals(2, vm.state.value.index)
        assertEquals(0, vm.state.value.historyIndex)
        assertEquals(0f, vm.state.value.adjustments.exposure, 0.001f)

        vm.show(0)
        assertEquals("第一张的历史与参数不受第三张回退影响", 1, vm.state.value.historyIndex)
        assertEquals(0.7f, vm.state.value.adjustments.exposure, 0.001f)
    }

    /** 不选预设时默认「无（保留原图）」，绝不擅自套上列表里的第一个滤镜。 */
    @Test
    fun noPresetSelectedDefaultsToNone() = runBlocking {
        awaitInit()
        assertEquals(Preset.NONE_ID, vm.state.value.presetId)
        assertTrue("选中的应是「无」", vm.state.value.selectedPreset?.isNone == true)
        assertTrue("原图不应带任何调节", vm.state.value.adjustments.isNeutral())
        // 每张照片的批量基线也必须是「无」，而不是某个随机滤镜。
        repeat(3) { i ->
            vm.show(i)
            assertEquals("第 ${i + 1} 张应保持原图", Preset.NONE_ID, vm.state.value.presetId)
        }
    }

    /** Changing the preset in the editor is an individual edit, not batch. */
    @Test
    fun selectingPresetOnOnePhotoDoesNotChangeOthers() = runBlocking {
        awaitInit()
        val baselineId = vm.state.value.presetId
        assertEquals("未选预设时基线应为「无」", Preset.NONE_ID, baselineId)
        val other = vm.state.value.presets.firstOrNull { !it.isNone }?.id
            ?: throw AssertionError("需要一个真实预设来验证切换")

        vm.show(1)
        vm.selectPreset(other)
        assertEquals(other, vm.state.value.presetId)

        vm.show(2)
        assertEquals("第三张预设不应被第二张改动", baselineId, vm.state.value.presetId)

        vm.show(1)
        assertEquals(other, vm.state.value.presetId)
    }

    /** Batch export still covers all photos, but with each photo's own look. */
    @Test
    fun exportPresetsByPhotoUsesEachPhotosOwnGrade() = runBlocking {
        awaitInit()
        val baselineGrade = vm.state.value.adjustments

        vm.show(1)
        vm.setAdjustments(vm.state.value.adjustments.copy(exposure = 0.7f), "曝光度")
        vm.show(2)
        vm.setAdjustments(vm.state.value.adjustments.copy(contrast = 0.3f), "对比度")

        val perPhoto = vm.exportPresetsByPhoto()
        assertEquals("批量导出仍应覆盖全部所选照片", 3, perPhoto.size)
        assertEquals(0.7f, perPhoto[2L]?.adjustments?.exposure ?: 0f, 0.001f)
        assertEquals(0.3f, perPhoto[3L]?.adjustments?.contrast ?: 0f, 0.001f)
        assertEquals("第一张应保持批量基线", baselineGrade, perPhoto[1L]?.adjustments)
        assertTrue(
            "三张照片的导出效果不应再是同一份",
            perPhoto[1L]?.adjustments != perPhoto[2L]?.adjustments
        )
    }
}
