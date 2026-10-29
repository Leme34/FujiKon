package com.fujikon.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.ui.components.ToneCurveEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToneCurveEditorTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tapAddsPointAndDoubleTapRemovesIt() {
        var points by mutableStateOf(ToneCurve.linear().normalizedPoints)
        var selected by mutableStateOf<Int?>(null)

        rule.setContent {
            ToneCurveEditor(
                points = points,
                basePoints = ToneCurve.linear().previewPoints(32),
                selectedIndex = selected,
                onPointsChange = { points = it },
                onSelectedChange = { selected = it },
                modifier = Modifier.size(240.dp).testTag("curve")
            )
        }

        rule.onNodeWithTag("curve").performTouchInput {
            down(center)
            advanceEventTime(80)
            up()
        }
        rule.waitForIdle()
        assertEquals(3, points.size)
        val added = points.first { it.x > 0.01f && it.x < 0.99f }
        assertEquals(0.5f, added.x, 0.02f)
        assertEquals(0.5f, added.y, 0.02f)

        rule.onNodeWithTag("curve").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(2, points.size)
    }

    @Test
    fun draggingAnAddedPointMovesIt() {
        var points by mutableStateOf(
            listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.5f, 0.5f),
                ToneCurvePoint(1f, 1f)
            )
        )
        var interactions = 0

        rule.setContent {
            ToneCurveEditor(
                points = points,
                basePoints = ToneCurve.linear().previewPoints(32),
                selectedIndex = 1,
                onPointsChange = { points = it },
                onSelectedChange = { },
                onInteractionFinished = { interactions++ },
                modifier = Modifier.size(240.dp).testTag("curve")
            )
        }

        rule.onNodeWithTag("curve").performTouchInput {
            down(center)
            moveTo(center + Offset(10f, -10f))
            moveTo(center + Offset(36f, -36f))
            up()
        }
        rule.waitForIdle()

        val moved = points[1]
        assertTrue("dragging right should increase input", moved.x > 0.55f)
        assertTrue("dragging up should increase output", moved.y > 0.55f)
        assertTrue("the gesture should commit once", interactions >= 1)
    }
}
