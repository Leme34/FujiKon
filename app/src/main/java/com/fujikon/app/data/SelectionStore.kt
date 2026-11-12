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
     * The photo currently shown in the editor. Preset previews must NOT read
     * this: paging through photos in the editor would otherwise silently change
     * what every preset in the list is previewed on. See [previewPhoto].
     */
    private val _currentPhoto = MutableStateFlow<PhotoItem?>(null)
    val currentPhoto: StateFlow<PhotoItem?> = _currentPhoto.asStateFlow()

    /**
     * The FIRST photo of the most recent selection. This is the single source
     * of truth for every preset preview (home grid and picker sheet). It only
     * changes when a new batch of photos is picked, so returning to the photo
     * picker and re-selecting photos always refreshes the previews.
     */
    private val _previewPhoto = MutableStateFlow<PhotoItem?>(null)
    val previewPhoto: StateFlow<PhotoItem?> = _previewPhoto.asStateFlow()

    fun set(photos: List<PhotoItem>, presetId: String?) {
        _photos.value = photos
        _presetId.value = presetId
        applyNewSelection(photos)
    }

    fun setPhotos(photos: List<PhotoItem>) {
        _photos.value = photos
        applyNewSelection(photos)
    }

    /** A new batch always restarts at its first photo, even if the previous
     * current photo is part of the new batch again. */
    private fun applyNewSelection(photos: List<PhotoItem>) {
        val first = photos.firstOrNull()
        _currentPhoto.value = first
        _previewPhoto.value = first
    }

    /** Called while the user re-picks photos, before entering the editor. */
    fun setPreviewPhoto(photo: PhotoItem?) {
        _previewPhoto.value = photo
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
        _previewPhoto.value = null
    }
}
