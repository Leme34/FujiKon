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
 * Cropping is non-destructive. [rotation] is a clockwise quarter turn
 * (0/90/180/270) and [straighten] is a fine correction angle. When [customRect]
 * is null the renderer finds the largest centered crop that keeps empty
 * corners out; when it is set, that normalized rectangle (relative to the
 * rotated display frame) is used and can be panned / zoomed freely.
 */
data class EditCrop(
    val aspect: String = CropAspect.ORIGINAL.id,
    /** Clockwise quarter turns: 0, 90, 180 or 270 degrees. */
    val rotation: Int = 0,
    /** Fine straighten angle in degrees, [-45, 45]. */
    val straighten: Float = 0f,
    /** Free crop window in the straightened display frame; null = centered. */
    val customRect: CropRect? = null
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
            straighten = safeStraighten,
            customRect = customRect?.let { clampRect(it) }
        )
    }

    fun isFull(tolerance: Float = 0.001f): Boolean {
        val state = normalized()
        return state.aspect == CropAspect.ORIGINAL.id &&
            state.rotation == 0 &&
            abs(state.straighten) <= tolerance &&
            (state.customRect == null || state.customRect.isFull)
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
        val custom = customRect
        if (custom != null) return clampRect(custom)

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

    /** Display frame dimensions after the quarter turn. */
    fun displayWidth(imageWidth: Int, imageHeight: Int): Int =
        if (rotation % 180 == 90) imageHeight else imageWidth

    fun displayHeight(imageWidth: Int, imageHeight: Int): Int =
        if (rotation % 180 == 90) imageWidth else imageHeight

    /**
     * Largest centered rectangle for the current aspect in the straightened
     * display frame. This is the starting point for the interactive editor
     * when no free rectangle has been stored yet.
     */
    fun centeredRect(displayWidth: Float, displayHeight: Float): CropRect {
        val safeWidth = displayWidth.coerceAtLeast(1f)
        val safeHeight = displayHeight.coerceAtLeast(1f)
        val displayAspect = safeWidth / safeHeight
        val targetAspect = CropAspect.fromId(aspect).ratio ?: displayAspect
        var width = 1f
        var height = 1f
        if (targetAspect > displayAspect) {
            height = displayAspect / targetAspect
        } else {
            width = targetAspect / displayAspect
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

    /** Stored free rectangle, or the centered default for the current aspect. */
    fun effectiveRect(displayWidth: Float, displayHeight: Float): CropRect =
        customRect?.let { clampRect(it) } ?: centeredRect(displayWidth, displayHeight)

    /**
     * Extra scale needed for the (fine-rotated) display frame to stay covered
     * when the photo is straightened. Quarter turns already swap the frame
     * dimensions and therefore need no extra scale.
     */
    fun coverScale(imageWidth: Int, imageHeight: Int): Float =
        coverScale(
            straighten = normalized().straighten,
            width = displayWidth(imageWidth, imageHeight),
            height = displayHeight(imageWidth, imageHeight)
        )

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
        customRect?.let { rect ->
            put(
                "rect",
                JSONObject().apply {
                    put("left", rect.left.toDouble())
                    put("top", rect.top.toDouble())
                    put("right", rect.right.toDouble())
                    put("bottom", rect.bottom.toDouble())
                }
            )
        }
    }

    companion object {
        const val STRAIGHTEN_RANGE = 45f
        private const val MIN_ASPECT = 0.01f
        private const val MAX_ASPECT = 100f
        private const val MIN_SIZE = CropSpec.MIN_FRACTION

        val Full = EditCrop()

        fun fromJson(json: JSONObject?): EditCrop {
            if (json == null) return Full
            val rectJson = json.optJSONObject("rect")
            val customRect = rectJson?.let {
                CropRect(
                    left = it.optDouble("left", 0.0).toFloat(),
                    top = it.optDouble("top", 0.0).toFloat(),
                    right = it.optDouble("right", 1.0).toFloat(),
                    bottom = it.optDouble("bottom", 1.0).toFloat()
                )
            }
            return EditCrop(
                aspect = json.optString("aspect", CropAspect.ORIGINAL.id),
                rotation = json.optInt("rotation", 0),
                straighten = json.optDouble("straighten", 0.0).toFloat(),
                customRect = customRect
            ).normalized()
        }

        /**
         * Extra scale so a frame rotated by [straighten] still covers the
         * axis-aligned display area without exposing empty corners.
         */
        fun coverScale(straighten: Float, width: Int, height: Int): Float {
            if (width <= 0 || height <= 0) return 1f
            val angle = abs(straighten).coerceIn(0f, STRAIGHTEN_RANGE)
            if (angle < 0.01f) return 1f
            val radians = Math.toRadians(angle.toDouble())
            val cosA = cos(radians).toFloat()
            val sinA = sin(radians).toFloat()
            val ratio = width.toFloat() / height.toFloat()
            val aspect = max(ratio, 1f / max(ratio, 0.0001f))
            return cosA + sinA * aspect
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
