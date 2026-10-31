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

    /**
     * The photo currently shown in the editor (or the first photo selected in
     * the gallery). Preset thumbnails use it as their preview source and fall
     * back to the built-in sample when it is null.
     */
    private val _currentPhoto = MutableStateFlow<PhotoItem?>(null)
    val currentPhoto: StateFlow<PhotoItem?> = _currentPhoto.asStateFlow()

    fun set(photos: List<PhotoItem>, presetId: String?) {
        _photos.value = photos
        _presetId.value = presetId
        if (photos.isNotEmpty() && photos.none { it.id == _currentPhoto.value?.id }) {
            _currentPhoto.value = photos.first()
        }
    }

    fun setPhotos(photos: List<PhotoItem>) {
        _photos.value = photos
        if (photos.isNotEmpty() && photos.none { it.id == _currentPhoto.value?.id }) {
            _currentPhoto.value = photos.first()
        }
    }

    fun setPreset(id: String?) {
        _presetId.value = id
    }

    fun setCurrentPhoto(photo: PhotoItem?) {
        _currentPhoto.value = photo
    }

    fun clear() {
        _photos.value = emptyList()
        _presetId.value = null
        _currentPhoto.value = null
    }
}
