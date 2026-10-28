package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a bitmap with pinch-zoom and panning. Holding one finger still for
 * ~430 ms toggles the before/after comparison while the finger is down.
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    original: Bitmap?,
    comparing: Boolean,
    onComparingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = bitmap
) {
    val haptic = LocalHapticFeedback.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // Key the gesture state by the photo, not by the bitmap currently shown:
    // toggling before/after swaps `bitmap` and must not reset zoom or restart
    // the pointer loop (which would immediately cancel the comparison).
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    fun clamped(raw: Offset, currentScale: Float): Offset {
        if (currentScale <= 1f || viewSize == IntSize.Zero) return Offset.Zero
        val maxX = viewSize.width * (currentScale - 1f) / 2f
        val maxY = viewSize.height * (currentScale - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged {
                viewSize = it
                offset = clamped(offset, scale)
            }
            .pointerInput(resetKey, viewSize) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        var moved = false
                        var maxPointers = 1
                        var longPressed = false

                        val timer = launch {
                            delay(430)
                            if (!moved && maxPointers == 1) {
                                longPressed = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onComparingChange(true)
                            }
                        }

                        try {
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                maxPointers = max(maxPointers, pressed)
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()

                                if (pressed >= 2) {
                                    moved = true
                                    if (zoom != 1f) {
                                        val oldScale = scale
                                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
                                        val focal = centroid - center
                                        scale = newScale
                                        offset = clamped(
                                            (offset - focal) * (newScale / oldScale) + focal + pan,
                                            newScale
                                        )
                                    } else if (pan != Offset.Zero) {
                                        offset = clamped(offset + pan, scale)
                                    }
                                } else if (pressed == 1) {
                                    if (pan.getDistance() > 3f) moved = true
                                    if (scale > 1f && pan != Offset.Zero) {
                                        offset = clamped(offset + pan, scale)
                                    }
                                }

                                if (moved && longPressed) {
                                    longPressed = false
                                    onComparingChange(false)
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            timer.cancel()
                            if (longPressed) onComparingChange(false)
                        }

                        val duration = System.currentTimeMillis() - downTime
                        if (!moved && maxPointers == 1 && duration < 300) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 320) {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    offset = Offset.Zero
                                }
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                            }
                        }
                    }
                }
            }
    ) {
        val shown = if (comparing && original != null) original else bitmap
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
