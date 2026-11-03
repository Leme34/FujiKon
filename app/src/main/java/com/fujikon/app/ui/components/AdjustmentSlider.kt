package com.fujikon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A compact Lightroom-style slider with a centre-zero track.
 *
 * Dragging publishes changes continuously. Double-tapping anywhere on the
 * slider calls [onReset], which the editor uses to return that control to 0.
 */
@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: () -> Unit = {},
    step: Float = 1f,
    formattedValue: String = formatSliderValue(value, step),
    enabled: Boolean = true,
    /** Fills the track from the left edge instead of the centre for 0..100 controls. */
    fillFromStart: Boolean = false
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentOnReset by rememberUpdatedState(onReset)
    val currentValue by rememberUpdatedState(value)

    val density = LocalDensity.current
    val tapThresholdPx = with(density) { 24.dp.toPx() }
    val safeStep = step.coerceAtLeast(0.001f)
    val modified = abs(value) > safeStep * 0.5f

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    shadow = Shadow(Color.Black.copy(alpha = 0.68f), blurRadius = 4f)
                ),
                color = TextPrimary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formattedValue,
                style = MaterialTheme.typography.labelMedium.copy(
                    shadow = Shadow(Color.Black.copy(alpha = 0.68f), blurRadius = 4f)
                ),
                color = if (modified) Amber else TextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = formattedValue
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                    setProgress { target ->
                        currentOnChange(snap(target, valueRange, safeStep))
                        currentOnFinished()
                        true
                    }
                }
                .pointerInput(enabled, valueRange, safeStep) {
                    if (!enabled) return@pointerInput
                    var lastTapTime = 0L
                    var lastTapX = 0f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width
                        val downX = down.position.x
                        var dragging = false
                        var verticalGesture = false
                        var lastX = downX
                        var upChange: PointerInputChange? = null

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            if (change.changedToUpIgnoreConsumed()) {
                                upChange = change
                                break
                            }
                            if (!dragging && !verticalGesture) {
                                // Only claim the gesture once it is clearly
                                // horizontal. A vertical swipe belongs to the
                                // sheet's scroll container, not to the slider.
                                val dx = abs(change.position.x - downX)
                                val dy = abs(change.position.y - down.position.y)
                                if (dx > viewConfiguration.touchSlop && dx > dy) {
                                    dragging = true
                                } else if (dy > viewConfiguration.touchSlop && dy >= dx) {
                                    verticalGesture = true
                                }
                            }
                            if (dragging) {
                                change.consume()
                                lastX = change.position.x
                                currentOnChange(
                                    valueAt(lastX, width, valueRange, safeStep)
                                )
                            }
                        }

                        if (upChange == null) {
                            lastTapTime = 0L
                            return@awaitEachGesture
                        }
                        if (dragging) {
                            currentOnChange(valueAt(lastX, width, valueRange, safeStep))
                            currentOnFinished()
                            lastTapTime = 0L
                            return@awaitEachGesture
                        }
                        if (verticalGesture) {
                            lastTapTime = 0L
                            return@awaitEachGesture
                        }

                        val tapTime = upChange.uptimeMillis
                        val isDoubleTap = lastTapTime != 0L &&
                            tapTime - lastTapTime in 0..viewConfiguration.doubleTapTimeoutMillis &&
                            abs(downX - lastTapX) <= tapThresholdPx
                        if (isDoubleTap) {
                            currentOnReset()
                            lastTapTime = 0L
                        } else {
                            currentOnChange(valueAt(downX, width, valueRange, safeStep))
                            currentOnFinished()
                            lastTapTime = tapTime
                            lastTapX = downX
                        }
                    }
                }
        ) {
            val trackStart = 8.dp.toPx()
            val trackEnd = size.width - 8.dp.toPx()
            val trackLength = (trackEnd - trackStart).coerceAtLeast(1f)
            val fraction = ((currentValue - valueRange.start) /
                (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val valueX = trackStart + trackLength * fraction
            val zeroX = if (fillFromStart) trackStart else trackStart + trackLength * 0.5f
            val centerY = size.height * 0.5f

            drawLine(
                color = Outline.copy(alpha = 0.9f),
                start = Offset(trackStart, centerY),
                end = Offset(trackEnd, centerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            if (!fillFromStart) {
                drawLine(
                    color = Color.White.copy(alpha = 0.26f),
                    start = Offset(zeroX, centerY - 5.dp.toPx()),
                    end = Offset(zeroX, centerY + 5.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            if (modified) {
                drawLine(
                    color = Amber.copy(alpha = 0.82f),
                    start = Offset(zeroX, centerY),
                    end = Offset(valueX, centerY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            drawCircle(
                color = Color.Black.copy(alpha = 0.38f),
                radius = 9.dp.toPx(),
                center = Offset(valueX, centerY)
            )
            drawCircle(
                color = if (modified) Amber else Color.White.copy(alpha = 0.88f),
                radius = 6.dp.toPx(),
                center = Offset(valueX, centerY)
            )
        }
    }
}

fun formatSliderValue(value: Float, step: Float = 1f): String {
    if (abs(value) < step * 0.05f) return "0"
    return if (step < 1f) {
        val rounded = (value * 10f).roundToInt() / 10f
        if (rounded > 0f) "+${String.format(java.util.Locale.US, "%.1f", rounded)}"
        else String.format(java.util.Locale.US, "%.1f", rounded)
    } else {
        val rounded = value.roundToInt()
        if (rounded > 0) "+$rounded" else rounded.toString()
    }
}

private fun valueAt(
    x: Float,
    width: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float
): Float {
    if (width <= 0) return range.start
    val fraction = (x / width).coerceIn(0f, 1f)
    return snap(range.start + (range.endInclusive - range.start) * fraction, range, step)
}

private fun snap(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float
): Float {
    val snapped = (value / step).roundToInt() * step
    return snapped.coerceIn(range.start, range.endInclusive)
}
