package com.fujikon.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.SheetValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.ToneCurve
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
    initialToneCurve: ToneCurve,
    presetToneCurve: ToneCurve,
    baseCurve: List<Pair<Float, Float>>,
    rendering: Boolean,
    saving: Boolean,
    onPreview: (EditAdjustments, String?) -> Unit,
    onToneCurvePreview: (ToneCurve) -> Unit,
    onClearToneCurve: () -> Unit,
    /** "全部归零" also drops the red / green / blue curves from the curve sheet. */
    onClearAllCurves: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // The panel is a fixed half-screen inspector now. Swiping down must not
        // dismiss it (and must not turn a vertical scroll into a slider change).
        // Only the Hidden transition is blocked so the sheet can still be shown.
        confirmValueChange = { it != SheetValue.Hidden }
    )
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp
    var draft by remember { mutableStateOf(initialAdjustments.normalized()) }
    var curveDraft by remember { mutableStateOf(initialToneCurve.normalizedPoints) }
    var curveDirty by remember { mutableStateOf(false) }
    var expandedGroups by remember {
        mutableStateOf(
            AdjustmentGroups.map { it.title }.toSet() + setOf("人像", "HSL", "影调", "滤镜")
        )
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var lastPreviewAt by remember { mutableLongStateOf(0L) }
    var livePreviewPending by remember { mutableStateOf(false) }
    var pendingField by remember { mutableStateOf<String?>(null) }
    var lastCurvePreviewAt by remember { mutableLongStateOf(0L) }
    var curvePreviewPending by remember { mutableStateOf(false) }
    var curvePendingField by remember { mutableStateOf("影调") }
    val latestDraft by rememberUpdatedState(draft)
    val latestCurveDraft by rememberUpdatedState(curveDraft)
    val latestRendering by rememberUpdatedState(rendering)
    val latestPendingField by rememberUpdatedState(pendingField)

    fun previewNow(field: String? = pendingField) {
        livePreviewPending = false
        lastPreviewAt = SystemClock.elapsedRealtime()
        onPreview(draft, field)
    }

    fun previewDuringDrag(field: String?) {
        pendingField = field
        if (latestRendering) {
            livePreviewPending = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewAt < AdjustmentPreviewIntervalMs) return
        lastPreviewAt = now
        onPreview(draft, field)
    }

    fun previewCurveNow(field: String = "影调") {
        curveDirty = true
        curvePreviewPending = false
        curvePendingField = field
        lastCurvePreviewAt = SystemClock.elapsedRealtime()
        onToneCurvePreview(ToneCurve(curveDraft))
    }

    fun previewCurveDuringDrag(field: String = "影调") {
        curveDirty = true
        curvePendingField = field
        if (latestRendering) {
            curvePreviewPending = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCurvePreviewAt < AdjustmentPreviewIntervalMs) return
        lastCurvePreviewAt = now
        onToneCurvePreview(ToneCurve(curveDraft))
    }

    fun commitAndDismiss() {
        if (curveDirty) {
            onToneCurvePreview(ToneCurve(curveDraft))
        }
        onPreview(draft, null)
        onDismiss()
    }

    // Publish the latest values after a render finishes so a fast drag never
    // leaves the photo showing a stale intermediate value.
    LaunchedEffect(rendering) {
        if (!rendering) {
            if (livePreviewPending) {
                livePreviewPending = false
                lastPreviewAt = SystemClock.elapsedRealtime()
                onPreview(latestDraft, latestPendingField)
            }
            if (curvePreviewPending) {
                curvePreviewPending = false
                lastCurvePreviewAt = SystemClock.elapsedRealtime()
                onToneCurvePreview(ToneCurve(latestCurveDraft))
            }
        }
    }

    fun updateDraft(field: String?, update: (EditAdjustments) -> EditAdjustments) {
        draft = update(draft).normalized()
        previewDuringDrag(field)
    }

    fun toggleGroup(title: String) {
        expandedGroups = if (title in expandedGroups) {
            expandedGroups - title
        } else {
            expandedGroups + title
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) commitAndDismiss() },
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
                        "调节",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.72f), blurRadius = 5f)
                        ),
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "固定半屏 · 人像 / HSL / 影调 / 滤镜 · 双击滑块归零",
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
                    onClick = { if (!saving) commitAndDismiss() },
                    enabled = !saving,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = TextPrimary)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "原图 → 预设 → 当前调节",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        draft = EditAdjustments.Neutral
                        curveDraft = presetToneCurve.normalizedPoints
                        curveDirty = false
                        curvePreviewPending = false
                        onClearAllCurves()
                        previewNow(null)
                    },
                    enabled = !saving && (!draft.isNeutral() || curveDirty)
                ) {
                    Text(
                        "全部归零",
                        color = if (draft.isNeutral() && !curveDirty) TextSecondary else Amber
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                AdjustmentGroups.forEach { group ->
                    val expanded = group.title in expandedGroups
                    AdjustmentSectionHeader(
                        title = group.title,
                        expanded = expanded,
                        onToggle = { toggleGroup(group.title) }
                    )
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
                                        updateDraft(spec.label) { spec.set(it, value) }
                                    },
                                    onValueChangeFinished = { previewNow(spec.label) },
                                    onReset = {
                                        draft = spec.set(draft, 0f).normalized()
                                        previewNow(spec.label)
                                    },
                                    enabled = !saving
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }

                PortraitSection(
                    portrait = draft.portrait,
                    expanded = "人像" in expandedGroups,
                    onToggle = { toggleGroup("人像") },
                    onChange = { portrait, field ->
                        updateDraft(field) { it.copy(portrait = portrait) }
                    },
                    onFinished = { field -> previewNow(field) },
                    enabled = !saving
                )

                HslSection(
                    hsl = draft.hsl,
                    expanded = "HSL" in expandedGroups,
                    onToggle = { toggleGroup("HSL") },
                    onBandChange = { index, band ->
                        updateDraft("HSL") { it.copy(hsl = it.hsl.withBand(index, band)) }
                    },
                    onFinished = { field -> previewNow(field) },
                    enabled = !saving
                )

                ToneSection(
                    points = curveDraft,
                    baseCurve = baseCurve,
                    expanded = "影调" in expandedGroups,
                    onToggle = { toggleGroup("影调") },
                    onPointsChange = { points ->
                        curveDraft = points
                        curveDirty = true
                    },
                    onDrag = { previewCurveDuringDrag("影调") },
                    onFinished = { previewCurveNow("影调") },
                    onReset = {
                        curveDraft = presetToneCurve.normalizedPoints
                        curveDirty = false
                        curvePreviewPending = false
                        onClearToneCurve()
                    },
                    enabled = !saving
                )

                CreativeFilterSection(
                    selectedId = draft.creativeFilter,
                    strength = draft.filterStrength,
                    expanded = "滤镜" in expandedGroups,
                    onToggle = { toggleGroup("滤镜") },
                    onSelect = { filter ->
                        draft = draft.copy(
                            creativeFilter = filter.id,
                            filterStrength = if (filter.isNone) 100f else draft.filterStrength
                        ).normalized()
                        previewNow("滤镜")
                    },
                    onStrengthChange = { value ->
                        updateDraft("滤镜强度") { it.copy(filterStrength = value) }
                    },
                    onStrengthFinished = { previewNow("滤镜强度") },
                    enabled = !saving
                )

                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    draftName = "${presetName.take(30)} 调节"
                    showSaveDialog = true
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .height(48.dp),
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
                            // slider and curve values even if a throttled
                            // preview is pending.
                            if (curveDirty) {
                                onToneCurvePreview(ToneCurve(curveDraft))
                            }
                            onPreview(draft, null)
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
