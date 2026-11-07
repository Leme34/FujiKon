package com.fujikon.app

import com.fujikon.app.data.HslAdjustments
import com.fujikon.app.data.HslBand
import com.fujikon.app.data.PointColor
import com.fujikon.app.ui.components.HslTarget
import com.fujikon.app.ui.components.fitBitmapPoint
import com.fujikon.app.ui.components.hueFromArgb
import com.fujikon.app.ui.components.hueLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HslPageTest {

    @Test
    fun fitBitmapPointCentreOfLetterboxedImageMapsToCentrePixel() {
        // 100x100 view, 100x50 image -> fit = 1, vertical bars top/bottom 25px.
        val point = fitBitmapPoint(50f, 50f, 100, 100, 100, 50)
        assertNotNull(point)
        assertEquals(50, point!!.first)
        assertEquals(25, point.second)
    }

    @Test
    fun fitBitmapPointInLetterboxReturnsNull() {
        assertNull(fitBitmapPoint(50f, 10f, 100, 100, 100, 50))
        assertNull(fitBitmapPoint(50f, 90f, 100, 100, 100, 50))
    }

    @Test
    fun fitBitmapPointHonoursFitScaleAndLeftBars() {
        // 200x100 view, square image -> fit = 1, horizontal bars left/right 50px.
        assertEquals(0 to 0, fitBitmapPoint(50f, 0f, 200, 100, 100, 100))
        assertEquals(99 to 99, fitBitmapPoint(149f, 99f, 200, 100, 100, 100))
        assertNull(fitBitmapPoint(20f, 50f, 200, 100, 100, 100))
    }

    @Test
    fun fitBitmapPointScalesDownWhenImageViewIsLarger() {
        assertEquals(50 to 50, fitBitmapPoint(150f, 150f, 300, 300, 100, 100))
        assertNull(fitBitmapPoint(-1f, 150f, 300, 300, 100, 100))
    }

    @Test
    fun fitBitmapPointRejectsDegenerateSizes() {
        assertNull(fitBitmapPoint(0f, 0f, 0, 100, 10, 10))
        assertNull(fitBitmapPoint(0f, 0f, 100, 100, 0, 10))
        assertNull(fitBitmapPoint(0f, 0f, 100, 100, 10, 0))
    }

    @Test
    fun bandAndPickedTargetsAreDistinct() {
        val band: HslTarget = HslTarget.Band(3)
        val picked: HslTarget = HslTarget.Picked
        assertTrue(band is HslTarget.Band)
        assertEquals(3, (band as HslTarget.Band).index)
        assertTrue(picked is HslTarget.Picked)
        assertTrue(band != picked)
    }

    @Test
    fun samplingAColourAloneDoesNotChangeTheImage() {
        // Picking only sets targetHue; the three HSL-style offsets stay zero
        // until the user moves a slider, so the render is unchanged.
        val picked = PointColor.Neutral.copy(targetHue = 33f)
        assertTrue(picked.isNeutral())
        assertEquals(33f, picked.targetHue, 0.001f)
    }

    @Test
    fun pickedColourSlidersWriteOnlyThePickedColour() {
        val pc = PointColor.Neutral.copy(targetHue = 210f)
        val adjusted = pc.copy(hueShift = 20f, saturation = -15f, luminance = 8f)
        assertTrue(adjusted.hueShift == 20f && adjusted.saturation == -15f && adjusted.luminance == 8f)
        assertEquals(210f, adjusted.targetHue, 0.001f)
        assertTrue(adjusted.isNeutral().not())
        assertEquals("天蓝", hueLabel(adjusted.targetHue))
    }

    @Test
    fun classicHslBandEditStaysIsolatedToItsBand() {
        val edited = HslAdjustments.Neutral.withBand(2, HslBand(hue = 25f, saturation = -10f))
        assertEquals(25f, edited.band(2).hue, 0.001f)
        assertEquals(-10f, edited.band(2).saturation, 0.001f)
        assertTrue(edited.band(0).isNeutral())
        assertTrue(edited.band(7).isNeutral())
        assertEquals(0f, edited.band(2).luminance, 0.001f)
    }

    @Test
    fun hueFromArgbMatchesTheColourFamilyShownInThePicker() {
        val red = 0xFFFF0000.toInt()
        assertEquals(0f, hueFromArgb(red), 0.5f)
        assertEquals("红色", hueLabel(hueFromArgb(red)))
        val blue = 0xFF0000FF.toInt()
        assertEquals(240f, hueFromArgb(blue), 0.5f)
        assertEquals("蓝色", hueLabel(hueFromArgb(blue)))
    }
}
