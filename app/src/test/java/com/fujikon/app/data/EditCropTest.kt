package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditCropTest {

    @Test
    fun fullCropKeepsTheWholeImage() {
        val crop = EditCrop.Full
        assertTrue(crop.isFull())
        val rect = crop.rect(400f, 300f)
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(1f, rect.right, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)

        val geometry = crop.geometry(400, 300)
        assertEquals(400, geometry.outputWidth)
        assertEquals(300, geometry.outputHeight)
    }

    @Test
    fun targetRatiosAreAppliedInPixelSpace() {
        val square = EditCrop(aspect = CropAspect.SQUARE.id).rect(400f, 300f)
        val squarePixels = (square.width * 400f) / (square.height * 300f)
        assertEquals(1f, squarePixels, 0.0001f)

        val wide = EditCrop(aspect = CropAspect.SIXTEEN_NINE.id).rect(400f, 300f)
        val widePixels = (wide.width * 400f) / (wide.height * 300f)
        assertEquals(16f / 9f, widePixels, 0.0001f)

        val portrait = EditCrop(aspect = CropAspect.NINE_SIXTEEN.id).rect(400f, 300f)
        val portraitPixels = (portrait.width * 400f) / (portrait.height * 300f)
        assertEquals(9f / 16f, portraitPixels, 0.0001f)

        val threeFour = EditCrop(aspect = CropAspect.THREE_FOUR.id).rect(400f, 300f)
        assertEquals(3f / 4f, (threeFour.width * 400f) / (threeFour.height * 300f), 0.0001f)

        val twoThree = EditCrop(aspect = CropAspect.TWO_THREE.id).rect(400f, 300f)
        assertEquals(2f / 3f, (twoThree.width * 400f) / (twoThree.height * 300f), 0.0001f)
    }

    @Test
    fun geometryMatchesTheSelectedRatio() {
        val square = EditCrop(aspect = CropAspect.SQUARE.id).geometry(400, 300)
        assertEquals(300, square.outputWidth)
        assertEquals(300, square.outputHeight)

        val wide = EditCrop(aspect = CropAspect.SIXTEEN_NINE.id).geometry(400, 300)
        assertEquals(400, wide.outputWidth)
        assertEquals(225, wide.outputHeight)

        val portrait = EditCrop(aspect = CropAspect.NINE_SIXTEEN.id).geometry(400, 300)
        assertEquals(9f / 16f, portrait.outputWidth.toFloat() / portrait.outputHeight, 0.01f)
        assertEquals(300, portrait.outputHeight)
    }

    @Test
    fun rotationSwapsTheOutputDimensions() {
        val rotated = EditCrop(rotation = 90).geometry(400, 300)
        assertEquals(300, rotated.outputWidth)
        assertEquals(400, rotated.outputHeight)
        assertEquals(0f, rotated.cosAngle, 0.0001f)
        assertEquals(1f, rotated.sinAngle, 0.0001f)

        val upsideDown = EditCrop(rotation = 180).geometry(400, 300)
        assertEquals(400, upsideDown.outputWidth)
        assertEquals(300, upsideDown.outputHeight)
    }

    @Test
    fun straightenShrinksTheCropWithoutEmptyCorners() {
        val geometry = EditCrop(straighten = 10f).geometry(400, 300)
        assertTrue(geometry.outputWidth < 400)
        assertTrue(geometry.outputHeight < 300)
        assertEquals(
            4f / 3f,
            geometry.outputWidth.toFloat() / geometry.outputHeight,
            0.01f
        )
    }

    @Test
    fun normalizedClampsRotationAndStraightenAndFallsBackToOriginal() {
        val crop = EditCrop(aspect = "missing", rotation = 450, straighten = 90f).normalized()
        assertEquals(CropAspect.ORIGINAL.id, crop.aspect)
        assertEquals(90, crop.rotation)
        assertEquals(EditCrop.STRAIGHTEN_RANGE, crop.straighten, 0.0001f)
        assertFalse(crop.isFull())

        assertEquals(270, EditCrop(rotation = -90).normalized().rotation)
    }

    @Test
    fun jsonRoundTripKeepsCropState() {
        val crop = EditCrop(
            aspect = CropAspect.THREE_TWO.id,
            rotation = 90,
            straighten = -7.5f
        )
        val parsed = EditCrop.fromJson(crop.toJson())
        assertEquals(crop, parsed)
        assertEquals(EditCrop.Full, EditCrop.fromJson(null))
    }

    @Test
    fun centeredHelperUsesTheRequestedAspect() {
        val rect = EditCrop.centered(300f, 400f, CropAspect.SQUARE)
        assertEquals(1f, (rect.width * 300f) / (rect.height * 400f), 0.0001f)
        assertEquals(0.5f, rect.left + rect.width / 2f, 0.0001f)
        assertEquals(0.5f, rect.top + rect.height / 2f, 0.0001f)
    }

    @Test
    fun freeRectOverridesTheAspectAndSurvivesJson() {
        val rect = CropRect(0.2f, 0.1f, 0.8f, 0.9f)
        val crop = EditCrop(rotation = 90, straighten = 6f, customRect = rect).normalized()
        assertFalse(crop.isFull())
        assertEquals(rect, crop.rect(400f, 300f))
        assertEquals(rect, EditCrop.fromJson(crop.toJson()).customRect)
        assertEquals(90, EditCrop.fromJson(crop.toJson()).rotation)
        assertEquals(6f, EditCrop.fromJson(crop.toJson()).straighten, 0.0001f)
    }

    @Test
    fun freeRectUsesTheRotatedDisplayFrame() {
        val crop = EditCrop(rotation = 90, customRect = CropRect(0.25f, 0.25f, 0.75f, 0.75f))
        assertEquals(300, crop.displayWidth(400, 300))
        assertEquals(400, crop.displayHeight(400, 300))
        val outputWidth = (crop.rect(400f, 300f).width * crop.displayWidth(400, 300)).toInt()
        val outputHeight = (crop.rect(400f, 300f).height * crop.displayHeight(400, 300)).toInt()
        assertEquals(150, outputWidth)
        assertEquals(200, outputHeight)
        assertEquals(3f / 4f, outputWidth.toFloat() / outputHeight, 0.0001f)
    }

    @Test
    fun fullCustomRectIsTreatedAsNoCrop() {
        assertTrue(EditCrop(customRect = CropRect(0f, 0f, 1f, 1f)).isFull())
    }
}
