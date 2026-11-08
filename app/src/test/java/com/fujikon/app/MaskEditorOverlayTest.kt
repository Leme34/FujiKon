package com.fujikon.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.LocalMask
import com.fujikon.app.ui.components.MaskEditorOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mask editor is a standalone FULL-SCREEN page. These tests lock in the
 * fix for "the operation area above the mask feature is too small": the photo
 * must occupy most of the screen, all mask operations happen on it, and the
 * numeric parameters live in a drawer that is collapsed by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MaskEditorOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    private fun solidBitmap(argb: Int): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private fun setEditor(
        onPreview: (EditAdjustments, String?) -> Unit = { _, _ -> },
        onApply: (EditAdjustments) -> Unit = {},
        onCancel: () -> Unit = {}
    ) {
        val bmp = solidBitmap(0xFF336699.toInt())
        rule.setContent {
            MaskEditorOverlay(
                initialAdjustments = EditAdjustments.Neutral,
                sourceBitmap = bmp,
                previewBitmap = bmp,
                rendering = false,
                onPreview = onPreview,
                onApply = onApply,
                onCancel = onCancel
            )
        }
    }

    @Test
    fun photoFillsMostOfTheFullScreenEditor() {
        setEditor()
        val root = rule.onNodeWithTag("mask_editor_root").fetchSemanticsNode().boundsInRoot
        val host = rule.onNodeWithTag("mask_editor_preview_host").fetchSemanticsNode().boundsInRoot
        // The preview must dominate the page — this is the whole point of
        // moving masking out of the cramped 44% half-screen overlay.
        assertTrue(
            "全屏页预览区应占屏幕一半以上：host=${host.height} root=${root.height}",
            host.height >= root.height * 0.5f
        )
        rule.onNodeWithTag("mask_preview_area").assertExists()
        rule.onNodeWithTag("mask_preview_canvas").assertExists()
        rule.onNodeWithTag("mask_toolbar").assertExists()
        listOf("POINT", "LINEAR", "RADIAL", "BRUSH").forEach {
            rule.onNodeWithTag("mask_kind_$it").assertExists()
        }
        rule.onNodeWithTag("mask_add_layer").assertExists()
    }

    @Test
    fun parametersDrawerIsCollapsedByDefaultAndOpensOnTap() {
        setEditor()
        rule.onNodeWithTag("mask_params_toggle").assertExists()
        rule.onNodeWithTag("mask_params_panel").assertDoesNotExist()
        rule.onNodeWithTag("mask_params_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mask_params_panel").assertExists()
        rule.onNodeWithText("收起参数").assertExists()
        // The empty-state hint appears in BOTH the toggle row and the drawer;
        // assert the drawer's own parameter help instead of a bare count.
        rule.onNodeWithText("形状、位置、图层和画笔都在全屏预览大图上操作", substring = true).assertExists()
    }

    @Test
    fun addingLayerAndPlacingMaskHappensOnTheFullScreenPhoto() {
        var last: EditAdjustments? = null
        setEditor(onPreview = { adjustments, _ -> last = adjustments })

        rule.onNodeWithTag("mask_add_layer").performClick()
        rule.waitForIdle()
        assertEquals("点 + 添加 应在全屏预览图上建立蒙版图层", 1, last?.local?.layers?.size)

        // Tap off-centre: the toolbar below the photo must not swallow it.
        rule.onNodeWithTag("mask_preview_canvas").performTouchInput {
            down(center + Offset(30f, 0f)); up()
        }
        rule.waitForIdle()
        val mask = last?.local?.layers?.firstOrNull()?.mask
        assertTrue("点按后应保留控制点类型: $mask", mask is LocalMask.ControlPoint)
        assertTrue(
            "点按应把选区写到手指位置（x 偏离默认 0.5）: $mask",
            mask is LocalMask.ControlPoint && kotlin.math.abs(mask.x - 0.5f) > 0.01f
        )
    }

    @Test
    fun doneAndCloseFireTheirCallbacks() {
        var applied = 0
        var cancelled = 0
        setEditor(onApply = { applied++ }, onCancel = { cancelled++ })
        rule.onNodeWithTag("mask_editor_done").performClick()
        rule.waitForIdle()
        assertEquals("完成应回调 onApply", 1, applied)
        rule.onNodeWithTag("mask_editor_close").performClick()
        rule.waitForIdle()
        assertEquals("关闭应回调 onCancel", 1, cancelled)
    }

    @Test
    fun fullScreenPageHasNoHalfScreenOverlayTag() {
        setEditor()
        // The old cramped in-panel overlay must never appear inside the
        // standalone editor.
        rule.onNodeWithTag("local_mask_overlay").assertDoesNotExist()
        rule.onNodeWithTag("adjustments_panel").assertDoesNotExist()
    }
}
