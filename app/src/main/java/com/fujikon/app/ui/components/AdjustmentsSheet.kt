package com.fujikon.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary

private const val AdjustmentPreviewIntervalMs = 90L

private data class AdjustmentSpec(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val get: (EditAdjustments) -> Float,
    val set: (EditAdjustments, Float) -> EditAdjustments
)

private data class AdjustmentGroup(
    val title: String,
    val specs: List<AdjustmentSpec>
)

private val toneRange = -EditAdjustments.TONE_RANGE..EditAdjustments.TONE_RANGE
private val exposureRange = -EditAdjustments.EXPOSURE_RANGE..EditAdjustments.EXPOSURE_RANGE

private val AdjustmentGroups = listOf(
    AdjustmentGroup(
        title = "光线",
        specs = listOf(
            AdjustmentSpec("曝光度", exposureRange, 0.1f, { it.exposure }, { a, v -> a.copy(exposure = v) }),
            AdjustmentSpec("对比度", toneRange, 1f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
            AdjustmentSpec("高光", toneRange, 1f, { it.highlights }, { a, v -> a.copy(highlights = v) }),
            AdjustmentSpec("阴影", toneRange, 1f, { it.shadows }, { a, v -> a.copy(shadows = v) }),
            AdjustmentSpec("白色色阶", toneRange, 1f, { it.whites }, { a, v -> a.copy(whites = v) }),
            AdjustmentSpec("黑色色阶", toneRange, 1f, { it.blacks }, { a, v -> a.copy(blacks = v) })
        )
    ),
    AdjustmentGroup(
        title = "颜色",
        specs = listOf(
            AdjustmentSpec("色温", toneRange, 1f, { it.temperature }, { a, v -> a.copy(temperature = v) }),
            AdjustmentSpec("色调", toneRange, 1f, { it.tint }, { a, v -> a.copy(tint = v) }),
            AdjustmentSpec("自然饱和度", toneRange, 1f, { it.vibrance }, { a, v -> a.copy(vibrance = v) })
        )
    ),
    AdjustmentGroup(
        title = "细节",
        specs = listOf(
            AdjustmentSpec("纹理", toneRange, 1f, { it.texture }, { a, v -> a.copy(texture = v) }),
            AdjustmentSpec("清晰度", toneRange, 1f, { it.clarity }, { a, v -> a.copy(clarity = v) }),
            AdjustmentSpec("去朦胧", toneRange, 1f, { it.dehaze }, { a, v -> a.copy(dehaze = v) })
        )
    )
)

/**
 * Lightroom-style global adjustment sheet.
 *
 * Changes are rendered live at a throttled rate while a slider is moving. The
 * sheet stays highly translucent so the photo remains visible underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustmentsSheet(
    presetName: String,
    initialAdjustments: EditAdjustments,
    rendering: Boolean,
    saving: Boolean,
    onPreview: (EditAdjustments) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initialAdjustments.normalized()) }
    var expandedGroups by remember {
        mutableStateOf(AdjustmentGroups.map { it.title }.toSet())
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var lastPreviewAt by remember { mutableLongStateOf(0L) }
    var livePreviewPending by remember { mutableStateOf(false) }
    val latestDraft by rememberUpdatedState(draft)
    val latestRendering by rememberUpdatedState(rendering)

    fun previewNow() {
        livePreviewPending = false
        lastPreviewAt = SystemClock.elapsedRealtime()
        onPreview(draft)
    }

    fun previewDuringDrag() {
        if (latestRendering) {
            livePreviewPending = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewAt < AdjustmentPreviewIntervalMs) return
        lastPreviewAt = now
        onPreview(draft)
    }

    // Publish the latest values after a render finishes so a fast drag never
    // leaves the photo showing a stale intermediate value.
    LaunchedEffect(rendering) {
        if (!rendering && livePreviewPending) {
            livePreviewPending = false
            lastPreviewAt = SystemClock.elapsedRealtime()
            onPreview(latestDraft)
        }
    }

    fun updateDraft(update: (EditAdjustments) -> EditAdjustments) {
        draft = update(draft).normalized()
        previewDuringDrag()
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
                        0.30f to InkElevated.copy(alpha = 0.18f),
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
                        "调节",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.72f), blurRadius = 5f)
                        ),
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "实时叠加在当前预设上 · 双击滑块归零",
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
                    onClick = { if (!saving) onDismiss() },
                    enabled = !saving,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = TextPrimary)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "原图 → 预设 → 当前调节",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        draft = EditAdjustments.Neutral
                        previewNow()
                    },
                    enabled = !saving && !draft.isNeutral()
                ) {
                    Text(
                        "全部归零",
                        color = if (draft.isNeutral()) TextSecondary else Amber
                    )
                }
            }

            AdjustmentGroups.forEach { group ->
                val expanded = group.title in expandedGroups
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedGroups = if (expanded) {
                                expandedGroups - group.title
                            } else {
                                expandedGroups + group.title
                            }
                        }
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 4f)
                        ),
                        color = TextPrimary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (expanded) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                CardHigh.copy(alpha = 0.16f),
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.07f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        group.specs.forEach { spec ->
                            AdjustmentSlider(
                                label = spec.label,
                                value = spec.get(draft),
                                valueRange = spec.range,
                                step = spec.step,
                                onValueChange = { value ->
                                    updateDraft { spec.set(it, value) }
                                },
                                onValueChangeFinished = { previewNow() },
                                onReset = {
                                    draft = spec.set(draft, 0f).normalized()
                                    previewNow()
                                },
                                enabled = !saving
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    draftName = "${presetName.take(30)} 调节"
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
            title = { Text("保存调节预设", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "将当前调节、曲线和预设参数保存为新副本，用于当前预览和批量导出；原始 NCP/NP3 文件不会被修改。",
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
                            // Make sure the repository snapshots the newest
                            // slider value even if a throttled preview is pending.
                            onPreview(draft)
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
