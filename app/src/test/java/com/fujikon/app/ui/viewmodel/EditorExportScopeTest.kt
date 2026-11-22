package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.render.ExportCoordinator
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
import java.util.Base64

/**
 * 1.6.68: 更多菜单导出拆分后的两个入口——
 *  - exportCurrent 只导出当前预览的那一张；
 *  - export 仍导出全部照片（拆分前行为）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorExportScopeTest {

    private lateinit var context: Context
    private lateinit var sampleUri: Uri
    private lateinit var cacheDir: File
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    )
    private val photos = (1L..3L).map { id ->
        PhotoItem(
            id = id, uri = Uri.EMPTY, displayName = "p$id.jpg",
            dateTaken = 0L, dateAdded = 0L, bucketId = "b", bucketName = "b",
            width = 64, height = 64, mimeType = "image/jpeg", size = 1L
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE).edit().clear().commit()
        sampleUri = Uri.fromFile(File(context.cacheDir, "export_scope.png").apply { writeBytes(png) })
        cacheDir = File(context.cacheDir, "export_scope_test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Thread.sleep(150)
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    private fun usablePhotos() = photos.map { it.copy(uri = sampleUri) }

    private suspend fun awaitInit(vm: EditorViewModel) {
        for (i in 0..60) {
            if (vm.state.value.presets.isNotEmpty() && vm.state.value.currentPreset != null) return
            kotlinx.coroutines.delay(50)
        }
    }

    private fun options() = ExportManager.Options(format = "PNG", album = "Test", preserveExif = false)

    @Test
    fun exportCurrentSavesExactlyOnePhoto() = runBlocking {
        // 让 selection 里的照片 uri 指向可解码文件。
        val settings = AppSettings(context)
        val manager = ExportManager(context, ImageRenderer(), settings)
        val coordinator = ExportCoordinator(manager, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val selection = SelectionStore().apply { setPhotos(usablePhotos()) }
        val vm = EditorViewModel(
            context = context, presetRepository = PresetRepository(context),
            selectionStore = selection, renderer = ImageRenderer(),
            exportManager = manager, exportCoordinator = coordinator,
            settings = settings, agnesImageService = AgnesImageService(), aiCacheDir = cacheDir
        )
        awaitInit(vm)

        vm.exportCurrent(options())
        val final = coordinator.status.first {
            it is ExportCoordinator.Status.Done || it is ExportCoordinator.Status.Failed
        }
        assertTrue("当前照片导出应成功: $final", final is ExportCoordinator.Status.Done)
        assertEquals("仅导出当前 1 张", 1, (final as ExportCoordinator.Status.Done).saved)
    }

    @Test
    fun exportAllSavesEveryPhoto() = runBlocking {
        val settings = AppSettings(context)
        val manager = ExportManager(context, ImageRenderer(), settings)
        val coordinator = ExportCoordinator(manager, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val selection = SelectionStore().apply { setPhotos(usablePhotos()) }
        val vm = EditorViewModel(
            context = context, presetRepository = PresetRepository(context),
            selectionStore = selection, renderer = ImageRenderer(),
            exportManager = manager, exportCoordinator = coordinator,
            settings = settings, agnesImageService = AgnesImageService(), aiCacheDir = cacheDir
        )
        awaitInit(vm)

        vm.export(options())
        val final = coordinator.status.first {
            it is ExportCoordinator.Status.Done || it is ExportCoordinator.Status.Failed
        }
        assertTrue("全部导出应成功: $final", final is ExportCoordinator.Status.Done)
        assertEquals("应导出全部 3 张", 3, (final as ExportCoordinator.Status.Done).saved)
    }
}
