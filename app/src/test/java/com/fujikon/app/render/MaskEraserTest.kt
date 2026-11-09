package com.fujikon.app.render

import com.fujikon.app.data.EraserStroke
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.ui.components.appendEraserStroke
import com.fujikon.app.ui.components.clearEraser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mask eraser is LAYER-scoped and mask-agnostic: it must remove scope from
 * every mask kind, in both the low-res grid used at render time and the layer
 * JSON that travels through presets / history / export.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskEraserTest {

    private fun centreStroke(radius: Float = 0.2f, feather: Float = 0.3f, strength: Float = 1f) =
        EraserStroke(points = listOf(0.5f, 0.5f), radius = radius, feather = feather, strength = strength)

    @Test
    fun eraserRemovesScopeFromControlPoint() {
        val g = LocalMaskRenderer.buildGrid(
            LocalMask.ControlPoint(x = 0.5f, y = 0.5f, radius = 0.4f, feather = 0.2f), 128, 128
        )
        val before = g.sample(0.5f, 0.5f)
        assertTrue("control point centre should be selected", before > 0.8f)
        LocalMaskRenderer.applyEraser(g.values, g.width, g.height, listOf(centreStroke()))
        assertTrue("eraser should clear the centre", g.sample(0.5f, 0.5f) < 0.05f)
    }

    @Test
    fun eraserWorksOnAllGeometricMasks() {
        val masks = listOf(
            LocalMask.ControlPoint(radius = 0.6f, feather = 0.2f),
            LocalMask.LinearGradient(startY = 0f, endY = 1f, feather = 0.45f),
            LocalMask.RadialGradient(radiusX = 0.7f, radiusY = 0.7f, feather = 0.2f),
            LocalMask.Brush(
                strokes = listOf(
                    com.fujikon.app.data.BrushStroke(points = listOf(0.2f, 0.5f, 0.8f, 0.5f))
                ),
                radius = 0.3f, feather = 0.3f
            )
        )
        for (mask in masks) {
            val g = LocalMaskRenderer.buildGrid(mask, 128, 128)
            val before = g.sample(0.5f, 0.5f)
            LocalMaskRenderer.applyEraser(g.values, g.width, g.height, listOf(centreStroke()))
            assertTrue(
                "${mask::class.simpleName}: eraser must reduce scope",
                g.sample(0.5f, 0.5f) < before * 0.2f + 1e-4f
            )
        }
    }

    @Test
    fun eraserWorksOnContentAwareFallback() {
        // No analysis -> SelectionMaskGenerator's deterministic fallback grid.
        val g = LocalMaskRenderer.buildGrid(LocalMask.SubjectMask(), 128, 128)
        val before = g.sample(0.5f, 0.52f)
        LocalMaskRenderer.applyEraser(g.values, g.width, g.height, listOf(centreStroke()))
        assertTrue("AI selection fallback must be erasable too", g.sample(0.5f, 0.5f) <= before)
    }

    @Test
    fun erasedCentreIsZeroAndFarCornerIntact() {
        val g = LocalMaskRenderer.buildGrid(
            LocalMask.RadialGradient(radiusX = 0.9f, radiusY = 0.9f, feather = 0.1f), 128, 128
        )
        val corner = g.sample(0.05f, 0.05f)
        LocalMaskRenderer.applyEraser(g.values, g.width, g.height, listOf(centreStroke(radius = 0.1f)))
        assertTrue(g.sample(0.5f, 0.5f) < 0.05f)
        assertEquals("far corner must be untouched", corner, g.sample(0.05f, 0.05f), 1e-5f)
    }

    @Test
    fun featherSoftensEraserEdge() {
        val hard = LocalMaskRenderer.buildGrid(LocalMask.RadialGradient(radiusX = 0.9f, radiusY = 0.9f, feather = 0.1f), 128, 128)
        val soft = LocalMaskRenderer.buildGrid(LocalMask.RadialGradient(radiusX = 0.9f, radiusY = 0.9f, feather = 0.1f), 128, 128)
        LocalMaskRenderer.applyEraser(hard.values, hard.width, hard.height, listOf(centreStroke(feather = 0.05f)))
        LocalMaskRenderer.applyEraser(soft.values, soft.width, soft.height, listOf(centreStroke(feather = 1f)))
        // At the rim the soft brush removes less than the hard brush.
        assertTrue(
            "a feathered eraser should fade out at the rim",
            soft.sample(0.58f, 0.5f) > hard.sample(0.58f, 0.5f)
        )
    }

    @Test
    fun strengthControlsRemovalDepth() {
        val g = LocalMaskRenderer.buildGrid(LocalMask.RadialGradient(radiusX = 0.9f, radiusY = 0.9f, feather = 0.1f), 128, 128)
        val before = g.sample(0.5f, 0.5f)
        LocalMaskRenderer.applyEraser(g.values, g.width, g.height, listOf(centreStroke(strength = 0.5f)))
        val after = g.sample(0.5f, 0.5f)
        assertTrue("half-strength eraser should only halve the scope", after in (before * 0.4f)..(before * 0.6f))
    }

    @Test
    fun multipleStrokesAccumulate() {
        val g = LocalMaskRenderer.buildGrid(LocalMask.RadialGradient(radiusX = 0.9f, radiusY = 0.9f, feather = 0.1f), 128, 128)
        val strokes = listOf(
            EraserStroke(points = listOf(0.35f, 0.5f), radius = 0.1f, feather = 0.2f),
            EraserStroke(points = listOf(0.65f, 0.5f), radius = 0.1f, feather = 0.2f)
        )
        LocalMaskRenderer.applyEraser(g.values, g.width, g.height, strokes)
        assertTrue(g.sample(0.35f, 0.5f) < 0.05f)
        assertTrue(g.sample(0.65f, 0.5f) < 0.05f)
    }

    @Test
    fun emptyStrokesLeaveMaskUntouched() {
        val g = LocalMaskRenderer.buildGrid(LocalMask.ControlPoint(), 128, 128)
        val before = g.values.copyOf()
        LocalMaskRenderer.applyEraser(g.values, g.width, g.height, emptyList())
        assertTrue("no strokes must be a no-op", g.values.contentEquals(before))
    }

    @Test
    fun eraserStrokeNormalizedClamps() {
        val n = EraserStroke(
            points = listOf(-1f, 2f), radius = 9f, feather = -3f, strength = 8f
        ).normalized()
        assertEquals(0f, n.points[0], 0f)
        assertEquals(1f, n.points[1], 0f)
        assertEquals(0.4f, n.radius, 0f)
        assertEquals(0.05f, n.feather, 0f)
        assertEquals(1f, n.strength, 0f)
    }

    @Test
    fun layerJsonRoundTripPreservesEraser() {
        val layer = LocalLayer(
            id = "eraser-1",
            mask = LocalMask.LinearGradient(),
            params = LocalParams(brightness = 30f),
            eraser = listOf(
                EraserStroke(points = listOf(0.1f, 0.2f, 0.3f, 0.4f), radius = 0.07f, feather = 0.8f, strength = 0.6f)
            )
        )
        val restored = LocalLayer.fromJson(layer.toJson())!!
        assertEquals(1, restored.eraser.size)
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), restored.eraser[0].points)
        assertEquals(0.07f, restored.eraser[0].radius, 1e-5f)
        assertEquals(0.8f, restored.eraser[0].feather, 1e-5f)
        assertEquals(0.6f, restored.eraser[0].strength, 1e-5f)
    }

    @Test
    fun legacyLayerJsonDefaultsToEmptyEraser() {
        val legacy = JSONObject().apply {
            put("id", "old")
            put("name", "局部")
            put("mask", LocalMask.ControlPoint().toJson())
            put("params", LocalParams.Neutral.toJson())
            put("strength", 100.0)
            put("enabled", true)
        }
        val restored = LocalLayer.fromJson(legacy)!!
        assertTrue("legacy presets must not lose any scope", restored.eraser.isEmpty())
    }

    @Test
    fun prepareAppliesEraserToActiveLayer() {
        val local = LocalAdjustments(
            listOf(
                LocalLayer(
                    id = "a",
                    mask = LocalMask.ControlPoint(x = 0.5f, y = 0.5f, radius = 0.6f, feather = 0.2f),
                    params = LocalParams(brightness = 50f),
                    eraser = listOf(EraserStroke(points = listOf(0.5f, 0.5f), radius = 0.2f, feather = 0.3f))
                )
            )
        )
        val prepared = LocalMaskRenderer.prepare(local, 128, 128, null)
        assertEquals(1, prepared.size)
        assertTrue("prepared grid must already be erased", prepared[0].grid.sample(0.5f, 0.5f) < 0.05f)
    }

    @Test
    fun appendAndClearEraserHelpers() {
        var layer = LocalLayer(id = "x")
        layer = appendEraserStroke(layer, listOf(0.2f, 0.3f, 0.4f, 0.5f), 0.08f, 0.7f)
        assertEquals(1, layer.eraser.size)
        assertEquals(0.08f, layer.eraser[0].radius, 1e-5f)
        assertEquals(0.7f, layer.eraser[0].feather, 1e-5f)
        layer = clearEraser(layer)
        assertTrue("clear must restore the full scope", layer.eraser.isEmpty())
    }
}
