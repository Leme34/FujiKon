package com.fujikon.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the current photo selection between the gallery and the editor. */
class SelectionStore {
    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    private val _presetId = MutableStateFlow<String?>(null)
    val presetId: StateFlow<String?> = _presetId.asStateFlow()

    fun set(photos: List<PhotoItem>, presetId: String?) {
        _photos.value = photos
        _presetId.value = presetId
    }

    fun setPhotos(photos: List<PhotoItem>) {
        _photos.value = photos
    }

    fun setPreset(id: String?) {
        _presetId.value = id
    }

    fun clear() {
        _photos.value = emptyList()
        _presetId.value = null
    }
}
