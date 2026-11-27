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
 * 「选择预设时调整套用强度，并对选中的所有照片生效」的回归测试。
 *
 * 强度的选择发生在相册/预设页的选择面板里，经 [SelectionStore] 跨页面传到
 * 编辑器 init，作为每张选中照片共享的 baseline。逐张编辑后彼此独立，但
 * 初始强度必须对所有照片一致；换预设时也能带强度。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorPresetStrengthBatchTest {

    private lateinit var context: Context
    private lateinit var selection: SelectionStore
    private lateinit var cacheDir: File

    private val testPhotos = (1L..3L).map { id ->
        PhotoItem(
            id = id,
            uri = Uri.parse("file:///fujikon_strength_batch/photo$id.jpg"),
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
        cacheDir = File(context.cacheDir, "strength_batch_test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Thread.sleep(150)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private suspend fun newViewModel(): EditorViewModel {
        val settings = AppSettings(context)
        val repo = PresetRepository(context)
        repo.load()
        val presetId = repo.presets.value.first { !it.isNone }.id
        // 模拟「选择预设面板」：选中一个真实预设，并把面板里设定的强度写回。
        selection = SelectionStore().apply {
            setPhotos(testPhotos)
            setPreset(presetId, 35f)
        }
        return EditorViewModel(
            context = context,
            presetRepository = repo,
            selectionStore = selection,
            renderer = ImageRenderer(),
            exportManager = ExportManager(context, ImageRenderer(), settings),
            settings = settings,
            agnesImageService = AgnesImageService(),
            aiCacheDir = cacheDir
        )
    }

    private suspend fun awaitInit(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (vm.state.value.historyIndex == 0 && vm.state.value.photos.size == 3) return
            delay(10)
        }
        throw AssertionError("初始化未完成: ${vm.state.value}")
    }

    @Test
    fun chosenStrengthIsAppliedToEverySelectedPhoto() = runBlocking {
        val vm = newViewModel()
        awaitInit(vm)

        // 编辑器 init 必须把选择时的强度(35)套到当前显示的 baseline。
        assertEquals(35f, vm.state.value.adjustments.presetStrength, 0.001f)

        // 逐张翻页，每张照片的初始强度都必须是 35——批量套用全部生效。
        val byPhoto = LinkedHashMap<Long, Float>()
        repeat(3) { idx ->
            vm.show(idx)
            byPhoto[vm.state.value.currentPhoto!!.id] =
                vm.state.value.adjustments.presetStrength
        }
        testPhotos.forEach { photo ->
            assertEquals(
                "照片 ${photo.id} 的初始强度应为选择时设定的 35",
                35f,
                byPhoto[photo.id] ?: -1f,
                0.001f
            )
        }
    }

    @Test
    fun selectPresetCarriesExplicitStrength() = runBlocking {
        val vm = newViewModel()
        awaitInit(vm)

        val presetId = vm.state.value.presets.first { !it.isNone }.id
        vm.selectPreset(presetId, 70f)
        assertEquals(70f, vm.state.value.adjustments.presetStrength, 0.001f)

        // 不带强度参数时沿用预设自带强度，兼容既有调用方。
        val presetStrength = vm.state.value.presets.first { it.id == presetId }
            .adjustments.presetStrength
        vm.selectPreset(Preset.NONE_ID)
        vm.selectPreset(presetId)
        assertEquals(presetStrength, vm.state.value.adjustments.presetStrength, 0.001f)
    }
}
