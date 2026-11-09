package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Standalone FULL-SCREEN mask editor rendered INSIDE the main Compose tree
 * (not a separate Dialog window — a Dialog window would physically cover the
 * editor's top action bar and make it impossible to click through to it).
 *
 * The root is a bare transparent Box with NO scrim and NO pointer input on
 * its top strip. That strip (status bar + the 64dp TopAppBar) is deliberately
 * left empty, so the REAL top action bar above the preview — preset switch,
 * undo, redo, history — stays visible and directly clickable while masking.
 * No action buttons are duplicated inside this page.
 *
 * The photo gets essentially all the remaining space via [LocalMaskOverlay];
 * numeric parameters live in a drawer that is EXPANDED by default (so they
 * are never "missing") and can be collapsed to enlarge the preview.
 *
 * [initialAdjustments] is the LIVE external state. A local draft keeps slider
 * drags and mask gestures from re-rendering on every frame, but follows the
 * external state whenever a global action (undo / redo / preset / history)
 * changes it — keeping this page in sync with the top action bar.
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
    val draftState = remember { mutableStateOf(initialAdjustments.normalized()) }
    var draft by draftState
    val draftRef = rememberUpdatedState(draft)
    LaunchedEffect(initialAdjustments) {
        val external = initialAdjustments.normalized()
        if (external != draftRef.value) draft = external
    }

    var selectedLayer by remember { mutableStateOf(0) }
    // Parameters are a first-class part of the editor: visible by default so
    // they can no longer be "missing", and collapsible to free the preview.
    var paramsExpanded by remember { mutableStateOf(true) }

    val screenHeight = LocalConfiguration.current.screenHeightDp
    val paramsHeight = (screenHeight * 0.26f).dp
    val aspect = remember(previewBitmap, sourceBitmap) {
        val b = previewBitmap ?: sourceBitmap
        if (b != null && !b.isRecycled && b.height > 0) b.width.toFloat() / b.height else 1f
    }

    val layers = draft.local.layers
    val index = selectedLayer.coerceIn(0, (layers.size - 1).coerceAtLeast(0))

    fun onChangeLocal(next: LocalAdjustments, field: String) {
        draft = draft.copy(local = next).normalized()
    }

    fun commit(field: String) {
        onPreview(draftState.value, field)
    }

    // In-tree full-screen page: the back key leaves it (and rolls back),
    // since there is no Dialog window to supply onDismissRequest anymore.
    BackHandler(onBack = onCancel)

    Box(
        Modifier
            .fillMaxSize()
            .testTag("mask_editor_root")
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 64.dp)
                .background(Ink)
                .navigationBarsPadding()
                .testTag("mask_editor_content")
        ) {
            // Slim title bar: cancel / title + hint / done. The GLOBAL action
            // bar (preset / undo / redo / history) is the real TopAppBar
            // visible in the transparent strip directly above this row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
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
                        "在照片上操作选区 · 下方调节参数",
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

            LocalMaskOverlay(
                local = draft.local,
                selectedLayer = index,
                onSelectLayer = { selectedLayer = it },
                onChange = { next, field -> onChangeLocal(next, field) },
                onFinished = { field -> commit(field) },
                enabled = true,
                displayBitmap = previewBitmap ?: sourceBitmap,
                // Region recognition + colour picking must read the UNEDITED
                // original, not the graded preview, so the sample never drifts.
                sourceBitmap = sourceBitmap ?: previewBitmap,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("mask_editor_preview_host")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkElevated)
                    .clickable { paramsExpanded = !paramsExpanded }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
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
                    Text(
                        "参数调节 · 数值作用于选中的蒙版图层",
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    LocalAdjustmentSection(
                        local = draft.local,
                        selectedLayer = index,
                        onSelectLayer = { selectedLayer = it },
                        onChange = { next, field -> onChangeLocal(next, field) },
                        onFinished = { field -> commit(field) },
                        enabled = true,
                        aspect = aspect
                    )
                }
            }
        }
    }
}
