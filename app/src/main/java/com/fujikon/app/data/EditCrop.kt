package com.fujikon.app.data

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * Geometry for a rotated crop. The output dimensions are in source pixel units,
 * and [cosAngle]/[sinAngle] map output pixels back into the source bitmap.
 */
data class CropGeometry(
    val outputWidth: Int,
    val outputHeight: Int,
    val cosAngle: Float,
    val sinAngle: Float,
    val targetAspect: Float
)

/**
 * Crop state stored with the rest of the non-destructive adjustments.
 *
 * Cropping is centered and non-destructive. [rotation] is a clockwise quarter
 * turn (0/90/180/270) and [straighten] is a fine correction angle. The renderer
 * automatically finds the largest crop that does not contain empty corners
 * after the image is rotated.
 */
data class EditCrop(
    val aspect: String = CropAspect.ORIGINAL.id,
    /** Clockwise quarter turns: 0, 90, 180 or 270 degrees. */
    val rotation: Int = 0,
    /** Fine straighten angle in degrees, [-45, 45]. */
    val straighten: Float = 0f
) {
    fun normalized(): EditCrop {
        val safeStraighten = if (straighten.isFinite()) {
            straighten.coerceIn(-STRAIGHTEN_RANGE, STRAIGHTEN_RANGE)
        } else {
            0f
        }
        val quarter = if (rotation == Int.MIN_VALUE) {
            0
        } else {
            ((rotation / 90f).roundToInt() % 4 + 4) % 4
        }
        return copy(
            aspect = CropAspect.fromId(aspect).id,
            rotation = quarter * 90,
            straighten = safeStraighten
        )
    }

    fun isFull(tolerance: Float = 0.001f): Boolean {
        val state = normalized()
        return state.aspect == CropAspect.ORIGINAL.id &&
            state.rotation == 0 &&
            abs(state.straighten) <= tolerance
    }

    /** Total clockwise angle, including the fine straighten correction. */
    fun effectiveAngleDegrees(): Float {
        val state = normalized()
        return state.rotation + state.straighten
    }

    /**
     * Axis-aligned crop rectangle for the selected ratio, before rotation or
     * straightening. Used for fast, pixel-exact crops when the angle is zero.
     */
    fun rect(imageWidth: Float, imageHeight: Float): CropRect {
        val safeWidth = imageWidth.coerceAtLeast(1f)
        val safeHeight = imageHeight.coerceAtLeast(1f)
        val imageAspect = safeWidth / safeHeight
        val targetAspect = CropAspect.fromId(aspect).ratio ?: imageAspect

        var width = 1f
        var height = 1f
        if (targetAspect > imageAspect) {
            // Taller target: full width, shorter height.
            height = imageAspect / targetAspect
        } else {
            // Wider target: full height, narrower width.
            width = targetAspect / imageAspect
        }

        val left = (1f - width) * 0.5f
        val top = (1f - height) * 0.5f
        return CropRect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = (left + width).coerceAtMost(1f),
            bottom = (top + height).coerceAtMost(1f)
        )
    }

    /**
     * Calculates the largest centered crop with the requested aspect that fits
     * inside the source after rotating by [effectiveAngleDegrees].
     */
    fun geometry(imageWidth: Int, imageHeight: Int): CropGeometry {
        val state = normalized()
        val width = max(1, imageWidth)
        val height = max(1, imageHeight)
        val angle = Math.toRadians((state.rotation + state.straighten).toDouble()).toFloat()
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val absCos = abs(cosAngle)
        val absSin = abs(sinAngle)

        val selected = CropAspect.fromId(state.aspect)
        val targetAspect = when {
            selected == CropAspect.ORIGINAL -> {
                if (state.rotation % 180 == 90) {
                    height.toFloat() / width
                } else {
                    width.toFloat() / height
                }
            }
            else -> selected.ratio ?: (width.toFloat() / height)
        }.coerceIn(MIN_ASPECT, MAX_ASPECT)

        val halfWidth = width * 0.5f
        val halfHeight = height * 0.5f
        val halfCropWidth = min(
            halfWidth / (absCos + absSin / targetAspect),
            halfHeight / (absSin + absCos / targetAspect)
        ).coerceAtLeast(0.5f)

        val outputWidth = (halfCropWidth * 2f).roundToInt()
            .coerceIn(1, max(width, height))
        val outputHeight = (outputWidth / targetAspect).roundToInt()
            .coerceIn(1, max(width, height))

        return CropGeometry(
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            cosAngle = cosAngle,
            sinAngle = sinAngle,
            targetAspect = targetAspect
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("aspect", aspect)
        put("rotation", rotation)
        put("straighten", straighten.toDouble())
    }

    companion object {
        const val STRAIGHTEN_RANGE = 45f
        private const val MIN_ASPECT = 0.01f
        private const val MAX_ASPECT = 100f
        private const val MIN_SIZE = 0.12f

        val Full = EditCrop()

        fun fromJson(json: JSONObject?): EditCrop {
            if (json == null) return Full
            return EditCrop(
                aspect = json.optString("aspect", CropAspect.ORIGINAL.id),
                rotation = json.optInt("rotation", 0),
                straighten = json.optDouble("straighten", 0.0).toFloat()
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
