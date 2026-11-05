package com.fujikon.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fujikon.app.data.PresetRepository
import com.fujikon.app.nic.NikonCloudDetail
import com.fujikon.app.nic.NikonCloudRecipe
import com.fujikon.app.nic.NikonCloudService
import com.fujikon.app.nic.contentHash
import com.fujikon.app.nic.toPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Browses the public Nikon Imaging Cloud recipe library and imports each
 * recipe as a local preset.
 */
class NikonCloudViewModel(
    private val service: NikonCloudService,
    private val repository: PresetRepository
) : ViewModel() {

    sealed interface ItemState {
        data object Idle : ItemState
        data object Importing : ItemState
        data object Imported : ItemState
        data object Duplicate : ItemState
        data class Failed(val message: String) : ItemState
    }

    sealed interface DetailState {
        data object Loading : DetailState
        data class Ready(val detail: NikonCloudDetail) : DetailState
        data class Failed(val message: String) : DetailState
    }

    data class UiState(
        val catalog: List<NikonCloudRecipe> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val details: Map<String, DetailState> = emptyMap(),
        val statuses: Map<String, ItemState> = emptyMap(),
        val notice: String? = null
    ) {
        val filteredCatalog: List<NikonCloudRecipe>
            get() {
                val search = query.trim()
                if (search.isEmpty()) return catalog
                return catalog.filter { recipe ->
                    val detail = (details[recipe.id] as? DetailState.Ready)?.detail
                    val brief = detail?.brief.orEmpty()
                    val note = detail?.note.orEmpty()
                    recipe.name.contains(search, ignoreCase = true) ||
                        recipe.creator.contains(search, ignoreCase = true) ||
                        recipe.pictureControlName.contains(search, ignoreCase = true) ||
                        brief.contains(search, ignoreCase = true) ||
                        note.contains(search, ignoreCase = true)
                }
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val detailPermits = Semaphore(4)

    /** Debounced background prefetch of recipe descriptions for full-text search. */
    private var searchPrefetch: Job? = null

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
                    it.copy(loading = false, error = e.message ?: "加载尼康云创失败")
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchPrefetch?.cancel()
        if (query.isBlank()) return
        searchPrefetch = viewModelScope.launch {
            delay(400)
            // Titles, creators and already-loaded descriptions are searched
            // immediately. Only when nothing matches do we fetch the remaining
            // descriptions, so typing does not spam the detail endpoint.
            if (_state.value.filteredCatalog.isNotEmpty()) return@launch
            _state.value.catalog.forEach { recipe -> requestDetail(recipe.id) }
        }
    }

    /** Called when a row becomes visible so its description is fetched once. */
    fun requestDetail(recipeId: String, force: Boolean = false) {
        if (recipeId.isBlank()) return
        val current = _state.value.details[recipeId]
        if (!force && (current == DetailState.Loading || current is DetailState.Ready)) return
        _state.update {
            it.copy(details = it.details + (recipeId to DetailState.Loading))
        }
        viewModelScope.launch {
            detailPermits.withPermit {
                try {
                    val detail = service.fetchDetail(recipeId)
                    _state.update {
                        it.copy(details = it.details + (recipeId to DetailState.Ready(detail)))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            details = it.details + (
                                recipeId to DetailState.Failed(e.message ?: "说明加载失败")
                                )
                        )
                    }
                }
            }
        }
    }

    fun importOne(recipe: NikonCloudRecipe) {
        when (_state.value.statuses[recipe.id]) {
            ItemState.Importing, ItemState.Imported, ItemState.Duplicate -> return
            else -> Unit
        }
        setStatus(recipe.id, ItemState.Importing)
        viewModelScope.launch {
            try {
                val cached = (_state.value.details[recipe.id] as? DetailState.Ready)?.detail
                val detail = cached ?: service.fetchDetail(recipe.id)
                if (cached == null) {
                    _state.update {
                        it.copy(details = it.details + (recipe.id to DetailState.Ready(detail)))
                    }
                }

                val hash = recipe.contentHash()
                if (repository.presets.value.any { it.contentHash == hash }) {
                    setStatus(recipe.id, ItemState.Duplicate)
                    _state.update { it.copy(notice = "${recipe.name} 已导入") }
                    return@launch
                }
                val preset = recipe.toPreset(detail)
                val saved = repository.add(preset)
                setStatus(recipe.id, ItemState.Imported)
                _state.update { it.copy(notice = "已导入「${saved.name}」") }
            } catch (e: CancellationException) {
                setStatus(recipe.id, ItemState.Idle)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "导入失败"
                setStatus(recipe.id, ItemState.Failed(message))
                _state.update { it.copy(notice = "${recipe.name}：$message") }
            }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun setStatus(id: String, status: ItemState) {
        _state.update { it.copy(statuses = it.statuses + (id to status)) }
    }
}
