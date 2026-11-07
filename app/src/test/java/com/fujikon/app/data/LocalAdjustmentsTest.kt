package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAdjustmentsTest {

    @Test
    fun pointColorRoundTripsAndClamps() {
        val pc = PointColor(
            targetHue = 400f, hueRange = 200f, minSaturation = 2f,
            hueShift = 300f, saturation = -300f, luminance = 40f
        ).normalized()
        assertEquals(40f, pc.targetHue, 0.001f)
        assertEquals(90f, pc.hueRange, 0.001f)
        assertEquals(100f, pc.hueShift, 0.001f)
        assertEquals(-100f, pc.saturation, 0.001f)
        assertFalse(pc.isNeutral())

        val restored = PointColor.fromJson(pc.toJson())
        assertEquals(pc, restored)
        assertTrue(PointColor.Neutral.isNeutral())
    }

    @Test
    fun allFourMaskShapesRoundTrip() {
        val masks = listOf(
            LocalMask.ControlPoint(0.3f, 0.7f, 0.4f, 0.8f),
            LocalMask.LinearGradient(0.2f, 0.1f, 0.8f, 0.9f, 0.3f, true),
            LocalMask.RadialGradient(0.4f, 0.6f, 0.3f, 0.5f, 0.7f, true),
            LocalMask.Brush(listOf(BrushStroke(listOf(0.1f, 0.1f, 0.5f, 0.5f), 0.8f)), 0.08f, 0.6f, false)
        )
        masks.forEach { original ->
            val json = LocalLayer("id1", mask = original).toJson()
            val restored = LocalLayer.fromJson(json)
            assertNotNull(restored)
            assertEquals(original, restored!!.mask)
        }
    }

    @Test
    fun layerIsActiveOnlyWhenEnabledWithNonNeutralParams() {
        val base = LocalLayer("a", params = LocalParams(brightness = 30f))
        assertTrue(base.active())
        assertFalse(base.copy(enabled = false).active())
        assertFalse(base.copy(strength = 0f).active())
        assertFalse(base.copy(params = LocalParams.Neutral).active())

        val local = LocalAdjustments(listOf(base, base.copy(id = "b", enabled = false)))
        assertEquals(1, local.activeLayers().size)
        assertFalse(local.isNeutral())
        assertTrue(LocalAdjustments.Neutral.isNeutral())
    }

    @Test
    fun localAdjustmentsCapLayerCountAndRoundTrip() {
        val many = (0 until 20).map { LocalLayer("l$it", params = LocalParams(shadows = 10f)) }
        assertEquals(LocalAdjustments.MAX_LAYERS, LocalAdjustments(many).normalized().layers.size)

        val restored = LocalAdjustments.fromJson(LocalAdjustments(many.take(2)).toJson())
        assertEquals(2, restored.layers.size)
        assertEquals("l0", restored.layers[0].id)
    }

    @Test
    fun legacyAdjustmentsJsonStaysNeutralForNewFields() {
        val legacy = org.json.JSONObject().put("exposure", 0.4).put("contrast", 20.0)
        val parsed = EditAdjustments.fromJson(legacy)
        assertEquals(0.4f, parsed.exposure, 0.001f)
        assertEquals(0f, parsed.denoise, 0.001f)
        assertEquals(0f, parsed.grain, 0.001f)
        assertEquals(0f, parsed.vignette, 0.001f)
        assertTrue(parsed.pointColor.isNeutral())
        assertTrue(parsed.local.isNeutral())
    }

    @Test
    fun newQualityFieldsRoundTripThroughEditAdjustments() {
        val original = EditAdjustments(
            denoise = 40f, grain = 25f, vignette = -30f,
            pointColor = PointColor(targetHue = 200f, saturation = 25f),
            local = LocalAdjustments(listOf(LocalLayer("z", params = LocalParams(brightness = 20f))))
        )
        val restored = EditAdjustments.fromJson(original.toJson())
        assertEquals(original.denoise, restored.denoise, 0.001f)
        assertEquals(original.grain, restored.grain, 0.001f)
        assertEquals(original.vignette, restored.vignette, 0.001f)
        assertEquals(original.pointColor, restored.pointColor)
        assertEquals(1, restored.local.layers.size)
        assertFalse(restored.isNeutral())
    }

    @Test
    fun aiResultIsPartOfHistorySnapshot() {
        val base = EditSnapshot(1L, "p", null, EditAdjustments.Neutral)
        assertFalse(base.hasAiResult)

        val withAi = base.withAiResult("file:///tmp/ai.jpg")
        assertTrue(withAi.hasAiResult)
        assertEquals("file:///tmp/ai.jpg", withAi.aiResultUri)
        assertFalse(withAi.copy(aiResultVisible = false).hasAiResult)

        val history = EditHistory()
        history.reset(base, "打开照片")
        history.recordAiResult("应用 AI 修图结果", withAi)
        assertEquals("file:///tmp/ai.jpg", history.currentAiResult)

        history.record("隐藏 AI 修图结果", base.withoutAiResult())
        assertEquals(null, history.currentAiResult)
        assertEquals("file:///tmp/ai.jpg", history.undo()!!.snapshot.aiResultUri)
    }
}
