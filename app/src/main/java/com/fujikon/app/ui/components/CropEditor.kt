package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.CropAspect
import com.fujikon.app.data.CropCorner
import com.fujikon.app.data.CropRect
import com.fujikon.app.data.CropSpec
import com.fujikon.app.data.EditCrop
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

private data class AspectOption(
    val label: String,
    val ratio: Float?,
    val aspect: CropAspect?
)

/**
 * Full-screen Lightroom-style crop and straighten editor.
 *
 * The photo sits behind a fixed crop window: dragging inside the window moves
 * the framing, pinching zooms the crop, corner handles resize it, the rotate
 * buttons turn the photo by quarter turns and the straighten slider applies a
 * fine correction. Nothing is committed until "完成".
 */
@Composable
fun CropEditorOverlay(
    bitmap: Bitmap,
    initial: EditCrop,
    onApply: (EditCrop) -> Unit,
    onCancel: () -> Unit
) {
    val start = remember(initial) { initial.normalized() }
    var rotation by remember(initial, bitmap) { mutableIntStateOf(start.rotation) }
    val displayWidth = if (rotation % 180 == 90) bitmap.height else bitmap.width
    val displayHeight = if (rotation % 180 == 90) bitmap.width else bitmap.height
    val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()

    val draftState = remember(initial, bitmap) {
        mutableStateOf(start.toCropSpec(displayWidth, displayHeight, displayAspect))
    }
    var selectedAspect by remember(initial, bitmap) { mutableIntStateOf(start.aspectIndex()) }

    val options = remember(displayAspect) {
        buildList {
            add(AspectOption("自由", null, null))
            CropAspect.entries.forEach { aspect ->
                add(AspectOption(aspect.label, aspect.ratio ?: displayAspect, aspect))
            }
        }
    }

    fun selectAspect(index: Int) {
        selectedAspect = index
        draftState.value = draftState.value.withAspectRatio(options[index].ratio, displayAspect)
    }

    fun rotate(delta: Int) {
        val spec = draftState.value
        rotation = (rotation + delta + 360) % 360
        val nextWidth = if (rotation % 180 == 90) bitmap.height else bitmap.width
        val nextHeight = if (rotation % 180 == 90) bitmap.width else bitmap.height
        val nextAspect = nextWidth.toFloat() / nextHeight.toFloat()
        val turned = if (delta > 0) {
            // Clockwise: (x, y) -> (1 - y, x).
            spec.copy(
                left = 1f - spec.bottom,
                top = spec.left,
                right = 1f - spec.top,
                bottom = spec.right
            )
        } else {
            // Counter-clockwise: (x, y) -> (y, 1 - x).
            spec.copy(
                left = spec.top,
                top = 1f - spec.right,
                right = spec.bottom,
                bottom = 1f - spec.left
            )
        }
        draftState.value = turned.normalized(nextAspect)
    }

    fun commit() {
        val spec = draftState.value.normalized(displayAspect)
        val rect = CropRect(spec.left, spec.top, spec.right, spec.bottom)
        val locked = options[selectedAspect].aspect
        onApply(
            EditCrop(
                aspect = locked?.id ?: CropAspect.ORIGINAL.id,
                rotation = rotation,
                straighten = spec.angle,
                customRect = rect.takeUnless { it.isFull }
            ).normalized()
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消裁剪", tint = TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "裁剪 / 纠偏",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        "拖动移动 · 双指缩放 · 角点调整",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                TextButton(onClick = ::commit) {
                    Text("完成", color = Amber, fontWeight = FontWeight.Bold)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                CropCanvas(
                    bitmap = bitmap,
                    displayAspect = displayAspect,
                    rotation = rotation,
                    draftState = draftState
                )
            }

            CropControls(
                draft = draftState.value,
                options = options,
                selectedAspect = selectedAspect,
                rotation = rotation,
                onSelectAspect = ::selectAspect,
                onRotate = ::rotate,
                onAngleChange = { angle ->
                    draftState.value = draftState.value.copy(
                        angle = angle.coerceIn(-CropSpec.MAX_ANGLE, CropSpec.MAX_ANGLE)
                    )
                },
                onReset = {
                    rotation = 0
                    selectedAspect = 0
                    draftState.value = CropSpec.None
                }
            )
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    displayAspect: Float,
    rotation: Int,
    draftState: MutableState<CropSpec>
) {
    val gridColor = Color.White.copy(alpha = 0.38f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val frameWidth: Dp
        val frameHeight: Dp
        if (availableWidth / availableHeight > displayAspect) {
            frameHeight = availableHeight
            frameWidth = availableHeight * displayAspect
        } else {
            frameWidth = availableWidth
            frameHeight = availableWidth / displayAspect
        }

        val draft = draftState.value
        val quarterTurn = rotation % 180 == 90
        val imageFrameWidth = if (quarterTurn) frameHeight else frameWidth
        val imageFrameHeight = if (quarterTurn) frameWidth else frameHeight
        val scale = EditCrop.coverScale(draft.angle, bitmap.width, bitmap.height)
        val density = LocalDensity.current
        val hitRadius = with(density) { 28.dp.toPx() }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(frameWidth, frameHeight)
                .clipToBounds()
                .pointerInput(bitmap, displayAspect, rotation) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0 // 0 = none, 1 = move, 2 = resize, 3 = pinch
                        var corner: CropCorner? = null
                        val current = draftState.value
                        val rect = current.rectIn(size.width.toFloat(), size.height.toFloat())
                        val corners = listOf(
                            CropCorner.TOP_LEFT to Offset(rect[0], rect[1]),
                            CropCorner.TOP_RIGHT to Offset(rect[2], rect[1]),
                            CropCorner.BOTTOM_LEFT to Offset(rect[0], rect[3]),
                            CropCorner.BOTTOM_RIGHT to Offset(rect[2], rect[3])
                        )
                        val nearest = corners.minByOrNull { (_, point) -> (point - down.position).getDistance() }
                        if (nearest != null && (nearest.second - down.position).getDistance() <= hitRadius) {
                            corner = nearest.first
                            mode = 2
                        } else if (down.position.inside(rect)) {
                            mode = 1
                        }

                        var lastPosition = down.position
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                mode = 3
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid != Offset.Unspecified) {
                                    val focusX = centroid.x / size.width
                                    val focusY = centroid.y / size.height
                                    if (zoom != 1f) {
                                        draftState.value = draftState.value
                                            .zoom(zoom, focusX, focusY, displayAspect)
                                    }
                                }
                                if (pan != Offset.Zero) {
                                    draftState.value = draftState.value.translate(
                                        pan.x / size.width,
                                        pan.y / size.height,
                                        displayAspect
                                    )
                                }
                                event.changes.forEach { if (it.pressed) it.consume() }
                                lastPosition = event.changes.firstOrNull { it.pressed }?.position
                                    ?: lastPosition
                            } else if (pressed == 1) {
                                val change = event.changes.first { it.pressed }
                                val position = change.position
                                val dx = (position.x - lastPosition.x) / size.width
                                val dy = (position.y - lastPosition.y) / size.height
                                when (mode) {
                                    1 -> {
                                        draftState.value = draftState.value
                                            .translate(dx, dy, displayAspect)
                                        change.consume()
                                    }
                                    2 -> {
                                        val active = corner
                                        if (active != null && (abs(dx) > 0f || abs(dy) > 0f)) {
                                            draftState.value = draftState.value
                                                .resize(active, dx, dy, displayAspect)
                                            change.consume()
                                        }
                                    }
                                    else -> Unit
                                }
                                lastPosition = position
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(imageFrameWidth, imageFrameHeight)
                    .graphicsLayer {
                        rotationZ = rotation + draft.angle
                        scaleX = scale
                        scaleY = scale
                    }
            )

            Canvas(Modifier.fillMaxSize()) {
                val l = draft.left * size.width
                val t = draft.top * size.height
                val r = draft.right * size.width
                val b = draft.bottom * size.height
                drawCropOverlay(l, t, r, b, gridColor)
            }
        }
    }
}

private fun DrawScope.drawCropOverlay(l: Float, t: Float, r: Float, b: Float, gridColor: Color) {
    val scrim = Color.Black.copy(alpha = 0.56f)
    drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, t))
    drawRect(scrim, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
    drawRect(scrim, topLeft = Offset(0f, t), size = Size(l, b - t))
    drawRect(scrim, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

    drawRect(
        color = Color.White.copy(alpha = 0.92f),
        topLeft = Offset(l, t),
        size = Size(r - l, b - t),
        style = Stroke(width = 1.2.dp.toPx())
    )

    val gridStroke = 1.dp.toPx()
    for (i in 1..2) {
        val x = l + (r - l) * i / 3f
        drawLine(gridColor, Offset(x, t), Offset(x, b), gridStroke)
        val y = t + (b - t) * i / 3f
        drawLine(gridColor, Offset(l, y), Offset(r, y), gridStroke)
    }

    val handleLength = (min(r - l, b - t) * 0.16f)
        .coerceIn(12.dp.toPx(), 28.dp.toPx())
    val handleStroke = 3.dp.toPx()
    drawCorner(l, t, handleLength, 1f, 1f, handleStroke)
    drawCorner(r, t, handleLength, -1f, 1f, handleStroke)
    drawCorner(l, b, handleLength, 1f, -1f, handleStroke)
    drawCorner(r, b, handleLength, -1f, -1f, handleStroke)
}

private fun DrawScope.drawCorner(
    x: Float,
    y: Float,
    length: Float,
    dx: Float,
    dy: Float,
    stroke: Float
) {
    drawLine(
        color = Amber,
        start = Offset(x, y),
        end = Offset(x + length * dx, y),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = Amber,
        start = Offset(x, y),
        end = Offset(x, y + length * dy),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
}

@Composable
private fun CropControls(
    draft: CropSpec,
    options: List<AspectOption>,
    selectedAspect: Int,
    rotation: Int,
    onSelectAspect: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    onAngleChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Surface(color = InkElevated) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEachIndexed { index, option ->
                    val selected = index == selectedAspect
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Amber else Card)
                            .clickable { onSelectAspect(index) }
                            .padding(horizontal = 15.dp, vertical = 7.dp)
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Ink else TextPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "旋转",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(32.dp)
                )
                IconButton(onClick = { onRotate(-90) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateLeft,
                        contentDescription = "向左旋转 90 度",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    "$rotation°",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    modifier = Modifier.width(42.dp)
                )
                IconButton(onClick = { onRotate(90) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "向右旋转 90 度",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) {
                    Text("重置", color = TextSecondary)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.RotateLeft,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("纠偏", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Slider(
                    value = draft.angle,
                    onValueChange = onAngleChange,
                    valueRange = -CropSpec.MAX_ANGLE..CropSpec.MAX_ANGLE,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = Card,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    )
                )
                Text(
                    String.format(Locale.US, "%+.1f°", draft.angle),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    modifier = Modifier.width(48.dp)
                )
            }
        }
    }
}

private fun EditCrop.toCropSpec(
    displayWidth: Int,
    displayHeight: Int,
    displayAspect: Float
): CropSpec {
    val rect = effectiveRect(displayWidth.toFloat(), displayHeight.toFloat())
    val locked = CropAspect.fromId(aspect).ratio
    return CropSpec(
        angle = straighten,
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        aspectRatio = locked
    ).normalized(displayAspect)
}

private fun EditCrop.aspectIndex(): Int {
    val selected = CropAspect.fromId(aspect)
    if (customRect != null && selected == CropAspect.ORIGINAL) return 0
    val index = CropAspect.entries.indexOf(selected)
    return (index + 1).coerceAtLeast(1)
}

private fun CropSpec.rectIn(width: Float, height: Float): FloatArray =
    floatArrayOf(left * width, top * height, right * width, bottom * height)

private fun Offset.inside(rect: FloatArray): Boolean =
    x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3]
