package com.fujikon.app

import android.app.Application
import android.content.Context
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoRepository
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val presetRepository = PresetRepository(appContext)
    val photoRepository = PhotoRepository(appContext)
    val selectionStore = SelectionStore()
    val settings = AppSettings(appContext)
    val imageRenderer = ImageRenderer()
    val exportManager = ExportManager(appContext, imageRenderer, settings)

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
