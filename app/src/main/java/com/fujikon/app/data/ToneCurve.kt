package com.fujikon.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** A point on the user-editable RGB tone curve. Both axes are normalised to 0..1. */
data class ToneCurvePoint(
    val x: Float,
    val y: Float
)

/**
 * A Lightroom-style master RGB tone curve.
 *
 * This curve is layered on top of a Nikon Picture Control, after the preset's
 * own LUT, contrast, brightness and shadow/highlight adjustments. It therefore
 * never overwrites the original NCP/NP3 data.
 */
data class ToneCurve(
    val points: List<ToneCurvePoint> = listOf(
        ToneCurvePoint(0f, 0f),
        ToneCurvePoint(1f, 1f)
    )
) {
    /** Sorted, clamped points with guaranteed endpoints at x=0 and x=1. */
    val normalizedPoints: List<ToneCurvePoint> = normalize(points)

    /** Evaluates the curve using monotone cubic Hermite interpolation. */
    fun apply(input: Float): Float {
        val curve = normalizedPoints
        val x = input.coerceIn(0f, 1f)
        if (curve.size == 2) {
            val t = (x - curve[0].x) / (curve[1].x - curve[0].x)
            return lerp(curve[0].y, curve[1].y, t).coerceIn(0f, 1f)
        }

        var segment = 0
        while (segment < curve.lastIndex - 1 && x > curve[segment + 1].x) {
            segment++
        }
        if (x <= curve.first().x) return curve.first().y
        if (x >= curve.last().x) return curve.last().y

        val x0 = curve[segment].x
        val x1 = curve[segment + 1].x
        val h = (x1 - x0).coerceAtLeast(1e-6f)
        val t = ((x - x0) / h).coerceIn(0f, 1f)
        val slopes = tangents(curve)
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        val value = h00 * curve[segment].y +
            h10 * h * slopes[segment] +
            h01 * curve[segment + 1].y +
            h11 * h * slopes[segment + 1]
        return value.coerceIn(0f, 1f)
    }

    /** True when the curve is visually indistinguishable from y = x. */
    fun isLinear(tolerance: Float = 0.002f): Boolean {
        if (normalizedPoints.size == 2 &&
            abs(normalizedPoints[0].y) <= tolerance &&
            abs(normalizedPoints[1].y - 1f) <= tolerance
        ) return true
        return (0..64).all { i ->
            val x = i / 64f
            abs(apply(x) - x) <= tolerance
        }
    }

    fun withPoint(index: Int, x: Float, y: Float): ToneCurve {
        if (index !in normalizedPoints.indices) return this
        val updated = normalizedPoints.toMutableList()
        updated[index] = ToneCurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        return ToneCurve(updated)
    }

    fun addPoint(x: Float, y: Float): ToneCurve =
        ToneCurve(normalizedPoints + ToneCurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))

    fun removePoint(index: Int): ToneCurve {
        val point = normalizedPoints.getOrNull(index) ?: return this
        if (point.x <= 1e-4f || point.x >= 1f - 1e-4f) return this
        return ToneCurve(normalizedPoints.filterIndexed { i, _ -> i != index })
    }

    fun previewPoints(count: Int = 64): List<Pair<Float, Float>> {
        val safeCount = count.coerceAtLeast(2)
        return (0..safeCount).map { i ->
            val x = i / safeCount.toFloat()
            x to apply(x)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("points", JSONArray().apply {
            normalizedPoints.forEach { point ->
                put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
            }
        })
    }

    companion object {
        fun linear(): ToneCurve = ToneCurve()

        fun fromJson(json: JSONObject): ToneCurve? = runCatching {
            val array = json.optJSONArray("points") ?: return null
            val points = (0 until array.length()).mapNotNull { i ->
                val pair = array.optJSONArray(i) ?: return@mapNotNull null
                ToneCurvePoint(pair.optDouble(0, 0.0).toFloat(), pair.optDouble(1, 0.0).toFloat())
            }
            if (points.isEmpty()) null else ToneCurve(points)
        }.getOrNull()

        private fun normalize(input: List<ToneCurvePoint>): List<ToneCurvePoint> {
            if (input.isEmpty()) return listOf(ToneCurvePoint(0f, 0f), ToneCurvePoint(1f, 1f))
            val sorted = input
                .map { ToneCurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                .sortedBy { it.x }
            val deduped = mutableListOf<ToneCurvePoint>()
            sorted.forEach { point ->
                if (deduped.isEmpty() || point.x - deduped.last().x > 1e-4f) {
                    deduped.add(point)
                } else {
                    // A dragged point wins over an older point at the same x.
                    deduped[deduped.lastIndex] = point
                }
            }
            if (deduped.first().x > 1e-4f) {
                deduped.add(0, ToneCurvePoint(0f, 0f))
            } else {
                deduped[0] = deduped[0].copy(x = 0f)
            }
            if (deduped.last().x < 1f - 1e-4f) {
                deduped.add(ToneCurvePoint(1f, 1f))
            } else {
                deduped[deduped.lastIndex] = deduped.last().copy(x = 1f)
            }
            return deduped
        }

        /** Fritsch-Carlson tangents keep each segment monotone and overshoot-free. */
        private fun tangents(points: List<ToneCurvePoint>): FloatArray {
            val n = points.size
            val slopes = FloatArray(n - 1) { i ->
                (points[i + 1].y - points[i].y) /
                    (points[i + 1].x - points[i].x).coerceAtLeast(1e-6f)
            }
            val tangents = FloatArray(n)
            tangents[0] = slopes[0]
            tangents[n - 1] = slopes[n - 2]
            for (i in 1 until n - 1) {
                val before = slopes[i - 1]
                val after = slopes[i]
                tangents[i] = if (before * after <= 0f) 0f else (before + after) * 0.5f
            }
            for (i in 0 until n - 1) {
                val delta = slopes[i]
                if (abs(delta) < 1e-6f) {
                    tangents[i] = 0f
                    tangents[i + 1] = 0f
                    continue
                }
                val a = tangents[i] / delta
                val b = tangents[i + 1] / delta
                if (a < 0f) tangents[i] = 0f
                if (b < 0f) tangents[i + 1] = 0f
                val length = a * a + b * b
                if (length > 9f) {
                    val scale = 3f / sqrt(length)
                    tangents[i] = scale * a * delta
                    tangents[i + 1] = scale * b * delta
                }
            }
            return tangents
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}
