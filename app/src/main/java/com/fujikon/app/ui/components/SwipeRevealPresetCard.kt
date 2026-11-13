package com.fujikon.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.render.PresetThumbnailStore
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Danger
import com.fujikon.app.ui.theme.Success
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「我的预设」卡片：向左滑动露出操作区，点击按钮才真正执行删除 / 恢复。
 *
 * 采用 reveal（滑开后停住）而不是 dismiss（滑到底直接删除），因为需求要求
 * 「显示删除按钮，点击删除」——避免误滑即删。手势与前景的点击在同一棵
 * pointer input 里协调：一旦确认是水平拖动就消费事件，且展开状态下点卡片
 * 只收起、不进入详情页。
 */
@Composable
fun SwipeRevealPresetCard(
    preset: Preset,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时表示「已删除」视图：操作区同时提供「恢复」与「彻底删除」。 */
    onRestore: (() -> Unit)? = null,
    thumbnailStore: PresetThumbnailStore? = null,
    photo: PhotoItem? = null,
    /** 1.6.53：为 true 时缩略图固定用 4K 风景壁纸套预设，忽略 [photo]。 */
    wallpaper: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    deleted: Boolean = false,
    testTag: String? = null
) {
    val density = LocalDensity.current
    val actionWidthDp: Dp = if (onRestore != null) 176.dp else 88.dp
    val actionPx = with(density) { actionWidthDp.toPx() }

    val offset = remember(preset.id) { Animatable(0f) }
    // Animatable.value 读取内部 mutableState，位移变化会触发重组/重新布局。
    val translationX = offset.value
    var revealed by remember(preset.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 未滑开时操作区根本不进入组合树，语义树上也不存在「删除」按钮，
    // 保证删除动作只能由一次真实的左滑 + 点击触发。
    val actionsVisible = revealed || translationX < -1f

    val rootModifier = modifier
        .clip(RoundedCornerShape(20.dp))
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
        .pointerInput(preset.id, actionPx) {
            // 用普通变量累计位移：detectHorizontalDragGestures 的回调不是
            // suspend 函数，Animatable 的挂起方法必须通过 scope.launch 调用；
            // 展开判定只依赖累计值，不受协程调度影响。
            var rawOffset = 0f
            detectHorizontalDragGestures(
                onDragStart = { rawOffset = offset.value },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    rawOffset = (rawOffset + dragAmount).coerceIn(-actionPx, 0f)
                    scope.launch { offset.snapTo(rawOffset) }
                },
                onDragEnd = {
                    val open = rawOffset < -actionPx * 0.45f
                    revealed = open
                    scope.launch { offset.animateTo(if (open) -actionPx else 0f, tween(180)) }
                },
                onDragCancel = {
                    scope.launch {
                        offset.animateTo(if (revealed) -actionPx else 0f, tween(180))
                    }
                }
            )
        }

    Box(rootModifier) {
        if (actionsVisible) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(CardHigh, RoundedCornerShape(20.dp)),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onRestore != null) {
                    SwipeActionButton(
                        label = "恢复",
                        icon = Icons.Filled.Restore,
                        tint = Success,
                        tag = "preset_swipe_restore_${preset.id}",
                        onClick = onRestore
                    )
                }
                SwipeActionButton(
                    label = if (deleted) "彻底删除" else "删除",
                    icon = Icons.Filled.Delete,
                    tint = Danger,
                    tag = "preset_swipe_delete_${preset.id}",
                    onClick = onDelete
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(translationX.roundToInt(), 0) }
        ) {
            PresetCard(
                preset = preset,
                onClick = {
                    if (revealed) {
                        revealed = false
                        scope.launch { offset.animateTo(0f, tween(180)) }
                    } else {
                        onClick()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onFavoriteClick = onFavoriteClick,
                thumbnailStore = thumbnailStore,
                photo = photo,
                wallpaper = wallpaper,
                deleted = deleted,
                onRestoreClick = onRestore
            )
        }
    }
}

@Composable
private fun SwipeActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
