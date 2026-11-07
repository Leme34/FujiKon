package com.fujikon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.PortraitRetouch
import com.fujikon.app.data.PortraitStyle
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Portrait retouch section: style chips plus the four skin strengths.
 *
 * Only skin is affected, so the controls stay useful on portraits shot with
 * any preset or creative filter.
 */
@Composable
internal fun PortraitSection(
    portrait: PortraitRetouch,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (PortraitRetouch, String) -> Unit,
    onFinished: (String) -> Unit,
    enabled: Boolean,
    /** Embedded in a tab page: no collapsible header, always expanded. */
    embedded: Boolean = false
) {
    if (!embedded) {
        AdjustmentSectionHeader(
            title = "人像",
            expanded = expanded,
            onToggle = onToggle
        )
    }
    if (!expanded && !embedded) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (listOf(PortraitStyle.NONE) + PortraitStyle.presets).forEach { style ->
                val selected = portrait.style == style.id
                OutlinedButton(
                    onClick = {
                        // Style chips are a one-shot press: request the preview
                        // right away instead of waiting for the drag throttle.
                        onChange(portrait.withStyle(style), "人像风格")
                        onFinished("人像风格")
                    },
                    enabled = enabled,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, if (selected) Amber else Outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) Amber else TextSecondary
                    )
                ) {
                    Text(style.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        PortraitSlider(
            label = "磨皮",
            value = portrait.smooth,
            onChange = { onChange(portrait.copy(smooth = it).asCustom(), "磨皮") },
            onFinished = { onFinished("磨皮") },
            enabled = enabled
        )
        PortraitSlider(
            label = "美白",
            value = portrait.whiten,
            onChange = { onChange(portrait.copy(whiten = it).asCustom(), "美白") },
            onFinished = { onFinished("美白") },
            enabled = enabled
        )
        PortraitSlider(
            label = "红润",
            value = portrait.blush,
            onChange = { onChange(portrait.copy(blush = it).asCustom(), "红润") },
            onFinished = { onFinished("红润") },
            enabled = enabled
        )
        PortraitSlider(
            label = "立体",
            value = portrait.contour,
            onChange = { onChange(portrait.copy(contour = it).asCustom(), "立体") },
            onFinished = { onFinished("立体") },
            enabled = enabled
        )
    }
}

@Composable
private fun PortraitSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    enabled: Boolean
) {
    AdjustmentSlider(
        label = label,
        value = value,
        valueRange = 0f..PortraitRetouch.MAX_STRENGTH,
        onValueChange = onChange,
        onValueChangeFinished = onFinished,
        onReset = {
            onChange(0f)
            onFinished()
        },
        step = 1f,
        formattedValue = value.roundToInt().toString(),
        enabled = enabled,
        fillFromStart = true
    )
}
