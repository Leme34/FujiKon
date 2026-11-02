package com.fujikon.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HslAdjustmentsTest {

    @Test
    fun neutralStartsEmptyAndNormalizesEightBands() {
        assertTrue(HslAdjustments.Neutral.isNeutral())
        val normalized = HslAdjustments(listOf(HslBand(hue = 500f))).normalized()
        assertEquals(HslAdjustments.BAND_COUNT, normalized.bands.size)
        assertEquals(100f, normalized.band(0).hue, 0.0001f)
        assertEquals(0f, normalized.band(7).hue, 0.0001f)
    }

    @Test
    fun withBandUpdatesOnlyTheRequestedColour() {
        val source = HslAdjustments.Neutral
            .withBand(1, HslBand(hue = 12f, saturation = -20f, luminance = 8f))
            .withBand(4, HslBand(luminance = 16f))
        assertEquals(12f, source.band(1).hue, 0.0001f)
        assertEquals(-20f, source.band(1).saturation, 0.0001f)
        assertEquals(8f, source.band(1).luminance, 0.0001f)
        assertEquals(0f, source.band(0).luminance, 0.0001f)
        assertEquals(16f, source.band(4).luminance, 0.0001f)
        assertFalse(source.isNeutral())
    }

    @Test
    fun jsonRoundTripKeepsEveryBand() {
        val source = HslAdjustments.Neutral.withBand(
            7,
            HslBand(hue = -9f, saturation = 33f, luminance = -12f)
        )
        val parsed = HslAdjustments.fromJson(source.toJson())
        assertEquals(source, parsed)
        assertTrue(HslAdjustments.fromJson(JSONObject()).isNeutral())
    }
}
