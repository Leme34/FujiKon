package com.fujikon.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fujikon.app.data.CropAspect
import com.fujikon.app.data.EditCrop
import com.fujikon.app.ui.components.CropEditorOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CropEditorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun applyingASquareAspectPassesTheCropBack() {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
        }
        var applied: EditCrop? = null

        composeRule.setContent {
            CropEditorOverlay(
                bitmap = bitmap,
                initial = EditCrop.Full,
                onApply = { applied = it },
                onCancel = {}
            )
        }

        composeRule.onNodeWithText("1:1").performClick()
        composeRule.onNodeWithText("完成").performClick()

        val crop = applied
        assertNotNull(crop)
        assertEquals(CropAspect.SQUARE.id, crop!!.aspect)
        val rect = crop.customRect
        assertNotNull(rect)
        val pixelAspect = (rect!!.width * 40f) / (rect.height * 30f)
        assertEquals(1f, pixelAspect, 0.01f)
    }

    @Test
    fun cancelDoesNotApplyTheDraft() {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
        }
        var applied: EditCrop? = null
        var cancelled = false

        composeRule.setContent {
            CropEditorOverlay(
                bitmap = bitmap,
                initial = EditCrop.Full,
                onApply = { applied = it },
                onCancel = { cancelled = true }
            )
        }

        composeRule.onNodeWithText("重置").performClick()
        composeRule.onNodeWithContentDescription("取消裁剪").performClick()

        assertEquals(null, applied)
        assertEquals(true, cancelled)
    }
}
