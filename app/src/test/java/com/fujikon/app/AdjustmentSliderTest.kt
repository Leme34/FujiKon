package com.fujikon.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
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
                modifier = Modifier.size(240.dp, 48.dp).testTag("exposure")
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
    fun verticalDragDoesNotChangeValue() {
        var value by mutableStateOf(0f)

        rule.setContent {
            AdjustmentSlider(
                label = "对比度",
                value = value,
                valueRange = -100f..100f,
                onValueChange = { value = it },
                onValueChangeFinished = { },
                modifier = Modifier.size(240.dp, 48.dp).testTag("vertical")
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
                modifier = Modifier.size(240.dp, 48.dp).testTag("contrast")
            )
        }

        rule.onNodeWithTag("contrast").performTouchInput { doubleClick(center) }
        rule.waitForIdle()

        assertEquals(1, resets)
        assertEquals(0f, value, 0.001f)
    }
}
