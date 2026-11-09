package com.fujikon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.TextPrimary

/** Copy kept as constants so the UI and its tests can't drift apart. */
const val SWIPE_HINT_TITLE = "右滑更精彩"
const val SWIPE_HINT_BODY = "底部功能按钮可左右滑动，试试右滑发现更多工具"
const val SWIPE_HINT_DISMISS = "知道了"

/**
 * One-shot decision for the "bottom tools can be swiped" hint (1.6.31).
 *
 * The floating card is shown only when the user has NOT dismissed it yet AND
 * there is a rendered photo to look at — otherwise it would pop over an empty
 * loading / error screen. `seenSwipeHint` comes from persisted [AppSettings],
 * so once the user taps 「知道了」 it stays hidden forever.
 */
fun shouldShowSwipeHint(seenSwipeHint: Boolean, hasRenderedPhoto: Boolean): Boolean =
    !seenSwipeHint && hasRenderedPhoto

/**
 * Small floating hint anchored at the bottom-right of the editor preview,
 * just above the horizontally scrollable tool row. Tapping 「知道了」 invokes
 * [onDismiss]; the caller persists the "seen" flag so it never appears again.
 */
@Composable
fun EditorSwipeHintCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(max = 210.dp)
            .testTag("editor_swipe_hint"),
        color = CardHigh.copy(alpha = 0.96f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.55f)),
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(16.dp)
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Amber.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    SWIPE_HINT_TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Amber,
                    modifier = Modifier.testTag("editor_swipe_hint_title")
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                SWIPE_HINT_BODY,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                modifier = Modifier.testTag("editor_swipe_hint_body")
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("editor_swipe_hint_dismiss")
            ) {
                Text(
                    SWIPE_HINT_DISMISS,
                    color = Amber,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
