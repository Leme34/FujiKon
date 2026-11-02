package com.fujikon.app.data

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Preset crop aspect ratios exposed by the crop editor. */
enum class CropAspect(
    val id: String,
    val label: String,
    val ratio: Float?
) {
    ORIGINAL("original", "原图", null),
    SQUARE("1:1", "1:1", 1f),
    FOUR_THREE("4:3", "4:3", 4f / 3f),
    THREE_FOUR("3:4", "3:4", 3f / 4f),
    THREE_TWO("3:2", "3:2", 3f / 2f),
    TWO_THREE("2:3", "2:3", 2f / 3f),
    SIXTEEN_NINE("16:9", "16:9", 16f / 9f),
    NINE_SIXTEEN("9:16", "9:16", 9f / 16f);

    companion object {
        fun fromId(id: String?): CropAspect = entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}

/** Normalized crop rectangle. All coordinates are relative to the source image. */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isFull: Boolean get() = left <= 0.0005f && top <= 0.0005f &&
        right >= 0.9995f && bottom >= 0.9995f
}

/**
 * Crop state stored with the rest of the non-destructive adjustments.
 *
 * The actual rectangle is calculated from the image aspect at render time, so
 * the same preset can be used on landscape and portrait photos without storing
 * pixel coordinates.
 */
data class EditCrop(
    val aspect: String = CropAspect.ORIGINAL.id,
    /** 1.0 keeps the largest crop for the selected ratio; 4.0 crops to 25%. */
    val zoom: Float = 1f,
    /** -1..1 moves the crop within the available horizontal margin. */
    val offsetX: Float = 0f,
    /** -1..1 moves the crop within the available vertical margin. */
    val offsetY: Float = 0f
) {
    fun normalized(): EditCrop = copy(
        aspect = CropAspect.fromId(aspect).id,
        zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
        offsetX = offsetX.coerceIn(-1f, 1f),
        offsetY = offsetY.coerceIn(-1f, 1f)
    )

    fun isFull(tolerance: Float = 0.001f): Boolean =
        aspect == CropAspect.ORIGINAL.id &&
            abs(zoom - 1f) <= tolerance &&
            abs(offsetX) <= tolerance &&
            abs(offsetY) <= tolerance

    /**
     * Calculates the normalized crop rectangle for an image. The ratio is
     * applied in pixel space, then converted back to normalized coordinates.
     */
    fun rect(imageWidth: Float, imageHeight: Float): CropRect {
        val safeWidth = imageWidth.coerceAtLeast(1f)
        val safeHeight = imageHeight.coerceAtLeast(1f)
        val imageAspect = safeWidth / safeHeight
        val targetAspect = CropAspect.fromId(aspect).ratio ?: imageAspect
        val state = normalized()

        var width = 1f
        var height = 1f
        if (targetAspect > imageAspect) {
            // Taller target: full width, shorter height.
            height = imageAspect / targetAspect
        } else {
            // Wider target: full height, narrower width.
            width = targetAspect / imageAspect
        }
        val safeZoom = state.zoom.coerceAtLeast(MIN_ZOOM)
        width = (width / safeZoom).coerceIn(MIN_SIZE, 1f)
        height = (height / safeZoom).coerceIn(MIN_SIZE, 1f)

        val marginX = (1f - width).coerceAtLeast(0f)
        val marginY = (1f - height).coerceAtLeast(0f)
        val left = (marginX * 0.5f + state.offsetX * marginX * 0.5f)
            .coerceIn(0f, marginX)
        val top = (marginY * 0.5f + state.offsetY * marginY * 0.5f)
            .coerceIn(0f, marginY)

        return CropRect(
            left = left,
            top = top,
            right = (left + width).coerceAtMost(1f),
            bottom = (top + height).coerceAtMost(1f)
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("aspect", aspect)
        put("zoom", zoom.toDouble())
        put("offsetX", offsetX.toDouble())
        put("offsetY", offsetY.toDouble())
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        private const val MIN_SIZE = 0.12f

        val Full = EditCrop()

        fun fromJson(json: JSONObject?): EditCrop {
            if (json == null) return Full
            return EditCrop(
                aspect = json.optString("aspect", CropAspect.ORIGINAL.id),
                zoom = json.optDouble("zoom", 1.0).toFloat(),
                offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                offsetY = json.optDouble("offsetY", 0.0).toFloat()
            ).normalized()
        }

        /** Keeps a crop rectangle inside the image and above the minimum size. */
        fun clampRect(rect: CropRect): CropRect {
            val left = rect.left.coerceIn(0f, 1f - MIN_SIZE)
            val top = rect.top.coerceIn(0f, 1f - MIN_SIZE)
            val right = rect.right.coerceIn(left + MIN_SIZE, 1f)
            val bottom = rect.bottom.coerceIn(top + MIN_SIZE, 1f)
            return CropRect(left, top, right, bottom)
        }

        /** Largest centered crop for a target ratio, useful for tests and UI helpers. */
        fun centered(imageWidth: Float, imageHeight: Float, aspect: CropAspect): CropRect {
            val state = EditCrop(aspect = aspect.id)
            return state.rect(imageWidth, imageHeight)
        }

        fun overlap(a: CropRect, b: CropRect): Float {
            val left = max(a.left, b.left)
            val top = max(a.top, b.top)
            val right = min(a.right, b.right)
            val bottom = min(a.bottom, b.bottom)
            return (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        }
    }
}
