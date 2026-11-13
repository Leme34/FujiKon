package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.BrushStroke
import com.fujikon.app.data.ColorSample
import com.fujikon.app.data.EraserStroke
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.data.PortraitRetouch
import com.fujikon.app.data.RefineStroke
import com.fujikon.app.data.SelectionRefine
import com.fujikon.app.data.isContentAware
import com.fujikon.app.data.isInverted
import com.fujikon.app.data.withInverted
import com.fujikon.app.render.MlSegmentation
import com.fujikon.app.render.LocalMaskRenderer
import com.fujikon.app.render.SelectionMaskGenerator
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.MaskScopeRed
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val qualityCard = RoundedCornerShape(14.dp)

/**
 * Red mask-scope shadow for a linear gradient, ordered start -> end.
 * LocalMaskRenderer gates the ramp to the strip BETWEEN the two endpoint
 * lines and drives it from 0 at the start to 1 at the end (or the reverse
 * when inverted), so the painted shadow ramps in the same direction.
 */
internal fun linearScopeColors(invert: Boolean): List<Color> {
    val weak = MaskScopeRed.copy(alpha = 0.03f)
    val strong = MaskScopeRed.copy(alpha = 0.34f)
    return if (invert) listOf(strong, weak) else listOf(weak, strong)
}

/** HSL hue in degrees (0..360) for an ARGB pixel; grey pixels report hue 0. */
fun hueFromArgb(argb: Int): Float = hslFromArgb(argb)[0]

/** HSL saturation (0..1) for an ARGB pixel. */
fun saturationFromArgb(argb: Int): Float = hslFromArgb(argb)[1]

/** HSL lightness (0..1) for an ARGB pixel. */
fun lightnessFromArgb(argb: Int): Float = hslFromArgb(argb)[2]

/**
 * Samples HSL from the ORIGINAL, unedited photo at a normalised point. Used
 * by the "选择颜色范围" tool so an already-applied grade cannot drift the
 * picked colour (same rule as the HSL picker). Returns null when no usable
 * bitmap is available. Kept `internal` so it can be unit-tested directly.
 */
internal fun sampleHslAt(bitmap: Bitmap?, nx: Float, ny: Float): FloatArray? {
    if (bitmap == null || bitmap.isRecycled || bitmap.width < 1 || bitmap.height < 1) return null
    val sx = (nx.coerceIn(0f, 1f) * (bitmap.width - 1)).roundToInt().coerceIn(0, bitmap.width - 1)
    val sy = (ny.coerceIn(0f, 1f) * (bitmap.height - 1)).roundToInt().coerceIn(0, bitmap.height - 1)
    return hslFromArgb(bitmap.getPixel(sx, sy))
}

/** Re-seeds a colour-range selection from a tapped point on the source photo. */
internal fun tapColorRange(
    mask: LocalMask.ColorRangeMask, bitmap: Bitmap?, nx: Float, ny: Float
): LocalMask.ColorRangeMask {
    val hsl = sampleHslAt(bitmap, nx, ny) ?: return mask.copy(seedX = nx, seedY = ny)
    // The tapped pixel MUST be part of its own selection. If the user taps a
    // near-grey / low-saturation area, the default "排除灰阶" threshold would
    // filter it out and the tool would appear dead (nothing gets selected).
    // Lower the grey-rejection just below the picked saturation on each pick.
    val minSat = minOf(mask.minSaturation, hsl[1] * 0.5f)
    return mask.copy(
        seedX = nx, seedY = ny,
        targetHue = hsl[0], targetSat = hsl[1], targetLum = hsl[2],
        minSaturation = minSat.coerceIn(0f, 0.9f)
    )
}

/**
 * Picks the most vivid (highest chroma, mid-lightness) pixel of the original
 * photo as the initial seed for "选择颜色范围". This keeps the tool useful the
 * instant it is opened — even when the frame centre is a white dress / grey wall
 * with no colour — instead of silently selecting nothing. Sampling is coarse
 * (every 7th pixel) so it stays cheap on the UI thread.
 */
internal fun mostVividSeed(bitmap: Bitmap?): Pair<Float, Float> {
    if (bitmap == null || bitmap.isRecycled || bitmap.width < 1 || bitmap.height < 1) {
        return 0.5f to 0.5f
    }
    val w = bitmap.width
    val h = bitmap.height
    var bestHue = 0f
    var bestSat = 0.08f
    var bestLum = 0.5f
    var bestScore = -1f
    var bx = 0.5f
    var by = 0.5f
    val step = max(1, w / 96)
    for (y in 0 until h step step) {
        for (x in 0 until w step step) {
            val hsl = hslFromArgb(bitmap.getPixel(x, y))
            val sat = hsl[1]
            val lum = hsl[2]
            // Prefer colourful, mid-tone pixels; penalise near-black/white/grey.
            val score = sat * (1f - kotlin.math.abs(lum - 0.5f) * 1.4f)
            if (score > bestScore) {
                bestScore = score
                bestHue = hsl[0]; bestSat = sat; bestLum = lum
                bx = x / (w - 1).toFloat(); by = y / (h - 1).toFloat()
            }
        }
    }
    return bx to by
}

/** Lightroom-style +/- brush mode for refining an automatic selection. */
enum class RefineMode { NONE, ADD, ERASE }

/**
 * Adds an eyedropper sample to a colour-range selection. [add] = true behaves
 * like Lightroom's "+" dropper (also select this shade), false like the "-"
 * dropper (exclude this shade). Keeping the tapped colour in the sample list
 * is what lets a user separate two close hues instead of fighting one slider.
 */
internal fun addColorSample(
    mask: LocalMask.ColorRangeMask, bitmap: Bitmap?, nx: Float, ny: Float, add: Boolean
): LocalMask.ColorRangeMask {
    val hsl = sampleHslAt(bitmap, nx, ny) ?: return mask
    return mask.copy(
        samples = (mask.samples + ColorSample(hsl[0], hsl[1], hsl[2], add)).take(12)
    )
}

/** Appends a painted +/- stroke to any content-aware selection. */
internal fun appendRefineStroke(mask: LocalMask, points: List<Float>, erase: Boolean): LocalMask {
    val stroke = RefineStroke(points = points, erase = erase)
    return when (mask) {
        is LocalMask.SubjectMask -> mask.copy(refine = mask.refine.copy(strokes = (mask.refine.strokes + stroke).take(64)))
        is LocalMask.SkyMask -> mask.copy(refine = mask.refine.copy(strokes = (mask.refine.strokes + stroke).take(64)))
        is LocalMask.ColorRangeMask -> mask.copy(refine = mask.refine.copy(strokes = (mask.refine.strokes + stroke).take(64)))
        else -> mask
    }
}

/** Appends one ERASER stroke to a layer. Works for EVERY mask kind. */
internal fun appendEraserStroke(
    layer: LocalLayer, points: List<Float>, radius: Float, feather: Float
): LocalLayer = layer.copy(
    eraser = (layer.eraser + EraserStroke(points = points, radius = radius, feather = feather)).take(64)
)

/** Removes every eraser stroke, restoring the layer's full scope. */
internal fun clearEraser(layer: LocalLayer): LocalLayer = layer.copy(eraser = emptyList())

/** Clears painted refinement strokes (the AI selection itself is untouched). */
internal fun clearRefineStrokes(mask: LocalMask): LocalMask = when (mask) {
    is LocalMask.SubjectMask -> mask.copy(refine = mask.refine.copy(strokes = emptyList()))
    is LocalMask.SkyMask -> mask.copy(refine = mask.refine.copy(strokes = emptyList()))
    is LocalMask.ColorRangeMask -> mask.copy(refine = mask.refine.copy(strokes = emptyList()))
    else -> mask
}

internal fun refineOf(mask: LocalMask): SelectionRefine = when (mask) {
    is LocalMask.SubjectMask -> mask.refine
    is LocalMask.SkyMask -> mask.refine
    is LocalMask.ColorRangeMask -> mask.refine
    else -> SelectionRefine.Default
}

internal fun withRefine(mask: LocalMask, transform: (SelectionRefine) -> SelectionRefine): LocalMask =
    when (mask) {
        is LocalMask.SubjectMask -> mask.copy(refine = transform(mask.refine))
        is LocalMask.SkyMask -> mask.copy(refine = transform(mask.refine))
        is LocalMask.ColorRangeMask -> mask.copy(refine = transform(mask.refine))
        else -> mask
    }

/**
 * Builds a semi-transparent overlay that shows the recognised selection. Pure
 * and side-effect free (apart from bitmap allocation) so it can be unit-tested
 * and safely called from a background dispatcher by the mask editor.
 */
internal fun buildSelectionOverlay(
    mask: LocalMask, bitmap: Bitmap?, eraser: List<EraserStroke> = emptyList()
): ImageBitmap? {
    if (!mask.isContentAware || !usable(bitmap)) return null
    val analysis = SelectionMaskGenerator.fromBitmap(bitmap) ?: return null
    val values = SelectionMaskGenerator.build(mask, analysis, null, analysis.w, analysis.h)
        ?: return null
    if (values.size != analysis.w * analysis.h) return null
    // The eraser is layer-scoped: punch the painted areas out of the red wash
    // too, so the preview matches the scope actually applied at render time.
    LocalMaskRenderer.applyEraser(values, analysis.w, analysis.h, eraser)
    // 1.6.59：反选必须在 eraser 之后执行，否则被擦除的洞在反选后仍不被选中。
    if (mask.isInverted) {
        for (i in values.indices) values[i] = (1f - values[i]).coerceIn(0f, 1f)
    }
    val argb = SelectionMaskGenerator.toOverlay(values, analysis.w, analysis.h)
    return Bitmap.createBitmap(argb, analysis.w, analysis.h, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

private fun hslFromArgb(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) * 0.5f
    val delta = max - min
    if (delta < 1e-6f) return floatArrayOf(0f, 0f, l)
    val s = delta / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
    val h = when (max) {
        r -> ((g - b) / delta) % 6f
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return floatArrayOf((h * 60f + 360f) % 360f, s.coerceIn(0f, 1f), l)
}

/** Human-readable colour family for a hue, used by the picker readout. */
fun hueLabel(hue: Float): String {
    val h = (hue % 360f + 360f) % 360f
    return when {
        h < 15f || h >= 345f -> "红色"
        h < 45f -> "橙色"
        h < 70f -> "黄色"
        h < 100f -> "黄绿"
        h < 140f -> "绿色"
        h < 170f -> "青绿"
        h < 200f -> "青色"
        h < 230f -> "天蓝"
        h < 260f -> "蓝色"
        h < 290f -> "紫色"
        h < 320f -> "洋红"
        else -> "粉红"
    }
}

internal fun usable(bitmap: Bitmap?): Boolean = bitmap != null && !bitmap.isRecycled

@Composable
private fun SectionHelp(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

private fun Modifier.qualityCard(): Modifier = this
    .fillMaxWidth()
    .background(CardHigh.copy(alpha = 0.16f), qualityCard)
    .border(1.dp, Color.White.copy(alpha = 0.07f), qualityCard)
    .padding(horizontal = 12.dp, vertical = 4.dp)

internal enum class MaskKind(val label: String) {
    POINT("控制点"), LINEAR("线性"), RADIAL("径向"), BRUSH("画笔"),
    SUBJECT("选择主体"), SKY("选择天空"), COLOR_RANGE("颜色范围")
}

internal fun kindOf(mask: LocalMask): MaskKind = when (mask) {
    is LocalMask.ControlPoint -> MaskKind.POINT
    is LocalMask.LinearGradient -> MaskKind.LINEAR
    is LocalMask.RadialGradient -> MaskKind.RADIAL
    is LocalMask.Brush -> MaskKind.BRUSH
    is LocalMask.SubjectMask -> MaskKind.SUBJECT
    is LocalMask.SkyMask -> MaskKind.SKY
    is LocalMask.ColorRangeMask -> MaskKind.COLOR_RANGE
}

internal fun defaultMask(kind: MaskKind): LocalMask = when (kind) {
    MaskKind.POINT -> LocalMask.ControlPoint()
    MaskKind.LINEAR -> LocalMask.LinearGradient()
    MaskKind.RADIAL -> LocalMask.RadialGradient()
    MaskKind.BRUSH -> LocalMask.Brush()
    MaskKind.SUBJECT -> LocalMask.SubjectMask()
    MaskKind.SKY -> LocalMask.SkyMask()
    MaskKind.COLOR_RANGE -> LocalMask.ColorRangeMask()
}

private data class QuickEffect(val label: String, val params: LocalParams)

private val quickEffects = listOf(
    QuickEffect("提亮主体", LocalParams(brightness = 35f, shadows = 18f)),
    QuickEffect("压暗亮部", LocalParams(highlights = -42f, brightness = -10f)),
    QuickEffect("增艳", LocalParams(saturation = 36f)),
    QuickEffect("暖调", LocalParams(temperature = 28f)),
    QuickEffect("冷调", LocalParams(temperature = -28f))
)

/**
 * Rectangle actually occupied by a ContentScale.Fit image inside a view.
 * Keeping it a pure function lets the mask geometry be unit-tested without a
 * device: taps in the letter-boxed area are rejected rather than warped onto
 * the edge of the photo.
 */
internal data class ImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    /** Normalised (0..1) image coordinates, or null for a letter-box tap. */
    fun normalized(x: Float, y: Float): Pair<Float, Float>? {
        if (width <= 0f || height <= 0f) return null
        val nx = (x - left) / width
        val ny = (y - top) / height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return nx to ny
    }
}

internal fun fitImageRect(viewW: Float, viewH: Float, bmpW: Int, bmpH: Int): ImageRect {
    if (viewW <= 0f || viewH <= 0f || bmpW <= 0 || bmpH <= 0) {
        return ImageRect(0f, 0f, viewW.coerceAtLeast(0f), viewH.coerceAtLeast(0f))
    }
    val fit = minOf(viewW / bmpW, viewH / bmpH)
    val w = bmpW * fit
    val h = bmpH * fit
    return ImageRect((viewW - w) / 2f, (viewH - h) / 2f, w, h)
}

private fun rectFor(viewW: Float, viewH: Float, bitmap: Bitmap?): ImageRect =
    if (usable(bitmap)) fitImageRect(viewW, viewH, bitmap!!.width, bitmap!!.height)
    else fitImageRect(viewW, viewH, 1, 1)

private fun safeAspect(aspect: Float): Float =
    aspect.takeIf { it.isFinite() && it > 0.0001f } ?: 1f

/** On-screen direction of a linear gradient, in degrees (0..360). */
internal fun linearAngleDeg(mask: LocalMask.LinearGradient, aspect: Float): Float {
    val a = safeAspect(aspect)
    val dx = (mask.endX - mask.startX) * a
    val dy = mask.endY - mask.startY
    return (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
}

/** On-screen length of a linear gradient (physical, height-normalised). */
internal fun linearSpan(mask: LocalMask.LinearGradient, aspect: Float): Float {
    val a = safeAspect(aspect)
    val dx = (mask.endX - mask.startX) * a
    val dy = mask.endY - mask.startY
    return sqrt(dx * dx + dy * dy)
}

private fun linearCentre(m: LocalMask.LinearGradient): Pair<Float, Float> =
    (m.startX + m.endX) * 0.5f to (m.startY + m.endY) * 0.5f

private fun clampLinear(m: LocalMask.LinearGradient): LocalMask.LinearGradient = m.copy(
    startX = m.startX.coerceIn(-0.5f, 1.5f),
    startY = m.startY.coerceIn(-0.5f, 1.5f),
    endX = m.endX.coerceIn(-0.5f, 1.5f),
    endY = m.endY.coerceIn(-0.5f, 1.5f)
)

/** Rebuild a linear gradient from its centre, angle (degrees) and physical span. */
private fun buildLinear(
    base: LocalMask.LinearGradient,
    cx: Float,
    cy: Float,
    angleDeg: Float,
    span: Float,
    aspect: Float
): LocalMask.LinearGradient {
    val a = safeAspect(aspect)
    val r = Math.toRadians(angleDeg.toDouble())
    val hx = Math.cos(r).toFloat() * span * 0.5f / a
    val hy = Math.sin(r).toFloat() * span * 0.5f
    return clampLinear(
        base.copy(
            startX = cx - hx, startY = cy - hy,
            endX = cx + hx, endY = cy + hy
        )
    )
}

/** Set a linear gradient's on-screen angle while keeping centre and span. */
internal fun setLinearAngle(
    mask: LocalMask.LinearGradient, angleDeg: Float, aspect: Float
): LocalMask.LinearGradient {
    val (cx, cy) = linearCentre(mask)
    return buildLinear(mask, cx, cy, angleDeg, linearSpan(mask, aspect), aspect)
}

/** Set a linear gradient's on-screen span while keeping centre and angle. */
internal fun setLinearSpan(
    mask: LocalMask.LinearGradient, span: Float, aspect: Float
): LocalMask.LinearGradient {
    val (cx, cy) = linearCentre(mask)
    return buildLinear(mask, cx, cy, linearAngleDeg(mask, aspect), span, aspect)
}

/**
 * Apply a cumulative pinch scale and rotation (degrees) to a BASE mask.
 * Called every gesture frame from the immutable snapshot taken on gesture
 * start, so repeated zoom deltas never accumulate rounding error. Rotation
 * now applies to linear gradients AND to the radial / brush angles.
 */
internal fun transformMask(
    base: LocalMask, scale: Float, rotationDeg: Float, aspect: Float = 1f
): LocalMask = when (base) {
    is LocalMask.ControlPoint -> base.copy(
        radius = (base.radius * scale).coerceIn(0.05f, 0.9f)
    )
    is LocalMask.RadialGradient -> base.copy(
        radiusX = (base.radiusX * scale).coerceIn(0.05f, 0.9f),
        radiusY = (base.radiusY * scale).coerceIn(0.05f, 0.9f),
        rotationDeg = base.rotationDeg + rotationDeg
    )
    is LocalMask.Brush -> base.copy(
        radius = (base.radius * scale).coerceIn(0.01f, 0.25f),
        rotationDeg = base.rotationDeg + rotationDeg
    )
    is LocalMask.LinearGradient -> {
        val (cx, cy) = linearCentre(base)
        val angle = linearAngleDeg(base, aspect) + rotationDeg
        val span = (linearSpan(base, aspect) * scale).coerceIn(0.05f, 2.5f)
        buildLinear(base, cx, cy, angle, span, aspect)
    }
    // Content-aware selections are derived from the photo, not a shape: a
    // pinch/rotate must not warp the recognised region.
    is LocalMask.SubjectMask,
    is LocalMask.SkyMask,
    is LocalMask.ColorRangeMask -> base
}

/**
 * Move a mask's anchor to a normalised point. For a linear gradient the
 * direction and span are PRESERVED (only the centre moves), so a rotated
 * gradient is not silently reset to vertical by a tap or a drag.
 */
internal fun placeMask(mask: LocalMask, nx: Float, ny: Float): LocalMask = when (mask) {
    is LocalMask.ControlPoint -> mask.copy(x = nx, y = ny)
    is LocalMask.RadialGradient -> mask.copy(x = nx, y = ny)
    is LocalMask.LinearGradient -> {
        val (cx, cy) = linearCentre(mask)
        val dx = nx - cx
        val dy = ny - cy
        clampLinear(
            mask.copy(
                startX = mask.startX + dx, startY = mask.startY + dy,
                endX = mask.endX + dx, endY = mask.endY + dy
            )
        )
    }
    is LocalMask.Brush -> mask
    // For a content-aware selection a tap RE-SEEDS the region growing: tap the
    // subject / the colour you want and the mask re-recognises around it.
    is LocalMask.SubjectMask -> mask.copy(seedX = nx, seedY = ny)
    is LocalMask.SkyMask -> mask
    is LocalMask.ColorRangeMask -> mask.copy(seedX = nx, seedY = ny)
}

/** Increment a mask's rotation (a single finger dragging its handle). */
internal fun rotateMaskBy(
    mask: LocalMask, deltaDeg: Float, aspect: Float
): LocalMask = when (mask) {
    is LocalMask.RadialGradient -> mask.copy(rotationDeg = mask.rotationDeg + deltaDeg)
    is LocalMask.Brush -> mask.copy(rotationDeg = mask.rotationDeg + deltaDeg)
    is LocalMask.LinearGradient -> setLinearAngle(
        mask, linearAngleDeg(mask, aspect) + deltaDeg, aspect
    )
    else -> mask
}

/** Translate a whole selection by a normalised delta (finger drag / two-finger pan). */
internal fun panMask(mask: LocalMask, dx: Float, dy: Float): LocalMask = when (mask) {
    is LocalMask.ControlPoint -> mask.copy(x = mask.x + dx, y = mask.y + dy)
    is LocalMask.RadialGradient -> mask.copy(x = mask.x + dx, y = mask.y + dy)
    is LocalMask.LinearGradient -> clampLinear(
        mask.copy(
            startX = mask.startX + dx, startY = mask.startY + dy,
            endX = mask.endX + dx, endY = mask.endY + dy
        )
    )
    // Brush uses a screen-space offset applied AFTER rotation, so a drag
    // always follows the finger even when the strokes are rotated.
    is LocalMask.Brush -> mask.copy(offsetX = mask.offsetX + dx, offsetY = mask.offsetY + dy)
    // A recognised region is not a draggable shape; re-seed it with a tap instead.
    is LocalMask.SubjectMask,
    is LocalMask.SkyMask,
    is LocalMask.ColorRangeMask -> mask
}

/**
 * On-screen position of the ROTATION handle for radial / brush selections.
 * A single finger pressed on this point spins the selection about its centre.
 * null for shapes without a rotation handle (point / linear).
 */
internal fun maskRotationHandle(
    mask: LocalMask, rect: ImageRect, aspect: Float, gapPx: Float = 34f
): Offset? {
    val w = rect.width; val h = rect.height
    if (w <= 0f || h <= 0f) return null
    fun px(nx: Float) = rect.left + nx * w
    fun py(ny: Float) = rect.top + ny * h
    val (centre, length, angleDeg) = when (mask) {
        is LocalMask.RadialGradient -> Triple(
            Offset(px(mask.x), py(mask.y)),
            mask.radiusY * h + gapPx,
            mask.rotationDeg
        )
        is LocalMask.Brush -> {
            val (cx, cy) = mask.centre()
            val p = mask.mapPoint(cx, cy, aspect)
            Triple(
                Offset(px(p.first), py(p.second)),
                minOf(w, h) * 0.16f + gapPx,
                mask.rotationDeg
            )
        }
        else -> return null
    }
    val r = Math.toRadians(angleDeg.toDouble())
    // Screen "up" (0,-1) spun clockwise by the current angle.
    val dir = Offset(sin(r).toFloat(), -cos(r).toFloat())
    return centre + dir * length
}

/** True when a normalised point lies on (or near) an existing brush stroke. */
internal fun hitBrushStroke(
    mask: LocalMask.Brush, nx: Float, ny: Float, aspect: Float, tolerance: Float
): Boolean {
    for (stroke in mask.strokes) {
        val p = stroke.points
        var i = 0
        while (i + 1 < p.size) {
            val a = mask.mapPoint(p[i], p[i + 1], aspect)
            val b = if (i + 3 < p.size) mask.mapPoint(p[i + 2], p[i + 3], aspect) else a
            val dx = b.first - a.first; val dy = b.second - a.second
            val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
            val t = (((nx - a.first) * dx + (ny - a.second) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(nx - (a.first + t * dx), ny - (a.second + t * dy)) <= tolerance) return true
            i += 2
        }
    }
    return false
}

/**
 * Append one brush stroke. Points arrive in SCREEN normalised coordinates and
 * are mapped back into the brush's local space, so painting still lands under
 * the finger even after the selection was rotated / dragged.
 */
internal fun appendBrushStroke(
    mask: LocalMask, flat: List<Float>, aspect: Float = 1f
): LocalMask =
    if (mask is LocalMask.Brush && flat.size >= 4) {
        val local = FloatArray(flat.size)
        var i = 0
        while (i + 1 < flat.size) {
            val p = mask.unmapPoint(flat[i], flat[i + 1], aspect)
            local[i] = p.first; local[i + 1] = p.second
            i += 2
        }
        mask.copy(strokes = (mask.strokes + BrushStroke(local.toList())).take(24))
    } else mask

/** Draw the mask outline in the same geometry used to interpret touches. */
/**
 * Builds a Path covering every eraser dab, used with ClipOp.Difference to
 * punch holes in the red scope fills. Dabs are sampled along each segment so
 * a fast drag still leaves a continuous gap rather than isolated dots.
 */
private fun buildEraserClip(eraser: List<EraserStroke>, rect: ImageRect): Path? {
    if (eraser.isEmpty()) return null
    val path = Path()
    val long = maxOf(rect.width, rect.height)
    var any = false
    for (stroke in eraser) {
        val r = (stroke.radius.coerceIn(0.005f, 0.4f) * long).coerceAtLeast(2f)
        val pts = stroke.points
        var k = 0
        while (k + 3 < pts.size) {
            val ax = rect.left + pts[k] * rect.width
            val ay = rect.top + pts[k + 1] * rect.height
            val bx = rect.left + pts[k + 2] * rect.width
            val by = rect.top + pts[k + 3] * rect.height
            val steps = maxOf(1, (hypot(bx - ax, by - ay) / (r * 0.5f)).roundToInt())
            for (j in 0..steps) {
                val t = j.toFloat() / steps
                val cx = ax + (bx - ax) * t; val cy = ay + (by - ay) * t
                path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                any = true
            }
            k += 2
        }
        if (pts.size == 2) {
            val cx = rect.left + pts[0] * rect.width
            val cy = rect.top + pts[1] * rect.height
            path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            any = true
        }
    }
    return if (any) path else null
}

private fun DrawScope.drawMaskOutline(
    mask: LocalMask,
    rect: ImageRect,
    liveBrush: List<Offset>,
    aspect: Float,
    eraser: List<EraserStroke> = emptyList()
) {
    val l = rect.left
    val t = rect.top
    val w = rect.width
    val h = rect.height
    if (w <= 0f || h <= 0f) return
    val base = minOf(w, h)
    fun px(nx: Float) = l + nx * w
    fun py(ny: Float) = t + ny * h

    // Punch painted eraser strokes out of the RED scope fills, so the visual
    // matches the mask actually applied by LocalMaskRenderer.applyEraser().
    val eraserPath = buildEraserClip(eraser, rect)
    fun DrawScope.scopedFill(block: DrawScope.() -> Unit) {
        if (eraserPath != null) clipPath(eraserPath, ClipOp.Difference, block) else block()
    }

    for (i in 1..2) {
        drawLine(Color.White.copy(alpha = 0.10f), Offset(l + w * i / 3f, t), Offset(l + w * i / 3f, t + h))
        drawLine(Color.White.copy(alpha = 0.10f), Offset(l, t + h * i / 3f), Offset(l + w, t + h * i / 3f))
    }
    when (mask) {
        is LocalMask.ControlPoint -> {
            val r = mask.radius * base
            val c = Offset(px(mask.x), py(mask.y))
            scopedFill { drawCircle(MaskScopeRed.copy(alpha = 0.30f), r, c) }
            drawCircle(MaskScopeRed, r, c, style = Stroke(2.dp.toPx()))
            drawCircle(MaskScopeRed, 5.dp.toPx(), c)
            // Grab handle: pinch (or drag the ring) to resize the region.
            drawCircle(Color.White, 6.dp.toPx(), Offset(c.x + r, c.y))
        }
        is LocalMask.RadialGradient -> {
            val topLeft = Offset(px(mask.x) - mask.radiusX * w, py(mask.y) - mask.radiusY * h)
            val oval = Size(mask.radiusX * 2f * w, mask.radiusY * 2f * h)
            val c = Offset(px(mask.x), py(mask.y))
            // The raster uses the same rotationDeg, so the outline matches the
            // pixels that actually change.
            rotate(mask.rotationDeg, pivot = c) {
                scopedFill { drawOval(MaskScopeRed.copy(alpha = 0.30f), topLeft, oval) }
                drawOval(MaskScopeRed, topLeft, oval, style = Stroke(2.dp.toPx()))
            }
            // Resize handle on the rotated +X rim.
            val r = Math.toRadians(mask.rotationDeg.toDouble())
            val rim = Offset(
                cos(r).toFloat() * mask.radiusX * w,
                sin(r).toFloat() * mask.radiusX * w
            )
            drawCircle(Color.White, 6.dp.toPx(), c + rim)
            // Rotation handle: a single finger on this dot spins the ellipse.
            val handle = maskRotationHandle(mask, rect, aspect)
            if (handle != null) {
                drawLine(Color.White.copy(alpha = 0.55f), c, handle, 2.dp.toPx())
                drawCircle(Color.White, 9.dp.toPx(), handle)
                drawCircle(MaskScopeRed, 4.dp.toPx(), handle)
            }
        }
        is LocalMask.LinearGradient -> {
            val s = Offset(px(mask.startX), py(mask.startY))
            val e = Offset(px(mask.endX), py(mask.endY))
            val dx = e.x - s.x; val dy = e.y - s.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val nx = -dy / len; val ny = dx / len
            val reach = maxOf(w, h)
            // RED SCOPE SHADOW: the renderer confines the effect to the strip
            // BETWEEN the two endpoint lines, ramping start -> end. Paint that
            // same band with a translucent red gradient (clipped to the photo)
            // so a linear mask shows its scope exactly like the point / radial
            // fills, instead of only showing two hairlines.
            val band = Path().apply {
                moveTo(s.x - nx * reach, s.y - ny * reach)
                lineTo(e.x - nx * reach, e.y - ny * reach)
                lineTo(e.x + nx * reach, e.y + ny * reach)
                lineTo(s.x + nx * reach, s.y + ny * reach)
                close()
            }
            clipRect(l, t, l + w, t + h) {
                scopedFill {
                    drawPath(
                        band,
                        brush = Brush.linearGradient(
                            linearScopeColors(mask.invert), start = s, end = e
                        )
                    )
                }
            }
            // Two red boundary lines AT the endpoints: the effect is confined
            // to the strip BETWEEN them (LocalMaskRenderer gates the ramp there).
            listOf(s, e).forEach { p ->
                drawLine(
                    MaskScopeRed.copy(alpha = 0.75f),
                    Offset(p.x - nx * reach, p.y - ny * reach),
                    Offset(p.x + nx * reach, p.y + ny * reach),
                    2.dp.toPx()
                )
            }
            drawLine(MaskScopeRed, s, e, 3.dp.toPx())
            drawCircle(Color.White, 6.dp.toPx(), s)
            drawCircle(Color.White, 6.dp.toPx(), e)
        }
        is LocalMask.Brush -> {
            scopedFill {
                mask.strokes.forEach { stroke ->
                    val pts = stroke.points
                    var i = 0
                    while (i + 3 < pts.size) {
                        val a = mask.mapPoint(pts[i], pts[i + 1], aspect)
                        val b = mask.mapPoint(pts[i + 2], pts[i + 3], aspect)
                        drawLine(
                            MaskScopeRed.copy(alpha = 0.8f),
                            Offset(px(a.first), py(a.second)),
                            Offset(px(b.first), py(b.second)),
                            (mask.radius * base * 2f).coerceAtLeast(3f)
                        )
                        i += 2
                    }
                    if (pts.size == 2) {
                        val a = mask.mapPoint(pts[0], pts[1], aspect)
                        drawCircle(
                            MaskScopeRed.copy(alpha = 0.8f),
                            (mask.radius * base).coerceAtLeast(3f),
                            Offset(px(a.first), py(a.second))
                        )
                    }
                }
            }
            if (mask.strokes.isNotEmpty()) {
                val (cx, cy) = mask.centre()
                val cN = mask.mapPoint(cx, cy, aspect)
                val c = Offset(px(cN.first), py(cN.second))
                val handle = maskRotationHandle(mask, rect, aspect)
                if (handle != null) {
                    drawLine(Color.White.copy(alpha = 0.55f), c, handle, 2.dp.toPx())
                    drawCircle(Color.White, 9.dp.toPx(), handle)
                    drawCircle(MaskScopeRed, 4.dp.toPx(), handle)
                }
            }
        }
        // Content-aware selections: mark the seed / region hint. The actual
        // recognised area is drawn as a semi-transparent overlay in the Canvas
        // above, so this ring only shows WHERE the user tapped.
        is LocalMask.SubjectMask,
        is LocalMask.ColorRangeMask -> {
            val sx = if (mask is LocalMask.SubjectMask) mask.seedX else (mask as LocalMask.ColorRangeMask).seedX
            val sy = if (mask is LocalMask.SubjectMask) mask.seedY else (mask as LocalMask.ColorRangeMask).seedY
            val c = Offset(px(sx), py(sy))
            drawCircle(MaskScopeRed.copy(alpha = 0.9f), 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            drawCircle(MaskScopeRed, 3.dp.toPx(), c)
        }
        is LocalMask.SkyMask -> {
            // Sky grows down from the top edge; a hairline marks that edge.
            drawLine(MaskScopeRed.copy(alpha = 0.8f), Offset(l, t + 2.dp.toPx()), Offset(l + w, t + 2.dp.toPx()), 2.dp.toPx())
        }
    }
    for (i in 1 until liveBrush.size) {
        drawLine(
            MaskScopeRed.copy(alpha = 0.9f),
            Offset(px(liveBrush[i - 1].x), py(liveBrush[i - 1].y)),
            Offset(px(liveBrush[i].x), py(liveBrush[i].y)),
            6.dp.toPx()
        )
    }
}

/**
 * Parameters-only drawer for the standalone full-screen [MaskEditorOverlay].
 *
 * Everything that positions or shapes a mask (shape picking, layer add /
 * remove / switch, tap-to-place, drag-to-move / paint, single-finger rotation
 * via the white handle, and two-finger pinch / rotate / pan) lives on the
 * full-screen photo, not here. This drawer holds the COMPLETE numeric
 * parameter set — shape geometry (incl. rotation angle) plus scoped light /
 * colour / detail params and layer strength — and can be collapsed to
 * enlarge the preview.
 */
@Composable
fun LocalAdjustmentSection(
    local: LocalAdjustments,
    selectedLayer: Int,
    onSelectLayer: (Int) -> Unit,
    onChange: (LocalAdjustments, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean,
    /** Display width / height, so linear-gradient angles match the screen. */
    aspect: Float = 1f,
    /** Shared selection-refinement UI state (owned by MaskEditorOverlay). */
    refineMode: RefineMode = RefineMode.NONE,
    onRefineModeChange: (RefineMode) -> Unit = {},
    sampleMode: Boolean? = null,
    onSampleModeChange: (Boolean?) -> Unit = {},
    showOverlay: Boolean = true,
    onShowOverlayChange: (Boolean) -> Unit = {},
    onRedetect: () -> Unit = {},
    /** Mask ERASER tool state (shared with the photo gesture surface). */
    eraserMode: Boolean = false,
    onEraserModeChange: (Boolean) -> Unit = {},
    eraserRadius: Float = 0.045f,
    onEraserRadiusChange: (Float) -> Unit = {},
    eraserFeather: Float = 0.5f,
    onEraserFeatherChange: (Float) -> Unit = {}
) {
    val layers = local.layers
    val index = selectedLayer.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    val layer = layers.getOrNull(index)

    val layersState = rememberUpdatedState(layers)
    val indexState = rememberUpdatedState(index)
    val onChangeState = rememberUpdatedState(onChange)
    val onFinishedState = rememberUpdatedState(onFinished)

    fun update(transform: (LocalLayer) -> LocalLayer, field: String = "蒙版") {
        val list = layersState.value
        val i = indexState.value
        val current = list.getOrNull(i) ?: return
        val next = list.toMutableList()
        next[i] = transform(current).normalized()
        onChangeState.value(LocalAdjustments(next), field)
    }

    fun commit(field: String = "蒙版") = onFinishedState.value(field)

    Column(Modifier.qualityCard()) {
        SectionHelp(
            "用途：只调整照片的一部分，比如提亮人脸、压暗过亮的天空，其余区域不受影响。\n" +
                "形状、位置、图层和画笔都在全屏预览大图上操作；径向与画笔按住白色旋转点可单指旋转，按已有笔迹可整体拖动；这里只调数值，可折叠收起让画面更大。"
        )

        if (layers.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                layers.forEachIndexed { i, l ->
                    TextButton(
                        onClick = { onSelectLayer(i) },
                        enabled = enabled,
                        modifier = Modifier.testTag("mask_layer_$i")
                    ) {
                        Text(
                            "${i + 1}·${kindOf(l.mask).label}${if (l.enabled) "" else "(停)"}",
                            color = if (i == index) Amber else TextSecondary
                        )
                    }
                }
            }
        }

        if (layer == null) {
            Text(
                "还没有蒙版：点照片下方工具条里的「+ 添加」，再在照片上点一下定位选区，然后展开参数调节数值。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            val mask = layer.mask
            Text(
                "当前蒙版 ${index + 1}/${layers.size} · ${kindOf(mask).label}",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            Text(
                "形状参数 · 也可在照片上双指捏合缩放/旋转、按住白色旋转点单指旋转",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            // Shape parameters only — placement happens on the preview overlay.
            when (val m = mask) {
                is LocalMask.ControlPoint -> {
                    AdjustmentSlider(
                        label = "作用半径", value = m.radius, valueRange = 0.05f..0.9f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(radius = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(radius = 0.25f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "羽化", value = m.feather, valueRange = 0.05f..0.98f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(feather = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(feather = 0.65f)) }); commit() },
                        enabled = enabled
                    )
                }
                is LocalMask.RadialGradient -> {
                    AdjustmentSlider(
                        label = "横向半径", value = m.radiusX, valueRange = 0.05f..0.9f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(radiusX = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(radiusX = 0.4f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "纵向半径", value = m.radiusY, valueRange = 0.05f..0.9f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(radiusY = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(radiusY = 0.4f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "羽化", value = m.feather, valueRange = 0.05f..0.98f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(feather = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(feather = 0.6f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "旋转角度", value = ((m.rotationDeg % 360f) + 360f) % 360f,
                        valueRange = 0f..360f, step = 1f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(rotationDeg = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(rotationDeg = 0f)) }); commit() },
                        enabled = enabled
                    )
                }
                is LocalMask.LinearGradient -> {
                    val angle = linearAngleDeg(m, aspect)
                    val span = linearSpan(m, aspect)
                    AdjustmentSlider(
                        label = "旋转角度", value = angle, valueRange = 0f..360f, step = 1f,
                        onValueChange = { v -> update({ it.copy(mask = setLinearAngle(m, v, aspect)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = setLinearAngle(m, 90f, aspect)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "渐变范围", value = span, valueRange = 0.1f..2f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = setLinearSpan(m, v, aspect)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = setLinearSpan(m, 1f, aspect)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "羽化", value = m.feather, valueRange = 0.05f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(feather = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(feather = 0.45f)) }); commit() },
                        enabled = enabled
                    )
                }
                is LocalMask.Brush -> {
                    AdjustmentSlider(
                        label = "笔刷半径", value = m.radius, valueRange = 0.01f..0.25f, step = 0.005f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(radius = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(radius = 0.06f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "羽化", value = m.feather, valueRange = 0.05f..0.98f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(feather = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(feather = 0.5f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "旋转角度", value = ((m.rotationDeg % 360f) + 360f) % 360f,
                        valueRange = 0f..360f, step = 1f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(rotationDeg = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(rotationDeg = 0f)) }); commit() },
                        enabled = enabled
                    )
                }
                is LocalMask.SubjectMask -> {
                    Text(
                        "端侧 AI 语义分割：自动识别人物、动物、车辆等主体并对齐真实边缘。点照片可切换选中的对象；用「添加/擦除」笔刷微调。",
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("mask_subject_hint")
                    )
                    AdjustmentSlider(
                        label = "识别范围", value = m.tolerance, valueRange = 0f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(tolerance = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(tolerance = 0.5f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "边缘羽化", value = m.feather, valueRange = 0f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(feather = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(feather = 0.35f)) }); commit() },
                        enabled = enabled
                    )
                }
                is LocalMask.SkyMask -> {
                    Text(
                        "端侧 AI 天空分割：语义识别蓝天、晚霞、阴天与云层，自动排除水面和蓝色衣物。建议配「压暗亮部」快速效果。",
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("mask_sky_hint")
                    )
                    AdjustmentSlider(
                        label = "识别范围", value = m.tolerance, valueRange = 0f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(tolerance = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(tolerance = 0.5f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "边缘羽化", value = m.feather, valueRange = 0f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(feather = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(feather = 0.35f)) }); commit() },
                        enabled = enabled
                    )
                }
                is LocalMask.ColorRangeMask -> {
                    Text(
                        "感知色差取色（OKLab）：点照片取主色，用「+/- 取样」补充或排除相近色；识别范围按人眼感知计算，不再误选邻近色。",
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("mask_color_hint")
                    )
                    AdjustmentSlider(
                        label = "色相范围", value = m.hueRange, valueRange = 5f..90f, step = 1f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(hueRange = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(hueRange = 24f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "颜色容差", value = m.tolerance, valueRange = 0f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(tolerance = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(tolerance = 0.5f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "排除灰阶", value = m.minSaturation, valueRange = 0f..0.6f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(minSaturation = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(minSaturation = 0.08f)) }); commit() },
                        enabled = enabled
                    )
                    AdjustmentSlider(
                        label = "边缘羽化", value = m.feather, valueRange = 0f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(feather = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(feather = 0.3f)) }); commit() },
                        enabled = enabled
                    )
                }
            }

            // ----------------------------------------------------------
            // 1.6.58：仅「选择主体」图层新增局部人像美颜参数。磨皮 / 美白
            // / 红润 / 立体只作用于选区内的人像皮肤，选区外不受影响；复用
            // PortraitSection 的风格 chips + 四滑块，数值写回 layer.portrait。
            // ----------------------------------------------------------
            if (mask is LocalMask.SubjectMask) {
                Text(
                    "人像美颜 · 仅作用于选中的主体",
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("mask_subject_portrait_title")
                )
                Text(
                    "磨皮 / 美白 / 红润 / 立体只作用于选区内的人像皮肤，背景与其它区域不受影响；" +
                        "配合上方「反选」可改作用于主体之外。选区由主体识别 + 擦除 / 精修共同决定。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                PortraitSection(
                    portrait = layer.portrait,
                    expanded = true,
                    onToggle = {},
                    onChange = { p, field -> update({ it.copy(portrait = p) }, field) },
                    onFinished = { field -> commit(field) },
                    enabled = enabled,
                    embedded = true
                )
            }

            // ----------------------------------------------------------
            // MASK ERASER: available for EVERY mask kind. Painted areas are
            // subtracted from this layer's scope in both preview and export.
            // ----------------------------------------------------------
            val eraserCount = layer?.eraser?.size ?: 0
            Text(
                "橡皮擦 · 开启后在照片上涂抹，即可从当前蒙版的作用域中擦除",
                color = Amber,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("mask_eraser_hint")
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(
                    onClick = { onEraserModeChange(!eraserMode) },
                    enabled = enabled,
                    modifier = Modifier.testTag("mask_eraser_toggle")
                ) {
                    Text(
                        if (eraserMode) "● 橡皮擦（涂抹中）" else "○ 橡皮擦",
                        color = if (eraserMode) Amber else TextSecondary
                    )
                }
                TextButton(
                    onClick = { update({ clearEraser(it) }, "橡皮擦"); commit("橡皮擦") },
                    enabled = enabled && eraserCount > 0,
                    modifier = Modifier.testTag("mask_eraser_clear")
                ) { Text("清除擦除 ($eraserCount)", color = TextSecondary) }
            }
            AdjustmentSlider(
                label = "橡皮擦大小", value = eraserRadius,
                valueRange = 0.01f..0.2f, step = 0.005f,
                onValueChange = onEraserRadiusChange,
                onValueChangeFinished = {},
                onReset = { onEraserRadiusChange(0.045f) },
                enabled = enabled
            )
            AdjustmentSlider(
                label = "橡皮擦羽化", value = eraserFeather,
                valueRange = 0.05f..1f, step = 0.01f,
                onValueChange = onEraserFeatherChange,
                onValueChangeFinished = {},
                onReset = { onEraserFeatherChange(0.5f) },
                enabled = enabled
            )

            // ----------------------------------------------------------
            // Shared "refine selection" panel for the three AI selections.
            // Mirrors Lightroom's mask panel: +/- brush, edge shift, smooth,
            // edge-aware alignment, show/hide overlay and re-detect.
            // ----------------------------------------------------------
            if (mask.isContentAware) {
                val refine = refineOf(mask)
                // Model loading happens off-thread. Poll briefly so the status
                // line flips from "loading / fallback" to "AI ready" once both
                // graphs are actually mapped, without a full re-composition.
                val aiOn by produceState(initialValue = MlSegmentation.available, mask) {
                    var tries = 0
                    while (!MlSegmentation.available && tries < 25) {
                        kotlinx.coroutines.delay(200)
                        tries++
                        value = MlSegmentation.available
                    }
                    value = MlSegmentation.available
                }
                Text(
                    if (aiOn) "识别引擎：端侧 AI 语义分割（本机运行，不联网）"
                    else "识别引擎：本机智能识别（AI 模型加载中或不可用，自动降级）",
                    color = if (aiOn) Amber else TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("mask_ai_status")
                )
                Text(
                    "精修选区 · 选一种笔刷后在照片上涂抹",
                    color = Amber,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            onRefineModeChange(if (refineMode == RefineMode.ADD) RefineMode.NONE else RefineMode.ADD)
                            onSampleModeChange(null)
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("mask_refine_add")
                    ) { Text("＋ 添加", color = if (refineMode == RefineMode.ADD) Amber else TextSecondary) }
                    TextButton(
                        onClick = {
                            onRefineModeChange(if (refineMode == RefineMode.ERASE) RefineMode.NONE else RefineMode.ERASE)
                            onSampleModeChange(null)
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("mask_refine_erase")
                    ) { Text("－ 擦除", color = if (refineMode == RefineMode.ERASE) Amber else TextSecondary) }
                    TextButton(
                        onClick = {
                            update({ it.copy(mask = clearRefineStrokes(it.mask)) }); commit()
                        },
                        enabled = enabled && refine.strokes.isNotEmpty(),
                        modifier = Modifier.testTag("mask_refine_clear")
                    ) { Text("清除笔刷 (${refine.strokes.size})", color = TextSecondary) }
                    TextButton(
                        onClick = onRedetect, enabled = enabled,
                        modifier = Modifier.testTag("mask_redetect")
                    ) { Text("重新识别", color = TextSecondary) }
                    TextButton(
                        onClick = { onShowOverlayChange(!showOverlay) }, enabled = enabled,
                        modifier = Modifier.testTag("mask_overlay_toggle")
                    ) { Text(if (showOverlay) "隐藏选区" else "显示选区", color = TextSecondary) }
                }
                AdjustmentSlider(
                    label = "收缩 / 扩展边缘", value = refine.edgeShift,
                    valueRange = -1f..1f, step = 0.01f,
                    onValueChange = { v ->
                        update({ it.copy(mask = withRefine(it.mask) { r -> r.copy(edgeShift = v) }) })
                    },
                    onValueChangeFinished = { commit() },
                    onReset = {
                        update({ it.copy(mask = withRefine(it.mask) { r -> r.copy(edgeShift = 0f) }) }); commit()
                    },
                    enabled = enabled
                )
                AdjustmentSlider(
                    label = "边缘平滑", value = refine.smooth,
                    valueRange = 0f..1f, step = 0.01f,
                    onValueChange = { v ->
                        update({ it.copy(mask = withRefine(it.mask) { r -> r.copy(smooth = v) }) })
                    },
                    onValueChangeFinished = { commit() },
                    onReset = {
                        update({ it.copy(mask = withRefine(it.mask) { r -> r.copy(smooth = 0.2f) }) }); commit()
                    },
                    enabled = enabled
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            update({ it.copy(mask = withRefine(it.mask) { r -> r.copy(detectEdges = !r.detectEdges) }) })
                            commit()
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("mask_edge_align_toggle")
                    ) {
                        Text(
                            if (refine.detectEdges) "智能边缘对齐：开" else "智能边缘对齐：关",
                            color = if (refine.detectEdges) Amber else TextSecondary
                        )
                    }
                }
                if (mask is LocalMask.ColorRangeMask) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton(
                            onClick = {
                                onRefineModeChange(RefineMode.NONE)
                                onSampleModeChange(if (sampleMode == true) null else true)
                            },
                            enabled = enabled,
                            modifier = Modifier.testTag("mask_sample_add")
                        ) { Text("＋ 取色", color = if (sampleMode == true) Amber else TextSecondary) }
                        TextButton(
                            onClick = {
                                onRefineModeChange(RefineMode.NONE)
                                onSampleModeChange(if (sampleMode == false) null else false)
                            },
                            enabled = enabled,
                            modifier = Modifier.testTag("mask_sample_sub")
                        ) { Text("－ 排除色", color = if (sampleMode == false) Amber else TextSecondary) }
                        TextButton(
                            onClick = {
                                update({ it.copy(mask = (it.mask as LocalMask.ColorRangeMask).copy(samples = emptyList())) })
                                commit()
                            },
                            enabled = enabled && mask.samples.isNotEmpty(),
                            modifier = Modifier.testTag("mask_sample_clear")
                        ) { Text("清空取样 (${mask.samples.size})", color = TextSecondary) }
                    }
                    if (sampleMode != null) {
                        Text(
                            "在照片上点一下：${if (sampleMode) "添加该颜色到选区" else "从选区排除该颜色"}",
                            color = Amber, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 1.6.58：反选升级为醒目的状态化开关，覆盖全部 7 种蒙版
                // （此前「控制点」无 invert 字段，点了静默无效）。选中态用
                // Amber 高亮，明确告诉用户「当前选区已被反选」。
                val inverted = mask.isInverted
                TextButton(
                    onClick = {
                        update({ l -> l.copy(mask = l.mask.withInverted(!l.mask.isInverted)) })
                        commit()
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .background(
                            if (inverted) Amber.copy(alpha = 0.18f) else CardHigh.copy(alpha = 0.30f),
                            RoundedCornerShape(50)
                        )
                        .testTag("mask_invert_toggle")
                ) {
                    Text(
                        if (inverted) "● 已反选（点击还原）" else "○ 反选选区",
                        color = if (inverted) Amber else TextPrimary
                    )
                }
                if (mask is LocalMask.ColorRangeMask) {
                    TextButton(onClick = {
                        update({ it.copy(mask = mask.copy(contiguous = !mask.contiguous)) })
                        commit()
                    }, enabled = enabled, modifier = Modifier.testTag("mask_contiguous_toggle")) {
                        Text(if (mask.contiguous) "仅连通区域" else "全图同色", color = TextSecondary)
                    }
                }
                if (mask is LocalMask.Brush && mask.strokes.isNotEmpty()) {
                    TextButton(onClick = {
                        update({ it.copy(mask = LocalMask.Brush(radius = mask.radius, feather = mask.feather)) })
                        commit()
                    }, enabled = enabled) { Text("清空笔迹", color = TextSecondary) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    update({ it.copy(enabled = !it.enabled) })
                    commit()
                }, enabled = enabled) { Text(if (layer.enabled) "已启用" else "已停用", color = Amber) }
            }

            Text(
                "快速效果（点一下立即看到选区变化）",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                quickEffects.forEach { effect ->
                    TextButton(
                        onClick = {
                            update({ it.copy(params = effect.params) })
                            commit()
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("local_quick_${effect.label}")
                    ) { Text(effect.label, color = Amber) }
                }
                TextButton(
                    onClick = {
                        update({ it.copy(params = LocalParams.Neutral) })
                        commit()
                    },
                    enabled = enabled
                ) { Text("清除", color = TextSecondary) }
            }

            Text(
                "效果参数（只作用于选区内）",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            val p = layer.params
            fun params(block: (LocalParams) -> LocalParams) = update({ it.copy(params = block(it.params)) })
            AdjustmentSlider(
                label = "亮度", value = p.brightness, valueRange = -100f..100f, step = 1f,
                onValueChange = { v -> params { it.copy(brightness = v) } },
                onValueChangeFinished = { commit() },
                onReset = { params { it.copy(brightness = 0f) }; commit() }, enabled = enabled
            )
            AdjustmentSlider(
                label = "对比度", value = p.contrast, valueRange = -100f..100f, step = 1f,
                onValueChange = { v -> params { it.copy(contrast = v) } },
                onValueChangeFinished = { commit() },
                onReset = { params { it.copy(contrast = 0f) }; commit() }, enabled = enabled
            )
            AdjustmentSlider(
                label = "高光", value = p.highlights, valueRange = -100f..100f, step = 1f,
                onValueChange = { v -> params { it.copy(highlights = v) } },
                onValueChangeFinished = { commit() },
                onReset = { params { it.copy(highlights = 0f) }; commit() }, enabled = enabled
            )
            AdjustmentSlider(
                label = "阴影", value = p.shadows, valueRange = -100f..100f, step = 1f,
                onValueChange = { v -> params { it.copy(shadows = v) } },
                onValueChangeFinished = { commit() },
                onReset = { params { it.copy(shadows = 0f) }; commit() }, enabled = enabled
            )
            AdjustmentSlider(
                label = "饱和度", value = p.saturation, valueRange = -100f..100f, step = 1f,
                onValueChange = { v -> params { it.copy(saturation = v) } },
                onValueChangeFinished = { commit() },
                onReset = { params { it.copy(saturation = 0f) }; commit() }, enabled = enabled
            )
            AdjustmentSlider(
                label = "色温", value = p.temperature, valueRange = -100f..100f, step = 1f,
                onValueChange = { v -> params { it.copy(temperature = v) } },
                onValueChangeFinished = { commit() },
                onReset = { params { it.copy(temperature = 0f) }; commit() }, enabled = enabled
            )
            AdjustmentSlider(
                label = "清晰度", value = p.clarity, valueRange = -100f..100f, step = 1f,
                onValueChange = { v -> params { it.copy(clarity = v) } },
                onValueChangeFinished = { commit() },
                onReset = { params { it.copy(clarity = 0f) }; commit() }, enabled = enabled
            )
            AdjustmentSlider(
                label = "图层强度", value = layer.strength, valueRange = 0f..100f, step = 1f,
                onValueChange = { v -> update({ it.copy(strength = v) }) },
                onValueChangeFinished = { commit() },
                onReset = { update({ it.copy(strength = 100f) }); commit() }, enabled = enabled
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}

/**
 * The photo + mask interaction surface, shared by the standalone full-screen
 * [MaskEditorOverlay]. The user works directly on the (large) preview image —
 * tap to place, single-finger drag to move / paint, single-finger press on the
 * white rotation handle to spin a radial / brush selection, and two-finger
 * pinch to scale / rotate / pan — with shape / layer controls in a compact
 * toolbar below the photo.
 *
 * The caller decides how much space this fills: in the full-screen editor it
 * is weighted to occupy essentially the whole screen. Photo area and toolbar
 * are separate vertical zones (never overlaid), so a tap near the centre of
 * the photo can never land on a shape button.
 */
@Composable
fun LocalMaskOverlay(
    local: LocalAdjustments,
    selectedLayer: Int,
    onSelectLayer: (Int) -> Unit,
    onChange: (LocalAdjustments, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean,
    displayBitmap: Bitmap?,
    /** Unedited original: colour picks and region recognition read from it. */
    sourceBitmap: Bitmap? = null,
    /** Lightroom-style +/- brush. When active, dragging paints refinement. */
    refineMode: RefineMode = RefineMode.NONE,
    onRefineModeChange: (RefineMode) -> Unit = {},
    /** Toggle the amber selection wash (purely visual, never changes the mask). */
    showOverlay: Boolean = true,
    /** Bumped by "重新识别" to force a fresh AI inference. */
    selectionVersion: Int = 0,
    /** Pending colour-range eyedropper: null = off, true = add, false = exclude. */
    sampleMode: Boolean? = null,
    onSampleTaken: () -> Unit = {},
    /** Mask ERASER (layer-scoped, works on EVERY mask kind). */
    eraserMode: Boolean = false,
    eraserRadius: Float = 0.045f,
    eraserFeather: Float = 0.5f,
    modifier: Modifier = Modifier
) {
    val layers = local.layers
    val index = selectedLayer.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    val layer = layers.getOrNull(index)
    var liveBrush by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val sourceState = rememberUpdatedState(sourceBitmap ?: displayBitmap)

    val layersState = rememberUpdatedState(layers)
    val indexState = rememberUpdatedState(index)
    val onChangeState = rememberUpdatedState(onChange)
    val onFinishedState = rememberUpdatedState(onFinished)
    // pointerInput(Unit) keeps its lambda across recompositions, so anything
    // read inside the gesture must come from an updated state.
    val enabledState = rememberUpdatedState(enabled)
    val bitmapState = rememberUpdatedState(displayBitmap)
    val refineState = rememberUpdatedState(refineMode)
    val onRefineChangeState = rememberUpdatedState(onRefineModeChange)
    val versionState = rememberUpdatedState(selectionVersion)
    val sampleModeState = rememberUpdatedState(sampleMode)
    val onSampleTakenState = rememberUpdatedState(onSampleTaken)
    val eraserState = rememberUpdatedState(eraserMode)
    val eraserRadiusState = rememberUpdatedState(eraserRadius)
    val eraserFeatherState = rememberUpdatedState(eraserFeather)

    fun update(transform: (LocalLayer) -> LocalLayer, field: String = "蒙版") {
        val list = layersState.value
        val i = indexState.value
        val current = list.getOrNull(i) ?: return
        val next = list.toMutableList()
        next[i] = transform(current).normalized()
        onChangeState.value(LocalAdjustments(next), field)
    }

    fun commit(field: String = "蒙版") = onFinishedState.value(field)

    // Semi-transparent visualisation of a content-aware selection, computed
    // off the main thread so recognising a region never janks a gesture. The
    // keys are the mask (any parameter change re-recognises) and the bitmaps.
    val currentMask = layer?.mask
    // "重新识别" invalidates the cached inference exactly ONCE per button
    // press — not on every slider tick (which also recomputes this overlay).
    androidx.compose.runtime.LaunchedEffect(selectionVersion) {
        if (selectionVersion > 0) MlSegmentation.invalidate()
    }
    val selectionOverlay by produceState<ImageBitmap?>(
        initialValue = null, currentMask, sourceBitmap, displayBitmap,
        selectionVersion, layer?.eraser
    ) {
        val mask = currentMask
        val src = sourceBitmap ?: displayBitmap
        value = if (mask != null && mask.isContentAware) {
            withContext(Dispatchers.Default) {
                buildSelectionOverlay(mask, src, layer?.eraser ?: emptyList())
            }
        } else null
    }

    Column(modifier.background(Color.Black)) {
        // Photo + interaction canvas occupy all the space ABOVE the toolbar.
        // Overlaying the toolbar on the photo made a tap near the centre land
        // on a shape button on short screens, silently changing the mask kind.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("mask_preview_area")
        ) {
        if (usable(displayBitmap)) {
            Image(
                bitmap = displayBitmap!!.asImageBitmap(),
                contentDescription = "在预览图上调整蒙版位置与形状",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("mask_preview_canvas")
                .pointerInput(Unit) {
                    // One unified gesture loop. The FIRST DOWN fixes the mode:
                    //  • on a rotation handle (radial / brush) → single-finger rotate
                    //  • on an existing brush stroke          → single-finger drag (move)
                    //  • on empty brush area                  → single-finger paint
                    //  • otherwise                           → move / tap-to-place
                    // Two fingers scale + rotate + pan any selection. The photo
                    // is re-rendered once, when the gesture finishes.
                    awaitEachGesture {
                        if (!enabledState.value) return@awaitEachGesture
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val rect = rectFor(
                            size.width.toFloat(), size.height.toFloat(), bitmapState.value
                        )
                        val aspect = if (rect.height > 0f) rect.width / rect.height else 1f
                        val baseMask = layersState.value.getOrNull(indexState.value)?.mask

                        // MASK ERASER: a single-finger drag removes scope from
                        // the CURRENT layer. Unlike the refine brush it works on
                        // EVERY mask kind, and takes priority over placement,
                        // panning and the AI refine brush.
                        if (eraserState.value && baseMask != null) {
                            val points = ArrayList<Float>()
                            val first = rect.normalized(down.position.x, down.position.y)
                            if (first != null) {
                                points += first.first; points += first.second
                                liveBrush = listOf(Offset(first.first, first.second))
                            }
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null) {
                                    val p = rect.normalized(change.position.x, change.position.y)
                                    if (p != null) {
                                        if (points.size < 2 ||
                                            hypot(p.first - points[points.size - 2],
                                                p.second - points[points.size - 1]) > 0.004f) {
                                            points += p.first; points += p.second
                                            liveBrush = liveBrush + Offset(p.first, p.second)
                                        }
                                    }
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            if (points.size >= 2) {
                                val r = eraserRadiusState.value
                                val f = eraserFeatherState.value
                                update({ l -> appendEraserStroke(l, points, r, f) }, "橡皮擦")
                                commit("橡皮擦")
                            }
                            liveBrush = emptyList()
                            return@awaitEachGesture
                        }

                        // Lightroom-style +/- brush: a single-finger drag paints
                        // an "add to selection" or "subtract from selection"
                        // stroke on top of the AI result. It takes priority over
                        // placement / panning so a mis-tap never moves the mask.
                        val mode = refineState.value
                        if (mode != RefineMode.NONE && baseMask != null && baseMask.isContentAware) {
                            val points = ArrayList<Float>()
                            val first = rect.normalized(down.position.x, down.position.y)
                            if (first != null) {
                                points += first.first; points += first.second
                                liveBrush = listOf(Offset(first.first, first.second))
                            }
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null) {
                                    val p = rect.normalized(change.position.x, change.position.y)
                                    if (p != null) {
                                        // Decimate: keep the stroke light.
                                        if (points.size < 2 ||
                                            hypot(p.first - points[points.size - 2],
                                                p.second - points[points.size - 1]) > 0.004f) {
                                            points += p.first; points += p.second
                                            liveBrush = liveBrush + Offset(p.first, p.second)
                                        }
                                    }
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            if (points.size >= 2) {
                                update({ l ->
                                    l.copy(mask = appendRefineStroke(l.mask, points, mode == RefineMode.ERASE))
                                })
                                commit()
                            }
                            liveBrush = emptyList()
                            return@awaitEachGesture
                        }

                        val brush = baseMask as? LocalMask.Brush
                        val brushMode = brush != null

                        val handle = baseMask?.let { maskRotationHandle(it, rect, aspect) }
                        val handleRadius = 30.dp.toPx()
                        val rotateMode = handle != null &&
                            (down.position - handle).getDistance() <= handleRadius
                        val downN = rect.normalized(down.position.x, down.position.y)
                        val strokeHit = brush != null && downN != null &&
                            hitBrushStroke(
                                brush, downN.first, downN.second, aspect,
                                (brush.radius * 1.2f).coerceAtLeast(0.025f)
                            )
                        val panMode = brushMode && strokeHit && !rotateMode
                        val paintMode = brushMode && !strokeHit && !rotateMode

                        // Rotation pivot (screen pixels) = selection centre.
                        val pivot = when (baseMask) {
                            is LocalMask.RadialGradient -> Offset(
                                rect.left + baseMask.x * rect.width,
                                rect.top + baseMask.y * rect.height
                            )
                            is LocalMask.Brush -> {
                                val (cx, cy) = baseMask.centre()
                                val c = baseMask.mapPoint(cx, cy, aspect)
                                Offset(rect.left + c.first * rect.width, rect.top + c.second * rect.height)
                            }
                            else -> Offset.Zero
                        }
                        val startAngle = Math.toDegrees(
                            atan2((down.position.y - pivot.y).toDouble(),
                                (down.position.x - pivot.x).toDouble())
                        ).toFloat()

                        var totalScale = 1f
                        var totalRotation = 0f
                        var panX = 0f
                        var panY = 0f
                        var moved = false
                        var multiPointer = false
                        val brushPoints = ArrayList<Offset>()
                        liveBrush = emptyList()

                        fun panNormalized(): Pair<Float, Float> = Pair(
                            panX / rect.width.coerceAtLeast(1f),
                            panY / rect.height.coerceAtLeast(1f)
                        )

                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val zoom = event.calculateZoom()
                            val rotation = event.calculateRotation()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)

                            when {
                                pressed >= 2 -> {
                                    multiPointer = true
                                    moved = true
                                    totalScale = (totalScale * zoom).coerceIn(0.15f, 8f)
                                    totalRotation += rotation
                                    panX += pan.x
                                    panY += pan.y
                                    if (baseMask != null) {
                                        val transformed = transformMask(baseMask, totalScale, totalRotation, aspect)
                                        val (dx, dy) = panNormalized()
                                        val panned = panMask(transformed, dx, dy)
                                        update({ l -> l.copy(mask = panned) })
                                    }
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                }
                                pressed == 1 && !multiPointer && rotateMode -> {
                                    if (pan.getDistance() > 2f) moved = true
                                    val angle = Math.toDegrees(
                                        atan2((centroid.y - pivot.y).toDouble(),
                                            (centroid.x - pivot.x).toDouble())
                                    ).toFloat()
                                    var delta = angle - startAngle
                                    while (delta > 180f) delta -= 360f
                                    while (delta < -180f) delta += 360f
                                    if (moved) baseMask?.let { bm ->
                                        val rotated = rotateMaskBy(bm, delta, aspect)
                                        update({ l -> l.copy(mask = rotated) })
                                    }
                                    event.changes.first { it.pressed }.consume()
                                }
                                pressed == 1 && !multiPointer && panMode -> {
                                    panX += pan.x
                                    panY += pan.y
                                    if (pan.getDistance() > 2f) moved = true
                                    if (moved) {
                                        val (dx, dy) = panNormalized()
                                        baseMask?.let { bm ->
                                            update({ l -> l.copy(mask = panMask(bm, dx, dy)) })
                                        }
                                    }
                                    event.changes.first { it.pressed }.consume()
                                }
                                pressed == 1 && !multiPointer -> {
                                    if (pan.getDistance() > 3f) moved = true
                                    if (moved) {
                                        val nx = ((centroid.x - rect.left) / rect.width).coerceIn(0f, 1f)
                                        val ny = ((centroid.y - rect.top) / rect.height).coerceIn(0f, 1f)
                                        if (paintMode) {
                                            brushPoints += Offset(nx, ny)
                                            liveBrush = brushPoints.toList()
                                        } else {
                                            update({ l -> l.copy(mask = placeMask(l.mask, nx, ny)) })
                                        }
                                        event.changes.first { it.pressed }.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        when {
                            paintMode && brushPoints.size >= 2 -> {
                                val flat = ArrayList<Float>(brushPoints.size * 2)
                                brushPoints.forEach { flat += it.x; flat += it.y }
                                update({ l -> l.copy(mask = appendBrushStroke(l.mask, flat, aspect)) })
                                commit()
                                liveBrush = emptyList()
                            }
                            !moved && !multiPointer && !rotateMode -> {
                                val p = rect.normalized(down.position.x, down.position.y)
                                if (p != null) {
                                        val sampler = sourceState.value
                                        val pending = sampleModeState.value
                                        update({ l ->
                                            val m = l.mask
                                            val next = when {
                                                m is LocalMask.ColorRangeMask && pending != null ->
                                                    addColorSample(m, sampler, p.first, p.second, pending)
                                                m is LocalMask.ColorRangeMask ->
                                                    // Tap = pick the colour under the finger
                                                    // from the ORIGINAL photo, then re-select.
                                                    tapColorRange(m, sampler, p.first, p.second)
                                                else -> placeMask(m, p.first, p.second)
                                            }
                                            l.copy(mask = next)
                                        })
                                        if (pending != null) onSampleTakenState.value()
                                        commit()
                                }
                            }
                            else -> commit()
                        }
                    }
                }
        ) {
            val rect = rectFor(size.width, size.height, displayBitmap)
            val aspect = if (rect.height > 0f) rect.width / rect.height else 1f
            // Draw the recognised area as a RED mask-scope shadow BEFORE the
            // outline, so the user can see exactly what "选择主体/天空/颜色"
            // selected and re-tap to correct it.
            if (showOverlay) selectionOverlay?.let { overlay ->
                drawImage(
                    image = overlay,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(
                        rect.width.roundToInt().coerceAtLeast(1),
                        rect.height.roundToInt().coerceAtLeast(1)
                    )
                )
            }
            layer?.let {
                // A refine drag is red (adding scope); an eraser drag is drawn
                // in white below, so it never looks like adding scope back.
                drawMaskOutline(
                    it.mask, rect,
                    if (eraserMode) emptyList() else liveBrush,
                    aspect, it.eraser
                )
            }
            if (eraserMode && liveBrush.isNotEmpty()) {
                val r = (eraserRadius * maxOf(rect.width, rect.height)).coerceAtLeast(3f)
                liveBrush.forEach { p ->
                    drawCircle(
                        Color.White.copy(alpha = 0.16f), r,
                        Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)
                    )
                }
                val head = liveBrush.last()
                drawCircle(
                    Color.White.copy(alpha = 0.9f), r,
                    Offset(rect.left + head.x * rect.width, rect.top + head.y * rect.height),
                    style = Stroke(2.dp.toPx())
                )
            }
        }
        }

        // Compact toolbar: shape / layer management live below the photo.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("mask_toolbar")
        ) {
            Text(
                "红色阴影为选中区域 · 点图重定位/取色 · 参数区「橡皮擦」可擦除任意蒙版作用域 · 「＋添加 / －擦除」笔刷精修 · 双指缩放旋转",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MaskKind.entries.forEach { kind ->
                    TextButton(
                        onClick = {
                            // Switching to 颜色范围 auto-picks the most vivid
                            // colour from the original photo (not the dead-centre
                            // pixel, which is often a white dress / grey wall), so
                            // the tool works out of the box. Tapping the photo
                            // re-picks the colour under the finger.
                            val sampler = sourceBitmap ?: displayBitmap
                            update({ l ->
                                val base = defaultMask(kind)
                                val seeded = if (base is LocalMask.ColorRangeMask) {
                                    val (sx, sy) = mostVividSeed(sampler)
                                    tapColorRange(base, sampler, sx, sy)
                                } else base
                                l.copy(mask = seeded)
                            })
                            commit()
                        },
                        enabled = enabled && layer != null,
                        modifier = Modifier.testTag("mask_kind_${kind.name}")
                    ) {
                        Text(
                            kind.label,
                            color = if (layer != null && kindOf(layer.mask) == kind) Amber else TextSecondary
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val fresh = LocalLayer(
                            id = "local_${System.currentTimeMillis()}",
                            name = "蒙版 ${layers.size + 1}",
                            mask = defaultMask(if (layer != null) kindOf(layer.mask) else MaskKind.POINT),
                            params = LocalParams(brightness = 25f)
                        )
                        onChange(
                            LocalAdjustments((layers + fresh).take(LocalAdjustments.MAX_LAYERS)),
                            "蒙版"
                        )
                        onSelectLayer(layers.size)
                        commit()
                    },
                    enabled = enabled && layers.size < LocalAdjustments.MAX_LAYERS,
                    modifier = Modifier.testTag("mask_add_layer")
                ) { Text("+ 添加", color = Amber) }
                if (layer != null) {
                    TextButton(
                        onClick = {
                            val next = layers.toMutableList().apply { removeAt(index) }
                            onChange(LocalAdjustments(next), "蒙版")
                            onSelectLayer((index - 1).coerceAtLeast(0))
                            commit()
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("mask_remove_layer")
                    ) { Text("删除", color = TextSecondary) }
                }
                if (layers.size > 1) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        layers.forEachIndexed { i, _ ->
                            TextButton(
                                onClick = { onSelectLayer(i) },
                                enabled = enabled,
                                modifier = Modifier.testTag("mask_overlay_layer_$i")
                            ) {
                                Text("${i + 1}", color = if (i == index) Amber else TextSecondary)
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
