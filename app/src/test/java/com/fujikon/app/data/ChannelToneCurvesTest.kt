package com.fujikon.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelToneCurvesTest {

    private fun identityLut() = IntArray(256) { it }

    private fun liftCurve(amount: Float = 0.2f) = ToneCurve(
        listOf(
            ToneCurvePoint(0f, 0f),
            ToneCurvePoint(0.5f, (0.5f + amount).coerceIn(0f, 1f)),
            ToneCurvePoint(1f, 1f)
        )
    )

    @Test
    fun neutralSetIsANoOp() {
        val set = ChannelToneCurves.Neutral
        assertTrue(set.isNeutral)
        assertTrue(set.withCurve(ToneCurveChannel.RGB, liftCurve()).isNeutral)
        assertEquals(ToneCurve.linear(), set.curve(ToneCurveChannel.RED))

        val master = identityLut()
        val luts = buildChannelLuts(master, set)
        assertEquals(3, luts.size)
        assertSame(master, luts[0])
        assertSame(master, luts[1])
        assertSame(master, luts[2])
    }

    @Test
    fun eachChannelOnlyChangesItsOwnLut() {
        val master = identityLut()
        val set = ChannelToneCurves(red = liftCurve(), blue = liftCurve(0.35f))
        assertFalse(set.isNeutral)

        val luts = buildChannelLuts(master, set)

        // Red and blue are remapped, green stays byte-identical to the master.
        assertSame(master, luts[1])
        assertTrue(luts[0][128] > master[128])
        assertTrue(luts[2][128] > luts[0][128])
        assertEquals(master[0], luts[0][0])
        assertEquals(master[255], luts[0][255])
        assertTrue(luts[0].all { it in 0..255 })
        assertTrue(luts[2].all { it in 0..255 })
    }

    @Test
    fun perChannelCurvesAreAppliedOnTopOfTheMasterLut() {
        // A master LUT that already crushes everything to mid grey.
        val master = IntArray(256) { 128 }
        val set = ChannelToneCurves(green = liftCurve())
        val luts = buildChannelLuts(master, set)

        // The green curve is evaluated at the master OUTPUT (128/255), not at
        // the original input, so every entry maps to the same lifted value.
        val expected = (set.green.apply(128 / 255f) * 255f).toInt()
        assertTrue(luts[1].all { kotlin.math.abs(it - expected) <= 1 })
        assertSame(master, luts[0])
        assertSame(master, luts[2])
    }

    @Test
    fun withCurveReplacesOnlyTheRequestedChannel() {
        val red = liftCurve(0.1f)
        val green = liftCurve(0.2f)
        val set = ChannelToneCurves.Neutral
            .withCurve(ToneCurveChannel.RED, red)
            .withCurve(ToneCurveChannel.GREEN, green)

        assertEquals(red, set.red)
        assertEquals(green, set.green)
        assertEquals(ToneCurve.linear(), set.blue)
        assertFalse(set.withCurve(ToneCurveChannel.BLUE, ToneCurve.linear()).isNeutral)
        assertEquals(set, set.withCurve(ToneCurveChannel.RGB, green))
    }

    @Test
    fun jsonRoundTripKeepsEveryChannel() {
        val set = ChannelToneCurves(
            red = ToneCurve(listOf(ToneCurvePoint(0f, 0f), ToneCurvePoint(0.3f, 0.45f), ToneCurvePoint(1f, 1f))),
            green = ToneCurve(listOf(ToneCurvePoint(0f, 0.08f), ToneCurvePoint(1f, 0.92f))),
            blue = liftCurve()
        )
        val parsed = ChannelToneCurves.fromJson(set.toJson())

        assertEquals(set.red.normalizedPoints, parsed.red.normalizedPoints)
        assertEquals(set.green.normalizedPoints, parsed.green.normalizedPoints)
        assertEquals(set.blue.normalizedPoints, parsed.blue.normalizedPoints)
        assertFalse(parsed.isNeutral)
    }

    @Test
    fun missingOrMalformedFieldsFallBackToLinear() {
        assertTrue(ChannelToneCurves.fromJson(null).isNeutral)
        assertTrue(ChannelToneCurves.fromJson(JSONObject()).isNeutral)

        val partial = JSONObject().apply {
            put("red", liftCurve().toJson())
            put("green", "not-an-object")
        }
        val parsed = ChannelToneCurves.fromJson(partial)
        assertFalse(parsed.isNeutral)
        assertFalse(parsed.red.isLinear())
        assertTrue(parsed.green.isLinear())
        assertTrue(parsed.blue.isLinear())
    }

    @Test
    fun legacyPresetJsonWithoutChannelCurvesStillLoads() {
        val base = Preset.none().copy(
            toneCurve = ToneCurve(listOf(ToneCurvePoint(0f, 0.05f), ToneCurvePoint(1f, 0.95f)))
        )
        val json = base.toJson()
        assertFalse(json.has("channelCurves"))

        val parsed = Preset.fromJson(json)
        assertTrue(parsed.channelCurves.isNeutral)
        assertTrue(parsed.hasToneCurve)
        assertEquals(0.05f, parsed.toneCurve!!.apply(0f), 0.001f)

        // Simulate a preset written by a much older app version.
        json.remove("channelCurves")
        assertTrue(Preset.fromJson(json).channelCurves.isNeutral)
    }

    @Test
    fun channelCurvesSurvivePresetRoundTripAndMarkThePreset() {
        val set = ChannelToneCurves(red = liftCurve())
        val preset = Preset.none().copy(channelCurves = set)

        assertTrue(preset.hasToneCurve)
        assertTrue(preset.toJson().has("channelCurves"))

        val parsed = Preset.fromJson(preset.toJson())
        assertEquals(set.red.normalizedPoints, parsed.channelCurves.red.normalizedPoints)
        assertTrue(parsed.hasToneCurve)
        // The old master-curve field is untouched, so old versions still work.
        assertTrue(parsed.toJson().isNull("toneCurve"))
    }

    @Test
    fun channelEnumKeysAreStable() {
        assertEquals("rgb", ToneCurveChannel.RGB.key)
        assertEquals("red", ToneCurveChannel.RED.key)
        assertEquals("green", ToneCurveChannel.GREEN.key)
        assertEquals("blue", ToneCurveChannel.BLUE.key)
        assertEquals(ToneCurveChannel.BLUE, ToneCurveChannel.fromKey("blue"))
        assertNull(ToneCurveChannel.fromKey("unknown"))
        assertTrue(ToneCurveChannel.RGB.isMaster)
        assertFalse(ToneCurveChannel.RED.isMaster)
    }
}
