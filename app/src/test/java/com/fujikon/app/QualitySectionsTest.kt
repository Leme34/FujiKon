package com.fujikon.app

import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.PointColor
import com.fujikon.app.ui.components.appendBrushStroke
import com.fujikon.app.ui.components.fitImageRect
import com.fujikon.app.ui.components.hueFromArgb
import com.fujikon.app.ui.components.hueLabel
import com.fujikon.app.ui.components.lightnessFromArgb
import com.fujikon.app.ui.components.placeMask
import com.fujikon.app.ui.components.saturationFromArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QualitySectionsTest {

    @Test
    fun purePrimaryColoursMapToExpectedHues() {
        assertEquals(0f, hueFromArgb(0xFFFF0000.toInt()), 0.5f)
        assertEquals(120f, hueFromArgb(0xFF00FF00.toInt()), 0.5f)
        assertEquals(240f, hueFromArgb(0xFF0000FF.toInt()), 0.5f)
        assertEquals(60f, hueFromArgb(0xFFFFFF00.toInt()), 0.5f)
        assertEquals(180f, hueFromArgb(0xFF00FFFF.toInt()), 0.5f)
        assertEquals(300f, hueFromArgb(0xFFFF00FF.toInt()), 0.5f)
    }

    @Test
    fun greyAndWhiteHaveNoSaturation() {
        assertEquals(0f, saturationFromArgb(0xFF808080.toInt()), 0.001f)
        assertEquals(0f, saturationFromArgb(0xFFFFFFFF.toInt()), 0.001f)
        assertEquals(1f, saturationFromArgb(0xFFFF0000.toInt()), 0.001f)
        assertEquals(0.5f, lightnessFromArgb(0xFFFF0000.toInt()), 0.001f)
        assertEquals(1f, lightnessFromArgb(0xFFFFFFFF.toInt()), 0.001f)
        assertEquals(0f, lightnessFromArgb(0xFF000000.toInt()), 0.001f)
    }

    @Test
    fun hueLabelNamesColourFamiliesAndWraps() {
        assertEquals("红色", hueLabel(0f))
        assertEquals("绿色", hueLabel(120f))
        assertEquals("天蓝", hueLabel(210f))
        assertEquals("蓝色", hueLabel(240f))
        assertEquals("红色", hueLabel(360f))
        assertEquals("红色", hueLabel(-10f))
    }

    @Test
    fun pointColorOnlyTakesEffectOnceAnOffsetIsSet() {
        assertTrue("a freshly picked colour must not alter the image", PointColor().isNeutral())
        assertTrue(
            "changing only the target hue must not alter the image",
            PointColor(targetHue = 33f).isNeutral()
        )
        assertFalse(
            "the one-tap demo must actually activate the operator",
            PointColor(saturation = 32f, luminance = 14f).isNeutral()
        )
    }

    @Test
    fun localLayerNeedsNonNeutralParamsToBeActive() {
        val neutral = LocalLayer(id = "a", params = LocalParams.Neutral)
        val visible = LocalLayer(id = "b", params = LocalParams(brightness = 25f))
        val disabled = visible.copy(enabled = false)

        assertFalse(neutral.active())
        assertTrue("a new layer with a default effect renders immediately", visible.active())
        assertFalse(disabled.active())

        val all = LocalAdjustments(listOf(neutral, visible, disabled))
        assertEquals(1, all.activeLayers().size)
        assertFalse(all.isNeutral())
        assertTrue(LocalAdjustments.Neutral.isNeutral())
    }

    @Test
    fun fitImageRectCentresThePhotoAndRejectsLetterboxTaps() {
        // 100x100 photo inside a 200x100 view => 100x100 image centred at x=50.
        val r = fitImageRect(200f, 100f, 100, 100)
        assertEquals(50f, r.left, 0.001f)
        assertEquals(0f, r.top, 0.001f)
        assertEquals(100f, r.width, 0.001f)
        assertEquals(100f, r.height, 0.001f)
        val centre = r.normalized(100f, 50f)
        assertEquals(0.5f, centre!!.first, 0.001f)
        assertEquals(0.5f, centre.second, 0.001f)
        assertEquals(null, r.normalized(10f, 50f))
        assertEquals(null, r.normalized(100f, -1f))
    }

    @Test
    fun placeMaskMovesAnchorsButLeavesBrushUntouched() {
        val p = placeMask(LocalMask.ControlPoint(), 0.2f, 0.8f) as LocalMask.ControlPoint
        assertEquals(0.2f, p.x, 0.001f)
        assertEquals(0.8f, p.y, 0.001f)
        val linear = placeMask(LocalMask.LinearGradient(), 0.3f, 0.9f) as LocalMask.LinearGradient
        assertEquals(0.3f, linear.startX, 0.001f)
        assertEquals(0.3f, linear.endX, 0.001f)
        val brush = LocalMask.Brush()
        assertTrue("点按不能改写画笔笔迹，改由拖动收集", placeMask(brush, 0.4f, 0.4f) === brush)
    }

    @Test
    fun appendBrushStrokeCapsStrokesAndIgnoresNonBrushMasks() {
        var m: LocalMask = LocalMask.Brush()
        repeat(30) { m = appendBrushStroke(m, listOf(0.1f, 0.1f, 0.2f, 0.2f)) }
        assertEquals(24, (m as LocalMask.Brush).strokes.size)
        val point = LocalMask.ControlPoint()
        assertTrue(appendBrushStroke(point, listOf(0.1f, 0.1f, 0.2f, 0.2f)) === point)
    }
}
