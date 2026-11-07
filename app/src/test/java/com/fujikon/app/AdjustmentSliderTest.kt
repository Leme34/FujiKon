package com.fujikon.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.components.AdjustmentSlider
import com.fujikon.app.ui.components.stepValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdjustmentSliderTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun draggingChangesValue() {
        var value by mutableStateOf(0f)

        rule.setContent {
            AdjustmentSlider(
                label = "曝光度",
                value = value,
                valueRange = -5f..5f,
                step = 0.1f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                modifier = Modifier.size(300.dp, 72.dp).testTag("exposure")
            )
        }

        rule.onNodeWithTag("exposure").performTouchInput {
            down(center)
            moveTo(center + Offset(70f, 0f))
            moveTo(center + Offset(90f, 0f))
            up()
        }
        rule.waitForIdle()

        assertTrue("dragging right should raise exposure", value > 1f)
    }

    @Test
    fun draggingCommitsExactlyOnceOnRelease() {
        var value by mutableStateOf(0f)
        var changes = 0
        var commits = 0

        rule.setContent {
            AdjustmentSlider(
                label = "曝光度",
                value = value,
                valueRange = -5f..5f,
                step = 0.1f,
                onValueChange = { value = it; changes++ },
                onValueChangeFinished = { commits++ },
                modifier = Modifier.size(300.dp, 72.dp).testTag("release")
            )
        }

        rule.onNodeWithTag("release").performTouchInput {
            down(center)
            moveTo(center + Offset(30f, 0f))
            moveTo(center + Offset(60f, 0f))
            moveTo(center + Offset(90f, 0f))
            up()
        }
        rule.waitForIdle()

        assertTrue("a drag should publish several intermediate values", changes >= 2)
        assertEquals("the render/commit must happen once, on release", 1, commits)
    }

    @Test
    fun verticalDragDoesNotChangeValue() {
        var value by mutableStateOf(0f)

        rule.setContent {
            AdjustmentSlider(
                label = "对比度",
                value = value,
                valueRange = -100f..100f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                modifier = Modifier.size(300.dp, 72.dp).testTag("vertical")
            )
        }

        rule.onNodeWithTag("vertical").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 80f))
            moveTo(center + Offset(4f, 90f))
            up()
        }
        rule.waitForIdle()

        assertEquals("a vertical scroll gesture must not edit the slider", 0f, value, 0.001f)
    }

    @Test
    fun diagonalSwipeDoesNotChangeValue() {
        var value by mutableStateOf(0f)

        rule.setContent {
            AdjustmentSlider(
                label = "高光",
                value = value,
                valueRange = -100f..100f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                modifier = Modifier.size(300.dp, 72.dp).testTag("diagonal")
            )
        }

        rule.onNodeWithTag("diagonal").performTouchInput {
            down(center)
            moveTo(center + Offset(30f, -70f))
            moveTo(center + Offset(40f, -110f))
            up()
        }
        rule.waitForIdle()

        assertEquals("a diagonal scroll swipe must not edit the slider", 0f, value, 0.001f)
    }

    @Test
    fun singleTapOnTrackDoesNotChangeValue() {
        var value by mutableStateOf(0f)

        rule.setContent {
            AdjustmentSlider(
                label = "阴影",
                value = value,
                valueRange = -100f..100f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                modifier = Modifier.size(300.dp, 72.dp).testTag("tap")
            )
        }

        rule.onNodeWithTag("tap").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()

        assertEquals("a stray tap on the track must not edit the slider", 0f, value, 0.001f)
    }

    @Test
    fun doubleTapResetsTheSlider() {
        var value by mutableStateOf(40f)
        var resets = 0

        rule.setContent {
            AdjustmentSlider(
                label = "对比度",
                value = value,
                valueRange = -100f..100f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                onReset = {
                    resets++
                    value = 0f
                },
                modifier = Modifier.size(300.dp, 72.dp).testTag("contrast")
            )
        }

        rule.onNodeWithTag("contrast").performTouchInput { doubleClick(center) }
        rule.waitForIdle()

        assertEquals(1, resets)
        assertEquals(0f, value, 0.001f)
    }

    @Test
    fun plusButtonNudgesValueForward() {
        var value by mutableStateOf(0f)

        rule.setContent {
            AdjustmentSlider(
                label = "曝光度",
                value = value,
                valueRange = -5f..5f,
                step = 0.1f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                modifier = Modifier.size(300.dp, 72.dp)
            )
        }

        rule.onNodeWithTag("adjust_step_plus_曝光度").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()

        assertTrue("the + button must raise the value", value > 0f)
    }

    @Test
    fun minusButtonNudgesValueBackward() {
        var value by mutableStateOf(0f)

        rule.setContent {
            AdjustmentSlider(
                label = "高光",
                value = value,
                valueRange = -100f..100f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                modifier = Modifier.size(300.dp, 72.dp)
            )
        }

        rule.onNodeWithTag("adjust_step_minus_高光").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()

        assertTrue("the - button must lower the value", value < 0f)
    }

    @Test
    fun plusButtonNeverExceedsRangeEnd() {
        var value by mutableStateOf(5f)
        var commits by mutableIntStateOf(0)

        rule.setContent {
            AdjustmentSlider(
                label = "曝光度",
                value = value,
                valueRange = -5f..5f,
                step = 0.1f,
                onValueChange = { value = it },
                onValueChangeFinished = { commits++ },
                modifier = Modifier.size(300.dp, 72.dp)
            )
        }

        rule.onNodeWithTag("adjust_step_plus_曝光度").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()

        assertEquals("a nudge at the upper bound must clamp", 5f, value, 0.0001f)
    }

    @Test
    fun stepValueAdvancesOneStepAndClamps() {
        assertEquals(0.1f, stepValue(0f, 1, -5f..5f, 0.1f), 0.0001f)
        assertEquals(-0.1f, stepValue(0f, -1, -5f..5f, 0.1f), 0.0001f)
        assertEquals(5f, stepValue(5f, 1, -5f..5f, 0.1f), 0.0001f)
        assertEquals(-5f, stepValue(-5f, -1, -5f..5f, 0.1f), 0.0001f)
        // 0..100 style sliders must not go below zero.
        assertEquals(0f, stepValue(0f, -1, 0f..100f, 1f), 0.0001f)
    }
}
