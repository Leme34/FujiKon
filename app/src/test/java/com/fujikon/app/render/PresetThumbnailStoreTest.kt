package com.fujikon.app.render

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fujikon.app.data.PresetRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
}
