package com.fujikon.app.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightroom-style crop and straighten geometry.
 *
 * [left]/[top]/[right]/[bottom] are normalised fractions of the straightened
 * image frame (0..1). [angle] rotates the photo behind that frame, and
 * [aspectRatio] locks the output width/height ratio (in pixels) when non-null.
 * Image movement and pinch zoom in the editor are expressed as changes to this
 * rectangle, so the same values can be reused at preview and export resolution.
 */
data class CropSpec(
    val angle: Float = 0f,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val aspectRatio: Float? = null
) {
    val widthFraction: Float get() = (right - left).coerceAtLeast(0f)
    val heightFraction: Float get() = (bottom - top).coerceAtLeast(0f)

    val isIdentity: Boolean
        get() = abs(angle) < ANGLE_EPSILON &&
            left <= RECT_EPSILON &&
            top <= RECT_EPSILON &&
            right >= 1f - RECT_EPSILON &&
            bottom >= 1f - RECT_EPSILON

    /**
     * Clamps the rectangle to the image frame and keeps the minimum crop size.
     * A locked aspect ratio is re-applied around the current centre.
     */
    fun normalized(imageAspect: Float = 1f): CropSpec {
        var l = left.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        if (r < l) {
            val swap = l
            l = r
            r = swap
        }
        var t = top.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (b < t) {
            val swap = t
            t = b
            b = swap
        }

        if (r - l < MIN_FRACTION) {
            if (l + MIN_FRACTION <= 1f) {
                r = l + MIN_FRACTION
            } else {
                r = 1f
                l = 1f - MIN_FRACTION
            }
        }
        if (b - t < MIN_FRACTION) {
            if (t + MIN_FRACTION <= 1f) {
                b = t + MIN_FRACTION
            } else {
                b = 1f
                t = 1f - MIN_FRACTION
            }
        }

        val locked = aspectRatio?.takeIf { it.isFinite() && it > 0f }
        if (locked != null) {
            val ratio = locked / safeAspect(imageAspect)
            val centerX = (l + r) * 0.5f
            val centerY = (t + b) * 0.5f
            var w = r - l
            var h = b - t
            if (w / h > ratio) w = h * ratio else h = w / ratio
            if (w < MIN_FRACTION || h < MIN_FRACTION) {
                if (ratio >= 1f) {
                    w = MIN_FRACTION * ratio
                    h = MIN_FRACTION
                } else {
                    w = MIN_FRACTION
                    h = MIN_FRACTION / ratio
                }
            }
            w = min(w, 1f)
            h = min(h, 1f)
            l = (centerX - w * 0.5f).coerceIn(0f, 1f - w)
            t = (centerY - h * 0.5f).coerceIn(0f, 1f - h)
            r = l + w
            b = t + h
        }

        return copy(
            angle = angle.coerceIn(-MAX_ANGLE, MAX_ANGLE),
            left = l,
            top = t,
            right = r,
            bottom = b
        )
    }

    /** Moves the crop window by a normalised delta and keeps it inside the frame. */
    fun translate(dx: Float, dy: Float, imageAspect: Float = 1f): CropSpec {
        val w = widthFraction
        val h = heightFraction
        val nextLeft = (left + dx).coerceIn(0f, 1f - w)
        val nextTop = (top + dy).coerceIn(0f, 1f - h)
        return copy(left = nextLeft, right = nextLeft + w, top = nextTop, bottom = nextTop + h)
            .normalized(imageAspect)
    }

    /**
     * Zooms the crop window around a normalised focus point. Pinching the
     * preview therefore tightens or loosens the crop just like Lightroom.
     */
    fun zoom(factor: Float, focusX: Float, focusY: Float, imageAspect: Float = 1f): CropSpec {
        if (!factor.isFinite() || factor <= 0f) return this
        val f = factor.coerceIn(0.2f, 5f)
        var w = (widthFraction * f).coerceIn(MIN_FRACTION, 1f)
        var h = (heightFraction * f).coerceIn(MIN_FRACTION, 1f)

        val locked = aspectRatio?.takeIf { it.isFinite() && it > 0f }
        if (locked != null) {
            val ratio = locked / safeAspect(imageAspect)
            if (w / h > ratio) w = h * ratio else h = w / ratio
            if (w > 1f) {
                w = 1f
                h = w / ratio
            }
            if (h > 1f) {
                h = 1f
                w = h * ratio
            }
        }

        val fx = focusX.coerceIn(0f, 1f)
        val fy = focusY.coerceIn(0f, 1f)
        var nextLeft = fx - (fx - left) * (w / widthFraction.coerceAtLeast(MIN_FRACTION))
        var nextTop = fy - (fy - top) * (h / heightFraction.coerceAtLeast(MIN_FRACTION))
        nextLeft = nextLeft.coerceIn(0f, 1f - w)
        nextTop = nextTop.coerceIn(0f, 1f - h)
        return copy(left = nextLeft, right = nextLeft + w, top = nextTop, bottom = nextTop + h)
            .normalized(imageAspect)
    }

    /**
     * Resizes one corner by a normalised delta. The opposite corner stays
     * anchored and a locked aspect ratio is respected.
     */
    fun resize(
        corner: CropCorner,
        dx: Float,
        dy: Float,
        imageAspect: Float = 1f
    ): CropSpec {
        val movesLeft = corner == CropCorner.TOP_LEFT || corner == CropCorner.BOTTOM_LEFT
        val movesTop = corner == CropCorner.TOP_LEFT || corner == CropCorner.TOP_RIGHT
        val anchorX = if (movesLeft) right else left
        val anchorY = if (movesTop) bottom else top
        val maxWidth = if (movesLeft) anchorX else 1f - anchorX
        val maxHeight = if (movesTop) anchorY else 1f - anchorY

        var freeX = (if (movesLeft) left else right) + dx
        var freeY = (if (movesTop) top else bottom) + dy
        freeX = freeX.coerceIn(0f, 1f)
        freeY = freeY.coerceIn(0f, 1f)

        var w = abs(freeX - anchorX).coerceIn(0f, maxWidth)
        var h = abs(freeY - anchorY).coerceIn(0f, maxHeight)
        if (w < MIN_FRACTION) w = min(MIN_FRACTION, maxWidth)
        if (h < MIN_FRACTION) h = min(MIN_FRACTION, maxHeight)

        val locked = aspectRatio?.takeIf { it.isFinite() && it > 0f }
        if (locked != null) {
            val ratio = locked / safeAspect(imageAspect)
            if (w / h > ratio) w = h * ratio else h = w / ratio
            if (w > maxWidth) {
                w = maxWidth
                h = w / ratio
            }
            if (h > maxHeight) {
                h = maxHeight
                w = h * ratio
            }
        } else {
            w = w.coerceIn(min(MIN_FRACTION, maxWidth), maxWidth)
            h = h.coerceIn(min(MIN_FRACTION, maxHeight), maxHeight)
        }

        val nextLeft = if (movesLeft) anchorX - w else anchorX
        val nextTop = if (movesTop) anchorY - h else anchorY
        return copy(
            left = nextLeft,
            top = nextTop,
            right = nextLeft + w,
            bottom = nextTop + h
        ).normalized(imageAspect)
    }

    /**
     * Applies an output aspect ratio, shrinking the current window around its
     * centre so the result stays inside the previous crop. A null [target]
     * unlocks the ratio (free crop).
     */
    fun withAspectRatio(target: Float?, imageAspect: Float = 1f): CropSpec {
        if (target == null || !target.isFinite() || target <= 0f) {
            return copy(aspectRatio = null).normalized(imageAspect)
        }
        val ratio = target / safeAspect(imageAspect)
        val centerX = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f
        var w = widthFraction
        var h = heightFraction
        if (w / h > ratio) w = h * ratio else h = w / ratio
        w = w.coerceAtMost(1f)
        h = h.coerceAtMost(1f)
        if (w < MIN_FRACTION || h < MIN_FRACTION) {
            if (ratio >= 1f) {
                w = MIN_FRACTION * ratio
                h = MIN_FRACTION
            } else {
                w = MIN_FRACTION
                h = MIN_FRACTION / ratio
            }
        }
        val nextLeft = (centerX - w * 0.5f).coerceIn(0f, 1f - w)
        val nextTop = (centerY - h * 0.5f).coerceIn(0f, 1f - h)
        return copy(
            left = nextLeft,
            top = nextTop,
            right = nextLeft + w,
            bottom = nextTop + h,
            aspectRatio = target
        ).normalized(imageAspect)
    }

    companion object {
        const val MAX_ANGLE = 45f
        const val MIN_FRACTION = 0.05f
        private const val ANGLE_EPSILON = 0.01f
        private const val RECT_EPSILON = 0.001f

        val None = CropSpec()

        fun safeAspect(aspect: Float): Float =
            if (aspect.isFinite() && aspect > 0f) max(aspect, 0.0001f) else 1f
    }
}

enum class CropCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}
