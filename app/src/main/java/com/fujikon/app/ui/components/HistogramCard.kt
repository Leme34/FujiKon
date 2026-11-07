package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import com.fujikon.app.render.AutoEnhance
import com.fujikon.app.render.AutoEnhanceResult
import com.fujikon.app.render.ImageStats
import com.fujikon.app.render.OptimizationReport
import com.fujikon.app.render.PresetRecommendation
import com.fujikon.app.render.PresetRecommender
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-line, explainable summary of what the closed loop actually measured. */
private fun reportLine(r: OptimizationReport): String {
    val rounds = if (r.iterations == 0) {
        "原片已达标 · 无需调整"
    } else {
        "闭环 ${r.iterations} 轮" + if (r.converged) "达标" else "取最优"
    }
    return "$rounds · 高光 ${"%.1f".format(r.highlightClip * 100)}% / " +
        "暗部 ${"%.1f".format(r.shadowClip * 100)}%"
}

/**
 * Lightroom-style live histogram overlaid on the preview.
 *
 * The card uses the same translucent gradient as the adjustments panel and is
 * always visible while a preview is rendered. Below the chart it shows the
 * problems actually measured in the pixels and a one-tap "一键优化" button.
 *
 * The button is a TRUE one-tap closed loop: on tap it renders candidate
 * parameters on the original (downscaled), measures the real pixels, and keeps
 * refining until 不过曝 / 不死黑 is met — see [AutoEnhance.optimize]. The
 * zone description, the convergence report and the offline preset picks form
 * one collapsible white-on-photo panel with a chevron toggle.
 */
@Composable
fun HistogramCard(
    bitmap: Bitmap?,
    currentAdjustments: EditAdjustments,
    onApplyAdjustments: (EditAdjustments) -> Unit,
    /** Presets available for the offline, explainable recommendation. */
    presets: List<Preset> = emptyList(),
    onSelectPreset: (String) -> Unit = {},
    /** Unedited original: the closed loop must verify against this, not the
     *  already-graded preview, or repeated taps would drift. */
    sourceBitmap: Bitmap? = null,
    /** Current preset (incl. its tone/channel curves) used as render base. */
    basePreset: Preset? = null,
    modifier: Modifier = Modifier,
    width: Dp = 190.dp
) {
    var data by remember { mutableStateOf<ImageStats?>(null) }
    var optimised by remember { mutableStateOf<AutoEnhanceResult?>(null) }
    var appliedSummary by remember { mutableStateOf<String?>(null) }
    var recommendations by remember { mutableStateOf<List<PresetRecommendation>>(emptyList()) }
    var report by remember { mutableStateOf<OptimizationReport?>(null) }
    var optimizing by remember { mutableStateOf(false) }
    /** Whether the zone description + report + preset picks panel is expanded. */
    var detailsExpanded by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Reset only when the PHOTO changes, not on every preview re-render, so the
    // "已优化 · 闭环 N 轮达标" confirmation stays visible after applying.
    LaunchedEffect(sourceBitmap) {
        appliedSummary = null
        report = null
    }
    LaunchedEffect(bitmap, presets) {
        if (presets.isEmpty()) { recommendations = emptyList(); return@LaunchedEffect }
        recommendations = withContext(Dispatchers.Default) {
            AutoEnhance.analyse(bitmap)?.let { PresetRecommender.rank(it, presets) } ?: emptyList()
        }
    }
    // Fast single-pass preview hint only. The expensive render→verify loop is
    // deliberately deferred to the button tap so slider dragging never stalls.
    LaunchedEffect(bitmap, currentAdjustments) {
        val pair = withContext(Dispatchers.Default) {
            val stats = AutoEnhance.analyse(bitmap)
            stats?.let { it to AutoEnhance.suggestZoned(bitmap, it, currentAdjustments) }
        }
        if (pair != null) {
            data = pair.first
            optimised = pair.second
        }
    }

    val shape = RoundedCornerShape(16.dp)
    val hasDetails = optimised?.zones?.isNotEmpty() == true ||
        recommendations.isNotEmpty() || report != null
    val whiteOnPhoto = MaterialTheme.typography.labelSmall.copy(
        shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 3f)
    )
    Column(
        modifier = modifier
            .width(width)
            .background(
                brush = Brush.verticalGradient(
                    0f to InkElevated.copy(alpha = 0.10f),
                    0.30f to InkElevated.copy(alpha = 0.28f),
                    1f to InkElevated.copy(alpha = 0.52f)
                ),
                shape = shape
            )
            .border(1.dp, Outline.copy(alpha = 0.55f), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            val w = size.width
            val h = size.height
            val bins = 64
            val bw = w / bins

            for (i in 1..3) {
                val x = w * i / 4f
                drawLine(
                    color = TextSecondary.copy(alpha = 0.16f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
            }
            drawLine(
                color = TextSecondary.copy(alpha = 0.20f),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1f
            )

            fun channel(values: FloatArray, color: Color) {
                val path = Path()
                path.moveTo(0f, h)
                for (i in 0 until bins) {
                    val x = i * bw + bw / 2f
                    val y = h - values[i].coerceIn(0f, 1f) * (h - 2f)
                    path.lineTo(x, y)
                }
                path.lineTo(w, h)
                path.close()
                drawPath(path, color.copy(alpha = 0.42f))
            }

            val d = data
            if (d != null) {
                channel(d.red, Color(0xFFFF5252))
                channel(d.green, Color(0xFF69D96F))
                channel(d.blue, Color(0xFF5A9BFF))
            } else {
                drawLine(
                    color = TextSecondary.copy(alpha = 0.25f),
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1f
                )
            }
        }

        Text(
            text = appliedSummary?.let { "已优化：$it" } ?: optimised?.let { res ->
                if (!res.changed || res.findings.isEmpty()) {
                    "曝光均衡，色调自然，无需优化"
                } else {
                    res.findings.take(2).joinToString(" · ") + "，建议一键优化"
                }
            } ?: "分析中…",
            style = MaterialTheme.typography.labelSmall.copy(
                shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 3f)
            ),
            color = if (appliedSummary != null) Amber else TextPrimary,
            maxLines = 2,
            modifier = Modifier.testTag("auto_enhance_hint")
        )

        // Collapsible panel: convergence report + zone description + presets.
        if (hasDetails) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { detailsExpanded = !detailsExpanded }
                        .padding(vertical = 2.dp)
                        .testTag("auto_enhance_details_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "分区与推荐",
                        style = whiteOnPhoto,
                        color = Color.White,
                        modifier = Modifier.testTag("auto_enhance_details_label")
                    )
                    Icon(
                        imageVector = if (detailsExpanded) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = if (detailsExpanded) "隐藏分区与推荐" else "显示分区与推荐",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (detailsExpanded) {
                    report?.let { r ->
                        Text(
                            text = reportLine(r),
                            style = whiteOnPhoto,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("auto_enhance_report")
                        )
                    }
                    optimised?.let { res ->
                        if (res.zones.isNotEmpty()) {
                            Text(
                                text = "分区：" + res.zones.joinToString("、") +
                                    if (res.providerLabel.isNotEmpty()) "\n" + res.providerLabel else "",
                                style = whiteOnPhoto,
                                color = Color.White,
                                maxLines = 2,
                                modifier = Modifier.testTag("auto_enhance_zones")
                            )
                        }
                    }
                    if (recommendations.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "推荐预设（本机匹配）",
                                style = whiteOnPhoto,
                                color = Color.White,
                                modifier = Modifier.testTag("preset_recommendation_header")
                            )
                            recommendations.forEach { rec ->
                                Text(
                                    text = "· ${rec.presetName} — ${rec.reason}",
                                    style = whiteOnPhoto,
                                    color = Color.White,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectPreset(rec.presetId) }
                                        .testTag("preset_recommendation")
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Amber.copy(alpha = 0.16f))
                .border(1.dp, Amber.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .clickable(enabled = optimised != null && !optimizing) {
                    optimised ?: return@clickable
                    scope.launch {
                        optimizing = true
                        try {
                            // TRUE one-tap: render → measure → refine on the
                            // ORIGINAL until 不过曝 / 不死黑 is met.
                            val result = withContext(Dispatchers.Default) {
                                AutoEnhance.optimize(
                                    source = sourceBitmap ?: bitmap,
                                    basePreset = basePreset ?: Preset.none(),
                                    current = currentAdjustments
                                )
                            }
                            appliedSummary = result.summary
                            report = result.report
                            onApplyAdjustments(result.adjustments)
                        } finally {
                            optimizing = false
                        }
                    }
                }
                .testTag("auto_enhance_button"),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (optimizing) "优化中…" else "一键优化",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Amber
                )
            }
        }
    }
}
