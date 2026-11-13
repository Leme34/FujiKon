package com.fujikon.app

import android.app.Application
import android.content.Context
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.agnes.AgnesTextService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoRepository
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.nikonpc.NikonPcService
import com.fujikon.app.nic.NikonCloudService
import com.fujikon.app.render.CubeLutProvider
import com.fujikon.app.render.ExportCoordinator
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import com.fujikon.app.render.MlSegmentation
import com.fujikon.app.render.OnDeviceModels
import com.fujikon.app.render.PresetThumbnailStore
import com.fujikon.app.render.SelectionMaskGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val presetRepository = PresetRepository(appContext)
    val photoRepository = PhotoRepository(appContext)
    val selectionStore = SelectionStore()
    val settings = AppSettings(appContext)
    val imageRenderer = ImageRenderer()
    val presetThumbnailStore = PresetThumbnailStore(appContext, imageRenderer)
    val exportManager = ExportManager(appContext, imageRenderer, settings)
    /** 进程级导出协调器：导出任务运行在 Application 作用域，独立于任何页面生命周期。 */
    val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val exportCoordinator = ExportCoordinator(exportManager, exportScope)
    val nikonPcService = NikonPcService(cacheDir = File(appContext.cacheDir, "nikonpc"))
    val nikonCloudService = NikonCloudService()
    // Resolved per request so a region switch in Settings takes effect at once.
    val agnesImageService = AgnesImageService(baseUrlProvider = { settings.agnesBaseUrl })
    /** Agnes 3.0 Flash text model, used to analyse the photo and write prompts. */
    val agnesTextService = AgnesTextService(
        baseUrlProvider = { AgnesTextService.baseUrlFor(settings.agnesRegion) }
    )
    /** Final images returned by one-tap AI retouching, keyed by photo id. */
    val aiEditCacheDir: File = File(appContext.cacheDir, "ai_edit").apply { mkdirs() }

    init {
        // On-device semantic segmentation for "select subject / sky". Loading
        // is lazy and guarded; a missing model or unsupported ABI simply falls
        // back to the deterministic CV pipeline, never a broken selection.
        MlSegmentation.attach(appContext)
        SelectionMaskGenerator.provider = MlSegmentation
        // 1.6.57：人脸检测 + 场景分类两个纯标准算子 TFLite 模型，同样
        // lazy + 守卫，缺失时自动降级到既有的人体/影调逻辑，绝不崩溃。
        OnDeviceModels.attach(appContext)
        // 云创 3D LUT 方案：渲染器是纯 JVM 类，这里用 assets 装配解码入口。
        CubeLutProvider.install { asset ->
            runCatching { appContext.assets.open(asset).use { it.readBytes() } }.getOrNull()
        }
        scope.launch {
            presetRepository.load()
            MlSegmentation.warmUp()
            OnDeviceModels.warmUp()
        }
    }
}

class FujiKonApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
