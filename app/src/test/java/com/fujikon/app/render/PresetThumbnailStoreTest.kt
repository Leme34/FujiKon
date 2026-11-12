package com.fujikon.app.render

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.PresetRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PresetThumbnailStoreTest {

    private lateinit var context: Context
    private lateinit var repository: PresetRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        repository = PresetRepository(context)
    }

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
    }

    @Test
    fun rendersSamplePreviewAndCachesIt() = runBlocking {
        repository.load()
        val presets = repository.presets.value
        val store = PresetThumbnailStore(context, ImageRenderer())

        val first = store.thumbnail(null, presets.first(), targetSize = 96)
        assertNotNull(first)
        assertEquals(96, max(first!!.width, first.height))
        assertSame(first, store.cached(null, presets.first(), 96))

        val cached = store.thumbnail(null, presets.first(), targetSize = 96)
        assertSame(first, cached)

        val other = store.thumbnail(null, presets[1], targetSize = 96)
        assertNotNull(other)
        assertNotSame(first, other)
    }

    @Test
    fun signatureChangesWhenTheLookChanges() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first()
        val store = PresetThumbnailStore(context, ImageRenderer())

        val base = store.visualSignature(preset)
        val adjusted = store.visualSignature(preset.copy(adjustments = preset.adjustments.copy(exposure = 1f)))

        assertEquals(base, store.visualSignature(preset))
        org.junit.Assert.assertNotEquals(base, adjusted)
    }

    @Test
    fun everySamplePresetProducesAVisibleThumbnail() = runBlocking {
        repository.load()
        val presets = repository.presets.value
        assertTrue(presets.isNotEmpty())
        val store = PresetThumbnailStore(context, ImageRenderer())

        presets.forEach { preset ->
            val thumbnail = store.thumbnail(null, preset, targetSize = 96)
            assertNotNull("Missing thumbnail for ${preset.name}", thumbnail)
            assertTrue("Blank thumbnail for ${preset.name}", thumbnail!!.hasVisiblePixels())
        }
    }

    @Test
    fun visiblePixelGuardRejectsBlankButKeepsDarkImages() {
        val blank = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        assertFalse(blank.hasVisiblePixels())

        val opaqueBlack = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        opaqueBlack.eraseColor(Color.BLACK)
        assertFalse(opaqueBlack.hasVisiblePixels())

        val veryDark = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        veryDark.eraseColor(Color.rgb(2, 3, 4))
        assertTrue(veryDark.hasVisiblePixels())

        val oneHighlight = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        oneHighlight.setPixel(4, 4, Color.WHITE)
        assertTrue(oneHighlight.hasVisiblePixels())
    }

    private fun unreadablePhoto(id: Long) = PhotoItem(
        id = id,
        uri = Uri.parse("file:///fujikon_missing_photos/photo_$id.jpg"),
        displayName = "unreadable_$id.jpg",
        dateTaken = 0L,
        dateAdded = 0L,
        bucketId = "b",
        bucketName = "b",
        width = 100,
        height = 100,
        mimeType = "image/jpeg",
        size = 1L
    )

    @Test
    fun failedDecodeDoesNotPoisonTheRealPhotoCacheKey() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first()
        val store = PresetThumbnailStore(context, ImageRenderer())
        val photo = unreadablePhoto(42L)

        // 重新选照片后 URI 可能短暂不可读：内置样张仍要显示，但绝不能写进
        // 真实照片的缓存键，否则照片可读后预览图永远停留在旧样张上。
        val fallback = store.thumbnail(photo, preset, targetSize = 96)
        assertNotNull("仍应给出可见的内置样张", fallback)
        assertTrue(fallback!!.hasVisiblePixels())
        assertNull("样张回退不能占用真实照片的缓存键", store.cached(photo, preset, 96))
        assertSame(fallback, store.cached(null, preset, 96))
    }

    @Test
    fun differentPhotoKeysNeverShareACachedPreview() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first()
        val store = PresetThumbnailStore(context, ImageRenderer())
        val a = unreadablePhoto(1L)
        val b = unreadablePhoto(2L)

        val red = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        red.eraseColor(Color.RED)
        val blue = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        blue.eraseColor(Color.BLUE)
        val first = store.thumbnailFromSource("photo:1", red, preset, 96)
        val second = store.thumbnailFromSource("photo:2", blue, preset, 96)

        assertNotNull(first)
        assertNotNull(second)
        assertNotSame("不同照片不能复用同一张预览", first, second)
        assertSame(first, store.cachedFromSource("photo:1", preset, 96))
        assertSame(second, store.cachedFromSource("photo:2", preset, 96))
        assertNull("照片 a 的键不应命中样张缓存", store.cached(a, preset, 96))
        assertNull("照片 b 的键不应命中样张缓存", store.cached(b, preset, 96))
    }

    private fun realPhoto(id: Long, name: String, color: Int): PhotoItem {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return PhotoItem(
            id = id,
            uri = Uri.fromFile(file),
            displayName = name,
            dateTaken = 0L,
            dateAdded = 0L,
            bucketId = "b",
            bucketName = "b",
            width = 64,
            height = 64,
            mimeType = "image/png",
            size = file.length()
        )
    }

    @Test
    fun eachRealPhotoGetsItsOwnCachedPreview() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first()
        val store = PresetThumbnailStore(context, ImageRenderer())
        val a = realPhoto(1L, "preview_a.png", Color.RED)
        val b = realPhoto(2L, "preview_b.png", Color.BLUE)

        val first = store.thumbnail(a, preset, 96)
        val second = store.thumbnail(b, preset, 96)

        assertNotNull(first)
        assertNotNull(second)
        assertNotSame("不同照片必须各自渲染预览，不能共用一张", first, second)
        assertSame(first, store.cached(a, preset, 96))
        assertSame(second, store.cached(b, preset, 96))
    }
}
