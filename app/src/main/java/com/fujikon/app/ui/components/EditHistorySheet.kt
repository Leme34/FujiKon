package com.fujikon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.EditHistory
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightroom-style history panel for the CURRENT photo only.
 *
 * Each selected photo owns an independent stack, so switching photos swaps in
 * that photo's own steps. Every step listed here belongs to the photo being
 * previewed; tapping a row restores that state without navigating away and
 * without touching any other photo.
 */
@Composable
fun EditHistorySheet(
    entries: List<EditHistory.Entry>,
    currentIndex: Int,
    photos: List<PhotoItem>,
    canUndo: Boolean,
    canRedo: Boolean,
    onSelect: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    EditorBottomPanel(
        onDismiss = onDismiss,
        testTag = "edit_history_panel"
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = Amber)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前照片 · 历史步骤", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Text(
                        "每张照片的操作历史相互独立；点击任意步骤即可回退",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "上一步",
                        tint = if (canUndo) TextPrimary else TextSecondary.copy(alpha = 0.4f)
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "下一步",
                        tint = if (canRedo) TextPrimary else TextSecondary.copy(alpha = 0.4f)
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("edit_history_close")) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = TextPrimary)
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                itemsIndexed(
                    items = entries.asReversed(),
                    key = { _, entry -> entry.id }
                ) { position, entry ->
                    val index = entries.lastIndex - position
                    val isCurrent = index == currentIndex
                    val isFuture = index > currentIndex
                    val photoNumber = photos.indexOfFirst { it.id == entry.snapshot.photoId }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) Amber.copy(alpha = 0.16f)
                                else Card.copy(alpha = if (isFuture) 0.24f else 0.62f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isCurrent) Icons.Filled.CheckCircle
                            else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isCurrent) Amber else TextSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    isCurrent -> TextPrimary
                                    isFuture -> TextSecondary.copy(alpha = 0.55f)
                                    else -> TextPrimary
                                },
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    timeFormat.format(Date(entry.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary.copy(alpha = 0.8f)
                                )
                                if (photos.size > 1 && photoNumber >= 0) {
                                    Text(
                                        "  ·  第 ${photoNumber + 1} 张",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        if (isCurrent) {
                            Box(
                                Modifier
                                    .background(Amber, RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.fujikon.app.ui.theme.Ink,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            TextButton(onClick = { onSelect(index) }) {
                                Text(
                                    if (isFuture) "前往" else "回到此步",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
