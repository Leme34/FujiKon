package com.fujikon.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

private const val LivePreviewIntervalMs = 100L

/**
 * Bottom sheet for adjusting the master RGB curve after a preset has been
 * applied. The sheet is translucent so the photo stays visible, and drags
 * publish throttled previews while the user is still moving a point. The
 * current look can be stored as a new preset without touching the original
 * NCP/NP3 file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToneCurveEditorSheet(
    presetName: String,
    baseCurve: List<Pair<Float, Float>>,
    presetCurve: ToneCurve,
    curve: ToneCurve,
    rendering: Boolean,
    saving: Boolean,
    onPreview: (ToneCurve) -> Unit,
    onClearOverride: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var points by remember(presetName) { mutableStateOf(curve.normalizedPoints) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var lastPreviewAt by remember(presetName) { mutableLongStateOf(0L) }
    var livePreviewPending by remember { mutableStateOf(false) }
    val latestPoints by rememberUpdatedState(points)

    val selected = selectedIndex?.takeIf { it in points.indices }
    val selectedPoint = selected?.let(points::getOrNull)

    fun previewNow() {
        livePreviewPending = false
        lastPreviewAt = SystemClock.elapsedRealtime()
        onPreview(ToneCurve(points))
    }

    fun previewDuringDrag() {
        if (rendering) {
            livePreviewPending = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewAt < LivePreviewIntervalMs) return
        lastPreviewAt = now
        onPreview(ToneCurve(points))
    }

    // If the user kept moving while a preview was rendering, render the latest
    // point positions as soon as that render finishes, even if the finger is
    // now still.
    LaunchedEffect(rendering) {
        if (!rendering && livePreviewPending) {
            livePreviewPending = false
            lastPreviewAt = SystemClock.elapsedRealtime()
            onPreview(ToneCurve(latestPoints))
        }
    }

    fun replacePoints(updated: List<ToneCurvePoint>) {
        points = updated
        selectedIndex = null
        previewNow()
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
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
                .background(
                    brush = Brush.verticalGradient(
                        0f to InkElevated.copy(alpha = 0.06f),
                        0.32f to InkElevated.copy(alpha = 0.18f),
                        1f to InkElevated.copy(alpha = 0.40f)
                    ),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 2.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .background(CardHigh, RoundedCornerShape(50))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "色调曲线",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.72f), blurRadius = 5f)
                        ),
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "实时预览 · RGB 主通道叠加在当前预设上",
                        style = MaterialTheme.typography.bodySmall.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.72f), blurRadius = 4f)
                        ),
                        color = TextSecondary
                    )
                }
                IconButton(
                    onClick = { if (!saving) onDismiss() },
                    enabled = !saving,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = TextPrimary)
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(216.dp)
                    .background(
                        CardHigh.copy(alpha = 0.20f),
                        RoundedCornerShape(16.dp)
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                ToneCurveEditor(
                    points = points,
                    basePoints = baseCurve,
                    selectedIndex = selected,
                    onPointsChange = { points = it },
                    onSelectedChange = { selectedIndex = it },
                    enabled = !saving,
                    onDrag = { previewDuringDrag() },
                    onInteractionFinished = { previewNow() },
                    modifier = Modifier.fillMaxWidth().height(188.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "灰线：预设曲线",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "黄线：曲线调整",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${points.size} / 16 点",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedPoint != null) {
                    Text(
                        "输入 ${(selectedPoint.x * 100).roundToInt()}   →   输出 ${(selectedPoint.y * 100).roundToInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected == 0 || selected == points.lastIndex) TextSecondary else Amber
                    )
                } else {
                    Text(
                        "点击空白处添加控制点，拖动调整，双击删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selected != null && selected != 0 && selected != points.lastIndex) {
                    TextButton(
                        onClick = {
                            val updated = points.filterIndexed { index, _ -> index != selected }
                            replacePoints(updated)
                        },
                        enabled = !saving
                    ) {
                        Text("删除选中点", color = TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { replacePoints(ToneCurve.linear().normalizedPoints) },
                    enabled = !saving
                ) {
                    Text("重置为线性", color = TextSecondary)
                }
                TextButton(
                    onClick = {
                        onClearOverride()
                        points = presetCurve.normalizedPoints
                        selectedIndex = null
                        lastPreviewAt = 0L
                        livePreviewPending = false
                    },
                    enabled = !saving
                ) {
                    Text("撤销本次调整", color = TextSecondary)
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    draftName = "${presetName.take(34)} 调整"
                    showSaveDialog = true
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = Ink,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在保存…", fontWeight = FontWeight.Bold)
                } else {
                    Text("保存为新预设", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { if (!saving) showSaveDialog = false },
            containerColor = Card,
            title = { Text("保存当前效果", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "将当前曲线、调节和预设参数保存为新副本，用于当前预览和批量导出；原始 NCP/NP3 文件不会被修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it.take(40) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("预设名称") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = Outline,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = draftName.trim()
                        if (name.isNotEmpty()) {
                            showSaveDialog = false
                            onSave(name)
                        }
                    },
                    enabled = draftName.isNotBlank() && !saving
                ) {
                    Text("保存", color = Amber)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSaveDialog = false },
                    enabled = !saving
                ) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}
