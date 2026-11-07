package com.fujikon.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.fujikon.app.data.PointColor
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

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

private fun hueSwatch(hue: Float): Color {
    val argb = AndroidColor.HSVToColor(
        floatArrayOf((hue % 360f + 360f) % 360f, 0.82f, 0.92f)
    )
    return Color(
        red = AndroidColor.red(argb) / 255f,
        green = AndroidColor.green(argb) / 255f,
        blue = AndroidColor.blue(argb) / 255f
    )
}

private fun bitmapRatio(bitmap: Bitmap?): Float {
    if (bitmap == null || bitmap.height <= 0) return 1.5f
    return (bitmap.width.toFloat() / bitmap.height).coerceIn(0.7f, 2.2f)
}

private fun usable(bitmap: Bitmap?): Boolean = bitmap != null && !bitmap.isRecycled

@Composable
private fun QualityHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = (if (expanded) "▾ " else "▸ ") + title,
            color = if (expanded) Amber else TextPrimary
        )
    }
}

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

/**
 * Point Color: tap a colour on the photo, then shift only that colour.
 *
 * The photo itself is the picker, so users never have to guess a hue angle.
 */
@Composable
fun PointColorSection(
    pointColor: PointColor,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (PointColor, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean,
    sourceBitmap: Bitmap? = null
) {
    QualityHeader("点颜色 · 只改一种颜色", expanded, onToggle)
    if (!expanded) return

    // Long-running pointer handlers must always see the newest draft value.
    val pointColorState = rememberUpdatedState(pointColor)
    val onChangeState = rememberUpdatedState(onChange)
    val onFinishedState = rememberUpdatedState(onFinished)
    var picked by remember { mutableStateOf<Offset?>(null) }
    val inactive = pointColor.isNeutral()

    Column(Modifier.qualityCard()) {
        SectionHelp(
            "用途：只改变画面里某一种颜色，其它颜色不动。例如把天空调更蓝、把衣服换个色，" +
                "而不影响肤色和背景。\n" +
                "① 直接点下方照片里的目标颜色取色　② 用「选取范围」控制影响多少相近色\n" +
                "③ 调整「色相偏移 / 饱和度 / 明度」，松手后渲染一次即可看到效果"
        )

        if (usable(sourceBitmap)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmapRatio(sourceBitmap))
                    .heightIn(max = 180.dp)
                    .clip(qualityCard)
                    .border(1.dp, Amber.copy(alpha = 0.55f), qualityCard)
                    .testTag("point_color_picker")
                    .pointerInput(sourceBitmap) {
                        detectTapGestures { offset ->
                            val bitmap = sourceBitmap ?: return@detectTapGestures
                            if (bitmap.isRecycled) return@detectTapGestures
                            val nx = (offset.x / size.width).coerceIn(0f, 1f)
                            val ny = (offset.y / size.height).coerceIn(0f, 1f)
                            val px = (nx * (bitmap.width - 1)).roundToInt()
                                .coerceIn(0, bitmap.width - 1)
                            val py = (ny * (bitmap.height - 1)).roundToInt()
                                .coerceIn(0, bitmap.height - 1)
                            val hue = hueFromArgb(bitmap.getPixel(px, py))
                            picked = Offset(nx, ny)
                            onChangeState.value(pointColorState.value.copy(targetHue = hue), "点颜色")
                            onFinishedState.value("点颜色")
                        }
                    }
            ) {
                Image(
                    bitmap = sourceBitmap!!.asImageBitmap(),
                    contentDescription = "点按照片选取要修改的颜色",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(Modifier.fillMaxSize()) {
                    picked?.let { p ->
                        val c = Offset(p.x * size.width, p.y * size.height)
                        drawCircle(Color.Black.copy(alpha = 0.55f), 12.dp.toPx(), c)
                        drawCircle(Amber, 9.dp.toPx(), c)
                        drawCircle(Color.White, 3.dp.toPx(), c)
                    }
                }
            }
            Text(
                "↑ 点照片任意位置取色（可反复点，重新选色）",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        } else {
            Text(
                "照片加载中：可先用下方「目标色相」滑动条手动选色",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(4.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(hueSwatch(pointColor.targetHue))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "已选：${hueLabel(pointColor.targetHue)} · ${pointColor.targetHue.roundToInt()}°",
                color = TextPrimary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            if (!inactive) {
                TextButton(
                    onClick = {
                        onChangeState.value(
                            pointColorState.value.copy(hueShift = 0f, saturation = 0f, luminance = 0f),
                            "点颜色"
                        )
                        onFinishedState.value("点颜色")
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("point_color_reset")
                ) { Text("复原件", color = TextSecondary) }
            }
        }

        if (inactive) {
            Text(
                "已选色，但还没改它：请调整下方三个偏移值，或点「一键试用」",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            TextButton(
                onClick = {
                    onChangeState.value(
                        pointColorState.value.copy(saturation = 32f, luminance = 14f),
                        "点颜色"
                    )
                    onFinishedState.value("点颜色")
                },
                enabled = enabled,
                modifier = Modifier.testTag("point_color_try")
            ) { Text("一键试用：增艳 + 提亮选中色", color = Amber) }
        } else {
            Text(
                "已生效 · 只影响画面中「${hueLabel(pointColor.targetHue)}」附近的像素",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        AdjustmentSlider(
            label = "目标色相",
            value = pointColor.targetHue,
            valueRange = 0f..360f,
            step = 1f,
            formattedValue = "${pointColor.targetHue.roundToInt()}°",
            onValueChange = { onChange(pointColorState.value.copy(targetHue = it), "点颜色") },
            onValueChangeFinished = { onFinished("点颜色") },
            onReset = {
                onChange(pointColorState.value.copy(targetHue = 210f), "点颜色")
                onFinished("点颜色")
            },
            enabled = enabled
        )
        AdjustmentSlider(
            label = "选取范围",
            value = pointColor.hueRange,
            valueRange = 5f..90f,
            step = 1f,
            formattedValue = "±${pointColor.hueRange.roundToInt()}°",
            onValueChange = { onChange(pointColor.copy(hueRange = it), "点颜色") },
            onValueChangeFinished = { onFinished("点颜色") },
            onReset = { onChange(pointColor.copy(hueRange = 30f), "点颜色"); onFinished("点颜色") },
            enabled = enabled
        )
        AdjustmentSlider(
            label = "色相偏移",
            value = pointColor.hueShift,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onChange(pointColor.copy(hueShift = it), "点颜色") },
            onValueChangeFinished = { onFinished("点颜色") },
            onReset = { onChange(pointColor.copy(hueShift = 0f), "点颜色"); onFinished("点颜色") },
            enabled = enabled
        )
        AdjustmentSlider(
            label = "饱和度",
            value = pointColor.saturation,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onChange(pointColor.copy(saturation = it), "点颜色") },
            onValueChangeFinished = { onFinished("点颜色") },
            onReset = { onChange(pointColor.copy(saturation = 0f), "点颜色"); onFinished("点颜色") },
            enabled = enabled
        )
        AdjustmentSlider(
            label = "明度",
            value = pointColor.luminance,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onChange(pointColor.copy(luminance = it), "点颜色") },
            onValueChangeFinished = { onFinished("点颜色") },
            onReset = { onChange(pointColor.copy(luminance = 0f), "点颜色"); onFinished("点颜色") },
            enabled = enabled
        )
    }
    Spacer(Modifier.height(2.dp))
}

private enum class MaskKind(val label: String) {
    POINT("控制点"), LINEAR("线性"), RADIAL("径向"), BRUSH("画笔")
}

private fun kindOf(mask: LocalMask): MaskKind = when (mask) {
    is LocalMask.ControlPoint -> MaskKind.POINT
    is LocalMask.LinearGradient -> MaskKind.LINEAR
    is LocalMask.RadialGradient -> MaskKind.RADIAL
    is LocalMask.Brush -> MaskKind.BRUSH
}

private fun defaultMask(kind: MaskKind): LocalMask = when (kind) {
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
 * Local adjustment editor: four selection shapes, an on-photo positioning
 * canvas, layer switching and one-tap effects so a new layer has a visible
 * result immediately.
 *
 * All coordinates are normalised, so the same layers drive preview and export.
 */
@Composable
fun LocalAdjustmentSection(
    local: LocalAdjustments,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (LocalAdjustments, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean,
    sourceBitmap: Bitmap? = null
) {
    QualityHeader("局部 · 只改画面一块", expanded, onToggle)
    if (!expanded) return

    var selected by remember { mutableStateOf(0) }
    val layers = local.layers
    val index = selected.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    val layer = layers.getOrNull(index)
    var brushPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // Stable state handles keep the drag gesture alive across recompositions:
    // the old code keyed pointerInput on the mask object, which restarted the
    // gesture on every move and made positioning impossible.
    val layersState = rememberUpdatedState(layers)
    val selectedState = rememberUpdatedState(index)
    val onChangeState = rememberUpdatedState(onChange)
    val onFinishedState = rememberUpdatedState(onFinished)

    fun update(transform: (LocalLayer) -> LocalLayer, field: String = "局部") {
        val list = layersState.value
        val i = selectedState.value
        val current = list.getOrNull(i) ?: return
        val next = list.toMutableList()
        next[i] = transform(current).normalized()
        onChangeState.value(LocalAdjustments(next), field)
    }

    fun commit(field: String = "局部") = onFinishedState.value(field)

    /** Move the centre / gradient line of the currently selected mask. */
    fun placeAt(nx: Float, ny: Float) {
        update({ l ->
            when (val m = l.mask) {
                is LocalMask.ControlPoint -> l.copy(mask = m.copy(x = nx, y = ny))
                is LocalMask.RadialGradient -> l.copy(mask = m.copy(x = nx, y = ny))
                is LocalMask.LinearGradient -> l.copy(
                    mask = m.copy(startX = nx, startY = 0f, endX = nx, endY = 1f)
                )
                is LocalMask.Brush -> l
            }
        })
    }

    Column(Modifier.qualityCard()) {
        SectionHelp(
            "用途：只调整照片的一部分，比如提亮人脸、压暗过亮的天空，其余区域不受影响。\n" +
                "① 点「+ 添加」建图层　② 在照片上点按 / 拖动定位选区\n" +
                "③ 点一个「快速效果」或自己调参数 —— 只有选区内会变化"
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "图层 ${layers.size} · ${layer?.name ?: "无"}",
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val fresh = LocalLayer(
                        id = "local_${System.currentTimeMillis()}",
                        name = "局部 ${layers.size + 1}",
                        mask = defaultMask(if (layer != null) kindOf(layer.mask) else MaskKind.POINT),
                        // A new layer gets a mild, visible default so adding
                        // one never looks like "nothing happened".
                        params = LocalParams(brightness = 25f)
                    )
                    onChange(LocalAdjustments((layers + fresh).take(LocalAdjustments.MAX_LAYERS)), "局部")
                    selected = layers.size
                    commit()
                },
                enabled = enabled && layers.size < LocalAdjustments.MAX_LAYERS,
                modifier = Modifier.testTag("local_add_layer")
            ) { Text("+ 添加", color = Amber) }
            if (layer != null) {
                TextButton(
                    onClick = {
                        val next = layers.toMutableList().apply { removeAt(index) }
                        onChange(LocalAdjustments(next), "局部")
                        selected = (index - 1).coerceAtLeast(0)
                        commit()
                    },
                    enabled = enabled
                ) { Text("删除", color = TextSecondary) }
            }
        }

        if (layers.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                layers.forEachIndexed { i, l ->
                    TextButton(
                        onClick = { selected = i },
                        enabled = enabled,
                        modifier = Modifier.testTag("local_layer_$i")
                    ) {
                        Text(
                            "${i + 1}·${kindOf(l.mask).label}${if (l.enabled) "" else "(停)"}",
                            color = if (i == index) Amber else TextSecondary
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MaskKind.entries.forEach { kind ->
                TextButton(
                    onClick = {
                        update({ it.copy(mask = defaultMask(kind)) })
                        commit()
                    },
                    enabled = enabled && layer != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        kind.label,
                        color = if (layer != null && kindOf(layer.mask) == kind) Amber else TextSecondary
                    )
                }
            }
        }

        if (layer != null) {
            val mask = layer.mask
            val maskKindKey = kindOf(mask).name
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmapRatio(sourceBitmap))
                    .heightIn(max = 200.dp)
                    .clip(qualityCard)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), qualityCard)
                    .testTag("local_mask_canvas")
            ) {
                if (usable(sourceBitmap)) {
                    Image(
                        bitmap = sourceBitmap!!.asImageBitmap(),
                        contentDescription = "局部调整选区定位",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(index, maskKindKey) {
                            detectTapGestures { offset ->
                                val nx = (offset.x / size.width).coerceIn(0f, 1f)
                                val ny = (offset.y / size.height).coerceIn(0f, 1f)
                                placeAt(nx, ny)
                                commit()
                            }
                        }
                        .pointerInput(index, maskKindKey) {
                            var isBrush = false
                            detectDragGestures(
                                onDragStart = {
                                    isBrush = layersState.value
                                        .getOrNull(selectedState.value)?.mask is LocalMask.Brush
                                    brushPoints = emptyList()
                                },
                                onDragEnd = {
                                    val pts = brushPoints
                                    if (isBrush && pts.isNotEmpty()) {
                                        val flat = ArrayList<Float>(pts.size * 2)
                                        pts.forEach { flat += it.x; flat += it.y }
                                        update({ l ->
                                            val brush = l.mask as? LocalMask.Brush
                                            if (brush == null) {
                                                l
                                            } else {
                                                l.copy(
                                                    mask = brush.copy(
                                                        strokes = (brush.strokes + BrushStroke(flat)).take(24)
                                                    )
                                                )
                                            }
                                        })
                                        commit()
                                        brushPoints = emptyList()
                                    }
                                }
                            ) { change, _ ->
                                change.consume()
                                val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                if (isBrush) {
                                    brushPoints = brushPoints + Offset(nx, ny)
                                } else {
                                    placeAt(nx, ny)
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    for (i in 1..2) {
                        drawLine(Color.White.copy(alpha = 0.10f), Offset(w * i / 3f, 0f), Offset(w * i / 3f, h))
                        drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, h * i / 3f), Offset(w, h * i / 3f))
                    }
                    when (val m = mask) {
                        is LocalMask.ControlPoint -> {
                            val r = m.radius * minOf(w, h)
                            drawCircle(Amber.copy(alpha = 0.16f), r, Offset(m.x * w, m.y * h))
                            drawCircle(Amber, r, Offset(m.x * w, m.y * h), style = Stroke(2.dp.toPx()))
                            drawCircle(Amber, 5.dp.toPx(), Offset(m.x * w, m.y * h))
                        }
                        is LocalMask.RadialGradient -> {
                            drawOval(
                                Amber.copy(alpha = 0.16f),
                                Offset(m.x * w - m.radiusX * w, m.y * h - m.radiusY * h),
                                Size(m.radiusX * 2f * w, m.radiusY * 2f * h)
                            )
                            drawOval(
                                Amber,
                                Offset(m.x * w - m.radiusX * w, m.y * h - m.radiusY * h),
                                Size(m.radiusX * 2f * w, m.radiusY * 2f * h),
                                style = Stroke(2.dp.toPx())
                            )
                        }
                        is LocalMask.LinearGradient -> {
                            val y0 = m.startY * h
                            val y1 = m.endY * h
                            drawLine(Amber, Offset(m.startX * w, y0), Offset(m.endX * w, y1), 3.dp.toPx())
                            drawLine(
                                Color.White.copy(alpha = 0.5f),
                                Offset(0f, (y0 + y1) / 2f),
                                Offset(w, (y0 + y1) / 2f)
                            )
                        }
                        is LocalMask.Brush -> {
                            m.strokes.forEach { stroke ->
                                val pts = stroke.points
                                var i = 0
                                while (i + 3 < pts.size) {
                                    drawLine(
                                        Amber.copy(alpha = 0.8f),
                                        Offset(pts[i] * w, pts[i + 1] * h),
                                        Offset(pts[i + 2] * w, pts[i + 3] * h),
                                        (m.radius * minOf(w, h) * 2f).coerceAtLeast(3f)
                                    )
                                    i += 2
                                }
                            }
                        }
                    }
                    // Live feedback while a brush stroke is still in progress.
                    for (i in 1 until brushPoints.size) {
                        drawLine(
                            Amber.copy(alpha = 0.9f),
                            Offset(brushPoints[i - 1].x * w, brushPoints[i - 1].y * h),
                            Offset(brushPoints[i].x * w, brushPoints[i].y * h),
                            6.dp.toPx()
                        )
                    }
                }
            }
            Text(
                "↑ 在照片上点按 / 拖动定位选区（橙色为受影响区域）",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            // Shape parameters
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
        } else {
            Text(
                "还没有局部图层：点右上「+ 添加」，然后在照片上点一下选区位置，再点「提亮主体」等快速效果。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}
