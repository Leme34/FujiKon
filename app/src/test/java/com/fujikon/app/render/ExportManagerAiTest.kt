package com.fujikon.app.render

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * Verifies that a finished AI image bypasses both the original-photo decode
 * and the local renderer. A deliberately broken source URI makes the two
 * branches produce different, observable outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportManagerAiTest {

    private lateinit var context: Context
    private lateinit var manager: ExportManager
    private lateinit var brokenPhoto: PhotoItem
    private val png: ByteArray = Base64.getDecoder().decode(ONE_PIXEL_PNG)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = ExportManager(context, ImageRenderer(), AppSettings(context))
        brokenPhoto = PhotoItem(
            id = 7L,
            uri = Uri.parse("file:///definitely/missing-source.jpg"),
            displayName = "missing.jpg",
            dateTaken = 0L,
            dateAdded = 0L,
            bucketId = "b",
            bucketName = "b",
            width = 1,
            height = 1,
            mimeType = "image/jpeg",
            size = 0L
        )
    }

    private fun options() = ExportManager.Options(format = "PNG", album = "Test", preserveExif = false)

    @Test
    fun withoutFinalImageTheOriginalPhotoIsDecoded() = runBlocking {
        val result = manager.export(
            photos = listOf(brokenPhoto),
            preset = Preset.none(),
            options = options(),
            onProgress = {}
        )
        assertTrue(
            "应尝试读取原图并失败: ${result.failures}",
            result.failures.any { it.contains("无法读取照片") }
        )
    }

    @Test
    fun finalImageSkipsOriginalDecodeAndLocalRenderer() = runBlocking {
        val finalFile = File(context.cacheDir, "ai_final.png").apply { writeBytes(png) }
        val result = manager.export(
            photos = listOf(brokenPhoto),
            preset = Preset.none(),
            options = options(),
            finalImageUris = mapOf(brokenPhoto.id to Uri.fromFile(finalFile)),
            onProgress = {}
        )
        assertTrue(
            "不应回退到原图解码或本地渲染: ${result.failures}",
            result.failures.none { it.contains("无法读取照片") }
        )
        finalFile.delete()
        Unit
    }

    /**
     * 1.6.28: batch export still walks the whole selection, but each photo is
     * rendered with its OWN look instead of one preset applied to every photo.
     */
    @Test
    fun perPhotoPresetsOverrideTheSharedFallback() {
        val fallback = Preset.none().copy(id = "fallback", name = "共享")
        val first = Preset.none().copy(id = "a", name = "甲")
        val second = Preset.none().copy(id = "b", name = "乙")
        val perPhoto = mapOf(1L to first, 2L to second)

        assertEquals("甲", ExportManager.presetFor(1L, fallback, perPhoto).name)
        assertEquals("乙", ExportManager.presetFor(2L, fallback, perPhoto).name)
        assertEquals("共享", ExportManager.presetFor(3L, fallback, perPhoto).name)
        assertEquals("共享", ExportManager.presetFor(1L, fallback, emptyMap()).name)
    }

    companion object {
        private const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    }
}
