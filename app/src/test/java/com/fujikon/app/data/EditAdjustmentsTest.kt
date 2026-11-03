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

    @Test
    fun hslCropAndCreativeFilterRoundTrip() {
        val source = EditAdjustments(
            hsl = HslAdjustments.Neutral.withBand(2, HslBand(hue = 14f, saturation = -30f, luminance = 9f)),
            crop = EditCrop(aspect = CropAspect.FOUR_THREE.id, rotation = 90, straighten = -4.5f),
            creativeFilter = CreativeFilter.LEICA.id,
            filterStrength = 72f
        )
        val parsed = EditAdjustments.fromJson(source.toJson())

        assertEquals(14f, parsed.hsl.band(2).hue, 0.001f)
        assertEquals(-30f, parsed.hsl.band(2).saturation, 0.001f)
        assertEquals(CropAspect.FOUR_THREE.id, parsed.crop.aspect)
        assertEquals(90, parsed.crop.rotation)
        assertEquals(-4.5f, parsed.crop.straighten, 0.001f)
        assertEquals(CreativeFilter.LEICA.id, parsed.creativeFilter)
        assertEquals(72f, parsed.filterStrength, 0.001f)
        assertFalse(parsed.isNeutral())
    }

    @Test
    fun newAdjustmentFieldsAreClamped() {
        val normalized = EditAdjustments(
            hsl = HslAdjustments.Neutral.withBand(0, HslBand(hue = 999f, saturation = -999f, luminance = 999f)),
            crop = EditCrop(rotation = 999, straighten = 99f),
            creativeFilter = "removed-filter",
            filterStrength = 500f
        ).normalized()

        assertEquals(100f, normalized.hsl.band(0).hue, 0.001f)
        assertEquals(-100f, normalized.hsl.band(0).saturation, 0.001f)
        assertEquals(270, normalized.crop.rotation)
        assertEquals(EditCrop.STRAIGHTEN_RANGE, normalized.crop.straighten, 0.001f)
        assertEquals(CreativeFilter.NONE.id, normalized.creativeFilter)
        assertEquals(100f, normalized.filterStrength, 0.001f)
    }

    @Test
    fun lensBlurRoundTripsAndIsClamped() {
        val source = EditAdjustments(
            lensBlur = LensBlur(
                amount = 42f,
                focusX = 0.2f,
                focusY = 0.8f,
                focusSize = 60f,
                feather = 30f,
                highlights = 12f
            )
        )
        val parsed = EditAdjustments.fromJson(source.toJson())

        assertEquals(source, parsed)
        assertFalse(parsed.isNeutral())

        val clamped = LensBlur(
            amount = 260f,
            focusX = -1f,
            focusY = 2f,
            focusSize = 200f,
            feather = -5f,
            highlights = 150f
        ).normalized()

        assertEquals(LensBlur.MAX_AMOUNT, clamped.amount, 0.001f)
        assertEquals(0f, clamped.focusX, 0.001f)
        assertEquals(1f, clamped.focusY, 0.001f)
        assertEquals(LensBlur.MAX_FOCUS_SIZE, clamped.focusSize, 0.001f)
        assertEquals(0f, clamped.feather, 0.001f)
        assertEquals(LensBlur.MAX_AMOUNT, clamped.highlights, 0.001f)
    }
}
