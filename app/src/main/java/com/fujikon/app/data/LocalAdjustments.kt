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
 * clarity). Structural edits (crop, blur, tone curve, filter) stay global by
 * design — they cannot be meaningfully feathered into a mask.
 *
 * 1.6.58 例外：当图层蒙版为「选择主体」时，[LocalLayer.portrait] 额外提供
 * 一组局部人像美颜（磨皮/美白/红润/立体），由渲染器把美颜限定在该选区内。
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
 * One manual refinement stroke painted ON TOP of an automatic selection.
 * [points] is a flat normalised `[x0, y0, x1, y1, ...]` list; [erase] chooses
 * "add to selection" vs "subtract from selection" (Lightroom's +/- brush).
 */
data class RefineStroke(
    val points: List<Float> = emptyList(),
    val radius: Float = 0.035f,
    val feather: Float = 0.5f,
    val erase: Boolean = false,
    val strength: Float = 1f
) {
    fun normalized(): RefineStroke = copy(
        points = points.map { it.coerceIn(0f, 1f) },
        radius = radius.coerceIn(0.005f, 0.4f),
        feather = feather.coerceIn(0.05f, 1f),
        strength = strength.coerceIn(0f, 1f)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("points", JSONArray().apply { points.forEach { put(it.toDouble()) } })
        put("radius", radius.toDouble())
        put("feather", feather.toDouble())
        put("erase", erase)
        put("strength", strength.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject?): RefineStroke {
            if (json == null) return RefineStroke()
            val array = json.optJSONArray("points") ?: JSONArray()
            val pts = ArrayList<Float>(array.length())
            for (i in 0 until array.length()) pts += array.optDouble(i, 0.0).toFloat()
            return RefineStroke(
                pts, json.optDouble("radius", 0.035).toFloat(),
                json.optDouble("feather", 0.5).toFloat(),
                json.optBoolean("erase", false),
                json.optDouble("strength", 1.0).toFloat()
            ).normalized()
        }
    }
}

/**
 * One stroke of the mask ERASER: a free-hand path that removes scope from the
 * CURRENT layer, for EVERY mask kind (control point / linear / radial / brush
 * and the three AI selections). Unlike [RefineStroke], which only refines a
 * content-aware selection, this lives on [LocalLayer] so the same eraser works
 * on a plain gradient too. [points] is a flat normalised `[x0,y0,x1,y1,...]`.
 */
data class EraserStroke(
    val points: List<Float> = emptyList(),
    val radius: Float = 0.045f,
    val feather: Float = 0.5f,
    val strength: Float = 1f
) {
    fun normalized(): EraserStroke = copy(
        points = points.map { it.coerceIn(0f, 1f) },
        radius = radius.coerceIn(0.005f, 0.4f),
        feather = feather.coerceIn(0.05f, 1f),
        strength = strength.coerceIn(0f, 1f)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("points", JSONArray().apply { points.forEach { put(it.toDouble()) } })
        put("radius", radius.toDouble())
        put("feather", feather.toDouble())
        put("strength", strength.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject?): EraserStroke {
            if (json == null) return EraserStroke()
            val array = json.optJSONArray("points") ?: JSONArray()
            val pts = ArrayList<Float>(array.length())
            for (i in 0 until array.length()) pts += array.optDouble(i, 0.0).toFloat()
            return EraserStroke(
                pts,
                json.optDouble("radius", 0.045).toFloat(),
                json.optDouble("feather", 0.5).toFloat(),
                json.optDouble("strength", 1.0).toFloat()
            ).normalized()
        }
    }
}

internal fun JSONArray.toEraserStrokes(): List<EraserStroke> {
    val out = ArrayList<EraserStroke>(length())
    for (i in 0 until length()) out += EraserStroke.fromJson(optJSONObject(i))
    return out
}

internal fun List<EraserStroke>.toEraserJsonArray(): JSONArray =
    JSONArray().apply { forEach { put(it.toJson()) } }

/** An extra eyedropper sample for the colour-range tool. */
data class ColorSample(
    val hue: Float, val sat: Float, val lum: Float,
    /** true = add this colour, false = exclude it (Lightroom +/- dropper). */
    val add: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hue", hue.toDouble()); put("sat", sat.toDouble())
        put("lum", lum.toDouble()); put("add", add)
    }

    companion object {
        fun fromJson(json: JSONObject?): ColorSample? {
            if (json == null) return null
            return ColorSample(
                json.optDouble("hue", 0.0).toFloat(),
                json.optDouble("sat", 0.5).toFloat(),
                json.optDouble("lum", 0.5).toFloat(),
                json.optBoolean("add", true)
            )
        }
    }
}

/**
 * Shared refinement parameters for all three content-aware selections. Kept in
 * one object so "select subject / sky / colour range" expose the same finishing
 * controls (add/erase brush, edge shift, smoothing, edge-aware alignment).
 */
data class SelectionRefine(
    val strokes: List<RefineStroke> = emptyList(),
    /** -1..1: negative shrinks the selection, positive expands it. */
    val edgeShift: Float = 0f,
    /** 0..1: extra smoothing of the selection boundary. */
    val smooth: Float = 0.2f,
    /** Guided-filter edge alignment against the original photo. */
    val detectEdges: Boolean = true
) {
    fun normalized(): SelectionRefine = copy(
        strokes = strokes.map { it.normalized() }.take(64),
        edgeShift = edgeShift.coerceIn(-1f, 1f),
        smooth = smooth.coerceIn(0f, 1f)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("strokes", JSONArray().apply { strokes.forEach { put(it.toJson()) } })
        put("edgeShift", edgeShift.toDouble())
        put("smooth", smooth.toDouble())
        put("detectEdges", detectEdges)
    }

    companion object {
        val Default = SelectionRefine()

        fun fromJson(json: JSONObject?): SelectionRefine {
            if (json == null) return Default
            val array = json.optJSONArray("strokes") ?: JSONArray()
            val list = ArrayList<RefineStroke>(array.length())
            for (i in 0 until array.length()) list += RefineStroke.fromJson(array.optJSONObject(i))
            return SelectionRefine(
                list,
                json.optDouble("edgeShift", 0.0).toFloat(),
                json.optDouble("smooth", 0.2).toFloat(),
                json.optBoolean("detectEdges", true)
            ).normalized()
        }
    }
}

internal fun JSONArray.toColorSamples(): List<ColorSample> {
    val out = ArrayList<ColorSample>(length())
    for (i in 0 until length()) ColorSample.fromJson(optJSONObject(i))?.let { out += it }
    return out
}

internal fun List<ColorSample>.toJsonArray(): JSONArray =
    JSONArray().apply { forEach { put(it.toJson()) } }

/**
 * The selection shapes supported by the local adjustment editor.
 *
 * Four are GEOMETRIC (a soft shape described by a handful of numbers). Three
 * are CONTENT-AWARE: they store only the generation parameters + an optional
 * seed point, and the pixel-accurate soft mask is REBUILT from the untouched
 * original photo at render time by [com.fujikon.app.render.SelectionMaskGenerator].
 * No FloatArray is ever persisted, so preview and export stay identical and
 * old presets keep working (unknown "type" falls back to a control point).
 *
 * All coordinates are normalised to the uncropped frame so preview and export
 * produce the identical mask.
 */
sealed class LocalMask {
    /** Snapseed-style control point: a soft Gaussian disc around a tapped spot. */
    data class ControlPoint(
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val radius: Float = 0.25f,
        val feather: Float = 0.65f,
        /** 1.6.58 反选：把软圆盘内/外的作用域对调，与其余蒙版类型一致。 */
        val invert: Boolean = false
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

    /**
     * Select the main subject. Grows a region out of [seedX]/[seedY] using a
     * foreground/background colour model, edge barriers, skin/centre priors
     * and a connected-component pass, so the selection follows object edges
     * instead of being a guessed centre disc. Tap the photo to move the seed.
     */
    data class SubjectMask(
        val seedX: Float = 0.5f,
        val seedY: Float = 0.52f,
        /** 0..1: how loose the colour / texture similarity test is. */
        val tolerance: Float = 0.5f,
        /** 0..1: softness of the selection boundary. */
        val feather: Float = 0.35f,
        val invert: Boolean = false,
        /** Manual +/- brush, edge shift and edge-aware alignment. */
        val refine: SelectionRefine = SelectionRefine.Default
    ) : LocalMask()

    /**
     * Select the sky. Detects a dynamic horizon from row-wise sky likelihood,
     * then flood-fills from the TOP edge with a colour-distance test and keeps
     * only the component connected to the top — so blue clothing, water and
     * reflections are not swept in.
     */
    data class SkyMask(
        /** 0..1: colour tolerance used by the flood fill. */
        val tolerance: Float = 0.5f,
        /** 0..1: softness of the horizon / cloud boundary. */
        val feather: Float = 0.35f,
        val invert: Boolean = false,
        val refine: SelectionRefine = SelectionRefine.Default
    ) : LocalMask()

    /**
     * Select a colour range (magic wand). [targetHue]/[targetSat]/[targetLum]
     * are sampled from the ORIGINAL, unedited photo when the user taps it, so
     * an already-applied grade cannot drift the picked colour. [contiguous]
     * restricts the result to the connected region containing the seed.
     */
    data class ColorRangeMask(
        val seedX: Float = 0.5f,
        val seedY: Float = 0.5f,
        val targetHue: Float = 210f,
        val targetSat: Float = 0.5f,
        val targetLum: Float = 0.5f,
        /** Hue half-width in degrees (5..90). */
        val hueRange: Float = 24f,
        /** 0..1: overall colour-distance tolerance. */
        val tolerance: Float = 0.5f,
        /** Grey / near-neutral pixels below this saturation are excluded. */
        val minSaturation: Float = 0.08f,
        val contiguous: Boolean = true,
        /** 0..1: softness of the selection boundary. */
        val feather: Float = 0.3f,
        val invert: Boolean = false,
        /** Extra eyedropper samples (add / exclude colours). */
        val samples: List<ColorSample> = emptyList(),
        val refine: SelectionRefine = SelectionRefine.Default
    ) : LocalMask()

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@LocalMask) {
            is ControlPoint -> {
                put("type", "point")
                put("x", x.toDouble()); put("y", y.toDouble())
                put("radius", radius.toDouble()); put("feather", feather.toDouble())
                put("invert", invert)
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
            is SubjectMask -> {
                put("type", "subject")
                put("seedX", seedX.toDouble()); put("seedY", seedY.toDouble())
                put("tolerance", tolerance.toDouble()); put("feather", feather.toDouble())
                put("invert", invert)
                put("refine", refine.toJson())
            }
            is SkyMask -> {
                put("type", "sky")
                put("tolerance", tolerance.toDouble()); put("feather", feather.toDouble())
                put("invert", invert)
                put("refine", refine.toJson())
            }
            is ColorRangeMask -> {
                put("type", "colorRange")
                put("seedX", seedX.toDouble()); put("seedY", seedY.toDouble())
                put("targetHue", targetHue.toDouble()); put("targetSat", targetSat.toDouble())
                put("targetLum", targetLum.toDouble()); put("hueRange", hueRange.toDouble())
                put("tolerance", tolerance.toDouble())
                put("minSaturation", minSaturation.toDouble())
                put("contiguous", contiguous)
                put("feather", feather.toDouble()); put("invert", invert)
                put("samples", samples.toJsonArray())
                put("refine", refine.toJson())
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
                "subject" -> SubjectMask(
                    seedX = json.optDouble("seedX", 0.5).toFloat(),
                    seedY = json.optDouble("seedY", 0.52).toFloat(),
                    tolerance = json.optDouble("tolerance", 0.5).toFloat(),
                    feather = json.optDouble("feather", 0.35).toFloat(),
                    invert = json.optBoolean("invert", false),
                    refine = SelectionRefine.fromJson(json.optJSONObject("refine"))
                )
                "sky" -> SkyMask(
                    tolerance = json.optDouble("tolerance", 0.5).toFloat(),
                    feather = json.optDouble("feather", 0.35).toFloat(),
                    invert = json.optBoolean("invert", false),
                    refine = SelectionRefine.fromJson(json.optJSONObject("refine"))
                )
                "colorRange" -> ColorRangeMask(
                    seedX = json.optDouble("seedX", 0.5).toFloat(),
                    seedY = json.optDouble("seedY", 0.5).toFloat(),
                    targetHue = json.optDouble("targetHue", 210.0).toFloat(),
                    targetSat = json.optDouble("targetSat", 0.5).toFloat(),
                    targetLum = json.optDouble("targetLum", 0.5).toFloat(),
                    hueRange = json.optDouble("hueRange", 24.0).toFloat(),
                    tolerance = json.optDouble("tolerance", 0.5).toFloat(),
                    minSaturation = json.optDouble("minSaturation", 0.08).toFloat(),
                    contiguous = json.optBoolean("contiguous", true),
                    feather = json.optDouble("feather", 0.3).toFloat(),
                    invert = json.optBoolean("invert", false),
                    samples = (json.optJSONArray("samples") ?: JSONArray()).toColorSamples(),
                    refine = SelectionRefine.fromJson(json.optJSONObject("refine"))
                )
                else -> ControlPoint(
                    json.optDouble("x", 0.5).toFloat(),
                    json.optDouble("y", 0.5).toFloat(),
                    json.optDouble("radius", 0.25).toFloat(),
                    json.optDouble("feather", 0.65).toFloat(),
                    json.optBoolean("invert", false)
                )
            }
        }
    }
}

/** True for the three content-aware, pixel-accurate selection tools. */
val LocalMask.isContentAware: Boolean
    get() = this is LocalMask.SubjectMask ||
        this is LocalMask.SkyMask ||
        this is LocalMask.ColorRangeMask

/**
 * 1.6.58：全 7 种蒙版统一的反选状态读取。此前「控制点」没有 invert 字段，
 * 且 UI 的「反转选区」按钮对控制点静默无效；现在每种选区都能反选。
 */
val LocalMask.isInverted: Boolean
    get() = when (this) {
        is LocalMask.ControlPoint -> invert
        is LocalMask.LinearGradient -> invert
        is LocalMask.RadialGradient -> invert
        is LocalMask.Brush -> invert
        is LocalMask.SubjectMask -> invert
        is LocalMask.SkyMask -> invert
        is LocalMask.ColorRangeMask -> invert
    }

/** 1.6.58：翻转任意蒙版的反选状态，其余参数原样保留。 */
fun LocalMask.withInverted(value: Boolean): LocalMask = when (this) {
    is LocalMask.ControlPoint -> copy(invert = value)
    is LocalMask.LinearGradient -> copy(invert = value)
    is LocalMask.RadialGradient -> copy(invert = value)
    is LocalMask.Brush -> copy(invert = value)
    is LocalMask.SubjectMask -> copy(invert = value)
    is LocalMask.SkyMask -> copy(invert = value)
    is LocalMask.ColorRangeMask -> copy(invert = value)
}

/** One editable local adjustment layer (mask + its scoped parameters). */
data class LocalLayer(
    val id: String,
    val name: String = "局部",
    val mask: LocalMask = LocalMask.ControlPoint(),
    val params: LocalParams = LocalParams.Neutral,
    val strength: Float = 100f,
    val enabled: Boolean = true,
    /** Free-hand eraser strokes that remove scope from THIS layer's mask. */
    val eraser: List<EraserStroke> = emptyList(),
    /**
     * 1.6.58：仅当 [mask] 为「选择主体」时生效的一组局部人像美颜。磨皮/美白
     * /红润/立体只作用于选区内的皮肤，选区外不受影响。默认 Off，旧预设的
     * JSON 没有该键时自动取 Off，完全向后兼容。
     */
    val portrait: PortraitRetouch = PortraitRetouch.Off
) {
    fun normalized(): LocalLayer = copy(
        strength = strength.coerceIn(0f, 100f),
        params = params.normalized(),
        eraser = eraser.map { it.normalized() }.take(64),
        portrait = portrait.normalized()
    )

    /**
     * 一个「选择主体」图层即使影调参数全为 0，只要设置了局部人像美颜也
     * 必须参与渲染；其它类型图层的 portrait 恒为 Off，不影响原有判定。
     */
    fun active(): Boolean = enabled && strength > 0.001f &&
        (!params.isNeutral() || (mask is LocalMask.SubjectMask && !portrait.isOff()))

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("mask", mask.toJson())
        put("params", params.toJson())
        put("strength", strength.toDouble())
        put("enabled", enabled)
        put("eraser", eraser.toEraserJsonArray())
        put("portrait", portrait.toJson())
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
                enabled = json.optBoolean("enabled", true),
                // Legacy presets have no "eraser" key -> empty list.
                eraser = (json.optJSONArray("eraser") ?: JSONArray()).toEraserStrokes(),
                // Legacy presets have no "portrait" key -> Off (no局部美颜).
                portrait = PortraitRetouch.fromJson(json.optJSONObject("portrait"))
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

    /** Whether any layer needs the original photo to rebuild its selection. */
    fun hasContentAwareMasks(): Boolean = layers.any { it.mask.isContentAware }

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
