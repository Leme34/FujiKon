package com.fujikon.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fujikon.app.data.AppSettings
import com.fujikon.app.ui.components.EditorSwipeHintCard
import com.fujikon.app.ui.components.SWIPE_HINT_BODY
import com.fujikon.app.ui.components.SWIPE_HINT_DISMISS
import com.fujikon.app.ui.components.SWIPE_HINT_TITLE
import com.fujikon.app.ui.components.shouldShowSwipeHint
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Before

/**
 * 1.6.31 one-shot "bottom tools can be swiped" hint:
 *
 * - The card is shown only on the FIRST entry, i.e. while the persisted
 *   "seen" flag is false AND a photo is actually rendered (never over an
 *   empty loading / error screen).
 * - After the user taps 「知道了」 the flag is persisted, so
 *   [shouldShowSwipeHint] is false forever — no later photo, no re-entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorSwipeHintTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun shownOnlyWhenNotSeenAndAPhotoIsRendered() {
        assertTrue(shouldShowSwipeHint(seenSwipeHint = false, hasRenderedPhoto = true))
        assertFalse("已点知道：不再显示", shouldShowSwipeHint(true, true))
        assertFalse("无预览图：不显示", shouldShowSwipeHint(false, false))
        assertFalse(shouldShowSwipeHint(true, false))
    }

    @Test
    fun onceSeenItNeverReturnsRegardlessOfPhoto() {
        assertFalse(shouldShowSwipeHint(seenSwipeHint = true, hasRenderedPhoto = true))
        assertFalse(shouldShowSwipeHint(seenSwipeHint = true, hasRenderedPhoto = false))
    }

    @Test
    fun cardShowsTitleBodyAndDismissButton() {
        rule.setContent {
            FujiKonTheme { EditorSwipeHintCard(onDismiss = {}) }
        }
        rule.onNodeWithTag("editor_swipe_hint").assertExists()
        rule.onNodeWithTag("editor_swipe_hint_title").assertExists()
        rule.onNodeWithTag("editor_swipe_hint_body").assertExists()
        rule.onNodeWithTag("editor_swipe_hint_dismiss").assertExists()
        rule.onNodeWithText(SWIPE_HINT_TITLE).assertExists()
        rule.onNodeWithText(SWIPE_HINT_BODY).assertExists()
        rule.onNodeWithText(SWIPE_HINT_DISMISS).assertExists()
    }

    @Test
    fun tappingDismissFiresCallbackExactlyOnce() {
        var dismissed = 0
        rule.setContent {
            FujiKonTheme { EditorSwipeHintCard(onDismiss = { dismissed++ }) }
        }
        rule.onNodeWithTag("editor_swipe_hint_dismiss").performClick()
        assertEquals(1, dismissed)
    }

    /**
     * Full "first entry only" loop, exercised without a ViewModel (so no
     * coroutine / Dispatchers.Main is involved): the persisted flag + the
     * show rule together guarantee the hint appears once and never returns.
     * The ViewModel's only job is to expose the flag in UiState and persist it
     * on dismiss — both covered at compile time and by AppSettingsTest.
     */
    @Test
    fun firstEntryShowsThenDismissPersistsForever() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = AppSettings(context)

        // First entry: flag not seen, photo rendered -> hint visible.
        assertFalse(settings.hasSeenSwipeHint)
        assertTrue(shouldShowSwipeHint(settings.hasSeenSwipeHint, hasRenderedPhoto = true))

        // User taps 「知道了」 -> flag persisted.
        settings.hasSeenSwipeHint = true

        // Re-entering / restart: a fresh settings instance still reads seen,
        // so the hint is suppressed from the very first frame, forever.
        val reentered = AppSettings(context)
        assertTrue(reentered.hasSeenSwipeHint)
        assertFalse(shouldShowSwipeHint(reentered.hasSeenSwipeHint, hasRenderedPhoto = true))
        assertFalse(shouldShowSwipeHint(reentered.hasSeenSwipeHint, hasRenderedPhoto = false))
    }
}
