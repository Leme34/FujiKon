package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {

    @Test
    fun linearCurveIsIdentity() {
        val curve = ToneCurve.linear()
        listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f).forEach { x ->
            assertEquals(x, curve.apply(x), 0.0005f)
        }
        assertTrue(curve.isLinear())
    }

    @Test
    fun sCurveLiftsShadowsAndLowersHighlights() {
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.25f, 0.18f),
                ToneCurvePoint(0.75f, 0.82f),
                ToneCurvePoint(1f, 1f)
            )
        )

        assertEquals(0.18f, curve.apply(0.25f), 0.001f)
        assertEquals(0.82f, curve.apply(0.75f), 0.001f)
        assertTrue(curve.apply(0.5f) > 0.4f)
        assertFalse(curve.isLinear())
    }

    @Test
    fun normalizationSortsClampsAndAddsEndpoints() {
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(2f, -1f),
                ToneCurvePoint(0.5f, 0.6f),
                ToneCurvePoint(0.5f, 0.7f),
                ToneCurvePoint(-1f, 2f)
            )
        )
        val points = curve.normalizedPoints

        assertEquals(0f, points.first().x, 0f)
        assertEquals(1f, points.last().x, 0f)
        assertEquals(1f, points.first().y, 0f)
        assertEquals(0f, points.last().y, 0f)
        assertEquals(0.7f, points.first { it.x == 0.5f }.y, 0f)
        assertTrue(points.zipWithNext().all { (a, b) -> a.x < b.x })
    }

    @Test
    fun endpointsCannotBeRemovedAndInteriorPointCanBe() {
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0.1f),
                ToneCurvePoint(0.5f, 0.4f),
                ToneCurvePoint(1f, 0.9f)
            )
        )

        assertEquals(3, curve.removePoint(0).normalizedPoints.size)
        assertEquals(3, curve.removePoint(2).normalizedPoints.size)
        assertEquals(2, curve.removePoint(1).normalizedPoints.size)
    }

    @Test
    fun curveStaysInRangeForNonMonotonicPoints() {
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0.8f),
                ToneCurvePoint(0.3f, 0.1f),
                ToneCurvePoint(0.7f, 0.9f),
                ToneCurvePoint(1f, 0.2f)
            )
        )

        val samples = curve.previewPoints(200).map { it.second }
        assertTrue(samples.all { it in 0f..1f })
        assertEquals(0.8f, curve.apply(0f), 0.0001f)
        assertEquals(0.2f, curve.apply(1f), 0.0001f)
    }

    @Test
    fun diagonalPointKeepsCurveLinear() {
        val curve = ToneCurve(
            listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.5f, 0.5f),
                ToneCurvePoint(1f, 1f)
            )
        )

        assertTrue(curve.isLinear())
    }
}
