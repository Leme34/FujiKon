package com.fujikon.app.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.render.ImageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM smoke tests for the Android-facing pieces: asset seeding, JSON persistence
 * and the CPU renderer. Robolectric supplies the Android runtime and assets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryRobolectricTest {

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
    fun seedsFiveSamplesOnFirstLaunch() = runBlocking {
        repository.load()

        assertTrue(repository.ready.value)
        assertEquals(5, repository.presets.value.size)
        assertTrue(repository.presets.value.any { it.name == "Agfa Optima" })
        assertTrue(repository.presets.value.any { it.name == "NC 400 MATCH" })
        assertTrue(repository.presets.value.any { it.format == "NP3" })
        assertTrue(repository.presets.value.all { it.isSample })
    }

    @Test
    fun secondLaunchDoesNotDuplicateSamples() = runBlocking {
        repository.load()
        val firstIds = repository.presets.value.map { it.id }.sorted()

        val reopened = PresetRepository(context)
        reopened.load()

        assertEquals(firstIds, reopened.presets.value.map { it.id }.sorted())
        assertEquals(5, reopened.presets.value.size)
    }

    @Test
    fun rendersNcpAndNp3AtOriginalPreviewSize() = runBlocking {
        repository.load()
        val ncp = repository.presets.value.first { it.name == "Agfa Optima" }
        val np3 = repository.presets.value.first { it.format == "NP3" && it.name.contains("CYAN", true) }

        val source = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(120, 90, 60))

        val ncpOut = ImageRenderer().render(source, ncp, forExport = false)
        assertEquals(24, ncpOut.width)
        assertEquals(16, ncpOut.height)
        assertFalse(source.isRecycled)

        val np3Out = ImageRenderer().render(ncpOut, np3, forExport = false)
        assertEquals(24, np3Out.width)
        assertEquals(16, np3Out.height)

        np3Out.recycle()
        ncpOut.recycle()
        source.recycle()
    }

    @Test
    fun exportRecyclesSourceBitmap() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first()
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)

        val output = ImageRenderer().render(source, preset, forExport = true)

        assertTrue(source.isRecycled)
        assertEquals(8, output.width)
        output.recycle()
    }

    @Test
    fun agfaCurveRaisesTheBlackPoint() = runBlocking {
        repository.load()
        val preset = repository.presets.value.first { it.name == "Agfa Optima" }
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.BLACK)

        val output = ImageRenderer().render(source, preset, forExport = false)
        val red = (output.getPixel(0, 0) shr 16) and 0xFF

        assertTrue("Agfa's curve lifts black to a small non-zero value", red > 0)
        output.recycle()
        source.recycle()
    }
}
