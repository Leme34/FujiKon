package com.fujikon.app

import android.app.Application
import android.content.Context
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoRepository
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.nikonpc.NikonPcService
import com.fujikon.app.nic.NikonCloudService
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import com.fujikon.app.render.PresetThumbnailStore
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
    val nikonPcService = NikonPcService(cacheDir = File(appContext.cacheDir, "nikonpc"))
    val nikonCloudService = NikonCloudService()
    val agnesImageService = AgnesImageService()
    /** Final images returned by one-tap AI retouching, keyed by photo id. */
    val aiEditCacheDir: File = File(appContext.cacheDir, "ai_edit").apply { mkdirs() }

    init {
        scope.launch { presetRepository.load() }
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
