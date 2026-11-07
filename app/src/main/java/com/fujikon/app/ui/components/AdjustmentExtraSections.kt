package com.fujikon.app.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.CreativeFilter
import com.fujikon.app.data.HslAdjustments
import com.fujikon.app.data.HslBand
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
internal fun AdjustmentSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
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
}

@Composable
internal fun HslSection(
    hsl: HslAdjustments,
    expanded: Boolean,
    onToggle: () -> Unit,
    onBandChange: (Int, HslBand) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean
) {
    var selectedBand by remember { mutableIntStateOf(0) }
    AdjustmentSectionHeader("HSL", expanded, onToggle)
    if (!expanded) return

    Column(
        Modifier
            .fillMaxWidth()
            .background(CardHigh.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            HslAdjustments.BandNames.forEachIndexed { index, name ->
                val selected = selectedBand == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { selectedBand = index }
                        .padding(horizontal = 2.dp, vertical = 3.dp)
                ) {
                    Box(
                        Modifier
                            .size(if (selected) 30.dp else 25.dp)
                            .background(Color(HslAdjustments.BandColors[index]), CircleShape)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) Amber else Color.White.copy(alpha = 0.16f),
                                shape = CircleShape
                            )
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Amber else TextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "当前：${HslAdjustments.BandNames[selectedBand]} · 双击滑块归零",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        val band = hsl.band(selectedBand)
        AdjustmentSlider(
            label = "色相",
            value = band.hue,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onBandChange(selectedBand, band.copy(hue = it)) },
            onValueChangeFinished = { onFinished("HSL ${HslAdjustments.BandNames[selectedBand]} 色相") },
            onReset = { onBandChange(selectedBand, band.copy(hue = 0f)); onFinished("HSL") },
            enabled = enabled
        )
        AdjustmentSlider(
            label = "饱和度",
            value = band.saturation,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onBandChange(selectedBand, band.copy(saturation = it)) },
            onValueChangeFinished = { onFinished("HSL ${HslAdjustments.BandNames[selectedBand]} 饱和度") },
            onReset = { onBandChange(selectedBand, band.copy(saturation = 0f)); onFinished("HSL") },
            enabled = enabled
        )
        AdjustmentSlider(
            label = "明度",
            value = band.luminance,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = { onBandChange(selectedBand, band.copy(luminance = it)) },
            onValueChangeFinished = { onFinished("HSL ${HslAdjustments.BandNames[selectedBand]} 明度") },
            onReset = { onBandChange(selectedBand, band.copy(luminance = 0f)); onFinished("HSL") },
            enabled = enabled
        )
    }
}

@Composable
internal fun CreativeFilterSection(
    selectedId: String,
    strength: Float,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (CreativeFilter) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onStrengthFinished: () -> Unit,
    enabled: Boolean,
    /** Embedded in a tab page: no collapsible header, always expanded. */
    embedded: Boolean = false
) {
    val selected = CreativeFilter.fromId(selectedId)
    if (!embedded) AdjustmentSectionHeader("滤镜", expanded, onToggle)
    if (!expanded && !embedded) return

    Column(
        Modifier
            .fillMaxWidth()
            .background(CardHigh.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        FilterRow(
            title = "风光",
            filters = CreativeFilter.landscape,
            selectedId = selected.id,
            onSelect = onSelect,
            enabled = enabled
        )
        Spacer(Modifier.height(6.dp))
        FilterRow(
            title = "人像",
            filters = CreativeFilter.portrait,
            selectedId = selected.id,
            onSelect = onSelect,
            enabled = enabled
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("无", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = selected.isNone,
                onClick = { onSelect(CreativeFilter.NONE) },
                enabled = enabled,
                label = { Text("原图色彩") },
                colors = filterChipColors()
            )
        }
        if (!selected.isNone) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "强度",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(44.dp)
                )
                Slider(
                    value = strength,
                    onValueChange = onStrengthChange,
                    onValueChangeFinished = onStrengthFinished,
                    valueRange = 0f..100f,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = CardHigh.copy(alpha = 0.8f)
                    )
                )
                Text(
                    "${strength.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    title: String,
    filters: List<CreativeFilter>,
    selectedId: String,
    onSelect: (CreativeFilter) -> Unit,
    enabled: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.width(44.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filters, key = { it.id }) { filter ->
                FilterChip(
                    selected = filter.id == selectedId,
                    onClick = { onSelect(filter) },
                    enabled = enabled,
                    label = { Text(filter.displayName) },
                    colors = filterChipColors()
                )
            }
        }
    }
}

@Composable
private fun filterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = CardHigh.copy(alpha = 0.28f),
    labelColor = TextSecondary,
    selectedContainerColor = Amber.copy(alpha = 0.20f),
    selectedLabelColor = Amber
)

@Composable
internal fun ToneSection(
    points: List<ToneCurvePoint>,
    baseCurve: List<Pair<Float, Float>>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPointsChange: (List<ToneCurvePoint>) -> Unit,
    onDrag: () -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
    enabled: Boolean,
    /** Embedded in a tab page: no collapsible header, always expanded. */
    embedded: Boolean = false
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    if (!embedded) AdjustmentSectionHeader("影调", expanded, onToggle)
    if (!expanded && !embedded) return

    Column(
        Modifier
            .fillMaxWidth()
            .background(CardHigh.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        ToneCurveEditor(
            points = points,
            basePoints = baseCurve,
            selectedIndex = selectedIndex,
            onPointsChange = onPointsChange,
            onSelectedChange = { selectedIndex = it },
            onDrag = onDrag,
            onInteractionFinished = onFinished,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "点击加点 · 拖动调节 · 双击删除",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selectedIndex = null; onReset() }, enabled = enabled) {
                Text("恢复预设曲线", color = Amber)
            }
        }
    }
}
