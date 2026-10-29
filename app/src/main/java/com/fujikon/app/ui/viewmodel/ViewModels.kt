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
import com.fujikon.app.data.PresetRepository.BinaryImportResult
import com.fujikon.app.data.PresetRepository.IncomingPreset
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.nikonpc.NikonPcPreset
import com.fujikon.app.nikonpc.NikonPcService
import com.fujikon.app.render.BitmapLoader
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

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

class NikonPcViewModel(
    private val service: NikonPcService,
    private val repository: PresetRepository
) : ViewModel() {

    sealed interface ItemState {
        data object Idle : ItemState
        data object Importing : ItemState
        data object Imported : ItemState
        data object Duplicate : ItemState
        data class Failed(val message: String) : ItemState
    }

    data class UiState(
        val catalog: List<NikonPcPreset> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val statuses: Map<String, ItemState> = emptyMap(),
        val importingAll: Boolean = false,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val progressName: String = "",
        val notice: String? = null,
        val errors: List<String> = emptyList()
    ) {
        val filteredCatalog: List<NikonPcPreset>
            get() {
                val search = query.trim()
                if (search.isEmpty()) return catalog
                return catalog.filter { preset ->
                    preset.name.contains(search, ignoreCase = true) ||
                        preset.category.contains(search, ignoreCase = true) ||
                        preset.path.contains(search, ignoreCase = true) ||
                        preset.slot.contains(search, ignoreCase = true)
                }
            }

        val importableCount: Int
            get() = catalog.count { preset ->
                val status = statuses[preset.path]
                status !is ItemState.Imported && status !is ItemState.Duplicate
            }

        val allDone: Boolean
            get() = catalog.isNotEmpty() && importableCount == 0
    }

    private data class DownloadedPreset(
        val item: NikonPcPreset,
        val bytes: ByteArray
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var importAllJob: Job? = null

    init {
        loadCatalog()
    }

    fun loadCatalog(force: Boolean = false) {
        val current = _state.value
        if (current.loading) return
        if (!force && current.catalog.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val catalog = service.fetchCatalog()
                _state.update { it.copy(catalog = catalog, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: "加载 nikonpc 预设失败"
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun importOne(item: NikonPcPreset) {
        val current = _state.value
        if (current.importingAll) {
            _state.update { it.copy(notice = "请等待批量导入完成") }
            return
        }
        when (current.statuses[item.path]) {
            ItemState.Importing, ItemState.Imported, ItemState.Duplicate -> return
            else -> Unit
        }

        setStatus(item.path, ItemState.Importing)
        viewModelScope.launch {
            val bytes = try {
                service.download(item)
            } catch (e: CancellationException) {
                setStatus(item.path, ItemState.Idle)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "下载失败"
                setStatus(item.path, ItemState.Failed(message))
                _state.update { it.copy(notice = "${item.name}：$message") }
                return@launch
            }

            val result = try {
                repository.importBinaries(
                    listOf(IncomingPreset(item.path, item.name, bytes, item.path))
                )[item.path]
            } catch (e: CancellationException) {
                setStatus(item.path, ItemState.Idle)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "保存失败"
                setStatus(item.path, ItemState.Failed(message))
                _state.update { it.copy(notice = "${item.name}：$message") }
                return@launch
            }

            when (result) {
                is BinaryImportResult.Imported -> {
                    setStatus(item.path, ItemState.Imported)
                    _state.update { it.copy(notice = "已导入「${result.preset.name}」") }
                }
                is BinaryImportResult.Duplicate -> {
                    setStatus(item.path, ItemState.Duplicate)
                    _state.update { it.copy(notice = "「${item.name}」已在预设列表中") }
                }
                is BinaryImportResult.Failed -> {
                    setStatus(item.path, ItemState.Failed(result.message))
                    _state.update { it.copy(notice = result.message) }
                }
                null -> {
                    val message = "${item.name}：保存失败"
                    setStatus(item.path, ItemState.Failed(message))
                    _state.update { it.copy(notice = message) }
                }
            }
        }
    }

    fun importAll() {
        val current = _state.value
        if (importAllJob?.isActive == true || current.importingAll) return
        if (current.catalog.isEmpty()) {
            _state.update { it.copy(notice = "在线预设尚未加载") }
            return
        }
        if (current.statuses.values.any { it is ItemState.Importing }) {
            _state.update { it.copy(notice = "请等待当前导入完成") }
            return
        }

        val target = current.catalog.filter { preset ->
            val status = current.statuses[preset.path]
            status !is ItemState.Imported && status !is ItemState.Duplicate
        }
        if (target.isEmpty()) {
            _state.update { it.copy(notice = "全部预设都已导入") }
            return
        }

        _state.update {
            it.copy(
                importingAll = true,
                progressDone = 0,
                progressTotal = target.size,
                progressName = "",
                errors = emptyList(),
                statuses = it.statuses + target.associate { preset ->
                    preset.path to ItemState.Importing
                }
            )
        }
        importAllJob = viewModelScope.launch {
            runImportAll(target)
        }
    }

    fun cancelImportAll() {
        if (importAllJob?.isActive != true) return
        importAllJob?.cancel()
        _state.update { current ->
            current.copy(
                progressName = "正在取消…",
                notice = "正在取消导入"
            )
        }
    }

    fun clearNotice() {
        _state.update { if (it.notice == null) it else it.copy(notice = null) }
    }

    fun clearErrors() {
        _state.update { if (it.errors.isEmpty()) it else it.copy(errors = emptyList()) }
    }

    private suspend fun runImportAll(target: List<NikonPcPreset>) {
        val downloaded = Collections.synchronizedList(mutableListOf<DownloadedPreset>())
        val failures = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        try {
            val semaphore = Semaphore(4)
            supervisorScope {
                target.map { item ->
                    async {
                        semaphore.withPermit {
                            ensureActive()
                            try {
                                val bytes = service.download(item)
                                downloaded += DownloadedPreset(item, bytes)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val message = e.message ?: "下载失败"
                                failures += item.path to "${item.name}：$message"
                                setStatus(item.path, ItemState.Failed(message))
                            } finally {
                                _state.update { current ->
                                    current.copy(
                                        progressDone = (current.progressDone + 1)
                                            .coerceAtMost(current.progressTotal),
                                        progressName = item.name
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            if (downloaded.isEmpty()) {
                val errors = failures.map { it.second }
                _state.update {
                    it.copy(
                        notice = importSummary(0, 0, errors.size),
                        errors = errors.take(12)
                    )
                }
                return
            }

            _state.update { it.copy(progressName = "正在保存到预设列表…") }
            val files = downloaded.map { downloadedPreset ->
                IncomingPreset(
                    key = downloadedPreset.item.path,
                    displayName = downloadedPreset.item.name,
                    bytes = downloadedPreset.bytes,
                    sourceName = downloadedPreset.item.path
                )
            }
            val results = repository.importBinaries(files)
            val errors = mutableListOf<String>()
            failures.forEach { (_, message) -> errors += message }

            var imported = 0
            var duplicates = 0
            files.forEach { file ->
                when (val result = results[file.key]) {
                    is BinaryImportResult.Imported -> {
                        imported++
                        setStatus(file.key, ItemState.Imported)
                    }
                    is BinaryImportResult.Duplicate -> {
                        duplicates++
                        setStatus(file.key, ItemState.Duplicate)
                    }
                    is BinaryImportResult.Failed -> {
                        errors += result.message
                        setStatus(file.key, ItemState.Failed(result.message))
                    }
                    null -> {
                        val message = "${file.displayName}：保存失败"
                        errors += message
                        setStatus(file.key, ItemState.Failed(message))
                    }
                }
            }

            _state.update {
                it.copy(
                    notice = importSummary(imported, duplicates, errors.size),
                    errors = errors.take(12)
                )
            }
        } catch (e: CancellationException) {
            resetImportingStatuses(target)
            _state.update { it.copy(notice = "已取消导入") }
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "批量导入失败"
            target.forEach { setStatus(it.path, ItemState.Failed(message)) }
            _state.update {
                it.copy(
                    notice = message,
                    errors = listOf(message)
                )
            }
        } finally {
            _state.update { it.copy(importingAll = false, progressName = "") }
            importAllJob = null
        }
    }

    private fun resetImportingStatuses(target: List<NikonPcPreset>) {
        _state.update { current ->
            val statuses = current.statuses.toMutableMap()
            target.forEach { preset ->
                if (statuses[preset.path] is ItemState.Importing) {
                    statuses[preset.path] = ItemState.Idle
                }
            }
            current.copy(statuses = statuses)
        }
    }

    private fun setStatus(path: String, status: ItemState) {
        _state.update { current ->
            current.copy(statuses = current.statuses + (path to status))
        }
    }

    private fun importSummary(imported: Int, duplicates: Int, failed: Int): String = buildString {
        if (imported > 0) append("已导入 $imported 个预设")
        if (duplicates > 0) {
            if (isNotEmpty()) append("，")
            append("$duplicates 个已存在")
        }
        if (failed > 0) {
            if (isNotEmpty()) append("，")
            append("$failed 个失败")
        }
        if (isEmpty()) append("没有导入任何预设")
    }
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
        val seenCompareHint: Boolean = false,
        /** Non-null while the user has an unsaved curve edit for the session. */
        val toneCurveOverride: ToneCurve? = null
    ) {
        val currentPhoto: PhotoItem? get() = photos.getOrNull(index)
        val selectedPreset: Preset? get() = presets.firstOrNull { it.id == presetId }
        val currentPreset: Preset? get() = selectedPreset?.let { base ->
            if (toneCurveOverride != null) base.copy(toneCurve = toneCurveOverride) else base
        }
        val effectiveToneCurve: ToneCurve
            get() = toneCurveOverride ?: selectedPreset?.toneCurve ?: ToneCurve.linear()
        val hasToneCurve: Boolean get() = !effectiveToneCurve.isLinear()
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
    private var renderGeneration = 0

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
                        presetId = if (stillThere) current.presetId else list.firstOrNull()?.id,
                        toneCurveOverride = if (stillThere) current.toneCurveOverride else null
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
        _state.update { it.copy(presetId = id, toneCurveOverride = null) }
        selectionStore.setPreset(id)
        renderCurrent()
    }

    fun setToneCurve(curve: ToneCurve) {
        _state.update { it.copy(toneCurveOverride = curve) }
        renderCurrent()
    }

    /** Reverts to the selected preset's own curve (usually the Nikon LUT). */
    fun clearToneCurveOverride() {
        if (_state.value.toneCurveOverride == null) return
        _state.update { it.copy(toneCurveOverride = null) }
        renderCurrent()
    }

    /**
     * Saves the current look as a new, binary-less preset. The original preset
     * and its NCP/NP3 file are left untouched.
     */
    suspend fun saveCurvePreset(name: String): Preset? {
        val snapshot = _state.value
        val base = snapshot.selectedPreset ?: return null
        val curve = snapshot.effectiveToneCurve
        val saved = presetRepository.add(
            base.copy(
                name = name.trim().ifBlank { "${base.name} 曲线" }.take(40),
                fileName = "",
                fileSize = 0,
                sourceName = "由 ${base.name} 调整",
                toneCurve = curve.takeUnless { it.isLinear() },
                note = base.note,
                importedAt = System.currentTimeMillis(),
                isSample = false,
                contentHash = ""
            )
        )
        // Keep working with the saved copy on the next launch instead of the
        // original preset plus a transient override.
        _state.update {
            it.copy(
                presets = presetRepository.presets.value,
                presetId = saved.id,
                toneCurveOverride = null
            )
        }
        selectionStore.setPreset(saved.id)
        // The saved copy has a new ID, so an in-flight render for the original
        // preset will be discarded by the generation guard. Render again with
        // the persisted curve.
        renderCurrent()
        return saved
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
        val photo = snapshot.currentPhoto ?: run {
            renderJob?.cancel()
            renderGeneration++
            return
        }
        renderJob?.cancel()
        val generation = ++renderGeneration
        val preset = snapshot.currentPreset ?: run {
            _state.update { it.copy(loading = false, error = "请先导入一个预设") }
            return
        }
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
                    if (generation == renderGeneration &&
                        current.currentPhoto?.id == photo.id &&
                        current.presetId == preset.id
                    ) {
                        current.copy(original = original, rendered = rendered, loading = false)
                    } else {
                        current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { current ->
                    if (generation == renderGeneration) {
                        current.copy(loading = false, error = "内存不足，无法渲染这张照片")
                    } else {
                        current
                    }
                }
                System.gc()
            } catch (e: Exception) {
                _state.update { current ->
                    if (generation == renderGeneration) {
                        current.copy(loading = false, error = e.message ?: "渲染失败")
                    } else {
                        current
                    }
                }
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
        modelClass.isAssignableFrom(NikonPcViewModel::class.java) ->
            NikonPcViewModel(container.nikonPcService, container.presetRepository) as T
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
