package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
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
 * Full-screen Lightroom-style crop, rotate and straighten editor.
 *
 * The crop frame is fixed: the photo pans and zooms behind it exactly like in
 * Lightroom. Rotation is always a rigid transform (a quarter turn plus the
 * straighten angle) applied around the frame centre, so the photo is never
 * squeezed: the scale used for drawing is uniform in both axes.
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

    fun adjustAngle(delta: Float) {
        val angle = (draftState.value.angle + delta)
            .coerceIn(-CropSpec.MAX_ANGLE, CropSpec.MAX_ANGLE)
        val snapped = if (abs(angle) < 0.4f) 0f else angle
        draftState.value = draftState.value.copy(angle = snapped)
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
                        "拖动移动 · 双指缩放 · 拖动角度盘纠偏",
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
                    rotation = rotation,
                    draftState = draftState,
                    freeResize = options[selectedAspect].aspect == null
                )
            }

            StraightenDial(
                angle = draftState.value.angle,
                onDelta = ::adjustAngle
            )

            CropControls(
                options = options,
                selectedAspect = selectedAspect,
                rotation = rotation,
                onSelectAspect = ::selectAspect,
                onRotate = ::rotate,
                onReset = {
                    rotation = 0
                    selectedAspect = 0
                    draftState.value = CropSpec.None
                }
            )
        }
    }
}

/**
 * Draws the photo with a single affine transform and overlays the fixed crop
 * frame on top, exactly like Lightroom: the frame stays put while the photo
 * pans, zooms and rotates behind it. The scale is uniform in both axes, so
 * rotating never compresses the photo.
 *
 * The straighten angle rotates about the image centre (matching the export
 * renderer) and the crop centre is offset afterwards, so what you see in the
 * editor is exactly what gets written to the file.
 */
@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    rotation: Int,
    draftState: MutableState<CropSpec>,
    freeResize: Boolean
) {
    val draft = draftState.value
    val quarterTurn = rotation % 180 == 90
    val displayWidth = (if (quarterTurn) bitmap.height else bitmap.width).toFloat()
    val displayHeight = (if (quarterTurn) bitmap.width else bitmap.height).toFloat()
    val displayAspect = displayWidth / displayHeight
    val cropPixelWidth = (draft.widthFraction * displayWidth).coerceAtLeast(1f)
    val cropPixelHeight = (draft.heightFraction * displayHeight).coerceAtLeast(1f)
    val frameAspect = cropPixelWidth / cropPixelHeight
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        val frameWidth: Dp
        val frameHeight: Dp
        if (maxWidth.value / maxHeight.value > frameAspect) {
            frameHeight = maxHeight
            frameWidth = maxHeight * frameAspect
        } else {
            frameWidth = maxWidth
            frameHeight = maxWidth / frameAspect
        }

        val density = LocalDensity.current
        val frameWidthPx = with(density) { frameWidth.toPx() }
        val frameHeightPx = with(density) { frameHeight.toPx() }
        val hitRadius = with(density) { 30.dp.toPx() }
        val currentFrameWidth = rememberUpdatedState(frameWidthPx)
        val currentFrameHeight = rememberUpdatedState(frameHeightPx)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, rotation, freeResize) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val areaWidth = size.width.toFloat()
                        val areaHeight = size.height.toFloat()
                        val frameW = currentFrameWidth.value
                        val frameH = currentFrameHeight.value
                        val frameLeft = (areaWidth - frameW) * 0.5f
                        val frameTop = (areaHeight - frameH) * 0.5f
                        var mode = 1 // 1 = pan the photo, 2 = resize the free frame
                        var corner: CropCorner? = null

                        if (freeResize) {
                            val corners = listOf(
                                CropCorner.TOP_LEFT to Offset(frameLeft, frameTop),
                                CropCorner.TOP_RIGHT to Offset(frameLeft + frameW, frameTop),
                                CropCorner.BOTTOM_LEFT to Offset(frameLeft, frameTop + frameH),
                                CropCorner.BOTTOM_RIGHT to
                                    Offset(frameLeft + frameW, frameTop + frameH)
                            )
                            val nearest = corners.minByOrNull { (_, point) ->
                                (point - down.position).getDistance()
                            }
                            if (nearest != null &&
                                (nearest.second - down.position).getDistance() <= hitRadius
                            ) {
                                corner = nearest.first
                                mode = 2
                            }
                        }

                        var lastPosition = down.position
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val spec = draftState.value
                                if (zoom != 1f && centroid != Offset.Unspecified) {
                                    // Pinching out zooms the photo in, so the crop tightens.
                                    val centerX = (spec.left + spec.right) * 0.5f
                                    val centerY = (spec.top + spec.bottom) * 0.5f
                                    val focusX = centerX +
                                        (centroid.x - areaWidth * 0.5f) / frameW *
                                        spec.widthFraction
                                    val focusY = centerY +
                                        (centroid.y - areaHeight * 0.5f) / frameH *
                                        spec.heightFraction
                                    draftState.value = spec.zoom(
                                        factor = 1f / zoom,
                                        focusX = focusX,
                                        focusY = focusY,
                                        imageAspect = displayAspect
                                    )
                                }
                                if (pan != Offset.Zero) {
                                    val latest = draftState.value
                                    val dx = -pan.x * latest.widthFraction / frameW
                                    val dy = -pan.y * latest.heightFraction / frameH
                                    draftState.value = latest.translate(dx, dy, displayAspect)
                                }
                                event.changes.forEach { if (it.pressed) it.consume() }
                                lastPosition = event.changes.firstOrNull { it.pressed }?.position
                                    ?: lastPosition
                            } else if (pressed == 1) {
                                val change = event.changes.first { it.pressed }
                                val position = change.position
                                val dxScreen = position.x - lastPosition.x
                                val dyScreen = position.y - lastPosition.y
                                val spec = draftState.value
                                when (mode) {
                                    1 -> {
                                        // Dragging moves the photo, so the crop window moves back.
                                        val dx = -dxScreen * spec.widthFraction / frameW
                                        val dy = -dyScreen * spec.heightFraction / frameH
                                        draftState.value = spec.translate(dx, dy, displayAspect)
                                        change.consume()
                                    }
                                    2 -> {
                                        val active = corner
                                        if (active != null && (dxScreen != 0f || dyScreen != 0f)) {
                                            val dx = dxScreen * spec.widthFraction / frameW
                                            val dy = dyScreen * spec.heightFraction / frameH
                                            draftState.value = spec.resize(
                                                active,
                                                dx,
                                                dy,
                                                displayAspect
                                            )
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
            Canvas(Modifier.fillMaxSize()) {
                // Frame width maps the whole crop width, so a single uniform
                // scale keeps the photo's proportions at every angle.
                val scale = frameWidthPx / cropPixelWidth
                val cover = EditCrop.coverScale(draft.angle, bitmap.width, bitmap.height)
                val cropOffsetX =
                    ((draft.left + draft.right) * 0.5f - 0.5f) * displayWidth
                val cropOffsetY =
                    ((draft.top + draft.bottom) * 0.5f - 0.5f) * displayHeight
                withTransform({
                    translate(size.width / 2f, size.height / 2f)
                    translate(-scale * cropOffsetX, -scale * cropOffsetY)
                    scale(scale * cover, scale * cover, Offset.Zero)
                    rotate(draft.angle, Offset.Zero)
                    rotate(rotation.toFloat(), Offset.Zero)
                }) {
                    drawImage(
                        image = imageBitmap,
                        topLeft = Offset(-bitmap.width / 2f, -bitmap.height / 2f)
                    )
                }
            }

            Canvas(Modifier.fillMaxSize()) {
                val frameLeft = (size.width - frameWidthPx) * 0.5f
                val frameTop = (size.height - frameHeightPx) * 0.5f
                val frameRight = frameLeft + frameWidthPx
                val frameBottom = frameTop + frameHeightPx
                val scrim = Color.Black.copy(alpha = 0.6f)

                // Dim everything outside the crop frame, like Lightroom.
                drawRect(scrim, Offset.Zero, Size(size.width, frameTop))
                drawRect(
                    scrim,
                    Offset(0f, frameBottom),
                    Size(size.width, size.height - frameBottom)
                )
                drawRect(scrim, Offset(0f, frameTop), Size(frameLeft, frameHeightPx))
                drawRect(
                    scrim,
                    Offset(frameRight, frameTop),
                    Size(size.width - frameRight, frameHeightPx)
                )

                drawCropFrame(
                    showHandles = freeResize,
                    left = frameLeft,
                    top = frameTop,
                    width = frameWidthPx,
                    height = frameHeightPx
                )
            }
        }
    }
}

/** Lightroom-style frame: thin white border, third lines and corner ticks. */
private fun DrawScope.drawCropFrame(
    showHandles: Boolean,
    left: Float,
    top: Float,
    width: Float,
    height: Float
) {
    val gridColor = Color.White.copy(alpha = 0.34f)
    drawRect(
        color = Color.White.copy(alpha = 0.9f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = 1.dp.toPx())
    )

    val gridStroke = 1.dp.toPx()
    for (i in 1..2) {
        val x = left + width * i / 3f
        drawLine(gridColor, Offset(x, top), Offset(x, top + height), gridStroke)
        val y = top + height * i / 3f
        drawLine(gridColor, Offset(left, y), Offset(left + width, y), gridStroke)
    }

    val handleLength = (min(width, height) * 0.12f)
        .coerceIn(14.dp.toPx(), 34.dp.toPx())
    val handleStroke = if (showHandles) 4.dp.toPx() else 3.dp.toPx()
    val color = if (showHandles) Amber else Color.White
    drawCorner(left, top, handleLength, 1f, 1f, handleStroke, color)
    drawCorner(left + width, top, handleLength, -1f, 1f, handleStroke, color)
    drawCorner(left, top + height, handleLength, 1f, -1f, handleStroke, color)
    drawCorner(left + width, top + height, handleLength, -1f, -1f, handleStroke, color)
}

private fun DrawScope.drawCorner(
    x: Float,
    y: Float,
    length: Float,
    dx: Float,
    dy: Float,
    stroke: Float,
    color: Color = Amber
) {
    drawLine(
        color = color,
        start = Offset(x, y),
        end = Offset(x + length * dx, y),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(x, y),
        end = Offset(x, y + length * dy),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
}

/**
 * Lightroom-style protractor: a horizontal tick strip under the photo. Dragging
 * it adjusts the straighten angle; the angle labels sits above the needle.
 */
@Composable
private fun StraightenDial(
    angle: Float,
    onDelta: (Float) -> Unit
) {
    val currentAngle by rememberUpdatedState(angle)
    val currentOnDelta by rememberUpdatedState(onDelta)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            String.format(Locale.US, "%+.1f°", angle),
            style = MaterialTheme.typography.labelMedium,
            color = if (abs(angle) > 0.05f) Amber else TextSecondary,
            fontWeight = FontWeight.Bold
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastX = down.position.x
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            val x = change.position.x
                            val delta = (x - lastX) * (90f / size.width)
                            if (delta != 0f) {
                                currentOnDelta(delta)
                                change.consume()
                            }
                            lastX = x
                            if (pressed == 0) break
                        } while (true)
                    }
                }
        ) {
            val step = size.width / 90f
            val baseline = size.height * 0.72f
            val centerX = size.width / 2f

            // Baseline with major ticks every 15 degrees and minor every 5.
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.dp.toPx()
            )
            for (degree in -45..45 step 5) {
                val x = centerX + degree * step
                val major = degree % 15 == 0
                val height = if (major) 10.dp.toPx() else 5.dp.toPx()
                drawLine(
                    color = Color.White.copy(alpha = if (major) 0.75f else 0.4f),
                    start = Offset(x, baseline - height),
                    end = Offset(x, baseline),
                    strokeWidth = if (major) 1.2.dp.toPx() else 1.dp.toPx()
                )
            }

            // Needle for the current angle.
            val needleX = centerX + currentAngle * step
            drawLine(
                color = Amber,
                start = Offset(needleX, 0f),
                end = Offset(needleX, baseline),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun CropControls(
    options: List<AspectOption>,
    selectedAspect: Int,
    rotation: Int,
    onSelectAspect: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    onReset: () -> Unit
) {
    Surface(color = InkElevated) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
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

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "旋转",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(36.dp)
                )
                IconButton(onClick = { onRotate(-90) }, modifier = Modifier.size(36.dp)) {
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
                IconButton(onClick = { onRotate(90) }, modifier = Modifier.size(36.dp)) {
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
