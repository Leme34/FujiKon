package com.fujikon.app.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fujikon.app.AppContainer
import com.fujikon.app.agnes.AgnesException
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.agnes.AgnesTextService
import com.fujikon.app.data.Album
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.ChannelToneCurves
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.EditCrop
import com.fujikon.app.data.EditHistory
import com.fujikon.app.data.EditSnapshot
import com.fujikon.app.data.LensBlur
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.PhotoRepository
import com.fujikon.app.data.Preset
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.data.PresetRepository.BinaryImportResult
import com.fujikon.app.data.PresetRepository.IncomingPreset
import com.fujikon.app.data.SelectionStore
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurveChannel
import com.fujikon.app.ncp.PictureControlParser
import com.fujikon.app.nikonpc.NikonPcPreset
import com.fujikon.app.nikonpc.NikonPcService
import com.fujikon.app.nikonpc.presetSummary
import com.fujikon.app.render.AutoEnhance
import com.fujikon.app.render.BitmapLoader
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlin.math.abs

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

    /**
     * Lazily built preview for one download row: the decoded preset plus the
     * summary built from its parameters.
     */
    sealed interface PreviewState {
        data object Loading : PreviewState
        data class Ready(val preset: Preset, val description: String) : PreviewState
        data class Failed(val message: String) : PreviewState
    }

    data class UiState(
        val catalog: List<NikonPcPreset> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val statuses: Map<String, ItemState> = emptyMap(),
        val previews: Map<String, PreviewState> = emptyMap(),
        /** Summaries fetched from `loadNpc`, keyed by preset path; powers search. */
        val descriptions: Map<String, String> = emptyMap(),
        /** Photo behind nikonpc.com's `cLutImage` preview canvas. */
        val previewPhoto: Bitmap? = null,
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
                    val description = (previews[preset.path] as? PreviewState.Ready)?.description
                        ?: descriptions[preset.path]
                    preset.name.contains(search, ignoreCase = true) ||
                        preset.category.contains(search, ignoreCase = true) ||
                        preset.path.contains(search, ignoreCase = true) ||
                        preset.slot.contains(search, ignoreCase = true) ||
                        description?.contains(search, ignoreCase = true) == true
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

    private val previewSlots = Semaphore(2)
    private val previewJobs = mutableMapOf<String, Job>()
    private var descriptionJob: Job? = null

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
                val previewPhoto = service.fetchPreviewImage()
                    ?.let { bytes ->
                        withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }
                _state.update {
                    it.copy(
                        catalog = catalog,
                        previewPhoto = previewPhoto ?: it.previewPhoto,
                        loading = false,
                        error = null
                    )
                }
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
        if (query.isNotBlank()) ensureDescriptions()
    }

    /**
     * Fetches `loadNpc` metadata for presets whose summary is not known yet so
     * that content search can match every preset, not only the rows that have
     * already been scrolled into view.
     */
    private fun ensureDescriptions() {
        if (descriptionJob?.isActive == true) return
        val state = _state.value
        val missing = state.catalog.filter { item ->
            state.descriptions[item.path] == null &&
                (state.previews[item.path] as? PreviewState.Ready)?.description == null
        }
        if (missing.isEmpty()) return
        descriptionJob = viewModelScope.launch {
            val slots = Semaphore(4)
            supervisorScope {
                missing.map { item ->
                    async {
                        slots.withPermit {
                            try {
                                val summary = presetSummary(service.fetchMetadata(item))
                                _state.update {
                                    it.copy(descriptions = it.descriptions + (item.path to summary))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Keep the row searchable by name even if its note is unavailable.
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * Builds the preview for a row in the download list.
     *
     * nikonpc.com has no per-preset sample image: its `cLutImage` canvas draws
     * the site's preview photo through the preset. The list therefore mimics it
     * by rendering the downloaded preset on that same photo, and summarises the
     * decoded parameters. Files are downloaded for visible rows only and cached
     * on disk, so importing a preset afterwards does not download it again.
     */
    fun requestPreview(item: NikonPcPreset, force: Boolean = false) {
        val preview = _state.value.previews[item.path]
        if (!force && (preview is PreviewState.Ready || preview is PreviewState.Loading)) return
        if (previewJobs[item.path]?.isActive == true) return

        _state.update { it.copy(previews = it.previews + (item.path to PreviewState.Loading)) }
        previewJobs[item.path] = viewModelScope.launch {
            try {
                val ready = previewSlots.withPermit {
                    val bytes = service.download(item)
                    val control = PictureControlParser.parse(bytes, item.name)
                    val preset = Preset.from(
                        control = control,
                        id = "nikonpc:${item.path}",
                        fileName = "",
                        fileSize = 0,
                        isSample = false,
                        contentHash = "",
                        sourceName = item.path
                    ).copy(name = item.name)
                    PreviewState.Ready(preset, presetSummary(control))
                }
                _state.update {
                    it.copy(
                        previews = it.previews + (item.path to ready),
                        descriptions = it.descriptions + (item.path to ready.description)
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(previews = it.previews - item.path) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        previews = it.previews + (
                            item.path to PreviewState.Failed(e.message ?: "示例加载失败")
                            )
                    )
                }
            } finally {
                previewJobs.remove(item.path)
            }
        }
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
    private val settings: AppSettings,
    private val agnesImageService: AgnesImageService,
    private val aiCacheDir: File,
    private val agnesTextService: AgnesTextService = AgnesTextService()
) : ViewModel() {

    sealed interface AiEditState {
        data object Idle : AiEditState
        /** No API key saved; the UI offers a shortcut into Settings. */
        data object NeedApiKey : AiEditState
        data class Running(val prompt: String) : AiEditState
        data class Failed(val message: String) : AiEditState
        data class Done(val message: String) : AiEditState
    }

    /** State of the "生成提示词" action driven by the Agnes text model. */
    sealed interface PromptGenState {
        data object Idle : PromptGenState
        data object Running : PromptGenState
        /** [source] tells the UI whether this came from Agnes or local analysis. */
        data class Done(val prompt: String, val source: PromptSource) : PromptGenState
        data class Failed(val message: String) : PromptGenState
    }

    enum class PromptSource { AGNES, LOCAL }

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
        /** Full adjusted preview, kept uncropped for the interactive crop editor. */
        val cropBase: Bitmap? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val export: ExportState = ExportState.Idle,
        val seenCompareHint: Boolean = false,
        /** Non-null while the user has an unsaved master curve edit for the session. */
        val toneCurveOverride: ToneCurve? = null,
        /** Non-null while the user has unsaved per-channel curve edits. */
        val channelCurvesOverride: ChannelToneCurves? = null,
        /** Adjustments of the photo currently being previewed (per-photo grade). */
        val adjustments: EditAdjustments = EditAdjustments.Neutral,
        /** Undo/redo stack of the photo currently being previewed. */
        val historyEntries: List<EditHistory.Entry> = emptyList(),
        val historyIndex: Int = -1,
        /** State of the one-tap Agnes AI retouch request. */
        val aiState: AiEditState = AiEditState.Idle,
        /** Final AI images, keyed by photo id; these bypass local rendering. */
        val aiResultUris: Map<Long, Uri> = emptyMap(),
        /** Last prompt, pre-filled into the AI dialog. */
        val aiPrompt: String = "",
        /** State of the Agnes text-model prompt generation. */
        val promptGen: PromptGenState = PromptGenState.Idle,
        /** Whether a key is configured, shown as a hint in the prompt editor. */
        val hasApiKey: Boolean = false
    ) {
        val currentPhoto: PhotoItem? get() = photos.getOrNull(index)
        val selectedPreset: Preset? get() = when (presetId) {
            Preset.NONE_ID -> Preset.none()
            else -> presets.firstOrNull { it.id == presetId }
        }
        val currentPreset: Preset? get() = selectedPreset?.let { base ->
            base.copy(
                toneCurve = toneCurveOverride ?: base.toneCurve,
                channelCurves = channelCurvesOverride ?: base.channelCurves,
                adjustments = adjustments
            )
        }
        val effectiveToneCurve: ToneCurve
            get() = toneCurveOverride ?: selectedPreset?.toneCurve ?: ToneCurve.linear()
        val effectiveChannelCurves: ChannelToneCurves
            get() = channelCurvesOverride ?: selectedPreset?.channelCurves ?: ChannelToneCurves.Neutral
        val hasToneCurve: Boolean
            get() = !effectiveToneCurve.isLinear() || !effectiveChannelCurves.isNeutral
        val hasAdjustments: Boolean get() = !adjustments.isNeutral()
        val hasCrop: Boolean get() = !adjustments.crop.isFull()
        val hasLensBlur: Boolean get() = !adjustments.lensBlur.isOff()
        val canUndo: Boolean get() = historyIndex > 0
        val canRedo: Boolean get() = historyIndex in 0 until historyEntries.lastIndex
        val currentHistoryLabel: String? get() = historyEntries.getOrNull(historyIndex)?.label
        val hasAiResult: Boolean
            get() = currentPhoto?.let { aiResultUris.containsKey(it.id) } ?: false
    }

    private val _state = MutableStateFlow(
        UiState(
            seenCompareHint = settings.hasSeenCompareHint,
            aiPrompt = settings.agnesPrompt
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val originalCache = object : LruCache<Long, Bitmap>(3) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1
        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            // Keep evicted bitmaps for the GC; recycling here could race with drawing.
        }
    }

    private var renderJob: Job? = null
    private var exportJob: Job? = null
    private var aiJob: Job? = null
    private var renderGeneration = 0

    /**
     * One photo's private edit session.
     *
     * "下一步" is the ONLY batch operation: the preset chosen in the gallery is
     * copied once onto every selected photo as a shared baseline. From then on
     * each photo owns its own grade AND its own undo/redo stack, so editing,
     * undoing or changing the preset on one photo never touches another.
     */
    private data class PhotoEditSession(
        var presetId: String?,
        var toneCurveOverride: ToneCurve? = null,
        var channelCurvesOverride: ChannelToneCurves? = null,
        var adjustments: EditAdjustments = EditAdjustments.Neutral,
        val history: EditHistory = EditHistory()
    )

    /** Keyed by PhotoItem.id; [show] swaps the whole grade + history pair. */
    private val photoSessions = LinkedHashMap<Long, PhotoEditSession>()

    init {
        viewModelScope.launch {
            presetRepository.load()
            val loaded = presetRepository.presets.value
            val requestedId = selectionStore.presetId.value
            val selectedId = when {
                requestedId == Preset.NONE_ID -> Preset.NONE_ID
                requestedId != null && loaded.any { it.id == requestedId } -> requestedId
                else -> loaded.firstOrNull()?.id
            }
            val selected = if (selectedId == Preset.NONE_ID) {
                Preset.none()
            } else {
                loaded.firstOrNull { it.id == selectedId }
            }
            val photos = selectionStore.photos.value
            val baseline = selected?.adjustments ?: EditAdjustments.Neutral

            // Batch apply happens exactly here: every selected photo receives
            // its OWN copy of the chosen preset as step 0 of its OWN history.
            photoSessions.clear()
            photos.forEach { photo ->
                val session = PhotoEditSession(
                    presetId = selectedId,
                    adjustments = baseline
                )
                session.history.reset(
                    EditSnapshot(
                        photoId = photo.id,
                        presetId = selectedId,
                        toneCurve = null,
                        adjustments = baseline
                    ),
                    "打开照片"
                )
                photoSessions[photo.id] = session
            }

            _state.update {
                it.copy(
                    photos = photos,
                    presets = loaded,
                    presetId = selectedId,
                    adjustments = baseline
                )
            }
            if (photos.isEmpty()) {
                _state.update { it.copy(loading = false, error = "没有待处理的照片") }
            } else {
                selectionStore.setCurrentPhoto(_state.value.currentPhoto)
                syncHistoryState()
                renderCurrent()
            }
        }
        viewModelScope.launch {
            presetRepository.presets.collect { list ->
                val fallbackId = list.firstOrNull()?.id
                val fallbackAdjustments = if (fallbackId == Preset.NONE_ID) {
                    Preset.none().adjustments
                } else {
                    list.firstOrNull { it.id == fallbackId }?.adjustments ?: EditAdjustments.Neutral
                }
                // A preset deleted from the repository would otherwise leave a
                // stale id in a session and break that photo's export.
                photoSessions.values.forEach { session ->
                    val valid = session.presetId == Preset.NONE_ID ||
                        list.any { it.id == session.presetId }
                    if (!valid) {
                        session.presetId = fallbackId
                        session.toneCurveOverride = null
                        session.channelCurvesOverride = null
                        session.adjustments = fallbackAdjustments
                    }
                }
                val currentValid = _state.value.presetId == Preset.NONE_ID ||
                    list.any { it.id == _state.value.presetId }
                _state.update { current ->
                    if (currentValid) {
                        // Do not clobber an in-flight grade change (e.g. saving
                        // a look creates a new preset and re-emits this flow).
                        current.copy(presets = list)
                    } else {
                        val session = photoSessions[current.currentPhoto?.id]
                        current.copy(
                            presets = list,
                            presetId = session?.presetId ?: fallbackId,
                            toneCurveOverride = session?.toneCurveOverride,
                            channelCurvesOverride = session?.channelCurvesOverride,
                            adjustments = session?.adjustments ?: fallbackAdjustments
                        )
                    }
                }
            }
        }
    }

    fun markCompareHintSeen() {
        settings.hasSeenCompareHint = true
        _state.update { it.copy(seenCompareHint = true) }
    }

    // ------------------------------------------------------------- AI retouch

    /**
     * Checks whether AI retouching can start. With no saved key the state
     * becomes [AiEditState.NeedApiKey] so the UI can show a dialog that jumps
     * straight into Settings; no network request is made in that case.
     */
    fun requestAiEdit(): Boolean {
        // The AI dialog is a prompt editor and must stay reachable even with
        // no key: "生成提示词" can fall back to on-device analysis. The key is
        // only really required when the user taps 开始修图, where runAiEdit()
        // raises NeedApiKey and offers the Settings shortcut.
        _state.update {
            it.copy(aiState = AiEditState.Idle, hasApiKey = settings.hasAgnesApiKey)
        }
        return true
    }

    fun dismissAiState() {
        _state.update { it.copy(aiState = AiEditState.Idle) }
    }

    /** Runs a one-tap AI retouch with a user-supplied prompt on the current preview. */
    fun runAiEdit(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(aiState = AiEditState.Failed("请输入修图提示词")) }
            return
        }
        val key = settings.agnesApiKey.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(aiState = AiEditState.NeedApiKey) }
            return
        }
        val snapshot = _state.value
        val photo = snapshot.currentPhoto ?: return
        val preview = snapshot.rendered
        if (preview == null || preview.isRecycled) {
            _state.update { it.copy(aiState = AiEditState.Failed("当前没有可修图的预览图")) }
            return
        }
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _state.update { it.copy(aiState = AiEditState.Running(trimmed)) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val out = ByteArrayOutputStream()
                    check(preview.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "图片编码失败" }
                    out.toByteArray()
                }
                val result = agnesImageService.editImage(
                    apiKey = key,
                    prompt = trimmed,
                    imageBytes = bytes,
                    mimeType = "image/jpeg",
                    size = "1K",
                    ratio = closestRatio(preview.width, preview.height)
                )
                val uri = withContext(Dispatchers.IO) {
                    // Validate the payload without allocating a Bitmap, then
                    // keep the original bytes: recompressing would lose the
                    // model's output quality and cost a full-size copy.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        throw AgnesException("Agnes 返回的不是有效图片")
                    }
                    if (!aiCacheDir.exists()) aiCacheDir.mkdirs()
                    val isPng = result.bytes.size >= 4 &&
                        result.bytes[0] == 0x89.toByte() &&
                        result.bytes[1] == 'P'.code.toByte() &&
                        result.bytes[2] == 'N'.code.toByte() &&
                        result.bytes[3] == 'G'.code.toByte()
                    val file = File(
                        aiCacheDir,
                        "ai_${photo.id}_${System.currentTimeMillis()}.${if (isPng) "png" else "jpg"}"
                    )
                    FileOutputStream(file).use { it.write(result.bytes) }
                    Uri.fromFile(file)
                }
                settings.agnesPrompt = trimmed
                _state.update { current ->
                    current.copy(
                        aiResultUris = current.aiResultUris + (photo.id to uri),
                        aiPrompt = trimmed,
                        aiState = AiEditState.Done("AI 修图完成，导出将直接使用这张成品图")
                    )
                }
                recordHistory("应用 AI 修图结果")
                renderCurrent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgnesException) {
                _state.update {
                    it.copy(aiState = AiEditState.Failed(e.message ?: "AI 修图失败"))
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(aiState = AiEditState.Failed(e.message ?: "AI 修图失败"))
                }
            }
        }
    }

    fun cancelAiEdit() {
        aiJob?.cancel()
        aiJob = null
        _state.update { it.copy(aiState = AiEditState.Idle) }
    }

    /**
     * Hides the AI result for the current photo and returns to local rendering.
     * The cached bytes are kept (not deleted) so that **redo** through the
     * history stack can bring the AI layer back; the cache directory is cleaned
     * up with the rest of the app cache.
     */
    fun clearAiResult() {
        val id = _state.value.currentPhoto?.id ?: return
        if (_state.value.aiResultUris[id] == null) return
        _state.update {
            it.copy(aiResultUris = it.aiResultUris - id, aiState = AiEditState.Idle)
        }
        recordHistory("隐藏 AI 修图结果")
        renderCurrent()
    }

    private fun closestRatio(width: Int, height: Int): String {
        val value = width.toFloat() / height.coerceAtLeast(1)
        return SUPPORTED_RATIOS.minByOrNull { abs(it.second - value) }?.first ?: "1:1"
    }

    // --------------------------------------------------- prompt generation

    private var promptJob: Job? = null

    /**
     * Asks Agnes 3.0 Flash to look at the current preview and write an editing
     * prompt. The result flows through [UiState.promptGen] so the dialog can
     * fill its text field and the user can keep editing before submitting.
     *
     * 3.0 Flash is generally available. If the gateway still reports the model
     * unavailable or the request fails transiently, we fall back to on-device
     * photo analysis (same measured statistics as 一键优化) instead of leaving
     * the button dead, and the source is surfaced so the user is never misled.
     */
    fun generateEditPrompt() {
        val preview = _state.value.rendered
        if (preview == null || preview.isRecycled) {
            _state.update { it.copy(promptGen = PromptGenState.Failed("当前没有可分析的预览图")) }
            return
        }
        val key = settings.agnesApiKey.trim()
        promptJob?.cancel()
        promptJob = viewModelScope.launch {
            _state.update { it.copy(promptGen = PromptGenState.Running) }
            // No key: still produce a usable prompt from on-device analysis so
            // the button never leaves the user with an empty, dead input.
            if (key.isEmpty()) {
                emitLocalPrompt(preview, "尚未配置 Agnes 密钥")
                return@launch
            }
            try {
                val bytes = withContext(Dispatchers.IO) { encodeForAnalysis(preview) }
                val prompt = agnesTextService.generateEditPrompt(
                    apiKey = key,
                    imageBytes = bytes,
                    mimeType = "image/jpeg",
                    imageUrlOverride = settings.agnesImagePublicUrl.takeIf { it.isNotBlank() }
                )
                _state.update {
                    it.copy(promptGen = PromptGenState.Done(prompt, PromptSource.AGNES))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgnesException) {
                if (!modelUnavailable(e)) {
                    _state.update {
                        it.copy(promptGen = PromptGenState.Failed(e.message ?: "生成提示词失败"))
                    }
                } else {
                    emitLocalPrompt(preview, e.message)
                }
            } catch (e: Exception) {
                emitLocalPrompt(preview, e.message)
            }
        }
    }

    /**
     * Fallback used when the cloud model is not reachable / not enabled for this account.
     * The prompt is derived from the very same measured pixel statistics that
     * drive 一键优化, so it is specific to this photo rather than generic.
     */
    private suspend fun emitLocalPrompt(preview: Bitmap, reason: String?) {
        val local = withContext(Dispatchers.Default) {
            AutoEnhance.analyse(preview)?.let { AutoEnhance.describeForPrompt(it) }
        }
        _state.update {
            if (local == null) {
                it.copy(promptGen = PromptGenState.Failed(reason ?: "生成提示词失败"))
            } else {
                it.copy(promptGen = PromptGenState.Done(local, PromptSource.LOCAL))
            }
        }
    }

    /** Clears a delivered result/error so the dialog can accept another run. */
    fun consumePromptGen() {
        _state.update {
            if (it.promptGen is PromptGenState.Idle) it
            else it.copy(promptGen = PromptGenState.Idle)
        }
    }

    fun cancelPromptGen() {
        promptJob?.cancel()
        promptJob = null
        consumePromptGen()
    }

    /** Auth / quota errors must surface, not be masked by a local fallback. */
    private fun modelUnavailable(e: AgnesException): Boolean {
        val code = e.httpCode
        if (code == null || code == 404 || code >= 500) return true
        if (code == 400) {
            val text = ((e.serverMessage ?: "") + " " + (e.message ?: "")).lowercase()
            return "model" in text || "模型" in text || "not found" in text
        }
        return false
    }

    /** Downscale to <= 768 px and JPEG-encode to keep the vision request small. */
    private fun encodeForAnalysis(bitmap: Bitmap): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scaled = if (longest > 768) {
            val scale = 768f / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        try {
            val out = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)) { "图片编码失败" }
            return out.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    // ------------------------------------------------------------- history

    /** Private session of the photo currently shown in the preview. */
    private fun currentSession(): PhotoEditSession? =
        _state.value.currentPhoto?.id?.let { photoSessions[it] }

    /** Copies the visible grade back into the current photo's session. */
    private fun persistCurrentSession() {
        val session = currentSession() ?: return
        val visible = _state.value
        session.presetId = visible.presetId
        session.toneCurveOverride = visible.toneCurveOverride
        session.channelCurvesOverride = visible.channelCurvesOverride
        session.adjustments = visible.adjustments
    }

    private fun currentSnapshot(): EditSnapshot? {
        val snapshot = _state.value
        val photo = snapshot.currentPhoto ?: return null
        return EditSnapshot(
            photoId = photo.id,
            presetId = snapshot.presetId,
            toneCurve = snapshot.toneCurveOverride,
            adjustments = snapshot.adjustments,
            channelCurves = snapshot.channelCurvesOverride,
            // The AI result is part of the edit state, so undo / redo restore
            // it together with the local grade instead of replacing the editor.
            aiResultUri = snapshot.aiResultUris[photo.id]?.toString()
        )
    }

    /** Records one step in the CURRENT photo's own history. */
    private fun recordHistory(label: String, coalesceKey: String? = null) {
        val session = currentSession() ?: return
        val snapshot = currentSnapshot() ?: return
        persistCurrentSession()
        session.history.record(label, snapshot, coalesceKey)
        syncHistoryState()
    }

    /** Mirrors the current photo's history into the UI state. */
    private fun syncHistoryState() {
        val history = currentSession()?.history
        _state.update {
            it.copy(
                historyEntries = history?.entries?.toList() ?: emptyList(),
                historyIndex = history?.index ?: -1
            )
        }
    }

    /**
     * Restores one step of the CURRENT photo's history.
     *
     * History is PER PHOTO: neither `index` (which photo is previewed) nor any
     * other photo's grade is touched. [show] is the only place that swaps in a
     * different photo's grade + history pair. Undoing on a non-first photo can
     * therefore never teleport back to photo #1 (1.6.27) and can never change
     * what another photo will export (1.6.28).
     */
    private fun jumpHistory(target: Int): Boolean {
        val session = currentSession() ?: return false
        val entry = session.history.entries.getOrNull(target) ?: return false
        val snapshot = entry.snapshot
        val photoId = _state.value.currentPhoto?.id ?: return false

        val presetStillExists = snapshot.presetId == Preset.NONE_ID ||
            _state.value.presets.any { it.id == snapshot.presetId }
        // Re-attach the AI layer that belonged to this step, but only when its
        // cached file still exists (a purged cache degrades cleanly).
        val restoredAi = snapshot.aiResultUri
            ?.takeIf { snapshot.aiResultVisible && snapshot.aiResultOpacity > 0.001f }
            ?.let { Uri.parse(it) }
            ?.takeIf { it.path?.let { path -> File(path).exists() } == true }
        _state.update { current ->
            current.copy(
                // `index` intentionally left untouched: undo / redo / jumpTo
                // operate on the photo the user is editing right now.
                presetId = if (presetStillExists) snapshot.presetId else current.presetId,
                toneCurveOverride = snapshot.toneCurve,
                channelCurvesOverride = snapshot.channelCurves,
                adjustments = snapshot.adjustments,
                aiResultUris = if (restoredAi != null) {
                    current.aiResultUris + (photoId to restoredAi)
                } else {
                    current.aiResultUris - photoId
                }
            )
        }
        session.history.jumpTo(target)
        persistCurrentSession()
        selectionStore.setPreset(_state.value.presetId)
        syncHistoryState()
        renderCurrent()
        return true
    }

    fun undo() {
        val history = currentSession()?.history ?: return
        if (history.canUndo) jumpHistory(history.index - 1)
    }

    fun redo() {
        val history = currentSession()?.history ?: return
        if (history.canRedo) jumpHistory(history.index + 1)
    }

    fun jumpToHistory(index: Int) {
        val history = currentSession()?.history ?: return
        if (index == history.index) return
        jumpHistory(index)
    }

    fun selectPreset(id: String) {
        if (_state.value.presetId == id) return
        val preset = if (id == Preset.NONE_ID) {
            Preset.none()
        } else {
            _state.value.presets.firstOrNull { it.id == id }
        }
        _state.update {
            it.copy(
                presetId = id,
                toneCurveOverride = null,
                channelCurvesOverride = null,
                adjustments = preset?.adjustments ?: EditAdjustments.Neutral
            )
        }
        selectionStore.setPreset(id)
        recordHistory("套用预设：${preset?.name ?: "未知预设"}")
        renderCurrent()
    }

    fun setToneCurve(curve: ToneCurve) {
        val normalized = ToneCurve(curve.normalizedPoints)
        if (_state.value.toneCurveOverride == normalized) return
        _state.update { it.copy(toneCurveOverride = normalized) }
        recordHistory("曲线调整", coalesceKey = "tone-curve")
        renderCurrent()
    }

    /**
     * Updates one colour channel of the tone curve set. The other channels are
     * taken from the currently effective set, so switching channels never
     * discards work done on a different channel.
     */
    fun setChannelCurves(channel: ToneCurveChannel, curve: ToneCurve) {
        if (channel.isMaster) {
            setToneCurve(curve)
            return
        }
        val normalized = ToneCurve(curve.normalizedPoints)
        val next = _state.value.effectiveChannelCurves
            .withCurve(channel, normalized)
            .normalized()
        if (_state.value.channelCurvesOverride == next) return
        _state.update { it.copy(channelCurvesOverride = next) }
        recordHistory("曲线调整 · ${channel.label} 通道", coalesceKey = "channel-curve:${channel.key}")
        renderCurrent()
    }

    fun setAdjustments(adjustments: EditAdjustments, changedField: String? = null) {
        val normalized = adjustments.normalized()
        if (_state.value.adjustments == normalized) return
        _state.update { it.copy(adjustments = normalized) }
        val label = if (changedField.isNullOrBlank()) "调节" else "调节 · $changedField"
        val key = if (changedField.isNullOrBlank()) "adjust" else "adjust:$changedField"
        recordHistory(label, coalesceKey = key)
        renderCurrent()
    }

    /**
     * Reverts only the master RGB curve. The adjustments sheet uses this for
     * its "reset tone" action, so resetting the master curve never discards
     * per-channel curves edited in the dedicated curve sheet.
     */
    fun clearToneCurveOverride() {
        if (_state.value.toneCurveOverride == null) return
        _state.update { it.copy(toneCurveOverride = null) }
        recordHistory("还原预设曲线")
        renderCurrent()
    }

    /** Reverts every curve (master + red / green / blue) to the preset's own. */
    fun clearAllCurveOverrides() {
        if (_state.value.toneCurveOverride == null && _state.value.channelCurvesOverride == null) return
        _state.update { it.copy(toneCurveOverride = null, channelCurvesOverride = null) }
        recordHistory("还原预设曲线")
        renderCurrent()
    }

    /**
     * Commits a crop / straighten change from the interactive editor. Crop is
     * part of the adjustments, so preview, presets and batch export all share
     * the same framing.
     */
    fun setCrop(crop: EditCrop) {
        setAdjustments(_state.value.adjustments.copy(crop = crop), "裁剪")
    }

    /** Applies the Lightroom-style lens blur edited in the full-screen editor. */
    fun setLensBlur(lensBlur: LensBlur) {
        setAdjustments(_state.value.adjustments.copy(lensBlur = lensBlur), "镜头模糊")
    }

    /**
     * Saves the current look as a new, binary-less preset. The original preset
     * and its NCP/NP3 file are left untouched.
     */
    suspend fun saveCurrentLookPreset(name: String): Preset? {
        val snapshot = _state.value
        val base = snapshot.selectedPreset ?: return null
        val curve = snapshot.effectiveToneCurve
        val channelCurves = snapshot.effectiveChannelCurves
        val saved = presetRepository.add(
            base.copy(
                name = name.trim().ifBlank { "${base.name} 调整" }.take(40),
                fileName = "",
                fileSize = 0,
                sourceName = "由 ${base.name} 调整",
                toneCurve = curve.takeUnless { it.isLinear() },
                channelCurves = channelCurves,
                adjustments = snapshot.adjustments,
                note = base.note,
                importedAt = System.currentTimeMillis(),
                isSample = false,
                contentHash = ""
            )
        )
        // Keep working with the saved copy on the next launch instead of the
        // original preset plus transient overrides.
        _state.update {
            it.copy(
                presets = presetRepository.presets.value,
                presetId = saved.id,
                toneCurveOverride = null,
                channelCurvesOverride = null,
                adjustments = saved.adjustments
            )
        }
        selectionStore.setPreset(saved.id)
        recordHistory("保存预设：${saved.name}")
        // The saved copy has a new ID, so an in-flight render for the original
        // preset will be discarded by the generation guard. Render again with
        // the persisted look.
        renderCurrent()
        return saved
    }

    /**
     * Switches to another photo for individual editing.
     *
     * The batch preset was already applied once on "下一步"; from here on every
     * photo is graded on its own, so switching swaps in that photo's private
     * grade AND its private history stack. Switching itself records no step.
     */
    fun show(index: Int) {
        if (index !in _state.value.photos.indices || index == _state.value.index) return
        persistCurrentSession()
        val targetId = _state.value.photos[index].id
        val target = photoSessions[targetId]
        _state.update { current ->
            if (target == null) {
                current.copy(index = index)
            } else {
                current.copy(
                    index = index,
                    presetId = target.presetId,
                    toneCurveOverride = target.toneCurveOverride,
                    channelCurvesOverride = target.channelCurvesOverride,
                    adjustments = target.adjustments,
                    historyEntries = target.history.entries.toList(),
                    historyIndex = target.history.index
                )
            }
        }
        selectionStore.setCurrentPhoto(_state.value.currentPhoto)
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
        val crop = snapshot.adjustments.crop
        renderJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val original = originalCache.get(photo.id) ?: run {
                    val decoded = BitmapLoader.decodePreview(context, photo.uri, 1800)
                        ?: throw IllegalStateException("无法读取这张照片")
                    originalCache.put(photo.id, decoded)
                    decoded
                }
                // A finished AI edit is the final photo: show and export it
                // as-is instead of applying the local preset a second time.
                val aiUri = snapshot.aiResultUris[photo.id]
                if (aiUri != null) {
                    val finalImage = BitmapLoader.decodePreview(context, aiUri, 1800)
                        ?: throw IllegalStateException("无法读取 AI 修图结果")
                    _state.update { current ->
                        if (generation == renderGeneration &&
                            current.currentPhoto?.id == photo.id &&
                            current.aiResultUris[photo.id] == aiUri
                        ) {
                            current.copy(
                                original = original,
                                rendered = finalImage,
                                cropBase = finalImage,
                                loading = false,
                                error = null
                            )
                        } else {
                            current
                        }
                    }
                    return@launch
                }
                // Render the uncropped frame once: it is both the base for the
                // interactive crop editor and the source for the visible crop.
                val basePreset = if (crop.isFull()) {
                    preset
                } else {
                    preset.copy(adjustments = preset.adjustments.copy(crop = EditCrop.Full))
                }
                val fullFrame = renderer.render(original, basePreset, forExport = false)
                val shown = if (crop.isFull()) fullFrame else renderer.crop(fullFrame, crop)
                _state.update { current ->
                    if (generation == renderGeneration &&
                        current.currentPhoto?.id == photo.id &&
                        current.presetId == preset.id
                    ) {
                        current.copy(
                            original = original,
                            rendered = shown,
                            cropBase = fullFrame,
                            loading = false
                        )
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

    /**
     * Builds the export preset for every selected photo from its OWN session.
     *
     * The batch "下一步" only seeds a shared baseline. After that, a preset /
     * curve / adjustment change on one photo must never be exported onto any
     * other photo. Batch export still processes the whole selection in one run
     * — it is the EDITING that is per-photo, not the export loop.
     */
    fun exportPresetsByPhoto(): Map<Long, Preset> {
        persistCurrentSession()
        val state = _state.value
        val result = LinkedHashMap<Long, Preset>()
        state.photos.forEach { photo ->
            val session = photoSessions[photo.id] ?: return@forEach
            val base = when (session.presetId) {
                Preset.NONE_ID -> Preset.none()
                else -> state.presets.firstOrNull { it.id == session.presetId }
            } ?: return@forEach
            result[photo.id] = base.copy(
                toneCurve = session.toneCurveOverride ?: base.toneCurve,
                channelCurves = session.channelCurvesOverride ?: base.channelCurves,
                adjustments = session.adjustments
            )
        }
        return result
    }

    fun export(options: ExportManager.Options) {
        if (_state.value.export is ExportState.Running) return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            // Snapshot every photo's own grade before the (long) export starts.
            val perPhoto = exportPresetsByPhoto()
            val fallback = _state.value.currentPreset
            if (fallback == null) {
                _state.update { it.copy(export = ExportState.Failed("请选择一个预设")) }
                return@launch
            }
            _state.update { it.copy(export = ExportState.Running(0, _state.value.photos.size, "")) }
            try {
                val result = exportManager.export(
                    photos = _state.value.photos,
                    preset = fallback,
                    options = options,
                    finalImageUris = _state.value.aiResultUris,
                    presetsByPhoto = perPhoto
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
        aiJob?.cancel()
        super.onCleared()
    }

    companion object {
        private val SUPPORTED_RATIOS = listOf(
            "1:1" to 1f,
            "3:4" to 0.75f,
            "4:3" to 4f / 3f,
            "16:9" to 16f / 9f,
            "9:16" to 9f / 16f,
            "2:3" to 2f / 3f,
            "3:2" to 3f / 2f,
            "21:9" to 21f / 9f
        )
    }
}

class FujiKonViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(PresetsViewModel::class.java) ->
            PresetsViewModel(container.presetRepository) as T
        modelClass.isAssignableFrom(NikonPcViewModel::class.java) ->
            NikonPcViewModel(container.nikonPcService, container.presetRepository) as T
        modelClass.isAssignableFrom(NikonCloudViewModel::class.java) ->
            NikonCloudViewModel(container.nikonCloudService, container.presetRepository) as T
        modelClass.isAssignableFrom(GalleryViewModel::class.java) ->
            GalleryViewModel(container.photoRepository, container.selectionStore) as T
        modelClass.isAssignableFrom(EditorViewModel::class.java) ->
            EditorViewModel(
                context = container.appContext,
                presetRepository = container.presetRepository,
                selectionStore = container.selectionStore,
                renderer = container.imageRenderer,
                exportManager = container.exportManager,
                settings = container.settings,
                agnesImageService = container.agnesImageService,
                aiCacheDir = container.aiEditCacheDir,
                agnesTextService = container.agnesTextService
            ) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
