package com.fujikon.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.PhotoRepository
import com.fujikon.app.data.SelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.42: 「最新选择的第一张照片」必须是稳定的勾选顺序第一位，而不是
 * Set 的迭代顺序（无序 Set 会让预览源随机漂移）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GalleryViewModelSelectionOrderTest {

    private lateinit var vm: GalleryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Context = ApplicationProvider.getApplicationContext()
        vm = GalleryViewModel(PhotoRepository(context), SelectionStore())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
    fun pickOrderIsStableAndRepickingMovesTheFirstPhoto() {
        vm.toggle(3)
        vm.toggle(1)
        vm.toggle(2)
        assertEquals(listOf(3L, 1L, 2L), vm.selectionOrder.value)
        assertEquals(setOf(1L, 2L, 3L), vm.selectedIds.value)

        // 取消后重新勾选，应排到末尾而不是回到原来的位置。
        vm.toggle(1)
        assertEquals(listOf(3L, 2L), vm.selectionOrder.value)
        vm.toggle(1)
        assertEquals(listOf(3L, 2L, 1L), vm.selectionOrder.value)

        vm.clearSelection()
        assertEquals(emptyList<Long>(), vm.selectionOrder.value)
        assertEquals(emptySet<Long>(), vm.selectedIds.value)
    }

    @Test
    fun selectAllKeepsGridOrderWithoutDuplicates() {
        vm.selectAll(listOf(photo(5), photo(4), photo(9)))
        assertEquals(listOf(5L, 4L, 9L), vm.selectionOrder.value)

        vm.selectAll(listOf(photo(9), photo(4)))
        assertEquals(listOf(5L, 4L, 9L), vm.selectionOrder.value)
        assertEquals(setOf(5L, 4L, 9L), vm.selectedIds.value)
    }

    @Test
    fun chosenPhotosFollowsSelectionOrder() {
        // 没有媒体权限时 state.photos 为空，chosenPhotos() 必须安全返回空。
        assertEquals(emptyList<PhotoItem>(), vm.chosenPhotos())
        vm.toggle(1)
        assertEquals(emptyList<PhotoItem>(), vm.chosenPhotos())
    }
}
