package com.fujikon.app.ui.components

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * 日期快速定位轴的纯逻辑回归：跨月分组、标签、拖动比例→照片下标映射。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DateScrubberTest {

    private fun photo(id: Long, dateMillis: Long) = com.fujikon.app.data.PhotoItem(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "IMG_$id.jpg",
        dateTaken = dateMillis,
        dateAdded = dateMillis / 1000L,
        bucketId = "camera",
        bucketName = "Camera",
        width = 1200,
        height = 800,
        mimeType = "image/jpeg",
        size = 1024L
    )

    private fun millis(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, 12, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun groupsPhotosByMonthInDescendingOrder() {
        // 3 张 5 月 + 2 张 4 月 + 1 张 3 月（已按日期倒序）。
        val photos = listOf(
            photo(1, millis(2024, 5, 20)),
            photo(2, millis(2024, 5, 15)),
            photo(3, millis(2024, 5, 2)),
            photo(4, millis(2024, 4, 28)),
            photo(5, millis(2024, 4, 3)),
            photo(6, millis(2024, 3, 11))
        )
        val ticks = buildDateTicks(photos, nowMillis = millis(2024, 5, 25))
        assertEquals(3, ticks.size)
        // 每个月的第一张位置：5月=0，4月=3，3月=5。
        assertEquals(listOf(0, 3, 5), ticks.map { it.startIndex })
    }

    @Test
    fun emptyAndSingleMonthCases() {
        assertTrue(buildDateTicks(emptyList(), 0L).isEmpty())
        val oneMonth = listOf(
            photo(1, millis(2024, 5, 20)),
            photo(2, millis(2024, 5, 2))
        )
        assertEquals(1, buildDateTicks(oneMonth, millis(2024, 5, 25)).size)
    }

    @Test
    fun labelUsesYearForOlderMonths() {
        // 今年只显示「M月」，往年显示「YY/M」。
        val thisYear = formatDateTickLabel(millis(2024, 5, 1), millis(2024, 5, 25))
        val lastYear = formatDateTickLabel(millis(2023, 12, 1), millis(2024, 5, 25))
        assertEquals("5月", thisYear)
        assertEquals("23/12", lastYear)
    }

    @Test
    fun fractionMapsToPhotoIndexLinearly() {
        assertEquals(0, fractionToPhotoIndex(0f, 100))
        assertEquals(99, fractionToPhotoIndex(1f, 100))
        assertEquals(50, fractionToPhotoIndex(0.5f, 101))
        // 越界钳制。
        assertEquals(0, fractionToPhotoIndex(-0.5f, 100))
        assertEquals(99, fractionToPhotoIndex(2f, 100))
        // 空列表安全。
        assertEquals(0, fractionToPhotoIndex(0.5f, 0))
    }

    @Test
    fun tickForIndexReturnsContainingSegment() {
        val ticks = listOf(
            DateTick(0, "5月", 0L),
            DateTick(3, "4月", 0L),
            DateTick(5, "3月", 0L)
        )
        assertEquals("5月", tickForPhotoIndex(ticks, 0)!!.label)
        assertEquals("5月", tickForPhotoIndex(ticks, 2)!!.label)
        assertEquals("4月", tickForPhotoIndex(ticks, 3)!!.label)
        assertEquals("4月", tickForPhotoIndex(ticks, 4)!!.label)
        assertEquals("3月", tickForPhotoIndex(ticks, 5)!!.label)
        assertEquals(null, tickForPhotoIndex(emptyList(), 0))
    }

    @Test
    fun fallsBackToDateAddedWhenTakenInvalid() {
        // PhotoItem.dateAdded 已是毫秒（Repository 读取时 ×1000），此处直接传毫秒。
        val p = photo(1, 0L).copy(dateTaken = 0L, dateAdded = millis(2024, 6, 1))
        val ticks = buildDateTicks(listOf(p), nowMillis = millis(2024, 6, 10))
        assertEquals(1, ticks.size)
        assertEquals("6月", ticks.first().label)
    }
}
