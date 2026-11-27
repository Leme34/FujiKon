package com.fujikon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary

/**
 * The seven editor tools. Since 1.6.29 they live in ONE horizontally
 * scrollable row; "导出" is no longer a bottom tool — it moved into the
 * top-right "更多" menu together with copy / paste effects.
 */
enum class EditorTool(val label: String, val icon: ImageVector) {
    PRESET("预设", Icons.Filled.Tune),
    ADJUST("调节", Icons.Filled.Equalizer),
    CROP("裁剪", Icons.Filled.Crop),
    CURVE("曲线", Icons.Filled.Timeline),
    BLUR("模糊", Icons.Filled.BlurOn),
    MASK("蒙版", Icons.Filled.Brush),
    AI("AI", Icons.Filled.AutoAwesome)
}

/** Stable display order of [EditorToolsRow]; kept as a constant for tests. */
val EDITOR_TOOLS: List<EditorTool> = EditorTool.values().toList()

/** Per-tool availability and highlight state, computed by the screen. */
data class EditorToolAppearance(
    val enabled: Boolean = true,
    /** Amber tint when this tool already has an active, non-neutral edit. */
    val highlighted: Boolean = false,
    /** Always-amber accent for the AI tool. */
    val accent: Boolean = false
)

/**
 * A single row of fixed-width tool buttons that can be swiped horizontally.
 * [horizontalScroll] is what makes all seven tools reachable on narrow screens
 * without shrinking them into unreadable slivers.
 */
@Composable
fun EditorToolsRow(
    appearance: (EditorTool) -> EditorToolAppearance,
    onToolClick: (EditorTool) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("editor_tools_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EDITOR_TOOLS.forEach { tool ->
            val look = appearance(tool)
            val contentColor = when {
                look.accent || look.highlighted -> Amber
                else -> TextPrimary
            }
            OutlinedButton(
                onClick = { onToolClick(tool) },
                enabled = look.enabled,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                border = BorderStroke(
                    1.dp,
                    if (look.accent || look.highlighted) Amber.copy(alpha = 0.7f) else Outline
                ),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                modifier = Modifier
                    .width(78.dp)
                    .height(50.dp)
                    .testTag("editor_tool_${tool.name.lowercase()}")
            ) {
                Icon(
                    tool.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    tool.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The actions of the top-right "更多" menu, exposed as a [ColumnScope]
 * extension so it can be rendered both inside [EditorMoreMenu]'s
 * [DropdownMenu] and directly inside a plain Column in UI tests.
 *
 * "粘贴效果" is disabled (greyed out and unclickable) until at least one
 * "复制效果" has succeeded; the ViewModel enforces the same guard.
 *
 * 1.6.68: the single export item is split into two —
 * "导出当前照片" (only the photo currently previewed) and
 * "导出所有照片" (the whole selection, the pre-split behaviour).
 *
 * 1.6.89: 新增「隐藏/显示直方图」开关，[histogramVisible] 决定文案与图标，
 * 点击回调 [onToggleHistogram] 由屏幕切换浮窗可见性并持久化偏好。
 */
@Composable
fun ColumnScope.EditorMoreMenuItems(
    canPaste: Boolean,
    canExport: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onExport: () -> Unit,
    onExportCurrent: () -> Unit = {},
    histogramVisible: Boolean = true,
    onToggleHistogram: () -> Unit = {}
) {
    val dimmed = TextSecondary.copy(alpha = 0.45f)
    DropdownMenuItem(
        text = { Text("复制效果", color = TextPrimary) },
        leadingIcon = {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = TextPrimary)
        },
        onClick = onCopy,
        modifier = Modifier.testTag("editor_more_copy")
    )
    DropdownMenuItem(
        text = { Text("粘贴效果", color = if (canPaste) TextPrimary else dimmed) },
        leadingIcon = {
            Icon(
                Icons.Filled.ContentPaste,
                contentDescription = null,
                tint = if (canPaste) TextPrimary else dimmed
            )
        },
        enabled = canPaste,
        onClick = onPaste,
        modifier = Modifier.testTag("editor_more_paste")
    )
    DropdownMenuItem(
        text = { Text("导出当前照片", color = if (canExport) Amber else dimmed) },
        leadingIcon = {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = if (canExport) Amber else dimmed
            )
        },
        enabled = canExport,
        onClick = onExportCurrent,
        modifier = Modifier.testTag("editor_more_export_current")
    )
    DropdownMenuItem(
        text = { Text("导出所有照片", color = if (canExport) Amber else dimmed) },
        leadingIcon = {
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                tint = if (canExport) Amber else dimmed
            )
        },
        enabled = canExport,
        onClick = onExport,
        modifier = Modifier.testTag("editor_more_export")
    )
    // 1.6.89：直方图浮窗的显示/隐藏开关。文案与图标随当前可见性切换。
    DropdownMenuItem(
        text = {
            Text(
                if (histogramVisible) "隐藏直方图" else "显示直方图",
                color = TextPrimary
            )
        },
        leadingIcon = {
            Icon(
                if (histogramVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = null,
                tint = TextPrimary
            )
        },
        onClick = onToggleHistogram,
        modifier = Modifier.testTag("editor_more_histogram")
    )
}

/** Three-dot overflow button in the editor's top-right corner. */
@Composable
fun EditorMoreMenu(
    canPaste: Boolean,
    canExport: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onExport: () -> Unit,
    onExportCurrent: () -> Unit = {},
    histogramVisible: Boolean = true,
    onToggleHistogram: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("editor_more_button")
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = TextPrimary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("editor_more_menu")
        ) {
            EditorMoreMenuItems(
                canPaste = canPaste,
                canExport = canExport,
                onCopy = { expanded = false; onCopy() },
                onPaste = { expanded = false; onPaste() },
                onExport = { expanded = false; onExport() },
                onExportCurrent = { expanded = false; onExportCurrent() },
                histogramVisible = histogramVisible,
                onToggleHistogram = { expanded = false; onToggleHistogram() }
            )
        }
    }
}
