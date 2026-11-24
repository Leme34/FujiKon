package com.fujikon.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.TextPrimary
import java.util.Calendar
import kotlin.math.max

/**
 * 照片日期快速定位轴的纯逻辑（不含 Compose，便于单元测试）。
 *
 * 照片列表按 dateAdded 倒序排列；这里把连续的同「年月日」照片聚成一个刻度，
 * 刻度的 startIndex 是该日期段第一张照片在列表中的下标。拖动轴时按列表位置
 * 比例映射到目标照片下标，再据此滚动网格——因此跳转位置与拖动位置线性对应。
 * 1.6.82：刻度由「年月」细化到「年月日」，可精确定位到某一天。
 */
data class DateTick(val startIndex: Int, val label: String, val millis: Long)

/**
 * 把日期标签格式化为紧凑形式：今年显示「M/d」（如 11/24），往年显示「YY/M/d」，
 * 保证跨年时用户能分辨年份，同时能一眼看出具体某一天。
 */
fun formatDateTickLabel(millis: Long, nowMillis: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val nowCal = Calendar.getInstance()
    nowCal.timeInMillis = nowMillis
    val nowYear = nowCal.get(Calendar.YEAR)
    return if (year == nowYear) "$month/$day" else "${year % 100}/$month/$day"
}

/**
 * 从照片列表构建日期刻度。photos 应已按日期倒序；结果按列表顺序（新→旧）。
 * 仅当「年月日」发生变化时生成一个新刻度，startIndex 指向该天第一张照片。
 * 若照片的日期无效（<=0），退化为用 dateAdded。
 */
fun buildDateTicks(photos: List<PhotoItem>, nowMillis: Long): List<DateTick> {
    if (photos.isEmpty()) return emptyList()
    val ticks = mutableListOf<DateTick>()
    var lastKey: String? = null
    for (i in photos.indices) {
        val raw = photos[i].dateTaken.takeIf { it > 0 } ?: photos[i].dateAdded
        val cal = Calendar.getInstance()
        cal.timeInMillis = raw
        val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        if (key != lastKey) {
            ticks.add(DateTick(startIndex = i, label = formatDateTickLabel(raw, nowMillis), millis = raw))
            lastKey = key
        }
    }
    return ticks
}

/**
 * 拖动比例（0=顶部最新，1=底部最旧）映射到目标照片下标。
 * photoCount 为列表长度；结果钳制在 [0, photoCount-1]。
 */
fun fractionToPhotoIndex(fraction: Float, photoCount: Int): Int {
    if (photoCount <= 0) return 0
    val f = fraction.coerceIn(0f, 1f)
    return (f * (photoCount - 1)).toInt().coerceIn(0, photoCount - 1)
}

/**
 * 给定目标照片下标，返回当前所处的日期刻度（用于拖动时显示的日期气泡，
 * 以及松手时吸附到该天首张照片）。取 startIndex <= index 的最后一个刻度；
 * 若 index 在第一个刻度之前则取第一个。
 */
fun tickForPhotoIndex(ticks: List<DateTick>, index: Int): DateTick? {
    if (ticks.isEmpty()) return null
    var result = ticks.first()
    for (tick in ticks) {
        if (tick.startIndex <= index) result = tick else break
    }
    return result
}

/**
 * 照片选择界面右侧的可拖动日期轴。
 *
 * [photos] 为当前可见照片（已按日期倒序），[ticks] 由 [buildDateTicks] 生成。
 * 用户按下或沿轴拖动时，按触点 Y 比例映射到目标照片下标并通过
 * [onTargetIndex] 回调；调用方据此滚动网格。拖动中显示当前「具体日期」气泡；
 * 松手时吸附到该天第一张照片，便于按某一天快速定位。
 * 轨道按照片实际位置比例绘制刻度，因此拖动位置与滚动进度线性对应。
 */
@Composable
fun DateScrubber(
    photos: List<PhotoItem>,
    ticks: List<DateTick>,
    modifier: Modifier = Modifier,
    onTargetIndex: (Int) -> Unit
) {
    if (photos.isEmpty() || ticks.isEmpty()) return
    var dragging by remember { mutableStateOf(false) }
    var activeFraction by remember { mutableStateOf(0f) }

    val activeIndex = fractionToPhotoIndex(activeFraction, photos.size)
    val activeTick = tickForPhotoIndex(ticks, activeIndex)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(52.dp)
            .pointerInput(photos.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    activeFraction = (down.position.y / h).coerceIn(0f, 1f)
                    onTargetIndex(fractionToPhotoIndex(activeFraction, photos.size))
                    down.consume()
                    // 跟随手指 / 鼠标移动，直到抬起。
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.all { !it.pressed }) break
                        val y = event.changes.first().position.y
                        activeFraction = (y / h).coerceIn(0f, 1f)
                        onTargetIndex(fractionToPhotoIndex(activeFraction, photos.size))
                        event.changes.forEach { it.consume() }
                    }
                    // 松手吸附到当天的第一张照片，定位到「某一天」而不是含糊的
                    // 相邻照片位置；拖动过程中仍保持连续滚动。
                    val settled = fractionToPhotoIndex(activeFraction, photos.size)
                    onTargetIndex(tickForPhotoIndex(ticks, settled)?.startIndex ?: settled)
                    dragging = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackX = size.width * 0.72f
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val usable = (bottom - top).coerceAtLeast(1f)
            // 轨道底线。
            drawLine(
                color = CardHigh,
                start = Offset(trackX, top),
                end = Offset(trackX, bottom),
                strokeWidth = 3.dp.toPx()
            )
            // 每天一个刻度点，位置按该天首张照片的下标比例。照片跨很多天时
            // 均匀抽稀绘制，避免点密集成一条实线；拖动气泡仍逐天显示精确日期。
            val last = (photos.size - 1).coerceAtLeast(1)
            val stride = max(1, (ticks.size + 47) / 48)
            ticks.forEachIndexed { index, tick ->
                if (index % stride != 0 && index != ticks.lastIndex) return@forEachIndexed
                val f = tick.startIndex.toFloat() / last
                val y = top + usable * f
                drawCircle(
                    color = Color(0xFF6E7078),
                    radius = if (stride > 1) 1.5f.dp.toPx() else 2.dp.toPx(),
                    center = Offset(trackX, y)
                )
            }
            // 当前手柄：与拖动位置对应。
            val handleY = top + usable * activeFraction
            if (dragging) {
                drawCircle(
                    color = Amber.copy(alpha = 0.25f),
                    radius = 12.dp.toPx(),
                    center = Offset(trackX, handleY)
                )
            }
            drawCircle(
                color = Amber,
                radius = if (dragging) 7.dp.toPx() else 5.dp.toPx(),
                center = Offset(trackX, handleY)
            )
        }
        // 拖动时在左侧显示当前「具体日期」气泡。
        if (dragging && activeTick != null) {
            Text(
                text = activeTick.label,
                color = TextPrimary,
                fontSize = if (activeTick.label.length > 5) 9.sp else 10.sp,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = 14.dp)
            )
        }
    }
}
