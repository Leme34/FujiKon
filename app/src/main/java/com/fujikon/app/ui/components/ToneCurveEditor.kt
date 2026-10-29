package com.fujikon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A Lightroom-style point curve editor.
 *
 * Tap an empty area to add a point, drag a point to move it, and double-tap an
 * interior point to remove it. [onPointsChange] updates the graph continuously,
 * [onDrag] fires alongside it for throttled live photo previews, and
 * [onInteractionFinished] fires once per gesture so callers can commit the
 * final render.
 */
@Composable
fun ToneCurveEditor(
    points: List<ToneCurvePoint>,
    basePoints: List<Pair<Float, Float>>,
    selectedIndex: Int?,
    onPointsChange: (List<ToneCurvePoint>) -> Unit,
    onSelectedChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDrag: () -> Unit = {},
    onInteractionFinished: () -> Unit = {},
    maxPoints: Int = 16
) {
    val currentPoints by rememberUpdatedState(points)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnChange by rememberUpdatedState(onPointsChange)
    val currentOnSelect by rememberUpdatedState(onSelectedChange)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnFinished by rememberUpdatedState(onInteractionFinished)

    val density = LocalDensity.current
    val thresholdPx = with(density) { 28.dp.toPx() }
    val touchSlopPx = with(density) { 8.dp.toPx() }

    Canvas(
        modifier = modifier
            .semantics { contentDescription = "色调曲线编辑区" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var lastTapTime = 0L
                var lastTapPosition = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    if (width <= 0f || height <= 0f) return@awaitEachGesture

                    val candidate = nearestPoint(
                        currentPoints,
                        down.position,
                        width,
                        height,
                        thresholdPx
                    )
                    if (candidate != null) currentOnSelect(candidate)

                    var dragging = false
                    var changed = false
                    var cancelled = false
                    var moved = Offset.Zero
                    var upChange: PointerInputChange? = null

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        if (change.changedToUpIgnoreConsumed()) {
                            upChange = change
                            break
                        }
                        if (!dragging) {
                            moved += change.positionChangeIgnoreConsumed()
                            if (moved.getDistance() > touchSlopPx) dragging = true
                        }
                        if (dragging && candidate != null) {
                            change.consume()
                            val list = currentPoints.toMutableList()
                            if (candidate in list.indices) {
                                val logicalX = (change.position.x / width).coerceIn(0f, 1f)
                                val logicalY = (1f - change.position.y / height).coerceIn(0f, 1f)
                                val minX = if (candidate == 0) 0f else list[candidate - 1].x + 0.008f
                                val maxX = if (candidate == list.lastIndex) 1f else list[candidate + 1].x - 0.008f
                                val x = when (candidate) {
                                    0 -> 0f
                                    list.lastIndex -> 1f
                                    // A previous drag can leave neighbors closer than
                                    // the 0.008 gap. Keep x unchanged rather than
                                    // calling coerceIn with an inverted range.
                                    else -> if (minX <= maxX) logicalX.coerceIn(minX, maxX)
                                    else list[candidate].x
                                }
                                list[candidate] = ToneCurvePoint(x, logicalY)
                                currentOnChange(list)
                                currentOnDrag()
                                changed = true
                            }
                        }
                    }

                    if (cancelled) {
                        lastTapTime = 0L
                        return@awaitEachGesture
                    }
                    if (dragging) {
                        if (changed) currentOnFinished()
                        lastTapTime = 0L
                        return@awaitEachGesture
                    }

                    val tapTime = upChange?.uptimeMillis ?: down.uptimeMillis
                    val tapPosition = upChange?.position ?: down.position
                    val isDoubleTap = lastTapTime != 0L &&
                        tapTime - lastTapTime in 0..viewConfiguration.doubleTapTimeoutMillis &&
                        (tapPosition - lastTapPosition).getDistance() <= thresholdPx

                    if (isDoubleTap) {
                        if (candidate != null && candidate != 0 && candidate != currentPoints.lastIndex) {
                            currentOnChange(currentPoints.filterIndexed { index, _ -> index != candidate })
                            currentOnSelect(null)
                            currentOnFinished()
                        }
                        lastTapTime = 0L
                    } else {
                        if (candidate == null && currentPoints.size < maxPoints) {
                            val x = (tapPosition.x / width).coerceIn(0.02f, 0.98f)
                            val y = (1f - tapPosition.y / height).coerceIn(0f, 1f)
                            if (currentPoints.none { abs(it.x - x) < 0.025f }) {
                                val added = ToneCurvePoint(x, y)
                                val updated = (currentPoints + added).sortedBy { it.x }
                                currentOnChange(updated)
                                currentOnSelect(updated.indexOf(added))
                                currentOnFinished()
                            }
                        }
                        lastTapTime = tapTime
                        lastTapPosition = tapPosition
                    }
                }
            }
    ) {
        val chartHeight = size.height
        val width = size.width
        if (width <= 0f || chartHeight <= 0f) return@Canvas

        for (i in 1..3) {
            val x = width * i / 4f
            drawLine(Outline, Offset(x, 0f), Offset(x, chartHeight), 1.dp.toPx())
            val y = chartHeight * i / 4f
            drawLine(Outline, Offset(0f, y), Offset(width, y), 1.dp.toPx())
        }
        drawLine(
            color = TextSecondary.copy(alpha = 0.22f),
            start = Offset(0f, chartHeight),
            end = Offset(width, 0f),
            strokeWidth = 1f
        )

        if (basePoints.size >= 2) {
            val basePath = Path()
            basePoints.forEachIndexed { index, (x, y) ->
                val px = x.coerceIn(0f, 1f) * width
                val py = (1f - y.coerceIn(0f, 1f)) * chartHeight
                if (index == 0) basePath.moveTo(px, py) else basePath.lineTo(px, py)
            }
            drawPath(
                path = basePath,
                color = TextSecondary.copy(alpha = 0.38f),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        val sampled = ToneCurve(points).previewPoints(96)
        if (sampled.size >= 2) {
            val curvePath = Path()
            sampled.forEachIndexed { index, (x, y) ->
                val px = x.coerceIn(0f, 1f) * width
                val py = (1f - y.coerceIn(0f, 1f)) * chartHeight
                if (index == 0) curvePath.moveTo(px, py) else curvePath.lineTo(px, py)
            }
            drawPath(
                path = curvePath,
                color = Amber.copy(alpha = 0.16f),
                style = Stroke(
                    width = 8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = curvePath,
                color = Amber,
                style = Stroke(
                    width = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        points.forEachIndexed { index, point ->
            val center = Offset(
                point.x.coerceIn(0f, 1f) * width,
                (1f - point.y.coerceIn(0f, 1f)) * chartHeight
            )
            val selected = index == currentSelected
            drawCircle(
                color = Ink,
                radius = if (selected) 7.dp.toPx() else 5.5.dp.toPx(),
                center = center
            )
            drawCircle(
                color = if (selected) Amber else Color.White.copy(alpha = 0.9f),
                radius = if (selected) 5.dp.toPx() else 3.5.dp.toPx(),
                center = center
            )
        }
    }
}

private fun nearestPoint(
    points: List<ToneCurvePoint>,
    position: Offset,
    width: Float,
    height: Float,
    threshold: Float
): Int? {
    if (width <= 0f || height <= 0f) return null
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    points.forEachIndexed { index, point ->
        val dx = position.x - point.x.coerceIn(0f, 1f) * width
        val dy = position.y - (1f - point.y.coerceIn(0f, 1f)) * height
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return if (bestDistance <= threshold) best else null
}
