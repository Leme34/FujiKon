package com.fujikon.app.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.LensBlur
import com.fujikon.app.render.autoFocusPoint
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen Lightroom-style lens blur editor.
 *
 * The photo is shown uncropped so [LensBlur.focusX] / [LensBlur.focusY] map
 * straight onto the rendered frame. Tapping anywhere moves the focus region,
 * dragging the sliders previews the real pipeline, and the parent applies the
 * same [LensBlur] state that the export uses.
 */
@Composable
fun LensBlurEditorOverlay(
    bitmap: Bitmap,
    initial: LensBlur,
    onPreview: (LensBlur) -> Unit,
    onApply: (LensBlur) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial.normalized()) }
    var lastPreviewAt by remember { mutableLongStateOf(0L) }

    /**
     * Throttles live previews so dragging a slider or the focus point does not
     * queue a render for every pixel of movement.
     */
    fun publish(next: LensBlur, force: Boolean = false) {
        draft = next
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastPreviewAt >= PREVIEW_INTERVAL_MS) {
            lastPreviewAt = now
            onPreview(next)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        // The controls keep their natural height and the photo takes whatever is
        // left, so the preview stays as large as possible. The panel itself
        // scrolls only when a very short window cannot fit every slider, and the
        // safe drawing padding keeps the last one clear of the navigation bar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消镜头模糊", tint = TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "镜头模糊",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        "点按照片选择焦点 · 拖动移动焦点",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                TextButton(onClick = { onApply(draft.normalized()) }) {
                    Text("完成", color = Amber, fontWeight = FontWeight.Bold)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                FocusCanvas(
                    bitmap = bitmap,
                    draft = draft,
                    onFocusChange = { x, y, force ->
                        publish(draft.copy(focusX = x, focusY = y), force)
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkElevated)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 48.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.Default) {
                                val point = autoFocusPoint(bitmap) ?: return@launch
                                val next = draft.copy(focusX = point.first, focusY = point.second)
                                draft = next
                                publish(next, force = true)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Text("自动对焦", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = {
                            publish(
                                draft.copy(
                                    amount = 0f,
                                    focusSize = 32f,
                                    feather = 50f,
                                    highlights = 0f
                                ),
                                force = true
                            )
                        }
                    ) {
                        Text("重置", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                }

                AdjustmentSlider(
                    label = "模糊强度",
                    value = draft.amount,
                    valueRange = 0f..LensBlur.MAX_AMOUNT,
                    onValueChange = { publish(draft.copy(amount = it)) },
                    onValueChangeFinished = { publish(draft, force = true) },
                    onReset = { publish(draft.copy(amount = 0f), force = true) },
                    fillFromStart = true,
                    formattedValue = draft.amount.roundToInt().toString()
                )
                AdjustmentSlider(
                    label = "聚焦范围",
                    value = draft.focusSize,
                    valueRange = LensBlur.MIN_FOCUS_SIZE..LensBlur.MAX_FOCUS_SIZE,
                    onValueChange = { publish(draft.copy(focusSize = it)) },
                    onValueChangeFinished = { publish(draft, force = true) },
                    onReset = { publish(draft.copy(focusSize = 32f), force = true) },
                    fillFromStart = true,
                    formattedValue = "${draft.focusSize.roundToInt()}%"
                )
                AdjustmentSlider(
                    label = "边缘过渡",
                    value = draft.feather,
                    valueRange = 0f..LensBlur.MAX_AMOUNT,
                    onValueChange = { publish(draft.copy(feather = it)) },
                    onValueChangeFinished = { publish(draft, force = true) },
                    onReset = { publish(draft.copy(feather = 50f), force = true) },
                    fillFromStart = true,
                    formattedValue = draft.feather.roundToInt().toString()
                )
                AdjustmentSlider(
                    label = "散景高光",
                    value = draft.highlights,
                    valueRange = 0f..LensBlur.MAX_AMOUNT,
                    onValueChange = { publish(draft.copy(highlights = it)) },
                    onValueChangeFinished = { publish(draft, force = true) },
                    onReset = { publish(draft.copy(highlights = 0f), force = true) },
                    fillFromStart = true,
                    formattedValue = draft.highlights.roundToInt().toString()
                )
            }
        }
    }
}

/**
 * Draws the photo with a letterboxed fit and overlays the focus ring. Tapping
 * or dragging anywhere inside the frame moves the focus point.
 */
@Composable
private fun FocusCanvas(
    bitmap: Bitmap,
    draft: LensBlur,
    onFocusChange: (Float, Float, Boolean) -> Unit
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val frame = fitFrame(
                        size.width.toFloat(),
                        size.height.toFloat(),
                        bitmap.width,
                        bitmap.height
                    )
                    fun update(position: Offset, force: Boolean) {
                        val fx = ((position.x - frame.left) / frame.width).coerceIn(0f, 1f)
                        val fy = ((position.y - frame.top) / frame.height).coerceIn(0f, 1f)
                        onFocusChange(fx, fy, force)
                    }

                    // A tap is a deliberate focus choice, so preview it right
                    // away; drags stay on the throttle.
                    update(down.position, true)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position != down.position) {
                            update(change.position, false)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val scale = min(
            size.width / image.width.toFloat(),
            size.height / image.height.toFloat()
        )
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f

        drawImage(
            image = image,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt())
        )

        val centerX = left + draft.focusX * drawWidth
        val centerY = top + draft.focusY * drawHeight
        val radius = (draft.focusSize / 100f * min(drawWidth, drawHeight)).coerceAtLeast(12.dp.toPx())
        val center = Offset(centerX, centerY)

        drawCircle(Color.Black.copy(alpha = 0.45f), radius, center, style = Stroke(6.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.95f), radius, center, style = Stroke(2.dp.toPx()))
        drawCircle(Amber, 4.dp.toPx(), center)
        drawLine(
            Color.White.copy(alpha = 0.85f),
            Offset(centerX - 10.dp.toPx(), centerY),
            Offset(centerX + 10.dp.toPx(), centerY),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            Color.White.copy(alpha = 0.85f),
            Offset(centerX, centerY - 10.dp.toPx()),
            Offset(centerX, centerY + 10.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    }
}

private data class FitFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

private fun fitFrame(
    canvasWidth: Float,
    canvasHeight: Float,
    bitmapWidth: Int,
    bitmapHeight: Int
): FitFrame {
    if (canvasWidth <= 0f || canvasHeight <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return FitFrame(0f, 0f, 1f, 1f)
    }
    val scale = min(canvasWidth / bitmapWidth, canvasHeight / bitmapHeight)
    val width = bitmapWidth * scale
    val height = bitmapHeight * scale
    return FitFrame(
        left = (canvasWidth - width) / 2f,
        top = (canvasHeight - height) / 2f,
        width = width,
        height = height
    )
}

private const val PREVIEW_INTERVAL_MS = 120L
