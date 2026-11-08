package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.BrushStroke
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.data.LocalLayer
import com.fujikon.app.data.LocalMask
import com.fujikon.app.data.LocalParams
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.abs

private val qualityCard = RoundedCornerShape(14.dp)

/** HSL hue in degrees (0..360) for an ARGB pixel; grey pixels report hue 0. */
fun hueFromArgb(argb: Int): Float = hslFromArgb(argb)[0]

/** HSL saturation (0..1) for an ARGB pixel. */
fun saturationFromArgb(argb: Int): Float = hslFromArgb(argb)[1]

/** HSL lightness (0..1) for an ARGB pixel. */
fun lightnessFromArgb(argb: Int): Float = hslFromArgb(argb)[2]

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
    POINT("控制点"), LINEAR("线性"), RADIAL("径向"), BRUSH("画笔")
}

internal fun kindOf(mask: LocalMask): MaskKind = when (mask) {
    is LocalMask.ControlPoint -> MaskKind.POINT
    is LocalMask.LinearGradient -> MaskKind.LINEAR
    is LocalMask.RadialGradient -> MaskKind.RADIAL
    is LocalMask.Brush -> MaskKind.BRUSH
}

internal fun defaultMask(kind: MaskKind): LocalMask = when (kind) {
    MaskKind.POINT -> LocalMask.ControlPoint()
    MaskKind.LINEAR -> LocalMask.LinearGradient()
    MaskKind.RADIAL -> LocalMask.RadialGradient()
    MaskKind.BRUSH -> LocalMask.Brush()
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

/** Move a mask's anchor to a normalised point. Pure so it is unit-testable. */
internal fun placeMask(mask: LocalMask, nx: Float, ny: Float): LocalMask = when (mask) {
    is LocalMask.ControlPoint -> mask.copy(x = nx, y = ny)
    is LocalMask.RadialGradient -> mask.copy(x = nx, y = ny)
    is LocalMask.LinearGradient -> mask.copy(startX = nx, startY = 0f, endX = nx, endY = 1f)
    is LocalMask.Brush -> mask
}

/** Append one normalised brush stroke, capped so JSON/export stay bounded. */
internal fun appendBrushStroke(mask: LocalMask, flat: List<Float>): LocalMask =
    if (mask is LocalMask.Brush && flat.size >= 4) {
        mask.copy(strokes = (mask.strokes + BrushStroke(flat)).take(24))
    } else mask

/** Draw the mask outline in the same geometry used to interpret touches. */
private fun DrawScope.drawMaskOutline(
    mask: LocalMask,
    rect: ImageRect,
    liveBrush: List<Offset>
) {
    val l = rect.left
    val t = rect.top
    val w = rect.width
    val h = rect.height
    if (w <= 0f || h <= 0f) return
    val base = minOf(w, h)
    fun px(nx: Float) = l + nx * w
    fun py(ny: Float) = t + ny * h

    for (i in 1..2) {
        drawLine(
            Color.White.copy(alpha = 0.10f),
            Offset(l + w * i / 3f, t), Offset(l + w * i / 3f, t + h)
        )
        drawLine(
            Color.White.copy(alpha = 0.10f),
            Offset(l, t + h * i / 3f), Offset(l + w, t + h * i / 3f)
        )
    }
    when (mask) {
        is LocalMask.ControlPoint -> {
            val r = mask.radius * base
            val c = Offset(px(mask.x), py(mask.y))
            drawCircle(Amber.copy(alpha = 0.16f), r, c)
            drawCircle(Amber, r, c, style = Stroke(2.dp.toPx()))
            drawCircle(Amber, 5.dp.toPx(), c)
        }
        is LocalMask.RadialGradient -> {
            val topLeft = Offset(px(mask.x) - mask.radiusX * w, py(mask.y) - mask.radiusY * h)
            val oval = Size(mask.radiusX * 2f * w, mask.radiusY * 2f * h)
            drawOval(Amber.copy(alpha = 0.16f), topLeft, oval)
            drawOval(Amber, topLeft, oval, style = Stroke(2.dp.toPx()))
        }
        is LocalMask.LinearGradient -> {
            drawLine(
                Amber,
                Offset(px(mask.startX), py(mask.startY)),
                Offset(px(mask.endX), py(mask.endY)),
                3.dp.toPx()
            )
            val mid = py((mask.startY + mask.endY) / 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(l, mid), Offset(l + w, mid))
        }
        is LocalMask.Brush -> {
            mask.strokes.forEach { stroke ->
                val pts = stroke.points
                var i = 0
                while (i + 3 < pts.size) {
                    drawLine(
                        Amber.copy(alpha = 0.8f),
                        Offset(px(pts[i]), py(pts[i + 1])),
                        Offset(px(pts[i + 2]), py(pts[i + 3])),
                        (mask.radius * base * 2f).coerceAtLeast(3f)
                    )
                    i += 2
                }
            }
        }
    }
    for (i in 1 until liveBrush.size) {
        drawLine(
            Amber.copy(alpha = 0.9f),
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
 * remove / switch, tap-to-place and brush painting) lives on the full-screen
 * photo, not here. Keeping this drawer to sliders avoids a second tiny copy
 * of the photo and lets the user collapse it to maximise the preview area.
 */
@Composable
fun LocalAdjustmentSection(
    local: LocalAdjustments,
    selectedLayer: Int,
    onSelectLayer: (Int) -> Unit,
    onChange: (LocalAdjustments, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean
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
                "形状、位置、图层和画笔都在全屏预览大图上操作；这里只调数值，可折叠收起让画面更大。"
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
                }
                is LocalMask.LinearGradient -> {
                    AdjustmentSlider(
                        label = "渐变终点", value = m.endY, valueRange = 0.1f..1f, step = 0.01f,
                        onValueChange = { v -> update({ it.copy(mask = m.copy(endY = v)) }) },
                        onValueChangeFinished = { commit() },
                        onReset = { update({ it.copy(mask = m.copy(endY = 1f)) }); commit() },
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
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    val inverted = when (val m = mask) {
                        is LocalMask.LinearGradient -> m.copy(invert = !m.invert)
                        is LocalMask.RadialGradient -> m.copy(invert = !m.invert)
                        is LocalMask.Brush -> m.copy(invert = !m.invert)
                        is LocalMask.ControlPoint -> m
                    }
                    update({ it.copy(mask = inverted) })
                    commit()
                }, enabled = enabled) { Text("反转选区", color = TextSecondary) }
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
 * tap to place, drag to move, finger-paint for the brush — with shape / layer
 * controls in a compact toolbar below the photo.
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
    modifier: Modifier = Modifier
) {
    val layers = local.layers
    val index = selectedLayer.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    val layer = layers.getOrNull(index)
    var liveBrush by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val layersState = rememberUpdatedState(layers)
    val indexState = rememberUpdatedState(index)
    val onChangeState = rememberUpdatedState(onChange)
    val onFinishedState = rememberUpdatedState(onFinished)
    // pointerInput(Unit) keeps its lambda across recompositions, so anything
    // read inside the gesture must come from an updated state.
    val enabledState = rememberUpdatedState(enabled)
    val bitmapState = rememberUpdatedState(displayBitmap)

    fun update(transform: (LocalLayer) -> LocalLayer, field: String = "蒙版") {
        val list = layersState.value
        val i = indexState.value
        val current = list.getOrNull(i) ?: return
        val next = list.toMutableList()
        next[i] = transform(current).normalized()
        onChangeState.value(LocalAdjustments(next), field)
    }

    fun commit(field: String = "蒙版") = onFinishedState.value(field)

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
                    if (!enabledState.value) return@pointerInput
                    detectTapGestures { offset ->
                        val rect = rectFor(
                            size.width.toFloat(), size.height.toFloat(), bitmapState.value
                        )
                        val p = rect.normalized(offset.x, offset.y) ?: return@detectTapGestures
                        update({ l -> l.copy(mask = placeMask(l.mask, p.first, p.second)) })
                        commit()
                    }
                }
                .pointerInput(Unit) {
                    if (!enabledState.value) return@pointerInput
                    var isBrush = false
                    detectDragGestures(
                        onDragStart = {
                            isBrush = layersState.value
                                .getOrNull(indexState.value)?.mask is LocalMask.Brush
                            liveBrush = emptyList()
                        },
                        onDragEnd = {
                            val pts = liveBrush
                            if (isBrush && pts.size >= 2) {
                                val flat = ArrayList<Float>(pts.size * 2)
                                pts.forEach { flat += it.x; flat += it.y }
                                update({ l -> l.copy(mask = appendBrushStroke(l.mask, flat)) })
                                commit()
                                liveBrush = emptyList()
                            }
                        }
                    ) { change, _ ->
                        change.consume()
                        val rect = rectFor(
                            size.width.toFloat(), size.height.toFloat(), bitmapState.value
                        )
                        val nx = ((change.position.x - rect.left) / rect.width).coerceIn(0f, 1f)
                        val ny = ((change.position.y - rect.top) / rect.height).coerceIn(0f, 1f)
                        if (isBrush) {
                            liveBrush = liveBrush + Offset(nx, ny)
                        } else {
                            update({ l -> l.copy(mask = placeMask(l.mask, nx, ny)) })
                        }
                    }
                }
        ) {
            val rect = rectFor(size.width, size.height, displayBitmap)
            layer?.let { drawMaskOutline(it.mask, rect, liveBrush) }
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
                "在照片上点按定位 · 拖动移动 · 画笔可直接涂抹（橙色为受影响区域）",
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
                            update({ l -> l.copy(mask = defaultMask(kind)) })
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
