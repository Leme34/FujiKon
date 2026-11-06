package com.fujikon.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the API key is actually persisted on the (virtual) device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun apiKeyDefaultsToEmpty() {
        val settings = AppSettings(context)
        assertEquals("", settings.agnesApiKey)
        assertFalse(settings.hasAgnesApiKey)
        assertEquals("", settings.agnesPrompt)
    }

    @Test
    fun apiKeyIsTrimmedAndPersistedAcrossInstances() {
        AppSettings(context).agnesApiKey = "  sk-abcdef123  "
        val reread = AppSettings(context)
        assertEquals("sk-abcdef123", reread.agnesApiKey)
        assertTrue(reread.hasAgnesApiKey)
    }

    @Test
    fun clearingKeyResetsFlag() {
        val settings = AppSettings(context)
        settings.agnesApiKey = "sk-abc"
        settings.agnesApiKey = "   "
        assertFalse(settings.hasAgnesApiKey)
        assertEquals("", settings.agnesApiKey)
    }

    @Test
    fun promptIsPersistedAndCapped() {
        val settings = AppSettings(context)
        settings.agnesPrompt = "  把天空改成星空  "
        assertEquals("把天空改成星空", AppSettings(context).agnesPrompt)
        settings.agnesPrompt = "x".repeat(900)
        assertEquals(500, AppSettings(context).agnesPrompt.length)
    }

    @Test
    fun existingSettingsAreUnaffected() {
        val settings = AppSettings(context)
        settings.albumName = "MyAlbum"
        settings.exportFormat = "PNG"
        settings.preserveExif = false
        settings.hasSeenCompareHint = true
        val reread = AppSettings(context)
        assertEquals("MyAlbum", reread.albumName)
        assertEquals("PNG", reread.exportFormat)
        assertFalse(reread.preserveExif)
        assertTrue(reread.hasSeenCompareHint)
    }
}
