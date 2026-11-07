package com.fujikon.app.ui.components

import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.HslAdjustments
import com.fujikon.app.data.HslBand
import com.fujikon.app.data.PointColor
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * What the three HSL sliders are currently editing.
 *
 * [Band] is the classic eight-channel HSL editor; [Picked] is the former
 * "point colour" feature: a colour sampled from the photo, so only pixels
 * near that hue are shifted. Both live on the same page now.
 */
sealed interface HslTarget {
    data class Band(val index: Int) : HslTarget
    object Picked : HslTarget
}

private fun swatchFor(hue: Float): Color {
    val argb = AndroidColor.HSVToColor(
        floatArrayOf(((hue % 360f) + 360f) % 360f, 0.82f, 0.92f)
    )
    return Color(
        red = AndroidColor.red(argb) / 255f,
        green = AndroidColor.green(argb) / 255f,
        blue = AndroidColor.blue(argb) / 255f
    )
}

/**
 * The merged HSL / point-colour page.
 *
 * The "从预览图选色" button turns the upper half of the screen into a colour
 * picker; the sampled hue becomes the single colour that the 色相 / 饱和度 /
 * 明度 sliders below will change. Selecting one of the eight swatches instead
 * edits that classic HSL channel.
 */
@Composable
fun HslPage(
    hsl: HslAdjustments,
    pointColor: PointColor,
    target: HslTarget,
    onTargetChange: (HslTarget) -> Unit,
    picking: Boolean,
    onRequestPick: () -> Unit,
    onCancelPick: () -> Unit,
    onBandChange: (Int, HslBand) -> Unit,
    onPointColorChange: (PointColor) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean
) {
    val bandIndex = (target as? HslTarget.Band)?.index ?: -1
    val picked = target is HslTarget.Picked

    Column(Modifier.fillMaxWidth()) {
        Text(
            "只改一种颜色：点「从预览图选色」，在上半屏照片里点一下目标颜色，" +
                "再用下方色相 / 饱和度 / 明度滑动条调节；也可以点下方色块按传统 HSL 色带调整。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { if (picking) onCancelPick() else onRequestPick() },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .testTag("hsl_pick_color_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (picking) CardHigh else Amber,
                contentColor = if (picking) TextPrimary else Ink
            )
        ) {
            Text(
                if (picking) "取消取色" else "从预览图选色（只改这一种颜色）",
                fontWeight = FontWeight.Bold
            )
        }

        if (picking) {
            Text(
                "↑ 在上方照片里点一下要修改的颜色，选完自动回到这里",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            HslAdjustments.BandNames.forEachIndexed { index, name ->
                val selected = bandIndex == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onTargetChange(HslTarget.Band(index)) }
                        .padding(horizontal = 2.dp, vertical = 3.dp)
                        .testTag("hsl_band_$index")
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

        // Current target readout: a colour chip plus a human-readable name.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (picked) swatchFor(pointColor.targetHue)
                        else Color(HslAdjustments.BandColors[bandIndex.coerceIn(0, 7)])
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (picked) {
                    "已选：${hueLabel(pointColor.targetHue)} · ${pointColor.targetHue.roundToInt()}°"
                } else {
                    "当前色带：${HslAdjustments.BandNames[bandIndex.coerceIn(0, 7)]}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        if (picked) {
            val pc = pointColor
            AdjustmentSlider(
                label = "色相",
                value = pc.hueShift,
                valueRange = -100f..100f,
                step = 1f,
                onValueChange = { onPointColorChange(pc.copy(hueShift = it)) },
                onValueChangeFinished = { onFinished("点颜色 色相") },
                onReset = {
                    onPointColorChange(pc.copy(hueShift = 0f))
                    onFinished("点颜色")
                },
                enabled = enabled
            )
            AdjustmentSlider(
                label = "饱和度",
                value = pc.saturation,
                valueRange = -100f..100f,
                step = 1f,
                onValueChange = { onPointColorChange(pc.copy(saturation = it)) },
                onValueChangeFinished = { onFinished("点颜色 饱和度") },
                onReset = {
                    onPointColorChange(pc.copy(saturation = 0f))
                    onFinished("点颜色")
                },
                enabled = enabled
            )
            AdjustmentSlider(
                label = "明度",
                value = pc.luminance,
                valueRange = -100f..100f,
                step = 1f,
                onValueChange = { onPointColorChange(pc.copy(luminance = it)) },
                onValueChangeFinished = { onFinished("点颜色 明度") },
                onReset = {
                    onPointColorChange(pc.copy(luminance = 0f))
                    onFinished("点颜色")
                },
                enabled = enabled
            )
            AdjustmentSlider(
                label = "选取范围",
                value = pc.hueRange,
                valueRange = 5f..90f,
                step = 1f,
                formattedValue = "±${pc.hueRange.roundToInt()}°",
                onValueChange = { onPointColorChange(pc.copy(hueRange = it)) },
                onValueChangeFinished = { onFinished("点颜色") },
                onReset = {
                    onPointColorChange(pc.copy(hueRange = 30f))
                    onFinished("点颜色")
                },
                enabled = enabled,
                fillFromStart = true
            )

            if (pc.isNeutral()) {
                Text(
                    "已选色，但还没改它：请调整上方色相 / 饱和度 / 明度，或点一键试用",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                TextButton(
                    onClick = {
                        onPointColorChange(pc.copy(saturation = 32f, luminance = 14f))
                        onFinished("点颜色")
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("hsl_pick_try")
                ) {
                    Text("一键试用：增艳 + 提亮选中色", color = Amber)
                }
            } else {
                Text(
                    "已生效 · 只影响画面中「${hueLabel(pc.targetHue)}」附近的像素",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                TextButton(
                    onClick = {
                        onPointColorChange(pc.copy(hueShift = 0f, saturation = 0f, luminance = 0f))
                        onFinished("点颜色")
                    },
                    enabled = enabled
                ) {
                    Text("复原件", color = TextSecondary)
                }
            }
        } else {
            val band = hsl.band(bandIndex)
            AdjustmentSlider(
                label = "色相",
                value = band.hue,
                valueRange = -100f..100f,
                step = 1f,
                onValueChange = { onBandChange(bandIndex, band.copy(hue = it)) },
                onValueChangeFinished = { onFinished("HSL ${HslAdjustments.BandNames[bandIndex]} 色相") },
                onReset = {
                    onBandChange(bandIndex, band.copy(hue = 0f))
                    onFinished("HSL")
                },
                enabled = enabled
            )
            AdjustmentSlider(
                label = "饱和度",
                value = band.saturation,
                valueRange = -100f..100f,
                step = 1f,
                onValueChange = { onBandChange(bandIndex, band.copy(saturation = it)) },
                onValueChangeFinished = { onFinished("HSL ${HslAdjustments.BandNames[bandIndex]} 饱和度") },
                onReset = {
                    onBandChange(bandIndex, band.copy(saturation = 0f))
                    onFinished("HSL")
                },
                enabled = enabled
            )
            AdjustmentSlider(
                label = "明度",
                value = band.luminance,
                valueRange = -100f..100f,
                step = 1f,
                onValueChange = { onBandChange(bandIndex, band.copy(luminance = it)) },
                onValueChangeFinished = { onFinished("HSL ${HslAdjustments.BandNames[bandIndex]} 明度") },
                onReset = {
                    onBandChange(bandIndex, band.copy(luminance = 0f))
                    onFinished("HSL")
                },
                enabled = enabled
            )
            Text(
                "传统 HSL：按 8 个色相通道分别调节；想只改画面里某个具体颜色，请点上方的「从预览图选色」。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}
