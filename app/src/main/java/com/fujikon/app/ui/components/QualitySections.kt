package com.fujikon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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

private val qualityCard = RoundedCornerShape(14.dp)

@Composable
private fun QualityHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = (if (expanded) "▾ " else "▸ ") + title,
            color = if (expanded) Amber else TextPrimary
        )
    }
}

private fun Modifier.qualityCard(): Modifier = this
    .fillMaxWidth()
    .background(CardHigh.copy(alpha = 0.16f), qualityCard)
    .border(1.dp, Color.White.copy(alpha = 0.07f), qualityCard)
    .padding(horizontal = 12.dp, vertical = 4.dp)

/** Point Color: pick a hue and shift only that colour. */
@Composable
fun PointColorSection(
    pointColor: PointColor,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (PointColor, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean
) {
    QualityHeader("点颜色 · 单色选取", expanded, onToggle)
    if (!expanded) return
    Column(Modifier.qualityCard()) {
        AdjustmentSlider(
            label = "目标色相",
            value = pointColor.targetHue,
            valueRange = 0f..360f,
            step = 1f,
            onValueChange = { onChange(pointColor.copy(targetHue = it), "点颜色") },
            onValueChangeFinished = { onFinished("点颜色") },
            onReset = { onChange(pointColor.copy(targetHue = 210f), "点颜色"); onFinished("点颜色") },
            enabled = enabled
        )
        AdjustmentSlider(
            label = "选取范围",
            value = pointColor.hueRange,
            valueRange = 5f..90f,
            step = 1f,
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

/**
 * Local adjustment editor: four selection shapes (control point / linear /
 * radial / brush) with a compact interactive canvas plus zone-scoped sliders.
 *
 * All coordinates edited here are normalised, so the same layers drive both
 * the preview and the full-resolution export.
 */
@Composable
fun LocalAdjustmentSection(
    local: LocalAdjustments,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (LocalAdjustments, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean
) {
    QualityHeader("局部 · 蒙版调整", expanded, onToggle)
    if (!expanded) return

    var selected by remember { mutableStateOf(0) }
    val layers = local.layers
    val index = selected.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    val layer = layers.getOrNull(index)
    var brushPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    fun update(transform: (LocalLayer) -> LocalLayer, field: String = "局部") {
        val current = layers.getOrNull(index) ?: return
        val next = layers.toMutableList()
        next[index] = transform(current).normalized()
        onChange(LocalAdjustments(next), field)
    }

    fun commit(field: String = "局部") = onFinished(field)

    Column(Modifier.qualityCard()) {
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
                        params = LocalParams.Neutral
                    )
                    onChange(LocalAdjustments((layers + fresh).take(LocalAdjustments.MAX_LAYERS)), "局部")
                    selected = layers.size
                    commit()
                },
                enabled = enabled && layers.size < LocalAdjustments.MAX_LAYERS
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
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(vertical = 4.dp)
                    .background(Color.Black.copy(alpha = 0.35f), qualityCard)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), qualityCard)
                    .pointerInput(index, mask) {
                        detectTapGestures { offset ->
                            val nx = (offset.x / size.width).coerceIn(0f, 1f)
                            val ny = (offset.y / size.height).coerceIn(0f, 1f)
                            update({
                                when (val m = it.mask) {
                                    is LocalMask.ControlPoint -> it.copy(mask = m.copy(x = nx, y = ny))
                                    is LocalMask.RadialGradient -> it.copy(mask = m.copy(x = nx, y = ny))
                                    is LocalMask.LinearGradient -> it.copy(
                                        mask = m.copy(startX = nx, startY = 0f, endX = nx, endY = 1f)
                                    )
                                    is LocalMask.Brush -> it
                                }
                            })
                            commit()
                        }
                    }
                    .pointerInput(index, mask) {
                        detectDragGestures(
                            onDragStart = { brushPoints = emptyList() },
                            onDragEnd = {
                                if (mask is LocalMask.Brush && brushPoints.isNotEmpty()) {
                                    val flat = ArrayList<Float>(brushPoints.size * 2)
                                    brushPoints.forEach { flat += it.x; flat += it.y }
                                    update({
                                        val m = it.mask as? LocalMask.Brush ?: return@update it
                                        it.copy(
                                            mask = m.copy(
                                                strokes = (m.strokes + BrushStroke(flat)).take(24)
                                            )
                                        )
                                    })
                                    commit()
                                }
                            }
                        ) { change, _ ->
                            change.consume()
                            val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                            val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                            when (val m = mask) {
                                is LocalMask.ControlPoint -> update({ it.copy(mask = m.copy(x = nx, y = ny)) })
                                is LocalMask.RadialGradient -> update({ it.copy(mask = m.copy(x = nx, y = ny)) })
                                is LocalMask.LinearGradient -> update({
                                    it.copy(mask = m.copy(startX = nx, startY = 0f, endX = nx, endY = 1f))
                                })
                                is LocalMask.Brush -> brushPoints = brushPoints + Offset(nx, ny)
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                // Faint thirds grid to make positioning readable.
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
                            androidx.compose.ui.geometry.Size(m.radiusX * 2f * w, m.radiusY * 2f * h)
                        )
                        drawOval(
                            Amber,
                            Offset(m.x * w - m.radiusX * w, m.y * h - m.radiusY * h),
                            androidx.compose.ui.geometry.Size(m.radiusX * 2f * w, m.radiusY * 2f * h),
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
            }

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
            Text("点「+ 添加」创建第一个局部调整图层", color = TextSecondary, modifier = Modifier.padding(8.dp))
        }
    }
    Spacer(Modifier.height(2.dp))
}
