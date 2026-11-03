package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropSpecTest {

    private val landscapeAspect = 4f / 3f

    @Test
    fun defaultSpecIsIdentity() {
        assertTrue(CropSpec.None.isIdentity)
        assertFalse(CropSpec(angle = 5f).isIdentity)
        assertFalse(CropSpec(left = 0.1f).isIdentity)
    }

    @Test
    fun normalizedClampsOutsideTheImage() {
        val spec = CropSpec(left = -0.2f, top = 0.4f, right = 0.3f, bottom = 0.99f)
            .normalized(landscapeAspect)

        assertEquals(0f, spec.left, 0.001f)
        assertEquals(0.3f, spec.right, 0.001f)
        assertEquals(0.4f, spec.top, 0.001f)
        assertEquals(0.99f, spec.bottom, 0.001f)
    }

    @Test
    fun normalizedEnforcesAMinimumCrop() {
        val spec = CropSpec(left = 0.6f, top = 0.7f, right = 0.62f, bottom = 0.71f)
            .normalized(landscapeAspect)

        assertTrue(spec.widthFraction >= CropSpec.MIN_FRACTION - 0.001f)
        assertTrue(spec.heightFraction >= CropSpec.MIN_FRACTION - 0.001f)
    }

    @Test
    fun translatingKeepsTheCropInsideTheFrame() {
        val spec = CropSpec(left = 0.5f, top = 0.2f, right = 0.9f, bottom = 0.6f)
            .translate(1f, 1f, landscapeAspect)

        assertEquals(1f, spec.right, 0.001f)
        assertEquals(1f, spec.bottom, 0.001f)
        assertEquals(0.6f, spec.left, 0.001f)
        assertEquals(0.6f, spec.top, 0.001f)
    }

    @Test
    fun zoomShrinksAroundTheFocusPoint() {
        val spec = CropSpec(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)
            .zoom(0.5f, 0.5f, 0.5f, landscapeAspect)

        assertEquals(0.35f, spec.left, 0.001f)
        assertEquals(0.65f, spec.right, 0.001f)
        assertEquals(0.35f, spec.top, 0.001f)
        assertEquals(0.65f, spec.bottom, 0.001f)
    }

    @Test
    fun cornerResizeKeepsTheOppositeCornerAnchored() {
        val spec = CropSpec()
            .resize(CropCorner.TOP_LEFT, 0.1f, 0.1f, landscapeAspect)

        assertEquals(0.1f, spec.left, 0.001f)
        assertEquals(0.1f, spec.top, 0.001f)
        assertEquals(1f, spec.right, 0.001f)
        assertEquals(1f, spec.bottom, 0.001f)
    }

    @Test
    fun lockedAspectSurvivesCornerResize() {
        val spec = CropSpec(aspectRatio = 1f)
            .resize(CropCorner.TOP_LEFT, 0.1f, 0.1f, landscapeAspect)
            .normalized(landscapeAspect)

        val pixelAspect = spec.widthFraction * landscapeAspect / spec.heightFraction
        assertEquals(1f, pixelAspect, 0.01f)
        assertEquals(1f, spec.right, 0.001f)
        assertEquals(1f, spec.bottom, 0.001f)
    }

    @Test
    fun pickingSquareBuildsTheLargestCenteredSquare() {
        val spec = CropSpec().withAspectRatio(1f, landscapeAspect)

        assertEquals(0.75f, spec.widthFraction, 0.001f)
        assertEquals(1f, spec.heightFraction, 0.001f)
        assertEquals(0.125f, spec.left, 0.001f)
        assertEquals(1f, spec.aspectRatio ?: 0f, 0.001f)
    }

    @Test
    fun originalAspectMatchesThePhoto() {
        val spec = CropSpec().withAspectRatio(landscapeAspect, landscapeAspect)

        val pixelAspect = spec.widthFraction * landscapeAspect / spec.heightFraction
        assertEquals(landscapeAspect, pixelAspect, 0.01f)
    }

    @Test
    fun angleIsClampedToTheStraightenRange() {
        val spec = CropSpec(angle = 80f).normalized(landscapeAspect)
        assertEquals(CropSpec.MAX_ANGLE, spec.angle, 0.001f)
    }
}
