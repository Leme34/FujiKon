package com.fujikon.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val DEG2RAD = 0.017453292f

private fun safeAspect(aspect: Float): Float =
    if (aspect.isFinite() && aspect > 0.0001f) aspect else 1f

/**
 * A compact, zone-scoped subset of the global adjustments.
 *
 * Local layers deliberately support only the parameters that make sense on a
 * selection (brightness / contrast / zones / saturation / temperature /
 * clarity). Structural edits (crop, blur, portrait, tone curve, filter) stay
 * global by design — they cannot be meaningfully feathered into a mask.
 */
data class LocalParams(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val clarity: Float = 0f
) {
    fun normalized(): LocalParams = copy(
        brightness = brightness.coerceIn(-RANGE, RANGE),
        contrast = contrast.coerceIn(-RANGE, RANGE),
        highlights = highlights.coerceIn(-RANGE, RANGE),
        shadows = shadows.coerceIn(-RANGE, RANGE),
        saturation = saturation.coerceIn(-RANGE, RANGE),
        temperature = temperature.coerceIn(-RANGE, RANGE),
        clarity = clarity.coerceIn(-RANGE, RANGE)
    )

    fun isNeutral(tolerance: Float = 0.005f): Boolean =
        abs(brightness) <= tolerance && abs(contrast) <= tolerance &&
            abs(highlights) <= tolerance && abs(shadows) <= tolerance &&
            abs(saturation) <= tolerance && abs(temperature) <= tolerance &&
            abs(clarity) <= tolerance

    fun toJson(): JSONObject = JSONObject().apply {
        put("brightness", brightness.toDouble())
        put("contrast", contrast.toDouble())
        put("highlights", highlights.toDouble())
        put("shadows", shadows.toDouble())
        put("saturation", saturation.toDouble())
        put("temperature", temperature.toDouble())
        put("clarity", clarity.toDouble())
    }

    companion object {
        const val RANGE = 100f
        val Neutral = LocalParams()

        fun fromJson(json: JSONObject?): LocalParams {
            if (json == null) return Neutral
            return LocalParams(
                brightness = json.optDouble("brightness", 0.0).toFloat(),
                contrast = json.optDouble("contrast", 0.0).toFloat(),
                highlights = json.optDouble("highlights", 0.0).toFloat(),
                shadows = json.optDouble("shadows", 0.0).toFloat(),
                saturation = json.optDouble("saturation", 0.0).toFloat(),
                temperature = json.optDouble("temperature", 0.0).toFloat(),
                clarity = json.optDouble("clarity", 0.0).toFloat()
            ).normalized()
        }
    }
}

/**
 * One painted stroke. Coordinates are normalised (0..1) and stored as a flat
 * [x0, y0, x1, y1, ...] list so JSON stays small and backend-agnostic.
 */
data class BrushStroke(
    val points: List<Float> = emptyList(),
    val strength: Float = 1f
) {
    fun normalized(): BrushStroke = copy(
        points = points.map { it.coerceIn(0f, 1f) },
        strength = strength.coerceIn(0f, 1f)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("points", JSONArray().apply { points.forEach { put(it.toDouble()) } })
        put("strength", strength.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject?): BrushStroke {
            if (json == null) return BrushStroke()
            val array = json.optJSONArray("points") ?: JSONArray()
            val points = ArrayList<Float>(array.length())
            for (i in 0 until array.length()) points += array.optDouble(i, 0.0).toFloat()
            return BrushStroke(points, json.optDouble("strength", 1.0).toFloat()).normalized()
        }
    }
}

/**
 * The four selection shapes supported by the local adjustment editor.
 * All coordinates are normalised to the uncropped frame so preview and export
 * produce the identical mask.
 */
sealed class LocalMask {
    /** Snapseed-style control point: a soft Gaussian disc around a tapped spot. */
    data class ControlPoint(
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val radius: Float = 0.25f,
        val feather: Float = 0.65f
    ) : LocalMask()

    /** Linear gradient between two points; [feather] softens the transition. */
    data class LinearGradient(
        val startX: Float = 0.5f,
        val startY: Float = 0f,
        val endX: Float = 0.5f,
        val endY: Float = 1f,
        val feather: Float = 0.45f,
        val invert: Boolean = false
    ) : LocalMask()

    /** Elliptical radial gradient (Lightroom's radial filter). */
    data class RadialGradient(
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val radiusX: Float = 0.4f,
        val radiusY: Float = 0.4f,
        val feather: Float = 0.6f,
        val invert: Boolean = false,
        /** Screen-space rotation of the ellipse in degrees, around its centre. */
        val rotationDeg: Float = 0f
    ) : LocalMask() {
        /**
         * Normalised distance from a pixel to the ROTATED ellipse: < 1 means
         * inside. Shared by the renderer (raster) and tests so a rotated oval
         * behaves identically to the outline drawn on the preview. `aspect` is
         * frameWidth/frameHeight; rotating in height-normalised physical space
         * keeps the angle identical on non-square photos.
         */
        fun rotatedDistance(nx: Float, ny: Float, aspect: Float): Float {
            val a = safeAspect(aspect)
            val ux = (nx - x) * a
            val uy = ny - y
            val r = rotationDeg * DEG2RAD
            val c = cos(r); val s = sin(r)
            // Undo the screen rotation to get back into the ellipse's own axes.
            val lx = ux * c + uy * s
            val ly = -ux * s + uy * c
            val rx = radiusX.coerceIn(0.01f, 1.5f) * a
            val ry = radiusY.coerceIn(0.01f, 1.5f)
            return hypot(lx / rx, ly / ry)
        }
    }

    /** Free-hand painted strokes rasterised into a distance field. */
    data class Brush(
        val strokes: List<BrushStroke> = emptyList(),
        val radius: Float = 0.06f,
        val feather: Float = 0.5f,
        val invert: Boolean = false,
        /** Screen-space translation applied AFTER rotation (so drags follow the finger). */
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        /** Screen-space rotation around the stroke bounding-box centre, in degrees. */
        val rotationDeg: Float = 0f
    ) : LocalMask() {
        /**
         * Rotation centre, pinned to the FIRST stroke's bounding box.
         * Pinning it (instead of recomputing over all strokes) means appending
         * a second/third stroke never shifts the pixels already painted. An
         * empty brush falls back to the frame centre; a brush cannot be spun
         * before it has any strokes anyway (no rotation handle is shown).
         */
        fun centre(): Pair<Float, Float> {
            val p = strokes.firstOrNull()?.points ?: return 0.5f to 0.5f
            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            var i = 0
            while (i + 1 < p.size) {
                minX = minOf(minX, p[i]); maxX = maxOf(maxX, p[i])
                minY = minOf(minY, p[i + 1]); maxY = maxOf(maxY, p[i + 1])
                i += 2
            }
            if (minX == Float.POSITIVE_INFINITY) return 0.5f to 0.5f
            return (minX + maxX) * 0.5f to (minY + maxY) * 0.5f
        }

        /** Local stroke point -> on-screen normalised point (rotate, then translate). */
        fun mapPoint(px: Float, py: Float, aspect: Float): Pair<Float, Float> {
            val a = safeAspect(aspect)
            val (cx, cy) = centre()
            val r = rotationDeg * DEG2RAD
            val c = cos(r); val s = sin(r)
            val ux = (px - cx) * a
            val uy = py - cy
            val rx = ux * c - uy * s
            val ry = ux * s + uy * c
            return (cx + rx / a + offsetX) to (cy + ry + offsetY)
        }

        /** On-screen normalised point -> local stroke point (inverse of [mapPoint]). */
        fun unmapPoint(nx: Float, ny: Float, aspect: Float): Pair<Float, Float> {
            val a = safeAspect(aspect)
            val (cx, cy) = centre()
            val r = rotationDeg * DEG2RAD
            val c = cos(r); val s = sin(r)
            val ux = (nx - offsetX - cx) * a
            val uy = ny - offsetY - cy
            val lx = ux * c + uy * s
            val ly = -ux * s + uy * c
            return (cx + lx / a) to (cy + ly)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@LocalMask) {
            is ControlPoint -> {
                put("type", "point")
                put("x", x.toDouble()); put("y", y.toDouble())
                put("radius", radius.toDouble()); put("feather", feather.toDouble())
            }
            is LinearGradient -> {
                put("type", "linear")
                put("startX", startX.toDouble()); put("startY", startY.toDouble())
                put("endX", endX.toDouble()); put("endY", endY.toDouble())
                put("feather", feather.toDouble()); put("invert", invert)
            }
            is RadialGradient -> {
                put("type", "radial")
                put("x", x.toDouble()); put("y", y.toDouble())
                put("radiusX", radiusX.toDouble()); put("radiusY", radiusY.toDouble())
                put("feather", feather.toDouble()); put("invert", invert)
                put("rotation", rotationDeg.toDouble())
            }
            is Brush -> {
                put("type", "brush")
                put("strokes", JSONArray().apply { strokes.forEach { put(it.toJson()) } })
                put("radius", radius.toDouble()); put("feather", feather.toDouble())
                put("invert", invert)
                put("offsetX", offsetX.toDouble()); put("offsetY", offsetY.toDouble())
                put("rotation", rotationDeg.toDouble())
            }
        }
    }

    companion object {
        fun fromJson(json: JSONObject?): LocalMask {
            if (json == null) return ControlPoint()
            return when (json.optString("type")) {
                "linear" -> LinearGradient(
                    json.optDouble("startX", 0.5).toFloat(),
                    json.optDouble("startY", 0.0).toFloat(),
                    json.optDouble("endX", 0.5).toFloat(),
                    json.optDouble("endY", 1.0).toFloat(),
                    json.optDouble("feather", 0.45).toFloat(),
                    json.optBoolean("invert", false)
                )
                "radial" -> RadialGradient(
                    x = json.optDouble("x", 0.5).toFloat(),
                    y = json.optDouble("y", 0.5).toFloat(),
                    radiusX = json.optDouble("radiusX", 0.4).toFloat(),
                    radiusY = json.optDouble("radiusY", 0.4).toFloat(),
                    feather = json.optDouble("feather", 0.6).toFloat(),
                    invert = json.optBoolean("invert", false),
                    rotationDeg = json.optDouble("rotation", 0.0).toFloat()
                )
                "brush" -> {
                    val array = json.optJSONArray("strokes") ?: JSONArray()
                    val strokes = ArrayList<BrushStroke>(array.length())
                    for (i in 0 until array.length()) {
                        strokes += BrushStroke.fromJson(array.optJSONObject(i))
                    }
                    Brush(
                        strokes = strokes,
                        radius = json.optDouble("radius", 0.06).toFloat(),
                        feather = json.optDouble("feather", 0.5).toFloat(),
                        invert = json.optBoolean("invert", false),
                        offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                        offsetY = json.optDouble("offsetY", 0.0).toFloat(),
                        rotationDeg = json.optDouble("rotation", 0.0).toFloat()
                    )
                }
                else -> ControlPoint(
                    json.optDouble("x", 0.5).toFloat(),
                    json.optDouble("y", 0.5).toFloat(),
                    json.optDouble("radius", 0.25).toFloat(),
                    json.optDouble("feather", 0.65).toFloat()
                )
            }
        }
    }
}

/** One editable local adjustment layer (mask + its scoped parameters). */
data class LocalLayer(
    val id: String,
    val name: String = "局部",
    val mask: LocalMask = LocalMask.ControlPoint(),
    val params: LocalParams = LocalParams.Neutral,
    val strength: Float = 100f,
    val enabled: Boolean = true
) {
    fun normalized(): LocalLayer = copy(
        strength = strength.coerceIn(0f, 100f),
        params = params.normalized()
    )

    fun active(): Boolean = enabled && strength > 0.001f && !params.isNeutral()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("mask", mask.toJson())
        put("params", params.toJson())
        put("strength", strength.toDouble())
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(json: JSONObject?): LocalLayer? {
            if (json == null) return null
            val id = json.optString("id", "")
            if (id.isBlank()) return null
            return LocalLayer(
                id = id,
                name = json.optString("name", "局部"),
                mask = LocalMask.fromJson(json.optJSONObject("mask")),
                params = LocalParams.fromJson(json.optJSONObject("params")),
                strength = json.optDouble("strength", 100.0).toFloat(),
                enabled = json.optBoolean("enabled", true)
            ).normalized()
        }
    }
}

/**
 * All local layers attached to a photo. Stored inside [EditAdjustments] so the
 * existing preset / history / export JSON paths carry them automatically.
 */
data class LocalAdjustments(
    val layers: List<LocalLayer> = emptyList()
) {
    fun normalized(): LocalAdjustments = LocalAdjustments(
        layers.filter { it.id.isNotBlank() }.map { it.normalized() }.take(MAX_LAYERS)
    )

    fun isNeutral(): Boolean = layers.none { it.active() }

    /** Layers that actually affect pixels, in draw order. */
    fun activeLayers(): List<LocalLayer> = layers.filter { it.active() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("layers", JSONArray().apply { layers.forEach { put(it.toJson()) } })
    }

    companion object {
        const val MAX_LAYERS = 12
        val Neutral = LocalAdjustments()

        fun fromJson(json: JSONObject?): LocalAdjustments {
            if (json == null) return Neutral
            val array = json.optJSONArray("layers") ?: JSONArray()
            val layers = ArrayList<LocalLayer>(array.length())
            for (i in 0 until array.length()) {
                LocalLayer.fromJson(array.optJSONObject(i))?.let { layers += it }
            }
            return LocalAdjustments(layers).normalized()
        }
    }
}
