package com.fujikon.app

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.HslAdjustments
import com.fujikon.app.data.PointColor
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.ui.components.AdjustmentsSheet
import com.fujikon.app.ui.components.HslPage
import com.fujikon.app.ui.components.HslTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the three interaction fixes: the panel must not be
 * dismissible by a downward swipe, features live on separate tab pages, and
 * the merged HSL picker samples a colour directly from the preview image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdjustmentsPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private fun solidBitmap(argb: Int): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private fun setPanel(
        source: Bitmap? = null,
        onDismiss: () -> Unit = {},
        onPreview: (EditAdjustments, String?) -> Unit = { _, _ -> }
    ) {
        rule.setContent {
            AdjustmentsSheet(
                presetName = "测试预设",
                initialAdjustments = EditAdjustments.Neutral,
                initialToneCurve = ToneCurve.linear(),
                presetToneCurve = ToneCurve.linear(),
                baseCurve = emptyList(),
                rendering = false,
                saving = false,
                onPreview = onPreview,
                onToneCurvePreview = {},
                onClearToneCurve = {},
                onClearAllCurves = {},
                onSave = {},
                onDismiss = onDismiss,
                sourceBitmap = source,
                previewBitmap = source
            )
        }
    }

    @Test
    fun swipingDownNeverDismissesThePanel() {
        var dismissals by mutableIntStateOf(0)
        var previews by mutableIntStateOf(0)
        setPanel(onDismiss = { dismissals++ }, onPreview = { _, _ -> previews++ })

        rule.onNodeWithTag("adjustments_panel").assertExists()
        // A long downward drag used to collapse / dismiss the bottom sheet.
        rule.onNodeWithTag("adjustments_panel").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 380f))
            up()
        }
        assertEquals("下滑手势不得关闭调节界面", 0, dismissals)
        assertEquals("下滑手势不得触发渲染或改参数", 0, previews)
        rule.onNodeWithTag("adjustments_panel").assertExists()
    }

    @Test
    fun eachFeatureHasItsOwnTabPage() {
        setPanel()
        listOf("LIGHT", "COLOR", "DETAIL", "QUALITY", "HSL", "PORTRAIT", "TONE", "FILTER", "LOCAL")
            .forEach { rule.onNodeWithTag("adjust_tab_$it").assertExists() }

        // HSL page holds the merged picker button.
        rule.onNodeWithTag("adjust_tab_HSL").performClick()
        rule.onNodeWithTag("hsl_pick_color_button").assertExists()

        // Switching away removes the HSL controls: pages are independent.
        rule.onNodeWithTag("adjust_tab_LIGHT").performClick()
        rule.onNodeWithTag("hsl_pick_color_button").assertDoesNotExist()
    }

    @Test
    fun pointColourIsMergedIntoHslNotAStandaloneSection() {
        setPanel(source = solidBitmap(0xFFFF0000.toInt()))
        rule.onNodeWithTag("adjust_tab_HSL").performClick()
        // Old standalone point-colour section is gone.
        rule.onNodeWithTag("point_color_picker").assertDoesNotExist()
        // The merged control is on the HSL page.
        rule.onNodeWithTag("hsl_pick_color_button").assertExists()
    }

    @Test
    fun hslPickButtonInvokesItsCallback() {
        var requests = 0
        rule.setContent {
            HslPage(
                hsl = HslAdjustments.Neutral,
                pointColor = PointColor.Neutral,
                target = HslTarget.Band(0),
                onTargetChange = {},
                picking = false,
                onRequestPick = { requests++ },
                onCancelPick = {},
                onBandChange = { _, _ -> },
                onPointColorChange = {},
                onFinished = {},
                enabled = true
            )
        }
        rule.onNodeWithTag("hsl_pick_color_button").performClick()
        assertEquals("点选色按钮必须触发进入取色模式", 1, requests)
    }

    @Test
    fun pickingAColourFromThePreviewReturnsToHslWithTheSampledHue() {
        val red = solidBitmap(0xFFFF0000.toInt())
        var lastAdjustments: EditAdjustments? = null
        setPanel(source = red, onPreview = { adjustments, _ -> lastAdjustments = adjustments })

        rule.onNodeWithTag("adjust_tab_HSL").performClick()
        rule.waitForIdle()
        val button = rule.onNodeWithTag("hsl_pick_color_button")
        button.performScrollTo()
        rule.waitForIdle()
        button.performClick()
        rule.waitForIdle()
        // The preview image itself becomes the picker in the upper half.
        rule.onNodeWithTag("hsl_color_pick_layer").assertExists()
        rule.onNodeWithTag("hsl_color_pick_target").assertExists()

        rule.onNodeWithTag("hsl_color_pick_target").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()

        // Picker closes, we are back on the HSL page, and the sampled hue
        // (pure red = 0 degrees) is stored on the point-colour target.
        rule.onNodeWithTag("hsl_color_pick_target").assertDoesNotExist()
        rule.onNodeWithTag("hsl_pick_color_button").assertExists()
        val hue = lastAdjustments?.pointColor?.targetHue
        assertTrue("取色结果应回写到 HSL 目标色", hue != null)
        assertTrue("红色取样应得到 0° 附近的色相，实际 $hue", hue!! < 1f || hue > 359f)
        // Sampling alone must not alter the image (offsets remain neutral).
        assertTrue(lastAdjustments!!.pointColor.isNeutral())
        assertTrue(lastAdjustments!!.isNeutral())
    }

    @Test
    fun closeButtonIsTheExplicitWayToLeave() {
        var dismissals by mutableIntStateOf(0)
        setPanel(onDismiss = { dismissals++ })
        rule.onNodeWithTag("adjustments_close").performClick()
        assertEquals(1, dismissals)
    }
}
