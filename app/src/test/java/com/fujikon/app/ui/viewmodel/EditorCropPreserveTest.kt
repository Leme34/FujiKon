package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.CropAspect
import com.fujikon.app.data.CropRect
import com.fujikon.app.data.EditCrop
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.6.72 回归：裁剪是构图编辑，与滤镜色调正交。用户反馈「裁剪后，再次套用
 * 预设，裁剪的效果会丢失」。根因是 selectPreset 用 preset.adjustments 整体
 * 覆盖当前 adjustments，把 crop 重置成 EditCrop.Full。本测试锁定：切换任意
 * 预设（含「无」）后，用户已做的裁剪必须原样保留。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorCropPreserveTest {

    private lateinit var context: Context
    private lateinit var vm: EditorViewModel
    private lateinit var cacheDir: File

    private val photo = PhotoItem(
        id = 1L,
        uri = Uri.parse("file:///fujikon_crop/photo1.jpg"),
        displayName = "photo1.jpg",
        dateTaken = 0L, dateAdded = 0L, bucketId = "b", bucketName = "b",
        width = 64, height = 64, mimeType = "image/jpeg", size = 1L
    )

    /** 一个明确的非 Full 裁剪：1:1 + 自定义矩形。 */
    private val cropped = EditCrop(
        aspect = CropAspect.SQUARE.id,
        customRect = CropRect(0.1f, 0.1f, 0.6f, 0.6f)
    ).normalized()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val settings = AppSettings(context)
        cacheDir = File(context.cacheDir, "crop_preserve_test").apply { mkdirs() }
        vm = EditorViewModel(
            context = context,
            presetRepository = PresetRepository(context),
            selectionStore = SelectionStore().apply { setPhotos(listOf(photo)) },
            renderer = ImageRenderer(),
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun awaitInit() {
        repeat(60) {
            if (vm.state.value.presets.isNotEmpty()) return
            delay(20)
        }
    }

    private fun realPresetId(): String =
        vm.state.value.presets.first { !it.isNone }.id

    @Test
    fun cropSurvivesSwitchingToAnotherPreset() = runBlocking {
        awaitInit()
        vm.setCrop(cropped)
        assertTrue("前置条件：已设置裁剪", !vm.state.value.adjustments.crop.isFull())

        // 先选一个真实预设，再切换到另一个
        vm.selectPreset(realPresetId())
        val all = vm.state.value.presets.filter { !it.isNone }.map { it.id }
        val other = all.first { it != vm.state.value.presetId }
        vm.selectPreset(other)

        assertEquals("切换预设后裁剪必须保留", cropped, vm.state.value.adjustments.crop)
        assertFalse("裁剪不应被重置", vm.state.value.adjustments.crop.isFull())
    }

    @Test
    fun cropSurvivesSwitchingToNone() = runBlocking {
        awaitInit()
        vm.selectPreset(realPresetId())
        vm.setCrop(cropped)

        vm.selectPreset(Preset.NONE_ID)

        assertEquals("切到「无」后裁剪必须保留", cropped, vm.state.value.adjustments.crop)
        assertFalse(vm.state.value.adjustments.crop.isFull())
    }

    @Test
    fun cropSurvivesFromNoneToPreset() = runBlocking {
        awaitInit()
        // 当前是「无」→ 先裁剪 → 再套预设（旧实现会在此丢失裁剪）
        assertEquals(Preset.NONE_ID, vm.state.value.presetId)
        vm.setCrop(cropped)
        vm.selectPreset(realPresetId())

        assertEquals("从「无」套预设后裁剪必须保留", cropped, vm.state.value.adjustments.crop)
        assertFalse(vm.state.value.adjustments.crop.isFull())
    }

    @Test
    fun presetIdSwitchWorksWhileCropIsKept() = runBlocking {
        awaitInit()
        val preset = vm.state.value.presets.first { !it.isNone }
        // 预设自带的 crop 一律忽略（裁剪永远属于当前照片），预设 id 正常切换。
        vm.setCrop(cropped)
        vm.selectPreset(preset.id)

        assertEquals(cropped, vm.state.value.adjustments.crop)
        assertEquals("预设 id 应正常切换", preset.id, vm.state.value.presetId)
    }
}
