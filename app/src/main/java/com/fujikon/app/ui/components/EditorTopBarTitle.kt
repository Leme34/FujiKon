package com.fujikon.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary

/**
 * Editor top-app-bar title: line 1 is the "current / total" photo counter,
 * line 2 is the file name.
 *
 * 1.6.30 fix: Material3's small [androidx.compose.material3.TopAppBar] is only
 * 64dp tall, and the five right-hand actions (undo / redo / history / preset
 * name / overflow) squeeze the title slot very narrow. The old inline title
 * used `titleMedium` with no line limit, so "1 / 3" wrapped onto two lines;
 * the two-line Column became three lines, exceeded the bar height and spilled
 * vertically ("1" floated into the status-bar area, "/" dropped below the
 * icons). The counter is now a single, non-wrapping line measured at its
 * content width so it stays complete and inside the bar on any narrow screen.
 */
@Composable
fun EditorTopBarTitle(
    index: Int,
    photoCount: Int,
    photoName: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().testTag("editor_top_bar_title")) {
        Text(
            text = editorPhotoCounter(index, photoCount),
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            // Measure at the text's own width instead of the (possibly tiny)
            // squeezed title slot: the counter must never wrap or be clipped.
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .testTag("editor_photo_counter")
        )
        Text(
            text = photoName ?: "选择照片",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("editor_photo_name")
        )
    }
}

/** "查看" for an empty selection, otherwise a 1-based "current / total". */
fun editorPhotoCounter(index: Int, photoCount: Int): String =
    if (photoCount <= 0) "查看" else "${(index + 1).coerceAtLeast(1)} / $photoCount"
