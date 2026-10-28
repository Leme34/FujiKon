package com.fujikon.app.render

import android.app.Application
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageIoRobolectricTest {

    private lateinit var context: Application
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = File(context.cacheDir, "source.jpg")
        javaClass.getResourceAsStream("/test_photo.jpg")!!.use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @After
    fun tearDown() {
        source.delete()
    }

    @Test
    fun previewAppliesExifOrientation() = runBlocking {
        val bitmap = BitmapLoader.decodePreview(context, Uri.fromFile(source), maxDimension = 256)
        assertNotNull(bitmap)
        assertEquals(48, bitmap!!.width)
        assertEquals(64, bitmap.height)
        bitmap.recycle()
    }

    @Test
    fun exifCopyKeepsCameraInfoAndDate() {
        val target = File(context.cacheDir, "target.jpg")
        source.copyTo(target, overwrite = true)
        val stripped = ExifInterface(target.absolutePath)
        stripped.setAttribute(ExifInterface.TAG_MAKE, null)
        stripped.saveAttributes()

        ExifWriter.copy(context, Uri.fromFile(source), target, width = 48, height = 64)

        val copied = ExifInterface(target.absolutePath)
        assertEquals("NIKON CORPORATION", copied.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("NIKON Z 6", copied.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("FujiKon 1.0", copied.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL.toString(),
            copied.getAttribute(ExifInterface.TAG_ORIENTATION)
        )
        assertEquals("48", copied.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION))
        assertEquals("64", copied.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION))

        val taken = ExifWriter.readDateTaken(context, Uri.fromFile(source))
        assertNotNull(taken)
        assertEquals("20250504_102030", ExifWriter.formatDate(taken!!))
        target.delete()
    }
}
