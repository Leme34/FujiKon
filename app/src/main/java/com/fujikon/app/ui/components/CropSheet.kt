package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.CropAspect
import com.fujikon.app.data.EditCrop
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropSheet(
    bitmap: Bitmap?,
    initialCrop: EditCrop,
    rendering: Boolean,
    onPreview: (EditCrop) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val height = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    var crop by remember { mutableStateOf(initialCrop.normalized()) }

    fun commit() = onPreview(crop.normalized())

    ModalBottomSheet(
        onDismissRequest = {
            commit()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = InkElevated,
        contentColor = TextPrimary,
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(height)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("裁剪", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Text(
                        "选择比例后缩放或移动取景框，导出会按相同区域裁切",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (rendering) {
                    CircularProgressIndicator(
                        color = Amber,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.padding(end = 8.dp).size(16.dp)
                    )
                }
                IconButton(
                    onClick = {
                        commit()
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = TextPrimary)
                }
            }

            Spacer(Modifier.height(10.dp))
            CropPreview(
                bitmap = bitmap,
                crop = crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
            )
            Spacer(Modifier.height(10.dp))

            Text("比例", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(CropAspect.entries, key = { it.id }) { aspect ->
                    FilterChip(
                        selected = crop.aspect == aspect.id,
                        onClick = {
                            crop = crop.copy(
                                aspect = aspect.id,
                                zoom = 1f,
                                offsetX = 0f,
                                offsetY = 0f
                            ).normalized()
                            commit()
                        },
                        label = { Text(aspect.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CardHigh.copy(alpha = 0.30f),
                            labelColor = TextSecondary,
                            selectedContainerColor = Amber.copy(alpha = 0.20f),
                            selectedLabelColor = Amber
                        )
                    )
                }
            }

            SliderRow(
                label = "缩放",
                valueText = "${(crop.zoom * 100).roundToInt()}%",
                value = crop.zoom,
                range = EditCrop.MIN_ZOOM..EditCrop.MAX_ZOOM,
                onChange = { crop = crop.copy(zoom = it).normalized() },
                onFinished = { commit() }
            )
            SliderRow(
                label = "水平位置",
                valueText = "${(crop.offsetX * 100).roundToInt()}",
                value = crop.offsetX,
                range = -1f..1f,
                onChange = { crop = crop.copy(offsetX = it).normalized() },
                onFinished = { commit() }
            )
            SliderRow(
                label = "垂直位置",
                valueText = "${(crop.offsetY * 100).roundToInt()}",
                value = crop.offsetY,
                range = -1f..1f,
                onChange = { crop = crop.copy(offsetY = it).normalized() },
                onFinished = { commit() }
            )

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        crop = EditCrop.Full
                        commit()
                    }
                ) {
                    Text("重置", color = TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        commit()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                ) {
                    Text("完成", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CropPreview(
    bitmap: Bitmap?,
    crop: EditCrop,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无法加载照片", color = TextSecondary)
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "裁剪预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            Canvas(Modifier.fillMaxSize()) {
                val imageAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                val viewAspect = size.width / size.height.coerceAtLeast(1f)
                val drawWidth: Float
                val drawHeight: Float
                if (imageAspect > viewAspect) {
                    drawWidth = size.width
                    drawHeight = size.width / imageAspect
                } else {
                    drawHeight = size.height
                    drawWidth = size.height * imageAspect
                }
                val imageLeft = (size.width - drawWidth) * 0.5f
                val imageTop = (size.height - drawHeight) * 0.5f
                val rect = crop.rect(bitmap.width.toFloat(), bitmap.height.toFloat())
                val left = imageLeft + rect.left * drawWidth
                val top = imageTop + rect.top * drawHeight
                val right = imageLeft + rect.right * drawWidth
                val bottom = imageTop + rect.bottom * drawHeight
                val dim = Color.Black.copy(alpha = 0.58f)

                drawRect(dim, Offset(0f, 0f), Size(size.width, top))
                drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(dim, Offset(0f, top), Size(left, bottom - top))
                drawRect(dim, Offset(right, top), Size(size.width - right, bottom - top))

                val stroke = 1.dp.toPx()
                for (division in 1..2) {
                    val x = left + (right - left) * division / 3f
                    val y = top + (bottom - top) * division / 3f
                    drawLine(Color.White.copy(alpha = 0.32f), Offset(x, top), Offset(x, bottom), stroke)
                    drawLine(Color.White.copy(alpha = 0.32f), Offset(left, y), Offset(right, y), stroke)
                }
                drawRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.width(60.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Outline
            )
        )
        Text(
            valueText,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            modifier = Modifier.width(42.dp)
        )
    }
}
