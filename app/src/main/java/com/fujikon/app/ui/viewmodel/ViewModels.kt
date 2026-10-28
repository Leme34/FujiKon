package com.fujikon.app.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fujikon.app.AppContainer
import com.fujikon.app.data.Album
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.PhotoRepository
import com.fujikon.app.data.Preset
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.render.BitmapLoader
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PresetsViewModel(private val repository: PresetRepository) : ViewModel() {
    val presets: StateFlow<List<Preset>> = repository.presets
    val ready: StateFlow<Boolean> = repository.ready

    init {
        viewModelScope.launch { repository.load() }
    }

    suspend fun importUris(uris: List<Uri>): PresetRepository.ImportResult = repository.importUris(uris)

    suspend fun restoreSamples(): Int = repository.restoreSamples()

    suspend fun update(preset: Preset) = repository.update(preset)

    suspend fun delete(id: String) = repository.delete(id)
}

class GalleryViewModel(
    private val photoRepository: PhotoRepository,
    private val selectionStore: SelectionStore
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val permissionDenied: Boolean = false,
        val photos: List<PhotoItem> = emptyList(),
        val albums: List<Album> = emptyList(),
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _albumFilter = MutableStateFlow<String?>(null)
    val albumFilter: StateFlow<String?> = _albumFilter.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (!photoRepository.hasReadPermission()) {
            _state.update { it.copy(permissionDenied = true, loading = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, permissionDenied = false, error = null) }
            val photos = photoRepository.loadPhotos()
            _state.update {
                it.copy(
                    loading = false,
                    photos = photos,
                    albums = photoRepository.albums(photos),
                    error = if (photos.isEmpty()) "相册中没有找到照片" else null
                )
            }
        }
    }

    fun setAlbum(id: String?) {
        _albumFilter.value = id
    }

    fun toggle(id: Long) {
        _selectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun selectAll(visible: List<PhotoItem>) {
        _selectedIds.update { current -> current + visible.map { it.id } }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun chosenPhotos(): List<PhotoItem> {
        val order = _state.value.photos.associateBy { it.id }
        return _selectedIds.value.mapNotNull { order[it] }
    }
}

class EditorViewModel(
    @SuppressLint("StaticFieldLeak") private val context: Context,
    private val presetRepository: PresetRepository,
    private val selectionStore: SelectionStore,
    private val renderer: ImageRenderer,
    private val exportManager: ExportManager,
    private val settings: AppSettings
) : ViewModel() {

    sealed interface ExportState {
        data object Idle : ExportState
        data class Running(val done: Int, val total: Int, val currentName: String) : ExportState
        data class Done(
            val saved: Int,
            val failures: List<String>,
            val firstUri: Uri? = null,
            val album: String = "FujiKon"
        ) : ExportState
        data class Failed(val message: String) : ExportState
    }

    data class UiState(
        val photos: List<PhotoItem> = emptyList(),
        val presets: List<Preset> = emptyList(),
        val index: Int = 0,
        val presetId: String? = null,
        val original: Bitmap? = null,
        val rendered: Bitmap? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val export: ExportState = ExportState.Idle,
        val seenCompareHint: Boolean = false
    ) {
        val currentPhoto: PhotoItem? get() = photos.getOrNull(index)
        val currentPreset: Preset? get() = presets.firstOrNull { it.id == presetId }
    }

    private val _state = MutableStateFlow(UiState(seenCompareHint = settings.hasSeenCompareHint))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val originalCache = object : LruCache<Long, Bitmap>(3) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1
        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            // Keep evicted bitmaps for the GC; recycling here could race with drawing.
        }
    }

    private var renderJob: Job? = null
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            presetRepository.load()
            _state.update {
                it.copy(
                    photos = selectionStore.photos.value,
                    presets = presetRepository.presets.value,
                    presetId = selectionStore.presetId.value ?: presetRepository.presets.value.firstOrNull()?.id
                )
            }
            if (_state.value.photos.isEmpty()) {
                _state.update { it.copy(loading = false, error = "没有待处理的照片") }
            } else {
                renderCurrent()
            }
        }
        viewModelScope.launch {
            presetRepository.presets.collect { list ->
                _state.update { current ->
                    val stillThere = list.any { it.id == current.presetId }
                    current.copy(
                        presets = list,
                        presetId = if (stillThere) current.presetId else list.firstOrNull()?.id
                    )
                }
            }
        }
    }

    fun markCompareHintSeen() {
        settings.hasSeenCompareHint = true
        _state.update { it.copy(seenCompareHint = true) }
    }

    fun selectPreset(id: String) {
        if (_state.value.presetId == id) return
        _state.update { it.copy(presetId = id) }
        selectionStore.setPreset(id)
        renderCurrent()
    }

    fun show(index: Int) {
        if (index !in _state.value.photos.indices || index == _state.value.index) return
        _state.update { it.copy(index = index) }
        renderCurrent()
    }

    fun retry() = renderCurrent()

    fun next() = show(_state.value.index + 1)

    fun previous() = show(_state.value.index - 1)

    private fun renderCurrent() {
        val snapshot = _state.value
        val photo = snapshot.currentPhoto ?: return
        val preset = snapshot.currentPreset ?: run {
            _state.update { it.copy(loading = false, error = "请先导入一个预设") }
            return
        }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val original = originalCache.get(photo.id) ?: run {
                    val decoded = BitmapLoader.decodePreview(context, photo.uri, 1800)
                        ?: throw IllegalStateException("无法读取这张照片")
                    originalCache.put(photo.id, decoded)
                    decoded
                }
                val rendered = renderer.render(original, preset, forExport = false)
                _state.update { current ->
                    if (current.currentPhoto?.id == photo.id && current.presetId == preset.id) {
                        current.copy(original = original, rendered = rendered, loading = false)
                    } else {
                        current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(loading = false, error = "内存不足，无法渲染这张照片") }
                System.gc()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "渲染失败") }
            }
        }
    }

    fun export(options: ExportManager.Options) {
        if (_state.value.export is ExportState.Running) return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            val preset = _state.value.currentPreset
            if (preset == null) {
                _state.update { it.copy(export = ExportState.Failed("请选择一个预设")) }
                return@launch
            }
            _state.update { it.copy(export = ExportState.Running(0, _state.value.photos.size, "")) }
            try {
                val result = exportManager.export(
                    photos = _state.value.photos,
                    preset = preset,
                    options = options
                ) { progress ->
                    _state.update { current ->
                        current.copy(
                            export = ExportState.Running(progress.done, progress.total, progress.currentName)
                        )
                    }
                }
                _state.update {
                    it.copy(
                        export = ExportState.Done(
                            saved = result.saved.size,
                            failures = result.failures,
                            firstUri = result.saved.firstOrNull(),
                            album = options.album
                        )
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(export = ExportState.Idle) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(export = ExportState.Failed(e.message ?: "导出失败")) }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(export = ExportState.Idle) }
    }

    fun dismissExportResult() {
        _state.update { it.copy(export = ExportState.Idle) }
    }

    fun currentOptions(): ExportManager.Options = exportManager.defaultOptions()

    override fun onCleared() {
        renderJob?.cancel()
        exportJob?.cancel()
        super.onCleared()
    }
}

class FujiKonViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(PresetsViewModel::class.java) ->
            PresetsViewModel(container.presetRepository) as T
        modelClass.isAssignableFrom(GalleryViewModel::class.java) ->
            GalleryViewModel(container.photoRepository, container.selectionStore) as T
        modelClass.isAssignableFrom(EditorViewModel::class.java) ->
            EditorViewModel(
                context = container.appContext,
                presetRepository = container.presetRepository,
                selectionStore = container.selectionStore,
                renderer = container.imageRenderer,
                exportManager = container.exportManager,
                settings = container.settings
            ) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
