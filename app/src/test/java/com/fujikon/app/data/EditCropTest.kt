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
    fun zoomAndOffsetStayInsideTheSource() {
        val crop = EditCrop(
            aspect = CropAspect.SQUARE.id,
            zoom = 2f,
            offsetX = 1f,
            offsetY = -1f
        )
        val rect = crop.rect(400f, 300f)
        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.right <= 1f)
        assertTrue(rect.bottom <= 1f)
        assertEquals(0.375f, rect.width, 0.0001f)
        assertEquals(0.5f, rect.height, 0.0001f)
        assertEquals(0.625f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
    }

    @Test
    fun normalizedClampsZoomAndOffsetsAndFallsBackToOriginal() {
        val crop = EditCrop(aspect = "missing", zoom = 10f, offsetX = -4f, offsetY = 9f).normalized()
        assertEquals(CropAspect.ORIGINAL.id, crop.aspect)
        assertEquals(EditCrop.MAX_ZOOM, crop.zoom, 0.0001f)
        assertEquals(-1f, crop.offsetX, 0.0001f)
        assertEquals(1f, crop.offsetY, 0.0001f)
        assertFalse(crop.isFull())
    }

    @Test
    fun jsonRoundTripKeepsCropState() {
        val crop = EditCrop(
            aspect = CropAspect.THREE_TWO.id,
            zoom = 1.75f,
            offsetX = 0.25f,
            offsetY = -0.5f
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
}
