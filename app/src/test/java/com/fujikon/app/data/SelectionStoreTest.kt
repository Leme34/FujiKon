package com.fujikon.app.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.42: 返回选择照片、重新勾选后，预设列表的预览图必须切到新批次的
 * 第一张。预览源与「编辑器当前浏览的照片」必须是两个独立状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectionStoreTest {

    private fun photo(id: Long) = PhotoItem(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "IMG_$id.jpg",
        dateTaken = id,
        dateAdded = id,
        bucketId = "camera",
        bucketName = "Camera",
        width = 1200,
        height = 800,
        mimeType = "image/jpeg",
        size = 1024L
    )

    @Test
    fun newSelectionStartsPreviewAtFirstPhoto() {
        val store = SelectionStore()
        val first = photo(1)
        store.setPhotos(listOf(first, photo(2), photo(3)))
        assertSame(first, store.previewPhoto.value)
        assertSame(first, store.currentPhoto.value)
        assertEquals(3, store.photos.value.size)
    }

    @Test
    fun reselectingPhotosAlwaysMovesPreviewToNewFirstPhoto() {
        val store = SelectionStore()
        val old = photo(1)
        store.setPhotos(listOf(old, photo(2)))

        // 新批次里仍包含旧照片也必须切到新的第一张（旧实现会保留旧照片）。
        val newFirst = photo(3)
        store.setPhotos(listOf(newFirst, old, photo(4)))
        assertSame(newFirst, store.previewPhoto.value)
        assertSame(newFirst, store.currentPhoto.value)

        // set(photos, presetId) 走同一条路径。
        val another = photo(9)
        store.set(listOf(another, newFirst), "preset-x")
        assertSame(another, store.previewPhoto.value)
        assertEquals("preset-x", store.presetId.value)
    }

    @Test
    fun pagingInEditorDoesNotChangePresetPreview() {
        val store = SelectionStore()
        val first = photo(1)
        val third = photo(3)
        store.setPhotos(listOf(first, photo(2), third))

        // 编辑器左右翻页只改 currentPhoto，不能把预设预览也带走。
        store.setCurrentPhoto(third)
        assertSame(third, store.currentPhoto.value)
        assertSame(first, store.previewPhoto.value)
    }

    @Test
    fun reselectingWhileBrowsingRefreshesPreviewImmediately() {
        val store = SelectionStore()
        store.setPhotos(listOf(photo(1), photo(2)))
        store.setCurrentPhoto(photo(2))

        // GalleryScreen 在用户重新勾选照片时直接同步预览源，无需点「下一步」。
        val newFirst = photo(7)
        store.setPreviewPhoto(newFirst)
        assertSame(newFirst, store.previewPhoto.value)
        assertEquals(2L, store.currentPhoto.value?.id)
    }

    @Test
    fun clearDropsEveryPhotoState() {
        val store = SelectionStore()
        store.setPhotos(listOf(photo(1)))
        store.setPreset("preset-y")
        store.clear()
        assertNull(store.previewPhoto.value)
        assertNull(store.currentPhoto.value)
        assertNull(store.presetId.value)
        assertEquals(emptyList<PhotoItem>(), store.photos.value)
    }
}
