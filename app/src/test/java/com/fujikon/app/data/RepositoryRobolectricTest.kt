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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun importsDownloadedBinariesInOneBatchAndDeduplicates() = runBlocking {
        repository.load()
        val original = javaClass.classLoader!!.getResourceAsStream("nc400_match.ncp")!!.readBytes()
        val bytes = original.copyOf().apply {
            val name = "Remote Test".toByteArray(Charsets.US_ASCII)
            for (index in 0 until 20) {
                this[0x10 + index] = if (index < name.size) name[index] else 0
            }
        }

        val results = repository.importBinaries(
            listOf(
                PresetRepository.IncomingPreset(
                    key = "first",
                    displayName = "Remote Test",
                    bytes = bytes,
                    sourceName = "B&W/PICCON16.NCP"
                ),
                PresetRepository.IncomingPreset(
                    key = "second",
                    displayName = "Remote Test copy",
                    bytes = bytes.copyOf()
                ),
                PresetRepository.IncomingPreset(
                    key = "broken",
                    displayName = "Broken",
                    bytes = byteArrayOf(1, 2, 3)
                )
            )
        )

        val first = results["first"]
        assertTrue(first is PresetRepository.BinaryImportResult.Imported)
        val imported = (first as PresetRepository.BinaryImportResult.Imported).preset
        assertEquals("Remote Test", imported.name)
        assertEquals("B&W/PICCON16.NCP", imported.sourceName)
        assertEquals(6, repository.presets.value.size)
        assertTrue(repository.fileFor(imported).exists())

        assertTrue(results["second"] is PresetRepository.BinaryImportResult.Duplicate)
        val failed = results["broken"]
        assertTrue(failed is PresetRepository.BinaryImportResult.Failed)
        assertTrue((failed as PresetRepository.BinaryImportResult.Failed).message.contains("Broken"))

        val reopened = PresetRepository(context)
        reopened.load()
        val persisted = reopened.presets.value.firstOrNull { it.id == imported.id }
        assertNotNull(persisted)
        assertEquals("B&W/PICCON16.NCP", persisted!!.sourceName)
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

    @Test
    fun toneCurveSurvivesJsonRoundTrip() = runBlocking {
        repository.load()
        val base = repository.presets.value.first()
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.3f, 0.2f),
                ToneCurvePoint(0.7f, 0.85f),
                ToneCurvePoint(1f, 1f)
            )
        )

        val decoded = Preset.fromJson(base.copy(toneCurve = curve).toJson())
        assertNotNull(decoded.toneCurve)
        assertEquals(4, decoded.toneCurve!!.normalizedPoints.size)
        assertEquals(0.2f, decoded.toneCurve!!.apply(0.3f), 0.0005f)
        assertTrue(decoded.hasToneCurve)

        val legacy = base.toJson().apply { remove("toneCurve") }
        assertNull(Preset.fromJson(legacy).toneCurve)
    }

    @Test
    fun toneCurveIsAppliedAfterTheNikonLook() = runBlocking {
        repository.load()
        val base = repository.presets.value.first { it.name == "Agfa Optima" }
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.rgb(128, 128, 128))

        val inverted = base.copy(
            toneCurve = ToneCurve(
                listOf(
                    ToneCurvePoint(0f, 1f),
                    ToneCurvePoint(1f, 1f)
                )
            )
        )
        val white = ImageRenderer().render(source, inverted, forExport = false)
        assertEquals(255, (white.getPixel(0, 0) shr 16) and 0xFF)
        assertEquals(255, (white.getPixel(0, 0) shr 8) and 0xFF)
        assertEquals(255, white.getPixel(0, 0) and 0xFF)

        val crushed = base.copy(
            toneCurve = ToneCurve(
                listOf(
                    ToneCurvePoint(0f, 0f),
                    ToneCurvePoint(1f, 0f)
                )
            )
        )
        val black = ImageRenderer().render(source, crushed, forExport = false)
        assertEquals(0, (black.getPixel(0, 0) shr 16) and 0xFF)

        white.recycle()
        black.recycle()
        source.recycle()
    }

    @Test
    fun curveCopyIsPersistedAndDeleteKeepsOriginalFile() = runBlocking {
        repository.load()
        val base = repository.presets.value.first()
        val originalFile = repository.fileFor(base)
        assertTrue(originalFile.exists())
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.5f, 0.7f),
                ToneCurvePoint(1f, 1f)
            )
        )

        val saved = repository.add(
            base.copy(
                name = base.name,
                fileName = "",
                fileSize = 0,
                sourceName = "由 ${base.name} 调整",
                toneCurve = curve,
                isSample = false
            )
        )

        assertTrue(saved.id != base.id)
        assertTrue(saved.name.startsWith(base.name))
        assertTrue(saved.isDerived)
        assertTrue(saved.hasToneCurve)

        val reopened = PresetRepository(context)
        reopened.load()
        val persisted = reopened.presets.value.firstOrNull { it.id == saved.id }
        assertNotNull(persisted)
        assertEquals(curve.normalizedPoints, persisted!!.toneCurve?.normalizedPoints)

        reopened.delete(saved.id)
        assertTrue("deleting a curve copy must not delete the source NCP", originalFile.exists())
        assertTrue(reopened.presets.value.none { it.id == saved.id })
    }
}
