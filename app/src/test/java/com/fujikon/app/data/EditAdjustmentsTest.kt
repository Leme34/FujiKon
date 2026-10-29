package com.fujikon.app.data

import com.fujikon.app.ncp.PictureControlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditAdjustmentsTest {

    @Test
    fun neutralAdjustmentsAreDetectedAndNormalized() {
        assertTrue(EditAdjustments.Neutral.isNeutral())

        val adjusted = EditAdjustments(exposure = 0.4f, clarity = 20f)
        assertFalse(adjusted.isNeutral())

        val clamped = EditAdjustments(
            temperature = 220f,
            exposure = -9f,
            texture = -180f
        ).normalized()
        assertEquals(100f, clamped.temperature, 0.001f)
        assertEquals(-5f, clamped.exposure, 0.001f)
        assertEquals(-100f, clamped.texture, 0.001f)
    }

    @Test
    fun jsonRoundTripKeepsEverySlider() {
        val source = EditAdjustments(
            temperature = 12f,
            tint = -8f,
            vibrance = 24f,
            exposure = 0.7f,
            contrast = -15f,
            highlights = -30f,
            shadows = 40f,
            whites = 5f,
            blacks = -6f,
            texture = 18f,
            clarity = 22f,
            dehaze = 9f
        )

        val parsed = EditAdjustments.fromJson(source.toJson())

        assertEquals(source, parsed)
        assertEquals(0f, EditAdjustments.fromJson(null).exposure, 0.001f)
    }

    @Test
    fun presetJsonRoundTripIncludesAdjustments() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("agfa_optima.ncp")!!.readBytes()
        val control = PictureControlParser.parse(bytes, "agfa_optima.ncp")
        val preset = Preset.from(
            control = control,
            id = "adjusted",
            fileName = "agfa_optima.ncp",
            fileSize = bytes.size,
            isSample = true
        ).copy(
            adjustments = EditAdjustments(temperature = 15f, exposure = -0.5f),
            toneCurve = ToneCurve(listOf(ToneCurvePoint(0f, 0.05f), ToneCurvePoint(1f, 0.95f)))
        )

        val parsed = Preset.fromJson(preset.toJson())

        assertEquals(15f, parsed.adjustments.temperature, 0.001f)
        assertEquals(-0.5f, parsed.adjustments.exposure, 0.001f)
        assertTrue(parsed.hasAdjustments)
        assertTrue(parsed.hasToneCurve)
    }
}
