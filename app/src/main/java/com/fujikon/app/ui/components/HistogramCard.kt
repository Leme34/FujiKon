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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Aggregated histogram + tone statistics of the current preview. */
private class HistogramData(
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
    val meanLuma: Float,
    val shadowClip: Float,
    val highlightClip: Float,
    val spread: Float,
    val saturation: Float
)

/**
 * Lightroom-style live histogram overlaid on the preview.
 *
 * The card uses the same translucent gradient as the adjustments panel and is
 * always visible while a preview is rendered. Below the chart it shows a hint
 * derived from the current distribution and a one-tap auto-fix button.
 */
@Composable
fun HistogramCard(
    bitmap: Bitmap?,
    currentAdjustments: EditAdjustments,
    onApplyAdjustments: (EditAdjustments) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 190.dp
) {
    // Keep the previous distribution while the next preview is being analysed,
    // so the chart does not blink on every slider move.
    var data by remember { mutableStateOf<HistogramData?>(null) }
    LaunchedEffect(bitmap) {
        val next = withContext(Dispatchers.Default) { bitmap?.let { analyse(it) } }
        if (next != null) data = next
    }

    val shape = RoundedCornerShape(16.dp)
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
        // Title row removed per request: no labels above chart
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
            text = data?.let { hintFor(it) } ?: "分析中…",
            style = MaterialTheme.typography.labelSmall.copy(
                shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 3f)
            ),
            color = TextPrimary,
            maxLines = 2
        )

        val suggestion = data?.let { autoFixSuggestion(it, currentAdjustments) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.Black.copy(alpha = 0.28f))
                .border(1.dp, Outline.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                .clickable(enabled = suggestion != null) { if (suggestion != null) onApplyAdjustments(suggestion) },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "一键修图",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Amber.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/** Downscale to at most 320 px, then bucket channels + luma statistics. */
private fun analyse(source: Bitmap): HistogramData? {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
    val longest = max(source.width, source.height)
    val sample = if (longest > 320) {
        val scale = 320f / longest
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else {
        source
    }
    val w = sample.width
    val h = sample.height
    val pixels = IntArray(w * h)
    sample.getPixels(pixels, 0, w, 0, 0, w, h)
    if (sample !== source) sample.recycle()

    val bins = 64
    val r = IntArray(bins)
    val g = IntArray(bins)
    val b = IntArray(bins)
    val luma = IntArray(256)
    var shadow = 0
    var highlight = 0
    var lumaSum = 0f
    var satSum = 0f

    for (px in pixels) {
        val cr = (px shr 16) and 0xFF
        val cg = (px shr 8) and 0xFF
        val cb = px and 0xFF
        r[cr * bins / 256]++
        g[cg * bins / 256]++
        b[cb * bins / 256]++
        val l = (0.2126f * cr + 0.7152f * cg + 0.0722f * cb) / 255f
        luma[(l * 255f).toInt().coerceIn(0, 255)]++
        lumaSum += l
        if (l < 0.02f) shadow++
        if (l > 0.98f) highlight++
        val mx = max(cr, max(cg, cb))
        val mn = min(cr, min(cg, cb))
        if (mx > 0) satSum += (mx - mn).toFloat() / mx
    }

    val total = pixels.size.toFloat().coerceAtLeast(1f)
    var maxBin = 1
    for (i in 0 until bins) maxBin = max(maxBin, max(r[i], max(g[i], b[i])))

    fun normalise(values: IntArray): FloatArray = FloatArray(bins) { i ->
        sqrt(values[i].toFloat() / maxBin)
    }

    // 5th / 95th percentile of luma for a contrast estimate.
    var acc = 0
    var p5 = 0
    var p95 = 255
    val p5Target = (total * 0.05f).toInt()
    val p95Target = (total * 0.95f).toInt()
    for (i in 0 until 256) {
        acc += luma[i]
        if (p5 == 0 && acc >= p5Target) p5 = i
        if (acc >= p95Target) {
            p95 = i
            break
        }
    }

    return HistogramData(
        red = normalise(r),
        green = normalise(g),
        blue = normalise(b),
        meanLuma = lumaSum / total,
        shadowClip = shadow / total,
        highlightClip = highlight / total,
        spread = (p95 - p5) / 255f,
        saturation = satSum / total
    )
}

private fun hintFor(data: HistogramData): String {
    val issues = buildList {
        if (data.meanLuma < 0.32f) add("整体偏暗")
        if (data.meanLuma > 0.70f) add("整体偏亮")
        if (data.highlightClip > 0.03f) add("高光溢出")
        if (data.shadowClip > 0.04f) add("暗部死黑")
        if (data.spread < 0.48f) add("反差偏平")
        if (data.saturation < 0.14f) add("色彩偏淡")
    }
    if (issues.isEmpty()) return "曝光均衡，色调自然"
    return issues.take(2).joinToString(" · ") + "，建议一键优化"
}

/**
 * Rule-based "AI" recommendation: nudges exposure, highlights, shadows,
 * contrast and vibrance based on how the current preview is distributed.
 * Every delta is applied on top of the existing adjustments.
 */
private fun autoFixSuggestion(
    data: HistogramData,
    current: EditAdjustments
): EditAdjustments {
    var exposure = current.exposure
    var highlights = current.highlights
    var shadows = current.shadows
    var whites = current.whites
    var blacks = current.blacks
    var contrast = current.contrast
    var vibrance = current.vibrance

    // Lightroom-style Auto Tone: gentle, preserve detail.
    val mean = data.meanLuma
    when {
        mean < 0.28f -> exposure += 0.30f
        mean > 0.75f -> exposure -= 0.25f
        mean < 0.38f -> exposure += 0.15f
        mean > 0.65f -> exposure -= 0.10f
    }

    // Recover highlights only when genuinely clipped
    if (data.highlightClip > 0.05f) highlights -= 12f
    else if (data.highlightClip > 0.02f) highlights -= 6f
    if (data.highlightClip > 0.04f) whites -= 8f

    // Lift shadows gently; avoid crushed blacks becoming grey
    if (data.shadowClip > 0.08f) shadows += 14f
    else if (data.shadowClip > 0.04f) shadows += 8f
    if (data.shadowClip > 0.06f) blacks += 6f

    // Contrast: widen narrow distributions, tame overly wide ones
    when {
        data.spread < 0.35f -> contrast += 10f
        data.spread < 0.45f -> contrast += 6f
        data.spread > 0.88f -> contrast -= 8f
    }

    // Saturation: modest boost only when clearly muted
    if (data.saturation < 0.12f) vibrance += 8f
    else if (data.saturation < 0.18f) vibrance += 4f

    return EditAdjustments(
        temperature = current.temperature,
        tint = current.tint,
        vibrance = vibrance,
        exposure = exposure,
        contrast = contrast,
        highlights = highlights,
        shadows = shadows,
        whites = whites,
        blacks = blacks,
        texture = current.texture,
        clarity = current.clarity,
        dehaze = current.dehaze
    ).normalized()
}
