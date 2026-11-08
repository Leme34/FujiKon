package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.HslBand
import com.fujikon.app.data.PointColor
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

private data class AdjustmentSpec(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val get: (EditAdjustments) -> Float,
    val set: (EditAdjustments, Float) -> EditAdjustments,
    /** Short "what is this / how to use it" hint shown under the slider. */
    val help: String? = null
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
    ),
    AdjustmentGroup(
        title = "画质",
        specs = listOf(
            AdjustmentSpec(
                "降噪", 0f..100f, 1f, { it.denoise }, { a, v -> a.copy(denoise = v) },
                help = "降噪：用保边滤波抹掉暗光、高 ISO 产生的彩色噪点和粗糙颗粒，只平滑平坦区域、保留轮廓与纹理，且在锐化之前执行以免放大噪点。" +
                    "夜景、室内暗光人像建议 20–50；光线充足的原片保持 0 即可，数值过高会让人脸和细节变糊。"
            ),
            AdjustmentSpec(
                "颗粒", 0f..100f, 1f, { it.grain }, { a, v -> a.copy(grain = v) },
                help = "颗粒：叠加确定性的胶片颗粒，营造复古质感、掩盖降噪后的塑料感。建议 10–30，预览与导出结果一致。"
            ),
            AdjustmentSpec(
                "暗角", toneRange, 1f, { it.vignette }, { a, v -> a.copy(vignette = v) },
                help = "暗角：负值压暗四角把视线引向主体，正值提亮四角。人像、静物常用 -15 ~ -35。"
            )
        )
    )
)

/** One tab = one self-contained page, so no long vertical list to mis-scroll. */
private enum class AdjustTab(val label: String, val groupIndex: Int? = null) {
    LIGHT("光线", 0),
    COLOR("颜色", 1),
    DETAIL("细节", 2),
    QUALITY("画质", 3),
    HSL("HSL"),
    PORTRAIT("人像"),
    TONE("影调"),
    FILTER("滤镜"),
    MASK("蒙版")
}

/**
 * Maps a tap on a ContentScale.Fit image back to a bitmap pixel.
 * Returns null when the tap lands in the letter-boxed area. Kept as a pure
 * function so the picker geometry is unit-testable without a device.
 */
internal fun fitBitmapPoint(
    x: Float,
    y: Float,
    viewW: Int,
    viewH: Int,
    bmpW: Int,
    bmpH: Int
): Pair<Int, Int>? {
    if (viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) return null
    val fit = minOf(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
    val dispW = bmpW * fit
    val dispH = bmpH * fit
    val left = (viewW - dispW) / 2f
    val top = (viewH - dispH) / 2f
    // Reject taps in the letter-boxed area before rounding: rounding a
    // negative coordinate to 0 would otherwise sample the edge pixel.
    if (x < left || y < top || x >= left + dispW || y >= top + dispH) return null
    val bx = ((x - left) / fit).roundToInt().coerceIn(0, bmpW - 1)
    val by = ((y - top) / fit).roundToInt().coerceIn(0, bmpH - 1)
    return bx to by
}

/**
 * Upper-half colour picker. The photo is drawn here (not behind a modal
 * window) so a tap can be mapped straight back to the original pixels.
 */
@Composable
private fun ColorPickLayer(
    displayBitmap: Bitmap?,
    sampleBitmap: Bitmap?,
    onPick: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.background(Color.Black)) {
        val shown = displayBitmap
        if (shown != null && !shown.isRecycled) {
            var viewSize by remember { mutableStateOf(IntSize.Zero) }
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "点按照片选取要修改的颜色",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
            )
            val samplerState = remember(sampleBitmap) { sampleBitmap ?: shown }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("hsl_color_pick_target")
                    .pointerInput(shown, samplerState, viewSize) {
                        detectTapGestures { offset ->
                            val sampler = samplerState
                            if (sampler.isRecycled) return@detectTapGestures
                            val point = fitBitmapPoint(
                                offset.x, offset.y,
                                viewSize.width, viewSize.height,
                                shown.width, shown.height
                            ) ?: return@detectTapGestures
                            // Sample from the unedited original (normalised
                            // coordinates) so an already-applied point-colour
                            // edit cannot drift the hue we are picking.
                            val nx = point.first / shown.width.toFloat()
                            val ny = point.second / shown.height.toFloat()
                            val sx = (nx * (sampler.width - 1)).roundToInt()
                                .coerceIn(0, sampler.width - 1)
                            val sy = (ny * (sampler.height - 1)).roundToInt()
                                .coerceIn(0, sampler.height - 1)
                            onPick(hueFromArgb(sampler.getPixel(sx, sy)))
                        }
                    }
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp),
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                "点击照片选取要修改的颜色 · 松手后自动回到 HSL",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * The mask tab is now only a launcher: masking lives in its own full-screen
 * editor [MaskEditorOverlay]. A half-screen overlay above the panel was far
 * too small to position a mask, so we no longer embed any photo or mask
 * controls here — just a short explanation and a single, obvious entry point.
 */
@Composable
private fun MaskLauncherCard(layerCount: Int, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardHigh.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = null,
            tint = Amber,
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "蒙版 · 局部调整",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "只调整照片的一部分：提亮人脸、压暗过亮的天空，其余区域不受影响。" +
                "蒙版已独立为全屏编辑页面，预览大图占满屏幕，形状、位置、图层与画笔都直接在照片上操作。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("open_mask_editor"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
        ) {
            Text(
                if (layerCount > 0) "打开全屏蒙版编辑（$layerCount 个蒙版）" else "打开全屏蒙版编辑",
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "进入后可在底部展开参数调节，默认收起以最大化预览图",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

/**
 * Lightroom-style adjustment panel.
 *
 * This is a plain overlay rather than a ModalBottomSheet: there is no
 * anchored drag gesture, so swiping down can never dismiss the panel or turn
 * a scroll into a slider change. Each sub-feature lives on its own tab page,
 * which keeps the content short and removes the long vertical list.
 *
 * Slider drags only mutate a local draft; the photo is re-rendered exactly
 * once when the gesture is released (or a nudge button is released).
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
    onDismiss: () -> Unit,
    /** Open the standalone full-screen mask editor (masking is no longer a
     * cramped half-screen overlay inside this panel). */
    onOpenMaskEditor: () -> Unit = {},
    /** Unedited source used by the colour picker. */
    sourceBitmap: Bitmap? = null,
    /** Current rendered preview, shown while picking a colour. */
    previewBitmap: Bitmap? = null
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val sheetHeight = (screenHeight * 0.56f).dp
    val pickHeight = (screenHeight * 0.44f).dp

    val draftState = remember { mutableStateOf(initialAdjustments.normalized()) }
    var draft by draftState
    val curveState = remember { mutableStateOf(initialToneCurve.normalizedPoints) }
    var curveDraft by curveState
    var curveDirty by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(AdjustTab.LIGHT) }
    var hslTarget by remember { mutableStateOf<HslTarget>(HslTarget.Band(0)) }
    var picking by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }

    fun previewNow(field: String? = null) {
        onPreview(draftState.value, field)
    }

    fun previewCurveNow() {
        curveDirty = true
        onToneCurvePreview(ToneCurve(curveState.value))
    }

    fun commitAndDismiss() {
        if (curveDirty) {
            onToneCurvePreview(ToneCurve(curveState.value))
        }
        onPreview(draftState.value, null)
        onDismiss()
    }

    fun updateDraft(field: String?, update: (EditAdjustments) -> EditAdjustments) {
        draft = update(draft).normalized()
        // Intentionally no preview: the host renders once on release.
    }

    // Always consume back while the sheet is open. Disabling the handler while
    // saving would leave back unconsumed and let the NavHost pop the editor
    // back to the gallery; instead swallow it until the save finishes. The
    // colour-picker layer still takes priority over closing the sheet.
    BackHandler {
        if (saving) return@BackHandler
        if (picking) picking = false else commitAndDismiss()
    }

    // Root is a bare, fully transparent Box: NO scrim and NO pointerInput on
    // the upper half. Taps there pass straight through to the editor's
    // TopAppBar (preset switch / undo / redo / history), so those actions stay
    // reachable while the adjustment panel is open. Only the bottom panel and
    // the (temporary) colour-picker consume touches.
    Box(Modifier.fillMaxSize().testTag("adjustments_host")) {
        if (picking) {
            ColorPickLayer(
                displayBitmap = previewBitmap ?: sourceBitmap,
                sampleBitmap = sourceBitmap ?: previewBitmap,
                onPick = { hue ->
                    draft = draft.copy(
                        pointColor = draft.pointColor.copy(targetHue = hue)
                    ).normalized()
                    hslTarget = HslTarget.Picked
                    tab = AdjustTab.HSL
                    picking = false
                    previewNow("点颜色")
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(pickHeight)
                    // Leave the status bar + TopAppBar strip untouched so the
                    // top action bar stays visible and tappable while picking.
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .testTag("hsl_color_pick_layer")
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
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
                .testTag("adjustments_panel")
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
                        "左右切换子页面 · 松手后渲染 · ± 微调（长按连调）· 双击滑块归零",
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
                        .testTag("adjustments_close")
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

            // Tab strip: one short page per feature, no long scrolling list.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AdjustTab.entries.forEach { entry ->
                    FilterChip(
                        selected = tab == entry,
                        onClick = {
                            tab = entry
                            // Leaving HSL must drop a pending colour pick so
                            // the picker overlay never covers another page.
                            if (entry != AdjustTab.HSL) picking = false
                        },
                        label = { Text(entry.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = Ink,
                            containerColor = CardHigh.copy(alpha = 0.30f),
                            labelColor = TextPrimary
                        ),
                        modifier = Modifier.testTag("adjust_tab_${entry.name}")
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                when (tab) {
                    AdjustTab.LIGHT, AdjustTab.COLOR, AdjustTab.DETAIL, AdjustTab.QUALITY -> {
                        val group = AdjustmentGroups[tab.groupIndex!!]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(CardHigh.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
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
                                    enabled = !saving,
                                    // 0..100 controls (denoise / grain) have
                                    // their zero at the LEFT edge. Without
                                    // this the anchor was drawn at the centre,
                                    // so the thumb and fill looked misplaced.
                                    fillFromStart = spec.range.start >= -0.001f
                                )
                                if (spec.help != null) {
                                    Text(
                                        spec.help,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(
                                            start = 2.dp, end = 2.dp, bottom = 8.dp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    AdjustTab.HSL -> HslPage(
                        hsl = draft.hsl,
                        pointColor = draft.pointColor,
                        target = hslTarget,
                        onTargetChange = { hslTarget = it },
                        picking = picking,
                        onRequestPick = { picking = true },
                        onCancelPick = { picking = false },
                        onBandChange = { index, band: HslBand ->
                            updateDraft("HSL") { it.copy(hsl = it.hsl.withBand(index, band)) }
                        },
                        onPointColorChange = { pc: PointColor ->
                            updateDraft("点颜色") { it.copy(pointColor = pc) }
                        },
                        onFinished = { field -> previewNow(field) },
                        enabled = !saving
                    )

                    AdjustTab.PORTRAIT -> PortraitSection(
                        portrait = draft.portrait,
                        expanded = true,
                        onToggle = {},
                        onChange = { portrait, field ->
                            updateDraft(field) { it.copy(portrait = portrait) }
                        },
                        onFinished = { field -> previewNow(field) },
                        enabled = !saving,
                        embedded = true
                    )

                    AdjustTab.TONE -> ToneSection(
                        points = curveDraft,
                        baseCurve = baseCurve,
                        expanded = true,
                        onToggle = {},
                        onPointsChange = { points ->
                            curveDraft = points
                            curveDirty = true
                        },
                        onDrag = { },
                        onFinished = { previewCurveNow() },
                        onReset = {
                            curveDraft = presetToneCurve.normalizedPoints
                            curveDirty = false
                            onClearToneCurve()
                        },
                        enabled = !saving,
                        embedded = true
                    )

                    AdjustTab.FILTER -> CreativeFilterSection(
                        selectedId = draft.creativeFilter,
                        strength = draft.filterStrength,
                        expanded = true,
                        onToggle = {},
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
                        enabled = !saving,
                        embedded = true
                    )

                    AdjustTab.MASK -> MaskLauncherCard(
                        layerCount = draft.local.layers.size,
                        onOpen = onOpenMaskEditor
                    )
                }
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
                            if (curveDirty) {
                                onToneCurvePreview(ToneCurve(curveState.value))
                            }
                            onPreview(draftState.value, null)
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
