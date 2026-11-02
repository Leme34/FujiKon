package com.fujikon.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import java.util.Locale
import kotlin.math.abs

private const val CropPreviewIntervalMs = 40L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropSheet(
    initialCrop: EditCrop,
    rendering: Boolean,
    onPreview: (EditCrop) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp
    var crop by remember { mutableStateOf(initialCrop.normalized()) }
    var lastPreviewAt by remember { mutableLongStateOf(0L) }
    var pendingPreview by remember { mutableStateOf(false) }
    val latestCrop by rememberUpdatedState(crop)
    val latestRendering by rememberUpdatedState(rendering)

    fun previewNow() {
        pendingPreview = false
        lastPreviewAt = SystemClock.elapsedRealtime()
        onPreview(crop.normalized())
    }

    fun previewDuringChange() {
        if (latestRendering) {
            pendingPreview = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewAt < CropPreviewIntervalMs) return
        lastPreviewAt = now
        onPreview(crop.normalized())
    }

    // Publish the final position after a render finishes so a fast drag never
    // leaves the main preview showing a stale intermediate crop.
    LaunchedEffect(rendering) {
        if (!rendering && pendingPreview) {
            pendingPreview = false
            lastPreviewAt = SystemClock.elapsedRealtime()
            onPreview(latestCrop.normalized())
        }
    }

    fun updateCrop(change: (EditCrop) -> EditCrop, forcePreview: Boolean = false) {
        crop = change(crop).normalized()
        if (forcePreview) previewNow() else previewDuringChange()
    }

    fun commitAndDismiss() {
        onPreview(crop.normalized())
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = ::commitAndDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = TextPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.03f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .background(
                    brush = Brush.verticalGradient(
                        0f to InkElevated.copy(alpha = 0.10f),
                        0.30f to InkElevated.copy(alpha = 0.28f),
                        1f to InkElevated.copy(alpha = 0.52f)
                    ),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "裁剪",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.72f), blurRadius = 5f)
                        ),
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "固定半屏 · 旋转 / 纠偏 · 实时预览",
                        style = MaterialTheme.typography.bodySmall.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.72f), blurRadius = 4f)
                        ),
                        color = TextSecondary
                    )
                }
                if (rendering) {
                    CircularProgressIndicator(
                        color = Amber,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp)
                    )
                }
                IconButton(
                    onClick = ::commitAndDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = TextPrimary)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "比例",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(CropAspect.entries, key = { it.id }) { aspect ->
                    FilterChip(
                        selected = crop.aspect == aspect.id,
                        onClick = {
                            updateCrop({ it.copy(aspect = aspect.id) }, forcePreview = true)
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

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "旋转",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(60.dp)
                )
                IconButton(
                    onClick = {
                        updateCrop(
                            { it.copy(rotation = (it.rotation + 270) % 360) },
                            forcePreview = true
                        )
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateLeft,
                        contentDescription = "向左旋转 90 度",
                        tint = TextPrimary
                    )
                }
                Text(
                    "${crop.rotation}°",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    modifier = Modifier.width(52.dp)
                )
                IconButton(
                    onClick = {
                        updateCrop(
                            { it.copy(rotation = (it.rotation + 90) % 360) },
                            forcePreview = true
                        )
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "向右旋转 90 度",
                        tint = TextPrimary
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "可连续点击",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }

            SliderRow(
                label = "纠偏",
                valueText = formatAngle(crop.straighten),
                value = crop.straighten,
                range = -EditCrop.STRAIGHTEN_RANGE..EditCrop.STRAIGHTEN_RANGE,
                onChange = { value -> updateCrop({ it.copy(straighten = value) }) },
                onFinished = { previewNow() }
            )

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        crop = EditCrop.Full
                        previewNow()
                    }
                ) {
                    Text("重置", color = TextSecondary)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = ::commitAndDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                ) {
                    Text("完成", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatAngle(value: Float): String {
    if (abs(value) < 0.05f) return "0.0°"
    val sign = if (value > 0f) "+" else ""
    return String.format(Locale.US, "%s%.1f°", sign, value)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            modifier = Modifier.width(52.dp)
        )
    }
}
