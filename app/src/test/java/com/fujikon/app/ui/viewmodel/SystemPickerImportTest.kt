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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「没选照片点下一步 → 系统图库选完返回」这条路径必须真正把系统返回的照片
 * 当成一批待处理照片：既能被 [GalleryViewModel.chosenPhotos] 选出并交给编辑器，
 * 也能把系统 content URI 转成带正确尺寸/mime 的 [PhotoItem]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemPickerImportTest {

    private lateinit var context: Context
    private lateinit var vm: GalleryViewModel
    private lateinit var source: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        vm = GalleryViewModel(PhotoRepository(context), SelectionStore())
        source = File(context.cacheDir, "picker_source.jpg")
        javaClass.getResourceAsStream("/test_photo.jpg")!!.use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        source.delete()
    }

    @Test
    fun adoptedExternalPhotosBecomeChosenInPickOrder() {
        // 系统图库返回的照片用负数 id，且不在相册列表（state.photos 为空）里。
        val picked = listOf(
            PhotoItem(
                id = -1L, uri = Uri.parse("content://picker/-1"), displayName = "a.jpg",
                dateTaken = 1L, dateAdded = 1L, bucketId = "picked", bucketName = "已选择",
                width = 48, height = 64, mimeType = "image/jpeg", size = 100L
            ),
            PhotoItem(
                id = -2L, uri = Uri.parse("content://picker/-2"), displayName = "b.jpg",
                dateTaken = 2L, dateAdded = 2L, bucketId = "picked", bucketName = "已选择",
                width = 64, height = 48, mimeType = "image/jpeg", size = 200L
            )
        )

        vm.adoptExternalPhotos(picked)

        assertEquals(setOf(-1L, -2L), vm.selectedIds.value)
        assertEquals(listOf(-1L, -2L), vm.selectionOrder.value)
        // 核心断言：不在相册列表里的系统照片也应能被选出，交给「下一步」导入编辑器。
        assertEquals(2, vm.chosenPhotos().size)
        assertEquals("a.jpg", vm.chosenPhotos().first().displayName)
        assertEquals("b.jpg", vm.chosenPhotos()[1].displayName)
    }

    @Test
    fun adoptClearsPreviousGallerySelection() {
        // 先模拟相册里已勾选一张（走 toggle 需要相册有数据，这里直接构造外部两批验证覆盖）。
        val first = PhotoItem(
            id = -1L, uri = Uri.parse("content://picker/1"), displayName = "old.jpg",
            dateTaken = 1L, dateAdded = 1L, bucketId = "picked", bucketName = "已选择",
            width = 48, height = 64, mimeType = "image/jpeg", size = 100L
        )
        vm.adoptExternalPhotos(listOf(first))
        assertEquals(listOf(-1L), vm.selectionOrder.value)

        val fresh = first.copy(id = -2L, displayName = "new.jpg")
        vm.adoptExternalPhotos(listOf(fresh))

        assertEquals(listOf(-2L), vm.selectionOrder.value)
        assertEquals(setOf(-2L), vm.selectedIds.value)
        assertEquals(1, vm.chosenPhotos().size)
        assertEquals("new.jpg", vm.chosenPhotos().first().displayName)
    }

    @Test
    fun photosFromUrisConvertsSystemUriToUsablePhotoItem() = runBlocking {
        val repo = PhotoRepository(context)
        val uri = Uri.fromFile(source)

        val photos = repo.photosFromUris(listOf(uri))

        assertEquals(1, photos.size)
        val photo = photos.first()
        assertNotNull(photo.uri)
        assertEquals(uri, photo.uri)
        assertTrue("id 应为负数，避免与 MediaStore 正数 id 冲突", photo.id < 0)
        assertEquals("来源 mime 应保留", "image/jpeg", photo.mimeType)
        assertEquals("displayName 应有兜底", true, photo.displayName.isNotBlank())
        // 测试图原始解码边界 64x48 且带 90° EXIF 旋转，应用旋转后应为 48x64，
        // 与 ImageIoRobolectricTest 的 decodePreview 结果一致。
        val bounds = com.fujikon.app.render.BitmapLoader.readBounds(context, uri)
        assertNotNull(bounds)
        assertTrue("宽高应大于 0", photo.width > 0 && photo.height > 0)
        assertEquals(48, photo.width)
        assertEquals(64, photo.height)
    }
}
