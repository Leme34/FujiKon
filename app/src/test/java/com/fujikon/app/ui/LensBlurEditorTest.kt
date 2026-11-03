package com.fujikon.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fujikon.app.data.LensBlur
import com.fujikon.app.ui.components.LensBlurEditorOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LensBlurEditorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun applyPassesTheDraftBack() {
        var applied: LensBlur? = null

        composeRule.setContent {
            LensBlurEditorOverlay(
                bitmap = grayBitmap(),
                initial = LensBlur(amount = 40f, focusX = 0.3f, focusY = 0.7f),
                onPreview = {},
                onApply = { applied = it },
                onCancel = {}
            )
        }

        composeRule.onNodeWithText("完成").performClick()

        assertEquals(40f, applied!!.amount, 0.001f)
        assertEquals(0.3f, applied!!.focusX, 0.001f)
        assertEquals(0.7f, applied!!.focusY, 0.001f)
    }

    @Test
    fun cancelDoesNotApplyTheDraft() {
        var applied: LensBlur? = null
        var cancelled = false

        composeRule.setContent {
            LensBlurEditorOverlay(
                bitmap = grayBitmap(),
                initial = LensBlur(amount = 40f),
                onPreview = {},
                onApply = { applied = it },
                onCancel = { cancelled = true }
            )
        }

        composeRule.onNodeWithContentDescription("取消镜头模糊").performClick()

        assertEquals(null, applied)
        assertTrue(cancelled)
    }

    @Test
    fun controlsScrollIntoViewWhenTheScreenIsShort() {
        composeRule.setContent {
            LensBlurEditorOverlay(
                bitmap = grayBitmap(),
                initial = LensBlur(amount = 40f),
                onPreview = {},
                onApply = {},
                onCancel = {}
            )
        }

        composeRule.onNodeWithText("散景高光").performScrollTo().assertIsDisplayed()
    }

    /** Every control must be on screen without scrolling on a normal phone. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun allControlsFitOnAPhoneSizedScreen() {
        composeRule.setContent {
            LensBlurEditorOverlay(
                bitmap = grayBitmap(),
                initial = LensBlur(amount = 40f),
                onPreview = {},
                onApply = {},
                onCancel = {}
            )
        }

        listOf("模糊强度", "聚焦范围", "边缘过渡", "散景高光").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    private fun grayBitmap(): Bitmap =
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
}
