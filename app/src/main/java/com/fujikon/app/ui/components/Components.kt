package com.fujikon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.render.PresetThumbnailStore
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card as CardColor
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.HueColors
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.theme.TextPrimary
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio

@Composable
fun CurveChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = Amber,
    showSpectrum: Boolean = true,
    showDiagonal: Boolean = true,
    gridAlpha: Float = 0.55f
) {
    Canvas(modifier = modifier) {
        val stripHeight = if (showSpectrum) 5.dp.toPx() else 0f
        val chartHeight = (size.height - stripHeight).coerceAtLeast(1f)
        val width = size.width

        val gridColor = Outline.copy(alpha = gridAlpha)
        for (i in 1..3) {
            val x = width * i / 4f
            drawLine(gridColor, Offset(x, 0f), Offset(x, chartHeight), 1.dp.toPx())
            val y = chartHeight * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(width, y), 1.dp.toPx())
        }

        if (showDiagonal) {
            drawLine(
                color = TextSecondary.copy(alpha = 0.22f),
                start = Offset(0f, chartHeight),
                end = Offset(width, 0f),
                strokeWidth = 1f
            )
        }

        if (points.size >= 2) {
            val path = Path()
            points.forEachIndexed { index, (x, y) ->
                val px = x.coerceIn(0f, 1f) * width
                val py = (1f - y.coerceIn(0f, 1f)) * chartHeight
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(
                path = path,
                color = lineColor.copy(alpha = 0.16f),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        if (showSpectrum) {
            val colors = HueColors + HueColors.first()
            drawRect(
                brush = Brush.horizontalGradient(colors),
                topLeft = Offset(0f, chartHeight + 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(width, stripHeight)
            )
        }
    }
}

@Composable
fun PresetCard(
    preset: Preset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    photo: PhotoItem? = null,
    thumbnailStore: PresetThumbnailStore? = null,
    onFavoriteClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor)
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                val fallback: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(CardHigh)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        CurveChart(
                            points = preset.effectiveCurvePoints(32),
                            modifier = Modifier.fillMaxSize(),
                            showSpectrum = false,
                            showDiagonal = false,
                            gridAlpha = 0.3f
                        )
                    }
                }
                if (thumbnailStore != null) {
                    PresetThumbnail(
                        photo = photo,
                        preset = preset,
                        store = thumbnailStore,
                        modifier = Modifier.fillMaxSize(),
                        targetSize = 320,
                        placeholder = fallback
                    )
                } else {
                    fallback()
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preset.baseProfileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onFavoriteClick != null) {
                    IconButton(onClick = onFavoriteClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (preset.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "收藏",
                            tint = if (preset.favorite) Amber else TextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(preset.format)
                MetaChip(
                    if (preset.hasCurve || preset.hasToneCurve) "曲线" else "参数",
                    tint = if (preset.hasToneCurve) Amber else TextSecondary
                )
                when {
                    preset.isDerived -> MetaChip("副本")
                    preset.isSample -> MetaChip("示例")
                }
            }
        }
    }
}

@Composable
fun MetaChip(text: String, tint: Color = TextSecondary) {
    Surface(
        shape = RoundedCornerShape(50),
        color = CardHigh,
        contentColor = tint
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun PhotoCell(
    uri: android.net.Uri,
    selected: Boolean,
    selectionNumber: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(CardHigh)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Amber.copy(alpha = 0.18f))
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(24.dp)
                    .background(Amber, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectionNumber?.toString() ?: "✓",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(14.dp)
                    .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(50))
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}
