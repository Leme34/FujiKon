package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.LocalAdjustments
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary

/**
 * Standalone FULL-SCREEN mask editor.
 *
 * Previously masking lived inside the adjustments panel as a tab plus a
 * cramped half-screen overlay above it (44% of the screen, minus status bar,
 * top action bar and the toolbar). That left far too little room to position
 * a mask accurately. This page is a first-class editor on the same level as
 * crop / lens-blur: it fills the whole screen, so the photo gets essentially
 * all the space except a slim title bar, the mask toolbar and an optional,
 * collapsible parameters drawer (collapsed by default).
 *
 * All mask placement / shaping / layer / brush operations happen directly on
 * the photo via [LocalMaskOverlay]; the drawer only holds numeric parameters
 * via [LocalAdjustmentSection].
 */
@Composable
fun MaskEditorOverlay(
    initialAdjustments: EditAdjustments,
    sourceBitmap: Bitmap?,
    previewBitmap: Bitmap?,
    rendering: Boolean,
    onPreview: (EditAdjustments, String?) -> Unit,
    onApply: (EditAdjustments) -> Unit,
    onCancel: () -> Unit
) {
    // A local draft so slider drags / mask gestures never re-render on every
    // frame: the host renders once per committed change (same contract as the
    // adjustments panel).
    val draftState = remember { mutableStateOf(initialAdjustments.normalized()) }
    var draft by draftState
    var selectedLayer by remember { mutableStateOf(0) }
    var paramsExpanded by remember { mutableStateOf(false) }

    val screenHeight = LocalConfiguration.current.screenHeightDp
    val paramsHeight = (screenHeight * 0.38f).dp

    val layers = draft.local.layers
    val index = selectedLayer.coerceIn(0, (layers.size - 1).coerceAtLeast(0))

    fun onChangeLocal(next: LocalAdjustments, field: String) {
        draft = draft.copy(local = next).normalized()
    }

    fun commit(field: String) {
        onPreview(draftState.value, field)
    }

    // Back / outside-tap is handled by the host Dialog's onDismissRequest,
    // exactly like the crop and lens-blur full-screen editors.

    Surface(
        modifier = Modifier.fillMaxSize().testTag("mask_editor_root"),
        color = Ink
    ) {
        Column(Modifier.fillMaxSize()) {
            // Slim title bar: cancel / title + hint / done.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("mask_editor_close")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "取消蒙版", tint = TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "蒙版",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        "全屏编辑 · 在照片上点按定位 / 拖动 / 画笔涂抹 · 底部切换形状与图层",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                if (rendering) {
                    CircularProgressIndicator(
                        color = Amber,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(16.dp)
                    )
                }
                TextButton(
                    onClick = { onApply(draftState.value) },
                    modifier = Modifier.testTag("mask_editor_done")
                ) {
                    Text("完成", color = Amber, fontWeight = FontWeight.Bold)
                }
            }

            // The photo + mask interaction + shape/layer toolbar fill all the
            // remaining vertical space. Collapsing the drawer below makes this
            // as large as the screen allows.
            LocalMaskOverlay(
                local = draft.local,
                selectedLayer = index,
                onSelectLayer = { selectedLayer = it },
                onChange = { next, field -> onChangeLocal(next, field) },
                onFinished = { field -> commit(field) },
                enabled = true,
                displayBitmap = previewBitmap ?: sourceBitmap,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("mask_editor_preview_host")
            )

            // Collapsible numeric-parameters drawer. Collapsed by default so
            // the preview stays big; one tap reveals the sliders.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkElevated)
                    .clickable { paramsExpanded = !paramsExpanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("mask_params_toggle"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (paramsExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowUp
                    },
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (paramsExpanded) "收起参数" else "展开参数调节",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (layers.isEmpty()) "还没有蒙版" else "蒙版 ${index + 1}/${layers.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            if (paramsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(paramsHeight)
                        .background(InkElevated)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .testTag("mask_params_panel")
                ) {
                    LocalAdjustmentSection(
                        local = draft.local,
                        selectedLayer = index,
                        onSelectLayer = { selectedLayer = it },
                        onChange = { next, field -> onChangeLocal(next, field) },
                        onFinished = { field -> commit(field) },
                        enabled = true
                    )
                }
            }
        }
    }
}
