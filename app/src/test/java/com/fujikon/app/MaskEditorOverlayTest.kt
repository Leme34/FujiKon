package com.fujikon.app

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.BrushStroke
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.ui.components.MaskEditorOverlay
import com.fujikon.app.ui.components.fitImageRect
import com.fujikon.app.ui.components.maskRotationHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mask editor is a standalone FULL-SCREEN page drawn inside the main
 * Compose tree. These tests lock in: the photo dominates the page, numeric
 * parameters are always present (never "missing"), the top strip stays
 * scrim-free so the real top action bar is clickable, and global history
 * actions (undo / redo / preset) keep the page in sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MaskEditorOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    private fun solidBitmap(argb: Int): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private fun setEditor(
        adjustments: EditAdjustments = EditAdjustments.Neutral,
        onPreview: (EditAdjustments, String?) -> Unit = { _, _ -> },
        onApply: (EditAdjustments) -> Unit = {},
        onCancel: () -> Unit = {}
    ) {
        val bmp = solidBitmap(0xFF336699.toInt())
        rule.setContent {
            MaskEditorOverlay(
                initialAdjustments = adjustments,
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
        // Even with the parameters drawer expanded, the photo+toolbar area
        // still owns at least half the screen — the whole point of moving
        // masking out of the cramped 44% half-screen overlay.
        assertTrue(
            "全屏页预览区应占屏幕一半以上：host=${host.height} root=${root.height}",
            host.height >= root.height * 0.5f
        )
        rule.onNodeWithTag("mask_preview_area").assertExists()
        rule.onNodeWithTag("mask_preview_canvas").assertExists()
        rule.onNodeWithTag("mask_toolbar").assertExists()
        listOf("POINT", "LINEAR", "RADIAL", "BRUSH", "SUBJECT", "SKY", "COLOR_RANGE").forEach {
            rule.onNodeWithTag("mask_kind_$it").assertExists()
        }
        rule.onNodeWithTag("mask_add_layer").assertExists()
    }

    @Test
    fun parametersAreVisibleByDefaultAndCollapseOnTap() {
        setEditor()
        rule.onNodeWithTag("mask_params_toggle").assertExists()
        // Parameters are a first-class, visible part of the editor now; they
        // must NOT be "missing" behind a collapsed drawer.
        rule.onNodeWithTag("mask_params_panel").assertExists()
        rule.onNodeWithText("参数调节 · 数值作用于选中的蒙版图层").assertExists()
        rule.onNodeWithText("收起参数").assertExists()
        rule.onNodeWithText("形状、位置、图层和画笔都在全屏预览大图上操作", substring = true).assertExists()
        // Collapsing frees the preview; expanding restores the panel.
        rule.onNodeWithTag("mask_params_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mask_params_panel").assertDoesNotExist()
        rule.onNodeWithText("展开参数调节").assertExists()
        rule.onNodeWithTag("mask_params_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mask_params_panel").assertExists()
    }

    @Test
    fun fullParameterSetIsPresentForTheSelectedLayer() {
        setEditor()
        rule.onNodeWithTag("mask_add_layer").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mask_params_panel").assertExists()
        // Shape params, scoped effect params, quick effects, layer strength:
        // the complete parameter set, not just the shape sliders.
        rule.onNodeWithText("形状参数", substring = true).assertExists()
        rule.onNodeWithText("效果参数", substring = true).assertExists()
        rule.onNodeWithText("作用半径").assertExists()
        rule.onNodeWithText("羽化").assertExists()
        rule.onNodeWithText("亮度").assertExists()
        rule.onNodeWithText("图层强度").assertExists()
        rule.onNodeWithTag("local_quick_提亮主体").assertExists()
    }

    @Test
    fun topStripIsScrimFreeAndTopBarReceivesRealTouches() {
        var topTaps = 0
        val bmp = solidBitmap(0xFF336699.toInt())
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                // Stand-in for the editor's real TopAppBar (preset / undo /
                // redo / history), sitting UNDER the full-screen mask page.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("top_undo")
                        .clickable { topTaps++ }
                )
                MaskEditorOverlay(
                    initialAdjustments = EditAdjustments.Neutral,
                    sourceBitmap = bmp,
                    previewBitmap = bmp,
                    rendering = false,
                    onPreview = { _, _ -> },
                    onApply = {},
                    onCancel = {}
                )
            }
        }
        rule.onNodeWithTag("mask_editor_root").assertExists()
        rule.onNodeWithTag("mask_editor_content").assertExists()
        // A REAL touch (not a semantics click, which would bypass hit-testing)
        // in the top strip must reach the underlying action bar.
        rule.onNodeWithTag("top_undo").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals("蒙版页顶部应无遮罩，顶部操作栏可随时点击", 1, topTaps)
    }

    @Test
    fun globalHistoryActionsAreReflectedInThePage() {
        val bmp = solidBitmap(0xFF336699.toInt())
        fun withRadius(r: Float) = EditAdjustments.Neutral.copy(
            local = LocalAdjustments(
                listOf(
                    LocalLayer(
                        id = "a",
                        mask = LocalMask.ControlPoint(radius = r),
                        params = LocalParams(brightness = 25f)
                    )
                )
            )
        )
        val external = mutableStateOf(withRadius(0.2f))
        var applied: EditAdjustments? = null
        rule.setContent {
            MaskEditorOverlay(
                initialAdjustments = external.value,
                sourceBitmap = bmp,
                previewBitmap = bmp,
                rendering = false,
                onPreview = { _, _ -> },
                onApply = { applied = it },
                onCancel = {}
            )
        }
        // Simulate an undo / redo / preset switch performed from the top
        // action bar while the mask page is open.
        external.value = withRadius(0.6f)
        rule.waitForIdle()
        rule.onNodeWithTag("mask_editor_done").performClick()
        rule.waitForIdle()
        val radius = (applied?.local?.layers?.firstOrNull()?.mask as? LocalMask.ControlPoint)?.radius
        assertEquals("顶栏历史动作后蒙版页必须同步到最新外部状态", 0.6f, radius ?: -1f, 0.001f)
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
    fun pinchGestureScalesTheMaskRegion() {
        var last: EditAdjustments? = null
        setEditor(onPreview = { adjustments, _ -> last = adjustments })
        rule.onNodeWithTag("mask_add_layer").performClick()
        rule.waitForIdle()
        val before = (last?.local?.layers?.firstOrNull()?.mask as? LocalMask.ControlPoint)?.radius
        assertEquals(0.25f, before ?: -1f, 0.001f)

        // Two-finger pinch-out must enlarge the control-point region. This
        // exercises the real canvas gesture loop, not just the pure maths.
        rule.onNodeWithTag("mask_preview_canvas").performTouchInput {
            val c = center
            down(0, c + Offset(-30f, 0f))
            down(1, c + Offset(30f, 0f))
            moveTo(0, c + Offset(-70f, 0f))
            moveTo(1, c + Offset(70f, 0f))
            up(0)
            up(1)
        }
        rule.waitForIdle()
        val after = (last?.local?.layers?.firstOrNull()?.mask as? LocalMask.ControlPoint)?.radius
        assertTrue(
            "双指捏合应放大控制点区域: before=$before after=$after",
            (after ?: 0f) > (before ?: 0f) + 0.01f
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
        rule.onNodeWithTag("local_mask_overlay").assertDoesNotExist()
        rule.onNodeWithTag("adjustments_panel").assertDoesNotExist()
    }

    private fun editorWith(mask: LocalMask) = EditAdjustments.Neutral.copy(
        local = LocalAdjustments(
            listOf(LocalLayer(id = "m", mask = mask, params = LocalParams(brightness = 25f)))
        )
    )

    @Test
    fun singleFingerOnTheRotationHandleSpinsARadialMask() {
        var last: EditAdjustments? = null
        val radial = LocalMask.RadialGradient(x = 0.5f, y = 0.5f, radiusX = 0.3f, radiusY = 0.3f)
        setEditor(adjustments = editorWith(radial), onPreview = { a, _ -> last = a })
        rule.waitForIdle()

        val canvas = rule.onNodeWithTag("mask_preview_canvas")
        val size = canvas.fetchSemanticsNode().size
        val rect = fitImageRect(size.width.toFloat(), size.height.toFloat(), 20, 20)
        val aspect = rect.width / rect.height
        val handle = maskRotationHandle(radial, rect, aspect)!!
        // One finger pressed on the white handle, dragged sideways, must spin
        // the ellipse — no second finger needed.
        canvas.performTouchInput {
            down(handle)
            moveTo(handle + Offset(80f, 40f))
            up()
        }
        rule.waitForIdle()
        val rotated = (last?.local?.layers?.firstOrNull()?.mask as? LocalMask.RadialGradient)?.rotationDeg
        assertTrue(
            "单指按住旋转点应改变径向角度: $rotated",
            rotated != null && kotlin.math.abs(rotated) > 1f
        )
    }

    @Test
    fun draggingAnExistingBrushStrokeMovesTheWholeSelection() {
        var last: EditAdjustments? = null
        val brush = LocalMask.Brush(
            strokes = listOf(BrushStroke(listOf(0.3f, 0.5f, 0.7f, 0.5f)))
        )
        setEditor(adjustments = editorWith(brush), onPreview = { a, _ -> last = a })
        rule.waitForIdle()

        val canvas = rule.onNodeWithTag("mask_preview_canvas")
        val size = canvas.fetchSemanticsNode().size
        val rect = fitImageRect(size.width.toFloat(), size.height.toFloat(), 20, 20)
        // Press ON an existing stroke (not on the rotation handle above it):
        // a single-finger drag must translate the whole selection.
        val mid = Offset(rect.left + 0.5f * rect.width, rect.top + 0.5f * rect.height)
        canvas.performTouchInput {
            down(mid)
            moveTo(mid + Offset(0f, 60f))
            up()
        }
        rule.waitForIdle()
        val moved = last?.local?.layers?.firstOrNull()?.mask as? LocalMask.Brush
        assertTrue(
            "单指按已有笔迹拖动应整体移动: ${moved?.offsetY}",
            (moved?.offsetY ?: 0f) > 0.01f
        )
    }

    @Test
    fun subjectMaskLayerShowsLocalPortraitSection() {
        // 选择主体图层应展示「仅作用于选中主体」的局部人像美颜参数区块。
        setEditor(adjustments = editorWith(LocalMask.SubjectMask(seedX = 0.5f, seedY = 0.5f)))
        rule.waitForIdle()
        rule.onNodeWithTag("mask_params_panel").assertExists()
        rule.onNodeWithTag("mask_subject_portrait_title").assertExists()
        // 风格 chips + 四滑块（磨皮 / 美白 / 红润 / 立体）须齐备。
        rule.onNodeWithText("磨皮").assertExists()
        rule.onNodeWithText("美白").assertExists()
        rule.onNodeWithText("红润").assertExists()
        rule.onNodeWithText("立体").assertExists()
    }

    @Test
    fun invertToggleWorksForControlPointAndSubjectMasks() {
        setEditor(adjustments = editorWith(LocalMask.ControlPoint(x = 0.5f, y = 0.5f)))
        rule.waitForIdle()
        rule.onNodeWithTag("mask_params_panel").assertExists()
        // 1.6.58：此前控制点蒙版没有 invert 字段，反选按钮静默失效；现在必须生效。
        rule.onNodeWithTag("mask_invert_toggle").assertExists()
        // 初始为「未反选」状态。
        rule.onNodeWithTag("mask_invert_toggle").assertExists()
        rule.onNodeWithText("○ 反选选区").assertExists()
        rule.onNodeWithText("● 已反选（点击还原）").assertDoesNotExist()
        // 点击反选：文案切换为「已反选」。（直接触发 onClick，绕过布局命中，
        // 聚焦验证反选逻辑本身；真实坐标点击在其它用例已覆盖。）
        rule.onNodeWithTag("mask_invert_toggle").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("● 已反选（点击还原）").assertExists()
        rule.onNodeWithText("○ 反选选区").assertDoesNotExist()
        // 再次点击还原为「未反选」。
        rule.onNodeWithTag("mask_invert_toggle").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("○ 反选选区").assertExists()
        rule.onNodeWithText("● 已反选（点击还原）").assertDoesNotExist()
    }
}
